package nebflow.core.root

import io.circe.Json
import io.circe.syntax.*
import nebflow.Version
import nebflow.core.AtomicJson
import nebflow.shared.NebflowLogger

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime}

import scala.collection.mutable

/**
 * 三层数据根 · 平铺根迁入账号布局 / 退回（B1，契约
 * `reports-evidence/20261104_b1-dataroot/design-api.md` §4）。
 *
 * == 语义四条（作者裁定展开，实现照此写）==
 *  1. '''触发点 = 显式动作'''，绝不在 boot / `RootLadder.resolve` 里自动跑：既有平铺机器升级后
 *     第一次启动必须仍是 flat + 零写盘（兼容红线）。本批只提供 API；登录腿在「平铺机器首次登录」
 *     时调 [[migrate]]（先迁移再写指针）。
 *  2. '''复制不是移动'''：源目录一个字节不动，逐条 sha256 记账 —— 「原件仍在可回滚」是作者裁定的
 *     逐字要求；平铺根 → 账号根的收尾清理（删原件）不在本批（属后续「迁移传输」腿）。
 *  3. '''未登录 ⇒ 待认领区'''：`machineRoot/pending-claim/<ts>/`（`ts` = 本地 `yyyyMMdd-HHmmss`），
 *     `layout.json` 写上、指针不写；下一次登录把待认领区认领进该账号根。
 *  4. '''rollback 按账删副本'''：先逐条校验副本 sha256，**一致才删**；不一致 ⇒ 拒删并点名（防搬丢），
 *     然后 `clearPointer` + 删 `layout.json` ⇒ 下次进程起来回到 flat。
 *
 * 磁盘成本如实报一行（复制 GB 级 `sessions/` 的场景）：
 * `[migrate] copying <n> entries, <size> total`。
 */
final case class MigrationReport(
  machineRoot: os.Path,
  /** 迁移时已知的首个登录账号；未登录 ⇒ None。 */
  accountSub: Option[String],
  /** accountSub 有 ⇒ 该账号根；无 ⇒ 待认领区目录。 */
  accountRoot: os.Path,
  /** true ⇒ accountRoot 落在 machineRoot/pending-claim/<ts>/。 */
  pendingClaim: Boolean,
  /** 已迁入的顶层条目名。 */
  movedEntries: List[String],
  /** 迁移记账文件（机器层）。 */
  reportPath: os.Path
)

