package com.thatdot.quine.app.v2api.endpoints

import io.circe.generic.extras.{Configuration => CirceConfig}
import io.circe.{Decoder, Encoder, Json, Printer}
import sttp.tapir.generic.{Configuration => TapirConfig}
import sttp.tapir.json.circe.TapirJsonCirce

trait V2ApiConfiguration extends TapirJsonCirce {

  override def jsonPrinter: Printer = Printer(dropNullValues = true, indent = "")

  //For some reason the Schema derivation uses Circe configuration while encoder/decoder derivation uses
  //Tapir configuration, so this exists to unify the two for options we need
  case class Configuration(discriminator: Option[String], renameConstructors: Map[String, String]) {
    def asTapir: TapirConfig =
      discriminator
        .fold(TapirConfig.default)(d => TapirConfig.default.withDiscriminator(d))
        .copy(
          toDiscriminatorValue = { s =>
            val className = TapirConfig.default.toDiscriminatorValue(s)
            renameConstructors.getOrElse(className, className)
          },
        )
    def asCircle: CirceConfig =
      discriminator
        .fold(CirceConfig.default)(d => CirceConfig.default.withDiscriminator(d))
        .copy(
          transformConstructorNames = s => renameConstructors.getOrElse(s, s),
        )
        .withDefaults
    def withDiscriminator(d: String): Configuration =
      copy(discriminator = Some(d))
    def renameConstructor(from: String, to: String): Configuration = copy(
      renameConstructors = renameConstructors + (from -> to),
    )

  }
  object Configuration {
    val default: Configuration = Configuration(None, Map.empty)
  }

  implicit def circeConfig(implicit config: Configuration): CirceConfig = config.asCircle
  implicit def tapirConfig(implicit config: Configuration): TapirConfig = config.asTapir

  val ingestSourceTypeConfig: Configuration =
    Configuration.default
      .withDiscriminator("type")
      .renameConstructor("NumberIteratorIngest", "NumberIterator")
      .renameConstructor("FileIngest", "File")
      .renameConstructor("S3Ingest", "S3")
      .renameConstructor("StdInputIngest", "StdInput")
      .renameConstructor("WebsocketIngest", "Websocket")
      .renameConstructor("KinesisIngest", "Kinesis")
      .renameConstructor("KinesisKclIngest", "KinesisKcl")
      .renameConstructor("ServerSentEventIngest", "ServerSentEvent")
      .renameConstructor("SQSIngest", "SQS")
      .renameConstructor("KafkaIngest", "Kafka")

  trait JsonDisjoint[A, B]
  trait JsonPrim[A]
  trait JsonListLike[A]
  trait JsonObjLike[A]

  implicit val jsonPrimInt: JsonPrim[Int] = new JsonPrim[Int] {}
  implicit val jsonPrimString: JsonPrim[String] = new JsonPrim[String] {}
  implicit val jsonPrimBoolean: JsonPrim[Boolean] = new JsonPrim[Boolean] {}

  implicit def jsonObjMap[K, V]: JsonObjLike[Map[K, V]] = new JsonObjLike[Map[K, V]] {}

  implicit def jsonListList[A]: JsonListLike[List[A]] = new JsonListLike[List[A]] {}
  implicit def jsonListSet[A]: JsonListLike[Set[A]] = new JsonListLike[Set[A]] {}

  implicit def jsonDisjointPrimObj[A: JsonPrim, B: JsonObjLike]: JsonDisjoint[A, B] = new JsonDisjoint[A, B] {}
  implicit def jsonDisjointObjPrim[A: JsonObjLike, B: JsonPrim]: JsonDisjoint[A, B] = new JsonDisjoint[A, B] {}
  implicit def jsonDisjointPrimList[A: JsonPrim, B: JsonListLike]: JsonDisjoint[A, B] = new JsonDisjoint[A, B] {}
  implicit def jsonDisjointListPrim[A: JsonListLike, B: JsonPrim]: JsonDisjoint[A, B] = new JsonDisjoint[A, B] {}
  implicit def jsonDisjointListObj[A: JsonListLike, B: JsonObjLike]: JsonDisjoint[A, B] = new JsonDisjoint[A, B] {}
  implicit def jsonDisjointObjList[A: JsonObjLike, B: JsonListLike]: JsonDisjoint[A, B] = new JsonDisjoint[A, B] {}

  implicit def disjointEitherEncoder[A, B](implicit
    disjoint: JsonDisjoint[A, B],
    encodeA: Encoder[A],
    encodeB: Encoder[B],
  ): Encoder[Either[A, B]] = new Encoder[Either[A, B]] {
    override def apply(a: Either[A, B]): Json = a match {
      case Left(value) => encodeA(value)
      case Right(value) => encodeB(value)
    }
  }
  implicit def disjointEitherDecoder[A, B](implicit
    disjoint: JsonDisjoint[A, B],
    decodeA: Decoder[A],
    decodeB: Decoder[B],
  ): Decoder[Either[A, B]] =
    decodeA.map(Left(_)).or(decodeB.map(Right(_)))

}
