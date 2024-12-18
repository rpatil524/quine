package com.thatdot.quine.app

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Future
import scala.language.implicitConversions

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.stream.{KillSwitches, UniqueKillSwitch}

import cats.syntax.either._
import io.circe.Json

import com.thatdot.quine.app.outputs.{
  ConsoleLoggingOutput,
  CypherQueryOutput,
  DropOutput,
  FileOutput,
  KafkaOutput,
  KinesisOutput,
  PostToEndpointOutput,
  SlackOutput,
  SnsOutput,
}
import com.thatdot.quine.app.serialization.{ConversionFailure, ProtobufSchemaCache, QuineValueToProtobuf}
import com.thatdot.quine.graph.MasterStream.SqResultsExecToken
import com.thatdot.quine.graph.{
  BaseGraph,
  CypherOpsGraph,
  NamespaceId,
  StandingQueryResult,
  StandingQueryResultStructure,
}
import com.thatdot.quine.model.{QuineIdProvider, QuineValue}
import com.thatdot.quine.routes.{OutputFormat, StandingQueryResultOutputUserDef}
import com.thatdot.quine.util.Log._
import com.thatdot.quine.util.Log.implicits._
import com.thatdot.quine.util.StringInput.filenameOrUrl

object StandingQueryResultOutput extends LazySafeLogging {

  import StandingQueryResultOutputUserDef._

  private def resultHandlingFlow(
    name: String,
    inNamespace: NamespaceId,
    output: StandingQueryResultOutputUserDef,
    graph: CypherOpsGraph,
  )(implicit
    protobufSchemaCache: ProtobufSchemaCache,
    logConfig: LogConfig,
  ): Flow[StandingQueryResult, SqResultsExecToken, NotUsed] = {
    val execToken = SqResultsExecToken(s"SQ: $name in: $inNamespace")
    output match {
      case Drop => DropOutput.flow(name, inNamespace, output, graph)
      case iq: InternalQueue =>
        Flow[StandingQueryResult].map { r =>
          iq.results
            .asInstanceOf[AtomicReference[Vector[StandingQueryResult]]] // ugh. gross.
            .getAndUpdate(results => results :+ r)
          execToken
        // TODO: Note that enqueuing a result does not properly respect the spirit of `execToken` in that the work
        //       of processing the result in the queue has not been done before emitting the token. But this
        //       `InternalQueue` is only meant for internal testing.
        }
      case webhookConfig: PostToEndpoint =>
        new PostToEndpointOutput(webhookConfig).flow(name, inNamespace, output, graph)

      case kafkaSettings: WriteToKafka =>
        new KafkaOutput(kafkaSettings).flow(name, inNamespace, output, graph)

      case kinesisSettings: WriteToKinesis =>
        new KinesisOutput(kinesisSettings).flow(name, inNamespace, output, graph)

      case snsSettings: WriteToSNS =>
        new SnsOutput(snsSettings).flow(name, inNamespace, output, graph)

      case loggingConfig: PrintToStandardOut =>
        new ConsoleLoggingOutput(loggingConfig).flow(name, inNamespace, output, graph)

      case fileConfig: WriteToFile =>
        new FileOutput(fileConfig).flow(name, inNamespace, output, graph)

      case slackSettings: PostToSlack =>
        new SlackOutput(slackSettings).flow(name, inNamespace, output, graph)

      case query: CypherQuery =>
        // Closures can't have implicit arguments in scala 2.13, so flatten the arguments list
        def createRecursiveOutput(
          name: String,
          inNamespace: NamespaceId,
          output: StandingQueryResultOutputUserDef,
          graph: CypherOpsGraph,
          protobufSchemaCache: ProtobufSchemaCache,
          logConfig: LogConfig,
        ): Flow[StandingQueryResult, SqResultsExecToken, NotUsed] =
          resultHandlingFlow(name, inNamespace, output, graph)(protobufSchemaCache, logConfig)

        new CypherQueryOutput(query, createRecursiveOutput).flow(name, inNamespace, output, graph)
    }
  }.named(s"sq-output-$name")

