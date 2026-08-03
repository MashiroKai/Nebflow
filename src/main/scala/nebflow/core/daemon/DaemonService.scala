package nebflow.core.daemon

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil

import java.io.{BufferedReader, InputStreamReader}
import scala.collection.mutable

import scala.concurrent.duration.*

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
    readFiber: Option[cats.effect.FiberIO[Unit]] = None
  )

  private val OutputCap = 200 // lines per daemon
  private val MaxOutputChars = 16000 // when returning to API

  private val entries: Ref[IO, Map[String, DaemonEntry]] = Ref.unsafe(Map.empty)

  // ── Lifecycle ──────────────────────────────────────────

  /** Start all daemons with autoStart=true. Called once on gateway boot. */
  def autoStart(store: DaemonStore): IO[Unit] =
    store.load().flatMap { configs =>
      val autoStartConfigs = configs.filter(_.autoStart)
      if autoStartConfigs.nonEmpty then
        logger.info(s"[daemon] Auto-starting ${autoStartConfigs.length} daemon(s): ${autoStartConfigs.map(_.name).mkString(", ")}")
          *> autoStartConfigs.traverse_(cfg => start(cfg).handleErrorWith(e =>
            logger.error(s"[daemon] Failed to auto-start '${cfg.name}': ${e.getMessage}"))
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
    entries.get.map(_.get(id).map(entry => toState(id, entry)))

  /** Get states for a list of daemon configs (merges config + runtime status). */
  def getStates(configs: List[DaemonConfig]): IO[List[DaemonState]] =
    entries.get.map { map =>
      configs.map { cfg =>
        val entry = map.getOrElse(cfg.id, DaemonEntry(cfg))
        toState(cfg.id, entry)
      }
    }

  // ── Internal ───────────────────────────────────────────

  private def doStart(config: DaemonConfig): IO[DaemonState] =
    IO.blocking {
      val workDir = config.cwd match
        case Some(dir) => java.io.File(dir)
        case None => java.io.File(System.getProperty("user.dir", "."))

      if config.command.isEmpty then
        throw new IllegalArgumentException(s"Daemon '${config.id}' has empty command")

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
        outputBuffer = mutable.ArrayDeque.empty[String]
      )
      // Start background reader fiber
      val readIO = readOutput(config.id, process)

      // We need to store the entry first, then start the fiber, then update with fiber ref
      entries.update(_ + (config.id -> entry)) *>
        readIO.start.flatMap { fiber =>
          entries.update(_ + (config.id ->
            entry.copy(readFiber = Some(fiber))
          )) *>
            logger.info(s"[daemon] Started '${config.name}' (pid=$pid)") *>
            // Monitor fiber: detect process exit
            monitorExit(config.id, process, fiber).start.void *>
            entries.get.map(_.get(config.id).map(e => toState(config.id, e)).getOrElse(toState(config.id, entry)))
        }
    }

  private def doStop(id: String, entry: DaemonEntry): IO[Option[DaemonState]] =
    IO.blocking {
      entry.process.foreach { p =>
        try
          p.destroy() // SIGTERM
          if !p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) then
            p.destroyForcibly() // SIGKILL
        catch case _: Exception => ()
      }
    } *> entry.readFiber.traverse_(_.cancel) *>
      entries.update { map =>
        map.updated(id, entry.copy(
          process = None,
          status = DaemonStatus.Stopped,
          readFiber = None
        ))
      } *>
      logger.info(s"[daemon] Stopped '${entry.config.name}'") *>
      entries.get.map(_.get(id).map(e => toState(id, e)))

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
      finally try reader.close() catch case _: Exception => ()
    }

  /** Background fiber: wait for process exit, update status. */
  private def monitorExit(id: String, process: Process, readerFiber: cats.effect.FiberIO[Unit]): IO[Unit] =
    IO.blocking {
      try
        val code = process.waitFor()
        Some(code)
      catch
        case _: InterruptedException => None
    }.flatMap {
      case None => IO.unit // cancelled
      case Some(code) =>
        // Cancel reader fiber
        readerFiber.cancel.handleErrorWith(_ => IO.unit) *>
          entries.update { map =>
            map.get(id) match
              case Some(entry) =>
                val newStatus =
                  if entry.status == DaemonStatus.Stopped then DaemonStatus.Stopped
                  else DaemonStatus.Crashed
                map.updated(id, entry.copy(
                  process = None,
                  status = newStatus,
                  exitCode = Some(code),
                  readFiber = None
                ))
              case None => map
          } *>
          entries.get.flatMap { map =>
            val entry = map.get(id)
            val name = entry.map(_.config.name).getOrElse(id)
            val statusLabel = entry.map(_.status).getOrElse(DaemonStatus.Crashed)
            logger.info(s"[daemon] '$name' exited (code=$code, status=$statusLabel)")
          } *>
          // Auto-restart if configured and exit wasn't from explicit stop
          entries.get.flatMap { map =>
            map.get(id) match
              case Some(entry) if entry.config.restartOnExit && entry.status == DaemonStatus.Crashed =>
                logger.info(s"[daemon] Auto-restarting '${entry.config.name}'...") *>
                  IO.sleep(2.seconds) *> doStart(entry.config).void.handleErrorWith(e =>
                    logger.error(s"[daemon] Auto-restart failed for '${entry.config.name}': ${e.getMessage}"))
              case _ => IO.unit
          }
    }

  private def toState(id: String, entry: DaemonEntry): DaemonState =
    DaemonState(
      id = id,
      name = entry.config.name,
      status = entry.status,
      pid = entry.process.flatMap(getPid),
      startedAt = entry.startedAt,
      exitCode = entry.exitCode,
      recentOutput = entry.outputBuffer.mkString("\n").take(MaxOutputChars)
    )

  private def getPid(process: Process): Option[Long] =
    try Some(process.pid())
    catch case _: Exception => None

end DaemonService
