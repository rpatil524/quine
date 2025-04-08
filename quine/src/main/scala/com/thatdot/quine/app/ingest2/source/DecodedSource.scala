package com.thatdot.quine.app.ingest2.source

import java.nio.charset.Charset

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Flow, Keep, RestartSource, Source, SourceWithContext}
import org.apache.pekko.{Done, NotUsed}

import cats.data.{Validated, ValidatedNel}
import cats.implicits.catsSyntaxValidatedId

import com.thatdot.common.logging.Log.{LazySafeLogging, LogConfig, Safe, SafeLoggableInterpolator}
import com.thatdot.quine.app.ingest.QuineIngestSource
import com.thatdot.quine.app.ingest.serialization.ContentDecoder
import com.thatdot.quine.app.ingest2.V2IngestEntities.{FileFormat, LogRecordErrorHandler, QuineIngestConfiguration}
import com.thatdot.quine.app.ingest2.codec.FrameDecoder
import com.thatdot.quine.app.ingest2.core.{DataFoldableFrom, DataFolderTo}
import com.thatdot.quine.app.ingest2.sources.S3Source.s3Source
import com.thatdot.quine.app.ingest2.sources.StandardInputSource.stdInSource
import com.thatdot.quine.app.ingest2.sources._
import com.thatdot.quine.app.routes.{IngestMeter, IngestMetered}
import com.thatdot.quine.app.serialization.{AvroSchemaCache, ProtobufSchemaCache}
import com.thatdot.quine.app.{ControlSwitches, ShutdownSwitch}
import com.thatdot.quine.graph.MasterStream.IngestSrcExecToken
import com.thatdot.quine.graph.metrics.implicits.TimeFuture
import com.thatdot.quine.graph.{CypherOpsGraph, NamespaceId}
import com.thatdot.quine.routes._
import com.thatdot.quine.util.{BaseError, SwitchMode, Valve, ValveSwitch}

/** A decoded source represents a source of interpreted values, that is, values that have
  * been translated from raw formats as supplied by their ingest source.
  */
// Note: The only reason the meter needs to be included here is to enable the creation of
// the quineIngestSource for v1 compatibility. If the meter is not used downstream from
// that it may not be needed here.
abstract class DecodedSource(val meter: IngestMeter) {
  type Decoded
  type Frame

  val foldable: DataFoldableFrom[Decoded]

  /** Stream of decoded values. This stream must already be metered. */
  def stream: Source[(Try[Decoded], Frame), ShutdownSwitch]

  def ack: Flow[Frame, Done, NotUsed] = Flow.fromFunction(_ => Done)

  def onTermination(): Unit = ()

  /** Generate an [[QuineIngestSource]] from this decoded stream Source[(Try[A], Frame), ShutdownSwitch]
    * into a Source[IngestSrcExecToken,NotUsed]
    * applying
    * RestartSettings | switch | valve | throttle | writeToGraph | Error Handler | Ack | Termination Hooks |
    *
    * return this source as an instance of a source that can be ingested into a Quine graph.
    */
  def toQuineIngestSource(
    ingestName: String,
    /* A step ingesting cypher (query,parameters) => graph.*/
    ingestQuery: QuineIngestQuery,
    cypherGraph: CypherOpsGraph,
    initialSwitchMode: SwitchMode = SwitchMode.Open,
    parallelism: Int = 1,
    maxPerSecond: Option[Int] = None,
  ): QuineIngestSource = new QuineIngestSource {

    val name: String = ingestName
    implicit val graph: CypherOpsGraph = cypherGraph

    override val meter: IngestMeter = DecodedSource.this.meter

    /** Fully assembled stream with the following operations applied:
      *
      * - restart settings
      * - shutdown switch
      * - valve
      * - throttle
      * - write to graph
      * - ack
      * - termination hook
      */
    override def stream(
      intoNamespace: NamespaceId,
      registerTerminationHooks: Future[Done] => Unit,
    ): Source[IngestSrcExecToken, NotUsed] = {

      val token = IngestSrcExecToken(name)
      // TODO error handler should be settable from a config, e.g. DeadLetterErrorHandler
      val decodeErrorHandler = LogRecordErrorHandler
      val ingestStream =
        DecodedSource.this.stream
          .wireTap(v => decodeErrorHandler.handleError[Decoded, Frame](v))
          .viaMat(Valve(initialSwitchMode))(Keep.both)
          .via(throttle(graph, maxPerSecond))

      val src: Source[IngestSrcExecToken, Unit] =
        SourceWithContext
          .fromTuples(ingestStream)
          // TODO this is slower than mapAsyncUnordered and is only necessary for Kafka acking case
          .mapAsync(parallelism) {
            case Success(t) =>
              graph.metrics
                .ingestQueryTimer(intoNamespace, name)
                .time(
                  ingestQuery.apply {
                    foldable.fold(t, DataFolderTo.cypherValueFolder)
                  },
                )
            case Failure(e) => Future.failed(e)
          }
          .asSource
          .map { case (_, envelope) => envelope }
          .via(ack)
          .map(_ => token)
          .watchTermination() { case ((a: ShutdownSwitch, b: Future[ValveSwitch]), c: Future[Done]) =>
            b.map(v => ControlSwitches(a, v, c))(ExecutionContext.parasitic)
          }
          .mapMaterializedValue(c => setControl(c, initialSwitchMode, registerTerminationHooks))
          .named(name)

      RestartSource.onFailuresWithBackoff(restartSettings) { () =>
        src
      }
    }
  }
}

