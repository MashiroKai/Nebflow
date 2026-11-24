package nebflow.gateway

import cats.effect.IO
import io.circe.{Json, parser}
import io.circe.syntax.*

import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Authoritative, fail-closed owner registry for resources. Missing rows are Unknown. */
final class OwnerStore private (file: Path):
  import OwnerStore.*

  private def read(): Map[String, String] =
    if !Files.exists(file) then Map.empty
    else
      val raw = Files.readString(file)
      parser
        .parse(raw)
        .toOption
        .flatMap(_.hcursor.get[Int]("version").toOption match
          case Some(Version) =>
            parser.parse(raw).toOption.flatMap(_.hcursor.get[Map[String, String]]("owners").toOption)
          case _ => None)
        .getOrElse(throw new IllegalStateException("invalid owner registry snapshot"))

  private def persist(rows: Map[String, String]): Unit =
    val parent = Option(file.getParent).getOrElse(file.toAbsolutePath.getParent)
    Files.createDirectories(parent)
    val temp = Files.createTempFile(parent, ".owners-", ".tmp")
    try
      Files.writeString(temp, Json.obj("version" -> Version.asJson, "owners" -> rows.asJson).noSpaces)
      try
        Files.setPosixFilePermissions(temp, Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).asJava)
      catch case _: UnsupportedOperationException => ()
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temp)

  /** Unregistered/invalid storage always resolves to no owner. */
  def resolve(key: String): IO[Option[String]] = IO.blocking(synchronized {
    try read().get(key).filter(GatewayOwnership.isKnownOwner)
    catch case NonFatal(_) => None
  })

  /** Idempotent create; ownership is derived only from an authenticated session credential. */
  def createOwned(key: String, credential: Credential): IO[Boolean] = IO.blocking(synchronized {
    val accountSub = GatewayOwnership.principal(credential) match
      case GatewayOwnership.Account(sub) => Some(sub)
      case GatewayOwnership.Unknown => None
    if !isValidKey(key) || accountSub.isEmpty then false
    else
      try
        val rows = read()
        rows.get(key) match
          case Some(owner) => accountSub.contains(owner)
          case None => persist(rows.updated(key, accountSub.get)); true
      catch case NonFatal(_) => false
  })

  /** Stable typed keys. IDs select a row only; they never establish its owner. */
  def sessionKey(sessionId: String): String = typedKey("session", sessionId)
  def fileKey(canonicalResourceId: String): String = typedKey("file", canonicalResourceId)
  def resourceKey(kind: String, resourceId: String): String = typedKey(s"resource:$kind", resourceId)

  private def typedKey(kind: String, id: String): String =
    if id == null || id.trim.isEmpty then "" else s"$kind:$id"

  private def isValidKey(key: String): Boolean =
    key != null && key.contains(":") && key == key.trim && key.nonEmpty

  /** Resource lifecycle boundary: create, resolve and copy preserve trusted owner data. */
  def createResource(kind: String, resourceId: String, credential: Credential): IO[Boolean] =
    createOwned(resourceKey(kind, resourceId), credential)

  def resolveResource(kind: String, resourceId: String): IO[Option[String]] =
    resolve(resourceKey(kind, resourceId))

  def copyResource(kind: String, sourceId: String, targetId: String, credential: Credential): IO[Boolean] =
    copyOwned(resourceKey(kind, sourceId), resourceKey(kind, targetId), credential)

  /** Establish the authoritative mapping from an authenticated server session record. */
  def registerSession(record: SessionRecord): IO[Boolean] =
    createOwned(sessionKey(record.sessionId), Credential.ViaSession(record))

  /** Verify an authenticated session against this registry (never infer from requested ID). */
  def ownsSession(credential: Credential, requestedSessionId: String): IO[Boolean] =
    resolve(sessionKey(requestedSessionId)).map(GatewayOwnership.owns(credential, _))

  /** Copy only a known source owner, after authenticating that same owner. */
  def copyOwned(source: String, target: String, credential: Credential): IO[Boolean] = IO.blocking(synchronized {
    try
      val rows = read()
      rows.get(source).filter(GatewayOwnership.isKnownOwner) match
        case None => false
        case Some(owner) if !isValidKey(target) => false
        case Some(owner) if !GatewayOwnership.owns(credential, Some(owner)) => false
        case Some(owner) if rows.contains(target) => rows(target) == owner
        case Some(owner) => persist(rows.updated(target, owner)); true
    catch case NonFatal(_) => false
  })

  /** Permanently isolate a known resource as unknown by removing its owner row. */
  def markUnknown(key: String): IO[Unit] = IO.blocking(synchronized {
    try
      val rows = read()
      if rows.contains(key) then persist(rows - key)
    catch case NonFatal(_) => ()
  })

end OwnerStore

object OwnerStore:
  private val Version = 1
  def at(file: Path): OwnerStore = new OwnerStore(file.toAbsolutePath.normalize())
  def forDataRoot(dataRoot: Path): OwnerStore = at(dataRoot.resolve("gateway-owners.json"))
