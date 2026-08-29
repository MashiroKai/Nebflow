package nebflow.core.task

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

import java.time.Instant
import scala.concurrent.duration.*

import scala.collection.mutable

trait TaskStore:
  def create(sessionId: String, input: TaskCreateInput): IO[String]
  def get(sessionId: String, taskId: String): IO[Option[Task]]
  def list(sessionId: String): IO[List[Task]]
  def listActive(sessionId: String): IO[List[Task]]
  def listVisible(sessionId: String): IO[List[Task]]
  def renderForPrompt(sessionId: String): IO[String]
  def update(sessionId: String, taskId: String, updates: TaskUpdateInput): IO[Option[Task]]
  def complete(sessionId: String, taskId: String, by: String): IO[Option[Task]]
  def delete(sessionId: String, taskId: String): IO[Boolean]
  def deleteAll(sessionId: String): IO[Unit]

/** Scope-key helpers for the team-domain task tools (spec
  * 20260825_team-manager-task-tool-spec.md §4.2 方案 A): the store API stays
  * keyed by a single string; team tasks pass `team:<name>` as the scope key
  * and FileTaskStore maps it to `tasks/teams/<name>/` (per-team directory +
  * independent high-water mark). Session keys pass through unchanged. */
object TaskStore:
  /** Build the team scope key for a team name (validated by the caller). */
  def teamScopeKey(teamName: String): String = s"team:$teamName"

  def isTeamScopeKey(key: String): Boolean = key.startsWith("team:")

  /** Path-safety guard for team names (defense-in-depth — production team
    * names come from validated team.json mounts, but the store must never
    * interpret a malicious key as a path traversal). */
  def isSafeTeamName(name: String): Boolean =
    name.nonEmpty && !name.contains("/") && !name.contains("\\") &&
      !name.contains("..") && !name.startsWith(".")

  // ---- TTL（任务工具重做 2026-08-30）----
  // completed/failed 过 6h 清、pending/in_progress 过 2d 清。判据=持久化的
  // createdAt/completedAt 字段（重启后扫描磁盘数据仍生效，非内存定时器）。
  val CompletedTtl: FiniteDuration = 6.hours
  val ActiveTtl: FiniteDuration = 2.days

  /** A task is expired under the TTL rules. Pure — used by both the
    * read-path visibility filter and the physical purge. Tasks without a
    * usable timestamp are never expired (conservative). */
  def isExpired(task: Task, now: Instant = Instant.now()): Boolean =
    def parseTs(raw: Option[String]): Option[Instant] =
      raw.flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
    task.status match
      case TaskStatus.Completed | TaskStatus.Failed =>
        parseTs(task.completedAt).orElse(parseTs(task.updatedAt)) match
          case Some(ts) => java.time.Duration.between(ts, now).toMillis >= CompletedTtl.toMillis
          case None     => false
      case TaskStatus.Pending | TaskStatus.InProgress =>
        parseTs(task.createdAt) match
          case Some(ts) => java.time.Duration.between(ts, now).toMillis >= ActiveTtl.toMillis
          case None     => false
  end isExpired
end TaskStore

