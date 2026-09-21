package nebflow.neblink

import io.circe.{Json, JsonObject}

/**
 * 附件**预览字段**契约声明件 —— 好友/群图片预览传输批 · **传输集成批**（2026-09-21）。
 *
 * 定位：把跨仓契约设计件 §B.1（**唯一权威**）的线面口径写成**可编译、可测、可引用**的
 * 单一真源 —— 字段名 / 六个子键名 / 取值域 / 上限族 / 编码口径 / **双向**判定。
 * 设计件绝对路径（只读，🔴 本仓禁改）：
 * `~/.nebflow/projects/neblink-server/.nebflow/Spec/20260919_201155_attachpreview-server-contract__chain-n-8b202bb8.md`
 *
 * ── 本批的**形变登记**（旧形态 → 线面形态，逐条；不是「顺手改」）──
 *   上一批（`imgpreview-gen`，解耦包）把本件写成**五键**镜面 `{mime,w,h,size,b64}`（mime =
 *   `image/webp|image/jpeg`）。跨仓腿 ① 落地后（neblink-server `4399d7a4c`）**服务端契约**是
 *   **六键** `{fmt,w,h,bytes,sha256,b64}`（`fmt ∈ {"webp","jpeg"}`，无 `data:` 前缀，带 sha256）。
 *   ⇒ 本批把镜像面**逐键对齐到线面**：`mime`→`fmt`（值域改成裸格式名）、`size`→`bytes`、
 *   **新增 `sha256`**、新增 base64 串长上限 [[MaxB64Len]]（= `4 × ⌈262144/3⌉`）。旧五键形态
 *   在本仓**从未上过线**（解耦包零接线）⇒ 无载荷兼容面，纯契约对齐。
 *
 * ── 镜像面**退役**（该文件自己写下的计划，本批执行）──
 *   上一批的 `MirrorPreview` / `MirrorAttachment` / `MirrorDeclaration` / `readPreview` /
 *   `parsePreview` 是**契约一致性夹具**（注释逐字：「接线批应把 `NeblinkModel` 的手写编码器
 *   按本件口径扩键，并**删除本镜像面**（避免两份真源漂移）」）。本批即接线批：判定面收敛到
 *   本件的 [[previewOf]] / [[encodePreview]]（**唯一**解析与编码口径），生产类型与生产编解码
 *   落 `NeblinkModel`（`AttachmentSummary.preview`）—— 镜像面**已删除**，账号只有一册。
 *
 * ── 数值真源（一个数一处；禁第二处拼写）──
 *   生成侧同值同源的执行面 = `web/js/attachImagePreview.js`（长边 1280 / q0.82 / 软上限 184320）
 *   与 `web/js/attachPreviewField.js`（键集 / 白名单 / 硬上限 / b64 串长上限）—— 客户端镜像改动
 *   必须与本件同一批落地（本批两件已同批改齐）。
 */
