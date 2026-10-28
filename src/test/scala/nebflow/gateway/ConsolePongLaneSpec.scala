package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*

/**
 * ConsolePongLane（console-reconnect-stability 批 2026-10-28）回归：
 *
 *  1. 帧类判定：只有 `type=="pong"` 进旁路；textDelta/configUpdated 等帧
 *     一律留在既有 FIFO（它们的相对顺序与 overflow 政策不得改变）。
 *  2. **活性钉（核心）**：数据流已经停产出（模拟大帧阵发/bulk 占线）时，
 *     pong 仍必须出队——修前 pong 排在同一条 FIFO 之后永远等不到空位，
 *     前端 3-miss 自我逐出即「连接断开 · 正在重连」。若把旁路撤掉
 *     （pong 重新 offer 进数据队列），本用例在 3s 预算内必红。
 */
class ConsolePongLaneSpec extends CatsEffectSuite:

  private val pong = WebSocketFrame.Text("""{"type":"pong"}""")
  private val dataFrame = WebSocketFrame.Text("""{"type":"textDelta","delta":"x"}""")

  test("isPong 只认 pong 帧（其余帧不旁路）") {
    assert(ConsolePongLane.isPong(Json.obj("type" -> Json.fromString("pong"))))
    assert(!ConsolePongLane.isPong(Json.obj("type" -> Json.fromString("textDelta"))))
    assert(!ConsolePongLane.isPong(Json.obj("type" -> Json.fromString("configUpdated"))))
    assert(!ConsolePongLane.isPong(Json.obj("delta" -> Json.fromString("pong"))))
    assert(!ConsolePongLane.isPong(Json.Null))
  }

  test("数据流停产时 pong 照样出线（不被 bulk 积压饿死）") {
    for
      control <- Queue.unbounded[IO, WebSocketFrame]
      // 数据流：首发一帧后永久停产（等效于本连接 bulk 发不出去的极端形态）。
      data = Stream.emit(dataFrame) ++ Stream.eval(IO.never[WebSocketFrame])
      merged = ConsolePongLane.prioritized(data, Stream.fromQueueUnterminated(control))
      fiber <- merged.take(2).compile.toList.start
      _ <- IO.sleep(100.millis)
      _ <- control.offer(pong)
      frames <- fiber.joinWithNever.timeout(3.seconds)
    yield
      assertEquals(frames.length, 2)
      assertEquals(frames.head, dataFrame, "控制道空闲时首帧仍是数据帧")
      assertEquals(frames.last, pong, "数据流停产后 pong 必须仍能出线")
  }

  test("pong 容量常量有界（不允许无界旁路）") {
    assert(ConsolePongLane.Capacity > 0 && ConsolePongLane.Capacity <= 64)
  }

end ConsolePongLaneSpec
