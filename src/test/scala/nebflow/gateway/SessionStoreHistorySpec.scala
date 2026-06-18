package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.UiMessage

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Tests for SessionStore UI message history performance optimizations:
 *   - getHistoryPage: single-read pagination (replaces double getUiMessages calls)
 *   - UI message in-memory cache correctness
 */
class SessionStoreHistorySpec extends CatsEffectSuite:

  private def withStore(test: SessionStore => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-history-test")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val tasksDir = os.Path(tmp.resolve("tasks"))
    try
      val store = SessionStore(sessionsDir, tasksDir)
      store.load.unsafeRunSync()
      test(store).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  private def aiMsg(n: Int): UiMessage.Ai = UiMessage.Ai(s"AI message $n")

  private def userMsg(n: Int): UiMessage.User = UiMessage.User(s"User message $n")

  private def userText(m: UiMessage): String = m.asInstanceOf[UiMessage.User].text

  private def aiText(m: UiMessage): String = m.asInstanceOf[UiMessage.Ai].text

  // ===== getHistoryPage — initial load (beforeIndex = None) =====

  test("getHistoryPage returns latest messages when total > limit") {
    withStore { store =>
      val sid = "hist-page-1"
      val msgs = (1 to 30).map(i => userMsg(i)).toList
      for
        _ <- store.createSession("Test 1")
        _ <- store.appendUiMessages(sid, msgs)
        (page, total, offset, hasMore) <- store.getHistoryPage(sid, limit = 10, beforeIndex = None)
      yield
        assertEquals(total, 30, "Total should be 30")
        assertEquals(page.length, 10, "Should return last 10 messages")
        assertEquals(offset, 20, "Offset should be 20")
        assert(hasMore, "Should have more messages")
        assertEquals(userText(page.head), "User message 21", "First returned should be message 21")
        assertEquals(userText(page.last), "User message 30", "Last returned should be message 30")
    }
  }

  test("getHistoryPage returns all when total <= limit") {
    withStore { store =>
      val sid = "hist-page-2"
      val msgs = (1 to 5).map(i => aiMsg(i)).toList
      for
        _ <- store.createSession("Test 2")
        _ <- store.appendUiMessages(sid, msgs)
        (page, total, offset, hasMore) <- store.getHistoryPage(sid, limit = 50, beforeIndex = None)
      yield
        assertEquals(total, 5)
        assertEquals(page.length, 5)
        assertEquals(offset, 0)
        assert(!hasMore, "Should not have more")
    }
  }

  test("getHistoryPage handles empty session") {
    withStore { store =>
      val sid = "hist-page-empty"
      for
        _ <- store.createSession("Empty")
        (page, total, offset, hasMore) <- store.getHistoryPage(sid, limit = 50, beforeIndex = None)
      yield
        assertEquals(total, 0)
        assertEquals(page.length, 0)
        assertEquals(offset, 0)
        assert(!hasMore)
    }
  }

  // ===== getHistoryPage — scroll-up pagination (beforeIndex = Some(n)) =====

  test("getHistoryPage returns older messages for scroll-up pagination") {
    withStore { store =>
      val sid = "hist-page-scroll"
      val msgs = (1 to 30).map(i => userMsg(i)).toList
      for
        _ <- store.createSession("Scroll Test")
        _ <- store.appendUiMessages(sid, msgs)
        // Simulate scroll-up: user has seen up to index 20, wants 10 before that
        (page, total, offset, hasMore) <- store.getHistoryPage(sid, limit = 10, beforeIndex = Some(20))
      yield
        assertEquals(total, 30)
        assertEquals(page.length, 10)
        assertEquals(offset, 10, "Offset should be 10")
        assert(hasMore, "Should have more (20 > 10)")
        assertEquals(userText(page.head), "User message 11")
        assertEquals(userText(page.last), "User message 20")
    }
  }

  test("getHistoryPage scroll-up clamps when before < limit") {
    withStore { store =>
      val sid = "hist-page-clamp"
      val msgs = (1 to 20).map(i => userMsg(i)).toList
      for
        _ <- store.createSession("Clamp Test")
        _ <- store.appendUiMessages(sid, msgs)
        // before=3, limit=10 → should return 3 messages (indices 0..2)
        (page, total, offset, hasMore) <- store.getHistoryPage(sid, limit = 10, beforeIndex = Some(3))
      yield
        assertEquals(total, 20)
        assertEquals(page.length, 3, "Should return min(limit, before) = 3 messages")
        assertEquals(offset, 0)
        assert(!hasMore, "3 is not > 10")
    }
  }

  // ===== UI Message Cache correctness =====

  test("getHistoryPage reflects newly appended messages (cache coherence)") {
    withStore { store =>
      val sid = "cache-coherence"
      for
        _ <- store.createSession("Cache Test")
        _ <- store.appendUiMessages(sid, List(userMsg(1), userMsg(2)))
        (page1, total1, _, _) <- store.getHistoryPage(sid, limit = 50, beforeIndex = None)
        _ <- store.appendUiMessages(sid, List(userMsg(3), userMsg(4)))
        (page2, total2, _, _) <- store.getHistoryPage(sid, limit = 50, beforeIndex = None)
      yield
        assertEquals(total1, 2)
        assertEquals(total2, 4, "Cache should reflect newly appended messages")
        assertEquals(page2.length, 4)
        assertEquals(userText(page2.last), "User message 4")
    }
  }

  test("cache is cleared on session deletion") {
    withStore { store =>
      val sid = "cache-delete"
      for
        _ <- store.createSession("Delete Cache Test")
        _ <- store.appendUiMessages(sid, List(userMsg(1), userMsg(2), userMsg(3)))
        (page1, total1, _, _) <- store.getHistoryPage(sid, limit = 50, beforeIndex = None)
        _ <- store.deleteSession(sid)
        (page2, total2, _, _) <- store.getHistoryPage(sid, limit = 50, beforeIndex = None)
      yield
        assertEquals(total1, 3, "Before deletion should have 3 messages")
        assertEquals(total2, 0, "After deletion should have 0 messages")
        assertEquals(page2.length, 0)
    }
  }

  test("setSessionFromCloud updates cache") {
    withStore { store =>
      val sid = "cache-cloud"
      val cloudUiMsgs = (1 to 5).map(i => aiMsg(i)).toList
      for
        _ <- store.createSession("Cloud Cache Test")
        _ <- store.appendUiMessages(sid, List(userMsg(1)))
        (_, total1) <- store.getUiMessages(sid, 0, 0)
        _ <- store.setSessionFromCloud(sid, Nil, cloudUiMsgs)
        (page2, total2, _, _) <- store.getHistoryPage(sid, limit = 50, beforeIndex = None)
      yield
        assertEquals(total1, 1, "Initially 1 message")
        assertEquals(total2, 5, "After cloud sync should have 5 messages (cache replaced)")
        assertEquals(aiText(page2.head), "AI message 1")
    }
  }

  test("multiple sessions have independent caches") {
    withStore { store =>
      val sid1 = "cache-multi-1"
      val sid2 = "cache-multi-2"
      for
        _ <- store.createSession("Session 1")
        _ <- store.createSession("Session 2")
        _ <- store.appendUiMessages(sid1, List(userMsg(1), userMsg(2)))
        _ <- store.appendUiMessages(sid2, List(aiMsg(1), aiMsg(2), aiMsg(3)))
        (page1, total1, _, _) <- store.getHistoryPage(sid1, limit = 50, beforeIndex = None)
        (page2, total2, _, _) <- store.getHistoryPage(sid2, limit = 50, beforeIndex = None)
        // Append to sid1, sid2 cache should be unaffected
        _ <- store.appendUiMessages(sid1, List(userMsg(3)))
        (page2b, total2b, _, _) <- store.getHistoryPage(sid2, limit = 50, beforeIndex = None)
      yield
        assertEquals(total1, 2)
        assertEquals(total2, 3)
        assertEquals(total2b, 3, "sid2 cache should be unaffected by sid1 append")
        assertEquals(page2b.length, 3)
      end for
    }
  }

  test("getHistoryPage and getUiMessages return consistent results after append") {
    withStore { store =>
      val sid = "consistency-test"
      val msgs = (1 to 15).map(i => userMsg(i)).toList
      for
        _ <- store.createSession("Consistency Test")
        _ <- store.appendUiMessages(sid, msgs)
        (historyPage, histTotal, histOffset, _) <- store.getHistoryPage(sid, limit = 10, beforeIndex = None)
        (getPage, getTotal) <- store.getUiMessages(sid, histOffset, 10)
      yield
        assertEquals(histTotal, getTotal, "Both methods should report same total")
        assertEquals(historyPage.length, getPage.length, "Both should return same number of messages")
        assertEquals(historyPage.map(userText), getPage.map(userText), "Both should return same messages")
    }
  }
end SessionStoreHistorySpec
