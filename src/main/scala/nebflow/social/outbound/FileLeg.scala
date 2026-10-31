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
   * 压缩包扩展名（小写、不带点）。2026-10-21 批首批收编的「文件类交付物」缺口面
   * （Pop 一个 zip/tar.gz 当时只落日志，作者令点名「压缩包=文件腿直推」）。
   *
   * 2026-10-31 渠道文件出站批作者实证（Pop 两个 .txt 测试文件、微信/飞书双双
   * 未收，日志落「extension is in neither authority table: txt」）把直推面**从
   * 压缩包表 widened 到一切在盘普通文件**（[[isPushable]]）——本表保留为具名
   * 类目的权威（压缩包=file 腿，与 W-4b/WO-W7 钉同面），不再被 isPushable 读取。
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

  /**
   * 文件腿直推判据（2026-10-31 渠道文件出站批收口，作者令「Pop 触达的文件随
   * 渠道腿出站到微信/飞书」）：**任何在盘普通文件**——分类层两张权威表
   * （图片表 / 文档表）之外的全部扩展名（txt/log/csv/json、压缩包、其余一切）
   * 一律文件腿直推。
   *
   * 分类层零扩表的结构决定不变（RichKind 两张表是控制台/渲染面的权威）；本判据
   * 只在投递面被读取。md/markdown/html/htm 不经这里（长图臂优先）；图片不经
   * 这里（组卡臂）。体积闸（[[MaxBytes]]）与去重（FileLongShot.Dedup，同台账）
   * 在投递实现内，本判据只管「上不上腿」。
   *
   * 渠道能力边界（如实）：微信腿 = sidecar file_path（getuploadurl media_type=3
   * → file_item，无公开类型白名单文档，渠道拒绝走可见降级卡）；飞书腿 =
   * im/v1/files，file_type 枚举 opus/mp4/pdf/doc/xls/ppt/STREAM，off-table 件按
   * STREAM「原字节直发」。文件不存在/非普通文件 ⇒ false（调用方维持 v1 日志/
   * 降级文本边界，无件可发）。
   */
  def isPushable(p: os.Path): Boolean = Try(os.isFile(p)).getOrElse(false)

  /**
   * 引擎侧体积闸（字节）。依据见文件头：与 sidecar 发送腿 sanity 默认
   * （25MB）对齐——超限件在引擎侧降级，不发起必被 400 拒绝的发送。
   */
  val MaxBytes: Long = 25_000_000L

  /** 文件字节数；读不到（文件消失/IO 错）⇒ None（调用方按不可读处理）。 */
  def sizeOf(p: os.Path): Option[Long] = Try(os.size(p)).toOption

end FileLeg
