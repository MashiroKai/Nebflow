package nebflow.core.tools

import munit.FunSuite
import nebflow.core.project.NodeEngine
import nebflow.prompts.PromptCatalog
import nebflow.shared.Defaults

/**
 * 交付落点纪律批 spec（作者 2026-11-16 15:31 令 + 2026-11-19 追问
 * 「应该是提示词和机制都要落实」）：worktree 与过程产物落点规则
 * （[[Defaults.DeliveryLandingLine]]）的**单源 + 派发面注入**锚。
 *
 * 钉三件事：
 *  1. 单源：`Defaults.DeliveryLandingLine` 逐字等于
 *     `PromptCatalog.Shared.DeliveryLandingLine`（文字面零散落）；
 *  2. delegate brief（[[DelegateTool.assembleBrief]]）携带该行，且位于 report 行
 *     之后、等待纪律铁律之前（首屏即见）；
 *  3. 节点脚注（`NodeEngine.protocolFootnoteFor`）同源携带，且既有
 *     `endsWith(ProtocolFootnote)` 身份断言零波及。
 *
 * 措辞三要素（规则本体的机械可判面）：`<project>/.stand/worktrees/<wt>/`、
 * `<project>/.stand/tmp/<wt>/{delivery,evidence}/`、七日 TTL/入库时间。
 */
class DeliveryLandingBriefSpec extends FunSuite:

  private val line = Defaults.DeliveryLandingLine

  test("单源：Defaults 转派逐字等于 PromptCatalog.Shared（文字面零散落）") {
    assertEquals(line, PromptCatalog.Shared.DeliveryLandingLine)
    assert(line.nonEmpty, "landing line must not be empty")
  }

  test("措辞三要素：worktree 落点 + tmp 交付/证据落点 + 七日 TTL 入库时间") {
    assert(line.contains("<project>/.stand/worktrees/<wt>/"), s"worktree location required: $line")
    assert(line.contains("<project>/.stand/tmp/<wt>/{delivery,evidence}/"), s"tmp artifact location required: $line")
    assert(line.contains("seven-day TTL"), s"TTL required: $line")
    assert(line.contains("admission time"), s"per-file admission time required: $line")
    assert(line.contains("[artifacts]"), s"scan prefix required: $line")
  }

  test("delegate brief 装配：落点行在 report 行之后、等待纪律铁律之前") {
    val brief = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /tmp/ws",
      bindingLine = Some("[branch] delegate/delegate-x from main"),
      task = "Harden the bind flow.",
      injectedBlock = ""
    )
    assert(brief.contains(line), s"delegate brief must carry the landing line: $brief")
    val reportAt = brief.indexOf("[report]")
    val landAt = brief.indexOf("[artifacts]")
    val ruleAt = brief.indexOf("Waiting discipline")
    val taskAt = brief.indexOf("Harden the bind flow.")
    assert(reportAt >= 0 && landAt > reportAt && landAt < ruleAt && ruleAt < taskAt, s"section order: $brief")
  }

  test("节点脚注同源注入：携带落点行，endsWith(ProtocolFootnote) 身份保持") {
    val plain = NodeEngine.protocolFootnoteFor(None)
    assert(plain.contains(line), "node footnote must carry the landing line")
    assert(
      plain.indexOf(line) > plain.indexOf(Defaults.PluginDiscoveryLine),
      "landing line sits after the plugin discovery line"
    )
    assert(plain.endsWith(NodeEngine.ProtocolFootnote), "identity pin intact")
    val verifier = NodeEngine.protocolFootnoteFor(Some("verifier"))
    assert(verifier.contains(line), "verifier footnote carries it too")
    assert(
      verifier.endsWith(NodeEngine.ProtocolFootnote + "\n" + NodeEngine.VerifierVerdictFootnote),
      "verifier appendix unchanged"
    )
  }

end DeliveryLandingBriefSpec
