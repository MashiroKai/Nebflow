/* Phase 3 去重(行为保持重构,2026-09-25)。 */
package nebflow.shared

import cats.effect.IO

import scala.concurrent.duration.*

/**
 * 重试/退避组合子(Phase 3 去重的公共层)。本步**零调用点改动**:只新增组合子
 * 与测试,各站点迁移由后续步逐个做;迁移纪律 = 先对照下表逐项核对语义,
 * 公共层表达不了的语义该站点保留原样。
 *
 * 参数面按现存站点实态定轴(迁移前必读的语义对照,行号以 2026-09-25 现状为准):
 *
 *  - llm/fallback.scala:310-375 tryWithRetry —— 初值 1s(InitialBackoffMs) ×2,
 *    cap 10s(MaxBackoffMs);加性抖动 [0,2000)ms 先加再封顶(retryDelayMs:
 *    min(backoff+jitter, MaxBackoffMs));三值分类 Fatal→立即抛 / Permanent→
 *    换 provider / Transient→重试。**不可表达**:overload(429/529)类延迟下限
 *    ≥60s 依赖失败类别(OverloadBackoffMinMs),且「换 provider」是链式回退
 *    非本组合子形状——该站点迁移时保留原样(llm/interface.scala:1128-1154 的
 *    流式重试同属此形状,另带 isTimeout 特例)。
 *  - dropbox/ChunkTransport.scala:289-323 sendWithRetry —— 初值 1s ×2
 *    (defaultBackoff = 2^n),cap 8s;无抖动;二值分类 isRetryable(错误码黑名单),
 *    判否立即返回 Left、不耗重试额度;Either 失败模型。**可表达**。
 *  - core/tools/RemoteExecutor.scala:962-969 p2pExecuteWithRetry —— 线性
 *    (n+1)s、无上限、无抖动;二值分类 isTransientError(前缀匹配);Either 模型。
 *    **不可表达**:线性延迟不是倍率形状——该站点迁移时保留原样。
 *  - neblink/DeviceMailInbox.scala:300-310 injectWithRetry —— 固定 2s × 总尝试
 *    3 次(InjectAttempts 口径 = 含首次);无分类;Either 模型。**可表达**
 *    (multiplier=1.0)。
 *  - gateway/TurnEndpoint.scala:144-159 readTurnResult —— 固定 200ms 轮询 ×25、
 *    耗尽降级返回空值不抛;值轮询模型(isSuccess 谓词)。**可表达**。
 *  - core/daemon/DaemonService.scala:499-502 backoffDelay —— 纯延迟函数
 *    base×2^(attempt-1) cap 60s,监督循环用;循环本体是监督/轮询形状非请求重试,
 *    迁移时只可复用延迟参数面,不动循环。
 *
 * 两轴约定:失败模型上,IO 异常模型走 [[AttemptFailure.Errored]],Either/值轮询
 * 模型走 [[AttemptFailure.Unsuccessful]](isSuccess 谓词判失败);预算语义上,
 * 分类判否 = 立即短路(异常直接抛 / 失败值直接返回),与 ChunkTransport
 * 「不可重试项不消耗重试额度」同口径。
 */
