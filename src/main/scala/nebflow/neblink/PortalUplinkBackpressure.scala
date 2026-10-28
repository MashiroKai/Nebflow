package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

/**
 * 台账 #949（2026-10-26 作者微信报，Mac 引擎 stand.log 近 2000 行刷屏）——
 * portal 上行背压的**纯机制件**，供 [[PortalTunnelBridge]] 的
 * `LocalWsListener.offerUp` / `teardown` / `PortalConnection.armLifecycle`
 * 装配：
 *
 *  - [[UplinkFullAlarm]]（面一·同因限频）：同一 requestId 的「inbox full」WARN
 *    只首条 + 每 N 拍（N=[[UplinkFullAlarm.EveryN]]=32 次事件计数）一条，状态
 *    变化（进入满态 / 恢复流动）即报——根治「一行×每秒」。事件驱动计数，零墙钟。
 *  - [[UplinkBackoff]]（面二·拍退避）：capacity-wait 的重试停等按**拍计数翻倍**
 *    （拍 = [[UplinkBackoff.BaseBeatMillis]] 毫秒的停等节拍，与
 *    `RelayFrameSender.PendingRetryMillis` 同拍长），封顶 [[UplinkBackoff.MaxBeats]]
 *    拍；**没有截止时刻、没有放弃时限**——退出只由两个事件驱动：拿到容量、或
 *    周期死亡（注册表摘除事件）。
 *  - [[PortalUplinkDeadLetter]]（面二·宁重勿丢的死信区，#944
 *    `DeviceResultOutbox` 同构）：capacity-wait 期间周期死亡 / 连接收尾时仍在
 *    uplink inbox 里排队的件**不再静默消失**——整帧原文（`PortalWireCodec.encode`
 *    产物，字节面 base64 已在帧内）落盘 `<dataRoot>/portal-uplink-deadletter/`，
 *    带来源（source）与 requestId；relay（重）连上第一刻按入队序补投
 *    （`PortalConnection.armLifecycle` fire-and-forget），成功出队、失败留盘，
 *    at-least-once。
 *
 * == 设计红线（作者 2026-10-26 令）==
 *
 *  - **零墙钟**：本文件没有任何 deadline / 超时判死；一切节奏都是「拍」（固定
 *    节拍的停等）与「事件」（入队成功 / 周期摘除 / 连接收尾）。
 *  - **落盘失败不阻断主路径**：死信写失败只 WARN（簿记面纪律，#944 同款），
 *    调用方的生命周期路径照常走完。
 *  - **有序**：文件名以 `enqueuedAt` 前缀 + id 序排序，drain 严格按入队序补投。
 */
private[neblink] object UplinkBackpressure:

  /** 面一限频 N：同因满态第 1 条报，之后每第 N 次事件报一条。 */
  val FullWarnEveryN: Int = 32

end UplinkBackpressure

/**
 * 同因限频报警器（面一）：一个 ws 周期（requestId）一个实例，单生产者
 * （该周期的本地 listener 回调串行）调用。
 *
 * 状态机：flowing --strike--> full（episode+1，第 1 条即报）
 *         full --strike--> full（每第 N 条报一条，其余 suppressed++）
 *         full --recovered--> flowing（状态变化即报，带 suppressed 读数）
 *
 * 所有计数纯事件驱动（strike / recovered 调用次数），零 wall-clock。
 */