object DecodedSource extends LazySafeLogging {

  /** Convenience to extract parallelism from v1 configuration types w/o altering v1 configurations */
  def parallelism(config: IngestStreamConfiguration): Int = config match {
    case k: KafkaIngest => k.parallelism
    case k: KinesisIngest => k.parallelism
    case s: ServerSentEventsIngest => s.parallelism
    case s: SQSIngest => s.writeParallelism
    case w: WebsocketSimpleStartupIngest => w.parallelism
    case f: FileIngest => f.parallelism
    case s: S3Ingest => s.parallelism
    case s: StandardInputIngest => s.parallelism
    case n: NumberIteratorIngest => n.parallelism
    case other => throw new NoSuchElementException(s"Ingest type $other not supported")

  }

  def quineIngestSource(
    name: String,
    settings: QuineIngestConfiguration,
    intoNamespace: NamespaceId,
    valveSwitchMode: SwitchMode,
  )(implicit
    graph: CypherOpsGraph,
    protobufSchemaCache: ProtobufSchemaCache,
    avroSchemaCache: AvroSchemaCache,
    logConfig: LogConfig,
  ): ValidatedNel[BaseError, QuineIngestSource] = {
    logger.info(safe"using v2 ingest to create ingest ${Safe(name)}")
    val meter = IngestMetered.ingestMeter(intoNamespace, name, graph.metrics)
    val query = QuineValueIngestQuery(settings, graph, intoNamespace)
    val decodedSource =
      DecodedSource(name, settings, meter, graph.system)(protobufSchemaCache, avroSchemaCache, logConfig)
    decodedSource.map(
      _.toQuineIngestSource(
        name,
        query,
        graph,
        valveSwitchMode,
        settings.parallelism,
        settings.maxPerSecond,
      ),
    )
  }

