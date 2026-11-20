package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.Json
import nebflow.actor.*
import nebflow.pilogic.*
import nebflow.shared.{StreamChunk, ToolCall, ToolExecResult}
import munit.CatsEffectSuite

import scala.collection.mutable

/**
 * pi-logic 接主链轮 A 接线测试：十处接线点逐点 ≥1 测 + 接线期四件（①在
 * SiteEventsSpec；②③④在此件）。全部纯值/捕获断言——零 actor 依赖、零 db 触达
 * （shadow 以捕获 lambda 注入）。
 */
class PiLoopWiringSpec extends CatsEffectSuite:

  // ── 捕获面（audit 行 + KernelEvent 影子）──────────────────────────────────

  final case class Captured(
    rows: mutable.ListBuffer[(String, String)] = mutable.ListBuffer(),
    events: mutable.ListBuffer[KernelEvent] = mutable.ListBuffer()
  ):
    def audit: (String, String) => Unit = (ev, d) => rows += ((ev, d))
    def shadow: KernelEvent => Unit = e => events += e

    def bridge(sessionId: String = "s-1", runId: String, startTurn: Long): PiLoopWiring.PiBridge =
      PiLoopWiring.PiBridge(audit, shadow, sessionId, runId, startTurn)

  // ── 接线期四件 ③（L-8）+ 接线点 1（run 边界）──────────────────────────────

  test("③ run 边界：新 loopTurnKey ⇒ fresh 开 run；同 key 且 run 在飞 ⇒ 续；run 已收口 ⇒ fresh"):
    val b1 = PiLoopWiring.runBoundary(RunLedger.empty, loopTurnKey = 5L, startTurn = 1L)
    assertEquals(b1.fresh, true)
    assertEquals(b1.ledger.runKey, 5L)
    assertEquals(b1.ledger.seqInSession, 0L) // 开 run 不消费序——KernelEvent 行才消费
    val b2 = PiLoopWiring.runBoundary(b1.ledger, loopTurnKey = 5L, startTurn = 9L)
    assertEquals(b2.fresh, false) // 同 run 续轮（ToolsComplete 续跑/压缩续跑）
    val closed = b1.ledger.closed
    val b3 = PiLoopWiring.runBoundary(closed, loopTurnKey = 5L, startTurn = 9L)
    assertEquals(b3.fresh, true) // run 已收口（RunEnded 已发）⇒ 同 key 也开新 run

  test("③ seqInSession 会话域跨 run 连续 + MessageSeq 每 run 重置（生产者/消费者两侧映射）"):
    val cap = Captured()
    // run 1（startTurn=1）：runStart 消费 seq=1；runEnd 消费 seq=2 并关 run
    val led1 = cap.bridge(runId = "r-a", startTurn = 1L).runStart(RunLedger.start(1L, "r-a", RunLedger.empty, 1L))
    assertEquals(led1.seqInSession, 1L)
    val led1e = cap.bridge(runId = "r-a", startTurn = 1L).runEnd("completed", led1)
    assertEquals(led1e.seqInSession, 2L)
    assertEquals(led1e.runId, "")
    // run 2（同会话）：seq 继承续号（3）——L-8 消费侧连续
    val led2 = RunLedger.start(2L, "r-b", led1e, 9L)
    val led2s = cap.bridge(runId = "r-b", startTurn = 9L).runStart(led2)
    assertEquals(led2s.seqInSession, 3L)
    assertEquals(cap.events.map(_.seqInSession).toVector, Vector(1L, 2L, 3L))
    assertEquals(cap.events.map(_.sessionId).distinct.toVector, Vector("s-1"))
    // 生产者侧：MessageSeq 相对 run 起点、每 run 重置
    assertEquals(cap.bridge(runId = "r-a", startTurn = 5L).messageSeq(5L).value, 1L)
    assertEquals(cap.bridge(runId = "r-a", startTurn = 5L).messageSeq(8L).value, 4L)
    assertEquals(cap.bridge(runId = "r-b", startTurn = 9L).messageSeq(9L).value, 1L)

  // ── 四件 ②（PiBridge audit 面 + ws 翻译）──────────────────────────────────

  test("② audit 面子集：Run/Turn/Message/Tool 起止落行；逐增量（Delta）不落行（防洪）"):
    val cap = Captured()
    val br = cap.bridge(runId = "r-a", startTurn = 1L)
    br.emitSync(LoopEvent.RunStarted)
    br.emitSync(LoopEvent.TurnStarted(TurnNumber(1L)))
    br.emitSync(LoopEvent.MessageStarted(MessageSeq(1L)))
    br.emitSync(LoopEvent.MessageDelta(MessageSeq(1L), Json.Null))
    br.emitSync(LoopEvent.MessageEnded(MessageSeq(1L), LoopMessage(LoopMessageRole.Assistant, Json.fromString("x"))))
    br.emitSync(LoopEvent.ToolExecutionDelta("c1", Json.Null))
    br.emitSync(LoopEvent.ToolExecutionEnded("c1", Json.Null, isError = false))
    val names = cap.rows.map(_._1).toVector
    assertEquals(
      names,
      Vector(
        "pilogic-run-started",
        "pilogic-turn-started",
        "pilogic-message-started",
        "pilogic-message-ended",
        "pilogic-tool-ended"
      )
    )

  test("② ws 翻译（闸门关·形状钉）：delta 族与 StreamBatching 帧逐字节对拍；ToolStart 双形态"):
    val delta = PiLoopWiring.chunkJson("text", "hello")
    assertEquals(
      PiLoopWiring.wsFrame(
        LoopEvent.MessageDelta(MessageSeq(1L), delta),
        isSubagent = false,
        sessionId = Some("s-1"),
        agentPath = "p",
        isAskMode = false
      ),
      Some(
        StreamBatching.textFrame(
          "hello",
          isAskMode = false,
          isSubagent = false,
          sessionId = Some("s-1"),
          agentPath = "p"
        )
      )
    )
    val think = PiLoopWiring.chunkJson("thinking", "hm")
    assertEquals(
      PiLoopWiring.wsFrame(
        LoopEvent.MessageDelta(MessageSeq(1L), think),
        isSubagent = false,
        sessionId = Some("s-1"),
        agentPath = "p",
        isAskMode = false
      ),
      Some(StreamBatching.thinkingFrame("hm", isSubagent = false, sessionId = Some("s-1"), agentPath = "p"))
    )
    assertEquals(
      PiLoopWiring.wsFrame(
        LoopEvent.ToolExecutionStarted("c1", "Bash"),
        isSubagent = false,
        sessionId = Some("s-1"),
        agentPath = "p",
        isAskMode = false
      ),
      Some(Json.obj("type" -> "toolStart".asJson, "sessionId" -> "s-1".asJson, "label" -> "Bash".asJson))
    )
    // RunEnded/Done 不产帧（主链 returnToIdle 单点构造——翻译表文档面）
    assertEquals(
      PiLoopWiring.wsFrame(
        LoopEvent.RunEnded(RunOutcome.Completed(Vector.empty)),
        isSubagent = false,
        sessionId = None,
        agentPath = "p",
        isAskMode = false
      ),
      None
    )

  // ── 四件 ④（崩溃兜底）────────────────────────────────────────────────────

  test("④ crashTail：活跃轮在飞 ⇒ 合成 TurnEnded(空)+RunEnded 对；aborted=true ⇒ Aborted 形"):
    val cap = Captured()
    val br = cap.bridge(runId = "r-a", startTurn = 1L)
    PiLoopWiring.crashTailIO(br, activeTurnId = 3L).map { _ =>
      val names = cap.rows.map(_._1).toVector
      assertEquals(names, Vector("pilogic-turn-ended", "pilogic-run-ended"))
      assert(cap.rows.exists(_._2.contains("turn=3")))
    } *> {
      val cap2 = Captured()
      val br2 = cap2.bridge(runId = "r-b", startTurn = 1L)
      PiLoopWiring.crashTailIO(br2, activeTurnId = 2L, aborted = true).map { _ =>
        assert(cap2.rows.exists(_._2.contains("Aborted")))
      }
    }

  test("④ crashTail 无活跃轮（turnId=0）⇒ 仅 RunEnded 单行（与 pilogic 助手契约一致）"):
    val cap = Captured()
    val br = cap.bridge(runId = "r-a", startTurn = 1L)
    PiLoopWiring.crashTailIO(br, activeTurnId = 0L).map { _ =>
      assertEquals(cap.rows.map(_._1).toVector, Vector("pilogic-run-ended"))
    }

  // ── 接线点 2（TurnNumber 序域）────────────────────────────────────────────

  test("点2 TurnNumber 取号与 stale 判定：数值语义与旧 Long 比较逐字等价"):
    assertEquals(PiLoopWiring.nextTurnNumber(4L).value, 5L)
    assertEquals(PiLoopWiring.isStaleTurn(3L, 4L), true)
    assertEquals(PiLoopWiring.isStaleTurn(4L, 4L), false)
    assertEquals(PiLoopWiring.isStaleTurn(5L, 4L), true)

  // ── 接线点 3（RetryPolicy 闸）─────────────────────────────────────────────

  test("点3 重试闸策略位化：新谓词与旧 `>= Fallback.MaxTurnLlmCalls` 全域等价（边界 3/4 两态）"):
    val cap4 = nebflow.llm.Fallback.MaxTurnLlmCalls
    (0 to 6).foreach { n =>
      assertEquals(PiLoopWiring.turnRetryExceeded(n), n >= cap4, s"n=$n")
    }
    assertEquals(PiLoopWiring.turnRetryPolicy.allows(cap4 - 1), true)
    assertEquals(PiLoopWiring.turnRetryPolicy.allows(cap4), false)

  // ── 接线点 4（translator）─────────────────────────────────────────────────

  test("点4 翻译表：LlmComplete⇒MessageEnded+TurnEnded；LlmFailed⇒TurnEnded(Error)；ToolsComplete⇒ToolResult 族"):
    val cr = ConsumeResult(
      text = "hi",
      toolCalls = List(ToolCall("c1", "Bash", io.circe.JsonObject())),
      results = Nil,
      stopReason = None
    )
    val ev1 = PiLoopWiring.PiLoopTranslator.eventsOnLlmComplete(cr, TurnNumber(2L), MessageSeq(2L))
    assertEquals(ev1(0).isInstanceOf[LoopEvent.MessageEnded], true)
    assertEquals(ev1(1).isInstanceOf[LoopEvent.TurnEnded], true)
    val ev2 = PiLoopWiring.PiLoopTranslator.eventsOnLlmFailed(TurnNumber(2L))
    assertEquals(
      ev2.collect { case LoopEvent.TurnEnded(_, _, _, o) => o }.toVector,
      Vector(TurnEndOutcome.Error("llm-failed"))
    )
    val ev3 = PiLoopWiring.PiLoopTranslator.eventsOnToolsComplete(
      List(ToolCall("c1", "Bash", io.circe.JsonObject()) -> ToolExecResult("ok")),
      TurnNumber(2L)
    )
    assertEquals(
      ev3.collectFirst { case LoopEvent.TurnEnded(_, _, rs, _) => rs.headOption.map(_.role) }.flatten,
      Some(LoopMessageRole.ToolResult)
    )

  // ── 接线点 5（六分支分类器）───────────────────────────────────────────────

  private def stateWith(job: Option[CompactionJob] = None, ask: Option[String] = None): AgentState =
    AgentState().withPendingCompaction(job).withAskMode(ask)

  test("点5 六分支分类器：七形态对拍 ladder（compact 三态/ask/工具/文本/空响应）"):
    val compactJob = Some(CompactionJob(subagentId = "compact-x", mode = "full"))
    val textCr = ConsumeResult("summary", Nil, Nil, None)
    val toolCr = ConsumeResult("", List(ToolCall("c1", "Bash", io.circe.JsonObject())), Nil, None)
    val emptyCr = ConsumeResult("", Nil, Nil, None)
    assertEquals(PiLoopWiring.classifyLlmComplete(stateWith(job = compactJob), textCr), "compact-response")
    assertEquals(PiLoopWiring.classifyLlmComplete(stateWith(job = compactJob), emptyCr), "compact-empty")
    assertEquals(PiLoopWiring.classifyLlmComplete(stateWith(job = compactJob), toolCr), "compact-toolcalls")
    assertEquals(PiLoopWiring.classifyLlmComplete(stateWith(ask = Some("ask-1")), textCr), "ask-complete")
    assertEquals(PiLoopWiring.classifyLlmComplete(stateWith(), toolCr), "tool-batch")
    assertEquals(PiLoopWiring.classifyLlmComplete(stateWith(), textCr), "text-finish")
    assertEquals(PiLoopWiring.classifyLlmComplete(stateWith(), emptyCr), "empty-response")
    // thinking-only 响应 = text-finish（ladder 同判：text 非空 || thinking 非空）
    assertEquals(PiLoopWiring.classifyLlmComplete(stateWith(), emptyCr.copy(thinking = Some("t"))), "text-finish")

  // ── 接线点 6/7（批收束/迟到留痕）──────────────────────────────────────────

  test("点6 批收束行：toolsComplete 记 results/errors 计数"):
    val cap = Captured()
    cap.bridge(runId = "r-a", startTurn = 1L).toolsComplete(results = 3, errors = 1)
    assertEquals(cap.rows.map(_._1).toVector, Vector("pilogic-tool-batch-settled"))
    assert(cap.rows.head._2.contains("results=3 errors=1"))

  test("点7 迟到留痕行：lateDiscarded 记 where+detail（idle/frozen 吞分支可观测性）"):
    val cap = Captured()
    cap.bridge(runId = "r-a", startTurn = 1L).lateDiscarded("idle", "llm-complete turnId=3 current=4")
    assertEquals(cap.rows.map(_._1).toVector, Vector("pilogic-late-event-discarded"))
    assert(cap.rows.head._2.contains("where=idle"))

  // ── 接线点 8（hook 映射）──────────────────────────────────────────────────

  test("点8 映射表全八条：HookEvent 值域全覆盖（缺一即红）+ 对位点名"):
    val map = PiLoopWiring.hookMapping.toMap
    nebflow.core.hooks.HookEvent.values.foreach { he =>
      assert(map.contains(he), s"HookEvent.$he 未映射")
    }
    assertEquals(map(nebflow.core.hooks.HookEvent.PreToolUse), HookPoint.ToolPre)
    assertEquals(map(nebflow.core.hooks.HookEvent.PostToolUse), HookPoint.ToolPost)
    assertEquals(map(nebflow.core.hooks.HookEvent.PostToolUseFailure), HookPoint.ToolPost)
    assertEquals(map(nebflow.core.hooks.HookEvent.PreCompact), Reserved.Compact.BeforeCompact)
    assertEquals(map(nebflow.core.hooks.HookEvent.PostCompact), HookPoint.CompactEnded)
    assertEquals(map(nebflow.core.hooks.HookEvent.SessionStart), HookPoint.SessionStarted)
    assertEquals(map(nebflow.core.hooks.HookEvent.SessionEnd), HookPoint.SessionEnding)
    assertEquals(map(nebflow.core.hooks.HookEvent.Stop), HookPoint.PostRunSettled)

  test("点8 桥接适配器：Block→HookEffect.Block；改参→Modify；无决定→Proceed（割接轮注册面）"):
    val adapter = new PiLoopWiring.PiHookAdapter((name, args) =>
      IO.pure(
        Some(
          (
            name == "Danger",
            if name == "Danger" then "denied" else null,
            if name == "Rewrite" then Some(args) else None
          )
        )
      )
    )
    adapter.toolPreEffect("Danger", Json.obj("x" -> 1.asJson)).map {
      case HookEffect.Block(reason, _) => assertEquals(reason, "denied")
      case e => fail(s"unexpected: $e")
    } *> adapter.toolPreEffect("Rewrite", Json.obj("x" -> 1.asJson)).map {
      case HookEffect.Modify(v) => assertEquals(v.hcursor.get[Int]("x").toOption, Some(1))
      case e => fail(s"unexpected: $e")
    } *> adapter.toolPreEffect("Plain", Json.obj()).map {
      case HookEffect.Proceed => ()
      case e => fail(s"unexpected: $e")
    }

  // ── 接线点 9（ToolExecutor 适配器）────────────────────────────────────────

  test("点9 执行器适配：pilogic 调用面→既有 executeTool 全链闭包；结果取模型面投影"):
    val seen = mutable.ListBuffer[ToolCall]()
    val exec = PiLoopWiring.PiToolExecutor(call => IO { seen += call; ToolExecResult("done-1", isError = false) })
    exec
      .execute(
        nebflow.pilogic.ToolCall("c9", "Read", Json.obj("path" -> "a.md".asJson)),
        new AbortSignal[IO]:
          def aborted: IO[Boolean] = IO.pure(false)
        ,
        _ => IO.unit
      )
      .map { r =>
        assertEquals(seen.headOption.map(_.name), Some("Read"))
        assertEquals(seen.headOption.flatMap(_.input("path").flatMap(_.asString)), Some("a.md"))
        assertEquals(r.output.asString, Some("done-1"))
        assertEquals(r.isError, false)
      }

  // ── 接线点 10（ProviderPort 适配器）───────────────────────────────────────

  private val noAbort: AbortSignal[IO] = new AbortSignal[IO]:
    def aborted: IO[Boolean] = IO.pure(false)

  test("点10 provider 适配：StreamChunk→ProviderChunk（text/thinking）+ 终响应/工具调用/stopReason 映射"):
    val chunks = List(
      StreamChunk.TextDelta("hello "),
      StreamChunk.TextDelta("world"),
      StreamChunk.ThinkingDelta("hm"),
      StreamChunk.ToolCallChunk(ToolCall("c1", "Bash", io.circe.JsonObject("command" -> "ls".asJson))),
      StreamChunk.Done(Some("tool_use"), None)
    )
    val port = new PiLoopWiring.PiProviderPort(
      () => fs2.Stream.emits(chunks),
      _ =>
        ConsumeResult(
          "hello world",
          toolCalls = List(ToolCall("c1", "Bash", io.circe.JsonObject("command" -> "ls".asJson))),
          results = Nil,
          stopReason = Some("tool_use")
        )
    )
    port.stream(ProviderRequest("m", None, Vector.empty, Json.obj(), Json.obj()), noAbort).map { ps =>
      assertEquals(ps.chunks.size, 3)
      assertEquals(
        ps.chunks
          .map(c => (c.delta.hcursor.get[String]("kind").toOption, c.delta.hcursor.get[String]("delta").toOption))
          .toVector,
        Vector((Some("text"), Some("hello ")), (Some("text"), Some("world")), (Some("thinking"), Some("hm")))
      )
      assertEquals(ps.response.stopReason, StopReason.ToolUse)
      assertEquals(ps.response.toolCalls.map(_.name).toVector, Vector("Bash"))
    }

  test("点10 stopReason 映射表：adapter 实测值域 + 未识别保守档"):
    assertEquals(PiLoopWiring.mapStopReason(Some("tool_use"), hasToolCalls = false), StopReason.ToolUse)
    assertEquals(PiLoopWiring.mapStopReason(None, hasToolCalls = true), StopReason.ToolUse)
    assertEquals(PiLoopWiring.mapStopReason(Some("stop"), hasToolCalls = false), StopReason.Stop)
    assertEquals(PiLoopWiring.mapStopReason(Some("end_turn"), hasToolCalls = false), StopReason.Stop)
    assertEquals(PiLoopWiring.mapStopReason(None, hasToolCalls = false), StopReason.Stop)
    assertEquals(PiLoopWiring.mapStopReason(Some("length"), hasToolCalls = false), StopReason.Length)
    assertEquals(PiLoopWiring.mapStopReason(Some("error"), hasToolCalls = false), StopReason.Error)
    assertEquals(PiLoopWiring.mapStopReason(Some("aborted"), hasToolCalls = false), StopReason.Aborted)
    assertEquals(PiLoopWiring.mapStopReason(Some("whatever"), hasToolCalls = false), StopReason.Stop)

  // ── 依赖方向机械自查（作者 20:06 ③：收尾报告数据源）───────────────────────

  test("依赖方向：接线层引用 pilogic 叶子（AgentState 账本字段在位）且账本缺省=empty"):
    val s = AgentState()
    assertEquals(s.pilogicLedger, RunLedger.empty)
    assertEquals(s.copy(pilogicLedger = RunLedger.start(1L, "r-x", RunLedger.empty, 1L)).pilogicLedger.runId, "r-x")
end PiLoopWiringSpec
