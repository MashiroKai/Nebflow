package nebflow.core.entity

import munit.FunSuite

/**
 * subagent-generalization 批（2026-10-24 作者令：「让 subagent 变成一个 general
 * 通用的一个 agent，就像一个节点那样的，不再只是只读」）专项 spec。三组分钉：
 *
 *  ① 通用工具面在场——`subagent` 固定面 = 基础六件 + AskUserQuestion + Web 两件
 *    （能写文件、跑命令、完成端到端小任务）；定义文案/提示词/工具描述同步去
 *    「read-only」化（模型可见面与机制面一致，禁一面之词）。
 *  ② 护栏不降级——身份闸（RootExclusiveTools 对 subagent 身份全量剥离）、
 *    危险命令确认链（ToolReversibility 按档位判、会话身份无关）、深度闸与
 *    15min 硬顶原值在场。
 *  ③ agentflow 审计关键数字（腿② REPORT 的承重钉，防审计后语义漂移）——
 *    步数上限单源、legacy guard 错误码、delegate 身份闸文案。
 *
 * 判据均引常量/纯函数，零裸行为面。
 */
class SubagentGeneralizationSpec extends FunSuite:

  // ── ① 通用工具面在场 ─────────────────────────────────────────

  test("subagent 固定面 = BaseTools + AskUserQuestion + WebSearch + WebFetch（恰 9 件，写/执行/提问/上网面在场）"):
    val face = nebflow.agent.AgentCore.SubagentFixedTools
    assertEquals(face, nebflow.agent.AgentCore.BaseTools + "AskUserQuestion" + "WebSearch" + "WebFetch")
    assertEquals(face.size, 9)
    Set("Read", "Write", "Edit", "Glob", "Grep", "Bash", "AskUserQuestion", "WebSearch", "WebFetch")
      .foreach(t => assert(face.contains(t), s"general face must hold: $t"))

  test("定义文案去只读化：subagent entry 描述/提示词不再宣称 read-only，且声明写/执行能力"):
    val entry = BuiltinAgents.entry("subagent").getOrElse(fail("subagent builtin entry must exist"))
    val text = entry.description + "\n" + entry.systemPrompt
    assert(!text.toLowerCase.contains("read-only"), s"builtin text must not claim read-only:\n$text")
    assert(entry.systemPrompt.contains("Write") && entry.systemPrompt.contains("Bash"),
      "subagent prompt must name the write/execute surface")
    assert(entry.systemPrompt.contains("NEVER send signals to or kill any sbt/java/nebflow"),
      "subagent prompt must carry the process-kill safety line")

  test("SubagentTool 描述去只读化：不再宣称 cannot write，且点名写文件/跑命令用途"):
    val d = nebflow.core.tools.SubagentTool.description
    assert(!d.contains("cannot write"), s"tool description must not claim cannot-write:\n$d")
    assert(d.contains("write files") && d.contains("run commands"), s"description must name write/execute usage:\n$d")

  // ── ② 护栏不降级 ────────────────────────────────────────────

  test("身份闸零放宽：RootExclusiveTools 对 subagent 身份全量剥离（Pop/Delegate/管控/编排面一件不过）"):
    assertEquals(
      nebflow.agent.AgentCore.exclusiveToolsFor("subagent"),
      nebflow.agent.AgentCore.RootExclusiveTools
    )
    Set("Pop", "Delegate", "AgentControl", "Subagent", "AgentFlow", "ChainWrite", "Schedule")
      .foreach(t => assert(nebflow.agent.AgentCore.RootExclusiveTools.contains(t), s"identity gate must list: $t"))

  test("危险命令确认链会话身份无关：ToolReversibility 按档位判（ConfirmEdits/AutoEdits 档危险 Bash 与 Write 需确认，AutoAll 放行）"):
    import io.circe.syntax.*
    val dangerous = JsonObject("command" -> "rm -rf /tmp/x".asJson)
    val benign = JsonObject("command" -> "ls /tmp".asJson)
    val writeCall = JsonObject("file_path" -> "/tmp/x".asJson, "content" -> "y".asJson)
    // 该判据函数无会话参数——subagent 拿到的 Bash/Write 面走同一条确认链（不降级钉）。
    assertEquals(nebflow.core.ToolReversibility.isReversible("Bash", dangerous, nebflow.core.SafetyMode.ConfirmEdits), false)
    assertEquals(nebflow.core.ToolReversibility.isReversible("Bash", dangerous, nebflow.core.SafetyMode.AutoEdits), false)
    assertEquals(nebflow.core.ToolReversibility.isReversible("Bash", dangerous, nebflow.core.SafetyMode.AutoAll), true)
    assertEquals(nebflow.core.ToolReversibility.isReversible("Bash", benign, nebflow.core.SafetyMode.AutoEdits), true)
    assertEquals(nebflow.core.ToolReversibility.isReversible("Write", writeCall, nebflow.core.SafetyMode.ConfirmEdits), false)
    assertEquals(nebflow.core.ToolReversibility.isReversible("Write", writeCall, nebflow.core.SafetyMode.AutoEdits), true)

  test("叶子深度闸与 15min 硬顶原值在场（BlockingSubagent 护栏不随面扩张而松动）"):
    import scala.concurrent.duration.*
    assertEquals(nebflow.core.tools.BlockingSubagent.Timeout, 15.minutes)
    assertEquals(nebflow.core.tools.DelegateTool.MaxDepth, 5)

  // ── ③ agentflow 审计关键数字（腿② REPORT 承重钉）────────────

  test("agentflow 步数上限单源：AgentFlow 上限 = ChainWrite 链节点上限（薄壳同底座）"):
    assertEquals(nebflow.core.tools.AgentFlowTool.MaxSteps, nebflow.core.tools.ChainWriteTool.MaxNodes)
    assertEquals(nebflow.core.tools.ChainWriteTool.MaxNodes, 12)

  test("agentflow legacy guard 错误码稳定（审计引用的拒收面编号）"):
    assertEquals(nebflow.core.tools.AgentFlowTool.LegacyShapeCode, "AGENTFLOW_LEGACY_SHAPE")

  test("agentflow 身份闸文案点名 Delegate 派发面（审计结论「root 不可直调，派发唯一入口 = Delegate」的文案钉）"):
    val err = nebflow.core.tools.AgentFlowTool.notDelegateError
    assert(err.contains("Delegate(task=<brief>)"), s"gate text must route dispatches to Delegate:\n$err")
    assert(err.contains("Subagent"), s"gate text must name the Subagent tier:\n$err")
end SubagentGeneralizationSpec
