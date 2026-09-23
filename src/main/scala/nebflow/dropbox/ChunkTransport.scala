package nebflow.dropbox

import cats.effect.IO
import cats.syntax.all.*

import scala.concurrent.duration.*

/**
 * 分块传输的**传输层缝** —— P2P 主腿 / relay 兜底腿 / 测试自环三者共用同一契约。
 *
 * WHY 缝：分块循环（读盘 → 逐块摘要 → 发送 → 重试 → 换腿 → 续传）是纯编排逻辑，
 * 与「字节怎么过网」正交。把它抽出后，编排逻辑可在**真实文件 IO + 真实摘要**下自环验证，
 * 不需要设备面对端（今天 peers 名册为空）。自环 = 非端到端，结论须如此标注。
 */
trait ChunkTransport:
  /** 传输一条腿的名字（"p2p" | "relay" | "harness"），用于错误体归因。 */
  def leg: String

  /** 送一块，取回执。失败必须自描述（不变量 I3）。 */
  def put(
    target: FileTransfer,
    frame: ChunkedTransfer.ChunkFrame,
    payload: Array[Byte]
  ): IO[Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck]]

  /** 续传探针：问对端当前 offset 与其自算的前缀摘要。 */
  def probe(target: FileTransfer): IO[Either[AttachContract.AttachError, ChunkedTransfer.ReceiveState]]

object ChunkTransport:

  /**
   * 两腿**共面**（作者排期口径：Dropbox 流式腿为底座 + relay 兜底分块）。
   * 逐块先走 primary，burn 完重试额度后**换腿**；换腿时 **offset 保持不变**（续传语义，
   * 不是重传整件）。两腿均失败 ⇒ `PEER_UNREACHABLE`，错误体**同时**携带两条腿的原因
   * （今天的 `"P2P and relay both failed. Relay: $err"` 丢弃 P2P 原因是已知缺口，禁复现）。
   */
  def failover(primary: ChunkTransport, fallback: ChunkTransport): ChunkTransport =
    new ChunkTransport:
      /**
       * 真实承载腿（P-13 观测缺口）：最近一次**成功**的腿。"p2p+relay" 只在两条腿都还没
       * 成功过时出现（= 「不知道」的显式表示，不再是一个恒真的标签）。
       */
      private val winner = new java.util.concurrent.atomic.AtomicReference[String](null)

      def leg: String = Option(winner.get()).getOrElse("p2p+relay")

      def put(
        target: FileTransfer,
        frame: ChunkedTransfer.ChunkFrame,
        payload: Array[Byte]
      ): IO[Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck]] =
        primary.put(target, frame, payload).flatMap {
          case r @ Right(_) => IO { winner.set(primary.leg) }.as(r)
          case Left(p2pErr) =>
            fallback.put(target, frame, payload).map {
              case r @ Right(_) =>
                winner.set(fallback.leg)
                r
              case Left(relayErr) => Left(peerUnreachable(p2pErr, relayErr))
            }
        }

      def probe(target: FileTransfer): IO[Either[AttachContract.AttachError, ChunkedTransfer.ReceiveState]] =
        primary.probe(target).flatMap {
          case r @ Right(_) => IO { winner.set(primary.leg) }.as(r)
          case Left(p2pErr) =>
            fallback.probe(target).map {
              case r @ Right(_) =>
                winner.set(fallback.leg)
                r
              case Left(relayErr) => Left(peerUnreachable(p2pErr, relayErr))
            }
        }

  /** 两腿均失败：错误体带 `p2pReason` 与 `relayReason` 两个字段（契约 §3.7）。 */
  def peerUnreachable(
    p2pErr: AttachContract.AttachError,
    relayErr: AttachContract.AttachError
  ): AttachContract.AttachError =
    AttachContract.AttachError(
      AttachContract.Codes.PeerUnreachable,
      s"Peer unreachable on both legs — p2p: ${p2pErr.render} | relay: ${relayErr.render}",
      phase = "transfer",
      p2pReason = Some(p2pErr.render),
      relayReason = Some(relayErr.render)
    )

