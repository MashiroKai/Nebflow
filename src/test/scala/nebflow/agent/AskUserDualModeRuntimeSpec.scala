package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{
  AgentCommand,
  AgentDef,
  AgentKind,
  AgentRecord,
  AgentStatus,
  InteractionAnswered,
  messages,
  status
}
import nebflow.actor.InteractionHubCommand
import nebflow.actor.{ActorPath, ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.mail.SessionMailbox
import nebflow.core.tools.{AskHostLedger, AskHostReplyTool, AskHostTool, AskUserQuestionTool, FileLockManager, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  FallbackAttempt,
  InteractionRequestId,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  PathUtil,
  StreamChunk,
  ThinkingConfig,
  ToolCall
}

import scala.concurrent.duration.*

/**
 * // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-B/M4):命令 ADT 下沉 actor、requestId 生成器下沉 shared。
 * // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M4):生成器已下沉 shared。
 *
 * **非阻塞-only 零漂移**（root 2026-10-02 令 #462 裁①/②/④；改写自「工具面按角色
 * 分化批」的双模式验收，**强度不降**）：真实 `AgentActor` + 真实 `InteractionHub`
 * + 真实 `AskUserQuestionTool`（脚本化 LLM 驱动工具调用，先例
 * `WaitTimeoutAskUserWiringSpec`）。五条读数：
 *
 *  1. **root 唯一可 dispatch**：Nebula root 身份的 AskUser 实际创建卡片/槽位，且答复
 *     经 hub → 桥（[[AskUserAnswerBridge]]）→ `ImmediateInput(fromUser=true)` 唤醒新 turn。
 *  2. **后台执行 agent 明确拒绝**：执行 agent 调用 AskUser 得到 root-only tool error，
 *     不创建卡片/槽位，且拒绝后第二轮 turn 正常完成（不靠等待超时制造通过）。
 *  3. **无窗口但仍是 root**：root 会话窗口未注册时，AskUser 仍由 hub 挂 pending。
 *  4. **`mode` 参数被显式拒绝**：真实链上带 `mode` 的调用得到可判读 `ToolError`，
 *     且**零副作用**（无槽位、无卡片、turn 未挂起）。
 *  5. **请求体口径**：root/执行身份的 LlmRequest.tools 里 AskUserQuestion 段与注册表基线
 *     **逐字节一致**（无 schema 变体残留）。
 */
class AskUserDualModeRuntimeSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  /**
   * 脚本化 LLM：首轮吐 AskUserQuestion 工具调用（入参由用例注入），后续轮次文本收尾。
   * `gate` = 观察窗（先例 `WaitTimeoutAskUserWiringSpec` 的 secondGate）：未放行时
   * turn 停在第二轮 LLM 调用上 ⇒「非阻塞不标 WaitingForUser」可在**确定的时间窗内**
   * 读数（否则 turn 秒完，turn-end 兜底会把状态刷回 Idle，读数变空转）。
   *
   * 节点实际执行的 agent 名（P1-3 归因修正 2026-10-03）：旧断言用手搓
   * `AgentDef("general")` / `("kernel")` 造身份，两名都不在 `BuiltinAgents.Names`
   * ⇒ 落 `legacyFixedTools` catch-all，实测报 `AskUserQuestion 不在该会话工具面内`
   * ⇒ 该 spec 全族假红。生产路径按 `BuiltinAgents.ExecutorName` 落名
   * （NodeEditTool createNode）⇒ 对齐名集即修根因，不是放宽断言。
   */
  private val executorName: String = nebflow.core.entity.BuiltinAgents.ExecutorName

  private class ScriptedAskLlm(
    requests: Ref[IO, List[LlmRequest]],
    askInput: JsonObject,
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
              StreamChunk.ToolCallChunk(ToolCall("tu-ask", AskUserQuestionTool.Name, askInput)),
              StreamChunk.Done(None, None)
            )
          else Stream.eval(gate.get).drain ++ Stream(StreamChunk.TextDelta("turn-done"), StreamChunk.Done(None, None))
        }

  end ScriptedAskLlm

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
      hubRef <- IO.ref(Option.empty[ActorRef[InteractionHubCommand]])
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
      voiceMutedRef = voiceMuted,
      interactionHubRef = hubRef
    )

  private def waitFor[A](ref: Ref[IO, A], pred: A => Boolean, msg: String, timeoutMs: Long = 20000): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      ref.get.map(pred).flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError(s"$msg in time"))
          else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  /** hub 只读快照（B6/多槽读数的同一入口）。 */
  private def pendingAsks(hub: ActorRef[InteractionHubCommand], sid: String): IO[List[Json]] =
    for
      d <- Deferred[IO, List[Json]]
      probe = new ActorRef[List[Json]]:
        val path: ActorPath = ActorPath("__pending-probe", Nil)
        def !(m: List[Json]): IO[Unit] = d.complete(m).void
        def ?[Q](mk: ActorRef[Q] => List[Json], t: Option[FiniteDuration]): IO[Q] =
          IO.raiseError(new UnsupportedOperationException("probe ref accepts no asks"))
      _ <- hub ! InteractionHubCommand.ListPendingAsks(sid, probe)
      list <- d.get
    yield list

  /** 命令式轮询：直到 hub 快照满足 pred（避免读早于 hub 处理 Request 的竞态）。 */
  private def pendingAsksUntil(
    hub: ActorRef[InteractionHubCommand],
    sid: String,
    pred: List[Json] => Boolean,
    msg: String,
    timeoutMs: Long = 20000
  ): IO[List[Json]] =
    def go(deadline: Long): IO[List[Json]] =
      pendingAsks(hub, sid).flatMap { l =>
        if pred(l) then IO.pure(l)
        else if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError(s"$msg in time"))
        else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  private case class Fixture(
    system: ActorSystem,
    resources: SharedResources,
    hub: ActorRef[InteractionHubCommand],
    actor: ActorRef[AgentCommand],
    sid: String,
    wsEvents: Ref[IO, List[Json]],
    requests: Ref[IO, List[LlmRequest]],
    releaseTurn: IO[Unit],
    cleanup: IO[Unit]
  )

  /**
   * 装配：hub（可选注册 root 窗口）+ 真实 AgentActor（Nebula/depth=0 或 general/depth=1）。
   * `holdTurn` = 把 turn 停在第二轮 LLM 调用上（观察窗，见 [[ScriptedAskLlm]]）。
   */
  private def setup(
    name: String,
    agentDef: AgentDef,
    depth: Int,
    sid: String,
    askInput: JsonObject,
    registerWindow: Boolean = true,
    holdTurn: Boolean = false
  ): Fixture =
    val system = ActorSystem(s"dualmode-$name")
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
      resources <- mkResources(system, tmp, new ScriptedAskLlm(requests, askInput, gate))
      wsEvents <- IO.ref(List.empty[Json])
      hub <- system.spawn(InteractionHub(), s"hub-$name")
      _ <- resources.interactionHubRef.set(Some(hub))
      _ <-
        if registerWindow then hub ! InteractionHubCommand.RegisterRoot(sid, (j: Json) => wsEvents.update(_ :+ j))
        else IO.unit
      actor <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = j => wsEvents.update(_ :+ j),
          depth = depth,
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
          kind = if depth == 0 then AgentKind.Root else AgentKind.Delegate,
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
      hub,
      actor,
      sid,
      wsEvents,
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

  private val askInputNonBlocking: JsonObject =
    JsonObject(
      "questions" -> Json.arr(
        Json.obj(
          "question" -> "运行态非阻塞验证：选一个".asJson,
          "options" -> Json.arr(Json.obj("label" -> "alpha".asJson), Json.obj("label" -> "beta".asJson))
        )
      )
    )

  /** 既有调用面携带已下线的 `mode` 参数 ⇒ 显式拒绝（本批的兼容判据）。 */
  private val askInputWithRetiredMode: JsonObject =
    JsonObject(
      "questions" -> Json.arr(
        Json.obj(
          "question" -> "带 mode 的调用：选一个".asJson,
          "options" -> Json.arr(Json.obj("label" -> "alpha".asJson), Json.obj("label" -> "beta".asJson))
        )
      ),
      "mode" -> "non-blocking".asJson
    )

  /** 无 `mode` 的常规入参（两身份共用同一份 —— 本批起不再有模式分叉）。 */
  private val askInputBlocking: JsonObject = askInputNonBlocking

  /** 从 LLM 请求里取最后一次 tool_result 文本（工具返回值 = 模型的可见反馈）。 */
  private def lastToolResults(reqs: List[LlmRequest]): List[String] =
    reqs.lastOption.toList.flatMap { r =>
      r.messages.reverse
        .collectFirst {
          case m if m.role == nebflow.shared.MessageRole.User =>
            m.content.toOption.toList.flatMap(_.collect { case t: nebflow.shared.ContentBlock.ToolResult =>
              t.content
            })
        }
        .getOrElse(Nil)
    }

  private def statusOf(resources: SharedResources, sid: String): IO[AgentStatus] =
    resources.agentRegistry.get.map(_(sid).status)

  private def waitForStatus(resources: SharedResources, sid: String, expected: AgentStatus): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      statusOf(resources, sid).flatMap {
        case `expected` => IO.unit
        case current if System.currentTimeMillis() >= deadline =>
          IO.raiseError(new AssertionError(s"session $sid did not reach $expected; last status=$current"))
        case _ => IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + 20000)

  // ============================================================
  // 1. root 非阻塞全链
  // ============================================================

  test("root 非阻塞: 发起即返回（ack 进工具结果）+ 卡片渲染 + 槽位 +1 + 不标 WaitingForUser + 答复回投唤醒新 turn") {
    val f = setup(
      "root-nonblock",
      AgentDef(name = "Nebula", description = "", tools = Nil),
      depth = 0,
      sid = "dualmode-root",
      askInput = askInputNonBlocking,
      holdTurn = true
    )
    (for
      // ① 发起即返回：第二轮 LLM 调用携带工具结果 = ack（turn 没有被挂住）
      _ <- waitFor(f.requests, _.size >= 2, "turn 未继续（第二轮 LLM 调用未发生）—— 疑似被静默改走阻塞")
      reqs1 <- f.requests.get
      toolResults <- IO(lastToolResults(reqs1))
      ack = toolResults.find(_.contains("non-blocking:")).getOrElse(fail(s"未拿到非阻塞 ack：$toolResults"))
      // 🔴 锚点迁移（2026-09-16 specdrift 批 2 · C15）：`#250 第五项 requestId 熵强化`（单点
      // 生成器，作用域前缀 `asknb-`）后，requestId = `asknb-` + 16 hex（调用点
      // `AskUserQuestionTool.scala:409-411`，生成器 `InteractionRequestId.forAskUserNonBlocking`）。
      // 旧正则 `requestId=([0-9a-f]+)` 只能从 `asknb-…` 里捞到首字符 `a` ⇒ 期望值随之错（假红）；
      // 下一条断言（卡片 requestId == 本值）即「同步期望值」腿。
      requestId = """requestId=(asknb-[0-9a-f]+)""".r
        .findFirstMatchIn(ack)
        .map(_.group(1))
        .getOrElse(fail(s"ack 缺 requestId：$ack"))
      // 判据强度只增不减：随机段长度逐字钉到契约常量 `InteractionRequestId.RandomHexChars`
      // （熵强化正是本条契约的本体 —— 旧版只比对「正则捞到的串 == 卡片串」，熵退化不会被发现）。
      _ = assertEquals(
        requestId.length,
        "asknb-".length + InteractionRequestId.RandomHexChars,
        s"requestId 随机段必须 = InteractionRequestId.RandomHexChars（读数 $requestId）"
      )
      // ② 卡片渲染（同一 requestId）+ 槽位 +1
      _ <- waitFor(f.wsEvents, _.exists(j => j.hcursor.get[String]("type").toOption.contains("askUser")), "无 askUser 帧")
      frames <- f.wsEvents.get
      card = frames.filter(_.hcursor.get[String]("type").toOption.contains("askUser")).last
      _ = assertEquals(card.hcursor.get[String]("requestId").toOption, Some(requestId))
      _ = assertEquals(card.hcursor.get[String]("sessionId").toOption, Some(f.sid))
      // 槽位可见 ⇒ `AgentActor` 的 `waitMarks *> hub ! Request` 已按序执行完
      // （标记若存在，此刻必已落库）——下面的状态读数因此是确定性读数而非竞态读数。
      pend1 <- pendingAsksUntil(f.hub, f.sid, _.size == 1, "非阻塞卡未占槽位")
      _ = assertEquals(pend1.size, 1, s"非阻塞卡未占槽位（多槽模型）: $pend1")
      // ③ registry 不出现 WaitingForUser（D2：非阻塞从未等待）——观察窗内读数：
      // turn 仍停在第二轮 LLM 调用（gate 未放行）⇒ 不会有 turn-end 兜底把
      // WaitingForUser 刷回 Idle，故本条是**确定性**断言（变异「恒标等待态」必红）。
      st1 <- statusOf(f.resources, f.sid)
      _ = assert(st1 != AgentStatus.WaitingForUser, s"非阻塞派发后 registry 被标 WaitingForUser（$st1）—— D2 被破坏")
      // 放行 turn（观察窗结束）
      _ <- f.releaseTurn
      // ④ 答复 → hub → 桥 → ImmediateInput(fromUser=true) → 新 turn，模型看到答案
      _ <- f.hub ! InteractionHubCommand.Answered(
        InteractionAnswered(requestId, f.sid, Json.obj("answers" -> Json.arr("alpha".asJson)))
      )
      _ <- waitFor(f.requests, _.size >= 3, "答复未唤醒新 turn（答案未回投）")
      reqs2 <- f.requests.get
      // 答案以**用户消息**形态到达（D5：不是工具结果；文本腿 = Left(text)）
      injected = reqs2.last.messages.reverse
        .find { m =>
          m.role == nebflow.shared.MessageRole.User && (m.content match
            case Left(t) => t.contains("alpha")
            case Right(blocks) =>
              blocks.exists {
                case nebflow.shared.ContentBlock.Text(t) => t.contains("alpha")
                case _ => false
              })
        }
        .getOrElse(fail(s"新 turn 里看不到答案文本：${reqs2.last.messages.takeRight(3)}"))
      _ = assertEquals(injected.source, None, "fromUser=true 的答案必须不带注入 source（呈现为普通 user 气泡）")
      pend2 <- pendingAsksUntil(f.hub, f.sid, _.isEmpty, "答复后槽位未回收")
      _ = assertEquals(pend2.size, 0, s"答复后槽位未回收: $pend2")
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

  // ============================================================
  // 2. 后台身份 root-only 拒绝：零副作用且拒绝后 turn 正常收尾
  // ============================================================

  test("后台执行 agent AskUser 被 root-only 拒绝：零卡片/槽位，第二轮 turn 正常完成") {
    val f = setup(
      "node-root-only",
      AgentDef(name = executorName, description = "", tools = Nil),
      depth = 1,
      sid = "dualmode-node",
      askInput = askInputNonBlocking
    )
    (for
      _ <- waitFor(f.requests, _.size >= 2, "拒绝 AskUser 后 turn 未进入第二轮")
      _ <- waitForStatus(f.resources, f.sid, AgentStatus.Idle)
      reqs <- f.requests.get
      toolResults <- IO(lastToolResults(reqs))
      _ = assert(
        toolResults.exists(_.contains("Tool not available: AskUserQuestion")),
        s"后台面必须在工具授权层拒绝 AskUserQuestion：$toolResults"
      )
      _ <- waitFor(f.requests, _.size >= 2, "拒绝后第二轮 LLM 请求未完成/发出")
      _ <- f.releaseTurn
      frames <- f.wsEvents.get
      _ = assertEquals(
        frames.count(_.hcursor.get[String]("type").toOption.contains("askUser")),
        0,
        s"后台身份拒绝后仍渲染 AskUser 卡片：$frames"
      )
      pend <- pendingAsks(f.hub, f.sid)
      _ = assertEquals(pend.size, 0, s"后台身份拒绝后却创建 hub 槽位：$pend")
      st <- statusOf(f.resources, f.sid)
      _ = assert(st != AgentStatus.WaitingForUser, s"拒绝的后台 turn 留在 WaitingForUser（$st）")
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

  test("后台执行 agent 通过 AskHost 将关联请求投递到 root mailbox") {
    val system = ActorSystem("askhost-root-route")
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "data")
    val previousRoot = PathUtil.dataRoot
    val previousLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    val check = for
      requests <- IO.ref(List.empty[LlmRequest])
      gate <- Deferred[IO, Unit]
      _ <- gate.complete(()).void
      resources <- mkResources(system, tmp, new ScriptedAskLlm(requests, askInputNonBlocking, gate))
      rootRef <- system.spawn(Behaviors.receiveMessage[AgentCommand](_ => IO.pure(Behaviors.ignore)), "askhost-root")
      now <- IO(System.currentTimeMillis())
      _ <- resources.agentRegistry.update(_ + ("askhost-root-session" -> AgentRecord(
        sessionId = "askhost-root-session", ref = rootRef, kind = AgentKind.Root,
        rootSessionId = "askhost-root-session", parentRef = None, startedAt = now,
        status = AgentStatus.Processing, lastActivityMs = now
      )))
      delegateRef <- system.spawn(Behaviors.receiveMessage[AgentCommand](_ => IO.pure(Behaviors.ignore)), "askhost-delegate")
      _ <- resources.agentRegistry.update(_ + ("askhost-delegate-session" -> AgentRecord(
        sessionId = "askhost-delegate-session", ref = delegateRef, kind = AgentKind.Delegate,
        rootSessionId = "askhost-root-session", parentRef = None, startedAt = now,
        status = AgentStatus.Processing, lastActivityMs = now
      )))
      context = ToolContext(
        projectRoot = tmp.toString,
        sessionId = Some("askhost-worker-session"),
        rootSessionId = Some("askhost-delegate-session"),
        agentDef = Some(AgentDef(name = executorName, description = "", tools = Nil)),
        depth = 1,
        sharedResources = Some(resources),
        toolCallId = "askhost-call-1"
      )
      fiber <- AskHostTool.call(
        JsonObject("question" -> "Choose a safe option".asJson), context
      ).start
      pending <- waitForMailbox("askhost-root-session")
      (injected, held) <- MailboxDrain.drainBarrierFace("askhost-root-session", outstanding = 1)
      _ = assertEquals(held, 0)
      _ = assertEquals(injected.map(_.source), List("askhost"))
      _ = assert(MailboxDrain.mailboxMessage(injected).toString.contains("Choose a safe option"))
      entry = pending.head
      event = entry.event
      _ = assertEquals(event.source, "askhost")
      _ = assertEquals(event.eventType, "request")
      _ = assertEquals(event.metadata("workerSession").flatMap(_.asString), Some("askhost-worker-session"))
      _ = assertEquals(event.metadata("rootSession").flatMap(_.asString), Some("askhost-root-session"))
      _ = assertEquals(event.metadata("sourceAgent").flatMap(_.asString), Some(executorName))
      _ = assertEquals(event.metadata("engineOwned").flatMap(_.asBoolean), Some(true))
      correlationId = event.correlationId.getOrElse(fail(s"AskHost event lacks correlation: $event"))
      _ = assertEquals(event.metadata("correlationId").flatMap(_.asString), Some(correlationId))
      _ = assert(entry.identity.contains(correlationId), s"mailbox identity not correlated: $entry")
      rootContext = ToolContext(projectRoot = tmp.toString, sessionId = Some("askhost-root-session"),
        agentDef = Some(AgentDef(name = "Nebula", description = "", tools = Nil)), depth = 0,
        sharedResources = Some(resources))
      replyInput = JsonObject("correlationId" -> correlationId.asJson, "decision" -> "Use option A".asJson)
      firstReply <- AskHostReplyTool.call(replyInput, rootContext)
      result <- fiber.joinWithNever
      _ <- AskHostLedger.markDelivered(correlationId)
      duplicateReply <- AskHostReplyTool.call(replyInput, rootContext)
      conflictReply <- AskHostReplyTool.call(JsonObject("correlationId" -> correlationId.asJson, "decision" -> "different".asJson), rootContext)
      _ = assert(firstReply.isRight && duplicateReply.isRight, s"duplicate reply failed: $duplicateReply")
      _ = assert(conflictReply.isLeft, "conflicting decisions must not be silently accepted")
      _ = assertEquals(result, Right("Use option A"), s"correlated AskHost reply did not complete the worker call (correlation=$correlationId)")
    yield ()
    check.guarantee(IO {
      nebflow.core.LlmLogWriter.setEnabled(previousLlmLog)
      PathUtil.setDataRoot(previousRoot)
      AskHostLedger.clearForTests()
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    }).unsafeRunSync()
  }

  test("AskHost falls back to the unique trusted Nebula root when ancestry is unavailable") {
    val system = ActorSystem("askhost-fallback")
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    val check = for
      requests <- IO.ref(List.empty[LlmRequest])
      gate <- Deferred[IO, Unit]
      resources <- mkResources(system, tmp, new ScriptedAskLlm(requests, askInputNonBlocking, gate))
      pair <- resources.sessionStore.ensureAgentSession("Nebula")
      root = pair._1.id
      ref <- system.spawn(Behaviors.receiveMessage[AgentCommand](_ => IO.pure(Behaviors.ignore)), "fallback-root")
      _ <- resources.agentRegistry.update(_ + (root -> AgentRecord(root, ref, AgentKind.Root, root)))
      context = ToolContext(projectRoot = tmp.toString, sessionId = Some("worker"), rootSessionId = Some("missing-parent"),
        agentDef = Some(AgentDef(name = executorName, description = "", tools = Nil)), depth = 1,
        sharedResources = Some(resources), toolCallId = "fallback-call")
      fiber <- AskHostTool.call(JsonObject("question" -> "fallback question".asJson), context).start
      pending <- waitForMailbox(root)
      id = pending.head.event.correlationId.get
      rootContext = ToolContext(projectRoot = tmp.toString, sessionId = Some(root),
        agentDef = Some(AgentDef(name = "Nebula", description = "", tools = Nil)), depth = 0, sharedResources = Some(resources))
      reply <- AskHostReplyTool.call(JsonObject("correlationId" -> id.asJson, "decision" -> "approved".asJson), rootContext)
      result <- fiber.joinWithNever
      _ = assert(reply.isRight)
      _ = assertEquals(result, Right("approved"))
      _ = println(s"ASKHOST_FALLBACK root=$root correlation=$id reply=$result")
    yield ()
    check.guarantee(IO {
      PathUtil.setDataRoot(previous)
      AskHostLedger.clearForTests()
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    }).unsafeRunSync()
  }

  test("AskHost timeout wakes the worker and closes the pending request") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (request, waiter) <- AskHostLedger.beginForCall("worker", "root", "agent", "question", "timeout-call")
      result <- AskHostTool.awaitReply(request.correlationId, waiter, 20.millis)
      rows <- AskHostLedger.recover
      _ = assert(result.isLeft, s"expected timeout: $result")
      _ = assert(rows.isEmpty)
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("real worker actor executes AskHost and persists the original tool result") {
    val system = ActorSystem("askhost-real-actor")
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "data")
    val previousRoot = PathUtil.dataRoot
    val previousLlmLog = nebflow.core.LlmLogWriter.isEnabled
    PathUtil.setDataRoot(tmp / "data")
    nebflow.core.LlmLogWriter.setEnabled(false)
    val check = for
      seen <- Deferred[IO, Unit]
      llm = new LlmHandle[IO]:
        def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("unexpected send"))
        def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
          Stream.eval(seen.tryGet).flatMap {
            case None => Stream.eval(seen.complete(())).drain ++ Stream(
              StreamChunk.ToolCallChunk(ToolCall("real-ask", "AskHost", JsonObject("question" -> "choose".asJson))),
              StreamChunk.Done(None, None))
            case Some(_) => Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
          }
      resources <- mkResources(system, tmp, llm)
      rootRef <- system.spawn(Behaviors.receiveMessage[AgentCommand](_ => IO.pure(Behaviors.ignore)), "root-probe")
      worker <- system.spawn(AgentActor(agentDef = AgentDef(name = executorName, description = "", tools = Nil),
        resources = resources, wsSend = _ => IO.unit, depth = 1, parentRef = None,
        sessionId = Some("real-worker"), rootSessionId = "real-root", safetyMode = "auto-all"), "real-worker")
      now <- IO(System.currentTimeMillis())
      _ <- resources.agentRegistry.set(Map(
        "real-root" -> AgentRecord(sessionId = "real-root", ref = rootRef, kind = AgentKind.Root, rootSessionId = "real-root", startedAt = now),
        "real-worker" -> AgentRecord(sessionId = "real-worker", ref = worker, kind = AgentKind.Delegate, rootSessionId = "real-root", startedAt = now)))
      _ <- worker ! AgentCommand.UserInput("begin", None, Some("real-input"))
      entries <- waitForMailbox("real-root")
      id = entries.head.identity
      recovered <- AskHostLedger.recoverPending(resources)
      _ = assertEquals(recovered, 1)
      _ <- AskHostLedger.answer(id, "approved")
      _ <- waitForPersistedAskResult(resources, "real-worker")
      transcript <- resources.sessionStore.loadMessagesForSession("real-worker")
      _ = assert(transcript.exists(_.content.toOption.exists(_.exists {
        case r: nebflow.shared.ContentBlock.ToolResult => r.toolUseId == "real-ask" && r.content == "approved"
        case _ => false
      })))
      _ <- IO.println(s"TRANSCRIPT_READBACK worker=real-worker toolUseId=real-ask result=approved correlation=$id startupScan=$recovered")
    yield ()
    check.guarantee(IO {
      nebflow.core.LlmLogWriter.setEnabled(previousLlmLog)
      PathUtil.setDataRoot(previousRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    }).unsafeRunSync()
  }

  private def waitForPersistedAskResult(resources: SharedResources, sid: String): IO[Unit] =
    def go(deadline: Long): IO[Unit] = resources.sessionStore.loadMessagesForSession(sid).flatMap { messages =>
      val found = messages.exists(_.content.toOption.exists(_.exists {
        case r: nebflow.shared.ContentBlock.ToolResult => r.toolUseId == "real-ask" && r.content == "approved"
        case _ => false
      }))
      if found then IO.unit
      else if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("tool result not persisted"))
      else IO.sleep(50.millis) *> go(deadline)
    }
    go(System.currentTimeMillis() + 20000)

  private def waitForMailbox(sid: String): IO[List[SessionMailbox.Entry]] =
    def go(deadline: Long): IO[List[SessionMailbox.Entry]] =
      SessionMailbox.scanPending(sid).flatMap { entries =>
        if entries.nonEmpty then IO.pure(entries)
        else if System.currentTimeMillis() >= deadline then
          IO.raiseError(new AssertionError(s"AskHost request did not reach root mailbox $sid"))
        else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + 20000)

  // ============================================================
  // 3. root 无窗口仍可挂 pending（无窗口不改变身份政策）
  // ============================================================

  test("裁④ 无预检: root 会话窗口未注册 ⇒ 不再拒绝，问题照常派发并可挂 pending 承接") {
    val f = setup(
      "root-nowindow",
      AgentDef(name = "Nebula", description = "", tools = Nil),
      depth = 0,
      sid = "dualmode-nowindow",
      askInput = askInputNonBlocking,
      registerWindow = false
    )
    (for
      _ <- waitFor(f.requests, _.size >= 2, "无窗口时 turn 未继续（疑似仍在预检里挂住）")
      reqs <- f.requests.get
      toolResults <- IO(lastToolResults(reqs))
      // 裁④：预检腿已去除 ⇒ 无窗口**不再**是错误；ack 照常返回。
      _ = assert(
        !toolResults.exists(_.contains("ASKUSER_NONBLOCK_NO_ROOT_WINDOW")),
        s"无窗口仍被预检拒绝（裁④ 未落地）：$toolResults"
      )
      _ = assert(
        toolResults.exists(_.contains("non-blocking:")),
        s"无窗口时未拿到非阻塞 ack：$toolResults"
      )
      // 绝对无窗 ⇒ 挂 pending（快照面可见），答案不会静默丢。
      pend <- pendingAsksUntil(f.hub, f.sid, _.nonEmpty, "无窗口的提问既未扇出也未挂 pending")
      _ = assert(pend.nonEmpty, s"无窗口的提问未挂 pending（答案无处承接）: $pend")
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

  // ============================================================
  // 4. `mode` 参数被显式拒绝（兼容判据）——零副作用
  // ============================================================

  test("退役参数: 调用面携带 `mode` ⇒ 可判读 ToolError + 零槽位 + 无卡片 + turn 未挂起") {
    val f = setup(
      "retired-mode",
      AgentDef(name = "Nebula", description = "", tools = Nil),
      depth = 0,
      sid = "dualmode-retired",
      askInput = askInputWithRetiredMode
    )
    (for
      _ <- waitFor(f.requests, _.size >= 2, "带 mode 的调用挂住了 turn")
      reqs <- f.requests.get
      toolResults <- IO(lastToolResults(reqs))
      _ = assert(
        toolResults.exists(_.contains(AskUserQuestionTool.ModeParamRetiredCode)),
        s"携带 mode 未被显式拒绝（静默降级？）：$toolResults"
      )
      // 拒绝文案自描述 + 不回溯叙述先前行为
      _ = assert(
        toolResults.exists(t =>
          t.contains("does not accept a `mode` parameter") && t.contains("always asks in a non-blocking way")
        ),
        s"拒绝文案不可判读：$toolResults"
      )
      // 零副作用：哨兵在闸之后才可能派发
      pend <- pendingAsks(f.hub, f.sid)
      _ = assertEquals(pend.size, 0, s"被拒的请求却占了 hub 槽位: $pend")
      frames <- f.wsEvents.get
      _ = assertEquals(
        frames.count(_.hcursor.get[String]("type").toOption.contains("askUser")),
        0,
        "被拒的请求渲染了卡片"
      )
      st <- statusOf(f.resources, f.sid)
      _ = assert(st != AgentStatus.WaitingForUser, s"被拒的请求把会话标成 WaitingForUser（$st）")
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

  // ============================================================
  // 5. root 身份 AskUser 正常 dispatch
  // ============================================================

  test("Nebula root 的真实执行身份允许 AskUser dispatch") {
    val f = setup(
      "root-dispatch",
      AgentDef(name = nebflow.actor.RootAgentIdentity.Name, description = "", tools = Nil),
      depth = 0,
      sid = "dualmode-root-dispatch",
      askInput = askInputBlocking
    )
    (for
      _ <- waitFor(f.requests, _.size >= 2, "root AskUser 后 turn 未收到工具结果")
      reqs <- f.requests.get
      toolResults <- IO(lastToolResults(reqs))
      _ = assert(toolResults.exists(_.contains("non-blocking:")), s"root identity 未能 dispatch AskUser: $toolResults")
      _ <- waitFor(f.wsEvents, _.exists(j => j.hcursor.get[String]("type").toOption.contains("askUser")), "root AskUser 卡片未渲染")
      pend <- pendingAsksUntil(f.hub, f.sid, _.size == 1, "root AskUser 未占 hub 槽位")
      _ = assertEquals(pend.size, 1)
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

  // ============================================================
  // 6. 请求体口径（= router JSONL 的同一对象图）：零变体
  // ============================================================

  test("请求体口径: root 请求有 AskUserQuestion、后台请求无 AskUserQuestion schema") {
    def askFrom(reqs: List[LlmRequest]): nebflow.shared.ToolDefinition =
      reqs.headOption
        .flatMap(_.tools.getOrElse(Nil).find(_.name == AskUserQuestionTool.Name))
        .getOrElse(fail(s"请求体里没有 AskUserQuestion（tools=${reqs.headOption.flatMap(_.tools).map(_.map(_.name))}）"))

    def hasAskUser(reqs: List[LlmRequest]): Boolean =
      reqs.headOption.exists(_.tools.getOrElse(Nil).exists(_.name == AskUserQuestionTool.Name))
    def modeOf(td: nebflow.shared.ToolDefinition): Option[Json] =
      td.inputSchema("properties").flatMap(_.asObject).flatMap(_.apply("mode"))

    val baseline = nebflow.core.tools.ToolRegistry.ALL_TOOLS.find(_.name == AskUserQuestionTool.Name).get
    val rootFx = setup(
      "reqface-root",
      AgentDef(name = "Nebula", description = "", tools = Nil),
      0,
      "reqface-root",
      askInputBlocking
    )
    val nodeFx = setup(
      "reqface-node",
      AgentDef(name = executorName, description = "", tools = Nil),
      1,
      "reqface-node",
      askInputBlocking
    )
    (for
      _ <- waitFor(rootFx.requests, _.nonEmpty, "root 请求未到达")
      _ <- waitFor(nodeFx.requests, _.nonEmpty, "node 请求未到达")
      rootReqs <- rootFx.requests.get
      nodeReqs <- nodeFx.requests.get
      _ <- rootFx.cleanup
      _ <- nodeFx.cleanup
      _ <- IO {
        val rootTd = askFrom(rootReqs)
        assert(modeOf(rootTd).isEmpty, "请求体里的 root 定义仍有 mode（裁② 不保留键未落地）")
        assertEquals(rootTd.description, baseline.description)
        assertEquals(rootTd.inputSchema, baseline.inputSchema)
        assert(hasAskUser(rootReqs), "Nebula root LLM 请求必须含 AskUserQuestion schema")
        assert(!hasAskUser(nodeReqs), "后台执行 agent LLM 请求不得含 AskUserQuestion schema")
        // 其余**共有**工具逐字节一致（成员资格差异是角色面差异）。
        val rootTools = rootReqs.head.tools.getOrElse(Nil)
        val nodeTools = nodeReqs.head.tools.getOrElse(Nil)
        val shared = rootTools.map(_.name).toSet.intersect(nodeTools.map(_.name).toSet)
        assert(shared.nonEmpty, "两角色共有工具为空 —— 对照面不成立")
        shared.foreach { n =>
          val a = rootTools.find(_.name == n).get
          val b = nodeTools.find(_.name == n).get
          assertEquals(a, b, s"共有工具 $n 的定义在两角色间发生差异")
        }
      }
    yield ()).unsafeRunSync()
  }

end AskUserDualModeRuntimeSpec
