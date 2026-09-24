package nebflow.core.project

import io.circe.Codec
import io.circe.syntax.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode

import java.time.Instant

import scala.collection.mutable

import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.core.tools.ToolError

/** A unified-ledger entry (wire schema, three states).
  *
  * 🔴 **No `note` field**: the note timeline is **not on the entry** -- it is a separate
  * engine-side data face (the `note`-class events of [[TaskLedgerHistory]]), whose only
  * write path is the automatic append on every Mail from Nebula to that task's dispatcher.
  * The `Task` tool has **no** note parameter (structurally it cannot write one).
  * `withDefaults`: missing fields fall back to defaults (zero migration); unknown keys are
  * tolerated. */
case class TaskEntry(
  id: String,
  title: String,
  status: String = TaskLedgerStore.Status.Open,
  assignee: Option[String] = None, // node id | "dispatcher" | "author"
  nodeId: Option[String] = None,   // -> optional one-way link to a Flow Map node
  links: List[String] = Nil,       // free-form anchors (doc paths / commits / cross-ledger refs), **not validated for reachability**
  blocks: List[String] = Nil,      // ids this entry **depends on** (the dependency gate reads only this field)
  parentId: Option[String] = None, // containment (sub-task), orthogonal to blocks
  project: Option[String] = None,  // **free-form tag** (not a storage location -- the ledger is one instance-wide file)
  createdAt: Option[String] = None,
  updatedAt: Option[String] = None,
  closedAt: Option[String] = None,    // when `closed` was reached
  completedAt: Option[String] = None  // when `completed` was reached
)
object TaskEntry:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskEntry] = ConfiguredCodec.derived

/** The top-level shape of `tasks-v2.json`: version + tasks + nextId (**byte-for-byte
  * isomorphic** with the two legacy ledgers).
  *
  * `nextId` = the **monotonic id watermark** (the last id handed out; 0 = none ever). A
  * missing key falls back to 0 (zero-migration read-in); **the legacy ledgers' watermarks
  * are NOT inherited** (this ledger starts at 0 ⇒ the first create = 1). */
case class TaskLedgerData(version: Int = 1, tasks: List[TaskEntry] = Nil, nextId: Int = 0)
object TaskLedgerData:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskLedgerData] = ConfiguredCodec.derived

/**
 * TaskLedgerStore -- the unified-ledger store (taskunify batch, 2026-09-24).
 *
 * Role: the **single state machine and persistence layer** of the `Task` tool (Nebula's
 * exclusive write face) -- replacing the retired `TaskListStore`
 * (`~/.nebflow/tasks.json`, the orchestration face) and `TaskBoardStore`
 * (`<workspace>/.nebflow/task-board.json`, the board face). One file replaces two:
 * **one ledger, one id space, one change-history file**.
 *
 * -- Physical shape (ruling L) --
 * A **single global file** `~/.nebflow/tasks-v2.json` (derived from `PathUtil.dataRoot` ⇒
 * isolated instances re-root and are test-isolated the same way). The top-level
 * `{version, tasks, nextId}` is **byte-for-byte isomorphic** with the two legacy ledgers
 * (reusing the current read/write primitives and rendering face keeps the change surface
 * minimal); entries carry a `project` field (a free-form tag, **not** a storage location --
 * the ledger is one instance-wide file). Attribution of a single entry is **filtered at
 * read time**, **no materialized view**.
 *
 * 🔴 **Zero deletion of the two legacy ledgers**: `~/.nebflow/tasks.json` and
 * `<ws>/.nebflow/task-board.json` (including their history files) stay **untouched, no
 * deletion, no overwrite**, kept as a **read-only archive** for later inspection; this
 * module does **not** read or write them. Watermarks are **not inherited** (this ledger
 * starts at 0; the first create = `max(∅,0)+1 = 1`).
 *
 * -- Three-state machine (ruling a①, replacing two four-state machines) --
 * `{open, closed, completed}` replaces `{open, in_progress, done, blocked}`.
 * - `open` → `completed` (achieved) / `open` → `closed` (voided / withdrawn)
 * - `closed` → `completed` = **legal** (an entry voided earlier turns out to have been
 *   achieved ⇒ promote it with `complete`, **rather than** creating a duplicate entry)
 * - `completed` = **terminal, no out-edges** (any out-edge is rejected)
 * - same-state write = no-op success (idempotent, including `completed→completed` /
 *   `closed→closed`)
 * - **no `in_progress`, no `blocked`**: progress and waiting are expressed on the note
 *   timeline, not as states. ⇒ the state machine is **total**: every entry is either still
 *   open, voided, or achieved.
 * - `update`'s `status` parameter is **deleted entirely** (the single channel is enforced
 *   at the schema level by "the parameter does not exist")
 *
 * -- Dependency gate (ruling `dep-escape`) --
 * `blocks` = the ids this entry **depends on**. `complete` is rejected while any dependency
 * has **not reached a terminal state** (`closed` AND `completed` **both count as closed** --
 * counting only one would let tasks in the other terminal state block downstream forever);
 * **`close` is never gated** (withdrawal is always available -- that is the escape hatch
 * when dependencies are stuck). Cycle detection (including self-dependency) and unknown
 * dependency ids are rejected.
 *
 * -- Sub-tasks (parentId) and dependencies (blocks) --
 * `parentId` = containment (a sub-task of ...; depth ≤5, cycles and unknown ids rejected);
 * `blocks` = ordering. The two are **orthogonal** and may coexist. A parent's state is
 * **never derived**: all sub-tasks reaching terminal does **not** auto-complete the parent,
 * and completing the parent does **not** rewrite the sub-tasks.
 *
 * -- id and lifecycle --
 * Ids are **never reused**: the ledger holds a monotonic watermark `nextId` ⇒ after an entry
 * is pruned its id is not handed to another task (an id always maps to exactly one task's
 * timeline). A terminal entry is pruned lazily **30 days after it reached terminal**, on the
 * **next `create`**; its change history stays readable after the prune (`show <id>` renders
 * the timeline in "archive hit" degraded mode, marked `[gone]`).
 *
 * -- Corruption tolerance --
 * Read corruption → empty view + WARN (zero side effects); write corruption → quarantine
 * rename to `tasks-v2.json.corrupt-<millis>` (all old bytes preserved) + continue on an
 * empty store (**never a silent overwrite**).
 *
 * -- Locking --
 * One in-instance lock: held for the whole mutation+persist (serialization covers the disk
 * write completing). No cross-process locking (single-host premise, the same trade-off as
 * the two legacy ledgers).
 */
