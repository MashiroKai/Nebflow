package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.node.NodeRunner
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{
  LlmHandle,
  LlmRequest,
  LlmResponse,
  PathUtil,
  StreamChunk,
  ThinkingConfig
}
import nebflow.shared.given

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

/**
 * wsSend 桥接批（2026-11-01 续聊面板空白批 · 同类可疑点②）——boot 重挂腿的机制钉 +
 * 防复活钉。
 *
 * 归因链（本 spec 钉的是链上的机械环节，与 `DelegateContinuationWsSendSpec` 同族）：
 *   1. `ProjectCrashRecovery.recoverDelegateScopeOn` 改前把
 *      `DelegateFlowHosts.mount(wsSend = None)` 写死 ⇒
 *   2. `DelegateFlowHosts.mount` 把 `NodeEngine.wsSendFn` 退化成 `(_) => IO.unit`
 *      （DelegateFlowHosts.scala:165 的 `wsSend.getOrElse`），且 `emitScopeEvent` 走
 *      `wsSend.fold(IO.unit)`（同文件 :400）⇒
 *   3. 重挂作用域里 node-* 会话的**全部流帧在发射处被吞**：无 live 广播，也到不了录制层
 *      （`WebSocketRoutes#makeRecordingWsSend` 按帧内 `nodeSessionId` 落盘）⇒ 恢复窗口
 *      的节点会话面板只剩注入行；作用域 flow-map 事件（nodeUpdated 等）同样静默。
 *   4. 同族对照：spawn 腿的 `DelegateFlowHosts.mount(wsSend = Some(childWsSend))` 有真实
 *      sink；boot 重挂腿是唯一传 None 的宿主挂载点。
 *
 * S1 = 吞帧机制（None ⇒ 帧零落点）；S2 = 落盘键盖章 + 两处消费面（wsSendFn /
 * emitScopeEvent）到点；S3 = 既有覆盖契约 + 挂载幂等不破；S4 = 防复活钉（源码钉：
 * 重挂腿禁用 None，GatewayMain 必须给真实广播）；S5 = 证据存在性钉（暂存普查件）。
 * S6 = 同类可疑点①（DeviceDelegate）「不修」判定留痕钉。
 */
class DelegateScopeRecoveryWsSendSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-scope-recovery-wssend"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()

  // ── 基建（DelegateFlowHostsSpec 同款骨架，裁剪到本 spec 需要的面）────────────

  private class NullLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
    ): Stream[IO, StreamChunk] = Stream.empty

  private def mkResources(system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = new NullLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private def mount(
    ws: os.Path,
    delegateId: String,
    system: ActorSystem,
    res: SharedResources,
    wsSend: Option[Json => IO[Unit]]
  ): IO[DelegateFlowRuntime] =
    DelegateFlowHosts
      .mount(
        delegateId = delegateId,
        project = Some("spec-project"),
        baseWorkspace = ws.toString,
        engineCwd = ws.toString,
        system = system,
        resources = res,
        wsSend = wsSend,
        reportGateHold = Some(false),
        rootNotifyQuietMs = Some(0),
        destroyWindowMs = Some(0L)
      )
      .map(_.getOrElse(fail("mount must succeed for a valid scope id")))

  /** 录制层同形的底座：把收到的每帧收进 Ref（= makeRecordingWsSend 的 base 位置）。 */
  private def sink =
    for ref <- IO.ref(List.empty[Json])
    yield (ref, (j: Json) => ref.update(_ :+ j))

  private val NodeSid = "node-9c0ffee1"

  /** 节点会话的流帧（NodeRunner.routeSubagentWsSend 之前 / 之后的两种形态各一处用）。 */
  private def agentFrame(delta: String): Json =
    Json.obj(
      "type" -> "agentTextDelta".asJson,
      "agentId" -> "nebflow/general".asJson,
      "delta" -> delta.asJson
    )

  // ── S1 吞帧机制（改前形态 = mount(wsSend = None)）────────────────────────────

  test("S1 mount(wsSend = None)（boot 重挂腿改前形态）⇒ engine.wsSendFn 是空转，节点帧零落点") {
    val ws = os.temp.dir(prefix = "nb-dsw-s1-", deleteOnExit = false)
    val system = ActorSystem(s"dsw-s1-${scala.util.Random.nextInt(1000000)}")
    for
      res <- mkResources(system)
      h <- mount(ws, "delegate-general-aaaa0001", system, res, wsSend = None)
      (ref, _) <- sink // 未接上：用于证伪「别处还有一条落盘/广播腿」——底座为 no-op 时零收帧
      routed = NodeRunner.routeSubagentWsSend(h.engine.wsSendFn, h.engine.rootSessionId, NodeSid)
      _ <- routed(agentFrame("hello"))
      got <- ref.get
      _ <- DelegateFlowHosts.unmount(h.delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield assertEquals(got, Nil, "None ⇒ 帧在发射处即被吞（旧形态：无 live、无录制）")
  }

  // ── S2 落盘键盖章 + 两处消费面到点（修后形态）────────────────────────────────

  test("S2 mount(wsSend = Some(sink)) ⇒ 节点帧到点且盖 nodeSessionId/sessionId/rootSessionId（落盘键）") {
    val ws = os.temp.dir(prefix = "nb-dsw-s2-", deleteOnExit = false)
    val system = ActorSystem(s"dsw-s2-${scala.util.Random.nextInt(1000000)}")
    for
      res <- mkResources(system)
      pair <- sink
      (ref, base) = pair
      h <- mount(ws, "delegate-general-bbbb0002", system, res, wsSend = Some(base))
      routed = NodeRunner.routeSubagentWsSend(h.engine.wsSendFn, h.engine.rootSessionId, NodeSid, Some("spec-project"))
      _ <- routed(agentFrame("hello"))
      got <- ref.get
      _ <- DelegateFlowHosts.unmount(h.delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(got.size, 1)
      val hc = got.head.hcursor
      assertEquals(hc.downField("delta").as[String].toOption, Some("hello"))
      // 录制层按 nodeSessionId 判归属落盘（makeRecordingWsSend 的 agent* 分支）。
      assertEquals(hc.downField("nodeSessionId").as[String].toOption, Some(NodeSid))
      assertEquals(hc.downField("sessionId").as[String].toOption, Some(h.engine.rootSessionId))
      assertEquals(hc.downField("rootSessionId").as[String].toOption, Some(h.engine.rootSessionId))
      assertEquals(hc.downField("type").as[String].toOption, Some("agentTextDelta"))
    end for
  }

  test("S2b mount(wsSend = Some(sink)) ⇒ 作用域 flow-map 事件（emitScopeEvent）同样到点") {
    val ws = os.temp.dir(prefix = "nb-dsw-s2b-", deleteOnExit = false)
    val system = ActorSystem(s"dsw-s2b-${scala.util.Random.nextInt(1000000)}")
    for
      res <- mkResources(system)
      pair <- sink
      (ref, base) = pair
      h <- mount(ws, "delegate-general-cccc0003", system, res, wsSend = Some(base))
      // emitEvent 是引擎的 flow-map 事件出口（DelegateFlowHosts.mount 的 emitScopeEvent）
      _ <- h.engine.emitEvent("nodeUpdated", "n-1234", Json.obj("id" -> "n-1234".asJson))
      got <- ref.get
      _ <- DelegateFlowHosts.unmount(h.delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(got.size, 1, "改前 = emitScopeEvent 的 wsSend.fold(IO.unit) ⇒ 零帧")
      val hc = got.head.hcursor
      assertEquals(hc.downField("type").as[String].toOption, Some("nodeUpdated"))
      assertEquals(hc.downField("nodeId").as[String].toOption, Some("n-1234"))
      assertEquals(hc.downField("project").as[String].toOption, Some("spec-project"))
    end for
  }

  // ── S3 既有契约不破（覆盖 + 幂等）──────────────────────────────────────────

  test("S3 契约不破：帧自带 nodeSessionId/sessionId ⇒ 路由不覆盖；重挂幂等返回同实例") {
    val ws = os.temp.dir(prefix = "nb-dsw-s3-", deleteOnExit = false)
    val system = ActorSystem(s"dsw-s3-${scala.util.Random.nextInt(1000000)}")
    val ownNode = "node-12345678"
    val ownSession = "node-self-owner"
    for
      res <- mkResources(system)
      pair <- sink
      (ref, base) = pair
      h <- mount(ws, "delegate-general-dddd0004", system, res, wsSend = Some(base))
      routed = NodeRunner.routeSubagentWsSend(h.engine.wsSendFn, h.engine.rootSessionId, NodeSid)
      _ <- routed(
        Json.obj(
          "type" -> "agentToolEnd".asJson,
          "nodeSessionId" -> ownNode.asJson,
          "sessionId" -> ownSession.asJson,
          "label" -> "Bash".asJson
        )
      )
      again <- mount(ws, "delegate-general-dddd0004", system, res, wsSend = Some(base))
      got <- ref.get
      _ <- DelegateFlowHosts.unmount(h.delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val hc = got.head.hcursor
      assertEquals(hc.downField("nodeSessionId").as[String].toOption, Some(ownNode), "自带 nodeSessionId 不被覆盖")
      assertEquals(hc.downField("sessionId").as[String].toOption, Some(ownSession), "自带 sessionId 不被覆盖")
      assert(again eq h, "同作用域二次挂载返回同一实例（幂等契约不破）")
    end for
  }

  // ── S4 防复活钉（源码钉）────────────────────────────────────────────────────

  test("S4 防复活钉：boot 重挂腿禁止回退 wsSend=None，GatewayMain 必须给真实广播") {
    val recovery = Files.readString(
      Paths.get("src/main/scala/nebflow/core/project/ProjectCrashRecovery.scala")
    )
    val onAt = recovery.indexOf("private def recoverDelegateScopeOn")
    assert(onAt >= 0, "recoverDelegateScopeOn 不在 ProjectCrashRecovery.scala —— 本钉需随源码结构重锚")
    val onBody = recovery.substring(onAt, math.min(recovery.length, onAt + 4000))
    val mountAt = onBody.indexOf("DelegateFlowHosts")
    assert(mountAt >= 0, "recoverDelegateScopeOn 内找不到 DelegateFlowHosts.mount 调用")
    val call = onBody.substring(mountAt, math.min(onBody.length, mountAt + 2000))
    assert(
      call.contains("wsSend = wsSend"),
      "boot 重挂腿必须把调用方 wsSend 透传给 mount（否则恢复窗口的 node-* 会话帧被吞 ⇒ 面板只剩注入行）"
    )
    assert(
      !call.contains("wsSend = None"),
      "重挂腿回退成 wsSend = None 会让恢复窗口重新吞帧（2026-11-01 同类可疑点②的根因）"
    )
    // 入口签名仍必须携带该参数（缺省 None = 旧形态；生产调用点显式给真实广播）。
    val entryAt = recovery.indexOf("def recoverDelegateScopes(")
    assert(entryAt >= 0, "recoverDelegateScopes 入口不在（结构漂移，本钉需重锚）")
    val entry = recovery.substring(entryAt, math.min(recovery.length, entryAt + 3000))
    assert(entry.contains("wsSend: Option[Json => IO[Unit]] = None"), "sweep 入口必须暴露 wsSend 参数")

    val gateway = Files.readString(Paths.get("src/main/scala/nebflow/gateway/GatewayMain.scala"))
    val sweepAt = gateway.indexOf(".recoverDelegateScopes(")
    assert(sweepAt >= 0, "GatewayMain 里找不到 recoverDelegateScopes 调用")
    val sweepCall = gateway.substring(sweepAt, math.min(gateway.length, sweepAt + 600))
    assert(
      sweepCall.contains("wsSend = Some("),
      "boot 链必须给重挂腿真实 wsSend（startupMount 的 #28 接线同源）"
    )
    assert(
      sweepCall.contains("wsHub.broadcast"),
      "真实 wsSend 必须是进程级广播（wsHub.broadcast）——boot 期没有控制台连接可桥"
    )
  }

  // ── S5 取证件存在性（普查可复跑）────────────────────────────────────────────

  test("S5 取证件在册：node-* 会话 ui.json 普查脚本可复跑（本批判据来源）") {
    val script = Paths.get("evidence/20261101_crash-recovery-ws-send/census.py")
    assert(Files.exists(script), s"普查脚本必须在册：$script")
    val body = Files.readString(script)
    assert(body.contains("node-"), "普查脚本必须覆盖 node-* 会话族")
    assert(body.contains("ai"), "普查脚本必须统计 ai 行")
  }

  // ── S6 同类可疑点①：DeviceDelegate「不修」判定留痕钉 ────────────────────────

  test("S6 判定留痕：DeviceDelegate 设备腿 wsSend=None 必须带本批判定注释（禁含糊放过）") {
    val src = Files.readString(Paths.get("src/main/scala/nebflow/core/delegate/DeviceDelegate.scala"))
    val spawnAt = src.indexOf("private[delegate] def spawnLocalDelegate")
    assert(spawnAt >= 0, "spawnLocalDelegate 不在 DeviceDelegate.scala —— 本钉需随源码结构重锚")
    val body = src.substring(spawnAt, math.min(src.length, spawnAt + 6000))
    val noneAt = body.indexOf("wsSend = None")
    assert(noneAt >= 0, "设备腿当前形态 = wsSend = None（本批判定为不修，须留注释说明）")
    val head = body.substring(math.max(0, noneAt - 2000), noneAt)
    assert(head.contains("同类可疑点"), "None 处缺失判定标注（『同类可疑点①』）")
    assert(head.contains("不修"), "None 处缺失判定结论（『不修』）")
    assert(head.contains("recorder"), "None 处缺失根治方向（进程级 recorder）")
  }
