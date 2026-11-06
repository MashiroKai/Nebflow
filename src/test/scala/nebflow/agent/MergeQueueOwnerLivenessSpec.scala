package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, AgentStatus, Behavior, Behaviors}
import nebflow.core.delegate.{MergeDeclaration, MergeHealthGate, MergeQueue}
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  PathUtil,
  StreamChunk,
  ThinkingConfig
}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * **合并队列 OWNER-GONE 误判修复批（2026-11-06）验收 spec**——两条门禁腿，一条验
 * 「活的不许判死」，一条验「真死的仍要判死」。
 *
 * == 病灶（改前红）==
 *
 * 存活判据写在 `MailboxProducer.deliver`：`sessionStore.listSessions.exists(_.id ==
 * parentSessionId)`。`listSessions` 只读**会话索引** `_index.json`，而 delegate 会话
 * **从不进索引**（只把 `<id>.json` / `<id>.ui.json` 落盘；见
 * `SessionStore.listSessionsIncludeUnindexed` 与 `shared.SessionScope` 头注）⇒ 对任何
 * delegate 收件方恒假 ⇒ 合并轮指令被判 `parent-session-gone` ⇒
 * `MergeQueue.grant ⑥` → `markOwnerGone`。作者实证：`41cd2d19` 在 AgentControl 里仍是
 * `Idle`、`lastActivity 0s`、root 会话健在，合并槽却被关掉。
 *
 * == 判据（改后）==
 *
 * `SessionLiveness`：活体 actor 记录（`agentRegistry`，AgentControl 同源）∪ 会话记录
 * （索引 ∪ 盘上）⇒ 活着；两源都查不到才判 `parent-session-gone`。真删会话时
 * `deleteSession` 会把索引行与盘上四件一并清掉 ⇒ 真 OWNER-GONE 照旧成立（用例 B 钉死
 * 这条，防止「修误判把真判据一起废掉」）。
 *
 * 用例全部走**真实投递路径**（`SharedResources` 构造时装配的生产
 * `OwnerDeliveryPort` → `MailboxProducer` → 落盘邮箱），不替身端口——替身端口正是这个
 * 缺陷藏身的地方（存量 spec 都用 `MergeQueueTestPorts` 把投递面整件换掉）。
 */
class MergeQueueOwnerLivenessSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-owner-liveness"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    MergeDeclaration.reset()

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeDeclaration.reset()
    MergeHealthGate.clear()
    MergeQueue.OwnerGonePort.clear()
    MergeQueue.BlockNotifyPort.clear()
    MergeQueue.OwnerDeliveryPort.clear()

  // ── 装置（同 MergeReceiptNotifyChainSpec 的生产装配副本，不新开第二套）────────

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private def mkResources(
    system: ActorSystem,
    root: os.Path,
    registry: Ref[IO, Map[String, AgentRecord]]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = fakeLlm,
      dispatcher = dispatcher,
      // 会话存储 = `<dataRoot>/sessions`：与落盘邮箱（`SessionMailbox` 用
      // `PathUtil.dataRoot / "sessions"`）、合并账本、宣告账同一根——判据读的盘上记录
      // 就是投递真正落的地方（否则 fixture 自己会让两条腿错位）。
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tempRoot / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tempRoot / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tempRoot / "subagent-tasks"),
      voiceMutedRef = voiceMuted,
      // AgentControl 同源：会话活体的唯一注册表。
      agentRegistry = registry
    )

  /** 任何 AgentCommand 都吞掉的 sink（只为占住一个「活体 actor」的 ref）。 */
  private def sinkBehavior: Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage(_ => IO.pure(loop))
    loop

  /**
   * 活体注册表（AgentControl 同源）：delegate 行 `Idle` + 刚有心跳，root 会话也在
   * ——作者 08:26 实测的那个形态（活人在场，却被判「父会话已消失」）。
   */
  private def liveRows(
    ref: nebflow.actor.ActorRef[AgentCommand],
    owner: String,
    rootSid: String
  ): Map[String, AgentRecord] =
    val now = System.currentTimeMillis()
    Map(
      owner -> AgentRecord(
        sessionId = owner,
        ref = ref,
        kind = AgentKind.Delegate,
        rootSessionId = rootSid,
        status = AgentStatus.Idle,
        lastActivityMs = now
      ),
      rootSid -> AgentRecord(
        sessionId = rootSid,
        ref = ref,
        kind = AgentKind.Root,
        rootSessionId = rootSid,
        status = AgentStatus.Idle,
        lastActivityMs = now
      )
    )

  private def mkRepo(dir: os.Path, branch: String): os.Path =
    if os.exists(dir) then os.remove.all(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    g("checkout", "-b", branch)
    os.write(dir / "deliverable.txt", "deliverable")
    g("add", ".")
    g("commit", "-m", "deliverable")
    g("checkout", "main")
    dir

  /** 成员（属主身份 = delegate 会话 id，P1 身份化的唯一投递依据）。 */
  private def memberOf(delegateId: String, owner: String, repo: os.Path): MergeQueue.Member =
    MergeQueue.Member(
      delegateId = delegateId,
      branch = s"delegate/$delegateId",
      baseWs = repo.toString,
      baseBranch = "main",
      worktree = false,
      projectKey = s"p-owner-liveness-$delegateId",
      taskId = Some("1688"),
      parentSessionId = "nebula-root-d7c3cb54",
      description = s"owner liveness $delegateId",
      readyAt = System.currentTimeMillis(),
      declaredAt = Some(System.currentTimeMillis() - 1000L),
      declareSource = Some(MergeDeclaration.Source.ReportMerge),
      channelKind = "identity",
      ownerSessionId = owner
    )

  /** 真 delegate 会话的盘上形态：只写 `<id>.json` / `<id>.ui.json`，**不进索引**。 */
  private def writeDelegateSessionOnDisk(sid: String): IO[Unit] =
    IO.blocking {
      val dir = tempRoot / "sessions"
      os.makeDir.all(dir)
      os.write.over(dir / s"$sid.json", "[]", createFolders = true)
      os.write.over(dir / s"$sid.ui.json", "[]", createFolders = true)
    }

  private def mergeTurnEntries(sid: String): IO[List[SessionMailbox.Entry]] =
    SessionMailbox.scanPending(sid).map(_.filter(_.kind == "merge-turn"))

  // ── 用例 A：活着的 delegate（Idle + 心跳 + root 会话在）─────────────────────

  test("A · delegate 存活（Idle + 心跳 + root 会话在）⇒ 合并入队不产生 OWNER-GONE，账本查得到 OPEN 条目") {
    val system = ActorSystem("owner-liveness-a")
    val root = os.temp.dir()
    val repo = mkRepo(root / "repo", branch = "delegate/nebf-41cd2d19")
    val owner = "delegate-nebflow-41cd2d19"
    val rootSid = "d7c3cb54-dd90-4f3f-bbf6-e7898c425cff"
    val ownerGone = ListBuffer.empty[(String, String)]
    val blockNotices = ListBuffer.empty[(String, String)]
    for
      ref <- system.spawn(sinkBehavior, "owner-liveness-a-sink")
      registry <- Ref.of[IO, Map[String, AgentRecord]](liveRows(ref, owner, rootSid))
      resources <- mkResources(system, root, registry)
      _ <- IO(MergeHealthGate.install((_: MergeQueue.Member) => MergeHealthGate.Verdict.Ok))
      _ <- IO(MergeQueue.OwnerGonePort.install((m, reason) => IO { ownerGone += ((m.delegateId, reason)) }))
      _ <- IO(MergeQueue.BlockNotifyPort.install((m, kind, reason) => IO { blockNotices += ((kind, reason)) }))
      // 盘上会话记录同时在场（作者实测形态：转录与 UI 件都在盘上）
      _ <- writeDelegateSessionOnDisk(owner)
      m = memberOf("nebf-41cd2d19", owner, repo)
      _ <- IO(MergeQueue.enqueue(m))
      sent <- mergeTurnEntries(owner)
      // 「另一面」症状：入队后核账本（no OPEN ledger entry after enqueue）
      verified <- MergeQueue.enqueueVerifiedIO(m)
    yield
      assertEquals(
        MergeQueue.find("nebf-41cd2d19").map(_.state),
        Some(MergeQueue.State.TurnGranted),
        s"a LIVE delegate must hold the merge window (owner-gone escalations: $ownerGone)"
      )
      assert(MergeQueue.find("nebf-41cd2d19").exists(_.open), "the ledger keeps an OPEN entry for the live delegate")
      assertEquals(ownerGone.toList, Nil, s"no OWNER-GONE may be produced for a live delegate (got: ${ownerGone.toList})")
      assertEquals(sent.size, 1, s"the merge-turn instruction landed in the owner's disk mailbox (got kinds: ${sent.map(_.kind)})")
      assert(verified, "the post-enqueue ledger verification must find the OPEN entry (second symptom)")
      assert(
        !blockNotices.exists(_._1 == "enqueue-failed"),
        s"no 'no OPEN ledger entry after enqueue' notice may be emitted (got: ${blockNotices.toList.map(_._1)})"
      )
  }

  // ── 用例 A2：判据本体——「索引里没有」不等于「会话没了」─────────────────────

  test("A2 · 盘上独有（索引里没有、也没有活体 actor）的 delegate 会话照样算活着 ⇒ 投得进去") {
    val system = ActorSystem("owner-liveness-a2")
    val root = os.temp.dir()
    val repo = mkRepo(root / "repo", branch = "delegate/nebf-20056caf")
    val owner = "delegate-nebflow-20056caf"
    val ownerGone = ListBuffer.empty[(String, String)]
    for
      registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty) // 重启后：actor 表空
      _ <- {
        // 判据前置断言（改前红的**最小形态**）：这个会话**不在**索引里——
        // 旧的 `listSessions` 判据因此必然把它判死。
        val store = SessionStore(tempRoot / "sessions", tempRoot / "tasks")
        writeDelegateSessionOnDisk(owner) *> store.listSessions.map { indexed =>
          assert(
            !indexed.exists(_.id == owner),
            s"delegate session '$owner' must NOT be in the session index (that is precisely why the old oracle lied)"
          )
        }
      }
      resources <- mkResources(system, root, registry)
      _ <- IO(MergeHealthGate.install((_: MergeQueue.Member) => MergeHealthGate.Verdict.Ok))
      _ <- IO(MergeQueue.OwnerGonePort.install((m, reason) => IO { ownerGone += ((m.delegateId, reason)) }))
      _ <- IO(MergeQueue.enqueue(memberOf("nebf-20056caf", owner, repo)))
      sent <- mergeTurnEntries(owner)
    yield
      assertEquals(
        MergeQueue.find("nebf-20056caf").map(_.state),
        Some(MergeQueue.State.TurnGranted),
        s"a delegate with a session record on disk is ALIVE even though it is not in the index (escalations: $ownerGone)"
      )
      assertEquals(ownerGone.toList, Nil, "index-miss alone must never close a merge slot")
      assertEquals(sent.size, 1, "the merge instruction landed in the delegate's disk mailbox")
      assert(resources.sessionStore.getSessionMeta(owner).unsafeRunSync().isEmpty, "and it is still absent from the index")
  }

  // ── 用例 B：真消失——判据不许被放宽废掉 ────────────────────────────────────

  test("B · delegate 真消失（无活体记录、索引与盘上都没有会话记录）⇒ 仍正确关账 OWNER-GONE") {
    val system = ActorSystem("owner-liveness-b")
    val root = os.temp.dir()
    val repo = mkRepo(root / "repo", branch = "delegate/nebf-deadbeef")
    val owner = "delegate-nebflow-deadbeef"
    val ownerGone = ListBuffer.empty[(String, String)]
    for
      registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
      resources <- mkResources(system, root, registry)
      _ <- IO(MergeHealthGate.install((_: MergeQueue.Member) => MergeHealthGate.Verdict.Ok))
      _ <- IO(MergeQueue.OwnerGonePort.install((m, reason) => IO { ownerGone += ((m.delegateId, reason)) }))
      // 真消失的现实形态：会话被 deleteSession 清干净（索引行与盘上四件都不在）。
      gone <- resources.sessionStore.sessionRecordExists(owner)
      _ <- IO(assert(!gone, "precondition: the delegate session record really is absent"))
      _ <- IO(MergeQueue.enqueue(memberOf("nebf-deadbeef", owner, repo)))
      sent <- mergeTurnEntries(owner)
    yield
      assertEquals(
        MergeQueue.find("nebf-deadbeef").map(_.state),
        Some(MergeQueue.State.OwnerGone),
        s"a truly missing owner session still closes as owner-gone (escalations: $ownerGone)"
      )
      assert(!MergeQueue.find("nebf-deadbeef").exists(_.open), "the membership is closed — no open entry survives")
      assertEquals(ownerGone.size, 1, "exactly one OWNER-GONE escalation")
      assert(
        ownerGone.head._2.contains("parent-session-gone"),
        s"the escalation names the undeliverable cause: ${ownerGone.toList}"
      )
      assertEquals(sent, Nil, "no garbage mailbox entry is written for a session that no longer exists")
  }
end MergeQueueOwnerLivenessSpec
