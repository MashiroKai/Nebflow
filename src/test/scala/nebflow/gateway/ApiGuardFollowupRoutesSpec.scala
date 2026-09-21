package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.llm.{NebflowServiceConfig, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

/**
 * 收尾微批（2026-09-20，作者裁定 + 分发器定形）· 两条停手路由加门的**契约钉**。
 *
 * 上一批「卡05 加固批」把两条路由主动停手（合法调用方没带令牌），本批把「客户端补带 +
 * 服务端加门」合成一批落地。本 spec 钉的是**服务端半边**，且刻意不加任何网络/实例依赖：
 *
 *  1. `POST /tts` —— 无令牌 403；带令牌 + `ttsService=None` ⇒ 404「TTS not configured」。
 *     🔴 这两条**成对**才有意义：同一服务状态（未配置）下「无令牌 404 / 有令牌 404」
 *     是上游实测到的**假绿陷阱**（`RestApiRoutes` 业务体的 404 与门的 403 在读数上不可
 *     区分 ⇒ 探针丧失区分力）。本 spec 用「同一 `ttsService=None`，令牌不同、状态码不同」
 *     把「门在业务判据之前」钉成结构事实，任何去掉 `withAuth` 的变异都会让第一条变 404。
 *  2. 旧 `GET /api/neblink/auth/end-session`（本批退场，分发器定形 ⒜(ii)）—— **门内 405**：
 *     无令牌 403、带令牌 405 + `{"error":"method-not-allowed","allow":"POST"}`。
 *     该 arm 不 arm/disarm 切换标记、不碰凭据 ⇒ 旧「GET 带副作用」的副作用不可达。
 *  3. `POST /api/neblink/auth/end-session` —— 无令牌 403；带令牌 + 无 NebLink 服务 ⇒
 *     404「NebLink service not initialized」（过了门才落到业务判据）。
 *
 * 端到端（真实例 + 真凭据拆除 + 302→200 链）见 impl 报告的隔离实例探针；本 spec 只承担
 * 门控契约与失败支的**廉价结构钉**（零端口、零 HOME 写、可随时单跑）。
 */
class ApiGuardFollowupRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-apiguard-followup"

  private val bearer = Headers("Authorization" -> s"Bearer $TestToken")

  /** 只构造路由表本身：本 spec 命中过的 arm 均不触 `sharedResources` / `configRef`
    * （TTS 未配置臂、门控拒绝臂、未初始化臂），故按最小依赖传入 null 即可。 */
  private def mkRoutes(tts: Option[TtsService] = None): RestApiRoutes =
    new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null,
      neblinkService = None,
      ttsService = tts
    )

  private def post(path: String, withToken: Boolean, body: Json = Json.obj()): Request[IO] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body)
    if withToken then base.withHeaders(bearer) else base

  private def get(path: String, withToken: Boolean): Request[IO] =
    val base = Request[IO](Method.GET, Uri.unsafeFromString(path))
    if withToken then base.withHeaders(bearer) else base

  private def run(req: Request[IO]): IO[(Status, Json)] =
    mkRoutes().routes(req).value.map(_.getOrElse(fail("route fell through"))).flatMap { resp =>
      resp.as[Json].map(json => (resp.status, json))
    }

  // ── TTS：门在业务判据之前 ────────────────────────────────────────────────

  test("POST /tts 无令牌 ⇒ 403 Unauthorized（业务体不执行）") {
    run(post("/tts", withToken = false, Json.obj("text" -> "hi".asJson))).map { case (status, body) =>
      assertEquals(status, Status.Forbidden)
      assertEquals(body.hcursor.downField("error").as[String].toOption, Some("Unauthorized"))
    }
  }

  test("POST /tts 带令牌 + 未配置 TTS ⇒ 404「TTS not configured」（同一服务状态、令牌不同 ⇒ 状态码不同 = 门的区分力）") {
    run(post("/tts", withToken = true, Json.obj("text" -> "hi".asJson))).map { case (status, body) =>
      assertEquals(status, Status.NotFound, "带令牌必须过门并落到业务判据（ttsService=None ⇒ 404）")
      assertEquals(body.hcursor.downField("error").as[String].toOption, Some("TTS not configured"))
    }
  }

  // ── end-session：新 POST 面在门内 ────────────────────────────────────────

  test("POST /neblink/auth/end-session 无令牌 ⇒ 403（未门控集不得新增条目）") {
    run(post("/neblink/auth/end-session", withToken = false, Json.obj("scenario" -> "switch".asJson))).map {
      case (status, body) =>
        assertEquals(status, Status.Forbidden)
        assertEquals(body.hcursor.downField("error").as[String].toOption, Some("Unauthorized"))
    }
  }

  test("POST /neblink/auth/end-session 带令牌 + 无 NebLink 服务 ⇒ 404「NebLink service not initialized」") {
    run(post("/neblink/auth/end-session", withToken = true, Json.obj("scenario" -> "switch".asJson))).map {
      case (status, body) =>
        assertEquals(status, Status.NotFound, "带令牌必须过门、落到业务判据")
        assertEquals(body.hcursor.downField("error").as[String].toOption, Some("NebLink service not initialized"))
    }
  }

  // ── 旧 GET 面：门内 405，副作用不可达 ────────────────────────────────────

  test("旧 GET /neblink/auth/end-session 无令牌 ⇒ 403（405 在门内，不泄露方法语义）") {
    run(get("/neblink/auth/end-session", withToken = false)).map { case (status, body) =>
      assertEquals(status, Status.Forbidden)
      assertEquals(body.hcursor.downField("error").as[String].toOption, Some("Unauthorized"))
    }
  }

  test("旧 GET /neblink/auth/end-session 带令牌 ⇒ 405 method-not-allowed + allow=POST") {
    run(get("/neblink/auth/end-session", withToken = true)).map { case (status, body) =>
      assertEquals(status, Status.MethodNotAllowed)
      assertEquals(body.hcursor.downField("error").as[String].toOption, Some("method-not-allowed"))
      assertEquals(body.hcursor.downField("allow").as[String].toOption, Some("POST"), "自解释：迁移目标写在体里")
    }
  }

  test("旧 GET 带 ?scenario=switch 也只得到门内 405（query 面已彻底退场，不再 arm 标记）") {
    run(get("/neblink/auth/end-session?scenario=switch", withToken = true)).map { case (status, body) =>
      assertEquals(status, Status.MethodNotAllowed)
      assertEquals(body.hcursor.downField("allow").as[String].toOption, Some("POST"))
    }
  }

end ApiGuardFollowupRoutesSpec
