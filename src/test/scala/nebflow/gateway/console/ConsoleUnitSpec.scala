package nebflow.gateway.console

import nebflow.gateway.*

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.websocket.WebSocketFrame

/**
 * 细粒度单元（console 模块）：pong 旁路道判据 + 接管协议帧形态 + 本地控制台
 * URL 的前置条件。
 *
 * 既有 `ConsolePongLaneSpec` / `ConsoleTakeoverSpec` / `LocalConsoleUrlSpec` 都从
 * WS 会话或真实本地回环起手（整链）。这里只钉每个单元自己的输入/输出：帧判别、
 * 帧字面、require 边界，毫秒级、零端口。
 */
class ConsoleUnitSpec extends CatsEffectSuite:

  // ── ConsolePongLane：只有 pong 进旁路 ─────────────────────────────────

  test("isPong: exactly {\"type\":\"pong\"} enters the bypass lane"):
    assert(ConsolePongLane.isPong(Json.obj("type" -> "pong".asJson)))
    assert(!ConsolePongLane.isPong(Json.obj("type" -> "ping".asJson)))
    assert(!ConsolePongLane.isPong(Json.obj("type" -> "configUpdated".asJson)))
    assert(!ConsolePongLane.isPong(Json.obj("type" -> 1.asJson)), "non-string type")
    assert(!ConsolePongLane.isPong(Json.arr()), "non-object frame")

  test("prioritized: merge keeps every frame of both lanes (no data loss, no reorder inside a lane)"):
    val data = Stream.emits(
      List(WebSocketFrame.Text("a"), WebSocketFrame.Text("b"), WebSocketFrame.Text("c"))
    )
    val control = Stream.emits(List(WebSocketFrame.Text("pong")))
    ConsolePongLane.prioritized(data, control).compile.toList.map { frames =>
      assertEquals(frames.map(f => new String(f.data.toArray)).sorted, List("a", "b", "c", "pong"))
    }

  // ── ConsoleTakeover：线协议字面 ────────────────────────────────────────

  test("isRetakeFrame: only a JSON object with type=takeoverRetake is a retake"):
    assert(ConsoleTakeover.isRetakeFrame("""{"type":"takeoverRetake"}"""))
    assert(!ConsoleTakeover.isRetakeFrame("""{"type":"takeover"}"""))
    assert(!ConsoleTakeover.isRetakeFrame("""{"type":1}"""))
    assert(!ConsoleTakeover.isRetakeFrame("not json"))
    assert(!ConsoleTakeover.isRetakeFrame("""{"type":"takeoverRetake" """ ), "truncated JSON")

  test("takeover frames carry their frozen wire shape"):
    assertEquals(ConsoleTakeover.takeoverFrame(42L).noSpaces, """{"type":"takeover","at":42}""")
    assertEquals(ConsoleTakeover.resultFrame(true).noSpaces, """{"type":"takeoverResult","ok":true}""")
    assertEquals(ConsoleTakeover.resultFrame(false).noSpaces, """{"type":"takeoverResult","ok":false}""")

  test("PastOwnerCap / EnvName are the documented constants"):
    assertEquals(ConsoleTakeover.PastOwnerCap, 64)
    assertEquals(ConsoleTakeover.EnvName, "CONSOLE_SINGLE_INSTANCE")

  // ── LocalConsoleUrl：issue 的前置条件（不触网） ────────────────────────

  test("issue: refuses non-loopback hosts, non-http schemes and query-bearing URLs before any IO"):
    intercept[IllegalArgumentException](LocalConsoleUrl.issue("https://example.com", "t"))
    intercept[IllegalArgumentException](LocalConsoleUrl.issue("http://192.168.1.10:6138", "t"))
    intercept[IllegalArgumentException](LocalConsoleUrl.issue("http://127.0.0.1:6138/?a=1", "t"))
    intercept[IllegalArgumentException](LocalConsoleUrl.issue("ftp://127.0.0.1:6138", "t"))

end ConsoleUnitSpec
