package nebflow.core.scheduler

import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.db.{DbHandle, DbHost, ReadResult}
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * Scheduled-task persistence（引擎侧文件存储：每会话一个 `<sessionId>.json`）。
 *
 * 🔒 全库互斥（2026-10-29 调度丢失 bug 根因修复，事故 f2ef6ff1）：本类的每个
 * 公开方法都是「读文件 → 改内存 → 整文件覆盖写」的读-改-写序列。改前它们**无锁**
 * ——同会话文件上的两个并发操作（例证：2026-10-29 11:50:21.073 同一毫秒、两个
 * blocker 线程各发一条 Schedule create，落同一会话文件）后写者用**旧读数**覆盖
 * 写回 ⇒ 先落的任务被静默抹掉（f2ef6ff1 创建回执成功、几分钟后 list 消失）。
 * 现在所有读写都过单一 Mutex（进程内全库串行）：
 *   - 写写竞争（丢任务根因）彻底消除；
 *   - 读写竞争（半截 JSON 解码失败 → 瞬时漏触发）一并消除。
 * 嵌套禁令：Mutex 不可重入 —— 公开方法只许调 `*Unlocked` 内部件，禁互调公开
 * 方法（upsertTaskByName = removeByNameUnlocked + addUnlocked，同一 permit
 * 内原子完成，spec 钉并发语义）。
 */
