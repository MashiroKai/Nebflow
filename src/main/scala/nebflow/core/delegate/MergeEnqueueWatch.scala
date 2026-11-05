package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.{AgentRecord, AgentStatus}
import nebflow.core.{AgentRuntimePort, GitWorktrees}
import nebflow.shared.{Defaults, NebflowLogger}

import scala.util.Try

/**
 * MergeEnqueueWatch —— 合并队列**活性守卫**（merge-fifo 批 2026-11-03 · 作者报障
 * 「两个会话都已经报了 merge，为什么还没收到通知？merge 的排队机制到底是怎么设计的」）。
 *
 * == 病灶（只读侦察已确认的代码坐标，非假设）==
 *
 * 入队原本只发生在**回合终态腿**（[[DelegateTerminal.holdForMerge]] /
 * [[nebflow.core.executor.ExternalSessionRunner]] settle → `MergeQueue.enqueueIO`）
 * 与本批新加的申报点预记账（[[MergeEnqueue]]）。两腿**双失**的形态（重启打断让终态
 * 腿没跑成 / 判定降级 / 会话在申报后提前收尾）⇒ 该 delegate 的分支在盘上有交付提交、
 * 任务行还是 `running`、却永远不在队列里——零台账行、零通知（队列从不排它，也从不
 * 宣布）。这是「两个会话都报了 merge 却收不到通知」的**候选根因之一**（另两个候选：
 * standby 闸读 ahead=None 静默停车 / record 缺 baseBranch 使 holdForMerge 拒绝入队
 * ——本件与 [[MergeEnqueue]] 一并覆盖三者的**后果面**，报告不把候选写成已证实）。
 *
 * == 本件是什么 ==
 *
 * 扫描拍尾（[[nebflow.core.processor.TaskStuckWatcher.scan]] 尾部接 `scan(resources)`，
 * 零新增定时器纪律）的一拍兜底：把「**看起来就该在队列里、却不在队列里**的成员」补
 * 进去，并沿 [[MergeQueue.BlockNotifyPort]] 发一条 kind=`watch-queued` 的通知（作者
 * 面看得见「差点漏掉的是这一条」）。
 *
 * 判据（[[shouldProxy]]，纯函数——spec 判红锚，零 IO 零时钟）七维：
 *   - `Idle`：会话此刻没在跑回合（在跑 = 终态腿还没到，轮不到守卫插手）；
 *   - `!frozen`：冻结是**故意的停**（错误冻结批：唤醒后接着做）——不得代它入队；
 *   - `!cancelledOrArchived`：取消墓碑 / 归档 = 已出账，绝不复活；
 *   - `ahead > 0`：分支上确有未并交付（零领先 = 无可合并产物，入队只会空转）；
 *   - `!hasOpenEntry`：账本已有开放条目 = 已在队列（幂等面本体）；
 *   - `idleMs ≥ thresholdMs`：**静默够久**（缺省 [[Defaults.MergeEnqueueWatchIdleSec]]，
 *     可经系统属性 `nebflow.merge.enqueueWatch.idleSec` 覆盖）——刚落一次 merge 申报
 *     的会话在终态腿到来前也满足其它各维，阈值把这截窗口让给它自己；
 *   - `taskRow ∈ {running, restarting}`：任务行还活着（completed / failed / cancelled
 *     已出账；ready-to-merge 说明停靠腿跑过了——都不是「漏排」形态）。
 *
 * 代办入队的锚：`readyAt = lastActivityMs`（会话最后一次**agent 侧**活动的时刻——
 * 「它什么时候干完的」最诚实读面；用扫描时刻会把漏网成员一律排到队尾），通道 =
 * 活体注册表行的 `InternalLeg(rec.ref, supervisorRef)`（两件都在则挂，属主还能被
 * 通知到；无活体 = 无闭包，交给 [[MergeQueue]] 既有的 R5 通道前提判定）。
 *
 * 幂等：`hasOpenEntry` 是唯一闸——重复扫描零重复入队、零重复通知。
 */
