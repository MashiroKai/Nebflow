package nebflow.social.outbound

import scala.util.Try

/**
 * 文件腿直推策略（pop-file-leg batch, 2026-10-21；作者令「这样你使用 pop 把这个
 * 文件发给我，就自动传过来了呀」）：PDF/办公件/压缩包类文件产物在社交出站面
 * **走 sidecar 文件腿直推**，不再只落在分类层 Unknown 的日志一行里。
 *
 * 类型策略（本批口径，承接 2026-10-20 长图批并补齐其文件面）：
 *
 *   - PDF / Office / epub / 字体等——已在文档权威表
 *     （`FeishuOutboundAdapter.defaultTable` = `NfFilePolicy.NfFileAllowedExt`，
 *     经 `RichKind.of` 判 Document）⇒ 文件腿，本批补去重 + 体积闸；
 *   - **压缩包**——两张权威表都不收（`NfFileAllowedExt` 无 zip/7z/rar/tar 族），
 *     原 Unknown 臂只落日志 ⇒ 本批在本表收编，Unknown 臂读它走文件腿；
 *   - md/markdown/html/htm——沿用既有「转单张长图」社交口径
 *     （[[FileLongShot]]，2026-10-20 作者裁定，本批零触碰）；
 *   - 其余扩展名——维持 v1 边界（日志一行，正文腿带回合）。
 *
 * 🔴 不扩 `RichKind` 的两张权威表（与 webshot/longshot 批同一结构决定：分类层
 * 表是控制台/渲染面的权威，不因出站投递能力而扩表）——本表只在投递面被读取。
 *
 * 体积上限依据（pop-file-leg batch 报告 §体积）：iLink bot 文件消息**无公开权威
 * 上限文档**（官方插件源注释「the protocol itself has no caps」；社区收集的 API
 * 参考与错误码表均无尺寸项）。仓内两处收敛读数：sidecar 发送腿 sanity
 * `MEDIA_MAX_BYTES` 默认 25MB（env `WEIXIN_SIDECAR_MEDIA_MAX_BYTES` 可调，
 * weixin-media batch）+ 入站媒体腿 100MB（weixin-inbound-media batch）。发送
 * 方向**实际生效**的是前者 ⇒ 引擎闸取 25MB 与 sidecar 默认对齐：超限在引擎侧
 * 降级（文本说明 + 控制台指引），不让 400 打到 sidecar 再兜一圈。
 */
object FileLeg:

  /**
   * 压缩包扩展名（小写、不带点）。文档权威表不收的「文件类交付物」缺口面——
   * Pop 一个 zip/tar.gz 今天只落日志，作者令点名「压缩包=文件腿直推」。
   */
  val ArchiveExtensions: Set[String] = Set(
    "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst"
  )

  /** 小写扩展名（不带点），无扩展名 ⇒ ""。与 [[RichKind.of]] 同一读取口径。 */
  def extensionOf(name: String): String =
    val lower = name.toLowerCase
    lower.lastIndexOf('.') match
      case -1 => ""
      case i  => lower.substring(i + 1)

  /** 是否文件腿直推类（压缩包表；PDF/办公件走 Document 臂，不经这里）。 */
  def isPushable(p: os.Path): Boolean = ArchiveExtensions.contains(extensionOf(p.toString))

  /**
   * 引擎侧体积闸（字节）。依据见文件头：与 sidecar 发送腿 sanity 默认
   * （25MB）对齐——超限件在引擎侧降级，不发起必被 400 拒绝的发送。
   */
  val MaxBytes: Long = 25_000_000L

  /** 文件字节数；读不到（文件消失/IO 错）⇒ None（调用方按不可读处理）。 */
  def sizeOf(p: os.Path): Option[Long] = Try(os.size(p)).toOption

end FileLeg
