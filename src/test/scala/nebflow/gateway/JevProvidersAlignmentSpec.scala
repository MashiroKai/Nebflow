package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, parser}
import munit.FunSuite
import nebflow.llm.decision.{DecisionProvider, JevSettings}
import nebflow.service.ConfigService
import nebflow.shared.{JevConfig, JevProviderConfig, NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.server.websocket.WebSocketBuilder2

import java.nio.file.Files

/**
 * jev-imagegen-align-llm 批（2026-11-02 契约 §B / §C / §I-4）后端四件的结构钉：
 *
 *  1. `JevConfig.providers` 解码 + `JevSettings.resolve` 的**逐字段三级回落**
 *     `providers[选中项] > 顶层 > 缺省`（旧配置零迁移：无 `providers` 时行为逐字不变）；
 *  2. `ConfigService.jevStatus`（`GET /api/config/jev`）投影：与 `imagegenStatus`
 *     同构，`keyConfigured` 只读**在位性**，**永不回传 key 值**；
 *  3. `JEV_WRITABLE_KEYS` 接受 `providers`（整块对象写入）并拒绝越界键 / 值型键；
 *  4. `POST /api/jev/test` 的 **apiKey 覆写腿**：`apiKey 覆写 > 已存 keyRef secret >
 *     无`（`JevProbe.resolveToken` 三腿，与 `ImageGenProbe.resolveToken` 同源；
 *     旧 keyRef 腿逐字保留）。
 *
 * 🔴 凭据纪律：本 spec 的 key 值一律是**假值**（`sk-jev-not-real` /
 * `test-key-not-real`），只用于证明「值可以到位」与「值绝不进投影」。
 */
class JevProvidersAlignmentSpec extends FunSuite:

  private val FakeSecretValue = "sk-jev-not-real"

  private def withDataRoot[A](f: os.Path => A): A =
    val tmp = os.Path(Files.createTempDirectory("jev-providers-spec"))
    val original = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    try f(tmp)
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  // ── 1. JevSettings.resolve：三级回落 ──────────────────────────────────

  test("schema: jev.providers 解码为 JevProviderConfig 映射；缺席 ⇒ None（旧配置零迁移）") {
    val withProviders = parser
      .parse(
        """{"enabled":true,"provider":"laya-local","providers":{"laya-local":{"type":"laya","endpoint":"http://127.0.0.1:8080/predict","model":"laya-m","keyRef":"jev-laya-local-key"}}}"""
      )
      .toOption
      .get
      .as[JevConfig]
    assert(withProviders.isRight, s"jev.providers 必须可解码: $withProviders")
    val cfg = withProviders.toOption.get
    assertEquals(cfg.providers.map(_.keySet), Some(Set("laya-local")))
    val entry = cfg.providers.get("laya-local")
    assertEquals(entry.`type`, DecisionProvider.Laya)
    assertEquals(entry.endpoint, Some("http://127.0.0.1:8080/predict"))
    assertEquals(entry.model, Some("laya-m"))
    assertEquals(entry.keyRef, Some("jev-laya-local-key"))

    // 顶层 5 键形状（旧配置）照旧解码，providers 缺键 ⇒ None。
    val legacy = parser.parse("""{"enabled":true,"provider":"typesafe-jev","keyRef":"typesafe-api-key"}""").toOption.get.as[JevConfig]
    assertEquals(legacy.map(_.providers), Right(None), "providers 缺键 ⇒ None（旧配置零迁移）")
    assertEquals(legacy.map(_.keyRef), Right(Some("typesafe-api-key")), "顶层 keyRef 逐字保留")
  }

  test("resolve: providers[选中项] 逐字段优先（endpoint/model/keyRef 全走第一级）") {
    withDataRoot { root =>
      os.write(root / "secrets" / "jev-laya-local-key", FakeSecretValue, createFolders = true)
      val cfg = JevConfig(
        enabled = Some(true),
        provider = Some("laya-local"),                       // 选中项指针（顶层，语义不变）
        endpoint = Some("http://top-level/should-lose"),
        model = Some("top-model"),
        keyRef = Some("jev-laya-local-key"),
        providers = Some(
          Map(
            "laya-local" -> JevProviderConfig(
              `type` = DecisionProvider.Laya,
              endpoint = Some("http://127.0.0.1:9999/predict"),
              model = Some("laya-m"),
              keyRef = Some("jev-laya-local-key")
            )
          )
        )
      )
      val s = JevSettings.resolve(Some(cfg), None).getOrElse(fail("providers 在位且 secret 在位 ⇒ 腿必须开"))
      assertEquals(s.provider, DecisionProvider.Laya, "第一级：选中项的 type 胜出")
      assertEquals(s.endpoint, "http://127.0.0.1:9999/predict", "第一级：选中项的 endpoint 胜出")
      assertEquals(s.model, "laya-m", "第一级：选中项的 model 胜出")
      assertEquals(s.token, Some(FakeSecretValue), "选中项的 keyRef 现读 secret（值只活在 token 位）")
    }
  }

  test("resolve: 选中项字段缺位 ⇒ 回落顶层（第二级）；providers 缺席 ⇒ 纯顶层（第三级 = 缺省）") {
    withDataRoot { _ =>
      // 第二级：providers 在、选中项只有 type（其余字段缺位）⇒ 顶层三字段接管。
      val partial = JevConfig(
        enabled = Some(true),
        provider = Some("typesafe-jev"),
        endpoint = Some("https://top.example/v1/systemone"),
        model = Some("top-model"),
        providers = Some(Map("typesafe-jev" -> JevProviderConfig(`type` = DecisionProvider.TypesafeJev)))
      )
      val s2 = JevSettings.resolve(Some(partial), None).getOrElse(fail("无 keyRef ⇒ 腿开"))
      assertEquals(s2.endpoint, "https://top.example/v1/systemone", "第二级：顶层 endpoint 接管")
      assertEquals(s2.model, "top-model", "第二级：顶层 model 接管")
      assertEquals(s2.token, None, "顶层 keyRef 缺席 ⇒ 无 token（不期望凭据形态）")

      // 第三级：providers 缺席（旧配置）⇒ 缺省值，行为与本批之前逐字相同。
      val legacy = JevConfig(enabled = Some(true))
      val s3 = JevSettings.resolve(Some(legacy), None).getOrElse(fail("旧配置必须照旧可解析"))
      assertEquals(s3.provider, DecisionProvider.TypesafeJev, "第三级：provider 缺省")
      assertEquals(s3.endpoint, DecisionProvider.DefaultTypesafeEndpoint, "第三级：endpoint 缺省")
      assertEquals(s3.model, DecisionProvider.DefaultModel, "第三级：model 缺省")
      assertEquals(s3.token, None, "第三级：无凭据")

      // 选中项（合法 provider 名）不在 providers 表内 ⇒ 退化为顶层读法（不是
      // 失败、不是空串）；表里只有别的名字，选中项查不到。
      val dangling = JevConfig(
        enabled = Some(true),
        provider = Some(DecisionProvider.Laya),
        model = Some("top-model"),
        providers = Some(Map("typesafe-jev" -> JevProviderConfig(`type` = DecisionProvider.TypesafeJev)))
      )
      val s4 = JevSettings.resolve(Some(dangling), None).getOrElse(fail("表中无选中项 ⇒ 照旧按顶层解析"))
      assertEquals(s4.provider, DecisionProvider.Laya, "选中项指针本身仍是权威的 provider 身份")
      assertEquals(s4.model, "top-model", "悬空指针 ⇒ 第二级（顶层）")
      assertEquals(s4.endpoint, DecisionProvider.DefaultLayaEndpoint, "第二级缺 endpoint ⇒ 第三级（按 provider 的缺省）")

      // 选中项是**未知 provider 名** ⇒ 仍按既有校验拒（本批不放宽任何校验）。
      val unknownProvider = JevConfig(
        enabled = Some(true),
        provider = Some("not-a-provider"),
        providers = Some(Map("not-a-provider" -> JevProviderConfig(`type` = "not-a-provider")))
      )
      assertEquals(JevSettings.resolve(Some(unknownProvider), None), None, "未知 provider 仍拒（校验逐字不变）")
    }
  }

  test("resolve: 选中项的 keyRef 缺席 secret ⇒ 腿自动关（四修③ 语义沿用，不因 providers 而放宽）") {
    withDataRoot { _ =>
      val cfg = JevConfig(
        enabled = Some(true),
        provider = Some("typesafe-jev"),
        providers = Some(
          Map(
            "typesafe-jev" -> JevProviderConfig(
              `type` = DecisionProvider.TypesafeJev,
              keyRef = Some("no-such-provider-secret")
            )
          )
        )
      )
      assertEquals(JevSettings.resolve(Some(cfg), None), None, "选中项 keyRef 无 secret ⇒ 腿关（不是静默掉凭据）")
    }
  }

  // ── 2. jevStatus 投影 ────────────────────────────────────────────────

  private def seedJevConfig(root: os.Path): Unit =
    os.write(
      root / nebflow.shared.Branding.configFileName,
      s"""{"jev":{"enabled":true,"provider":"typesafe-jev",
         |"endpoint":"https://api.typesafe.ai/v1/systemone","model":"jev-latest",
         |"keyRef":"jev-typesafe-jev-key",
         |"providers":{
         |  "typesafe-jev":{"type":"typesafe-jev","endpoint":"https://api.typesafe.ai/v1/systemone","model":"jev-latest","keyRef":"jev-typesafe-jev-key"},
         |  "laya-local":{"type":"laya","endpoint":"http://127.0.0.1:8080/predict","model":"laya-m","keyRef":"jev-laya-local-key"}}}}""".stripMargin,
      createFolders = true
    )

  test("jevStatus: 投影形状与 imagegenStatus 同构，keyConfigured = 纯在位性（值绝不进载荷）") {
    withDataRoot { root =>
      seedJevConfig(root)
      // 只给 typesafe-jev 的 secret 值；laya-local 的 keyRef 保持缺件。
      os.write(root / "secrets" / "jev-typesafe-jev-key", FakeSecretValue, createFolders = true)

      val status = ConfigService.jevStatus.unsafeRunSync()
      assertEquals(status.hcursor.downField("configured").as[Boolean].toOption, Some(true))
      assertEquals(status.hcursor.downField("enabled").as[Boolean].toOption, Some(true))
      assertEquals(status.hcursor.downField("provider").as[String].toOption, Some("typesafe-jev"))
      assertEquals(status.hcursor.downField("endpoint").as[String].toOption, Some("https://api.typesafe.ai/v1/systemone"))
      assertEquals(status.hcursor.downField("model").as[String].toOption, Some("jev-latest"))
      assertEquals(status.hcursor.downField("keyRef").as[String].toOption, Some("jev-typesafe-jev-key"), "keyRef 是指针名，逐字回传（不打码）")
      assertEquals(status.hcursor.downField("keyConfigured").as[Boolean].toOption, Some(true), "在位性：有值 ⇒ true")

      val providers = status.hcursor.downField("providers").as[Map[String, Json]].toOption.get
      assertEquals(providers.keySet, Set("typesafe-jev", "laya-local"))
      assertEquals(providers("typesafe-jev").hcursor.downField("type").as[String].toOption, Some("typesafe-jev"))
      assertEquals(providers("typesafe-jev").hcursor.downField("keyConfigured").as[Boolean].toOption, Some(true))
      assertEquals(providers("laya-local").hcursor.downField("endpoint").as[String].toOption, Some("http://127.0.0.1:8080/predict"))
      assertEquals(providers("laya-local").hcursor.downField("keyConfigured").as[Boolean].toOption, Some(false), "缺件 ⇒ 待录入")

      // 🔴 投影零 key 值：值的唯一出现位置是 secrets 文件本身。
      val wire = status.noSpaces
      assert(!wire.contains(FakeSecretValue), s"key 值绝不进 jevStatus 载荷: $wire")
    }
  }

  test("jevStatus: 未配置态零造假（configured/enabled=false，providers 空对象而非 null）") {
    withDataRoot { _ =>
      val status = ConfigService.jevStatus.unsafeRunSync()
      assertEquals(status.hcursor.downField("configured").as[Boolean].toOption, Some(false))
      assertEquals(status.hcursor.downField("enabled").as[Boolean].toOption, Some(false))
      assertEquals(status.hcursor.downField("keyConfigured").as[Boolean].toOption, Some(false))
      assertEquals(
        status.hcursor.downField("providers").as[Map[String, Json]].toOption.map(_.isEmpty),
        Some(true),
        "未配置态 providers = 空对象（面板据此渲染零卡片）"
      )
    }
  }

  // ── 3. 写面白名单 ────────────────────────────────────────────────────

  test("setJevSection: providers 在册（整块写入 + 读回一致），越界键与值型键仍拒写") {
    withDataRoot { root =>
      val providers = parser
        .parse(
          """{"typesafe-jev":{"type":"typesafe-jev","model":"jev-latest","keyRef":"jev-typesafe-jev-key"}}"""
        )
        .toOption
        .get
      assertEquals(
        ConfigService.setJevSection(Map("provider" -> "typesafe-jev".asJson, "providers" -> providers)).unsafeRunSync(),
        Right(()),
        "providers 必须在白名单里（契约 §B.3）"
      )
      val after = parser.parse(os.read(root / nebflow.shared.Branding.configFileName)).toOption.get
      assertEquals(
        after.hcursor.downField("jev").downField("providers").downField("typesafe-jev").downField("model").as[String],
        Right("jev-latest"),
        "providers 整块落盘，逐键 merge 语义不变"
      )
      assertEquals(after.hcursor.downField("jev").downField("provider").as[String], Right("typesafe-jev"))

      // 越界键：拒写且不落盘。
      val unknown = ConfigService.setJevSection(Map("mystery" -> 1.asJson)).unsafeRunSync()
      assert(unknown.isLeft, s"越界键必须拒写: $unknown")
      // 值型键（即便混进白名单也拦）：本面永不收凭据值（契约 §C.6）。
      val valueBearing = ConfigService.setJevSection(Map("apiKey" -> FakeSecretValue.asJson)).unsafeRunSync()
      assert(valueBearing.isLeft, s"值型凭据键必须拒写: $valueBearing")
      val after2 = parser.parse(os.read(root / nebflow.shared.Branding.configFileName)).toOption.get
      assert(after2.hcursor.downField("jev").downField("apiKey").focus.isEmpty, "被拒的写不得落盘")
    }
  }

  // ── 4. /jev/test 的 apiKey 覆写腿（三腿优先级）──────────────────────

  test("resolveToken: apiKey 覆写 > 已存 keyRef secret > 无（旧 keyRef 腿逐字保留）") {
    withDataRoot { root =>
      os.write(root / "secrets" / "jev-typesafe-jev-key", FakeSecretValue, createFolders = true)
      // ① 覆写腿最优先（即便存储 secret 在位）。
      assertEquals(
        JevProbe.resolveToken(Some("test-key-not-real"), Some("jev-typesafe-jev-key")),
        Right("test-key-not-real"),
        "表单覆写必须赢过已存 secret（未保存也测）"
      )
      // ② 无覆写 ⇒ 已存 keyRef 的 secret 现读。
      assertEquals(JevProbe.resolveToken(None, Some("jev-typesafe-jev-key")), Right(FakeSecretValue))
      // ③ 无 keyRef = 不期望凭据形态 ⇒ 空 token 直发（laya 本地端点同款）。
      assertEquals(JevProbe.resolveToken(None, None), Right(""))
      // ④ keyRef 在但 secret 缺席 ⇒ Left（待录入，只带 keyRef 名，不带值）。
      val missing = JevProbe.resolveToken(Some(""), Some("definitely-absent-keyref-name"))
      assert(missing.isLeft, s"缺席 secret 必须 Left: $missing")
      assert(missing.swap.getOrElse("").contains("definitely-absent-keyref-name"), "文案带 keyRef 名")
      // ⑤ 覆盖腿对缺席 secret 也照样放行（用户填了值就不看旧指针）。
      assertEquals(JevProbe.resolveToken(Some("test-key-not-real"), Some("definitely-absent-keyref-name")), Right("test-key-not-real"))
    }
  }

  test("callWithToken: 覆写值真的上到 Authorization 头（旧 keyRef 腿在无覆写时照旧）") {
    var seen: List[String] = Nil
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      (ex) => {
        seen = seen :+ Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
        val b = """{"model":"jev-1.13.0","answers":{"q1":{"type":"noul","noul":0.9}}}""".getBytes
        ex.sendResponseHeaders(200, b.length)
        ex.getResponseBody.write(b)
        ex.close()
      }
    )
    server.start()
    try
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/v1/systemone"
      withDataRoot { root =>
        os.write(root / "secrets" / "jev-typesafe-jev-key", FakeSecretValue, createFolders = true)
        // 覆写腿：Authorization 带表单值。
        JevProbe.callWithToken(DecisionProvider.TypesafeJev, url, "jev-latest", Some("test-key-not-real"), Some("jev-typesafe-jev-key")) match
          case JevProbe.Verdict.Ok(_, _) => ()
          case other => fail(s"expected Ok, got $other")
        assertEquals(seen.last, "Bearer test-key-not-real", "覆写值覆写存储在位的旧 keyRef")
        // 无覆写 ⇒ 旧 keyRef 腿（存储 secret）。
        JevProbe.callWithToken(DecisionProvider.TypesafeJev, url, "jev-latest", None, Some("jev-typesafe-jev-key")) match
          case JevProbe.Verdict.Ok(_, _) => ()
          case other => fail(s"expected Ok on the legacy keyRef leg, got $other")
        assertEquals(seen.last, s"Bearer $FakeSecretValue", "旧 keyRef 腿逐字保留")
      }
    finally server.stop(0)
  }

  // ── 5. 路由面：GET /api/config/jev 存在 + PUT 接受 providers / 拒越界 ──

  private val TestToken = "jev-providers-spec-token"

  private def mkRoutes(jev: Option[JevConfig] = None): HttpRoutes[IO] =
    val inner = new RestApiRoutes(
      tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty), jev = jev)
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null
    )
    val wsb = null.asInstanceOf[WebSocketBuilder2[IO]]
    inner.routes <+> inner.presenceWsRoutes(wsb)

  private def authed(method: Method, path: String, body: Option[Json] = None): Request[IO] =
    val base = Request[IO](method, Uri.unsafeFromString(path))
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $TestToken"))
    body.fold(base)(b => base.withEntity(b))

  private def bodyJson(r: Response[IO]): Json =
    parser.parse(r.bodyText.compile.string.unsafeRunSync()).getOrElse(Json.Null)

  test("GET /api/config/jev：新投影端点在位（与 imagegen 同 withAuth 门槛），无令牌 403") {
    withDataRoot { root =>
      seedJevConfig(root)
      val routes = mkRoutes()
      // 路径形态 = Router 相对（生产挂载 Router("/api" -> routes)），直调需去前缀。
      val ok = routes(authed(Method.GET, "/config/jev")).value.unsafeRunSync().getOrElse(fail("GET /config/jev fell through"))
      assertEquals(ok.status, Status.Ok, "GET /config/jev 必须存在（契约 §B.4）")
      val json = bodyJson(ok)
      assertEquals(json.hcursor.downField("provider").as[String].toOption, Some("typesafe-jev"))
      assertEquals(json.hcursor.downField("keyConfigured").as[Boolean].toOption, Some(false), "无 secret ⇒ 待录入")
      assert(!json.noSpaces.contains(FakeSecretValue), "投影零 key 值")

      val denied = routes(Request[IO](Method.GET, Uri.unsafeFromString("/config/jev"))).value.unsafeRunSync()
        .getOrElse(fail("GET /config/jev fell through (unauthenticated)"))
      assertEquals(denied.status, Status.Forbidden, "与 GET /config/imagegen 同 withAuth 门槛")
    }
  }

  test("PUT /api/config/jev：接受 providers 并回 updated，越界键 400 且不落盘") {
    withDataRoot { root =>
      val routes = mkRoutes()
      val body = parser
        .parse(
          """{"provider":"typesafe-jev","providers":{"typesafe-jev":{"type":"typesafe-jev","model":"jev-latest","keyRef":"jev-typesafe-jev-key"}}}"""
        )
        .toOption
        .get
      val ok = routes(authed(Method.PUT, "/config/jev", Some(body))).value.unsafeRunSync().getOrElse(fail("PUT /config/jev fell through"))
      assertEquals(ok.status, Status.Ok, s"PUT 必须接受 providers: ${bodyJson(ok).noSpaces}")
      assertEquals(bodyJson(ok).hcursor.downField("updated").as[Boolean].toOption, Some(true))
      val after = parser.parse(os.read(root / nebflow.shared.Branding.configFileName)).toOption.get
      assertEquals(
        after.hcursor.downField("jev").downField("providers").downField("typesafe-jev").downField("keyRef").as[String],
        Right("jev-typesafe-jev-key"),
        "providers 落到 nebflow.json（指针名，非值）"
      )

      val bad = parser.parse("""{"apiKey":"test-key-not-real","mystery":1}""").toOption.get
      val refused = routes(authed(Method.PUT, "/config/jev", Some(bad))).value.unsafeRunSync().getOrElse(fail("PUT fell through"))
      assertEquals(refused.status, Status.BadRequest, "越界/值型键一律 400（契约 §C.6）")
      val after2 = parser.parse(os.read(root / nebflow.shared.Branding.configFileName)).toOption.get
      assert(after2.hcursor.downField("jev").downField("apiKey").focus.isEmpty, "被拒的写不得落盘")
    }
  }
end JevProvidersAlignmentSpec
