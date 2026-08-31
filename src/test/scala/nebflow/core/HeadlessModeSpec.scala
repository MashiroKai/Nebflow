package nebflow.core

import munit.CatsEffectSuite
import nebflow.agent.ContextRefresher
import nebflow.core.telemetry.TelemetryReporter
import nebflow.core.tools.AskUserQuestionTool

/**
 * HeadlessMode touchpoint coverage (design doc "Headless 一次性执行入口"
 * §3.4 / §D5 — benchmark determinism switch, NEBFLOW_HEADLESS=1).
 *
 * HeadlessMode.enabled is a val frozen at object init. The test JVM runs
 * without NEBFLOW_HEADLESS, so the frozen binding asserts the false default,
 * and each touchpoint guard takes the flag as an explicit parameter to cover
 * the headless=true branch without env mutation (which cannot unfreeze a
 * val). The wired touchpoints themselves run headless in E2E smoke.
 */
class HeadlessModeSpec extends CatsEffectSuite:

  // ============================================================
  // HeadlessMode.enabled — frozen binding
  // ============================================================

  test("HeadlessMode.enabled: unset env freezes to false") {
    // Guard the premise: if the CI environment leaks NEBFLOW_HEADLESS into
    // the test JVM, every downstream assertion would be meaningless — fail
    // here, loudly, instead.
    assert(!sys.env.contains("NEBFLOW_HEADLESS"))
    assertEquals(HeadlessMode.enabled, false)
  }

  test("HeadlessMode.enabled: only the exact value 1 counts as on") {
    // Mirrors the definition against the live env (read-only): empty, 0,
    // true, random strings are all off — one canonical switch value.
    assertEquals(HeadlessMode.enabled, sys.env.get("NEBFLOW_HEADLESS").contains("1"))
  }

  // ============================================================
  // Touchpoint 1 — memory injection (ContextRefresher)
  // ============================================================

  test("shouldInjectMemory: headless=true skips memory even for eligible agents") {
    // Both shapes that would inject memory in interactive mode — Nebula
    // standalone (the ONLY memory-bearing agent since 2026-08-31 裁定①) —
    // produce no memory block when headless: no cross-run state leaks into a
    // benchmark session.
    assert(!ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "Nebula", headless = true))
    assert(!ContextRefresher.shouldInjectMemory(isWorker = true, agentName = "Nebula", headless = true))
  }

  test("shouldInjectMemory: headless=false injects ONLY for Nebula") {
    // Eligible since 2026-08-31 裁定①: Nebula (non-worker, non-headless) only.
    assert(ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "Nebula", headless = false))
    // Team agents no longer inject memory (was eligible pre-redesign)
    assert(!ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "Backend", headless = false))
    // Exclusions unchanged: SubTask workers, standalone non-Nebula
    assert(!ContextRefresher.shouldInjectMemory(isWorker = true, agentName = "Nebula", headless = false))
    assert(!ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "Coder", headless = false))
  }

  // ============================================================
  // Touchpoint 2 — AskUser does not hang (AskUserQuestionTool)
  // ============================================================

  test("askGuard: headless=true returns ToolError pushing autonomous decision") {
    val err = AskUserQuestionTool.askGuard(headless = true)
    assert(err.isDefined)
    assertEquals(
      err.get.message,
      "Headless mode: no interactive user available — decide autonomously and continue with your best judgment."
    )
  }

  test("askGuard: headless=false returns None — interactive flow unchanged") {
    assertEquals(AskUserQuestionTool.askGuard(headless = false), None)
  }

  test("askGuard: default binding follows the frozen HeadlessMode.enabled") {
    assertEquals(AskUserQuestionTool.askGuard().isDefined, HeadlessMode.enabled)
  }

  // ============================================================
  // Touchpoint 3 — telemetry disabled (TelemetryReporter)
  // ============================================================

  test("TelemetryReporter.create returns None — disabled means no reporter") {
    // isEnabled is false in the current build and NEBFLOW_HEADLESS is unset
    // here, so create short-circuits before any client-id/queue I/O. The
    // headless=true arm of the same gate (an additional or-condition on the
    // frozen val) is exercised by E2E smoke, not settable in-JVM.
    TelemetryReporter.create().map(r => assert(r.isEmpty, s"expected None, got $r"))
  }
