package nebflow.gateway

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*

/**
 * WS 面僵尸会话回收装配单点（conn-guard 家族；台账 #950 旁支 2026-10-26 presence
 * 面落地，#960 平移 /api/ws console 面共用本核）。
 *
 * 病灶与手法同源（[[PresenceRoutes.presenceWsStreams]] 批平移——**复用装配**，非逻辑
 * 复制）：half-open 僵尸（对端消失且无 FIN/RST 到达，如休眠唤醒/组网抖动/重连风暴）
 * 的收流永不终结 ⇒ finalizer（槽位回减等面内清理）永不执行 ⇒ per-ip 槽位
 * （[[ConnGuardConfig.wsPerIpCap]]，默认 64）被灌满，[[ConnGuard.checkWs]] 以
 * per-ip-cap 拒掉该 IP 的一切新连接。修复三层（口径与 presence 批一致）：
 *
 *   1. 关闭帧 / TCP 层（结构保证）：acquire 移进**会话 fs2 scope**（收流管起跑 =
 *      升级会话起跑才计槽），release 与之配对同一 scope（恰好一次）——客户端
 *      close/EOF/异常断开 ⇒ 收流终结 ⇒ 回减必达；并封死 R-1b 注记的升级期幽灵洞
 *      （response 已产出而 upgrade 会话未起跑 ⇒ finalizer 无处挂载的计数泄漏：
 *      acquire 未起跑即未发生）。
 *   2. 判死回收（half-open 兜底）：入站活跃钟（任何入站帧即触碰，判据是「线还
 *      活着」、与帧语义无关）+ tick 拍上判死——连续 `deadAfter` 无入站帧 ⇒
 *      `onDead`（审计行等面内收尾）+ `deadSig` 打点**双侧直达**：send 侧
 *      interruptWhen 终结（ember 对 send 终结写 close 帧，对端同步收场）+
 *      receive 侧自身 interruptWhen 中断 ⇒ 第 1 层的 finalizer 必达（不依赖
 *      ember 装配的 concurrently 中断方向，见 🔴 注记）。
 *   3. 对账面（计数 ↔ 实活 socket）：配对由结构保证（acquire/release 同 scope）；
 *      每次判死回收经调用方 `onDead(silentMs)` 留审计行（presence/console 同格式
 *      「conn-guard: … recycled … slot released」），per-ip 槽位随回收立即复纳
 *      ——漂移在结构上不可再积累。
 *
 * 两面共用本核，差异只在接线参数：
 *   - presence 面（引擎互联对端，[[PresenceRoutes.presenceWsStreams]]）：tick 拍
 *     兼任**既有**服务端 ping 节拍（`tickFrame = Some(ping)`，规范对端每拍必回
 *     pong ⇒ 健康入站间隔 ≤ tick）；send 侧终结走自身 sendQueue 的 None 终结符
 *     （onDead 内收口），deadSig 只服务 receive 侧。
 *   - console 面（浏览器面板，[[WebSocketRoutes]] `/api/ws`）：客户端自带 30s
 *     应用层心跳（web/js/ws.js `HB_INTERVAL_MS`，服务端 pong 应答早已存在于
 *     [[WsSystemHandlers.handlePing]]）⇒ tickFrame=None（服务端补发文本 ping
 *     对浏览器无应答面，纯浪费）；send 侧终结 = 自身 interruptWhen(deadSig)
 *     （与既有 overflow 拆线同款，前端既有重连+resync 腿接住）。
 */
