package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 「out 可空置 + 接线即投递」批（2026-09-12 裁定 1/3/4）根会话可见面回归。
 *
 * 覆盖（本批 A1／A3／A7／A8 的**行为**面，与既有 NodeConnectionPolicySpec 的**落边**面互补）：
 *  - ①D bare `"Nebula"` = 纯出口标记：节点完成 ⇒ **零 root 投递**，但持久记账位已置
 *       （nebulaDeliveredAt 非空 ⇒ 补投扫描不会把它当欠账）；
 *  - ①E 显式门集 `"(pass,failed)Nebula"` = 通知声明：恰 1 条 ImmediateInput（能力保留）；
 *  - ③D 空 out 创建合法：节点完成 ⇒ 零投递、零升根（绝不默认 Nebula），结果保留在 result；
 *       且创建回执尾部带悬空提示（W1，A8）；
 *  - ④D 悬空 completed 经**路径 B**（下游 `in:` 声明）接线 ⇒ 补投递（接线即投递第二路）；
 *  - ⑦D `NodePayload.wiringGap` 条件键（A7）：空 out ⇒ pending/retained；有 out ⇒ 缺键。
 */
class OutNullableDeliverySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-out-nullable"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private class CaptureLlm(reply: String = "ok"):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream
          .eval(inputs.update(_ :+ req.messages.map(_.textContent).mkString("\n")))
          .flatMap(_ => Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None)))

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
      sessionId = Some("outnull-sid"),
      rootSessionId = Some("outnull-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

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

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "outnull-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None    => fail(s"node '$name' must exist")
    }

  private def nodeById(rt: ProjectRuntime, id: String): IO[Option[NodeDef]] =
    rt.store.snapshot.map(_.nodes.get(id))

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(15.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  /** 根会话记录器（收 ImmediateInput 的真实终点）+ root 会话登记。 */
  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    lazy val b: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def registerRoot(res: SharedResources, system: ActorSystem, recorded: Ref[IO, List[AgentCommand]]): IO[Unit] =
    system.spawn(recorderBehavior(recorded), s"outnull-root-${scala.util.Random.nextInt(100000)}").flatMap { ref =>
      res.agentRegistry.update(_ + ("outnull-root" -> AgentRecord("outnull-root", ref, AgentKind.Root, "outnull-root")))
    }

  private def imms(recorded: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })

  private def awaitImms(recorded: Ref[IO, List[AgentCommand]], min: Int, timeoutMs: Long = 10_000L): IO[List[AgentCommand.ImmediateInput]] =
    def go(deadline: Long): IO[List[AgentCommand.ImmediateInput]] =
      imms(recorded).flatMap { ms =>
        if ms.size >= min || System.currentTimeMillis() >= deadline then IO.pure(ms)
        else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── ①D bare "Nebula" = 出口标记：零投递 + 记账置位 ─────────

  test("①D bare \"Nebula\" exit marker: completion delivers NOTHING to root, yet the ledger is stamped") {
    val ws = tempRoot / "ws-exit"
    os.makeDir.all(ws)
    val system = ActorSystem(s"outnull-exit-${scala.util.Random.nextInt(100000)}")
    val llm = new CaptureLlm("exit-marker result")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- registerRoot(res, system, recorded)
      rt <- mountProject("outnull-exit", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("outnull-exit", "exit-node", "description" -> Json.fromString("exit marker node"),
        "task" -> Json.fromString("finish through the marker"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "exit-node", Set(NodeLifecycle.Completed))
      node <- idOf(rt, "exit-node").flatMap(nodeById(rt, _))
      _ <- IO.sleep(500.millis)
      msgs <- imms(recorded)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"exit-marker create must pass, got: $r")
      assertEquals(node.map(_.out), Some(List(OutEdge(OutEdge.NebulaTarget, Set(OutEdge.Pass), OutEdge.Signal))),
        "bare \"Nebula\" must persist as {pass}/signal")
      assert(msgs.isEmpty, s"exit marker must deliver NOTHING to root (默认不升根), got: ${msgs.map(_.text)}")
      assert(node.flatMap(_.nebulaDeliveredAt).isDefined,
        "the ledger must still be stamped — otherwise the 30s redelivery scan would treat the corner as unpaid")
  }

  // ── ①E 显式门集 = 通知声明：恰 1 条投递 ───────────────────

  test("①E explicit gate set \"(pass,failed)Nebula\" keeps the notify capability: exactly one root delivery") {
    val ws = tempRoot / "ws-notify"
    os.makeDir.all(ws)
    val system = ActorSystem(s"outnull-notify-${scala.util.Random.nextInt(100000)}")
    val llm = new CaptureLlm("explicit notify result")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- registerRoot(res, system, recorded)
      rt <- mountProject("outnull-notify", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("outnull-notify", "notify-node", "description" -> Json.fromString("notify node"),
        "task" -> Json.fromString("report to root"), "out" -> Json.fromString("(pass,failed)Nebula")), ctx)
      _ <- waitStatus(rt, "notify-node", Set(NodeLifecycle.Completed))
      msgs <- awaitImms(recorded, min = 1)
      _ <- IO.sleep(300.millis)
      all <- imms(recorded)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"explicit-gate notify create must pass, got: $r")
      assertEquals(msgs.size, 1, s"explicit gate set must deliver exactly once, got: ${msgs.map(_.text)}")
      assert(msgs.head.text.contains("explicit notify result"), "delivery carries the node result")
      assertEquals(all.size, 1, "no duplicate delivery")
  }

  // ── ③D 空 out 创建合法：零投递、零升根、结果保留 ───────────

  test("③D empty out create is legal: completion delivers nothing, no root notify, result retained (dangling hint in receipt)") {
    val ws = tempRoot / "ws-empty"
    os.makeDir.all(ws)
    val system = ActorSystem(s"outnull-empty-${scala.util.Random.nextInt(100000)}")
    val llm = new CaptureLlm("retained dangling result")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- registerRoot(res, system, recorded)
      rt <- mountProject("outnull-empty", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("outnull-empty", "dangling-node", "description" -> Json.fromString("dangling node"),
        "task" -> Json.fromString("produce a retained result")), ctx)
      _ <- waitStatus(rt, "dangling-node", Set(NodeLifecycle.Completed))
      node <- idOf(rt, "dangling-node").flatMap(nodeById(rt, _))
      _ <- IO.sleep(500.millis)
      msgs <- imms(recorded)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"empty-out create must be LEGAL (out nullable), got: $r")
      assert(r.exists(_.contains("NO out edge")), s"receipt must carry the dangling hint (W1), got: $r")
      assertEquals(node.map(_.out), Some(Nil), "no edge is fabricated")
      assertEquals(node.map(_.status), Some(NodeLifecycle.Completed), "the node still runs to completion")
      assert(node.flatMap(_.result).exists(_.nonEmpty), "result is retained on the node")
      assert(msgs.isEmpty, s"空 out 绝不默认 Nebula：no root delivery (got: ${msgs.map(_.text)})")
      assert(node.flatMap(_.nebulaDeliveredAt).isEmpty, "no ledger stamp (there is no Nebula edge at all)")
  }

  // ── ④D 悬空 completed 经路径 B（下游 in:）接线 ⇒ 补投递 ─────

  test("④D dangling completed node wired by a downstream 'in:' declaration (path B) → retained result delivered") {
    val ws = tempRoot / "ws-pathb"
    os.makeDir.all(ws)
    val system = ActorSystem(s"outnull-pathb-${scala.util.Random.nextInt(100000)}")
    val llm = new CaptureLlm("dangling upstream result")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- registerRoot(res, system, recorded)
      rt <- mountProject("outnull-pathb", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // A：悬空入口（task，无 out）→ 完成（结果滞留）
      _ <- nodeEdit(nodeInput("outnull-pathb", "A-悬空", "description" -> Json.fromString("dangling producer"),
        "task" -> Json.fromString("produce")), ctx)
      _ <- waitStatus(rt, "A-悬空", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "A-悬空")
      // B：声明 in=[A] ⇒ 镜像接线 + 补投递（路径 B；判据 = result ∧ 非 blocked ∧ Terminal）
      rB <- nodeEdit(nodeInput("outnull-pathb", "B-下游", "description" -> Json.fromString("consumer"),
        "task" -> Json.fromString("consume"), "in" -> Json.fromString(aId)), ctx)
      bId <- idOf(rt, "B-下游")
      _ <- waitUntil(10.seconds)(nodeById(rt, bId).map(_.exists(_.deliveredTo.contains(aId))))
      b <- nodeById(rt, bId)
      a <- nodeById(rt, aId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rB.isRight, s"downstream in: declaration must pass, got: $rB")
      assertEquals(a.map(_.out), Some(List(OutEdge(bId))), "the in: declaration mirrors a default pass edge onto A.out")
      assert(b.exists(_.deliveredTo.contains(aId)), s"B must receive A's retained result (path B), got ${b.map(_.deliveredTo)}")
  }

  // ── ⑦D wiringGap 条件键（A7）───────────────────────────

  test("⑦D NodePayload.wiringGap: empty out ⇒ pending/retained key; nodes with an out edge carry no key") {
    val now = System.currentTimeMillis()
    def node(id: String, out: List[OutEdge], status: String, result: Option[String]) =
      NodeDef(id = id, name = id, agent = "general", out = out, status = status, result = result, createdAt = now)
    val wiringEmpty = NodePayload.buildNodeJson(node("n-w", Nil, NodeLifecycle.Wiring, None), now)
    val completedEmpty = NodePayload.buildNodeJson(node("n-c", Nil, NodeLifecycle.Completed, Some("retained")), now)
    val withOut = NodePayload.buildNodeJson(node("n-o", List(OutEdge("n-x")), NodeLifecycle.Wiring, None), now)
    assertEquals(wiringEmpty.hcursor.get[String]("wiringGap").toOption, Some("pending"),
      "empty out + wiring ⇒ wiringGap=pending")
    assertEquals(completedEmpty.hcursor.get[String]("wiringGap").toOption, Some("retained"),
      "empty out + result ⇒ wiringGap=retained (more actionable than pending)")
    assert(withOut.hcursor.get[String]("wiringGap").isLeft, "缺键 = 有 out（有出边节点字段集零漂移）")
  }

end OutNullableDeliverySpec
