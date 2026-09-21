package nebflow.neblink

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import nebflow.neblink.AttachmentPreviewContract.given
import nebflow.neblink.FriendCodecs.given

/**
 * `AttachmentPreviewContract` 一致性夹具 spec —— 好友/群图片预览传输批 · 解耦包。
 *
 * 本 spec 只验**声明面**（字段名 / 可选性 / 双向编解码语义），**不验** E2E（真渲染收发
 * round / 兼容矩阵四格 / 下载 sha 一致 = 候跨仓腿 ①，本批明令不做）。
 *
 * 三例必测（作者裁定）：**缺字段 / 未知字段 / 空值**；另加两条对照读数：
 *   · **「旧端载荷逐字节不变」**：镜像编码器在 `preview = None` 时的输出，与**未改**的真源
 *     编码器（`FriendCodecs` 的 `Encoder[AttachmentSummary]`）**逐字节**相等；
 *   · **未改的生产编码器会丢 `preview` 键**（本批禁改 `NeblinkModel` ⇒ 这是**已知**读数，
 *     接线批必须按本件口径扩键 —— 见本 spec 末例的结论行）。
 */
class AttachmentPreviewContractSpec extends FunSuite:

  /** 测试用 JSON 解析（失败即 fail-fast，禁静默）。 */
  private def jsonOf(s: String): Json =
    parse(s) match
      case Right(j) => j
      case Left(e)  => fail(s"bad test json: ${e.getMessage}")

  /** 真源（未改）编码器产出 —— 所有「字节不变」对照的**唯一**参照。 */
  private val legacySummary =
    AttachmentSummary(id = "a1", name = "p.jpg", size = 900000L, sha256 = "ab12", state = "ready", mime = Some("image/jpeg"))
  private val legacyBytes = legacySummary.asJson.noSpaces

  private val goodPreviewJson =
    """{"mime":"image/webp","w":4,"h":3,"size":9,"b64":"AQIDBAUGBwgJ"}"""

  /** 合法的 9 字节 base64（长度 = 4 × ceil(9/3) = 12）。 */
  private val goodPreview = jsonOf(goodPreviewJson)

  // ── 例 0：常量单点（数值/键集与客户端同值同源）──────────────

  test("constants: key set / mime set / parameter baseline are the declared ones") {
    assertEquals(AttachmentPreviewContract.PreviewKey, "preview")
    assertEquals(AttachmentPreviewContract.PreviewKeys, List("mime", "w", "h", "size", "b64"))
    assertEquals(AttachmentPreviewContract.LegacyKeyOrder, List("id", "name", "size", "mime", "sha256", "state"))
    assertEquals(AttachmentPreviewContract.Mimes, List("image/webp", "image/jpeg"))
    assertEquals(AttachmentPreviewContract.MaxLongEdge, 1280)
    assertEquals(AttachmentPreviewContract.BaseQuality, 0.82)
    assertEquals(AttachmentPreviewContract.SoftCapBytes, 184320L)
    assertEquals(AttachmentPreviewContract.HardCapBytes, 262144L)
    assertEquals(AttachmentPreviewContract.base64LengthOf(9L), 12L)
  }

  // ── 例 1：缺字段 ⇒ 旧消息现行为不变（逐字节）──────────────

  test("missing field: legacy attachment decodes to no-preview and re-encodes byte-identically") {
    val legacyJson = jsonOf(legacyBytes)
    assertEquals(AttachmentPreviewContract.readPreview(legacyJson), None)
    assertEquals(AttachmentPreviewContract.readDeclaration(legacyJson), None)
    assertEquals(AttachmentPreviewContract.parsePreview(Json.Null), None)

    // 真源解码面（未改）读旧形态：零错误、零字段变化。
    assertEquals(legacyJson.as[AttachmentSummary], Right(legacySummary))

    // 镜像编码器在 preview=None 时与真源编码器**逐字节相等**（skip_serializing_if 等价语义）。
    val mirror = AttachmentPreviewContract.MirrorAttachment(
      id = "a1", name = "p.jpg", size = 900000L, sha256 = "ab12", state = "ready", mime = Some("image/jpeg"), preview = None
    )
    assertEquals(mirror.asJson.noSpaces, legacyBytes)

    // mime 缺席那一支同样逐字节相等（键序 = id,name,size,sha256,state）。
    val noMime = AttachmentSummary(id = "a1", name = "p.jpg", size = 900000L, sha256 = "ab12", state = "ready")
    val mirrorNoMime = AttachmentPreviewContract.MirrorAttachment(
      id = "a1", name = "p.jpg", size = 900000L, sha256 = "ab12", state = "ready", mime = None, preview = None
    )
    assertEquals(mirrorNoMime.asJson.noSpaces, noMime.asJson.noSpaces)
    assert(!mirror.asJson.noSpaces.contains("preview"), "省键约束：preview=None ⇒ 不得出现该键")
  }

  // ── 例 2：未知 / 多余字段 ⇒ 忽略不炸，既有键零增删 ──────────

  test("unknown field: extra keys are ignored, and an existing decoder tolerates the additivity") {
    val withExtra = jsonOf(
      s"""{"id":"a1","name":"p.jpg","size":900000,"sha256":"ab12","state":"ready","mime":"image/jpeg",
         | "futureKey":{"keep":true},
         | "preview":{"mime":"image/webp","w":4,"h":3,"size":9,"b64":"AQIDBAUGBwgJ","extra":"ignore","future":42}}""".stripMargin
    )
    val p = AttachmentPreviewContract.readPreview(withExtra)
    assert(p.isDefined, "preview with unknown sub-keys must still parse")
    assertEquals(p.map(_.mime), Some("image/webp"))
    assertEquals(p.map(_.size), Some(9L))

    // 归一化结果只带白名单五键 ⇒ 多余键不进结果（也不报错）。
    val encoded = p.get.asJson.asObject.get.keys.toList
    assertEquals(encoded.sorted, AttachmentPreviewContract.PreviewKeys.sorted)

    // 真源解码面（未改）面对**加性键**零影响：解得出、不抛、既有六键取值不变。
    val decodedByReal = withExtra.as[AttachmentSummary]
    assertEquals(decodedByReal, Right(legacySummary))

    // 未知键不参与判定：preview 形状不全时照样回落 None（不因多余键而"救活"）。
    val shapeBroken = jsonOf("""{"preview":{"mime":"image/webp","w":4,"extra":"x"}}""")
    assertEquals(AttachmentPreviewContract.readPreview(shapeBroken), None)
  }

  // ── 例 3：空值 ⇒ 一律回落「无预览」（fail-closed）──────────

  test("empty values: null / blank / empty object / out-of-range all fall back to no-preview") {
    val bad: List[(String, String)] = List(
      "preview-null"          -> """{"id":"a1","preview":null}""",
      "preview-string"        -> """{"id":"a1","preview":"image/webp"}""",
      "preview-array"         -> """{"id":"a1","preview":[]}""",
      "preview-empty-object"  -> """{"id":"a1","preview":{}}""",
      "mime-empty"            -> """{"id":"a1","preview":{"mime":"","w":4,"h":3,"size":9,"b64":"AQIDBAUGBwgJ"}}""",
      "mime-out-of-set"       -> """{"id":"a1","preview":{"mime":"image/png","w":4,"h":3,"size":9,"b64":"AQIDBAUGBwgJ"}}""",
      "b64-empty"             -> """{"id":"a1","preview":{"mime":"image/webp","w":4,"h":3,"size":9,"b64":""}}""",
      "b64-length-mismatch"   -> """{"id":"a1","preview":{"mime":"image/webp","w":4,"h":3,"size":9,"b64":"AQIDBAU"}}""",
      "size-zero"             -> """{"id":"a1","preview":{"mime":"image/webp","w":4,"h":3,"size":0,"b64":""}}""",
      "size-over-hard-cap"    -> s"""{"id":"a1","preview":{"mime":"image/webp","w":4,"h":3,"size":${AttachmentPreviewContract.HardCapBytes + 1},"b64":"AQIDBAUGBwgJ"}}""",
      "w-zero"                -> """{"id":"a1","preview":{"mime":"image/webp","w":0,"h":3,"size":9,"b64":"AQIDBAUGBwgJ"}}""",
      "w-string"              -> """{"id":"a1","preview":{"mime":"image/webp","w":"4","h":3,"size":9,"b64":"AQIDBAUGBwgJ"}}"""
    )
    for (label, raw) <- bad do
      val j = jsonOf(raw)
      assertEquals(AttachmentPreviewContract.readPreview(j), None, s"$label must fall back to no-preview")
      // 空值/越界**不**使真源解码面变红（加性键的失败只影响自身）。
      assert(j.as[AttachmentSummary].isRight, s"$label must not break the existing decoder")
  }

  // ── 例 4：字段在场时的双向读数（前缀字节不变 + 末位追加）──────

  test("present field: legacy key prefix is byte-identical and preview is appended last") {
    val mirror = AttachmentPreviewContract.MirrorAttachment(
      id = "a1", name = "p.jpg", size = 900000L, sha256 = "ab12", state = "ready", mime = Some("image/jpeg"),
      preview = AttachmentPreviewContract.parsePreview(goodPreview)
    )
    val out = mirror.asJson.noSpaces
    // 既有六键的**前缀**逐字节不变（把真源输出末尾的 `}` 换成 `,` 即为期望前缀）。
    assert(out.startsWith(legacyBytes.dropRight(1) + ","), s"legacy prefix changed: $out")
    assert(out.endsWith("}"), s"unexpected tail: $out")
    assert(out.contains("," + "\"" + AttachmentPreviewContract.PreviewKey + "\":"), s"preview must be appended last: $out")
    // 往返：镜像编码 → 镜像解码，preview 逐字段等价。
    assertEquals(AttachmentPreviewContract.readPreview(jsonOf(out)).map(_.b64), Some("AQIDBAUGBwgJ"))
    // 申报体（去程）：None ⇒ 空对象（零键）；Some ⇒ 单键对象。
    assertEquals(AttachmentPreviewContract.MirrorDeclaration(None).asJson.noSpaces, "{}")
    assertEquals(
      AttachmentPreviewContract.MirrorDeclaration(AttachmentPreviewContract.parsePreview(goodPreview)).asJson.noSpaces,
      s"""{"preview":$goodPreviewJson}"""
    )
  }

  // ── 例 5：已知读数（开放项，非本批红）────────────────────────
  //
  // 未改的生产编码器（`FriendCodecs` 的 `Encoder[AttachmentSummary]`）只输出既有六键
  // ⇒ 服务端下发的 `preview` 键**在网关 REST 出口会被丢弃**。本批明令禁改
  // `NeblinkModel.scala` ⇒ 这是**声明面与执行面之间已知的缺口**，接线批必须按本件
  // 口径把该编码器扩键（`preview` 追加末位、`None` 省键）。本例把"缺口存在"钉成
  // **可执行的读数**，防止它被静默遗忘。

  test("known gap (not this batch's red): the untouched production encoder drops the preview key") {
    val withPreview = jsonOf(
      s"""{"id":"a1","name":"p.jpg","size":900000,"sha256":"ab12","state":"ready","mime":"image/jpeg","preview":$goodPreviewJson}"""
    )
    val decoded = withPreview.as[AttachmentSummary].toOption.get
    // 字段本身被正确"忽略"（解码面零影响）……
    assertEquals(decoded, legacySummary)
    // ……但重编码（网关出口的实际路径）只剩既有六键 ⇒ 缺口 = 接线批必须扩键。
    assert(!decoded.asJson.noSpaces.contains(AttachmentPreviewContract.PreviewKey))
    assertEquals(decoded.asJson.noSpaces, legacyBytes)
  }
