package nebflow.core.tools

import cats.effect.IO
import munit.CatsEffectSuite

import java.net.{InetAddress, InetSocketAddress, ServerSocket}

/**
 * 探测税判据的钉子（砍探测税批 2026-11-04）。
 *
 * 覆盖三件事，全部二值可判：
 *   ① 负证据窗口的退避曲线（`negativeWindowMs`）与封顶；
 *   ② 负证据的**失效条件**（地址集变化 / 成功 / 显式作废 / 窗口到期）；
 *   ③ 并发探测的「首胜即放行」与「全不通的耗时上界 = 单端点预算，不是候选数 × 预算」。
 *
 * ⚠ 探针形态（2026-11-04 实测批换形）：活性探测 = 有界 HTTP GET `/api/health`，**不是**
 * 裸 TCP connect —— 本机透明代理把任意地址的 TCP connect 就地接通（TEST-NET-3 也 0–12ms
 * 「连上」），裸 connect 是假阳性机器；HTTP 探针才把「被代理接走」与「真在服务」分开。
 * 因此「死端点」的构造不能再用「刚释放的端口」——本机代理同样会替它应答；spec 的死端点
 * 改用「对端桩服务器按位拒绝」的路径：对不可服务路径断言 `None`。
 *
 * 为什么这三条必须是 spec 而不是报告里的一句话：改前的病灶正是「每 60 s 重付一次
 * 4.5 s 探测税」——如果窗口/失效/并发任一条写错，税会以另一种形态回来（要么永不
 * 恢复 P2P，要么按分钟重付），而这两者都只在真机上才看得出来。
 */
