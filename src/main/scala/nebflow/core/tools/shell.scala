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
  val deadNotified: AtomicBoolean = new AtomicBoolean(false),
  /** 后台任务输出查看批（2026-09-09）：逐行输出汇聚口——runProcess 的 stdout/
    * stderr 行回调调用。仅后台路径（executeBackground）注入 BgTaskOutputStore
    * 缓冲 sink；前台/默认 no-op（前台输出直接随 ProcessResult 返回，无需缓冲）。 */
  val outputSink: String => Unit = _ => ()
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
  // fiber 类型用存在类型 ?——cancel/join.void 对 ? 均可用。
  fiber: Fiber[IO, Throwable, ?],
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
  /** #391 机制 E：本 session 当前存活的 OS 进程（前台 + 后台 runProcess 启动时
    * 注册、bracket release 注销）。AgentControl restart/Stop 时 killSessionProcesses
    * 遍历此表杀进程树——修复「cancelCurrentTurn 只取消 fiber、IO.blocking 不中断、
    * bracket release 永不执行」的 B9 残留根因链。 */
  private val activeProcesses: Ref[IO, Set[Process]],
  private val cleanupFiber: Fiber[IO, Throwable, Unit],
  private val lastAccessed: Ref[IO, Long],
  private val isAlive: Ref[IO, Boolean],
  private val lifecycleMutex: Mutex[IO],
  /** 阶段 2a 沙箱（§A.4）：会话持有的策略——BashSeatbelt 包装 + 初 cwd 的依据。
    * None/off = 旧行为（初 cwd=user.dir，无包装）。会话内不可变（root 会话级
    * 不可变，§A.2）。 */
  private val sandbox: Option[nebflow.core.sandbox.SandboxPolicy] = None
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

  /** Liveness probe for the registry self-heal path (doGetOrCreate): a killed
    * session left in the map must be replaced, not returned. */
  private[tools] def isDead: IO[Boolean] = isAlive.get.map(!_)

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
    jobIdOverride: Option[String] = None,
    // #391 机制 B：后台硬超时 + 停滞窗口阈值（nebflow.json 可配，默认 Defaults）。
    hardTimeoutMs: Long = Defaults.BashBackgroundHardTimeoutMs,
    stuckWindowSec: Int = Defaults.BashStuckWindowSec,
    // 测试注入用：health check 采样间隔（生产默认 30s）。
    healthCheckIntervalSec: Int = Defaults.BgHealthCheckIntervalSec,
    // 节点完成闸批（作者 2026-09-05 18:29 裁定）：persistent=true = 服务型
    // （长驻 server）——豁免 B1 idle 杀与 B2 硬超时+停滞杀（idle server 零 CPU
    // 被 5min idle 杀、30min 被硬超时杀对 server 全是误杀）。用户自担：仍可
    // cancel_background_job 显式取消、killSessionProcesses/kill 照常清理、
    // WS 心跳指示器照常可见。心跳保持（可见性不受豁免影响）。
    persistent: Boolean = false,
    // 输出查看批（2026-09-09）：逐行输出汇聚口（BgTaskOutputStore 缓冲 sink，
    // BashTool 后台路径注入）。None = 无缓冲（默认，兼容既有调用方/测试）。
    outputSink: Option[String => Unit] = None
  ): IO[String] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        jobId <- jobIdOverride.fold(IO.randomUUID.map(_.toString.take(8)))(IO.pure)
        deferred <- Deferred[IO, Either[Throwable, ProcessResult]]
        health = new JobHealth(outputSink = outputSink.getOrElse(_ => ()))
        fiber <- backgroundExecute(command, deferred, health, on_complete).start
        hbFiber <- on_heartbeat match
          case Some(cb) => startHeartbeat(jobId, deferred, health, cb)
          case None => IO.pure(None)
        hcFiber <-
          if persistent then IO.pure(None) // 服务型：不启 B1/B2 看护 fiber（豁免杀）
          else startJobHealthCheck(
            jobId,
            deferred,
            health,
            command = command,
            hardTimeoutMs = hardTimeoutMs,
            stuckWindowSec = stuckWindowSec,
            checkIntervalSec = healthCheckIntervalSec
          )
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

  /** Cancel a background job by its ID.
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
   * #391 机制 E：杀本 session 全部存活 OS 进程树（前台 + 后台 runProcess 注册的
   * 进程）。幂等 no-op（无注册进程时）。与 kill() 的区别：kill() 只取消后台任务
   * fiber 并 complete deferred，不碰前台进程——而前台进程卡在 IO.blocking 上时
   * fiber 取消无效（B9 盲区 4），必须在此直接 killProcessTree。
   */
  def killActiveProcesses(): IO[Unit] =
    lifecycleMutex.lock.surround {
      activeProcesses.getAndSet(Set.empty).flatMap { procs =>
        procs.toList.traverse_(p => ProcessTree.killProcessTree(p))
      }
    }

  /**
   * Kill this session: cancel all background jobs and cleanup fiber.
   *  Serialised with lifecycleMutex to prevent executeBackground from adding
   *  jobs after we read the map.
   *
   * #391 机制 E：kill 现在也杀后台任务的 OS 进程树（原实现只 cancel fiber——
   * IO.blocking 取消不中断线程，后台命令进程残留）。前台进程由
   * killActiveProcesses 单独处理（killSessionProcesses 组合两者）。
   */
  def kill(): IO[Unit] =
    lifecycleMutex.lock.surround {
      for
        _ <- isAlive.set(false)
        jobs <- backgroundJobs.getAndSet(Map.empty)
        _ <- jobs.values.toList.traverse_(job =>
          val proc = job.health.processRef.get()
          (if proc != null && proc.isAlive then ProcessTree.killProcessTree(proc) else IO.unit) *>
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
   * Resolution chain lives on the companion (ShellSession.resolvedBashPath)
   * so the boot-time dependency probe reads the same single source.
   */
  private lazy val windowsBashPath: String = ShellSession.resolvedBashPath

  private def buildProcessBuilder(command: String, cwd: String): ProcessBuilder =
    // On Mac/Linux: bash -c "command" — straightforward.
    // On Windows: bash -s — read commands from stdin. This avoids Java
    // ProcessBuilder's Windows argument quoting (which uses \" to escape
    // embedded double quotes) being misinterpreted by Cygwin/MSYS2 bash's
    // argument parser, causing commands with quotes, &&, ||, or newlines
    // to be mangled.
    val bashPath = if isWindows then windowsBashPath else "bash"
    val plain: ProcessBuilder =
      if isWindows then
        // Bundled MinGit bash (Team #11): MinGit has no bin/bash.exe wrapper
        // that would assemble the MSYS environment, so run it as a LOGIN
        // shell (-l) — /etc/profile then builds PATH from MSYSTEM=MINGW64
        // (mingw64/bin holds git.exe) and inherits the Windows PATH. System
        // Git installs keep the plain form (their bin/bash.exe wrapper
        // already does this setup).
        if ShellSession.isBundledBash then
          val bundled = new ProcessBuilder(bashPath, "-l", "-s")
          bundled.environment().put("MSYSTEM", "MINGW64")
          bundled
        else new ProcessBuilder(bashPath, "-s")
      else new ProcessBuilder(bashPath, "-c", command)
    // 执行环境 provider 接缝（design §1.1 F11：**接缝不是围栏，是接入点**）。
    // [沙箱拆围栏批 S3，2026-09-10] 缺省 provider=host ⇒ SandboxRuntime.current =
    // `SandboxBackend.Host`，wrap 恒 None ⇒ 走 plain（宿主路径**不再包裹**）；
    // provider=local-process ⇒ Seatbelt 后端，wrap 返回 sandbox-exec 包裹 argv
    // （§4.5 回退点，切 provider 即恢复）。**本调用点保留为唯一 wrap 接缝**：容器/VM
    // 执行面的 Bash 命令未来必须从同一处注入（「一处切换、全量生效」的机械支点）。
    // 非宿主 provider 若不可用，BashTool.call 已在入口显式失败（绝不静默回落宿主）。
    val pb: ProcessBuilder =
      if !isWindows && sandbox.exists(_.enabled) then
        nebflow.core.sandbox.SandboxRuntime.current.wrap(List("bash", "-c", command), sandbox.get) match
          case Some(wrapped) => new ProcessBuilder(wrapped*)
          case None => plain
      else plain
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
   * that makes NO progress — no new output AND no CPU progress within a
   * single sample window (see Defaults.ForegroundCpuProgressNanos) — for this
   * long is killed with an informative error: it is almost certainly waiting
   * for interactive input or hung on something the agent cannot see. Commands
   * that keep producing output, or that burn real CPU every window (builds,
   * test suites), run on; sleep-like commands are excluded (#319: `sleep N`
   * foreground must complete).
   *
   * 2026-09-10（验收项 3 修复）：判据是**窗口增量**——每次比较的都是「本窗」烧掉的
   * CPU，基线在每个分支都推进，见 `foregroundNoProgressWatch`。
   *
   * 采样窗与判死窗口可经 system prop 缩短（kill-switch 先例），供下游真实形态复现 /
   * 验收把 10min 级单臂压到分钟级——见 Defaults.ForegroundSampleIntervalMs /
   * Defaults.ForegroundNoProgressTimeoutMs。
   */
  private def ForegroundSampleInterval: FiniteDuration = Defaults.ForegroundSampleIntervalMs.millis
  private def ForegroundNoProgressTimeout: FiniteDuration = Defaults.ForegroundNoProgressTimeoutMs.millis

  private val shellLogger = NebflowLogger.forName("nebflow.shell")

  /** CPU sampling window to distinguish slow builds from idle prompts. */
  private val CpuSampleInterval: FiniteDuration = 2.seconds

  /**
   * Minimum CPU delta (nanos) during sampling to consider a process "active".
   * 10ms of CPU work in 2s means the process is computing, not waiting for input.
   * private[tools]（#391 机制 D）：BashTool 活动桥接的 hasProgress 与后台停滞
   * 探测（2s 采样窗）共用此常量。
   *
   * 2026-09-10 卡死判据换轴（阈值解耦）：前台 no-progress ceiling **不再**用本
   * 常量——10ms/30s = 0.033% 单核，任何有偶发唤醒的进程都能越过（事故实证：
   * 前台 dev server 1.786 ms/s = 5.4 倍阈值 ⇒ 10 分钟安全网被微动无条件解除，
   * 命令跑了 2h50m 未被杀）。ceiling 改用
   * Defaults.ForegroundCpuProgressNanos（1s/30s 窗 = 3.3% 单核，与采样窗同量纲）。
   */
  private[tools] val CpuActiveThresholdNanos: Long = 10_000_000L

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
      val pb = new ProcessBuilder("ps", "-A", "-o", "pid=,ppid=,time=")
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

  /** Parse `ps -A -o pid=,ppid=,time=` output, build process tree, and sum the
    * kernel-reported cumulative CPU of root + all descendants.
    *
    * #17 completion (2026-08-10 qa 打回链): the `time` column is the ONLY CPU
    * source that covers GRANDCHILDREN on macOS — ProcessHandle
    * .totalCpuDuration() is only implemented for the current JVM process
    * there (empirically: 1 of allProcesses() reports it). Tree-blind sampling
    * false-killed redirected-output jobs whose work runs in a forked child
    * (sbt run, Maven exec) even after the tree-aware enumeration "fix".
    * `time` format: macOS `MM:SS.cc` (centisecond granularity — 10ms, equal
    * to CpuActiveThresholdNanos; a busy child accrues ~2s per 2s sample
    * window), Linux `[[dd-]hh:]mm:ss`. */
  private def sumCpuTimeFromProcessTree(psOutput: String, rootPid: Long): Long =
    case class ProcEntry(ppid: Long, cpuNanos: Long)
    val procs = scala.collection.mutable.Map.empty[Long, ProcEntry]
    for line <- psOutput.linesIterator do
      val parts = line.trim.split("\\s+")
      if parts.length >= 3 then
        parts(0).toLongOption.foreach { pid =>
          parts(1).toLongOption.foreach { ppid =>
            procs(pid) = ProcEntry(ppid, parsePsTimeToNanos(parts(2)))
          }
      }
    val childrenMap = scala.collection.mutable.Map.empty[Long, List[Long]]
    procs.foreach { case (pid, e) =>
      childrenMap(e.ppid) = pid :: childrenMap.getOrElse(e.ppid, Nil)
    }
    def collect(pid: Long): List[Long] =
      childrenMap.getOrElse(pid, Nil).flatMap(child => child :: collect(child))
    (rootPid :: collect(rootPid)).map(pid => procs.get(pid).map(_.cpuNanos).getOrElse(0L)).sum

  /** `[[dd-]hh:]mm:ss[.cc]` → nanos. Returns 0 on unparseable input (ps format
    * drift degrades to "no signal seen", never crashes the detector). */
  private def parsePsTimeToNanos(t: String): Long =
    try
      val (days, rest) = t.split("-").toList match
        case d :: r if r.nonEmpty => (d.toLong, r.mkString("-"))
        case other                => (0L, other.mkString)
      val units = rest.split(":").map(_.trim).filter(_.nonEmpty).map(_.toDouble)
      // rightmost = seconds, then minutes, hours
      var secs = 0.0
      var mult = 1.0
      units.reverse.foreach { u =>
        secs += u * mult
        mult *= 60.0
      }
      ((days * 86400.0 + secs) * 1e9).toLong
    catch case _: Exception => 0L

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
      // #391 机制 E：进程启动即注册进 session 活动表（前台 + 后台），bracket
      // release 注销——restart/Stop 时 killSessionProcesses 靠它杀进程树。
      val registerActive = activeProcesses.update(_ + proc)
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
            h.outputLineCount.incrementAndGet()
            // 输出查看批：行序按读取到达序（stdout/stderr 两读线程并发，全局
            // 近似行序——简洁优先，不做流间严格排序）。
            h.outputSink(line),
          isProcessExited = () => !proc.isAlive
        )
      )
      val stderrIO = IO.blocking(
        readStream(
          proc.getErrorStream,
          line =>
            h.lastActivityMs.set(System.currentTimeMillis())
            h.outputLineCount.incrementAndGet()
            h.outputSink(line),
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
      // A foreground command that produces no output and makes no CPU progress
      // *within a sample window* for ForegroundNoProgressTimeout is killed —
      // interactive prompts / hung waits would otherwise freeze the agent's
      // turn forever (default timeout is 365.days). Progress (output OR a
      // window CPU increment over Defaults.ForegroundCpuProgressNanos) resets
      // the window, so long builds and test suites run to completion
      // (#319 preserved).
      def foregroundNoProgressWatch: IO[Unit] =
        def watch(lastLines: Int, lastCpu: Long, idleMs: Long): IO[Unit] =
          IO.sleep(ForegroundSampleInterval) *> IO {
            val alive = proc.isAlive()
            val lines = h.outputLineCount.get()
            val cpu = if alive then sampleProcessCpuTime(proc) else 0L
            (alive, lines, cpu)
          }.flatMap { (alive, lines, cpu) =>
            if !alive then IO.unit
            // Strictly-greater: macOS `ps` time quantizes to centiseconds —
            // one quantum (10ms) EQUALS the bridge's threshold.
            // 2026-09-10 换轴（阈值解耦）：本 ceiling 的「有 CPU 进展」判据改用
            // Defaults.ForegroundCpuProgressNanos（1s / 30s 采样窗 ≈ 3.3% 单核，
            // 与采样窗同量纲），**不再**共用 BashTool 活动桥接的 10ms 常量：后者
            // 10ms/30s = 0.033% 单核，进程一有偶发唤醒就解除窗口（事故实证：该
            // 前台命令凭 CPU 微动（5.4 倍阈值）绕过了本条 10 分钟安全网，跑了 2h50m）。
            // 真正在算的进程（构建/测试）一个 30s 窗烧掉远超 1s CPU → 照常续跑。
            //
            // 2026-09-10（验收项 3 修复）：`lastCpu`/`lastLines` 是**上一个采样窗**的
            // 基线，两条分支都推进 ⇒ 上面的差值是**本窗增量**，不是「自上次重置以来的
            // 累计量」。旧实现只在重置分支推进 `lastCpu`，于是每窗比较的是跨窗累加值：
            // 事故速率 1.786 ms/s 只需 19 窗就能累过 1s 门槛 ⇒ 每 ~19 窗重置一次，
            // 10 分钟安全网（20 窗）永不触发（有效门槛被压到 1.754 ms/s，声明值 33.3 ms/s）。
            else if lines > lastLines || (cpu - lastCpu) > Defaults.ForegroundCpuProgressNanos then
              watch(lines, cpu, 0L) // 有进展（输出 / 本窗 CPU 超门槛）→ 重置判死窗口
            else if idleMs + ForegroundSampleInterval.toMillis >= ForegroundNoProgressTimeout.toMillis then
              // 2026-09-10 死日志修复：原为 `IO.delay(shellLogger.warn(...))`——warn 已返回
              // IO[Unit]，再包一层得到 IO[IO[Unit]]，内层日志永不执行（看护路径静默无日志）。
              shellLogger.warn(
                s"Foreground command idle for ${(idleMs + ForegroundSampleInterval.toMillis) / 1000}s " +
                  s"(no output, no CPU progress) — killing: ${command.take(80)}"
              ) *> stuckFlag.set(true) *> ProcessTree.killProcessTree(proc)
            // 无进展：仍要推进基线，否则下一窗比较的是累计值而非本窗增量（见上）。
            else watch(lines, cpu, idleMs + ForegroundSampleInterval.toMillis)
          }
        // lastCpu 基线自 0 起 = 进程出生时的 CPU（新进程出生时 CPU 为 0）——首窗
        // 比较的同样是「本窗增量」（出生 → 首次采样），与后续各窗口径一致。
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
          // 2026-09-10 死日志修复：去掉外层 IO.delay（内层 IO 永不执行）。杀树顺序不变。
          shellLogger.warn(s"Command timeout (${timeout.toSeconds}s) reached — killing process tree: ${command.take(80)}") *>
          ProcessTree.killProcessTree(proc)

      for
        _ <- storeProc
        _ <- registerActive
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
                    // Strictly-greater — same ps-quantum equality rationale as
                    // the foreground watch above (one 10ms quantum ≠ activity).
                    cpuActive = (cpu2 - cpu1) > CpuActiveThresholdNanos
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
                  "Command produced no output and no CPU progress for " +
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
      // 先注销再 killProcessTree（进程已退出时是幂等 no-op）——避免
      // killSessionProcesses 在 release 中途并发读到已收尾的进程。
      activeProcesses.update(_ - proc) *> ProcessTree.killProcessTree(proc)
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
                  // 2026-09-10 死日志修复：回调失败路径原为 IO.delay(warn(...)) —— 静默无日志。
                  NebflowLogger.forName("nebflow.shell").warn(s"Background job callback failed: ${e.getMessage}")
                )
            }
        case _ =>
          deferred.complete(result).void *>
            on_complete.fold(IO.unit) { cb =>
              IO.delay(cb(result))
                .flatten
                .handleErrorWith(e =>
                  // 2026-09-10 死日志修复：同上（结果回调失败路径）。
                  NebflowLogger.forName("nebflow.shell").warn(s"Background job callback failed: ${e.getMessage}")
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
   * Normal background job (executeBackground): the backgroundExecute fiber manages
   * the process lifecycle and completes the deferred when the process exits.
   * If the process crashes, backgroundExecute unblocks naturally (proc.waitFor()
   * returns), so we just log and do nothing.
   *
   * Idle timeout: if the process has been alive but produced no output for
   * BgIdleTimeoutSec, it is forcibly killed and the deferred is completed with
   * a TimeoutException. The backgroundExecute fiber's on_complete callback then
   * notifies the agent, which can retry or continue — this is the primary
   * recovery path for delegate/subtask agents that would otherwise be blocked
   * forever by a stuck background command.
   *
   * #391 机制 B（2026-08-25 用户裁定「输出零增长且 CPU 零消耗才杀」；
   * #26 2026-08-30 保留——只服务显式 run_in_background 后台任务）：
   * - B1 idle CPU 豁免：idle 判定期间 CPU 有消耗（≥ CpuActiveThresholdNanos /
   *   采样窗口）→ 不算 idle，不杀（避免误杀 CPU 忙的合法任务，如无输出编译）。
   * - B2 硬超时兜底：运行 > hardTimeoutMs（默认 30min）后进入停滞观察——输出
   *   零增长且 CPU 增量 < 阈值 连续 ≥ stuckWindowSec（默认 120s）→ killProcessTree
   *   + TimeoutException。总时长不重置：硬超时后必须持续证明活着（有进展才重置
   *   停滞计数）。持续吐日志/烧 CPU 的卡死任务（盲区 5）由此兜底。
   *
   * persistent=true（节点完成闸批）不进入本看护——executeBackground 直接跳过
   * 启动本 fiber（服务型豁免 B1/B2，见其参数注释）。
   */
  private def startJobHealthCheck(
    jobId: String,
    deferred: Deferred[IO, Either[Throwable, ProcessResult]],
    health: JobHealth,
    command: String = "",
    hardTimeoutMs: Long = Defaults.BashBackgroundHardTimeoutMs,
    stuckWindowSec: Int = Defaults.BashStuckWindowSec,
    checkIntervalSec: Int = Defaults.BgHealthCheckIntervalSec
  ): IO[Option[Fiber[IO, Throwable, Unit]]] =
    val intervalSec = checkIntervalSec
    val idleTimeoutMs = Defaults.BgIdleTimeoutSec.toLong * 1000L
    val stuckWindowMs = stuckWindowSec.toLong * 1000L

    /** 停滞（统一口径，对齐前台 no-progress ceiling）：输出零增长且 CPU 增量
      * < CpuActiveThresholdNanos（10ms/采样窗口）——双条件（#391 用户裁定）。 */
    def isStalled(lines: Int, cpu: Long, lastLines: Int, lastCpu: Long): Boolean =
      lines <= lastLines && (cpu - lastCpu) < CpuActiveThresholdNanos

    def killWith(message: String, loggerMsg: String): IO[Unit] =
      val logger = NebflowLogger.forName("nebflow.shell")
      logger.warn(loggerMsg) *>
        ProcessTree.killProcessTree(health.processRef.get()) *>
        deferred
          .complete(Left(new TimeoutException(message)))
          .attempt
          .void

    def loop(lastLines: Int, lastCpu: Long, stuckStartMs: Long): IO[Unit] =
      IO.sleep(intervalSec.seconds) *>
        deferred.tryGet.flatMap {
          case Some(_) => IO.unit // job already finished — stop checking
          case None =>
            val proc = health.processRef.get()
            if proc == null then loop(lastLines, lastCpu, stuckStartMs) // process not yet started
            else if !proc.isAlive() then
              // 进程意外死亡：backgroundExecute 经 proc.waitFor() 自然返回完成，
              // 此处无需处理（deferred 由 backgroundExecute 完成）。
              IO.unit
            else
              // Process still alive — B1/B2 checks
              val isSleepLike = SleepCommandRe.findFirstIn(command).isDefined
              val now = System.currentTimeMillis()
              val idleMs = now - health.lastActivityMs.get()
              val runningMs = now - health.startedAtMs.get()
              val lines = health.outputLineCount.get()
              val cpu = sampleProcessCpuTime(proc)
              val progress = !isStalled(lines, cpu, lastLines, lastCpu)
              val hardTimeoutHit = runningMs > hardTimeoutMs

              if !isSleepLike && idleMs > idleTimeoutMs && !progress then
                // B1：idle timeout（300s 无输出）——CPU 豁免已并入 progress 判断
                //（CPU 忙 → progress=true → 不进入此分支，不杀）。
                if health.deadNotified.compareAndSet(false, true) then
                  killWith(
                    s"Background command was idle (no output) for ${idleMs / 1000}s " +
                      s"and was automatically cancelled. The command may be stuck " +
                      s"or waiting for interactive input. Consider using a non-interactive " +
                      s"alternative or running it manually.",
                    s"Background job $jobId idle for ${idleMs / 1000}s (timeout ${Defaults.BgIdleTimeoutSec}s) — auto-cancelling"
                  )
                else IO.unit
              else if hardTimeoutHit && !progress then
                // B2：硬超时后停滞观察——零输出零 CPU 连续 ≥ stuckWindowSec 才杀
                if stuckStartMs == 0L then loop(lines, cpu, now)
                else if now - stuckStartMs >= stuckWindowMs then
                  if health.deadNotified.compareAndSet(false, true) then
                    killWith(
                      s"Background command ran for ${runningMs / 1000}s (hard timeout ${hardTimeoutMs / 1000}s) " +
                        s"with no output and no CPU activity for ${(now - stuckStartMs) / 1000}s — killed by stuck guard.",
                      s"Background job $jobId stalled ${(now - stuckStartMs) / 1000}s after ${runningMs / 1000}s (no output, no CPU) — killing"
                    )
                  else IO.unit
                else loop(lines, cpu, stuckStartMs)
              else
                // 有进展 → 重置停滞观察（总时长不重置，硬超时仍是允许运行总时间）
                loop(lines, cpu, 0L)
            end if
        }
    loop(0, 0L, 0L).start.map(Some(_))

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

  /**
   * Find Git Bash on Windows. Git for Windows is a declared dependency.
   * Common installation paths are checked explicitly to avoid picking up
   * WSL's bash.exe (C:\Windows\System32\bash.exe), which uses a different
   * filesystem layout.
   *
   * The bundled MinGit copy (msi payload <install>\app\git, version pinned
   * by packaging/build-msi.sh) wins over system installs: deterministic,
   * tested with the release, and zero external dependency.
   */
  lazy val resolvedBashPath: String =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    if !isWindows then "bash"
    else
      val progFiles = sys.env.getOrElse("ProgramFiles", "C:\\Program Files")
      val progFilesX86 = sys.env.getOrElse("ProgramFiles(x86)", "C:\\Program Files (x86)")
      val localAppData = sys.env.getOrElse("LOCALAPPDATA", "")
      val candidates =
        nebflow.core.InstallLayout.bundledBash.toList ++
          List(
            s"$progFiles\\Git\\bin\\bash.exe",
            s"$progFilesX86\\Git\\bin\\bash.exe"
          ) ++ (if localAppData.nonEmpty then List(s"$localAppData\\Programs\\Git\\bin\\bash.exe") else Nil)
      candidates.find(p => new File(p).exists()).getOrElse("bash")

  /** True when resolvedBashPath picked the bundled MinGit copy — that one is
    * invoked with -l + MSYSTEM=MINGW64 (see buildProcessBuilder). */
  lazy val isBundledBash: Boolean =
    nebflow.core.InstallLayout.bundledBash.contains(resolvedBashPath)

  // Best-effort cleanup of all sessions on JVM exit
  sys.addShutdownHook {
    import cats.effect.unsafe.implicits.global
    sessions.get.flatMap(s => s.values.toList.traverse_(_.kill())).unsafeRunAndForget()
  }

  def forSession(
    sessionId: String,
    initialDir: Option[String] = None,
    sandbox: Option[nebflow.core.sandbox.SandboxPolicy] = None
  ): IO[ShellSession] =
    createMutex.flatMap(_.lock.surround(doGetOrCreate(sessionId, initialDir, sandbox)))

  private  def doGetOrCreate(sessionId: String, initialDir: Option[String], sandbox: Option[nebflow.core.sandbox.SandboxPolicy]): IO[ShellSession] =
    def replace(old: ShellSession): IO[ShellSession] =
      old.cancelCleanupFiber() *> old.kill() *> sessions.update(_ - sessionId) *>
        ShellSession.create(sessionId, initialDir, sandbox).flatMap { newS =>
          sessions.update(_ + (sessionId -> newS)).as(newS)
        }
    sessions.get.flatMap { m =>
      m.get(sessionId) match
        case Some(s) =>
          s.isStale.flatMap {
            case true =>
              // Cancel the old cleanup fiber before killing the session to prevent fiber leak
              replace(s)
            case false =>
              // 2026-08-28 restart 恢复修复：滞留注册表的死会话（isAlive=false，
              // 如 restart/Stop 杀进程后未出 map 的历史形态）不返回——自愈重建，
              // 否则恢复后的 member 首个 Bash 调用 checkAlive 永远抛
              // "Session has been destroyed"，无法自愈。
              s.isDead.flatMap { dead =>
                if dead then replace(s) else s.touch.as(s)
              }
          }
        case None =>
          ShellSession.create(sessionId, initialDir, sandbox).flatMap { newS =>
            sessions.update(_ + (sessionId -> newS)).as(newS)
          }
    }

  def destroySession(sessionId: String): IO[Unit] =
    sessions.modify { m =>
      m.get(sessionId) match
        case Some(s) => (m - sessionId, s.kill())
        case None => (m, IO.unit)
    }.flatten

  /**
   * #391 机制 E：restart/Stop 联动——杀该 session 全部 OS 进程树（前台进程 +
   * 后台任务进程）并取消后台任务 fiber。session 已销毁/不存在时幂等 no-op。
   * AgentControl restart 在 cancelCurrentTurn（只取消 fiber）之后调用本方法，
   * 修复 IO.blocking 取消不中断线程导致的 OS 进程树残留（B9 残留根因）。
   * 不碰：其他 session 的进程、JVM 自身（ProcessTree 只操作注册的 ProcessHandle）。
   */
  def killSessionProcesses(sessionId: Option[String]): IO[Unit] =
    sessionId.fold(IO.unit) { sid =>
      // 2026-08-28 restart 恢复修复：kill 的同时把会话移出注册表（与
      // destroySession 对称）。原实现只杀进程+置 isAlive=false，死会话滞留
      // sessions map → member restart 恢复后 forSession 命中 Some(死) →
      // touch 原样返回 → 首个 Bash 调用 checkAlive 抛 "Session has been
      // destroyed" 且永不自愈（slideblocks Frontend restart 后无法跑命令、
      // 收尾卡死的根因）。移出后恢复路径走 get-or-create 的 None 分支，
      // 惰性重建新会话。
      sessions.modify { m =>
        m.get(sid) match
          case Some(s) => (m - sid, s.killActiveProcesses() *> s.kill())
          case None    => (m, IO.unit)
      }.flatten
    }

  private[tools] def create(
    sessionId: String,
    initialDir: Option[String] = None,
    sandbox: Option[nebflow.core.sandbox.SandboxPolicy] = None
  ): IO[ShellSession] =
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
      procsRef <- Ref.of[IO, Set[Process]](Set.empty)
      fiber <- startCleanupFiber(jobsRef)
      accessRef <- Clock[IO].realTime.map(_.toMillis).flatMap(Ref.of[IO, Long])
      aliveRef <- Ref.of[IO, Boolean](true)
      mutex <- Mutex[IO]
    yield new ShellSession(sessionId, dirRef, jobsRef, procsRef, fiber, accessRef, aliveRef, mutex, sandbox)

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
