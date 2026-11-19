package nebflow.core.ask

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas Note store: the authoritative, versioned copy of each file's note.
 *
 * Identity: `sid = ask-<8 hex of sha256(absPath)>` (AskSessions.sessionIdFor), so the same
 * file has the same note on every device and browser.
 *
 * Record: `<dataRoot>/ask/notes/<sid>.json`, `{"v":1,"version":<ms>,"text":"…","cleared":<bool>}`,
 * replaced with `AtomicJson.writeSync` (temp file plus atomic rename), so a reader sees either
 * the complete old or the complete new record.
 *   · The version is assigned here and strictly increases per note (`max(now, previous + 1)`).
 *     Version 0 means "no record". A clear is a record with `cleared:true` and an empty text:
 *     a writer that last saw an older version can tell it from a note that never existed.
 *   · A note written before versioning (`<sid>.md`) reads with its modification time as the
 *     version. The first accepted write creates the envelope and then removes the `.md` file.
 *     While an envelope exists it is the only authority: a corrupt envelope is a read error and
 *     the `.md` file is never used in its place (it could be stale).
 *
 * Writes are conditional (see [[AskNoteStore.put]]): a write names the version it is based on and
 * is applied only while that version is current. Anything that cannot be read is reported as a
 * read error, never as an empty note, and is never overwritten.
 *
 * Size: at most `MaxBytes` (256 KiB) of UTF-8 bytes of text. User content is never deleted
 * automatically; clearing a note is the only way its text goes away.
 *
 * Consistency: every read and write of one note runs under one lock that is shared by all
 * `AskNoteStore` instances (the gateway builds an instance per call), so a conditional write is a
 * single read-compare-write step and a reader never sees the migration half done.
 * ═══════════════════════════════════════════════════════════════════════════ */

/**
 * One read of a note. `ts` is the record version (0 = no record); for a clear `exists` is false,
 * `cleared` is true and `ts` is the version of the clear. `error` is set when the note could not
 * be read: such a snapshot is NOT an empty note and must not be treated as one.
 */
final case class AskNoteSnapshot(
  text: String,
  ts: Long,
  exists: Boolean,
  cleared: Boolean = false,
  error: Option[String] = None
):
  def version: Long = ts
  def isEmpty: Boolean = error.isEmpty && (!exists || text.isEmpty)

/** Result of a write. Sealed: callers must handle every branch. */
enum AskNoteWrite:
  /** Applied (or already saved) at version `ts`; `supersededTs` is the version it replaced (0 = none). */
  case Ok(ts: Long, supersededTs: Long)

  /** Over the size limit (UTF-8 bytes). Nothing written. */
  case TooLarge(bytes: Int, limit: Int)

  /** Malformed session id (path traversal guard). Nothing written. */
  case BadSessionId(id: String)

  /** The write itself failed (disk full, permissions). The stored record is unchanged. */
  case Failed(message: String)

  /** The base version is not the current one. Nothing written; `current` is the stored state. */
  case Conflict(current: AskNoteSnapshot)

  /** The write named no base version. Nothing written; `current` is the stored state. */
  case VersionRequired(current: AskNoteSnapshot)

  /** The stored record cannot be read, so it must not be overwritten. Nothing written. */
  case ReadFailed(message: String)

