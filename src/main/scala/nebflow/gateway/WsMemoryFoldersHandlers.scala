/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorSystem as NebulaActorSystem
import nebflow.agent.*
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowTreeActor, FlowTreeRegistry, TeamSessionRegistry}
import nebflow.core.mcp.McpManager
import nebflow.core.project.{CancelSource as ChainCancelSource, *}
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.core.{PathUtil, *}
import nebflow.gateway.NfFilePolicy.*
import nebflow.gateway.WsDispatch.{inboundEnvelope, parsedJson}
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*
import scala.io.Source

/** 记忆与文件夹域(memory/folders):memory 读写、文件夹管理、文件夹规则。 */
private[gateway] object WsMemoryFoldersHandlers:

  private[gateway] val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "createFolder" -> handleCreateFolder,
    "renameFolder" -> handleRenameFolder,
    "deleteFolder" -> handleDeleteFolder,
    "moveSessionToFolder" -> handleMoveSessionToFolder,
    "moveFolder" -> handleMoveFolder,
    "setFolderProjectRoot" -> handleSetFolderProjectRoot,
    "getRules" -> handleGetRules,
    "saveRules" -> handleSaveRules,
    "deleteRules" -> handleDeleteRules,
    "rulesStatus" -> handleRulesStatus,
    "getMemory" -> handleGetMemory,
    "saveMemory" -> handleSaveMemory,
    "memoryStatus" -> handleMemoryStatus
  )

  private def handleCreateFolder(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ===== Folder Management =====

    val json = parsedJson(text)
    val name = json.hcursor.downField("name").as[String].getOrElse("New Folder")
    val parentId = json.hcursor.downField("parentId").as[Option[String]].getOrElse(None)
    val agentNameFromMsg = json.hcursor.downField("agentName").as[String].getOrElse("")
    if name.nonEmpty then
      val agentNameIO =
        if agentNameFromMsg.nonEmpty then IO.pure(agentNameFromMsg)
        else sessionStore.getActiveMeta.map(_.flatMap(_.agentName).getOrElse("Nebula"))
      agentNameIO
        .flatMap { agentName =>
          sessionService.createFolder(name, parentId, agentName).flatMap { _ =>
            sendAgentSessionListByName(wsSend, agentName)
          }
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
  end handleCreateFolder

  private def handleRenameFolder(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
    val newName = json.hcursor.downField("name").as[String].getOrElse("")
    if folderId.nonEmpty && newName.nonEmpty then
      sessionService
        .renameFolder(folderId, newName)
        .flatMap { _ =>
          sessionStore.getActiveMeta.flatMap { metaOpt =>
            val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
            sendAgentSessionListByName(wsSend, agentName)
          }
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
  end handleRenameFolder

  private def handleDeleteFolder(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val folderId = parse(text).flatMap(_.hcursor.downField("folderId").as[String]).getOrElse("")
    if folderId.nonEmpty then
      sessionService
        .deleteFolder(folderId)
        .flatMap { _ =>
          sessionStore.getActiveMeta.flatMap { metaOpt =>
            val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
            sendAgentSessionListByName(wsSend, agentName)
          }
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
  end handleDeleteFolder

  private def handleMoveSessionToFolder(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val folderId = json.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
    if sessionId.nonEmpty then
      sessionService
        .moveSessionToFolder(sessionId, folderId)
        .flatMap { _ =>
          sendAgentSessionList(wsSend, sessionId)
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
  end handleMoveSessionToFolder

  private def handleMoveFolder(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
    val parentId = json.hcursor.downField("parentId").as[Option[String]].getOrElse(None)
    if folderId.nonEmpty then
      sessionService
        .moveFolder(folderId, parentId)
        .flatMap { _ =>
          sessionStore.getActiveMeta.flatMap { metaOpt =>
            val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
            sendAgentSessionListByName(wsSend, agentName)
          }
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
  end handleMoveFolder

  private def handleSetFolderProjectRoot(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
    val projectRoot = json.hcursor.downField("projectRoot").as[Option[String]].getOrElse(None)
    if folderId.nonEmpty then
      sessionService
        .setFolderProjectRoot(folderId, projectRoot)
        .flatMap {
          case Right(_) =>
            // Use the folder's own agent name, not the active session's,
            // to ensure the frontend receives the update regardless of which agent tab is active.
            sessionStore.getFolderAgentName(folderId).flatMap { agentOpt =>
              val agentName = agentOpt.getOrElse("Nebula")
              sendAgentSessionListByName(wsSend, agentName)
            }
          case Left(err) =>
            wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleSetFolderProjectRoot

  private def handleGetRules(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ===== Folder Rules Management =====

    val json = parsedJson(text)
    val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
    if folderId.nonEmpty then
      val content = RulesStore.loadFolderRules(folderId).getOrElse("")
      wsSend(
        io.circe.Json.obj(
          "type" -> "rulesData".asJson,
          "folderId" -> folderId.asJson,
          "content" -> content.asJson
        )
      )
    else IO.unit
  end handleGetRules

  private def handleSaveRules(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
    val content = json.hcursor.downField("content").as[String].getOrElse("")
    if folderId.nonEmpty then
      RulesStore.saveFolderRules(folderId, content) *>
        wsSend(io.circe.Json.obj("type" -> "rulesSaved".asJson, "folderId" -> folderId.asJson))
    else IO.unit

  private def handleDeleteRules(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
    if folderId.nonEmpty then
      RulesStore.deleteFolderRules(folderId) *>
        wsSend(io.circe.Json.obj("type" -> "rulesDeleted".asJson, "folderId" -> folderId.asJson))
    else IO.unit

  private def handleRulesStatus(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
    if folderId.nonEmpty then
      wsSend(
        io.circe.Json.obj(
          "type" -> "rulesStatus".asJson,
          "folderId" -> folderId.asJson,
          "exists" -> RulesStore.exists(folderId).asJson,
          "preview" -> RulesStore.preview(folderId).asJson
        )
      )
    else IO.unit
  end handleRulesStatus

  private def handleGetMemory(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val scope = json.hcursor.downField("scope").as[String].getOrElse("session")
    // 未走信封助手:toOption.filter(_.nonEmpty) 把空串归一为 None(缺席走活跃会话回退),非空串回退同形,保持原样(2026-09-24)
    val sessionIdParam = json.hcursor.downField("sessionId").as[String].toOption.filter(_.nonEmpty)
    val metaIO = sessionIdParam match
      case Some(sid) => sessionStore.getSessionMeta(sid)
      case None => sessionStore.getActiveMeta
    (metaIO
      .flatMap { metaOpt =>
        val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
        val teamName = metaOpt.flatMap(_.flowName)
        val content = scope match
          case "user" => MemoryStore.loadUserMemory.getOrElse("")
          case "agent" =>
            teamName match
              case Some(tn) => MemoryStore.loadTeamAgentMemory(tn, agentName).getOrElse("")
              case None => MemoryStore.loadAgentMemory(agentName).getOrElse("")
          case _ => ""
        wsSend(
          io.circe.Json.obj(
            "type" -> "memoryData".asJson,
            "scope" -> scope.asJson,
            "content" -> content.asJson
          )
        )
      })
      .handleErrorWith { e =>
        logger.warn(s"getMemory error: ${e.getMessage}")
        wsSend(
          io.circe.Json.obj("type" -> "error".asJson, "message" -> s"getMemory failed: ${e.getMessage}".asJson)
        )
      }
  end handleGetMemory

  private def handleSaveMemory(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val scope = json.hcursor.downField("scope").as[String].getOrElse("session")
    val content = json.hcursor.downField("content").as[String].getOrElse("")
    // 未走信封助手:toOption.filter(_.nonEmpty) 把空串归一为 None(缺席走活跃会话回退),非空串回退同形,保持原样(2026-09-24)
    val sessionIdParam = json.hcursor.downField("sessionId").as[String].toOption.filter(_.nonEmpty)
    val metaIO = sessionIdParam match
      case Some(sid) => sessionStore.getSessionMeta(sid)
      case None => sessionStore.getActiveMeta
    (metaIO
      .flatMap { metaOpt =>
        val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
        val teamName = metaOpt.flatMap(_.flowName)
        val save = scope match
          case "user" => MemoryStore.saveUserMemory(content)
          case "agent" =>
            teamName match
              // 2026-08-31 裁定①: team agents have no memory — refuse to
              // resurrect deleted team memory.md files via the modal.
              case Some(_) => IO.unit
              case None => MemoryStore.saveAgentMemory(agentName, content)
          case _ => IO.unit
        save *> wsSend(io.circe.Json.obj("type" -> "memorySaved".asJson, "scope" -> scope.asJson))
      })
      .handleErrorWith { e =>
        // M4 落盘单点闸（MemoryWriteGate）：预算超硬顶 / 快照失败 = **结构化拒绝**，
        // 与 infra 失败**可判**区分 —— code 同码进日志与错误帧。拒绝不是 no-op，
        // 也不是静默跳过：前端拿到 error 帧（附 code），日志拿到同一码。
        val (code, detail) = e match
          case r: nebflow.service.MemoryWriteGate.Rejected =>
            (r.code, r.detail)
          case other =>
            ("SAVEMEMORY_FAILED", Option(other.getMessage).getOrElse(other.getClass.getSimpleName))
        // `logger.warn` 返回 IO —— 必须 *> 进链才会真的执行（旧写法把它当语句丢弃 ⇒
        // 该 WARN 从不落日志：一处既有的静默出口，随本批一并接上）。
        logger.warn(s"saveMemory error [$code]: $detail") *>
          wsSend(
            io.circe.Json.obj(
              "type" -> "error".asJson,
              "code" -> code.asJson,
              "message" -> s"saveMemory failed: $detail".asJson
            )
          )
      }
  end handleSaveMemory

  private def handleMemoryStatus(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    sessionStore.getActiveMeta.flatMap { metaOpt =>
      val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
      val teamName = metaOpt.flatMap(_.flowName)
      val (agentExists, agentPreview) = teamName match
        case Some(tn) =>
          (MemoryStore.teamAgentExists(tn, agentName), MemoryStore.teamAgentPreview(tn, agentName))
        case None => (MemoryStore.agentExists(agentName), MemoryStore.agentPreview(agentName))
      wsSend(
        io.circe.Json.obj(
          "type" -> "memoryStatus".asJson,
          "user" -> io.circe.Json.obj(
            "exists" -> MemoryStore.userExists.asJson,
            "preview" -> MemoryStore.userPreview.asJson
          ),
          "agent" -> io.circe.Json.obj(
            "exists" -> agentExists.asJson,
            "preview" -> agentPreview.asJson
          )
        )
      )
    }
  end handleMemoryStatus

end WsMemoryFoldersHandlers
