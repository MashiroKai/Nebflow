package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentDef, AgentKind, AgentRecord, Behavior, Behaviors}
import nebflow.agent.{AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.ask.{AskGatewayServices, AskPromptCache, AskServices, AskSessionRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.llm.ModelCandidate
import nebflow.shared.PathUtil
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk, ThinkingConfig}

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * ask-mail 批（2026-11-16 · 作者令「canvas Ask 目前无双向通信通道；应给 ASK · INJECTED
 * 加上地址，使编排方（Spider）可通过 Mail 向该地址继续通信」）——ask 腿的机械钉。
 *
 * 地址形态：`ask:<sessionId>`（= `ask:ask-<8hex>`，前缀 + sessionId 零变换，单源
 * `MailAddress.AskPrefix`）。本 spec 钉住腿的三态与授权面：
 *  1. live（统一注册表有活 actor）⇒ `ExternalEvent("mail","inject")` 直投 ask 会话，
 *     metadata 携 `askMailAddress`（web chip 的结构化来源链起点）；
 *  2. 不 live ⇒ `SessionMailbox.mailOnce` 落该会话邮箱（`<dataRoot>/sessions/<id>/
 *     mailbox/pending/`，askOpen/askQuestion 下次唤醒时由 MailboxDrain 排干）；
 *  3. 绑定缺席 / 文件失联 / ask 面未装配 / 非授权发送方 / 空目标 ⇒ 显式错误码
 *     （MAIL_ASK_NOT_FOUND / MAIL_ASK_NOT_ROUTABLE / MAIL_ASK_UNAVAILABLE /
 *     MAIL_ASK_EXCLUSIVE / Malformed），零静默、零隐式排队。
 *
 * 授权面 = delegate 续聊同面：root（Nebula）专属；dispatcher/delegate/standalone 一律
 * MAIL_ASK_EXCLUSIVE。
 *
 * Isolation：`PathUtil.dataRoot` 指向 temp 目录（邮箱绝不触真实 `~/.nebflow`）；
 * `AskGatewayServices.instance` 是全局服务定位——每例 beforeEach 装、afterEach 卸，
 * 不与其它 suite 互污。
 */
class MailAskChannelSpec extends FunSuite:

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-mail-ask-channel"))
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit = PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    AskGatewayServices.uninstall()
    os.remove.all(tempRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = AskGatewayServices.uninstall()
  override def afterEach(context: munit.AfterEach): Unit = AskGatewayServices.uninstall()

  // ── fixtures（MailProjectTriggerDemolitionSpec 夹具形态承袭）──

  private def qIn(fields: (String, String)*): JsonObject =
    JsonObject.fromIterable(fields.map((k, v) => k -> Json.fromString(v)))

  private class StubLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = new StubLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tempRoot / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tempRoot / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  /** Root（Nebula）发送方：ask 腿授权判据 = agentDef.name（roleOf 单点）。 */
  private def rootCtx(system: ActorSystem, res: SharedResources): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-mail-ask-root"),
      isDispatcher = false,
      projectName = None,
      agentDef = Some(AgentDef(name = "Nebula", description = "spec fixture")),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  /** Delegate 会话发送方（引擎侧前缀判据）——ask 腿必须对它 EXCLUSIVE。 */
  private def delegateCtx(system: ActorSystem, res: SharedResources): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("delegate-demoab12"),
      isDispatcher = false,
      projectName = None,
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  /** 普通子代理发送方（Standalone 面）——同款 EXCLUSIVE。 */
  private def standaloneCtx(system: ActorSystem, res: SharedResources): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-plain-agent"),
      isDispatcher = false,
      projectName = None,
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  /** 记录到达的 AgentCommand（ask 会话探针——live 腿的接收面）。 */
  private def mkProbe(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  /** 建一个 ask 绑定（可问 .md 文件 + 注册表安装到服务定位）。doc 名每次唯一：
   * sid 由绑定路径哈希派生 ⇒ 每例独立 ask 会话与邮箱目录，前例的 pending 条目
   * 不会泄漏进后例的零残留断言（round1 红 2 根因 = 固定路径让全 suite 共享 sid）。 */
  private def mountAskFace(): (AskSessionRegistry, String, os.Path) =
    val docName = s"spec-doc-${java.util.UUID.randomUUID().toString.take(8)}.md"
    val doc = tempRoot / "docs" / docName
    os.write.over(doc, "# spec doc\nbody", createFolders = true)
    val registry = new AskSessionRegistry(tempRoot)
    val binding = registry.bind(doc.toString, docName).unsafeRunSync()
    AskGatewayServices.install(AskServices(registry, null, new AskPromptCache))
    (registry, binding.id, doc)

  private def pendingFiles(sid: String): List[os.Path] =
    val dir = tempRoot / "sessions" / sid / "mailbox" / "pending"
    if os.exists(dir) then os.list(dir).filter(os.isFile(_)).toList else Nil

  // ── 1. live：注入直达 ask 会话 ───────────────────────────────

  test("root → live ask session: ExternalEvent(mail, inject) reaches the ask agent with askMailAddress metadata"):
    val system = ActorSystem(s"mail-ask-live-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val (_, askId, _) = mountAskFace()
      val program =
        for
          record <- Ref.of[IO, List[AgentCommand]](Nil)
          resources <- mkResources(system)
          probe <- system.spawn(mkProbe(record), s"probe-$askId")
          _ <- resources.agentRegistry.update(
            _ + (askId -> AgentRecord(sessionId = askId, ref = probe, kind = AgentKind.SubTask, rootSessionId = "nebula-root"))
          )
          res <- MailTool.call(
            qIn("address" -> s"ask:$askId", "message" -> "follow-up from the orchestrator"),
            rootCtx(system, resources)
          )
          _ <- IO.sleep(100.millis) // tell 是入队面：给探针一拍处理时间
          _ <- system.stopAll.handleErrorWith(_ => IO.unit)
        yield (res, record)

      val (res, record) = program.unsafeRunSync()
      res match
        case Right(ok) =>
          assert(ok.contains(s"[ask:$askId]"), s"receipt must name the ask session, got: $ok")
          assert(ok.contains("live"), s"receipt must say the live leg fired, got: $ok")
        case Left(err) => fail(s"live delivery must succeed, got: ${err.message}")
      val cmds = record.get.unsafeRunSync()
      assertEquals(cmds.size, 1, s"exactly one command may reach the ask agent, got: $cmds")
      cmds.head match
        case AgentCommand.ExternalEvent(source, eventType, payload, metadata, _, _) =>
          assertEquals(source, "mail")
          assertEquals(eventType, "inject")
          assertEquals(payload, "follow-up from the orchestrator")
          assertEquals(
            metadata("askMailAddress").flatMap(_.asString),
            Some(s"ask:$askId"),
            "the structured address chain starts here (web chip reads this field)"
          )
        case other => fail(s"expected ExternalEvent, got: $other")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()

  // ── 2. 不 live：落会话邮箱（下次 askOpen/askQuestion 排干）────

  test("root → ask session not live: message persists in the session mailbox (drained at the next askOpen/askQuestion)"):
    val system = ActorSystem(s"mail-ask-queue-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val (_, askId, _) = mountAskFace()
      val program =
        for
          resources <- mkResources(system)
          res <- MailTool.call(
            qIn("address" -> s"ask:$askId", "message" -> "queued supplement"),
            rootCtx(system, resources)
          )
        yield res

      val res = program.unsafeRunSync()
      res match
        case Right(ok) =>
          assert(ok.contains(s"[ask:$askId]"), s"receipt must name the ask session, got: $ok")
          assert(ok.contains("mailbox"), s"receipt must say the mailbox leg fired, got: $ok")
        case Left(err) => fail(s"mailbox delivery must succeed, got: ${err.message}")

      val files = pendingFiles(askId)
      assertEquals(files.size, 1, s"exactly one pending entry, got: $files")
      val entry = io.circe.parser.parse(os.read(files.head)).fold(e => fail(s"entry must be JSON: $e"), identity)
      val c = entry.hcursor
      assertEquals(c.downField("kind").as[String].toOption, Some("ask-mail"), "entry kind")
      assertEquals(c.downField("producer").as[String].toOption, Some("mail"), "entry producer")
      assertEquals(
        c.downField("event").downField("source").as[String].toOption,
        Some("mail"),
        "event source"
      )
      assertEquals(
        c.downField("event").downField("eventType").as[String].toOption,
        Some("inject"),
        "event type"
      )
      assertEquals(
        c.downField("event").downField("payload").as[String].toOption,
        Some("queued supplement"),
        "event payload must carry the mail body verbatim"
      )
      assertEquals(
        c.downField("event").downField("metadata").downField("askMailAddress").as[String].toOption,
        Some(s"ask:$askId"),
        "drain leg forwards this metadata into the askAddress UI chain"
      )
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()

  // ── 3. 未知 ask id：显式 NOT_FOUND，零排队 ────────────────────

  test("root → unknown ask id: MAIL_ASK_NOT_FOUND, nothing queued (never silent)"):
    val system = ActorSystem(s"mail-ask-404-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      mountAskFace() // face 在场但目标 id 未绑定
      val sid = "ask-deadbeef"
      val program =
        for
          resources <- mkResources(system)
          res <- MailTool.call(
            qIn("address" -> s"ask:$sid", "message" -> "into the void"),
            rootCtx(system, resources)
          )
        yield res

      program.unsafeRunSync() match
        case Left(err) =>
          assert(err.message.contains(MailTool.ErrAskNotFound), s"must carry NOT_FOUND, got: ${err.message}")
        case Right(v) => fail(s"unknown ask id must be refused, got: $v")
      assertEquals(pendingFiles(sid), Nil, "no mailbox entry may be created for an unknown id")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()

  // ── 4. 非授权发送方：EXCLUSIVE（delegate 续聊同面）───────────

  test("delegate / standalone senders → MAIL_ASK_EXCLUSIVE (the delegate-continuation face)"):
    val system = ActorSystem(s"mail-ask-excl-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val (_, askId, _) = mountAskFace()
      val program =
        for
          resources <- mkResources(system)
          resDelegate <- MailTool.call(
            qIn("address" -> s"ask:$askId", "message" -> "delegate may not"),
            delegateCtx(system, resources)
          )
          resPlain <- MailTool.call(
            qIn("address" -> s"ask:$askId", "message" -> "standalone may not"),
            standaloneCtx(system, resources)
          )
        yield (resDelegate, resPlain)

      val (resDelegate, resPlain) = program.unsafeRunSync()
      for (label, res) <- List("delegate" -> resDelegate, "standalone" -> resPlain) do
        res match
          case Left(err) =>
            assert(err.message.contains(MailTool.ErrAskExclusive), s"$label: must carry EXCLUSIVE, got: ${err.message}")
          case Right(v) => fail(s"$label: must be refused, got: $v")
      assertEquals(pendingFiles(askId), Nil, "a refused sender must leave no mailbox entry")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()

  // ── 5. 绑定文件失联：NOT_ROUTABLE（不静默积压邮箱）────────────

  test("root → bound file gone: MAIL_ASK_NOT_ROUTABLE (spawn face rejects it too — no silent eternal queue)"):
    val system = ActorSystem(s"mail-ask-dead-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val (_, askId, doc) = mountAskFace()
      os.remove(doc) // 绑定在场、文件失联——ask 会话的「不可达」面
      val program =
        for
          resources <- mkResources(system)
          res <- MailTool.call(
            qIn("address" -> s"ask:$askId", "message" -> "no one will read this"),
            rootCtx(system, resources)
          )
        yield res

      program.unsafeRunSync() match
        case Left(err) =>
          assert(err.message.contains(MailTool.ErrAskNotRoutable), s"must carry NOT_ROUTABLE, got: ${err.message}")
        case Right(v) => fail(s"an unroutable ask session must be refused, got: $v")
      assertEquals(pendingFiles(askId), Nil, "an unroutable session must not accumulate mailbox entries")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()

  // ── 6. ask 面未装配：UNAVAILABLE ─────────────────────────────

  test("ask face not mounted (no AskGatewayServices) → MAIL_ASK_UNAVAILABLE"):
    val system = ActorSystem(s"mail-ask-off-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      AskGatewayServices.uninstall() // beforeEach 已卸，此处显式表意
      val program =
        for
          resources <- mkResources(system)
          res <- MailTool.call(
            qIn("address" -> "ask:ask-1a2b3c4d", "message" -> "no face"),
            rootCtx(system, resources)
          )
        yield res

      program.unsafeRunSync() match
        case Left(err) =>
          assert(err.message.contains(MailTool.ErrAskUnavailable), s"must carry UNAVAILABLE, got: ${err.message}")
        case Right(v) => fail(s"a missing ask face must be refused, got: $v")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()

  // ── 7. 空目标：显式 Malformed（不猜、不兜底）─────────────────

  test("bare `ask:` (empty target) → explicit Malformed address error"):
    val system = ActorSystem(s"mail-ask-mal-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      mountAskFace()
      val program =
        for
          resources <- mkResources(system)
          res <- MailTool.call(
            qIn("address" -> "ask:", "message" -> "malformed"),
            rootCtx(system, resources)
          )
        yield res

      program.unsafeRunSync() match
        case Left(err) =>
          assert(err.message.contains("Malformed address"), s"must say Malformed, got: ${err.message}")
        case Right(v) => fail(s"an empty ask target must be refused, got: $v")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()

end MailAskChannelSpec
