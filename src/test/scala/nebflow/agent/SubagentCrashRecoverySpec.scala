package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord, AgentStatus, ActorSystem, Behavior, Behaviors}
import nebflow.shared.{ContentBlock, Message, MessageRole, SubAgentTask}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * 子代理等待三缺口批（2026-10-25）缺口② 验收 spec：**checkpoint 恢复的在飞
 * 工具调用终结面**（[[SubagentCrashRecovery]]）。
 *
 * 990bd848 卡死链实证：delegate 在 Subagent 工具相位被 Stop → supervisor
 * respawn → 断点里无 result 的 tool_use 原样进恢复会话（自毒根因：模型只能
 * 盲目重发同一调用）+ registry 幽灵工具相位让 watcher 掐杀恢复轮 → 重启配额
 * 烧尽。本批三件处置的验收面：
 *  - transcript 面（纯函数）：悬挂 tool_use 补 `recovered-after-crash` 错误
 *    result；完整配对面恒等（零漂移）；多悬挂合并一条 user 消息。
 *  - 子会话面：崩溃会话名下仍 running 的子任务 = 孤儿 → Stop + 结算
 *    （registry 移除 + task 终态化 cancelled）。
 *  - registry 面（纯函数）：恢复记录清幽灵工具相位；干净记录恒等。
 */
class SubagentCrashRecoverySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-subagent-crash-recovery"
  private val originalRoot = nebflow.shared.PathUtil.dataRoot

  override def beforeAll(): Unit =
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "agents")
    os.write.over(tempRoot / "nebflow.json", "{}")
    nebflow.shared.PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    nebflow.shared.PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  // ── ① transcript 面：悬挂 tool_use 补 recovered-after-crash ────────────────

  test("缺口②: 恢复点含在飞 Subagent 调用（无 result）⇒ 恢复即以 recovered-after-crash 失败返回"):
    val toolUse = ContentBlock.ToolUse("call-1", "Subagent", io.circe.JsonObject.empty)
    val transcript = List(
      Message(MessageRole.User, Left("verify the facts")),
      Message(MessageRole.Assistant, Right(List(toolUse))) // 断点：调用在飞、无 result
    )
    val (repaired, terminated) = SubagentCrashRecovery.terminateDanglingToolCalls(transcript)
    assertEquals(terminated, List(("Subagent", "call-1")), "the dangling Subagent call must be reported terminated")
    assertEquals(repaired.size, transcript.size + 1, "exactly one synthetic result message is appended")
    val synthetic = repaired.last
    assertEquals(synthetic.role, MessageRole.User, "tool results travel in a user message (batch settlement shape)")
    assertEquals(synthetic.source, Some("crash-recovery"), "the synthetic message must carry its injection source")
    synthetic.content match
      case Right(blocks) =>
        assertEquals(blocks.collect { case r: ContentBlock.ToolResult => r }.size, 1, "one result per dangling call")
        blocks.collectFirst { case r: ContentBlock.ToolResult => r } match
          case Some(r) =>
            assertEquals(r.toolUseId, "call-1", "the result must pair the dangling tool_use id")
            assertEquals(r.isError, Some(true), "the recovery disposal is an explicit FAILURE return")
            assert(
              r.content.contains(SubagentCrashRecovery.RecoveredAfterCrashToken),
              s"failure text must carry the token '${SubagentCrashRecovery.RecoveredAfterCrashToken}': ${r.content}"
            )
            assert(r.content.contains("Subagent"), s"failure text must name the tool: ${r.content}")
          case None => fail("synthetic tool_result missing")
      case _ => fail("synthetic message must carry content blocks")
    // 顺序不变式：synthetic result 紧随断点 assistant 消息（provider 配对要求）
    assertEquals(repaired(repaired.size - 2), transcript.last, "the synthetic result must follow the dangling assistant message")

  test("缺口②: 完整配对的批次恒等（零漂移——正常 checkpoint 一字不动）"):
    val transcript = List(
      Message(MessageRole.User, Left("do things")),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.ToolUse("call-2", "Bash", io.circe.JsonObject.empty)))
      ),
      Message(
        MessageRole.User,
        Right(List(ContentBlock.ToolResult("call-2", "ok", Some(false))))
      )
    )
    val (repaired, terminated) = SubagentCrashRecovery.terminateDanglingToolCalls(transcript)
    assertEquals(repaired, transcript, "a fully-paired transcript must pass through untouched")
    assertEquals(terminated, Nil, "no dangling calls ⇒ nothing terminated")

  test("缺口②: 空输入恒等 / 多悬挂合并一条 user 消息（provider 配对形态）"):
    assertEquals(SubagentCrashRecovery.terminateDanglingToolCalls(Nil), (Nil, Nil), "empty checkpoint stays empty")
    val dangling =
      List(ContentBlock.ToolUse("d1", "Subagent", io.circe.JsonObject.empty), ContentBlock.ToolUse("d2", "Read", io.circe.JsonObject.empty))
    val (repaired, terminated) = SubagentCrashRecovery.terminateDanglingToolCalls(
      List(Message(MessageRole.Assistant, Right(dangling)))
    )
    assertEquals(terminated.map(_._1).sorted, List("Read", "Subagent"), "every dangling call is terminated")
    assertEquals(repaired.size, 2, "one assistant + ONE synthetic user message")
    repaired.last.content match
      case Right(blocks) =>
        assertEquals(blocks.collect { case r: ContentBlock.ToolResult => r }.map(_.toolUseId).sorted, List("d1", "d2"),
          "both results land in the same user message (valid Anthropic pairing shape)")
      case _ => fail("synthetic message must carry content blocks")

  // ── ② 子会话面：孤儿子会话处置 ─────────────────────────────────────────────

  private class RecordingActor(record: cats.effect.Ref[IO, List[AgentCommand]]):
    def behavior: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(behavior))

  test("缺口②: 崩溃会话名下仍 running 的子任务 = 孤儿 ⇒ Stop + registry 移除 + task 终态化"):
    val system = ActorSystem("scr-orphan")
    val captured = TrieMap[String, String]()
    val fakeLlm = new nebflow.shared.LlmHandle[IO]:
      def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
        IO.raiseError(new RuntimeException("fake llm not expected here"))
      def sendStream(
        req: nebflow.shared.LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, nebflow.shared.StreamChunk] =
        fs2.Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))
    (for
      res <- SpecResources.mkResources(system, tempRoot, fakeLlm)
      received <- IO.ref[List[AgentCommand]](Nil)
      childRef <- system.spawn(RecordingActor(received).behavior, "scr-orphan-child")
      _ <- res.subAgentTaskStore.recordTask(
        SubAgentTask(
          taskId = "subagent-orphan1",
          parentSessionId = "delegate-crashed",
          agentName = "subagent",
          prompt = "verify facts",
          description = "recon",
          status = "running",
          retryCount = 0,
          spawnedAt = System.currentTimeMillis(),
          completedAt = None,
          lastError = None,
          source = "subagent"
        )
      )
      // 干净对照：已终态的兄弟行不得被处置
      _ <- res.subAgentTaskStore.recordTask(
        SubAgentTask(
          taskId = "subagent-done1",
          parentSessionId = "delegate-crashed",
          agentName = "subagent",
          prompt = "done already",
          description = "recon",
          status = "completed",
          retryCount = 0,
          spawnedAt = System.currentTimeMillis(),
          completedAt = Some(System.currentTimeMillis()),
          lastError = None,
          source = "subagent"
        )
      )
      _ <- res.agentRegistry.update(_ + ("subagent-orphan1" -> AgentRecord(
        sessionId = "subagent-orphan1",
        ref = childRef,
        kind = AgentKind.SubTask,
        rootSessionId = "delegate-crashed",
        parentSessionId = "delegate-crashed"
      )))
      disposed <- SubagentCrashRecovery.disposeOrphanChildren(res, "delegate-crashed")
      cmds <- received.get
      tasks <- res.subAgentTaskStore.loadTasks("delegate-crashed")
      registry <- res.agentRegistry.get
    yield
      assertEquals(disposed, List("subagent-orphan1"), "only the running child is an orphan")
      assert(cmds.exists { case AgentCommand.Stop(reason) => reason.contains("orphaned"); case _ => false },
        s"the orphan child must receive a Stop, got $cmds")
      assert(!registry.contains("subagent-orphan1"), "the orphan's registry entry must be removed")
      val orphanRow = tasks.find(_.taskId == "subagent-orphan1").getOrElse(fail("orphan row must exist"))
      assertEquals(orphanRow.status, "cancelled", "the orphan row must be terminalized cancelled")
      assert(orphanRow.completedAt.isDefined, "the orphan row must carry completedAt")
      val doneRow = tasks.find(_.taskId == "subagent-done1").getOrElse(fail("done row must exist"))
      assertEquals(doneRow.status, "completed", "the already-terminal sibling must stay untouched")
    ).guarantee(system.stopAll.handleErrorWith(_ => IO.unit))

  test("缺口②: 无孤儿（无 running 行）= no-op"):
    val system = ActorSystem("scr-noop")
    val fakeLlm = new nebflow.shared.LlmHandle[IO]:
      def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
        IO.raiseError(new RuntimeException("fake llm not expected here"))
      def sendStream(
        req: nebflow.shared.LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, nebflow.shared.StreamChunk] =
        fs2.Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))
    (for
      res <- SpecResources.mkResources(system, tempRoot, fakeLlm)
      disposed <- SubagentCrashRecovery.disposeOrphanChildren(res, "delegate-no-children")
    yield assertEquals(disposed, Nil, "no running rows ⇒ no disposal")
    ).guarantee(system.stopAll.handleErrorWith(_ => IO.unit))

  // ── ③ registry 面：幽灵工具相位清零 ────────────────────────────────────────

  test("缺口②: 恢复记录清幽灵工具相位（watcher 不再追杀恢复轮）+ 干净记录恒等"):
    val now = System.currentTimeMillis()
    val stale = AgentRecord(
      sessionId = "delegate-ghost",
      ref = null,
      kind = AgentKind.Delegate,
      rootSessionId = "root",
      startedAt = now,
      status = AgentStatus.Idle,
      lastActivityMs = now,
      currentToolName = Some("Subagent"),
      currentToolStartedAt = now - 655_000L,
      currentToolDeadlineMs = 0L
    )
    val cleared = SubagentCrashRecovery.staleToolPhaseCleared(stale)
    assertEquals(cleared.currentToolName, None, "ghost tool name must be cleared at the recovery point")
    assertEquals(cleared.currentToolStartedAt, 0L, "ghost tool-phase start must be cleared at the recovery point")
    assertEquals(cleared.currentToolDeadlineMs, 0L, "ghost deadline must be cleared at the recovery point")
    // 非相位字段零漂移（只清相位，不动身份/活动戳）
    assertEquals(cleared.sessionId, stale.sessionId)
    assertEquals(cleared.status, stale.status)
    assertEquals(cleared.lastActivityMs, stale.lastActivityMs)
    // 干净记录恒等（同引用——零开销路径）
    val clean = AgentRecord(sessionId = "delegate-clean", ref = null, kind = AgentKind.Delegate, rootSessionId = "root")
    assertEquals(SubagentCrashRecovery.staleToolPhaseCleared(clean), clean, "a clean record passes through unchanged")

end SubagentCrashRecoverySpec
