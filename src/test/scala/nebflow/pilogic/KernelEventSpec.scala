package nebflow.pilogic

import io.circe.{Json, parser}
import io.circe.syntax.*
import munit.CatsEffectSuite

/** envelope 编解码（本轮必测项一）：wire 名对拍 db §D-12。 */
class KernelEventSpec extends CatsEffectSuite:

  private val sample = KernelEvent(
    eventType = "node-failed",
    occurredAt = 1762224000001L,
    sessionId = "s-1",
    seqInSession = 1L,
    payload = Json.obj("summary" -> "sample-1".asJson, "chainId" -> Json.Null)
  )

  test("envelope 编解码 round-trip"):
    assertEquals(sample.asJson.as[KernelEvent], Right(sample))

  test("wire 字段名恰五且序对拍 db D-12 样例（subject 归 payload 故不在）"):
    val obj = sample.asJson.asObject.get
    assertEquals(
      obj.keys.toVector,
      Vector("type", "occurredAt", "sessionId", "seqInSession", "payload")
    )
    assertEquals(sample.asJson, parser.parse(KernelEvent.dbSampleWire).toOption.get)

  test("db §D-12 落库样例行（含 subject 未知字段）可直接解码——Decoder 容忍未知字段"):
    val dbLine =
      """{"type":"node-failed","occurredAt":1762224000001,"sessionId":"s-1","seqInSession":1,"payload":{"summary":"sample-1","chainId":null},"subject":"node-3f2a"}"""
    assertEquals(parser.decode[KernelEvent](dbLine), Right(sample))
end KernelEventSpec
