package nebflow.core.project

import io.circe.Codec
import io.circe.syntax.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode

import java.time.Instant

import nebflow.core.{NebflowLogger, PathUtil}

/**
 * TaskLedgerHistory -- the change history of the unified ledger (taskunify batch,
 * 2026-09-24).
 *
 * Role: the **change-history data face** of the `Task` tool (Nebula's exclusive write
 * face) -- append-only JSONL in a **single global file**
 * `~/.nebflow/tasks-v2-history.jsonl` (same directory as the ledger, not per-workspace:
 * a unified ledger = one id space + one watermark ⇒ one history file, filtered by entry
 * id).
 *
 * Difference from the two legacy histories (unified, not an alias): the legacy face had
 * `TaskListHistory` (`tasks-history.jsonl`, per home) AND `TaskBoardHistory`
 * (`task-history.jsonl`, per workspace) side by side; this file is **one** history at
 * `<dataRoot>/tasks-v2-history.jsonl`. Both legacy histories stay **untouched, zero
 * deletion, zero overwrite** (read-only archive); new code neither reads nor writes them.
 *
 * Row schema (registry-style extension: unknown kind / unknown keys are accepted, not
 * rejected, on the read side): `at` (ISO-8601 UTC) / `kind` / `id` (entry id; omitted for
 * global events) / `actor` (`nebula` | `dispatcher` | `node` | `system`; **derived
 * engine-side, never from client parameters**) / `from` (the origin domain of note-class
 * events, see [[TaskLedgerHistory.Origins]]) / `text` (the full body of a note-class
 * event) / `prev` / `next` (both sides' full text for note-overwrite events) / `links` /
 * `detail` (free text). **No seq**: the ordering key is file line order (append-only gives
 * natural chronology); `at` is for display only.
 *
 * **The main body is the note timeline** (author ruling n: structured = `from` +
 * timestamp + body excerpt): the only write path for a note is the engine appending
 * automatically on every Mail from Nebula to that task's dispatcher (the tool layer has
 * **no** note parameter ⇒ structurally impossible). Each append writes one `kind=note`
 * row carrying `from` (origin domain) + `at` (timestamp) + `text` (body). The per-item cap
 * is 16,000 characters (see [[TaskLedgerStore.NoteCapChars]]); **over-long bodies are
 * chunked, never truncated** (🔴 "truncate and drop" is forbidden -- after chunking the
 * full text is still in the history).
 *
 * Rotation (same parameters as the current code): the active file exceeding 5 MiB or
 * 20,000 lines (whichever comes first) ⇒ triggered lazily on the **next write**; active →
 * `.1` (**overwriting the previous generation**), and the new active file's first line is
 * a `rotate` event (carrying the archived line count and byte size). On-disk hard cap ≈
 * 10 MiB. The read path never writes (no rotation).
 *
 * Locking: this module has **no lock of its own** -- every append happens inside the
 * `TaskLedgerStore.fileLock` critical section (the write path), naturally serialized with
 * the state writes in the same critical section. This module holds no task state.
 */
case class TaskLedgerEvent(
  at: String,
  kind: String,
  id: Option[String] = None,
  actor: String = TaskLedgerHistory.Actors.System,
  from: Option[String] = None,
  text: Option[String] = None,
  prev: Option[String] = None,
  next: Option[String] = None,
  links: List[String] = Nil,
  detail: Option[String] = None
)
object TaskLedgerEvent:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskLedgerEvent] = ConfiguredCodec.derived

