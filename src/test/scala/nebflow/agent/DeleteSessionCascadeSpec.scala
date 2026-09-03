package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * V1 (2026-09-03, 结果投递链丢失向量修复): deleteSession 级联停 Delegate 子代理。
 *
 * 丢失形态（审计 V1）：deleteSession 只停 team 成员不停 Delegate 子代理——
 * 子完成后 BackoffSupervisor `parentRef ! ExternalEvent` 进死 queue（actor
 * 框架 offer 即忘，"messages sent to it vanish silently"）→ 结果静默蒸发。
 *
 * 修复：SessionChildCascade.stopChildDelegateActors——按 parentSessionId 找
 * Delegate/SubTask/Ephemeral 子 actor 停止 + registry 清理 + 任务记录终态化
 * （cancelRunningForParent）。取舍「停掉 vs 让其完成落盘」见对象 doc。
 *
 * 用例：
 *  - R1 GREEN 全链：真 parent AgentActor Delegate 出真子代理（挂死 LLM）→
 *    级联停止 → 子 actor 停（LLM 流取消）、registry 行清、任务记录 cancelled、
 *    父不被唤醒。
 *  - R2 RED BASELINE（丢失形态，变异 = 绕过级联）：父先删（Stop），子延迟
 *    完成 → 完成事件投进死 queue → 父无唤醒请求、结果无处可寻（审计形态复现）。
 *  - R3 store 级：cancelRunningForParent 只取消在飞任务、保留已完成记录。
 */
