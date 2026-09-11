package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * Ghost-peer convergence tests (neblink client online-status fix, C2 + C3).
 *
 * Root cause being nailed here: the local peer list only ever GREW — the
 * periodic heartbeat path upserted peers but never removed ones that vanished
 * from the NebLink Server response, and syncPeers judged staleness only
 * against active WS connections (so peers without a P2P connection — e.g.
 * cross-network, relay-only — never left the list). A device that logged out
 * 18h ago stayed in the list and, combined with the hardcoded
 * `"online" -> true` in /neblink/status, displayed as online forever.
 *
 * Nails:
 *  - C2a: syncPeers removes peersRef entries absent from the server response
 *    (grace-period removal, cancellable by re-appearance).
 *  - C2b: syncPeers refreshes lastSeen for listed peers (drives C3 freshness).
 *  - C2c: the heartbeat path (doHeartbeat) converges via syncPeers, not bare
 *    upsert — ghost peer is evicted after a heartbeat with an empty response.
 *  - C3: onlineFreshnessMs / isPeerOnline — 90s floor matching the server TTL,
 *    2x sync-interval slack, future-skew tolerance.
 */
class NeblinkPresenceConvergenceSpec extends CatsEffectSuite:

  private val testGrace = 400.millis

  private def peer(id: String, lastSeen: Long = System.currentTimeMillis()): PeerInfo =
    PeerInfo(
      deviceId = id,
      deviceName = s"Device-$id",
      platform = "macos",
      address = "http://127.0.0.1:9", // discard port — connect() fails fast
      lastSeen = lastSeen
    )

  private def serverPeer(id: String): NeblinkPeerInfo =
    NeblinkPeerInfo(id, s"Device-$id", "macos", List(NeblinkEndpoint("127.0.0.1", 9, "lan")), online = true)

  /** Heartbeat stub client — never touches the network. */
  private def stubClient(peers: List[NeblinkPeerInfo]): NeblinkClient =
    new NeblinkClient(NeblinkServerConfig(url = "http://127.0.0.1:9", networkId = "n1", secret = "s"), 0):
      override def heartbeat: IO[Either[String, List[NeblinkPeerInfo]]] = IO.pure(Right(peers))

  private def withStack[A](use: (NeblinkService, NeblinkPresenceService, NeblinkDiscovery) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.createForTest(0, dispatcher, testGrace)
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        discovery = new NeblinkDiscovery(ms, 0, ps, None)
        out <- use(ms, ps, discovery)
      yield out
    }

  private def withTunnel[A](use: (NeblinkService, NeblinkRelayTunnel) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.createForTest(0, dispatcher, testGrace)
        // 2026-09-11：构造参 serverUrl 已移除（连接期 live 解析）。本 spec 只驱动
        // 帧解析，从不起隧道 ⇒ 无需配置 server 址。
        tunnel = new NeblinkRelayTunnel(ms, () => IO.pure(None))(dispatcher)
        out <- use(ms, tunnel)
      yield out
    }

  // ===== C2a: peersRef-based staleness =====

  test("syncPeers evicts ghost peers absent from the server response") {
    withStack { (ms, ps, _) =>
      for
        _ <- ms.upsertPeer(peer("ghost"))
        _ <- ps.syncPeers(Nil) // server no longer reports the device
        during <- ms.peers // grace period: still listed briefly
        _ <- IO.sleep(testGrace + 300.millis)
        after <- ms.peers
      yield
        assert(during.exists(_.deviceId == "ghost"), "peer should linger within the grace period")
        assertEquals(after.exists(_.deviceId == "ghost"), false, "ghost peer must be evicted after grace")
    }
  }

  test("syncPeers eviction is cancelled when the peer reappears in time") {
    withStack { (ms, ps, _) =>
      for
        _ <- ms.upsertPeer(peer("flap"))
        _ <- ps.syncPeers(Nil) // one response without it
        _ <- ps.syncPeers(List(peer("flap"))) // next response has it again
        _ <- IO.sleep(testGrace + 300.millis)
        after <- ms.peers
      yield assert(after.exists(_.deviceId == "flap"), "re-appeared peer must survive the pending removal")
    }
  }

  test("syncPeers keeps listed peers that never had a WS connection") {
    withStack { (ms, ps, _) =>
      for
        _ <- ms.upsertPeer(peer("relay-only"))
        _ <- ps.syncPeers(List(peer("relay-only")))
        _ <- IO.sleep(testGrace + 300.millis)
        after <- ms.peers
      yield assert(after.exists(_.deviceId == "relay-only"), "listed peer must not be evicted")
    }
  }

  // ===== C2b: freshness refresh =====

  test("syncPeers refreshes lastSeen for listed peers (drives C3 freshness)") {
    withStack { (ms, ps, _) =>
      val stale = System.currentTimeMillis() - 60_000
      for
        _ <- ms.upsertPeer(peer("seen", lastSeen = stale))
        _ <- ps.syncPeers(List(peer("seen")))
        after <- ms.peers
      yield
        val refreshed = after.find(_.deviceId == "seen").map(_.lastSeen).getOrElse(0L)
        assert(refreshed > stale, s"lastSeen should be refreshed by sync (was $stale, now $refreshed)")
    }
  }

  // ===== C2c: heartbeat path converges =====

  test("heartbeat path evicts ghost peers (doHeartbeat uses syncPeers)") {
    withStack { (ms, _, discovery) =>
      for
        _ <- ms.upsertPeer(peer("kai-ghost"))
        _ <- discovery.setClient(Some(stubClient(Nil))) // server reports zero peers
        _ <- discovery.heartbeatCycle
        _ <- IO.sleep(testGrace + 300.millis)
        after <- ms.peers
      yield assertEquals(after.exists(_.deviceId == "kai-ghost"), false, "heartbeat with empty response must evict ghost")
    }
  }

  test("heartbeat path adds new peers and evicts vanished ones in one cycle") {
    withStack { (ms, _, discovery) =>
      for
        _ <- ms.upsertPeer(peer("gone"))
        _ <- discovery.setClient(Some(stubClient(List(serverPeer("newcomer")))))
        _ <- discovery.heartbeatCycle
        immediate <- ms.peers
        _ <- IO.sleep(testGrace + 300.millis)
        settled <- ms.peers
      yield
        assert(immediate.exists(_.deviceId == "newcomer"), "new peer from heartbeat response must be listed")
        assertEquals(settled.exists(_.deviceId == "gone"), false, "vanished peer must be evicted")
        assert(settled.exists(_.deviceId == "newcomer"), "reported peer must remain")
    }
  }

  // ===== C3: freshness predicate =====

  test("onlineFreshnessMs: 90s floor matches server TTL, 2x interval slack above it") {
    assertEquals(NeblinkService.onlineFreshnessMs(45), 90_000L) // default interval → floor
    assertEquals(NeblinkService.onlineFreshnessMs(10), 90_000L) // small interval → floor
    assertEquals(NeblinkService.onlineFreshnessMs(120), 240_000L) // large interval → 2x slack
  }

  test("isPeerOnline: fresh peer online, aged-out peer offline, future skew tolerated") {
    val now = System.currentTimeMillis()
    assert(NeblinkService.isPeerOnline(peer("a", lastSeen = now), now, 45), "just-seen peer is online")
    assert(NeblinkService.isPeerOnline(peer("b", lastSeen = now - 89_000), now, 45), "within window")
    assert(!NeblinkService.isPeerOnline(peer("c", lastSeen = now - 91_000), now, 45), "past window → offline")
    assert(NeblinkService.isPeerOnline(peer("d", lastSeen = now + 5_000), now, 45), "clock skew never marks offline")
  }

  // ===== C6: server-pushed DeviceStatusUpdate =====

  test("DeviceStatusUpdate offline push flips a listed peer offline immediately") {
    withStack { (ms, _, _) =>
      for
        _ <- ms.upsertPeer(peer("pushed"))
        callbackCount <- cats.effect.Ref.of[IO, Int](0)
        _ <- ms.addPeerChangeCallback(callbackCount.update(_ + 1))
        _ <- ms.applyServerPeerStatus("pushed", online = false)
        after <- ms.peers
        count <- callbackCount.get
      yield
        val p = after.find(_.deviceId == "pushed").getOrElse(fail("peer must stay listed as offline"))
        assert(!NeblinkService.isPeerOnline(p, System.currentTimeMillis(), 45), "pushed-offline peer must read offline")
        assertEquals(count, 1, "push must fire the peer-change callback (drives UI refetch)")
    }
  }

  test("DeviceStatusUpdate online push flips the peer back and cancels pending removal") {
    withStack { (ms, ps, _) =>
      for
        _ <- ms.upsertPeer(peer("flappy"))
        _ <- ms.applyServerPeerStatus("flappy", online = false)
        _ <- ms.removePeer("flappy") // pending removal in flight
        _ <- ms.applyServerPeerStatus("flappy", online = true)
        _ <- IO.sleep(testGrace + 300.millis)
        after <- ms.peers
      yield
        val p = after.find(_.deviceId == "flappy").getOrElse(fail("online push must cancel the pending removal"))
        assert(NeblinkService.isPeerOnline(p, System.currentTimeMillis(), 45), "pushed-online peer must read online")
    }
  }

  test("offline-flagged server peers are never freshened by sync (toNeblinkPeers honors online flag)") {
    withStack { (ms, _, discovery) =>
      for
        _ <- discovery.setClient(Some(stubClient(List(serverPeer("dead").copy(online = false)))))
        _ <- discovery.heartbeatCycle
        after <- ms.peers
      yield
        val p = after.find(_.deviceId == "dead").getOrElse(fail("offline-flagged peer stays listed"))
        assert(!NeblinkService.isPeerOnline(p, System.currentTimeMillis(), 45),
          "a heartbeat response carrying online=false must not mark the peer fresh")
    }
  }

  test("relay tunnel frame parser accepts camelCase and snake_case deviceId (C6 wire)") {
    withTunnel { (ms, tunnel) =>
      for
        _ <- ms.upsertPeer(peer("camel"))
        _ <- ms.upsertPeer(peer("snake"))
        _ <- tunnel.handleDeviceStatusUpdate(
          io.circe.parser.parse("""{"type":"device_status_update","deviceId":"camel","online":false}""").toOption.get)
        _ <- tunnel.handleDeviceStatusUpdate(
          io.circe.parser.parse("""{"type":"device_status_update","device_id":"snake","online":false}""").toOption.get)
        after <- ms.peers
      yield
        val now = System.currentTimeMillis()
        assert(!after.exists(p => p.deviceId == "camel" && NeblinkService.isPeerOnline(p, now, 45)), "camelCase frame must apply")
        assert(!after.exists(p => p.deviceId == "snake" && NeblinkService.isPeerOnline(p, now, 45)), "snake_case frame must apply")
    }
  }

  test("DeviceStatusUpdate frame without deviceId is ignored, not fatal") {
    withTunnel { (_, tunnel) =>
      tunnel.handleDeviceStatusUpdate(
        io.circe.parser.parse("""{"type":"device_status_update","online":false}""").toOption.get)
    }
  }

  // ===== C6b: server wire uses `status` string, not the `online` boolean =====
  // The server (relay.rs) emits `{"deviceId":"...","status":"offline"}`, and
  // its wire-shape test pins that spelling. The consumer previously read only
  // the `online` boolean (defaulting to false), so a `status:"online"` frame
  // from the upcoming server online-broadcast would have been misread as
  // OFFLINE. These tests pin the dual-field toleration.

  test("DeviceStatusUpdate status-string frame (current server wire) flips a peer online/offline (fix-client-events #1)") {
    withTunnel { (ms, tunnel) =>
      for
        _ <- ms.upsertPeer(peer("srv"))
        _ <- tunnel.handleDeviceStatusUpdate(
          io.circe.parser.parse("""{"type":"device_status_update","deviceId":"srv","status":"online"}""").toOption.get)
        afterOnline <- ms.peers
        _ <- tunnel.handleDeviceStatusUpdate(
          io.circe.parser.parse("""{"type":"device_status_update","deviceId":"srv","status":"offline"}""").toOption.get)
        afterOffline <- ms.peers
      yield
        val now = System.currentTimeMillis()
        assert(afterOnline.exists(p => p.deviceId == "srv" && NeblinkService.isPeerOnline(p, now, 45)),
          "status:online must mark the peer online")
        assert(!afterOffline.exists(p => p.deviceId == "srv" && NeblinkService.isPeerOnline(p, now, 45)),
          "status:offline must mark the peer offline")
    }
  }

  test("DeviceStatusUpdate status:string is case-insensitive (ONLINE/OFFLINE tolerated)") {
    withTunnel { (ms, tunnel) =>
      for
        _ <- ms.upsertPeer(peer("cs"))
        _ <- tunnel.handleDeviceStatusUpdate(
          io.circe.parser.parse("""{"type":"device_status_update","deviceId":"cs","status":"ONLINE"}""").toOption.get)
        after <- ms.peers
      yield
        val now = System.currentTimeMillis()
        assert(after.exists(p => p.deviceId == "cs" && NeblinkService.isPeerOnline(p, now, 45)),
          "status:ONLINE (upper-case) must still mark online")
    }
  }

  test("DeviceStatusUpdate online:boolean still wins when both spellings are present (back-compat)") {
    withTunnel { (ms, tunnel) =>
      for
        _ <- ms.upsertPeer(peer("both"))
        // online:true takes priority over a contradictory status:string
        _ <- tunnel.handleDeviceStatusUpdate(
          io.circe.parser.parse("""{"type":"device_status_update","deviceId":"both","online":true,"status":"offline"}""").toOption.get)
        after <- ms.peers
      yield
        val now = System.currentTimeMillis()
        assert(after.exists(p => p.deviceId == "both" && NeblinkService.isPeerOnline(p, now, 45)),
          "online:true must win over status:offline")
    }
  }

end NeblinkPresenceConvergenceSpec
