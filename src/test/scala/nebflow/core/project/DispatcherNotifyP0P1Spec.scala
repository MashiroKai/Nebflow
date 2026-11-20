package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behaviors}
import nebflow.agent.{SpecResources, StubLlm}
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * **D1-D4 机械验收**（dispatch-notify 投递地址批 2026-11-02；RCA
 * `docs/20261102_200551_dispatcher-node-failure-notification-rca__delegate-nebflow-dfab3d21.md`
 * §7 P0/P1/P2）。
 *
 * 与 [[DispatchNotifyDeliveryTargetSpec]] / [[ChainWedgedReceiptSpec]] /
 * [[DispatcherTextTruthSpec]] 的分工：那三份验**单元契约**（直驱 DispatchNotify /
 * 直驱 checkBarriersNow / 直读源文件子串）；本份验**接线契约**——走生产挂载面
 * （[[DelegateFlowHosts.mount]] / [[ProjectRuntimeRegistry]]），证明「装置真的接对了」。
 *
 *  - **D1（P0 正向）**：delegate 作用域宿主里的节点 failed ⇒ 通知投**创建者
 *    delegate**（`[dispatch-notify]` + 节点名）+ `dispatch-notify-delivery`
 *    (`target=delegate-creator:<id>`) + 项目分发器**零**投递（不旁路）。
 *  - **D2（P0 反向回归 = 改前接线复现）**：项目面同形态 failed ⇒ 仍投
 *    `ProjectActor.TriggerDispatcher` 恰一次，delegate 面零投递。这组用例描述的
 *    是**旧接线**的行为——改前代码上它就是绿的，改后仍必须绿（红/绿对照基线）。
 *  - **D3（P1 · 作用域面）**：链内节点 failed + 下游 pending 被永久闸死 ⇒ 基工作区
 *    事件流出现 `chain-wedged`（三要素 + 顶层 chainId）且创建者 delegate 收到**恰一条**
 *    回执；同链第二次终态写点零新增（单发）。
 *  - **D4（P2 文本真实性 · 生产路径版）**：D2 那条**真被投出去**的通知文本必须自洽——
 *    含 `AskUserQuestion` / `TaskBoard` / `没有消息工具`，且**不含**「自动投递 Nebula」。
 *    这正是 `NotifyDispatcherSpec` ⑬ 断言改后仍为**真话**的实证：⑬ 钉的不是
 *    `assert(true)`，而是生产路径产出的真实文本里确实存在的那两条路径。
 */