class DeleteSessionCascadeSpec extends FunSuite:

  override def munitTimeout: FiniteDuration = 180.seconds

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  /** parent turn 1 = Delegate toolcall；child 行为按 mode：hang（级联测试）/
    * delayed（死信丢失形态测试——2s 后完成）。 */
  private class RoutingLlm(
      parentSid: String,
      counters: Ref[IO, Map[String, Int]],
      requests: Ref[IO, List[LlmRequest]],
      childMode: String
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counters.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, 0) + 1)) *>
        requests.update(req :: _)) >>
        (if req.sessionId == parentSid then
           Stream.eval(counters.get.map(_.getOrElse(req.sessionId, 0))).flatMap { n =>
             if n == 1 then
               Stream.emits(Seq(
                 StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
                   id = "tc-delegate-1",
                   name = "Delegate",
                   input = JsonObject(
                     "prompt" -> "work on the background item".asJson,
                     "description" -> "v1 cascade target".asJson,
                     "agent" -> "Worker".asJson
                   )
                 )),
                 StreamChunk.Done(None, None)
               )).covary[IO]
             else
               Stream.emits(Seq(
                 StreamChunk.TextDelta(s"parent turn $n"), StreamChunk.Done(None, None)
               )).covary[IO]
           }
         else if req.sessionId.startsWith("delegate-") then
           childMode match
             case "hang"     => Stream.never[IO]
             case "delayed"  => Stream.sleep[IO](2.seconds).drain ++ Stream(StreamChunk.TextDelta("V1_CHILD_RESULT_MARKER"), StreamChunk.Done(None, None))
             case other      => Stream.raiseError[IO](new RuntimeException(s"bad mode $other"))
         else Stream(StreamChunk.TextDelta("unexpected"), StreamChunk.Done(None, None)).covary[IO])

  private def mkResources(
      system: ActorSystem,
      tmp: os.Path,
      llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
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
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  /** parent def：name 必须是 Nebula（Delegate 为 Nebula 专属工具）。 */
  private val nebulaDef: AgentDef =
    AgentDef(name = "Nebula", description = "v1 root", tools = List("Delegate"), systemPrompt = "")

  private def seedAgents(tmp: os.Path): Unit =
    val neb = tmp / "agents" / "Nebula"
    os.makeDir.all(neb)
    os.write.over(neb / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"v1 root","tools":["Delegate"]}"""
    )
    val worker = tmp / "agents" / "Worker"
    os.makeDir.all(worker)
    os.write.over(worker / "agent.json",
      """{"name":"Worker","displayName":"Worker","description":"v1 delegate target","tools":[]}"""
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 100.millis)(
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

  /** Spawn 真 parent + Delegate 出真 child（挂死或延迟完成），返回 fixture。 */
  private def spawnParentWithChild(
      system: ActorSystem,
      tmp: os.Path,
      childMode: String
  ): IO[(SharedResources, String, Ref[IO, List[LlmRequest]])] =
    for
      counters <- Ref.of[IO, Map[String, Int]](Map.empty)
      requests <- Ref.of[IO, List[LlmRequest]](Nil)
      resources <- mkResources(system, tmp, RoutingLlm("v1-parent", counters, requests, childMode))
      parentRef <- system.spawn(
        AgentActor(
          agentDef = nebulaDef,
          resources = resources,
          wsSend = _ => IO.unit,
          depth = 0,
          sessionId = Some("v1-parent"),
          sessionName = Some("v1-cascade")
        ),
        "v1-parent"
      )
      _ <- resources.agentRegistry.update(
        _ + ("v1-parent" -> AgentRecord("v1-parent", parentRef, AgentKind.Root, "v1-parent", None))
      )
      _ <- parentRef ! AgentCommand.UserInput("kick off the delegation", None, Some("v1-1"))
      _ <- waitUntil(15.seconds)(
        resources.agentRegistry.get.map(_.keys.exists(_.startsWith("delegate-")))
      )
    yield (resources, "v1-parent", requests)

  test("R1 GREEN: cascade cancels the hung child via supervisor, unregisters it, cancels its task, barrier released") {
    val system = ActorSystem("v1-r1")
    val tmp = os.temp.dir(prefix = "v1-r1")
    seedAgents(tmp)
    PathUtil.setDataRoot(tmp / "data")
    try
      val io = for
        (resources, parentSid, requests) <- spawnParentWithChild(system, tmp, "hang")
        registry1 <- resources.agentRegistry.get
        childSid = registry1.keys.find(_.startsWith("delegate-")).get
        // ── V1 cascade（deleteSession 的级联步骤，与 WS 路径同一实现）──
        stopped <- SessionChildCascade.stopChildDelegateActors(resources, parentSid)
        _ = assertEquals(clue(stopped), List(childSid), "the delegate child must be cascade-stopped")
        _ <- waitUntil(10.seconds)(resources.agentRegistry.get.map(!_.contains(childSid)))
        taskAfter <- resources.subAgentTaskStore.findByTaskId(childSid)
        _ <- waitUntil(10.seconds)(
          resources.subAgentTaskStore.findByTaskId(childSid).map(t => t.exists(_.status == "cancelled"))
        )
        _ = assert(taskAfter.flatMap(_.lastError).exists(t => t.contains("deleted") || t.contains("cancelled")), clue(taskAfter).toString)
        // Supervisor 正路：父收到 cancelled 通知（barrier 正确释放，无 phantom slot），
        // 唤醒轮的 LLM 请求携带 cancelled payload。
        _ <- waitUntil(15.seconds)(
          requests.get.map(r =>
            r.filter(_.sessionId == parentSid).exists(_.messages.exists(_.textContent.contains("cancelled")))
          )
        )
        // 之后不再有 child 完成/重启的进一步注入（child 已被 supervisor 停止）。
        _ <- IO.sleep(1.5.seconds)
        parentRequests <- requests.get.map(_.count(_.sessionId == parentSid))
        _ = assertEquals(clue(parentRequests), 3, "turn-1 (2 calls) + cancelled wake = 3; no more")
      yield ()
      io.unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("R2 RED BASELINE (cascade bypassed): child completes after parent death — result vanishes into the dead queue") {
    val system = ActorSystem("v1-r2")
    val tmp = os.temp.dir(prefix = "v1-r2")
    seedAgents(tmp)
    PathUtil.setDataRoot(tmp / "data")
    try
      val io = for
        (resources, parentSid, requests) <- spawnParentWithChild(system, tmp, "delayed")
        registry1 <- resources.agentRegistry.get
        childSid = registry1.keys.find(_.startsWith("delegate-")).get
        // 变异点（= 绕过 V1 修复）：不调 SessionChildCascade。父 actor 先死
        // （deleteSession 语义的等价物：actor 循环退出、registry 行移除）。
        parentRow <- resources.agentRegistry.get.map(_.get(parentSid))
        _ <- parentRow.traverse_(rec => system.stop(rec.ref))
        _ <- resources.agentRegistry.update(_ - parentSid)
        // 子代理 2s 后完成 → BackoffSupervisor notifyParentAndStop →
        // ExternalEvent offer 进已死父 queue（offer 即忘，无人消费）。
        _ <- IO.sleep(4.seconds) // child completes at ~2s; give the offer time to vanish
        parentRequests <- requests.get.map(_.filter(_.sessionId == parentSid))
        childDone <- requests.get.map(_.exists(r => r.sessionId.startsWith("delegate-")))
      yield (parentRequests, childDone, childSid)

      val (parentRequests, childDone, childSid) = io.unsafeRunSync()
      assert(clue(childDone), "child must have completed its turn (delayed LLM)")
      assert(
        !parentRequests.exists(_.messages.exists(_.textContent.contains("V1_CHILD_RESULT_MARKER"))),
        s"RED BASELINE: the child result must NEVER reach the deleted parent (audited V1 loss shape, child=$childSid)"
      )
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("R3 store: cancelRunningForParent cancels in-flight tasks only, keeps completed records") {
    val tmp = os.temp.dir(prefix = "v1-r3")
    PathUtil.setDataRoot(tmp / "data")
    try
      val store = new SubAgentTaskStore(tmp / "subagent-tasks")
      val io = for
        _ <- store.recordTask(SubAgentTask("delegate-a", "p1", "Worker", "x", "d-a", "running", 0, 1L, None, None, "delegate"))
        _ <- store.recordTask(SubAgentTask("delegate-b", "p1", "Worker", "x", "d-b", "completed", 0, 1L, Some(2L), None, "delegate"))
        _ <- store.recordTask(SubAgentTask("delegate-c", "p1", "Worker", "x", "d-c", "restarting", 1, 1L, None, None, "delegate"))
        _ <- store.recordTask(SubAgentTask("delegate-d", "p2", "Worker", "x", "d-d", "running", 0, 1L, None, None, "delegate"))
        cancelled <- store.cancelRunningForParent("p1", "parent deleted")
        tasks <- store.loadTasks("p1")
        other <- store.loadTasks("p2")
      yield (cancelled, tasks, other)
      val (cancelled, tasks, other) = io.unsafeRunSync()
      assertEquals(clue(cancelled.sorted), List("delegate-a", "delegate-c"))
      val byId = tasks.map(t => t.taskId -> t).toMap
      assertEquals(byId("delegate-a").status, "cancelled")
      assertEquals(byId("delegate-c").status, "cancelled")
      assertEquals(byId("delegate-b").status, "completed", "completed records are history — untouched")
      assert(byId("delegate-a").lastError.exists(_.contains("deleted")))
      assertEquals(clue(other.map(_.status)), List("running"), "other parents untouched")
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

end DeleteSessionCascadeSpec
