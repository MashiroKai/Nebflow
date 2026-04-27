package nebflow.core.mcp

import cats.effect.IO
import io.circe.{Json, parser}
import io.circe.syntax.*
import io.circe.generic.auto.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.concurrent.{ConcurrentHashMap, atomic}

/** MCP Transport interface */
trait McpTransport:
  def send(request: JsonRpcRequest): IO[JsonRpcResponse]
  def close(): IO[Unit]

/** Stdio transport */
class StdioTransport(command: String, args: List[String], env: Map[String, String]) extends McpTransport:
  private val process = new ProcessBuilder((command :: args)*)
    .inheritIO()
    .redirectOutput(ProcessBuilder.Redirect.PIPE)
    .redirectInput(ProcessBuilder.Redirect.PIPE)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
  env.foreach { case (k, v) => process.environment().put(k, v) }
  private val proc = process.start()
  private val stdin = new PrintWriter(proc.getOutputStream, true)
  private val stdout = new BufferedReader(new InputStreamReader(proc.getInputStream))
  private val counter = new atomic.AtomicInteger(0)
  private val pending = new ConcurrentHashMap[String, Promise[JsonRpcResponse]]()
  private var running = true

  // Reader thread
  private val readerThread = new Thread(() => {
    try
      while running do
        val line = stdout.readLine()
        if line != null then
          parser.parse(line).toOption.foreach { json =>
            val id = json.hcursor.downField("id").as[Json].getOrElse(Json.Null)
            val idStr = id.toString
            pending.get(idStr) match
              case null => // no pending request
              case p =>
                val result = json.hcursor.downField("result").as[Json].toOption
                val error = json.hcursor.downField("error").as[Json].toOption
                val response = error match
                  case Some(err) =>
                    val code = err.hcursor.downField("code").as[Int].getOrElse(-1)
                    val msg = err.hcursor.downField("message").as[String].getOrElse("Unknown error")
                    JsonRpcResponse(id = id, error = Some(JsonRpcError(code, msg)))
                  case None =>
                    JsonRpcResponse(id = id, result = result)
                p.success(response)
                pending.remove(idStr)
          }
        else running = false
    catch
      case _: Exception => running = false
  })
  readerThread.setDaemon(true)
  readerThread.start()

  def send(request: JsonRpcRequest): IO[JsonRpcResponse] = IO.async { cb =>
    IO {
      val id = counter.incrementAndGet()
      val reqWithId = request.copy(id = Json.fromInt(id))
      val json = reqWithId.asJson.deepDropNullValues
      val line = json.noSpaces
      val p = Promise[JsonRpcResponse]()
      pending.put(Json.fromInt(id).toString, p)
      stdin.println(line)
      stdin.flush()

      p.future.onComplete {
        case scala.util.Success(r) => cb(Right(r))
        case scala.util.Failure(e) => cb(Left(e))
      }(scala.concurrent.ExecutionContext.global)

      Some(IO.unit)
    }
  }

  def close(): IO[Unit] = IO {
    running = false
    proc.destroy()
  }

/** HTTP transport */
class HttpTransport(url: String, headers: Map[String, String]) extends McpTransport:
  import sttp.client4.*
  import sttp.client4.httpclient.HttpClientSyncBackend

  private val backend = HttpClientSyncBackend()
  private val baseUrl = url.replaceAll("/+$", "")
  private val counter = new atomic.AtomicInteger(0)

  def send(request: JsonRpcRequest): IO[JsonRpcResponse] = IO.blocking {
    val json = request.asJson.deepDropNullValues
    var req = basicRequest
      .post(uri"$baseUrl")
      .header("content-type", "application/json")
      .body(json.noSpaces)

    headers.foreach { case (k, v) => req = req.header(k, v) }

    val response = req.response(asStringAlways).send(backend)
    parser.parse(response.body) match
      case Left(err) => throw new RuntimeException(s"Failed to parse MCP response: ${err.message}")
      case Right(json) =>
        val id = json.hcursor.downField("id").as[Json].getOrElse(Json.Null)
        val result = json.hcursor.downField("result").as[Json].toOption
        val error = json.hcursor.downField("error").as[Json].toOption
        error match
          case Some(err) =>
            val code = err.hcursor.downField("code").as[Int].getOrElse(-1)
            val msg = err.hcursor.downField("message").as[String].getOrElse("Unknown error")
            JsonRpcResponse(id = id, error = Some(JsonRpcError(code, msg)))
          case None => JsonRpcResponse(id = id, result = result)
  }

  def close(): IO[Unit] = IO.unit
