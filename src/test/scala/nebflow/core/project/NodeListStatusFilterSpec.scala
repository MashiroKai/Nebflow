package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeListTool, NodeTools, ToolContext, ToolError}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/** NodeList status 过滤参数 spec（观测面上下文经济学批 20260907 裁定⑤a）。
  *
  * 审计基准：活动区常态 131 节点（96% 终态），默认全量载荷被节点数乘数放大；
  * 过滤参数让分发器按状态切片读取（如 "running,pending" 只看在飞工作）。
  * 契约三点：
  *  1. **缺省全量铁律**：无 status 调用输出字节级等于全量路径（向后兼容——
  *     REST/前端/既有分发器行为零改动）；
  *  2. 三形态宽容（单值/逗号串/array）+ 组合过滤返回子集；
  *  3. 非法值可描述报错（列出全部合法枚举）。
  */
class NodeListStatusFilterSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-nodelist-status-filter"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private class NoLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = new NoLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（生产默认开；
        // 腿 2 默认开行为由 NodeReportReminderSpec 覆盖）。
        reportGateHold = Some(false)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("filter-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  /** 种 6 节点跨 5 状态（无 running——避免 liveness 探测路径，纯过滤语义验证）。 */
  private def seedStatuses(rt: ProjectRuntime): IO[Unit] =
    val now = System.currentTimeMillis()
    val defs = List(
      ("n-w1", "wiring 节点", NodeLifecycle.Wiring),
      ("n-p1", "pending 节点", NodeLifecycle.Pending),
      ("n-c1", "completed 甲", NodeLifecycle.Completed),
      ("n-c2", "completed 乙", NodeLifecycle.Completed),
      ("n-f1", "failed 节点", NodeLifecycle.Failed),
      ("n-b1", "blocked 节点", NodeLifecycle.Blocked)
    ).map { (id, name, st) =>
      id -> NodeDef(id = id, name = name, agent = "general", status = st, createdAt = now)
    }
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ defs.toMap)).void

  private def listCall(project: String, ctx: ToolContext, extra: (String, Json)*): IO[Either[ToolError, String]] =
    NodeListTool.call(JsonObject.fromIterable(List("project" -> Json.fromString(project)) ++ extra), ctx)

  private def parseOk(r: Either[ToolError, String]): Json =
    io.circe.parser.parse(r.toOption.getOrElse(fail(s"NodeList failed: $r"))).getOrElse(fail("not json"))

  private def statusOf(json: Json): List[String] =
    json.hcursor.downField("nodes").as[List[Json]].toOption.getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("status").toOption)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  test("F① 缺省全量铁律：无 status 调用 = buildNodeListPayload 默认路径（字节级一致）+ 全状态在场") {
    val ws = tempRoot / s"ws-f1-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nsf-f1-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nsf-f1", ws, system, res)
      _ <- seedStatuses(rt)
      ctx = mkCtx(res, system, ws.toString)
      viaTool <- listCall("nsf-f1", ctx)
      viaDirect <- NodeTools.buildNodeListPayload(rt).map(_.noSpaces)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val toolRaw = viaTool.toOption.getOrElse(fail(s"NodeList failed: $viaTool"))
      assertEquals(toolRaw, viaDirect, "no-filter NodeList tool output must be byte-identical to the default payload path (裁定⑤a 铁律)")
      val statuses = statusOf(parseOk(viaTool))
      assertEquals(statuses.size, 6, "all six seeded nodes present without filter")
      assertEquals(statuses.count(_ == NodeLifecycle.Completed), 2)
  }

  test("F② 组合过滤：三形态（单值/逗号串/array）返回子集；不匹配状态 → 空 nodes") {
    val ws = tempRoot / s"ws-f2-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nsf-f2-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nsf-f2", ws, system, res)
      _ <- seedStatuses(rt)
      ctx = mkCtx(res, system, ws.toString)
      single <- listCall("nsf-f2", ctx, "status" -> Json.fromString("completed"))
      csv <- listCall("nsf-f2", ctx, "status" -> Json.fromString("failed,blocked"))
      arr <- listCall("nsf-f2", ctx, "status" -> Json.arr(Json.fromString("wiring"), Json.fromString("pending")))
      none <- listCall("nsf-f2", ctx, "status" -> Json.fromString("running"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(statusOf(parseOk(single)), List(NodeLifecycle.Completed, NodeLifecycle.Completed), "single-value filter returns only completed")
      assertEquals(statusOf(parseOk(csv)).toSet, Set(NodeLifecycle.Failed, NodeLifecycle.Blocked), "comma-separated multi-filter")
      assertEquals(statusOf(parseOk(arr)).toSet, Set(NodeLifecycle.Wiring, NodeLifecycle.Pending), "array multi-filter")
      assertEquals(statusOf(parseOk(none)), Nil, "no matching status → empty nodes array (still valid payload)")
      // meta 与 worktrees 仍在（载荷骨架不因过滤缺席）
      assertEquals(parseOk(none).hcursor.downField("meta").get[String]("project").toOption, Some("nsf-f2"))
  }

  test("F③ 非法值可描述报错：列出合法枚举；空串/逗号归一为全量") {
    val ws = tempRoot / s"ws-f3-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nsf-f3-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nsf-f3", ws, system, res)
      _ <- seedStatuses(rt)
      ctx = mkCtx(res, system, ws.toString)
      bad <- listCall("nsf-f3", ctx, "status" -> Json.fromString("done"))
      badArr <- listCall("nsf-f3", ctx, "status" -> Json.arr(Json.fromString("completed"), Json.fromString("bogus")))
      blank <- listCall("nsf-f3", ctx, "status" -> Json.fromString(""))
      nullForm <- listCall("nsf-f3", ctx, "status" -> Json.Null)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(bad.isLeft, "unknown status must be rejected")
      val msg = bad.left.toOption.map(_.message).getOrElse("")
      assert(msg.contains("done") && msg.contains(NodeLifecycle.Blocked) && msg.contains(NodeLifecycle.Pending),
        s"error must name the bad value + list valid enums, got: $msg")
      assert(badArr.isLeft, "array containing an invalid value must be rejected wholesale")
      // 空串/null → 宽容归一为全量（不炸、不空列表）
      assertEquals(statusOf(parseOk(blank)).size, 6, "blank string degrades to full listing")
      assertEquals(statusOf(parseOk(nullForm)).size, 6, "explicit null degrades to full listing")
  }

end NodeListStatusFilterSpec
