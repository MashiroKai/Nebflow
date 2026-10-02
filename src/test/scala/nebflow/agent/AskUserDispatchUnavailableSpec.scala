package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{
  ActorRef,
  ActorSystem,
  AgentCommand,
  AgentDef,
  AgentKind,
  AgentRecord,
  AgentStatus,
  InteractionHubCommand
}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{AskUserQuestionTool, FileLockManager, ProjectCreateTool, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  AskUserDispatch,
  ContentBlock,
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  Message,
  MessageRole,
  PathUtil,
  StreamChunk,
  ThinkingConfig,
  ToolCall
}

import scala.concurrent.duration.*

/**
 * 承接面缺席（`interactionHubRef = None`）时的**派发失败信号**端到端读数
 * （rework round 1 的唯一边界内应改点）。
 *
 * 前提：`AgentProcessing` 的 `AskUser` 分支在 hub 缺席腿**不再**以空载荷
 * （`! Nil`）回 `replyTo` —— 空载荷的两个订阅腿都会把它读成「用户没答」：
 * 非阻塞桥会把它当用户答复注入会话（会话里凭空一条空用户消息），面板腿会把它
 * 当「用户关闭了面板」（`Shelved` 搁置消息）。本 spec 的两个用例分别钉住这两条
 * 订阅腿的**新口径**，每条都带可分离的红锚：
 *
 *  1. **面板腿**（真 `ProjectCreateTool.pathPanel` + 真 `AgentActor` + hub 缺席）：
 *     派发失败 ⇒ 工具结果里有可判读失败码 [[AskUserDispatch.UnavailableCode]]
 *     （自描述：问题从未上卡 / 答案永不投达 / 这不是用户答复），且**不含**
 *     `shelved` 搁置文案（返工前口径 = `Shelved` ⇒ 本用例转红）。
 *  2. **桥腿**（真 `AskUserQuestionTool` 非阻塞档 + 真 `AgentActor` + hub 缺席）：
 *     `AgentProcessing` 缺席腿自动投给桥的失败信号**零注入**（无含失败码的用户
 *     消息、无额外 LLM 轮）；同一桥函数吃**真答复**时照常注入（阳性对照 ⇒ 证明
 *     桥的注入腿在本 fixture 里是活的，第 2 条读数不是「什么都没发生」的空转）。
 *
 * 无 hub ⇒ 无卡片、无 pending 槽、无窗口；「答案到达」这条腿在本 fixture 里
 * 结构性不可达（这正是缺席腿的存在理由），故第 2 条读数的失败信号由
 * `AgentProcessing` 缺席腿在工具派发时自动投出（不是测试自造）。
 */
class AskUserDispatchUnavailableSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  /** 脚本化 LLM：首轮吐工具调用（入参由用例给定），后续轮文本收尾（gate = 观察窗）。 */
  private class ScriptedLlm(
    requests: Ref[IO, List[LlmRequest]],
    tool: String,
    toolInput: JsonObject,
    gate: Deferred[IO, Unit]
  ) extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)).drain ++
        Stream.eval(requests.get.map(_.size)).flatMap { n =>
          if n <= 1 then
            Stream(
              StreamChunk.ToolCallChunk(ToolCall("tu-1", tool, toolInput)),
              StreamChunk.Done(None, None)
            )
          else
            Stream.eval(gate.get).drain ++
              Stream(StreamChunk.TextDelta("turn-done"), StreamChunk.Done(None, None))
        }

  end ScriptedLlm

  /**
   * 🔴 本 spec 的判据面 = **hub 缺席** ⇒ 不设 `interactionHubRef`（缺省 `None`）、
   * 不 spawn hub。其余字段与真实装配同形（先例 `AskUserDualModeRuntimeSpec.mkResources`）。
   */
  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
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
      voiceMutedRef = voiceMuted
    )

  private def waitFor[A](
    ref: Ref[IO, A],
    pred: A => Boolean,
    msg: String,
    timeoutMs: Long = 20000
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      ref.get.map(pred).flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError(s"$msg in time"))
          else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  private case class Fixture(
    system: ActorSystem,
    resources: SharedResources,
    actor: ActorRef[AgentCommand],
    sid: String,
    requests: Ref[IO, List[LlmRequest]],
    releaseTurn: IO[Unit],
    cleanup: IO[Unit]
  )

  private def setup(
    name: String,
    tool: String,
    toolInput: JsonObject,
    holdTurn: Boolean = true
  ): Fixture =
    val system = ActorSystem(s"dispatch-unavailable-$name")
    val sid = s"dispatch-unavailable-$name"
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "data")
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    val program = for
      requests <- IO.ref(List.empty[LlmRequest])
      gate <- Deferred[IO, Unit]
      _ <- if holdTurn then IO.unit else gate.complete(()).void
      resources <- mkResources(system, tmp, new ScriptedLlm(requests, tool, toolInput, gate))
      actor <- system.spawn(
        AgentActor(
          agentDef = AgentDef(name = "Nebula", description = "", tools = List(tool)),
          resources = resources,
          wsSend = _ => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(sid),
          sessionName = Some(s"fixture-$name"),
          safetyMode = "auto-all"
        ),
        s"agent-$name"
      )
      now <- IO(System.currentTimeMillis())
      _ <- resources.agentRegistry.update(
        _ + (sid -> AgentRecord(
          sessionId = sid,
          ref = actor,
          kind = AgentKind.Root,
          rootSessionId = sid,
          parentRef = None,
          startedAt = now,
          status = AgentStatus.Processing,
          lastActivityMs = now
        ))
      )
      _ <- actor ! AgentCommand.UserInput("ask me now", None, Some(s"cmid-$name"))
    yield Fixture(
      system,
      resources,
      actor,
      sid,
      requests,
      releaseTurn = gate.complete(()).void,
      cleanup = IO {
        nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
        PathUtil.setDataRoot(prevRoot)
      } *> system.stopAll.attempt.void *> IO(os.remove.all(tmp)).attempt.void
    )
    try program.unsafeRunSync()
    catch
      case e: Throwable =>
        nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
        PathUtil.setDataRoot(prevRoot)
        system.stopAll.attempt.void.unsafeRunSync()
        os.remove.all(tmp)
        throw e

  end setup

  /** 从 LLM 请求里取最后一次 tool_result 文本（工具返回值 = 模型的可见反馈）。 */
  private def lastToolResults(reqs: List[LlmRequest]): List[String] =
    reqs.lastOption.toList.flatMap { r =>
      r.messages.reverse
        .collectFirst {
          case m if m.role == MessageRole.User =>
            m.content.toOption.toList.flatMap(_.collect { case t: ContentBlock.ToolResult => t.content })
        }
        .getOrElse(Nil)
    }

  /**
   * 「注入气泡」文本 = 用户消息里**不带 tool_result 块**的文本（工具结果同挂
   * User 角色，必须排除 —— 面板腿的失败文案正是随工具结果回到模型的那一份，
   * 我们是拿它当**应当存在**的读数）。
   */
  private def injectedUserTexts(msgs: List[Message]): List[String] =
    msgs.filter(_.role == MessageRole.User).flatMap { m =>
      m.content match
        case Left(t) => List(t)
        case Right(blocks) =>
          if blocks.exists(_.isInstanceOf[ContentBlock.ToolResult]) then Nil
          else blocks.collect { case ContentBlock.Text(t) => t }
    }

  private def allMessages(reqs: List[LlmRequest]): List[Message] = reqs.flatMap(_.messages)

  private def statusOf(resources: SharedResources, sid: String): IO[AgentStatus] =
    resources.agentRegistry.get.map(_(sid).status)

  private def assertHubAbsent(resources: SharedResources): IO[Unit] =
    resources.interactionHubRef.get.map { h =>
      assert(h.isEmpty, s"本 spec 的前提 = 承接面缺席；实测 hub 存在（$h）⇒ 读数不成立")
    }

  // ============================================================
  // 1. 面板腿（阻塞等答复的调用方）：派发失败 ⇒ 明确报错，不是「用户关闭面板」
  // ============================================================

  test("hub 缺席 ⇒ 面板腿给可判读失败码（不是 shelved），失败信号不冒充用户答复") {
    val f = setup("panel", ProjectCreateTool.name, JsonObject.empty)
    (for
      _ <- assertHubAbsent(f.resources)
      // 面板在自己的 fiber 里 .? 等待答复 ⇒ 派发失败信号即其**返回值**
      _ <- waitFor(f.requests, _.size >= 2, "面板派发后 turn 未继续（疑似被挂住）")
      reqs <- f.requests.get
      results <- IO(lastToolResults(reqs))
      msg = results.mkString(" | ")
      _ = assert(
        msg.contains(AskUserDispatch.UnavailableCode),
        s"面板腿未给出派发失败码 ${AskUserDispatch.UnavailableCode}：$msg"
      )
      _ = assert(
        msg.contains("could not be dispatched") && msg.contains("never shown") && msg.contains("no answer can arrive"),
        s"面板腿失败文案不可判读（缺「未派发 / 未上卡 / 答案永不投达」自描述）：$msg"
      )
      // 判别力（红锚）：失败信号不得落进**路径解析**腿 —— 那样虽也报错，但把
      // 「派发失败」误读成「用户给了一个不可用路径」（返工前的 `Shelved` 是同一
      // 误读的另一形态）。失效判据 = isFailure 退化（恒 false）⇒ 本条转红。
      _ = assert(
        !msg.contains("is not usable"),
        s"派发失败信号落进了路径解析腿（被误读成用户给的路径不可用）：$msg"
      )
      _ = assert(
        !msg.toLowerCase.contains("shelved"),
        s"派发失败被当成「用户关闭了面板」搁置（返工前口径）：$msg"
      )
      // hub 缺席腿不标等待态（waitMarks 在真派发腿内）⇒ 不可能造出永不解除的等待
      st <- statusOf(f.resources, f.sid)
      _ = assert(
        st != AgentStatus.WaitingForUser,
        s"承接面缺席却标了 WaitingForUser（$st）—— 造出永不解除的等待"
      )
      // 收尾：失败信号不得把会话挂在半路（answerLanded 配对恢复仍可达）
      _ <- f.releaseTurn
      _ <- waitFor(
        f.resources.agentRegistry,
        m => m.get(f.sid).exists(_.status == AgentStatus.Idle),
        "失败信号后 turn 未收尾"
      )
      _ <- IO.sleep(700.millis)
      reqs2 <- f.requests.get
      injected = injectedUserTexts(allMessages(reqs2))
      _ = assertEquals(reqs2.size, 2, s"面板腿失败后发生了额外 LLM 轮（注入？）：${reqs2.size}")
      _ = assert(
        !injected.exists(_.contains(AskUserDispatch.UnavailableCode)),
        s"派发失败信号被注入成用户消息（通话里凭空多一条气泡）：$injected"
      )
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

  // ============================================================
  // 2. 桥腿（非阻塞工具）：失败信号零注入 + 真答复照常注入（阳性对照）
  // ============================================================

  test("hub 缺席 ⇒ 桥腿对派发失败信号零注入；同一桥函数吃真答复时照常注入（对照）") {
    val askInput: JsonObject =
      JsonObject(
        "questions" -> Json.arr(
          Json.obj(
            "question" -> "运行态派发失败验证：选一个".asJson,
            "options" -> Json.arr(Json.obj("label" -> "alpha".asJson), Json.obj("label" -> "beta".asJson))
          )
        )
      )
    val f = setup("bridge", AskUserQuestionTool.Name, askInput)
    (for
      _ <- assertHubAbsent(f.resources)
      _ <- waitFor(f.requests, _.size >= 2, "工具提问后 turn 未继续（非阻塞 ack 未回投）")
      reqs1 <- f.requests.get
      toolResults <- IO(lastToolResults(reqs1))
      ack = toolResults.find(_.contains("non-blocking:")).getOrElse(fail(s"未拿到非阻塞 ack：$toolResults"))
      requestId = """requestId=(asknb-[0-9a-f]+)""".r
        .findFirstMatchIn(ack)
        .map(_.group(1))
        .getOrElse(fail(s"ack 缺 requestId：$ack"))
      // 观察窗内：`AgentProcessing` 的缺席腿把失败信号投给桥（同一次派发的
      // replyTo）⇒ 桥按其**独立判据**处置失败。
      // 桥的注入腿在本 fixture 里结构性不可达（hub 缺席 ⇒ 槽位/答复腿不存在，
      // 真答复只能由测试直接投给桥——那正是下面阳性对照所做的事）。因此这里的
      // 读数是**等价可直接观测**的桥臂结果：
      //  · 失败信号被识别 ⇒ 只留 WARN、零注入（本 fixture 下表现为零额外 LLM 轮）；
      //  · 若判据退化（isFailure 恒 false）⇒ 失败信号被当成答复注入 ⇒ 桥会在
      //    turn 结束后投 `ImmediateInput` 唤醒新轮（含失败码 / 空文本气泡）⇒ 红。
      _ <- IO.sleep(700.millis)
      reqsDuring <- f.requests.get
      _ = assertEquals(
        reqsDuring.size,
        2,
        s"失败信号在观察窗内就造出了新 LLM 轮（被当成答复注入）：${reqsDuring.size}"
      )
      // 收尾 turn
      _ <- f.releaseTurn
      _ <- waitFor(
        f.resources.agentRegistry,
        m => m.get(f.sid).exists(_.status == AgentStatus.Idle),
        "turn 未收尾"
      )
      _ <- IO.sleep(1000.millis)
      reqs2 <- f.requests.get
      injected2 = injectedUserTexts(allMessages(reqs2))
      _ = assertEquals(reqs2.size, 2, s"派发失败信号造出了额外 LLM 轮：${reqs2.size}（注入面）")
      _ = assert(
        !injected2.exists(_.contains(AskUserDispatch.UnavailableCode)),
        s"派发失败信号被当成用户答复注入：$injected2"
      )
      // 注入面（不带 tool_result 的用户文本）不得出现空串 —— 空载荷冒充答复的旧
      // 形态正是「凭空一条空用户消息」（`formatAnswer(items, Nil)` 单题 ⇒ `""`）。
      _ = assert(
        !injected2.exists(_.trim.isEmpty),
        s"会话里出现了空文本用户消息（空载荷冒充答复的旧形态）：$injected2"
      )
      // ── 阳性对照：同一桥构造（同 items/requestId/ctx）吃**真答复** ⇒ 照常注入 ──
      items = AskUserQuestionTool.parseItems(askInput("questions").flatMap(_.asArray).getOrElse(Nil))
      ctx = ToolContext(
        projectRoot = os.pwd.toString,
        sessionId = Some(f.sid),
        sharedResources = Some(f.resources),
        agentActorRef = Some(f.actor)
      )
      bridge = AskUserAnswerBridge.ref(f.actor, items, requestId, ctx)
      _ <- bridge ! List("alpha")
      _ <- waitFor(f.requests, _.size >= 3, "真答复未唤醒新 turn（桥的注入腿在本 fixture 里不活）")
      reqs3 <- f.requests.get
      injected3 = injectedUserTexts(allMessages(reqs3))
      _ = assert(
        injected3.exists(_.contains("alpha")),
        s"真答复未被注入成用户消息（对照腿失效）：$injected3"
      )
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

end AskUserDispatchUnavailableSpec
