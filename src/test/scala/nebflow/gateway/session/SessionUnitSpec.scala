package nebflow.gateway.session

import nebflow.gateway.*

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.shared.Branding

/**
 * 细粒度单元（session 模块）：会话存储的单次状态迁移 + cookie 冻结属性。
 *
 * 既有 `GatewaySessionStoreSpec` / `SessionStore*Spec` 走的是「落盘快照 / 路由
 * 授权」整链。这里逐条钉 `Ref.modify` 的迁移语义：创建、滑动、过期即删、
 * 掩码撤销、按 origin 撤销、全撤。
 */
class SessionUnitSpec extends CatsEffectSuite:

  private def fresh(): GatewaySessionStore = GatewaySessionStore.unsafeCreate()

  test("create: 43-char url-safe id, sliding ≤ absolute, origin/boundNetwork carried verbatim"):
    val store = fresh()
    store.create("sub-1", GatewaySessionStore.OriginRelayMint, "net-9").map { rec =>
      assertEquals(rec.sessionId.length, 43, "32 bytes base64url unpadded")
      assert(rec.sessionId.forall(c => c.isLetterOrDigit || c == '-' || c == '_'))
      assertEquals(rec.accountSub, "sub-1")
      assertEquals(rec.origin, GatewaySessionStore.OriginRelayMint)
      assertEquals(rec.boundNetworkId, "net-9")
      assert(rec.slidingExpiresAt <= rec.absoluteExpiresAt)
    }

  test("verifyAndTouch: a live hit slides both lastSeen and the sliding window (capped by absolute)"):
    val store = fresh()
    for
      rec <- store.create("sub", GatewaySessionStore.OriginLocalTicket, "net")
      hit <- store.verifyAndTouch(rec.sessionId)
      miss <- store.verifyAndTouch("no-such-session")
    yield
      assertEquals(hit.map(_.sessionId), Some(rec.sessionId))
      assert(hit.exists(_.lastSeenAt >= rec.createdAt))
      assert(hit.exists(r => r.slidingExpiresAt <= r.absoluteExpiresAt))
      assertEquals(miss, None)

  test("expired row: the verify transition deletes it (expired-then-gone)"):
    val store = GatewaySessionStore.unsafeCreate(absoluteTtlSeconds = 0L, idleTimeoutSeconds = 0L)
    for
      rec <- store.create("sub", GatewaySessionStore.OriginRelayMint, "net")
      before <- store.size
      hit <- store.verifyAndTouch(rec.sessionId)
      after <- store.size
    yield
      assertEquals(before, 1)
      assertEquals(hit, None, "zero-TTL row is never live")
      assertEquals(after, 0, "the miss must also evict the row")

  test("list: maskedId is the first 8 chars; revokeByMaskedId targets that prefix only"):
    val store = fresh()
    for
      a <- store.create("a", GatewaySessionStore.OriginRelayMint, "n")
      b <- store.create("b", GatewaySessionStore.OriginRelayMint, "n")
      views <- store.list
      revoked <- store.revokeByMaskedId(a.sessionId.take(8))
      aHit <- store.verifyAndTouch(a.sessionId)
      bHit <- store.verifyAndTouch(b.sessionId)
      emptyNoop <- store.revokeByMaskedId("")
    yield
      assertEquals(views.map(_.maskedId).sorted, List(a.sessionId.take(8), b.sessionId.take(8)).sorted)
      assertEquals(revoked, 1)
      assertEquals(aHit, None)
      assert(bHit.isDefined, "the other session must survive a masked-id revoke")
      assertEquals(emptyNoop, 0, "empty prefix is a no-op")

  test("revokeRelayMinted: relay-mint dies, local-ticket (break-glass) survives; revokeAll clears all"):
    val store = fresh()
    for
      relay <- store.create("r", GatewaySessionStore.OriginRelayMint, "n")
      local <- store.create("l", GatewaySessionStore.OriginLocalTicket, "n")
      killed <- store.revokeRelayMinted()
      relayHit <- store.verifyAndTouch(relay.sessionId)
      localHit <- store.verifyAndTouch(local.sessionId)
      all <- store.revokeAll()
      size <- store.size
    yield
      assertEquals(killed, 1)
      assertEquals(relayHit, None)
      assert(localHit.isDefined, "local-ticket is the break-glass escape hatch")
      assertEquals(all, 1)
      assertEquals(size, 0)

  test("CookieName is brand-derived (never a hardcoded second literal)"):
    assertEquals(GatewaySessionStore.CookieName, s"${Branding.lowerName}_session")
    assertEquals(GatewaySessionStore.CookieName, "stand_session")

  test("newCookie: HttpOnly + SameSite=Lax + host-only + Path=/ + Max-Age=absolute TTL"):
    val secure = GatewaySessionStore.newCookie("sid-1", secure = true)
    assertEquals(secure.name, GatewaySessionStore.CookieName)
    assertEquals(secure.content, "sid-1")
    assertEquals(secure.httpOnly, true)
    assertEquals(secure.secure, true)
    assertEquals(secure.sameSite, Some(org.http4s.SameSite.Lax))
    assertEquals(secure.path, Some("/"))
    assertEquals(secure.domain, None, "host-only: never a Domain attribute")
    assertEquals(secure.maxAge, Some(GatewaySessionStore.AbsoluteTtlSeconds))

    val plain = GatewaySessionStore.newCookie("sid-1", secure = false)
    assertEquals(plain.secure, false, "plain-http local console must not set Secure (WebKit drops it)")

end SessionUnitSpec
