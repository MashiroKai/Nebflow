package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.gateway.{SessionMeta, SessionStore}
import nebflow.shared.Message

class SessionService(store: SessionStore):

  def createSession(name: String, agentName: Option[String] = None): IO[SessionMeta] =
    store.createSession(name, agentName = agentName)

  def deleteSession(id: String): IO[Unit] =
    store.deleteSession(id)

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

  def sendSessionList(wsSend: Json => IO[Unit]): IO[Unit] =
    for
      sessions <- store.listSessions
      activeId <- store.getActiveId
      _ <- wsSend(
        Json.obj(
          "type" -> "sessionList".asJson,
          "sessions" -> sessions.asJson,
          "activeId" -> activeId.asJson
        )
      )
    yield ()
end SessionService
