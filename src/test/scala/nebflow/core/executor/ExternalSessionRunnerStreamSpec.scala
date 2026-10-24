package nebflow.core.executor

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.*
import nebflow.core.*
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.PluginMcpManager
import nebflow.core.scheduler.{ScheduledTaskService, ScheduledTaskStore}
import nebflow.shared.*
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * 子智能体面板实时可视化批（2026-10-24）验收 · 端到端流帧通路。
 *
 * 真 [[ExternalSessionRunner.spawn]] 驱动（不是适配器纯解析）——假 CLI 二进制
 * （shell 脚本逐行吐 codex fixture JSONL，行间 sleep 证增量时序）走完：
 *   spawn → readLoop 逐行 → parseLineWithFallback → WS 帧（sink 收集）→
 *   settle 终态 → SessionStore 历史落盘 → DelegateRegistry 记录。
 *
 * 中心性质（作者令验收面）：
 *   - 帧序：agentStart（含 project/taskId/taskTitle 归属三键）→ agentThinking →
 *     agentToolStart → agentToolEnd → agentTextDelta → agentDone（含 usage 的
 *     inputTokens/outputTokens），与进程输出逐行同步（不等进程结束一次性倒）；
 *   - 三键盖章与内部腿 routeSubagentWsSend 同契约（rootSessionId 归桶 /
 *     sessionId 路由 / nodeSessionId 弹窗）；
 *   - codex 空返回修复：fixture 无 Final 帧（成功 turn.completed 只有 usage）
 *     ⇒ 终稿退流面最后一段 agent_message 文本——SessionStore assistant 轮
 *     非空、父投递文本非空；
 *   - 历史同库：外部会话的 user/assistant 轮与内部 delegate 同一 SessionStore。
 */
