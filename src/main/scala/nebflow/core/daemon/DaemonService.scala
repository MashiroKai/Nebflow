package nebflow.core.daemon

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.core.util.ProcessTree

import java.io.{BufferedReader, InputStreamReader}
import java.util.concurrent.locks.ReentrantLock

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
final class DaemonService(
  dispatcher: Dispatcher[IO],
  /** Marker file recording spawned pids — used to reclaim stale processes of
   *  an abnormally-killed previous instance at boot (see reclaimStaleDaemons). */
  markerFile: os.Path = PathUtil.dataRoot / "daemon-pids.json"
):

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

  /** Guards the marker file's read-modify-write (parallel daemon starts). */
  private val markerLock = new ReentrantLock()

  // ── Lifecycle ──────────────────────────────────────────

  /**
   * Start all daemons with autoStart=true. Called once on gateway boot.
   *
   * Runs stale-process reclaim FIRST: a previous instance killed outside its
   * shutdown hook (SIGKILL, restart-script KILL escalation) leaves its spawned
   * daemons orphaned and holding the configured ports forever — the boot would
   * then see every port "open" and silently refuse to start (Stopped +
   * portOpen=true), i.e. the "Dev Server 无法打开" symptom. Reclaim kills only
   * processes we recorded ourselves (marker file + pid start-time match), so a
   * server the user started manually is never touched.
   */
  def autoStart(store: DaemonStore): IO[Unit] =
    reclaimStaleDaemons() *>
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
        // Not running. Mark stopRequested so any takeover fiber probing this
        // entry's port stops waiting (an explicit stop = "leave it alone").
        entries.update(map => map.updated(id, entry.copy(stopRequested = true))) *>
          IO.pure(Some(toState(id, entry)))
      case None => IO.pure(None)
    }

  /** Restart a daemon. */
  def restart(store: DaemonStore, id: String): IO[Option[DaemonState]] =
    stop(id).flatMap {
      case None => IO.pure(None)
      case Some(_) => startById(store, id)
    }

  /**
   * Stop (if running) and forget a daemon — the config-removal path (DELETE
   * /api/daemons/:id). Dropping the entry also terminates the low-rate
   * takeover fiber: without this, removing a daemon whose port was held by an
   * external process left the fiber probing forever and RESURRECTING the
   * deleted daemon once the port freed.
   */
  def remove(id: String): IO[Unit] =
    entries.get.map(_.get(id)).flatMap {
      case Some(entry) if entry.status == DaemonStatus.Running =>
        doStop(id, entry) *> dropEntry(id, "removed")
      case Some(entry) => dropEntry(id, "removed")
      case None => IO.unit
    }

  /**
   * Sync in-memory entries with the store's configs (the source of truth).
   * Call after any config mutation (GET /daemons, create) so a daemons.json
   * hot-edit cannot leave ghosts:
   *   - entry whose id is no longer in the store (config removed) → stop the
   *     process and drop the entry;
   *   - entry whose config changed under it (same id, different command/port/
   *     env/...) → the running process belongs to the OLD config → stop and
   *     drop; the new config starts fresh on demand (autoStart at boot).
   * Running entries whose config is unchanged are left untouched.
   */
  def reconcile(configs: List[DaemonConfig]): IO[Unit] =
    val cfgById = configs.map(c => c.id -> c).toMap
    entries.get.flatMap { map =>
      map.toList.traverse_ { case (id, entry) =>
        if !cfgById.contains(id) then dropEntry(id, "config removed")
        else if cfgById(id) != entry.config then dropEntry(id, "config changed")
        else IO.unit
      }
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
   *
   * Port pre-flight: when the configured port is already serving — a stale
   *      process from a previous Nebflow instance that was killed outside the
   *      JVM shutdown hook window, or a server the user started manually — we
   *      must NOT spawn a duplicate: it dies instantly with "address already in
   *      use" (exit 1), burns the auto-restart budget, and the crash-loop
   *      protection eventually gives up, leaving the daemon dead forever after
   *      the next restart. Instead the daemon is marked Stopped with portOpen
   *      = true (frontend open button works; stop is a no-op) and the low-rate
   *      retry loop (see maybeAutoRestart) picks it up once the port frees.
   */
  private def doStart(config: DaemonConfig, restartCount: Int): IO[DaemonState] =
    config.port match
      case Some(port) if probePort(port) =>
        val entry = DaemonEntry(config = config, status = DaemonStatus.Stopped)
        logger.info(
          s"[daemon] '${config.name}' port $port already open — skipping start (externally running); will take over when port frees"
        ) *> entries.update(_ + (config.id -> entry)) *>
          // Keep probing at low rate — when the externally-held port frees
          // (stale previous-instance process exits, user stops the manual
          // server) we take over so the daemon self-heals without a restart.
          lowRateTakeover(config.id).start.void *>
          toStateIO(config.id, entry)
      case _ => doStartInternal(config, restartCount)

  /**
   * Low-rate self-heal loop for a daemon whose port was found occupied at
   *      start time (external process). Probes every MaxBackoffDelay (60s);
   *      when the port frees, starts the daemon. Runs until the entry leaves
   *      the Stopped-no-stopRequested state (user stopped it, or we started it).
   */
  private def lowRateTakeover(id: String): IO[Unit] =
    def loop: IO[Unit] =
      IO.sleep(MaxBackoffDelay) *> entries.get.flatMap(_.get(id) match
        case Some(e) if e.status == DaemonStatus.Stopped && !e.stopRequested =>
          e.config.port match
            case Some(p) if !probePort(p) =>
              // Re-check before starting: another takeover fiber (or the user)
              // may have won the race and started the daemon already.
              entries.get.map(_.get(id)).flatMap {
                case Some(e2) if e2.status == DaemonStatus.Stopped && !e2.stopRequested =>
                  logger.info(s"[daemon] '${e2.config.name}' port $p freed — taking over") *>
                    doStartInternal(e2.config, 0).void
                      .handleErrorWith(err =>
                        logger.error(s"[daemon] Takeover start failed for '${e2.config.name}': ${err.getMessage}") *> loop
                      )
                case _ => IO.unit // someone else started it — done
              }
            case Some(_) => loop // port still held — keep probing
            case None => IO.unit // no port configured — nothing to wait for
        case _ => IO.unit // user stopped it, entry removed, or we started it — done
      )
    loop
  private def doStartInternal(config: DaemonConfig, restartCount: Int): IO[DaemonState] =
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
      val markerIO = pid.fold(IO.unit)(p =>
        // Persist the spawn marker (best-effort): lets the NEXT instance
        // reclaim this process if we die without the shutdown hook (SIGKILL).
        mutateMarkers(_ + (config.id -> DaemonPidMarker(config.id, p, config.name, config.port, now)))
      )
      entries.update(_ + (config.id -> entry)) *>
        markerIO *>
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
      // Forget the spawn marker — a cleanly-stopped daemon must not be
      // reclaimed (it is no longer a stale process of a previous instance).
      mutateMarkers(_ - id) *>
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

  /**
   * Stop (if running) and drop an entry from the map — reconcile / remove
   * path. Dropping the entry terminates takeover fibers (they exit when the
   * id is gone from entries). `reason` is logged for observability.
   */
  private def dropEntry(id: String, reason: String): IO[Unit] =
    entries.get.map(_.get(id)).flatMap {
      case Some(entry) if entry.status == DaemonStatus.Running =>
        doStop(id, entry).void *> dropEntry(id, reason)
      case Some(entry) =>
        mutateMarkers(_ - id) *> entries.update(_ - id) *>
          logger.info(s"[daemon] Reconciled '${entry.config.name}' ($reason) — no ghost process")
      case None => IO.unit
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
              // Crash budget exhausted: do NOT give up permanently. A daemon
              // that crashed during the Nebflow-restart window (port still held
              // by the previous instance's process) must self-heal once the
              // port frees — giving up left it dead until a manual restart
              // (2026-08-18: all three daemons dead after a restart raced the
              // old instance's shutdown). Degrade to a low-rate retry: check
              // every MaxBackoffDelay whether the environment recovered (port
              // free) and start then. Keeps crash-loop protection from
              // hammering the port, but never leaves the daemon abandoned.
              logger.warn(
                s"[daemon] '${entry.config.name}' crashed ${entry.config.restartMaxAttempts} time(s) in a row; " +
                  "auto-restart budget exhausted — retrying at low rate for self-heal"
              )
              IO.sleep(MaxBackoffDelay) *> entries.get.flatMap(_.get(id) match
                case Some(e) if e.status == DaemonStatus.Crashed && !e.stopRequested =>
                  e.config.port match
                    case Some(p) if probePort(p) =>
                      // Port still held (e.g. stale instance process) — keep the
                      // low-rate probe alive so we self-heal when it frees.
                      lowRateTakeover(id).start.void
                    case _ =>
                      doStart(e.config, attempt).void.handleErrorWith(err =>
                        logger.error(s"[daemon] Low-rate retry failed for '${e.config.name}': ${err.getMessage}")
                      )
                case _ => IO.unit)
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
   *
   * Uses SocketChannel (not `new Socket()`): on macOS + JDK 23, an unbound
   * java.net.Socket connecting to `::1:<port>` returns success WITHOUT a
   * completed handshake (phantom connect — the write/read then times out).
   * That made probePort report EVERY port "open": doStart refused to spawn,
   * and the takeover loop's `!probePort` condition never became true — the
   * daemon stayed Stopped+portOpen forever = "Dev Server 无法打开"
   * (2026-08-24, root-caused via instrumented probe + standalone repro).
   * SocketChannel performs a real handshake: refused stays refused.
   */
  private def probePort(port: Int): Boolean =
    def tryConnect(host: String): Boolean =
      val channel = java.nio.channels.SocketChannel.open()
      try
        channel.socket().connect(new java.net.InetSocketAddress(host, port), 500)
        true
      catch case _: Exception => false
      finally
        try channel.close()
        catch case _: Exception => ()
    tryConnect("127.0.0.1") || tryConnect("::1")
    tryConnect("127.0.0.1") || tryConnect("::1")

  private def getPid(process: Process): Option[Long] =
    try Some(process.pid())
    catch case _: Exception => None

  // ── Stale-process reclaim (marker file) ─────────────────

  /**
   * Kill daemon processes orphaned by an abnormally-killed previous instance
   * and clear the marker file. Only processes WE recorded (marker pid + start
   * time match) are touched — a manually-started server or a recycled pid
   * pointing at an unrelated process is never killed.
   */
  private def reclaimStaleDaemons(): IO[Unit] =
    loadMarkers().flatMap { markers =>
      if markers.isEmpty then IO.unit
      else
        markers.values.toList.flatMap { m =>
          Option(ProcessHandle.of(m.pid).orElse(null)).map(ph => m -> ph)
        } match
          case Nil => clearMarkers() // all markers dead already
          case alive =>
            val ours = alive.filter { case (m, ph) =>
              val startMs =
                try
                  val opt = ph.info.startInstant()
                  if opt.isPresent then opt.get().toEpochMilli else 0L
                catch case _: Exception => 0L
              // Guard against pid reuse: a recycled pid has a DIFFERENT start
              // time than the one we recorded at spawn → not our process.
              startMs != 0L && math.abs(startMs - m.startedAt) <= 2000
            }
            ours.traverse_ { case (m, ph) =>
              logger.info(
                s"[daemon] Reclaiming stale process of previous instance: '${m.name}' (pid=${m.pid}${m.port.map(p => s", port $p").getOrElse("")})"
              ) *>
                ProcessTree.killProcessTree(ph).handleErrorWith(e =>
                  logger.warn(s"[daemon] Reclaim kill failed for '${m.name}': ${e.getMessage}")
                )
            } *> clearMarkers()
    }

  private def loadMarkers(): IO[Map[String, DaemonPidMarker]] =
    IO.blocking {
      if !os.exists(markerFile) then Map.empty
      else
        decode[DaemonPidMarkerFile](os.read(markerFile)) match
          case Right(f) => f.markers.map(m => m.id -> m).toMap
          case Left(_) => Map.empty
    }

  private def clearMarkers(): IO[Unit] =
    IO.blocking {
      if os.exists(markerFile) then
        try os.remove(markerFile)
        catch case _: Exception => ()
    }

  /**
   * Read-modify-write the marker file under a lock (parallel daemon starts
   * must not lose each other's markers). AtomicJson temp+rename keeps the
   * file crash-safe. Best-effort: a marker write failure never fails the
   * daemon start — the worst case is a missing reclaim at the next boot.
   */
  private def mutateMarkers(fn: Map[String, DaemonPidMarker] => Map[String, DaemonPidMarker]): IO[Unit] =
    IO.blocking {
      markerLock.lock()
      try
        val current =
          if !os.exists(markerFile) then Map.empty[String, DaemonPidMarker]
          else
            decode[DaemonPidMarkerFile](os.read(markerFile)) match
              case Right(f) => f.markers.map(m => m.id -> m).toMap
              case Left(_) => Map.empty
        AtomicJson.writeSync(markerFile, DaemonPidMarkerFile(fn(current).values.toList).asJson.noSpaces)
      catch
        case e: Throwable =>
          logger.warnSync(s"[daemon] marker file update failed: ${e.getMessage}")
      finally markerLock.unlock()
    }

end DaemonService
