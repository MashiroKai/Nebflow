package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord, messages}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, RemoteExecutor}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.neblink.{NeblinkService, PeerInfo}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}
import fs2.Stream

import scala.concurrent.duration.*

/**
 * isofix 批（2026-09-17）· **跨 suite 单例 memo 串扰**的最小判别钉（承重负控载体）。
 *
 * 缺陷形态（判据 = `.nebflow/reports/20260917_devicesred-diag.md` §3 四要素链）：
 * `object AgentActor extends AgentCore` 上的 `deviceInfoCache`（`# Devices` 块的 30s
 * memo）**只按时间窗失效、且短路在读 `RemoteExecutor.current` 之前** ⇒ 同 JVM 内
 * `RemoteExecutor.initialize` 把全局执行器**重新指向**另一个 `NeblinkService` 后，
 * memo 里上一个执行器的设备清单在窗口内仍被复用。
 *
 * 本 spec 把该机制**在同一 suite 内两次 `initialize` 之间**确定性地钉住（不依赖
 * 邻居 suite 的构成、不需 sleep）：
 *  - 用例 1（承重基座）：executor **α**（peer 表 = `peer-one`）跑一个真回合 ⇒
 *    α 的设备块必须进了模型收到的请求（**这同时证明 memo 被写过**）；
 *  - 用例 2（隔离钉）：换源到 executor **β**（peer 表 = `DEVX`/`DEVY`）后**首个**
 *    回合 ⇒ 必须实读 β，**不得**残留 α 的 `peer-one`。
 *
 * 读数口径：memo 未带源身份时（修复前）用例 2 必红（读到的仍是 α 块）；memo 键含
 * 源身份后（修复后）必绿。故本 spec 即「修法真的承重」的可推翻读数载体。
 *
 * 🔴 本 spec 不改动 `DevicesDeltaBaselineSpec` 的任何字节；两 spec 各钉一层
 * （本 spec 钉**机制**、DDB 钉**产品语义**），断言口径互不替代。
 */
class DeviceInfoCacheIsolationSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val peerOne =
    PeerInfo(deviceId = "peer-1", deviceName = "peer-one", platform = "macos", address = "http://127.0.0.1:9")

  private val peerX =
    PeerInfo(deviceId = "dev-x", deviceName = "DEVX", platform = "macos", address = "http://127.0.0.1:7")

  private val peerY =
    PeerInfo(deviceId = "dev-y", deviceName = "DEVY", platform = "linux", address = "http://127.0.0.1:8")

  private class CaptureLlm(requests: Ref[IO, List[LlmRequest]]) extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)).drain ++
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

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
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks-ui"),
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

  private def seedNebula(tmp: os.Path): Unit =
    val dir = tmp / "agents" / "Nebula"
    os.makeDir.all(dir)
    os.write.over(
      dir / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"memo-isolation root","tools":["Read"]}"""
    )

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

  /** 清 temp 树必须 best-effort（后台 fiber 可能仍在写）——不得顶掉断言异常。 */
  private def cleanupTree(tmp: os.Path): IO[Unit] =
    IO.blocking {
      var attempts = 0
      var removed = false
      while !removed && attempts < 3 do
        attempts += 1
        try
          os.remove.all(tmp)
          removed = true
        catch case _: Exception => Thread.sleep(200)
    }

  private def turnBody(
    system: ActorSystem,
    tmp: os.Path,
    tag: String,
    peers: List[PeerInfo]
  ): IO[String] =
    val sid = s"devices-memo-$tag"
    for
      requests <- IO.ref(List.empty[LlmRequest])
      resources <- mkResources(system, tmp, CaptureLlm(requests))
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      ms <- NeblinkService.create(0, dispatcher)
      _ = ms.setRelayClient(None)
      // 🔴 被钉的那个动作：换源 = 把全局执行器重新指向本用例自己的 service
      _ <- IO(RemoteExecutor.initialize(ms, dispatcher, None))
      _ <- peers.traverse_(p => ms.upsertPeer(p))
      nebulaDef = AgentDef(
        name = "Nebula",
        description = "memo-isolation root",
        tools = List("Read"),
        systemPrompt = ""
      )
      ref <- system.spawn(
        AgentActor(
          agentDef = nebulaDef,
          resources = resources,
          wsSend = _ => IO.unit,
          depth = 0,
          sessionId = Some(sid),
          sessionName = Some(s"memo-$tag")
        ),
        sid
      )
      _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
      _ <- ref ! AgentCommand.UserInput(s"turn 1", None, Some(s"$tag-cmid-1"))
      _ <- waitUntil(20.seconds)(requests.get.map(_.nonEmpty))
      reqs <- requests.get
    // `# Devices` 块由 `PromptSections` 装配进 system 面（`AgentCore.scala:662`
    // `devInfo = deviceInfoBlock` ⇒ `systemDynamic`/`systemStable`），**不在**
    // messages 里 —— 与 DDB 只取 messages 的差量行口径不同，本钉必须三面并取。
    yield (reqs.head.systemStable.toList ++ reqs.head.systemDynamic.toList ++
      reqs.head.messages.map(_.textContent)).mkString("\n")

    end for

  end turnBody

  /** 装配一个真回合，返回模型真正收到的那条请求全文；退出前恢复全局态并清 temp。 */
  private def assembleOneTurn(peers: List[PeerInfo], tag: String): IO[String] =
    for
      tmp <- IO.blocking(os.temp.dir(prefix = s"nb-devices-memo-$tag-"))
      prevRoot <- IO(PathUtil.dataRoot)
      prevLlmLog <- IO(nebflow.core.LlmLogWriter.isEnabled)
      system <- IO(ActorSystem(s"devices-memo-$tag"))
      _ <- IO(seedNebula(tmp))
      _ <- IO(PathUtil.setDataRoot(tmp / "data"))
      _ <- IO(nebflow.core.LlmLogWriter.setEnabled(false))
      text <- turnBody(system, tmp, tag, peers).guarantee(
        system.stopAll.attempt.void *>
          IO(PathUtil.setDataRoot(prevRoot)) *>
          IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog))
      )
      _ <- cleanupTree(tmp)
    yield text

  test("承重基座：executor α（peer-one）回合 ⇒ α 的设备块确实进了模型请求（memo 被写过）") {
    assembleOneTurn(List(peerOne), "alpha").map { text =>
      assert(
        text.contains("peer-one"),
        s"α 回合必须读到自身 service 的设备清单（否则本钉不承重）：\n$text"
      )
      assert(!text.contains("DEVX"), s"α 回合不得出现 β 的设备：\n$text")
    }
  }

  test("隔离钉：换源 α → β 后首个回合必须实读新源，不得残留 α 的陈旧 memo") {
    assembleOneTurn(List(peerX, peerY), "beta").map { text =>
      assert(text.contains("DEVX"), s"换源后首个回合缺 β 设备 DEVX（= 复用了陈旧 memo）：\n$text")
      assert(text.contains("DEVY"), s"换源后首个回合缺 β 设备 DEVY（= 复用了陈旧 memo）：\n$text")
      assert(!text.contains("peer-one"), s"换源后首个回合残留 α 的 peer-one（源身份未进 memo 键）：\n$text")
    }
  }

end DeviceInfoCacheIsolationSpec
