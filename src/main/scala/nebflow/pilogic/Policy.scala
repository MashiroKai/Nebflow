package nebflow.pilogic

import scala.concurrent.duration.FiniteDuration

/** 策略位（设计 §Ⅱ.2，L-10 三元全 Option）：上限的唯一来源是构造参数，缺省无帽；
  * 引擎触及上限的唯一方式 = 每轮循环头对本对象做谓词查询。
  * 模块内零硬编码数字上限（验收 A-3 grep 门：pilogic 源码无数字字面量上限）。
  */
final case class LoopPolicy(
  maxTurns: Option[TurnNumber] = None,
  tokenBudget: Option[TokenBudget] = None,
  wallClock: Option[FiniteDuration] = None,
):

  /** nextTurn = 即将开跑的轮号（从 1 起）。maxTurns=Some(3) ⇒ 第 4 轮开跑前命中 ⇒ 恰停 3 轮（A-2）。 */
  def exceedsTurns(nextTurn: TurnNumber): Boolean =
    maxTurns.exists(_.value < nextTurn.value)

  def exceedsTokens(spent: Long): Boolean =
    tokenBudget.exists(_.limit < spent)

  /** 墙钟谓词（elapsed 由循环用 Clock.monotonic 差值计算——纯值进、纯值出，时钟不落策略件）。 */
  def exceedsWallClock(elapsed: FiniteDuration): Boolean =
    wallClock.exists(_ <= elapsed)

end LoopPolicy

object LoopPolicy:
  /** 缺省无帽策略。 */
  val none: LoopPolicy = LoopPolicy()

/** 失败重试预算——与轮数预算是两个类型分离的独立位（病灶 #5 的结构性拆分：
  * 轮数预算数每一轮；重试预算只数失败重投。pilogic 只承载策略位，不内建数字）。 */
final case class RetryPolicy(maxAttempts: Option[Int] = None):

  /** 已失败的 attempt 数是否仍在预算内（接线点 3：主链 per-turn LLM 重试闸的
    * 策略位谓词——`attemptsDone < maxAttempts`。None = 无帽恒允许）。 */
  def allows(attemptsDone: Int): Boolean =
    maxAttempts.forall(attemptsDone < _)

/** 成本帽（单位由装配层的 CostMeter 实现定义；此处只承载总量限）。 */
final case class TokenBudget(limit: Long) extends AnyVal

/** 成本快照——BudgetReported 事件载荷。 */
final case class CostSnapshot(tokensSpent: Long, turnsCompleted: Long)

/** 成本计量查询点（策略件接口；实现归接线/装配层，pilogic 不内建计量）。 */
trait CostMeter[F[_]]:
  def spent: F[CostSnapshot]
