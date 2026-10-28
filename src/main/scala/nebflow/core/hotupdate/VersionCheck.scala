package nebflow.core.hotupdate

import cats.effect.IO
import nebflow.shared.Branding

/**
 * 版本指针读取与比对——**单一实现**。
 *
 * 复用点（设计附录 B #29）：既有设置页版本检查的「读版本指针 → 剥前导 v → 与当前
 * 版本比对」逻辑本体收拢于此，使更新编排器的「检查」相位与一切检查入口走**同一实现**
 * ——禁第二套比对（双实现必然漂移）。
 *
 * **读源（统一更新系统 M3 ②，DESIGN §5 兼容序，双读过渡期）**：
 *   1. 官网 manifest（`https://<domain>/updates/manifest[-beta].json`——stand-website
 *      M1 批发布的权威源）；
 *   2. manifest 不可达 / 解析失败 / schema 不识别 ⇒ 回落 OSS 裸 txt 指针（既有
 *      `latest-version.txt`，**只检不装**——该路径无 sha 校验面）；
 *   3. 都失败 ⇒ `None`（**既有语义：失败静默**）。
 * 网络语义与本批前逐字节一致：连接与读取各 5 秒超时、任何异常 ⇒ None。
 * 过渡期后 mirror 退役时点 = 开放问题 #3（采默认：保留至 M3 落地 + 一个发布周期）。
 */
