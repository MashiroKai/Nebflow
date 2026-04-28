package nebflow.core.tools

import cats.effect.IO
import cats.effect.std.Dispatcher

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.UUID
import java.util.concurrent.*

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/** Persistent Shell Session */
class PersistentShell:

  private val process = new ProcessBuilder("bash", "--norc", "--noprofile")
    .redirectErrorStream(true)
    .start()

  private val stdin = new PrintWriter(process.getOutputStream, true)
  private val stdout = new BufferedReader(new InputStreamReader(process.getInputStream))
  private val executor = Executors.newSingleThreadExecutor()
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  @volatile private var buffer = ""
  @volatile private var pending: Option[Promise[String]] = None
  @volatile private var separator = ""
  @volatile private var alive = true
  @volatile private var currentDir: String = System.getProperty("user.dir")

  // Background jobs: jobId -> CompletableFuture[result]
  private val backgroundJobs = new ConcurrentHashMap[String, java.util.concurrent.CompletableFuture[String]]()

  // 单一读取线程，同时处理 ready 检测
  private val readerThread = new Thread(() =>
    try
      var readySeen = false
      while alive do
        val ch = stdout.read()
        if ch == -1 then alive = false
        else
          buffer += ch.toChar
          if !readySeen then
            // 等待 shell 初始化标记
            val idx = buffer.indexOf("__SHELL_READY__")
            if idx >= 0 then
              readySeen = true
              buffer = buffer.substring(idx + "__SHELL_READY__".length)
          else if pending.isDefined then
            val idx = buffer.indexOf(separator)
            if idx >= 0 then
              val result = buffer.substring(0, idx).trim
              buffer = buffer.substring(idx + separator.length)
              pending.foreach(_.success(result))
              pending = None
    catch case _: Exception => alive = false
  )
  readerThread.setDaemon(true)
  readerThread.start()

  // 发送 ready 检测命令
  stdin.println("""echo "__SHELL_READY__"""")
  stdin.flush()

  def getCurrentDir: String = currentDir

  def execute(command: String, timeoutMs: Long): IO[String] =
    IO.async_[String] { cb =>
      val sep = s"__NEBFLOW_DONE_${UUID.randomUUID()}__"
      val heredocMarker = s"__NEBFLOW_CMD_${UUID.randomUUID()}__"
      separator = sep

      val p = Promise[String]()
      pending = Some(p)

      // 超时处理
      val timeoutTask = scheduler.schedule(
        new Runnable:
          def run(): Unit =
            if pending.contains(p) then
              pending = None
              buffer = ""
              p.failure(new Exception(s"Command timed out after ${timeoutMs}ms"))
        ,
        timeoutMs,
        TimeUnit.MILLISECONDS
      )

      p.future.onComplete {
        case scala.util.Success(result) =>
          timeoutTask.cancel(false)
          cb(Right(result))
        case scala.util.Failure(e) =>
          timeoutTask.cancel(false)
          cb(Left(e))
      }

      stdin.println(s"""cat <<'$heredocMarker' | bash 2>&1
$command
$heredocMarker
echo "$sep"
""")
      stdin.flush()
    }

  def executeBackground(command: String, timeoutMs: Long): String =
    val jobId = UUID.randomUUID().toString.take(8)
    val future = new java.util.concurrent.CompletableFuture[String]()

    executor.execute(() =>
      try
        val processBuilder = new ProcessBuilder("bash", "-c", command)
          .redirectErrorStream(true)
        val proc = processBuilder.start()

        val reader = new BufferedReader(new InputStreamReader(proc.getInputStream))
        val sb = new StringBuilder
        var line: String = null
        while { line = reader.readLine(); line != null } do sb.append(line).append("\n")
        proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if proc.isAlive then
          proc.destroyForcibly()
          sb.append("[Command timed out after ").append(timeoutMs).append("ms]").append("\n")
        future.complete(sb.toString().trim)
      catch case e: Exception => future.completeExceptionally(e)
    )

    backgroundJobs.put(jobId, future)
    jobId

  def getBackgroundResult(jobId: String): Option[String] =
    backgroundJobs.get(jobId) match
      case null => None
      case f if f.isDone =>
        try Some(f.get())
        catch case _: Exception => Some("[Background job failed]")
      case _ => None

  def listBackgroundJobs(): List[(String, Boolean)] =
    backgroundJobs.asScala.toList.map { case (id, f) => (id, f.isDone) }

  private def updateDir(): Unit =
    // Best-effort directory tracking - won't work with current heredoc approach
    // Will be tracked via separate pwd command
    ()

  def queryDir(): IO[String] =
    execute("pwd", 5000L)

  def kill(): Unit =
    alive = false
    process.destroy()
    scheduler.shutdown()
    executor.shutdown()

end PersistentShell

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
