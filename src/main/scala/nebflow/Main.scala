package nebflow

import cats.effect.{ExitCode, IO, IOApp}
import nebflow.cli.*
import nebflow.gateway.GatewayConfig
import nebflow.shared.{Branding, Config, Defaults, PathUtil}

import scala.util.Try

// ── 文件布局（Python 惯例：先定义，后执行）────────────────────────────────
//
// 本文件按 "definitions first, entry point last" 排列，从上往下读就是
// 一个完整的启动故事：
//
//   1. Dispatch ADT       —— 决策结果（sealed：子类型封闭、match 可穷尽）
//   2. MainDispatch       —— 纯决策函数（无 IO、无盘、spec 可直驱）
//   3. MainBoot           —— 真正的启动编排（全部 IO 副作用都在这一段）
//   4. Main extends IOApp —— 文件最底部的入口壳（只把 run 转给 MainBoot）
//
// Scala 与 Python 的差别要说明白：Scala 声明顺序不影响语义（object 可前向
// 引用），所以"入口放最后"是**可读性约定**，不是编译要求。Python 的
// `if __name__ == "__main__": main()` 则是语法必需（模块顶层代码在 import
// 时就会执行）。此处照搬的是阅读节奏，不是运行时约束。
//
// ── Dispatch decision core (phase 2 daemon plan §1.1, F-6) ───────────────
//
// Pure, IO-free, disk-free: the whole start-path decision tree lives here so
// the spec can drive the full matrix (flags x commands) without booting a
// Main. `MainBoot.run` only orchestrates IO per the returned Dispatch.

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

end Dispatch

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
      case Nil => true
      case head :: _ => StartModes.contains(head)
    if !isStart then Dispatch.CliRoute
    else
      succeedOpt match
        case Some(intent) => Dispatch.Succeed(intent)
        case None =>
          if daemonChild then Dispatch.StrictChild
          else if foreground then Dispatch.ForegroundLegacy
          else if !classPathSpawnable then Dispatch.ForegroundNoSpawn
          else Dispatch.Daemon
  end decide

  /**
   * 显式 `--takeover` 旗标（隔离实例误伤批 2026-11-04 §2）：**只有**显式给出才允许
   * 接管「端口被另一个 home 的活实例占着」这一情形（[[SingleInstanceGuard.preemptOtherHome]]
   * 仍然只对 home 可证且 ≠ 本家的 listener 发信号；home 不可判者一律不碰）。
   * 默认为 false —— 撞另一个 home 的实例一律拒启、零信号、绝不 focus 绝不 kill。
   */
  @volatile private var _takeover: Boolean = false
  def setTakeover(v: Boolean): Unit = _takeover = v
  def takeover: Boolean = _takeover

  /**
   * Strict-boot port gate verdict: only PortFree may boot; a nebflow
   * instance or foreign occupant is a zero-signal refusal (ExitCode.Error).
   */
  def decideStrictPort(state: SingleInstanceGuard.PortState): Either[ExitCode, Unit] =
    state match
      case SingleInstanceGuard.PortFree => Right(())
      case _ => Left(ExitCode.Error)

  /** The refusal text paired with a Left from [[decideStrictPort]]. */
  def strictRefusalMessage(state: SingleInstanceGuard.PortState, port: Int): Option[String] =
    state match
      case SingleInstanceGuard.PortFree => None
      case SingleInstanceGuard.NebflowInstance(_) =>
        Some(
          s"daemon child refused: port $port already served by a ${Branding.productName} instance — " +
            "not focusing (no terminal), not killing"
        )
      // 双实例并存批 2026-10-30：占口的是另一命令品牌的实例 —— 点名是谁、默认家/口在哪。
      case SingleInstanceGuard.OtherInstance(brand, _) =>
        Some(
          s"daemon child refused: port $port is held by the OTHER instance brand '$brand' " +
            s"(default home ~/.$brand, default port ${Defaults.legacyPortOf(brand)}) — " +
            s"${Branding.productName} and '$brand' are isolated and meant to coexist; they never share " +
            s"a port. Stop that instance ('$brand stop') or pick another port " +
            "(--port / GATEWAY_PORT / config 'port' key)."
        )
      // 隔离实例误伤批 2026-11-04 §2：端口被**另一个 home 的活实例**占着 —— 同品牌但不同
      // 数据根就是不同实例。拒启并点名（占用者 home/端口/pid + 建议），**绝不 focus、绝不 kill**；
      // 只有显式 --takeover 才允许接管（见 portGateThen）。
      case SingleInstanceGuard.OtherHomeInstance(home, _, pid) =>
        Some(
          s"daemon child refused: port $port is held by an instance of ANOTHER home ($home, pid $pid) — " +
            s"this instance roots at ${PathUtil.dataRoot}; different data roots are different instances, " +
            "so it is never focused and never killed (zero signal). Stop it from its own home " +
            "(--home <that root> / --takeover to take it over), or pick another port (--port / config 'port' key)."
        )
      case SingleInstanceGuard.ForeignOccupant(detail) =>
        Some(
          s"ERROR: $detail — ${Branding.productName} cannot start.\nStop it, or pick another port (--port / GATEWAY_PORT / config 'port' key)."
        )

