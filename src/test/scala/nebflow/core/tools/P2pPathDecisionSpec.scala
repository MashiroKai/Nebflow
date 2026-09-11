package nebflow.core.tools

import munit.CatsEffectSuite
import nebflow.neblink.PeerInfo

import scala.concurrent.duration.*

/**
 * A + C4 决策面的纯函数回归网（2026-09-11 P2P 直连修复批，方案 §2 选项 A / C4）。
 *
 * WHY 纯函数:方案给这两项的验收口径是**行为断言**——
 *   · A：`directOnline=false` 且无负证据 ⇒ 尝试 P2P；有负证据（relay 记忆 / 近期失败）⇒ relay；
 *   · C4：地址变化 ⇒ 记忆失效；TTL 过期 ⇒ 重探。
 * 改前这些规则埋在 IO + `@volatile` 里，只能靠驱动真实网络来观察。抽成
 * [[P2pPathDecision]] 后逐条二值可判（本 spec 就是它们的钉子），而不必在报告里口述。
 *
 * 注意 A 的关键语义：`directOnline` **根本不是** [[P2pPathDecision.shouldSkipP2pForRelay]]
 * 的入参——它被降级成 [[P2pPathDecision.probeBudget]] 的提示。第 1 条用例就是把这个
 * 「缺席」本身钉住：没有负证据时无论 presence 连没连，都不旁路 P2P。
 */
