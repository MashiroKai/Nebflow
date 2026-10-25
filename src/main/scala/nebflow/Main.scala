package nebflow

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import nebflow.cli.*
import nebflow.gateway.GatewayConfig
import nebflow.shared.{Branding, Config, PathUtil}

import scala.util.Try

// ── Dispatch decision core (phase 2 daemon plan §1.1, F-6) ───────────────
//
// Pure, IO-free, disk-free: the whole start-path decision tree lives here so
// the spec can drive the full matrix (flags x commands) without booting a
// Main. `Main.run` only orchestrates IO per the returned Dispatch.

sealed trait Dispatch
object Dispatch:
  /** `--succeed <intent>` on the start path — hot-restart successor boot. */
  final case class Succeed(intent: os.Path) extends Dispatch
  /** `--daemon-child` — the spawned gateway JVM booting under strict gating. */
  case object StrictChild extends Dispatch
  /** Foreground exemption — today's full start semantics (sbt run / explicit). */
  case object ForegroundLegacy extends Dispatch
  /** Classpath-empty (jpackage/bundled) form — no spawn possible, run foreground. */
  case object ForegroundNoSpawn extends Dispatch
  /** Default product form — parent launcher spawns the daemon child JVM. */
  case object Daemon extends Dispatch
  /** Everything else (incl. `--succeed X status`) goes to the CLI router. */
  case object CliRoute extends Dispatch

object MainDispatch:

  private val StartModes = Set("start", "-s", "--server")

  /**
   * The start-path decision. `--succeed` is deliberately NOT top-level: it
   * only diverts on the start/Nil path (today's structure), so
   * `nebflow --succeed X status` routes to CliRouter and runs `status`
   * (R2-2b regression face). Precedence inside the start path: successor
   * boot > daemon child > foreground > spawnability > daemon.
   */
  def decide(
      cmdArgs: List[String],
      succeedOpt: Option[os.Path],
      daemonChild: Boolean,
      foreground: Boolean,
      classPathSpawnable: Boolean
  ): Dispatch =
    val isStart = cmdArgs match
      case Nil          => true
      case head :: _    => StartModes.contains(head)
    if !isStart then Dispatch.CliRoute
    else
      succeedOpt match
        case Some(intent) => Dispatch.Succeed(intent)
        case None =>
          if daemonChild then Dispatch.StrictChild
          else if foreground then Dispatch.ForegroundLegacy
          else if !classPathSpawnable then Dispatch.ForegroundNoSpawn
          else Dispatch.Daemon

  /**
   * Strict-boot port gate verdict: only PortFree may boot; a nebflow
   * instance or foreign occupant is a zero-signal refusal (ExitCode.Error).
   */
  def decideStrictPort(state: SingleInstanceGuard.PortState): Either[ExitCode, Unit] =
    state match
      case SingleInstanceGuard.PortFree => Right(())
      case _                            => Left(ExitCode.Error)

  /** The refusal text paired with a Left from [[decideStrictPort]]. */
  def strictRefusalMessage(state: SingleInstanceGuard.PortState, port: Int): Option[String] =
    state match
      case SingleInstanceGuard.PortFree => None
      case SingleInstanceGuard.NebflowInstance(_) =>
        Some(
          s"daemon child refused: port $port already served by a ${Branding.productName} instance — " +
            "not focusing (no terminal), not killing"
        )
      case SingleInstanceGuard.ForeignOccupant(detail) =>
        Some(s"ERROR: $detail — ${Branding.productName} cannot start.\nStop it, or pick another port (--port / GATEWAY_PORT).")

