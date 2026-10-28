/* 刀二（2026-10-28 无感压缩批·彻底零痕迹）验收 spec——compactComplete 帧 →
 * ui.json 落盘行契约（WebSocketRoutes.compactCompleteRowFromFrame 纯函数单点）。
 *
 * 口径（作者令 2026-10-28 11:44）：完成卡/失败卡渲染面归零，数据面零削减——
 * 本 spec 钉「留账」半边：常规帧照常落 chat.compacted（载荷与旧版逐字节一致）；
 * emergency 帧（0.95 硬守卫安全阀）额外落 params.emergency=true，前端重放腿
 * （persistence.js restoreCompactCardRow）据此保留该卡的渲染，其余一律 null。
 */
package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

class CompactFrameRecordSpec extends FunSuite:

  test("legacy frame (no emergency key) → params byte-identical to the pre-batch payload, no emergency key") {
    val row = WebSocketRoutes.compactCompleteRowFromFrame(
      Json.obj("type" -> "compactComplete".asJson, "before" -> 359.asJson, "after" -> 4.asJson)
    )
    assertEquals(row.i18nKey, Some("chat.compacted"))
    assertEquals(row.content, "Context compacted: 359 → 4 messages")
    val params = row.params.get
    assertEquals(params.hcursor.downField("before").as[Int].toOption, Some(359))
    assertEquals(params.hcursor.downField("after").as[Int].toOption, Some(4))
    assert(
      params.hcursor.downField("emergency").as[Boolean].isLeft,
      "缺席即不落键——旧载荷逐字节不变（answerOf/quote 兼容先例）"
    )
  }

  test("emergency frame (0.95 hard guard) → params.emergency=true rides along (replay-leg gate)") {
    val row = WebSocketRoutes.compactCompleteRowFromFrame(
      Json.obj(
        "type" -> "compactComplete".asJson,
        "before" -> 359.asJson,
        "after" -> 4.asJson,
        "emergency" -> true.asJson
      )
    )
    assertEquals(row.i18nKey, Some("chat.compacted"))
    val params = row.params.get
    assertEquals(params.hcursor.downField("emergency").as[Boolean].toOption, Some(true))
    assertEquals(params.hcursor.downField("before").as[Int].toOption, Some(359))
  }

  test("malformed frame → default fallbacks (before/after=0), still recorded (data plane never drops)") {
    val row = WebSocketRoutes.compactCompleteRowFromFrame(Json.obj("type" -> "compactComplete".asJson))
    assertEquals(row.content, "Context compacted: 0 → 0 messages")
    assert(row.params.get.hcursor.downField("emergency").as[Boolean].isLeft)
  }

end CompactFrameRecordSpec
