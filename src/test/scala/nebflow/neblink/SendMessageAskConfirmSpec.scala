package nebflow.neblink

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.{
  AgentDef,
  AgentLibrary,
  InteractionHub,
  InteractionHubCommand,
  InteractionAnswered,
  SendConfirm,
  SharedResources,
  SubAgentTaskStore
}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, FriendMessageTool, ToolContext, ToolError}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * #147 接线段（2026-09-12）：`SendMessage` ask 档确认链的**端到端**钉子。
 *
 * 事实锚（作者 2026-09-11 裁定 U-5）：`askConfirm` 全仓未接线 ⇒ ask 档一调即
 * `Left("ask mode requires a confirmation callback (not wired)")`。
 *
 * 本 spec 用**真实组件**跑判据（禁静态论证）：
 *  - 真实 `InteractionHub` actor —— 帧面读数 = 注册在 hub 上的 root wsSend 收到的
 *    每一帧（生产里该 wsSend 是 `WebSocketRoutes.registerRootInteraction` 的
 *    recordingWsSend，同形）；
 *  - 真实 `FriendService`（经**装配缝** `NeblinkWiring.friendService` 构造，含
 *    GatewayMain 传的 `askConfirm`）+ 真实 `FriendMessageTool.call`；
 *  - 真实答复路径 = `InteractionHubCommand.Answered`（`WebSocketRoutes` 把前端
 *    `askUserAnswer{sessionId, answers, requestId}` 翻成的就是它，
 *    `WebSocketRoutes.scala:1066-1076`）；
 *  - 传输缝 stub（`FriendMessageToolSpec.StubClient` 同款，零网络）：投递读数 =
 *    它记录的 `POST /api/friends/<uid>/messages`（不投递 = 记录为空）。
 *
 * 判据：① 正向（出卡→批准→投递）；② 负控（拒绝/取消/自由文本 ⇒ 零投递）；
 * ③ 零回归（auto/off 新旧入口逐字相同且确认面零调用）；④ 超时（零投递 + 可判定
 * + 卡片撤回）；⑤ 无交互面（显式失败，禁静默）。
 */
