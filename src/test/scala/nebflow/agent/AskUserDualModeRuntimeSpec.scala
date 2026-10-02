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
import nebflow.core.tools.{AskUserQuestionTool, FileLockManager}
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
 *  1. **全链（任意身份）**：发起即返回（ack 进工具结果）+ 卡片渲染（`askUser` 帧）
 *     + hub 槽位 +1 + registry **不出现** `WaitingForUser`；答复经 hub → 桥
 *     （[[AskUserAnswerBridge]]）→ `ImmediateInput(fromUser=true)` 唤醒新 turn，
 *     模型看到答案文本；槽位 -1。
 *  2. **节点身份零拒绝**（裁①「全量非阻塞」的落地面）：general/depth=1 与 kernel
 *     会话现在**同样**走非阻塞 —— 历史上的 `ASKUSER_NONBLOCK_NOT_ROOT` 拒答面消失
 *     ⇒ 断言变为「无该码、turn 未挂起、槽位占上」。
 *  3. **无窗口不拒**（裁④「去除预检」）：root 会话窗口未注册时**不再** fail-closed；
 *     问题照常经 hub 派发/挂 pending 承接，答案不会静默丢（hub 扇出腿）。
 *  4. **`mode` 参数被显式拒绝**：真实链上带 `mode` 的调用得到可判读 `ToolError`，
 *     且**零副作用**（无槽位、无卡片、turn 未挂起）。
 *  5. **请求体口径**：两身份的 LlmRequest.tools 里 AskUserQuestion 段与注册表基线
 *     **逐字节一致**（无 schema 变体残留）。
 */
class AskUserDualModeRuntimeSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  /**
   * 脚本化 LLM：首轮吐 AskUserQuestion 工具调用（入参由用例注入），后续轮次文本收尾。
   * `gate` = 观察窗（先例 `WaitTimeoutAskUserWiringSpec` 的 secondGate）：未放行时
   * turn 停在第二轮 LLM 调用上 ⇒「非阻塞不标 WaitingForUser」可在**确定的时间窗内**
   * 读数（否则 turn 秒完，turn-end 兜底会把状态刷回 Idle，读数变空转）。
   */
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
  // 2. 任意身份全量非阻塞（裁① 的落地面）：节点会话不再被拒
  // ============================================================

  test("全量非阻塞（真实 actor）: general 节点 depth=1 照常提问 ⇒ 零拒绝 + 占槽位 + 无 WaitingForUser + turn 未挂起") {
    val f = setup(
      "node-nonblock",
      AgentDef(name = "general", description = "", tools = Nil),
      depth = 1,
      sid = "dualmode-node",
      askInput = askInputNonBlocking
    )
    (for
      _ <- waitFor(f.requests, _.size >= 2, "节点会话的 turn 未继续（疑似静默改走阻塞 = 挂起）")
      reqs <- f.requests.get
      toolResults <- IO(lastToolResults(reqs))
      // 裁①：节点身份**不再**被「非阻塞仅 root」拒 —— 既无该码，也照常派发。
      _ = assert(
        !toolResults.exists(_.contains("ASKUSER_NONBLOCK_NOT_ROOT")),
        s"节点身份仍被 root-only 门拒绝（裁① 未落地）：$toolResults"
      )
      _ = assert(
        toolResults.exists(_.contains("non-blocking:")),
        s"节点身份未拿到非阻塞 ack：$toolResults"
      )
      // 卡片照常渲染 + 槽位 +1（= 全量非阻塞真派发，不是静默丢弃）
      _ <- waitFor(
        f.wsEvents,
        _.exists(j => j.hcursor.get[String]("type").toOption.contains("askUser")),
        "节点会话的卡未渲染"
      )
      pend <- pendingAsksUntil(f.hub, f.sid, _.size == 1, "节点会话的卡未占槽位")
      _ = assertEquals(pend.size, 1, s"节点会话的卡未占槽位: $pend")
      st <- statusOf(f.resources, f.sid)
      _ = assert(st != AgentStatus.WaitingForUser, s"非阻塞派发后 registry 被标 WaitingForUser（$st）—— 造出永不解除的等待")
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

  test("全量非阻塞（真实 actor）: kernel depth=1 同上（内核会话不再被拒）") {
    val f = setup(
      "kernel-nonblock",
      AgentDef(name = "kernel", description = "", tools = Nil),
      depth = 1,
      sid = "dualmode-kernel",
      askInput = askInputNonBlocking
    )
    (for
      _ <- waitFor(f.requests, _.size >= 2, "内核会话的 turn 未继续")
      reqs <- f.requests.get
      toolResults <- IO(lastToolResults(reqs))
      _ = assert(
        toolResults.exists(_.contains("non-blocking:")),
        s"内核身份未拿到非阻塞 ack（裁① 未覆盖内核）：$toolResults"
      )
      pend <- pendingAsksUntil(f.hub, f.sid, _.size == 1, "内核会话的卡未占槽位")
      _ = assertEquals(pend.size, 1)
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

  // ============================================================
  // 3. 裁④ 去除预检：无窗口不再 fail-closed
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
        toolResults.exists(t => t.contains("does not accept a `mode` parameter") && t.contains("always asks in a non-blocking way")),
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
  // 5. 无等待态：非阻塞派发**恒不**标 WaitingForUser（现状的对照面已消失）
  // ============================================================

  test("无等待态（真实 actor）: 非阻塞派发后 map 不出现 WaitingForUser，且第二轮 LLM 调用照常发生") {
    val f = setup(
      "node-blocking",
      AgentDef(name = "general", description = "", tools = Nil),
      depth = 1,
      sid = "dualmode-blocking",
      askInput = askInputBlocking
    )
    (for
      _ <- waitFor(f.requests, _.size >= 2, "非阻塞派发后 turn 未继续（疑似等待态）")
      _ <- waitFor(f.wsEvents, _.exists(j => j.hcursor.get[String]("type").toOption.contains("askUser")), "未渲染卡片")
      pend <- pendingAsksUntil(f.hub, f.sid, _.size == 1, "卡未占槽位")
      _ = assertEquals(pend.size, 1)
      // 派发后 turn 继续（第二轮 LLM 调用已发生）⇒ 会话**不**停在等待态。
      st <- statusOf(f.resources, f.sid)
      _ = assert(
        st != AgentStatus.WaitingForUser,
        s"非阻塞派发把会话标成 WaitingForUser（$st）—— 等待态未随裁① 退场"
      )
      _ <- f.cleanup
    yield ()).unsafeRunSync()
  }

  // ============================================================
  // 6. 请求体口径（= router JSONL 的同一对象图）：零变体
  // ============================================================

  test("请求体口径: 两身份的 LlmRequest.tools 里 AskUserQuestion 段与注册表基线逐字节一致（无 schema 变体残留）") {
    def askFrom(reqs: List[LlmRequest]): nebflow.shared.ToolDefinition =
      reqs.headOption
        .flatMap(_.tools.getOrElse(Nil).find(_.name == AskUserQuestionTool.Name))
        .getOrElse(fail(s"请求体里没有 AskUserQuestion（tools=${reqs.headOption.flatMap(_.tools).map(_.map(_.name))}）"))
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
      AgentDef(name = "general", description = "", tools = Nil),
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
        val nodeTd = askFrom(nodeReqs)
        // 裁②「不保留键」：两身份的 AskUserQuestion 定义都**无** mode，且与注册表基线
        // **逐字节相同**（description + input_schema）⇒ 无变体可分。
        assert(modeOf(rootTd).isEmpty, "请求体里的 root 定义仍有 mode（裁② 不保留键未落地）")
        assert(modeOf(nodeTd).isEmpty, "请求体里的 node 定义出现了 mode")
        assertEquals(rootTd.description, baseline.description)
        assertEquals(rootTd.inputSchema, baseline.inputSchema)
        assertEquals(nodeTd.description, baseline.description)
        assertEquals(nodeTd.inputSchema, baseline.inputSchema)
        // 两身份的这一段彼此也逐字节相同（本批的核心不变量：无身份分化）。
        assertEquals(rootTd.description, nodeTd.description)
        assertEquals(rootTd.inputSchema, nodeTd.inputSchema)
        // 其余**共有**工具逐字节一致（成员资格差异是既有角色面差异，与本批无关）
        val rootTools = rootReqs.head.tools.getOrElse(Nil)
        val nodeTools = nodeReqs.head.tools.getOrElse(Nil)
        val shared = rootTools.map(_.name).toSet.intersect(nodeTools.map(_.name).toSet) - AskUserQuestionTool.Name
        assert(shared.nonEmpty, "两角色共有工具为空 —— 对照面不成立")
        shared.foreach { n =>
          val a = rootTools.find(_.name == n).get
          val b = nodeTools.find(_.name == n).get
          assertEquals(a, b, s"共有工具 $n 的定义在两角色间发生差异（分化越界）")
        }
      }
    yield ()).unsafeRunSync()
  }

end AskUserDualModeRuntimeSpec
