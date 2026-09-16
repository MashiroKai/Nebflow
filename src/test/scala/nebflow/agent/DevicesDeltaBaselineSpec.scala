package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, RemoteExecutor}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.neblink.{NeblinkService, PeerInfo}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}
import fs2.Stream

import scala.concurrent.duration.*

/** devoscfix 批（2026-09-17）· **F-C devices 差量基线每轮推进**的双向钉。
  *
  * 缺陷形态（诊断 §1 轻档 / §7 F-C）：`[devices] Devices changed:` 的差量基线
  * **只在 lifecycle 轮推进**（旧 `AgentCore.scala:865-870`）⇒ 一次真 roster 变更
  * 在其后**每一轮**被重新差量、逐轮复播同一条 ⇒ 诊断实测 09-16 `+KAI` 28 行 /
  * 23min 逐字相同（把「变更次数」高估达一个数量级）。
  *
  * 验收（作者三答② / P2，双向）：
  *  - (a) **同内容复播归零**：同一设备清单连续 N ≥ 5 轮 ⇒ 提示行计数 **0**
  *    （改前 = 每轮各重复一遍，本 spec 在基线上必红）；
  *  - (b) **真实 roster 变更仍提示**：成员增 / 成员删 / 条目文本变更（画像后缀）
  *    各一例 ⇒ 提示行**仍出**，且行格式逐字不变（`+<entry>` / `-<entry>`）。
  *
  * 测法：**真实 `AgentActor` 回合管线** —— 真 `NeblinkService`（peer 表即 roster 真源）
  * + 真 `RemoteExecutor` 装配 + 捕获真实 `LlmRequest` 的 mock LLM；断言打在
  * 「模型真正收到的那条 `Devices changed:` 提示行」上，不经任何私有函数。
  * 🔴 关键取舍：基线**只在提示行真的进了请求**时推进（见 `AgentCore` 实现处注释）
  * —— 未播报的真变更保持 pending，下一个真用户轮照常提示。
  */
class DevicesDeltaBaselineSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  /** `deviceInfoBlock` 的 30s 缓存窗口 ⇒ 让 peer 表变更可见需要跨过它一次。 */
  private val cacheWindow = 31.seconds

  private val DeviceMarker = "Devices changed:"

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
      """{"name":"Nebula","displayName":"Nebula","description":"devices-delta root","tools":["Read"]}"""
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** One real user turn; returns the LlmRequest the model actually received. */
  private def userTurn(
    ref: ActorRef[AgentCommand],
    reqs: Ref[IO, List[LlmRequest]],
    n: Int
  ): IO[LlmRequest] =
    (ref ! AgentCommand.UserInput(s"turn $n", None, Some(s"cmid-$n"))) *>
      waitUntil(20.seconds)(reqs.get.map(_.size >= n)) *>
      reqs.get.map(_(n - 1))

  /** The devices reminder delta lines exactly as injected (🔴 not a private-fn call:
    * this reads the rendered request the model sees). Empty = no devices reminder. */
  private def deviceDeltaLines(req: LlmRequest): List[String] =
    val lines = req.messages.map(_.textContent).mkString("\n").split("\n").toList
    val idx   = lines.indexWhere(_.contains(DeviceMarker))
    if idx < 0 then Nil
    else lines.drop(idx + 1).takeWhile(l => l.startsWith("+") || l.startsWith("-"))

  private val peerY = PeerInfo(deviceId = "dev-y", deviceName = "DEVY", platform = "linux", address = "http://127.0.0.1:9")
  private val peerZ = PeerInfo(deviceId = "dev-z", deviceName = "DEVZ", platform = "linux", address = "http://127.0.0.1:8")
  private val peerX = PeerInfo(deviceId = "dev-x", deviceName = "DEVX", platform = "macos", address = "http://127.0.0.1:7")

  /** 画像 store 原文（`<dataRoot>/neblink/device-profiles.json`）——条目文本变更
    * （画像后缀）的真源，走真 decode + 真渲染路径。
    *
    * 🔴 键集必须与 `ProfileField`（`value` + `probedAt`，无 `stale` 键）逐字对齐：
    * 缺 `probedAt` 会让**整个** `fields` map decode 失败 ⇒ `DeviceProfile.load`
    * fail-closed 回空 Map ⇒ 后缀永不渲染（本 spec 首轮即踩此坑，读数见
    * `logs/03_test_fixed.log`：差量只有 `+DEVZ/-DEVY`、无 DEVX 条目）。 */
  private def writeProfile(dataRoot: os.Path, cwd: String): Unit =
    val p   = dataRoot / "neblink" / "device-profiles.json"
    val now = System.currentTimeMillis()
    os.makeDir.all(p / os.up)
    os.write.over(
      p,
      s"""{"version":1,"profiles":{"dev-x":{"deviceId":"dev-x","deviceName":"DEVX","platform":"macos",""" +
        s""""addressKey":"","probedAt":$now,"probeVersion":1,"available":true,""" +
        s""""fields":{"cwd":{"value":"$cwd","probedAt":$now}}}}}"""
    )

  test("P2: 同内容复播归零（N=6 轮）+ 真实变更（成员增/删 + 画像文本）仍提示") {
    val system = ActorSystem("devices-delta")
    val tmp    = os.temp.dir()
    seedNebula(tmp)
    val prevRoot  = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid    = "devices-delta-session"
      val dataRt = tmp / "data"
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
        // 真 roster（NeblinkService peer 表）+ 真 RemoteExecutor 装配 ⇒ `# Devices`
        // 段由 `AgentCore.deviceInfoBlock` 从真服务读取（不走任何测试后门）
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        ms <- NeblinkService.create(0, dispatcher)
        _ = ms.setRelayClient(None)
        _ <- IO(RemoteExecutor.initialize(ms, dispatcher, None))
        // 基线态：X（带画像后缀 cwd=/p1）+ Y 已在 roster 里（turn 1 的 lifecycle
        // 快照即包含它们 ⇒ 后续才有「成员删」与「文本变更」可观测）
        _ <- IO(writeProfile(dataRt, "/p1"))
        _ <- ms.upsertPeer(peerX)
        _ <- ms.upsertPeer(peerY)
        nebulaDef = AgentDef(name = "Nebula", description = "root under test", tools = List("Read"), systemPrompt = "")
        actorRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("devices-delta")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None)))
        // ── turn 1：lifecycle 轮（基线在此建立；本段不注入差量）──
        req1 <- userTurn(actorRef, requests, 1)
        baseLines = deviceDeltaLines(req1)
        _ <- IO(assert(baseLines.isEmpty, s"lifecycle 轮不得注入 devices 差量行: $baseLines"))
        // ── 真实变更：成员增（Z）+ 成员删（Y，15s 宽限）+ 条目文本变更（X 画像 p1→p2）──
        _ <- IO(writeProfile(dataRt, "/p2"))
        _ <- ms.removePeer("dev-y")
        _ <- ms.upsertPeer(peerZ)
        // 跨过 `deviceInfoBlock` 的 30s 缓存窗（同轮也覆盖 15s 的 removePeer 宽限）
        _ <- IO.sleep(cacheWindow)
        // ── turn 2：真变更必须仍提示（三类各一例）──
        req2 <- userTurn(actorRef, requests, 2)
        lines2 = deviceDeltaLines(req2)
        // ── turn 3..8：同内容连续 6 轮（≥5）⇒ 提示行计数必须为 0 ──
        _ <- (3 to 8).toList.traverse_(n => userTurn(actorRef, requests, n).void)
        reqs <- requests.get
        replayLines = (3 to 8).toList.flatMap(n => deviceDeltaLines(reqs(n - 1)))
        req3 <- IO.pure(reqs(2))
        lines3 = deviceDeltaLines(req3)
      yield
        // ── P2(b) 真变更仍提示：成员增 / 成员删 / 条目文本变更各一例 ──
        assert(lines2.nonEmpty, s"真实 roster 变更必须仍注入 devices 提示行:\n${req2.messages.map(_.textContent).mkString("\n")}")
        assert(lines2.exists(l => l.contains("DEVZ")), s"成员增（+Z）缺失: $lines2")
        assert(lines2.exists(l => l.contains("DEVY")), s"成员删（-Y）缺失: $lines2")
        assert(lines2.exists(l => l.contains("cwd=/p2")), s"条目文本变更（新画像后缀）缺失: $lines2")
        assert(lines2.exists(l => l.contains("cwd=/p1")), s"条目文本变更（旧画像后缀）缺失: $lines2")
        // 行格式逐字不变：新增行 `+<entry>`、消失行 `-<entry>`（与旧行为同形）
        assert(
          lines2.filter(_.contains("DEVZ")).forall(_.startsWith("+")),
          s"新增条目必须仍是 `+<entry>` 形态: $lines2"
        )
        assert(
          lines2.filter(_.contains("DEVY")).forall(_.startsWith("-")),
          s"消失条目必须仍是 `-<entry>` 形态: $lines2"
        )
        assertEquals(lines2.count(_.contains("cwd=/p2")), 1, s"文本变更新形态恰一行: $lines2")
        assertEquals(lines2.count(_.contains("cwd=/p1")), 1, s"文本变更旧形态恰一行: $lines2")
        // ── P2(a) 同内容复播归零（6 轮 ≥ 5）──
        assertEquals(
          lines3,
          Nil,
          s"改后：变更播报后的下一轮不得复播（真变更已在 turn 2 告知）: $lines3"
        )
        assertEquals(replayLines, Nil, s"改后：turn 3..8 逐轮复播计数必须为 0（改前 = 每轮各重复一遍）: $replayLines")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

end DevicesDeltaBaselineSpec
