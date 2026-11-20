package nebflow.core.entity

import munit.FunSuite

/**
 * 输出风格约束（output-style 批，作者令 2026-11-05 08:56「很好，把这些对你输出的要求，
 * 写进提示词里。」＋ 09:01 修正令「不是强制要求每个都有过程……也不是必须要都用带颜色的
 * 字体」；09:13 追加令「对段落内容，要有缩进。」段落缩进条目已由**撤回批**
 * 2026-11-05 16:43 作者令「你发的消息不加缩进功能……把缩进的优化撤回吧。效果并不好」
 * 整条删除 —— 本 spec 对应的断言同步翻转为「撤回锚」）：根 Nebula 提示词 `## Voice`
 * 节的防回退锚。
 *
 * 断言面 = `entry("Nebula")` 公开读路（与 [[NebulaPromptVoiceRoutingSpec]] /
 * [[NebulaPromptOutputStyleGateRetiredSpec]] 同一单点）——不是读源文件文本：断言必须证明**加载
 * 路径上的那份提示词**真的带着这些句子，否则「写进提示词」只是改了一个文件。
 *
 * 两处刻意的**反向**断言（作者 09:01 修正令的机械形态）：
 *  - 过程折叠 = **触发式**，不是每条消息必做 ⇒ 断言「短消息不得强制」子句在位，且
 *    过程相关行里不出现 every message / must always 这类普遍强制措辞；
 *  - 颜色 = 仅在情绪/强强调场景，默认加粗 ⇒ 断言「bold by default」与「不是满篇彩色」
 *    两句子在位，且不存在「必须用颜色」措辞。
 */
