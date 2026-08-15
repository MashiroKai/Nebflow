package nebflow.agent

import nebflow.shared.AgentModelConfig

/**
 * Per-node output contract for flow agents (R8-P1).
 *
 * Built by FlowDagExecutor from the node's flow.json declaration — case keys
 * of its Switch onComplete + the `outputs` slot schema — and attached to the
 * spawned agent's AgentDef. Consumed in two places:
 *  - AgentCore.buildToolList appends [[describe]] to the FlowReport tool
 *    description, so the agent knows its verdict enum up front;
 *  - FlowReportTool.call validates verdict membership and slot types at call
 *    time, returning a ToolError the agent can self-correct in the same turn.
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
 * contextWindow / maxTokens are resolved at runtime from nebflow.json.
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
  preset: Option[String] = None, // references a named preset in model-presets.json
  category: String = "standalone",
  mcpServers: List[String] = Nil,
  skills: List[String] = Nil, // skill names this agent can see
  flows: List[String] = Nil, // flow names this agent can trigger
  // Flow-node agents only: the node's output contract (verdict enum + slots
  // schema) injected by FlowDagExecutor. None for all other agents.
  flowContract: Option[FlowNodeContract] = None
)
