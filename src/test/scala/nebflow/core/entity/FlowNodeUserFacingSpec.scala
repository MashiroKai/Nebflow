package nebflow.core.entity

import io.circe.parser.*
import io.circe.syntax.*
import munit.FunSuite

/** 轨道二 #5: FlowNode.userFacing whitelist field — decode/encode contract.
  * Default false; encoder omits (null) for default so legacy flows round-trip
  * byte-compatibly; explicit true survives a re-parse. */
class FlowNodeUserFacingSpec extends FunSuite:

  test("userFacing defaults to false when the key is absent"):
    val json = """{"agent":"explorer","input":"$task","onComplete":"$return"}"""
    val node = parse(json).flatMap(_.as[FlowNode])
    assertEquals(node.map(_.userFacing), Right(false))

  test("userFacing:true decodes (the whitelist escape hatch)"):
    val json = """{"agent":"deck-qa","input":"$task","onComplete":"join","userFacing":true}"""
    val node = parse(json).flatMap(_.as[FlowNode])
    assertEquals(node.map(_.userFacing), Right(true))

  test("encoder emits null for default and true for whitelisted nodes"):
    val plain = FlowNode("a", "$task", NodeRoute.Return)
    assert(plain.asJson.hcursor.downField("userFacing").as[Option[Boolean]].toOption.flatten.isEmpty)
    val flagged = FlowNode("a", "$task", NodeRoute.Return, userFacing = true)
    assertEquals(flagged.asJson.hcursor.downField("userFacing").as[Boolean].toOption, Some(true))

  test("round-trip preserves userFacing in both directions"):
    for flag <- List(false, true) do
      val node = FlowNode("n", "$task", NodeRoute.Return, userFacing = flag)
      val back = node.asJson.as[FlowNode]
      assertEquals(back.map(_.userFacing), Right(flag))
end FlowNodeUserFacingSpec
