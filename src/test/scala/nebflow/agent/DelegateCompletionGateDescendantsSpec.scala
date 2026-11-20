package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent}
import nebflow.actor.{AgentKind, AgentRecord}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  Message,
  MessageRole,
  PathUtil,
  StreamChunk,
  SubAgentTask,
  ThinkingConfig
}

import scala.concurrent.duration.*

/**
 * Completion-gate trusted-descendants spec (contract: the gate is three segments —
 * background jobs ① (existing) + trusted descendants ② a/b/c; any hit holds the
 * completion and delivers the last assistant text as a PROGRESS message instead of
 * impersonating a completion).
 *
 *  - G-a scope-map in-flight nodes hold the completion (②a, DelegateInflightGate).
 *  - G-b child task rows in flight (running / restarting) hold it (②b) — queued /
 *    starting / spawn-race windows are covered by the row.
 *  - G-c the session start lease held by a revival/continuation defers it (②c).
 *  - G-p progress idempotency: same turn = same identity (one entry); a new turn
 *    mints a new identity; keys never collide with terminal identities.
 *  - G-k kernel single-shot leg: ② is empty for a plain kernel delegate with no
 *    children — the Completed settles immediately (zero behavior change).
 */
class DelegateCompletionGateDescendantsSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-gate-descendants"
  private val originalRoot: os.Path = PathUtil.dataRoot

  private def resetRoot(): Unit =
    if os.exists(tempRoot) then
      try os.remove.all(tempRoot)
      catch case _: Throwable => ()
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  resetRoot()

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    resetRoot()

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private def mkStopOnStopProbe(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage:
        case stop: AgentCommand.Stop =>
          record.update(_ :+ stop).as(Behaviors.stopped)
        case other =>
          record.update(_ :+ other).as(loop)
    loop

  private def mkRecordingCmd(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
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
      sessionStore = SessionStore(os.temp.dir(), os.temp.dir()),
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

  private def mkDelegateRecord(id: String, parentSid: String): Unit =
    nebflow.core.delegate.DelegateRegistry.put(
      nebflow.core.delegate.DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = os.temp.dir().toString,
        project = None,
        taskId = None,
        parentSessionId = parentSid,
        title = s"gate $id",
        createdAt = System.currentTimeMillis(),
        roundNo = Some(1),
        roundId = Some("gen-gate-1")
      )
    )

  private def mkChildRow(delegateSid: String, childTaskId: String, status: String): SubAgentTask =
    SubAgentTask(
      taskId = childTaskId,
      parentSessionId = delegateSid,
      agentName = "Worker",
      prompt = "child",
      description = "child of the delegate",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def spawnSupervisor(
    system: ActorSystem,
    resources: SharedResources,
    parentRef: nebflow.actor.ActorRef[AgentCommand],
    childSid: String,
    parentSid: String,
    progressEnabled: Option[Boolean] = Some(true)
  ): IO[nebflow.actor.ActorRef[AgentEvent]] =
    for
      _ <- IO {
        progressEnabled.foreach(enabled =>
          os.write.over(PathUtil.configJsonWritePath(PathUtil.dataRoot), s"""{"delegateProgressNotifications":$enabled}""", createFolders = true)
        )
      }
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), s"child-$childSid")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "gate descendants test",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "do work",
          source = "delegate",
          completionCapMs = 60_000L
        ),
        s"supervisor-$childSid"
      )
    yield supervisor

  private def assistantMsg(text: String): Message =
    Message(MessageRole.Assistant, Left(text))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def mails(parentSid: String, kind: String): IO[List[nebflow.core.mail.SessionMailbox.Entry]] =
    nebflow.core.mail.SessionMailbox
      .scanPending(parentSid)
      .map(_.filter(_.kind == kind))
      .handleErrorWith(_ => IO.pure(Nil))

  test("default off versus explicit on: held text delivers zero versus one, terminal delivers in both") {
    def scenario(enabled: Option[Boolean], suffix: String): IO[(Int, Int)] =
      val system = ActorSystem(s"gate-switch-$suffix")
      val tmp = os.temp.dir()
      val childSid = s"delegate-switch-$suffix"
      for
        resources <- mkResources(system, tmp)
        parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
        parentSid = parent.id
        _ = mkDelegateRecord(childSid, parentSid)
        _ <- resources.subAgentTaskStore.recordTask(mkChildRow(childSid, s"child-$suffix", "running"))
        events <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- system.spawn(mkRecordingCmd(events), s"parent-$suffix")
        supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid, enabled)
        _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("held nonempty text")))
        _ <- IO.sleep(800.millis)
        progress <- mails(parentSid, "delegate-progress")
        _ <- resources.subAgentTaskStore.updateStatus(childSid, s"child-$suffix", "completed", completedAt = Some(System.currentTimeMillis()))
        _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("final report")))
        _ <- waitUntil(10.seconds)(mails(parentSid, "delegate-terminal").map(_.nonEmpty))
        terminal <- mails(parentSid, "delegate-terminal")
        _ <- system.stopAll
      yield (progress.size, terminal.size)
    for
      off <- scenario(None, "off")
      on <- scenario(Some(true), "on")
    yield
      assertEquals(off, (0, 1), "default off suppresses progress only")
      assertEquals(on, (1, 1), "negative control: explicit on delivers progress and terminal")
  }

  // ── G-a scope-map in-flight nodes ─────────────────────────────────────────

  test("G-a an in-flight scope-map node holds the completion; progress rides the parent mailbox; quiet map releases") {
    val system = ActorSystem("gate-desc-a")
    val tmp = os.temp.dir()
    val childSid = "delegate-gate-a1"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ = mkDelegateRecord(childSid, parentSid)
      _ <- resources.subAgentTaskStore.recordTask(
        mkChildRow(childSid, childSid, "running").copy(parentSessionId = parentSid)
      )
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-ga")
      // mount the delegate's scope host with ONE non-terminal node (②a signal)
      mounted <- nebflow.core.project.DelegateFlowHosts.mount(
        delegateId = childSid,
        project = None,
        baseWorkspace = tmp.toString,
        engineCwd = tmp.toString,
        system = system,
        resources = resources,
        wsSend = None
      )
      h = mounted.getOrElse(fail("scope host must mount"))
      _ <- h.store.mutate(st =>
        st.copy(nodes =
          st.nodes + ("impl" -> nebflow.core.project.NodeDef(
            id = "impl",
            name = "implement",
            agent = "nebflow",
            description = Some("still implementing"),
            in = Nil,
            status = nebflow.core.project.NodeLifecycle.Running,
            createdAt = System.currentTimeMillis() - 60_000L
          ))
        )
      )
      supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid)
      _ <- supervisor ! AgentEvent.Completed(
        childSid,
        List(assistantMsg("dispatched 8 nodes; implement is still running"))
      )
      _ <- IO.sleep(600.millis)
      heldTerminal <- mails(parentSid, "delegate-terminal")
      heldProgress <- mails(parentSid, "delegate-progress")
      heldTasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      // the node finishes; the wake-turn Completed now finds ② empty and settles
      _ <- h.store.mutate(st =>
        st.copy(nodes =
          st.nodes + ("impl" -> st.nodes("impl").copy(status = nebflow.core.project.NodeLifecycle.Completed))
        )
      )
      _ <- supervisor ! AgentEvent.Completed(
        childSid,
        List(
          assistantMsg("dispatched 8 nodes; implement is still running"),
          assistantMsg("all nodes done — final report")
        )
      )
      _ <- waitUntil(10.seconds)(mails(parentSid, "delegate-terminal").map(_.nonEmpty))
      terminal <- mails(parentSid, "delegate-terminal")
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(childSid)
      _ <- system.stopAll
    yield
      assertEquals(heldTerminal, Nil, "②a must hold: NO terminal while a scope node is in flight")
      assertEquals(heldProgress.size, 1, "the held turn's last text lands as exactly one progress mail")
      val p = heldProgress.head
      assert(p.identity.contains("gen-gate-1"), s"progress identity carries the roundId: ${p.identity}")
      assert(p.event.eventType == "progress")
      assert(
        p.event.payload.contains("implement is still running"),
        s"payload carries the mid-run text: ${p.event.payload}"
      )
      assertEquals(p.event.metadata("progress"), Some(true.asJson), "progress flag rides the metadata")
      assertEquals(
        heldTasks.find(_.taskId == childSid).map(_.status),
        Some("running"),
        "the held phase keeps the task row running (no fake completion)"
      )
      assertEquals(terminal.size, 1, "quiet map releases: exactly one terminal")
      assert(
        terminal.head.event.payload.contains("all nodes done"),
        s"the final text is the deliverable: ${terminal.head.event.payload}"
      )
    end for
  }

  // ── G-b child task rows in flight ─────────────────────────────────────────

  test("G-b a running child task row holds the completion (queued/starting race); row completion releases it") {
    val system = ActorSystem("gate-desc-b")
    val tmp = os.temp.dir()
    val childSid = "delegate-gate-b1"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ = mkDelegateRecord(childSid, parentSid)
      // the delegate's own row (parent = Nebula root) + a CHILD row (parent = the delegate)
      _ <- resources.subAgentTaskStore.recordTask(
        mkChildRow(childSid, childSid, "running").copy(parentSessionId = parentSid)
      )
      _ <- resources.subAgentTaskStore.recordTask(mkChildRow(childSid, "subchild-b1", "running"))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-gb")
      supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid)
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("spawned a helper, waiting for it")))
      _ <- IO.sleep(600.millis)
      heldTerminal <- mails(parentSid, "delegate-terminal")
      heldProgress <- mails(parentSid, "delegate-progress")
      // the child row reaches a terminal state; the re-check settles
      _ <- resources.subAgentTaskStore.updateStatus(
        childSid,
        "subchild-b1",
        "completed",
        completedAt = Some(System.currentTimeMillis())
      )
      _ <- supervisor ! AgentEvent.Completed(
        childSid,
        List(assistantMsg("spawned a helper, waiting for it"), assistantMsg("helper returned — final report"))
      )
      _ <- waitUntil(10.seconds)(mails(parentSid, "delegate-terminal").map(_.nonEmpty))
      terminal <- mails(parentSid, "delegate-terminal")
      _ <- system.stopAll
    yield
      assertEquals(heldTerminal, Nil, "②b must hold: a running child row means the work is not done")
      assertEquals(heldProgress.size, 1, "one progress mail for the held turn")
      assert(
        heldProgress.head.event.payload.contains("spawned a helper"),
        s"mid-run text rides the progress: ${heldProgress.head.event.payload}"
      )
      assertEquals(terminal.size, 1)
      assert(terminal.head.event.payload.contains("helper returned"))
    end for
  }

  // ── H-e bounded escalation of the trusted-descendant hold ─────────────────
  //
  // ②b has no force-terminal cap by design (a real descendant must never be
  // terminalized by wallclock). The failure mode it leaves open is the ORPHAN
  // row: in flight, no actor in the registry, no progress — held silently
  // forever. The escalation leg flags exactly those rows for manual review and
  // releases the completion; a parked child keeps its registry entry, so it is
  // neither flagged nor terminalized (the legit-hold contrast below).

  private val EscalationProp = "nebflow.delegate.descendantHoldEscalationMs"

  private def withEscalationMs[A](ms: String)(body: IO[A]): IO[A] =
    val prev = sys.props.remove(EscalationProp)
    val set = IO(sys.props(EscalationProp) = ms)
    val restore = IO {
      prev.fold(sys.props.remove(EscalationProp))(v => sys.props(EscalationProp) = v)
      ()
    }
    set *> body.guarantee(restore)

  test("H-e1 a stalled child row with no live actor escalates: flagged for review, completion released") {
    val system = ActorSystem("gate-desc-he1")
    val tmp = os.temp.dir()
    val childSid = "delegate-gate-he1"
    withEscalationMs("60000") {
      for
        resources <- mkResources(system, tmp)
        parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
        parentSid = parent.id
        _ = mkDelegateRecord(childSid, parentSid)
        _ <- resources.subAgentTaskStore.recordTask(
          mkChildRow(childSid, childSid, "running").copy(parentSessionId = parentSid)
        )
        // the CHILD row: in flight on disk, absent from the registry (no actor), and its
        // status has not moved for 120s — well past the 60s window.
        _ <- resources.subAgentTaskStore.recordTask(
          mkChildRow(childSid, "subchild-he1", "running").copy(spawnedAt = System.currentTimeMillis() - 120_000L)
        )
        parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-he1")
        supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid)
        _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("spawned a helper; it is still running")))
        _ <- waitUntil(10.seconds)(mails(parentSid, "delegate-terminal").map(_.nonEmpty))
        terminal <- mails(parentSid, "delegate-terminal")
        rows <- resources.subAgentTaskStore.loadTasks(childSid)
        parentRows <- resources.subAgentTaskStore.loadTasks(parentSid)
        _ <- system.stopAll
      yield
        assertEquals(terminal.size, 1, "escalation releases the completion instead of holding it silently forever")
        val child = rows.find(_.taskId == "subchild-he1").getOrElse(fail("child row missing"))
        assertEquals(
          child.suspectedLostTerminal,
          Some(true),
          "the stalled orphan is flagged for manual review (flag ≠ terminalization)"
        )
        assertEquals(child.status, "running", "flagging must not terminalize the row")
        assertEquals(
          parentRows.find(_.taskId == childSid).map(_.status),
          Some("completed"),
          "the delegate's own row settles (the completion was released, not held)"
        )
      end for
    }
  }

  test("H-e2 a child row cancelled before the gate read neither holds the completion nor gets flagged") {
    val system = ActorSystem("gate-desc-he2")
    val tmp = os.temp.dir()
    val childSid = "delegate-gate-he2"
    withEscalationMs("60000") {
      for
        resources <- mkResources(system, tmp)
        parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
        parentSid = parent.id
        _ = mkDelegateRecord(childSid, parentSid)
        _ <- resources.subAgentTaskStore.recordTask(
          mkChildRow(childSid, "subchild-he2", "running").copy(spawnedAt = System.currentTimeMillis() - 120_000L)
        )
        // cancellation wins the race: the row leaves the in-flight set before the gate reads it
        cancelled <- resources.subAgentTaskStore.cancelRunningForParent(childSid, "parent deleted")
        parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-he2")
        supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid)
        _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("helper cancelled underneath me")))
        _ <- waitUntil(10.seconds)(mails(parentSid, "delegate-terminal").map(_.nonEmpty))
        terminal <- mails(parentSid, "delegate-terminal")
        rows <- resources.subAgentTaskStore.loadTasks(childSid)
        _ <- system.stopAll
      yield
        assertEquals(cancelled, List("subchild-he2"), "the cancel leg cancelled the child row")
        assertEquals(terminal.size, 1, "a cancelled child is not in flight — the completion must settle")
        val child = rows.find(_.taskId == "subchild-he2").getOrElse(fail("child row missing"))
        assertEquals(child.status, "cancelled", "escalation never overwrites the cancellation")
        assertEquals(child.suspectedLostTerminal, None, "a cancelled row is not a candidate for flagging")
      end for
    }
  }

  test("H-e3 a legitimate parked child (live registry row) stays a hold and is never flagged") {
    val system = ActorSystem("gate-desc-he3")
    val tmp = os.temp.dir()
    val childSid = "delegate-gate-he3"
    withEscalationMs("60000") {
      for
        resources <- mkResources(system, tmp)
        parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
        parentSid = parent.id
        _ = mkDelegateRecord(childSid, parentSid)
        // same stalled-on-paper row as H-e1, but the actor IS parked and registered
        _ <- resources.subAgentTaskStore.recordTask(
          mkChildRow(childSid, "subchild-he3", "running").copy(spawnedAt = System.currentTimeMillis() - 120_000L)
        )
        parkedEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        parkedRef <- system.spawn(mkRecordingCmd(parkedEvents), "parked-child-he3")
        _ <- resources.agentRegistry.update(
          _.updated(
            "subchild-he3",
            AgentRecord(
              sessionId = "subchild-he3",
              ref = parkedRef,
              kind = AgentKind.Delegate,
              rootSessionId = childSid,
              parentSessionId = childSid
            )
          )
        )
        parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-he3")
        supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid)
        _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("helper is parked on a question")))
        _ <- IO.sleep(800.millis)
        heldTerminal <- mails(parentSid, "delegate-terminal")
        heldProgress <- mails(parentSid, "delegate-progress")
        rows <- resources.subAgentTaskStore.loadTasks(childSid)
        _ <- system.stopAll
      yield
        assertEquals(heldTerminal, Nil, "a parked child with a live actor is a legitimate hold — no release")
        assertEquals(heldProgress.size, 1, "the held turn still reports progress")
        val child = rows.find(_.taskId == "subchild-he3").getOrElse(fail("child row missing"))
        assertEquals(child.suspectedLostTerminal, None, "escalation must never flag a legitimately parked child")
        assertEquals(child.status, "running", "the parked child keeps its in-flight row")
      end for
    }
  }

  // ── G-c session start lease ───────────────────────────────────────────────

  test("G-c the session start lease held by a revival defers the completion; release lets it settle") {
    val system = ActorSystem("gate-desc-c")
    val tmp = os.temp.dir()
    val childSid = "delegate-gate-c1"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ = mkDelegateRecord(childSid, parentSid)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-gc")
      supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid)
      // a revival/continuation holds the start lease (②c)
      holder <- nebflow.core.delegate.SessionStartLease.exclusive(childSid)(IO.never).start
      _ <- waitUntil(5.seconds)(IO(nebflow.core.delegate.SessionStartLease.isHeld(childSid)))
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("turn done while being revived")))
      _ <- IO.sleep(600.millis)
      heldTerminal <- mails(parentSid, "delegate-terminal")
      heldProgress <- mails(parentSid, "delegate-progress")
      _ <- holder.cancel
      _ <- waitUntil(5.seconds)(IO(!nebflow.core.delegate.SessionStartLease.isHeld(childSid)))
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("final report after the lease released")))
      _ <- waitUntil(10.seconds)(mails(parentSid, "delegate-terminal").map(_.nonEmpty))
      terminal <- mails(parentSid, "delegate-terminal")
      _ <- system.stopAll
    yield
      assertEquals(heldTerminal, Nil, "②c must defer: a start lease in flight means the round is still opening")
      assertEquals(heldProgress.size, 1, "the deferred turn still reports progress (nothing swallowed)")
      assertEquals(terminal.size, 1, "lease released: the completion settles")
    end for
  }

  // ── G-p progress idempotency ──────────────────────────────────────────────

  test("G-p progress identities: same turn replays dedupe; a new turn mints a new key; terminal keys never collide") {
    val system = ActorSystem("gate-desc-p")
    val tmp = os.temp.dir()
    val childSid = "delegate-gate-p1"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ = mkDelegateRecord(childSid, parentSid)
      _ <- resources.subAgentTaskStore.recordTask(mkChildRow(childSid, "subchild-p1", "running"))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-gp")
      supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid)
      turnOne = List(assistantMsg("turn one: waiting"))
      _ <- supervisor ! AgentEvent.Completed(childSid, turnOne)
      _ <- supervisor ! AgentEvent.Completed(childSid, turnOne) // same-turn replay
      _ <- waitUntil(5.seconds)(mails(parentSid, "delegate-progress").map(_.size >= 1))
      _ <- IO.sleep(300.millis)
      afterReplay <- mails(parentSid, "delegate-progress")
      // a NEW turn (extra assistant message => turnSeq advances)
      _ <- supervisor ! AgentEvent.Completed(childSid, turnOne :+ assistantMsg("turn two: still waiting"))
      _ <- waitUntil(5.seconds)(mails(parentSid, "delegate-progress").map(_.size >= 2))
      both <- mails(parentSid, "delegate-progress")
      _ <- system.stopAll
    yield
      assertEquals(afterReplay.size, 1, "same-turn replay dedupes onto the same identity")
      assertEquals(both.size, 2, "a new turn mints a new progress identity")
      val ids = both.map(_.identity).distinct
      assertEquals(ids.size, 2)
      assert(
        ids.forall(_.startsWith(s"progress-$childSid-gen-gate-1-")),
        s"identities = progress-<id>-<roundId>-<turnSeq>: $ids"
      )
      assert(!ids.exists(_.contains("#")), "progress keys never share the terminal <id>#<gen> identity shape")
    end for
  }

  // ── G-k kernel single-shot leg zero change ────────────────────────────────

  test("G-k kernel leg with no descendants settles immediately (② always empty — zero behavior change)") {
    val system = ActorSystem("gate-desc-k")
    val tmp = os.temp.dir()
    val childSid = "delegate-gate-k1"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ = mkDelegateRecord(childSid, parentSid)
      // the delegate's OWN row (parent = root) must NOT count as a child
      _ <- resources.subAgentTaskStore.recordTask(
        mkChildRow(childSid, childSid, "running").copy(parentSessionId = parentSid)
      )
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-gk")
      supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid)
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("kernel one-shot done")))
      _ <- waitUntil(10.seconds)(mails(parentSid, "delegate-terminal").map(_.nonEmpty))
      terminal <- mails(parentSid, "delegate-terminal")
      progress <- mails(parentSid, "delegate-progress")
      _ <- system.stopAll
    yield
      assertEquals(terminal.size, 1, "no descendants: immediate settlement (kernel leg unchanged)")
      assertEquals(progress, Nil, "no hold => no progress mail")
    end for
  }

end DelegateCompletionGateDescendantsSpec
