package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{AgentControlTool, FileLockManager}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * cancelsem 批 1（**R1** 面板取消 ⇒ agent 可见通知 · **R4** 区分 `source=user`/`engine`
 * 且 user-cancel 不得被重新武装）机械用例。
 *
 * 验收面（逐条对应任务书判据）：
 *  - C1（红→绿）：**面板入口**（WS `case "cancelAgent"` 的引擎侧腿 =
 *    `AgentControlTool.doCancel(rec from registry, reason, notifyWs)`）取消一个运行中节点 ⇒
 *    经**同一** dispatch-notify 通道回流，文本含 `source=user` + 节点 id + 链 id；
 *    且 user 变体**不含**「承接（首选）」重派发指引。
 *  - C2（去重）：同一次取消**恰一条**通知；同一节点再次进入取消链不新增（既有
 *    `notifySentAt` 持久去重面）。
 *  - C3（R4 钉）：下游的失败自动重试**不得**重新武装一个 **user-cancel** 的上游
 *    （节点保持 cancelled + result 不清 + 下游 gen 不变 + 审计留痕）；**engine-cancel**
 *    上游保持既有语义（照常重激活）＝对照读数。
 *  - C5（旧代码必 fail）：本文件全部新判据在支起点 main 上必红（读数见批报告）。
 *
 * ⚠ 断面声明：WS handler 的 JSON 胶水（解析 / kind 白名单 / `cancelAgentResult` 回帧）
 * 不在此文件——本文件覆盖它**调用链的引擎侧**（同一 `doCancel` 入口 + 真实 NodeEngine
 * 桥 + 真实终态写点 + 真实回流接缝）。浏览器帧腿只做打印不做断言（非本批语义面）。
 */
class CancelSemanticsSourceSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  // ── LLM 桩 ────────────────────────────────────────────────────────────

  /** 挂死桩：turn 永不完成（节点保持 running——面板取消的现场）。 */
  private def hangingLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      Stream.eval(IO.never)

  /** 脚本桩：输入文本含 `failMarker` ⇒ 该会话失败（节点 failed）；其余一律挂死。 */
  private def scriptedLlm(failMarker: String): LlmHandle[IO] = new LlmHandle[IO]:
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      val text = req.messages.map(_.textContent).mkString("\n")
      if text.contains(failMarker) then Stream.raiseError[IO](new RuntimeException(s"spec: $failMarker"))
      else Stream.eval(IO.never)

  // ── fixture ───────────────────────────────────────────────────────────

  private def withFixture(
    name: String,
    llm: LlmHandle[IO],
    notifySeam: Option[Ref[IO, List[String]]] = None
  )(
    body: (FlowMapStore, NodeEngine, SharedResources, ActorSystem, Ref[IO, List[AgentCommand]], String, os.Path) => Unit
  ): Unit =
    val tmp = os.temp.dir(prefix = s"cancelsem-$name")
    PathUtil.setDataRoot(tmp / "data")
    os.makeDir.all(tmp / "data" / "agents" / "test-agent")
    os.write.over(
      tmp / "data" / "agents" / "test-agent" / "agent.json",
      """{"name":"test-agent","description":"cancelsem spec agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tmp / "data" / "agents" / "test-agent" / "system.md", "# test-agent\n")
    val system = ActorSystem(s"cancelsem-$name")
    try
      val ws = tmp / "ws"
      val io = for
        _ <- IO(os.makeDir.all(ws))
        store <- FlowMapStore.open(s"csproj-$name", ws.toString)
        dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        resources = SharedResources(
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
          historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
          fileLockManager = fileLocks,
          sessionModelOverrides = modelOverrides,
          providerRegistry = null,
          healthMonitor = ProviderHealthMonitor(null),
          actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
          voiceMutedRef = voiceMuted
        )
        recorded <- Ref.of[IO, List[AgentCommand]](Nil)
        rootSid = "cs-root-sid"
        rootRef <- system.spawn(recorderBehavior(recorded), s"cs-root-${name.take(8)}")
        engine = new NodeEngine(
          store,
          system,
          resources,
          _ => IO.unit,
          ws.toString,
          rootSid,
          s"csproj-$name",
          FeedbackRouter.ModeAuto,
          (_, _, _) => IO.unit,
          notifyTriggerOverride = notifySeam.map(ref => (text: String) => ref.update(_ :+ text))
        )
      yield (store, engine, resources, recorded, rootSid, rootRef)
      val (store, engine, resources, recorded, rootSid, rootRef) = io.unsafeRunSync()
      resources.agentRegistry
        .update(_ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid)))
        .unsafeRunSync()
      body(store, engine, resources, system, recorded, rootSid, ws)
      awaitTerminalTailDrained(store).unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      removeTempBounded(tmp)

    end try

  end withFixture

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    lazy val b: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  /** 尾链收敛等待（teardown 竞态治本，锚可观测状态）：全部终态节点 `notifySentAt` 已落库。 */
  private def awaitTerminalTailDrained(store: FlowMapStore): IO[Unit] =
    val startedAt = System.currentTimeMillis()
    def converged: IO[Boolean] =
      store.snapshot.map(
        _.nodes.values.filter(n => NodeLifecycle.Terminal.contains(n.status)).forall(_.notifySentAt.isDefined)
      )
    def go(deadline: Long): IO[Unit] =
      converged.flatMap {
        case true => IO.unit
        case false if System.currentTimeMillis() >= deadline =>
          IO.println(
            s"[spec] awaitTerminalTailDrained: 10s 未收敛（+${System.currentTimeMillis() - startedAt}ms）——" +
              "交由 removeTempBounded 兜底（teardown 卫生，非断言）"
          )
        case false => IO.sleep(50.millis) >> go(deadline)
      }
    go(startedAt + 10_000L)

  end awaitTerminalTailDrained

  private def removeTempBounded(tmp: os.Path, attempts: Int = 3): Unit =
    def go(n: Int): Unit =
      try os.remove.all(tmp)
      catch
        case e: java.nio.file.DirectoryNotEmptyException if n > 1 =>
          println(s"[spec] remove-all retry ($n left) after DirectoryNotEmptyException: ${e.getMessage}")
          Thread.sleep(200L)
          go(n - 1)
    go(attempts)

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] = cond.flatMap {
      case true => IO.unit
      case _ if System.currentTimeMillis() >= deadline =>
        IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
      case false => IO.sleep(every) >> go(deadline)
    }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** 面板视角的会话记录（WS handler 的 `registry.get(sessionId)` 同源）。 */
  private def nodeSession(resources: SharedResources): IO[Option[AgentRecord]] =
    resources.agentRegistry.get.map(
      _.values.find(r => r.kind == AgentKind.Flow && r.sessionId.startsWith(NodeEngine.SessionPrefix))
    )

  private def seed(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void

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

  /** user-cancel 变体与 engine 变体的**可判读**标记（本批 R1/R4 文本契约）。 */
  private val UserCancelMarker = "用户主动取消"
  private val NoReDispatchMarker = "不得重新派发"
  private val EngineFirstChoice = "承接（首选）"

  // ── 源码判据的**代码行视图**（M8/M11 同款形态：剥掉注释行后再判）──────────
  //
  // 为什么必须剥（chaincancel 批 V7 实测）：文档注释里**逐字引用**了被判据约束的代码
  // 原文（本批新增的 #675(a) 头注同样引用了 `OutEdge.isLoopEdge`）——不剥的话，把代码
  // 改掉、留下注释，`contains` 断言照样绿 = 判据被注释**背书**而假绿。反向同理：注释里
  // 出现 `cascade` 会让「保留面不得含 cascade」的负断言误红。
  private def codeOnly(src: String): String =
    src.linesIterator
      .filterNot { l =>
        val t = l.trim
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
      }
      .mkString("\n")

  private def engineSrcWindow(sig: String, n: Int): String =
    // 2026-09-25 G 步重钉:detachCancelledUpstream / referencesOf 随终态化簇自 NodeEngine
    // 迁至 NodeCompletion(self-type trait,行为保持重构)——源读数扩为跨文件聚合(先例
    // SubAgentInboxMirrorSpec 2.3 增补),锚文本不变、窗口语义不变。
    val src =
      codeOnly(os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "project" / "NodeEngine.scala")) +
        codeOnly(os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "project" / "NodeCompletion.scala"))
    val lines = src.linesIterator.toList
    val start = lines.indexWhere(_.contains(sig))
    assert(start >= 0, s"anchor not found in NodeEngine.scala(+NodeCompletion.scala): $sig")
    lines.slice(start, math.min(start + n, lines.size)).mkString("\n")

  // ── C6（#675(a) / #697 机械钉点）：打标面排除 `:loop` 回边目标 ────────────

  test(
    "C6 R4/#675(a) source window (comments stripped): the cancel-family detach leg's TARGET SET excludes ':loop' back-edge targets — and the conduction exclusion stays pinned at referencesOf (two faces, distinct sites)"
  ) {
    val marking = engineSrcWindow("private def detachCancelledUpstream", 60)
    println(s"[spec] C6 marking-face window (code-only) head:\n${marking.linesIterator.take(25).mkString("\n")}")
    // ① 打标面（本批改动点）：前向扫描跳过回边 —— 逐字形态判据（撤掉本过滤 ⇒ 本行必红）
    // 2026-09-24:钉死文本更新为 scalafmt 重排后的两行形态(判据语义不变)。
    assert(
      marking.contains("val forward = from.out\n                .filterNot(OutEdge.isLoopEdge)"),
      "the target set must exclude ':loop' back-edge targets on the forward scan (#675(a))"
    )
    // ② 取消族 取代面（chaincancel 批）原样在位——本批不动它
    assert(
      marking.contains("suppressTargets") && marking.contains("cascadeCancelledIds"),
      "the cascade 取代面 (suppressTargets + cascadeCancelledIds) must stay in place"
    )
    // ③ 传导面（三答 3 的具名钉点）：`referencesOf` 的两条方向扫描俱在 —— **另一处**，本批零改动
    // 2026-09-25 B 步重钉:referencesOf 曾留守 NodeEngine,仅因取消/销毁窗簇迁出的
    // NodeCanceller(self-type trait)经 cascadeClosure 引用它而加宽 private[project]。
    // 2026-09-25 G 步重钉:referencesOf 随终态化簇自 NodeEngine 迁至 NodeCompletion
    // (self-type trait,行为保持重构)——读数经 engineSrcWindow 跨文件聚合解析,锚文本
    // 不变。判据语义不变。
    val conduction = engineSrcWindow("private[project] def referencesOf", 30)
    assert(
      conduction.contains("filterNot(OutEdge.isLoopEdge)") && conduction.contains("!OutEdge.isLoopEdge(e)"),
      "the conduction exclusion stays pinned at referencesOf (forward + reverse scans) — a DIFFERENT site from the marking face"
    )
    // ④ 负向：打标面**不得**借传导闭包（`cascadeClosure`）代劳 —— 两处口径各有其位点
    //    （⚠ 注意：本窗口在**剥注释后**取 60 **代码行**，故会比 60 原始行伸得更远——含
    //      `referencesOf` 自身的签名行；所以此处判据取「不得调用传导闭包」而非「不得出现
    //      `referencesOf` 字样」，后者会被邻接方法的签名行误红）。
    assert(
      !marking.contains("cascadeClosure"),
      "the marking leg must not re-derive the conduction union (two faces stay separate sites)"
    )
  }

  // ── C1/C2/C5：面板取消 ⇒ agent 可见通知（同一通道）──────────────────────

  test(
    "C1/C2/C5 R1: panel cancelAgent finalizes the node as cancelled[source=user] AND reaches the agent through the SAME dispatch-notify channel — text carries source=user + node id + chain id, forbids re-dispatch, and exactly ONE notification goes out"
  ) {
    val triggered = Ref.unsafe[IO, List[String]](Nil)
    withFixture("panel", hangingLlm, Some(triggered)) { (store, engine, resources, system, _, _, ws) =>
      val io = for
        now <- IO(System.currentTimeMillis())
        // 三成员链（n-d 最早 ⇒ chainId = chain-n-d 在摘除前后恒稳定）：
        //   n-u(取消目标) → n-d → n-e
        _ <- seed(
          store,
          NodeDef(
            id = "n-d",
            name = "D",
            agent = "test-agent",
            task = Some("downstream-D"),
            status = NodeLifecycle.Pending,
            in = List("n-u"),
            out = List(OutEdge("n-e")),
            createdAt = now - 3000L
          )
        )
        _ <- seed(
          store,
          NodeDef(
            id = "n-u",
            name = "U",
            agent = "test-agent",
            task = Some("upstream-U"),
            status = NodeLifecycle.Wiring,
            out = List(OutEdge("n-d")),
            createdAt = now - 2000L
          )
        )
        _ <- seed(
          store,
          NodeDef(
            id = "n-e",
            name = "E",
            agent = "test-agent",
            task = Some("downstream-E"),
            status = NodeLifecycle.Pending,
            in = List("n-d"),
            out = List(OutEdge.nebula),
            createdAt = now - 1000L
          )
        )
        chainBefore <- store.chainIdOf("n-u")
        _ <- engine.startNode("n-u").start
        _ <- waitUntil(30.seconds)(store.getNode("n-u").map(_.exists(_.status == NodeLifecycle.Running)))
        _ <- waitUntil(30.seconds)(nodeSession(resources).map(_.isDefined))
        rec <- nodeSession(resources).map(_.getOrElse(fail("node session must be registered for the panel")))
        // 面板门（WS handler :1401 同款白名单）
        panelGate <- IO(AgentControlTool.cancelable(rec))
        panelFrames <- Ref.of[IO, List[Json]](Nil)
        // 面板入口的引擎侧腿（WS handler :1411-1412 逐字同调用）
        cancelRes <- AgentControlTool.doCancel(
          resources,
          rec,
          "cancelled from panel",
          notifyWs = Some((j: Json) => panelFrames.update(_ :+ j))
        )
        _ <- waitUntil(30.seconds)(store.getNode("n-u").map(_.exists(_.status == NodeLifecycle.Cancelled)))
        _ <- waitUntil(30.seconds)(store.getNode("n-u").map(_.exists(_.notifySentAt.isDefined)))
        u <- store.getNode("n-u").map(_.get)
        d <- store.getNode("n-d").map(_.get)
        texts1 <- triggered.get
        frames <- panelFrames.get
        // C2 去重：同一节点再次进入取消链（引擎侧入口）不得新增通知
        _ <- engine.reapStaleRunning("n-u")
        _ <- IO.sleep(300.millis)
        texts2 <- triggered.get
      yield (panelGate, cancelRes, chainBefore, u, d, texts1, texts2, frames)
      val (panelGate, cancelRes, chainBefore, u, d, texts1, texts2, frames) = io.unsafeRunSync()
      println(
        s"[spec] panel frames (browser-only leg, non-asserted): ${frames.size}; chain before cancel = $chainBefore"
      )
      assertEquals(panelGate, true, "the panel gate must admit a node session (AgentControlTool.cancelable)")
      assert(cancelRes.isRight, s"panel doCancel must succeed: $cancelRes")
      assertEquals(u.status, NodeLifecycle.Cancelled, "panel cancel must finalize the node as cancelled")
      assert(
        u.result.exists(_.startsWith("cancelled[source=user]: reason=")),
        s"R2/R7: the persisted result must carry the source (got ${u.result})"
      )
      // ── C1：回流存在 + 内容契约 ──
      assertEquals(
        texts1.size,
        1,
        s"exactly ONE cancelled notification must reach the agent for the panel cancel, got ${texts1.size}: ${texts1.map(_.take(120))}"
      )
      val t = texts1.head
      println(s"[spec] C1 evidence — cancelled notification text (verbatim, ${t.length} chars):\n${t.take(1600)}")
      println(
        s"[spec] C2 evidence — notification count: after panel cancel=${texts1.size}, after re-entry(reapStaleRunning)=${texts2.size}"
      )
      val header = t.linesIterator.next()
      assert(
        header.contains("source=user"),
        s"R1: the notification header must self-describe the source (not just the echoed reason) — got: $header"
      )
      assert(t.contains("(n-u)"), "R1: text must name the cancelled node id")
      assert(t.contains("reason=cancelled"), "R1: text must carry the reason code")
      assert(t.contains(UserCancelMarker), "R1/R4: text must state this was a USER cancel")
      assert(t.contains(NoReDispatchMarker), "R4: user variant must forbid re-dispatch")
      assert(
        !t.contains(EngineFirstChoice),
        "R4: the user variant must NOT offer 承接 as the first choice (that path re-dispatches the cancelled work)"
      )
      assertEquals(chainBefore, Some("chain-n-d"), "fixture precondition: 3-member chain, n-d earliest")
      assert(t.contains("chain=chain-n-d"), s"R1: text must carry the chain id (chain-n-d) — got: ${t.take(400)}")
      // ── C2：恰一条（第二次入口零新增）──
      assertEquals(texts2.size, 1, s"a second cancel-path entry must not add a notification, got ${texts2.size}")
      // R4 摘除面零回归（既有语义）＋ 待承接标记
      assertEquals(u.out, List(OutEdge.nebula), "R4: out still detached to Nebula (unchanged)")
      assert(
        !d.in.contains("n-u") && d.pendingSuccession == List("n-u"),
        s"R4: downstream mirror still pruned + 待承接 registered, got in=${d.in} ps=${d.pendingSuccession}"
      )
    }
  }

  // ── C3/C5：R4 —— user-cancel 上游不得被重新武装；engine-cancel 对照 ──────

  private def retryFixture(upstreamResult: String, store: FlowMapStore): IO[Unit] =
    val now = System.currentTimeMillis()
    for
      _ <- seed(
        store,
        NodeDef(
          id = "n-u",
          name = "U",
          agent = "test-agent",
          status = NodeLifecycle.Cancelled,
          result = Some(upstreamResult),
          out = List(OutEdge.nebula),
          createdAt = now - 5000L,
          completedAt = Some(now - 4000L)
        )
      )
      _ <- seed(
        store,
        NodeDef(
          id = "n-b",
          name = "B",
          agent = "test-agent",
          task = Some("FAIL-B work"),
          status = NodeLifecycle.Pending,
          out = List(OutEdge.nebula),
          retry = Some(RetryPolicy(upstream = "n-u", max = 3)),
          createdAt = now - 1000L
        )
      )
    yield ()

    end for

  end retryFixture

  test(
    "C3/C5 R4: a USER-cancelled upstream is NOT re-armed by the downstream's failure auto-retry (upstream stays cancelled with its result intact, downstream stays failed with gen untouched, audit records the suppression)"
  ) {
    val triggered = Ref.unsafe[IO, List[String]](Nil)
    withFixture("retry-user", scriptedLlm("FAIL-B"), Some(triggered)) { (store, engine, _, _, _, _, ws) =>
      val io = for
        _ <- retryFixture("cancelled[source=user]: reason=cancelled from panel", store)
        _ <- engine.startNode("n-b").start
        _ <- waitUntil(30.seconds)(store.getNode("n-b").map(_.exists(_.status == NodeLifecycle.Failed)))
        _ <- waitUntil(30.seconds)(triggered.get.map(_.exists(_.contains("reason=failed"))))
        _ <- IO.sleep(500.millis) // 迟到写窗口（retry 腿若存在，此刻已写）
        u <- store.getNode("n-u").map(_.get)
        b <- store.getNode("n-b").map(_.get)
        audit <- readAudit(ws)
        texts <- triggered.get
      yield (u, b, audit, texts)
      val (u, b, audit, texts) = io.unsafeRunSync()
      val retryEvents = audit.filter { case (t, _, _) => t == "retry" }
      assertEquals(
        u.status,
        NodeLifecycle.Cancelled,
        s"R4: a user-cancelled upstream must NOT be reactivated, got ${u.status}"
      )
      assertEquals(
        u.result,
        Some("cancelled[source=user]: reason=cancelled from panel"),
        "R4: the user-cancelled upstream's result must stay intact (not cleared by a re-arm)"
      )
      assertEquals(b.status, NodeLifecycle.Failed, "the downstream stays failed (no self-reactivation either)")
      assertEquals(b.gen, 0, "R4: the retry budget must not be spent (gen untouched)")
      assert(
        retryEvents.exists(_._3.contains("auto-retry suppressed")),
        s"R4: the suppression must be audited on the retry channel — got ${retryEvents.map(_._3)}"
      )
      assert(
        retryEvents.forall(e => !e._3.contains("reactivating self")),
        s"R4: no retry leg may have run — got ${retryEvents.map(_._3)}"
      )
      assert(texts.exists(_.contains("reason=failed")), s"the failed reflux must still fire: $texts")
      assert(!texts.exists(_.contains("已被**取消**")), "no cancelled notification may be produced by this leg")
    }
  }

  test(
    "C3 control: an ENGINE-cancelled upstream keeps today's semantics — the downstream's auto-retry still reactivates it (reactivating self + rerunning upstream)"
  ) {
    val triggered = Ref.unsafe[IO, List[String]](Nil)
    withFixture("retry-engine", scriptedLlm("FAIL-B"), Some(triggered)) { (store, engine, _, _, _, _, ws) =>
      val io = for
        _ <- retryFixture(
          "cancelled[source=engine]: reason=dead-session reap: status=running but no live execution fiber",
          store
        )
        _ <- engine.startNode("n-b").start
        _ <- waitUntil(30.seconds)(store.getNode("n-b").map(n => n.exists(_.gen == 1)))
        _ <- IO.sleep(500.millis)
        u <- store.getNode("n-u").map(_.get)
        b <- store.getNode("n-b").map(_.get)
        audit <- readAudit(ws)
      yield (u, b, audit)
      val (u, b, audit) = io.unsafeRunSync()
      val retryEvents = audit.filter { case (t, _, _) => t == "retry" }
      assert(
        retryEvents.exists(_._3.contains("auto-retry: reactivating self + rerunning upstream")),
        s"engine-cancel semantics must be preserved (retry leg runs) — got ${retryEvents.map(_._3)}"
      )
      assert(
        u.status != NodeLifecycle.Cancelled,
        s"the engine-cancelled upstream must be reactivated as today, got ${u.status}"
      )
      assert(u.result.isEmpty, s"reactivation clears the result (existing field family), got ${u.result}")
      assertEquals(b.gen, 1, "the retry budget is spent as today")
    }
  }

  // ── C3（agent 面）：boot 清单 recommend 对 user-cancel 槽位不得再建议「承接」──

  private def inventoryJson(upstreamResult: String, now: Long): String =
    FlowMapState(
      project = "cs-boot",
      updatedAt = now,
      nodes = Map(
        "n-u" -> NodeDef(
          id = "n-u",
          name = "U",
          agent = "test-agent",
          status = NodeLifecycle.Cancelled,
          result = Some(upstreamResult),
          out = List(OutEdge.nebula),
          createdAt = now - 900_000L,
          completedAt = Some(now - 600_000L)
        ),
        "n-d" -> NodeDef(
          id = "n-d",
          name = "D",
          agent = "test-agent",
          task = Some("downstream"),
          status = NodeLifecycle.Pending,
          in = List("n-u"),
          out = List(OutEdge.nebula),
          createdAt = now - 900_000L
        )
      )
    ).asJson.noSpaces

  test(
    "C3/C5 R4 (agent face): the boot-wake inventory must stop recommending 承接 for a slot whose upstream was cancelled by the USER — the engine-cancel case keeps the today's wording"
  ) {
    val now = System.currentTimeMillis()
    val dir = os.temp.dir(prefix = "cancelsem-inv")
    try
      def itemRecommend(upstreamResult: String): (List[String], String) =
        val inv = BootWakeInventory
          .fromJson(inventoryJson(upstreamResult, now), dir, "cs-boot", now)
          .fold(e => fail(s"inventory must build from a valid flow-map: $e"), identity)
        val it =
          inv.items.find(_.nodeId == "n-d").getOrElse(fail(s"n-d must be listed, got ${inv.items.map(_.nodeId)}"))
        (it.buckets, it.recommend)

      val (userBuckets, userRecommend) =
        itemRecommend("cancelled[source=user]: reason=cancelled from panel")
      val (engineBuckets, engineRecommend) =
        itemRecommend("cancelled[source=engine]: reason=dead-session reap: no live execution fiber")

      assert(
        userBuckets.contains(BootWakeInventory.BucketDeadBarrier),
        s"fixture precondition: B3 (dead barrier, upstream gap) expected, got $userBuckets"
      )
      assert(
        userRecommend.startsWith("改接|放弃"),
        s"R4: for a user-cancelled slot the boot inventory must not offer 承接, got: $userRecommend"
      )
      assert(
        userRecommend.contains(NoReDispatchMarker),
        s"R4: the boot inventory must state the no-re-dispatch rule, got: $userRecommend"
      )
      assert(engineRecommend.startsWith("改接|承接"), s"engine-cancel wording must stay as today, got: $engineRecommend")
    finally os.remove.all(dir)
    end try
  }
end CancelSemanticsSourceSpec
