package nebflow.gateway

import munit.FunSuite

/**
 * 第六件 QC (2026-08-30): the AskUser chat-input passthrough is WS-input-box
 * ONLY. The headless REST turn entry ("rest-turn", P0 benchmark path) must
 * never probe — a benchmark POST would otherwise silently answer a pending
 * card and make the benchmark non-deterministic.
 *
 * (The timeout fallback of the passthrough wait lives in handleUserText on
 * the instance side and is not directly unit-testable — WebSocketRoutes
 * needs the full gateway stack. The gate itself is the whole behavioral
 * delta of this fix and is pinned here.)
 */
class UserTextGateSpec extends FunSuite:

  test("WS input-box sources probe the passthrough") {
    assertEquals(WebSocketRoutes.probesPassthrough("userMessage"), true)
    assertEquals(WebSocketRoutes.probesPassthrough("immediateInput"), true)
  }

  test("rest-turn never probes — headless POSTs stay deterministic") {
    assertEquals(WebSocketRoutes.probesPassthrough("rest-turn"), false)
  }

  test("unknown sources probe (only the explicit REST entry is gated off)") {
    assertEquals(WebSocketRoutes.probesPassthrough(""), true)
    assertEquals(WebSocketRoutes.probesPassthrough("anything-else"), true)
  }

end UserTextGateSpec
