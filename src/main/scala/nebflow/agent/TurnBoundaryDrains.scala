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
  //
  // == 2026-09-23 收窄（作者裁定 A「改消费侧」；notifypack 解 b 批）==
  // 上述「禁合并语义」的**规范面**收窄为可机械判定的谓词：**队列层恒「一次边界只消费
  // 队首一个元素」**（[[drainHead]] 语义不变）；**唯一豁免 = 该元素自带
  // `ImmediateInput.windowItems`（root 通道通知打包窗冲刷件，唯一写入点 =
  // `NodeEngine.flushRootNotify` 的 `case many`）**——其**载荷**在同一边界展开为 N 个
  // 虚拟件、于**同一 turn** 内逐件注入（N 气泡 / 1 次唤醒，[[drainHeadExpanded]]）。
  // (W1 provisional shim: the two members below are ported verbatim from main's
  // TurnBoundaryDrains object (main keeps them inside AgentActor.scala; the PR
  // tree hosts this standalone object) so the notifypack window-expansion
  // feature and its main-side specs survive the merge.)

  /** 窗冲刷载荷的展开（notifypack 解 b 批 · 载体 A-ii）。
    *
    * 队列语义不变：本函数**不改** [[drainHead]]——它只把元素**内部**携带的
    * N 件载荷展开为 N 个虚拟件，供同一 turn 内逐件注入（N 气泡 / 1 次唤醒）。
    * 判据 = 元素自带 [[AgentCommand.ImmediateInput.windowItems]]（唯一写入点 =
    * `NodeEngine.flushRootNotify` 的 `case many`）⇒ 作用域非模糊判断，是可机械
    * 验定的谓词。缺席（用户消息腿 / Mail / deviceMail / flow / askUser 等）⇒
    * **恒等返回**（1→1），逐条逐 turn 语义逐字不变。
    *
    * 🔴 本函数 = 谓词与幂等性的**唯一定义处**。幂等：展开出的虚拟件
    * `windowItems = None` ⇒ 不可二次展开（防递归放大）。逐件 `sender` 来自
    * **写入点**（该处 `projectName` 在作用域内）⇒ 此处零字符串手术。 */
  def expandWindowFlush(imm: AgentCommand.ImmediateInput): List[AgentCommand.ImmediateInput] =
    imm.windowItems match
      case Some(items) if items.nonEmpty =>
        items.map(w =>
          imm.copy(
            text = w.text,
            eventType = Some(w.status),
            sender = Some(w.sender),
            windowItems = None
          )
        )
      case _ => List(imm)

  /** [[drainHead]] 的载荷展开包装：队列层语义 = `drainHead`（逐字不动），
    * 返回值在**队首恰有窗冲刷载荷**时展开为 N 个虚拟件。空队 / compaction 挂起
    * （`drainHead` 返回 `None`）⇒ `Nil`，与改前逐字同形。
    *
    * `A => List[A]` 形参使本函数**对元素类型无假设**（本 object 现为纯决策层、零业务
    * 依赖）；root 通知语义只经 `expand = windowItems 判据` 注入。 */
  def drainHeadExpanded[A](queue: List[A], compactionPending: Boolean)(
      expand: A => List[A]
  ): (List[A], List[A]) =
    drainHead(queue, compactionPending) match
      case (Some(head), tail) => (expand(head), tail)
      case (None, tail)       => (Nil, tail)

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