  // build from v1 configuration
  def apply(
    name: String,
    config: IngestStreamConfiguration,
    meter: IngestMeter,
    system: ActorSystem,
  )(implicit
    protobufCache: ProtobufSchemaCache,
    logConfig: LogConfig,
  ): ValidatedNel[BaseError, DecodedSource] = {

    config match {
      case KafkaIngest(
            format,
            topics,
            _,
            bootstrapServers,
            groupId,
            securityProtocol,
            maybeExplicitCommit,
            autoOffsetReset,
            kafkaProperties,
            endingOffset,
            _,
            recordDecoders,
          ) =>
        KafkaSource(
          topics,
          bootstrapServers,
          groupId.getOrElse(name),
          securityProtocol,
          maybeExplicitCommit,
          autoOffsetReset,
          kafkaProperties,
          endingOffset,
          recordDecoders.map(ContentDecoder(_)),
          meter,
          system,
        ).framedSource.map(_.toDecoded(FrameDecoder(format)))

      case FileIngest(
            format,
            path,
            encoding,
            _,
            maximumLineSize,
            startAtOffset,
            ingestLimit,
            _,
            fileIngestMode,
          ) =>
        FileSource.decodedSourceFromFileStream(
          FileSource.srcFromIngest(path, fileIngestMode),
          FileFormat(format),
          Charset.forName(encoding),
          maximumLineSize,
          IngestBounds(startAtOffset, ingestLimit),
          meter,
          Seq(), // V1 file ingest does not define recordDecoders
        )

      case S3Ingest(
            format,
            bucketName,
            key,
            encoding,
            _,
            credsOpt,
            maxLineSize,
            startAtOffset,
            ingestLimit,
            _,
          ) =>
        S3Source(
          FileFormat(format),
          bucketName,
          key,
          credsOpt,
          maxLineSize,
          Charset.forName(encoding),
          IngestBounds(startAtOffset, ingestLimit),
          meter,
          Seq(), // There is no compression support in the v1 configuration object.
        )(system).decodedSource

      case StandardInputIngest(
            format,
            encoding,
            _,
            maximumLineSize,
            _,
          ) =>
        StandardInputSource(
          FileFormat(format),
          maximumLineSize,
          Charset.forName(encoding),
          meter,
          Seq(),
        ).decodedSource

      case KinesisIngest(
            streamedRecordFormat,
            streamName,
            shardIds,
            _,
            creds,
            region,
            iteratorType,
            numRetries,
            _,
            recordEncodings,
            _,
          ) =>
        KinesisSource(
          streamName,
          shardIds,
          creds,
          region,
          iteratorType,
          numRetries, // TODO not currently supported
          meter,
          recordEncodings.map(ContentDecoder(_)),
        )(system.getDispatcher).framedSource.map(_.toDecoded(FrameDecoder(streamedRecordFormat)))
      case NumberIteratorIngest(_, startAtOffset, ingestLimit, _, _) =>
        Validated.valid(NumberIteratorSource(IngestBounds(startAtOffset, ingestLimit), meter).decodedSource)

      case SQSIngest(
            format,
            queueURL,
            readParallelism,
            _,
            credentialsOpt,
            regionOpt,
            deleteReadMessages,
            _,
            recordEncodings,
          ) =>
        SqsSource(
          queueURL,
          readParallelism,
          credentialsOpt,
          regionOpt,
          deleteReadMessages,
          meter,
          recordEncodings.map(ContentDecoder(_)),
        ).framedSource
          .map(_.toDecoded(FrameDecoder(format)))

      case ServerSentEventsIngest(
            format,
            url,
            _,
            _,
            recordEncodings,
          ) =>
        ServerSentEventSource(url, meter, recordEncodings.map(ContentDecoder(_)))(system).framedSource
          .map(_.toDecoded(FrameDecoder(format)))

      case WebsocketSimpleStartupIngest(
            format,
            wsUrl,
            initMessages,
            keepAliveProtocol,
            _,
            encoding,
          ) =>
        WebsocketSource(wsUrl, initMessages, keepAliveProtocol, Charset.forName(encoding), meter)(system).framedSource
          .map(_.toDecoded(FrameDecoder(format)))
    }
  }

  import com.thatdot.quine.app.ingest2.V2IngestEntities._

  //V2 configuration
  def apply(src: FramedSource, format: IngestFormat)(implicit
    protobufCache: ProtobufSchemaCache,
    avroCache: AvroSchemaCache,
  ): DecodedSource =
    src.toDecoded(FrameDecoder(format))

