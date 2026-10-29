package nebflow.core.plugin

import cats.effect.IO
import nebflow.shared.{NebflowLogger, PathUtil}
import PluginSourceLedger.{Kind, Pin, Via}

import java.security.MessageDigest

import scala.util.control.NonFatal

/**
 * PluginSourceInstall —— 从源安装流（远程发布源设计稿 §4，P2 批）。
 *
 * 管线（三态同构，§4.2 状态机）：source spec 解析（P1 类型面）→ 暂存获取
 * （official=清单派生归档下载、git=pin clone、https=直链归档）→ **双值校验**
 * （归档字节 sha256 + 解出后内容树 digest 咬合，§0.3 公理 2——装机时现算，
 * 绝不信任声明值直接落库）→ 原子切换落盘（staging 在 `plugins/` 之外 ⇒
 * 半装态永不进注册表扫描面；落地 = `os.move` 同分区 rename）→ provenance 落账
 * （P1 账本 [[PluginSourceLedger.recordInstall]]，best-effort 不回滚安装）→
 * 装载语义零改动（在位即信任照旧，公理 3）。
 *
 * 入口三面（§4.1）：
 *  - [[installFromArg]]：CLI `stand plugin add <arg>` 歧义消解后的分派
 *    （`://`/`git@`/`.git` ⇒ git 源；`/`、`./`、`~` 开头或路径存在 ⇒ 本地；
 *    其余 ⇒ 官方 id（`name[@version]`）；本地∧官方 id 双命中 ⇒ 显式报错列候选，不猜）；
 *  - [[installFromSpecJson]]：CLI `stand plugin add --from <spec.json>`（三态 spec 直读）；
 *  - [[installOfficial]] / [[installHttps]] / [[installGitPinned]]：三态各自实现单点。
 *
 * 版本解析（A8②）：`@version` 精确指定（清单 versions[] 里找不到 ⇒ 报错并列可用版本）；
 * 缺省跟引擎通道（`nebflow.Version.channel` ⇒ index.json / index-beta.json，A7① 一机一
 * 通道——不新增第二个 beta 开关），装清单 versions[] 第一条（新→旧，装载器只需第一条）。
 *
 * 官方身份闸（P2 形态）：official 安装的 id 必须在官方索引里（[[CodeIdNotInIndex]]）；
 * `nebflow-` 前缀与 pin 表的内容闸迁移属 P4（本批装载/身份判定面零改动）。
 *
 * 本批不做（P3+）：更新流/三态冲突/回滚、`--replace`（同名已存在即拒——1274-1281
 * 现状口径逐字保持）、清单 sequence 防回滚水位（P4 §6.3）、minEngine 安装闸（归
 * P3 检查面；字段本批已读入）。
 *
 * 安全围栏（§2.3）：zip 条目拒绝绝对路径 / `\` 分隔 / `..`·`.`·空段 / `:` 段 /
 * 越出包根（[[PluginRegistry.containedUnder]] 终防）／符号链接条目（解压后全树
 * isLink 巡检）；条目数 ≤ 4096、解压总字节 ≤ 256MB、归档字节长与声明 sizeBytes 咬合。
 */
