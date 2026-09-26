/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 会话域(sessions,F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的
 * /sessions 全族 case(同步 turn / 列表 / 历史 / 建 / 删)逐字迁入,行为保持;
 * 经 RestApiRoutes.routes 级联挂载,各臂首段路径字面量与其余域互不重叠。
 */
private[gateway] object SessionRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
      // Headless synchronous turn (P0 benchmark): send user text into a session
      // and block until the turn completes, returning the final assistant
      // message and tool trace. Logic lives in TurnEndpoint (testable);
      // this route adds auth + session validation.
      case req @ POST -> Root / "sessions" / sessionId / "turn" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val content = body.hcursor.downField("content").as[String].getOrElse("")
            val timeoutSec =
              body.hcursor.downField("timeoutSec").as[Int].getOrElse(1800).min(7200).max(1)
            if content.isEmpty then BadRequest(Json.obj("error" -> "content is required".asJson))
            else
              sessionStore.getSessionMeta(sessionId).flatMap {
                case None => NotFound(Json.obj("error" -> s"session not found: $sessionId".asJson))
                case Some(_) =>
                  TurnEndpoint.gated(sessionId) {
                    TurnEndpoint.runTurn(
                      wsHub,
                      sessionStore,
                      wsRoutes.dispatchHeadlessTurn,
                      sessionId,
                      content,
                      timeoutSec
                    )
                  }
              }
            end if
          }
        }

      // Session list. `includeUnindexed=1` (search scope) unions in on-disk-only
      // .ui.json sessions (delegate/subtask/dag sub-agents) that never enter the
      // index. The sidebar consumes this endpoint WITHOUT the param and must stay
      // index-only — that isolation is the reason this is a query param.
      case req @ GET -> Root / "sessions" =>
        withAuth(req) {
          val list =
            if req.params.get("includeUnindexed").contains("1") then sessionStore.listSessionsIncludeUnindexed
            else sessionStore.listSessions
          list.flatMap { sessions =>
            sessionStore.getActiveId.flatMap { activeId =>
              // 出口 overlay：逐会话 `safetyMode` 输出**有效档位** = 应用级全局持久值
              // （permshield S1 后已无会话覆盖面）。与 WS 出口共用同一个 helper——
              // CLI/QA 据此读到的是实际生效的档位，而不是 `_index.json` 的遗留值。
              sharedResources.overlaySessionList(sessions).flatMap { sessionsJson =>
                Ok(Json.obj("sessions" -> sessionsJson, "activeId" -> activeId.asJson))
              }
            }
          }
        }

      // Session history
      case req @ GET -> Root / "sessions" / sessionId / "history" =>
        withAuth(req) {
          sessionStore.getUiMessages(sessionId, 0, 0).flatMap { case (messages, total) =>
            Ok(Json.obj("messages" -> messages.asJson, "total" -> total.asJson, "sessionId" -> sessionId.asJson))
          }
        }

      // Create session
      case req @ POST -> Root / "sessions" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val name = body.hcursor.downField("name").as[String].getOrElse("New Session")
            val agentName = body.hcursor.downField("agentName").as[Option[String]].getOrElse(None)
            val folderId = body.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
            // 2026-09-12 权限全局单一权威源（设计 §10 #16 / §13 #13）：**不再把全局档位
            // 写进会话 meta**。新会话的**有效档位** = 全局值，由 resolver（覆盖 ?? 全局）
            // 保证，与盘上键无关 —— 此前把全局值写进 meta 正是"会话各自持有权威档位"的
            // 承载面（索引损坏恢复路径把顶档写回落盘 ⇒ R1/T-2）。
            sessionStore
              .createSession(name, agentName = agentName, folderId = folderId)
              .flatMap { meta =>
                Ok(meta.asJson)
              }
          }
        }

      // Delete session
      case req @ DELETE -> Root / "sessions" / sessionId =>
        withAuth(req) {
          // 2026-09-13（permshield S1）：此处原为"清该会话的内存权限覆盖条目"。覆盖层
          // 删除后档位是应用级的 ⇒ 删除会话**不得**（也无从）改动档位，本清理点移除。
          sessionStore.deleteSession(sessionId) *>
            Ok(Json.obj("deleted" -> true.asJson))
        }

    }
  end routes

end SessionRoutes
