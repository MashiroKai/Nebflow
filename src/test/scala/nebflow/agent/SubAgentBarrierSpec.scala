package nebflow.agent

import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.ToolExecResult
import nebflow.shared.ToolCall
import munit.FunSuite

/**
 * Sub-agent result barrier (2026-08-18, worker blocking semantics): while a
 * parallel Delegate/SubTask batch is outstanding, its results are held and
 * delivered ALL together instead of interrupting the agent one at a time.
 *
 * These tests validate the pure drain decision (TurnBoundaryDrains.drainBarrier)
 * that all three drain sites (ToolsComplete / finishTurnCont / CompactionComplete)
 * delegate to.
 */
class SubAgentBarrierSpec extends FunSuite:

  private def subtask(n: Int): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent("subtask", "completed", s"subtask-result-$n")

  private def delegate(n: Int): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent("delegate", "completed", s"delegate-result-$n")

  private def other(n: Int): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent("background-task", "completed", s"bg-result-$n")

  test("isSubagentResult matches subtask and delegate sources only") {
    assert(TurnBoundaryDrains.isSubagentResult(subtask(1)))
    assert(TurnBoundaryDrains.isSubagentResult(delegate(1)))
    assert(!TurnBoundaryDrains.isSubagentResult(other(1)))
    assert(!TurnBoundaryDrains.isSubagentResult(AgentCommand.ExternalEvent("mail-ask", "completed", "x")))
    assert(!TurnBoundaryDrains.isSubagentResult(AgentCommand.ExternalEvent("inject", "inject", "x")))
  }

  test("while the batch is outstanding, all subtask/delegate results are held") {
    val queue = List(subtask(1), delegate(2), subtask(3))
    val (drained, remaining) = TurnBoundaryDrains.drainBarrier(queue, compactionPending = false, outstanding = 3)
    assert(drained.isEmpty, "no result may interrupt the agent while the batch is running")
    assertEquals(remaining, queue, "held results must stay queued untouched")
  }

  test("while the batch is outstanding, a non-subagent event may still pass") {
    val queue = List(subtask(1), other(2), subtask(3))
    val (drained, remaining) = TurnBoundaryDrains.drainBarrier(queue, compactionPending = false, outstanding = 2)
    assertEquals(drained.map(_.payload), List("bg-result-2"))
    assertEquals(remaining.map(_.payload), List("subtask-result-1", "subtask-result-3"))
  }

  test("batch complete: ALL held subtask/delegate results drain together") {
    val queue = List(subtask(1), delegate(2), subtask(3))
    val (drained, remaining) = TurnBoundaryDrains.drainBarrier(queue, compactionPending = false, outstanding = 0)
    assertEquals(drained.map(_.payload), List("subtask-result-1", "delegate-result-2", "subtask-result-3"))
    assert(remaining.isEmpty)
  }

  test("batch complete: non-subagent events keep the one-at-a-time drain") {
    val queue = List(subtask(1), other(2), other(3))
    val (drained, remaining) = TurnBoundaryDrains.drainBarrier(queue, compactionPending = false, outstanding = 0)
    assertEquals(drained.map(_.payload), List("subtask-result-1", "bg-result-2"))
    assertEquals(remaining.map(_.payload), List("bg-result-3"))
  }

  test("single non-subagent event without a batch is drained as before") {
    val queue = List(other(1))
    val (drained, remaining) = TurnBoundaryDrains.drainBarrier(queue, compactionPending = false, outstanding = 0)
    assertEquals(drained.map(_.payload), List("bg-result-1"))
    assert(remaining.isEmpty)
  }

  test("compaction guard holds everything, barrier or not") {
    val queue = List(subtask(1), subtask(2))
    val (drained, remaining) = TurnBoundaryDrains.drainBarrier(queue, compactionPending = true, outstanding = 0)
    assert(drained.isEmpty)
    assertEquals(remaining, queue)
  }

  test("empty queue is a no-op") {
    assertEquals(TurnBoundaryDrains.drainBarrier(Nil, compactionPending = false, outstanding = 3), (Nil, Nil))
    assertEquals(TurnBoundaryDrains.drainBarrier(Nil, compactionPending = false, outstanding = 0), (Nil, Nil))
  }

  // ── Barrier count: persistent Delegate exclusion (qa-backend 2026-08-19) ──

  private def call(name: String, lifecycle: Option[String] = None): ToolCall =
    val input = lifecycle.map(l => JsonObject("lifecycle" -> l.asJson)).getOrElse(JsonObject.empty)
    ToolCall("id-" + name + lifecycle.getOrElse(""), name, input)

  private val ok = ToolExecResult("ok", isError = false)
  private val err = ToolExecResult("err", isError = true)

  test("countBarrierIncrements: ephemeral Delegate counts") {
    assertEquals(TurnBoundaryDrains.countBarrierIncrements(List(call("Delegate") -> ok)), 1)
  }

  test("countBarrierIncrements: persistent Delegate does NOT count") {
    assertEquals(TurnBoundaryDrains.countBarrierIncrements(List(call("Delegate", Some("persistent")) -> ok)), 0)
  }

  test("countBarrierIncrements: SubTask always counts") {
    assertEquals(TurnBoundaryDrains.countBarrierIncrements(List(call("SubTask") -> ok)), 1)
    assertEquals(TurnBoundaryDrains.countBarrierIncrements(List(call("SubTask", Some("persistent")) -> ok)), 1)
  }

  test("countBarrierIncrements: failed spawns never count") {
    assertEquals(TurnBoundaryDrains.countBarrierIncrements(List(call("Delegate") -> err)), 0)
    assertEquals(TurnBoundaryDrains.countBarrierIncrements(List(call("SubTask") -> err)), 0)
    assertEquals(TurnBoundaryDrains.countBarrierIncrements(List(call("Delegate", Some("persistent")) -> err)), 0)
  }

  test("countBarrierIncrements: mixed batch counts only ephemeral successes") {
    val batch = List(
      call("Delegate") -> ok,                          // 1 (ephemeral)
      call("Delegate", Some("persistent")) -> ok,      // 0 (persistent excluded)
      call("SubTask") -> ok,                           // 1
      call("Delegate") -> err,                         // 0 (failed)
      call("Read") -> ok                               // 0 (not a sub-agent)
    )
    assertEquals(TurnBoundaryDrains.countBarrierIncrements(batch), 2)
  }

end SubAgentBarrierSpec
