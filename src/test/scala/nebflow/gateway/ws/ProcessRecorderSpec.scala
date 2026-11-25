package nebflow.gateway.ws

import nebflow.gateway.*

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig, UiMessage}

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

/**
 * 进程级 ProcessRecorder 行为 spec（process-recorder 批 2026-11-01 F1；设计稿 §5
 * S1-S6 + P1/P2/P4 防复活钉；spec 同族 = `DelegateContinuationWsSendSpec`）。
 *
 * 被钉对象 = 「录制从腿的行李升格为进程基础设施」的机制链：
 *   1. `ProcessRecorder` 挂 `wsHub.registerListener`（全部腿的帧唯一汇聚点）；
 *   2. 按帧内 `nodeSessionId`（`team-` 前缀规整）懒建缓冲，落行粒度 = 工具轮；
 *   3. agent\* 四分支已从 `makeRecordingWsSend` 整体迁除（单写者，禁共存/双写）；
 *   4. 写路径全走 `SessionStore.appendUiMessages` / `markRoundTerminal`（复用去抖/
 *      原子/封顶，不绕行）；
 *   5. boot 链注册先于 startupMount（腿④⑤的帧自恢复第一帧起有录制）。
 *
 * S1 帧→行；S2 `team-` 归属键；S3 单写者（行为级）；S4 写路径契约（源码钉）；
 * S5 root 零漂移（root 分支未被本批触碰的行为钉 + 录制器不接 root 帧）；S6 孤儿
 * 缓冲扫；S7 真 WsHub 集成（生产装配形态：listener 位 + 控制帧/root 帧不录 +
 * 耗时读数）；P1/P2/P4 = 源码级防复活钉。
 */
class ProcessRecorderSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-process-recorder"
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "sessions")

  private def mkStore: SessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks")

  /** DelegateScopeRecoveryWsSendSpec 同款最小 SharedResources（本 spec 只需要 sessionStore）。 */
  private def mkResources(store: SessionStore): SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = store,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  /** 磁盘行读数：flush 后读 `.ui.json`，返回 (type → 行数) 与原始行列表。 */
  private def readRows(sid: String): IO[(Map[String, Int], List[Json])] =
    IO.blocking {
      val f = tempRoot / "sessions" / s"$sid.ui.json"
      if !os.exists(f) then (Map.empty[String, Int], Nil)
      else
        val rows = io.circe.parser.parse(os.read(f)).fold(_ => Nil, _.asArray.get.toList)
        (rows.groupMapReduce(_.hcursor.downField("type").as[String].getOrElse(""))(_ => 1)(_ + _), rows)
    }

  private def textFrame(nsid: String, delta: String): Json =
    Json.obj(
      "type" -> "agentTextDelta".asJson,
      "agentId" -> "nebflow/general".asJson,
      "nodeSessionId" -> nsid.asJson,
      "delta" -> delta.asJson
    )

  private def thinkingFrame(nsid: String, delta: String): Json =
    Json.obj("type" -> "agentThinking".asJson, "nodeSessionId" -> nsid.asJson, "delta" -> delta.asJson)

  private def toolEndFrame(nsid: String, label: String, summary: String = "did a thing"): Json =
    Json.obj(
      "type" -> "agentToolEnd".asJson,
      "nodeSessionId" -> nsid.asJson,
      "label" -> label.asJson,
      "summary" -> summary.asJson,
      "content" -> "out".asJson,
      "isError" -> false.asJson,
      "input" -> Json.obj("cmd" -> "ls".asJson)
    )

  private def doneFrame(nsid: String, model: String): Json =
    Json.obj("type" -> "agentDone".asJson, "nodeSessionId" -> nsid.asJson, "model" -> model.asJson)

  // ── S1 帧→行（设计稿 §5 S1：三帧序列 ⇒ Ai+Tool+terminal Ai 行，键 = nodeSessionId）──

  test("S1 帧序列 agentTextDelta→agentToolEnd→agentTextDelta→agentDone ⇒ Ai+Tool+终态Ai 行，键=nodeSessionId") {
    val sid = "node-0a11b22c"
    val store = mkStore
    val rec = new ProcessRecorder(store)
    val io = for
      _ <- rec(textFrame(sid, "hello "))
      _ <- rec(textFrame(sid, "world"))
      _ <- rec(toolEndFrame(sid, "Bash"))
      _ <- rec(textFrame(sid, "final answer"))
      _ <- rec(doneFrame(sid, "claude-x"))
      _ <- store.flushPendingUiWrites
      counts <- readRows(sid)
    yield counts
    io.map { case (counts, rows) =>
      assertEquals(counts.get("ai"), Some(2), s"两条 Ai 行（工具轮前冲刷 + agentDone 终态）：$rows")
      assertEquals(counts.get("tool"), Some(1), s"一条 Tool 行：$rows")
      // 冲刷序：Ai 在上、Tool 在下（frontend finishAi 同形，wrapper :2986 语义）。
      assertEquals(rows(0).hcursor.downField("text").as[String].toOption, Some("hello world"))
      assertEquals(rows(1).hcursor.downField("label").as[String].toOption, Some("Bash"))
      // 终态行：agentDone 落 terminal=true + durationMs + model（replay-terminal 批语义随迁）。
      val last = rows(2).hcursor
      assertEquals(last.downField("text").as[String].toOption, Some("final answer"))
      assertEquals(last.downField("terminal").as[Option[Boolean]].toOption.flatten, Some(true))
      assertEquals(last.downField("model").as[Option[String]].toOption.flatten, Some("claude-x"))
      assert(last.downField("durationMs").as[Option[Long]].toOption.flatten.isDefined, "turnStart ⇒ duration 在场")
    }
  }

  // ── S2 归属键：team- 前缀规整（语义搬家不丢，wrapper :2789 同判）─────────────

  test("S2 team- 前缀 nodeSessionId 规整后落队会话文件，不落幻影 team-<id> 会话") {
    val teamSid = "team-node-9b7c5d3a"
    val real = teamSid.drop(5)
    val store = mkStore
    val rec = new ProcessRecorder(store)
    val io = for
      _ <- rec(textFrame(teamSid, "team text"))
      _ <- rec(doneFrame(teamSid, "m"))
      _ <- store.flushPendingUiWrites
      realRows <- readRows(real)
      phantomRows <- readRows(teamSid)
    yield (realRows, phantomRows)
    io.map { case ((realCounts, _), (phantomCounts, _)) =>
      assert(realCounts.get("ai").exists(_ > 0), "规整后必须落到真实会话文件")
      assertEquals(phantomCounts.get("ai"), None, "禁落幻影 team-<id> 会话文件")
    }
  }

  // ── S3 单写者（行为级）：同帧仅落一行 ────────────────────────────────────────

  test("S3 单写者：同一 agentToolEnd 帧恰好落一行 Tool（无重复行）") {
    val sid = "node-0c33d44e"
    val store = mkStore
    val rec = new ProcessRecorder(store)
    val io = for
      _ <- rec(toolEndFrame(sid, "Read"))
      _ <- store.flushPendingUiWrites
      counts <- readRows(sid)
    yield counts
    io.map { case (counts, _) =>
      assertEquals(counts.get("tool"), Some(1), "单帧单行（单写者；双写复活 = P1 钉红）")
      assertEquals(counts.get("ai"), None)
    }
  }

  test("Canvas root 帧序列经真实 recorder 与分条快照保持消息边界，GitViz 保持合并") {
    val sid = "ask-display-fixture"
    val store = mkStore
    val routes = new WebSocketRoutes(
      wsb = null, sessionService = null, agentService = null,
      configService = nebflow.service.ConfigService,
      configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
      rateLimiter = null, tokenRef = Ref.unsafe[IO, String]("test-token"),
      sessions = GatewaySessionStore.unsafeCreate(), fileChangeTracker = null,
      sessionStore = store, wsHub = null, mcpManager = null, sharedResources = mkResources(store)
    )
    val events = io.circe.parser.parse(Files.readString(Paths.get("tests/ask-display-frames.json")))
      .toOption.get.asArray.get.toList
    val expected = List("前后", "第二条", "第三条长文本：abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "终结")
    val send = routes.makeRecordingWsSend(sid, _ => IO.unit)
    for
      _ <- events.traverse_(send)
      rows <- store.getUiMessages(sid, 0, 200).map(_._1)
    yield
      def texts(rows: List[Json]): List[String] = rows.map(_.hcursor.get[String]("text").getOrElse(""))
      assertEquals(texts(WsAskHandlers.encodeCanvasTranscript(rows)), expected)
      assertEquals(texts(WsAskHandlers.encodeTranscript(rows)), List(expected.mkString))
  }

  // ── S4 写路径契约（源码钉）：落盘只走 SessionStore 既有面 ────────────────────

  test("S4 写路径契约：recorder 落盘走 appendUiMessages/markRoundTerminal，零绕行介质") {
    val src = Files.readString(Paths.get("src/main/scala/nebflow/gateway/ProcessRecorder.scala"))
    assert(src.contains("sessionStore.appendUiMessages"), "正文落盘必须复用 SessionStore.appendUiMessages（去抖/原子/封顶同帽）")
    assert(src.contains("sessionStore.markRoundTerminal"), "空缓冲终态回填必须走段感知 markRoundTerminal")
    // 禁用面钉代码级 token（类注释里对既有机制的「提及」不算绕行）。
    assert(!src.contains("AtomicJson.write"), "禁绕过 SessionStore 直写 AtomicJson（第二写者介质 = 漂移源）")
    assert(!src.contains("os.write"), "禁直写磁盘（崩溃安全由 SessionStore 既有面承担，§2.4）")
    assert(!src.contains("os.read"), "禁直读磁盘（读取面 = SessionStore 缓存既有）")
    assert(!src.contains("appendSyncDurable"), "禁 per-frame fsync（设计稿 §2.4：数量级倒挂）")
    assert(!src.contains("FileChannel"), "禁 per-frame fsync（同上）")
  }

  // ── S5 root 零漂移：root 帧族仍由既有包装落盘，recorder 不接 root 帧 ─────────

  test("S5 root 帧经既有包装的落盘形态不变；recorder 对 root 帧零动作") {
    val sid = "rootspec-0e55f66a"
    val store = mkStore
    val rec = new ProcessRecorder(store)
    val routes = new WebSocketRoutes(
      wsb = null,
      sessionService = null,
      agentService = null,
      configService = nebflow.service.ConfigService,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      rateLimiter = null,
      tokenRef = cats.effect.Ref.unsafe[IO, String]("test-token-process-recorder"),
      sessions = GatewaySessionStore.unsafeCreate(),
      fileChangeTracker = null,
      sessionStore = store,
      wsHub = null,
      mcpManager = null,
      sharedResources = mkResources(store)
    )
    val send = routes.makeRecordingWsSend(sid, _ => IO.unit)
    val io = for
      // root 帧序列（改前 wrapper 同序列的落盘形态：Ai(冲刷) + Tool + 终态 Ai）。
      _ <- send(Json.obj("type" -> "textDelta".asJson, "delta" -> "root text ".asJson))
      _ <- send(Json.obj("type" -> "toolStart".asJson))
      _ <- send(
        Json.obj(
          "type" -> "toolEnd".asJson,
          "label" -> "Bash".asJson,
          "summary" -> "".asJson,
          "content" -> "ok".asJson,
          "isError" -> false.asJson,
          "input" -> Json.Null
        )
      )
      _ <- send(Json.obj("type" -> "textDelta".asJson, "delta" -> "root final".asJson))
      _ <- send(Json.obj("type" -> "done".asJson, "model" -> "claude-x".asJson))
      // recorder 对 root 帧零动作（帧无 nodeSessionId ⇒ 忽略）。
      _ <- rec(Json.obj("type" -> "textDelta".asJson, "delta" -> "should be ignored".asJson))
      _ <- rec(Json.obj("type" -> "agentTextDelta".asJson, "delta" -> "no key ignored".asJson))
      _ <- store.flushPendingUiWrites
      counts <- readRows(sid)
    yield counts
    io.map { case (counts, rows) =>
      assertEquals(counts.get("ai"), Some(2), s"root 路径形态与改前一致（toolStart 冲刷 + done 终态）：$rows")
      assertEquals(counts.get("tool"), Some(1))
      val last = rows(2).hcursor
      assertEquals(last.downField("text").as[String].toOption, Some("root final"))
      assertEquals(
        last.downField("terminal").as[Option[Boolean]].toOption.flatten,
        Some(true),
        "root done 仍落 terminal=true（零漂移）"
      )
    }
  }

  // ── S6 孤儿缓冲：残留冲刷落盘后清除（F1 内容③）─────────────────────────────

  test("S6 孤儿缓冲：idle 超限冲刷残余文本成 Ai 行（无 terminal）后清除；idle 未超限不动") {
    val sid = "node-0f77a88b"
    val store = mkStore
    val rec = new ProcessRecorder(store)
    val io = for
      _ <- rec(textFrame(sid, "residual text"))
      // 未超限：零收殓（显式 nowMs=纪元0 ⇒ idle 巨大也判未超限——时钟无关的确定性面）。
      none <- rec.sweepOrphans(maxIdleMs = 24 * 3600 * 1000L, nowMs = 0L)
      _ <- store.flushPendingUiWrites
      before <- readRows(sid)
      // 超限（maxIdle=0 + nowMs 前移 1 分钟 ⇒ 必然 stale）：收殓 1 会话，残余文本冲刷成 Ai 行。
      swept <- rec.sweepOrphans(maxIdleMs = 0L, nowMs = System.currentTimeMillis() + 60_000L)
      _ <- store.flushPendingUiWrites
      after <- readRows(sid)
      // 二次扫：缓冲已清，零收殓（幂等）。
      again <- rec.sweepOrphans(maxIdleMs = 0L)
    yield (none, before, swept, after, again)
    io.map { case (none, before, swept, after, again) =>
      assertEquals(none, 0, "idle 未超限 ⇒ 不动")
      assertEquals(before._1.get("ai"), None, "冲刷前磁盘零行（缓冲在内存）")
      assertEquals(swept, 1, "超限 ⇒ 收殓 1 会话")
      assertEquals(after._1.get("ai"), Some(1), "残余文本冲刷成一行 Ai")
      assertEquals(after._2.head.hcursor.downField("text").as[String].toOption, Some("residual text"))
      assertEquals(
        after._2.head.hcursor.downField("terminal").as[Option[Boolean]].toOption.flatten,
        None,
        "轮未完成 ⇒ 不写 terminal"
      )
      assertEquals(again, 0, "清除后幂等")
    }
  }

  // ── S7 真 WsHub 集成：生产装配形态（listener 位）+ 快路径 + 耗时读数 ─────────

  test("S7 真 WsHub：registerListener(recorder) 后 broadcast 帧落行；控制帧/root 帧不录；耗时读数在册") {
    val sid = "node-10a9bccd"
    val store = mkStore
    val rec = new ProcessRecorder(store)
    val hub = new WsHub()
    val io = for
      _ <- hub.registerListener(json => rec.apply(json))
      _ <- hub.broadcast(textFrame(sid, "hello "))
      _ <- hub.broadcast(toolEndFrame(sid, "Bash"))
      _ <- hub.broadcast(textFrame(sid, "done text"))
      _ <- hub.broadcast(doneFrame(sid, "claude-x"))
      // 快路径帧：root 帧族 + 面板控制帧 + Flow Map 事件——不得被录。
      _ <- hub.broadcast(Json.obj("type" -> "textDelta".asJson, "delta" -> "root".asJson))
      _ <- hub.broadcast(Json.obj("type" -> "nodeUpdated".asJson, "nodeId" -> "n-1".asJson))
      _ <- hub.broadcast(Json.obj("type" -> "agentStart".asJson, "nodeSessionId" -> sid.asJson))
      _ <- store.flushPendingUiWrites
      counts <- readRows(sid).map(_._1)
      stats <- rec.stats
      listeners <- hub.listenerCount
    yield (counts, stats, listeners)
    io.map { case (counts, stats, listeners) =>
      assertEquals(counts.getOrElse("ai", 0), 2, "两条 Ai 行（S7 集成同 S1 形态）")
      assertEquals(counts.getOrElse("tool", 0), 1, "一条 Tool 行")
      assertEquals(listeners, 1, "recorder 常驻监听者恰一位（进程生命周期，永不注销）")
      assertEquals(stats.framesHandled, 4L, "在册四帧各计一次；控制帧/root 帧零触碰（快路径）")
      assert(stats.maxNanos >= 0L)
    }
  }

  // ── P1 防复活钉（源码级）：wrapper 禁再现 agent* 分支（双写复活 = 回潮）────────

  test("P1 源码钉：makeRecordingWsSend 内 agent* 四分支必须已迁除（禁双写回潮），root 分支必须在位") {
    val src = Files.readString(Paths.get("src/main/scala/nebflow/gateway/WebSocketRoutes.scala"))
    for t <- List("agentTextDelta", "agentThinking", "agentToolEnd", "agentDone") do
      assert(
        !src.contains(s"""case "$t" =>"""),
        s"""wrapper 内再现 `case "$t" =>` = agent* 分支回潮 ⇒ 与 ProcessRecorder 双写（面板双气泡）。录制唯一写者 = ProcessRecorder。"""
      )
    for t <- List("textDelta", "toolStart", "roundComplete", "done", "toolEnd") do
      assert(src.contains(s"""case "$t" =>"""), s"root 分支 `case \"$t\" =>` 必须仍在 wrapper（root 录制 F1 不动）")
    val recSrc = Files.readString(Paths.get("src/main/scala/nebflow/gateway/ProcessRecorder.scala"))
    for t <- List("agentTextDelta", "agentThinking", "agentToolEnd", "agentDone") do
      assert(recSrc.contains(s"""case "$t" =>"""), s"录制语义唯一宿主 ProcessRecorder 必须持有 `case \"$t\" =>`")
  }

  // ── P2 防复活钉：GatewayMain 必须装配 recorder 且注册先于 startupMount ────────

  test("P2 源码钉：GatewayMain 装配 ProcessRecorder + registerListener，链序先于 startupMount") {
    val gw = Files.readString(Paths.get("src/main/scala/nebflow/gateway/GatewayMain.scala"))
    assert(gw.contains("new ProcessRecorder("), "GatewayMain 必须构造进程级 recorder（录制=进程基础设施）")
    assert(
      gw.contains("wsHub.registerListener(json => processRecorder.apply(json))"),
      "recorder 必须挂在 wsHub.registerListener 监听者位（全部腿的帧唯一汇聚点）"
    )
    // 链序判读在空白展平副本上做（scalafmt 折行不破钉）。
    val flat = gw.replaceAll("\\s+", " ")
    val chainIdx = flat.indexOf("hubSetup *> recorderSetup *>")
    val mountIdx = flat.indexOf("*> startupMount *>")
    assert(chainIdx >= 0, "boot 链必须含 recorderSetup（recorderSetup 未入链 = 帧零录制）")
    assert(mountIdx >= 0, "boot 链结构漂移：找不到 startupMount（本钉需随源码重锚）")
    assert(chainIdx < mountIdx, "recorderSetup 必须先于 startupMount（P2：注册点禁晚于挂载——boot 重挂/启动挂载帧自第一帧起有录制）")
  }

  // ── P4 防复活钉：腿④⑤「广播≠录制」注释必须已改写为收口注记 ─────────────────

  test("P4 源码钉：腿④⑤注释禁残留「广播不是录制/仍不落盘」自相矛盾陈述，必须为 recorder 收口注记") {
    val gw = Files.readString(Paths.get("src/main/scala/nebflow/gateway/GatewayMain.scala"))
    assert(!gw.contains("诚实边界：广播不是录制"), "GatewayMain 腿⑤注释仍说『广播不是录制……开放项』= 与 F1 现实矛盾（P4）")
    assert(gw.contains("录制面（process-recorder 批 2026-11-01 F1 收口"), "腿⑤注释必须含 recorder 收口注记")
    val rec = Files.readString(Paths.get("src/main/scala/nebflow/core/project/ProjectCrashRecovery.scala"))
    assert(!rec.contains("本参数不覆盖的面"), "ProjectCrashRecovery wsSend 参数注释仍说『本参数不覆盖录制』= 与 F1 现实矛盾（P4）")
    assert(rec.contains("录制面（process-recorder 批 2026-11-01 F1 收口"), "ProjectCrashRecovery 注释必须含 recorder 收口注记")
  }

end ProcessRecorderSpec
