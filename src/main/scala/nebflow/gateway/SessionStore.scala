package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.shared.{Message, given}

import java.util.UUID

case class SessionMeta(
  id: String,
  name: String,
  createdAt: Long,
  updatedAt: Long,
  hasUnread: Boolean,
  agentName: Option[String] = None
)

object SessionMeta:

  given Encoder[SessionMeta] = Encoder.instance { m =>
    val base = Json.obj(
      "id" -> m.id.asJson,
      "name" -> m.name.asJson,
      "createdAt" -> m.createdAt.asJson,
      "updatedAt" -> m.updatedAt.asJson,
      "hasUnread" -> m.hasUnread.asJson
    )
    m.agentName.fold(base)(n => base.deepMerge(Json.obj("agentName" -> n.asJson)))
  }

  given Decoder[SessionMeta] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      name <- c.downField("name").as[String]
      createdAt <- c.downField("createdAt").as[Long]
      updatedAt <- c.downField("updatedAt").as[Long]
      hasUnread <- c.downField("hasUnread").as[Option[Boolean]].map(_.getOrElse(false))
      agentName <- c.downField("agentName").as[Option[String]]
    yield SessionMeta(id, name, createdAt, updatedAt, hasUnread, agentName)
  }

class SessionStore(sessionsDir: os.Path):
  private val logger = nebflow.core.NebflowLogger.forName("nebflow.session")

  // (activeId, metas sorted by updatedAt desc)
  private val indexRef: Ref[IO, (String, List[SessionMeta])] =
    Ref.unsafe[IO, (String, List[SessionMeta])]("", Nil)

  // Only active session's messages in memory
  private val activeMessagesRef: Ref[IO, List[Message]] =
    Ref.unsafe[IO, List[Message]](Nil)

  private val indexFile = sessionsDir / "_index.json"

  def load: IO[Unit] =
    IO.blocking {
      if !os.exists(sessionsDir) then os.makeDir.all(sessionsDir)
    } *> (
      if os.exists(indexFile) then loadFromIndex
      else migrateFromLegacy
    )

  private def loadFromIndex: IO[Unit] =
    IO.blocking {
      val raw = os.read(indexFile)
      val json = decode[Json](raw).getOrElse(Json.obj())
      val activeId = json.hcursor.downField("activeId").as[String].getOrElse("")
      val sessions = json.hcursor.downField("sessions").as[List[SessionMeta]].getOrElse(Nil)
      (activeId, sessions)
    }.flatMap { case (activeId, sessions) =>
      if activeId.nonEmpty && sessions.exists(_.id == activeId) then
        loadSessionMessages(activeId).flatMap { msgs =>
          indexRef.set((activeId, sessions)) *> activeMessagesRef.set(msgs)
        }
      else if sessions.nonEmpty then
        // Fallback: use most recent session
        val first = sessions.maxByOption(_.updatedAt).get
        loadSessionMessages(first.id).flatMap { msgs =>
          indexRef.set((first.id, sessions)) *> activeMessagesRef.set(msgs)
        }
      else
        // No sessions at all — create default
        createDefaultSession
    }

  private def migrateFromLegacy: IO[Unit] =
    val legacyPath = sessionsDir / "web.json"
    IO.blocking {
      if os.exists(legacyPath) then Some(os.read(legacyPath))
      else None
    }.flatMap {
      case Some(raw) =>
        val msgs = decode[List[Message]](raw).getOrElse(Nil)
        val id = UUID.randomUUID().toString
        val now = System.currentTimeMillis()
        val meta = SessionMeta(id, "Default Session", now, now, hasUnread = false)
        IO.blocking {
          os.write.over(sessionFile(id), msgs.asJson.spaces2, createFolders = true)
        } *> indexRef.set((id, List(meta))) *> activeMessagesRef.set(msgs) *> saveIndex
      case None =>
        createDefaultSession
    }

  private def createDefaultSession: IO[Unit] =
    val id = UUID.randomUUID().toString
    val now = System.currentTimeMillis()
    val meta = SessionMeta(id, "Default Session", now, now, hasUnread = false)
    indexRef.set((id, List(meta))) *> activeMessagesRef.set(Nil) *>
      IO.blocking(os.write.over(sessionFile(id), "[]", createFolders = true)) *> saveIndex

  private def sessionFile(id: String): os.Path = sessionsDir / s"$id.json"

  private def loadSessionMessages(id: String): IO[List[Message]] =
    IO.blocking {
      val f = sessionFile(id)
      if os.exists(f) then decode[List[Message]](os.read(f)).getOrElse(Nil)
      else Nil
    }

  /**
   * Load messages for a specific session directly from disk (bypasses activeMessagesRef).
   *  Used by SessionActor to safely start REPL for non-active sessions.
   */
  def loadMessagesForSession(id: String): IO[List[Message]] = loadSessionMessages(id)

  private def saveSessionMessages(id: String, msgs: List[Message]): IO[Unit] =
    IO.blocking(os.write.over(sessionFile(id), msgs.asJson.spaces2, createFolders = true))

  private def saveIndex: IO[Unit] =
    indexRef.get.flatMap { case (activeId, sessions) =>
      IO.blocking {
        val json = Json.obj(
          "activeId" -> activeId.asJson,
          "sessions" -> sessions.asJson
        )
        os.write.over(indexFile, json.spaces2, createFolders = true)
      }
    }

  def getActiveMessages: IO[List[Message]] = activeMessagesRef.get

  def setActiveMessages(msgs: List[Message]): IO[Unit] =
    indexRef.get.flatMap { case (activeId, sessions) =>
      val now = System.currentTimeMillis()
      val updated = sessions.map(s => if s.id == activeId then s.copy(updatedAt = now) else s)
      activeMessagesRef.set(msgs) *>
        saveSessionMessages(activeId, msgs) *>
        indexRef.set((activeId, updated))
    // Don't save index on every message to reduce disk writes — save periodically or on switch
    }

  /**
   * Save messages to a specific session (not necessarily the active one).
   *  Updates the active ref only if the target is still the active session.
   */
  def saveMessagesForSession(targetId: String, msgs: List[Message]): IO[Unit] =
    indexRef.get.flatMap { case (activeId, sessions) =>
      val now = System.currentTimeMillis()
      val updated = sessions.map(s => if s.id == targetId then s.copy(updatedAt = now) else s)
      val updateRef = if targetId == activeId then activeMessagesRef.set(msgs) else IO.unit
      updateRef *> saveSessionMessages(targetId, msgs) *> indexRef.set((activeId, updated))
    }

  def flushIndex: IO[Unit] = saveIndex

  def listSessions: IO[List[SessionMeta]] =
    indexRef.get.map { case (_, sessions) => sessions.sortBy(-_.updatedAt) }

  def getActiveId: IO[String] = indexRef.get.map(_._1)

  def getActiveMeta: IO[Option[SessionMeta]] =
    indexRef.get.map { case (activeId, sessions) => sessions.find(_.id == activeId) }

  def getSessionMeta(id: String): IO[Option[SessionMeta]] =
    indexRef.get.map { case (_, sessions) => sessions.find(_.id == id) }

  def switchSession(id: String): IO[List[Message]] =
    // Use modify for atomic read-modify-write to prevent concurrent corruption.
    // Returns: Right(oldId) for switch, Left(None) for same-session, Left(Some(err)) for not-found
    indexRef
      .modify { case (currentId, sessions) =>
        if id == currentId then ((currentId, sessions), Left(Option.empty[String]))
        else
          sessions.find(_.id == id) match
            case None => ((currentId, sessions), Left(Some(s"Session $id not found")))
            case Some(_) =>
              val updated = sessions.map(s =>
                if s.id == id then s.copy(hasUnread = false)
                else s
              )
              ((id, updated), Right(currentId))
      }
      .flatMap {
        case Left(None) => activeMessagesRef.get
        case Left(Some(err)) => IO.raiseError(new RuntimeException(err))
        case Right(oldId) =>
          activeMessagesRef.get.flatMap { currentMsgs =>
            saveSessionMessages(oldId, currentMsgs) *> loadSessionMessages(id).flatMap { newMsgs =>
              activeMessagesRef.set(newMsgs) *> saveIndex *> newMsgs.pure[IO]
            }
          }
      }

  def createSession(name: String, initialMsgs: List[Message] = Nil, agentName: Option[String] = None): IO[SessionMeta] =
    val id = UUID.randomUUID().toString
    val now = System.currentTimeMillis()
    val meta = SessionMeta(id, name, now, now, hasUnread = true, agentName = agentName)
    indexRef.get.flatMap { case (activeId, sessions) =>
      saveSessionMessages(id, initialMsgs) *>
        indexRef.set((activeId, meta :: sessions)) *> saveIndex *> meta.pure[IO]
    }

  def deleteSession(id: String): IO[Unit] =
    indexRef
      .modify { case (activeId, sessions) =>
        val updated = sessions.filterNot(_.id == id)
        val newActiveId =
          if id == activeId then updated.maxByOption(_.updatedAt).map(_.id).getOrElse("")
          else activeId
        ((newActiveId, updated), (id == activeId, newActiveId))
      }
      .flatMap { case (wasActive, newActiveId) =>
        IO.blocking {
          val f = sessionFile(id)
          if os.exists(f) then os.remove(f)
        } *> (if wasActive && newActiveId.nonEmpty then
                loadSessionMessages(newActiveId).flatMap(msgs => activeMessagesRef.set(msgs))
              else IO.unit) *> saveIndex
      }

  def markUnread(id: String): IO[Unit] =
    indexRef.update { case (activeId, sessions) =>
      val updated = sessions.map(s => if s.id == id then s.copy(hasUnread = true) else s)
      (activeId, updated)
    } *> saveIndex

  def renameSession(id: String, newName: String): IO[Unit] =
    indexRef.update { case (activeId, sessions) =>
      val updated = sessions.map(s => if s.id == id then s.copy(name = newName) else s)
      (activeId, updated)
    } *> saveIndex
end SessionStore
