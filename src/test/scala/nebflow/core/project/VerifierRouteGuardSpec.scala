package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources, StubLlm}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, NodeTools, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/**
 * fail 路由静默摘除 → 拒绝态 + 显式告警（chain-failroute-guard · 案 A 薄）：**红验锚点**。
 *
 * 语义一句：摘边照摘（退场语义逐字不动），但**受害 verifier 必须同帧落拒绝态**——
 * `verifier-route-lost` 审计（主语 = 受害 verifier）+ `NodeList` 载荷派生键
 * `verifierRoute="lost"`（条件键，缺键 = 合法）。此前受害侧**零留痕**：摘除只在
 * `abandoned` 行里留一个裸计数 `out-refs=1`，审计面无法重建「哪条边被摘、谁被致残」。
 *
 * 改前必红 / 改后必绿（本文件的机械判据）：
 *  - **R1/G1** 工具腿（`NodeEdit(abandon=true)`）⇒ 受害 verifier 的 fail 边被摘除，
 *    但载荷必须带 `verifierRoute="lost"` 且审计必须有 `verifier-route-lost`（主语 = verifier）。
 *  - **R3/A4** 判词腿（verifier 报 `fail` 而路由缺失）⇒ 拒绝态留痕，**不再是**良性退化文案。
 *  - **G2** 自动解禁：补回 `(fail)<worker2>:loop` ⇒ 键**消失**（纯派生、零额外动作）。
 *  - **G3** `pendingOut` 计入「已声明」⇒ 目标 running 期间**不得**报拒绝态（防假阳性）。
 *  - **边界矩阵**：(i) 空 out 的 verifier 合法（创建期两相令牌形态）；(ii) **悬空 fail
 *    目标计非法**（与编辑期硬拒同口径）；健康路由合法；非 verifier 恒不报。
 *  - **G4 负控**：`:loop` 回边目标零便签（**禁用** `pendingSuccession` 承载本事实）——
 *    权威全绿读数 = `CancelLoopTargetNoMarkerSpec`（P1/P1′/P2/L6）+ `AbandonDetachSpec`。
 *
 * 装配口径（与 `AbandonDetachSpec` / `LoopExecutionLegSpec` 同源）：引擎/store 层直挂，
 * 无 `ProjectActor` ⇒ 30s 回填腿由测试**显式点名**调用（零后台竞态）。
 */
class VerifierRouteGuardSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-verifier-route-guard"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"verifier-route-guard spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 装配 ────────────────────────────────────────────────────────

  /**
   * 工具申报状态机（`LoopExecutionLegSpec.FailReportLlm` 同款）：首 turn 发
   * `node_report(fail)`，见回执后收尾 ⇒ 判词由**真实引擎路径**产生（`verifierFailR`）。
   */
  private class FailReportLlm:
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
            Stream(StreamChunk.TextDelta("被判定对象不合格，正式给出 fail verdict。本节点收尾。"), StreamChunk.Done(None, None))
        else
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(
              StreamChunk.ToolCallChunk(
                ToolCall(
                  "vrg-fail-1",
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

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    frames: Ref[IO, List[Json]]
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (j: Json) => frames.update(_ :+ j),
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (typ: String, nodeId: String, payload: Json) =>
          frames.update(
            _ :+ payload.deepMerge(Json.obj("type" -> Json.fromString(typ), "nodeId" -> Json.fromString(nodeId)))
          ),
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

  private def seed(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void

  private def node(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.getNode(id).map(_.getOrElse(fail(s"node '$id' must exist")))

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("verifier-route-guard-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  /** 审计读数（type, nodeId, summary）。 */
  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(
        _.flatMap(l =>
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

  /** 载荷读数单点：NodeList 快照载荷（工具面 / REST flow-map 共用同一序列化点）。 */
  private def payloadOf(rt: ProjectRuntime, id: String): IO[Json] =
    NodeTools.buildNodeListPayload(rt).map { j =>
      j.hcursor
        .downField("nodes")
        .as[List[Json]]
        .getOrElse(Nil)
        .find(_.hcursor.get[String]("id").toOption.contains(id))
        .getOrElse(fail(s"node '$id' must appear in the NodeList payload"))
    }

  private def routeKeyOf(rt: ProjectRuntime, id: String): IO[Option[String]] =
    payloadOf(rt, id).map(_.hcursor.get[String]("verifierRoute").toOption)

  private def keysOf(rt: ProjectRuntime, id: String): IO[List[String]] =
    payloadOf(rt, id).map(_.asObject.map(_.keys.toList.sorted).getOrElse(Nil))

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

  /**
   * 判据拓扑（卡 §4.1 R1/R2 装配）：
   *   worker(被判位) ← `(fail)worker:loop` ← verifier(role=verifier) → land(正常 pass sink)。
   */
  private def verifierFixture(
    store: FlowMapStore,
    now: Long,
    workerStatus: String = NodeLifecycle.Completed
  ): IO[Unit] =
    for
      _ <- seed(
        store,
        NodeDef(
          id = "n-work",
          name = "WORK",
          agent = "general",
          status = workerStatus,
          task = Some("work"),
          result = Some("round-1 output"),
          completedAt = Some(now - 30_000),
          createdAt = now - 90_000
        )
      )
      _ <- seed(
        store,
        NodeDef(
          id = "n-ver",
          name = "VER",
          agent = "general",
          status = NodeLifecycle.Pending,
          task = Some("judge"),
          in = List("n-work"),
          role = NodeRoles.Verifier,
          out = List(OutEdge("n-land"), OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop)),
          createdAt = now - 60_000
        )
      )
      _ <- seed(
        store,
        NodeDef(
          id = "n-land",
          name = "LAND",
          agent = "general",
          status = NodeLifecycle.Pending,
          task = Some("land"),
          in = List("n-ver"),
          createdAt = now - 50_000
        )
      )
    yield ()

  /**
   * 受害 verifier 的三项留痕读数（本 spec 的核心判据，逐条打印便于双跑对照）。
   *
   * 判据：① 载荷派生键 = `"lost"`；② 审计主语 = **受害 verifier**（不是退役节点）；
   * ③ summary 载「被摘目标 / 保留 pass 目标」+ 可行动文案（不是裸计数）。
   */
  private def assertRejectionState(
    rt: ProjectRuntime,
    ws: os.Path,
    verId: String,
    leg: String,
    lostTarget: Option[String]
  ): IO[Unit] =
    for
      key <- routeKeyOf(rt, verId)
      keys <- keysOf(rt, verId)
      audit <- readAudit(ws)
      lost = audit.filter(_._1 == "verifier-route-lost")
      ver <- node(rt, verId)
      _ <- IO(
        println(
          s"[spec] $leg — $verId verifierRoute=$key ; out=${ver.out} ; " +
            s"verifier-route-lost=${lost.map(l => s"[nodeId=${l._2}] ${l._3}")} ; payload keys=${keys.mkString(",")}"
        )
      )
    yield
      assertEquals(
        key,
        Some("lost"),
        s"$leg: the victim verifier must carry the derived rejection-state key (verifierRoute=lost)"
      )
      assertEquals(
        lost.map(_._2).distinct,
        List(verId),
        s"$leg: the audit subject must be the VICTIM VERIFIER, not the retired node (got ${lost.map(_._2)})"
      )
      lostTarget.foreach { tgt =>
        assert(
          lost.exists(_._3.contains(tgt)),
          s"$leg: the line must name the detached fail target '$tgt', got ${lost.map(_._3)}"
        )
      }
      assert(
        lost.exists(s => s._3.contains("(fail)") || s._3.contains("NODE_VERIFIER_NEEDS_ROUTE")),
        s"$leg: the line must carry the actionable restore form, got ${lost.map(_._3)}"
      )

  // ── R1 / G1：工具腿（abandon）────────────────────────────────────

  test(
    "R1/G1 abandon leg: the detach semantics stay byte-for-byte, but the victim verifier lands in the REJECTION STATE in the same frame (payload verifierRoute=lost + verifier-route-lost with the verifier as subject)"
  ) {
    val ws = tempRoot / "ws-r1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vrg-r1-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vrg-r1", ws, system, res, frames)
      _ <- verifierFixture(rt.store, now)
      ver <- node(rt, "n-ver")
      keyBefore <- routeKeyOf(rt, "n-ver")
      _ <- IO(
        println(
          s"[spec] R1 BEFORE — verifier.out=${ver.out} ; verifierRoute=$keyBefore " +
            "(a healthy fail route ⇒ expected: no key)"
        )
      )
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("vrg-r1", "WORK", "abandon" -> Json.fromBoolean(true)), ctx)
      work <- node(rt, "n-work")
      verAfter <- node(rt, "n-ver")
      _ <- assertRejectionState(rt, ws, "n-ver", "R1/G1 after abandon", Some("n-work"))
      landKey <- routeKeyOf(rt, "n-land")
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(keyBefore, None, "BEFORE: a healthy fail route carries no key (缺键 = 合法)")
      assert(r.isRight, s"abandon must succeed, got: $r")
      // ① 退场语义逐字不动：退役位照旧 cancelled + 自身 out 收束 Nebula
      assertEquals(work.status, NodeLifecycle.Cancelled, "the retire semantics are untouched (still cancelled)")
      assertEquals(
        work.out,
        List(OutEdge.root),
        "the retiring node's own out still collapses to the Nebula exit marker"
      )
      // ② 摘边照摘（本批不改这个退场语义）：受害 verifier 的 fail 边被摘除，pass 边保留
      assertEquals(
        verAfter.out,
        List(OutEdge("n-land")),
        "the detach still severs the referrer's fail edge (this batch does NOT change the retire semantics)"
      )
      // ③ 拒绝态留痕三项由 assertRejectionState 断言
      // ④ 事件主语分工：退役位照旧只有自己的 `abandoned` 行（裸计数形态保留）
      assert(
        audit.exists((t, id, s) => t == "abandoned" && id == "n-work" && s.contains("out-refs=1")),
        s"the retire line for the judged worker must stay (out-refs count included), got ${audit.map(_._1)}"
      )
      assertEquals(landKey, None, "a plain sink node must not gain the key (条件键，零字段漂移)")
    end for
  }

  // ── R3 / A4：判词腿（fail 判词 + 路由缺失）───────────────────────

  test(
    "R3/A4 verdict leg: a verifier that reports fail with no usable fail route leaves a rejection-state trace instead of a benign-degradation line"
  ) {
    val ws = tempRoot / "ws-r3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vrg-r3-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new FailReportLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vrg-r3", ws, system, res, frames)
      // 存量 route-less 形态（#7 `llmstallfix-verify` / #8 `xferpreview-e2e` 同形）：
      // 有 out（pass 边在）但**无任何合法的 fail 选通边**。
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-work",
          name = "WORK",
          agent = "general",
          status = NodeLifecycle.Completed,
          task = Some("work"),
          result = Some("round-1 output"),
          completedAt = Some(now - 30_000),
          createdAt = now - 90_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-ver",
          name = "VER",
          agent = "general",
          status = NodeLifecycle.Wiring,
          task = Some("judge"),
          in = List("n-work"),
          deliveredTo = List("n-work"),
          role = NodeRoles.Verifier,
          out = List(OutEdge("n-land")),
          createdAt = now - 60_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-land",
          name = "LAND",
          agent = "general",
          status = NodeLifecycle.Pending,
          task = Some("land"),
          in = List("n-ver"),
          createdAt = now - 50_000
        )
      )
      keyBefore <- routeKeyOf(rt, "n-ver")
      _ <- IO(
        println(
          s"[spec] R3 BEFORE — route-less verifier payload key=$keyBefore " +
            "(pre-fix: absent ⇒ the defect is invisible in both the audit and the payload)"
        )
      )
      _ <- rt.engine.startNode("n-ver")
      _ <- waitUntil(40.seconds) {
        rt.store
          .getNode("n-ver")
          .map(_.exists(n => n.status == NodeLifecycle.Completed || n.status == NodeLifecycle.Failed))
      }
      ver <- node(rt, "n-ver")
      _ <- assertRejectionState(rt, ws, "n-ver", "R3/A4 after the fail verdict", None)
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 派生键描述的是**状态**（不是事件痕迹）：route-less verifier 判词**之前**就已在
      // 拒绝态 ⇒ 键在场。改前该键恒缺席（缺陷在审计面与载荷面双双不可见）。
      assertEquals(
        keyBefore,
        Some("lost"),
        "the derived key describes the STATE: a route-less verifier is in the rejection state even before judging"
      )
      assertEquals(
        ver.lastVerdict,
        Some("fail"),
        "the fail verdict is still recorded (verdict ≠ node status, unchanged)"
      )
      assertEquals(ver.status, NodeLifecycle.Completed, "the node still completes (no refusal to accept the verdict)")
      // 良性退化措辞必须消失（拒绝态口径）
      val benign = audit.filter((_, _, s) =>
        s.contains("loop inert") || s.contains("benign") || s.contains("no re-run route (the node still completes")
      )
      assert(benign.isEmpty, s"the benign-degradation wording must be gone, got: $benign")
      assert(
        audit.exists((t, id, _) => t == "verifier-route-lost" && id == "n-ver"),
        s"the rejection state must be audited with the verifier as subject, got ${audit.map(_._1)}"
      )
    end for
  }

  // ── G2：自动解禁（纯派生键，补回即消失）─────────────────────────

  test(
    "G2 auto-release: re-declaring '(fail)<worker2>:loop' clears the key with zero extra unblock action (purely derived, no migration)"
  ) {
    val ws = tempRoot / "ws-g2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vrg-g2-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vrg-g2", ws, system, res, frames)
      _ <- verifierFixture(rt.store, now)
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-work2",
          name = "WORK2",
          agent = "general",
          status = NodeLifecycle.Pending,
          task = Some("work-2"),
          createdAt = now - 20_000
        )
      )
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("vrg-g2", "WORK", "abandon" -> Json.fromBoolean(true)), ctx)
      lost <- routeKeyOf(rt, "n-ver")
      _ <- IO(println(s"[spec] G2 step 1 — after the detach the victim verifier key=$lost (expected: lost)"))
      r <- nodeEdit(nodeInput("vrg-g2", "VER", "out" -> Json.fromString("(pass)LAND, (fail)WORK2:loop")), ctx)
      released <- routeKeyOf(rt, "n-ver")
      ver <- node(rt, "n-ver")
      _ <- IO(
        println(s"[spec] G2 step 2 — after re-wiring the key=$released ; out=${ver.out} ; pendingOut=${ver.pendingOut}")
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(lost, Some("lost"), "precondition: the detach put the verifier into the rejection state")
      assert(r.isRight, s"re-declaring the fail route must be accepted, got: $r")
      assertEquals(
        released,
        None,
        "the key MUST vanish by itself (derived ⇒ zero extra unblock action, zero migration, zero backfill)"
      )
      assert(
        ver.out.exists(e => e.on.contains(OutEdge.Fail) && OutEdge.isLoopEdge(e)),
        s"the restored route must be wired into out, got ${ver.out}"
      )
    end for
  }

  // ── G3：pendingOut 计入「已声明」（防假阳性）─────────────────────

  test(
    "G3 pendingOut counts as declared: a fail route queued for a RUNNING target is NOT a rejection state (no false positive inside the real auto-wiring window)"
  ) {
    val ws = tempRoot / "ws-g3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vrg-g3-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vrg-g3", ws, system, res, frames)
      _ <- verifierFixture(rt.store, now)
      // 目标 running（实盘窗口：discofix 18:36:59 → 19:01:27 = 24.5 分钟）——控制边进 pendingOut。
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-work3",
          name = "WORK3",
          agent = "general",
          status = NodeLifecycle.Running,
          task = Some("work-3"),
          startedAt = Some(now - 10_000),
          createdAt = now - 20_000
        )
      )
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("vrg-g3", "WORK", "abandon" -> Json.fromBoolean(true)), ctx)
      lost <- routeKeyOf(rt, "n-ver")
      _ <- IO(println(s"[spec] G3 step 1 — verifier key after the detach=$lost (expected: lost)"))
      r <- nodeEdit(nodeInput("vrg-g3", "VER", "out" -> Json.fromString("(pass)LAND, (fail)WORK3:loop")), ctx)
      verQueued <- node(rt, "n-ver")
      keyQueued <- routeKeyOf(rt, "n-ver")
      auditQueued <- readAudit(ws)
      _ <- IO(
        println(
          s"[spec] G3 step 2 (target running) — key=$keyQueued ; out=${verQueued.out} ; " +
            s"pendingOut=${verQueued.pendingOut} ; wiring-deferred=${auditQueued.count(_._1 == "wiring-deferred")}"
        )
      )
      // 目标离开 running ⇒ 自动接线（pendingOut → out）；键仍缺席
      _ <- rt.store
        .mutate(s =>
          s.copy(nodes = s.nodes.updated("n-work3", s.nodes("n-work3").copy(status = NodeLifecycle.Pending)))
        )
        .void
      _ <- rt.engine.applyDeferredWiring()
      verWired <- node(rt, "n-ver")
      keyWired <- routeKeyOf(rt, "n-ver")
      auditWired <- readAudit(ws)
      _ <- IO(
        println(
          s"[spec] G3 step 3 (target left running) — key=$keyWired ; out=${verWired.out} ; " +
            s"pendingOut=${verWired.pendingOut} ; wiring-applied=${auditWired.count(_._1 == "wiring-applied")}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(lost, Some("lost"), "precondition: the detach put the verifier into the rejection state")
      assert(r.isRight, s"declaring a fail route onto a running target must be accepted (deferred), got: $r")
      assert(
        verQueued.pendingOut.exists(e => e.on.contains(OutEdge.Fail) && OutEdge.isLoopEdge(e)),
        s"the control edge must be queued in pendingOut, got ${verQueued.pendingOut}"
      )
      assert(auditQueued.exists(_._1 == "wiring-deferred"), "the deferral must be audited (wiring-deferred)")
      assertEquals(
        keyQueued,
        None,
        "pendingOut IS a declaration ⇒ no rejection state inside the wiring window (this is the false-positive guard)"
      )
      assert(verWired.pendingOut.isEmpty, s"the deferred edge must be auto-wired, got ${verWired.pendingOut}")
      assert(
        verWired.out.exists(e => e.on.contains(OutEdge.Fail) && OutEdge.isLoopEdge(e)),
        s"the fail route must end up in out, got ${verWired.out}"
      )
      assertEquals(keyWired, None, "and the key stays absent after the auto-wiring")
    end for
  }

  // ── 边界矩阵（判据 D 的三条边界）───────────────────────────────

  test(
    "boundary matrix: empty-out verifier is legal (two-phase token form); a DANGLING fail target counts as invalid (same yardstick as the edit-time gate); a healthy route and a non-verifier are never flagged"
  ) {
    val ws = tempRoot / "ws-bound"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vrg-bound-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vrg-bound", ws, system, res, frames)
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-work",
          name = "WORK",
          agent = "general",
          status = NodeLifecycle.Pending,
          task = Some("work"),
          createdAt = now - 90_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-land",
          name = "LAND",
          agent = "general",
          status = NodeLifecycle.Pending,
          task = Some("land"),
          createdAt = now - 80_000
        )
      )
      // (a) 健康路由 ⇒ 缺键
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-healthy",
          name = "HEALTHY",
          agent = "general",
          status = NodeLifecycle.Wiring,
          task = Some("judge"),
          role = NodeRoles.Verifier,
          out = List(OutEdge("n-land"), OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop)),
          createdAt = now - 70_000
        )
      )
      // (b) 边界 (i)：空 out（创建期两相令牌形态）⇒ 缺键
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-emptyout",
          name = "EMPTYOUT",
          agent = "general",
          status = NodeLifecycle.Pending,
          task = Some("judge"),
          role = NodeRoles.Verifier,
          out = Nil,
          createdAt = now - 60_000
        )
      )
      // (c) 边界 (ii)：fail 边目标悬空（n-ghost 不在活动区）⇒ **计非法**
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-dangling",
          name = "DANGLING",
          agent = "general",
          status = NodeLifecycle.Wiring,
          task = Some("judge"),
          role = NodeRoles.Verifier,
          out = List(OutEdge("n-land"), OutEdge("n-ghost", Set(OutEdge.Fail), OutEdge.Loop)),
          createdAt = now - 50_000
        )
      )
      // (d) 非 verifier（task 带 :loop 的畸形存量）⇒ 恒不报（角色门）
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-taskloop",
          name = "TASKLOOP",
          agent = "general",
          status = NodeLifecycle.Wiring,
          task = Some("work"),
          role = NodeRoles.Task,
          out = List(OutEdge("n-land"), OutEdge("n-ghost", Set(OutEdge.Fail), OutEdge.Loop)),
          createdAt = now - 40_000
        )
      )
      healthy <- routeKeyOf(rt, "n-healthy")
      emptyout <- routeKeyOf(rt, "n-emptyout")
      dangling <- routeKeyOf(rt, "n-dangling")
      taskloop <- routeKeyOf(rt, "n-taskloop")
      emptyKeys <- keysOf(rt, "n-emptyout")
      work <- node(rt, "n-work")
      _ <- IO(
        println(
          s"[spec] boundary — healthy=$healthy emptyout=$emptyout dangling=$dangling taskloop=$taskloop ; " +
            s"empty-out payload keys=${emptyKeys.mkString(",")} ; loop target pendingSuccession=${work.pendingSuccession} " +
            s"in=${work.in}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(healthy, None, "a verifier with a resolvable fail route is legal (缺键 = 合法)")
      assertEquals(
        emptyout,
        None,
        "boundary (i): an empty-out verifier keeps the existing two-phase-token semantics (legal, no key)"
      )
      assertEquals(
        dangling,
        Some("lost"),
        "boundary (ii): a DANGLING fail target counts as invalid — same yardstick as the edit-time hard refusal"
      )
      assertEquals(taskloop, None, "the role gate holds: a non-verifier is never flagged")
      // G4 负控（本批红线）：回边目标零便签、回边不写 in 镜像 —— 不新造 pendingSuccession 承载面
      assertEquals(work.pendingSuccession, Nil, "the ':loop' target must not receive any 待承接 marker")
      assertEquals(work.in, Nil, "a ':loop' control edge is never mirrored into the target's in ledger")
    end for
  }

  // ── G4 负控（载荷面零漂移）─────────────────────────────────────

  test(
    "G4 negative control: the guard adds exactly one conditional key — healthy nodes keep their payload key set byte-for-byte"
  ) {
    val ws = tempRoot / "ws-g4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vrg-g4-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vrg-g4", ws, system, res, frames)
      _ <- verifierFixture(rt.store, now)
      verKeys <- keysOf(rt, "n-ver")
      landKeys <- keysOf(rt, "n-land")
      workKeys <- keysOf(rt, "n-work")
      _ <- IO(
        println(
          s"[spec] G4 payload key sets — verifier=${verKeys.mkString(",")} ; " +
            s"land=${landKeys.mkString(",")} ; work=${workKeys.mkString(",")}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 健康 verifier：role 键在（非 task 携带），verifierRoute 键**不在**
      assert(
        verKeys.contains("role") && verKeys.contains("out") && !verKeys.contains("verifierRoute"),
        s"a healthy verifier must keep its existing key set (role/out; no verifierRoute), got $verKeys"
      )
      // task 节点：连 role 都不带（缺键 = task），verifierRoute 更不在
      for (id, ks) <- List("n-land" -> landKeys, "n-work" -> workKeys) do
        assert(
          !ks.contains("role") && !ks.contains("verifierRoute"),
          s"a plain task node must keep its byte-level key set (no role / no verifierRoute), got $id=$ks"
        )
    end for
  }
end VerifierRouteGuardSpec