final class AskNoteStore(dataRoot: os.Path):
  import AskNoteStore.*

  private val logger = NebflowLogger.forName("nebflow.ask.notes")

  /** Size limit in UTF-8 bytes of the text. */
  val MaxBytes: Int = 256 * 1024

  /** Directory of the records: `<dataRoot>/ask/notes`. */
  val dir: os.Path = dataRoot / "ask" / "notes"

  /** `ask-` plus 8 hex only: this is also the path-traversal guard (`../`, absolute paths, drive letters never match). */
  private val SidPattern = "^ask-[0-9a-f]{8}$".r

  def isValidSid(sid: String): Boolean =
    sid != null && SidPattern.matches(sid)

  /** Path of the legacy plain-text record (read, then migrated by the first accepted write). */
  def pathFor(sid: String): os.Path = dir / s"$sid.md"

  /** Path of the versioned record. */
  def recordPathFor(sid: String): os.Path = dir / s"$sid.json"

  private def lockOf(sid: String): AnyRef = locks.computeIfAbsent(recordPathFor(sid).toString, _ => new Object)

  private def readStored(sid: String): Stored =
    val record = recordPathFor(sid)
    val legacy = pathFor(sid)
    try
      if os.exists(record) then
        decodeRecord(os.read(record)) match
          case Right(stored) => stored
          case Left(why) => Stored.Unreadable(s"corrupt note record: $why")
      else if os.exists(legacy) then
        val text = os.read(legacy)
        if text.isEmpty then Stored.Cleared(os.mtime(legacy)) else Stored.Live(text, os.mtime(legacy))
      else Stored.Absent
    catch
      case NonFatal(e) =>
        logger.warn(s"ask note read failed: $sid: ${e.getMessage}")
        Stored.Unreadable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  /** Read one note. A missing note is an empty snapshot; an unreadable one carries `error` and no text. */
  def read(sid: String): IO[AskNoteSnapshot] =
    IO.blocking {
      if !isValidSid(sid) then AskNoteSnapshot("", 0L, false)
      else lockOf(sid).synchronized(snapshotOf(readStored(sid)))
    }

  /**
   * Conditional write: the only write the gateway performs.
   *   · unreadable record → `ReadFailed`, nothing written;
   *   · no base version → `VersionRequired`, nothing written (an old caller cannot tell a note that
   *     never existed from one cleared before clears were versioned);
   *   · text equal to the stored text → `Ok` at the stored version, nothing written (a retry after
   *     a lost receipt, or two writers that agree);
   *   · base version differs from the stored version → `Conflict`, nothing written;
   *   · otherwise the text is stored with a new version; empty text stores a clear.
   * `baseVersion = Some(0)` means "I saw no note".
   */
  def put(sid: String, text: String, baseVersion: Option[Long]): IO[AskNoteWrite] =
    writeNote(sid, text, conditional = true, baseVersion)

  /** Unconditional write for the store's own tests and maintenance; the gateway never calls it. */
  private[ask] def write(sid: String, text: String): IO[AskNoteWrite] =
    writeNote(sid, text, conditional = false, None)

  private def writeNote(sid: String, text: String, conditional: Boolean, baseVersion: Option[Long]): IO[AskNoteWrite] =
    IO.blocking {
      if !isValidSid(sid) then AskNoteWrite.BadSessionId(String.valueOf(sid))
      else
        val body = if text == null then "" else text
        val bytes = body.getBytes(StandardCharsets.UTF_8).length
        if bytes > MaxBytes then AskNoteWrite.TooLarge(bytes, MaxBytes)
        else lockOf(sid).synchronized(commit(sid, body, conditional, baseVersion))
    }

  /** The read-compare-write step. Callers hold the note's lock. */
  private def commit(sid: String, body: String, conditional: Boolean, baseVersion: Option[Long]): AskNoteWrite =
    readStored(sid) match
      case Stored.Unreadable(why) => AskNoteWrite.ReadFailed(why)
      case stored =>
        val current = snapshotOf(stored)
        if conditional && baseVersion.isEmpty then AskNoteWrite.VersionRequired(current)
        else if body == current.text then AskNoteWrite.Ok(current.version, current.version)
        else if conditional && !baseVersion.contains(current.version) then AskNoteWrite.Conflict(current)
        else store(sid, body, current.version)

  /** Replace the record atomically, then drop the legacy file. A failed write leaves the stored record unchanged. */
  private def store(sid: String, body: String, previousVersion: Long): AskNoteWrite =
    val version = math.max(System.currentTimeMillis(), previousVersion + 1)
    try
      AtomicJson.writeSync(recordPathFor(sid), encodeRecord(version, body))
      val legacy = pathFor(sid)
      try os.remove(legacy, checkExists = false)
      catch
        case NonFatal(e) =>
          // The envelope is authoritative, so a leftover legacy file is only clutter.
          logger.warn(s"ask note legacy file not removed: $sid: ${e.getMessage}")
      AskNoteWrite.Ok(version, previousVersion)
    catch
      case NonFatal(e) =>
        logger.warn(s"ask note write failed: $sid: ${e.getMessage}")
        AskNoteWrite.Failed(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
end AskNoteStore

object AskNoteStore:

  /** One lock per note, shared by every instance. */
  private val locks = new ConcurrentHashMap[String, AnyRef]()

  /** What is on disk for one note. */
  private enum Stored:
    case Absent
    case Live(text: String, version: Long)
    case Cleared(version: Long)
    case Unreadable(message: String)

  private def snapshotOf(stored: Stored): AskNoteSnapshot = stored match
    case Stored.Absent => AskNoteSnapshot("", 0L, false)
    case Stored.Live(text, version) => AskNoteSnapshot(text, version, true)
    case Stored.Cleared(version) => AskNoteSnapshot("", version, false, cleared = true)
    case Stored.Unreadable(why) => AskNoteSnapshot("", 0L, false, error = Some(why))

  private def encodeRecord(version: Long, text: String): String =
    Json
      .obj(
        "v" -> 1.asJson,
        "version" -> version.asJson,
        "text" -> text.asJson,
        "cleared" -> text.isEmpty.asJson
      )
      .printWith(io.circe.Printer.noSpaces.copy(escapeNonAscii = true))

  /** Parse an envelope. A record that contradicts itself is corrupt, not "empty". */
  private def decodeRecord(raw: String): Either[String, Stored] =
    io.circe.parser.parse(raw).left.map(_.getMessage).flatMap { json =>
      val cursor = json.hcursor
      for
        version <- cursor.get[Long]("version").left.map(_.getMessage)
        text <- cursor.get[String]("text").left.map(_.getMessage)
        cleared <- cursor.getOrElse[Boolean]("cleared")(text.isEmpty).left.map(_.getMessage)
        stored <-
          if version <= 0L then Left("version must be positive")
          else if cleared && text.nonEmpty then Left("cleared record carries text")
          else if cleared || text.isEmpty then Right(Stored.Cleared(version))
          else Right(Stored.Live(text, version))
      yield stored
    }

  /** Built from the data root on every call (`PathUtil.dataRoot` is live, so an isolated root takes effect at once). */
  def current: AskNoteStore = new AskNoteStore(PathUtil.dataRoot)
end AskNoteStore