class DispatcherNotifyP0P1Spec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-p0p1"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"p0p1 wiring spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()

  // ── 捕获面 ────────────────────────────────────────────────────────────────

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  /** 任一 ActorSystem 消息的纯捕获行为（ProjectCommand 腿用）。 */
  private def plainRecorder[Msg](recorded: Ref[IO, List[Msg]]): nebflow.actor.Behavior[Msg] =
    lazy val b: nebflow.actor.Behavior[Msg] =
      Behaviors.receiveMessage[Msg](msg => recorded.update(_ :+ msg).as(b))
    b

  /** 注册一个「live 的 delegate 会话」（捕获 ImmediateInput）。 */
  private def registerDelegate(
    res: nebflow.agent.SharedResources,
    system: ActorSystem,
    delegateId: String
  ): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"rec-$delegateId-${scala.util.Random.nextInt(1000000)}")
      _ <- res.agentRegistry.update(
        _ + (delegateId -> AgentRecord(
          sessionId = delegateId,
          ref = ref,
          kind = AgentKind.Delegate,
          rootSessionId = delegateId
        ))
      )
    yield recorded

  private def imms(recorded: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })

  private def awaitImms(
    recorded: Ref[IO, List[AgentCommand]],
    min: Int,
    pred: AgentCommand.ImmediateInput => Boolean = (_: AgentCommand.ImmediateInput) => true,
    timeoutMs: Long = 20_000L
  ): IO[List[AgentCommand.ImmediateInput]] =
    def go(deadline: Long): IO[List[AgentCommand.ImmediateInput]] =
      imms(recorded).map(_.filter(pred)).flatMap { ms =>
        if ms.size >= min || System.currentTimeMillis() >= deadline then IO.pure(ms)
        else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  private def readEvents(ws: os.Path): IO[List[Json]] =
    IO.blocking {
      val f = ws / ".nebflow" / FlowMapEventLog.FileName
      if os.exists(f) then os.read(f).linesIterator.toList.filter(_.trim.nonEmpty).flatMap(l => jsonParse(l).toOption)
      else Nil
    }

  /**
   * 事件面有界轮询：投递审计行（`dispatch-notify-delivery` / `chain-wedged`）是
   * **offer 之后**的独立写点，与注入到达不是同一时刻（实测差数百微秒）——直接
   * 一次性读盘会与写盘竞态。判据 = 「已有 ≥min 条满足 pred 的行」。
   */
  private def awaitEvents(
    ws: os.Path,
    min: Int,
    pred: Json => Boolean,
    timeoutMs: Long = 10_000L
  ): IO[List[Json]] =
    def go(deadline: Long): IO[List[Json]] =
      readEvents(ws).flatMap { all =>
        val hits = all.filter(pred)
        if hits.size >= min || System.currentTimeMillis() >= deadline then IO.pure(hits)
        else IO.sleep(25.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  private def typ(j: Json): String = j.hcursor.get[String]("type").getOrElse("")
  private def summary(j: Json): String = j.hcursor.get[String]("summary").getOrElse("")

  // ── 播种 ──────────────────────────────────────────────────────────────────

  /**
   * 一个「尚未产出结果而死掉」的节点：`Running` ∧ 无 `sessionRef` ⇒
   * [[NodeEngine.bootRecoveryClaim]] 走 (c) 类 `failNode` 全链（`deliverFailed` →
   * `retryOrNotify` → `dispatchNotify.notifyTerminal(Failed)`）。用真实失败链而不是
   * 直调 notifyTerminal，是为了让 D1/D2 验的是**生产入口**。
   *
   * `out = Nil`：本 spec 主题是 dispatch-notify 腿，去掉 root 通报腿的噪音
   * （failed 通知与 out 边无关，不受影响）。
   */
  private def seedDeadRunning(store: FlowMapStore, id: String, name: String, chainId: Option[String] = None): IO[Unit] =
    val now = System.currentTimeMillis()
    store
      .mutate(s =>
        s.copy(nodes =
          s.nodes.updated(
            id,
            NodeDef(
              id = id,
              name = name,
              agent = "general",
              task = Some("p0p1 wiring spec node"),
              status = NodeLifecycle.Running,
              createdAt = now - 3_600_000L,
              startedAt = Some(now - 3_600_000L),
              chainId = chainId
            )
          )
        )
      )
      .void

  end seedDeadRunning

  private def child(nodeId: String, name: String, upstream: String, chainId: Option[String]): NodeDef =
    NodeDef(
      id = nodeId,
      name = name,
      agent = "general",
      status = NodeLifecycle.Pending,
      in = List(upstream),
      chainId = chainId,
      createdAt = System.currentTimeMillis() - 600_000L
    )

  private def seedChild(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes.updated(n.id, n))).void

  // ── 项目面挂载（D2/D4：真实 defaultTrigger 链 → ProjectActor.TriggerDispatcher）──

  private case class ProjectFace(
    ws: os.Path,
    rt: ProjectRuntime,
    commands: Ref[IO, List[ProjectActor.ProjectCommand]]
  )

  /**
   * 项目面生产接线：`notifyTriggerOverride` **不传**（= 真实
   * [[DispatchNotify.defaultTrigger]] → `ProjectRuntimeRegistry` → `ProjectActor
   * .ProjectCommand.TriggerDispatcher`），`notifyQuietMs = Some(0)` 只为把 5s 打包窗
   * 压成同步（读数确定性），不改投递目标。
   */
  private def mountProjectFace(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: nebflow.agent.SharedResources
  ): IO[ProjectFace] =
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
        emitEvent = (_: String, _: String, _: Json) => IO.unit,
        reportGateHold = Some(false),
        notifyQuietMs = Some(0L)
      )
      commands <- Ref.of[IO, List[ProjectActor.ProjectCommand]](Nil)
      ref <- system.spawn(plainRecorder(commands), s"pc-rec-$name-${scala.util.Random.nextInt(1000000)}")
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, Some(ref))
      _ <- ProjectRuntimeRegistry.register(rt)
    yield ProjectFace(ws, rt, commands)

  private def triggers(commands: Ref[IO, List[ProjectActor.ProjectCommand]]): IO[List[String]] =
    commands.get.map(
      _.collect { case ProjectActor.ProjectCommand.TriggerDispatcher(taskText, _, _, _, _, _) => taskText }
    )

  // ══ D1 ═════════════════════════════════════════════════════════════════════

  test(
    "D1 (P0 forward): a failed node inside a delegate scope host notifies the CHAIN OWNER delegate — creator receives [dispatch-notify] + a dispatch-notify-delivery row (target=delegate-creator:<id>), and the project dispatcher receives ZERO"
  ) {
    val ws = tempRoot / "ws-d1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"p0p1-d1-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d1a00001"
    val projectName = "p0p1-scope-d1"
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      creator <- registerDelegate(res, system, delegateId)
      // 诱饵：作用域引擎的 projectName 若被误当「投给项目分发器」，本 Ref 会非空
      decoy <- mountProjectFace(projectName, ws / "decoy-project", system, res)
      _ <- IO(os.makeDir.all(ws / "decoy-project"))
      mounted <- DelegateFlowHosts.mount(
        delegateId = delegateId,
        project = Some(projectName),
        baseWorkspace = ws.toString,
        engineCwd = ws.toString,
        system = system,
        resources = res,
        wsSend = None,
        reportGateHold = Some(false),
        rootNotifyQuietMs = Some(0L),
        destroyWindowMs = Some(0L)
      )
      host = mounted.getOrElse(fail("delegate scope host must mount"))
      _ <- seedDeadRunning(host.store, "n-fail", "D1-node", chainId = Some("c-d1"))
      node <- host.store.getNode("n-fail").map(_.getOrElse(fail("seed failed")))
      _ <- host.engine.bootRecoveryClaim(node).void
      // dispatch-notify 缺省 5s 打包窗（mount 面不注入 notifyQuietMs）⇒ 等它 flush
      got <- awaitImms(creator, 1, _.text.contains("[dispatch-notify]"), timeoutMs = 30_000L)
      decoyDelegate <- registerDelegate(res, system, "delegate-general-d1decoy")
      bypass <- triggers(decoy.commands)
      delivered <- awaitEvents(
        ws,
        1,
        j => typ(j) == DispatchNotify.DeliveryEventType && summary(j).contains("outcome=delivered")
      )
      decoyGot <- imms(decoyDelegate)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(got.nonEmpty, "D1: the creator delegate must receive the dispatch-notify injection")
      val text = got.head.text
      assert(text.contains("[dispatch-notify]"), s"D1: notify text must carry the marker, got: ${text.take(300)}")
      assert(text.contains("D1-node"), s"D1: notify text must name the node, got: ${text.take(300)}")
      assertEquals(
        got.head.source,
        Some("delegate-notify"),
        "D1: delivery must ride DelegateNotify.inject (turn-boundary channel)"
      )
      assert(
        delivered.exists(j => summary(j).contains(s"target=${DispatchNotify.delegateCreatorLabel(delegateId)}")),
        s"D1: the delivery row must name the creator target, got ${delivered.map(j => summary(j))}"
      )
      assertEquals(
        bypass,
        Nil,
        s"D1: ZERO bypass — the project dispatcher leg must never fire for a scope node, got $bypass"
      )
      assertEquals(decoyGot, Nil, "D1: no stray delegate injection")
    end for
  }

  // ══ D2 ═════════════════════════════════════════════════════════════════════

  test(
    "D2 (P0 reverse / pre-change wiring): the project face keeps its legacy delivery — the same failure shape reaches ProjectActor.TriggerDispatcher exactly once and no delegate at all"
  ) {
    val ws = tempRoot / "ws-d2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"p0p1-d2-${scala.util.Random.nextInt(1000000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      face <- mountProjectFace("p0p1-project-d2", ws, system, res)
      decoy <- registerDelegate(res, system, "delegate-general-d2decoy")
      _ <- seedDeadRunning(face.rt.store, "n-p", "D2-node")
      node <- face.rt.store.getNode("n-p").map(_.getOrElse(fail("seed failed")))
      _ <- face.rt.engine.bootRecoveryClaim(node).void
      texts <- triggers(face.commands)
      decoyImms <- imms(decoy)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        texts.size,
        1,
        s"D2: the project dispatcher leg must fire exactly once (unchanged), got ${texts.size}"
      )
      assert(texts.head.contains("[dispatch-notify]"), s"D2: text must carry the marker, got: ${texts.head.take(200)}")
      assert(texts.head.contains("D2-node"), "D2: text must name the node")
      assertEquals(
        decoyImms,
        Nil,
        s"D2: the project face must never deliver to a delegate, got ${decoyImms.map(_.text.take(80))}"
      )
    end for
  }

  // ══ D3 ═════════════════════════════════════════════════════════════════════

  test(
    "D3 (P1 on the scope face): a chain-owned downstream wedged under a delegate scope emits chain-wedged into the base-workspace event stream AND delivers exactly ONE receipt to the creator delegate — a second terminal write on the same chain emits nothing more"
  ) {
    val ws = tempRoot / "ws-d3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"p0p1-d3-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d3a00001"
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      creator <- registerDelegate(res, system, delegateId)
      mounted <- DelegateFlowHosts.mount(
        delegateId = delegateId,
        project = None,
        baseWorkspace = ws.toString,
        engineCwd = ws.toString,
        system = system,
        resources = res,
        wsSend = None,
        reportGateHold = Some(false),
        rootNotifyQuietMs = Some(0L),
        destroyWindowMs = Some(0L)
      )
      host = mounted.getOrElse(fail("delegate scope host must mount"))
      // 上游 failed（有链归属）→ 两个同链下游 pending 被永久闸死
      _ <- host.store
        .mutate(s =>
          s.copy(nodes =
            s.nodes.updated(
              "n-up",
              NodeDef(
                id = "n-up",
                name = "UP",
                agent = "general",
                status = NodeLifecycle.Failed,
                result = Some("boom"),
                out = List(OutEdge("n-dn1"), OutEdge("n-dn2")),
                createdAt = System.currentTimeMillis() - 900_000L,
                completedAt = Some(System.currentTimeMillis() - 800_000L)
              )
            )
          )
        )
        .void
      _ <- seedChild(host.store, child("n-dn1", "DN1", "n-up", Some("c-d3")))
      _ <- seedChild(host.store, child("n-dn2", "DN2", "n-up", Some("c-d3")))
      _ <- host.engine.checkBarriersNow("n-up", cause = "failed")
      got <- awaitImms(creator, 1, _.text.contains("chain-wedged"), timeoutMs = 20_000L)
      _ <- host.engine.checkBarriersNow("n-up", cause = "failed") // 第二次终态写点：单发
      again <- imms(creator)
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val wedged = events.filter(j => typ(j) == NodeEngine.ChainWedgedEventType)
      assertEquals(wedged.size, 1, s"D3: exactly one chain-wedged event, got ${events.map(typ)}")
      val s0 = summary(wedged.head)
      assert(s0.contains("chain=c-d3"), s"D3: summary must carry chain=<id>: $s0")
      assert(s0.contains("node=n-dn"), s"D3: summary must name the wedged downstream: $s0")
      assert(s0.contains("wedgedBy=n-up"), s"D3: summary must name the terminal upstream: $s0")
      assert(
        wedged.head.hcursor.get[String]("chainId").toOption.contains("c-d3"),
        "D3: the event must carry the top-level chainId key"
      )
      assertEquals(got.size, 1, s"D3: the creator delegate must receive exactly one receipt, got ${got.size}")
      assert(got.head.text.contains("c-d3"), "D3: the receipt must carry the chainId")
      assert(
        got.head.text.contains("hand over") && got.head.text.contains("rewire") && got.head.text.contains("abandon"),
        s"D3: the receipt must state the three remedies: ${got.head.text.take(400)}"
      )
      assertEquals(
        again.count(_.text.contains("chain-wedged")),
        1,
        "D3: single-shot per chainId — the second terminal write must not re-notify"
      )
      // 零回归面：barrier-blocked 每被闸死下游各一条（与改前一致）
      assertEquals(events.count(j => typ(j) == "barrier-blocked"), 2, "D3: barrier-blocked emission is unchanged")
    end for
  }

  // ══ D4 ═════════════════════════════════════════════════════════════════════

  test(
    "D4 (P2 text truth through the production path): the dispatcher notification that actually gets delivered names the two REAL escalation paths (AskUserQuestion / TaskBoard), states there is no messaging tool, and never re-promises auto-delivery to Nebula"
  ) {
    val ws = tempRoot / "ws-d4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"p0p1-d4-${scala.util.Random.nextInt(1000000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      face <- mountProjectFace("p0p1-project-d4", ws, system, res)
      _ <- seedDeadRunning(face.rt.store, "n-t", "D4-node")
      node <- face.rt.store.getNode("n-t").map(_.getOrElse(fail("seed failed")))
      _ <- face.rt.engine.bootRecoveryClaim(node).void
      texts <- triggers(face.commands)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(texts.size, 1, "D4: exactly one notification to inspect")
      val t = texts.head
      // 真实工具集裁决者：9 件、无 Mail —— 下面两条路径断言的地基
      assertEquals(nebflow.agent.AgentCore.DispatcherFixedTools.size, 9, "D4: DispatcherFixedTools is 9 tools")
      assert(!nebflow.agent.AgentCore.DispatcherFixedTools.contains("Mail"), "D4: the dispatcher holds no Mail tool")
      assert(
        t.contains("`AskUserQuestion`") && t.contains("`TaskBoard`"),
        s"D4: the delivered text must teach the two paths that actually exist, got: ${t.take(600)}"
      )
      assert(t.contains("没有消息工具"), s"D4: the delivered text must state the true fact, got: ${t.take(600)}")
      assert(
        !t.contains("自动投递 Nebula"),
        s"D4: the text must NOT re-promise auto-delivery to Nebula (pre-change false promise), got: ${t.take(600)}"
      )
      // ⑬ 断言的实证：改后 `prompt.contains("`AskUserQuestion`") && ...("`TaskBoard`")` 为真，
      // 且 `!prompt.contains("自动投递 Nebula")` 为真 —— 两者都由本条生产路径文本机器验证。
      assert(t.contains("上报"), "D4: the escalate semantics survive (the ⑬ assertion's unchanged half)")
    end for
  }

end DispatcherNotifyP0P1Spec
