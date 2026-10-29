package nebflow.core.entity

import munit.FunSuite

/** 防回退锚（文件直出批 2026-10-29 作者令「要求文件你直接pop给用户，而不是
  * 告诉用户在哪让用户自己找。直接pop」）：组装后的 Nebula 系统提示词（经
  * `entry("Nebula")` 公开读路，与 [[NebulaPromptVoiceRoutingSpec]] 同一单点；
  * 显示名参数化不影响稳定子串）恒含 Pop 交付纪律关键句——只报路径不算交付、
  * Pop 文件本身、路径只能伴随 Pop 不能替代 Pop。 */
class NebulaPromptPopDeliverySpec extends FunSuite:

  private def nebulaPrompt: String =
    nebflow.core.entity.BuiltinAgents.entry("Nebula")
      .map(_.systemPrompt)
      .getOrElse(fail("BuiltinAgents must carry a code-defined 'Nebula' entry"))

  test("组装后的 Nebula 提示词含 Pop 文件直出交付纪律句（防回退锚）"):
    val text = nebulaPrompt
    assert(
      text.contains("Naming the path alone is not delivery"),
      "Lifecycle §5 必须钉「只报路径不算交付」关键句"
    )
    assert(
      text.contains("Pop the files themselves"),
      "Lifecycle §5 必须钉「Pop 文件本身」关键句"
    )
    assert(
      text.contains("write the path only alongside the Pop, never instead of it"),
      "Lifecycle §5 必须钉「路径只伴随、不替代」关键句"
    )

end NebulaPromptPopDeliverySpec
