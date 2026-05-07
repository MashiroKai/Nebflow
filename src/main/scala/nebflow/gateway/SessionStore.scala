package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.shared.{Message, UiMessage, given}

import java.util.UUID

case class SessionMeta(
  id: String,
  name: String,
  createdAt: Long,
  updatedAt: Long,
  hasUnread: Boolean,
  agentName: Option[String] = None,
  modelRef: Option[String] = None
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
    val withAgent = m.agentName.fold(base)(n => base.deepMerge(Json.obj("agentName" -> n.asJson)))
    m.modelRef.fold(withAgent)(r => withAgent.deepMerge(Json.obj("modelRef" -> r.asJson)))
  }

  given Decoder[SessionMeta] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      name <- c.downField("name").as[String]
      createdAt <- c.downField("createdAt").as[Long]
      updatedAt <- c.downField("updatedAt").as[Long]
      hasUnread <- c.downField("hasUnread").as[Option[Boolean]].map(_.getOrElse(false))
      agentName <- c.downField("agentName").as[Option[String]]
      modelRef <- c.downField("modelRef").as[Option[String]]
    yield SessionMeta(id, name, createdAt, updatedAt, hasUnread, agentName, modelRef)
  }

end SessionMeta

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
        sessions.maxByOption(_.updatedAt) match
          case Some(first) =>
            loadSessionMessages(first.id).flatMap { msgs =>
              indexRef.set((first.id, sessions)) *> activeMessagesRef.set(msgs)
            }
          case None => createDefaultSession
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
   * Used to load history when creating a root agent for a non-active session.
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
    val meta = SessionMeta(id, name, now, now, hasUnread = false, agentName = agentName)
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
        } *> deleteUiMessages(id) *> (if wasActive && newActiveId.nonEmpty then
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

  def updateSessionModel(id: String, modelRef: Option[String]): IO[Unit] =
    indexRef.update { case (activeId, sessions) =>
      val updated = sessions.map(s => if s.id == id then s.copy(modelRef = modelRef) else s)
      (activeId, updated)
    } *> saveIndex

  // ============================================================
  // UI Messages (frontend rendering history)
  // ============================================================

  private def uiFile(id: String): os.Path = sessionsDir / s"$id.ui.json"

  private def loadUiMessages(id: String): IO[List[UiMessage]] =
    IO.blocking {
      val f = uiFile(id)
      if os.exists(f) then decode[List[UiMessage]](os.read(f)).getOrElse(Nil)
      else Nil
    }

  private def saveUiMessages(id: String, msgs: List[UiMessage]): IO[Unit] =
    IO.blocking(os.write.over(uiFile(id), msgs.asJson.noSpaces, createFolders = true))

  /** Append UI messages to a session. Creates the file if it doesn't exist. */
  def appendUiMessages(sessionId: String, msgs: List[UiMessage]): IO[Unit] =
    if msgs.isEmpty then IO.unit
    else
      loadUiMessages(sessionId).flatMap { existing =>
        saveUiMessages(sessionId, existing ++ msgs)
      }

  /**
   * Get a page of UI messages for a session.
   * Returns (messages_in_order, totalCount).
   * Use offset/limit for forward pagination from oldest.
   * For "load latest", pass offset = max(0, total - limit).
   */
  def getUiMessages(sessionId: String, offset: Int, limit: Int): IO[(List[UiMessage], Int)] =
    loadUiMessages(sessionId).map { all =>
      val total = all.size
      val start = Math.min(offset, total)
      val end = Math.min(start + limit, total)
      (all.slice(start, end), total)
    }

  /** Delete UI messages file when session is deleted. Already called from deleteSession. */
  def deleteUiMessages(sessionId: String): IO[Unit] =
    IO.blocking {
      val f = uiFile(sessionId)
      if os.exists(f) then os.remove(f)
    }

  /**
   * Search across all sessions' UI messages for a query string.
   * Returns results grouped by session, limited to `maxResults` total matches.
   * Each result includes: sessionId, sessionName, messageIndex, snippet (context around match), messageType.
   */
  def searchHistory(query: String, maxResults: Int = 50): IO[List[SearchHit]] =
    if query.trim.isEmpty then IO.pure(Nil)
    else
      IO.blocking {
        val q = query.toLowerCase
        val results = scala.collection.mutable.ListBuffer.empty[SearchHit]
        val sessions =
          try
            val raw = os.read(indexFile)
            val json = decode[Json](raw).getOrElse(Json.obj())
            json.hcursor.downField("sessions").as[List[SessionMeta]].getOrElse(Nil)
          catch case _: Exception => Nil

        var stop = false
        sessions.takeWhile(_ => !stop).foreach { meta =>
          if results.size >= maxResults then stop = true
          if !stop then
            val f = uiFile(meta.id)
            if os.exists(f) then
              try
                val msgs = decode[List[UiMessage]](os.read(f)).getOrElse(Nil)
                msgs.zipWithIndex.takeWhile(_ => !stop).foreach { case (msg, idx) =>
                  if results.size >= maxResults then { stop = true }
                  if !stop then
                    msg match
                      case UiMessage.User(text, _) if text.toLowerCase.contains(q) =>
                        results += SearchHit(meta.id, meta.name, idx, snippet(text, q), "user")
                      case UiMessage.Ai(text, _, _) if text.toLowerCase.contains(q) =>
                        results += SearchHit(meta.id, meta.name, idx, snippet(text, q), "ai")
                      case UiMessage.Agent(_, text) if text.toLowerCase.contains(q) =>
                        results += SearchHit(meta.id, meta.name, idx, snippet(text, q), "agent")
                      case UiMessage.Tool(label, summary, content, _, _, _) =>
                        val combined = s"$label $summary $content"
                        if combined.toLowerCase.contains(q) then
                          results += SearchHit(meta.id, meta.name, idx, snippet(combined, q), "tool")
                      case UiMessage.Ask(question, answer, _, _) =>
                        val combined = s"$question $answer"
                        if combined.toLowerCase.contains(q) then
                          results += SearchHit(meta.id, meta.name, idx, snippet(combined, q), "ask")
                      case _ => ()
                }
              catch case _: Exception => ()
        }
        results.toList
      }

  /** Extract a short snippet around the first match occurrence. */
  private def snippet(text: String, queryLower: String, contextLen: Int = 60): String =
    val textLower = text.toLowerCase
    val idx = textLower.indexOf(queryLower)
    if idx < 0 then text.take(120)
    else
      val start = Math.max(0, idx - contextLen)
      val end = Math.min(text.length, idx + queryLower.length + contextLen)
      val prefix = if start > 0 then "..." else ""
      val suffix = if end < text.length then "..." else ""
      prefix + text.substring(start, end) + suffix

end SessionStore

case class SearchHit(
  sessionId: String,
  sessionName: String,
  messageIndex: Int,
  snippet: String,
  messageType: String
)

object SearchHit:
  given Encoder[SearchHit] = Encoder.instance { h =>
    Json.obj(
      "sessionId" -> h.sessionId.asJson,
      "sessionName" -> h.sessionName.asJson,
      "messageIndex" -> h.messageIndex.asJson,
      "snippet" -> h.snippet.asJson,
      "messageType" -> h.messageType.asJson
    )
  }
