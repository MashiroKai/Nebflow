package nebflow.neblink

import io.circe.*
import io.circe.syntax.*

/**
 * 附件**预览字段**契约声明件 —— 好友/群图片预览传输批 · 解耦包（作者 2026-09-19 20:4x 裁定）。
 *
 * 定位：把「与 `neblink-server` **待落** DTO 的镜像口径」写成**可编译、可测、可引用**的
 * 声明面 —— 字段名 / 取值域 / 生成参数基线 / **双向**编解码语义（去程申报 + 回程解析）。
 *
 * 🔴 **本件不接线**（作者逐字范围）：
 *   · 生产解码面仍是 `NeblinkModel.scala`（本批**禁改**）；生产发送面仍是
 *     `RestApiRoutes.scala`（本批**禁改**）；
 *   · 故本件里的 case class **一律带 `Mirror` 名号**，是**契约镜像 / 一致性夹具**，
 *     不是第二套生产 codec；接线批（跨仓腿 ① 落地后）应把 `NeblinkModel` 的手写
 *     编码器**按本件口径扩键**，并**删除本镜像面**（避免两份真源漂移）。
 *   · 本批**不含** E2E 面（真渲染收发 round / 兼容矩阵四格 / 下载 sha 一致）。
 *
 * ── 镜像口径（逐条）──
 *   · 键名 = [[PreviewKey]]（`preview`），挂在**附件对象**（镜像 `AttachmentSummary`）上；
 *   · **可选性**：`preview` 为 `Option`，缺省 `None` ⇒ **旧消息 / 非图片附件的形态不变**；
 *   · 子键 = [[PreviewKeys]] 五键（`mime` / `w` / `h` / `size` / `b64`）**全为必填**
 *     （子键不做可选：缺一个即整个字段判不可用 ⇒ 回落「无预览」）；
 *   · mime 取值域 = [[Mimes]]（= 发送侧格式梯两档出口）；
 *   · **「旧端载荷逐字节不变」的编码约束**（= Rust 侧 `skip_serializing_if` 等价语义）：
 *     `preview = None` ⇒ **省键**（**不是** `"preview":null`），且既有六键的**键序与取值
 *     逐字节不变** —— 由 [[LegacyKeyOrder]] + [[MirrorAttachment]] 的手写编码器共同钉住，
 *     单测里与**未改**的真源编码器（`FriendCodecs` 的 `Encoder[AttachmentSummary]`）逐字节对照。
 *
 * ── 生成参数基线（作者裁定；真源 = 前身 `imgpreview-impl` 报告 §6 实测表，禁自行另立口径）──
 *   长边 ≤ [[MaxLongEdge]] px（禁放大）+ WebP [[BaseQuality]] 首选 / JPEG [[BaseQuality]] 兜底
 *   + 单预览软上限 [[SoftCapBytes]]。**数值在此只作声明**：执行面在客户端
 *   （`web/js/attachImagePreview.js`，同值同源）。
 */
