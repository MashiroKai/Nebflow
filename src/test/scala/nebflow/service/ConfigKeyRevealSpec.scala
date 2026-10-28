package nebflow.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.gateway.RestApiRoutes
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import scala.compiletime.uninitialized

/**
 * 设置页 apikey 明文回读（作者令 2026-10-27 10:33「设置里配置的 apikey，直接
 * 显示就行了，比如 JeV 的以及我们 llm api 的」）的**契约钉**。
 *
 * 通用读面（GET /api/config / WS getConfig → [[ConfigService.getConfig]]）的
 * keyaudit-quickfix ③ 打码**维持不动**（[[ConfigSecretMaskSpec]] 继续钉它）；
 * 本批加的是一条 owner 鉴权的**定向回读面**：`GET /api/config/provider-keys`
 * → [[ConfigService.revealProviderApiKeys]]，把 `llm.providers.<id>.apiKey`
 * 真值投给设置页，编辑弹窗才能「配过什么就直接看到什么」。
 *
 * 钉四件事：
 *   1. **投影形态**：`{"providers":{"<id>":"<apiKey>"}}` 只含 provider apiKey
 *      真值；无 apiKey 的 provider 缺席（前端空态，不是掩码）；`jev.keyRef` /
 *      `embedding.keyRef` 等其余节一律不出现在载荷里（指针名不走本面）；
 *   2. **盘上真相不变**：回读是纯读投影，读后盘上文件逐字节不动；
 *   3. **权限红线**：无令牌 ⇒ 403（门在业务之前，且带 X-Nf-Reason:
 *      credential-required——与设置写 PUT /api/config/jev 同一条 withAuth 面）；
 *   4. **闭环**：带令牌读回的明文 = 盘上所存值（保存 → 重载设置页 → 字段
 *      明文等于所存值 的服务端半边）。
 */
