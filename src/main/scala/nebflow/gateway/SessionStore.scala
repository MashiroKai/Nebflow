package nebflow.gateway

import cats.effect.std.Semaphore
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.PathUtil
import nebflow.shared.{*, given}

// Re-export SessionMeta from shared package for backward compatibility
export nebflow.shared.SessionMeta

import java.util.UUID

// ===== Per-session bridge binding config =====
// Generic: each platform stores its config as a Json object.
// e.g. bridges = { "telegram": { "chatId": -123 } }

case class Folder(
  id: String,
  name: String,
  parentId: Option[String] = None,
  agentName: String = "",
  projectRoot: Option[String] = None,
  createdAt: Long,
  updatedAt: Long
)

object Folder:

  given Encoder[Folder] = Encoder.instance { f =>
    val base = Json.obj(
      "id" -> f.id.asJson,
      "name" -> f.name.asJson,
      "createdAt" -> f.createdAt.asJson,
      "updatedAt" -> f.updatedAt.asJson
    )
    val withParent = f.parentId.fold(base)(p => base.deepMerge(Json.obj("parentId" -> p.asJson)))
    val withAgent =
      if f.agentName.nonEmpty then withParent.deepMerge(Json.obj("agentName" -> f.agentName.asJson)) else withParent
    f.projectRoot.fold(withAgent)(pr => withAgent.deepMerge(Json.obj("projectRoot" -> pr.asJson)))
  }

  given Decoder[Folder] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      name <- c.downField("name").as[String]
      parentId <- c.downField("parentId").as[Option[String]]
      agentName <- c.downField("agentName").as[Option[String]]
      projectRoot <- c.downField("projectRoot").as[Option[String]]
      createdAt <- c.downField("createdAt").as[Long]
      updatedAt <- c.downField("updatedAt").as[Long]
    yield Folder(id, name, parentId, agentName.getOrElse(""), projectRoot, createdAt, updatedAt)
  }
end Folder

