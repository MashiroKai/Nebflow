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
import nebflow.actor.{AgentCommand, AgentDef, messages, sessionId, status}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ToolError}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  ContentBlock,
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  MemoryHygieneSignal,
  PathUtil,
  StreamChunk,
  ThinkingConfig
}

import scala.concurrent.duration.*

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M4):信号已下沉 shared。
/**
 * 任务账本 e2e（2026-09-06 TaskList 批，stub-LLM 全链路；2026-10-14 redfix 批
 * 按 taskunify 合一批（2026-09-24 `75fddc5db`）合同重锚：TaskList/TaskBoard 双件
 * 已合一为 `Task`（Nebula 独占唯一写面，合一账本 `~/.nebflow/tasks-v2.json`，
 * 三态 {open, closed, completed}），提醒数据源同步指向合一账本
 * （`ContextRefresher` → `TaskLedgerStore.openSummaryLine`，标记 `[Task]`）。
 * 本 spec 原「TaskList 工具 + tasks.json」面即被取代的旧合同形态。）
 *
 * 覆盖任务书验收「重启后账本持久、open 摘要提醒出现、全闭环后提醒消失」——
 * 真 AgentActor（name=Nebula，固定面含 Task——taskunify 后 Nebula 唯一任务写面）
 * + 真 refreshTurn/buildMemoryBlock 注入链 + 真 Task 工具执行 + 真
 * ~/.nebflow 层 tasks-v2.json 落盘，仅 LLM 桩化（stub，任务书允许 stub/隔离实例
 * 二选一；禁碰宿主——全程 PathUtil.setDataRoot(临时目录)）：
 *
 *  - A「重启后持久 + 提醒出现」：前进程遗留的 tasks-v2.json（直写盘面）+ 进程
 *    启动信号（MemoryHygieneSignal restarted=true——与真重启同源）→ 新 actor
 *    首个 LlmRequest 的 systemStable 带 [Task] 摘要行；同 actor 内真工具
 *    调用（stub LLM 发 ToolCallChunk）经 face 过滤 → 注册表 → 执行 → 落盘。
 *  - B「跨 actor 代际持久」：新 actor（新会话）同 home 首请求可见 A 代创建的
 *    任务（tasks-v2.json 是唯一持久源——文件级「重启持久」证明）。
 *  - C「全闭环提醒消失」：闭环全部任务后，新生命周期信号 + 新 actor 首请求
 *    systemStable 不含 [Task]。
 *
 * 注：条件块（含 memoryBlock）整体进 systemStable，且 systemStable 仅在生命
 * 周期节点重建（cache v2）——与「生命周期节点注入时点」的设计语义同构。
 */
