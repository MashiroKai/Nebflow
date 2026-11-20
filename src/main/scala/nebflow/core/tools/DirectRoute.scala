package nebflow.core.tools

import cats.effect.IO
import io.circe.parser.parse as parseJson
import io.circe.Json
import nebflow.neblink.core.EndpointPreference
import nebflow.shared.NebflowLogger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

/**
 * 三档选路（作者裁定 2026-11-04 16:51 逐字：「tailscale是专门做这个的 我们也检测 如果有
 * tailscale的直连服务 就用它的 然后再用我们的 最后relay」）。
 *
 * == 病灶（presence-direct 取证已定案） ==
 *
 * 改前的「档位」是**地址形状猜出来的**：`EndpointPreference.rank` 把 `100.64/10` 硬编码成
 * rank 0（`NeblinkModel.scala:677-682`），于是「地址像 tailnet」就等于「① 档可用」。宿主
 * Tailscale 停着时，候选集塌成一条不可达的 CGNAT 地址 —— 排第一、拨不通、白付一份
 * `DialAddressClass.Tailnet` 预算，这就是全天直连 0% 的直接形态。
 *
 * 本文件的判据把「像」换成「测得」：**每一档都必须先证明自己可用，不可用的档位直接跳过**
 * （不付探测/超时税），而不是「排第一 → 失败 → 回落」。
 *
 * == 判据（三档 + 每档一次探测，全部有界、可缓存） ==
 *
 *   ① `tailscale`：[[TailnetProbe]] 测得「服务在跑 + 本机有 tailnet 地址 + 目标 peer 在其
 *      netmap 里 online」三条同时成立才选；任一条不成立给出**原因码**
 *      （`tailscale-stopped` / `tailscale-not-installed` / `peer-offline` / `probe-timeout` …）。
 *      同一档里非 tailnet 地址（LAN / 公网）的既有直拨**原样保留**（`via=direct`）——
 *      它们是既有生产行为，本批零回归；① 档的门只作用于 tailnet 地址。
 *   ② `self-p2p`：自研 UDP 打洞 P2P 的**预留槽**。全仓零 `DatagramSocket|DatagramChannel`
 *      ⇒ 恒判不可用，原因码 `p2p-not-implemented`；接入点见 [[SelfP2pSlot]]。
 *   ③ `relay`：兜底，**永远可用**（`relay-always-available`）；不可用档位被跳过后落到这里。
 *
 * == 可观测（本批硬要求 ③） ==
 *
 * 每次选路输出**一行**结构化日志（[[RoutePlan.logLine]]）：
 * `[route] device=… via=… reasons=… probeMs=… cached=… candidates=… kept=… tiers=…`
 * —— 将来任何一次「为什么没直连」都能从这一行读出来，不用猜。
 */
enum RouteTier(val label: String):
  /** ① Tailscale 直连（需探测证明可用）。 */
  case Tailscale extends RouteTier("tailscale")

  /** ② 自研 P2P（UDP 打洞）——预留槽位，未实现。 */
  case SelfP2p extends RouteTier("self-p2p")

  /** ③ relay 兜底（永远可用）。 */
  case Relay extends RouteTier("relay")

/**
 * 原因码表（日志/报表/判据共用一处，禁第二套字符串）。
 *
 * 命名口径：`<档位>-<状态>`，可 grep、可稳定断言；`via=` 的取值只有
 * `tailscale` / `direct` / `relay` 三种。
 */
