package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 批E1 · P1 out 语义门控端到端回归（spec 20260908_semantic-gating-askuser §2.2，
 * 验收④⑤；NodeBarrierDeliverySpec 同款基建）：
 *
 *  - ④a `(pass)B, (failed)C` 扇出：A 完成后 B 沿 pass 边收结果启动，C（failed 门）
 *    不触发保持 wiring——旧单值拓扑表达不了的形态；
 *  - ④b `(failed)C:signal` 失败纯信号边：A failed 后 C 记账归零 barrier 启动，
 *    输入 = 自身 task（buildInput 按边 mode 抑制载荷——失败错误文本不作输入投递）；
 *  - ⑤a NODE_MERGE_PASS_ONLY：指向 merge 节点的 on-failed 边创建/改接 0 spawn 硬拒；
 *  - ⑤b 无 on-failed 边 WARNING：上游已 failed 而下游持 in 边且非 merge → 提示不阻断；
 *  - codec 双读：旧字符串 out（"n-a"/"Nebula"/缺键）解码形态 + "Nebula" 双通报门
 *    落盘形态（归档数据零迁移，G1 解码基线）。
 */
class NodeEdgeGatingSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-edge-gating"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 组合 LLM 桩：按输入文本决定回复/失败/延迟（DispatchLlm + FailOnLlm 合一）。 */
  private class GateLlm(
      replyOf: String => String = _ => "ok",
      failWhen: String => Boolean = _ => false,
      delayOf: String => FiniteDuration = _ => 0.millis
  ):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        if failWhen(text) then
          Stream.eval(inputs.update(_ :+ text) >> IO.sleep(delayOf(text))) >>
            Stream.raiseError[IO](new RuntimeException("boom-exploded"))
        else
          Stream
            .eval(inputs.update(_ :+ text) >> IO.sleep(delayOf(text)))
            .flatMap(_ => Stream(StreamChunk.TextDelta(replyOf(text)), StreamChunk.Done(None, None)))

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
      sessionId = Some("gating-sid"),
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

  private def mountProject(
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
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  /** 按名取节点 id（活动区；找不到 fail 该测试）。 */
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

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── ④a 扇出：完成投递沿 pass 门过滤 ─────────────────────

  test("④a fan-out '(pass)B, (failed)C': completion reaches B only; C (failed gate) keeps waiting") {
    val ws = tempRoot / "ws-fanout"
    os.makeDir.all(ws)
    val system = ActorSystem(s"gating-fan-${scala.util.Random.nextInt(100000)}")
    // A 的任务延迟 1.2s 回复——留出建 B/C + 改接 A 的窗口；其余即刻回
    val llm = new GateLlm(replyOf = t => if t.contains("seed-fan-a") then "a-result-token" else "ok",
      delayOf = t => if t.contains("seed-fan-a") then 1200.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("gating-fanout", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // A 入口（task + out=Nebula）创建即运行（延迟窗口）
      _ <- nodeEdit(nodeInput("gating-fanout", "fan-a", "description" -> Json.fromString("fan source"),
        "task" -> Json.fromString("seed-fan-a"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- idOf(rt, "fan-a")
      // B/C 接 in=[A]（A 运行中 → 不触发 D1 投递，B/C 保持 wiring 等待 barrier）
      _ <- nodeEdit(nodeInput("gating-fanout", "fan-b", "description" -> Json.fromString("pass consumer"),
        "task" -> Json.fromString("b-consumes-a"), "in" -> Json.fromString(aId), "out" -> Json.fromString("Nebula")), ctx)
      _ <- nodeEdit(nodeInput("gating-fanout", "fan-c", "description" -> Json.fromString("failed fallback"),
        "task" -> Json.fromString("c-own-task"), "in" -> Json.fromString(aId), "out" -> Json.fromString("Nebula")), ctx)
      bId <- idOf(rt, "fan-b")
      cId <- idOf(rt, "fan-c")
      // 改接 A：扇出 "(pass)B, (failed)C"（P1 新表达力）
      rew <- nodeEdit(nodeInput("gating-fanout", "fan-a",
        "out" -> Json.fromString(s"(pass)$bId, (failed)$cId")), ctx)
      _ <- waitStatus(rt, "fan-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "fan-b", Set(NodeLifecycle.Completed))
      aAfter <- nodeById(rt, aId).map(_.getOrElse(fail("A must exist")))
      bAfter <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      cAfter <- nodeById(rt, cId).map(_.getOrElse(fail("C must exist")))
      bInput <- llm.inputs.get.map(_.find(t => t.contains("b-consumes-a")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rew.isRight, s"fan-out rewire must succeed, got: $rew")
      // A 落库边形 = 规范化扇出（pass 边 + failed 边，Nebula 边被替换）
      assertEquals(aAfter.out, List(OutEdge(bId), OutEdge(cId, Set("failed"))),
        s"A.out must be the canonical fan-out pair, got ${aAfter.out}")
      // pass 路径：B 收到结果并启动完成；deliveredTo 记账
      assertEquals(bAfter.status, NodeLifecycle.Completed, "B must start & complete along the pass edge")
      assert(bAfter.deliveredTo.contains(aId), s"B must record A delivery, got ${bAfter.deliveredTo}")
      assert(bInput.exists(_.contains("=== Node fan-a ===")) && bInput.exists(_.contains("a-result-token")),
        s"B's input must carry A's result payload, got: ${bInput.map(_.take(300))}")
      // failed 门不触发：C 保持 wiring、零记账（D5 停等形态；mount-stalled 承载可见性）
      assertEquals(cAfter.status, NodeLifecycle.Wiring, "C (failed-gate only) must NOT be triggered by completion")
      assertEquals(cAfter.deliveredTo, Nil, "C must have zero delivery bookkeeping on the pass path")
  }

  // ── ④b 失败纯信号边：记账归零 barrier，不投载荷 ──────────

  test("④b '(failed)C:signal': upstream failure starts C via signal (own task input, no error payload)") {
    val ws = tempRoot / "ws-signal"
    os.makeDir.all(ws)
    val system = ActorSystem(s"gating-sig-${scala.util.Random.nextInt(100000)}")
    // A 的任务延迟 800ms 后失败——留出建 B + 改接 A 的窗口
    val llm = new GateLlm(failWhen = t => t.contains("seed-sig-fail"),
      delayOf = t => if t.contains("seed-sig-fail") then 800.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("gating-signal", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("gating-signal", "sig-a", "description" -> Json.fromString("doomed source"),
        "task" -> Json.fromString("seed-sig-fail"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- idOf(rt, "sig-a")
      // B 接 in=[A]（A 运行中 → 保持 wiring）
      _ <- nodeEdit(nodeInput("gating-signal", "sig-b", "description" -> Json.fromString("signal fallback"),
        "task" -> Json.fromString("b-own-task"), "in" -> Json.fromString(aId), "out" -> Json.fromString("Nebula")), ctx)
      bId <- idOf(rt, "sig-b")
      // 改接 A：失败纯信号边（P1 新表达力）
      rew <- nodeEdit(nodeInput("gating-signal", "sig-a",
        "out" -> Json.fromString(s"(failed)$bId:signal")), ctx)
      _ <- waitStatus(rt, "sig-a", Set(NodeLifecycle.Failed))
      _ <- waitStatus(rt, "sig-b", Set(NodeLifecycle.Completed))
      aAfter <- nodeById(rt, aId).map(_.getOrElse(fail("A must exist")))
      bAfter <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      bInput <- llm.inputs.get.map(_.find(t => t.contains("b-own-task")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rew.isRight, s"signal-edge rewire must succeed, got: $rew")
      assertEquals(aAfter.out, List(OutEdge(bId, Set("failed"), "signal")),
        s"A.out must be the failed-signal edge, got ${aAfter.out}")
      assertEquals(aAfter.status, NodeLifecycle.Failed, "A must have failed")
      assertEquals(bAfter.status, NodeLifecycle.Completed, "B must be started by the failure signal")
      assert(bAfter.deliveredTo.contains(aId), s"signal edge must settle the barrier bookkeeping, got ${bAfter.deliveredTo}")
      // signal 模式：不投载荷——B 的输入 = 自身 task（无 A 错误文本、无上游结果段）
      assert(bInput.isDefined, "B must have run with its own task input")
      assert(!bInput.exists(_.contains("boom-exploded")), s"B's input must NOT carry A's error text, got: ${bInput.map(_.take(300))}")
      assert(!bInput.exists(_.contains("=== Node ")), s"B's input must NOT carry any upstream payload section, got: ${bInput.map(_.take(300))}")
  }

  // ── ⑤a NODE_MERGE_PASS_ONLY：指向 merge 的 on-failed 边硬拒 ──

  test("⑤a NODE_MERGE_PASS_ONLY: on-failed edge into a merge node rejected on create AND edit (0 spawn)") {
    val ws = tempRoot / "ws-mergegate"
    os.makeDir.all(ws)
    val system = ActorSystem(s"gating-merge-${scala.util.Random.nextInt(100000)}")
    val llm = new GateLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("gating-mergegate", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 上游先建（merge 校验②要求 in ≥1），merge 节点随后
      _ <- nodeEdit(nodeInput("gating-mergegate", "mg-up", "description" -> Json.fromString("upstream"),
        "task" -> Json.fromString("mg-up-task"), "out" -> Json.fromString("Nebula")), ctx)
      upId <- idOf(rt, "mg-up")
      _ <- nodeEdit(nodeInput("gating-mergegate", "mg-sink", "description" -> Json.fromString("landing sink"),
        "merge" -> Json.fromBoolean(true), "in" -> Json.fromString(upId), "out" -> Json.fromString("Nebula"),
        "task" -> Json.fromString("landing commands")), ctx)
      mId <- idOf(rt, "mg-sink")
      // 创建期：out="(failed)merge" → 硬拒
      createRes <- nodeEdit(nodeInput("gating-mergegate", "mg-attacker", "description" -> Json.fromString("attacker"),
        "task" -> Json.fromString("attacker task"), "out" -> Json.fromString(s"(failed)$mId")), ctx)
      // 改接期：存量节点 out 改指 merge 的 on-failed 边 → 同码硬拒
      _ <- nodeEdit(nodeInput("gating-mergegate", "mg-editor", "description" -> Json.fromString("editor"),
        "task" -> Json.fromString("editor task"), "out" -> Json.fromString("Nebula")), ctx)
      editRes <- nodeEdit(nodeInput("gating-mergegate", "mg-editor",
        "out" -> Json.fromString(s"(failed)$mId")), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(createRes.isLeft, "create with on-failed edge into merge must be rejected")
      assert(createRes.left.exists(_.contains("NODE_MERGE_PASS_ONLY")), s"error must carry the code, got: $createRes")
      assert(editRes.isLeft, "edit rewiring an on-failed edge into merge must be rejected")
      assert(editRes.left.exists(_.contains("NODE_MERGE_PASS_ONLY")), s"error must carry the code, got: $editRes")
      // 被拒节点不落库（0 spawn 校验面）
      assert(!snap.nodes.values.exists(_.name == "mg-attacker"), "rejected create must not leave a node behind")
      val editor = snap.nodes.values.find(_.name == "mg-editor").getOrElse(fail("editor node must exist"))
      assert(editor.out.forall(_.to == "Nebula"), s"rejected edit must not touch node.out, got ${editor.out}")
  }

  // ── ⑤b 无 on-failed 边 WARNING：提示不阻断 ───────────────

  test("⑤b WARNING: downstream holding in-edge from a FAILED upstream without on-failed edge gets a non-blocking warning") {
    val ws = tempRoot / "ws-warn"
    os.makeDir.all(ws)
    val system = ActorSystem(s"gating-warn-${scala.util.Random.nextInt(100000)}")
    val llm = new GateLlm(failWhen = t => t.contains("seed-warn-fail"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("gating-warn", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("gating-warn", "warn-up", "description" -> Json.fromString("doomed upstream"),
        "task" -> Json.fromString("seed-warn-fail"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "warn-up", Set(NodeLifecycle.Failed))
      upId <- idOf(rt, "warn-up")
      // 下游创建（in=已 failed 上游）→ 成功但附 WARNING
      created <- nodeEdit(nodeInput("gating-warn", "warn-dn", "description" -> Json.fromString("downstream"),
        "task" -> Json.fromString("dn-task"), "in" -> Json.fromString(upId), "out" -> Json.fromString("Nebula")), ctx)
      // 对照组：正常拓扑（上游未 failed）→ 无 WARNING 噪音
      _ <- nodeEdit(nodeInput("gating-warn", "warn-ok-up", "description" -> Json.fromString("healthy upstream"),
        "task" -> Json.fromString("ok-up-task"), "out" -> Json.fromString("Nebula")), ctx)
      okUpId <- idOf(rt, "warn-ok-up")
      quiet <- nodeEdit(nodeInput("gating-warn", "warn-dn2", "description" -> Json.fromString("quiet downstream"),
        "task" -> Json.fromString("dn2-task"), "in" -> Json.fromString(okUpId), "out" -> Json.fromString("Nebula")), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(created.isRight, s"warning must NOT block creation, got: $created")
      val msg = created.toOption.getOrElse("")
      assert(msg.contains("⚠"), s"result must carry the warning marker, got: $msg")
      assert(msg.contains("has FAILED") && msg.contains(upId), s"warning must name the failed upstream, got: $msg")
      // 对照：健康上游零噪音
      assert(quiet.isRight && !quiet.toOption.getOrElse("").contains("⚠"),
        s"healthy topology must not warn, got: $quiet")
      // 节点照常落库（不阻断）
      assert(snap.nodes.values.exists(_.name == "warn-dn"), "warned node must still be created")
  }

  // ── codec 双读（G1 解码基线，归档零迁移凭据）──────────────

  test("codec dual-read: legacy string / Nebula / absent out decode per G1; persisted shape stays edge-array") {
    val ws = tempRoot / "ws-codec"
    val neb = ws / ".nebflow"
    os.makeDir.all(neb)
    val legacy =
      """{"v":1,"project":"gating-codec","updatedAt":1,"nodes":{
        |"n-a":{"id":"n-a","name":"a","agent":"general","createdAt":1,"out":"n-b","status":"completed"},
        |"n-c":{"id":"n-c","name":"c","agent":"general","createdAt":1,"out":"Nebula","status":"completed"},
        |"n-d":{"id":"n-d","name":"d","agent":"general","createdAt":1,"status":"wiring"}
        |}}""".stripMargin
    os.write.over(neb / "flow-map.json", legacy)
    for
      store <- FlowMapStore.open("gating-codec", ws.toString)
      a <- store.getNode("n-a")
      c <- store.getNode("n-c")
      d <- store.getNode("n-d")
      _ <- store.mutate(s => s).void // 触发一次落盘（canonical 边数组形态写回）
      raw <- IO.blocking(os.read(neb / "flow-map.json"))
      parsed <- IO.fromEither(io.circe.parser.parse(raw))
      aOut = parsed.hcursor.downField("nodes").downField("n-a").downField("out").focus
      cOut = parsed.hcursor.downField("nodes").downField("n-c").downField("out").focus
      dOut = parsed.hcursor.downField("nodes").downField("n-d").downField("out").focus
    yield
      // 旧字符串单边 → pass 缺省边（"A"→OutEdge("A",{pass},result)）
      assertEquals(a.map(_.out), Some(List(OutEdge("n-b"))), "legacy single id must decode to a pass edge")
      // "Nebula" → 双通报门 {pass,failed}（旧拓扑零漂移——completed/failed 双通知形态）
      assertEquals(c.map(_.out), Some(List(OutEdge.nebula)), "legacy Nebula must decode to the dual-gate reporting edge")
      // 缺键 → Nil（withDefaults）
      assertEquals(d.map(_.out), Some(Nil), "absent out must decode to Nil")
      // 落盘：非空 → 边数组（on 全量写出）；Nil → 键缺省 null（无出环节点零漂移）
      assertEquals(aOut.map(_.noSpaces.replace(" ", "")),
        Some("""[{"to":"n-b","on":["pass"],"mode":"result"}]"""),
        s"persisted shape must be the edge array, got: $aOut")
      assert(cOut.map(_.noSpaces).exists(_.contains("\"pass\"")) && cOut.map(_.noSpaces).exists(_.contains("\"failed\"")),
        s"persisted Nebula edge must carry both gates, got: $cOut")
      assertEquals(dOut, Some(Json.Null), "Nil out must persist as JSON null (byte-stable with legacy None)")
  }

end NodeEdgeGatingSpec