class ConfigKeyRevealSpec extends CatsEffectSuite:

  private var originalRoot: os.Path = uninitialized
  private var tmpRoot: os.Path = uninitialized

  override def beforeAll(): Unit =
    // Redirect PathUtil.dataRoot BEFORE any ConfigService access
    // (ConfigSecretMaskSpec 同款 fixture；Test / parallelExecution := false 串行)
    originalRoot = PathUtil.dataRoot
    tmpRoot = os.pwd / "target" / "config-key-reveal-test" / s"data-${System.nanoTime()}"
    os.makeDir.all(tmpRoot)
    PathUtil.setDataRoot(tmpRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(tmpRoot)
    catch case _: Exception => ()

  /** 无 apiKey 的 provider 底稿（apiKey 形态字段缺席 = 揭影里也必须缺席）。 */
  private val ProviderJson: Json = Json.obj(
    "baseUrl" -> Json.fromString("https://prov.example.com/v1"),
    "protocol" -> Json.fromString("openai"),
    "models" -> Json.arr(Json.obj("id" -> Json.fromString("m1"), "contextWindow" -> Json.fromInt(8192)))
  )

  private def providerJson(apiKey: Option[String]): Json =
    apiKey.fold(ProviderJson)(k => ProviderJson.deepMerge(Json.obj("apiKey" -> Json.fromString(k))))

  /** 盘上样本：两个带 key 的 provider + 一个不带 key 的 + keyRef 指针 + search 节。 */
  private def seedConfig(): String =
    Json
      .obj(
        "llm" -> Json.obj(
          "providers" -> Json.obj(
            "kimi" -> providerJson(Some("sk-real-kimi-1")),
            "deepseek" -> providerJson(Some("sk-real-ds-2")),
            "anon" -> providerJson(None)
          )
        ),
        "search" -> Json.obj("provider" -> Json.fromString("zhipu"), "apiKey" -> Json.fromString("zhipu-real-3")),
        "jev" -> Json.obj("enabled" -> Json.fromBoolean(true), "keyRef" -> Json.fromString("jev-key")),
        "embedding" -> Json.obj("keyRef" -> Json.fromString("embed-key"), "model" -> Json.fromString("emb-1"))
      )
      .spaces2

  private def writeSeed(): Unit = os.write.over(PathUtil.dataRoot / "nebflow.json", seedConfig())

  // ── 1. 投影形态（service 层）────────────────────────────────────────────

  test("revealProviderApiKeys projects ONLY real provider apiKeys; keyless providers and keyRef pointers stay out") {
    writeSeed()
    val revealed = ConfigService.revealProviderApiKeys.unsafeRunSync()
    val hc = revealed.hcursor
    assertEquals(
      hc.downField("providers").downField("kimi").as[String],
      Right("sk-real-kimi-1"),
      "真值明文出投影——设置页「配过什么就直接看到什么」"
    )
    assertEquals(hc.downField("providers").downField("deepseek").as[String], Right("sk-real-ds-2"))
    assertEquals(
      hc.downField("providers").downField("anon").as[Json].toOption,
      None,
      "无 apiKey 的 provider 缺席 = 前端空态（不是掩码）"
    )
    assertEquals(
      hc.downField("providers").as[Map[String, Json]].toOption.map(_.keySet),
      Some(Set("kimi", "deepseek")),
      "载荷只有 providers 一节，其余配置节（jev/embedding/search 的 keyRef/apiKey）一律不外带"
    )
  }

  test("reveal is a pure read: the on-disk file is byte-identical after the projection") {
    writeSeed()
    val before = os.read(PathUtil.dataRoot / "nebflow.json")
    ConfigService.revealProviderApiKeys.unsafeRunSync()
    val after = os.read(PathUtil.dataRoot / "nebflow.json")
    assertEquals(after, before, "回读不落盘——盘上真相不变")
  }

  test("reveal on a missing config lands an empty providers map (fresh-install safe)") {
    os.remove.all(PathUtil.dataRoot / "nebflow.json")
    val revealed = ConfigService.revealProviderApiKeys.unsafeRunSync()
    assertEquals(revealed.hcursor.downField("providers").as[Map[String, Json]].toOption, Some(Map.empty))
  }

  // ── 2. 通用读面打码不动（交叉核对：掩码与回读并存，不改写快译 ③ 的语义）──

  test("cross-check: getConfig stays masked while reveal serves the real value (two faces, one disk truth)") {
    writeSeed()
    val served = parse(ConfigService.getConfig.unsafeRunSync()).toOption.get
    assertEquals(
      served.hcursor.downField("llm").downField("providers").downField("kimi").downField("apiKey").as[String],
      Right("***"),
      "keyaudit-quickfix ③ 通用读面打码维持（ConfigSecretMaskSpec 的地盘未被本批侵入）"
    )
    assertEquals(
      ConfigService.revealProviderApiKeys.unsafeRunSync().hcursor.downField("providers").downField("kimi").as[String],
      Right("sk-real-kimi-1")
    )
  }

  // ── 3. 权限红线 + 闭环（gateway 路由层，ApiGuardFollowupRoutesSpec 同款
  //      廉价结构钉：零端口、零 HOME 写；本臂不触 sharedResources ⇒ null 可）──

  private val TestToken = "test-token-config-key-reveal"

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

  private def get(path: String, withToken: Boolean): Request[IO] =
    val base = Request[IO](Method.GET, Uri.unsafeFromString(path))
    if withToken then base.withHeaders(Headers("Authorization" -> s"Bearer $TestToken")) else base

  test("GET /config/provider-keys 无令牌 ⇒ 403（owner 鉴权与设置写同门槛；门在业务之前）") {
    mkRoutes()
      .routes(get("/config/provider-keys", withToken = false))
      .value
      .map(_.getOrElse(fail("route fell through")))
      .flatMap { resp =>
        val status = resp.status
        val reason = resp.headers.get(org.typelevel.ci.CIString("X-Nf-Reason")).map(_.head.value)
        resp.bodyText.compile.string.map { body =>
          assertEquals(status, Status.Forbidden)
          assertEquals(reason, Some("credential-required"))
          assertEquals(
            parse(body).toOption.flatMap(_.hcursor.downField("error").as[String].toOption),
            Some("Unauthorized")
          )
        }
      }
  }

  test("GET /config/provider-keys 带令牌 ⇒ 200 + 盘上真值（保存 → 重载设置页 → 字段明文等于所存值 的服务端半边）") {
    writeSeed()
    mkRoutes()
      .routes(get("/config/provider-keys", withToken = true))
      .value
      .map(_.getOrElse(fail("route fell through")))
      .flatMap(resp => resp.as[Json].map(json => (resp.status, json)))
      .map { case (status, body) =>
        assertEquals(status, Status.Ok)
        assertEquals(body.hcursor.downField("providers").downField("kimi").as[String], Right("sk-real-kimi-1"))
        assertEquals(body.hcursor.downField("providers").downField("deepseek").as[String], Right("sk-real-ds-2"))
      }
  }

end ConfigKeyRevealSpec
