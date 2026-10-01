package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources, StubLlm}
import nebflow.shared.PathUtil

import scala.concurrent.duration.*
import scala.util.Random

/**
 * eng-deferred-cancel 批（`chain-tasklist-anim` 链 · 2026-10-02）——**K-1 判据③**
 * （「链的取消态仍可判读」：引擎面 deferred-cancel 期间必须有**可读面**，禁做成静默延迟）
 * 与**零载荷形状变更**的自证判据。
 *
 * 面（逐条对应任务书 §二.1 K-1 / §二.1 B / §二.3 红线）：
 *  - L1 意图可判读：`withChainCancelIntent` 登记后 `cancellingAtOf` 可读出登记时刻。
 *  - L2 **不入三态投影**：意图在途时 `statusOf` 仍是 `active`（🔴 禁改 `cancelled > paused`
 *    优先级、禁动 REST/WS 载荷形状）——本批只新增「进行中」的可读面，不改状态投影。
 *  - L3 **终态原子清**：`withChainControl(cancelled)` 与意图清除落在**同一次** State 改写上
 *    ⇒「正在取消 ∧ 已取消」不可同时可读。
 *  - L4 TTL 读时过滤：超窗条目不参与判读（崩在「意图已写、取消未跑」窄窗内的残留自清），
 *    且写入点顺带惰性 prune。
 *  - L5 落盘面：`setChainCancelIntent` 真落盘（重开可读出）、`setChainControl(cancelled)`
 *    把意图清除也落盘（否则内存与磁盘静默不一致）。
 *  - L6 面分工：节点级 `cancelNodes` **不得**伪造链级意图（意图只由 `cancelChain` 腿登记）。
 *
 * **红验语义**（逐条钉死「把判据改坏 ⇒ 本 spec 必红」的变异）：
 * 评测红读数以「把实现回改成改动前形态」为变异臂——`withChainCancelIntent` 恒返回入参
 * ⇒ L1/L5 红；`cancellingChains` 进 `statusOf` ⇒ L2 红；`withChainControl` 不清意图 ⇒
 * L3 红；`cancellingAtOf` 去掉 TTL 过滤 ⇒ L4 红。
 *
 * 本位只做**定向**分批跑（`testOnly`），全量 `sbt test` 归零——见任务书 §二.4。
 */
class DeferredCancelLedgerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val t0 = 1750000000000L

  private def stateWithChain(chainId: String): ChainLedger.State =
    ChainLedger.observe(
      ChainLedger.State(project = "p"),
      List(ChainInfo(id = chainId, memberIds = List("n-a", "n-b"))),
      t0
    ).state

  // ── L1 意图可判读（判据③的承载）────────────────────────────────────────

  test("L1: recording a cancel intent makes the chain readable as 'cancellation in progress'") {
    val st = stateWithChain("chain-l1")
    val after = ChainLedger.withChainCancelIntent(st, "chain-l1", t0 + 1000L)
    assertEquals(
      ChainLedger.cancellingAtOf(after, "chain-l1", t0 + 1000L),
      Some(t0 + 1000L),
      "K-1③: the in-progress cancel intent must be readable (禁静默延迟)"
    )
    assertEquals(
      ChainLedger.cancellingChainsAt(after, t0 + 1000L).keySet,
      Set("chain-l1"),
      "K-1③: the in-progress face must be enumerable for the REST/WS read path"
    )
  }

  // ── L2 不入三态投影（零载荷形状变更）───────────────────────────────────

  test("L2: the in-progress intent does NOT enter the three-state projection (payload shape unchanged)") {
    val st = stateWithChain("chain-l2")
    val after = ChainLedger.withChainCancelIntent(st, "chain-l2", t0 + 1000L)
    assertEquals(
      ChainLedger.statusOf(after, "chain-l2"),
      ChainLedger.StatusActive,
      "an in-flight cancel intent is NOT a terminal state: projection stays active (禁改 cancelled > paused 优先级)"
    )
    assertEquals(ChainLedger.cancelledAtOf(after, "chain-l2"), None, "cancelledAt must stay empty while the cancel is in progress")
    assertEquals(ChainLedger.pausedAtOf(after, "chain-l2"), None, "the intent must not touch pausedChains")
    assert(
      !ChainLedger.blocksDispatch(after, "chain-l2"),
      "the in-progress intent must not block dispatch (that would silently change chain control semantics)"
    )
  }

  // ── L3 终态原子清（两面不可同时可读）────────────────────────────────────

  test("L3: the terminal cancel write clears the in-progress intent in the SAME state rewrite") {
    val st = stateWithChain("chain-l3")
    val inFlight = ChainLedger.withChainCancelIntent(st, "chain-l3", t0 + 1000L)
    assertEquals(ChainLedger.cancellingAtOf(inFlight, "chain-l3", t0 + 1000L).isDefined, true, "precondition: intent readable")
    val landed = ChainLedger.withChainControl(inFlight, "chain-l3", ChainLedger.StatusCancelled, t0 + 2000L)
    assertEquals(
      ChainLedger.cancellingAtOf(landed, "chain-l3", t0 + 2000L),
      None,
      "「正在取消 ∧ 已取消」不可同时可读 — the terminal write must clear the intent atomically"
    )
    assertEquals(ChainLedger.cancelledAtOf(landed, "chain-l3"), Some(t0 + 2000L), "the terminal cancellation itself must still be recorded")
    assertEquals(ChainLedger.statusOf(landed, "chain-l3"), ChainLedger.StatusCancelled, "projection priority stays cancelled > paused > active")
  }

  // ── L4 TTL 读时过滤 + 写入惰性 prune ────────────────────────────────────

  test("L4: an intent older than the TTL reads as absent, and a new record prunes the stale entry") {
    val st = stateWithChain("chain-l4")
    val recorded = ChainLedger.withChainCancelIntent(st, "chain-l4", t0)
    val justInside = t0 + ChainLedger.CancellingTtlMs - 1L
    val past = t0 + ChainLedger.CancellingTtlMs
    assertEquals(
      ChainLedger.cancellingAtOf(recorded, "chain-l4", justInside),
      Some(t0),
      "inside the window the intent is readable (no false negative)"
    )
    assertEquals(
      ChainLedger.cancellingAtOf(recorded, "chain-l4", past),
      None,
      "past the window the intent reads as absent — a crash between intent and cancel must not leave a permanent 'cancelling' chain"
    )
    assertEquals(ChainLedger.cancellingChainsAt(recorded, past), Map.empty[String, Long], "the enumeration honours the same TTL")
    val pruned = ChainLedger.withChainCancelIntent(recorded, "chain-l4", past)
    assertEquals(
      pruned.cancellingChains.keySet,
      Set("chain-l4"),
      "the write path prunes lazily: only the fresh row survives (no second judge face)"
    )
  }

  // ── L5 落盘面 ─────────────────────────────────────────────────────────

  test("L5: the intent is persisted, and the terminal write persists its clearing too") {
    val dir = os.temp.dir(prefix = "nb-deferred-cancel-ledger-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    val now = System.currentTimeMillis()
    for
      store <- ChainLedgerStore.open("deferred-ledger", path, arch)
      comps = List(ChainInfo(id = "chain-l5", memberIds = List("n-a", "n-b")))
      _ <- store.reconcile(comps, Set("n-a", "n-b"), Map.empty, Set.empty, now)
      _ <- store.setChainCancelIntent("chain-l5", now)
      onDisk <- IO.blocking(os.read(path))
      midSnap <- store.snapshot
      _ <- store.setChainControl("chain-l5", ChainLedger.StatusCancelled, now + 50L)
      finalDisk <- IO.blocking(os.read(path))
      reopened <- ChainLedgerStore.open("deferred-ledger", path, arch)
      reloaded <- reopened.snapshot
    yield
      assertEquals(
        ChainLedger.cancellingAtOf(midSnap, "chain-l5", now + 50L),
        Some(now),
        "the intent must be readable from the live store right after the chain-level write point"
      )
      assert(
        onDisk.contains("cancellingChains"),
        s"K-1③ requires a PERSISTED readable face (a silent in-memory flag would not survive / not be readable): ${onDisk.take(400)}"
      )
      assertEquals(
        ChainLedger.cancellingAtOf(reloaded, "chain-l5", now + 100L),
        None,
        "the terminal write clears the intent ON DISK as well (memory and disk must not silently disagree)"
      )
      assertEquals(
        ChainLedger.cancelledAtOf(reloaded, "chain-l5"),
        Some(now + 50L),
        "the terminal cancellation survives the reopen"
      )
      assert(!finalDisk.contains(s""""chain-l5":$now"""), "the stale intent row must not remain on disk after the terminal write")
  }

  // ── L6 面分工：节点级取消不得伪造链级意图 ────────────────────────────────

  private val tempRoot: os.Path = os.pwd / "target" / "test-deferred-cancel-ledger"
  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** 真 NodeEngine 夹具（沿 `ChainCascadeSpec` 同款形态；零 spawn、零端口）。 */
  private def mountRig(name: String): IO[(ProjectRuntime, os.Path, ActorSystem, SharedResources)] =
    PathUtil.setDataRoot(tempRoot)
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "agents" / "general")
    os.write.over(
      tempRoot / "agents" / "general" / "agent.json",
      """{"name":"general","description":"deferred ledger rig agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
    val ws = tempRoot / s"ws-$name"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deferred-ledger-$name-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
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
    yield (rt, ws, system, res)

  private def seedChain(rt: ProjectRuntime, chainId: String, a: String, b: String): IO[Unit] =
    // 弱连通分量靠**真实 out 边**成形（沿 `ChainCancelSpec.linearChain` 同款口径；
    // 只写 `deps` 不成链——实测该夹具下分量退化为单成员，前置断言当场抓住）。
    rt.store
      .mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          a -> NodeDef(id = a, name = a, agent = "general", status = NodeLifecycle.Pending, out = List(OutEdge(b)), createdAt = 1L),
          b -> NodeDef(id = b, name = b, agent = "general", status = NodeLifecycle.Pending, in = List(a), createdAt = 2L)
        ))
      )
      .void

  test("L6: the chain-cancel leg records the intent on the persisted ledger (and a node-level cancel must not)") {
    val name = "deferred-l6"
    val program = for
      mounted <- mountRig(name)
      (rt, ws, system, res) = mounted
      _ <- seedChain(rt, "chain-n-l6", "n-l6a", "n-l6b")
      // 链号派生口径：`chain-<分量内 createdAt 最早成员 id>`（本夹具最早 = n-l6a）
      resolved <- rt.store.chainMembersOf("chain-n-l6a")
      ledgerPath = ws / ".nebflow" / ChainLedger.FileName
      // 前置：取消前链控面无在途意图（走**落盘面**读，避免只看内存）
      before <- IO.blocking(if os.exists(ledgerPath) then os.read(ledgerPath) else "")
      _ <- IO(assert(!before.contains("cancellingChains"), s"precondition: no intent face before the chain cancel (file=${os.exists(ledgerPath)})"))
      // 链级腿：意图必须在**任何节点被信号之前**登记（判据③的可判读面）
      rep <- rt.engine.cancelChain("chain-n-l6a", CancelSource.User, "user wants this track gone")
      snap <- rt.store.chainLedgerStore.snapshot
      afterIntent <- IO.blocking(if os.exists(ledgerPath) then os.read(ledgerPath) else "")
    yield
      assert(resolved.exists(_.info.memberIds.size >= 2), s"precondition: a 2-member chain must resolve, got: $resolved")
      assert(rep.isRight, s"the chain cancel must be accepted, got: $rep")
      assert(
        afterIntent.contains("cancellingChains"),
        s"K-1③: the chain-cancel leg must leave a PERSISTED in-progress face readable while the deferred legs run, " +
          s"got ledger file exists=${os.exists(ledgerPath)}: ${afterIntent.take(500)}"
      )
      assertEquals(
        ChainLedger.cancellingChainsAt(snap, System.currentTimeMillis()).keySet,
        Set("chain-n-l6a"),
        "K-1③: exactly the cancelled chain must read as in progress (a raw cancelChain has not landed the terminal state yet)"
      )
      assertEquals(
        ChainLedger.statusOf(snap, "chain-n-l6a"),
        ChainLedger.StatusActive,
        "K-1③: the in-progress face is NOT a state — the three-state projection is untouched"
      )
    program.guarantee(
      IO(PathUtil.setDataRoot(originalRoot))
    )
  }

  // ── L7 settleDeferredCancel：探针必须锚「会话真的停了」而非登记表 ─────────
  //
  // 这条是 K-1 判据①的**引擎侧护栏**：`runWithAgent` 的取消腿放行 deferred stop 后，
  // 必须以「会话已终止」为放行条件再走 `system.stop(ref)` 一阶拆解；否则一阶拆解会
  // 与「落批」赛跑，把 K-2 要保的产物重新砍掉。
  // 探针选择是**承重**的（实现头注点名）：`system.isAlive` = actor 系统自己的存活登记表
  // （actor 循环在 guarantee 里自己摘除）；`resources.agentRegistry` 由**本 fiber** 摘除、
  // 发生在本等待**之后** ⇒ 轮询它永远看不到转变、只会白烧满额 grace。

  test("L7: settleDeferredCancel observes the real session death (isAlive probe), not the engine's own registry") {
    val name = "deferred-l7"
    val program = for
      mounted <- mountRig(name)
      (rt, _, system, res) = mounted
      sid = "deferred-l7-session"
      ref <- system.spawn(
        {
          def loop: nebflow.actor.Behavior[nebflow.actor.AgentCommand] =
            nebflow.actor.Behaviors.receiveMessage[nebflow.actor.AgentCommand](_ => IO.pure(loop))
          loop
        },
        sid
      )
      // 登记表**保持有记录**：这正是实现刻意不用的那个探针面（本 fiber 稍后才摘）
      _ <- res.agentRegistry.update(
        _ + (sid -> nebflow.actor.AgentRecord(sid, ref, nebflow.actor.AgentKind.Delegate, sid))
      )
      aliveBefore <- system.isAlive(ref.path)
      // 停掉会话 actor（actor 循环自己摘除 isAlive 登记；引擎登记表**不动**）
      _ <- system.stop(ref)
      stopped <- rt.engine.settleDeferredCancel(ref)
      stillRegistered <- res.agentRegistry.get.map(_.contains(sid))
      t0 <- IO(System.currentTimeMillis())
      // 对照臂：登记表里仍有记录 ⇒ 若探针误用登记表则必然烧满 grace
      second <- rt.engine.settleDeferredCancel(ref)
      elapsed = System.currentTimeMillis() - t0
    yield
      assert(aliveBefore, "precondition: the freshly spawned session must be alive")
      assert(
        stopped,
        "L7 VIOLATED — settleDeferredCancel must observe the real session death (isAlive probe) and return true"
      )
      assert(
        stillRegistered,
        "L7 precondition: the engine's own agentRegistry entry must still be present (it is removed later by the caller fiber)"
      )
      assert(
        elapsed < NodeStarter.DeferredStopGraceMs / 2,
        s"L7 VIOLATED — the second settle returned in ${elapsed}ms, i.e. it did NOT burn the grace window; " +
          "a probe pointed at agentRegistry (still registered) would have slept the full ${NodeStarter.DeferredStopGraceMs}ms"
      )
      assert(second, "L7: a repeated settle on an already-dead session must still report stopped")
    program.guarantee(
      IO(PathUtil.setDataRoot(originalRoot))
    )
  }

end DeferredCancelLedgerSpec