  // build from v2 configuration
  def apply(
    name: String,
    config: V2IngestConfiguration,
    meter: IngestMeter,
    system: ActorSystem,
  )(implicit
    protobufCache: ProtobufSchemaCache,
    avroCache: AvroSchemaCache,
    logConfig: LogConfig,
  ): ValidatedNel[BaseError, DecodedSource] =
    config.source match {
      case FileIngest(format, path, mode, maximumLineSize, startOffset, limit, charset, recordDecoders) =>
        FileSource.decodedSourceFromFileStream(
          FileSource.srcFromIngest(path, mode),
          format,
          charset,
          maximumLineSize.getOrElse(1000000), //TODO - To optional
          IngestBounds(startOffset, limit),
          meter,
          recordDecoders.map(ContentDecoder(_)),
        )

      case StdInputIngest(format, maximumLineSize, charset) =>
        FileSource.decodedSourceFromFileStream(
          stdInSource,
          format,
          charset,
          maximumLineSize.getOrElse(1000000), //TODO
          IngestBounds(),
          meter,
          Seq(),
        )

      case S3Ingest(format, bucketName, key, creds, maximumLineSize, startOffset, limit, charset, recordDecoders) =>
        FileSource.decodedSourceFromFileStream(
          s3Source(bucketName, key, creds)(system),
          format,
          charset,
          maximumLineSize.getOrElse(1000000), //TODO
          IngestBounds(startOffset, limit),
          meter,
          recordDecoders.map(ContentDecoder(_)),
        )

      case NumberIteratorIngest(_, startAtOffset, ingestLimit) =>
        NumberIteratorSource(IngestBounds(startAtOffset, ingestLimit), meter).decodedSource.valid

      case WebsocketIngest(format, wsUrl, initMessages, keepAliveProtocol, charset) =>
        WebsocketSource(wsUrl, initMessages, keepAliveProtocol, charset, meter)(system).framedSource
          .map(_.toDecoded(FrameDecoder(format)))

      case KinesisIngest(format, streamName, shardIds, creds, region, iteratorType, numRetries, recordDecoders) =>
        KinesisSource(
          streamName,
          shardIds,
          creds,
          region,
          iteratorType,
          numRetries, //TODO not currently supported
          meter,
          recordDecoders.map(ContentDecoder(_)),
        )(ExecutionContext.parasitic).framedSource.map(_.toDecoded(FrameDecoder(format)))

      case KinesisKclIngest(
            name,
            streamName,
            format,
            credentialsOpt,
            regionOpt,
            iteratorType,
            numRetries,
            bufferSize,
            backpressureTimeoutMillis,
            recordDecoders,
            checkpointSettings,
          ) =>
        KinesisKclSrc(
          name,
          streamName,
          meter,
          credentialsOpt,
          regionOpt,
          iteratorType,
          numRetries,
          recordDecoders.map(ContentDecoder(_)),
          bufferSize,
          backpressureTimeoutMillis,
          checkpointSettings,
        )(ExecutionContext.parasitic).framedSource.map(_.toDecoded(FrameDecoder(format)))

      case ServerSentEventIngest(format, url, recordDecoders) =>
        ServerSentEventSource(url, meter, recordDecoders.map(ContentDecoder(_)))(system).framedSource
          .map(_.toDecoded(FrameDecoder(format)))

      case SQSIngest(
            format,
            queueUrl,
            readParallelism,
            credentialsOpt,
            regionOpt,
            deleteReadMessages,
            recordDecoders,
          ) =>
        SqsSource(
          queueUrl,
          readParallelism,
          credentialsOpt,
          regionOpt,
          deleteReadMessages,
          meter,
          recordDecoders.map(ContentDecoder(_)),
        ).framedSource.map(_.toDecoded(FrameDecoder(format)))
      case KafkaIngest(
            format,
            topics,
            bootstrapServers,
            groupId,
            securityProtocol,
            maybeExplicitCommit,
            autoOffsetReset,
            kafkaProperties,
            endingOffset,
            recordDecoders,
          ) =>
        KafkaSource(
          topics,
          bootstrapServers,
          groupId.getOrElse(name),
          securityProtocol,
          maybeExplicitCommit,
          autoOffsetReset,
          kafkaProperties,
          endingOffset,
          recordDecoders.map(ContentDecoder(_)),
          meter,
          system,
        ).framedSource.map(_.toDecoded(FrameDecoder(format)))
      case ReactiveStreamIngest(format, url, port) =>
        ReactiveSource(url, port, meter).framedSource.map(_.toDecoded(FrameDecoder(format)))
    }

}