object AttachmentPreviewContract:

  // ===== 字段名（单一命名面）=====

  /** 预览字段的键名（附件对象上的**可选**加性键）。 */
  val PreviewKey: String = "preview"

  /** 预览字段的**键集**（顺序即结构体声明序 = 编码顺序；客户端镜像 = `attachPreviewField.js`
    * 的 `PREVIEW_FIELD_KEYS`）。六键**全有或全无**（无「只有几何没有字节」形态）。 */
  val PreviewKeys: List[String] = List("fmt", "w", "h", "bytes", "sha256", "b64")

  /** 附件对象的**既有**键序（= `NeblinkModel` 手写编码器的键序；「旧端形态」判据面）。 */
  val LegacyKeyOrder: List[String] = List("id", "name", "size", "mime", "sha256", "state")

  // ===== 取值域 =====

  /** WebP（首选档）—— `fmt` 的**裸格式名**（不是 `image/webp`）。 */
  val FormatWebp: String = "webp"

  /** JPEG（兜底档）—— 注意是 `jpeg`，**不是** `jpg`。 */
  val FormatJpeg: String = "jpeg"

  /** 允许的 `fmt`（不在表内 ⇒ 该预览判**不可用** ⇒ 上层按「无预览」回落）。 */
  val Formats: List[String] = List(FormatWebp, FormatJpeg)

  /** `fmt` 是否在取值域内。 */
  def formatAllowed(fmt: String): Boolean = Formats.contains(fmt)

  /** 生成器（`attachImagePreview.js`）的 mime 出口 —— 与 [[Formats]] 的**唯一**映射表。 */
  val Mimes: List[String] = List("image/webp", "image/jpeg")

  /** mime → 线面 `fmt`（表的另一向；未知 mime ⇒ `None` ⇒ 不申报预览）。 */
  def formatOfMime(mime: String): Option[String] = mime match
    case "image/webp" => Some(FormatWebp)
    case "image/jpeg" => Some(FormatJpeg)
    case _            => None

  // ===== 生成 / 阈值基线（作者裁定 = 前身报告 §6；执行面同值同源）=====

  /** 长边钳制上限（px）—— **禁放大**：任一边 ≤ 本值 ⇒ 只重编码，尺寸逐像素不变。 */
  val MaxLongEdge: Int = 1280

  /** 基线质量档（WebP 首选 / JPEG 兜底同档）。`q` **不上 wire**（发送端本地生成参数）。 */
  val BaseQuality: Double = 0.82

  /** 单预览**软上限** = 180 KiB = 184,320 B（发送侧降级梯的触发线，**非**硬闸）。 */
  val SoftCapBytes: Long = 184320L

  /** 服务端**硬上限** = 256 KiB（`bytes` = **解码后**字节数的上界；与软上限刻意不是一把尺）。 */
  val HardCapBytes: Long = 262144L

  /** `b64` **串长**上限 = `4 × ⌈262144/3⌉` = 349,528（先验长、再解码 ⇒ 免解码拦巨型串）。 */
  val MaxB64Len: Long = base64LengthOf(HardCapBytes)

  /** n 字节的标准 base64 长度（RFC 4648 §4 字母表 + `=` 填充、无换行）—— 完整性判据，**免解码**。 */
  def base64LengthOf(byteCount: Long): Long = 4L * ((byteCount + 2L) / 3L)

  // ===== 判定面（**唯一**解析口径）=====

  /** `sha256` 必须是**64 位小写 hex**（服务端 `is_valid_sha256_hex` 的同一条判据）。 */
  def sha256HexOk(s: String): Boolean =
    s.length == 64 && s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))

  /** `w` / `h`：正整数且长边 ≤ [[MaxLongEdge]]（只校验**声明值**，不解码）。 */
  def dimsOk(w: Int, h: Int): Boolean = w >= 1 && h >= 1 && math.max(w, h) <= MaxLongEdge

  /** `bytes`：解码后字节数 ∈ `1..=HardCapBytes`。 */
  def bytesOk(bytes: Long): Boolean = bytes >= 1L && bytes <= HardCapBytes

  /** `b64`：非空、串长 ≤ [[MaxB64Len]] 且 == [[base64LengthOf]]`(bytes)`（免解码完整性判据）。 */
  def b64Ok(b64: String, bytes: Long): Boolean =
    b64.nonEmpty && b64.length.toLong <= MaxB64Len && b64.length.toLong == base64LengthOf(bytes)

  /** 对象字段读（`null` 与缺席同义 —— 服务端 `#[serde(default)]` 的镜像语义）。 */
  private def field(o: JsonObject, name: String): Option[Json] =
    o(name).filterNot(_.isNull)

  private def strOf(o: JsonObject, name: String): Option[String] = field(o, name).flatMap(_.asString)

  private def intOf(o: JsonObject, name: String): Option[Int] = field(o, name).flatMap(_.asNumber).flatMap(_.toInt)

  private def longOf(o: JsonObject, name: String): Option[Long] = field(o, name).flatMap(_.asNumber).flatMap(_.toLong)

  /**
   * 解析**预览对象**（六键全有或全无；任一不成立 ⇒ `None` ⇒ 上层按「无预览」回落）：
   *   ① 入参是 JSON 对象（`null` / 字符串 / 数组 / 数字一律不算）；
   *   ② `fmt ∈ [[Formats]]`；③ [[dimsOk]]；④ [[bytesOk]]；⑤ [[sha256HexOk]]；⑥ [[b64Ok]]。
   * **未知 / 多余键**：只读白名单六键，多余键既不参与判定也不报错（前向兼容）。
   */
  def previewOf(json: Json): Option[AttachmentPreview] =
    json.asObject.flatMap { o =>
      for
        fmt <- strOf(o, "fmt").filter(formatAllowed)
        w   <- intOf(o, "w")
        h   <- intOf(o, "h")
        b   <- longOf(o, "bytes")
        sha <- strOf(o, "sha256").filter(sha256HexOk)
        b64 <- strOf(o, "b64")
        if dimsOk(w, h) && bytesOk(b) && b64Ok(b64, b)
      yield AttachmentPreview(fmt, w, h, b, sha, b64)
    }

  /** 从**附件对象 JSON** 上读预览（键缺席 / `null` / 形状不全 ⇒ `None`）。 */
  def previewOfAttachment(attachmentJson: Json): Option[AttachmentPreview] =
    attachmentJson.asObject.flatMap(_(PreviewKey)).flatMap(previewOf)

  /** 预览对象 → JSON（**唯一**编码口径：键序 = [[PreviewKeys]] 声明序）。 */
  def encodePreview(p: AttachmentPreview): Json =
    Json.obj(
      "fmt"    -> Json.fromString(p.fmt),
      "w"      -> Json.fromInt(p.w),
      "h"      -> Json.fromInt(p.h),
      "bytes"  -> Json.fromLong(p.bytes),
      "sha256" -> Json.fromString(p.sha256),
      "b64"    -> Json.fromString(p.b64)
    )

end AttachmentPreviewContract
