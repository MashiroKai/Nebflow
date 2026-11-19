package nebflow.agent

/**
 * Dispatch 发起方分类（freeze-schedule spec D1）：决定 pipeLlmCall gate 的行为。
 * Gated（默认）= 系统续跑（ToolsComplete 续轮 / finishTurnCont / ExternalEvent /
 * CompactionComplete 恢复 / Retry 等）——受冻结时间表约束（#337 黑名单语义）；UserWake = 用户显式
 * 发起（idle UserInput[clientMessageId.isDefined] / AskQuestion / SkillActivate）
 * ——冻结时段也放行（用户输入即唤醒，需求硬指标）。
 */
sealed trait DispatchCause

object DispatchCause:
  /** 系统续跑：受冻结调度约束（冻结窗口内进入 frozen behavior）。 */
  case object Gated extends DispatchCause

  /** 用户显式发起：gate 直接放行（唤醒语义）。 */
  case object UserWake extends DispatchCause
