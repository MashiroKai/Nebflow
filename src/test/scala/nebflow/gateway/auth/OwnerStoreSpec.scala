package nebflow.gateway.auth

import nebflow.gateway.{Credential, GatewaySessionStore, OwnerStore}

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.Files

class OwnerStoreSpec extends CatsEffectSuite:
  test("temporary owner registry persists trusted owners and leaves legacy resources unknown"):
    val dir = Files.createTempDirectory("owner-store-spec-")
    val file = dir.resolve("owners.json")
    val store = OwnerStore.at(file)
    for
      before <- store.resolve("legacy/session/file")
      ownerSession <- GatewaySessionStore.create().flatMap(_.create("account-A", GatewaySessionStore.OriginRelayMint, "test"))
      otherSession <- GatewaySessionStore.create().flatMap(_.create("account-B", GatewaySessionStore.OriginRelayMint, "test"))
      created <- store.createResource("attachment", "resource-1", Credential.ViaSession(ownerSession))
      owner <- store.resolveResource("attachment", "resource-1")
      conflict <- store.createResource("attachment", "resource-1", Credential.ViaSession(otherSession))
      copiedUnknown <- store.copyResource("attachment", "legacy-id", "copy-unknown", Credential.ViaSession(ownerSession))
      copiedWrongOwner <- store.copyResource("attachment", "resource-1", "copy-wrong", Credential.ViaSession(otherSession))
      copiedKnown <- store.copyResource("attachment", "resource-1", "copy", Credential.ViaSession(ownerSession))
      copyOwner <- store.resolveResource("attachment", "copy")
      invalidKey <- store.createResource("attachment", " ", Credential.ViaSession(ownerSession))
      _ = assertEquals(before, None)
      _ = assert(created)
      _ = assertEquals(owner, Some("account-A"))
      _ = assert(!conflict)
      _ = assert(!copiedUnknown)
      _ = assert(!copiedWrongOwner)
      _ = assert(copiedKnown)
      _ = assert(!invalidKey)
      _ = assertEquals(copyOwner, Some("account-A"))
      _ <- IO.blocking(Files.writeString(file, "{not-json"))
      corrupt <- store.resolve("uploads/sid/file.png")
      rejected <- store.createOwned("file:new", Credential.ViaSession(ownerSession))
      _ = assertEquals(corrupt, None)
      _ = assert(!rejected)
    yield ()

  test("owner identity rejects unknown placeholder and bearer credentials"):
    val dir = Files.createTempDirectory("owner-store-auth-spec-")
    val store = OwnerStore.at(dir.resolve("owners.json"))
    for
      record <- GatewaySessionStore.create().flatMap(_.create("unknown", GatewaySessionStore.OriginRelayMint, "test"))
      sessionOwnerCreated <- store.createOwned("x", Credential.ViaSession(record))
      bearerOwnerCreated <- store.createOwned("x", Credential.ViaBearer(isFileToken = true))
      missing <- store.resolve("x")
      _ = assert(!sessionOwnerCreated)
      _ = assert(!bearerOwnerCreated)
      _ = assertEquals(missing, None)
    yield ()
