package nebflow.core.plugin

import cats.effect.IO
import io.circe.Json
import nebflow.shared.Branding

import scala.util.control.NonFatal

/**
 * PluginSourceCheck —— 官方源读取面（远程发布源设计稿 §3.2/§4.1，P2 批）。
 *
 * 读序（对齐既有双跳先例 [[nebflow.core.hotupdate.VersionCheck]]，禁第二套形态）：
 *   1. 官网静态资产 `https://<domain>/plugins/index[-beta].json`（brand.conf `domain`
 *      单一事实源派生；beta 通道同构 `index-beta.json`，A7① 一机一通道——通道跟随引擎
 *      `nebflow.Version.channel`，不新增第二个 beta 开关）；
 *   2. 不可达 / 解析失败 / schema≠1 ⇒ 回落 OSS 镜像 `<cosBucket>.oss-cn-hangzhou.aliyuncs.com/
 *      plugins/index[-beta].json`（同一文件字节，发布侧双写，§3.2 读序原文）；
 *   3. 都失败 ⇒ [[Unreachable]]（**列试过的端点与各自失败原因**——安装是用户动作，
 *      与 VersionCheck 的「失败静默 None」不同：检查可以静默，安装必须给可行动错误）。
 *
 * 清单 schema 校验（ReleaseManifest 先例，VersionCheck.scala:130-171 同款容忍度）：
 *  - `schema` 门：只认 schema=1，缺失/≠1 ⇒ 该端点按「不可用」处理（跳回落）；
 *  - **未知字段宽容忽略**（前向兼容——P3+ 加字段不破旧读端）；
 *  - **缺字段降级不失败**：`latest`/`sequence`/`manifest`/`publishedAt`/`requires` 等全部
 *    Option；索引条目缺 `id` ⇒ 跳过该条目（不炸整份清单）；版本条目缺 `version` ⇒ 跳过；
 *  - 安装期强依赖的字段（归档 `url`/`sha256`/`digest`）缺失不属解析失败——版本条目照常
 *    读入，由 [[PluginSourceInstall]] 按「该 artifact 不可用」处置（解析与安装两级容忍度）。
 *
 * `sequence`（单调发布序号）本批**只读不判**：防回滚水位线（§6.3）属 P4 信任迁移批，
 * 读取面先把字段带上来，P4 落 `plugins.source.sequence` 水位后启用「低于 ⇒ 整份作废」。
 *
 * 端点注入面（fixture/集成测试用，与 `nebflow.updates.manifestUrl` 同款系统属性口径）：
 *  - `nebflow.plugins.indexUrl`：主端点（官网）整串替换；
 *  - `nebflow.plugins.indexFallbackUrl`：回落端点（OSS）整串替换。
 */
