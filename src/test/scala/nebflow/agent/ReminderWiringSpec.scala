package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.{FileTaskStore, TaskCreateInput}
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.core.task.{FileTaskStore, TaskCreateInput}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}
import fs2.Stream

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*

/**
 * Reminder refactor (2026-08-20 user ruling) — WIRING pins on a real
 * AgentActor (Nebula def) + mock LLM. The unit specs cover
 * SystemReminders.collectAllIO semantics; these tests prove AgentCore's
 * pipeLlmCall actually passes the gating:
 *   ① system-event turns (ExternalEvent injection) inject ZERO time/tasks
 *   ② real-user turns inject time but NOT tasks for Nebula (task redesign
 *      2026-08-30: task injection moved to team sessions; Nebula has no
 *      task tools and its session is not team-registered)
 *   D6 (revised): systemStable NO LONGER carries the Task List Protocol
 *      section for Nebula (condition = agentCategory == "team")
 */
class ReminderWiringSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private class CaptureLlm(requests: Ref[IO, List[LlmRequest]]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)).drain ++
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks-ui"),
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

  /** Pin the Nebula def on disk (empty agents dir falls back to Seeds.Nebula
    * with a different toolset — harness trap, see memory). */
  private def seedNebula(tmp: os.Path): Unit =
    val dir = tmp / "agents" / "Nebula"
    os.makeDir.all(dir)
    os.write.over(
      dir / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"wiring root","tools":["Read"]}"""
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 100.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def textOf(req: LlmRequest): String =
    req.messages.map(_.textContent).mkString("\n")

  test("① system-event turn injects zero time/tasks; ② real-user turn injects time but Nebula gets NO tasks (task redesign)"):
    val system = ActorSystem("reminder-wiring")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "wiring-nebula"
      // one active task for this session → real-user turns carry the list
      FileTaskStore.create(sid, TaskCreateInput(subject = "wiring task", description = "d")).unsafeRunSync()
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
        nebulaDef = AgentDef(
          name = "Nebula",
          description = "root under test",
          tools = List("Read"),
          systemPrompt = ""
        )
        actorRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("reminder-wiring")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None))
        )
        // ── ① ExternalEvent injection = system-event turn ──
        _ <- actorRef ! AgentCommand.ExternalEvent(
          source = "delegate",
          eventType = "completed",
          payload = "[RESULT] sub-agent finished"
        )
        _ <- waitUntil(15.seconds)(requests.get.map(_.size >= 1))
        req1 <- requests.get.map(_.head)
        // ── ② real user turn (clientMessageId → source=None) ──
        _ <- actorRef ! AgentCommand.UserInput("hello from the user", None, Some("cmid-1"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.size >= 2))
        req2 <- requests.get.map(_.apply(1))
        // ── ④ next real-user turn, tasks unchanged ──
        _ <- actorRef ! AgentCommand.UserInput("still here", None, Some("cmid-2"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.size >= 3))
        req3 <- requests.get.map(_.apply(2))
      yield
        // ① system-event turn: no time, no tasks (even though the actor IS
        // Nebula with an active task in the store)
        assert(!textOf(req1).contains("Current time"), s"system-event turn must not inject time:\n${textOf(req1)}")
        assert(!textOf(req1).contains("Current Tasks"), s"system-event turn must not inject tasks:\n${textOf(req1)}")
        assert(!textOf(req1).contains("Tasks unchanged"), s"system-event turn must not inject tasks:\n${textOf(req1)}")
        // D6 revised (task redesign 2026-08-30): Nebula's systemStable must
        // NOT carry the protocol section — it is team-category-only now
        req1.systemStable.foreach { s =>
          assert(!s.contains("## Task List Protocol"), s"Nebula systemStable must not carry the protocol section (team-only):\n$s")
        }
        // ② real-user turn: time injected, but NO tasks for Nebula — task
        // injection moved to team-registered sessions; Nebula has no task
        // tools and its session is not team-scoped
        assert(textOf(req2).contains("Current time"), s"real-user turn must inject time:\n${textOf(req2)}")
        assert(!textOf(req2).contains("## Current Tasks"), s"task redesign: Nebula no longer gets the task reminder:\n${textOf(req2)}")
        assert(!textOf(req2).contains("wiring task"), s"task redesign: Nebula must not see task lines:\n${textOf(req2)}")
        // ③ next real-user turn: still no tasks
        assert(textOf(req3).contains("Current time"), s"real-user turn must inject time:\n${textOf(req3)}")
        assert(!textOf(req3).contains("## Current Tasks"), s"task redesign: Nebula no longer gets the task reminder:\n${textOf(req3)}")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

  test("② unregistered Manager session gets zero tasks even on real-user turns"):
    val system = ActorSystem("reminder-wiring-mgr")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "wiring-manager"
      FileTaskStore.create(sid, TaskCreateInput(subject = "mgr task", description = "d")).unsafeRunSync()
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
        mgrDef = AgentDef(
          name = "Manager",
          description = "team lead under test",
          tools = List("Read"),
          systemPrompt = ""
        )
        actorRef <- system.spawn(
          AgentActor(
            agentDef = mgrDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("reminder-wiring-mgr")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None))
        )
        _ <- actorRef ! AgentCommand.UserInput("hello from the user", None, Some("cmid-1"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.nonEmpty))
        req1 <- requests.get.map(_.head)
        sys1 = req1.systemStable.getOrElse("")
      yield
        assert(textOf(req1).contains("Current time"), s"real-user turn still injects time for team agents:\n${textOf(req1)}")
        assert(!textOf(req1).contains("Current Tasks"), s"Manager must not get the tasks reminder:\n${textOf(req1)}")
        assert(!textOf(req1).contains("mgr task"), s"Manager must not see task lines:\n${textOf(req1)}")
        assert(!sys1.contains("## Task List Protocol"), s"Task List Protocol is Nebula-only:\n$sys1")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

  /** Task redesign 2026-08-30: task injection now targets team sessions.
    * A team-registered agent (category="team") gets the full task render on
    * its real-user turn + the Task List Protocol section in systemStable. */
  test("③ team-registered agent gets tasks on real-user turns (task redesign)"):
    val system = ActorSystem("reminder-wiring-team")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "wiring-team-member"
      val teamName = "t-redesign"
      // team-scoped task store key = "team:<name>"
      FileTaskStore
        .create(nebflow.core.task.TaskStore.teamScopeKey(teamName), TaskCreateInput(subject = "team task", description = "d"))
        .unsafeRunSync()
      val program = for
        _ <- nebflow.core.flow.TeamSessionRegistry.clear
        _ <- nebflow.core.flow.TeamSessionRegistry.registerSession(teamName, "Backend", sid)
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
        memberDef = AgentDef(
          name = "Backend",
          description = "team member under test",
          tools = List("Read"),
          category = "team",
          systemPrompt = ""
        )
        actorRef <- system.spawn(
          AgentActor(
            agentDef = memberDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("reminder-wiring-team")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Team, sid, None))
        )
        _ <- actorRef ! AgentCommand.UserInput("hello from the user", None, Some("cmid-1"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.nonEmpty))
        req1 <- requests.get.map(_.head)
        sys1 = req1.systemStable.getOrElse("")
      yield
        assert(textOf(req1).contains("Current time"), s"real-user turn injects time:\n${textOf(req1)}")
        assert(textOf(req1).contains("## Current Tasks (1 active)"), s"team member gets the full task render:\n${textOf(req1)}")
        assert(textOf(req1).contains("team task"), s"task line present:\n${textOf(req1)}")
        // 阶段 2d（§D.2）：order 630 Task List Protocol 段已下迁进 TeamTask 三件
        // description——systemStable 不再携带协议段；协议经工具定义必达（wire 层）。
        assert(!sys1.contains("## Task List Protocol"), s"order 630 section retired (2d §D.2):\n$sys1")
        assert(
          req1.tools.exists(_.exists(td => td.name == "TeamTaskList" && td.description.contains("auto-expire"))),
          "protocol reaches the team member via TeamTaskList description (双轨期)"
        )
      program.unsafeRunSync()
    finally
      nebflow.core.flow.TeamSessionRegistry.clear.unsafeRunSync()
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

end ReminderWiringSpec
