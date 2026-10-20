package nebflow.core.tools

import munit.FunSuite
import nebflow.core.project.NodeEngine
import nebflow.shared.Defaults

/**
 * sleep-poll 禁令批 C 段 spec：启动注入铁律（[[Defaults.NoSleepPollRule]]）
 * 的三面单一权威文本 + 装配锚。
 *
 * 作者令 2026-10-20：delegate/节点/subagent 启动注入文案加铁律「禁止
 * sleep/轮询等待；结束回合，通知会唤醒你；等作者门控事件（重启/批准）时交付
 * 报告并结束回合」。三消费点：delegate brief（[[DelegateTool.assembleBrief]]）、
 * 节点 ProtocolFootnote（`NodeEngine.protocolFootnoteFor`）、subagent/workflow
 * 步首条消息（[[BlockingSubagent.promptWithRule]]）。
 */
class SleepPollStartupRuleSpec extends FunSuite:

  private val rule = Defaults.NoSleepPollRule

  test("铁律文本三要素：禁止 sleep/轮询 + run_in_background/结束回合 + 作者门控事件处置") {
    assert(rule.contains("NEVER wait with `sleep` or polling loops"), s"forbidden clause: $rule")
    assert(rule.contains("run_in_background"), s"correct pattern must be named: $rule")
    assert(rule.contains("END YOUR TURN"), s"end-your-turn instruction required: $rule")
    assert(rule.contains("author-gated event"), s"author-gated event clause required: $rule")
    assert(rule.nonEmpty && rule.length < 800, s"single-paragraph iron rule, compact: ${rule.length} chars")
  }

  test("delegate brief 装配（DelegateTool.assembleBrief）：铁律在任务正文之前、纪律行之后") {
    val brief = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /tmp/ws",
      bindingLine = Some("[branch] delegate/delegate-x from main"),
      task = "Harden the bind flow.",
      injectedBlock = ""
    )
    assert(brief.contains(rule), s"brief must inject the iron rule: $brief")
    assert(brief.contains(DelegateTool.BriefReportLine), "report discipline line must stay")
    // 排序锚：workspace 头 → report 行 → 铁律 → 任务正文
    val wsAt = brief.indexOf("[workspace]")
    val reportAt = brief.indexOf("[report]")
    val ruleAt = brief.indexOf("Waiting discipline")
    val taskAt = brief.indexOf("Harden the bind flow.")
    assert(wsAt < reportAt && reportAt < ruleAt && ruleAt < taskAt, s"section order: $brief")
    // 插件注入块在任务正文之后（既有位形不变）
    val withBlock = DelegateTool.assembleBrief("[workspace] /w", None, "TASK", "<injected-plugins>x</injected-plugins>")
    assert(withBlock.indexOf("TASK") < withBlock.indexOf("<injected-plugins>"), s"plugin block stays last: $withBlock")
  }

  test("节点脚注（protocolFootnoteFor）：铁律前缀注入，endsWith(ProtocolFootnote) 身份保持") {
    val plain = NodeEngine.protocolFootnoteFor(None)
    assert(plain.contains(rule), "node footnote must carry the iron rule")
    assert(plain.endsWith(NodeEngine.ProtocolFootnote), "identity pin: footnote still ends with ProtocolFootnote (NodeChainAttributionSpec anchor)")

    val verifier = NodeEngine.protocolFootnoteFor(Some("verifier"))
    assert(verifier.contains(rule), "verifier footnote carries the rule too")
    assert(verifier.endsWith(NodeEngine.ProtocolFootnote + "\n" + NodeEngine.VerifierVerdictFootnote), "verifier appendix unchanged")
    // 末行措辞钉不受影响（TaskBoardInjectionSpec 锚）
    assertEquals(NodeEngine.ProtocolFootnote.linesIterator.toList.last, "TaskBoard work order ⇒ close = done, blocked = stuck.")
  }

  test("subagent/workflow 步包装（BlockingSubagent.promptWithRule）：铁律前缀 + 原文保全") {
    val wrapped = BlockingSubagent.promptWithRule("Find all callers of X.")
    assert(wrapped.startsWith(rule), "rule is the prefix")
    assert(wrapped.endsWith("Find all callers of X."), "original prompt intact")
    assert(BlockingSubagent.promptWithRule("").startsWith(rule), "empty prompt still gets the rule")
  }
end SleepPollStartupRuleSpec
