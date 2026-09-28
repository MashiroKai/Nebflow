package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.{FileChangeTracker, PathUtil}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.project.{TaskEntry, TaskLedgerData, TaskLedgerStore}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, TaskTool, ToolContext, ToolError}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * Task-ledger e2e (stub-LLM full chain).
 *
 * Retargeted by the taskunify batch (2026-09-24): the persistence source moved from
 * the retired `~/.nebflow/tasks.json` to the unified ledger `~/.nebflow/tasks-v2.json`,
 * the write tool from `TaskList` to `Task` (Nebula-exclusive), and the injected summary
 * prefix from `[TaskList]` to `[Task]`. The e2e invariants are unchanged:
 *
 *  - A "persists across a restart + the reminder appears": a ledger left behind by the
 *    previous process (written straight to disk) + the process-start signal
 *    (MemoryHygieneSignal restarted=true — same origin as a real restart) ⇒ the new
 *    actor's first LlmRequest carries the `[Task]` summary line in systemStable; the
 *    same actor then performs a REAL tool call (the stub LLM emits a ToolCallChunk)
 *    through face filtering → registry → execution → disk. Nebula carries 17 tools,
 *    including `Task` (current = 17, the 2026-09-18 18:18 order +5 value).
 *  - B "persistence across actor generations": a new actor (new session) on the same
 *    home sees tasks created by generation A on its first request (the single ledger
 *    file is the only persistence source — the file-level proof of "restart persistence").
 *  - C "the reminder disappears once nothing is open": after every task reaches a
 *    terminal state, a new lifecycle signal + a new actor's first request carries no
 *    `[Task]` summary line.
 *
 * The conditional block (incl. memoryBlock) enters systemStable as a whole, and
 * systemStable is rebuilt only at lifecycle nodes (cache v2) — isomorphic to the design
 * semantics of "injected at the lifecycle node".
 */
