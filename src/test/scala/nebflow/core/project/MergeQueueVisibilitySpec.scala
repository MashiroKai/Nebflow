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
import nebflow.core.tools.{FileLockManager, NodeTools}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * **排队位次可见性批**（2026-09-14，作者 16:39 双裁 = 显示「数字 + 持有者双显」，
 * 路线 = 案 A「引擎条件键 + 前端渲染」）引擎面回归族。
 *
 * 判据真源 = 引擎活态（[[MergeMutexPolicy.holders]] + verdict 准入过滤单点
 * [[NodeEngine.mergeQueueHolders]]）——🔴 **禁从事件流回放派生**（事件流是审计面）、
 * 🔴 **禁读文件票层**（`.nebflow/locks/main-merge.queue` 是过渡期并存的旧层）、
 * 🔴 **禁前端/分发器复刻**。本族只验证「引擎把同一判据派生成载荷条件键」。
 *
 * 用例面（任务书验收①-⑤逐条，缺一不算完成）：
 *  - ① 排队态**有键**：被挡的 merge 节点载荷带 `mergeQueue`，`ahead` = 持有者数；
 *  - ② 运行态**无键**：临界区持有者自身的载荷**不带**该键（同一份载荷同一测试内对读）；
 *  - ③ **多持有者（全列）**：2 个前方持有者 ⇒ `ahead=2` 且 `holders` 两项全在场；
 *  - ④ **同键多项目**（O-1）：他项目与本项目同键 ⇒ 键带 `sameKeyProjects`（= 降级信号）；
 *  - ⑤ **释放后清零**：持有者终态化 ⇒ 原被挡节点的键**消失**（不是残留旧值）。
 *
 * 另有零漂移（非 merge / 未排队的 merge / 自身 / 持久面）与计数边界（不含自己、
 * 含持有者、未到达者不计、verdict 准入过滤）两组断言。
 *
 * 变异臂（自证）：把 `mergeQueueHoldersBatch` 换成「只数 running 者」⇒ ③ 必红
 * （开态先到者丢失）；去掉 verdict 准入过滤 ⇒ 边界组必红。
 */
class MergeQueueVisibilitySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-queue-pos"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"merge queue position spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** 本族**不启动任何节点**（判据是纯状态派生）：LLM 腿只作装配占位，被调用即失败。 */
  private val deadLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("no node may be started by this spec"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(IO.raiseError(new RuntimeException("no node may be started by this spec")))

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = deadLlm,
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

  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  /** 载荷读数（**真入口** = NodeList / REST flow-map 的共用序列化面）。 */
  private def payload(rt: ProjectRuntime): IO[Json] = NodeTools.buildNodeListPayload(rt)

  private def nodeJson(p: Json, id: String): Json =
    p.hcursor
      .downField("nodes")
      .as[List[Json]]
      .getOrElse(Nil)
      .find(_.hcursor.get[String]("id").contains(id))
      .getOrElse(fail(s"node '$id' missing from payload"))

  private def mqField(p: Json, id: String): Option[Json] =
    nodeJson(p, id).hcursor.downField("mergeQueue").focus

  private def aheadOf(p: Json, id: String): Option[Int] =
    mqField(p, id).flatMap(_.hcursor.get[Int]("ahead").toOption)

  private def holderField(p: Json, id: String, field: String): List[String] =
    mqField(p, id)
      .flatMap(_.hcursor.downField("holders").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String](field).toOption)

  private def sameKeyProjects(p: Json, id: String): Option[List[String]] =
    mqField(p, id).flatMap(_.hcursor.downField("sameKeyProjects").as[List[String]].toOption)

  // ── engine-defects 批 #2/#227 显示槽读数（只增键，既有键逐字保留）──────────

  private def inSectionOf(p: Json, id: String): Option[List[String]] =
    mqField(p, id).flatMap(_.hcursor.downField("inSection").as[List[String]].toOption)

  private def rankKey(p: Json, id: String): Option[(String, List[String])] =
    mqField(p, id).flatMap(_.hcursor.downField("rank").focus).map { r =>
      (
        r.hcursor.get[String]("primary").getOrElse("<missing>"),
        r.hcursor.downField("tiebreaks").as[List[String]].getOrElse(Nil)
      )
    }

  private def holderJsons(p: Json, id: String): List[Json] =
    mqField(p, id).flatMap(_.hcursor.downField("holders").as[List[Json]].toOption).getOrElse(Nil)

  private def holderLong(p: Json, id: String, field: String): List[Long] =
    holderJsons(p, id).flatMap(_.hcursor.get[Long](field).toOption)

  /** 指定**持有者**（在被挡节点 id 的 holders[] 中按 id 定位）的单字段读数。 */
  private def holderFieldOf(p: Json, subject: String, holderId: String, field: String): Option[Long] =
    holderJsons(p, subject)
      .find(_.hcursor.get[String]("id").contains(holderId))
      .flatMap(_.hcursor.get[Long](field).toOption)

  private def holderFlag(p: Json, id: String, field: String): List[Boolean] =
    holderJsons(p, id).flatMap(_.hcursor.get[Boolean](field).toOption)

  private def holderReasons(p: Json, id: String): List[(String, String)] =
    holderJsons(p, id).flatMap { h =>
      for
        nid <- h.hcursor.get[String]("id").toOption
        why <- h.hcursor.get[String]("notStartedReason").toOption
      yield (nid, why)
    }

  private def keyFields(p: Json, id: String): Set[String] =
    mqField(p, id).flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  /** merge 节点（入口形态：无 in 无 deps ⇒ 到达时刻 readyAt = createdAt）。 */
  private def mergeNode(id: String, name: String, status: String, createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      merge = true,
      task = Some(s"landing $name"),
      status = status,
      out = List(OutEdge.nebula),
      createdAt = createdAt
    )

  /** 持有者（临界区内运行；`running` 恒优先，与 rank 无关）。 */
  private def runningHolder(id: String, name: String, createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      merge = true,
      task = Some(s"landing $name"),
      status = NodeLifecycle.Running,
      out = List(OutEdge.nebula),
      startedAt = Some(createdAt + 10L),
      createdAt = createdAt
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

  // ── ① + ② 排队态有键 / 运行态无键（同一份载荷对读）──────────────────

  test(
    "①+② queued carries the key, the holder does NOT: the snapshot payload gives the waiting merge node mergeQueue{ahead,holders} while the running holder in the same payload has no such key at all"
  ) {
    val ws = tempRoot / "ws-12"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-12-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-12", ws, system, res)
      _ <- seed(
        rt,
        runningHolder("n-holder", "attach-merge", now - 8000L),
        mergeNode("n-wait", "docs-merge", NodeLifecycle.Pending, now - 3000L)
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ① 排队态有键（数字 = 持有者数，含持有者；身份/状态全带）
      assertEquals(aheadOf(p, "n-wait"), Some(1), s"the queued merge node MUST carry mergeQueue.ahead: $p")
      assertEquals(holderField(p, "n-wait", "id"), List("n-holder"), "the key must name the holder id")
      assertEquals(
        holderField(p, "n-wait", "name"),
        List("attach-merge"),
        "the key must carry the holder NAME (footnote source)"
      )
      assertEquals(
        holderField(p, "n-wait", "status"),
        List(NodeLifecycle.Running),
        "the key must carry the holder status"
      )
      // ② 运行态无键：临界区持有者自己**不**带该键（同一份载荷内对读，非同源空白断言）
      assertEquals(
        mqField(p, "n-holder"),
        None,
        s"the running critical-section holder must NOT carry mergeQueue: ${nodeJson(p, "n-holder")}"
      )
    end for
  }

  // ── ③ 多持有者（全列）──────────────────────────────────────────────

  test(
    "③ multiple holders: a running holder plus an earlier-arrival open-state predecessor yield ahead=2 with BOTH listed (the earlier arrival counts — FIFO rank, not just running)"
  ) {
    val ws = tempRoot / "ws-3"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-3-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-3", ws, system, res)
      _ <- seed(
        rt,
        runningHolder("n-holder", "attach-merge", now - 9000L),
        mergeNode("n-early", "homebrew-generalize-merge", NodeLifecycle.Wiring, now - 8000L),
        mergeNode("n-subject", "docs-merge", NodeLifecycle.Pending, now - 3000L)
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(aheadOf(p, "n-subject"), Some(2), s"two blocking predecessors ⇒ ahead=2: $p")
      assertEquals(
        holderField(p, "n-subject", "id").sorted,
        List("n-early", "n-holder"),
        "both holders must be listed in full (no truncation, no first-only)"
      )
      assertEquals(
        holderField(p, "n-subject", "name").sorted,
        List("attach-merge", "homebrew-generalize-merge"),
        "holder NAMES are the footnote source — every holder must be named"
      )
      // 早到者不被吞并：它自己也被 running 持有者挡着（同一判据，非特判）
      assertEquals(aheadOf(p, "n-early"), Some(1), "the earlier-arrival open node is itself held by the running holder")
      assertEquals(mqField(p, "n-holder"), None, "the running holder is never 'queued'")
    end for
  }

  // ── ⑤ 释放后清零 ──────────────────────────────────────────────────

  test(
    "⑤ cleared on release: once the holder reaches a terminal state the queued node's key DISAPPEARS from the payload (no stale value, no cached rank)"
  ) {
    val ws = tempRoot / "ws-5"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-5-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-5", ws, system, res)
      _ <- seed(
        rt,
        runningHolder("n-holder", "attach-merge", now - 8000L),
        mergeNode("n-wait", "docs-merge", NodeLifecycle.Pending, now - 3000L)
      )
      before <- payload(rt)
      // 持有者终态化（完成）——闸的释放判据 = 状态派生（无锁文件、无 TTL、无孤儿）
      _ <- rt.store.mutate(s =>
        s.copy(nodes =
          s.nodes
            .updated("n-holder", s.nodes("n-holder").copy(status = NodeLifecycle.Completed, completedAt = Some(now)))
        )
      )
      after <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(aheadOf(before, "n-wait"), Some(1), "precondition: the node was queued before the release")
      assertEquals(
        mqField(after, "n-wait"),
        None,
        s"after the holder's terminal write the key MUST be gone (not a stale copy): ${nodeJson(after, "n-wait")}"
      )
      assertEquals(aheadOf(after, "n-holder"), None, "the released (completed) node is never queued")
    end for
  }

  // ── engine-defects 批 #2/#227（2026-09-15）：位次**显示槽**─────────────────
  //
  // 动因（真身 `flow-map-events.jsonl:5581`）：旧载荷只给 `{id,name,status}`，消费方
  // 只能自行把 `running` 读成「在临界区」、把 `wiring|pending` 读成「在排队」；而引擎
  // 自己的停等文案对**开态**持有者也写「hold the critical section … (mechanism
  // guarantee)」——作者 00:30 亲历「为什么现在没有节点在跑」无从判断。
  // 本族验证：同一判据（[[NodeEngine.mergeQueueHolders]] → [[MergeMutexPolicy.holders]]）
  // 被派生成**显式三键**：`rank{primary,tiebreaks}` / `inSection[]` / `holders[].notStartedReason`。
  // 🔴 零行为面：不改闸、不改 FIFO、不写持久字段；既有键逐字保留（下方零漂移用例钉）。

  test(
    "#2/#227 slot: the key separates the critical section from mere queue order and states, per holder, why it has not started (queued / awaiting-handover / barrier-incomplete / in-critical-section)"
  ) {
    val ws = tempRoot / "ws-slot"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-slot-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-slot", ws, system, res)
      _ <- seed(
        rt,
        runningHolder("n-holder", "attach-merge", now - 9000L),
        mergeNode("n-early", "docs-merge", NodeLifecycle.Wiring, now - 8000L),
        // barrier 残缺：in 仍有未投递上游
        mergeNode("n-barrier", "blocked-merge", NodeLifecycle.Wiring, now - 7000L).copy(in = List("n-x")),
        // R4 待承接：摘除的 cancelled 上游留下的槽位 ⇒ 不会自行启动（#85 的形态）
        mergeNode("n-handover", "handover-merge", NodeLifecycle.Wiring, now - 5000L)
          .copy(pendingSuccession = List("n-x-cancelled")),
        mergeNode("n-subject", "waiting-merge", NodeLifecycle.Pending, now - 3000L)
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(aheadOf(p, "n-subject"), Some(4), s"four blocking predecessors: $p")
      // ① 临界区与「只是排在前面」分栏
      assertEquals(
        inSectionOf(p, "n-subject"),
        Some(List("n-holder")),
        s"inSection must name exactly the holders that are REALLY inside the critical section: $p"
      )
      assertEquals(
        holderFlag(p, "n-subject", "inSection").count(identity),
        1,
        "exactly one holder may be flagged inSection in this fixture"
      )
      // ② rank 依据可只读读出（次序键元数据；前端只读不派生）
      assertEquals(
        rankKey(p, "n-subject"),
        Some(("readyAt", List("createdAt", "id"))),
        "the key must publish the rank basis explicitly (readyAt, then createdAt, then id)"
      )
      // ③ 逐持有者「为何未点火」四态
      assertEquals(
        holderReasons(p, "n-subject").toMap,
        Map(
          "n-holder" -> "in-critical-section",
          "n-barrier" -> "barrier-incomplete",
          "n-early" -> "queued",
          "n-handover" -> "awaiting-handover"
        ),
        s"every holder must state why it has not started (four-state judgement): $p"
      )
      // ④ rank 数值与节点自身字段同源（无第二判据：入口形态 readyAt = createdAt）
      assertEquals(
        holderFieldOf(p, "n-subject", "n-early", "readyAt"),
        Some(now - 8000L),
        "readyAt must be the rank primary key itself (entry node => createdAt)"
      )
      assertEquals(holderFieldOf(p, "n-subject", "n-early", "createdAt"), Some(now - 8000L))
      assertEquals(
        holderFieldOf(p, "n-subject", "n-handover", "readyAt"),
        Some(now - 5000L),
        "the R4 pendingSuccession holder's readyAt is its arrival time (the removed upstream is NOT a rank input)"
      )
    end for
  }

  test(
    "#2/#227 zero drift: the queued key's field set is EXACTLY {ahead,inSection,rank,holders} and every pre-existing holder field keeps its literal value"
  ) {
    val ws = tempRoot / "ws-slot-zero"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-slotz-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-slotzero", ws, system, res)
      _ <- seed(
        rt,
        runningHolder("n-holder", "attach-merge", now - 8000L),
        mergeNode("n-wait", "docs-merge", NodeLifecycle.Pending, now - 3000L)
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 显示槽是**只增键**：本批新增/保留的键恰为四件。`sameKeyProjects` 是既有条件键
      // （O-1 降级信号，其在场取决于同一 JVM 内**其它已挂载项目**是否解析到同一 git 目录
      // ——spec 工作区落在本仓工作树内，`rev-parse` 会上溯到本仓 .git ⇒ 与同 JVM 内其它
      // spec 挂载共享键），故此处把它排除、单独按其自身判据断言（不引入非确定性）。
      assertEquals(
        keyFields(p, "n-wait") - "sameKeyProjects",
        Set("ahead", "inSection", "rank", "holders"),
        s"the queue key must carry exactly the declared fields: ${mqField(p, "n-wait")}"
      )
      assert(
        keyFields(p, "n-wait").subsetOf(Set("ahead", "inSection", "rank", "holders", "sameKeyProjects")),
        s"no undeclared key may appear on the queue slot: ${mqField(p, "n-wait")}"
      )
      // 既有三键逐字保留 + 本批新增四键（holders[] 项字段集精确相等，无多余键）
      assertEquals(
        holderJsons(p, "n-wait").map(_.asObject.map(_.keys.toSet).getOrElse(Set.empty[String])).toList,
        List[Set[String]](Set("id", "name", "status", "readyAt", "createdAt", "inSection", "notStartedReason")),
        s"holder entries must be the pre-existing three keys plus the four declared ones: ${holderJsons(p, "n-wait")}"
      )
      assertEquals(
        holderField(p, "n-wait", "status"),
        List(NodeLifecycle.Running),
        "pre-existing holder.status must keep its literal value"
      )
      // 非排队节点仍无键（条件键纪律未破）
      assertEquals(mqField(p, "n-holder"), None, "the running holder still carries no key")
    end for
  }

  // ── ④ 同键多项目（O-1）= 降级信号 ─────────────────────────────────

  test(
    "④ same-key multi-project (O-1): when another registered project resolves to the SAME git dir the key carries sameKeyProjects (the frontend degradation signal — the number must not be trusted)"
  ) {
    val repo = tempRoot / "q4-repo"
    val wt = tempRoot / "q4-wt"
    if !gitAvailable then IO.unit
    else
      val system = ActorSystem(s"mqpos-4-${scala.util.Random.nextInt(100000)}")
      val now = System.currentTimeMillis()
      for
        _ <- IO {
          mkGitRepo(repo)
          os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, "-b", "q4-branch")
            .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        }
        res <- mkResources(system, tempRoot)
        rtA <- mountProject("mqpos-o1-a", repo, system, res)
        rtB <- mountProject("mqpos-o1-b", wt, system, res)
        kA <- MergeMutexPolicy.keyOf(repo.toString)
        kB <- MergeMutexPolicy.keyOf(wt.toString)
        _ <- seed(rtA, runningHolder("a-holder", "merge-a", now - 8000L))
        _ <- seed(
          rtB,
          runningHolder("b-holder", "merge-b", now - 8000L),
          mergeNode("b-wait", "merge-b-wait", NodeLifecycle.Pending, now - 3000L)
        )
        pA <- payload(rtA)
        pB <- payload(rtB)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(kB, kA, "fixture sanity: the worktree and the main repo share one git dir key")
        assertEquals(aheadOf(pB, "b-wait"), Some(1), "the in-project derivation is unchanged (one holder here)")
        assertEquals(
          sameKeyProjects(pB, "b-wait"),
          Some(List("mqpos-o1-a")),
          "the O-1 situation MUST be visible on the key as sameKeyProjects (frontend degrades: no number rendered)"
        )
        // 非同键项目的载荷不带该信号（缺键 = 位次可信；与 merge/deps 同构条件键）
        assertEquals(mqField(pA, "a-holder"), None, "no queue key for a running holder (zero drift)")
      end for
    end if
  }

  // ── 零漂移：非 merge / 未排队 / 持久面 ─────────────────────────────

  test(
    "zero drift: non-merge nodes and an unheld merge node carry NO mergeQueue key, and the key is derived only (never persisted on NodeDef)"
  ) {
    val ws = tempRoot / "ws-zero"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-zero-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-zero", ws, system, res)
      _ <- seed(
        rt,
        NodeDef(
          id = "n-plain",
          name = "plain",
          agent = "coder",
          task = Some("work"),
          status = NodeLifecycle.Pending,
          out = List(OutEdge.nebula),
          createdAt = now - 3000L
        ),
        NodeDef(
          id = "n-plain-run",
          name = "plain-run",
          agent = "coder",
          task = Some("work"),
          status = NodeLifecycle.Running,
          out = List(OutEdge.nebula),
          createdAt = now - 2000L
        ),
        mergeNode("n-alone", "solo-merge", NodeLifecycle.Pending, now - 1000L)
      )
      p <- payload(rt)
      alone <- rt.store.getNode("n-alone")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      for id <- List("n-plain", "n-plain-run", "n-alone") do
        assertEquals(mqField(p, id), None, s"no key may appear for '$id' (no merge-window hold): ${nodeJson(p, id)}")
      // 纯派生量：NodeDef 持久面**无**该键（不进 flow-map.json / 归档批）
      val persisted = alone.getOrElse(fail("n-alone must exist")).asJson
      assertEquals(
        persisted.hcursor.downField("mergeQueue").focus,
        None,
        s"mergeQueue must never be persisted on NodeDef: $persisted"
      )
    end for
  }

  // ── 计数边界（纯判据组；「位次定义」逐条可测）──────────────────────

  test(
    "boundary of N: excludes SELF, INCLUDES the running holder, excludes not-yet-arrived nodes, excludes non-merge nodes, and includes every strictly-earlier arrival by rank=(readyAt,createdAt,id)"
  ) {
    val now = System.currentTimeMillis()
    val holder = NodeDef(
      id = "h",
      name = "holder",
      agent = "general",
      merge = true,
      task = Some("t"),
      status = NodeLifecycle.Running,
      out = Nil,
      startedAt = Some(now - 60_000L),
      createdAt = now - 90_000L
    )
    val earlyOpen = NodeDef(
      id = "e",
      name = "early",
      agent = "general",
      merge = true,
      task = Some("t"),
      status = NodeLifecycle.Wiring,
      out = Nil,
      createdAt = now - 80_000L
    )
    // 后到者（开态且 rank 严格更大 ⇒ 不计入）
    val lateOpen = NodeDef(
      id = "l",
      name = "late",
      agent = "general",
      merge = true,
      task = Some("t"),
      status = NodeLifecycle.Pending,
      out = Nil,
      createdAt = now - 1_000L
    )
    val plainEarly = NodeDef(
      id = "p",
      name = "plain",
      agent = "coder",
      task = Some("t"),
      status = NodeLifecycle.Pending,
      out = Nil,
      createdAt = now - 85_000L
    )
    // 未到达：上游仍 running ⇒ readyAt = MaxValue ⇒ 天然让位（不阻断）
    val upRunning = NodeDef(
      id = "u",
      name = "up",
      agent = "coder",
      task = Some("t"),
      status = NodeLifecycle.Running,
      out = List(OutEdge("x")),
      createdAt = now - 100_000L
    )
    val notArrived = NodeDef(
      id = "na",
      name = "not-arrived",
      agent = "general",
      merge = true,
      task = Some("t"),
      status = NodeLifecycle.Pending,
      in = List("u"),
      out = Nil,
      createdAt = now - 95_000L
    )
    val subject = NodeDef(
      id = "s",
      name = "subject",
      agent = "general",
      merge = true,
      task = Some("t"),
      status = NodeLifecycle.Pending,
      out = Nil,
      createdAt = now - 5_000L
    )
    val all = List(holder, earlyOpen, lateOpen, plainEarly, upRunning, notArrived, subject)
      .map(n => n.id -> n)
      .toMap
    val hs = MergeMutexPolicy.holders(subject, all).map(_.id).sorted
    assertEquals(
      hs,
      List("e", "h"),
      "N counts: running holder (always) + strictly-earlier ARRIVAL (rank) — excludes self, later arrivals, non-merge nodes and not-yet-arrived nodes"
    )
    assertEquals(MergeMutexPolicy.holders(subject, Map(subject.id -> subject)), Nil, "a merge node never holds itself")
    assertEquals(MergeMutexPolicy.holders(plainEarly, all), Nil, "the hold set is empty for non-merge nodes")
  }

  test(
    "boundary via the engine single point: the verdict-admission filter removes a predecessor that is itself held by the verdict gate (no dead queue head)"
  ) {
    val ws = tempRoot / "ws-verdict"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-ver-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-ver", ws, system, res)
      verifier = NodeDef(
        id = "n-ver",
        name = "verifier",
        agent = "general",
        task = Some("verify"),
        status = NodeLifecycle.Completed,
        role = NodeRoles.Verifier,
        lastVerdict = Some(rt.engine.VerdictFail),
        result = Some("fail verdict"),
        out = Nil,
        createdAt = now - 200_000L,
        completedAt = Some(now - 100_000L)
      )
      // 早到者自身被 verdict 闸挡着（in 挂未 pass 的 verifier）⇒ 不算持有者
      blockedEarly = NodeDef(
        id = "n-early",
        name = "early-merge",
        agent = "general",
        merge = true,
        task = Some("landing"),
        status = NodeLifecycle.Pending,
        in = List("n-ver"),
        out = List(OutEdge.nebula),
        createdAt = now - 80_000L
      )
      _ <- seed(
        rt,
        verifier,
        runningHolder("n-holder", "attach-merge", now - 90_000L),
        blockedEarly,
        mergeNode("n-subject", "docs-merge", NodeLifecycle.Pending, now - 3000L)
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        holderField(p, "n-subject", "id"),
        List("n-holder"),
        s"a verdict-blocked predecessor must NOT be counted as a holder (else the queue head deadlocks): $p"
      )
      assertEquals(aheadOf(p, "n-subject"), Some(1), "N reflects the admission-filtered set")
    end for
  }

end MergeQueueVisibilitySpec
