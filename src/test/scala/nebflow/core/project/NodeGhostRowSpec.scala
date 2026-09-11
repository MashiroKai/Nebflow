package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * B：节点「幽灵行」事件契约回归（Explorer 取证 c759e8c，#37 B）。
 *
 * 根因：节点会话（parentRef=None, depth=1）完成时发射会话级 `done`（非 `agentDone`），
 * 前端 Sub-Agents 面板只认 agentDone 删行 → 每个节点留一行永久幽灵行。
 *
 * 修复：前端源头过滤 `node-` 前缀行（agentStart/activeAgents 跳过）+ done 兜底清理。
 * 本测试断言修复生效的**事件前提**：
 * 1. 节点 spawn 的 agentStart 事件 agentId 以 `node-` 开头（前端过滤条件可命中）
 * 2. 节点完成发 nodeCompleted（Flow Map 终态通道，与 Sub-Agents 面板无关）
 * 3. 节点完成**不**发 agentDone（根因确认——前端 agentDone 删行路径对节点永不触发，
 *    因此前端必须按 node- 前缀在源头过滤/终态清理，本测试锁定该契约）
 */
class NodeGhostRowSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-ghost-row"
  private val originalRoot = PathUtil.dataRoot

  // 类级：隔离 dataRoot + 预置 test-agent 定义（NodeEngine 走 EntityLoader= dataRoot/agents）
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"ghost-row regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private class RecordingLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
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
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  test("node run: agentStart agentId has node- prefix + nodeCompleted emitted + NO agentDone (ghost-row contract)") {
    val ws = tempRoot / "ws"
    os.makeDir.all(ws)
    val llm = new RecordingLlm
    for
      system <- IO.pure(ActorSystem(s"ghost-${scala.util.Random.nextInt(100000)}"))
      resources <- mkResources(system, tempRoot, llm)
      store <- FlowMapStore.open("ghost-test", ws.toString)
      wsEvents <- Ref.of[IO, List[Json]](Nil)
      engineEvents <- Ref.of[IO, List[(String, String)]](Nil)
      engine = new NodeEngine(
        store,
        system,
        resources,
        wsSendFn = j => wsEvents.update(j :: _),
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = "ghost-test",
        emitEvent = (t, id, _) => engineEvents.update((t, id) :: _),
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（生产默认开；
        // 腿 2 默认开行为由 NodeReportReminderSpec 覆盖）。
        reportGateHold = Some(false)
      )
      now = System.currentTimeMillis()
      _ <- store.mutate(s =>
        s.copy(nodes = s.nodes + ("n-1" -> NodeDef(
          id = "n-1",
          name = "调研-幽灵行",
          agent = "test-agent",
          skill = None,
          mcp = None,
          worktree = None,
          preset = None,
          task = Some("跑一轮"),
          in = Nil,
          out = List(OutEdge.nebula),
          status = NodeLifecycle.Pending,
          createdAt = now
        )))
      )
      _ <- engine.startNode("n-1")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      wsList <- wsEvents.get
      evList <- engineEvents.get
    yield
      // 1. agentStart 事件存在且 agentId 前缀 node-（前端源头过滤可命中）
      val starts = wsList.filter(j => j.hcursor.get[String]("type").toOption.contains("agentStart"))
      assert(starts.nonEmpty, "agentStart must be emitted for a node session")
      val nodeIds = starts.flatMap(_.hcursor.get[String]("agentId").toOption)
      assert(nodeIds.forall(_.startsWith("node-")), s"node agentStart agentId must have node- prefix: $nodeIds")

      // 2. 节点完成发 nodeCompleted（Flow Map 终态通道）
      assert(evList.exists(_._1 == "nodeCompleted"), s"nodeCompleted must be emitted, got: $evList")

      // 3. 节点完成不发 agentDone（根因锁定：前端 agentDone 删行对节点永不触发）
      val doneEvents = wsList.filter(j => j.hcursor.get[String]("type").toOption.contains("agentDone"))
      assertEquals(doneEvents.size, 0, "node completion must NOT emit agentDone (session-level done only)")
  }

end NodeGhostRowSpec