object RootLayout:

  private val logger = NebflowLogger.forName("nebflow.root.layout")

  /** 迁移记账文件名（机器层，契约 §9.1）。 */
  val ReportFileName: String = "migration-report.json"

  /** 待认领区容器名（机器层）。 */
  val PendingClaimDirName: String = "pending-claim"

  private val TsFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  /** 逐条记账的落盘形态（契约 §4.4：`{name, kind, bytes, sha256}` + 引擎版本）。 */
  private final case class EntryRecord(name: String, kind: String, bytes: Long, sha256: String)

  /** 逐文件记账（rollback 的摘要校验面；比顶层聚合更细，能逐条点名）。 */
  private final case class FileRecord(path: String, bytes: Long, sha256: String)

  /**
   * 平铺根 → 账号布局。**只复制、不动源**（作者裁定「原件仍在」）；返回迁移报告。
   *
   * 分岔（契约 §4.1/§4.2）：
   *  - 机器层已有登录态（指针可用或任一账号根有档案）⇒ 复制进该账号根，`pendingClaim = false`；
   *    指针**不由本方法写**（登录腿先 migrate 再写指针，§4.1）；
   *  - 无任何账号 ⇒ 复制进 `pending-claim/<ts>/` 待认领区，`pendingClaim = true`。
   *
   * 两条分岔都会写 `layout.json`（否则下次起来仍是 flat，指针永远不被认）与
   * `migration-report.json`（逐条 sha256 记账）。
   */
  def migrate(machineRoot: os.Path): MigrationReport =
    val entries: List[os.Path] =
      if !os.exists(machineRoot) then Nil
      else
        os.list(machineRoot)
          .toList
          // 布局本体绝不自迁：accounts/ · unauthenticated/ · pending-claim/ · 标记 · 指针 · 记账。
          .filterNot(p => managedNames.contains(p.last))
          .sortBy(_.last)

    val target0: Option[os.Path] = firstAccountRoot(machineRoot)
    val pendingClaim = target0.isEmpty
    val target: os.Path = target0.getOrElse {
      val ts = TsFormatter.format(LocalDateTime.now())
      val dir = machineRoot / PendingClaimDirName / ts
      os.makeDir.all(dir)
      dir
    }

    val files = mutable.ListBuffer.empty[FileRecord]
    val entryRecords = entries.map { src =>
      val dst = target / src.last
      val filesBefore = files.size
      val kind = if os.isDir(src) then "dir" else "file"
      val bytes = copyTree(src, dst, target, files)
      val slice = files.drop(filesBefore).toList
      val digest =
        if kind == "file" then slice.headOption.map(_.sha256).getOrElse("")
        else AccountRoot.sha256Hex(slice.map(f => s"${f.path}:${f.sha256}").sorted.mkString("\n"))
      EntryRecord(src.last, kind, bytes, digest)
    }

    val totalBytes = entryRecords.map(_.bytes).sum
    logger.infoSync(
      s"[migrate] copying ${entryRecords.size} entries, ${humanSize(totalBytes)} total -> $target" +
        (if pendingClaim then " (pending claim — no account signed in)" else "")
    )

    AccountRoot.writeLayoutMarker(machineRoot)

    val accountSub = target0.flatMap(d => AccountRoot.readProfile(d).map(_.accountSub))
    val reportPath = machineRoot / ReportFileName
    val reportJson = Json.obj(
      "migratedAt" -> Instant.now().toString.asJson,
      "engineVersion" -> Version.string.asJson,
      "machineRoot" -> machineRoot.toString.asJson,
      "accountSub" -> accountSub.asJson,
      "pendingClaim" -> pendingClaim.asJson,
      "targetRoot" -> target.toString.asJson,
      "movedEntries" -> entryRecords.map(_.name).asJson,
      "entries" -> entryRecords
        .map(e =>
          Json.obj(
            "name" -> e.name.asJson,
            "kind" -> e.kind.asJson,
            "bytes" -> e.bytes.asJson,
            "sha256" -> e.sha256.asJson
          )
        )
        .asJson,
      "files" -> files.toList
        .map(f => Json.obj("path" -> f.path.asJson, "bytes" -> f.bytes.asJson, "sha256" -> f.sha256.asJson))
        .asJson
    )
    AtomicJson.writeSyncRestricted(reportPath, reportJson.spaces2)

    MigrationReport(
      machineRoot = machineRoot,
      accountSub = accountSub,
      accountRoot = target,
      pendingClaim = pendingClaim,
      movedEntries = entryRecords.map(_.name),
      reportPath = reportPath
    )

  end migrate

  /**
   * 退回平铺布局（契约 §4.3）：按 manifest 逐条校验**副本**摘要，一致才删；不一致 ⇒ 拒删并点名
   * （防搬丢 —— 副本被改过/被删过时删副本会掩盖一次搬运事故）。然后 `clearPointer` + 删
   * `layout.json` ⇒ 下次进程起来回到 flat。**原件一个字节不动**。
   */
  def rollback(report: MigrationReport): Unit =
    val (entries, files) = readManifest(report.reportPath)
    if entries.isEmpty && report.movedEntries.nonEmpty then
      throw new IllegalStateException(
        s"[rollback] refusing to delete copies — no verifiable manifest at ${report.reportPath} " +
          s"although the report claims ${report.movedEntries.size} moved entrie(s)"
      )
    val problems = files.flatMap { f =>
      val copy = report.accountRoot / os.SubPath(f.path)
      if !os.exists(copy) then Some(s"missing copy: $copy")
      else
        val actual = AccountRoot.sha256Hex(os.read.bytes(copy))
        if actual != f.sha256 then
          Some(s"sha256 mismatch at $copy (manifest=${f.sha256.take(12)}…, actual=${actual.take(12)}…)")
        else None
    }
    if problems.nonEmpty then
      throw new IllegalStateException(
        s"[rollback] refusing to delete copies — manifest verification failed (${problems.size} issue(s)):\n  " +
          problems.mkString("\n  ")
      )
    entries.map(_.name).distinct.foreach { name =>
      val copy = report.accountRoot / os.SubPath(name)
      if os.exists(copy) then os.remove.all(copy)
    }
    if report.pendingClaim then
      val container = report.machineRoot / PendingClaimDirName
      if os.exists(report.accountRoot) && os.list(report.accountRoot).isEmpty then os.remove(report.accountRoot)
      if os.exists(container) && os.list(container).isEmpty then os.remove(container)
    AccountRoot.clearPointer(report.machineRoot)
    val layout = AccountRoot.layoutMarkerPath(report.machineRoot)
    if os.exists(layout) then os.remove(layout)
    logger.infoSync(
      s"[rollback] flat layout restored (originals untouched); report kept at ${report.reportPath}"
    )
  end rollback

  // ── 内部 ───────────────────────────────────────────────────────────────

  /** 布局本体名 —— migrate 绝不把这些迁进账号根。 */
  private val managedNames: Set[String] = Set(
    RootLadder.AccountsDirName,
    RootLadder.UnauthenticatedDirName,
    PendingClaimDirName,
    AccountRoot.PointerFileName,
    AccountRoot.LayoutFileName,
    ReportFileName
  )

  /** 「首个登录账号」：指针可用优先，否则账号目录里第一个有档案的（账号数 = 个位数）。 */
  private def firstAccountRoot(machineRoot: os.Path): Option[os.Path] =
    val fromPointer = AccountRoot
      .readPointer(machineRoot)
      .map(a => AccountRoot.accountRoot(machineRoot, a.accountSub))
      .filter(AccountRoot.isAccountRoot)
    fromPointer.orElse {
      val base = AccountRoot.accountsDir(machineRoot)
      if !os.exists(base) then None
      else os.list(base).filter(os.isDir).toList.sortBy(_.last).find(AccountRoot.isAccountRoot)
    }

  /**
   * 复制 `src`（文件或目录树）到 `dst`，逐文件记账（`base` = 记账相对基线 = 迁移目标根）。
   * 返回本子树复制的字节数。**源一个字节不动**（只读 + 写到 dst）。
   */
  private def copyTree(src: os.Path, dst: os.Path, base: os.Path, out: mutable.ListBuffer[FileRecord]): Long =
    if os.isDir(src) then
      os.makeDir.all(dst)
      os.list(src).toList.sortBy(_.last).map(child => copyTree(child, dst / child.last, base, out)).sum
    else
      val bytes = os.read.bytes(src)
      os.write.over(dst, bytes, createFolders = true)
      out += FileRecord(
        path = dst.relativeTo(base).toString.replace('\\', '/'),
        bytes = bytes.length.toLong,
        sha256 = AccountRoot.sha256Hex(bytes)
      )
      bytes.length.toLong

  private def humanSize(bytes: Long): String =
    if bytes < 1024L then s"${bytes}B"
    else if bytes < 1024L * 1024 then f"${bytes / 1024.0}%.1fKB"
    else if bytes < 1024L * 1024 * 1024 then f"${bytes / (1024.0 * 1024)}%.1fMB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.2fGB"

  /** 读回迁移记账（`entries` = 顶层条目，`files` = 逐文件摘要，rollback 的校验面）。 */
  private def readManifest(path: os.Path): (List[EntryRecord], List[FileRecord]) =
    if !os.exists(path) then (Nil, Nil)
    else
      io.circe.parser.parse(os.read(path)).toOption match
        case None => (Nil, Nil)
        case Some(json) =>
          val c = json.hcursor
          def listOf(field: String): List[Json] = c.downField(field).as[List[Json]].getOrElse(Nil)
          val entries = listOf("entries").flatMap { j =>
            val h = j.hcursor
            for
              name <- h.get[String]("name").toOption
              kind <- h.get[String]("kind").toOption
              bytes <- h.get[Long]("bytes").toOption
              sha <- h.get[String]("sha256").toOption
            yield EntryRecord(name, kind, bytes, sha)
          }
          val files = listOf("files").flatMap { j =>
            val h = j.hcursor
            for
              p <- h.get[String]("path").toOption
              bytes <- h.get[Long]("bytes").toOption
              sha <- h.get[String]("sha256").toOption
            yield FileRecord(p, bytes, sha)
          }
          (entries, files)

end RootLayout
