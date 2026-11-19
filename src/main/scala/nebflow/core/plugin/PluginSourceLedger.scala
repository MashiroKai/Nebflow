package nebflow.core.plugin

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * PluginSourceLedger —— provenance 账本（远程发布源设计稿 §2.1/§2.4，P1 源引用批；
 * 插件分发与生命周期协议升级草案 §5.4 + §12.1 schema 2，A 期 §12.7）。
 *
 * 权威来源层 = `<dataRoot>/plugin-sources/<name>.json`（单文件单包；`plugins/` 的兄弟目录，
 * **包目录之外**）。设计公理一「provenance 不入包」：digest 覆盖包目录全部字节（盘点 §1.3），
 * 来源写进包内 = 改 digest = 自毁信任面。**硬不变量（spec 钉死）**：安装/回填/账本写全部
 * 不得改变包 digest——先装 → 记账 → 重算 digest 前后相等。
 *
 * 字段（schema 2 = §5.4 地基字段 + §12.1 分发信任面；**复用既有槽位不造重复字段**：
 * contentDigest→`digest`、resolvedVersion→`version`、sourceCommit→`pin.commit`）：
 * `{schema, name, kind, url?, pin?, version, digest, installedAt, via,`
 * ` origin, trustLevel, distributionOwner, updatePolicy, pinned,`
 * ` distributionId?, publisherKeyId?, artifactSha256?, validationSummary?, transactionId?,`
 * ` previousGeneration?, publisherClass?, hubReviewId?, submissionStatus?, lastCheckedAt?}`
 *  - `kind` ∈ official | git | https | local；`via` ∈ seed | official | git | https | local；
 *  - `origin`/`trustLevel`/`distributionOwner`（§12.1 三轴）+ `updatePolicy`/`pinned`
 *    （作者所有）为必位字段（缺位 = 按 via 迁移映射/默认升档，见下）；
 *  - `?` 字段**缺席 ≠ null**：None 序列化时整体不落（预留空值族纪律）；
 *  - 读端对未知字段**宽容忽略**（前向兼容），对已知字段违规（类型/闭合枚举外取值）
 *    降级 unknown-source 并指名字段。
 *
 * **schema 1 → 2 兼容升档**（红绿①）：存量文件照常装载，§12.1/§5.4 缺位字段按
 * [[PluginDistributionTrust.factsForLegacyVia]]（via → 分发面迁移映射，**单一事实源，
 * 禁两套映射**）与默认（updatePolicy=check、pinned=false、可空族 None）补齐。
 *
 * **作者所有字段跨世代保留**（§12.1 唯一所有者）：update 腿重记（[[recordInstall]] 二次
 * 调用）不冲掉 distributionOwner / updatePolicy / pinned / hub 三字段与 previousGeneration
 * ——分发所有者由作者显式切换，不因更新事件隐式转移；origin/trustLevel 是本世代字节的
 * 安装事件事实，随事件重记。
 *
 * 写面：引擎唯一写点 = 安装路径（install 腿 + update 腿 + [[backfill]] 回填）。落账
 * best-effort：账本写失败**不回滚安装**（在位即信任零变化），该包查询面降级
 * unknown-source 并 WARN 留痕。回填 = boot 后首查一次、幂等、已记账零改写、绝不触包
 * 目录字节。
 *
 * 读面：内部查询 API（[[query]] / [[queryAll]]）。缺账本/账本损坏 ⇒ **明确
 * unknown-source，不猜**（绝不因包名前缀或目录形态反推来源）；单包账本坏 = 该包
 * unknown-source，不影响他包（单文件单包的天然隔离）。
 *
 * 审计：每次账本落一行审计（安装流现役审计族同款 logger 行；回填落一行汇总，不在 boot
 * 刷 23 行）。
 */
