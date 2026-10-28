package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.bridge.AskAnswerReceipt
import nebflow.core.project.{FlowMapEventLog, FlowMapStore, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.tools.{AskUserQuestionTool, ToolContext}
import nebflow.shared.{AskItem, AskOption}

import scala.concurrent.duration.*

/**
 * answer-landing 实施批（2026-10-21）——桥 `sessionAlive=false` 分支的三臂着陆
 * 单测（任务令质量闸：「bridge 三臂单测（活/亡/无归因）」）：
 *
 *   - 臂0（活会话，既有不变）：答复仍按 fromUser=true 注入会话（既有臂字节回归）；
 *   - 臂1（亡会话＋归因在＋项目宿主在）：答案走 `ProjectActor.AskAnswerReentry`
 *     （ReenterDispatcher 族）+ `answer-retry` 审计事件 + 渠道**确认回执行**；
 *   - 臂3（亡会话＋归因缺席 / 宿主不在）：**失败出口不静默**——渠道失败回执行
 *     「问题已作废+原因」+ 桌面 toast 帧（interactionAnswerRejected 族）+ 会话
 *     零注入。
 *
 * 另固化 [[AskAnswerReceipt]] 反向通道注册表的登记/消费/清理/有界语义（缺陷①的
 * 「向社交腿推『问题已作废+原因』」的载具）。
 */
class AskAnswerLandingSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val items = List(AskItem("继续？", List(AskOption("yes"))))

  // ───────────── harness ─────────────

  /** 捕获 ImmediateInput 的目标会话（活会话臂断言面）。 */
  private def targetCapturer(
    system: ActorSystem
  ): IO[(nebflow.actor.ActorRef[nebflow.actor.AgentCommand], Ref[IO, List[(String, Boolean)]])] =
    Ref.of[IO, List[(String, Boolean)]](Nil).flatMap { seen =>
      lazy val behavior: nebflow.actor.Behavior[nebflow.actor.AgentCommand] =
        Behaviors.receiveMessage[nebflow.actor.AgentCommand] {
          case nebflow.actor.AgentCommand.ImmediateInput(text, _, _, _, _, _, _, fromUser, _, _) =>
            seen.update(_ :+ ((text, fromUser))).as(behavior)
          case _ => IO.pure(behavior)
        }
      system.spawn(behavior, s"landing-target-${System.nanoTime()}").map(ref => (ref, seen))
    }

  /** 捕获 AskAnswerReentry 的假 ProjectActor（重入臂断言面）。 */
  private def projectActorCapturer(
    system: ActorSystem
  ): IO[
    (nebflow.actor.ActorRef[nebflow.core.project.ProjectActor.ProjectCommand], Ref[IO, List[(String, String, String)]])
  ] =
    Ref.of[IO, List[(String, String, String)]](Nil).flatMap { seen =>
      lazy val behavior: nebflow.actor.Behavior[nebflow.core.project.ProjectActor.ProjectCommand] =
        Behaviors.receiveMessage[nebflow.core.project.ProjectActor.ProjectCommand] {
          case cmd @ nebflow.core.project.ProjectActor.ProjectCommand.AskAnswerReentry(nodeId, requestId, answerText) =>
            seen.update(_ :+ ((nodeId, requestId, answerText))).as(behavior)
          case _ => IO.pure(behavior)
        }
      system.spawn(behavior, s"landing-project-${System.nanoTime()}").map(ref => (ref, seen))
    }

  /** null-容忍 SharedResources（对齐 ProjectArchiveSpec 既有构造形态）——只要 agentRegistry。 */
  private def testResources(): nebflow.agent.SharedResources =
    new nebflow.agent.SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = Ref.unsafe[IO, nebflow.shared.ThinkingConfig](nebflow.shared.ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, nebflow.llm.ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )

  /** 渠道回执记录器（登记进 AskAnswerReceipt 的 send 闭包落这里）。 */
  private final class ReceiptSink:
    val lines: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def entry(channel: String = "weixin-ilink", routeKey: String = "wx_a"): AskAnswerReceipt.Entry =
      AskAnswerReceipt.Entry(channel, routeKey, line => lines.update(_ :+ line))
    def recorded: IO[List[String]] = lines.get

  /** wsSend 捕获器（桌面 toast 断言面）。 */
  private final class WsSink:
    val frames: Ref[IO, List[Json]] = Ref.unsafe[IO, List[Json]](Nil)
    val send: Json => IO[Unit] = j => frames.update(_ :+ j)
    def recorded: IO[List[Json]] = frames.get

  /** 等待直到 `probe` 命中非空（actor 异步消化 tell 的同步化探针；上限 ~5s）。 */
  private def poll[A](probe: IO[Option[A]]): IO[A] =
    def loop(tries: Int): IO[A] =
      if tries <= 0 then IO.raiseError(new IllegalStateException("poll timeout: landing side-effect not observed"))
      else
        probe.flatMap:
          case Some(a) => IO.pure(a)
          case None => IO.sleep(50.millis) *> loop(tries - 1)
    loop(100)

  private def ctx(
    res: nebflow.agent.SharedResources,
    project: Option[String],
    node: Option[String],
    ws: Option[WsSink] = None
  ): ToolContext =
    ToolContext(
      projectRoot = "",
      sessionId = Some("node-s1"),
      sharedResources = Some(res),
      wsSend = ws.map(_.send),
      projectName = project,
      flowNodeId = node
    )

  /** 项目宿主挂载（store 带 n1 节点；actorRef = 捕获器）。返回 (store, ws)——ws 供审计文件断言。 */
  private def mountProject(
    name: String,
    nodeId: String,
    ref: nebflow.actor.ActorRef[nebflow.core.project.ProjectActor.ProjectCommand],
    res: nebflow.agent.SharedResources,
    system: ActorSystem
  ): IO[(FlowMapStore, os.Path)] =
    for
      ws <- IO(os.temp.dir(prefix = s"nb-landing-$name-"))
      store <- FlowMapStore.open(name, ws.toString)
      node = nebflow.core.project.NodeDef(
        id = nodeId,
        name = "node-a",
        agent = "nebflow",
        createdAt = System.currentTimeMillis() // NodeDef.apply 唯一无默认必填位
      )
      _ <- store.mutate(s => s.copy(nodes = s.nodes + (nodeId -> node)))
      _ <- ProjectRuntimeRegistry.register(
        ProjectRuntime(
          ProjectDef(name = name, workspace = ws.toString, agentFile = "", createdAt = 0L),
          store,
          null, // 本面只触 store/actorRef/project——engine 不在判据面（测试零触）
          system,
          res,
          Some(ref)
        )
      )
    yield (store, ws)

  override def beforeAll(): Unit =
    ProjectRuntimeRegistry.clear.unsafeRunSync() // 进程级注册表隔离（跨 suite 污染防护）

  override def afterAll(): Unit =
    ProjectRuntimeRegistry.clear.unsafeRunSync()

  // ───────────── 臂0：活会话（既有不变） ─────────────

  test("L0 会话活：答复仍按 fromUser=true 注入（既有臂字节回归）") {
    val system = ActorSystem("landing-alive")
    val res = testResources()
    for
      (target, seen) <- targetCapturer(system)
      _ <- res.agentRegistry.update(_.updated("node-s1", null)) // registry 在册 = 活
      bridge = AskUserAnswerBridge.ref(target, items, "asknb-l0", ctx(res, project = None, node = None))
      _ <- bridge ! List("yes")
      hits <- poll(seen.get.map(_.headOption)) // 注入 tell 的消化异步——poll 同步化
      _ <- system.stopAll
    yield assertEquals(hits, (AskUserQuestionTool.formatAnswer(items, List("yes")), true))
    end for
  }

  // ───────────── 臂1：亡会话＋归因在＋项目宿主在 ⇒ 引擎重入 ─────────────

  test("L1 亡会话＋归因在＋宿主在：AskAnswerReentry 重入 + answer-retry 审计 + 确认回执行") {
    val system = ActorSystem("landing-reentry")
    val res = testResources()
    for
      (target, seen) <- targetCapturer(system)
      (projRef, reentered) <- projectActorCapturer(system)
      (_, ws) <- mountProject("landing-p1", "n1", projRef, res, system)
      // 注册表无 node-s1 = 发起会话已亡
      sink = ReceiptSink()
      _ <- AskAnswerReceipt.record("asknb-l1", sink.entry())
      bridge = AskUserAnswerBridge.ref(target, items, "asknb-l1", ctx(res, Some("landing-p1"), Some("n1")))
      // deliver 全链（audit 落盘 → tell → 回执行）在本次 bind 内同步完成；
      // tell 的消化（capturer actor）异步——poll 同步化。
      _ <- bridge ! List("yes")
      re <- poll(reentered.get.map(_.headOption))
      rc <- sink.recorded
      // answer-retry 审计事件落 FlowMapEventLog（`<ws>/.nebflow/flow-map-events.jsonl`；
      // 带闭引号的子串匹配，不误吞 answer-retry-failed 行）
      audit <- IO
        .blocking(
          os.read
            .lines(ws / ".nebflow" / FlowMapEventLog.FileName)
            .filter(_.contains("\"type\":\"answer-retry\""))
        )
        .handleErrorWith(_ => IO.pure(Nil))
      inj <- seen.get
      _ <- system.stopAll
    yield
      // 目标会话零注入（会话已亡，注入无意义）
      assertEquals(inj.size, 0, s"亡会话臂不得注入目标：$inj")
      // 重入指令到达（ReenterDispatcher 族）：nodeId/requestId/答案全文
      assertEquals(re, ("n1", "asknb-l1", AskUserQuestionTool.formatAnswer(items, List("yes"))))
      // 渠道确认回执（「社交腿零通知」修复面）
      assertEquals(rc.size, 1, s"确认回执恰一条：$rc")
      assert(rc.head.startsWith("已收到"), s"确认回执开场不符：${rc.headOption}")
      assert(rc.head.contains("引擎重入") && rc.head.contains("node-a"), s"确认回执缺重入语义：$rc")
      // answer-retry 审计事件落 FlowMapEventLog
      assertEquals(audit.nonEmpty, true, "answer-retry 审计事件未落")
    end for
  }

  // ───────────── 臂3：归因缺席 / 宿主不在 ⇒ 失败出口不静默 ─────────────

  test("L2 归因缺席：失败回执「问题已作废」+ 桌面 toast + 零注入") {
    val system = ActorSystem("landing-noattr")
    val res = testResources()
    for
      (target, seen) <- targetCapturer(system)
      sink = ReceiptSink()
      ws = WsSink()
      _ <- AskAnswerReceipt.record("asknb-l2", sink.entry())
      bridge = AskUserAnswerBridge.ref(target, items, "asknb-l2", ctx(res, project = None, node = None, ws = Some(ws)))
      // 失败出口全链（WARN/审计/toast/回执）在 deliver 的同一 IO 链内同步完成
      _ <- bridge ! List("yes")
      hits <- seen.get
      rc <- sink.recorded
      toasts <- ws.recorded
      _ <- system.stopAll
    yield
      assertEquals(hits.size, 0, "归因缺席臂不得注入")
      assertEquals(rc.size, 1, s"失败回执缺失：$rc")
      assert(rc.head.contains("问题已作废"), s"回执缺作废语义：${rc.head}")
      assert(rc.head.contains("归因缺席"), s"回执缺原因：${rc.head}")
      val toastTypes = toasts.flatMap(_.hcursor.downField("type").as[String].toOption)
      assertEquals(toastTypes, List("interactionAnswerRejected"), s"桌面 toast 帧缺失：$toasts")
    end for
  }

  test("L3 宿主不在（项目未挂载、无委托宿主）：失败回执 + toast + 零注入") {
    val system = ActorSystem("landing-nohost")
    val res = testResources()
    for
      (target, seen) <- targetCapturer(system)
      sink = ReceiptSink()
      ws = WsSink()
      _ <- AskAnswerReceipt.record("asknb-l3", sink.entry())
      bridge = AskUserAnswerBridge.ref(
        target,
        items,
        "asknb-l3",
        ctx(res, Some("no-such-project"), Some("n9"), Some(ws))
      )
      _ <- bridge ! List("yes")
      hits <- seen.get
      rc <- sink.recorded
      toasts <- ws.recorded
      _ <- system.stopAll
    yield
      assertEquals(hits.size, 0, "宿主不在臂不得注入")
      assertEquals(rc.size, 1, s"失败回执缺失：$rc")
      assert(rc.head.contains("问题已作废") && rc.head.contains("宿主不在"), s"回执语义不符：${rc.head}")
      val toastTypes = toasts.flatMap(_.hcursor.downField("type").as[String].toOption)
      assertEquals(toastTypes, List("interactionAnswerRejected"))
    end for
  }

  // ───────────── decide 纯函数判据（单点分派表固化） ─────────────

  test("L4 decide 判据表：三臂 + 宿主缺席各分支（按形状分派，不与文案全等）") {
    import AskAnswerLanding.*
    import AskAnswerLanding.Landing.*
    def shapeOf(l: Landing): String = l match
      case ProjectReentry => "ProjectReentry"
      case DelegateInjection => "DelegateInjection"
      case Fail(_) => "Fail"
    // 归因缺席 ⇒ 失败出口（原因文案由 land 端到端面承载，L2 断言其内容）
    assertEquals(shapeOf(decide(false, false, false, false)), "Fail")
    // 臂1：节点在项目图内 + 宿主带指令面（图内分派只看 projectActorPresent，
    // delegateHostAlive 不参与——land 的图内调用点恒传 false）
    assertEquals(shapeOf(decide(true, true, true, true)), "ProjectReentry")
    assertEquals(shapeOf(decide(true, true, true, false)), "ProjectReentry")
    // 图内但 actorRef 缺席 ⇒ 失败（decide 仅凭布尔表态；文案分派在 land 单点）
    assertEquals(shapeOf(decide(true, true, false, true)), "Fail")
    // 臂2：节点不在项目图（委托作用域）+ 委托宿主活
    assertEquals(shapeOf(decide(true, false, false, true)), "DelegateInjection")
    assertEquals(shapeOf(decide(true, false, false, false)), "Fail")
    // 归因缺席的 Fail 确实是 Fail 分支（非对象臂）
    decide(false, false, false, false) match
      case Fail(_) => ()
      case other => fail(s"归因缺席必须判 Fail，实际：$other")
  }

  // ───────────── AskAnswerReceipt 反向通道注册表 ─────────────

  test("R1 record/take/peek/drop 语义：登记→peek 只读→take 原子消费→drop 幂等") {
    val sink = ReceiptSink()
    for
      _ <- AskAnswerReceipt.record("r1", sink.entry())
      p1 <- AskAnswerReceipt.peek("r1")
      _ <- AskAnswerReceipt.drop("r1")
      p2 <- AskAnswerReceipt.peek("r1")
      t1 <- AskAnswerReceipt.take("r1")
      _ <- AskAnswerReceipt.record("r1", sink.entry())
      t2 <- AskAnswerReceipt.take("r1")
      t3 <- AskAnswerReceipt.take("r1")
    yield
      assertEquals(p1.map(_.channel), Some("weixin-ilink"))
      assertEquals(p2, None, "drop 后 peek 必须为空")
      assertEquals(t1, None, "drop 后 take 必须为空")
      assertEquals(t2.map(_.routeKey), Some("wx_a"))
      assertEquals(t3, None, "take 消费即弃（登记→消费 1:1）")
    end for
  }

  test("R2 有界：容量溢出逐出最早登记项（零定时器自清理）") {
    val sink = ReceiptSink()
    for
      _ <- (0 until AskAnswerReceipt.MaxEntries + 8).toList.traverse_(i =>
        AskAnswerReceipt.record(s"cap-$i", sink.entry(routeKey = s"k-$i"))
      )
      size <- AskAnswerReceipt.size
      oldestKept <- AskAnswerReceipt.peek("cap-0")
      newestKept <- AskAnswerReceipt.peek(s"cap-${AskAnswerReceipt.MaxEntries + 7}")
    yield
      assertEquals(size <= AskAnswerReceipt.MaxEntries, true, s"容量未封顶：$size")
      assertEquals(oldestKept, None, "最早登记项应被逐出")
      assertEquals(newestKept.map(_.routeKey), Some(s"k-${AskAnswerReceipt.MaxEntries + 7}"))
    end for
  }

  test("R3 sendLine：无登记只 WARN 返回 false；登记在场送达并消费") {
    for
      ok0 <- AskAnswerReceipt.sendLine("no-such-rid", "x")
      sink = ReceiptSink()
      _ <- AskAnswerReceipt.record("has-rid", sink.entry())
      ok1 <- AskAnswerReceipt.sendLine("has-rid", "回执行")
      rc <- sink.recorded
      gone <- AskAnswerReceipt.peek("has-rid")
    yield
      assertEquals(ok0, false)
      assertEquals(ok1, true)
      assertEquals(rc, List("回执行"))
      assertEquals(gone, None, "sendLine 默认消费（take）")
    end for
  }

end AskAnswerLandingSpec
