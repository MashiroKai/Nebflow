package nebflow.bridge

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import nebflow.shared.NebflowLogger

/**
 * AskUser 社交腿答案的**回执反向通道注册表**（answer-landing 实施批 2026-10-21）。
 *
 * == 为什么需要它（缺口定位）==
 *
 * 桥面作答的上行链是单向的：渠道 → `forwardInteractionAnswer` → hub `complete` →
 * `AskUserAnswerBridge.deliver`。landing 面（着陆面三臂）在最后一公里才知道
 * 「答案送到了哪」——**失败出口**（归因缺席 / 宿主不在）必须把「问题已作废 + 原因」
 * **显式送回渠道**（任务令：任何出口不静默；设计开放问题 #2 推荐默认「渠道回执」
 * 采纳），但引擎侧（agent 包）没有任何一条通往渠道的路由。本注册表就是那条路：
 *
 *   · 桥插件（微信 / 飞书）在**转发作答的同一时刻**登记回执发送闭包
 *     （`requestId → 渠道外发一行`）——渠道此刻在手，闭包零成本；
 *   · landing 面按 `requestId` 取用：成功臂消费后即弃，失败臂用它回发失败回执行；
 *   · 问题退役的各既有清理点（取消 / `askUserClosed` / B-3 新问顶旧问）随撤登记。
 *
 * == 设计边界 ==
 *
 *   · **零 hub 改动、零网关改动**：不走 payload `via` 键（那需要改 hub `complete`
 *     的投递面），requestId 本就是全程唯一键（`asknb-<16hex>`）。
 *   · **有界**：容量 [[MaxEntries]]，溢出逐出最早登记项（createdAt 序）——登记项
 *     只服务「刚发出的答案」的回执，陈旧项被逐出 = 该答案早已着陆或早已失败，
 *     回执通道随之失效是正确语义（审计链仍在 FlowMapEventLog / 日志，不静默）。
 *   · **消费即弃**：`take` 原子取走（登记 → 消费是 1:1）；`peek` 供 landing 面在
 *     不确定成败时先行只读（actor 侧失败路径用 `take` 兜底）。
 *   · 纯内存（Ref），无 fiber、无定时器——**零超时纪律**不破（不引入任何定时清理）。
 */
object AskAnswerReceipt:

  private val logger = NebflowLogger.forName("nebflow.bridge.ask-receipt")

  /**
   * 一条回执通道：哪个渠道（诊断可读）、路由键（诊断可读）、以及把一行文本
   * 送回渠道的闭包。闭包应当 contained（内部消化发送失败，绝不 raise）——
   * 回执是 best-effort 增量面，绝不让一次回执失败炸掉着陆面本身。
   */
  final case class Entry(
    channel: String,
    routeKey: String,
    send: String => IO[Unit],
    createdAt: Long = System.currentTimeMillis(),
    /** 单调登记序（record 时刻由注册表盖章；同毫秒登记的确定性逐出裁决键）。 */
    seq: Long = 0L
  )

  /** 容量上限：溢出逐出最早登记项（有界自清理，零定时器）。 */
  val MaxEntries: Int = 256

  private val entries: Ref[IO, Map[String, Entry]] = Ref.unsafe[IO, Map[String, Entry]](Map.empty)
  private val seqCounter: java.util.concurrent.atomic.AtomicLong = new java.util.concurrent.atomic.AtomicLong(0L)

  /**
   * 登记（同 requestId 幂等覆盖——后到的转发是同一答案的重复面，后者为准）。
   * 每次登记由注册表盖单调 seq——`createdAt` 毫秒粒度在同毫秒批量登记下不可分
   * （同毫秒并列时 sortBy 稳定序落在 Map 的散列迭代序上 = 任意），seq 使「最旧」
   * 恒等于「最早登记」（先例：hub `PendingRequest.createdAt` 的插入序判据）。
   */
  def record(requestId: String, entry: Entry): IO[Unit] =
    IO.delay(seqCounter.getAndIncrement()).flatMap { seq =>
      entries.modify { m =>
        val updated = m.updated(requestId, entry.copy(seq = seq))
        val evicted =
          if updated.sizeIs <= MaxEntries then updated
          else
            val excess = updated.size - MaxEntries
            val oldest =
              updated.toVector.sortBy((_, e) => (e.createdAt, e.seq)).take(excess).map(_._1).toSet
            updated.filterNot((k, _) => oldest.contains(k) && k != requestId)
        (evicted, ())
      }
    }

  /** 只读取用（不消费）——landing 面判断「有没有回执通道」用。 */
  def peek(requestId: String): IO[Option[Entry]] = entries.get.map(_.get(requestId))

  /** 原子消费（取走即删）——唯一成功语义的取用面。 */
  def take(requestId: String): IO[Option[Entry]] =
    entries.modify(m => (m - requestId, m.get(requestId)))

  /** 随问题退役清理（取消 / askUserClosed / B-3 新问顶旧问 / 幂等 no-op）。 */
  def drop(requestId: String): IO[Unit] = entries.update(_ - requestId)

  /** 在册登记项数（有界判据的观测面；spec R2 用）。 */
  def size: IO[Int] = entries.get.map(_.size)

  /**
   * **best-effort 回执行**（landing 失败出口的消费面）：取走登记项并外发一行；
   * 无登记 / 发送失败都只 WARN——回执失败绝不掩盖主出口的审计与 toast 面。
   * `peekFirst` = true 时只读不消费（调用方还会走其它出口时用）。
   */
  def sendLine(requestId: String, line: String, peekFirst: Boolean = false): IO[Boolean] =
    (if peekFirst then peek(requestId) else take(requestId)).flatMap {
      case None =>
        logger.warn(s"ask-answer receipt: no channel registered for requestId=$requestId — receipt line not sent")
          .as(false)
      case Some(e) =>
        e.send(line)
          .as(true)
          .handleErrorWith(err =>
            logger.warn(
              s"ask-answer receipt: channel=${e.channel} route=${e.routeKey} send failed: ${err.getMessage}"
            ).as(false)
          )
    }

end AskAnswerReceipt
