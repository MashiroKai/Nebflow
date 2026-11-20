package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * 假 DOWN 批（2026-11-05）判据 B 的回归闸：**健康表只许延迟、不许否决**。
 *
 * 作者报障逐字：「但是我们的fallback逻辑，当cmdcode不可用，不是就是会连接到deepseek
 * 吗？」现场读数（`~/.stand/logs/stand.2026-11-05.0.log`）：
 *
 * {{{
 * [Deepseek/deepseek-flash(health-down), cmdcode/deepseek/deepseek-v4.1-flash(soft-avoid),
 *  ds/deepseek-flash(health-down), zhipu/glm-5.3-flash(soft-avoid)]]
 * reason=partially-tried, chain=4 tried=1 skipped=4
 * }}}
 *
 * 收入链了，却因为一条 20:04 的 `health-down` 记录被**永久**跳过——48 分钟里一次
 * 真实调用都没发生，而同一台机器同一套 JVM 客户端对同一端点的实测是 200/0.9s。
 * 本 spec 钉住修后的四件事：
 *
 *   T1 **反例保护**（判据 5）：退避窗内的 Down 候选照旧被跳过，且跳过原因里写清
 *      「还差多久、第几次试探」——「不否决」不等于「永远重试」。
 *   T2 **只许延迟**（判据 B 本体）：窗到期 ⇒ 候选被放回链上真实试一次（半开），
 *      且放行是原子的（并发多路只放行一路）。
 *   T3 **退避阶梯**：逐次放行 → 真实失败 ⇒ 下一次更晚（60→120→240→480→600s 封顶）。
 *   T4 **成功清零**：半开试探成功（markUp）⇒ 阶梯清零，下一次 Down 从 base 起算。
 *   T5 **幻影清理**：配置里已消失的 ref 的 DOWN 项被清出表（现场两条幻影之一即
 *      `deepseek/deepseek-flash`——provider 早已改名，永远不会被探测也不会被试用）。
 *   T6 **可诊断性**：探测失败必须刷新表里的原因并带出**完整失败链**（旧实现把首次
 *      原因钉死、探测失败只在 debug 记一条）。
 *   T7 **探测链不死**：单轮异常不得让整条探测链停摆（Down 候选唯一的被动恢复路径）。
 */
class HealthHalfOpenSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def candidate(pid: String, model: String) =
    ModelCandidate(pid, ProviderConfig("http://localhost", "k", LlmProtocol.OpenAI), model)

  private def withBase[A](ms: Long)(body: => IO[A]): IO[A] =
    IO {
      if ms >= 0 then System.setProperty("nebflow.health.downRetryBaseMs", ms.toString)
      else System.clearProperty("nebflow.health.downRetryBaseMs")
    }.bracket(_ => body)(_ =>
      IO {
        if ms >= 0 then System.clearProperty("nebflow.health.downRetryBaseMs")
        else System.clearProperty("nebflow.health.downRetryBaseMs")
      }
    )

  private def downKey(monitor: ProviderHealthMonitor, ref: String): IO[Option[String]] =
    monitor.getStates.map {
      _.get(ref).collect { case HealthState.Down(reason, _) => reason }
    }

  // ============================================================
  // T1 — 反例保护：退避窗内的 Down 候选仍然被跳过（不是「永远重试」）
  // ============================================================

  test("T1: a Down candidate inside the backoff window is still SKIPPED — with the wait spelled out") {
    withBase(-1) { // 生产缺省 base = 60s
      val monitor = ProviderHealthMonitor(null)
      val cs = List(candidate("dead", "m1"), candidate("alive", "m1"))

      for
        _ <- monitor.markDown("dead", "m1", "statusCode: 401")
        (eligible, skipped) <- monitor.filterCandidatesDetailed(cs)
        _ <- monitor.getStates
      yield
        // 反例保护（判据 5）：真的不可用的候选没有因为「不许否决」就变成每一发都重试。
        assertEquals(eligible.map(_.providerId), List("alive"), "within the backoff window it must stay skipped")
        assertEquals(skipped.map(_._1.providerId), List("dead"))
        val (_, cause) = skipped.head
        assertEquals(cause.wire, "health-down", "the ledger tag stays machine-readable")
        val text = cause.describe
        assert(text.contains("statusCode: 401"), s"reason must be carried through verbatim: $text")
        assert(
          text.contains("half-open trial #1 in 60s"),
          s"the skip must state how long until the real trial (只许延迟): $text"
        )
        assert(
          text.contains("never veto it"),
          s"the skip must document that this is a delay, not a permanent veto: $text"
        )
      end for
    }
  }

  // ============================================================
  // T2 — 只许延迟：窗到期 ⇒ 放回链上真实试一次（半开），且放行是原子的
  // ============================================================

  test("T2: once the backoff expires the candidate is put back on the chain for a REAL try (half-open)") {
    withBase(-1) { // 生产缺省 base = 60s；用 ageHalfOpenForTest 模拟窗到期（不 sleep）
      val monitor = ProviderHealthMonitor(null)
      val cs = List(candidate("flaky", "m1"))

      for
        _ <- monitor.markDown(
          "flaky",
          "m1",
          "Exception when sending request: POST https://api.deepseek.com/anthropic/v1/messages"
        )
        (blocked, skipBlocked) <- monitor.filterCandidatesDetailed(cs)
        _ <- monitor.ageHalfOpenForTest("flaky", "m1", 60_000L) // 窗到期
        // 并发 8 路同时抢这一次放行：必须**恰好一路**拿到（原子记账）
        racing <- List.fill(8)(monitor.filterCandidatesDetailed(cs)).parSequence
        trialsAfter <- monitor.getHalfOpenTrials
        (second, skippedSecond) <- monitor.filterCandidatesDetailed(cs)
      yield
        assertEquals(blocked, Nil, "inside the window nothing may be tried")
        assert(skipBlocked.head._2.describe.contains("half-open trial #1 in 60s"), skipBlocked.head._2.describe)
        assertEquals(
          racing.count(_._1.nonEmpty),
          1,
          s"exactly one of 8 concurrent requests may claim the half-open trial, got ${racing.map(_._1.map(_.providerId))}"
        )
        assertEquals(trialsAfter.get("flaky/m1").map(_.trials), Some(1))
        assertEquals(second, Nil, "a later request inside the new window must not claim a duplicate trial")
        assertEquals(skippedSecond.map(_._1.providerId), List("flaky"))
        assert(
          skippedSecond.head._2.describe.contains("half-open trial #2 in 120s"),
          s"the ladder must be visible on the skip line, got ${skippedSecond.head._2.describe}"
        )
      end for
    }
  }

  // ============================================================
  // T2b — 读面不得消费试探名额（面板渲染/waitForAnyUp 只读）
  // ============================================================

  test("T2b: the READ faces (panel current / waitForAnyUp) never consume a half-open slot") {
    withBase(-1) {
      val monitor = ProviderHealthMonitor(null)
      val cs = List(candidate("flaky", "m1"))
      for
        _ <- monitor.markDown("flaky", "m1", "outage")
        _ <- monitor.ageHalfOpenForTest("flaky", "m1", 60_000L)
        (upRead, downRead) <- monitor.filterCandidates(cs) // 面板 /api/agents/:name/model current 读面
        trialsAfterRead <- monitor.getHalfOpenTrials
        (admitted, _) <- monitor.filterCandidatesDetailed(cs) // 真请求面
      yield
        // 到了窗的候选在两个面上**都算可用**（「下一次真实请求会试它」是事实）：
        // 唯一区别是读面不记账。这一点对 [[waitForAnyUp]] 尤其重要——全 DOWN 链的
        // 等待者必须被「窗已到」唤醒，否则又退回「等 120s 探测」的老坑。
        assertEquals(
          upRead.map(_.providerId),
          List("flaky"),
          "a window-expired candidate is available to the read face"
        )
        assertEquals(downRead, Nil)
        assertEquals(
          trialsAfterRead.get("flaky/m1").map(_.trials),
          Some(0),
          "a panel render must never claim the trial (otherwise a render would veto the next real attempt)"
        )
        assertEquals(admitted.map(_.providerId), List("flaky"), "the real request still gets its trial")
      end for
    }
  }

  // ============================================================
  // T3 — 退避阶梯：真实失败 ⇒ 下一次更晚（指数 + 封顶）
  // ============================================================

  test("T3: real failures re-arm a LONGER backoff each time (60s → 120s → 240s → 480s → 600s cap)") {
    val ladder = (0 to 6).map(ProviderHealthMonitor.downRetryBackoffMs).toList
    assertEquals(
      ladder,
      List(60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L, 600_000L),
      s"backoff ladder must be exponential with a 10-minute cap, got $ladder"
    )
    // 行为面：一次「放行 → 真实失败」之后，第 2 次试探的等待严格长于第 1 次。
    withBase(-1) {
      val monitor = ProviderHealthMonitor(null)
      val cs = List(candidate("dead", "m1"))
      for
        _ <- monitor.markDown("dead", "m1", "auth")
        _ <- monitor.ageHalfOpenForTest("dead", "m1", 60_000L)
        _ <- monitor.filterCandidatesDetailed(cs) // 放行第 1 次
        t1 <- monitor.getHalfOpenTrials
        _ <- monitor.markDown("dead", "m1", "auth (trial #1 really failed)") // 试探真失败
        fresh <- downKey(monitor, "dead/m1")
        // 只再等 60s 不够（第 2 次窗口是 120s）
        (tooEarly, tooEarlySkips) <- monitor.filterCandidatesDetailed(cs)
        _ <- monitor.ageHalfOpenForTest("dead", "m1", 120_000L)
        _ <- monitor.filterCandidatesDetailed(cs) // 放行第 2 次
        t2 <- monitor.getHalfOpenTrials
      yield
        assertEquals(t1.get("dead/m1").map(_.trials), Some(1))
        assertEquals(tooEarly, Nil, "the second window is twice as long — 60s of waiting is not enough")
        assert(
          tooEarlySkips.head._2.describe.contains("half-open trial #2 in 120s"),
          tooEarlySkips.head._2.describe
        )
        assertEquals(t2.get("dead/m1").map(_.trials), Some(2))
        assertEquals(
          fresh,
          Some("auth (trial #1 really failed)"),
          "the Down reason must be the LATEST failure (the old fossil reason is a diagnosability bug)"
        )
      end for
    }
  }

  // ============================================================
  // T4 — 成功清零：半开试探成功 ⇒ 阶梯归零，下一次 Down 从 base 起算
  // ============================================================

  test("T4: a successful half-open trial clears the ladder — the next Down starts again at base") {
    withBase(-1) {
      val monitor = ProviderHealthMonitor(null)
      val cs = List(candidate("svc", "m1"))
      for
        _ <- monitor.markDown("svc", "m1", "outage")
        _ <- monitor.ageHalfOpenForTest("svc", "m1", 60_000L)
        _ <- monitor.filterCandidatesDetailed(cs) // 放行（模拟一次真实试探）
        before <- monitor.getHalfOpenTrials
        _ <- monitor.markUp("svc", "m1") // 试探成功
        cleared <- monitor.getHalfOpenTrials
        states <- monitor.getStates
        // 再次 Down：阶梯从 (now, 0) 重新起算 ⇒ 首个试探仍在 base 之后（不是立刻）
        _ <- monitor.markDown("svc", "m1", "outage 2")
        again <- monitor.getHalfOpenTrials
        (eligible, skipped) <- monitor.filterCandidatesDetailed(cs)
      yield
        assertEquals(before.get("svc/m1").map(_.trials), Some(1), "the trial must have been claimed")
        assertEquals(cleared.get("svc/m1"), None, "markUp must clear the half-open ladder")
        assertEquals(states.get("svc/m1"), Some(HealthState.Up))
        assertEquals(again.get("svc/m1").map(_.trials), Some(0), "a fresh Down restarts the ladder")
        assertEquals(eligible, Nil)
        assert(
          skipped.head._2.describe.contains("half-open trial #1 in 60s"),
          s"first trial of the new episode must be base-later, got ${skipped.head._2.describe}"
        )
      end for
    }
  }

  // ============================================================
  // T5 — 幻影清理：配置里已消失的 ref 的 DOWN 项被清出表
  // ============================================================

  /** Registry resolving only a whitelist of refs (simulates config hot-reload dropping one). */
  private class WhitelistRegistry(known: Set[String]) extends ProviderRegistry(null, null):

    override def getAdapter(providerId: String): IO[ProviderAdapter[IO]] =
      IO.raiseError(new RuntimeException("not needed in this spec"))

    override def getAdapterRaw(providerId: String): IO[ProviderAdapter[IO]] =
      IO.raiseError(new RuntimeException("not needed in this spec"))

    override def getCandidateForRef(ref: String): IO[Option[ModelCandidate]] =
      if known.contains(ref) then
        ref.split("/").toList match
          case pid :: m :: Nil =>
            IO.pure(
              Some(ModelCandidate(pid, ProviderConfig("http://x", "k", LlmProtocol.OpenAI, List(ModelConfig(m))), m))
            )
          case _ => IO.pure(None)
      else IO.pure(None)

  test("T5: DOWN entries whose ref left the config are dropped (phantom DOWN must not accumulate)") {
    val monitor = ProviderHealthMonitor(WhitelistRegistry(Set("kept/m1")))
    for
      _ <- monitor.markDown("ghost", "gone", "provider renamed away")
      _ <- monitor.markDown("kept", "m1", "real outage")
      dcBefore <- monitor.downCandidates()
      _ <- monitor.gcUnresolvableDown()
      states <- monitor.getStates
      dcAfter <- monitor.downCandidates()
    yield
      // 清理前：幻影项解析不出候选 ⇒ 永远既不探测也不试用（现场 `deepseek/deepseek-flash` 形态）
      assertEquals(dcBefore.map(c => s"${c.providerId}/${c.model}"), List("kept/m1"))
      assertEquals(states.contains("ghost/gone"), false, s"phantom DOWN must be swept, got ${states.keys}")
      assertEquals(states.contains("kept/m1"), true, s"a configured Down candidate must be KEPT, got ${states.keys}")
      assertEquals(dcAfter.map(c => s"${c.providerId}/${c.model}"), List("kept/m1"))
  }

  // ============================================================
  // T6 — 可诊断性：探测失败刷新原因 + 带出完整失败链（类名 + cause 链）
  // ============================================================

  private class FailingAdapter(err: Throwable) extends ProviderAdapter[IO]:

    def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
      IO.raiseError(new RuntimeException("probe must use the streaming face"))

    def sendMessageStream(params: SendMessageParams): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](err)

  private class AdapterRegistry(adapter: ProviderAdapter[IO]) extends ProviderRegistry(null, null):
    override def getAdapter(providerId: String): IO[ProviderAdapter[IO]] = IO.pure(adapter)
    override def getAdapterRaw(providerId: String): IO[ProviderAdapter[IO]] = IO.pure(adapter)

  test("T6: a probe failure REFRESHES the Down reason and carries the full cause chain (class names included)") {
    val root = new java.net.SocketException("Connection reset")
    val wrapped = new java.io.IOException(
      "Exception when sending request: POST https://api.deepseek.com/anthropic/v1/messages",
      root
    )
    val monitor = ProviderHealthMonitor(AdapterRegistry(FailingAdapter(wrapped)))
    for
      // 化石原因：现场表里那条 20:04 的包装句
      _ <- monitor.markDown(
        "Deepseek",
        "deepseek-flash",
        "Exception when sending request: POST https://api.deepseek.com/anthropic/v1/messages"
      )
      _ <- monitor.probe(candidate("Deepseek", "deepseek-flash"))
      reason <- downKey(monitor, "Deepseek/deepseek-flash")
    yield
      val r = reason.getOrElse(fail("candidate must stay Down after a failed probe"))
      assert(r.contains("IOException: Exception when sending request"), s"wrapper class must be named: $r")
      assert(r.contains("SocketException: Connection reset"), s"root cause must be carried through the chain: $r")
      assert(r.contains(" | "), s"the chain separator must make the nesting readable: $r")
  }

  // ============================================================
  // T7 — 探测链不死：单轮异常不得停摆整条链
  // ============================================================

  private class ExplodingRegistry extends ProviderRegistry(null, null):

    override def getAdapter(providerId: String): IO[ProviderAdapter[IO]] =
      IO.raiseError(new RuntimeException("boom"))

    override def getAdapterRaw(providerId: String): IO[ProviderAdapter[IO]] =
      IO.raiseError(new RuntimeException("boom"))

    override def getCandidateForRef(ref: String): IO[Option[ModelCandidate]] =
      IO.raiseError(new RuntimeException("config ref resolution exploded"))

  test("T7: the probe loop survives a throwing cycle instead of dying silently (the only passive recovery path)") {
    val monitor = ProviderHealthMonitor(new ExplodingRegistry)
    for
      _ <- monitor.markDown("x", "m1", "outage")
      // 循环若被一轮异常打死，attempt 会立刻返回 Left；活着则被 timeoutTo 到点取回。
      outcome <- monitor.start().attempt.timeoutTo(600.millis, IO.pure(Right(())))
      states <- monitor.getStates
    yield
      assert(outcome.isRight, s"a failed probe cycle must not terminate the loop, got $outcome")
      assertEquals(
        states.contains("x/m1"),
        true,
        "the sweep must not be able to wipe states on error (phantom sweep runs before the probe set)"
      )
  }
end HealthHalfOpenSpec