object RouteReason:

  /** Tailscale 服务在，但后端状态不是 Running（macOS/Windows/Linux 同码）。 */
  val TailscaleStopped = "tailscale-stopped"

  /** 本机找不到 tailscale 可执行文件（未安装 / 不在已知平台路径上）。 */
  val TailscaleNotInstalled = "tailscale-not-installed"

  /** 服务在跑但要交互登录/机器授权（不是「停」，是「登不上」）。 */
  val TailscaleNeedsLogin = "tailscale-needs-login"

  /** Topology 里没有本机 tailnet 地址（服务异常态：Running 却没有 IP）。 */
  val TailscaleNoAddress = "no-tailnet-address"

  /** 探测本身超预算（进程不返回）——不得把整条命令路径拖住。 */
  val ProbeTimeout = "probe-timeout"

  /** 探测异常（解析失败 / 进程起不来 / 其它 Throwable）——一律降级为「不可用」。 */
  val ProbeError = "probe-error"

  /** 没有接入探测器（fail-open）：保留既有行为，不做门控。 */
  val Unprobed = "tailscale-unprobed"

  /** 服务在跑，但目标 peer 不在本机 netmap 的 online 集合里。 */
  val PeerOffline = "peer-offline"

  /** 候选集里根本没有 tailnet 地址（① 档不适用）。 */
  val NoTailnetCandidate = "no-tailnet-candidate"

  /** ① 档成立（探测三条全中）。 */
  val TailscaleReady = "tailscale-ready"

  /** ② 档：自研 P2P 尚未实现（占位）。 */
  val P2pNotImplemented = "p2p-not-implemented"

  /** ③ 档：兜底恒可用。 */
  val RelayAlwaysAvailable = "relay-always-available"

  /** 退化输入：候选集为空（既无直拨候选也无 tailnet 候选）。 */
  val NoCandidate = "no-candidate-endpoint"

end RouteReason

/**
 * 一次探测的判决。
 *
 * `Unprobed` 是**故意**的第一等公民：没有接入探测器时行为必须与改前逐字节相同
 * （fail-open）。理由：既有 40+ 套件用 tailnet 地址做夹具，若把「无探测器」当成
 * 「不可用」，本批会以「测试环境的网络形态」为理由改动生产判据——那是把测试债
 * 塞进生产判据里。
 */
enum TailnetVerdict:
  /** 未接入探测器 ⇒ 不做门控（fail-open，改前行为）。 */
  case Unprobed

  /** 测得不可用 + 原因码（+ 可读细节）。 */
  case Unavailable(reason: String, detail: String = "")

  /**
   * 测得可用：`selfAddresses` = 本机 tailnet 地址（v4 CGNAT 与 v6 ULA 都有），
   * `onlinePeers` = netmap 里 `Online=true` 的那些 peer 的 tailnet 地址。
   * `backendState` 仅用于日志（`Running`）。
   */
  case Ready(selfAddresses: Set[String], onlinePeers: Set[String], backendState: String)

object TailnetVerdict:

  /** 判决是否「可用」——只有 [[Ready]] 是。 */
  def usable(v: TailnetVerdict): Boolean = v match
    case Ready(_, _, _) => true
    case _ => false

  /** 不可用的原因码（`Ready`/`Unprobed` ⇒ None）。 */
  def reasonOf(v: TailnetVerdict): Option[String] = v match
    case Unavailable(r, _) => Some(r)
    case _ => None

/**
 * 一次探测的**读数**（判决 + 是否命中缓存 + 耗时）。
 *
 * `cached=true` 的 `ms` 恒 ≈0 —— 这是「不重复收税」判据的取数面（连续 N 次命令只探测 1 次）。
 */
final case class ProbeResult(verdict: TailnetVerdict, cached: Boolean, ms: Long)

object ProbeResult:

  /** 未接入探测器（零成本、恒 `Unprobed`）。 */
  val unprobed: ProbeResult = ProbeResult(TailnetVerdict.Unprobed, cached = false, ms = 0L)

/**
 * Tailscale 可用性探测器（**同步接口**，调用方必须在阻塞池上跑）。
 *
 * WHY 同步而非 `IO`：缓存与 single-flight 要在一个临界区里完成「查缓存 → 可能刷新 →
 * 落账」，用 `IO` 写就得在临界区里 `unsafeRunSync`（禁）。同步签名 + 调用点
 * `IO.blocking` 是唯一干净形态；约束（有界、零抛异常、不占 io-compute）由实现与调用点
 * 各自承担。
 */
trait TailnetProbe:

  /** 探测一次（可阻塞 ≤ 预算；**绝不抛异常**，异常一律映射成 `Unavailable`）。 */
  def probe: ProbeResult

