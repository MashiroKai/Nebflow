package nebflow.core.delegate

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.delegate.WorkspaceLedger.Workspace
import nebflow.core.project.{DelegateFlowHosts, DelegateFlowRuntime, NodeDef, NodeLifecycle, ProjectStore}
import nebflow.core.tools.{BgTaskRegistry, FileLockManager}
import nebflow.llm.ModelCandidate
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * WorkspaceOccupancy 验收（node-feedback delivery · Priority A）：
 *   - **聚合判据单点**：账本 `activity=idle`（dispatcher 已 settle 待命）但其作用域
 *     图仍有非终态节点、或其会话仍挂着活动后台任务 ⇒ 工作区**仍忙**；
 *   - **整批拒绝**：批量预定里任何一个工作区忙或态势不可读 ⇒ 整批拒绝（fail-closed），
 *     逐工作区给出原因，且**不落任何账**（拒绝不登记等待）；
 *   - 注入面：两个底层读数器均可注入合成读数（本 spec 的纯判定组用例）。
 */
class WorkspaceOccupancySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-workspace-occupancy"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.makeDir.all(tempRoot / "sessions")
  os.makeDir.all(tempRoot / "delegates")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    // Per-test ledger/project isolation: the workspace ledger and merger rows live
    // under the data root; leftovers from the previous test would make
    // ledger.merger see a repository change for the same project name.
    (DelegateFlowHosts.clear *> cats.effect.IO {
      nebflow.ForceRemove.removeAll(tempRoot / "delegates")
      nebflow.ForceRemove.removeAll(tempRoot / "workspaces")
      nebflow.ForceRemove.removeAll(tempRoot / "projects")
    }).unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateFlowHosts.clear.unsafeRunSync()

  // ── 注入面：合成读数的纯判定组 ─────────────────────────────────────────

  private def busyNodes(owner: String): IO[DelegateInflightGate.Reading] =
    IO.pure(
      DelegateInflightGate.Reading(List(DelegateInflightGate.Inflight("node-1", "build-and-verify", "running")), readable = true)
    )

  private def idleNodes(owner: String): IO[DelegateInflightGate.Reading] =
    IO.pure(DelegateInflightGate.Reading(Nil, readable = true))

  private def unreadableNodes(owner: String): IO[DelegateInflightGate.Reading] =
    IO.pure(DelegateInflightGate.Reading(Nil, readable = false))

  private def syntheticJobs(jobDescription: String): String => IO[List[BgTaskRegistry.ActiveTask]] = owner =>
    IO.pure(
      List(
        BgTaskRegistry.ActiveTask(
          jobId = "job-1",
          sessionId = owner,
          description = jobDescription,
          startedAtMs = 0L,
          kind = "local"
        )
      )
    )

  test("aggregate: a running scope node or a live background job makes the owner busy; an empty graph is idle") {
    val fromNodes = new WorkspaceOccupancy(busyNodes, _ => IO.pure(Nil))
    val fromJobs = new WorkspaceOccupancy(idleNodes, syntheticJobs("sbt test (background)"))
    val idle = new WorkspaceOccupancy(idleNodes, _ => IO.pure(Nil))
    for
      nodeReasons <- fromNodes.occupancyOf("owner-a")
      jobReasons <- fromJobs.occupancyOf("owner-a")
      idleReasons <- idle.occupancyOf("owner-a")
    yield
      assert(nodeReasons.exists(_.exists(_.contains("node-1"))), s"the running node must be a busy reason: $nodeReasons")
      assert(nodeReasons.exists(_.exists(_.contains("build-and-verify"))), s"the node name must be listed: $nodeReasons")
      assert(jobReasons.exists(_.nonEmpty), s"a live background job must be a busy reason: $jobReasons")
      assertEquals(idleReasons, Right(Nil), "an owner with no nodes and no jobs is idle")
    end for
  }

  test("fail-closed: an unreadable node reading or a failing job reader yields Left, never a silent pass") {
    val unreadableGraph = new WorkspaceOccupancy(unreadableNodes, _ => IO.pure(Nil))
    val failingJobs = new WorkspaceOccupancy(idleNodes, _ => IO.raiseError(new RuntimeException("registry exploded")))
    for
      fromGraph <- unreadableGraph.occupancyOf("owner-a")
      fromJobs <- failingJobs.occupancyOf("owner-a")
    yield
      assert(fromGraph.isLeft, s"readable=false must be fail-closed: $fromGraph")
      assert(fromJobs.isLeft, s"a failing reader must be fail-closed: $fromJobs")
      fromGraph.foreach(reasons => fail(s"Left expected, got reasons: $reasons"))
    end for
  }

  test("checkBatch: any busy or unreadable workspace fails the WHOLE batch with per-workspace reasons") {
    val mixed = new WorkspaceOccupancy(
      owner => if owner == "owner-a" then busyNodes(owner) else idleNodes(owner),
      _ => IO.pure(Nil)
    )
    val uncertain = new WorkspaceOccupancy(
      owner => if owner == "owner-b" then unreadableNodes(owner) else idleNodes(owner),
      _ => IO.pure(Nil)
    )
    val batch = List("ws-a" -> "owner-a", "ws-b" -> "owner-b")
    for
      busyBatch <- mixed.checkBatch(batch)
      uncertainBatch <- uncertain.checkBatch(batch)
    yield
      assert(busyBatch.exists(_.nonEmpty), s"a busy member must reject the whole batch: $busyBatch")
      busyBatch.foreach(reasons =>
        assert(reasons.exists(_.contains("ws-a")), s"the busy workspace must be named: $reasons")
        assert(reasons.exists(_.contains("node-1")), s"the reason must name the in-flight node: $reasons")
        assert(!reasons.exists(_.contains("ws-b")), s"idle members contribute no reason: $reasons")
      )
      assert(uncertainBatch.isLeft, s"an unreadable member must fail the whole batch (fail-closed): $uncertainBatch")
      uncertainBatch.foreach(reasons => fail(s"Left expected, got: $reasons"))
    end for
  }

  test("busyOwners: owners with non-empty reasons are busy; any unreadable owner fails the whole set") {
    val mixed = new WorkspaceOccupancy(
      owner => if owner == "owner-a" then busyNodes(owner) else idleNodes(owner),
      _ => IO.pure(Nil)
    )
    val uncertain = new WorkspaceOccupancy(_ => unreadableNodes("owner-b"), _ => IO.pure(Nil))
    for
      busy <- mixed.busyOwners(List("owner-a", "owner-b"))
      refused <- uncertain.busyOwners(List("owner-a", "owner-b"))
    yield
      assertEquals(busy, Right(Set("owner-a")), "only the busy owner is reported")
      assert(refused.isLeft, s"an unreadable owner fails the whole set: $refused")
    end for
  }

  // ── 端到端：MergeRequests.submit 的整批拒绝（判据 = 真实读数器）─────────

  private def mkResources(system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- nebflow.core.RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, nebflow.shared.ThinkingConfig](nebflow.shared.ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = null,
      dispatcher = dispatcher,
      sessionStore = nebflow.core.SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  /** Git repo + worktree fixture; registers the workspace idle + the merger (ledger + registry). */
  private def fixture(prefix: String): (os.Path, os.Path, String, String) =
    val root = os.temp.dir(prefix = s"occupancy-$prefix-")
    val repo = root / "repo"
    val tree = root / "task"
    os.makeDir.all(repo)
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main")
    git("config", "user.name", "test")
    git("config", "user.email", "test@example.invalid")
    git("commit", "--allow-empty", "-m", "base")
    val base = git("rev-parse", "HEAD")
    git("worktree", "add", "-b", "delegate/task", tree.toString, "main")
    os.write(tree / "result.txt", "committed result")
    os.proc("git", "add", "result.txt").call(cwd = tree)
    os.proc("git", "commit", "-m", "result").call(cwd = tree, stdout = os.Pipe)
    val owner = s"delegate-worker-$prefix"
    val mergerId = s"delegate-merger-$prefix"
    val ledger = WorkspaceLedger.default
    ledger.register(
      Workspace(
        "task",
        "example",
        WorkspaceLedger.repositoryKey(repo),
        tree.toNIO.toRealPath().toString,
        "delegate/task",
        "main",
        base,
        owner,
        "dispatcher"
      )
    )
    ledger.merger("example", WorkspaceLedger.repositoryKey(repo), mergerId)
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = mergerId,
        executor = "default",
        cwd = repo.toString,
        project = Some("example"),
        taskId = None,
        parentSessionId = "root",
        title = "project merger",
        createdAt = System.currentTimeMillis(),
        mode = Some("merger")
      )
    )
    (repo, tree, owner, mergerId)

  private def mountHost(ws: os.Path, owner: String, system: ActorSystem, res: SharedResources): IO[DelegateFlowRuntime] =
    DelegateFlowHosts
      .mount(
        delegateId = owner,
        project = None,
        baseWorkspace = ws.toString,
        engineCwd = ws.toString,
        system = system,
        resources = res,
        wsSend = None,
        reportGateHold = Some(false),
        rootNotifyQuietMs = Some(0),
        destroyWindowMs = Some(0L)
      )
      .map(_.getOrElse(fail("host must mount")))

  private def seedNode(h: DelegateFlowRuntime, id: String, name: String, status: String): IO[Unit] =
    h.store
      .mutate(s =>
        s.copy(nodes =
          s.nodes.updated(
            id,
            NodeDef(
              id = id,
              name = name,
              agent = "general",
              task = Some(s"seeded $id"),
              status = status,
              chainId = None,
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      .void

  test("dispatcher standby but a Node still running: submit rejects the WHOLE batch naming the node and writes nothing") {
    val system = ActorSystem(s"occupancy-node-${scala.util.Random.nextInt(1000000)}")
    val (repo, _, owner, mergerId) = fixture("node")
    for
      resources <- mkResources(system)
      _ <- ProjectStore.create("example", repo.toString, None, "# Project instructions")
      h <- mountHost(repo, owner, system, resources)
      _ <- seedNode(h, "gate", "batch-gate", NodeLifecycle.Running)
      rejected <- MergeRequests.submit("example", "req-node", List("task"), "user authorized", resources)
      state = WorkspaceLedger.default.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val error = rejected.left.getOrElse(fail(s"a running node must reject the request, got: $rejected"))
      assert(error.contains("gate"), s"the refusal must name the in-flight node: $error")
      assert(error.contains("batch-gate"), s"the refusal must name the node: $error")
      assert(error.contains("busy"), s"the refusal must say why: $error")
      // 拒绝不登记等待：零请求落账、工作区保持 open/idle 可继续开发。
      assertEquals(state.requests, Nil, "a rejected request must not be registered")
      val ws = state.workspaces.find(_.id == "task").get
      assertEquals(ws.lifecycle, "open", "the workspace must stay open")
      assertEquals(ws.activity, "idle", "the workspace must stay idle (the dispatcher settled)")
      assertEquals(mergerId, state.mergers.head.delegateId, "fixture sanity")
    end for
  }

  test("dispatcher standby but a live background job exists: submit rejects the batch naming the job") {
    val system = ActorSystem(s"occupancy-job-${scala.util.Random.nextInt(1000000)}")
    val (repo, _, owner, _) = fixture("job")
    val jobId = s"occupancy-job-${scala.util.Random.nextInt(100000)}"
    for
      resources <- mkResources(system)
      _ <- ProjectStore.create("example", repo.toString, None, "# Project instructions")
      _ <- BgTaskRegistry.register(
        jobId,
        owner,
        "sbt test (writing the workspace)",
        "local",
        rootSessionId = owner
      )
      rejected <- MergeRequests.submit("example", "req-job", List("task"), "user authorized", resources)
      state = WorkspaceLedger.default.snapshot
      _ <- BgTaskRegistry.unregister(jobId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val error = rejected.left.getOrElse(fail(s"a live background job must reject the request, got: $rejected"))
      assert(error.contains("background job"), s"the refusal must name the job kind: $error")
      assert(error.contains(jobId), s"the refusal must name the job id: $error")
      assertEquals(state.requests, Nil, "a rejected request must not be registered")
    end for
  }

  test("idle owner: the same submit passes the occupancy gate and the request is accepted (behavior preserved)") {
    val system = ActorSystem(s"occupancy-idle-${scala.util.Random.nextInt(1000000)}")
    val (repo, _, _, _) = fixture("idle")
    for
      resources <- mkResources(system)
      _ <- ProjectStore.create("example", repo.toString, None, "# Project instructions")
      accepted <- MergeRequests.submit("example", "req-idle", List("task"), "user authorized", resources)
      state = WorkspaceLedger.default.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(accepted.isRight, s"an idle owner must pass the gate, got: $accepted")
      assertEquals(state.requests.map(_.id), List("req-idle"), "the accepted request is recorded")
      assertEquals(state.workspaces.find(_.id == "task").get.lifecycle, "reserved", "the workspace is reserved")
    end for
  }

  test("cleanup busy set is derived from occupancy: a live background job blocks the cleanup, release unblocks it") {
    val system = ActorSystem(s"occupancy-cleanup-${scala.util.Random.nextInt(1000000)}")
    val (repo, tree, owner, _) = fixture("cleanup")
    val jobId = s"occupancy-cleanup-job-${scala.util.Random.nextInt(100000)}"
    for
      resources <- mkResources(system)
      _ <- ProjectStore.create("example", repo.toString, None, "# Project instructions")
      _ <- BgTaskRegistry.register(jobId, owner, "long build still running", "local")
      refused <- nebflow.core.delegate.ProjectWorkspaceCleanup.run("example", "task", resources, None)
      _ <- BgTaskRegistry.unregister(jobId)
      allowed <- nebflow.core.delegate.ProjectWorkspaceCleanup.run("example", "task", resources, None)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val error = refused.left.getOrElse(fail(s"a live background job must block cleanup, got: $refused"))
      assert(error.contains("still executing") || error.contains("busy"), s"the refusal must say why: $error")
      assert(allowed.isRight, s"after the job ends the cleanup must proceed, got: $allowed")
      assert(os.exists(tree), "a preview-only cleanup does not remove the workspace")
    end for
  }

end WorkspaceOccupancySpec
