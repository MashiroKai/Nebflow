package nebflow.perception

import io.circe.parser
import io.circe.syntax.*
import io.circe.generic.semiauto.*
import nebflow.shared.PathUtil
import os.Path
import PerceptionModel.*

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import java.nio.file.{Files, Path as JPath, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/**
 * 感知域文件持久化（r2 §13 文件布局，数据根随实例：
 * `<dataRoot>/perception/`）：
 *
 * {{{
 * perception/
 *   accounts.json        # 邮箱账户（无授权码值）
 *   state.json           # 每账户会话状态：lastSeenUid / lastError
 *   drafts.json          # 草稿注册表
 *   audit.jsonl          # 全链路审计（0600）
 *   spool/<id>.json      # 引擎/会话不可达时的投递缓冲（注入成功即删）
 *   billing-buffer/<date>.jsonl  # 当日账单缓冲（日结注入成功即清）
 *   drafts/              # draft-<ts>-<rand>.md（0600）
 * }}}
 *
 * 全部落盘走 `IO.blocking`；敏感目录 0700、文件 0600（os.write 自带
 * default-perms 参数在部分平台不可靠，用 Files.createFile + attrs 收口）。
 */
class PerceptionStore(val root: Path):

  import PerceptionStore.*

  def accountsFile: Path = root / "accounts.json"
  def stateFile: Path = root / "state.json"
  def draftsFile: Path = root / "drafts.json"
  def auditFile: Path = root / "audit.jsonl"
  def spoolDir: Path = root / "spool"
  def billingDir: Path = root / "billing-buffer"
  def draftsDir: Path = root / "drafts"

  /** Ensure the directory skeleton exists (idempotent, 0700). */
  def ensureDirs(): Either[String, Unit] =
    try
      os.makeDir.all(root)
      restrictDir(root)
      List(spoolDir, billingDir, draftsDir).foreach { d =>
        os.makeDir.all(d); restrictDir(d)
      }
      Right(())
    catch case e: Throwable => Left(s"perception dirs: ${e.getClass.getSimpleName}: ${e.getMessage}")

  // ── accounts.json ──────────────────────────────────────────────────────

  def loadAccounts(): List[MailAccount] =
    readJson(accountsFile).flatMap(_.as[List[MailAccount]].toOption).getOrElse(Nil)

  def saveAccounts(accounts: List[MailAccount]): Either[String, Unit] =
    writeJsonPrivate(accountsFile, accounts.asJson)

  // ── state.json（per-account session state）────────────────────────────

  def loadState(): Map[String, AccountState] =
    readJson(stateFile)
      .flatMap(_.as[Map[String, AccountState]].toOption)
      .getOrElse(Map.empty)

  def saveState(state: Map[String, AccountState]): Either[String, Unit] =
    writeJsonPrivate(stateFile, state.asJson)

  // ── drafts.json ────────────────────────────────────────────────────────

  def loadDrafts(): List[DraftRecord] =
    readJson(draftsFile).flatMap(_.as[List[DraftRecord]].toOption).getOrElse(Nil)

  def saveDrafts(drafts: List[DraftRecord]): Either[String, Unit] =
    writeJsonPrivate(draftsFile, drafts.asJson)

  /** Write a draft markdown file (0600); returns its absolute path string. */
  def writeDraftFile(draftId: String, content: String): Either[String, String] =
    try
      val p = draftsDir / s"$draftId.md"
      val jp = p.toNIO
      val bytes = content.getBytes(StandardCharsets.UTF_8)
      if Files.exists(jp) then
        Files.write(jp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
      else
        writePrivateNew(jp, bytes)
      restrictFile(jp)
      Right(p.toString)
    catch case e: Throwable => Left(s"draft write: ${e.getClass.getSimpleName}: ${e.getMessage}")

  def readDraftFile(pathStr: String): Either[String, String] =
    try Right(os.read(os.Path(pathStr)))
    catch case e: Throwable => Left(s"draft read: ${e.getMessage}")

  def deleteDraftFile(pathStr: String): Unit =
    try os.remove(os.Path(pathStr))
    catch case _: Throwable => ()

  // ── audit.jsonl（0600，append-only）────────────────────────────────────

  def appendAudit(line: AuditLine): Unit =
    try
      val jp = auditFile.toNIO
      val bytes = (line.asJson.noSpaces + "\n").getBytes(StandardCharsets.UTF_8)
      if Files.notExists(jp) then
        writePrivateNew(jp, bytes)
      else Files.write(jp, bytes, StandardOpenOption.APPEND)
      restrictFile(jp)
    catch case _: Throwable => ()

  // ── spool（引擎停机缓冲）──────────────────────────────────────────────

  def spoolWrite(env: DeliveryEnvelope): Either[String, String] =
    try
      val id = s"spool-${System.currentTimeMillis()}-${rand4()}"
      val p = spoolDir / s"$id.json"
      os.write(p, env.asJson.noSpaces)
      restrictFile(p.toNIO)
      Right(id)
    catch case e: Throwable => Left(s"spool write: ${e.getMessage}")

  def spoolList(): List[(String, DeliveryEnvelope)] =
    try
      os.list(spoolDir).filter(_.ext == "json").flatMap { p =>
        parser
          .decode[DeliveryEnvelope](os.read(p))
          .toOption
          .map(env => p.last.stripSuffix(".json") -> env)
      }.toList
    catch case _: Throwable => Nil

  def spoolRemove(id: String): Unit =
    try os.remove(spoolDir / s"$id.json")
    catch case _: Throwable => ()

  // ── billing buffer（当日账单缓冲）────────────────────────────────────

  def billingFileFor(dateStr: String): Path = billingDir / s"$dateStr.jsonl"

  def billingAppend(dateStr: String, line: BillingLine): Either[String, Unit] =
    try
      val jp = billingFileFor(dateStr).toNIO
      val bytes = (line.asJson.noSpaces + "\n").getBytes(StandardCharsets.UTF_8)
      if Files.notExists(jp) then
        writePrivateNew(jp, bytes)
      else Files.write(jp, bytes, StandardOpenOption.APPEND)
      restrictFile(jp)
      Right(())
    catch case e: Throwable => Left(s"billing append: ${e.getMessage}")

  def billingRead(dateStr: String): List[BillingLine] =
    try
      os.read.lines(billingFileFor(dateStr)).flatMap(l => parser.decode[BillingLine](l).toOption).toList
    catch case _: Throwable => Nil

  def billingClear(dateStr: String): Unit =
    try os.remove(billingFileFor(dateStr))
    catch case _: Throwable => ()

end PerceptionStore

object PerceptionStore:

  /** Default root: `<dataRoot>/perception`. */
  def defaultRoot: Path = PathUtil.dataRoot / "perception"

  def rand4(): String =
    f"${java.util.concurrent.ThreadLocalRandom.current().nextLong(0x10000L)}%04x".take(4)

  /** 每账户会话状态（state.json 的值面）。 */
  case class AccountState(
    lastSeenUid: Long = 0L,
    uidvalidity: Long = 0L,
    lastError: String = "",
    lastOkAt: Long = 0L
  )

  object AccountState:
    given io.circe.Encoder[AccountState] = deriveEncoder
    given io.circe.Decoder[AccountState] = deriveDecoder

  /** 账单缓冲行（0.4 判定入缓冲的元数据形态；不落正文全文）。 */
  case class BillingLine(
    ts: Long,
    messageId: String,
    fromDomain: String,
    subject: String,
    amountSample: String,
    matchedBy: String
  )

  object BillingLine:
    given io.circe.Encoder[BillingLine] = deriveEncoder
    given io.circe.Decoder[BillingLine] = deriveDecoder

  // ── low-level json file io ─────────────────────────────────────────────

  private def readJson(p: Path): Option[io.circe.Json] =
    try parser.parse(os.read(p)).toOption
    catch case _: Throwable => None

  private def writeJsonPrivate(p: Path, json: io.circe.Json): Either[String, Unit] =
    try
      val jp = p.toNIO
      val bytes = json.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
      if Files.exists(jp) then
        restrictFile(jp) // narrow BEFORE new bytes (same order as SecretsStore discipline)
        Files.write(jp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
      else
        writePrivateNew(jp, bytes)
      restrictFile(jp)
      Right(())
    catch case e: Throwable => Left(s"${p.last}: ${e.getClass.getSimpleName}: ${e.getMessage}")

  /** CREATE_NEW with 0600 from the first byte (mode from the creation attribute). */
  private[perception] def writePrivateNew(jp: JPath, bytes: Array[Byte]): Unit =
    val opts = java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    val ch = Files.newByteChannel(jp, opts, filePerms)
    try ch.write(java.nio.ByteBuffer.wrap(bytes))
    finally ch.close()
  end writePrivateNew

  private def filePerms: java.nio.file.attribute.FileAttribute[java.util.Set[PosixFilePermission]] =
    PosixFilePermissions.asFileAttribute(
      java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    )

  def restrictFile(p: JPath): Unit =
    try Files.setPosixFilePermissions(p, Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).asJava)
    catch case _: Throwable => ()

  def restrictDir(p: Path): Unit =
    try Files.setPosixFilePermissions(
        p.toNIO,
        Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE).asJava
      )
    catch case _: Throwable => ()