object TailnetProbe:

  /** 正证据 TTL：Tailscale 起来/停掉是人工动作，15s 足够快、探测税足够低。 */
  val PositiveTtlMs: Long = 15_000L

  /**
   * 单次 `tailscale status --json` 的进程预算。
   *
   * WHY 800ms：实测本机（tailscale 停）一次 `status --json` 是毫秒级；800ms 只用来兜
   * 「tailscaled 卡住 / 进程起不来」——它必须显著小于命令路径上任何一个既有预算
   * （presence tailnet 单候选 3000ms、exec 探测 1200ms），否则「探测」本身就成了新税。
   */
  val ProcessBudgetMs: Long = 800L

  /** 未接入探测器（fail-open）。 */
  val Unprobed: TailnetProbe = new TailnetProbe:
    def probe: ProbeResult = ProbeResult.unprobed

  /** 判据注入缝（三态模拟 / spec）：恒返回一个固定判决，零成本。 */
  def stub(verdict: TailnetVerdict): TailnetProbe = new TailnetProbe:
    def probe: ProbeResult = ProbeResult(verdict, cached = false, ms = 0L)

  /**
   * 带缓存 + 负证据退避的包装（生产口径）。
   *
   * 三条不变量：
   *  - **正证据 TTL = [[PositiveTtlMs]]**；**负证据窗口复用
   *    [[P2pProbePolicy.negativeWindowMs]]**（60s 起、每次翻倍、封顶 15min）——与
   *    `P2pReachability` 同一条曲线，禁第二套退避。
   *  - **single-flight**：同一时刻只有一个调用者能 spawn 进程，其余在缓存里拿同一判决
   *    （`synchronized`；缓存命中路径的临界区是几次字段读，纳秒级）。
   *  - **时钟可注入**（`clock`）：spec 直测窗口到期而不用 sleep。
   */
  def cached(
    inner: TailnetProbe,
    clock: () => Long = () => System.currentTimeMillis(),
    positiveTtlMs: Long = PositiveTtlMs
  ): TailnetProbe = new CachedTailnetProbe(inner, clock, positiveTtlMs)

  /**
   * 真机探测器：按平台找可执行文件 → `status --json` → [[Parse.parse]]。
   *
   * 平台路径（作者裁定「只在平台支持时探测，不可用时快速返回」）：
   *  - macOS：`/Applications/Tailscale.app/Contents/MacOS/Tailscale`（GUI 包内 CLI，
   *    App Store / 官方 pkg 装法的唯一可执行体）→ `/usr/local/bin/tailscale`（brew / 直装）
   *    → `/opt/homebrew/bin/tailscale` → PATH 上的 `tailscale`；
   *  - Windows：`%ProgramFiles%\Tailscale\tailscale.exe` → `%ProgramFiles(x86)%\Tailscale\…`
   *    → PATH 上的 `tailscale.exe`；
   *  - Linux：`/usr/bin/tailscale` → `/usr/local/bin/tailscale` → PATH 上的 `tailscale`；
   *  - 其它平台：直接 `Unavailable(tailscale-not-installed)`（不 spawn、不拖时间）。
   */
  def system(budgetMs: Long = ProcessBudgetMs, env: Map[String, String] = sys.env): TailnetProbe =
    new SystemTailnetProbe(budgetMs, env)

  /** 本机平台标识（日志/原因细节用；`sys.props("os.name")` 在 Windows 是 `Windows 11`）。 */
  def platformLabel: String =
    val os = Option(System.getProperty("os.name")).getOrElse("").toLowerCase
    if os.contains("mac") then "macos"
    else if os.contains("win") then "windows"
    else if os.contains("nux") || os.contains("nix") then "linux"
    else "other"

  /**
   * 候选可执行文件（绝对路径优先，最后一项是裸名 = 交给 PATH 解析）。
   *
   * 纯函数（不吃真实文件系统）⇒ 平台路径表可直测；`exists` 由 [[resolveBinary]] 判定。
   */
  def binaryCandidates(platform: String = platformLabel, env: Map[String, String] = sys.env): List[String] =
    val programFiles = env.getOrElse("ProgramFiles", """C:\Program Files""")
    val programFilesX86 = env.getOrElse("ProgramFiles(x86)", """C:\Program Files (x86)""")
    platform match
      case "macos" =>
        List(
          "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
          "/usr/local/bin/tailscale",
          "/opt/homebrew/bin/tailscale",
          "tailscale"
        )
      case "windows" =>
        List(
          s"$programFiles\\Tailscale\\tailscale.exe",
          s"$programFilesX86\\Tailscale\\tailscale.exe",
          "tailscale.exe"
        )
      case "linux" =>
        List("/usr/bin/tailscale", "/usr/local/bin/tailscale", "tailscale")
      case _ => Nil

    end match

  end binaryCandidates

  /** 第一个存在的候选（没有 ⇒ None = 未安装；不抛异常）。 */
  def resolveBinary(
    candidates: List[String],
    exists: String => Boolean = p => Files.exists(Paths.get(p))
  ): Option[String] =
    // 裸名（无分隔符）是「交给 PATH 解析」的哨兵：不 stat，直接交出去（spawn 失败会被
    // SystemTailnetProbe 兜成 not-installed）。
    candidates.find(c => (!c.contains("/") && !c.contains("\\")) || exists(c))

