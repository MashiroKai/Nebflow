package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.RootAgentIdentity
import nebflow.gateway.{Folder, SessionMeta, SessionStore}
import nebflow.shared.Message

/**
 * 会话服务层。
 *
 * 2026-09-13（permshield S1）：档位相关职责收敛为**出口 overlay** —— 会话列表里
 * 逐会话 `safetyMode` 输出**有效档位** = 应用级全局持久档位
 * （`GlobalSafety.defaultMode`，与 `SharedResources.effectiveSafetyMode` 同源）。
 * 会话级覆盖面已删除 ⇒ 本层**没有任何档位入参**（构造参数里原来那个"覆盖快照
 * 提供者"随之删除：它唯一的存在理由就是漏注入会让出口报全局值，而现在出口**就是**
 * 全局值）。新建会话**不再把档位写进 meta**（meta 是非权威遗留键）。
 */
class SessionService(
  store: SessionStore
):

  /**
   * 新建会话。
   *
   * 2026-09-12 权限全局单一权威源（设计 §10 #16 / §13 #13）：**不再解析全局值写进
   * meta**。会话的**有效档位**由 resolver（覆盖 ?? 全局）决定，与盘上键无关；因此
   * 「新会话的初始档 = 全局值」这条语义由 resolver 保证，无需也不需要落盘。
   *
   * 历史沿革：启动默认 = 全部放行（2026-09-12 作者令）曾在此把全局值写进 `meta`，
   * 那正是"会话各自持有权威档位"的承载面（R1/T-2 同族），本批结构性移除。
   */
  def createSession(
    name: String,
    agentName: Option[String] = None,
    folderId: Option[String] = None,
    flowName: Option[String] = None
  ): IO[SessionMeta] =
    store.createSession(name, agentName = agentName, folderId = folderId, flowName = flowName)

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

  def sendSessionList(wsSend: Json => IO[Unit], agentName: String = RootAgentIdentity.Name): IO[Unit] =
    for
      sessions <- store.listSessions
      folders <- store.listFolders(agentName)
      activeId <- store.getActiveId
      // 出口 overlay：逐会话 `safetyMode` = 有效档位 = 应用级全局持久值
      // （permshield S1：已无会话覆盖面），与 SharedResources 的列表出口共用同一
      // helper（键恒存在，不再依赖 Encoder「= confirm-edits 时省略键」的隐式契约）。
      global <- nebflow.core.GlobalSafety.defaultMode
      sessionsJson = nebflow.shared.SessionMeta.withEffectiveSafetyModes(sessions, global)
      rulesFolderIds = folders.filter(f => nebflow.service.RulesStore.exists(f.id)).map(_.id)
      _ <- wsSend(
        Json.obj(
          "type" -> "sessionList".asJson,
          "sessions" -> sessionsJson,
          "folders" -> folders.asJson,
          "activeId" -> activeId.asJson,
          "foldersWithRules" -> rulesFolderIds.asJson
        )
      )
    yield ()
end SessionService
