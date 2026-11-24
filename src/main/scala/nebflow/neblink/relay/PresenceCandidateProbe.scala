package nebflow.neblink.relay

import nebflow.neblink.core.{EndpointPreference, NeblinkEndpoint}
import nebflow.shared.PeerInfo

/**
 * presence 候选端点面：**上报面卫生** + **候选集保真** + 拨号客户端的建连上界常数。
 *
 * 取证依据（本批唯一技术依据，均为只读实测）：
 *  - `evidence/presence-direct-261104/probe/probe.md`：唯一根因在**宿主侧**
 *    （本机 Tailscale `Stopped` ⇒ 对端 tailnet 被丢给运营商网关黑洞化），
 *    对端监听面 / 绑定面 / 实例形态 / tailnet IP 全部正常。
 *  - `evidence/presence-direct-261104/audit/{audit,fix-spec}.md`：根因落在宿主侧之后，
 *    **代码侧唯一能改善的是失败面韧性/收敛**——C-1（候选集被「观察式覆盖」塌缩成
 *    单地址）、C-3（上报面污染：链路本地/隧道网卡地址全量上报）、C-4（拨号客户端
 *    无 TCP 建连上界，预算到点后 SYN 继续在飞）。
 *
 * 本文件**纯逻辑**（除一个常数被调用方使用外零 IO、零后台任务、零全局可变状态）：
 * 不做可达性探测，不建 socket，不新增第二套负缓存——拨号本身即探测器（见
 * [[sanitizeReported]] 上方注记与 audit.md §Q3 的算术）。
 *
 * 改动面纪律（回滚 = 删本文件 + 撤回 4 处一行调用点，无残留状态）：
 *  - 不 import 并行批（76417e90）的任何新类型（`PresenceDialPool` / `PresenceDialSchedule`
 *    / `DialRoundOutcome`），否则合并时会连带编译失败；
 *  - 不碰 `GatewayMain.scala` / `PresenceDialPool.scala`（并行批持有）；
 *  - 不削弱 relay 兜底（`sendDataOrRelay` 的回落路径零触碰）。
 */
