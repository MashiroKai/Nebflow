package nebflow.core.plugin

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * PluginSourceLedger —— provenance 账本（远程发布源设计稿 §2.1/§2.4，P1 源引用批）。
 *
 * 权威来源层 = `<dataRoot>/plugin-sources/<name>.json`（单文件单包；`plugins/` 的兄弟目录，
 * **包目录之外**）。设计公理一「provenance 不入包」：digest 覆盖包目录全部字节（盘点 §1.3），
 * 来源写进包内 = 改 digest = 自毁信任面。**硬不变量（spec 钉死）**：安装/回填/账本写全部
 * 不得改变包 digest——先装 → 记账 → 重算 digest 前后相等。
 *
 * 字段（本批任务字段表）：`{schema, name, kind, url?, pin?, version, digest, installedAt, via}`
 *  - `kind` ∈ official | git | https | local（local = 本地目录拷贝，非三态 spec 形态：字节
 *    在盘上，无「重取源」语义）；
 *  - `via` ∈ seed | official | git | https | local（seed = 随引擎分发内置字节；https =
 *    P2 直链归档腿，additive 扩展——旧读端按闭合枚举降级 unknown-source）；
 *  - `pin` 两形态：git 源 = `{commit[, tag]}`（本批 install 腿记录 clone 后 HEAD 实测值）；
 *    seed 回填 = `{digest}`（「以当前 digest 为基线 pin」，任务字段表原文）。
 *  - `history` 由 update 腿（P2/P3）追加（滚动裁剪 N=10，§2.4）；本批 reader 对文件内
 *    未知字段**宽容忽略**（前向兼容），对已知字段违规降级 unknown-source。
 *
 * 写面：引擎唯一写点 = 安装路径（本批接 install 腿：[[recordInstall]]；update 腿 P2/P3 接）。
 * 落账 best-effort：账本写失败**不回滚安装**（在位即信任零变化），该包查询面降级
 * unknown-source 并 WARN 留痕。存量回填（[[backfill]]）= boot 后首查一次、幂等、已记账
 * 零改写、绝不触包目录字节。
 *
 * 读面：内部查询 API（[[query]] / [[queryAll]]；REST 投影留 P3）。缺账本/账本损坏 ⇒
 * **明确 unknown-source，不猜**（绝不因包名前缀或目录形态反推来源）；单包账本坏 = 该包
 * unknown-source，不影响他包（单文件单包的天然隔离）。
 *
 * 审计：每次账本落一行审计（安装流现役审计族同款 logger 行；回填落一行汇总，不在 boot
 * 刷 23 行）。
 */
object PluginSourceLedger:

  private val logger = NebflowLogger.forName("nebflow.plugin.sources")

  /** 账本 schema 版本（§2.4 同款演进位：读端未知字段宽容、缺字段降级不失败）。 */
  val Schema = 1

  private val Hex40 = "[0-9a-fA-F]{40}"
  private val Hex64 = "[0-9a-fA-F]{64}"

  // ── 类型面 ────────────────────────────────────────────────

  enum Kind(val label: String):
    case Official extends Kind("official")
    case Git extends Kind("git")
    case Https extends Kind("https")

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

  /** 单包账本条目（字段表见类注释；`url`/`pin` 可缺席——缺席 ≠ null，不出现在 JSON 里）。 */
  final case class Entry(
      name: String,
      kind: Kind,
      url: Option[String],
      pin: Option[Pin],
      version: String,
      digest: String,
      installedAt: Long,
      via: Via
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

  // ── 写面（引擎唯一写点；本批调用点 = installFromSync 安装腿）──────────────

  /**
   * 安装/更新腿的账本落账（同步实现——调用点在 `IO.blocking` 内，installFromSync 同款）。
   * 原子写（AtomicJson tmp+rename，crash-safe）；成功落一行审计。
   */
  def recordInstall(
      name: String,
      kind: Kind,
      url: Option[String],
      pin: Option[Pin],
      version: String,
      digest: String,
      via: Via
  ): Either[String, Entry] =
    if !PluginRegistry.validPluginName(name) then Left(s"invalid plugin name '$name' — ledger entry refused")
    else if digest.trim.isEmpty then Left(s"empty digest for '$name' — ledger entry refused")
    else
      val entry = Entry(
        name = name,
        kind = kind,
        url = url.map(_.trim).filter(_.nonEmpty),
        pin = pin,
        version = version,
        digest = digest.trim.toLowerCase,
        installedAt = System.currentTimeMillis() / 1000L,
        via = via
      )
      writeEntry(entry)
      Right(entry)

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
    val withUrl = e.url.fold(base)(u => base.add("url", u.asJson))
    val withPin = e.pin.fold(withUrl)(p => withUrl.add("pin", pinJson(p)))
    Json.fromJsonObject(withPin)

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
   * 条目解码（schema 1）。**未知字段宽容忽略**（P3 history 等后续扩展不破旧读端）；
   * 已知字段缺失/类型违规/枚举外取值 = Left（corrupt）。`via`/`kind` 是闭合枚举
   * （任务字段表逐字），枚举外取值按损坏处置并指名——这是「读出 honest 未知」而不是
   * 猜一个近似值。
   */
  private def decodeEntry(json: Json): Either[String, Entry] =
    if !json.isObject then Left("ledger entry must be a JSON object")
    else
      val c = json.hcursor
      for
        name <- requiredStr(c, "name")
        kind <- requiredStr(c, "kind").flatMap(l => Kind.fromLabel(l).toRight(s"unknown ledger kind '$l' (expected: official | git | https | local)"))
        via <- requiredStr(c, "via").flatMap(l => Via.fromLabel(l).toRight(s"unknown ledger via '$l' (expected: seed | official | git | https | local)"))
        version <- requiredStr(c, "version")
        digest <- requiredStr(c, "digest")
        _ <- Either.cond(digest.matches(Hex64), (), s"digest must be 64-hex, got '${digest.take(12)}…'")
        installedAt <- c.downField("installedAt").as[Long].left.map(_ => "installedAt must be an epoch-seconds integer")
        url = c.downField("url").as[Option[String]].toOption.flatten.map(_.trim).filter(_.nonEmpty)
        pin <- decodePin(json)
      yield Entry(name, kind, url, pin, version, digest.toLowerCase, installedAt, via)

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
   * 幂等 = 只新增缺失账本文件；文件已存在 ⇒ 零改写（连内容比对都不做——本批没有任何
   * 改写既有账本的路径，update 腿 P2/P3 接）。二次 boot 零改写由 spec 以 mtime 断言钉死。
   * **绝不改包目录任何字节**（digest 不变量，spec 钉死）。单包失败只丢该包并 WARN
   * （照应「损坏降级不崩」的同款隔离纪律）。
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
            via = if seedMember then Via.Seed else Via.Local
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
