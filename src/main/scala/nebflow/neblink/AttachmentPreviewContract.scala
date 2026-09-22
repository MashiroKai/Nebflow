package nebflow.neblink

import io.circe.{Json, JsonObject}

/**
 * 附件**预览字段**契约声明件 —— 好友/群图片预览传输批 · **字段方言双兼容批**（2026-09-22）。
 *
 * ## 为什么是「方言登记」而不是「一套键集」
 *
 * 上一批（`xferpreview-impl2`）把**六键** `{fmt,w,h,bytes,sha256,b64}` 静态钉成**唯一**形态；
 * 对端 `neblink-server` 的现读 main（`41e12af`，对齐批 `6b2af54`）却是**五键**
 * `{mime,w,h,size,b64}`（full MIME 拼写、无摘要步）⇒ 换代即全链失效（旧 E2E 判词的
 * `PARSE-ERR missing field mime` 即该失效的取证读数）。
 *
 * 作者令（2026-09-22）：「因为 server 也不可能一直不动，不能让以后 server 换个字段名就又不行」
 * / 「我需要 server 和这边的兼容，你改这边，让两边都兼容」⇒ 本批的形态 = **字段方言双兼容**：
 *
 *   · **收面**：把**每个已知方言**登记成一条**数据**（[[PreviewDialect]]），解析按登记表逐条
 *     试、**按键集识别、零版本协商**（两侧不需要谁先声明自己说哪一版）。
 *   · **发面**：写出**哪一套键名**由**方言 id 参数**决定（默认 = [[DefaultSendDialectId]]，
 *     可被调用方逐次指定）—— 本件里**不存在**「独立于方言的键集常量」当收发唯一判据
 *     （键序/键名全部由 [[PreviewDialect]] 的字段名算出）。
 *   · **第三种未知方言**：登记表里没有 ⇒ 解析回 `None` ⇒ 上层按**无预览回落**（拉原件）；
 *     发面若收到未登记的方言 id ⇒ **显式拒绝**（`None` / `400` 列出已登记方言），
 *     禁崩、禁静默丢件。
 *
 * ── 内部表示（与方言无关的唯一形状）──
 *   [[nebflow.neblink.AttachmentPreview]] 用**标准内部拼写**承载一个预览：
 *   `mime` = **full MIME**（`image/webp` / `image/jpeg`）、`size` = **解码后字节数**、
 *   `sha256` = `Option`（有的方言带摘要、有的不带 —— 带与不带都不是「残缺」）、
 *   `dialectId` = **本对象说哪一版**（解析面 = 识别到的方言；产出面 = 申报的方言）。
 *   解析结果自带方言 id ⇒ **重编码按原方言回写**（收到什么拼写就还什么拼写，收-发往返恒等）。
 *
 * ── 数值真源（一个数一处；禁第二处拼写）──
 *   生成侧同值同源的执行面 = `web/js/attachImagePreview.js`（长边 1280 / q0.82 / 软上限 184320）
 *   与 `web/js/attachPreviewField.js`（方言登记表 / 硬上限 / b64 串长自洽）—— 客户端镜像改动
 *   必须与本件同一批落地（本批两件已同批改齐）。
 */