end TailnetProbe

/**
 * `tailscale status --json` 的解析（**纯函数**，spec 直测；不碰进程、不碰网络）。
 *
 * 判决映射（实测报文形态见 evidence 落盘）：
 *  - `BackendState = "Running"` ⇒ Ready（`Self.TailscaleIPs` + 全部 online peer 的
 *    `TailscaleIPs`）；
 *  - `"Stopped"` / `"NoState"` / `"Starting"` / `"Stopping"` ⇒ `tailscale-stopped`
 *    （对上层是同一件事：现在拨不通 tailnet）；
 *  - `"NeedsLogin"` / `"NeedsMachineAuth"` ⇒ `tailscale-needs-login`；
 *  - 其它状态 ⇒ `tailscale-<状态下划线转小写>`；
 *  - JSON 解析不了 / 缺 BackendState ⇒ `probe-error`（stderr 明示「连不上 tailscaled」时
 *    归 `tailscale-stopped`——那正是「服务没在跑」的可执行形态）。
 */
object Parse:

  def parse(stdout: String, stderr: String = "", exitCode: Int = 0): TailnetVerdict =
    val out = Option(stdout).getOrElse("")
    val obj = parseJson(out).toOption.flatMap(_.asObject).map(_.toMap)
    obj match
      case Some(m) =>
        m.get("BackendState").flatMap(_.asString) match
          case Some("Running") =>
            val selfIps = stringSet(m.get("Self"))
            val peers = m.get("Peer").flatMap(_.asObject).map(o => normalizePeers(o.toMap)).getOrElse(Set.empty)
            if selfIps.isEmpty then
              TailnetVerdict.Unavailable(
                RouteReason.TailscaleNoAddress,
                "BackendState=Running, Self.TailscaleIPs empty"
              )
            else TailnetVerdict.Ready(selfIps, peers, "Running")
          case Some(state) => TailnetVerdict.Unavailable(codeForState(state), s"BackendState=$state")
          case None => TailnetVerdict.Unavailable(RouteReason.ProbeError, "no BackendState in JSON")
      case None =>
        val detail = firstLine(stderr).orElse(firstLine(out)).getOrElse(s"exit=$exitCode")
        val lowered = detail.toLowerCase
        val reason =
          if lowered.contains("failed to connect to local tailscaled") || lowered.contains("not running") then
            RouteReason.TailscaleStopped
          else if exitCode != 0 then RouteReason.TailscaleStopped
          else RouteReason.ProbeError
        TailnetVerdict.Unavailable(reason, detail)

    end match

  end parse

  /** `BackendState` → 原因码（两个「交互登录」态单列，其余归 stopped 家族）。 */
  def codeForState(state: String): String = state match
    case "Stopped" | "NoState" | "Starting" | "Stopping" => RouteReason.TailscaleStopped
    case "NeedsLogin" | "NeedsMachineAuth" => RouteReason.TailscaleNeedsLogin
    case other => s"tailscale-${other.toLowerCase}"

  /** 只取 `Online == true` 的 peer，并合并其 `TailscaleIPs`（v4/v6 都收）。 */
  private def normalizePeers(peers: Map[String, Json]): Set[String] =
    peers.values.flatMap { v =>
      val online = v.asObject.flatMap(o => o.toMap.get("Online")).flatMap(_.asBoolean).getOrElse(false)
      if online then stringSet(Some(v)) else Nil
    }.toSet

  /** 一个节点的 `TailscaleIPs`（数组）→ 字符串集合；缺失/空 ⇒ 空集。 */
  private def stringSet(node: Option[Json]): Set[String] =
    node.flatMap(_.asObject).flatMap(o => o.toMap.get("TailscaleIPs")).flatMap(_.asArray) match
      case Some(arr) => arr.flatMap(_.asString).map(_.trim).filter(_.nonEmpty).toSet
      case None => Set.empty

  private def firstLine(s: String): Option[String] =
    Option(s).map(_.trim).filter(_.nonEmpty).map(_.linesIterator.next().take(120))

