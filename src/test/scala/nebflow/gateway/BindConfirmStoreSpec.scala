package nebflow.gateway

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * D6 bind-confirm pending state (phase 3c plan §8 component ①) — the
 * testable faces:
 *
 *  - code format: 6 digits, string, leading zeros are real codes;
 *  - TTL: expiry arithmetic on the injected clock (the plan's 15-minute
 *    recommendation) and the read-path sweep;
 *  - single-slot replace semantics (newest report wins).
 *
 * Deliberately NOT run here: the ONLINE mint route and the real socket send
 * (the send path mirrors `sendAck` verbatim; its honesty mechanics are
 * covered by the ack-family specs).
 */
class BindConfirmStoreSpec extends FunSuite:

  private def storeAt(clockRef: Ref[IO, Long]): BindConfirmStore =
    BindConfirmStore.unsafeCreate(clockRef.get)

  test("newCode: always exactly 6 digits (format stability over a large sample)"):
    val codes = (1 to 1000).map(_ => BindConfirmStore.newCode())
    codes.foreach(c =>
      assert(c.length == 6 && c.forall(_.isDigit), s"malformed code: $c")
    )

  test("newCode: the sample space spans both low and high codes (leading zeros are real codes)"):
    // A uniform 000000-999999 generator over 1000 draws must not stay inside
    // one decade band; this pins the "string, not number" hazard: a numeric
    // pipeline would collapse every leading-zero code.
    val codes = (1 to 1000).map(_ => BindConfirmStore.newCode())
    assert(codes.exists(_.startsWith("0")), "expected at least one leading-zero code in 1000 draws")
    assert(codes.exists(!_.startsWith("0")), "expected at least one non-leading-zero code")

  test("arm records expiry = now + ttl*1000 and peek returns the pending entry"):
    val clock = Ref.unsafe[IO, Long](1_000_000L)
    val store = storeAt(clock)
    val pending = store.arm("012345", 900L).unsafeRunSync()
    assertEquals(pending.code, "012345")
    assertEquals(pending.ttlSec, 900L)
    assertEquals(pending.expiresAtMs, 1_900_000L, "expiry = now + ttl*1000")
    val peeked = store.peek().unsafeRunSync()
    assertEquals(peeked.map(_.code), Some("012345"))

  test("TTL: before the deadline the entry is live, at/after it is swept (plan TTL = 900s)"):
    val clock = Ref.unsafe[IO, Long](0L)
    val store = storeAt(clock)
    store.arm("999999", BindConfirmStore.DefaultTtlSec).unsafeRunSync()
    clock.set(900_000L - 1).unsafeRunSync()
    assert(store.peek().unsafeRunSync().isDefined, "1ms before the deadline the code is still pending")
    clock.set(900_000L).unsafeRunSync()
    assert(store.peek().unsafeRunSync().isEmpty, "at the deadline the entry is expired")
    assert(store.peek().unsafeRunSync().isEmpty, "the sweep is sticky (no zombie resurrection)")

  test("arm replaces the previous pending code (single slot, newest wins)"):
    val clock = Ref.unsafe[IO, Long](0L)
    val store = storeAt(clock)
    store.arm("111111", 900L).unsafeRunSync()
    store.arm("222222", 900L).unsafeRunSync()
    val peeked = store.peek().unsafeRunSync()
    assertEquals(peeked.map(_.code), Some("222222"), "the first code must be invalidated by the second arm")
    assertEquals(peeked.map(_.ttlSec), Some(900L))

  test("DefaultTtlSec is the plan's 15-minute recommendation"):
    assertEquals(BindConfirmStore.DefaultTtlSec, 900L)

end BindConfirmStoreSpec
