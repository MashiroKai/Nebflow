package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/**
 * nrloop 二期「节点稳定性快修批」（作者 2026-09-14 11:17 插队令）的回归：三件修复
 * 各自的**旧代码必红**用例（逐条对齐任务书的三件判据）：
 *
 *  ① **reloopTo 执行腿**：verifier 判 fail ⇒ 目标**真实重激活**（状态离开
 *     `completed`、旧结果丢弃）+ 回边**真实投递**（`loop-round` 记
 *     `dispatched=reloopTo`，不再是 `deferred=execution-leg-phase-2`），且**驱动方
 *     复位**（verifier 回 `wiring` 并把目标那一轨从 `deliveredTo` 摘掉）——否则目标
 *     重跑完成后无人复核，回边只跑一轮即静默冻结。
 *  ② **轮预算耗尽显式终态**（两处机制根因）：
 *     (a) 轮次维**在消费后判定** ⇒ `3/3` 是**熔断轮**而非再派发轮（终态 + `loop-budget`
 *         事件 + 无重跑派发）；旧口径用消费前的计数 ⇒ 3/3 仍派发、要等第 4 次判词。
 *     (b) 熔断**不再被「起点缺失」静默吞掉**（旧口径 `clearLoopStartedAt` 返回 false
 *         即整条熔断跳过 —— 轮次维耗尽时若起点已被孤儿扫描清掉，节点不终态化、
 *         不发事件、不通知分发器）。
 *     (c) 时间维的驱动方判据 = **判词边**而非节点生命周期 ⇒ `completed` + `lastVerdict=fail`
 *         的 verifier 仍是驱动方（走熔断），**不再**被误判成孤儿（清表 + 零终态化）。
 *  ③ **settle-sweep 判词感知（判定：main 已交付 ⇒ 本批零改动，改为验收读数）**：
 *     任务书 ③ 的判据「sweep 拉起 merge sink 前须核上游 verifier `lastVerdict=pass`；
 *     fail ⇒ 不拉起」在 main 上**已由既有 merge verdict 闸承担**（`7fd0f2fc5` 2026-09-12，
 *     出处同为 perm-global-merge(n-9e9c385d)：`mergeVerdictHolders` + 三处落点，其中
 *     落点② = `settleRunnableSweep` 资格回扫；权威 spec = `MergeVerdictGateSpec` V1–V8，
 *     本批回归臂整跑）。本 spec 因此**不**复刻该闸，只做本批在 sweep 面上的
 *     **零回归对照**：普通 task 上游的孤儿 barrier 照旧自愈补投并启动（既有行为逐字不变），
 *     而 `lastVerdict=fail` 的 verifier 上游 ⇒ 自愈**照旧发生**（`deliveredTo` 记全，闸的
 *     前提契约不改）但 **merge 不得被拉起**（闸持有，`mount-stalled` 点名 verdict 闸）。
 *     🔴 记录：本批曾在该处加「fail-verifier 一律不补投」的挡投腿，实测打红
 *     `MergeVerdictGateSpec.V1`（其前提断言 = 自愈确实跑了）⇒ 已撤除；读数见
 *     `.nebflow/evidence/20260914_stability-hotfix/rawlogs/regress-before-revert.sbt.log`。
 *
 * 驱动方式（确定性，零真实 LLM）：`ToolCallLlm` 在 verifier 会话里发 `node_report(fail)`
 * 工具调用后收尾 ⇒ 真实引擎路径 `completeNode(declared=fail)` → `verifierFail`。
 * 目标节点一律**无 task**：`resetForLoop` 判据落 `wiring`、`startNode` 因零接线防御
 * 不 spawn ⇒ 断言面无会话竞态（本 spec 只验**派发/终态/挡投**三件事，不验目标会话本身
 * 的输入装配——那由 `startNode` 的 `loopRework` 参数单测面覆盖）。
 */
class LoopExecutionLegSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-loop-exec-leg"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")

  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"loop exec leg agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /**
   * 工具申报状态机 LLM（`NodeBlockedToolSignalSpec` 同款；本 spec 只用 fail 面）：
   * 首 turn 发 `node_report(fail)`，见到回执后输出无锚定收尾文本 ⇒ 终态由**工具通道**
   * 驱动，而不是文本形态（证明 `verifierFail` 是被真实引擎路径调到的）。
   */
  private class FailReportLlm(closing: String):
    /**
     * 结论文本（= 会话最后一轮 assistant 输出，落 result 的那一段）；断言「熔断不得
     * 吞掉判词全文」（engine-defects 批 #239）时按它对照。
     */
    val closingText: String = closing
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    private val ackText = "[OK] verdict recorded (fail)"

    private def sawAck(req: LlmRequest): Boolean =
      req.messages.exists { m =>
        m.content match
          case Right(blocks) =>
            blocks.exists {
              case ContentBlock.ToolResult(_, content, _) => content.contains(ackText)
              case _ => false
            }
          case Left(t) => t.contains(ackText)
      }

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        if sawAck(req) then
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(StreamChunk.TextDelta(closing), StreamChunk.Done(None, None))
        else
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(
              StreamChunk.ToolCallChunk(
                ToolCall(
                  "rb-fail-1",
                  "node_report",
                  JsonObject(
                    "category" -> "fail".asJson,
                    "detail" -> "artifact does not compile".asJson,
                    "suggestion" -> "re-run upstream after the dependency rollback".asJson
                  )
                )
              ),
              StreamChunk.Done(Some("tool_use"), None)
            )

        end if

      end sendStream

  end FailReportLlm

  /**
   * ①b 夹具：**由返工段驱动的分支**。目标重跑会话的首条输入带 `[LoopNode rework` 段 ⇒
   * 该会话走「worker 返工」分支：记下输入后被 `gate` **持有**（turn 不结束）⇒ 断言
   * 「目标 flowmap status 回 running」时无竞态（NodeEngine 先置 running 再发首轮请求）。
   * verifier 会话（输入不含返工段）走原 fail 申报状态机；见回执后收尾。
   */
  private class ReentryLlm(gate: cats.effect.Deferred[IO, Unit]):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    private val ackText = "[OK] verdict recorded (fail)"

    private def sawAck(req: LlmRequest): Boolean =
      req.messages.exists { m =>
        m.content match
          case Right(blocks) =>
            blocks.exists {
              case ContentBlock.ToolResult(_, content, _) => content.contains(ackText)
              case _ => false
            }
          case Left(t) => t.contains(ackText)
      }

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        if text.contains("[LoopNode rework") then
          // worker 返工轮：记为「目标重跑会话真的起了」，然后挂住（等测试放行）
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream.eval(gate.get) >>
            Stream(StreamChunk.TextDelta("worker rework round delivered"), StreamChunk.Done(None, None))
        else if sawAck(req) then
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(StreamChunk.TextDelta("被判定对象不合格，正式给出 fail verdict。本节点收尾。"), StreamChunk.Done(None, None))
        else
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(
              StreamChunk.ToolCallChunk(
                ToolCall(
                  "rb-fail-1",
                  "node_report",
                  JsonObject(
                    "category" -> "fail".asJson,
                    "detail" -> "artifact does not compile".asJson,
                    "suggestion" -> "re-run upstream after the dependency rollback".asJson
                  )
                )
              ),
              StreamChunk.Done(Some("tool_use"), None)
            )

        end if

      end sendStream

  end ReentryLlm

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

  /** 引擎挂载（无 ProjectActor：所有扫描腿由本 spec 显式点名调用，零后台竞态）。 */
  private def mountEngineOnly(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources
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
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  /** 直种节点（绕过 NodeEdit：本 spec 验的是运行期判据，不是创建期校验）。 */
  private def mkNode(
    id: String,
    name: String,
    status: String,
    role: String = NodeRoles.Task,
    in: List[String] = Nil,
    out: List[OutEdge] = Nil,
    result: Option[String] = None,
    deliveredTo: List[String] = Nil,
    deps: List[String] = Nil,
    lastVerdict: Option[String] = None,
    loopRound: Int = 0,
    loopStartedAt: Option[Long] = None,
    withTask: Boolean = false,
    merge: Boolean = false
  ): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "test-agent",
      task = if withTask then Some(s"$name task") else None,
      status = status,
      in = in,
      out = out,
      deps = deps,
      result = result,
      deliveredTo = deliveredTo,
      role = role,
      lastVerdict = lastVerdict,
      loopRound = loopRound,
      loopStartedAt = loopStartedAt,
      merge = merge,
      createdAt = System.currentTimeMillis()
    )

  /** verifier 驱动方（`(fail)n-work:loop`）——被判对象的返工回边持有者。 */
  private def failVerifier(
    id: String,
    status: String,
    loopRound: Int = 0,
    lastVerdict: Option[String] = None,
    deliveredTo: List[String] = Nil
  ): NodeDef =
    mkNode(
      id,
      id,
      status,
      role = NodeRoles.Verifier,
      in = List("n-work"),
      out = List(OutEdge("n-land"), OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop)),
      result = Some("verdict closing text"),
      deliveredTo = deliveredTo,
      lastVerdict = lastVerdict,
      loopRound = loopRound,
      withTask = true
    )

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines =>
        lines.flatMap(l =>
          jsonParse(l).toOption.map(j =>
            (
              j.hcursor.get[String]("type").getOrElse(""),
              j.hcursor.get[String]("nodeId").getOrElse(""),
              j.hcursor.get[String]("summary").getOrElse("")
            )
          )
        )
      )
      .handleError(_ => Nil)

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def waitTerminal(rt: ProjectRuntime, id: String): IO[Unit] =
    waitUntil(30.seconds)(rt.store.snapshot.map(_.nodes.get(id).exists(n => NodeLifecycle.Terminal.contains(n.status))))

  /**
   * 等待某节点的 `loop-round` 事件落盘（= `verifierFail` 走到「已处置」的唯一同步点）。
   * ⚠ **不能等「节点终态」**：执行腿落地后驱动方被复位出 completed（那正是修复本体），
   * 终态等待会永远超时——旧口径下「fail 恒 completed 且无人复位」才是终态可等的。
   */
  private def waitLoopRound(ws: os.Path, id: String): IO[Unit] =
    waitUntil(30.seconds)(readAudit(ws).map(_.exists((t, nid, _) => t == "loop-round" && nid == id)))

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private val LoopProps = List("nebflow.nrloop.maxRounds", "nebflow.nrloop.maxWallClockMs")

  // ── ① 执行腿：fail ⇒ 真实重激活 + 真实投递 + 驱动方复位 ───────────────

  test(
    "① reloopTo: a fail verdict really dispatches — target re-activated, loop-round records the dispatch, driver reset so the loop can advance"
  ) {
    val ws = tempRoot / "ws-exec"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lexec-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "被判定对象不合格，正式给出 fail verdict。本节点收尾。")
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountEngineOnly("lexec", ws, system, res)
        // 真实形态：worker 已跑完并把结果投给了 verifier（deliveredTo 有记录）。
        _ <- seed(
          rt,
          mkNode(
            "n-work",
            "n-work",
            NodeLifecycle.Completed,
            out = List(OutEdge("n-ver")),
            result = Some("worker round-1 output")
          ),
          failVerifier("n-ver", NodeLifecycle.Wiring, loopRound = 0, deliveredTo = List("n-work")),
          mkNode("n-land", "n-land", NodeLifecycle.Wiring)
        )
        _ <- rt.engine.startNode("n-ver")
        _ <- waitLoopRound(ws, "n-ver")
        ver <- rt.store.snapshot.map(_.nodes("n-ver"))
        work <- rt.store.snapshot.map(_.nodes("n-work"))
        land <- rt.store.snapshot.map(_.nodes("n-land"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        // 判词本体：verdict ≠ 节点状态（fail 恒 completed/已复位，不是 failed）
        assertEquals(ver.lastVerdict, Some("fail"), "the fail verdict is recorded on the driver")
        assert(
          !audit.exists((t, _, s) => t == "loop-round" && s.contains("deferred=")),
          s"the silent defer MUST be gone, got: $audit"
        )
        assert(
          audit.exists((t, id, s) =>
            t == "loop-round" && id == "n-ver" && s.contains("dispatched=reloopTo") && s.contains("round=1/3")
          ),
          s"round 1/3 must be DISPATCHED (not deferred), got: $audit"
        )
        // 目标重激活：离开 completed、旧结果丢弃（前端看到 attempt N，而不是拓扑增殖）
        assertEquals(work.status, NodeLifecycle.Wiring, "the judged target leaves the completed terminal state")
        assertEquals(work.result, None, "the old result is discarded (it is about to be recomputed)")
        assert(work.loopStartedAt.isDefined, "the wall-clock anchor survives the re-run (cross-round semantics)")
        assert(
          audit.exists((t, id, s) => t == "reactivated" && id == "n-work" && s.contains("source=loop")),
          s"the re-run must be audited as an engine loop re-run (source=loop), got: $audit"
        )
        // 驱动方复位 ⇒ 回边下一轮可达（不复位则目标重跑完成后无人复核 = 静默冻结）
        assertEquals(
          ver.status,
          NodeLifecycle.Wiring,
          "the fail-route driver is reset so the target's new output can re-trigger it"
        )
        assert(
          !ver.deliveredTo.contains("n-work"),
          s"the target's delivery is withdrawn from the driver's barrier (waits for the NEW output), got: ${ver.deliveredTo}"
        )
        // 选通面：fail 判词不投 pass 边（既有语义零变化）
        assertEquals(land.deliveredTo, Nil, "verdict=fail must not deliver along the pass edge")
        assertEquals(land.status, NodeLifecycle.Wiring, "the sink must not be pulled up by a fail verdict")
    finally
      LoopProps.foreach(System.clearProperty)
    end try
  }

  // ── ①b worker 重新入轮：目标**真的重跑**（flowmap status 回 running + 返工段到位）──

  test(
    "① worker re-entry: the re-activated target really re-enters a round — flowmap status goes back to RUNNING and the re-run session's first input carries the round/issues/requirements rework section"
  ) {
    val ws = tempRoot / "ws-reentry"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lreentry-${scala.util.Random.nextInt(100000)}")
    try
      for
        gate <- cats.effect.Deferred[IO, Unit]
        llm = new ReentryLlm(gate)
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountEngineOnly("lreentry", ws, system, res)
        _ <- seed(
          rt,
          // 目标 = 有 task 的 worker（真会 spawn 会话）；out 空 ⇒ 重跑完成不级联回 verifier
          mkNode(
            "n-rework",
            "n-rework",
            NodeLifecycle.Completed,
            result = Some("worker round-1 output"),
            withTask = true
          ),
          // 驱动方 = fail-verifier（回边 (fail)->n-rework:loop）
          mkNode(
            "n-ver",
            "n-ver",
            NodeLifecycle.Wiring,
            role = NodeRoles.Verifier,
            in = List("n-rework"),
            out = List(OutEdge("n-rework", Set(OutEdge.Fail), OutEdge.Loop)),
            deliveredTo = List("n-rework"),
            withTask = true
          )
        )
        _ <- rt.engine.startNode("n-ver")
        _ <- waitLoopRound(ws, "n-ver")
        before <- llm.inputs.get
        _ <- waitUntil(30.seconds)(llm.inputs.get.map(_.exists(_.contains("[LoopNode rework"))))
        // 目标重跑会话已起且被持有（turn 未结束）⇒ 此刻读状态无竞态
        workLive <- rt.store.snapshot.map(_.nodes("n-rework"))
        turns <- llm.inputs.get
        _ <- gate.complete(())
        _ <- waitUntil(30.seconds)(rt.store.snapshot.map(_.nodes("n-rework").status == NodeLifecycle.Completed))
        workDone <- rt.store.snapshot.map(_.nodes("n-rework"))
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        // 前置断言：重入轮之前**没有任何**请求带返工段（该段的出现只能是重入轮带来的）
        assert(
          before.forall(!_.contains("[LoopNode rework")),
          s"precondition: no rework section may exist before the re-entry, got ${before.size} request(s)"
        )
        // 「worker 重新入轮」的可观察面 = flowmap status 回 **running**
        assertEquals(
          workLive.status,
          NodeLifecycle.Running,
          s"the re-activated target must really re-enter a round (status back to running), got ${workLive.status}"
        )
        // 返工段（round / 判词 issues / pass 判据）逐段到位；全文任务重注（设计 §3.7 选项 (i)）
        val reworkTurn = turns.find(_.contains("[LoopNode rework")).getOrElse("")
        assert(reworkTurn.contains("round 1"), s"the rework section must name the round, got: ${reworkTurn.take(400)}")
        assert(
          reworkTurn.contains("artifact does not compile"),
          s"the verifier's issues must ride the re-run input, got: ${reworkTurn.take(400)}"
        )
        assert(
          reworkTurn.contains("re-run upstream after the dependency rollback"),
          s"the verifier's requirements must ride the re-run input, got: ${reworkTurn.take(400)}"
        )
        assert(
          reworkTurn.contains("n-rework task"),
          s"the full task is re-injected alongside the rework section, got: ${reworkTurn.take(400)}"
        )
        // 放行后 worker 正常跑完 ⇒ 这是「真重跑」而不是「只改状态」
        assertEquals(
          workDone.status,
          NodeLifecycle.Completed,
          s"after the held turn is released the worker completes normally, got ${workDone.status}"
        )
    finally
      LoopProps.foreach(System.clearProperty)
    end try
  }

  // ── ②(a) 轮预算：3/3 是熔断轮，不是再派发轮 ─────────────────────────

  test(
    "② round cap: 3/3 is the CIRCUIT-BREAK round (explicit terminal + loop-budget event + no dispatch), not one more re-run"
  ) {
    val ws = tempRoot / "ws-rounds"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lround-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "再次判 fail。本节点收尾。")
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountEngineOnly("lround", ws, system, res)
        _ <- IO(System.setProperty("nebflow.nrloop.maxRounds", "3"))
        // loopRound=2 ⇒ 本次判词消费的是**第 3 轮** = 预算上限 ⇒ 必须熔断而非再派发。
        _ <- seed(
          rt,
          mkNode(
            "n-work",
            "n-work",
            NodeLifecycle.Completed,
            out = List(OutEdge("n-ver")),
            result = Some("worker round-2 output")
          ),
          failVerifier("n-ver", NodeLifecycle.Wiring, loopRound = 2, deliveredTo = List("n-work")),
          mkNode("n-land", "n-land", NodeLifecycle.Wiring)
        )
        _ <- rt.engine.startNode("n-ver")
        _ <- waitTerminal(rt, "n-ver")
        ver <- rt.store.snapshot.map(_.nodes("n-ver"))
        work <- rt.store.snapshot.map(_.nodes("n-work"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        // 显式终态（②）
        assertEquals(
          ver.status,
          NodeLifecycle.Failed,
          "budget exhaustion terminalizes the fail-route driver (visible to the dispatcher)"
        )
        assert(
          ver.result.exists(_.contains("loop budget exhausted")),
          s"result must carry the reason, got: ${ver.result}"
        )
        assert(
          ver.result.exists(_.contains("rounds=3/3")),
          s"the metering must read the consumed round 3/3, got: ${ver.result}"
        )
        assert(
          audit.exists((t, id, s) => t == "loop-budget" && id == "n-ver" && s.contains("rounds=3/3")),
          s"a loop-budget event must be logged with the consumed round, got: $audit"
        )
        // ── engine-defects 批 #239（RED 臂）：熔断**不得吞掉判词全文** ──────────────
        // 旧口径只把计量串写进 result ⇒「判词已落盘、node_report 的终止申报与结论全文
        // 丢失、结果被降级成 stub」。修后 = 计量串在前后并列原结论文本（既有
        // `[original-conclusion]` 稳定锚，与 completeNode 闸门 Reject 分支同机制）。
        val conclusion = llm.closingText
        assert(conclusion.trim.nonEmpty, "precondition: this fixture reports a real closing text")
        assert(
          ver.result.exists(_.contains(conclusion)),
          s"the circuit break MUST retain the verdict's conclusion text (not degrade the result to a metering stub), got: ${ver.result}"
        )
        assert(
          ver.result.exists(_.contains(CompletionGate.OriginalTextMarker)),
          s"the retained conclusion must carry the retrieval anchor ${CompletionGate.OriginalTextMarker}, got: ${ver.result}"
        )
        assert(
          ver.result.exists { r =>
            val m = r.indexOf("loop budget exhausted"); val a = r.indexOf(CompletionGate.OriginalTextMarker)
            m >= 0 && a >= 0 && m < a
          },
          s"the metering line must come FIRST (dispatcher-first readability; existing assertions preserved), got: ${ver.result}"
        )
        assert(
          audit.exists((t, id, s) => t == "loop-budget" && id == "n-ver" && s.contains("conclusion=retained")),
          s"the loop-budget event must record that the conclusion was retained, got: $audit"
        )
        // 不再派发该轮（旧口径在 3/3 仍派发，须等第 4 次判词才熔断）
        assertEquals(work.status, NodeLifecycle.Completed, "3/3 must NOT dispatch one more re-run")
        assert(
          !audit.exists((t, id, _) => t == "reactivated" && id == "n-work"),
          s"no re-run may be dispatched on the exhausted round, got: $audit"
        )
    finally
      LoopProps.foreach(System.clearProperty)
    end try
  }

  // ── ②(b) 熔断不被「起点缺失」静默吞掉 ───────────────────────────────

  test(
    "② no silent swallow: the round-cap circuit break terminalizes even when the target has NO loop timer to clear"
  ) {
    val ws = tempRoot / "ws-notimer"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lnotimer-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "判 fail。本节点收尾。")
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountEngineOnly("lnotimer", ws, system, res)
        _ <- IO(System.setProperty("nebflow.nrloop.maxRounds", "3"))
        // 起点缺失（= 已被孤儿扫描清掉的真实形态）⇒ 旧口径 clearLoopStartedAt 返回 false
        // ⇒ 整条熔断被跳过：不终态化、不发 loop-budget、不通知分发器（静默冻结）。
        _ <- seed(
          rt,
          mkNode(
            "n-work",
            "n-work",
            NodeLifecycle.Completed,
            out = List(OutEdge("n-ver")),
            result = Some("output"),
            loopStartedAt = None
          ),
          failVerifier("n-ver", NodeLifecycle.Wiring, loopRound = 2, deliveredTo = List("n-work")),
          mkNode("n-land", "n-land", NodeLifecycle.Wiring)
        )
        _ <- rt.engine.startNode("n-ver")
        _ <- waitTerminal(rt, "n-ver")
        ver <- rt.store.snapshot.map(_.nodes("n-ver"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(
          ver.status,
          NodeLifecycle.Failed,
          "the round cap must terminalize regardless of the timer's presence"
        )
        assert(
          audit.exists((t, id, s) => t == "loop-budget" && id == "n-ver" && s.contains("rounds=3/3")),
          s"the loop-budget event must be written (the timer is a product of the break, not its gate), got: $audit"
        )
    finally
      LoopProps.foreach(System.clearProperty)
    end try
  }

  // ── ②(c) 时间维驱动方判据 = 判词边（终态 fail-verifier 不再被当孤儿）────

  test(
    "② wall-clock driver: a COMPLETED verifier with lastVerdict=fail is still the driver (circuit-breaks); pass/no-verdict stays orphan"
  ) {
    val ws = tempRoot / "ws-wall"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lwall-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "unused")
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountEngineOnly("lwall", ws, system, res)
        _ <- IO(System.setProperty("nebflow.nrloop.maxWallClockMs", "1"))
        now = System.currentTimeMillis()
        _ <- seed(
          rt,
          // 本体：驱动方已 completed 且判词 = fail（verdict ≠ 节点状态的常态形态）
          mkNode(
            "n-work",
            "n-work",
            NodeLifecycle.Wiring,
            out = List(OutEdge("n-ver")),
            loopStartedAt = Some(now - 10_000)
          ),
          failVerifier("n-ver", NodeLifecycle.Completed, loopRound = 1, lastVerdict = Some("fail")),
          // 对照组：驱动方 completed 但判词 = pass ⇒ 不算驱动方（孤儿语义逐字保留）
          mkNode(
            "n-work2",
            "n-work2",
            NodeLifecycle.Wiring,
            out = List(OutEdge("n-ver2")),
            loopStartedAt = Some(now - 10_000)
          ),
          mkNode(
            "n-ver2",
            "n-ver2",
            NodeLifecycle.Completed,
            role = NodeRoles.Verifier,
            in = List("n-work2"),
            out = List(OutEdge("n-land"), OutEdge("n-work2", Set(OutEdge.Fail), OutEdge.Loop)),
            result = Some("passed"),
            lastVerdict = Some("pass")
          )
        )
        _ <- rt.engine.sweepLoopBudgets()
        ver <- rt.store.snapshot.map(_.nodes("n-ver"))
        ver2 <- rt.store.snapshot.map(_.nodes("n-ver2"))
        work <- rt.store.snapshot.map(_.nodes("n-work"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(
          ver.status,
          NodeLifecycle.Failed,
          "a completed fail-verifier still drives the re-run ⇒ the expired timer circuit-breaks it (was: orphan ⇒ nothing terminalized)"
        )
        assert(
          audit.exists((t, id, s) => t == "loop-budget" && id == "n-ver" && s.contains("wallClock")),
          s"the wall-clock break must be logged for the driver, got: $audit"
        )
        assertEquals(work.loopStartedAt, None, "the target's timer is cleared by the break")
        assertEquals(work.status, NodeLifecycle.Wiring, "the TARGET is not terminalized — only the driver is")
        // 对照组：判词 = pass（或无判词）的终态 verifier **不**算驱动方 ⇒ 孤儿分支逐字不变
        assertEquals(
          ver2.status,
          NodeLifecycle.Completed,
          "a pass/no-verdict terminal verifier is NOT a driver (orphan semantics unchanged)"
        )
        assert(
          audit.exists((t, id, s) => t == "loop-budget" && id == "n-work2" && s.contains("stage=orphan")),
          s"the control target must still take the orphan branch, got: $audit"
        )
    finally
      LoopProps.foreach(System.clearProperty)
    end try
  }

  // ── ③ sweep 面：判据本体在既有 merge verdict 闸（权威 = MergeVerdictGateSpec V1–V8）
  //      本用例 = 本批在 sweep 面上的**零回归对照**（既有行为逐字不变）────────────

  test(
    "③ sweep zero-regression: a plain task upstream is still healed by the orphan-barrier sweep; a fail-verifier upstream is healed as before (deliveredTo recorded) BUT its merge sink is NOT started (pre-existing verdict gate)"
  ) {
    val ws = tempRoot / "ws-sweep"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lsweep-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "unused")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountEngineOnly("lsweep", ws, system, res)
      _ <- seed(
        rt,
        // merge 收口位：barrier 只差 fail-verifier 一轨未结算（deps 空 ⇒ 唯一持有者 = 判词闸）
        mkNode("n-merge", "n-merge", NodeLifecycle.Wiring, in = List("n-ver"), withTask = true, merge = true),
        mkNode(
          "n-ver",
          "n-ver",
          NodeLifecycle.Completed,
          role = NodeRoles.Verifier,
          in = List("n-earlier"),
          result = Some("REJECTION: the artifact does not compile"),
          lastVerdict = Some("fail")
        ),
        // 对照组：普通 task 上游（合法产物）⇒ 照旧补投（无 verifier 上游的既有行为不变）
        mkNode("n-sink2", "n-sink2", NodeLifecycle.Wiring, in = List("n-task"), deps = List("n-absent")),
        mkNode("n-task", "n-task", NodeLifecycle.Completed, result = Some("REAL DELIVERABLE"))
      )
      _ <- rt.engine.settleRunnableSweep()
      m1 <- rt.store.snapshot.map(_.nodes("n-merge"))
      sink2 <- rt.store.snapshot.map(_.nodes("n-sink2"))
      audit1 <- readAudit(ws)
      _ <- rt.engine.settleRunnableSweep() *> IO.sleep(200.millis) *> rt.engine.settleRunnableSweep()
      m2 <- rt.store.snapshot.map(_.nodes("n-merge"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ③ 判据本体：fail-verifier 上游 ⇒ sweep 不得把 merge 拉起（既有权威 = merge verdict 闸）
      assertEquals(
        m1.status,
        NodeLifecycle.Wiring,
        "a merge whose in-upstream verifier judged fail must not be started by the sweep"
      )
      assert(m1.startedAt.isEmpty, "no session may be spawned for a held merge")
      assertEquals(m1.result, None, "the gate must not fabricate a result")
      assertEquals(m2.status, NodeLifecycle.Wiring, "repeated sweeps must keep holding (idempotent)")
      // 既有契约逐字不改：孤儿 barrier 自愈照旧跑、deliveredTo 记全
      // （🔴 这正是本批一度加上的「fail-verifier 一律不补投」挡投腿所打红的面，见 spec 头注）
      assert(
        m1.deliveredTo.contains("n-ver"),
        s"the sweep must still heal the orphan barrier (pre-existing contract), got: ${m1.deliveredTo}"
      )
      assert(
        audit1.exists((t, id, _) => t == "settle-sweep" && id == "n-merge"),
        s"the heal must stay traceable, got: $audit1"
      )
      // 对照组：无 verifier 上游的既有行为逐字不变（孤儿 barrier 照旧自愈补投）
      assert(
        sink2.deliveredTo.contains("n-task"),
        s"a plain completed upstream is still redelivered (no-verifier behaviour unchanged), got: ${sink2.deliveredTo}"
      )
    end for
  }
end LoopExecutionLegSpec
