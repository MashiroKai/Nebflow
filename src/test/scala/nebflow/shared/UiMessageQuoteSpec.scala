package nebflow.shared

import io.circe.Json
import io.circe.syntax.*
import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.SessionStore

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * quote-message 一期（2026-10-09，spec §3.3/§3.7）— `UiMessage.User.quote` 的
 * 编解码 + 落盘 round-trip 契约：
 *
 *  ① 缺席即不落键：无引用行的 Encoder 输出与旧 `.ui.json` 行字节形态**逐字相同**
 *     （键集 = type/text/attachments [+timestamp]）—— 旧行可解析性零变化；
 *  ② 带 quote 行 round-trip：encode → decode → 同一 quote Json（引擎不解释内部
 *     字段，原样落盘原样下发）；
 *  ③ 旧行（无 quote 键）decode ⇒ None；
 *  ④ SessionStore.appendUiMessages → getHistoryPage round-trip：quote 随历史分页
 *     原样回来（前端历史重渲的数据面）。
 */
class UiMessageQuoteSpec extends CatsEffectSuite:

  private def quoteJson: Json =
    Json.obj(
      "refMessageId" -> "h3-ab12".asJson,
      "excerpt" -> "帮我把这段结论并进周报".asJson,
      "senderRole" -> "user".asJson,
      "senderName" -> "我".asJson,
      "ts" -> 1760000000000L.asJson
    )

  test("quote-less User row encodes WITHOUT the quote key (old byte form intact)"):
    val encoded = (UiMessage.User("plain text", timestamp = 100L): UiMessage).asJson
    val keys = encoded.asObject.map(_.keys.toList).getOrElse(Nil)
    assertEquals(keys.toSet, Set("type", "text", "attachments", "timestamp"))
    assert(!keys.contains("quote"), "缺席 quote 绝不落键（旧 .ui.json 行字节形态逐字不变）")
    // 全量比对：编码结果 == 改前形态（逐键）
    assertEquals(
      encoded,
      Json.obj(
        "type" -> "user".asJson,
        "text" -> "plain text".asJson,
        "attachments" -> Json.arr(),
        "timestamp" -> 100L.asJson
      )
    )

  test("User row with quote round-trips encode → decode with the same quote payload"):
    val row = UiMessage.User("带引用发送", timestamp = 200L, quote = Some(quoteJson))
    val decoded = (row: UiMessage).asJson.as[UiMessage].toOption.get
    val u = decoded.asInstanceOf[UiMessage.User]
    assertEquals(u.text, "带引用发送")
    assertEquals(u.quote, Some(quoteJson))

  test("quote carries the raw payload verbatim (engine does not interpret inner fields)"):
    val exotic = Json.obj("refMessageId" -> "x".asJson, "custom" -> Json.arr("a".asJson, 1.asJson))
    val decoded = (UiMessage
      .User("t", quote = Some(exotic)): UiMessage).asJson.as[UiMessage].toOption.get.asInstanceOf[UiMessage.User]
    assertEquals(decoded.quote, Some(exotic))

  test("legacy .ui.json row without a quote key decodes with quote=None"):
    val legacy = Json.obj(
      "type" -> "user".asJson,
      "text" -> "old row".asJson,
      "attachments" -> Json.arr(),
      "timestamp" -> 42L.asJson
    )
    val decoded = legacy.as[UiMessage].toOption.get.asInstanceOf[UiMessage.User]
    assertEquals(decoded.quote, None)
    assertEquals(decoded.text, "old row")

  test("ai rows never carry quote (only the User variant has the field)"):
    val encoded = (UiMessage.Ai("answer", timestamp = 7L): UiMessage).asJson
    assert(encoded.asObject.map(_.keys.toList).getOrElse(Nil).forall(_ != "quote"))

  // ===== SessionStore 落盘 round-trip（appendUiMessages → getHistoryPage）=====

  private def withStore(test: SessionStore => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-quote-test")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val tasksDir = os.Path(tmp.resolve("tasks"))
    try
      val store = SessionStore(sessionsDir, tasksDir)
      store.load.unsafeRunSync()
      test(store).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  test("quote survives appendUiMessages → getHistoryPage verbatim"):
    withStore { store =>
      val sid = "quote-roundtrip-1"
      val row = UiMessage.User("带引用发送", timestamp = 300L, quote = Some(quoteJson))
      for
        _ <- store.createSession("quote rt")
        _ <- store.appendUiMessages(sid, List(row))
        (page, total, _, _) <- store.getHistoryPage(sid, limit = 10, beforeIndex = None)
      yield
        assertEquals(total, 1)
        val u = page.head.asInstanceOf[UiMessage.User]
        assertEquals(u.text, "带引用发送")
        assertEquals(u.quote, Some(quoteJson))
    }

  test("quote-less rows round-trip with quote absent (old rows byte-form intact)"):
    withStore { store =>
      val sid = "quote-roundtrip-2"
      for
        _ <- store.createSession("quote rt 2")
        _ <- store.appendUiMessages(sid, List(UiMessage.User("无引用", timestamp = 400L)))
        (page, _, _, _) <- store.getHistoryPage(sid, limit = 10, beforeIndex = None)
      yield
        val u = page.head.asInstanceOf[UiMessage.User]
        assertEquals(u.text, "无引用")
        assertEquals(u.quote, None)
        // 落盘行的编码键集不含 quote（缺席即不落键的最直读数面）
        val keys = (u: UiMessage).asJson.asObject.map(_.keys.toList).getOrElse(Nil)
        assert(!keys.contains("quote"))
    }
end UiMessageQuoteSpec
