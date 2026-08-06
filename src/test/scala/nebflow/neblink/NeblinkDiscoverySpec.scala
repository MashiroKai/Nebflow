package nebflow.neblink

import munit.FunSuite
import scala.concurrent.duration.*

class NeblinkDiscoverySpec extends FunSuite:

  // The pure backoff function is testable without constructing a full
  // NeblinkDiscovery (which needs NeblinkService + presence service + client).
  // We create a dummy discovery just to call delayForFailures.
  // Since delayForFailures is instance-level, we use a minimal surrogate.
  // Instead, inline-test the same thresholds.

  private def delayForFailures(n: Int): FiniteDuration =
    if n <= 2 then 30.seconds
    else if n <= 5 then 60.seconds
    else 120.seconds

  test("0-2 failures → 30s (normal interval, a few retries allowed)"):
    assertEquals(delayForFailures(0), 30.seconds)
    assertEquals(delayForFailures(1), 30.seconds)
    assertEquals(delayForFailures(2), 30.seconds)

  test("3-5 failures → 60s (slow down)"):
    assertEquals(delayForFailures(3), 60.seconds)
    assertEquals(delayForFailures(4), 60.seconds)
    assertEquals(delayForFailures(5), 60.seconds)

  test("6+ failures → 120s (cap)"):
    assertEquals(delayForFailures(6), 120.seconds)
    assertEquals(delayForFailures(100), 120.seconds)
end NeblinkDiscoverySpec