end MainDispatch

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    // Phase 1: Parse global flags before any dispatching
    val (homeOpt, portOpt, succeedOpt, daemonChild, explicitForeground, remaining) = parseGlobalFlags(args)

    // 数据根解析（单一处）：CLI --home > STAND_HOME > NEBFLOW_HOME（legacy）>
    // 默认 ~/.stand（legacy-only 机器回落 ~/.nebflow + 一行提示，见
    // PathUtil.resolveDefaultDataRoot）。解析发生在任何 logger 初始化之前，
    // logback 的 FILE 路径由下面这行桥接钉到同一个根。
    val root = homeOpt match
      case Some(h) => os.Path(expandHome(h), os.pwd)
      case None => PathUtil.dataRoot // dual-env read (Branding.env) + default resolution
    // 产品侧同源（2026-09-10 作者裁定）：`--home` 决定一切落盘位置，日志同属
    // 该语义范围内——不是新语义，是既有语义的补齐。logback 的 FILE appender
    // 读 `STAND_HOME` / `NEBFLOW_HOME`（src/main/resources/logback.xml），单靠
    // setDataRoot 只能改 JVM 内的数据根，日志仍落 logback 自身默认 ⇒「数据在
    // A、日志在 B」的分叉。本批起桥接**无条件**进行（含两 env 均未设的默认档）：
    // legacy-only 机器数据根回落 ~/.nebflow 时，日志必须跟着落 ~/.nebflow。
    // 取值与 setDataRoot 完全同源（同一解析出的 `root`），不引入第二套 home 解析。
    // 先落系统属性，再设数据根：logback 在 LoggerContext 首次解析配置时就固化
    // FILE 路径，本行必须早于任何 `LoggerFactory.getLogger` 首用——Main.run
    // 之前只有 IOApp 引导（不碰 slf4j），parseGlobalFlags 亦不触 logger，故
    // 此处是最早点。logback 的 ${STAND_HOME:-${NEBFLOW_HOME:-…}} 解析顺序为
    // 系统属性 > 环境变量，桥接用的旧名系统属性胜出、env 作兜底（CLI --home
    // 同理钉住日志位置）。
    sys.props("NEBFLOW_HOME") = root.toString
    PathUtil.setDataRoot(root)
    // 后台任务输出落盘（输出查看补全批 2026-10-25 作者令需求④）：数据根在此
    // 定案后一次性开启（`<dataRoot>/bg-task-logs/`）——只在真实 boot 面开启，
    // CLI 路由与 spec（不跑 Main.run）保持纯内存，零测试污染。
    nebflow.core.tools.BgTaskOutputStore.enablePersistence(root)
    portOpt.foreach { p =>
      GatewayConfig.setPort(p)
    }
    portOpt.foreach(p => GatewayClient.setPort(p))

    // Foreground determination (phase 2 daemon plan §1.5): sbt forks this
    // JVM with -Dnebflow.start.foreground=true (build.sbt javaOptions), so
    // the dev form keeps today's byte-identical foreground behavior; an
    // explicit --foreground / NEBFLOW_START_FOREGROUND=1 is the operator
    // escape hatch (still the launcher plane, so stop may manage it).
    val syspropForeground = sys.props.get("nebflow.start.foreground").contains("true")
    val envForeground = Branding.env("START_FOREGROUND").contains("1")
    val foreground = explicitForeground || syspropForeground || envForeground
    // Meta attribution for foreground boots: sbt-owned when injected by the
    // build (conservative — stop refuses by default), launcher otherwise.
    val foregroundStartedBy =
      if syspropForeground then PidMeta.StartedBySbt else PidMeta.StartedByLauncher

    // Phase 2: Command dispatching (stop migrated back into CliRouter —
    // its --json/--quiet and exit-code faces are the router's own).
    val cmdArgs = remaining.filterNot(a => a == "--json" || a == "--quiet")

    MainDispatch.decide(cmdArgs, succeedOpt, daemonChild, foreground, DaemonSpawn.classPathSpawnable) match
      case Dispatch.CliRoute =>
        CliRouter.run(remaining)
      case Dispatch.Succeed(intentPath) =>
        // F-5: first-run config only on the start path — pure query commands
        // keep zero write side effects.
        ensureFirstRunConfig() *> succeedGateway(intentPath)
      case Dispatch.StrictChild =>
        strictBoot()
      case Dispatch.ForegroundLegacy =>
        startGatewayLegacy(foregroundStartedBy)
      case Dispatch.ForegroundNoSpawn =>
        // R2-4: jpackage/bundled forms have an empty java.class.path — the
        // child JVM could not be spawned. Degrade to today's foreground form
        // (unchanged .app behavior) with a stderr declaration.
        IO.blocking(
          System.err.println(
            "daemon spawn unavailable (empty java.class.path — jpackage/bundled form); running in foreground"
          )
        ) *> startGatewayLegacy(PidMeta.StartedByLauncher)
      case Dispatch.Daemon =>
        startGatewayDaemon()

    end match

  end run

  /**
   * Parse --home, --port, --no-browser, --succeed, --daemon-child,
   * --foreground from args, returning
   * (homeOpt, portOpt, succeedIntentOpt, daemonChild, foreground, remainingArgs).
   *
   * --succeed <intentPath>（hot-restart 批设计 §3.3）：热重启后继进场旗标——
   * 仅热重启编排器 spawn 的后继进程携带；普通启动永不出现（普通启动不解析
   * 任何 intent）。
   *
   * --daemon-child / --foreground (phase 2): consumed HERE, never leaked into
   * `remaining` — a leaked flag would hit CliRouter's unknown-flag face.
   */
  private def parseGlobalFlags(
      args: List[String]
  ): (Option[String], Option[Int], Option[os.Path], Boolean, Boolean, List[String]) =
    var home: Option[String] = None
    var port: Option[Int] = None
    var succeed: Option[os.Path] = None
    var noBrowser: Boolean = false
    var daemonChild: Boolean = false
    var foreground: Boolean = false
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
        case "--daemon-child" =>
          daemonChild = true
          i += 1
        case "--foreground" =>
          foreground = true
          i += 1
        case other =>
          remaining += other
          i += 1
    end while
    if noBrowser then GatewayConfig.setNoBrowser(true)
    (home, port, succeed, daemonChild, foreground, remaining.result())

  end parseGlobalFlags

  /** Expand ~ to user home directory. */
  private def expandHome(path: String): String =
    if path.startsWith("~/") then sys.props("user.home") + path.substring(1)
    else if path == "~" then sys.props("user.home")
    else path

  /** Ensure config directory exists (first-run setup; start path only — F-5). */
  private def ensureFirstRunConfig(): IO[Unit] = IO.blocking {
    val configPath = Config.DefaultConfigPath
    if !os.exists(configPath) then os.write.over(configPath, "{}", createFolders = true)
  }

  /**
   * Daemon-child boot (R2-2a): the spawned child JVM carries its OWN strict
   * port gate — a manual `nebflow --daemon-child --port 8080` against a live
   * nebflow instance on 8080 must refuse with zero signals, never reach
   * GatewayMain's ensureSingleInstance kill path. No pid fast path (the
   * child has no terminal; focusing is meaningless) and no meta/launcher
   * face — bootGateway writes .pid + meta as the real gateway process.
   */
  private def strictBoot(): IO[ExitCode] =
    ensureFirstRunConfig() *>
      GatewayConfig.load.flatMap { cfg =>
        nebflow.cli.SingleInstanceGuard
          .checkPort(cfg.host.toString, cfg.port.value)
          .flatMap { state =>
            MainDispatch.decideStrictPort(state) match
              case Right(()) =>
                bootGateway(PidMeta.StartedByLauncher, PidMeta.ModeDaemon)
              case Left(code) =>
                val msg = MainDispatch
                  .strictRefusalMessage(state, cfg.port.value)
                  .getOrElse("daemon child refused to start")
                IO.blocking(System.err.println(msg)).as(code)
          }
      }

  /** Port gate with focus/foreign handling shared by both start faces. */
  private def portGateThen(onFree: => IO[ExitCode]): IO[ExitCode] =
    nebflow.gateway.GatewayConfig.load.flatMap { cfg =>
      nebflow.cli.SingleInstanceGuard
        .checkPort(cfg.host.toString, cfg.port.value)
        .flatMap {
          case nebflow.cli.SingleInstanceGuard.PortFree =>
            onFree
          case nebflow.cli.SingleInstanceGuard.NebflowInstance(url) =>
            // 门户开门（同 pid 快道）：聚焦目标换 PortalUrl 解析值；探测原样。
            nebflow.gateway.PortalUrl.focusUrl(
              url,
              authenticateLocal = !GatewayConfig.noBrowser && !nebflow.core.HeadlessMode.enabled
            ).flatMap { focusTarget =>
              // #512 门审计批：聚焦腿开门目标落 stdout（CLI 路径无 logger 面）——
              // 二实例撞端口时「开的是哪条门」可从终端对账。
              IO.println(
                s"[door] focus 开门: 目标=$focusTarget（解析=PortalUrl env>config>brand；NEBFLOW_OPEN_UI=local ⇒ 本地自铸票）"
              ) *>
                nebflow.cli.SingleInstanceGuard
                  .focusExisting(
                    focusTarget,
                    "same port",
                    openBrowser = !nebflow.gateway.GatewayConfig.noBrowser
                  )
                  .as(ExitCode.Success)
            }
          case nebflow.cli.SingleInstanceGuard.ForeignOccupant(detail) =>
            IO.println(s"ERROR: $detail — ${Branding.productName} cannot start.") *>
              IO.println(
                "Stop it, or pick another port (--port / GATEWAY_PORT)."
              ) *> IO.pure(ExitCode.Error)
        }
    }

  /** The listening port is authoritative. A PID copied from another device
   * can refer to an unrelated live process, and must never suppress startup.
   */
  private def startGatewayLegacy(metaStartedBy: String): IO[ExitCode] =
    ensureFirstRunConfig() *>
      portGateThen(bootGateway(metaStartedBy, PidMeta.ModeForeground))

  /** Product start uses the same port gate before spawning its daemon. */
  private def startGatewayDaemon(): IO[ExitCode] =
    ensureFirstRunConfig() *>
      portGateThen(
        nebflow.gateway.GatewayConfig.load.flatMap { cfg =>
          DaemonSpawn.launch(PathUtil.dataRoot, cfg.port.value, GatewayConfig.noBrowser)
        }
      )

  /**
   * Hot-restart successor entry (--succeed <intent>, hot-restart 批设计 §3.3):
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
        // Hooks mirror bootGateway (pid cleanup + graceful-interrupt on
        // SIGINT/SIGTERM) — minus the eager pid write (see doc above).
        Runtime.getRuntime.addShutdownHook(new Thread(() => ProcessManager.removePid()))
        // Single graceful-interrupt hook (中断恢复语义批 2026-09-13, spec
        // 20260908_interrupt-recovery-semantics §2.3-2): draining flag →
        // Running → interrupted CAS per node → in-flight LLM abort (the
        // 2026-08-19 P0 token-burn defence, order preserved last).
        Runtime.getRuntime.addShutdownHook(
          new Thread(() => nebflow.core.project.GracefulInterruptHook.run())
        )
        // GatewayMain.run(Nil): Nil is load-bearing (arg gate) — the successor
        // context travels via the static holder, not argv.
        // Exit code is passed through verbatim (JDK-21 gate, 2026-09-10): a
        // normal shutdown still yields Success, but a gate refusal inside
        // GatewayMain must not be swallowed into 0 — the hot-restart
        // orchestrator would then read "JVM too old, refused to start" as a
        // clean handover.
        nebflow.gateway.GatewayMain
          .run(Nil)
          .guarantee(IO.blocking(ProcessManager.removePid()))
    }

  private def bootGateway(startedBy: String, mode: String): IO[ExitCode] =
    val pid = java.lang.ProcessHandle.current().pid()
    // Phase 2 (§2.2, F-1): meta writeFresh BEFORE writePid — at this point
    // `.pid` is absent or stale (the start path focused-and-exited on a live
    // one), so the writeFresh rule allows it; the self-exemption in the rule
    // keeps this correct even if the two writes were ever reordered.
    PidMeta.writeFresh(startedBy, mode)
    ProcessManager.writePid(pid)
    // JVM shutdown hook as backup — ensures PID file cleanup even on SIGINT/SIGTERM
    Runtime.getRuntime.addShutdownHook(new Thread(() => ProcessManager.removePid()))
    // P0 (2026-08-19): Ctrl+C left in-flight LLM HTTP requests running while
    // the JVM drained — a token-burn path (2亿 token 事故潜在路径). Abort
    // every active FS2/sttp stream on shutdown so no request continues past
    // the hook. Idempotent + exception-safe (runs on IORuntime.global).
    //
    // 2026-09-13 (中断恢复语义批, spec 20260908_interrupt-recovery-semantics
    // §2.3-2 单钩子): the two cancelAllInflightSync registrations are merged
    // into ONE GracefulInterruptHook, strictly ordered inside its thread —
    // draining.set(true) → CAS every Running node Running → interrupted
    // (WS + audit trail) → cancelAllInflightSync(). The abort leg is unchanged
    // and stays LAST (order is correctness: the draining flag must be visible
    // before the abort-induced failure chain can reach failNode). Rollback
    // switch Defaults.ShutdownInterruptEnabled=false restores the pre-batch
    // behaviour byte-for-byte (abort only).
    Runtime.getRuntime.addShutdownHook(
      new Thread(() => nebflow.core.project.GracefulInterruptHook.run())
    )
    // GatewayMain.run(Nil): boot the real gateway. Nil is load-bearing — the
    // P0 2026-09-06 arg gate in GatewayMain.run rejects ANY argument, and Main
    // has already consumed --home/--port/--no-browser as global flags.
    // Exit code is passed through verbatim (JDK-21 gate, 2026-09-10): the
    // success path stays Success, but a gate refusal must not be swallowed
    // into 0 — scripts/launchd would otherwise read "JVM too old, refused to
    // start" as a normal exit.
    nebflow.gateway.GatewayMain
      .run(Nil)
      .guarantee(IO.blocking(ProcessManager.removePid()))
  end bootGateway
end Main
