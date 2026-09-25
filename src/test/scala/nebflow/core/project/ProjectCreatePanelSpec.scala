package nebflow.core.project

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, MailTool, NodeTools, ProjectCreateTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * ProjectCreate「未知路径 AskUser 式交互面板」全链验收（阶段2迁移 §6.2 口径②）。
 *
 * 口径/语义映射：
 *  ① 已知路径直建（test ①）——name 缺省 = basename、脚手架、幂等挂载、Mail 提示（R2 后）；
 *  ② 未知/缺省 path → AskUser 式面板 pending 产生（test ②，真实 InteractionHub）；
 *  ③ 面板点选 → 创建链走通（test ③，含 #43 兼容断言：agent 侧负载不消费面板槽）；
 *  ④ 创建后 Task(project=…) 可触发（test ④，真实分发器会话拉起证据）；
 *  ⑤ 幂等 / 同名异 workspace 冲突语义（test ⑤）；
 *  ⑥ 取消哨兵 / 空答案 → 明确搁置不创建；非绝对路径 → 明确报错（test ⑥）；
 *     无 agent 会话时缺省 path → 立即报错不悬挂（test ⑦）。
 *  ⑧–⑫ S2 作者 2026-09-17 12:09 裁定单（④ 反守卫 + ③ 补缺脚手架 + ④-11b：
 *     异 name 指向已占用 workspace → 默认拒绝零写盘（⑧）、归一化变体同拒（⑨）、
 *     归档占用者同拒（⑩）、幂等挂载路径补缺脚手架（⑪）、NodeList meta 增
 *     workspace 权威键（⑫）。
 *
 * 面板复用 AskUser pending 机制（AgentCommand.AskUser → InteractionHub → 前端
 * AskUserQuestion 卡片）——spec 用真实 hub actor + 桩 agent actor（只复刻
 * AgentActor.AskUser handler 的转发职责）+ 答案 sink，用户点选 = 带 answers
 * 字段的 Answered 帧（与 gateway askUserAnswer 翻译后同形态）。#43 语义不受
 * 影响：面板槽只能被用户形态帧消费，agent 消息形态负载（无 answers 字段）永不
 * 消费（test ③ 以真实 requestId + 噪声负载钉住）。
 */
class ProjectCreatePanelSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-project-create-panel"
  private val originalRoot = PathUtil.dataRoot

  // 类级：隔离 dataRoot（projects/ agents/ 落 tempRoot，禁碰真实 ~/.nebflow）
  // + 预置分发器定义（test ④ 走 EntityLoader）。
  // 2026-09-09 作者裁定：面板不再下发候选（删除 candidates-dir 属性注入）。
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "project-dispatcher")

  os.write.over(
    tempRoot / "agents" / "project-dispatcher" / "agent.json",
    """{"name":"project-dispatcher","description":"panel spec dispatcher","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "project-dispatcher" / "system.md", "# project-dispatcher\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ============================================================
  // Harness
  // ============================================================

  private def minimalResources(projectRoot: os.Path): SharedResources =
    new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = projectRoot,
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
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )

  /** 正常完成 LLM：一个文本 delta 即收尾（test ④ 分发器 turn 快速终态）。 */
  private class RecordingLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def fullResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
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
      voiceMutedRef = voiceMuted
    )

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

  /**
   * 桩 agent actor：只复刻 AgentActor.AskUser handler 的转发职责——收到
   * AgentCommand.AskUser → 记录 (requestId, items)（面板载荷断言面）→ 投真实
   * InteractionHub（pending 槽 + 根窗口渲染帧由此产生）。
   *
   * 🔴 S3 批 r4 返工（作者 2026-09-17 裁定 R3 帧级红因 = (甲) 测试面缺陷）：载荷
   * **不再由本桩自造**——改经 `AskUserFrameBridge` 走生产序列化单点
   * `AgentActor.buildAskUserJson`（本桩自此只保留转发职责）。原桩硬写
   * `question`/`options`/`allowOther` 三键 ⇒ 结构性不含 `dirPicker`/`freeInput`，
   * 帧级断言实际所检 = 测试自造字面量（缺陷本体）。改后帧级断言所检 = 真 `AskItem`
   * 序列化输出 ⇒ 生产发射面改动可使该断言转红（红锚可分离）。
   */
  private def panelAgentActor(
    hub: ActorRef[InteractionHubCommand],
    rootSid: String,
    gotAsk: Ref[IO, Option[(String, List[nebflow.core.AskItem])]]
  )(system: ActorSystem): IO[ActorRef[AgentCommand]] =
    lazy val behavior: Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand] {
      case AgentCommand.AskUser(requestId, items, replyToOpt, _askMode) =>
        gotAsk.set(Some((requestId, items))) *>
          (hub ! InteractionHubCommand.Request(
            InteractionRequest(
              requestId = requestId,
              kind = InteractionKind.AskUser,
              // 帧载荷 = 生产序列化单点产出（本桩不自造 item 字面量）
              payload = AskUserFrameBridge.payload(rootSid, items),
              reply = InteractionReply.AskUserReply(replyToOpt),
              rootSessionId = rootSid,
              sourceAgent = "Nebula",
              sourceSession = rootSid
            )
          )).void.as(behavior)
      case _ => IO.pure(behavior)
    }
    system.spawn(behavior, s"panel-agent-${scala.util.Random.nextInt(100000)}")

  end panelAgentActor

  private def toolCtx(
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    agentRef: Option[ActorRef[AgentCommand]] = None,
    wsSend: Option[Json => IO[Unit]] = None
  ): ToolContext =
    ToolContext(
      projectRoot = ws.toString,
      sessionId = Some("nebula-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system),
      agentActorRef = agentRef,
      wsSend = wsSend
    )

  // ============================================================
  // ① 已知路径直建
  // ============================================================

  test("①（R2 后）已知路径直建：project.json 字段落盘 + 脚手架 + 幂等挂载 + Mail 提示") {
    val ws = tempRoot / "ws-alpha"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-1-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    val input = Json
      .obj(
        "workspace" -> Json.fromString(ws.toString),
        "description" -> Json.fromString("panel spec direct create")
      )
      .asObject
      .get
    for
      result <- ProjectCreateTool.call(input, toolCtx(ws, system, res))
      pd <- ProjectStore.load("ws-alpha")
      rt <- ProjectRuntimeRegistry.get("ws-alpha")
      gitignore <- IO(os.read(ws / ".gitignore"))
      _ <- ProjectRuntimeRegistry.unregister("ws-alpha")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isRight, s"direct create must succeed: $result")
      val msg = result.toOption.get
      // R2（2026-09-12）：提示语由 Task(...) 改为 Mail(address="project:...")——旧工具已删净退役
      assert(msg.contains("Mail(address='project:ws-alpha'"), s"success message must carry the Mail usage hint: $msg")
      // S2 2026-09-17 12:09 裁定单 ③-9：创建路径同样逐件报 created/skipped
      assert(msg.contains("Scaffold:"), s"create-path message must carry the per-item scaffold report: $msg")
      assert(msg.contains("AGENTS.md created"), s"create-path scaffold report must list 'AGENTS.md created': $msg")
      assert(pd.isDefined, "project.json must be persisted")
      val defn = pd.get
      assertEquals(defn.name, "ws-alpha", "name must derive from workspace basename when omitted")
      assertEquals(defn.workspace, ws.toString)
      assertEquals(defn.description, Some("panel spec direct create"))
      assertEquals(defn.agentFile, (ws / "AGENTS.md").toString)
      assert(defn.createdAt > 0, "createdAt must be set")
      assert(os.exists(ws / "AGENTS.md"), "workspace AGENTS.md scaffold must exist")
      assertEquals(
        os.read(ws / "AGENTS.md"),
        "",
        "AGENTS.md 默认空模板（作者 2026-09-18 裁定）：创建路径落盘的也是 0 字节空文件，内容由用户自持"
      )
      assert(os.isDir(ws / ".nebflow"), "workspace .nebflow/ scaffold must exist")
      assert(gitignore.contains(".nebflow/"), "workspace .gitignore must guard .nebflow/")
      assert(rt.isDefined, "project must be mounted (ProjectRuntimeRegistry)")
      assertEquals(rt.get.engine.rootSessionId, "nebula-root", "mount must anchor to the upline root session")
    end for
  }

  // ============================================================
  // ② 未知/缺省 path → 面板 pending 产生
  // ============================================================

  test("② 缺省 workspace → AskUser 式面板 pending 产生（真实 hub；空 options + dirPicker，2026-09-09 作者裁定）") {
    val ws = tempRoot / "ws-p2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-2-${scala.util.Random.nextInt(100000)}")
    for
      res <- IO(minimalResources(ws))
      frames <- Ref.of[IO, List[Json]](Nil)
      hub <- system.spawn(InteractionHub(), "interaction-hub-p2")
      _ <- hub ! InteractionHubCommand.RegisterRoot("nebula-root", (j: Json) => frames.update(_ :+ j))
      gotAsk <- Ref.of[IO, Option[(String, List[nebflow.core.AskItem])]](None)
      agentRef <- panelAgentActor(hub, "nebula-root", gotAsk)(system)
      done <- Deferred[IO, Either[nebflow.core.tools.ToolError, String]]
      fib <- ProjectCreateTool
        .call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => done.complete(r).void)
        .start
      // pending 证据：面板载荷已派发（items 断言面）+ 根窗口渲染帧已发出（hub 状态面）
      _ <- waitUntil(10.seconds)(gotAsk.get.map(_.isDefined))
      _ <- waitUntil(10.seconds)(
        frames.get.map(_.exists(_.hcursor.downField("type").as[String].toOption.contains("askUser")))
      )
      askOpt <- gotAsk.get
      fs <- frames.get
      // 用户取消收尾（面板不能悬挂测试）
      rid = askOpt.map(_._1).getOrElse("")
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(
          requestId = rid,
          rootSessionId = "nebula-root",
          payload = Json.obj("answers" -> Json.arr(Json.fromString(ProjectCreateTool.CancelSentinel)))
        )
      )
      result <- done.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isRight, s"cancel closes the panel successfully: $result")
      assert(result.toOption.get.contains("shelved"), s"cancel → shelved message: $result")
      val (ridGot, items) = askOpt.getOrElse(fail("panel items must be dispatched"))
      assertEquals(ridGot, rid)
      assertEquals(items.length, 1, "exactly one question (path selection)")
      assertEquals(
        items.head.options,
        List.empty,
        "no candidate options — in-app browser is the selection surface (2026-09-09；该条未被 09-17 裁定取代)"
      )
      assert(items.head.dirPicker, "workspace card must carry dirPicker=true (2026-09-05 作者裁定)")
      assert(items.head.question.contains("选择工作区"), "question must guide to the in-app browser")
      // 2026-09-17 作者裁定（S3 ②-5/②-7）：面板文案退役「~ 手输」叙述（改述「选择后即完成创建」），
      // 且该卡显式 freeInput=false（前端不渲染自由输入 textarea；选择面不可用时才按需揭示兜底）。
      assert(items.head.question.contains("选择后即完成创建"), "question must state that picking completes the creation")
      assert(!items.head.question.contains("输入框"), "question must not advertise the retired free-input box")
      assert(!items.head.question.contains("~"), "question must no longer advertise the retired ~ manual input")
      assert(!items.head.freeInput, "workspace card must carry freeInput=false (2026-09-17 ②-7)")
      val askFrame = fs.find(_.hcursor.downField("type").as[String].toOption.contains("askUser")).get
      val frameItem0 = askFrame.hcursor.downField("items").as[List[Json]].toOption.get.head
      val frameOptions = frameItem0.hcursor.downField("options").as[List[Json]].toOption.get
      assertEquals(frameOptions, Nil, "rendered frame must carry empty options")
      // 帧级断言（JSON 游标口径：不依赖模型新字段名 ⇒ 改前树同样可编译，红锚可分离）
      assertEquals(
        frameItem0.hcursor.downField("dirPicker").as[Boolean],
        Right(true),
        "rendered frame must carry dirPicker=true"
      )
      assertEquals(
        frameItem0.hcursor.downField("freeInput").as[Boolean],
        Right(false),
        "rendered frame must carry freeInput=false (2026-09-17 ②-7)"
      )
      assert(askFrame.hcursor.downField("requestId").as[String].isRight, "frame must carry requestId")
    end for
  }

  // ============================================================
  // ③ 面板点选 → 创建完成（含 #43 兼容断言）
  // ============================================================

  test("③ 用户点选目录浏览器返回的路径 → 创建链走通；pending 期间 agent 形态负载不消费面板槽（#43 语义）") {
    val ws = tempRoot / "ws-p3"
    os.makeDir.all(ws)
    // 浏览器选中目录（等价 workspacePicker onPick 回传的绝对路径）
    val pickDir = tempRoot / "ws-pick-beta"
    os.makeDir.all(pickDir)
    val system = ActorSystem(s"pcp-3-${scala.util.Random.nextInt(100000)}")
    for
      res <- IO(minimalResources(ws))
      hub <- system.spawn(InteractionHub(), "interaction-hub-p3")
      _ <- hub ! InteractionHubCommand.RegisterRoot("nebula-root", (_: Json) => IO.unit)
      gotAsk <- Ref.of[IO, Option[(String, List[nebflow.core.AskItem])]](None)
      agentRef <- panelAgentActor(hub, "nebula-root", gotAsk)(system)
      done <- Deferred[IO, Either[nebflow.core.tools.ToolError, String]]
      fib <- ProjectCreateTool
        .call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => done.complete(r).void)
        .start
      _ <- waitUntil(10.seconds)(gotAsk.get.map(_.isDefined))
      rid <- gotAsk.get.map(_.map(_._1).getOrElse(fail("requestId missing")))
      // pending 期间 agent 侧消息形态负载到达（delegate 汇报形态：无 answers 字段，
      // requestId 指向真实面板槽——#43 事故形态）
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(rid, "nebula-root", Json.obj("text" -> Json.fromString("delegate 汇报：任务完成。")))
      )
      notYet <- IO.race(done.get.map(_.toString), IO.sleep(250.millis).as("still-pending"))
      // 用户选中 pickDir（卡片 → askUserAnswer → hub Answered，answers=[路径]）
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(rid, "nebula-root", Json.obj("answers" -> Json.arr(Json.fromString(pickDir.toString))))
      )
      result <- done.get
      pd <- ProjectStore.load("ws-pick-beta")
      rt <- ProjectRuntimeRegistry.get("ws-pick-beta")
      _ <- ProjectRuntimeRegistry.unregister("ws-pick-beta")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        notYet,
        Right("still-pending"),
        "agent-shaped payload must NOT complete the panel slot (#43 semantics — only user answers)"
      )
      assert(result.isRight, s"user pick must complete creation: $result")
      assert(pd.isDefined, "project.json must be created from the picked path")
      assertEquals(pd.get.name, "ws-pick-beta", "name derives from picked path basename")
      assertEquals(pd.get.workspace, pickDir.toString)
      assert(rt.isDefined, "project must be mounted after panel-driven creation")
    end for
  }

  // ============================================================
  // ④ 创建后 Task(project=…) 可触发（真实分发器会话拉起证据）
  // ============================================================

  test("④ 创建后 Task(project=…) 触发分发器：agentStart 带 dispatcher 会话 id（真实拉起）") {
    val ws = tempRoot / "ws-p4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-4-${scala.util.Random.nextInt(100000)}")
    // 令 3（2026-09-12）**契约变更**：turn 完成后分发器会话**保活**
    // （`Defaults.DispatcherIdleWindowMs`，生产 30 min），不再由观察桥即时拆除；
    // 收尾改由 TtlTick 扫描腿在窗口到期时销毁。本用例判据随之改写为
    // 「拉起 ⇒ 保活（窗口内仍注册）⇒ 窗口到期销毁 ⇒ 注销」。
    // 窗口用 `sys.props` 压到 8 s（该 Defaults 条目每次调用现读，spec 可即时翻转；
    // 用完即刻还原，避免污染并发 suite）——等效性依据：只压「窗口 / 节拍」两个常量，
    // 判定（`sweepIdleDispatchers` 的 due 判据）与销毁（`expireIdleDispatcher`）
    // 代码路径逐行未变；节拍由 `ProjectActor.ttlScanner(1.second)` 驱动。
    val propKey = "nebflow.dispatcher.idleWindowMs"
    val propBefore = Option(System.getProperty(propKey))
    val restore: IO[Unit] = IO {
      propBefore match
        case Some(v) => System.setProperty(propKey, v)
        case None => System.clearProperty(propKey)
      ()
    }
    System.setProperty(propKey, "8000")
    ProjectActor
      .ttlScanner(1.second)
      .background
      .use { _ =>
        for
          res <- fullResources(system, new RecordingLlm)
          frames <- Ref.of[IO, List[Json]](Nil)
          created <- ProjectCreateTool.call(
            Json
              .obj("name" -> Json.fromString("trigger-proj"), "workspace" -> Json.fromString(ws.toString))
              .asObject
              .get,
            toolCtx(ws, system, res, wsSend = Some((j: Json) => frames.update(_ :+ j)))
          )
          triggered <- MailTool.call(
            Json
              .obj("address" -> Json.fromString("project:trigger-proj"), "message" -> Json.fromString("冒烟任务"))
              .asObject
              .get,
            toolCtx(ws, system, res)
          )
          // 分发器会话拉起证据：engine wsSend 路由帧 agentStart.nodeSessionId = dispatcher-*
          _ <- waitUntil(30.seconds)(frames.get.map { fs =>
            fs.exists { j =>
              j.hcursor.get[String]("type").toOption.contains("agentStart") &&
              j.hcursor.get[String]("nodeSessionId").toOption.exists(_.startsWith(ProjectActor.DispatcherSessionPrefix))
            }
          })
          // 令 3 保活判据：turn 已由录制 LLM 单 delta 收尾，此刻会话仍须**在场**
          // （不再「完成即拆」——这正是「连续派发复用同一会话」的结构前提）。
          heldRegistered <- res.agentRegistry.get.map(_.keys.exists(_.startsWith(ProjectActor.DispatcherSessionPrefix)))
          // 窗口（8 s）到期 ⇒ TtlTick 扫描腿销毁 ⇒ 注销（链路完整收尾，无幽灵行）
          _ <- waitUntil(30.seconds)(
            res.agentRegistry.get.map(_.keys.forall(!_.startsWith(ProjectActor.DispatcherSessionPrefix)))
          )
          gone <- res.agentRegistry.get.map(_.keys.forall(!_.startsWith(ProjectActor.DispatcherSessionPrefix)))
          _ <- ProjectRuntimeRegistry.unregister("trigger-proj")
          _ <- system.stopAll.handleErrorWith(_ => IO.unit)
        yield
          assert(created.isRight, s"create must succeed: $created")
          assert(triggered.isRight, s"Task must trigger: $triggered")
          assert(triggered.toOption.get.contains("dispatcher triggered"), triggered.toOption.get)
          assert(heldRegistered, "令 3：turn 完成后（窗口内）分发器会话必须保活，而非即时拆除")
          assert(gone, "令 3：窗口到期后必须销毁注销（ghost-row 语义保持）")
        end for
      }
      .guarantee(restore)
  }

  // ============================================================
  // ⑤ 幂等 / 同名冲突语义
  // ============================================================

  test("⑤ 同名同 workspace → 幂等 already-exists；同名异 workspace → 明确报错") {
    val wsA = tempRoot / "ws-dup-a"
    val wsB = tempRoot / "ws-dup-b"
    List(wsA, wsB).foreach(os.makeDir.all)
    val system = ActorSystem(s"pcp-5-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(wsA)
    val mk = (w: os.Path) =>
      Json.obj("name" -> Json.fromString("dup"), "workspace" -> Json.fromString(w.toString)).asObject.get
    for
      first <- ProjectCreateTool.call(mk(wsA), toolCtx(wsA, system, res))
      second <- ProjectCreateTool.call(mk(wsA), toolCtx(wsA, system, res))
      rt <- ProjectRuntimeRegistry.get("dup")
      conflict <- ProjectCreateTool.call(mk(wsB), toolCtx(wsA, system, res))
      _ <- ProjectRuntimeRegistry.unregister("dup")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(first.isRight, s"first create must succeed: $first")
      assert(second.isRight, s"same name + same workspace must be idempotent: $second")
      assert(second.toOption.get.contains("already exists"), second.toOption.get)
      assert(rt.isDefined, "project stays mounted across the idempotent call")
      assert(conflict.isLeft, "same name + different workspace must be rejected")
      val err = conflict.swap.toOption.get.message
      assert(err.contains("different workspace"), s"conflict must be explicit: $err")
      assert(err.contains(wsA.toString), s"conflict must name the existing workspace: $err")
    end for
  }

  // ============================================================
  // ⑥ 取消 / 空答案 / 非绝对路径语义
  // ============================================================

  test("⑥ 取消哨兵与空答案 → 搁置消息不创建；非绝对自由输入 → 明确报错不创建") {
    val ws = tempRoot / "ws-p6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-6-${scala.util.Random.nextInt(100000)}")
    for
      res <- IO(minimalResources(ws))
      before <- ProjectStore.list() // 基线（此前用例可能已建项目）
      hub <- system.spawn(InteractionHub(), "interaction-hub-p6")
      _ <- hub ! InteractionHubCommand.RegisterRoot("nebula-root", (_: Json) => IO.unit)
      gotAsk <- Ref.of[IO, Option[(String, List[nebflow.core.AskItem])]](None)
      agentRef <- panelAgentActor(hub, "nebula-root", gotAsk)(system)
      // (a) 取消哨兵
      doneA <- Deferred[IO, Either[nebflow.core.tools.ToolError, String]]
      fibA <- ProjectCreateTool
        .call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => doneA.complete(r).void)
        .start
      _ <- waitUntil(10.seconds)(gotAsk.get.map(_.isDefined))
      ridA <- gotAsk.get.map(_.map(_._1).getOrElse(fail("rid A missing")))
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(
          ridA,
          "nebula-root",
          Json.obj("answers" -> Json.arr(Json.fromString(ProjectCreateTool.CancelSentinel)))
        )
      )
      resA <- doneA.get
      // (b) 空答案（Other 输入空白即提交）
      _ <- gotAsk.set(None)
      doneB <- Deferred[IO, Either[nebflow.core.tools.ToolError, String]]
      fibB <- ProjectCreateTool
        .call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => doneB.complete(r).void)
        .start
      _ <- waitUntil(10.seconds)(gotAsk.get.map(_.isDefined))
      ridB <- gotAsk.get.map(_.map(_._1).getOrElse(fail("rid B missing")))
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(ridB, "nebula-root", Json.obj("answers" -> Json.arr(Json.fromString(""))))
      )
      resB <- doneB.get
      // (c) 非绝对路径自由输入
      _ <- gotAsk.set(None)
      doneC <- Deferred[IO, Either[nebflow.core.tools.ToolError, String]]
      fibC <- ProjectCreateTool
        .call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => doneC.complete(r).void)
        .start
      _ <- waitUntil(10.seconds)(gotAsk.get.map(_.isDefined))
      ridC <- gotAsk.get.map(_.map(_._1).getOrElse(fail("rid C missing")))
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(ridC, "nebula-root", Json.obj("answers" -> Json.arr(Json.fromString("relative/path"))))
      )
      resC <- doneC.get
      after <- ProjectStore.list()
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(resA.isRight && resA.toOption.get.contains("shelved"), s"cancel → shelved message: $resA")
      assert(resB.isRight && resB.toOption.get.contains("shelved"), s"empty answer → shelved message: $resB")
      assert(resC.isLeft, "relative free input must be rejected")
      assert(resC.swap.toOption.get.message.contains("absolute"), resC.swap.toOption.get.message)
      assertEquals(
        after.map(_.name).toSet,
        before.map(_.name).toSet,
        "no project may be created in any of the three branches"
      )
    end for
  }

  // ============================================================
  // ⑦ 无 agent 会话 + 缺省 path → 立即明确报错（不悬挂）
  // ============================================================

  test("⑦ 缺省 path 且无 agent 会话 → 立即明确报错（harness/REST 直调不悬挂）") {
    val ws = tempRoot / "ws-p7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-7-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      result <- ProjectCreateTool.call(Json.obj().asObject.get, toolCtx(ws, system, res, agentRef = None))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isLeft, "panel requires an agent session — must fail fast")
      assert(result.swap.toOption.get.message.contains("no interactive session"), result.swap.toOption.get.message)
    end for
  }

  // ============================================================
  // ⑧–⑫ S2 2026-09-17 12:09 裁定单：④ 反守卫（默认拒绝）+ ③ 补缺脚手架 + ④-11b
  // ============================================================

  private def sha256Of(p: os.Path): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(os.read.bytes(p)).map(b => f"$b%02x").mkString

  /** 工作区「逐件 sha256」读数（零写盘判据的机械面）。 */
  private def wsSha(ws: os.Path): Map[String, String] =
    List(
      (ws / "AGENTS.md", "AGENTS.md"),
      (ws / ".gitignore", ".gitignore"),
      (ws / ".nebflow" / "flow-map.json", ".nebflow/flow-map.json"),
      (ws / ".nebflow" / "task-board.json", ".nebflow/task-board.json")
    ).flatMap { (p, label) =>
      if os.exists(p) && os.isFile(p) then Some(label -> sha256Of(p)) else None
    }.toMap

  /** `.nebflow/` 内容集合（集合不变判据）。 */
  private def nebflowEntries(ws: os.Path): List[String] =
    val d = ws / ".nebflow"
    if os.exists(d) then os.list(d).map(_.last).toList.sorted else Nil

  private def mkInput(n: String, w: String): io.circe.JsonObject =
    Json.obj("name" -> Json.fromString(n), "workspace" -> Json.fromString(w)).asObject.get

  test("⑧ 新 name × workspace 已被别的 name 占用 → 默认拒绝（可行动报错 + 零写盘）") {
    val ws = tempRoot / "ws-guard-occ"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-8-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      first <- ProjectCreateTool.call(mkInput("occ-one", ws.toString), toolCtx(ws, system, res))
      beforeSha <- IO(wsSha(ws))
      beforeNb <- IO(nebflowEntries(ws))
      second <- ProjectCreateTool.call(mkInput("occ-two", ws.toString), toolCtx(ws, system, res))
      afterSha <- IO(wsSha(ws))
      afterNb <- IO(nebflowEntries(ws))
      newDir <- IO(os.exists(ProjectStore.projectDir("occ-two")))
      _ <- ProjectRuntimeRegistry.unregister("occ-one")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(first.isRight, s"first create must succeed: $first")
      assert(beforeSha.size >= 2, s"workspace must carry the scaffold before the rejected call: $beforeSha")
      assert(second.isLeft, s"反守卫失效：异 name 指向已占用 workspace 竟被放行 ⇒ $second")
      val err = second.swap.toOption.get.message
      // 可行动报错逐字钉住：点名占用者 name + 归一化 workspace + 归档态位 + 两条出路
      // （a 复用既有项目 / b 换工作区目录）。逐字等值 ⇒ 内容要求不可被静默弱化。
      val expectedErr =
        s"Workspace '${ws.toString}' is already used by project 'occ-one' " +
          s"(its project.json workspace = '${ws.toString}'). ProjectCreate default-denies creating 'occ-two' " +
          "on an occupied workspace — a second project on the same workspace would silently share its " +
          "flow-map / task board / worktrees (2026-09-17 裁定 ④-4). Two ways out: " +
          "(a) reuse the existing project — ProjectCreate(name='occ-one') to re-mount it, or " +
          "Mail(address='project:occ-one', message=...) to dispatch work; " +
          "(b) pass a different 'workspace' directory for 'occ-two'."
      assertEquals(err, expectedErr, "占用报错原文必须逐字稳定（可行动四点齐备）")
      assert(!newDir, "拒绝路径必须零写盘：projects/occ-two/ 不得出现")
      assertEquals(afterSha, beforeSha, "拒绝路径不得改动 workspace 任何件（逐件 sha256）")
      assertEquals(afterNb, beforeNb, "拒绝路径不得改动 .nebflow/ 内容集合")
    end for
  }

  test("⑨ 归一化变体（尾斜杠 / 大小写差异）→ 同样被拒（占用判据与幂等判据同一归一函数）") {
    val ws = tempRoot / "ws-guard-norm"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-9-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    val trailing = ws.toString + "/"
    val upper = ws.toString.toUpperCase
    for
      first <- ProjectCreateTool.call(mkInput("norm-one", ws.toString), toolCtx(ws, system, res))
      rTrailing <- ProjectCreateTool.call(mkInput("norm-two", trailing), toolCtx(ws, system, res))
      rUpper <- ProjectCreateTool.call(mkInput("norm-three", upper), toolCtx(ws, system, res))
      d2 <- IO(os.exists(ProjectStore.projectDir("norm-two")))
      d3 <- IO(os.exists(ProjectStore.projectDir("norm-three")))
      _ <- ProjectRuntimeRegistry.unregister("norm-one")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(first.isRight, s"first create must succeed: $first")
      assert(trailing != ws.toString, "变体 ① 必须是不同的字符串（尾斜杠）")
      assert(upper != ws.toString, "变体 ② 必须是不同的字符串（仅大小写差）")
      assert(rTrailing.isLeft, s"尾斜杠变体必须同拒（同一归一函数）⇒ $rTrailing")
      assert(rUpper.isLeft, s"大小写变体必须同拒（大小写不敏感归一）⇒ $rUpper")
      assert(rTrailing.swap.toOption.get.message.contains("norm-one"), "尾斜杠变体报错须点名占用者")
      assert(rUpper.swap.toOption.get.message.contains("norm-one"), "大小写变体报错须点名占用者")
      assert(!d2 && !d3, "两个变体都必须零写盘（projects/<name>/ 不得出现）")
    end for
  }

  test("⑩ 占用者含 archived 定义 → 同拒（保守默认「宁误拒不误建」；占用者本体零改动）") {
    val ws = tempRoot / "ws-guard-arch"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-10-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      first <- ProjectCreateTool.call(mkInput("occ-arch", ws.toString), toolCtx(ws, system, res))
      arch <- ProjectStore.archive("occ-arch")
      beforeSha <- IO(wsSha(ws))
      r <- ProjectCreateTool.call(mkInput("occ-new", ws.toString), toolCtx(ws, system, res))
      afterSha <- IO(wsSha(ws))
      newDir <- IO(os.exists(ProjectStore.projectDir("occ-new")))
      occJson <- IO(os.read(ProjectStore.projectJsonPath("occ-arch")))
      _ <- ProjectRuntimeRegistry.unregister("occ-arch")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(first.isRight, s"first create must succeed: $first")
      assert(arch.isRight, s"archive must succeed: $arch")
      assert(r.isLeft, s"归档占用者也必须被拒（保守默认）⇒ $r")
      val err = r.swap.toOption.get.message
      assert(err.contains("occ-arch"), s"报错须点名归档占用者: $err")
      assert(err.contains("archived"), s"报错须标注占用者归档态: $err")
      assert(!newDir, "拒绝路径零写盘")
      assertEquals(afterSha, beforeSha, "workspace 逐件 sha256 不变")
      assert(occJson.contains("\"archived\""), "占用者（归档）定义本体零改动")
    end for
  }

  test("⑪ 幂等挂载路径补缺脚手架：删 AGENTS.md → 重挂恢复 + 逐件文案；既有件字节不变") {
    val ws = tempRoot / "ws-scaffold-mount"
    os.makeDir.all(ws)
    val gi = ws / ".gitignore"
    os.write.over(gi, "# user rules\n.nebflow/\n") // 预置：已含 .nebflow/ 行（禁重复、禁覆写）
    val system = ActorSystem(s"pcp-11-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      created <- ProjectCreateTool.call(mkInput("scaf-one", ws.toString), toolCtx(ws, system, res))
      _ <- IO(os.remove(ws / "AGENTS.md"))
      giBefore <- IO(sha256Of(gi))
      nbBefore <- IO(nebflowEntries(ws))
      again <- ProjectCreateTool.call(mkInput("scaf-one", ws.toString), toolCtx(ws, system, res))
      giAfter <- IO(sha256Of(gi))
      nbAfter <- IO(nebflowEntries(ws))
      // 新语义（作者 2026-09-18 令：「AGENTS.md 应该默认是空的，用户去写，我们只是创建」；
      // popt W3 已把模板置空 = `ProjectCreateTool.scala:108` `agentMdTemplate = ""`）。
      // 判据随之改为：**文件本体仍被创建**（存在）+ **内容为空**（0 字节），两者缺一即红。
      restoredExists <- IO(os.exists(ws / "AGENTS.md"))
      restoredText <- IO(if os.exists(ws / "AGENTS.md") then os.read(ws / "AGENTS.md") else "<missing>")
      _ <- ProjectRuntimeRegistry.unregister("scaf-one")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(created.isRight, s"first create must succeed: $created")
      assert(again.isRight, s"幂等重挂必须成功: $again")
      val msg = again.toOption.get
      assert(msg.contains("already exists"), s"既有 contains 子串不得破: $msg")
      assert(msg.contains("AGENTS.md created"), s"补缺必须逐件报 created（③-9）: $msg")
      // 🔴 断言不弱化（两类回归仍被抓住，且是「实质判据」而非恒真）：
      //   ① 文件未被创建 ⇒ `restoredExists=false` 首条红，且 `restoredText="<missing>"` 次条亦红；
      //   ② 模板被写回非空 ⇒ `restoredText` 非空，次条红（正是本批要抓的回归类）。
      // 只断 exists 会漏 ②；旧写法 nonEmpty 与「默认空模板」裁定正面对撞（本条即为更新点）。
      assert(restoredExists, s"被删的 AGENTS.md 必须由幂等挂载路径补回（③-8）：文件本体必须存在（exists=$restoredExists）")
      assertEquals(
        restoredText,
        "",
        s"AGENTS.md 默认空模板（作者 2026-09-18 裁定）：补回的文件内容必须为空，实测 ${restoredText.length} 字符"
      )
      assertEquals(giAfter, giBefore, ".gitignore 必须逐字节不变（已含 .nebflow/ 行）")
      assertEquals(
        os.read(gi).linesIterator.count(_.trim == ".nebflow/"),
        1,
        "不得重复追加 .nebflow/ 行"
      )
      assertEquals(nbAfter, nbBefore, ".nebflow/ 内容集合不变")
    end for
  }

  test("⑫ ④-11(b) NodeList 载荷 meta 增 workspace 权威键（既有键名/类型语义不变）") {
    val ws = tempRoot / "ws-nodelist-meta"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-12-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      created <- ProjectCreateTool.call(mkInput("nl-meta", ws.toString), toolCtx(ws, system, res))
      rtOpt <- ProjectRuntimeRegistry.get("nl-meta")
      rt = rtOpt.getOrElse(fail("project must be mounted for the NodeList payload"))
      payload <- NodeTools.buildNodeListPayload(rt, None)
      meta = payload.hcursor.downField("meta")
      _ <- ProjectRuntimeRegistry.unregister("nl-meta")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(created.isRight, s"create must succeed: $created")
      // ④-11(b) 逐键钉住：meta 的键集/类型/值与期望 Json 等值（circe JsonObject 等值与键序无关）
      // ⇒ 既有键名与类型不变 + 新增 workspace = 权威工作区绝对路径，一断言同时钉两面。
      val metaRaw = meta.focus.getOrElse(fail("meta object missing")).noSpaces
      val maskedRaw = metaRaw.replaceAll("\"updatedAt\":[0-9]+", "\"updatedAt\":0")
      val expectedMeta = Json.obj(
        "project" -> Json.fromString("nl-meta"),
        "workspace" -> Json.fromString(ws.toString),
        "updatedAt" -> Json.fromLong(0L),
        "archived" -> Json.fromInt(0)
      )
      assertEquals(
        io.circe.parser.parse(maskedRaw).getOrElse(fail(s"meta is not json: $maskedRaw")),
        expectedMeta,
        s"meta 逐键读数（键名/类型/顺序无关；updatedAt 已 mask）；载荷 meta 原文=$metaRaw"
      )
      assert(payload.hcursor.downField("nodes").as[List[Json]].isRight, "载荷 nodes 骨架不变")
    end for
  }

end ProjectCreatePanelSpec