end Parse

/** 缓存实现（正 TTL / 负退避 / single-flight）。线程安全：全部状态在 `lock` 的临界区里读写。 */
private final class CachedTailnetProbe(
  inner: TailnetProbe,
  clock: () => Long,
  positiveTtlMs: Long
) extends TailnetProbe:

  private val lock = new Object
  private var cachedAt: Long = Long.MinValue
  private var cachedVerdict: Option[TailnetVerdict] = None
  private var consecutiveFailures: Int = 0
  private val spawns = new AtomicLong(0L)
  private val hitCount = new AtomicLong(0L)

  def probe: ProbeResult =
    lock.synchronized {
      val now = clock()
      cachedVerdict match
        case Some(v) if now - cachedAt < ttlFor(v) =>
          hitCount.incrementAndGet()
          ProbeResult(v, cached = true, ms = 0L)
        case _ =>
          val t0 = System.nanoTime()
          val fresh = inner.probe
          val ms = (System.nanoTime() - t0) / 1000000L
          cachedVerdict = Some(fresh.verdict)
          cachedAt = clock()
          // 正/负证据的阶数只由**真探测**驱动：命中缓存的正判决不再刷失败计数（否则一次
          // 失败会被无限续期），命中缓存的负判决也不再加一阶（否则窗口无界增长）。
          consecutiveFailures = if TailnetVerdict.usable(fresh.verdict) then 0 else consecutiveFailures + 1
          spawns.incrementAndGet()
          ProbeResult(fresh.verdict, cached = false, ms = ms)
      end match
    }

  /** 读数面（报告/spec 取数）：`(真探测次数, 缓存命中次数)`。 */
  def stats: (Long, Long) = lock.synchronized((spawns.get(), hitCount.get()))

  private def ttlFor(v: TailnetVerdict): Long =
    if v == TailnetVerdict.Unprobed then 0L
    else if TailnetVerdict.usable(v) then positiveTtlMs
    else math.max(P2pProbePolicy.negativeWindowMs(consecutiveFailures), P2pProbePolicy.NegativeTtlBaseMs)

end CachedTailnetProbe

/**
 * 真机探测器（spawn `tailscale status --json`）。
 *
 * 边界（本批硬要求）：
 *  - **有界**：`waitFor(budgetMs)` 到点 `destroyForcibly`；stdout 由守护线程读，绝不让
 *    「管道写满」把读端与进程互相卡死。
 *  - **零异常外泄**：任何 `Throwable` ⇒ `Unavailable(probe-error)`。
 *  - **平台不支持 ⇒ 不 spawn**（[[TailnetProbe.binaryCandidates]] 为空 ⇒ not-installed）。
 */
