package nebflow.neblink

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import nebflow.neblink.FriendCodecs.given

/**
 * 附件预览字段 · **契约编解码单测** —— **字段方言双兼容批**（2026-09-22）。
 *
 * 判据面（逐条 → 本 spec 的用例）：
 *   ⓪ **方言登记表**：两版方言各登记一条、键序**算出**（不是抄来的一份常量）、
 *      未知方言 id ⇒ `None`（发面**拒绝**，不静默替换）；
 *   ① **收面双形态**：`{mime,w,h,size,b64}`（A）与 `{fmt,w,h,bytes,sha256,b64}`（B）
 *      **都**解得进，且各自**按键集**识别（A 的主体不满足 B 的读法、反向亦然）；
 *   ② **发面不写死**：同一内部表示按**方言 id 参数**写出两套键名（键序 = 该方言算出的键序）；
 *   ③ **未知 / 第三种方言** ⇒ 收面回落无预览（`None`）、发面显式拒绝（`None`），**不抛、不崩**；
 *   ④ **往返恒等**：解析结果自带方言 id ⇒ `encodePreview` 按原方言回写，`decode∘encode` 逐字恒等（两版各一）；
 *   ⑤ `None` ⇒ 出参文本**无该键**（逐字片段证明不是 `"preview":null`）；
 *   ⑥ **旧端字节不变**：无 `preview` 件的出参与**改前字面量夹具**逐字节相等；
 *   ⑦ 上限族（字节数 / 长边 / 摘要拼写 / b64 串长自洽）两版**逐条同判**；
 *   ⑧ 旧服务端形态（无 `preview` 键）⇒ 解码零错误、取值逐字段不变；
 *   ⑨ 键序：`preview` **追加末位**、既有键前缀逐字节不变。
 *
 * 🔴 本 spec 是**生产 codec** 的 spec（`FriendCodecs` 的 `Encoder/Decoder[AttachmentSummary]`），
 * 不是一致性夹具的 spec —— 上一批的镜像面（`MirrorPreview` / `MirrorAttachment` /
 * `readPreview`）已按该文件自己的计划**退役**（判定面收敛到 `AttachmentPreviewContract`）。
 */
