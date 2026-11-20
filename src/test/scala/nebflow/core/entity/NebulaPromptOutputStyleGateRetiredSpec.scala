package nebflow.core.entity

import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * 退役的 `outputBlock.enabled` 总闸在**根提示词基座**上的无效应钉（report 块
 * 可见性批，作者令 2026-11-20）。
 *
 * 历史：prompt-face 补批（2026-11-01，作者令「把 output 块相关的内容先关闭」）
 * 曾把 Voice 节那条块 bullet 做成闸感知——闸关 = 整行剥除。设计稿 proposal 逐字
 * 定调本次迁移「the old `outputBlock` kill switch is retired for root rather than
 * allowing a full-text leak」⇒ 闸与剥除腿**整体退役**：`NebulaOutputVoiceBullet`
 * 常量删除、`nebflow.shared.OutputBlockGate` 删除、配置键不再被任何一侧读取。
 *
 * 判据两面：
 *  - 正面：基座**恒**携带块纪律（配对 `<report>` 标签 + 画像面），`## Voice`
 *    节与相邻 bullet 逐字保全；
 *  - 反向：配置里把退役键写成 false / true / 垃圾类型，基座字节**不变**——
 *    这就是「无回退面」的机械证明（旧 spec 的两态参数化随之作废）。
 *
 * 断言面 = `entry("Nebula")` 公开读路（与 [[NebulaPromptVoiceRoutingSpec]] /
 * [[NebulaPromptOutputStyleSpec]] 同一单点），不是读源文件文本。
 */
class NebulaPromptOutputStyleGateRetiredSpec extends FunSuite:

  private def nebulaPrompt: String =
    nebflow.core.entity.BuiltinAgents.entry("Nebula").map(_.systemPrompt).getOrElse("")

  /** Run `body` with a temp data root carrying the given config JSON. */
  private def withConfigJson[A](json: String)(body: => A): A =
    val prev = PathUtil.dataRoot
    val home = os.temp.dir(prefix = "nb-nebula-gate-retired-")
    PathUtil.setDataRoot(home)
    try
      val cfg = PathUtil.configJsonReadPath(home)
      os.write(cfg, json, createFolders = true)
      body
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(home)

  test("基座恒携带 Voice 节 report 块 bullet（原文逐字，无闸态）"):
    val p = nebulaPrompt
    assert(
      p.contains("User-visible output: wrap everything the user should read in paired `<report>...</report>` tags"),
      "the Voice bullet is present verbatim"
    )
    assert(p.contains("## Voice"), "Voice section stays")

  test("退役锚：基座零 `<output>` 教学（旧标签按普通文本处理，无过渡别名）"):
    val p = nebulaPrompt
    assert(
      !p.contains("<output>"),
      s"no retired tag teaching: ${p.linesIterator.filter(_.contains("output")).mkString("\n")}"
    )
    assert(!p.contains("Output discipline"), "the old discipline header is gone")

  test("退役键写 false 也不剥除 bullet（无「关 = 全文泄漏」回退面）"):
    withConfigJson("""{"outputBlock":{"enabled":false}}"""):
      val p = nebulaPrompt
      assert(p.contains("<report>...</report>"), "the bullet survives a false kill-switch value")
      assert(p.contains("## Voice"), "Voice section stays")
      assert(p.contains("Sound like a real human chatting on WeChat"), "style bullet intact")
      assert(p.contains("Terse does not mean robotic"), "terse bullet intact")

  test("退役键写 true / 垃圾类型 / 无键：基座逐字节相同"):
    // Baseline and every variant are read under their own temp data root — the
    // rendered prompt carries the root display name, which is data-root-local.
    def promptUnder(json: String): String = withConfigJson(json)(nebulaPrompt)
    val baseline = promptUnder("""{"llm":{"providers":{}}}""")
    List(
      """{"outputBlock":{"enabled":true}}""",
      """{"outputBlock":{"enabled":false}}""",
      """{"outputBlock":{"enabled":"yes"}}""",
      """not json at all"""
    ).foreach { json =>
      assertEquals(promptUnder(json), baseline, s"config $json must not change the root prompt by a single byte")
    }

end NebulaPromptOutputStyleGateRetiredSpec
