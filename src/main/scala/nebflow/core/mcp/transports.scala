package nebflow.core.mcp

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.core.NebflowLogger

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.concurrent.{ConcurrentHashMap, atomic}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Promise}

/** MCP Transport interface */
trait McpTransport:
  def send(request: JsonRpcRequest): IO[JsonRpcResponse]
  def sendNotification(notification: JsonRpcNotification): IO[Unit]
  def onNotification(handler: JsonRpcNotification => IO[Unit]): IO[Unit]
  def close(): IO[Unit]

/** Stdio transport — communicates with MCP server via subprocess stdin/stdout. */
class StdioTransport private (
  command: String,
  args: List[String],
  env: Map[String, String],
  proc: Process,
  stdin: PrintWriter,
  stdout: BufferedReader
) extends McpTransport:

  private val logger = NebflowLogger.forName("nebflow.mcp.stdio")

  private val counter = new atomic.AtomicInteger(0)
  private val pending = new ConcurrentHashMap[String, Promise[JsonRpcResponse]]()
  private val notificationHandlers = new java.util.concurrent.CopyOnWriteArrayList[JsonRpcNotification => IO[Unit]]()
  @volatile private var running = true

  /** Called by factory after construction to start reader threads. */
  private[mcp] def startReaderThreads(): Unit =
    // stderr reader thread — logs server stderr through nebflow logger
    val stderrThread = new Thread(
      () =>
        try
          val errReader = new BufferedReader(new InputStreamReader(proc.getErrorStream))
          var line: String = null
          while running do
            line = errReader.readLine()
            if line != null then logger.info(s"[stderr] $line")
            else running = false
        catch case _: Exception => () // stderr closed, that's fine
      ,
      "mcp-stderr-reader"
    )
    stderrThread.setDaemon(true)
    stderrThread.start()

    // stdout reader thread — dispatches responses and notifications
    val readerThread = new Thread(
      () =>
        try
          while running do
            val line = stdout.readLine()
            if line != null then
              parser.parse(line) match
                case Left(err) =>
                  logger.warn(s"Failed to parse MCP message: ${err.message}")
                case Right(json) =>
                  // Distinguish: messages with "id" are responses, without are notifications
                  val hasId = json.hcursor.downField("id").as[Json].toOption.exists(_ != Json.Null)
                  if hasId then dispatchResponse(json) else dispatchNotification(json)
            else running = false
        catch
          case _: InterruptedException => () // normal shutdown
          case e: Exception =>
            running = false
            // Notify all pending promises so callers don't hang forever
            val remaining = new java.util.ArrayList(pending.values())
            pending.clear()
            remaining.forEach(_.failure(e))
      ,
      "mcp-stdout-reader"
    )
    readerThread.setDaemon(true)
    readerThread.start()

  end startReaderThreads

  private def dispatchResponse(json: Json): Unit =
    val id = json.hcursor.downField("id").as[Json].getOrElse(Json.Null)
    val idStr = id.toString
    pending.get(idStr) match
      case null => logger.warn(s"Received response with unknown id: $idStr")
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

  end dispatchResponse

  private def dispatchNotification(json: Json): Unit =
    val method = json.hcursor.downField("method").as[String].toOption.getOrElse("")
    val params = json.hcursor.downField("params").as[JsonObject].toOption
    val notification = JsonRpcNotification(method = method, params = params)
    // Fire and forget — handlers run on the reader thread's EC via IO.eval
    notificationHandlers.forEach { handler =>
      handler(notification).unsafeRunAndForget()
    }

  def send(request: JsonRpcRequest): IO[JsonRpcResponse] =
    IO.defer {
      val id = counter.incrementAndGet()
      val reqWithId = request.copy(id = Json.fromInt(id))
      val idStr = Json.fromInt(id).toString
      val p = Promise[JsonRpcResponse]()

      // Register promise FIRST, then write — prevents race with fast server
      pending.put(idStr, p)

      val cancelToken: IO[Unit] = IO {
        pending.remove(idStr)
        p.failure(new RuntimeException(s"MCP request $id cancelled"))
        ()
      }

      IO.blocking {
        stdin.println(reqWithId.asJson.deepDropNullValues.noSpaces)
        stdin.flush()
      } *> IO.async { (cb: Either[Throwable, JsonRpcResponse] => Unit) =>
        p.future.onComplete {
          case scala.util.Success(r) => cb(Right(r))
          case scala.util.Failure(e) => cb(Left(e))
        }(ExecutionContext.global)
        IO.pure(Some(cancelToken))
      }
    }

  def sendNotification(notification: JsonRpcNotification): IO[Unit] =
    IO.blocking {
      stdin.println(notification.asJson.deepDropNullValues.noSpaces)
      stdin.flush()
    }

  def onNotification(handler: JsonRpcNotification => IO[Unit]): IO[Unit] =
    IO { notificationHandlers.add(handler); () }

  def close(): IO[Unit] = IO {
    running = false
    proc.destroy()
    // Notify all pending promises so callers receive an error instead of hanging
    val remaining = new java.util.ArrayList(pending.values())
    pending.clear()
    remaining.forEach(_.failure(new RuntimeException("MCP transport closed")))
  }

end StdioTransport

object StdioTransport:

  /** Factory method — creates process inside IO for proper error handling. */
  def apply(command: String, args: List[String], env: Map[String, String]): IO[StdioTransport] =
    IO.blocking {
      val processBuilder = new ProcessBuilder((command :: args)*)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
      env.foreach { case (k, v) => processBuilder.environment().put(k, v) }
      val proc = processBuilder.start()
      val stdin = new PrintWriter(proc.getOutputStream, true)
      val stdout = new BufferedReader(new InputStreamReader(proc.getInputStream))
      new StdioTransport(command, args, env, proc, stdin, stdout)
    }.flatTap { transport =>
      // Start reader threads after construction
      IO(transport.startReaderThreads())
    }
end StdioTransport

/** HTTP transport — communicates with MCP server via HTTP POST. */
class HttpTransport(url: String, headers: Map[String, String]) extends McpTransport:

  import sttp.client4.*
  import sttp.client4.httpclient.HttpClientSyncBackend

  private val logger = NebflowLogger.forName("nebflow.mcp.http")
  private val backend = HttpClientSyncBackend()
  private val baseUrl = url.replaceAll("/+$", "")
  private val counter = new atomic.AtomicInteger(0)
  private val notificationHandlers = new java.util.concurrent.CopyOnWriteArrayList[JsonRpcNotification => IO[Unit]]()

  def send(request: JsonRpcRequest): IO[JsonRpcResponse] = IO.blocking {
    val json = request.asJson.deepDropNullValues
    var req = basicRequest
      .post(uri"$baseUrl")
      .header("content-type", "application/json")
      .readTimeout(30.seconds)
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

  def sendNotification(notification: JsonRpcNotification): IO[Unit] = IO.blocking {
    val json = notification.asJson.deepDropNullValues
    var req = basicRequest
      .post(uri"$baseUrl")
      .header("content-type", "application/json")
      .readTimeout(30.seconds)
      .body(json.noSpaces)
    headers.foreach { case (k, v) => req = req.header(k, v) }
    req.response(asStringAlways).send(backend)
    ()
  }

  def onNotification(handler: JsonRpcNotification => IO[Unit]): IO[Unit] =
    IO { notificationHandlers.add(handler); () }

  def close(): IO[Unit] = IO.blocking(backend.close())

end HttpTransport
