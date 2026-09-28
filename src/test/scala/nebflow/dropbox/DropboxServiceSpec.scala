package nebflow.dropbox

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.gateway.WsHub
import nebflow.neblink.{NeblinkClient, NeblinkServerConfig, NeblinkService, PeerInfo}

import scala.concurrent.duration.*

/**
 * Dropbox transfer state machine fixes (diag-transfer-stuck, fix-client-events #2):
 *
 *  - R3: sendDataOrRelay no longer swallows relay failures — callers mark the
 *    message failed so the UI never sits on「传输中…」for a delivery that died.
 *  - R4: signaling timeouts — pending/accepted/transferring messages parked
 *    past their window migrate to failed (previously: no timeout at all).
 *  - R5: late file-response / file-complete frames after a restart rebuild a
 *    minimal transfer from the persisted message instead of being dropped.
 */
class DropboxServiceSpec extends CatsEffectSuite:

  // Short signaling windows so timeout tests finish fast.
  private val OfferT     = 300.millis  // pending → file-response
  private val AcceptedT  = 400.millis  // accepted → upload starts
  private val TransferT  = 500.millis  // transferring → done

  private def peer(id: String): PeerInfo =
    PeerInfo(deviceId = id, deviceName = s"Device-$id", platform = "macos", address = "http://127.0.0.1:9")

  /** Persisted message of the given kind/transfer, or fail. */
  private def msgByTransfer(svc: DropboxService, deviceId: String, transferId: String): IO[DropboxMessage] =
    svc.getHistory(deviceId).map(_.find(m => m.kind == "file" && m.transferId == transferId))
      .map(_.getOrElse(fail(s"no file message with transferId=$transferId for $deviceId")))

  /** Events recorded from WsHub broadcasts (type + success fields). */
  private def fileCompleteEvents(seen: Ref[IO, List[Json]]): IO[List[(Boolean, String)]] =
    seen.get.map(_.flatMap { j =>
      val hc = j.hcursor
      if hc.downField("type").as[String].toOption.contains("dropbox-file-complete") then
        Some((hc.downField("msg").downField("success").as[Boolean].getOrElse(true),
              hc.downField("msg").downField("error").as[String].getOrElse("")))
      else None
    })

  private def withStack[A](use: (NeblinkService, DropboxService, Ref[IO, List[Json]]) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        prevRoot <- IO(PathUtil.dataRoot)
        tempDir <- IO.blocking(os.temp.dir(prefix = "nb-dropbox-spec-"))
        _ <- IO(PathUtil.setDataRoot(tempDir))
        ms <- NeblinkService.create(0, dispatcher)
        hub = new WsHub
        seen <- Ref.of[IO, List[Json]](Nil)
        regId <- hub.register(j => seen.update(_ :+ j))
        svc <- DropboxService.createForTest(ms, hub, OfferT, AcceptedT, TransferT)
        out <- use(ms, svc, seen)
        _ <- hub.unregister(regId)
        _ <- IO {
          PathUtil.setDataRoot(prevRoot)
          os.remove.all(tempDir)
        }
      yield out
    }

  // ===== R3: delivery failure is surfaced, not swallowed =====

  test("offerFile with no channel and no relay client marks the message failed (R3)") {
    withStack { (ms, svc, seen) =>
      for
        _ <- ms.upsertPeer(peer("peer1"))
        _ <- svc.offerFile("peer1", "a.txt", 123L, "text/plain")
        msg <- svc.getHistory("peer1").map(_.find(_.kind == "file"))
        events <- fileCompleteEvents(seen)
      yield
        assertEquals(msg.map(_.status), Some("failed"), "undeliverable offer must be marked failed, not pending")
        assert(events.exists(_._1 == false), "a dropbox-file-complete(success=false) must reach the frontend")
    }
  }

  test("relay delivery failure propagates through sendDataOrRelay (R3, relay path exercised)") {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val failingRelay = new NeblinkClient(
      NeblinkServerConfig(url = "http://127.0.0.1:9", networkId = "n1", secret = "s"), 0):
      override def relayNotify(targetDeviceId: String, channel: String, payload: Json): IO[Either[String, String]] =
        IO { calls.incrementAndGet() }.as(Left("relay tunnel missing"))
    withStack { (ms, svc, seen) =>
      for
        _ <- IO(ms.setRelayClient(Some(failingRelay)))
        _ <- ms.upsertPeer(peer("peer1"))
        _ <- svc.offerFile("peer1", "a.txt", 123L, "text/plain")
        msg <- svc.getHistory("peer1").map(_.find(_.kind == "file"))
      yield
        assertEquals(calls.get(), 1, "relay fallback must actually be attempted")
        assertEquals(msg.map(_.status), Some("failed"), "relay failure must surface as failed, not swallow")
    }
  }

  // ===== State machine happy path (regression) =====

  test("offerFile → pending; file-response flips the message to accepted (state migration intact)") {
    withStack { (ms, svc, seen) =>
      val okSend: (String, String, Json) => IO[Boolean] = (_, _, _) => IO.pure(true)
      for
        _ <- ms.setSendDataFn(okSend) // P2P channel "delivers"
        _ <- ms.upsertPeer(peer("peer1"))
        _ <- svc.offerFile("peer1", "a.txt", 123L, "text/plain")
        pending <- svc.getHistory("peer1").map(_.find(_.kind == "file"))
        transferId = pending.map(_.transferId).getOrElse("")
        _ <- ms.handleDataMessage(
          parse(s"""{"kind":"file-response","transferId":"$transferId","accepted":true}""").toOption.get)
        accepted <- msgByTransfer(svc, "peer1", transferId)
      yield
        assertEquals(pending.map(_.status), Some("pending"), "successful offer must be pending")
        assertEquals(accepted.status, "accepted", "file-response must migrate pending → accepted")
    }
  }

  // ===== R4: stuck states time out to failed =====

  test("offer parked in pending past the timeout window migrates to failed with an event (R4)") {
    withStack { (ms, svc, seen) =>
      val okSend: (String, String, Json) => IO[Boolean] = (_, _, _) => IO.pure(true)
      for
        _ <- ms.setSendDataFn(okSend)
        _ <- ms.upsertPeer(peer("peer1"))
        _ <- svc.offerFile("peer1", "a.txt", 123L, "text/plain")
        before <- svc.getHistory("peer1").map(_.find(_.kind == "file"))
        _ <- IO.sleep(OfferT + 300.millis) // let the watchdog fire
        after <- svc.getHistory("peer1").map(_.find(_.kind == "file"))
        events <- fileCompleteEvents(seen)
      yield
        assertEquals(before.map(_.status), Some("pending"))
        assertEquals(after.map(_.status), Some("failed"), "stuck pending must time out to failed")
        assert(events.exists(_._1 == false), "timeout must notify the frontend with a failure event")
    }
  }

  // ===== R5: late frames after restart rebuild from persisted messages =====

  test("file-complete arriving after a restart rebuilds the transfer and completes the message (R5)") {
    withStack { (ms, svc1, seen) =>
      // svc1 (process 1) receives a file-offer: persists an inbound file message.
      val offer = parse(
        """{"kind":"file-offer","senderId":"peer1","senderName":"P1","transferId":"t-restart",
           "msgId":"m-restart","fileName":"a.txt","fileSize":123,"mimeType":"text/plain"}""").toOption.get
      val complete = parse(
        """{"kind":"file-complete","transferId":"t-restart","msgId":"m-restart","success":true,"sha256":"x"}""").toOption.get
      for
        _ <- ms.handleDataMessage(offer)
        // "restart": a fresh DropboxService loads persisted messages but has an
        // empty transfersRef. It re-registers the data handler on the same
        // service (as a second process sharing the state would).
        svc2 <- DropboxService.createForTest(ms, new WsHub, OfferT, AcceptedT, TransferT)
        _ <- ms.handleDataMessage(complete)
        hist2 <- svc2.getHistory("peer1")
        rebuilt = hist2.find(_.transferId == "t-restart")
      yield
        assert(rebuilt.isDefined, "rebuilt instance must hold the persisted message")
        assertEquals(rebuilt.map(_.status), Some("completed"),
          "late file-complete must migrate the rebuilt message to completed (no more stuck『传输中…』)")
    }
  }

end DropboxServiceSpec
