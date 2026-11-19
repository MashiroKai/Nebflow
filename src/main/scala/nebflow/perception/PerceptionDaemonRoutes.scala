package nebflow.perception

import cats.effect.IO
import cats.data.OptionT
import cats.syntax.all.*
import io.circe.{Encoder, Decoder, Json}
import io.circe.syntax.*
import io.circe.generic.semiauto.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.server.Router
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 统一 daemon 管理进程的 HTTP 面（统一 daemon 批 2026-10-29）。挂载于 daemon
 * 进程 `127.0.0.1:<port>`（[[PerceptionDaemonMain]]），**全端点 token 门**
 * （`X-Nf-Perception-Token` == `<dataRoot>/perception/daemon.token`，0600；
 * 引擎每 boot 轮换并先于 spawn 落盘）。三组：
 *
 *  1. `GET /health` —— 健康面（引擎 dock 握手 + DaemonService 端口探针 +
 *     冒烟族消费）：booted / uptime / role 标识。
 *  2. `/api/perception/…`  —— 面板 API 权威实现（[[PerceptionPanelRoutes.panel]]
 *     迁入的同一张处理表；引擎 `/api/perception/…`  是它的逐字代理，会话门照旧）。
 *  3. `/internal/perception/…`  —— 引擎工具腿（EmailSend / AskUser 开窗）：
 *     has-account / create-draft / attach-request。ingest 三腿（deliver/
 *     broadcast/llm）在引擎侧（[[nebflow.gateway.PerceptionIngestRoutes]]），
 *     本文件不承担。
 */
object PerceptionDaemonRoutes:

  /** Token 头名（引擎 ingest 门同款——单一拼写，两进程各校验各的）。 */
  val TokenHeader: String = "X-Nf-Perception-Token"

  /** Token 门（常数时间比较；头缺失/不匹配 ⇒ 403——引擎会话门同款拒绝面）。 */
  def tokenGate(token: String)(inner: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO] { req =>
      val presented = req.headers.get(CIString(TokenHeader)).map(_.head.value).getOrElse("")
      val ok = MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8),
        token.getBytes(StandardCharsets.UTF_8)
      )
      if ok then inner(req)
      else OptionT.liftF(Forbidden(Json.obj("error" -> "Unauthorized".asJson)))
    }

  /** 组装：health + panel（权威实现）+ 引擎工具腿（全部 token 门后）。 */
  def routes(
    resolve: IO[Option[PerceptionCore]],
    health: IO[Json],
    token: String
  ): HttpRoutes[IO] =
    tokenGate(token)(
      Router(
        "/api/perception" -> PerceptionPanelRoutes.panel(resolve),
        "/internal/perception" -> internalLegs(resolve),
        "" -> HttpRoutes.of[IO] {
          case GET -> Root / "health" => Ok(health)
          case _                      => NotFound(Json.obj("error" -> "not found".asJson))
        }
      )
    )

  /** 引擎工具腿（EmailSend / AskUser 开窗；全路径 `/internal/perception/…` ）。 */
  private def internalLegs(resolve: IO[Option[PerceptionCore]]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {

      case POST -> Root / "has-account" =>
        resolve.flatMap {
          case None       => daemonDown
          case Some(core) => core.hasAccount.flatMap(h => Ok(Json.obj("hasAccount" -> h.asJson)))
        }

      case req @ POST -> Root / "create-draft" =>
        resolve.flatMap {
          case None => daemonDown
          case Some(core) =>
            req.as[Json].flatMap { body =>
              body.as[DraftDocWire].toOption match
                case Some(wire) =>
                  core.createDraft(
                    DraftCodec.DraftDoc(
                      draftId = "",
                      to = wire.to,
                      cc = wire.cc,
                      bcc = wire.bcc,
                      subject = wire.subject,
                      bodyHtml = wire.bodyHtml,
                      attachments = wire.attachments,
                      replyToMessageId = wire.replyToMessageId,
                      quoteHtml = wire.quoteHtml
                    )
                  ).flatMap {
                    case Right(rec) => Ok(rec.asJson)
                    case Left(err)  => BadRequest(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
                  }
                case None => BadRequest(Json.obj("ok" -> false.asJson, "error" -> "draft doc required".asJson))
            }
        }

      case req @ POST -> Root / "attach-request" =>
        resolve.flatMap {
          case None => daemonDown
          case Some(core) =>
            req.as[Json].flatMap { body =>
              val draftId = str(body, "draftId")
              val requestId = str(body, "requestId")
              if draftId.isBlank || requestId.isBlank then
                BadRequest(Json.obj("ok" -> false.asJson, "error" -> "draftId/requestId required".asJson))
              else core.attachRequest(draftId, requestId).flatMap(_ => Ok(Json.obj("ok" -> true.asJson)))
            }
        }
    }

  private def daemonDown: IO[Response[IO]] =
    ServiceUnavailable(Json.obj("ok" -> false.asJson, "error" -> "perception daemon not booted".asJson))

  private def str(j: Json, k: String): String =
    j.hcursor.downField(k).as[String].getOrElse("")

  /** DraftDoc 线格式（引擎 EmailSend 工具腿专用；本地编解码，不动 DraftCodec）。 */
  private case class DraftDocWire(
    to: List[String],
    cc: List[String],
    bcc: List[String],
    subject: String,
    bodyHtml: String,
    attachments: List[String],
    replyToMessageId: Option[String],
    quoteHtml: Option[String]
  )
  private object DraftDocWire:
    given Encoder[DraftDocWire] = deriveEncoder
    given Decoder[DraftDocWire] = deriveDecoder

end PerceptionDaemonRoutes
