package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * mailack batch (2026-09-23, author order "the task dispatcher never stopped, it kept
 * receiving Mail") - the B/C face criteria. Re-pinned by the mailmodel batch (2026-09-25):
 * the `type` parameter of the Mail tool is retired, so the dispatcher-reply batching
 * window no longer carries a per-entry mail type. The P0 (dispatcher-to-root) packing-window
 * exemption is now judged on the **body's first-line `[INTERRUPT]` literal**
 * (`MailTool.isDispatcherMailInterrupt`) — a mechanism, not a message type; the merged
 * entry is always injected with eventType `info`.
 *
 * This file pins (each mechanically decidable and able to turn red):
 *   1. **Merge**: N entries into the window => exactly ONE injection (N>=2 sections the
 *      body, every entry's full text still present);
 *   2. **Order + no loss**: FIFO within a batch, cross-batch order == enqueue order;
 *      overflow (beyond `max`) stays queued for the next window;
 *   3. **Exemption + closed window**: a first-line `[INTERRUPT]` literal and `windowMs <= 0`
 *      both bypass the window (Some) => the old behaviour stays reachable;
 *   4. **Literal polarity**: only the FIRST non-empty line, exact-case `[INTERRUPT]`
 *      triggers the exemption — a later line or a lowercase variant must NOT.
 *
 * Red side (mutation): change `flushDispatcherReplies`'s `splitAt` cap to 1 (= never merge)
 * => face 1 turns red; drop the `isDispatcherMailInterrupt` check => faces 3/4 turn red.
 */
class MailDispatcherReplyBatchSpec extends FunSuite:

  override def beforeEach(context: munit.BeforeEach): Unit =
    // clear the in-process buffer before each case (single Ref, shared across suites)
    MailTool.flushDispatcherReplies().unsafeRunSync()

  test("A · empty-window flush is a no-op (no injection, no error)"):
    MailTool.flushDispatcherReplies().unsafeRunSync()
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0)

  test("B · entry sits in the buffer and is NOT injected before the window closes (pending count visible)"):
    val captured = scala.collection.mutable.ListBuffer.empty[String]
    val deliver: String => IO[Unit] = t => IO { captured += t; () }
    val r = MailToolTestAccess.enqueue("hello-1", deliver).unsafeRunSync()
    assertEquals(r, None, "queued => None (the caller reports accepted, delivered at window end)")
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 1)
    assert(MailTool.dispatcherMailWindowArmed.unsafeRunSync())
    assertEquals(captured.toList, Nil, "window still open => not yet injected")
    MailTool.flushDispatcherReplies().unsafeRunSync()
    assertEquals(captured.toList, List("hello-1"), "window end => exactly one injection, body verbatim (N=1)")
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0)

  test("C · N entries merge into ONE injection + every entry's full text present + order kept"):
    val captured = scala.collection.mutable.ListBuffer.empty[String]
    val deliver: String => IO[Unit] = t => IO { captured += t; () }
    (1 to 3).foreach { i =>
      MailToolTestAccess.enqueue(s"body-$i", deliver).unsafeRunSync()
    }
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 3)
    MailTool.flushDispatcherReplies().unsafeRunSync()
    assertEquals(captured.size, 1, "N entries => one injection (the core reading of this batch)")
    val merged = captured.head
    assert(merged.contains("body-1") && merged.contains("body-2") && merged.contains("body-3"), "every entry's full text survives (no folding, no loss)")
    assert(merged.indexOf("body-1") < merged.indexOf("body-2"), "in-batch order kept")
    assert(merged.indexOf("body-2") < merged.indexOf("body-3"), "in-batch order kept")

  test("D · overflow stays queued for the next window (no entry lost) — max=2 injects two per window"):
    val captured = scala.collection.mutable.ListBuffer.empty[String]
    val deliver: String => IO[Unit] = t => IO { captured += t; () }
    (1 to 3).foreach(i => MailToolTestAccess.enqueue(s"x$i", deliver).unsafeRunSync())
    MailToolTestAccess.flushWithMax(2).unsafeRunSync()
    assertEquals(captured.size, 1, "first window: one injection")
    assert(captured.head.contains("x1") && captured.head.contains("x2"))
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 1, "the overflow entry stays queued")
    MailTool.flushDispatcherReplies().unsafeRunSync()
    assertEquals(captured.size, 2, "second window takes the remainder")
    assert(captured.last.contains("x3"), "the overflow entry was not lost")
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0)

  test("E · P0 exemption: a first-line [INTERRUPT] literal bypasses the buffer (Some => immediate-injection path)"):
    val captured = scala.collection.mutable.ListBuffer.empty[String]
    val deliver: String => IO[Unit] = t => IO { captured += t; () }
    val r = MailToolTestAccess.enqueue("[INTERRUPT]\nurgent body", deliver).unsafeRunSync()
    assertEquals(r, Some("[INTERRUPT]\nurgent body"), "first-line [INTERRUPT] => bypass (the caller walks the immediate path)")
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0, "never enters the buffer")
    assertEquals(captured.toList, Nil, "this layer does not inject (the call site's immediate path is responsible)")

  test("F · closed window (windowMs <= 0) => everything bypasses, equal to the old behaviour"):
    MailToolTestAccess.withWindowMs(0L) {
      val captured = scala.collection.mutable.ListBuffer.empty[String]
      val deliver: String => IO[Unit] = t => IO { captured += t; () }
      val r = MailToolTestAccess.enqueue("legacy", deliver).unsafeRunSync()
      assertEquals(r, Some("legacy"), "closed window => bypass (the rollback face)")
      assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0)
    }

  test("G · [INTERRUPT] literal polarity: first non-empty line only, exact case — later lines and lowercase do NOT bypass"):
    // positive: the first non-empty line, trimmed, exact
    assert(MailTool.isDispatcherMailInterrupt("[INTERRUPT]\nbody"), "exact first line must match")
    assert(MailTool.isDispatcherMailInterrupt("\n  [INTERRUPT]  \nbody"), "blank leading lines are skipped; trim applies")
    // negative: the literal on a LATER line is ordinary text (never bypasses)
    assert(!MailTool.isDispatcherMailInterrupt("plain reply\n[INTERRUPT]"), "a later line must NOT trigger the exemption")
    // negative: case-sensitive — the lowercase variant is ordinary text
    assert(!MailTool.isDispatcherMailInterrupt("[interrupt]\nbody"), "lowercase must NOT trigger the exemption")
    // negative: not a prefix/suffix match — adjacent characters break the literal
    assert(!MailTool.isDispatcherMailInterrupt("[INTERRUPT] now"), "adjacent text breaks the literal")

