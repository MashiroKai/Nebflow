package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.project.{ProjectActor, ProjectDef, ProjectRuntimeRegistry, ProjectStore}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ProjectTool, ToolContext}
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig, ThinkingConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import scala.concurrent.duration.*

/**
 * 项目级 WS 事件面契约（tabrealtime 批 2026-09-17 · 作者裁定 (b) 方案 B / (e) 两身份事件）。
 *
 * 缺陷（取证报告 §C-2/§C-3 第 7 跳）：后端 205 类 WS 事件中 0 个 project 前缀 ⇒
 * 「无推送面」，创建项目后前端列表不更新。本批补两个身份事件，载荷逐字对齐报告 §D-2：
 *
 *   projectCreated  → { type, project, row{name,workspace,agentFile,description,createdAt,origin}, mounted }
 *   projectArchived → { type, project, archivedAt }
 *
 * 本 spec 钉死四条（报告 §G-1 交付要求）：
 *  1. 创建成功 → **emit 1 帧**，载荷六字段（含 additive `origin`）+ mounted=true 逐字齐备，经既有 wsHub 广播面到达连接；
 *  2. 幂等重挂（already exists）→ **emit 0 帧**（无视觉变化）；
 *  3. 无会话上下文（定义就绪、未挂载）→ 1 帧且 mounted=false；
 *  4. 归档成功 → **emit 1 帧** projectArchived（archivedAt = 响应体同值），走真实路由
 *     （POST /projects/<name>/archive）而非只测构造函数；
 *  5. 负面核：两腿之外**零 project 事件帧**（一次调用一帧，无第三处误发）。
 */
class ProjectEventSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val TestToken = "test-token-123"
  private val tempRoot: os.Path = os.pwd / "target" / "test-project-events"

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  private def mkResources(projectRoot: os.Path): SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = projectRoot,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "project-event-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private def mkRoutes(hub: WsHub, projectRoot: os.Path): RestApiRoutes =
    new RestApiRoutes(
      tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
      configRef = Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = mkResources(projectRoot),
      sessionStore = null,
      wsRoutes = null,
      wsHub = hub
    )

  /** 真实广播面 + 帧收集器（与生产构造同形：wsSend = 记录包装的 wsHub.broadcast）。 */
  private def hubWithFrames: IO[(WsHub, Ref[IO, Vector[Json]])] =
    for
      hub <- IO.pure(new WsHub)
      frames <- Ref.of[IO, Vector[Json]](Vector.empty)
      _ <- hub.registerListener(json => frames.update(_ :+ json))
    yield (hub, frames)

  private def projectFrames(frames: Vector[Json]): Vector[Json] =
    frames.filter(f => f.hcursor.downField("type").as[String].toOption.exists(_.startsWith("project")))

  private def field(json: Json, name: String): Option[String] =
    json.hcursor.downField(name).as[String].toOption

  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  test("create leg: successful ProjectCreate emits exactly ONE projectCreated frame with the §D-2 payload") {
    val ws = tempRoot / "ws-evt-create"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pe-create-${scala.util.Random.nextInt(100000)}")
    val input = Json
      .obj(
        "name" -> Json.fromString("evt-create"),
        "workspace" -> Json.fromString(ws.toString),
        "description" -> Json.fromString("realtime event contract")
      )
      .asObject
      .get
    for
      pair <- hubWithFrames
      (hub, frames) = pair
      ctx = ToolContext(
        projectRoot = ws.toString,
        sessionId = Some("nebula-sid"),
        rootSessionId = Some("nebula-root"),
        sharedResources = Some(mkResources(ws)),
        actorSystem = Some(system),
        wsSend = Some((json: Json) => hub.broadcast(json))
      )
      result <- ProjectTool.call(input, ctx)
      collected <- frames.get
      _ <- ProjectRuntimeRegistry.unregister("evt-create")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isRight, result.toString)
      assertEquals(projectFrames(collected).size, 1, s"exactly one project frame, got: $collected")
      val frame = projectFrames(collected).head
      assertEquals(field(frame, "type"), Some("projectCreated"))
      assertEquals(field(frame, "project"), Some("evt-create"))
      assertEquals(field(frame, "mounted"), None) // mounted is a boolean, not a string
      assertEquals(frame.hcursor.downField("mounted").as[Boolean].toOption, Some(true))
      val row = frame.hcursor.downField("row")
      assertEquals(row.downField("name").as[String].toOption, Some("evt-create"))
      assertEquals(row.downField("workspace").as[String].toOption, Some(ws.toString))
      assertEquals(row.downField("agentFile").as[String].toOption, Some((ws / "AGENTS.md").toString))
      assertEquals(row.downField("description").as[String].toOption, Some("realtime event contract"))
      assert(row.downField("createdAt").as[Long].toOption.exists(_ > 0L), "row.createdAt must be a positive epoch ms")
      // Row field set stays isomorphic with the GET /api/projects row — seven
      // fields, no more, no less. `origin` is the additive ui-device-faces
      // field (ProjectActor.projectCreatedFrame), null for a local project;
      // `device` is the additive #920 field (effectiveDevice storage value,
      // "self" for local), closing the row isomorphism with GET /api/projects.
      assertEquals(
        row.keys.map(_.toSet).getOrElse(Set.empty),
        Set("name", "workspace", "agentFile", "description", "createdAt", "origin", "device")
      )
      // #920：device 存储值与 origin 同源（origin 非本机字面量 ⇒ = origin.device）。
      assertEquals(
        row.downField("device").as[String].toOption,
        Some("self"),
        "local project device storage value = self (effectiveDevice single point)"
      )
      assertEquals(frame.asObject.map(_.keys.toSet).getOrElse(Set.empty), Set("type", "project", "row", "mounted"))
    end for
  }

  test("create leg idempotency: re-creating an existing project emits ZERO projectCreated frames") {
    val ws = tempRoot / "ws-evt-dup"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pe-dup-${scala.util.Random.nextInt(100000)}")
    val input = Json
      .obj(
        "name" -> Json.fromString("evt-dup"),
        "workspace" -> Json.fromString(ws.toString)
      )
      .asObject
      .get
    for
      pair <- hubWithFrames
      (hub, frames) = pair
      ctx = ToolContext(
        projectRoot = ws.toString,
        sessionId = Some("nebula-sid"),
        rootSessionId = Some("nebula-root"),
        sharedResources = Some(mkResources(ws)),
        actorSystem = Some(system),
        wsSend = Some((json: Json) => hub.broadcast(json))
      )
      first <- ProjectTool.call(input, ctx)
      afterFirst <- frames.get
      _ <- frames.set(Vector.empty)
      second <- ProjectTool.call(input, ctx) // 定义已存在 → 幂等重挂（created=false）
      afterSecond <- frames.get
      _ <- ProjectRuntimeRegistry.unregister("evt-dup")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(first.isRight, first.toString)
      assert(second.isRight, second.toString)
      assert(second.toOption.get.contains("already exists"), "second call must report already-exists")
      assertEquals(projectFrames(afterFirst).size, 1, "new create emits 1 frame")
      assertEquals(projectFrames(afterSecond).size, 0, s"idempotent re-mount must not emit, got: $afterSecond")
    end for
  }

  test("create leg without session context: definition-ready branch emits ONE frame with mounted=false") {
    val ws = tempRoot / "ws-evt-nosession"
    os.makeDir.all(ws)
    val input = Json
      .obj(
        "name" -> Json.fromString("evt-nosession"),
        "workspace" -> Json.fromString(ws.toString)
      )
      .asObject
      .get
    for
      pair <- hubWithFrames
      (hub, frames) = pair
      // 无 actorSystem/sharedResources ⇒ 走到「definition ready. Mount requires an agent session.」
      ctx = ToolContext(
        projectRoot = ws.toString,
        sessionId = Some("nebula-sid"),
        rootSessionId = Some("nebula-root"),
        wsSend = Some((json: Json) => hub.broadcast(json))
      )
      result <- ProjectTool.call(input, ctx)
      collected <- frames.get
    yield
      assert(result.isRight, result.toString)
      assert(result.toOption.get.contains("definition ready"), s"must take the definition-ready branch, got: $result")
      assertEquals(projectFrames(collected).size, 1, s"exactly one project frame, got: $collected")
      val frame = projectFrames(collected).head
      assertEquals(field(frame, "type"), Some("projectCreated"))
      assertEquals(field(frame, "project"), Some("evt-nosession"))
      assertEquals(frame.hcursor.downField("mounted").as[Boolean].toOption, Some(false))
    end for
  }

  test("archive leg: POST /projects/<name>/archive emits exactly ONE projectArchived frame through the real route") {
    val ws = tempRoot / "ws-evt-archive"
    os.makeDir.all(ws)
    for
      created <- ProjectStore.create("evt-archive", ws.toString, None, "# template\n")
      pair <- hubWithFrames
      (hub, frames) = pair
      routes = mkRoutes(hub, os.pwd)
      authed = Request[IO](Method.POST, Uri.unsafeFromString("/projects/evt-archive/archive"))
        .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
      resp <- routes.routes(authed).value.map(_.getOrElse(fail("archive route fell through")))
      body <- resp.as[Json]
      collected <- frames.get
    yield
      assert(created.isRight, created.toString)
      assertEquals(resp.status, Status.Ok)
      assertEquals(body.hcursor.downField("archived").as[Boolean].toOption, Some(true))
      val archivedAt = body.hcursor.downField("archivedAt").as[Long].toOption
      assert(archivedAt.exists(_ > 0L), "response must carry archivedAt as epoch ms")
      assertEquals(projectFrames(collected).size, 1, s"exactly one project frame, got: $collected")
      val frame = projectFrames(collected).head
      assertEquals(field(frame, "type"), Some("projectArchived"))
      assertEquals(field(frame, "project"), Some("evt-archive"))
      assertEquals(
        frame.hcursor.downField("archivedAt").as[Long].toOption,
        archivedAt,
        "frame archivedAt must equal the response archivedAt (single source: ProjectStore.archive)"
      )
      assertEquals(frame.asObject.map(_.keys.toSet).getOrElse(Set.empty), Set("type", "project", "archivedAt"))
    end for
  }

  test("archive leg failure: unknown project emits ZERO frames (no success frame on Left)") {
    for
      pair <- hubWithFrames
      (hub, frames) = pair
      routes = mkRoutes(hub, os.pwd)
      authed = Request[IO](Method.POST, Uri.unsafeFromString("/projects/evt-missing/archive"))
        .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
      resp <- routes.routes(authed).value.map(_.getOrElse(fail("archive route fell through")))
      collected <- frames.get
    yield
      assertEquals(resp.status, Status.NotFound)
      assertEquals(projectFrames(collected).size, 0, s"failure path must not emit, got: $collected")
  }

  test("negative core: frame builders are the only producers, and no `projectsChanged` type exists") {
    val pd = ProjectDef(
      name = "evt-shape",
      description = None,
      workspace = "/tmp/ws",
      agentFile = "/tmp/ws/AGENTS.md",
      createdAt = 1234L
    )
    val created = ProjectActor.projectCreatedFrame(pd, mounted = true)
    val archived = ProjectActor.projectArchivedFrame("evt-shape", 5678L)
    val descUpdated = ProjectActor.projectDescriptionUpdatedFrame("evt-shape", "新描述", 9999L)
    IO {
      // description 为 None → 与 GET /api/projects 行同构地编码为 null（不是缺失键）
      assertEquals(created.hcursor.downField("row").downField("description").focus, Some(Json.Null))
      assertEquals(archived.hcursor.downField("archivedAt").as[Long].toOption, Some(5678L))
      // #920：描述帧载荷四键（type/project/description/updatedAt），description= 空串表示清空
      assertEquals(field(descUpdated, "type"), Some("projectDescriptionUpdated"))
      assertEquals(field(descUpdated, "project"), Some("evt-shape"))
      assertEquals(field(descUpdated, "description"), Some("新描述"))
      assertEquals(descUpdated.hcursor.downField("updatedAt").as[Long].toOption, Some(9999L))
      assertEquals(
        descUpdated.asObject.map(_.keys.toSet).getOrElse(Set.empty),
        Set("type", "project", "description", "updatedAt")
      )
      // 🔴 单 projectsChanged 事件未采（作者裁定 (e)）：帧 type 只可能是这三个身份名
      val types: Set[Option[String]] = Set(field(created, "type"), field(archived, "type"), field(descUpdated, "type"))
      assertEquals(
        types,
        Set[Option[String]](Some("projectCreated"), Some("projectArchived"), Some("projectDescriptionUpdated"))
      )
    }
  }

  // ── 台账 #920（2026-10-27）：面板侧描述写穿（POST /projects/<name>/description）────
  // 与 archive 腿同构的走真实路由四钉：成功帧恰一帧（载荷含写入值）/ 幂等腿零帧 +
  // unchanged 标记 / 未知项目 404 零帧 / 帧键形单点。

  test("description leg: POST /projects/<name>/description writes through and emits exactly ONE frame") {
    val ws = tempRoot / "ws-evt-desc"
    os.makeDir.all(ws)
    for
      created <- ProjectStore.create("evt-desc", ws.toString, None, "# template\n")
      pair <- hubWithFrames
      (hub, frames) = pair
      routes = mkRoutes(hub, os.pwd)
      authed = Request[IO](Method.POST, Uri.unsafeFromString("/projects/evt-desc/description"))
        .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
        .withEntity(Json.obj("description" -> Json.fromString("面板侧写入的描述")))
      resp <- routes.routes(authed).value.map(_.getOrElse(fail("description route fell through")))
      body <- resp.as[Json]
      stored <- ProjectStore.load("evt-desc")
      collected <- frames.get
    yield
      assert(created.isRight, created.toString)
      assertEquals(resp.status, Status.Ok)
      assertEquals(body.hcursor.downField("saved").as[Boolean].toOption, Some(true))
      // 写穿读数：project.json 的 description 单字段已变（ProjectStore.load 单点回读）
      assertEquals(stored.flatMap(_.description), Some("面板侧写入的描述"))
      assertEquals(projectFrames(collected).size, 1, s"exactly one project frame, got: $collected")
      val frame = projectFrames(collected).head
      assertEquals(field(frame, "type"), Some("projectDescriptionUpdated"))
      assertEquals(field(frame, "project"), Some("evt-desc"))
      assertEquals(field(frame, "description"), Some("面板侧写入的描述"))
      assert(frame.hcursor.downField("updatedAt").as[Long].toOption.exists(_ > 0L), "frame carries updatedAt epoch ms")
    end for
  }

  test("description leg idempotency: same value returns unchanged with ZERO frames") {
    val ws = tempRoot / "ws-evt-desc-idem"
    os.makeDir.all(ws)
    for
      created <- ProjectStore.create("evt-desc-idem", ws.toString, None, "# template\n")
      _ <- ProjectStore.updateDescription("evt-desc-idem", "同一句")
      pair <- hubWithFrames
      (hub, frames) = pair
      routes = mkRoutes(hub, os.pwd)
      authed = Request[IO](Method.POST, Uri.unsafeFromString("/projects/evt-desc-idem/description"))
        .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
        .withEntity(Json.obj("description" -> Json.fromString("同一句")))
      resp <- routes.routes(authed).value.map(_.getOrElse(fail("description route fell through")))
      body <- resp.as[Json]
      collected <- frames.get
    yield
      assert(created.isRight, created.toString)
      assertEquals(resp.status, Status.Ok)
      assertEquals(body.hcursor.downField("saved").as[Boolean].toOption, Some(true))
      assertEquals(
        body.hcursor.downField("unchanged").as[Boolean].toOption,
        Some(true),
        "idempotent leg carries the unchanged flag"
      )
      assertEquals(projectFrames(collected).size, 0, s"unchanged must not emit, got: $collected")
    end for
  }

  test("description leg failure: unknown project → 404 with ZERO frames") {
    for
      pair <- hubWithFrames
      (hub, frames) = pair
      routes = mkRoutes(hub, os.pwd)
      authed = Request[IO](Method.POST, Uri.unsafeFromString("/projects/evt-desc-missing/description"))
        .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
        .withEntity(Json.obj("description" -> Json.fromString("x")))
      resp <- routes.routes(authed).value.map(_.getOrElse(fail("description route fell through")))
      body <- resp.as[Json]
      collected <- frames.get
    yield
      assertEquals(resp.status, Status.NotFound)
      assert(body.hcursor.downField("error").as[String].toOption.isDefined, "404 body carries the error text")
      assertEquals(projectFrames(collected).size, 0, s"failure path must not emit, got: $collected")
    end for
  }

end ProjectEventSpec
