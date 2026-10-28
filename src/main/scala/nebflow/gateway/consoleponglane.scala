package nebflow.gateway

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import org.http4s.websocket.WebSocketFrame

/**
 * Console 心跳 pong 优先道（console-reconnect-stability 批 · 2026-10-28）。
 *
 * 病灶：客户端 30s 应用层 ping 的 pong 应答此前与**本连接全部数据帧共用一条
 * FIFO**（`WebSocketRoutes.perConnWsSend` → bounded outbound）。大帧阵发
 * （historyPage 补页、大 toolResult、图片字节）期间，pong 排在本周期 bulk 之后
 * ——出站队列容量 1024 远未打满（`overflowDrops=0 / overflowCloses=0` 不代表
 * 无积压），前端 `hbProbe` 连续 3 次 miss 即自我逐出 ⇒ 顶栏
 * 「连接断开 · 正在重连」。门户腿同一 pong 随后还要过 relay 上行队列与
 * link-server 下行队列，积压进一步放大（HealthRoutes 既有注记
 * 「隧道把心跳 pong 饿死 ⇒ 前端 3-miss 自我逐出」同案）。
 *
 * 修法 = 只给 pong 一条旁路：pong 走独立小队列，发送侧与既有数据流
 * **并发 merge**——pong 不再等本连接 bulk 排空。语义安全边界：
 *   · pong 是对客户端 ping 的独立应答，与任何数据帧无顺序契约（ws.js 只看
 *     `type=="pong"` 清 `pendingPong`；提前于 delta/补页到达零副作用）；
 *   · 只旁路 `{"type":"pong"}` 一类帧，其余控制/状态帧（configUpdated 等）
 *     仍走既有 FIFO + overflow 拆线政策，**帧间相对顺序不变**；
 *   · 旁路容量 [[Capacity]] 有界（客户端每 30s 最多一声 ping，16 深度即
 *     8 分钟积压余量）；道满回落既有 FIFO 路径，任何情况下不劣于修前。
 */
object ConsolePongLane:

  /** 旁路队列容量（有界红线：不允许第二条无界出站通道）。 */
  val Capacity: Int = 16

  /** 帧类判定单点：仅 `type=="pong"` 进旁路（handlePing 唯一产出面）。 */
  def isPong(json: Json): Boolean =
    json.hcursor.downField("type").as[String].contains("pong")

  /**
   * 发送侧装配：控制流（pong）与数据流并发 merge。pong 道空闲时零开销、零
   * 干预；pong 到达时不必等数据流产出下一帧（merge 的两侧各自拉取）。
   * 生命周期与既有数据流一致——都由外层 `WsZombieGuard.guardedSend` 的
   * overflow/判死中断与同会话 scope 收口，本件不新增终结语义。
   */
  def prioritized(
    data: Stream[IO, WebSocketFrame],
    control: Stream[IO, WebSocketFrame]
  ): Stream[IO, WebSocketFrame] =
    control.merge(data)

end ConsolePongLane
