package nebflow.server

import cats.effect.{IO, Ref}
import io.circe.syntax.*
import io.circe.parser.decode
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import nebflow.core.NebflowLogger

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// ===== Data Models =====

case class UserRecord(userId: String, username: String, passwordHash: String, createdAt: Long)
object UserRecord:
  given Encoder[UserRecord] = deriveEncoder
  given Decoder[UserRecord] = deriveDecoder

case class SessionRecord(userId: String, sessionToken: String, createdAt: Long, expiresAt: Long)
object SessionRecord:
  given Encoder[SessionRecord] = deriveEncoder
  given Decoder[SessionRecord] = deriveDecoder

case class DeviceRecord(
  deviceId: String,
  deviceName: String,
  platform: String,
  address: String,
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = "",
  deviceSecret: String = "",
  updatedAt: Long = 0,
  expiresAt: Long = 0
)
object DeviceRecord:
  given Encoder[DeviceRecord] = deriveEncoder
  given Decoder[DeviceRecord] = deriveDecoder

case class RelayRecord(
  relayId: String,
  userId: String,
  fromDeviceId: String,
  toDeviceId: String,
  action: String,
  params: Json,
  status: String = "pending", // pending | running | done | error
  result: String = "",
  error: String = "",
  createdAt: Long = 0,
  expiresAt: Long = 0
)
object RelayRecord:
  given Encoder[RelayRecord] = deriveEncoder
  given Decoder[RelayRecord] = deriveDecoder

/**
 * File-based storage for the relay server.
 * All state is kept in memory (Refs) for speed, and persisted to disk on every write.
 * Single-process design — sufficient for hundreds of concurrent users.
 */