object PluginSourceCheck:

  // ── 端点派生（brand.conf 单源；禁硬编码域名/桶名）────────────────

  /** OSS 发布端点基座（与 VersionCheck.pointerUrl / release/install.sh COS_BASE_CN 同值同派生）。 */
  def ossBase: String = s"https://${Branding.cosBucket}.oss-cn-hangzhou.aliyuncs.com"

  private def prop(name: String): Option[String] =
    sys.props.get(name).map(_.trim).filter(_.nonEmpty)

  /**
   * 官方索引端点（读序 = 列表序）：[官网主端点, OSS 回落端点]。
   * beta = `index-beta.json`（与 manifest-beta.json 同构命名，§7.3）。
   */
  def indexUrls(beta: Boolean): List[String] =
    val suffix = if beta then "-beta" else ""
    List(
      prop("nebflow.plugins.indexUrl").getOrElse(s"https://${Branding.domain}/plugins/index$suffix.json"),
      prop("nebflow.plugins.indexFallbackUrl").getOrElse(s"$ossBase/plugins/index$suffix.json")
    ).distinct

  /**
   * 版本清单端点（读序 = 列表序）。条目带显式 `manifest` URL ⇒ 只用该 URL（显式 pin，
   * 不臆造镜像——镜像双写契约只对官方默认对成立）；缺省 ⇒ 由**实际伺服成功的索引 URL**
   * 的目录派生（`<indexDir>/<id>/manifest[-beta].json`），且官方默认对补一条 OSS 同路径
   * 镜像（`plugins/<id>/manifest[-beta].json`，§3.3 布局）。
   *
   * @param officialPair true = 默认官方索引（spec.index 缺省）⇒ 允许补 OSS 镜像跳
   */
  def manifestUrls(servedIndexUrl: String, entry: IndexEntry, beta: Boolean, officialPair: Boolean): List[String] =
    entry.manifest match
      case Some(explicit) => List(explicit)
      case None =>
        val base = servedIndexUrl.substring(0, servedIndexUrl.lastIndexOf('/') + 1)
        val suffix = if beta then "-beta" else ""
        val primary = s"$base${entry.id}/manifest$suffix.json"
        if !officialPair then List(primary)
        else
          // 官方对 ⇒ 同路径 OSS 镜像（把 authority 换成 OSS 基座，路径段逐字保留）
          val path = primary.stripPrefix("https://").stripPrefix("http://").dropWhile(_ != '/')
          List(primary, s"$ossBase$path").distinct

  // ── 类型面（全部宽容解析；安装强依赖字段在 Install 层判）────────────

  final case class IndexEntry(
      id: String,
      latest: Option[String],
      sequence: Option[Long],
      manifest: Option[String]
  )

  final case class OfficialIndex(
      schema: Int,
      sequence: Option[Long],
      updatedAt: Option[String],
      minCompatibleEngine: Option[String],
      plugins: List[IndexEntry]
  ):
    def entry(id: String): Option[IndexEntry] = plugins.find(_.id == id)

  final case class Artifact(
      format: Option[String],
      sizeBytes: Option[Long],
      sha256: Option[String],
      digest: Option[String],
      url: Option[String],
      mirror: Option[String]
  ):
    /** P2 安装可用性：zip 格式（缺省视为 zip——唯一已知格式）+ 下载/双值校验三字段齐备。 */
    def usableForZipInstall: Boolean =
      (format.isEmpty || format.exists(_.equalsIgnoreCase("zip"))) &&
        url.exists(_.trim.nonEmpty) && sha256.exists(_.trim.nonEmpty) && digest.exists(_.trim.nonEmpty)

  final case class VersionEntry(
      version: String,
      publishedAt: Option[String],
      notesUrl: Option[String],
      minEngine: Option[String],
      manifestSchema: Option[String],
      artifacts: List[Artifact]
  )

  final case class VersionManifest(
      schema: Int,
      id: Option[String],
      sequence: Option[Long],
      versions: List[VersionEntry]
  )

  // ── 解析（纯函数；schema=1 门 + 宽容降级）──────────────────────

  /** 索引解析：非 JSON / 非 object / schema 缺失或 ≠1 ⇒ None（该端点按不可用处理）。 */
  def parseIndex(raw: String): Option[OfficialIndex] =
    try
      for
        json <- io.circe.parser.parse(raw).toOption if json.isObject
        schema <- json.hcursor.downField("schema").as[Int].toOption if schema == 1
      yield
        val c = json.hcursor
        OfficialIndex(
          schema = schema,
          sequence = c.downField("sequence").as[Long].toOption,
          updatedAt = optionalStr(c, "updatedAt"),
          minCompatibleEngine = optionalStr(c, "minCompatibleEngine"),
          plugins = c.downField("plugins").as[List[Json]].toOption.getOrElse(Nil).flatMap(parseEntry)
        )
    catch case NonFatal(_) => None

  private def parseEntry(json: Json): Option[IndexEntry] =
    if !json.isObject then None
    else
      val c = json.hcursor
      optionalStr(c, "id").map { id =>
        IndexEntry(
          id = id,
          latest = optionalStr(c, "latest"),
          sequence = c.downField("sequence").as[Long].toOption,
          manifest = optionalStr(c, "manifest")
        )
      }

  /** 版本清单解析：同款 schema 门；版本条目缺 `version` ⇒ 跳过（降级不失败）。 */
  def parseVersionManifest(raw: String): Option[VersionManifest] =
    try
      for
        json <- io.circe.parser.parse(raw).toOption if json.isObject
        schema <- json.hcursor.downField("schema").as[Int].toOption if schema == 1
      yield
        val c = json.hcursor
        VersionManifest(
          schema = schema,
          id = optionalStr(c, "id"),
          sequence = c.downField("sequence").as[Long].toOption,
          versions = c.downField("versions").as[List[Json]].toOption.getOrElse(Nil).flatMap(parseVersion)
        )
    catch case NonFatal(_) => None

  private def parseVersion(json: Json): Option[VersionEntry] =
    if !json.isObject then None
    else
      val c = json.hcursor
      optionalStr(c, "version").map { version =>
        val requires = c.downField("requires").focus.filter(_.isObject).map(_.hcursor)
        VersionEntry(
          version = version,
          publishedAt = optionalStr(c, "publishedAt"),
          notesUrl = optionalStr(c, "notesUrl"),
          minEngine = requires.flatMap(r => optionalStr(r, "minEngine")),
          manifestSchema = requires.flatMap(r => optionalStr(r, "manifestSchema")),
          artifacts = c.downField("artifacts").as[List[Json]].toOption.getOrElse(Nil).flatMap(parseArtifact)
        )
      }

  private def parseArtifact(json: Json): Option[Artifact] =
    if !json.isObject then None
    else
      val c = json.hcursor
      Some(
        Artifact(
          format = optionalStr(c, "format"),
          sizeBytes = c.downField("sizeBytes").as[Long].toOption.filter(_ >= 0),
          sha256 = optionalStr(c, "sha256").map(_.trim.toLowerCase),
          digest = optionalStr(c, "digest").map(_.trim.toLowerCase),
          url = optionalStr(c, "url"),
          mirror = optionalStr(c, "mirror")
        )
      )

  private def optionalStr(c: io.circe.HCursor, field: String): Option[String] =
    c.downField(field).as[String].toOption.map(_.trim).filter(_.nonEmpty)

  // ── 读取（IO；超时/错误语义与 VersionCheck 同族）────────────────

  /** 读端点失败清单：`(url, reason)` 有序——render 给用户的「试过哪些端点」证据面。 */
  final case class Unreachable(tried: List[(String, String)]):
    def render: String =
      s"official plugin source unreachable — tried: " +
        tried.map((u, r) => s"$u ($r)").mkString("; ")

  /**
   * 读原文（索引/清单共用）。连接与读取各 5 秒超时（VersionCheck.fetchRaw 同款）；
   * 非 2xx / 异常 ⇒ Left(原因)。空体照常返回（解析层判不可用——降级不失败）。
   */
  def fetchText(url: String): IO[Either[String, String]] =
    IO.blocking {
      try
        val conn = java.net.URI.create(url).toURL.openConnection()
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(5000)
        val code = conn match
          case hc: java.net.HttpURLConnection => hc.getResponseCode
          case _ => 200
        if code < 200 || code >= 300 then Left(s"HTTP $code")
        else Right(new String(conn.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
      catch case NonFatal(e) => Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
    }

  /** 按列表序尝试各端点：首个「取到 ∧ 解析通过」者胜；全败 ⇒ 逐端点原因（有序，render 用）。 */
  private def firstOk[A](urls: List[String], parse: String => Option[A]): IO[Either[Unreachable, (A, String)]] =
    def go(rest: List[String], tried: List[(String, String)]): IO[Either[Unreachable, (A, String)]] =
      rest match
        case Nil => IO.pure(Left(Unreachable(tried)))
        case url :: tail =>
          fetchText(url).flatMap {
            case Right(raw) =>
              parse(raw) match
                case Some(v) => IO.pure(Right((v, url)))
                case None    => go(tail, tried :+ (url -> "unparseable or schema not supported"))
            case Left(reason) => go(tail, tried :+ (url -> reason))
          }
    go(urls, Nil)

  /** 读官方索引（读序 = [[indexUrls]] 序；全失败 ⇒ Unreachable 含逐端点原因）。 */
  def fetchIndex(beta: Boolean): IO[Either[Unreachable, (OfficialIndex, String)]] =
    fetchIndexAt(indexUrls(beta))

  /** 同款读序，端点列表由调用方给（spec 显式 index / fixture 注入形态）。 */
  def fetchIndexAt(urls: List[String]): IO[Either[Unreachable, (OfficialIndex, String)]] =
    firstOk(urls, parseIndex)

  /** 读版本清单（读序 = 传入端点序（[[manifestUrls]] 产物）；同款失败面）。 */
  def fetchVersionManifest(urls: List[String]): IO[Either[Unreachable, (VersionManifest, String)]] =
    firstOk(urls, parseVersionManifest)

end PluginSourceCheck
