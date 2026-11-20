package nebflow.actor

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * 刀三·爆炸保护 —— **内核消息箱**有界 + 死信计数的反向验红（段D ②）。
 *
 * 裁定口径：**只做爆炸保护，不做流量控制**——箱容量极高
 * （[[ActorSystem.DefaultMailboxCapacity]] = 8192，`-D nebflow.actor.mailboxCapacity`
 * 可覆写），满时**拒绝 + 计数 + ERROR 可见**（[[DeadLetterLedger]]，与 Pekko
 * `DeadLetter` 同义），**不挂起**（不把阻塞传染给发送方）、**不静默丢**、
 * **无 TTL / 无丢弃策略 / 无常态限流**。
 *
 * ## 本 spec 钉住的三条边（一条测试全给，因为它们是同一事故态的三个面）
 *
 * - **K1（拒得准）**：箱内恰好灌满 `capacity` 条后，第 `capacity+1` 条使
 *   `deadLetterCount` **恰好 +1**（不多记、不重复记、不静默 —— `!` 仍返回
 *   `IO.unit`）。
 * - **K2（不崩、不丢已入队的）**：拒信路径绝不打断 actor —— 闸开之后，箱里
 *   那 `capacity` 条**逐条都被处理**（一条不丢）。
 * - **K3（事后照常）**：风暴过后再发一条照样被处理，`isAlive` 为真。
 *
 * 🔴 非空洞：K1 先断言风暴前计数 == 0（否则「恰好 1」可能只是从 1 开始加）；
 * K2 断言的是 `capacity` 这个**具体数**，而不是「≥ 某数」。
 *
 * 🔴 零污染：本 spec **不碰任何 `-D`**（用默认容量原值跑，避免影响并行套件），
 * 用「首个消息把 handler 卡在 `Deferred` 上」制造确定性积压；末尾 `stopAll`。
 */
class ActorMailboxExplosionGuardSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  private case object Block
  private case object Ping

  /** 首条消息把 loop 卡在 `gate` 上（确定性积压），其余消息只累加计数。 */
  private def probe(
    started: Deferred[IO, Unit],
    gate: Deferred[IO, Unit],
    processed: Ref[IO, Int]
  ): Behavior[Any] =
    new Behavior[Any]:
      def receive(ctx: ActorContext[Any], msg: Any): IO[Behavior[Any]] =
        msg match
          case Block => started.complete(()).void *> gate.get *> IO.pure(this)
          case _ => processed.update(_ + 1).as(this)

  /** 有界等待（不是无限轮询）：最多 30s，超时即以当前值返回由断言判红。 */
  private def awaitProcessed(processed: Ref[IO, Int], target: Int): IO[Int] =
    processed.get
      .flatMap { n =>
        if n >= target then IO.pure(n)
        else IO.sleep(50.millis) *> awaitProcessed(processed, target)
      }
      .timeoutTo(30.seconds, processed.get)

  test("stopping an older actor must not unregister its same-path replacement") {
    val system = ActorSystem(s"actor-incarnation-${scala.util.Random.nextInt(100000)}")
    val idle = new Behavior[String]:
      def receive(ctx: ActorContext[String], msg: String): IO[Behavior[String]] = IO.pure(this)
    (for
      old <- system.spawn(idle, "same-name")
      replacement <- system.spawn(idle, "same-name")
      _ <- system.stop(old)
      resolved <- system.resolve[String](replacement.path.toString)
    yield assert(resolved eq replacement, "old incarnation cleanup removed its replacement"))
      .guarantee(system.stopAll)
  }

  test(
    "K1/K2/K3: filling the kernel box to capacity + 1 records exactly ONE dead letter, keeps the actor alive, loses no queued message"
  ) {
    val cap = ActorSystem.DefaultMailboxCapacity
    val system = ActorSystem(s"deadletter-spec-${scala.util.Random.nextInt(100000)}")
    val program = for
      started <- Deferred[IO, Unit]
      gate <- Deferred[IO, Unit]
      processed <- IO.ref(0)
      ref <- system.spawn(probe(started, gate, processed), "bounded-probe")
      before <- system.deadLetterCount
      _ <- ref ! Block
      _ <- started.get // 此刻 loop 卡在 handler 里，箱内条目只会累积
      _ <- (1 to cap).toList.traverse_(_ => ref ! Ping) // 恰好灌满 capacity
      _ <- ref ! Ping // 第 capacity+1 条 ⇒ 走到拒绝 + 计数
      rejected <- system.deadLetterCount
      aliveWhileFull <- system.isAlive(ref.path)
      _ <- gate.complete(())
      drained <- awaitProcessed(processed, cap)
      _ <- ref ! Ping
      afterOneMore <- awaitProcessed(processed, cap + 1)
      alive <- system.isAlive(ref.path)
    yield
      // K1 —— 拒得准
      assertEquals(before, 0L, "non-vacuity: a fresh system must start at zero dead letters")
      assertEquals(
        clue(rejected),
        1L,
        s"exactly ONE message must be recorded as dead at capacity=$cap (reject + count, never silent)"
      )
      assert(aliveWhileFull, "the dead-letter path must not kill the actor")
      // K2 —— 已入队的 capacity 条一条不丢
      assertEquals(
        clue(drained),
        cap,
        s"every one of the $cap queued messages must still be processed once the box drains"
      )
      // K3 —— 风暴后照常
      assertEquals(clue(afterOneMore), cap + 1, "a message sent after the storm must be handled normally")
      assert(alive, "the actor must still be alive after the storm")
    program.guarantee(system.stopAll.handleErrorWith(_ => IO.unit))
  }

end ActorMailboxExplosionGuardSpec
