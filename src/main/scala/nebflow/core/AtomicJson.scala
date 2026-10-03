package nebflow.core

import cats.effect.IO

/**
 * Crash-safe file writes for JSON (or any small text) state files.
 *
 * Writes go to a unique temp file in the target's directory, then
 * `java.nio.file.Files.move` with `ATOMIC_MOVE` — which maps to rename(2),
 * a true single-step atomic replace on POSIX. Readers therefore see either
 * the complete old file or the complete new file, never a truncated/partial
 * write left behind by a mid-write crash (issue #23).
 *
 * Why not `os.move.over(replaceExisting = true)`: os-lib implements that as
 * delete-then-rename, which is NOT atomic (and throws NoSuchFileException if
 * the target disappears between the two steps). The old tmp+move.over
 * precedents only narrowed the corruption window — this closes it.
 *
 * Concurrency: the temp name carries a random UUID, so concurrent writers
 * never share a temp file (the shared `_index.json.tmp` race — see
 * SessionStore.saveIndex history).
 */
object AtomicJson:

  /** Effectful write — runs on the blocking thread pool. */
  def write(path: os.Path, content: String): IO[Unit] =
    IO.blocking(writeSync(path, content))

  /**
   * Direct blocking write for call sites already inside `IO.blocking`, or
   * synchronous code (ModelRegistry-style). Same semantics as [[write]].
   */
  def writeSync(path: os.Path, content: String): Unit =
    val tmp = path / os.up / s"${path.last}.tmp.${java.util.UUID.randomUUID()}"
    try
      os.write.over(tmp, content, createFolders = true)
      java.nio.file.Files.move(
        tmp.toNIO,
        path.toNIO,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE
      )
    catch
      case e: Throwable =>
        // On failure the target file is untouched — that is the point of the
        // tmp+rename dance. Best-effort cleanup so failed writes don't litter
        // the directory with *.tmp.* residue.
        try if os.exists(tmp) then os.remove(tmp)
        catch case _: Exception => ()
        throw e
    end try
  end writeSync

  /**
   * Durability-first variant of [[writeSync]] for caches and state files whose
   * loss on a crash-window matters more than fail-loud atomicity: the temp
   * file is fsync'd (`FileChannel.force(true)`) before the rename, and a
   * filesystem without atomic-move support degrades to a plain replacing
   * move instead of failing the write (usage-agg / device-profiles precedent).
   * Callers wanting strict atomic-or-fail semantics should use [[writeSync]].
   */
  def writeSyncDurable(path: os.Path, content: String): Unit =
    val tmp = path / os.up / s"${path.last}.tmp.${java.util.UUID.randomUUID()}"
    try
      os.write.over(tmp, content, createFolders = true)
      val ch = java.nio.channels.FileChannel.open(tmp.toNIO, java.nio.file.StandardOpenOption.WRITE)
      try ch.force(true)
      finally ch.close()
      try
        java.nio.file.Files.move(
          tmp.toNIO,
          path.toNIO,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
      catch
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          // Providers without atomic move support: degrade to a replacing
          // move rather than losing the write.
          java.nio.file.Files.move(
            tmp.toNIO,
            path.toNIO,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
          )
      end try
    catch
      case e: Throwable =>
        try if os.exists(tmp) then os.remove(tmp)
        catch case _: Exception => ()
        throw e
    end try
  end writeSyncDurable

  // ── Append journal + periodic checkpoint (perf-481 A3) ────────────────────
  //
  // PROBLEM this solves: [[writeSync]] rewrites the whole file on every change.
  // Chain-ledger.json is 13.4 MB for a busy project (39 033 `rounds` manifests,
  // 8 `entries` rows) ⇒ every state change costs a full 13.4 MB write, measured
  // at 1.88 writes/min = ~26 MB/min and 2.23–17.34 ms of blocking IO per write.
  //
  // SHAPE: a write-ahead journal beside the file.
  //   · every change appends ONE record line to `<file>.journal`
  //     (one line = `noSpaces` JSON, so no embedded newline);
  //   · the journal is folded back into the checkpoint — an atomic [[writeSync]]
  //     of the file itself — once it has grown to the rotation bound, and only
  //     THEN is the journal truncated.
  //
  // The caller owns the record SHAPE (this object stays generic over lines): a
  // record need not be the whole file — the ledger appends the state minus the
  // append-only rows the checkpoint already holds (see `ChainLedgerStore`), and
  // that trim is what makes each append a few KB instead of 13.4 MB.
  //
  // WHY THIS ORDERING IS THE WHOLE POINT (crash safety, preserve-don't-weaken):
  //   · The invariant is "checkpoint + journal together carry the newest state".
  //     The checkpoint is always an observable, COMPLETE state on its own; the
  //     journal only ever carries records written after it (`persist` always
  //     appends before it considers rotating).
  //   · Fold-then-truncate is the same discipline the ledger already uses for
  //     its cold archive ("cold file first, hot ledger second"): the durable
  //     copy of the state lands before the source is dropped. The reverse order
  //     would discard records that never reached the checkpoint — strictly
  //     worse than the current code, so it is not allowed.
  //   · A torn TAIL (the process died mid-append) is discarded on replay: a
  //     line without a terminating newline is not a frame at all, and a framed
  //     line that does not parse is dropped (a valid-JSON prefix is impossible
  //     — a truncated object has unclosed braces). Replay stops at the first
  //     torn frame: records after it cannot be trusted as a continuation.
  //   · A crash between the checkpoint and the truncate could not have lost
  //     anything even in principle (the checkpoint already holds that state);
  //     at worst the journal replays records it had already folded in, and the
  //     caller's merge is idempotent under that replay (round rows dedupe by
  //     their append-only round number).

  /** Journal file paired with `path` (same directory, `<last>.journal` suffix). */
  def journalPathOf(path: os.Path): os.Path = path / os.up / s"${path.last}.journal"

  /** One accepted journal record plus its on-disk frame size (record + newline). */
  final case class Note(note: String, frameBytes: Long)

  /**
   * Raw read of a checkpointed file: the checkpoint text when it is present and
   * valid, plus every COMPLETE journal record, oldest first.
   *
   * `checkpointError` is `Some` only for an EXISTING-but-unusable checkpoint
   * (unreadable, empty, or failing to parse) — an absent checkpoint is normal
   * (a fresh file, or one whose first write has not rotated yet) and reports
   * `None`. The caller decides the response; the point is that a corrupt
   * authoritative file is representable instead of silently absorbed.
   */
  final case class JournalRead(
    checkpoint: Option[String] = None,
    records: List[Note] = Nil,
    checkpointError: Option[String] = None
  ):
    /** True when neither face carries anything (fresh file; no WARN warranted). */
    def isEmpty: Boolean = checkpoint.isEmpty && records.isEmpty && checkpointError.isEmpty

  /**
   * Read both faces. Never fails: a file with nothing usable in it comes back
   * as an empty [[JournalRead]] (or one carrying `checkpointError`), so callers
   * keep their own「empty + WARN」policy rather than having it decided here.
   */
  def readAll(path: os.Path): JournalRead =
    val records = readJournalRecords(path)
    if !os.exists(path) then JournalRead(records = records)
    else
      val raw =
        try Some(os.read(path))
        catch case e: Exception => None
      raw match
        case None => JournalRead(records = records, checkpointError = Some(s"${path.last} unreadable"))
        case Some(text) if text.trim.isEmpty =>
          JournalRead(records = records, checkpointError = Some(s"${path.last} empty"))
        case Some(text) =>
          // 🔴 `parser.parse` returns `Either`, it does NOT throw: wrapping it in
          // `Try(...).toOption` would make every failure look like a success
          // (`Success(Left(...))` is still `Some`), so a corrupt checkpoint would
          // be accepted as valid and `checkpointError` would never be set — the
          // 「损坏绝不静默」guarantee silently lost. Match the `Either` directly.
          io.circe.parser.parse(text) match
            case Right(_) => JournalRead(checkpoint = Some(text), records = records)
            case Left(_)  => JournalRead(records = records, checkpointError = Some(s"${path.last} corrupt"))

  /**
   * Frame bytes currently carried by the journal's complete records (0 when it
   * has none). This is the checkpoint lag: exactly the bytes the next rotation
   * folds back into `<file>`.
   */
  def noteBytes(path: os.Path): Long = readJournalRecords(path).map(_.frameBytes).sum

  /** Current journal size in bytes (0 when absent). */
  def journalBytes(path: os.Path): Long =
    val j = journalPathOf(path)
    if os.exists(j) then os.size(j) else 0L

  /** Bytes appended by [[appendSync]] for a record of this size (record + newline). */
  def recordBytes(content: String): Long = content.getBytes("UTF-8").length.toLong + 1L

  /** Append one complete record line. Blocking; call inside `IO.blocking`. */
  def appendSync(path: os.Path, content: String): Unit =
    // `content` must be newline-free or the line framing breaks; every current
    // caller passes `Json.noSpaces`, whose string values escape newlines.
    require(!content.contains('\n'), "appendSync record must be a single line")
    os.write.append(journalPathOf(path), content + "\n", createFolders = true)

  /**
   * Fold `content` into the checkpoint atomically, then drop the journal.
   * Fold FIRST, truncate SECOND — see the ordering note above.
   */
  def rotateSync(path: os.Path, content: String): Unit =
    writeSync(path, content)
    val j = journalPathOf(path)
    try if os.exists(j) then os.remove(j)
    catch case _: Exception => ()   // a leftover journal only costs a replay

  /**
   * Every COMPLETE record in the journal, oldest first, with its frame size.
   *
   *   · a trailing segment without a terminating newline is a torn append (the
   *     process died mid-write) and is DROPPED — it is not a frame;
   *   · a framed line that does not parse is likewise torn and drops the rest
   *     of the file from consideration (replay stops there).
   */
  private def readJournalRecords(path: os.Path): List[Note] =
    val j = journalPathOf(path)
    if !os.exists(j) then Nil
    else
      val text =
        try os.read(j)
        catch case _: Exception => return Nil
      // Only lines that END with '\n' are framed records; the tail after the
      // last '\n' (which may be '' when the file ends cleanly) is discarded.
      val framed = text.split("\n", -1).toList.dropRight(1)
      val out = scala.collection.mutable.ListBuffer.empty[Note]
      var stop = false
      framed.foreach { l =>
        if !stop then
          if l.isEmpty then stop = true
          else
            scala.util.Try(io.circe.parser.parse(l)).toOption match
              case Some(_) => out += Note(l, recordBytes(l))
              case None => stop = true     // torn frame: discard it and everything after
      }
      out.toList

end AtomicJson