object AttachmentPreviewContract:

  // ===== 字段名（单一命名面）=====

  /** 预览字段的键名（附件对象上的**可选**加性键；**两版方言共用同一个外层键名**）。 */
  val PreviewKey: String = "preview"

  // ===== 方言登记（字段方言双兼容的**唯一**机制面）=====

  /**
   * 一条**预览字段方言**的完整形状描述。
   *
   * 🔴 键名与键序**全部由本类的字段算出**（[[keyOrder]]），本件内任何地方都不得再写一份
   * 与方言无关的键集常量 —— 那正是上一批失效的形态（「换字段名就又不行」）。
   *
   * @param id           方言 id（唯一；发面用它与 [[previewDialect]] 取回本结构）
   * @param note         人可读来源说明（对端哪一版说的这一套）
   * @param mimeKey      MIME/格式子键名（`mime` / `fmt`）
   * @param bytesKey     「解码后字节数」子键名（`size` / `bytes`）
   * @param digestKey    摘要子键名（`Some("sha256")`；**不带摘要的方言 = `None`**）
   * @param toCanonical  线上拼写 → 标准内部 MIME（`"webp"` → `"image/webp"`）
   * @param fromCanonical标准内部 MIME → 本方言的线上拼写
   */
  final case class PreviewDialect(
    id: String,
    note: String,
    mimeKey: String,
    bytesKey: String,
    digestKey: Option[String],
    toCanonical: Map[String, String],
    fromCanonical: Map[String, String]
  ):

    /** 本方言的 wire 键序（= 计算所得，不是抄来的一份常量）。 */
    def keyOrder: List[String] = List(mimeKey, "w", "h", bytesKey) ++ digestKey.toList ++ List("b64")

    /** 本方言是否**必须**携带摘要（不带 ⇒ 该键缺席/形状不全即判整字段不可用）。 */
    def digestRequired: Boolean = digestKey.isDefined

    /** 本方言取值域（报告/错误文案用）。 */
    def mimeDomain: List[String] = toCanonical.keys.toList.sorted

  /**
   * 方言 **A** = 五键 `{mime,w,h,size,b64}`：
   * `neblink-server` 现读 main（`41e12af`，对齐批 `6b2af54`）的 `AttachmentPreviewDto`；
   * full MIME 拼写、**无摘要步**（该批删掉了 `PREVIEW_DIGEST_MISMATCH`）。
   */
  val MimeFiveKey: PreviewDialect = PreviewDialect(
    id = "mime5",
    note = "five keys {mime,w,h,size,b64}; full MIME spellings; no digest step (neblink-server main 41e12af)",
    mimeKey = "mime",
    bytesKey = "size",
    digestKey = None,
    toCanonical = Map("image/webp" -> "image/webp", "image/jpeg" -> "image/jpeg"),
    fromCanonical = Map("image/webp" -> "image/webp", "image/jpeg" -> "image/jpeg")
  )

  /**
   * 方言 **B** = 六键 `{fmt,w,h,bytes,sha256,b64}`：
   * 已退役的 `neblink-server` 提交 `4399d7a4c` 与本仓 `xferpreview-impl2` 支说的那一套；
   * **裸格式名**、带 64 位小写 hex 摘要步。
   *
   * 🔴 它**不是**「已作废所以不认」：作废的是「把它当**唯一**形态」这件事；作为**对端可能说的
   * 一版方言**，收面必须认（作者令「两边都兼容」）。
   */
  val FormatSixKey: PreviewDialect = PreviewDialect(
    id = "fmt6",
    note = "six keys {fmt,w,h,bytes,sha256,b64}; bare format names; digest step (neblink-server 4399d7a4c / xferpreview-impl2)",
    mimeKey = "fmt",
    bytesKey = "bytes",
    digestKey = Some("sha256"),
    toCanonical = Map("webp" -> "image/webp", "jpeg" -> "image/jpeg"),
    fromCanonical = Map("image/webp" -> "webp", "image/jpeg" -> "jpeg")
  )

  /**
   * 登记表（**收面的唯一判据面**）：解析按本表**逐条**试，先命中者胜。
   *
   * 平局规则（有意的、写明的）：两版方言的**区分键互斥**（A 靠 `mime`+`size`、B 靠
   * `fmt`+`bytes`+`sha256`），唯一可能「同时像两版」的载荷是**人为构造的混合体**；
   * 那种载荷按**本表声明序**取先者（A 先）—— 判据确定、可复算，不留隐式优先级。
   */
  val Dialects: List[PreviewDialect] = List(MimeFiveKey, FormatSixKey)

  /** 按 id 取方言（**发面的取用点**；未登记 id ⇒ `None`）。 */
  def previewDialect(id: String): Option[PreviewDialect] = Dialects.find(_.id == id)

  /**
   * **默认发面方言**（= [[MimeFiveKey]]）—— 这是**一个可切换的政策旋钮**，**不是**判据本身：
   *   · 解析面**不读它**（解析只按 [[Dialects]] 登记表逐条试，任何已登记方言都认）；
   *   · 发面每次写出哪一套键名由**调用方给的方言 id** 决定，本值只是「调用方没给时用哪版」。
   * 取值理由：它等于现读对端 main（`41e12af`）说的那一版 ⇒ 默认配置下新载荷可直接被现读
   * 服务端接受。对端再换代 ⇒ 改**这一行**（或在调用点逐次指定），收发代码零改动。
   */
  val DefaultSendDialectId: String = MimeFiveKey.id

  /** 默认发面方言结构（由 id 取回，单一命名面）。 */
  def defaultSendDialect: PreviewDialect = previewDialect(DefaultSendDialectId).getOrElse(MimeFiveKey)

  /** 全部已登记方言的 id（错误文案/读数用）。 */
  def dialectIds: List[String] = Dialects.map(_.id)

  /** 取一个预览对象「说哪一版」的方言（未登记的 id ⇒ 默认方言 —— 只有程序化构造才会走到）。 */
  def dialectOf(p: AttachmentPreview): PreviewDialect =
    previewDialect(p.dialectId).getOrElse(defaultSendDialect)

  // ===== 取值域 =====

  /** 标准内部表示放行的 full MIME（两版方言的**并集取值域**；一个事实一处拼写）。 */
  val Mimes: List[String] = List("image/webp", "image/jpeg")

  /** mime 是否在取值域内。 */
  def mimeAllowed(mime: String): Boolean = Mimes.contains(mime)

  // ===== 生成 / 阈值基线（作者裁定 = 前身报告 §6；执行面同值同源）=====

  /** 长边钳制上限（px）—— **禁放大**：任一边 ≤ 本值 ⇒ 只重编码，尺寸逐像素不变。 */
  val MaxLongEdge: Int = 1280

  /** 基线质量档（WebP 首选 / JPEG 兜底同档）。`q` **不上 wire**（发送端本地生成参数）。 */
  val BaseQuality: Double = 0.82

  /** 单预览**软上限** = 180 KiB = 184,320 B（发送侧降级梯的触发线，**非**硬闸）。 */
  val SoftCapBytes: Long = 184320L

  /** 回程解析**硬上界** = 256 KiB（= 对端 `MAX_ATTACHMENT_PREVIEW_BYTES` 同值，**非**软上限）。 */
  val HardCapBytes: Long = 262144L

  /** `b64` **串长**上限 = `4 × ⌈262144/3⌉` = 349,528（先验长、再解码 ⇒ 免解码拦巨型串）。 */
  val MaxB64Len: Long = base64LengthOf(HardCapBytes)

  /** n 字节的标准 base64 长度（RFC 4648 §4 字母表 + `=` 填充、无换行）—— 完整性判据，**免解码**。 */
  def base64LengthOf(byteCount: Long): Long = 4L * ((byteCount + 2L) / 3L)

  // ===== 逐项判据（两版方言**共用同一套**值域判定）=====

  /** `sha256` 必须是**64 位小写 hex**（对端 `is_valid_sha256_hex` 的同一条判据）。 */
  def sha256HexOk(s: String): Boolean =
    s.length == 64 && s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))

  /** `w` / `h`：正整数且长边 ≤ [[MaxLongEdge]]（只校验**声明值**，不解码）。 */
  def dimsOk(w: Int, h: Int): Boolean = w >= 1 && h >= 1 && math.max(w, h) <= MaxLongEdge

  /** 解码后字节数 ∈ `1..=HardCapBytes`。 */
  def sizeOk(size: Long): Boolean = size >= 1L && size <= HardCapBytes

  /** `b64`：非空、串长 ≤ [[MaxB64Len]] 且 == [[base64LengthOf]]`(size)`（免解码完整性判据）。 */
  def b64Ok(b64: String, size: Long): Boolean =
    b64.nonEmpty && b64.length.toLong <= MaxB64Len && b64.length.toLong == base64LengthOf(size)

  /** 一个**内部表示**是否整体自洽（发面前的自检；不自洽 ⇒ 宁可不发也不发半截）。 */
  def payloadOk(p: AttachmentPreview): Boolean =
    mimeAllowed(p.mime) && dimsOk(p.w, p.h) && sizeOk(p.size) && b64Ok(p.b64, p.size) &&
      p.sha256.forall(sha256HexOk)

  // ===== 解析面（**按键集识别、零版本协商**）=====

  /** 对象字段读（`null` 与缺席同义 —— 对端 `#[serde(default)]` + `Option` 的镜像语义）。 */
  private def field(o: JsonObject, name: String): Option[Json] = o(name).filterNot(_.isNull)

  private def strOf(o: JsonObject, name: String): Option[String] = field(o, name).flatMap(_.asString)

  private def intOf(o: JsonObject, name: String): Option[Int] =
    field(o, name).flatMap(_.asNumber).flatMap(_.toInt)

  private def longOf(o: JsonObject, name: String): Option[Long] =
    field(o, name).flatMap(_.asNumber).flatMap(_.toLong)

  /**
   * 按**一条指定方言**解析预览对象：该方言的 mime 键 / 字节数键 / 摘要键（若有）与几何、
   * b64 全部成立 ⇒ `Some`；任一不成立 ⇒ `None`（本方言不认识这一体）。
   *
   * 判据（逐条，与方言无关的共用值域）：
   *   ① 入参是 JSON 对象（`null` / 字符串 / 数组 / 数字一律不算）；
   *   ② 本方言的 mime 子键在场、取值在**本方言的取值域**内；
   *   ③ `w` / `h`：[[dimsOk]]；④ 字节数：[[sizeOk]]；⑤ `b64`：[[b64Ok]]；
   *   ⑥ 本方言带摘要键 ⇒ 该键在场且 [[sha256HexOk]]（缺摘要 = 不是本方言的形状）。
   * **未知 / 多余键**：只读本方言声明的键，多余键既不参与判定也不报错（前向兼容：
   * 对端将来加键不影响本版识别）。
   */
  def parseIn(dialect: PreviewDialect, json: Json): Option[AttachmentPreview] =
    json.asObject.flatMap { o =>
      val rawMime = strOf(o, dialect.mimeKey)
      val w       = intOf(o, "w")
      val h       = intOf(o, "h")
      val size    = longOf(o, dialect.bytesKey)
      val b64     = strOf(o, "b64")
      val digest  = dialect.digestKey.flatMap(k => strOf(o, k))
      for
        rm  <- rawMime
        m   <- dialect.toCanonical.get(rm)
        pw  <- w
        ph  <- h
        sz  <- size
        bs  <- b64
        dg  <- dialect.digestKey match
                 case Some(_) => digest.filter(sha256HexOk).map(Some(_))
                 case None    => Some(None)
        if dimsOk(pw, ph) && sizeOk(sz) && b64Ok(bs, sz)
      yield AttachmentPreview(mime = m, w = pw, h = ph, size = sz, sha256 = dg, b64 = bs, dialectId = dialect.id)
    }

  /**
   * 解析预览对象 —— **唯一收面入口**：按 [[Dialects]] 登记表逐条试，命中即回
   * （结果**自带方言 id** ⇒ 重编码按原方言回写）。
   * 全部方言都不认（含「第三种未知方言」）⇒ `None` ⇒ 上层按无预览回落，**零异常**。
   */
  def parsePreview(json: Json): Option[AttachmentPreview] =
    Dialects.view.flatMap(d => parseIn(d, json)).headOption

  /** 解析并同时给出是**哪一版方言**被识别到（读数/协商用）。 */
  def parsePreviewWith(json: Json): Option[(AttachmentPreview, PreviewDialect)] =
    Dialects.view.flatMap(d => parseIn(d, json).map(_ -> d)).headOption

  /** 从**附件对象 JSON** 上读预览（外层键缺席 / `null` / 形状不全 ⇒ `None`）。 */
  def parsePreviewAttachment(attachmentJson: Json): Option[AttachmentPreview] =
    attachmentJson.asObject.flatMap(_(PreviewKey)).flatMap(parsePreview)

  /** 去程申报体的读数（与 [[parsePreview]] 同判据，入参是申报体 JSON）。 */
  def parseDeclaration(declarationJson: Json): Option[AttachmentPreview] =
    parsePreview(declarationJson)

  // ===== 编码面（**唯一**编码口径：方言由参数决定）=====

  /**
   * 把内部表示编码成**指定方言**的线上对象（键序 = [[PreviewDialect.keyOrder]]）。
   *
   * 拒绝面（回 `None`，**绝不发半截字段**）：
   *   · 内部表示的 mime 不在本方言的出口映射里（方言不会说这个 MIME）；
   *   · 本方言带摘要键而该对象没有合法摘要（缺摘要 = 本方言的形状不成立）；
   *   · 载荷本身不自洽（[[payloadOk]]）。
   */
  def encodeIn(dialect: PreviewDialect, p: AttachmentPreview): Option[Json] =
    // 🔴 取值域判定落在**标准内部 mime** 上（`Mimes`），方言只提供拼写映射 —— 把 `mimeAllowed`
    //    套在**拼写**上会把 `fmt6` 的 `"webp"` 误判越域（实测踩到：六键写面恒 None）。
    val canonical = Option.when(mimeAllowed(p.mime))(p.mime)
    val spelling  = canonical.flatMap(dialect.fromCanonical.get)
    val digest   = dialect.digestKey match
      case Some(k) => p.sha256.filter(sha256HexOk).map(s => Some(k -> Json.fromString(s)))
      case None    => Some(None)
    for
      _  <- canonical
      sp <- spelling
      dg <- digest
      if payloadOk(p)
    yield Json.fromFields(
      List(
        dialect.mimeKey -> Json.fromString(sp),
        "w"             -> Json.fromInt(p.w),
        "h"             -> Json.fromInt(p.h),
        dialect.bytesKey -> Json.fromLong(p.size)
      ) ++ dg.toList ++ List("b64" -> Json.fromString(p.b64))
    )

  /** 按 id 取方言再编码（未登记 id ⇒ `None`，**不回落**：静默换方言是「换字段名就又不行」的根因）。 */
  def encodeInDialect(id: String, p: AttachmentPreview): Option[Json] =
    previewDialect(id).flatMap(d => encodeIn(d, p))

  /** 按**该对象自己说的方言**编码（收-发往返恒等：收到哪套拼写就还哪套）。 */
  def encodePreview(p: AttachmentPreview): Option[Json] = encodeIn(dialectOf(p), p)

end AttachmentPreviewContract