private[neblink] final class UplinkFullAlarm(val requestId: String, everyN: Int = UplinkBackpressure.FullWarnEveryN):

  private val inFull = new AtomicBoolean(false)
  private val strikes = new AtomicLong(0L)
  private val episodes = new AtomicLong(0L)
  private val suppressed = new AtomicLong(0L)

  /** 当前满态累计 strike 数（等容量期间经历的事件读数）。 */
  def strikeCount: Long = strikes.get()

  /**
   * 一次「inbox 满」事件。返回 Some(line) = 这一条必须上 WARN（首条 / 每 N 条 /
   * 状态变化进入满态），None = 被限频吞掉（只累计 suppressed）。
   *
   * @param depth     当前 inbox 深度读数（积压件数）
   * @param capacity  inbox 容量
   * @param itemBytes 触发本条的帧 payload 字节读数（0 = 非帧件）
   */
  def strike(depth: Int, capacity: Int, itemBytes: Long): Option[String] =
    val entered = !inFull.getAndSet(true) // flowing → full：状态变化，即报
    val n = strikes.incrementAndGet()
    if entered then
      episodes.incrementAndGet()
      Some(fullLine(n, depth, capacity, itemBytes))
    else if n % everyN == 0 then Some(fullLine(n, depth, capacity, itemBytes))
    else
      suppressed.incrementAndGet()
      None
  end strike

  private def fullLine(n: Long, depth: Int, capacity: Int, itemBytes: Long): String =
    s"portal uplink inbox full: requestId=$requestId — capacity-wait (item QUEUED, NOT dropped) " +
      s"strike=$n episode=${episodes.get()} depth=$depth/$capacity " +
      s"lastItemBytes=$itemBytes suppressed=${suppressed.get()} " +
      "(relay slower than the local peer; beat backoff active — ledger #949)"

  /**
   * 状态变化 full → flowing（拿到容量恢复流动）：每个满态回合恰一条恢复读数，
   * 然后清回合计数。
   */
  def recovered(): Option[String] =
    if inFull.getAndSet(false) then
      val n = strikes.getAndSet(0)
      val sup = suppressed.getAndSet(0)
      Some(
        s"portal uplink inbox full recovered: requestId=$requestId — episode=${episodes.get()} ended " +
          s"after $n full strikes (suppressed=$sup); uplink flowing again"
      )
    else None
  end recovered

end UplinkFullAlarm

/**
 * 面二·拍退避（纯函数 + 停等）：第 r 轮停等 `min(2^(r-1), MaxBeats)` 个拍
 * （1 拍 = [[BaseBeatMillis]] 毫秒，与 `RelayFrameSender.PendingRetryMillis`
 * 同拍长——同一 socket 面统一的节拍单位）。**纯拍计数，无截止时刻**：调用方
 * 退出循环只看「拿到容量 / 周期死亡」两个事件，不看时钟。
 */
private[neblink] object UplinkBackoff:

  /** 一拍的停等毫秒数（事件节拍单位，非超时——没有任何放弃语义）。 */
  val BaseBeatMillis: Long = 25L

  /** 拍数封顶：第 r 轮起不再翻倍（16 拍 = 400ms 顶，保住 selector 线程让位）。 */
  val MaxBeats: Int = 16

  /** 第 r 轮的停等拍数：1,2,4,8,16,16,16,…（翻倍 + 封顶）。 */
  def beatsFor(round: Int): Int =
    if round <= 1 then 1 else math.min(1 << (round - 1), MaxBeats)

  def parkMillisFor(round: Int): Long = beatsFor(round) * BaseBeatMillis

  /** 停等一拍（可中断；中断只恢复标志位，不抛——listener 线程纪律）。 */
  def park(round: Int): Unit =
    try Thread.sleep(parkMillisFor(round))
    catch case _: InterruptedException => Thread.currentThread().interrupt()

end UplinkBackoff

/**
 * portal 上行死信区（面二·「宁重勿丢」的持久半边，#944 `DeviceResultOutbox`
 * 同构）：**确需丢弃**的 uplink 件——capacity-wait 期间周期死亡、或连接收尾时
 * 仍在 inbox 排队——整帧原文落盘 `<dataRoot>/portal-uplink-deadletter/`，带
 * 来源（source）与 requestId，relay（重）连上第一刻按入队序补投，永不静默消失。
 *
 * 语义 = at-least-once：重投可能重复（对端会话已死则由 ingress 丢弃），但件
 * 绝不在本机无声蒸发；投递成功才删、失败/异常件留盘等下一轮（重连 / 下一次
 * boot）。
 *
 * 存储：`<dir>/<enqueuedAt>-<safeId(id)>.json`，一条一件，AtomicJson 整写。
 * **零墙钟**：无 TTL、不按时间判失败；条目只在补投成功后被删。
 */
