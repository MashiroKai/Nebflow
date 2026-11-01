package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 控制台单实例 · 【重新接管】HTTP 腿（接管必达批 2026-11-01 作者令①）。
 *
 * `POST /api/takeover/retake` —— 被顶出页面点【重新接管】的**必达**入口：
 *   · 会话身份 = HttpOnly cookie（session-rail only，`Rails.StaticAuth`）——与 ws
 *     腿的连接注册同轨（Bearer/CLI 不参与接管语义，pat/bearerFile 双关）；
 *   · 回执 = HTTP 响应本体（同步、无队列）——ws 腿回执可被出站队列溢出拆连/
 *     死连接静默吞掉的缺口在本腿结构性封死；
 *   · 不要求本会话在连接账上有活连接（页面在 = 会话在；ws 死了也必须能夺回），
 *     所有权翻面 + 反向踢与 ws 腿逐字同核（`ConsoleTakeover.retakeBySession`）。
 *
 * 响应契约（前端 takeoverModal.js 消费；业务裁决恒 200 + ok 字段，非 200 只留
 * 给鉴权面）：
 *   200 {"ok":true,"kicked":n}            —— 所有权已翻回，n = 反向踢送达连接数
 *   200 {"ok":false,"error":"disabled"}   —— 单实例总闸关闭（无接管语义可言）
 *   403 {"error":"Unauthorized"} + X-Nf-Reason: credential-required —— 会话轨
 *     拒绝（与 RestApiCtx.authRejected 同形，前端 sessionGate 分流判据不变）。
 */
object ConsoleTakeoverRoutes:

  def routes(
    sessions: GatewaySessionStore,
    takeover: ConsoleTakeover,
    logger: NebflowLogger
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "takeover" / "retake" =>
      // 会话轨 only（Rails.StaticAuth = session 开、双 Bearer 关）：接管是控制台
      // 页面语义，与 ws 腿的连接注册同轨。fileToken 传 ""——bearerFile=false 时
      // 该值不进任何比较（GatewayAuth.bearerLeg 首闸即 rails 检查）。
      GatewayAuth.authenticate(req, "", sessions, GatewayAuth.Rails.StaticAuth).flatMap {
        case Some(Credential.ViaSession(record)) =>
          takeover.retakeBySession(record.sessionId).flatMap {
            case ConsoleTakeover.RetakeOutcome.Ok(kicked) =>
              logger.info(
                s"console-takeover: http retake ok (session ${record.sessionId.take(8)}…, kicked=$kicked)"
              ) *> Ok(Json.obj("ok" -> true.asJson, "kicked" -> kicked.asJson))
            case ConsoleTakeover.RetakeOutcome.Disabled =>
              logger.warn(s"console-takeover: http retake refused — master switch off") *>
                Ok(Json.obj("ok" -> false.asJson, "error" -> "disabled".asJson))
          }
        case _ =>
          logger.warn(s"console-takeover: http retake rejected — no session credential") *>
            Forbidden(Json.obj("error" -> "Unauthorized".asJson)).map(
              _.putHeaders(
                Header.Raw(org.typelevel.ci.CIString("X-Nf-Reason"), "credential-required")
              )
            )
      }
    }

end ConsoleTakeoverRoutes
