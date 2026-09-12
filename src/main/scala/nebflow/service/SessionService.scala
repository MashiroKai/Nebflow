package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.gateway.{Folder, SessionMeta, SessionStore}
import nebflow.shared.Message

class SessionService(store: SessionStore):

  /** 新建会话。
    *
    * 启动默认 = 全部放行（2026-09-12 作者令）：不传 `safetyMode` 时不再硬编码
    * "confirm-edits"，而是取**全局默认档**（`nebflow.json` 的 `safety.defaultMode`，
    * 未配置 ⇒ 顶档 —— 见 `GlobalSafety.defaultMode`）。这与 REST `POST /api/sessions`
    * 是同一处默认源，两条新建通路不再分叉。
    *
    * 显式传值（含 "confirm-edits"）照旧优先 —— 会话内回退通路不受影响（顶档只是
    * **初始值**，不是焊死的终值）。
    */
  def createSession(
    name: String,
    agentName: Option[String] = None,
    folderId: Option[String] = None,
    flowName: Option[String] = None,
    safetyMode: Option[String] = None
  ): IO[SessionMeta] =
    val resolvedMode: IO[String] = safetyMode match
      case Some(explicit) => IO.pure(explicit)
      case None           => nebflow.core.GlobalSafety.defaultMode.map(nebflow.core.SafetyMode.toString)
    resolvedMode.flatMap { mode =>
      store.createSession(name, agentName = agentName, folderId = folderId, flowName = flowName, safetyMode = mode)
    }

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

  def setFolderProjectRoot(folderId: String, projectRoot: Option[String]): IO[Either[String, Unit]] =
    store.setFolderProjectRoot(folderId, projectRoot)

  def resolveProjectRoot(folderId: Option[String]): IO[Option[String]] =
    store.resolveProjectRoot(folderId)

  def listFolders(agentName: String): IO[List[Folder]] =
    store.listFolders(agentName)

  def sendSessionList(wsSend: Json => IO[Unit], agentName: String = "Nebula"): IO[Unit] =
    for
      sessions <- store.listSessions
      folders <- store.listFolders(agentName)
      activeId <- store.getActiveId
      rulesFolderIds = folders.filter(f => nebflow.service.RulesStore.exists(f.id)).map(_.id)
      _ <- wsSend(
        Json.obj(
          "type" -> "sessionList".asJson,
          "sessions" -> sessions.asJson,
          "folders" -> folders.asJson,
          "activeId" -> activeId.asJson,
          "foldersWithRules" -> rulesFolderIds.asJson
        )
      )
    yield ()
end SessionService
