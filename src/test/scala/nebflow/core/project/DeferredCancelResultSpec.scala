package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/**
 * eng-deferred-cancel 批（`chain-tasklist-anim` 链 · 2026-10-02）——**K-2**
 * （「deferred-cancel 终止时保留已 produced 产物，非覆盖清空」）与**取消原因承载分离**
 * 的自证判据。
 *
 * 面（逐条对应任务书 §二.1 B / §二.3 红线）：
 *  - K1 已产出 `result` 在取消后**仍可读出**（原因**前置**、产物在后，不得互相覆盖）。
 *  - K2 取消原因前缀恒在 offset 0（`CancelSource.fromResult` 用 `startsWith` 解析 ⇒
 *    前置是硬契约，不是排版选择）——并给改动前「覆盖」形态的对照读数。
 *  - K3 二次取消**幂等**：同一节点再取消一次 ⇒ 恰一个原因、不叠加（判据复用既有解析单点）。
 *  - K4 无产物时行为**逐字不变**（`result` = 单纯取消原因），即本项只影响「已有产物」那一面。
 *  - K5 面①（已终态成员的 `preserved ⊎ skipped` 三分区语义）不受影响：本 spec 只读
 *    `NodeDef.result`，不触分区投影。
 *
 * **红验语义**（变异 ⇒ 本 spec 必红）：把 `preservedCancelResult` 回改成「恒返回 rendered」
 * （= 改动前的覆盖形态）⇒ K1/K3 红、K2 的对照读数改观；把前置改成后置（`s"$prev\n\n$rendered"`）
 * ⇒ K2 红（`fromResult` 反解为 `None`）。
 */
class DeferredCancelResultSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-deferred-cancel-result"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"deferred-cancel result spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** 静默 LLM 桩：本 spec 只验终态写点，turn 不参与。 */
  private class QuietLlm:
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mount(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
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

  private def mkNode(id: String, status: String, result: Option[String] = None): NodeDef =
    NodeDef(
      id = id,
      name = id.toUpperCase,
      agent = "general",
      task = Some(s"$id task"),
      status = status,
      result = result,
      createdAt = System.currentTimeMillis()
    )

  private def nodeOf(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.getOrElse(id, fail(s"node '$id' must still exist")))

  /**
   * Count plain-substring occurrences of the cancel-reason rendering marker.
   * Deliberately NOT `String.split`: that takes a REGEX, and `cancelled[source=`
   * contains a character class opener — the first draft of this spec died with
   * `PatternSyntaxException` instead of measuring the thing under test.
   */
  private def reasonCount(s: String): Int =
    val marker = "cancelled[source="
    var idx = s.indexOf(marker)
    var n = 0
    while idx >= 0 do
      n += 1
      idx = s.indexOf(marker, idx + marker.length)
    n

  /** 真实取消写点（detach/notify 关掉：本 spec 只量 `result` 组成，不量级联/通知）。 */
  private def cancel(rt: ProjectRuntime, id: String, reason: String): IO[Unit] =
    rt.engine.cancelNode(id, reason, CancelSource.User, detach = false, notify = false, emitNotify = false)

  private def withRig(name: String)(f: (ProjectRuntime, ActorSystem) => IO[Unit]): IO[Unit] =
    val ws = tempRoot / s"ws-$name"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deferred-result-$name-${Random.nextInt(100000)}")
    val res = SpecResources.mkResources(system, tempRoot, new QuietLlm().handle)
    for
      resources <- res
      rt <- mount(name, ws, system, resources)
      _ <- f(rt, system).guarantee(system.stopAll.handleErrorWith(_ => IO.unit))
    yield ()

  // ── K1 / K2：已产出产物保留 + 原因前缀仍在 offset 0（含改动前对照）──────────

  test("K1/K2: an already-produced result survives the cancel, with the reason still at offset 0") {
    val produced = "PRODUCED_ARTIFACT: 12 of 20 samples analysed; partial report written to out/report.md"
    withRig("k12") { (rt, _) =>
      for
        _ <- seed(rt, mkNode("n-k12", NodeLifecycle.Running, result = Some(produced)))
        before <- nodeOf(rt, "n-k12")
        _ <- cancel(rt, "n-k12", "chain cancel: upstream node failed")
        after <- nodeOf(rt, "n-k12")
      yield
        assertEquals(before.result, Some(produced), "precondition: the node has a produced result before the cancel")
        assertEquals(after.status, NodeLifecycle.Cancelled, "the cancel must still reach the cancelled terminal state")
        val res = after.result.getOrElse(fail("K-2: cancelled node must carry a result"))
        assert(
          res.contains(produced),
          s"K-2 VIOLATED — the produced artifact was overwritten by the cancel reason (pre-batch form). got: $res"
        )
        assert(
          res.startsWith(s"cancelled[source=${CancelSource.UserCode}]:"),
          s"K1/K2: the cancel-reason prefix must stay at offset 0 (fromResult parses with startsWith). got: $res"
        )
        assertEquals(
          CancelSource.fromResult(after.result),
          Some(CancelSource.User),
          "K2: the source must remain machine-readable after the preservation change"
        )
        assert(
          res.endsWith(produced),
          s"K1: the produced artifact must be the trailing payload, verbatim. got: $res"
        )
    }
  }

  // ── K3a：可达入口的幂等（零写；零回归控制，非本批新判据）──────────────────
  //
  // 可达性/分层说明（写错判据的坑，先行钉死）：`ChainCancelScope` 闸在
  // **`cancelNodes`**（链级/节点级入口）里，不在 `NodeCompletion.cancelNode` 本体
  // ⇒ 第二次取消经**入口**不可达终态写点（幂等出口：零写零信号零通知），而**直调**本体
  // 仍会再写一次。故本用例走**入口**（`cancelNodes`）量上游闸；`preservedCancelResult`
  // 的防线另见 K3b（直调探针）。
  // 已有先例：`ChainCancelSpec` C6 已钉「第二调用 == 0 帧/0 注入/0 审计」全链形态。

  test("K3a: re-cancelling an already-cancelled node through the entry point is a zero-write no-op") {
    val produced = "ARTIFACT-B: 3 findings persisted"
    withRig("k3a") { (rt, _) =>
      for
        _ <- seed(rt, mkNode("n-k3a", NodeLifecycle.Running, result = Some(produced)))
        first <- rt.engine.cancelNodes(List("n-k3a"), CancelSource.User, "chain cancel: first", cascade = false)
        once <- nodeOf(rt, "n-k3a")
        second <- rt.engine.cancelNodes(List("n-k3a"), CancelSource.User, "chain cancel: second", cascade = false)
        twice <- nodeOf(rt, "n-k3a")
      yield
        assertEquals(first.cancelled.map(_.nodeId), List("n-k3a"), "precondition: the first cancel terminalizes the node")
        assertEquals(once.status, NodeLifecycle.Cancelled, "precondition: the node is cancelled after the first call")
        assertEquals(second.cancelled, Nil, s"K3a: the entry point must skip a terminal node, got: ${second.cancelled}")
        assertEquals(
          second.preserved.map(_.nodeId),
          List("n-k3a"),
          "K3a: the already-cancelled node lands in `preserved` (terminal), not in `cancelled`"
        )
        assertEquals(
          twice.result,
          once.result,
          "K3a: a re-cancel through the entry point must write NOTHING (one reason + the artifact, byte-identical)"
        )
        assert(
          twice.result.exists(_.contains(produced)),
          s"K3a: the produced artifact stays readable, got: ${twice.result}"
        )
    }
  }

  // ── K3b：终态写点的第二道防线（不可达入口 · 直调探针）────────────────────
  //
  // `preservedCancelResult` 的不叠加规则是**第二道防线**：经 `ChainCancelScope` 闸后
  // 不可达（见 K3a），故此处**直调** `cancelNode` 把节点摆到「已 cancelled 且 result 已是
  // 取消渲染」的形态上探它。判据 = 恰一个原因（禁叠成两个）+ 仍可解析。
  // 🔴 不主张「产物在此路径也被保留」——本路径连第一次的产物都已被既有的覆盖形态替代；
  // 本批的可达保留面见 K1/K2。

  test("K3b: the write-point's second line of defence keeps exactly one reason (no stacking)") {
    val alreadyCancelled = s"cancelled[source=${CancelSource.UserCode}]: reason=first"
    withRig("k3b") { (rt, _) =>
      for
        _ <- seed(rt, mkNode("n-k3b", NodeLifecycle.Cancelled, result = Some(alreadyCancelled)))
        _ <- cancel(rt, "n-k3b", "second")
        after <- nodeOf(rt, "n-k3b")
      yield
        assertEquals(
          reasonCount(after.result.getOrElse("")),
          1,
          s"K3b: a re-cancel must not stack a second reason rendering (un-doubling), got: ${after.result}"
        )
        assertEquals(
          CancelSource.fromResult(after.result),
          Some(CancelSource.User),
          "K3b: the single reason must still be machine-readable"
        )
    }
  }

  // ── K4：无产物 ⇒ 逐字不变 ──────────────────────────────────────────────

  test("K4: with no produced result the rendering is byte-identical to the historical form") {
    withRig("k4") { (rt, _) =>
      for
        _ <- seed(rt, mkNode("n-k4", NodeLifecycle.Running, result = None))
        _ <- cancel(rt, "n-k4", "chain cancel: nothing produced yet")
        after <- nodeOf(rt, "n-k4")
      yield
        assertEquals(
          after.result,
          Some(s"cancelled[source=${CancelSource.UserCode}]: reason=chain cancel: nothing produced yet"),
          "K4: no artifact ⇒ the result stays exactly the cancel reason (this item only changes the produced-artifact face)"
        )
    }
  }

end DeferredCancelResultSpec
