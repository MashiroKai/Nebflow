package nebflow.shared

import munit.FunSuite

import scala.jdk.CollectionConverters.*

/** 时间基修正纯函数面单测（hostresume 批 2026-09-22，设计卡 §4 #1 / §6 口径 1、2、6）。
  *
  * 覆盖：空窗集逐字节等价（口径 1 的数学半边）、合成睡眠窗扣减 + 红绿对照（口径 2）、
  * 交集边界与钳制、窗集修剪、实例隔离、kill-switch 全局闸（口径 6 的消费侧半边）。
  */
class PowerStateMathSpec extends FunSuite:

  private val S = 1000L // 1s in ms

  override def afterEach(context: munit.AfterEach): Unit =
    // 全局窗集复位（本 spec 在 shared 包内，可用测试专用复位；生产零调用点）。
    PowerStateTracker.resetGlobalForTest()
    sys.props.remove("nebflow.wake.sense.enabled")
    super.afterEach(context)

  // ── 口径 1：空窗集 ⇒ 修正量恒为零，返回值与裸差值逐字节相等 ────────────────

  test("T1 空窗集恒等：effectiveElapsed(Nil,…) == 裸差值（含正差值）") {
    assertEquals(PowerStateMath.effectiveElapsed(Nil, 1_000L, 911_000L), 910_000L)
    assertEquals(PowerStateMath.effectiveElapsed(Nil, 0L, Long.MaxValue), Long.MaxValue)
  }

  test("T2 空窗集恒等：时钟回拨负值逐字节透传（消费点落「不判」分支，与现状同判）") {
    assertEquals(PowerStateMath.effectiveElapsed(Nil, 2_000L, 1_000L), -1_000L)
    assertEquals(PowerStateMath.frozenOverlapMs(Nil, 2_000L, 1_000L), 0L)
  }

  // ── 口径 2：合成睡眠窗 [t−900s, t] ⇒ wall idle 910s ⇒ effective 10s ─────────

  test("T3 合成窗扣减：910s 墙钟跨 900s 窗 ⇒ effective 10s（绿臂）") {
    val now = 10_000_000L
    val windows = List(SleepWindow(now - 900 * S, now))
    assertEquals(PowerStateMath.effectiveElapsed(windows, now - 910 * S, now), 10 * S)
  }

  test("T4 红绿对照：同跨度无窗 ⇒ 910s（无修正 = 现状必判形态）") {
    val now = 10_000_000L
    assertEquals(PowerStateMath.effectiveElapsed(Nil, now - 910 * S, now), 910 * S)
  }

  // ── 交集算术边界 ─────────────────────────────────────────────────────────────

  test("T5 交集边界：窗两端越界 / 相离 / 端点相切 各归其位") {
    val start = 1_000_000L
    val now = 2_000_000L
    // 窗完全覆盖跨度 ⇒ 钳 ≥0
    assertEquals(
      PowerStateMath.effectiveElapsed(List(SleepWindow(start - 5_000L, now + 5_000L)), start, now), 0L)
    // 窗与跨度相离 ⇒ 恒等
    assertEquals(
      PowerStateMath.effectiveElapsed(List(SleepWindow(now + 1L, now + 9_999L)), start, now), now - start)
    // 端点相切（hi == lo）⇒ 贡献 0
    assertEquals(
      PowerStateMath.effectiveElapsed(List(SleepWindow(now, now + 9_999L)), start, now), now - start)
    assertEquals(
      PowerStateMath.effectiveElapsed(List(SleepWindow(start - 9_999L, start)), start, now), now - start)
    // 部分交叠：窗 [start−100, start+400] ⇒ 扣 500... 精确 = [start, start+400] = 400
    assertEquals(
      PowerStateMath.effectiveElapsed(List(SleepWindow(start - 100L, start + 400L)), start, now), now - start - 400L)
  }

  test("T6 多窗求和 + 重叠窗钳制不返回负值") {
    val start = 0L
    val now = 1_000_000L
    val two = List(SleepWindow(100_000L, 200_000L), SleepWindow(500_000L, 700_000L))
    assertEquals(PowerStateMath.effectiveElapsed(two, start, now), now - 300_000L)
    // 输入重叠窗（结构性不该发生；防御面）⇒ frozenOverlap 多计，但 effective 钳 ≥0
    val overlap = List(SleepWindow(0L, 800_000L), SleepWindow(100_000L, 900_000L))
    assertEquals(PowerStateMath.effectiveElapsed(overlap, start, now), 0L)
  }

  // ── Tracker 实例语义 ────────────────────────────────────────────────────────

  test("T7 实例隔离：两实例窗集互不可见") {
    val a = new PowerStateTracker()
    val b = new PowerStateTracker()
    a.register(SleepWindow(0L, 900 * S))
    assertEquals(a.effectiveElapsed(0L, 910 * S), 10 * S)
    assertEquals(b.effectiveElapsed(0L, 910 * S), 910 * S) // b 空窗集 = 现状
  }

  test("T8 窗集修剪：超保留期旧窗被裁、总量钳在 MaxWindows") {
    val t = new PowerStateTracker()
    val now = 10L * 24 * 60 * 60 * 1000L // 10 天前纪元
    // 200 条极老窗（wakeAt 远超保留期界）+ 1 条新窗 ⇒ 只留新窗
    t.register(SleepWindow(0L, 1_000L))
    (1 to 200).foreach(i => t.register(SleepWindow(now + i * 1000L, now + i * 1000L + 500L)))
    assertEquals(t.windows.size, PowerStateTracker.MaxWindows)
    assert(t.windows.forall(_.wakeAtMs >= now), "超龄窗必须被修剪")
    // 最老被裁掉：0L/1000L 那条已不在
    assert(!t.windows.exists(_.sleepAtMs == 0L))
  }

  // ── 口径 6（消费侧半边）：kill-switch 闸 ────────────────────────────────────

  test("T9 kill-switch：nebflow.wake.sense.enabled=false ⇒ 全局入口恒等回裸差值（窗集有残留也不修正）") {
    val now = 10_000_000L
    try
      sys.props.put("nebflow.wake.sense.enabled", "true")
      PowerStateTracker.registerSleepWindow(now - 900 * S, now)
      assertEquals(PowerStateTracker.effectiveElapsed(now - 910 * S, now), 10 * S)
      // 开关切 false（每次调用现读，可即时翻转）⇒ 恒等，即使窗集残留
      sys.props.put("nebflow.wake.sense.enabled", "false")
      assertEquals(PowerStateTracker.effectiveElapsed(now - 910 * S, now), 910 * S)
      // 恒等面同帧核对双向：start<now 正差值 + start>now 负差值（回拨）逐字节透传
      assertEquals(PowerStateTracker.effectiveElapsed(now - 910 * S, now - 900 * S), 10 * S)
      assertEquals(PowerStateTracker.effectiveElapsed(now - 900 * S, now - 910 * S), -10 * S)
    finally
      sys.props.remove("nebflow.wake.sense.enabled")
      PowerStateTracker.resetGlobalForTest()
  }

  test("T10 默认（无 prop）= 开启：全局入口按窗集修正") {
    val now = 10_000_000L
    try
      assert(sys.props.get("nebflow.wake.sense.enabled").isEmpty, "前置：prop 未设")
      PowerStateTracker.registerSleepWindow(now - 900 * S, now)
      assertEquals(PowerStateTracker.effectiveElapsed(now - 910 * S, now), 10 * S)
    finally
      PowerStateTracker.resetGlobalForTest()
  }
