package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.{NebflowServiceConfig, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

/**
 * 感知域路由门的契约钉（Missing token 报障批 2026-10-26）。
 *
 * 报障：作者在 relay 源控制台点「连接邮箱」得红字「Missing token」而引擎日志
 * 12:39-12:43 窗口零鉴权拒绝记录。定因：该字串是 link-server ingress 门
 * （`require_user`，routes.rs）的 403 体——请求在隧道之前被拒，**根本没到引擎**；
 * 引擎自己的鉴权拒绝对照体是 403 `{"error":"Unauthorized"}` +
 * `X-Nf-Reason: credential-required`（RestApiCtx 稳定性批 2026-10-26 的可分形出向）。
 *
 * 本 spec 把引擎侧形状钉成结构事实（廉价、零端口、零 HOME 写；withAuth 是
 * by-name 门——拒绝臂业务体零执行，null 依赖安全，同 ApiGuardFollowupRoutesSpec
 * 先例）：
 *
 *  1. `POST /api/perception/mail/connect` 无令牌 ⇒ 403「Unauthorized」+ reason 头。
 *     「Missing token」四字必须是 ingress 门独占形状，引擎侧永不出——前端与
 *     排障据此把两种 403 分形（有 reason = 引擎形状；无 reason JSON = 门形状）。
 *  2. `GET /api/perception/entries` 无令牌 ⇒ 同形 403（面板打开腿同门同体）。
 *
 * 「带合法令牌落到业务判据」的写穿腿由真实例 E2E 承担（connect 连真 IMAP，
 * 廉价 spec 不 boot 感知守护进程）。
 */
class PerceptionRoutesAuthGateSpec extends CatsEffectSuite:

  private val TestToken = "test-token-perception-authgate"

  /** 只构造路由表本身：拒绝臂不触 sharedResources / sessionStore（by-name 门），
    * 故按最小依赖传 null 即可（ApiGuardFollowupRoutesSpec 同法）。 */
  private def mkRoutes(): RestApiRoutes =
    new RestApiRoutes(
      tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null,
      neblinkService = None,
      ttsService = None
    )

  private def assertAuthRejected(resp: Response[IO]): IO[Unit] = IO {
    assertEquals(resp.status, Status.Forbidden, "引擎鉴权拒绝 = 403（不是 401，也不是门的形状混淆）")
    assertEquals(
      resp.headers.get(org.typelevel.ci.CIString("X-Nf-Reason")).map(_.head.value),
      Some("credential-required"),
      "网关 403 必须带 reason 头——无 reason 的 JSON 403 是 relay 门形状，前端据此分流"
    )
  }

  private def bodyOf(resp: Response[IO]): IO[Json] = resp.as[Json]

  // 路径形态 = Router 相对：生产挂载是 Router("/api" -> restApiRoutes.routes)
  //（GatewayMain），域内 case 全部以 `/perception/...` 相对段匹配。本 spec 曾
  // 直书 `/api/perception/...`——直调 routes 不经 Router 前缀剥离 ⇒ 永久
  // fall-through（authchain 批 REPORT §3.2 注记「sbt 运行延至队列空窗」的
  // 欠账，报障 2026-10-28 回归批补跑时实证）。修路径不改契约。
  private def api(path: String): Uri = Uri.unsafeFromString(path.stripPrefix("/api"))

  test("POST /api/perception/mail/connect 无令牌 ⇒ 403「Unauthorized」+ reason 头（业务体零执行）") {
    mkRoutes().routes(
      Request[IO](Method.POST, api("/api/perception/mail/connect"))
        .withEntity(Json.obj("address" -> "fake@qq.com".asJson, "authCode" -> "bad".asJson))
    ).value.map(_.getOrElse(fail("route fell through"))).flatMap { resp =>
      assertAuthRejected(resp) *> bodyOf(resp).map { body =>
        assertEquals(body.hcursor.downField("error").as[String].toOption, Some("Unauthorized"),
        "「Missing token」是 ingress 门独占形状——引擎侧鉴权拒绝的 error 字恒为 Unauthorized")
      }
    }
  }

  test("GET /api/perception/entries 无令牌 ⇒ 同形 403（面板打开腿同门同体）") {
    mkRoutes().routes(
      Request[IO](Method.GET, api("/api/perception/entries"))
    ).value.map(_.getOrElse(fail("route fell through"))).flatMap { resp =>
      assertAuthRejected(resp) *> bodyOf(resp).map { body =>
        assertEquals(body.hcursor.downField("error").as[String].toOption, Some("Unauthorized"))
      }
    }
  }

end PerceptionRoutesAuthGateSpec