  /** Construct a destination to which results are output. Results will flow through one or more
    * chained [[resultHandlingFlow]]s before emitting a completion token to the master stream
    *
    * @param name        name of the Standing Query Output
    * @param inNamespace the namespace running this standing query
    * @param output      configuration for handling the results
    * @param graph       reference to the graph
    */
  def resultHandlingSink(
    name: String,
    inNamespace: NamespaceId,
    output: StandingQueryResultOutputUserDef,
    graph: CypherOpsGraph,
  )(implicit
    protobufSchemaCache: ProtobufSchemaCache,
    logConfig: LogConfig,
  ): Sink[StandingQueryResult, UniqueKillSwitch] =
    Flow[StandingQueryResult]
      .viaMat(KillSwitches.single)(Keep.right)
      .via(resultHandlingFlow(name, inNamespace, output, graph))
      .to(graph.masterStream.standingOutputsCompletionSink)

  def serialized(
    name: String,
    format: OutputFormat,
    graph: BaseGraph,
    structure: StandingQueryResultStructure,
  )(implicit
    protobufSchemaCache: ProtobufSchemaCache,
    logConfig: LogConfig,
  ): Flow[StandingQueryResult, Array[Byte], NotUsed] =
    format match {
      case OutputFormat.JSON =>
        Flow[StandingQueryResult].map(_.toJson(structure)(graph.idProvider, logConfig).noSpaces.getBytes)
      case OutputFormat.Protobuf(schemaUrl, typeName) =>
        val serializer: Future[QuineValueToProtobuf] =
          protobufSchemaCache
            .getMessageDescriptor(filenameOrUrl(schemaUrl), typeName, flushOnFail = true)
            .map(new QuineValueToProtobuf(_))(
              graph.materializer.executionContext, // this is effectively part of stream materialization
            )
        val serializerRepeated: Source[QuineValueToProtobuf, Future[NotUsed]] = Source.futureSource(
          serializer
            .map(Source.repeat[QuineValueToProtobuf])(graph.materializer.executionContext),
        )
        Flow[StandingQueryResult]
          .filter(_.meta.isPositiveMatch)
          .zip(serializerRepeated)
          .map { case (result, serializer) =>
            serializer
              .toProtobufBytes(result.data)
              .leftMap { (err: ConversionFailure) =>
                logger.warn(
                  log"""On Standing Query output: ${Safe(name)}, can't serialize provided datum: $result
                       |to protobuf type: ${Safe(typeName)}. Skipping datum. Error: ${err.toString}
                       |""".cleanLines,
                )
              }
          }
          .collect { case Right(value) => value }
    }

  sealed abstract class SlackSerializable {
    def slackJson: String
    implicit def stringToJson(s: String): Json = Json.fromString(s)

  }

  object SlackSerializable {
    def apply(positiveOnly: Boolean, results: Seq[StandingQueryResult])(implicit
      idProvider: QuineIdProvider,
      logConfig: LogConfig,
    ): Option[SlackSerializable] = results match {
      case Seq() => None // no new results or cancellations
      case cancellations if positiveOnly && !cancellations.exists(_.meta.isPositiveMatch) =>
        None // no new results, only cancellations, and we're configured to drop cancellations
      case Seq(result) => // one new result or cancellations
        if (result.meta.isPositiveMatch) Some(NewResult(result.data))
        else if (!positiveOnly) Some(CancelledResult(result.data))
        else None
      case _ => // multiple results (but maybe not all valid given `positiveOnly`)
        val (positiveResults, cancellations) = results.partition(_.meta.isPositiveMatch)

        if (positiveOnly && positiveResults.length == 1) {
          val singleResult = positiveResults.head
          Some(NewResult(singleResult.data))
        } else if (!positiveOnly && positiveResults.isEmpty && cancellations.length == 1) {
          Some(CancelledResult(cancellations.head.data))
        } else if (positiveOnly && positiveResults.nonEmpty) {
          Some(MultipleUpdates(positiveResults, Seq.empty))
        } else if (positiveResults.nonEmpty || cancellations.nonEmpty) {
          Some(MultipleUpdates(positiveResults, cancellations))
        } else None
    }
  }

  final case class NewResult(data: Map[String, QuineValue])(implicit idProvider: QuineIdProvider, logConfig: LogConfig)
      extends SlackSerializable {
    // pretty-printed JSON representing `data`. Note that since this is used as a value in another JSON object, it
    // may not be perfectly escaped (for example, if the data contains a triple-backquote)
    private val dataPrettyJson: String =
      Json.fromFields(data.view.map { case (k, v) => (k, QuineValue.toJson(v)) }.toSeq).spaces2

    def slackBlock: Json =
      Json.obj("type" -> "section", "text" -> Json.obj("type" -> "mrkdwn", "text" -> s"```$dataPrettyJson```"))

    override def slackJson: String = Json
      .obj(
        "text" -> "New Standing Query Result",
        "blocks" -> Json.arr(
          Json
            .obj("type" -> "header", "text" -> Json.obj("type" -> "plain_text", "text" -> "New Standing Query Result")),
        ),
      )
      .noSpaces
  }