class SessionStore(sessionsDir: os.Path, tasksDir: os.Path):
  private val logger = nebflow.core.NebflowLogger.forName("nebflow.session")

  // (activeId, metas sorted by updatedAt desc, folders)
  private val indexRef: Ref[IO, (String, List[SessionMeta], List[Folder])] =
    Ref.unsafe[IO, (String, List[SessionMeta], List[Folder])]("", Nil, Nil)

  // Only active session's messages in memory
  private val activeMessagesRef: Ref[IO, List[Message]] =
    Ref.unsafe[IO, List[Message]](Nil)

  /** Optional hook — called with sessionId after any session data change. Used by CloudSessionSync for push. */
  private var sessionChangedHook: Option[String => IO[Unit]] = None

  /** Register a callback invoked after session data changes (messages saved, session created/deleted). */
  def setSessionChangedHook(hook: String => IO[Unit]): Unit =
    sessionChangedHook = Some(hook)

  private def notifySessionChanged(sessionId: String): IO[Unit] =
    sessionChangedHook match
      case Some(hook) => hook(sessionId).handleErrorWith(_ => IO.unit)
      case None => IO.unit

  private val indexFile = sessionsDir / "_index.json"
  private val indexTempFile = sessionsDir / "_index.json.tmp"

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
      val folders = json.hcursor.downField("folders").as[List[Folder]].getOrElse(Nil)
      (activeId, sessions, folders)
    }.flatMap { case (activeId, sessions, folders) =>
      recoverOrphans(sessions).flatMap { recovered =>
        val allSessions = sessions ++ recovered
        if recovered.nonEmpty then
          logger.info(
            s"Recovered ${recovered.size} orphan session(s): ${recovered.map(s => s"${s.name}(${s.id.take(8)})").mkString(", ")}"
          )
        if activeId.nonEmpty && allSessions.exists(_.id == activeId) then
          loadSessionMessages(activeId).flatMap { msgs =>
            indexRef.set((activeId, allSessions, folders)) *> activeMessagesRef.set(msgs)
          }
        else if allSessions.nonEmpty then
          allSessions.maxByOption(_.updatedAt) match
            case Some(first) =>
              loadSessionMessages(first.id).flatMap { msgs =>
                indexRef.set((first.id, allSessions, folders)) *> activeMessagesRef.set(msgs)
              }
            case None => createDefaultSession
        else createDefaultSession
      }
    }

  /** Scan disk for session files not in the index and recover them. */
  private def recoverOrphans(indexed: List[SessionMeta]): IO[List[SessionMeta]] =
    IO.blocking {
      val indexedIds = indexed.map(_.id).toSet
      os.list(sessionsDir)
        .filter(p => p.last.endsWith(".json") && !p.last.startsWith("_") && !p.last.endsWith(".ui.json"))
        .toList
    }.flatMap { files =>
      files
        .flatTraverse { f =>
          val id = f.last.stripSuffix(".json")
          // Skip non-UUID names (legacy files like "web.json", "last.json", etc.)
          if id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") then
            IO.blocking {
              val mtime = os.mtime(f)
              // Try to extract name from first user message
              val name =
                try
                  val msgs = decode[List[Message]](os.read(f)).getOrElse(Nil)
                  msgs.find(_.role == MessageRole.User).map(_.textContent.take(50)).getOrElse("Recovered Session")
                catch case _: Exception => "Recovered Session"
              List(SessionMeta(id, name, mtime, mtime, hasUnread = false))
            }
          else IO.pure(Nil)
        }
        .map { candidates =>
          val indexedIds = indexed.map(_.id).toSet
          candidates.filterNot(m => indexedIds.contains(m.id))
        }
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
        } *> indexRef.set((id, List(meta), Nil)) *> activeMessagesRef.set(msgs) *> saveIndex
      case None =>
        createDefaultSession
    }

  end migrateFromLegacy

  private def createDefaultSession: IO[Unit] =
    val id = UUID.randomUUID().toString
    val now = System.currentTimeMillis()
    val meta = SessionMeta(id, "Default Session", now, now, hasUnread = false)
    indexRef.set((id, List(meta), Nil)) *> activeMessagesRef.set(Nil) *>
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
    indexRef.get.flatMap { case (activeId, sessions, folders) =>
      IO.blocking {
        val json = Json.obj(
          "activeId" -> activeId.asJson,
          "sessions" -> sessions.asJson,
          "folders" -> folders.asJson
        )
        // Atomic write: write to temp file then rename
        os.write.over(indexTempFile, json.spaces2, createFolders = true)
        os.move.over(indexTempFile, indexFile)
      }
    }

  def getActiveMessages: IO[List[Message]] = activeMessagesRef.get

  def setActiveMessages(msgs: List[Message]): IO[Unit] =
    indexRef.get.flatMap { case (activeId, sessions, folders) =>
      val now = System.currentTimeMillis()
      val updated = sessions.map(s => if s.id == activeId then s.copy(updatedAt = now) else s)
      activeMessagesRef.set(msgs) *>
        saveSessionMessages(activeId, msgs) *>
        indexRef.set((activeId, updated, folders)) *>
        notifySessionChanged(activeId)
    // Don't save index on every message to reduce disk writes — save periodically or on switch
    }

  /**
   * Save messages to a specific session (not necessarily the active one).
   *  Updates the active ref only if the target is still the active session.
   */
  def saveMessagesForSession(targetId: String, msgs: List[Message]): IO[Unit] =
    val now = System.currentTimeMillis()
    indexRef
      .modify { case (activeId, sessions, folders) =>
        val updated = sessions.map(s => if s.id == targetId then s.copy(updatedAt = now) else s)
        ((activeId, updated, folders), activeId)
      }
      .flatMap { activeId =>
        val updateRef = if targetId == activeId then activeMessagesRef.set(msgs) else IO.unit
        updateRef *> saveSessionMessages(targetId, msgs) *> notifySessionChanged(targetId)
      }

  def flushIndex: IO[Unit] = saveIndex

  def listSessions: IO[List[SessionMeta]] =
    indexRef.get.map { case (_, sessions, _) => sessions.sortBy(-_.updatedAt) }

  /** List folders for a given agent. Old folders (agentName == "") are visible to Nebula only. */
  def listFolders(agentName: String): IO[List[Folder]] =
    indexRef.get.map { case (_, _, folders) =>
      folders.filter { f =>
        f.agentName == agentName || (f.agentName.isEmpty && agentName == "Nebula")
      }
    }

  /** Get folder name by ID. Synchronous lookup from in-memory index. */
  def getFolderName(folderId: String): Option[String] =
    import cats.effect.unsafe.implicits.global
    indexRef.get.map { case (_, _, folders) => folders.find(_.id == folderId).map(_.name) }.unsafeRunSync()

  /** Get parentId for a folder — returns None if folder not found, Some(None) if top-level. */
  def getFolderParentId(folderId: String): Option[Option[String]] =
    import cats.effect.unsafe.implicits.global
    indexRef.get.map { case (_, _, folders) => folders.find(_.id == folderId).map(_.parentId) }.unsafeRunSync()

  /** Get a folder's agent name by ID. Returns None if folder not found. */
  def getFolderAgentName(folderId: String): IO[Option[String]] =
    indexRef.get.map { case (_, _, folders) =>
      folders.find(_.id == folderId).map(_.agentName).filter(_.nonEmpty)
    }

  /** List sessions filtered by agentName. Sessions without agentName match "Nebula". */
  def listSessionsByAgent(agentName: String): IO[List[SessionMeta]] =
    indexRef.get.map { case (_, sessions, _) =>
      sessions
        .filter { s =>
          val effective = s.agentName.getOrElse("Nebula")
          effective == agentName
        }
        .sortBy(-_.updatedAt)
    }

  def getActiveId: IO[String] = indexRef.get.map(_._1)

  def getActiveMeta: IO[Option[SessionMeta]] =
    indexRef.get.map { case (activeId, sessions, _) => sessions.find(_.id == activeId) }

  def getSessionMeta(id: String): IO[Option[SessionMeta]] =
    indexRef.get.map { case (_, sessions, _) => sessions.find(_.id == id) }

  def switchSession(id: String): IO[List[Message]] =
    // Use modify for atomic read-modify-write to prevent concurrent corruption.
    // Returns: Right(oldId) for switch, Left(None) for same-session, Left(Some(err)) for not-found
    indexRef
      .modify { case (currentId, sessions, folders) =>
        if id == currentId then ((currentId, sessions, folders), Left(Option.empty[String]))
        else
          sessions.find(_.id == id) match
            case None => ((currentId, sessions, folders), Left(Some(s"Session $id not found")))
            case Some(_) =>
              val updated = sessions.map(s =>
                if s.id == id then s.copy(hasUnread = false)
                else s
              )
              ((id, updated, folders), Right(currentId))
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

  def createSession(
    name: String,
    initialMsgs: List[Message] = Nil,
    agentName: Option[String] = None,
    folderId: Option[String] = None
  ): IO[SessionMeta] =
    val id = UUID.randomUUID().toString
    val now = System.currentTimeMillis()
    val meta = SessionMeta(id, name, now, now, hasUnread = false, agentName = agentName, folderId = folderId)
    indexRef.get.flatMap { case (activeId, sessions, folders) =>
      saveSessionMessages(id, initialMsgs) *>
        indexRef.set((activeId, meta :: sessions, folders)) *> saveIndex *> meta.pure[IO]
    }

  /**
   * Get an existing session by ID, or create one with that exact ID.
   * Used by MemoryAgentManager to maintain persistent sessions across restarts.
   * Returns (meta, wasCreated).
   */
  def getOrCreateSession(
    id: String,
    name: String,
    agentName: Option[String] = None
  ): IO[(SessionMeta, Boolean)] =
    indexRef.get.flatMap { case (_, sessions, _) =>
      sessions.find(_.id == id) match
        case Some(meta) => IO.pure((meta, false))
        case None =>
          val now = System.currentTimeMillis()
          val meta = SessionMeta(id, name, now, now, hasUnread = false, agentName = agentName)
          indexRef.get.flatMap { case (activeId, sessions, folders) =>
            saveSessionMessages(id, Nil) *>
              indexRef.set((activeId, meta :: sessions, folders)) *> saveIndex *> IO.pure((meta, true))
          }
    }

  def deleteSession(id: String): IO[Unit] =
    indexRef
      .modify { case (activeId, sessions, folders) =>
        // Get the agent name of the session being deleted
        val deletedAgent = sessions.find(_.id == id).flatMap(_.agentName)
        val updated = sessions.filterNot(_.id == id)
        val newActiveId =
          if id == activeId then
            // Pick the most recently updated session from the SAME agent only,
            // to prevent cross-agent activeId contamination
            updated
              .filter { s =>
                val effectiveAgent = s.agentName
                effectiveAgent == deletedAgent
              }
              .maxByOption(_.updatedAt)
              .map(_.id)
              .getOrElse("")
          else activeId
        ((newActiveId, updated, folders), (id == activeId, newActiveId))
      }
      .flatMap { case (wasActive, newActiveId) =>
        IO.blocking {
          // Remove session data files
          val f = sessionFile(id)
          if os.exists(f) then os.remove(f)
          // Remove task directory
          val td = tasksDir / id
          if os.exists(td) then os.remove.all(td)
          // Remove uploaded attachments directory
          val ud = PathUtil.dataRoot / "uploads" / id
          if os.exists(ud) then os.remove.all(ud)
        } *> deleteUiMessages(id) *> appendSemaphores.update(
          _ - id
        ) *> (if wasActive && newActiveId.nonEmpty then
                loadSessionMessages(newActiveId).flatMap(msgs => activeMessagesRef.set(msgs))
              else IO.unit) *> saveIndex
      }

  def markUnread(id: String): IO[Unit] =
    indexRef.update { case (activeId, sessions, folders) =>
      val updated = sessions.map(s => if s.id == id then s.copy(hasUnread = true) else s)
      (activeId, updated, folders)
    } *> saveIndex

  def renameSession(id: String, newName: String): IO[Unit] =
    indexRef.update { case (activeId, sessions, folders) =>
      val updated = sessions.map(s => if s.id == id then s.copy(name = newName) else s)
      (activeId, updated, folders)
    } *> saveIndex

  def updateSessionModel(id: String, modelRef: Option[String]): IO[Unit] =
    indexRef.update { case (activeId, sessions, folders) =>
      val updated = sessions.map(s => if s.id == id then s.copy(modelRef = modelRef) else s)
      (activeId, updated, folders)
    } *> saveIndex

  def updateSessionBridge(id: String, platform: String, config: Option[Json]): IO[Unit] =
    indexRef.update { case (activeId, sessions, folders) =>
      val updated = sessions.map { s =>
        if s.id == id then
          val newBridges = config match
            case Some(c) => s.bridges.updated(platform, c)
            case None => s.bridges - platform
          s.copy(bridges = newBridges)
        else s
      }
      (activeId, updated, folders)
    } *> saveIndex

  // ===== Folder Management =====

  def createFolder(name: String, parentId: Option[String] = None, agentName: String = ""): IO[Folder] =
    val id = UUID.randomUUID().toString
    val now = System.currentTimeMillis()
    val folder = Folder(id, name, parentId, agentName, projectRoot = None, now, now)
    indexRef.update { case (activeId, sessions, folders) =>
      (activeId, sessions, folder :: folders)
    } *> saveIndex *> IO.pure(folder)

  /** Set projectRoot on any folder. Nearest ancestor's projectRoot takes precedence. */
  def setFolderProjectRoot(folderId: String, projectRoot: Option[String]): IO[Either[String, Unit]] =
    indexRef
      .modify { case (activeId, sessions, folders) =>
        folders.find(_.id == folderId) match
          case None => ((activeId, sessions, folders), Left(s"Folder $folderId not found"))
          case Some(_) =>
            val updated = folders.map(f =>
              if f.id == folderId then f.copy(projectRoot = projectRoot, updatedAt = System.currentTimeMillis()) else f
            )
            ((activeId, sessions, updated), Right(()))
      }
      .flatMap {
        case Right(_) => saveIndex.as(Right(()))
        case err => IO.pure(err)
      }

  /**
   * Resolve effective projectRoot: walk from the session's folder up to the root,
   * returning the first (nearest) non-empty projectRoot.
   */
  def resolveProjectRoot(folderId: Option[String]): IO[Option[String]] =
    folderId match
      case None => IO.pure(None)
      case Some(fid) =>
        indexRef.get.map { case (_, _, folders) =>
          // Walk up: nearest folder with a projectRoot wins
          def findNearest(id: String): Option[String] =
            folders.find(_.id == id) match
              case None => None
              case Some(f) =>
                f.projectRoot.filter(_.nonEmpty) match
                  case Some(pr) => Some(pr)
                  case None =>
                    f.parentId match
                      case Some(pid) => findNearest(pid)
                      case None => None
          findNearest(fid)
        }

  def renameFolder(id: String, newName: String): IO[Unit] =
    indexRef.update { case (activeId, sessions, folders) =>
      val updated =
        folders.map(f => if f.id == id then f.copy(name = newName, updatedAt = System.currentTimeMillis()) else f)
      (activeId, sessions, updated)
    } *> saveIndex

  def deleteFolder(id: String): IO[Unit] =
    indexRef.modify { case (activeId, sessions, folders) =>
      // Collect all descendant folder IDs (children, grandchildren, etc.)
      def descendantIds(folderId: String, all: List[Folder]): Set[String] =
        val children = all.filter(_.parentId == Some(folderId)).map(_.id).toSet
        children ++ children.flatMap(c => descendantIds(c, all))

      val idsToRemove = Set(id) ++ descendantIds(id, folders)

      // Remove folders
      val updatedFolders = folders.filterNot(f => idsToRemove.contains(f.id))
      // Move sessions in deleted folders to root
      val updatedSessions = sessions.map { s =>
        if s.folderId.exists(idsToRemove.contains) then s.copy(folderId = None, updatedAt = System.currentTimeMillis())
        else s
      }
      ((activeId, updatedSessions, updatedFolders), ())
    } *> saveIndex

  // NOTE: moving to a folder is reorganization — must not touch updatedAt (interaction timestamp)
  def moveSessionToFolder(sessionId: String, folderId: Option[String]): IO[Unit] =
    indexRef.update { case (activeId, sessions, folders) =>
      val updated = sessions.map(s => if s.id == sessionId then s.copy(folderId = folderId) else s)
      (activeId, updated, folders)
    } *> saveIndex

  def moveFolder(folderId: String, parentId: Option[String]): IO[Unit] =
    indexRef.update { case (activeId, sessions, folders) =>
      // Prevent circular: cannot move a folder into itself or one of its descendants
      def descendantIds(id: String, all: List[Folder]): Set[String] =
        val children = all.filter(_.parentId == Some(id)).map(_.id).toSet
        children ++ children.flatMap(c => descendantIds(c, all))
      val invalidParents = Set(folderId) ++ descendantIds(folderId, folders)
      if parentId.exists(invalidParents.contains) then (activeId, sessions, folders) // no-op on circular
      else
        val updated = folders.map(f =>
          if f.id == folderId then f.copy(parentId = parentId, updatedAt = System.currentTimeMillis()) else f
        )
        (activeId, sessions, updated)
    } *> saveIndex

  // ============================================================
  // Cloud Sync Integration
  // ============================================================

  /** List all folders (for cloud sync — includes all agents' folders). */
  def listAllFolders: IO[List[Folder]] =
    indexRef.get.map { case (_, _, folders) => folders }

  /**
   * Merge cloud session index into local. Sessions and folders are deduplicated by ID;
   * the copy with the newer `updatedAt` wins. New sessions from cloud are added locally.
   */
  def mergeCloudIndex(cloudSessions: List[SessionMeta], cloudFolders: List[Folder]): IO[Unit] =
    indexRef
      .modify { case (activeId, localSessions, localFolders) =>
        val sessionMap = scala.collection.mutable.LinkedHashMap.empty[String, SessionMeta]
        for s <- localSessions do sessionMap(s.id) = s
        for cs <- cloudSessions do
          sessionMap.get(cs.id) match
            case Some(existing) if existing.updatedAt >= cs.updatedAt => // keep local
            case _ => sessionMap(cs.id) = cs
        val mergedSessions = sessionMap.values.toList

        val folderMap = scala.collection.mutable.LinkedHashMap.empty[String, Folder]
        for f <- localFolders do folderMap(f.id) = f
        for cf <- cloudFolders do
          folderMap.get(cf.id) match
            case Some(existing) if existing.updatedAt >= cf.updatedAt => // keep local
            case _ => folderMap(cf.id) = cf
        val mergedFolders = folderMap.values.toList

        val changed = mergedSessions.length != localSessions.length ||
          mergedFolders.length != localFolders.length ||
          mergedSessions.exists(ms => localSessions.find(_.id == ms.id).exists(_.updatedAt != ms.updatedAt))

        ((activeId, mergedSessions, mergedFolders), changed)
      }
      .flatMap { changed =>
        if changed then saveIndex else IO.unit
      }

  /**
   * Overwrite local session data with data pulled from cloud.
   * Updates messages, UI messages, and bumps updatedAt in the index.
   * Does NOT trigger the sessionChanged hook (this is a pull, not a local edit).
   */
  def setSessionFromCloud(sessionId: String, messages: List[Message], uiMessages: List[UiMessage]): IO[Unit] =
    for
      _ <- saveSessionMessages(sessionId, messages)
      _ <- saveUiMessages(sessionId, uiMessages)
      _ <- indexRef.update { case (activeId, sessions, folders) =>
        val now = System.currentTimeMillis()
        val updated = sessions.map(s => if s.id == sessionId then s.copy(updatedAt = now) else s)
        (activeId, updated, folders)
      }
      activeId <- getActiveId
      _ <- if activeId == sessionId then activeMessagesRef.set(messages) else IO.unit
    yield ()

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

  // Per-session semaphore to serialize appendUiMessages (prevents TOCTOU race)
  private val appendSemaphores: Ref[IO, Map[String, Semaphore[IO]]] =
    Ref.unsafe[IO, Map[String, Semaphore[IO]]](Map.empty)

  private def getAppendSemaphore(sessionId: String): IO[Semaphore[IO]] =
    appendSemaphores.get.flatMap(_.get(sessionId) match
      case Some(sem) => IO.pure(sem)
      case None => Semaphore[IO](1).flatTap(sem => appendSemaphores.update(_.updated(sessionId, sem))))

  /** Maximum number of UI messages kept per session. */
  private val MaxUiMessagesPerSession = 200

  /** Append UI messages to a session. Creates the file if it doesn't exist. */
  def appendUiMessages(sessionId: String, msgs: List[UiMessage]): IO[Unit] =
    if msgs.isEmpty then IO.unit
    else
      getAppendSemaphore(sessionId).flatMap { sem =>
        sem.permit.use { _ =>
          loadUiMessages(sessionId).flatMap { existing =>
            val combined = existing ++ msgs
            val trimmed =
              if combined.size > MaxUiMessagesPerSession then combined.takeRight(MaxUiMessagesPerSession) else combined
            saveUiMessages(sessionId, trimmed)
          }
        }
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

end SessionStore