end ChunkTransport

/**
 * 分块发送编排（传输无关）。
 *
 * 逐块**串行**（ack 即背压信号，契约 §3.6），有界内存（≤1 块，不变量 I4）。
 * 每块发送后做**双侧校验**：
 *   ① 对端回执里的块摘要必须等于本地算出的块摘要（对端自算，禁自证）；
 *   ② 末块回执必须带对端**自算**的整件摘要，且等于发送端单遍算出的整件摘要（不变量 I2）。
 */
object ChunkedSendLoop:

  final case class Outcome(bytesSent: Long, receiverComputedSha256: String, leg: String, chunksSent: Int)

  /** 建议值（非冻结口径）：每块每腿 3 次、指数退避 1s/2s/4s；备选 2 次固定 2s / 5 次上限 30s。 */
  val DefaultRetriesPerLeg: Int = 3

  /**
   * 在飞字节上报节拍（建议值 2 s，照方案卡 P0-4；改前 = 一整块落地才报一次，
   * 4 MiB 块在中继腿要 ≥140 s ⇒ 几分钟零反馈）。
   */
  val DefaultProgressCadence: FiniteDuration = 2.seconds

  def defaultBackoff(attempt: Int): FiniteDuration =
    math.min(1L << math.max(0, attempt), 8L).seconds

  /**
   * 跑完一个传输。
   *
   * @param chunkSize  会话冻结的分块大小（契约 §3.1：块大小在会话内恒定，由 offer 协商）。
   *                   发送与接收必须用**同一个**值 —— 两边各用各的默认值会让
   *                   `chunkIndex × chunkSize` 推导出的 offset 对不上（自环测试实测命中）。
   * @param openSender 给定 startOffset 打开发送游标（续传时由探针结果驱动）
   * @param onProgress 块级确认回调（块 index, 接收端权威已确认字节）—— 语义不变。
   * @param onInFlight **在飞**节拍回调（已确认字节, 在飞字节）= (acked, inFlight)；
   *                   纪律：在飞值与「已确认」**分字段**（P0-4），禁混进 `bytesReceived`。
   * @param onRate     实测速率回调（bytes/s，按**成功块**的数据时间算；重试/退避计入分母
   *                   ⇒ 偏向保守）。DropboxService 用它收敛 peer 速率记忆（下次会话的块参）。
   */
  def run(
    transport: ChunkTransport,
    target: FileTransfer,
    source: os.Path,
    chunkSize: Int = AttachContract.ChunkSize,
    retriesPerLeg: Int = DefaultRetriesPerLeg,
    backoff: Int => FiniteDuration = defaultBackoff,
    onProgress: (Int, Long) => IO[Unit] = (_, _) => IO.unit,
    onInFlight: (Long, Long) => IO[Unit] = (_, _) => IO.unit,
    progressCadence: FiniteDuration = DefaultProgressCadence,
    onRate: Long => IO[Unit] = _ => IO.unit
  ): IO[Either[AttachContract.AttachError, Outcome]] =
    // ① 续传探针：权威 offset 在接收端，发送端不得假设。
    transport.probe(target).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(state) =>
        val startOffset = math.min(state.bytesReceived, os.size(source))
        ChunkSender.open(source, target.transferId, startOffset, chunkSize).flatMap { sender =>
          // ② 在飞节拍（P0-4）：整个 run 期间每 `progressCadence` 报一次
          //    「已确认 / 在飞」两个值 —— 块上传本身是阻塞 IO，故节拍跑在独立 fiber 上，
          //    异常一律吞掉（上报失败绝不打断传输），收口 `guarantee` 取消。
          //
          //    🔴 在飞的**定义**（否则本字段恒 0 = 假装报了）：`sender.bytesSent` 只在**块
          //    ack 之后**推进，块在途的整段时间里它仍等于已确认水位 ⇒ 用它算在飞永远是 0。
          //    故在飞取「**本次尝试的块尾** − 已确认水位」：块发出前把 `attemptEnd` 置为
          //    `offset + bytes`（重试不改，同一块），ack 后两者相等 ⇒ 自动归 0。
          //    这是本端**可观测**的粒度（阻塞式 put 看不到 socket 内部的半块进度），
          //    故按块算、不假装按字节算。
          val acked = new java.util.concurrent.atomic.AtomicLong(sender.bytesSent)
          val attemptEnd = new java.util.concurrent.atomic.AtomicLong(sender.bytesSent)
          //    🔴 `IO.defer` 不是装饰：`*>` 的右侧是**按值**求值的（`productR(that)`），写
          //    `IO.sleep(c) *> { ... }` 会让块在**构造时**只求值一次 —— 于是 `acked`/`attemptEnd`
          //    的读数被冻在传输开始前（恒 0），节拍每次重复执行的都是同一个「(0,0)」的 IO。
          //    实测形态：6 次节拍全部报 (0,0)，而此刻块正在途（P0-4 等于没报）。`IO.defer`
          //    把读数推迟到**每次执行**，节拍才是活的。
          val ticker =
            (IO.sleep(progressCadence) *> IO.defer {
              val a = acked.get()
              onInFlight(a, math.max(0L, attemptEnd.get() - a))
            })
              .handleErrorWith(_ => IO.unit)
              .foreverM
          ticker.start.flatMap { fiber =>
            loop(transport, target, sender, acked, attemptEnd, retriesPerLeg, backoff, onProgress, onRate, 0)
              .guarantee(fiber.cancel)
          }
        }
    }

  private def loop(
    transport: ChunkTransport,
    target: FileTransfer,
    sender: ChunkSender,
    ackedRef: java.util.concurrent.atomic.AtomicLong,
    attemptEndRef: java.util.concurrent.atomic.AtomicLong,
    retriesPerLeg: Int,
    backoff: Int => FiniteDuration,
    onProgress: (Int, Long) => IO[Unit],
    onRate: Long => IO[Unit],
    chunksSent: Int
  ): IO[Either[AttachContract.AttachError, Outcome]] =
    sender.readNext().flatMap {
      case None =>
        // 空文件（totalBytes == 0）：无块可发，直接以整件摘要收口。
        if sender.bytesSent <= 0L then
          IO.pure(Right(Outcome(sender.bytesSent, sender.wholeSha256, transport.leg, chunksSent)))
        else
          // 🔴 续传补验（P0-2）：本次**一块都没发**却有非零 offset ⇒ 对端 temp 已含整件
          // （上一次尝试的最后一块 ack 丢了 / 进程在 commit 前重启）。此时不得凭发送端
          // 自算摘要宣布成功（不变量 I2 的反面），必须用**接收端重算**的前缀摘要比对 ——
          // 探针的 `prefixSha256` 恰是 temp[0, bytesReceived) 的重算值，offset 到底时即整件。
          transport.probe(target).flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(state) =>
              val receiverWhole = state.prefixSha256
              if receiverWhole.nonEmpty && receiverWhole == sender.wholeSha256 then
                IO.pure(Right(Outcome(sender.bytesSent, receiverWhole, transport.leg, chunksSent)))
              else
                IO.pure(
                  Left(
                    AttachContract.AttachError(
                      AttachContract.Codes.WholeDigestMismatch,
                      s"Resume reports ${state.bytesReceived} bytes already landed but the receiver's recomputed " +
                        s"digest does not match the sender's whole-file digest — refusing to call the transfer complete",
                      phase = "commit",
                      bytesReceived = Some(state.bytesReceived),
                      expected = Some(sender.wholeSha256),
                      actualHash = Some(receiverWhole)
                    )
                  )
                )
              end if
          }
      case Some((frame, payload)) =>
        val startedAt = System.nanoTime()
        // 在飞水位（P0-4）：本块**出发前**先把块尾写进 `attemptEndRef` ⇒ 节拍在块在途的
        // 时间内能读到「已确认 < 已发出」的差（ack 后两者相等，自动归 0）。
        attemptEndRef.set(frame.offset + frame.bytes)
        sendWithRetry(transport, target, frame, payload, retriesPerLeg, backoff).flatMap {
          case Left(err) => IO.pure(Left(err))
          case Right(ack) =>
            val elapsedMillis = math.max(1L, (System.nanoTime() - startedAt) / 1000000L)
            val measuredRate = math.max(0L, payload.length.toLong * 1000L / elapsedMillis)
            ack.chunkSha256 == frame.chunkSha256 match
              case false =>
                IO.pure(
                  Left(
                    AttachContract.AttachError(
                      AttachContract.Codes.ChunkDigestMismatch,
                      s"Peer ack for chunk ${frame.chunkIndex} reports a different digest than the sender computed",
                      phase = "transfer",
                      chunkIndex = Some(frame.chunkIndex),
                      expected = Some(frame.chunkSha256),
                      actualHash = Some(ack.chunkSha256)
                    )
                  )
                )
              case true =>
                ackedRef.set(ack.bytesReceived)
                onRate(measuredRate) *> onProgress(frame.chunkIndex, ack.bytesReceived) *> {
                  if ack.bytesReceived >= frame.totalBytes then
                    // 末块：接收端**自算**的整件摘要必须等于发送端单遍算出的整件摘要（I2）。
                    // 注意 senderWhole 必须在**读完末块之后**取（digest 此时才覆盖整件）。
                    val senderWhole = sender.wholeSha256
                    ack.wholeSha256 match
                      case None =>
                        IO.pure(
                          Left(
                            AttachContract.AttachError(
                              AttachContract.Codes.WholeDigestMismatch,
                              s"Peer completed the transfer without returning its self-computed whole-file digest (chunk ${frame.chunkIndex})",
                              phase = "commit",
                              chunkIndex = Some(frame.chunkIndex),
                              bytesReceived = Some(ack.bytesReceived)
                            )
                          )
                        )
                      case Some(receiverWhole) =>
                        ChunkedTransfer.verifyWholeDigest(senderWhole, receiverWhole, ack.bytesReceived) match
                          case Left(err) => IO.pure(Left(err))
                          case Right(_) =>
                            IO.pure(Right(Outcome(ack.bytesReceived, receiverWhole, transport.leg, chunksSent + 1)))
                    end match
                  else
                    loop(
                      transport,
                      target,
                      sender,
                      ackedRef,
                      attemptEndRef,
                      retriesPerLeg,
                      backoff,
                      onProgress,
                      onRate,
                      chunksSent + 1
                    )
                }
            end match
        }
    }

  /** 单块重试（同一腿内 burn 完额度）。幂等键 = (transferId, chunkIndex) ⇒ 重试不重置 offset。 */
  private def sendWithRetry(
    transport: ChunkTransport,
    target: FileTransfer,
    frame: ChunkedTransfer.ChunkFrame,
    payload: Array[Byte],
    retriesLeft: Int,
    backoff: Int => FiniteDuration,
    attempt: Int = 0
  ): IO[Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck]] =
    transport.put(target, frame, payload).flatMap {
      case r @ Right(_) => IO.pure(r)
      case Left(err) if !isRetryable(err) => IO.pure(Left(err))
      case Left(err) if retriesLeft <= 0 => IO.pure(Left(err))
      case Left(_) =>
        IO.sleep(backoff(attempt)) *> sendWithRetry(
          transport,
          target,
          frame,
          payload,
          retriesLeft - 1,
          backoff,
          attempt + 1
        )
    }

  /** 不可重试项（契约 §3.7）：立即失败，不消耗重试额度。 */
  def isRetryable(err: AttachContract.AttachError): Boolean =
    !Set(
      AttachContract.Codes.AttachTooLarge,
      AttachContract.Codes.AttachTooMany,
      AttachContract.Codes.WholeDigestMismatch,
      AttachContract.Codes.UnsupportedProtocol,
      AttachContract.Codes.QueueFull,
      AttachContract.Codes.InvalidArgument
    ).contains(err.code)

end ChunkedSendLoop
