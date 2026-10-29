package nebflow.core.plugin

import io.circe.{HCursor, Json}

/**
 * PluginSourceSpec —— 三态源引用模型（远程发布源设计稿 §2.2，P1 源引用批类型面）。
 *
 * 三态（§2.2 逐字）：
 *  - `official`：官方源——用户侧只需要「包 id」（官方索引定位版本清单；index 缺省 = 默认
 *    官方索引，由 P2 的读取面按 Branding 单源拼装，本件不猜）；
 *  - `git`：git 仓 + pin（tag/commit）——**pin 必填，禁浮动 HEAD**（浮动 pin 让「同一来源
 *    两次安装得到不同内容」合法化，直接击穿 digest 信任面，§2.2B 原文）；commit 必填
 *    （40-hex，tag 可缺省——「只 pin commit 时允许」，§2.2B）；
 *  - `https`：直链归档 + sha256（归档字节摘要；digest/sizeBytes 可选）。
 *
 * 本件是**纯类型面 + 纯解析器**：零网络、零 IO、零文件系统访问（P1 批边界）。解析产物
 * （[[Spec]]）是 P2 拉取式安装的输入——P2 的 fetcher 按态出网，本件只回答「这份 spec
 * 写得对不对」。错误走稳定错误码（`SPEC_` 前缀常量，同 `OFFICIAL_IMPERSONATION` 的
 * 命名纪律），不抛异常。
 *
 * 容忍度口径（对齐既有 ReleaseManifest 先例，VersionCheck.scala:130-171）：未知字段
 * 宽容忽略（前向兼容——P2 扩展/P3 history 不破旧解析器）；已知字段类型违规即拒（Left）。
 * spec 是安装输入，错就早错。
 */
