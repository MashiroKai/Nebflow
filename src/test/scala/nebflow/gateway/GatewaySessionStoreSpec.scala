package nebflow.gateway

import cats.effect.IO
import munit.CatsEffectSuite
import cats.syntax.all.*

import scala.concurrent.duration.*

/**
 * GatewaySessionStore unit tests (phase 3b, plan §2): mint/verify, the 12h
 * absolute ceiling, the 2h sliding window (capped at the ceiling),
 * expired-then-gone in the same transition, and the three revocation faces
 * (masked-id prefix / all / relay-mint-only origin filter).
 *
 * Shortened TTLs are injected through the test seam (`create(absolute,
 * idle)`) — production constants are 43200/7200 and must not be waited out.
 */
class GatewaySessionStoreSpec extends CatsEffectSuite:

  test("create mints a distinct random id and records origin/accountSub/networkId verbatim"):
    for
      store <- GatewaySessionStore.create()
      a <- store.create("logto|user-42", GatewaySessionStore.OriginRelayMint, "net-1")
      b <- store.create("local", GatewaySessionStore.OriginLocalTicket, "")
      _ = assert(a.sessionId != b.sessionId, "session ids must be unique")
      _ = assertEquals(a.sessionId.length, 43, "32 bytes base64url unpadded = 43 chars")
      _ = assertEquals(a.origin, GatewaySessionStore.OriginRelayMint)
      _ = assertEquals(a.accountSub, "logto|user-42")
      _ = assertEquals(a.boundNetworkId, "net-1")
      size <- store.size
      _ = assertEquals(size, 2)
    yield ()

  test("verifyAndTouch touches lastSeen and renews the sliding window, capped at the absolute ceiling"):
    for
      store <- GatewaySessionStore.create(absoluteTtlSeconds = 100, idleTimeoutSeconds = 100)
      rec <- store.create("u", "relay-mint", "net")
      touched <- store.verifyAndTouch(rec.sessionId)
      _ = assert(touched.isDefined)
      _ = assert(touched.get.lastSeenAt >= rec.lastSeenAt, "lastSeen must advance (or hold at now)")
      _ = assert(
        touched.get.slidingExpiresAt > rec.slidingExpiresAt || touched.get.slidingExpiresAt == rec.absoluteExpiresAt,
        "sliding window must renew (up to the absolute cap)"
      )
    yield ()

  test("verifyAndTouch on an unknown id returns None and mutates nothing"):
    for
      store <- GatewaySessionStore.create()
      miss <- store.verifyAndTouch("no-such-session")
      _ = assert(miss.isEmpty)
      size <- store.size
      _ = assertEquals(size, 0)
    yield ()

  test("expired-then-gone: an idle-expired session is DELETED by the verifying transition"):
    for
      store <- GatewaySessionStore.create(absoluteTtlSeconds = 3600, idleTimeoutSeconds = 1)
      rec <- store.create("u", "relay-mint", "net")
      _ <- IO.sleep(1100.millis)
      after <- store.verifyAndTouch(rec.sessionId)
      _ = assert(after.isEmpty, "an idle-expired session must verify as None")
      size <- store.size
      _ = assertEquals(size, 0, "the expired row must be gone from the store, not lazily retained")
    yield ()

  test("absolute TTL is a hard ceiling: verify fails even with fresh sliding activity"):
    for
      store <- GatewaySessionStore.create(absoluteTtlSeconds = 1, idleTimeoutSeconds = 3600)
      rec <- store.create("u", "relay-mint", "net")
      _ <- IO.sleep(1100.millis)
      after <- store.verifyAndTouch(rec.sessionId)
      _ = assert(after.isEmpty, "the 12h absolute ceiling must never be renewed away")
    yield ()

  test("revokeRelayMinted drops ONLY relay-mint sessions (break-glass local-ticket survives)"):
    for
      store <- GatewaySessionStore.create()
      relay <- store.create("logto|u", GatewaySessionStore.OriginRelayMint, "net")
      local <- store.create("local", GatewaySessionStore.OriginLocalTicket, "")
      dropped <- store.revokeRelayMinted()
      _ = assertEquals(dropped, 1)
      relayGone <- store.verifyAndTouch(relay.sessionId)
      _ = assert(relayGone.isEmpty, "relay-mint session must be dead after revocation")
      localAlive <- store.verifyAndTouch(local.sessionId)
      _ = assert(localAlive.isDefined, "local-ticket session is the break-glass escape and survives")
    yield ()

  test("revokeByMaskedId matches by id prefix and reports the dropped count"):
    for
      store <- GatewaySessionStore.create()
      a <- store.create("u1", "relay-mint", "net")
      _ <- store.create("u2", "relay-mint", "net")
      prefix = a.sessionId.take(8)
      dropped <- store.revokeByMaskedId(prefix)
      _ = assertEquals(dropped, 1)
      gone <- store.verifyAndTouch(a.sessionId)
      _ = assert(gone.isEmpty)
      rest <- store.size
      _ = assertEquals(rest, 1)
      none <- store.revokeByMaskedId("")
      _ = assertEquals(none, 0, "an empty prefix is a no-op, never 'revoke everything'")
    yield ()

  test("revokeAll drops everything"):
    for
      store <- GatewaySessionStore.create()
      _ <- store.create("u1", "relay-mint", "net")
      _ <- store.create("u2", "local-ticket", "")
      dropped <- store.revokeAll()
      _ = assertEquals(dropped, 2)
      size <- store.size
      _ = assertEquals(size, 0)
    yield ()

  test("list exposes masked ids only (never the full session id)"):
    for
      store <- GatewaySessionStore.create()
      rec <- store.create("logto|u", "relay-mint", "net-9")
      views <- store.list
      _ = assertEquals(views.size, 1)
      v = views.head
      _ = assertEquals(v.maskedId, rec.sessionId.take(8))
      _ = assert(!views.toString.contains(rec.sessionId), "the full session id must not leak into the view")
    yield ()

  test("the session cookie carries the frozen attribute set (criterion 4, response-header face)"):
    val cookie = GatewaySessionStore.newCookie("abc123")
    assertEquals(cookie.name, GatewaySessionStore.CookieName)
    assertEquals(cookie.content, "abc123")
    assertEquals(cookie.maxAge, Some(GatewaySessionStore.AbsoluteTtlSeconds), "Max-Age=43200 (12h)")
    assertEquals(cookie.sameSite, Some(org.http4s.SameSite.Lax), "SameSite=Lax")
    assert(cookie.secure, "Secure")
    assert(cookie.httpOnly, "HttpOnly")
    assertEquals(cookie.domain, None, "host-only: no Domain attribute")
    assertEquals(cookie.path, Some("/"), "Path=/")
    // The name is brand-derived and NOT the legacy cookie name.
    assert(cookie.name != "nebflow_token", "the session cookie must be a distinct jar entry")
    assertEquals(cookie.name, s"${nebflow.shared.Branding.lowerName}_session")

end GatewaySessionStoreSpec
