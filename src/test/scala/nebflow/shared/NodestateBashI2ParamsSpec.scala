package nebflow.shared

import munit.FunSuite

/**
 * nodestate-bash 批 **I2「参数与开关层」** 读取单测 + 正控（2026-09-14）。
 *
 * 设计件（唯一权威）：`~/.nebflow/docs/Nebflow/20260914_075109_nodestate-bash-terminal-redesign__chain-n-6ff25c31.md`
 * ——§4.2（自动转后台）/ §4.3.3 A1（豁免预算）/ §4.4.1 T1–T8 / §4.4.2 G1–G3 / §7.2 O1–O7。
 *
 * 本 spec 钉三件（对应 I2 验收判据 ②③）：
 *   ① **默认值逐项**：新增腿 = 设计件逐字给定值（§4.2/O1 = 300000、A1/O2 = 600000、
 *      O5 = 120000、T8 = 1800000、G2 = 300、G3 = 900）；既存现网阈值 T3/T5/T6/T7 =
 *      **旧行为现行取值**（600000 / 300 / 30 / 10000000）——🔴 I2 禁上调，设计件提案值
 *      （1800s / 900s / 300s / 1e9）的翻值归 I5；
 *   ② **正控（现读）**：翻转 prop ⇒ **下一次读取**即为新值（证明取值点是
 *      `sys.props.getOrElse` 每次调用现读，不是启动/对象初始化时的快照）；
 *   ③ **清场**：移除 prop ⇒ 回到默认（防跨 suite 全局污染）。
 *
 * 🔴 I2 全层在 I2 内**零消费点**（静态断言 =
 * `.nebflow/tools/20260914_nodestate-i2-params-check.sh` 的 §3）⇒ 本 spec 只验
 * 「参数层可读 / 可翻转 / 默认不变」，**不验任何行为变化**——「零行为变化」的结构性
 * 依据是「无消费点」，不是靠断言凑（判据①由该静态断言承载）。
 */