object PluginSourceLedger:

  private val logger = NebflowLogger.forName("nebflow.plugin.sources")

  import PluginDistributionTrust.*

  /** 账本 schema 版本（§2.4 同款演进位：读端未知字段宽容、缺字段降级不失败；1 存量兼容升档）。 */
  val Schema = 2

  private val Hex40 = "[0-9a-fA-F]{40}"
  private val Hex64 = "[0-9a-fA-F]{64}"

  // ── 类型面 ────────────────────────────────────────────────

  enum Kind(val label: String):
    case Official extends Kind("official")
    case Git extends Kind("git")
    case Https extends Kind("https")
    case Catalog extends Kind("catalog")

    /** 本地目录拷贝（开发态安装）——非三态 spec 形态：无「重取」语义。 */
    case Local extends Kind("local")

  object Kind:
    def fromLabel(s: String): Option[Kind] = values.find(_.label == s)

  enum Via(val label: String):
    case Seed extends Via("seed")
    case Official extends Via("official")
    case Git extends Via("git")
    /** 直链归档安装（P2 https 腿；additive——旧读端遇该值按闭合枚举降级 unknown-source，诚实不猜）。 */
    case Https extends Via("https")
    case Hub extends Via("hub")
    case Local extends Via("local")

  object Via:
    def fromLabel(s: String): Option[Via] = values.find(_.label == s)

  /** pin 载荷（两形态）：git 源 = commit/tag pin；seed 回填 = digest 基线 pin。 */
  enum Pin:
    case Commit(tag: Option[String], commit: String)
    case DigestBasis(digest: String)

    /** 投影用（REST/CLI 读面；与写面 pinJson 同形——单一序列化形态）。 */
    def toJson: io.circe.Json = this match
      case Commit(tag, commit) =>
        val o = JsonObject.empty.add("commit", commit.asJson)
        Json.fromJsonObject(tag.fold(o)(t => o.add("tag", t.asJson)))
      case DigestBasis(digest) => Json.obj("digest" -> digest.asJson)

  /** 世代切换历史（§5.4；P3 update 腿追加，A 期只做读往返不写）。 */
  final case class PreviousGeneration(version: String, digest: String, switchedAt: Long)

  /**
   * 安装/更新腿的 §5.4 A 期地基字段载荷（recordInstall 的 additive 第八参，缺省全
   * None——既有 7 参调用点不破）。逐事件字段：描述本次落盘世代，不跨世代保留。
   */
  final case class InstallMeta(
      distributionId: Option[String] = None,
      publisherKeyId: Option[String] = None,
      artifactSha256: Option[String] = None,
      validationSummary: Option[String] = None,
      transactionId: Option[String] = None
  )

  /**
   * 单包账本条目（字段表见类注释；`?` 形字段缺席 ≠ null，不出现在 JSON 里）。
   * `distribution`（§12.1 分发面）与 `updatePolicy`/`pinned`（作者所有）见
   * [[PluginDistributionTrust]]——作者所有字段跨世代保留，事件字段随重记更新。
   */
  final case class Entry(
      name: String,
      kind: Kind,
      url: Option[String],
      pin: Option[Pin],
      version: String,
      digest: String,
      installedAt: Long,
      via: Via,
      distribution: Distribution,
      updatePolicy: UpdatePolicy,
      pinned: Boolean,
      distributionId: Option[String],
      publisherKeyId: Option[String],
      artifactSha256: Option[String],
      validationSummary: Option[String],
      transactionId: Option[String],
      previousGeneration: Option[PreviousGeneration]
  )

  /** 查询结果：Known = 有账本；UnknownSource = 明确「来源不可知」（缺账本/损坏/名实不符）。 */
  sealed trait Query
  final case class Known(entry: Entry) extends Query
  final case class UnknownSource(reason: String) extends Query

  // ── 路径 ──────────────────────────────────────────────────

  /**
   * 账本根目录：`<dataRoot>/plugin-sources/`（`plugins/` 的兄弟目录）。def 而非 val：
   * dataRoot 可被 setDataRoot/--home 换根（与 PluginRegistry.pluginsDir 同纪律）。
   */
  def ledgerDir: os.Path = PathUtil.dataRoot / "plugin-sources"

  private def entryPath(name: String): os.Path = ledgerDir / s"$name.json"

  // ── 写面（引擎唯一写点；调用点 = install/update 腿 + 回填）────────────────

  /**
   * 安装/更新腿的账本落账（同步实现——调用点在 `IO.blocking` 内，installFromSync 同款）。
   * 原子写（AtomicJson tmp+rename，crash-safe）；成功落一行审计。
   *
   * §12.1 作者所有字段跨世代保留：同包已有有效账本 ⇒ distributionOwner / updatePolicy /
   * pinned / hub 三字段 / previousGeneration 沿用旧值（不因更新腿重记隐式转移或回落默认）；
   * origin / trustLevel 与 §5.4 事件字段（version/digest/url/pin/meta）随本次事件重记。
   */
  def recordInstall(
      name: String,
      kind: Kind,
      url: Option[String],
      pin: Option[Pin],
      version: String,
      digest: String,
      via: Via,
      meta: InstallMeta = InstallMeta(),
      publisherClass: Option[PublisherClass] = None
  ): Either[String, Entry] =
    if !PluginRegistry.validPluginName(name) then Left(s"invalid plugin name '$name' — ledger entry refused")
    else if digest.trim.isEmpty then Left(s"empty digest for '$name' — ledger entry refused")
    else
      val facts = factsForLegacyVia(via)
      val prior = priorEntry(name) // 已有账本损坏 ⇒ 视同无先账（作者所有面回落默认，WARN 已由 query 面留痕）
      val entry = Entry(
        name = name,
        kind = kind,
        url = url.map(_.trim).filter(_.nonEmpty),
        pin = pin,
        version = version,
        digest = digest.trim.toLowerCase,
        installedAt = System.currentTimeMillis() / 1000L,
        via = via,
        distribution = Distribution(
          origin = facts.origin, // 本世代字节的安装事件事实（随事件重记）
          trustLevel = facts.trustLevel,
          distributionOwner = prior.map(_.distribution.distributionOwner).getOrElse(facts.distributionOwner),
          publisherClass = publisherClass,
          hubReviewId = prior.flatMap(_.distribution.hubReviewId),
          submissionStatus = prior.flatMap(_.distribution.submissionStatus),
          lastCheckedAt = prior.flatMap(_.distribution.lastCheckedAt)
        ),
        updatePolicy = prior.map(_.updatePolicy).getOrElse(UpdatePolicy.Check),
        pinned = prior.map(_.pinned).getOrElse(false),
        distributionId = meta.distributionId,
        publisherKeyId = meta.publisherKeyId,
        artifactSha256 = meta.artifactSha256,
        validationSummary = meta.validationSummary,
        transactionId = meta.transactionId,
        previousGeneration = prior.flatMap(_.previousGeneration)
      )
      writeEntry(entry)
      Right(entry)

  /** 重记前的同包先账（名实不符/损坏 ⇒ None——不猜、不沿用可疑状态）。 */
  private def priorEntry(name: String): Option[Entry] =
    val path = entryPath(name)
    if !os.exists(path) then None
    else
      readEntry(path) match
        case Right(e) if e.name == name => Some(e)
        case _                          => None

  /** 唯一的字节写实现（recordInstall 与 backfill 共用；原子替换 + 审计行）。 */
  private def writeEntry(entry: Entry): Unit =
    AtomicJson.writeSync(entryPath(entry.name), entryJson(entry).noSpaces)
    logger.infoSync(
      s"[plugin-sources] '${entry.name}' provenance recorded — via=${entry.via.label} kind=${entry.kind.label} " +
        s"digest=${entry.digest.take(12)}… (engine-only provenance write point)"
    )

  private def entryJson(e: Entry): Json =
    val base = JsonObject.empty
      .add("schema", Schema.asJson)
      .add("name", e.name.asJson)
      .add("kind", e.kind.label.asJson)
      .add("version", e.version.asJson)
      .add("digest", e.digest.asJson)
      .add("installedAt", e.installedAt.asJson)
      .add("via", e.via.label.asJson)
      .add("origin", e.distribution.origin.label.asJson)
      .add("trustLevel", e.distribution.trustLevel.label.asJson)
      .add("distributionOwner", e.distribution.distributionOwner.label.asJson)
      .add("updatePolicy", e.updatePolicy.label.asJson)
      .add("pinned", e.pinned.asJson)
    val withUrl = e.url.fold(base)(u => base.add("url", u.asJson))
    val withPin = e.pin.fold(withUrl)(p => withUrl.add("pin", pinJson(p)))
    // None ⇒ 缺席不落（缺席 ≠ null）；Some ⇒ 落字符串（§12.1 预留空值族 + §5.4 A 期地基字段）
    val optionalStrings = List(
      "distributionId" -> e.distributionId,
      "publisherKeyId" -> e.publisherKeyId,
      "artifactSha256" -> e.artifactSha256,
      "validationSummary" -> e.validationSummary,
      "transactionId" -> e.transactionId,
      "hubReviewId" -> e.distribution.hubReviewId
    )
    val withOpt = optionalStrings.foldLeft(withPin)((o, kv) => kv._2.fold(o)(x => o.add(kv._1, x.asJson)))
    val optionalEnums = List(
      e.distribution.publisherClass.map(p => "publisherClass" -> p.label.asJson),
      e.distribution.submissionStatus.map(s => "submissionStatus" -> s.label.asJson)
    ).flatten
    val withEnums = optionalEnums.foldLeft(withOpt)((o, kv) => o.add(kv._1, kv._2))
    val withTime = e.distribution.lastCheckedAt.fold(withEnums)(t => withEnums.add("lastCheckedAt", t.asJson))
    Json.fromJsonObject(e.previousGeneration.fold(withTime)(g => withTime.add("previousGeneration", Json.obj(
      "version" -> g.version.asJson, "digest" -> g.digest.asJson, "switchedAt" -> g.switchedAt.asJson
    ))))

  private def pinJson(p: Pin): Json = p match
    case Pin.Commit(tag, commit) =>
      val o = JsonObject.empty.add("commit", commit.asJson)
      Json.fromJsonObject(tag.fold(o)(t => o.add("tag", t.asJson)))
    case Pin.DigestBasis(digest) => Json.obj("digest" -> digest.asJson)

  // ── 读面（内部查询 API；REST 投影留 P3）────────────────────

  /** 单包查询（同步）。缺账本/损坏/名实不符 ⇒ UnknownSource（明确不猜）。 */
  def query(name: String): Query =
    if !PluginRegistry.validPluginName(name) then UnknownSource(s"invalid plugin name '$name'")
    else
      val path = entryPath(name)
      if !os.exists(path) then
        UnknownSource(
          s"no provenance ledger entry for '$name' (unknown-source — installed before source recording, " +
            "or its ledger write failed; provenance is never guessed)"
        )
      else
        readEntry(path) match
          case Right(entry) if entry.name == name => Known(entry)
          case Right(entry) => degrade(name, s"ledger entry declares name '${entry.name}' but the file is '$name.json'")
          case Left(reason) => degrade(name, reason)

  /** 逐包查询（面板一次拿全的内部形态；投影端点 P3）。 */
  def queryAll(names: List[String]): Map[String, Query] =
    names.map(n => n -> query(n)).toMap

  /** 降级出口：损坏账本 = 该包 unknown-source + WARN（其他包零影响）。 */
  private def degrade(name: String, reason: String): UnknownSource =
    logger.warnSync(
      s"[plugin-sources] ledger entry for '$name' unusable ($reason) — this package degrades to unknown-source (other packages unaffected)"
    )
    UnknownSource(reason)

  /** 单文件解析：任何字段级违规 = corrupt（降级 unknown-source），绝不抛出。 */
  private def readEntry(path: os.Path): Either[String, Entry] =
    try
      io.circe.parser.parse(os.read(path)) match
        case Left(err)    => Left(s"ledger file unparseable: ${err.message}")
        case Right(json)  => decodeEntry(json)
    catch case NonFatal(e) => Left(s"ledger file unreadable (${e.getClass.getSimpleName}: ${e.getMessage})")

  /**
   * 条目解码（schema 2；schema 1 存量兼容升档）。**未知字段宽容忽略**（P3 history 等
   * 后续扩展不破旧读端）；已知字段缺失/类型违规/枚举外取值 = Left（corrupt）。`via`/
   * `kind`/`origin`/`trustLevel`/`distributionOwner`/`updatePolicy`/`submissionStatus`/
   * `publisherClass` 是闭合枚举（任务字段表逐字），枚举外取值按损坏处置并指名——这是
   * 「读出 honest 未知」而不是猜一个近似值。§12.1/§5.4 缺位字段按 via 迁移映射
   * （[[PluginDistributionTrust.factsForLegacyVia]]）与默认补齐。
   */
  private def decodeEntry(json: Json): Either[String, Entry] =
    if !json.isObject then Left("ledger entry must be a JSON object")
    else
      val c = json.hcursor
      for
        _ <- c.downField("schema").focus match
          case None | Some(Json.Null) => Right(()) // 无 schema 历史中间态：视作 1 升档，宽容不炸
          case Some(j) =>
            j.as[Int].left.map(_ => "field 'schema' must be an integer").flatMap {
              case 1 | 2  => Right(())
              case other  => Left(s"unknown ledger schema $other (expected: 1 | 2)")
            }
        name <- requiredStr(c, "name")
        kind <- requiredStr(c, "kind").flatMap(l => Kind.fromLabel(l).toRight(s"unknown ledger kind '$l' (expected: official | git | https | local)"))
        via <- requiredStr(c, "via").flatMap(l => Via.fromLabel(l).toRight(s"unknown ledger via '$l' (expected: seed | official | git | https | local)"))
        version <- requiredStr(c, "version")
        digest <- requiredStr(c, "digest")
        _ <- Either.cond(digest.matches(Hex64), (), s"digest must be 64-hex, got '${digest.take(12)}…'")
        installedAt <- c.downField("installedAt").as[Long].left.map(_ => "installedAt must be an epoch-seconds integer")
        url = c.downField("url").as[Option[String]].toOption.flatten.map(_.trim).filter(_.nonEmpty)
        pin <- decodePin(json)
        // §12.1 三轴 + 作者所有两位（闭合枚举；缺位 ⇒ via 迁移映射/默认升档）
        origin <- optionalLabel(c, "origin", Origin.fromLabel, "seed | hub | local-import | git-pinned")
        trustLevel <- optionalLabel(c, "trustLevel", TrustLevel.fromLabel, "official-verified | catalog-signed | local-import | unknown")
        owner <- optionalLabel(c, "distributionOwner", DistributionOwner.fromLabel, "seed | hub | local")
        policy <- optionalLabel(c, "updatePolicy", UpdatePolicy.fromLabel, "check | minor | all")
        pinned <- c.downField("pinned").focus match
          case None | Some(Json.Null) => Right(false)
          case Some(j)                => j.asBoolean.toRight("field 'pinned' must be a boolean")
        // §12.1 预留空值族 + §5.4 A 期地基字段（缺席 ≠ null）
        publisherClass <- optionalLabel(c, "publisherClass", PublisherClass.fromLabel, "official | verified | community")
        submissionStatus <- optionalLabel(c, "submissionStatus", SubmissionStatus.fromLabel, "published | pending-review | rejected")
        hubReviewId <- optionalStr(c, "hubReviewId")
        distributionId <- optionalStr(c, "distributionId")
        publisherKeyId <- optionalStr(c, "publisherKeyId")
        validationSummary <- optionalStr(c, "validationSummary")
        transactionId <- optionalStr(c, "transactionId")
        lastCheckedAt <- c.downField("lastCheckedAt").as[Option[Long]].left.map(_ => "field 'lastCheckedAt' must be an epoch-seconds integer")
        artifactSha256 <- optionalStr(c, "artifactSha256").flatMap {
          case None    => Right(None)
          case Some(h) => Either.cond(h.matches(Hex64), Some(h.toLowerCase), s"artifactSha256 must be 64-hex, got '${h.take(12)}…'")
        }
        previousGeneration <- decodePreviousGeneration(json)
      yield
        val facts = factsForLegacyVia(via)
        Entry(
          name = name,
          kind = kind,
          url = url,
          pin = pin,
          version = version,
          digest = digest.toLowerCase,
          installedAt = installedAt,
          via = via,
          distribution = Distribution(
            origin = origin.getOrElse(facts.origin),
            trustLevel = trustLevel.getOrElse(facts.trustLevel),
            distributionOwner = owner.getOrElse(facts.distributionOwner),
            publisherClass = publisherClass,
            hubReviewId = hubReviewId,
            submissionStatus = submissionStatus,
            lastCheckedAt = lastCheckedAt
          ),
          updatePolicy = policy.getOrElse(UpdatePolicy.Check),
          pinned = pinned,
          distributionId = distributionId,
          publisherKeyId = publisherKeyId,
          artifactSha256 = artifactSha256,
          validationSummary = validationSummary,
          transactionId = transactionId,
          previousGeneration = previousGeneration
        )

  /** 可选字符串位（缺席/null ⇒ None；在位必须字符串，trim 后空串视作缺席）。 */
  private def optionalStr(c: io.circe.HCursor, field: String): Either[String, Option[String]] =
    c.downField(field).focus match
      case None | Some(Json.Null) => Right(None)
      case Some(j) =>
        j.as[String]
          .map(v => Option(v.trim).filter(_.nonEmpty))
          .left.map(_ => s"field '$field' must be a string")

  /** 可选闭合枚举位（缺席 ⇒ None 升档；在位必须枚举内取值，否则指名 corrupt）。 */
  private def optionalLabel[T](
      c: io.circe.HCursor,
      field: String,
      parse: String => Option[T],
      expected: String
  ): Either[String, Option[T]] =
    optionalStr(c, field).flatMap {
      case None    => Right(None)
      case Some(l) => parse(l).toRight(s"unknown ledger $field '$l' (expected: $expected)").map(v => Some(v))
    }

  private def decodePreviousGeneration(json: Json): Either[String, Option[PreviousGeneration]] =
    json.hcursor.downField("previousGeneration").focus match
      case None | Some(Json.Null) => Right(None)
      case Some(j) =>
        if !j.isObject then Left("previousGeneration must be an object")
        else
          val gc = j.hcursor
          for
            v <- requiredStr(gc, "version")
            d <- requiredStr(gc, "digest")
            _ <- Either.cond(d.matches(Hex64), (), s"previousGeneration.digest must be 64-hex, got '${d.take(12)}…'")
            t <- gc.downField("switchedAt").as[Long].left.map(_ => "previousGeneration.switchedAt must be an epoch-seconds integer")
          yield Some(PreviousGeneration(v, d.toLowerCase, t))

  private def decodePin(json: Json): Either[String, Option[Pin]] =
    json.hcursor.downField("pin").focus match
      case None | Some(Json.Null) => Right(None)
      case Some(p) =>
        if !p.isObject then Left("pin must be an object")
        else
          val pc = p.hcursor
          val commit = pc.downField("commit").as[Option[String]].toOption.flatten.map(_.trim).filter(_.nonEmpty)
          val digest = pc.downField("digest").as[Option[String]].toOption.flatten.map(_.trim).filter(_.nonEmpty)
          (commit, digest) match
            case (Some(cm), _) =>
              if cm.matches(Hex40) then
                Right(
                  Some(
                    Pin.Commit(
                      pc.downField("tag").as[Option[String]].toOption.flatten.map(_.trim).filter(_.nonEmpty),
                      cm.toLowerCase
                    )
                  )
                )
              else Left("pin.commit must be 40-hex")
            case (None, Some(d)) => if d.matches(Hex64) then Right(Some(Pin.DigestBasis(d.toLowerCase))) else Left("pin.digest must be 64-hex")
            case (None, None)    => Left("pin object must carry 'commit' (git pin) or 'digest' (seed baseline pin)")

  private def requiredStr(c: io.circe.HCursor, field: String): Either[String, String] =
    c.downField(field).as[Option[String]] match
      case Left(_)      => Left(s"field '$field' must be a string")
      case Right(None)  => Left(s"field '$field' is missing")
      case Right(Some(v)) =>
        val t = v.trim
        if t.isEmpty then Left(s"field '$field' is empty") else Right(t)

  // ── 存量回填（boot 后首查一次；幂等；绝不改包目录字节）──────────────────

  final case class BackfillReport(written: List[String], alreadyRecorded: List[String], failed: List[(String, String)]):
    /** 零写零败（已全部记账）⇒ 汇总审计行静默（零噪音纪律）。 */
    def isIdle: Boolean = written.isEmpty && failed.isEmpty

  /**
   * 对已装包幂等回填 provenance（本批任务范围 3）：
   *  - **seed 树内**（`OfficialPackages.allowlist()` 键命中 = 随引擎分发的官方包，含已被
   *    用户改过字节的成员——成员资格按名判定，不按 digest）⇒ `via=seed`、`kind=official`、
   *    **以当前 digest 为基线 pin**（`pin={digest: <当前盘上 digest>}`——任务字段表原文；
   *    注意是盘上现值，不是 seed 树 digest：账本记的是「这台机器上装的是什么」）；
   *  - **seed 外** ⇒ `via=local`、`kind=local`、url/pin 缺席（原拷贝路径无处可查，**不猜**）。
   *
   * §12.1 分发面按 via 迁移映射落档（[[PluginDistributionTrust.factsForLegacyVia]]——与
   * 安装期默认同一函数）；updatePolicy=check、pinned=false、可空族全 None。
   *
   * 幂等 = 只新增缺失账本文件；文件已存在 ⇒ 零改写（连内容比对都不做）。二次 boot 零改写
   * 由 spec 以 mtime 断言钉死。**绝不改包目录任何字节**（digest 不变量，spec 钉死）。
   * 单包失败只丢该包并 WARN（照应「损坏降级不崩」的同款隔离纪律）。
   *
   * `installedAt` 用包目录 mtime——首见时刻能拿到的最近真实观测（回填无从得知真实安装
   * 时刻，注记于此，不伪造 now）。
   */
  def backfill(plugins: List[PluginRegistry.PluginDef]): BackfillReport =
    val seedNames = OfficialPackages.allowlist().keySet
    val written = mutable.ListBuffer[String]()
    val skipped = mutable.ListBuffer[String]()
    val failed = mutable.ListBuffer[(String, String)]()
    os.makeDir.all(ledgerDir)
    plugins.foreach { p =>
      val path = entryPath(p.name)
      if os.exists(path) then skipped += p.name
      else
        try
          val seedMember = seedNames.contains(p.name)
          val via = if seedMember then Via.Seed else Via.Local
          val entry = Entry(
            name = p.name,
            kind = if seedMember then Kind.Official else Kind.Local,
            url = None, // 回填不猜 url：seed 字节随引擎分发、local 原拷贝路径无记录可查
            pin = if seedMember then Some(Pin.DigestBasis(p.digest)) else None,
            version = p.version,
            digest = p.digest,
            installedAt =
              try os.mtime(os.Path(p.dir)) / 1000L
              catch case NonFatal(_) => System.currentTimeMillis() / 1000L,
            via = via,
            distribution = factsForLegacyVia(via),
            updatePolicy = UpdatePolicy.Check,
            pinned = false,
            distributionId = None,
            publisherKeyId = None,
            artifactSha256 = None,
            validationSummary = None,
            transactionId = None,
            previousGeneration = None
          )
          AtomicJson.writeSync(path, entryJson(entry).noSpaces)
          written += p.name
        catch
          case NonFatal(e) =>
            val reason = s"${e.getClass.getSimpleName}: ${e.getMessage}"
            logger.warnSync(s"[plugin-sources] backfill failed for '${p.name}' ($reason) — stays unknown-source until re-recorded")
            failed += (p.name -> reason)
    }
    val report = BackfillReport(written.toList, skipped.toList, failed.toList)
    if !report.isIdle then
      logger.infoSync(
        s"[plugin-sources] backfill: ${report.written.size} recorded, ${report.alreadyRecorded.size} already recorded, " +
          s"${report.failed.size} failed — package directories untouched"
      )
    report

end PluginSourceLedger