/** Test seam: direct `private[tools]` face calls (same precedent as `MailQueueNebulaSpec`). */
object MailToolTestAccess:
  def enqueue(text: String, deliver: String => IO[Unit]): IO[Option[String]] =
    MailTool.enqueueDispatcherReplyForTest(text, deliver)

  def flushWithMax(n: Int): IO[Unit] =
    MailTool.flushDispatcherRepliesWithMaxForTest(n)

  def withWindowMs[A](ms: Long)(body: => A): A =
    val old = System.getProperty("nebflow.mail.dispatcherBatchMs")
    System.setProperty("nebflow.mail.dispatcherBatchMs", ms.toString)
    try body
    finally
      if old == null then System.clearProperty("nebflow.mail.dispatcherBatchMs")
      else System.setProperty("nebflow.mail.dispatcherBatchMs", old)

  /** IO-level bracket of `withWindowMs`: the prop is set before `io` RUNS and restored
    * after it completes (the window length is read at enqueue time, so specs that drive
    * the production `MailTool.call` with a dispatcher identity must close the window on
    * the RUN, not around the IO construction). Absorbed from the mailunify branch's
    * `withWindowClosed` technique (read-only inventory, mailmodel batch 2026-09-25). */
  def withWindowMsIO[A](ms: Long)(io: IO[A]): IO[A] =
    IO {
      val old = System.getProperty("nebflow.mail.dispatcherBatchMs")
      System.setProperty("nebflow.mail.dispatcherBatchMs", ms.toString)
      old
    }.flatMap { old =>
      io.guarantee(IO {
        if old == null then System.clearProperty("nebflow.mail.dispatcherBatchMs")
        else System.setProperty("nebflow.mail.dispatcherBatchMs", old)
      })
    }