object PresenceCandidateProbe:

  /**
   * 上报候选条数上限 = 4。
   *
   * WHY 是 4：拨号腿单轮并发名额与单个 peer 单拍的候选遍历都以「一轮拨完」为目标
   * （`DialBudget.roundBudgetMs` = 12s < 同步拍 45s，按最贵档 3s 计 = 4 个候选）。
   * 超过 4 条的候选在本轮只会被截断到下一拍（`truncated` 留痕）——即「多上报」不
   * 增加成功率，只增加对端每个候选各付一份预算的探测税（audit.md C-3）。
   */
  val MaxReportedEndpoints: Int = 4

  /**
   * 单次拨号 TCP 建连上界 = 3000ms。
   *
   * WHY 是 3000（不是更短）：它等于本仓**最贵档**的单候选预算
   * （`DialAddressClass.Tailnet.budgetMs = 3000`，见 `NeblinkPresenceService.scala` 的
   * enum 注释：tailnet 相对实测 RTT 555ms 约 5.4×RTT）。取最贵档 ⇒ 任何一类候选的
   * 合法 TCP 建连都不会被这层提前掐断；它的作用只是给「黑洞地址」一个**资源上界**
   * ——改前预算到点只是调用方不再等（`future.get(budgetMs)`），底层 TCP/SYN 与
   * HttpClient 自带的 selector 线程继续在飞（audit.md C-4）。
   *
   * 取值与 `DialAddressClass.Tailnet.budgetMs` 同源；此处用字面量而非跨类引用，
   * 是为了让本文件不依赖被并行批（76417e90）改写的拨号结构文件（见本文件头注记）。
   */
  val MaxConnectTimeoutMs: Long = 3_000L

  /**
   * 一条自报地址是否**不该上报**（纯函数，spec 直测）。
   *
   * 只判「结构性不可达」的地址，不做可达性判断（那是拨号的活）：
   *  - 空串 / 空白；
   *  - `0.0.0.0`（未指定，对端永远拨不到）；
   *  - `169.254.0.0/16`（APIPA / 链路本地：网线半通或 DHCP 未成，跨链路零价值，
   *    是本批取证里「每拍白付一份 Lan 预算」的主要污染源）；
   *  - `127.0.0.0/8`（回环：对端拨的是它自己）；
   *  - `224.0.0.0/4` 组播与 `255.255.255.255` 广播（建连无意义）。
   *
   * 🔴 `100.64.0.0/10`（Tailscale / CGNAT）**不得**判为不可上报——它是本仓首选的
   * 直连面；取证里出问题的是宿主侧 tailnet 掉线，不是这个地址段本身。
   * 非 IPv4 字面量（主机名等）一律放行：本判据不负责 DNS 语义。
   */
  def isUnreportableAddress(address: String): Boolean =
    val trimmed = Option(address).map(_.trim).getOrElse("")
    if trimmed.isEmpty then true
    else if EndpointPreference.isIpv6Literal(trimmed) then isUnreportableV6(trimmed)
    else
      octets(trimmed) match
        case None => false // 非 IPv4 字面量：不在此判据范围内
        case Some(o) =>
          val a = o(0)
          val b = o(1)
          (a == 0 && b == 0 && o(2) == 0 && o(3) == 0) || // 0.0.0.0
          (a == 169 && b == 254) || // 链路本地 APIPA
          (a == 127) || // 回环
          (a >= 224 && a <= 239) || // 组播
          (a == 255 && b == 255 && o(2) == 255 && o(3) == 255) // 广播
  end isUnreportableAddress

  /**
   * IPv6 侧的「结构性不可达」（IPv6 优先批 2026-11-04）——与 v4 判据**同一语义**：
   *  - `::`（未指定）与 `::1`（回环）：对端拨的是它自己 / 拨不到；
   *  - `fe80::/10`（链路本地）：跨链路零价值，是 v6 版 APIPA（本批 v4 头号污染源的对应物）；
   *  - `ff00::/8`（组播）：建连无意义。
   *
   * 🔴 全局单播（`2000::/3`）与 ULA（`fc00::/7`，含 Tailscale `fd7a:115c:a1e0::/48`）
   * **必须放行**：v6 优先改造的目的就是让它们进候选面；tailnet ULA 与 `100.64/10` 同权。
   * scope id（`fe80::1%en0`）先剥离再判——带 scope 的链路本地照样是链路本地。
   */
  private def isUnreportableV6(host: String): Boolean =
    val h = host.stripPrefix("[").stripSuffix("]").toLowerCase
    val noScope = h.indexOf('%') match
      case -1 => h
      case i => h.substring(0, i)
    if noScope == "::" || noScope == "::1" || noScope == "0:0:0:0:0:0:0:0" || noScope == "0:0:0:0:0:0:0:1" then true
    else
      firstHextet(noScope) match
        case None => false
        case Some(h0) =>
          (h0 >= 0xfe80 && h0 <= 0xfebf) || // 链路本地 fe80::/10
          (h0 >= 0xff00 && h0 <= 0xffff) // 组播 ff00::/8

  /** v6 字面量的首个 hextet（压缩形态以 `::` 开头 ⇒ 0）；非法 ⇒ None（放行，不在此判据内）。 */
  private def firstHextet(h: String): Option[Int] =
    if h.startsWith(":") then Some(0)
    else
      val head = h.takeWhile(_ != ':')
      if head.isEmpty then Some(0) else scala.util.Try(Integer.parseInt(head, 16)).toOption

  /**
   * IPv6 优先排序（IPv6 优先批 2026-11-04）——纯函数，spec 直测。
   *
   * 规则（三条，缺一不可）：
   *  1. **v6 在前**：双栈主机把 v6 候选先报给对端（对端可能只有 v6 可达）；
   *  2. **v4 必留槽位**：v6 最多占 [[MaxV6Slots]] 条，其余 v6 排到 v4 之后——否则一台有
   *     4+ 条 v6（运营商给每台机器一堆）的机器会把唯一那条可用 v4 挤出
   *     [[MaxReportedEndpoints]] 的 4 条上限，对端就只剩拨不动的 v6；
   *  3. **无 v6 ⇒ 原样返回**（逐字节相同）：无 v6 环境下行为零变化。
   *
   * 只重排、不删：条数上限仍由 [[sanitizeReported]] 一处封顶。
   */
  def preferV6(endpoints: List[NeblinkEndpoint], maxV6Slots: Int = MaxV6Slots): List[NeblinkEndpoint] =
    val (v6, v4) = endpoints.partition(ep => EndpointPreference.isIpv6Literal(ep.address))
    if v6.isEmpty then endpoints
    else
      val cap = math.max(1, maxV6Slots)
      val (headV6, tailV6) = v6.splitAt(cap)
      headV6 ++ v4 ++ tailV6

  /** v4 之前的 v6 名额上限（见 [[preferV6]] 规则 2）。 */
  val MaxV6Slots: Int = 2

  /**
   * 网卡名是否「已知隧道/虚拟适配器」——旧 marker 表（`NeblinkClient.isLikelyVirtualNic:
   * virtual / vethernet / vmware / docker / wsl / hyper-v / virtualbox`）漏掉的那批。
   *
   * 补的是取证点名的漏网者：TAP-Windows / tun / utun / ZeroTier / Hamachi / Npcap /
   * 各类 VPN / WireGuard 适配器——它们的名字里没有任何旧 marker，于是一律进候选面。
   *
   * ⚠️ 刻意**不含** `tailscale`：Windows 上 tailscale 是真实 NIC，其 `100.64/10`
   * 地址是首选直连面；且 [[sanitizeReported]] 对 tailnet 地址有例外分支（见下），
   * 加不加都不该影响它——不加是为了不引入第二个判据来源。
   */
  def isTunnelNicLabel(label: String): Boolean =
    val l = Option(label).getOrElse("").toLowerCase
    l.nonEmpty && TunnelNicMarkers.exists(l.contains)

  private val TunnelNicMarkers: List[String] =
    List("tap", "tun", "utun", "zerotier", "hamachi", "npcap", "vpn", "wireguard", "wintun")

  /**
   * ① 上报面卫生（修 C-3）：丢不可路由地址 + 丢隧道网卡的非 tailnet 地址 + 去重 + 封顶。
   *
   * 三条不变量：
   *  - **职责边界**：过滤（丢不可路由 / 丢隧道网卡的非 tailnet 地址）、去重、封顶**由本函数负责**；
   *    **排序不由本函数负责**——由调用点前置的 [[preferV6]]（v6 前置 + v4 必留槽位）与
   *    `EndpointPreference.rank`（tailnet → 同 /24 → 其余）共同决定；本函数在已排好的序列上只删不重排。
   *  - **不误杀首选项**：`100.64/10` 地址即使挂在 `utun`（macOS tailscale 的真实形态）
   *    也照样保留。
   *  - **永不返回空**：过滤后为空 ⇒ 原样返回入参（沿用 `NeblinkClient.detectLocalEndpoints`
   *    的 "never lose all endpoints" 不变量，宁可上报一条污染地址也不让对端无候选）。
   *
   * 去重键 = `(address, port)`：同机多实例（不同端口）是合法候选，不能被合并。
   */
  def sanitizeReported(
    endpoints: List[NeblinkEndpoint],
    max: Int = MaxReportedEndpoints
  ): List[NeblinkEndpoint] =
    if endpoints.isEmpty then endpoints
    else
      val reportable = endpoints.filterNot(ep => isUnreportableAddress(ep.address))
      val noTunnels =
        reportable.filterNot(ep =>
          isTunnelNicLabel(ep.label) &&
            !EndpointPreference.isTailscaleHost(ep.address) && !EndpointPreference.isTailnetV6(ep.address)
        )
      // 隧道网卡被全部丢掉时退回上一档（仍比「什么都不报」强）。
      val kept = if noTunnels.nonEmpty then noTunnels else reportable
      val deduped = kept.foldLeft(List.empty[NeblinkEndpoint]) { (acc, ep) =>
        if acc.exists(e => e.address == ep.address && e.port == ep.port) then acc else acc :+ ep
      }
      val capped = deduped.take(math.max(1, max))
      if capped.nonEmpty then capped else endpoints

  /**
   * ② 候选集保真（修 C-1）：入站观察到的记录（`endpoints = Nil`）不得抹掉名册给的
   * 全量候选集。
   *
   * 缺陷形态（改前，`audit.md` §Q1 四生产者表）：4 个 `PeerInfo` 生产者里 3 个（入站
   * presence WS / announce 推送 / 入站 handshake）写 `endpoints = Nil` +
   * `address = http://<观察到的对端源 IP>:<port>`，且全部**整记录替换** ⇒ 名册给的 N 条
   * 候选最多活到下一条入站心跳（≤1 拍 45s）。于是拨号腿只剩「对端眼里的我的源地址」
   * 一条候选——本批事故里正是这一条（`100.65.49.141`，运营商 CGNAT 段，对端结构性
   * 不可达）⇒ 直连恒 0%。
   *
   * 合并规则（只增不减）：
   *  - `observed.endpoints` 非空 ⇒ **原样返回**（名册腿零行为变化，名册每拍都会把
   *    全量候选集重新写回）；
   *  - 否则 `endpoints = (observed.address :: prev.endpoints).distinct.take(max)`，
   *    且 `address == endpoints.head` 恒成立（「此刻观察到的活地址」永远排第一）。
   *
   * 纯函数、无锁：只在调用方的 `peersRef.modify` / `update` 闭包里跑，不在外面读一次再写。
   */
  def mergeObserved(prev: Option[PeerInfo], observed: PeerInfo, max: Int = MaxReportedEndpoints): PeerInfo =
    if observed.endpoints.nonEmpty then observed
    else
      val head = Option(observed.address).map(_.trim).filter(_.nonEmpty).toList
      val inherited = prev.map(_.endpoints).getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
      val merged = (head ++ inherited).distinct.take(math.max(1, max))
      if merged.isEmpty then observed
      else observed.copy(address = merged.head, endpoints = merged)

  /** `a.b.c.d` ⇒ 四元组；非 IPv4 字面量 ⇒ `None`。 */
  private def octets(host: String): Option[Vector[Int]] =
    val parts = host.split('.')
    if parts.length != 4 then None
    else
      val nums = parts.toVector.map(_.toIntOption.filter(n => n >= 0 && n <= 255))
      if nums.forall(_.isDefined) then Some(nums.map(_.get)) else None
end PresenceCandidateProbe