class P2pPathDecisionSpec extends CatsEffectSuite:

  private def peer(addr: String, endpoints: List[String] = Nil): PeerInfo =
    PeerInfo(deviceId = "dev-1", deviceName = "KAI", platform = "macos", address = addr, endpoints = endpoints)

  private val relayMemory = P2pPathDecision.PathMemory("relay", 1_000L, "k")

  // ===== A：负证据驱动旁路（presence 未连 ≠ 不可达）=====

  test("A: 无负证据 ⇒ 不旁路 P2P（directOnline 不参与决策；改前这里恒为 true ⇒ 9/9 relay）") {
    // 改前公式是 `memory.contains("relay") || !directOnline`：第二项让
    // 「presence 未连」直接等价于「P2P 不可达」。新公式只认负证据 ⇒ 此处置 false。
    assertEquals(
      P2pPathDecision.shouldSkipP2pForRelay(memoryMethod = None, recentP2pFailure = false, relayAvailable = true),
      false
    )
    // 显式对照：`directOnline` 已不是入参（编译期即证）——它就是「降级为预算提示」的
    // 机器可读证据。三个入参分别是 relay 记忆 / 负缓存 / relay 可用性。
    val decision: (Option[String], Boolean, Boolean) => Boolean = P2pPathDecision.shouldSkipP2pForRelay
    assertEquals(decision(None, false, true), false)
  }

  test("A: relay 路径记忆（负证据之一）⇒ 旁路 P2P") {
    assertEquals(
      P2pPathDecision.shouldSkipP2pForRelay(Some("relay"), recentP2pFailure = false, relayAvailable = true),
      true
    )
  }

  test("A: 近期真实 P2P 失败（负缓存）⇒ 旁路 P2P") {
    assertEquals(
      P2pPathDecision.shouldSkipP2pForRelay(None, recentP2pFailure = true, relayAvailable = true),
      true
    )
  }

  test("A: p2p 路径记忆不是负证据 ⇒ 仍尝试 P2P") {
    assertEquals(
      P2pPathDecision.shouldSkipP2pForRelay(Some("p2p"), recentP2pFailure = false, relayAvailable = true),
      false
    )
  }

  test("A: relay 不可用时即使有负证据也不旁路——没有可旁路到的通道，必须试 P2P") {
    assertEquals(
      P2pPathDecision.shouldSkipP2pForRelay(Some("relay"), recentP2pFailure = true, relayAvailable = false),
      false
    )
  }

  // ===== A：探测预算（directOnline 的降级用法）=====

  test("A: directOnline=false ⇒ 单次尝试 + 1.5s 连接预算（回落 relay 时延有界）") {
    val b = P2pPathDecision.probeBudget(directOnline = false)
    assertEquals(b.maxRetries, 0, "不得重试——改前的 3 次重试 + 1/2/3s 退避正是 skipP2p 存在的理由")
    assertEquals(b.connectTimeout, 1500.millis)
    assert(b.connectTimeout <= 1500.millis, "方案 §2 A 口径：connect timeout ≤1.5s")
    assertEquals(b.shortConnect, true, "走短连接超时后端")
    // 最坏回落时延预算 = connect 1.5s（无重试、无退避）；对照改前 ≈ 3s×4 + 1+2+3s ≈ 12-15s。
    assertEquals(b.connectTimeout.toMillis + 0L, 1500L)
  }

  test("A: directOnline=true ⇒ 保持既有韧性姿态（3 次重试 / 3s 连接）") {
    val b = P2pPathDecision.probeBudget(directOnline = true)
    assertEquals(b.maxRetries, 3)
    assertEquals(b.connectTimeout, 3.seconds)
    assertEquals(b.shortConnect, false)
  }

  // ===== C4：地址敏感 + TTL =====

  test("C4: TTL 从 5min 收到 60s；负缓存同窗口（一个窗口治理两侧）") {
    assertEquals(P2pPathDecision.PathMemoryTtlMs, 60_000L)
    assertEquals(P2pPathDecision.NegativeCacheTtlMs, 60_000L)
  }

  test("C4: 地址（端点集合）变化 ⇒ 记忆失效") {
    val p1 = peer("http://192.168.1.145:8080", List("http://192.168.1.145:8080"))
    val p2 = peer("http://100.91.165.120:8080", List("http://100.91.165.120:8080"))
    val mem = P2pPathDecision.record(None, "relay", p1, 1_000L)
    assert(P2pPathDecision.reusable(Some(mem), p1, 1_100L).isDefined, "同地址 + TTL 内 ⇒ 可复用")
    assertEquals(P2pPathDecision.reusable(Some(mem), p2, 1_100L), None, "地址集合变化 ⇒ 必须失效")
  }

  test("C4: 端点集合增删（非仅 head）也算地址变化") {
    val two = peer("http://a:8080", List("http://a:8080", "http://b:8080"))
    val one = peer("http://a:8080", List("http://a:8080"))
    val mem = P2pPathDecision.record(None, "relay", two, 1_000L)
    assertEquals(P2pPathDecision.reusable(Some(mem), one, 1_100L), None)
  }

  test("C4: 端点顺序抖动不失效（键用排序后集合，只有真实变化才清记忆）") {
    val ab = peer("http://a:8080", List("http://a:8080", "http://b:8080"))
    val ba = peer("http://b:8080", List("http://b:8080", "http://a:8080"))
    val mem = P2pPathDecision.record(None, "relay", ab, 1_000L)
    assert(P2pPathDecision.reusable(Some(mem), ba, 1_100L).isDefined)
  }

  test("C4: TTL 过期 ⇒ 记忆不可用（⇒ 必须重探 P2P 一次）") {
    val p = peer("http://a:8080")
    val mem = P2pPathDecision.record(None, "relay", p, 1_000L)
    assert(P2pPathDecision.reusable(Some(mem), p, 1_000L + P2pPathDecision.PathMemoryTtlMs - 1).isDefined)
    assertEquals(P2pPathDecision.reusable(Some(mem), p, 1_000L + P2pPathDecision.PathMemoryTtlMs), None)
  }

  test("C4: relay 成功**不滑动续期**（改前每次成功都续期 ⇒ 5min 内永久旁路）") {
    val p = peer("http://a:8080")
    val first = P2pPathDecision.record(None, "relay", p, 1_000L)
    val again = P2pPathDecision.record(Some(first), "relay", p, 50_000L)
    assertEquals(again.atMs, first.atMs, "窗口起点必须保持，否则永远等不到重探")
    // 窗口确实会过期（这就是「TTL 过后必须重探」的实现）
    assertEquals(P2pPathDecision.reusable(Some(again), p, 61_000L), None)
  }

  test("C4: 过期后再落 relay ⇒ 开新窗口（下一个 TTL 周期仍会重探）") {
    val p = peer("http://a:8080")
    val first = P2pPathDecision.record(None, "relay", p, 1_000L)
    val restarted = P2pPathDecision.record(Some(first), "relay", p, 61_000L)
    assertEquals(restarted.atMs, 61_000L)
  }

  test("C4: p2p 成功**可以**续期（停在 P2P 是期望态）") {
    val p = peer("http://a:8080")
    val first = P2pPathDecision.record(None, "p2p", p, 1_000L)
    assertEquals(P2pPathDecision.record(Some(first), "p2p", p, 30_000L).atMs, 30_000L)
  }

  test("C4: 方法切换（relay → p2p）立刻覆盖，不留 relay 残留") {
    val p = peer("http://a:8080")
    val relay = P2pPathDecision.record(None, "relay", p, 1_000L)
    val switched = P2pPathDecision.record(Some(relay), "p2p", p, 1_100L)
    assertEquals(switched.method, "p2p")
    assertEquals(switched.atMs, 1_100L)
  }

  test("C4: addressKey —— endpoints 为空时退回单地址（legacy peer 不回归）") {
    assertEquals(P2pPathDecision.addressKey(peer("http://a:8080")), "http://a:8080")
    assertEquals(
      P2pPathDecision.addressKey(peer("http://a:8080", List("http://b:8080", "http://a:8080"))),
      "http://a:8080|http://b:8080",
      "多端点按排序后集合成键；顺序不参与"
    )
  }

end P2pPathDecisionSpec
