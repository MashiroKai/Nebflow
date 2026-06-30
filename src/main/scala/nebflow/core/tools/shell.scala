package nebflow.core.tools

import cats.effect.*
import cats.effect.std.Mutex
import cats.syntax.all.*
import nebflow.shared.Defaults

import java.io.{BufferedReader, File, InputStreamReader}
import java.lang.Process
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.*

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.Using

/** Tracks process health for heartbeat / progress detection. Thread-safe via atomics. */
private[tools] class JobHealth(
  val processRef: AtomicReference[Process] = new AtomicReference[Process](null),
  val lastActivityMs: AtomicLong = new AtomicLong(System.currentTimeMillis()),
  val outputLineCount: AtomicInteger = new AtomicInteger(0),
  val startedAtMs: AtomicLong = new AtomicLong(System.currentTimeMillis()),
  /** Whether a "process dead" notification has already been sent — prevents spam. */
  val deadNotified: AtomicBoolean = new AtomicBoolean(false)
)

/** Snapshot of a running background job's health. */
case class BackgroundJobHealth(
  isAlive: Boolean,
  runningMs: Long,
  idleMs: Long,
  outputLineCount: Int,
  command: String
)

/** Background job managed by cats-effect Fiber + Deferred */
private case class BackgroundJob(
  fiber: Fiber[IO, Throwable, Unit],
  heartbeatFiber: Option[Fiber[IO, Throwable, Unit]],
  healthCheckFiber: Option[Fiber[IO, Throwable, Unit]],
  deferred: Deferred[IO, Either[Throwable, ProcessResult]],
  command: String,
  description: Option[String] = None,
  on_complete: Option[Either[Throwable, ProcessResult] => IO[Unit]] = None,
  startedAtMs: Long = System.currentTimeMillis(),
  health: JobHealth = new JobHealth()
):
  def isComplete: IO[Boolean] = deferred.tryGet.map(_.isDefined)

