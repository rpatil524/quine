package com.thatdot.quine.app.v2api.definitions

import com.thatdot.quine.app.v2api.definitions.{ApiStandingQueries => Api}
import com.thatdot.quine.{routes => Standing}

object ApiToStanding {

  private def apply(mode: Api.StandingQueryPattern.StandingQueryMode): Standing.StandingQueryPattern.StandingQueryMode =
    mode match {
      case Api.StandingQueryPattern.StandingQueryMode.DistinctId =>
        Standing.StandingQueryPattern.StandingQueryMode.DistinctId
      case Api.StandingQueryPattern.StandingQueryMode.MultipleValues =>
        Standing.StandingQueryPattern.StandingQueryMode.MultipleValues
      case Api.StandingQueryPattern.StandingQueryMode.QuinePattern =>
        Standing.StandingQueryPattern.StandingQueryMode.QuinePattern
    }
  private def apply(format: Api.OutputFormat): Standing.OutputFormat = format match {
    case Api.OutputFormat.JSON => Standing.OutputFormat.JSON
    case Api.OutputFormat.Protobuf(schemaUrl, typeName) => Standing.OutputFormat.Protobuf(schemaUrl, typeName)
  }

  private def apply(
    level: Api.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel,
  ): Standing.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel = level match {
    case Api.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Trace =>
      Standing.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Trace
    case Api.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Debug =>
      Standing.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Debug
    case Api.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Info =>
      Standing.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Info
    case Api.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Warn =>
      Standing.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Warn
    case Api.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Error =>
      Standing.StandingQueryResultOutputUserDef.PrintToStandardOut.LogLevel.Error
  }

  private def apply(
    mode: Api.StandingQueryResultOutputUserDef.PrintToStandardOut.LogMode,
  ): Standing.StandingQueryResultOutputUserDef.PrintToStandardOut.LogMode = mode match {
    case Api.StandingQueryResultOutputUserDef.PrintToStandardOut.LogMode.Complete =>
      Standing.StandingQueryResultOutputUserDef.PrintToStandardOut.LogMode.Complete
    case Api.StandingQueryResultOutputUserDef.PrintToStandardOut.LogMode.FastSampling =>
      Standing.StandingQueryResultOutputUserDef.PrintToStandardOut.LogMode.FastSampling
  }

  private def apply(pattern: Api.StandingQueryPattern): Standing.StandingQueryPattern = pattern match {
    case Api.StandingQueryPattern.Cypher(query, mode) =>
      Standing.StandingQueryPattern.Cypher(query, ApiToStanding(mode))
  }

  def apply(sq: Api.StandingQueryResultOutputUserDef): Standing.StandingQueryResultOutputUserDef = {
    val result = sq match {
      case Api.StandingQueryResultOutputUserDef.PostToEndpoint(url, parallelism, onlyPositiveMatchData, _) =>
        Standing.StandingQueryResultOutputUserDef.PostToEndpoint(
          url,
          parallelism,
          onlyPositiveMatchData,
        )
      case Api.StandingQueryResultOutputUserDef.WriteToKafka(topic, bootstrapServers, format, kafkaProperties, _) =>
        Standing.StandingQueryResultOutputUserDef.WriteToKafka(
          topic,
          bootstrapServers,
          ApiToStanding(format),
          kafkaProperties,
        )
      case Api.StandingQueryResultOutputUserDef.WriteToKinesis(
            credentials,
            region,
            streamName,
            format,
            kinesisParallelism,
            kinesisMaxBatchSize,
            kinesisMaxRecordsPerSecond,
            kinesisMaxBytesPerSecond,
            _,
          ) =>
        Standing.StandingQueryResultOutputUserDef.WriteToKinesis(
          credentials.map(ApiToIngest.apply),
          region.map(ApiToIngest.apply),
          streamName,
          ApiToStanding(format),
          kinesisParallelism,
          kinesisMaxBatchSize,
          kinesisMaxRecordsPerSecond,
          kinesisMaxBytesPerSecond,
        )
      case Api.StandingQueryResultOutputUserDef.WriteToSNS(credentials, region, topic, _) =>
        Standing.StandingQueryResultOutputUserDef.WriteToSNS(
          credentials.map(ApiToIngest.apply),
          region.map(ApiToIngest.apply),
          topic,
        )
      case Api.StandingQueryResultOutputUserDef.PrintToStandardOut(logLevel, logMode, _) =>
        Standing.StandingQueryResultOutputUserDef.PrintToStandardOut(
          ApiToStanding(logLevel),
          ApiToStanding(logMode),
        )
      case Api.StandingQueryResultOutputUserDef.WriteToFile(path, _) =>
        Standing.StandingQueryResultOutputUserDef.WriteToFile(path)
      case Api.StandingQueryResultOutputUserDef.PostToSlack(hookUrl, onlyPositiveMatchData, intervalSeconds, _) =>
        Standing.StandingQueryResultOutputUserDef.PostToSlack(
          hookUrl,
          onlyPositiveMatchData,
          intervalSeconds,
        )
      case Api.StandingQueryResultOutputUserDef.Drop(_) =>
        Standing.StandingQueryResultOutputUserDef.Drop
      case Api.StandingQueryResultOutputUserDef.CypherQuery(
            query,
            parameter,
            parallelism,
            allowAllNodeScan,
            shouldRetry,
            _,
          ) =>
        Standing.StandingQueryResultOutputUserDef.CypherQuery(
          query,
          parameter,
          parallelism,
          None,
          allowAllNodeScan,
          shouldRetry,
        )
    }
    sq.sequence.foldRight(result) { case (cypher, sq) =>
      Standing.StandingQueryResultOutputUserDef.CypherQuery(
        cypher.query,
        cypher.parameter,
        cypher.parallelism,
        Some(sq),
        cypher.allowAllNodeScan,
        cypher.shouldRetry,
      )
    }
  }

  def apply(sq: Api.StandingQueryDefinition): Standing.StandingQueryDefinition =
    Standing.StandingQueryDefinition(
      ApiToStanding(sq.pattern),
      sq.outputs.view.mapValues(ApiToStanding.apply).toMap,
      sq.includeCancellations,
      sq.inputBufferSize,
      sq.shouldCalculateResultHashCode,
    )

}