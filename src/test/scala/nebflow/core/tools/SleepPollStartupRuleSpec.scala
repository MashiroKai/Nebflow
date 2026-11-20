package nebflow.core.tools

import munit.FunSuite
import nebflow.core.project.NodeEngine
import nebflow.shared.Defaults
import nebflow.shared.PathUtil

/**
 * sleep-poll 禁令批 C 段 spec：启动注入铁律（[[Defaults.NoSleepPollRule]]）
 * 的三面单一权威文本 + 装配锚。
 *
 * 作者令 2026-10-20：delegate/节点/subagent 启动注入文案加铁律「禁止
 * sleep/轮询等待；结束回合，通知会唤醒你；等作者门控事件（重启/批准）时交付
 * 报告并结束回合」。三消费点：delegate brief（[[DelegateTool.assembleBrief]]）、
 * 节点 ProtocolFootnote（`NodeEngine.protocolFootnoteFor`）、subagent/workflow
 * 步首条消息（[[BlockingSubagent.promptWithRule]]）。
 *
 * stand-plugins 批（作者令 2026-10-23）增钉：插件能力查询行
 * （[[Defaults.PluginDiscoveryLine]]）注入 delegate brief 与节点脚注两个**有
 * Bash** 的面（铁律之后、任务/基线之前）；subagent 只读侦察面无 Bash，**不**
 * 注入查询行（promptWithRule 形态不变——查询通道不可达，注入即纯噪音）。
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

  test("插件能力查询行（PluginDiscoveryLine）：delegate brief 铁律之后、任务正文之前注入（stand-plugins 批接线钉）") {
    val line = Defaults.PluginDiscoveryLine
    assert(line.contains("[plugin discovery]"), s"prefix tag required: $line")
    assert(line.contains("stand-plugins list") && line.contains("search"), s"query usage named: $line")
    assert(line.contains("mounting authority stays with the dispatch side"), s"boundary clause required: $line")
    val brief = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /tmp/ws",
      bindingLine = None,
      task = "Do the thing.",
      injectedBlock = ""
    )
    val ruleAt = brief.indexOf("Waiting discipline")
    val plugAt = brief.indexOf("[plugin discovery]")
    val taskAt = brief.indexOf("Do the thing.")
    assert(ruleAt >= 0 && plugAt > ruleAt && plugAt < taskAt, s"discovery line sits after the rule, before the task: $brief")
  }

  test("插件能力查询行：节点脚注同源注入（endsWith 身份断言零波及）") {
    val plain = NodeEngine.protocolFootnoteFor(None)
    assert(plain.contains(Defaults.PluginDiscoveryLine), "node footnote must carry the discovery line")
    assert(
      plain.indexOf(Defaults.PluginDiscoveryLine) > plain.indexOf(Defaults.NoSleepPollRule),
      "discovery line sits after the iron rule"
    )
    assert(plain.endsWith(NodeEngine.ProtocolFootnote), "identity pin intact (NodeChainAttributionSpec anchor)")
    val verifier = NodeEngine.protocolFootnoteFor(Some("verifier"))
    assert(verifier.contains(Defaults.PluginDiscoveryLine), "verifier footnote carries it too")
    assert(verifier.endsWith(NodeEngine.ProtocolFootnote + "\n" + NodeEngine.VerifierVerdictFootnote), "verifier appendix unchanged")
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

  // ── report 块可见性批（2026-11-20；原 output 块语法批 2026-10-31 16:13，
  //    16:16 修订标签式）──
  // 判据翻转：`outputBlock.enabled` 总闸**整体退役**（设计稿 proposal 逐字
  // 「retired ... rather than allowing a full-text leak」）⇒ 四个注入面
  // （brief / 节点脚注 / subagent 包装 / 根提示词正文）**恒注入**，不再有两态
  // 参数；闸键写在配置里也不能影响任何一个面。

  test("report 块纪律行（OutputBlockRule）：<report> 成对标签 + 空块允许 + 交接不过滤") {
    val line = Defaults.OutputBlockRule
    assert(line.contains("<report>") && line.contains("</report>"), s"paired tags named: $line")
    assert(!line.contains("<output>"), "旧 <output> 字样按普通文本处理（无过渡别名）")
    assert(line.contains("── Report discipline ──"), s"header renamed with the mechanism: $line")
    assert(line.contains("Zero blocks is allowed"), s"zero-block form is legitimate: $line")
    assert(line.contains("folded into the existing turn badge"), s"outside text has a named destination: $line")
    assert(line.contains("Multiple blocks appear in order"), s"multi-block order stated: $line")
    assert(line.contains("Agent-to-agent handoffs stay complete and unfiltered"), s"handoff boundary stated: $line")
    assert(line.nonEmpty && line.length < 900, s"compact: ${line.length} chars")
  }

  test("report 块纪律行：delegate brief 插件行之后、任务正文之前注入（恒注入，无闸态）") {
    val brief = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /tmp/ws",
      bindingLine = None,
      task = "Do the thing.",
      injectedBlock = ""
    )
    val plugAt = brief.indexOf("[plugin discovery]")
    val outAt  = brief.indexOf("Report discipline")
    val taskAt = brief.indexOf("Do the thing.")
    assert(plugAt >= 0 && outAt > plugAt && outAt < taskAt, s"report rule sits after the discovery line, before the task: $brief")
  }

  test("report 块纪律行：退役的闸键写在配置里也压不住 brief（无全文泄漏回退面）") {
    val prev = PathUtil.dataRoot
    val home = os.temp.dir(prefix = "nb-retired-gate-")
    PathUtil.setDataRoot(home)
    try
      val cfg = PathUtil.configJsonReadPath(home)
      os.write(cfg, """{"outputBlock":{"enabled":false}}""", createFolders = true)
      val brief = DelegateTool.assembleBrief("[workspace] /tmp/ws", None, "Do the thing.", "")
      assert(brief.contains(Defaults.OutputBlockRule), s"the retired key must not suppress the rule: $brief")
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(home)
  }

  test("report 块纪律行：节点脚注同源注入（endsWith 身份断言零波及）") {
    val plain = NodeEngine.protocolFootnoteFor(None)
    assert(plain.contains(Defaults.OutputBlockRule), "node footnote must carry the report rule")
    assert(
      plain.indexOf(Defaults.OutputBlockRule) > plain.indexOf(Defaults.PluginDiscoveryLine),
      "report rule sits after the discovery line"
    )
    assert(plain.endsWith(NodeEngine.ProtocolFootnote), "identity pin intact (NodeChainAttributionSpec anchor)")
    // verifier 附录腿同构（纪律行与附录并存）
    val verifier = NodeEngine.protocolFootnoteFor(Some("verifier"))
    assert(verifier.contains(Defaults.OutputBlockRule), "verifier footnote carries it too")
    assert(verifier.endsWith(NodeEngine.ProtocolFootnote + "\n" + NodeEngine.VerifierVerdictFootnote), "verifier appendix unchanged")
  }

  test("report 块纪律行：subagent/workflow 步包装同源注入（铁律之后、原文之前）") {
    val wrapped = BlockingSubagent.promptWithRule("Find all callers of X.")
    assert(wrapped.contains(Defaults.OutputBlockRule), "wrapped prompt carries the report rule")
    assert(wrapped.indexOf(Defaults.OutputBlockRule) > wrapped.indexOf(rule), "after the iron rule")
    assert(wrapped.endsWith("Find all callers of X."), "original prompt intact")
  }

  test("report 块纪律行：root Nebula 提示词正文同文（Voice 节接线面，恒注入）") {
    val prompt = nebflow.core.entity.BuiltinAgents.entry("Nebula").map(_.systemPrompt).getOrElse("")
    assert(prompt.contains("<report>") && prompt.contains("</report>"), "root prompt carries the paired-tag rule")
    assert(prompt.contains("User.md"), "root prompt names the profile surface")
    assert(!prompt.contains("<output>"), s"no retired tag may remain: ${prompt.linesIterator.filter(_.contains("output")).mkString("\n")}")
    assert(prompt.contains("## Voice"), "Voice section stays")
    assert(prompt.contains("Sound like a real human chatting on WeChat"), "neighboring bullet (style) intact")
    assert(prompt.contains("Terse does not mean robotic"), "neighboring bullet (terse) intact")
  }
end SleepPollStartupRuleSpec