class NodestateBashI2ParamsSpec extends FunSuite:

  /** 本批 prop 名总表（清场用；防跨 suite 泄漏影响其它 suite）。 */
  private val i2Props: List[String] = List(
    "nebflow.shell.autoBackgroundMs", // 新参 ForegroundAutoBackgroundMs
    "nebflow.shell.sleepExemptBudgetMs", // 新参 ForegroundSleepExemptBudgetMs
    "nebflow.noderpt.reportFreezeGraceMs", // 新参 ReportFreezeGraceMs
    "nebflow.noderpt.deadSessionBgWaitGraceMs", // 新参 DeadSessionBgWaitGraceMs（T8）
    "nebflow.stuck.suspectTickSec", // 新参 StuckSuspectTickSec（G2）
    "nebflow.stuck.demandTickSec", // 新参 StuckDemandTickSec（G3）
    "nebflow.stuck.thresholdMs", // T3 prop 面（I2 新加）
    "nebflow.shell.bgIdleTimeoutSec", // T5 prop 面（I2 新加）
    "nebflow.shell.stuckDetectionGraceSec", // T6 prop 面（I2 新加）
    "nebflow.shell.cpuActiveThresholdNanos" // T7 prop 面（I2 新加）
  )

  override def afterAll(): Unit = i2Props.foreach(p => sys.props.remove(p))

  /** 正控装置（Long）：默认 → 翻转 → 现读生效 → 清场 → 回默认。 */
  private def longFace(prop: String, read: () => Long, default: Long, flipped: Long): Unit =
    assertEquals(read(), default, s"$prop 默认值必须 = 本层声明值")
    sys.props(prop) = flipped.toString
    try
      assertEquals(read(), flipped, s"$prop 翻转 prop 后必须**现读**生效（正控）")
    finally
      sys.props.remove(prop)
    assertEquals(read(), default, s"$prop 清场后必须回到默认")

  /** 正控装置（Int）。 */
  private def intFace(prop: String, read: () => Int, default: Int, flipped: Int): Unit =
    assertEquals(read(), default, s"$prop 默认值必须 = 本层声明值")
    sys.props(prop) = flipped.toString
    try
      assertEquals(read(), flipped, s"$prop 翻转 prop 后必须**现读**生效（正控）")
    finally
      sys.props.remove(prop)
    assertEquals(read(), default, s"$prop 清场后必须回到默认")

  // ── 一、本批新增腿的参数（默认值 = 设计件逐字给定值；`≤ 0` = 关闭本腿）──────────

  test("ForegroundAutoBackgroundMs（设计 §4.2 / O1）：默认 300000 = 5min，prop 现读") {
    longFace(
      "nebflow.shell.autoBackgroundMs",
      () => Defaults.ForegroundAutoBackgroundMs,
      300_000L,
      1_234L
    )
  }

  test("ForegroundSleepExemptBudgetMs（设计 §4.3.3 A1 / O2）：默认 600000 = 10min，prop 现读") {
    longFace(
      "nebflow.shell.sleepExemptBudgetMs",
      () => Defaults.ForegroundSleepExemptBudgetMs,
      600_000L,
      2_345L
    )
  }

  test("ReportFreezeGraceMs（设计 §4.1 出口 B / O5）：默认 120000 = 4×TtlTick，prop 现读") {
    longFace(
      "nebflow.noderpt.reportFreezeGraceMs",
      () => Defaults.ReportFreezeGraceMs,
      120_000L,
      3_456L
    )
  }

  test("DeadSessionBgWaitGraceMs（设计 §4.4.1 T8）：默认 1800000 = 30min，prop 现读") {
    longFace(
      "nebflow.noderpt.deadSessionBgWaitGraceMs",
      () => Defaults.DeadSessionBgWaitGraceMs,
      1_800_000L,
      4_567L
    )
  }

  test("StuckSuspectTickSec（设计 §4.4.2 G2）：默认 300s，prop 现读") {
    intFace("nebflow.stuck.suspectTickSec", () => Defaults.StuckSuspectTickSec, 300, 11)
  }

  test("StuckDemandTickSec（设计 §4.4.2 G3）：默认 900s，prop 现读") {
    intFace("nebflow.stuck.demandTickSec", () => Defaults.StuckDemandTickSec, 900, 22)
  }

  // ── 二、T1–T8 的 prop 面：I2 新加的四项，默认值 = 旧行为现行取值（禁上调）────────

  test("T3 StuckThresholdMs：默认 600000 = 旧行为现行取值（提案 1800s 归 I5），prop 现读") {
    longFace("nebflow.stuck.thresholdMs", () => Defaults.StuckThresholdMs, 600_000L, 5_678L)
  }

  test("T5 BgIdleTimeoutSec：默认 300 = 旧行为现行取值（提案 900s 归 I5），prop 现读") {
    intFace("nebflow.shell.bgIdleTimeoutSec", () => Defaults.BgIdleTimeoutSec, 300, 33)
  }

  test("T6 StuckDetectionGraceSec：默认 30 = 旧行为现行取值（提案 300s 归 I5），prop 现读") {
    intFace("nebflow.shell.stuckDetectionGraceSec", () => Defaults.StuckDetectionGraceSec, 30, 44)
  }

  test("T7 CpuActiveThresholdNanos：默认 10000000 = 10ms/窗 = 旧行为现行取值（提案 1e9 归 I5），prop 现读") {
    longFace(
      "nebflow.shell.cpuActiveThresholdNanos",
      () => Defaults.CpuActiveThresholdNanos,
      10_000_000L,
      6_789L
    )
  }

  // ── 三、既有 prop 面（T1/T2/T4）现值复核：I2 **零改动**，默认值仍 = 旧行为 ───────
  //
  // 这三项在 I2 之前就已是现读 prop（Defaults.scala 内 `sys.props.getOrElse`），
  // I2 不触碰它们（只在本层索引里登记 file:line）。其默认值断言走 `assume` 守卫：
  // sbt 同 JVM 并行跑 suite 时，兄弟 suite 可能正持有同名 prop ⇒ 现值断言不可靠
  // （静态断言见 tools 脚本 §2，不受并发影响）。

  test("T1 ForegroundNoProgressTimeoutMs：默认 600000 = 旧行为（I2 零改动）") {
    assume(
      sys.props.get("nebflow.shell.foregroundNoProgressTimeoutMs").isEmpty,
      "T1 prop 被并发 suite 置位 ⇒ 跳过现值断言（静态断言见 tools 脚本）"
    )
    assertEquals(Defaults.ForegroundNoProgressTimeoutMs, 600_000L)
  }

  test("T2 ToolPhaseStuckMs：默认 600000 = 旧行为（I2 零改动）") {
    assume(sys.props.get("nebflow.stuck.toolPhaseMs").isEmpty, "T2 prop 被并发 suite 置位 ⇒ 跳过现值断言")
    assertEquals(Defaults.ToolPhaseStuckMs, 600_000L)
  }

  test("T4 SessionKickIdleSec：默认 150 = 旧行为（I2 零改动）") {
    assume(
      sys.props.get("nebflow.hardRecovery.kickIdleSec").isEmpty,
      "T4 prop 被并发 suite 置位 ⇒ 跳过现值断言"
    )
    assertEquals(Defaults.SessionKickIdleSec, 150)
  }

end NodestateBashI2ParamsSpec
