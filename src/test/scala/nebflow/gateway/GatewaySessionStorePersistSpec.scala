package nebflow.gateway

import cats.effect.IO
import munit.CatsEffectSuite

/**
 * GatewaySessionStore persistence tests (portal 会话短命根修批, 2026-10-31 —
 * 「引擎重启不杀 portal 会话」, #908 修法②). The snapshot file is the restart
 * bridge: a store that comes back from the same file must still verify the
 * session the browser holds, while expired rows stay dead and a corrupt
 * file boots empty instead of failing.
 */
class GatewaySessionStorePersistSpec extends CatsEffectSuite:

  private def tempFile: os.Path =
    os.temp.dir(prefix = "gw-sess-persist-") / "gateway-sessions.json"

  test("a session created before a restart verifies after restore (engine restart no longer kills portal sessions)"):
    val f = tempFile
    for
      before <- GatewaySessionStore.create(persistenceFile = Some(f))
      rec <- before.create("logto|u", GatewaySessionStore.OriginRelayMint, "net-1")
      _ = before.shutdown()
      // "restart": a brand-new store over the same file.
      after <- GatewaySessionStore.create(persistenceFile = Some(f))
      hit <- after.verifyAndTouch(rec.sessionId)
      _ = assert(hit.isDefined, "the restored session must still verify")
      _ = assertEquals(hit.get.accountSub, "logto|u")
      _ = assertEquals(hit.get.origin, GatewaySessionStore.OriginRelayMint)
      _ = after.shutdown()
    yield ()

  test("sliding touches survive the restart bridge once flushed (dirty-mark + flush path)"):
    val f = tempFile
    for
      before <- GatewaySessionStore.create(persistenceFile = Some(f))
      rec <- before.create("u", "relay-mint", "net")
      touched <- before.verifyAndTouch(rec.sessionId)
      _ = assert(touched.isDefined)
      // Force the daemon's flush path synchronously (the daemon itself ticks
      // every 60s — far too slow for a test).
      _ <- IO(before.flushIfDirtyForTest())
      _ = before.shutdown()
      after <- GatewaySessionStore.create(persistenceFile = Some(f))
      hit <- after.verifyAndTouch(rec.sessionId)
      _ = assert(hit.isDefined)
      _ = assert(hit.get.lastSeenAt >= touched.get.lastSeenAt, "the touched lastSeen must ride the snapshot")
      _ = after.shutdown()
    yield ()

  test("expired rows are NOT resurrected by a restore"):
    val f = tempFile
    for
      store <- GatewaySessionStore.create(absoluteTtlSeconds = 3600, idleTimeoutSeconds = 1, persistenceFile = Some(f))
      rec <- store.create("u", "relay-mint", "net")
      _ <- IO.sleep(scala.concurrent.duration.Duration(1100, "millis"))
      _ = store.shutdown()
      after <- GatewaySessionStore.create(absoluteTtlSeconds = 3600, idleTimeoutSeconds = 1, persistenceFile = Some(f))
      hit <- after.verifyAndTouch(rec.sessionId)
      _ = assert(hit.isEmpty, "an idle-expired session must stay dead across a restart")
      size <- after.size
      _ = assertEquals(size, 0)
      _ = after.shutdown()
    yield ()

  test("a corrupt snapshot boots empty instead of failing"):
    val f = tempFile
    for
      _ <- IO(os.write.over(f, "{not json"))
      store <- GatewaySessionStore.create(persistenceFile = Some(f))
      size <- store.size
      _ = assertEquals(size, 0)
      // The store stays fully usable after the bad load.
      rec <- store.create("u", "relay-mint", "net")
      hit <- store.verifyAndTouch(rec.sessionId)
      _ = assert(hit.isDefined)
      _ = store.shutdown()
    yield ()

  test("revocations write through: a revoked session stays revoked across a restart"):
    val f = tempFile
    for
      store <- GatewaySessionStore.create(persistenceFile = Some(f))
      rec <- store.create("u", "relay-mint", "net")
      n <- store.revokeByMaskedId(rec.sessionId.take(8))
      _ = assertEquals(n, 1)
      _ = store.shutdown()
      after <- GatewaySessionStore.create(persistenceFile = Some(f))
      hit <- after.verifyAndTouch(rec.sessionId)
      _ = assert(hit.isEmpty, "revocation must survive the restart bridge")
      _ = after.shutdown()
    yield ()

  test("no persistence file (the default) keeps the pure in-memory shape"):
    for
      store <- GatewaySessionStore.create()
      rec <- store.create("u", "relay-mint", "net")
      hit <- store.verifyAndTouch(rec.sessionId)
      _ = assert(hit.isDefined)
      _ = store.shutdown()
    yield ()

end GatewaySessionStorePersistSpec
