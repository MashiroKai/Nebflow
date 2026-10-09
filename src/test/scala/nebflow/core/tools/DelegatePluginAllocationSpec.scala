package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.kernel.Ref
import cats.effect.std.Dispatcher
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand}
import nebflow.agent.SharedResources
import nebflow.core.RateLimiter
import nebflow.core.delegate.{ChainStore, DelegateRegistry}
import nebflow.core.entity.BuiltinAgents
import nebflow.core.executor.ExternalSessionRunner
import nebflow.core.FileChangeTracker
import nebflow.core.jev.{CatalogEntry, JevAllocation, JevFallbackKind, PluginAllocator}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * Delegate spawn 腿分配链接读（jev-plugins 批 #1226）：
 *
 *  - 内置执行器腿（spawnBackground 单点，Mail 续聊复活腿同点复用）：分配三态经
 *    PluginAllocator stub 注入——分配集进 brief（`<injected-plugins>` 块）＋
 *    pluginMcp.acquire（grant 随会话）＋ DelegateRegistry.plugins 集 ＋ ChainStore
 *    delegate-spawn 行 plugins/allocation 字段（task 行结构帧可查）；
 *  - 失败语义分层：分配 fail-open（空集照常 spawn）；解析失败 = 显式拒 spawn；
 *  - **外部执行器零注入**（硬约束）：SpawnSpec 字段面零插件位——外部腿结构性不可注入。
 */
