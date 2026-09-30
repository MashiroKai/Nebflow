package nebflow.actor

import nebflow.shared.AgentModelConfig

/**
 * Per-node output contract for flow agents (R8-P1).
 *
 * Built by FlowDagExecutor from the node's flow.json declaration — case keys
 * of its Switch onComplete + the `outputs` slot schema — and attached to the
 * spawned agent's AgentDef. Historical consumers (both retired with the
 * FlowReport tool, 2026-09-06 工具面裁撤批): the buildToolList describe
 * append and the FlowReportTool.call contract validation. The contract data
 * itself is still injected by the engine (zero-touch line) and kept for
 * stage-3 engine-side data migration.
 */
case class FlowNodeContract(
  caseKeys: Set[String] = Set.empty, // non-empty ⇔ this node routes via a Switch
  slots: Map[String, String] = Map.empty // slot name -> "string" | "array"
):

  /** Human/LLM-readable contract block appended to the FlowReport description. */
  def describe: String =
    val verdictLine =
      if caseKeys.isEmpty then "- verdict: no switch routing on this node — report \"done\"."
      else s"- verdict: MUST be exactly one of: ${caseKeys.toList.sorted.mkString(" | ")}"
    val slotsLines =
      if slots.isEmpty then Nil
      else
        List(
          "- slots: report these typed fields in the slots parameter (exactly these keys, no extras):"
        ) ++ slots.toList.sortBy(_._1).map { (k, t) => s"    - $k ($t)" }
    (List("## This node's contract (authoritative — validated on call)", verdictLine) ++ slotsLines)
      .mkString("\n")

end FlowNodeContract

/**
 * Runtime agent definition.
 *
 * All fields are resolved from code ([[AgentLibrary.defaults]]), not from disk.
 * The system prompt may be overridden by a user-edited system.md file.
 * contextWindow is resolved at runtime from nebflow.json.
 */
case class AgentDef(
  name: String,
  description: String,
  tools: List[String] = Nil,
  systemPrompt: String = "",
  avatar: Option[String] = None,
  displayName: Option[String] = None,
  voiceEnabled: Boolean = true,
  model: Option[AgentModelConfig] = None,
  preset: Option[String] = None, // legacy stored reference; audit-only, not read by the engine
  category: String = "standalone",
  mcpServers: List[String] = Nil,
  skills: List[String] = Nil, // skill names this agent can see
  flows: List[String] = Nil, // flow names this agent can trigger
  // Flow-node agents only: the node's output contract (verdict enum + slots
  // schema) injected by FlowDagExecutor. None for all other agents.
  flowContract: Option[FlowNodeContract] = None,
  // 阶段 2b Plugins（§B.4 第 4 步）：node 分配 plugin 后由 NodeEngine 在 spawn 时
  // 注入（entry.toAgentDef 之后的 copy）——仅存在于该会话的运行时 AgentDef，不落
  // agent.json。pluginMcpServers = plugin MCP serverId（`plugin_<p>_<s>`），经
  // buildAllowedToolSet MCP 过滤段追加前缀来源；pluginTools = org.nebflow/tools
  // 授予的 builtin 工具名（白名单校验在 PluginRegistry 装载层，此处再过滤一次）。
  pluginMcpServers: List[String] = Nil,
  pluginTools: List[String] = Nil
)

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-F):本常量自 agent.AgentCore 下沉
// actor(纯 String 常量;AgentCore 不留转发别名,shim 禁令),全仓改指,实现与注释逐字迁移。
object AgentDef:

  /**
   * 记忆整理 agent 定义名（spec §5 R5 O-A；seed = `src/main/resources/seed/agents/
   * <name>/`，运行时 `~/.nebflow/agents/<name>/`）。压缩双轨的第二轨按此名解析
   * def——名字缺失 ⇒ 轨失败降级（照常装机，队列保留）。
   * （六族退役批 2026-09-29：原注释内的轨符号链接已随轨本体退役删除；本常量
   * 自身的收口归 e5-memconsolidator 批，本批一字不动。）
   */
  val MemoryConsolidatorName = "memory-consolidator"

end AgentDef
