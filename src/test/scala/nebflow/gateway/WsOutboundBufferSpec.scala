package nebflow.gateway

import munit.CatsEffectSuite
import org.http4s.websocket.WebSocketFrame

class WsOutboundBufferSpec extends CatsEffectSuite:
  test("满队时新流帧只淘汰最旧流帧，不丢队首终态") {
    val terminal = WebSocketFrame.Text("done")
    val old = WebSocketFrame.Text("old delta")
    val fresh = WebSocketFrame.Text("new delta")
    for
      buffer <- WsOutboundBuffer.create(2)
      _ <- buffer.enqueue(terminal, false)
      _ <- buffer.enqueue(old, true)
      result <- buffer.enqueue(fresh, true)
      first <- buffer.take
      second <- buffer.take
    yield
      assertEquals(result, WsOutboundBuffer.Evicted)
      assertEquals(List(first, second), List(terminal, fresh))
  }

  test("全非流帧满时两种新帧都关闭且保留原队列") {
    val first = WebSocketFrame.Text("error")
    val second = WebSocketFrame.Text("done")
    for
      buffer <- WsOutboundBuffer.create(2)
      _ <- buffer.enqueue(first, false)
      _ <- buffer.enqueue(second, false)
      streamResult <- buffer.enqueue(WebSocketFrame.Text("delta"), true)
      controlResult <- buffer.enqueue(WebSocketFrame.Text("tool"), false)
      frames <- buffer.take.flatMap(a => buffer.take.map(b => List(a, b)))
    yield
      assertEquals(streamResult, WsOutboundBuffer.Full)
      assertEquals(controlResult, WsOutboundBuffer.Full)
      assertEquals(frames, List(first, second))
  }
