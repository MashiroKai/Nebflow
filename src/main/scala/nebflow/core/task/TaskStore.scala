package nebflow.core.task

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}

import java.time.Instant

import scala.collection.mutable

trait TaskStore:
  def create(sessionId: String, input: TaskCreateInput): IO[String]
  def get(sessionId: String, taskId: String): IO[Option[Task]]
  def list(sessionId: String): IO[List[Task]]
  def listActive(sessionId: String): IO[List[Task]]
  def listVisible(sessionId: String): IO[List[Task]]
  def dismiss(sessionId: String, taskId: String): IO[Option[Task]]
  def renderForPrompt(sessionId: String): IO[String]
  def update(sessionId: String, taskId: String, updates: TaskUpdateInput): IO[Option[Task]]
  def complete(sessionId: String, taskId: String, by: String): IO[Option[Task]]
  /** todo-panel v2 §2.4 (C16/C17): user returns a needs_confirmation task to
    * in_progress with feedback. Raises IllegalStateException when the task is
    * not awaiting confirmation or is a human todo. */
  def `return`(sessionId: String, taskId: String, feedback: String): IO[Option[Task]]
  def delete(sessionId: String, taskId: String): IO[Boolean]
  def deleteAll(sessionId: String): IO[Unit]

object FileTaskStore extends TaskStore:
  private val logger = NebflowLogger.forName("nebflow.taskstore")

  /** `def` on purpose: PathUtil.dataRoot may be swapped after this object's
    * first use (tests inject a temp root) — a `val` would freeze the path at
    * class-init time (same trap as UsageTracker, 2026-08-14). */
  private def root: os.Path = PathUtil.dataRoot / "tasks"
  private val hwmFile = ".highwatermark"

  /** Cap on per-task event history — oldest entries are dropped beyond this. */
  private val MaxEvents = 50

  // Atomic high-water mark per session (Issue #1)
  private val hwmRef: Ref[IO, Map[String, Int]] = Ref.unsafe(Map.empty)

  private def sessionDir(sessionId: String): os.Path = root / sessionId

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

  private def writeHighWaterMark(sessionId: String, value: Int): IO[Unit] = IO.blocking {
    val f = sessionDir(sessionId) / hwmFile
    os.write.over(f, value.toString)
  }

  private def readTask(path: os.Path): IO[Option[Task]] = IO.blocking {
    if os.exists(path) then
      decode[Task](os.read(path)) match
        case Right(task) => Some(task)
        case Left(err) => None // Skip corrupted task files silently
    else None
  }

  private def writeTask(sessionId: String, task: Task): IO[Unit] = IO.blocking {
    // Issue #23: atomic write via tmp+rename — a bare os.write.over can leave
    // a truncated/partial JSON file if the process dies mid-write; readers
    // then silently drop the task (readTask skips corrupted files).
    // NOTE: os.move.over(replaceExisting=true) is NOT atomic (it does
    // delete-then-rename internally and throws if the target is missing) —
    // java.nio Files.move with ATOMIC_MOVE maps to rename(2), which is the
    // true single-step atomic replace on POSIX.
    val f = taskFile(sessionId, task.id)
    val tmp = f / os.up / s"${f.last}.tmp.${java.util.UUID.randomUUID()}"
    os.write.over(tmp, task.asJson.noSpaces)
    java.nio.file.Files.move(
      tmp.toNIO,
      f.toNIO,
      java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      java.nio.file.StandardCopyOption.ATOMIC_MOVE
    )
  }

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
        taskKind = Task.normalizeTaskKind(input.taskKind)
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

  // Issue #2 + todo-panel v2 §2.2: state transition validation matrix.
  // needs_confirmation -> in_progress is NOT in this matrix — it is the
  // user-only return() path (spec C16/C17); the agent TaskUpdate path must
  // not be able to yank a task out of the awaiting-user-ruling state.
  private def isValidTransition(from: TaskStatus, to: TaskStatus): Boolean =
    (from, to) match
      case (TaskStatus.Pending, TaskStatus.InProgress) => true
      case (TaskStatus.Pending, TaskStatus.NeedsConfirmation) => true // fast-complete path
      case (TaskStatus.Pending, TaskStatus.Completed) => true
      case (TaskStatus.Pending, TaskStatus.Failed) => true
      case (TaskStatus.Pending, TaskStatus.Pending) => true // no-op
      case (TaskStatus.InProgress, TaskStatus.NeedsConfirmation) => true // C15: the ONLY completion lane
      case (TaskStatus.InProgress, TaskStatus.Completed) => true
      case (TaskStatus.InProgress, TaskStatus.Failed) => true
      case (TaskStatus.InProgress, TaskStatus.InProgress) => true // no-op
      case (TaskStatus.NeedsConfirmation, TaskStatus.NeedsConfirmation) => true // no-op
      case (TaskStatus.NeedsConfirmation, TaskStatus.Completed) => true // user confirm (complete())
      case (TaskStatus.NeedsConfirmation, TaskStatus.Dismissed) => true // user dismiss
      case (TaskStatus.Completed, TaskStatus.Completed) => true // no-op
      case (TaskStatus.Completed, TaskStatus.Dismissed) => true
      case (TaskStatus.Failed, TaskStatus.Failed) => true // no-op
      case (TaskStatus.Failed, TaskStatus.Dismissed) => true
      case (TaskStatus.Dismissed, TaskStatus.Dismissed) => true // no-op
      case _ => false

  /** todo-panel §2.2: human tasks have no in-progress state — the user either
    * completes them via the circle or the agent fails/dismisses them. Guards
    * the agent path (TaskUpdate) from pushing a human reminder into its own
    * work pipeline; the dedicated complete() path checks the plain matrix
    * (pending/in_progress -> completed) because it records completedBy.
    *
    * todo-panel v2: adds C15 (agent may not reach completed directly —
    * needs_confirmation is the only completion lane, completed is reserved
    * for the user's confirmation) and C22 (agent may not re-judge a task
    * failed while it is awaiting the user's ruling). */
  private def isValidTransitionFor(task: Task, to: TaskStatus): Boolean =
    if task.taskKind == "human" && (to == TaskStatus.InProgress || to == TaskStatus.NeedsConfirmation) then
      false // human todos: no in-progress, no awaiting-confirmation (v2 B9)
    else if task.status == TaskStatus.NeedsConfirmation && to == TaskStatus.Failed then
      false // C22: no re-judging while awaiting user ruling
    else if to == TaskStatus.Completed && task.status != TaskStatus.Completed && task.taskKind == "agent" then
      false // C15: agent path may not complete directly (needs_confirmation only)
    else isValidTransition(task.status, to)

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
        // Dismissed tasks are "cleared" by the user — agent updates (status or
        // otherwise) are silently accepted as no-ops so agents don't error.
        if existing.status == TaskStatus.Dismissed then IO.pure(Some(existing))
        else
          // Issue #2: Validate status transition
          val newStatus = updates.status.getOrElse(existing.status)
          val statusValid = updates.status.isEmpty || isValidTransitionFor(existing, newStatus)

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
            // (completed or failed). Preserved once set (dismiss keeps it).
            val newStatus2 = newStatus
            val entersTerminal =
              (newStatus2 == TaskStatus.Completed || newStatus2 == TaskStatus.Failed) &&
                existing.status != TaskStatus.Completed && existing.status != TaskStatus.Failed
            val newCompletedAt =
              if entersTerminal then Some(now) else existing.completedAt

            // C2 event stream: append one event per kind of change, cap at MaxEvents
            val evBuilder = List.newBuilder[TaskEvent]
            if updates.status.exists(_ != existing.status) then
              evBuilder += TaskEvent(
                "status",
                Some(s"${TaskStatus.wireName(existing.status)}→${TaskStatus.wireName(newStatus2)}"),
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
              events = newEvents
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

  def listActive(sessionId: String): IO[List[Task]] =
    // v2 §3: needs_confirmation is NOT terminal — it still counts as "not
    // done yet" (header activeCount = pending + in_progress + needs_confirmation).
    list(sessionId).map(_.filter(t =>
      t.status == TaskStatus.Pending || t.status == TaskStatus.InProgress || t.status == TaskStatus.NeedsConfirmation
    ))

  def listVisible(sessionId: String): IO[List[Task]] =
    list(sessionId).map(_.filter(_.status != TaskStatus.Dismissed))

  def dismiss(sessionId: String, taskId: String): IO[Option[Task]] =
    update(sessionId, taskId, TaskUpdateInput(status = Some(TaskStatus.Dismissed)))

  /** todo-panel §7.1: mark a task completed and record WHO completed it
    * ("user" = user clicked the circle; "agent" = agent flow). Mirrors
    * update()'s terminal-state bookkeeping (completedAt + status event) —
    * deliberately NOT routed through update() because TaskUpdateInput is
    * frozen by the spec and completedBy is not an agent-settable field.
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

  /**
   * Render tasks as a hierarchical text block for system prompt injection.
   *  Only active (pending + in_progress + needs_confirmation) tasks are
   *  shown, with tree-style indentation. v2: needs_confirmation tasks render
   *  with a marker so the agent knows they are DONE but AWAITING USER
   *  CONFIRMATION — it must not re-work them (B10/C15/C22).
   */
  def renderForPrompt(sessionId: String): IO[String] =
    list(sessionId).map { allTasks =>
      val active = allTasks.filter(t =>
        t.status == TaskStatus.Pending || t.status == TaskStatus.InProgress || t.status == TaskStatus.NeedsConfirmation
      )
      if active.isEmpty then ""
      else
        val byParent = active.groupBy(_.parentId)
        val roots = byParent.getOrElse(None, Nil).sortBy(_.id.toIntOption.getOrElse(0))

        val sb = new StringBuilder
        sb.append("## Current Tasks\n\n")
        sb.append(
          "Your task list is below. Work through tasks in order. When a task is fully done, " +
            "mark it needs_confirmation (NOT completed) and attach a note with the outcome — " +
            "completed is reserved for the user's confirmation. Tasks marked [needs_confirmation] " +
            "are DONE and awaiting user confirmation: do NOT work on them again; if the user " +
            "returns one with feedback, a [打回任务] block tells you what to revise.\n\n"
        )

        def renderTask(t: Task, depth: Int): Unit =
          val indent = "  " * depth
          val statusIcon = t.status match
            case TaskStatus.InProgress => "[in_progress]"
            case TaskStatus.NeedsConfirmation => "[needs_confirmation]"
            case TaskStatus.Pending =>
              // todo-panel §2.3: human todos are reminders FOR the user — the
              // agent must not sweep them into its own work-through loop.
              if t.taskKind == "human" then "[waiting-user]" else "[pending]"
            case _ => ""
          val activeStr = t.activeForm match
            case Some(a) if t.status == TaskStatus.InProgress => s" — $a"
            case _ => ""
          sb.append(s"$indent#${t.id} $statusIcon ${t.subject}$activeStr\n")
          val children = byParent.getOrElse(Some(t.id), Nil).sortBy(_.id.toIntOption.getOrElse(0))
          children.foreach(c => renderTask(c, depth + 1))

        roots.foreach(r => renderTask(r, 0))
        sb.toString
      end if
    }

  /** todo-panel v2 §2.4 (C16/C17): user return — needs_confirmation back to
    * in_progress with durable feedback. Mirrors complete()'s structure:
    * validates against the authoritative task state, raises
    * IllegalStateException (caller turns it into taskError) when the task is
    * a human todo or not in needs_confirmation. On success: returnCount+1,
    * feedback appended to notes (C20 — durable revision history), events
    * record the return. Empty feedback is legal (bare return, §5.4) and adds
    * no note. */
  def `return`(sessionId: String, taskId: String, feedback: String): IO[Option[Task]] =
    get(sessionId, taskId).flatMap {
      case None => IO.pure(None)
      case Some(existing) =>
        if existing.taskKind == "human" then
          IO.raiseError(
            new IllegalStateException(s"Task #$taskId is a human todo — human todos have no return")
          )
        else if existing.status != TaskStatus.NeedsConfirmation then
          IO.raiseError(
            new IllegalStateException(
              s"Invalid return: task #$taskId is ${TaskStatus.wireName(existing.status)}, " +
                s"not needs_confirmation (only awaiting-confirmation tasks can be returned)"
            )
          )
        else
          val now = Instant.now().toString
          val evBase = List(
            TaskEvent("status", Some("needs_confirmation→in_progress"), Some(now)),
            TaskEvent("returned", Some(feedback.take(120)), Some(now))
          )
          val updated = existing.copy(
            status = TaskStatus.InProgress,
            returnCount = existing.returnCount + 1,
            updatedAt = Some(now),
            notes = if feedback.nonEmpty then existing.notes :+ TaskNote(feedback, Nil, Some(now)) else existing.notes,
            events = (existing.events ++ evBase).takeRight(MaxEvents)
          )
          writeTask(sessionId, updated).as(Some(updated))
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
