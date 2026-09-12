package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.core.flow.{MailDeliveryDedup, MailQueueStore, TeamSessionRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * V13 (2026-09-03, 结果投递链丢失向量修复): mail-dedup 收窄至重放窗口。
 *
 * 丢失形态（审计 V13）：指纹 = SHA-256(sender|recipient|content)，30min 窗口内
 * 相同三元组第二次 queue 投递被消费但不注入——重启重放与合法同文重发不可区分
 * （10min 轮询文本、重发指令均被吞，仅 WARN 留痕）。
 *
 * 修复：dedup 咨询仅作用于收件会话激活后 MailDedupReplayWindowMs（60s）内——
 * 重启恢复 re-fire（MailTool activateAgent 重发磁盘 head）是唯一真实重复源，
 * 只可能在激活后数秒内注入。窗口外投递不再咨询/不再记账 → 合法同文重发无条件
 * 投递（R1/R4），重放形状照旧抑制（R2）。
 *
 * 用例：
 *  - R1 窗口外同文重发放行（丢弃形态的红基线场景）
 *  - R1b 窗口外投递不记账（后续窗口内重放不被误杀为重复……反向：账本无该指纹）
 *  - R2 重放形状（激活窗口内 + 磁盘指纹跨重启）照旧抑制
 *  - R3 窗口过期放行（既有语义不回归）
 *  - R4 运行时全链（真 AgentActor + MailTool）：激活 >60s 后同文重发 → 两次都注入
 */
class MailDedupReplayWindowSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  // ── 组件级 ──────────────────────────────────────────────

  test("R1: same-content re-send OUTSIDE the replay window delivers even with a fresh fingerprint") {
    val tmp = os.temp.dir(prefix = "v13-r1")
    PathUtil.setDataRoot(tmp / "data")
    MailDeliveryDedup.reset()
    try
      val sid = "sess-v13-r1"
      val fp = MailDeliveryDedup.fingerprint("alice", sid, "10min polling text")
      val now = System.currentTimeMillis()
      // First delivery (inside replay window — normal activation-time mail):
      // allowed + recorded, exactly like the pre-fix first delivery.
      val first = MailDeliveryDedup.tryDeliver(sid, fp, now).unsafeRunSync()
      // 10 minutes later: a legitimate same-content re-send. Pre-fix this was
      // suppressed by the fresh fingerprint (the audited loss); post-fix the
      // caller passes withinReplayWindow=false (activation was long ago) and
      // the mail MUST deliver.
      val resend =
        MailDeliveryDedup.tryDeliver(sid, fp, now + 10 * 60 * 1000L, withinReplayWindow = false).unsafeRunSync()
      assert(clue(first), "first delivery must pass")
      assert(clue(resend), "legitimate re-send outside the replay window must NOT be suppressed (V13 red-baseline scenario)")
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

  test("R1b: outside-window delivery leaves no ledger entry (no consult, no record)") {
    val tmp = os.temp.dir(prefix = "v13-r1b")
    PathUtil.setDataRoot(tmp / "data")
    MailDeliveryDedup.reset()
    try
      val sid = "sess-v13-r1b"
      val fp = MailDeliveryDedup.fingerprint("bob", sid, "content X")
      val now = System.currentTimeMillis()
      // Outside-window delivery: delivers but must not touch the ledger.
      val outside =
        MailDeliveryDedup.tryDeliver(sid, fp, now + 60 * 60 * 1000L, withinReplayWindow = false).unsafeRunSync()
      assert(clue(outside), "outside-window delivery passes")
      assert(!os.exists(PathUtil.dataRoot / "mail-dedup.json"), "no ledger file may be written by an outside-window delivery")
      // A replay-shaped delivery (inside window) of the SAME content right
      // after must therefore be injected — the ledger never saw the content.
      val replay = MailDeliveryDedup.tryDeliver(sid, fp, now + 60 * 60 * 1000L + 1000).unsafeRunSync()
      assert(clue(replay), "replay consult misses — content was never recorded, so it delivers")
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

  test("R2: restart replay INSIDE the replay window is still suppressed (disk fingerprint)") {
    val tmp = os.temp.dir(prefix = "v13-r2")
    PathUtil.setDataRoot(tmp / "data")
    MailDeliveryDedup.reset()
    try
      val sid = "sess-v13-r2"
      val fp = MailDeliveryDedup.fingerprint("carol", sid, "REPLAY_MARKER_V13")
      val now = System.currentTimeMillis()
      // Pre-restart delivery recorded to disk; JVM restart (reset) wipes memory.
      assert(MailDeliveryDedup.tryDeliver(sid, fp, now).unsafeRunSync(), "pre-restart delivery passes")
      assert(os.exists(PathUtil.dataRoot / "mail-dedup.json"), "fingerprint persisted")
      MailDeliveryDedup.reset()
      // Post-restart recovery re-fire — by construction inside the replay
      // window (activation just happened) → suppressed exactly as before.
      val replay = MailDeliveryDedup.tryDeliver(sid, fp, now + 5000, withinReplayWindow = true).unsafeRunSync()
      assert(!clue(replay), "replay inside the replay window must still be suppressed (no double injection)")
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

  test("R3: expired fingerprint inside the replay window still delivers (window semantics unchanged)") {
    val tmp = os.temp.dir(prefix = "v13-r3")
    PathUtil.setDataRoot(tmp / "data")
    MailDeliveryDedup.reset()
    try
      val sid = "sess-v13-r3"
      val fp = MailDeliveryDedup.fingerprint("dave", sid, "old content")
      val now = System.currentTimeMillis()
      assert(MailDeliveryDedup.tryDeliver(sid, fp, now).unsafeRunSync(), "first passes")
      val after =
        MailDeliveryDedup.tryDeliver(sid, fp, now + nebflow.shared.Defaults.MailDedupWindowMs + 1).unsafeRunSync()
      assert(clue(after), "expired fingerprint delivers — dedup window semantics untouched by V13")
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

  // ── R2 分层地址面（2026-09-12）：新腿的重放窗行为显式钉死 ──

  test("R5 R2：node: 腿 immediate —— 无投递级去重（不咨询 mail-dedup、不记账、不落盘）") {
    val tmp = os.temp.dir(prefix = "r2-r5")
    PathUtil.setDataRoot(tmp / "data")
    MailDeliveryDedup.reset()
    try
      val sid = "node-sess-r5"
      // node: 腿 = MailTool.deliverToNode → 引擎单点 sendNodeMessage（三态判据），
      // **不经** AgentActor 的 queue drain ⇒ MailDeliveryDedup 结构上不参与。
      // 本用例的可执行判据：两次同文 node: 投递后，抑制计数与账本文件均无变化
      //（若 node 腿误经 queue+dedup，第二次会被吞且记账 ⇒ 断言可被反向证伪）。
      val before = MailDeliveryDedup.suppressedTotal
      val fp = MailDeliveryDedup.fingerprint("disp-r5", sid, "节点补充同文")
      // 证据锚②：同一 (sender|sid|content) 三元组在**组件层**确实会被抑制
      // —— 证明上面的「无变化」不是因为指纹面失效，而是 node 腿根本不走这里。
      val now = System.currentTimeMillis()
      assert(MailDeliveryDedup.tryDeliver(sid, fp, now).unsafeRunSync(), "组件层首投放行")
      assert(!MailDeliveryDedup.tryDeliver(sid, fp, now + 1000).unsafeRunSync(), "组件层同三元组窗口内被抑制")
      val afterComponent = MailDeliveryDedup.suppressedTotal
      assertEquals(afterComponent, before + 1L, "组件层抑制计数 +1（哨兵有载力）")
      // node: 腿不落 queue 面 ⇒ 无 MailQueueStore 落盘、无 mail-dedup 账本条目
      assertEquals(MailQueueStore.load(sid).unsafeRunSync(), Nil, "node 会话不得有 queue 条目")
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

  // ── 运行时全链（真 AgentActor + MailTool.activateAgent）────────

  private class RecordingLlm(delayMs: Long = 0L) extends LlmHandle[IO]:
    val requests: Ref[IO, List[LlmRequest]] = Ref.unsafe(Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(req :: _)) >>
        Stream.eval(IO.sleep(delayMs.millis)) >>
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def fixtureTeam(tmp: os.Path): Unit =
    val data = tmp / "data"
    val teamDir = data / "teams" / "dq"
    os.makeDir.all(teamDir)
    os.write.over(
      teamDir / "team.json",
      """{"name": "dq", "description": "v13 fixture", "lead": "boss", "members": ["member"]}"""
    )
    val memberDir = teamDir / "agents" / "member"
    os.makeDir.all(memberDir)
    os.write.over(memberDir / "agent.json", """{"description": "fixture member", "useWhen": "tests"}""")

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO], sessionStore: SessionStore): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = sessionStore,
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

  private def ctxFor(resources: SharedResources, system: ActorSystem, senderSid: String): nebflow.core.tools.ToolContext =
    nebflow.core.tools.ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(senderSid),
      sharedResources = Some(resources),
      actorSystem = Some(system)
    )

  private def mkItem(id: String, message: String): MailQueueStore.MailQueueItem =
    MailQueueStore.MailQueueItem(
      id = id, from = "tester", fromSession = "sender-dd",
      message = message, `type` = "INFO",
      timestamp = System.currentTimeMillis(), imagePaths = Nil
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
      cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  test("R4 runtime: same-content re-send long after activation injects BOTH mails (audited loss shape, green)") {
    val system = ActorSystem(s"v13-r4-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "v13-r4")
    fixtureTeam(tmp)
    PathUtil.setDataRoot(tmp / "data")
    MailDeliveryDedup.reset()
    val llm = new RecordingLlm
    try
      val io = for
        sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
        meta <- sessionStore.createSession("dq/member", agentName = Some("member"), flowName = Some("dq"))
        _ <- TeamSessionRegistry.registerSession("dq", "member", meta.id)
        resources <- mkResources(system, tmp, llm, sessionStore)
        refOpt <- MailTool.activateAgent(meta.id, resources, system, ctxFor(resources, system, "sender-dd"))
        // Age the recipient's activation stamp far beyond the replay window —
        // simulates "the session has been alive for a long time", so any
        // arriving mail is by definition NOT a restart-replay re-fire.
        _ <- resources.agentRegistry.update { m =>
          m.updatedWith(meta.id)(_.map(_.copy(startedAt = System.currentTimeMillis() - nebflow.shared.Defaults.MailDedupReplayWindowMs * 20))
          )
        }
        // First mail — normal delivery + injection.
        item1 = mkItem("mail-q-v13a", "V13_RESEND_MARKER")
        _ <- MailQueueStore.append(meta.id, item1)
        _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(item1, "sender-dd"))
        _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
        _ <- IO.sleep(500.millis) // turn completes (incl. reminder retries), actor back to idle
        countAfterFirstTurn <- llm.requests.get.map(_.size)
        // Second mail: DISTINCT queue item, IDENTICAL content, 20× replay
        // window after activation. Pre-fix this was consumed-without-inject
        // (the audited loss — NO new turn at all); post-fix it must inject.
        _ <- MailQueueStore.append(meta.id, mkItem("mail-q-v13b", "V13_RESEND_MARKER"))
        _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(mkItem("mail-q-v13b", "V13_RESEND_MARKER"), "sender-dd"))
        _ <- waitUntil(20.seconds)(MailQueueStore.load(meta.id).map(_.isEmpty))
        _ <- waitUntil(20.seconds)(llm.requests.get.map(_.size > countAfterFirstTurn))
        _ <- IO.sleep(1.second) // let the injection turn settle
        reqs <- llm.requests.get
        queueLeft <- MailQueueStore.load(meta.id)
      yield (countAfterFirstTurn, reqs, queueLeft)

      val (countAfterFirstTurn, reqs, queueLeft) = io.unsafeRunSync()
      val secondTurnRequests = reqs.drop(countAfterFirstTurn)
      assert(
        clue(secondTurnRequests).exists(_.messages.exists(_.textContent.contains("V13_RESEND_MARKER"))),
        s"the re-sent mail must open a NEW injection turn (pre-fix: suppressed, no turn). firstTurn=$countAfterFirstTurn total=${reqs.size}"
      )
      assert(clue(queueLeft).isEmpty, "both queue items consumed")
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

end MailDedupReplayWindowSpec
