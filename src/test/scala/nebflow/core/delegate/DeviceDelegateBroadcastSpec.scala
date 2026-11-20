package nebflow.core.delegate

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.*
import nebflow.core.*
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.PluginMcpManager
import nebflow.core.scheduler.{ScheduledTaskService, ScheduledTaskStore}
import nebflow.core.tools.{DelegateTool, ToolContext}
import nebflow.gateway.{ProcessRecorder, WsHub}
import nebflow.shared.*

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

/**
 * 设备腿接广播（process-recorder 批 2026-11-01 F2，设计稿 §4 F2 / 钉 P3）——
 * 腿⑥（DeviceDelegate.spawnLocalDelegate，对端派活 spawn）帧「在发射处消失」的
 * 机制钉 + 防复活钉。spec 同族 = `DelegateContinuationWsSendSpec` /
 * `DelegateScopeRecoveryWsSendSpec`（本 spec 的 S3 = 后者 S6 的 F2 落地改写）。
 *
 * 归因链（前批判定「不修」的三判据前两条已随本批消失）：
 *   1. GatewayMain 在 `DeviceDelegate.initialize(resources, broadcast)` 注入
 *      **进程级广播函数值**（`wsHub.broadcast`；窄端口，禁传 WsHub 本体——
 *      check-scala-layers.mjs core 禁 import gateway 门禁）；
 *   2. `spawnLocalDelegate` 把它交给 `DelegateTool.spawnBackground(wsSend = …)` ⇒
 *      `routeWsSend` 盖 nodeSessionId 后进 wsHub ⇒ **ProcessRecorder**（F1，挂
 *      registerListener）按 nodeSessionId 落 `.ui.json` ＋ live 广播同源；
 *   3. 缺省 `None`（initialize 未接线）= 逐字旧形态（帧 no-op）。
 *
 * S1 = initialize 装配缝（广播面在册 / 缺省 None 旧形态）；S2 = 源码钉（spawnLocal
 * Delegate 禁 `wsSend = None`，必须传广播面）；S3 = P3 一致性钉（判据注释与代码
 * 状态同步：F2 已接入，禁「已修却仍写未修判据」的反向漂移——原 S6 改写）；
 * S4 = 腿⑥链路机械集成（routeWsSend 盖章 → 真 WsHub → recorder → .ui.json 行）。
 */
class DeviceDelegateBroadcastSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-device-delegate-broadcast"
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "sessions")

  private def mkStore: SessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks")

  /** 磁盘行读数（方法级 helper——禁在 for 推导内写 io.circe 全名，会被 val io 遮蔽）。 */
  private def readRows(sid: String): IO[List[Json]] =
    IO.blocking {
      val f = tempRoot / "sessions" / s"$sid.ui.json"
      if !os.exists(f) then List.empty[Json]
      else
        io.circe.parser.parse(os.read(f)) match
          case Right(json) => json.asArray.get.toList
          case Left(_)     => List.empty[Json]
    }

  /** 未接线桩（DeviceDelegateSpawnTrustSpec 的 neverResources 同款：触到即炸——本 spec 只允许碰 neblinkService）。 */
  private def stubResources(neblink: Option[NeblinkServicePort] = None): AgentRuntimePort =
    new AgentRuntimePort:
      def sessionStore: SessionStore = throw new IllegalStateException("not expected")
      def actorSystem: ActorSystem = throw new IllegalStateException("not expected")
      def contextWindow: Int = throw new IllegalStateException("not expected")
      def effectiveSafetyMode: IO[SafetyMode] = throw new IllegalStateException("not expected")
      def subAgentTaskStore: SubAgentTaskStorePort = throw new IllegalStateException("not expected")
      def pluginMcp: PluginMcpManager = throw new IllegalStateException("not expected")
      def scheduledTaskStore: ScheduledTaskStore = throw new IllegalStateException("not expected")
      def scheduledTaskService: Option[ScheduledTaskService] = throw new IllegalStateException("not expected")
      def neblinkService: Option[NeblinkServicePort] = neblink
      def dropboxService: Option[DropboxServicePort[?]] = throw new IllegalStateException("not expected")
      def friendService: Option[FriendServicePort] = throw new IllegalStateException("not expected")
      def healthMonitor: ProviderHealthPort = throw new IllegalStateException("not expected")
      def interactionHubRef: Ref[IO, Option[ActorRef[InteractionHubCommand]]] =
        throw new IllegalStateException("not expected")
      def spawnAgentActor(system: ActorSystem, p: NodeRunner.SpawnParams): IO[ActorRef[AgentCommand]] =
        throw new IllegalStateException("not expected")
      def spawnSupervisedAdapter(
        system: ActorSystem,
        params: NodeRunner.SpawnParams,
        childRef: ActorRef[AgentCommand],
        childName: String,
        description: String,
        agentName: String,
        subagentId: String,
        parentSessionId: String,
        initialPrompt: String,
        source: String,
        extraMetadata: JsonObject = JsonObject.empty,
        wsSend: Option[Json => IO[Unit]] = None
      ): IO[ActorRef[AgentEvent]] = throw new IllegalStateException("not expected")
      def agentActorBehavior(
        agentDef: AgentDef,
        wsSend: io.circe.Json => IO[Unit],
        depth: Int,
        parentRef: Option[ActorRef[AgentCommand]] = None,
        sessionId: Option[String] = None,
        sessionName: Option[String] = None,
        initialMessages: List[Message] = Nil,
        readTracker: Option[ReadTracker] = None,
        fileHistory: Option[FileHistory] = None,
        contextWindow: Int = Defaults.ContextWindow,
        projectRoot: Option[String] = None,
        rulesMd: Option[String] = None,
        agentsMd: Option[String] = None,
        folderId: Option[String] = None,
        safetyMode: String = "confirm-edits",
        gitBranch: Option[String] = None,
        expectsMail: Boolean = false,
        rootSessionId: String = "",
        freezeExempt: Boolean = false,
        isFlowNode: Boolean = false,
        userFacingNode: Boolean = false,
        flowNodeId: Option[String] = None,
        isDispatcher: Boolean = false,
        flowNodeRole: Option[String] = None,
        projectName: Option[String] = None,
        flowNodeName: Option[String] = None,
        flowChainId: Option[String] = None,
        sandboxEnabled: Boolean = false,
        sandboxRoot: Option[String] = None,
        sessionCwd: Option[String] = None,
        projectSession: Boolean = false,
        compactThresholdRatio: Option[Double] = None,
        sessionDevice: Option[String] = None,
        sessionDeviceWorkspace: Option[String] = None
      ): Behavior[AgentCommand] = throw new IllegalStateException("not expected")
      def agentRegistry: Ref[IO, Map[String, AgentRecord]] = throw new IllegalStateException("not expected")
      def processingSessionIds: IO[List[String]] = throw new IllegalStateException("not expected")
    end new

  override def afterEach(context: munit.AfterEach): Unit =
    // 复位对象级广播面（跨测试隔离；None = 逐字旧形态）。
    DeviceDelegate.initialize(stubResources(), None)

  // ── S1 装配缝：initialize 注入的广播面在册；缺省 None = 旧形态 ───────────────

  test("S1 initialize(resources, broadcast=Some(f)) ⇒ 广播面在册；缺省调用 = None（逐字旧形态）") {
    val probe: Json => IO[Unit] = _ => IO.unit
    DeviceDelegate.initialize(stubResources(), Some(probe))
    val wired = DeviceDelegate.currentBroadcast
    DeviceDelegate.initialize(stubResources())
    val default = DeviceDelegate.currentBroadcast
    assert(wired.isDefined, "GatewayMain 注入面必须在册（F2：设备腿帧的进程级出口）")
    assert(default.isEmpty, "缺省 initialize 必须保持 None（未接线调用点/spec 桩零改动 = 逐字旧形态）")
  }

  // ── S2 源码钉：spawnLocalDelegate 禁 wsSend=None，必须传广播面 ───────────────

  test("S2 源码钉：spawnLocalDelegate 必须传进程级广播面（禁回退 wsSend=None ⇒ 帧在发射处消失）") {
    val src = Files.readString(Paths.get("src/main/scala/nebflow/core/delegate/DeviceDelegate.scala"))
    val spawnAt = src.indexOf("private[delegate] def spawnLocalDelegate")
    assert(spawnAt >= 0, "spawnLocalDelegate 不在 DeviceDelegate.scala —— 本钉需随源码结构重锚")
    val body = src.substring(spawnAt, math.min(src.length, spawnAt + 6000))
    assert(
      !body.contains("wsSend = None"),
      "设备腿回退 wsSend = None 会让本地会话帧在发射处再次消失（腿⑥根因；录制/live 双失）"
    )
    assert(
      body.contains("wsSend = processBroadcast"),
      "设备腿必须把进程级广播面交给 spawnBackground（帧盖 nodeSessionId 后进 hub：ProcessRecorder 落盘 + live 同源）"
    )
    assert(
      src.contains("broadcast: Option[io.circe.Json => IO[Unit]] = None"),
      "initialize 必须暴露缺省 None 的广播面参数（窄端口，未接线调用点零改动）"
    )
    // 生产装配点：GatewayMain 必须真给广播（函数值 = wsHub.broadcast，禁传 WsHub 本体）。
    val gw = Files.readString(Paths.get("src/main/scala/nebflow/gateway/GatewayMain.scala"))
    val initAt = gw.indexOf("DeviceDelegate.initialize(")
    assert(initAt >= 0, "GatewayMain 里找不到 DeviceDelegate.initialize 调用")
    val call = gw.substring(initAt, math.min(gw.length, initAt + 500))
    assert(call.contains("Some("), "GatewayMain 必须给设备腿注入广播面（None = 腿⑥退回旧形态）")
    assert(call.contains("wsHub.broadcast"), "注入值必须是进程级广播 wsHub.broadcast")
  }

  // ── S3（P3）一致性钉：判据注释与代码状态同步（原 S6 的 F2 落地改写）────────────

  test("S3（P3）判定留痕钉改写：注释必须陈述 F2 已接入（禁『已修却仍写未修判据』的自相矛盾）") {
    val src = Files.readString(Paths.get("src/main/scala/nebflow/core/delegate/DeviceDelegate.scala"))
    val spawnAt = src.indexOf("private[delegate] def spawnLocalDelegate")
    assert(spawnAt >= 0, "spawnLocalDelegate 不在 DeviceDelegate.scala —— 本钉需随源码结构重锚")
    val body = src.substring(spawnAt, math.min(src.length, spawnAt + 6000))
    val noneAt = body.indexOf("wsSend = processBroadcast")
    assert(noneAt >= 0, "F2 接线行缺席（S2 应先红——本钉锚在接线行前的判据注释）")
    val head = body.substring(math.max(0, noneAt - 2400), noneAt)
    assert(head.contains("F2 已接入"), "判据注释必须标注 F2 落地现实（禁留「不修」旧判据误导后人）")
    assert(head.contains("ProcessRecorder"), "注释必须指认录制写者（进程级 recorder）")
    assert(head.contains("wsHub.broadcast") || head.contains("processBroadcast"), "注释必须指认广播面来源")
    assert(!body.contains("＝**不修**"), "旧『不修』判定头必须已改写（代码已接入，注释不得反向漂移）")
  }

  // ── S4 腿⑥链路机械集成：routeWsSend 盖章 → 真 WsHub → recorder → .ui.json ────

  test("S4 腿⑥链路集成：广播面经 routeWsSendFor 盖 nodeSessionId 进真 WsHub ⇒ 本机 .ui.json 有行 + live 有帧") {
    val sid = "node-d6eadf01"
    val parent = "root-device-leg-parent"
    val store = mkStore
    val rec = new ProcessRecorder(store)
    val hub = new WsHub()
    // 生产同形闭包：GatewayMain 注入的广播函数值（Some(wsHub.broadcast)）。
    val broadcast: Json => IO[Unit] = json => hub.broadcast(json)
    val io = for
      _ <- hub.registerListener(json => rec.apply(json))
      // live 证伪面：第二个监听者收帧 = live 面在册（设备腿帧同时可见）。
      live <- IO.ref(List.empty[Json])
      _ <- hub.registerListener(j => live.update(_ :+ j))
      // 本行即腿⑥帧出 DeviceDelegate 后的链路：spawnBackground 的 routeWsSend 盖章。
      childWsSend = DelegateTool.routeWsSendFor(sid, Some(parent), Some(broadcast))
      _ <- childWsSend(Json.obj("type" -> "agentTextDelta".asJson, "agentId" -> "nebflow/delegate".asJson, "delta" -> "device leg text".asJson))
      _ <- childWsSend(Json.obj("type" -> "agentDone".asJson, "model" -> "claude-x".asJson))
      _ <- store.flushPendingUiWrites
      frames <- live.get
      rows <- readRows(sid)
    yield (frames, rows)
    io.map { case (frames, rows) =>
      assertEquals(frames.size, 2, "live 面：两帧都到（广播面接通）")
      assert(
        frames.forall(_.hcursor.downField("nodeSessionId").as[String].toOption.contains(sid)),
        "路由盖章：帧必须携带 nodeSessionId（录制归属键）"
      )
      val aiRows = rows.filter(_.hcursor.downField("type").as[String].toOption.contains("ai"))
      assertEquals(aiRows.size, 1, "录制面：正文落 .ui.json（改前 = 帧在发射处消失，零行）")
      assertEquals(
        aiRows.head.hcursor.downField("text").as[String].toOption,
        Some("device leg text"),
        "终态 Ai 行文本 = 残余缓冲冲刷（agentDone）"
      )
    }
  }

end DeviceDelegateBroadcastSpec
