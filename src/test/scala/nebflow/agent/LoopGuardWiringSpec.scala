package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/** Block 3 循环检测器 WIRING 钉子（supervision trio §D/§F，2026-08-27）。
  *
  * 真实 AgentActor + 恒败 Read 工具（同一 file_path 不存在）+ 收紧阈值
  * （soft=1 / hard=3，nebflow.json supervision.loopGuard 热读路径）：
  *   L0 — soft 警告以 <system-reminder> 注入下一轮请求消息
  *   L1 — depth≥1 的 hard 命中走 LlmFailed(LoopDetectedError) fatal 链
  *        （WS error 帧 + busy=false + LLM 请求停止增长）
  *   root 豁免 — depth=0 的 L1 被降级为 L0，turn 不自动终止
  *   L2 — L1 终止过的 fp 在后续 turn 复发 → LoopFreezeDetected → Frozen(loop)
  *        （WS loopDetected 帧 + registry Frozen）
  *
  * 判定核心的纯函数矩阵在 LoopGuardSpec；此处验证挂载点行为。
  */
class LoopGuardWiringSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private val badPath = "/nonexistent/loop-guard-wiring-test.md"

  /** 恒定返回同一 Read 调用（唯一 id）；第 maxToolCalls 次后返回纯文本收尾。 */
  private class LoopLlm(requests: Ref[IO, List[LlmRequest]], maxToolCalls: Int) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)).drain ++
        Stream.eval(requests.get.map(_.size)).flatMap { n =>
          if n <= maxToolCalls then
            Stream(
              StreamChunk.ToolCallChunk(
                ToolCall(s"tu-$n", "Read", JsonObject("file_path" -> badPath.asJson))
              ),
              StreamChunk.Done(None, None)
            )
          else Stream(StreamChunk.TextDelta("all done"), StreamChunk.Done(None, None))
        }

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

  /** 收紧阈值 soft=1 / hard=3 落盘（loadConfig 热读路径）。 */
  private def seedLoopConfig(tmp: os.Path): Unit =
    os.makeDir.all(tmp / "data")
    os.write.over(
      tmp / "data" / "nebflow.json",
      """{"supervision":{"loopGuard":{"enabled":true,"identicalFailureSoft":1,
        |"identicalFailureHard":3,"identicalCallHard":10,"identicalTextHard":10,"crossTurnFailureTurns":3,
        |"exemptTools":[]}}}""".stripMargin.replaceAll("\n", "")
    )

  private def waitFor[A](ref: Ref[IO, A], pred: A => Boolean, msg: String, timeoutMs: Long): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      ref.get.map(pred).flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"$msg in time"))
          else IO.sleep(100.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  private def textOf(req: LlmRequest): String =
    req.messages.map(_.textContent).mkString("\n")

  /** 驱动一轮完整场景。depth=0 → root（L1 降级路径）；depth=1 → sub-agent。 */
  private def drive(
      actorName: String,
      depth: Int,
      maxToolCalls: Int,
      secondTurn: Option[String],
      expectFirstTurnBusy: Boolean = true
  ): (List[LlmRequest], List[io.circe.Json], Map[String, AgentRecord]) =
    val system = ActorSystem(s"loop-guard-$actorName")
    val tmp = os.temp.dir()
    seedLoopConfig(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        llm = new LoopLlm(requests, maxToolCalls)
        resources <- mkResources(system, tmp, llm)
        wsEvents <- IO.ref(List.empty[io.circe.Json])
        def_ = AgentDef(name = "Worker", description = "loop fixture", tools = List("Read"), systemPrompt = "")
        sid = s"loop-$actorName"
        actor <- system.spawn(
          AgentActor(
            agentDef = def_,
            resources = resources,
            wsSend = j => wsEvents.update(_ :+ j),
            depth = depth,
            parentRef = None,
            sessionId = Some(sid),
            sessionName = Some(actorName),
            safetyMode = "auto-all"
          ),
          actorName
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, actor, AgentKind.Ephemeral, sid, None)))
        _ <- actor ! AgentCommand.UserInput("do the thing", None, Some("cmid-1"))
        _ <-
          if expectFirstTurnBusy then
            waitFor(wsEvents, evs => evs.exists(j => (j \\ "busy").exists(!_.asBoolean.getOrElse(true))), s"$actorName first turn did not finish", 30000)
          else
            // freeze 路径无 busy=false 终态帧——轮询 registry Frozen 终态（即
            // root-degrade 断言依赖的状态迁移本身），取代固定 2500ms 收集窗
            // （满载下 freeze→registry 写入可滞后于窗口 → Processing 假读）
            waitFor(resources.agentRegistry, (reg: Map[String, AgentRecord]) =>
              reg.get(sid).exists(_.status == AgentStatus.Frozen), s"$actorName first turn did not freeze", 30000)
        _ <- secondTurn match
          case Some(msg) =>
            (actor ! AgentCommand.UserInput(msg, None, Some("cmid-2"))) *>
              // 同上：轮询 registry Frozen 终态，取代固定 1500ms 收集窗
              // （l2-freeze L2 复发冻结链在满载下可滞后——CI :217 Processing 假读）
              waitFor(resources.agentRegistry, (reg: Map[String, AgentRecord]) =>
                reg.get(sid).exists(_.status == AgentStatus.Frozen), s"$actorName turn-2 did not freeze", 30000)
          case None => IO.unit
        reqs <- requests.get
        evs <- wsEvents.get
        registry <- resources.agentRegistry.get
      yield (reqs.reverse, evs, registry)
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  end drive

  private def errorFrames(evs: List[io.circe.Json]): List[io.circe.Json] =
    evs.filter(j => (j \\ "type").exists(_.asString.contains("error")))

  test("L0+L1 (depth=1): soft reminder injected into round 2; hard terminates turn with loop error") {
    val (reqs, evs, _) = drive("l1-terminate", depth = 1, maxToolCalls = 10, secondTurn = None)
    // soft=1 → round1 Warn；round2 streak=2；round3 streak=3=hard → Terminate。
    // 恰好 3 次 LLM 请求（第 4 次永不再来——turn 已死）。
    assertEquals(reqs.size, 3, s"expected exactly 3 LLM calls (terminated at hard=3), got ${reqs.size}: ${reqs.map(textOf).mkString("\n---\n")}")
    // L0：round 2 的请求携带 loop 警告 system-reminder
    assert(textOf(reqs(1)).contains("Loop guard"), s"round-2 request must carry the L0 reminder:\n${textOf(reqs(1))}")
    // L1：WS error 帧携带 loop-detected 消息 + busy=false
    val errs = errorFrames(evs)
    assert(errs.nonEmpty, s"expected a WS error frame, got: $evs")
    val errText = errs.map(_.toString).mkString
    assert(errText.contains("loop-detected"), s"error frame must name the loop:\n$errText")
    assert(errText.contains("identical arguments failed 3 times"), s"error frame must carry the S1 message:\n$errText")
  }

  test("root exemption (depth=0): L1 degraded to Warn (turn survives hard), L2 recurrence still freezes") {
    // root（D3）：round 3 的 hard Terminate 被降级为 Warn——turn 不死，第 4 次
    // 请求照发；round 4 同 fp 复发 → recurrence L2 → 冻结（root 保留 L2，
    // 只豁免 L1——「root 错误由用户裁决」不等于放任复发循环）。
    val (reqs, evs, registry) = drive("root-degrade", depth = 0, maxToolCalls = 5,
      secondTurn = None, expectFirstTurnBusy = false)
    // r1(warn) r2 r3(terminate→degraded→续轮) r4(recurrence freeze，不续轮) = 4
    assertEquals(reqs.size, 4, s"root must survive L1 (round 4 dispatched) but freeze on L2 recurrence, got ${reqs.size}")
    assert(errorFrames(evs).isEmpty, s"root must NOT get a fatal error frame (L1 suppressed): ${errorFrames(evs)}")
    assert(textOf(reqs(1)).contains("Loop guard"), s"round-2 must still carry the L0 reminder:\n${textOf(reqs(1))}")
    val loopFrames = evs.filter(j => (j \\ "type").exists(_.asString.contains("loopDetected")))
    assert(loopFrames.nonEmpty, s"expected a loopDetected WS frame (L2 applies to root), got: $evs")
    val frozenRec = registry.get("loop-root-degrade")
    assert(frozenRec.exists(_.status == AgentStatus.Frozen), s"registry must mark the session Frozen, got: $frozenRec")
  }

  test("L2 recurrence (depth=1): terminated fp failing in the next turn freezes with loopDetected + Frozen") {
    // turn 1：3 连败 → L1 终止（fp 记入 terminatedFps，随 idle state 存活）。
    // turn 2：同 fp 首败 → recurrence → Freeze（WS loopDetected + registry Frozen）。
    val (reqs, evs, registry) = drive("l2-freeze", depth = 1, maxToolCalls = 10, secondTurn = Some("try again"))
    // turn 1 恰 3 请求；turn 2 第 4 请求复发 → Freeze（可能已派出第 5 请求——竞态窗内，
    // 不 pin 上限，pin 下限）
    assertEquals(reqs.size, 4, s"turn-2 recurrence freezes at round 4 with no further dispatch, got ${reqs.size}")
    val loopFrames = evs.filter(j => (j \\ "type").exists(_.asString.contains("loopDetected")))
    assert(loopFrames.nonEmpty, s"expected a loopDetected WS frame, got: $evs")
    val frozenRec = registry.get("loop-l2-freeze")
    assert(frozenRec.exists(_.status == AgentStatus.Frozen), s"registry must mark the session Frozen, got: $frozenRec")
  }

end LoopGuardWiringSpec
