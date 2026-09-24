/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import fs2.Stream
import io.circe.syntax.*
import io.circe.Json
import nebflow.core.hotrestart.HealthPayload
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

/**
 * 健康域(health,F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的
 * /health、/health/conn、/usage/aggregate、/tts、/command case 逐字迁入,
 * 行为保持;经 RestApiRoutes.routes 级联首位挂载,各臂首段路径字面量与
 * 其余域互不重叠。
 */
private[gateway] object HealthRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
      // Health check (P2-6 layered, 2026-08-25): `providers` = per-model health
      // (up / down:<reason>), `search` = Tier 2a standalone search API health
      // (unconfigured/up/down) — INDEPENDENT of model health, so "模型配额 DOWN
      // 但搜索 API 正常" is visible at a glance. `status` stays "ok" while the
      // gateway serves (the watchdog keys on HTTP 200).
      case GET -> Root / "health" =>
        // Payload construction is SINGLE-SOURCED in
        // `nebflow.core.hotrestart.HealthPayload.build` (hotupdate batch 2, G3): the
        // hot-restart door self-check (tier 3 "port-serving" / tier 4 "version-match")
        // reads the very same payload over HTTP, so the version a successor
        // advertises can never drift from what this endpoint serves. Fields,
        // field order and value semantics are unchanged (byte-compatible body).
        HealthPayload.build(sharedResources.healthMonitor).flatMap(Ok(_))

      // 连接面只读读数（R-1b conn-guard 批，设计件 §C④-4）：零凭据面（仅
      // 对端 IP + 计数 + 上限/超时口径 + fd 代理）。与 /health 同级无令牌门
      // （watchdog/作者探视用）；不含任何 header/body/token 内容。
      case GET -> Root / "health" / "conn" =>
        connGuard.snapshot.flatMap(s => Ok(ConnGuard.healthJson(s, ConnGuard.fdCount())))

      // Token consumption dashboard aggregate (2026-08-18): structured LLM usage
      // telemetry with dimension slicing.
      //   dim=provider|model|agent|hour|day (absent = totals only)
      //   from/to = epoch millis, inclusive lower / exclusive upper (both optional)
      //   provider/model/agent = exact-match filters, applied before grouping
      //     (dashboard D1: orthogonal to dim — all absent = unfiltered, and an
      //     empty value is treated as absent for backward compatibility)
      case req @ GET -> Root / "usage" / "aggregate" =>
        withAuth(req) {
          val params = req.uri.multiParams
          val dim = params.get("dim").flatMap(_.headOption)
          val from = params.get("from").flatMap(_.headOption).flatMap(_.toLongOption)
          val to = params.get("to").flatMap(_.headOption).flatMap(_.toLongOption)
          val provider = params.get("provider").flatMap(_.headOption).filter(_.nonEmpty)
          val model = params.get("model").flatMap(_.headOption).filter(_.nonEmpty)
          val agent = params.get("agent").flatMap(_.headOption).filter(_.nonEmpty)
          sharedResources.usageRecordStore
            .aggregate(dim, from, to, provider, model, agent)
            .flatMap(agg => Ok(agg.asJson))
        }

      // TTS 语音合成。门控（2026-09-20 收尾批）：原注记「无需 auth，内部调用」**已作废** ——
      // 唯一调用方是登录态 webui（`web/js/chat.js` `VoicePlayer._fetchTts`，本批已补带
      // `Authorization`）。与其余 REST 面同门：无令牌 403、带令牌照走业务体（`withAuth`
      // 纯套壳，业务体逐行保留）。门的判据在业务判据（`ttsService` 是否配置）**之前**。
      case req @ POST -> Root / "tts" =>
        withAuth(req) {
          ttsService match
            case None => NotFound(Json.obj("error" -> "TTS not configured".asJson))
            case Some(svc) =>
              req.as[Json].flatMap { body =>
                val text = body.hcursor.downField("text").as[String].getOrElse("")
                svc.synthesize(text).flatMap {
                  case Some(bytes) =>
                    IO.pure(
                      Response[IO](
                        status = Status.Ok,
                        headers = Headers(`Content-Type`(MediaType.audio.wav)),
                        body = Stream.emits(bytes).covary[IO]
                      )
                    )
                  case None => NotFound(Json.obj("error" -> "TTS synthesis failed".asJson))
                }
              }
        }

      // Generic command endpoint — mirrors WS messages
      case req @ POST -> Root / "command" =>
        withAuth(req) {
          req.as[Json].flatMap { payload =>
            val responseRef = cats.effect.Ref.unsafe[IO, Option[Json]](None)
            val wsSend = (json: Json) => responseRef.set(Some(json))
            wsRoutes.handleMessagePublic(payload.noSpaces, wsSend).flatMap { _ =>
              responseRef.get.flatMap {
                case Some(resp) => Ok(resp)
                case None => Ok(Json.obj("status" -> "ok".asJson))
              }
            }
          }
        }

    }
  end routes

end HealthRoutes
