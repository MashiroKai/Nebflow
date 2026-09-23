package nebflow.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, parser}
import nebflow.core.PathUtil
import sttp.client4.*
import sttp.model.{StatusCode, Uri}

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
    parseJson(resp.code, resp.body, path)
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
    parseJson(resp.code, resp.body, path)
  }

  /** HTTP DELETE — returns JSON */
  def delete(path: String): IO[Json] = IO.blocking {
    val uri = Uri.unsafeParse(s"$baseUri$path").withParam("token", token)
    val resp = basicRequest
      .delete(uri)
      .header("Authorization", s"Bearer $token")
      .response(asStringAlways)
      .send(backend)
    parseJson(resp.code, resp.body, path)
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
    parseJson(resp.code, resp.body, path)
  }

  /** HTTP PUT — sends JSON body, returns JSON (#339: model set → PUT /presets/:name) */
  def put(path: String, body: Json): IO[Json] = IO.blocking {
    val uri = Uri.unsafeParse(s"$baseUri$path").withParam("token", token)
    val resp = basicRequest
      .put(uri)
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "application/json")
      .body(body.noSpaces)
      .response(asStringAlways)
      .send(backend)
    parseJson(resp.code, resp.body, path)
  }

  /** POST /api/command — generic WS-equivalent endpoint */
  def command(payload: Json): IO[Json] = post("/api/command", payload)

  /**
   * A1: a non-2xx status is a FAILURE, not an empty payload. Before this
   * check only `parser.parse` ran, so an error body (`{"error":"Unauthorized"}`)
   * parsed fine and every downstream `getOrElse` fallback silently produced an
   * empty list / `{}` / `"true"` — `session delete` printed "Session s1
   * deleted" on a 403 with exit 0. The failure now surfaces as a real error
   * and reaches CliRouter's error branch (exit 1, T8 message).
   */
  private def parseJson(code: StatusCode, body: String, path: String): Json =
    if !code.isSuccess then throw new RuntimeException(GatewayClient.requestError(code.code, body))
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
    if !resp.code.isSuccess then throw new RuntimeException(GatewayClient.requestError(resp.code.code, resp.body))
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

  /**
   * CLI-side port override (C1/A4). `--port` is consumed by the JVM-level
   * global-flag parser, which until now only told the gateway package — the
   * CLI's HTTP client kept reading `GATEWAY_PORT`/8080, so a `--port` instance
   * was unreachable from the CLI. This is the CLI half of that propagation;
   * it is set from the same entry point that consumes `--port`.
   */
  @volatile private var portOverride: Option[Int] = None

  private[nebflow] def setPort(port: Int): Unit = portOverride = Some(port)
  private[cli] def resetPort(): Unit = portOverride = None

  /**
   * Single port resolver: explicit CLI override > `GATEWAY_PORT` env > 8080.
   * Every CLI-side port read goes through here — no second mechanism.
   */
  def readPort: IO[Int] = IO.blocking {
    portOverride
      .orElse(nebflow.core.Branding.env("GATEWAY_PORT").flatMap(_.toIntOption))
      .getOrElse(8080)
  }

  /**
   * T8 wording for a non-2xx gateway response. The detail prefers the body's
   * `error`/`message` field (the gateway answers `403 {"error":"Unauthorized"}`,
   * `RestApiRoutes.scala:3737-3738`) and falls back to the raw body.
   */
  private[cli] def requestError(code: Int, body: String): String =
    val detail = parser
      .parse(body)
      .toOption
      .flatMap { j =>
        val c = j.hcursor
        c.downField("error")
          .as[String]
          .toOption
          .orElse(c.downField("message").as[String].toOption)
      }
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse {
        if body.startsWith("Not found") || body.contains("<!DOCTYPE") then
          "Gateway REST API not available. Restart Gateway to enable CLI support."
        else body.trim.take(200)
      }
    s"Gateway request failed (HTTP $code): $detail"

  end requestError

  /** Read the stored auth token */
  def readToken: IO[Option[String]] = IO.blocking {
    if os.exists(authPath) then parser.decode[String](os.read(authPath)).toOption
    else None
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
