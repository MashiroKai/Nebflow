package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
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
    os.remove.all(tempRoot / "workspaces")
    os.write.over(tempRoot / nebflow.shared.Branding.configFileName, "{}", createFolders = true)
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
    // 必要文件（作者令「plugin.md = 插件必要文件机制」）：注入面读包根 Plugin.md
    os.write.over(dir / "Plugin.md", s"# $name\n\n## $marker\n", createFolders = true)
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
  private def spawn(
    res: SharedResources,
    task: String,
    mode: nebflow.core.delegate.ModeDecision = nebflow.core.delegate.ModeParam.default,
    failStartup: Boolean = false,
    systemOverride: Option[ActorSystem] = None
  ): IO[(Either[ToolError, (String, String)], IO[Unit])] =
    val system = systemOverride.getOrElse(ActorSystem(s"dpalloc-${scala.util.Random.nextInt(100000)}"))
    DelegateTool
      .spawnBackground(
        agentDef = BuiltinAgents.entry(DelegateTool.KernelAgentName).map(_.toAgentDef).get,
        task = task,
        description = "alloc spec",
        workspace = (tempRoot / "general").toString,
        project = None,
        taskId = None,
        system = if failStartup then null else system,
        resources = res,
        parentDepth = 0,
        parentRef = None,
        wsSend = None,
        parentSessionId = Some("root-x"),
        allocateSeat = false,
        modeDecision = mode
      )
      .map(r => (r, system.stopAll.attempt.void))

  end spawn

  test("failed first merger startup preserves the identity and resumes only after explicit manual wake") {
    val capture = TrieMap[String, LlmRequest]()
    val repo = tempRoot / "general"
    val recoverySystem = ActorSystem("merger-manual-recovery")
    val exercise = for
      base <- mkResources(new RecordingLlm(capture))
      res = base.copy(actorSystem = recoverySystem)
      _ <- IO.blocking {
        os.makeDir.all(repo)
        os.proc("git", "init", "-b", "main").call(cwd = repo, stdout = os.Pipe)
      }
      mode = nebflow.core.delegate.ModeDecision("merger", "explicit", "user selected merger")
      (failed, stop) <- spawn(res, "Initialize the project merger", mode, failStartup = true)
      _ <- stop
      _ = assert(failed.isLeft)
      seed = nebflow.core.delegate.WorkspaceLedger.default.snapshot.mergerBootstraps.values.head
      id = seed.record.id
      _ = assertEquals(seed.status, "failed")
      _ = assert(DelegateRegistry.isFrozen(id))
      _ = assert(capture.isEmpty)
      transcript <- res.sessionStore.loadMessagesForSession(id)
      _ = assert(transcript.exists(_.textContent.contains(nebflow.prompts.PromptCatalog.MergerRole)))
      row <- res.subAgentTaskStore.findByTaskId(id)
      _ = assert(row.exists(_.status == "frozen"))
      (duplicate, stopDuplicate) <- spawn(res, "A second initialization must not run", mode)
      _ <- stopDuplicate
      _ = assertEquals(duplicate.toOption.get._1, id)
      _ = assert(duplicate.toOption.get._2.contains("taskAccepted=false"))
      _ = assert(capture.isEmpty)
      boot <- nebflow.agent.SubAgentStartupRecovery.sweep(
        res.subAgentTaskStore,
        res.sessionStore,
        Some(nebflow.agent.SubAgentStartupRecovery.RevivalDeps(res, recoverySystem, None)),
        manual = false
      )
      _ = assert(boot.revived.isEmpty, "initialization failure cannot be revived by an automatic boot policy")
      _ = assert(capture.isEmpty)
      _ <- nebflow.core.delegate.MergerInitialization.exclusive(id) {
        nebflow.agent.DelegateWakeLeg.wake(res, None, id, "must not race startup", "panel-wake").map { result =>
          assert(result.left.toOption.exists(_.contains("RECOVERY_IN_PROGRESS")))
          assert(capture.isEmpty)
        }
      }
      woke <- nebflow.agent.DelegateWakeLeg.wake(res, None, id, "Continue initialization", "panel-wake")
      _ = assert(woke.isRight, woke.toString)
      _ <- waitUntil(30.seconds)(IO(capture.nonEmpty))
      _ = assertEquals(nebflow.core.delegate.WorkspaceLedger.default.snapshot.mergers.map(_.delegateId), List(id))
      _ = assertEquals(nebflow.core.delegate.WorkspaceLedger.default.snapshot.mergerBootstraps(id).status, "ready")
      _ = assert(DelegateRegistry.find(id).exists(_.mode.contains("merger")))
      _ = assert(capture.values.head.messages.exists(_.textContent.contains("Initialize the project merger")))
      live <- res.agentRegistry.get
      _ = assert(live.contains(id))
    yield ()
    exercise.guarantee(recoverySystem.stopAll)
  }

  test("adapter startup failure stops the already-created child and preserves a frozen initialization record") {
    val capture = TrieMap[String, LlmRequest]()
    val underlying = ActorSystem("partial-merger-startup")
    val exercise = for
      children <- Ref.of[IO, List[nebflow.actor.ActorPath]](Nil)
      failing = new ActorSystem:
        def localDevice = underlying.localDevice
        def spawn[M](
          behavior: nebflow.actor.Behavior[M],
          name: String,
          ttl: Option[FiniteDuration] = None
        ): IO[nebflow.actor.ActorRef[M]] =
          if name.endsWith("-adapter") then
            IO.raiseError(new IllegalStateException("simulated adapter initialization failure"))
          else underlying.spawn(behavior, name, ttl).flatTap(ref => children.update(_ :+ ref.path))
        def resolve[M](path: String) = underlying.resolve[M](path)
        def stop(ref: nebflow.actor.ActorRef[?]) = underlying.stop(ref)
        def stopAll = underlying.stopAll
        def isAlive(path: nebflow.actor.ActorPath) = underlying.isAlive(path)
        def watch(watcher: nebflow.actor.ActorPath, target: nebflow.actor.ActorPath) = underlying.watch(watcher, target)
        def unwatch(watcher: nebflow.actor.ActorPath, target: nebflow.actor.ActorPath) =
          underlying.unwatch(watcher, target)
        def deadLetterCount = underlying.deadLetterCount
        def replayWatches = underlying.replayWatches
      base <- mkResources(new RecordingLlm(capture))
      res = base.copy(actorSystem = failing)
      _ <- IO.blocking {
        val repo = tempRoot / "general"
        os.makeDir.all(repo)
        os.proc("git", "init", "-b", "main").call(cwd = repo, stdout = os.Pipe)
      }
      (result, _) <- spawn(
        res,
        "Initialize without leaving an unsupervised actor",
        nebflow.core.delegate.ModeDecision("merger", "explicit", "selected"),
        systemOverride = Some(failing)
      )
      _ = assert(result.isLeft)
      paths <- children.get
      _ = assertEquals(paths.size, 1)
      alive <- paths.traverse(underlying.isAlive)
      _ = assert(alive.forall(!_))
      _ = assert(capture.isEmpty)
      seed = nebflow.core.delegate.WorkspaceLedger.default.snapshot.mergerBootstraps.values.head
      _ = assertEquals(seed.status, "failed")
      _ = assert(DelegateRegistry.isFrozen(seed.record.id))
      live <- res.agentRegistry.get
      _ = assert(live.isEmpty)
      retry <- nebflow.agent.DelegateWakeLeg.wake(res, None, seed.record.id, "retry initialization", "panel-wake")
      _ = assert(retry.isLeft)
      retriedPaths <- children.get
      _ = assertEquals(retriedPaths.size, 2)
      retryAlive <- retriedPaths.traverse(underlying.isAlive)
      _ = assert(retryAlive.forall(!_))
      retryRow <- res.subAgentTaskStore.findByTaskId(seed.record.id)
      _ = assert(retryRow.exists(_.status == "frozen"))
      _ = assertEquals(
        nebflow.core.delegate.WorkspaceLedger.default.snapshot.mergerBootstraps(seed.record.id).status,
        "failed"
      )
    yield ()
    exercise.guarantee(underlying.stopAll)
  }

  test("interrupted creation repairs missing metadata without execution and never replaces ready history") {
    val capture = TrieMap[String, LlmRequest]()
    val id = "delegate-bootstrap-interrupted"
    for
      res <- mkResources(new RecordingLlm(capture))
      _ <- IO.blocking {
        val record = DelegateRegistry.Record(
          id,
          "nebflow",
          tempRoot.toString,
          None,
          None,
          "root-x",
          "Interrupted merger initialization",
          System.currentTimeMillis(),
          mode = Some("merger")
        )
        nebflow.core.delegate.WorkspaceLedger.default.merger(
          "project",
          "repository",
          id,
          Some(nebflow.core.delegate.WorkspaceLedger.MergerBootstrap(record, "saved initialization brief"))
        )
      }
      _ <- nebflow.core.delegate.MergerInitialization.repairAll(res)
      _ = assert(capture.isEmpty)
      _ = assert(DelegateRegistry.isFrozen(id))
      row <- res.subAgentTaskStore.findByTaskId(id)
      _ = assert(row.exists(_.status == "frozen"))
      saved <- res.sessionStore.loadMessagesForSession(id)
      _ = assertEquals(saved.map(_.textContent), List("saved initialization brief"))
      _ <- nebflow.core.delegate.MergerInitialization.exclusive(id) {
        nebflow.core.delegate.MergerInitialization.repair(res, id).attempt.map(result => assert(result.isLeft))
      }
      _ <- nebflow.core.delegate.MergerInitialization.ready(id)
      _ <- nebflow.core.delegate.MergerInitialization.failed(res, id, "later execution failed")
      _ = assert(nebflow.core.delegate.WorkspaceLedger.default.snapshot.mergerBootstraps(id).everStarted)
      _ <- res.sessionStore.durableSwapMessages(id, Nil)
      _ <- IO.blocking(DelegateRegistry.remove(id))
      refused <- nebflow.core.delegate.MergerInitialization.repair(res, id).attempt
      _ = assert(refused.isLeft)
      stillMissing <- res.sessionStore.loadMessagesForSession(id)
      _ = assert(stillMissing.isEmpty)
      _ = assert(DelegateRegistry.find(id).isEmpty)
      _ = assert(capture.isEmpty)
    yield ()
    end for
  }

  test("concurrent merger creation starts one real actor and injects its integration role without allocation") {
    val capture = TrieMap[String, LlmRequest]()
    val repo = tempRoot / "general"
    for
      res <- mkResources(new RecordingLlm(capture))
      calls = installStub(List("must-not-allocate"))
      _ <- IO.blocking {
        os.makeDir.all(repo)
        os.proc("git", "init", "-b", "main").call(cwd = repo, stdout = os.Pipe)
      }
      attempts <- List
        .fill(12)(
          spawn(
            res,
            "Inspect authorized requests",
            nebflow.core.delegate.ModeDecision("merger", "explicit", "user selected merger")
          )
        )
        .parSequence
      _ <- waitUntil(30.seconds)(IO(capture.nonEmpty))
      results = attempts.map(_._1.toOption.get)
      ids = results.map(_._1).distinct
      _ = assertEquals(ids.size, 1)
      _ = assertEquals(results.count(!_._2.contains("taskAccepted=false")), 1)
      _ = assertEquals(capture.size, 1)
      _ = assert(capture.values.head.messages.head.textContent.contains(nebflow.prompts.PromptCatalog.MergerRole))
      _ = assert(!capture.values.head.messages.head.textContent.contains(DelegateTool.BriefReportLine))
      _ = assert(calls.isEmpty)
      _ = assertEquals(DelegateRegistry.find(ids.head).flatMap(_.mode), Some("merger"))
      _ = assertEquals(nebflow.core.delegate.WorkspaceLedger.default.snapshot.mergers.map(_.delegateId), ids)
      _ <- attempts.traverse_(_._2)
    yield ()
    end for
  }

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

  test("spawn leg allocation failure refuses execution by default") {
    writeSkillPlugin("p1", "UNKNOWN-CAPABILITIES")
    val capture = TrieMap[String, LlmRequest]()
    for
      res <- mkResources(new RecordingLlm(capture))
      calls = installFailingStub()
      (result, shutdown) <- spawn(res, "must not start without known capabilities")
      _ <- shutdown
    yield
      assert(result.left.toOption.exists(_.toString.contains("DELEGATE_PLUGINS_REFUSED")))
      assert(calls.nonEmpty)
      assert(capture.isEmpty)
      assert(DelegateRegistry.list().isEmpty)
  }

  test("spawn leg fail-open: explicit opt-in permits an empty injection after allocation failure") {
    os.write.over(
      tempRoot / nebflow.shared.Branding.configFileName,
      """{"plugins":{"failOpenOnAllocationError":true}}"""
    )
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
