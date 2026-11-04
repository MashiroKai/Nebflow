package nebflow.core.tools

import cats.effect.IO
import munit.CatsEffectSuite

import java.util.concurrent.atomic.AtomicLong

/**
 * 三档选路的判据钉子（作者裁定 2026-11-04 16:51：「Tailscale 直连 → 自研 P2P → relay」）。
 *
 * 覆盖四件事，全部二值可判、零真机网络、零进程：
 *   ① **三态模拟**（A 服务停 / B 起且 peer 在线 / C 起但 peer 不在线）——档位选择与原因码；
 *   ② **不可用档位是跳过**（`plan.attempt` 为空 ⇒ 一个字节都不拨），不是「排第一再回落」；
 *   ③ **不重复收税**（缓存：连续 N 次选路只探测 1 次；负证据退避；到点才重探）；
 *   ④ **fail-open**（未接入探测器 ⇒ 与改前逐字节相同）与「探测异常绝不炸命令路径」。
 *
 * 「改前行为」的等价臂就是 ④：`TailnetProbe.Unprobed` = 不门控 = 只看地址形状
 * （`EndpointPreference.rank` 的 rank 0 硬编码），那正是本批要换掉的东西。
 */
class DirectRouteSpec extends CatsEffectSuite:

  // ===== 夹具：`tailscale status --json` 的真实形态（字段名对齐本机实测报文）=====

  private val stoppedJson: String =
    """{
      |  "Version": "1.104.2-tc3430f0d2-g598b63867",
      |  "TUN": true,
      |  "BackendState": "Stopped",
      |  "HaveNodeKey": true,
      |  "AuthURL": "https://login.tailscale.com/a/96495a2e2a61",
      |  "TailscaleIPs": null,
      |  "Self": {"ID": "", "HostName": "Mashiro's MacBook Pro", "TailscaleIPs": null, "Online": false},
      |  "Peer": {}
      |}""".stripMargin

  private def runningJson(peerOnline: Boolean): String =
    val peerIp = "100.65.49.141"
    s"""{
       |  "Version": "1.104.2",
       |  "BackendState": "Running",
       |  "TailscaleIPs": ["100.127.149.106", "fd7a:115c:a1e0::1"],
       |  "Self": {
       |    "ID": "nSelf",
       |    "HostName": "host-a",
       |    "DNSName": "host-a.tail1234.ts.net.",
       |    "Online": true,
       |    "TailscaleIPs": ["100.127.149.106", "fd7a:115c:a1e0::1"]
       |  },
       |  "Peer": {
       |    "nodekey:aaaa": {
       |      "HostName": "kai",
       |      "DNSName": "kai.tail1234.ts.net.",
       |      "Online": $peerOnline,
       |      "TailscaleIPs": ["$peerIp", "fd7a:115c:a1e0::2"]
       |    }
       |  }
       |}""".stripMargin

  private val needsLoginJson: String = """{"BackendState": "NeedsLogin", "TailscaleIPs": null}"""

  /** 三态的探测器（注入面：spec 不依赖真机网络、不 spawn 进程）。 */
  private def probeOf(verdict: TailnetVerdict): TailnetProbe = TailnetProbe.stub(verdict)

  private val tailnetCandidate = "http://100.65.49.141:6138"
  private val lanCandidate = "http://192.0.2.145:6138"

  // ===== ① 三态模拟（红→绿判据的核心）=====

  test("态 A（tailscale 停）⇒ 选 relay，原因码 tailscale-stopped + p2p-not-implemented，零候选零拨号") {
    val plan = DirectRoute.planSync("KAI", List(tailnetCandidate), probeOf(Parse.parse(stoppedJson)))
    assertEquals(plan.via, "relay", "① 档不可用 ⇒ 落到 ③ relay")
    assert(plan.isEmpty, "① 档被整档跳过 ⇒ 本拍没有任何该拨的候选（不是「拨了再回落」）")
    assertEquals(plan.reasons, List(RouteReason.TailscaleStopped, RouteReason.P2pNotImplemented))
    assertEquals(plan.dropped, List(tailnetCandidate))
    val line = plan.logLine("KAI")
    assert(line.contains("via=relay"), line)
    assert(line.contains("tailscale-stopped"), "原因码必须能在**一行日志**里读出来")
    assert(line.contains("p2p-not-implemented"), line)
    assert(line.contains("tailscale:skip(tailscale-stopped"), line)
    assert(line.contains("relay:select("), line)
  }

  test("态 B（tailscale 起且 peer 可达）⇒ 选 ① 档，日志 via=tailscale") {
    val plan = DirectRoute.planSync("KAI", List(tailnetCandidate), probeOf(Parse.parse(runningJson(true))))
    assertEquals(plan.via, "tailscale")
    assertEquals(plan.attempt, List(tailnetCandidate))
    assertEquals(plan.reasons, List(RouteReason.P2pNotImplemented), "只有 ② 档「未实现」这一条上档原因")
    val line = plan.logLine("KAI")
    assert(line.contains("via=tailscale"), line)
    assert(line.contains("tailscale:select(tailscale-ready"), line)
  }

  test("态 C（tailscale 起但 peer 不可达）⇒ 跳过 ①，落 ③ relay，原因码 peer-offline") {
    val plan = DirectRoute.planSync("KAI", List(tailnetCandidate), probeOf(Parse.parse(runningJson(false))))
    assertEquals(plan.via, "relay")
    assert(plan.isEmpty)
    assertEquals(plan.reasons, List(RouteReason.PeerOffline, RouteReason.P2pNotImplemented))
    assert(plan.logLine("KAI").contains("tailscale:skip(peer-offline"), plan.logLine("KAI"))
  }

  test("态 C′（服务在跑但本机没有 tailnet 地址）⇒ 原因码 no-tailnet-address") {
    val j = """{"BackendState":"Running","Self":{"TailscaleIPs":null},"Peer":{}}"""
    val plan = DirectRoute.planSync("KAI", List(tailnetCandidate), probeOf(Parse.parse(j)))
    assertEquals(plan.via, "relay")
    assertEquals(plan.reasons, List(RouteReason.TailscaleNoAddress, RouteReason.P2pNotImplemented))
  }

  test("改前等价臂（Unprobed = 不门控）⇒ tailnet 候选照旧排第一，且日志标明未门控") {
    val plan = DirectRoute.planSync("KAI", List(tailnetCandidate), TailnetProbe.Unprobed)
    assertEquals(plan.via, "tailscale", "无探测器 ⇒ 与改前一致：地址像 tailnet 就选")
    assertEquals(plan.attempt, List(tailnetCandidate))
    assert(plan.logLine("KAI").contains(RouteReason.Unprobed))
  }

  // ===== ② 档位不混装：非 tailnet 直拨零回归 =====

  test("非 tailnet 候选不受 ① 档的门影响（LAN 直拨保留 ⇒ via=direct）") {
    val a = DirectRoute.planSync("KAI", List(tailnetCandidate, lanCandidate), probeOf(Parse.parse(stoppedJson)))
    assertEquals(a.attempt, List(lanCandidate), "tailnet 被丢、LAN 保留")
    assertEquals(a.via, "direct")
    // 无 tailnet 候选时 ① 档给的是「不适用」而不是「不可用」。
    val b = DirectRoute.planSync("KAI", List(lanCandidate), probeOf(Parse.parse(stoppedJson)))
    assertEquals(b.attempt, List(lanCandidate))
    assertEquals(b.reasons, List(RouteReason.NoTailnetCandidate, RouteReason.P2pNotImplemented))
  }

  test("② 档是显式占位：恒不可用、原因码 p2p-not-implemented，且不冒充既有直拨") {
    assert(!SelfP2pSlot.implemented, "② 档未实现 ⇒ 日志必须说 p2p-not-implemented")
    val plan = DirectRoute.planSync("KAI", List(lanCandidate), probeOf(Parse.parse(runningJson(true))))
    assert(plan.via != RouteTier.SelfP2p.label, "② 档绝不能成为 via 的取值")
    assertEquals(plan.traces.find(_.tier == RouteTier.SelfP2p).map(_.reason), Some(RouteReason.P2pNotImplemented))
  }

  test("viaOf：tailnet v4/v6 ⇒ tailscale，其余直拨 ⇒ direct") {
    assertEquals(DirectRoute.viaOf("http://100.65.49.141:6138"), "tailscale")
    assertEquals(DirectRoute.viaOf("http://[fd7a:115c:a1e0::2]:6138"), "tailscale")
    assertEquals(DirectRoute.viaOf("http://192.0.2.145:6138"), "direct")
    assertEquals(DirectRoute.viaOf("http://[2001:db8::1]:6138"), "direct")
  }

  // ===== ③ 不重复收税 =====

  test("缓存：连续 10 次选路只探测 1 次（正 TTL 内命中）") {
    val calls = new AtomicLong(0L)
    val inner = new TailnetProbe:
      def probe: ProbeResult =
        calls.incrementAndGet()
        ProbeResult(TailnetVerdict.Unavailable(RouteReason.TailscaleStopped), cached = false, ms = 3L)
    var now = 1_000L
    val cached = TailnetProbe.cached(inner, () => now).asInstanceOf[CachedTailnetProbe]
    val plans = (1 to 10).map(_ => DirectRoute.planSync("KAI", List(tailnetCandidate), cached))
    assertEquals(calls.get(), 1L, "10 次选路 = 1 次真探测（其余命中缓存）")
    assert(plans.forall(_.via == "relay"))
    assert(plans.forall(_.reasons.contains(RouteReason.TailscaleStopped)), "命中缓存也必须带着同一个原因码")
    assert(plans.tail.forall(_.probeCached), "除第一次外全部是缓存命中（ms ≈ 0）")
    assertEquals(plans.tail.map(_.probeMs).distinct.toList, List(0L))
    assertEquals(cached.stats, (1L, 9L))
    // 负证据窗口（首次失败 = 60s）到期 ⇒ 重探一次。
    now += 61_000L
    DirectRoute.planSync("KAI", List(tailnetCandidate), cached)
    assertEquals(calls.get(), 2L, "窗口到期 ⇒ 只补一次半开探测")
  }

  test("缓存：负证据退避——连续失败窗口翻倍（与 P2pReachability 同一条曲线）") {
    val calls = new AtomicLong(0L)
    val inner = new TailnetProbe:
      def probe: ProbeResult =
        calls.incrementAndGet()
        ProbeResult(TailnetVerdict.Unavailable(RouteReason.TailscaleStopped), cached = false, ms = 1L)
    var now = 0L
    val cached = TailnetProbe.cached(inner, () => now)
    def route(): RoutePlan = DirectRoute.planSync("KAI", List(tailnetCandidate), cached)
    // ① t=0 首次失败 ⇒ 窗口 60s
    route()
    assertEquals(calls.get(), 1L)
    // ② t=30s 仍在窗口内 ⇒ 零探测
    now = 30_000L
    route()
    assertEquals(calls.get(), 1L, "窗口内不得重探 —— 这就是「不重复收税」的形态")
    // ③ t=61s 窗口到期 ⇒ 第 2 次失败，窗口 120s
    now = 61_000L
    route()
    assertEquals(calls.get(), 2L)
    // ④ t=150s（距上次 89s < 120s）⇒ 零探测
    now = 150_000L
    route()
    assertEquals(calls.get(), 2L, "第 2 阶窗口覆盖 120s")
    // ⑤ t=190s（距上次 129s > 120s）⇒ 第 3 次失败，窗口 240s
    now = 190_000L
    route()
    assertEquals(calls.get(), 3L)
    // ⑥ t=420s（距上次 230s < 240s）⇒ 零探测
    now = 420_000L
    route()
    assertEquals(calls.get(), 3L, "第 3 阶窗口覆盖 240s")
    // ⑦ t=440s（距上次 250s > 240s）⇒ 半开探测一次
    now = 440_000L
    route()
    assertEquals(calls.get(), 4L)
  }

  test("缓存：正证据 TTL 到期后重探（服务被停掉必须在一个 TTL 内被发现）") {
    val calls = new AtomicLong(0L)
    val verdicts = Iterator(
      Parse.parse(runningJson(true)),
      Parse.parse(stoppedJson),
      Parse.parse(stoppedJson)
    )
    val inner = new TailnetProbe:
      def probe: ProbeResult =
        calls.incrementAndGet()
        ProbeResult(verdicts.next(), cached = false, ms = 2L)
    var now = 0L
    val cached = TailnetProbe.cached(inner, () => now)
    assertEquals(DirectRoute.planSync("KAI", List(tailnetCandidate), cached).via, "tailscale")
    assertEquals(calls.get(), 1L)
    now += TailnetProbe.PositiveTtlMs - 1
    assertEquals(DirectRoute.planSync("KAI", List(tailnetCandidate), cached).via, "tailscale", "TTL 内命中缓存")
    assertEquals(calls.get(), 1L)
    now += 2L
    assertEquals(DirectRoute.planSync("KAI", List(tailnetCandidate), cached).via, "relay", "TTL 到期 ⇒ 重探拿到新判决")
    assertEquals(calls.get(), 2L)
    assertEquals(DirectRoute.planSync("KAI", List(tailnetCandidate), cached).via, "relay", "负证据窗口内零探测")
    assertEquals(calls.get(), 2L)
  }

  // ===== ④ 探测异常绝不炸命令路径 =====

  test("探测器抛异常 ⇒ IO 面兜成 probe-error（不抛、不阻塞），档位按不可用处理") {
    val boom = new TailnetProbe:
      def probe: ProbeResult = throw new IllegalStateException("tailscaled exploded")
    DirectRoute
      .plan("KAI", List(tailnetCandidate), Some(boom))
      .map { plan =>
        assertEquals(plan.via, "relay")
        assertEquals(plan.reasons.head, RouteReason.ProbeError)
        assert(plan.logLine("KAI").contains("probe-error"))
      }
  }

  test("探测超预算 ⇒ 原因码 probe-timeout（有界，不拖住命令路径）") {
    val slow = new TailnetProbe:
      def probe: ProbeResult = ProbeResult(TailnetVerdict.Unavailable(RouteReason.ProbeTimeout, "801ms"), false, 801L)
    val plan = DirectRoute.planSync("KAI", List(tailnetCandidate), slow)
    assertEquals(plan.reasons.head, RouteReason.ProbeTimeout)
    assertEquals(plan.probeMs, 801L, "探测耗时分档进读数")
  }

  test("空候选 ⇒ relay + no-candidate-endpoint 之外不炸（退化输入的显式形态）") {
    val plan = DirectRoute.planSync("KAI", Nil, probeOf(Parse.parse(stoppedJson)))
    assertEquals(plan.via, "relay")
    assert(plan.isEmpty)
    assert(plan.reasons.contains(RouteReason.NoTailnetCandidate))
  }

  // ===== 解析面（纯函数，真机报文形态）=====

  test("Parse：Stopped/NoState/Starting/Stopping ⇒ tailscale-stopped") {
    List("Stopped", "NoState", "Starting", "Stopping").foreach { s =>
      val v = Parse.parse(s"""{"BackendState":"$s"}""")
      assertEquals(TailnetVerdict.reasonOf(v), Some(RouteReason.TailscaleStopped), s)
    }
  }

  test("Parse：NeedsLogin/NeedsMachineAuth ⇒ tailscale-needs-login（不是「停」）") {
    assertEquals(
      TailnetVerdict.reasonOf(Parse.parse(needsLoginJson)),
      Some(RouteReason.TailscaleNeedsLogin)
    )
    assertEquals(
      TailnetVerdict.reasonOf(Parse.parse("""{"BackendState":"NeedsMachineAuth"}""")),
      Some(RouteReason.TailscaleNeedsLogin)
    )
  }

  test("Parse：Running ⇒ Ready（本机地址 + 只收 Online 的 peer 地址，v4/v6 都收）") {
    Parse.parse(runningJson(true)) match
      case TailnetVerdict.Ready(self, peers, state) =>
        assertEquals(state, "Running")
        assertEquals(self, Set("100.127.149.106", "fd7a:115c:a1e0::1"))
        assertEquals(peers, Set("100.65.49.141", "fd7a:115c:a1e0::2"))
      case other => fail(s"expected Ready, got $other")
    Parse.parse(runningJson(false)) match
      case TailnetVerdict.Ready(_, peers, _) => assertEquals(peers, Set.empty[String], "Online=false 不算可达")
      case other                             => fail(s"expected Ready, got $other")
  }

  test("Parse：非 JSON（tailscaled 没跑）⇒ tailscale-stopped；空输出 ⇒ probe-error") {
    assertEquals(
      TailnetVerdict.reasonOf(Parse.parse("", "failed to connect to local tailscaled; it doesn't appear to be running", 1)),
      Some(RouteReason.TailscaleStopped)
    )
    assertEquals(TailnetVerdict.reasonOf(Parse.parse("", "", 0)), Some(RouteReason.ProbeError))
    assertEquals(TailnetVerdict.reasonOf(Parse.parse("not json at all")), Some(RouteReason.ProbeError))
  }

  // ===== 平台可执行路径表（不可用时快速返回「未安装」）=====

  test("binaryCandidates：三平台各有路径表，其它平台为空（⇒ 不 spawn，快速 not-installed）") {
    val mac = TailnetProbe.binaryCandidates("macos", Map.empty)
    assert(mac.head.contains("Tailscale.app"), mac.head)
    assert(mac.last == "tailscale", "最后一项是 PATH 哨兵")
    val win = TailnetProbe.binaryCandidates("windows", Map("ProgramFiles" -> """D:\Apps"""))
    assert(win.exists(_.toLowerCase.endsWith("tailscale.exe")), win)
    assert(win.head.startsWith("D:\\Apps"), win.head)
    assert(TailnetProbe.binaryCandidates("linux", Map.empty).contains("/usr/bin/tailscale"))
    assertEquals(TailnetProbe.binaryCandidates("plan9", Map.empty), Nil)
  }

  test("resolveBinary：不存在的绝对路径被跳过；裸名交 PATH；全不存在 ⇒ None") {
    assertEquals(
      TailnetProbe.resolveBinary(List("/nope/a", "/nope/b"), _ => false),
      None
    )
    assertEquals(
      TailnetProbe.resolveBinary(List("/nope/a", "/yes/b"), _ => true),
      Some("/nope/a"),
      "第一个「存在」的候选优先"
    )
    assertEquals(TailnetProbe.resolveBinary(List("tailscale"), _ => false), Some("tailscale"))
  }

  test("真机探测器：本机可执行体存在时，判决在预算内返回且绝不抛异常（不依赖网络状态）") {
    val t0 = System.nanoTime()
    val r = TailnetProbe.system().probe
    val ms = (System.nanoTime() - t0) / 1000000L
    assert(r.verdict != null)
    assert(ms < 5_000L, s"探测必须有界（实测 ${ms}ms，预算 ${TailnetProbe.ProcessBudgetMs}ms）")
  }