class SendMessageAskConfirmSpec extends CatsEffectSuite:

  override val munitIOTimeout = 180.seconds

  private val RootSid = "root-1"
  private val FriendsJson =
    """{"friends":[{"userId":"u1","username":"customNL1","display_name":"林小满"}],"incoming":[],"outgoing":[]}"""

  // ── 传输缝 stub（零网络；记录发送路径）────────────────────────────
  private class StubClient:
    val postedPaths = scala.collection.mutable.ListBuffer.empty[String]

    val client = new NeblinkClient(
      NeblinkServerConfig(url = "http://stub.local", networkId = "n1", secret = "s"),
      serverPort = 1
    ):
      override protected def sendRequest(
        method: String,
        url: String,
        body: String,
        token: Option[String]
      ): IO[Either[String, String]] =
        if url.endsWith("/api/device/login") then
          IO.pure(Right("""{"token":"tok-1","networkId":"n1","deviceId":"d1","peers":[]}"""))
        else if url.endsWith("/api/friends") && method == "GET" then IO.pure(Right(FriendsJson))
        else if url.endsWith("/messages") then
          IO { postedPaths += url } *> IO.pure(Right("""{"messageId":5,"conversationId":"c1","createdAt":123}"""))
        else IO.pure(Left(s"unexpected request: $method $url"))

    def login(): Unit =
      client
        .login("dev-1", "TestMac", "macos", List(NeblinkEndpoint("10.0.0.5", 1, "lan")))
        .unsafeRunSync()

  /** 从不被调用的 LLM（本 spec 不跑 agent turn；仅满足 SharedResources 构造）。 */
  private object DeadLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("llm not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] = Stream.empty

  private case class Fixture(
    system: nebflow.actor.ActorSystem,
    resources: SharedResources,
    hub: nebflow.actor.ActorRef[InteractionHubCommand],
    wsEvents: Ref[IO, List[Json]],
    stub: StubClient,
    fs: FriendService,
    ctx: ToolContext,
    cleanup: IO[Unit]
  )

  /** 装配：真实 hub + 真实装配缝 FriendService + 真实工具 + 帧记录 wsSend。 */
  private def setup(
    name: String,
    mode: String = "ask",
    hubPresent: Boolean = true,
    guard: FriendMessagingGuard = new FriendMessagingGuard()
  ): IO[Fixture] =
    val system = nebflow.actor.ActorSystem(s"sendconfirm-$name")
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "data")
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
      hubRef <- IO.ref(Option.empty[nebflow.actor.ActorRef[InteractionHubCommand]])
      resources = SharedResources(
        llm = DeadLlm,
        dispatcher = dispatcher,
        sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
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
        voiceMutedRef = voiceMuted,
        interactionHubRef = hubRef
      )
      wsEvents <- IO.ref(List.empty[Json])
      hub <- system.spawn(InteractionHub(), s"hub-$name")
      _ <- if hubPresent then hubRef.set(Some(hub)) else IO.unit
      // 生产同形：WebSocketRoutes 在 root 会话订阅时注册它的 recordingWsSend
      _ <- hub ! InteractionHubCommand.RegisterRoot(RootSid, (j: Json) => wsEvents.update(_ :+ j))
      stub = StubClient()
      _ <- IO(stub.login())
      // 装配缝（唯一生产入口）：GatewayMain 在此传 askConfirm
      fs = NeblinkWiring.friendService(
        IO.pure(Some(stub.client)),
        AgentMessagingConfig(mode = mode),
        guard = guard,
        askConfirm = Some(SendConfirm.NoInteractiveSurface)
      )
      _ <- IO(FriendMessageTool.initialize(fs))
      ctx = ToolContext(
        projectRoot = tmp.toString,
        sessionId = Some(RootSid),
        rootSessionId = Some(RootSid),
        sessionName = Some("spec"),
        agentDef = Some(AgentDef(name = "Nebula", description = "spec fixture")),
        sharedResources = Some(resources),
        actorSystem = Some(system)
      )
    yield Fixture(
      system,
      resources,
      hub,
      wsEvents,
      stub,
      fs,
      ctx,
      cleanup = system.stopAll.attempt.void *> IO(os.remove.all(tmp)).attempt.void
    )

  private val input: JsonObject =
    JsonObject("to" -> "customNL1".asJson, "message" -> "hi from spec".asJson)

  private def isAskUser(j: Json): Boolean = j.hcursor.get[String]("type").toOption.contains("askUser")

  private def waitFor[A](ref: Ref[IO, A], pred: A => Boolean, msg: String, timeoutMs: Long = 20000): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      ref.get.flatMap { a =>
        if pred(a) then IO.unit
        else if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError(s"$msg in time"))
        else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  /** 等第 n 张 askUser 卡（1-based）并取其帧。 */
  private def waitForAsk(f: Fixture, n: Int = 1): IO[Json] =
    waitFor(f.wsEvents, evs => evs.count(isAskUser) >= n, s"第 $n 张确认卡未渲染") *>
      f.wsEvents.get.map(_.filter(isAskUser).apply(n - 1))

  private def answer(f: Fixture, requestId: String, answerText: String): IO[Unit] =
    f.hub ! InteractionHubCommand.Answered(
      InteractionAnswered(requestId, RootSid, Json.obj("answers" -> Json.arr(Json.fromString(answerText))))
    )

  private def frameCount(f: Fixture, tpe: String): IO[Int] =
    f.wsEvents.get.map(_.count(j => j.hcursor.get[String]("type").toOption.contains(tpe)))

  // ═══════════════ ① 正向：出卡 → 批准 → 投递 ═══════════════

  test("① ask 档正向：出确认卡（帧面）→ 用户批准 → 投递（POST 计数 1）；批准前零投递") {
    setup("positive").flatMap { f =>
      (for
        call <- FriendMessageTool.call(input, f.ctx).start
        frame <- waitForAsk(f)
        _ <- IO {
          assertEquals(frame.hcursor.get[String]("sessionId"), Right(RootSid), "卡片必须落在提问会话的窗口")
          assertEquals(frame.hcursor.get[String]("sourceAgent"), Right("Nebula"), "来源标注 = 提问 agent")
          assertEquals(frame.hcursor.get[String]("agentName"), Right("Nebula"))
          val q = frame.hcursor.downField("items").downArray.downField("question").as[String].toOption.getOrElse("")
          assert(q.contains("林小满（customNL1）"), s"卡面必须点名收件人: $q")
          assert(q.contains("hi from spec"), s"卡面必须带正文预览: $q")
          val labels = frame.hcursor.downField("items").downArray.downField("options").as[List[Json]].toOption
            .getOrElse(Nil)
            .flatMap(_.hcursor.downField("label").as[String].toOption)
          assertEquals(labels, List(SendConfirm.ApproveLabel, SendConfirm.DeclineLabel), "两选项卡（批准/拒绝）")
        }
        _ <- IO(assertEquals(f.stub.postedPaths.toList, Nil, "批准之前绝不允许投递"))
        _ <- IO.println(s"[FRAME-askUser] ${frame.noSpaces}")
        requestId = frame.hcursor.get[String]("requestId").toOption.get
        _ <- IO(assert(requestId.nonEmpty, "确认请求必须带 requestId（答复路由键）"))
        _ <- answer(f, requestId, SendConfirm.ApproveLabel)
        res <- call.joinWithNever
        _ <- IO(assert(res.isRight, s"批准后必须投递成功，got ${res.left.toOption.map(_.message)}"))
        _ <- IO(
          assertEquals(
            f.stub.postedPaths.toList,
            List("http://stub.local/api/friends/u1/messages"),
            "批准后恰一次投递（username→userId 回映射 + POST 计数 = 1）"
          )
        )
        _ <- IO(assert(res.toOption.get.contains("已发送给 林小满（customNL1）"), s"回执 = ${res.toOption.get}"))
        _ <- IO.println(s"[RESULT-approved] ${res.toOption.get} | POSTs=${f.stub.postedPaths.toList}")
      yield ()).guarantee(f.cleanup)
    }
  }

  // ═══════════════ ② 负控：拒绝 → 零投递 ═══════════════

  test("② 负控：用户拒绝 → 零投递，文案 = declined（严禁静默当批准）") {
    setup("negative").flatMap { f =>
      (for
        call <- FriendMessageTool.call(input, f.ctx).start
        frame <- waitForAsk(f)
        _ <- answer(f, frame.hcursor.get[String]("requestId").toOption.get, SendConfirm.DeclineLabel)
        res <- call.joinWithNever
        _ <- IO {
          assert(res.isLeft, "拒绝必须返回失败（不得当批准）")
          assert(res.left.toOption.get.message.contains("declined"), s"got ${res.left.toOption.get.message}")
          assertEquals(f.stub.postedPaths.toList, Nil, "拒绝 ⇒ 零投递（负控）")
        }
      yield ()).guarantee(f.cleanup)
    }
  }

  test("② 负控（变体）：取消（__cancelled__）与自由文本一律不投递（fail-closed）") {
    setup("negative-cancel").flatMap { f =>
      (for
        call1 <- FriendMessageTool.call(input, f.ctx).start
        frame1 <- waitForAsk(f, 1)
        _ <- answer(f, frame1.hcursor.get[String]("requestId").toOption.get, "__cancelled__")
        res1 <- call1.joinWithNever
        _ <- IO(assert(res1.isLeft, "取消不得投递"))
        call2 <- FriendMessageTool.call(input, f.ctx).start
        frame2 <- waitForAsk(f, 2)
        _ <- answer(f, frame2.hcursor.get[String]("requestId").toOption.get, "ok 随便吧")
        res2 <- call2.joinWithNever
        _ <- IO {
          assert(res2.isLeft, "非批准标签一律不投递（fail-closed）")
          assertEquals(f.stub.postedPaths.toList, Nil, "取消/自由文本 ⇒ 零投递")
        }
      yield ()).guarantee(f.cleanup)
    }
  }

  // ═══════════════ ③ 零回归：auto / off ═══════════════

  test("③ 零回归 auto：新旧入口行为逐字相同，且确认回调一次都不被调用、零确认帧") {
    setup("auto-regression", mode = "auto").flatMap { f =>
      (for
        calls <- IO.ref(0)
        confirm = Some((_: String) => calls.update(_ + 1).as(true))
        // pre-change 入口 = 2 参（改前生产代码的形态）；新入口 = 3 参（本次新增）
        legacy <- f.fs.sendAsAgent("u1", "legacy")
        postsAfterLegacy <- IO(f.stub.postedPaths.toList)
        current <- f.fs.sendAsAgent("u1", "current", confirm)
        res <- FriendMessageTool.call(input, f.ctx)
        n <- calls.get
        askFrames <- frameCount(f, "askUser")
        posts <- IO(f.stub.postedPaths.toList)
        _ <- IO {
          assertEquals(legacy, Right("Message sent"), "auto 档 2 参入口行为不变（改前形态）")
          assertEquals(current, Right("Message sent"), "auto 档 3 参入口行为不变")
          assertEquals(postsAfterLegacy.size, 1, "auto 档直发：2 参入口 POST 恰一次")
          assert(res.isRight, s"工具层 auto 档直发不受影响: ${res.left.toOption.map(_.message)}")
          assertEquals(n, 0, "auto 档绝不诉诸确认（确认面零调用 = 无新语义）")
          assertEquals(askFrames, 0, "auto 档零确认帧")
          assertEquals(posts.size, 3, s"三路 auto 直发各一次 POST: $posts")
        }
      yield ()).guarantee(f.cleanup)
    }
  }

  test("③ 零回归 off：两入口都拒绝且零投递、零确认") {
    setup("off-regression", mode = "off").flatMap { f =>
      (for
        calls <- IO.ref(0)
        confirm = Some((_: String) => calls.update(_ + 1).as(true))
        a <- f.fs.sendAsAgent("u1", "legacy")
        b <- f.fs.sendAsAgent("u1", "current", confirm)
        res <- FriendMessageTool.call(input, f.ctx)
        n <- calls.get
        askFrames <- frameCount(f, "askUser")
        _ <- IO {
          assertEquals(a, Left("User has disabled agent messaging"), "off 档 2 参入口行为不变")
          assertEquals(b, Left("User has disabled agent messaging"), "off 档 3 参入口行为不变")
          assert(res.isLeft, "工具层 off 档仍拒绝")
          assertEquals(f.stub.postedPaths.toList, Nil, "off 档零投递")
          assertEquals(n, 0, "off 档绝不诉诸确认")
          assertEquals(askFrames, 0, "off 档零确认帧")
        }
      yield ()).guarantee(f.cleanup)
    }
  }

  test("③ auto 超限降级档：确认链同样接通（改前该档也必失败）") {
    setup("auto-downgrade", mode = "auto", guard = new FriendMessagingGuard(perFriendPerHour = 0)).flatMap { f =>
      (for
        call <- FriendMessageTool.call(input, f.ctx).start
        frame <- waitForAsk(f)
        _ <- IO(assertEquals(f.stub.postedPaths.toList, Nil, "降级 ask ⇒ 先确认，后投递"))
        _ <- answer(f, frame.hcursor.get[String]("requestId").toOption.get, SendConfirm.ApproveLabel)
        res <- call.joinWithNever
        _ <- IO {
          assert(res.isRight, s"降级档批准后必须投递，got ${res.left.toOption.map(_.message)}")
          assertEquals(f.stub.postedPaths.toList, List("http://stub.local/api/friends/u1/messages"))
        }
      yield ()).guarantee(f.cleanup)
    }
  }

  // ═══════════════ ④ 超时：零投递 + 可判定 + 卡片撤回 ═══════════════

  test("④ 超时（确认未回）：零投递 + 可判定文案 + 卡片精确撤回（askUserClosed 帧）") {
    setup("timeout").flatMap { f =>
      val prop = SendConfirm.TimeoutProperty
      (for
        _ <- IO(System.setProperty(prop, "300"))
        call <- FriendMessageTool.call(input, f.ctx).start
        frame <- waitForAsk(f)
        requestId = frame.hcursor.get[String]("requestId").toOption.get
        res <- call.joinWithNever
        _ <- IO {
          assert(res.isLeft, "超时必须失败（禁静默本地执行/静默成功）")
          val msg = res.left.toOption.get.message
          assert(msg.contains("timed out"), s"超时必须可判定（文案点名超时），got $msg")
          assert(msg.contains("NOT sent"), s"文案必须明确未发送，got $msg")
          assertEquals(f.stub.postedPaths.toList, Nil, "超时 ⇒ 零投递")
        }
        _ <- waitFor(
          f.wsEvents,
          evs =>
            evs.exists(j =>
              j.hcursor.get[String]("type").toOption.contains("askUserClosed") &&
                j.hcursor.get[String]("requestId").toOption.contains(requestId)
            ),
          "超时后卡片未被撤回（askUserClosed 帧缺席 ⇒ 僵尸卡）"
        )
        closed <- f.wsEvents.get.map(_.filter(j => j.hcursor.get[String]("type").toOption.contains("askUserClosed")))
        _ <- IO {
          assertEquals(closed.size, 1, "恰一次撤回广播")
          assertEquals(closed.head.hcursor.get[String]("reason"), Right("caller-withdrew"))
        }
        _ <- IO.println(s"[RESULT-timeout] ${res.left.toOption.get.message}")
        _ <- IO.println(s"[FRAME-askUserClosed] ${closed.head.noSpaces}")
        // 撤回后才点的卡（迟到答复）不得复活投递
        _ <- answer(f, requestId, SendConfirm.ApproveLabel)
        _ <- IO.sleep(300.millis)
        _ <- IO(assertEquals(f.stub.postedPaths.toList, Nil, "撤回后的迟到答复不得投递"))
      yield ()).guarantee(IO(System.clearProperty(prop)) *> f.cleanup)
    }
  }

  // ═══════════════ ⑤ 无交互面：显式失败（fail-closed）═══════════════

  test("⑤ 无交互面（hub 未起）：ask 档显式失败、零投递、零静默") {
    setup("no-hub", hubPresent = false).flatMap { f =>
      (for
        res <- FriendMessageTool.call(input, f.ctx)
        _ <- IO {
          assert(res.isLeft, "无交互面必须显式失败")
          val msg = res.left.toOption.get.message
          assert(msg.contains("Confirmation failed") && msg.contains("NOT sent"), s"got $msg")
          assertEquals(f.stub.postedPaths.toList, Nil, "无交互面 ⇒ 零投递（禁静默本地执行）")
        }
      yield ()).guarantee(f.cleanup)
    }
  }

  test("⑤ 无会话上下文（ToolContext 无 sharedResources，如 REST 直调）：装配缝默认值 fail-closed") {
    setup("no-ctx").flatMap { f =>
      val bare = ToolContext(projectRoot = "/tmp")
      (for
        res <- FriendMessageTool.call(input, bare)
        _ <- IO {
          assert(res.isLeft, "无交互面必须显式失败")
          val msg = res.left.toOption.get.message
          assert(msg.contains("Confirmation failed") && msg.contains("NOT sent"), s"got $msg")
          assertEquals(f.stub.postedPaths.toList, Nil, "零投递")
        }
      yield ()).guarantee(f.cleanup)
    }
  }

end SendMessageAskConfirmSpec
