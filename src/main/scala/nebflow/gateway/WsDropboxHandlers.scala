/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorSystem as RootActorSystem
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

/** Dropbox 域(dropbox):跨设备消息与文件传输臂。 */
private[gateway] object WsDropboxHandlers:

  private[gateway] val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "dropbox-send-text" -> handleDropboxSendText,
    "dropbox-file-offer" -> handleDropboxFileOffer,
    "dropbox-file-probe" -> handleDropboxFileProbe,
    "dropbox-file-respond" -> handleDropboxFileRespond,
    "dropbox-get-history" -> handleDropboxGetHistory
  )

  private def handleDropboxSendText(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ===== Dropbox: cross-device messaging & file transfer =====

    // 未走信封助手:此处产出 HCursor(失败回退 Json.Null.hcursor),非 Json 回退同形,保持原样(2026-09-24)
    val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
    val deviceId = hc.downField("deviceId").as[String].getOrElse("")
    val msgText = hc.downField("text").as[String].getOrElse("")
    if deviceId.nonEmpty && msgText.nonEmpty then
      sharedResources.dropboxService match
        case None =>
          wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> "Dropbox not enabled".asJson))
        case Some(svc) =>
          svc
            .sendText(deviceId, msgText)
            .handleErrorWith(e =>
              wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> e.getMessage.asJson))
            )
            .void
    else IO.unit
  end handleDropboxSendText

  private def handleDropboxFileOffer(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 未走信封助手:此处产出 HCursor(失败回退 Json.Null.hcursor),非 Json 回退同形,保持原样(2026-09-24)
    val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
    val deviceId = hc.downField("deviceId").as[String].getOrElse("")
    val fileName = hc.downField("fileName").as[String].getOrElse("")
    val fileSize = hc.downField("fileSize").as[Long].getOrElse(0L)
    val mimeType = hc.downField("mimeType").as[String].getOrElse("")
    // 附件腿批（2026-09-12）：`files: [{fileName,fileSize,mimeType}]` ⇒ 单条消息多件
    // （≤9）。旧单件字段（fileName/fileSize/mimeType）继续有效 —— 向后兼容。
    val batch = hc
      .downField("files")
      .as[List[io.circe.Json]]
      .getOrElse(Nil)
      .flatMap { j =>
        val c = j.hcursor
        c.downField("fileName").as[String].toOption.map { n =>
          nebflow.dropbox.DropboxService.FileSpec(
            n,
            c.downField("fileSize").as[Long].getOrElse(0L),
            c.downField("mimeType").as[String].getOrElse("")
          )
        }
      }
    val specs =
      if batch.nonEmpty then batch
      else if fileName.nonEmpty then List(nebflow.dropbox.DropboxService.FileSpec(fileName, fileSize, mimeType))
      else Nil
    if deviceId.nonEmpty && specs.nonEmpty then
      sharedResources.dropboxService match
        case None =>
          wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> "Dropbox not enabled".asJson))
        case Some(svc) =>
          svc
            .offerFiles(deviceId, specs)
            .flatMap {
              case Right(_) => IO.unit
              case Left(err) =>
                // 超限 fail-fast + **回显实际值**：结构化错误体（code/actual/limit）直达前端。
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "dropboxError".asJson,
                    "deviceId" -> deviceId.asJson,
                    "error" -> err.message.asJson,
                    "errorDetail" -> err.toJson
                  )
                )
            }
            .handleErrorWith(e =>
              wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> e.getMessage.asJson))
            )
    else IO.unit
    end if
  end handleDropboxFileOffer

  private def handleDropboxFileProbe(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 未走信封助手:此处产出 HCursor(失败回退 Json.Null.hcursor),非 Json 回退同形,保持原样(2026-09-24)
    val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
    val transferId = hc.downField("transferId").as[String].getOrElse("")
    if transferId.nonEmpty then
      sharedResources.dropboxService match
        case None => IO.unit
        case Some(svc) =>
          svc.probeTransfer(transferId).flatMap {
            case Right(state) =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "dropbox-file-probe".asJson,
                  "transferId" -> transferId.asJson,
                  "bytesReceived" -> state.bytesReceived.asJson,
                  "totalBytes" -> state.totalBytes.asJson,
                  "prefixSha256" -> state.prefixSha256.asJson
                )
              )
            case Left(err) =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "dropboxError".asJson,
                  "transferId" -> transferId.asJson,
                  "error" -> err.message.asJson,
                  "errorDetail" -> err.toJson
                )
              )
          }
    else IO.unit
    end if
  end handleDropboxFileProbe

  private def handleDropboxFileRespond(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 未走信封助手:此处产出 HCursor(失败回退 Json.Null.hcursor),非 Json 回退同形,保持原样(2026-09-24)
    val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
    val deviceId = hc.downField("deviceId").as[String].getOrElse("")
    val transferId = hc.downField("transferId").as[String].getOrElse("")
    val accepted = hc.downField("accepted").as[Boolean].getOrElse(false)
    if deviceId.nonEmpty && transferId.nonEmpty then
      sharedResources.dropboxService match
        case None => IO.unit
        case Some(svc) => svc.respondToOffer(deviceId, transferId, accepted).handleErrorWith(_ => IO.unit)
    else IO.unit
  end handleDropboxFileRespond

  private def handleDropboxGetHistory(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 未走信封助手:此处产出 HCursor(失败回退 Json.Null.hcursor),非 Json 回退同形,保持原样(2026-09-24)
    val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
    val deviceId = hc.downField("deviceId").as[String].getOrElse("")
    if deviceId.nonEmpty then
      sharedResources.dropboxService match
        case None => IO.unit
        case Some(svc) =>
          svc.getHistory(deviceId).flatMap { msgs =>
            wsSend(
              io.circe.Json
                .obj(
                  "type" -> "dropbox-history".asJson,
                  "deviceId" -> deviceId.asJson,
                  "messages" -> msgs.asJson
                )
            )
          }
    else IO.unit
    end if
  end handleDropboxGetHistory

end WsDropboxHandlers