object WsZombieGuard:

  /** 判死参数（两面单一真源）：`tick`=判死拍；`deadAfter`=连续无入站帧判死阈值。 */
  final case class Liveness(tick: FiniteDuration = 10.seconds, deadAfter: FiniteDuration = 30.seconds)

  /**
   * console /api/ws 面口径：`deadAfter=90s = 3 × 客户端自 ping 间隔`（ws.js
   * `HB_INTERVAL_MS=30s`，可见标签页每 30s 一声 ping）——容忍连续两拍 ping 丢失
   * （网络抖动/GC 停顿），第三拍仍无任何入站帧才判死；tick=10s 判死拍 ⇒ 回收
   * 时延 90–100s 带。后台标签页（visibility hidden 时 ws.js 停拍）在静默 90s 后
   * 一并回收：唤醒侧 ws.js `checkConnection()`（CLOSED/CLOSING ⇒ forceReconnect，
   * OPEN ⇒ ping/pong wake-probe）已有重连腿兜底——回收换槽位释放正是本批题意
   * （隐藏半开连接不清 ⇒ 槽位照灌不误）。
   */
  val ConsoleLiveness: Liveness = Liveness(tick = 10.seconds, deadAfter = 90.seconds)

  /**
   * 单会话装配体（[[open]] 产物）：入站活跃钟 + 判死信号 + 两面共用的收发接线。
   * 🔴 方向注记（presence 批 fs2 3.11 实测钉死）：`concurrently` 的后台流终结
   * **不**中断前台——判死必须凭 `deadSig` 各自直达两侧，不依赖 ember 的
   * readSide.concurrently(sendSide) 对 send 终结的处置方向。
   */
  final class Session private[WsZombieGuard] (
    lastInbound: Ref[IO, Long],
    liveness: Liveness,
    /** 判死信号：完成即双侧收口（send 侧 interruptWhen 终结、receive 侧中断）。 */
    val deadSig: Deferred[IO, Unit],
    /** 入站活跃钟触碰（任何入站帧即触碰；判据是「线还活着」，与帧语义无关）。 */
    val touch: IO[Unit]
  ):

    /**
     * 判死腿（挂 tick 拍）：活跃 ⇒ 产出 `tickFrame`（空 Seq = 本面本拍不发帧）；
     * 判死 ⇒ `onDead(silentMs)`（审计行等面内收尾；错误吞掉不阻收口）+ deadSig
     * 打点，**本腿随即终结**。零新增帧语义：tick 拍是否兼发心跳帧由 `tickFrame`
     * 决定（presence=Some(ping)，console=None）。
     *
     * 🔴 终结形态（B2 探针钉死）：判死收尾经 `None` 标记 + `unNoneTerminate` 收腿
     * ——不能用裸 `unNone`（那是 collect 语义，None 被丢、本腿带死不僵 ⇒ merge
     * 永不收口，console send 侧凭 deadSig 自终结的腿就悬空了）；也不能用
     * 「healthy 发 None」表达本拍不发帧（会被 unNoneTerminate 误杀）。故 healthy
     * 包 `Some(Seq(0..1 帧))`、判死发 `None`，两种含义不同型。
     */
    def heartbeat(tickFrame: Option[WebSocketFrame])(onDead: Long => IO[Unit]): Stream[IO, WebSocketFrame] =
      Stream
        .awakeEvery[IO](liveness.tick)
        .evalMap { _ =>
          (IO.realTime.map(_.toMillis), lastInbound.get).mapN { (nowMs, lastMs) =>
            val silentMs = nowMs - lastMs
            if silentMs >= liveness.deadAfter.toMillis then Left(silentMs) else Right(())
          }
        }
        .evalMap {
          case Right(_) => IO.pure(Some(tickFrame.toSeq)) // healthy：0 或 1 帧
          case Left(silentMs) =>
            (onDead(silentMs).handleErrorWith(_ => IO.unit) *> deadSig.complete(())).as(None) // dead：终结标记
        }
        .unNoneTerminate
        .flatMap(Stream.emits(_))

    /**
     * console 面发送侧装配：既有 bounded 队列流 + overflow 中断（既有拆线道）
     * + 判死中断（send 侧直达）+ 判死腿挂同一会话作用域（连接在则拍在、连接亡
     * 则拍亡——判死计时面与连接面同生共死，零独立计时漂移）。`raw` 为调用方
     * 已组装的队列流（含错误面）；判死时 merge 的两个成员一个被 deadSig 终结、
     * 一个自行终结，整体收口 ⇒ ember 写 close 帧。
     */
    def guardedSend(
      raw: Stream[IO, WebSocketFrame],
      overflowSig: Deferred[IO, Unit]
    )(onDead: Long => IO[Unit]): Stream[IO, WebSocketFrame] =
      raw
        .interruptWhen(overflowSig.get.attempt)
        .interruptWhen(deadSig.get.attempt)
        .merge(heartbeat(None)(onDead))

    /**
     * 会话作用域收流装配（幽灵计数洞的结构封死，两面同款）：acquire 进会话
     * fs2 scope（收流管起跑才计槽；升级未起跑 ⇒ acquire 未发生 ⇒ 零幽灵计数），
     * release 同 scope 配对恰好一次（close/EOF/异常断开/判死中断四途同归）；
     * 任何入站帧先触碰活跃钟再交 `base`（面内协议处理与面内 finalizer 各自保留）；
     * deadSig 完成即中断（正常关闭时收流自然终结，此层零干预）。
     */
    def guardedReceive[A](
      acquire: IO[A],
      release: A => IO[Unit]
    )(base: Pipe[IO, WebSocketFrame, Unit]): Pipe[IO, WebSocketFrame, Unit] =
      frames =>
        Stream
          .bracket(acquire <* touch)(release)
          .flatMap(_ => frames.evalTap(_ => touch).through(base))
          .interruptWhen(deadSig.get.attempt)

  /** 开一条会话装配体：活跃钟起点 = 调用时刻（会话受理时刻）。 */
  def open(liveness: Liveness): IO[Session] =
    IO.realTime.map(_.toMillis).flatMap { startedMs =>
      for
        lastInbound <- Ref.of[IO, Long](startedMs)
        deadSig <- Deferred[IO, Unit]
        touch = IO.realTime.map(_.toMillis).flatMap(lastInbound.set)
      yield new Session(lastInbound, liveness, deadSig, touch)
    }
end WsZombieGuard
