package nebflow.agent

import munit.FunSuite
import nebflow.actor.AgentDef
import nebflow.core.tools.ExternalToolConfig
import nebflow.core.tools.ScriptTool
import nebflow.core.tools.ToolRegistry
import nebflow.llm.ToolLoadMode
import nebflow.shared.AgentModelConfig

/**
 * 动态工具装载的**装配面**验证（dynload 批 2026-11-19）：证明 `buildToolPlan`
 * （= `buildToolList` 的唯一实现）真的把插件授予的工具按腿档分流——不是只有模块
 * 单测，而是装配面接线也成立：
 *
 *   · ExecutionGate 腿（活跃腿 deepseek）：plugin 工具**不上 wire**，但**仍在授权集**
 *     （执行门）且**产尾部通告**；
 *   · Deferred 腿（claude tool-search 模型）：plugin 工具带上 `defer_loading` 进入 wire，
 *     不产通告；
 *   · 无插件授予：零变化。
 */
class DynamicToolLoadWiringSpec extends FunSuite:

  private object CoreProbe extends AgentCore:
    def planFor(defn: AgentDef, isFlowNode: Boolean = false): nebflow.llm.DynamicToolLoad.Plan =
      buildToolPlan(defn, isFlowNode = isFlowNode)

    def allowed(defn: AgentDef, isFlowNode: Boolean = false): Set[String] =
      buildAllowedToolSet(defn, isFlowNode = isFlowNode)

  private val probe = "mcp__plugin_dynload_probe__probe_tool"

  private def register(): Unit =
    ToolRegistry.registerTool(
      new ScriptTool(
        ExternalToolConfig(
          name = probe,
          description = "dynload wiring probe",
          command = "true",
          inputSchema = io.circe.JsonObject.empty
        ),
        os.pwd
      )
    )

  private def pluginDef(model: Option[String]): AgentDef =
    AgentDef(name = "general", description = "", tools = Nil, category = "standalone")
      .copy(
        pluginMcpServers = List("plugin_dynload_probe"),
        model = model.map(m => AgentModelConfig(preferred = Some(m)))
      )

  test("wiring: ExecutionGate 腿 plugin 工具不上 wire（仍在授权集+通告）；Deferred 腿带 defer 上 wire"):
    register()
    try
      // ── ExecutionGate（活跃腿模型）──────────────────────────────────
      val execPlan = CoreProbe.planFor(pluginDef(Some("deepseek/deepseek-v4-flash")))
      assertEquals(execPlan.event.mode, ToolLoadMode.ExecutionGate)
      assert(
        CoreProbe.allowed(pluginDef(Some("deepseek/deepseek-v4-flash"))).contains(probe),
        "执行门必须仍授权该工具（授权判在执行门）"
      )
      assert(!execPlan.wireTools.exists(_.name == probe), "ExecutionGate：plugin 工具不得上 wire（前缀不变量）")
      assert(execPlan.advertisedTools.exists(_.name == probe), "ExecutionGate：必须产尾部通告")
      assert(
        nebflow.llm.DynamicToolLoad.advertisementText(execPlan.advertisedTools).contains(probe),
        "通告正文须点名该工具"
      )

      // ── Deferred（claude tool-search 模型）──────────────────────────
      val defPlan = CoreProbe.planFor(pluginDef(Some("claude-sonnet-5-5")))
      assertEquals(defPlan.event.mode, ToolLoadMode.Deferred)
      val onWire = defPlan.wireTools.find(_.name == probe)
      assert(onWire.isDefined, "Deferred：plugin 工具须在 wire 上")
      assert(onWire.get.deferLoading, "Deferred：必须带 deferred 标记")
      assertEquals(defPlan.advertisedTools, Nil, "Deferred：不产尾部通告")

      // ── 无插件授予 ⇒ 零变化 ─────────────────────────────────────────
      val none = CoreProbe.planFor(AgentDef(name = "general", description = "", tools = Nil))
      assert(!none.wireTools.exists(_.name == probe))
      assertEquals(none.advertisedTools, Nil)
    finally ToolRegistry.unregisterTool(probe)

  test("撤权竞态：通告不是授权——旧通告仍在场，执行门已撤权 ⇒ 门拒绝（防发现后撤销）"):
    register()
    try
      val granted = pluginDef(Some("deepseek/deepseek-v4-flash"))
      val planWithGrant = CoreProbe.planFor(granted)
      assert(planWithGrant.advertisedTools.exists(_.name == probe), "撤权前：通告在场")
      // 撤权 = 运行时 AgentDef 的 pluginMcpServers 不再含该 server（NodeEngine 的
      // 授予面）；执行门重评面（allowed）按当前 def 判 —— 通告文本不是授权凭据。
      val revoked = granted.copy(pluginMcpServers = Nil)
      assert(
        !CoreProbe.allowed(revoked).contains(probe),
        "撤权后执行门必须拒绝该工具（旧通告在场 ≠ 授权；双保险的第二半）"
      )
    finally ToolRegistry.unregisterTool(probe)

end DynamicToolLoadWiringSpec