object PluginSourceSpec:

  // ── 错误码（稳定常量；🔴 禁改名——CLI/REST/测试逐字断言）────────────────

  val CodeUnparseable          = "SPEC_UNPARSEABLE"            // 不是合法 JSON
  val CodeNotObject            = "SPEC_NOT_OBJECT"             // 顶层不是 object
  val CodeKindMissing          = "SPEC_KIND_MISSING"           // kind 缺失/非字符串
  val CodeKindUnknown          = "SPEC_KIND_UNKNOWN"           // kind 不在三态内
  val CodeOfficialIdMissing    = "SPEC_OFFICIAL_ID_MISSING"    // official: id 缺失/空白
  val CodeOfficialIdInvalid    = "SPEC_OFFICIAL_ID_INVALID"    // official: id 违反 §5.5 名约束
  val CodeOfficialIndexInvalid = "SPEC_OFFICIAL_INDEX_INVALID" // official: index 缺失形态违规/非 http(s)
  val CodeGitUrlMissing        = "SPEC_GIT_URL_MISSING"        // git: url 缺失/空白
  val CodeGitUrlInvalid        = "SPEC_GIT_URL_INVALID"        // git: url 不像 git remote
  val CodePathInvalid          = "SPEC_PATH_INVALID"           // git: path 非仓内相对子目录
  val CodePinMissing           = "SPEC_PIN_MISSING"            // git: pin 缺失/非 object（禁浮动 HEAD）
  val CodePinCommitInvalid     = "SPEC_PIN_COMMIT_INVALID"     // git: pin.commit 缺失/非 40-hex
  val CodePinTagInvalid        = "SPEC_PIN_TAG_INVALID"        // git: pin.tag 空白/非字符串
  val CodeArchiveMissing       = "SPEC_ARCHIVE_MISSING"        // https: archive 缺失/空白
  val CodeArchiveInvalid       = "SPEC_ARCHIVE_INVALID"        // https: archive 非 http(s) URL
  val CodeSha256Missing        = "SPEC_SHA256_MISSING"         // https: sha256 缺失/空白
  val CodeSha256Invalid        = "SPEC_SHA256_INVALID"         // https: sha256 非 64-hex
  val CodeDigestInvalid        = "SPEC_DIGEST_INVALID"         // https: digest 非 64-hex
  val CodeSizeInvalid          = "SPEC_SIZE_INVALID"           // https: sizeBytes 非非负整数

  /** 解析错误：稳定错误码 + 人读原因（同官方闸「只产原因字符串」纪律，不抛异常）。 */
  final case class Error(code: String, message: String):
    def render: String = s"$code: $message"

  // ── 类型面 ────────────────────────────────────────────────

  enum Kind(val label: String):
    case Official extends Kind("official")
    case Git extends Kind("git")
    case Https extends Kind("https")

  /**
   * git pin（§2.2B）：commit 必填（40-hex；tag 被移动的防线 = 安装时解引用比对，P2 落），
   * tag 可缺省（「只 pin commit 时允许」）。commit 归一为小写 hex。
   */
  final case class GitPin(tag: Option[String], commit: String)

  sealed trait Spec:
    def kind: Kind

  /** A. 官方源：index 缺省 = 默认官方索引（P2 按 Branding 单源拼装，本件不猜）。 */
  final case class OfficialSpec(index: Option[String], id: String) extends Spec:
    val kind = Kind.Official

  /** B. git 源：path = 仓内子目录（缺省 = 仓根，§2.2B）。 */
  final case class GitSpec(url: String, path: Option[String], pin: GitPin) extends Spec:
    val kind = Kind.Git

  /** C. 直链归档：sha256 = 归档字节摘要；digest = 内容树摘要（装机现算比对值，可缺省）。 */
  final case class HttpsSpec(archive: String, sha256: String, sizeBytes: Option[Long], digest: Option[String])
      extends Spec:
    val kind = Kind.Https

  // ── 解析器（纯函数；零网络零 IO）──────────────────────────

  /** 从 JSON 文本解析（CLI / `--from spec.json` 入口形态）。 */
  def parse(raw: String): Either[Error, Spec] =
    io.circe.parser.parse(raw) match
      case Left(err) => Left(Error(CodeUnparseable, s"source spec is not valid JSON (${err.message})"))
      case Right(json) => parse(json)

  /** 从已解析 JSON 解析（REST body 形态）。 */
  def parse(json: Json): Either[Error, Spec] =
    if !json.isObject then Left(Error(CodeNotObject, "source spec must be a JSON object"))
    else
      val c = json.hcursor
      c.downField("kind").as[String] match
        case Left(_) =>
          Left(Error(CodeKindMissing, "source spec is missing the required string field 'kind' (official | git | https)"))
        case Right(rawKind) =>
          rawKind.trim.toLowerCase match
            case "official" => parseOfficial(c)
            case "git"      => parseGit(c)
            case "https"    => parseHttps(c)
            case other =>
              Left(Error(CodeKindUnknown, s"source spec kind '$other' is not one of: official | git | https"))

  private def parseOfficial(c: HCursor): Either[Error, Spec] =
    for
      id <- requiredString(c, "id", CodeOfficialIdMissing, "official source needs the package id (the official index id)")
      _ <- ensure(
        PluginRegistry.validPluginName(id),
        CodeOfficialIdInvalid,
        s"official id '$id' violates the §5.5 plugin name constraints — the index id must be an installable package name"
      )
      index <- optionalString(c, "index", CodeOfficialIndexInvalid, "official source field 'index' must be a string URL when present")
      _ <- ensure(
        index.forall(isHttpUrl),
        CodeOfficialIndexInvalid,
        s"official source field 'index' must be an http(s) URL${index.getOrElse("")}"
      )
    yield OfficialSpec(index, id)

  private def parseGit(c: HCursor): Either[Error, Spec] =
    for
      url <- requiredString(c, "url", CodeGitUrlMissing, "git source needs the repository URL")
      _ <- ensure(
        isGitUrl(url),
        CodeGitUrlInvalid,
        s"git source field 'url' does not look like a git remote ('$url') — accepted: https://… | http://… | git@… | ….git"
      )
      path <- optionalString(c, "path", CodePathInvalid, "git source field 'path' must be a string when present")
      _ <- ensure(
        isRepoRelativePath(path),
        CodePathInvalid,
        s"git source field 'path' must be a repo-relative subdirectory (no absolute path, no '..' segments)${path.getOrElse("")}"
      )
      pinJson <- c.downField("pin").focus match
        case None | Some(Json.Null) =>
          Left(
            Error(
              CodePinMissing,
              "git source is missing 'pin' — floating HEAD is forbidden (design §2.2B: a pin must resolve to an " +
                "immutable commit, otherwise the same source installs different content on different days)"
            )
          )
        case Some(p) => Right(p)
      pin <- parsePin(pinJson)
    yield GitSpec(url, path, pin)

  private def parsePin(p: Json): Either[Error, GitPin] =
    if !p.isObject then Left(Error(CodePinMissing, "git source field 'pin' must be an object ({\"commit\"[, \"tag\"]})"))
    else
      val pc = p.hcursor
      for
        commit <- requiredString(
          pc,
          "commit",
          CodePinCommitInvalid,
          "pin.commit is required and must be a 40-hex git commit (SHA-1)"
        )
        _ <- ensure(
          isHex(commit, 40),
          CodePinCommitInvalid,
          s"pin.commit must be a 40-hex git commit (SHA-1), got '${commit.take(12)}…'"
        )
        tag <- optionalString(pc, "tag", CodePinTagInvalid, "pin.tag must be a string when present")
        _ <- ensure(tag.forall(_.nonEmpty), CodePinTagInvalid, "pin.tag must be non-empty when present")
      yield GitPin(tag, commit.toLowerCase)

  private def parseHttps(c: HCursor): Either[Error, Spec] =
    for
      archive <- requiredString(c, "archive", CodeArchiveMissing, "https source needs the archive URL")
      _ <- ensure(isHttpUrl(archive), CodeArchiveInvalid, s"https source field 'archive' must be an http(s) URL ('$archive')")
      sha <- requiredString(c, "sha256", CodeSha256Missing, "https source needs the archive-bytes sha256 (64 hex)")
      _ <- ensure(isHex(sha, 64), CodeSha256Invalid, s"sha256 must be 64 hex, got '${sha.take(12)}…'")
      digest <- optionalString(c, "digest", CodeDigestInvalid, "field 'digest' must be a string when present")
      _ <- ensure(
        digest.forall(isHex(_, 64)),
        CodeDigestInvalid,
        s"field 'digest' (content-tree digest) must be 64 hex${digest.getOrElse("")}"
      )
      // sizeBytes 必须**真数字**：circe 的 Long decoder 会把 JSON 字符串 "81234" 宽容转型，
      // 这里显式挡回（类型违规 = SPEC_SIZE_INVALID）；非整数（81.5）与负数同拒。
      sizeJson = c.downField("sizeBytes").focus
      size = sizeJson.flatMap(_.asNumber.flatMap(_.toLong))
      _ <- sizeJson match
        case None | Some(Json.Null) => Right(())
        case Some(v) =>
          ensure(
            v.isNumber && v.asString.isEmpty && size.exists(_ >= 0),
            CodeSizeInvalid,
            s"field 'sizeBytes' must be a non-negative integer, got: $v"
          )
    yield HttpsSpec(archive, sha.toLowerCase, size.filter(_ >= 0), digest.map(_.toLowerCase))

  // ── 校验助手（同族判定供 P2 fetcher 复用；public 只读面）────────────────

  /**
   * git remote 形态判定 —— 与 `PluginRegistry.installFromSync` 的既有 isGit 判据逐字同域
   * （https:// | http:// | git@ | *.git）。单一判域，禁第二套。
   */
  def isGitUrl(s: String): Boolean =
    s.startsWith("https://") || s.startsWith("http://") || s.startsWith("git@") || s.endsWith(".git")

  /** 仓内相对子目录：非绝对、无 `..`/`.`/空段、无反斜杠。 */
  def isRepoRelativePath(p: Option[String]): Boolean = p match
    case None => true
    case Some(v) =>
      !v.startsWith("/") && !v.contains("\\") &&
        v.split('/').forall(seg => seg.nonEmpty && seg != "." && seg != "..")

  def isHttpUrl(s: String): Boolean =
    (s.startsWith("https://") || s.startsWith("http://")) &&
      s.stripPrefix("https://").stripPrefix("http://").nonEmpty

  private def isHex(s: String, len: Int): Boolean =
    s.length == len && s.forall(c => c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))

  private def requiredString(c: HCursor, field: String, code: String, hint: String): Either[Error, String] =
    c.downField(field).as[Option[String]] match
      case Left(_)      => Left(Error(code, s"source spec field '$field' must be a string ($hint)"))
      case Right(None)  => Left(Error(code, s"source spec is missing the required field '$field' ($hint)"))
      case Right(Some(v)) =>
        val t = v.trim
        if t.isEmpty then Left(Error(code, s"source spec field '$field' must be non-empty ($hint)"))
        else Right(t)

  /**
   * 可选 string 字段：**缺席 = None；在场 = 保留 trim 后原值**（含空白串）——「在场但
   * 空白」是否合法由各字段的校验条款判定（blank index/digest 违反 URL/hex 判据、blank
   * tag 由 pin 的显式 ensure 拒绝），本助手不代行取舍（否则「present 但无效」会被静默
   * 洗成 absent，类型违规面失明）。
   */
  private def optionalString(c: HCursor, field: String, code: String, hint: String): Either[Error, Option[String]] =
    c.downField(field).as[Option[String]] match
      case Left(_)  => Left(Error(code, hint))
      case Right(o) => Right(o.map(_.trim))

  private def ensure(cond: Boolean, code: String, message: String): Either[Error, Unit] =
    if cond then Right(()) else Left(Error(code, message))

end PluginSourceSpec
