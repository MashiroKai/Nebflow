package nebflow.neblink

import cats.effect.{Deferred, IO, Outcome}
import nebflow.core.NebflowLogger

import java.util.concurrent.{CancellationException, ConcurrentHashMap}

/** 进程级**单飞登记表**（keyed single-flight）—— 「闸不被 hot-swap 重置」的落地件。
  *
  * WHY 进程级而不是实例级（kaiauth 修法批 ③，2026-09-16 作者「治本」已批）：
  * 修前 `NeblinkClient.reloginGate` 是**实例级** `Ref`，而 `NeblinkEnrollment.persist`
  * 的 hot-swap 会新建一个 client 实例 ⇒ 新实例的闸为空，**旧实例在飞的 enroll /
  * 会话交换不受新实例约束**（类注释自证其前提「all consumers share one instance」
  * 在 hot-swap 当刻不成立）。服务端 `enroll_device` 是 `INSERT OR REPLACE`
  * （跨仓只读 `neblink-server/src/store.rs:2951-2969`）⇒ 并发 enroll 互相作废，
  * 「enroll 成功」与「发送值有效」可不重合（诊断报告 §6 的一条口子）。
  *
  * 本对象把闸搬到**companion 级**（JVM 内共享）⇒ 同一 (维度键) 的并发调用在任何
  * hot-swap 前后都只有一个赢家。**范围（诚实边界）**：进程内共享，**无跨进程/跨实例
  * 协调、无 wire 新增**（与 `NeblinkService.kickParked` 的「进程内状态」口径同源）。
  *
  * 语义 = **单飞**，不是限流/冷却：赢家跑 body，并发输家等赢家结果并**复用**它；
  * 串行（不重叠）的调用各自跑自己的 body。
  *
  * 🔴 调用契约：**一个 key 只能配一种载荷类型**（内部按 `Any` 存放 + 取回时还原；
  * 同 key 混用不同类型 = `ClassCastException`）。key 由 [[key]] 以命名字空间构造
  * （`session` / `enroll`），两个命名空间**故意不同**：`enroll` 的 body 内部会发起
  * 会话交换 ⇒ 若与 `session` 同名，同一 fiber 会在自己的闸上自等（死锁）。
  */
private[neblink] object NeblinkSingleFlight:

  private val logger = NebflowLogger.forName("nebflow.neblink.singleflight")

  /** key → 正在跑的赢家（完成后移除）。`Deferred` 在 create/complete 两侧都幂等。 */
  private val registry = new ConcurrentHashMap[String, Deferred[IO, Either[Throwable, Any]]]()

  /** 命名字空间键。`namespace` 区分调用面（勿混用）；`a` / `b` 是维度（如
    * deviceId / networkId，或 url / networkId）。 */
  def key(namespace: String, a: String, b: String): String = s"$namespace|$a|$b"

  /** 单飞执行：赢家跑 `body`，输家等赢家结果并复用（同一个值）。
    *
    * 类型面：登记表按 `Any` 存放（`Deferred[F, A]` 在 A 上**不变**，混型只能靠边界
    * 还原），取回处 `asInstanceOf[A]` —— 正确性由「一个 key 一种载荷类型」的调用契约
    * 保证（见对象注释）。 */
  def serialize[A](k: String)(body: IO[A]): IO[A] =
    Deferred[IO, Either[Throwable, Any]].flatMap { mine =>
      IO(registry.compute(k, (_, current) => if current == null then mine else current)).flatMap { gate =>
        if gate eq mine then
          // 赢家：跑自己的 body，把结果发布给输家，然后释放闸。
          // 释放走 guaranteeCase：取消路径也必须释放 + 发布（否则输家永远等在
          // 一个不会有结果的 Deferred 上——比「报错」更糟：静默挂死）。
          body.attempt
            .flatMap(result => mine.complete(result).as(result))
            .guaranteeCase {
              case Outcome.Canceled() =>
                mine
                  .complete(Left(new CancellationException(s"single-flight winner cancelled [$k]")))
                  .void
                  .guarantee(release(k, mine))
              case _ => release(k, mine)
            }
            .flatMap(_.fold(IO.raiseError, v => IO.pure(v.asInstanceOf[A])))
        else
          // 输家：等赢家（复用其结果；赢家失败 ⇒ 同一个错误如实透出）。
          gate.get.flatMap(_.fold(IO.raiseError, v => IO.pure(v.asInstanceOf[A])))
      }
    }

  private def release(k: String, mine: Deferred[IO, Either[Throwable, Any]]): IO[Unit] =
    IO {
      registry.compute(k, (_, current) => if current eq mine then null else current)
      ()
    }

  /** 观测面（测试用）：当前在册（在飞）的 key 集合。 */
  private[neblink] def inFlightKeys: Set[String] =
    registry.keySet().stream().toList.toArray.toSet.map(_.toString)

  /** 测试隔离（**只**用于 spec 的 beforeEach）：清掉在册闸。
    *
    * 为什么不给生产留口子：`registry` 里只放**在飞**的闸，赢家完成即出表 ⇒ 生产
    * 语义下永不需要清。但 munit 的多个 suite 共享同一 JVM，若某个 spec 的 fiber 被
    * 取消在「已 acquire、未 release」的窗口里（例如 `parSequence` 被中断），登记表
    * 会残留一个已完成/半完成的键 ⇒ 后续同名 key 的 spec 会复用旧结果而非重跑。
    * 本方法把这个不确定性从测试面消掉（调用点仅限 spec）。 */
  private[neblink] def resetForTest(): Unit =
    logger.info(s"NeblinkSingleFlight: resetForTest cleared ${registry.size()} in-flight gate(s)")
    registry.clear()

end NeblinkSingleFlight
