package nebflow.gateway.auth

import nebflow.gateway.*

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{Request, Uri}

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class AttachmentCommitServiceSpec extends CatsEffectSuite:
  private def request(sessionId: String): Request[IO] =
    Request[IO](uri = Uri.unsafeFromString("/"))
      .addCookie(GatewaySessionStore.CookieName, sessionId)

  test("commit publishes only after trusted owner registration; rejects unknown owner and path keys"):
    for
      dir <- IO.blocking(Files.createTempDirectory("attachment-commit-spec-"))
      store = OwnerStore.at(dir.resolve("owners.json"))
      sessions <- GatewaySessionStore.create()
      ownedRecord <- sessions.create("account-A", GatewaySessionStore.OriginRelayMint, "test")
      unknownRecord <- sessions.create("unknown", GatewaySessionStore.OriginRelayMint, "test")
      _ <- store.registerSession(ownedRecord)
      _ <- store.registerSession(unknownRecord)
      access <- OwnerAccess.authenticate(request(ownedRecord.sessionId), "unused-token", sessions, store, GatewayAuth.Rails.ApiFull)
      unknownAccess <- OwnerAccess.authenticate(request(unknownRecord.sessionId), "unused-token", sessions, store, GatewayAuth.Rails.ApiFull)
      key = ResourceKey.from("resource-1").toOption.get
      service = AttachmentCommitService.at(dir.resolve("attachments"))
      published <- service.commit(access.get, key, new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)))
      owner <- store.resolveResource("attachment", "resource-1")
      bytes <- IO.blocking(Files.readString(published))
      unknownResult <- service.commit(unknownAccess.get, ResourceKey.from("unknown-file").toOption.get,
        new ByteArrayInputStream("not-published".getBytes(StandardCharsets.UTF_8))).attempt
      unsafe <- IO.pure(ResourceKey.from("../escape"))
      _ = assertEquals(owner, Some("account-A"))
      _ = assertEquals(bytes, "payload")
      _ = assert(unknownResult.isLeft)
      _ = assert(!Files.exists(dir.resolve("attachments/unknown-file")))
      _ = assert(unsafe.isLeft)
      _ <- IO.blocking {
        val stream = Files.list(dir.resolve("attachments"))
        try assertEquals(stream.count(), 1L) finally stream.close()
      }
    yield ()

  test("missing typed access cannot be used to commit"):
    assertEquals(ResourceKey.from("bad/key"), Left("invalid resource key"))
