package nebflow

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import nebflow.cli.*
import nebflow.core.PathUtil
import nebflow.gateway.GatewayConfig
import nebflow.llm.Config

import scala.util.Try

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    // Phase 1: Parse --home and --port global flags before any dispatching
    val (homeOpt, portOpt, remaining) = parseGlobalFlags(args)

    homeOpt.foreach { h =>
      PathUtil.setDataRoot(os.Path(expandHome(h), os.pwd))
    }
    portOpt.foreach { p =>
      GatewayConfig.setPort(p)
    }

    // Phase 2: Command dispatching
    val startModes = Set("start", "-s", "--server")
    val stopModes = Set("stop")
    // Filter out display flags for command matching
    val cmdArgs = remaining.filterNot(a => a == "--json" || a == "--quiet")

    cmdArgs match
      case Nil =>
        // No args → start Gateway (backward compatible)
        startGateway()
      case head :: _ if stopModes.contains(head) =>
        // stop command
        ProcessManager.stop().as(ExitCode.Success)
      case head :: rest if startModes.contains(head) =>
        // start command — launch Gateway
        startGateway()
      case "help" :: _ =>
        CliRouter.run(Nil)
      case _ =>
        // All other commands go through CLI router
        CliRouter.run(remaining)

    end match

  end run

  /** Parse --home, --port, --no-browser from args, returning (homeOpt, portOpt, remainingArgs). */
  private def parseGlobalFlags(args: List[String]): (Option[String], Option[Int], List[String]) =
    var home: Option[String] = None
    var port: Option[Int] = None
    var noBrowser: Boolean = false
    val remaining = List.newBuilder[String]
    var i = 0
    while i < args.length do
      args(i) match
        case "--home" if i + 1 < args.length =>
          home = Some(args(i + 1))
          i += 2
        case "--port" if i + 1 < args.length =>
          port = Try(args(i + 1).toInt).toOption
          i += 2
        case "--no-browser" =>
          noBrowser = true
          i += 1
        case other =>
          remaining += other
          i += 1
    if noBrowser then GatewayConfig.setNoBrowser(true)
    (home, port, remaining.result())

  end parseGlobalFlags

  /** Expand ~ to user home directory. */
  private def expandHome(path: String): String =
    if path.startsWith("~/") then sys.props("user.home") + path.substring(1)
    else if path == "~" then sys.props("user.home")
    else path

  private def startGateway(): IO[ExitCode] =
    // Ensure config directory exists (first-run setup)
    val configPath = Config.DefaultConfigPath
    if !os.exists(configPath) then os.write.over(configPath, "{}", createFolders = true)

    ProcessManager.readPid() match
      case Some(pid) if ProcessManager.isRunning(pid) =>
        // PID fast path (same home). Port-level URL is probed best-effort so
        // the focus action opens the live instance even when the pid file's
        // port assumption differs (--port override on the running instance).
        for
          cfg <- nebflow.gateway.GatewayConfig.load
          state = pidFocusState(cfg)
          _ <- state match
            case Some(url) =>
              nebflow.cli.SingleInstanceGuard.focusExisting(
                url,
                s"pid $pid",
                openBrowser = !nebflow.gateway.GatewayConfig.noBrowser
              )
            case None =>
              IO.println(s"nebflow is already running (pid: $pid)") *>
                IO.println("Run 'nebflow stop' to stop it.")
          // Focusing an existing instance is a success exit — the desktop
          // .app relaunch must not look like a crash (stdout is discarded
          // there; the browser focus is the visible action).
        yield ExitCode.Success
      case _ =>
        // Port-level guard (2026-08-27 hard requirement): the pid file is
        // per-home and misses cross-home collisions, stale files, and older
        // builds — the port is the actual contended resource.
        nebflow.gateway.GatewayConfig.load.flatMap { cfg =>
          nebflow.cli.SingleInstanceGuard
            .checkPort(cfg.host.toString, cfg.port.value)
            .flatMap {
              case nebflow.cli.SingleInstanceGuard.PortFree =>
                bootGateway()
              case nebflow.cli.SingleInstanceGuard.NebflowInstance(url) =>
                nebflow.cli.SingleInstanceGuard
                  .focusExisting(
                    url,
                    "same port",
                    openBrowser = !nebflow.gateway.GatewayConfig.noBrowser
                  )
                  .as(ExitCode.Success)
              case nebflow.cli.SingleInstanceGuard.ForeignOccupant(detail) =>
                IO.println(s"ERROR: $detail — nebflow cannot start.") *>
                  IO.println(
                    "Stop it, or pick another port (--port / GATEWAY_PORT)."
                  ) *> IO.pure(ExitCode.Error)
            }
        }

  /** For the pid-file hit: probe whether our port serves a nebflow health
    * endpoint (best-effort, reused from the guard). Some -> focus URL.
    */
  private def pidFocusState(cfg: nebflow.gateway.GatewayConfig): Option[String] =
    nebflow.cli.SingleInstanceGuard.checkPortBlocking(cfg.host.toString, cfg.port.value)

  private def bootGateway(): IO[ExitCode] =
    val pid = java.lang.ProcessHandle.current().pid()
    ProcessManager.writePid(pid)
    // JVM shutdown hook as backup — ensures PID file cleanup even on SIGINT/SIGTERM
    Runtime.getRuntime.addShutdownHook(new Thread(() => ProcessManager.removePid()))
    // P0 (2026-08-19): Ctrl+C left in-flight LLM HTTP requests running while
    // the JVM drained — a token-burn path (2亿 token 事故潜在路径). Abort
    // every active FS2/sttp stream on shutdown so no request continues past
    // the hook. Idempotent + exception-safe (runs on IORuntime.global).
    Runtime.getRuntime.addShutdownHook(
      new Thread(() => nebflow.llm.LlmInterface.cancelAllInflightSync())
    )
    // GatewayMain.run(Nil): boot the real gateway. Nil is load-bearing — the
    // P0 2026-09-06 arg gate in GatewayMain.run rejects ANY argument, and Main
    // has already consumed --home/--port/--no-browser as global flags.
    nebflow.gateway.GatewayMain.run(Nil)
      .guarantee(IO.blocking(ProcessManager.removePid()))
      .as(ExitCode.Success)
  end bootGateway
end Main