object FileTaskStore extends TaskStore:
  private val logger = NebflowLogger.forName("nebflow.taskstore")

  /** `def` on purpose: PathUtil.dataRoot may be swapped after this object's
    * first use (tests inject a temp root) — a `val` would freeze the path at
    * class-init time (same trap as UsageTracker, 2026-08-14). */
  private def root: os.Path = PathUtil.dataRoot / "tasks"
  private val hwmFile = ".highwatermark"

  /** Cap on per-task event history — oldest entries are dropped beyond this. */
  private val MaxEvents = 50

  // Atomic high-water mark per scope (Issue #1) — keyed by scope key
  // (sessionId or "team:<name>"), so every team gets an independent id
  // sequence (spec §4.2: per-directory hwm).
  //
  // ⚠ LIFECYCLE NOTE (2026-08-30, hwmRef evaluation — Manager batch):
  // `hwmRef` is PROCESS-LEVEL (object singleton, keyed by scope STRING only),
  // while `root` above is a `def` that re-reads PathUtil.dataRoot on every
  // call. The two lifecycles DISAGREE when dataRoot is swapped mid-process:
  // the cached hwm for a scope survives the swap, so the NEXT create in that
  // scope reuses the old counter even though it now writes into a different
  // directory tree.
  //
  // Production is SAFE by construction: the only setDataRoot call is
  // Main.scala:19 (--home parsing, once, before any gateway/task activity);
  // canary runs are separate processes. There is NO mid-process root switch
  // in the product, so this mismatch cannot fire in production.
  //
  // TESTS are the hazard: two suites in one JVM both calling setDataRoot
  // share this singleton cache. If suite A pushes scope "team:X" hwm to 2,
  // then suite B swaps tempRoot and creates in "team:X", ids start at 3, not
  // 1 (qa FAIL 2026-08-30, batch-A member attribution). Test isolation for
  // task ids MUST use a unique scope key per suite (e.g. team:attribution-*),
  // never a key another suite owns. Do NOT "fix" by keying hwmRef on root —
  // the product path never needs it (see evaluation report to Manager).
  private val hwmRef: Ref[IO, Map[String, Int]] = Ref.unsafe(Map.empty)

  /** Map a scope key to its task directory:
    *   - "team:<name>"  → tasks/teams/<name>/   (team domain)
    *   - anything else  → tasks/<sessionId>/    (session domain, legacy path)
    * Throws IllegalArgumentException on an unsafe team name (defense against
    * path traversal — callers validate first and surface a ToolError).
    */
  private def scopePath(scopeKey: String): os.Path =
    if TaskStore.isTeamScopeKey(scopeKey) then
      val name = scopeKey.stripPrefix("team:")
      if !TaskStore.isSafeTeamName(name) then
        throw new IllegalArgumentException(s"Unsafe team name in task scope key: $name")
      root / "teams" / name
    else root / scopeKey

  private def sessionDir(sessionId: String): os.Path = scopePath(sessionId)

  private def taskFile(sessionId: String, taskId: String): os.Path =
    sessionDir(sessionId) / s"$taskId.json"

  private def ensureDir(sessionId: String): IO[Unit] = IO.blocking {
    val dir = sessionDir(sessionId)
    if !os.exists(dir) then os.makeDir.all(dir)
  }

  private def readHighWaterMark(sessionId: String): IO[Int] = IO.blocking {
    val f = sessionDir(sessionId) / hwmFile
    if os.exists(f) then os.read(f).trim.toIntOption.getOrElse(0)
    else 0
  }

  private def writeHighWaterMark(sessionId: String, value: Int): IO[Unit] =
    AtomicJson.write(sessionDir(sessionId) / hwmFile, value.toString)

  private def readTask(path: os.Path): IO[Option[Task]] = IO.blocking {
    if os.exists(path) then
      decode[Task](os.read(path)) match
        case Right(task) => Some(task)
        case Left(err) => None // Skip corrupted task files silently
    else None
  }

  private def writeTask(sessionId: String, task: Task): IO[Unit] =
    // Issue #23: atomic write (tmp + rename(2)) via AtomicJson — a bare
    // os.write.over can leave a truncated/partial JSON file if the process
    // dies mid-write; readers then silently drop the task (readTask skips
    // corrupted files).
    AtomicJson.write(taskFile(sessionId, task.id), task.asJson.noSpaces)

  // Issue #1: Atomic hwm using Ref
  private def initHwmIfNeeded(sessionId: String): IO[Unit] =
    hwmRef.get.map(_.contains(sessionId)).flatMap {
      case true => IO.unit
      case false =>
        readHighWaterMark(sessionId).flatMap { diskHwm =>
          hwmRef.update { hwms =>
            if hwms.contains(sessionId) then hwms
            else hwms.updated(sessionId, diskHwm)
          }
        }
    }

  private def nextId(sessionId: String): IO[String] =
    for
      _ <- initHwmIfNeeded(sessionId)
      next <- hwmRef.modify { hwms =>
        val current = hwms.getOrElse(sessionId, 0)
        val next = current + 1
        (hwms.updated(sessionId, next), next)
      }
      _ <- writeHighWaterMark(sessionId, next).handleErrorWith { e =>
        hwmRef.update(_.updated(sessionId, next - 1)) *>
          IO.raiseError(new RuntimeException(s"Failed to sync hwm for $sessionId: ${e.getMessage}", e))
      }
    yield next.toString

  def create(sessionId: String, input: TaskCreateInput): IO[String] =
    val now = Instant.now().toString
    // Team-domain tasks stamp scope/teamId from the scope key (方案 A — the
    // scope key IS the source of truth; the store API stays unchanged).
    val (scope, teamId) =
      if TaskStore.isTeamScopeKey(sessionId) then
        ("team", Some(sessionId.stripPrefix("team:")))
      else ("session", None)
    for
      _ <- ensureDir(sessionId)
      newId <- nextId(sessionId)
      task = Task(
        id = newId,
        subject = input.subject,
        description = input.description,
        activeForm = input.activeForm,
        status = TaskStatus.Pending,
        parentId = input.parentTaskId,
        createdAt = Some(now),
        updatedAt = Some(now),
        events = List(TaskEvent("created", None, Some(now))),
        taskKind = Task.normalizeTaskKind(input.taskKind),
        scope = scope,
        teamId = teamId,
        assignee = input.assignee
      )
      _ <- writeTask(sessionId, task)
    yield newId

    end for
  end create

  def get(sessionId: String, taskId: String): IO[Option[Task]] =
    readTask(taskFile(sessionId, taskId))

  def list(sessionId: String): IO[List[Task]] = IO
    .blocking {
      val dir = sessionDir(sessionId)
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(p => p.last.endsWith(".json") && !p.last.startsWith("."))
          .toList
          .sortBy(_.last.stripSuffix(".json").toIntOption.getOrElse(0))
    }
    .flatMap { paths =>
      // Issue #6: Don't swallow decode errors
      paths
        .traverse { p =>
          IO.blocking {
            decode[Task](os.read(p)) match
              case Right(task) => task
              case Left(err) =>
                org.slf4j.LoggerFactory
                  .getLogger("nebflow.task")
                  .warn(s"Skipping corrupted task at $p: ${err.getMessage}")
                null
          }
        }
        .map(_.filter(_ != null))
    }

  // 任务工具重做（2026-08-30）：统一四态矩阵 pending → in_progress →
  // completed / failed（team 域矩阵本来就是目标形态，session 域的五态矩阵、
  // C15 确认环节、C22、human 特例全部退役）。terminal = completed/failed。
  private def isValidTransition(from: TaskStatus, to: TaskStatus): Boolean =
    (from, to) match
      case (TaskStatus.Pending, TaskStatus.InProgress) => true
      case (TaskStatus.Pending, TaskStatus.Completed) => true
      case (TaskStatus.Pending, TaskStatus.Failed) => true
      case (TaskStatus.Pending, TaskStatus.Pending) => true // no-op
      case (TaskStatus.InProgress, TaskStatus.Completed) => true
      case (TaskStatus.InProgress, TaskStatus.Failed) => true
      case (TaskStatus.InProgress, TaskStatus.InProgress) => true // no-op
      case (TaskStatus.Completed, TaskStatus.Completed) => true // no-op
      case (TaskStatus.Failed, TaskStatus.Failed) => true // no-op
      case _ => false

  // Issue #3: DFS cycle detection in dependency graph
  private def hasCycle(tasks: List[Task]): Boolean =
    val adj = tasks.map(t => t.id -> t.blockedBy.filter(_.nonEmpty)).toMap
    val visited = mutable.Set[String]()
    val recStack = mutable.Set[String]()

    def dfs(id: String): Boolean =
      visited += id
      recStack += id
      val neighbors = adj.getOrElse(id, Nil)
      val found = neighbors.exists { neighbor =>
        if !visited.contains(neighbor) then dfs(neighbor)
        else if recStack.contains(neighbor) then true
        else false
      }
      recStack -= id
      found

    // Only visit tasks that are in the graph
    tasks.exists(t => !visited.contains(t.id) && dfs(t.id))

  end hasCycle

  def update(sessionId: String, taskId: String, updates: TaskUpdateInput): IO[Option[Task]] =
    get(sessionId, taskId).flatMap {
      case None => IO.pure(None)
      case Some(existing) =>
        val newStatus = updates.status.getOrElse(existing.status)
        val statusValid = updates.status.isEmpty ||
          isValidTransition(existing.status, newStatus)

        if !statusValid then
          IO.raiseError(
            new IllegalStateException(
              s"Invalid status transition: ${existing.status} -> $newStatus for task #$taskId"
            )
          )
        else
          val newBlocks = (existing.blocks ++ updates.addBlocks.getOrElse(Nil)).distinct
            .filterNot(updates.removeBlocks.getOrElse(Nil).contains)
          val newBlockedBy = (existing.blockedBy ++ updates.addBlockedBy.getOrElse(Nil)).distinct
            .filterNot(updates.removeBlockedBy.getOrElse(Nil).contains)

          val now = Instant.now().toString

          // C2: completedAt = the moment the task enters a terminal state
          // (completed/failed). Preserved once set.
          val entersTerminal =
            (newStatus == TaskStatus.Completed || newStatus == TaskStatus.Failed) &&
              existing.status != TaskStatus.Completed && existing.status != TaskStatus.Failed
          val newCompletedAt =
            if entersTerminal then Some(now) else existing.completedAt

          // Member attribution (2026-08-30): explicit reassignment — a
          // non-blank value sets it, blank string clears it, absent = keep.
          val newAssignee = updates.assignee match
            case Some(raw) => raw.trim match
              case ""    => None
              case value => Some(value)
            case None      => existing.assignee

          // C2 event stream: append one event per kind of change, cap at MaxEvents
          val evBuilder = List.newBuilder[TaskEvent]
          if updates.status.exists(_ != existing.status) then
            evBuilder += TaskEvent(
              "status",
              Some(s"${TaskStatus.wireName(existing.status)}→${TaskStatus.wireName(newStatus)}"),
              Some(now)
            )
          if updates.subject.exists(_ != existing.subject) then
            evBuilder += TaskEvent("subject", Some(s"was: ${existing.subject.take(120)}"), Some(now))
          if updates.description.exists(_ != existing.description) then
            evBuilder += TaskEvent("description", Some(s"was: ${existing.description.take(120)}"), Some(now))
          if newBlocks != existing.blocks || newBlockedBy != existing.blockedBy then
            evBuilder += TaskEvent("dependency", Some(s"blocks=${newBlocks.mkString(",")} blockedBy=${newBlockedBy.mkString(",")}"), Some(now))
          if updates.note.exists(_.nonEmpty) then
            evBuilder += TaskEvent("note", updates.note.map(_.take(120)), Some(now))
          if newAssignee != existing.assignee then
            evBuilder += TaskEvent(
              "assignee",
              Some(s"${existing.assignee.getOrElse("(none)")}→${newAssignee.getOrElse("(cleared)")}"),
              Some(now)
            )
          val newEvents = (existing.events ++ evBuilder.result()).takeRight(MaxEvents)

          // C2 notes: append-only
          val newNotes = updates.note.filter(_.nonEmpty) match
            case Some(content) => existing.notes :+ TaskNote(content, updates.noteLinks.getOrElse(Nil), Some(now))
            case None          => existing.notes

          val updated = existing.copy(
            subject = updates.subject.getOrElse(existing.subject),
            description = updates.description.getOrElse(existing.description),
            activeForm = updates.activeForm.orElse(existing.activeForm),
            status = newStatus,
            blocks = newBlocks,
            blockedBy = newBlockedBy,
            updatedAt = Some(now),
            completedAt = newCompletedAt,
            notes = newNotes,
            events = newEvents,
            assignee = newAssignee
          )

          // Issue #3: Check for cycles after dependency changes
          list(sessionId).flatMap { allTasks =>
            val tasksForCheck = allTasks.filterNot(_.id == taskId) :+ updated
            if hasCycle(tasksForCheck) then
              IO.raiseError(
                new IllegalStateException(
                  s"Dependency update for task #$taskId would create a cycle"
                )
              )
            else writeTask(sessionId, updated).as(Some(updated))
          }
        end if
    }

  /** Active = pending + in_progress（四态世界无 needs_confirmation）。 */
  def listActive(sessionId: String): IO[List[Task]] =
    list(sessionId).map(_.filter(t =>
      t.status == TaskStatus.Pending || t.status == TaskStatus.InProgress
    ))

  /** Visible = the panel-visible set = pending + in_progress ONLY (任务工具重做
    * 2026-08-30: 「列表只显 pending+in_progress」——completed/failed vanish from
    * the panel the moment they finish; the 6h/2d TTL governs DISK purge, not
    * visibility). Kept distinct from [[listActive]] only for the caller's
    * intent-readability. */
  def listVisible(sessionId: String): IO[List[Task]] = listActive(sessionId)

  /** todo-panel §7.1: mark a task completed and record WHO completed it
    * ("user" = user clicked the circle; "agent" = agent flow). Mirrors
    * update()'s terminal-state bookkeeping (completedAt + status event).
    * pending/in_progress -> completed are legal; re-completing a terminal
    * task raises IllegalStateException (caller turns it into taskError). */
  def complete(sessionId: String, taskId: String, by: String): IO[Option[Task]] =
    get(sessionId, taskId).flatMap {
      case None => IO.pure(None)
      case Some(existing) =>
        if !isValidTransition(existing.status, TaskStatus.Completed) then
          IO.raiseError(
            new IllegalStateException(
              s"Invalid status transition: ${existing.status} -> completed for task #$taskId"
            )
          )
        else if existing.status == TaskStatus.Completed then
          // No-op completion (user double-send raced a list refresh): keep
          // the original completedBy, just return the task.
          IO.pure(Some(existing))
        else
          val now = Instant.now().toString
          val updated = existing.copy(
            status = TaskStatus.Completed,
            updatedAt = Some(now),
            completedAt = Some(now),
            completedBy = Some(by),
            events = (existing.events :+ TaskEvent(
              "status",
              Some(s"${TaskStatus.wireName(existing.status)}→completed"),
              Some(now)
            )).takeRight(MaxEvents)
          )
          writeTask(sessionId, updated).as(Some(updated))
    }

  /** Max rendered subject length (chars) — reminder-refactor 2026-08-20:
    * full subjects stay available via the TaskList tool. */
  private val MaxSubjectChars = 30

  /** Max pending lines rendered before folding to a count (2026-08-20 ruling). */
  private val MaxPendingLines = 8

  private def truncateSubject(s: String): String =
    if s.length <= MaxSubjectChars then s else s.take(MaxSubjectChars) + "…"

  /**
   * Render active tasks as a hierarchical text block for the per-turn tasks
   * reminder. Only active (pending + in_progress) tasks are shown, with
   * tree-style indentation.
   *
   * Reminder refactor (2026-08-20, user ruling): subjects truncated to
   * [[MaxSubjectChars]]; pending lines beyond [[MaxPendingLines]] fold into a
   * count line (in_progress always renders); the instruction block (~300B)
   * lives in the cached prompt (PromptSections.tasksGuideSection) —
   * semantics in the cache, data travels per turn.
   */
  def renderForPrompt(sessionId: String): IO[String] =
    listActive(sessionId).map { active =>
      if active.isEmpty then ""
      else
        val byParent = active.groupBy(_.parentId)
        val roots = byParent.getOrElse(None, Nil).sortBy(_.id.toIntOption.getOrElse(0))

        def subtreeActive(t: Task): List[Task] =
          t :: byParent.getOrElse(Some(t.id), Nil).sortBy(_.id.toIntOption.getOrElse(0)).flatMap(subtreeActive)

        // Pending-fold state: once the pending budget is exhausted, whole
        // all-pending subtrees fold into the count; a pending node with
        // in_progress descendants still renders so the actionable tasks are
        // never silently dropped.
        var pendingRendered = 0
        var foldedCount = 0

        val sb = new StringBuilder
        sb.append(s"## Current Tasks (${active.size} active)\n")

        def renderTask(t: Task, depth: Int): Unit =
          val indent = "  " * depth
          val statusIcon = t.status match
            case TaskStatus.InProgress => "[in_progress]"
            case TaskStatus.Pending =>
              // todo-panel §2.3: human todos are reminders FOR the user — the
              // agent must not sweep them into its own work-through loop.
              if t.taskKind == "human" then "[waiting-user]" else "[pending]"
            case _ => ""
          val activeStr = t.activeForm match
            case Some(a) if t.status == TaskStatus.InProgress => s" — $a"
            case _ => ""
          sb.append(s"$indent#${t.id} $statusIcon ${truncateSubject(t.subject)}$activeStr\n")
          if t.status == TaskStatus.Pending then pendingRendered += 1
          val children = byParent.getOrElse(Some(t.id), Nil).sortBy(_.id.toIntOption.getOrElse(0))
          children.foreach(c => renderTask(c, depth + 1))
        end renderTask

        def tryRender(t: Task, depth: Int): Unit =
          val foldable = t.status == TaskStatus.Pending &&
            pendingRendered >= MaxPendingLines &&
            subtreeActive(t).forall(_.status == TaskStatus.Pending)
          if foldable then foldedCount += subtreeActive(t).size
          else renderTask(t, depth)

        roots.foreach(r => tryRender(r, 0))
        if foldedCount > 0 then
          sb.append(s"… +$foldedCount more pending (TaskList shows all)\n")
        sb.toString
      end if
    }

  // ---- TTL 物理清理（任务工具重做 2026-08-30）----

  /** Physically delete expired task files in one scope. Read-path visibility
    * already hides them (listVisible); this is the disk-hygiene half. */
  def purgeExpired(scopeKey: String): IO[Int] =
    list(scopeKey).flatMap { tasks =>
      val expired = tasks.filter(TaskStore.isExpired(_))
      expired.traverse_(t => delete(scopeKey, t.id)).as(expired.size)
    }

  /** Startup sweep across every scope directory on disk (session + team).
    * Called once at gateway boot — the persistence half of the TTL contract
    * (重启后扫描磁盘数据仍生效). Best-effort: never blocks startup. */
  def purgeAllExpired(): IO[Int] =
    IO.blocking {
      if !os.exists(root) then Nil
      else
        val sessionScopes =
          os.list(root).filter(p => os.isDir(p) && p.last != "teams").map(p => p.last)
        val teamScopes =
          if os.exists(root / "teams") then
            os.list(root / "teams").filter(os.isDir(_)).map(p => s"team:${p.last}")
          else Nil
        (sessionScopes ++ teamScopes).toList
    }.handleErrorWith(_ => IO.pure(Nil)).flatMap { scopes =>
      scopes.traverse(purgeExpired).map(_.sum)
    }

  // Issue #10: Delete transaction ordering — cleanup references before deleting file
  def delete(sessionId: String, taskId: String): IO[Boolean] =
    get(sessionId, taskId).flatMap {
      case None => IO.pure(false)
      case Some(_) =>
        // Step 1: Clean up references in other tasks BEFORE deleting the file
        list(sessionId).flatMap { tasks =>
          val cleanup = tasks.traverse_ { t =>
            if t.id != taskId then
              val newBlocks = t.blocks.filterNot(_ == taskId)
              val newBlockedBy = t.blockedBy.filterNot(_ == taskId)
              if newBlocks != t.blocks || newBlockedBy != t.blockedBy then
                writeTask(sessionId, t.copy(blocks = newBlocks, blockedBy = newBlockedBy))
              else IO.unit
            else IO.unit
          }
          // Step 2: Delete the file only after cleanup succeeds
          cleanup *> IO.blocking {
            val f = taskFile(sessionId, taskId)
            if os.exists(f) then
              os.remove(f)
              true
            else false
          }
        }
    }

  def deleteAll(sessionId: String): IO[Unit] = IO.blocking {
    val dir = sessionDir(sessionId)
    if os.exists(dir) then os.remove.all(dir)
  }

end FileTaskStore
