package nebflow.skill

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.llm.EmbeddingConfig
import sttp.client4.*
import sttp.client4.httpclient.HttpClientSyncBackend

import scala.concurrent.duration.*

class EmbeddingService(config: EmbeddingConfig):
  private val logger = NebflowLogger.forName("nebflow.skill.embedding")
  private val backend = HttpClientSyncBackend()

  private val baseUrl = config.baseUrl.getOrElse(
    config.provider match
      case "zhipu" => "https://open.bigmodel.cn/api/paas"
      case _ => "https://api.openai.com/v1"
  )

  def embed(text: String): IO[Array[Float]] =
    embedBatch(List(text)).map(_.head)

  def embedBatch(texts: List[String]): IO[List[Array[Float]]] =
    IO.blocking {
      val body = Json.obj(
        "model" -> Json.fromString(config.model),
        "input" -> Json.fromValues(texts.map(Json.fromString))
      )

      val req = basicRequest
        .post(uri"$baseUrl/v1/embeddings")
        .header("Authorization", s"Bearer ${config.apiKey}")
        .header("Content-Type", "application/json")
        .body(body.noSpaces)
        .response(asStringAlways)
        .readTimeout(30.seconds)

      val resp = req.send(backend)
      if resp.code.isSuccess then
        val json = io.circe.parser.parse(resp.body).toOption.getOrElse(Json.Null)
        val embeddings = json.hcursor.downField("data").as[List[Json]] match
          case Right(arr) =>
            arr.sortBy(_.hcursor.get[Int]("index").getOrElse(0)).map { item =>
              item.hcursor.get[List[Double]]("embedding") match
                case Right(vec) => vec.map(_.toFloat).toArray
                case Left(_) => Array.emptyFloatArray
            }
          case Left(_) => Nil
        embeddings
      else
        logger.warn(s"Embedding API error: ${resp.code} ${resp.body.take(200)}")
        texts.map(_ => Array.emptyFloatArray)
    }.handleErrorWith { e =>
      logger.warn(s"Embedding call failed: ${e.getMessage}")
      IO.pure(texts.map(_ => Array.emptyFloatArray))
    }
end EmbeddingService
