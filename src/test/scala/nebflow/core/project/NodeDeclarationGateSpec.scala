package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, NodeTools, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 建位期声明闸四项（nodegate 方案件 §1 判据 + 本批四项范围裁定；spec 先行，先红后绿）：
 *
 *  - ① `NODE_MERGE_SINK_NEEDS_OUT`：merge sink 建位必须带 out——零 out 零声明 ⇒ 拒；
 *    `dangling=true` 是显式登记令牌（C-ii）；merge **带** out 形态零扰动（回归钉）。
 *  - ② `dangling=true`（create-only 令牌）：创建回执 ⚠ 行升级为带意图文案（declared
 *    标记）；审计事件 `dangling-declared` 落 flow-map-events.jsonl；**task 节点悬空
 *    不带令牌仍合法**（2026-09-12「out 可空置」裁定零回退——W1 仅 merge 子集硬拒）。
 *  - ③ `verifierRoutePending=true`（create-only 令牌，C-i）：空 out verifier 建位
 *    无令牌 ⇒ 拒（`NODE_VERIFIER_NEEDS_ROUTE`，不落库零改边）；带令牌 ⇒ 放行 + 回执
 *    `(verifierRoutePending)` ⚠ 行 + 审计 `verifier-route-deferred`；解除 = 派生
 *    （PC-3 补挂 fail 边即自然解除）；**镜像腿不豁免**（NC-2：下游 in= 给无路由
 *    verifier 补 pass 边仍拒，上游零改边）；对照 NC-3（已路由 verifier 的镜像追加
 *    合法）；编辑面同码（C-1：空 out verifier 编辑被拒；C-3：已路由 verifier 的
 *    无关编辑零误伤）；令牌在编辑面被容忍忽略（merge 先例，非错误）。
 *  - ④ `NODE_PLUGINS_UNDECLARED`：建位不带 plugins 键 ⇒ 拒（不落库）；`plugins=[]`
 *    = 显式「无需能力面」（合法）；P2a = flag off 时声明必须**警告**不得静默丢弃；
 *    P3 = task 文本提及 Catalog 插件名而 plugins 空 ⇒ 警告不硬拒。
 *
 * 全域 0 spawn 纪律（NodeVerdictRoutingSpec 同款）：建位尽量 in-only（不构成入口
 * 启动）；verifier 以 in=[worker] 满足输入侧；stub LLM 恒 "send not expected"。
 */
class NodeDeclarationGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-decl-gate"
  private val originalRoot = PathUtil.dataRoot

  // 2026-09-18 测试面成因级修复（候选①·治本）：夹具建立与**进程级** `PathUtil.setDataRoot`
  // 一律**不在类体构造期**执行，收进 `beforeAll`；顺序 = 「先清树 / 建树，最后换根」
  // （镜像 NodeSchemaSlimSpec 的同款修法 —— 两套件共用「组合跑 + 陈旧树在场」这一故障面，
  // 双侧任一残留写入都能落进对方刚换上的树）。语义零变化：本套件仍独占自己的 dataRoot。
  override def beforeAll(): Unit =
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "agents" / "general")
    os.write.over(
      tempRoot / "agents" / "general" / "agent.json",
      """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private class QuietLlm:

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("decl-gate-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  /** 默认声明面：`plugins=[]`（显式「无需能力面」）放最前——extra 同名键后置覆盖。 */
  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(
      ("project" -> Json
        .fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: ("plugins" -> Json.arr()) :: extra.toList*
    )

  /** 直种节点（绕过 NodeEdit 校验，把状态摆到待验判据上）。 */
  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  private def mkNode(
    id: String,
    name: String,
    status: String,
    role: String = NodeRoles.Task,
    out: List[OutEdge] = Nil
  ): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name task"),
      status = status,
      out = out,
      role = role,
      createdAt = System.currentTimeMillis()
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
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

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines =>
        lines.flatMap(l =>
          io.circe.parser
            .parse(l)
            .toOption
            .map(j =>
              (
                j.hcursor.get[String]("type").getOrElse(""),
                j.hcursor.get[String]("nodeId").getOrElse(""),
                j.hcursor.get[String]("summary").getOrElse("")
              )
            )
        )
      )
      .handleError(_ => Nil)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private def mkEnv(name: String): IO[(os.Path, ActorSystem, SharedResources, ProjectRuntime, ToolContext)] =
    val ws = tempRoot / s"ws-$name"
    os.makeDir.all(ws)
    val system = ActorSystem(s"decl-$name-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, QuietLlm().handle)
      rt <- mountProject(s"decl-$name", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
    yield (ws, system, res, rt, ctx)

  // ── ① merge sink 建位必须带 out ─────────────────────────────

  test(
    "① merge sink without out and without dangling=true is refused (NODE_MERGE_SINK_NEEDS_OUT, zero writes); dangling=true declares it; merge WITH out unaffected"
  ) {
    for
      (ws, system, _, rt, ctx) <- mkEnv("m1")
      _ <- seed(rt, mkNode("n-up1", "up1", NodeLifecycle.Wiring))
      // 负控：merge + 空 out + 无声明 ⇒ 拒
      r1 <- nodeEdit(
        nodeInput(
          "decl-m1",
          "sink-a",
          "description" -> Json.fromString("landing sink"),
          "merge" -> Json.fromBoolean(true),
          "in" -> Json.fromString("n-up1")
        ),
        ctx
      )
      // 零写断言：被拒建位不落库、上游零改边
      afterReject <- rt.store.snapshot
      // 正控：同形态 + dangling=true ⇒ 放行（C-ii 令牌）
      r2 <- nodeEdit(
        nodeInput(
          "decl-m1",
          "sink-b",
          "description" -> Json.fromString("landing sink"),
          "merge" -> Json.fromBoolean(true),
          "in" -> Json.fromString("n-up1"),
          "dangling" -> Json.fromBoolean(true)
        ),
        ctx
      )
      // 回归钉：merge 带 out ⇒ 与本批前完全一致（零扰动）
      r3 <- nodeEdit(
        nodeInput(
          "decl-m1",
          "sink-c",
          "description" -> Json.fromString("landing sink"),
          "merge" -> Json.fromBoolean(true),
          "in" -> Json.fromString("n-up1"),
          "out" -> Json.fromString("(pass)Nebula")
        ),
        ctx
      )
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r1.left.exists(_.contains("NODE_MERGE_SINK_NEEDS_OUT")),
        s"merge sink without out must be refused, got: $r1"
      )
      assert(!afterReject.nodes.values.exists(_.name == "sink-a"), "the refused sink must not persist")
      assertEquals(
        afterReject.nodes("n-up1").out,
        Nil,
        "the refused create must not touch the upstream's out (zero mirror writes)"
      )
      assert(r2.isRight, s"merge sink + dangling=true must pass (C-ii token), got: $r2")
      assert(r2.exists(_.contains("dangling=true")), s"the receipt must mark the dangling declaration, got: $r2")
      val sinkB = s.nodes.values.find(_.name == "sink-b")
      assert(sinkB.exists(_.merge), "sink-b is a merge node")
      assert(sinkB.exists(_.out.isEmpty), "sink-b stays dangling (no out)")
      assert(r3.isRight, s"merge sink WITH out must stay legal, got: $r3")
  }

  // ── ② dangling 令牌面（⚠ 升级 + 审计；task 节点悬空合法零回退）──

  test(
    "② dangling=true upgrades the wiring-gap notice and logs 'dangling-declared'; task-node dangling WITHOUT the token stays legal (2026-09-12 ruling intact)"
  ) {
    for
      (ws, system, _, rt, ctx) <- mkEnv("d2")
      // 负对照（合法形态回归钉）：task 节点空 out 无令牌 ⇒ 仍合法，⚠ 行是不带 declared 标记的原文案
      r1 <- nodeEdit(
        nodeInput(
          "decl-d2",
          "loose-a",
          "description" -> Json.fromString("dangling task node"),
          "task" -> Json.fromString("work without exit")
        ),
        ctx
      )
      // 正面：dangling=true ⇒ ⚠ 带 declared 标记 + 审计事件
      r2 <- nodeEdit(
        nodeInput(
          "decl-d2",
          "loose-b",
          "description" -> Json.fromString("declared dangling node"),
          "task" -> Json.fromString("work without exit"),
          "dangling" -> Json.fromBoolean(true)
        ),
        ctx
      )
      bid <- rt.store.snapshot.map(_.nodes.values.find(_.name == "loose-b").map(_.id).getOrElse(""))
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isRight, s"task-node dangling (no token) must stay LEGAL (out nullable ruling), got: $r1")
      assert(r1.exists(_.contains("NO out edge")), s"the undeclared variant keeps the wiring-gap warning, got: $r1")
      assert(
        r1.exists(!_.contains("(dangling=true)")),
        s"the undeclared variant must NOT carry the declared marker, got: $r1"
      )
      assert(r2.isRight, s"declared dangling create must pass, got: $r2")
      assert(r2.exists(_.contains("(dangling=true)")), s"the receipt must carry the declared-dangling marker, got: $r2")
      assert(
        audit.exists((t, id, _) => t == "dangling-declared" && id == bid),
        s"a dangling-declared audit event must be logged for the declared create, got: $audit"
      )
      assert(
        !audit.exists((t, id, _) => t == "dangling-declared" && id != bid),
        s"the undeclared create must NOT be audited as dangling-declared, got: $audit"
      )
  }

  // ── ③ verifier 建位路由闸 + 令牌 + 镜像腿 ────────────────────

  test(
    "③ NC-1/PC-2 create face: empty-out verifier refused without token (zero writes); allowed with verifierRoutePending=true (warning + audit)"
  ) {
    for
      (ws, system, _, rt, ctx) <- mkEnv("v3a")
      _ <- seed(rt, mkNode("n-w1", "w1", NodeLifecycle.Wiring))
      // NC-1：无令牌 ⇒ 拒（码 NODE_VERIFIER_NEEDS_ROUTE，不落库、上游零改边）
      r1 <- nodeEdit(
        nodeInput(
          "decl-v3a",
          "v-noroute",
          "description" -> Json.fromString("route-less verifier"),
          "role" -> Json.fromString(NodeRoles.Verifier),
          "in" -> Json.fromString("n-w1")
        ),
        ctx
      )
      afterReject <- rt.store.snapshot
      // PC-2：带令牌 ⇒ 放行 + (verifierRoutePending) ⚠ 行 + 审计
      r2 <- nodeEdit(
        nodeInput(
          "decl-v3a",
          "v-pending",
          "description" -> Json.fromString("pending verifier"),
          "role" -> Json.fromString(NodeRoles.Verifier),
          "in" -> Json.fromString("n-w1"),
          "verifierRoutePending" -> Json.fromBoolean(true)
        ),
        ctx
      )
      audit <- readAudit(ws)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r1.left.exists(_.contains("NODE_VERIFIER_NEEDS_ROUTE")),
        s"empty-out verifier without token must be refused, got: $r1"
      )
      assert(!afterReject.nodes.values.exists(_.name == "v-noroute"), "the refused verifier must not persist")
      assertEquals(afterReject.nodes("n-w1").out, Nil, "the refused create must not mirror any edge (zero writes)")
      assert(r2.isRight, s"pending verifier (token) must pass, got: $r2")
      assert(r2.exists(_.contains("(verifierRoutePending)")), s"the receipt must carry the pending marker, got: $r2")
      val v = s.nodes.values.find(_.name == "v-pending")
      assert(v.exists(_.role == NodeRoles.Verifier), "the pending verifier persists with role=verifier")
      assert(v.exists(_.out.isEmpty), "the pending verifier has no out yet")
      val vid = v.map(_.id).getOrElse("")
      assert(
        audit.exists((t, id, _) => t == "verifier-route-deferred" && id == vid),
        s"a verifier-route-deferred audit event must be logged, got: $audit"
      )
  }

  test(
    "③ PC-3 release is derived: wiring the fail route later via NodeEdit passes (pending state is not a persisted field)"
  ) {
    for
      (_, system, _, rt, ctx) <- mkEnv("v3b")
      _ <- seed(rt, mkNode("n-w1", "w1", NodeLifecycle.Wiring))
      _ <- nodeEdit(
        nodeInput(
          "decl-v3b",
          "v-later",
          "description" -> Json.fromString("pending verifier"),
          "role" -> Json.fromString(NodeRoles.Verifier),
          "in" -> Json.fromString("n-w1"),
          "verifierRoutePending" -> Json.fromBoolean(true)
        ),
        ctx
      )
      wired <- nodeEdit(nodeInput("decl-v3b", "v-later", "out" -> Json.fromString("(fail)n-w1:loop")), ctx)
      vOut <- rt.store.snapshot.map(_.nodes.values.find(_.name == "v-later").map(_.out).getOrElse(Nil))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(wired.isRight, s"wiring the fail route must pass (release = derived), got: $wired")
      assert(
        vOut.exists(e => e.on.contains(OutEdge.Fail) && e.mode == OutEdge.Loop),
        s"the fail route is on the ledger, got: $vOut"
      )
  }

  test(
    "③ NC-2 mirror leg: a downstream in= onto a route-less verifier is refused (no pending escape on the mirror), upstream unchanged; NC-3 control: routed verifier accepts the pass append"
  ) {
    for
      (_, system, _, rt, ctx) <- mkEnv("v3c")
      _ <- seed(rt, mkNode("n-w1", "w1", NodeLifecycle.Wiring))
      // pending verifier（令牌建位，仍无路由）
      _ <- nodeEdit(
        nodeInput(
          "decl-v3c",
          "v-pend2",
          "description" -> Json.fromString("pending verifier"),
          "role" -> Json.fromString(NodeRoles.Verifier),
          "in" -> Json.fromString("n-w1"),
          "verifierRoutePending" -> Json.fromBoolean(true)
        ),
        ctx
      )
      vid <- rt.store.snapshot.map(_.nodes.values.find(_.name == "v-pend2").map(_.id).getOrElse(""))
      // NC-2：下游 in=[pending verifier] ⇒ 镜像追加会让它「有边无 fail 路由」⇒ 拒
      r1 <- nodeEdit(
        nodeInput("decl-v3c", "down-a", "description" -> Json.fromString("downstream"), "in" -> Json.fromString(vid)),
        ctx
      )
      afterReject <- rt.store.snapshot
      // NC-3 对照：已路由 verifier 的镜像追加合法（pass 边照写，fail 路由不动）
      _ <- nodeEdit(
        nodeInput(
          "decl-v3c",
          "v-routed",
          "description" -> Json.fromString("routed verifier"),
          "role" -> Json.fromString(NodeRoles.Verifier),
          "in" -> Json.fromString("n-w1"),
          "out" -> Json.fromString("(fail)n-w1:loop")
        ),
        ctx
      )
      rid <- rt.store.snapshot.map(_.nodes.values.find(_.name == "v-routed").map(_.id).getOrElse(""))
      r2 <- nodeEdit(
        nodeInput("decl-v3c", "down-b", "description" -> Json.fromString("downstream"), "in" -> Json.fromString(rid)),
        ctx
      )
      // down-b 的 id 只能取自**追加之后**的快照：afterReject 早于 down-b 建位，原断言从
      // 该旧快照 find ⇒ 恒回落字面量 "down-b" ⇒ 与真实 id（n-xxxxxxxx）比对必假红。
      // 判据未动（仍断言 pass 边被镜像到已路由 verifier），只修 id 取数面。
      afterAppend <- rt.store.snapshot
      downBId = afterAppend.nodes.values.find(_.name == "down-b").map(_.id)
      vRoutedOut <- rt.store.snapshot.map(_.nodes.values.find(_.name == "v-routed").map(_.out).getOrElse(Nil))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r1.left.exists(_.contains("NODE_VERIFIER_NEEDS_ROUTE")),
        s"the mirror append onto a route-less verifier must be refused (mirror leg is NOT exempt), got: $r1"
      )
      assertEquals(afterReject.nodes(vid).out, Nil, "the pending verifier's out stays EMPTY (zero edge writes)")
      assert(!afterReject.nodes.values.exists(_.name == "down-a"), "the refused downstream must not persist")
      assert(r2.isRight, s"a routed verifier accepts the downstream pass append, got: $r2")
      assert(
        downBId.exists(id => vRoutedOut.exists(_.to == id)),
        s"the pass edge was mirrored onto the routed verifier, got: $vRoutedOut (down-b id=$downBId)"
      )
      assert(
        vRoutedOut.exists(e => e.on.contains(OutEdge.Fail) && e.mode == OutEdge.Loop),
        s"the fail route is intact, got: $vRoutedOut"
      )
  }

  test(
    "③ C-1/C-3 edit face: an empty-out verifier edit is refused with the SAME code; a routed verifier's unrelated edit passes (zero false kill)"
  ) {
    for
      (_, system, _, rt, ctx) <- mkEnv("v3d")
      _ <- seed(
        rt,
        mkNode("n-w1", "w1", NodeLifecycle.Wiring),
        mkNode("n-bare", "bare-v", NodeLifecycle.Wiring, role = NodeRoles.Verifier),
        mkNode(
          "n-ok",
          "ok-v",
          NodeLifecycle.Wiring,
          role = NodeRoles.Verifier,
          out = List(OutEdge("n-w1", Set(OutEdge.Fail), OutEdge.Loop))
        )
      )
      r1 <- nodeEdit(nodeInput("decl-v3d", "bare-v", "description" -> Json.fromString("edited description")), ctx)
      r2 <- nodeEdit(nodeInput("decl-v3d", "ok-v", "description" -> Json.fromString("edited description")), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r1.left.exists(_.contains("NODE_VERIFIER_NEEDS_ROUTE")),
        s"an edit leaving a verifier route-less must be refused with the same code (C-1), got: $r1"
      )
      assert(r2.isRight, s"a routed verifier's unrelated edit must pass (C-3 zero false kill), got: $r2")
  }

  test("③ verifierRoutePending on edit is tolerated (create-only token, ignored like merge — not an error)") {
    for
      (_, system, _, rt, ctx) <- mkEnv("v3e")
      _ <- seed(rt, mkNode("n-t1", "t1", NodeLifecycle.Wiring))
      r <- nodeEdit(
        nodeInput(
          "decl-v3e",
          "t1",
          "description" -> Json.fromString("edited"),
          "verifierRoutePending" -> Json.fromBoolean(true)
        ),
        ctx
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield assert(r.isRight, s"the create-only token must be ignored on edit (merge precedent), got: $r")
  }

  // ── ④ plugins 声明面（P1 硬拒 / P2a flag-off 警告 / P3 文本警告）──

  test(
    "④ P1: a create WITHOUT a plugins key is refused (NODE_PLUGINS_UNDECLARED, zero writes); plugins=[] is the explicit no-capability face"
  ) {
    for
      (_, system, _, rt, ctx) <- mkEnv("p4a")
      // 负控：键缺席 ⇒ 拒
      r1 <- nodeEdit(
        Json.obj(
          "project" -> Json.fromString("decl-p4a"),
          "nodename" -> Json.fromString("no-key"),
          "description" -> Json.fromString("omitted capability face"),
          "task" -> Json.fromString("work")
        ),
        ctx
      )
      afterReject <- rt.store.snapshot
      // 正面：plugins=[] = 显式声明
      r2 <- nodeEdit(
        nodeInput(
          "decl-p4a",
          "explicit-empty",
          "description" -> Json.fromString("explicit no-capability"),
          "task" -> Json.fromString("work")
        ),
        ctx
      )
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r1.left.exists(_.contains("NODE_PLUGINS_UNDECLARED")),
        s"an omitted plugins key must be refused on create, got: $r1"
      )
      assert(r1.left.exists(_.contains("plugins=[]")), s"the error must teach the [] escape, got: $r1")
      assert(!afterReject.nodes.values.exists(_.name == "no-key"), "the refused create must not persist")
      assert(r2.isRight, s"plugins=[] is the explicit no-capability declaration, got: $r2")
      assert(s.nodes.values.exists(_.name == "explicit-empty"), "the explicit create persists")
  }

  test("④ P2a: a plugins declaration while the plugins flag is OFF must WARN in the receipt (never silently dropped)") {
    val flagFile = tempRoot / "nebflow.json"
    os.write.over(flagFile, """{"plugins":{"enabled":false}}""")
    // 🔴 夹具存续期必须覆盖**效果**存续期：try/finally 在 by-name body 求值（= 构建 IO）
    // 时就执行 finally ⇒ 文件在 IO 真正跑之前已被删 ⇒ PluginsConfig.enabled 读不到 ⇒ 回落
    // true ⇒ 校验照跑、本用例恒红。改用 guarantee 把删除挂在效果尾部（成败都删）。
    // 判据未动（flag off 仍须「放行 + 回执警告 + 空能力面」）。
    (for
      (_, system, _, rt, ctx) <- mkEnv("p4b")
      r <- nodeEdit(
        nodeInput(
          "decl-p4b",
          "flagged",
          "description" -> Json.fromString("declared while flag off"),
          "task" -> Json.fromString("work"),
          "plugins" -> Json.arr(Json.fromString("some-plugin"))
        ),
        ctx
      )
      n <- rt.store.snapshot.map(_.nodes.values.find(_.name == "flagged"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"flag-off keeps today's ignore-don't-reject semantics, got: $r")
      assert(
        r.exists(_.contains("flag off")) || r.exists(_.contains("enabled=false")),
        s"the receipt must warn that the declaration was NOT applied, got: $r"
      )
      assert(n.exists(_.plugins.isEmpty), "flag-off still stores an empty capability face (unchanged)")
    ).guarantee(IO(os.remove.all(flagFile)))
  }

  test("④ P3: task text naming a Catalog plugin with an empty plugins face warns (warning only, never a hard reject)") {
    // Catalog 夹具：真实目录 + plugin.json + skills 面。🔴 skills 面非可有可无：装载校验
    // 「skills 与 mcp 至少其一」（PluginRegistry 裁定 12）⇒ 只有 plugin.json 的目录会被
    // **装载期拒**、不进 Catalog（snapshot.plugins）⇒ 文本扫描无对象、本用例恒红。
    // 形态对齐 NodeSchemaSlimSpec 的 slim-e2e 夹具（同为 plugin.json + skills/<n>/SKILL.md）。
    val p3dir = tempRoot / "plugins" / "decl-gate-p3"
    os.makeDir.all(p3dir / "skills" / "howto")
    os.write.over(
      p3dir / "plugin.json",
      """{"$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"decl-gate-p3","version":"1.0.0","description":"P3 text-scan fixture"}"""
    )
    os.write.over(
      p3dir / "skills" / "howto" / "SKILL.md",
      """---
        |name: howto
        |description: P3 text-scan fixture skill
        |---
        |## DeclGateP3 Marker
        |Body.""".stripMargin
    )
    for
      (_, system, _, rt, ctx) <- mkEnv("p4c")
      r1 <- nodeEdit(
        nodeInput(
          "decl-p4c",
          "mentions",
          "description" -> Json.fromString("mentions a catalog name"),
          "task" -> Json.fromString("use the decl-gate-p3 plugin skills for this work")
        ),
        ctx
      )
      r2 <- nodeEdit(
        nodeInput(
          "decl-p4c",
          "silent",
          "description" -> Json.fromString("no catalog mention"),
          "task" -> Json.fromString("ordinary work")
        ),
        ctx
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isRight, s"P3 is a warning face — the create must pass, got: $r1")
      assert(r1.exists(_.contains("decl-gate-p3")), s"the receipt must name the mentioned plugin, got: $r1")
      assert(r2.isRight, s"control create passes, got: $r2")
      assert(r2.exists(!_.contains("task text mentions")), s"control receipt carries no P3 warning, got: $r2")
    end for
  }
end NodeDeclarationGateSpec
