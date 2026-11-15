package nebflow.gateway

import munit.FunSuite

class RecoveryPrincipalSpec extends FunSuite:
  test("recovery principal uses only verified supported credential rails") {
    val record = SessionRecord("secret-cookie", "account", "relay-mint", "network", 1L, 2L, 1L, 2L)
    val first = RecoveryPrincipal.fromCredential(Credential.ViaSession(record)).get
    val second = RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(sessionId = "another-secret"))).get
    assertEquals(first, second)
    assert(!first.contains("secret"))
    assertNotEquals(first, RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(boundNetworkId = "other"))).get)
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaSession(record.copy(accountSub = ""))), None)
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaBearer(true)), Some("local-file-token"))
    assertEquals(RecoveryPrincipal.fromCredential(Credential.ViaBearer(false)), None)
  }