class AttachmentPreviewContractSpec extends FunSuite:

  private def jsonOf(s: String): Json =
    parse(s) match
      case Right(j) => j
      case Left(e)  => fail(s"bad test json: ${e.getMessage}")

  /** **改前字面量夹具**（判据 ⑥ 的独立参照）：这是本批改动前 `AttachmentSummary` 的唯一出参
    * 形态 —— 手写字面量（**不是**由被测编码器产出），故「逐字节相等」是同一条判据的真检查。 */
  private val legacyLiteral = """{"id":"a1","name":"p.jpg","size":900000,"mime":"image/jpeg","sha256":"ab12","state":"ready"}"""
  private val legacyNoMimeLiteral = """{"id":"a1","name":"p.jpg","size":900000,"sha256":"ab12","state":"ready"}"""

  private val legacySummary =
    AttachmentSummary(id = "a1", name = "p.jpg", size = 900000L, sha256 = "ab12", state = "ready", mime = Some("image/jpeg"))

  private val b64Nine = "AQIDBAUGBwgJ"          // 9 字节 ⇒ 12 字符（4 × ceil(9/3)）
  private val sha64   = "a" * 64

  /** 方言 A 的夹具（五键，full MIME，无摘要）。 */
  private def previewAJson(w: Int = 4, h: Int = 3, size: Long = 9L, b64: String = b64Nine): String =
    s"""{"mime":"image/webp","w":$w,"h":$h,"size":$size,"b64":"$b64"}"""

  /** 方言 B 的夹具（六键，裸格式名，带摘要）。 */
  private def previewBJson(w: Int = 4, h: Int = 3, size: Long = 9L, sha: String = sha64, b64: String = b64Nine): String =
    s"""{"fmt":"webp","w":$w,"h":$h,"bytes":$size,"sha256":"$sha","b64":"$b64"}"""

  private val goodA = jsonOf(previewAJson())
  private val goodB = jsonOf(previewBJson())

  // ── 判据 ⓪：方言登记表（键序算出 / 未知 id 拒绝）────────────────────

  test("dialects: both known spellings are registered; key order is derived; unknown ids are refused") {
    assertEquals(AttachmentPreviewContract.PreviewKey, "preview")
    assertEquals(AttachmentPreviewContract.dialectIds, List("mime5", "fmt6"))
    assertEquals(AttachmentPreviewContract.previewDialect("mime5").map(_.keyOrder), Some(List("mime", "w", "h", "size", "b64")))
    assertEquals(AttachmentPreviewContract.previewDialect("fmt6").map(_.keyOrder), Some(List("fmt", "w", "h", "bytes", "sha256", "b64")))
    assertEquals(AttachmentPreviewContract.previewDialect("never-heard-of-it"), None)
    // 默认发面方言必须是**登记在册**的一条（政策旋钮不能指到表外）
    assert(AttachmentPreviewContract.previewDialect(AttachmentPreviewContract.DefaultSendDialectId).isDefined)
    // 值域与阈值（一个数一处）
    assertEquals(AttachmentPreviewContract.Mimes, List("image/webp", "image/jpeg"))
    assertEquals(AttachmentPreviewContract.MaxLongEdge, 1280)
    assertEquals(AttachmentPreviewContract.BaseQuality, 0.82)
    assertEquals(AttachmentPreviewContract.SoftCapBytes, 184320L)
    assertEquals(AttachmentPreviewContract.HardCapBytes, 262144L)
    assertEquals(AttachmentPreviewContract.MaxB64Len, 349528L)
    assertEquals(AttachmentPreviewContract.base64LengthOf(9L), 12L)
    assertEquals(AttachmentPreviewContract.base64LengthOf(3L), 4L)
    assertEquals(AttachmentPreviewContract.base64LengthOf(-1L), 0L)
  }

  // ── 判据 ①：收面双形态（按键集识别，零版本协商）───────────────────

  test("receive: both dialects parse to one internal shape, each identified by its OWN key set") {
    val a = AttachmentPreviewContract.parsePreview(goodA)
    val b = AttachmentPreviewContract.parsePreview(goodB)
    assert(a.isDefined, "the five-key spelling must parse")
    assert(b.isDefined, "the six-key spelling must parse")
    // 归一化：同一个内部形状、同一个 mime 拼写、同一个字节数；摘要只有 B 带
    assertEquals(a.get.mime, "image/webp")
    assertEquals(b.get.mime, "image/webp")
    assertEquals(a.get.size, b.get.size)
    assertEquals(a.get.w, b.get.w)
    assertEquals(a.get.b64, b.get.b64)
    assertEquals(a.get.sha256, None)
    assertEquals(b.get.sha256, Some(sha64))
    // 「识别到哪一版」= 归一化对象自带的信息（收-发往返的依据）
    assertEquals((a.get.dialectId, b.get.dialectId), ("mime5", "fmt6"))
    // 🔴 判据**按键集**分派：交错喂 ≠ 认识
    assertEquals(AttachmentPreviewContract.parseIn(AttachmentPreviewContract.MimeFiveKey, goodB), None)
    assertEquals(AttachmentPreviewContract.parseIn(AttachmentPreviewContract.FormatSixKey, goodA), None)
    // 附件层入口（真收面路径）：同一个附件对象，两版载荷都读得进
    assertEquals(AttachmentPreviewContract.parsePreviewAttachment(jsonOf(s"""{"id":"a1","preview":${previewAJson()}}""")).map(_.dialectId), Some("mime5"))
    assertEquals(AttachmentPreviewContract.parsePreviewAttachment(jsonOf(s"""{"id":"a1","preview":${previewBJson()}}""")).map(_.dialectId), Some("fmt6"))
  }

  // ── 判据 ②：发面不写死（同一表示 → 两套键名，由参数决定）─────────────

  test("send: one internal value is spelled two ways, chosen by the dialect id argument") {
    val p = AttachmentPreviewContract.parsePreview(goodA).get
    val asA = AttachmentPreviewContract.encodeInDialect("mime5", p)
    val asB = AttachmentPreviewContract.encodeInDialect("fmt6", p)
    assertEquals(asA.map(_.asObject.get.keys.toList), Some(List("mime", "w", "h", "size", "b64")))
    // B 需要摘要，而 A 的载荷没带 ⇒ **拒绝**（不发半截字段）
    assertEquals(asB, None)
    val b = AttachmentPreviewContract.parsePreview(goodB).get
    val bAsB = AttachmentPreviewContract.encodeInDialect("fmt6", b)
    assertEquals(bAsB.map(_.asObject.get.keys.toList), Some(List("fmt", "w", "h", "bytes", "sha256", "b64")))
    // 同一个 b 换成 A 的拼写：键名换、数值不变（证明差异**只在拼写**）
    val bAsA = AttachmentPreviewContract.encodeInDialect("mime5", b)
    assertEquals(bAsA.map(_.asObject.get.keys.toList), Some(List("mime", "w", "h", "size", "b64")))
    assertEquals(bAsA.flatMap(j => j.hcursor.get[Long]("size").toOption), Some(9L))
    assertEquals(bAsB.flatMap(j => j.hcursor.get[Long]("bytes").toOption), Some(9L))
    // 未登记方言 id ⇒ 拒绝（**不回落**到默认方言）
    assertEquals(AttachmentPreviewContract.encodeInDialect("never-heard-of-it", b), None)
    // 该方言不会说的 mime ⇒ 拒绝
    assertEquals(AttachmentPreviewContract.encodeInDialect("mime5", b.copy(mime = "image/gif")), None)
  }

  // ── 判据 ③：未知 / 第三种方言 ⇒ 收面回落、发面拒绝，零异常 ────────────

  test("unknown dialect: the receive side falls back to no-preview; the send side refuses explicitly") {
    // 第三种方言（键名既不是 A 也不是 B 的那套）
    val thirdParty = jsonOf("""{"format":"image/webp","width":4,"height":3,"length":9,"data":"AQIDBAUGBwgJ"}""")
    assertEquals(AttachmentPreviewContract.parsePreview(thirdParty), None)
    // 混合体（A 的 mime 键 + B 的 bytes/sha256 键）同样不是任何一版方言的形状
    val hybrid = jsonOf(s"""{"mime":"image/webp","w":4,"h":3,"bytes":9,"sha256":"$sha64","b64":"$b64Nine"}""")
    assertEquals(AttachmentPreviewContract.parsePreview(hybrid), None)
    // 附件整体仍然解得出（降级可见、**不**炸）；既有键逐字段不变
    val att = jsonOf(s"""{"id":"a1","name":"p.jpg","size":900000,"mime":"image/jpeg","sha256":"ab12","state":"ready","preview":$thirdParty}""")
    assertEquals(att.as[AttachmentSummary].map(_.preview), Right(None))
    assertEquals(att.as[AttachmentSummary].map(a => (a.id, a.name, a.size, a.sha256, a.state)), Right(("a1", "p.jpg", 900000L, "ab12", "ready")))
    // 形状非对象
    assertEquals(AttachmentPreviewContract.parsePreview(Json.Null), None)
    assertEquals(AttachmentPreviewContract.parsePreview(Json.fromString("webp")), None)
    assertEquals(AttachmentPreviewContract.parsePreview(Json.arr()), None)
  }

  // ── 判据 ④：往返恒等（两版各一）──────────────────────────────────

  test("roundtrip: parse then encode (own dialect) is the identity on the wire shape, both dialects") {
    val a = AttachmentPreviewContract.parsePreview(goodA)
    assert(a.isDefined, "a well-formed five-key object must parse")
    assertEquals(AttachmentPreviewContract.encodePreview(a.get).map(_.noSpaces), Some(goodA.noSpaces))
    val b = AttachmentPreviewContract.parsePreview(goodB)
    assert(b.isDefined, "a well-formed six-key object must parse")
    assertEquals(AttachmentPreviewContract.encodePreview(b.get).map(_.noSpaces), Some(goodB.noSpaces))
    // 再解一遍仍是同一对象（幂等）
    assertEquals(AttachmentPreviewContract.parsePreview(AttachmentPreviewContract.encodePreview(b.get).get), b)
  }

  // ── 判据 ⑤⑥：None ⇒ 省键；旧端载荷逐字节不变 ──────────────────────

  test("absent preview: the key is omitted (never null) and the legacy payload is byte-identical") {
    val out = legacySummary.asJson.noSpaces
    assertEquals(out, legacyLiteral, "no-preview attachment must re-encode byte-for-byte as before the batch")
    assert(!out.contains(AttachmentPreviewContract.PreviewKey), "省键约束：preview=None ⇒ 不得出现该键")
    assert(!out.contains("null"), "省键约束：preview=None ⇒ 不得写 null")
    // mime 缺席那一支同样逐字节相等（键序 = id,name,size,sha256,state）
    val noMime = AttachmentSummary(id = "a1", name = "p.jpg", size = 900000L, sha256 = "ab12", state = "ready")
    assertEquals(noMime.asJson.noSpaces, legacyNoMimeLiteral)
    // 解码面：旧形态（无该键）与显式 null 都 ⇒ None，且既有六键取值不变
    assertEquals(jsonOf(legacyLiteral).as[AttachmentSummary], Right(legacySummary))
    assertEquals(jsonOf(s"""{"id":"a1","name":"p.jpg","size":900000,"mime":"image/jpeg","sha256":"ab12","state":"ready","preview":null}""").as[AttachmentSummary].toOption.flatMap(_.preview), None)
  }

  // ── 判据 ⑦：上限族逐条边界（两版同判）──────────────────────────────

  test("caps: from / 长边 / digest spelling / b64 length each have an exact boundary — in BOTH dialects") {
    // 字节数 ∈ 1..=262_144（两版各自的字节键名）
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewAJson(size = 0L, b64 = ""))), None)
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewAJson(size = 262145L, b64 = "A" * 349528))), None)
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewBJson(size = 0L, b64 = ""))), None)
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewBJson(size = 262145L, b64 = "A" * 349528))), None)
    // 边界内必须放行（否则上面的拒绝读数是同义反复）
    assert(AttachmentPreviewContract.parsePreview(goodA).isDefined)
    assert(AttachmentPreviewContract.parsePreview(goodB).isDefined)
    // 长边 ≤1280 且 w,h ≥1
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewAJson(w = 1281, h = 960))), None)
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewBJson(w = 1281, h = 960))), None)
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewBJson(w = -1, h = 3))), None)
    // 摘要拼写（只有 B 有这一键）
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewBJson(sha = "0" * 63))), None)
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewBJson(sha = "A" * 64))), None)
    assert(AttachmentPreviewContract.sha256HexOk("0" * 64))
    assert(!AttachmentPreviewContract.sha256HexOk("0" * 64 + " "))
    // B **必须**带摘要：把它整键删掉 ⇒ 不成形状（不是「退化成 A」）
    assertEquals(AttachmentPreviewContract.parsePreview(Json.fromFields(goodB.asObject.get.toList.filterNot(_._1 == "sha256"))), None)
    // b64 串长上限（先验长、再解码）
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewAJson(size = 9L, b64 = "A" * 349529))), None)
    // 长度与字节数不自洽（免解码完整性判据）
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewAJson(size = 9L, b64 = "AQIDBAU"))), None)
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewBJson(size = 9L, b64 = "AQIDBAU"))), None)
    // mime 取值域：A 只认 full MIME、B 只认裸格式名（互不接受对方的拼法）
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewAJson().replace("image/webp", "webp"))), None)
    assertEquals(AttachmentPreviewContract.parsePreview(jsonOf(previewBJson().replace("\"webp\"", "\"image/webp\""))), None)
    // 各自缺一个键 ⇒ 整体不可用（无「部分预览」形态）
    for k <- AttachmentPreviewContract.MimeFiveKey.keyOrder do
      val o = goodA.asObject.get.remove(k)
      assertEquals(AttachmentPreviewContract.parsePreview(Json.fromJsonObject(o)), None, s"missing '$k' must refuse the five-key field")
    for k <- AttachmentPreviewContract.FormatSixKey.keyOrder do
      val o = goodB.asObject.get.remove(k)
      assertEquals(AttachmentPreviewContract.parsePreview(Json.fromJsonObject(o)), None, s"missing '$k' must refuse the six-key field")
  }

  // ── 判据 ⑧：旧服务端形态（无 preview 键）⇒ 零报错、逐字段不变 ────────

  test("old server face: an attachment without preview decodes cleanly and keeps every legacy value") {
    val rows = jsonOf(s"""[{"id":"a1","name":"p.jpg","size":900000,"mime":"image/jpeg","sha256":"ab12","state":"ready"},
                         | {"id":"a2","name":"n.txt","size":10,"sha256":"cd","state":"uploading"}]""".stripMargin)
    val decoded = rows.as[List[AttachmentSummary]]
    assert(decoded.isRight, s"old-server payload must decode: $decoded")
    val head = decoded.toOption.get.head
    assertEquals(head, legacySummary)
    assertEquals(head.preview, None)
    // 网关 REST 出口的重编码（旧服务端 × 新客户端）：仍与该行的旧字节形态逐字节相等
    assertEquals(head.asJson.noSpaces, legacyLiteral)
    // 带**某一版方言**预览的那一行：既有键逐字段不变 + 预览按**那一版**回写
    val withA = jsonOf(s"""{"id":"a1","name":"p.jpg","size":900000,"mime":"image/jpeg","sha256":"ab12","state":"ready","preview":${previewAJson()}}""")
    val withB = jsonOf(s"""{"id":"a1","name":"p.jpg","size":900000,"mime":"image/jpeg","sha256":"ab12","state":"ready","preview":${previewBJson()}}""")
    assertEquals(withA.asJson.noSpaces, legacyLiteral.dropRight(1) + "," + s""""preview":${previewAJson()}}""")
    assertEquals(withB.asJson.noSpaces, legacyLiteral.dropRight(1) + "," + s""""preview":${previewBJson()}}""")
  }

  // ── 判据 ⑨：键序 —— 既有键前缀不变、preview 追加末位 ────────────────

  test("key order: legacy prefix is byte-identical and preview is appended last (both dialects)") {
    for (wire, expectedDialect) <- List((goodA, "mime5"), (goodB, "fmt6")) do
      val withPreview = legacySummary.copy(preview = AttachmentPreviewContract.parsePreview(wire))
      val out = withPreview.asJson.noSpaces
      assert(out.startsWith(legacyLiteral.dropRight(1) + ","), s"legacy key prefix changed: $out")
      assert(out.endsWith("}"), s"unexpected tail: $out")
      assert(out.contains("," + "\"" + AttachmentPreviewContract.PreviewKey + "\":"), s"preview must be appended last: $out")
      // 附件对象面往返：解码 → 预览逐字段等价 + 方言 id 保持（出参 → 入参同形）
      val back = jsonOf(out).as[AttachmentSummary].toOption.get
      assertEquals(back, withPreview)
      assertEquals(back.preview.map(_.dialectId), Some(expectedDialect))
      // 内层键序 = 该方言算出的键序
      assertEquals(
        AttachmentPreviewContract.encodePreview(back.preview.get).map(_.asObject.get.keys.toList),
        AttachmentPreviewContract.previewDialect(expectedDialect).map(_.keyOrder)
      )
  }

end AttachmentPreviewContractSpec
