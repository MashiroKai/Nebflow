package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder}
import nebflow.actor.status
import nebflow.core.SubAgentTaskStorePort
import nebflow.core.delegate.{DelegateRegistry, DelegateSessions}
import nebflow.shared.{NebflowLogger, PathUtil, SubAgentTask}

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-G/M4):SubAgentTask 模型与伴生 codec 已剪出
// 下沉 nebflow.shared(新文件 shared/SubAgentTaskModels.scala,整块逐字);本文件 import 改指。
// 同裁定 R-A/M1:本类原地 extends core 的窄口 SubAgentTaskStorePort(签名镜像,零行为差)。

/**
 * File-based store for SubAgentTask records.
 *
 * Layout: `~/.nebflow/subagent-tasks/<parentSessionId>.json` — one file per
 * parent session, containing a list of tasks. Completed/failed tasks are
 * retained for a short period for audit, then pruned.
 */
class SubAgentTaskStore(baseDir: os.Path) extends SubAgentTaskStorePort:

  private val logger = NebflowLogger.forName("nebflow.subagent-task.store")

  /**
   * 并发写锁（2026-08-18 11:08 重放实证）：多个 Delegate 并发 spawn 时
   * recordTask/updateStatus 同时 load→modify→save 同一文件——读-改-写竞态
   * 导致任务记录互相覆盖甚至被半写状态解析失败后写空（[]）。load-modify-save
   * 必须整体持锁串行化。ReentrantLock + IO.blocking：JVM 内进程级互斥，
   * 无创建点改动（SharedResources 默认参数 + 测试共用同一构造签名）。
   */
  private val lock = new java.util.concurrent.locks.ReentrantLock()

  private def withLock[A](body: => A): IO[A] = IO.blocking {
    lock.lock()
    try body
    finally lock.unlock()
  }

  private def sessionFile(parentSessionId: String): os.Path =
    // Guard: a blank parentSessionId (caller bug — e.g. an unsupervised
    // context with no session) must not silently write to ".json" where it
    // pollutes the directory and is never read back. Callers already wrap
    // store operations in handleErrorWith, so this degrades to a warn.
    if parentSessionId.isBlank then
      throw new IllegalArgumentException(
        s"subAgentTaskStore: blank parentSessionId refused (would write ${baseDir}/.json)"
      )
    baseDir / s"$parentSessionId.json"

  private def ensureBaseDirSync(): Unit =
    if !os.exists(baseDir) then os.makeDir.all(baseDir)

  private def readTasksSync(parentSessionId: String): List[SubAgentTask] =
    val f = sessionFile(parentSessionId)
    if !os.exists(f) then Nil
    else
      decode[List[SubAgentTask]](os.read(f)) match
        case Right(list) => list
        case Left(err) =>
          logger.warnSync(s"Failed to parse subagent tasks for $parentSessionId: ${err.getMessage}")
          Nil

  private def writeTasksSync(parentSessionId: String, tasks: List[SubAgentTask]): Unit =
    ensureBaseDirSync()
    os.write.over(sessionFile(parentSessionId), tasks.asJson.noSpaces)

  def loadTasks(parentSessionId: String): IO[List[SubAgentTask]] =
    withLock(readTasksSync(parentSessionId))

  /**
   * 星型子代理按父投影（delegate-ui 批 2026-10-08 · G3 查询面）：`GET /api/delegates/
   * :id/children` 的唯一数据源——本 store 本就按父分文件（`<parentSessionId>.json`），
   * 本投影 = 该文件的整表读（持锁纪律与 loadTasks 同缝）；条目自带 `source`
   * （subagent/subtask/delegate——嵌套 Delegate 也在本域），过滤归消费端。查无文件 =
   * Nil（子代理计数 0，绝不 404——空集是合法形态）。
   */
  def findByParent(parentSessionId: String): IO[List[SubAgentTask]] =
    withLock(readTasksSync(parentSessionId))

  /**
   * 全量任务行（#566 修复批：/api/chains 行投影的 taskStatus 权威读数——delegate
   * 终态（completed/failed/cancelled/ready-to-merge）的引擎侧唯一状态源）。
   * **单遍扫目录**（一次读全部父文件，不逐行 findByTaskId 全扫 N 遍——行投影
   * 每 refresh 一次，量级 = 活跃父会话文件数）。fail-soft：坏文件跳过。
   */
  def findAllTasks(): IO[List[SubAgentTask]] =
    withLock {
      if !os.exists(baseDir) then List.empty[SubAgentTask]
      else
        os.list(baseDir)
          .filter(_.toString.endsWith(".json"))
          .flatMap(f => decode[List[SubAgentTask]](os.read(f)).toOption.getOrElse(List.empty[SubAgentTask]))
          .toList
    }

  def saveTasks(parentSessionId: String, tasks: List[SubAgentTask]): IO[Unit] =
    withLock(writeTasksSync(parentSessionId, tasks))

  /** Record a new task at spawn time. */
  def recordTask(task: SubAgentTask): IO[Unit] =
    withLock {
      // #692：statusAt 缺省 = spawnedAt（新行从落盘起就有 watchdog 判据输入）。
      writeTasksSync(
        task.parentSessionId,
        readTasksSync(task.parentSessionId) :+ task.copy(statusAt = task.statusAt.orElse(Some(task.spawnedAt)))
      )
      upgradeParentOrchestrationMode(task.parentSessionId)
    }

  /**
   * G1 编排模式惰性升级挂钩（delegate-ui 批 2026-10-08，**单点**）：星型子代理记账
   * 动作（本 store recordTask）= 模式升级的唯一触发面——任何工具形（Subagent /
   * Subtask / 嵌套 Delegate）一经记账，父 delegate 的 orchestrationMode 升至
   * `subagent-star`（flow 结构由 AgentFlow 链登记点升 `agentflow`，值域顶、只上
   * 不下——见 [[DelegateRegistry.upgradeOrchestrationMode]] 的秩守卫）。双闸保守：
   * 前缀闸（`delegate-`）+ 记录闸（父确有 DelegateRegistry 记录）⇒ root/节点会话
   * 的同名记账零触碰；升级失败只 WARN，绝不影响记账主路径（fail-soft）。
   */
  private def upgradeParentOrchestrationMode(parentSessionId: String): Unit =
    if DelegateSessions.isDelegateSession(Some(parentSessionId)) then
      DelegateRegistry.upgradeOrchestrationMode(parentSessionId, DelegateRegistry.ModeSubagentStar) match
        case Right(()) => ()
        case Left(err) =>
          logger.warnSync(s"orchestrationMode upgrade skipped for '$parentSessionId': $err")

  /**
   * Update a task's status. #692：每次状态写入都刷新 statusAt = now——它是重启
   * 屏障 watchdog 的「当前状态已持续多久」判据输入（restarting 行超龄判读）。
   */
  def updateStatus(
    parentSessionId: String,
    taskId: String,
    status: String,
    retryCount: Option[Int] = None,
    lastError: Option[String] = None,
    completedAt: Option[Long] = None
  ): IO[Unit] =
    withLock {
      val now = System.currentTimeMillis()
      val updated = readTasksSync(parentSessionId).map { t =>
        if t.taskId == taskId then
          t.copy(
            status = status,
            retryCount = retryCount.getOrElse(t.retryCount),
            lastError = lastError.orElse(t.lastError),
            completedAt = completedAt.orElse(t.completedAt),
            statusAt = Some(now)
          )
        else t
      }
      writeTasksSync(parentSessionId, updated)
    }

  /** Remove a task (called after successful completion + grace period). */
  def removeTask(parentSessionId: String, taskId: String): IO[Unit] =
    withLock {
      writeTasksSync(parentSessionId, readTasksSync(parentSessionId).filterNot(_.taskId == taskId))
    }

  /**
   * V1 (2026-09-03): mark every in-flight task of a parent session cancelled.
   * Called by deleteSession's cascade-stop — the parent (and its children with
   * it) is being deleted, so no result can ever be delivered; the terminal
   * record keeps the task store truthful AND keeps the V2 startup sweep from
   * later "recovering" tasks whose parent session no longer exists. Returns
   * the cancelled task ids (for the delete log).
   */
  def cancelRunningForParent(parentSessionId: String, reason: String): IO[List[String]] =
    withLock {
      val now = System.currentTimeMillis()
      val existing = readTasksSync(parentSessionId)
      val inFlight = existing.filter(t => t.status == "running" || t.status == "restarting")
      if inFlight.isEmpty then Nil
      else
        val updated = existing.map { t =>
          if t.status == "running" || t.status == "restarting" then
            t.copy(status = "cancelled", lastError = Some(reason), completedAt = Some(now))
          else t
        }
        writeTasksSync(parentSessionId, updated)
        inFlight.map(_.taskId)
    }

  /**
   * Find all in-flight tasks ("running" ∪ "restarting" — #692 修批：restarting 行
   * 同样在飞，10-21 实测僵尸正是「respawn 半途引擎重启 → restarting 行既不在
   * running 清单里被启动清扫捞到、又无 actor 接管」卡死 7.5h)。消费方 = 启动
   * 恢复清扫（SubAgentStartupRecovery）与热重启在飞判定——两处都需要「还有没
   * 收尾的活」，restarting 理应同票。
   */
  def findRunningTasks: IO[List[SubAgentTask]] = IO
    .blocking {
      if !os.exists(baseDir) then Nil
      else
        os.list(baseDir)
          .flatMap { f =>
            if f.toString.endsWith(".json") then
              decode[List[SubAgentTask]](os.read(f)) match
                case Right(list) => list.filter(t => t.status == "running" || t.status == "restarting")
                case Left(_) => Nil
            else Nil
          }
          .toList
    }
    .handleErrorWith(e => logger.warn(s"findRunningTasks failed: ${e.getMessage}").as(Nil))

  /**
   * AgentControl（spec §2 status 命令）：按 taskId 全目录反查任务记录——registry
   * 无记录时检测孤儿任务（task status=running 但 actor 已不存在），也用于
   * restart 前区分 ephemeral Delegate（有任务记录）与 persistent（无记录）。
   * 文件数 = 活跃父会话数（量小），线性扫描可接受。
   */
  def findByTaskId(taskId: String): IO[Option[SubAgentTask]] =
    IO
      .blocking {
        if !os.exists(baseDir) then None
        else
          os.list(baseDir)
            .flatMap { f =>
              if f.toString.endsWith(".json") then
                decode[List[SubAgentTask]](os.read(f)) match
                  case Right(list) => list.filter(_.taskId == taskId)
                  case Left(_) => Nil
              else Nil
            }
            .headOption
      }
      .handleErrorWith(e => logger.warn(s"findByTaskId failed: ${e.getMessage}").as(None))

end SubAgentTaskStore
