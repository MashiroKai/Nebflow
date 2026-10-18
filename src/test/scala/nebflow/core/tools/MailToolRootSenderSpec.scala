package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.actor.AgentRecord
import nebflow.agent.SharedResources
import nebflow.core.flow.TeamSessionRegistry
import nebflow.core.SessionStore
import nebflow.llm.ModelCandidate
import nebflow.shared.{PathUtil, ThinkingConfig}

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

  // ── root sender + bare short name: still rejected (b9d427c6 regression) ──

  test("queue: root sender + bare short name still rejected"):
    val res = MailTool.deliverQueue("Backend", "hi", "INFO", Nil, rootCtx, null).unsafeRunSync()
    assertRejected(res, "queue short name")

  test("immediate: root sender + bare short name still rejected"):
    val res = MailTool
      .deliverShortNameUnscoped("Backend", "hi", None, "INFO", rootCtx, null, rootSid)
      .unsafeRunSync()
    assertRejected(res, "immediate short name")

  // ── R2 分层地址面（R-13/R-14/R-15，2026-09-12；project-trigger 拆除批后 Root 面不含项目地址）──

  /** Nebula root 身份：判据 = agentDef.name（引擎侧身份，非字符串猜名）。 */
  private def nebulaCtx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = tempRoot.toString,
      sessionId = Some(rootSid),
      agentDef = Some(nebflow.actor.AgentDef(name = "Nebula", description = "", tools = Nil)),
      actorSystem = Some(system)
    )

  test("R2: Nebula sender + node:<id|name> ⇒ N1 刀3 授权面扩：零挂载作用域 = 显式 CHAIN_NODE_NOT_FOUND（不猜不静默、指路 NodeList）"):
    val system = ActorSystem(s"mail-r2-neb-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("node:n-1"), "message" -> Json.fromString("hi")),
          nebulaCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          // N1 刀3（S6=C · v3 §7.2 ①授权面扩）：root 的 node: 腿从「越界」升格为
          // 「跨已挂载 delegate 作用域解析」——旧 R2 钉（Nebula 无 node 面 ⇒
          // outside-your-address-face）被设计取代。零挂载作用域 = 显式
          // CHAIN_NODE_NOT_FOUND（不猜不静默），文案指路属主 delegate 的 NodeList。
          assert(err.message.contains("CHAIN_NODE_NOT_FOUND"), s"须显式未找到：${err.message}")
          assert(err.message.contains("NodeList"), s"文案须指路 NodeList：${err.message}")
        case Right(v) => fail(s"零挂载作用域必须显式报错，got: $v")
    finally system.stopAll.unsafeRunSync()

    end try

  test("R2: Nebula sender + \"Nebula\"（自身）⇒ 显式报错（无自身地址）"):
    val system = ActorSystem(s"mail-r2-self-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(JsonObject("address" -> Json.fromString("Nebula"), "message" -> Json.fromString("hi")), nebulaCtx(system))
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(err.message.contains("self"), s"须点名自身地址：${err.message}")
          assert(err.message.contains("node:<节点id或名>"), s"错误文案须指明合法地址面：${err.message}")
        case Right(v) => fail(s"Nebula 发自身上址必须显式报错，got: $v")
    finally system.stopAll.unsafeRunSync()

  test("R2: Nebula sender + 认不出的裸名 ⇒ 显式报错（禁静默兜底/模糊匹配）"):
    val system = ActorSystem(s"mail-r2-unk-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("no-such-project-xyz"), "message" -> Json.fromString("hi")),
          nebulaCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(err.message.contains("not a recognizable target"), s"须是解析失败报错：${err.message}")
          assert(err.message.contains("node:<节点id或名>"), s"错误文案须指明合法地址面：${err.message}")
          assert(!err.message.contains("TEAM names only"), "不得回落成 team 路由口径（禁静默兜底）")
        case Right(v) => fail(s"认不出的地址必须显式报错，got: $v")
    finally system.stopAll.unsafeRunSync()

  // ── project: leg demolished (the project-trigger demolition batch) ──────────
  // Mail carries NO project trigger face any more: a project address
  // (`project:<name>` — bare mounted names are refused on their own legs) is an
  // explicit MAIL_PROJECT_TRIGGER_UNSUPPORTED refusal for EVERY role, before any
  // mount check or dispatcher trigger runs (no spawn, no revive, no note append,
  // no auto-created task). Task dispatching is Delegate's entry.

  private def delegateCtx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = tempRoot.toString,
      sessionId = Some("delegate-ab12cd34"),
      actorSystem = Some(system)
    )

  private def dispatcherCtx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = tempRoot.toString,
      sessionId = Some("dispatcher-spec-sid"),
      isDispatcher = true,
      actorSystem = Some(system)
    )

  test("demolished: delegate session + project:<name> => explicit refusal (no dispatcher trigger)"):
    val system = ActorSystem(s"mail-r2-del-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("project:p-x"), "message" -> Json.fromString("hi")),
          delegateCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(
            err.message.contains(MailTool.ErrProjectTriggerUnsupported),
            s"must be the demolition refusal: ${err.message}"
          )
          assert(err.message.contains("Delegate"), s"must point at Delegate: ${err.message}")
          assert(!err.message.contains("not mounted"), s"the refusal must not depend on mount state: ${err.message}")
        case Right(v) => fail(s"a delegate mailing project: must be rejected, got: $v")
    finally system.stopAll.unsafeRunSync()

  test("demolished: team sender + project:<name> => explicit refusal (no dispatcher trigger)"):
    val system = ActorSystem(s"mail-r2-team-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("project:p-x"), "message" -> Json.fromString("hi")),
          rootCtx.copy(actorSystem = Some(system)) // no Nebula agentDef => team-context role
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(
            err.message.contains(MailTool.ErrProjectTriggerUnsupported),
            s"must be the demolition refusal: ${err.message}"
          )
          assert(!err.message.contains("not mounted"), s"the refusal must not depend on mount state: ${err.message}")
        case Right(v) => fail(s"a team sender mailing project: must be rejected, got: $v")
    finally system.stopAll.unsafeRunSync()

  test("demolished: dispatcher + project:<name> => explicit refusal (no self-trigger)"):
    val system = ActorSystem(s"mail-r2-disp-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("project:p-x"), "message" -> Json.fromString("hi")),
          dispatcherCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(
            err.message.contains(MailTool.ErrProjectTriggerUnsupported),
            s"must be the demolition refusal: ${err.message}"
          )
          assert(!err.message.contains("not mounted"), s"the refusal must not depend on mount state: ${err.message}")
        case Right(v) => fail(s"a dispatcher mailing project: must be rejected, got: $v")
    finally system.stopAll.unsafeRunSync()

  test("demolished: Nebula root + project:<name> => explicit refusal (the trigger leg is gone, mount state irrelevant)"):
    val system = ActorSystem(s"mail-r2-root-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("project:no-such-project-xyz"), "message" -> Json.fromString("hi")),
          nebulaCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(
            err.message.contains(MailTool.ErrProjectTriggerUnsupported),
            s"must be the demolition refusal: ${err.message}"
          )
          assert(err.message.contains("Delegate"), s"must point at Delegate: ${err.message}")
          assert(
            !err.message.contains("outside your address face"),
            s"the demolition word replaces the face word: ${err.message}"
          )
        case Right(v) => fail(s"a project address must be refused, got: $v")
    finally system.stopAll.unsafeRunSync()

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
        assert(
          !err.message.contains("TEAM names only"),
          s"team-internal short name must not hit the rule: ${err.message}"
        )
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
