package nebflow.llm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.*

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
      result1 <- monitor.waitForAnyUp().timeoutTo(500.millis, IO.pure("timeout"))
      _ = assertEquals(result1, "timeout")
      // Mark up — now waitForAnyUp should complete
      _ <- monitor.markUp("glm", "glm-5")
      // Race again — wait should return immediately now
      result2 <- monitor.waitForAnyUp().timeoutTo(500.millis, IO.pure("timeout"))
    yield assert(result2 == (), s"expected unit, got $result2")
  }

  test("waitForAnyUp returns immediately if a signal was already fired") {
    val monitor = newMonitor

    for
      _ <- monitor.markDown("glm", "glm-5", "down")
      _ <- monitor.markUp("glm", "glm-5") // fires signal
      _ <- monitor.waitForAnyUp() // should not block
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
end HealthMonitorSpec
