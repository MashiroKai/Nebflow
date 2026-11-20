package nebflow.gateway

import munit.FunSuite

class RecoveryPrincipalSpec extends FunSuite:

  test("recovery principal uses only verified supported credential rails") {
    val record = SessionRecord("secret-cookie", "account", "relay-mint", "network", 1L, 2L, 1L, 2L)
    val first = RecoveryPrincipal.fromCredential(Credential.ViaSession(record)).get
    val second = RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(sessionId = "another-secret"))).get
    assertEquals(first, second)
    assert(!first.contains("secret"))
    assertNotEquals(
      first,
      RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(boundNetworkId = "other"))).get
    )
    assertNotEquals(
      first,
      RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(accountSub = "other"))).get
    )
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(accountSub = ""))), None)
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(origin = ""))), None)
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(boundNetworkId = ""))), None)
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaBearer(true)), Some("local-file-token"))
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaBearer(false)), None)
  }

  test("local tickets without network enrollment have stable, account-scoped recovery principals") {
    val record = SessionRecord("secret-cookie", "local", GatewaySessionStore.OriginLocalTicket, "", 1L, 2L, 1L, 2L)
    val first = RecoveryPrincipal.fromCredential(Credential.ViaSession(record)).get
    val renewed = record.copy(
      sessionId = "renewed-secret",
      createdAt = 3L,
      absoluteExpiresAt = 4L,
      lastSeenAt = 3L,
      slidingExpiresAt = 4L
    )
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaSession(renewed)), Some(first))
    assert(!first.contains("secret"))
    assertNotEquals(first, "local-file-token")
    assertNotEquals(
      first,
      RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(accountSub = "other"))).get
    )
    assertNotEquals(
      first,
      RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(boundNetworkId = "network"))).get
    )
    assertNotEquals(
      RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(boundNetworkId = "network"))),
      RecoveryPrincipal.fromCredential(
        Credential.ViaSession(record.copy(origin = GatewaySessionStore.OriginRelayMint, boundNetworkId = "network"))
      )
    )
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(accountSub = ""))), None)
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(origin = ""))), None)
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(origin = "unknown"))), None)
    assertEquals(
      RecoveryPrincipal.fromCredential(
        Credential.ViaSession(record.copy(origin = GatewaySessionStore.OriginRelayMint))
      ),
      None
    )
  }
end RecoveryPrincipalSpec
