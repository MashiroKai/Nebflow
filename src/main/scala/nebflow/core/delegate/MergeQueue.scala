package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{ActorPath, ActorRef, AgentCommand, AgentEvent}
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.FiniteDuration

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
    reminderSentAt: Option[Long] = None,
    /**
     * 外部执行器腿的合并轮注入通道（引擎加固批 2026-10-09，P1-2）：None = 内部
     * 腿（childRef actor 注入——既有单点）；Some = 外部腿（续聊通道注入——
     * mailbox 排队 / CLI resume，settle 侧终结再判）。MergeQueue 零
     * core.executor import：通道函数由 settle 构造 Entry 时闭包携带。
     */
    external: Option[ExternalLeg] = None,
    /**
     * 跨重启恢复腿（P2-5 boot sweep 重挂的条目）：原会话 actor 已死，注入走
     * revive 通道（内部腿 = 续起会话 + [[rekey]] 换键；外部腿 = CLI resume，
     * 同 [[external]] 通道形）。readyAt 由 sweep 从中断账本行原值回挂（FIFO 序
     * 跨重启保真）。
     */
    recovered: Option[Recovered] = None
  )

  /**
   * 外部腿通道（P1-2）：`send(brief)` = 把合并指令送进外部 delegate 的续聊通道
   * （live → mailbox 排队；终态 → CLI resume）。`Right(())` = 送进成功；`Left`
   * = 通道失败（注入失败的条目出队 + 窗释放，绝不楔死队列）。
   */
  final case class ExternalLeg(send: String => IO[Either[String, Unit]])

  /**
   * 恢复腿通道（P2-5）：`revive(brief)` = 让已死的会话重新吃到合并指令。内部腿
   * 的实现 = 续起一个新会话执行合并并 [[rekey]] 换键（Right(()) 已含换键事实）。
   */
  final case class Recovered(originId: String, revive: String => IO[Either[String, Unit]])

  // ── 进程内运行态（单飞行 per project） ────────────────────────────────
  private val lock = new Object
  private var queues: Map[String, List[Entry]] = Map.empty // projectKey → FIFO（readyAt 升序）
  private var inFlight: Set[String] = Set.empty // projectKey → 合并轮在飞

  /**
   * 通道条目（`external`/`recovered` 腿——P1-2/P2-5）的占位 ref：actor 注入路径
   * （fireViaActor）对这两类条目永不走（fireMergeReminder 按通道分派），ref 仅
   * 因 Entry 字段非可选而需要在场。`!` = IO.unit（误投即 no-op，不炸）。
   */
  val NoopChild: ActorRef[AgentCommand] =
    new ActorRef[AgentCommand]:
      def path = ActorPath("noop", "merge-queue/channel-child")
      def !(msg: AgentCommand) = IO.unit
      def ?[Reply](makeMsg: ActorRef[Reply] => AgentCommand, timeout: Option[FiniteDuration]) =
        IO.raiseError(new RuntimeException("no-op channel ref cannot ask"))

  val NoopSupervisor: ActorRef[AgentEvent] =
    new ActorRef[AgentEvent]:
      def path = ActorPath("noop", "merge-queue/channel-supervisor")
      def !(msg: AgentEvent) = IO.unit
      def ?[Reply](makeMsg: ActorRef[Reply] => AgentEvent, timeout: Option[FiniteDuration]) =
        IO.raiseError(new RuntimeException("no-op channel ref cannot ask"))

  // ── 查询面（审计 / spec） ─────────────────────────────────────────────

  def find(delegateId: String): Option[Entry] = lock.synchronized {
    queues.values.flatten.find(_.delegateId == delegateId)
  }

  def pending(projectKey: String): List[Entry] = lock.synchronized {
    queues.getOrElse(projectKey, Nil)
  }

  def isInFlight(projectKey: String): Boolean = lock.synchronized(inFlight.contains(projectKey))

  /**
   * 行内排队槽（delegate-ui 批 2026-10-08 · G5 读面单点）：某 delegate 当下在本队列
   * 的实时槽位投影。🔴 判据**单点** = 本队列的进程内状态（`queues` + `inFlight`，
   * 引擎闸本体）——🔴 禁读 ChainStore `merge-queue` 历史行（审计面，非实时真源）、
   * 🔴 禁读文件票层（`.nebflow/locks/main-merge.queue`，过渡期旧层）、🔴 禁从事件流
   * 回放。排序键 = 引擎自己的 FIFO 序（`sortByReadyAt`：readyAt ↑ → delegateId ↑），
   * 行投影不二次派生次序。
   *
   * 键语义与 NodeList 载荷键同族（`mergeQueue` = 可见性槽「谁挡着我/谁持窗」、
   * `mergeQueuePos` = 位次「我排第几」）；`None` = 未排队（不在册）⇒ 消费行**缺键**
   * （与 NodeList「只收非空项」同纪律）。`projectKey` 原样透出——P2 把键切到
   * workspace 维度时本面零改动、消费端语义自动跟随（两批零耦合）。
   */
  final case class QueueView(
    delegateId: String,
    projectKey: String,
    branch: String,
    baseBranch: String,
    /** 1 起位次（引擎 FIFO 序；队头 = 1）。 */
    position: Int,
    total: Int,
    /** 严格排在本位之前的 delegateId（引擎序）。队头 = Nil。 */
    ahead: List[String],
    /** 合并窗持有者（`inFlight` 的队头条目 id）；None = 窗空闲（纯排队）。 */
    holder: Option[String],
    /** 本条是否已被注入合并轮指令（reminder 已发 = 持窗者的动作面已启动）。 */
    reminderSent: Boolean,
    readyAt: Long
  )

  def queueViewOf(delegateId: String): Option[QueueView] = lock.synchronized {
    queues.toList
      .collectFirst { case (projectKey, q) if q.exists(_.delegateId == delegateId) => projectKey }
      .flatMap { projectKey =>
        val q = sortByReadyAt(queues.getOrElse(projectKey, Nil))
        q.find(_.delegateId == delegateId).map { entry =>
          val pos = q.indexWhere(_.delegateId == delegateId) + 1
          QueueView(
            delegateId = delegateId,
            projectKey = projectKey,
            branch = entry.branch,
            baseBranch = entry.baseBranch,
            position = pos,
            total = q.size,
            ahead = q.take(pos - 1).map(_.delegateId),
            holder = if inFlight.contains(projectKey) then q.headOption.map(_.delegateId) else None,
            reminderSent = entry.reminderSentAt.isDefined,
            readyAt = entry.readyAt
          )
        }
      }
  }

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
    lock
      .synchronized {
        val q = queues.getOrElse(entry.projectKey, Nil) :+ entry
        val sorted = q.sortBy(e => (e.readyAt, e.delegateId)) // S11：完成时间 FIFO
        queues = queues.updated(entry.projectKey, sorted)
        record(entry, "enqueued", Json.obj("readyAt" -> entry.readyAt.asJson, "queueDepth" -> sorted.size.asJson))
        pumpLocked(entry.projectKey)
      }
      .foreach(fireMergeReminder)

  /**
   * delegate 终态（任意形态：合并轮完成 / 合并窗超时被取消 / 直接终态）时的
   * 出队钩子——若该 delegate 是本 project 在飞合并的持有者，释放并轮转下一条。
   * 幂等（查无条目 = no-op）。由 BackoffSupervisor 的终态路径（gate source=delegate）
   * 调用。
   */
  def onTerminal(delegateId: String): Unit =
    lock
      .synchronized {
        findAndRemoveLocked(delegateId) match
          case None => None
          case Some(entry) =>
            if entry.reminderSentAt.isDefined then
              inFlight = inFlight - entry.projectKey
              record(entry, "dequeued", Json.obj("phase" -> "merge-round-done".asJson))
            else record(entry, "dequeued", Json.obj("phase" -> "pre-injection".asJson))
            pumpLocked(entry.projectKey)
      }
      .foreach(fireMergeReminder)

  /** 排序纯函数（spec 判红锚：完成时间序，创建时间序必红）。 */
  private[delegate] def sortByReadyAt(entries: List[Entry]): List[Entry] =
    entries.sortBy(e => (e.readyAt, e.delegateId))

  /**
   * 恢复腿换键（P2-5）：内部腿续起会话产生**新会话 id**——条目换键后，合并轮
   * 终态（新 id 的 onCompleted → `MergeQueue.find(newId)` 的 mergeRound 判定与
   * `onTerminal(newId)` 出队）才能正确驱动单飞行窗。换键即盖章
   * `reminderSentAt`（revive 成功 = 合并指令已送达新会话——合并轮在飞）。幂等：
   * 查无旧键 = no-op。换键事实落 ChainStore `recovered-rekeyed` 行。
   */
  def rekey(originId: String, newId: String): Unit =
    if originId == newId then ()
    else
      lock
        .synchronized {
          findAndRemoveLocked(originId) match
            case None => None
            case Some(entry) =>
              val rekeyed = entry.copy(
                delegateId = newId,
                recovered = None,
                reminderSentAt = Some(System.currentTimeMillis()) // 换键即盖章：brief 已送达续起会话
              )
              val q = queues.getOrElse(entry.projectKey, Nil) :+ rekeyed
              queues = queues.updated(entry.projectKey, sortByReadyAt(q))
              record(
                entry,
                "recovered-rekeyed",
                Json.obj(
                  "originId" -> originId.asJson,
                  "newId" -> newId.asJson,
                  "phase" -> "merge-round-superseded".asJson
                )
              )
              Some(rekeyed)
        }
        .foreach { _ => () } // 换键条目已在飞（窗已持）——不重复 pump

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
   * 合并轮注入（S12；引擎加固批 2026-10-09 扩展 P1-2/P2-5 三通道）：按条目通道
   * 分派——内部腿 = 既有 actor 注入（逐字）；外部腿（P1-2）/ 恢复腿（P2-5）=
   * `send/revive` 闭包注入（mailbox / CLI resume / 续起会话）。`reminderSentAt`
   * 与 ChainStore 行 = 增补件 §3.3「代码侧记录 reminder 已发状态（审计可查）」。
   * **锁外执行**（enqueue/onTerminal/rekey 尾部）；通道注入失败 = 条目出队＋窗
   * 释放＋WARN＋账本行（绝不楔死队列单飞行窗）。
   */
  private def fireMergeReminder(entry: Entry): Unit =
    entry.recovered
      .orElse(entry.external.map(l => Recovered(entry.delegateId, l.send))) match
      case Some(recov) => fireViaChannel(entry, recov)
      case None => fireViaActor(entry)

  /** 既有单点（内部腿 actor 注入——原 fireMergeReminder 本体逐字）。 */
  private def fireViaActor(entry: Entry): Unit =
    val brief = mergeBriefFor(entry)
    import cats.effect.unsafe.implicits.global
    (entry.childRef ! AgentCommand.UserInput(
      text = brief,
      replyTo = Some(entry.supervisorRef),
      source = Some(Source),
      eventType = Some("merge-turn")
    )).unsafeRunSync()
    stampReminder(entry)
    record(stampOf(entry), "reminder-sent", Json.obj("mergeRound" -> true.asJson))

  /** 外部腿/恢复腿通道注入（P1-2/P2-5）。 */
  private def fireViaChannel(entry: Entry, recov: Recovered): Unit =
    import cats.effect.unsafe.implicits.global
    recov.revive(mergeBriefFor(entry)).attempt.unsafeRunSync() match
      case Right(Right(())) =>
        // 换键腿（内部恢复）已在 rekey 盖章；未换键腿（外部通道，同 id）在此盖章。
        stampReminder(entry)
        record(
          stampOf(entry),
          "reminder-sent",
          Json.obj(
            "mergeRound" -> true.asJson,
            "viaChannel" -> (if entry.recovered.isDefined then "recovered" else "external").asJson,
            "originId" -> recov.originId.asJson
          )
        )
      case Right(Left(err)) => dropFailedInject(entry, err)
      case Left(e) => dropFailedInject(entry, e.getMessage)

    end match

  end fireViaChannel

  /** 通道注入失败：条目出队＋窗释放＋pump 下一条（绝不楔死单飞行窗）。 */
  private def dropFailedInject(entry: Entry, err: String): Unit =
    logger.warn(
      s"merge-queue channel inject failed for ${entry.delegateId} — entry dropped, single-flight window released: ${err.take(300)}"
    )
    lock
      .synchronized {
        findAndRemoveLocked(entry.delegateId)
        inFlight = inFlight - entry.projectKey
        pumpLocked(entry.projectKey)
      }
      .foreach(fireMergeReminder)
    record(entry, "inject-failed", Json.obj("error" -> err.take(300).asJson))

  /**
   * reminderSentAt 盖章（队列内按 delegateId 定位——换键腿的章已由 rekey 落、
   * 旧 id 定位不到 = 自然 no-op）。
   */
  private def stampReminder(entry: Entry): Unit =
    if queues.getOrElse(entry.projectKey, Nil).exists(_.delegateId == entry.delegateId) then
      val now = System.currentTimeMillis()
      lock.synchronized {
        queues = queues.updated(
          entry.projectKey,
          queues
            .getOrElse(entry.projectKey, Nil)
            .map(e => if e.delegateId == entry.delegateId then e.copy(reminderSentAt = Some(now)) else e)
        )
      }

  private def stampOf(entry: Entry): Entry = entry.copy(reminderSentAt = Some(System.currentTimeMillis()))

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
        Json
          .obj(
            "action" -> action.asJson,
            "delegateId" -> entry.delegateId.asJson,
            "branch" -> entry.branch.asJson,
            "projectKey" -> entry.projectKey.asJson,
            "readyAt" -> entry.readyAt.asJson
          )
          .deepMerge(extra)
      )
    catch
      case e: Throwable =>
        logger.warn(s"MergeQueue ChainStore line failed ($action): ${e.getMessage}")

  /** IO 包装（BackoffSupervisor 终态路径是 IO 上下文）。 */
  def enqueueIO(entry: Entry): IO[Unit] = IO.blocking(enqueue(entry))
  def onTerminalIO(delegateId: String): IO[Unit] = IO.blocking(onTerminal(delegateId))

end MergeQueue
