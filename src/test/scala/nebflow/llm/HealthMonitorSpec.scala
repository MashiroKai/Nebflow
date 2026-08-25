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

  private class FakeAdapter(result: IO[AdapterResponse]) extends ProviderAdapter[IO]:
    def sendMessage(params: SendMessageParams): IO[AdapterResponse] = result
    def sendMessageStream(params: SendMessageParams): fs2.Stream[IO, StreamChunk] = fs2.Stream.empty

  private class FakeRegistry(adapter: ProviderAdapter[IO]) extends ProviderRegistry(null, null):
    override def getAdapter(providerId: String): IO[ProviderAdapter[IO]] = IO.pure(adapter)

  /** Registry that resolves a whitelist of model refs (simulates a config with
    * a default chain that does NOT include the Down candidate — the 2026-08-25
    * kimi scenario where kimi/k3-256k lives only in the Vision preset). */
  private class FakeRegistry2(adapter: ProviderAdapter[IO], known: Set[String])
      extends ProviderRegistry(null, null):
    override def getAdapter(providerId: String): IO[ProviderAdapter[IO]] = IO.pure(adapter)
    override def getCandidateForRef(ref: String): IO[Option[ModelCandidate]] =
      if known.contains(ref) then
        ref.split("/").toList match
          case pid :: m :: Nil => IO.pure(Some(candidate(pid, m)))
          case _               => IO.pure(None)
      else IO.pure(None)

  test("probe marks Up on a successful response (empty reply OK — thinking-only)") {
    // A thinking model (GLM-5.2) can return content="" when all tokens went to
    // reasoning — with F3 the adapter treats that as success, so probe must markUp.
    val adapter = FakeAdapter(IO.pure(AdapterResponse("", Nil, None)))
    val monitor = ProviderHealthMonitor(FakeRegistry(adapter))
    val c = candidate("glm", "glm-5-107")

    for
      _ <- monitor.markDown("glm", "glm-5-107", "first-token timeout")
      _ <- monitor.probe(c)
      states <- monitor.getStates
    yield states.get("glm/glm-5-107") match
      case Some(HealthState.Up) => ()
      case other => fail(s"expected Up after successful probe, got $other")
  }

  test("probe keeps Down when the probe request fails") {
    val adapter = FakeAdapter(IO.raiseError(new RuntimeException("probe error")))
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

  // ============================================================
  // downCandidates — probe set covers ALL Down candidates
  // (2026-08-25 kimi incident: Vision-preset candidate was never
  // probed because the probe set was the default chain only)
  // ============================================================

  test("downCandidates includes Down candidates outside the default chain (kimi fix)") {
    val adapter = FakeAdapter(IO.pure(AdapterResponse("", Nil, None)))
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
      FakeRegistry2(FakeAdapter(IO.pure(AdapterResponse("", Nil, None))), Set.empty)
    )

    for
      _ <- monitor.markDown("ghost", "gone", "error")
      dc <- monitor.downCandidates()
    yield assertEquals(dc, List.empty[ModelCandidate])
  }
end HealthMonitorSpec