object Retry:

  /**
   * 单次尝试的失败面(覆盖仓内两种失败模型):
   * - [[Errored]]:动作抛异常(llm/fallback 的 IO 异常模型);
   * - [[Unsuccessful]]:动作返回了未过 isSuccess 谓词的值(ChunkTransport /
   *   RemoteExecutor / DeviceMailInbox 的 Either 模型、TurnEndpoint 的值轮询模型)。
   */
  enum AttemptFailure[+A]:
    case Errored(cause: Throwable) extends AttemptFailure[Nothing]
    case Unsuccessful(value: A) extends AttemptFailure[A]

  /**
   * 可选抖动:默认关闭。[[Additive]] 的形状对齐 llm/fallback 的
   * `min(backoff + jitter, MaxBackoffMs)`——加性抖动先加、再套 maxDelay 上限;
   * `nextDouble` 是 [0,1) 随机源,可注入做确定性测试。
   */
  enum Jitter:
    case Disabled

    case Additive(
      bound: FiniteDuration,
      nextDouble: () => Double = () => java.util.concurrent.ThreadLocalRandom.current().nextDouble()
    )

  /**
   * 取消令牌在(试图发起)第 attemptNo 次尝试前观测为真时抛出:重试链立即终止,
   * 不发起该次尝试。fiber 级取消不走此类型(直接中断,见 retryWithBackoff 第 7 条)。
   */
  final case class RetryCancelled(attemptNo: Int)
      extends RuntimeException(s"retry cancelled before attempt #$attemptNo")
      with scala.util.control.NoStackTrace

  /**
   * 退避/重试策略参数面(轴向对照见 object Retry 的 scaladoc 站点表)。
   *
   * @param maxAttempts  总尝试次数上限(**含首次**)。ChunkTransport「每腿 3 次重试」
   *                     = 4;DeviceMailInbox 的 InjectAttempts=3 是「总尝试」口径 = 3。
   * @param initialDelay 第 1 次重试前的延迟(fallback 1s / DeviceMail 2s /
   *                     TurnEndpoint 200ms / RemoteExecutor 首间隔 1s)。
   * @param multiplier   延迟倍率:第 n 次尝试失败后 = initialDelay × multiplier^(n-1)。
   *                     1.0 = 固定间隔(DeviceMail/TurnEndpoint);2.0 = 经典指数
   *                     (fallback / ChunkTransport / DaemonService.backoffDelay)。
   * @param maxDelay     单次延迟上限(ChunkTransport 8s / fallback 10s / DaemonService 60s);
   *                     默认 1 天 = 事实上的无上限安全阀,长退避站点显式给出。
   * @param jitter       可选加性抖动(fallback: [0,2000)ms 先加再封顶)。
   * @param isSuccess    成功谓词,默认一律成功。Either 站点传 `_.isRight`,
   *                     值轮询站点(TurnEndpoint)传「窗口内出现目标元素」。
   * @param isRetryable  失败分类谓词,默认一律重试。判否 → 不重试直接短路
   *                     (异常直接抛原异常 / 失败值直接返回原值,不耗重试额度——
   *                     ChunkTransport isRetryable 错误码黑名单口径)。
   * @param shouldCancel 取消令牌:每次尝试前求值,真 → 抛 [[RetryCancelled]]。
   * @param onAttempt    每次尝试完成后的回调(**先于**重试决策)——fallback 的
   *                     onAttempt 遥测/审计钩子位。
   */
  final case class Policy[A](
    maxAttempts: Int,
    initialDelay: FiniteDuration,
    multiplier: Double = 1.0,
    maxDelay: FiniteDuration = 1.day,
    jitter: Jitter = Jitter.Disabled,
    isSuccess: A => Boolean = (_: A) => true,
    isRetryable: AttemptFailure[A] => Boolean = (_: AttemptFailure[A]) => true,
    shouldCancel: IO[Boolean] = IO.pure(false),
    onAttempt: (Int, Either[Throwable, A]) => IO[Unit] = (_: Int, _: Either[Throwable, A]) => IO.unit
  ):
    require(maxAttempts >= 1, s"Retry.Policy.maxAttempts must be >= 1, got $maxAttempts")
    require(multiplier >= 0.0, s"Retry.Policy.multiplier must be >= 0, got $multiplier")
    require(initialDelay >= Duration.Zero, s"Retry.Policy.initialDelay must be >= 0, got $initialDelay")
    require(maxDelay >= Duration.Zero, s"Retry.Policy.maxDelay must be >= 0, got $maxDelay")

  end Policy

  /**
   * 重试组合子。语义(每条都有 RetryWithBackoffSpec 对应测试钉住):
   *
   * 1. 每次尝试前查取消令牌 `shouldCancel`,真 → 立即抛 [[RetryCancelled]]
   *    (第 1 次尝试前也查——预取消的令牌不发起任何尝试);
   * 2. 动作抛错 → [[AttemptFailure.Errored]];返回未过 isSuccess 的值 →
   *    [[AttemptFailure.Unsuccessful]];两种失败面都先过 `onAttempt` 钩子再分类;
   * 3. `isRetryable` 判否 → 短路:Errored 直接抛**原**异常,Unsuccessful 直接
   *    返回**原**值(不耗重试额度);
   * 4. 过 isSuccess → 直通返回,零额外尝试、零延迟;
   * 5. 耗尽(n == maxAttempts)→ 抛最后一次的**原**异常(身份保持,非包装)/
   *    返回最后一次的失败值(TurnEndpoint 耗尽降级口径);
   * 6. 退避延迟(第 n 次尝试失败后)= min(initialDelay × multiplier^(n-1) + 抖动,
   *    maxDelay),再钳 ≥0(NeblinkRelayTunnel.scala:925 记录过负延迟睡眠瞬回的
   *    前科,这里防御);
   * 7. 全程无 `IO.uncancelable`——外部 fiber 取消在退避睡眠中即时生效。
   */
  def retryWithBackoff[A](action: IO[A])(policy: Policy[A]): IO[A] =

    def terminal(failure: AttemptFailure[A]): IO[A] = failure match
      case AttemptFailure.Errored(cause) => IO.raiseError(cause)
      case AttemptFailure.Unsuccessful(v) => IO.pure(v)

    def decide(n: Int, failure: AttemptFailure[A]): IO[A] =
      if !policy.isRetryable(failure) then terminal(failure)
      else if n >= policy.maxAttempts then terminal(failure)
      else IO.sleep(backoffDelay(policy, n)).flatMap(_ => attemptNo(n + 1))

    def attemptNo(n: Int): IO[A] =
      policy.shouldCancel.flatMap { cancelled =>
        if cancelled then IO.raiseError(RetryCancelled(n))
        else
          action.attempt.flatMap { raw =>
            policy.onAttempt(n, raw).flatMap { _ =>
              raw match
                case Right(value) if policy.isSuccess(value) => IO.pure(value)
                case Right(value) => decide(n, AttemptFailure.Unsuccessful(value))
                case Left(cause) => decide(n, AttemptFailure.Errored(cause))
            }
          }
      }

    attemptNo(1)
  end retryWithBackoff

  /**
   * 第 n 次尝试失败后的退避延迟(纯函数):
   * min(initialDelay × multiplier^(n-1) + 抖动, maxDelay),钳 ≥0。
   */
  private def backoffDelay(policy: Policy[?], failedAttemptNo: Int): FiniteDuration =
    val baseNanos = policy.initialDelay.toNanos.toDouble * math.pow(policy.multiplier, failedAttemptNo - 1)
    val withJitterNanos = policy.jitter match
      case Jitter.Disabled => baseNanos
      case Jitter.Additive(bound, nextDouble) => baseNanos + bound.toNanos.toDouble * nextDouble()
    val cappedNanos = math.min(withJitterNanos, policy.maxDelay.toNanos.toDouble)
    math.max(0.0, cappedNanos).toLong.nanos

end Retry
