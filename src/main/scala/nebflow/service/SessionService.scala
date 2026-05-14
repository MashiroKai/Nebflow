package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.gateway.{Folder, SessionMeta, SessionStore}
import nebflow.shared.Message

class SessionService(store: SessionStore):

  def createSession(name: String, agentName: Option[String] = None, folderId: Option[String] = None): IO[SessionMeta] =
    store.createSession(name, agentName = agentName, folderId = folderId)

  def deleteSession(id: String): IO[Unit] =
    store.deleteSession(id)

  def batchDeleteSessions(ids: List[String]): IO[Unit] =
    ids.traverse_(deleteSession)

  def switchSession(id: String): IO[Unit] =
    store.switchSession(id).void

  def renameSession(id: String, name: String): IO[Unit] =
    store.renameSession(id, name)

  def listSessions: IO[List[SessionMeta]] =
    store.listSessions

  def getActiveId: IO[String] =
    store.getActiveId

  def setActiveMessages(messages: List[Message]): IO[Unit] =
    store.setActiveMessages(messages)

  def saveMessages(sessionId: String, messages: List[Message]): IO[Unit] =
    store.saveMessagesForSession(sessionId, messages) *> store.flushIndex

  // ===== Folder API =====

  def createFolder(name: String, parentId: Option[String] = None, agentName: String = ""): IO[Folder] =
    store.createFolder(name, parentId, agentName)

  def renameFolder(id: String, name: String): IO[Unit] =
    store.renameFolder(id, name)

  def deleteFolder(id: String): IO[Unit] =
    store.deleteFolder(id)

  def moveSessionToFolder(sessionId: String, folderId: Option[String]): IO[Unit] =
    store.moveSessionToFolder(sessionId, folderId)

  def moveFolder(folderId: String, parentId: Option[String]): IO[Unit] =
    store.moveFolder(folderId, parentId)

  def listFolders(agentName: String): IO[List[Folder]] =
    store.listFolders(agentName)

  def sendSessionList(wsSend: Json => IO[Unit], agentName: String = "Nebula"): IO[Unit] =
    for
      sessions <- store.listSessions
      folders <- store.listFolders(agentName)
      activeId <- store.getActiveId
      _ <- wsSend(
        Json.obj(
          "type" -> "sessionList".asJson,
          "sessions" -> sessions.asJson,
          "folders" -> folders.asJson,
          "activeId" -> activeId.asJson
        )
      )
    yield ()
end SessionService