class TaskListE2ESpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  /** Stub LLM that captures requests: the e2e-tl-a session emits a `Task` create toolcall on its first turn, plain text otherwise. */
  private class ScriptedLlm(
    counters: Ref[IO, Map[String, Int]],
    requests: Ref[IO, List[LlmRequest]],
    scriptedSid: String,
    createTitle: String
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(
        counters.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, 0) + 1)) *>
          requests.update(_ :+ req)
      ).flatMap { _ =>
        if req.sessionId == scriptedSid && req.tools.exists(_.exists(_.name == "Task")) then
          scriptedTurn(counters.get.map(_.getOrElse(req.sessionId, 0)))
        else
          Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
      }

    private def scriptedTurn(n: IO[Int]): Stream[IO, StreamChunk] =
      Stream.eval(n).flatMap {
        case 1 =>
          Stream(
            StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
              id = "tc-task-1",
              name = "Task",
              input = JsonObject(
                "action" -> "create".asJson,
                "title" -> createTitle.asJson,
                "project" -> "e2e".asJson
              )
            )),
            StreamChunk.Done(None, None)
          )
        case _ =>
          Stream(StreamChunk.TextDelta("created via Task"), StreamChunk.Done(None, None))
      }
  end ScriptedLlm

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

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
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

  /** loadCurrentDef 每 turn 从磁盘重载 Nebula——显式 agent.json 钉住（空目录
    * 会回落 Seeds.Nebula；converged 名单固定面不受声明影响，但 seed 保持与
    * AgentControlE2ESpec 线束同构）。 */
  private def seedNebula(tmp: os.Path): Unit =
    val dir = tmp / "agents" / "Nebula"
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"e2e root","tools":["Read"]}"""
    )

  private def spawnActor(system: ActorSystem, resources: SharedResources, sid: String): IO[nebflow.actor.ActorRef[AgentCommand]] =
    for
      ref <- system.spawn(
        AgentActor(
          agentDef = AgentDef(name = "Nebula", description = "e2e tasklist root", tools = List("Read"), systemPrompt = ""),
          resources = resources,
          wsSend = _ => IO.unit,
          depth = 0,
          sessionId = Some(sid),
          sessionName = Some(s"e2e-$sid")
        ),
        sid
      )
      _ <- resources.agentRegistry.update(_ + (sid -> nebflow.agent.AgentRecord(sid, ref, nebflow.agent.AgentKind.Root, sid, None)))
    yield ref

  private def diskTasks(home: os.Path): List[TaskEntry] =
    val f = home / TaskLedgerStore.FileName
    if os.exists(f) then io.circe.parser.decode[TaskLedgerData](os.read(f)).toOption.map(_.tasks).getOrElse(Nil)
    else Nil

  test("E2E: restart persists the ledger + the open reminder appears + a real tool call lands on disk; the reminder disappears once nothing is open"):
    val system = ActorSystem("tasklist-e2e")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp) // ledger = tmp/tasks-v2.json (same-layer semantics as ~/.nebflow)
    try
      val program = for
        // ── state left behind by the previous process: write one open task
        //    straight to the ledger (simulating the previous process's write) ──
        _ <- IO {
          val seed = TaskLedgerData(nextId = 1, tasks = List(
            TaskEntry(
              id = "1", title = "write the delivery report", status = TaskLedgerStore.Status.Open,
              createdAt = Some("2026-09-24T00:00:00Z"), updatedAt = Some("2026-09-24T00:00:00Z")))
          )
          os.write.over(tmp / TaskLedgerStore.FileName, seed.asJson.noSpaces)
        }
        counters <- IO.ref(Map.empty[String, Int])
        requests <- IO.ref(List.empty[LlmRequest])
        llm = ScriptedLlm(counters, requests, scriptedSid = "e2e-tl-a", createTitle = "write the weekly report")
        resources <- mkResources(system, tmp, llm)

        // ── Phase A：「重启」（新 actor + 进程启动信号）→ open 提醒出现 ──
        _ <- IO { MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false) }
        refA <- spawnActor(system, resources, "e2e-tl-a")
        _ <- refA ! AgentCommand.UserInput("track a task", None, Some("tl-a-1"))
        _ <- waitUntil(20.seconds)(counters.get.map(_.getOrElse("e2e-tl-a", 0) >= 2))
        reqsA <- requests.get
        firstA = reqsA.find(_.sessionId == "e2e-tl-a").get
        _ = assert(firstA.systemStable.exists(_.contains("[Task]")),
          s"the first request after a restart must carry the [Task] summary line:\n${firstA.systemStable.getOrElse("").take(2000)}")
        _ = assert(firstA.systemStable.exists(_.contains("#1[open] write the delivery report")),
          "the summary line must carry the leftover open task")
        // Real tool execution: face filtering (Nebula carries 17 tools incl. `Task`;
        // current = 17, the 2026-09-18 18:18 order +5 value) → registry → execution → disk.
        // 证据链：① 第二轮请求携带 ToolResult 块（textContent 不含 ToolResult
        // 文本，按块类型断言——AgentControlE2ESpec 同款 content.fold 手法）；
        // ② stub toolcall 创建的任务落盘（最强执行证据）。
        _ = assert(reqsA.exists(_.messages.exists(m =>
          m.content.fold(_ => false, bs => bs.exists(_.isInstanceOf[ContentBlock.ToolResult])))),
          "the new request after the toolcall turn must carry the Task ToolResult block")
        diskAfterA = diskTasks(tmp)
        _ = assert(diskAfterA.exists(t => t.id == "2" && t.title == "write the weekly report"),
          s"the task created by the stub toolcall must land on disk: $diskAfterA")

        // ── Phase B：跨 actor 代际持久（新会话，同 home）──
        _ <- IO { MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false) }
        refB <- spawnActor(system, resources, "e2e-tl-b")
        _ <- refB ! AgentCommand.UserInput("check tasks", None, Some("tl-b-1"))
        _ <- waitUntil(20.seconds)(requests.get.map(_.exists(_.sessionId == "e2e-tl-b")))
        firstB <- requests.get.map(_.find(_.sessionId == "e2e-tl-b").get)
        _ = assert(firstB.systemStable.exists(_.contains("[Task] 2 open")),
          s"generation persistence: the new actor's first request must see generation A's tasks (2 open):\n${firstB.systemStable.getOrElse("").take(2000)}")
        _ = assert(firstB.systemStable.exists(_.contains("#2[open] write the weekly report")),
          "the task created by generation A's stub is visible across generations")

        // Drive every task to a terminal state (calling the tool directly — the
        // execution path was already verified end to end in phase A). `close` (voided)
        // is used because it is state-machine-legal from `open` and never gated.
        closeRes <- IO(TaskTool.dispatchSync(TaskLedgerStore.open(), Map.empty,
          ctxResources(resources, "e2e-tl-b"), "close", id = Some("1")))
        _ = assert(closeRes.isRight, closeRes.toString)
        closeRes2 <- IO(TaskTool.dispatchSync(TaskLedgerStore.open(), Map.empty,
          ctxResources(resources, "e2e-tl-b"), "close", id = Some("2")))
        _ = assert(closeRes2.isRight, closeRes2.toString)
        _ = assert(diskTasks(tmp).forall(_.status == TaskLedgerStore.Status.Closed), "every entry reached a terminal state")

        // ── Phase C：全 done + 新生命周期信号 → 提醒消失 ──
        _ <- IO { MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false) }
        refC <- spawnActor(system, resources, "e2e-tl-c")
        _ <- refC ! AgentCommand.UserInput("all done?", None, Some("tl-c-1"))
        _ <- waitUntil(20.seconds)(requests.get.map(_.exists(_.sessionId == "e2e-tl-c")))
        firstC <- requests.get.map(_.find(_.sessionId == "e2e-tl-c").get)
        _ = assert(!firstC.systemStable.exists(_.contains("[Task]")),
          s"once nothing is open the new lifecycle injection must not carry [Task]:\n${firstC.systemStable.getOrElse("").take(2000)}")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

  /** Nebula-root identity: `ToolContext.isNebulaWriter` delegates to
    * `AgentCore.isNebulaRoot` (`agentDef.name == "Nebula" && depth == 0`), which is the
    * engine-side write authority for the `Task` face. */
  private def ctxResources(resources: SharedResources, sid: String): ToolContext =
    ToolContext(projectRoot = os.pwd.toString, sessionId = Some(sid),
      agentDef = Some(AgentDef(name = "Nebula", description = "e2e task root", tools = Nil)),
      sharedResources = Some(resources))

end TaskListE2ESpec