object AttachmentPreviewContract:

  // ===== 字段名（单一命名面）=====

  /** 预览字段的键名（附件对象上的**可选**加性键）。 */
  val PreviewKey: String = "preview"

  /** 预览字段的**键集**（顺序即编码顺序；客户端镜像 = `web/js/attachPreviewField.js` 的 `PREVIEW_FIELD_KEYS`）。 */
  val PreviewKeys: List[String] = List("mime", "w", "h", "size", "b64")

  /** 附件对象的**既有**键序（= `NeblinkModel` 手写编码器的键序；「旧端形态」判据面）。 */
  val LegacyKeyOrder: List[String] = List("id", "name", "size", "mime", "sha256", "state")

  // ===== 取值域 =====

  /** WebP（首选档）。 */
  val MimeWebp: String = "image/webp"

  /** JPEG（兜底档）。 */
  val MimeJpeg: String = "image/jpeg"

  /** 允许的 mime（不在表内 ⇒ 预览字段判**不可用** ⇒ 回落「无预览」）。 */
  val Mimes: List[String] = List(MimeWebp, MimeJpeg)

  /** mime 是否在取值域内。 */
  def mimeAllowed(mime: String): Boolean = Mimes.contains(mime)

  // ===== 生成 / 阈值基线（作者裁定 = 前身报告 §6；执行面同值同源）=====

  /** 长边钳制上限（px）—— **禁放大**：任一边 ≤ 本值 ⇒ 只重编码，尺寸逐像素不变。 */
  val MaxLongEdge: Int = 1280

  /** 基线质量档（WebP 首选 / JPEG 兜底同档）。 */
  val BaseQuality: Double = 0.82

  /** 单预览**软上限** = 180 KiB = 184,320 B（发送侧降级梯的触发线，**非**硬闸）。 */
  val SoftCapBytes: Long = 184320L

  /**
   * **回程解析硬上界** = 256 KiB —— 与 [[SoftCapBytes]] **刻意不是同一把尺**（不互推）：
   * 软上限管发送侧降级触发，本值只拦「明显越界 / 恶意构造」的载荷。
   */
  val HardCapBytes: Long = 262144L

  /** n 字节的标准 base64 长度（无换行）—— 完整性判据，**免解码**即可判 `b64` 长度是否自洽。 */
  def base64LengthOf(byteCount: Long): Long = 4L * ((byteCount + 2L) / 3L)

  // ===== JSON 读面（可空键：`null` 与缺席同义）=====

  private def strField(c: HCursor, name: String): Option[String] =
    c.downField(name).focus.flatMap(v => if v.isNull then None else v.asString)

  private def intField(c: HCursor, name: String): Option[Int] =
    c.downField(name).focus.flatMap(v => if v.isNull then None else v.asNumber.flatMap(_.toInt))

  private def longField(c: HCursor, name: String): Option[Long] =
    c.downField(name).focus.flatMap(v => if v.isNull then None else v.asNumber.flatMap(_.toLong))

  // ===== 预览子对象（五键必填）=====

  /** 预览字段的**镜像** case class（五键 = [[PreviewKeys]]，顺序即编码顺序）。 */
  final case class MirrorPreview(mime: String, w: Int, h: Int, size: Long, b64: String)

  /**
   * 解析预览字段（**唯一解析口径**：回程解析与去程回显共用）。
   *
   * 判据（任一不成立 ⇒ `None` ⇒ 上层判「无预览」= 现行为不变）：
   *   ① 入参是 JSON 对象（`null` / 字符串 / 数组 / 数字一律不算）；
   *   ② `mime ∈ [[Mimes]]`；
   *   ③ `w` / `h` 为正整数；`size` 为正且 ≤ [[HardCapBytes]]；
   *   ④ `b64` 非空且长度 == [[base64LengthOf]]`(size)`（免解码的完整性判据）。
   * **未知 / 多余键**：只读白名单五键，多余键既不参与判定也不报错。
   */
  def parsePreview(json: Json): Option[MirrorPreview] =
    json.asObject.flatMap { o =>
      val mime = o("mime").flatMap(v => if v.isNull then None else v.asString)
      val w = o("w").flatMap(v => if v.isNull then None else v.asNumber.flatMap(_.toInt))
      val h = o("h").flatMap(v => if v.isNull then None else v.asNumber.flatMap(_.toInt))
      val size = o("size").flatMap(v => if v.isNull then None else v.asNumber.flatMap(_.toLong))
      val b64 = o("b64").flatMap(v => if v.isNull then None else v.asString)
      for
        m <- mime.filter(mimeAllowed)
        pw <- w.filter(_ > 0)
        ph <- h.filter(_ > 0)
        sz <- size.filter(s => s > 0 && s <= HardCapBytes)
        bs <- b64.filter(_.nonEmpty)
        if bs.length.toLong == base64LengthOf(sz)
      yield MirrorPreview(m, pw, ph, sz, bs)
    }

  /** 从**附件对象 JSON** 上读预览字段（键缺席 / `null` / 形状不全 ⇒ `None`）。 */
  def readPreview(attachmentJson: Json): Option[MirrorPreview] =
    attachmentJson.asObject.flatMap(_(PreviewKey)).flatMap(parsePreview)

  /** 去程申报体的读数（与 [[readPreview]] 同判据，入参是申报体 JSON）。 */
  def readDeclaration(declarationJson: Json): Option[MirrorPreview] =
    readPreview(declarationJson)

  given Encoder[MirrorPreview] = Encoder.instance { p =>
    Json.fromFields(
      List(
        "mime" -> p.mime.asJson,
        "w" -> p.w.asJson,
        "h" -> p.h.asJson,
        "size" -> p.size.asJson,
        "b64" -> p.b64.asJson
      )
    )
  }

  /**
   * 严格解码（契约入参面）：形状不全即 `DecodingFailure`，**不静默**折成空值。
   * 宽容侧（线上消息面）用 [[readPreview]] —— 两者是**同一判据**的两个出口。
   */
  given Decoder[MirrorPreview] = Decoder.instance { c =>
    parsePreview(c.value) match
      case Some(p) => Right(p)
      case None =>
        Left(
          DecodingFailure(
            s"preview field must carry {${PreviewKeys.mkString(", ")}} with mime in {${Mimes.mkString(", ")}}",
            c.history
          )
        )
  }

  // ===== 回程解析面（服务端 → 本仓镜像）=====

  /**
   * 附件对象**镜像**（`AttachmentSummary` 形状 + 可选 [[PreviewKey]]）。
   *
   * 🔴 **只读镜像 / 一致性夹具**：生产解码面仍是 `NeblinkModel`（本批禁改）。
   * 本类的存在只为**两条判据可执行**：① `preview = None` 时编码输出与真源**逐字节相同**；
   * ② `preview = Some` 时既有六键的**前缀**逐字节不变、`preview` 追加在**末位**。
   */
  final case class MirrorAttachment(
    id: String,
    name: String,
    size: Long,
    sha256: String,
    state: String,
    mime: Option[String] = None,
    preview: Option[MirrorPreview] = None
  )

  given Decoder[MirrorAttachment] = Decoder.instance { c =>
    Right(
      MirrorAttachment(
        id = strField(c, "id").getOrElse(""),
        name = strField(c, "name").getOrElse(""),
        size = longField(c, "size").getOrElse(0L),
        sha256 = strField(c, "sha256").getOrElse(""),
        state = strField(c, "state").getOrElse(""),
        mime = strField(c, "mime"),
        preview = readPreview(c.value)
      )
    )
  }

  /** 附件出参（手写：`mime` / `preview` 缺席即**省键**；键序 = 旧六键 + 末位 `preview`）。 */
  given Encoder[MirrorAttachment] = Encoder.instance { a =>
    Json.fromFields(
      List(
        Some("id" -> a.id.asJson),
        Some("name" -> a.name.asJson),
        Some("size" -> a.size.asJson),
        a.mime.map(m => "mime" -> m.asJson),
        Some("sha256" -> a.sha256.asJson),
        Some("state" -> a.state.asJson),
        a.preview.map(p => PreviewKey -> p.asJson)
      ).flatten
    )
  }

  // ===== 去程申报面（本仓 → 服务端上传申报）=====

  /**
   * 上传申报体的**加性键**声明：`{ "preview": {…} }`；`None` ⇒ **空对象**（零键）。
   *
   * 与 [[MirrorAttachment]] 同一条纪律（`skip_serializing_if` 等价语义）：`None` **省键** ⇒
   * 申报体与旧形态逐字节一致，旧服务端解析零影响。
   */
  final case class MirrorDeclaration(preview: Option[MirrorPreview] = None)

  given Encoder[MirrorDeclaration] = Encoder.instance { d =>
    Json.fromFields(List(d.preview.map(p => PreviewKey -> p.asJson)).flatten)
  }

  given Decoder[MirrorDeclaration] = Decoder.instance { c =>
    Right(MirrorDeclaration(readDeclaration(c.value)))
  }

end AttachmentPreviewContract