object PluginSourceInstall:

  private val logger = NebflowLogger.forName("nebflow.plugin.sources")

  // ── 稳定错误码（🔴 禁改名——CLI/REST/测试逐字断言）────────────────

  val CodeUnreachable      = "SOURCE_UNREACHABLE"       // 索引/清单/归档所有候选端点都不可达
  val CodeIdNotInIndex     = "SOURCE_ID_NOT_IN_INDEX"   // 官方身份闸：id ∉ 官方索引
  val CodeVersionNotFound  = "SOURCE_VERSION_NOT_FOUND" // @version 不在清单（列可用版本）
  val CodeArtifactUnusable = "SOURCE_ARTIFACT_UNUSABLE" // 清单版本无可用 zip artifact（字段不齐）
  val CodeSizeMismatch     = "SOURCE_SIZE_MISMATCH"     // 归档字节长 ≠ 声明 sizeBytes
  val CodeSha256Mismatch   = "SOURCE_SHA256_MISMATCH"   // 双值校验①：归档字节 sha256 失配
  val CodeDigestMismatch   = "SOURCE_DIGEST_MISMATCH"   // 双值校验②：内容树 digest 失配
  val CodeArchiveLayout    = "SOURCE_ARCHIVE_LAYOUT"    // 归档布局违规（无 plugin.json 等）
  val CodeArchiveFence     = "SOURCE_ARCHIVE_FENCE"     // zip 围栏拒绝（逃逸/链接/超限）
  val CodeGitPinRequired   = "GIT_PIN_REQUIRED"         // 浮动 HEAD 禁令（§2.2B）
  val CodeGitCloneFailed   = "GIT_CLONE_FAILED"
  val CodeGitTagNotFound   = "GIT_TAG_NOT_FOUND"        // tag 不存在/不可解析
  val CodeGitTagMoved      = "GIT_TAG_MOVED"            // tag 解引用 ≠ pin commit（防 tag 被移动）
  val CodeGitCheckoutFailed = "GIT_CHECKOUT_FAILED"

  // ── zip 围栏常量（§2.3；zip bomb 面）─────────────────────────

  private val MaxZipEntries        = 4096
  private val MaxUncompressedBytes = 256L * 1024 * 1024
  private val MaxArchiveBytes      = 256L * 1024 * 1024

  // ── 路径（与 PluginRegistry.pluginsDir / P1 ledgerDir 同根派生）──

  private def pluginsDir: os.Path  = PathUtil.dataRoot / "plugins"
  private def stagingRoot: os.Path = PathUtil.dataRoot / ".plugin-staging"

  /**
   * A7①：插件通道跟随引擎（一机一通道）；测试经 installOfficial 的显式 beta 参数注入。
   * dev 构建的外部通道自报可被 `nebflow.plugins.channel` 系统属性覆盖（fixture/集成
   * 测试缝，与 nebflow.updates.manifestUrl 同款注入口径；生产不设 ⇒ Version.channel）。
   */
  def engineBeta: Boolean =
    sys.props.get("nebflow.plugins.channel").map(_.trim).filter(_.nonEmpty) match
      case Some(forced) => forced.equalsIgnoreCase("beta")
      case None         => nebflow.Version.channel.trim.equalsIgnoreCase("beta")

  // ── 入口①：CLI 参数歧义消解（§4.1 逐字规则）────────────────────

  sealed trait ArgClass
  object ArgClass:
    final case class Git(url: String, tag: Option[String])           extends ArgClass
    final case class Local(path: String)                             extends ArgClass
    final case class Official(id: String, version: Option[String])   extends ArgClass

  /**
   * `#tag` 片段先行剥离（git URL 的 tag 载体；本地路径带 `#` 不属合法形态），其后按
   * §4.1 判据：git 标记（含 `://` / `git@` / `.git` 结尾）**优先**——与 installFromSync
   * 既有 isGit 先判同序（保住「绝对路径/.git 结尾 = 本地仓 clone」的既有语义，
   * PluginSourceLedgerSpec 同款夹具依赖它）；其余：本地标记（`/`、`./`、`../`、`~`
   * 开头、`.`/`..` 或路径存在）与官方 id（`name[@version]`）双命中 ⇒ 歧义报错列候选；
   * 单命中 ⇒ 该态；零命中 ⇒ 可行动指引。
   */
  def classifyArg(raw: String): Either[String, ArgClass] =
    val arg = raw.trim
    if arg.isEmpty then
      Left("plugin source required — pass an official plugin id (name[@version]), a git URL ('<url>#<tag>'), or a local plugin directory")
    else
      val hash = arg.indexOf('#')
      val base = if hash >= 0 then arg.substring(0, hash).trim else arg
      val tag  = if hash >= 0 then Some(arg.substring(hash + 1).trim).filter(_.nonEmpty) else None
      if PluginSourceSpec.isGitUrl(base) then Right(ArgClass.Git(base, tag))
      else
        val looksLocal =
          base.startsWith("/") || base.startsWith("./") || base.startsWith("../") ||
            base.startsWith("~") || base == "." || base == ".."
        val pathExists =
          try os.exists(os.Path(expandTilde(base), os.pwd))
          catch case NonFatal(_) => false
        val official = parseOfficialArg(base)
        if looksLocal then Right(ArgClass.Local(base))
        else if pathExists then
          official match
            // 双命中 ⇒ 显式报错列候选，不猜（§4.1 原文）
            case Some(_) =>
              Left(
                s"ambiguous plugin source '$arg' — it matches both an official plugin id and an existing local path. " +
                  "Prefix the local path with './' to install locally, or use 'stand plugin add --from <spec.json>' " +
                  "to name the source explicitly."
              )
            case None => Right(ArgClass.Local(base))
        else official match
          case Some((id, version)) => Right(ArgClass.Official(id, version))
          case None =>
            Left(
              s"'$arg' is not a git URL, does not name an existing local path, and is not a valid official plugin id " +
                "(official form: name[@version]; names are 1-64 chars of a-z 0-9 - . per §5.5). " +
                "Or pass a full source spec: stand plugin add --from <spec.json>."
            )
    end if
  end classifyArg

  /** `name[@version]`：name 过 §5.5 名约束才算官方形态；version 非空任意串（未知版本由索引面报）。 */
  private def parseOfficialArg(base: String): Option[(String, Option[String])] =
    base.indexOf('@') match
      case -1 => Option.when(PluginRegistry.validPluginName(base))((base, None))
      case i =>
        val id = base.substring(0, i)
        val v  = base.substring(i + 1).trim
        Option.when(PluginRegistry.validPluginName(id) && v.nonEmpty)((id, Some(v)))

  private def expandTilde(p: String): String =
    if p == "~" then System.getProperty("user.home")
    else if p.startsWith("~/") then System.getProperty("user.home") + p.stripPrefix("~")
    else p

  /** CLI `stand plugin add <arg>` 的分派单点。 */
  def installFromArg(raw: String): IO[Either[String, String]] =
    classifyArg(raw) match
      case Left(err)                       => IO.pure(Left(err))
      case Right(ArgClass.Git(url, tag))   => installGitFromArg(url, tag)
      case Right(ArgClass.Local(path))     => PluginRegistry.installFromLocalPath(path)
      case Right(ArgClass.Official(id, v)) => installOfficial(id, v, beta = engineBeta, explicitIndex = None)

  // ── 入口②：--from 三态 spec 直读 ────────────────────────────

  def installFromSpecJson(raw: String): IO[Either[String, String]] =
    PluginSourceSpec.parse(raw) match
      case Left(err)   => IO.pure(Left(err.render))
      case Right(spec) => installFromSpec(spec)

  def installFromSpec(spec: PluginSourceSpec.Spec): IO[Either[String, String]] = spec match
    case o: PluginSourceSpec.OfficialSpec => installOfficial(o.id, version = None, beta = engineBeta, explicitIndex = o.index)
    case g: PluginSourceSpec.GitSpec      => installGitPinned(g)
    case h: PluginSourceSpec.HttpsSpec    => installHttps(h)

  // ── official 腿：索引 → 清单 → 版本解析 → 归档双值 → 落盘 ────────

  /**
   * 官方源安装。`explicitIndex` = spec 显式 index URL（`--from` 自定义源：不臆造 OSS
   * 镜像跳）；None = 默认官方对（官网 → OSS 双跳，A8② 通道由 `beta` 决定）。
   */
  def installOfficial(
      id: String,
      version: Option[String],
      beta: Boolean,
      explicitIndex: Option[String]
  ): IO[Either[String, String]] =
    val indexEndpoints = explicitIndex match
      case Some(u) => List(u)
      case None    => PluginSourceCheck.indexUrls(beta)
    PluginSourceCheck.fetchIndexAt(indexEndpoints).flatMap {
      case Left(un) => IO.pure(Left(s"${un.render} (${CodeUnreachable})"))
      case Right((index, servedIndexUrl)) =>
        index.entry(id) match
          case None =>
            IO.pure(Left(
              s"official identity gate: id '$id' is not in the official plugin index (served by $servedIndexUrl) — " +
                s"the official source only installs ids it publishes. (${CodeIdNotInIndex})"
            ))
          case Some(entry) =>
            val manifestEndpoints =
              PluginSourceCheck.manifestUrls(servedIndexUrl, entry, beta, officialPair = explicitIndex.isEmpty)
            PluginSourceCheck.fetchVersionManifest(manifestEndpoints).flatMap {
              case Left(un) => IO.pure(Left(s"${un.render} (${CodeUnreachable})"))
              case Right((manifest, _)) =>
                IO.pure(resolveVersion(manifest, version)).flatMap {
                  case Left(err) => IO.pure(Left(err))
                  case Right(ventry) =>
                    IO.pure(ventry.artifacts.find(_.usableForZipInstall)).flatMap {
                      case None =>
                        IO.pure(Left(
                          s"version ${ventry.version} of '$id' has no usable zip artifact (needs format=zip-or-absent " +
                            s"plus url+sha256+digest on the artifact; manifest listed ${ventry.artifacts.size} artifact(s)). " +
                            s"(${CodeArtifactUnusable})"
                        ))
                      case Some(artifact) =>
                        // usableForZipInstall 已钉死 sha256/digest/url 齐备；此处只是拆 Option 壳
                        val expectedSha = artifact.sha256.map(_.trim.toLowerCase).getOrElse("")
                        val expectedTreeDigest = artifact.digest.map(_.trim.toLowerCase)
                        downloadArtifact(artifact).flatMap {
                          case Left(err) => IO.pure(Left(s"$err (${CodeUnreachable})"))
                          case Right(bytes) =>
                            // 校验 + 原子落盘同在一个暂存会话内（withStagingSync 的 finally
                            // 兜底清理——会话内存活物只有未提交的暂存包）
                            IO.blocking {
                              withStagingSync(id) { pkg =>
                                verifyAndStageZip(
                                  bytes, pkg,
                                  declaredSize = artifact.sizeBytes,
                                  expectedSha256 = expectedSha,
                                  expectedDigest = expectedTreeDigest
                                ).flatMap { digest =>
                                  commitStaged(
                                    pkg, digest, fallbackVersion = ventry.version,
                                    kind = Kind.Official, via = Via.Official,
                                    url = artifact.url, pin = None,
                                    sourceDesc = s"official source ($id@${ventry.version})"
                                  )
                                }
                              }
                            }
                        }
                    }
                }
            }
        end match
    }
  end installOfficial

  /** 版本解析（A8②）：@version 精确 / 缺省 = versions[] 第一条（新→旧，装载器只需第一条）。 */
  private def resolveVersion(manifest: PluginSourceCheck.VersionManifest, wanted: Option[String]): Either[String, PluginSourceCheck.VersionEntry] =
    val available = manifest.versions.map(_.version)
    wanted match
      case None =>
        manifest.versions.headOption.toRight(
          s"version manifest lists no usable versions (every entry is missing its version string) (${CodeVersionNotFound})"
        )
      case Some(v) =>
        manifest.versions.find(_.version == v).toRight(
          s"version $v is not published by this manifest (available: ${if available.isEmpty then "none" else available.mkString(", ")}) " +
            s"(${CodeVersionNotFound})"
        )

  /** 归档下载（官方 = OSS 字节为主、mirror 兜底；https = 直链单候选）。全败 ⇒ 列试过端点。 */
  private def downloadArtifact(a: PluginSourceCheck.Artifact): IO[Either[String, Array[Byte]]] =
    downloadFromCandidates(List(a.url, a.mirror).flatten.distinct)

  private def downloadFromCandidates(urls: List[String]): IO[Either[String, Array[Byte]]] =
    def go(rest: List[String], tried: List[String]): IO[Either[String, Array[Byte]]] =
      rest match
        case Nil => IO.pure(Left(s"archive download failed — tried: ${if tried.isEmpty then "(no endpoint declared)" else tried.mkString("; ")}"))
        case url :: tail =>
          fetchBytes(url).flatMap {
            case Right(b)     => IO.pure(Right(b))
            case Left(reason) => go(tail, tried :+ s"$url ($reason)")
          }
    go(urls, Nil)

  /** 归档字节下载：连接 5s / 读取 60s（大归档）；硬上限 [[MaxArchiveBytes]]（内存炸弹面）。 */
  private def fetchBytes(url: String): IO[Either[String, Array[Byte]]] =
    IO.blocking {
      try
        val conn = java.net.URI.create(url).toURL.openConnection()
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(60000)
        val code = conn match
          case hc: java.net.HttpURLConnection => hc.getResponseCode
          case _                              => 200
        if code < 200 || code >= 300 then Left(s"HTTP $code")
        else
          val in = conn.getInputStream
          val buf = new java.io.ByteArrayOutputStream()
          val chunk = new Array[Byte](64 * 1024)
          var total = 0L
          var n = 0
          while
            n = in.read(chunk)
            n != -1
          do
            total += n
            if total > MaxArchiveBytes then
              in.close()
              throw new IllegalStateException(s"archive exceeds the $MaxArchiveBytes byte limit")
            buf.write(chunk, 0, n)
          in.close()
          Right(buf.toByteArray)
      catch case NonFatal(e) => Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
    }

  // ── 双值校验 + 围栏解压（official / https 两腿共用）───────────────

  /**
   * 下载字节 → 解出内容树：归档字节长 vs 声明 sizeBytes → **双值①** sha256(归档字节) →
   * 围栏解压到暂存 → **双值②** computeDigest(内容树) vs 声明 digest。任何一值失配 ⇒
   * 拒装（零副作用——暂存会话由 withStagingSync 的 finally 兜底清理，plugins/ 零触碰）。
   * 返回现算 digest（== 声明值；https 无声明 digest 时它成为落账基准）。
   */
  private def verifyAndStageZip(
      bytes: Array[Byte],
      pkg: os.Path,
      declaredSize: Option[Long],
      expectedSha256: String,
      expectedDigest: Option[String]
  ): Either[String, String] =
    declaredSize match
      case Some(want) if bytes.length != want =>
        Left(s"archive size mismatch — manifest declares $want bytes, downloaded ${bytes.length} (${CodeSizeMismatch})")
      case _ =>
        val actualSha = sha256Hex(bytes)
        if !MessageDigest.isEqual(actualSha.getBytes("UTF-8"), expectedSha256.toLowerCase.getBytes("UTF-8")) then
          Left(
            s"archive sha256 mismatch — expected ${expectedSha256.take(12)}…, got ${actualSha.take(12)}… " +
              s"(the bytes on the wire are not the published archive) (${CodeSha256Mismatch})"
          )
        else
          extractZipFenced(bytes, pkg).flatMap { _ =>
            PluginRegistry.computeDigest(pkg) match
              case Left(err) => Left(s"digest computation failed on staged package: $err (${CodeArchiveLayout})")
              case Right((digest, _)) =>
                expectedDigest match
                  case Some(want) if digest != want.toLowerCase =>
                    Left(
                      s"content digest mismatch — manifest pins ${want.take(12)}…, extracted tree computes ${digest.take(12)}… " +
                        s"(archive bytes matched but the unpacked content differs) (${CodeDigestMismatch})"
                    )
                  case _ => Right(digest)
          }
  end verifyAndStageZip

  /**
   * 围栏解压（§2.3）：条目名拒绝绝对路径 / `\` / `..`·`.`·空段·含 `:` 段；写出路径经
   * [[PluginRegistry.containedUnder]] 终防（单一 judge）；条目数 ≤ 4096、解压总字节
   * ≤ 256MB；包根必须平铺含 `plugin.json`（§2.3 布局）；解压后全树 isLink 巡检
   * （解压器从不创建链接——巡检是兜底防御 + 可断言的围栏面）。
   */
  private def extractZipFenced(zip: Array[Byte], dest: os.Path): Either[String, Unit] =
    try
      os.makeDir.all(dest)
      val zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))
      var entries = 0
      var total = 0L
      var failure: Option[String] = None
      var scanning = true
      while scanning && failure.isEmpty do
        val entry = zin.getNextEntry
        if entry == null then scanning = false
        else
          entries += 1
          if entries > MaxZipEntries then failure = Some(s"archive holds more than $MaxZipEntries entries (${CodeArchiveFence})")
          else
            val rawName = entry.getName
            val isDir = rawName.endsWith("/")
            val name = if isDir then rawName.dropRight(1) else rawName
            fenceError(name) match
              case Some(err) => failure = Some(err)
              case None =>
                try
                  val target = dest / os.SubPath(name)
                  if !PluginRegistry.containedUnder(dest, target) then
                    failure = Some(s"archive entry '$name' escapes the package root (${CodeArchiveFence})")
                  else if isDir then os.makeDir.all(target)
                  else
                    val content = zin.readAllBytes()
                    total += content.length
                    if total > MaxUncompressedBytes then
                      failure = Some(s"archive inflates beyond $MaxUncompressedBytes bytes (${CodeArchiveFence})")
                    else
                      os.makeDir.all(target / os.up)
                      os.write.over(target, content, createFolders = true)
                catch
                  case NonFatal(e) =>
                    failure = Some(s"archive entry '$name' failed to extract (${e.getClass.getSimpleName}: ${e.getMessage}) (${CodeArchiveFence})")
            end match
      end while
      zin.close()
      failure.toLeft(()).flatMap { _ =>
        val links =
          try os.walk(dest).filter(os.isLink).map(_.relativeTo(dest).toString).toList
          catch case NonFatal(_) => Nil
        if links.nonEmpty then
          Left(s"archive contains symlink entr${if links.size == 1 then "y" else "ies"}: ${links.mkString(", ")} — refused (${CodeArchiveFence})")
        else if !os.isFile(dest / "plugin.json") then
          Left(s"archive root has no plugin.json — the package root must be flat at the archive top level (design §2.3) (${CodeArchiveLayout})")
        else Right(())
      }
    catch case NonFatal(e) => Left(s"archive unreadable (${e.getClass.getSimpleName}: ${e.getMessage}) (${CodeArchiveLayout})")
  end extractZipFenced

  private def fenceError(name: String): Option[String] =
    val bad =
      name.startsWith("/") || name.contains("\\") || name.contains("\u0000") ||
        name.split('/').exists(seg => seg.isEmpty || seg == "." || seg == ".." || seg.contains(':'))
    Option.when(bad)(
      s"archive entry name '$name' violates the extraction fence (absolute path, backslash, or '.'/'..'/empty/':' segment) (${CodeArchiveFence})"
    )

  // ── git 腿：pin 必填（浮动 HEAD 退役）→ clone → 双锚校验 → 落盘 ───

  /**
   * CLI git 形态（`<git-url>[#<tag>]`）。**浮动 HEAD 退役**（设计 §4.1 对今天的
   * `add <git-url>` 的裁定 + §2.2B「禁浮动 HEAD」）：无 `#tag` ⇒ 拒绝并给写法指引。
   * tag 在安装时刻解引用为 commit（ls-remote），该 commit 成为不可变锚。
   */
  def installGitFromArg(url: String, tag: Option[String]): IO[Either[String, String]] =
    tag match
      case None =>
        IO.pure(Left(
          s"git source '$url' carries no pin — floating HEAD installs are forbidden (the same URL could install " +
            "different content on different days, which defeats the digest trust face). Pass '<url>#<tag>' or use " +
            s"'stand plugin add --from' with a spec pinning {\"commit\"[, \"tag\"]}. (${CodeGitPinRequired})"
        ))
      case Some(t) =>
        resolveTagCommit(url, t).flatMap {
          case Left(err) => IO.pure(Left(err))
          case Right(commit) =>
            installGitPinned(
              PluginSourceSpec.GitSpec(url, path = None, pin = PluginSourceSpec.GitPin(tag = Some(t), commit = commit))
            )
        }

  /** `git ls-remote` 解引用 tag → commit（优先 peeled `^{}`——annotated tag 的真 commit）。 */
  private def resolveTagCommit(url: String, tag: String): IO[Either[String, String]] =
    IO.blocking {
      try
        val res = os.proc("git", "ls-remote", url, s"refs/tags/$tag", s"refs/tags/$tag^{}").call(check = false)
        if res.exitCode != 0 then
          Left(s"git tag '$tag' could not be resolved on '$url' (exit ${res.exitCode}): ${errTail(res)} (${CodeGitTagNotFound})")
        else
          val lines = res.out.text().linesIterator.toList
          val peeled = lines.find(_.endsWith(s"refs/tags/$tag^{}")).map(_.takeWhile(_ != '\t'))
          val plain  = lines.find(_.endsWith(s"refs/tags/$tag")).map(_.takeWhile(_ != '\t'))
          peeled.orElse(plain).map(_.trim.toLowerCase).filter(_.matches("[0-9a-f]{40}")) match
            case Some(commit) => Right(commit)
            case None         => Left(s"git tag '$tag' not found on '$url' (${CodeGitTagNotFound})")
      catch case NonFatal(e) => Left(s"git ls-remote failed (${e.getClass.getSimpleName}: ${e.getMessage}) (${CodeGitTagNotFound})")
    }

  /**
   * spec 形态的 git 安装（pin 已定：commit 必填、tag 可缺省）。clone
   * `--filter=blob:none --no-checkout`（老 git 不认 filter ⇒ 退一次无 filter clone）→
   * checkout pin commit → tag 在 clone 内**复解引用比对**（ls-remote 与 clone 之间
   * tag 被移动的 TOCTOU 防线）→ 包树（仓内子目录或仓根，剔 `.git`——注册表扫描/digest
   * 面零仓元数据）→ 内容树 digest 现算落账 → 原子落盘。
   */
  def installGitPinned(spec: PluginSourceSpec.GitSpec): IO[Either[String, String]] =
    IO.blocking {
      val tmp = os.temp.dir(prefix = "nb-plugin-git-clone")
      try
        val repo = tmp / "repo"
        val cloneUrl = localFileUri(spec.url).getOrElse(spec.url)
        val withFilter = os.proc("git", "clone", "-q", "--filter=blob:none", "--no-checkout", cloneUrl, repo.toString).call(check = false)
        val cloned =
          if withFilter.exitCode == 0 then Right(())
          else
            val retry = os.proc("git", "clone", "-q", "--no-checkout", cloneUrl, repo.toString).call(check = false)
            if retry.exitCode == 0 then Right(())
            else Left(s"git clone failed (exit ${retry.exitCode}): ${errTail(retry)} (${CodeGitCloneFailed})")
        cloned.flatMap { _ =>
          val checkout = os.proc("git", "-C", repo, "checkout", "-q", spec.pin.commit).call(check = false)
          if checkout.exitCode != 0 then
            Left(s"git checkout ${spec.pin.commit.take(12)} failed: ${errTail(checkout)} (${CodeGitCheckoutFailed})")
          else if !tagDerefsTo(repo, spec.pin) then
            Left(
              s"git tag '${spec.pin.tag.getOrElse("")}' no longer dereferences to the pinned commit ${spec.pin.commit.take(12)}… " +
                s"— the tag was moved after the pin was made; refusing (design §2.2B) (${CodeGitTagMoved})"
            )
          else
            spec.path match
              case Some(rel) if !os.isDir(repo / os.SubPath(rel)) =>
                Left(s"git source path '$rel' is not a directory inside the repo at ${spec.pin.commit.take(12)}… (${CodeArchiveLayout})")
              case _ =>
                withStagingSync(spec.pin.commit.take(12)) { pkg =>
                  val root = spec.path match
                    case Some(rel) => repo / os.SubPath(rel)
                    case None      => repo
                  os.copy(root, pkg, createFolders = true, mergeFolders = true, replaceExisting = true)
                  try os.remove.all(pkg / ".git")
                  catch case NonFatal(_) => ()
                  PluginRegistry.computeDigest(pkg) match
                    case Right((digest, _)) =>
                      // 提交在暂存会话**内**完成（finally 兜底只清未提交残渣）
                      commitStaged(
                        pkg, digest, fallbackVersion = "",
                        kind = Kind.Git, via = Via.Git,
                        url = Some(spec.url),
                        pin = Some(Pin.Commit(spec.pin.tag, spec.pin.commit)),
                        sourceDesc = s"git ${spec.url} @ ${spec.pin.commit.take(12)}…"
                      )
                    case Left(err) => Left(s"digest computation failed on cloned package: $err (${CodeArchiveLayout})")
                }
        }
      finally try os.remove.all(tmp) catch case NonFatal(_) => ()
    }
  end installGitPinned

  /** clone 内 tag 复解引用（rev-list 剥 annotated 壳；commit-only pin 恒真——无 tag 可挪）。 */
  private def tagDerefsTo(repo: os.Path, pin: PluginSourceSpec.GitPin): Boolean =
    pin.tag match
      case None => true
      case Some(tag) =>
        try
          val res = os.proc("git", "-C", repo, "rev-list", "-n", "1", tag).call(check = false)
          res.exitCode == 0 && res.out.text().trim.equalsIgnoreCase(pin.commit)
        catch case NonFatal(_) => false

  /** 本地已存在路径 ⇒ file:// URI（partial clone 走 upload-pack；裸路径 clone 会忽略 filter）。 */
  private def localFileUri(url: String): Option[String] =
    try
      val p = os.Path(url, os.pwd)
      if os.exists(p) then Some(java.nio.file.Paths.get(p.toString).toUri.toString) else None
    catch case NonFatal(_) => None

  private def errTail(res: os.CommandResult): String =
    scala.util.Try(res.err.text()).toOption.getOrElse("").linesIterator.toList.takeRight(3).mkString("; ")

  // ── https 腿：直链归档 + 双值 ────────────────────────────────

  def installHttps(spec: PluginSourceSpec.HttpsSpec): IO[Either[String, String]] =
    fetchBytes(spec.archive).flatMap {
      case Left(reason) => IO.pure(Left(s"archive download failed — ${spec.archive} ($reason) (${CodeUnreachable})"))
      case Right(bytes) =>
        IO.blocking {
          withStagingSync("https") { pkg =>
            verifyAndStageZip(
              bytes, pkg,
              declaredSize = spec.sizeBytes,
              expectedSha256 = spec.sha256,
              expectedDigest = spec.digest
            ).flatMap { digest =>
              commitStaged(
                pkg, digest, fallbackVersion = "",
                kind = Kind.Https, via = Via.Https,
                url = Some(spec.archive), pin = None,
                sourceDesc = s"direct archive ${spec.archive}"
              )
            }
          }
        }
    }
  end installHttps

  // ── 暂存（plugins/ 之外 ⇒ 半装态零扫描面）与原子落盘 ──────────────

  /**
   * 暂存会话（同步实现；official/https 腿包在 IO.blocking、git 腿直调）：`<dataRoot>/
   * .plugin-staging/<label>-<ts>-<rnd>/pkg`。半装态永不出现在 `plugins/` 扫描面
   * （PluginRegistry 只扫 plugins/）；落地 = 单次 `os.move`（同分区 rename，§4.2
   * 「Staged → Installed: 空位 ⇒ 原子 rename 落地」）；finally 清理会话——任何失败
   * 路径（校验拒装 / 中断）都不留半包。进程被杀的残留会话由 [[sweepStaleStaging]]
   * 兜底（>24h 清除，best-effort）。
   */
  private def withStagingSync[A](label: String)(body: os.Path => Either[String, A]): Either[String, A] =
    sweepStaleStaging()
    val session = stagingRoot / s"$label-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString.take(8)}"
    os.makeDir.all(session)
    try body(session / "pkg")
    finally try os.remove.all(session)
    catch case NonFatal(_) => ()

  private def sweepStaleStaging(): Unit =
    try
      if os.isDir(stagingRoot) then
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        os.list(stagingRoot).foreach { p =>
          if os.mtime(p) < cutoff then
            try os.remove.all(p)
            catch case NonFatal(_) => ()
        }
    catch case NonFatal(_) => ()

  /**
   * 原子落盘 + 落账（三态共用的收口；「同名已存在即拒」= 1274-1281 现状口径逐字保持——
   * 覆盖/备份属 P3 更新流）。步骤：staged manifest 校验（name 过 §5.5）→ 空位检查 →
   * makeDir plugins → `os.move` 原子 rename → 复职（清 removed 墓碑，卸载批语义）→
   * 缓存失效 → provenance 落账（best-effort：写失败仅 WARN 不回滚——在位即信任零变化）
   * → 成功文案。
   */
  private def commitStaged(
      pkg: os.Path,
      digest: String,
      fallbackVersion: String,
      kind: Kind,
      via: Via,
      url: Option[String],
      pin: Option[Pin],
      sourceDesc: String
  ): Either[String, String] =
    val manifestFile = pkg / "plugin.json"
    if !os.isFile(manifestFile) then Left(s"staged package has no plugin.json manifest — not a plugin package (${CodeArchiveLayout})")
    else
      io.circe.parser.parse(os.read(manifestFile)) match
        case Left(err) => Left(s"staged plugin.json unparseable: ${err.message} (${CodeArchiveLayout})")
        case Right(json) =>
          val name = json.hcursor.downField("name").as[String].toOption.map(_.trim).filter(_.nonEmpty)
          name.filter(PluginRegistry.validPluginName) match
            case None => Left("staged manifest 'name' missing or violates §5.5 constraints — refusing to install")
            case Some(pname) =>
              // 版本 = **解析后的发布版本**优先（A8② 契约：账本记「用户选的那一版」）；
              // 发布清单缺席该字段（git/https 腿）时回落包内 manifest 自报版本。
              val stagedVersion = json.hcursor.downField("version").as[String].toOption.map(_.trim).filter(_.nonEmpty)
              val version = if fallbackVersion.nonEmpty then fallbackVersion else stagedVersion.getOrElse("")
              val target = pluginsDir / pname
              if os.exists(target) then
                Left(s"Plugin '$pname' already exists at $target — refusing to overwrite (remove it first)")
              else
                val moved: Either[String, Unit] =
                  try
                    os.makeDir.all(pluginsDir)
                    os.move(pkg, target) // 无 replaceExisting：空位检查后的竞态也由「目标已存在即失败」兜住
                    Right(())
                  catch
                    case _: java.nio.file.FileAlreadyExistsException =>
                      Left(s"Plugin '$pname' already exists at $target — refusing to overwrite (remove it first)")
                    case NonFatal(e) =>
                      Left(s"atomic move of staged package failed (${e.getClass.getSimpleName}: ${e.getMessage})")
                moved.flatMap { _ =>
                  // 显式重装 = 显式复职（卸载批 2026-10-29 语义；从未卸载过 = no-op）
                  PluginRemovePolicy.reinstate(pname)
                  PluginRegistry.invalidateCache()
                  PluginSourceLedger.recordInstall(
                    name = pname, kind = kind, url = url, pin = pin,
                    version = version, digest = digest, via = via
                  ) match
                    case Right(_) =>
                      logger.infoSync(
                        s"[plugin-sources] '$pname' installed from $sourceDesc — via=${via.label} " +
                          s"digest=${digest.take(12)}… (presence = trust; provenance recorded)"
                      )
                    case Left(err) =>
                      logger.warnSync(
                        s"[plugin-sources] provenance recording failed for '$pname' ($err) — the package stays installed " +
                          "(presence = trust); its source query degrades to unknown-source"
                      )
                  Right(
                    s"Plugin '$pname'${if version.isEmpty then "" else s" (v$version)"} installed to $target from $sourceDesc — " +
                      "active on the next scan (presence = trust: no approval step). Provenance recorded " +
                      s"(via=${via.label}, digest ${digest.take(12)}…). " +
                      s"Block it if intended: Plugin panel, REST POST /api/plugins/$pname/revoke, or CLI 'stand plugin revoke $pname'."
                  )
                }
          end match
      end match
    end if
  end commitStaged

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

end PluginSourceInstall
