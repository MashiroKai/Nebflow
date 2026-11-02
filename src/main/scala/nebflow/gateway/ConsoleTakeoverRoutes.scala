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
 *
 * 误报根修批（2026-11-02）：实例身份 = 请求体声明的 `clientId`（前端
 * `deviceIdentity.js` 的稳定 id，与本页 ws 握手的 `?clientId=` 同一个值）+ 会话
 * 退路——**必须与 ws 入账同键**，否则「点重新接管 ⇒ reload ⇒ 新连接」会被自己
 * 认成另一个实例再弹一次提示。clientId 缺失 = 老缓存页 ⇒ 落会话退路（既有行为）。
 */
object ConsoleTakeoverRoutes:

  /** 请求体 clientId 上限（防御性；前端值是 UUID）。 */
  private val ClientIdMax = 128

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
          // 声明面 clientId（可选；解析失败按缺席走会话退路，绝不因坏体 500）。
          val clientIdF: IO[Option[String]] =
            req.attemptAs[Json].value.map {
              case Right(json) =>
                json.hcursor
                  .downField("clientId")
                  .as[String]
                  .toOption
                  .map(_.trim)
                  .filter(id => id.nonEmpty && id.length <= ClientIdMax)
              case Left(_) => None
            }
          clientIdF.flatMap { clientId =>
            takeover.retakeBySession(record.sessionId, clientId).flatMap {
              case ConsoleTakeover.RetakeOutcome.Ok(kicked) =>
                logger.info(
                  s"console-takeover: http retake ok (session ${record.sessionId.take(8)}…, " +
                    s"client=${clientId.map(_.take(8)).getOrElse("-")}, kicked=$kicked)"
                ) *> Ok(Json.obj("ok" -> true.asJson, "kicked" -> kicked.asJson))
              case ConsoleTakeover.RetakeOutcome.Disabled =>
                logger.warn(s"console-takeover: http retake refused — master switch off") *>
                  Ok(Json.obj("ok" -> false.asJson, "error" -> "disabled".asJson))
            }
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
