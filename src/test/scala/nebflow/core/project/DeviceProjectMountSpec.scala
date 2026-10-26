package nebflow.core.project

import cats.effect.IO
import cats.effect.Ref
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite
import nebflow.actor.*
import nebflow.core.*
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.PluginMcpManager
import nebflow.core.scheduler.{ScheduledTaskService, ScheduledTaskStore}
import nebflow.core.tools.RemoteExecutor
import nebflow.shared.*

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * 跨设备项目挂载 spec（跨设备项目挂载批 2026-10-25）——两条铁律的行为钉：
 *
 *   1. **workspace 必须在声明设备上真实存在才准挂**——本机腿（现场事故闸门
 *      [[ProjectMountPreflight.checkLocalWorkspace]]）与幂等重挂支路都拒绝
 *      不存在的目录，**零写入**（project.json 定义与骨架都不落盘；事故原形
 *      2026-10-24：`C:\Users\Kai\stand` 在 Mac 被静默造出 `~/.stand/C:\.../`
 *      假骨架并「假挂载成功」）。
 *   2. **不存在 → 结构化报错（指明设备与路径）**——本机腿含解析落点 + device
 *      指引；设备腿（[[ProjectMountPreflight.deviceRefusal]]）点名设备与路径。
 *
 * 设备列举断言（spec §钉）：未知/离线设备 = 显式拒绝 + **列在线设备名单**
 * （创建腿 `DEVICE_OFFLINE` 与解析单点 `RemoteExecutor.resolvePeer` 的
 * `Available:` 名单），绝不静默落本机。跨设备读文件（transferGetScoped 对真
 * 设备）属真机验收面（KAI 实机挂 stand 项），由主会话执行——本 spec 只钉
 * 无网络/离线时的结构化失败形态（stub NeblinkServicePort，零真实网络）。
 */
class DeviceProjectMountSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-device-mount"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  // ---- stubs（任何其他成员被触到即炸——被测路径不得触碰运行时） ----------------

  private def boom: Throwable =
    new IllegalStateException("test path must not touch runtime resources")

  private val winPath = """C:\Users\Kai\stand"""

  private val kaiPeers: List[PeerInfo] =
    List(PeerInfo(deviceId = "kai-device-id", deviceName = "KAI", platform = "windows", address = ""))

  /** 名册 stub：peers/scanNow 恒回 KAI 在线；其余成员 = 炸。 */
  private val rosterNeblink: NeblinkServicePort = new NeblinkServicePort:
    def identity: IO[DeviceIdentityView] = throw boom
    def peers: IO[List[PeerInfo]] = IO.pure(kaiPeers)
    def scanNow: IO[List[PeerInfo]] = IO.pure(kaiPeers)
    def addDataHandler(handler: Json => IO[Unit]): IO[Unit] = throw boom
    def sendData(deviceId: String, channel: String, payload: Json): IO[Boolean] = throw boom
    def relayClientOpt: Option[NeblinkClientPort] = None
    private[nebflow] def httpBackend: sttp.client4.SyncBackend = throw boom
    def relayTunnelOpt: Option[RelayTunnelPort] = None
    def presenceServiceOpt: Option[PresenceServicePort] = None
  end rosterNeblink

  private def resourcesWith(neblink: Option[NeblinkServicePort]): AgentRuntimePort =
    new AgentRuntimePort:
      def sessionStore: SessionStore = throw boom
      def actorSystem: ActorSystem = throw boom
      def contextWindow: Int = throw boom
      def effectiveSafetyMode: IO[SafetyMode] = throw boom
      def subAgentTaskStore: SubAgentTaskStorePort = throw boom
      def pluginMcp: PluginMcpManager = throw boom
      def scheduledTaskStore: ScheduledTaskStore = throw boom
      def scheduledTaskService: Option[ScheduledTaskService] = throw boom
      def neblinkService: Option[NeblinkServicePort] = neblink
      def dropboxService: Option[DropboxServicePort[?]] = throw boom
      def friendService: Option[FriendServicePort] = throw boom
      def healthMonitor: ProviderHealthPort = throw boom
      def interactionHubRef: Ref[IO, Option[ActorRef[InteractionHubCommand]]] = throw boom
      def spawnAgentActor(system: ActorSystem, p: NodeRunner.SpawnParams): IO[ActorRef[AgentCommand]] =
        throw boom
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
      ): IO[ActorRef[AgentEvent]] = throw boom
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
        isSubTaskWorker: Boolean = false,
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
      ): Behavior[AgentCommand] = throw boom
      def agentRegistry: Ref[IO, Map[String, AgentRecord]] = throw boom
      def processingSessionIds: IO[List[String]] = throw boom
    end new

  /** 无会话身份（definition-only）：create 只走定义腿 + 预检，不挂载。 */
  private val identity = ProjectCreateService.CreateIdentity.definitionOnly

  private def identityWith(res: AgentRuntimePort): ProjectCreateService.CreateIdentity =
    ProjectCreateService.CreateIdentity(None, Some(res), None, None, None)

  // ---- 铁律①：本机腿挂不存在目录 = 拒绝 + 零写入 -----------------------------

  test("铁律① 本机腿：不存在目录挂载被拒，project.json 与骨架零写入") {
    val ghost = tmp / "ws-root" / "no-such"
    for
      r <- ProjectCreateService.create(Some("ghost-proj"), ghost.toString, None, identity)
    yield
      val msg = clue(r.left.toOption.map(_.message).getOrElse("<unexpected Right>"))
      assert(clue(r.isLeft), s"expected Left, got $r")
      assert(clue(msg).contains("Mount refused"))
      // 零写入（铁律字面）：绝不静默造骨架（事故形态），定义也不落盘。
      assert(clue(!os.exists(ghost)), "scaffold must NOT be created for a missing workspace")
      assert(clue(!os.exists(ProjectStore.projectJsonPath("ghost-proj"))), "definition must NOT be written")
      assert(clue(!os.exists(ProjectStore.projectDir("ghost-proj"))))
  }

  test("铁律②（事故原形）Windows 风格路径走本机腿：拒绝 + device 指引 + 零写入") {
    // 主机无关化（2026-10-26 #901 批）：路径必须**在任何主机上都不存在**——原形字面
    // `C:\Users\Kai\stand` 在 Windows 主机（KAI）上是真实存在的目录（本仓自身），
    // 本机腿预检通过 ⇒ create 成功 ⇒ 本例假绿转红。事故判据的本体是「Windows 风格
    // 路径误走本机腿被拒 + device 指引 + 零写入」，不依赖那个具体路径本身。
    val ghostWin = """C:\no-such-incident-dir-901\nb"""
    for
      r <- ProjectCreateService.create(Some("stand"), ghostWin, None, identity)
    yield
      assert(clue(r.isLeft))
      val msg = clue(r.left.toOption.map(_.message).getOrElse("<unexpected Right>"))
      assert(clue(msg).contains("Mount refused"))
      // 结构化指引：点明该路径应带 device 参数走设备腿（现场事故的可行动出路）。
      assert(clue(msg).contains("device"))
      // 零写入：按生产同款解析（os.Path(workspace, base)）核对绝不出现假骨架目录
      // （2026-10-24 原形：`~/.stand/C:\Users\Kai\stand/` 垃圾骨架）。
      val resolved = scala.util.Try(os.Path(ghostWin, base = tmp)).toOption
      assert(clue(resolved.forall(p => !os.exists(p))), "incident shape: fake scaffold dir under dataRoot")
      assert(clue(!os.exists(ProjectStore.projectDir("stand"))))
  }

  test("控制组：真实存在的本机目录照常创建（闸门不误伤）") {
    val ws = tmp / "real-ws"
    os.makeDir.all(ws)
    for
      r <- ProjectCreateService.create(Some("real-proj"), ws.toString, None, identity)
      pd <- ProjectStore.load("real-proj")
    yield
      assert(clue(r.isRight), s"existing dir must create fine, got ${r.left.toOption.map(_.message)}")
      assert(clue(pd.isDefined))
      assert(clue(os.exists(ws / "AGENTS.md")), "scaffold backfill still runs for a real dir")
  }

  test("铁律① 幂等支路：目录后来被删 → 重挂被拒 + 零写入") {
    val ws = tmp / "vanish-ws"
    os.makeDir.all(ws)
    for
      _ <- ProjectCreateService.create(Some("vanish-proj"), ws.toString, None, identity)
      _ = assert(clue(os.exists(ProjectStore.projectJsonPath("vanish-proj"))), "first create writes the definition")
      _ = os.remove.all(ws)
      r2 <- ProjectCreateService.create(Some("vanish-proj"), ws.toString, None, identity)
    yield
      assert(clue(r2.isLeft), s"re-mount of a vanished dir must be refused, got $r2")
      assert(clue(r2.left.toOption.map(_.message).getOrElse("")).contains("Mount refused"))
      // 零写入：目录不被静默重建（幂等支路同样先过预检）。
      assert(clue(!os.exists(ws)), "idempotent branch must NOT recreate the vanished dir")
  }

  // ---- 预检纯判据 -------------------------------------------------------------

  test("Preflight 纯判据：windowsStylePath 与 checkLocalWorkspace") {
    // Windows 风格判据（事故信号）。
    assert(clue(ProjectMountPreflight.windowsStylePath("""C:\Users\Kai\stand""")))
    assert(clue(ProjectMountPreflight.windowsStylePath("C:/x/y")))
    assert(clue(ProjectMountPreflight.windowsStylePath("""\\srv\share""")))
    assert(clue(!ProjectMountPreflight.windowsStylePath("/usr/local/stand")))
    assert(clue(!ProjectMountPreflight.windowsStylePath("relative/path")))
    // 本机腿判据：存在目录 = None（准挂）；不存在 / 是文件 = Some(拒绝文案)。
    val dir = tmp / "pf-dir"
    os.makeDir.all(dir)
    assertEquals(clue(ProjectMountPreflight.checkLocalWorkspace(dir.toString, tmp)), None)
    val miss = ProjectMountPreflight.checkLocalWorkspace((tmp / "pf-missing").toString, tmp)
    assert(clue(miss).exists(_.contains("Mount refused")))
    val file = tmp / "pf-file"
    os.write.over(file, "x")
    assert(clue(ProjectMountPreflight.checkLocalWorkspace(file.toString, tmp)).isDefined)
  }

  // ---- brainWorkspace 判据（大脑锚点单点） ------------------------------------

  test("brainWorkspace：本机=workspace 本身；设备=projects/<name>/brain；self 字面=本机") {
    val local = ProjectDef(
      name = "local-p",
      description = None,
      workspace = "/tmp/ws-local",
      agentFile = "/tmp/ws-local/AGENTS.md",
      createdAt = 1L
    )
    assertEquals(clue(ProjectStore.deviceOrigin(local)), None)
    assertEquals(clue(ProjectStore.brainWorkspace(local)), "/tmp/ws-local")

    val device = ProjectDef(
      name = "dev-p",
      description = None,
      workspace = winPath,
      agentFile = s"$winPath\\AGENTS.md",
      createdAt = 1L,
      origin = Some(ProjectOrigin("kai-device-id", winPath))
    )
    assert(clue(ProjectStore.deviceOrigin(device)).isDefined)
    assertEquals(
      clue(ProjectStore.brainWorkspace(device)),
      (PathUtil.dataRoot / "projects" / "dev-p" / "brain").toString
    )

    // self 字面 = 本机（deviceOrigin 过滤；编排权威本机形态零变化）。
    val selfLit = local.copy(origin = Some(ProjectOrigin(ProjectOrigin.Self, "/tmp/ws-local")))
    assertEquals(clue(ProjectStore.deviceOrigin(selfLit)), None)
    assertEquals(clue(ProjectStore.brainWorkspace(selfLit)), "/tmp/ws-local")
  }

  // ---- 设备腿：无网络 / 离线 / 通道缺位 = 结构化拒绝 ---------------------------

  test("设备腿：无设备网络 device=create → (DEVICE_ROUTE_UNAVAILABLE) 零写入") {
    for
      r <- ProjectCreateService.create(Some("dev-proj"), winPath, None, identity, device = Some("KAI"))
    yield
      assert(clue(r.isLeft))
      assert(clue(r.left.toOption.map(_.message).getOrElse("")).contains("(DEVICE_ROUTE_UNAVAILABLE)"))
      assert(clue(!os.exists(ProjectStore.projectJsonPath("dev-proj"))), "device leg writes nothing without a route")
  }

  test("设备列举断言：未知设备 = (DEVICE_OFFLINE) 拒绝并列在线设备名单") {
    for
      r <- ProjectCreateService.create(
        Some("roam-proj"),
        winPath,
        None,
        identityWith(resourcesWith(Some(rosterNeblink))),
        device = Some("ghost-device")
      )
    yield
      assert(clue(r.isLeft))
      val msg = clue(r.left.toOption.map(_.message).getOrElse(""))
      assert(clue(msg).contains("(DEVICE_OFFLINE)"))
      assert(clue(msg).contains("ghost-device"), "refusal names the requested device")
      assert(clue(msg).contains("KAI"), "refusal lists the online device roster")
      assert(clue(!os.exists(ProjectStore.projectJsonPath("roam-proj"))), "never silently creates locally")
  }

  test("resolvePeer 零命中：Available 名单（解析单点的设备列举口径）") {
    val left = RemoteExecutor.resolvePeer("ghost-device", kaiPeers)
    assert(clue(left.isLeft))
    val msg = clue(left.left.toOption.map(_.message).getOrElse(""))
    assert(clue(msg).contains("Available: KAI"))
    // 精确命中照常（控制组）。
    assert(clue(RemoteExecutor.resolvePeer("KAI", kaiPeers).isRight))
  }

  test("DeviceProjectMount.prepare：通道缺位 = 结构化拒绝（指明设备与路径），不落大脑根") {
    val pd = ProjectDef(
      name = "prep-p",
      description = None,
      workspace = winPath,
      agentFile = s"$winPath\\AGENTS.md",
      createdAt = 1L,
      origin = Some(ProjectOrigin("kai-device-id", winPath))
    )
    for
      // (a) NebLink 未配置（neblinkService = None）。
      e1 <- DeviceProjectMount.prepare(pd, resourcesWith(None)).attempt
      // (b) RemoteExecutor 未初始化（本 spec 未触静态 instance ⇒ current = None）。
      e2 <- DeviceProjectMount.prepare(pd, resourcesWith(Some(rosterNeblink))).attempt
    yield
      val m1 = clue(e1.swap.toOption.map(_.getMessage).getOrElse("<unexpected Right>"))
      assert(clue(m1).contains("Mount refused"))
      // 通道缺位时显示名不可知——拒绝点名 origin.device（稳定 id，铁律②「指明设备」）。
      assert(clue(m1).contains("kai-device-id"), s"refusal must name the device: $m1")
      assert(clue(m1).contains(winPath), s"refusal must name the device path: $m1")
      val m2 = clue(e2.swap.toOption.map(_.getMessage).getOrElse("<unexpected Right>"))
      assert(clue(m2).contains("Mount refused"))
      assert(clue(m2).contains("device executor is not initialized"))
      // prepare 失败 = 全程零本机写（大脑根目录也不建）。
      assert(clue(!os.exists(PathUtil.dataRoot / "projects" / "prep-p" / "brain")))
  }

end DeviceProjectMountSpec
