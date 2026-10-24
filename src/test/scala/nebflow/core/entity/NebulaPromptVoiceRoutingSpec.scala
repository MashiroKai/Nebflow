package nebflow.core.entity

import munit.FunSuite

/**
 * 极简防回退锚（voice-routing 批 2026-10）：NebulaPromptTemplate 两处审计通过的
 * 提示词改动在位——① "Project first" 行尾追加的 Routing test 路由判据句；
 * ② 模板末尾新增 `## Voice` 语气纪律节。经 `entry("Nebula")` 公开读路断言
 * （与 [[PromptFaceSplitSpec]] 同一单点；显示名参数化不影响这两个稳定子串）。
 */
class NebulaPromptVoiceRoutingSpec extends FunSuite:

  private def nebulaPrompt: String =
    nebflow.core.entity.BuiltinAgents
      .entry("Nebula")
      .map(_.systemPrompt)
      .getOrElse(fail("BuiltinAgents must carry a code-defined 'Nebula' entry"))

  test("Nebula 提示词含 Routing test 判据句与 ## Voice 节（防回退锚）"):
    val text = nebulaPrompt
    assert(
      text.contains("Routing test: dispatching without `project` means the task has no project home"),
      "Routing test 句在位（Project first 行扩展）"
    )
    assert(text.contains("## Voice"), "Voice 节头在位（模板末尾语气纪律节）")

end NebulaPromptVoiceRoutingSpec
