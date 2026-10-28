package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentKind, AgentRecord, AgentCommand}
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.delegate.{ChainStore, DelegateRegistry, MergeQueue}
import nebflow.core.project.DelegateFlowHosts
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * A8 per-task 只读读面验收（N1 刀3 · 设计 v3 §3 A8 / §9；#1228 stub §四.3/.4 的唯一
 * 硬依赖落位）。HTTP 臂在 PresenceRoutes（`/api/chains*`），本 spec 直驱投影单点：
 *
 *  - C1 行投影：Record 原样键 + `live` 活性提示（agentRegistry 快照在册 = running）
 *    + 最新 `terminal-verdict` 判词（ready-to-merge 供给源——v3 §9 逐字，前端既有
 *    rowFromRegistry 推导不抢）；无在册会话/无判词 = 对应键缺席。
 *  - C2 链投影：已挂载宿主现读（mounted=true + 节点经 v3 §9 映射表 + ChainNode 形）；
 *    未挂载但盘面作用域在 = 探针只读（mounted=false，不落 hosts）；作用域目录不存在
 *    （外部执行器形态）= chain=null 降级；三行族 record 原样透传（适配层直接输入）。
 *  - C3 查无/非法键 = None（HTTP 404/400 面）；读面零写盘（探针不建目录）。
 */
class ChainsReadFaceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chains-read-face"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()
    MergeQueue.reset()
    if os.exists(tempRoot / "delegates") then nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    if os.exists(tempRoot / "chains") then nebflow.ForceRemove.removeAll(tempRoot / "chains")
    if os.exists(tempRoot / "projects") then nebflow.ForceRemove.removeAll(tempRoot / "projects")
    if os.exists(tempRoot / "c4b-real-ws") then nebflow.ForceRemove.removeAll(tempRoot / "c4b-real-ws")
    if os.exists(tempRoot / "c4b-alias-root") then nebflow.ForceRemove.removeAll(tempRoot / "c4b-alias-root")

  override def afterEach(context: munit.AfterEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()
    MergeQueue.reset()

  private def mkResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
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
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
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

  private class NullLlm:

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
      ): Stream[IO, StreamChunk] = Stream.empty

  private def record(
    id: String,
    taskId: Option[String],
    projectWorkspace: Option[String] = None
  ): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = (tempRoot / "general").toString,
      project = None,
      taskId = taskId,
      parentSessionId = "nebula-root",
      title = "spec chain",
      createdAt = System.currentTimeMillis(),
      projectWorkspace = projectWorkspace.orElse(Some(tempRoot.toString))
    )

  private def agentRecord(sid: String): AgentRecord =
    AgentRecord(
      sessionId = sid,
      ref = null.asInstanceOf[nebflow.actor.ActorRef[AgentCommand]], // 读面只查键在册，零取用
      kind = AgentKind.Delegate,
      rootSessionId = sid
    )

  // ── C1 行投影 ───────────────────────────────────────────────────────────

  test(
    "C1: rows project the registry record with the live hint and the latest terminal-verdict; absent sources leave the keys out"
  ) {
    val liveRec = record("delegate-general-11aa22bb", Some("42"))
    val deadRec = record("delegate-general-22bb33cc", None)
    DelegateRegistry.put(liveRec)
    DelegateRegistry.put(deadRec)
    // 台账键 = keyFor（台账号优先）：verdict 行挂在 42 下。
    ChainStore.appendSync(
      "42",
      "terminal-verdict",
      io.circe.Json
        .obj("verdict" -> "ready-to-merge".asJson, "reason" -> "branch ready".asJson, "source" -> "self-report".asJson)
    )
    val registry = Map("delegate-general-11aa22bb" -> agentRecord("delegate-general-11aa22bb"))
    ChainsReadFace.rowsJson(registry).map { body =>
      val rows = body.hcursor.downField("rows").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assertEquals(rows.size, 2, "one row per registry record")
      val live =
        rows.find(_.hcursor.get[String]("id").toOption.contains(liveRec.id)).getOrElse(fail("live row missing"))
      assertEquals(
        live.hcursor.get[String]("title").toOption,
        Some("spec chain"),
        "record fields pass through verbatim"
      )
      assertEquals(
        live.hcursor.downField("live").get[String]("status").toOption,
        Some("running"),
        "registry presence ⇒ live.status hint (stub §四.4, A8 same face)"
      )
      assertEquals(
        live.hcursor.downField("verdict").get[String]("verdict").toOption,
        Some("ready-to-merge"),
        "latest terminal-verdict row supplies ready-to-merge (v3 §9 verbatim)"
      )
      assertEquals(live.hcursor.downField("verdict").get[String]("reason").toOption, Some("branch ready"))
      val dead =
        rows.find(_.hcursor.get[String]("id").toOption.contains(deadRec.id)).getOrElse(fail("dead row missing"))
      assert(
        dead.hcursor.downField("live").focus.isEmpty,
        "no registry presence ⇒ no live key (adapter keeps authority)"
      )
      // 无判词键：deadRec 的键 = delegateId 回落（keyFor）——42 行不属它。
      assert(dead.hcursor.downField("verdict").focus.isEmpty, "no verdict row on its own key ⇒ no verdict key")
      // 无 taskStatus 供给 = 键缺席（旧投影，缺键容忍）。
      assert(dead.hcursor.downField("taskStatus").focus.isEmpty, "no taskStatus supply ⇒ no taskStatus key")
    }
  }

  test(
    "C1b (#566): taskStatus supply lands verbatim on the matching row — the engine terminal authority for stale-row retirement"
  ) {
    val rec = record("delegate-general-33cc44dd", Some("52"))
    DelegateRegistry.put(rec)
    val statuses = Map(
      rec.id -> "failed",
      "delegate-unknown-ghost" -> "completed" // 查无行 = 零投影（不臆造）
    )
    ChainsReadFace.rowsJson(Map.empty, archived = false, taskStatuses = statuses).map { body =>
      val rows = body.hcursor.downField("rows").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      val row = rows.find(_.hcursor.get[String]("id").toOption.contains(rec.id)).getOrElse(fail("row missing"))
      assertEquals(
        row.hcursor.get[String]("taskStatus").toOption,
        Some("failed"),
        "the engine task-row status rides the row verbatim (UI terminal authority)"
      )
    }
  }

  // ── C5 G1「合并中」一等可查询态（delegate 视图重设计实施批 · 2026-10-21）──────

  test(
    "C5 (G1): the merge-window holder row exposes taskStatus=merging — an explicit engine-queryable state, not a frontend derivation"
  ) {
    val holderId = "delegate-general-merge-holder-1"
    val queuedId = "delegate-general-merge-queued-2"
    val recHolder = record(holderId, Some("61"))
    val recQueued = record(queuedId, Some("62"))
    DelegateRegistry.put(recHolder)
    DelegateRegistry.put(recQueued)
    val projectKey = tempRoot.toString
    // 账本两行（seed 直写——绕过入账泵；read-face 是纯读面，不需要真通知通道）：
    // holder（turn-granted = 单持有人窗本体）+ 排队者。readyAt 错开保证 FIFO 序。
    def member(id: String, readyAt: Long, taskId: Option[String]): MergeQueue.Member =
      MergeQueue.Member(
        delegateId = id,
        branch = s"delegate/$id",
        baseWs = projectKey,
        baseBranch = "main",
        worktree = false,
        projectKey = projectKey,
        taskId = taskId,
        parentSessionId = "spec-root",
        description = "spec merge",
        readyAt = readyAt,
        channelKind = "internal"
      )
    MergeQueue.seedMemberForTest(
      member(holderId, 1000L, Some("61")).copy(state = MergeQueue.State.TurnGranted, grantedAt = Some(1L))
    )
    MergeQueue.seedMemberForTest(member(queuedId, 2000L, Some("62")))
    val statuses = Map(holderId -> "ready-to-merge", queuedId -> "ready-to-merge")
    ChainsReadFace.rowsJson(Map.empty, archived = false, taskStatuses = statuses).map { body =>
      val rows = body.hcursor.downField("rows").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      val holder =
        rows.find(_.hcursor.get[String]("id").toOption.contains(holderId)).getOrElse(fail("holder row missing"))
      val queued =
        rows.find(_.hcursor.get[String]("id").toOption.contains(queuedId)).getOrElse(fail("queued row missing"))
      assertEquals(
        holder.hcursor.get[String]("taskStatus").toOption,
        Some("merging"),
        "the merge-window holder exposes the explicit merging state (G1: derivation lives in the engine read face)"
      )
      // 排队者维持 ready-to-merge（排队 ≠ 合并中）。
      assertEquals(
        queued.hcursor.get[String]("taskStatus").toOption,
        Some("ready-to-merge"),
        "a queued (non-holder) row keeps ready-to-merge"
      )
      // 行内排队键照常（位次/持有者——前端零派生的透传面）。
      assertEquals(
        holder.hcursor.downField("mergeQueue").get[String]("holder").toOption,
        Some(holderId),
        "mergeQueue.holder rides the row for visibility"
      )
      assertEquals(
        holder.hcursor.downField("mergeQueue").get[String]("state").toOption,
        Some("turn-granted"),
        "mergeQueue.state rides the row (the ledger state machine is panel-visible)"
      )
      assertEquals(
        queued.hcursor.downField("mergeQueuePos").get[Int]("position").toOption,
        Some(2),
        "mergeQueuePos.position = engine FIFO order (holder included in total)"
      )
    }
  }

  // ── C6 delegates 列表投影（视图重设计实施批 · UI 最小集）──────────────────────

  test(
    "C6: delegatesJson filters by project and attaches the sub-process topology payload (children for star, steps for agentflow); single rows carry no topology key"
  ) {
    import nebflow.shared.SubAgentTask
    val starId = "delegate-general-c6-star"
    val flowId = "delegate-general-c6-flow"
    val singleId = "delegate-general-c6-single"
    val otherId = "delegate-general-c6-other-project"
    val ws = tempRoot
    DelegateRegistry.put(
      record(starId, Some("71")).copy(orchestrationMode = Some("subagent-star"), project = Some("spec-proj-c6"))
    )
    DelegateRegistry.put(
      record(flowId, Some("72")).copy(orchestrationMode = Some("agentflow"), project = Some("spec-proj-c6"))
    )
    DelegateRegistry.put(
      record(singleId, Some("73")).copy(project = Some("spec-proj-c6"))
    )
    DelegateRegistry.put(
      record(otherId, Some("74")).copy(project = Some("spec-proj-other"))
    )
    // agentflow 行的盘面作用域（探针只读腿——C2 同款最小 flow-map.json）。
    val flowDir = ws / ".nebflow" / "flow-maps" / flowId
    os.makeDir.all(flowDir)
    os.write.over(
      flowDir / "flow-map.json",
      FlowMapState(
        project = flowId,
        updatedAt = 0L,
        nodes = Map(
          "s1" -> NodeDef(
            id = "s1",
            name = "step-one",
            agent = "general",
            task = Some("step one full task"),
            status = NodeLifecycle.Completed,
            createdAt = 1L
          )
        )
      ).asJson.noSpaces
    )
    val child = SubAgentTask(
      taskId = "subtask-c6-1",
      parentSessionId = starId,
      agentName = "scout",
      prompt = "p",
      description = "star child",
      status = "running",
      retryCount = 0,
      spawnedAt = 1L,
      completedAt = None,
      lastError = None,
      source = "subagent"
    )
    val childrenOf = (id: String) => if id == starId then IO(List(child)) else IO(Nil)
    for
      all <- ChainsReadFace.delegatesJson(Map.empty, childrenOf = childrenOf)
      filtered <- ChainsReadFace.delegatesJson(
        Map.empty,
        project = Some("spec-proj-c6"),
        childrenOf = childrenOf
      )
    yield
      val rowsOf =
        (body: io.circe.Json) => body.hcursor.downField("rows").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      val byId = (rows: List[io.circe.Json], id: String) => rows.find(_.hcursor.get[String]("id").toOption.contains(id))
      // 项目过滤：只留 spec-proj-c6 三行。
      val frows = rowsOf(filtered)
      assertEquals(frows.size, 3, "project filter keeps only the matching rows")
      // 星扇行：topology.children 全集；agentflow 行：topology.steps 紧凑形（rowStatusOf 映射）。
      val star = byId(frows, starId).getOrElse(fail("star row missing"))
      val starTop = star.hcursor.downField("topology")
      assertEquals(starTop.get[String]("mode").toOption, Some("subagent-star"))
      val kids = starTop.downField("children").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assertEquals(kids.size, 1, "children = the star fan payload")
      assertEquals(kids.headOption.flatMap(_.hcursor.get[String]("agentName").toOption), Some("scout"))
      assertEquals(kids.headOption.flatMap(_.hcursor.get[String]("status").toOption), Some("running"))
      val flow = byId(frows, flowId).getOrElse(fail("flow row missing"))
      val flowTop = flow.hcursor.downField("topology")
      assertEquals(flowTop.get[String]("mode").toOption, Some("agentflow"))
      val steps = flowTop.downField("steps").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assertEquals(steps.size, 1, "steps = the scoped flow-map nodes (probe leg)")
      assertEquals(steps.headOption.flatMap(_.hcursor.get[String]("id").toOption), Some("s1"))
      assertEquals(
        steps.headOption.flatMap(_.hcursor.get[String]("status").toOption),
        Some("completed"),
        "step status goes through the same rowStatusOf mapping"
      )
      // single 行零 topology 键（缺键纪律——不加空壳键）。
      val single = byId(frows, singleId).getOrElse(fail("single row missing"))
      assert(
        single.hcursor.downField("topology").focus.isEmpty,
        "single rows carry no topology key (missing-key discipline)"
      )
      // 全量臂不过滤。
      assertEquals(rowsOf(all).size, 4, "no project filter = the full row set")
    end for
  }

  // ── C7 重启对账（任务列表行重启对账批 · 2026-10-22）────────────────────────

  test(
    "C7 (重启对账): restart-interrupted rows carry the flag verbatim; continuedBy lands only when a later record cites them"
  ) {
    import nebflow.shared.SubAgentTask
    val now = System.currentTimeMillis()
    val oldId = "delegate-general-cc01old1"
    val loneId = "delegate-general-cc02lone"
    val succId = "delegate-general-cc03succ"
    // 打断行两行（其一被接续、其一孤悬）+ 接续者（title 引用旧 id，晚于打断行派发）。
    DelegateRegistry.put(record(oldId, Some("81")).copy(restartInterruptedAt = Some(now), createdAt = 1000L))
    DelegateRegistry.put(record(loneId, Some("82")).copy(restartInterruptedAt = Some(now), createdAt = 1001L))
    DelegateRegistry.put(
      record(succId, Some("83"))
        .copy(createdAt = 2000L, title = "【断点续作】前会话（delegate-general-cc01old1）被引擎重启打断")
    ) // 台账任务原文行（引用文本源——title 截断后仍可命中的兜底腿）。
    val taskRows = List(
      SubAgentTask(
        taskId = loneId,
        parentSessionId = "p",
        agentName = "nebflow",
        prompt = "unrelated prompt text",
        description = "lone",
        status = "failed",
        retryCount = 0,
        spawnedAt = 1L,
        completedAt = None,
        lastError = None,
        source = "delegate"
      )
    )
    ChainsReadFace.rowsJson(Map.empty, taskStatuses = Map.empty, taskRows = taskRows).map { body =>
      val rows = body.hcursor.downField("rows").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      val byId =
        (id: String) => rows.find(_.hcursor.get[String]("id").toOption.contains(id)).getOrElse(fail(s"$id missing"))
      val old = byId(oldId)
      assertEquals(
        old.hcursor.get[Long]("restartInterruptedAt").toOption,
        Some(now),
        "the flag rides the record verbatim (record asJson passthrough)"
      )
      assertEquals(
        old.hcursor.get[String]("continuedBy").toOption,
        Some(succId),
        "a later record citing the interrupted id ⇒ continuedBy (fold pointer)"
      )
      val lone = byId(loneId)
      assert(
        lone.hcursor.get[String]("continuedBy").toOption.isEmpty,
        "uncited interrupted row carries NO continuedBy key (missing-key discipline)"
      )
      val succ = byId(succId)
      assert(
        succ.hcursor.get[String]("continuedBy").toOption.isEmpty,
        "the continuation itself is not interrupted ⇒ no fold pointer"
      )
    }
  }

  // ── C2 链投影 ───────────────────────────────────────────────────────────

  test(
    "C2: mounted scope projects FlowMap nodes through the v3 §9 status map; unmounted probes the disk read-only; missing dir degrades to null"
  ) {
    val ws = os.temp.dir(prefix = "nb-crf-c2-", deleteOnExit = false)
    val system = ActorSystem(s"crf-c2-${scala.util.Random.nextInt(1000000)}")
    val mountedId = "delegate-general-33cc44dd"
    val ghostId = "delegate-general-44dd55ee"
    // noDir：记录在册但作用域目录不存在（外部执行器形态）——null 降级腿。
    val noDirId = "delegate-general-55ee66ff"
    for
      res <- mkResources(system, NullLlm().handle)
      _ <- DelegateFlowHosts
        .mount(
          delegateId = mountedId,
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
        .flatMap {
          case None => IO.raiseError(new AssertionError("host must mount"))
          case Some(h) =>
            h.store.mutate { s =>
              s.copy(nodes =
                s.nodes ++ Map(
                  "n-a" -> NodeDef(
                    id = "n-a",
                    name = "alpha",
                    agent = "general",
                    task = Some("first line of the task\nsecond"),
                    description = Some("short desc"),
                    deps = List("n-b"),
                    status = NodeLifecycle.Pending,
                    createdAt = 1L
                  ),
                  "n-b" -> NodeDef(
                    id = "n-b",
                    name = "beta",
                    agent = "general",
                    status = NodeLifecycle.Running,
                    createdAt = 2L
                  ),
                  "n-c" -> NodeDef(
                    id = "n-c",
                    name = "gamma",
                    agent = "general",
                    status = NodeLifecycle.Completed,
                    createdAt = 3L
                  ),
                  "n-d" -> NodeDef(
                    id = "n-d",
                    name = "delta",
                    agent = "general",
                    status = NodeLifecycle.Failed,
                    createdAt = 4L
                  )
                )
              )
            }.void
        }
      _ <- IO(DelegateRegistry.put(record(mountedId, None)))
      _ <- IO(DelegateRegistry.put(record(ghostId, None, projectWorkspace = Some(ws.toString))))
      _ <- IO(DelegateRegistry.put(record(noDirId, None, projectWorkspace = Some(ws.toString))))
      // ghost：盘面有作用域目录但宿主未挂载（探针腿）——最小 flow-map.json。
      _ <- IO.blocking {
        val dir = ws / ".nebflow" / "flow-maps" / ghostId
        os.makeDir.all(dir)
        os.write.over(
          dir / "flow-map.json",
          FlowMapState(
            project = ghostId,
            updatedAt = 0L,
            nodes = Map(
              "g-1" -> NodeDef(
                id = "g-1",
                name = "ghost-node",
                agent = "general",
                status = NodeLifecycle.Wiring,
                createdAt = 1L
              )
            )
          ).asJson.noSpaces
        )
      }
      registry = Map(
        mountedId -> agentRecord(mountedId),
        ghostId -> agentRecord(ghostId),
        noDirId -> agentRecord(noDirId)
      )
      mountedBody <- ChainsReadFace.chainJson(mountedId, registry)
      ghostBody <- ChainsReadFace.chainJson(ghostId, registry)
      missingBody <- ChainsReadFace.chainJson("delegate-general-no-such-scope-here", registry)
      noDirBody <- ChainsReadFace.chainJson(noDirId, registry)
      badBody <- ChainsReadFace.chainJson("../escape", registry)
      ghostMounted <- DelegateFlowHosts.isMounted(ghostId)
      ghostDirCreated <- IO.blocking(os.exists(ws / ".nebflow" / "flow-maps" / "delegate-general-no-such-scope-here"))
      _ <- DelegateFlowHosts.unmount(mountedId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val m = mountedBody.getOrElse(fail("mounted scope must project"))
      val nodes = m.hcursor.downField("chain").downField("nodes").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assertEquals(nodes.size, 4, "active-zone members project in createdAt order")
      val a = nodes.head
      assertEquals(a.hcursor.get[String]("id").toOption, Some("n-a"))
      assertEquals(a.hcursor.get[String]("kind").toOption, Some("flow-step"), "ChainNode kind contract")
      assertEquals(
        a.hcursor.get[String]("label").toOption,
        Some("first line of the task"),
        "label = task first line (≤80)"
      )
      assertEquals(a.hcursor.get[String]("description").toOption, Some("short desc"))
      assertEquals(a.hcursor.get[String]("status").toOption, Some("pending"), "pending/wiring ⇒ pending")
      assertEquals(a.hcursor.downField("deps").as[List[String]].toOption, Some(List("n-b")))
      val statuses = nodes.flatMap(_.hcursor.get[String]("status").toOption)
      assertEquals(statuses, List("pending", "running", "completed", "failed"), "v3 §9 mapping table single point")
      assertEquals(m.hcursor.downField("chain").get[Boolean]("mounted").toOption, Some(true))
      // record = 三行族原样（适配层直接输入；此处为空记录形）。
      assertEquals(
        m.hcursor.downField("record").downField("entries").as[List[io.circe.Json]].toOption,
        Some(Nil),
        "record carries the entries array verbatim"
      )
      // 探针腿：未挂载但盘面在。
      val g = ghostBody.getOrElse(fail("on-disk scope must project via probe"))
      assertEquals(g.hcursor.downField("chain").get[Boolean]("mounted").toOption, Some(false))
      val gnodes = g.hcursor.downField("chain").downField("nodes").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assertEquals(
        gnodes.flatMap(_.hcursor.get[String]("status").toOption),
        List("pending"),
        "wiring ⇒ pending through the probe"
      )
      assert(!ghostMounted, "the probe never mounts into the hosts registry (read-only)")
      // 无目录（记录在册）= chain null 降级；查无记录 = None（HTTP 404 面）；
      // 非法键 = None（HTTP 400 面）；探针零建目录。
      assertEquals(
        noDirBody.map(_.hcursor.downField("chain").focus),
        Some(Some(io.circe.Json.Null)),
        "missing scope dir ⇒ chain null (three-row-family degraded view)"
      )
      assertEquals(missingBody, None, "unknown key ⇒ None (route 404s)")
      assertEquals(badBody, None, "invalid key ⇒ None (route 400s before, defense in depth)")
      assert(!ghostDirCreated, "the read face never creates scope directories")
    end for
  }

  // ── C4 现役窗世界座位闸（面板退役台账泄漏微批 #506，2026-10-18）──────────────

  /** 项目定义夹具（ProjectStore.list() 的世界腿数据源；project.json 手写最小形）。 */
  private def putProjectDef(name: String, workspace: os.Path): Unit =
    val dir = tempRoot / "projects" / name
    os.makeDir.all(dir)
    os.write(
      dir / "project.json",
      io.circe.Json
        .obj(
          "name" -> name.asJson,
          "workspace" -> workspace.toString.asJson,
          "agentFile" -> (workspace / "AGENTS.md").toString.asJson,
          "createdAt" -> System.currentTimeMillis().asJson
        )
        .noSpaces
    )

  /** 外来隔离实例（coldstart 腿）形态的记录：座位在实例世界之外。 */
  private def foreignRecord(id: String, taskId: Option[String], createdAt: Long): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = s"/tmp/nb-spec-coldstart-$id/general",
      project = None,
      taskId = taskId,
      parentSessionId = "coldstart-root",
      title = "retired-ledger leftover",
      createdAt = createdAt
    )

  test(
    "C4: the active rows window drops foreign-seat (retired-instance) records; the archived window keeps them (zero deletion)"
  ) {
    // 世界两腿：dataRoot 子树（缺省 general 座位）+ 项目定义 workspace（dataRoot 之外）。
    val projectWs = os.temp.dir(prefix = "nb-crf-c4-projws-", deleteOnExit = false)
    putProjectDef("spec-proj", projectWs)
    val defaultSeat = DelegateRegistry.Record(
      id = "delegate-general-66aa77bb",
      executor = "nebflow",
      cwd = (tempRoot / "general").toString, // dataRoot 子树（缺省座位）
      project = None,
      taskId = Some("900"),
      parentSessionId = "nebula-root",
      title = "in-world default seat",
      createdAt = System.currentTimeMillis(),
      projectWorkspace = None
    )
    val projectSeat = record("delegate-general-77bb88cc", Some("901")).copy(
      project = Some("spec-proj"),
      projectWorkspace = Some(projectWs.toString),
      cwd = (projectWs / ".nebflow" / "worktrees" / "wt-901").toString // worktree 座位 ⊂ 项目 workspace
    )
    val staleActive = foreignRecord("delegate-nebflow-88cc99dd", Some("33"), createdAt = 1L)
    val staleArchived = foreignRecord("delegate-nebflow-99dd00ee", Some("36"), createdAt = 2L)
      .copy(archivedAt = Some(3L))
    List(defaultSeat, projectSeat, staleActive, staleArchived).foreach(DelegateRegistry.put)

    for
      activeBody <- ChainsReadFace.rowsJson(Map.empty)
      archivedBody <- ChainsReadFace.rowsJson(Map.empty, archived = true)
    yield
      val activeIds = activeBody.hcursor
        .downField("rows")
        .as[List[io.circe.Json]]
        .toOption
        .getOrElse(Nil)
        .map(
          _.hcursor.get[String]("id").toOption.getOrElse("")
        )
      val archivedIds = archivedBody.hcursor
        .downField("rows")
        .as[List[io.circe.Json]]
        .toOption
        .getOrElse(Nil)
        .map(
          _.hcursor.get[String]("id").toOption.getOrElse("")
        )
      assertEquals(
        activeIds.toSet,
        Set(defaultSeat.id, projectSeat.id),
        "active window = this instance's world only (default seat + project-workspace seat); retired-instance rows never leak"
      )
      assert(
        !activeIds.contains(staleActive.id),
        "a /tmp coldstart-seat record (retired ledger taskNo) is not panel-visible"
      )
      assertEquals(
        archivedIds.toSet,
        Set(staleArchived.id),
        "the archived window stays complete (zero deletion): archived foreign record still inspectable"
      )
    end for
  }

  test("C4b: seat scoping is canonical-path stable — a symlinked workspace alias still counts as in-world") {
    // Windows symlink 特权门（别名夹具造不出来 ⇒ ignore，能力缺失非引擎缺陷）。
    nebflow.SymlinkProbe.assumeSymlinks()
    // 真实形态：projects 目录 nebflow→stand 改名后，旧记录 cwd 仍走旧名（symlink 别名）。
    // canonical 判定必须吃掉别名差异（否则改名即丢现役行）。夹具落 tempRoot 内（生产
    // 形态 = /Users 下稳定路径的改名 symlink，非系统 tmpdir 形态）。
    val realWs = tempRoot / "c4b-real-ws"
    val aliasDir = tempRoot / "c4b-alias-root"
    val aliasWs = aliasDir / "AliasName"
    os.makeDir.all(realWs)
    os.makeDir.all(aliasDir)
    java.nio.file.Files.createSymbolicLink(
      aliasWs.toNIO,
      realWs.toNIO
    )
    putProjectDef("spec-alias-proj", realWs) // 世界登记 = 真名
    val aliasedSeat = record("delegate-general-aabb0011", Some("902")).copy(
      projectWorkspace = Some((aliasWs / ".nebflow" / "worktrees" / "wt-902").toString) // 座位走别名
    )
    DelegateRegistry.put(aliasedSeat)
    val foreign = foreignRecord("delegate-nebflow-bbcc0022", Some("64"), createdAt = 4L)
    DelegateRegistry.put(foreign)
    for body <- ChainsReadFace.rowsJson(Map.empty)
    yield
      val ids = body.hcursor
        .downField("rows")
        .as[List[io.circe.Json]]
        .toOption
        .getOrElse(Nil)
        .map(
          _.hcursor.get[String]("id").toOption.getOrElse("")
        )
      assertEquals(
        ids.toSet,
        Set(aliasedSeat.id),
        "symlink-aliased project seat resolves into the world (canonical compare); foreign seat filtered"
      )
    end for
  }

end ChainsReadFaceSpec
