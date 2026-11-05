package nebflow.actor

import cats.effect.{IO, Ref}
import nebflow.shared.NebflowLogger

/**
 * 死信台账（刀三·爆炸保护）。
 *
 * 来源 = 审计件
 * `actorization-gap-and-pekko-migration-audit__20261104_211833__delegate-nebflow-18eb158f.md`
 * §3 刀三 + 作者裁定「**只做爆炸保护，不做流量控制**」：
 *
 *   - 内核消息箱 / 信号箱上界极高，仅在**极端积压**时拒绝入队；
 *   - 拒绝必须**可见**：ERROR 日志（带 path 与容量原因）+ 计数递增（本台账），
 *     绝不静默丢；
 *   - 方案取审计件 §3 刀三的 **(b) 拒绝 + 计数 + 死信面**（与 Pekko 的
 *     `DeadLetter` 同义），不取 (a) `offer` 挂起——挂起会把阻塞传染给发送方
 *     actor，把背压变成级联停顿；
 *   - **绝不** TTL / 自动丢弃 / 常态限流：正常积压与今日完全一致，`!` 签名
 *     （`IO[Unit]`）不变，`LocalActorRef.!` 满时返回 `IO.unit` 而不抛。
 *
 * 纪律：本台账**只计数**，不参与任何投递语义（不入队、不重放、不改序）。
 * 计数为只读观测面（面板 / spec）。
 */
final class DeadLetterLedger private[actor] (ref: Ref[IO, Long]):
  private val log = NebflowLogger.forName("nebflow.actor.dead-letters")

  /** 累计被拒条数（只读访问器；进程内累计，不落盘）。 */
  def count: IO[Long] = ref.get

  /** 记一条死信：计数 +1，并响亮 ERROR（带 path / 箱种类 / 容量）。 */
  def record(path: String, queueKind: String, capacity: Int, reason: String): IO[Unit] =
    ref.updateAndGet(_ + 1).flatMap { n =>
      log.error(
        s"dead letter #$n: $path — $queueKind full (capacity=$capacity, $reason); " +
          "message REJECTED and NOT enqueued (explosion guard only — no TTL, no drop policy, no rate limit)"
      )
    }

end DeadLetterLedger

object DeadLetterLedger:

  /** 显式创建（IO 面；测试/新装配点用）。 */
  def create: IO[DeadLetterLedger] = Ref.of[IO, Long](0L).map(new DeadLetterLedger(_))

  /** 构造期装配面（LocalActorSystem 的 `registry`/`watchers` 同款 `Ref.unsafe` 先例）。 */
  def unsafe: DeadLetterLedger = new DeadLetterLedger(Ref.unsafe(0L))

end DeadLetterLedger