class RelayStore private (
  dataDir: os.Path,
  usersRef: Ref[IO, Map[String, UserRecord]],        // username → UserRecord
  sessionsRef: Ref[IO, Map[String, SessionRecord]],   // sessionToken → SessionRecord
  devicesRef: Ref[IO, Map[String, List[DeviceRecord]]], // userId → devices
  relayRef: Ref[IO, List[RelayRecord]]                // all pending/running relay commands
):
  private val logger = NebflowLogger.forName("nebflow.server.store")

  private val SessionTtl = 30L * 24 * 60 * 60 * 1000  // 30 days
  private val RelayTtl = 30L * 60 * 1000               // 30 minutes

  // ===== Auth =====

  def checkUsername(username: String): IO[Boolean] =
    usersRef.get.map(!_.contains(username))

  def register(username: String, password: String): IO[Json] =
    for
      _ <- IO.raiseWhen(username.length < 3)(new RuntimeException("Username must be at least 3 characters"))
      _ <- IO.raiseWhen(!username.matches("^[a-zA-Z0-9_-]+$"))(
        new RuntimeException("Username can only contain letters, numbers, _ and -")
      )
      _ <- IO.raiseWhen(password.length < 6)(new RuntimeException("Password must be at least 6 characters"))
      existing <- usersRef.get
      _ <- IO.raiseWhen(existing.contains(username))(new RuntimeException("Username already taken"))
      userId = java.util.UUID.randomUUID().toString
      hash <- IO(hashPassword(password))
      now = System.currentTimeMillis()
      token = generateToken()
      user = UserRecord(userId, username, hash, now)
      session = SessionRecord(userId, token, now, now + SessionTtl)
      _ <- usersRef.update(_ + (username -> user))
      _ <- sessionsRef.update(_ + (token -> session))
      _ <- persistUsers()
      _ <- persistSessions()
    yield Json.obj("userId" -> userId.asJson, "username" -> username.asJson, "sessionToken" -> token.asJson)

  def login(username: String, password: String): IO[Json] =
    for
      users <- usersRef.get
      userOpt = users.get(username)
      _ <- IO.fromOption(userOpt)(new RuntimeException("Invalid username or password"))
      user = userOpt.get
      valid <- IO(verifyPassword(password, user.passwordHash))
      _ <- IO.raiseUnless(valid)(new RuntimeException("Invalid username or password"))
      token = generateToken()
      now = System.currentTimeMillis()
      session = SessionRecord(user.userId, token, now, now + SessionTtl)
      _ <- sessionsRef.update(_ + (token -> session))
      _ <- persistSessions()
    yield Json.obj("userId" -> user.userId.asJson, "username" -> user.username.asJson, "sessionToken" -> token.asJson)

  def verifySession(userId: String, sessionToken: String): IO[Unit] =
    for
      sessions <- sessionsRef.get
      _ <- IO.raiseUnless(sessions.contains(sessionToken))(new RuntimeException("Invalid session"))
      session = sessions(sessionToken)
      _ <- IO.raiseWhen(session.userId != userId)(new RuntimeException("Session userId mismatch"))
      _ <- IO.raiseWhen(System.currentTimeMillis() > session.expiresAt)(new RuntimeException("Session expired"))
    yield ()

  // ===== Discovery =====

  def registerDevice(
    userId: String, deviceId: String, deviceName: String, platform: String,
    address: String, capabilities: Map[String, String], userDescription: String, deviceSecret: String
  ): IO[Json] =
    val now = System.currentTimeMillis()
    val record = DeviceRecord(deviceId, deviceName, platform, address, capabilities, userDescription, deviceSecret, now, now + 7 * 24 * 60 * 60 * 1000L)
    for
      _ <- devicesRef.update { m =>
        val list = m.getOrElse(userId, Nil)
        val filtered = list.filterNot(_.deviceId == deviceId)
        m + (userId -> (filtered :+ record))
      }
      _ <- persistDevices(userId)
    yield Json.obj("ok" -> true.asJson)

  def lookupDevices(userId: String, excludeDeviceId: Option[String]): IO[Json] =
    for
      devices <- devicesRef.get
      now = System.currentTimeMillis()
      list = devices.getOrElse(userId, Nil).filter(_.expiresAt > now)
      filtered = excludeDeviceId match
        case Some(id) => list.filterNot(_.deviceId == id)
        case None => list
      peers = filtered.map(d => Json.obj(
        "deviceId" -> d.deviceId.asJson,
        "deviceName" -> d.deviceName.asJson,
        "platform" -> d.platform.asJson,
        "address" -> d.address.asJson,
        "capabilities" -> d.capabilities.asJson,
        "userDescription" -> d.userDescription.asJson,
        "deviceSecret" -> d.deviceSecret.asJson
      ))
    yield Json.obj("peers" -> peers.asJson)

  // ===== Relay =====

  def relaySubmit(userId: String, fromDeviceId: String, toDeviceId: String, action: String, params: Json): IO[Json] =
    val now = System.currentTimeMillis()
    val relayId = java.util.UUID.randomUUID().toString
    val record = RelayRecord(relayId, userId, fromDeviceId, toDeviceId, action, params, "pending", createdAt = now, expiresAt = now + RelayTtl)
    for
      _ <- relayRef.update(_ :+ record)
      _ <- persistRelay()
    yield Json.obj("relayId" -> relayId.asJson)

  def relayPoll(userId: String, deviceId: String): IO[Json] =
    val now = System.currentTimeMillis()
    for
      all <- relayRef.get
      pending = all.filter(r => r.userId == userId && r.toDeviceId == deviceId && r.status == "pending" && r.expiresAt > now)
      _ <- relayRef.update { list =>
        list.map { r =>
          if pending.exists(_.relayId == r.relayId) then r.copy(status = "running") else r
        }
      }
      _ <- persistRelay()
      commands = pending.map(c => Json.obj(
        "relayId" -> c.relayId.asJson,
        "fromDeviceId" -> c.fromDeviceId.asJson,
        "action" -> c.action.asJson,
        "params" -> c.params
      ))
    yield Json.obj("commands" -> commands.asJson)

  def relayResult(relayId: String, result: String, error: String): IO[Json] =
    for
      _ <- relayRef.update { list =>
        list.map { r =>
          if r.relayId == relayId then r.copy(status = if error.nonEmpty then "error" else "done", result = result, error = error)
          else r
        }
      }
      _ <- persistRelay()
    yield Json.obj("ok" -> true.asJson)

  def relayFetchResult(relayId: String): IO[Json] =
    relayRef.get.map { list =>
      list.find(_.relayId == relayId) match
        case Some(r) => Json.obj("status" -> r.status.asJson, "result" -> r.result.asJson, "error" -> r.error.asJson)
        case None => Json.obj("status" -> "not_found".asJson)
    }

  /** Get pending relay commands for a device (for WebSocket push). */
  def getPendingForDevice(userId: String, deviceId: String): IO[List[RelayRecord]] =
    val now = System.currentTimeMillis()
    relayRef.get.map { list =>
      list.filter(r => r.userId == userId && r.toDeviceId == deviceId && r.status == "pending" && r.expiresAt > now)
    }

  /** Mark a relay command as running (after WebSocket push). */
  def markRelayRunning(relayId: String): IO[Unit] =
    relayRef.update { list =>
      list.map(r => if r.relayId == relayId then r.copy(status = "running") else r)
    } *> persistRelay()

  // ===== Persistence =====

  private def persistUsers(): IO[Unit] = usersRef.get.flatMap { data =>
    IO.blocking {
      val json = data.map { case (k, v) => k -> v.asJson }.asJson
      os.write.over(dataDir / "users.json", json.spaces2, createFolders = true)
    }
  }

  private def persistSessions(): IO[Unit] = sessionsRef.get.flatMap { data =>
    IO.blocking {
      os.write.over(dataDir / "sessions.json", data.values.toList.asJson.spaces2, createFolders = true)
    }
  }

  private def persistDevices(userId: String): IO[Unit] = devicesRef.get.flatMap { data =>
    IO.blocking {
      val json = data.getOrElse(userId, Nil).asJson
      os.write.over(dataDir / "discovery" / s"$userId.json", json.spaces2, createFolders = true)
    }
  }

  private def persistRelay(): IO[Unit] = relayRef.get.flatMap { data =>
    IO.blocking {
      os.write.over(dataDir / "relay.json", data.asJson.spaces2, createFolders = true)
    }
  }

  // ===== Helpers =====

  private def generateToken(): String =
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    bytes.map(b => String.format("%02x", java.lang.Byte.valueOf(b))).mkString

  private def hashPassword(password: String): String =
    val random = new SecureRandom()
    val salt = new Array[Byte](16)
    random.nextBytes(salt)
    val spec = new PBEKeySpec(password.toCharArray, salt, 65536, 128)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val hash = factory.generateSecret(spec).getEncoded
    Base64.getEncoder.encodeToString(salt) + ":" + Base64.getEncoder.encodeToString(hash)

  private def verifyPassword(password: String, stored: String): Boolean =
    try
      val parts = stored.split(":", 2)
      if parts.length != 2 then false
      else
        val salt = Base64.getDecoder.decode(parts(0))
        val expectedHash = parts(1)
        val spec = new PBEKeySpec(password.toCharArray, salt, 65536, 128)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = Base64.getEncoder.encodeToString(factory.generateSecret(spec).getEncoded)
        hash == expectedHash
    catch case _: Exception => false

