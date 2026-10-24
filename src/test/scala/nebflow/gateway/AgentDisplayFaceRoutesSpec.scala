package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString

/**
 * Agent display face（2026-10-21 诊断批症状⑧「头像/名称跨设备不同步」）的网关腿：
 *
 *  - `PUT /agents/:name/display-name` —— profile 面板「名称回车即存」的落盘腿：
 *    AtomicJson 合并写 `agents/<name>/agent.json` 的 displayName（root 带 name 键
 *    补底，非 root 不补）；空值/超长 400； agentsData 行与 rootName 探针同源此文件。
 *  - `PUT/GET/DELETE /agents/:name/avatar` —— 头像同步腿：dataURL 字节闸落
 *    `agents/<name>/avatar`；GET 显式字节流实体（fs2.Stream——裸 Ok(Array[Byte])
 *    命中 circe byte 数组编码器即图片损坏，/neblink/avatar 同款教训）+ 内容 sha
 *    强 ETag + If-None-Match 304；DELETE 幂等。
 *
 * 定位纪律：**display 面**——2026-09-06 skills/flows 退役（PUT /agents/:name ⇒ 410）
 * 射程是「面板写能力配置」，本组只写展示面；退役臂在本 spec 里保持钉住（sibling
 * 不被本批复活）。
 */
