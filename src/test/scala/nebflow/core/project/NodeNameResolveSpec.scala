package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeTools, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/** Node 工具 project 名解析 spec（2026-09-07 作者裁定：工具 project 参数可选化 + 大小写容错）。
  *
  * 实证锚点：`NodeList(Nebflow) — Project 'Nebflow' is not mounted. Use ProjectCreate first.`
  * 分发器在项目沙箱内传 "Nebflow" 而被拒（registry 精确 key 匹配，canonical 名是小写）。
  *
  * 修复落点（three-way）：
  *  1. `ProjectRuntimeRegistry.get` 大小写不敏感（精确 key 优先，equalsIgnoreCase 兜底）——
  *     MailTool/TaskTool/FeedbackRouter/DispatchNotify 同享；
  *  2. 四 Node 工具 schema `project` 退为 optional（缺省=分发器当前项目，ctx.projectName）；
  *  3. 不匹配报错附可用项目列表（mountError 纯函数）。
  *
  * 本 spec 验证行为路径（resolveProject/registry get）——纯函数 mountError 由
  * NodeToolsSpec 覆盖。
  */
class NodeNameResolveSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-name-resolve"
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

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String, projectName: Option[String] = None): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("resolve-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system),
      projectName = projectName
    )

  // 注意：全局 ProjectRuntimeRegistry 跨 suite 共享；beforeEach 钩子不在此处清
  // （io 被丢弃不执行——munit beforeEach 为 Unit 返回型）。为保证本 spec 对
  // `resolveProject` 的 `all`（未命中报错列表）确定性，在每个测试的 IO 链内显式
  // `ProjectRuntimeRegistry.clear`（IO 链内一定执行），再挂载本 suite 的项目。
  test("R① 缺省走当前项目：project=None + ctx.projectName=Some(...) 解析为该项目") {
    val ws = tempRoot / s"ws-r1-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nr-r1-${Random.nextInt(100000)}")
    for
      _ <- ProjectRuntimeRegistry.clear
      res <- mkResources(system, tempRoot)
      rt <- mountProject("res-p1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString, projectName = Some("res-p1"))
      r <- NodeTools.resolveProject(None, ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(r.map(_.project.name), Right("res-p1"), "no explicit project → fall back to ctx.projectName (dispatcher's current project)")
  }

  test("R② 大小写不敏感：project=Some(\"RES-P1\") 解析成功（registry equalsIgnoreCase 兜底）") {
    val ws = tempRoot / s"ws-r2-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nr-r2-${Random.nextInt(100000)}")
    for
      _ <- ProjectRuntimeRegistry.clear
      res <- mkResources(system, tempRoot)
      rt <- mountProject("res-p1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      viaTool <- NodeTools.resolveProject(Some("RES-P1"), ctx)
      viaCtx <- NodeTools.resolveProject(Some("ReS-p1"), mkCtx(res, system, ws.toString, projectName = Some("other")))
      regGet <- ProjectRuntimeRegistry.get("RES-P1")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(viaTool.map(_.project.name), Right("res-p1"), "case variant project name must resolve case-insensitively")
      assertEquals(viaCtx.map(_.project.name), Right("res-p1"), "mixed-case variant resolves too")
      assertEquals(regGet.map(_.project.name), Some("res-p1"), "registry get is case-insensitive (benefits Mail/Task/Feedback/Dispatch too)")
  }

  test("R③ 不匹配报错含可用项目列表：实际名称提示，不裸报 not mounted") {
    val ws = tempRoot / s"ws-r3-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nr-r3-${Random.nextInt(100000)}")
    for
      _ <- ProjectRuntimeRegistry.clear
      res <- mkResources(system, tempRoot)
      _ <- mountProject("res-p1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- NodeTools.resolveProject(Some("nonexistent"), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, "unknown project must be rejected")
      val e = r.left.toOption.getOrElse(fail("expected a Left error"))
      assert(e.contains("nonexistent"), s"error must name the requested project, got: $e")
      // 挂载项目会列在可用列表里（前缀 + 实际项目名；不要求紧跟前缀——跨 suite 残留时
      // 列表可能含其它项目，仅需证明实际挂载项目被列出）
      assert(e.contains("Available projects:") && e.contains("res-p1"),
        s"error must list the mounted project(s) in 'Available projects:', got: $e")
      assert(e.contains("Use ProjectCreate first"), s"error must point to ProjectCreate, got: $e")
  }

end NodeNameResolveSpec
