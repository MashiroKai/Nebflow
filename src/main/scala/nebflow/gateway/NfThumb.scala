package nebflow.gateway

import cats.effect.IO

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Image thumbnail leg for the content-addressed byte exit
 * (`GET /api/nf-obj/<cid>/<name>?w=<px>`).
 *
 * 作者裁定 2026-11-19 10:2x（D5 候选 A）：媒体首屏按显示尺寸取图 —— 列表 / 叠卡 /
 * 消息图只取**预览**，点开才取原图。原图出口 [[NfFileRoutes.nfObjRoutes]] 已是内容
 * 寻址（`cid` = 整件 sha256 前 32 hex）；缩略图是 cid 与目标宽度的**纯函数**，因此
 * 也按内容寻址：`thumbCid = sha256("<cid>:<w>")` 前 [[NfThumb.ThumbCidHexChars]] hex。
 * 同 (cid, w) 的第二条腿命中浏览器缓存（immutable + 强 ETag = `"<thumbCid>"`），
 * 与「列表腿 / 点开腿」两条腿的字节互不串味（两个不同的不可变 URL）。
 *
 * 实现取 **JDK 内置 ImageIO**（作者令：优先 ImageIO，不引入 libvips / 无原生依赖）。
 * 能力边界（诚实降级，绝不硬失败）：ImageIO 读不出（SVG / 非位图 / 损坏件）或源宽
 * 已达目标宽 ⇒ `render` 回 `None`，路由原样服务**原图**（`/api/nf-file` 与
 * 无 `?w=` 的 `/api/nf-obj` 行为逐字不变）。
 *
 * 缓存/淘汰（与既有 office-preview 缓存同一条纪律，单源复用其形状）：产物落
 * `<dataRoot>/cache/nf-thumb/<thumbCid>.<ext>`；每次落盘顺手 `sweep` —— 超过
 * [[NfThumb.SweepAge]] 的条目 + 超出 [[NfThumb.SweepCap]] 的最旧条目删掉。TTL/容量
 * 都只管**服务端重算**的节省；浏览器的 immutable 副本与 cid 同寿（内容变 ⇒ cid 变 ⇒
 * URL 变 ⇒ 旧副本自然不再被引用）。
 */
object NfThumb:

  /** 目标宽上限：超过即不缩（按原图服务，诚实降级；也挡住异常大值的内存面）。 */
  val MaxWidth: Int = 4096

  /** thumbCid 位数（与 [[NfObjStore.CidHexChars]] 同族）。 */
  val ThumbCidHexChars: Int = 32

  /** thumbCid 形态（与 [[NfObjStore.CidPattern]] 同族）。 */
  val ThumbCidPattern: String = "^[0-9a-f]{32}$"

  /** JPEG 编码质量（预览档；原图腿不受影响，点开仍是原件）。 */
  private val JpegQuality: Float = 0.85f

  /** 缓存清扫：30 天 + 200 条上限（与 NfOfficeRoutes 的 office-preview 同值同形）。 */
  private val SweepAgeMs: Long = 30L * 24 * 60 * 60 * 1000
  private val SweepCap: Int = 200

  /** 缩略图缓存根（可由测试注入）。 */
  def defaultCacheDir: os.Path = nebflow.shared.PathUtil.dataRoot / "cache" / "nf-thumb"

  /**
   * 缩略图的内容身份：`(cid, w)` 的确定性函数。
   * 源件内容变 ⇒ cid 变 ⇒ thumbCid 变 ⇒ URL 变 ⇒ immutable 安全。
   */
  def thumbCid(cid: String, w: Int): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(s"$cid:$w".getBytes("UTF-8"))
    md.digest().take(ThumbCidHexChars / 2).map(b => "%02x".format(b & 0xff)).mkString

  /**
   * 位图缩略（`None` = 该源不可缩，请按原图服务）：
   *   · ImageIO 读不出（SVG / 非位图 / 损坏件）；
   *   · 源宽 ≤ `w`（不放大）；
   *   · 编码失败。
   * 保留 alpha 的源出 PNG，其余出 JPEG（[[JpegQuality]]）。
   */
  def render(src: Path, w: Int): IO[Option[(Array[Byte], String)]] =
    IO.blocking {
      if w < 1 || w > MaxWidth then None
      else
        val img = ImageIO.read(src.toFile)
        if img == null || img.getWidth <= w then None
        else
          val outH = math.max(1, math.round(img.getHeight.toDouble * w / img.getWidth).toInt)
          val alpha = img.getColorModel.hasAlpha
          val kind = if alpha then BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
          val out = new BufferedImage(w, outH, kind)
          val g = out.createGraphics()
          try
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(img, 0, 0, w, outH, null)
          finally g.dispose()
          val bos = new ByteArrayOutputStream()
          val ext = if alpha then "png" else "jpg"
          val written =
            try ImageIO.write(out, ext, bos)
            catch case _: Throwable => false
          if !written || bos.size() == 0 then None else Some((bos.toByteArray, ext))
    }.handleError(_ => None)

  /** 缓存命中：`<cacheDir>/<thumbCid>.<ext>`（返回文件与 ext）。 */
  def cached(cacheDir: os.Path, thumbCidValue: String): Option[(os.Path, String)] =
    try
      if !os.exists(cacheDir) then None
      else
        os.list(cacheDir).collectFirst {
          case p if p.last.startsWith(thumbCidValue + ".") =>
            (p, p.last.drop(thumbCidValue.length + 1))
        }
    catch case _: Throwable => None

  /** 落盘 + 顺手清扫（清理面永不拖垮主腿）。 */
  def store(cacheDir: os.Path, thumbCidValue: String, bytes: Array[Byte], ext: String): os.Path =
    os.makeDir.all(cacheDir)
    val target = cacheDir / s"$thumbCidValue.$ext"
    os.write.over(target, bytes)
    sweep(cacheDir)
    target

  private def sweep(cacheDir: os.Path): Unit =
    try
      if os.exists(cacheDir) then
        val entries = os.list(cacheDir).sortBy(f => os.mtime(f))
        val now = System.currentTimeMillis()
        entries.filter(f => os.mtime(f) < now - SweepAgeMs).foreach(os.remove.all)
        entries.drop(SweepCap).foreach(os.remove.all)
    catch case _: Throwable => () // 清理面永不拖垮主腿

end NfThumb