private final class SystemTailnetProbe(budgetMs: Long, env: Map[String, String]) extends TailnetProbe:

  def probe: ProbeResult =
    val t0 = System.nanoTime()
    val verdict =
      try runOnce()
      catch
        case e: Throwable =>
          TailnetVerdict.Unavailable(
            RouteReason.ProbeError,
            s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
          )
    ProbeResult(verdict, cached = false, ms = (System.nanoTime() - t0) / 1000000L)

  private def runOnce(): TailnetVerdict =
    TailnetProbe.resolveBinary(TailnetProbe.binaryCandidates(TailnetProbe.platformLabel, env)) match
      case None =>
        TailnetVerdict.Unavailable(RouteReason.TailscaleNotInstalled, s"platform=${TailnetProbe.platformLabel}")
      case Some(bin) =>
        val pb = new ProcessBuilder(bin, "status", "--json")
        pb.redirectErrorStream(false)
        val started =
          try Some(pb.start())
          catch case _: java.io.IOException => None
        started match
          case None => TailnetVerdict.Unavailable(RouteReason.TailscaleNotInstalled, s"spawn failed: $bin")
          case Some(proc) =>
            try
              proc.getOutputStream.close()
              val out = new java.io.ByteArrayOutputStream()
              val err = new java.io.ByteArrayOutputStream()
              val outReader = drain(proc.getInputStream, out)
              val errReader = drain(proc.getErrorStream, err)
              val finished = proc.waitFor(math.max(1L, budgetMs), TimeUnit.MILLISECONDS)
              if !finished then
                proc.destroyForcibly()
                TailnetVerdict.Unavailable(RouteReason.ProbeTimeout, s"tailscale status exceeded ${budgetMs}ms")
              else
                // 进程已退出 ⇒ 读线程最多再等一个短窗口把剩余字节抽干（不 join 会读到半截 JSON）。
                outReader.join(200L)
                errReader.join(200L)
                Parse
                  .parse(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), proc.exitValue())
            finally if proc.isAlive then proc.destroyForcibly()

        end match

  /** 守护线程读一条流（避免进程输出大于管道缓冲时双向死锁）。 */
  private def drain(in: java.io.InputStream, sink: java.io.ByteArrayOutputStream): Thread =
    val t = new Thread(
      () =>
        try
          val buf = new Array[Byte](8192)
          var n = in.read(buf)
          while n >= 0 && sink.size() < (1 << 20) do
            sink.write(buf, 0, n)
            n = in.read(buf)
        catch case _: Throwable => (), "tailnet-probe-reader"
    )
    t.setDaemon(true)
    t.start()
    t

  end drain

end SystemTailnetProbe

/**
 * ② 档预留槽（自研 P2P）——**未实现的显式占位**。
 *
 * 全仓 `DatagramSocket|DatagramChannel` 命中 0 ⇒ 本档恒不可用，原因码
 * `p2p-not-implemented`。将来接入点（下一批 P2P 化的工作面，契约在此钉死）：
 *
 *   1. 实现 `SelfP2pSlot.Dialer`：`def reachable(peerId: String, candidates: List[String]): Boolean`
 *      —— 输入名册候选（供信令/反射候选用），输出「本拍能否建立 UDP 打洞直连」。
 *   2. 把 [[SelfP2pSlot.implemented]] 置 true，并在 [[DirectRoute.planSync]] 的 ② 档分支里
 *      用该 `Dialer` 产生**本档的候选**（届时 `RoutePlan.attempt` 的元素是「信号端点」，
 *      不再是 HTTP 候选）。
 *   3. 日志形态不变（`tiers=self-p2p:select(...)`），原因码从 `p2p-not-implemented`
 *      换成实测码。
 *
 * 🔴 在没有 ② 档实现之前，**不得**把非 tailnet 的既有直拨候选改标成 `self-p2p`：那会让
 * 「这一档还没实现」这个事实从日志里消失，正是本批要消灭的那类「用地址形状冒充能力」。
 */
object SelfP2pSlot:

  /** ② 档是否已实现（唯一开关：实现落地时才置 true）。 */
  val implemented: Boolean = false

  /** 将来 ② 档的探测接口（本批只钉契约、不给实现）。 */
  trait Dialer:
    /** 目标 peer 本拍能否建立 UDP 打洞直连。 */
    def reachable(peerId: String, candidates: List[String]): Boolean

/** 单档读数（`selected` + 原因码 + 该档探测耗时 + 可读细节）。 */
final case class TierTrace(tier: RouteTier, selected: Boolean, reason: String, detail: String, probeMs: Long):

  def render: String =
    val mark = if selected then "select" else "skip"
    val d = if detail.isEmpty then "" else s" $detail"
    s"${tier.label}:$mark($reason,${probeMs}ms$d)"

/**
 * 一次选路的收口（判据 + 读数 + 日志行）。
 *
 * `attempt` 是**本轮真该拨的候选**（已按档过滤、保序）；为空即「三档都不通 ⇒ 直接 relay」，
 * 调用方**一个字节都不该拨**（这正是「跳过而不是排第一再回落」的可执行形态）。
 */