end MainDispatch

private object MainBoot:

  /**
   * 启动编排（真正的执行部分）。
   *
   * 顺序即语义，勿重排：旗标解析 → 数据根定案 → logback 桥接 → 端口覆盖 →
   * 前台判定 → 六态分发。返回 `IO[ExitCode]`，由文件底部的 [[Main]] 执行。
   *
   * 与 [[MainDispatch]] 的分工是刻意的：`MainDispatch` 只回答「该走哪条路」
   * （纯函数，spec 可直接驱动）；本方法只回答「这条路怎么走」（IO 与进程
   * 副作用）。本对象**不实现** `IOApp` —— 把退出码交给 JVM 是入口壳的职责。
   */
  def run(args: List[String]): IO[ExitCode] =
    // Phase 1: Parse global flags before any dispatching
    val (homeOpt, portOpt, succeedOpt, daemonChild, explicitForeground, remaining) = parseGlobalFlags(args)

    // 数据根解析（单一处）：CLI --home > STAND_HOME > NEBFLOW_HOME（legacy）>
    // 默认 ~/.stand（legacy-only 机器回落 ~/.nebflow + 一行提示，见
    // PathUtil.resolveDefaultDataRoot）。解析发生在任何 logger 初始化之前，
    // logback 的 FILE 路径由下面这行桥接钉到同一个根。
    //
    // == 三层数据根（b1-ladder 批；契约 design-api.md §8.1 启动序；分档语义见
    //    PathUtil.resolvedLayer 文档 src/main/scala/nebflow/shared/paths.scala:272-274）==
    // 本批起这里定的是**机器层根**（machineRoot），有效根（账号层 / 未登录层 / 平铺根）由
    // core/root 的 RootLadder 阶梯派生（src/main/scala/nebflow/core/root/RootLadder.scala）：
    //   1. machineRoot = --home | PathUtil.machineRoot（今日解析链原样，不引入第二套 home 解析）
    //   2. PathUtil.setMachineRoot(machineRoot) ← 只钉机器层（生产启动序唯一调用点，
    //      paths.scala:252；进程内二次换值即拒 = 「禁热切」，判定面 = LoginGuard.checkHotSwitch，
    //      守卫判定逻辑只在 core/root/Guards.scala 一处）
    //   3. PathUtil.dataRoot / RootLadder.current 定档（lazy，一次）：
    //      account（指针⇒账号根）/ unauthenticated（layout.json+无指针⇒machineRoot/unauthenticated）
    //      / flat（无 layout.json 的平铺根 ⇒ 有效根 == 机器根 —— 老用户启动路径**逐字节不变**）
    //      显式 --home 平铺档钉死语义（paths.scala:272-273）不被本批破坏：--home 指向普通目录
    //      ⇒ 平铺档；--home 同时接受账号根（阶梯第 0 档 profile.json 判别，不新增旗标）。
    // 迁移触发点（契约 §4.1）：登录腿显式动作（NeblinkEnrollment.persistCredential ⇒
    // establishAccountRootAfterSignIn：平铺首登先 RootLayout.migrate 再写指针，失败 rollback），
    // **绝不在 boot 自动跑** —— 平铺根升级后第一次启动必须仍 flat + 零写盘（兼容红线）；
    // 启动序只负责把迁移产出（layout.json + 指针）经阶梯解析成账号档。
    val machineRoot = homeOpt match
      case Some(h) => os.Path(expandHome(h), os.pwd)
      case None => PathUtil.machineRoot // dual-env read (Branding.env) + default resolution
    // 禁热切的自检（§0 总则 2）：定根前后各取一次 —— 阶梯绝不允许在定根过程中把机器层根换掉。
    // 两侧取的是同一个声明值，故正常启动恒 Right；Left 只可能来自实现被改坏。
    val declaredMachineRoot = machineRoot
    PathUtil.setMachineRoot(machineRoot)
    nebflow.core.root.LoginGuard.checkHotSwitch(declaredMachineRoot, PathUtil.machineRoot) match
      case Right(_) => ()
      case Left(refusal) =>
        System.err.println(refusal.message)
        throw new IllegalStateException(refusal.message)
    // 产品侧同源（2026-09-10 作者裁定）：`--home` 决定一切落盘位置，日志同属
    // 该语义范围内——不是新语义，是既有语义的补齐。logback 的 FILE appender
    // 读 `STAND_HOME` / `NEBFLOW_HOME`（src/main/resources/logback.xml），单靠
    // 换根只能改 JVM 内的数据根，日志仍落 logback 自身默认 ⇒「数据在
    // A、日志在 B」的分叉。本批起桥接**无条件**进行（含两 env 均未设的默认档）：
    // legacy-only 机器数据根回落 ~/.nebflow 时，日志必须跟着落 ~/.nebflow。
    // 取值与本启动序定根完全同源（同一解析出的 `machineRoot`），不引入第二套 home 解析。
    // 先落系统属性，再定档：logback 在 LoggerContext 首次解析配置时就固化
    // FILE 路径，本行必须早于任何 `LoggerFactory.getLogger` 首用——Main.run
    // 之前只有 IOApp 引导（不碰 slf4j），parseGlobalFlags 亦不触 logger，故
    // 此处是最早点（定档可能触发的 ROOT-POINTER-DAMAGED WARN 也晚于本行）。
    // logback 的 ${STAND_HOME:-${NEBFLOW_HOME:-…}} 解析顺序为
    // 系统属性 > 环境变量，桥接用的旧名系统属性胜出、env 作兜底（CLI --home
    // 同理钉住日志位置）。
    //
    // ⚠️ 三层数据根批改一处（§8.1 第 5 步）：这里写**机器层根**，不是有效根 ——
    // 账号根不可解析时必须仍能写生命周期日志；`sys.props("NEBFLOW_HOME")` 全仓只有
    // logback 读（logback.xml:24/29），故对数据根解析零影响。
    sys.props("NEBFLOW_HOME") = machineRoot.toString
    // 有效根（账号层 / 未登录层 / 平铺根）—— 数据面从这里起全部走 dataRoot。
    // 首读即定档：PathUtil.resolvedLayer 的进程级快照（RootLadder.resolve 跑一次）。
    val root = PathUtil.dataRoot
    val rootLayer = nebflow.core.root.RootLadder.current
    // [b2-owner 占位] OwnerGuard 接线调用位（下一节点实现判定，本节点不接任何判定）：
    //   ownerGate 应挂在 GatewayMain.runGateway 开头（真实 boot 面）——
    //   OwnerGuard.check(账号档 accountSub（RootLadder.current）, OwnerIdentity.localAnchor)，
    //   拒启不静默；判定逻辑只在 core/root/Guards.scala（守卫单点铁律）。
    // 后台任务输出落盘（输出查看补全批 2026-10-25 作者令需求④）：数据根在此
    // 定案后一次性开启（`<dataRoot>/bg-task-logs/`）——只在真实 boot 面开启，
    // CLI 路由与 spec（不跑 Main.run）保持纯内存，零测试污染。
    // 三层数据根批：传**有效根**（后台任务输出属账号内容，§8.1 尾条）。
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
        case "--takeover" =>
          // 显式接管（隔离实例误伤批 2026-11-04 §2）：只影响「端口被另一个 home 的
          // 活实例占着」这一类的裁决，其余单实例门零改变。
          MainDispatch.setTakeover(true)
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
            nebflow.gateway.PortalUrl
              .focusUrl(
                url,
                authenticateLocal = !GatewayConfig.noBrowser && !nebflow.core.HeadlessMode.enabled
              )
              .flatMap { focusTarget =>
                // #512 门审计批：聚焦腿开门目标落 stdout（CLI 路径无 logger 面）——
                // 二实例撞端口时「开的是哪条门」可从终端对账。
                //
                // 工作过程中开新标签页批（2026-11-05 · 作者令）：本腿同样是**自动**
                // 开门（二实例撞端口就把浏览器拉到控制台），作者可能正在用控制台
                // ⇒ 与 boot 门共用 ConsoleActivity 判据（读**活实例**写下的控制台
                // 在用标记件）。在用 ⇒ 只打「已在运行」的人话，不 exec 浏览器；
                // 事件本身仍可从终端对账（零静默）。
                nebflow.gateway.ConsoleActivity.verdict.flatMap {
                  case nebflow.gateway.ConsoleActivity.Verdict(true, why) =>
                    IO.println(
                      s"[door] focus 开门: 目标=$focusTarget（解析=PortalUrl env>config>brand；NEBFLOW_OPEN_UI=local ⇒ 本地自铸票；判据=$why）"
                    ) *>
                      // 持久审计（同日批）：stdout 只活在本进程终端里，撞端口那一刻的
                      // 宿主日志「零开门记录」正是取证卡点 ⇒ 同一判词追加落
                      // `<dataRoot>/logs/door-focus.log`（best-effort，不拦开门）。
                      nebflow.gateway.ConsoleActivity.audit(
                        s"[door] focus 开门: 目标=$focusTarget 判据=$why pid=${ProcessHandle.current().pid()}"
                      ) *>
                      nebflow.cli.SingleInstanceGuard
                        .focusExisting(
                          focusTarget,
                          "same port",
                          openBrowser = !nebflow.gateway.GatewayConfig.noBrowser
                        )
                        .as(ExitCode.Success)
                  case nebflow.gateway.ConsoleActivity.Verdict(false, why) =>
                    IO.println(s"[door] focus 开门 suppressed: $why") *>
                      nebflow.gateway.ConsoleActivity.audit(
                        s"[door] focus 开门 suppressed: $why 目标=$focusTarget pid=${ProcessHandle.current().pid()}"
                      ) *>
                      nebflow.cli.SingleInstanceGuard
                        .focusExisting(focusTarget, "same port", openBrowser = false)
                        .as(ExitCode.Success)
                }
              }
          case nebflow.cli.SingleInstanceGuard.OtherHomeInstance(home, _, pid) =>
            // 隔离实例误伤批 2026-11-04 §2：同品牌但**另一个 home** 的活实例占着这个口 ——
            // 不同数据根就是不同实例。默认：拒启 + 点名，零信号，绝不 focus（那是别人的
            // 实例），绝不 kill。只有显式 --takeover 才走接管腿。
            if MainDispatch.takeover then
              IO.blocking(
                nebflow.cli.SingleInstanceGuard.preemptOtherHome(cfg.port.value, PathUtil.dataRoot.toString)
              ) *> IO.println(
                s"[takeover] port ${cfg.port.value}: explicit --takeover — took over the instance of home $home (pid $pid)"
              ) *> onFree
            else
              IO.println(
                s"ERROR: port ${cfg.port.value} is held by an instance of ANOTHER home ($home, pid $pid)."
              ) *>
                IO.println(
                  s"This instance roots at ${PathUtil.dataRoot}; different data roots are different instances, " +
                    "so it is never focused and never killed (zero signal)."
                ) *>
                IO.println(
                  "Stop it from its own home (--home <that root>), pass --takeover to take it over explicitly, " +
                    "or pick another port (--port / config 'port' key)."
                ) *> IO.pure(ExitCode.Error)
          case nebflow.cli.SingleInstanceGuard.OtherInstance(brand, _) =>
            // 双实例并存批 2026-10-30：同机并存的对家命令品牌占着这个口 ——
            // 绝不 focus（那是别人的实例/别人的家），拒启并点名。
            IO.println(
              s"ERROR: port ${cfg.port.value} is held by the OTHER instance brand '$brand' " +
                s"(default home ~/.$brand, default port ${Defaults.legacyPortOf(brand)})."
            ) *>
              IO.println(
                s"'${Branding.productName}' and '$brand' are isolated and meant to coexist — they never share " +
                  s"a port. Stop that instance ('$brand stop') or pick another port (--port / GATEWAY_PORT / config 'port' key)."
              ) *> IO.pure(ExitCode.Error)
          case nebflow.cli.SingleInstanceGuard.ForeignOccupant(detail) =>
            IO.println(s"ERROR: $detail — ${Branding.productName} cannot start.") *>
              IO.println(
                "Stop it, or pick another port (--port / GATEWAY_PORT / config 'port' key)."
              ) *> IO.pure(ExitCode.Error)
        }
    }

  /**
   * The listening port is authoritative. A PID copied from another device
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
end MainBoot

// ── 入口（文件末尾，对应 Python 的 `if __name__ == "__main__": main()`）──────
//
// JVM 侧链路：`Main-Class: nebflow.Main`（build.sbt:243）→ `IOApp` 提供的
// `public static void main(String[])` 静态转发器 → 下面的 `run`。
//
// 本对象刻意只做一件事：把参数转交 [[MainBoot]]。任何启动逻辑都不写在这里
// —— 它是开关，不是电路。
object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] = MainBoot.run(args)
end Main
