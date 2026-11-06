package nebflow.agent

import nebflow.neblink.core.*

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord, messages}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.{FileTaskStore, TaskCreateInput}
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.task.{FileTaskStore, TaskCreateInput}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}
import fs2.Stream

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*

/**
 * WIRING pins on a real AgentActor (Nebula def) + mock LLM.
 *
 * Reminder refactor (2026-08-20 user ruling) covered: system-event turns used to
 * inject ZERO time; real-user turns inject time but NOT tasks for Nebula (task
 * redesign 2026-08-30). **时间感知修复批（作者指令 2026-11-05 18:48）把前者翻了**：
 * 每一个起始轮（含 Mail / 节点消息 / 定时任务 / 外部事件驱动的 system-event 轮）
 * 都必须带时间锚——没有可信「现在」正是相对时间系统性误判（「10 天前」说成「两年」）
 * 的病根。本 spec 现在同时钉：① 起始轮有锚；② 每次请求的 systemDynamic 有当刻锚；
 * ③ systemStable 里绝无时间戳（时间不得冻结进前缀缓存）。
 * D6 (revised): systemStable NO LONGER carries the Task List Protocol
 *      section for Nebula (condition = agentCategory == "team")
 */
class ReminderWiringSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private class CaptureLlm(requests: Ref[IO, List[LlmRequest]]) extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)).drain ++
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks-ui"),
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
   * Pin the Nebula def on disk (empty agents dir falls back to Seeds.RootAgent
   * with a different toolset — harness trap, see memory).
   */
  private def seedNebula(tmp: os.Path): Unit =
    val dir = tmp / "agents" / "Nebula"
    os.makeDir.all(dir)
    os.write.over(
      dir / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"wiring root","tools":["Read"]}"""
    )

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

  private def textOf(req: LlmRequest): String =
    req.messages.map(_.textContent).mkString("\n")

  test(
    "① every input turn (system-event included) carries the time anchor; real-user turn injects time but Nebula gets NO tasks (task redesign)"
  ):
    val system = ActorSystem("reminder-wiring")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "wiring-nebula"
      // one active task for this session → real-user turns carry the list
      FileTaskStore.create(sid, TaskCreateInput(subject = "wiring task", description = "d")).unsafeRunSync()
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
        nebulaDef = AgentDef(
          name = "Nebula",
          description = "root under test",
          tools = List("Read"),
          systemPrompt = ""
        )
        actorRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("reminder-wiring")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None))
        )
        // ── ① ExternalEvent injection = system-event turn ──
        _ <- actorRef ! AgentCommand.ExternalEvent(
          source = "delegate",
          eventType = "completed",
          payload = "[RESULT] sub-agent finished"
        )
        _ <- waitUntil(15.seconds)(requests.get.map(_.size >= 1))
        req1 <- requests.get.map(_.head)
        // ── ② real user turn (clientMessageId → source=None) ──
        _ <- actorRef ! AgentCommand.UserInput("hello from the user", None, Some("cmid-1"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.size >= 2))
        req2 <- requests.get.map(_.apply(1))
        // ── ④ next real-user turn, tasks unchanged ──
        _ <- actorRef ! AgentCommand.UserInput("still here", None, Some("cmid-2"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.size >= 3))
        req3 <- requests.get.map(_.apply(2))
      yield
        // ① system-event turn (ExternalEvent injection)：时间感知修复批（2026-11-05）
        // **改为必须带锚**——旧契约「system-event 轮不注入时间」正是病根：交给模型的
        // 新输入可以完全没有「现在」，于是相对时间靠猜。tasks 仍为 0（团队面语义未变）。
        assert(
          textOf(req1).contains("Current time"),
          s"system-event turn MUST now carry the time anchor:\n${textOf(req1)}"
        )
        assert(!textOf(req1).contains("Current Tasks"), s"system-event turn must not inject tasks:\n${textOf(req1)}")
        assert(!textOf(req1).contains("Tasks unchanged"), s"system-event turn must not inject tasks:\n${textOf(req1)}")
        // D6 revised (task redesign 2026-08-30): Nebula's systemStable must
        // NOT carry the protocol section — it is team-category-only now
        req1.systemStable.foreach { s =>
          assert(
            !s.contains("## Task List Protocol"),
            s"Nebula systemStable must not carry the protocol section (team-only):\n$s"
          )
        }
        // ② real-user turn: time injected, but NO tasks for Nebula — task
        // injection moved to team-registered sessions; Nebula has no task
        // tools and its session is not team-scoped
        assert(textOf(req2).contains("Current time"), s"real-user turn must inject time:\n${textOf(req2)}")
        assert(
          !textOf(req2).contains("## Current Tasks"),
          s"task redesign: Nebula no longer gets the task reminder:\n${textOf(req2)}"
        )
        assert(
          !textOf(req2).contains("wiring task"),
          s"task redesign: Nebula must not see task lines:\n${textOf(req2)}"
        )
        // ③ next real-user turn: still no tasks
        assert(textOf(req3).contains("Current time"), s"real-user turn must inject time:\n${textOf(req3)}")
        assert(
          !textOf(req3).contains("## Current Tasks"),
          s"task redesign: Nebula no longer gets the task reminder:\n${textOf(req3)}"
        )
        // ④ 每请求时间锚（时间感知修复批 2026-11-05）：三个请求（含 system-event 轮）
        // 的 systemDynamic 都必须带当刻锚，且 **systemStable 里绝不许出现任何时间戳**
        // ——写进稳定前缀 = 时间冻结（它只在生命周期节点重建）。同一条 spec 同时钉
        // 正面（动态段有锚）与反面（stable 段无时刻）。
        val stampRe = raw"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{2}:\d{2}".r
        List(req1, req2, req3).zipWithIndex.foreach { case (req, i) =>
          val dyn = req.systemDynamic.getOrElse("")
          assert(
            dyn.contains("Clock (authoritative; re-read on every request)") && stampRe.findFirstIn(dyn).isDefined,
            s"request #${i + 1}: every request must carry a fresh clock line in systemDynamic, got: $dyn"
          )
          assert(
            dyn.contains("epoch_ms"),
            s"request #${i + 1}: the anchor must expose epoch_ms (arithmetic base): $dyn"
          )
          req.systemStable.foreach { st =>
            assert(
              stampRe.findFirstIn(st).isEmpty,
              s"request #${i + 1}: systemStable must NEVER carry a timestamp (frozen clock): $st"
            )
            assert(
              st.contains(nebflow.shared.Defaults.TimeDisciplineRule),
              s"request #${i + 1}: the time discipline rule must ride systemStable for every agent"
            )
          }
        }
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

    end try

  test("② unregistered Manager session gets zero tasks even on real-user turns"):
    val system = ActorSystem("reminder-wiring-mgr")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "wiring-manager"
      FileTaskStore.create(sid, TaskCreateInput(subject = "mgr task", description = "d")).unsafeRunSync()
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
        mgrDef = AgentDef(
          name = "Manager",
          description = "team lead under test",
          tools = List("Read"),
          systemPrompt = ""
        )
        actorRef <- system.spawn(
          AgentActor(
            agentDef = mgrDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("reminder-wiring-mgr")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None))
        )
        _ <- actorRef ! AgentCommand.UserInput("hello from the user", None, Some("cmid-1"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.nonEmpty))
        req1 <- requests.get.map(_.head)
        sys1 = req1.systemStable.getOrElse("")
      yield
        assert(
          textOf(req1).contains("Current time"),
          s"real-user turn still injects time for team agents:\n${textOf(req1)}"
        )
        assert(!textOf(req1).contains("Current Tasks"), s"Manager must not get the tasks reminder:\n${textOf(req1)}")
        assert(!textOf(req1).contains("mgr task"), s"Manager must not see task lines:\n${textOf(req1)}")
        assert(!sys1.contains("## Task List Protocol"), s"Task List Protocol is Nebula-only:\n$sys1")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

    end try

  // 团队功能删除批（team-feature-deletion-20261104）阶段 3：原用例 ③
  // 「team-registered agent gets tasks on real-user turns」整条删除 —— 它靠
  // `TeamSessionRegistry.registerSession(team, member, sid)` 把会话登记为团队成员，
  // 再断言 `team:<name>` 作用域任务出现在该会话的 turn 文本里。团队会话登记表本体
  // 已删 ⇒ 团队归属**恒 None**，该注入腿结构上不可达。
  // 删因：**团队会话归属能力已退役**（不是「跑不过」）。团队作用域任务存储
  // （`TaskStore.teamScopeKey`）与 TeamTask* 工具**未删**（作者未裁定），只是不再
  // 有会话能被判为团队作用域。
end ReminderWiringSpec
