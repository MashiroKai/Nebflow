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
  InteractionHubCommand,
  InteractionKind,
  InteractionReply,
  InteractionRequest,
  status
}
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ProjectTool}
import nebflow.core.{RateLimiter, SessionStore}
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
 * ask-death-exit 批（2026-10-28「黄色问题胶囊无问题仍显示计数」排查面①）——
 * **死亡出口待答槽收口**的端到端接线钉子。真实 AgentActor + 真实 InteractionHub
 * 驱动（WaitTimeoutAskUserWiringSpec 同款夹具，真阻塞调用方 = ProjectTool.pathPanel）。
 *
 * WHY（缺陷链）：#250②（2026-09-13）只给 Interrupt 接了 hub 槽回收
 * （`closePendingInteractionsForInterruptedTurn`，reason="turn-interrupted"）；
 * 同批按「禁扩面」登记未修的邻接 Exit —— `Stop`（含 deferred 兑现点）/ `Retry` /
 * `ResetSession` / `RestartAgent` —— 全部**只取消 turn 不清槽**：等待方已死，
 * hub 槽位 + 前端卡片/待答徽标继续挂（零超时纪律下永久僵尸；delegate 被父重启
 * /链取消/收殓走的恰是 Stop 家族，本批报障「无问题仍显示计数」的生命周期缺口）。
 *
 * 本批接线（幂等）：四个死亡出口统一走同一收口点，reason 语义化 ——
 *   Stop/deferred-stop → "source-stopped"；Retry → "turn-interrupted"；
 *   ResetSession → "session-reset"；RestartAgent → "agent-restarted"。
 * 三钉：
 *   D1 Stop：pending 期间 Stop → askUserClosed(reason=source-stopped) + 槽回收 +
 *           旁观者（别的 sourceSession）不误伤；
 *   D2 ResetSession：pending 期间 /clear → askUserClosed(reason=session-reset) + 槽回收；
 *   D3 Retry：pending 期间 Retry → 旧槽 askUserClosed(reason=turn-interrupted) +
 *           重派后新槽成立（一问一槽不粘连）。
 *
 * 验红变异：摘掉 AgentProcessing.Stop / resetSessionHandler /
 * retryFromCheckpoint 里新增的 closePendingInteractionsForInterruptedTurn 调用
 * ⇒ 各自用例红（askUserClosed 不广播、快照槽滞留）；恢复后绿。
 */
class AskDeathExitCleanupSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  /** 首轮决定性工具调用（Project/pathPanel 真阻塞档）；次轮 gate 后文本收尾。 */
  private class AskLlm(
    requests: Ref[IO, List[LlmRequest]],
    secondGate: Deferred[IO, Unit]
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
              StreamChunk.ToolCallChunk(ToolCall("tu-1", "Project", JsonObject.empty)),
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

  private def mkRecordingActor(sink: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => sink.update(_ :+ cmd).as(loop))
    loop

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

  private def setup(name: String): Fixture =
    val system = ActorSystem(s"ask-death-exit-$name")
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "data")
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    val program = for
      secondGate <- Deferred[IO, Unit]
      requests <- IO.ref(List.empty[LlmRequest])
      llm = new AskLlm(requests, secondGate)
      resources <- mkResources(system, tmp, llm)
      wsEvents <- IO.ref(List.empty[Json])
      hub <- system.spawn(InteractionHub(), s"hub-$name")
      _ <- resources.interactionHubRef.set(Some(hub))
      _ <- hub ! InteractionHubCommand.RegisterRoot("death-exit-sid", (j: Json) => wsEvents.update(_ :+ j))
      def_ = AgentDef(
        name = "Worker",
        description = "ask death exit fixture",
        tools = List("Project"),
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
          sessionId = Some("death-exit-sid"),
          sessionName = Some(s"fixture-$name"),
          safetyMode = "auto-all"
        ),
        s"agent-$name"
      )
      now <- IO(System.currentTimeMillis())
      _ <- resources.agentRegistry.update(
        _ + ("death-exit-sid" -> AgentRecord(
          sessionId = "death-exit-sid",
          ref = actor,
          kind = AgentKind.Delegate,
          rootSessionId = "death-exit-sid",
          parentRef = Some(parentRef),
          startedAt = now,
          status = AgentStatus.Processing,
          lastActivityMs = now
        ))
      )
      _ <- actor ! AgentCommand.UserInput("ask me", None, Some(s"cmid-$name"))
      // 等 pathPanel 派发的 AskUser 卡真正挂上（本用例的一切判据的前提）。
      _ <- waitFor(
        wsEvents,
        evs => evs.exists(j => j.hcursor.get[String]("type").toOption.contains("askUser")),
        "askUser 卡未渲染"
      )
    yield Fixture(
      system,
      resources,
      hub,
      actor,
      "death-exit-sid",
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

  /** 旁观者槽（别的 sourceSession）——死亡出口清理不得扫射。 */
  private def spawnBystander(f: Fixture): IO[String] =
    IO.ref(Option.empty[List[String]]).flatMap { sinkGot =>
      f.system
        .spawn(
          Behaviors.receiveMessage[List[String]] { a => sinkGot.set(Some(a)).as(Behaviors.stopped) },
          s"bystander-${System.nanoTime()}"
        )
        .flatMap { sink =>
          val rid = "ask-bystander-deathexit"
          // ActorRef.! 返回投递 IO（有界邮箱 offer）——必须 sequenced，不能丢。
          (f.hub ! InteractionHubCommand.Request(
            InteractionRequest(
              requestId = rid,
              kind = InteractionKind.AskUser,
              payload = Json.obj("items" -> Json.arr(), "agentName" -> Json.fromString("Nebula")),
              reply = InteractionReply.AskUserReply(Some(sink)),
              rootSessionId = f.sid,
              sourceAgent = "Nebula",
              sourceSession = "node-bystander"
            )
          )).as(rid)
        } <* IO.sleep(150.millis)
    }

  private def closedFrames(evs: List[Json], requestId: String): List[Json] =
    evs.filter(j =>
      j.hcursor.get[String]("type").toOption.contains("askUserClosed") &&
        j.hcursor.get[String]("requestId").toOption.contains(requestId)
    )

  private def ourRequestId(evs: List[Json]): String =
    evs
      .filter(j => j.hcursor.get[String]("type").toOption.contains("askUser"))
      .flatMap(_.hcursor.get[String]("requestId").toOption)
      .headOption
      .getOrElse(throw new AssertionError("无 askUser 帧可取 requestId"))

  private def snapshotIds(hub: ActorRef[InteractionHubCommand]): IO[List[String]] =
    hub.?[List[Json]](reply => InteractionHubCommand.ListAllPendingAsks(reply)).map(
      _.flatMap(_.hcursor.get[String]("requestId").toOption)
    )

  // ── D1：Stop ──────────────────────────────────────────────────────────────
  test("D1 Stop：pending 期间 Stop → askUserClosed(reason=source-stopped) + 槽回收，旁观者不误伤") {
    val f = setup("stop")
    (for
      rid <- IO(ourRequestId(f.wsEvents.get.unsafeRunSync()))
      bystander <- spawnBystander(f)
      _ <- f.actor ! AgentCommand.Stop("test-stop")
      _ <- waitFor(
        f.wsEvents,
        evs => closedFrames(evs, rid).nonEmpty,
        "Stop 后未广播 askUserClosed（死亡出口收口未接线）"
      )
      evs <- f.wsEvents.get
      closed = closedFrames(evs, rid).last
      _ <- IO(assertEquals(closed.hcursor.get[String]("reason").toOption, Some("source-stopped")))
      snap <- snapshotIds(f.hub)
      _ <- IO(
        assertEquals(snap, List(bystander), "本会话槽必须回收，别的 sourceSession 槽必须存活")
      )
    yield ()).guarantee(f.cleanup)
  }

  // ── D2：ResetSession（/clear）────────────────────────────────────────────
  test("D2 ResetSession：pending 期间 /clear → askUserClosed(reason=session-reset) + 槽回收") {
    val f = setup("reset")
    (for
      rid <- IO(ourRequestId(f.wsEvents.get.unsafeRunSync()))
      _ <- f.actor ! AgentCommand.ResetSession
      _ <- waitFor(
        f.wsEvents,
        evs => closedFrames(evs, rid).nonEmpty,
        "ResetSession 后未广播 askUserClosed（死亡出口收口未接线）"
      )
      evs <- f.wsEvents.get
      closed = closedFrames(evs, rid).last
      _ <- IO(assertEquals(closed.hcursor.get[String]("reason").toOption, Some("session-reset")))
      snap <- snapshotIds(f.hub)
      _ <- IO(assertEquals(snap, Nil, "/clear 后不得残留本会话槽"))
    yield ()).guarantee(f.cleanup)
  }

  // ── D3：Retry ─────────────────────────────────────────────────────────────
  test("D3 Retry：pending 期间 Retry → 旧槽收口(reason=turn-interrupted) + 重派新槽成立") {
    val f = setup("retry")
    (for
      oldRid <- IO(ourRequestId(f.wsEvents.get.unsafeRunSync()))
      _ <- f.actor ! AgentCommand.Retry("test-retry")
      _ <- waitFor(
        f.wsEvents,
        evs => closedFrames(evs, oldRid).nonEmpty,
        "Retry 后旧槽未广播 askUserClosed（死亡出口收口未接线）"
      )
      evs <- f.wsEvents.get
      closed = closedFrames(evs, oldRid).last
      _ <- IO(assertEquals(closed.hcursor.get[String]("reason").toOption, Some("turn-interrupted")))
      // 重派 = 同一 tu-1 工具调用重跑 → pathPanel 再次提问 → 新槽（新 requestId）。
      // 判据取**最后**一条 askUser 帧（首条恒为旧槽的原始卡）。
      _ <- waitFor(
        f.wsEvents,
        evs =>
          evs.exists(j => j.hcursor.get[String]("type").toOption.contains("askUser")) &&
            ourRequestId(evs.reverse) != oldRid,
        "Retry 后重派腿未产生新 askUser 槽"
      )
      snap <- snapshotIds(f.hub)
      _ <- IO(
        assert(!snap.contains(oldRid), s"旧槽必须收口，快照=$snap")
      )
    yield ()).guarantee(f.cleanup)
  }

end AskDeathExitCleanupSpec
