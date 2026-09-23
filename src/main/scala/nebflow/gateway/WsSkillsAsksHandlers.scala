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
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.core.project.{
  CancelSource as ChainCancelSource,
  ChainCancelEntry,
  ChainCancelReport,
  ProjectRuntime,
  ProjectRuntimeRegistry
}
import nebflow.core.{PathUtil, *}
import nebflow.gateway.NfFilePolicy.*
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{Charset, HttpRoutes, MediaType, Response, Status, StaticFile}

import scala.concurrent.duration.*
import scala.io.Source

import WsDispatch.{parsedJson, inboundEnvelope}

/** 技能与提问域(skills/asks):ask 卡片执行、技能激活、pending-AskUser 快照。 */
private[gateway] object WsSkillsAsksHandlers:

  private[gateway] val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "ask" -> handleAsk,
    "getSkills" -> handleGetSkills,
    "getTeams" -> handleGetTeams,
    "skill" -> handleSkill,
    "deleteSkill" -> handleDeleteSkill,
    "getPendingAsks" -> handleGetPendingAsks
  )

  private def handleAsk(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val askJson = parsedJson(text)
    val question = askJson.hcursor.downField("question").as[String].getOrElse("")
    val askSessionId = askJson.hcursor.downField("sessionId").as[String].getOrElse("")
    if question.nonEmpty && askSessionId.nonEmpty then
      executeAsk(askSessionId, question, wsSend).handleErrorWith { e =>
        logger.warn(s"Ask failed for session $askSessionId: ${e.getMessage}")
        wsSend(
          io.circe.Json.obj(
            "type" -> "askError".asJson,
            "sessionId" -> askSessionId.asJson,
            "message" -> s"Ask failed: ${e.getMessage.take(200)}".asJson
          )
        )
      }
    else IO.unit
  end handleAsk

  private def handleGetSkills(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    for
      skills <- SkillService.listSkills()
      _ <- wsSend(
        io.circe.Json.obj(
          "type" -> "skillList".asJson,
          "skills" -> skills.asJson
        )
      )
    yield ()
  end handleGetSkills

  private def handleGetTeams(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    for
      teams <- EntityLoader.listTeams()
      flows <- EntityLoader.listFlows()
      teamEntries = teams.values.toList
        .sortBy(_.name)
        .map(t =>
          io.circe.Json
            .obj("name" -> t.name.asJson, "description" -> t.description.asJson, "type" -> "team".asJson)
        )
      flowEntries = flows.values.toList
        .sortBy(_.name)
        .map(f =>
          io.circe.Json
            .obj("name" -> f.name.asJson, "description" -> f.description.asJson, "type" -> "flow".asJson)
        )
      _ <- wsSend(
        io.circe.Json.obj(
          "type" -> "teamList".asJson,
          "teams" -> teamEntries.asJson,
          "flows" -> flowEntries.asJson
        )
      )
    yield ()
    end for
  end handleGetTeams

  private def handleSkill(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val skillJson = parsedJson(text)
    val skillName = skillJson.hcursor.downField("skillName").as[String].getOrElse("")
    val skillInput = skillJson.hcursor.downField("input").as[String].getOrElse("")
    val skillSessionId = skillJson.hcursor.downField("sessionId").as[String].getOrElse("")
    if skillName.nonEmpty && skillSessionId.nonEmpty then
      // Persist user message and skill activation system bubble to session history,
      // so they survive session switching (frontend rebuilds DOM from backend history).
      sharedResources.sessionStore.appendUiMessages(
        skillSessionId,
        List(
          UiMessage.User(skillInput, timestamp = System.currentTimeMillis()),
          UiMessage.System(
            s"Using skill: $skillName",
            Some("slash.skillActivated"),
            Some(io.circe.Json.obj("skillName" -> skillName.asJson))
          )
        )
      ) *>
        executeSkill(skillName, skillInput, skillSessionId, wsSend).handleErrorWith { e =>
          logger.warn(s"Skill '$skillName' failed for session $skillSessionId: ${e.getMessage}")
          wsSend(
            io.circe.Json.obj(
              "type" -> "skillError".asJson,
              "sessionId" -> skillSessionId.asJson,
              "message" -> s"Skill failed: ${e.getMessage.take(200)}".asJson
            )
          )
        }
    else IO.unit
    end if
  end handleSkill

  private def handleDeleteSkill(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val delJson = parsedJson(text)
    val delName = delJson.hcursor.downField("name").as[String].getOrElse("")
    if delName.nonEmpty then
      SkillService.deleteSkill(delName).flatMap { success =>
        wsSend(
          io.circe.Json.obj(
            "type" -> "skillDeleted".asJson,
            "name" -> delName.asJson,
            "success" -> success.asJson
          )
        ) *>
          SkillService.listSkills().flatMap { skills =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "skillList".asJson,
                "skills" -> skills.asJson
              )
            )
          }
      }
    else IO.unit
    end if
  end handleDeleteSkill

  private def handleGetPendingAsks(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 多 AskUser 并发批（#250 第③项，2026-09-13 作者裁定「6 项全补」）：
    // **全局** pending-AskUser 快照 —— 前端重连时把待办条/badge 的本地镜像
    // 与 hub 权威一次性对齐（与「按会话订阅重放」解耦）。
    //
    // 旧口径缺口：前端 onReconnect 做全局清空（resetPendingAsks），后端重建
    // 却只在「某会话 getHistory 首帧」触发（replayPendingAsks，按会话订阅）
    // ⇒ 重连时活动会话 ≠ 承载卡片的 root 会话时，镜像清空后永不重建：
    // 待办条/badge 显示 0 而卡片还挂着 = 待办信号静默丢失。
    //
    // 读侧纪律与 replayPendingAsks 同族：只读快照、不触碰槽位。
    // 无静默路径核证：hub 未装配 ⇒ 回空快照（确定性结论，不是「无响应」）；
    // 查询失败 ⇒ 回 `failed:true` 帧（前端保留本地镜像并给可见提示），
    // 绝不假装「零 pending」把用户已有的待办清零。
    listAllPendingAsks(wsSend)
  end handleGetPendingAsks

end WsSkillsAsksHandlers
