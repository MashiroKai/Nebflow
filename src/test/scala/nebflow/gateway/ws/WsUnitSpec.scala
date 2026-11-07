package nebflow.gateway.ws

import nebflow.gateway.*

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.UiMessage

/**
 * 细粒度单元（ws 模块）：入站信封解析 + browsePath 帧构造 + historyPage 页指纹
 * + 单会话 turn 闸。
 *
 * 既有 `WsHubUnregisterSpec` / `PickerBrowseFrameSpec` / `ChainViewWsEntrySpec` 都
 * 起 WS 或整条 dispatch 链。这里只钉纯函数/单对象：非法 JSON 的兜底、帧字段、
 * 索引算术、闸的互斥与幂等。
 */
class WsUnitSpec extends CatsEffectSuite:

  // ── WsDispatch：入站信封 ───────────────────────────────────────────────

  test("parsedJson: invalid JSON degrades to Json.Null (never throws)"):
    assertEquals(WsDispatch.parsedJson("""{"type":"ping"}"""), Json.obj("type" -> "ping".asJson))
    assertEquals(WsDispatch.parsedJson("{not json"), Json.Null)
    assertEquals(WsDispatch.parsedJson(""), Json.Null)

  test("inboundEnvelope.sessionId: string or empty, never an exception"):
    assertEquals(WsDispatch.inboundEnvelope("""{"sessionId":"s-1"}""").sessionId, "s-1")
    assertEquals(WsDispatch.inboundEnvelope("""{"sessionId":7}""").sessionId, "")
    assertEquals(WsDispatch.inboundEnvelope("""{"type":"ping"}""").sessionId, "")
    assertEquals(WsDispatch.inboundEnvelope("garbage").sessionId, "")

  // ── WebSocketRoutes.browseFrame：目录浏览帧 ────────────────────────────

  private def withTempTree[A](f: os.Path => A): A =
    val dir = os.temp.dir(prefix = "ws-unit-")
    try
      os.makeDir(dir / "alpha")
      os.makeDir(dir / "Beta")
      os.write(dir / "plain.txt", "x")
      f(dir)
    finally os.remove.all(dir)

  test("browseFrame: dirs only, lexical order, full total, no truncation"):
    withTempTree { dir =>
      val frame = WebSocketRoutes.browseFrame(dir, "/display", "", 100)
      assertEquals(frame.hcursor.get[String]("type").toOption, Some("browseResult"))
      assertEquals(frame.hcursor.get[String]("path").toOption, Some("/display"))
      assertEquals(frame.hcursor.get[String]("query").toOption, Some(""))
      assertEquals(frame.hcursor.get[Boolean]("truncated").toOption, Some(false))
      assertEquals(frame.hcursor.get[Int]("total").toOption, Some(2))
      val names = frame.hcursor.downField("entries").as[List[Json]].toOption.map(_.flatMap(_.hcursor.get[String]("name").toOption))
      assertEquals(names, Some(List("Beta", "alpha")))
    }

  test("browseFrame: query filters case-insensitively BEFORE the cap; query is echoed verbatim"):
    withTempTree { dir =>
      val filtered = WebSocketRoutes.browseFrame(dir, "/d", "BET", 100)
      assertEquals(filtered.hcursor.get[Int]("total").toOption, Some(1))
      assertEquals(filtered.hcursor.get[String]("query").toOption, Some("BET"))

      val capped = WebSocketRoutes.browseFrame(dir, "/d", "", 1)
      assertEquals(capped.hcursor.get[Int]("total").toOption, Some(2), "total is the FULL matched count")
      assertEquals(capped.hcursor.get[Boolean]("truncated").toOption, Some(true))
      assertEquals(capped.hcursor.downField("entries").as[List[Json]].toOption.map(_.size), Some(1))
    }

  test("browseErrorFrame: same shape, empty entries, typed errorKind/error"):
    val frame = WebSocketRoutes.browseErrorFrame("/nope", "not-a-directory", "not a directory", "q")
    assertEquals(frame.hcursor.get[String]("type").toOption, Some("browseResult"))
    assertEquals(frame.hcursor.get[Int]("total").toOption, Some(0))
    assertEquals(frame.hcursor.get[Boolean]("truncated").toOption, Some(false))
    assertEquals(frame.hcursor.downField("entries").as[List[Json]].toOption.map(_.isEmpty), Some(true))
    assertEquals(frame.hcursor.get[String]("errorKind").toOption, Some("not-a-directory"))
    assertEquals(frame.hcursor.get[String]("error").toOption, Some("not a directory"))

  // ── HistoryPageFingerprint：页指纹算术 ────────────────────────────────

  test("lastTimestamp: User/Ai carry a ts, Tool rows and empty pages read 0"):
    assertEquals(HistoryPageFingerprint.lastTimestamp(Nil), 0L)
    assertEquals(HistoryPageFingerprint.lastTimestamp(List(UiMessage.User("hi", timestamp = 111L))), 111L)
    assertEquals(HistoryPageFingerprint.lastTimestamp(List(UiMessage.Ai("ok", timestamp = 222L))), 222L)
    assertEquals(HistoryPageFingerprint.lastTimestamp(List(UiMessage.Tool("t", "s"))), 0L)

  test("of: last = offset + pageLen - 1, empty page = -1, lastAt follows the tail row"):
    val empty = HistoryPageFingerprint.of(Nil, total = 0L, offset = 0L)
    assertEquals(empty.hcursor.get[Int]("last").toOption, Some(-1))
    assertEquals(empty.hcursor.get[Int]("pageLen").toOption, Some(0))
    assertEquals(empty.hcursor.get[Int]("lastAt").toOption, Some(0))

    val page = HistoryPageFingerprint.of(
      List(UiMessage.User("a", timestamp = 5L), UiMessage.Tool("l", "s")),
      total = 10L,
      offset = 3L
    )
    assertEquals(page.hcursor.get[Int]("last").toOption, Some(4))
    assertEquals(page.hcursor.get[Int]("total").toOption, Some(10))
    assertEquals(page.hcursor.get[Int]("pageLen").toOption, Some(2))
    assertEquals(page.hcursor.get[Int]("lastAt").toOption, Some(0), "tail Tool row has no ts")

  // ── TurnEndpoint：单会话同步 turn 闸 ──────────────────────────────────

  test("acquire/releaseGate: mutual exclusion, release is idempotent, slot is reusable"):
    (for
      first <- TurnEndpoint.acquireGate("s-unit")
      second <- TurnEndpoint.acquireGate("s-unit")
      _ <- TurnEndpoint.releaseGate("s-unit")
      _ <- TurnEndpoint.releaseGate("s-unit")
      third <- TurnEndpoint.acquireGate("s-unit")
      _ <- TurnEndpoint.releaseGate("s-unit")
    yield (first, second, third)).map { case (first, second, third) =>
      assertEquals(first, true)
      assertEquals(second, false, "second concurrent turn must be refused")
      assertEquals(third, true, "slot is reusable after release")
    }

end WsUnitSpec
