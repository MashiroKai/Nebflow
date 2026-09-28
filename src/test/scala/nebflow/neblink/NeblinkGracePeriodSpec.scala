package nebflow.neblink

import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * Tests for the peer removal grace period mechanism.
 *
 * When a WebSocket disconnects briefly (heartbeat timeout, network hiccup),
 * removePeer schedules a delayed removal instead of immediately stripping the
 * peer from the list. If the peer reconnects within the grace period (via
 * upsertPeer), the removal is cancelled and the frontend never sees a change.
 */
class NeblinkGracePeriodSpec extends CatsEffectSuite:

  // Short grace period for fast tests
  private val testGrace = 500.millis

  private def mkService: IO[NeblinkService] =
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(0, dispatcher, testGrace)
    }

  private val samplePeer = PeerInfo(
    deviceId = "dev-001",
    deviceName = "TestPC",
    platform = "windows",
    address = "http://100.0.0.1:8080"
  )

  test("removePeer does NOT immediately remove the peer") {
    mkService.flatMap { svc =>
      for
        _ <- svc.upsertPeer(samplePeer)
        _ <- svc.removePeer("dev-001")
        peers <- svc.peers
      yield assertEquals(
        peers.exists(_.deviceId == "dev-001"),
        true,
        "Peer should still be in list immediately after removePeer (within grace period)"
      )
    }
  }

  test("upsertPeer cancels pending removal — peer stays in list") {
    mkService.flatMap { svc =>
      for
        _ <- svc.upsertPeer(samplePeer)
        _ <- svc.removePeer("dev-001")
        _ <- svc.upsertPeer(samplePeer) // reconnect within grace period
        _ <- IO.sleep(testGrace + 200.millis) // wait past original grace period
        peers <- svc.peers
      yield assertEquals(
        peers.exists(_.deviceId == "dev-001"),
        true,
        "Peer should still be present — reconnection cancelled the pending removal"
      )
    }
  }

  test("peer IS removed after grace period expires without reconnect") {
    mkService.flatMap { svc =>
      for
        _ <- svc.upsertPeer(samplePeer)
        _ <- svc.removePeer("dev-001")
        _ <- IO.sleep(testGrace + 200.millis) // wait past grace period
        peers <- svc.peers
      yield assertEquals(
        peers.exists(_.deviceId == "dev-001"),
        false,
        "Peer should be removed after grace period expires without reconnect"
      )
    }
  }

  test("double removePeer schedules only one removal") {
    mkService.flatMap { svc =>
      for
        _ <- svc.upsertPeer(samplePeer)
        _ <- svc.removePeer("dev-001")
        _ <- svc.removePeer("dev-001") // second call should be no-op
        _ <- svc.upsertPeer(samplePeer) // reconnect
        _ <- IO.sleep(testGrace + 200.millis)
        peers <- svc.peers
      yield assertEquals(
        peers.exists(_.deviceId == "dev-001"),
        true,
        "Double removePeer + reconnect should keep peer in list"
      )
    }
  }

  test("notifyPeersChanged fires exactly once on real removal") {
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        svc <- NeblinkService.createForTest(0, dispatcher, testGrace)
        callCount <- Ref.of[IO, Int](0)
        _ <- svc.addPeerChangeCallback(callCount.update(_ + 1))
        _ <- svc.upsertPeer(samplePeer) // +1 (new peer)
        count1 <- callCount.get
        _ <- svc.removePeer("dev-001") // schedules removal, no immediate callback
        count2 <- callCount.get
        _ <- IO.sleep(testGrace + 200.millis) // grace period expires → +1
        count3 <- callCount.get
      yield
        assertEquals(count1, 1, "upsertPeer should fire callback once for new peer")
        assertEquals(count2, 1, "removePeer should NOT fire callback immediately")
        assertEquals(count3, 2, "callback should fire once after grace period expiry")
    }
  }

  test("notifyPeersChanged does NOT fire when reconnect cancels removal") {
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        svc <- NeblinkService.createForTest(0, dispatcher, testGrace)
        callCount <- Ref.of[IO, Int](0)
        _ <- svc.addPeerChangeCallback(callCount.update(_ + 1))
        _ <- svc.upsertPeer(samplePeer) // +1
        _ <- svc.removePeer("dev-001") // schedule removal
        _ <- svc.upsertPeer(samplePeer) // cancel removal
        _ <- IO.sleep(testGrace + 200.millis)
        count <- callCount.get
      yield assertEquals(
        count,
        1,
        "Only the initial upsert should fire callback — no extra fires for disconnect+reconnect within grace"
      )
    }
  }

end NeblinkGracePeriodSpec
