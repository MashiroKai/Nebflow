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
 * file-diff → 委托结果行（bug #58 修，2026-10-09）— `UiMessage.User.fileChanges`
 * 的编解码 + 落盘 round-trip 契约（`UiMessageQuoteSpec` 同族镜像）：
 *
 *  ① 缺席即不落键：无载荷行的 Encoder 输出与旧 `.ui.json` 行字节形态**逐字相同**
 *     （键集不含 `fileChanges`）—— 旧行可解析性零变化；
 *  ② 带载荷行 round-trip：encode → decode → 同一 diff Json（引擎不解释内部字段，
 *     原样落盘原样下发）；
 *  ③ 旧行（无 `fileChanges` 键）decode ⇒ None；
 *  ④ SessionStore.appendUiMessages → getHistoryPage round-trip：载荷随历史分页
 *     原样回来（前端历史重渲的数据面 = 聊天产物卡展开即 diff 视图）。
 */
class UiMessageFileChangesSpec extends CatsEffectSuite:

  private def diffPayload: Json =
    Json.obj(
      "nodeId" -> "delegate-nebflow-539d2db2".asJson,
      "generatedAt" -> 1791514151292L.asJson,
      "root" -> "/tmp/nebflow-coldstart/general".asJson,
      "files" -> List(
        Json.obj(
          "path" -> "src/main/resources/web/js/msgQuote.js".asJson,
          "changeType" -> "added".asJson,
          "added" -> 120.asJson,
          "removed" -> 0.asJson,
          "source" -> "tool-ledger".asJson,
          "diffText" -> "@@ -0,0 +1,2 @@\n+export function q() {}\n+// end".asJson
        )
      ).asJson
    )

  test("payload-less User row encodes WITHOUT the fileChanges key (old byte form intact)"):
    val encoded = (UiMessage.User(
      "delegate result",
      injected = true,
      timestamp = 100L,
      source = Some("delegate"),
      eventType = Some("completed"),
      header = Some("DELEGATE · NEBULA · NEBFLOW · COMPLETED")
    ): UiMessage).asJson
    val keys = encoded.asObject.map(_.keys.toList).getOrElse(Nil)
    assert(!keys.contains("fileChanges"), "缺席载荷绝不落键（旧 .ui.json 行字节形态逐字不变）")

  test("User row with fileChanges round-trips encode → decode with the same payload"):
    val row = UiMessage.User(
      "delegate result",
      injected = true,
      timestamp = 200L,
      source = Some("delegate"),
      fileChanges = Some(diffPayload)
    )
    val decoded = (row: UiMessage).asJson.as[UiMessage].toOption.get
    val u = decoded.asInstanceOf[UiMessage.User]
    assertEquals(u.fileChanges, Some(diffPayload))
    // 与 quote/intake 同款：既有键不被载荷挤掉
    assertEquals(u.source, Some("delegate"))

  test("fileChanges carries the raw payload verbatim (engine does not interpret inner fields)"):
    val exotic = Json.obj("files" -> List(Json.obj("custom" -> Json.arr("a".asJson, 1.asJson))).asJson)
    val decoded = (UiMessage
      .User("t", fileChanges = Some(exotic)): UiMessage).asJson.as[UiMessage].toOption.get.asInstanceOf[UiMessage.User]
    assertEquals(decoded.fileChanges, Some(exotic))

  test("legacy .ui.json row without a fileChanges key decodes with fileChanges=None"):
    val legacy = Json.obj(
      "type" -> "user".asJson,
      "text" -> "old delegate row".asJson,
      "attachments" -> Json.arr(),
      "injected" -> true.asJson,
      "source" -> "delegate".asJson,
      "timestamp" -> 42L.asJson
    )
    val decoded = legacy.as[UiMessage].toOption.get.asInstanceOf[UiMessage.User]
    assertEquals(decoded.fileChanges, None)
    assertEquals(decoded.text, "old delegate row")

  // ===== SessionStore 落盘 round-trip（appendUiMessages → getHistoryPage）=====

  private def withStore(test: SessionStore => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-filechanges-test")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val tasksDir = os.Path(tmp.resolve("tasks"))
    try
      val store = SessionStore(sessionsDir, tasksDir)
      store.load.unsafeRunSync()
      test(store).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  test("fileChanges survives appendUiMessages → getHistoryPage verbatim"):
    withStore { store =>
      val sid = "filechanges-roundtrip-1"
      val row = UiMessage.User(
        "结果报告",
        injected = true,
        timestamp = 300L,
        source = Some("delegate"),
        eventType = Some("completed"),
        fileChanges = Some(diffPayload)
      )
      for
        _ <- store.createSession("filechanges rt")
        _ <- store.appendUiMessages(sid, List(row))
        (page, total, _, _) <- store.getHistoryPage(sid, limit = 10, beforeIndex = None)
      yield
        assertEquals(total, 1)
        val u = page.head.asInstanceOf[UiMessage.User]
        assertEquals(u.fileChanges, Some(diffPayload))
    }

end UiMessageFileChangesSpec
