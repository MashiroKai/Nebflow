package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.agent.*
import nebflow.core.{FileChangeTracker, PathUtil}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import java.security.MessageDigest
import scala.concurrent.duration.*

/**
 * cancelsem 批 2 —— **failed 通知文本的抑制态联动**（作者 2026-09-16 裁定 #696②）
 * 机械用例。
 *
 * 被验语义：当下游节点的 `retry.upstream` 是**用户主动取消**的节点时，引擎的自动重试
 * 整腿被抑制（cancelsem 批 1 · R4）——但 failed 回流文本原本仍劝「首选重激活重跑」
 * （把用户刚停下的上游重新卷进来）。本批让 failed 文本**感知抑制态**（改劝「等承接 /
 * 勿重激活」），且**非抑制态文本逐字不变**。
 *
 * 验收面（逐条对应任务书判据）：
 *  - **F1**：抑制态文本不含「首选重激活」劝语（负向锚 = [[DispatchNotify.FailedReactivateLead]]
 *    常量），且含抑制自描述头行字段（[[DispatchNotify.SuppressedRetryKey]]）+ 上游 id；
 *    同一份源码在**改前树必红**（读数见批报告 / 证据 `f5-*`）。
 *  - **F2（零回归，硬）**：四形态非抑制态 failed 文本 sha256 = **改前树读数**（字节锁）。
 *  - **F3（可见性不丢）**：抑制事实仍落 `retry` 审计事件 + WARN（逐字未改），且分发器
 *    侧照常收到一条可判读通知（真引擎路径，非仅单测直驱）。
 *  - **F4（判据单源）**：抑制态判定 = [[DispatchNotify.userCancelSuppression]]，
 *    与 `NodeEngine.retryOrNotify` 第四态**同函数同常量**（本 spec 的引擎臂即证明
 *    「引擎不重武装」与「文本改劝」由同一判据同时决定）。
 *
 * ⚠ 断面声明：本文件不断言 LLM/网关胶水层；引擎臂走真实 NodeEngine 桥 + 真实终态写点
 * + 真实回流接缝（与 `CancelSemanticsSourceSpec` 同一装配口径，见该文件头注）。
 */
class FailedNotifySuppressionSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  // ── 判据锚（本批新增；两条既有契约锚 UserCancelLead / NoReDispatchPhrase 逐字未改）──

  /** 抑制态头行自描述字段（正向锚）。 */
  private val SuppressedKey = DispatchNotify.SuppressedRetryKey

  /** 抑制态**不得出现**的「首选重激活」劝语（负向锚 = 常态文本的首句片段）。 */
  private val ReactivateLead = DispatchNotify.FailedReactivateLead

  /** 常态 failed 动作 1 的逐字片段（第二负向锚，`CancelDeadlockFixSpec:236` 同族）。 */
  private val FailedRecipePhrase = "NodeEdit 编辑该节点任意实际变更"
  private val FailedRevivePhrase = "原节点复活"

  /** cancelled 专属的绝对句——failed 抑制态变体**不得**照抄（本节点仍可显式重激活）。 */
  private val CancelledAbsolutePhrase = "不可重激活"

  /**
   * **F2 字节锁**：四形态非抑制态 failed 文本在**改前树**（`86064874f`）上的 sha256。
   *
   * 读数原文（同一份探针夹具 `FailTextProbeSpec`、同一 fixture 命名）：
   * `.nebflow/evidence/20260916_failtext/e1-probe-prechange.log`。改后逐形态相同
   * ⇒ 非抑制态文本零漂移（`e2-probe-postchange.log`）。
   */
  private val PreChangeSha: Map[String, String] = Map(
    "no-retry" -> "615003108f5240f800a3b01fb0e732257fac2da2788c652e4a5c7faa6026ed35",
    "capped" -> "bc14abf999ea7201a4d315339e1ea082a8be19a9d4a90e0cbdc39d7f90e8b9a6",
    "engine-cancel" -> "139e583dd3458bc003215f236e449a93f400da2d1cbb9a07d3865bd3bf6ab27b",
    "unknown-source" -> "e822a00a57cd394ee85a6ca3c08e0a91aab409ab3288d93b31eed0210713b059"
  )

  /** 改前树抑制态读数（同一夹具）：与常态同形（664 B），改后必须变（新变体 922 B）。 */
  private val PreChangeSuppressedSha = "bde725c93b800b3fd4a0612d244ffbadfdc34a00f758cae8b8787b7393e0e264"

  private def sha256(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes("UTF-8"))
      .map(b => f"$b%02x")
      .mkString

  // ── 单测装配：直驱 DispatchNotify（窗口关闭，判据确定性）────────────────────

  /**
   * 五形态 fixture（**与探针 `FailTextProbeSpec` 逐字同形**——F2 的 sha 常量即取自
   * 同一 fixture 的改前读数；命名/project/节点 id/结果文本全部固定 ⇒ 文本全确定）。
   */
  private def fiveForms(): IO[Map[String, String]] =
    val proj = "failtext-probe"
    val tmp = os.temp.dir(prefix = "failtext-spec")
    val ws = tmp / "ws"
    val now = 1_700_000_000_000L
    val io = for
      _ <- IO(os.makeDir.all(ws))
      store <- FlowMapStore.open(proj, ws.toString)
      triggered <- Ref.of[IO, List[String]](Nil)
      dn = new DispatchNotify(
        store,
        ws.toString,
        proj,
        escalate = (_: String, _: String) => IO.unit,
        emitUpdated = (_: NodeDef) => IO.unit,
        trigger = (t: String) => triggered.update(_ :+ t),
        failedBudgetMax = 1000,
        failedWindowThreshold = 1000,
        windowMs = 0L
      )
      _ <- store
        .mutate(s =>
          s.copy(nodes =
            s.nodes ++ Map(
              "n-u-engine" -> NodeDef(
                id = "n-u-engine",
                name = "UE",
                agent = "general",
                status = NodeLifecycle.Cancelled,
                result = Some(
                  "cancelled[source=engine]: reason=dead-session reap: status=running but no live execution fiber"
                ),
                out = List(OutEdge.root),
                createdAt = now - 9000L,
                completedAt = Some(now - 8000L)
              ),
              "n-u-unknown" -> NodeDef(
                id = "n-u-unknown",
                name = "UX",
                agent = "general",
                status = NodeLifecycle.Cancelled,
                result = Some("cancelled by NodeCancel (legacy data, no source prefix)"),
                out = List(OutEdge.root),
                createdAt = now - 9000L,
                completedAt = Some(now - 8000L)
              ),
              "n-u-user" -> NodeDef(
                id = "n-u-user",
                name = "UU",
                agent = "general",
                status = NodeLifecycle.Cancelled,
                result = Some("cancelled[source=user]: reason=cancelled from panel"),
                out = List(OutEdge.root),
                createdAt = now - 9000L,
                completedAt = Some(now - 8000L)
              ),
              "n-u-done" -> NodeDef(
                id = "n-u-done",
                name = "UD",
                agent = "general",
                status = NodeLifecycle.Completed,
                result = Some("done"),
                out = List(OutEdge.root),
                createdAt = now - 9000L,
                completedAt = Some(now - 8000L)
              )
            )
          )
        )
        .void
      forms = List(
        "no-retry" -> NodeDef(
          id = "n-f1",
          name = "F1",
          agent = "general",
          status = NodeLifecycle.Failed,
          result = Some("boom: scripted failure"),
          gen = 0,
          createdAt = now - 1000L
        ),
        "capped" -> NodeDef(
          id = "n-f2",
          name = "F2",
          agent = "general",
          status = NodeLifecycle.Failed,
          result = Some("boom: scripted failure"),
          retry = Some(RetryPolicy("n-u-done", 1)),
          gen = 1,
          createdAt = now - 1000L
        ),
        "engine-cancel" -> NodeDef(
          id = "n-f3",
          name = "F3",
          agent = "general",
          status = NodeLifecycle.Failed,
          result = Some("boom: scripted failure"),
          retry = Some(RetryPolicy("n-u-engine", 3)),
          gen = 0,
          createdAt = now - 1000L
        ),
        "unknown-source" -> NodeDef(
          id = "n-f4",
          name = "F4",
          agent = "general",
          status = NodeLifecycle.Failed,
          result = Some("boom: scripted failure"),
          retry = Some(RetryPolicy("n-u-unknown", 3)),
          gen = 0,
          createdAt = now - 1000L
        ),
        "suppressed" -> NodeDef(
          id = "n-f5",
          name = "F5",
          agent = "general",
          status = NodeLifecycle.Failed,
          result = Some("boom: scripted failure"),
          retry = Some(RetryPolicy("n-u-user", 3)),
          gen = 0,
          createdAt = now - 1000L
        )
      )
      _ <- store.mutate { s =>
        val waiters = forms.zipWithIndex.map { case ((_, f), i) =>
          s"n-w${i + 1}" -> NodeDef(
            id = s"n-w${i + 1}",
            name = s"W${i + 1}",
            agent = "general",
            status = NodeLifecycle.Pending,
            task = Some("waiting"),
            in = List(f.id),
            out = List(OutEdge.root),
            createdAt = now - 500L
          )
        }.toMap
        s.copy(nodes = s.nodes ++ forms.map { case (_, f) => f.id -> f }.toMap ++ waiters)
      }.void
      _ <- forms.traverse_ { case (_, f) => dn.notifyTerminal(f, NotifyReason.Failed) }
      texts <- triggered.get
      marked <- forms.traverse { case (_, f) => store.getNode(f.id).map(_.flatMap(_.notifySentAt).isDefined) }
    yield (forms.map(_._1).zip(texts).toMap, marked)

    io.guaranteeCase(_ => IO(os.remove.all(tmp)))
      .map { case (texts, marked) =>
        assertEquals(texts.size, 5, s"one notification per form expected, got ${texts.keys}")
        assert(marked.forall(identity), "F3: every form must still be marked (tell-then-mark chain intact)")
        texts
      }
  end fiveForms

  // ── F2：非抑制态四形态逐字不变（字节锁）─────────────────────────────

  test(
    "F2: the four NON-suppressed failed-text forms are byte-identical to the pre-change tree (sha256 lock: no retry / gen>=max RetryCap / engine-cancel upstream / unclassifiable source)"
  ) {
    val texts = fiveForms().unsafeRunSync()
    PreChangeSha.foreach { case (form, pre) =>
      val t = texts(form)
      assertEquals(
        sha256(t),
        pre,
        s"F2 zero-regression: form '$form' text must be byte-identical to the pre-change reading"
      )
      assert(
        !t.contains(SuppressedKey),
        s"F2: form '$form' must NOT carry the suppressed-state marker (that is a suppression-only field)"
      )
      assert(t.contains(ReactivateLead), s"F2: form '$form' must keep the normal first-choice recipe verbatim")
    }
    // 对照读数（同一夹具）：抑制态改前 sha = PreChangeSuppressedSha（**常态形态**文本，
    // 即被修的缺陷）；改后必须换形态（F1 臂另给正向/负向锚）
    assert(
      sha256(texts("suppressed")) != PreChangeSuppressedSha,
      "the suppressed fixture must no longer render the pre-change (normal-shape) text"
    )
  }

  // ── F1：抑制态文本改劝（正向锚 + 双重负向锚）─────────────────────────

  test(
    "F1: the suppressed failed text self-describes the suppression and drops the 'first choice = reactivate' advice — it neither copies the cancelled-only absolute sentence nor claims the normal recipe"
  ) {
    val texts = fiveForms().unsafeRunSync()
    val t = texts("suppressed")
    println(s"[spec] F1 evidence — suppressed failed text (verbatim, ${t.length} chars):\n$t")
    println(s"[spec] F1 evidence — sha256=${sha256(t)} (pre-change was ${PreChangeSuppressedSha})")
    // 正向锚：抑制态自描述 + 上游身份（分发器无需自查即知谁被取消）
    assert(t.contains(SuppressedKey), s"F1: header must carry the suppressed marker ($SuppressedKey) — got: $t")
    assert(t.contains(DispatchNotify.UserCancelLead), "F1: text must state the upstream was a USER cancel")
    assert(t.contains("(n-u-user)"), "F1: text must name the user-cancelled upstream id")
    // 负向锚①：不得含「首选重激活」劝语
    assert(
      !t.contains(ReactivateLead),
      s"F1 (author criterion): the suppressed text must NOT advise reactivation as the first choice (found '$ReactivateLead')"
    )
    // 负向锚②：不得含常态动作 1 的重激活配方
    assert(
      !t.contains(FailedRecipePhrase) && !t.contains(FailedRevivePhrase),
      "F1: the suppressed text must not carry the normal step-1 reactivate recipe"
    )
    // 负向锚③：不得照抄 cancelled 的绝对句（failed 仍可被显式重激活）
    assert(
      !t.contains(CancelledAbsolutePhrase),
      "F1: the suppressed variant must not copy the cancelled-only absolute sentence (this node is still explicitly reactivatable)"
    )
    // 改劝到位 + 逐字保留面
    assert(t.contains("等承接") && t.contains("勿重激活"), "F1: the advice must be re-pointed to 等承接 / 勿重激活")
    assert(t.contains("reason=failed"), "the reason code must stay (dispatcher routing depends on it)")
    assert(t.contains("boom: scripted failure"), "the error summary line must stay")
    assert(t.contains(s"""NodeList(detail="n-f5", project=failtext-probe)"""), "the full-result pointer must stay")
    assert(t.contains("无需回报——拓扑与状态已落 Flow Map。"), "the tail line must stay")
    assert(
      t.contains("W5(n-w5)") && t.contains("不会自动续跑"),
      "F1: the waiter line must be rewritten for the suppressed state (no false auto-resume promise)"
    )
    // 改前/改后不同（本批真增量）
    assert(sha256(t) != PreChangeSuppressedSha, "the suppressed text must differ from the pre-change reading")
  }

  // ── 引擎臂（F1 真实接缝 + F3 可见性）：真实 NodeEngine 桥 ────────────────

  private def hangingLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      Stream.eval(IO.never)

  private def scriptedLlm(failMarker: String): LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      val text = req.messages.map(_.textContent).mkString("\n")
      if text.contains(failMarker) then Stream.raiseError[IO](new RuntimeException(s"spec: $failMarker"))
      else Stream.eval(IO.never)

  /** 引擎装配（与 `CancelSemanticsSourceSpec.withFixture` 同一口径）。 */
  private def withFixture(
    name: String,
    llm: LlmHandle[IO]
  )(body: (FlowMapStore, NodeEngine, Ref[IO, List[String]], os.Path) => Unit): Unit =
    val tmp = os.temp.dir(prefix = s"failtext-$name")
    PathUtil.setDataRoot(tmp / "data")
    os.makeDir.all(tmp / "data" / "agents" / "test-agent")
    os.write.over(
      tmp / "data" / "agents" / "test-agent" / "agent.json",
      """{"name":"test-agent","description":"failtext spec agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tmp / "data" / "agents" / "test-agent" / "system.md", "# test-agent\n")
    val system = ActorSystem(s"failtext-$name")
    try
      val ws = tmp / "ws"
      val io = for
        _ <- IO(os.makeDir.all(ws))
        store <- FlowMapStore.open(s"ftproj-$name", ws.toString)
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
        triggered <- Ref.of[IO, List[String]](Nil)
        rootSid = "ft-root-sid"
        rootRef <- system.spawn(recorderBehavior(recorded), s"ft-root-${name.take(8)}")
        engine = new NodeEngine(
          store,
          system,
          resources,
          _ => IO.unit,
          ws.toString,
          rootSid,
          s"ftproj-$name",
          FeedbackRouter.ModeAuto,
          (_, _, _) => IO.unit,
          notifyTriggerOverride = Some((text: String) => triggered.update(_ :+ text))
        )
      yield (store, engine, resources, rootSid, rootRef, triggered)
      val (store, engine, resources, rootSid, rootRef, triggered) = io.unsafeRunSync()
      resources.agentRegistry
        .update(_ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid)))
        .unsafeRunSync()
      body(store, engine, triggered, ws)
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

  private def awaitTerminalTailDrained(store: FlowMapStore): IO[Unit] =
    def converged: IO[Boolean] =
      store.snapshot.map(
        _.nodes.values.filter(n => NodeLifecycle.Terminal.contains(n.status)).forall(_.notifySentAt.isDefined)
      )
    def go(deadline: Long): IO[Unit] =
      converged.flatMap {
        case true => IO.unit
        case false if System.currentTimeMillis() >= deadline => IO.unit
        case false => IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + 10_000L)

  private def removeTempBounded(tmp: os.Path, attempts: Int = 3): Unit =
    def go(n: Int): Unit =
      try os.remove.all(tmp)
      catch
        case e: java.nio.file.DirectoryNotEmptyException if n > 1 =>
          Thread.sleep(200L)
          go(n - 1)
        case _: Throwable => ()
    go(attempts)

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] = cond.flatMap {
      case true => IO.unit
      case _ if System.currentTimeMillis() >= deadline =>
        IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
      case false => IO.sleep(every) >> go(deadline)
    }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(
        _.flatMap(l =>
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

  private def retryFixture(upstreamResult: String, store: FlowMapStore): IO[Unit] =
    val now = System.currentTimeMillis()
    for
      _ <- store.mutate(s =>
        s.copy(nodes =
          s.nodes + ("n-u" -> NodeDef(
            id = "n-u",
            name = "U",
            agent = "test-agent",
            status = NodeLifecycle.Cancelled,
            result = Some(upstreamResult),
            out = List(OutEdge.root),
            createdAt = now - 5000L,
            completedAt = Some(now - 4000L)
          ))
        )
      )
      _ <- store.mutate(s =>
        s.copy(nodes =
          s.nodes + ("n-b" -> NodeDef(
            id = "n-b",
            name = "B",
            agent = "test-agent",
            task = Some("FAIL-B work"),
            status = NodeLifecycle.Pending,
            out = List(OutEdge.root),
            retry = Some(RetryPolicy(upstream = "n-u", max = 3)),
            createdAt = now - 1000L
          ))
        )
      )
    yield ()

    end for

  end retryFixture

  test(
    "F1/F3 (engine seam): a downstream whose retry.upstream was cancelled by the USER fails ⇒ the dispatcher receives the SUPPRESSED variant (no first-choice reactivation advice) while the retry audit + WARN facts are preserved verbatim"
  ) {
    withFixture("user", scriptedLlm("FAIL-B")) { (store, engine, triggered, ws) =>
      val io = for
        _ <- retryFixture("cancelled[source=user]: reason=cancelled from panel", store)
        _ <- engine.startNode("n-b").start
        _ <- waitUntil(30.seconds)(store.getNode("n-b").map(_.exists(_.status == NodeLifecycle.Failed)))
        _ <- waitUntil(30.seconds)(triggered.get.map(_.nonEmpty))
        _ <- IO.sleep(500.millis)
        u <- store.getNode("n-u").map(_.get)
        b <- store.getNode("n-b").map(_.get)
        audit <- readAudit(ws)
        texts <- triggered.get
      yield (u, b, audit, texts)
      val (u, b, audit, texts) = io.unsafeRunSync()
      val retryEvents = audit.filter { case (t, _, _) => t == "retry" }
      val notifyEvents = audit.filter { case (t, _, _) => t == "dispatch-notify" }
      println(s"[spec] F1/F3 engine evidence — retry audit: ${retryEvents.map(_._3)}")
      println(s"[spec] F1/F3 engine evidence — dispatch-notify audit: ${notifyEvents.map(_._3)}")
      println(s"[spec] F1/F3 engine evidence — injected text (verbatim):\n${texts.mkString("\n---\n")}")
      // R4 机制面照旧（零回归）：不重武装、不重跑、预算未花
      assertEquals(u.status, NodeLifecycle.Cancelled, "R4: the user-cancelled upstream must stay cancelled")
      assertEquals(b.gen, 0, "R4: the retry budget must not be spent")
      // F3：抑制事实仍可见（审计逐字保留 + 通知仍注入）
      assert(
        retryEvents.exists(_._3.contains("auto-retry suppressed")),
        s"F3: the suppression audit must stay verbatim — got ${retryEvents.map(_._3)}"
      )
      assert(
        notifyEvents.exists(_._3.contains("triggered: failed → dispatcher")),
        s"F3: the failed reflux must still fire — got ${notifyEvents.map(_._3)}"
      )
      assertEquals(texts.size, 1, s"F3: exactly one dispatcher injection expected, got ${texts.size}")
      val t = texts.head
      // F1（真实接缝）：分发器收到的就是抑制态变体
      assert(t.contains(SuppressedKey), s"F1: the injected text must carry the suppressed marker — got: ${t.take(300)}")
      assert(!t.contains(ReactivateLead), "F1: the injected text must not advise first-choice reactivation")
      assert(!t.contains(FailedRecipePhrase), "F1: the injected text must not carry the normal step-1 recipe")
      assert(
        t.contains("(n-u)") && t.contains(DispatchNotify.UserCancelLead),
        "F1: the injected text must name the user-cancelled upstream"
      )
    }
  }

  test(
    "F2 control (engine seam): an ENGINE-cancelled upstream keeps today's semantics AND today's text (reactivation recipe intact, no suppressed marker)"
  ) {
    withFixture("engine", scriptedLlm("FAIL-B")) { (store, engine, triggered, ws) =>
      val io = for
        _ <- retryFixture(
          "cancelled[source=engine]: reason=dead-session reap: status=running but no live execution fiber",
          store
        )
        _ <- engine.startNode("n-b").start
        _ <- waitUntil(30.seconds)(store.getNode("n-b").map(n => n.exists(_.gen == 1)))
        _ <- IO.sleep(600.millis)
        audit <- readAudit(ws)
        texts <- triggered.get
      yield (audit, texts)
      val (audit, texts) = io.unsafeRunSync()
      val retryEvents = audit.filter { case (t, _, _) => t == "retry" }
      assert(
        retryEvents.exists(_._3.contains("auto-retry: reactivating self + rerunning upstream")),
        s"engine-cancel semantics must be preserved — got ${retryEvents.map(_._3)}"
      )
      assert(
        texts.forall(t => !t.contains(SuppressedKey)),
        s"F2: an engine-cancel upstream must NOT produce the suppressed variant — got: ${texts.map(_.take(200))}"
      )
      println(
        s"[spec] F2 control evidence — retry audit: ${retryEvents.map(_._3)}; texts=${texts.size} (suppressed marker absent)"
      )
    }
  }
end FailedNotifySuppressionSpec
