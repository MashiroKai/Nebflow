package nebflow.pilogic

/**
 * L-8 消费侧 run 账本（接线期四件 ③）：run 身份 + 会话域 KernelEvent 序的**不可变值**。
 *
 * 纪律（病灶 #1 结构性免疫在接线层的延续）：跨 run 记忆以值形态显式进宿主状态
 * （AgentState.pilogicLedger 值传递），**不进 object 可变态 / @volatile / Ref.unsafe**。
 *
 * 序域语义（L-8 拍板 + db 接线 C5 宽松口径对拍）：
 *   - 生产者侧：`MessageSeq` 每 run 重置、run 内单调（pilogic 循环内语义，本件不涉）；
 *   - 消费者侧：`seqInSession` **会话域单调、跨 run 连续**、从 1 起、重启归零属宽松语义
 *     （全局序在存储层 envelope seq，重放锚在 dedupKey——FlowMapEventLog.shadowAppendEvent
 *     同款口径）。两侧映射单点在接线层桥（PiEventBridge）。
 *
 * run 生命周期：`empty` → `start`（RunStarted 行消费一序）→ … → `end`（RunEnded 行
 * 消费一序，runId 置空=run 关闭；seqInSession 保留供下一 run 连续）。
 */
final case class RunLedger(
  /** 逻辑 run 纪元（宿主 loopTurnKey 同域——真实 turn 唤醒 +1、续轮/续跑不递增）。 */
  runKey: Long,
  /** 本次 run 身份（空 = 无在飞 run——RunEnded 已发或从未开始）。 */
  runId: String,
  /** 会话域 KernelEvent 序（跨 run 连续；0 = 尚未发过任何 KernelEvent 行）。 */
  seqInSession: Long,
  /**
   * run 起点 dispatch 轮号（L-8 生产者侧映射：MessageSeq = turnId − startTurn + 1，
   * 每 run 重置、run 内单调）。
   */
  startTurn: Long
):
  def nextSeq: RunLedger = copy(seqInSession = seqInSession + 1L)
  def closed: RunLedger = copy(runId = "")

object RunLedger:
  val empty: RunLedger = RunLedger(runKey = 0L, runId = "", seqInSession = 0L, startTurn = 0L)

  /** 开新 run：runKey/runId/起点换新，seqInSession 继承（会话域连续——L-8 消费侧）。 */
  def start(runKey: Long, runId: String, carry: RunLedger, startTurn: Long): RunLedger =
    RunLedger(runKey = runKey, runId = runId, seqInSession = carry.seqInSession, startTurn = startTurn)
