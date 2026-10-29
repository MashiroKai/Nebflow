package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.perception.{MailImportanceJudge, PerceptionModel}
import PerceptionModel.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 引擎侧 ingest 端点（统一 daemon 批 2026-10-29）——统一 daemon 管理进程的
 * 三条出域腿的承接面（`X-Nf-Perception-Token` 门，非会话门：daemon 无会话；
 * token = `<dataRoot>/perception/daemon.token` 0600，每引擎 boot 轮换）。
 *
 *  - `POST /api/perception/internal/deliver` —— 投递信封注入：引擎侧
 *    `MailTool.resolveRoots` + ImmediateInput（原 PerceptionCore.injectEnvelope
 *    的引擎半边，随进程拆分回引擎）；不可达 ⇒ `{"delivered":false}`，daemon
 *    落本地 spool（「引擎停机不丢信」语义原样）。
 *  - `POST /api/perception/internal/broadcast` —— WS 事件帧入 wsHub
 *    （perception.state.changed / otp.code / otp.expired / draft.updated …）。
 *  - `POST /api/perception/internal/llm` —— L1 判定 transport：daemon 出域
 *    邮件面（IncomingMail）⇒ 引擎 `MailImportanceJudge.promptFor` +
 *    `resources.llm.send` 代答（**裁决解析在 daemon**——判定逻辑随进程走）。
 *
 * 挂载序纪律：必须排在 PerceptionRoutes（代理腿）**之前**——`/api/perception/
 * internal/…` 与面板路径同前缀，先到先得。
 */
private[gateway] object PerceptionIngestRoutes:

  private val TokenHeader = "X-Nf-Perception-Token"

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req if req.pathInfo.renderString.startsWith("/perception/internal/") =>
        // token 单源 = dock 腿的 endpoint 登记（每引擎 boot 轮换；未 docked
        // ⇒ 403——daemon 只会在被拉起后才 ingest，届时握手已绑定）。
        nebflow.perception.PerceptionClient.current.flatMap {
          case None => forbidden
          case Some(ep) =>
            val presented = req.headers.get(CIString(TokenHeader)).map(_.head.value).getOrElse("")
            if MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                ep.token.getBytes(StandardCharsets.UTF_8)
              )
            then handle(req, ctx)
            else forbidden
        }
    }

  private def forbidden: IO[Response[IO]] =
    Forbidden(Json.obj("error" -> "Unauthorized".asJson))

  private def handle(req: Request[IO], ctx: RestApiCtx): IO[Response[IO]] =
    val segments = req.pathInfo.renderString.split("/").filter(_.nonEmpty).toList
    segments match
      case "perception" :: "internal" :: "deliver" :: Nil if req.method == Method.POST =>
        req.as[Json].flatMap { body =>
          val text = str(body, "text")
          val priority = str(body, "priority")
          if text.isBlank then BadRequest(Json.obj("delivered" -> false.asJson, "error" -> "text required".asJson))
          else deliverEnvelope(ctx, text, priority)
        }
      case "perception" :: "internal" :: "broadcast" :: Nil if req.method == Method.POST =>
        req.as[Json].flatMap { body =>
          ctx.wsHub.broadcast(body).flatMap(_ => Ok(Json.obj("ok" -> true.asJson)))
        }
      case "perception" :: "internal" :: "llm" :: Nil if req.method == Method.POST =>
        req.as[Json].flatMap { body =>
          body.as[IncomingMail].toOption match
            case None => BadRequest(Json.obj("error" -> "mail required".asJson))
            case Some(mail) =>
              ctx.sharedResources.llm
                .send(MailImportanceJudge.promptFor(mail))
                .flatMap(r => Ok(Json.obj("reply" -> r.reply.asJson)))
                .handleErrorWith(e => Ok(Json.obj("reply" -> "".asJson, "error" -> e.getMessage.take(120).asJson)))
        }
      case _ => NotFound(Json.obj("error" -> "not found".asJson))

  /**
   * 投递注入（原 injectEnvelope 引擎半边逐字）：唯一根（Nebula 判据）⇒ 注入
   * ImmediateInput；零根/多根 ⇒ `delivered:false`（daemon spool，回放腿补投
   * ——多根从不臆测，同原语义）。
   */
  private def deliverEnvelope(ctx: RestApiCtx, text: String, priority: String): IO[Response[IO]] =
    nebflow.core.tools.MailTool.resolveRoots(ctx.sharedResources, senderSessionId = "", preferredRootSid = None).flatMap {
      case List((sid, ref)) =>
        (ref ! nebflow.actor.AgentCommand.ImmediateInput(
          text = text,
          source = Some("perception"),
          eventType = Some(s"perception.$priority"),
          sender = Some("感知"),
          fromUser = false
        )).flatMap(_ => Ok(Json.obj("delivered" -> true.asJson)))
      case _ =>
        Ok(Json.obj("delivered" -> false.asJson))
    }

  private def str(j: Json, k: String): String =
    j.hcursor.downField(k).as[String].getOrElse("")

end PerceptionIngestRoutes
