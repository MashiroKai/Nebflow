package nebflow.core.tools

import nebflow.shared.Defaults

/**
 * SleepPollGuard —— sleep-poll 轮询护栏（sleep-poll 禁令批 B 段，作者令
 * 2026-10-20「明令禁止使用 sleep 或轮询的方式等待后台任务完成」）。
 *
 * 实撞案例（根因锚）：delegate-nebflow-747cd36a 在 `sleep 570; echo wN`
 * 心跳循环里轮询 5h16m（27 拍）。既有 [[nebflow.core.processor.LoopGuard]]
 * 抓不到该形态：R-call 判「同工具+同参数」指纹，`echo wN` 每拍变参 ⇒ 指纹
 * 恒新；S1 判同败，心跳全是成功调用。本护栏换判据——**不看参数指纹，看
 * 命令形态**：前台命令含 sleep > 阈值（[[Defaults.SleepPollThresholdSec]]，
 * 默认 60s）即计一次，会话级阶梯处置：
 *
 *   - 第 1 次：放行执行，回执首部附警告 + 指导文案（「用 run_in_background
 *     并结束回合，完成通知会自动唤醒」）；
 *   - 第 N 次（[[Defaults.SleepPollEscalateAfter]]，默认 3，含本次）：**拒绝
 *     执行**（可描述错误：错在哪/期望什么/修法），调用方升级面板可见帧。
 *
 * 豁免面：显式 `run_in_background` 的命令（后台是正道姿势，本护栏只管前台
 * 等待）、`background_job_id` 查询/取消腿、`persistent` 服务型申报。
 * 计数冷却：同会话 30 分钟无新犯即归零（合法偶发长 sleep 不积累成罪）。
 *
 * 纯函数核心（本对象）：检测 / 阶梯裁决与文案全部可单测；BashTool 持有
 * 会话级计数状态并消费裁决。
 */
object SleepPollGuard:

  /**
   * 命令内 sleep 秒数提取（全部匹配取最大——多段命令任一长 sleep 即命中）。
   * 数字后边界含 `;&|)` 等 shell 分隔符（`sleep 570; echo wN` 形态——747cd36a
   * 现场原样——数字直跟分号）。
   */
  val SleepCommandRe = """(?i)(?:^|[;&|(\s])sleep\s+([0-9]+(?:\.[0-9]+)?)(?=[\s;&|)]|$)""".r

  /**
   * 检测：命令内的最大前台 sleep 秒数。None = 无 sleep（或仅小数秒碎睡）。
   * 747cd36a 复现形态 `sleep 570; echo w1` ⇒ Some(570)。
   */
  def maxSleepSeconds(command: String): Option[Long] =
    val hits = SleepCommandRe.findAllMatchIn(command).map(_.group(1).toDouble.toLong).toList
    if hits.isEmpty then None else Some(hits.max)

  /** 会话级护栏状态：累计犯次 + 最近一犯时刻（冷却判据）。 */
  final case class State(count: Int = 0, lastOffenseMs: Long = 0L)

  /** 冷却窗：距上次犯超过此值 ⇒ 计数归零重计（合法偶发不积累）。 */
  val CooldownMs: Long = 30 * 60 * 1000L

  /**
   * 阈值配置（生产面 = Defaults 现读 prop；显式入参 = spec 注入缝——本工程
   * 测试 JVM 下 sys.props 写入不可靠，LoopGuard.Config / NodeStarter 构造器缝
   * 同款纪律）。
   */
  final case class Config(
    enabled: Boolean = Defaults.SleepPollGuardEnabled,
    thresholdSec: Long = Defaults.SleepPollThresholdSec.toLong,
    escalateAfter: Int = Defaults.SleepPollEscalateAfter
  )
  object Config:
    val Default: Config = Config()

  sealed trait Verdict extends Product with Serializable
  object Verdict:

    /** 无 sleep / 阈值下 / 开关关 ⇒ 零开销透传。 */
    case object Pass extends Verdict

    /** 首犯：放行执行，回执附警告（[[warningText]]）。 */
    final case class WarnExecute(state: State, sleepSec: Long) extends Verdict

    /** 升级：拒绝执行（[[rejectionText]]），调用方升级面板可见帧。 */
    final case class Reject(state: State, sleepSec: Long) extends Verdict
  end Verdict

  /**
   * 阶梯裁决（纯函数）。`nowMs` 注入便于冷却判据的确定性测试；`cfg` 注入便于
   * 阈值/升级档的确定性测试（prop 面生产消费）。
   * 计数语义：WarnExecute 与 Reject 都计犯（Reject 不放行但也是一次心跳尝试
   * ——连续 Reject 期间不再重复升级帧，状态稳定在 ≥escalateAfter 即可）。
   */
  def decide(command: String, state: State, nowMs: Long, cfg: Config = Config.Default): Verdict =
    if !cfg.enabled then Verdict.Pass
    else
      maxSleepSeconds(command) match
        case None => Verdict.Pass
        case Some(secs) if secs <= cfg.thresholdSec => Verdict.Pass
        case Some(secs) =>
          val cooled = state.count > 0 && nowMs - state.lastOffenseMs > CooldownMs
          val base = if cooled then State(0, 0L) else state
          val next = State(base.count + 1, nowMs)
          if next.count >= cfg.escalateAfter then Verdict.Reject(next, secs)
          else Verdict.WarnExecute(next, secs)

  /** 首犯回执警告（附在正常结果首部；说出错在哪 + 期望 + 修法）。 */
  def warningText(sleepSec: Long): String =
    val threshold = Defaults.SleepPollThresholdSec
    s"""⚠ [sleep-poll guard] This command contains a foreground `sleep ${sleepSec}s` (threshold: ${threshold}s). Sleeping/polling to wait is FORBIDDEN in Nebflow: background tasks notify on completion. Correct pattern: run the long work with Bash `run_in_background: true`, then END YOUR TURN — the completion notification wakes this session with the output automatically. Executed this time; further sleep-poll commands above the threshold will be rejected.""".stripMargin

  /** 升级拒绝文案（🔴 可描述错误：错在哪 / 期望什么 / 修法 / 实际值）。 */
  def rejectionText(count: Int, sleepSec: Long): String =
    val threshold = Defaults.SleepPollThresholdSec
    s"""[sleep-poll guard] REJECTED: this session has now issued $count foreground sleep-poll commands above the ${threshold}s threshold (this one sleeps ${sleepSec}s). Waiting by sleeping/polling burns the turn and is forbidden — see the author directive against sleep/poll waits. Fix: run the actual long-running work with Bash `run_in_background: true` and END YOUR TURN immediately; the completion notification wakes this session with the output. If you are waiting for an author-gated event (engine restart, approval, merge reminder), deliver your report and end your turn now — you will be woken.""".stripMargin
end SleepPollGuard
