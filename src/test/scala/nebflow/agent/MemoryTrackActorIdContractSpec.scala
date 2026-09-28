package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentRecord, rootSessionId, sessionId, status}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{AgentControlTool, FileLockManager, MemoryQueue, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  MemoryStore,
  PathUtil,
  StreamChunk,
  ThinkingConfig
}

import scala.concurrent.duration.*

/**
 * 记忆轨 actor 身份契约（2026-09-13 面板可见性取证 C-2 判红跑）。
 *
 * 契约真源：`WebSocketRoutes.activeAgentEntryJson`（快照行键 `agentId == sessionId`）
 * ×`NodeRunner.spawnAgentActor:99`（actor 名默认 = sessionId）× 活帧
 * `agentId = ctx.self.path.name`（`AgentCore.emitStream`/`emitStreamIO`；re-pin
 * 2026-09-25：protocol.scala 三拆删除，原 `protocol.scala:803` 行号锚已漂移，
 * 改指现行发射单点）。三者一致时快照行与活帧行
 * 落在同一个键空间；不一致时会话在面板上出现「agentDone 清不掉的幽灵行」。
 *
 * 本 spec 用**真 `MemoryTrack.run` + 真 ActorSystem + 真注册表**取现场读数
 * （不是读源码推断）：
 *   1. 轨内注册表条目的 `rec.ref.path.name` 与 `rec.sessionId` 逐字符对照；
 *   2. 同一条目走**真 cancel 降级路径**（`AgentControlTool.doCancel`：本轨
 *      `supervisorRef = None` ⇒ Ephemeral 兜底分支）——断言 Stop 送达、注册表摘键、
 *      轨的 `Deferred` 由桥的死亡监视完成、`MemoryTrack.run` 收敛（不悬挂）。
 *
 * 确定性手段：LLM 桩恒 `Stream.never`（轨内唯一回合永不完成）⇒ 本轮运行的唯一
 * 终结者就是 cancel；轨内 `wsSend` 是空操作、`parentRef = None`，与生产同参。
 *
 * 隔离：`PathUtil.setDataRoot(临时目录)`（TaskListE2ESpec 同款），不触 `~/.nebflow`。
 */
class MemoryTrackActorIdContractSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val RootSid = "spec-c2-root"

  /** 恒挂起的 LLM 桩：轨内回合永不完成 ⇒ 唯一终结者 = cancel。 */
  private object HangingLlm extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this spec"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.never[IO]

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
      llm = HangingLlm,
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

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def waitForTrackEntry(resources: SharedResources, timeout: FiniteDuration): IO[AgentRecord] =
    def go(deadline: Long): IO[AgentRecord] =
      resources.agentRegistry.get.flatMap { m =>
        m.values.find(_.sessionId.startsWith("memconsolidate-")) match
          case Some(rec) => IO.pure(rec)
          case None =>
            if System.currentTimeMillis() >= deadline then
              IO.raiseError(
                new AssertionError(
                  s"track registry entry never appeared; registry keys = ${m.keys.toList.sorted}"
                )
              )
            else IO.sleep(20.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  end waitForTrackEntry

  /** 现场读数（全部来自运行时取值，不是源码推断）。 */
  private final case class Reading(
    actorName: String,
    sessionId: String,
    rootSessionId: String,
    kind: String,
    supervisorRefDefined: Boolean,
    nameEqualsSessionId: Boolean,
    cancelResult: Either[String, String],
    registryClearedAfterCancel: Boolean,
    settleMs: Long,
    runStatus: String,
    runDetail: String
  )

  private def observeAndCancel(resources: SharedResources): IO[Reading] =
    for
      runFiber <- MemoryTrack.run(resources, parentSessionId = Some(RootSid), parentDepth = 0).start
      rec <- waitForTrackEntry(resources, 20.seconds)
      name = rec.ref.path.name
      _ <- IO(println(s"[C-2 reading] actor name (rec.ref.path.name) = '$name' (len=${name.length})"))
      _ <- IO(
        println(s"[C-2 reading] sessionId (registry key)       = '${rec.sessionId}' (len=${rec.sessionId.length})")
      )
      _ <- IO(println(s"[C-2 reading] name == sessionId              = ${name == rec.sessionId}"))
      _ <- IO(
        println(
          s"[C-2 reading] kind = ${rec.kind}, supervisorRef.isDefined = ${rec.supervisorRef.isDefined}, rootSessionId = '${rec.rootSessionId}'"
        )
      )
      cancelRes <- AgentControlTool
        .doCancel(resources, rec, reason = "spec-c2", by = "MemoryTrackActorIdContractSpec")
        .map(_.left.map(_.message))
      _ <- IO(println(s"[C-2 reading] doCancel -> ${cancelRes.fold(e => s"Left($e)", s => s"Right($s)")}"))
      t0 <- IO.monotonic
      runRes <- runFiber.joinWithNever.timeoutTo(
        20.seconds,
        IO.pure(MemoryTrack.Result(MemoryTrack.Status.Failed, "SPEC-TIMEOUT: the track never settled after cancel", 0))
      )
      t1 <- IO.monotonic
      registryAfter <- resources.agentRegistry.get
      _ <- IO(
        println(
          s"[C-2 reading] track run settled in ${(t1 - t0).toMillis}ms: status=${runRes.status} detail='${runRes.detail}'"
        )
      )
      _ <- IO(println(s"[C-2 reading] registry entry removed after cancel = ${!registryAfter.contains(rec.sessionId)}"))
    yield Reading(
      actorName = name,
      sessionId = rec.sessionId,
      rootSessionId = rec.rootSessionId,
      kind = rec.kind.toString,
      supervisorRefDefined = rec.supervisorRef.isDefined,
      nameEqualsSessionId = name == rec.sessionId,
      cancelResult = cancelRes,
      registryClearedAfterCancel = !registryAfter.contains(rec.sessionId),
      settleMs = (t1 - t0).toMillis,
      runStatus = runRes.status.toString,
      runDetail = runRes.detail
    )

  /** 夹具：临时 dataRoot + 一条 pending 账目 + 真 ActorSystem；读数后统一清理。 */
  private def withReading(body: Reading => IO[Unit]): IO[Unit] =
    val system = ActorSystem("memtrack-actorid")
    val tmp = os.temp.dir(prefix = "nb-memtrack-actorid-")
    seedConsolidatorAgent(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp)
    val program =
      for
        _ <- IO(
          os.write
            .over(MemoryStore.userMemoryPath, "# User\n\n## C-2 spec section\n\n- existing\n", createFolders = true)
        )
        _ <- IO(
          MemoryQueue.enqueue(
            "user",
            "append",
            Some("## C-2 spec section"),
            None,
            Some("- C-2 spec note"),
            Some("spec"),
            MemoryQueue.TriggerManual,
            "Nebula"
          )
        )
        resources <- mkResources(system, tmp)
        reading <- observeAndCancel(resources)
        _ <- body(reading)
      yield ()
    program.guarantee(
      (IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(prevRoot)) *>
        system.stopAll.attempt.void).attempt.void
    )

  end withReading

  test("契约：轨内注册表条目的 actor 名 == sessionId（逐字符；活帧 agentId 与快照行键同键空间）"):
    withReading { reading =>
      IO {
        assert(
          reading.nameEqualsSessionId,
          s"actor name '${reading.actorName}' must equal sessionId '${reading.sessionId}' " +
            "(WebSocketRoutes.activeAgentEntryJson / NodeRunner.spawnAgentActor:99 contract)"
        )
        assert(
          !reading.actorName.startsWith("memory-consolidator-"),
          s"the old prefixed actor name must not come back: '${reading.actorName}'"
        )
        assertEquals(reading.kind, "Ephemeral", "本轨注册 kind 不变")
        assert(!reading.supervisorRefDefined, "本轨无 supervisor（降级 cancel 路径的前提）")
        assertEquals(reading.rootSessionId, RootSid, "rootSessionId = 被压缩会话（面板分桶键）不变")
      }
    }

  test("cancel 路径不受影响：无 supervisor 降级路径停掉轨内 actor、注册表摘键、运行收敛（零悬挂）"):
    withReading { reading =>
      IO {
        assert(reading.cancelResult.isRight, s"cancel must succeed: ${reading.cancelResult}")
        assert(
          reading.cancelResult.toOption.exists(_.contains("fallback path")),
          s"本轨无 supervisor ⇒ 必走降级 Stop 路径: ${reading.cancelResult}"
        )
        assert(reading.registryClearedAfterCancel, "cancel 后注册表键必须摘除")
        assert(
          reading.runStatus == "Failed" && reading.runDetail.contains("agent stopped"),
          s"cancel 必须经桥的死亡监视收敛本轨运行: status=${reading.runStatus} detail='${reading.runDetail}'"
        )
        assert(reading.settleMs < 10_000L, s"收敛必须及时（实测 ${reading.settleMs}ms）")
      }
    }

end MemoryTrackActorIdContractSpec
