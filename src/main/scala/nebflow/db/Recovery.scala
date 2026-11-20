package nebflow.db

import nebflow.db.format.{GuardDocs, PendingRecord}

/**
 * 恢复面（设计 §3.11，D-8/D-10 采纳）——纯函数规划腿（执行腿在 [[nebflow.db.DbHandle]]）。
 *
 * boot 重水合时序（表步 1–6 的库侧职责；步 7 回执闸 = 现状已有面，接线期对象）：
 *  1. `INSTANCE.lock` 认领（世代检查）——[[InstanceLock.claim]]
 *  2. 读 `seq/counter.json`：坏/缺+有数据 ⇒ 拒启——[[SeqCounter.open]]
 *  3. `pending/` 重放：未闭合 **permission 重装**、**ask 过期关闭**（D-8 工作默认）——[[planPendingRecovery]]
 *  4. `events.idx` 重建（坏 ⇒ 删了重建，派生面③）——DbHandle.checkEventIndexAtOpen
 *  5. `guard/` 冷却/软避窗恢复（不归零；坏 ⇒ 保守向）——[[conservativeNotifyGuard]]
 *  6. 会话消息流 lazy 水合（boot 只载索引）——DbHandle.sessionIndex
 *
 * 不变式（V6）：孤儿 ask == 0——恢复腿跑完后每条 `ask.open` 都有同 requestId 终态行。
 */
object Recovery:

  /** 折叠视图：同 requestId 取最后一行（闭合行是追加的决议事件——原行永不动）。 */
  def foldPending(rows: List[PendingRecord]): List[PendingRecord] =
    rows
      .foldLeft(Map.empty[String, PendingRecord]) { (acc, r) =>
        acc.updated(r.requestId, r)
      }
      .values
      .toList
      .sortBy(_.seq)

  /** D-8 一次恢复的动作单。 */
  final case class PendingPlan(
    /** 重装清单：未闭合 Permission/McpPermission 卡（回 hub，等待方可继续答）。 */
    reinstall: List[PendingRecord],
    /** 过期关闭清单：未闭合 AskUser（本腿已为其追加 `expired-by-restart` 终态行）。 */
    expiredAsks: List[PendingRecord]
  ):

    def summary: String =
      s"reinstall=${reinstall.size} ask-expired-closed=${expiredAsks.size}"

  /**
   * D-8 工作默认规划：permission（Permission/McpPermission）⇒ **重装**（不丢待审单——
   * 对照 cap「权限单纯内存」最危险丢失类）；ask（AskUser）⇒ **过期关闭**（保留现状
   * bootReconcile 语义，收编 `PendingAskLedger.bootReconcile:117-164` 既有形——从
   * 「全量过期」改为「重装 permission + 补终态 ask」）。
   */
  def planPendingRecovery(latest: List[PendingRecord]): PendingPlan =
    val open = latest.filter(_.state == PendingRecord.StateOpen)
    PendingPlan(
      reinstall = open.filter(r => r.kind == PendingRecord.KindPermission || r.kind == PendingRecord.KindMcpPermission),
      expiredAsks = open.filter(_.kind == PendingRecord.KindAskUser)
    )

  /** 闭单行的固定口径（D-8）：过期关闭 = system / expired-by-restart。 */
  val ExpiredCloseBy: String = "system"
  val ExpiredCloseReason: String = "expired-by-restart"

  /**
   * D-10 保守向：guard 档坏 ⇒ 视为「全部仍在冷却」（宁可少发不可风暴）——
   * cooldownUntil = Long.MaxValue（窗口态全保真至重新写档）。
   */
  def conservativeNotifyGuard(source: String, now: Long): GuardDocs.NotifyGuardDoc =
    GuardDocs.NotifyGuardDoc(
      version = 1,
      source = source,
      failedAt = Nil,
      cooldownUntil = Long.MaxValue,
      updatedAt = now
    )

end Recovery