class ExternalSessionRunnerStreamSpec extends CatsEffectSuite:

  override val munitIOTimeout = 90.seconds

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.temp.dir(prefix = "nb-ext-stream-")
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  /** 外部腿不得触碰的成员即炸（spawn 只碰 sessionStore / subAgentTaskStore）。 */
  private def boom: Throwable = new IllegalStateException("external leg must not touch this runtime member")

  private def makeResources(store: SessionStore): AgentRuntimePort =
    new AgentRuntimePort:
      def sessionStore: SessionStore = store
      def subAgentTaskStore: SubAgentTaskStorePort = new SubAgentTaskStorePort:
        def recordTask(task: SubAgentTask): IO[Unit] = IO.unit
        def updateStatus(
          parentSessionId: String,
          taskId: String,
          status: String,
          retryCount: Option[Int] = None,
          lastError: Option[String] = None,
          completedAt: Option[Long] = None
        ): IO[Unit] = IO.unit
        def findRunningTasks: IO[List[SubAgentTask]] = IO.pure(Nil)
        def findByTaskId(taskId: String): IO[Option[SubAgentTask]] = IO.pure(None)
      def actorSystem: ActorSystem = throw boom
      def contextWindow: Int = throw boom
      def effectiveSafetyMode: IO[SafetyMode] = throw boom
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
        extraMetadata: io.circe.JsonObject = io.circe.JsonObject.empty,
        wsSend: Option[Json => IO[Unit]] = None
      ): IO[ActorRef[AgentEvent]] = throw boom
      def agentActorBehavior(
        agentDef: AgentDef,
        wsSend: Json => IO[Unit],
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

  /**
   * 假 codex 二进制：逐行吐 fixture JSONL（行间 sleep 0.3s——帧序断言的增量
   * 性依据：agentDone 必然出现在最后一行 stdout 之后 300ms+）。成功路径无
   * Final 帧（codex 实况：turn.completed 只带 usage）——空返回修复的 spec 面。
   */
  private def fakeCodexBin(dir: os.Path): os.Path =
    val bin = dir / "codex"
    os.write(
      bin,
      """#!/bin/sh
        |echo '{"type":"thread.started","thread_id":"th-fixture-1"}'
        |sleep 0.3
        |echo '{"type":"item.started","item":{"type":"reasoning","text":"pondering"}}'
        |echo '{"type":"item.started","item":{"type":"command_execution","command":"grep smoke log.txt"}}'
        |sleep 0.3
        |echo '{"type":"item.completed","item":{"type":"command_execution","command":"grep smoke log.txt","aggregated_output":"1 hit"}}'
        |echo '{"type":"item.completed","item":{"type":"agent_message","text":"hello from fake cli"}}'
        |sleep 0.3
        |echo '{"type":"turn.completed","usage":{"input_tokens":5,"output_tokens":7}}'
        |""".stripMargin
    )
    os.perms.set(bin, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    bin

  test("端到端：假 codex 流过真 runner — 帧序/三键盖章/usage 徽标/历史落盘/空返回兜底") {
    val workspace = tmp / "workspace"
    os.makeDir(workspace)
    val binDir = tmp / "bin"
    os.makeDir(binDir)
    val bin = fakeCodexBin(binDir)
    val store = SessionStore(tmp / "sessions", tmp / "tasks")
    val framesRef = Ref.unsafe[IO, Vector[Json]](Vector.empty)
    val sink: Json => IO[Unit] = j => framesRef.update(_ :+ j)

    for
      spawned <- ExternalSessionRunner.spawn(
        ExternalSessionRunner.SpawnSpec(
          executorId = "codex",
          binPath = bin.toString,
          task = "smoke task",
          title = "smoke title",
          workspace = workspace.toString,
          project = Some("proj-x"),
          taskId = Some("42"),
          parentSessionId = "sess-parent",
          rootSessionId = "sess-root",
          parentRef = None,
          wsSend = Some(sink),
          resources = makeResources(store)
        )
      )
      pair <- spawned match
        case Right(p) => IO.pure(p)
        case Left(err) => IO.raiseError(fail(s"spawn failed: $err"))
      (sessionId, _) = pair
      // 等 agentDone（进程退出 + settle 完成）；最长 30s。
      _ <- IO.defer(framesRef.get)
        .iterateUntil(_.exists(_.hcursor.downField("type").as[String].toOption.contains("agentDone")))
        .timeout(30.seconds)
        .handleErrorWith(_ => IO.raiseError(fail("agentDone frame not observed within 30s")))
      frames <- framesRef.get
      types = frames.map(_.hcursor.downField("type").as[String].toOption.getOrElse("?")).toList
      // ── 帧序（增量性）：agentStart < agentThinking < agentToolStart <
      //    agentToolEnd < agentTextDelta < agentDone ──
      startIdx = types.indexOf("agentStart")
      thinkIdx = types.indexOf("agentThinking")
      toolStartIdx = types.indexOf("agentToolStart")
      toolEndIdx = types.indexOf("agentToolEnd")
      textIdx = types.indexOf("agentTextDelta")
      doneIdx = types.indexOf("agentDone")
      _ = assertEquals(startIdx, 0)
      _ = assert(
        startIdx < thinkIdx && thinkIdx < toolStartIdx && toolStartIdx < toolEndIdx && toolEndIdx < textIdx && textIdx < doneIdx,
        s"frame order violated: $types"
      )
      // ── 三键盖章 + 同契约键 ──
      start = frames(startIdx)
      _ = assertEquals(start.hcursor.downField("agentType").as[String].toOption, Some("external"))
      _ = assertEquals(start.hcursor.downField("rootSessionId").as[String].toOption, Some("sess-root"))
      _ = assertEquals(start.hcursor.downField("sessionId").as[String].toOption, Some("sess-parent"))
      _ = assertEquals(start.hcursor.downField("nodeSessionId").as[String].toOption, Some(sessionId))
      _ = assertEquals(start.hcursor.downField("project").as[String].toOption, Some("proj-x"))
      _ = assertEquals(start.hcursor.downField("taskId").as[String].toOption, Some("42"))
      _ = assertEquals(start.hcursor.downField("taskTitle").as[String].toOption, Some("smoke title"))
      // ── 内容帧 ──
      _ = assert(frames.exists(j =>
        j.hcursor.downField("label").as[String].toOption.contains("grep") &&
          j.hcursor.downField("type").as[String].toOption.contains("agentToolStart")
      ))
      _ = assert(frames.exists(j =>
        j.hcursor.downField("label").as[String].toOption.contains("grep") &&
          j.hcursor.downField("type").as[String].toOption.contains("agentToolEnd")
      ))
      _ = assert(frames.exists(j =>
        j.hcursor.downField("delta").as[String].toOption.contains("hello from fake cli") &&
          j.hcursor.downField("type").as[String].toOption.contains("agentTextDelta")
      ))
      // ── usage 徽标进 agentDone ──
      done = frames(doneIdx)
      _ = assertEquals(done.hcursor.downField("inputTokens").as[Int].toOption, Some(5))
      _ = assertEquals(done.hcursor.downField("outputTokens").as[Int].toOption, Some(7))
      // ── 历史落盘（同一 SessionStore；空返回兜底 = 终稿退 agent_message 文本）──
      msgs <- store.loadMessagesForSession(sessionId)
      rolesTexts = msgs.map(m => (m.role, m.textContent))
      _ = assertEquals(rolesTexts.count(_._1 == MessageRole.User), 1, rolesTexts.toString)
      assistant = rolesTexts.filter(_._1 == MessageRole.Assistant)
      _ = assertEquals(assistant.length, 1, rolesTexts.toString)
      _ = assertEquals(assistant.head._2, "hello from fake cli")
      // ── DelegateRegistry 记录 + CLI 会话锚点 ──
      rec = delegate.DelegateRegistry.find(sessionId)
      _ = assert(rec.isDefined, "delegate record must exist")
      _ = assertEquals(rec.flatMap(_.cliSessionId), Some("th-fixture-1"))
      _ = assertEquals(rec.map(_.executor), Some("codex"))
    yield ()
  }
end ExternalSessionRunnerStreamSpec
