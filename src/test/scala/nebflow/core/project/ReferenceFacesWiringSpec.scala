package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, MailTool, NodeReportToolDef, TaskBoardToolDef, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * chainmodel **批三+「台账引用面三面」接线判据 spec**（`mail-usage` / `board-usage` /
 * `report-usage`）。
 *
 * 定位：`ChainLedgerSpec` T7 钉的是**面集合的收放**（登记面 id 集 + 写点/增减时机在册）；
 * 本 spec 钉的是**接线本体**——每个面「引用发生 ⇒ 台账计数增」的读数，以及轴(b) 的
 * 三条边界（退役判据不回归 / 不复活 / 零回填）。两者互补，缺一即「登记即接线」（判红）。
 *
 * 判据映射（任务书 §③ 验收）：
 *  - **c)** 真实写点 + 计数正确性：R1 = `mail-usage` **端到端**（真投递一封带链号的 Mail ⇒
 *    台账 `externalRefs` 落痕 + 覆盖式复算后该链 `refCount` 由 0 变 1）；R2 = `board-usage`
 *    （板卡写动作正文命中 ⇒ 增）；R3 = `report-usage`（node_report 正文命中 ⇒ 增）。
 *  - **d)** 退役判据不回归：R4（`refCount == 0` 才可退役的读数 + 本批计数**不得复活**
 *    已退役/已归档链 —— 离场下沉后再引用只落零命中）。
 *  - **e)** 零回填：R5（悬空号 / 未登记号出现在正文里 ⇒ 不建条目、不建别名、不建计数键；
 *    本批新增回填笔数 = 0）；R7（无籍面闸不回归：未登记 faceId ⇒ `Left` + 零写）。
 *  - **f)** 反过度设计的机械面：正文命中判据是纯函数（R6）+ 计数账在复算下单调（R8）。
 *
 * 变异验红（复核位复跑点；本 spec 的承重断言与变异体的对应关系）：
 *  - **M1** 摘掉 `MailTool.layeredRoute` 的 `countMailUsage` 包裹 ⇒ **R1 红**
 *    （`externalRefs` 恒空）；同理摘 `TaskBoardTool`/`NodeReportTool` 的钩子 ⇒ **R2/R3 红**。
 *  - **M2** 只把三面 `wired` 置 true 而不接线 ⇒ 本 spec R1/R2/R3 全红（`ChainLedgerSpec`
 *    T7 反而绿 —— T7 守的是面集合，本 spec 守的是接线本体；两条互为补集）。
 *  - **M3** 放宽退役判据（`planRetire` 条目谓词改 `refCount < 0` 之类的永不退役式）⇒
 *    **R4** 的「零引用 ⇒ 退役 1 行」读数红。
 *  - **M4** 让正文扫描对未登记号也计数（去掉 `knownIds` 过滤）⇒ **R5** 红（回填笔数 > 0）。
 *
 * 隔离纪律与 `NodeMessageSpec`/`ChainLedgerSpec` 同款：数据集根重定向到 `target/test-ref-faces`
 * （不触真实 `~/.nebflow`）、项目运行时经 `ProjectRuntimeRegistry` 注册并在每条用例前后清空、
 * 自起 actor system 收尾 `stopAll`。
 */
class ReferenceFacesWiringSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-ref-faces"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")

  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"ref-faces wiring agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 本 spec 不 spawn 真会话（不发任何 LLM 请求）。 */
  private object NoLlm extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[nebflow.shared.LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("sendStream not expected"))

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
      llm = NoLlm,
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
      sessionId = Some("rf-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    withBoard: Boolean = false
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        feedbackMode = FeedbackRouter.ModeAuto,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(
        pd,
        store,
        engine,
        system,
        res,
        None,
        board = if withBoard then Some(TaskBoardStore.open(name, ws.toString)) else None
      )
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  /**
   * 台账文件路径 —— 与 `FlowMapStore.open` 的载入点逐字同源（`base = os.Path(ws, dataRoot) /
   * ".nebflow"`）；🔴 父目录须先建：fixture 早于 `mountProject`（= 早于 open 的
   * `makeDir.all(base)`）⇒ 不建目录则 `os.write.over` 抛 NoSuchFileException。
   */
  private def ledgerFilePath(ws: os.Path): os.Path =
    os.Path(ws.toString, PathUtil.dataRoot) / ".nebflow" / ChainLedger.FileName

  /**
   * 落一份台账 fixture（早于 mountProject ⇒ open 期载入）；`project` 名与所落项目一致
   * （判据面只看 entries/aliases/externalRefs，project 仅为账内自述）。
   */
  private def writeLedgerFixture(ws: os.Path, project: String, st: ChainLedger.State): IO[Unit] =
    IO.blocking {
      val p = ledgerFilePath(ws)
      os.makeDir.all(p / os.up)
      os.write.over(p, st.copy(project = project).asJson.noSpaces)
    }

  private def seedNode(
    rt: ProjectRuntime,
    id: String,
    name: String,
    status: String,
    task: Option[String] = None
  ): IO[Unit] =
    rt.store
      .mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            id -> NodeDef(
              id = id,
              name = name,
              agent = "test-agent",
              task = task,
              result = None,
              status = status,
              out = List(OutEdge.root),
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      .void

  private def ledgerOf(rt: ProjectRuntime): IO[ChainLedger.State] =
    rt.store.chainLedgerStore.snapshot

  private def failMsg(r: Either[nebflow.core.tools.ToolError, String]): String =
    r.left.map(_.message).toString

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private val Canon = "chain-n-rf-canon"

  /** 单条目台账 fixture（一条已出生链 —— 正文/引用面的计数目标行）。 */
  private def canonFixture(project: String, canon: String = Canon): ChainLedger.State =
    ChainLedger.State(
      project = project,
      updatedAt = 1L,
      entries = Map(canon -> ChainLedger.Entry(chainId = canon, anchor = canon, bornAt = 1L))
    )

  // ── R1 [c) mail-usage 端到端] ──────────────────────────────────────────

  test("R1 [c) mail-usage 端到端] 带链号 Mail 投递成功 ⇒ externalRefs 落痕 + 该链 refCount 0→1；无链号/投递失败 ⇒ 零计数") {
    val ws = tempRoot / "ws-rf-r1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"rf-r1-${scala.util.Random.nextInt(100000)}")
    for
      _ <- writeLedgerFixture(ws, "rf-r1", canonFixture("rf-r1"))
      res <- mkResources(system, tempRoot)
      rt <- mountProject("rf-r1", ws, system, res)
      _ <- seedNode(rt, "n-r1", "rf-runner", NodeLifecycle.Pending, task = Some("rf 用例任务"))
      _ <- seedNode(rt, "n-r1-term", "rf-term", NodeLifecycle.Completed, task = Some("t"))
      dispCtx = mkCtx(res, system, ws.toString).copy(isDispatcher = true, projectName = Some("rf-r1"))
      st0 <- ledgerOf(rt)
      // ① 投递成功 + 携带链号 ⇒ 计 1
      ok <- MailTool
        .call(
          Json.obj("address" -> "node:n-r1".asJson, "message" -> "带链号".asJson, "chainId" -> Canon.asJson).asObject.get,
          dispCtx
        )
        .map(failMsg)
      st1 <- ledgerOf(rt)
      // ② 不带链号 ⇒ 照常投递、零计数
      okNoChain <- MailTool
        .call(Json.obj("address" -> "node:n-r1".asJson, "message" -> "无链号".asJson).asObject.get, dispCtx)
        .map(failMsg)
      st2 <- ledgerOf(rt)
      // ③ 带链号但投递失败（终态节点 ⇒ NODE_TERMINAL_NO_MESSAGE）⇒ 零计数
      refused <- MailTool
        .call(
          Json
            .obj("address" -> "node:n-r1-term".asJson, "message" -> "迟到".asJson, "chainId" -> Canon.asJson)
            .asObject
            .get,
          dispCtx
        )
        .map(failMsg)
      st3 <- ledgerOf(rt)
      // ④ 校验失败（未登记号 ⇒ MAIL_CHAIN_NOT_FOUND）⇒ 零计数（引用没发生）
      rejected <- MailTool
        .call(
          Json
            .obj("address" -> "node:n-r1".asJson, "message" -> "悬空号".asJson, "chainId" -> "chain-n-rf-ghost".asJson)
            .asObject
            .get,
          dispCtx
        )
        .map(failMsg)
      st4 <- ledgerOf(rt)
      onDisk <- IO.blocking(os.read(ledgerFilePath(ws)))
      counted = ChainLedger.recomputeRefCounts(st4, ChainLedger.FaceCounts())
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(st0.externalRefs, Map.empty[String, Int], "起跑：台账零外部引用")
      assert(ok.startsWith("Right("), s"带链号的合法 Mail 必须投递成功，got: $ok")
      assertEquals(
        st1.externalRefs.get(Canon),
        Some(1),
        "🔴 incWhen 落点：Mail 携带 chainId 且投递成功 ⇒ mail-usage 面计 1（noteReference 落痕）"
      )
      assert(okNoChain.startsWith("Right("), s"无链号 Mail 照常投递，got: $okNoChain")
      assertEquals(st2.externalRefs, st1.externalRefs, "无链号 ⇒ 零计数（面只在携带链号时计）")
      assert(refused.contains("NODE_TERMINAL_NO_MESSAGE"), s"终态节点必须拒绝投递，got: $refused")
      assertEquals(st3.externalRefs, st1.externalRefs, "投递失败 ⇒ 零计数（引用未发生）")
      assert(rejected.contains("MAIL_CHAIN_NOT_FOUND"), s"未登记号必须拒，got: $rejected")
      assertEquals(st4, st3, "校验失败 ⇒ 台账逐字不变（零副作用）")
      assertEquals(counted.entries(Canon).refCount, 1, "🔴 读数：覆盖式复算按账并入 ⇒ 该链 refCount 由 0 变 1（Mail 引用真实挡住退役）")
      assert(onDisk.contains(Canon) && onDisk.contains("externalRefs"), "落痕持久：台账文件明档可读该引用键（非内存态）")
    end for
  }

  // ── R2 [c) board-usage] ────────────────────────────────────────────────

  test("R2 [c) board-usage] 板卡写动作正文命中已登记链号 ⇒ 计 1；只读动作 / 写失败 / 未登记号 ⇒ 零计数") {
    val ws = tempRoot / "ws-rf-r2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"rf-r2-${scala.util.Random.nextInt(100000)}")
    for
      _ <- writeLedgerFixture(ws, "rf-r2", canonFixture("rf-r2"))
      res <- mkResources(system, tempRoot)
      rt <- mountProject("rf-r2", ws, system, res, withBoard = true)
      ctx = mkCtx(res, system, ws.toString).copy(isDispatcher = true, projectName = Some("rf-r2"))
      st0 <- ledgerOf(rt)
      // ① 写动作成功 + 正文命中 ⇒ 计 1（note 里带链号）
      ok <- TaskBoardToolDef
        .call(
          Json
            .obj("action" -> "create".asJson, "title" -> "任务书".asJson, "note" -> s"本条任务引用链 $Canon（板卡正文面）".asJson)
            .asObject
            .get,
          ctx
        )
        .map(failMsg)
      st1 <- ledgerOf(rt)
      // ② 只读动作（list）⇒ 零计数
      _ <- TaskBoardToolDef.call(Json.obj("action" -> "list".asJson).asObject.get, ctx).map(failMsg)
      st2 <- ledgerOf(rt)
      // ③ 写失败（缺 title）⇒ 零计数
      bad <- TaskBoardToolDef
        .call(Json.obj("action" -> "create".asJson, "note" -> s"写失败的正文 $Canon".asJson).asObject.get, ctx)
        .map(failMsg)
      st3 <- ledgerOf(rt)
      // ④ log 正文命中 ⇒ 再计 1（每次引用各计一次）；条目 id 从板面现读（不猜编号）
      boardId <- IO.blocking(rt.board.toList.flatMap(_.entriesSync()).headOption.map(_.id).getOrElse(""))
      _ <- TaskBoardToolDef
        .call(
          Json.obj("action" -> "log".asJson, "id" -> boardId.asJson, "text" -> s"补记：$Canon 的落地读数".asJson).asObject.get,
          ctx
        )
        .map(failMsg)
      st4 <- ledgerOf(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(st0.externalRefs, Map.empty[String, Int], "起跑：零外部引用")
      assert(ok.startsWith("Right("), s"板卡 create 必须成功，got: $ok")
      assertEquals(st1.externalRefs.get(Canon), Some(1), "🔴 board-usage 计数钩子：写成功后正文命中 ⇒ 计 1")
      assertEquals(st2.externalRefs, st1.externalRefs, "list（只读）⇒ 零计数")
      assert(bad.contains("TBOARD_PARAM"), s"缺 title 的 create 必须失败，got: $bad")
      assertEquals(st3.externalRefs, st1.externalRefs, "写失败 ⇒ 零计数（引用未发生）")
      assertEquals(st4.externalRefs.get(Canon), Some(2), s"log 正文再次引用 ⇒ 再计 1（只计不减、每次引用各计；entry id=$boardId）")
    end for
  }

  // ── R3 [c) report-usage] ───────────────────────────────────────────────

  test("R3 [c) report-usage] node_report 正文命中已登记链号 ⇒ 计 1；未登记号正文 ⇒ 零计数") {
    val ws = tempRoot / "ws-rf-r3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"rf-r3-${scala.util.Random.nextInt(100000)}")
    for
      _ <- writeLedgerFixture(ws, "rf-r3", canonFixture("rf-r3"))
      res <- mkResources(system, tempRoot)
      rt <- mountProject("rf-r3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString).copy(
        projectName = Some("rf-r3"),
        flowNodeId = Some("n-r3"),
        flowNodeRole = Some(NodeRoles.Task)
      )
      st0 <- ledgerOf(rt)
      ok <- NodeReportToolDef
        .call(Json.obj("category" -> "finish".asJson, "detail" -> s"本节点交付物引用链 $Canon（报告正文面）".asJson).asObject.get, ctx)
        .map(failMsg)
      st1 <- ledgerOf(rt)
      // 未登记号只在正文里 ⇒ 零计数（禁回填）
      _ <- NodeReportToolDef
        .call(
          Json.obj("category" -> "finish".asJson, "detail" -> "本条只提到 chain-n-rf-ghost 与 681".asJson).asObject.get,
          ctx
        )
        .map(failMsg)
      st2 <- ledgerOf(rt)
      onDisk <- IO.blocking {
        val p = os.Path(ws.toString, PathUtil.dataRoot) / ".nebflow" / NodeReportRegistry.JournalFileName
        if os.exists(p) then os.read(p) else ""
      }
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(st0.externalRefs, Map.empty[String, Int], "起跑：零外部引用")
      assert(ok.startsWith("Right("), s"合法申报必须成功，got: $ok")
      assertEquals(st1.externalRefs.get(Canon), Some(1), "🔴 report-usage 计数钩子：申报登记成功后正文命中 ⇒ 计 1")
      assertEquals(st2.externalRefs, st1.externalRefs, "未登记号 ⇒ 零计数（零回填）")
      assert(onDisk.contains("chain-n-rf-ghost"), "申报正文照旧落库（计数与否不改变申报本身）")
    end for
  }

  // ── R4 [d) 退役判据不回归 + 不复活] ────────────────────────────────────

  test("R4 [d) 退役判据不回归 + 不复活] refCount==0 才可退役（读数）；本批计数不得复活已退役/已归档链") {
    val canon = "chain-n-r4-canon"
    val archived = ChainLedger.Entry(
      chainId = canon,
      anchor = canon,
      bornAt = 1L,
      status = ChainLedger.StatusArchived,
      archivedAt = Some(2L),
      refCount = 0
    )
    // ① 纯函数读数：归档 ∧ 零引用 ⇒ 退役 1 行；同一行被外部面计 1 ⇒ 退役 0 行
    val stZero = ChainLedger.State(project = "r4", entries = Map(canon -> archived))
    val retiredZero = ChainLedger.planRetire(stZero, 5L)
    val stCounted =
      ChainLedger.recomputeRefCounts(stZero.copy(externalRefs = Map(canon -> 1)), ChainLedger.FaceCounts())
    val retiredCounted = ChainLedger.planRetire(stCounted, 5L)
    // ② store 端到端读数：归档零引用行经一拍 reconcile 下沉冷档 ⇒ 之后引用只落零命中
    val ws = tempRoot / "ws-rf-r4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"rf-r4-${scala.util.Random.nextInt(100000)}")
    for
      _ <- writeLedgerFixture(
        ws,
        "rf-r4",
        ChainLedger.State(project = "rf-r4", updatedAt = 1L, entries = Map(canon -> archived))
      )
      res <- mkResources(system, tempRoot)
      rt <- mountProject("rf-r4", ws, system, res)
      before <- ledgerOf(rt)
      obs <- rt.store.chainLedgerStore.reconcile(Nil, Set.empty, Map.empty, Set.empty, 10L)
      after <- ledgerOf(rt)
      rounds <- rt.store.chainLedgerStore.readRounds
      noted <- rt.store.chainLedgerStore.noteTextReferences(s"本条引用 $canon（已退役号）", "board-usage")
      afterNote <- ledgerOf(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(retiredZero.entries.map(_.chainId), List(canon), "🔴 读数：已归档 ∧ refCount==0 ⇒ 退役 1 行（判据本体不变）")
      assertEquals(stCounted.entries(canon).refCount, 1, "外部面按账并入 ⇒ 该行引用数 1")
      assert(
        retiredCounted.isEmpty,
        s"🔴 有活引用 ⇒ 退役 0 行（本批计数确实拦住早退役：轴 b 承重），got ${retiredCounted.entries.map(_.chainId)}"
      )
      assert(before.entries.contains(canon), "起跑：归档零引用行仍在热面（未到拍）")
      assertEquals(obs.dissolved.map(_.chainId), List(canon), "无命中分量 ⇒ 离场 ⇒ 整行下沉（读数：1 行）")
      assert(!after.entries.contains(canon), "下沉后热面不再有该行（载荷整行在冷档）")
      assertEquals(rounds.map(_.kind), List(ChainLedger.RoundDissolve), "冷档留痕：dissolve 一轮（只归档不删除）")
      assertEquals(noted, Right(Nil): Either[String, List[String]], "已退役号不在 knownIds（热面）⇒ 零命中")
      assertEquals(afterNote.externalRefs, Map.empty[String, Int], "🔴 不复活：不为已退役号新建计数键（本批计数零副作用）")
      assertEquals(afterNote.entries.keySet, after.entries.keySet, "🔴 不复活：entries 集合逐字不变（计数不建条目、不改状态）")
    end for
  }

  // ── R5 [e) 零回填] ────────────────────────────────────────────────────

  test("R5 [e) 零回填] 正文里的悬空号/未登记号 ⇒ 不建条目、不建别名、不建计数键；命中的只有已登记号") {
    val ws = tempRoot / "ws-rf-r5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"rf-r5-${scala.util.Random.nextInt(100000)}")
    // 正文样本：登记号 ×1 + 悬空号三类（设计件「681」类裸编号 / 未登记 chain 号 / 前缀族近似号）
    val mixed = s"引用 $Canon 与 681 与 chain-n-rf-ghost 与 chain-n-rf-can"
    for
      _ <- writeLedgerFixture(ws, "rf-r5", canonFixture("rf-r5"))
      res <- mkResources(system, tempRoot)
      rt <- mountProject("rf-r5", ws, system, res)
      st0 <- ledgerOf(rt)
      hit <- rt.store.chainLedgerStore.noteTextReferences(mixed, "board-usage")
      st1 <- ledgerOf(rt)
      // 纯悬空号正文 ⇒ 零写（连计数键都不新建）
      none <- rt.store.chainLedgerStore.noteTextReferences("681 / chain-n-rf-ghost / chain-n-rf-can", "report-usage")
      st2 <- ledgerOf(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        hit,
        Right(List(Canon)): Either[String, List[String]],
        "🔴 命中集 = 恰一个已登记号（前缀近似号 chain-n-rf-can ≠ chain-n-rf-canon；裸编号不计）"
      )
      assertEquals(st1.externalRefs, Map(Canon -> 1), "只有登记号进账（一次原子写）")
      assertEquals(st1.entries.keySet, st0.entries.keySet, "本批新增条目 = 0（零回填）")
      assertEquals(st1.aliases.keySet, st0.aliases.keySet, "本批新增别名行 = 0（零回填）")
      assertEquals(st1.externalRefs.size, 1, "🔴 本批新增回填笔数 = 0（externalRefs 无悬空键）")
      assertEquals(none, Right(Nil): Either[String, List[String]], "纯悬空号正文 ⇒ 零命中")
      assertEquals(st2, st1, "零命中 ⇒ 零写（台账逐字不变，禁空账轮）")
    end for
  }

  // ── R6 正文命中判据（纯函数，边界承重）────────────────────────────────

  test("R6 [正文命中判据] 逐字 + 链号边界：前缀族链号互不误命中；空文本/空已知集恒空") {
    val known = Set("chain-u-1", "chain-u-10", "chain-n-rf-canon", "chain-legacy.old")
    assertEquals(
      ChainLedger.referencedIds("chain-u-10", Set("chain-u-1")),
      Nil,
      "🔴 边界判据：`chain-u-1` 不得因 `chain-u-10` 出现而命中"
    )
    assertEquals(
      ChainLedger.referencedIds("chain-u-1", Set("chain-u-10")),
      Nil,
      "反向同款：`chain-u-10` 不得因 `chain-u-1` 出现而命中"
    )
    assertEquals(
      ChainLedger.referencedIds("见 chain-u-1 与 chain-u-10", known),
      List("chain-u-1", "chain-u-10"),
      "两个都在 ⇒ 两个都命中（升序确定）"
    )
    assertEquals(ChainLedger.referencedIds("前缀 chain-n-rf-can 不算", Set("chain-n-rf-canon")), Nil, "未完整出现的登记号不算引用")
    assertEquals(
      ChainLedger.referencedIds("[chain-u-1] (chain-u-1)、chain-u-1。", known),
      List("chain-u-1"),
      "标点/括号/换行皆为合法边界（非链号字符）"
    )
    assertEquals(
      ChainLedger.referencedIds("改号后缀 chain-legacy.old 命中", known),
      List("chain-legacy.old"),
      "`.` 是链号字符：带点后缀号整体命中"
    )
    assertEquals(ChainLedger.referencedIds("", known), Nil, "空文本 = 空表")
    assertEquals(ChainLedger.referencedIds("chain-u-1", Nil), Nil, "空已知集 = 空表")
  }

  // ── R7 无籍面闸不回归 ─────────────────────────────────────────────────

  test("R7 [禁无籍计数] 未登记 faceId ⇒ Left 可行动错误 + 台账逐字不变（两张入口同闸）") {
    val ws = tempRoot / "ws-rf-r7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"rf-r7-${scala.util.Random.nextInt(100000)}")
    for
      _ <- writeLedgerFixture(ws, "rf-r7", canonFixture("rf-r7"))
      res <- mkResources(system, tempRoot)
      rt <- mountProject("rf-r7", ws, system, res)
      st0 <- ledgerOf(rt)
      r1 <- rt.store.chainLedgerStore.noteReference(Canon, "no-such-face", 1)
      r2 <- rt.store.chainLedgerStore.noteTextReferences(s"引用 $Canon", "no-such-face")
      st1 <- ledgerOf(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.left.exists(_.contains("unknown reference face 'no-such-face'")), s"未登记面必须显式拒绝，got: $r1")
      assert(r2.left.exists(_.contains("unknown reference face 'no-such-face'")), s"正文入口同款拒绝，got: $r2")
      assert(
        r1.left.exists(_.contains("禁无籍计数")) && r2.left.exists(_.contains("禁无籍计数")),
        "两张入口文案同源（单点 unknownFaceError）"
      )
      assertEquals(st1, st0, "无籍计数 ⇒ 零写（台账逐字不变）")
    end for
  }

  // ── R8 计数账在覆盖式复算下单调（只计不减）────────────────────────────

  test("R8 [只计不减] 覆盖式复算不清外部账：连续两拍复算 ⇒ 计数不丢、不归零") {
    val canon = "chain-n-r8-canon"
    val st0 = ChainLedger.State(
      project = "r8",
      entries = Map(canon -> ChainLedger.Entry(chainId = canon, anchor = canon, bornAt = 1L)),
      externalRefs = Map(canon -> 3)
    )
    val st1 = ChainLedger.recomputeRefCounts(st0, ChainLedger.FaceCounts())
    val st2 = ChainLedger.recomputeRefCounts(st1, ChainLedger.FaceCounts())
    assertEquals(st1.entries(canon).refCount, 3, "外部面按账并入（3 次引用 ⇒ refCount 3）")
    assertEquals(st2.entries(canon).refCount, 3, "🔴 再复算一拍仍为 3（丢更新不可复算的反面：恒等式）")
    assertEquals(st2.externalRefs, st0.externalRefs, "externalRefs 是覆盖式复算的**输入**，不被复算改写")
  }
end ReferenceFacesWiringSpec