object MergeEnqueueWatch:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-enqueue-watch")

  /** 通知 kind（[[MergeQueue.BlockNotifyPort]] 文案分支锚）。 */
  val NotifyKind: String = "watch-queued"

  /** 判据阈值（毫秒；缺省 [[Defaults.MergeEnqueueWatchIdleSec]] 秒，系统属性可覆盖）。 */
  def idleThresholdMs: Long =
    val secs =
      sys.props
        .get("nebflow.merge.enqueueWatch.idleSec")
        .flatMap(s => Try(s.trim.toLong).toOption)
        .filter(_ >= 0L)
        .getOrElse(Defaults.MergeEnqueueWatchIdleSec)
    Math.max(0L, secs) * 1000L

  /**
   * 一个待判候选（[[scan]] 由三段读数——registry / 活体注册表 / 任务行——装配；
   * [[shouldProxy]] 只在它上面做布尔运算，零 IO）。
   */
  final case class Candidate(
    delegateId: String,
    /** 活体注册表状态（None = 会话不在册——不在跑回合，但也不算 Idle 活体）。 */
    status: Option[AgentStatus],
    /** 冻结（registry 冻结旗 / 活体 Frozen 态任一在案）。 */
    frozen: Boolean,
    /** 取消墓碑或归档旗在案。 */
    cancelledOrArchived: Boolean,
    /** 分支相对基分支领先的提交数（None = 不可读）。 */
    aheadOfBase: Option[Int],
    /** 账本已有本 delegate 的**开放**条目。 */
    hasOpenEntry: Boolean,
    /** 距最后一次 agent 侧活动的毫秒数。 */
    idleMs: Long,
    /** 会话最后一次 agent 侧活动时刻（代办入队的 readyAt 锚）。 */
    lastActivityMs: Long,
    /** 任务行状态（None = 无任务行）。 */
    taskRow: Option[String],
    /**
     * **属主完成宣告**在案（[[MergeDeclaration]]；宣告闸批 2026-11-04 第八维）。
     * 缺省 false = 保守方向：没有宣告的成员，本守卫**绝不代办入队**，只发可见告警。
     */
    declared: Boolean = false
  )

  /**
   * 七维「看起来就该在队列里」判据（**不含宣告维**）。判据语义逐字沿用：idle +
   * 没冻结 + 没取消/归档 + 分支有领先提交 + 账本没开放条目 + 静默够久 + 任务行
   * running/restarting。
   */
  def looksOrphaned(c: Candidate, thresholdMs: Long): Boolean =
    c.status.contains(AgentStatus.Idle) &&
      !c.frozen &&
      !c.cancelledOrArchived &&
      c.aheadOfBase.exists(_ > 0) &&
      !c.hasOpenEntry &&
      c.idleMs >= thresholdMs &&
      c.taskRow.exists(s => s == "running" || s == "restarting")

  /**
   * 纯判据（spec 判红锚）：`looksOrphaned ∧ 属主已宣告`才代办。
   *
   * **宣告维是硬闸**（缺陷批 2026-11-04 · 作者令 1「不许把『有提交』当『已完成』」）：
   * 本守卫原来的七维里，`aheadOfBase > 0` + `taskRow == running/restarting` 恰恰是
   * 「批还在跑、只有部分提交」的形态——把它代办入队，等于**用「有提交」代替
   * 「owner 宣告完成」**，半成品分支从此排队、拿窗、被合进主干。现在：没有宣告一律
   * 不入队（[[shouldAlertUndeclared]] 走告警腿）。
   */
  def shouldProxy(c: Candidate, thresholdMs: Long): Boolean =
    looksOrphaned(c, thresholdMs) && c.declared

  /** 未宣告但有提交的漏网形态：**不代办入队**（作者令 1），改发可见告警（作者令 2）。 */
  def shouldAlertUndeclared(c: Candidate, thresholdMs: Long): Boolean =
    looksOrphaned(c, thresholdMs) && !c.declared

  /** 端口（生产 = [[fromResources]]；测试 = 假端口驱动纯矩阵，不碰真注册表/账本）。 */
  trait Port:
    /** 全量 delegate 记录（过滤归本件判据；归档/墓碑态也要读得到）。 */
    def delegates: IO[List[DelegateRegistry.Record]]
    /** 活体注册表读数（status / lastActivityMs / frozenReason）。 */
    def liveRecords: IO[Map[String, AgentRecord]]
    /** 任务行状态（taskId → status）。 */
    def taskRowStatuses: IO[Map[String, String]]
    /** 分支领先提交数（fail-soft None）。 */
    def commitsAhead(rec: DelegateRegistry.Record): Option[Int]
    /** 账本是否已有该 delegate 的开放条目。 */
    def hasOpenEntry(delegateId: String): Boolean
    /** 代办入队（生产 = [[queueNow]]；测试 = 记录实参）。 */
    def queue(rec: DelegateRegistry.Record, c: Candidate, live: Option[AgentRecord]): IO[Unit]

  /**
   * 代办入队本体（生产/测试同一条腿——spec D3 断言 readyAt 锚与通知文案）：
   * 成员构造（[[MergeEnqueue.membershipOf]]，两锚缺一 = 不入队）→ 挂内部腿通道
   * （活体行给出 child + supervisor 才挂）→ 预记账入队（只记成员、不 pump，放行
   * 由下一拍 careTick 兑现）→ `watch-queued` 通知。**绝不抛**（调用方已 fail-soft，
   * 本件自己也收口）。
   */
  def queueNow(rec: DelegateRegistry.Record, c: Candidate, live: Option[AgentRecord]): IO[Unit] =
    MergeEnqueue.membershipOf(rec, c.lastActivityMs, MergeDeclaration.find(rec.id)) match
      case None =>
        IO(
          logger.warn(
            s"[merge-enqueue-watch] ${rec.id} looks orphaned in the queue but has no branch/baseBranch anchor " +
              "(the engine never guesses 'main') — left alone"
          )
        )
      case Some(base) =>
        val leg = live.flatMap(r => r.supervisorRef.map(sup => MergeQueue.InternalLeg(r.ref, sup)))
        val member = base.copy(internal = leg)
        val reason =
          s"it reported ready-to-merge but no report/terminal leg ever enqueued it — idle for ${c.idleMs / 1000}s, " +
            s"${c.aheadOfBase.getOrElse(0)} commit(s) ahead of the base branch, task row '${c.taskRow.getOrElse("-")}'; " +
            "the idle-safety watch queued it so it can receive its FIFO merge turn"
        MergeQueue.enqueuePendingIO(member).attempt.flatMap {
          case Left(e) =>
            IO(
              logger.warn(
                s"[merge-enqueue-watch] pre-booked enqueue failed for ${rec.id}: ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
              )
            )
          case Right(_) =>
            IO(
              logger.warn(
                s"[merge-enqueue-watch] queued the missing member '${rec.id}' (branch '${base.branch}' → '${base.baseBranch}', " +
                  s"readyAt=${c.lastActivityMs}, channel=${if leg.isDefined then "internal" else "none"})"
              )
            ) *> IO.blocking(MergeQueue.BlockNotifyPort.deliver(member, NotifyKind, reason))
        }
  end queueNow

  /** 生产端口（AgentRuntimePort 装配；git 读数走 GitWorktrees，账本读 [[MergeQueue]]）。 */
  def fromResources(resources: AgentRuntimePort): Port = new Port:
    def delegates = IO.blocking(DelegateRegistry.list()).handleErrorWith(_ => IO.pure(Nil))
    def liveRecords = resources.agentRegistry.get
    def taskRowStatuses =
      // 窄口（`SubAgentTaskStorePort`）没有全量读面：`findRunningTasks` 恰好只给
      // running/restarting 两态——正是本判据要求的值域。缺席的行（completed /
      // failed / cancelled / ready-to-merge，或压根无行）= 已出账形态 ⇒ 判据假。
      resources.subAgentTaskStore.findRunningTasks
        .map(rows => rows.map(t => t.taskId -> t.status).toMap)
        .handleErrorWith(e =>
          logger.warn(s"[merge-enqueue-watch] task row read failed: ${e.getMessage}").as(Map.empty[String, String])
        )
    def commitsAhead(rec: DelegateRegistry.Record): Option[Int] =
      for
        b <- rec.branch
        bb <- rec.baseBranch
        ws <- Try(os.Path(rec.cwd)).toOption if GitWorktrees.isGitRepo(ws)
        ahead <- Try(GitWorktrees.commitsAhead(ws, bb, b)).toOption
      yield ahead
    def hasOpenEntry(delegateId: String): Boolean =
      Try(MergeQueue.find(delegateId).exists(_.open)).getOrElse(false)
    def queue(rec: DelegateRegistry.Record, c: Candidate, live: Option[AgentRecord]): IO[Unit] =
      queueNow(rec, c, live)

  /**
   * 单拍（阈值与时钟可注入——spec D2 阈值边界 / D3 readyAt 锚都用它）。返回本拍
   * 代办了的候选（空 = 无漏网成员）。
   */
  def scan(port: Port, nowMs: Long, thresholdMs: Long): IO[List[Candidate]] =
    for
      recs <- port.delegates
      live <- port.liveRecords
      rows <- port.taskRowStatuses
      pairs = recs.map { rec =>
        val lr = live.get(rec.id)
        val lastActivity = lr.map(_.lastActivityMs).filter(_ > 0L).getOrElse(rec.createdAt)
        (
          rec,
          lr,
          Candidate(
            delegateId = rec.id,
            status = lr.map(_.status),
            frozen = rec.frozenSince.isDefined || lr.exists(_.status == AgentStatus.Frozen),
            cancelledOrArchived = rec.cancelRequestedAt.isDefined || rec.archivedAt.isDefined,
            aheadOfBase = port.commitsAhead(rec),
            hasOpenEntry = port.hasOpenEntry(rec.id),
            idleMs = Math.max(0L, nowMs - lastActivity),
            lastActivityMs = lastActivity,
            taskRow = rec.taskId.flatMap(rows.get),
            declared = Try(MergeDeclaration.hasOwnerDeclaration(rec.id)).getOrElse(false)
          )
        )
      }
      proxied = pairs.filter((_, _, c) => shouldProxy(c, thresholdMs))
      undeclared = pairs.filter((_, _, c) => shouldAlertUndeclared(c, thresholdMs))
      _ <-
        if proxied.isEmpty then IO.unit
        else
          IO(
            logger.warn(
              s"[merge-enqueue-watch] ${proxied.size} ready-to-merge delegate(s) were MISSING from the merge queue — queueing now: " +
                proxied
                  .map((_, _, c) => s"${c.delegateId}(idle=${c.idleMs / 1000}s,ahead=${c.aheadOfBase.getOrElse(0)})")
                  .mkString(", ")
            )
          )
      _ <-
        if undeclared.isEmpty then IO.unit
        else
          IO(
            logger.warn(
              s"[merge-enqueue-watch] ${undeclared.size} delegate(s) have commits ahead of the base branch but NO owner " +
                "declaration of 'done and mergeable' — NOT queued (the queue never treats 'it has commits' as 'the batch is finished'): " +
                undeclared.map((_, _, c) => c.delegateId).mkString(", ")
            )
          )
      _ <- undeclared.traverse_ { (rec, lr, c) =>
        alertUndeclared(rec, c, lr).handleErrorWith(e =>
          IO(
            logger.warn(
              s"[merge-enqueue-watch] undeclared alert failed for ${c.delegateId}: ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
            )
          )
        )
      }
      _ <- proxied.traverse_ { (rec, lr, c) =>
        port
          .queue(rec, c, lr)
          .handleErrorWith(e =>
            IO(
              logger.warn(
                s"[merge-enqueue-watch] queueing failed for ${c.delegateId}: ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
              )
            )
          )
      }
    yield proxied.map((_, _, c) => c)

  /** 告警 kind（[[MergeQueue.BlockNotifyPort]] 文案分支锚）：有提交但无属主宣告。 */
  val UndeclaredNotifyKind: String = "undeclared-not-queued"

  /**
   * 「有提交但没人宣告完成」的告警腿（宣告闸批 2026-11-04 · 作者令 1/2）：**不入队**，
   * 只把可行动事实摆到父/根会话面前——分支有 N 个未并提交、任务行还在跑、属主从未
   * 宣告 ready-to-merge；处置 = 让属主显式申报（`delegate_report(category="merge")`）
   * 或人工合并。幂等由「不入队」天然保证（没有账本行，告警每拍一条；同因去重由
   * BlockNotifyPort 的调用方面承担——此处一成员一拍一条，成本可忽略）。
   */
  private def alertUndeclared(rec: DelegateRegistry.Record, c: Candidate, live: Option[AgentRecord]): IO[Unit] =
    MergeEnqueue.membershipOf(rec, c.lastActivityMs) match
      case None => IO.unit // 缺锚：连合并目标都不知道，告警无意义（既有缺锚纪律）
      case Some(base) =>
        val member = base.copy(internal = live.flatMap(r => r.supervisorRef.map(sup => MergeQueue.InternalLeg(r.ref, sup))))
        val reason =
          s"'${rec.id}' has ${c.aheadOfBase.getOrElse(0)} commit(s) ahead of the base branch and its task row is " +
            s"'${c.taskRow.getOrElse("-")}', but the OWNER NEVER DECLARED it ready-to-merge — the merge queue does NOT " +
            "queue it (a branch that merely has commits is not a finished batch). Action: have the owner declare " +
            "delegate_report(category=\"merge\"), or merge the branch by hand."
        IO.blocking(MergeQueue.BlockNotifyPort.deliver(member, UndeclaredNotifyKind, reason))

  /** 生产入口（挂 [[nebflow.core.processor.TaskStuckWatcher.scan]] 拍尾；fail-soft）。 */
  def scan(resources: AgentRuntimePort): IO[List[Candidate]] =
    scan(fromResources(resources), System.currentTimeMillis(), idleThresholdMs)
      .handleErrorWith(e =>
        IO(
          logger.warn(s"[merge-enqueue-watch] sweep failed: ${Option(e.getMessage).getOrElse(e.toString).take(200)}")
        ).as(Nil)
      )

end MergeEnqueueWatch