class AgentDisplayFaceRoutesSpec extends FunSuite:

  private val TestToken = "test-token-agentdisplay"
  private val tempRoot: os.Path = os.pwd / "target" / "test-agent-display-face"
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  private val NullBackend =
    null.asInstanceOf[sttp.client4.StreamBackend[IO, sttp.capabilities.fs2.Fs2Streams[IO]]]

  private def mkResources: SharedResources =
    val registryConfigRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
      NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    )
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "agent-display-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = new nebflow.llm.ProviderRegistry(registryConfigRef, NullBackend),
      healthMonitor = nebflow.llm.ProviderHealthMonitor(null),
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  end mkResources

  private val routes = new RestApiRoutes(
    tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
    configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
      NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    ),
    sharedResources = mkResources,
    sessionStore = null,
    wsRoutes = null
  )

  private val wsb: WebSocketBuilder2[IO] = null.asInstanceOf[WebSocketBuilder2[IO]]
  // 生产挂载形状（DeviceFaceHardeningRoutesSpec 同款）：/agents/* display 面与退役臂
  // 同住 presenceWsRoutes 表（紧随 presence 臂之后），单探 routes.routes 会全量
  // 穿透成 None——spec 头注「all-None 表象」正是它存在的理由。wsb 占位安全性同前证：
  // presence 臂在 neblinkService=None 时短路，本组臂从不解引用 builder。
  private val mounted: HttpRoutes[IO] = routes.routes <+> routes.presenceWsRoutes(wsb)

  private def authed(req: Request[IO]): Request[IO] =
    req.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $TestToken"))

  private def run(req: Request[IO]): Response[IO] =
    mounted(authed(req)).value.unsafeRunSync().getOrElse(fail("route fell through"))

  private def runNoAuth(req: Request[IO]): Response[IO] =
    mounted(req).value.unsafeRunSync().getOrElse(fail("route fell through"))

  private def putJson(path: String, json: io.circe.Json): Request[IO] =
    Request[IO](method = Method.PUT, uri = Uri.unsafeFromString(path)).withEntity(json)

  // 1×1 px PNG dataURL（最小合法图，非真实头像语义——字节闸/回显/304 的载体）。
  private val TinyPng =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

  private def avatarPath(name: String): os.Path = tempRoot / "agents" / name / "avatar"

  // ── display-name ────────────────────────────────────────────

  test("PUT display-name writes agent.json displayName (root backfills the name key)"):
    val resp =
      run(putJson("/agents/Nebula/display-name", io.circe.Json.obj("displayName" -> io.circe.Json.fromString("小星"))))
    assertEquals(resp.status, Status.Ok)
    val json = io.circe.parser.parse(os.read(tempRoot / "agents" / "Nebula" / "agent.json")).toOption.get
    assertEquals(json.hcursor.get[String]("displayName").toOption.get, "小星")
    assertEquals(json.hcursor.get[String]("name").toOption.get, "Nebula", "root 名键补底（OnboardingArtifacts 同款纪律）")

  test("PUT display-name for a non-root agent merges without forcing a name key"):
    val dir = tempRoot / "agents" / "Coder"
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json", """{"name":"Coder","model":"gpt-x"}""")
    val resp =
      run(putJson("/agents/Coder/display-name", io.circe.Json.obj("displayName" -> io.circe.Json.fromString("码手"))))
    assertEquals(resp.status, Status.Ok)
    val json = io.circe.parser.parse(os.read(dir / "agent.json")).toOption.get
    assertEquals(json.hcursor.get[String]("displayName").toOption.get, "码手")
    assertEquals(json.hcursor.get[String]("model").toOption.get, "gpt-x", "既有字段不被合并写抹掉")

  test("PUT display-name rejects blank, over-long and invalid agent names"):
    assertEquals(
      run(
        putJson("/agents/Nebula/display-name", io.circe.Json.obj("displayName" -> io.circe.Json.fromString("  ")))
      ).status,
      Status.BadRequest
    )
    assertEquals(
      run(
        putJson("/agents/Nebula/display-name", io.circe.Json.obj("displayName" -> io.circe.Json.fromString("x" * 65)))
      ).status,
      Status.BadRequest
    )
    // 路径穿越形态（".." 判据）：用 a..b（过正则、撞显式禁令；"/agents/../x" 会被
    // Uri 归一化吃掉，测不到网关臂）。
    assertEquals(
      run(
        putJson("/agents/a..b/display-name", io.circe.Json.obj("displayName" -> io.circe.Json.fromString("x")))
      ).status,
      Status.BadRequest
    )

  // ── avatar ──────────────────────────────────────────────────

  test("PUT avatar stores bytes behind the size gate; malformed payloads 400"):
    assertEquals(
      run(putJson("/agents/Nebula/avatar", io.circe.Json.obj("dataUrl" -> io.circe.Json.fromString(TinyPng)))).status,
      Status.Ok
    )
    assert(os.exists(avatarPath("Nebula")), "字节落 agents/<name>/avatar")
    assertEquals(
      run(
        putJson(
          "/agents/Nebula/avatar",
          io.circe.Json.obj("dataUrl" -> io.circe.Json.fromString("data:text/html;base64,PGI+"))
        )
      ).status,
      Status.BadRequest
    )
    assertEquals(
      run(
        putJson(
          "/agents/Nebula/avatar",
          io.circe.Json.obj("dataUrl" -> io.circe.Json.fromString("data:image/png;base64,not!base64"))
        )
      ).status,
      Status.BadRequest
    )
    val huge = "data:image/png;base64," + ("A" * 200_001)
    assertEquals(
      run(putJson("/agents/Nebula/avatar", io.circe.Json.obj("dataUrl" -> io.circe.Json.fromString(huge)))).status,
      Status.BadRequest
    )

  test("GET avatar serves explicit byte-stream entity with content-sha ETag; If-None-Match hits 304; absent 404"):
    val resp = run(Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/agents/Nebula/avatar")))
    assertEquals(resp.status, Status.Ok)
    // 显式字节流比对：经 CirceEntityCodec 作用域 resp.as[Array[Byte]] 会撞 circe 的
    // JSON 数字数组解码器（正是引擎侧用 fs2.Stream 显式实体躲开的坑），走 raw body。
    val bytes = scodec.bits.ByteVector(resp.body.compile.toVector.unsafeRunSync().toArray)
    assertEquals(bytes, scodec.bits.ByteVector(os.read.bytes(avatarPath("Nebula"))), "字节逐等（非 JSON 数字数组）")
    val etag = resp.headers.get(CIString("ETag")).map(_.head.value).getOrElse(fail("ETag header missing"))
    assert(etag.startsWith("\"") && etag.length > 20, s"强 ETag=内容 sha: $etag")
    val notMod = run(
      Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/agents/Nebula/avatar"))
        .putHeaders(Header.Raw(CIString("If-None-Match"), etag))
    )
    assertEquals(notMod.status, Status.NotModified)
    assertEquals(
      run(Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/agents/Coder/avatar"))).status,
      Status.NotFound
    )

  test("DELETE avatar clears and is idempotent"):
    assert(os.exists(avatarPath("Nebula")), "前置：头像在位")
    assertEquals(
      run(Request[IO](method = Method.DELETE, uri = Uri.unsafeFromString("/agents/Nebula/avatar"))).status,
      Status.Ok
    )
    assert(!os.exists(avatarPath("Nebula")))
    assertEquals(
      run(Request[IO](method = Method.DELETE, uri = Uri.unsafeFromString("/agents/Nebula/avatar"))).status,
      Status.Ok,
      "幂等"
    )

  // ── rail 与退役 sibling ─────────────────────────────────────

  test("all display faces sit behind withAuth (bare request ⇒ 403) and the retired PUT sibling stays 410"):
    assertEquals(
      runNoAuth(Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/agents/Nebula/avatar"))).status,
      Status.Forbidden
    )
    assertEquals(
      runNoAuth(
        putJson("/agents/Nebula/display-name", io.circe.Json.obj("displayName" -> io.circe.Json.fromString("x")))
      ).status,
      Status.Forbidden
    )
    assertEquals(
      runNoAuth(
        putJson("/agents/Nebula/avatar", io.circe.Json.obj("dataUrl" -> io.circe.Json.fromString(TinyPng)))
      ).status,
      Status.Forbidden
    )
    val retired = run(putJson("/agents/Nebula", io.circe.Json.obj("skills" -> io.circe.Json.arr())))
    assertEquals(retired.status, Status.Gone, "能力配置退役面不被本批复活")
end AgentDisplayFaceRoutesSpec
