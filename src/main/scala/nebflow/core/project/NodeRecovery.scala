/* 从 NodeEngine 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.project

import cats.effect.*
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.shared.PathUtil

private[project] trait NodeRecovery:
  self: NodeEngine =>

  // ── 未申报提醒阶梯（noderpt 批 A 段 2026-09-11 作者裁定）────────────────────
  //
  // 背景：完成门腿 2 默认开（`reportGateHoldEnabled`）⇒ 节点交棒（桥收到 Completed）
  // 而**未**调 node_report 时不终态化——节点保持 Running、会话存活。本腿是那条
  // 「保持 Running」的兜底提醒：**永不判 failed、永不上报失败、永不杀进程或会话**
  // （相对设计稿 §4.2「30min 判 failed」的核心改判），只提醒 + 留痕，等人工处置。
  //
  // 时序（作者裁定逐字）：10min / 30min / 1h / 2h / 4h + 此后每 4h 一拍、单节点上限
  // 8 拍；第 8 拍后**不再注入**（每拍都是一次完整 LLM turn，封顶为省 token），但
  // **事件不停**——改每 `quiescentIntervalMs`（默认 4h）一条 `node-report-missing`
  // （stage=quiescent、reminderCount 不递增），直到终态/挂起出口/申报清表。
  //
  // 计时跨宿主重启存活：起点落**节点持久字段** `reportPendingSince`（落盘），扫描腿在
  // `ProjectActor.TtlTick`（30s 节拍）上跑——禁用 per-node fiber 计时器（重启即丢）。

  /**
   * 未申报提醒阶梯扫描腿（ProjectActor.TtlTick 30s 驱动，与 `settleStaleRunningNodes` /
   * `settleRunnableSweep` 同族——复用既有心跳点零新调度器）。
   *
   * 逐节点判据（作者裁定口径）：`status == Running ∧ reportPendingSince 已置`；
   *   - 申报槽非空（**非消费读**）⇒ 申报已到但桥可能未观测（`NodeMessage` 重入轮不产
   *     生 `Completed`）⇒ **唤醒桥复检放行**（[[wakeBridgeForRelease]]，不清表不注入提醒）；
   *   - `elapsed ≥ 第 N 档 ∧ N > reportReminderCount` ⇒ 发第 N 拍（注入 + 事件）；
   *   - `dueRung ≥ maxRungs` 且拍数已满 ⇒ quiescent 档（只写事件，间隔
   *     `quiescentIntervalMs`）；
   *   - 与 `cleanupRunTables` 对称：节点非 Running / 会话映射已清 / 会话非活 ⇒ 跳过
   *     （注入需要活会话；僵尸腿由 settleStaleRunningNodes 管）。
   *
   * 幂等：拍数按 `reportReminderCount` 的 CAS 单发（同一拍并发/重复命中 ⇒ 计数已推进
   * ⇒ no-op、不重复写事件）；quiescent 按内存记账节流。**零 failNode 路径**。
   */
  def remindUnreportedNodes(): IO[Unit] =
    val ladder = nebflow.shared.Defaults.NodeReportReminderLadderMs
    val maxRungs = math.min(nebflow.shared.Defaults.NodeReportReminderMaxRungs, ladder.length)
    if ladder.isEmpty || maxRungs <= 0 then IO.unit
    else
      store.snapshot.flatMap { s =>
        s.nodes.values
          .filter(n => n.status == NodeLifecycle.Running && n.reportPendingSince.isDefined)
          .toList
          .traverse_(n => remindUnreportedNode(n, ladder, maxRungs))
      }

  private def remindUnreportedNode(node: NodeDef, ladder: List[Long], maxRungs: Int): IO[Unit] =
    val since = node.reportPendingSince.getOrElse(0L)
    IO(System.currentTimeMillis()).flatMap { now =>
      val elapsed = now - since
      val dueRung = ladder.count(_ <= elapsed)
      val ladderStage = math.min(dueRung, maxRungs) > node.reportReminderCount
      val quiescentStage = !ladderStage && dueRung >= maxRungs
      if !ladderStage && !quiescentStage then IO.unit
      else
        nodeSessions.get.map(_.get(node.id)).flatMap { cached =>
          // 会话 id 候选 = 内存缓存 ∪ 持久 sessionRef（injectRunning 同款解析序）。
          val candidates = (cached.toList ++ node.sessionRef.toList).distinct
          if candidates.isEmpty then IO.unit // 三表已清（会话映射无）⇒ 跳过
          else
            // 申报槽检查（非消费读）：任一候选会话有申报 ⇒ 申报已到 ⇒ 清表不提醒。
            candidates
              .traverse(sid => NodeReportRegistry.peek(sid).map(sid -> _))
              .flatMap { probes =>
                probes.collectFirst { case (sid, Some(_)) => sid } match
                  case Some(sid) => wakeBridgeForRelease(node, sid, since)
                  case None =>
                    val target = math.min(dueRung, maxRungs)
                    if ladderStage then
                      resources.agentRegistry.get.flatMap { reg =>
                        candidates.find(reg.contains) match
                          case None => IO.unit // 会话非活 ⇒ 跳过（注入不可达）
                          case Some(sid) =>
                            fireReminderRung(node, sid, since, elapsed, target, maxRungs, ladder(target - 1))
                      }
                    else fireQuiescentEvent(node, since, elapsed, maxRungs, ladder(maxRungs - 1))
              }
          end if
        }
      end if
    }

  end remindUnreportedNode

  /**
   * 发第 N 拍（阶梯期）：`reportReminderCount` CAS 单发 ⇒ 注入提醒轮 + 写
   * `node-report-missing`（stage=active）。注入复用 `sendNodeMessage` 通道（其内部
   * 会写 node-message 留痕并在会话已终结时回退「注入未达」），投递结果进事件字段。
   */
  private def fireReminderRung(
    node: NodeDef,
    sessionId: String,
    since: Long,
    elapsed: Long,
    rung: Int,
    maxRungs: Int,
    rungMs: Long
  ): IO[Unit] =
    val expectedCount = node.reportReminderCount
    store
      .mutateWithResult { st =>
        st.nodes.get(node.id) match
          case Some(fresh)
              if fresh.status == NodeLifecycle.Running
                && fresh.reportPendingSince.contains(since)
                && fresh.reportReminderCount == expectedCount =>
            (st.copy(nodes = st.nodes.updated(node.id, fresh.copy(reportReminderCount = rung))), true)
          case _ => (st, false)
      }
      .flatMap {
        case (_, false) => IO.unit // 同拍重复命中 / 状态已变 ⇒ 不再发（幂等）
        case (s, true) =>
          val exhausted = rung >= maxRungs
          injectReminderTurn(
            sessionId,
            NodeEngine.reportReminderText(node.name, rung, maxRungs, rungMs, elapsed),
            rung,
            maxRungs
          )
            .flatMap { delivered =>
              s.nodes.get(node.id).traverse_(emitUpdated) *>
                FlowMapEventLog.append(
                  workspace,
                  projectName,
                  node.id,
                  NodeEngine.NodeReportMissingEventType,
                  NodeEngine.reportMissingSummary(
                    stage = NodeEngine.NodeReportStageActive,
                    rung = rung,
                    maxRungs = maxRungs,
                    rungMs = rungMs,
                    elapsedMs = elapsed,
                    pendingSince = since,
                    reminderCount = rung,
                    ladderExhausted = exhausted,
                    delivered = Some(delivered)
                  )
                ) *>
                logger.warn(
                  s"Node '${node.name}' (${node.id}) finished its turn without a node_report declaration — " +
                    s"reminder $rung/$maxRungs injected (delivered=$delivered, waited=${elapsed / 1000}s, session=$sessionId); " +
                    "the node stays Running — never failed, never killed (human supervision expected)"
                )
            }
      }

  end fireReminderRung

  /**
   * 释放唤醒（noderpt 批 F2，2026-09-11 复核 D2 修复）：申报**已到**但桥未观测时的放行腿。
   *
   * 病灶（复核 D2 / 探针 P3 实证）：`NodeMessage` 重入轮（`sendNodeMessage → injectRunning
   * → AgentCommand.ImmediateInput`）在 AgentActor 内转成 `UserInput(replyTo = None)` ⇒ 该轮
   * 收尾的 `completionTargets` 不含节点观察桥 ⇒ **无 `AgentEvent.Completed` 到桥**（⑧-1
   * 同一根因）。若该轮里 agent 调了 `node_report`（分发器催办后最常见的那一轮），申报就进了
   * `NodeReportRegistry`，而桥的复检点只在 `Completed` 分支 ⇒ 永不触发。改前本腿走 `peek`
   * 命中分支**只清表**（`clearReportPending`）不放行 ⇒ 计时清零、节点永久 Running、
   * 零提醒零投递（安全网被无声关闭）。
   *
   * 本腿动作：命中申报槽 ⇒ **唤醒桥复检**（**不清表**）。桥在 `Completed` 分支里做的正是
   * 「`clearReportPending *> releaseNow`」原子组合 ⇒ 复检即放行，清表与放行同点；申报信息
   * 由终态点的 `NodeReportRegistry.drain` 单次消费（pass/fail/blocked 三链分流零改动）。
   *
   * **为什么退化为「注入一次唤醒轮」而非直接放行**（按任务书要求登记退化理由 + 代码锚）：
   * `releaseNow` 是桥行为内的**局部闭包**（`NodeEngine.scala:2160`，闭包持有本次 `Completed`
   * 的 `messages` 与 `resultDeferred`），扫描腿（`ProjectActor.TtlTick`）手上只有
   * `AgentRecord`/`AgentRef`——其接收面是 `AgentEvent`，而 `Completed/Failed/Cancelled`
   * 三者都是**终态事件**：合成一个喂给桥 = 伪造终态（`messages` 只能是编造文本），比不释放
   * 更糟。故 **不存在等价的直接释放入口** ⇒ 退化为注入唤醒轮，让桥自己走它既有的复检放行
   * 路径。通道 = 与提醒轮/腿 1 后台完成通知同一条已实证机制：`node-` 前缀 Flow 会话以
   * `AgentRecord.supervisorRef`（= 本桥）作粘性 `replyTo` ⇒ 唤醒轮收尾必回 `Completed`。
   *
   * **为什么不清表**：若在此先清表，桥复检将见空槽 ⇒ 再次 hold，且申报已被吃掉、无法再被
   * `drain` 消费 ⇒ 比现状更糟的静默搁死（节点永久 Running 且无监督痕迹）。清表必须与放行
   * 同点（桥内原子组合）。
   *
   * 单发：同一未申报期只唤醒一次（see [[releaseWakeSent]]）。会话已不在 registry（死会话）
   * 时**不清表**、只留痕：计时保留 ⇒ quiescent 留痕与僵尸腿（`settleStaleRunningNodes`）
   * 两条既有出路都不丢。
   */
  private def wakeBridgeForRelease(node: NodeDef, sessionId: String, since: Long): IO[Unit] =
    releaseWakeSent.get.flatMap { sent =>
      if sent.get(node.id).contains(since) then IO.unit
      else
        releaseWakeSent.update(_ + (node.id -> since)) *>
          injectExternalTurn(
            sessionId,
            NodeEngine.NodeReportReleaseWakeSource,
            eventType = "release-wake",
            payload = NodeEngine.reportReleaseWakeText(node.name),
            metadata = io.circe.JsonObject("pendingSince" -> since.asJson)
          ).flatMap { delivered =>
            FlowMapEventLog.append(
              workspace,
              projectName,
              node.id,
              NodeEngine.NodeReportReleaseWakeEventType,
              NodeEngine.reportReleaseWakeSummary(sessionId, since, delivered)
            ) *>
              (if delivered then
                 logger.info(
                   s"Node '${node.name}' (${node.id}) holds a node_report declaration the completion bridge never " +
                     s"observed (immediate-injection turn produces no Completed) — release wake injected into " +
                     s"session '$sessionId' so the bridge re-checks and releases (clock kept: $since)"
                 )
               else
                 logger.warn(
                   s"Node '${node.name}' (${node.id}) holds a node_report declaration but session '$sessionId' is " +
                     "not live — release wake undeliverable; clock kept, node stays Running (dead-session settle / " +
                     "human action expected)"
                 ))
          }
    }

  /**
   * 提醒轮注入（**外部事件唤醒通道**）。
   *
   * 为什么不是 `sendNodeMessage`（ImmediateInput）通道：`ImmediateInput` 在 AgentActor
   * 内转成 `UserInput(replyTo = None)`，其轮次收尾的
   * `completionTargets = replyTo.toList ++ owedCompletion`（`finishTurnCont`）**不含**
   * 本节点观察桥 ⇒ 唤醒轮**不产生** `AgentEvent.Completed` ⇒ 桥无法复检、节点无法放行
   * （本批 spec R4 实证：提醒已注入、agent 已按提醒申报，节点仍滞留 Running）。
   * `ExternalEvent` 走的是**节点会话唤醒轮专用腿**（AgentActor：`node-` 前缀且
   * `kind=Flow` 的会话以 `AgentRecord.supervisorRef` 作粘性 replyTo）——唤醒轮结束必回
   * `Completed` 到本桥，与完成闸腿 1 的「后台任务完成通知 → 唤醒轮 → 复检」**同一机制**。
   * 提醒文本原文（含专用前缀头 [[NodeReportReminderPrefix]]）进 agent 上下文/transcript。
   *
   * 返回 true = 已投递给活会话 actor（tell 语义，fire-and-forget）；false = 会话已不在
   * registry（会话非活 ⇒ 事件字段 delivered=false，节点仍保持 Running，等人工/僵尸腿）。
   */
  private def injectReminderTurn(sessionId: String, text: String, rung: Int, maxRungs: Int): IO[Boolean] =
    injectExternalTurn(
      sessionId,
      NodeEngine.NodeReportReminderSource,
      eventType = "reminder",
      payload = text,
      metadata = io.circe.JsonObject(
        "rung" -> rung.asJson,
        "maxRungs" -> maxRungs.asJson
      )
    )

  /**
   * 外部事件唤醒轮注入**单点**（提醒轮与释放唤醒共用，见 [[wakeBridgeForRelease]]）：
   * 查 registry 取会话 actor → `AgentCommand.ExternalEvent`（tell 语义，fire-and-forget）。
   * 返回 true = 已投递给活会话 actor；false = 会话已不在 registry（调用方据此写
   * `delivered=false` / 走 WARN 留痕）。
   */
  private def injectExternalTurn(
    sessionId: String,
    source: String,
    eventType: String,
    payload: String,
    metadata: io.circe.JsonObject
  ): IO[Boolean] =
    resources.agentRegistry.get.map(_.get(sessionId)).flatMap {
      case None => IO.pure(false)
      case Some(record) =>
        (record.ref ! AgentCommand.ExternalEvent(
          source = source,
          eventType = eventType,
          payload = payload,
          metadata = metadata
        )).void *> IO.pure(true)
    }

  // ── C-2 台账回放（2026-11-04）────────────────────────────────────────────
  //
  // `node:` 消息的**重启重放腿**：发送侧 `sendNodeMessage` 已把每条消息在投递前落进
  // [[NodeMessageLedger]]（append-only）；本腿在 boot 认领时把「投了但未被消费」的
  // 消息重新送进节点的 resume prompt（续跑第一输入）。
  //
  // 幂等三闸（同一消息绝不二次注入/二次执行）：
  //   ① 台账状态：`consumed`/`dead` 终态跳过（纯函数 [[ReplayPlan.of]]）；
  //   ② 转录命中 `消息号 <id>`：crash 落在「接收方已消费、台账未标」窗口 ⇒ 转录是权威
  //      ⇒ append `consumed` 并跳过（绝不重复注入）；
  //   ③ 回放过的条目状态翻 `replayed`：第二次 boot 不再重放（文案里仍带标记 ⇒ 节点
  //      自己也能认出「这条我已收到」）。
  //
  // `dead`（终态拒拒收一致性，裁定④）：节点已终态的消息**永不**进入 resume prompt——
  // 新会话不存在，文本没有读者，注入只会变成幽灵指令。
  //
  // 留痕：每条实际进 prompt 的消息追加一行 `boot-recovery` 事件（禁止静默自愈纪律）。

  /** 回放计划（纯函数——spec 可直接单测，无 IO）。 */
  private[project] object ReplayPlan:
    final case class Item(entry: NodeMessageLedger.Entry, why: String)
    /**
     * @param items 需要回放进 resume prompt 的条目
     * @param consumedIds 台账未终态、但**转录已含标记**的条目（crash 落在「已消费未记账」
     *        窗口）——转录是权威，调用方补 `consumed` 行且**绝不**重复注入
     * @param terminalIds 已是终态（consumed/dead）的条目（零动作）
     */
    final case class Plan(items: List[Item], consumedIds: List[String], terminalIds: List[String])

    /**
     * 从全量台账行挑出该节点的回放候选。
     * @param ledgerAll 全部台账行（调用方 load 一次，避免多次扫盘）
     * @param transcriptHas 标记命中函数（消息号 => 转录是否已含）——调用方注入，保持纯函数
     */
    def of(
      nodeId: String,
      ledgerAll: List[NodeMessageLedger.Row],
      transcriptHas: String => Boolean
    ): Plan =
      val entries = NodeMessageLedger.fold(ledgerAll).filter(_.nodeId == nodeId)
      val (terminal, rest) = entries.partition(_.isTerminal)
      val (consumed, live) = rest.partition(e => transcriptHas(e.id))
      Plan(
        items = live.map(e => Item(e, why(e))),
        consumedIds = consumed.map(_.id),
        terminalIds = terminal.map(e => s"${e.id}(${e.event})")
      )

    private def why(e: NodeMessageLedger.Entry): String = e.event match
      case NodeMessageLedger.Queued =>
        "crash between the ledger write and the delivery tell (the fire-and-forget window closed before any consumer persisted it)"
      case NodeMessageLedger.Injected =>
        "tell accepted but the receiver died before persisting (the turn never reached the transcript nor the injection queue)"
      case NodeMessageLedger.Replayed =>
        "a previous replay landed before the session died again — replaying once more so the resumed turn still carries it"
      case other => other

  end ReplayPlan

  /**
   * 快段回放腿（[[bootRecoveryClaim]] resume 两分支调用，claim 事务**之后**）：
   * 读台账 → 挑该节点候选 → 转录命中者判 `consumed`（幂等②）→ 幸存条目拼成
   * `ResumeContext.resumePrompt` 的附录（带 `[NODE-MESSAGE]` 原头 + 幂等标记）→
   * 实际回放者翻 `replayed` 并落 `boot-recovery` 事件行。
   * 全程 fail-soft：台账读失败 = 零候选（不阻断认领）。
   */
  private def ledgerReplayAppendix(nodeId: String, node: NodeDef): IO[String] =
    NodeMessageLedger
      .load(workspace)
      .flatMap { rows =>
        val readTranscript: IO[List[nebflow.shared.Message]] =
          node.sessionRef match
            case Some(sid) => resources.sessionStore.loadMessagesForSession(sid).attempt.map(_.getOrElse(Nil))
            case None      => IO.pure(Nil)
        readTranscript.flatMap { transcript =>
          val plan = ReplayPlan.of(nodeId, rows, id => NodeMessageLedger.inMessages(transcript, id))
          val byId = rows.groupBy(_.id)
          def lastRow(id: String): Option[NodeMessageLedger.Row] = byId.get(id).flatMap(_.lastOption)
          // 幂等②写面：转录已含标记 ⇒ 台账终态化（永不再回放）。
          val consumeMarks = plan.consumedIds.traverse_(id =>
            lastRow(id)
              .map(r =>
                NodeMessageLedger.appendSoft(
                  workspace,
                  r.copy(
                    event = NodeMessageLedger.Consumed,
                    ts = System.currentTimeMillis(),
                    detail = Some("transcript already carries the message marker (consumed before the crash; ledger closed at boot)")
                  )
                )
              )
              .getOrElse(IO.unit)
          )
          if plan.items.isEmpty then consumeMarks.as("")
          else
            val lines = plan.items.map { it =>
              s"- [NODE-MESSAGE] ${NodeMessageLedger.markerOf(it.entry.id)}：${it.entry.text}（重启回放：原始投递未确认消费 — ${it.why}）"
            }
            consumeMarks *>
              plan.items.traverse_(it =>
                NodeMessageLedger.appendSoft(
                  workspace,
                  NodeMessageLedger.Row(
                    id = it.entry.id,
                    event = NodeMessageLedger.Replayed,
                    nodeId = it.entry.nodeId,
                    nodeName = it.entry.nodeName,
                    text = it.entry.text,
                    route = it.entry.route,
                    ts = System.currentTimeMillis(),
                    detail = Some(s"replayed into the resume prompt (session=${node.sessionRef.getOrElse("-")})")
                  )
                )
              ) *>
              FlowMapEventLog
                .append(
                  workspace,
                  projectName,
                  nodeId,
                  NodeEngine.BootRecoveryEventType,
                  s"node-message replay: ${plan.items.size} message(s) re-injected into the resume prompt " +
                    s"(ids: ${plan.items.map(_.entry.id).mkString(", ")}; skipped: ${plan.terminalIds.mkString(",")})"
                ) *>
              logger
                .info(
                  s"[boot-recovery] node '${node.name}' ($nodeId): ${plan.items.size} node-message(s) replayed into the resume prompt " +
                    s"(${plan.items.map(_.entry.id).mkString(", ")}); ${plan.consumedIds.size} already consumed, ${plan.terminalIds.size} terminal"
                )
                .as(
                  s"\n\n== 分发器补充（NodeMessage 重启回放 · C-2） ==\n" +
                    "以下消息在你的上次会话里已投递但**未被消费**（不在恢复的历史里）——按已收到的指令执行，勿重做已完成步骤。\n" +
                    lines.mkString("\n")
                )
        }
      }
      .handleErrorWith(e =>
        logger
          .warn(
            s"[boot-recovery] node-message ledger replay failed for node $nodeId: ${Option(e.getMessage).getOrElse(e.toString)} — resume continues without replay"
          )
          .as("")
      )

  /**
   * 队列兜底回放腿（[[ProjectCrashRecovery.recoverProject]] / delegate 作用域 sweep 在
   * claim 循环之后各调一次）：**没有被认领**的节点（wiring/pending 等）若台账里还躺着
   * 「已排队未投递」的消息，说明崩溃落在「台账 `queued` 之后、task 追加之前」这一微窗口
   * ——task 没有正文，回放靠把消息**追加进 task**（== 分发器补充 == 段，随 flow-map
   * 落盘、节点启动 buildInput 必读）并翻 `appended`。
   *
   * 幂等：task 正文已带标记（同一消息追加过）⇒ 只补状态行，零重复追加；终态节点 ⇒
   * `dead`（裁定④：终态不再收消息）；本次 boot 已认领（在 bootRecoveryQueue）⇒ 跳过
   * （resume prompt 回放腿负责，绝不同时走两腿）。返回实际动作条数（0 = 零日志噪音）。
   */
  private[project] def replayPendingNodeMessages(): IO[Int] =
    NodeMessageLedger
      .load(workspace)
      .flatMap { rows =>
        val pending = NodeMessageLedger.fold(rows).filter(_.pending)
        if pending.isEmpty then IO.pure(0)
        else
          (bootRecoveryQueue.get, store.snapshot).flatMapN { (claimed, snap) =>
            pending
              .filterNot(e => claimed.contains(e.nodeId))
              .foldLeft(IO.pure(0)) { (acc, e) =>
                acc.flatMap { n =>
                  snap.nodes.get(e.nodeId) match
                    case None =>
                      logger
                        .warn(
                          s"[boot-recovery] node-message ${e.id} orphaned: node ${e.nodeId} is gone from the flow map — ledger left as-is"
                        )
                        .as(n)
                    case Some(node) if NodeLifecycle.Terminal.contains(node.status) =>
                      NodeMessageLedger
                        .transition(workspace, e, NodeMessageLedger.Dead, Some(s"node terminal (${node.status}) at boot"))
                        .as(n)
                    case Some(node) =>
                      if NodeMessageLedger.inText(node.task.getOrElse(""), e.id) then
                        // 幂等：正文已带标记 ⇒ 已追加过（只是状态行没写完）
                        NodeMessageLedger
                          .transition(workspace, e, NodeMessageLedger.Appended, Some("task already carries the marker"))
                          .as(n)
                      else
                        val sec =
                          s"${NodeEngine.nodeMessageSection(undelivered = true)} · ${NodeMessageLedger.markerOf(e.id)}"
                        store
                          .mutate { st =>
                            st.nodes.get(e.nodeId) match
                              case Some(fresh) =>
                                st.copy(nodes =
                                  st.nodes.updated(
                                    e.nodeId,
                                    fresh.copy(task = Some(fresh.task.getOrElse("") + s"\n\n$sec\n${e.text}"))
                                  )
                                )
                              case None => st
                          }
                          .flatMap { _ =>
                            FlowMapEventLog.append(
                              workspace,
                              projectName,
                              e.nodeId,
                              NodeEngine.NodeMessageEventType,
                              s"replayed into task at boot (status=${node.status}): ${e.text}"
                            ) *> NodeMessageLedger
                              .transition(
                                workspace,
                                e,
                                NodeMessageLedger.Appended,
                                Some("replayed into the task at boot (never delivered before the crash)")
                              )
                              .as(n + 1)
                          }
              }
            }
          }
      }
      .handleErrorWith(e =>
        logger
          .warn(s"[boot-recovery] node-message queue replay failed: ${Option(e.getMessage).getOrElse(e.toString)}")
          .as(0)
      )

  /** 慢段收尾（[[bootRecoveryStart]] 之后）：已进转录的回放条目翻 `consumed`。 */
  private[project] def markReplayedConsumed(nodeId: String): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case None => IO.unit
      case Some(node) =>
        node.sessionRef.traverse_(sid =>
          resources.sessionStore.loadMessagesForSession(sid).attempt.flatMap {
            case Right(msgs) =>
              NodeMessageLedger.entries(workspace).flatMap { entries =>
                entries
                  .filter(e =>
                    e.nodeId == nodeId && e.event == NodeMessageLedger.Replayed && NodeMessageLedger.inMessages(msgs, e.id)
                  )
                  .traverse_(e =>
                    NodeMessageLedger.transition(
                      workspace,
                      e,
                      NodeMessageLedger.Consumed,
                      Some(s"replayed message consumed (marker found in session=$sid transcript)")
                    )
                  )
              }
            case Left(_) => IO.unit
          }
        )
    }

  // ── boot-time 崩溃恢复（crash-recovery 批 2026-09-07，设计 §3.2 快段/慢段）──
  //
  // 快段（同步秒级）：对崩溃残留 status=Running（kill -9 / 无钩子机会）**或**
  // status=Interrupted（优雅关机钩子翻态，中断恢复语义批 2026-09-13 spec §2.4）
  // 节点按持久层完整性三分类（§3.3）：
  //   (a) checkpoint 完整 / (b) mid-turn —— 磁盘不可区分，统一同路径：认领 = 翻回
  //       Pending + 清 bgWait + boot-recovery 事件 + 入 bootRecoveryQueue，续跑交慢段；
  //   (c) sessionRef 无值 / transcript 缺失/损坏/空 —— failNode("crash recovery:
  //       session transcript lost/corrupt") 走既有 deliverFailed 链（姊妹批挂接后自动
  //       获分发器 failed 通知）。
  // 认领动作改变节点状态使其即刻脱离 watchdog（settleStaleRunningNodes 只看 Running）
  // 与资格回扫（bootRecoveryQueue 排除）的处置口径——sweep 与 watchdog 的竞速由
  // 挂载顺序结构性关闭（快段先于 projectTtlScanner 启动完成）。
  // 判定材料 = sessionRef（D1，与 startedAt 同事务落库）→ SessionStore transcript
  // 文件。分类读取用 loadMessagesForSession（decode 失败侧自动备份损坏文件——
  // SessionStore 既有语义，非静默）。

  /**
   * 快段单节点：分类 + 认领。返回：
   * - Some(Right(ctx))：已认领（翻 Pending + 清 bgWait + 事件 + 入队），待慢段
   *   startNode(ctx) 续跑；
   * - Some(Left(reason))：(c) 类已 failNode（reason 含 transcript 指针），下游走
   *   deliverFailed 既有链；
   * - None：非候选（非 Running|Interrupted）或认领事务败给并发状态变更（fresh 守卫拒写）。
   * 节点级异常不在此吞——调用方（sweep）逐节点 handleErrorWith，残余 Running 由
   * watchdog 兜底（R4）。
   */
  /**
   * 台账 `dead` 终态化（终态拒收一致性）：节点已终态/判死时，其未消费的 `node:` 消息
   * 永不回放（受裁定④约束——终态节点不收消息，新会话不存在，文本没有读者）。
   */
  private def ledgerDeadMark(nodeId: String, why: String): IO[Unit] =
    NodeMessageLedger.pendingFor(workspace, nodeId).flatMap { pending =>
      pending.traverse_(e => NodeMessageLedger.transition(workspace, e, NodeMessageLedger.Dead, Some(why)))
    }

  /**
   * 认领成功后的台账回放收口（幂等③）：`Some(Right(ctx))` 才补回放附录——认领 CAS
   * 败方（并发终态化/已被处置）**不得**回放（消息留待人工处置，账仍 pending 可查）。
   * 其余结果原样透传（Left/None 零动作）。
   */
  private def withLedgerReplay(
    n: NodeDef,
    outcome: Option[Either[String, NodeEngine.ResumeContext]]
  ): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
    outcome match
      case Some(Right(ctx)) =>
        ledgerReplayAppendix(n.id, n).map { appendix =>
          if appendix.isEmpty then Some(Right(ctx))
          else Some(Right(ctx.copy(resumePrompt = ctx.resumePrompt + appendix)))
        }
      case other => IO.pure(other)

  def bootRecoveryClaim(n: NodeDef): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
    // 资格判据扩展（中断恢复语义批 2026-09-13，spec §2.4 #2）：Running ∪ Interrupted
    // ——优雅关机现场（interrupted）与崩溃现场（Running，kill -9 / 无钩子机会）同一
    // 认领链（R8 复用纪律：零新机制）。非二者 ⇒ 非候选。
    if n.status != NodeLifecycle.Running && n.status != NodeLifecycle.Interrupted then IO.pure(None)
    else
      def failClaim(reason: String): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
        // (c) 类：仍 ∈ {Running, Interrupted} 才处置（fresh 守卫——与并发终态化互斥）；
        // failNode 自带 deliverFailed + WS；boot-recovery 事件留痕（禁止静默自愈）。
        // draining 诚实性（中断恢复语义批）：failNode 可能被守卫拒写（关机窗口）——此时
        // **不得**假装已失败（会让「boot-recovery failed」事件与真实状态说谎），故写后
        // 复核：真落 failed 才记事件并报 Left，否则返回 None（留痕已由守卫自身给出）。
        store.getNode(n.id).flatMap {
          case Some(fresh) if fresh.status == NodeLifecycle.Running || fresh.status == NodeLifecycle.Interrupted =>
            failNode(n.id, reason) *>
              store.getNode(n.id).flatMap {
                case Some(now) if now.status == NodeLifecycle.Failed =>
                  FlowMapEventLog.append(
                    workspace,
                    projectName,
                    n.id,
                    NodeEngine.BootRecoveryEventType,
                    s"failed (class c): $reason"
                  ) *>
                    // C-2：节点以 failed 收场 ⇒ 其未消费的 node 消息永不回放（`dead`）——
                    // 与裁定④「终态拒收」同口径：新会话不存在，注入只会变成幽灵指令。
                    ledgerDeadMark(n.id, s"node failed at boot-recovery (class c): $reason") *>
                    logger.warn(s"[boot-recovery] node '${n.name}' (${n.id}) failed: $reason").as(Some(Left(reason)))
                case _ =>
                  logger
                    .warn(
                      s"[boot-recovery] node '${n.name}' (${n.id}) failure write was refused (host draining) — claim left undone"
                    )
                    .as(None)
              }
          case _ => IO.pure(None)
        }
      def resumeClaim(
        ctx: NodeEngine.ResumeContext,
        claimNote: String
      ): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
        // (a)/(b) 类认领：Running|Interrupted → Pending 单事务翻转（CAS：并发终态化/
        // 已处置 → 拒写返回 None）+ 清 bgWait（等待集随进程蒸发 G4，resume prompt
        // 已附死亡告知）。interrupted 的其余字段原样：sessionRef（恢复依据）、
        // deliveredTo（in-barrier 已收投递，既有 claim 行为一致）保留——续跑用的
        // 就是中断前的会话与断点。
        store
          .mutateWithResult { s =>
            s.nodes.get(n.id) match
              case Some(f) if f.status == NodeLifecycle.Running || f.status == NodeLifecycle.Interrupted =>
                (
                  s.copy(nodes =
                    s.nodes.updated(
                      n.id,
                      f.copy(
                        status = NodeLifecycle.Pending,
                        bgWait = None,
                        // 未申报计时随认领清零（noderpt 批 A 段）：重挂载后按新会话重新起表，
                        // 旧一轮的起点/拍数不继承（flipToRunning 处还有一道同语义的兜底清零）。
                        reportPendingSince = None,
                        reportReminderCount = 0
                      )
                    )
                  ),
                  true
                )
              case _ => (s, false)
          }
          .flatMap { (s, claimed) =>
            if !claimed then IO.pure(None)
            else
              s.nodes.get(n.id).traverse_(emitUpdated) *>
                bootRecoveryQueue.update(_ + n.id) *>
                FlowMapEventLog.append(
                  workspace,
                  projectName,
                  n.id,
                  NodeEngine.BootRecoveryEventType,
                  s"claimed (resume): $claimNote"
                ) *>
                logger
                  .info(s"[boot-recovery] node '${n.name}' (${n.id}) claimed for rehydrate: $claimNote")
                  .as(Some(Right(ctx)))
          }
      // ── 分类（§3.3 判定表；loop 节点裁定③双会话续接）──
      if n.loop.exists(_.enabled) then
        n.sessionRef match
          case None =>
            failClaim(
              s"crash recovery: session transcript lost (loop node has no sessionRef persisted — predates crash recovery; " +
                s"loopRound=${n.loopRound}, loopPhase=${n.loopPhase.getOrElse("-")} persisted for audit)"
            )
          case Some(workerSid) =>
            resources.sessionStore.loadMessagesForSession(workerSid).attempt.flatMap {
              case Right(workerMsgs) if workerMsgs.nonEmpty =>
                // verify transcript 可缺（每轮输入自足）：缺 → 空 transcript 水合（等价新会话）。
                val verifySid = n.sessionRefVerify
                verifySid
                  .traverse(sid =>
                    resources.sessionStore.loadMessagesForSession(sid).attempt.map {
                      case Right(msgs) => msgs
                      case Left(_) => Nil // 既有 BackoffSupervisor 同款容错：坏档不阻断恢复
                    }
                  )
                  .map(_.getOrElse(Nil))
                  .flatMap { verifyMsgs =>
                    val round = math.max(n.loopRound, 1)
                    val phase = n.loopPhase.getOrElse(NodeEngine.LoopPhaseWorker)
                    resumeClaim(
                      NodeEngine.ResumeContext(
                        sessionId = workerSid,
                        recoveredMessages = workerMsgs,
                        resumePrompt = NodeEngine.loopWorkerResumePrompt(n, round, phase, workerMsgs.size),
                        verifySessionId = verifySid,
                        verifyMessages = verifyMsgs,
                        loopResumeRound = round,
                        loopResumePhase = Some(phase)
                      ),
                      s"loop dual-session resume (worker=$workerSid msgs=${workerMsgs.size}, verify=${verifySid.getOrElse("-")} msgs=${verifyMsgs.size}, round=$round, phase=$phase)"
                    ).flatMap(withLedgerReplay(n, _))
                  }
              case Right(_) =>
                failClaim(
                  s"crash recovery: session transcript lost/corrupt (loop worker sessionRef=$workerSid empty or missing; " +
                    s"loopRound=${n.loopRound}, loopPhase=${n.loopPhase.getOrElse("-")} persisted for audit)"
                )
              case Left(e) =>
                failClaim(
                  s"crash recovery: session transcript unreadable (loop worker sessionRef=$workerSid: ${Option(e.getMessage).getOrElse(e.toString)})"
                )
            }
      else
        n.sessionRef match
          case None =>
            failClaim(
              "crash recovery: session transcript lost (no sessionRef persisted — node predates crash recovery)"
            )
          case Some(sid) =>
            resources.sessionStore.loadMessagesForSession(sid).attempt.flatMap {
              case Right(msgs) if msgs.nonEmpty =>
                resumeClaim(
                  NodeEngine.ResumeContext(
                    sessionId = sid,
                    recoveredMessages = msgs,
                    resumePrompt = NodeEngine.nodeResumePrompt(projectName, n, msgs.size, n.bgWait)
                  ),
                  s"resume (sessionRef=$sid msgs=${msgs.size}${n.bgWait
                      .fold("")(w => s", bgWait cleared: ${w.take(120)}")})"
                ).flatMap(withLedgerReplay(n, _))
              case Right(_) =>
                failClaim(s"crash recovery: session transcript lost/corrupt (sessionRef=$sid)")
              case Left(e) =>
                failClaim(
                  s"crash recovery: session transcript unreadable (sessionRef=$sid: ${Option(e.getMessage).getOrElse(e.toString)})"
                )
            }

      end if

  /**
   * 慢段单节点：出队 + startNode(resume)。出队即归还 settle 回扫资格——本 fiber
   * 紧接 startNode（CAS 翻转与任何并发新鲜启动互斥裁决，微秒窗口败者安静）；失败
   * （agent 缺失等）则节点留在正常 pending 池由资格回扫新鲜兜底（R4 降级）。
   */
  def bootRecoveryStart(nodeId: String, ctx: NodeEngine.ResumeContext): IO[Unit] =
    bootRecoveryQueue.update(_ - nodeId) *>
      logger.info(s"[boot-recovery] node $nodeId rehydrating (session=${ctx.sessionId})") *>
      startNode(nodeId, Some(ctx)) *>
      // C-2 收尾（幂等③的观测面）：续跑会话已把回放文本写进转录 ⇒ 台账翻 `consumed`。
      markReplayedConsumed(nodeId)

  /**
   * 本引擎是否拥有该会话（P2 定位键修正的实例侧入口，**只读** store 扫描）。
   * watcher 用它代替 `rt.engine.rootSessionId == rec.rootSessionId` 判定「哪个
   * runtime 是这个会话的家」。
   */
  def ownsSession(sessionId: String): IO[Boolean] =
    store.snapshot
      .map(snap => NodeEngine.findNodeForSession(snap, sessionId).isDefined)
      .handleErrorWith(_ => IO.pure(false))

  /**
   * P2：**挂起**节点会话——「只停 actor，不改节点状态」。
   *
   * 这就是 R-1=B 要求的「挂起不终态化」原语：今天的 `bridgeCancelled` 一步兼两职
   * （停 actor + 节点终态化），本方法把前者单独拆出来。实现方式是向观察桥发一条
   * 带 [[NodeEngine.SuspendReasonPrefix]] 哨兵的 `AgentEvent.Cancelled`——桥照旧
   * 完成 `resultDeferred`，引擎 fiber 认出哨兵后走**挂起腿**（停 actor + 清理运行表，
   * **不写 status/result/ttl、不通知、不摘除**），节点保持 `Running`。
   *
   * 调用方（`TaskStuckWatcher` 的 L3 恢复腿）随后按「有界等待 actor 终止确认」
   * 轮询 [[nebflow.agent.SharedResources]] 的 registry，确认会话已摘除后再调
   * [[hardResumeNode]] 做 CAS + resume。
   *
   * 与 `cancelNode`（终态化）的关系：两条路**互斥且语义不同**——`cancelled` 保持
   * 真终态（不可重激活），挂起只是本代次的中断点。
   */
  def suspendNode(sessionId: String, reason: String): IO[Boolean] =
    store.snapshot.map(snap => NodeEngine.findNodeForSession(snap, sessionId)).flatMap {
      case None =>
        // ③ 恢复路径语义（#159/#176，2026-09-14；取证件 §4.4「恢复失败路径」）：
        // 「**no node owns session**」= 承接失败的一种——本引擎 store 里查无该会话的
        // 归属节点（从未绑定 / 已归档）⇒ **无可挂起之物**。此前只有 WARN、事件流零行
        // ⇒ 事后无法 join 出「哪次承接失败」。本行补事件留痕（`nodeId` 字段承载
        // **会话 id**——与 `FlowMapEventLog.DispatcherIdleExpiredType` 同款先例：
        // 查无节点时无 NodeDef.id 可写）。**零行为变更**（仍返回 false）。
        FlowMapEventLog.append(
          workspace,
          projectName,
          sessionId,
          "hard-recovery",
          s"no-owner session=$sessionId stage=suspend verdict=recovery-impossible " +
            "(session never bound to a node in this project's store / node already archived)"
        ) *>
          logger.warn(s"[stuck-recovery] suspend skipped — no node owns session $sessionId").as(false)
      case Some(n) =>
        resources.agentRegistry.get.map(_.get(sessionId).flatMap(_.supervisorRef)).flatMap {
          case Some(sup) =>
            (sup ! AgentEvent.Cancelled(sessionId, s"${NodeEngine.SuspendReasonPrefix}): $reason"))
              .as(true)
              .handleErrorWith(e =>
                logger.warn(s"[stuck-recovery] suspend signal for $sessionId failed: ${e.getMessage}").as(false)
              )
          case None =>
            logger
              .warn(
                s"[stuck-recovery] suspend skipped for node '${n.name}' (${n.id}) — no supervisor bridge registered " +
                  "(session already gone or never bound)"
              )
              .as(false)
        }
    }

  /**
   * P2：恢复锚探测（设计 §3.1 的 A3 + §3.2 的产物判据）。**零 LLM、零副作用**——
   * 只读目录 + 三条 git 只读查询；探测失败如实降级（不伪造可用）。
   *
   * 口径见 [[RecoveryAnchor]]（含与 §3.2 逐字公式的差异说明）。
   */
  def probeRecoveryAnchors(sessionId: String): IO[NodeEngine.RecoveryAnchor] =
    store.snapshot
      .flatMap { snap =>
        NodeEngine.findNodeForSession(snap, sessionId) match
          case None =>
            IO.pure(NodeEngine.RecoveryAnchor(false, "", false, "node not found in this project's store"))
          case Some(n) =>
            val dir: String =
              try PathUtil.resolveNodeProjectRoot(workspace, n.worktree).toString
              catch case _: Throwable => workspace
            val f = os.Path(dir)
            val worktreeOk = os.exists(f) && os.isDir(f) && os.exists(f / ".git")
            val gitOut = (args: List[String]) =>
              try
                val r = os.proc(args).call(cwd = f, check = false, stderr = os.Pipe, mergeErrIntoOut = true)
                Some((r.exitCode, r.out.text().trim))
              catch case _: Throwable => None
            val porcelain = gitOut(List("git", "status", "--porcelain"))
            // 「本代次新提交数」= 本节点 startedAt 之后的提交（NodeDef.startedAt 是
            // Option[Long]；缺失则退化为 0 ⇒ 该维不计，只靠 P2 工作区判据）。
            val since = gitOut(
              List("git", "rev-list", "--count", "HEAD", "--since=" + (n.startedAt.getOrElse(0L) / 1000).toString)
            )
            val newCommits = since.flatMap(_._2.toIntOption).getOrElse(0)
            val dirty = porcelain.exists(_._2.nonEmpty)
            val hasOutput = dirty || newCommits > 0
            val evidence =
              s"git status --porcelain = ${if dirty then "non-empty" else "clean"}, " +
                s"commits since node start = $newCommits"
            val anchor = NodeEngine.RecoveryAnchor(worktreeOk, dir, hasOutput, evidence)
            // ③（#159/#176）：A3 锚不可用 = **J1 形态**（目录消失）⇒ 单发留痕一行
            // `worktree-missing` 事件（把「静默消失」变成事件流上可 join 的一等信号；
            // 取证件 §6.1 建议 4）。**零行为变更**：锚探测结果与恢复分支逐字不变。
            if worktreeOk then IO.pure(anchor)
            else noteWorktreeMissingOnce(n.id, n.worktree.getOrElse(""), dir).as(anchor)
      }
      .handleErrorWith(e =>
        logger
          .warn(s"[stuck-recovery] anchor probe for $sessionId failed: ${Option(e.getMessage).getOrElse(e.toString)}")
          .as(NodeEngine.RecoveryAnchor(false, "", false, "anchor probe failed (see log)"))
      )

  // ── #159/#176（wtsurv 批，2026-09-14）：**worktree 目录中途消失**面 ────────────
  //
  // **① 定因（机制类表述；禁指认责任人——责任者未证）**：两例消失
  // （`delegate-kernel-impl-2` / `-retry`，2026-09-11）的观测签名 =「**目录消失 +
  // `.git/worktrees/<name>` 注册残留** ⇒ `git worktree list` 标 **prunable**」。逐条
  // 排除后**唯一自洽类** = 「**git 之外的、未经注册的递归删除**」——`git worktree prune`
  // 语义上**从不删工作目录**（只清注册）、`git worktree remove` 会**同时**清注册 ⇒
  // 二者都造不出 prunable 签名。候选路径（D-1 agent/分发器手工 `rm -rf`、D-2 UI
  // file-explorer `os.remove.all`，见 `WebSocketRoutes.deletePath`）只作**机制类**候选
  // 并列，**不指认**任何具体要求责任人。取证全文 =
  // worktree-vanish-forensics 取证（内部留档）
  // （§1.2 机制 D / §1.4 未证清单 / §4.3 J1–J7 判据表 / §6.1 建议 1–4）。
  //
  // **② 补上可用信号**（原状：`TaskStuckWatcher` 类③ 注释自陈 `AgentRecord` 无 cwd
  // 字段 ⇒ registry 快照上「无信号可用」）：
  //   - [[sessionCwdAlive]] 会话运行时 cwd 的**存在性**判据（fail-safe 三态）；
  //   - [[failEnvLostNode]] 环境失效的**快速失败**出口（终态化 + 显式错误 + 事件留痕）；
  //   - [[noteWorktreeMissingOnce]] J1 形态的**事件面一等信号**（`worktree-missing`）。
  // ⚠ 这三个信号只做「环境失效」分类与留痕，**不参与任何判死不等式**（与
  // `TaskStuckWatcher` 红线 R6-4 同款纪律：不得用它们促成判死）。

  /**
   * 会话运行时工作目录（**只读**）：本引擎 store 里拥有该会话的节点的运行时
   * projectRoot——与 `runWithAgent` 的会话 cwd **同源单点**
   * （[[PathUtil.resolveNodeProjectRoot]] 的双位置实存解析）。
   * `None` = 本引擎不拥有该会话（未绑定 / 已归档）⇒ 调用方据此避开「查无 ⇒ 误判失效」。
   */
  def sessionRuntimeRoot(sessionId: String): IO[Option[String]] =
    store.snapshot
      .map { snap =>
        NodeEngine.findNodeForSession(snap, sessionId).map { n =>
          try PathUtil.resolveNodeProjectRoot(workspace, n.worktree)
          catch case _: Throwable => workspace
        }
      }
      .handleErrorWith(e =>
        logger
          .warn(s"[env-lost] runtime root probe for $sessionId failed: ${Option(e.getMessage).getOrElse(e.toString)}")
          .as(None)
      )

  /**
   * **环境失效判据**（类③ `env-lost` 的唯一取数口）——fail-safe 三态：
   *   - `None` = **未知**（本引擎不拥有该会话 / 探测自身失败）⇒ 调用方**不得**据此判死；
   *   - `Some(true)` = 运行时 cwd 实存且是目录 ⇒ 环境正常（**负控**：绝不发 env-lost）；
   *   - `Some(false)` = 运行时 cwd **已消失** ⇒ J1/J2 形态的机器可读判据。
   *
   * 口径：`os.exists ∧ os.isDir`（沿软链，与 [[PathUtil.resolveWorktreeDir]] 同语义）；
   * 位置**双查**由 `resolveNodeProjectRoot` 单点给出（权威位置 `worktrees/<名>` 优先、
   * 顶层存量 fallback）⇒ **J3**（目录在而报 cwd 缺失 = 路径解析面问题，查
   * `paths.scala:162-186`）不会被本判据读成「消失」。异常一律回落 `true`（判活）。
   */
  def sessionCwdAlive(sessionId: String): IO[Option[Boolean]] =
    sessionRuntimeRoot(sessionId).map(
      _.map(dir =>
        try
          val p = os.Path(dir)
          os.exists(p) && os.isDir(p)
        catch case _: Throwable => true
      )
    )

  /**
   * **环境失效快速失败**（#159/#176 ② 的引擎侧出口）：把「运行时目录消失 ⇒ 该会话
   * 不可能再产生有效副作用」从「静默 ~10min 后判 stuck（L1→L3 三段阶梯，实测
   * 671s/683s）」改成**显式终态 + 明确错误**。
   *
   * 语义与 [[failStuckRecovery]] **同族**（引擎接管失败 = `failed`；`failed` 可经
   * `NodeEdit` 重激活，`cancelled` 不可）——差异只在**触发判据**（环境失效 vs 恢复腿
   * 未生效）与**时限**（静默窗 vs 三段阶梯）。
   *
   * 返回 `true` = 本引擎确实把节点改判为 `failed`（调用方据此只广播一次）；
   * `false` = 本引擎不拥有该会话 / 节点已终态 ⇒ **幂等**：零写、零重复通知。
   *
   * 事件面：写一条 `env-lost` 事件（session + cwd + J 判据号），使「目录消失」在
   * `flow-map-events.jsonl` 上可 join；`DispatchNotify.releaseTerminalNotify` 归还
   * 可能的回流占位（与 [[failStuckRecovery]] 逐字同款）。
   */
  def failEnvLostNode(sessionId: String, reason: String): IO[Boolean] =
    store.snapshot.flatMap { snap =>
      NodeEngine.findNodeForSession(snap, sessionId) match
        case None =>
          logger
            .warn(s"[env-lost] fast-fail skipped — no node owns session $sessionId in this project's store")
            .as(false)
        case Some(n) if NodeLifecycle.Terminal.contains(n.status) =>
          logger
            .info(s"[env-lost] fast-fail skipped for node '${n.name}' (${n.id}) — already ${n.status} (idempotent)")
            .as(false)
        case Some(n) =>
          val dir =
            try PathUtil.resolveNodeProjectRoot(workspace, n.worktree)
            catch case _: Throwable => workspace
          dispatchNotify.releaseTerminalNotify(n.id) *>
            FlowMapEventLog.append(
              workspace,
              projectName,
              n.id,
              "env-lost",
              s"session=$sessionId cwd=${FlowMapEventLog.noWs(dir)} judge=J1 " +
                "(dir missing; a git worktree registration may remain ⇒ recursive delete outside git)"
            ) *>
            failNode(n.id, reason).as(true)
    }

  private def noteWorktreeMissingOnce(nodeId: String, bare: String, dir: String): IO[Unit] =
    worktreeMissingNoted
      .modify(set => if set.contains(bare) then (set, false) else (set + bare, true))
      .flatMap { fresh =>
        if !fresh then IO.unit
        else
          FlowMapEventLog.append(
            workspace,
            projectName,
            nodeId,
            "worktree-missing",
            s"bare=${FlowMapEventLog.noWs(bare)} path=${FlowMapEventLog.noWs(dir)} judge=J1"
          ) *>
            logger.warn(
              s"[worktree-missing] node '$nodeId' declares worktree '$bare' but '$dir' does not exist — " +
                "J1 signature (directory gone; a `.git/worktrees/<name>` registration may remain ⇒ prunable). " +
                "Machine class: a recursive delete performed OUTSIDE git (prune never removes the work tree, " +
                "worktree remove also clears the registration) — no responsible party is identified here."
            )
      }

  /**
   * P2：恢复腿未生效时的终局写点（R-1=B 形态）。
   *
   * 与 R5 的 [[settleFailedHardResume]] 的差异：R5 那条腿的前提是「节点处于中间态
   * `Cancelled`」（先终态化再救援），而 R-1=B 之后恢复腿全程不进 `Cancelled` ——
   * 节点此时是 **`Running`**。语义完全一致（诚实失败：引擎接管失败 = failed，可经
   * `NodeEdit` 重激活），故复用同一条 `failNode` 全链，只是状态守卫不同。
   *
   * 先 [[DispatchNotify.releaseTerminalNotify]] 归还可能的回流占位（幂等：无占位
   * 零写）——保证「恰好一次回流」在两种形态下都成立（挂起腿本不写占位，R5 旧形态
   * 会写，两者都在此归还）。
   */
  def failStuckRecovery(sessionId: String, err: String): IO[Unit] =
    store.snapshot.flatMap { snap =>
      NodeEngine.findNodeForSession(snap, sessionId) match
        case None =>
          logger.error(
            s"[stuck-recovery] recovery failed but no node owns session $sessionId — nothing to re-judge " +
              "(manual intervention required)"
          )
        case Some(n) if NodeLifecycle.Terminal.contains(n.status) =>
          logger.info(
            s"[stuck-recovery] recovery failed for session $sessionId but node '${n.name}' (${n.id}) is already " +
              s"${n.status} — no fallback action needed (idempotent)"
          )
        case Some(n) =>
          dispatchNotify.releaseTerminalNotify(n.id) *>
            FlowMapEventLog.append(
              workspace,
              projectName,
              n.id,
              "hard-recovery",
              s"stuck recovery FAILED (session=$sessionId) — node re-judged as failed: ${err.take(200)}"
            ) *>
            failNode(n.id, err)
    }

  /**
   * Hard-recovery P5-L3（2026-09-07 设计 §2.6/§9；**2026-09-11 stuck 自动恢复批 P2 改造**）：
   * 运行时活挂节点的真重启——从磁盘 transcript 断点 rehydrate 续跑，复用
   * boot-recovery 的 ResumeContext / startNode(resume) 全链（设计 §3.1「同一恢复底座」）。
   *
   * **P2 改造（R-1=B：恢复动作放在终态之前）**：
   *   - 前置动作 = L2 transport abort（止血）+ [[suspendNode]]（**挂起**，不是终态化）
   *     ⇒ 节点到达这里时状态是 **`Running`**（本代次从未进过 `Cancelled`）；
   *   - CAS 前置条件随之**收敛为 `Running` 单一值**（旧值 `Cancelled | Running` 是
   *     「先终态化再救援」形态的遗留：留住它等于给「已取消」的节点一条复活路径，
   *     与「`cancelled` 是不可重激活的真终态」冲突 —— 收敛后该语义由类型面保证）；
   *   - `resumePrompt` 按**零产出 / 有产出**分支（§3.2）：零产出告知「上次尝试零产出、
   *     已中断」，有产出带「未落盘的副作用不可信」+ 已产出清单。**重放封顶**（R-2 的
   *     40 条）在 P3 落地，本段先构造分支文本。
   *
   * 竞态：CAS 失败（并发终态化 / 已被重排 / 已被资格回扫新鲜启动）安静返回 None；
   * transcript 缺失 → None（由调用方走一次上报，诚实失败原则）。返回重启的 nodeId。
   *
   * **`onResumed` 回调（硬约束② 修复，2026-09-11）**：CAS **接受瞬间**触发（在
   * `emitUpdated` / `FlowMapEventLog` / [[startNode]] **之前**），语义 = 「恢复已
   * 生效」。返回之后才写副作用是错的：`startNode` 同步等到被恢复会话的**整段终态**
   * （`runWithAgent` 的 `IO.race`）——恢复后的会话可能运行数十分钟，写点落在返回值
   * 之后 ⇒ 这段时间里「冷却窗基点 / 互斥点 2 指纹基线」不置位（独立复核实测：恢复
   * 成功后 25s 账本仍 `lastRecoveryAt=0`，冷却窗与互斥点 2 在运行期形同不存在）。
   * 回调**带默认值**以保源兼容（既有调用点零改动）；回调失败只留痕、**不中断恢复腿**；
   * CAS 被拒时不触发（负控：拒绝 ⇒ 零写入）。
   */
  def hardResumeNode(
    sessionId: String,
    anchor: Option[NodeEngine.RecoveryAnchor] = None,
    onResumed: IO[Unit] = IO.unit
  ): IO[Option[String]] =
    // 互斥点 1(a)（P3，设计 §3.4）：**恢复路径禁止唤醒冻结会话**。
    // 冻结态的自动出口是「用户输入唤醒」那条路——它会调 `resetCrossTurn` 清零跨轮
    // 指纹；恢复链若走到那里，就等于把 365 天 LoopGuard 冻结**绕过**。本批不改冻结面
    // （`AgentActor`/`LoopGuard` 是越界面），故把该纪律落成**显式断言**：会话仍处于
    // 冻结（`status==Frozen` / `frozenReason` 有值）⇒ 恢复**拒绝执行**。恢复的真实
    // 入口是 `startNode(resume)` → `NodeRunner` 起的**全新会话**（全新 actor，从不进
    // frozen behavior），这正是「不绕过」的结构性理由。
    resources.agentRegistry.get.map(_.get(sessionId)).flatMap { live =>
      if NodeEngine.frozenSessionBlocksResume(live) then
        logger
          .error(
            s"[hard-recovery] resume REFUSED for session $sessionId — the session is frozen " +
              s"(status=${live.map(_.status).getOrElse(AgentStatus.Idle)}, " +
              s"reason=${live.flatMap(_.frozenReason).getOrElse("-")}): waking a frozen session would " +
              "bypass the LoopGuard freeze via the user-input resetCrossTurn path (design §3.4 mutex 1a). " +
              "Node left as-is; manual reactivation only."
          )
          .as(None)
      else
        store.snapshot.flatMap { snap =>
          NodeEngine.findNodeForSession(snap, sessionId) match
            case None =>
              // R5（取消静默死锁修复批）：此前静默 `IO.pure(None)`——L3 6/6 零留痕的一个
              // 候选出口。留痕不改行为（仍返回 None）。
              // ③（#159/#176，2026-09-14）：**事件流留痕补齐**——WARN 之外再写一行
              // `hard-recovery` 事件（`nodeId` 字段承载会话 id，同 suspendNode 同款先例），
              // 使「L3 resume 被跳过」这一**承接失败路径**在 flow-map 事件面可 join。
              FlowMapEventLog.append(
                workspace,
                projectName,
                sessionId,
                "hard-recovery",
                s"no-owner session=$sessionId stage=l3-resume verdict=resume-skipped " +
                  "(session never bound to a node / node already archived)"
              ) *>
                logger
                  .warn(
                    s"[hard-recovery] no node owns session $sessionId — L3 resume skipped (session never bound to a node / node already archived)"
                  )
                  .as(None)
            case Some(n) =>
              resources.sessionStore.loadMessagesForSession(sessionId).attempt.flatMap {
                case Right(msgs) if msgs.nonEmpty =>
                  // R-2：transcript 重放封顶（默认 40 条）——直击「重发全量 ~250k 上下文」
                  // 的 token 放大面。截断事实进 resume prompt（否则模型会以为上下文完整）。
                  val (replay, capped) =
                    NodeEngine.capReplayMessages(msgs, nebflow.shared.Defaults.StuckRecoveryReplayMaxMsgs)
                  val ctx = NodeEngine.ResumeContext(
                    sessionId = sessionId,
                    recoveredMessages = replay,
                    resumePrompt = NodeEngine.nodeResumePrompt(projectName, n, replay.size, n.bgWait) +
                      NodeEngine.stuckResumeNote(anchor) +
                      (if capped then NodeEngine.replayCapNote(msgs.size, replay.size) else "")
                  )
                  store
                    .mutateWithResult { s =>
                      s.nodes.get(n.id) match
                        // P2（R-1=B）：CAS 前置条件**收敛为 `Running` 单一值**——挂起腿
                        // 不终态化，节点到达此处必为 Running。保留 `Cancelled` 等于给
                        // 「真终态」留一条复活后门，与「cancelled 不可重激活」的用户可
                        // 预期语义冲突（设计 §3.6 选项 B 的立论：消除冲突而不是绕过它）。
                        case Some(f) if f.status == NodeLifecycle.Running =>
                          (
                            s.copy(nodes =
                              s.nodes.updated(
                                n.id,
                                f.copy(
                                  status = NodeLifecycle.Pending,
                                  completedAt = None,
                                  ttlExpireAt = None,
                                  bgWait = None,
                                  // 未申报计时清零（noderpt 批 A 段）：L3 resume 是新会话，旧一轮的
                                  // 计时起点/拍数不继承（挂起出口清表不累计的同一纪律）。
                                  reportPendingSince = None,
                                  reportReminderCount = 0,
                                  // R5 方案 4：L3 中间态的回流占位（cancelNode(notify=false) 写入）
                                  // 在此归还——节点已复活，本代次的真实终态尚未发生，占位若留
                                  // 会把将来的 completion 回流持久去重吞掉（新一代静默死锁）。
                                  // Running → Pending 时清无可清（幂等零副作用）。
                                  notifySentAt = None
                                )
                              )
                            ),
                            true
                          )
                        case _ => (s, false)
                    }
                    .flatMap { (s, ok) =>
                      if !ok then
                        // R5：此前静默 `IO.pure(None)`——L3 6/6 零留痕的另一个候选出口。
                        // 留痕不改行为（节点保持原状，仍返回 None）。
                        logger
                          .error(
                            s"[hard-recovery] resume CAS rejected for node '${n.name}' (${n.id}) — status is no longer " +
                              s"Running (concurrent terminal / restart / sweep); node left as-is, manual re-trigger needed"
                          )
                          .as(None)
                      else
                        // 硬约束② 修复（2026-09-11）：**「恢复已接受」回调在 CAS 接受瞬间
                        // 触发**，位置在 `startNode` **之前**——`startNode` 会同步阻塞到被恢复
                        // 会话终态，回调若放在它的返回值之后，「冷却窗基点 / 互斥点 2 基线」
                        // 就会在恢复后会话的整段运行期缺位。回调失败只 warn（不影响恢复腿）。
                        onResumed.handleErrorWith(e =>
                          logger.warn(
                            s"[hard-recovery] onResumed callback failed for session $sessionId: ${e.getMessage}"
                          )
                        ) *>
                          s.nodes.get(n.id).traverse_(emitUpdated) *>
                          FlowMapEventLog.append(
                            workspace,
                            projectName,
                            n.id,
                            "hard-recovery",
                            s"resumed from stuck (session=$sessionId msgs=${msgs.size})"
                          ) *>
                          logger.info(
                            s"[hard-recovery] node '${n.name}' (${n.id}) resumed from transcript breakpoint (session=$sessionId)"
                          ) *>
                          startNode(n.id, Some(ctx)).as(Some(n.id))
                    }
                case _ =>
                  logger
                    .warn(
                      s"[hard-recovery] no readable transcript for session $sessionId — node left terminal, manual re-trigger needed"
                    )
                    .as(None)
              }
        }
    }

  /**
   * R5 **方案 4**（取消静默死锁修复批 2026-09-10，作者裁定「L3 resume 失败 → 改判
   * failed」）：L3 硬恢复的 resume 未生效 ⇒ 节点由 Cancelled **改判 failed**
   * （走既有 [[failNode]] 全链：result=原因 / nodeUpdated / 失败回流 / D5 零结算
   * 停等留痕 / failed 侧 barrier 检查）。这是 [[hardResumeNode]] 返回 None 的引擎侧
   * 唯一收口，也是**失败腿唯一的终局写点**。
   *
   * 为什么是 failed 而不是「留在 cancelled + 按 R4 摘除」（方案 3）：语义上
   * 「引擎接管失败」是失败而非取消；能力上 failed 可经 NodeEdit 重激活（cancelled
   * 不可），把「上游修好 → 重跑 → 停等下游自动续跑」这条 D5 首选恢复路径还给用户。
   *
   * 三条同步面（设计 §5 影响面裁定）：
   *   ① **不摘除 / 不打「待承接」标**：failed = D5 零结算停等（下游保持 in 完整、
   *      barrier 继续等上游 reactivate）——与失败的 R4「自动摘除扩展到 failed」提案
   *      在同一裁定里被**永久拒绝**的理由一致（摘除会掐死 reactivate 恢复路径）。
   *      `cancelNode` / [[detachCancelledUpstream]] 的永久拒绝注释与 failed 侧零摘除
   *      纪律**不回退**：本方法不调用二者。
   *   ② **回流恰好一次且语义为 failed**：L3 路径的中间态 Cancelled 不发通知
   *      （`cancelNode(notify = false)` 占位）——这里先
   *      [[DispatchNotify.releaseTerminalNotify]] 归还占位，再经 [[failNode]] →
   *      `deliverFailed` → `notifyTerminal(Failed)` 发**唯一**一条通知。⇒ 双向坏形态
   *      都被结构性排除：不会「同节点 cancelled + failed 双份回流」（cancelled 那条
   *      从未发出），也不会「零回流」（failed 这条经既有去重链正常发出；即便预算
   *      耗尽，[[DispatchNotify]] 也会 markSent 止重扫）。
   *   ③ **审计可 join、不改写历史**：同一 nodeId 上按 `ts` 顺序可对账的链条 =
   *      `FlowMapEventLog` 的 `cancelled`（中间态，含 source 与原因）→ `FlowMapEventLog`
   *      的 `hard-recovery … L3 resume FAILED … re-judged as failed`（本方法追加）→
   *      `FlowMapEventLog` 的 `dispatch-notify`（`triggered: failed → dispatcher`，回流面）
   *      + WS `nodeUpdated(Failed)`（对外可见终态帧，非事件日志行）。**已写事件不修订**：
   *      `FlowMapEventLog.append` 只有追加面无 update/delete，bridge 侧历史 `cancelled`
   *      行**永不改写**，读者按 ts 顺序即得「被取消 → 恢复失败 → 改判失败」的完整链。
   *
   * `chainArchivable` 结论（[[FlowMapStore.chainArchivable]]，见其 failed 判据）：
   * **无需改动**——failed 成员要求 `notifySentAt.isDefined`，改判后的节点与任何 failed 节点
   * 的归档资格完全一致：回流发出 → `markSent`；failed 预算耗尽 → 同样 `markSent` 止重扫。
   * 唯一「暂时不满足」的形态 = 失败通知被失败窗口抑制（`Suppressed`/`CooldownOn` 有意
   * **不** markSent）——此时该节点（连同其拓扑分量：归档判据是**链级** [[FlowMapStore.chainArchivable]]）
   * 留在主图，等 `FailedCooldownMs`（30min，阈值 5 次/10min）冷却结束后的 30s 补投扫描
   * 必然补投并 `markSent` ⇒ **不会永留主图**（仅当进程在冷却窗口内永久停机才滞留，那是既有
   * failed 链语义，非本路径引入）。「引擎接管失败且通知从未发出」同样不是本路径的新形态。
   *
   * 幂等：状态守卫（非 Cancelled → 零动作）+ failed 回流/事件/告警各自去重。
   *
   * **查无节点的出口（U3/B 修复，2026-09-11 作者拍板）**：旧口径只 ERROR、**零动作**
   * ⇒ 既不摘除也不登记「待承接」，下游 barrier 永久停等且无任何可见账（09-11 全天
   * 15 例 L3 全数落此出口）。现改为 [[lateDetachUnlocatableSession]] 收口——见该方法
   * 的「哪条路径摘除、哪条不摘除」逐条说明。**「已能定位节点」分支（本方法下面
   * `Some(n)` 两支）行为逐字不变**：改判 failed 全链 + D5 零结算停等、不摘除。
   */
  def settleFailedHardResume(sessionId: String): IO[Unit] =
    store.snapshot.flatMap { snap =>
      NodeEngine.findNodeForSession(snap, sessionId) match
        case None =>
          lateDetachUnlocatableSession(sessionId)
        case Some(n) if n.status != NodeLifecycle.Cancelled =>
          logger.info(
            s"[hard-recovery] L3 resume failed for session $sessionId but node '${n.name}' (${n.id}) is ${n.status} — no fallback action needed"
          )
        case Some(n) =>
          // 失败原因 = sessionId + 「L3 hard-recovery resume failed」语义 + 恢复指引
          // （节点 result 是分发器/前端读到的权威文本，必须自解释）。
          val err =
            s"L3 hard-recovery resume failed (session=$sessionId) — the engine could not revive this node from " +
              "the on-disk transcript breakpoint (no readable transcript / resume CAS rejected); " +
              "node re-judged as failed by the L3 hard-recovery chain. Downstream keeps waiting (D5 zero-settlement): " +
              "reactivate the upstream (NodeEdit) or rewire the graph to recover."
          val keptTargets = n.out.map(_.to).filterNot(_ == OutEdge.RootTarget)
          dispatchNotify.releaseTerminalNotify(n.id) *>
            FlowMapEventLog.append(
              workspace,
              projectName,
              n.id,
              "hard-recovery",
              s"L3 resume FAILED (session=$sessionId) — node re-judged as failed (was cancelled by the L3 bridge); " +
                "out kept (no detach), successors keep waiting (D5 zero-settlement)" +
                (if keptTargets.nonEmpty then s"; downstream still in=${keptTargets.mkString(",")}" else "")
            ) *>
            failNode(n.id, err)
    }
end NodeRecovery
