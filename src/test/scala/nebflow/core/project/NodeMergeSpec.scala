package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.{JsonObject, Json as CJson}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, PermissionPolicy, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.sandbox.{SandboxConfig, SandboxRuntime}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/**
 * 合并节点（merge node）机制 E2E（merge-node 批 20260905，方案
 * .nebflow/Spec/merge-node-plan.md §6）：进程内 stub LLM + 临时 git fixture
 * （隔离 NEBFLOW_HOME 下建项目仓，引擎/host 侧均有写权限）。
 *
 * 触发语义四场景（§3 spec）：
 *  - S1 两并行任务节点 → 合并节点：全 completed 触发，真实执行 --no-ff 合并 +
 *    worktree remove + branch -d，git worktree list / for-each-ref 零残留断言。
 *    合并经真实 agent 栈执行：stub LLM 发 Bash tool_use → 权限（AutoAll seed）→
 *    BashTool → ShellSession（cwd=沙箱根=workspace）——git 变更对根内 .git 的
 *    写能力（权限选型③核心）随落地成功一并实证。
 *  - S2 单上游 blocked：不触发（走既有 blocked 重入/升级链路——escalate-only 档
 *    经 recorder 可观测），合并节点保持 wiring 不悬挂不启动。
 *  - S3 单上游 failed：不触发且不悬挂——引擎把合并节点转 blocked 可见终态
 *    （MergeNodePolicy，category=upstream-incomplete，blockCount 不增），
 *    Nebula 收到 merge-blocked 通报，git 零合并零清理（残留原样保留待处置）。
 *  - S4 上游 held：release 后按 completed 计入（NodeHoldSpec T2 语义衔接），
 *    放行触发合并真实落地。
 *
 * 附 T0：NodeEdit create 校验（merge+worktree 拒绝；merge=true 落库；归档节点
 * 拒设 merge）。
 */
class NodeMergeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-merge"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"merge regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // merge-agent：携带 Bash 工具（合并节点落地执行者）
  os.makeDir.all(tempRoot / "agents" / "merge-agent")
  os.write.over(
    tempRoot / "agents" / "merge-agent" / "agent.json",
    """{"name":"merge-agent","description":"merge landing agent","tools":["Bash"],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "merge-agent" / "system.md", "# merge-agent\n")

  // 沙箱后端初始化（GatewayMain 同款单点）。环境约束：本测试 JVM 运行在宿主沙箱
  // 进程树内（workspace-root 会话跑 sbt）——嵌套 sandbox-exec probe 必败（实测
  // 2026-09-05）→ 显式 bashFailIfUnavailable=false 降级：git 落地真实执行（cwd=
  // 沙箱根、根内 .git 写放行的 cwd 语义不变），仅 Seatbelt 包裹层在此环境缺席；
  // 「root 内 .git 可写」的生产属性由 SandboxPolicy/Seatbelt 代码证据链承载
  // （spec §4），落地命令集与零残留断言不受影响。
  SandboxRuntime.init(SandboxConfig(enabled = true, bashFailIfUnavailable = false))

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // ── stub LLM：按输入文本分流应答 ─────────────────────────

  /** 脚本化 LLM：
    *   - 输入含 blocked-please → BLOCKED JSON（S2）
    *   - 输入含 boom-please   → 流错误（非可重试 → fail fast → AgentEvent.Failed，S3）
    *   - 输入含 MERGE-landing → 从任务文本提取 CMD:…END 命令块发 Bash tool_use；
    *     第二轮（见 ToolResult）应答 merge done（S1/S4 落地执行）
    *   - 其余 → 首行 echo（上游任务节点结果）
    * delayEcho：上游 echo 应答延迟（S1 制造「两个上游都接线后才完成」的确定性窗口）。 */
  private class MergeLlm(delayEcho: FiniteDuration = 0.millis):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        val toolResults = req.messages
          .flatMap(_.content.toOption.toList.flatten)
          .collect { case ContentBlock.ToolResult(_, content, _) => content }
        Stream.eval(inputs.update(_ :+ text)).flatMap { _ =>
          if toolResults.nonEmpty then
            Stream.eval(IO.sleep(50.millis)) >>
              Stream(StreamChunk.TextDelta(s"merge done (${toolResults.mkString.take(120)})"), StreamChunk.Done(None, None))
          else if text.contains("blocked-please") then
            Stream(
              StreamChunk.TextDelta(
                "BLOCKED\n{\"category\":\"external-dependency\",\"detail\":\"vendor API down\",\"suggestion\":\"retry later\"}"),
              StreamChunk.Done(None, None))
          else if text.contains("boom-please") then
            Stream.raiseError[IO](new RuntimeException("injected failure (boom-please)"))
          else if text.contains("MERGE-landing") then
            val cmd = MergeLlm.extractCmd(text)
            Stream(
              StreamChunk.ToolCallChunk(ToolCall(
                "merge-landing-cmd",
                "Bash",
                JsonObject("command" -> cmd.asJson, "timeout" -> 120000.asJson))),
              StreamChunk.Done(Some("tool_use"), None))
          else
            Stream.eval(IO.sleep(delayEcho)) >>
              Stream(
                StreamChunk.TextDelta(text.linesIterator.nextOption().getOrElse("ok").take(200)),
                StreamChunk.Done(None, None))
        }
  object MergeLlm:
    /** 从节点 task 文本提取 CMD: 与 END 之间的落地命令块——E2E 直接执行分发器
      * 模板原文，验证模板命令集真实可执行。 */
    def extractCmd(text: String): String =
      """(?s)CMD:\s*(.*?)\s*END""".r.findFirstMatchIn(text).map(_.group(1).trim).getOrElse("echo no-cmd-found")

  // ── git fixture（host 侧，无沙箱——等价分发器用 workspace 根会话建仓）──

  private def git(ws: os.Path, args: String*): String =
    val argv: Seq[os.Shellable] = (Seq("git", "-C", ws.toString) ++ args).map(s => s: os.Shellable)
    os.proc(argv*).call(cwd = ws, stdout = os.Pipe, stderr = os.Pipe).out.text()

  /** 建隔离项目仓：main + 初始提交 + git 身份（worktree 共享 repo config）。 */
  private def initRepo(ws: os.Path): Unit =
    os.makeDir.all(ws)
    os.proc("git", "init", "-b", "main").call(cwd = ws, stdout = os.Pipe, stderr = os.Pipe)
    git(ws, "config", "user.email", "merge-spec@nebflow.local")
    git(ws, "config", "user.name", "merge-spec")
    os.write.over(ws / "README.md", "base\n")
    git(ws, "add", ".")
    git(ws, "commit", "-m", "init")

  /** 分发器等价操作：建 worktree + 分支 + 产物提交（worktree 内 commit）。 */
  private def mkWork(ws: os.Path, name: String): Unit =
    os.makeDir.all(ws / ".nebflow" / "worktrees")
    git(ws, "worktree", "add", s".nebflow/worktrees/$name", "-b", s"feat/$name")
    val wt = ws / ".nebflow" / "worktrees" / name
    os.write.over(wt / s"artifact-$name.txt", s"$name artifact\n")
    os.proc("git", "-C", wt.toString, "add", ".").call(stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", wt.toString, "commit", "-m", s"feat($name): artifact")
      .call(stdout = os.Pipe, stderr = os.Pipe)

  private def worktreeNames(ws: os.Path): List[String] =
    git(ws, "worktree", "list", "--porcelain").linesIterator
      .filter(_.startsWith("worktree ")).map(_.stripPrefix("worktree ")).toList

  private def branchNames(ws: os.Path): List[String] =
    git(ws, "for-each-ref", "refs/heads", "--format=%(refname:short)").linesIterator.toList

  /** 分发器模板原文（staging/system-addendum-merge-node.md 同款命令集）。 */
  private def mergeTask(a: String, b: String): String =
    s"""MERGE-landing 合并落地：把上游分支逐支 --no-ff 合并进 main 并清理本批 worktree/分支。
       |上游清单：feat/$a (worktree $a)；feat/$b (worktree $b)。
       |CMD:
       |git merge --no-ff feat/$a -m "merge: $a" &&
       |git merge --no-ff feat/$b -m "merge: $b" &&
       |git worktree remove .nebflow/worktrees/$a &&
       |git worktree remove .nebflow/worktrees/$b &&
       |git branch -d feat/$a && git branch -d feat/$b &&
       |git worktree list && git for-each-ref refs/heads
       |END""".stripMargin

  // ── 基建（NodeHoldSpec 同款）─────────────────────────────

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
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
      actorSystem = null,
      voiceMutedRef = voiceMuted,
      // 会话沙箱策略：显式降级 bashFailIfUnavailable=false（会话策略来源是本字段
      // 而非 SandboxRuntime.init 参数——S4 实测 8ms SANDBOX_UNAVAILABLE 教训）
      sandboxConfig = SandboxConfig(enabled = true, bashFailIfUnavailable = false)
    )

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("merge-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: CJson, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
      cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def nodeInput(project: String, nodename: String, extra: (String, CJson)*): CJson =
    CJson.obj(("project" -> CJson.fromString(project)) :: ("nodename" -> CJson.fromString(nodename)) :: extra.toList*)

  private def registerRecorder(res: SharedResources, system: ActorSystem, sid: String): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"merge-rec-${scala.util.Random.nextInt(100000)}")
      _ <- res.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid)))
    yield recorded

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def recordedImmediate(recorded: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    feedbackMode: String = FeedbackRouter.ModeAuto
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: CJson) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        feedbackMode = feedbackMode,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  /** 合并节点 Bash 落地的权限放行：root 会话桶 seed AutoAll（真实施 = 用户对根
    * 会话开 auto-all；桶缺失会回落 nebflow.json 全局档=ConfirmEdits → Bash 逐条
    * 判可逆性，git merge 非可逆会被 Ask 卡死）。 */
  private def allowBash(res: SharedResources): IO[Unit] =
    res.permissionPolicies.update(_ + ("nebula-root" -> PermissionPolicy(safetyMode = nebflow.core.SafetyMode.AutoAll)))

  private def byName(rt: ProjectRuntime, name: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n
      case None    => fail(s"node '$name' must exist")
    }

  private def idOf(rt: ProjectRuntime, name: String): IO[String] = byName(rt, name).map(_.id)

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String], timeout: FiniteDuration = 30.seconds): IO[Unit] =
    waitUntil(timeout) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── T0 NodeEdit create 校验 ─────────────────────────────

  test("T0: merge=true refuses worktree; persists merge flag; archived node refuses merge") {
    val ws = tempRoot / "ws-t0"
    os.makeDir.all(ws)
    initRepo(ws)
    val system = ActorSystem(s"merge-t0-${scala.util.Random.nextInt(100000)}")
    val llm = MergeLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("merge-t0", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // N1: merge=true + worktree → 拒（merge 校验插在 hold 校验之后、agent/
      // worktree 实存校验之前——用不存在的 worktree 名也会先被 merge 校验拦下）
      n1 <- nodeEdit(nodeInput("merge-t0", "m1", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("merge"), "out" -> CJson.fromString("Nebula"),
        "merge" -> CJson.fromBoolean(true), "worktree" -> CJson.fromString("nope")), ctx)
      // N2: merge=true 落库（无 worktree）
      n2 <- nodeEdit(nodeInput("merge-t0", "m2", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("merge-task"), "out" -> CJson.fromString("Nebula"),
        "merge" -> CJson.fromBoolean(true)), ctx)
      m2 <- byName(rt, "m2")
      // N2b: 合并节点不随创建自动启动（E2E 实测缺陷修复：task 只是落地指令集，
      // 不构成入口语义——零上游时开跑 = 落地在空输入上执行）
      _ <- IO.sleep(300.millis)
      m2b <- byName(rt, "m2")
      // N3: 归档节点拒设 merge（store 直种归档区——TTL 过期等价形态）
      _ <- rt.store.mutateArchive(a => a.copy(nodes = a.nodes + ("n-arch-x" -> NodeDef(
        id = "n-arch-x", name = "arch-x", agent = "test-agent",
        status = NodeLifecycle.Completed, result = Some("old"),
        createdAt = 1L, completedAt = Some(1L)))))
      n3 <- nodeEdit(nodeInput("merge-t0", "arch-x", "merge" -> CJson.fromBoolean(true)), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(n1.isLeft && n1.left.exists(_.contains("merge=true")),
        s"N1 merge+worktree must refuse with actionable text, got $n1")
      assert(n2.isRight, s"N2 merge create must succeed, got $n2")
      assertEquals(m2.merge, true, "merge flag must persist")
      assertEquals(m2.worktree, None, "merge node must have no worktree")
      assertEquals(m2.status, NodeLifecycle.Pending, "merge node (task, no in) starts pending")
      assertEquals(m2b.status, NodeLifecycle.Pending,
        s"merge node must NOT auto-start on create (no upstreams yet), got ${m2b.status}")
      assert(n3.isLeft && n3.left.exists(_.contains("create-only")),
        s"N3 merge on archived node must refuse, got $n3")
  }

  // ── S1 全 completed → 触发 + 真实落地 + 零残留 ──────────

  test("S1: two upstreams completed → merge node triggers, lands --no-ff merges, zero worktree/branch residue") {
    val ws = tempRoot / "ws-s1"
    initRepo(ws)
    mkWork(ws, "task-a")
    mkWork(ws, "task-b")
    val system = ActorSystem(s"merge-s1-${scala.util.Random.nextInt(100000)}")
    // 上游 echo 延迟 400ms：保证 merge.in=[a,b] 在任一上游完成前接线完成（防
    // 「单上游即满足 barrier」竞态——NodeBarrierDeliverySpec ③ 同款手法）
    val llm = MergeLlm(delayEcho = 400.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- allowBash(res)
      rt <- mountProject("merge-s1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 生产顺序：合并节点先建（task + out=Nebula + merge=true → pending）
      _ <- nodeEdit(nodeInput("merge-s1", "merge-x", "agent" -> CJson.fromString("merge-agent"),
        "task" -> CJson.fromString(mergeTask("task-a", "task-b")),
        "out" -> CJson.fromString("Nebula"), "merge" -> CJson.fromBoolean(true)), ctx)
      mergeXId <- idOf(rt, "merge-x") // out 目标契约 = 节点 id（deliverOut 按 id 查找）
      // 任务节点逐个 out→merge（setOut 反向补 in 边）
      _ <- nodeEdit(nodeInput("merge-s1", "task-a", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("produce artifact a (feat/task-a)"),
        "out" -> CJson.fromString(mergeXId)), ctx)
      _ <- nodeEdit(nodeInput("merge-s1", "task-b", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("produce artifact b (feat/task-b)"),
        "out" -> CJson.fromString(mergeXId)), ctx)
      _ <- waitStatus(rt, "task-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "task-b", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "merge-x", Set(NodeLifecycle.Completed), timeout = 60.seconds)
      merge <- byName(rt, "merge-x")
      b <- byName(rt, "task-b")
      mergeInput <- llm.inputs.get.map(_.find(t => t.contains("=== Node task-a ===") && t.contains("=== Node task-b ===")))
      log <- IO(git(ws, "log", "--oneline", "main"))
      branches = branchNames(ws)
      wts = worktreeNames(ws)
      artifactA = os.exists(ws / "artifact-task-a.txt")
      artifactB = os.exists(ws / "artifact-task-b.txt")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // barrier 触发：合并节点收齐两上游结果后才启动（输入含两个结果头）
      assertEquals(merge.status, NodeLifecycle.Completed, "merge node must run to completion")
      assert(mergeInput.isDefined,
        s"merge input must carry BOTH upstream results, got ${llm.inputs.get.unsafeRunSync().map(_.take(100))}")
      assertEquals(b.status, NodeLifecycle.Completed)
      // 落地真实发生：两支合并提交进 main + 产物文件落 workspace
      assert(log.contains("merge: task-a") && log.contains("merge: task-b"),
        s"main must contain both merge commits, got:\n$log")
      assert(artifactA && artifactB, "merged artifacts must exist in workspace")
      // 防污染闭环：git worktree list / for-each-ref 零残留
      assert(!wts.exists(p => p.endsWith("task-a") || p.endsWith("task-b")),
        s"no batch worktree may remain, got $wts")
      assertEquals(wts.count(_.endsWith("ws-s1")), 1, "only the main worktree remains")
      assert(!branches.contains("feat/task-a") && !branches.contains("feat/task-b"),
        s"no batch branch may remain, got $branches")
      assert(branches.contains("main"), "main must survive")
  }

  // ── S2 上游 blocked → 不触发，走既有 blocked 链路 ───────

  test("S2: one upstream BLOCKED → merge node NOT triggered (stays pending), escalation visible, nothing merged") {
    val ws = tempRoot / "ws-s2"
    initRepo(ws)
    mkWork(ws, "task-a")
    mkWork(ws, "task-b")
    val system = ActorSystem(s"merge-s2-${scala.util.Random.nextInt(100000)}")
    val llm = MergeLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- allowBash(res)
      recorded <- registerRecorder(res, system, "nebula-root")
      // escalate-only：blocked 直接升级 Nebula（可观测），不自动重派（重派语义
      // 归 NodeBlockedReentrySpec；本断言焦点 = 合并节点不被触发）
      rt <- mountProject("merge-s2", ws, system, res, feedbackMode = FeedbackRouter.ModeEscalateOnly)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("merge-s2", "merge-y", "agent" -> CJson.fromString("merge-agent"),
        "task" -> CJson.fromString(mergeTask("task-a", "task-b")),
        "out" -> CJson.fromString("Nebula"), "merge" -> CJson.fromBoolean(true)), ctx)
      mergeYId <- idOf(rt, "merge-y")
      // A 将自报 BLOCKED；先建 A 并等 blocked，再建 B（确定性接线）
      _ <- nodeEdit(nodeInput("merge-s2", "task-a", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("blocked-please"), "out" -> CJson.fromString(mergeYId)), ctx)
      _ <- waitStatus(rt, "task-a", Set(NodeLifecycle.Blocked))
      _ <- nodeEdit(nodeInput("merge-s2", "task-b", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("produce artifact b"), "out" -> CJson.fromString(mergeYId)), ctx)
      _ <- waitStatus(rt, "task-b", Set(NodeLifecycle.Completed))
      _ <- IO.sleep(500.millis) // 若（不该发生的）触发，给窗口显形
      merge <- byName(rt, "merge-y")
      a <- byName(rt, "task-a")
      b <- byName(rt, "task-b")
      imms <- recordedImmediate(recorded)
      log = git(ws, "log", "--oneline", "main")
      branches = branchNames(ws)
      wts = worktreeNames(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Blocked, "upstream A must be blocked")
      assertEquals(b.status, NodeLifecycle.Completed)
      // 不触发：barrier 残缺（A 不结算）→ 合并节点保持 pending（创建态，未启动）
      assertEquals(merge.status, NodeLifecycle.Pending,
        s"merge node must stay un-triggered, got ${merge.status}")
      assertEquals(merge.deliveredTo, List(b.id),
        s"only the completed upstream may have settled, got ${merge.deliveredTo}")
      assert(merge.in.size == 2, "merge node must carry both in edges")
      // blocked 链路在走：升级通知可见（escalate-only 档）
      assert(imms.exists(_.eventType.contains("blocked")),
        s"blocked escalation must be visible, got ${imms.map(_.eventType)}")
      // 零落地：无合并提交、worktree/分支残留原样保留（待重入处置）
      assert(!log.contains("merge: task-a") && !log.contains("merge: task-b"), s"nothing may merge, got:\n$log")
      assert(branches.contains("feat/task-a") && branches.contains("feat/task-b"), "branches intact")
      assert(wts.exists(_.endsWith("task-a")) && wts.exists(_.endsWith("task-b")), "worktrees intact")
  }

  // ── S3 上游 failed → 不触发且不悬挂（转 blocked 可见终态）──

  test("S3: one upstream FAILED → merge node converts to blocked (visible, not hanging), nothing merged, Nebula notified") {
    val ws = tempRoot / "ws-s3"
    initRepo(ws)
    mkWork(ws, "task-a")
    mkWork(ws, "task-b")
    val system = ActorSystem(s"merge-s3-${scala.util.Random.nextInt(100000)}")
    val llm = MergeLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- allowBash(res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("merge-s3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("merge-s3", "merge-z", "agent" -> CJson.fromString("merge-agent"),
        "task" -> CJson.fromString(mergeTask("task-a", "task-b")),
        "out" -> CJson.fromString("Nebula"), "merge" -> CJson.fromBoolean(true)), ctx)
      mergeZId <- idOf(rt, "merge-z")
      // A 将注入失败（boom-please → fail fast → failed）；等 conversion 落定
      _ <- nodeEdit(nodeInput("merge-s3", "task-a", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("boom-please"), "out" -> CJson.fromString(mergeZId)), ctx)
      _ <- waitStatus(rt, "task-a", Set(NodeLifecycle.Failed))
      _ <- waitStatus(rt, "merge-z", Set(NodeLifecycle.Blocked))
      _ <- nodeEdit(nodeInput("merge-s3", "task-b", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("produce artifact b"), "out" -> CJson.fromString(mergeZId)), ctx)
      _ <- waitStatus(rt, "task-b", Set(NodeLifecycle.Completed))
      _ <- IO.sleep(300.millis)
      merge <- byName(rt, "merge-z")
      a <- byName(rt, "task-a")
      b <- byName(rt, "task-b")
      imms <- recordedImmediate(recorded)
      log = git(ws, "log", "--oneline", "main")
      branches = branchNames(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Failed, "upstream A must be failed")
      assertEquals(b.status, NodeLifecycle.Completed)
      // 不触发且不悬挂：合并节点 blocked 可见终态（非 pending/wiring 悬挂、未启动）
      assertEquals(merge.status, NodeLifecycle.Blocked, "merge node must be VISIBLY blocked, not hanging")
      assert(merge.blockedFeedback.isDefined, "structured feedback persisted")
      assertEquals(merge.blockedFeedback.map(_.category), Some("upstream-incomplete"))
      assert(merge.blockedFeedback.exists(_.detail.contains("task-a")),
        s"feedback must name the failed upstream, got ${merge.blockedFeedback.map(_.detail)}")
      assertEquals(merge.blockCount, 0, "engine-converted blocked must NOT count an agent-reported round")
      assertEquals(merge.ttlExpireAt, None, "blocked never expires (todo semantics)")
      // 已完成上游 B 的结算保留；A 的槽位不占位（collect 被旁路）
      assert(merge.deliveredTo.contains(b.id), s"completed upstream stays settled, got ${merge.deliveredTo}")
      assert(!merge.deliveredTo.contains(a.id), "failed upstream must NOT placeholder-settle")
      // Nebula 通报（merge-blocked 通知，eventType=blocked）
      assert(imms.exists(m => m.eventType.contains("blocked") && m.text.contains("merge-z")),
        s"Nebula must be notified of the merge-blocked, got ${imms.map(m => (m.eventType, m.text.take(60)))}")
      // 零落地：分支/worktree 残留原样（待分发器处置失败上游后重激活）
      assert(!log.contains("merge: task-a") && !log.contains("merge: task-b"), s"nothing may merge, got:\n$log")
      assert(branches.contains("feat/task-a") && branches.contains("feat/task-b"), "branches intact")
  }

  // ── S4 上游 held → release 按 completed 计入（NodeHoldSpec 衔接）──

  test("S4: held upstream releases → counts as completed, merge triggers and lands for real") {
    val ws = tempRoot / "ws-s4"
    initRepo(ws)
    mkWork(ws, "task-a")
    mkWork(ws, "task-b")
    val system = ActorSystem(s"merge-s4-${scala.util.Random.nextInt(100000)}")
    val llm = MergeLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- allowBash(res)
      rt <- mountProject("merge-s4", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("merge-s4", "merge-w", "agent" -> CJson.fromString("merge-agent"),
        "task" -> CJson.fromString(mergeTask("task-a", "task-b")),
        "out" -> CJson.fromString("Nebula"), "merge" -> CJson.fromBoolean(true)), ctx)
      mergeWId <- idOf(rt, "merge-w")
      // A：hold=true 人工闸点（先建并等 held，确定性先于 B 接线）
      _ <- nodeEdit(nodeInput("merge-s4", "task-a", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("produce artifact a"), "out" -> CJson.fromString(mergeWId),
        "hold" -> CJson.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "task-a", Set(NodeLifecycle.Held))
      _ <- nodeEdit(nodeInput("merge-s4", "task-b", "agent" -> CJson.fromString("test-agent"),
        "task" -> CJson.fromString("produce artifact b"), "out" -> CJson.fromString(mergeWId)), ctx)
      _ <- waitStatus(rt, "task-b", Set(NodeLifecycle.Completed))
      _ <- IO.sleep(300.millis)
      preMerge <- byName(rt, "merge-w")
      // release → held → completed → deliverOut → barrier 归零 → 合并真实落地
      rel <- nodeEdit(nodeInput("merge-s4", "task-a", "release" -> CJson.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "task-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "merge-w", Set(NodeLifecycle.Completed), timeout = 60.seconds)
      merge <- byName(rt, "merge-w")
      a <- byName(rt, "task-a")
      mergeInput <- llm.inputs.get.map(_.find(t => t.contains("=== Node task-a ===") && t.contains("=== Node task-b ===")))
      log = git(ws, "log", "--oneline", "main")
      branches = branchNames(ws)
      wts = worktreeNames(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rel.isRight, s"release must succeed, got $rel")
      assertEquals(preMerge.status, NodeLifecycle.Pending,
        s"barrier must hold while A is held (merge node stays at create-state pending), got ${preMerge.status}")
      assertEquals(a.status, NodeLifecycle.Completed, "release: held → completed")
      assertEquals(merge.status, NodeLifecycle.Completed, "released upstream counts in; merge ran")
      assert(mergeInput.isDefined, "merge input must carry BOTH results (incl. the released one)")
      assert(log.contains("merge: task-a") && log.contains("merge: task-b"), s"real landing happened, got:\n$log")
      assert(!branches.contains("feat/task-a") && !branches.contains("feat/task-b"), "branches cleaned")
      assert(!wts.exists(p => p.endsWith("task-a") || p.endsWith("task-b")), "worktrees cleaned")
  }

end NodeMergeSpec