final case class RoutePlan(
  attempt: List[String],
  dropped: List[String],
  via: String,
  traces: List[TierTrace],
  probeMs: Long,
  probeCached: Boolean
):

  /**
   * 未选**上层**档位的原因码（按档序）。
   *
   * relay 恒不在其中：它是兜底档，要么被选中（`via=relay`），要么只是后备（`fallback-only`）
   * ——「为什么没选 relay」不是一个有意义的问题，把它算进原因码只会污染日志。
   */
  def reasons: List[String] =
    traces.filter(t => !t.selected && t.tier != RouteTier.Relay).map(_.reason)

  /** 是否连一个候选都没有（⇒ 直接走 relay，零拨号）。 */
  def isEmpty: Boolean = attempt.isEmpty

  /**
   * 一行结构化选路读数（不含 `[route] device=` 前缀；供需要把选路嵌进既有行的地方复用，
   * 例如 presence 拨号行的 `dialRoundLog` —— 那里有「决策迁移才落 INFO」的行数纪律）。
   */
  def summary: String =
    val why = if reasons.isEmpty then "-" else reasons.mkString(",")
    val tierText = traces.map(_.render).mkString(" ")
    s"via=$via reasons=$why probeMs=${probeMs}ms cached=$probeCached " +
      s"candidates=${attempt.size + dropped.size} kept=${attempt.size} tiers=$tierText"

  /**
   * 一行结构化日志（本批可观测面的唯一形态）。
   *
   * 形态：`[route] device=<win> via=relay reasons=tailscale-stopped,p2p-not-implemented
   * probeMs=0ms cached=true candidates=1 kept=0 tiers=tailscale:skip(tailscale-stopped,0ms)
   * self-p2p:skip(p2p-not-implemented,0ms) relay:select(relay-always-available,0ms)`
   */
  def logLine(device: String): String = s"[route] device=$device $summary"

end RoutePlan