class TaskLedgerHistory private ():
  import TaskLedgerHistory.*

  /** `def` not `val`: `PathUtil.dataRoot` can be re-rooted by tests (`setDataRoot`) --
    * the same family of trap. */
  def file: os.Path = PathUtil.dataRoot / FileName

  def archiveFile: os.Path = PathUtil.dataRoot / ArchiveFileName

  // ------------------------------------------------------------------
  // Write: append (rotation check -> trailing-newline fix -> append)
  // ------------------------------------------------------------------

  /** Append one event; `None` = success, `Some(reason)` = failure (the caller then
    * attaches a NOTE to the result line or reports an error -- **never silently**). */
  def appendSync(ev: TaskLedgerEvent): Option[String] =
    try
      val rotated = rotateIfNeeded()
      if !rotated then ensureTrailingNewline()
      os.write.append(file, ev.asJson.noSpaces + "\n", createFolders = true)
      None
    catch
      case e: Exception =>
        val reason = s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("(no message)")}"
        logger.warnSync(s"[taskledger] history append failed ($reason) — path=$file")
        Some(reason)

  /** If the active file's last byte is not `\n`, append one first (a safeguard against a
    * crash's half-written line gluing onto the next one). */
  private def ensureTrailingNewline(): Unit =
    if os.exists(file) then
      val raf = new java.io.RandomAccessFile(file.toIO, "r")
      try
        val len = raf.length()
        if len > 0 then
          raf.seek(len - 1)
          if raf.read() != '\n' then os.write.append(file, "\n", createFolders = true)
      finally raf.close()

  /** Lazy rotation (called only on the write path): the active file crossing a threshold
    * ⇒ rename to overwrite generation `.1` + write a `rotate` event as the new active
    * file's first line. Returns whether a rotation happened. */
  private def rotateIfNeeded(): Boolean =
    if !os.exists(file) then false
    else
      val size = os.size(file)
      val overBytes = size > RotationMaxBytes
      val lines = if size > LineCountProbeBytes then countLines() else 0
      if !overBytes && lines <= RotationMaxLines then false
      else
        try
          os.move(file, archiveFile, replaceExisting = true)
          val ev = TaskLedgerEvent(
            at = Instant.now().toString,
            kind = Kinds.Rotate,
            actor = Actors.System,
            detail = Some(s"rotated lines=$lines bytes=$size to=$ArchiveFileName (previous generation dropped)"))
          os.write.over(file, ev.asJson.noSpaces + "\n")
          logger.warnSync(s"[taskledger] history rotated: lines=$lines bytes=$size (archive=$ArchiveFileName)")
          true
        catch
          case e: Exception =>
            logger.warnSync(s"[taskledger] history rotation failed (${e.getMessage}) — appending to active file unchanged")
            false

  private def countLines(): Int =
    try os.read.lines(file).count(_.trim.nonEmpty)
    catch case _: Exception => 0

  // ------------------------------------------------------------------
  // Read: generation `.1` + active (archive before active ⇒ naturally ascending)
  // ------------------------------------------------------------------

  /** Read one entry's events: return the most recent `limit` in ascending line order
    * (`only` = class filter, used for the independent read windows of "the note mainline"
    * and "the state-class secondary section"); `total` = the number of rows matching this
    * id that also pass `only`; `skipped` = the number of rows that matched the prefilter
    * but could not be parsed. The read path never writes. */
  def readFor(id: String, limit: Int = Int.MaxValue, only: TaskLedgerEvent => Boolean = _ => true): ReadResult =
    val needle = s""""id":"$id""""
    val buf = scala.collection.mutable.ListBuffer[TaskLedgerEvent]()
    var total = 0
    var skipped = 0

    def scan(path: os.Path): Unit =
      if os.exists(path) then
        val lines =
          try os.read.lines(path)
          catch
            case e: Exception =>
              logger.warnSync(s"[taskledger] history read failed (${e.getMessage}) — path=$path")
              Seq.empty
        lines.foreach { raw =>
          val t = raw.trim
          if t.nonEmpty && t.contains(needle) then
            decode[TaskLedgerEvent](t) match
              case Right(ev) =>
                if only(ev) then
                  total += 1
                  if buf.size >= limit then buf.remove(0)
                  buf += ev
              case Left(_) => skipped += 1
        }

    scan(archiveFile)
    scan(file)
    ReadResult(events = buf.toList, total = total, skipped = skipped)

  /** Cheap probe (no JSON parsing): whether this id ever appeared in the history files --
    * used to decide an archive hit. */
  def countLinesFor(id: String): Int =
    val needle = s""""id":"$id""""
    def count(path: os.Path): Int =
      if !os.exists(path) then 0
      else
        try os.read.lines(path).count(_.contains(needle))
        catch case _: Exception => 0
    count(archiveFile) + count(file)
end TaskLedgerHistory

object TaskLedgerHistory:

  val FileName        = "tasks-v2-history.jsonl"
  val ArchiveFileName = "tasks-v2-history.1.jsonl"

  /** Active-file rotation thresholds (> 5 MiB or > 20,000 lines, whichever comes first) ⇒
    * on-disk hard cap ≤ 2 generations ≈ 10 MiB. The values follow the current code (the
    * task book's §② ruling: "rotation parameters follow the current code = 5 MiB /
    * 20,000 lines"). */
  val RotationMaxBytes: Long = 5L * 1024 * 1024
  val RotationMaxLines: Int  = 20_000

  /** Line-count probe threshold: a file under 1 MiB cannot exceed the 20,000-line
    * threshold (the rotation check is O(1) in the normal case). */
  val LineCountProbeBytes: Long = 1L * 1024 * 1024

  /** actor value domain: derived engine-side (Nebula / dispatcher / flow node / the
    * tool's built-in system). */
  object Actors:
    val Nebula     = "nebula"
    val Dispatcher = "dispatcher"
    val Node       = "node"
    val System     = "system"

  /** The **origin domain** of note-class events (the `from` field value; ruling n:
    * structured = `from` + timestamp + body excerpt). */
  object Origins:
    /** Nebula's Mail-driven automatic append (the only normal write path). */
    val Nebula = "nebula"
    /** Engine-side system events (attribution repair / migration class). */
    val Engine = "engine"

  /** Event kind value domain (registry-style extension: a new kind = add one word here +
    * call it at the write point; the read side needs no change). */
  object Kinds:
    val Note       = "note"       // note-timeline entry (auto-appended by the engine on Mail)
    val Create     = "create"
    val Update     = "update"
    val Complete   = "complete"
    val Close      = "close"
    val Prune      = "prune"
    val Quarantine = "quarantine"
    val Rotate     = "rotate"

  /** Note-mainline test (the `show` main section): note-class events form the timeline
    * body; state-class events fall back to the secondary section. */
  def isNoteEvent(ev: TaskLedgerEvent): Boolean =
    ev.kind == Kinds.Note || (ev.kind == Kinds.Update && ev.next.isDefined)

  private[project] val logger = NebflowLogger.forName("nebflow.taskledger.history")

  /** Singleton (a global file, not per-workspace). */
  val instance: TaskLedgerHistory = new TaskLedgerHistory()

  def open(): TaskLedgerHistory = instance

  /** Read result: `events` ascending (the most recent `limit`); `total` = total matches;
    * `skipped` = number of bad rows. */
  final case class ReadResult(
    events: List[TaskLedgerEvent] = Nil,
    total: Int = 0,
    skipped: Int = 0
  ):
    def truncated: Boolean = total > events.size
end TaskLedgerHistory
