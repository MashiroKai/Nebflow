package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.ModelCandidate
import nebflow.shared.{
  ContentBlock,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  PathUtil,
  StreamChunk,
  ThinkingConfig,
  ToolCall
}

import scala.concurrent.duration.*

/**
 * 申报消费的原子化/补偿（engine-defects 批 #239①，2026-09-15）——**面②** 的回归钉：
 * `NodeStarter.scala` 桥终态点 `drain(sessionId) → completeNode(...)`（2026-09-25 H 步
 * 重钉：随启动簇 runWithAgent 自 `NodeEngine.scala` 迁至 NodeStarter，行为保持重构），
 * `drain` 取走即移除
 * （`NodeReportRegistry` 头注「drain 即移除」），而终态写有多条**拒写**路径（节点已消失 /
 * 状态已变＝R2 fresh-read 竞态纪律）⇒ 旧口径下**拒写即申报永久丢失、无补偿写回**。
 *
 * 本 spec 走**真实引擎路径**（NodeBlockedToolSignalSpec 同款基建：真 store / 真桥 / 真
 * `node_report` 工具通道 / 假 LLM），在「工具申报已登记」与「桥终态写」之间插入**拒写
 * 前置条件**（stub LLM 在见到工具回执后的收尾 turn 之前执行 hook：删除本节点 / 改其状态），
 * 使终态写必然走到拒写支：
 *
 *  ① 拒写 + 旧码：`drain` 已把申报取走 ⇒ 磁盘、事件流、内存三处**零副本**（本 spec 的
 *     断言面 = 申报全文必须在 `flow-map-events.jsonl` 里有补偿行；旧码无此行 ⇒ 红）。
 *  ② 拒写 + 新码：`completeNodeR` 回报 `landed=false` ⇒ `compensateUnconsumedReport`
 *     把申报全文（category/detail/suggestion）+ 拒写事实写回审计流
 *     （type=`node-report-unconsumed`）⇒ 申报可 grep 取回，不再「永久丢失」。
 *  ③ 对照（防误报）：终态写**落地**的正常路径**不得**出现该行——钉住「这行 = 拒写」
 *     而不是「有申报就写一行」。
 *
 * **消费点改挂 loop verify 腿（no-blocked-semantics 一期随改）**：普通节点的桥终态点
 * 已改为**直接投递最终文本**（main 1bfc09d4a「Forward node completion payload
 * directly」——plain 节点完成不再需要申报，也不在终态点 drain 登记表），申报消费 +
 * 补偿面现存唯一点 = **loop verify 腿**（`handleVerify` 的 pass 支挂 `completeNodeR`
 * 落地判词 / fail 支挂 `setVerdictR` 落地判词）。本 spec 三臂据此改用 loop 节点驱动：
 * worker turn 产普通文本，verify turn 先发 `node_report` 工具调用（登记申报）、见到
 * 回执后执行一次性 hook（制造拒写条件 / 或零 hook 落地对照），再输出 verdict 锚定文本。
 * 申报类别 = `pass` / `fail`（verifier 值域；blocked 类别已随终态退役在工具入参层即拒，
 * 登记 zero-residual 面归 NodeBlockedToolSignalSpec）。
 *
 * 事件类型串在本 spec 内**用字面量**（不引用 `NodeEngine.ReportUnconsumedEventType`）：
 * 本文件必须能在**旧主源码**上编译（双向红绿钉的旧码臂要求）。
 */
class NodeReportConsumptionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-report-consumption"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)

  for agent <- List("test-agent", "project-dispatcher", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"report consumption regression agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /**
   * 拒写驱动 LLM（loop verify 腿形态）：worker turn → 普通产出文本；verify turn →
   * 首 turn 发 `node_report(category)` 工具调用（登记申报），见到回执后**先执行
   * 一次性 hook**（制造申报消费点的拒写条件；对照臂 hook = 零动作），再输出
   * verdict 锚定文本。多轮 loop 下 hook 只执行一次（单发旗标）；后续 verify 轮
   * 重新发工具调用（新会话新槽）——预算帽（maxRounds）保证有限轮收敛。
   */
  private class LoopRefusalLlm(
    category: String,
    detail: String,
    suggestion: String,
    verdictText: String,
    hook: Ref[IO, IO[Unit]],
    hookRan: Ref[IO, Boolean]
  ):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    private def ackText: String =
      if nebflow.core.tools.NodeReportToolDef.isFinish(category) then s"[OK] node report ($category) recorded"
      else s"[OK] verdict recorded ($category)"

    private def isVerifyTurn(req: LlmRequest): Boolean =
      req.messages.lastOption.exists(_.textContent.contains("[LoopNode verification"))

    private def sawDeclarationAck(req: LlmRequest): Boolean =
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
        if !isVerifyTurn(req) then
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
        else if sawDeclarationAck(req) then
          // 回执已见 ⇒ 先执行一次性 hook（拒写前置条件 / 对照零动作），再出 verdict 文本。
          Stream.eval(hookRan.modify(prev => (true, prev)).flatMap { already =>
            val runHook = if already then IO.unit else hook.get.flatten.attempt.void *> hookRan.set(true)
            runHook
          }) >> Stream(StreamChunk.TextDelta(verdictText), StreamChunk.Done(None, None))
        else
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(
              StreamChunk.ToolCallChunk(
                ToolCall(
                  "lrc-1",
                  "node_report",
                  JsonObject(
                    "category" -> category.asJson,
                    "detail" -> detail.asJson,
                    "suggestion" -> suggestion.asJson
                  )
                )
              ),
              StreamChunk.Done(Some("tool_use"), None)
            )

      end sendStream

    end handle

  end LoopRefusalLlm

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("report-consumption-sid"),
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

  // 建位期声明闸（nodegate 批 0accce90e「四项④」NODE_PLUGINS_UNDECLARED，判据源
  // NodeEditTool.scala:115）：本 spec 三处调用**全为建位**（`nrc-vanish` / `nrc-status` /
  // `nrc-landed`）⇒ 补 `plugins=[]`（显式「无需能力面」），与同批 18 个既有 spec 同形
  // （先例：NodeEdgeRepairSpec.scala:146 / NodeBlockedToolSignalSpec.scala:174）。
  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(
      ("project" -> Json
        .fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: ("plugins" -> Json.arr()) :: extra.toList*
    )

  /** 引擎挂载（无 ProjectActor：无 TTL 扫描腿干扰，终态写点即唯一行为面）+ WS 事件捕获。 */
  private def mountEngineOnly(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources
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
        // 本 spec 的主题是**终态写拒写**下的申报去向（非 hold 腿）⇒ 显式关腿 2，
        // 让会话完成即放行到终态点（hold 腿行为由 NodeReportReminderSpec 覆盖）。
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
    yield (rt, events)

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None => fail(s"node '$name' must exist")
    }

  private def nodeByName(rt: ProjectRuntime, name: String): IO[Option[NodeDef]] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name))

  /** 审计流原始行（type / nodeId / summary 三元组；缺失文件 → Nil）。 */
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

  /** 有界轮询审计流（终态化是异步的）：命中返回该行；超时 None（断言侧给明确文案）。 */
  private def auditLineWithin(ws: os.Path, timeout: FiniteDuration)(
    pred: ((String, String, String)) => Boolean
  ): IO[Option[(String, String, String)]] =
    def go(deadline: Long): IO[Option[(String, String, String)]] =
      readAudit(ws).flatMap { lines =>
        lines.find(pred) match
          case Some(hit) => IO.pure(Some(hit))
          case None =>
            if System.currentTimeMillis() >= deadline then IO.pure(None)
            else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private val UnconsumedType = "node-report-unconsumed"

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ══ ① 拒写支 A：终态写时**节点已消失**（store 里已无该节点）══════════════════
  // 复现路径（loop verify pass 支）：工具申报已登记 → 节点在 `handleVerify` 消费申报、
  // 走 `completeNodeR` 之前从 flow-map 消失（NodeEdit 删除 / 外部重载）⇒
  // `completedNodeR` 的 `case None` 拒写支 ⇒ `landed=false` ⇒ 补偿行带走申报全文。
  // 旧码：申报被 drain 取走且**全系统零副本**（无痕迹）⇒ 本 spec 红。
  // 申报类别 = `pass`（verifier 值域合法值；blocked 类别已随终态退役在工具入参层即拒）。

  test(
    "refused terminal write (node vanished): the drained node_report is compensated into the audit log — never silently lost"
  ) {
    val ws = tempRoot / "ws-refuse-vanish"
    os.makeDir.all(ws)
    val nodeName = "vanish-a"
    val system = ActorSystem(s"nrc-vanish-${scala.util.Random.nextInt(100000)}")
    val detail = "已完成约定交付（vanish 臂）"
    val suggestion = "无需重派（vanish 臂）"
    val hook = Ref.unsafe[IO, IO[Unit]](IO.unit)
    val hookRan = Ref.unsafe[IO, Boolean](false)
    val vanishedId = Ref.unsafe[IO, Option[String]](None)
    val llm = new LoopRefusalLlm("pass", detail, suggestion, "VERDICT: PASS", hook, hookRan)
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, _) <- mountEngineOnly("nrc-vanish", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 拒写前置条件（在**申报已登记之后、消费点终态写之前**执行）：把本节点从 flow-map 删除
      _ <- hook.set(rt.store.snapshot.flatMap { s =>
        s.nodes.values.find(_.name == nodeName) match
          case Some(n) => vanishedId.set(Some(n.id)) *> rt.store.mutate(st => st.copy(nodes = st.nodes - n.id)).void
          case None => IO.unit
      })
      _ <- nodeEdit(
        nodeInput(
          "nrc-vanish",
          nodeName,
          "description" -> Json.fromString("loop node purpose"),
          "task" -> Json.fromString("will-report-pass-then-vanish"),
          "loop" -> Json.fromBoolean(true),
          "maxRounds" -> Json.fromInt(2),
          "verify" -> Json.fromString("general"),
          "out" -> Json.fromString("(pass,failed)Nebula")
        ),
        ctx
      )
      _ <- waitUntil(30.seconds)(hookRan.get)
      line <- auditLineWithin(ws, 20.seconds) { case (t, _, s) =>
        t == UnconsumedType && s.contains(detail)
      }
      audit <- readAudit(ws)
      nid <- vanishedId.get
      after <- nodeByName(rt, nodeName)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(after, None, "precondition: the node must be gone from the flow-map (refusal arm A)")
      assert(
        line.isDefined,
        s"the drained declaration MUST NOT vanish with the refused terminal write — expected a '$UnconsumedType' " +
          s"audit line carrying the payload; got audit lines: ${audit.map(l => (l._1, l._3))}"
      )
      val (typ, lineNodeId, summary) = line.get
      assertEquals(typ, UnconsumedType)
      assertEquals(
        lineNodeId,
        nid.getOrElse(fail("the hook must have captured the vanished node id")),
        "the compensation line must be attributed to the very node whose terminal write refused"
      )
      assert(
        summary.contains("category=pass"),
        s"payload category must be preserved verbatim, got: $summary"
      )
      assert(
        summary.contains(s"detail=$detail"),
        s"payload detail must be preserved verbatim (untruncated), got: $summary"
      )
      assert(
        summary.contains(s"suggestion=$suggestion"),
        s"payload suggestion must be preserved verbatim, got: $summary"
      )
      assert(summary.contains("refused"), s"the refusal cause must be readable, got: $summary")
    end for
  }

  // ══ ② 拒写支 B：判词写时**节点已非可写 loop 实体**（fresh-read 纪律的正面）═══════
  // 复现路径（loop verify fail 支）：工具申报已登记 → 节点的 loop 配置被摘除（模拟
  // 并发拓扑改写竞态）⇒ `setVerdictR` 的「非 loop 节点」no-op 拒写支 ⇒ `landed=false`
  // ⇒ 补偿行带走申报全文，且判词**不得**落在被改写的节点上（不覆盖并发赢家）。
  // 预算帽（maxRounds=2）保证有限轮收敛：拒写轮耗尽预算 ⇒ 引擎熔断 failed（既有
  // LoopBudget 兜底链）。申报类别 = `fail`（verifier verdict 面）。

  test(
    "refused verdict write (loop config gone): the drained node_report is compensated AND the verdict never lands on the rewritten node"
  ) {
    val ws = tempRoot / "ws-refuse-verdict"
    os.makeDir.all(ws)
    val nodeName = "verdict-a"
    val system = ActorSystem(s"nrc-verdict-${scala.util.Random.nextInt(100000)}")
    val detail = "验收未过：交付物缺规格段（verdict 臂）"
    val suggestion = "补规格段后重跑（verdict 臂）"
    val hook = Ref.unsafe[IO, IO[Unit]](IO.unit)
    val hookRan = Ref.unsafe[IO, Boolean](false)
    val llm = new LoopRefusalLlm("fail", detail, suggestion, "VERDICT: FAIL", hook, hookRan)
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, _) <- mountEngineOnly("nrc-verdict", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 拒写前置条件：把节点的 loop 配置摘除（loop=None ⇒ setVerdictR 判「非 loop 节点」拒写）
      _ <- hook.set(rt.store.snapshot.flatMap { s =>
        s.nodes.values.find(_.name == nodeName) match
          case Some(n) =>
            rt.store
              .mutate(st => st.copy(nodes = st.nodes.updated(n.id, n.copy(loop = None))))
              .void
          case None => IO.unit
      })
      _ <- nodeEdit(
        nodeInput(
          "nrc-verdict",
          nodeName,
          "description" -> Json.fromString("loop node purpose"),
          "task" -> Json.fromString("will-report-fail-verdict"),
          "loop" -> Json.fromBoolean(true),
          "maxRounds" -> Json.fromInt(2),
          "verify" -> Json.fromString("general"),
          "out" -> Json.fromString("(pass,failed)Nebula")
        ),
        ctx
      )
      _ <- waitUntil(30.seconds)(hookRan.get)
      line <- auditLineWithin(ws, 20.seconds) { case (t, _, s) =>
        t == UnconsumedType && s.contains(detail)
      }
      _ <- waitUntil(30.seconds) {
        nodeByName(rt, nodeName).map(_.exists(n => NodeLifecycle.Terminal.contains(n.status)))
      }
      audit <- readAudit(ws)
      after <- nodeByName(rt, nodeName)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val n = after.getOrElse(fail("node must still exist in this arm"))
      assertEquals(
        n.loopLastVerdict,
        None,
        "the refused verdict write must NOT land loopLastVerdict (R2 discipline preserved — no clobber)"
      )
      assertEquals(n.blockedFeedback, None, "no structured feedback may land (retired face)")
      assertEquals(n.blockCount, 0, "no blocked round counting may happen (retired face)")
      assert(
        line.isDefined,
        s"the drained declaration MUST NOT vanish with the refused verdict write — expected a '$UnconsumedType' " +
          s"audit line carrying the payload; got audit lines: ${audit.map(l => (l._1, l._3))}"
      )
      val (_, lineNodeId, summary) = line.get
      assertEquals(lineNodeId, n.id)
      assert(summary.contains("category=fail"), s"payload category must be preserved, got: $summary")
      assert(summary.contains(s"detail=$detail"), s"payload detail must be preserved verbatim, got: $summary")
      assert(
        summary.contains(s"suggestion=$suggestion"),
        s"payload suggestion must be preserved verbatim, got: $summary"
      )
      assert(summary.contains("refused"), s"the refusal cause must be readable, got: $summary")
      // 预算帽收敛：拒写轮耗尽预算 ⇒ 引擎熔断 failed（既有 LoopBudget 兜底链，非 blocked）
      assertEquals(
        n.status,
        NodeLifecycle.Failed,
        s"budget exhaustion must terminate via the engine's failed chain, got: ${n.status}"
      )
    end for
  }

  // ══ ③ 对照臂（防误报）：终态写**落地** ⇒ 零补偿行 ══════════════════════════════
  // 钉住「这行 = 拒写」，不是「有申报就写一行」——本轮修法不得给正常路径加噪音。
  // 申报类别 = `pass`（loop verify pass 支正常落地形态；blocked 类别已随终态退役，
  // 落地对照不再可能有 blocked 形态——PASS 即 completed 原链投递 worker 产出）。

  test(
    "control: a pass declaration consumed by a LANDED terminal write emits no compensation line"
  ) {
    val ws = tempRoot / "ws-landed"
    os.makeDir.all(ws)
    val nodeName = "landed-a"
    val system = ActorSystem(s"nrc-landed-${scala.util.Random.nextInt(100000)}")
    val detail = "验收通过：交付物齐备（对照臂）"
    val suggestion = "无需重派（对照臂）"
    val hook = Ref.unsafe[IO, IO[Unit]](IO.unit)
    val hookRan = Ref.unsafe[IO, Boolean](false)
    val llm = new LoopRefusalLlm("pass", detail, suggestion, "VERDICT: PASS", hook, hookRan)
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, _) <- mountEngineOnly("nrc-landed", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(
        nodeInput(
          "nrc-landed",
          nodeName,
          "description" -> Json.fromString("loop node purpose"),
          "task" -> Json.fromString("will-report-pass-and-land"),
          "loop" -> Json.fromBoolean(true),
          "maxRounds" -> Json.fromInt(2),
          "verify" -> Json.fromString("general"),
          "out" -> Json.fromString("(pass,failed)Nebula")
        ),
        ctx
      )
      _ <- waitUntil(30.seconds) {
        nodeByName(rt, nodeName).map(_.exists(_.status == NodeLifecycle.Completed))
      }
      // 终态写之后再看审计流：给补偿腿（若有）充分机会出现
      _ <- IO.sleep(500.millis)
      audit <- readAudit(ws)
      b <- nodeByName(rt, nodeName)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val n = b.getOrElse(fail("node must exist"))
      assertEquals(n.status, NodeLifecycle.Completed, "the declared pass verdict must land via the normal completed chain")
      assertEquals(n.result, Some("ok"), "PASS delivers the worker's final output (loop §2.2 裁定建议 a)")
      assertEquals(
        n.blockedFeedback,
        None,
        "no structured feedback may land (blocked fields are legacy-stock only)"
      )
      assert(
        !audit.exists(l => l._1 == UnconsumedType),
        s"a LANDED terminal write must not emit a compensation line (false-positive guard); got: ${audit.filter(_._1 == UnconsumedType)}"
      )
      assert(
        !audit.exists(l => l._1 == "blocked" && l._2 == n.id),
        s"no blocked audit line may ever be written (blocked terminal retired), got: ${audit.filter(l => l._1 == "blocked" && l._2 == n.id)}"
      )
    end for
  }
end NodeReportConsumptionSpec
