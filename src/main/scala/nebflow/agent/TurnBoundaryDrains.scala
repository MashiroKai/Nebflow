package nebflow.agent

import nebflow.actor.AgentCommand

/**
 * Pure turn-boundary drain decisions shared by ToolsComplete / CompactionComplete.
 * Top-level and side-effect-free so specs can verify the compaction guard
 * directly (queued-inputs-during-compaction bug, 2026-08-14).
 */
object TurnBoundaryDrains:

  /**
   * Drain at most the head of a queue at a turn boundary (serial processing,
   * remainder stays queued). While a compaction job is pending the queue must
   * NOT be consumed: anything injected into the save/compact turn's history is
   * discarded when the summary replaces it — consumed from the queue yet lost.
   * CompactionComplete drains the queues after the summary is in place.
   */
  def drainHead[A](queue: List[A], compactionPending: Boolean): (Option[A], List[A]) =
    if compactionPending then (None, queue)
    else (queue.headOption, if queue.isEmpty then queue else queue.tail)

  // ── 2026-09-15 用户消息队列 burst 缺陷批（root 裁定）────────────────────────
  // 裁定逐字：**排队消息按序逐条注入、每条独立成 turn，保序、禁合并语义、禁全量
  // burst；错误恢复路径与正常路径同规。**
  // 本 object 原有的两个合批消费器（`drainAll` = 整队、`drainUserBatch` = 可内联
  // 件整批，缺陷⑥ 2026-09-07 设计 §8）已删除：合批把 N 件排队消息塞进**一次**请求，
  // 用户侧观测即「一报错，队列里的消息一次全发过来」（作者 2026-09-15 12:59 报告，
  // 取证：Nebula-5cc7590a 12:56:49 `batch=11` + `batch=2` 同一次 tools-complete
  // 续轮）。唯一合法形态 = [[drainHead]]（一次边界只消费队首一件，其余留队，到达
  // 顺序不变）；token 放大（N 件 = N 次全上下文往返）是裁定显式接受的代价。

  /** True for ExternalEvents carrying a Delegate/SubTask result. */
  def isSubagentResult(e: AgentCommand.ExternalEvent): Boolean =
    e.source == "subtask" || e.source == "delegate"

  /**
   * Count how many sub-agent barrier slots a batch of tool results should add.
   * Only successful ephemeral Delegates and all SubTasks count — persistent
   * Delegates send their completion with source=address (not "delegate"), so
   * isSubagentResult never matches and the counter would never decrement.
   * (qa-backend 2026-08-19: phantom barrier fix)
   */
  def countBarrierIncrements(results: List[(nebflow.shared.ToolCall, nebflow.shared.ToolExecResult)]): Int =
    results.count { (call, r) =>
      !r.isError && (
        call.name == "SubTask" ||
          (call.name == "Delegate" && call.input("lifecycle").flatMap(_.asString).forall(_ != "persistent"))
      )
    }

  /**
   * Barrier-aware drain (2026-08-18, worker blocking semantics): while a
   * parallel sub-agent batch is outstanding (outstanding > 0), subtask/delegate
   * result events are HELD in the queue — the agent is not interrupted one
   * result at a time. The first non-subagent event (if any) may still pass,
   * so mail/background-task keep their existing serial drain. When the batch
   * is complete (outstanding == 0), ALL held subtask/delegate results are
   * drained together for one batched injection; other event types keep the
   * existing one-at-a-time drain. Compaction guard applies as in [[drainHead]].
   */
  def drainBarrier(
    queue: List[AgentCommand.ExternalEvent],
    compactionPending: Boolean,
    outstanding: Int
  ): (List[AgentCommand.ExternalEvent], List[AgentCommand.ExternalEvent]) =
    if compactionPending then (Nil, queue)
    else if outstanding > 0 then
      queue.indexWhere(e => !isSubagentResult(e)) match
        case -1 => (Nil, queue) // everything held — wait for the batch
        case i => (List(queue(i)), queue.patch(i, Nil, 1))
    else
      val (subtasks, others) = queue.partition(isSubagentResult)
      (subtasks ::: others.take(1), others.drop(1))

end TurnBoundaryDrains
