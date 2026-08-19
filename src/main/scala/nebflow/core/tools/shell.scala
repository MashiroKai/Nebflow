package nebflow.core.tools

import cats.effect.*
import cats.effect.std.Mutex
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.core.util.ProcessTree
import nebflow.shared.Defaults

import java.io.File
import java.lang.Process
import java.nio.ByteBuffer
import java.nio.charset.*
import java.util.concurrent.atomic.*

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.jdk.StreamConverters.*
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
  def execute(
    command: String,
    timeout: FiniteDuration,
    health: Option[JobHealth] = None,
    isBackground: Boolean = false
  ): IO[ProcessResult] =
    for
      _ <- checkAlive *> touch
      cwd <- currentDir.get
      result <- runProcess(command, cwd, timeout, health, isBackground)
      // On Windows (Git Bash), pwd -W returns Windows-style paths (C:/Users/...)
      // which Java's File and Paths APIs accept. Plain pwd would return MSYS2
      // paths (/c/Users/...) which are unusable for Read/Write/Edit tools.
      newCwd <- runProcess(if isWindows then "pwd -W" else "pwd", cwd, 5.seconds).attempt.map {
        case Right(r) => r.stdout.trim
        case Left(_) => cwd // keep old cwd if pwd fails
      }
      _ <- currentDir.set(newCwd)
    yield result.copy(cwd = newCwd)

  /** Start a background job and return its job ID */
  def executeBackground(
    command: String,
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
        fiber <- backgroundExecute(command, deferred, health, on_complete).start
        hbFiber <- on_heartbeat match
          case Some(cb) => startHeartbeat(jobId, deferred, health, cb)
          case None => IO.pure(None)
        hcFiber <- startJobHealthCheck(jobId, deferred, health, command = command)
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
        hcFiber <- startJobHealthCheck(jobId, deferred, health, isRegisteredJob = true, command = command)
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
                val killProcess =
                  val proc = job.health.processRef.get()
                  if proc != null && proc.isAlive then ProcessTree.killProcessTree(proc)
                  else IO.unit
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

  /**
   * Find Git Bash on Windows. Git for Windows is a declared dependency.
   * We check common installation paths explicitly to avoid picking up
   * WSL's bash.exe (C:\Windows\System32\bash.exe), which uses a different
   * filesystem layout.
   */
  private lazy val windowsBashPath: String =
    val progFiles = sys.env.getOrElse("ProgramFiles", "C:\\Program Files")
    val progFilesX86 = sys.env.getOrElse("ProgramFiles(x86)", "C:\\Program Files (x86)")
    val localAppData = sys.env.getOrElse("LOCALAPPDATA", "")
    val candidates = List(
      s"$progFiles\\Git\\bin\\bash.exe",
      s"$progFilesX86\\Git\\bin\\bash.exe"
    ) ++ (if localAppData.nonEmpty then List(s"$localAppData\\Programs\\Git\\bin\\bash.exe") else Nil)
    candidates.find(p => new File(p).exists()).getOrElse("bash")

  private def buildProcessBuilder(command: String, cwd: String): ProcessBuilder =
    // On Mac/Linux: bash -c "command" — straightforward.
    // On Windows: bash -s — read commands from stdin. This avoids Java
    // ProcessBuilder's Windows argument quoting (which uses \" to escape
    // embedded double quotes) being misinterpreted by Cygwin/MSYS2 bash's
    // argument parser, causing commands with quotes, &&, ||, or newlines
    // to be mangled.
    val bashPath = if isWindows then windowsBashPath else "bash"
    val pb =
      if isWindows then new ProcessBuilder(bashPath, "-s")
      else new ProcessBuilder(bashPath, "-c", command)
    // Empty or invalid working directory causes failures. Fall back to user home.
    val safeCwd =
      if cwd == null || cwd.isEmpty || !new File(cwd).exists() then
        sys.props.getOrElse("user.home", if isWindows then "C:\\" else "/tmp")
      else cwd
    pb.directory(new File(safeCwd))
    if isWindows then
      pb.redirectInput(ProcessBuilder.Redirect.PIPE)
      // Force UTF-8 for Python's C runtime (Git Bash itself is already UTF-8).
      val env = pb.environment()
      env.put("PYTHONUTF8", "1")
      env.put("PYTHONIOENCODING", "utf-8")
    else pb.redirectInput(new File("/dev/null"))
    pb.redirectErrorStream(false) // stdout/stderr separated
    pb

  end buildProcessBuilder

  private[tools] val SleepCommandRe = """\bsleep\s+\d+""".r

  /** Grace period before checking if a quiet background process is stuck. */
  private val StuckDetectionGracePeriod: FiniteDuration = 30.seconds

  /**
   * #22 (2026-08-19): foreground no-progress ceiling. A foreground command
   * with NO new output AND no CPU activity for this long is killed with an
   * informative error — it is almost certainly waiting for interactive input
   * or hung on something the agent cannot see. Commands that keep producing
   * output or burning CPU (builds, test suites) run on; sleep-like commands
   * are excluded (#319: `sleep N` foreground must complete).
   */
  private val ForegroundSampleInterval: FiniteDuration = 30.seconds
  private val ForegroundNoProgressTimeout: FiniteDuration = 10.minutes

  private val shellLogger = NebflowLogger.forName("nebflow.shell")

  /** CPU sampling window to distinguish slow builds from idle prompts. */
  private val CpuSampleInterval: FiniteDuration = 2.seconds

  /**
   * Minimum CPU delta (nanos) during sampling to consider a process "active".
   * 10ms of CPU work in 2s means the process is computing, not waiting for input.
   */
  private val CpuActiveThresholdNanos: Long = 10_000_000L

  /** Sum total CPU duration (nanos) of a process and all its descendants. */
  private[tools] def sampleProcessCpuTime(proc: Process): Long =
    val handle = proc.toHandle
    def cpuNanos(ph: ProcessHandle): Long =
      val opt = ph.info().totalCpuDuration()
      if opt.isPresent then opt.get().toNanos else 0L
    // Method 1: ProcessHandle descendants API
    val viaHandle = cpuNanos(handle) + handle.descendants().toScala(List).map(cpuNanos).sum
    // Method 2: ps-based enumeration (more reliable for deep trees on macOS, e.g. sbt → sh → java)
    val viaPs = if !isWindows then sampleCpuTimeViaPs(proc.pid) else 0L
    math.max(viaHandle, viaPs)

  /**
   * Enumerate all descendant PIDs via `ps` and sum their CPU time through
   * the ProcessHandle API (nanosecond precision). More reliable than
   * `ProcessHandle.descendants()` which may miss deeply nested processes.
   */
  private def sampleCpuTimeViaPs(rootPid: Long): Long =
    try
      val pb = new ProcessBuilder("ps", "-A", "-o", "pid=,ppid=")
      pb.redirectInput(new File("/dev/null"))
      pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
      pb.redirectErrorStream(true)
      val psProc = pb.start()
      val ok = psProc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      if !ok then
        psProc.destroyForcibly()
        0L
      else
        val output = new String(psProc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        psProc.getInputStream.close()
        sumCpuTimeFromProcessTree(output, rootPid)
    catch case _: Exception => 0L

  /** Parse `ps` output, build process tree, and sum CPU time of root + all descendants. */
  private def sumCpuTimeFromProcessTree(psOutput: String, rootPid: Long): Long =
    val childrenMap = scala.collection.mutable.Map.empty[Long, List[Long]]
    for line <- psOutput.linesIterator do
      val parts = line.trim.split("\\s+")
      if parts.length >= 2 then
        parts(0).toLongOption.foreach { pid =>
          parts(1).toLongOption.foreach { ppid =>
            childrenMap(ppid) = pid :: childrenMap.getOrElse(ppid, Nil)
          }
        }
    def collect(pid: Long): List[Long] =
      childrenMap.getOrElse(pid, Nil).flatMap(child => child :: collect(child))
    val allPids = rootPid :: collect(rootPid)
    allPids.map { pid =>
      val phOpt = ProcessHandle.of(pid)
      if phOpt.isPresent then
        val cpuOpt = phOpt.get().info().totalCpuDuration()
        if cpuOpt.isPresent then cpuOpt.get().toNanos else 0L
      else 0L
    }.sum

  end sumCpuTimeFromProcessTree

  private def runProcess(
    command: String,
    cwd: String,
    timeout: FiniteDuration,
    health: Option[JobHealth] = None,
    isBackground: Boolean = false
  ): IO[ProcessResult] =
    IO.blocking {
      try buildProcessBuilder(command, cwd).start()
      catch
        case e: java.io.IOException =>
          throw new java.io.IOException(
            if isWindows then
              "bash.exe not found. The Bash tool requires Git for Windows.\n" +
                "Install it from https://git-scm.com/download/win or re-run the Nebflow installer."
            else "bash not found in PATH. Please install bash (e.g. apt install bash).",
            e
          )
    }.bracket { proc =>
      val h = health.getOrElse(new JobHealth())
      val storeProc = IO(h.processRef.set(proc))
      // On Windows, write the command to bash's stdin (bash -s mode), then
      // close stdin to signal EOF. This avoids Java ProcessBuilder's Windows
      // argument quoting which mangles double quotes and special characters.
      val writeStdin = IO {
        if isWindows then
          try
            val os = proc.getOutputStream()
            os.write(command.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            os.close()
          catch case _: java.io.IOException => ()
      }
      val stdoutIO = IO.blocking(
        readStream(
          proc.getInputStream,
          line =>
            // Health tracking uses the local health tracker (h), not the
            // outer health — ensures stuck detection works even when caller
            // passed health = None.
            h.lastActivityMs.set(System.currentTimeMillis())
            h.outputLineCount.incrementAndGet(),
          isProcessExited = () => !proc.isAlive
        )
      )
      val stderrIO = IO.blocking(
        readStream(
          proc.getErrorStream,
          line =>
            h.lastActivityMs.set(System.currentTimeMillis())
            h.outputLineCount.incrementAndGet(),
          isProcessExited = () => !proc.isAlive
        )
      )
      // #22: stream readers are poll-based (see readStream) — a process-exit
      // grace bounds them even when an orphaned grandchild keeps holding the
      // pipes. waitIO stays a plain blocking wait (proc death always ends it).
      val waitIO: IO[Int] = IO.blocking {
        proc.waitFor()
        proc.exitValue()
      }

      // ── Stuck process detection (background tasks only) ──────────────
      // Foreground commands run to completion (#319) — their safety nets are
      // the explicit timeout watchdog and the no-progress ceiling below.
      //
      // Background tasks have no time limit, so a command waiting for stdin
      // (ssh, sudo, telnet…) would hang forever. After the grace period
      // (30s), if the process has zero output AND zero CPU activity, we
      // kill it with an informative error so the LLM can retry differently.
      //
      // Sleep-like commands are excluded — they legitimately produce no
      // output while their timer runs.
      val isSleepLike = SleepCommandRe.findFirstIn(command).isDefined
      val enableStuckDetection = isBackground && !isSleepLike

      // Shared by the background stuck detector and the foreground no-progress
      // ceiling: set when WE killed the tree (vs. natural exit) so the result
      // carries the informative TimeoutException instead of a bare exit code.
      val stuckFlag = Ref.unsafe[IO, Boolean](false)

      // ── Foreground no-progress ceiling (#22, 2026-08-19) ────────────────
      // A foreground command that produces no output and burns no CPU for
      // ForegroundNoProgressTimeout is killed — interactive prompts / hung
      // waits would otherwise freeze the agent's turn forever (default
      // timeout is 365.days). Progress (output OR CPU) resets the window, so
      // long builds and test suites run to completion (#319 preserved).
      def foregroundNoProgressWatch: IO[Unit] =
        def watch(lastLines: Int, lastCpu: Long, idleMs: Long): IO[Unit] =
          IO.sleep(ForegroundSampleInterval) *> IO {
            val alive = proc.isAlive()
            val lines = h.outputLineCount.get()
            val cpu = if alive then sampleProcessCpuTime(proc) else 0L
            (alive, lines, cpu)
          }.flatMap { (alive, lines, cpu) =>
            if !alive then IO.unit
            else if lines > lastLines || (cpu - lastCpu) >= CpuActiveThresholdNanos then
              watch(lines, cpu, 0L)
            else if idleMs + ForegroundSampleInterval.toMillis >= ForegroundNoProgressTimeout.toMillis then
              IO.delay(
                shellLogger.warn(
                  s"Foreground command idle for ${(idleMs + ForegroundSampleInterval.toMillis) / 1000}s " +
                    s"(no output, no CPU) — killing: ${command.take(80)}"
                )
              ) *> stuckFlag.set(true) *> ProcessTree.killProcessTree(proc)
            else watch(lastLines, lastCpu, idleMs + ForegroundSampleInterval.toMillis)
          }
        watch(h.outputLineCount.get(), 0L, 0L)

      // ── Hard timeout watchdog (#22, 2026-08-19 20:35 incident) ──────────
      // `.timeout` over the three IO.blocking reads below is SOFT: IO.blocking
      // cannot be interrupted, so the TimeoutException only surfaces after the
      // reads return — i.e. after every pipe-holding descendant exits. In the
      // incident the explicit 10-min timeout fired at 20:45 but the tool
      // returned at 21:12 because an orphaned sbt→java test JVM kept the pipe
      // open. The watchdog kills the whole tree AT the deadline: pipes hit
      // EOF, reads unblock, and the timeout surfaces within milliseconds.
      val timeoutWatchdog =
        IO.sleep(timeout) *>
          IO.delay(
            shellLogger.warn(s"Command timeout (${timeout.toSeconds}s) reached — killing process tree: ${command.take(80)}")
          ) *>
          ProcessTree.killProcessTree(proc)

      for
        _ <- storeProc
        _ <- writeStdin

        // Stuck detector fiber: after the grace period, if the process is
        // quiet, sample CPU over a short window before deciding to kill.
        stuckFiber <- (
          if enableStuckDetection then
            IO.sleep(StuckDetectionGracePeriod) *>
              IO {
                val alive = proc.isAlive()
                val hasOutput = h.outputLineCount.get() > 0
                alive && !hasOutput
              }.flatMap { possiblyStuck =>
                if !possiblyStuck then IO.unit
                else
                  // Quiet but alive — sample CPU to distinguish slow builds
                  // from interactive prompts waiting for input.
                  for
                    cpu1 <- IO(sampleProcessCpuTime(proc))
                    _ <- IO.sleep(CpuSampleInterval)
                    cpu2 <- IO(sampleProcessCpuTime(proc))
                    cpuActive = (cpu2 - cpu1) >= CpuActiveThresholdNanos
                    _ <-
                      if !cpuActive then
                        IO(proc.isAlive()).flatMap { stillAlive =>
                          if stillAlive then stuckFlag.set(true) *> ProcessTree.killProcessTree(proc)
                          else IO.unit
                        }
                      else IO.unit
                  yield ()
              }
          else IO.unit
        ).start

        noProgressFiber <- (if !isBackground && !isSleepLike then foregroundNoProgressWatch else IO.unit).start
        watchdogFiber <- timeoutWatchdog.start

        // Main execution: read stdout/stderr and wait for process completion
        result <- (stdoutIO, stderrIO, waitIO)
          .parMapN { (out, err, code) =>
            ProcessResult(out, err, code, cwd)
          }
          .timeout(timeout)

        // Cleanup: cancel the watchdog / detector fibers
        _ <- watchdogFiber.cancel
        _ <- noProgressFiber.cancel
        _ <- stuckFiber.cancel

        // Check if the process was killed by the stuck detector / no-progress ceiling
        wasStuck <- stuckFlag.get
        finalResult <-
          if wasStuck then
            IO.raiseError(
              new TimeoutException(
                (if isBackground then
                   "Command produced no output within " + StuckDetectionGracePeriod.toSeconds +
                     " seconds and no CPU activity was detected."
                 else
                  "Command produced no output and no CPU activity for " +
                    ForegroundNoProgressTimeout.toSeconds + " seconds (foreground no-progress ceiling).") +
                  " This command likely requires interactive terminal input (or is hung). " +
                  "Use a non-interactive alternative, pass an explicit timeout, or run it " +
                  "manually in your terminal."
              )
            )
          else IO.pure(result)
      yield finalResult
      end for
    } { proc =>
      ProcessTree.killProcessTree(proc)
    }

  private def backgroundExecute(
    command: String,
    deferred: Deferred[IO, Either[Throwable, ProcessResult]],
    health: JobHealth,
    on_complete: Option[Either[Throwable, ProcessResult] => IO[Unit]] = None
  ): IO[Unit] =
    // Background jobs have no timeout — they run until completion or cancellation.
    // The idle-timeout health check may kill the process and complete the deferred
    // with a TimeoutException before execute() returns; in that case the on_complete
    // callback must receive the TimeoutException (not the raw exit-137 result) so
    // the agent gets a descriptive timeout message.
    execute(command, 365.days, Some(health), isBackground = true).attempt.flatMap { result =>
      deferred.tryGet.flatMap {
        case Some(existing) if existing.isLeft =>
          // Deferred already completed with an error (idle timeout, process death, etc.)
          // — use that error for the callback instead of the raw execute result.
          deferred.complete(result).void *>
            on_complete.fold(IO.unit) { cb =>
              IO.delay(cb(existing))
                .flatten
                .handleErrorWith(e =>
                  IO.delay(
                    NebflowLogger.forName("nebflow.shell").warn(s"Background job callback failed: ${e.getMessage}")
                  )
                )
            }
        case _ =>
          deferred.complete(result).void *>
            on_complete.fold(IO.unit) { cb =>
              IO.delay(cb(result))
                .flatten
                .handleErrorWith(e =>
                  IO.delay(
                    NebflowLogger.forName("nebflow.shell").warn(s"Background job callback failed: ${e.getMessage}")
                  )
                )
            }
      }
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
   * Idle timeout: if the process has been alive but produced no output for
   * BgIdleTimeoutSec, it is forcibly killed and the deferred is completed with
   * a TimeoutException. The backgroundExecute fiber's on_complete callback then
   * notifies the agent, which can retry or continue — this is the primary
   * recovery path for delegate/subtask agents that would otherwise be blocked
   * forever by a stuck background command.
   */
  private def startJobHealthCheck(
    jobId: String,
    deferred: Deferred[IO, Either[Throwable, ProcessResult]],
    health: JobHealth,
    isRegisteredJob: Boolean = false,
    command: String = ""
  ): IO[Option[Fiber[IO, Throwable, Unit]]] =
    val intervalSec = Defaults.BgHealthCheckIntervalSec
    val idleTimeoutMs = Defaults.BgIdleTimeoutSec.toLong * 1000L
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
                NebflowLogger
                  .forName("nebflow.shell")
                  .info(s"Job $jobId process died unexpectedly (exit: ${proc.exitValue()}) — completing deferred") *>
                  deferred
                    .complete(
                      Left(new RuntimeException("Process died unexpectedly (exit code: " + proc.exitValue() + ")"))
                    )
                    .attempt
                    .void
              else
                // Normal job: backgroundExecute will handle completion via waitFor()
                IO.unit
            else
              // Process still alive — check idle timeout
              val isSleepLike = SleepCommandRe.findFirstIn(command).isDefined
              val idleMs = System.currentTimeMillis() - health.lastActivityMs.get()
              if !isSleepLike && idleMs > idleTimeoutMs && health.deadNotified.compareAndSet(false, true) then
                // Idle timeout: kill the process and complete deferred with error.
                // The on_complete callback (in backgroundExecute) will notify the
                // agent with the timeout message, unblocking delegate/subtask agents.
                val logger = NebflowLogger.forName("nebflow.shell")
                logger.warn(
                  s"Background job $jobId idle for ${idleMs / 1000}s (timeout ${Defaults.BgIdleTimeoutSec}s) — auto-cancelling"
                ) *>
                  ProcessTree.killProcessTree(proc) *>
                  deferred
                    .complete(
                      Left(
                        new TimeoutException(
                          s"Background command was idle (no output) for ${idleMs / 1000}s " +
                            s"and was automatically cancelled. The command may be stuck " +
                            s"or waiting for interactive input. Consider using a non-interactive " +
                            s"alternative or running it manually."
                        )
                      )
                    )
                    .attempt
                    .void
              else loop
              end if
            end if
        }
    loop.start.map(Some(_))

  end startJobHealthCheck

  /**
   * Poll-based line reader (#22, 2026-08-19).
   *
   * A blocking `BufferedReader.readLine()` cannot be interrupted — IO.blocking
   * defers cancellation until the native read returns, and a pipe held open by
   * an orphaned grandchild (reparented to launchd, invisible to every tree
   * walk) never returns EOF. That is how a 10-minute explicit timeout ran for
   * 37 minutes in the 20:35 incident while the turn looked silently dead.
   *
   * This reader never blocks while the process is alive: it polls
   * `reader.ready()` on a 25ms cadence and reads only when data is available.
   * After the process exits it drains whatever is buffered for a short grace
   * window, then stops with the output it has — bounding every pipe-holder
   * class (orphaned grandchildren included) to exit + grace, with zero added
   * latency for normal commands.
   *
   * Known narrow corner (documented, accepted): a PARTIAL line (no newline
   * yet) read while the process is alive may block in readLine() until the
   * writer finishes the line or dies; if a surviving orphan then holds the
   * pipe open forever, so can this read — requires both a mid-line write at
   * exit AND an orphan holder, vs. the previous every-orphan hang.
   */
  private def readStream(
      is: java.io.InputStream,
      onLine: String => Unit = _ => (),
      isProcessExited: () => Boolean = () => true,
      exitGraceMs: Long = 250L
  ): String =
    // On Windows, detect whether the output is UTF-8 or system ANSI code page
    // (GBK on Chinese Windows). Git Bash and Python (with PYTHONUTF8=1) output
    // UTF-8, but native Windows programs (ipconfig, systeminfo, cmd, etc.) output
    // in the system ANSI code page. We probe the first chunk to pick the right
    // charset, then read the entire stream with it.
    val (stream, charset) =
      if isWindows then probeCharset(is)
      else (is, StandardCharsets.UTF_8)

    Using.resource(new java.io.BufferedReader(new java.io.InputStreamReader(stream, charset))) { reader =>
      val sb = new StringBuilder
      var line: String = null
      val truncationMarker = "\n[Output truncated due to size limit]\n"
      var truncated = false
      var exitedAtMs = -1L
      var done = false

      while !done do
        try
          if reader.ready() then
            exitedAtMs = -1L
            line = reader.readLine()
            if line == null then done = true // EOF
            else
              onLine(line)
              if !truncated then
                sb.append(line).append("\n")
                if sb.length > MaxOutputSize then
                  val trimTo = math.max(0, MaxOutputSize - truncationMarker.length)
                  sb.setLength(trimTo)
                  sb.append(truncationMarker)
                  truncated = true
          else if isProcessExited() then
            val now = System.currentTimeMillis()
            if exitedAtMs < 0 then exitedAtMs = now
            // Grace: buffered tail data surfaces as ready() within this window;
            // no data after it (writers dead or orphaned) → stop, EOF or not.
            if now - exitedAtMs >= exitGraceMs then done = true
            else Thread.sleep(25)
          else Thread.sleep(25)
        catch
          // expected when proc.destroyForcibly() closes the stream on timeout/cancel
          case _: java.io.IOException => done = true
      end while

      val s = sb.toString()
      if s.trim.isEmpty then "" else s
    }

  end readStream

  /**
   * Probe the first bytes of a stream to detect UTF-8 vs system ANSI code page.
   * Returns the stream (rewound via PushbackInputStream) and the detected charset.
   *
   * UTF-8 has strict multi-byte structure — GBK output almost certainly contains
   * byte sequences that violate it, so strict UTF-8 decoding is a reliable detector.
   * The probe bytes are pushed back so the caller's InputStreamReader sees the
   * complete stream from the beginning.
   */
  private def probeCharset(is: java.io.InputStream): (java.io.InputStream, Charset) =
    val ProbeSize = 4096
    val pushback = new java.io.PushbackInputStream(is, ProbeSize)
    val probe = new Array[Byte](ProbeSize)
    val n = pushback.read(probe)
    if n <= 0 then (pushback, StandardCharsets.UTF_8)
    else
      pushback.unread(probe, 0, n)
      val charset =
        try
          val decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
          decoder.decode(ByteBuffer.wrap(probe, 0, n))
          StandardCharsets.UTF_8
        catch
          case _: CharacterCodingException =>
            try Charset.forName("GBK")
            catch case _: Exception => StandardCharsets.UTF_8
      (pushback, charset)
    end if
  end probeCharset

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
