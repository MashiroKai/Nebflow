package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentStreamEvent, sessionId, status}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, MemoryQueue}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{
  FallbackAttempt,
  LlmHandle,
  LlmMeta,
  LlmRequest,
  LlmResponse,
  MemoryStore,
  PathUtil,
  StreamChunk,
  TokenUsage
}

import scala.concurrent.duration.*

/**
 * 记忆轨 subagent 面板可见性（B 腿，2026-09-15 压缩管线三件批）。
 *
 * **作者规格（逐字）**：「我的期待是 subagent 直接根据队列中的记忆来编辑记忆文件，
 * 压缩好了之后，正好合成注入新的上下文记忆」+ 现场疑问「我在 subagent 面板里并没有
 * 看到这样一个 agent 在跑」。
 *
 * **考古结论**：本轨本来就是真 subagent（独立 `AgentActor` + `AgentKind.Ephemeral`
 * 注册），不是 Nebula 内联自干；缺的只是**事件接线**——`spawn` 把 `wsSend` 传成恒
 * `IO.unit`，于是注册表有条目、前端零活帧，而面板行**由 `agentStart` 活帧创建**
 * ⇒ 正常会话里这一行从不出现。
 *
 * 本 spec 用**真 `MemoryTrack.run` + 真 ActorSystem + 真 wsSend 汇**取现场读数
 * （不是读源码推断——与 [[MemoryTrackActorIdContractSpec]] 同款取证口径）：
 *   1. [[MemoryTrack.panelWsSend]] 纯函数面：父 wsSend 可得 ⇒ 三键注入
 *      （`rootSessionId` 归桶键 / `sessionId` 路由键 / `nodeSessionId` 行键）；
 *      不可得 ⇒ 恒 no-op（改动前逐字同参，零行为漂移）。
 *   2. 端到端：真跑一轮 ⇒ 事件流里出现 `agentStart`（建行）与 `agentDone`（收行），
 *      且 `agentId == nodeSessionId == memconsolidate-*`（行键与快照键同键空间，
 *      `WebSocketRoutes.activeAgentEntryJson` 契约）。
 *
 * **LLM 桩必带 usage chunk（判红 d）**：`StreamChunk.Done(_, Some(TokenUsage(...)))`
 * ——缺 usage 会让 token 记账走估算路径，`emergency-compact` 可能撕掉本轮历史。
 *
 * 隔离：`PathUtil.setDataRoot(临时目录)`，不触 `~/.nebflow`。
 */
class MemoryTrackPanelVisibilitySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val RootSid = "spec-panel-root"

  /** 正常收尾的 LLM 桩：一轮文本 + **带 usage 的 Done**（判红 d）。 */
  private object CompletingLlm extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this spec"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(
        StreamChunk.TextDelta("[panel-visibility spec] consolidation round finished."),
        StreamChunk.Done(
          stopReason = Some("end_turn"),
          usage = Some(TokenUsage(inputTokens = 1200, outputTokens = 40)),
          meta = Some(
            LlmMeta(
              sessionId = req.sessionId,
              agentId = "memory-consolidator",
              providerId = "spec",
              model = "spec-model",
              durationMs = 5L
            )
          )
        )
      )

  end CompletingLlm

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = CompletingLlm,
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

  /** 轨内消费链的 agent 定义（生产从 dataRoot 装载；本 spec 只钉「存在」）。 */
  private def seedConsolidatorAgent(tmp: os.Path): Unit =
    val dir = tmp / "agents" / MemoryTrack.AgentName
    os.makeDir.all(dir)
    os.write.over(
      dir / "agent.json",
      s"""{"name":"${MemoryTrack.AgentName}","description":"spec consumer","preset":"general"}"""
    )
    os.write.over(dir / "system.md", "You are the memory consolidator (spec stub).")

  private def field(json: Json, key: String): Option[String] =
    json.hcursor.get[String](key).toOption

  private def ofType(events: List[Json], tpe: String): List[Json] =
    events.filter(e => field(e, "type").contains(tpe))

  // ── 1. 纯函数面：三键注入 / 不可得回落 ────────────────────────

  test("panelWsSend: 父 wsSend 可得 ⇒ 注入 rootSessionId / sessionId / nodeSessionId 三键") {
    val captured = scala.collection.mutable.ListBuffer.empty[Json]
    val base: Json => IO[Unit] = j => IO { captured += j; () }
    val send = MemoryTrack.panelWsSend(Some(base), rootSessionId = "root-1", sessionId = "memconsolidate-abcd1234")
    val frame = AgentStreamEvent
      .AgentStart("memory-consolidator", "desc", Some("memory-consolidation"))
      .toJson("memconsolidate-abcd1234", isSubagent = true, Some("memconsolidate-abcd1234"))
    send(frame).unsafeRunSync()
    assertEquals(captured.size, 1, "帧必须透传（不得吞）")
    val f = captured.head
    assertEquals(field(f, "type"), Some("agentStart"), "面板建行帧必须送达")
    assertEquals(field(f, "agentId"), Some("memconsolidate-abcd1234"), "行键 = 子会话自身 id")
    assertEquals(field(f, "rootSessionId"), Some("root-1"), "归桶键 = 被压缩会话")
    assertEquals(field(f, "sessionId"), Some("root-1"), "路由键（agentStart 自身无 sessionId ⇒ 缺省注入）")
    assertEquals(field(f, "nodeSessionId"), Some("memconsolidate-abcd1234"), "已有 nodeSessionId 不得被覆盖")
  }

  test("panelWsSend: 已有 sessionId 的帧不被覆盖（子会话用量不得写进根会话账）") {
    val captured = scala.collection.mutable.ListBuffer.empty[Json]
    val base: Json => IO[Unit] = j => IO { captured += j; () }
    val send = MemoryTrack.panelWsSend(Some(base), rootSessionId = "root-1", sessionId = "memconsolidate-abcd1234")
    val frame = AgentStreamEvent
      .UsageUpdate(inputTokens = 5, contextWindow = 1000, compactThreshold = 0.8, model = None)
      .toJson("memconsolidate-abcd1234", isSubagent = true, Some("memconsolidate-abcd1234"))
      .deepMerge(Json.obj("sessionId" -> Json.fromString("memconsolidate-abcd1234")))
    send(frame).unsafeRunSync()
    assertEquals(field(captured.head, "sessionId"), Some("memconsolidate-abcd1234"), "自身会话 id 优先")
  }

  test("panelWsSend: 父 wsSend 不可得 ⇒ 恒 no-op（与改动前逐字同参，零行为漂移）") {
    val send = MemoryTrack.panelWsSend(None, rootSessionId = "root-1", sessionId = "memconsolidate-abcd1234")
    val frame = AgentStreamEvent
      .AgentStart("memory-consolidator", "desc", None)
      .toJson("memconsolidate-abcd1234", isSubagent = true, Some("memconsolidate-abcd1234"))
    // 不抛、不产生任何副作用即为通过
    send(frame).unsafeRunSync()
    assert(true)
  }

  // ── 2. 端到端：真跑一轮 ⇒ 面板建行帧 + 收行帧 ──────────────────

  test("端到端：真跑一轮记忆轨 ⇒ 事件流出现 agentStart（建行）与 agentDone（收行），行键同键空间") {
    val system = ActorSystem("memtrack-panel")
    val tmp = os.temp.dir(prefix = "nb-memtrack-panel-")
    seedConsolidatorAgent(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp)
    val events: Ref[IO, List[Json]] = Ref.unsafe(Nil)
    val sink: Json => IO[Unit] = j => events.update(_ :+ j)

    def waitFor(pred: List[Json] => Boolean, what: String, timeout: FiniteDuration): IO[List[Json]] =
      def go(deadline: Long): IO[List[Json]] =
        events.get.flatMap { evs =>
          if pred(evs) then IO.pure(evs)
          else if System.currentTimeMillis() >= deadline then
            IO.raiseError(
              new AssertionError(
                s"$what never observed within $timeout; observed types = " +
                  s"${evs.flatMap(e => field(e, "type")).distinct.mkString(", ")}"
              )
            )
          else IO.sleep(100.millis) >> go(deadline)
        }
      go(System.currentTimeMillis() + timeout.toMillis)

    val program =
      for
        _ <- IO(
          os.write.over(
            MemoryStore.userMemoryPath,
            "# User\n\n## Panel Spec Section\n\n- existing line\n",
            createFolders = true
          )
        )
        _ <- IO(
          MemoryQueue.enqueue(
            "user",
            "append",
            Some("## Panel Spec Section"),
            None,
            Some("- panel-visibility spec note"),
            Some("spec"),
            MemoryQueue.TriggerManual,
            "Nebula"
          )
        )
        resources <- mkResources(system, tmp)
        // ── 被测：带父 wsSend 真跑一轮（生产调用面 AgentActor 同参）──
        runFiber <- MemoryTrack
          .run(resources, parentSessionId = Some(RootSid), parentDepth = 0, parentWsSend = Some(sink))
          .start
        started <- waitFor(evs => ofType(evs, "agentStart").nonEmpty, "agentStart", 60.seconds)
        startFrame = ofType(started, "agentStart").head
        _ <- IO(println(s"[B-leg reading] agentStart frame = ${startFrame.noSpaces}"))
        _ <- IO(
          println(
            s"[B-leg reading] rootSessionId = ${field(startFrame, "rootSessionId")}  " +
              s"sessionId = ${field(startFrame, "sessionId")}  nodeSessionId = ${field(startFrame, "nodeSessionId")}"
          )
        )
        runRes <- runFiber.joinWithNever.timeoutTo(
          30.seconds,
          IO.pure(MemoryTrack.Result(MemoryTrack.Status.Failed, "SPEC-TIMEOUT", 0))
        )
        settled <- events.get
        _ <- IO(println(s"[B-leg reading] track run status = ${runRes.status} detail = '${runRes.detail.take(200)}'"))
        _ <- IO(println("[B-leg reading] === FULL FRAME SET (after teardown) ==="))
        _ <- IO(settled.zipWithIndex.foreach((e, i) => println(s"[B-leg reading] #$i ${e.noSpaces}")))
        _ <- IO(println("[B-leg reading] === END FRAME SET ==="))
        // 收行帧在 cleanup（guarantee）里发，不依赖 agent 是否把回合跑完 ⇒ 等一小段即可。
        doneEvents <- waitFor(evs => ofType(evs, "agentDone").nonEmpty, "agentDone", 10.seconds)
        doneFrame = ofType(doneEvents, "agentDone").head
        // ── 断言 ──
        _ <- IO {
          val sid = field(startFrame, "agentId").getOrElse("")
          assert(sid.startsWith("memconsolidate-"), s"行键必须是子会话自身 id，实测 '$sid'")
          assertEquals(field(startFrame, "type"), Some("agentStart"), "建行帧")
          assertEquals(field(startFrame, "name"), Some(MemoryTrack.AgentName), "面板行名 = 整理 agent 名")
          assertEquals(field(startFrame, "rootSessionId"), Some(RootSid), "归桶键 = 被压缩会话（面板分桶）")
          assertEquals(field(startFrame, "sessionId"), Some(RootSid), "路由键 = 被压缩会话")
          assertEquals(field(startFrame, "nodeSessionId"), Some(sid), "行键 == nodeSessionId（快照/活帧同键空间）")
          // 收行帧：与建行帧**同一行键**（不同键 = 幽灵行）
          assertEquals(field(doneFrame, "type"), Some("agentDone"), "收行帧类型")
          assertEquals(field(doneFrame, "agentId"), Some(sid), "收行帧必须是同一行键（否则幽灵行）")
          // 会话级生命周期帧必须被滤掉（本轨子会话不是会话）
          val leaked = settled.filter(e => Set("done", "sessionBusy").contains(field(e, "type").getOrElse("")))
          assertEquals(leaked, Nil, s"会话级生命周期帧不得泄漏：$leaked")
          assertEquals(runRes.status, MemoryTrack.Status.Completed, s"轨必须正常收敛：'${runRes.detail}'")
        }
      yield ()
    program
      .guarantee(
        (IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
          IO(PathUtil.setDataRoot(prevRoot)) *>
          system.stopAll.attempt.void).attempt.void
      )
      .unsafeRunSync()
  }

end MemoryTrackPanelVisibilitySpec
