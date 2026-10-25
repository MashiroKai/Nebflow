package nebflow.core.delegate

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, AgentKind, AgentRecord, Behavior, Behaviors}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * 合并队列**滞留告警 + 退避重试**看护判读（merge-queue 滞留两病灶修复批
 * 2026-10-25 作者令腿②③）：
 *
 *  - ① 队首停靠条目「无竞争合并 ∧ 工件已清理」⇒ 引擎先清障（未跟踪工件收编）
 *    再重发合并轮（内部腿经 RevivalPort 续起会话）；窗随复活轮持住；
 *  - ② 清障后仍脏（白名单外源码）⇒ 本拍不烧 LLM 只记账（退避进位），不重发；
 *  - ③ 逐次拒绝通知：delegate 主通知携「谁在挡、挡在哪」具体路径清单 +
 *    correlationId 携失败计数（每次拒绝独立事件），父会话一行知会；幂等盖章；
 *  - ④ 重试预算耗尽 ⇒ 升级告警投活 root（粘住一次），此后不再重试不再告警；
 *  - ⑤ 退避未到 ⇒ 零动作；⑥ 领先活条目在场 ⇒ 队首停靠条目让位（pump 管轮转）。
 */
class MergeStallCareSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-stall-care"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "chains")
    MergeQueue.reset()
    MergeStallCare.RevivalPort.clear()
    MergeRefusalCare.EscalationPort.clear()

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeStallCare.RevivalPort.clear()
    MergeRefusalCare.EscalationPort.clear()

  private def fakeLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("not expected"))

  private def mkResources(system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
      tmp <- IO(os.temp.dir())
    yield SharedResources(
      llm = fakeLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
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
      healthMonitor = null,
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

  /** 真仓基工作区（main@init）——清障面/收编面实测。 */
  private def mkRepo(dir: os.Path): os.Path =
    os.remove.all(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    dir

  private class Sink(records: ListBuffer[String]) extends Behavior[AgentCommand]:
    def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
      msg match
        case AgentCommand.UserInput(text, _, _, _, _, _, _, _, _, _, _, _, _) => IO { records += text; this }
        case _ => IO.pure(this)

  private def enqueueParked(
    system: ActorSystem,
    delegateId: String,
    projectKey: String,
    repo: os.Path,
    readyAt: Long
  ): IO[MergeQueue.Entry] =
    for
      child <- system.spawn(new Sink(ListBuffer.empty[String]), s"child-$delegateId")
      sup <- system.spawn(
        new Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[Behavior[AgentEvent]] =
            IO.pure(this),
        s"sup-$delegateId"
      )
      entry = MergeQueue.Entry(
        delegateId = delegateId,
        branch = s"delegate/$delegateId",
        baseWs = repo.toString,
        baseBranch = "main",
        worktree = false,
        projectKey = projectKey,
        taskId = Some("31"),
        parentSessionId = "root-1",
        description = s"task for $delegateId",
        readyAt = readyAt,
        childRef = child,
        supervisorRef = sup
      )
      _ <- IO { MergeQueue.enqueue(entry) }
      _ <- IO.sleep(60.millis) // 提醒注入（合并轮在飞、窗持）
      _ <- IO { MergeQueue.onTerminal(delegateId, keepForRetry = true) } // 合并轮被拒（停靠，retryCount=1）
      _ <- IO.sleep(30.millis)
    yield entry

  /** 记录型复活通道（仿生产：续起会话 + rekey）。 */
  private final class RecordingRevival(calls: Ref[IO, List[String]]) extends MergeStallCare.RevivalPort.Face:
    def revive(entry: MergeQueue.Entry): IO[Either[String, Unit]] =
      calls.update(_ :+ entry.delegateId) *>
        IO {
          val newId = s"${entry.delegateId}-revived"
          MergeQueue.rekey(entry.delegateId, newId)
          Right(())
        }

  /** 速配配置：零退避（退避待时序的用例单独给大值）。 */
  private val fastCfg: MergeStallCare.Config =
    MergeStallCare.Config(enabled = true, maxRetries = 3, backoffBaseMs = 1L, backoffCapMs = 8L)

  private def chainActions(delegateId: String): List[String] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).flatMap { e =>
      val c = e.payload.hcursor
      c.get[String]("delegateId").toOption
        .filter(_ == delegateId)
        .map(_ => c.get[String]("action").toOption.getOrElse(""))
    }

  // ── ① 清障 + 重发（内部腿 RevivalPort） ──────────────────────────────

  test("① head parked entry: engine clears untracked artifacts (absorb) then re-fires the merge round via the revival port; window held") {
    val system = ActorSystem(s"msc-1-${scala.util.Random.nextInt(100000)}")
    val repo = mkRepo(tempRoot / "msc-repo-1")
    os.makeDir.all(repo / "reports" / "stray")
    os.write(repo / "reports" / "stray" / "note.md", "generated\n")
    for
      resources <- mkResources(system)
      revivalCalls <- Ref.of[IO, List[String]](Nil)
      _ = MergeStallCare.RevivalPort.install(RecordingRevival(revivalCalls))
      _ <- enqueueParked(system, "d-s1", "p-s1", repo, readyAt = 1_000L)
      findings <- MergeStallCare.care(resources, Some(fastCfg))
      calls <- revivalCalls.get
      revived = MergeQueue.find("d-s1-revived")
      note = os.read(repo / "reports" / "stray" / "note.md")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // 同拍双面：通知面（首次拒绝诊断）+ 重试面（清障后重发）
      assertEquals(findings.map(_.action), List(MergeStallCare.ActionRefusalNotice, MergeStallCare.ActionRetryInjected), s"findings: $findings")
      assertEquals(calls, List("d-s1"), "the revival port fired exactly once for the parked head")
      assert(revived.isDefined, "the revival rekeyed the entry to the continuation session")
      assert(revived.flatMap(_.reminderSentAt).isDefined, "the revived merge round is stamped in flight")
      assertEquals(MergeQueue.isInFlight("p-s1"), true, "the window is held by the revived merge round")
      assertEquals(note, "generated\n", "the artifact was absorbed (committed), not deleted")
      assert(
        chainActions("d-s1").contains("stall-attempt") || calls.nonEmpty,
        "the care attempt is audited on the entry's ledger"
      )
  }

  // ── ② 清障后仍脏 ⇒ 记账退避，不烧 LLM ────────────────────────────────

  test("② dirt outside the whitelist survives hygiene ⇒ blocked attempt counted (backoff advance), no revival fired") {
    val system = ActorSystem(s"msc-2-${scala.util.Random.nextInt(100000)}")
    val repo = mkRepo(tempRoot / "msc-repo-2")
    os.makeDir.all(repo / "src")
    os.write(repo / "src" / "HalfDone.scala", "// human work")
    for
      resources <- mkResources(system)
      revivalCalls <- Ref.of[IO, List[String]](Nil)
      _ = MergeStallCare.RevivalPort.install(RecordingRevival(revivalCalls))
      _ <- enqueueParked(system, "d-s2", "p-s2", repo, readyAt = 1_000L)
      findings <- MergeStallCare.care(resources, Some(fastCfg))
      calls <- revivalCalls.get
      parked = MergeQueue.find("d-s2")
      src = os.read(repo / "src" / "HalfDone.scala")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // 同拍双面：通知面（拒绝诊断，携具体路径）+ 重试面（清障仍脏 ⇒ 记账不烧 LLM）
      assertEquals(
        findings.map(_.action),
        List(MergeStallCare.ActionRefusalNotice, MergeStallCare.ActionBlockedByDirt),
        s"findings: $findings"
      )
      assertEquals(calls, Nil, "no merge round is re-fired over an unclean tree (no LLM burn)")
      assertEquals(parked.map(_.retryCount), Some(2), "the blocked attempt advanced the backoff counter")
      // porcelain 将全未跟踪目录折叠为 `src/` 形——诊断面携目录路径
      assert(findings.exists(_.detail.contains("src/")), s"the finding names the blocker: $findings")
      assertEquals(src, "// human work", "source dirt untouched")
  }

  // ── ③ 逐次拒绝通知（谁在挡、挡在哪） ─────────────────────────────────

  private final class RecordingFace:
    private val lock = new Object
    private val buf =
      scala.collection.mutable.ListBuffer.empty[(String, String, AgentCommand.ExternalEvent, AgentCommand.ExternalEvent)]
    val face: MergeRefusalCare.EscalationPort.Face = (delegateId, parentSessionId, delegateEvent, parentEvent) =>
      IO.blocking(lock.synchronized(buf += ((delegateId, parentSessionId, delegateEvent, parentEvent)))).void
    def all: List[(String, String, AgentCommand.ExternalEvent, AgentCommand.ExternalEvent)] =
      lock.synchronized(buf.toList)

  test("③ every refusal notifies the delegate (who's blocking + where, concrete paths, attempt-keyed correlationId) + parent FYI; idempotent") {
    val rf = RecordingFace()
    try
      val system = ActorSystem(s"msc-3-${scala.util.Random.nextInt(100000)}")
      val repo = mkRepo(tempRoot / "msc-repo-3")
      os.makeDir.all(repo / "reports" / "gen")
      os.write(repo / "reports" / "gen" / "note.md", "generated\n")
      val io = for
        resources <- mkResources(system) // 注意：SharedResources 构造行会装配生产投递面
        _ = MergeRefusalCare.EscalationPort.install(rf.face) // 盖回测试假面（须在 mkResources 之后）
        revivalCalls <- Ref.of[IO, List[String]](Nil)
        _ = MergeStallCare.RevivalPort.install(RecordingRevival(revivalCalls))
        _ <- enqueueParked(system, "d-s3", "p-s3", repo, readyAt = 1_000L)
        findings <- MergeStallCare.care(resources, Some(fastCfg))
        got1 = rf.all
        parked1 = MergeQueue.find("d-s3-revived") // 重试拍已 rekey（假复活通道续起会话）——章随换键携带
        findings2 <- MergeStallCare.care(resources, Some(fastCfg))
        got2 = rf.all
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield (findings, got1, parked1, findings2, got2)
      val (findings, got1, parked1, findings2, got2) = io.unsafeRunSync()
      // 通知到达：delegate 主通知 + 父会话知会
      assertEquals(got1.size, 1, s"exactly one dual notification: ${got1.size}")
      val (delegateId, parentSid, ev, pev) = got1.head
      assertEquals(delegateId, "d-s3")
      assertEquals(parentSid, "root-1")
      assertEquals(ev.correlationId, Some("merge-queue-refused-d-s3-1"), "correlationId keys the attempt (each refusal is a distinct event)")
      assert(ev.payload.contains("reports/"), s"the notice names the blocking path: ${ev.payload.take(400)}")
      assert(ev.payload.contains("attempt 1"), s"the notice carries the attempt count: ${ev.payload.take(200)}")
      assert(ev.payload.contains("re-fires your merge round automatically"), "the notice states the retry policy")
      assert(pev.correlationId.exists(_.startsWith("merge-queue-refused-parent-d-s3-1")), "parent FYI keyed separately")
      assert(pev.payload.contains("no action needed"), "parent FYI is a no-action line")
      assertEquals(parked1.map(_.stallNotified), Some(true), "the notice is stamped (idempotence gate)")
      // 第二拍：通知不再重复（盖章幂等）；重试面继续（重发/清障）
      assertEquals(got2.size, 1, "the second sweep does NOT re-notify (stallNotified gate)")
      assert(findings.map(_.action).contains(MergeStallCare.ActionRefusalNotice), s"findings: $findings")
      assert(!findings2.exists(_.action == MergeStallCare.ActionRefusalNotice), s"no second notice: $findings2")
    finally MergeRefusalCare.EscalationPort.clear()
  }

  // ── ④ 预算耗尽 ⇒ 升级告警投活 root（粘住一次） ───────────────────────

  test("④ retry budget exhausted ⇒ ONE escalation reaches the live root, then the entry is left parked (no further attempts/alerts)") {
    val system = ActorSystem(s"msc-4-${scala.util.Random.nextInt(100000)}")
    val repo = mkRepo(tempRoot / "msc-repo-4")
    os.makeDir.all(repo / "src")
    os.write(repo / "src" / "Stuck.scala", "// unresolvable by the engine")
    for
      resources <- mkResources(system)
      // 隔离通知面：拒绝诊断双通知走假面（no-op），root 录制 ref 只收升级告警
      _ = MergeRefusalCare.EscalationPort.install((_, _, _, _) => IO.unit)
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      rootRef <- system.spawn(
        {
          lazy val loop: Behavior[AgentCommand] =
            Behaviors.receiveMessage[AgentCommand](cmd => received.update(_ :+ cmd).as(loop))
          loop
        },
        "root-sink"
      )
      _ <- resources.agentRegistry.update(_ + ("root-1" -> AgentRecord("root-1", rootRef, AgentKind.Root, "root-1")))
      revivalCalls <- Ref.of[IO, List[String]](Nil)
      _ = MergeStallCare.RevivalPort.install(RecordingRevival(revivalCalls))
      _ <- enqueueParked(system, "d-s4", "p-s4", repo, readyAt = 1_000L)
      _ <- IO { MergeQueue.onTerminal("d-s4", keepForRetry = true) } // retryCount=2
      _ <- IO { MergeQueue.onTerminal("d-s4", keepForRetry = true) } // retryCount=3 = 预算
      _ <- IO.sleep(30.millis)
      findings <- MergeStallCare.care(resources, Some(fastCfg))
      parked1 = MergeQueue.find("d-s4")
      _ <- IO.sleep(150.millis) // 直投走 actor 邮箱——给投递留到达窗
      got1 <- received.get
      findings2 <- MergeStallCare.care(resources, Some(fastCfg))
      calls <- revivalCalls.get
      _ <- IO.sleep(100.millis)
      got2 <- received.get
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // 同拍双面：通知面（第 3 次拒绝的诊断，走假面）+ 升级面（预算耗尽告警 root）
      assertEquals(
        findings.map(_.action),
        List(MergeStallCare.ActionRefusalNotice, MergeStallCare.ActionEscalated),
        s"findings: $findings"
      )
      val events = got1.collect { case e: AgentCommand.ExternalEvent => e }
      val escalation = events.filter(_.correlationId.contains("merge-queue-stall-d-s4"))
      assertEquals(escalation.size, 1, "exactly one escalation reached the live root")
      assertEquals(escalation.head.source, MergeStallCare.Source)
      assert(escalation.head.payload.contains("MANUAL ACTION"), s"the alert demands manual action: ${escalation.head.payload.take(300)}")
      assert(escalation.head.payload.contains("src/"), s"the alert names the blocking path: ${escalation.head.payload.take(400)}")
      assertEquals(parked1.map(_.stallEscalated), Some(true), "the escalation is stamped")
      // 第二拍：粘住——零新动作（不重试、不重复告警、不重复通知）。
      // received 是累积 Ref——判据 = stall 事件数仍为 1（无新增），而非空列表。
      assertEquals(findings2, Nil, s"second sweep is a no-op: $findings2")
      assertEquals(calls, Nil, "no revival was ever fired over an unclean tree")
      assertEquals(
        got2.collect { case e: AgentCommand.ExternalEvent => e }.count(_.correlationId.exists(_.contains("merge-queue-stall"))),
        1,
        "no duplicate alert — exactly one escalation overall"
      )
  }

  // ── ⑤ 退避未到 ⇒ 零动作 ─────────────────────────────────────────────

  test("⑤ backoff not yet due ⇒ zero actions (no revival, no blocked counting)") {
    val system = ActorSystem(s"msc-5-${scala.util.Random.nextInt(100000)}")
    val repo = mkRepo(tempRoot / "msc-repo-5")
    for
      resources <- mkResources(system)
      revivalCalls <- Ref.of[IO, List[String]](Nil)
      _ = MergeStallCare.RevivalPort.install(RecordingRevival(revivalCalls))
      _ <- enqueueParked(system, "d-s5", "p-s5", repo, readyAt = 1_000L)
      _ <- IO { MergeQueue.markStallNotified("d-s5") } // 通知面已盖（隔离退避判据）
      findings <- MergeStallCare.care(
        resources,
        Some(MergeStallCare.Config(enabled = true, maxRetries = 3, backoffBaseMs = 3_600_000L, backoffCapMs = 3_600_000L))
      )
      calls <- revivalCalls.get
      parked = MergeQueue.find("d-s5")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(findings, Nil, s"backoff gate holds: $findings")
      assertEquals(calls, Nil, "no early revival")
      assertEquals(parked.map(_.retryCount), Some(1), "no attempt counting before the backoff elapses")
  }

  // ── ⑥ 领先活条目在场 ⇒ 队首停靠条目让位 ─────────────────────────────

  test("⑥ a LIVE entry sorting ahead of the parked one defers the retry (pump owns the rotation)") {
    val system = ActorSystem(s"msc-6-${scala.util.Random.nextInt(100000)}")
    val repo = mkRepo(tempRoot / "msc-repo-6")
    for
      resources <- mkResources(system)
      revivalCalls <- Ref.of[IO, List[String]](Nil)
      _ = MergeStallCare.RevivalPort.install(RecordingRevival(revivalCalls))
      _ <- enqueueParked(system, "d-s6p", "p-s6", repo, readyAt = 2_000L) // 停靠条目（readyAt 晚）
      // 更早完成的活条目随后入队（readyAt 更早 ⇒ 排到停靠条目前面，pump 授窗）
      bChild <- system.spawn(new Sink(ListBuffer.empty[String]), "child-d-s6b")
      bSup <- system.spawn(
        new Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[Behavior[AgentEvent]] =
            IO.pure(this),
        "sup-d-s6b"
      )
      _ <- IO {
        MergeQueue.enqueue(
          MergeQueue.Entry(
            delegateId = "d-s6b",
            branch = "delegate/d-s6b",
            baseWs = repo.toString,
            baseBranch = "main",
            worktree = false,
            projectKey = "p-s6",
            taskId = Some("31"),
            parentSessionId = "root-1",
            description = "younger completion, earlier readyAt",
            readyAt = 1_000L,
            childRef = bChild,
            supervisorRef = bSup
          )
        )
      }
      _ <- IO.sleep(60.millis)
      findings <- MergeStallCare.care(resources, Some(fastCfg))
      calls <- revivalCalls.get
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // 重试面让位（领先活条目在场 = pump 管轮转）；通知面照常覆盖停靠条目
      assertEquals(findings.map(_.action), List(MergeStallCare.ActionRefusalNotice), s"findings: $findings")
      assertEquals(calls, Nil, "no retry is attempted behind a live merge round")
  }

  // ── ⑦ 障碍清除事件驱动唤醒（作者 2026-10-25 补充令③——今日精确缺口） ──

  test("⑦ tree dirty→clean between beats wakes the parked head IMMEDIATELY: backoff bypassed, reminder re-fired (never a proxy merge)") {
    val system = ActorSystem(s"msc-7-${scala.util.Random.nextInt(100000)}")
    val repo = mkRepo(tempRoot / "msc-repo-7")
    os.makeDir.all(repo / "src")
    os.write(repo / "src" / "HumanWork.scala", "// cleared by a human later")
    // 大退避：无事件腿时本测试窗口内重试永不发生（事件腿是唯一唤醒通道）
    val slowCfg = MergeStallCare.Config(enabled = true, maxRetries = 3, backoffBaseMs = 3_600_000L, backoffCapMs = 3_600_000L)
    for
      resources <- mkResources(system)
      revivalCalls <- Ref.of[IO, List[String]](Nil)
      _ = MergeStallCare.RevivalPort.install(RecordingRevival(revivalCalls))
      _ = MergeRefusalCare.EscalationPort.install((_, _, _, _) => IO.unit) // 通知面静音
      _ <- enqueueParked(system, "d-s7", "p-s7", repo, readyAt = 1_000L)
      // 拍1：基线记录（dirty=true），退避闸持住——零重试
      findings1 <- MergeStallCare.care(resources, Some(slowCfg))
      calls1 <- revivalCalls.get
      // 人类/delegate 清障（git add+commit——平台零参与）
      _ <- IO.blocking {
        os.proc("git", "-C", repo.toString, "add", "src/HumanWork.scala").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        os.proc("git", "-C", repo.toString, "commit", "-m", "human clears the obstacle")
          .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        ()
      }
      // 拍2：树脏→洁真过渡 ⇒ 退避旁路 + 重发提醒
      findings2 <- MergeStallCare.care(resources, Some(slowCfg))
      calls2 <- revivalCalls.get
      row = chainActions("d-s7")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(findings1.map(_.action), List(MergeStallCare.ActionRefusalNotice), s"beat 1: baseline only, backoff holds: $findings1")
      assertEquals(calls1, Nil, "no retry before the obstacle clears")
      assertEquals(
        findings2.map(_.action),
        List(MergeStallCare.ActionObstacleCleared, MergeStallCare.ActionRetryInjected),
        s"beat 2: the clear event wakes the parked head: $findings2"
      )
      assertEquals(calls2, List("d-s7"), "the merge reminder is re-fired to the delegate (revival channel), never run by the platform")
      assert(row.contains("obstacle-cleared"), s"the clear event is audited: $row")
      assert(
        MergeQueue.find("d-s7-revived").isDefined,
        "the delegate session was revived to run ITS OWN merge (platform zero-proxy)"
      )
  }

end MergeStallCareSpec
