package nebflow.dropbox

import io.circe.syntax.*
import munit.FunSuite

/**
 * 台账解码健壮化（作者 2026-09-17 #785 裁定）—— 定向 spec。
 *
 * 钉住的四条语义（逐条对应裁定点）：
 *   ① **禁整表失败**：表内任一条目不可解 ⇒ **其余条目照常解出**（逐条读数）；
 *   ② **键缺失语义显式化**：可缺键缺席 ⇒ 显式缺省补齐并保留该条（缺省值逐键断言）；
 *   ③ **缺席 ≠ 类型错**：键在场但类型不符 ⇒ 该条**跳过**（不静默伪造缺省）；
 *   ④ **零回归**：全键台账 ⇒ 逐字段原样照读、零跳过、顺序保持。
 *
 * 纯函数面（`DropboxLedger.decode` + `given Decoder[DropboxMessage]`）——不起实例、零落盘。
 */
class DropboxLedgerDecodeSpec extends FunSuite:

  private val Full = """{
    "d2": [
      {"msgId":"m-1","direction":"in","kind":"text","ts":1758000000000,"text":"hello","origin":"user",
       "transferId":"","fileName":"","fileSize":0,"mimeType":"","status":"","savedPath":"",
       "batchId":"","attachmentIndex":0,"attachmentCount":1},
      {"msgId":"m-2","direction":"out","kind":"file","ts":1758000001000,"text":"","origin":"agent",
       "transferId":"t-9","fileName":"a.bin","fileSize":7,"mimeType":"application/octet-stream",
       "status":"completed","savedPath":"/tmp/a.bin","deviceOutPath":"/Users/kaiyu/send/a.bin",
       "batchId":"b-1","attachmentIndex":1,"attachmentCount":2}
    ]}"""

  /** 只给必给键（= 最极端的「可缺键全缺席」形态）。 */
  private val OnlyRequired =
    """{"d2":[{"msgId":"r-1","direction":"in","kind":"text","ts":1758000000000}]}"""

  private def decode(raw: String): DropboxLedgerDecode =
    DropboxLedger.decode(raw) match
      case Right(d) => d
      case Left(e)  => fail(s"expected the table to decode, got Left($e)")

  private def one(raw: String): DropboxMessage =
    val d = decode(raw)
    assertEquals(d.messages.get("d2").map(_.size), Some(1), s"expected exactly 1 message, got ${d.messages}")
    d.messages("d2").head

  // ── ② 键缺失语义显式化：可缺键缺席 ⇒ 缺省补齐且保留该条 ──────────────────

  test("② 只给必给键 ⇒ 该条保留，且每个可缺键落到**显式缺省值**") {
    val d = decode(OnlyRequired)
    assertEquals(d.skipped, Nil, "缺席可缺键不得导致跳过")
    val m = one(OnlyRequired)
    // 逐键缺省（与 case class 默认值 + 报告里的表逐字相同）
    assertEquals(m.text, "")
    assertEquals(m.origin, "user") // fail-safe 方向：不声称 agent 代发
    assertEquals(m.transferId, "")
    assertEquals(m.fileName, "")
    assertEquals(m.fileSize, 0L)
    assertEquals(m.mimeType, "")
    assertEquals(m.status, "")
    assertEquals(m.savedPath, "")
    // selfattach 批（B′ 腿）：发送端本机真实路径是**可缺键**，缺席 ⇒ 空串（= 无值，不猜）。
    assertEquals(m.deviceOutPath, "")
    assertEquals(m.batchId, "")
    assertEquals(m.attachmentIndex, 0)
    assertEquals(m.attachmentCount, 1)
  }

  test("② 可缺键**在场但为 null** ⇒ 缺省补齐（不跳过、不抛）") {
    val raw = """{"d2":[{"msgId":"n-1","direction":"in","kind":"text","ts":1,
      "text":null,"origin":null,"fileSize":null,"attachmentCount":null}]}"""
    val d = decode(raw)
    assertEquals(d.skipped, Nil)
    val m = one(raw)
    assertEquals(m.text, "")
    assertEquals(m.origin, "user")
    assertEquals(m.fileSize, 0L)
    assertEquals(m.attachmentCount, 1)
  }

  // ── ① 禁整表失败：条目级失败只影响该条 ────────────────────────────────

  test("① 缺必给键 msgId 的条目被跳过，**其余条目照常解出**（禁整表失败）") {
    val raw = """{"d2":[
      {"direction":"in","kind":"text","ts":2,"text":"defective"},
      {"msgId":"ok-1","direction":"in","kind":"text","ts":1,"text":"healthy"},
      {"msgId":"ok-2","direction":"out","kind":"text","ts":3,"text":"healthy2"}]}"""
    val d = decode(raw)
    assertEquals(d.messages("d2").map(_.msgId), List("ok-1", "ok-2"), "其余条目必须照常解出")
    assertEquals(d.skipped.size, 1)
    assertEquals(d.skipped.head.deviceId, "d2")
    assertEquals(d.skipped.head.index, 0)
    assert(d.skipped.head.reason.nonEmpty, "跳过必须带原因（禁静默）")
  }

  test("① 设备值不是数组 ⇒ 该设备跳过、**其余设备照常解出**") {
    val raw = """{"d2":[{"msgId":"k-1","direction":"in","kind":"text","ts":1}],"d9":{"msgId":"x"}}"""
    val d = decode(raw)
    assertEquals(d.messages.get("d2").map(_.map(_.msgId)), Some(List("k-1")))
    assertEquals(d.messages.get("d9"), Some(Nil))
    assertEquals(d.skipped.map(_.deviceId), List("d9"))
    assertEquals(d.skipped.head.index, -1)
  }

  test("① 顶层不是对象 / 非 JSON ⇒ 表级 Left（无「其余条目」可救；空表语义由调用方保持）") {
    assert(DropboxLedger.decode("[{\"msgId\":\"x\"}]").isLeft, "顶层数组 ⇒ Left")
    assert(DropboxLedger.decode("{\n").isLeft, "非法 JSON ⇒ Left")
  }

  test("① 空对象 ⇒ 空表、零跳过（与改前同值，回 [] 语义不变）") {
    val d = decode("{}")
    assertEquals(d.messages, Map.empty[String, List[DropboxMessage]])
    assertEquals(d.skipped, Nil)
  }

  // ── ③ 缺席 ≠ 类型错：类型不符不得静默取缺省 ─────────────────────────────

  test("③ 可缺键在场但类型不符 ⇒ 跳过该条（不静默伪造缺省值）") {
    val raw = """{"d2":[{"msgId":"bad","direction":"in","kind":"text","ts":1,"text":123}]}"""
    val d = decode(raw)
    assertEquals(d.messages("d2"), Nil, "类型不符者不得以 text=\"\" 混进表里")
    assertEquals(d.skipped.size, 1)
  }

  // ── ④ 零回归：全键台账逐字段原样照读 ─────────────────────────────────

  test("④ 全键台账 ⇒ 逐字段照读、零跳过、顺序保持") {
    val d = decode(Full)
    assertEquals(d.skipped, Nil)
    val ms = d.messages("d2")
    assertEquals(ms.map(_.msgId), List("m-1", "m-2"))
    assertEquals(ms.head.text, "hello")
    assertEquals(ms.head.origin, "user")
    assertEquals(ms(1).origin, "agent", "在场的 origin 必须逐字照读（不夹带归一化）")
    assertEquals(ms(1).transferId, "t-9")
    assertEquals(ms(1).fileName, "a.bin")
    assertEquals(ms(1).fileSize, 7L)
    assertEquals(ms(1).status, "completed")
    assertEquals(ms(1).savedPath, "/tmp/a.bin")
    // selfattach 批：键在场 ⇒ **逐字照读**（不夹带 basename / 不重算 / 不归一化）。
    assertEquals(ms(1).deviceOutPath, "/Users/kaiyu/send/a.bin")
    assertEquals(ms(1).batchId, "b-1")
    assertEquals(ms(1).attachmentIndex, 1)
    assertEquals(ms(1).attachmentCount, 2)
  }

  test("④ 编码器未动：解出后再编码 ⇒ 16 键齐备（persistMessages 形态不变）") {
    val out = one(OnlyRequired).asJson.asObject.getOrElse(fail("encoded message is not an object"))
    assertEquals(out.keys.toList.sorted.size, 16)
    assertEquals(out("msgId"), Some(io.circe.Json.fromString("r-1")))
    assertEquals(out("origin"), Some(io.circe.Json.fromString("user")))
    // selfattach 批：新键在编码面**必在场**（值可为空串）——本机前端帧据此读路径。
    assertEquals(out("deviceOutPath"), Some(io.circe.Json.fromString("")))
  }

  // ── 结构断言：两个键集互补且并集 = 全字段（防「加字段忘登记语义」漂移）──

  test("键集完整性：必给键 ∪ 可缺键 = 本 case class 的全部字段，且两集互斥") {
    val fields = DropboxMessage("", "", "", 0L).productElementNames.toList.sorted
    val declared = (DropboxMessage.RequiredKeys ++ DropboxMessage.OptionalKeys).sorted
    assertEquals(declared, fields, "字段集与键集漂移 ⇒ 必须同批更新缺席语义（本 spec 即守卫）")
    assertEquals(DropboxMessage.RequiredKeys.toSet.intersect(DropboxMessage.OptionalKeys.toSet), Set.empty[String])
    assertEquals(DropboxMessage.RequiredKeys.size, 4)
    assertEquals(DropboxMessage.OptionalKeys.size, 12)
  }
