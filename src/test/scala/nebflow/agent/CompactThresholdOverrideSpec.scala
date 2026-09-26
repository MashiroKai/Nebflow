package nebflow.agent

import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.Json
import munit.FunSuite
import nebflow.actor.{
  AgentState,
  CompactThresholdOverride,
  SessionContext,
  compactThresholdRatioOverride,
  compactThresholdTokens,
  effectiveCompactThresholdRatio,
  withCompactThresholdRatio,
  withContextWindow
}
import nebflow.shared.CompactThreshold
import nebflow.shared.SessionMeta

/**
 * ctxthresh 批（2026-09-15 方案 A，作者卡答「按方案A实施」＋「上限90%，下限不得
 * 小于当前上下文用量而且大于15%」）的引擎侧判据（设计 §8.1 承重钉 + §8.3 E1/E2）。
 *
 * 本 spec 是**默认值不动**这一承重钉的机械证明之一（另一半 = 既有
 * `CompactThresholdSpec` / `CompactionPolicySpec` 逐字不改仍全绿），并逐条钉住
 * 值域/钳制/持久化三面。真值源 = `CompactThresholdOverride`（`AgentState.scala`；
 * re-pin 2026-09-25：随 protocol.scala 三拆迁入，定义逐字未动）。
 */
class CompactThresholdOverrideSpec extends FunSuite:

  /** 无覆盖时的读数必须与现值函数**逐字等价**（口径② / §8.1）——双向读数第一向。 */
  test("E2 default (no override): effective读数与 CompactThreshold 现值函数逐字等价") {
    val windows = List(1000, 32000, 128000, 200000, 300000, 300001, 320000, 500000, 1000000)
    windows.foreach { w =>
      assertEquals(
        CompactThresholdOverride.effectiveThreshold(w, None),
        CompactThreshold.threshold(w),
        s"effectiveThreshold 偏离现值函数 (window=$w)"
      )
      assertEquals(
        CompactThresholdOverride.effectiveRatio(w, None),
        CompactThreshold.thresholdRatio(w),
        s"effectiveRatio 偏离现值函数 (window=$w)"
      )
    }
  }

  /** 有覆盖 ⇒ `window × r`（双向读数第二向；口径③「有会话覆盖用覆盖」）。 */
  test("E1 override: 有覆盖 ⇒ 生效门限 = (window × r).toInt（与现值函数无关）") {
    // 大窗：现值函数给 256000（固定），覆盖按比例走 ⇒ 双向可分辨
    assertEquals(CompactThresholdOverride.effectiveThreshold(1000000, Some(0.9)), 900000)
    assertEquals(CompactThresholdOverride.effectiveThreshold(1000000, Some(0.5)), 500000)
    assertEquals(CompactThresholdOverride.effectiveThreshold(1000000, Some(0.16)), 160000)
    // 小窗：现值函数给 80%，覆盖可收紧也可放宽（≤90%）
    assertEquals(CompactThresholdOverride.effectiveThreshold(128000, Some(0.16)), 20480)
    assertEquals(CompactThresholdOverride.effectiveThreshold(128000, Some(0.9)), 115200)
    // 生效比例 = 覆盖值本身（上报面与判定面同源）
    assertEquals(CompactThresholdOverride.effectiveRatio(1000000, Some(0.5)), 0.5)
    // 覆盖恒优先于现值函数（同一 window 下两者不同值）
    assertNotEquals(
      CompactThresholdOverride.effectiveThreshold(1000000, Some(0.5)),
      CompactThreshold.threshold(1000000)
    )
  }

  /** 静态值域 `15% < r ≤ 90%`（作者卡答逐字；15% 本身禁选）。 */
  test("E1 值域：15% < r ≤ 90%（边界开/闭逐字）") {
    assert(!CompactThresholdOverride.isValid(0.15), "15% 必须禁选（开区间）")
    assert(!CompactThresholdOverride.isValid(0.10))
    assert(!CompactThresholdOverride.isValid(0.0))
    assert(!CompactThresholdOverride.isValid(-0.5))
    assert(CompactThresholdOverride.isValid(0.16))
    assert(CompactThresholdOverride.isValid(0.5))
    assert(CompactThresholdOverride.isValid(0.90), "90% 必须可选（闭区间）")
    assert(!CompactThresholdOverride.isValid(0.91))
    assert(!CompactThresholdOverride.isValid(Double.NaN))
    assert(!CompactThresholdOverride.isValid(Double.PositiveInfinity))
  }

  /** 动态下限：不得小于当前上下文用量，且 > 15%（逐例读数）。 */
  test("E1 动态下限：max(当前用量, 16%)——低于当前用量 ⇒ 钳回") {
    assertEquals(CompactThresholdOverride.minRatioFor(0.0), 0.16)
    assertEquals(CompactThresholdOverride.minRatioFor(0.05), 0.16)
    assertEquals(CompactThresholdOverride.minRatioFor(0.16), 0.16)
    assertEquals(CompactThresholdOverride.minRatioFor(0.35), 0.35)
    assertEquals(CompactThresholdOverride.minRatioFor(0.62), 0.62)
    // 钳回（不是静默丢弃）：低于下限 ⇒ 抬到下限
    assertEquals(CompactThresholdOverride.clamp(0.05, 0.35), 0.35)
    assertEquals(CompactThresholdOverride.clamp(0.05, 0.0), 0.16)
    assertEquals(CompactThresholdOverride.clamp(0.351, 0.35), 0.351)
    // 上限钳制
    assertEquals(CompactThresholdOverride.clamp(0.95, 0.0), 0.90)
    assertEquals(CompactThresholdOverride.clamp(Double.NaN, 0.0), 0.16)
    assert(CompactThresholdOverride.clamp(0.15, 0.0) > CompactThresholdOverride.MinRatio)
  }

  /** AgentState 级读数：判定点唯一入口 `compactThresholdTokens`。 */
  test("E1 AgentState：compactThresholdTokens 双向读数（无覆盖=现值函数 / 有覆盖=window×r）") {
    val none = AgentState(contextWindow = 1000000, sessionId = Some("s"))
    assertEquals(none.compactThresholdRatioOverride, None)
    assertEquals(none.compactThresholdTokens, 256000)
    assertEquals(none.effectiveCompactThresholdRatio, 0.256)

    val over = none.withCompactThresholdRatio(Some(0.5))
    assertEquals(over.compactThresholdRatioOverride, Some(0.5))
    assertEquals(over.compactThresholdTokens, 500000)
    assertEquals(over.effectiveCompactThresholdRatio, 0.5)

    // 清除覆盖 ⇒ 立刻回现值函数（UI「恢复默认」的引擎语义）
    val cleared = over.withCompactThresholdRatio(None)
    assertEquals(cleared.compactThresholdTokens, 256000)
    assertEquals(cleared.effectiveCompactThresholdRatio, 0.256)

    // 换窗口后覆盖仍按比例生效（新窗口可分辨：覆盖恒优先于现值函数）
    val widened = over.withContextWindow(200000)
    assertEquals(widened.compactThresholdTokens, 100000)
    assertEquals(CompactThreshold.threshold(200000), 160000)
  }

  /** 作用域口径③：SessionContext 默认 None ⇒ 非 root spawn 构造点零改动。 */
  test("口径③ 默认：SessionContext.compactThresholdRatio 缺省为 None（非 root 面零改动）") {
    assertEquals(SessionContext().compactThresholdRatio, None)
    assertEquals(AgentState().compactThresholdRatioOverride, None)
    // 非 root spawn 不传该实参 ⇒ 恒 None ⇒ 走现值函数
    assertEquals(AgentState(contextWindow = 128000).compactThresholdTokens, CompactThreshold.threshold(128000))
  }

  /** 持久化：新键 round-trip + 老会话缺键 ⇒ None（零格式迁移，向后兼容）。 */
  test("O4 持久化：SessionMeta.compactThresholdRatio round-trip；缺键 ⇒ None") {
    val base = SessionMeta(id = "s1", name = "n", createdAt = 1L, updatedAt = 2L, hasUnread = false)
    assertEquals(base.compactThresholdRatio, None)

    val withRatio = base.copy(compactThresholdRatio = Some(0.42))
    val encoded: Json = withRatio.asJson
    assertEquals(
      encoded.hcursor.downField("compactThresholdRatio").as[Option[Double]],
      Right(Some(0.42))
    )
    assertEquals(encoded.as[SessionMeta].map(_.compactThresholdRatio), Right(Some(0.42)))

    // 老会话（无该键）——Decoder 缺省 ⇒ None ⇒ 走现值函数
    val legacy = parse("""{"id":"s1","name":"n","createdAt":1,"updatedAt":2,"hasUnread":false}""").toOption.get
    assertEquals(legacy.as[SessionMeta].map(_.compactThresholdRatio), Right(None))
    // 无覆盖时**不**写该键（盘上字节零噪声）
    assert(!base.asJson.asObject.exists(_.contains("compactThresholdRatio")))
  }

  /** 值语义 = **窗口比例**（不是绝对 token）：同一 r 在不同窗口给出不同 token。 */
  test("O2 值语义：比例（换窗口 ⇒ 生效 token 随之变，r 不漂移）") {
    val r = Some(0.5)
    assertEquals(CompactThresholdOverride.effectiveThreshold(1000000, r), 500000)
    assertEquals(CompactThresholdOverride.effectiveThreshold(200000, r), 100000)
    assertEquals(CompactThresholdOverride.effectiveRatio(200000, r), 0.5)
  }
end CompactThresholdOverrideSpec
