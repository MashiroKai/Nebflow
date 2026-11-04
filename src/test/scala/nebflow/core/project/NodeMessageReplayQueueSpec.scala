/* C-2 · 兜底回放腿（2026-11-04）·
 *
 * 与 [[NodeMessagePersistenceSpec]] 分开成件的理由：本腿测的是**新接口**
 * `NodeEngine.replayPendingNodeMessages`（项目/delegate 作用域 sweep 在 claim 循环之后
 * 调用的队列兜底回放），要能独立跑出「改前红」（改前无此接口 = 编译即红）与「改后绿」，
 * 不与 S1–S3 的语义红混在一条日志里。
 *
 * 场景（确切崩溃窗口）：`sendNodeMessage` 写完台账 `queued` 行、**还没**把消息追加进
 * task（或接线/pending 路由的变异事务被打断）时进程死掉 ⇒ task 没有正文，节点也没被
 * boot 认领（它不是 Running/Interrupted）⇒ resume prompt 回放腿不覆盖它。兜底腿把这条
 * 消息按「注入未达」形态追加进 task（durable carrier，随 flow-map 落盘），并保证
 * **二次 boot 不重复追加**。
 */
package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentKind, AgentRecord}
import nebflow.agent.SharedResources
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.ProviderHealthMonitor
import nebflow.shared.{PathUtil, ThinkingConfig}

import scala.concurrent.duration.*

class NodeMessageReplayQueueSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val originalRoot = PathUtil.dataRoot
  nebflow.core.LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  private def mkEngine(
    store: FlowMapStore,
    system: ActorSystem,
    resources: SharedResources,
    ws: os.Path,
    project: String
  ): NodeEngine =
    new NodeEngine(
      store,
      system,
      resources,
      wsSendFn = (_: Json) => IO.unit,
      workspace = ws.toString,
      rootSessionId = "nebula-root",
      projectName = project,
      emitEvent = (_: String, _: String, _: Json) => IO.unit
    )

  private def occurrences(hay: String, needle: String): Int =
    if needle.isEmpty then 0 else hay.sliding(needle.length).count(_ == needle)

  test("S4 队列兜底回放: 未认领节点的排队消息补进 task（二次 boot 不重复追加）") {
    val tmp = os.temp.dir(prefix = "nmq-s4")
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(s"nmq-s4-${java.util.UUID.randomUUID().toString.take(6)}")
    val io =
      for
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, nebflow.llm.ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        sessionStore = SessionStore(tmp / "sessions", tmp / "tasks")
        resources = SharedResources(
          llm = null,
          dispatcher = dispatcher,
          sessionStore = sessionStore,
          projectRoot = os.pwd,
          thinkingConfigRef = thinkingRef,
          rateLimiter = rateLimiter,
          fileChangeTracker = tracker,
          contextWindow = 100_000,
          agentLibrary = new nebflow.agent.AgentLibrary(tmp / "agents"),
          taskStore = FileTaskStore,
          historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
          fileLockManager = fileLocks,
          sessionModelOverrides = modelOverrides,
          providerRegistry = null,
          healthMonitor = ProviderHealthMonitor(null),
          actorSystem = system,
          subAgentTaskStore = new nebflow.agent.SubAgentTaskStore(tmp / "subagent-tasks"),
          voiceMutedRef = voiceMuted
        )
        ws <- IO.blocking { val d = tmp / "ws-s4"; os.makeDir.all(d); d }
        store <- FlowMapStore.open("nmq-s4", ws.toString)
        sid = "node-s4-session"
        nodeId = "n-s4"
        _ <- store.mutate(s =>
          s.copy(nodes =
            s.nodes + (nodeId -> NodeDef(
              id = nodeId,
              name = "n-s4",
              agent = "general",
              status = NodeLifecycle.Pending,
              sessionRef = Some(sid),
              task = Some("S4_BRIEF"),
              createdAt = System.currentTimeMillis() - 1000L
            ))
          )
        )
        // 崩溃现场：台账有 queued 行，task 没正文（挂在 queued 与 task 追加之间的微窗口）
        id = "nm-s4-fixture"
        _ <- NodeMessageLedger.append(
          ws.toString,
          NodeMessageLedger.Row(
            id = id,
            event = NodeMessageLedger.Queued,
            nodeId = nodeId,
            nodeName = "n-s4",
            text = "S4_QUEUED_INSTRUCTION",
            route = NodeMessageLedger.RouteTask,
            ts = System.currentTimeMillis()
          )
        )
        engine1 = mkEngine(store, system, resources, ws, "nmq-s4")
        n1 <- engine1.replayPendingNodeMessages()
        task1 <- store.getNode(nodeId).map(_.flatMap(_.task).getOrElse(""))
        ledger1 <- NodeMessageLedger.entries(ws.toString)
        // 二次 boot（同一盘面）：绝不再追加一次
        engine2 = mkEngine(store, system, resources, ws, "nmq-s4")
        n2 <- engine2.replayPendingNodeMessages()
        task2 <- store.getNode(nodeId).map(_.flatMap(_.task).getOrElse(""))
        marker = NodeMessageLedger.markerOf(id)
        _ <- IO(
          println(
            Seq(
              s"[NMQ S4] replay #1 actions = $n1 ; #2 actions = $n2 (idempotent)",
              s"[NMQ S4] task carries body = ${task2.contains("S4_QUEUED_INSTRUCTION")} ; marker occurrences = ${occurrences(task2, marker)}",
              s"[NMQ S4] ledger = ${ledger1.map(e => s"${e.id}=${e.event}").mkString(", ")}"
            ).mkString("\n")
          )
        )
        _ = assertEquals(n1, 1, "S4: the queued message must be replayed into the task")
        _ = assert(
          task1.contains("S4_QUEUED_INSTRUCTION") && task1.contains(marker),
          "S4: the replay lands in the durable carrier (task「分发器补充」section) with its marker"
        )
        _ = assertEquals(ledger1.find(_.id == id).map(_.event), Some(NodeMessageLedger.Appended), "S4: state closes as appended")
        _ = assertEquals(n2, 0, "S4 IDEMPOTENCY: a second boot must replay nothing")
        _ = assertEquals(occurrences(task2, marker), 1, "S4 IDEMPOTENCY: the body is appended exactly once across two boots")
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

end NodeMessageReplayQueueSpec