class TaskListE2ESpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  /** 捕获请求的桩 LLM：e2e-tl-a 会话首轮发 Task create toolcall，其余纯文本。 */
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
      Stream
        .eval(
          counters.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, 0) + 1)) *>
            requests.update(_ :+ req)
        )
        .flatMap { _ =>
          if req.sessionId == scriptedSid && req.tools.exists(_.exists(_.name == "Task")) then
            scriptedTurn(counters.get.map(_.getOrElse(req.sessionId, 0)))
          else Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
        }

    private def scriptedTurn(n: IO[Int]): Stream[IO, StreamChunk] =
      Stream.eval(n).flatMap {
        case 1 =>
          Stream(
            StreamChunk.ToolCallChunk(
              nebflow.shared.ToolCall(
                id = "tc-task-1",
                name = "Task",
                input = JsonObject(
                  "action" -> "create".asJson,
                  "title" -> createTitle.asJson,
                  "project" -> "e2e".asJson
                )
              )
            ),
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
        case true => IO.unit
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

  /**
   * loadCurrentDef 每 turn 从磁盘重载 Nebula——显式 agent.json 钉住（空目录
   * 会回落 Seeds.RootAgent；converged 名单固定面不受声明影响，但 seed 保持与
   * AgentControlE2ESpec 线束同构）。
   */
  private def seedNebula(tmp: os.Path): Unit =
    val dir = tmp / "agents" / "Nebula"
    os.makeDir.all(dir)
    os.write.over(
      dir / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"e2e root","tools":["Read"]}"""
    )

  private def spawnActor(
    system: ActorSystem,
    resources: SharedResources,
    sid: String
  ): IO[nebflow.actor.ActorRef[AgentCommand]] =
    for
      ref <- system.spawn(
        AgentActor(
          agentDef =
            AgentDef(name = "Nebula", description = "e2e tasklist root", tools = List("Read"), systemPrompt = ""),
          resources = resources,
          wsSend = _ => IO.unit,
          depth = 0,
          sessionId = Some(sid),
          sessionName = Some(s"e2e-$sid")
        ),
        sid
      )
      _ <- resources.agentRegistry.update(
        _ + (sid -> nebflow.actor.AgentRecord(sid, ref, nebflow.actor.AgentKind.Root, sid, None))
      )
    yield ref

  private def ledgerTasks(home: os.Path): List[nebflow.core.project.TaskEntry] =
    val f = home / nebflow.core.project.TaskLedgerStore.FileName
    if os.exists(f) then
      io.circe.parser.decode[nebflow.core.project.TaskLedgerData](os.read(f)).toOption.map(_.tasks).getOrElse(Nil)
    else Nil

  test("E2E: 重启后账本持久 + open 提醒出现 + 真工具执行落盘；全闭环后提醒消失"):
    val system = ActorSystem("tasklist-e2e")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp) // tasks-v2.json = tmp/tasks-v2.json（~/.nebflow 同层语义）
    try
      val program = for
        // ── 前进程遗留状态：直写一个 open 任务（模拟上一进程落盘；taskunify 后
        // 唯一持久源 = 合一账本 tasks-v2.json，水位 nextId=1 ⇒ 工具 create 得 id 2）──
        _ <- IO {
          val seed = nebflow.core.project.TaskLedgerData(
            tasks = List(
              nebflow.core.project.TaskEntry(
                id = "1",
                title = "写交付报告",
                status = nebflow.core.project.TaskLedgerStore.Status.Open,
                createdAt = Some("2026-09-06T00:00:00Z"),
                updatedAt = Some("2026-09-06T00:00:00Z")
              )
            ),
            nextId = 1
          )
          os.write.over(tmp / nebflow.core.project.TaskLedgerStore.FileName, seed.asJson.noSpaces)
        }
        counters <- IO.ref(Map.empty[String, Int])
        requests <- IO.ref(List.empty[LlmRequest])
        llm = ScriptedLlm(counters, requests, scriptedSid = "e2e-tl-a", createTitle = "写周报")
        resources <- mkResources(system, tmp, llm)

        // ── Phase A：「重启」（新 actor + 进程启动信号）→ open 提醒出现 ──
        _ <- IO { MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false) }
        refA <- spawnActor(system, resources, "e2e-tl-a")
        _ <- refA ! AgentCommand.UserInput("track a task", None, Some("tl-a-1"))
        _ <- waitUntil(20.seconds)(counters.get.map(_.getOrElse("e2e-tl-a", 0) >= 2))
        reqsA <- requests.get
        firstA = reqsA.find(_.sessionId == "e2e-tl-a").get
        _ = assert(
          firstA.systemStable.exists(_.contains("[Task]")),
          s"重启后首请求 systemStable 必须带 [Task] 摘要行:\n${firstA.systemStable.getOrElse("").take(2000)}"
        )
        _ = assert(firstA.systemStable.exists(_.contains("#1[open] 写交付报告")), "摘要行须含遗留 open 任务")
        // 真工具执行：face 过滤（Nebula 固定面含 Task——taskunify 后 Nebula 唯一
        // 任务写面；🔴 授能集成员曾是 PR48 重建回退的重灾区，本断言即其回归门）
        // → 执行 → 落盘。
        // 证据链：① 第二轮请求携带 ToolResult 块（textContent 不含 ToolResult
        // 文本，按块类型断言——AgentControlE2ESpec 同款 content.fold 手法）；
        // ② stub toolcall 创建的任务落盘（最强执行证据）。
        _ = assert(
          reqsA.exists(
            _.messages.exists(m => m.content.fold(_ => false, bs => bs.exists(_.isInstanceOf[ContentBlock.ToolResult])))
          ),
          "toolcall 轮之后的新请求必须携带 Task 的 ToolResult 块"
        )
        diskAfterA = ledgerTasks(tmp)
        _ = assert(diskAfterA.exists(t => t.id == "2" && t.title == "写周报"), s"stub toolcall 创建的任务必须落盘: $diskAfterA")

        // ── Phase B：跨 actor 代际持久（新会话，同 home）──
        _ <- IO { MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false) }
        refB <- spawnActor(system, resources, "e2e-tl-b")
        _ <- refB ! AgentCommand.UserInput("check tasks", None, Some("tl-b-1"))
        _ <- waitUntil(20.seconds)(requests.get.map(_.exists(_.sessionId == "e2e-tl-b")))
        firstB <- requests.get.map(_.find(_.sessionId == "e2e-tl-b").get)
        _ = assert(
          firstB.systemStable.exists(_.contains("[Task] 2 open")),
          s"代际持久：新 actor 首请求须见 A 代创建的任务（2 open）:\n${firstB.systemStable.getOrElse("").take(2000)}"
        )
        _ = assert(firstB.systemStable.exists(_.contains("#2[open] 写周报")), "A 代 stub 创建的任务跨代可见")

        // 闭环全部任务（直调工具——Nebula 根身份 ctx（写面 fail-closed 闸要求
        // isNebulaWriter）；执行路径 Phase A 已全链路验证）
        closeRes <- nebflow.core.tools.TaskToolDef.call(
          JsonObject("action" -> "close".asJson, "id" -> "1".asJson),
          nebulaCtx(resources, "e2e-tl-b")
        )
        _ = assert(closeRes.isRight, closeRes.toString)
        closeRes2 <- nebflow.core.tools.TaskToolDef.call(
          JsonObject("action" -> "close".asJson, "id" -> "2".asJson),
          nebulaCtx(resources, "e2e-tl-b")
        )
        _ = assert(closeRes2.isRight, closeRes2.toString)
        _ = assert(
          ledgerTasks(tmp).forall(t => t.status == "closed" || t.status == "completed"),
          "全部闭环（三态账本终态 = closed/completed）"
        )

        // ── Phase C：全闭环 + 新生命周期信号 → 提醒消失 ──
        _ <- IO { MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false) }
        refC <- spawnActor(system, resources, "e2e-tl-c")
        _ <- refC ! AgentCommand.UserInput("all done?", None, Some("tl-c-1"))
        _ <- waitUntil(20.seconds)(requests.get.map(_.exists(_.sessionId == "e2e-tl-c")))
        firstC <- requests.get.map(_.find(_.sessionId == "e2e-tl-c").get)
        _ = assert(
          !firstC.systemStable.exists(_.contains("[Task]")),
          s"全闭环后新生命周期注入不得再带 [Task]:\n${firstC.systemStable.getOrElse("").take(2000)}"
        )
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

    end try

  /** 遗留 ctx（无 agent 身份 ⇒ isNebulaWriter=false，用于负控面）。 */
  private def ctxResources(resources: SharedResources, sid: String): nebflow.core.tools.ToolContext =
    nebflow.core.tools.ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(sid),
      sharedResources = Some(resources)
    )

  /** Nebula 根身份 ctx——`Task` 写面运行期闸（isNebulaWriter）的合法形态。 */
  private def nebulaCtx(resources: SharedResources, sid: String): nebflow.core.tools.ToolContext =
    ctxResources(resources, sid).copy(
      agentDef = Some(AgentDef(name = "Nebula", description = "e2e ledger writer", tools = Nil, systemPrompt = "")),
      depth = 0
    )

end TaskListE2ESpec
