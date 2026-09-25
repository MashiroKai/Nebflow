package nebflow.core.project

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * **mergefifo-engine 批**（2026-09-13，作者 A-4 裁决收窄落地）引擎面回归族。
 *
 * 语义（作者原话逐字）：「**每个项目 git 目录下，只能同时有一个合并节点在工作。**」
 * 落地形态 = 与既有 verdict 闸**同构**的启动闸（同三落点：`startNode` 收口 /
 * `settleRunnableSweep` 资格回扫 / `settleTo` barrier 结算），持有者 = 状态派生
 * （`merge=true ∧ status=running`），终态写点自动释放（无文件、无 TTL、无孤儿）。
 * 真源 = 规格件 `.nebflow/Spec/20260913_merge-window-fifo.md`；纯判据
 * [[MergeMutexPolicy]]。
 *
 * 用例面（判红清单逐条，缺一不算完成）：
 *  - [[MergeMutexGateSpec]] ① 互斥：同键两个 merge 节点同时就绪 ⇒ **恰一个**进运行；
 *  - ② FIFO 次序：后到者不得插队（**rank = (readyAt, createdАt, id)**——为与 createdAt
 *    序可区分，夹具刻意把 createdAt 与 readyAt 设成**反序**：若实现用 createdAt 排序，
 *    本用例必红）；
 *  - ③ 释放：持有者完成 / 取消 / 失败三种收尾 ⇒ 下一个在**有界**内启动（三条读数）；
 *  - ④ 跨键并行：不同 git 目录（两项目两 repo）⇒ 两 merge 节点**同时**运行（零停等）；
 *  - ⑤ O-1 告警：两项目共用同一 git 目录 ⇒ `merge-queue` 告警**必现**（claim/抢占
 *    按作者令不实现 ⇒ 同用例里同时把「漏互斥」这一**已知缺口**读出来）；
 *  - ⑥ O-2：`deps` 挂 verifier 且未 pass ⇒ merge 不启动（fail 持有 / pass 放行对照）；
 *  - 键判据：`realpath(git-common-dir)`——worktree 与主仓**同键**、异 repo 异键、
 *    非 git 目录回落自身路径；
 *  - 非 merge 节点：**零行为变化**（两个普通节点并行照跑，闸是 merge-only）。
 *
 * 变异臂（自证；见节点报告）：去掉启动闸 ⇒ ①② 必红；去掉 O-1 检测 ⇒ ⑤ 必红。
 */
class MergeMutexGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-mutex-gate"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"merge mutex gate spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /**
   * 闸控 LLM：每个节点会话的第一轮从 `gates` 取一个放行闸（取不到 = 直接放行）。
   * 取到闸的会话**停在 Running** 直到测试显式放行——FIFO/互斥/并行三类判据都需要
   * 「持有者仍在跑」这一可观测窗口（否则亚秒级完成会把串行与并行读成同一形态）。
   */
  private class GatedLlm(gates: Ref[IO, List[Deferred[IO, Unit]]]):
    val calls: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream.eval(
          calls.update(_ + 1) *>
            gates
              .modify { st =>
                st match
                  case Nil => (st, Option.empty[Deferred[IO, Unit]])
                  case h :: t => (t, Some(h))
              }
              .flatMap {
                case Some(d) => d.get
                case None => IO.unit
              }
        ) >> Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  end GatedLlm

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
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段腿 2 显式关：本 fixture 主题非 node_report 语义（与
        // MergeVerdictGateSpec / NodeMountEnforceSpec 同款装配）。
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

  private def node(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.getNode(id).map(_.getOrElse(fail(s"node '$id' must exist")))

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

  private def waitStatus(
    rt: ProjectRuntime,
    id: String,
    statuses: Set[String],
    timeout: FiniteDuration = 60.seconds
  ): IO[Unit] =
    waitUntil(timeout) { rt.store.getNode(id).map(_.exists(n => statuses.contains(n.status))) }

  /** 「保持未启动」的负向断言窗口：给 fork 出的启动腿足够时间跑完（生产同款亚秒级）。 */
  private def settleWindow: IO[Unit] = IO.sleep(800.millis)

  /** merge 节点（merge=true，入口形态：无 in 无 deps ⇒ 就绪时刻 = createdAt）。 */
  private def mergeEntry(id: String, name: String, createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      merge = true,
      task = Some(s"landing task for $name"),
      status = NodeLifecycle.Pending,
      out = List(OutEdge.nebula),
      createdAt = createdAt
    )

  /** task 上游（completed；`completedAt` 决定下游 merge 的 readyAt = 到达时刻）。 */
  private def doneUpstream(id: String, name: String, out: List[OutEdge], completedAt: Long, createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name task"),
      status = NodeLifecycle.Completed,
      result = Some(s"$name artifact"),
      out = out,
      createdAt = createdAt,
      completedAt = Some(completedAt)
    )

  /** verifier 上游（role=verifier；verdict 走 lastVerdict 面）。 */
  private def verifierUp(id: String, name: String, verdict: Option[String], now: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name verify task"),
      status = NodeLifecycle.Completed,
      role = NodeRoles.Verifier,
      lastVerdict = verdict,
      result = Some(s"verdict report for $name"),
      out = Nil,
      createdAt = now - 400_000L,
      completedAt = Some(now - 200_000L)
    )

  private def mkGitRepo(dir: os.Path): Unit =
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)

  private def gitAvailable: Boolean =
    try os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
    catch case _: Throwable => false

  private def holdsFor(audit: List[(String, String, String)], id: String): List[String] =
    audit.filter((t, i, _) => t == FlowMapEventLog.MergeQueueType && i == id).map(_._3)

  // ── 键判据（SEM-5 / 设计件 §3.1）─────────────────────────────────

  test(
    "KEY (design §3.1): key = realpath(git-common-dir) — a worktree shares ONE key with its main repo, two repos differ, and a workspace with no enclosing repo falls back to its own canonical path"
  ) {
    val repo = tempRoot / "key-main"
    val wt = tempRoot / "key-wt"
    val other = tempRoot / "key-other"
    val plain = tempRoot / "key-plain"
    os.makeDir.all(plain)
    if !gitAvailable then IO.unit
    else
      for
        _ <- IO {
          mkGitRepo(repo)
          os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, "-b", "wt-branch")
            .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
          mkGitRepo(other)
        }
        outside <- IO(os.temp.dir(prefix = "nb-mm-key-"))
        kMain <- MergeMutexPolicy.keyOf(repo.toString)
        kWt <- MergeMutexPolicy.keyOf(wt.toString)
        kOther <- MergeMutexPolicy.keyOf(other.toString)
        kPlain <- MergeMutexPolicy.keyOf(plain.toString)
        kOuter <- MergeMutexPolicy.keyOf(os.pwd.toString)
        kOutside <- MergeMutexPolicy.keyOf(outside.toString)
      yield
        assertEquals(
          kWt,
          kMain,
          "a worktree's merge target is the main repo's git dir — the keys MUST be equal (design §3.1)"
        )
        assert(kOther != kMain, s"two distinct git dirs must NOT share a key: $kOther vs $kMain")
        // 非 repo 根下的工作区：`git rev-parse --git-common-dir` 命中**外层**仓库
        // ⇒ 键 = 外层仓库共同目录（这是设计件判据命令的字面语义，不是回落；本仓库
        // worktree 与主仓同键在此同样可读：kOuter = 主仓 .git）。
        assertEquals(
          kPlain,
          kOuter,
          s"a workspace nested inside a repo resolves to that ENCLOSING repo's git dir (literal command semantics)"
        )
        assert(kPlain != kMain, "the enclosing-repo key must not be confused with a nested standalone repo's key")
        // 真正无外层仓库的目录（系统临时目录）⇒ git 失败 ⇒ 键回落自身 canonical 路径
        assertEquals(
          kOutside,
          MergeMutexPolicy.canonical(outside.toString),
          "a workspace with NO enclosing repo falls back to its own canonical path (no false mutex, no false alarm)"
        )
        assert(
          kOutside != kMain && kOutside != kOther && kOutside != kPlain,
          "the fallback key must not collide with any repo key"
        )
    end if
  }

  // ── ① 互斥 + ② FIFO + ③ 释放（完成腿）────────────────────────────

  test(
    "Q1 mutex + FIFO + release(completed): with two same-key merge nodes ready at once EXACTLY ONE runs (the earlier arrival by rank); the other stays pending with a merge-queue hold record, and starts only after the holder's terminal write"
  ) {
    val ws = tempRoot / "ws-q1"; os.makeDir.all(ws)
    val system = ActorSystem(s"mm-q1-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      gate1 <- Deferred[IO, Unit]
      gates <- Ref.of[IO, List[Deferred[IO, Unit]]](List(gate1))
      llm = new GatedLlm(gates)
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mm-q1", ws, system, res)
      // 到达序（rank）: A(createdAt now-3000) 先于 B(now-2000)——两个都在同一 tick 就绪
      _ <- seed(rt, mergeEntry("n-a", "merge-a", now - 3000L), mergeEntry("n-b", "merge-b", now - 2000L))
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-a", Set(NodeLifecycle.Running))
      _ <- settleWindow
      a1 <- node(rt, "n-a")
      b1 <- node(rt, "n-b")
      audit1 <- readAudit(ws)
      // 幂等重放：后续回扫仍不得放行 B
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      b1b <- node(rt, "n-b")
      bHolds <- IO(holdsFor(audit1, "n-b"))
      aHolds <- IO(holdsFor(audit1, "n-a"))
      // 释放：持有者完成 ⇒ 下一个在有界内启动
      _ <- gate1.complete(()).void
      _ <- waitStatus(rt, "n-a", Set(NodeLifecycle.Completed))
      a2 <- node(rt, "n-a")
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-b", Set(NodeLifecycle.Completed))
      b2 <- node(rt, "n-b")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ① 互斥：恰一个进运行
      assertEquals(a1.status, NodeLifecycle.Running, "the earlier arrival (rank) must be the one that runs")
      assertEquals(b1.status, NodeLifecycle.Pending, "the later arrival must stay pending (mutex + FIFO)")
      assert(b1.startedAt.isEmpty, "no session may be spawned for the queued merge node")
      assertEquals(b1.result, None, "the gate must not fabricate any result")
      assertEquals(b1.blockedFeedback, None, "the gate must NOT fake a blocked state (legal wait)")
      assertEquals(b1b.status, NodeLifecycle.Pending, "repeated sweeps must keep holding the queued merge (idempotent)")
      // ② FIFO 停等留痕：B 被 A 挡住的 merge-queue 事件（单发）；胜者 A 不得有 hold 记录
      assertEquals(bHolds.size, 1, s"exactly one merge-queue hold record for the queued node, got $bHolds")
      assert(bHolds.head.contains("kind=hold"), s"hold summary must carry kind=hold: ${bHolds.head}")
      assert(bHolds.head.contains("holders=n-a"), s"hold summary must name the holder: ${bHolds.head}")
      assertEquals(aHolds, Nil, "the winner must not be recorded as held")
      // ③ 释放（完成腿）：下一个启动且启动时刻不早于持有者终态时刻
      assertEquals(
        b2.status,
        NodeLifecycle.Completed,
        "after the holder completed the next one must start (bounded release)"
      )
      assert(b2.startedAt.isDefined, "the released merge must have really run")
      assert(
        b2.startedAt.get >= a2.completedAt.getOrElse(Long.MaxValue),
        s"release must follow the holder's terminal write (startedAt=${b2.startedAt} vs holder.completedAt=${a2.completedAt})"
      )
    end for
  }

  test(
    "Q2 FIFO order: the queue is ranked by ARRIVAL (readyAt) — a node created earlier but arriving later must NOT jump the queue (fixture inverts createdAt vs readyAt, so a createdAt-based implementation goes red)"
  ) {
    val ws = tempRoot / "ws-q2"; os.makeDir.all(ws)
    val system = ActorSystem(s"mm-q2-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      g1 <- Deferred[IO, Unit]; g2 <- Deferred[IO, Unit]; g3 <- Deferred[IO, Unit]
      gates <- Ref.of[IO, List[Deferred[IO, Unit]]](List(g1, g2, g3))
      llm = new GatedLlm(gates)
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mm-q2", ws, system, res)
      // 到达序（readyAt = 最晚上游 completedAt）: m3(now-5000) < m2(now-3000) < m1(now-1000)
      // createdAt 反序: m1 最老、m3 最新 ⇒ 只有按 readyAt 排序才会得到 m3,m2,m1
      _ <- seed(
        rt,
        doneUpstream("u1", "up-1", List(OutEdge("n-m1")), now - 1000L, now - 900_000L),
        doneUpstream("u2", "up-2", List(OutEdge("n-m2")), now - 3000L, now - 900_000L),
        doneUpstream("u3", "up-3", List(OutEdge("n-m3")), now - 5000L, now - 900_000L),
        NodeDef(
          id = "n-m1",
          name = "merge-1",
          agent = "general",
          merge = true,
          task = Some("landing m1"),
          status = NodeLifecycle.Pending,
          in = List("u1"),
          out = List(OutEdge.nebula),
          createdAt = now - 90_000L
        ),
        NodeDef(
          id = "n-m2",
          name = "merge-2",
          agent = "general",
          merge = true,
          task = Some("landing m2"),
          status = NodeLifecycle.Pending,
          in = List("u2"),
          out = List(OutEdge.nebula),
          createdAt = now - 80_000L
        ),
        NodeDef(
          id = "n-m3",
          name = "merge-3",
          agent = "general",
          merge = true,
          task = Some("landing m3"),
          status = NodeLifecycle.Pending,
          in = List("u3"),
          out = List(OutEdge.nebula),
          createdAt = now - 70_000L
        )
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-m3", Set(NodeLifecycle.Running))
      _ <- settleWindow
      s1 <- node(rt, "n-m1"); s2 <- node(rt, "n-m2"); s3 <- node(rt, "n-m3")
      // 放行第一号 ⇒ 第二号（m2）在下一轮回扫起跑
      _ <- g1.complete(()).void
      _ <- waitStatus(rt, "n-m3", Set(NodeLifecycle.Completed))
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-m2", Set(NodeLifecycle.Running))
      _ <- settleWindow
      t1 <- node(rt, "n-m1"); t2 <- node(rt, "n-m2")
      // 放行第二号 ⇒ 第三号（m1，最后到达者）最后起跑
      _ <- g2.complete(()).void
      _ <- waitStatus(rt, "n-m2", Set(NodeLifecycle.Completed))
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-m1", Set(NodeLifecycle.Running))
      _ <- g3.complete(()).void
      _ <- waitStatus(rt, "n-m1", Set(NodeLifecycle.Completed))
      f1 <- node(rt, "n-m1")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 第一号 = 最早到达者（readyAt 决定，不因 createdAt 最年轻而让位）
      assertEquals(s3.status, NodeLifecycle.Running, "the earliest ARRIVAL must run first")
      assertEquals(s1.status, NodeLifecycle.Pending, "the node created earliest but arriving last must wait")
      assertEquals(s2.status, NodeLifecycle.Pending, "the second arrival must wait too")
      // 第二号紧随（第一号终态后）
      assertEquals(t2.status, NodeLifecycle.Running, "the second arrival runs after the first finished")
      assertEquals(t1.status, NodeLifecycle.Pending, "the last arrival still waits")
      // 次序读数：startedAt 严格递增，且与 createdAt 序相反（证明 rank 以 readyAt 为主键）
      val order = List(f1, t2, s3) // m1, m2, m3 by node identity
      assert(order.forall(_.startedAt.isDefined), s"all three must have run: ${order.map(n => n.id -> n.startedAt)}")
      assert(
        s3.startedAt.get < t2.startedAt.get && t2.startedAt.get < f1.startedAt.get,
        s"FIFO start order must be m3 < m2 < m1 by startedAt, got ${List(("n-m3", s3.startedAt), ("n-m2", t2.startedAt), ("n-m1", f1.startedAt))}"
      )
      assert(
        s3.createdAt > t2.createdAt && t2.createdAt > f1.createdAt,
        "fixture sanity: createdAt order is the INVERSE of the observed start order (so createdAt-based ordering would be red here)"
      )
    end for
  }

  // ── ③ 释放：取消腿 / 失败腿 ──────────────────────────────────────

  test(
    "Q3 release(cancelled): a merge node cancelled while holding the critical section releases it — the queued node starts right after (bounded)"
  ) {
    val ws = tempRoot / "ws-q3"; os.makeDir.all(ws)
    val system = ActorSystem(s"mm-q3-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      g1 <- Deferred[IO, Unit]; g2 <- Deferred[IO, Unit]
      gates <- Ref.of[IO, List[Deferred[IO, Unit]]](List(g1, g2))
      llm = new GatedLlm(gates)
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mm-q3", ws, system, res)
      _ <- seed(rt, mergeEntry("n-a", "merge-a", now - 3000L), mergeEntry("n-b", "merge-b", now - 2000L))
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-a", Set(NodeLifecycle.Running))
      _ <- settleWindow
      held <- node(rt, "n-b")
      // 取消持有者（真实入口：NodeCancel → cancelNodeById）
      _ <- rt.engine.cancelNodeById("n-a")
      _ <- waitStatus(rt, "n-a", Set(NodeLifecycle.Cancelled))
      a <- node(rt, "n-a")
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-b", Set(NodeLifecycle.Running))
      b <- node(rt, "n-b")
      _ <- g2.complete(()).void
      _ <- waitStatus(rt, "n-b", Set(NodeLifecycle.Completed))
      bf <- node(rt, "n-b")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(held.status, NodeLifecycle.Pending, "precondition: the later arrival was queued behind the holder")
      assertEquals(a.status, NodeLifecycle.Cancelled, "precondition: the holder was cancelled mid-run")
      assertEquals(
        b.status,
        NodeLifecycle.Running,
        "the cancel of the holder must release the queue (the next one really started)"
      )
      assertEquals(bf.status, NodeLifecycle.Completed, "the released merge ran to completion")
      assert(
        bf.startedAt.get >= a.completedAt.getOrElse(Long.MaxValue),
        s"release must follow the cancel terminal write (startedAt=${bf.startedAt} vs completedAt=${a.completedAt})"
      )
    end for
  }

  test(
    "Q3 release(failed): a merge node that converges to failed while holding the critical section releases it — the queued node starts right after (bounded)"
  ) {
    val ws = tempRoot / "ws-q3f"; os.makeDir.all(ws)
    val system = ActorSystem(s"mm-q3f-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      gates <- Ref.of[IO, List[Deferred[IO, Unit]]](Nil)
      llm = new GatedLlm(gates)
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mm-q3f", ws, system, res)
      // 持有者：种子 running（无活会话/无在飞 fiber）——判据面等价于「已进临界区」
      _ <- seed(
        rt,
        NodeDef(
          id = "n-a",
          name = "merge-a",
          agent = "general",
          merge = true,
          task = Some("landing a"),
          status = NodeLifecycle.Running,
          out = List(OutEdge.nebula),
          startedAt = Some(now - 3_600_000L),
          createdAt = now - 3000L
        ),
        mergeEntry("n-b", "merge-b", now - 2000L)
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      held <- node(rt, "n-b")
      // 失败终态写点（真实入口：死会话收敛 → failed）
      _ <- rt.engine.settleStaleRunningNodes()
      a <- node(rt, "n-a")
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-b", Set(NodeLifecycle.Completed))
      b <- node(rt, "n-b")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        held.status,
        NodeLifecycle.Pending,
        "precondition: the later arrival was queued behind the running holder"
      )
      assertEquals(a.status, NodeLifecycle.Failed, "precondition: the dead-session holder converged to failed")
      assertEquals(b.status, NodeLifecycle.Completed, "the failure of the holder must release the queue")
      assert(
        a.completedAt.isDefined,
        "the failed terminal write must carry completedAt (release fact is derived from status)"
      )
      assert(
        b.startedAt.get >= a.completedAt.getOrElse(Long.MaxValue),
        s"release must follow the failed terminal write (startedAt=${b.startedAt} vs completedAt=${a.completedAt})"
      )
    end for
  }

  // ── ④ 跨键并行（SEM-5：异键不互斥）───────────────────────────────

  test(
    "Q4 cross-key parallel: two projects on DIFFERENT git dirs run their merge nodes AT THE SAME TIME (no cross-key mutex, zero queue records)"
  ) {
    val repoA = tempRoot / "q4-repo-a"
    val repoB = tempRoot / "q4-repo-b"
    if !gitAvailable then IO.unit
    else
      val system = ActorSystem(s"mm-q4-${scala.util.Random.nextInt(100000)}")
      val now = System.currentTimeMillis()
      for
        _ <- IO { mkGitRepo(repoA); mkGitRepo(repoB) }
        g1 <- Deferred[IO, Unit]; g2 <- Deferred[IO, Unit]
        gates <- Ref.of[IO, List[Deferred[IO, Unit]]](List(g1, g2))
        llm = new GatedLlm(gates)
        res <- SpecResources.mkResources(system, tempRoot, llm.handle)
        rtA <- mountProject("mm-q4-a", repoA, system, res)
        rtB <- mountProject("mm-q4-b", repoB, system, res)
        kA <- MergeMutexPolicy.keyOf(repoA.toString)
        kB <- MergeMutexPolicy.keyOf(repoB.toString)
        _ <- seed(rtA, mergeEntry("a-merge", "merge-a", now - 3000L))
        _ <- seed(rtB, mergeEntry("b-merge", "merge-b", now - 1000L))
        _ <- rtA.engine.settleRunnableSweep()
        _ <- rtB.engine.settleRunnableSweep()
        _ <- waitStatus(rtA, "a-merge", Set(NodeLifecycle.Running))
        _ <- waitStatus(rtB, "b-merge", Set(NodeLifecycle.Running))
        x <- node(rtA, "a-merge")
        y <- node(rtB, "b-merge")
        auditA <- readAudit(repoA)
        auditB <- readAudit(repoB)
        _ <- g1.complete(()).void *> g2.complete(()).void
        _ <- waitStatus(rtA, "a-merge", Set(NodeLifecycle.Completed))
        _ <- waitStatus(rtB, "b-merge", Set(NodeLifecycle.Completed))
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assert(kA != kB, s"fixture sanity: the two projects must have different keys ($kA vs $kB)")
        assertEquals(x.status, NodeLifecycle.Running, "cross-key: project A's merge must run")
        assertEquals(
          y.status,
          NodeLifecycle.Running,
          "cross-key: project B's merge must run AT THE SAME TIME (SEM-5: different keys MUST be allowed to run in parallel)"
        )
        assertEquals(holdsFor(auditA, "a-merge"), Nil, "no queue record may be produced across different keys")
        assertEquals(holdsFor(auditB, "b-merge"), Nil, "no queue record may be produced across different keys")
      end for
    end if
  }

  // ── ⑤ O-1：同键多项目 ⇒ 发生即告警（已知缺口登记）─────────────────

  test(
    "Q5 O-1 alarm: two projects resolving to the SAME git dir (main repo + its worktree) raise the visible merge-queue alarm (kind=same-git-dir-multi-project) — and the KNOWN GAP is read out: no cross-project claim is implemented, so both merge nodes run"
  ) {
    val repo = tempRoot / "q5-repo"
    val wt = tempRoot / "q5-wt"
    if !gitAvailable then IO.unit
    else
      val system = ActorSystem(s"mm-q5-${scala.util.Random.nextInt(100000)}")
      val now = System.currentTimeMillis()
      for
        _ <- IO {
          mkGitRepo(repo)
          os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, "-b", "q5-branch")
            .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        }
        g <- Deferred[IO, Unit]
        gates <- Ref.of[IO, List[Deferred[IO, Unit]]](List(g))
        llm = new GatedLlm(gates)
        res <- SpecResources.mkResources(system, tempRoot, llm.handle)
        rtA <- mountProject("mm-o1-a", repo, system, res)
        rtB <- mountProject("mm-o1-b", wt, system, res)
        kA <- MergeMutexPolicy.keyOf(repo.toString)
        kB <- MergeMutexPolicy.keyOf(wt.toString)
        // A：持有者在临界区（种子 running）；B：同键新就绪的 merge 节点
        _ <- seed(
          rtA,
          NodeDef(
            id = "a-merge",
            name = "merge-a",
            agent = "general",
            merge = true,
            task = Some("landing a"),
            status = NodeLifecycle.Running,
            out = List(OutEdge.nebula),
            startedAt = Some(now - 60_000L),
            createdAt = now - 3000L
          )
        )
        _ <- seed(rtB, mergeEntry("b-merge", "merge-b", now - 1000L))
        _ <- rtB.engine.settleRunnableSweep()
        _ <- waitStatus(rtB, "b-merge", Set(NodeLifecycle.Running))
        alarm <- readAudit(wt)
        aRunning <- node(rtA, "a-merge")
        bRunning <- node(rtB, "b-merge")
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(kB, kA, "fixture sanity: the worktree and the main repo share one key (design §3.1)")
        val alarms = alarm.filter((t, _, s) =>
          t == FlowMapEventLog.MergeQueueType && s.contains("kind=same-git-dir-multi-project")
        )
        assertEquals(
          alarms.size,
          1,
          s"the same-git-dir multi-project situation MUST raise the visible alarm, got $alarms"
        )
        val (_, alarmNode, summary) = alarms.head
        assertEquals(
          alarmNode,
          "mm-o1-b",
          "the alarm's nodeId field carries the reporting project name (dispatcher-wake precedent)"
        )
        assert(summary.contains("foreign=mm-o1-a"), s"the alarm must name the foreign project: $summary")
        assert(summary.contains("key="), s"the alarm must carry the (normalized) key: $summary")
        assert(summary.contains("foreignRunning=1"), s"the alarm must report the foreign running merge count: $summary")
        assert(summary.contains("mineRunning="), s"the alarm must report this project's running merge count: $summary")
        // 已知缺口读数（作者令：不实现 claim/抢占 ⇒ 只告警）：B 的 merge 节点**照跑**
        assertEquals(
          aRunning.status,
          NodeLifecycle.Running,
          "precondition: project A's merge node is in the critical section"
        )
        assertEquals(
          bRunning.status,
          NodeLifecycle.Running,
          "KNOWN GAP (O-1): cross-project mutual exclusion is NOT enforced for two projects on one git dir — the alarm is the compensating control (claim/preemption deliberately out of scope this batch)"
        )
      end for
    end if
  }

  // ── ⑥ O-2：deps 参与 verdict 闸 ──────────────────────────────────

  test(
    "Q6 O-2 deps: a merge node whose VERIFIER is mounted via `deps` (not `in`) is held while lastVerdict != pass, and starts once the verdict is pass (G-1 one-liner, zero legacy impact)"
  ) {
    val ws = tempRoot / "ws-q6"; os.makeDir.all(ws)
    val system = ActorSystem(s"mm-q6-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      gate <- Deferred[IO, Unit]
      gates <- Ref.of[IO, List[Deferred[IO, Unit]]](List(gate))
      llm = new GatedLlm(gates)
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mm-q6", ws, system, res)
      _ <- seed(
        rt,
        verifierUp("n-ver", "verifier", Some("fail"), now),
        NodeDef(
          id = "n-merge",
          name = "merge-deps",
          agent = "general",
          merge = true,
          task = Some("landing via deps"),
          status = NodeLifecycle.Pending,
          deps = List("n-ver"),
          out = List(OutEdge.nebula),
          createdAt = now - 100_000L
        )
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      held <- node(rt, "n-merge")
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      held2 <- node(rt, "n-merge")
      // verifier 重跑出 pass（等价于其第二轮 node_report(pass)）——闸读当下值
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes.updated("n-ver", s.nodes("n-ver").copy(lastVerdict = Some("pass"))))
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Running))
      _ <- gate.complete(()).void
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Completed))
      m <- node(rt, "n-merge")
      v <- node(rt, "n-ver")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        held.status,
        NodeLifecycle.Pending,
        "O-2: a deps-mounted verifier that has not passed MUST hold the merge (before this batch `deps` was invisible to the gate)"
      )
      assert(held.startedAt.isEmpty, "no session may be spawned while the deps-mounted verdict is not pass")
      assertEquals(
        held2.status,
        NodeLifecycle.Pending,
        "the hold is idempotent across sweeps (current value, not a one-shot latch)"
      )
      assertEquals(v.lastVerdict, Some("pass"), "precondition: the verifier was re-run to pass")
      assertEquals(
        m.status,
        NodeLifecycle.Completed,
        "O-2 control: once the deps-mounted verdict is pass the merge starts (the deps leg is live, not inert)"
      )
    end for
  }

  // ── 非 merge 节点零行为变化（闸是 merge-only）─────────────────────

  test(
    "Q7 merge-only: two NON-merge nodes in the same project run in parallel — the mutex gate leaves every non-merge node untouched"
  ) {
    val ws = tempRoot / "ws-q7"; os.makeDir.all(ws)
    val system = ActorSystem(s"mm-q7-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      g1 <- Deferred[IO, Unit]; g2 <- Deferred[IO, Unit]
      gates <- Ref.of[IO, List[Deferred[IO, Unit]]](List(g1, g2))
      llm = new GatedLlm(gates)
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mm-q7", ws, system, res)
      _ <- seed(
        rt,
        NodeDef(
          id = "n-p1",
          name = "plain-1",
          agent = "general",
          task = Some("work 1"),
          status = NodeLifecycle.Pending,
          out = List(OutEdge.nebula),
          createdAt = now - 3000L
        ),
        NodeDef(
          id = "n-p2",
          name = "plain-2",
          agent = "general",
          task = Some("work 2"),
          status = NodeLifecycle.Pending,
          out = List(OutEdge.nebula),
          createdAt = now - 1000L
        )
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-p1", Set(NodeLifecycle.Running))
      _ <- waitStatus(rt, "n-p2", Set(NodeLifecycle.Running))
      p1 <- node(rt, "n-p1")
      p2 <- node(rt, "n-p2")
      audit <- readAudit(ws)
      _ <- g1.complete(()).void *> g2.complete(()).void
      _ <- waitStatus(rt, "n-p1", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "n-p2", Set(NodeLifecycle.Completed))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(p1.status, NodeLifecycle.Running, "non-merge node behaviour must be unchanged (no serialization)")
      assertEquals(p2.status, NodeLifecycle.Running, "both non-merge nodes must run simultaneously")
      val queueEvents = audit.filter((t, _, _) => t == FlowMapEventLog.MergeQueueType)
      assertEquals(
        queueEvents,
        Nil,
        s"the gate is merge-only — no merge-queue record may be produced for non-merge nodes, got $queueEvents"
      )
    end for
  }

end MergeMutexGateSpec
