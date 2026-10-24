/* askucancel 批（2026-10-08）：AskUser 取消能力的 REST 域 + **共享取消执行器**。
 *
 * 三个取消入口（WS 帧 `askUserCancel` / 本 REST 域 / BridgeContext 渠道面）都
 * 收敛到 [[cancelOnce]] 一处：hub `CancelRequest`（kind 闸 + 发起方终结通知 +
 * 显式裁决）→ 裁决为 Cancelled 时在**卡片所属会话**（rootSessionId）追加一条
 * `__cancelled__` 作答行——与前端卡片取消按钮既有落盘形态逐字同构
 * （chat.js 取消回填 answers=['__cancelled__']；persistence.js 据此把历史卡渲染
 * 成已取消态），单一构造点仍是 `UiMessage.askUserAnswer`（案 B 的 answerOf
 * 数据驱动读法不受影响）。
 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.InteractionHubCommand
import nebflow.agent.SharedResources
import nebflow.core.SessionStore
import nebflow.core.tools.ProjectTool
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

private[gateway] object AskUserCancelRoutes:

  /** 一次取消的出口读数。`state`：cancelled / unknown / not-ask-user / no-hub。 */
  final case class Verdict(state: String, kind: Option[String] = None, sessionId: Option[String] = None):
    def ok: Boolean = state == "cancelled"

    /** 机器可读体（WS 结果帧与 REST 响应体共用一套字段）。 */
    def json(requestId: String): Json =
      Json.obj(
        "requestId" -> requestId.asJson,
        "state" -> state.asJson,
        "kind" -> kind.map(_.asJson).getOrElse(Json.Null),
        "sessionId" -> sessionId.map(_.asJson).getOrElse(Json.Null)
      )
  end Verdict

  /**
   * 共享取消执行器（唯一实现，三个入口委托）。`reason` 缺省语义在 hub 侧补
   * `"user-cancelled"`；`by` = 取消发起面（web / rest / feishu / weixin-ilink /
   * bridge-displace…），只进日志与审计行，不参与判定。
   *
   * 落盘行**只**在 `Cancelled` 裁决后追加（先取消后落盘：hub 说 unknown 时绝不
   * 落一条伪造的取消行——那会把仍 pending 的卡在历史里渲染成已取消）。落盘失败
   * 不回滚取消（取消已生效，记录面降级为 WARN）。
   */
  def cancelOnce(
    sharedResources: SharedResources,
    sessionStore: SessionStore,
    requestId: String,
    reason: String,
    by: String
  ): IO[Verdict] =
    sharedResources.interactionHubRef.get.flatMap {
      case None => IO.pure(Verdict("no-hub"))
      case Some(hub) =>
        hub
          .?[nebflow.actor.AskCancelOutcome](reply =>
            InteractionHubCommand.CancelRequest(requestId, reason, by, Some(reply))
          )
          .flatMap {
            case nebflow.actor.AskCancelOutcome.Cancelled(rid, kind, rootSessionId, _) =>
              sessionStore
                .appendUiMessages(
                  rootSessionId,
                  // 取消落盘行：与前端卡片取消按钮同形态（哨兵 = ProjectTool 单点常量，
                  // UiMessage.askUserAnswer 仍是唯一构造点；answerOf=rid 保证案 B 的
                  // 数据驱动历史读法直接把这张卡渲染成已取消）。
                  List(
                    nebflow.shared.UiMessage.askUserAnswer(ProjectTool.CancelSentinel, rid, System.currentTimeMillis())
                  )
                )
                .handleErrorWith(_ => IO.unit) *> IO.pure(
                Verdict("cancelled", Some(kind.toString), Some(rootSessionId))
              )
            case nebflow.actor.AskCancelOutcome.UnknownRequestId => IO.pure(Verdict("unknown"))
            case nebflow.actor.AskCancelOutcome.NotAskUser(kind) =>
              IO.pure(Verdict("not-ask-user", Some(kind.toString)))
          }
    }
  end cancelOnce

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] { case req @ POST -> Root / "askuser" / "cancel" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val requestId = body.hcursor.downField("requestId").as[String].toOption.map(_.trim).filter(_.nonEmpty)
          val reason = body.hcursor
            .downField("reason")
            .as[String]
            .toOption
            .map(_.trim)
            .filter(_.nonEmpty)
            .getOrElse("user-cancelled")
          val by = body.hcursor.downField("by").as[String].toOption.map(_.trim).filter(_.nonEmpty).getOrElse("rest")
          requestId match
            case None =>
              BadRequest(
                Json.obj("error" -> "requestId is required (the asknb-/panel- id from the askUser frame)".asJson)
              )
            case Some(rid) =>
              cancelOnce(sharedResources, sessionStore, rid, reason, by).flatMap {
                case v if v.ok => Ok(v.json(rid))
                case v @ Verdict("unknown", _, _) =>
                  NotFound(
                    v.json(rid)
                      .deepMerge(
                        Json.obj(
                          "error" -> s"no pending ask with requestId=$rid (already answered, closed, or never existed)".asJson
                        )
                      )
                  )
                case v @ Verdict("not-ask-user", _, _) =>
                  Conflict(
                    v.json(rid)
                      .deepMerge(
                        Json.obj(
                          "error" -> s"slot $rid is a ${v.kind.getOrElse("?")} card — only AskUser questions are cancellable".asJson
                        )
                      )
                  )
                case v => BadGateway(v.json(rid).deepMerge(Json.obj("error" -> "interaction hub unavailable".asJson)))
              }
          end match
        }
      }
    }
  end routes
end AskUserCancelRoutes
