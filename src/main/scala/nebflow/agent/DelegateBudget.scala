package nebflow.agent

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import nebflow.core.NebflowLogger

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * DelegateBudget — R11 第 4 层 wall-clock 预算（Delegate 内核会话专用）。
 *
 * 存在的理由（恢复设计 §4.4）：远端执行没有进程级活性探测，前台远程调用每
 * 30s 主动刷进程侧活动戳 ⇒ `TaskStuckWatcher` 对「远端命令挂死但本地在心跳」
 * 永不触发。第 4 层给这种情况一个确定上界。
 *
 * 口径（作者裁定 U1 = C-a，逐字落地）：
 *   - **3600s 预算不含人机等待期**——内核提问挂起（`WaitingForUser`）时暂停
 *     计时，答案到达时恢复；等待本身仍无界（R1「人而不是进程驱动进展」）。
 *   - **10min TaskStuckWatcher 档在等待期不生效 = 零改动**（`TaskStuckWatcher`
 *     的状态过滤先于判据；本文件不触碰它、也不触碰 `Defaults`）。
 *   - 超时走**既有 cancel 链**：向监督者（`BackoffSupervisor`）投
 *     `AgentEvent.Cancelled(reason="timeout")` ⇒ 既有终态通知 + 父 barrier 释放
 *     + registry 清理 + child Stop —— **不新增事件类型**。
 *
 * 实现形态（作者裁定 U8 = (ii)）：由 ask 发起 / 答复两个**既有单点**发
 * 暂停 / 恢复信号（`AgentActor` 的 AskUser 分支 + `AskUserQuestionTool` 的
 * `restoreRegistryAfterAnswer`），**不轮询**读 registry status。
 *
 * 计时精度 = 事件驱动（`IO.race(IO.sleep(remaining), queue.take)`）：暂停时
 * 取消 sleep，恢复时按实际等待时长扣减，无扫描周期粒度损失。
 */
