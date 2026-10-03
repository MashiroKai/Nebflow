package nebflow.core.seed

import munit.FunSuite

/**
 * node-output contract · 定义面钉子（2026-09-24 立面；govmemory 批 2026-09-25 随
 * seed 对齐改写判据；builtin-def 批 2026-10-03 随 agent 定义上收代码再改判据源）。
 *
 * govmemory 批按方案 §1.1/§1.2 把运行面纪律逐段并入 general 的 system prompt
 * （运行面 dual-track 交付体例取代原单轨段）。该 prompt 的**唯一权威源**现为
 * `nebflow.core.entity.BuiltinAgents`（builtin-def 批 2026-10-03 作者令①「唯一标准
 * 源就是代码」——seed/agents 资源树已删，磁盘 system.md 是死信），本 spec 的判据源
 * 随之指向代码定义。现行钉面：
 *  ① 末条输出 = 自身交付物（dual-track 双轨体例开头即承载）；
 *  ② 结果自包含、双轨强制（Part 2 证据块不得省）；
 *  ③ node_report 申报义务 + 未申报不终态语义在位；
 *  ④ 代码级 prompt **零 HTML 注释**（种子面的「批源注记」形态随种子退役——注释不
 *     进模型上下文，代码定义从结构上就不携带它）。
 *
 * 面性质：本文件只读 `BuiltinAgents` 的代码定义，不写盘、不碰 `PathUtil.dataRoot`。
 * 引擎贡献面（`NodeEngine.ProtocolFootnote` 与 `PromptSections.NodeSessionAlwaysOnSection`）
 * 不在本 spec 判定内。
 */
class NodeSeedOutputContractSpec extends FunSuite:

  private def promptText: String =
    nebflow.core.entity.BuiltinAgents.entry("general")
      .map(_.systemPrompt)
      .getOrElse(fail("BuiltinAgents must carry a code-defined 'general' entry"))

  private def contentLines: List[String] =
    promptText.split("\n", -1).toList.filterNot(_.trim.startsWith("<!--"))

  test("general 代码级 prompt 首内容行 = dual-track 交付体例（末条输出 = 自身交付物；双轨强制）"):
    val firstContent = contentLines.dropWhile(_.trim.isEmpty).headOption.getOrElse(
      fail("prompt has no content lines")
    )
    assert(firstContent.startsWith("Deliver in two parts"),
      s"首内容行语域被改：${firstContent.take(80)}")
    val text = promptText
    assert(text.contains("your final assistant text IS the deliverable"),
      "交付物身份句在位（node deliverable semantics）")
    assert(text.contains("both parts are mandatory"),
      "双轨强制句在位（evidence block 不得省）")
    // 交付体例只承载一次（零重复）
    assertEquals(contentLines.count(_.contains("Deliver in two parts")), 1,
      "dual-track 体例只在首内容行承载（零重复）")

  test("general 代码级 prompt 输出契约语义在正文 + node_report 义务在位"):
    val text = promptText
    // node_report 申报义务 + 未申报不终态（既有机制句不得删）
    assert(text.contains("Report before you finish"), "node_report 段头在位")
    assert(text.contains("call it before wrapping up"), "申报义务句在位")
    assert(text.contains("Report first, then write your wrap-up text"), "报告先行句在位")
    assert(text.contains("never terminalizes"), "未申报语义在位")
    // 节点工具面行在位（执行面陈述）
    assert(text.contains("Read / Write / Edit / Glob / Grep / Bash / AskUserQuestion"),
      "节点工具面行在位")

  test("general 代码级 prompt 零 HTML 注释（种子批源注记形态随种子退役；注释不进模型上下文）"):
    // builtin-def 批：prompt = 代码定义，从结构上不携带种子面的 HTML 批源注记。
    // 若有人把注记形态带进代码定义，本条即红（注释行剥离判据的形态前提随之作废）。
    val commented = promptText.split("\n", -1).toList.filter(_.contains("<!--"))
    assertEquals(commented, Nil, "代码级 prompt 不得携带 HTML 注释行（种子注记形态已退役）")
end NodeSeedOutputContractSpec
