package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig}
import nebflow.shared.PathUtil
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * REST 契约测试：全局权限模式的**唯一 REST 观测面 + 写入口**（设计 §5.1 / §13 #17）。
 *
 * 钉死（对应设计 §8 的 A-1 / A-2 / A-3）：
 *  1. `PUT /api/safety/mode` 合法三档 ⇒ 200 且 `nebflow.json` 落盘为该值（A-1）
 *  2. 非法值 ⇒ **400** 且文案含合法值列表、配置文件**字节不变**（A-2）
 *  3. `GET /api/safety` 五例表驱动（缺文件 / 无 safety 键 / 不可识别值 / 三档显式值 /
 *     文件截断）——与设计 §2.3 的三分支表一一对应（A-3）
 *  4. auth 门禁（无 token ⇒ 403），两条路由都在 `withAuth` 之后
 *
 * 隔离：`PathUtil.setDataRoot` 指向临时目录（配置文件读写都落在那里）。
 */
class SafetyModeRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-123"

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-safety-routes"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  // def（非 val）：`tmp` 由 beforeEach 赋值，val 会在类初始化时先于 beforeEach 求值
  private def configPath: os.Path = tmp / "nebflow.json"

  private def writeConfig(content: String): Unit = os.write.over(configPath, content, createFolders = true)

  /**
   * 最小装配：这两条路由只用到 GlobalSafety（配置文件）/ ConfigService（定向写）/
   * wsHub（广播），其余槽位为 null（`ProjectAgentFileRoutesSpec` 轻量装配先例）。
   */
  private val resources = SharedResources(
    llm = null,
    dispatcher = null,
    sessionStore = null,
    projectRoot = os.pwd,
    thinkingConfigRef = null,
    rateLimiter = null,
    fileChangeTracker = null,
    contextWindow = 100_000,
    agentLibrary = null,
    taskStore = null,
    historyArchiver = null,
    fileLockManager = null,
    sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
    providerRegistry = null,
    healthMonitor = null,
    actorSystem = null,
    voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false)
  )

  private val routes = new RestApiRoutes(
    token = TestToken,
    configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
      NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    ),
    sharedResources = resources,
    sessionStore = null,
    wsRoutes = null
  )

  private def run(req: Request[IO]): IO[Response[IO]] =
    routes.routes(req).value.map(_.getOrElse(fail("route fell through")))

  private def authed(req: Request[IO]): Request[IO] =
    req.withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  private def putMode(mode: String): IO[Response[IO]] =
    run(
      authed(
        Request[IO](Method.PUT, Uri.unsafeFromString("/safety/mode"))
          .withEntity(Json.obj("mode" -> Json.fromString(mode)))
      )
    )

  private def getSafety: IO[Response[IO]] =
    run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/safety"))))

  private def bodyJson(resp: Response[IO]): IO[Json] = resp.as[Json]

  // ── A-1：合法值落盘 ────────────────────────────────────────────────────────

  test("A-1: PUT /safety/mode confirm-edits ⇒ 200 and the value lands in nebflow.json"):
    writeConfig("""{"llm": {"providers": {}}}""")
    for
      resp <- putMode("confirm-edits")
      json <- bodyJson(resp)
      onDisk = io.circe.parser.parse(os.read(configPath)).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.hcursor.downField("updated").as[Boolean].toOption, Some(true))
      assertEquals(json.hcursor.downField("defaultMode").as[String].toOption, Some("confirm-edits"))
      assertEquals(
        onDisk.hcursor.downField("safety").downField("defaultMode").as[String].toOption,
        Some("confirm-edits")
      )
      // 定向写只碰 safety 子树：既有键保留
      assert(onDisk.hcursor.downField("llm").succeeded, "pre-existing config keys must be preserved")

  test("A-1: the other two wire values round-trip too (auto-edits / auto-all)"):
    for
      _ <- IO(writeConfig("{}"))
      _ <- putMode("auto-edits")
      afterEdits = io.circe.parser.parse(os.read(configPath)).toOption.get
      _ <- putMode("auto-all")
      afterAll = io.circe.parser.parse(os.read(configPath)).toOption.get
    yield
      assertEquals(
        afterEdits.hcursor.downField("safety").downField("defaultMode").as[String].toOption,
        Some("auto-edits")
      )
      assertEquals(afterAll.hcursor.downField("safety").downField("defaultMode").as[String].toOption, Some("auto-all"))

  // ── A-2：非法值 400 且不落盘 ────────────────────────────────────────────────

  test("A-2: PUT /safety/mode yolo ⇒ 400 with the valid list, and the file is byte-identical"):
    writeConfig("""{"safety": {"defaultMode": "auto-all"}, "llm": {"providers": {}}}""")
    val before = os.read(configPath)
    for
      resp <- putMode("yolo")
      json <- bodyJson(resp)
    yield
      assertEquals(resp.status, Status.BadRequest)
      val err = json.hcursor.downField("error").as[String].toOption.getOrElse("")
      assert(err.contains("unknown mode 'yolo'"), s"error must name the rejected value, got: $err")
      assert(
        err.contains("confirm-edits") && err.contains("auto-edits") && err.contains("auto-all"),
        s"error must list the valid values, got: $err"
      )
      assertEquals(os.read(configPath), before, "an invalid mode must not touch the config file")

  test("A-2: a missing mode field is a 400 too (no silent default)"):
    writeConfig("""{"safety": {"defaultMode": "auto-all"}}""")
    val before = os.read(configPath)
    for
      resp <- run(authed(Request[IO](Method.PUT, Uri.unsafeFromString("/safety/mode")).withEntity(Json.obj())))
      json <- bodyJson(resp)
    yield
      assertEquals(resp.status, Status.BadRequest)
      assert(
        json.hcursor.downField("error").as[String].toOption.exists(_.startsWith("unknown mode ''")),
        s"got: ${json.noSpaces}"
      )
      assertEquals(os.read(configPath), before)

  // ── A-3：GET 五例表驱动（与 §2.3 三分支表一一对应）────────────────────────

  test("A-3: GET /safety — missing file ⇒ auto-all, configured=false"):
    for
      resp <- getSafety
      json <- bodyJson(resp)
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.hcursor.downField("defaultMode").as[String].toOption, Some("auto-all"))
      assertEquals(json.hcursor.downField("configured").as[Boolean].toOption, Some(false))
      assertEquals(json.hcursor.downField("source").as[String].toOption, Some(configPath.toString))

  test("A-3: GET /safety — file without a safety key ⇒ auto-all, configured=false"):
    writeConfig("""{"llm": {"providers": {}}}""")
    for json <- getSafety.flatMap(bodyJson)
    yield
      assertEquals(json.hcursor.downField("defaultMode").as[String].toOption, Some("auto-all"))
      assertEquals(json.hcursor.downField("configured").as[Boolean].toOption, Some(false))

  test("A-3: GET /safety — an unrecognised value ⇒ confirm-edits, configured=false"):
    writeConfig("""{"safety": {"defaultMode": "yolo"}}""")
    for json <- getSafety.flatMap(bodyJson)
    yield
      assertEquals(json.hcursor.downField("defaultMode").as[String].toOption, Some("confirm-edits"))
      assertEquals(json.hcursor.downField("configured").as[Boolean].toOption, Some(false))

  test("A-3: GET /safety — each of the three explicit values ⇒ its own value, configured=true"):
    val cases = List("confirm-edits", "auto-edits", "auto-all")
    cases.traverse_ { mode =>
      // 写配置必须是 IO（延迟到运行时）—— 否则 traverse_ 构造期就把三次写全做了，
      // 三次读都读到最后一个值。
      IO(writeConfig(s"""{"safety": {"defaultMode": "$mode"}}""")) *>
        getSafety.flatMap(bodyJson).map { json =>
          assertEquals(json.hcursor.downField("defaultMode").as[String].toOption, Some(mode))
          assertEquals(json.hcursor.downField("configured").as[Boolean].toOption, Some(true))
        }
    }

  test("A-3: GET /safety — a truncated file ⇒ auto-all (fail-open per author ruling R-b)"):
    // `permissions.scala` 的自陈：读不到有效值（缺文件/缺键/**文件不可解析**/读盘失败）⇒ AutoAll
    writeConfig("""{"safety": """)
    for json <- getSafety.flatMap(bodyJson)
    yield
      assertEquals(json.hcursor.downField("defaultMode").as[String].toOption, Some("auto-all"))
      assertEquals(json.hcursor.downField("configured").as[Boolean].toOption, Some(false))

  test("A-3: GET /safety — a null value ⇒ auto-all, configured=false (null == missing)"):
    writeConfig("""{"safety": {"defaultMode": null}}""")
    for json <- getSafety.flatMap(bodyJson)
    yield
      assertEquals(json.hcursor.downField("defaultMode").as[String].toOption, Some("auto-all"))
      assertEquals(json.hcursor.downField("configured").as[Boolean].toOption, Some(false))

  // ── auth 门禁 + 路由归属 ────────────────────────────────────────────────────

  test("both safety routes are behind auth (no token ⇒ 403, config untouched)"):
    writeConfig("""{"safety": {"defaultMode": "auto-all"}}""")
    val before = os.read(configPath)
    for
      getResp <- run(Request[IO](Method.GET, Uri.unsafeFromString("/safety")))
      putResp <- run(
        Request[IO](Method.PUT, Uri.unsafeFromString("/safety/mode"))
          .withEntity(Json.obj("mode" -> Json.fromString("confirm-edits")))
      )
    yield
      assertEquals(getResp.status, Status.Forbidden)
      assertEquals(putResp.status, Status.Forbidden)
      assertEquals(os.read(configPath), before)

  test("unrelated methods/paths do not match the safety routes (keep them narrow)"):
    // /safety/mode 只认 PUT；/safety 只认 GET。其余（本路由表未声明）应**落空**
    // （HttpRoutes fallthrough）——不落空就意味着有别的 handler 认领了它。
    val probe: Request[IO] => IO[Boolean] = req => routes.routes(req).value.map(_.isEmpty)
    for
      postFallsThrough <- probe(
        authed(Request[IO](Method.POST, Uri.unsafeFromString("/safety/mode")).withEntity(Json.obj()))
      )
      getModeFallsThrough <- probe(authed(Request[IO](Method.GET, Uri.unsafeFromString("/safety/mode"))))
      putSafetyFallsThrough <- probe(
        authed(Request[IO](Method.PUT, Uri.unsafeFromString("/safety")).withEntity(Json.obj()))
      )
    yield
      assert(postFallsThrough, "POST /safety/mode must not be handled by the safety routes")
      assert(getModeFallsThrough, "GET /safety/mode must not be handled by the safety routes")
      assert(putSafetyFallsThrough, "PUT /safety must not be handled by the safety routes")

end SafetyModeRoutesSpec
