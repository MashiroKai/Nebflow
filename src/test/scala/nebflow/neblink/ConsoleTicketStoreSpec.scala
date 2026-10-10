package nebflow.neblink

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * Console ticket store unit tests (phase 3a design 3.4) — the faces the
 * bridge spec cannot reach with real time: TTL expiry, the 120 s hard cap,
 * and the issue-path sweep. Redeemed-then-destroyed and the mint budget are
 * pinned in `PortalTunnelBridgeSpec` (they need the mailbox consumer).
 */
class ConsoleTicketStoreSpec extends CatsEffectSuite:

  test("issued ticket carries the contract anchor fields (origin relay-mint, scope console, one-time)"):
    for
      store <- ConsoleTicketStore.create()
      issued <- store.issue("logto|user-42", "qa-device")
      consumed <- store.verifyAndConsume(issued.token)
    yield
      assertEquals(issued.expiresInSec, 30L)
      assert(issued.token.length >= 43, s"32-byte base64url token expected, got ${issued.token.length} chars")
      consumed match
        case Right(t) =>
          assertEquals(t.accountSub, "logto|user-42")
          assertEquals(t.boundHost, "qa-device")
          assertEquals(t.origin, "relay-mint")
          assertEquals(t.scope, "console")
          assertEquals(t.remaining, 1)
        case Left(reason) => fail(s"first redemption must succeed: $reason")

  test("TTL expiry: verifyAndConsume refuses after the TTL and the entry is gone"):
    for
      store <- ConsoleTicketStore.create(ttlSeconds = 1)
      issued <- store.issue("logto|user-42", "qa-device")
      _ <- IO.sleep(1300.millis)
      expired <- store.verifyAndConsume(issued.token)
      again <- store.verifyAndConsume(issued.token)
    yield
      assertEquals(expired, Left("expired"))
      assertEquals(again, Left("invalid-ticket"), "the expired entry must be deleted, not retried")

  test("TTL is clamped to the contract's 120 s hard cap"):
    for
      clamped <- ConsoleTicketStore.create(ttlSeconds = 9999)
      issued <- clamped.issue("logto|user-42", "qa-device")
    yield assertEquals(issued.expiresInSec, 120L)

  test("issue sweeps expired tickets (single-transaction shape with the insert)"):
    for
      store <- ConsoleTicketStore.create(ttlSeconds = 1)
      stale <- store.issue("logto|a", "qa-device")
      _ <- IO.sleep(1300.millis)
      _ <- store.issue("logto|b", "qa-device")
      size <- store.size
      staleVerdict <- store.verifyAndConsume(stale.token)
    yield
      assertEquals(size, 1, "only the fresh ticket survives the issue-path sweep")
      assertEquals(staleVerdict, Left("invalid-ticket"))

  test("revokeAll drops every live ticket"):
    for
      store <- ConsoleTicketStore.create()
      _ <- store.issue("logto|a", "qa-device")
      _ <- store.issue("logto|b", "qa-device")
      dropped <- store.revokeAll()
      size <- store.size
    yield
      assertEquals(dropped, 2)
      assertEquals(size, 0)

end ConsoleTicketStoreSpec
