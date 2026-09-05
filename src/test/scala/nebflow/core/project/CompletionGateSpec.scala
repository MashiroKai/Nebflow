package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 产物完整性闸门回归（CompletionGate，worktree 审计 20260905 机制建议落地）。
 *
 * 覆盖（作者验收口径 a/b/c 三合法态 + 滞留转 blocked + 变异验红）：
 *  - 单元级：态 a（ahead≥1+干净）/ 态 b（脏+commit-ready 标记，含「宿主落地命令」
 *    不作为充分标记的负控）/ 态 c（零改动）/ 滞留 Reject 诊断 / fail-open（runner
 *    抛错与 git 失败）/ 跳过面（无 worktree / 目录缺失 / 损坏值回退 workspace）/
 *    kill-switch / feedback 文案（产物滞留未申报+诊断四要素）；
 *  - 真 git 集成（best effort，git 可用时）：临时 git init fixture 走 defaultRunner；
 *  - 引擎级（stub runner 注入，NodeBlockedReentrySpec 同款 harness）：①态 a →
 *    completed 正常投递 ②态 b → completed ③④滞留 → blocked + blockedFeedback
 *    「产物滞留未申报」+ 不投递 out（④即变异验红用例）⑤blocked 优先于 gate
 *    ⑥hold 优先于 gate ⑦release 闸门（拒 → 保持 held + Left 诊断；清账后放行）
 *    ⑧引擎级 kill-switch。
 */
class CompletionGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-completion-gate"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("test-agent") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"completion gate regression agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // ── harness（NodeBlockedReentrySpec 同款，mountEngineOnly 增 gateRunner 注入）──

  /** stub runner 状态：status 行集 / ahead 数 / 失败注入。 */
  private final case class StubGit(
    status: List[String] = Nil,
    ahead: Int = 0,
    aheadFail: Boolean = false,
    statusFail: Boolean = false
  )

  private def stubRunner(state: Ref[IO, StubGit]): CompletionGate.GitRunner = (_, args) =>
    args match
      case List("status", "--porcelain") =>
        state.get.map(s => if s.statusFail then Left("stub status failure (exit=128)") else Right(s.status.mkString("\n")))
      case List("rev-list", "--count", "main..HEAD") =>
        state.get.map(s => if s.aheadFail then Left("stub rev-list failure") else Right(s.ahead.toString))
      case other => IO.pure(Left(s"stub unexpected args: $other"))

  /** 建 worktree 目录实存（gate 的 exists 检查 + NodeEdit worktree 校验都要求）。 */
  private def mkWt(ws: os.Path, bare: String): os.Path =
    val dir = ws / ".nebflow" / "worktrees" / bare
    os.makeDir.all(dir)
    dir

  private class FuncLlm(respond: String => IO[String]):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream
          .eval(inputs.update(_ :+ text))
          .flatMap(_ => Stream.eval(respond(text)))
          .flatMap(reply => Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None)))

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
      voiceMutedRef = voiceMuted
    )

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("gate-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
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

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def mountEngineOnly(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    runner: CompletionGate.GitRunner
  ): IO[(ProjectRuntime, Ref[IO, List[(String, String, Json)]])] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      events <- Ref.of[IO, List[(String, String, Json)]](Nil)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (t, id, payload) => events.update((t, id, payload) :: _),
        gateRunner = runner
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield (rt, events)

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None    => fail(s"node '$name' must exist")
    }

  private def nodeById(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.get(id)).map(_.getOrElse(fail(s"node '$id' must exist")))

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(20.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  private def readAuditTypes(ws: os.Path): IO[List[(String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => jsonParse(l).toOption.map(j =>
        (j.hcursor.get[String]("type").getOrElse(""), j.hcursor.get[String]("nodeId").getOrElse("")))))
      .handleError(_ => Nil)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 单元级：三合法态 + 滞留 Reject ──────────────────────────────────

  test("gate 态a: ahead≥1 + clean → Pass(committed)") {
    val ws = tempRoot / "unit-a"
    os.makeDir.all(mkWt(ws, "wt"))
    for
      state <- Ref.of[IO, StubGit](StubGit(status = Nil, ahead = 3))
      verdict <- CompletionGate.check(ws.toString, Some("wt"), "ok", stubRunner(state))
    yield
      val p = verdict.asInstanceOf[CompletionGate.Pass]
      assert(p.reason.contains("committed") && p.reason.contains("3"), s"reason must mark committed+ahead, got: ${p.reason}")
  }

  test("gate 态b: dirty + commit-ready 标记变体 → Pass；「宿主落地命令」单独出现不构成申报（负控）") {
    val ws = tempRoot / "unit-b"
    os.makeDir.all(mkWt(ws, "wt"))
    val dirty = List("M src/Main.scala", "?? notes/new-spec.md")
    for
      state <- Ref.of[IO, StubGit](StubGit(status = dirty, ahead = 0))
      // 标记契约变体：- / 空格 / _ / 大小写 / 无分隔
      r1 <- CompletionGate.check(ws.toString, Some("wt"), "沙箱 EPERM；产出 commit-ready 待宿主落地", stubRunner(state))
      r2 <- CompletionGate.check(ws.toString, Some("wt"), "Report is Commit Ready for landing", stubRunner(state))
      r3 <- CompletionGate.check(ws.toString, Some("wt"), "全部 commit_ready 申报", stubRunner(state))
      r4 <- CompletionGate.check(ws.toString, Some("wt"), "output COMMITREADY", stubRunner(state))
      // 负控：只含「宿主落地命令」不含 commit-ready → 必须拒（已提交批报告归档段
      // 常含该词，仅凭它会误放行滞留节点——作者规格明确裁决）
      r5 <- CompletionGate.check(ws.toString, Some("wt"), "产出已就绪，待宿主落地命令执行", stubRunner(state))
    yield
      assert(r1.isInstanceOf[CompletionGate.Pass], s"commit-ready must pass, got: $r1")
      assert(r2.isInstanceOf[CompletionGate.Pass], s"'Commit Ready' variant must pass, got: $r2")
      assert(r3.isInstanceOf[CompletionGate.Pass], s"'commit_ready' variant must pass, got: $r3")
      assert(r4.isInstanceOf[CompletionGate.Pass], s"'COMMITREADY' variant must pass, got: $r4")
      assert(r5.isInstanceOf[CompletionGate.Reject], s"宿主落地命令 alone must NOT pass, got: $r5")
  }

  test("gate 态c: ahead=0 + clean → Pass(zero-change)；rev-list 失败按 0 仍 Pass") {
    val ws = tempRoot / "unit-c"
    os.makeDir.all(mkWt(ws, "wt"))
    for
      state <- Ref.of[IO, StubGit](StubGit(status = Nil, ahead = 0))
      r1 <- CompletionGate.check(ws.toString, Some("wt"), "调查结论：只读无产出", stubRunner(state))
      _ <- state.update(_.copy(aheadFail = true))
      r2 <- CompletionGate.check(ws.toString, Some("wt"), "只读无产出", stubRunner(state))
    yield
      val p1 = r1.asInstanceOf[CompletionGate.Pass]
      assert(p1.reason.contains("zero-change"), s"reason must mark zero-change, got: ${p1.reason}")
      assert(r2.isInstanceOf[CompletionGate.Pass], s"rev-list failure → 0 → zero-change pass, got: $r2")
  }

  test("gate 滞留: dirty + 未申报 → Reject，诊断含 ahead/脏数/status 样本/markerHit=false；feedback 文案含产物滞留未申报") {
    val ws = tempRoot / "unit-reject"
    os.makeDir.all(mkWt(ws, "wt"))
    val dirty = (1 to 12).map(i => s"?? untracked-$i.md").toList
    for
      state <- Ref.of[IO, StubGit](StubGit(status = dirty, ahead = 2))
      r <- CompletionGate.check(ws.toString, Some("wt"), "做完了（无任何申报字样）", stubRunner(state))
    yield
      val rej = r.asInstanceOf[CompletionGate.Reject]
      val d = rej.diagnostic
      assertEquals(d.aheadCount, 2)
      assertEquals(d.dirtyCount, 12)
      assertEquals(d.markerHit, false)
      assertEquals(d.statusSample.size, 10, "status 样本最多前 10 行")
      assert(d.statusSample.head.contains("untracked-1"), s"sample carries porcelain lines, got: ${d.statusSample.head}")
      val fb = CompletionGate.feedback(d)
      assertEquals(fb.category, "artifact-residue")
      assert(fb.detail.contains("产物滞留未申报"), s"detail must carry 滞留标注, got: ${fb.detail}")
      assert(fb.detail.contains("领先 main 2 commit"), s"detail must carry ahead, got: ${fb.detail}")
      assert(fb.detail.contains("12 个未提交改动"), s"detail must carry dirty count, got: ${fb.detail}")
      assert(fb.detail.contains("markerHit=false"), s"detail must carry marker detection, got: ${fb.detail}")
      assert(fb.suggestion.contains("commit-ready"), s"suggestion must teach the marker contract, got: ${fb.suggestion}")
  }

  test("gate fail-open: runner 抛错 → 放行；git status 非零 → 放行") {
    val ws = tempRoot / "unit-failopen"
    os.makeDir.all(mkWt(ws, "wt"))
    val raising: CompletionGate.GitRunner = (_, _) => IO.raiseError(new RuntimeException("git exploded"))
    val failing: CompletionGate.GitRunner = (_, args) =>
      if args == List("status", "--porcelain") then IO.pure(Left("fatal: not a git repository (exit=128)"))
      else IO.pure(Right("0"))
    for
      r1 <- CompletionGate.check(ws.toString, Some("wt"), "no marker", raising)
      r2 <- CompletionGate.check(ws.toString, Some("wt"), "no marker", failing)
    yield
      assert(r1.isInstanceOf[CompletionGate.Pass], s"runner raise must fail-open, got: $r1")
      assert(r1.asInstanceOf[CompletionGate.Pass].reason.contains("fail-open"), s"reason must mark fail-open, got: ${r1.asInstanceOf[CompletionGate.Pass].reason}")
      assert(r2.isInstanceOf[CompletionGate.Pass], s"git non-zero must fail-open, got: $r2")
  }

  test("gate 跳过面: 无 worktree / 目录缺失 / 损坏值回退 workspace → 全部 Pass 跳过") {
    val ws = tempRoot / "unit-skip"
    os.makeDir.all(ws)
    for
      r1 <- CompletionGate.check(ws.toString, None, "no marker", stubRunner(Ref.unsafe[IO, StubGit](StubGit())))
      // 目录缺失：resolveNodeProjectRoot 回退旧公式路径（不存在）→ skip
      r2 <- CompletionGate.check(ws.toString, Some("no-such-wt"), "no marker", stubRunner(Ref.unsafe[IO, StubGit](StubGit())))
      // 损坏存储值（绝对路径）：归一化拒绝 → 解析回退 workspace 本体 → skip（防误检主仓）
      r3 <- CompletionGate.check(ws.toString, Some("/elsewhere/evil"), "no marker", stubRunner(Ref.unsafe[IO, StubGit](StubGit())))
    yield
      assert(r1.isInstanceOf[CompletionGate.Pass], s"no worktree must skip, got: $r1")
      assert(r2.isInstanceOf[CompletionGate.Pass], s"missing dir must skip, got: $r2")
      assert(r3.isInstanceOf[CompletionGate.Pass], s"corrupt value must skip, got: $r3")
  }

  test("gate kill-switch: nebflow.completionGate.disabled=true → 脏且未申报照常放行") {
    val ws = tempRoot / "unit-kill"
    os.makeDir.all(mkWt(ws, "wt"))
    for
      state <- Ref.of[IO, StubGit](StubGit(status = List("M a.scala"), ahead = 0))
      _ <- IO(java.lang.System.setProperty("nebflow.completionGate.disabled", "true"))
      r <- CompletionGate.check(ws.toString, Some("wt"), "no marker", stubRunner(state))
      _ <- IO(java.lang.System.clearProperty("nebflow.completionGate.disabled"))
    yield
      assert(r.isInstanceOf[CompletionGate.Pass], s"kill-switch must bypass gate, got: $r")
      assert(r.asInstanceOf[CompletionGate.Pass].reason.contains("kill-switch"), s"reason must mark kill-switch, got: ${r.asInstanceOf[CompletionGate.Pass].reason}")
  }

  // ── 真 git 集成（best effort：git 不可用时跳过断言，仅验证不崩）────────

  test("gate 真git: 临时 repo fixture 走 defaultRunner——committed/dirty/标记/gitignore 全链") {
    val ws = tempRoot / "unit-realgit"
    val wt = mkWt(ws, "wt-real")
    val gitAvailable =
      try os.proc("git", "--version").call(check = false).exitCode == 0
      catch case _: Throwable => false
    if gitAvailable then
      def git(args: String*): Unit = os.proc("git", "-C", wt, args).call(check = true, stderr = os.Pipe)
      os.write(wt / "base.txt", "base\n")
      git("init")
      git("config", "user.email", "t@t.local")
      git("config", "user.name", "t")
      git("add", ".")
      git("commit", "-m", "base")
      val baseSha = os.proc("git", "-C", wt, "rev-parse", "HEAD").call(check = true).out.trim()
      git("checkout", "-b", "work")
      os.write(wt / "feature.txt", "feature\n")
      git("add", ".")
      git("commit", "-m", "feature")
      git("branch", "-f", "main", baseSha) // main 定在 base；work 领先 1
      val checkReal = CompletionGate.check(ws.toString, Some("wt-real"), _: String)
      for
        // rClean 时点：work 树干净（feature 已提交）、ahead(main..HEAD)=1
        rClean <- checkReal("no marker")
        _ <- IO(os.write(wt / "u.txt", "untracked\n"))
        rDirty <- checkReal("no marker")
        rMarked <- checkReal("产出 commit-ready（沙箱 EPERM）")
        _ <- IO {
          // .gitignore 内文件自然不可见：清掉 u.txt、提交 .gitignore 后 ignored.txt 不算脏
          os.remove(wt / "u.txt")
          os.write(wt / ".gitignore", "ignored.txt\n")
          git("add", ".gitignore")
          git("commit", "-m", "gitignore")
          os.write(wt / "ignored.txt", "x\n")
        }
        rIgnored <- checkReal("no marker")
      yield
        assert(rClean.asInstanceOf[CompletionGate.Pass].reason.contains("committed"), s"clean+ahead1 → committed, got: $rClean")
        val rej = rDirty.asInstanceOf[CompletionGate.Reject]
        assertEquals(rej.diagnostic.dirtyCount, 1, "untracked u.txt must count dirty")
        assert(rej.diagnostic.statusSample.exists(_.contains("u.txt")), s"sample must name the untracked file, got: ${rej.diagnostic.statusSample}")
        assert(rMarked.isInstanceOf[CompletionGate.Pass], s"dirty+marker → pass, got: $rMarked")
        assert(rIgnored.asInstanceOf[CompletionGate.Pass].reason.contains("committed"), s"gitignored file invisible → clean, got: $rIgnored")
    else
      IO(assert(true, "git unavailable — real-git integration skipped (best-effort 口径)"))
  }

  // ── 引擎级：completeNode 接线（stub runner 注入）────────────────────

  /** 引擎场景基建：B wiring 种子 + A（worktree+out→B）经 NodeEdit 创建并驱动。
    * 返回 (runtime, WS 事件, actor system——收尾 stopAll 用)。 */
  private def engineScenario(
    name: String,
    llm: FuncLlm,
    runner: CompletionGate.GitRunner
  ): IO[(ProjectRuntime, Ref[IO, List[(String, String, Json)]], ActorSystem)] =
    val ws = tempRoot / s"ws-$name"
    os.makeDir.all(ws)
    mkWt(ws, "wt-gate")
    val system = ActorSystem(s"gate-$name-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly(name, ws, system, res, runner)
      ctx = mkCtx(res, system, ws.toString)
      // B（wiring，无 task）种子——A 的 out 投递断言点（NodeBlockedReentrySpec 同款）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-down-b" -> NodeDef(id = "n-down-b", name = "down-b", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      _ <- nodeEdit(nodeInput(name, "gate-a", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("gate-scenario"), "worktree" -> Json.fromString("wt-gate"),
        "out" -> Json.fromString("n-down-b")), ctx)
    yield (rt, events, system)

  private def stop(system: ActorSystem): IO[Unit] = system.stopAll.handleErrorWith(_ => IO.unit)

  test("engine ①: 态a（ahead≥1+干净）→ completed + 正常投递下游启动") {
    val llm = new FuncLlm(text => if text.contains("gate-scenario") then IO.pure("done: committed everything") else IO.pure("ok-b"))
    for
      state <- Ref.of[IO, StubGit](StubGit(status = Nil, ahead = 2))
      (rt, events, system) <- engineScenario("eng-a", llm, stubRunner(state))
      _ <- waitStatus(rt, "gate-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "down-b", Set(NodeLifecycle.Running, NodeLifecycle.Completed))
      inputs <- llm.inputs.get
      aId <- idOf(rt, "gate-a")
      evs <- events.get
      _ <- stop(system)
    yield
      assert(inputs.exists(_.contains("=== Node gate-a ===")), s"downstream must receive delivered result, got: ${inputs.map(_.take(80))}")
      assert(evs.exists((t, id, p) => t == "nodeCompleted" && id == aId && p.hcursor.get[String]("status").toOption.contains(NodeLifecycle.Completed)),
        "completed must emit nodeCompleted")
  }

  test("engine ②: 态b（脏 + 结果申报 commit-ready）→ completed 正常投递") {
    val llm = new FuncLlm(text =>
      if text.contains("gate-scenario") then IO.pure("沙箱 EPERM 无法 git commit；产出已落 worktree，报告 commit-ready，待宿主落地命令。")
      else IO.pure("ok-b"))
    for
      state <- Ref.of[IO, StubGit](StubGit(status = List("M spec.md", "?? report.md"), ahead = 0))
      (rt, events, system) <- engineScenario("eng-b", llm, stubRunner(state))
      _ <- waitStatus(rt, "gate-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "down-b", Set(NodeLifecycle.Running, NodeLifecycle.Completed))
      inputs <- llm.inputs.get
      a <- rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).map(_.getOrElse(fail("A must exist")))
      _ <- stop(system)
    yield
      assertEquals(a.status, NodeLifecycle.Completed, "declared commit-ready must complete")
      assertEquals(a.blockedFeedback, None, "declared node must not carry gate feedback")
      assert(inputs.exists(_.contains("=== Node gate-a ===")), "declared node must still deliver")
  }

  test("engine ③④: 滞留（脏 + 未申报）→ blocked + blockedFeedback「产物滞留未申报」+ 不投递 out 目标（变异验红用例）") {
    val llm = new FuncLlm(text => if text.contains("gate-scenario") then IO.pure("ok-done") else IO.pure("ok-b"))
    for
      state <- Ref.of[IO, StubGit](StubGit(status = List("M EngineSpec.scala", "?? new-note.md"), ahead = 1))
      (rt, events, system) <- engineScenario("eng-reject", llm, stubRunner(state))
      _ <- waitUntil(20.seconds) {
        rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).flatMap {
          case Some(n) => IO.pure(n.status == NodeLifecycle.Held || n.status == NodeLifecycle.Blocked || n.status == NodeLifecycle.Completed)
          case None    => IO.pure(false)
        }
      }
      _ <- rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).map {
        case Some(n) => assertEquals(n.status, NodeLifecycle.Blocked,
          s"dirty undeclared must block, not complete (gate mutation red = here; feedback=${n.blockedFeedback}, result=${n.result.map(_.take(80))})")
        case None    => fail("A must exist")
      }
      _ <- IO.sleep(300.millis) // 给「假如误投递」留窗口
      aId <- idOf(rt, "gate-a")
      a <- nodeById(rt, aId)
      b <- rt.store.snapshot.map(_.nodes.get("n-down-b")).map(_.getOrElse(fail("B must exist")))
      inputs <- llm.inputs.get
      evs <- events.get
      audit <- readAuditTypes(tempRoot / "ws-eng-reject")
      _ <- stop(system)
    yield
      // 终态形态：blocked（复用 BLOCKED 反馈协议）
      assertEquals(a.status, NodeLifecycle.Blocked, "dirty undeclared must block, not complete")
      assertEquals(a.blockedFeedback.map(_.category), Some("artifact-residue"))
      assert(a.blockedFeedback.exists(_.detail.contains("产物滞留未申报")), s"feedback must carry 滞留标注, got: ${a.blockedFeedback}")
      assert(a.blockedFeedback.exists(_.detail.contains("2 个未提交改动")), s"feedback must carry dirty count, got: ${a.blockedFeedback}")
      assert(a.blockedFeedback.exists(_.detail.contains("领先 main 1 commit")), s"feedback must carry ahead, got: ${a.blockedFeedback}")
      assert(a.blockedFeedback.exists(_.suggestion.contains("commit-ready")), s"suggestion must teach marker contract, got: ${a.blockedFeedback}")
      assert(a.result.exists(_.startsWith("[blocked:artifact-residue]")), s"result is rendered feedback string, got: ${a.result}")
      // 不走 out 投递、结果不丢弃（作者规格）
      assertEquals(b.status, NodeLifecycle.Wiring, "downstream must NOT be settled by gate-blocked")
      assertEquals(b.deliveredTo, Nil, "no delivery to out target")
      assert(!inputs.exists(_.contains("=== Node gate-a ===")), "result string must never be delivered downstream")
      // WS：nodeUpdated（无新事件类型），无 nodeCompleted
      assert(!evs.exists((t, id, _) => t == "nodeCompleted" && id == aId), "gate-blocked must NOT emit nodeCompleted")
      assert(evs.exists((t, id, p) => t == "nodeUpdated" && id == aId && p.hcursor.get[String]("status").toOption.contains(NodeLifecycle.Blocked)),
        s"gate-blocked must emit nodeUpdated, got: ${evs.map((t, id, _) => (t, id))}")
      // 审计
      assert(audit.exists((t, id) => t == "blocked" && id == aId), s"blocked audit line must exist, got: $audit")
  }

  test("engine ⑤: 自报 BLOCKED 优先于 gate（脏 worktree + BLOCKED 输出 → agent 的 category，非 artifact-residue）") {
    val llm = new FuncLlm(text =>
      if text.contains("gate-scenario") then
        IO.pure("""BLOCKED: 无法继续
{"category":"task-underspecified","detail":"任务缺验收标准","suggestion":"补验收"}""")
      else IO.pure("ok-b"))
    for
      state <- Ref.of[IO, StubGit](StubGit(status = List("M x.scala"), ahead = 0))
      (rt, events, system) <- engineScenario("eng-blk-first", llm, stubRunner(state))
      _ <- waitStatus(rt, "gate-a", Set(NodeLifecycle.Blocked))
      aId <- idOf(rt, "gate-a")
      a <- nodeById(rt, aId)
      _ <- stop(system)
    yield
      assertEquals(a.blockedFeedback.map(_.category), Some("task-underspecified"),
        "agent-declared blocked must win over gate (gate 后置)")
  }

  test("engine ⑥: hold 优先于 gate（hold=true + 脏 + 未申报 → held，等 release 时再闸）") {
    val llm = new FuncLlm(text => if text.contains("gate-scenario") then IO.pure("held-result") else IO.pure("ok-b"))
    // hold 校验①：hold=true 必须 out 为节点 id（out=Nebula 被 NodeEdit 拒）→ 种子 B
    val ws = tempRoot / "ws-eng-hold"
    os.makeDir.all(ws)
    mkWt(ws, "wt-gate")
    val system = ActorSystem(s"gate-hold-${scala.util.Random.nextInt(100000)}")
    for
      state <- Ref.of[IO, StubGit](StubGit(status = List("M h.scala"), ahead = 0))
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("eng-hold", ws, system, res, stubRunner(state))
      ctx = mkCtx(res, system, ws.toString)
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-down-b" -> NodeDef(id = "n-down-b", name = "down-b", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      created <- nodeEdit(nodeInput("eng-hold", "gate-a", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("gate-scenario"), "worktree" -> Json.fromString("wt-gate"),
        "hold" -> Json.fromBoolean(true), "out" -> Json.fromString("n-down-b")), ctx)
      _ <- waitUntil(20.seconds) {
        rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).flatMap {
          case Some(n) => IO.pure(n.status == NodeLifecycle.Held || n.status == NodeLifecycle.Blocked || n.status == NodeLifecycle.Completed)
          case None    => IO.pure(false)
        }
      }
      a <- rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).map(_.getOrElse(fail("A must exist")))
      _ <- stop(system)
    yield
      assert(created.isRight, s"create must succeed, got: $created")
      assertEquals(a.status, NodeLifecycle.Held, s"hold split must precede gate; gate re-runs at release (hold=${a.hold}, feedback=${a.blockedFeedback}, result=${a.result.map(_.take(120))})")
      assertEquals(a.result, Some("held-result"))
  }

  test("engine ⑦: release 闸门——脏未申报 → Left 拒绝保持 held；stub 清账后重放 → completed") {
    val llm = new FuncLlm(text => if text.contains("gate-scenario") then IO.pure("held-result") else IO.pure("ok-b"))
    val ws = tempRoot / "ws-eng-release"
    os.makeDir.all(ws)
    mkWt(ws, "wt-gate")
    val system = ActorSystem(s"gate-rel-${scala.util.Random.nextInt(100000)}")
    for
      state <- Ref.of[IO, StubGit](StubGit(status = List("M r.scala"), ahead = 0))
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("eng-release", ws, system, res, stubRunner(state))
      ctx = mkCtx(res, system, ws.toString)
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-down-b" -> NodeDef(id = "n-down-b", name = "down-b", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      created <- nodeEdit(nodeInput("eng-release", "gate-a", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("gate-scenario"), "worktree" -> Json.fromString("wt-gate"),
        "hold" -> Json.fromBoolean(true), "out" -> Json.fromString("n-down-b")), ctx)
      _ <- waitUntil(20.seconds) {
        rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).flatMap {
          case Some(n) => IO.pure(n.status == NodeLifecycle.Held || n.status == NodeLifecycle.Blocked || n.status == NodeLifecycle.Completed)
          case None    => IO.pure(false)
        }
      }
      _ <- rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).map {
        case Some(n) => assertEquals(n.status, NodeLifecycle.Held, s"setup: node must be held (got ${n.status}, created=$created, feedback=${n.blockedFeedback})")
        case None    => fail("A must exist")
      }
      // 第一次 release：脏且未申报 → 拒绝，保持 held
      refused <- nodeEdit(nodeInput("eng-release", "gate-a", "release" -> Json.fromBoolean(true)), ctx)
      _ <- IO.sleep(200.millis)
      a1 <- rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).map(_.getOrElse(fail("A must exist")))
      // 落地产物（模拟 commit 完成：ahead=1 + clean）→ 重放成功
      _ <- state.set(StubGit(status = Nil, ahead = 1))
      ok <- nodeEdit(nodeInput("eng-release", "gate-a", "release" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "gate-a", Set(NodeLifecycle.Completed))
      a2 <- rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).map(_.getOrElse(fail("A must exist")))
      _ <- stop(system)
    yield
      assert(refused.isLeft, s"dirty undeclared release must be rejected, got: $refused")
      assert(refused.left.exists(m => m.contains("release rejected") && m.contains("产物滞留未申报")), s"rejection must carry gate diagnostic, got: $refused")
      assertEquals(a1.status, NodeLifecycle.Held, "node stays held after rejection")
      assert(ok.isRight, s"clean release must succeed, got: $ok")
      assertEquals(a2.status, NodeLifecycle.Completed, "release → completed after artifacts landed")
  }

  test("engine ⑧: kill-switch 生效于引擎（disabled → 脏未申报节点照常 completed）") {
    val llm = new FuncLlm(text => if text.contains("gate-scenario") then IO.pure("ok-done") else IO.pure("ok-b"))
    for
      state <- Ref.of[IO, StubGit](StubGit(status = List("M k.scala"), ahead = 0))
      _ <- IO(java.lang.System.setProperty("nebflow.completionGate.disabled", "true"))
      (rt, events, system) <- engineScenario("eng-kill", llm, stubRunner(state))
      _ <- waitStatus(rt, "gate-a", Set(NodeLifecycle.Completed))
      _ <- IO(java.lang.System.clearProperty("nebflow.completionGate.disabled"))
      a <- rt.store.snapshot.map(_.nodes.values.find(_.name == "gate-a")).map(_.getOrElse(fail("A must exist")))
      _ <- stop(system)
    yield
      assertEquals(a.status, NodeLifecycle.Completed, "kill-switch bypasses gate at engine level")
      assertEquals(a.blockedFeedback, None)
  }

end CompletionGateSpec
