package nebflow.core.delegate

import cats.effect.IO
import cats.effect.Ref
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.*
import nebflow.core.*
import nebflow.core.device.{DeviceEntry, DeviceHmac, DeviceRegistry}
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.PluginMcpManager
import nebflow.core.scheduler.{ScheduledTaskService, ScheduledTaskStore}
import nebflow.core.tools.ToolContext
import nebflow.shared.*

import java.nio.file.Files

/**
 * DeviceDelegate spawn 信任通道（v2.1 阶段 1 §A.3/§A.4）的承重断言：
 *   - spawnCanonical 冻结形（签名与验签唯一拼装点的锚）；
 *   - buildSpawnPayload ↔ spawnCanonical + DeviceHmac.verify 闭环；task 篡改必拒；
 *   - **fail-closed 接收面**：malformed / 未配对 / 坏签 / 超窗 ts 的 spawn 一律拒绝
 *     且**绝不触到运行时资源**（throwing-stub 证明——拒绝路径只有判据与日志）。
 *
 * 接受路径（真 spawn）需要完整运行时，不在本 spec（集成面）；这里钉的是
 * 「没有有效 HMAC 就没有会话」这半边。
 */
class DeviceDelegateSpawnTrustSpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-dd-trust"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  // 任何成员被触到即炸——拒绝路径不得触碰运行时。
  private def boom: Throwable = new IllegalStateException("rejection path must not touch runtime resources")

  private val neverResources: AgentRuntimePort = new AgentRuntimePort:
    def sessionStore: SessionStore = throw boom
    def actorSystem: ActorSystem = throw boom
    def contextWindow: Int = throw boom
    def effectiveSafetyMode: IO[SafetyMode] = throw boom
    def subAgentTaskStore: SubAgentTaskStorePort = throw boom
    def pluginMcp: PluginMcpManager = throw boom
    def scheduledTaskStore: ScheduledTaskStore = throw boom
    def scheduledTaskService: Option[ScheduledTaskService] = throw boom
    def neblinkService: Option[NeblinkServicePort] = throw boom
    def dropboxService: Option[DropboxServicePort[?]] = throw boom
    def friendService: Option[FriendServicePort] = throw boom
    def healthMonitor: ProviderHealthPort = throw boom
    def interactionHubRef: Ref[IO, Option[ActorRef[InteractionHubCommand]]] = throw boom
    def spawnAgentActor(system: ActorSystem, p: NodeRunner.SpawnParams): IO[ActorRef[AgentCommand]] = throw boom
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
      compactThresholdRatio: Option[Double] = None
    ): Behavior[AgentCommand] = throw boom
    def agentRegistry: Ref[IO, Map[String, AgentRecord]] = throw boom
    def processingSessionIds: IO[List[String]] = throw boom
  end neverResources

  private val key = "pair-secret-α"
  private val hostId = "dev-host"

  private def pairHost(): Unit =
    DeviceRegistry.upsertSync(DeviceEntry(hostId, "Host", key, allowSpawn = true, createdAt = 1L))

  private def signedPayload(
    k: String = key,
    nowMs: Long = System.currentTimeMillis(),
    nonce: String = "n-" + System.nanoTime(),
    task: String = "do the thing"
  ): Json =
    DeviceDelegate.buildSpawnPayload(
      k,
      hostId,
      "Host",
      "spawn-1",
      "proj",
      "/remote/ws",
      task,
      "Do The Thing",
      "parent-1",
      "root-1",
      Some("42"),
      nowMs,
      nonce
    )

  test("spawnCanonical is the frozen pipe-joined form with task sha in the tail") {
    val canon = DeviceDelegate.spawnCanonical("d", 1L, "n", "s", "p", "w", "t", "ti", "par", "root", Some("7"))
    assertEquals(canon, s"d|1|n|SPAWN|/neblink/delegate-spawn|s|p|w|ti|par|root|7|${DeviceHmac.bodySha256("t")}")
    // taskId 缺席 = 空段（不是缺段——列数冻结）。
    val canonNoTask = DeviceDelegate.spawnCanonical("d", 1L, "n", "s", "p", "w", "t", "ti", "par", "root", None)
    assertEquals(canonNoTask.split('|').length, 13)
  }

  test("buildSpawnPayload verifies against spawnCanonical; tampered task fails") {
    val payload = signedPayload()
    val ts = payload.hcursor.downField("ts").as[Long].getOrElse(0L)
    val nonce = payload.hcursor.downField("nonce").as[String].getOrElse("")
    val sig = payload.hcursor.downField("sig").as[String].getOrElse("")
    val canon = DeviceDelegate.spawnCanonical(
      hostId,
      ts,
      nonce,
      payload.hcursor.downField("spawnId").as[String].getOrElse(""),
      payload.hcursor.downField("projectName").as[String].getOrElse(""),
      payload.hcursor.downField("workspace").as[String].getOrElse(""),
      payload.hcursor.downField("task").as[String].getOrElse(""),
      payload.hcursor.downField("title").as[String].getOrElse(""),
      payload.hcursor.downField("parentSessionId").as[String].getOrElse(""),
      payload.hcursor.downField("rootSessionId").as[String].getOrElse(""),
      payload.hcursor.downField("taskId").as[Option[String]].toOption.flatten
    )
    assert(DeviceHmac.verify(key, canon, sig))
    // 体篡改（task 换了）⇒ sha 段变化 ⇒ 拒。
    val tampered = canon.split('|').dropRight(1).mkString("|") + "|" + DeviceHmac.bodySha256("evil")
    assert(!DeviceHmac.verify(key, tampered, sig))
  }

  test("handleSpawn: malformed payload rejected without touching runtime") {
    val bad = Json.obj("kind" -> DeviceDelegate.KindSpawn.asJson) // 缺 spawnId/task/workspace
    assertIO(DeviceDelegate.handleSpawn(neverResources, bad), ())
  }

  test("handleSpawn: unpaired device rejected (no local session, no resource touch)") {
    val payload = signedPayload()
    assertIO(DeviceDelegate.handleSpawn(neverResources, payload), ())
  }

  test("handleSpawn: paired but bad signature rejected") {
    pairHost()
    // 用错误密钥签名（对端没有这份共享钥）。
    val payload = signedPayload(k = "wrong-key")
    assertIO(DeviceDelegate.handleSpawn(neverResources, payload), ())
  }

  test("handleSpawn: paired but stale ts rejected (±90s window)") {
    pairHost()
    val payload = signedPayload(nowMs = System.currentTimeMillis() - 120_000L)
    assertIO(DeviceDelegate.handleSpawn(neverResources, payload), ())
  }

  test("handleSpawn: paired but allowSpawn=false rejected") {
    DeviceRegistry.upsertSync(DeviceEntry(hostId, "Host", key, allowSpawn = false, createdAt = 1L))
    val payload = signedPayload()
    assertIO(DeviceDelegate.handleSpawn(neverResources, payload), ())
  }

  test("extractPointers maps terminal fileChanges metadata to (device,path,changeType) pointers") {
    val meta = JsonObject(
      "fileChanges" -> Json.obj(
        "files" -> Json.arr(
          Json.obj("path" -> "/ws/a.txt".asJson, "changeType" -> "created".asJson),
          Json.obj("path" -> "/ws/b.md".asJson, "changeType" -> "modified".asJson),
          Json.obj("other" -> "x".asJson) // 无 path 键 = 跳过
        )
      )
    )
    val pointers = DeviceDelegate.extractPointers(meta, "dev-exec")
    assertEquals(pointers.map(_.hcursor.downField("device").as[String].getOrElse("?")), List("dev-exec", "dev-exec"))
    assertEquals(pointers.map(_.hcursor.downField("path").as[String].getOrElse("?")), List("/ws/a.txt", "/ws/b.md"))
    // 空 metadata = 空清单（指针不迁移语义：无文件变更就无指针）。
    assertEquals(DeviceDelegate.extractPointers(JsonObject.empty, "d"), Nil)
  }

end DeviceDelegateSpawnTrustSpec
