package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{ActorRef, AgentCommand, AgentEvent}
import nebflow.shared.NebflowLogger

/**
 * MergeQueue —— delegate 合并队列（R7/S11/S12；worktree-judge-fifo 批 #1224 增补
 * §3.2/§3.3：**排序键＝ready-to-merge 终态（完成）时间**，非创建时间；每 project
 * 一条，代码写死 FIFO）.
 *
 * 执行下沉（S12 改写 R7）：轮到合并 → **system reminder 注入 delegate**（与节点
 * 提醒同通道：`<system-reminder>` 包裹的合并指令，经 turn 边界注入）→
 * **delegate 自行执行合并**（`merge --no-ff` 回基分支）→ 合并轮终态再判
 * （completed / 冲突 blocked，判定与轮转在 [[nebflow.agent.BackoffSupervisor]] 的
 * 终态钩子）。**引擎不代跑任何 git merge**。
 *
 * 调度集中：本 object 只做队列与轮转（单飞行 per project：一个项目的合并轮在飞时
 * 后续 ready-to-merge 排队等待）。队列状态为进程内运行态（actor 死亡即消亡——
 * 与被管 delegate 的 actor 生命周期一致），每次状态迁移**同步落 ChainStore
 * `merge-queue` 行**（跨重启审计可查；重启后未完成的队列条目随 actor 消亡——
 * 已知限制，交付回报注明，恢复面属 #1225 的 resume 语义）。
 *
 * 与旧引擎 merge 节点互斥面（`core.project` MergeMutexPolicy / NodeGating）**零
 * 关系、零引用**（增补件 §3.2：旧引擎队列面不相干、不在本批范围）。
 */
