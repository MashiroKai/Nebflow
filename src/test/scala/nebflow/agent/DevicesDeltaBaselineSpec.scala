package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord, messages}
import nebflow.core.SystemReminders
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
 * devoscfix 批（2026-09-17）· **F-C devices 差量基线每轮推进**的双向钉。
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
        case true => IO.unit
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

  /**
   * The devices reminder delta lines exactly as injected (🔴 not a private-fn call:
   * this reads the rendered request the model sees). Empty = no devices reminder.
   */
  private def deviceDeltaLines(req: LlmRequest): List[String] =
    val lines = req.messages.map(_.textContent).mkString("\n").split("\n").toList
    val idx = lines.indexWhere(_.contains(DeviceMarker))
    if idx < 0 then Nil
    else lines.drop(idx + 1).takeWhile(l => l.startsWith("+") || l.startsWith("-"))

  private val peerY =
    PeerInfo(deviceId = "dev-y", deviceName = "DEVY", platform = "linux", address = "http://127.0.0.1:9")

  private val peerZ =
    PeerInfo(deviceId = "dev-z", deviceName = "DEVZ", platform = "linux", address = "http://127.0.0.1:8")

  private val peerX =
    PeerInfo(deviceId = "dev-x", deviceName = "DEVX", platform = "macos", address = "http://127.0.0.1:7")

  /**
   * 画像 store 原文（`<dataRoot>/neblink/device-profiles.json`）——条目文本变更
   * （画像后缀）的真源，走真 decode + 真渲染路径。
   *
   * 🔴 键集必须与 `ProfileField`（`value` + `probedAt`，无 `stale` 键）逐字对齐：
   * 缺 `probedAt` 会让**整个** `fields` map decode 失败 ⇒ `DeviceProfile.load`
   * fail-closed 回空 Map ⇒ 后缀永不渲染（本 spec 首轮即踩此坑，读数见
   * `logs/03_test_fixed.log`：差量只有 `+DEVZ/-DEVY`、无 DEVX 条目）。
   */
  private def writeProfile(dataRoot: os.Path, cwd: String): Unit =
    val p = dataRoot / "neblink" / "device-profiles.json"
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
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "devices-delta-session"
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
      // 🔴 清理必须 best-effort：后台 fiber（sync loop / removePeer 宽限 / capture 清扫）
      // 可能正往 `tmp/data` 里写 ⇒ `os.remove.all` 会抛 `DirectoryNotEmptyException`
      // **顶掉真正的断言异常**（首轮变异验红即被顶掉，读数见
      // `logs/07_mutation_M2_fc.log`：报的是清理异常而非断言）。清理失败只留 temp
      // 目录，绝不得掩盖测试结论。
      var attempts = 0
      var removed = false
      while !removed && attempts < 3 do
        attempts += 1
        try
          os.remove.all(tmp)
          removed = true
        catch case _: Exception => IO.sleep(200.millis).unsafeRunSync()
    end try
  }

  // ==================================================================
  // F-2（presdial 批 2026-09-19，`kaiflap-diag` §2.4 M1）——
  // **同一设备变化跨会话只计一次**（计数面 = `[devices]` 日志行）。
  //
  // 缺陷形态：`stableSnapshot.devices` 是**每 session 各自一套**基线（进程内 state），
  // 同一次 roster 变化会被「被真用户轮告知的 session 数」各计一遍（KAI 侧
  // 「1.2 s 两条同内容」的最可能解释）。修法：计数键 = 设备**成员集合**（deviceId 面）
  // + 进程内账本跨会话去重；**注入不受影响**（每会话仍各自收到差量）。
  //
  // 判据（双向）：
  //  (a) 两个真会话、同一次 roster 变化 ⇒ `[devices]` 行计数 **1**（改前 = 2，必红）；
  //  (b) 两个会话的请求里**都**含差量条目（注入是每会话的，不得被去重吞掉）。
  // ==================================================================

  private final class RemindersAppender
      extends ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent]:
    val lines = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
      lines.add(event.getFormattedMessage)

  private def captureRemindersLog[A](body: java.util.concurrent.ConcurrentLinkedQueue[String] => IO[A]): IO[A] =
    IO {
      org.slf4j.LoggerFactory.getLogger("nebflow.reminders") match
        case lb: ch.qos.logback.classic.Logger =>
          val appender = new RemindersAppender
          appender.setContext(lb.getLoggerContext)
          appender.start()
          lb.addAppender(appender)
          (lb, appender)
        case other => fail(s"expected a logback logger for the reminders channel, got $other")
    }.flatMap { (lb, appender) =>
      body(appender.lines).guarantee(IO(lb.detachAppender(appender)))
    }

  private val peerP1 =
    PeerInfo(deviceId = "dev-m1-p1", deviceName = "PEERP1", platform = "linux", address = "http://127.0.0.1:6")

  private val peerP2 =
    PeerInfo(deviceId = "dev-m1-p2", deviceName = "PEERP2", platform = "linux", address = "http://127.0.0.1:5")

  test("F-2 M1: 同一设备变化在 2 个会话只计一次（两侧仍各收到差量；改前 = 每会话各一行）") {
    val system = ActorSystem("devices-delta-m1")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    // 计数账本复位 ⇒ 本用例的读数不受同 JVM 内其他 suite/用例已计键影响（🔴 断言确定性）
    SystemReminders.DeviceChangeCount.reset()
    try
      val sidA = "devices-m1-a"
      val sidB = "devices-m1-b"
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        ms <- NeblinkService.create(0, dispatcher)
        _ = ms.setRelayClient(None)
        _ <- IO(RemoteExecutor.initialize(ms, dispatcher, None))
        // 基线 roster：只有 P1（两个会话的 lifecycle 轮都以此建立基线）
        _ <- ms.upsertPeer(peerP1)
        nebulaDef = AgentDef(name = "Nebula", description = "root under test", tools = List("Read"), systemPrompt = "")
        refA <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sidA),
            sessionName = Some("m1-a")
          ),
          sidA
        )
        refB <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sidB),
            sessionName = Some("m1-b")
          ),
          sidB
        )
        _ <- resources.agentRegistry.update(_ + (sidA -> AgentRecord(sidA, refA, AgentKind.Root, sidA, None)))
        _ <- resources.agentRegistry.update(_ + (sidB -> AgentRecord(sidB, refB, AgentKind.Root, sidB, None)))
        // ── 两个会话各一轮 lifecycle（建立各自的基线；本段不注入差量）──
        reqA1 <- userTurn(refA, requests, 1)
        reqB1 <- userTurn(refB, requests, 2)
        _ <- IO(assert(deviceDeltaLines(reqA1).isEmpty, s"A 的 lifecycle 轮不得注入差量: ${deviceDeltaLines(reqA1)}"))
        _ <- IO(assert(deviceDeltaLines(reqB1).isEmpty, s"B 的 lifecycle 轮不得注入差量: ${deviceDeltaLines(reqB1)}"))
        // ── 一次真实 roster 变化：新增成员 P2 ──
        _ <- ms.upsertPeer(peerP2)
        // 跨过两个 AgentCore 各自 `deviceInfoBlock` 的 30s memo 窗
        _ <- IO.sleep(cacheWindow)
        captured <- captureRemindersLog { lines =>
          for
            _ <- userTurn(refA, requests, 3)
            _ <- userTurn(refB, requests, 4)
            out <- IO(lines.toArray.toList.map(_.toString))
          yield out
        }
        reqs <- requests.get
        reqA2 = reqs(2)
        reqB2 = reqs(3)
      yield (reqA2, reqB2, captured)
      program.unsafeRunSync() match
        case (reqA2, reqB2, captured) =>
          val linesA = deviceDeltaLines(reqA2)
          val linesB = deviceDeltaLines(reqB2)
          assert(
            linesA.nonEmpty,
            s"会话 A 必须被告知这次 roster 变化（注入是每会话的）: ${reqA2.messages.map(_.textContent).mkString("\n")}"
          )
          assert(
            linesB.nonEmpty,
            s"会话 B 必须被告知这次 roster 变化（注入是每会话的）: ${reqB2.messages.map(_.textContent).mkString("\n")}"
          )
          assert(
            linesA.exists(_.contains("PEERP2")) && linesB.exists(_.contains("PEERP2")),
            s"A/B 都必须看到 +PEERP2（A=$linesA B=$linesB）"
          )
          val deviceLines = captured.filter(_.contains("[devices]"))
          assertEquals(
            deviceLines.size,
            1,
            s"同一设备变化在 2 个会话只计一次（改前 = 每个会话各一行）:\n${deviceLines.mkString("\n")}"
          )
          assertEquals(SystemReminders.DeviceChangeCount.total, 1L, "进程内计数 = 1")
      end match
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      var attempts = 0
      var removed = false
      while !removed && attempts < 3 do
        attempts += 1
        try
          os.remove.all(tmp)
          removed = true
        catch case _: Exception => IO.sleep(200.millis).unsafeRunSync()
    end try
  }

end DevicesDeltaBaselineSpec
