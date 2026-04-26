package nebscala.core.tools

import cats.effect.IO
import cats.effect.std.Dispatcher
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}

/** 持久 Shell Session - 单例 */
class PersistentShell:
  private val process = new ProcessBuilder("bash", "--norc", "--noprofile")
    .redirectErrorStream(true)
    .start()

  private val stdin = new PrintWriter(process.getOutputStream, true)
  private val stdout = new BufferedReader(new InputStreamReader(process.getInputStream))
  private val executor = Executors.newSingleThreadExecutor()
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

  @volatile private var buffer = ""
  @volatile private var pending: Option[Promise[String]] = None
  @volatile private var separator = ""
  @volatile private var alive = true

  // 启动读取线程
  private val readerThread = new Thread(() => {
    try
      while alive do
        val ch = stdout.read()
        if ch == -1 then alive = false
        else
          buffer += ch.toChar
          if pending.isDefined then
            val idx = buffer.indexOf(separator)
            if idx >= 0 then
              val result = buffer.substring(0, idx).trim
              buffer = buffer.substring(idx + separator.length)
              pending.foreach(_.success(result))
              pending = None
    catch
      case _: Exception => alive = false
  })
  readerThread.setDaemon(true)
  readerThread.start()

  // 等待 shell 就绪
  private val readyPromise = Promise[Unit]()
  stdin.println("""echo "__SHELL_READY__"""")
  private val readyThread = new Thread(() => {
    try
      var ready = false
      while !ready && alive do
        val line = stdout.readLine()
        if line != null && line.contains("__SHELL_READY__") then
          ready = true
          readyPromise.success(())
      if !ready then readyPromise.success(()) // fallback
    catch
      case _: Exception => readyPromise.success(())
    ()
  })
  readyThread.setDaemon(true)
  readyThread.start()

  def execute(command: String, timeoutMs: Long): IO[String] =
    IO.fromFuture(IO(readyPromise.future)).flatMap { _ =>
      IO.async_[String] { cb =>
        val sep = s"__NEBSCALA_DONE_${UUID.randomUUID()}__"
        val heredocMarker = s"__NEBSCALA_CMD_${UUID.randomUUID()}__"
        separator = sep

        val p = Promise[String]()
        pending = Some(p)

        // 超时处理
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler.schedule(new Runnable {
          def run(): Unit =
            if pending.contains(p) then
              pending = None
              buffer = ""
              p.failure(new Exception(s"Command timed out after ${timeoutMs}ms"))
        }, timeoutMs, TimeUnit.MILLISECONDS)

        p.future.onComplete {
          case scala.util.Success(result) =>
            scheduler.shutdown()
            cb(Right(result))
          case scala.util.Failure(e) =>
            scheduler.shutdown()
            cb(Left(e))
        }

        stdin.println(s"""cat <<'$heredocMarker' | bash 2>&1
$command
$heredocMarker
echo "$sep"
""")
        stdin.flush()
      }
    }

  def kill(): Unit =
    alive = false
    process.destroy()
    executor.shutdown()

object PersistentShell:
  @volatile private var instance: Option[PersistentShell] = None

  def get(): PersistentShell = synchronized {
    instance match
      case Some(s) => s
      case None =>
        val s = new PersistentShell()
        instance = Some(s)
        s
  }
