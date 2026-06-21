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
import scala.collection.mutable

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

case class SessionDataRecord(
  sessionId: String,
  messages: Json = Json.arr(),
  uiMessages: Json = Json.arr(),
  meta: Json = Json.obj(),
  updatedAt: Long = 0
)
object SessionDataRecord:
  given Encoder[SessionDataRecord] = deriveEncoder
  given Decoder[SessionDataRecord] = deriveDecoder

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

case class FileRecord(path: String, content: String, fingerprint: Json, updatedAt: Long)
object FileRecord:
  given Encoder[FileRecord] = deriveEncoder
  given Decoder[FileRecord] = deriveDecoder

case class BusyRecord(sessionId: String, deviceId: String, deviceName: String, expiresAt: Long)
object BusyRecord:
  given Encoder[BusyRecord] = deriveEncoder
  given Decoder[BusyRecord] = deriveDecoder

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
  sessionDataRef: Ref[IO, Map[String, Map[String, SessionDataRecord]]], // userId → sessionId → data
  indexRef: Ref[IO, Map[String, Json]],               // userId → session index JSON
  relayRef: Ref[IO, List[RelayRecord]],               // all pending/running relay commands
  filesRef: Ref[IO, Map[String, Map[String, FileRecord]]], // userId → path → FileRecord
  busyRef: Ref[IO, Map[String, Map[String, BusyRecord]]]   // userId → sessionId → BusyRecord
):
  private val logger = NebflowLogger.forName("nebflow.server.store")

  private val SessionTtl = 30L * 24 * 60 * 60 * 1000  // 30 days
  private val RelayTtl = 30L * 60 * 1000               // 30 minutes
  private val BusyTtl = 5L * 60 * 1000                  // 5 minutes

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

  // ===== Session Sync =====

  def pushIndex(userId: String, sessions: Json, folders: Json): IO[Json] =
    for
      existing <- indexRef.get.map(_.getOrElse(userId, Json.obj("sessions" -> Json.arr(), "folders" -> Json.arr())))
      existingSessions = existing.hcursor.downField("sessions").as[Json].getOrElse(Json.arr())
      existingFolders = existing.hcursor.downField("folders").as[Json].getOrElse(Json.arr())
      mergedSessions = mergeJsonArrays(existingSessions, sessions)
      mergedFolders = mergeJsonArrays(existingFolders, folders)
      merged = Json.obj("sessions" -> mergedSessions, "folders" -> mergedFolders, "updatedAt" -> System.currentTimeMillis().asJson)
      _ <- indexRef.update(_ + (userId -> merged))
      _ <- persistIndex(userId)
    yield Json.obj("ok" -> true.asJson)

  def pullIndex(userId: String): IO[Json] =
    indexRef.get.map { m =>
      m.getOrElse(userId, Json.obj("sessions" -> Json.arr(), "folders" -> Json.arr(), "updatedAt" -> 0.asJson))
    }

  def pushSession(userId: String, sessionId: String, messages: Json, uiMessages: Json, meta: Json): IO[Json] =
    val now = System.currentTimeMillis()
    val record = SessionDataRecord(sessionId, messages, uiMessages, meta, now)
    for
      _ <- sessionDataRef.update { m =>
        val userMap = m.getOrElse(userId, Map.empty)
        m + (userId -> (userMap + (sessionId -> record)))
      }
      _ <- persistSessionData(userId, sessionId)
    yield Json.obj("ok" -> true.asJson, "updatedAt" -> now.asJson)

  def pullSession(userId: String, sessionId: String): IO[Json] =
    sessionDataRef.get.map { m =>
      m.getOrElse(userId, Map.empty).get(sessionId) match
        case Some(r) => Json.obj(
          "found" -> true.asJson,
          "messages" -> r.messages,
          "uiMessages" -> r.uiMessages,
          "meta" -> r.meta,
          "updatedAt" -> r.updatedAt.asJson
        )
        case None => Json.obj("found" -> false.asJson)
    }

  def deleteSession(userId: String, sessionId: String): IO[Json] =
    for
      _ <- sessionDataRef.update { m =>
        val userMap = m.getOrElse(userId, Map.empty)
        m + (userId -> userMap.removed(sessionId))
      }
      _ <- busyRef.update { m =>
        val userMap = m.getOrElse(userId, Map.empty)
        m + (userId -> userMap.removed(sessionId))
      }
      // Remove from index
      _ <- indexRef.update { m =>
        m.get(userId) match
          case Some(idx) =>
            val sessions = idx.hcursor.downField("sessions").as[List[Json]].getOrElse(Nil)
            val filtered = sessions.filterNot(_.hcursor.downField("id").as[String].getOrElse("") == sessionId)
            m + (userId -> idx.deepMerge(Json.obj("sessions" -> filtered.asJson)))
          case None => m
      }
      _ <- persistSessionData(userId, sessionId)
      _ <- persistIndex(userId)
    yield Json.obj("ok" -> true.asJson)

  // ===== Busy Lock =====

  def sessionBusy(userId: String, sessionId: String, busy: Option[Boolean], deviceId: String, deviceName: String): IO[Json] =
    val now = System.currentTimeMillis()
    for
      busyMap <- busyRef.get.map(_.getOrElse(userId, Map.empty).filter(_._2.expiresAt > now))
      current = busyMap.get(sessionId)
      result <- busy match
        case None => // Query
          IO.pure(current match
            case Some(b) => Json.obj("busy" -> true.asJson, "busyDeviceId" -> b.deviceId.asJson, "busyDeviceName" -> b.deviceName.asJson)
            case None => Json.obj("busy" -> false.asJson, "busyDeviceId" -> Json.Null, "busyDeviceName" -> Json.Null)
          )
        case Some(true) => // Acquire
          current match
            case Some(b) if b.deviceId != deviceId =>
              IO.pure(Json.obj("busy" -> true.asJson, "busyDeviceId" -> b.deviceId.asJson, "busyDeviceName" -> b.deviceName.asJson, "acquired" -> false.asJson))
            case _ =>
              val record = BusyRecord(sessionId, deviceId, deviceName, now + BusyTtl)
              for
                _ <- busyRef.update(m => m + (userId -> (busyMap + (sessionId -> record))))
              yield Json.obj("busy" -> true.asJson, "busyDeviceId" -> deviceId.asJson, "busyDeviceName" -> deviceName.asJson, "acquired" -> true.asJson)
        case Some(false) => // Release
          current match
            case Some(b) if b.deviceId == deviceId =>
              for
                _ <- busyRef.update(m => m + (userId -> busyMap.removed(sessionId)))
              yield Json.obj("busy" -> false.asJson, "busyDeviceId" -> Json.Null, "busyDeviceName" -> Json.Null, "acquired" -> true.asJson)
            case _ =>
              IO.pure(Json.obj("busy" -> false.asJson, "busyDeviceId" -> Json.Null, "busyDeviceName" -> Json.Null))
    yield result

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

  // ===== File Sync =====

  def fileSync(userId: String, fingerprints: Json): IO[Json] =
    for
      userFiles <- filesRef.get.map(_.getOrElse(userId, Map.empty))
      localFps = fingerprints.asObject.map(_.toMap).getOrElse(Map.empty)
      download = scala.collection.mutable.ListBuffer.empty[Json]
      uploadNeeded = scala.collection.mutable.ListBuffer.empty[String]
      _ = localFps.foreach { case (path, localFp) =>
        val cloudOpt = userFiles.get(path)
        cloudOpt match
          case None => uploadNeeded += path
          case Some(cloud) =>
            val localHash = localFp.hcursor.downField("hash").as[String].getOrElse("")
            val cloudHash = cloud.fingerprint.hcursor.downField("hash").as[String].getOrElse("")
            if localHash == cloudHash then () // unchanged
            else
              val localMtime = localFp.hcursor.downField("mtime").as[Long].getOrElse(0L)
              val cloudMtime = cloud.fingerprint.hcursor.downField("mtime").as[Long].getOrElse(0L)
              if localMtime > cloudMtime then uploadNeeded += path
              else download += Json.obj("path" -> path.asJson, "fileID" -> path.asJson)
      }
      _ = userFiles.foreach { case (path, _) =>
        if !localFps.contains(path) then download += Json.obj("path" -> path.asJson, "fileID" -> path.asJson)
      }
    yield Json.obj(
      "download" -> download.result.asJson,
      "uploadNeeded" -> uploadNeeded.result.asJson
    )

  def fileUpload(userId: String, path: String, content: String, fingerprint: Json): IO[Json] =
    val record = FileRecord(path, content, fingerprint, System.currentTimeMillis())
    for
      _ <- filesRef.update { m =>
        val userMap = m.getOrElse(userId, Map.empty)
        m + (userId -> (userMap + (path -> record)))
      }
      _ <- persistFiles(userId)
    yield Json.obj("ok" -> true.asJson, "size" -> Base64.getDecoder.decode(content).length.asJson)

  def fileDownload(userId: String, fileID: String): IO[Json] =
    filesRef.get.map { m =>
      m.getOrElse(userId, Map.empty).get(fileID) match
        case Some(r) => Json.obj("content" -> r.content.asJson)
        case None => Json.obj("content" -> "".asJson)
    }

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

  private def persistSessionData(userId: String, sessionId: String): IO[Unit] = sessionDataRef.get.flatMap { data =>
    IO.blocking {
      val dir = dataDir / "session_data" / userId
      val json = data.getOrElse(userId, Map.empty).asJson
      os.write.over(dir / "_all.json", json.spaces2, createFolders = true)
    }
  }

  private def persistIndex(userId: String): IO[Unit] = indexRef.get.flatMap { data =>
    IO.blocking {
      val dir = dataDir / "index"
      val json = data.getOrElse(userId, Json.obj())
      os.write.over(dir / s"$userId.json", json.spaces2, createFolders = true)
    }
  }

  private def persistRelay(): IO[Unit] = relayRef.get.flatMap { data =>
    IO.blocking {
      os.write.over(dataDir / "relay.json", data.asJson.spaces2, createFolders = true)
    }
  }

  private def persistFiles(userId: String): IO[Unit] = filesRef.get.flatMap { data =>
    IO.blocking {
      val dir = dataDir / "files" / userId
      val json = data.getOrElse(userId, Map.empty).asJson
      os.write.over(dir / "_all.json", json.spaces2, createFolders = true)
    }
  }

  // ===== Helpers =====

  private def mergeJsonArrays(a: Json, b: Json): Json =
    val aList = a.asArray.map(_.toList).getOrElse(List.empty[Json])
    val bList = b.asArray.map(_.toList).getOrElse(List.empty[Json])
    val aIds = aList.flatMap(_.hcursor.downField("id").as[String].toOption).toSet
    val merged = aList ++ bList.filter { item =>
      item.hcursor.downField("id").as[String].toOption match
        case Some(id) => !aIds.contains(id)
        case None => true
    }
    merged.asJson

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
      sessionData <- loadAllSessionData(dataDir / "session_data")
      indices <- loadAllIndices(dataDir / "index")
      relay <- loadList[RelayRecord](dataDir / "relay.json")
      files <- loadAllFiles(dataDir / "files")
      busy = Map.empty[String, Map[String, BusyRecord]] // in-memory only, auto-expires
      u <- Ref.of[IO, Map[String, UserRecord]](users)
      s <- Ref.of[IO, Map[String, SessionRecord]](sessions)
      d <- Ref.of[IO, Map[String, List[DeviceRecord]]](devices)
      sd <- Ref.of[IO, Map[String, Map[String, SessionDataRecord]]](sessionData)
      i <- Ref.of[IO, Map[String, Json]](indices)
      r <- Ref.of[IO, List[RelayRecord]](relay)
      f <- Ref.of[IO, Map[String, Map[String, FileRecord]]](files)
      b <- Ref.of[IO, Map[String, Map[String, BusyRecord]]](busy)
    yield new RelayStore(dataDir, u, s, d, sd, i, r, f, b)

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

  private def loadAllSessionData(dir: os.Path): IO[Map[String, Map[String, SessionDataRecord]]] =
    IO.blocking {
      if os.exists(dir) then
        os.list(dir).filter(os.isDir).map { userDir =>
          val userId = userDir.last
          val all = os.read(userDir / "_all.json")
          val data = decode[Map[String, SessionDataRecord]](all).getOrElse(Map.empty)
          userId -> data
        }.toMap
      else Map.empty
    }.handleError(_ => Map.empty)

  private def loadAllIndices(dir: os.Path): IO[Map[String, Json]] =
    IO.blocking {
      if os.exists(dir) then
        os.list(dir).filter(_.last.endsWith(".json")).map { f =>
          val userId = f.last.stripSuffix(".json")
          val json = decode[Json](os.read(f)).getOrElse(Json.obj())
          userId -> json
        }.toMap
      else Map.empty
    }.handleError(_ => Map.empty)

  private def loadAllFiles(dir: os.Path): IO[Map[String, Map[String, FileRecord]]] =
    IO.blocking {
      if os.exists(dir) then
        os.list(dir).filter(os.isDir).map { userDir =>
          val userId = userDir.last
          val data = decode[Map[String, FileRecord]](os.read(userDir / "_all.json")).getOrElse(Map.empty)
          userId -> data
        }.toMap
      else Map.empty
    }.handleError(_ => Map.empty)

end RelayStore