class ScheduledTaskStore(
  baseDir: os.Path,
  /**
   * db 接线第一阶 C6（待办/定时任务面落库）：显式注入缝（测试传临时根句柄）。
   * 缺省 + 生产默认目录构造（SharedResources:57）⇒ 自开共享句柄（DbHost，
   * best-effort——失败回落纯旧档行为）；测试传临时路径 ⇒ 不自开，零波及。
   */
  db: Option[DbHandle] = None
):

  import ScheduledTaskStore.*

  private val logger = NebflowLogger.forName("nebflow.scheduled-task.store")

  /** db 影子面句柄（None = 未接线/自开失败 ⇒ 影子腿与恢复读全部 no-op）。 */
  private val dbHandle: Option[DbHandle] = db.orElse(
    Option.when(baseDir == defaultBaseDir)(DbHost.openBestEffort(PathUtil.dataRoot.toNIO)).flatten
  )

  /**
   * 全库读写互斥：任何文件读/改/写都必须持有（见类注）。CE 3.5 的 Mutex 无
   * unsafe 直构 —— lazy + global runtime 物化一次（先例：DeviceProfile.scala）。
   */
  private[scheduler] def tryStoreMutex: IO[Boolean] =
    IO.blocking(baseDir.toNIO.toRealPath()).flatMap { canonical =>
      val mutex = ScheduledTaskStore.mutexFor(canonical)
      mutex.tryAcquire.flatMap(acquired => if acquired then mutex.release.as(true) else IO.pure(false))
    }

  private[scheduler] def withStoreLock[A](
    action: IO[A],
    onOsWait: IO[Unit] = IO.unit,
    onMutexWait: IO[Unit] = IO.unit,
    onMutexAcquired: IO[Unit] = IO.unit
  ): IO[A] =
    for
      canonical <- IO.blocking {
        java.nio.file.Files.createDirectories(baseDir.toNIO)
        baseDir.toNIO.toRealPath()
      }
      result <- (onMutexWait *> ScheduledTaskStore.mutexFor(canonical).permit.surround {
        onMutexAcquired *> {
          val fileLock = Resource
            .make {
              IO.blocking(
                java.nio.channels.FileChannel.open(
                  canonical.resolve(".scheduled-tasks.lock"),
                  java.nio.file.StandardOpenOption.CREATE,
                  java.nio.file.StandardOpenOption.WRITE
                )
              )
            }(channel => IO.blocking(channel.close()).handleError(_ => ()))
            .flatMap { channel =>
              def acquire: IO[java.nio.channels.FileLock] =
                IO.blocking(Option(channel.tryLock())).flatMap {
                  case Some(lock) => IO.pure(lock)
                  case None => onOsWait *> IO.sleep(scala.concurrent.duration.DurationInt(25).millis) *> acquire
                }
              Resource.makeFull[IO, java.nio.channels.FileLock](poll => poll(acquire))(lock =>
                IO.blocking(lock.release()).handleError(_ => ())
              )
            }
          fileLock.use(_ => action)
        }
      })
    yield result

  private def sessionFile(sessionId: String): os.Path =
    baseDir / s"$sessionId.json"

  private def ensureBaseDir: IO[Unit] = IO.blocking {
    if !os.exists(baseDir) then os.makeDir.all(baseDir)
  }

  // ------------------------------------------------------------------
  // Unlocked internals — 只许被持锁的公开方法调用
  // ------------------------------------------------------------------

  private def loadUnlocked(sessionId: String): IO[List[ScheduledTask]] = IO.blocking {
    val f = sessionFile(sessionId)
    if !os.exists(f) then Nil
    else
      decode[List[ScheduledTask]](os.read(f)) match
        case Right(list) => list
        case Left(err) =>
          // 静默面收编（db 接线第一阶 C6/D-5，标签③派生面）：坏档不再静默折叠成
          // Nil——隔离（字节保全，Quarantine）+ WARN + db 影子恢复读；db 无档/不可读
          // ⇒ 按缺席起步（调用方随后 save 覆盖销毁的正是这份**已隔离**的坏档）。
          val iso = nebflow.db.Quarantine.quarantine(f.toNIO, System.currentTimeMillis())
          logger.warnSync(
            s"scheduled-tasks file corrupt for $sessionId: ${err.getMessage} " +
              s"quarantined=${iso.map(_.toString).getOrElse("-")}; trying db shadow restore"
          )
          dbHandle match
            case None => Nil
            case Some(h) =>
              restoreFromDbSync(h, sessionId) match
                case Right(tasks) => tasks
                case Left(why) =>
                  logger.warnSync(s"db shadow restore unavailable for $sessionId: $why")
                  Nil
    end if
  }

  /** 坏档 db 恢复读（同步腿，只在 loadUnlocked 的 blocking 区内调用）。 */
  private def restoreFromDbSync(h: DbHandle, sessionId: String): Either[String, List[ScheduledTask]] =
    import cats.effect.unsafe.implicits.global
    try
      h.liveScheduleTasks(sessionId).unsafeRunSync() match
        case ReadResult.Absent() => Left("db 面无该会话调度流")
        case ReadResult.Corrupt(k, ev) => Left(s"db 面 Corrupt($k, $ev)")
        case ReadResult.Present(rows) =>
          val tasks = rows.flatMap(_.taskDef.as[ScheduledTask].toOption)
          if tasks.isEmpty then Left("db 面活任务折叠为空") else Right(tasks)
    catch case e: Throwable => Left(s"db 读异常: ${e.getMessage}")

  /**
   * 整档覆盖写 + db 影子差量：先读旧档（decode 成功才算基线——坏档情形由
   * loadUnlocked 的隔离/恢复腿负责，这里静默取空基线），与 new 列表做差量写穿。
   */
  private def saveUnlocked(sessionId: String, tasks: List[ScheduledTask]): IO[Unit] =
    IO.fromEither(
      tasks.traverse_(t =>
        t.due.traverse_(d => d.asJson.as[TodoDue].left.map(e => new IllegalArgumentException(e.getMessage)))
      )
    ) *> ensureBaseDir *> IO
      .blocking {
        val f = sessionFile(sessionId)
        val before =
          if os.exists(f) then decode[List[ScheduledTask]](os.read(f)).getOrElse(Nil)
          else Nil
        nebflow.core.AtomicJson.writeSync(f, tasks.asJson.noSpaces)
        before
      }
      .flatMap { before =>
        shadowDiff(sessionId, before, tasks)
      }

  // ------------------------------------------------------------------
  // db 影子写（C6）—— 写穿 best-effort：任何 db 故障 ⇒ WARN + 跳过，绝不阻断主链
  // ------------------------------------------------------------------

  /**
   * 差量写穿：旧列表 vs 新列表 ⇒ 新增/变更行 `appendScheduleTask`（同 id 取最后
   * 行的折叠语义 = 天然 upsert），消失行 `tombstoneScheduleTask`。句柄 None ⇒
   * no-op；单行写失败只 WARN 该腿（其余行继续），保旧档为主读面。
   */
  private def shadowDiff(sessionId: String, before: List[ScheduledTask], after: List[ScheduledTask]): IO[Unit] =
    dbHandle match
      case None => IO.unit
      case Some(h) =>
        val now = System.currentTimeMillis()
        val beforeById = before.iterator.map(t => t.id -> t).toMap
        val afterById = after.iterator.map(t => t.id -> t).toMap
        val upserts = after.filterNot(t => beforeById.get(t.id).contains(t))
        val deletes = before.filterNot(t => afterById.contains(t.id))
        val legs =
          upserts.map(t =>
            h.appendScheduleTask(sessionId, t.asJson, now).attempt.flatMap {
              case Left(e) => logger.warn(s"db shadow appendScheduleTask failed ($sessionId/${t.id}): ${e.getMessage}")
              case Right(_) => IO.unit
            }
          ) ++ deletes.map(t =>
            h.tombstoneScheduleTask(sessionId, t.id, now).attempt.flatMap {
              case Left(e) => logger.warn(s"db shadow tombstone failed ($sessionId/${t.id}): ${e.getMessage}")
              case Right(_) => IO.unit
            }
          )
        legs.sequence_.void

  /** 持锁读-改-写单会话文件：f 纯函数变换，返回 (新列表, 结果)。 */
  /** Session ids represented by the on-disk `<sid>.json` files (blocking read of the directory). */
  private def sessionIdsUnlocked: IO[List[String]] = IO.blocking {
    if !os.exists(baseDir) then Nil
    else os.list(baseDir).filter(_.last.endsWith(".json")).map(_.last.stripSuffix(".json")).toList
  }

  /** Transform the authoritative payload once, then retire stale copies only after it lands. */
  private def updateByIdUnlocked(taskId: String)(f: ScheduledTask => ScheduledTask): IO[Option[ScheduledTask]] =
    sessionIdsUnlocked.flatMap(_.traverse(sid => loadUnlocked(sid).map(sid -> _))).flatMap { sessions =>
      val copies = sessions.flatMap(_._2).filter(_.id == taskId)
      distinctById(copies).headOption match
        case None if copies.nonEmpty =>
          IO.raiseError(new IllegalStateException(s"scheduled task $taskId has conflicting revisions; update refused"))
        case None => IO.pure(None)
        case Some(current) =>
          val requested = f(current)
          if requested.due != current.due && requested.occurrences != current.occurrences then
            throw new IllegalStateException("rule change cannot rewrite occurrences")
          val changed = requested.copy(
            id = current.id,
            sessionId = current.sessionId,
            createdAt = current.createdAt,
            source = current.source,
            revision = current.revision,
            dueRuleVersion =
              if requested.due == current.due then current.dueRuleVersion
              else Math.addExact(current.dueRuleVersion, 1L),
            dueRuleHistory =
              if requested.due == current.due then current.dueRuleHistory
              else current.dueRuleHistory ++ current.due.toList.map(TodoRuleSnapshot(current.dueRuleVersion, _))
          )
          val updated =
            if changed == current then current
            else changed.copy(revision = Math.addExact(current.revision, 1L))
          val rows = sessions.find(_._1 == current.sessionId).get._2
          val target = rows.map(t => if t.id == taskId then updated else t)
          (if target == rows then IO.unit else saveUnlocked(current.sessionId, target)) *>
            sessions
              .filter { case (sid, stored) => sid != current.sessionId && stored.exists(_.id == taskId) }
              .traverse_ { case (sid, stored) =>
                saveUnlocked(sid, stored.filterNot(_.id == taskId))
                  .handleErrorWith(e =>
                    logger.warn(s"task update left a stale copy in $sid (id=$taskId): ${e.getMessage}")
                  )
              }
              .as(Some(updated))
      end match
    }

  private def addUnlocked(task: ScheduledTask): IO[Unit] =
    (loadUnlocked(task.sessionId), scanUnlocked(_.id == task.id, sort = false)).tupled.flatMap { (existing, copies) =>
      if copies.nonEmpty then IO.raiseError(new IllegalArgumentException("duplicate task id"))
      else saveUnlocked(task.sessionId, existing :+ task)
    }

  private def removeByNameUnlocked(
    name: String,
    kind: Option[String],
    owner: Option[String],
    legacyOwner: ScheduledTask => Boolean,
    excludeId: String = ""
  ): IO[List[ScheduledTask]] = IO
    .blocking {
      if !os.exists(baseDir) then Nil
      else
        val removed = List.newBuilder[ScheduledTask]
        val matchKind = kind.getOrElse(ScheduledTask.KindTask)
        os.list(baseDir)
          .filter(_.last.endsWith(".json"))
          .toList
          .foreach { f =>
            decode[List[ScheduledTask]](os.read(f)) match
              case Right(list) =>
                // Ownership must be explicit or PROVEN by a legacy marker; a record whose
                // origin cannot be shown is kept, never guessed away. An unreadable file is
                // likewise left byte-for-byte untouched.
                val (drop, keep) =
                  list.partition(t =>
                    t.id != excludeId && t.name.contains(name) && ScheduledTask.sameKind(t.kind, Some(matchKind)) &&
                      owner.isDefined && (t.source == owner || (t.source.isEmpty && legacyOwner(t)))
                  )
                if drop.nonEmpty then
                  removed ++= drop
                  nebflow.core.AtomicJson.writeSync(f, keep.asJson.noSpaces)
              case Left(_) => () // unreadable: leave untouched (source unknown ⇒ never delete)
          }
        removed.result()
    }
    .flatMap { dropped =>
      // db 影子：跨会话整名清理 ⇒ 逐个 tombstone（各自 sessionId）
      dropped
        .foldLeft(IO.unit) { (acc, t) =>
          acc *> shadowTombstone(t.sessionId, t.id)
        }
        .as(dropped)
    }

  /** 单行 tombstone 影子腿（句柄 None ⇒ no-op；失败 WARN 不抛）。 */
  private def shadowTombstone(sessionId: String, taskId: String): IO[Unit] =
    dbHandle match
      case None => IO.unit
      case Some(h) =>
        h.tombstoneScheduleTask(sessionId, taskId, System.currentTimeMillis()).attempt.flatMap {
          case Left(e) => logger.warn(s"db shadow tombstone failed ($sessionId/$taskId): ${e.getMessage}")
          case Right(_) => IO.unit
        }

  private def scanUnlocked(filter: ScheduledTask => Boolean, sort: Boolean): IO[List[ScheduledTask]] =
    IO.blocking {
      if !os.exists(baseDir) then Nil
      else
        val found = os
          .list(baseDir)
          .filter(_.last.endsWith(".json"))
          .toList
          .flatMap { f =>
            decode[List[ScheduledTask]](os.read(f)) match
              case Right(list) => list.filter(filter)
              case Left(_) => Nil
          }
        if sort then found.sortBy(_.triggerAt) else found
      end if
    }

  // ------------------------------------------------------------------
  // Public API — 全部持锁；按 id 的删除/更新/触发覆盖所有会话文件里的副本
  // （跨会话 rearm 的可恢复窗口可能留下同 id 第二份），按 name 的清理仍跨文件。
  // ------------------------------------------------------------------

  def loadTasks(sessionId: String): IO[List[ScheduledTask]] =
    withStoreLock {
      // Keep this read's quarantine/DB recovery, but do not expose a stale session copy.
      loadUnlocked(sessionId).flatMap { local =>
        scanUnlocked(_.sessionId != sessionId, sort = false)
          .map(other => distinctById(local ++ other).filter(_.sessionId == sessionId))
      }
    }

  def saveTasks(sessionId: String, tasks: List[ScheduledTask]): IO[Unit] =
    withStoreLock {
      (loadUnlocked(sessionId), scanUnlocked(_ => true, sort = false)).tupled.flatMap { (existing, all) =>
        val byId = existing.map(t => t.id -> t).toMap
        val stale = tasks.exists { t =>
          val copies = all.filter(_.id == t.id)
          val localStale = byId.get(t.id).exists(_.revision != t.revision)
          val newer = copies.exists(_.revision > t.revision)
          val equalConflict = copies.exists(c =>
            c.revision == t.revision && c.copy(sessionId = "") != t.copy(sessionId = "") &&
              !byId.get(t.id).contains(c)
          )
          localStale || newer || equalConflict
        }
        val rewritesInstances = tasks.exists(t =>
          byId.get(t.id).exists(old => old.due != t.due && old.occurrences != t.occurrences)
        )
        val deletesRows = existing.exists(old => !tasks.exists(_.id == old.id))
        if stale then IO.raiseError(new IllegalStateException("stale task revision"))
        else if deletesRows then IO.raiseError(new IllegalStateException("direct save cannot delete tasks"))
        else if rewritesInstances then IO.raiseError(new IllegalStateException("rule change cannot rewrite occurrences"))
        else
          val adjusted = tasks.map { task =>
            byId.get(task.id) match
              case Some(old) if old.due != task.due =>
                task.copy(
                  revision = Math.addExact(old.revision, 1L),
                  dueRuleVersion = Math.addExact(old.dueRuleVersion, 1L),
                  dueRuleHistory = old.dueRuleHistory ++ old.due.toList.map(TodoRuleSnapshot(old.dueRuleVersion, _))
                )
              case Some(old) =>
                task.copy(
                  revision = if task == old then old.revision else Math.addExact(old.revision, 1L),
                  dueRuleVersion = old.dueRuleVersion,
                  dueRuleHistory = old.dueRuleHistory
                )
              case None => task
          }
          saveUnlocked(sessionId, adjusted)
      }
    }

  /** Commit occurrence and series cursor in the same atomic session-file replacement. */
  private[scheduler] def recordOccurrence(
    sessionId: String,
    occurrence: TodoOccurrence,
    nextDueAt: Option[Long],
    beforeCommit: () => Unit = () => (),
    afterCommit: () => Unit = () => ()
  ): IO[Boolean] =
    withStoreLock {
      scanUnlocked(_.id == occurrence.todoId, sort = false).flatMap { copies =>
        val authority = distinctById(copies).headOption
        loadUnlocked(sessionId).flatMap { rows =>
          rows.find(_.id == occurrence.todoId) match
            case Some(task) if !authority.contains(task) =>
              IO.raiseError(new IllegalStateException("stale or conflicting task revision"))
            case None => IO.raiseError(new IllegalArgumentException("todo not found"))
            case Some(task) if task.occurrences.exists(_.key == occurrence.key) => IO.pure(false)
            case Some(task) if task.due.isDefined && task.dueRuleVersion != occurrence.ruleVersion =>
              IO.raiseError(new IllegalStateException("stale reminder rule version"))
            case Some(task) if !task.nextDueAt.contains(occurrence.scheduledAt) =>
              IO.raiseError(new IllegalArgumentException("occurrence does not match next due time"))
            case Some(task) =>
              val updated = task.copy(
                occurrences = task.occurrences :+ occurrence,
                nextDueAt = nextDueAt,
                revision = Math.addExact(task.revision, 1L)
              )
              IO(beforeCommit()) *> saveUnlocked(sessionId, rows.map(t => if t.id == task.id then updated else t)) *>
                IO(afterCommit()).as(true)
        }
      }
    }

  /** Materialize all elapsed reminder instances; each replacement commits cursor and instance together. */
  private[scheduler] def materializeDueOccurrences(now: Long): IO[Unit] =
    def next(rule: TodoDue, scheduled: Long): Option[Long] = rule match
      case _: TodoDue.Once => None
      case weekly: TodoDue.Weekly =>
        val zone = java.time.ZoneId.of(weekly.zoneId)
        val date = java.time.Instant.ofEpochMilli(scheduled).atZone(zone).toLocalDate.plusWeeks(1)
        val local = date.atTime(java.time.LocalTime.parse(weekly.localTime))
        val rules = zone.getRules
        val offsets = rules.getValidOffsets(local)
        val instant =
          if offsets.isEmpty then rules.getTransition(local).getDateTimeAfter.atZone(zone).toInstant
          else local.toInstant(offsets.get(0))
        Some(instant.toEpochMilli)
    def advance(sessionId: String, task: ScheduledTask): IO[Unit] =
      (task.due, task.nextDueAt) match
        case (Some(rule), Some(at)) if at <= now =>
          val following = next(rule, at)
          val occurrence = TodoOccurrence.create(task.id, at, task.dueRuleVersion)
          val updated = task.copy(
            occurrences = task.occurrences :+ occurrence,
            nextDueAt = following,
            revision = Math.addExact(task.revision, 1L)
          )
          loadUnlocked(sessionId).flatMap { rows =>
            if !rows.find(_.id == task.id).contains(task) then
              IO.raiseError(new IllegalStateException("stale task revision or cursor"))
            else saveUnlocked(sessionId, rows.map(t => if t.id == task.id then updated else t))
          } *>
            advance(sessionId, updated)
        case _ => IO.unit
    withStoreLock {
      readUnlocked(t => t.isTodo && t.enabled && !t.isCompleted && t.nextDueAt.exists(_ <= now), sort = false)
        .flatMap(_.traverse_(task => advance(task.sessionId, task)))
    }

  def addTask(task: ScheduledTask): IO[Unit] =
    withStoreLock(addUnlocked(task))

  /**
   * Idempotent user-create retry, scoped to one session and exact requested fields.
   *
   * `triggerAt`/`completedAt` are deliberately NOT part of the identity: a retry recomputes
   * `nextTodoSurfaceAt` and the user may have completed the row since the lost receipt, so
   * comparing those state fields would turn a legitimate retry into an identity conflict.
   * The created frame reports the durable row (`saved`) so the client sees the real state.
   */
  def addUserTodoOnce(task: ScheduledTask): IO[ScheduledTask] =
    withStoreLock {
      loadUnlocked(task.sessionId).flatMap { existing =>
        existing.find(_.id == task.id) match
          case Some(old)
              if old.source.contains("user") && old.isTodo &&
                old.content == task.content && old.name == task.name && old.detail == task.detail &&
                old.project == task.project && old.priority == task.priority =>
            IO.pure(old)
          case Some(_) => IO.raiseError(new IllegalArgumentException("create identity conflict"))
          case None => saveUnlocked(task.sessionId, existing :+ task).as(task)
      }
    }

  /**
   * Delete a task by id. An id is globally unique, but a cross-session rearm whose source
   * removal failed can leave the same id in two session files (see `upsertTaskByName`).
   * A cancel must therefore cover **every** copy: otherwise the survivor keeps firing.
   * The `sessionId` argument stays for API compatibility; the id is the authority.
   */
  def deleteTask(sessionId: String, taskId: String): IO[Unit] =
    cancelTask(taskId).void

  /**
   * Explicit cancellation needs no authoritative payload. Return all removed copies so
   * callers can distinguish absence and notify every affected session without guessing.
   */
  def cancelTask(taskId: String): IO[List[ScheduledTask]] =
    withStoreLock {
      sessionIdsUnlocked.flatMap(_.traverse(sid => loadUnlocked(sid).map(sid -> _))).flatMap { sessions =>
        val copies = sessions.flatMap(_._2).filter(_.id == taskId)
        // Remove stale rows first. A failed cleanup must not leave only an obsolete due row.
        sessions
          .filter(_._2.exists(_.id == taskId))
          .sortBy { case (sid, rows) => (rows.filter(_.id == taskId).map(_.revision).max, sid) }
          .traverse_ { case (sid, rows) => saveUnlocked(sid, rows.filterNot(_.id == taskId)) }
          .as(copies)
      }
    }

  def markTriggered(sessionId: String, taskId: String): IO[Unit] =
    withStoreLock {
      updateByIdUnlocked(taskId)(_.copy(triggered = true, triggeredAt = Some(System.currentTimeMillis()))).void
    }

  /** Reschedule a recurring task — bump triggerAt forward, keep triggered=false. */
  def rescheduleTask(sessionId: String, taskId: String, nextTriggerAt: Long): IO[Unit] =
    updateTask(sessionId, taskId)(_.copy(triggered = false, triggeredAt = None, triggerAt = nextTriggerAt)).void

  /**
   * Resolve authority BEFORE any state/time predicate; rearm may move the time either way.
   * Equal-version replicas may differ only in location. Conflicting legacy payloads have no
   * provable ordering: preserve them, warn, and refuse to fire until cancelled or rearmed.
   * Raw scans remain available to rearm/cleanup so no stored copy is hidden from repair.
   */
  private def distinctById(rows: List[ScheduledTask]): List[ScheduledTask] =
    val byId = rows.groupBy(_.id)
    rows.map(_.id).distinct.flatMap { id =>
      val copies = byId(id)
      val revision = copies.map(_.revision).max
      val newest = copies.filter(_.revision == revision)
      if newest.map(_.copy(sessionId = "")).distinct.size == 1 then newest.sortBy(_.sessionId).headOption
      else
        logger.warnSync(
          s"scheduled task authority conflict: id=$id revision=${newest.head.revision} " +
            s"sessions=${newest.map(_.sessionId).sorted.mkString(",")}; reads suppressed until cancel or rearm"
        )
        None
    }

  private def readUnlocked(filter: ScheduledTask => Boolean, sort: Boolean): IO[List[ScheduledTask]] =
    scanUnlocked(_ => true, sort = false).map { rows =>
      val selected = distinctById(rows).filter(filter)
      if sort then selected.sortBy(_.triggerAt) else selected
    }

  /**
   * Get all due (untriggered, past triggerAt) tasks across all sessions.
   * 双面化（2026-10-24）：待办（kind=todo）不走定时触发路径——排除之，
   * 由 getAllDueTodos / ScheduledTaskService.surfaceTodos 的周期浮出面接管。
   */
  def getAllDueTasks: IO[List[ScheduledTask]] =
    withStoreLock {
      val now = System.currentTimeMillis()
      readUnlocked(t => !t.triggered && t.triggerAt <= now && t.enabled && !t.isTodo, sort = true)
    }

  /**
   * Get all pending (untriggered) tasks across all sessions — used to compute next fire time.
   * 含待办（待办也是 pending 条目；调用方按 kind 分面呈现——工具 list 分节、
   * next-fire 计算把待办的下次浮出时间视同一次未来注入，无害）。
   * 🔴 已完成（completedAt 置位）不算 pending（待办体系统一批 2026-11-04）：软删
   * 行留在库里，但「下一次浮出」与 list 面都不许再看到它。
   */
  def getAllPendingTasks: IO[List[ScheduledTask]] =
    withStoreLock(readUnlocked(t => !t.triggered && !t.isCompleted, sort = false))

  private def todosUnlocked(includeCompleted: Boolean): IO[List[ScheduledTask]] =
    withStoreLock {
      readUnlocked(t => t.isTodo && !t.triggered && t.enabled && (includeCompleted || !t.isCompleted), sort = false)
        .map(_.sortBy(t => (t.triggerAt, t.createdAt)))
    }

  /**
   * Get ALL **unfinished** todos across all sessions — digest 的内容源（含尚未到
   * 浮出时间的，汇总语义：一次把未完成待办说全）。
   * 已完成（completedAt 置位）行被排除（待办体系统一批 2026-11-04：软删行留库
   * 不等于留面——digest / 周报未销账清单 / 收件箱默认面都不许看到它）。
   */
  def getAllTodos: IO[List[ScheduledTask]] = todosUnlocked(includeCompleted = false)

  /**
   * 收件箱「含已完成」读口（opt-in，`listScheduledTasks{scope:"all",
   * includeCompleted:true}` 专用）：**默认读口 [[getAllTodos]] 一字不改**，只有
   * 显式要归档/撤销视图时才走这里。刻意做成独立方法而不是默认参数——默认参数在
   * 省略括号的调用点会 eta-展开成函数值（本批实测编译报错于
   * ScheduledTaskActor/WeeklyReport 两处旧调用点）。
   */
  def getAllTodosIncludingCompleted: IO[List[ScheduledTask]] = todosUnlocked(includeCompleted = true)

  /**
   * Get due todos (untriggered, enabled, past surface time) across all sessions.
   * 🔴 触发面硬闸（待办体系统一批 2026-11-04）：已完成行在此被**读口**挡住——
   * 挡在这里而不是在 Actor 里加判断，是因为 getAllDueTodos 是浮出腿的唯一入口；
   * 漏了它，已完成待办会每日 09:00 继续浮出、missCount 继续涨、下次浮出继续
   * 顺延（spec 钉）。
   */
  def getAllDueTodos: IO[List[ScheduledTask]] =
    withStoreLock {
      val now = System.currentTimeMillis()
      readUnlocked(t => t.isTodo && !t.triggered && t.enabled && !t.isCompleted && t.triggerAt <= now, sort = false)
        .map(_.sortBy(t => (t.triggerAt, t.createdAt)))
    }

  /** Get pending (untriggered, not-completed) task count for a session. */
  def getPendingCount(sessionId: String): IO[Int] =
    loadTasks(sessionId).map(_.count(t => !t.triggered && !t.isCompleted))

  /** Toggle the enabled state of a task. Returns the updated enabled value. */
  def toggleTask(sessionId: String, taskId: String): IO[Boolean] =
    withStoreLock {
      updateByIdUnlocked(taskId)(t => t.copy(enabled = !t.enabled)).map(_.map(_.enabled).getOrElse(true))
    }

  /**
   * 通用任务更新（2026-10-29 降频/哨兵批）：f 纯函数变换目标任务（conditionState /
   * idleStreak / reviewFlag / lastFiredAt 等新字段的唯一写入口）。任务不存在 ⇒
   * 原样写回、返回 false（调用方自行决定是否告警）。
   *
   * 按 id 覆盖**所有**会话文件里的副本（跨会话 rearm 的可恢复窗口可能留下同 id
   * 第二份；只更新一份会让另一份继续浮出/触发）。id 全局唯一，`sessionId` 仅为
   * 兼容既有调用签名。
   */
  // Time-zone edits must pass through a future explicit series-edit operation which resolves
  // already-generated, not-yet-fired instances. Its policy awaits the author's decision.
  def updateTask(sessionId: String, taskId: String)(f: ScheduledTask => ScheduledTask): IO[Boolean] =
    withStoreLock {
      updateByIdUnlocked(taskId)(f).map(_.isDefined)
    }

  /**
   * Find a task by id across ALL session files（2026-09-06 升级）：list 是跨
   * 会话的，cancel 按 id 也必须跨会话——任务可能住在旧会话文件里（重启后
   * re-arm 去重的目标正是它们）。
   */
  def findTaskById(taskId: String): IO[Option[ScheduledTask]] =
    withStoreLock {
      readUnlocked(_.id == taskId, sort = false).map(_.headOption)
    }

  /**
   * Remove every task whose name == given name across ALL session files.
   * Returns the removed tasks. Same-name tasks are global-unique（upsert 语义
   * 的清理半步）；读不了的任务文件原样跳过（与 loadTasks 容错同口径）。
   * 双面化（2026-10-24）：kind 圈定清理范围——待办与定时任务可同名互不覆盖
   * （kind 不匹配者原样保留）；默认 None = 定时任务面，对存量调用方零变化。
   */
  def removeByName(
    name: String,
    kind: Option[String] = Some(ScheduledTask.KindTask),
    legacyOwner: ScheduledTask => Boolean = _ => false
  ): IO[List[ScheduledTask]] =
    withStoreLock(removeByNameUnlocked(name, kind, Some("engine"), legacyOwner))

  /**
   * Upsert（2026-09-06 事故根因修复，2026-11-14 收尾：稳定身份 + 可恢复跨会话迁移）：
   * 带 name 的任务收敛全库同 name 的**同来源**记录——例行任务重启后 re-arm 不叠加。
   *
   * 🔴 身份稳定：被替换的记录保留它自己的 `id` 与 `createdAt`；请求中新建的 id
   * 只用于「没有任何同来源记录」的首次创建。调用方据 [[Rearm.saved]] 回报/广播
   * 真实 id，不能再用请求里的临时 id。
   *
   * 🔴 来源：只合并显式同来源（`task.source`）或**可证明**的旧记录
   * （`legacyOwner`，由 producer 自己提供判据，例如「kind=task 且带 name 只可能
   * 来自 ScheduleTool」）。来源未知（`source` 为空且判据不成立）⇒ 明确拒绝，
   * 且在任何写入之前抛出；未知记录保持原样，绝不按名字猜测删除。
   *
   * 🔴 跨会话：记录被移动到请求的会话（保持同一 id）。顺序 = 先原子写目标会话
   * （失败 ⇒ 旧记录分毫未动，无丢失），再逐个清理旧会话/重复副本。第二步失败
   * 只 WARN：同一 id 可能短暂存在于两个会话文件，下一次 rearm 会再次收敛——
   * 这是可恢复的中间态，记录不丢。不存在跨文件原子事务能力（AtomicJson 只保证
   * 单文件替换），所以用这个「先写后删」的可恢复顺序，而不是假装两文件原子。
   *
   * 同名但**不同显式来源**的记录（如用户待办）一律保留，不参与替换。
   * 无 name ⇒ 直接追加（旧语义）。`task.source` 为空 ⇒ 仅合并同为空的旧记录
   * （数据批之前的调用方语义，零迁移）。
   */
  def upsertTaskByName(
    task: ScheduledTask,
    legacyOwner: ScheduledTask => Boolean = _ => false
  ): IO[Rearm] =
    task.name match
      case None => addTask(task).as(Rearm(task, Nil))
      case Some(n) =>
        withStoreLock {
          scanUnlocked(t => t.name.contains(n) && ScheduledTask.sameKind(t.kind, task.kind), sort = false).flatMap {
            candidates =>
              val owner = task.source
              val (owned, unknown) =
                if owner.isEmpty then (candidates.filter(_.source.isEmpty), Nil)
                else
                  val legacy = candidates.filter(_.source.isEmpty)
                  (
                    candidates.filter(t => owner.isDefined && t.source == owner) ++ legacy.filter(legacyOwner),
                    legacy.filterNot(legacyOwner)
                  )
              if unknown.nonEmpty then
                IO.raiseError(
                  new IllegalStateException(
                    s"legacy task ownership is unknown; rearm refused (name=$n kind=${task.kind.getOrElse(ScheduledTask.KindTask)} " +
                      s"unknown=${unknown.map(t => s"${t.id}@${t.sessionId}").mkString(",")})"
                  )
                )
              else
                val canonical =
                  distinctById(owned).sortBy(t => (-t.revision, t.createdAt, t.id)).headOption
                canonical match
                  case _ if distinctById(owned).size != owned.map(_.id).distinct.size =>
                    IO.raiseError(new IllegalStateException("conflicting task replicas; rearm refused"))
                  case None if owned.nonEmpty =>
                    IO.raiseError(new IllegalStateException("conflicting task replicas; rearm refused"))
                  case None =>
                    loadUnlocked(task.sessionId).flatMap { existing =>
                      if existing.exists(_.id == task.id) then
                        IO.raiseError(new IllegalArgumentException("duplicate task id"))
                      else saveUnlocked(task.sessionId, existing :+ task).as(Rearm(task, Nil))
                    }
                  case Some(old) =>
                    // Stable identity: keep the id and creation time the record already has;
                    // the request only supplies the new fields and the destination session.
                    val persisted =
                      task.copy(
                        id = old.id,
                        sessionId = task.sessionId,
                        createdAt = old.createdAt,
                        source = owner,
                        occurrences = old.occurrences,
                        revision = Math.addExact(owned.map(_.revision).max, 1L),
                        dueRuleVersion =
                          if old.due == task.due then old.dueRuleVersion else Math.addExact(old.dueRuleVersion, 1L),
                        dueRuleHistory =
                          if old.due == task.due then old.dueRuleHistory
                          else old.dueRuleHistory ++ old.due.toList.map(TodoRuleSnapshot(old.dueRuleVersion, _))
                      )
                    loadUnlocked(task.sessionId).flatMap { targetRows =>
                      val sameSessionIds = owned.filter(_.sessionId == task.sessionId).map(_.id).toSet
                      val target =
                        targetRows.filterNot(t => t.id == persisted.id || sameSessionIds.contains(t.id)) :+ persisted
                      // Step 1 (atomic, single file): the destination has the new record before
                      // any source copy is dropped, so a failure here cannot lose it.
                      saveUnlocked(task.sessionId, target).flatMap { _ =>
                        // Step 2 (recoverable): drop the old/duplicate copies. A failure only
                        // leaves the same id in two files; the next rearm converges it.
                        owned
                          .filter(_.sessionId != task.sessionId)
                          .groupBy(_.sessionId)
                          .toList
                          .sortBy(_._1)
                          .foldLeft(IO.unit) { case (acc, (sid, rows)) =>
                            val ids = rows.map(_.id).toSet
                            acc *> loadUnlocked(sid)
                              .flatMap(current => saveUnlocked(sid, current.filterNot(t => ids.contains(t.id))))
                              .handleErrorWith(e =>
                                logger.warn(
                                  s"rearm left a duplicate in session $sid (ids=${ids.mkString(",")}): ${e.getMessage}"
                                )
                              )
                          }
                          .as(Rearm(persisted, owned))
                      }
                    }
                end match
              end if
          }
        }

