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
 * 参数面：q（关键词，必填）/kinds（类型片，逗号分隔 message|media|file|link——
 * tool 片已退役：聊天记录搜索优化批 2026-10-30 作者令①，搜索结果只搜对话，
 * 传入的 "tool" 在此剥除，剥后为空且无其他谓词 ⇒ 503 回落旧全扫）/sid（会话
 * 键——current 档）/since/until（epoch ms 时间片）/limit/offset（真分页）。
 * 谓词纪律（§4.1 纪律 3）：kinds/sid/since/until 至少一个；无谓词 ⇒ 503 +
 * 前端回落旧全扫（灰度零风险——索引缺失/陈旧时行为与今天逐字一致）。
 *
 * 旧库残留兜底：改动前入库的 kind="tool" 行在会话下次变更重建前可能仍在库，
 * 出结果前一律再过滤（hits 剔除 + total 按页内剔除数减扣）——「不入结果」对
 * 任何查询形状都成立，残留随会话重建自然收敛为零。
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
          // 作者令①：tool 片退役——剥除调用方传入的 "tool"（其余片原样透传）。
          val kinds  = params.get("kinds").map(_.split(',').map(_.trim).filter(_.nonEmpty).toList)
            .getOrElse(Nil).filter(_ != "tool")
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
                // 旧库残留兜底：改动前的 kind="tool" 行会话重建前仍在库——
                // 出结果前剔除（total 按页内剔除数减扣；残留随重建收敛为零后
                // 此过滤为恒等，成本可忽略）。
                val staleTools = result.hits.count(_.kind == "tool")
                val hits       = result.hits.filter(_.kind != "tool")
                val total      = math.max(0, result.total - staleTools)
                val payload = Json.obj(
                  "hits"     -> hits.map(hitJson).asJson,
                  "total"    -> total.asJson,
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
