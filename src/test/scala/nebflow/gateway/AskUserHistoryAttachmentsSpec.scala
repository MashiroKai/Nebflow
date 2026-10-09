package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.SessionStore
import nebflow.shared.UiMessage

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * askUser 历史恢复批（2026-10-02，chain-askuser-histattach）配对 spec。
 *
 * 判据面 = **网关落盘腿层：`askUser` 帧 → `.ui.json` 落盘行**，单一构造点
 * = 伴生对象纯函数 [[WebSocketRoutes.askUserRowFromFrame]]（`private[gateway]`，
 * 生产消费者 = `WebSocketRoutes#makeRecordingWsSend` 的 `case "askUser"`）。
 *
 * 🔴 与 `AskUserAnswerPersistSpec` 的分工（禁重复层）：
 *   - `AskUserAnswerPersistSpec` ④b 覆盖的是 `UiMessage.AskUser` **类型层**的
 *     编码器 / 解码器语义（落键规则、往返、形态不合回落）；
 *   - 本 spec 覆盖的是**帧解析层**（`Json` 帧的取键形态 / 宽松回落）与**该行落盘后
 *     盘上原始字节**（历史恢复腿读回的同一形状）。两层判据互不重复。
 *
 * 目标是「askUser 落盘行携带 attachments（与活会话行同构）+ 历史恢复腿读回」，
 * 且「非 askUser / 无附件载荷逐字节不变」。
 */
class AskUserHistoryAttachmentsSpec extends CatsEffectSuite:

  private val atts = List("/tmp/report.pdf", "/tmp/chart.png")

  private def askFrame(extra: (String, Json)*): Json =
    Json.obj(
      ("type" -> Json.fromString("askUser")) ::
        ("items" -> Json.arr(Json.obj("question" -> Json.fromString("历史恢复批：附件行")))) ::
        extra.toList*
    )

  private def uiFile(sessionsDir: os.Path, sid: String): os.Path = sessionsDir / s"$sid.ui.json"

  private def withStore(test: (SessionStore, os.Path) => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-askhistatt-test")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val store = SessionStore(sessionsDir, os.Path(tmp.resolve("tasks")))
    try
      store.load.unsafeRunSync()
      test(store, sessionsDir).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  // ============================================================
  // ① 帧带 attachments ⇒ 落盘行带 attachments（与活会话行同构的键）
  // ============================================================
  test("① 帧带 attachments + requestId ⇒ 行 attachments / requestId / items 三者俱在") {
    val frame = askFrame(
      "requestId" -> Json.fromString("asknb-hist-1"),
      "attachments" -> atts.asJson
    )
    val row = WebSocketRoutes.askUserRowFromFrame(frame)
    assertEquals(row.attachments, Some(atts), "帧附件必须原样进落盘行（人的可点击入口）")
    assertEquals(row.requestId, Some("asknb-hist-1"))
    assertEquals(row.items.size, 1)
  }

  test("② 帧无 attachments 键 ⇒ None（旧帧形态回落，非 askUser 类载荷零影响）") {
    val row = WebSocketRoutes.askUserRowFromFrame(askFrame("requestId" -> Json.fromString("asknb-hist-2")))
    assertEquals(row.attachments, None)
  }

  test("③ 帧 attachments 为空数组 ⇒ None（与编码器「空即不落键」对偶）") {
    val row = WebSocketRoutes.askUserRowFromFrame(askFrame("attachments" -> Json.arr()))
    assertEquals(row.attachments, None, "空列表与缺席同形，禁落空键")
  }

  test("④ 帧形态不合（非字符串数组 / 非数组）⇒ None，且不使整帧解析失败") {
    val notStrings = WebSocketRoutes.askUserRowFromFrame(
      askFrame("attachments" -> Json.arr(Json.fromInt(1), Json.fromInt(2)))
    )
    assertEquals(notStrings.attachments, None, "非字符串数组 ⇒ 回落 None（宽松透传）")

    val notArray = WebSocketRoutes.askUserRowFromFrame(askFrame("attachments" -> Json.obj("a" -> Json.fromString("b"))))
    assertEquals(notArray.attachments, None, "非数组 ⇒ 回落 None")
  }

  test("⑤ 帧 requestId 取键形态与改前逐字一致（缺席 / 形态不合 ⇒ None）") {
    assertEquals(WebSocketRoutes.askUserRowFromFrame(askFrame()).requestId, None, "缺席 ⇒ None")
    assertEquals(WebSocketRoutes.askUserRowFromFrame(askFrame("requestId" -> Json.fromInt(7))).requestId, None, "形态不合 ⇒ None")
    // 逐字对齐既有腿（`as[String].toOption.filter(_.nonEmpty)`：空白串仍是「在场」，
    // 本批不越界改这条语义 —— 仅作改前/改后同形自证）。
    assertEquals(WebSocketRoutes.askUserRowFromFrame(askFrame("requestId" -> Json.fromString("   "))).requestId, Some("   "))
  }

  // ============================================================
  // ⑥ 真实落盘：帧 → 行 → 盘上原始字节（历史恢复腿读回的同一形状）
  // ============================================================
  test("⑥ SessionStore：带 attachments 的帧落盘 ⇒ 盘上字节含 attachments 与路径；读回同值") {
    val frame = askFrame(
      "requestId" -> Json.fromString("asknb-hist-3"),
      "attachments" -> atts.asJson
    )
    withStore { (store, sessionsDir) =>
      val sid = "askhistatt-1"
      for
        _ <- store.appendUiMessages(sid, List(WebSocketRoutes.askUserRowFromFrame(frame)))
        _ <- store.flushPendingUiWrites
        (rows, total) <- store.getUiMessages(sid, 0, 10)
      yield
        assertEquals(total, 1)
        assertEquals(
          rows.collectFirst { case a: UiMessage.AskUser => a.attachments }.flatten,
          Some(atts),
          "历史恢复腿的数据源必须读回附件"
        )
        val raw = os.read(uiFile(sessionsDir, sid))
        assert(raw.contains("\"attachments\""), s"盘上提问行缺 attachments 键：$raw")
        assert(raw.contains("\"/tmp/report.pdf\""), s"盘上提问行缺附件路径：$raw")
    }
  }

  test("⑦ 旧帧（无 attachments 键）落盘 ⇒ 盘上字节不含 attachments（逐字节不变）") {
    withStore { (store, sessionsDir) =>
      val sid = "askhistatt-legacy"
      for
        _ <- store.appendUiMessages(
          sid,
          List(WebSocketRoutes.askUserRowFromFrame(askFrame("requestId" -> Json.fromString("asknb-hist-4"))))
        )
        _ <- store.flushPendingUiWrites
      yield
        val raw = os.read(uiFile(sessionsDir, sid))
        assert(!raw.contains("\"attachments\""), s"无附件载荷禁落键（旧形态逐字节不变）：$raw")
        assert(raw.contains("\"type\":\"askUser\""), s"提问行仍照常落盘：$raw")
        assert(raw.contains("\"requestId\":\"asknb-hist-4\""), s"既有 requestId 键不受影响：$raw")
    }
  }
end AskUserHistoryAttachmentsSpec
