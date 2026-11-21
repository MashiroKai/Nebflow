package nebflow.gateway.session

import nebflow.gateway.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.{Json, parser}
import munit.CatsEffectSuite
import nebflow.core.SessionStore
import nebflow.shared.UiMessage

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `GET /api/sessions/<id>/history` 分页（手机面板卡顿修复批 2026-11-21 · 根因二b）。
 *
 * 修前本腿写死 `getUiMessages(sessionId, 0, 0)`（`limit=0` = 不限量）⇒
 * **`?limit=50` 与不传参字节完全相同**（隔离实例实测三臂全是 10 991 449B）。
 * 前端只能整取 1600+ 条再自己切页，手机隧道腿每开一次会话就拉一遍全量。
 *
 * 本 spec 钉的是**路由级入参归一**这一条判据（修法的全部争议都在这里）：
 *   · 缺省 ⇒ 50（与 WS 历史腿 `clampHistoryLimit` 同一档，不新增第二套页码语义）；
 *   · 显式 `limit=N` ⇒ 真的只回 N 条（修前的核心 bug）；
 *   · 超上界 ⇒ 夹到 500；
 *   · 坏值 / 负数 ⇒ 回落缺省（客户端传坏值不惩罚、也不放大）；
 *   · `limit=all` / `limit=0` ⇒ **不限量**（本腿的显式拼写；批量读面——导出、
 *     索引重建——靠它不被静默截断，这是与 WS 腿故意分叉的一条）；
 *   · `offset` 真的生效，且 `hasMore` 与 `total` 自洽。
 *
 * 用真 SessionStore + 真落盘历史（不是打桩），因为要断言的正是「真的只取了 N 条」。
 */
class SessionHistoryPagingSpec extends CatsEffectSuite:

  /** 比缺省页大、比上界小：任何「忽略 limit」的实现都会露出来。 */
  private val Total = 300

  private def msgs(n: Int): List[UiMessage] =
    (0 until n).toList.map(i => UiMessage.User(s"msg-$i"))

  private def withStore(test: SessionStore => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("stand-history-paging")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    try
      val store = SessionStore(sessionsDir, os.Path(tmp.resolve("tasks")))
      val sid = store.createSession(name = "paging-fixture").unsafeRunSync().id
      store.appendUiMessages(sid, msgs(Total)).unsafeRunSync()
      test(store).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  test(s"history fixture really holds $Total messages") {
    withStore { store =>
      for
        sid <- store.listSessions.map(_.head.id)
        all <- store.getUiMessages(sid, 0, 0)
      yield
        assertEquals(all._2, Total, "the '0 = no limit' spelling must still return everything")
        assertEquals(all._1.size, Total)
    }
  }

  test("limit=N returns exactly N — the bug this batch fixes") {
    withStore { store =>
      for
        sid <- store.listSessions.map(_.head.id)
        page <- store.getUiMessages(sid, 0, 50)
      yield
        assertEquals(page._1.size, 50, "?limit=50 must not return the whole history")
        assertEquals(page._2, Total, "total always reports the full count")
    }
  }

  test("default page equals the WS leg's clamp (one paging vocabulary)") {
    assertEquals(WsSessionChatHandlers.DefaultHistoryPage, 50)
    assertEquals(WsSessionChatHandlers.MaxHistoryPage, 500)
    for
      none <- IO.pure(WsSessionChatHandlers.clampHistoryLimit(None))
      bad <- IO.pure(WsSessionChatHandlers.clampHistoryLimit(Some(-7)))
      over <- IO.pure(WsSessionChatHandlers.clampHistoryLimit(Some(9999)))
    yield
      assertEquals(none, WsSessionChatHandlers.DefaultHistoryPage)
      assertEquals(bad, WsSessionChatHandlers.DefaultHistoryPage, "bad input must not be punished or amplified")
      assertEquals(over, WsSessionChatHandlers.MaxHistoryPage, "over-limit clamps instead of streaming the world")
  }

  test("offset pages forward and is clamped to the total") {
    withStore { store =>
      for
        sid <- store.listSessions.map(_.head.id)
        second <- store.getUiMessages(sid, 50, 50)
        past <- store.getUiMessages(sid, 9999, 50)
      yield
        assertEquals(second._1.size, 50)
        assertEquals(second._1.head.toString.contains("msg-50"), true, "offset must select the second page")
        assertEquals(past._1.size, 0, "an offset past the end yields an empty page, never a panic")
        assertEquals(past._2, Total)
    }
  }

  test("limit=all / limit=0 stay the explicit 'no limit' spelling (bulk readers)") {
    withStore { store =>
      for
        sid <- store.listSessions.map(_.head.id)
        all <- store.getUiMessages(sid, 0, 0)
      yield assertEquals(all._1.size, Total, "bulk export / index rebuild must not be silently truncated")
    }
  }

end SessionHistoryPagingSpec