object DelegateBudget:

  private val logger = NebflowLogger.forName("nebflow.agent.delegate-budget")

  /** 内核会话的预算（与 `RemoteExecutor.BgTimeout` 同值，便于解释）。 */
  val DefaultBudget: FiniteDuration = 3600.seconds

  /** 预算消息：暂停 / 恢复 / 释放（终态）。 */
  enum Signal:
    case Pause, Resume, Release

  /**
   * 预算账本（**纯函数**，C-a 判据的单点来源）。
   *
   * 语义：`countedMs` = 从 spawn 起算的墙上时间 − 人机等待时长。等待期一律不
   * 判超时（`exhausted` 在 paused 时恒 false）——这是 U1=C-a 与 C-b（纯
   * wall-clock，含等待）可区分的那一行。
   */
  final case class Ledger(
      budgetMs: Long,
      startedAt: Long,
      pausedAt: Option[Long] = None,
      pausedTotalMs: Long = 0L
  ):
    /** 进入等待（重复 Pause 幂等——第一次的起点为准）。 */
    def pause(now: Long): Ledger =
      if pausedAt.isDefined then this else copy(pausedAt = Some(now))

    /** 离开等待：把本次等待时长累计进「不计入」桶。非等待态调用是 no-op。 */
    def resume(now: Long): Ledger =
      pausedAt match
        case Some(t0) => copy(pausedAt = None, pausedTotalMs = pausedTotalMs + math.max(0L, now - t0))
        case None     => this

    /** 已计入预算的执行时长（等待期扣除）。 */
    def countedMs(now: Long): Long =
      val pausedNow = pausedAt.map(t0 => math.max(0L, now - t0)).getOrElse(0L)
      math.max(0L, (now - startedAt) - (pausedTotalMs + pausedNow))

    /** 剩余预算（可为负 = 已超支）。 */
    def remainingMs(now: Long): Long = budgetMs - countedMs(now)

    /** 是否已超预算。**等待中恒 false**（等待无界，不被 3600s 收割）。 */
    def exhausted(now: Long): Boolean = pausedAt.isEmpty && remainingMs(now) <= 0L
  end Ledger

  /** 活预算通道：sessionId → 信号队列。只含被 register 过的 Delegate 内核会话
    * （其余会话的 pause/resume/release 调用是无害 no-op）。 */
  private val channels = TrieMap.empty[String, Queue[IO, Signal]]

  /** 可观测读数（诊断 / 测试用）：当前有预算在跑的会话数。 */
  def activeCount: Int = channels.size

  /**
   * 为 `sessionId` 起一条预算。`onTimeout` 在预算耗尽时执行一次（既有 cancel
   * 链的接入点）。重复 register 同一 sessionId = 覆盖（先 release 旧的）。
   * `clock` 可注入（测试）；生产用墙上时钟。
   */
  def register(
      sessionId: String,
      budget: FiniteDuration = DefaultBudget,
      clock: () => Long = () => System.currentTimeMillis()
  )(onTimeout: IO[Unit]): IO[Unit] =
    for
      _ <- release(sessionId)
      q <- Queue.unbounded[IO, Signal]
      _ = channels.put(sessionId, q)
      _ <- loop(
        sessionId,
        q,
        Ledger(budgetMs = budget.toMillis, startedAt = clock()),
        clock,
        onTimeout
      ).start.void
      _ <- logger.info(s"DelegateBudget: budget ${budget.toSeconds}s armed for $sessionId (wait-time excluded, ruling U1=C-a)")
    yield ()

  /** ask 发起单点调用（`AgentActor` 的 AskUser 分支）：暂停计时。 */
  def pause(sessionId: String): IO[Unit] = signal(sessionId, Signal.Pause)

  /** ask 答复单点调用（`AskUserQuestionTool.restoreRegistryAfterAnswer`）：恢复计时。 */
  def resume(sessionId: String): IO[Unit] = signal(sessionId, Signal.Resume)

  /** 终态 / respawn：释放通道（不再计时），并清掉 map 条目。 */
  def release(sessionId: String): IO[Unit] = signal(sessionId, Signal.Release)

  private def signal(sessionId: String, s: Signal): IO[Unit] =
    channels.get(sessionId) match
      case Some(q) => q.offer(s).void
      case None    => IO.unit

  private def loop(
      sessionId: String,
      q: Queue[IO, Signal],
      ledger: Ledger,
      clock: () => Long,
      onTimeout: IO[Unit]
  ): IO[Unit] =
    val now = clock()
    // 顺序有意：等待态优先（等待无界，即使剩余已 ≤0 也不收割——U1=C-a）。
    if ledger.pausedAt.isDefined then awaitResume(sessionId, q, ledger, clock, onTimeout)
    else if ledger.exhausted(now) then fire(sessionId, ledger, clock, onTimeout)
    else
      IO.race(IO.sleep(ledger.remainingMs(now).millis), q.take).flatMap {
        case Left(_)               => fire(sessionId, ledger, clock, onTimeout)
        case Right(Signal.Release) => IO(channels.remove(sessionId)).void
        case Right(Signal.Resume)  => loop(sessionId, q, ledger, clock, onTimeout)
        case Right(Signal.Pause)   => loop(sessionId, q, ledger.pause(clock()), clock, onTimeout)
      }

  /** 等待期：预算冻结，直到 Resume（继续计时）或 Release（放弃）。 */
  private def awaitResume(
      sessionId: String,
      q: Queue[IO, Signal],
      ledger: Ledger,
      clock: () => Long,
      onTimeout: IO[Unit]
  ): IO[Unit] =
    q.take.flatMap {
      case Signal.Release => IO(channels.remove(sessionId)).void
      case Signal.Resume  => loop(sessionId, q, ledger.resume(clock()), clock, onTimeout)
      case Signal.Pause   => awaitResume(sessionId, q, ledger, clock, onTimeout)
    }

  private def fire(
      sessionId: String,
      ledger: Ledger,
      clock: () => Long,
      onTimeout: IO[Unit]
  ): IO[Unit] =
    val counted = ledger.countedMs(clock())
    logger.warn(
      s"DelegateBudget: kernel session $sessionId exceeded its ${ledger.budgetMs / 1000}s wall-clock budget " +
        s"(counted ${counted / 1000}s excluding human-wait) — cancelling via the existing chain (reason=timeout)"
    ) *>
      IO(channels.remove(sessionId)).void *>
      onTimeout.handleErrorWith(e => logger.warn(s"DelegateBudget: timeout cancel failed for $sessionId: ${e.getMessage}"))

end DelegateBudget
