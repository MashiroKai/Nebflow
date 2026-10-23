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
    recovered: Option[Recovered] = None,
    /**
     * 待重试标记（steward 批 D3-③，2026-10-23）：合并轮被拒（冲突/脏树/机械
     * 验收拒绝）或提醒不可投（目标会话已死）的条目**不再堵塞后续**——单飞行窗
     * 立即放行轮转下一条，本条**留队待重试**（位置键 readyAt 不变，FIFO 序保持）。
     * 资格恢复 = 事件驱动：会话复活（respawn reattach / boot sweep 重挂）时清除。
     * pump 授窗时跳过本标记条目（`pumpLocked`）。
     */
    pendingRetry: Boolean = false
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
   * 全量条目快照（steward 批 D3-⑤ 台账看护消费面 + 审计）。进程内态只读投影，
   * 零副作用；`MergeWindowCare.care` 每拍经此取全队列做活性看护。
   */
  def entriesSnapshot: List[Entry] = lock.synchronized(queues.values.flatten.toList)

  // ── ② 活性探针（steward 批 2026-10-23：提醒注入不再绑活体引用盲投）──────
  //
  // 注入前探测 childRef 是否活（actor 注册表已退出 = 死——`ref !` 是队列 offer
  // 永不失败，盲投死 ref = 消息进无人消费的队列静默蒸发 + 章照盖 + 窗被尸体持死）。
  // 注册点 = agent.SharedResources 构造行（actorSystem 单点注入，本 object 不持
  // ActorSystem 引用）；未装配（测试 harness）= 恒活 ⇒ 既有行为零变化。
  private val livenessProbe: java.util.concurrent.atomic.AtomicReference[ActorRef[?] => IO[Boolean]] =
    new java.util.concurrent.atomic.AtomicReference(_ => IO.pure(true))

  /** 注册活性探针（生产 boot 装配；测试可注入假探针）。幂等覆盖。 */
  def installLivenessProbe(probe: ActorRef[?] => IO[Boolean]): Unit = livenessProbe.set(probe)

  /** 探针求值（fail-open：探针自身故障 = 不判死——绝不因探针丢提醒）。 */
  private def childAlive(ref: ActorRef[?]): Boolean =
    try
      import cats.effect.unsafe.implicits.global
      livenessProbe.get.apply(ref).unsafeRunSync()
    catch case _: Throwable => true

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
   *
   * `keepForRetry`（steward 批 D3-③，2026-10-23）：合并轮**被拒**（冲突/脏树/
   * 机械验收拒绝）的终态传 true——条目**不出队**，标 `pendingRetry` 留队待重试
   * （位置键 readyAt 不变），单飞行窗立即释放并轮转下一条（队头被拒不再堵塞
   * 后续）。重试资格 = 事件驱动恢复（respawn reattach / boot sweep 重挂）。
   * 默认 false = 既有出队语义逐字不变。
   */
  def onTerminal(delegateId: String, keepForRetry: Boolean = false): Unit =
    lock
      .synchronized {
        findAndRemoveLocked(delegateId) match
          case None => None
          case Some(entry) if keepForRetry && entry.reminderSentAt.isDefined =>
            val flagged = entry.copy(pendingRetry = true)
            val q = queues.getOrElse(entry.projectKey, Nil) :+ flagged
            queues = queues.updated(entry.projectKey, sortByReadyAt(q))
            inFlight = inFlight - entry.projectKey
            record(flagged, "refused-keep-queued", Json.obj("phase" -> "merge-refused-pending-retry".asJson))
            pumpLocked(entry.projectKey)
          case Some(entry) =>
            if entry.reminderSentAt.isDefined then
              inFlight = inFlight - entry.projectKey
              record(entry, "dequeued", Json.obj("phase" -> "merge-round-done".asJson))
            else
              record(entry, "dequeued", Json.obj("phase" -> "pre-injection".asJson))
              releaseGhostWindowLocked(entry.projectKey)
            pumpLocked(entry.projectKey)
      }
      .foreach(fireMergeReminder)

  // lock held
  /**
   * 尸窗自愈（steward 批 D3-③）：pre-injection 出队后窗仍被占、但队列里再无
   * 「在飞」（提醒已发且未待重试）条目 = 持窗者已消亡（终态清理竞态形态）——
   * 释放窗，绝不留幽灵窗楔死整队。有活持窗者时零动作。
   */
  private def releaseGhostWindowLocked(projectKey: String): Unit =
    if inFlight.contains(projectKey) &&
      !queues.getOrElse(projectKey, Nil).exists(e => e.reminderSentAt.isDefined && !e.pendingRetry)
    then inFlight = inFlight - projectKey

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
      // D3-③（steward 批）：待重试条目不参与授窗（跳过继续处理后续项，队头
      // 保持待重试）——复活轮 reattach / boot sweep 重挂时清除标记后重新入序。
      queues.get(projectKey).flatMap(_.filterNot(_.pendingRetry).headOption) match
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

  /**
   * 既有单点（内部腿 actor 注入——原 fireMergeReminder 本体）。steward 批 D3-②
   * （2026-10-23）：注入前活性探测——目标已死 ⇒ **不盲投**（投尸 = 消息进无人
   * 消费的队列）、不盖章、不持窗：条目标记 `pendingRetry` 留队，账本行
   * `reminder-deferred`（提醒持久化面——持久账本行 + 复活轮 reattach/pump 重投，
   * 不绑活体引用），单飞行窗立即放行轮转下一条。
   */
  private def fireViaActor(entry: Entry): Unit =
    import cats.effect.unsafe.implicits.global
    if !childAlive(entry.childRef) then deferUndeliverable(entry, "child session not live at injection time")
    else
      val brief = mergeBriefFor(entry)
      (entry.childRef ! AgentCommand.UserInput(
        text = brief,
        replyTo = Some(entry.supervisorRef),
        source = Some(Source),
        eventType = Some("merge-turn")
      )).unsafeRunSync()
      stampReminder(entry)
      record(stampOf(entry), "reminder-sent", Json.obj("mergeRound" -> true.asJson))

  /**
   * 提醒不可投（目标会话已死）：条目留队待重试（不删不投），窗释放 + 轮转下一条
   * + 账本行 `reminder-deferred`（dropFailedInject 的「留队」变体——条目价值仍在，
   * 只等会话复活）。
   */
  private def deferUndeliverable(entry: Entry, reason: String): Unit =
    logger.warn(
      s"merge-queue reminder deferred for ${entry.delegateId} — entry kept pending retry, single-flight window released: $reason"
    )
    lock
      .synchronized {
        queues.get(entry.projectKey).flatMap(_.find(_.delegateId == entry.delegateId)) match
          case None => None
          case Some(_) =>
            queues = queues.updated(
              entry.projectKey,
              queues(entry.projectKey)
                .map(e => if e.delegateId == entry.delegateId then e.copy(pendingRetry = true) else e)
            )
            inFlight = inFlight - entry.projectKey
            pumpLocked(entry.projectKey)
      }
      .foreach(fireMergeReminder)
    record(entry, "reminder-deferred", Json.obj("reason" -> reason.take(200).asJson))

  end deferUndeliverable

  /**
   * 崩溃重启换绑（steward 批 D3-②，2026-10-23）：delegate 会话崩溃经监督者
   * respawn 换新子 actor——排队条目的 childRef 原地换绑（**事件驱动**，respawn
   * 点调用），`pendingRetry` 标记随之清除（复活轮重新获得注入资格）。提醒**未发
   * 过**的条目若正巧队首且窗空闲 ⇒ 立即 pump 重投；提醒**已发过**（合并轮在飞
   * 崩溃）只换绑不重投——断点续跑轮经 recovered messages 续推合并轮。查无条目
   * = no-op（未停靠 / 已出队 / 通道腿）。
   */
  def reattach(delegateId: String, newChildRef: ActorRef[AgentCommand]): Unit =
    lock
      .synchronized {
        queues.find((_, q) => q.exists(_.delegateId == delegateId)) match
          case None => None
          case Some((key, _)) =>
            val refreshed = queues(key)
              .map(e => if e.delegateId == delegateId then e.copy(childRef = newChildRef, pendingRetry = false) else e)
            queues = queues.updated(key, refreshed)
            val me = refreshed.find(_.delegateId == delegateId).getOrElse(queues(key).head)
            record(me, "child-reattached", Json.obj("phase" -> "respawn-reattach".asJson))
            if me.reminderSentAt.isEmpty then pumpLocked(key) else None
      }
      .foreach(fireMergeReminder)

  /** IO 包装（BackoffSupervisor respawn 路径是 IO 上下文）。 */
  def reattachIO(delegateId: String, newChildRef: ActorRef[AgentCommand]): IO[Unit] =
    IO.blocking(reattach(delegateId, newChildRef))

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
If git refuses or reports conflicts: STOP — change nothing, resolve nothing; report the refusal/conflict verbatim in your final text, call delegate_report(category="finish", detail=<the refusal/conflict>), and end your turn (there is no blocked category — the refusal is your report's content, not a state).
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

  def onTerminalIO(delegateId: String, keepForRetry: Boolean = false): IO[Unit] =
    IO.blocking(onTerminal(delegateId, keepForRetry))

end MergeQueue
