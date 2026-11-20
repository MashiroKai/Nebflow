package nebflow.social.outbound

import cats.effect.IO

import scala.concurrent.duration.*

/**
 * 出站腿的有界退避重试策略（社交出站 P0 批 2026-11-21，作者令③
 * 「排队出站按有界退避重试排空，不许一次失败就放弃」）。
 *
 * 背景（两条腿的实测形态）：重启之后的失败**一次即弃**——微信 iLink 腿在
 * `attempts=1` 后直接放弃（2026-11-21 01:35、01:50 两条日志），飞书腿连放弃
 * 都不记录。作者令③要求排队出站按**有界退避**重试排空：一次失败是常态
 * （iLink `ret=-2 prepare failed` = 会话尚未就绪、5xx = 上游抖动），不是终态。
 *
 * 设计边界（为什么默认仍然是 [[Single]]）：
 *
 *   - 逐段即时批的**重发边界**未变（design §0.4「超时可能已经送达 ⇒ 不补发」）：
 *     因此可重试的判定只认**确定性未送达**的分类（见 [[RetryPolicy.Bounded]] 的
 *     调用侧判定），超时一类的中立失败**不进重试**——重发会重复投递。
 *   - 默认 [[Single]] 保持既有契约（每个既有钉子的 `attempts=1` 语义、单次调用
 *     面）；生产装配处显式传 [[Bounded]] 才启用退避重试。判别式装配是既有形状
 *     （两桥的 `send` / `rootSessionGate` 缝同款），不是新机制。
 *
 * 纯数据 + 纯 IO 组合，零时钟依赖以外的东西：`IO.sleep` 是唯一的时间来源，
 * 离线可判（[[OutboundRetrySpec]] 钉死退避序列与次数上限）。
 */
final case class RetryPolicy(maxAttempts: Int, baseDelay: FiniteDuration, maxDelay: FiniteDuration):

  require(maxAttempts >= 1, "a retry policy never attempts zero times")

  /**
   * 第 `nextAttempt` 次尝试**之前**要等多久（指数、封顶）。第 1 次立即开跑
   * （首试零等待），第 2 次 = `baseDelay`，第 3 次 = `2×baseDelay`，以此类推，
   * 上界 `maxDelay`。有界 = 次数有界（[[maxAttempts]]）**且**单次间隔有界
   * （[[maxDelay]]），总等待 ≤ `maxAttempts × maxDelay`。
   */
  def delayBefore(nextAttempt: Int): FiniteDuration =
    if nextAttempt <= 1 then Duration.Zero
    else
      val factor = math.pow(2.0, (nextAttempt - 2).toDouble)
      val raw = baseDelay.toMillis.toDouble * factor
      math.min(raw, maxDelay.toMillis.toDouble).toLong.millis

end RetryPolicy

object RetryPolicy:

  /**
   * 既有契约：恰好一次尝试。**默认值**——所有既有钉子（`attempts=1`、单次
   * 调用面、不补发边界）因此逐字保持；测试直接构造两桥时拿到的是它。
   */
  val Single: RetryPolicy = RetryPolicy(1, Duration.Zero, Duration.Zero)

  /**
   * 生产装配面（作者令③）：3 次尝试、0.5s 起、封顶 8s——总等待 ≤ 1.5s+2s。
   * 算术：一次失败是常态（iLink 会话未就绪 0.5s 内多半已就绪；5xx 抖动同理），
   * 三次仍失败即判定「这一条这一轮送不出去」，落 ERROR 交面板，不再无限拖。
   */
  val Bounded: RetryPolicy = RetryPolicy(3, 500.millis, 8.seconds)

end RetryPolicy

/**
 * 出站重试的执行面（唯一实现源，两条渠道腿共用）。
 *
 * 语义：按 [[RetryPolicy]] 尝试；**成功即停**；失败的分类决定是否继续——
 * 只有调用侧判为 `retryable` 的失败才吃掉一次名额，其余（确定性拒绝：鉴权、
 * 配置、业务 4xx）立即停手（重试同一条注定同样的拒绝，只是拖时间）。
 * 返回**最后一次**的读数：调用侧原有的失败语义（WARN / 失败注明 / 降级一行）
 * 原样保持，本对象不吞、不改写任何读数。
 */
object OutboundRetry:

  /**
   * @param policy   次数与间隔上界
   * @param attempt  真实的一次尝试（第 N 次，1 起）
   * @param ok       这一次是否成功（成功即停，不再重试）
   * @param retryable 这一次的失败是否值得再试（确定性拒绝 ⇒ false）
   */
  def run[A](policy: RetryPolicy)(attempt: Int => IO[A])(ok: A => Boolean, retryable: A => Boolean): IO[A] =
    def loop(done: Int, last: A): IO[A] =
      if ok(last) || done >= policy.maxAttempts || !retryable(last) then IO.pure(last)
      else
        IO.sleep(policy.delayBefore(done + 1)) *>
          attempt(done + 1).flatMap(next => loop(done + 1, next))

    if policy.maxAttempts <= 1 then attempt(1)
    else attempt(1).flatMap(first => loop(1, first))

  end run

end OutboundRetry
