package nebflow.core.tools

import cats.effect.IO
import cats.effect.std.Dispatcher

import java.io.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/** Persistent Shell Session */
class PersistentShell:

  @volatile private var currentDir: String = System.getProperty("user.dir")
  private val executor = Executors.newFixedThreadPool(4)
  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  // Background jobs: jobId -> CompletableFuture[result]
  private val backgroundJobs = new ConcurrentHashMap[String, java.util.concurrent.CompletableFuture[String]]()

  def getCurrentDir: String = currentDir

  def execute(command: String, timeoutMs: Long): IO[String] =
    IO.async[String] { cb =>
      IO.delay {
        val p = Promise[String]()
        val completed = new AtomicBoolean(false)
        val procRef = new AtomicReference[Process](null)

        executor.execute(() =>
          try
            val processBuilder = new ProcessBuilder("bash", "-c", command)
              .redirectErrorStream(true)
            val cwd = new File(currentDir)
            if cwd.exists() then processBuilder.directory(cwd)
            // Redirect stdin from /dev/null so commands that wait for input (e.g. sudo) fail fast
            val devNull = new File("/dev/null")
            if devNull.exists() then processBuilder.redirectInput(devNull)
            val proc = processBuilder.start()
            procRef.set(proc)

            val reader = new BufferedReader(new InputStreamReader(proc.getInputStream))
            try
              val sb = new StringBuilder
              var line: String = null
              while { line = reader.readLine(); line != null } do sb.append(line).append("\n")
              proc.waitFor()
              p.success(sb.toString().trim)
            finally
              try reader.close()
              catch case _: Exception => ()
          catch case e: Exception => p.failure(e)
        )

        val timeoutTask = scheduler.schedule(
          new Runnable:
            def run(): Unit =
              val proc = procRef.get()
              if proc != null then proc.destroyForcibly()
              if !completed.getAndSet(true) then cb(Left(new Exception(s"Command timed out after ${timeoutMs}ms")))
          ,
          timeoutMs,
          TimeUnit.MILLISECONDS
        )

        p.future.onComplete {
          case scala.util.Success(result) =>
            timeoutTask.cancel(false)
            if !completed.getAndSet(true) then cb(Right(result))
          case scala.util.Failure(e) =>
            timeoutTask.cancel(false)
            if !completed.getAndSet(true) then cb(Left(e))
        }(ExecutionContext.global)

        Some(IO.delay {
          val proc = procRef.get()
          if proc != null then proc.destroyForcibly()
          if !completed.getAndSet(true) then cb(Left(new Exception("Command cancelled")))
        })
      }
    }

  def executeBackground(command: String, timeoutMs: Long): String =
    val jobId = UUID.randomUUID().toString.take(8)
    val future = new java.util.concurrent.CompletableFuture[String]()

    executor.execute(() =>
      var reader: BufferedReader = null
      try
        val processBuilder = new ProcessBuilder("bash", "-c", command)
          .redirectErrorStream(true)
        val cwd = new File(currentDir)
        if cwd.exists() then processBuilder.directory(cwd)
        val devNull = new File("/dev/null")
        if devNull.exists() then processBuilder.redirectInput(devNull)
        val proc = processBuilder.start()

        reader = new BufferedReader(new InputStreamReader(proc.getInputStream))
        val sb = new StringBuilder
        var line: String = null
        while { line = reader.readLine(); line != null } do sb.append(line).append("\n")
        proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if proc.isAlive then
          proc.destroyForcibly()
          sb.append("[Command timed out after ").append(timeoutMs).append("ms]").append("\n")
        future.complete(sb.toString().trim)
      catch case e: Exception => future.completeExceptionally(e)
      finally
        if reader != null then
          try reader.close()
          catch case _: Exception => ()
    )

    backgroundJobs.put(jobId, future)
    jobId

  end executeBackground

  def getBackgroundResult(jobId: String): Option[String] =
    backgroundJobs.get(jobId) match
      case null => None
      case f if f.isDone =>
        try Some(f.get())
        catch case e: Exception => Some(s"[Background job failed: ${e.getMessage}]")
      case _ => None

  def listBackgroundJobs(): List[(String, Boolean)] =
    backgroundJobs.asScala.toList.map { case (id, f) => (id, f.isDone) }

  def queryDir(): IO[String] =
    IO.pure(currentDir)

  def kill(): Unit =
    scheduler.shutdown()
    executor.shutdown()
    try scheduler.awaitTermination(2, TimeUnit.SECONDS)
    catch case _: Exception => ()
    try executor.awaitTermination(2, TimeUnit.SECONDS)
    catch case _: Exception => ()

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
