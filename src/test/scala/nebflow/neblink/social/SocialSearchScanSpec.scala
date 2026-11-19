package nebflow.neblink.social

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import nebflow.neblink.core.MessageSummary

class SocialSearchScanSpec extends munit.FunSuite:
  test("bounded scans find matches and stop, repeated twenty times") {
    (1 to 20).foreach { _ =>
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)
      val result = SocialSearchScan.search(List("a", "b"), "planning", (id, after, limit) => IO {
        calls.incrementAndGet()
        assertEquals(limit, 200)
        Right(List(MessageSummary(1L, "sender", "text", s"Planning $id", 1L)))
      }).unsafeRunSync()
      assertEquals(calls.get(), 2)
      assertEquals(result.hits.map(_.conversationId), List("a", "b"))
      assert(result.truncated)
      assert(!result.failed)
    }
  }

  test("failure and page limit are visible") {
    (1 to 20).foreach { _ =>
      val failure = SocialSearchScan.search(List("a"), "x", (_, _, _) => IO.pure(Left("offline"))).unsafeRunSync()
      assert(failure.failed && failure.truncated)
      val bounded = SocialSearchScan.search(List("a"), "missing", (_, after, _) => IO.pure(Right(
        (1 to 200).toList.map(i => MessageSummary(after + i, "s", "text", "other", 1L))
      ))).unsafeRunSync()
      assertEquals(bounded.pages, 10)
      assert(bounded.truncated)
    }
  }