/** Per-session shell executor using pure cats-effect IO */
final class ShellSession private (
  val sessionId: String,
  currentDir: Ref[IO, String],
  backgroundJobs: Ref[IO, Map[String, BackgroundJob]],
  private val cleanupFiber: Fiber[IO, Throwable, Unit],
  private val lastAccessed: Ref[IO, Long],
  private val isAlive: Ref[IO, Boolean],
  private val lifecycleMutex: Mutex[IO]
):

  private val SessionTTL = 30.minutes.toMillis

  def getCurrentDir: IO[String] = currentDir.get

  private[tools] def touch: IO[Unit] =
    Clock[IO].realTime.map(_.toMillis).flatMap(lastAccessed.set)

  private[tools] def isStale: IO[Boolean] =
    for
      now <- Clock[IO].realTime.map(_.toMillis)
      last <- lastAccessed.get
    yield now - last > SessionTTL

  /** Cancel the cleanup fiber to prevent leaks when the session is evicted. */
  private[tools] def cancelCleanupFiber(): IO[Unit] =
    cleanupFiber.cancel.handleErrorWith(_ => IO.unit)

  private def checkAlive: IO[Unit] =
    isAlive.get.flatMap {
      case true => IO.unit
      case false => IO.raiseError(new IllegalStateException("Session has been destroyed"))
    }

  /**
   * Execute a command synchronously, updating cwd afterwards via pwd.
   *  If pwd fails (e.g. old cwd was deleted), currentDir is left unchanged.
   */
  def execute(command: String, timeout: FiniteDuration, health: Option[JobHealth] = None): IO[ProcessResult] =
    for
      _ <- checkAlive *> touch
      cwd <- currentDir.get
      result <- runProcess(command, cwd, timeout, health)
      newCwd <- runProcess("pwd", cwd, 5.seconds).attempt.map {
        case Right(r) => r.stdout.trim
        case Left(_) => cwd // keep old cwd if pwd fails
      }
      _ <- currentDir.set(newCwd)
    yield result.copy(cwd = newCwd)

  /** Start a background job and return its job ID */
  def executeBackground(
    command: String,
    timeout: FiniteDuration,
    description: Option[String] = None,
    on_complete: Option[Either[Throwable, ProcessResult] => IO[Unit]] = None,
    on_heartbeat: Option[(String, JobHealth) => IO[Unit]] = None,
    jobIdOverride: Option[String] = None
  ): IO[String] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        jobId <- jobIdOverride.fold(IO.randomUUID.map(_.toString.take(8)))(IO.pure)
        deferred <- Deferred[IO, Either[Throwable, ProcessResult]]
        health = new JobHealth()
        fiber <- backgroundExecute(command, timeout, deferred, health, on_complete).start
        hbFiber <- on_heartbeat match
          case Some(cb) => startHeartbeat(jobId, deferred, health, cb)
          case None => IO.pure(None)
        hcFiber <- startJobHealthCheck(jobId, deferred, health)
        job = BackgroundJob(
          fiber,
          hbFiber,
          hcFiber,
          deferred,
          command,
          description,
          on_complete,
          health.startedAtMs.get(),
          health
        )
        _ <- backgroundJobs.update(_ + (jobId -> job))
      yield jobId
    }

  /** Query a background job. If complete, remove it and return the result. */
  def getBackgroundResult(jobId: String): IO[Option[Either[Throwable, ProcessResult]]] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        res <- backgroundJobs.get.map(_.get(jobId)).flatMap {
          case None => IO.pure(None)
          case Some(job) =>
            job.deferred.tryGet.flatMap {
              case None => IO.pure(None)
              case Some(result) =>
                backgroundJobs.update(_ - jobId).as(Some(result))
            }
        }
      yield res
    }

  /** Get health info for a running background job (does not remove the job). */
  def getBackgroundJobHealth(jobId: String): IO[Option[BackgroundJobHealth]] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        jobs <- backgroundJobs.get
      yield jobs.get(jobId).map { job =>
        val proc = job.health.processRef.get()
        val now = System.currentTimeMillis()
        BackgroundJobHealth(
          isAlive = proc != null && proc.isAlive,
          runningMs = now - job.health.startedAtMs.get(),
          idleMs = now - job.health.lastActivityMs.get(),
          outputLineCount = job.health.outputLineCount.get(),
          command = job.command
        )
      }
    }

  /** List background jobs with completion status */
  def listBackgroundJobs(): IO[List[(String, Boolean, String)]] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        jobs <- backgroundJobs.get
        res <- jobs.toList.traverse { case (id, job) =>
          job.isComplete.map((id, _, job.command))
        }
      yield res
    }

  /**
   * Register an externally-started fiber as a background job so it can be cancelled
   * via cancelBackgroundJob. Used for auto-backgrounded commands.
   */
  def registerBackgroundJob(
    jobId: String,
    fiber: Fiber[IO, Throwable, Unit],
    command: String,
    health: JobHealth
  ): IO[Unit] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        deferred <- Deferred[IO, Either[Throwable, ProcessResult]]
        // Watcher: when the fiber finishes naturally, complete the deferred so the cleanup fiber can evict it
        _ <- (fiber.joinWithNever.attempt.flatMap { result =>
          deferred.complete(result.map(_ => ProcessResult("", "", 0, ""))).void
        }).start
        hcFiber <- startJobHealthCheck(jobId, deferred, health, isRegisteredJob = true)
        job = BackgroundJob(
          fiber = fiber,
          heartbeatFiber = None,
          healthCheckFiber = hcFiber,
          deferred = deferred,
          command = command,
          health = health
        )
        _ <- backgroundJobs.update(_ + (jobId -> job))
      yield ()
    }

  /**
   * Cancel a background job by its ID.
   * Kills the underlying process (if still alive), cancels all fibers, and removes the job.
   * Returns true if the job was found and cancelled, false if already gone/completed.
   */
  def cancelBackgroundJob(jobId: String): IO[Boolean] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        res <- backgroundJobs.get.map(_.get(jobId)).flatMap {
          case None => IO.pure(false)
          case Some(job) =>
            job.isComplete.flatMap {
              case true => IO.pure(false)
              case false =>
                // 1) Kill the underlying OS process directly (not relying on fiber cancellation)
                val killProcess = IO {
                  val proc = job.health.processRef.get()
                  if proc != null && proc.isAlive then
                    proc.destroyForcibly()
                    try proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    catch case _: InterruptedException => ()
                  ()
                }
                // 2) Complete the deferred so any waiters get the cancellation signal
                val completeDeferred =
                  job.deferred.complete(Left(new InterruptedException("Cancelled"))).attempt.void
                // 3) Cancel the cats-effect fibers (command + heartbeat + health check)
                val cancelFibers =
                  job.fiber.cancel *> job.heartbeatFiber.traverse(_.cancel) *> job.healthCheckFiber.traverse(_.cancel)
                // 4) Remove from map
                val remove = backgroundJobs.update(_ - jobId)

                killProcess *> completeDeferred *> cancelFibers *> remove.as(true)
            }
        }
      yield res
    }

  /**
   * Kill this session: cancel all background jobs and cleanup fiber.
   *  Serialised with lifecycleMutex to prevent executeBackground from adding
   *  jobs after we read the map.
   */
  def kill(): IO[Unit] =
    lifecycleMutex.lock.surround {
      for
        _ <- isAlive.set(false)
        jobs <- backgroundJobs.getAndSet(Map.empty)
        _ <- jobs.values.toList.traverse_(job =>
          job.deferred.complete(Left(new InterruptedException("Session killed"))).attempt.void *>
            job.fiber.cancel *>
            job.heartbeatFiber.traverse(_.cancel) *>
            job.healthCheckFiber.traverse(_.cancel) *>
            job.fiber.join.void.timeout(5.seconds).attempt.void
        )
        _ <- cleanupFiber.cancel *> cleanupFiber.join.void.timeout(5.seconds).attempt.void
      yield ()
    }

  // ------------------------------------------------------------------
  // Internals
  // ------------------------------------------------------------------

  private val MaxOutputSize = 10 * 1024 * 1024 // 10MB

  private val isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  private def buildProcessBuilder(command: String, cwd: String): ProcessBuilder =
    val pb =
      if isWindows then
        // Prefix with "chcp 65001 >nul" to switch the console code page to UTF-8.
        // Without this, cmd.exe and its child processes output in the system OEM
        // code page (GBK/CP936 on Chinese Windows), causing garbled text when
        // readStream decodes as UTF-8.
        new ProcessBuilder("cmd.exe", "/c", "chcp 65001 >nul 2>nul & " + command)
      else new ProcessBuilder("bash", "-c", command)
    // Empty or invalid working directory causes cmd.exe to fail on Windows
    // with "文件名、目录名或卷标语法不正确". Fall back to user home.
    val safeCwd =
      if cwd == null || cwd.isEmpty || !new File(cwd).exists() then
        sys.props.getOrElse("user.home", if isWindows then "C:\\" else "/tmp")
      else cwd
    pb.directory(new File(safeCwd))
    if isWindows then
      pb.redirectInput(ProcessBuilder.Redirect.PIPE)
      // Inject UTF-8 env vars for common runtimes that don't respect the
      // console code page (e.g. Python uses the C runtime locale by default).
      val env = pb.environment()
      env.put("PYTHONUTF8", "1")
      env.put("PYTHONIOENCODING", "utf-8")
    else pb.redirectInput(new File("/dev/null"))
    pb.redirectErrorStream(false) // stdout/stderr separated
    pb

  end buildProcessBuilder

  private def runProcess(
    command: String,
    cwd: String,
    timeout: FiniteDuration,
    health: Option[JobHealth] = None
  ): IO[ProcessResult] =
    IO.blocking {
      buildProcessBuilder(command, cwd).start()
    }.bracket { proc =>
      val storeProc = health.fold(IO.unit)(h => IO(h.processRef.set(proc)))
      // On Windows, close stdin immediately to prevent the cmd.exe process
      // from hanging on commands that try to read stdin. This also avoids a
      // potential JVM bug with Redirect.DISCARD on certain Windows builds.
      val closeStdin = IO {
        if isWindows then
          try proc.getOutputStream().close()
          catch case _: java.io.IOException => ()
      }
      val stdoutIO = IO.blocking(
        readStream(
          proc.getInputStream,
          line =>
            health.foreach { h =>
              h.lastActivityMs.set(System.currentTimeMillis())
              h.outputLineCount.incrementAndGet()
            }
        )
      )
      val stderrIO = IO.blocking(readStream(proc.getErrorStream))
      val waitIO = IO.blocking {
        proc.waitFor()
        proc.exitValue()
      }

      storeProc *> closeStdin *> (stdoutIO, stderrIO, waitIO)
        .parMapN { (out, err, code) =>
          ProcessResult(out, err, code, cwd)
        }
        .timeout(timeout)
    } { proc =>
      IO.blocking {
        proc.destroyForcibly()
        proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
        ()
      }
    }

  private def backgroundExecute(
    command: String,
    timeout: FiniteDuration,
    deferred: Deferred[IO, Either[Throwable, ProcessResult]],
    health: JobHealth,
    on_complete: Option[Either[Throwable, ProcessResult] => IO[Unit]] = None
  ): IO[Unit] =
    // Background jobs have no timeout — they run until completion or cancellation
    execute(command, 365.days, Some(health)).attempt.flatMap { result =>
      deferred.complete(result).void *> on_complete.fold(IO.unit)(cb => cb(result).handleErrorWith(_ => IO.unit))
    }

  /**
   * Start a heartbeat fiber that periodically reports job health.
   * Interval backoff: 30s → 60s → 120s as the job stays idle longer,
   * so long-running services don't flood the frontend with redundant updates.
   */
  private def startHeartbeat(
    jobId: String,
    deferred: Deferred[IO, Either[Throwable, ProcessResult]],
    health: JobHealth,
    onHeartbeat: (String, JobHealth) => IO[Unit]
  ): IO[Option[Fiber[IO, Throwable, Unit]]] =
    val baseSec = Defaults.BgHeartbeatIntervalSec
    def nextInterval: IO[FiniteDuration] =
      IO {
        val idleSec = (System.currentTimeMillis() - health.lastActivityMs.get()) / 1000
        if idleSec < 120 then baseSec.seconds // active: 30s
        else if idleSec < 600 then 60.seconds // idle 2-10min: 60s
        else 120.seconds // idle 10+min: 120s
      }
    def loop: IO[Unit] =
      for
        _ <- nextInterval.flatMap(IO.sleep)
        result <- deferred.tryGet
        _ <-
          if result.isDefined then IO.unit
          else onHeartbeat(jobId, health).handleErrorWith(_ => IO.unit) *> loop
      yield ()
    loop.start.map(Some(_))

  end startHeartbeat

  /**
   * Start a health check fiber that periodically verifies the OS process is alive.
   *
   * - Normal background job (executeBackground): the backgroundExecute fiber manages
   *   the process lifecycle and completes the deferred when the process exits.
   *   If the process crashes, backgroundExecute unblocks naturally (proc.waitFor()
   *   returns), so we just log and do nothing.
   *
   * - Registered job (registerBackgroundJob): used for auto-backgrounded commands.
   *   The watcher fiber uses fiber.joinWithNever, which may never complete if the
   *   task fiber is stuck on a blocking op. If the process dies, we must complete
   *   the deferred here to unblock cleanup.
   *
   * This fiber does NOT auto-kill processes that are idle — long-running servers
   * (web servers, sleep commands, etc.) produce no output by design and should
   * not be killed. Only the agent/LLM can decide to cancel a stuck job via the
   * existing heartbeat stuck notification (10 min idle).
   */
  private def startJobHealthCheck(
    jobId: String,
    deferred: Deferred[IO, Either[Throwable, ProcessResult]],
    health: JobHealth,
    isRegisteredJob: Boolean = false
  ): IO[Option[Fiber[IO, Throwable, Unit]]] =
    val intervalSec = Defaults.BgHealthCheckIntervalSec
    def loop: IO[Unit] =
      IO.sleep(intervalSec.seconds) *>
        deferred.tryGet.flatMap {
          case Some(_) => IO.unit // job already finished — stop checking
          case None =>
            val proc = health.processRef.get()
            if proc == null then loop // process not yet started
            else if !proc.isAlive() then
              if isRegisteredJob && health.deadNotified.compareAndSet(false, true) then
                // Only complete deferred for registered (auto-backgrounded) jobs,
                // where the watcher fiber may not detect the death
                IO.println(
                  s"[ShellSession] Job $jobId process died unexpectedly (exit: ${proc.exitValue()}) — completing deferred"
                ) *>
                  deferred
                    .complete(
                      Left(new RuntimeException("Process died unexpectedly (exit code: " + proc.exitValue() + ")"))
                    )
                    .attempt
                    .void
              else
                // Normal job: backgroundExecute will handle completion via waitFor()
                IO.unit
            else loop // process still alive, keep checking
            end if
        }
    loop.start.map(Some(_))

  end startJobHealthCheck

  private def readStream(is: java.io.InputStream, onLine: String => Unit = _ => ()): String =
    Using.resource(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) { reader =>
      val sb = new StringBuilder
      var line: String = null
      val truncationMarker = "\n[Output truncated due to size limit]\n"

      try
        while {
          line = reader.readLine()
          line != null
        } do
          sb.append(line).append("\n")
          onLine(line)
          if sb.length > MaxOutputSize then
            val trimTo = math.max(0, MaxOutputSize - truncationMarker.length)
            sb.setLength(trimTo)
            sb.append(truncationMarker)
            while { line = reader.readLine(); line != null } do ()
      catch
        case _: java.io.IOException => () // expected when proc.destroyForcibly() closes the stream on timeout/cancel

      val s = sb.toString()
      if s.trim.isEmpty then "" else s
    }

