package nebflow.llm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.shared.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class HealthMonitorSpec extends CatsEffectSuite:

  // Helper: create dummy candidates
  private def candidate(pid: String, model: String) =
    ModelCandidate(pid, ProviderConfig("http://localhost", "k", LlmProtocol.OpenAI), model)

  private def newMonitor = ProviderHealthMonitor(null) // registry not needed for state tests

  // ============================================================
  // markDown / filterCandidates
  // ============================================================

  test("fresh monitor: all candidates are Up") {
    val monitor = newMonitor
    val cs = List(candidate("glm", "glm-5"), candidate("kimi", "moonshot-v1"))

    for (up, down) <- monitor.filterCandidates(cs)
    yield
      assertEquals(up.size, 2)
      assertEquals(down.size, 0)
  }

  test("markDown removes candidate from Up list") {
    val monitor = newMonitor
    val cs = List(candidate("glm", "glm-5"), candidate("kimi", "moonshot-v1"))

    for
      _ <- monitor.markDown("glm", "glm-5", "server error")
      (up, down) <- monitor.filterCandidates(cs)
    yield
      assertEquals(up.map(_.providerId), List("kimi"))
      assertEquals(down.map(_.providerId), List("glm"))
  }

  test("markDown is idempotent") {
    val monitor = newMonitor

    for
      _ <- monitor.markDown("glm", "glm-5", "error1")
      _ <- monitor.markDown("glm", "glm-5", "error2") // no-op
      states <- monitor.getStates
    yield
      assertEquals(states.size, 1)
      // First reason is kept
      states.get("glm/glm-5").collect { case HealthState.Down(reason, _) =>
        assertEquals(reason, "error1")
      }
  }

  // ============================================================
  // markUp / recovery
  // ============================================================

  test("markUp restores candidate to Up list") {
    val monitor = newMonitor
    val cs = List(candidate("glm", "glm-5"), candidate("kimi", "moonshot-v1"))

    for
      _ <- monitor.markDown("glm", "glm-5", "server error")
      (up1, _) <- monitor.filterCandidates(cs)
      _ <- monitor.markUp("glm", "glm-5")
      (up2, _) <- monitor.filterCandidates(cs)
    yield
      assertEquals(up1.map(_.providerId), List("kimi"))
      assertEquals(up2.map(_.providerId).toSet, Set("glm", "kimi"))
  }

  test("markUp is no-op if already Up") {
    val monitor = newMonitor

    for
      _ <- monitor.markUp("glm", "glm-5") // never was Down
      states <- monitor.getStates
    yield assertEquals(states.get("glm/glm-5"), None)
  }

  // ============================================================
  // Per-model granularity: same provider, different models
  // ============================================================

  test("markDown is per provider+model, not per provider") {
    val monitor = newMonitor
    val glm5 = candidate("glm", "glm-5")
    val glm4 = candidate("glm", "glm-4-flash")

    for
      _ <- monitor.markDown("glm", "glm-5", "model error")
      (up, down) <- monitor.filterCandidates(List(glm5, glm4))
    yield
      assertEquals(up.map(_.model), List("glm-4-flash"))
      assertEquals(down.map(_.model), List("glm-5"))
  }

  // ============================================================
  // waitForAnyUp — blocks then wakes on markUp
  // ============================================================

  test("waitForAnyUp blocks until markUp wakes it") {
    val monitor = newMonitor
    val cs = List(candidate("glm", "glm-5"))

    for
      _ <- monitor.markDown("glm", "glm-5", "down")
      // Race: waitForAnyUp vs 500ms timeout — timeout should win (still blocked)
      result1 <- monitor.waitForAnyUp(cs).timeoutTo(500.millis, IO.pure("timeout"))
      _ = assertEquals(result1, "timeout")
      // Mark up — now waitForAnyUp should complete
      _ <- monitor.markUp("glm", "glm-5")
      // Race again — wait should return immediately now
      result2 <- monitor.waitForAnyUp(cs).timeoutTo(500.millis, IO.pure("timeout"))
    yield assert(result2 == (), s"expected unit, got $result2")
  }

  test("waitForAnyUp returns immediately if a signal was already fired") {
    val monitor = newMonitor
    val cs = List(candidate("glm", "glm-5"))

    for
      _ <- monitor.markDown("glm", "glm-5", "down")
      _ <- monitor.markUp("glm", "glm-5") // fires signal
      _ <- monitor.waitForAnyUp(cs) // should not block
    yield ()
  }

  // ============================================================
  // waitForAnyUp — multi-waiter recovery race (2026-08-15 incident)
  // ============================================================

  test("late waiter after an early waiter consumed the signal must not hang") {
    val monitor = newMonitor
    val cs = List(candidate("glm", "glm-5"))

    // Exact reproduction of the dual-DOWN ghost timeout: waiter A enters after
    // the signal fired (old code: tryGet=Some -> refresh Deferred -> return),
    // then waiter B enters and finds a FRESH EMPTY Deferred with every
    // provider already Up — old code blocked B for the full 120s timeout.
    // New code re-checks health state on entry/wake, so B returns instantly.
    for
      _ <- monitor.markDown("glm", "glm-5", "timeout")
      _ <- monitor.markUp("glm", "glm-5") // signal fires before any waiter
      _ <- monitor.waitForAnyUp(cs) // waiter A consumes the signal path
      r <- monitor.waitForAnyUp(cs).timeout(2.seconds).attempt // waiter B, late
    yield assert(r.isRight, s"late waiter must pass via state re-check, got $r")
  }

  test("two parked waiters both released by one markUp signal") {
    val monitor = newMonitor
    val cs = List(candidate("glm", "glm-5"))

    for
      _ <- monitor.markDown("glm", "glm-5", "down")
      dA <- IO.deferred[Unit]
      dB <- IO.deferred[Unit]
      fibA <- (monitor.waitForAnyUp(cs) *> dA.complete(())).start
      fibB <- (monitor.waitForAnyUp(cs) *> dB.complete(())).start
      _ <- IO.sleep(200.millis) // both parked on the signal or a tick
      _ <- monitor.markUp("glm", "glm-5")
      _ <- dA.get.timeout(2.seconds) // both must be released promptly
      _ <- dB.get.timeout(2.seconds)
      _ <- fibA.join *> fibB.join
    yield ()
  }

  test("waiter blocked on an orphaned Deferred escapes via tick re-check") {
    val monitor = newMonitor
    val cs = List(candidate("glm", "glm-5"))

    // Anti-spin path: a waiter woken without recovery replaces the signal
    // Deferred; another waiter still parked on the old Deferred is orphaned
    // (nothing will ever complete it) — it must escape via the periodic tick
    // re-check once its candidates recover, not hang forever.
    for
      _ <- monitor.markDown("glm", "glm-5", "down")
      dOrphan <- IO.deferred[Unit]
      fibOrphan <- (monitor.waitForAnyUp(cs) *> dOrphan.complete(())).start
      _ <- IO.sleep(200.millis) // orphan parked on D1
      // Simulate another waiter's anti-spin Deferred replacement by forcing
      // the replacement directly (refresh signalRef to a fresh empty one).
      _ <- IO.deferred[Unit].flatMap(fresh => monitor.replaceSignalForTest(fresh))
      _ <- monitor.markUp("glm", "glm-5") // completes the NEW Deferred only
      _ <- dOrphan.get.timeout(ProviderHealthMonitor.RecheckIntervalSec.seconds + 2.seconds)
      _ <- fibOrphan.join
    yield ()
  }

  // ============================================================
  // Priority ordering preserved after filtering
  // ============================================================

  test("filterCandidates preserves priority order") {
    val monitor = newMonitor
    // Chain: [GLM, Kimi, DeepSeek]
    val cs = List(
      candidate("glm", "glm-5"),
      candidate("kimi", "moonshot-v1"),
      candidate("deepseek", "deepseek-v4")
    )

    for
      // GLM and DeepSeek down — only Kimi up
      _ <- monitor.markDown("glm", "glm-5", "error")
      _ <- monitor.markDown("deepseek", "deepseek-v4", "error")
      (up, down) <- monitor.filterCandidates(cs)
    yield
      assertEquals(up.map(_.providerId), List("kimi"))
      assertEquals(down.map(_.providerId), List("glm", "deepseek"))
  }

  test("full chain down returns empty Up list") {
    val monitor = newMonitor
    val cs = List(candidate("glm", "glm-5"), candidate("kimi", "moonshot-v1"))

    for
      _ <- monitor.markDown("glm", "glm-5", "error")
      _ <- monitor.markDown("kimi", "moonshot-v1", "error")
      (up, down) <- monitor.filterCandidates(cs)
    yield
      assertEquals(up.size, 0)
      assertEquals(down.size, 2)
  }

  // ============================================================
  // probe — recovery path for Down providers
  // ============================================================

  // ============================================================
  // probe — recovery path for Down providers
  //
  // 修 2026-10-21「供应商恢复后不回绿」：探测的 serving 判据 = 流式请求的
  // **首个内容事件**（与真实流面 winner 同一批 chunk），流在首个事件后即取消
  // —— 清除时点 = 探测成功时点（首事件时点），不等补全、不等周期尾部。
  // 旧探测（非流 `hi` 补全 + 30s 帽）在「恢复但首补全慢」的思考型 provider
  // 上每轮假失败，provider 无限滞留 DOWN——面板读面（filterCandidates /
  // getStates 现读）永远不回绿。
  // ============================================================

  /**
   * Stream-face fake. `chunks` are emitted in order; `delayBeforeMs` parks the
   * first emission (budget-binding probes); `hangAfter` keeps the stream open
   * forever afterwards (simulates a slow thinking provider whose final text
   * never arrives inside the probe — the probe must already have cleared at
   * the first serving event). `err` raises before any chunk.
   */
  private class FakeStreamAdapter(
    chunks: Seq[StreamChunk] = Nil,
    delayBeforeMs: Long = 0L,
    hangAfter: Boolean = false,
    err: Option[Throwable] = None
  ) extends ProviderAdapter[IO]:

    def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
      IO.raiseError(new RuntimeException("probe must use the streaming face (修 2026-10-21)"))

    def sendMessageStream(params: SendMessageParams): fs2.Stream[IO, StreamChunk] =
      err match
        case Some(e) => fs2.Stream.raiseError[IO](e)
        case None =>
          val head: fs2.Stream[IO, StreamChunk] =
            if delayBeforeMs > 0 then fs2.Stream.eval(IO.sleep(delayBeforeMs.millis)) >> fs2.Stream.emits(chunks.toList)
            else fs2.Stream.emits(chunks.toList)
          if hangAfter then head ++ fs2.Stream.never[IO] else head

  end FakeStreamAdapter

  private class FakeRegistry(adapter: ProviderAdapter[IO]) extends ProviderRegistry(null, null):
    override def getAdapter(providerId: String): IO[ProviderAdapter[IO]] = IO.pure(adapter)

  /**
   * Registry that resolves a whitelist of model refs (simulates a config with
   * a default chain that does NOT include the Down candidate — the 2026-08-25
   * kimi scenario where kimi/k3-256k lives only in the Vision preset).
   */
  private class FakeRegistry2(adapter: ProviderAdapter[IO], known: Set[String]) extends ProviderRegistry(null, null):
    override def getAdapter(providerId: String): IO[ProviderAdapter[IO]] = IO.pure(adapter)

    override def getCandidateForRef(ref: String): IO[Option[ModelCandidate]] =
      if known.contains(ref) then
        ref.split("/").toList match
          case pid :: m :: Nil => IO.pure(Some(candidate(pid, m)))
          case _ => IO.pure(None)
      else IO.pure(None)

  test("probe clears at the FIRST serving event — a slow thinking provider (never completes) still recovers") {
    // The 2026-10-21 incident shape: provider recovered, but its thinking-only
    // output streams thinking events for minutes before any final text. The
    // old probe awaited a full completion under a 30s cap → false-failed every
    // cycle → DOWN forever → the panel never turned green. The probe must
    // clear at the thinking event and must NOT wait for the stream to end.
    val adapter = FakeStreamAdapter(chunks = Seq(StreamChunk.ThinkingDelta("reasoning…")), hangAfter = true)
    val monitor = ProviderHealthMonitor(FakeRegistry(adapter))
    val c = candidate("glm", "glm-5-107")

    for
      _ <- monitor.markDown("glm", "glm-5-107", "first-token timeout")
      started <- IO.realTime
      _ <- monitor.probe(c)
      elapsed <- IO.realTime.map(_ - started)
      states <- monitor.getStates
    yield
      states.get("glm/glm-5-107") match
        case Some(HealthState.Up) => ()
        case other => fail(s"first serving event must clear DOWN immediately, got $other")
      assert(elapsed < 5.seconds, s"clear must land at the first event, not at completion/cycle tail; took $elapsed")
  }

  test("probe keeps Down when the stream errors before any serving event") {
    val adapter = FakeStreamAdapter(err = Some(new RuntimeException("probe error")))
    val monitor = ProviderHealthMonitor(FakeRegistry(adapter))
    val c = candidate("glm", "glm-5-107")

    for
      _ <- monitor.markDown("glm", "glm-5-107", "first-token timeout")
      _ <- monitor.probe(c)
      states <- monitor.getStates
    yield states.get("glm/glm-5-107") match
      case Some(HealthState.Down(_, _)) => ()
      case other => fail(s"expected Down after failed probe, got $other")
  }

  test("probe keeps Down on a Done-only stream (no content — the real path fails the same shape)") {
    // Mirror of the streaming path's "Stream completed with no content" raise:
    // a provider that answers Done without any content chunk is not serving.
    val adapter = FakeStreamAdapter(chunks = Seq(StreamChunk.Done(None, None)))
    val monitor = ProviderHealthMonitor(FakeRegistry(adapter))
    val c = candidate("glm", "glm-5-107")

    for
      _ <- monitor.markDown("glm", "glm-5-107", "timeout")
      _ <- monitor.probe(c)
      states <- monitor.getStates
    yield states.get("glm/glm-5-107") match
      case Some(HealthState.Down(_, _)) => ()
      case other => fail(s"a content-less Done must NOT clear DOWN, got $other")
  }

  test("probe budget shrinks via nebflow.health.probeTimeoutSec (read at probe, never a startup constant)") {
    val prev = System.getProperty("nebflow.health.probeTimeoutSec")
    // First (serving) event arrives only after 10s — the 1s budget must cut
    // the probe first (IO.sleep cancellation is the deterministic park; a
    // bare never-emitting pull is not what the budget is about).
    val adapter = FakeStreamAdapter(chunks = Seq(StreamChunk.TextDelta("late")), delayBeforeMs = 10_000)
    val monitor = ProviderHealthMonitor(FakeRegistry(adapter))
    val c = candidate("glm", "glm-5-107")

    // set/restore INSIDE the IO: an eager try/finally only brackets the
    // CONSTRUCTION of the IO — the probe runs after that finally already
    // restored the property (read-at-probe discipline is the whole point).
    (for
      _ <- IO(System.setProperty("nebflow.health.probeTimeoutSec", "1"))
      _ <- IO(assertEquals(ProviderHealthMonitor.ProbeTimeoutSec, 1, "budget must shrink via the sys prop"))
      _ <- monitor.markDown("glm", "glm-5-107", "timeout")
      started <- IO.realTime
      _ <- monitor.probe(c)
      elapsed <- IO.realTime.map(_ - started)
      states <- monitor.getStates
    yield
      states.get("glm/glm-5-107") match
        case Some(HealthState.Down(_, _)) => ()
        case other => fail(s"a beyond-budget event must not clear DOWN, got $other")
      assert(elapsed >= 900.millis && elapsed < 8.seconds, s"budget must bind the probe; took $elapsed")
    ).guarantee(IO {
      if prev != null then System.setProperty("nebflow.health.probeTimeoutSec", prev)
      else System.clearProperty("nebflow.health.probeTimeoutSec")
    })
  }

  test("probeNow → 现读回绿: filterCandidates (the /model panel read face) reflects the clear immediately") {
    // 不可用 → 恢复 → 回绿全链（清除时点 = 探测成功时点，非周期尾部）：
    // markDown ⇒ probeNow（并行、首事件即清）⇒ getStates Up ∧ filterCandidates
    // 的 up 非空。filterCandidates 正是 /api/agents/:name/model `current` 的
    // 现读源（PresenceRoutes /model 读面），getStates 是 /api/health `providers`
    // 的现读源——后端清除即刻反映，零缓存、零额外定时器。
    val adapter = FakeStreamAdapter(chunks = Seq(StreamChunk.TextDelta("pong")))
    val monitor = ProviderHealthMonitor(FakeRegistry(adapter))
    val cs = List(candidate("glm", "glm-5"), candidate("kimi", "moonshot-v1"))

    for
      _ <- monitor.markDown("glm", "glm-5", "outage")
      (upBefore, downBefore) <- monitor.filterCandidates(cs)
      _ <- monitor.probeNow(List(candidate("glm", "glm-5")))
      states <- monitor.getStates
      (upAfter, downAfter) <- monitor.filterCandidates(cs)
    yield
      assertEquals(upBefore.map(_.providerId), List("kimi"))
      assertEquals(downBefore.map(_.providerId), List("glm"))
      states.get("glm/glm-5") match
        case Some(HealthState.Up) => ()
        case other => fail(s"probeNow must clear the outage marker, got $other")
      assertEquals(upAfter.map(_.providerId).toSet, Set("glm", "kimi"))
      assertEquals(downAfter, List.empty[ModelCandidate])
  }

  test("downCandidates includes Down candidates outside the default chain (kimi fix)") {
    val adapter = FakeStreamAdapter(chunks = Seq(StreamChunk.TextDelta("pong")))
    // kimi/k3-256k is NOT in the default (general) chain — only in Vision preset
    val monitor = ProviderHealthMonitor(FakeRegistry2(adapter, Set("kimi/k3-256k")))

    for
      _ <- monitor.markDown("kimi", "k3-256k", "timeout")
      dc <- monitor.downCandidates()
      _ <- monitor.probe(dc.head)
      states <- monitor.getStates
    yield
      assertEquals(dc.map(c => s"${c.providerId}/${c.model}"), List("kimi/k3-256k"))
      states.get("kimi/k3-256k") match
        case Some(HealthState.Up) => ()
        case other => fail(s"kimi/k3-256k must recover via downCandidates probe, got $other")
  }

  test("downCandidates skips refs whose provider was removed from config") {
    val monitor = ProviderHealthMonitor(
      FakeRegistry2(FakeStreamAdapter(chunks = Seq(StreamChunk.TextDelta("pong"))), Set.empty)
    )

    for
      _ <- monitor.markDown("ghost", "gone", "error")
      dc <- monitor.downCandidates()
    yield assertEquals(dc, List.empty[ModelCandidate])
  }
end HealthMonitorSpec
