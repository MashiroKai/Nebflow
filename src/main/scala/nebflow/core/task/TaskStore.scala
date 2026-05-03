package nebflow.core.task

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.time.Instant

import scala.collection.mutable

trait TaskStore:
  def create(sessionId: String, input: TaskCreateInput): IO[String]
  def get(sessionId: String, taskId: String): IO[Option[Task]]
  def list(sessionId: String): IO[List[Task]]
  def update(sessionId: String, taskId: String, updates: TaskUpdateInput): IO[Option[Task]]
  def delete(sessionId: String, taskId: String): IO[Boolean]

object FileTaskStore extends TaskStore:
  private val logger = NebflowLogger.forName("nebflow.taskstore")
  private val root = os.home / ".nebflow" / "tasks"
  private val hwmFile = ".highwatermark"

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
    if os.exists(f) then
      os.read(f).trim.toIntOption.getOrElse(0)
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
        case Left(err)   => throw new RuntimeException(s"Failed to decode task at $path: ${err.getMessage}")
    else None
  }

  private def writeTask(sessionId: String, task: Task): IO[Unit] = IO.blocking {
    os.write.over(taskFile(sessionId, task.id), task.asJson.noSpaces)
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
        metadata = input.metadata,
        createdAt = Some(now),
        updatedAt = Some(now)
      )
      _ <- writeTask(sessionId, task)
    yield newId

  def get(sessionId: String, taskId: String): IO[Option[Task]] =
    readTask(taskFile(sessionId, taskId))

  def list(sessionId: String): IO[List[Task]] = IO.blocking {
    val dir = sessionDir(sessionId)
    if !os.exists(dir) then Nil
    else
      os.list(dir)
        .filter(p => p.last.endsWith(".json") && !p.last.startsWith("."))
        .toList
        .sortBy(_.last.stripSuffix(".json").toIntOption.getOrElse(0))
  }.flatMap { paths =>
    // Issue #6: Don't swallow decode errors
    paths.traverse { p =>
      IO.blocking {
        decode[Task](os.read(p)) match
          case Right(task) => task
          case Left(err)   => throw new RuntimeException(s"Failed to decode task at $p: ${err.getMessage}")
      }
    }
  }

  // Issue #2: State transition validation matrix
  private def isValidTransition(from: TaskStatus, to: TaskStatus): Boolean =
    (from, to) match
      case (TaskStatus.Pending, TaskStatus.InProgress) => true
      case (TaskStatus.Pending, TaskStatus.Pending)    => true // no-op
      case (TaskStatus.InProgress, TaskStatus.Completed) => true
      case (TaskStatus.InProgress, TaskStatus.InProgress) => true // no-op
      case (TaskStatus.Completed, TaskStatus.Completed)  => true // no-op
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

  def update(sessionId: String, taskId: String, updates: TaskUpdateInput): IO[Option[Task]] =
    get(sessionId, taskId).flatMap {
      case None => IO.pure(None)
      case Some(existing) =>
        // Issue #2: Validate status transition
        val newStatus = updates.status.getOrElse(existing.status)
        val statusValid = updates.status.isEmpty || isValidTransition(existing.status, newStatus)

        if !statusValid then
          IO.raiseError(new IllegalStateException(
            s"Invalid status transition: ${existing.status} -> $newStatus for task #$taskId"
          ))
        else
          val newBlocks = (existing.blocks ++ updates.addBlocks.getOrElse(Nil)).distinct
            .filterNot(updates.removeBlocks.getOrElse(Nil).contains)
          val newBlockedBy = (existing.blockedBy ++ updates.addBlockedBy.getOrElse(Nil)).distinct
            .filterNot(updates.removeBlockedBy.getOrElse(Nil).contains)

          val updated = existing.copy(
            subject = updates.subject.getOrElse(existing.subject),
            description = updates.description.getOrElse(existing.description),
            activeForm = updates.activeForm.orElse(existing.activeForm),
            status = newStatus,
            blocks = newBlocks,
            blockedBy = newBlockedBy,
            metadata = updates.metadata.orElse(existing.metadata),
            updatedAt = Some(Instant.now().toString)
          )

          // Issue #3: Check for cycles after dependency changes
          list(sessionId).flatMap { allTasks =>
            val tasksForCheck = allTasks.filterNot(_.id == taskId) :+ updated
            if hasCycle(tasksForCheck) then
              IO.raiseError(new IllegalStateException(
                s"Dependency update for task #$taskId would create a cycle"
              ))
            else
              writeTask(sessionId, updated).as(Some(updated))
          }
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

end FileTaskStore
