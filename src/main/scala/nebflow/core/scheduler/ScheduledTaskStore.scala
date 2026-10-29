package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

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
class ScheduledTaskStore(baseDir: os.Path):

  private val logger = NebflowLogger.forName("nebflow.scheduled-task.store")

  /**
   * 全库读写互斥：任何文件读/改/写都必须持有（见类注）。CE 3.5 的 Mutex 无
   * unsafe 直构 —— lazy + global runtime 物化一次（先例：DeviceProfile.scala）。
   */
  private lazy val storeLock: Mutex[IO] =
    import cats.effect.unsafe.implicits.global
    Mutex[IO].unsafeRunSync()

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
          logger.warnSync(s"Failed to parse scheduled tasks for $sessionId: ${err.getMessage}")
          Nil
  }

  private def saveUnlocked(sessionId: String, tasks: List[ScheduledTask]): IO[Unit] =
    ensureBaseDir *> IO.blocking {
      os.write.over(sessionFile(sessionId), tasks.asJson.noSpaces)
    }

  /** 持锁读-改-写单会话文件：f 纯函数变换，返回 (新列表, 结果)。 */
  private def modifySession[A](sessionId: String)(f: List[ScheduledTask] => (List[ScheduledTask], A)): IO[A] =
    storeLock.lock.surround {
      loadUnlocked(sessionId).flatMap { existing =>
        val (updated, result) = f(existing)
        saveUnlocked(sessionId, updated).as(result)
      }
    }

  private def addUnlocked(task: ScheduledTask): IO[Unit] =
    loadUnlocked(task.sessionId).flatMap(existing => saveUnlocked(task.sessionId, existing :+ task))

  private def removeByNameUnlocked(name: String, kind: Option[String]): IO[List[ScheduledTask]] = IO.blocking {
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
              val (drop, keep) =
                list.partition(t => t.name.contains(name) && ScheduledTask.sameKind(t.kind, Some(matchKind)))
              if drop.nonEmpty then
                removed ++= drop
                os.write.over(f, keep.asJson.noSpaces)
            case Left(_) => () // unreadable: leave untouched
        }
      removed.result()
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
  // Public API — 全部持锁（单会话读-改-写走 modifySession；跨文件走 lock.surround）
  // ------------------------------------------------------------------

  def loadTasks(sessionId: String): IO[List[ScheduledTask]] =
    storeLock.lock.surround(loadUnlocked(sessionId))

  def saveTasks(sessionId: String, tasks: List[ScheduledTask]): IO[Unit] =
    storeLock.lock.surround(saveUnlocked(sessionId, tasks))

  def addTask(task: ScheduledTask): IO[Unit] =
    modifySession(task.sessionId)(existing => (existing :+ task, ()))

  def deleteTask(sessionId: String, taskId: String): IO[Unit] =
    modifySession(sessionId)(existing => (existing.filterNot(_.id == taskId), ()))

  def markTriggered(sessionId: String, taskId: String): IO[Unit] =
    modifySession(sessionId) { existing =>
      val updated = existing.map { t =>
        if t.id == taskId then t.copy(triggered = true, triggeredAt = Some(System.currentTimeMillis()))
        else t
      }
      (updated, ())
    }

  /** Reschedule a recurring task — bump triggerAt forward, keep triggered=false. */
  def rescheduleTask(sessionId: String, taskId: String, nextTriggerAt: Long): IO[Unit] =
    updateTask(sessionId, taskId)(_.copy(triggered = false, triggeredAt = None, triggerAt = nextTriggerAt)).void

  /**
   * Get all due (untriggered, past triggerAt) tasks across all sessions.
   * 双面化（2026-10-24）：待办（kind=todo）不走定时触发路径——排除之，
   * 由 getAllDueTodos / ScheduledTaskService.surfaceTodos 的周期浮出面接管。
   */
  def getAllDueTasks: IO[List[ScheduledTask]] =
    storeLock.lock.surround {
      val now = System.currentTimeMillis()
      scanUnlocked(t => !t.triggered && t.triggerAt <= now && t.enabled && !t.isTodo, sort = true)
    }

  /**
   * Get all pending (untriggered) tasks across all sessions — used to compute next fire time.
   * 含待办（待办也是 pending 条目；调用方按 kind 分面呈现——工具 list 分节、
   * next-fire 计算把待办的下次浮出时间视同一次未来注入，无害）。
   */
  def getAllPendingTasks: IO[List[ScheduledTask]] =
    storeLock.lock.surround(scanUnlocked(!_.triggered, sort = false))

  /**
   * Get ALL unfinished todos across all sessions — digest 的内容源（含尚未到
   * 浮出时间的，汇总语义：一次把未完成待办说全）。
   */
  def getAllTodos: IO[List[ScheduledTask]] =
    storeLock.lock.surround {
      scanUnlocked(t => t.isTodo && !t.triggered && t.enabled, sort = false)
        .map(_.sortBy(t => (t.triggerAt, t.createdAt)))
    }

  /** Get due todos (untriggered, enabled, past surface time) across all sessions. */
  def getAllDueTodos: IO[List[ScheduledTask]] =
    storeLock.lock.surround {
      val now = System.currentTimeMillis()
      scanUnlocked(t => t.isTodo && !t.triggered && t.enabled && t.triggerAt <= now, sort = false)
        .map(_.sortBy(t => (t.triggerAt, t.createdAt)))
    }

  /** Get pending (untriggered) task count for a session. */
  def getPendingCount(sessionId: String): IO[Int] =
    loadTasks(sessionId).map(_.count(!_.triggered))

  /** Toggle the enabled state of a task. Returns the updated enabled value. */
  def toggleTask(sessionId: String, taskId: String): IO[Boolean] =
    modifySession(sessionId) { existing =>
      val updated = existing.map { t =>
        if t.id == taskId then t.copy(enabled = !t.enabled)
        else t
      }
      (updated, updated.find(_.id == taskId).map(_.enabled).getOrElse(true))
    }

  /**
   * 通用任务更新（2026-10-29 降频/哨兵批）：f 纯函数变换目标任务（conditionState /
   * idleStreak / reviewFlag / lastFiredAt 等新字段的唯一写入口）。任务不存在 ⇒
   * 原样写回、返回 false（调用方自行决定是否告警）。
   */
  def updateTask(sessionId: String, taskId: String)(f: ScheduledTask => ScheduledTask): IO[Boolean] =
    modifySession(sessionId) { existing =>
      val updated = existing.map(t => if t.id == taskId then f(t) else t)
      (updated, updated.exists(_.id == taskId))
    }

  /**
   * Find a task by id across ALL session files（2026-09-06 升级）：list 是跨
   * 会话的，cancel 按 id 也必须跨会话——任务可能住在旧会话文件里（重启后
   * re-arm 去重的目标正是它们）。
   */
  def findTaskById(taskId: String): IO[Option[ScheduledTask]] =
    storeLock.lock.surround {
      scanUnlocked(_.id == taskId, sort = false).map(_.headOption)
    }

  /**
   * Remove every task whose name == given name across ALL session files.
   * Returns the removed tasks. Same-name tasks are global-unique（upsert 语义
   * 的清理半步）；读不了的任务文件原样跳过（与 loadTasks 容错同口径）。
   * 双面化（2026-10-24）：kind 圈定清理范围——待办与定时任务可同名互不覆盖
   * （kind 不匹配者原样保留）；默认 None = 定时任务面，对存量调用方零变化。
   */
  def removeByName(name: String, kind: Option[String] = Some(ScheduledTask.KindTask)): IO[List[ScheduledTask]] =
    storeLock.lock.surround(removeByNameUnlocked(name, kind))

  /**
   * Upsert（2026-09-06 事故根因修复）：带 name 的任务替换全库所有同 name 任务
   * （重启后 re-arm 例行任务同名自动去重，收敛单份）；无 name 任务按旧语义
   * 直接追加、不参与去重。返回被替换掉的旧任务列表。双面化（2026-10-24）：
   * 清理半步按 task 自身 kind 圈定——todo upsert 不吃同名定时任务，反之亦然。
   * 🔒 同一 permit 内原子完成（清理半步 + 追加），并发 upsert 不再互相掏空。
   */
  def upsertTaskByName(task: ScheduledTask): IO[List[ScheduledTask]] =
    task.name match
      case None => addTask(task).as(Nil)
      case Some(n) =>
        storeLock.lock.surround {
          removeByNameUnlocked(n, task.kind).flatMap(removed => addUnlocked(task).as(removed))
        }

end ScheduledTaskStore
