package nebflow.skill

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import sttp.client4.*
import sttp.client4.httpclient.HttpClientSyncBackend

import scala.concurrent.duration.*

case class QdrantPoint(id: String, vector: Array[Float], payload: Map[String, String])
case class SearchResult(id: String, score: Double, payload: Map[String, String])

class QdrantClient(baseUrl: String):
  private val logger = NebflowLogger.forName("nebflow.skill.qdrant")
  private val backend = HttpClientSyncBackend()

  def ensureCollection(name: String, vectorSize: Int): IO[Unit] =
    IO.blocking {
      // Check if collection exists
      val checkResp = basicRequest
        .get(uri"$baseUrl/collections/$name")
        .response(asStringAlways)
        .readTimeout(10.seconds)
        .send(backend)

      if !checkResp.code.isSuccess then
        // Create collection
        val body = Json.obj(
          "vectors" -> Json.obj(
            "size" -> Json.fromInt(vectorSize),
            "distance" -> Json.fromString("Cosine")
          )
        )
        val createResp = basicRequest
          .put(uri"$baseUrl/collections/$name")
          .header("Content-Type", "application/json")
          .body(body.noSpaces)
          .response(asStringAlways)
          .readTimeout(30.seconds)
          .send(backend)

        if createResp.code.isSuccess then
          logger.info(s"Created Qdrant collection: $name (dim=$vectorSize)")
        else
          logger.warn(s"Failed to create collection: ${createResp.code} ${createResp.body.take(200)}")
      else ()
    }

  def upsertPoints(collection: String, points: List[QdrantPoint]): IO[Unit] =
    if points.isEmpty then IO.unit
    else IO.blocking {
      val pointsJson = points.map { p =>
        Json.obj(
          "id" -> Json.fromString(p.id),
          "vector" -> Json.fromValues(p.vector.map(v => Json.fromDoubleOrNull(v.toDouble))),
          "payload" -> Json.fromFields(
            p.payload.map { case (k, v) => k -> Json.fromString(v) }
          )
        )
      }
      val body = Json.obj("points" -> Json.fromValues(pointsJson))

      val resp = basicRequest
        .put(uri"$baseUrl/collections/$collection/points")
        .header("Content-Type", "application/json")
        .body(body.noSpaces)
        .response(asStringAlways)
        .readTimeout(30.seconds)
        .send(backend)

      if !resp.code.isSuccess then
        logger.warn(s"Qdrant upsert error: ${resp.code} ${resp.body.take(200)}")
    }

  def search(collection: String, vector: Array[Float], limit: Int, threshold: Double): IO[List[SearchResult]] =
    if vector.isEmpty then IO.pure(Nil)
    else IO.blocking {
      val body = Json.obj(
        "vector" -> Json.fromValues(vector.map(v => Json.fromDoubleOrNull(v.toDouble))),
        "limit" -> Json.fromInt(limit),
        "score_threshold" -> Json.fromDoubleOrNull(threshold),
        "with_payload" -> Json.fromBoolean(true)
      )

      val resp = basicRequest
        .post(uri"$baseUrl/collections/$collection/points/search")
        .header("Content-Type", "application/json")
        .body(body.noSpaces)
        .response(asStringAlways)
        .readTimeout(15.seconds)
        .send(backend)

      if resp.code.isSuccess then
        val json = io.circe.parser.parse(resp.body).toOption.getOrElse(Json.Null)
        json.hcursor.downField("result").as[List[Json]] match
          case Right(arr) =>
            arr.flatMap { item =>
              val score = item.hcursor.get[Double]("score").getOrElse(0.0)
              val payload = item.hcursor.downField("payload").as[Map[String, String]].getOrElse(Map.empty)
              val id = item.hcursor.get[String]("id").getOrElse("")
              Some(SearchResult(id, score, payload))
            }
          case Left(_) => Nil
      else
        logger.warn(s"Qdrant search error: ${resp.code} ${resp.body.take(200)}")
        Nil
    }

  def deleteByFilter(collection: String, field: String, value: String): IO[Unit] =
    IO.blocking {
      val body = Json.obj(
        "filter" -> Json.obj(
          "must" -> Json.fromValues(List(
            Json.obj(
              "key" -> Json.fromString(field),
              "match" -> Json.obj("value" -> Json.fromString(value))
            )
          ))
        )
      )

      val resp = basicRequest
        .post(uri"$baseUrl/collections/$collection/points/delete")
        .header("Content-Type", "application/json")
        .body(body.noSpaces)
        .response(asStringAlways)
        .readTimeout(15.seconds)
        .send(backend)

      if !resp.code.isSuccess then
        logger.warn(s"Qdrant delete error: ${resp.code} ${resp.body.take(200)}")
    }

  /** Scroll all points and extract specified payload fields, grouped by groupField.
   *  Paginates automatically to handle >100 points. */
  def scrollPayloads(collection: String, groupField: String, mtimeField: String): IO[Map[String, Long]] =
    def scrollPage(offset: Option[String], acc: Map[String, Long]): IO[Map[String, Long]] =
      IO.blocking {
        val bodyFields = List(
          "limit" -> Json.fromInt(100),
          "with_payload" -> Json.fromBoolean(true)
        ) ++ offset.map(o => "offset" -> Json.fromString(o)).toList
        val body = Json.obj(bodyFields*)
        val resp = basicRequest
          .post(uri"$baseUrl/collections/$collection/points/scroll")
          .header("Content-Type", "application/json")
          .body(body.noSpaces)
          .response(asStringAlways)
          .readTimeout(15.seconds)
          .send(backend)

        if resp.code.isSuccess then
          val json = io.circe.parser.parse(resp.body).toOption.getOrElse(Json.Null)
          val cursor = json.hcursor
          val pointsResult = cursor.downField("result").downField("points").as[List[Json]]
          val nextPageOffset = cursor.downField("result").get[String]("next_page_offset").toOption
          (pointsResult, nextPageOffset)
        else (Left(io.circe.ParsingFailure("bad status", new RuntimeException())), None)
      }.flatMap {
        case (Right(points), nextOffset) =>
          val batch = points.flatMap { p =>
            for
              id <- p.hcursor.downField("payload").get[String](groupField).toOption
              mtimeStr <- p.hcursor.downField("payload").get[String](mtimeField).toOption
              mtime <- mtimeStr.toLongOption
            yield id -> mtime
          }.toMap
          val merged = acc ++ batch
          nextOffset match
            case Some(o) => scrollPage(Some(o), merged)
            case None => IO.pure(merged)
        case (Left(_), _) => IO.pure(acc)
      }

    scrollPage(None, Map.empty)
end QdrantClient
