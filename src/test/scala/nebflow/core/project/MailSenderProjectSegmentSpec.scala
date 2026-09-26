package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, MailTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * evfmt 批 A 收尾 R-A 补（2026-09-15 ③ root 裁定，engine 面）· **双向钉**：
 * **气泡四段式 header 第 2 段（`PROJECT`）= 发送方实际项目域**，两条同时成立：
 *
 *   (a) 发送方**自带项目** ⇒ 第二段 = 其**实际项目域**（不是根域 `NEBULA`、更不是收件方项目）；
 *   (b) 发送方**不带项目（根域）** ⇒ 第二段 = `NEBULA`（🔴 防过度修正：只做 (a) 一侧 = 判红不成立）。
 *
 * ## 被判缺陷（R-A 登记，来源 = `20260915_evfmt-verify.md` §r3-3 R-A 实测 T6）
 * 取值链 ①「构造点显式置位」**未落位**（`MailTool` 侧不置 `InjectionAttribution.project`）⇒
 * 腿① 走 `ProjectActor.leg1SenderProject` 的根域兜底 ⇒ 项目上下文发送方落帧
 * `MAIL · NEBULA · WORKER-A · INFO`，发送方项目 `PROJ-P6-SRC` 丢失。
 * 本 spec 用**生产构造点**（`MailTool.call`，非喂参直调）触发，故修复前为红。
 *
 * ## 本 spec 钉的三段（每条都走真实引擎路径，非纯函数喂参）
 *   T1 腿①（`MailTool.call("project:<mounted>")` → 分发器收件面 WS 帧）——(a) 侧；
 *   T2 腿① 同路径、发送方无项目上下文（Nebula root 身份）——(b) 侧；
 *   T3 腿③（`MailTool.call("Nebula")` → root 会话的 `ImmediateInput`）——同源置位读数；
 *
 * 读取点 = **WS 帧 `header` 键逐字**（与前端渲染同一串；引擎单一来源
 * `NotificationHeader`，唯一发射点 `AgentActor#emitInjectedUserEvent`）。
 *
 * **本 spec 不证明的**（如实标注）：真实宿主重启后的浏览器 DOM 渲染；腿③ 的整串 header
 * （T3 止于 `ImmediateInput.project` 字段 + 发射点回落链的逐字复刻，root 会话非真 AgentActor）。
 */
class MailSenderProjectSegmentSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-mail-sender-project-segment"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "project-dispatcher")

  os.write.over(
    tempRoot / "agents" / "project-dispatcher" / "agent.json",
    """{"name":"project-dispatcher","description":"R-A 补 pin dispatcher","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "project-dispatcher" / "system.md", "# project-dispatcher\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)
  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** 记录型 LLM：immediate 收尾（零工具调用），只计数已结束 stream。 */
  private class QuietLlm:
    val streamsDone: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)) ++
          Stream.eval(streamsDone.update(_ + 1)).drain

  private def mkResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
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

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def mount(
    name: String,
    system: ActorSystem,
    res: SharedResources,
    frames: Ref[IO, List[Json]]
  ): IO[ProjectRuntime] =
    val ws = tempRoot / name
    os.makeDir.all(ws)
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    ProjectRuntimeRegistry.mount(
      pd,
      system,
      res,
      Some((j: Json) => frames.update(_ :+ j)),
      rootSessionId = "nebula-root",
      ttlCheckIntervalSec = 1,
      dispatcherIdleWindowMs = Some(300_000L)
    )

  end mount

  private def injectedFrames(frames: Ref[IO, List[Json]]): IO[List[Json]] =
    frames.get.map(_.filter(j => j.hcursor.downField("injected").as[Boolean].getOrElse(false)))

  private def headerOf(f: Json): Option[String] =
    f.hcursor.downField("header").as[Option[String]].getOrElse(None)

  private def callMail(ctx: ToolContext, address: String, message: String): IO[String] =
    MailTool
      .call(
        Json
          .obj("address" -> Json.fromString(address), "message" -> Json.fromString(message))
          .asObject
          .get,
        ctx
      )
      .map {
        case Right(s) => s"RIGHT: $s"
        case Left(err) => s"LEFT: ${err.message}"
      }

  /**
   * 腿①（`Mail → project` 分发器收件面）：走**生产构造点** `MailTool.call`，
   * 读回收件会话注入帧的 `header` 整串。
   *
   * @param senderProject 发送方的**项目上下文**（`ToolContext.projectName` = 会话实际项目域，
   *                      `None` = 无项目上下文 / 根域会话）
   * @param senderAgent   发送方 agent 名（`Nebula` ⇒ `roleOf` 判 Root）
   */
  private def runLeg1(
    tag: String,
    senderProject: Option[String],
    senderAgent: String
  ): IO[(String, Option[String])] =
    val system = ActorSystem(s"mail-projseg-$tag-${scala.util.Random.nextInt(100000)}")
    for
      llm <- IO.pure(new QuietLlm)
      frames <- Ref.of[IO, List[Json]](Nil)
      resources <- mkResources(system, llm.handle)
      _ <- mount(s"proj-$tag-dst", system, resources, frames)
      ctx = ToolContext(
        projectRoot = os.pwd.toString,
        sessionId = Some(s"sender-session-$tag"),
        rootSessionId = Some("nebula-root"),
        agentDef = Some(AgentDef(name = senderAgent, description = "", tools = List("Mail"), category = "team")),
        sharedResources = Some(resources),
        actorSystem = Some(system),
        projectName = senderProject,
        isDispatcher = false
      )
      out <- callMail(ctx, s"project:proj-$tag-dst", s"R-A 补 pin $tag")
      _ <- IO.delay(println(s"[$tag-CALL] $out"))
      _ <- waitUntil(60.seconds)(injectedFrames(frames).map(_.nonEmpty))
      fs <- injectedFrames(frames)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val h = fs.map(headerOf).headOption.flatten
      println(s"[$tag-HEADER] ${h.getOrElse("<absent>")}")
      (out, h)

    end for

  end runLeg1

  test("T1 双向钉 (a)：腿① 发送方**自带项目** ⇒ 气泡第二段 = 其实际项目域"):
    runLeg1("t1", Some("PROJ-P6-SRC"), "worker-a").map { (out, h) =>
      assert(out.startsWith("RIGHT:"), s"生产构造点必须放行（非 dispatcher 自带的项目会话可发 project: 腿）：$out")
      assertEquals(
        h,
        Some("MAIL · PROJ-P6-SRC · WORKER-A · INFO"),
        "发送方项目未取到 ⇒ PROJECT 段落到根域 NEBULA（R-A 缺陷形态）"
      )
    }

  test("T2 双向钉 (b)：腿① 发送方**不带项目（根域）** ⇒ 气泡第二段 = NEBULA（防过度修正）"):
    runLeg1("t2", None, "Nebula").map { (out, h) =>
      assert(out.startsWith("RIGHT:"), s"根域发送方发 project: 腿必须放行：$out")
      assertEquals(
        h,
        Some("MAIL · NEBULA · NEBULA · INFO"),
        "根域场景被改动 ⇒ 过度修正（判据要求跨 root 直投件 PROJECT = NEBULA 逐字不变）"
      )
    }

  /**
   * 发射点 `AgentActor#emitInjectedUserEvent` 的 PROJECT 段回落链**逐字复刻**
   * （`project.orElse(sessionProject).orElse(RootProject)`）。
   */
  private def emitReduction(project: Option[String], sessionProject: Option[String]): String =
    NotificationHeader
      .header(
        "mail",
        Some("mail"),
        project.orElse(sessionProject).orElse(Some(NotificationHeader.RootProject)),
        Some("project-dispatcher"),
        None,
        Some("info")
      )
      .getOrElse("<absent>")

  private def mkRecordingActor(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  /**
   * 腿③（分发器 → root）：真 actor 收录 `ImmediateInput`，读**置位字段** `project`
   * （= 气泡 PROJECT 段链首级）+ 发射点回落链复刻读数。
   */
  private def runLeg3(tag: String, senderProject: Option[String]): IO[(String, Option[String], String)] =
    val system = ActorSystem(s"mail-projseg-$tag-${scala.util.Random.nextInt(100000)}")
    for
      record <- Ref.of[IO, List[AgentCommand]](Nil)
      rootRef <- system.spawn(mkRecordingActor(record), s"root-$tag-${scala.util.Random.nextInt(100000)}")
      resources = SharedResources(
        llm = null,
        dispatcher = null,
        sessionStore = null,
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
        agentRegistry = Ref.unsafe[IO, Map[String, AgentRecord]](
          Map("nebula-root-sid" -> AgentRecord("nebula-root-sid", rootRef, AgentKind.Root, "nebula-root-sid"))
        ),
        voiceMutedRef = Ref.unsafe[IO, Boolean](false)
      )
      ctx = ToolContext(
        projectRoot = os.pwd.toString,
        sessionId = Some("dispatcher-sid"),
        rootSessionId = Some("nebula-root-sid"),
        agentDef = Some(AgentDef(name = "project-dispatcher", description = "", category = "standalone")),
        sharedResources = Some(resources),
        actorSystem = Some(system),
        projectName = senderProject,
        isDispatcher = true
      )
      out <- callMail(ctx, "Nebula", s"R-A 补 leg3 $tag")
      cmds <- record.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val imm = cmds.collect { case i: AgentCommand.ImmediateInput => i }
      val got = imm.headOption.flatMap(_.project)
      println(s"[$tag-CALL] $out | ImmediateInput#=${imm.size} project=${got.getOrElse("<None>")}")
      // ⑥ 发射点回落链复刻（会话侧 = root 会话：无项目上下文 ⇒ sessionProject=None）
      (out, got, emitReduction(got, None))

    end for

  end runLeg3

  test("T3 同源置位：腿③（分发器 → root）`ImmediateInput.project` = 发送方项目 / 根域回落 None"):
    for
      withProject <- runLeg3("t3a", Some("PROJ-P6-SRC"))
      rootDomain <- runLeg3("t3b", None)
    yield
      val (outA, gotA, emittedA) = withProject
      assert(outA.startsWith("RIGHT:"), s"腿③ 投递必须成功（可解析 root）：$outA")
      assertEquals(gotA, Some("PROJ-P6-SRC"), "腿③ 未置位发送方项目 ⇒ 气泡 PROJECT 段落到 root 会话侧")
      assertEquals(emittedA, "MAIL · PROJ-P6-SRC · PROJECT-DISPATCHER · INFO", "发射点回落链复刻成串")
      val (outB, gotB, emittedB) = rootDomain
      assert(outB.startsWith("RIGHT:"), s"腿③ 根域发送方投递必须成功：$outB")
      assertEquals(gotB, None, "无项目上下文时不得臆造项目名（禁静默填空）")
      assertEquals(emittedB, "MAIL · NEBULA · PROJECT-DISPATCHER · INFO", "根域场景第二段 = NEBULA 逐字不变")

end MailSenderProjectSegmentSpec
