package nebflow.core.seed

import munit.FunSuite

import java.nio.charset.StandardCharsets.UTF_8

/**
 * node-output contract · 种子面钉子（2026-09-24 立面；govmemory 批 2026-09-25 随
 * seed 对齐改写判据）。
 *
 * govmemory 批按方案 §1.1/§1.2 把运行面纪律逐段并入 `seed/agents/general/system.md`
 * （运行面 dual-track 交付体例取代本 seed 原单轨段——该取代在种子头注登记为批源注记）。
 * 原判据钉的是旧 6 行单轨形态的字面（"You are a task node …" 首句 + Base tools +
 * Workspace 三段结构），该形态已被取代；本改写把**同一组交付语义**重新钉在新种子体上：
 *  ① 末条输出 = 自身交付物（dual-track 双轨体例开头即承载）；
 *  ② 结果自包含、双轨强制（Part 2 证据块不得省）；
 *  ③ node_report 申报义务 + 未申报不终态语义在位；
 *  ④ 批源注记 = HTML 注释形态（首内容行之前），且注记不承载交付语义（语义在正文）。
 *
 * 面性质：本文件走 **seed 面**——`SeedService` 对 agents 目录是**缺失自愈 + 差集
 * REFUSING**（`SeedAgentSelfHealSpec`），改种子不推翻已有运行时，只影响新播种 /
 * 同步面。引擎贡献面（`NodeEngine.ProtocolFootnote` 与
 * `PromptSections.NodeSessionAlwaysOnSection`）不在本 spec 判定内。
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

  private def contentLines: List[String] =
    seedText.split("\n", -1).toList.filterNot(_.trim.startsWith("<!--"))

  test("seed general/system.md 首内容行 = dual-track 交付体例（末条输出 = 自身交付物；双轨强制）"):
    val firstContent = contentLines.dropWhile(_.trim.isEmpty).headOption.getOrElse(
      fail("seed has no content lines (annotations only)")
    )
    assert(firstContent.startsWith("Deliver in two parts"),
      s"种子首内容行语域被改：${firstContent.take(80)}")
    val text = seedText
    assert(text.contains("your final assistant text IS the deliverable"),
      "交付物身份句在位（node deliverable semantics）")
    assert(text.contains("both parts are mandatory"),
      "双轨强制句在位（evidence block 不得省）")
    // 交付体例只承载一次（零重复）
    assertEquals(contentLines.count(_.contains("Deliver in two parts")), 1,
      "dual-track 体例只在首内容行承载（零重复）")

  test("seed general/system.md 输出契约语义在正文（注释不承载语义）+ node_report 义务在位"):
    val text = seedText
    // node_report 申报义务 + 未申报不终态（既有机制句不得删）
    assert(text.contains("Report before you finish"), "node_report 段头在位")
    assert(text.contains("call it before wrapping up"), "申报义务句在位")
    assert(text.contains("Report first, then write your wrap-up text"), "报告先行句在位")
    assert(text.contains("never terminalizes"), "未申报语义在位")
    // 上述语义句全部不在 HTML 注释内（注释 = 批源注记专用，不进模型上下文承载面）
    val commented = seedText.split("\n", -1).toList.filter(_.trim.startsWith("<!--"))
    commented.foreach { l =>
      assert(!l.contains("Deliver in two parts") && !l.contains("node_report"),
        "批源注记不得承载交付/协议语义（语义句必须在正文）")
    }
    // 节点工具面行在位（种子体例的执行面陈述）
    assert(text.contains("Read / Write / Edit / Glob / Grep / Bash / AskUserQuestion"),
      "节点工具面行在位")

  test("seed general/system.md 注记纪律：所有 HTML 注释成行出现（引擎管线按注释行剥离）"):
    // 批源注记 = 单行 HTML 注释（`<!-- … -->` 独占一行）——这是引擎侧「注释行剥离」
    // 判据的形态前提：剥离只作用于整行注释，不吞正文。
    seedText.split("\n", -1).toList.filter(_.contains("<!--")).foreach { l =>
      assert(l.trim.startsWith("<!--"), s"注释必须独占一行起头: ${l.take(60)}")
    }
end NodeSeedOutputContractSpec
