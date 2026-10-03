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
  InteractionHubCommand,
  InteractionKind,
  InteractionReply,
  InteractionRequest,
  status
}
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.processor.TaskStuckWatcher
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ProjectCreateTool}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.gateway.WsHub
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  FallbackAttempt,
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
 * R2 状态机闭环（wait-timeout-fix，2026-09-03 作者裁定）端到端接线钉子 ——
 * **非阻塞-only 收敛后的等价物**（root 2026-10-02 令 #462 裁①；本批改写，
 * 判据改、**强度不降**）。真实 AgentActor + 真实 InteractionHub 驱动
 * AskUser pending 全生命周期，钉住「标记与解除成对，等待态绝不滞留」。
 *
 * 🔴 **前提变更（本批裁①）**：`AskUserQuestionTool` 一律非阻塞 ⇒ **工具提问不再
 * 产生等待态** —— 历史用例 1 的前提（工具派发 ⇒ `WaitingForUser`）**已随裁定退场**，
 * 不再是本 spec 的断言面（改由「零等待态」正面钉住：工具派发的 AskUser 之后
 * registry **恒不出现** `WaitingForUser`，见下 R2-nb；原文件的「验红变异」措辞
 * 随之作废）。
 *
 * **等待态并未整体消失**：`AgentProcessing` 的 `waitMarks` 改由命令面客观事实
 * `awaitsAnswer` 门控（root 2026-10-02 令 #462 下仍在阻塞等待的调用方 =
 * `ProjectCreateTool.pathPanel`，它把答复作为 `createChain` 的入参）。⇒ 本 spec
 * 的闭环判据**整体迁移到该调用方**（下面 R2-panel 用例，走真实工具链：
 * LLM 发 `ProjectCreate` 工具调用 → `pathPanel` 派发 `awaitsAnswer=true` 的
 * `AgentCommand.AskUser` → 等待态 + 预算暂停 → hub 答复 → `answerLanded` 配对解除）：
 *
 *   1. 真阻塞调用方派发 → registry 标 `WaitingForUser`（AgentState.scala
 *      AgentStatus 接线；此前残留 Processing → TaskStuckWatcher 每 30s 误报，
 *      审计 20260903 当日 116 条）；
 *   2. 挂起超阈值（缩阈模拟 11min：回拨 lastActivityMs）→ scan 零动作——
 *      误报与破坏性 Stop 链消失（验收 1）；
 *   3. 用户回答落地 → 解除等待态恢复 Processing（活动戳刷新）→ turn 续跑
 *      到 Idle——「TaskStuckWatcher 重新覆盖」前提成立；
 *   4. 重新覆盖证明：恢复后同形回拨 → scan 立即开火（真卡死仍可达）；
 *   5. 用户取消（Interrupt）→ 解除为 Idle——真挂死兜底可达（等待态不是
 *      终态，用户随时可退）。
 *
 * 时间模拟手法（沿用 TaskStuckWatcherSpec 先例，零真实等待）：直接回拨
 * registry 的 lastActivityMs + 参数化 threshold 驱动单轮 scan。
 *
 * 验红变异（验收 1，迁移到 R2-panel）：把 `AgentProcessing` 的 `waitMarks`
 * 改回恒 `IO.unit`（即丢掉 `awaitsAnswer` 门控）⇒ 面板等待期 status 残留
 * Processing ⇒ 步骤 2 的 scan 开火（taskStuck 广播 + Stop）→ 红；恢复门控后绿。
 */
class WaitTimeoutAskUserWiringSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private val StuckThresholdMs = 10 * 60 * 1000L // 与 Defaults.StuckThresholdMs 同值

  /**
   * 首轮返回 **决定性工具调用**（由 `tool` 参数给定）——
   *   · `"AskUserQuestion"`（非阻塞档）：工具发起即返回 ack，**不产生等待态**
   *     （本批裁① 的正面钉面，见 R2-nb 用例）；
   *   · `"ProjectCreate"`（真阻塞档）：`pathPanel` 在自己的 fiber 里 `.?` 等待，
   *     并把答复作为 `createChain` 的入参 ⇒ `awaitsAnswer=true` ⇒ 等待态闭环
   *     （R2-panel 用例，本文件的主判决面）。
   * 第二轮阻塞在 secondGate（制造回答后的稳定观察窗，断言「恢复 Processing」不被
   * turn 秒完淹没），放行后文本收尾。
   */
  private class AskLlm(
    requests: Ref[IO, List[LlmRequest]],
    secondGate: Deferred[IO, Unit],
    tool: String,
    toolInput: JsonObject
  ) extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)).drain ++
        Stream.eval(requests.get.map(_.size)).flatMap { n =>
          if n == 1 then
            Stream(
              StreamChunk.ToolCallChunk(ToolCall("tu-1", tool, toolInput)),
              StreamChunk.Done(None, None)
            )
          else
            Stream.eval(secondGate.get.void).drain ++
              Stream(StreamChunk.TextDelta("answered-done"), StreamChunk.Done(None, None))
        }

  end AskLlm

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
          else IO.sleep(100.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  /** 记录收到消息的自旋 actor（parentRef 占位 + Stop 观察点）。 */
  private def mkRecordingActor(sink: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => sink.update(_ :+ cmd).as(loop))
    loop

  /**
   * 场景装配：hub + agent（Delegate 形态，有 parentRef——审计破坏性链的
   * 主角）+ 首轮工具调用派发（`tool` 参数选择：AskUserQuestion 非阻塞档 /
   * ProjectCreate 真阻塞档）。返回观察点句柄。
   */
  private case class Fixture(
    system: ActorSystem,
    resources: SharedResources,
    hub: ActorRef[InteractionHubCommand],
    actor: ActorRef[AgentCommand],
    sid: String,
    wsEvents: Ref[IO, List[Json]],
    requests: Ref[IO, List[LlmRequest]],
    secondGate: Deferred[IO, Unit],
    tmp: os.Path,
    cleanup: IO[Unit]
  )

  private def setup(name: String, tool: String = "AskUserQuestion"): Fixture =
    val system = ActorSystem(s"r2-wiring-$name")
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "data")
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    // 首轮工具调用入参：两档各自的最小合法载荷。
    val toolInput =
      if tool == "ProjectCreate" then JsonObject.empty
      else JsonObject("questions" -> Json.arr(Json.obj("question" -> Json.fromString("R2 wiring check?"))))
    val program = for
      secondGate <- Deferred[IO, Unit]
      requests <- IO.ref(List.empty[LlmRequest])
      llm = new AskLlm(requests, secondGate, tool, toolInput)
      resources <- mkResources(system, tmp, llm)
      wsEvents <- IO.ref(List.empty[Json])
      hub <- system.spawn(InteractionHub(), s"hub-$name")
      _ <- resources.interactionHubRef.set(Some(hub))
      _ <- hub ! InteractionHubCommand.RegisterRoot("r2-wiring-sid", (j: Json) => wsEvents.update(_ :+ j))
      def_ = AgentDef(
        name = "Worker",
        description = "r2 wiring fixture",
        tools = List(tool),
        systemPrompt = ""
      )
      parentSink <- IO.ref(List.empty[AgentCommand])
      parentRef <- system.spawn(mkRecordingActor(parentSink), s"parent-$name")
      actor <- system.spawn(
        AgentActor(
          agentDef = def_,
          resources = resources,
          wsSend = j => wsEvents.update(_ :+ j),
          depth = 1,
          parentRef = Some(parentRef),
          sessionId = Some("r2-wiring-sid"),
          sessionName = Some(s"fixture-$name"),
          safetyMode = "auto-all"
        ),
        s"agent-$name"
      )
      now <- IO(System.currentTimeMillis())
      _ <- resources.agentRegistry.update(
        _ + ("r2-wiring-sid" -> AgentRecord(
          sessionId = "r2-wiring-sid",
          ref = actor,
          kind = AgentKind.Delegate,
          rootSessionId = "r2-wiring-sid",
          parentRef = Some(parentRef),
          startedAt = now,
          status = AgentStatus.Processing,
          lastActivityMs = now
        ))
      )
      _ <- actor ! AgentCommand.UserInput("ask me", None, Some(s"cmid-$name"))
    yield Fixture(
      system,
      resources,
      hub,
      actor,
      "r2-wiring-sid",
      wsEvents,
      requests,
      secondGate,
      tmp,
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

  /** 缩阈模拟「等待 11min+」：把活动戳回拨到阈值之外。 */
  private def backdate(resources: SharedResources, sid: String, minusMs: Long): IO[Unit] =
    IO(System.currentTimeMillis()).flatMap { now =>
      resources.agentRegistry.update { m =>
        m.get(sid) match
          case Some(rec) => m.updated(sid, rec.copy(lastActivityMs = now - minusMs))
          case None => m
      }
    }

  private def statusOf(resources: SharedResources, sid: String): IO[AgentStatus] =
    resources.agentRegistry.get.map(_(sid).status)

  private def taskStuckFrames(evs: List[Json]): List[Json] =
    evs.filter(j => j.hcursor.get[String]("type").toOption.contains("taskStuck"))

  // ============================================================
  // 主链路：标注 → 挂起零误报 → 回答解除 → 重新覆盖
  // 🔴 本批裁① 后，**唯一**仍会产生等待态的调用方 = ProjectCreateTool.pathPanel
  //    （真阻塞：答复即 createChain 的入参）⇒ 本用例改经该工具链驱动。
  // ============================================================

  test("R2 闭环 (panel): 真阻塞调用方派发标 WaitingForUser → 挂起超阈值零误报 → 回答恢复 Processing → 重新覆盖可开火 → turn 完成 Idle") {
    val f = setup("main", tool = "ProjectCreate")
    (for
      // ── 1. 派发：pathPanel（awaitsAnswer=true）→ WaitingForUser 标注 + askUser 卡渲染 ──
      _ <- waitFor(
        f.resources.agentRegistry,
        m => m.get(f.sid).exists(_.status == AgentStatus.WaitingForUser),
        "真阻塞调用方（pathPanel）派发后 registry 未标 WaitingForUser"
      )
      _ <- waitFor(
        f.wsEvents,
        evs => evs.exists(j => j.hcursor.get[String]("type").toOption.contains("askUser")),
        "askUser 卡未渲染"
      )

      // ── 2. 挂起 11min+（回拨模拟）→ scan 零动作：零误报、零 Stop（验收 1）──
      _ <- backdate(f.resources, f.sid, StuckThresholdMs + 60_000)
      wsHub = new WsHub()
      scanWs <- IO.ref(List.empty[Json])
      _ <- wsHub.registerListener(json => scanWs.update(_ :+ json))
      _ <- TaskStuckWatcher.scan(f.resources, wsHub, StuckThresholdMs)
      _ <- IO.sleep(200.millis)
      scanFrames1 <- scanWs.get
      _ <- IO(assert(scanFrames1.isEmpty, s"等待态被误报（复现了审计 116 条/日 形态）: $scanFrames1"))
      st1 <- statusOf(f.resources, f.sid)
      _ <- IO(assertEquals(st1, AgentStatus.WaitingForUser, "等待态必须原样保留（不被打扰）"))

      // ── 3. 回答落地 → 解除等待态恢复 Processing（secondGate 仍关，观察窗稳定）──
      //   答案取**取消哨兵** ⇒ `parsePanelAnswer` 得 `PanelAnswer.Shelved` ⇒
      //   不创建任何项目（本用例只判等待态配对，不判创建链——创建链由
      //   `ProjectCreatePanelSpec` 覆盖），但 `.?` 的答复路径与 `answerLanded`
      //   配对解除**逐字相同**（答复落地才是判据本体）。
      evs <- f.wsEvents.get
      askFrame = evs.find(j => j.hcursor.get[String]("type").toOption.contains("askUser")).get
      requestId = askFrame.hcursor.get[String]("requestId").toOption.get
      _ <- IO(assert(requestId.startsWith("panel-"), s"面板 requestId 前缀应为本工具的 panel-：$requestId"))
      _ <- f.hub ! InteractionHubCommand.Answered(
        InteractionAnswered(
          requestId,
          f.sid,
          Json.obj("answers" -> Json.arr(Json.fromString(ProjectCreateTool.CancelSentinel)))
        )
      )
      _ <- waitFor(
        f.resources.agentRegistry,
        m => m.get(f.sid).exists(_.status == AgentStatus.Processing),
        "回答落地后未恢复 Processing"
      )
      st2 <- statusOf(f.resources, f.sid)
      _ <- IO(assertEquals(st2, AgentStatus.Processing, "答案回填后必须恢复 Processing（标记解除成对）"))
      now <- IO(System.currentTimeMillis())
      stamp <- f.resources.agentRegistry.get.map(_(f.sid).lastActivityMs)
      _ <- IO(assert(now - stamp < 10_000, s"活动戳必须随回答刷新（watcher 窗口重启），delta=${now - stamp}ms"))

      // ── 4. 放行第二轮 LLM → turn 完成 → Idle ──
      _ <- f.secondGate.complete(())
      _ <- waitFor(f.resources.agentRegistry, m => m.get(f.sid).exists(_.status == AgentStatus.Idle), "turn 未完成回 Idle")
      reqs <- f.requests.get
      _ <- IO(assertEquals(reqs.size, 2, "回答后必须续跑第二轮 LLM"))

      // ── 5. 重新覆盖证明：turn 结束后模拟再次真卡死（Processing + 回拨）→
      //    scan 立即开火（验收 5 解除路径：等待态解除后 session 重回 watcher
      //    覆盖；此处 Stop 落在 idle actor 上终止其生命周期，无碍断言）──
      _ <- f.resources.agentRegistry.update { m =>
        val rec = m(f.sid)
        m.updated(
          f.sid,
          rec.copy(
            status = AgentStatus.Processing,
            lastActivityMs = System.currentTimeMillis() - (StuckThresholdMs + 60_000)
          )
        )
      }
      _ <- TaskStuckWatcher.scan(f.resources, wsHub, StuckThresholdMs)
      _ <- IO.sleep(200.millis)
      scanFrames2 <- scanWs.get
      _ <- IO(assert(taskStuckFrames(scanFrames2).nonEmpty, s"解除等待后 session 必须重新处于 watcher 覆盖下（回拨即开火）: $scanFrames2"))
    yield ()).guarantee(f.cleanup)
  }

  // ============================================================
  // 🔴 本批裁① 的**正面钉面**（取代历史用例 1 的前提）：工具提问 ⇒ 零等待态。
  //    真 AgentActor + 真 hub + 真 AskUserQuestionTool（非阻塞档）。
  // ============================================================

  test("R2-nb（裁①）: 工具非阻塞提问 ⇒ 卡片渲染 + 槽位在 + registry **恒不出现** WaitingForUser（零误标）") {
    val f = setup("askuser-nonblocking")
    (for
      // 卡片必须真渲染出来（否则「没有等待态」可能只是因为什么都没发生）
      _ <- waitFor(
        f.wsEvents,
        evs => evs.exists(j => j.hcursor.get[String]("type").toOption.contains("askUser")),
        "非阻塞 askUser 卡未渲染"
      )
      // 观察窗：足够长，若真存在历史标注路径必然已落（工具有 ack 即时返回，
      // 标注在命令处理内同步发生）——窗口取 1.5s，远超单次命令处理时延。
      _ <- IO.sleep(1500.millis)
      st <- statusOf(f.resources, f.sid)
      _ <- IO(
        assert(
          st != AgentStatus.WaitingForUser,
          s"非阻塞工具提问出现了 WaitingForUser —— 裁①「全量非阻塞」未落地（status=$st）"
        )
      )
      // 负控的反向印证：等待态是**从未发生**，不是「被谁清了」——活动戳仍是
      // Processing 语义下的持续推进（turn 仍在跑，未被挂起）。
      _ <- IO(assert(st == AgentStatus.Processing, s"非阻塞提问后应保持 Processing，实测 $st"))
      // 槽位确实挂上了 hub（卡片 + 槽位 +1 是「提问真的派发了」的证据）
      snap <- f.hub.?[List[Json]](reply => InteractionHubCommand.ListAllPendingAsks(reply))
      _ <- IO(assert(snap.nonEmpty, s"非阻塞提问必须挂上 hub pending 槽（实测快照 = $snap）"))
      _ <- IO(assert(snap.exists(_.hcursor.get[String]("type").toOption.contains("askUser")), s"槽载荷应含 askUser：$snap"))
    yield ()).guarantee(f.cleanup)
  }

  // ============================================================
  // 取消路径：pending 期间 Interrupt → Idle（等待态不是终态，兜底可达）
  // ============================================================

  test("R2 闭环 (panel): pending 期间用户 Interrupt → 解除为 Idle（取消路径同步解除，真挂死兜底可达）") {
    val f = setup("cancel", tool = "ProjectCreate")
    (for
      _ <- waitFor(
        f.resources.agentRegistry,
        m => m.get(f.sid).exists(_.status == AgentStatus.WaitingForUser),
        "真阻塞调用方（pathPanel）派发后 registry 未标 WaitingForUser"
      )
      _ <- f.actor ! AgentCommand.Interrupt()
      _ <- waitFor(
        f.resources.agentRegistry,
        m => m.get(f.sid).exists(_.status == AgentStatus.Idle),
        "Interrupt 后 registry 未回 Idle"
      )
      st <- statusOf(f.resources, f.sid)
      _ <- IO(assert(st != AgentStatus.WaitingForUser, "取消后绝不能滞留等待态"))
    yield ()).guarantee(f.cleanup)
  }

  // ============================================================
  // #250 ②（2026-09-13 作者裁定「6 项全补」）：Interrupt 必须回收 pending 槽
  //
  // 改前：Interrupt 只 cancelCurrentTurn + 回 Idle（本文件上一条用例），全仓
  // CleanupForSession 只有 NodeEngine / BackoffSupervisor 两个调用点 ⇒ root 会话
  // 的提问卡在中断后成为永久僵尸（等待无超时、无人回收）。
  // 本用例是**单测级**证据（真 AgentActor + 真 hub + 真消息，无运行实例）。
  // ============================================================

  test("#250②: pending 期间 Interrupt → hub 槽位回收 + 广播 askUserClosed(reason=turn-interrupted)，且不误伤别的 sourceSession") {
    val f = setup("interrupt-cleanup", tool = "ProjectCreate")
    (for
      _ <- waitFor(
        f.resources.agentRegistry,
        m => m.get(f.sid).exists(_.status == AgentStatus.WaitingForUser),
        "真阻塞调用方（pathPanel）派发后 registry 未标 WaitingForUser"
      )
      _ <- waitFor(
        f.wsEvents,
        evs => evs.exists(j => j.hcursor.get[String]("type").toOption.contains("askUser")),
        "askUser 卡未渲染"
      )
      evs0 <- f.wsEvents.get
      askFrame = evs0.find(j => j.hcursor.get[String]("type").toOption.contains("askUser")).get
      requestId = askFrame.hcursor.get[String]("requestId").toOption.get
      // 负控素材：另一个 sourceSession 的 pending 槽（中断清理不得扫射）
      bystanderGot <- IO.ref(Option.empty[List[String]])
      bystander <- f.system
        .spawn(
          nebflow.actor.Behaviors.receiveMessage[List[String]] { a =>
            bystanderGot.set(Some(a)).as(nebflow.actor.Behaviors.stopped)
          },
          "bystander-sink"
        )
        .map(sink =>
          InteractionRequest(
            requestId = "ask-bystander-00000000",
            kind = InteractionKind.AskUser,
            payload = Json.obj("items" -> Json.arr(), "agentName" -> Json.fromString("Nebula")),
            reply = InteractionReply.AskUserReply(Some(sink)),
            rootSessionId = f.sid,
            sourceAgent = "Nebula",
            sourceSession = "node-bystander"
          )
        )
      _ <- f.hub ! InteractionHubCommand.Request(bystander)
      _ <- IO.sleep(100.millis)

      // ── 中断 ──
      _ <- f.actor ! AgentCommand.Interrupt()
      _ <- waitFor(
        f.resources.agentRegistry,
        m => m.get(f.sid).exists(_.status == AgentStatus.Idle),
        "Interrupt 后 registry 未回 Idle"
      )
      _ <- waitFor(
        f.wsEvents,
        evs =>
          evs.exists(j =>
            j.hcursor.get[String]("type").toOption.contains("askUserClosed") &&
              j.hcursor.get[String]("requestId").toOption.contains(requestId)
          ),
        "Interrupt 后未广播 askUserClosed（② 的槽位回收未接线）"
      )
      evs <- f.wsEvents.get
      closed = evs
        .filter(j =>
          j.hcursor.get[String]("type").toOption.contains("askUserClosed") &&
            j.hcursor.get[String]("requestId").toOption.contains(requestId)
        )
        .last
      _ <- IO(assertEquals(closed.hcursor.get[String]("reason").toOption, Some("turn-interrupted")))
      // 槽位真的没了（不是只广播）：hub 快照里只剩旁观者
      snap <- f.hub.?[List[Json]](reply => InteractionHubCommand.ListAllPendingAsks(reply))
      _ <- IO(
        assertEquals(
          snap.map(_.hcursor.get[String]("requestId").toOption.get),
          List("ask-bystander-00000000"),
          "本会话的槽必须被回收，别的 sourceSession 的槽必须存活"
        )
      )
    yield ()).guarantee(f.cleanup)
  }

end WaitTimeoutAskUserWiringSpec
