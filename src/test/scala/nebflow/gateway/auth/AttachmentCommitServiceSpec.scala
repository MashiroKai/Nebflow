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

  test("no principal, cross-account owner, and forged session selector fail closed"):
    for
      dir <- IO.blocking(Files.createTempDirectory("attachment-owner-deny-"))
      store = OwnerStore.at(dir.resolve("owners.json"))
      sessions <- GatewaySessionStore.create()
      ownerA <- sessions.create("account-A", GatewaySessionStore.OriginRelayMint, "test")
      ownerB <- sessions.create("account-B", GatewaySessionStore.OriginRelayMint, "test")
      _ <- store.registerSession(ownerA)
      _ <- store.registerSession(ownerB)
      accessA <- OwnerAccess.authenticate(request(ownerA.sessionId), "unused", sessions, store, GatewayAuth.Rails.ApiFull)
      accessB <- OwnerAccess.authenticate(request(ownerB.sessionId), "unused", sessions, store, GatewayAuth.Rails.ApiFull)
      bearer = OwnerAccess.fromAuthenticatedCredential(Credential.ViaBearer(isFileToken = false), store)
      canClaim <- bearer.createResource("attachment", "no-principal")
      forged <- accessA.get.ownsSession(ownerB.sessionId)
      allowed <- accessA.get.ownsSession(ownerA.sessionId)
      cross <- store.createResource("attachment", "cross-account", Credential.ViaSession(ownerB))
      ownsCross <- accessA.get.ownsResource("attachment", "cross-account")
      _ = assert(!canClaim, "Bearer has no owner principal")
      _ = assert(!forged, "a supplied session selector cannot substitute for the authenticated session")
      _ = assert(allowed)
      _ = assert(cross)
      _ = assert(!ownsCross, "cross-account resource must not be visible")
      _ = assertEquals(accessA.isDefined, true)
    yield ()

  test("failed temp write is cleaned and never registers or publishes"):
    for
      dir <- IO.blocking(Files.createTempDirectory("attachment-failure-"))
      store = OwnerStore.at(dir.resolve("owners.json"))
      sessions <- GatewaySessionStore.create()
      record <- sessions.create("account-A", GatewaySessionStore.OriginRelayMint, "test")
      _ <- store.registerSession(record)
      access <- OwnerAccess.authenticate(request(record.sessionId), "unused", sessions, store, GatewayAuth.Rails.ApiFull)
      ops = new AttachmentCommitService.FileOps:
        def createTemp(parent: java.nio.file.Path): java.nio.file.Path = Files.createTempFile(parent, ".test-", ".tmp")
        def copy(input: java.io.InputStream, temp: java.nio.file.Path): Unit = throw new java.io.IOException("injected write failure")
        def ownerOnly(path: java.nio.file.Path): Unit = ()
        def atomicMove(temp: java.nio.file.Path, target: java.nio.file.Path): Unit = Files.move(temp, target)
        def delete(path: java.nio.file.Path): Unit = Files.deleteIfExists(path)
      service = AttachmentCommitService.at(dir.resolve("attachments"), ops)
      result <- service.commit(access.get, ResourceKey.from("failure-file").toOption.get,
        new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))).attempt
      owner <- store.resolveResource("attachment", "failure-file")
      count <- IO.blocking {
        val stream = Files.list(dir.resolve("attachments"))
        try stream.count() finally stream.close()
      }
      _ = assert(result.isLeft)
      _ = assertEquals(owner, None)
      _ = assertEquals(count, 0L)
    yield ()

  test("invalid selectors cannot be created"):
    assertEquals(ResourceKey.from("bad/key"), Left("invalid resource key"))