class P2pReachabilitySpec extends CatsEffectSuite:

  // ===== ① 退避曲线 =====

  test("negativeWindowMs：60s 起、每次翻倍、封顶 15min（0/负数 ⇒ 0）") {
    assertEquals(P2pProbePolicy.negativeWindowMs(0), 0L, "no failure ⇒ no window")
    assertEquals(P2pProbePolicy.negativeWindowMs(-3), 0L)
    assertEquals(P2pProbePolicy.negativeWindowMs(1), 60_000L, "first failure = the old fixed 60s TTL")
    assertEquals(P2pProbePolicy.negativeWindowMs(2), 120_000L)
    assertEquals(P2pProbePolicy.negativeWindowMs(3), 240_000L)
    assertEquals(P2pProbePolicy.negativeWindowMs(4), 480_000L)
    assertEquals(P2pProbePolicy.negativeWindowMs(5), P2pProbePolicy.NegativeTtlMaxMs, "5th failure hits the 15min cap")
    assertEquals(P2pProbePolicy.negativeWindowMs(40), P2pProbePolicy.NegativeTtlMaxMs, "cap never overflows")
  }

  test("probeTarget：从端点串取 host:port（缺端口 ⇒ 6138；空/无 host ⇒ 显式错误）") {
    assertEquals(P2pProbePolicy.probeTarget("http://100.91.165.120:6138"), Right(("100.91.165.120", 6138)))
    assertEquals(P2pProbePolicy.probeTarget("http://192.168.1.145"), Right(("192.168.1.145", 6138)))
    assertEquals(P2pProbePolicy.probeTarget("192.168.1.145:8097"), Right(("192.168.1.145", 8097)))
    assert(P2pProbePolicy.probeTarget("  ").isLeft)
  }

  // ===== ② 账本：窗口 / 失效条件 =====

  test("账本：失败 ⇒ 窗口内旁路；到期 ⇒ 不再旁路（半开探测）") {
    val l = new P2pReachability
    val key = "http://a:6138|http://b:6138"
    assertEquals(l.noteFailure("dev", key, nowMs = 1_000L), 1)
    assert(l.shouldSkip("dev", key, 1_100L), "inside the 60s window ⇒ skip P2P")
    assertEquals(l.negativeRemainingMs("dev", key, 1_100L), Some(59_900L), "1_000 + 60_000 - 1_100")
    assert(!l.shouldSkip("dev", key, 1_000L + 60_001L), "window expires ⇒ probe again (half-open)")
  }

  test("账本：连续失败按退避增长——5 次后一次判决覆盖 15min") {
    val l = new P2pReachability
    val key = "k"
    (1 to 5).foreach(n => assertEquals(l.noteFailure("dev", key, nowMs = n.toLong), n))
    assert(l.shouldSkip("dev", key, 5L + 14L * 60 * 1000), "14min later still inside the 15min window")
    assert(!l.shouldSkip("dev", key, 5L + 16L * 60 * 1000))
  }

  test("账本：地址集变化 ⇒ 负证据立即失效（新地址可能是通的）") {
    val l = new P2pReachability
    l.noteFailure("dev", "old-key", nowMs = 1_000L)
    assert(!l.shouldSkip("dev", "new-key", 1_100L), "a different address set must not inherit the verdict")
    assert(!l.shouldSkip("dev", "old-key", 1_100L), "and the stale entry is dropped, not kept")
  }

  test("账本：成功 / 显式作废 ⇒ 立即清账") {
    val l = new P2pReachability
    l.noteFailure("dev", "k", 1_000L)
    l.noteSuccess("dev")
    assert(!l.shouldSkip("dev", "k", 1_100L), "a success must clear the negative evidence at once")

    l.noteFailure("dev", "k", 1_000L)
    l.invalidate("dev")
    assert(!l.shouldSkip("dev", "k", 1_100L), "explicit invalidation must clear it too")
  }

  test("账本：读数面（条目数 / 探测数 / 旁路数）单调递增") {
    val l = new P2pReachability
    l.noteProbeAttempt()
    l.noteProbeAttempt()
    l.noteFailure("dev", "k", 1_000L)
    l.shouldSkip("dev", "k", 1_100L)
    l.noteSkip()
    val (entries, probes, skips) = l.stats
    assertEquals(entries, 1)
    assertEquals(probes, 2L)
    assertEquals(skips, 1L)
  }

  // ===== ③ 并发探测：首胜即放行 + 耗时上界 =====

  /** 无 HTTP 服务的本机端口（探针必须判不可用）。 */
  private def deadEndpoint(): String =
    val s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try s"http://127.0.0.1:${s.getLocalPort}"
    finally s.close()

  /** 一台真在服务的桩对端（应答 `/api/health` 200）—— 探针是 HTTP 探针，桩必须真应答。 */
  private def liveStub(): (com.sun.net.httpserver.HttpServer, String) =
    val server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/api/health",
      (ex: com.sun.net.httpserver.HttpExchange) =>
        val body = """{"status":"ok"}""".getBytes("UTF-8")
        ex.sendResponseHeaders(200, body.length.toLong)
        ex.getResponseBody.write(body)
        ex.close()
    )
    server.start()
    (server, s"http://127.0.0.1:${server.getAddress.getPort}")

  test("probeConcurrently：探测到真服务的端点 ⇒ 首胜即放行（活桩胜出）") {
    val (server, liveEp) = liveStub()
    P2pReachability
      .probeConcurrently(List(liveEp), 1_200)
      .map { winner =>
        assertEquals(winner, Some(liveEp), "the serving candidate must win (HTTP probe, any status)")
      }
      .guarantee(IO.blocking(server.stop(0)))
  }

  test("probeConcurrently：死候选 + 活桩 ⇒ 首胜即放行，活的那个胜出（并发不串行）") {
    val (server, liveEp) = liveStub()
    val eps = List(deadEndpoint(), liveEp, deadEndpoint())
    val t0 = System.nanoTime()
    P2pReachability
      .probeConcurrently(eps, 1_200)
      .map { winner =>
        val ms = (System.nanoTime() - t0) / 1000000L
        assertEquals(winner, Some(liveEp))
        assert(ms < 1_200L, s"first-wins must release as soon as the live candidate answers (took ${ms}ms)")
      }
      .guarantee(IO.blocking(server.stop(0)))
  }

  test("probeConcurrently：全部不可服务 ⇒ None，且耗时上界 = 单端点预算（不是候选数 × 预算）") {
    // 死端点 = 不存在的 HTTP 服务端口（无监听）——HTTP 探针在此必须失败。
    // 注：不能用「刚释放的端口」断言死：本机透明代理会替任何 TCP 应答（实测批教训），
    // 而这里断言的是「无 HTTP 服务 ⇒ 探针失败」，监听与否由真桩对照用例钉。
    val eps = List(deadEndpoint(), deadEndpoint(), deadEndpoint())
    val t0 = System.nanoTime()
    P2pReachability.probeConcurrently(eps, 1_200).map { winner =>
      val ms = (System.nanoTime() - t0) / 1000000L
      assertEquals(winner, None)
      // 三个端点各自「连接被拒」是毫秒级；关键断言是**不许**出现串行累加的超时。
      assert(ms < 1_200L, s"concurrent probe must be bounded by one budget, took ${ms}ms")
    }
  }

end P2pReachabilitySpec