object PortalUplinkDeadLetter:

  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  /** 件来源（审计读数）：capacity-wait 中周期死亡 / 连接收尾时仍有积压。 */
  val SourceWaitCycleDeath: String = "uplink-wait-cycle-death"
  val SourceTeardown: String = "teardown-connection-end"

  /** 一件死信。`id` = 幂等键（`<seq>-<requestId>`）。 */
  final case class Entry(
    id: String,
    requestId: String,
    source: String,
    /** open_result | ws_frame | relay_close（`PortalWireCodec` 族名）。 */
    kind: String,
    /** 整帧上行原文（`PortalWireCodec.encode` 产物）——重投直接 `send`。 */
    frameText: String,
    /** 帧 payload 字节读数（ws_frame = data 字节；控制件 = 0）。 */
    bytes: Long,
    /** 为什么进死信（人类可读，随件留档）。 */
    reason: String,
    enqueuedAt: Long,
    attempts: Int = 0
  )

  object Entry:
    given Encoder[Entry] = Encoder.instance { e =>
      Json.obj(
        "id" -> e.id.asJson,
        "requestId" -> e.requestId.asJson,
        "source" -> e.source.asJson,
        "kind" -> e.kind.asJson,
        "frameText" -> e.frameText.asJson,
        "bytes" -> e.bytes.asJson,
        "reason" -> e.reason.asJson,
        "enqueuedAt" -> e.enqueuedAt.asJson,
        "attempts" -> e.attempts.asJson
      )
    }
    given Decoder[Entry] = Decoder.instance { c =>
      for
        id         <- c.downField("id").as[String]
        requestId  <- c.downField("requestId").as[String]
        source     <- c.downField("source").as[String]
        kind       <- c.downField("kind").as[String]
        frameText  <- c.downField("frameText").as[String]
        bytes      <- c.downField("bytes").as[Long]
        reason     <- c.downField("reason").as[String]
        enqueuedAt <- c.downField("enqueuedAt").as[Long]
        attempts   <- c.downField("attempts").as[Option[Int]]
      yield Entry(id, requestId, source, kind, frameText, bytes, reason, enqueuedAt, attempts.getOrElse(0))
    }
  end Entry

  private def dir: os.Path = PathUtil.dataRoot / "portal-uplink-deadletter"

  /** id 安全（作文件名尾）：非白名单字符一律替换，防路径穿越。 */
  private def safeId(id: String): String =
    val cleaned = id.map(c => if c.isLetterOrDigit || c == '-' || c == '.' || c == '_' then c else '_')
    if cleaned.nonEmpty then cleaned.take(120) else "anon"

  private def fileOf(e: Entry): os.Path = dir / s"${e.enqueuedAt}-${safeId(e.id)}.json"

  private val seq = new AtomicLong(0L)

  /**
   * 落盘一件（同步核心，listener / teardown 线程纪律）。写失败只 WARN 绝不抛
   * （簿记挂掉不拖垮生命周期路径）。**永不抛** = 死信语义的底线：件进不了盘
   * 也要给调用方一个如实的 WARN 读数。
   */
  def enqueueSync(
    requestId: String,
    source: String,
    kind: String,
    frameText: String,
    bytes: Long,
    reason: String
  ): Unit =
    val e = Entry(
      // seq 零填充 20 位：文件名/id 的字典序 == 数值序 == 入队序（同毫秒批内
      // 也不因 9→10 进位翻序——补投按序是本区的文档化不变量）。
      id = f"${seq.incrementAndGet()}%020d-$requestId",
      requestId = requestId,
      source = source,
      kind = kind,
      frameText = frameText,
      bytes = bytes,
      reason = reason,
      enqueuedAt = System.currentTimeMillis()
    )
    try
      if !os.exists(dir) then os.makeDir.all(dir)
      AtomicJson.writeSync(fileOf(e), e.asJson.noSpaces)
      logger.warnSync(
        s"portal uplink dead-letter: requestId=$requestId kind=$kind bytes=$bytes source=$source " +
          s"file=${fileOf(e).last} — item preserved for in-order replay (ledger #949)"
      )
    catch
      case t: Throwable =>
        logger.warnSync(
          s"portal uplink dead-letter WRITE FAILED: requestId=$requestId kind=$kind bytes=$bytes " +
            s"source=$reason ${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)} — item could not be preserved"
        )
  end enqueueSync

  /** 全量待补投件（按入队序；坏文件跳过并 WARN——簿记坏件不阻断其余补投）。 */
  def pending: IO[List[Entry]] = IO.blocking(pendingSync())

  private def pendingSync(): List[Entry] =
    try
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(_.ext == "json")
          .toList
          .flatMap { p =>
            decode[Entry](os.read(p)) match
              case Right(e) => Some(e)
              case Left(err) =>
                logger.warnSync(s"PortalUplinkDeadLetter: unreadable entry ${p.last} skipped (${err.getMessage})")
                None
          }
          .sortBy(e => (e.enqueuedAt, e.id))
    catch
      case t: Throwable =>
        logger.warnSync(s"PortalUplinkDeadLetter: pending scan failed (${t.getMessage}) — treated as empty")
        Nil

  /** 补投成功 ⇒ 删除该件（幂等：不存在即 no-op）。 */
  def delivered(id: String): IO[Unit] =
    IO.blocking {
      pendingSync().filter(_.id == id).foreach(e => if os.exists(fileOf(e)) then os.remove(fileOf(e)))
    }.void.handleErrorWith(e =>
      logger.warn(s"PortalUplinkDeadLetter.delivered failed for id=$id: ${e.getMessage}").void
    )

  /** 记一次补投尝试（纯读数：attempts 升序可见，不参与任何判失败逻辑）。 */
  private def bumpAttempt(e: Entry): IO[Unit] =
    IO.blocking {
      val updated = e.copy(attempts = e.attempts + 1)
      AtomicJson.writeSync(fileOf(updated), updated.asJson.noSpaces)
    }.void.handleErrorWith(err =>
      logger.warn(s"PortalUplinkDeadLetter: attempt counter write failed for ${e.id}: ${err.getMessage}").void
    )

  /** 补投结果读数。 */
  final case class DrainReport(replayed: List[String] = Nil, kept: List[(String, String)] = Nil)

  /**
   * 按入队序补投全量死信。`send` = 实际上行腿（当前连接的
   * `RelayFrameSender.send(entry.frameText)` 注入，便于 spec 用纯桩驱动）。
   * **任一成功即出队**；失败的留在盘上等下一次（relay 重连 / 下一次 boot）。
   * `send` 抛异常按失败处理（一件炸不拖垮其余——继续补后面的件）。
   */
  def drain(send: Entry => IO[Unit]): IO[DrainReport] =
    pending.flatMap { entries =>
      entries.foldLeft(IO.pure(DrainReport())) { (acc, e) =>
        acc.flatMap { report =>
          send(e)
            .attempt
            .flatMap {
              case Right(_) =>
                delivered(e.id).as(report.copy(replayed = report.replayed :+ e.id))
              case Left(t) =>
                bumpAttempt(e).as(
                  report.copy(kept =
                    report.kept :+ (e.id, Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
                  )
                )
            }
        }
      }
    }

  /** 清空（运维/spec 用；正常路径绝不调用——零删除纪律）。 */
  def clear(): IO[Unit] =
    IO.blocking(if os.exists(dir) then os.remove.all(dir)).void
      .handleErrorWith(e => logger.warn(s"PortalUplinkDeadLetter.clear failed: ${e.getMessage}").void)

end PortalUplinkDeadLetter
