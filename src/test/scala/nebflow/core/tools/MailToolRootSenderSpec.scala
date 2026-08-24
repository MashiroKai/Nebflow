package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.flow.TeamSessionRegistry
import nebflow.gateway.SessionStore
import nebflow.llm.{ModelCandidate, ThinkingConfig}

/**
 * User ruling 2026-08-24: root/outside-team senders mail TEAM names only.
 * The "team/agent" explicit-address escape hatch (the 372/373 direct-to-member
 * bypass that left the Manager blind) is closed at the MailTool layer —
 * "nebflow-project/Frontend" and bare short names are both rejected from a
 * root sender. TEAM names still route to the team Manager; team-internal
 * short names and same-team team/agent routes are untouched.
 *
 * Layer note: the underlying TeamSessionRegistry.resolveSessionId keeps its
 * full semantics (TeamSessionRegistrySpec pins it) — this spec pins the
 * MailTool-level guard that sits ABOVE it.
 */
class MailToolRootSenderSpec extends FunSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-mail-root-sender"
  PathUtil.setDataRoot(tempRoot)

  private def writeTeam(name: String, lead: String): Unit =
    val dir = tempRoot / "teams" / name
    os.makeDir.all(dir)
    os.write.over(
      dir / "team.json",
      s"""{"name": "$name", "description": "test team", "lead": "$lead", "members": []}"""
    )

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "teams")
    TeamSessionRegistry.clear.unsafeRunSync()
    writeTeam("myteam", lead = "boss")

  // A root sender: sessionId never registered in TeamSessionRegistry.
  private val rootSid = "root-sender-sid"

  private def rootCtx: ToolContext =
    ToolContext(projectRoot = tempRoot.toString, sessionId = Some(rootSid))

  private def teamMemberCtx(workerSid: String): ToolContext =
    ToolContext(projectRoot = tempRoot.toString, sessionId = Some(workerSid))

  private def askCtx: ToolContext =
    rootCtx.copy(
      actorSystem = Some(ActorSystem("mail-root-spec")),
      sharedResources = Some(
        new SharedResources(
          llm = null,
          dispatcher = null,
          sessionStore = null,
          projectRoot = os.pwd,
          thinkingConfigRef = cats.effect.Ref.unsafe[IO, nebflow.llm.ThinkingConfig](nebflow.llm.ThinkingConfig()),
          rateLimiter = null,
          fileChangeTracker = null,
          contextWindow = 100_000,
          agentLibrary = null,
          taskStore = null,
          historyArchiver = null,
          fileLockManager = null,
          sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, nebflow.llm.ModelCandidate]](Map.empty),
          providerRegistry = null,
          healthMonitor = null,
          actorSystem = null,
          agentRegistry = cats.effect.Ref.unsafe[IO, Map[String, nebflow.agent.AgentRecord]](Map.empty),
          voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false)
        )
      )
    )

  private def assertRejected(res: Either[ToolError, String], what: String): Unit =
    res match
      case Left(err) =>
        assert(err.message.contains("TEAM names only"), s"should cite the routing rule: ${err.message}")
        assert(
          !err.message.contains("Or use explicit"),
          s"must no longer advertise the team/agent escape hatch: ${err.message}"
        )
        assert(err.message.contains("team/agent"), s"should mention the blocked form: ${err.message}")
      case Right(_) => fail(s"$what must be rejected for a root sender")

  // ── root sender + team/agent explicit address: rejected in ALL modes ──

  test("queue: root sender + team/agent address rejected"):
    val res = MailTool.deliverQueue("myteam/Frontend", "hi", "INFO", Nil, rootCtx, null).unsafeRunSync()
    assertRejected(res, "queue team/agent")

  test("immediate: root sender + team/agent address rejected"):
    val res = MailTool
      .deliverShortNameUnscoped("myteam/Frontend", "hi", None, "INFO", rootCtx, null, rootSid)
      .unsafeRunSync()
    assertRejected(res, "immediate team/agent")

  test("ask: root sender + team/agent address rejected"):
    val res = MailTool.forkAndAsk("myteam/Frontend", "question?", None, askCtx).unsafeRunSync()
    assertRejected(res, "ask team/agent")

  // ── root sender + bare short name: still rejected (b9d427c6 regression) ──

  test("queue: root sender + bare short name still rejected"):
    val res = MailTool.deliverQueue("Backend", "hi", "INFO", Nil, rootCtx, null).unsafeRunSync()
    assertRejected(res, "queue short name")

  test("immediate: root sender + bare short name still rejected"):
    val res = MailTool
      .deliverShortNameUnscoped("Backend", "hi", None, "INFO", rootCtx, null, rootSid)
      .unsafeRunSync()
    assertRejected(res, "immediate short name")

  test("ask: root sender + bare short name still rejected"):
    val res = MailTool.forkAndAsk("Backend", "question?", None, askCtx).unsafeRunSync()
    assertRejected(res, "ask short name")

  // ── root sender + TEAM name: routes to the team Manager ──

  test("queue: root sender + TEAM name routes to the team Manager (lead)"):
    TeamSessionRegistry.registerSession("myteam", "boss", "boss-sid").unsafeRunSync()
    val sys = ActorSystem("mail-root-spec")
    val store = SessionStore(os.temp.dir(), os.temp.dir())
    val res = MailTool
      .deliverQueue(
        "myteam",
        "hello team",
        "INFO",
        Nil,
        rootCtx.copy(
          actorSystem = Some(sys),
          sharedResources = Some(
            new SharedResources(
              llm = null,
              dispatcher = null,
              sessionStore = store,
              projectRoot = os.pwd,
              thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
              rateLimiter = null,
              fileChangeTracker = null,
              contextWindow = 100_000,
              agentLibrary = null,
              taskStore = null,
              historyArchiver = null,
              fileLockManager = null,
              sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
              providerRegistry = null,
              healthMonitor = null,
              actorSystem = null,
              agentRegistry = Ref.unsafe[IO, Map[String, AgentRecord]](Map.empty),
              voiceMutedRef = Ref.unsafe[IO, Boolean](false)
            )
          )
        ),
        sys
      )
      .unsafeRunSync()
    // End-to-end routing evidence: the mail landed in the Manager's persisted
    // queue (dataRoot/sessions/<manager-sid>/mail-queue.json), from the root sender.
    val qFile = tempRoot / "sessions" / "boss-sid" / "mail-queue.json"
    assert(os.exists(qFile), s"mail must be queued to the Manager session: $res")
    val qContent = os.read(qFile)
    assert(qContent.contains("hello team"), s"queue should carry the message: $qContent")
    assert(qContent.contains("Nebula"), s"queue from-field should be the root sender: $qContent")

  // ── team-internal paths: untouched (acceptance 4) ──

  test("team member: short name routing unaffected"):
    TeamSessionRegistry.registerSession("myteam", "worker", "worker-sid").unsafeRunSync()
    TeamSessionRegistry.registerSession("myteam", "Backend", "backend-sid").unsafeRunSync()
    val res = MailTool.deliverQueue("Backend", "hi", "INFO", Nil, teamMemberCtx("worker-sid"), null).unsafeRunSync()
    res match
      case Left(err) =>
        assert(err.message.contains("Backend"), s"should route within the team: ${err.message}")
        assert(!err.message.contains("TEAM names only"), s"team-internal short name must not hit the rule: ${err.message}")
      case Right(_) => fail(s"expected queueToSession outcome, got success: $res")

  test("team member: same-team team/agent route unaffected"):
    TeamSessionRegistry.registerSession("myteam", "worker", "worker-sid").unsafeRunSync()
    TeamSessionRegistry.registerSession("myteam", "Frontend", "frontend-sid").unsafeRunSync()
    val res = MailTool
      .deliverQueue("myteam/Frontend", "hi", "INFO", Nil, teamMemberCtx("worker-sid"), null)
      .unsafeRunSync()
    res match
      case Left(err) =>
        assert(err.message.contains("Frontend"), s"should route the explicit same-team route: ${err.message}")
        assert(!err.message.contains("TEAM names only"), s"same-team team/agent must not hit the rule: ${err.message}")
      case Right(_) => fail(s"expected queueToSession outcome, got success: $res")

end MailToolRootSenderSpec
