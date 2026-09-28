package nebflow.core.compact

import munit.FunSuite

class CompactThresholdSpec extends FunSuite:

  // V1: large-context model (>300k) → fixed 256k
  test("V1: threshold(320000) == 256000") {
    assertEquals(CompactThreshold.threshold(320000), 256000)
  }

  // V2: mid-context model (≤300k) → 80%
  test("V2: threshold(128000) == 102400") {
    assertEquals(CompactThreshold.threshold(128000), 102400)
  }

  // V3: small-context model → 80%
  test("V3: threshold(32000) == 25600") {
    assertEquals(CompactThreshold.threshold(32000), 25600)
  }

  test("boundary: exactly 300000 uses 80% (not fixed)") {
    assertEquals(CompactThreshold.threshold(300000), 240000)
  }

  test("boundary: 300001 switches to fixed 256000") {
    assertEquals(CompactThreshold.threshold(300001), 256000)
  }

  test("thresholdRatio is consistent with threshold") {
    assertEquals(CompactThreshold.thresholdRatio(128000), 0.8)
    assertEquals(CompactThreshold.thresholdRatio(320000), 256000.0 / 320000)
  }
end CompactThresholdSpec