end ShellSession

object ShellSession:

  private val sessions: Ref[IO, Map[String, ShellSession]] =
    Ref.unsafe[IO, Map[String, ShellSession]](Map.empty)
  // Guards concurrent get-or-create to prevent duplicate ShellSession + cleanupFiber leaks
  private val createMutex: IO[Mutex[IO]] = Mutex[IO].memoize.flatten

  // Best-effort cleanup of all sessions on JVM exit
  sys.addShutdownHook {
    import cats.effect.unsafe.implicits.global
    sessions.get.flatMap(s => s.values.toList.traverse_(_.kill())).unsafeRunAndForget()
  }

  def forSession(sessionId: String, initialDir: Option[String] = None): IO[ShellSession] =
    createMutex.flatMap(_.lock.surround(doGetOrCreate(sessionId, initialDir)))

  private def doGetOrCreate(sessionId: String, initialDir: Option[String]): IO[ShellSession] =
    sessions.get.flatMap { m =>
      m.get(sessionId) match
        case Some(s) =>
          s.isStale.flatMap {
            case true =>
              // Cancel the old cleanup fiber before killing the session to prevent fiber leak
              s.cancelCleanupFiber() *> s.kill() *> sessions.update(_ - sessionId) *>
                ShellSession.create(sessionId, initialDir).flatMap { newS =>
                  sessions.update(_ + (sessionId -> newS)).as(newS)
                }
            case false => s.touch.as(s)
          }
        case None =>
          ShellSession.create(sessionId, initialDir).flatMap { newS =>
            sessions.update(_ + (sessionId -> newS)).as(newS)
          }
    }

  def destroySession(sessionId: String): IO[Unit] =
    sessions.modify { m =>
      m.get(sessionId) match
        case Some(s) => (m - sessionId, s.kill())
        case None => (m, IO.unit)
    }.flatten

  private[tools] def create(sessionId: String, initialDir: Option[String] = None): IO[ShellSession] =
    for
      dirRef <- Ref.of[IO, String](
        initialDir.getOrElse {
          val userDir = System.getProperty("user.dir")
          if userDir == null || userDir.isEmpty then
            val isWin = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
            sys.props.getOrElse("user.home", if isWin then "C:\\" else "/tmp")
          else userDir
        }
      )
      jobsRef <- Ref.of[IO, Map[String, BackgroundJob]](Map.empty)
      fiber <- startCleanupFiber(jobsRef)
      accessRef <- Clock[IO].realTime.map(_.toMillis).flatMap(Ref.of[IO, Long])
      aliveRef <- Ref.of[IO, Boolean](true)
      mutex <- Mutex[IO]
    yield new ShellSession(sessionId, dirRef, jobsRef, fiber, accessRef, aliveRef, mutex)

  private def startCleanupFiber(jobsRef: Ref[IO, Map[String, BackgroundJob]]): IO[Fiber[IO, Throwable, Unit]] =
    def loop: IO[Unit] =
      IO.sleep(5.minutes) *> evictCompleted(jobsRef).handleErrorWith { e =>
        IO.println(s"[ShellSession] Cleanup error: ${e.getMessage}")
      } *> IO.defer(loop)
    loop.start

  private def evictCompleted(jobsRef: Ref[IO, Map[String, BackgroundJob]]): IO[Unit] =
    jobsRef.get.flatMap {
      case jobs if jobs.isEmpty => IO.unit
      case jobs =>
        jobs.toList
          .traverse { case (id, job) =>
            job.isComplete.map(if _ then Some(id -> job) else None)
          }
          .map(_.flatten)
          .flatMap { completed =>
            // Cancel heartbeat fibers for completed jobs
            completed.traverse_ { case (_, job) =>
              job.heartbeatFiber.traverse(_.cancel) *> job.healthCheckFiber.traverse(_.cancel)
            } *> jobsRef.update(_ -- completed.map(_._1))
          }
    }
end ShellSession