class DelegatePluginAllocationSpec extends CatsEffectSuite:

  override val munitIOTimeout = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-plugin-alloc"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "plugins")
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "chains")
    os.remove.all(tempRoot / "logs")
    os.remove.all(tempRoot / "general")
    os.write.over(tempRoot / "nebflow.json", "{}", createFolders = true)
    PluginAllocator.clear()
    nebflow.core.project.DelegateFlowHosts.clear

  override def afterEach(context: munit.AfterEach): Unit =
    PluginAllocator.clear()
    nebflow.core.project.DelegateFlowHosts.clear

  // ── 插件夹具（skills-only 单包；$schema canonical 必填） ──────────────

  private def writeSkillPlugin(name: String, marker: String): Unit =
    val dir = tempRoot / "plugins" / name
    os.makeDir.all(dir / "skills" / "howto")
    os.write.over(
      dir / "plugin.json",
      s"""{"$$schema":"${nebflow.core.plugin.PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$marker fixture"}""",
      createFolders = true
    )
    os.write.over(
      dir / "skills" / "howto" / "SKILL.md",
      s"""---
         |name: howto
         |description: injection test skill
         |---
         |## $marker""".stripMargin,
      createFolders = true
    )

  end writeSkillPlugin

  private class RecordingLlm(capture: TrieMap[String, LlmRequest]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      val put = IO(capture.update(req.sessionId, req)).void
      fs2.Stream.eval(put).drain ++ fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = nebflow.core.SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tempRoot / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  /**
   * 分配桩面：固定集合 + 调用记录。⚠️ 必须在 `mkResources` **之后**调用——
   * SharedResources 构造即装配生产 face（providerRegistry=null），先装的桩会被覆盖。
   */
  private def installStub(names: List[String], calls: ListBuffer[String] = ListBuffer.empty): ListBuffer[String] =
    PluginAllocator.install(
      new PluginAllocator.Face:
        def allocatePlugins(subject: String, taskText: String, catalog: List[CatalogEntry]) =
          IO {
            calls += subject
            PluginAllocator.PluginAllocationResult(
              JevAllocation.Allocated(names),
              Some(PluginAllocator.AllocationVia.Jev)
            )
          }
    )
    calls

  /** 全败桩（两臂全败 → 端口 Failure fail-open），并记录调用。 */
  private def installFailingStub(calls: ListBuffer[String] = ListBuffer.empty): ListBuffer[String] =
    PluginAllocator.install(
      new PluginAllocator.Face:
        def allocatePlugins(subject: String, taskText: String, catalog: List[CatalogEntry]) =
          IO {
            calls += subject
            PluginAllocator.PluginAllocationResult(JevAllocation.FellBack(JevFallbackKind.Failure, "stub down"), None)
          }
    )
    calls

  /**
   * spawn 是异步启动（adapter→actor→LLM sendStream），brief 到达 RecordingLlm 需要
   * 轮询等待（NodePluginChainSpec.waitUntil 同款）。
   */
  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) *> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /**
   * spawnBackground 直呼（内置执行器腿单点——Mail 续聊复活腿同点复用）。返回值附
   * 带系统停机句柄（测试卫生：会话 actor 随 system.stopAll 收敛）。
   */
  private def spawn(res: SharedResources, task: String): IO[(Either[ToolError, (String, String)], IO[Unit])] =
    val system = ActorSystem(s"dpalloc-${scala.util.Random.nextInt(100000)}")
    DelegateTool
      .spawnBackground(
        agentDef = BuiltinAgents.entry(DelegateTool.KernelAgentName).map(_.toAgentDef).get,
        task = task,
        description = "alloc spec",
        workspace = (tempRoot / "general").toString,
        project = None,
        taskId = None,
        system = system,
        resources = res,
        parentDepth = 0,
        parentRef = None,
        wsSend = None,
        parentSessionId = Some("root-x"),
        judgeWorkspace = false
      )
      .map(r => (r, system.stopAll.attempt.void))

  end spawn

  test("spawn leg: the allocated set rides the brief and the grant, and lands in the queryable records") {
    writeSkillPlugin("p1", "DELEGATE-ALLOC-MARKER")
    val capture = TrieMap[String, LlmRequest]()
    for
      res <- mkResources(new RecordingLlm(capture))
      // 桩装在 mkResources 之后（生产 face 构造即装配，先装会被覆盖——见 installStub 注）。
      calls = installStub(List("p1"))
      (spawned, shutdown) <- spawn(res, "ship the report")
      (sid, ack) = spawned.toOption.get
      // brief 到达是异步的（adapter→actor→LLM）：轮询到 capture 非空再取。
      _ <- waitUntil(30.seconds)(IO(capture.nonEmpty))
      req = capture.values.head
      rec = DelegateRegistry.find(sid)
      key = ChainStore.keyFor(None, sid)
      line = ChainStore.readSync(key).flatMap(_.entries.find(_.kind == "delegate-spawn"))
      holds <- res.pluginMcp.sessionHolds
      _ <- IO.sleep(200.millis) *> shutdown
    yield
      assert(calls.exists(_.startsWith("delegate-")), s"the stub was consulted with the delegate subject: $calls")
      val first = req.messages.head.textContent
      assert(first.contains("<injected-plugins>"), s"injected block missing from the brief:\n$first")
      assert(first.contains("DELEGATE-ALLOC-MARKER"), "the skill body reached the delegate brief")
      assert(first.startsWith("[workspace]"), "the brief keeps its workspace header above the injection")
      assertEquals(rec.flatMap(_.plugins), Some(List("p1")), "the registry record carries the allocated set")
      val payload = line.map(_.payload).getOrElse(fail("delegate-spawn line missing"))
      assertEquals(payload.hcursor.get[List[String]]("plugins").toOption, Some(List("p1")))
      assertEquals(payload.hcursor.get[String]("allocation").toOption, Some("jev"))
      assertEquals(holds.get(sid), None, "a skills-only package holds no MCP server")
      assert(ack.contains(sid), "the ack carries the continuation address payload")
    end for
  }

  test("spawn leg fail-open: the allocation family failing leaves an empty injection and a normal spawn") {
    writeSkillPlugin("p1", "FALLOPEN-ME") // 目录非空——否则 empty-catalog 短路先于分配端口
    val capture = TrieMap[String, LlmRequest]()
    for
      res <- mkResources(new RecordingLlm(capture))
      // 全败桩（显式模拟两臂全败：Jev 腿败 + LLM 兜底败 → 端口 Failure fail-open）。
      // 不能靠「端口未注册」模拟——mkResources 已装生产 face，桩必须在其后覆盖。
      calls = installFailingStub()
      (spawned, shutdown) <- spawn(res, "plain task")
      (sid, _) = spawned.toOption.get
      _ <- waitUntil(30.seconds)(IO(capture.nonEmpty))
      rec = DelegateRegistry.find(sid)
      _ <- IO.sleep(200.millis) *> shutdown
    yield
      assert(spawned.isRight, s"fail-open never blocks the spawn: $spawned")
      assert(calls.exists(_.startsWith("delegate-")), s"the stub was consulted (catalog non-empty): $calls")
      val first = capture.values.head.messages.head.textContent
      assert(!first.contains("<injected-plugins>"), "an empty allocation ⇒ byte-identical legacy brief")
      assertEquals(rec.flatMap(_.plugins), Some(Nil), "fail-open is an explicit empty allocation (Some(Nil), not None)")
    end for
  }

  test("spawn leg off: the allocator absent (port unregistered) records no set — None, not Some(Nil)") {
    writeSkillPlugin("p1", "OFF-ME") // 目录非空——分配面被咨询且以 Off 回落（非 empty-catalog）
    val capture = TrieMap[String, LlmRequest]()
    for
      res <- mkResources(new RecordingLlm(capture))
      // mkResources 之后 clear：拆走生产 face = 端口未注册 = Off（分配动作未发生）。
      _ = PluginAllocator.clear()
      (spawned, shutdown) <- spawn(res, "plain task")
      (sid, _) = spawned.toOption.get
      _ <- waitUntil(30.seconds)(IO(capture.nonEmpty))
      rec = DelegateRegistry.find(sid)
      _ <- IO.sleep(200.millis) *> shutdown
    yield
      assert(spawned.isRight, "Off never blocks the spawn")
      val first = capture.values.head.messages.head.textContent
      assert(!first.contains("<injected-plugins>"), "Off ⇒ byte-identical legacy brief")
      assertEquals(rec.flatMap(_.plugins), None, "an allocation that never ran records no set (None)")
  }

  test("spawn leg refuses explicitly when a chosen package fails resolution (分配失败是节点级失败，不静默降级)") {
    writeSkillPlugin("p1", "BLOCKED-ME")
    writeSkillPlugin("p2", "KEEPER")
    val capture = TrieMap[String, LlmRequest]()
    for
      res <- mkResources(new RecordingLlm(capture))
      // 桩装在 mkResources 之后（生产 face 覆盖问题——见 installStub 注）；封禁在
      // spawn 前落盘（block 写面自带 invalidateCache——扫描缓存键不含 nebflow.json）。
      calls = installStub(List("p1"))
      _ <- nebflow.core.plugin.PluginBlockPolicy.block("p1", "blocked fixture", "spec")
      (spawned, shutdown) <- spawn(res, "task")
      _ <- shutdown
    yield
      assert(spawned.isLeft, s"expected an explicit refusal, got: $spawned")
      assert(spawned.left.toOption.get.message.contains("PLUGIN_BLOCKED"), spawned.left.toOption.get.message)
      assertEquals(calls.size, 1, "the allocation ran once (catalog non-empty via p2)")
      assertEquals(capture.size, 0, "no session was ever started for a refused spawn")
  }

  test("external executor zero injection (硬约束): the SpawnSpec face has no plugin position at all") {
    // 结构性钉：外部腿的契约面（SpawnSpec）不存在插件位——注入面不存在的腿不可能
    // 被注入；stub 分配面在外部腿路径零调用（externalSpawn 不经 spawnBackground）。
    val spec = ExternalSessionRunner.SpawnSpec(
      executorId = "claude",
      binPath = "/usr/bin/true",
      task = "t",
      title = "t",
      workspace = "/tmp",
      project = None,
      taskId = None,
      parentSessionId = "",
      rootSessionId = "",
      parentRef = None,
      wsSend = None,
      resources = null
    )
    val fields = spec.productElementNames.toSet
    assertEquals(fields.intersect(Set("plugins", "allocatedPlugins", "injectedBlock", "pluginMcp")), Set.empty)
    // DelegateTool 源面钉：外部腿（externalSpawn）不触碰分配面。
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "DelegateTool.scala")
    val externalLeg = src.substring(src.indexOf("private def externalSpawn"), src.indexOf("private def spawnKernel"))
    assert(!externalLeg.contains("PluginInjection"), "the external leg must not consult the allocation face")
    assert(!externalLeg.contains("pluginMcp"), "the external leg must not touch the plugin MCP manager")
  }

end DelegatePluginAllocationSpec