object VersionCheck:

  /** 正式通道版本指针地址（桶名走 `Branding.cosBucket` 派生，禁硬编码桶名）。 */
  def pointerUrl: String =
    sys.props
      .get("nebflow.updates.pointerUrl")
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse:
        s"https://${Branding.cosBucket}.oss-cn-hangzhou.aliyuncs.com/latest-version.txt"

  /**
   * manifest 地址（DESIGN §5：stable = `/updates/manifest.json`，beta 同构
   * `manifest-beta.json`——与既有 latest-version.txt / latest-beta-version.txt
   * 双通道对齐）。域名走 brand.conf `domain` 单一事实源；验证注入面：系统属性
   * `nebflow.updates.manifestUrl`（staging/local fixture 用，与既有
   * `nebflow.hotupdate.installCommand` 同款口径）。
   */
  def manifestUrl(beta: Boolean): String =
    sys.props
      .get("nebflow.updates.manifestUrl")
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse:
        val base = s"https://${Branding.domain}/updates/manifest"
        if beta then s"$base-beta.json" else s"$base.json"

  /** 读原文（前导 `v` 由调用方决定剥不剥）。连接与读取各 5 秒超时；异常 / 空响应 ⇒ None。 */
  def fetchRaw(url: String): IO[Option[String]] =
    IO.blocking {
      try
        val conn = java.net.URI.create(url).toURL.openConnection()
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(5000)
        val raw =
          new String(conn.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
        if raw.nonEmpty then Some(raw) else None
      catch case _: Exception => None
    }

  /** 无参形态（既有检查链同款：stable 通道裸 txt 直读）。 */
  def fetchRaw(): IO[Option[String]] = fetchRaw(pointerUrl)

  /**
   * 读 manifest（M3 ②读序第一跳）。`schema` 是清单演进位：**只认 schema=1**，
   * 不识别 ⇒ 按「不可用」处理（同失败静默语义，DESIGN §5 schema 字段契约）。
   * 解析容错：未知增量字段忽略（向前兼容）；`version` 缺失 ⇒ 不可用。
   */
  def fetchManifest(beta: Boolean): IO[Option[ReleaseManifest]] =
    // URL 解析在 IO **运行期**求值（构造期会读不到测试注入面——props 是运行时事实）
    IO(manifestUrl(beta)).flatMap(fetchRaw).map(_.flatMap(ReleaseManifest.parse))

  /** 最新版本号（M3 读序：manifest → OSS txt 只检不装 → None；剥前导 `v`）。 */
  def fetchLatestVersion(beta: Boolean = false): IO[Option[String]] =
    fetchManifest(beta).flatMap {
      case Some(m) => IO.pure(Some(m.version.stripPrefix("v")))
      case None =>
        IO {
          if beta then
            sys.props.get("nebflow.updates.pointerBetaUrl").map(_.trim).filter(_.nonEmpty).getOrElse(pointerBetaUrl)
          else pointerUrl
        }.flatMap(fetchRaw).map(_.map(_.stripPrefix("v")))
    }

  /** beta 通道裸 txt 指针（回落位；与 install.sh 的 `latest-beta-version.txt` 同键）。 */
  def pointerBetaUrl: String =
    s"https://${Branding.cosBucket}.oss-cn-hangzhou.aliyuncs.com/latest-beta-version.txt"

  /** 是否有更新（异值即有更新——与既有检查链同口径，冻结不变）。 */
  def hasUpdate(current: String, latest: String): Boolean = latest != current

  /**
   * 日历版序比较（M3 ③采纳语义的承重件；**与 link-server M2 `cmp_versions`
   * 逐条同构——服务端口径冻结一致，禁第二套排序语义**）：
   *   - 先按第一个 `-` 拆 (core, pre)；
   *   - core 按点分段逐段数值比较（缺段 = 0；数字/非数字混排段回退整段字典序）；
   *   - core 相等时 pre-release 排在裸发布**之前**（semver 规则），两个 pre 按字典序。
   * 字符串直接比较会反转 2026.10 vs 2026.9——禁用。
   */
  def compareVersions(a: String, b: String): Int =
    def splitRelease(tag: String): (String, Option[String]) =
      tag.split("-", 2) match
        case Array(core) => (core, None)
        case Array(core, pre) => (core, Some(pre))
        case _ => (tag, None)

    def cmpSegment(x: String, y: String): Int =
      (x.toLongOption, y.toLongOption) match
        case (Some(nx), Some(ny)) => java.lang.Long.compare(nx, ny)
        case _ => x.compare(y)

    val (aCore, aPre) = splitRelease(a)
    val (bCore, bPre) = splitRelease(b)
    val aParts = aCore.split('.').toList
    val bParts = bCore.split('.').toList
    val len = math.max(aParts.length, bParts.length)
    val coreOrd = (0 until len)
      .map { i =>
        val pa = aParts.applyOrElse(i, (_: Int) => "0")
        val pb = bParts.applyOrElse(i, (_: Int) => "0")
        cmpSegment(pa, pb)
      }
      .find(_ != 0)
      .getOrElse(0)
    if coreOrd != 0 then coreOrd
    else
      (aPre, bPre) match
        case (None, None) => 0
        case (Some(_), None) => -1
        case (None, Some(_)) => 1
        case (Some(x), Some(y)) => x.compare(y)
  end compareVersions

end VersionCheck

/**
 * 官网 release manifest（stand-website M1 批 schema=1 的引擎侧读面；DESIGN §5
 * 草案字段）。解析**容错**：未知字段忽略（向前兼容）；引擎消费的字段缺失时按
 * 「该能力不可用」降级而非解析失败（jar 校验面字段不齐 ⇒ 走 legacy 安装路径）。
 */
final case class ReleaseManifest(
  channel: String,
  version: String,
  build: Option[String],
  publishedAt: Option[String],
  releaseNotesUrl: Option[String],
  jarUrl: Option[String],
  jarSha256: Option[String],
  jarSizeBytes: Option[Long],
  minCompatibleEngine: Option[String]
)

object ReleaseManifest:

  /** schema=1 门 + 宽容解析。非 1 / 解析异常 / version 缺失 ⇒ None（不可用）。 */
  def parse(raw: String): Option[ReleaseManifest] =
    try
      val json = io.circe.parser.parse(raw).toOption
      for
        obj <- json
        schema <- obj.hcursor.downField("schema").as[Int].toOption if schema == 1
        version <- obj.hcursor.downField("version").as[String].toOption.filter(_.trim.nonEmpty)
        channel = obj.hcursor.downField("channel").as[String].toOption.filter(_.trim.nonEmpty).getOrElse("stable")
        jar = obj.hcursor.downField("artifacts").downField("jar")
      yield ReleaseManifest(
        channel = channel,
        version = version.trim,
        build = obj.hcursor.downField("build").as[String].toOption.map(_.trim).filter(_.nonEmpty),
        publishedAt = obj.hcursor.downField("publishedAt").as[String].toOption,
        releaseNotesUrl = obj.hcursor.downField("releaseNotesUrl").as[String].toOption,
        jarUrl = jar.downField("url").as[String].toOption.map(_.trim).filter(_.nonEmpty),
        jarSha256 = jar.downField("sha256").as[String].toOption.map(_.trim.toLowerCase).filter(_.nonEmpty),
        jarSizeBytes = jar.downField("sizeBytes").as[Long].toOption.filter(_ > 0),
        minCompatibleEngine = obj.hcursor.downField("minCompatibleEngine").as[String].toOption
      )
      end for
    catch case _: Exception => None
end ReleaseManifest