class TaskLedgerStore private ():
  import TaskLedgerStore.*

  /** `def` not `val`: `PathUtil.dataRoot` can be re-rooted by tests (`setDataRoot`). */
  private def file: os.Path = PathUtil.dataRoot / FileName

  /** Change history (a single global file; stateless, lock-free -- all appends happen
    * inside the fileLock section). */
  private val history = TaskLedgerHistory.open()

  /** One in-instance lock (the same trade-off as the two legacy ledgers). The read path
    * takes no lock: AtomicJson swaps in atomically, so a reader only ever sees the old or
    * the new complete file. */
  private val fileLock = new Object

  // ------------------------------------------------------------------
  // Read / write primitives
  // ------------------------------------------------------------------

  private def readSync(): Either[String, Store] =
    if !os.exists(file) then Right(Store())
    else
      scala.util.Try(decode[Store](os.read(file))) match
        case scala.util.Success(Right(store)) => Right(store)
        case scala.util.Success(Left(err))    => Left(err.getMessage)
        case scala.util.Failure(e)            => Left(e.getMessage)

  private def readViewSync(): Store =
    readSync() match
      case Right(store) => store
      case Left(reason) =>
        logger.warnSync(
          s"[taskledger] tasks-v2.json corrupted, read path degrades to empty view: $reason")
        Store()

  private def readForWriteSync(): (Store, Option[String]) =
    readSync() match
      case Right(store) => (store, None)
      case Left(reason) =>
        val quarantine = os.Path(s"${file}.corrupt-${System.currentTimeMillis()}")
        val renamed =
          try
            os.move(file, quarantine)
            Some(quarantine.last)
          catch case _: Exception => None
        logger.warnSync(
          s"[taskledger] tasks-v2.json corrupted ($reason) — quarantined to ${renamed.getOrElse("(rename failed, proceeding on empty store)")}, starting fresh store")
        (Store(), renamed)

  private def writeSync(store: Store): Unit =
    AtomicJson.writeSync(file, store.asJson.noSpaces)

  private def notFound(store: Store, id: String): ToolError =
    val open = store.tasks.filter(_.status == Status.Open)
    val hint = if open.isEmpty then "(no open entries)"
      else open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
    ToolError(
      s"""Task: no entry #$id. Open entries: $hint — pick an id from action=list. (${Codes.NotFound})""")

  // ------------------------------------------------------------------
  // Change-history writes
  // ------------------------------------------------------------------

  /** Append one history event; success = "", failure = `" NOTE: history append failed
    * (<reason>)"`. The main operation does not fail because the history failed (the tool
    * call face is already recorded by ToolsLogWriter), but it **must be stated explicitly
    * on the result line** (never silently). */
  private def appendHistory(ev: TaskLedgerEvent): String =
    history.appendSync(ev).map(r => s" NOTE: history append failed ($r)").getOrElse("")

  private def histNoteFor(id: String): String =
    s" History: ${history.file} (see it with action=show id=$id)."

  /** The existing result text for a prune. */
  private def pruneNoteOf(pruned: List[Entry], quarantined: Option[String]): String =
    (pruned.size, quarantined) match
      case (0, None)    => ""
      case (n, None)    => s" Pruned $n terminal entr${if n == 1 then "y" else "ies"} older than ${TerminalTtlDays}d."
      case (0, Some(q)) => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q; fresh ledger started."
      case (n, Some(q)) => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q. Pruned $n stale terminal entries."

  private def pruneHistory(pruned: List[Entry]): String =
    pruned
      .map(e =>
        appendHistory(TaskLedgerEvent(
          at = nowStr, kind = TaskLedgerHistory.Kinds.Prune, id = Some(e.id),
          actor = TaskLedgerHistory.Actors.System,
          detail = Some(s"status=${e.status} title=${truncate(e.title, 80)}"))))
      .mkString

  private def quarantineHistory(quarantined: Option[String]): String =
    quarantined
      .map(q =>
        appendHistory(TaskLedgerEvent(
          at = nowStr, kind = TaskLedgerHistory.Kinds.Quarantine,
          actor = TaskLedgerHistory.Actors.System,
          detail = Some(s"tasks-v2.json unreadable — quarantined as $q; fresh ledger started"))))
      .getOrElse("")

  // ------------------------------------------------------------------
  // The six actions
  // ------------------------------------------------------------------

  /** create: a new entry (status = `open`). Returns the entry's number. */
  def createSync(
    title: String,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    blocksRaw: Option[List[String]] = None,
    linksRaw: Option[List[String]] = None,
    parentId: Option[String] = None,
    project: Option[String] = None,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val links = linksRaw.map(normalizeLinks).getOrElse(Nil)
    val invalid =
      if title.trim.isEmpty then Some(ToolError(s"Task: create requires a non-empty `title`. (${Codes.Param})"))
      else checkTextLimit("title", Some(title), TitleWriteMaxChars, "Shorten the title (≤300 chars).")
        .orElse(checkLinks(links))
    invalid match
      case Some(err) => Left(err)
      case None =>
        val (base, quarantined) = readForWriteSync()
        val (store, pruned) = pruneTerminal(base)
        val blocks = blocksRaw.map(normalizeBlocks).getOrElse(Nil)
        val parent = parentId.map(_.trim).filter(_.nonEmpty)
        for
          _ <- checkBlockIds(store, blocks).toLeft(())
          _ <- checkParent(store, None, parent).toLeft(())
          nextId = nextNumId(store)
          now = nowStr
          entry = Entry(
            id = nextId.toString,
            title = title.trim,
            status = Status.Open,
            assignee = Some(assignee.map(_.trim).filter(_.nonEmpty).getOrElse(Assignee.Dispatcher)),
            nodeId = nodeId.map(_.trim).filter(_.nonEmpty),
            links = links,
            blocks = blocks,
            parentId = parent,
            project = project.map(_.trim).filter(_.nonEmpty),
            createdAt = Some(now),
            updatedAt = Some(now)
          )
          _ <- {
            writeSync(store.copy(tasks = store.tasks :+ entry, nextId = nextId))
            Right(())
          }
        yield
          val depsNote = if blocks.nonEmpty then s" (deps: ${blocks.map("#" + _).mkString(", ")})" else ""
          val parentNote = parent.map(p => s" (parent: #$p)").getOrElse("")
          val extra = pruneNoteOf(pruned, quarantined) + quarantineHistory(quarantined) + pruneHistory(pruned) +
            appendHistory(TaskLedgerEvent(
              at = now, kind = TaskLedgerHistory.Kinds.Create, id = Some(entry.id), actor = actor,
              links = links, detail = Some(s"title=${truncate(entry.title, 120)}"))) + histNoteFor(entry.id)
          s"""[OK] Task created #$nextId [open @${entry.assignee.getOrElse("?")}] ${truncate(entry.title, 60)}$depsNote$parentNote$extra
             |Reach `completed` with action=complete; `closed` (voided) with action=close.""".stripMargin
  }

  /** update: **non-state fields only** (a state is reachable only through
    * `complete`/`close`; the `status` parameter was deleted entirely). An empty string =
    * clear (`assignee`/`nodeId`/`parentId`/`project`); `links`/`blocks` = **full
    * replacement** (`[]` clears). */
  def updateSync(
    id: String,
    title: Option[String] = None,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    blocksRaw: Option[List[String]] = None,
    linksRaw: Option[List[String]] = None,
    parentId: Option[String] = None,
    project: Option[String] = None,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None => Left(notFound(store, id))
      case Some(existing) =>
        val newBlocks = blocksRaw.map(normalizeBlocks).getOrElse(existing.blocks)
        val newLinks = linksRaw.map(normalizeLinks).getOrElse(existing.links)
        val invalid =
          checkTextLimit("title", title, TitleWriteMaxChars, "Shorten the title (≤300 chars).")
            .orElse(checkLinks(newLinks))
        invalid match
          case Some(err) => Left(err)
          case None =>
            for
              _ <- checkBlockIds(store, newBlocks).toLeft(())
              _ <-
                // Cycle detection: run DFS over the "post-change" edge set (including self-dependency)
                val edgeSet = store.tasks.map(t => if t.id == existing.id then t.copy(blocks = newBlocks) else t)
                if hasCycle(edgeSet) then
                  Left(ToolError(
                    s"Task: blocks update for #$id would create a dependency cycle (self-reference or loop). (${Codes.Cycle})"))
                else Right(())
              newParent = parentId.map(_.trim).filter(_.nonEmpty).orElse(existing.parentId)
              _ <- checkParent(store, Some(existing), parentId.map(_.trim).filter(_.nonEmpty)).toLeft(())
              now = nowStr
              updated = existing.copy(
                title = title.map(_.trim).filter(_.nonEmpty).getOrElse(existing.title),
                assignee = assignee match
                  case Some(a) => Some(a.trim).filter(_.nonEmpty)
                  case None    => existing.assignee,
                nodeId = nodeId match
                  case Some(n) => Some(n.trim).filter(_.nonEmpty)
                  case None    => existing.nodeId,
                blocks = newBlocks,
                links = newLinks,
                parentId = parentId match
                  case Some(p) => Some(p.trim).filter(_.nonEmpty)
                  case None    => existing.parentId,
                project = project match
                  case Some(p) => Some(p.trim).filter(_.nonEmpty)
                  case None    => existing.project,
                updatedAt = Some(now)
              )
              _ <- {
                writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
                Right(())
              }
            yield
              val qNote = quarantined.map(q => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q.").getOrElse("")
              val hist = appendHistory(TaskLedgerEvent(
                at = now, kind = TaskLedgerHistory.Kinds.Update, id = Some(id), actor = actor,
                detail = Some(updateDetail(existing, updated))))
              s"[OK] Task updated #$id$qNote$hist${histNoteFor(id)}"
  }

  /** complete: reach `completed` (achieved). **Legal from `open` and from `closed`**;
    * already `completed` = idempotent no-op success. Dependency gate: any dependency not
    * yet terminal ⇒ rejected. */
  def completeSync(
    id: String,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None => Left(notFound(store, id))
      case Some(existing) if existing.status == Status.Completed =>
        Right(s"[OK] Task #$id already completed at ${existing.completedAt.getOrElse("(unknown time)")} — no-op.")
      case Some(existing) =>
        checkDepsTerminal(store, existing.blocks, "complete", id) match
          case Some(err) => Left(err)
          case None =>
            val now = nowStr
            val updated = existing.copy(status = Status.Completed, completedAt = Some(now), updatedAt = Some(now))
            writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
            val qNote = quarantined.map(q => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q.").getOrElse("")
            val hist = appendHistory(TaskLedgerEvent(
              at = now, kind = TaskLedgerHistory.Kinds.Complete, id = Some(id), actor = actor,
              detail = Some(s"${existing.status}→completed")))
            Right(s"[OK] Task completed #$id ${existing.status}→completed$qNote$hist")
  }

  /** close: reach `closed` (voided / withdrawn). Already `closed` = idempotent no-op
  * success; **rejected when `completed`** (terminal). 🔴 **The dependency gate never
  * applies to close** -- withdrawal is always available. */
  def closeSync(
    id: String,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None => Left(notFound(store, id))
      case Some(existing) if existing.status == Status.Completed =>
        Left(ToolError(
          s"Task: #$id is `completed` — the terminal state has no transitions out, so it cannot be closed. " +
            s"Achieved work is not withdrawable; create a new entry if you need one. (${Codes.Status})"))
      case Some(existing) if existing.status == Status.Closed =>
        Right(s"[OK] Task #$id already closed at ${existing.closedAt.getOrElse("(unknown time)")} — no-op.")
      case Some(existing) =>
        val now = nowStr
        val updated = existing.copy(status = Status.Closed, closedAt = Some(now), updatedAt = Some(now))
        writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
        val qNote = quarantined.map(q => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q.").getOrElse("")
        val hist = appendHistory(TaskLedgerEvent(
          at = now, kind = TaskLedgerHistory.Kinds.Close, id = Some(id), actor = actor,
          detail = Some(s"${existing.status}→closed")))
        Right(s"[OK] Task closed #$id ${existing.status}→closed (voided/withdrawn)$qNote$hist")
  }

  /** list: render entries (state + dependencies). Optional exact filters
    * (status/assignee/project). */
  def listSync(
    status: Option[String] = None,
    assignee: Option[String] = None,
    project: Option[String] = None,
    nodeTerminal: Map[String, String] = Map.empty
  ): Either[ToolError, String] =
    val store = readViewSync()
    val filtered = store.tasks
      .filter(t => status.forall(_ == t.status))
      .filter(t => assignee.forall(t.assignee.contains))
      .filter(t => project.forall(t.project.contains))
    if filtered.isEmpty then
      val scope = List(
        status.map(s => s"status=$s"), assignee.map(a => s"assignee=$a"), project.map(p => s"project=$p")
      ).flatten.mkString(" ")
      Right(s"Task ledger — empty${if scope.isEmpty then "" else s" matching $scope"} (no entries). Create one with action=create.")
    else
      val lines = filtered.map(t => TaskLedgerRenderer.compactLine(t, store.tasks, nodeTerminal))
      val openCount = filtered.count(_.status == Status.Open)
      Right(
        s"""Task ledger — ${filtered.size} entr${if filtered.size == 1 then "y" else "ies"} ($openCount open)
           |${lines.mkString("\n")}""".stripMargin)

  /** show: one entry's full fields + links + current dependency states + reverse
    * dependencies + parent chain / direct sub-entries + the note timeline. If the main
    * ledger no longer has the id but the history still does ⇒ "archive hit" degraded
    * rendering (**do not error out**). */
  def showSync(
    id: String,
    nodeTerminal: Map[String, String] = Map.empty
  ): Either[ToolError, String] =
    val store = readViewSync()
    val notes = history.readFor(id, TaskLedgerRenderer.TimelineNoteMaxVersions, TaskLedgerHistory.isNoteEvent)
    val states = history.readFor(id, TaskLedgerRenderer.TimelineStateMaxLines, ev => !TaskLedgerHistory.isNoteEvent(ev))
    store.tasks.find(_.id == id) match
      case Some(entry) =>
        Right(TaskLedgerRenderer.renderShow(entry, store.tasks, nodeTerminal, notes, states))
      case None =>
        if history.countLinesFor(id) > 0 then
          Right(TaskLedgerRenderer.renderArchived(id, store.tasks, notes, states))
        else Left(notFound(store, id))

  // ------------------------------------------------------------------
  // Note timeline (the engine-side only write path; the tool layer has no note parameter)
  // ------------------------------------------------------------------

  /** The engine automatically appends one note-timeline record on **every Mail from
    * Nebula to that task's dispatcher**.
    *
    * Shape (ruling n ⓒ, structured) = `from` (origin domain) + timestamp (`at`) + body
    * excerpt. The per-item cap [[NoteCapChars]] = **16,000 characters** (taken from
    * `TaskBoardStore.scala:555`, abandoning the orchestration face's old 2,000 -- 🔴 a
    * **behaviour-visible change**). **Over-long bodies are chunked, never truncated**: the
    * body is split into several chunks at paragraph boundaries (the first carrying a
    * `(part k/n)` marker) and **the full text stays in the history** -- 🔴 "truncate and
    * drop" is forbidden (the legacy `TaskListStore`'s 2,000 cap rejected the write; here it
    * is accepted by chunking).
    *
    * Returns `Left` only when the entry does not exist or the history append failed (a
    * note's payload IS the history itself, so a silent success would lose the record). */
  def appendNoteSync(
    id: String,
    text: String,
    from: String = TaskLedgerHistory.Origins.Nebula,
    links: List[String] = Nil,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val body = text.trim
    if body.isEmpty then
      Left(ToolError(s"Task: note append requires a non-empty body (the Mail message). (${Codes.Param})"))
    else
      val store = readViewSync()
      store.tasks.find(_.id == id) match
        case None => Left(notFound(store, id))
        case Some(existing) =>
          val chunks = chunkNote(body, NoteCapChars)
          val now = nowStr
          var firstFailure: Option[String] = None
          chunks.zipWithIndex.foreach { case (chunk, i) =>
            val partMark = if chunks.size == 1 then "" else s" (part ${i + 1}/${chunks.size})"
            val ev = TaskLedgerEvent(
              at = now, kind = TaskLedgerHistory.Kinds.Note, id = Some(id), actor = actor,
              from = Some(from), text = Option(chunk + partMark), links = links,
              detail = Some(s"chars=${chunk.length}${if chunks.size == 1 then "" else s" of ${body.length}"} title=${truncate(existing.title, 60)}"))
            history.appendSync(ev) match
              case Some(reason) if firstFailure.isEmpty => firstFailure = Some(reason)
              case _                                    => ()
          }
          firstFailure match
            case Some(reason) =>
              Left(ToolError(
                s"Task: note for #$id could not be persisted — history append failed ($reason) at ${history.file}. " +
                  s"Nothing was recorded (the ledger is unchanged). Retry, or fix the path/permissions. (${Codes.History})"))
            case None =>
              val segNote = if chunks.size == 1 then "" else s" (${chunks.size} segments)"
              Right(s"[OK] Task #$id note appended from=$from (${body.length} chars$segNote).${histNoteFor(id)}")
  }

  /** A snapshot of all ledger entries (the read entry point; used for injection rendering
    * and attribution resolution). */
  def entriesSync(): List[TaskEntry] = readViewSync().tasks

  /** Fetch one entry by id (used for attribution resolution / TaskInfo). */
  def findSync(id: String): Option[TaskEntry] = readViewSync().tasks.find(_.id == id)

  /** The one-line open summary injected globally (taskunify batch 2026-09-24, replacing
    * the data source of `TaskListStore.openSummaryLine`): the **Nebula face keeps an open
    * summary** (one of the read faces of ruling n) now pointing at the unified ledger. 🔴
    * The legacy `~/.nebflow/tasks.json` is **no longer read** by new code. No open entries
    * ⇒ "" (no resident noise line). */
  def openSummaryLine(): String =
    val store = readViewSync()
    val open = store.tasks.filter(_.status == Status.Open)
    if open.isEmpty then ""
    else
      val shown = open.take(5).map { t =>
        val proj = t.project.map(p => s"@$p").getOrElse("")
        s"#${t.id}[${t.status}] ${truncate(t.title, 40)}$proj"
      }
      val more = if open.size > 5 then s" (+${open.size - 5} more)" else ""
      val line = s"[Task] ${open.size} open task(s): ${shown.mkString(" | ")}$more — details: Task(action=list|show)"
      truncate(line, 600)

end TaskLedgerStore

// ---------------------------------------------------------------------------
// companion: constants / pure validators / singleton
// ---------------------------------------------------------------------------

object TaskLedgerStore:

  private[project] val logger = NebflowLogger.forName("nebflow.taskledger")

  /** The unified ledger's physical path (the value fixed by ruling L; 🔴 **never
    * overwrite** `~/.nebflow/tasks.json`). */
  val FileName: String = "tasks-v2.json"

  /** The three-state constants (the single source of the wire format, ruling a①). */
  object Status:
    val Open      = "open"
    val Closed    = "closed"
    val Completed = "completed"
    val all: Set[String] = Set(Open, Closed, Completed)

    /** Terminal test (the single point of the dependency-gate criterion): **both terminal
      * states count as closed** -- `completed` (achieved) and `closed` (voided). Counting
      * only one would let tasks in the other terminal state block downstream forever. */
    def isTerminal(s: String): Boolean = s != Open
  end Status

  /** Reserved special values for assignee (a node id is used as the raw `NodeDef.id`). */
  object Assignee:
    val Dispatcher = "dispatcher"
    val Author     = "author"

  /** The `TASK_*` error-code family (centralized here; the tool layer and this file quote
    * the same source). */
  object Codes:
    val Param           = "TASK_PARAM"
    val Status          = "TASK_STATUS"
    val Blocked         = "TASK_BLOCKED"
    val BlockUnknown    = "TASK_BLOCK_UNKNOWN"
    val Cycle           = "TASK_CYCLE"
    val ParentCycle     = "TASK_PARENT_CYCLE"
    val ParentUnknown   = "TASK_PARENT_UNKNOWN"
    val ParentDepth     = "TASK_PARENT_DEPTH"
    val Forbidden       = "TASK_FORBIDDEN"
    val NotFound        = "TASK_NOT_FOUND"
    val History         = "TASK_HISTORY"
    val NoAttachment    = "TASKINFO_NO_ATTACHMENT"

  /** Terminal-entry retention (cleaned up lazily on `create`). */
  val TerminalTtlDays: Long = 30L

  /** The per-note cap = **16,000 characters** (the value fixed by ruling n; taken from the
    * board face's current code at `TaskBoardStore.scala:555`, abandoning the orchestration
    * face's old 2,000). 🔴 **Behaviour-visible change**: the orchestration face's old 2,000
    * cap **rejected** an over-long note; this ledger instead **accepts it by chunking**
    * (the full text stays in the history) rather than rejecting the write. */
  val NoteCapChars: Int = 16_000

  /** The `title` cap for a single write (same value as both legacy faces: 300). */
  val TitleWriteMaxChars: Int = 300

  /** The `links` count cap (20 items × 300 chars each = 6 KB worst case). */
  val LinksWriteMax: Int = 20
  val LinkWriteMaxChars: Int = 300

  /** Maximum sub-task chain depth. */
  val ParentMaxDepth: Int = 5

  type Entry = TaskEntry
  val Entry = TaskEntry

  type Store = TaskLedgerData
  val Store = TaskLedgerData

  /** Singleton (a single global file ⇒ not per-workspace). */
  val instance: TaskLedgerStore = new TaskLedgerStore()

  def open(): TaskLedgerStore = instance

  /** Pure function: chunk a note body (**never truncate** -- an over-long body is split
    * into several chunks and the full text enters the history). The cut point prefers a
    * newline / whitespace boundary (to avoid cutting a word in half); if none is found it
    * hard-cuts at the cap. */
  private[project] def chunkNote(body: String, cap: Int): List[String] =
    if body.length <= cap then List(body)
    else
      val out = scala.collection.mutable.ListBuffer[String]()
      var rest = body
      while rest.length > cap do
        val window = rest.take(cap)
        val cut =
          val nl = window.lastIndexOf('\n')
          val sp = window.lastIndexOf(' ')
          if nl > cap / 2 then nl + 1 else if sp > cap / 2 then sp + 1 else cap
        out += rest.take(cut)
        rest = rest.drop(cut)
      if rest.nonEmpty then out += rest
      out.toList

  // ------------------------------------------------------------------
  // Pure validators
  // ------------------------------------------------------------------

  /** The state-transition matrix (ruling a①). Same state = allowed as a no-op
    * (idempotent). `completed` has no out-edges; `closed → completed` is **legal**. */
  private[project] def isValidTransition(from: String, to: String): Boolean =
    (from, to) match
      case (f, t) if f == t                => true // no-op (including the completed→completed idempotent case)
      case (Status.Open, Status.Completed) => true
      case (Status.Open, Status.Closed)    => true
      case (Status.Closed, Status.Completed) => true // ruling a①: an entry voided earlier turns out achieved ⇒ promote it
      case _                               => false // completed→anything (terminal has no out-edges), etc.

  /** Dependency-graph cycle detection (id → blocks edges; including self-dependency).
    * DFS + a recursion stack. */
  private[project] def hasCycle(tasks: List[Entry]): Boolean =
    val adj = tasks.map(t => t.id -> t.blocks.filter(_.nonEmpty)).toMap
    val visited = mutable.Set[String]()
    val recStack = mutable.Set[String]()

    def dfs(id: String): Boolean =
      visited += id
      recStack += id
      val found = adj.getOrElse(id, Nil).exists { n =>
        if !visited.contains(n) then dfs(n)
        else if recStack.contains(n) then true
        else false
      }
      recStack -= id
      found

    adj.keys.exists(id => !visited.contains(id) && dfs(id))
  end hasCycle

  /** parentId chain cycle detection (including self-dependency): walking up the parent
    * edges, hitting yourself again is a cycle. */
  private[project] def hasParentCycle(tasks: List[Entry]): Boolean =
    val parentOf = tasks.map(t => t.id -> t.parentId.filter(_.nonEmpty)).toMap
    parentOf.keys.exists { start =>
      var cur = parentOf.getOrElse(start, None)
      var steps = 0
      var cyc = false
      val seen = mutable.Set[String](start)
      while cur.isDefined && !cyc && steps <= tasks.size + 1 do
        val c = cur.get
        if seen.contains(c) then cyc = true
        else
          seen += c
          cur = parentOf.getOrElse(c, None)
        steps += 1
      cyc
    }

  /** parent chain depth (counting while walking up the parent edges; cycle prevention is
    * handled separately by [[hasParentCycle]]). */
  private[project] def parentDepth(tasks: List[Entry], id: String): Int =
    val parentOf = tasks.map(t => t.id -> t.parentId.filter(_.nonEmpty)).toMap
    var cur = parentOf.getOrElse(id, None)
    var d = 0
    val seen = mutable.Set[String](id)
    while cur.isDefined && d <= tasks.size + 1 do
      val c = cur.get
      if seen.contains(c) then cur = None
      else
        seen += c
        d += 1
        cur = parentOf.getOrElse(c, None)
    d

  /** Normalize the blocks list: trim whitespace, deduplicate. */
  private[project] def normalizeBlocks(raw: List[String]): List[String] =
    raw.map(_.trim).filter(_.nonEmpty).distinct

  /** Normalize the links list: trim whitespace, deduplicate (**no reachability check**). */
  private[project] def normalizeLinks(raw: List[String]): List[String] =
    raw.map(_.trim).filter(_.nonEmpty).distinct

  /** Write-side text-length validation. */
  private[project] def checkTextLimit(field: String, value: Option[String], max: Int, fix: String): Option[ToolError] =
    value.map(_.trim).filter(_.nonEmpty).filter(_.length > max).map { v =>
      ToolError(s"Task: `$field` is ${v.length} chars — the write-side limit is $max chars. $fix (${Codes.Param})")
    }

  /** links capacity validation (item count + per-item length). Existence is not
    * validated. */
  private[project] def checkLinks(links: List[String]): Option[ToolError] =
    if links.size > LinksWriteMax then
      Some(ToolError(
        s"Task: too many `links` (${links.size}) — max $LinksWriteMax per entry. " +
          s"Keep the most relevant anchors (paths/commits/ids) and drop the rest. (${Codes.Param})"))
    else
      links.find(_.length > LinkWriteMaxChars).map { l =>
        ToolError(
          s"Task: link '${truncate(l, 60)}' is ${l.length} chars — max $LinkWriteMaxChars per link. " +
            s"Use a path / commit hash / id, not free-form prose. (${Codes.Param})")
      }

  /** Dependency id existence validation. */
  private[project] def checkBlockIds(store: Store, blocks: List[String]): Option[ToolError] =
    val known = store.tasks.map(_.id).toSet
    blocks.find(!known.contains(_)).map { unknown =>
      val knownList = if known.isEmpty then "(none — ledger is empty)" else known.map("#" + _).mkString(", ")
      ToolError(
        s"Task: dependency id '#$unknown' does not exist. Known ids: $knownList. " +
          s"Fix the id or drop it. (${Codes.BlockUnknown})")
    }

  /** Dependency gate (ruling `dep-escape`): on `complete`, everything in `blocks` must have
    * **reached either terminal state** (`closed` AND `completed` both count as closed).
    * **`close` never passes through this gate** (always available). */
  private[project] def checkDepsTerminal(store: Store, blocks: List[String], action: String, id: String): Option[ToolError] =
    val open = blocks.flatMap(dep => store.tasks.find(_.id == dep)).filterNot(e => Status.isTerminal(e.status))
    if open.isEmpty then None
    else
      val listing = open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
      Some(ToolError(
        s"Task: cannot $action #$id — dependency(ies) not terminal yet: $listing. " +
          s"Complete or close them first. (Withdrawing is the escape hatch: `close` is NEVER gated.) (${Codes.Blocked})"))

  /** parentId validation (on create / update): existence + cycle + depth ≤
    * `ParentMaxDepth`. `self` = the entry itself in an update scenario (to prevent setting
    * parent to itself or a descendant, which would form a cycle); in a `create` scenario
    * `None` is passed (the candidate entry is not registered yet, so a sentinel id takes
    * part in the depth computation). */
  private[project] def checkParent(store: Store, self: Option[Entry], parent: Option[String]): Option[ToolError] =
    parent match
      case None => None
      case Some(p) =>
        if !store.tasks.exists(_.id == p) then
          Some(ToolError(s"Task: parent id '#$p' does not exist. (${Codes.ParentUnknown})"))
        else if self.exists(_.id == p) then
          Some(ToolError(s"Task: parent cannot be the entry itself (#$p). (${Codes.ParentCycle})"))
        else if self.exists(e => isDescendant(store, p, e.id)) then
          // parent is a descendant of itself ⇒ a cycle (self → ... → p → self)
          Some(ToolError(
            s"Task: setting parent=#$p on #${self.get.id} would create a parent cycle (it is a descendant of this entry). (${Codes.ParentCycle})"))
        else
          val candidate = self match
            case Some(e) => store.tasks.map(t => if t.id == e.id then t.copy(parentId = Some(p)) else t)
            case None    => store.tasks :+ Entry(id = NewEntrySentinel, title = "(new)", parentId = Some(p))
          val focus = self.map(_.id).getOrElse(NewEntrySentinel)
          if hasParentCycle(candidate) then
            Some(ToolError(s"Task: setting parent=#$p would create a parent cycle. (${Codes.ParentCycle})"))
          else
            val d = parentDepth(candidate, focus)
            if d > ParentMaxDepth then
              Some(ToolError(s"Task: parent chain depth $d exceeds the max of $ParentMaxDepth. (${Codes.ParentDepth})"))
            else None

  /** The sentinel id for a new entry (only participates in the parent-depth candidate
    * set; never persisted). */
  private[project] val NewEntrySentinel: String = "(new)"

  /** Whether `candidate` is a descendant of `ancestor` (the test walks up the parent
    * edges). */
  private[project] def isDescendant(store: Store, candidate: String, ancestor: String): Boolean =
    val parentOf = store.tasks.map(t => t.id -> t.parentId.filter(_.nonEmpty)).toMap
    var cur = parentOf.getOrElse(candidate, None)
    var found = false
    var steps = 0
    while cur.isDefined && !found && steps <= store.tasks.size + 1 do
      if cur.get == ancestor then found = true
      else cur = parentOf.getOrElse(cur.get, None)
      steps += 1
    found

  private[project] def truncate(s: String, max: Int): String =
    if s.length <= max then s else s.take(max) + "…"

  private[project] def nowStr: String = Instant.now().toString

  /** The change summary for an update (non-state fields only; a state is reachable only
    * through complete/close ⇒ no state is recorded here). */
  private[project] def updateDetail(before: Entry, after: Entry): String =
    val fields = scala.collection.mutable.ListBuffer[String]()
    if after.title != before.title then fields += "title"
    if after.assignee != before.assignee then fields += "assignee"
    if after.nodeId != before.nodeId then fields += "nodeId"
    if after.blocks != before.blocks then fields += "blocks"
    if after.links != before.links then fields += "links"
    if after.parentId != before.parentId then fields += "parentId"
    if after.project != before.project then fields += "project"
    if fields.isEmpty then "no-op (nothing changed)" else s"changed: ${fields.mkString(", ")}"

  /** The next id (**monotonic watermark**, ids never reused): `max(max of the existing
    * numeric ids, the persisted watermark nextId) + 1`. Legacy watermarks are **not
    * inherited** (this ledger starts at 0 ⇒ the first create = 1). */
  private[project] def nextNumId(store: Store): Int =
    math.max(store.tasks.flatMap(_.id.toIntOption).maxOption.getOrElse(0), store.nextId) + 1

  /** Lazy cleanup of terminal entries (more than 30 days since reaching terminal; kept
    * conservatively if parsing fails). Returns (after cleanup, the pruned entries) -- "an
    * entry dying" is itself traced, and the history file is never touched. */
  private[project] def pruneTerminal(store: Store): (Store, List[Entry]) =
    val cutoff = Instant.now().minusSeconds(TerminalTtlDays * 24 * 3600)
    def terminalAt(t: Entry): Option[Instant] =
      val raw = if t.status == Status.Completed then t.completedAt.orElse(t.closedAt) else t.closedAt.orElse(t.completedAt)
      raw.flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
    val (expired, kept) = store.tasks.partition { t =>
      Status.isTerminal(t.status) && terminalAt(t).exists(_.isBefore(cutoff))
    }
    (store.copy(tasks = kept), expired)
end TaskLedgerStore
