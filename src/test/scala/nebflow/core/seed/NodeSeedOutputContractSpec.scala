package nebflow.core.seed

import munit.FunSuite

import java.nio.charset.StandardCharsets.UTF_8

/**
 * node-output contract（2026-09-24）· 种子面钉子：`seed/agents/general/system.md`
 * 首句 `Your final assistant text IS the node deliverable.` 之后必须补上两条要求的
 * seed 面表述——① 末条输出 = 自身完成汇报（含结论/产物位置/未决项）；② 结果自包含、
 * 禁「see above / same as above」指代收尾。
 *
 * 面性质（为何这里只钉字面、不钉运行时）：本文件走 **seed 面**——`SeedService` 对
 * agents 目录是**缺失自愈**（`SeedAgentSelfHealSpec` 明确「用户改过的运行时不覆盖，
 * 逐字节保留」），不是覆盖式 reconcile ⇒ 改种子**不推翻已有运行时**，只影响新播种 /
 * 缺失自愈面。故它是最弱的一个面（引擎贡献面 = `NodeEngine.ProtocolFootnote` 与
 * `PromptSections.NodeSessionAlwaysOnSection`），本用例只防「种子首句被回退成旧版」。
 *
 * 零副作用：只读 classpath 资源，不写盘、不碰 `PathUtil.dataRoot`。
 */
class NodeSeedOutputContractSpec extends FunSuite:

  private val SeedResource = "seed/agents/general/system.md"

  private def seedText: String =
    val in = Option(getClass.getClassLoader.getResourceAsStream(SeedResource)).getOrElse(
      fail(s"classpath resource '$SeedResource' not found — seed tree missing from test classpath")
    )
    try new String(in.readAllBytes(), UTF_8)
    finally in.close()

  test("seed general/system.md 首句后补有输出契约两条（末条输出 = 自身完成汇报；自包含、禁 see above）"):
    val text = seedText
    val firstLine = text.linesIterator.next()
    // 种子体例保持：仍是「You are a task node …」体，仍以既有首句的语义开头（未换语域）
    assert(firstLine.startsWith("You are a task node running inside Nebflow."), s"种子首句语域被改：${firstLine.take(80)}")
    assert(text.contains("Your final assistant text IS the node deliverable."),
      "既有首句（交付物身份）不得删")
    // 要求 1：末条输出 = 自身完成汇报
    assert(text.contains("your own completion report"), s"要求 1 缺席:\n$firstLine")
    // 要求 2：自包含 + 点名指代禁语
    assert(text.contains("self-contained"), s"要求 2 缺席:\n$firstLine")
    assert(text.contains("\"see above\""), s"要求 2 必须点名 see above:\n$firstLine")
    // 机制句不得被误伤（node_report 申报义务 + 会话不销毁）
    assert(text.contains("Call `node_report` before wrapping up"), "既有申报义务句不得删")
    assert(text.contains("never terminalizes"), "既有未申报语义不得删")

  test("seed general/system.md 输出契约只在首行承载（零重复，后两段逐字未动）"):
    val lines = seedText.split("\n", -1).toList
    assertEquals(lines.size, 6, "种子行数变化（首行 + 空行 + 工具段 + 空行 + workspace 段 + 收尾换行）")
    val contractHits = lines.zipWithIndex.filter { case (l, _) => l.contains("self-contained") }
    assertEquals(contractHits.map(_._2), List(0), "输出契约只应在首行承载")
    assert(lines(2).startsWith("Base tools:"), "工具段逐字未动")
    assert(lines(4).startsWith("Workspace:"), "workspace 段逐字未动")
end NodeSeedOutputContractSpec
