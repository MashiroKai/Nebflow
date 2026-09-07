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
    val (homeOpt, portOpt, succeedOpt, remaining) = parseGlobalFlags(args)

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
        startGateway(succeedOpt)
      case head :: _ if stopModes.contains(head) =>
        // stop command
        ProcessManager.stop().as(ExitCode.Success)
      case head :: rest if startModes.contains(head) =>
        // start command — launch Gateway
        startGateway(succeedOpt)
      case "help" :: _ =>
        CliRouter.run(Nil)
      case _ =>
        // All other commands go through CLI router
        CliRouter.run(remaining)

    end match

  end run

  /** Parse --home, --port, --no-browser, --succeed from args, returning
    * (homeOpt, portOpt, succeedIntentOpt, remainingArgs).
    *
    * --succeed <intentPath>（hot-restart 批设计 §3.3）：热重启后继进场旗标——
    * 仅热重启编排器 spawn 的后继进程携带；普通启动永不出现（普通启动不解析
    * 任何 intent）。 */
  private def parseGlobalFlags(args: List[String]): (Option[String], Option[Int], Option[os.Path], List[String]) =
    var home: Option[String] = None
    var port: Option[Int] = None
    var succeed: Option[os.Path] = None
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
        case "--succeed" if i + 1 < args.length =>
          succeed = Some(os.Path(expandHome(args(i + 1)), os.pwd))
          i += 2
        case other =>
          remaining += other
          i += 1
    if noBrowser then GatewayConfig.setNoBrowser(true)
    (home, port, succeed, remaining.result())

  end parseGlobalFlags

  /** Expand ~ to user home directory. */
  private def expandHome(path: String): String =
    if path.startsWith("~/") then sys.props("user.home") + path.substring(1)
    else if path == "~" then sys.props("user.home")
    else path

  private def startGateway(succeedIntent: Option[os.Path] = None): IO[ExitCode] =
    // Ensure config directory exists (first-run setup)
    val configPath = Config.DefaultConfigPath
    if !os.exists(configPath) then os.write.over(configPath, "{}", createFolders = true)

    succeedIntent match
      case Some(intentPath) =>
        // 热重启后继分流（hot-restart 批设计 §3.3 / §4.2）：必须早于 pid 快路径与
        // 端口分类闸——否则后继会被「聚焦既有实例」语义劝退（:23-26 裁定语义对
        // 同一实例的接力后继不适用）。
        succeedGateway(intentPath)
      case None =>
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

  /** Hot-restart successor entry (--succeed <intent>, hot-restart 批设计 §3.3):
    * boot the gateway WITHOUT the pid fast-path and port classification gates —
    * the successor is the SAME instance's relay, not a second instance (the
    * guard's focus-existing semantics would kill the relay here; §4.2 bundled-
    * form note). The standard single-instance hardening still applies LATER,
    * in GatewayMain's succeed gate — but only after the old pid is confirmed
    * dead (a live old listener must NEVER be classified stale-and-killed by
    * its own successor: 禁裸 kill 红线零弱化).
    *
    * PID file discipline: NOT written here. The old instance's removePid
    * shutdown hook would race-delete a successor-written pid file; GatewayMain's
    * succeed gate writes it only after the old pid is confirmed dead (old hooks
    * have completed by then — pid death is the confirmation).
    */
  private def succeedGateway(intentPath: os.Path): IO[ExitCode] =
    // [s0] parse the intent EARLY — garbage intent → loud exit, zero side
    // effects; the old instance is unaffected (still serving; its C2 poll will
    // time out, TERM the dead child, clear draining and keep serving).
    IO.blocking(nebflow.core.hotrestart.SuccessorContext.load(intentPath)).flatMap {
      case Left(err) =>
        IO.println(s"ERROR: hot-restart successor refused intent: $err") *>
          IO.println("The old instance keeps serving — no action needed.") *>
          IO.pure(ExitCode.Error)
      case Right(ctx) =>
        nebflow.core.hotrestart.SuccessorContext.set(ctx)
        // Hooks mirror bootGateway (pid cleanup + in-flight LLM abort on
        // SIGINT/SIGTERM) — minus the eager pid write (see doc above).
        Runtime.getRuntime.addShutdownHook(new Thread(() => ProcessManager.removePid()))
        Runtime.getRuntime.addShutdownHook(
          new Thread(() => nebflow.llm.LlmInterface.cancelAllInflightSync())
        )
        // GatewayMain.run(Nil): Nil is load-bearing (arg gate) — the successor
        // context travels via the static holder, not argv.
        nebflow.gateway.GatewayMain.run(Nil)
          .guarantee(IO.blocking(ProcessManager.removePid()))
          .as(ExitCode.Success)
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
