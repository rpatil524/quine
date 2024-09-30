package com.thatdot.quine.app.outputs

import java.nio.file.{Paths, StandardOpenOption}

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{FileIO, Flow}
import org.apache.pekko.util.ByteString

import com.thatdot.quine.graph.{CypherOpsGraph, MasterStream, NamespaceId, StandingQueryResult}
import com.thatdot.quine.routes.StandingQueryResultOutputUserDef
import com.thatdot.quine.routes.StandingQueryResultOutputUserDef.WriteToFile
import com.thatdot.quine.util.Log._

class FileOutput(val config: WriteToFile)(implicit private val logConfig: LogConfig) extends OutputRuntime {

  def flow(
    name: String,
    inNamespace: NamespaceId,
    output: StandingQueryResultOutputUserDef,
    graph: CypherOpsGraph,
  ): Flow[StandingQueryResult, MasterStream.SqResultsExecToken, NotUsed] = {
    val token = execToken(name, inNamespace)
    val WriteToFile(path) = config

    Flow[StandingQueryResult]
      .map(result => ByteString(result.toJson(graph.idProvider, logConfig).noSpaces + "\n"))
      .alsoTo(
        FileIO
          .toPath(
            Paths.get(path),
            Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
          )
          .named(s"sq-output-file-writer-for-$name"),
      )
      .map(_ => token)
  }
}