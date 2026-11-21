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
      // .ui.json sessions that never enter the index. The sidebar consumes this
      // endpoint WITHOUT the param and must stay index-only — that isolation is
      // the reason this is a query param.
      //
      // 会话搜索范围收窄批（2026-11-01，作者 17:29 令「主 agent 的聊天记录搜索只搜
      // 主会话，后台 agent 产生的消息一条不出」）：`includeUnindexed=1` 现在只返回
      // **主会话**（listSearchScopeSessions——判据单点 = SessionScope），后台 agent
      // 族（delegate/subtask/subagent/workflow/node/dispatcher/external/dag）一条
      // 不进。旧全扫引擎（chatSearch.js 的 fetchSessionList → 逐会话 history）因此
      // 与索引引擎（/api/search/chat）自动同一范围，前端零事后剔除。
      //
      // `includeBgAgents=1` 是**显式取全量**的旧形态口子（仅 bg-agent / flow 弹窗
      // 需要看自己的家族）：`includeUnindexed=1&includeBgAgents=1` ⇒ 旧的全量并集。
      case req @ GET -> Root / "sessions" =>
        withAuth(req) {
          val includeUnindexed = req.params.get("includeUnindexed").contains("1")
          val includeBgAgents = req.params.get("includeBgAgents").contains("1")
          val list =
            if includeUnindexed && includeBgAgents then sessionStore.listSessionsIncludeUnindexed
            else if includeUnindexed then sessionStore.listSearchScopeSessions
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

      // Session history — **paged** (手机面板卡顿修复批 2026-11-21 · 根因二b).
      //
      // 修前本腿写死 `getUiMessages(sessionId, 0, 0)`（`limit=0` = 不限量）⇒ 客户端
      // 传 `?limit=50` 与不传**字节完全相同**（实测 10 991 449B / 1600+ 条），前端
      // 只能整取后自己切页（chatSearch.js `fetchHistory` 头注自陈「backend ignores
      // unknown params today」）。超长会话在手机隧道腿上就是每开一次搜一次拉 11MB。
      //
      // 现按 WS 历史腿**同一档**归一（`WsSessionChatHandlers.clampHistoryLimit`：
      // 缺省 50、上限 500、坏值不惩罚）——两条历史读面单一口径，不新增第二套页码语义。
      //   · `limit` 缺席 ⇒ [[DefaultHistoryPage]]（**行为变更**：修前是「全部」）；
      //     要全量的旧消费方显式传 `limit=all`（或 `0`），这两者是本腿的显式
      //     「不限量」拼写，与 clampHistoryLimit 的 `<=0 → 默认` 故意分叉：REST 腿
      //     的历史消费方里有整表扫描用途（导出/索引重建），不能把它们静默截断。
      //   · `offset` 缺席 ⇒ 0（正向分页起点，与 `SessionStore.getUiMessages` 同义）。
      //   · 应答**只增不减**：`messages`/`total`/`sessionId` 三键逐字保留，新增
      //     `offset`/`limit`/`hasMore` 三键（加宽纪律，旧前端零改动可用）。
      case req @ GET -> Root / "sessions" / sessionId / "history" =>
        withAuth(req) {
          val rawLimit = req.params.get("limit")
          // `limit=all` / `limit=0` = this leg's explicit "no limit" spelling (bulk
          // readers: export, index rebuild). Everything else normalizes through the
          // WS leg's own clamp — one paging vocabulary, not two.
          val unlimited = rawLimit.exists(v => v == "all" || v.trim == "0")
          val limit =
            if unlimited then 0
            else WsSessionChatHandlers.clampHistoryLimit(rawLimit.flatMap(_.toIntOption))
          val offset = req.params.get("offset").flatMap(_.toIntOption).filter(_ >= 0).getOrElse(0)
          sessionStore.getUiMessages(sessionId, offset, limit).flatMap { case (messages, total) =>
            val start = math.min(offset, total)
            val returned = messages.size
            Ok(
              Json.obj(
                "messages" -> messages.asJson,
                "total" -> total.asJson,
                "sessionId" -> sessionId.asJson,
                "offset" -> start.asJson,
                "limit" -> limit.asJson,
                "hasMore" -> (start + returned < total).asJson
              )
            )
          }
        }

      // 按牌取原消息（msgchip 批 2026-11-01，作者令「消息身份牌系统升级」取数腿）：
      // **只读**端点——前端聊天气泡里的短名牌 chip（{{msg:sid8-seq6|短名}} 渲染形态）
      // 点击弹小窗时，跨会话/后台 agent 的牌经此按牌号取映射全文。同会话优先前端
      // 本地查（会话消息已在内存），本端点是权威回退腿；映射缺失/畸形牌面 = 404
      // `found:false`（前端降级「原消息不可达」，不崩）。入参 = 裸核心 sid8-seq6
      // （也容忍完整牌面，registry.lookup 单点剥形）。铸牌零涉入（纯读内存索引，
      // 惰性装载与消解同缝）。
      case req @ GET -> Root / "sessions" / "msg-token" / tokenId =>
        withAuth(req) {
          sharedResources.msgTokens.lookup(tokenId).flatMap {
            case None =>
              NotFound(Json.obj("found" -> false.asJson, "token" -> tokenId.asJson))
            case Some(m) =>
              val base = Json.obj(
                "found" -> true.asJson,
                "token" -> m.token.asJson,
                "sessionId" -> m.sessionId.asJson,
                "ts" -> m.ts.asJson,
                "text" -> m.text.asJson
              )
              sessionStore.getSessionMeta(m.sessionId).flatMap {
                case Some(meta) => Ok(base.deepMerge(Json.obj("sessionName" -> meta.name.asJson)))
                case None => Ok(base.deepMerge(Json.obj("sessionName" -> "".asJson)))
              }
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
