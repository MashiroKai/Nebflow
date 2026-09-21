package nebflow.neblink

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import nebflow.neblink.FriendCodecs.given

/**
 * 附件预览字段 · **契约编解码单测**（传输集成批 2026-09-21 · 判据 §3.1）。
 *
 * 判据面（逐条 → 本 spec 的用例）：
 *   ① 六键逐字往返（`previewOf` ∘ `encodePreview` 恒等）；
 *   ② `fmt` 白名单外的值 ⇒ 判不可用（`None`，**不**抛、**不**折叠成别的值）；
 *   ③ `None` ⇒ 出参文本**无该键**（逐字片段证明不是 `"preview":null`）；
 *   ④ **旧端字节不变**：无 `preview` 件的出参与**改前字面量夹具**逐字节相等；
 *   ⑤ 上限族（`bytes` / 长边 / `sha256` 拼写 / `b64` 串长）逐条边界；
 *   ⑥ 旧服务端形态（无 `preview` 键）⇒ 解码零错误、取值逐字段不变（§B.7 #14）；
 *   ⑦ 键序：`preview` **追加末位**、既有六键前缀逐字节不变。
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

  /** **改前字面量夹具**（判据 ④ 的独立参照）：这是本批改动前 `AttachmentSummary` 的唯一出参
    * 形态 —— 手写字面量（**不是**由被测编码器产出），故「逐字节相等」是同一条判据的真检查。 */
  private val legacyLiteral = """{"id":"a1","name":"p.jpg","size":900000,"mime":"image/jpeg","sha256":"ab12","state":"ready"}"""
  private val legacyNoMimeLiteral = """{"id":"a1","name":"p.jpg","size":900000,"sha256":"ab12","state":"ready"}"""

  private val legacySummary =
    AttachmentSummary(id = "a1", name = "p.jpg", size = 900000L, sha256 = "ab12", state = "ready", mime = Some("image/jpeg"))

  /** 9 字节的合法 base64（长度 = 4 × ⌈9/3⌉ = 12）与其声明摘要（64 位小写 hex 拼写）。 */
  private val b64Nine = "AQIDBAUGBwgJ"
  private val sha64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

  /** 合法预览（六键；`sha256` 只做拼写校验 —— 服务端不解码，本 spec 同样不重算摘要）。 */
  private def previewJson(
    fmt: String = "webp",
    w: Int = 4,
    h: Int = 3,
    bytes: Long = 9L,
    sha256: String = sha64,
    b64: String = b64Nine
  ): String = s"""{"fmt":"$fmt","w":$w,"h":$h,"bytes":$bytes,"sha256":"$sha256","b64":"$b64"}"""

  private val goodPreview = Json.obj(
    "fmt"    -> Json.fromString("webp"),
    "w"      -> Json.fromInt(4),
    "h"      -> Json.fromInt(3),
    "bytes"  -> Json.fromLong(9L),
    "sha256" -> Json.fromString(sha64),
    "b64"    -> Json.fromString(b64Nine)
  )

  // ── 判据 ①（常量面）：键集 / 白名单 / 上限族与线面逐字同值 ──────────────

  test("constants: key set / format whitelist / cap family are the §B.1 values") {
    assertEquals(AttachmentPreviewContract.PreviewKey, "preview")
    assertEquals(AttachmentPreviewContract.PreviewKeys, List("fmt", "w", "h", "bytes", "sha256", "b64"))
    assertEquals(AttachmentPreviewContract.LegacyKeyOrder, List("id", "name", "size", "mime", "sha256", "state"))
    assertEquals(AttachmentPreviewContract.Formats, List("webp", "jpeg"))
    assertEquals(AttachmentPreviewContract.MaxLongEdge, 1280)
    assertEquals(AttachmentPreviewContract.BaseQuality, 0.82)
    assertEquals(AttachmentPreviewContract.SoftCapBytes, 184320L)
    assertEquals(AttachmentPreviewContract.HardCapBytes, 262144L)
    // b64 串长上限 = 4 × ⌈262144/3⌉（**算出来的**，禁第二处拼数字）
    assertEquals(AttachmentPreviewContract.MaxB64Len, 349528L)
    assertEquals(AttachmentPreviewContract.base64LengthOf(9L), 12L)
    assertEquals(AttachmentPreviewContract.base64LengthOf(3L), 4L)
    assertEquals(AttachmentPreviewContract.base64LengthOf(-1L), 0L)
    // mime ⇄ fmt 唯一映射表（生成器出口 ↔ 线面取值域）
    assertEquals(AttachmentPreviewContract.Mimes, List("image/webp", "image/jpeg"))
    assertEquals(AttachmentPreviewContract.formatOfMime("image/webp"), Some("webp"))
    assertEquals(AttachmentPreviewContract.formatOfMime("image/jpeg"), Some("jpeg"))
    assertEquals(AttachmentPreviewContract.formatOfMime("image/png"), None)
  }

  // ── 判据 ①（往返）：六键逐字往返 ────────────────────────────────────

  test("roundtrip: six keys survive encode → parse → encode verbatim") {
    val decoded = AttachmentPreviewContract.previewOf(goodPreview)
    assert(decoded.isDefined, "a well-formed six-key object must parse")
    assertEquals(decoded.get.fmt, "webp")
    assertEquals(decoded.get.bytes, 9L)
    val encoded = AttachmentPreviewContract.encodePreview(decoded.get)
    assertEquals(encoded.noSpaces, goodPreview.noSpaces, "encode ∘ parse must be the identity on the wire shape")
    assertEquals(AttachmentPreviewContract.previewOf(encoded).map(_.b64), Some(b64Nine))
  }

  // ── 判据 ②：fmt 白名单外 ⇒ None（且不破坏既有解码面）────────────────

  test("fmt whitelist: jpg / image-webp / WEBP / gif are all refused (no throw, no folding)") {
    for bad <- List("jpg", "image/webp", "WEBP", "jpeg2000", "gif", "") do
      val j = jsonOf(s"""{"id":"a1","preview":${previewJson(fmt = bad)}}""")
      assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(fmt = bad))), None, s"fmt=$bad must be refused")
      assert(j.as[AttachmentSummary].isRight, s"fmt=$bad must not break the attachment decoder")
      assertEquals(j.as[AttachmentSummary].toOption.flatMap(_.preview), None)
    // 两档出口必须都放行（正例；否则上面的拒绝读数是同义反复）
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(fmt = "webp"))).map(_.fmt), Some("webp"))
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(fmt = "jpeg"))).map(_.fmt), Some("jpeg"))
  }

  // ── 判据 ③ + ④：None ⇒ 省键；旧端载荷逐字节不变 ──────────────────────

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

  // ── 判据 ⑤：上限族逐条边界 ──────────────────────────────────────────

  test("caps: bytes / long edge / sha256 spelling / b64 length each have an exact boundary") {
    // bytes ∈ 1..=262_144
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(bytes = 0L, b64 = ""))), None)
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(bytes = 262145L, b64 = "A" * 349528))), None)
    // 边界内（b64 串长自洽）必须放行：9 字节这条就是下界邻域
    assert(AttachmentPreviewContract.previewOf(jsonOf(previewJson())).isDefined)
    // 长边 ≤1280 且 w,h ≥1
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(w = 1281, h = 960, b64 = b64Nine))), None)
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(w = 0, h = 0, b64 = ""))), None)
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(w = -1, h = 3, b64 = b64Nine))), None)
    // sha256 必须 64 位小写 hex
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(sha256 = "0" * 63))), None)
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(sha256 = "A" * 64))), None)
    assert(AttachmentPreviewContract.sha256HexOk("0" * 64))
    assert(!AttachmentPreviewContract.sha256HexOk("0" * 64 + " "))
    // b64 串长上限（先验长、再解码）：349_529 必拒 —— 即使 bytes 声明在界内
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(bytes = 9L, b64 = "A" * 349529))), None)
    // 长度与 bytes 不自洽（免解码完整性判据）
    assertEquals(AttachmentPreviewContract.previewOf(jsonOf(previewJson(bytes = 9L, b64 = "AQIDBAU"))), None)
    // 6 键缺一 ⇒ 整体不可用（无「部分预览」形态）
    for k <- AttachmentPreviewContract.PreviewKeys do
      val o = goodPreview.asObject.get.remove(k)
      assertEquals(AttachmentPreviewContract.previewOf(Json.fromJsonObject(o)), None, s"missing '$k' must refuse the whole field")
    // 形状非对象
    assertEquals(AttachmentPreviewContract.previewOf(Json.Null), None)
    assertEquals(AttachmentPreviewContract.previewOf(Json.fromString("webp")), None)
    assertEquals(AttachmentPreviewContract.previewOf(Json.arr()), None)
  }

  // ── 判据 ⑥：旧服务端形态（§B.7 #14）⇒ 零报错、逐字段不变 ──────────────

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
  }

  // ── 判据 ⑦：键序 —— 既有六键前缀不变、preview 追加末位 ────────────────

  test("key order: legacy prefix is byte-identical and preview is appended last") {
    val withPreview = legacySummary.copy(preview = AttachmentPreviewContract.previewOf(goodPreview))
    val out = withPreview.asJson.noSpaces
    assert(out.startsWith(legacyLiteral.dropRight(1) + ","), s"legacy key prefix changed: $out")
    assert(out.endsWith("}"), s"unexpected tail: $out")
    assert(out.contains("," + "\"" + AttachmentPreviewContract.PreviewKey + "\":"), s"preview must be appended last: $out")
    // 附件对象面往返：解码 → 预览六键逐字段等价（出参 → 入参同形，§B.1「两侧同批扩键」）
    val back = jsonOf(out).as[AttachmentSummary].toOption.get
    assertEquals(back, withPreview)
    assertEquals(back.preview.map(_.fmt), Some("webp"))
    // 六键编码后的键序 = PreviewKeys 声明序（`fmt,w,h,bytes,sha256,b64`）
    assertEquals(AttachmentPreviewContract.encodePreview(back.preview.get).asObject.get.keys.toList, AttachmentPreviewContract.PreviewKeys)
  }

end AttachmentPreviewContractSpec