end ScheduledTaskStore

object ScheduledTaskStore:
  private val directoryLocks = new java.util.concurrent.ConcurrentHashMap[java.nio.file.Path, Semaphore[IO]]()

  private def mutexFor(path: java.nio.file.Path): Semaphore[IO] =
    import cats.effect.unsafe.implicits.global
    directoryLocks.computeIfAbsent(path, _ => Semaphore[IO](1).unsafeRunSync())

  /**
   * Production default storage directory (SharedResources assembly point; the default-root
   *  discriminator baseline). `def` rather than `val`: PathUtil.dataRoot is the test root seam,
   *  so it must be evaluated per call.
   */
  def defaultBaseDir: os.Path = PathUtil.dataRoot / "scheduled-tasks"

  /**
   * Outcome of [[ScheduledTaskStore.upsertTaskByName]].
   *
   * `saved` is the record actually stored: on rearm it carries the record's existing `id`
   * (stable identity) and the destination session. `replaced` lists the rows that were
   * replaced/dropped by this call (old copies or duplicates), for the caller's delete/update
   * broadcasts. A first create reports `replaced = Nil`.
   */
  final case class Rearm(saved: ScheduledTask, replaced: List[ScheduledTask])

  /**
   * Provenance a tool rearm may adopt without guessing: a **named scheduled task**.
   *
   * Evidence in the tree: the UI create arm (`WsTasksWorkspaceHandlers.handleCreateScheduledTask`,
   * the `kind != todo` branch) builds `ScheduledTask.create` without a `name`, and the
   * reminder panel frame (`scheduled-task.js` create payload) sends no `name`. Only
   * ScheduleTool names `kind=task` records (its todo arm names `kind=todo`). A legacy
   * `kind=todo` row with a name is therefore NOT provable — the todo UI does send a name
   * (`todoStore.js` create frames) — and stays "unknown" for a tool rearm.
   */
  val toolOwnsLegacyNamedTask: ScheduledTask => Boolean =
    t => ScheduledTask.sameKind(t.kind, Some(ScheduledTask.KindTask)) && t.name.exists(_.nonEmpty)
end ScheduledTaskStore
