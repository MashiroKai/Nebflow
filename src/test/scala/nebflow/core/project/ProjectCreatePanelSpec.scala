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
import nebflow.core.tools.{FileLockManager, ProjectCreateTool, TaskTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * ProjectCreate「未知路径 AskUser 式交互面板」全链验收（阶段2迁移 §6.2 口径②）。
 *
 * 口径/语义映射：
 *  ① 已知路径直建（test ①）——name 缺省 = basename、脚手架、幂等挂载、Task 提示；
 *  ② 未知/缺省 path → AskUser 式面板 pending 产生（test ②，真实 InteractionHub）；
 *  ③ 面板点选 → 创建链走通（test ③，含 #43 兼容断言：agent 侧负载不消费面板槽）；
 *  ④ 创建后 Task(project=…) 可触发（test ④，真实分发器会话拉起证据）；
 *  ⑤ 幂等 / 同名异 workspace 冲突语义（test ⑤）；
 *  ⑥ 取消哨兵 / 空答案 → 明确搁置不创建；非绝对路径 → 明确报错（test ⑥）；
 *     无 agent 会话时缺省 path → 立即报错不悬挂（test ⑦）。
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

  /** 桩 agent actor：只复刻 AgentActor.AskUser handler 的转发职责——收到
    * AgentCommand.AskUser → 记录 (requestId, items)（面板载荷断言面）→ 组
    * items 载荷投真实 InteractionHub（pending 槽 + 根窗口渲染帧由此产生）。 */
  private def panelAgentActor(
      hub: ActorRef[InteractionHubCommand],
      rootSid: String,
      gotAsk: Ref[IO, Option[(String, List[nebflow.core.AskItem])]]
  )(system: ActorSystem): IO[ActorRef[AgentCommand]] =
    lazy val behavior: Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand] {
      case AgentCommand.AskUser(requestId, items, replyToOpt) =>
          gotAsk.set(Some((requestId, items))) *>
            (hub ! InteractionHubCommand.Request(
              InteractionRequest(
                requestId = requestId,
                kind = InteractionKind.AskUser,
                payload = Json.obj(
                  "items" -> Json.arr(items.map { it =>
                    Json.obj(
                      "question" -> Json.fromString(it.question),
                      "options" -> Json.arr(it.options.map(o => Json.obj("label" -> Json.fromString(o.label)))*),
                      "allowOther" -> Json.fromBoolean(it.allowOther)
                    )
                  }*),
                  "agentName" -> Json.fromString("Nebula")
                ),
                reply = InteractionReply.AskUserReply(replyToOpt),
                rootSessionId = rootSid,
                sourceAgent = "Nebula",
                sourceSession = rootSid
              )
            )).void.as(behavior)
        case _ => IO.pure(behavior)
      }
    system.spawn(behavior, s"panel-agent-${scala.util.Random.nextInt(100000)}")

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

  test("① 已知路径直建：project.json 字段落盘 + 脚手架 + 幂等挂载 + Task 提示") {
    val ws = tempRoot / "ws-alpha"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pcp-1-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    val input = Json.obj(
      "workspace" -> Json.fromString(ws.toString),
      "description" -> Json.fromString("panel spec direct create")
    ).asObject.get
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
      assert(msg.contains("Task(project='ws-alpha'"), s"success message must carry the Task usage hint: $msg")
      assert(pd.isDefined, "project.json must be persisted")
      val defn = pd.get
      assertEquals(defn.name, "ws-alpha", "name must derive from workspace basename when omitted")
      assertEquals(defn.workspace, ws.toString)
      assertEquals(defn.description, Some("panel spec direct create"))
      assertEquals(defn.agentFile, (ws / "AGENTS.md").toString)
      assert(defn.createdAt > 0, "createdAt must be set")
      assert(os.exists(ws / "AGENTS.md"), "workspace AGENTS.md scaffold must exist")
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
      fib <- ProjectCreateTool.call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => done.complete(r).void).start
      // pending 证据：面板载荷已派发（items 断言面）+ 根窗口渲染帧已发出（hub 状态面）
      _ <- waitUntil(10.seconds)(gotAsk.get.map(_.isDefined))
      _ <- waitUntil(10.seconds)(frames.get.map(_.exists(_.hcursor.downField("type").as[String].toOption.contains("askUser"))))
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
      assertEquals(items.head.options, List.empty, "no candidate options — in-app browser is the selection surface (2026-09-09)")
      assert(items.head.dirPicker, "workspace card must carry dirPicker=true (2026-09-05 作者裁定)")
      assert(items.head.question.contains("选择工作区"), "question must guide to the in-app browser")
      assert(items.head.question.contains("~"), "question must keep the manual-input (~) fallback")
      val askFrame = fs.find(_.hcursor.downField("type").as[String].toOption.contains("askUser")).get
      val frameOptions = askFrame.hcursor.downField("items").as[List[Json]].toOption.get.head
        .hcursor.downField("options").as[List[Json]].toOption.get
      assertEquals(frameOptions, Nil, "rendered frame must carry empty options")
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
      fib <- ProjectCreateTool.call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => done.complete(r).void).start
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
    for
      res <- fullResources(system, new RecordingLlm)
      frames <- Ref.of[IO, List[Json]](Nil)
      created <- ProjectCreateTool.call(
        Json.obj("name" -> Json.fromString("trigger-proj"), "workspace" -> Json.fromString(ws.toString)).asObject.get,
        toolCtx(ws, system, res, wsSend = Some((j: Json) => frames.update(_ :+ j)))
      )
      triggered <- TaskTool.call(
        Json.obj("project" -> Json.fromString("trigger-proj"), "task" -> Json.fromString("冒烟任务")).asObject.get,
        toolCtx(ws, system, res)
      )
      // 分发器会话拉起证据：engine wsSend 路由帧 agentStart.nodeSessionId = dispatcher-*
      _ <- waitUntil(30.seconds)(frames.get.map { fs =>
        fs.exists { j =>
          j.hcursor.get[String]("type").toOption.contains("agentStart") &&
          j.hcursor.get[String]("nodeSessionId").toOption.exists(_.startsWith(ProjectActor.DispatcherSessionPrefix))
        }
      })
      // 录制 LLM 单 delta 即终态 → 观察桥拆除（registry 清空）——链路完整收尾
      _ <- waitUntil(30.seconds)(res.agentRegistry.get.map(_.keys.forall(!_.startsWith(ProjectActor.DispatcherSessionPrefix))))
      _ <- ProjectRuntimeRegistry.unregister("trigger-proj")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(created.isRight, s"create must succeed: $created")
      assert(triggered.isRight, s"Task must trigger: $triggered")
      assert(triggered.toOption.get.contains("dispatcher triggered"), triggered.toOption.get)
    end for
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
      fibA <- ProjectCreateTool.call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => doneA.complete(r).void).start
      _ <- waitUntil(10.seconds)(gotAsk.get.map(_.isDefined))
      ridA <- gotAsk.get.map(_.map(_._1).getOrElse(fail("rid A missing")))
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(ridA, "nebula-root", Json.obj("answers" -> Json.arr(Json.fromString(ProjectCreateTool.CancelSentinel))))
      )
      resA <- doneA.get
      // (b) 空答案（Other 输入空白即提交）
      _ <- gotAsk.set(None)
      doneB <- Deferred[IO, Either[nebflow.core.tools.ToolError, String]]
      fibB <- ProjectCreateTool.call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => doneB.complete(r).void).start
      _ <- waitUntil(10.seconds)(gotAsk.get.map(_.isDefined))
      ridB <- gotAsk.get.map(_.map(_._1).getOrElse(fail("rid B missing")))
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(ridB, "nebula-root", Json.obj("answers" -> Json.arr(Json.fromString(""))))
      )
      resB <- doneB.get
      // (c) 非绝对路径自由输入
      _ <- gotAsk.set(None)
      doneC <- Deferred[IO, Either[nebflow.core.tools.ToolError, String]]
      fibC <- ProjectCreateTool.call(Json.obj().asObject.get, toolCtx(ws, system, res, Some(agentRef)))
        .flatMap(r => doneC.complete(r).void).start
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
      assertEquals(after.map(_.name).toSet, before.map(_.name).toSet, "no project may be created in any of the three branches")
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

end ProjectCreatePanelSpec
