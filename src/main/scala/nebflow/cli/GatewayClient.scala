package nebflow.cli
import nebflow.core.PathUtil

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, parser}
import sttp.client4.*
import sttp.model.Uri

/**
 * HTTP client for communicating with the running Gateway.
 * Reads the auth token from ~/.nebflow/auth.json.
 */
class GatewayClient(baseUri: String, token: String):
  private val backend = DefaultSyncBackend()

  /** HTTP GET — returns JSON */
  def get(path: String): IO[Json] = IO.blocking {
    val uri = Uri.unsafeParse(s"$baseUri$path").withParam("token", token)
    val resp = basicRequest
      .get(uri)
      .header("Authorization", s"Bearer $token")
      .response(asStringAlways)
      .send(backend)
    parseJson(resp.body, path)
  }

  /** HTTP POST — sends JSON body, returns JSON */
  def post(path: String, body: Json): IO[Json] = IO.blocking {
    val uri = Uri.unsafeParse(s"$baseUri$path").withParam("token", token)
    val resp = basicRequest
      .post(uri)
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "application/json")
      .body(body.noSpaces)
      .response(asStringAlways)
      .send(backend)
    parseJson(resp.body, path)
  }

  /** HTTP DELETE — returns JSON */
  def delete(path: String): IO[Json] = IO.blocking {
    val uri = Uri.unsafeParse(s"$baseUri$path").withParam("token", token)
    val resp = basicRequest
      .delete(uri)
      .header("Authorization", s"Bearer $token")
      .response(asStringAlways)
      .send(backend)
    parseJson(resp.body, path)
  }

  /** HTTP PATCH — sends JSON body, returns JSON */
  def patch(path: String, body: Json): IO[Json] = IO.blocking {
    val uri = Uri.unsafeParse(s"$baseUri$path").withParam("token", token)
    val resp = basicRequest
      .patch(uri)
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "application/json")
      .body(body.noSpaces)
      .response(asStringAlways)
      .send(backend)
    parseJson(resp.body, path)
  }

  /** POST /api/command — generic WS-equivalent endpoint */
  def command(payload: Json): IO[Json] = post("/api/command", payload)

  private def parseJson(body: String, path: String): Json =
    parser.parse(body) match
      case Right(json) => json
      case Left(_) =>
        // If we got a non-JSON response, the Gateway version doesn't support REST API
        if body.startsWith("Not found") || body.contains("<!DOCTYPE") then
          throw new RuntimeException(
            s"Gateway REST API not available. Restart Gateway to enable CLI support."
          )
        else throw new RuntimeException(s"Failed to parse response from $path: ${body.take(100)}")

  /** Simple health check — any HTTP response means gateway is running */
  def healthCheck: IO[Boolean] = IO.blocking {
    try
      val uri = Uri.unsafeParse(s"$baseUri/").withParam("token", token)
      val resp = basicRequest
        .get(uri)
        .response(asStringAlways)
        .send(backend)
      resp.code.code != 0 // any HTTP response means server is up
    catch case _: Exception => false
  }

  /** POST /api/chat with SSE streaming — calls handler for each SSE event */
  def chatStream(body: Json, onEvent: String => IO[Unit]): IO[Unit] = IO.blocking {
    val uri = Uri.unsafeParse(s"$baseUri/api/chat/stream").withParam("token", token)
    val resp = basicRequest
      .post(uri)
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "application/json")
      .header("Accept", "text/event-stream")
      .body(body.noSpaces)
      .response(asStringAlways)
      .send(backend)
    val respBody: String = resp.body
    // Parse SSE events: data: {...}\n\n
    respBody.split("\n\n").filter(_.nonEmpty).foreach { block =>
      val dataLines = block.linesIterator
        .filter(_.startsWith("data: "))
        .map(_.stripPrefix("data: "))
        .mkString("\n")
      if dataLines.nonEmpty then onEvent(dataLines).unsafeRunSync()
    }
  }
end GatewayClient

object GatewayClient:
  private val authPath = PathUtil.dataRoot / "auth.json"

  /** Read the stored auth token */
  def readToken: IO[Option[String]] = IO.blocking {
    if os.exists(authPath) then parser.decode[String](os.read(authPath)).toOption
    else None
  }

  /** Read the Gateway port from env or default */
  def readPort: IO[Int] = IO.blocking {
    sys.env.get("NEBFLOW_GATEWAY_PORT").flatMap(_.toIntOption).getOrElse(8080)
  }

  /** Create a client if Gateway is running and accessible */
  def create: IO[Option[GatewayClient]] =
    readToken.flatMap {
      case Some(t) =>
        readPort.flatMap { port =>
          val client = new GatewayClient(s"http://localhost:$port", t)
          client.healthCheck.map(if _ then Some(client) else None)
        }
      case None => IO.pure(None)
    }
end GatewayClient
