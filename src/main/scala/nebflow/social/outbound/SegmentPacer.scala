package nebflow.social.outbound

import cats.effect.{Deferred, IO, Ref}

import scala.concurrent.duration.*

/**
 * 逐段即时推送批（2026-10-10）：渠道出站文本段的节流队列。
 *
 * 背景：渠道出站腿（[[nebflow.social.FeishuBridgePlugin]] /
 * [[nebflow.social.WeixinIlinkBridgePlugin]]）原来把整轮 `textDelta` 攒在
 * pendingRef 里、`done` 时一次性整发——与桌面端「一段一气泡、随提交即现」的
 * 体验不一致（作者 2026-10-09 原话：一条消息就对应一条渠道消息，一条一条即时
 * 推送）。逐段化之后，段与段之间需要一个**顺序 + 节奏**的收口面：本类就是
 * 那个收口面，每条渠道腿各持一个实例（两渠道的频控预算互不共享）。
 *
 * 机制（票据链）：每个任务注册时领一张「完成票据」（Deferred），并把票据装进
 * 单一 Ref 作为下一位的前任——**取前任与装新票是一次原子 modify**，两个并发
 * 注册也严格成链。runner 的形状是 `前任完成 → 节流间隔 → 任务 → 交自己的票`：
 * 等前任（而非重放链）保证每个任务恰好执行一次；前任的票由其 guarantee 交付
 * ⇒ 等待是传递的（我的票到手的瞬间，此前所有任务都已完成）。
 *
 *   - **段间最小间隔**：相邻两次发送**开始**时刻至少隔 `minGapMs`（start-to-start
 *     口径：慢发送不会缩短间隔，快发送不会突发连发；空闲后首条零等待——
 *     「即时」对第一段永远成立）；
 *   - **两种调用形态**：`enqueue(task)` 注册并 **await**——返回的 IO 在本任务
 *     完成后才结束（轮终腿 `done` 用它——等到尾段出网时，队列里先于它的段必然
 *     全部出网，这就是「轮终不重发、顺序有界」的结构保证）；`enqueueAsync(task)`
 *     **同步注册、后台运行**（段边界腿 `toolStart` / `roundComplete` 用它——
 *     注册落在调用方 fiber 上，顺序即事件到达顺序；后台跑使流式事件面
 *     （wsHub 广播监听内联执行）绝不被 1s 节流阻塞）。
 *   - **内存有界**：Ref 里只留最后一张票，进程存活期不累积。
 *
 * 边界：队列只管顺序与节奏——不吞异常、不重试、不改写任务；失败读数
 * （各腿既有的 WARN / 失败注明腿）留在任务内部原样保持。WeixinOutbound 的
 * 重发边界（超时可能已送达 ⇒ 不补发）不受影响。
 */
final class SegmentPacer private (minGapMs: Long):

  /**
   * The turn-taking ticket: `Some(d)` = the latest registered runner's
   * completion signal (already completed at rest); `None` = never used yet.
   * ONE atomic cell: installing my ticket and reading my predecessor is a
   * single modify, so even two racing registrars chain strictly.
   */
  private val ticketRef: Ref[IO, Option[Deferred[IO, Unit]]] = Ref.unsafe(None)

  /** The last send's START (monotonic ms) — the start-to-start pace anchor. */
  private val lastStartMs: Ref[IO, Long] = Ref.unsafe[IO, Long](0L)

  /**
   * The pace: ≥ `minGapMs` since the previous send STARTED (zero wait for the
   * first send after idle).
   */
  private def paced(task: IO[Unit]): IO[Unit] =
    lastStartMs.get.flatMap { last =>
      IO.monotonic.flatMap { now =>
        val wait = if last <= 0L then 0L else math.max(0L, minGapMs - (now.toMillis - last))
        IO.sleep(wait.millis) *> IO.monotonic.flatMap(d => lastStartMs.set(d.toMillis)) *> task
      }
    }

  /**
   * Register `task` at the chain tail **on the caller's fiber** (the atomic
   * modify IS the ordering point) and return its runner. The runner runs
   * exactly once — inline via [[enqueue]] or forked via [[enqueueAsync]] —
   * and hands ITS ticket to the next registrant on completion.
   */
  private def register(task: IO[Unit]): IO[IO[Unit]] =
    Deferred[IO, Unit].flatMap { mine =>
      ticketRef
        .modify {
          case Some(prev) => (Some(mine), Some(prev))
          case None => (Some(mine), None)
        }
        .map {
          case Some(prev) => prev.get *> paced(task).guarantee(mine.complete(()).void)
          case None => paced(task).guarantee(mine.complete(()).void)
        }
    }

  /**
   * Register and AWAIT: the returned IO ends when this task (and, by the
   * ticket chain, everything registered before it) completed. The turn-end
   * leg (`done`) uses this — awaiting the tail is the structural guarantee
   * that every earlier segment is already out.
   */
  def enqueue(task: IO[Unit]): IO[Unit] = register(task).flatten

  /**
   * Register synchronously, run in the background: the mid-turn boundary leg
   * (`toolStart` / `roundComplete`) uses this — the register lands in the
   * caller's fiber (order preserved), the 1s-paced run never blocks the
   * streaming event face.
   */
  def enqueueAsync(task: IO[Unit]): IO[Unit] = register(task).flatMap(_.start).void

end SegmentPacer

object SegmentPacer:

  /** 段间最小间隔（生产默认 1s）。两条渠道腿各自持有一个 pacer，互不影响。 */
  val DefaultGapMs = 1000L

  /**
   * The constructor face for callers that build their refs unsafely (the two
   * bridges' field-initializer style — no IO context at construction).
   */
  def unsafe(minGapMs: Long = DefaultGapMs): SegmentPacer = new SegmentPacer(minGapMs)

end SegmentPacer
