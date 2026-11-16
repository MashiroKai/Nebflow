package nebflow.core.plugin

import munit.FunSuite
import PluginLifecyclePlan.*

/**
 * 统一生命周期计划（插件分发与生命周期协议升级草案 §6.3 + §9 A 行「把已有安装
 * 更新入口接到统一计划」）：planId 绑定 操作 | 包名 | 候选摘要 | 当前摘要 | 策略版本
 * ——任一变化 ⇒ id 变化；同输入 ⇒ 确定性相等。§7 输出面的 planId 由此派生。
 */
class PluginLifecyclePlanSpec extends FunSuite:

  private val base = Plan(
    operation = Operation.Update,
    name = "stand-dev",
    candidateVersion = "1.5.0",
    candidateDigest = "a" * 64,
    currentVersion = Some("1.4.0"),
    currentDigest = Some("b" * 64),
    policyVersion = "plugin-ledger@2:check"
  )

  test("planId 确定性：同输入 ⇒ 相等；面内任一绑定值变化 ⇒ id 变化"):
    assertEquals(planId(base), planId(base.copy()), "同输入必须确定性相等")
    assertNotEquals(planId(base), planId(base.copy(candidateDigest = "c" * 64)), "候选摘要变化 ⇒ 不同计划")
    assertNotEquals(planId(base), planId(base.copy(currentDigest = None)), "当前摘要缺席（首装 vs 升级）⇒ 不同计划")
    assertNotEquals(planId(base), planId(base.copy(policyVersion = "plugin-ledger@2:minor")), "策略版本变化 ⇒ 不同计划")
    assertNotEquals(planId(base), planId(base.copy(operation = Operation.Install)), "操作不同 ⇒ 不同计划")
    assertNotEquals(planId(base), planId(base.copy(name = "stand-dev-2")), "包名不同 ⇒ 不同计划")

  test("planId 形态稳定：前缀 + 16 hex（日志/审计行可读可截）"):
    val id = planId(base)
    assert(id.startsWith("plan-"), s"带 plan- 前缀, got: $id")
    assert(id.drop(5).length == 16, s"16 hex 截断形态, got: $id")
    assert(id.drop(5).forall(c => c >= '0' && c <= '9' || c >= 'a' && c <= 'f'), s"hex only, got: $id")

  test("render 一行审计形态：操作/包名/版本对/摘要前缀/策略版本齐备"):
    val line = PluginLifecyclePlan.render(base)
    assert(line.contains("update"))
    assert(line.contains("stand-dev"))
    assert(line.contains("1.4.0") && line.contains("1.5.0"))
    assert(line.contains("aaaa")) // 候选摘要前缀（可读截断）
    assert(line.contains("plugin-ledger@2:check"))
    // 首装形态（无当前版本）不出现 "→" 版本对退化
    val install =
      PluginLifecyclePlan.render(base.copy(operation = Operation.Install, currentVersion = None, currentDigest = None))
    assert(install.contains("install"))

end PluginLifecyclePlanSpec