  final case class CancelledResult(data: Map[String, QuineValue])(implicit
    idProvider: QuineIdProvider,
    protected val logConfig: LogConfig,
  ) extends SlackSerializable {
    // pretty-printed JSON representing `data`. Note that since this is used as a value in another JSON object, it
    // may not be perfectly escaped (for example, if the data contains a triple-backquote)
    private val dataPrettyJson: String =
      Json.fromFields(data.view.map { case (k, v) => (k, QuineValue.toJson(v)) }.toSeq).spaces2

    def slackBlock: Json =
      Json.obj("type" -> "section", "text" -> Json.obj("type" -> "mrkdwn", "text" -> s"```$dataPrettyJson```"))

    override def slackJson: String = Json
      .obj(
        "text" -> "Standing Query Result Cancelled",
        "blocks" ->

        Json.arr(
          Json.obj(
            "type" -> "header",
            "text" -> Json.obj(
              "type" -> "plain_text",
              "text" -> "Standing Query Result Cancelled",
            ),
          ),
          slackBlock,
        ),
      )
      .noSpaces
  }

  final case class MultipleUpdates(newResults: Seq[StandingQueryResult], newCancellations: Seq[StandingQueryResult])(
    implicit
    idProvider: QuineIdProvider,
    logConfig: LogConfig,
  ) extends SlackSerializable {
    val newResultsBlocks: Vector[Json] = newResults match {
      case Seq() => Vector.empty
      case Seq(result) =>
        Vector(
          Json.obj(
            "type" -> "header",
            "text" -> Json.obj(
              "type" -> "plain_text",
              "text" -> "New Standing Query Result",
            ),
          ),
          NewResult(result.data).slackBlock,
        )
      case result +: remainingResults =>
        val excessMetaData = remainingResults.map(_.meta.toString) // TODO: what here since no result ID?
        val excessResultItems: Seq[String] =
          if (excessMetaData.length <= 10) excessMetaData
          else excessMetaData.take(9) :+ s"(${excessMetaData.length - 9} more)"

        Vector(
          Json.obj(
            "type" -> "header",
            "text" -> Json.obj(
              "type" -> "plain_text",
              "text" -> "New Standing Query Results",
            ),
          ),
          Json.obj(
            "type" -> "section",
            "text" -> Json.obj(
              "type" -> "mrkdwn",
              "text" -> "Latest result:",
            ),
          ),
        ) ++ (NewResult(result.data).slackBlock +: Vector(
          Json.obj(
            "type" -> "section",
            "text" -> Json.obj(
              "type" -> "mrkdwn",
              "text" -> "*Other New Result Meta Data:*",
            ),
          ),
          Json.obj(
            "type" -> "section",
            "fields" -> Json.fromValues(excessResultItems.map { str =>
              Json.obj(
                "type" -> "mrkdwn",
                "text" -> str,
              )
            }),
          ),
        ))
      case _ => throw new Exception(s"Unexpected value $newResults")
    }

    val cancellationBlocks: Vector[Json] = newCancellations match {
      case Seq() => Vector.empty
      case Seq(cancellation) =>
        Vector(
          Json.obj(
            "type" -> "header",
            "text" -> Json.obj(
              "type" -> "plain_text",
              "text" -> "Standing Query Result Cancelled",
            ),
          ),
          CancelledResult(cancellation.data).slackBlock,
        )
      case cancellations =>
        val cancelledMetaData = cancellations.map(_.meta.toString)
        val itemsCancelled: Seq[String] =
          if (cancellations.length <= 10) cancelledMetaData
          else cancelledMetaData.take(9) :+ s"(${cancellations.length - 9} more)"

        Vector(
          Json.obj(
            "type" -> "header",
            "text" -> Json.obj(
              "type" -> "plain_text",
              "text" -> "Standing Query Results Cancelled",
            ),
          ),
          Json.obj(
            "type" -> "section",
            "fields" -> Json.fromValues(itemsCancelled.map { str =>
              Json.obj(
                "type" -> "mrkdwn",
                "text" -> str,
              )
            }),
          ),
        )
    }
    override def slackJson: String = Json
      .obj(
        "text" -> "New Standing Query Updates",
        "blocks" -> Json.fromValues(newResultsBlocks ++ cancellationBlocks),
      )
      .noSpaces
  }

}
