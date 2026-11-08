package nebflow.core.delegate

import nebflow.shared.{Defaults, SubAgentTask}

/**
 * DelegateTerminalTtl —— 死 delegate 清理机制的**判读单源**（终态 7 天 TTL 批
 * 2026-11-06 · 作者令 22:39 逐字「我发现了非常久远的 delegate 任务也在里面。说明
 * 我们没有一个死 delegate 的一个清理机制。设置跟 work tree 一个 ttl，一周。」）。
 *
 * 考古结论（本批）：仓内**没有** worktree TTL 机制（merge-sink.py / PostMergeCleanup
 * / NodeEditTool.reclaimAbandonedWorktree 全是事件驱动回收，非年龄驱动）——本批
 * 立统一 TTL：`Defaults.DelegateTerminalTtlMs`（7 天，全仓唯一定义点）。
 *
 * 三态判读（spec 钉死边界）：
 *  - `InFlight`     在飞会话——**绝不清理**（红线：running/restarting/ready-to-merge
 *    且未停靠的行永不过期，100 天也不清）；
 *  - `WithinTtl`    终态/保存态但未满 7 天——保留；
 *  - `Expired`      到期——进入清理面（登记条目移除 + 任务行剪行 + worktree 联动
 *    回收；本对象只判读，动作面在 [[nebflow.agent.DelegateTtlSweep]]）。
 *
 * 终态时刻锚（按优先级，spec 钉死解析序）：
 *  1. **保存态锚**（作者 2026-11-06 20:36 新规「保存态待手动恢复同属终态计时」）：
 *     `standbySince`（待命停靠）/ `frozenSince`（错误冻结）——保存态不能无限堆积，
 *     7 天后一并清理；
 *  2. **任务行终态锚**：`completedAt`（failed/cancelled/completed 终态写入时刻）
 *     → `statusAt` → `spawnedAt` 兜底；
 *  3. **登记兜底锚**（owner-gone：任务行缺失）：`cancelRequestedAt`（取消墓碑）
 *     → `restartInterruptedAt`（重启打断旗）→ `createdAt`（登记时间，brief 兜底令）。
 *
 * 边界（机械）：`now - anchor >= TtlMs` 判到期——6天23h59m 不清、7天整清、
 * 7天+1m 清。TTL=0 永不到期（关闸形态，与 DelegateFreeze.retentionMs 同纪律）。
 */
object DelegateTerminalTtl:

  /** 终态 TTL（ms）——唯一定义点在 `Defaults.DelegateTerminalTtlMs`（7 天）。 */
  val TtlMs: Long = Defaults.DelegateTerminalTtlMs

  /** 任务行终态值域（与 MailboxBootSweep.terminalDelegateRows 同族）。 */
  val TerminalRowStatuses: Set[String] = Set("completed", "failed", "cancelled")

  sealed trait Verdict:
    /** 命中的终态锚（时刻 + 锚名；InFlight = None）——审计/spec greppable 面。 */
    def anchor: Option[(Long, String)]
    def isExpired: Boolean

  case object InFlight extends Verdict:
    val anchor: Option[(Long, String)] = None
    val isExpired: Boolean = false

  final case class WithinTtl(anchorMs: Long, anchorName: String) extends Verdict:
    val anchor: Option[(Long, String)] = Some((anchorMs, anchorName))
    val isExpired: Boolean = false

  final case class Expired(anchorMs: Long, anchorName: String) extends Verdict:
    val anchor: Option[(Long, String)] = Some((anchorMs, anchorName))
    val isExpired: Boolean = true

  /** 边界判读（唯一比较点）：age ≥ TTL ⇒ Expired；TTL=0 = 关闸永不到期。 */
  def ageVerdict(anchorMs: Long, anchorName: String, nowMs: Long): Verdict =
    val age = nowMs - anchorMs
    if Defaults.DelegateTerminalTtlMs > 0 && age >= Defaults.DelegateTerminalTtlMs then Expired(anchorMs, anchorName)
    else WithinTtl(anchorMs, anchorName)

  /**
   * 任务行级判读（**通知面闸**——SubAgentStartupRecovery 复活/收殓视野、finalize
   * 收尾腿、MailboxBootSweep 账面合成腿共用）：非终态行 = InFlight（在飞红线，
   * 永不过期）；终态行以 completedAt→statusAt→spawnedAt 为锚。
   */
  def rowVerdict(t: SubAgentTask, nowMs: Long): Verdict =
    if !TerminalRowStatuses.contains(t.status) then InFlight
    else ageVerdict(t.completedAt.orElse(t.statusAt).getOrElse(t.spawnedAt), "task-row", nowMs)

  def rowExpired(t: SubAgentTask, nowMs: Long): Boolean = rowVerdict(t, nowMs).isExpired

  /**
   * 记录级判读（**清理面闸**——DelegateTtlSweep 消费）：保存态锚优先 → 任务行 →
   * 登记兜底。调用方（sweep）负责在飞守卫（活体 actor / 开放合并队列成员资格）
   * ——本函数只做盘上判读。
   */
  def verdict(rec: DelegateRegistry.Record, taskOpt: Option[SubAgentTask], nowMs: Long): Verdict =
    if WorkspaceLedger.default.owned(rec.id).nonEmpty then return InFlight
    // Legacy worktree ownership is kept until explicitly reconciled into the ledger.
    if rec.worktree.exists(_.nonEmpty) then return InFlight
    val savedAnchor: Option[(Long, String)] =
      (rec.standbySince, rec.frozenSince) match
        case (Some(a), Some(b)) => Some((math.max(a, b), "saved-state"))
        case (Some(a), None) => Some((a, "standby"))
        case (None, Some(b)) => Some((b, "frozen"))
        case (None, None) => None
    savedAnchor match
      case Some((ms, name)) => ageVerdict(ms, name, nowMs)
      case None =>
        taskOpt match
          case Some(t) => rowVerdict(t, nowMs)
          case None =>
            rec.cancelRequestedAt.orElse(rec.restartInterruptedAt) match
              case Some(ms) => ageVerdict(ms, "registry-cancel-marker", nowMs)
              case None => ageVerdict(rec.createdAt, "registry-created", nowMs)
  end verdict
end DelegateTerminalTtl