object MergeQueue:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.mergequeue")

  /** 注入源标记（UserInput.source）——UI 气泡归因 + 审计 grep 锚。 */
  val Source: String = "merge-queue"

  /** 注入事件行种类（ChainStore）。 */
  val EntryKind: String = "merge-queue"

  /**
   * 一条排队项。`readyAt` = ready-to-merge 终态（完成）时间——**唯一排序键**
   * （S11；同刻并列按 delegateId 字典序，保决定性）。`baseWs` = 合并执行的工作区
   * （worktree 腿 = 基工作区——worktree 里 `git merge` 会并进自己的分支，方向错）；
   * `baseBranch` = spawn 时基工作区的当前分支（合并目标）。
   */
  final case class Entry(
      delegateId: String,
      branch: String,
      baseWs: String,
      baseBranch: String,
      worktree: Boolean,
      projectKey: String,
      taskId: Option[String],
      parentSessionId: String,
      description: String,
      readyAt: Long,
      childRef: ActorRef[AgentCommand],
      supervisorRef: ActorRef[AgentEvent],
      reminderSentAt: Option[Long] = None
  )

  // ── 进程内运行态（单飞行 per project） ────────────────────────────────
  private val lock = new Object
  private var queues: Map[String, List[Entry]] = Map.empty // projectKey → FIFO（readyAt 升序）
  private var inFlight: Set[String] = Set.empty            // projectKey → 合并轮在飞

  // ── 查询面（审计 / spec） ─────────────────────────────────────────────

  def find(delegateId: String): Option[Entry] = lock.synchronized {
    queues.values.flatten.find(_.delegateId == delegateId)
  }

  def pending(projectKey: String): List[Entry] = lock.synchronized {
    queues.getOrElse(projectKey, Nil)
  }

  def isInFlight(projectKey: String): Boolean = lock.synchronized(inFlight.contains(projectKey))

  /** 测试复位（生产无调用方）。 */
  private[delegate] def reset(): Unit = lock.synchronized {
    queues = Map.empty
    inFlight = Set.empty
  }

  // ── 入队与轮转 ────────────────────────────────────────────────────────

  /**
   * ready-to-merge 入队并尝试轮转。FIFO 排序键 = `readyAt`（完成时间）——
   * 后到不得插队；排序在**本单点**（spec 判红锚）。锁定区只做队列状态机；
   * 中选的注入在本函数尾部（锁外）执行——actor 消息投递（`!` = IO）不在
   * 锁内跑（mail 箱投递虽非阻塞，锁内同步 IO 执行是反面先例）。
   */
  def enqueue(entry: Entry): Unit =
    lock.synchronized {
      val q = queues.getOrElse(entry.projectKey, Nil) :+ entry
      val sorted = q.sortBy(e => (e.readyAt, e.delegateId)) // S11：完成时间 FIFO
      queues = queues.updated(entry.projectKey, sorted)
      record(entry, "enqueued", Json.obj("readyAt" -> entry.readyAt.asJson, "queueDepth" -> sorted.size.asJson))
      pumpLocked(entry.projectKey)
    }.foreach(fireMergeReminder)

  /**
   * delegate 终态（任意形态：合并轮完成 / 合并窗超时被取消 / 直接终态）时的
   * 出队钩子——若该 delegate 是本 project 在飞合并的持有者，释放并轮转下一条。
   * 幂等（查无条目 = no-op）。由 BackoffSupervisor 的终态路径（gate source=delegate）
   * 调用。
   */
  def onTerminal(delegateId: String): Unit =
    lock.synchronized {
      findAndRemoveLocked(delegateId) match
        case None => None
        case Some(entry) =>
          if entry.reminderSentAt.isDefined then
            inFlight = inFlight - entry.projectKey
            record(entry, "dequeued", Json.obj("phase" -> "merge-round-done".asJson))
          else record(entry, "dequeued", Json.obj("phase" -> "pre-injection".asJson))
          pumpLocked(entry.projectKey)
    }.foreach(fireMergeReminder)

  /** 排序纯函数（spec 判红锚：完成时间序，创建时间序必红）。 */
  private[delegate] def sortByReadyAt(entries: List[Entry]): List[Entry] =
    entries.sortBy(e => (e.readyAt, e.delegateId))

  // lock held
  private def findAndRemoveLocked(delegateId: String): Option[Entry] =
    queues.find((_, q) => q.exists(_.delegateId == delegateId)).flatMap { (key, _) =>
      val entry = queues(key).find(_.delegateId == delegateId)
      queues = queues.updated(key, queues(key).filterNot(_.delegateId == delegateId))
      if queues(key).isEmpty then queues = queues - key
      entry
    }

  // lock held
  private def pumpLocked(projectKey: String): Option[Entry] =
    if inFlight.contains(projectKey) then None
    else
      queues.get(projectKey).flatMap(_.headOption) match
        case None => None
        case Some(head) =>
          inFlight = inFlight + projectKey
          Some(head)

  /**
   * 合并轮注入（S12）：`<system-reminder>` 包裹的合并指令，直接投给 delegate
   * actor 的 idle 队（replyTo = 监督者——合并轮的 AgentEvent.Completed 必须回到
   * 终态钩子再判）。`reminderSentAt` 与 ChainStore 行 = 增补件 §3.3「代码侧记录
   * reminder 已发状态（审计可查）」。**锁外执行**（enqueue/onTerminal 尾部）——
   * `ActorRef.!` 是 IO，这里用 `unsafeRunSync` 触发投递（GatewayMain 同款先例；
   * `!` = mail 箱入队，非阻塞，同步触发无等待面）。
   */
  private def fireMergeReminder(entry: Entry): Unit =
    val brief = mergeBriefFor(entry)
    import cats.effect.unsafe.implicits.global
    (entry.childRef ! AgentCommand.UserInput(
      text = brief,
      replyTo = Some(entry.supervisorRef),
      source = Some(Source),
      eventType = Some("merge-turn")
    )).unsafeRunSync()
    val stamped = entry.copy(reminderSentAt = Some(System.currentTimeMillis()))
    lock.synchronized {
      queues = queues.updated(
        entry.projectKey,
        queues.getOrElse(entry.projectKey, Nil).map(e => if e.delegateId == entry.delegateId then stamped else e)
      )
    }
    record(stamped, "reminder-sent", Json.obj("mergeRound" -> true.asJson))

  /**
   * 合并指令文本（🔴 模型可见文案——§16 待过目标注：本批最小成稿，终稿归 N3
   * 提示词批）。worktree 腿与就地腿同形：`git -C <baseWs> merge --no-ff <branch>`
   * （worktree 里 merge 会并进 delegate 自己的分支——方向必须钉死在基工作区）。
   */
  def mergeBriefFor(entry: Entry): String =
    s"""<system-reminder>
Merge turn (FIFO): your branch `${entry.branch}` has been judged ready-to-merge and it is now your turn in the queue for `${entry.baseWs}`.
Execute the merge EXACTLY like this (single command, no flags beyond these):
  git -C ${entry.baseWs} merge --no-ff ${entry.branch}
This merges your branch into the base branch `${entry.baseBranch}`. Do NOT merge anywhere else, do NOT force-push, do NOT rebase, do NOT touch other branches or other sessions' work.
If git refuses or reports conflicts: STOP — change nothing, resolve nothing; report the refusal/conflict verbatim in your final text, call delegate_report(category="blocked", detail=<the refusal/conflict>), and end your turn.
On success, call delegate_report(category="finish", detail=<what the merge landed>) and end with your final report; the engine re-judges the merge round.
</system-reminder>"""

  /** ChainStore 审计行（fail-soft：账本失败绝不阻断队列）。 */
  private def record(entry: Entry, action: String, extra: Json): Unit =
    try
      ChainStore.appendSync(
        ChainStore.keyFor(entry.taskId, entry.delegateId),
        EntryKind,
        Json.obj(
          "action" -> action.asJson,
          "delegateId" -> entry.delegateId.asJson,
          "branch" -> entry.branch.asJson,
          "projectKey" -> entry.projectKey.asJson,
          "readyAt" -> entry.readyAt.asJson
        ).deepMerge(extra)
      )
    catch
      case e: Throwable =>
        logger.warn(s"MergeQueue ChainStore line failed ($action): ${e.getMessage}")

  /** IO 包装（BackoffSupervisor 终态路径是 IO 上下文）。 */
  def enqueueIO(entry: Entry): IO[Unit] = IO.blocking(enqueue(entry))
  def onTerminalIO(delegateId: String): IO[Unit] = IO.blocking(onTerminal(delegateId))

end MergeQueue
