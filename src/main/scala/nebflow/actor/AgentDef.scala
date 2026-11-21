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
  // 2026-11-21 作者令停教：显示/TTS 面封存期间默认为 false（原 true）——
  // 防内置/程序化 agent 走非 agent.json 路径再产出 `<voice>` 块。恢复语音功能时
  // 连同 PromptSections.voiceTeachingParked 一并翻回。
  voiceEnabled: Boolean = false,
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