end RelayStore

object RelayStore:
  def create(dataDir: os.Path): IO[RelayStore] =
    for
      users <- loadMap[UserRecord](dataDir / "users.json", keyFrom = _.username)
      sessions <- loadList[SessionRecord](dataDir / "sessions.json").map(_.map(s => s.sessionToken -> s).toMap)
      devices <- loadAllDevices(dataDir / "discovery")
      relay <- loadList[RelayRecord](dataDir / "relay.json")
      u <- Ref.of[IO, Map[String, UserRecord]](users)
      s <- Ref.of[IO, Map[String, SessionRecord]](sessions)
      d <- Ref.of[IO, Map[String, List[DeviceRecord]]](devices)
      r <- Ref.of[IO, List[RelayRecord]](relay)
    yield new RelayStore(dataDir, u, s, d, r)

  private def loadMap[T: Decoder](path: os.Path, keyFrom: T => String): IO[Map[String, T]] =
    IO.blocking {
      if os.exists(path) then
        decode[Map[String, T]](os.read(path)).getOrElse(Map.empty)
      else Map.empty
    }

  private def loadList[T: Decoder](path: os.Path): IO[List[T]] =
    IO.blocking {
      if os.exists(path) then
        decode[List[T]](os.read(path)).getOrElse(Nil)
      else Nil
    }

  private def loadAllDevices(dir: os.Path): IO[Map[String, List[DeviceRecord]]] =
    IO.blocking {
      if os.exists(dir) then
        os.list(dir).filter(_.last.endsWith(".json")).map { f =>
          val userId = f.last.stripSuffix(".json")
          val devices = decode[List[DeviceRecord]](os.read(f)).getOrElse(Nil)
          userId -> devices
        }.toMap
      else Map.empty
    }.handleError(_ => Map.empty)

end RelayStore