class NebulaPromptOutputStyleSpec extends FunSuite:

  private def nebulaPrompt: String =
    nebflow.core.entity.BuiltinAgents
      .entry("Nebula")
      .map(_.systemPrompt)
      .getOrElse(fail("BuiltinAgents must carry a code-defined 'Nebula' entry"))

  /** `## Voice` 是模板末节 ⇒ 截到文末即整节。 */
  private def voiceSection(prompt: String): String =
    val i = prompt.indexOf("## Voice")
    assert(i >= 0, "prompt must carry the `## Voice` section")
    prompt.substring(i)

  test("长度硬上限：正文 ≤100 字在位（并与「结构是默认形态」合并进同一条 bullet）"):
    val v = voiceSection(nebulaPrompt)
    assert(v.contains("at most 100 characters"), "the 100-character cap is stated")
    assert(v.contains("over that is a defect"), "an over-length body is named a defect, not a preference")
    assert(
      v.contains("Structure is the default shape of a report, not a last resort"),
      "旧「Plain flowing sentences by default」被合并改写（结构=默认形态），不是两套并存"
    )

  test("重要度顺序：三行判据 + 最重要在最上"):
    val v = voiceSection(nebulaPrompt)
    assert(v.contains("Order is importance"), "the ordering rule is stated")
    assert(v.contains("the most important at the top"), "most-important-first is explicit")
    assert(v.contains("what blocks him"), "criterion (1) blocking him")
    assert(v.contains("what he can see right now"), "criterion (2) a visible result")
    assert(v.contains("what needs his decision but blocks nothing yet"), "criterion (3) decision, non-blocking")
    assert(v.contains("plain progress reporting"), "criterion (4) plain progress")

  test("强调：默认加粗，颜色是情绪/强强调（非默认手段，且不得满篇彩色）"):
    val v = voiceSection(nebulaPrompt)
    assert(v.contains("Emphasis is **bold** by default"), "bold is the default emphasis")
    assert(v.contains("<span style=\"color:#d93025\">"), "the restrained colour form is named for escalation only")
    assert(v.contains("Ordinary content never gets colour"), "colour is not the default carrier of emphasis")
    assert(v.contains("never mostly colour"), "a message must not be page-wide colour")
    assert(!v.toLowerCase.contains("must use colour"), "no mandatory-colour wording may exist")

  test("可视化：mermaid 围栏 + GFM 表在位，ASCII 框图禁止并带理由"):
    val v = voiceSection(nebulaPrompt)
    assert(v.contains("fenced block tagged `mermaid`"), "mermaid fence is the structure surface")
    assert(v.contains("GFM table"), "GFM table is the tabular surface")
    assert(v.contains("Never draw a diagram out of characters"), "ASCII art ban")
    assert(v.contains("collapses on the first narrow screen"), "the ban carries its reason (unreadable when narrow)")
    // 禁止的形态必须点名，否则模型不知道 `+---+` 也算
    assert(v.contains("`+---+`") && v.contains("├─"), "the banned glyph families are named")

  test("过程折叠：`<details>` 在最底部，且为触发式（短消息不得强制）"):
    val v = voiceSection(nebulaPrompt)
    assert(v.contains("<details><summary>过程</summary>"), "the fold block form is named")
    assert(v.contains("ABSOLUTE BOTTOM"), "bottom placement is a hard constraint, not an afterthought")
    assert(v.contains("never inside the body, never mid-message"), "body/mid-message placement refused")
    // 触发式判据（作者 09:01 修正令）：长 ∧ 会顶破上限 才折；短消息零折叠块
    assert(v.contains("genuinely long"), "the trigger requires genuinely long process detail")
    assert(v.contains("push the body past the length cap"), "the trigger pairs length with the cap")
    assert(v.contains("a short message carries no process block at all"), "short messages must NOT be forced to fold")
    assert(v.contains("padding block added to look thorough is a defect"), "a ritual fold is itself a defect")
    val processLines = v.linesIterator
      .filter(l => l.toLowerCase.contains("details") || l.toLowerCase.contains("process detail"))
      .toList
    assert(processLines.nonEmpty, "the process rule must be locatable")
    val mandatory = List("every message", "always fold", "must always", "每条消息")
    mandatory.foreach { phrase =>
      assert(
        !processLines.exists(_.toLowerCase.contains(phrase)),
        s"no universal mandate may ride the process rule (found '$phrase'): ${processLines.mkString(" | ")}"
      )
    }

  test("缩进：层级缩进规则已撤回（撤回批 2026-11-05 16:43 作者令「你发的消息不加缩进功能」）"):
    val v = voiceSection(nebulaPrompt)
    // 撤回的判据 = 原文消失，且没有改写成「不要缩进」这类反向规则（作者令：删掉该条，
    // 不留解释性残留）。消息正文回到普通段落 / 单层列表形态。
    val retired = List(
      "Indentation carries hierarchy",
      "Indent by nesting",
      "Depth cap: two levels",
      "never flush-left",
      "spends none of the 100-character budget",
      "a third level means this should have been a second message"
    )
    retired.foreach(phrase => assert(!v.contains(phrase), s"the retired indent rule fragment is gone ('$phrase')"))
    // 反向堆叠也不许：不写「不要缩进」的同义规则
    val reverseWording = List("never indent", "no indentation", "do not indent", "不要缩进", "禁止缩进")
    reverseWording.foreach(phrase =>
      assert(!v.toLowerCase.contains(phrase.toLowerCase), s"no reverse rule may replace it ('$phrase')")
    )

  test("结论优先 + 只讲关键信息与决策（Terse bullet 合并而非并列）"):
    val v = voiceSection(nebulaPrompt)
    assert(v.contains("Terse does not mean robotic"), "the pre-existing terse bullet stays (merged, not replaced)")
    assert(v.contains("Conclusion first"), "conclusion-first is stated")
    assert(v.contains("anything the user did not ask to be walked through is left out"), "key-information-only rule")

  test("语气：像真人在微信聊天（emoji 表情绪，短不等于机器人腔）"):
    val v = voiceSection(nebulaPrompt)
    assert(v.contains("Sound like a real human chatting on WeChat"), "the WeChat-human tone anchor stays")
    assert(v.contains("never stiff, never templated"), "no templated voice")
    assert(v.contains("an emoji"), "emoji for mood is allowed")
    assert(v.contains("Short is not robotic"), "short ≠ robotic")

  test("陈旧的 Card 指针已修（Card 工具 2026-10-03 退役，指向它等于教模型调错工具）"):
    val p = nebulaPrompt
    assert(!p.contains("MUST be rendered with the Card tool"), "the retired-tool instruction is gone")
    assert(!p.contains("use Card for status and results"), "the second retired-tool instruction is gone")
    assert(
      p.contains("the Card tool is retired, `Pop` is the surface for files and images"),
      "the retired state is stated in place, so the model cannot re-derive the old pointer"
    )

end NebulaPromptOutputStyleSpec