/** 选路判据点（**纯逻辑** + 一个注入的探测器；IO 只出现在 [[DirectRoute.plan]] 的阻塞包装里）。 */
object DirectRoute:

  private val logger = NebflowLogger.forName("nebflow.route")

  /** 进程级探测器（默认 = 未接入 ⇒ fail-open；生产在 GatewayMain 装真机探测器）。 */
  private val installedProbe = new AtomicReference[TailnetProbe](TailnetProbe.Unprobed)

  /** 当前探测器（每次调用现读 ⇒ 热装生效，无需重启）。 */
  def probe: TailnetProbe = installedProbe.get()

  /** 装入探测器（生产：[[installSystemProbe]]；spec：[[TailnetProbe.stub]]）。 */
  def install(p: TailnetProbe): Unit = installedProbe.set(p)

  /** 生产口径：真机探测器 + 缓存（正 TTL + 负退避 + single-flight）。 */
  def installSystemProbe(): Unit = install(TailnetProbe.cached(TailnetProbe.system()))

  /** 测试接缝：恢复 fail-open（默认态）。 */
  def resetForTests(): Unit = installedProbe.set(TailnetProbe.Unprobed)

  /** ② 档是否已实现（见 [[SelfP2pSlot]]）。 */
  def selfP2pImplemented: Boolean = SelfP2pSlot.implemented

  /** 一条候选是否是 tailnet 地址（v4 `100.64/10` 与 v6 ULA `fd7a:115c:a1e0::/48`）。 */
  def isTailnetEndpoint(endpoint: String): Boolean =
    val host = EndpointPreference.hostOf(endpoint)
    EndpointPreference.isTailscaleHost(host) || EndpointPreference.isTailnetV6(host)

  /** 一条候选兑现的传输标签：tailnet ⇒ `tailscale`，其它直拨 ⇒ `direct`。 */
  def viaOf(endpoint: String): String =
    if isTailnetEndpoint(endpoint) then RouteTier.Tailscale.label else "direct"

  /** 选路（IO 面）：**绝不在 io-compute 上 spawn 进程**（`IO.blocking`），**绝不抛异常**。 */
  def plan(
    device: String,
    candidates: List[String],
    probeOverride: Option[TailnetProbe] = None
  ): IO[RoutePlan] =
    IO.blocking(planSync(device, candidates, probeOverride.getOrElse(probe))).handleErrorWith { e =>
      // 选路判据自身出错 = 「探测不可用」，绝不能把整条命令路径炸掉。
      val fallback = planSync(
        device,
        candidates,
        TailnetProbe.stub(TailnetVerdict.Unavailable(RouteReason.ProbeError, e.getClass.getSimpleName))
      )
      IO.pure(fallback)
    }

  /** 选路（同步面，spec 直测）：同一条判据，只有探测器是可注入的。 */
  def planSync(
    device: String,
    candidates: List[String],
    probe: TailnetProbe = DirectRoute.probe
  ): RoutePlan =
    val usableCandidates = candidates.map(_.trim).filter(_.nonEmpty).distinct
    val tailnet = usableCandidates.filter(isTailnetEndpoint)
    val others = usableCandidates.filterNot(isTailnetEndpoint)
    val result = probe.probe
    val (keptTailnet, tailTrace) = gateTailnet(tailnet, result)
    val selfP2pTrace =
      if selfP2pImplemented then TierTrace(RouteTier.SelfP2p, selected = false, "self-p2p-ready", "", 0L)
      else TierTrace(RouteTier.SelfP2p, selected = false, RouteReason.P2pNotImplemented, "", 0L)
    val attempt = keptTailnet ++ others
    val via = attempt.headOption.map(viaOf).getOrElse(RouteTier.Relay.label)
    val relayTrace =
      if attempt.isEmpty then TierTrace(RouteTier.Relay, selected = true, RouteReason.RelayAlwaysAvailable, "", 0L)
      else TierTrace(RouteTier.Relay, selected = false, RouteReason.RelayAlwaysAvailable, "fallback-only", 0L)
    RoutePlan(
      attempt = attempt,
      dropped = tailnet.filterNot(keptTailnet.contains),
      via = via,
      traces = List(tailTrace, selfP2pTrace, relayTrace),
      probeMs = result.ms,
      probeCached = result.cached
    )

  end planSync

  /**
   * ① 档的门（唯一判据）：**测得可用才选**。
   *
   * 三种判决的行为（与原因码一一对应）：
   *  - `Unprobed`（未接入探测器）⇒ 原样保留 tailnet 候选（fail-open = 改前行为；原因码
   *    `tailscale-unprobed` 让「这次为什么没门控」在日志里可读）；
   *  - `Unavailable(code)` ⇒ **丢光 tailnet 候选**（一个都不拨，省下整份超时；原因码透传，
   *    如 `tailscale-stopped` / `probe-timeout`）；
   *  - `Ready(self, online, _)` ⇒ 只留「对端地址在本机 netmap 的 online 集合里」的候选；
   *    一条都不中 ⇒ `peer-offline`（服务在跑，但这个对端此刻不在 tailnet 上）。
   *
   * 非 tailnet 候选（LAN/公网直拨）**不受本门影响**：它们是既有生产行为，零回归。
   */
  private def gateTailnet(tailnet: List[String], result: ProbeResult): (List[String], TierTrace) =
    if tailnet.isEmpty then
      (Nil, TierTrace(RouteTier.Tailscale, selected = false, RouteReason.NoTailnetCandidate, "", result.ms))
    else
      result.verdict match
        case TailnetVerdict.Unprobed =>
          (tailnet, TierTrace(RouteTier.Tailscale, selected = true, RouteReason.Unprobed, "fail-open", result.ms))
        case TailnetVerdict.Unavailable(reason, detail) =>
          (Nil, TierTrace(RouteTier.Tailscale, selected = false, reason, detail, result.ms))
        case TailnetVerdict.Ready(selfAddresses, onlinePeers, backendState) =>
          val reachable = tailnet.filter { ep =>
            val host = EndpointPreference.hostOf(ep)
            onlinePeers.contains(host) || selfAddresses.contains(host)
          }
          if reachable.nonEmpty then
            (
              reachable,
              TierTrace(
                RouteTier.Tailscale,
                selected = true,
                RouteReason.TailscaleReady,
                s"backend=$backendState",
                result.ms
              )
            )
          else
            (
              Nil,
              TierTrace(
                RouteTier.Tailscale,
                selected = false,
                RouteReason.PeerOffline,
                s"backend=$backendState peers=${onlinePeers.size}",
                result.ms
              )
            )

          end if

  /** 落一行结构化选路日志（每次选路一次；失败不影响选路）。 */
  def logPlan(device: String, plan: RoutePlan): Unit =
    try logger.info(plan.logLine(device))
    catch case _: Throwable => ()
end DirectRoute
