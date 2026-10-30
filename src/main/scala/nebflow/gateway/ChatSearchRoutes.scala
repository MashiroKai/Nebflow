package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.search.SearchIndexService
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * ChatSearchRoutes —— 聊天检索查询 API（datalayer-spec 批 2026-10-30，C2）。
 *
 * `GET /api/search/chat` —— 与旧全扫路径**并行存在**（设计稿 §4.6 R4：
 * 新端点并行、旧路径保底可回退；前端特性开关切换，见 chatSearch.js）。
 *
 * 参数面：q（关键词，必填）/kinds（类型片，逗号分隔 message|tool|media|
 * file|link）/sid（会话键——current 档）/since/until（epoch ms 时间片）/
 * limit/offset（真分页）。谓词纪律（§4.1 纪律 3）：kinds/sid/since/until
 * 至少一个；无谓词 ⇒ 503 + 前端回落旧全扫（灰度零风险——索引缺失/陈旧
 * 时行为与今天逐字一致）。
 *
 * 凭证（§4.4 / C6 服务端半边）：结果带 ETag（Σ命中会话 version 结构键），
 * `If-None-Match` 命中 ⇒ 304 零重搜零重发（HttpCache.conditionalOk 复用，
 * 六端点已验证的模式）。
 */
private[gateway] object ChatSearchRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "search" / "chat" =>
        withAuth(req) {
          val params = req.params
          val q      = params.get("q").map(_.trim).getOrElse("")
          val kinds  = params.get("kinds").map(_.split(',').map(_.trim).filter(_.nonEmpty).toList).getOrElse(Nil)
          val sid    = params.get("sid").filter(_.nonEmpty)
          val since  = params.get("since").flatMap(v => scala.util.Try(v.toLong).toOption)
          val until  = params.get("until").flatMap(v => scala.util.Try(v.toLong).toOption)
          val limit  = params.get("limit").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(50)
          val offset = params.get("offset").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(0)

          SearchIndexService
            .query("chat", SearchIndexService.Query(q, kinds, sid, since, until, limit, offset))
            .flatMap {
              case Left(reason) =>
                // 降级面：索引未就绪/谓词缺失/查询失败 ⇒ 503 + 理由，
                // 前端回落旧全扫路径（行为与未接入索引前逐字一致）。
                ServiceUnavailable(Json.obj("error" -> "search-index unavailable".asJson, "reason" -> reason.asJson))
              case Right(result) =>
                val payload = Json.obj(
                  "hits"     -> result.hits.map(hitJson).asJson,
                  "total"    -> result.total.asJson,
                  "degraded" -> result.degraded.asJson
                )
                HttpCache.conditionalOk(req, payload, result.etag)
            }
        }
    }

  private def hitJson(h: SearchIndexService.Hit): Json =
    Json.obj(
      "sid"     -> h.sid.asJson,
      "ord"     -> h.ord.asJson,
      "kind"    -> h.kind.asJson,
      "ts"      -> h.ts.asJson,
      "snippet" -> h.snippet.asJson,
      "extra"   -> parseExtra(h.extra)
    )

  /** extra 投影字段：合法 JSON 原样嵌入，非法/空 ⇒ 空对象（宽松读法）。 */
  private def parseExtra(raw: String): Json =
    if raw == null || raw.isEmpty then Json.obj()
    else io.circe.parser.parse(raw).getOrElse(Json.obj())

end ChatSearchRoutes
