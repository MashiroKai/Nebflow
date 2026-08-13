package nebflow.core.daemon

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.core.util.ProcessTree

import java.io.{BufferedReader, InputStreamReader}

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.StreamConverters.*

/**
 * Manages lifecycle of external long-running processes (development servers, etc.)
 *
 * Architecture: pure cats-effect IO — no Actor needed.
 * Process state is tracked in a Ref[Map[String, DaemonEntry]].
 * Each running process gets a background fiber reading stdout/stderr into a ring buffer.
 */
final class DaemonService(dispatcher: Dispatcher[IO]):

  private val logger = NebflowLogger.forName("nebflow.daemon")

  /** In-memory state for each daemon process. */
  private case class DaemonEntry(
    config: DaemonConfig,
    process: Option[Process] = None,
    status: DaemonStatus = DaemonStatus.Stopped,
    startedAt: Option[Long] = None,
    exitCode: Option[Int] = None,
    outputBuffer: mutable.ArrayDeque[String] = mutable.ArrayDeque.empty[String],
    readFiber: Option[cats.effect.FiberIO[Unit]] = None,
    /**
     * True while an explicit stop (user API / stopAll / JVM shutdown hook) is in
     *        flight. monitorExit treats an exit with this set as intentional: status
     *        becomes Stopped (never Crashed) and no auto-restart fires — this is what
     *        keeps the JVM shutdown path (stopAll) from restarting daemons.
     */
    stopRequested: Boolean = false,
    /** Consecutive auto-restarts already performed (0 = fresh/manual start). */
    restartCount: Int = 0
  )

  private val OutputCap = 200 // lines per daemon
  private val MaxOutputChars = 16000 // when returning to API
  private val MaxBackoffDelay = 60.seconds

  /**
   * Consecutive port-probe failures (at healthCheckSec intervals) before the
   *      active health check declares a live-but-port-closed process crashed.
   */
  private val HealthCheckFailThreshold = 4

  private val entries: Ref[IO, Map[String, DaemonEntry]] = Ref.unsafe(Map.empty)

  // ── Lifecycle ──────────────────────────────────────────

  /** Start all daemons with autoStart=true. Called once on gateway boot. */
  def autoStart(store: DaemonStore): IO[Unit] =
    store.load().flatMap { configs =>
      val autoStartConfigs = configs.filter(_.autoStart)
      if autoStartConfigs.nonEmpty then
        logger.info(
          s"[daemon] Auto-starting ${autoStartConfigs.length} daemon(s): ${autoStartConfigs.map(_.name).mkString(", ")}"
        )
          *> autoStartConfigs.traverse_(cfg =>
            start(cfg)
              .handleErrorWith(e => logger.error(s"[daemon] Failed to auto-start '${cfg.name}': ${e.getMessage}"))
          )
      else IO.unit
    }

  /** Stop all running daemons. Called on gateway shutdown. */
  def stopAll(): IO[Unit] =
    entries.get.flatMap { map =>
      val running = map.values.filter(_.status == DaemonStatus.Running).toList
      if running.nonEmpty then
        logger.info(s"[daemon] Stopping ${running.length} daemon(s) on shutdown...")
          *> running.traverse_(entry => stop(entry.config.id).handleErrorWith(_ => IO.unit))
      else IO.unit
    }

  // ── Control ────────────────────────────────────────────

  /** Start a daemon by config. Returns the updated DaemonState. */
  def start(config: DaemonConfig): IO[DaemonState] =
    entries.get.map(_.get(config.id)).flatMap {
      case Some(entry) if entry.status == DaemonStatus.Running =>
        IO.pure(toState(config.id, entry))
      case _ =>
        doStart(config)
    }

  /** Start a daemon by id (loads config from store). */
  def startById(store: DaemonStore, id: String): IO[Option[DaemonState]] =
    store.load().map(_.find(_.id == id)).flatMap {
      case Some(cfg) => start(cfg).map(Some(_))
      case None => IO.pure(None)
    }

  /** Stop a running daemon by id. */
  def stop(id: String): IO[Option[DaemonState]] =
    entries.get.map(_.get(id)).flatMap {
      case Some(entry) if entry.status == DaemonStatus.Running =>
        doStop(id, entry)
      case Some(entry) =>
        IO.pure(Some(toState(id, entry)))
      case None => IO.pure(None)
    }

  /** Restart a daemon. */
  def restart(store: DaemonStore, id: String): IO[Option[DaemonState]] =
    stop(id).flatMap {
      case None => IO.pure(None)
      case Some(_) => startById(store, id)
    }

  // ── Queries ────────────────────────────────────────────

  /** Get the current state of a single daemon. */
  def getState(id: String): IO[Option[DaemonState]] =
    entries.get.flatMap(_.get(id) match
      case Some(entry) => toStateIO(id, entry).map(Some(_))
      case None => IO.pure(None))

  /** Get states for a list of daemon configs (merges config + runtime status + port probe). */
  def getStates(configs: List[DaemonConfig]): IO[List[DaemonState]] =
    entries.get.flatMap { map =>
      configs.traverse { cfg =>
        val entry = map.getOrElse(cfg.id, DaemonEntry(cfg))
        toStateIO(cfg.id, entry)
      }
    }

  // ── Internal ───────────────────────────────────────────

  private def doStart(config: DaemonConfig): IO[DaemonState] = doStart(config, restartCount = 0)

  /**
   * Start a daemon process. `restartCount` is carried over from the auto-restart
   *      chain so the crash-loop counter survives process replacements.
   */
  private def doStart(config: DaemonConfig, restartCount: Int): IO[DaemonState] =
    IO.blocking {
      val workDir = config.cwd match
        case Some(dir) => java.io.File(dir)
        case None => java.io.File(System.getProperty("user.dir", "."))

      if config.command.isEmpty then throw new IllegalArgumentException(s"Daemon '${config.id}' has empty command")

      val pb = new ProcessBuilder(config.command*)
      pb.directory(workDir)
      pb.redirectErrorStream(true)
      // Set environment
      val env = pb.environment()
      config.env.foreach { (k, v) => env.put(k, v) }

      val process = pb.start()
      process
    }.flatMap { process =>
      val pid = getPid(process)
      val now = System.currentTimeMillis()
      val entry = DaemonEntry(
        config = config,
        process = Some(process),
        status = DaemonStatus.Running,
        startedAt = Some(now),
        outputBuffer = mutable.ArrayDeque.empty[String],
        restartCount = restartCount
      )
      // Start background reader fiber
      val readIO = readOutput(config.id, process)

      // We need to store the entry first, then start the fiber, then update with fiber ref
      entries.update(_ + (config.id -> entry)) *>
        readIO.start.flatMap { fiber =>
          entries.update(
            _ + (config.id ->
              entry.copy(readFiber = Some(fiber)))
          ) *>
            logger.info(s"[daemon] Started '${config.name}' (pid=$pid)") *>
            // Monitor fiber: detect process exit
            monitorExit(config.id, process, fiber).start.void *>
            // Active health-check fiber: detect a live-but-unresponsive process
            startHealthMonitor(config, process) *>
            entries.get.flatMap { map =>
              map.get(config.id) match
                case Some(e) => toStateIO(config.id, e)
                case None => toStateIO(config.id, entry)
            }
        }
    }

  private def doStop(id: String, entry: DaemonEntry): IO[Option[DaemonState]] =
    // Mark the stop as intentional BEFORE killing: monitorExit must see the
    // imminent exit as Stopped (never Crashed) and must not auto-restart. This
    // also covers the JVM shutdown path (stopAll -> doStop) — daemons must die
    // with Nebflow, not be resurrected by the shutdown hook's own kill.
    entries.update(map => map.updated(id, entry.copy(stopRequested = true))) *>
      ProcessTree.killProcessTree(
        entry.process.getOrElse(throw new IllegalStateException(s"Daemon '$id' has no process"))
      ) *>
      entry.readFiber.traverse_(_.cancel) *>
      entries.update { map =>
        map.updated(
          id,
          entry.copy(
            process = None,
            status = DaemonStatus.Stopped,
            readFiber = None
          )
        )
      } *>
      logger.info(s"[daemon] Stopped '${entry.config.name}'") *>
      entries.get.flatMap { map =>
        map.get(id) match
          case Some(e) => toStateIO(id, e).map(Some(_))
          case None => IO.pure(Some(toState(id, entry)))
      }

  /** Background fiber: read stdout+stderr lines into ring buffer. */
  private def readOutput(id: String, process: Process): IO[Unit] =
    IO.blocking {
      val reader = BufferedReader(InputStreamReader(process.getInputStream, "UTF-8"))
      try
        var line: String = null
        while { line = reader.readLine(); line != null } do
          entries.update { map =>
            map.get(id) match
              case Some(entry) =>
                val buf = entry.outputBuffer
                buf.addOne(line)
                if buf.length > OutputCap then buf.remove(0) // keep last N lines
                map.updated(id, entry.copy(outputBuffer = buf))
              case None => map
          }
      catch case _: Exception => ()
      finally
        try reader.close()
        catch case _: Exception => ()
      end try
    }

  /** Background fiber: wait for process exit, update status. */
  private def monitorExit(id: String, process: Process, readerFiber: cats.effect.FiberIO[Unit]): IO[Unit] =
    IO.blocking {
      try
        val code = process.waitFor()
        Some(code)
      catch case _: InterruptedException => None
    }.flatMap {
      case None => IO.unit // cancelled
      case Some(code) =>
        // Cancel reader fiber
        readerFiber.cancel.handleErrorWith(_ => IO.unit) *>
          entries.update { map =>
            map.get(id) match
              case Some(entry) =>
                val intentional = entry.stopRequested
                val newStatus =
                  if intentional then DaemonStatus.Stopped
                  else DaemonStatus.Crashed
                map.updated(
                  id,
                  entry.copy(
                    process = None,
                    status = newStatus,
                    exitCode = Some(code),
                    readFiber = None
                  )
                )
              case None => map
          } *>
          entries.get.flatMap { map =>
            val entry = map.get(id)
            val name = entry.map(_.config.name).getOrElse(id)
            val statusLabel = entry.map(_.status).getOrElse(DaemonStatus.Crashed)
            logger.info(s"[daemon] '$name' exited (code=$code, status=$statusLabel)")
          } *>
          maybeAutoRestart(id)
    }

  /**
   * Auto-restart policy for a crashed daemon.
   *
   * Triggers when the daemon is autoStart=true OR restartOnExit=true — autoStart
   * daemons are persistent environment services (dev servers), so a crash must
   * bring them back. Never fires for an intentional stop (stopRequested), which
   * covers the JVM shutdown path: stopAll sets stopRequested before killing, so
   * daemons are not resurrected while Nebflow is going down.
   *
   * Backoff: exponential (restartBackoffSec doubled per attempt, capped at 60s).
   * Crash-loop protection: give up after restartMaxAttempts consecutive crashes.
   * The counter resets when a run outlives restartStableWindowSec, so a daemon
   * that crashes once a day is never falsely exhausted.
   */
  private def maybeAutoRestart(id: String): IO[Unit] =
    entries.get.flatMap { map =>
      map.get(id) match
        case None => IO.unit
        case Some(entry) =>
          val wantsRestart = entry.config.autoStart || entry.config.restartOnExit
          if !wantsRestart || entry.status != DaemonStatus.Crashed || entry.stopRequested then IO.unit
          else
            val now = System.currentTimeMillis()
            val stableWindowMs = math.max(1, entry.config.restartStableWindowSec).toLong * 1000L
            val ranStable = entry.startedAt.exists(t => now - t > stableWindowMs)
            val consecutive = if ranStable then 0 else entry.restartCount
            val attempt = consecutive + 1
            if attempt > entry.config.restartMaxAttempts then
              logger.error(
                s"[daemon] '${entry.config.name}' crashed ${entry.config.restartMaxAttempts} time(s) in a row; " +
                  "auto-restart exhausted (crash loop). Manual restart required."
              )
            else
              val delay = backoffDelay(attempt, entry.config.restartBackoffSec)
              logger.info(
                s"[daemon] Auto-restarting '${entry.config.name}' (attempt $attempt/${entry.config.restartMaxAttempts}) in ${delay.toSeconds}s"
              )
              // Re-check before spawning: the user may have stopped or manually
              // restarted the daemon while we were backing off.
              IO.sleep(delay) *> entries.get.flatMap(_.get(id) match
                case Some(e) if e.status == DaemonStatus.Crashed && !e.stopRequested =>
                  doStart(entry.config, attempt).void.handleErrorWith(e =>
                    logger.error(s"[daemon] Auto-restart failed for '${entry.config.name}': ${e.getMessage}")
                  )
                case _ => IO.unit)
            end if
          end if
    }

  /** Exponential backoff: base * 2^(attempt-1), capped at MaxBackoffDelay. */
  private def backoffDelay(attempt: Int, baseSec: Int): FiniteDuration =
    val base = math.max(1, baseSec)
    val exp = math.min(attempt - 1, 5) // cap exponential growth at 32x base
    (base.toLong * math.pow(2, exp).toLong).seconds.min(MaxBackoffDelay)

  /** Start the active health-check fiber for a daemon with a configured port. */
  private def startHealthMonitor(config: DaemonConfig, process: Process): IO[Unit] =
    config.port match
      case Some(_) if config.healthCheckSec > 0 =>
        monitorHealth(config, process)
          .handleErrorWith(e => logger.error(s"[daemon] Health check for '${config.name}' stopped: ${e.getMessage}"))
          .start
          .void
      case _ => IO.unit

  /**
   * Active health check: periodically TCP-probes the daemon's configured port.
   * Catches the zombie case — parent process still alive but the actual server
   * child is dead (port closed). After HealthCheckFailThreshold consecutive
   * failures the process tree is killed, so the ordinary monitorExit -> Crashed
   * -> auto-restart pipeline takes over.
   *
   * Self-terminates when the entry no longer references this process or the
   * daemon is being stopped (stopRequested) — it never declares a crash for an
   * intentional stop, incl. JVM shutdown.
   */
  private def monitorHealth(config: DaemonConfig, process: Process): IO[Unit] =
    val id = config.id
    val intervalSec = math.max(1, config.healthCheckSec)
    val port = config.port.get

    def loop(failures: Int): IO[Unit] =
      IO.sleep(intervalSec.seconds) *>
        entries.get.flatMap { map =>
          map.get(id) match
            case Some(entry)
                if entry.status == DaemonStatus.Running &&
                  entry.process.exists(_ eq process) && !entry.stopRequested =>
              IO.blocking(probePort(port)).flatMap { open =>
                if open then loop(0)
                else
                  val next = failures + 1
                  if next >= HealthCheckFailThreshold then
                    logger.warn(
                      s"[daemon] '${config.name}' port $port closed for $next consecutive probes while process alive; " +
                        "treating as crashed — killing process tree"
                    ) *> ProcessTree.killProcessTree(process) *> IO.unit // monitorExit takes over from here
                  else loop(next)
              }
            case _ => IO.unit // daemon stopped / replaced / stop in flight — stop probing
        }

    loop(0)

  end monitorHealth

  private def toState(id: String, entry: DaemonEntry): DaemonState =
    DaemonState(
      id = id,
      name = entry.config.name,
      status = entry.status,
      pid = entry.process.flatMap(getPid),
      startedAt = entry.startedAt,
      exitCode = entry.exitCode,
      recentOutput = entry.outputBuffer.mkString("\n").take(MaxOutputChars),
      command = entry.config.command,
      port = entry.config.port
    )

  /** toState + TCP reachability probe on the configured port (if any). */
  private def toStateIO(id: String, entry: DaemonEntry): IO[DaemonState] =
    entry.config.port match
      case Some(port) =>
        IO.blocking(probePort(port)).map(open => toState(id, entry).copy(portOpen = Some(open)))
      case None => IO.pure(toState(id, entry))

  /**
   * Lightweight TCP connect probe — decoupled from process status.
   * An externally-started server (not managed by DaemonService) is still
   * reported reachable; a live process with a dead port is not clickable.
   *
   * Tries IPv4 first, then IPv6 — Node/Astro/Vite dev servers often bind
   * only to `[::1]`, which a pure `127.0.0.1` probe would miss.
   */
  private def probePort(port: Int): Boolean =
    def tryConnect(host: String): Boolean =
      val socket = new java.net.Socket()
      try
        socket.connect(new java.net.InetSocketAddress(host, port), 500)
        true
      catch case _: Exception => false
      finally
        try socket.close()
        catch case _: Exception => ()
    tryConnect("127.0.0.1") || tryConnect("::1")

  private def getPid(process: Process): Option[Long] =
    try Some(process.pid())
    catch case _: Exception => None

end DaemonService
