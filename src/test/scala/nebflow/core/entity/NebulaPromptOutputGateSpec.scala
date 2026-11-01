package nebflow.core.entity

import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * output 块总闸·提示词面（prompt-face 补批 2026-11-01，作者令「把 output 块
 * 相关的内容先关闭……」）：根提示词**基座**（`BuiltinAgents.entry("Nebula")`
 * 公开读路——与 [[NebulaPromptVoiceRoutingSpec]] 同一单点）的闸感知钉。
 *
 *  - Voice 节 output 块 bullet 是根基座里唯一的 output 块指令；闸关 =
 *    整行剥除（含行尾换行，其余字节零变），`## Voice` 节与相邻两条 bullet 保全；
 *  - 闸开 = 原文逐字（与总闸化前字节一致——闸恢复今日行为的入口语义）；
 *  - 剥除锚漂移判红：`NebulaPromptTemplate` 的 bullet 行与
 *    `NebulaOutputVoiceBullet` 常量若改不同步，闸关断言（零 `<output>`）即红。
 *
 * 两态经临时 home 写 `outputBlock.enabled` 参数化（OB-G3 同款形态），与机器
 * 真实配置解耦。
 */
class NebulaPromptOutputGateSpec extends FunSuite:

  private def nebulaPrompt: String =
    nebflow.core.entity.BuiltinAgents.entry("Nebula").map(_.systemPrompt).getOrElse("")

  private def withGate[A](enabled: Boolean)(body: => A): A =
    val prev = PathUtil.dataRoot
    val home = os.temp.dir(prefix = "nb-nebula-gate-")
    PathUtil.setDataRoot(home)
    try
      val cfg  = PathUtil.configJsonReadPath(home)
      val json = s"""{"outputBlock":{"enabled":$enabled}}"""
      if os.exists(cfg) then os.write.over(cfg, json) else os.write(cfg, json)
      body
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(home)

  test("闸开：根基座携带 Voice 节 output 块 bullet（原文逐字）"):
    withGate(enabled = true):
      val p = nebulaPrompt
      assert(
        p.contains("User-visible output: wrap everything the user should read in paired `<output>...</output>` tags"),
        "the Voice bullet is present verbatim"
      )
      assert(p.contains("## Voice"), "Voice section stays")

  test("闸关：根基座零 output 块指令，Voice 节与其余 bullet 保全"):
    withGate(enabled = false):
      val p = nebulaPrompt
      assert(!p.contains("<output>"), s"no literal tag teaching: ${p.linesIterator.filter(_.contains("output")).mkString("\n")}")
      assert(!p.contains("Output discipline"), "no output discipline text")
      assert(!p.contains("User-visible output"), "the bullet line is stripped entirely")
      // 剥除字节形态：相邻 bullet 之间不残留空行（原行含行尾换行一并剥除）
      assert(
        p.contains("many parallel items).\n- Terse does not mean robotic"),
        "the line is removed whole — exactly one newline between the neighboring bullets"
      )
      assert(p.contains("## Voice"), "Voice section stays")
      assert(p.contains("Sound like a real human chatting on WeChat"), "style bullet intact")
      assert(p.contains("Terse does not mean robotic"), "terse bullet intact")

  test("闸缺席（无配置文件）= 关（fail-safe 默认，与 OB-G3 同判）"):
    val prev = PathUtil.dataRoot
    val home = os.temp.dir(prefix = "nb-nebula-gate-absent-")
    PathUtil.setDataRoot(home)
    try
      assert(!os.exists(PathUtil.configJsonReadPath(home)), "precondition: no config file")
      assert(!nebulaPrompt.contains("<output>"), "absent config = gate off = bullet stripped")
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(home)

end NebulaPromptOutputGateSpec
