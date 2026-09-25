/* 从 NodeEngine 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.project

import cats.effect.*
import cats.syntax.all.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.PluginMcpManager
import nebflow.core.tools.BgTaskRegistry
import nebflow.shared.Message

private[project] trait NodeLoopRunner:
  self: NodeEngine =>

  /**
   * Loop 会话常驻桥行为（与 runWithAgent bridge 同构，但**每轮完成不停止**——只
   * complete 本轮 Deferred、保持存活等下一轮注入）。失败/取消/会话死亡 → complete
   * Left（本轮即终，spawnAndRunLoop guarantee 统一销毁）。不持会话内状态（状态全在
   * 共享 round Ref），故每轮简单递归构造新 Behavior 即可（无 lazy val 循环引用）。
   */
  private def loopBridge(
    round: Ref[IO, Deferred[IO, Either[String, List[Message]]]],
    sessionId: String,
    sessionName: String
  ): Behavior[AgentEvent] =
    new Behavior[AgentEvent]:
      def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
        event match
          case AgentEvent.Completed(_, messages) =>
            round.get.flatMap(_.complete(Right(messages)).attempt.void).as(loopBridge(round, sessionId, sessionName))
          case AgentEvent.Failed(_, err) =>
            round.get
              .flatMap(_.complete(Left(Option(err.message).getOrElse("unknown error"))).attempt.void)
              .as(loopBridge(round, sessionId, sessionName))
          case AgentEvent.Cancelled(_, reason) =>
            round.get
              .flatMap(_.complete(Left(s"cancelled: $reason")).attempt.void)
              .as(loopBridge(round, sessionId, sessionName))
      override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
        signal match
          case SystemSignal.Terminated(_) =>
            logger.warn(
              s"Loop session '$sessionId' ($sessionName) terminated WITHOUT a terminal event — finalize round as failed"
            )
            round.get
              .flatMap(
                _.complete(Left(s"loop session '${sessionId}' terminated without a terminal event")).attempt.void
              )
              .as(loopBridge(round, sessionId, sessionName))

  /**
   * spawn 单个 Loop 会话（agent + 常驻桥 + registry 注册）。MCP grant 由调用方
   * （spawnAndRunLoop）分别对 worker/verify acquire 后传入（各自独立引用记账）。
   * baseDef=经 SchemePolicy 名称策略的 AgentDef；prepared=已解析的插件分配。
   */
  private[project] def spawnLoopSession(
    baseDef: AgentDef,
    prepared: NodeEngine.PluginPreparation,
    grant: PluginMcpManager.Grant,
    sessionId: String,
    sessionName: String,
    projectRoot: String,
    initialMessages: List[Message] = Nil,
    /**
     * TaskBoard 批 2（§1d）：loop 会话引擎侧节点身份（所属 NodeDef.id——worker/
     * verify 同属该 loop 节点，TaskBoard 权限矩阵与普通节点同面）。
     */
    flowNodeId: Option[String] = None,
    /**
     * 节点角色（nrloop 一期 2026-09-12，B1 透传链第一段 loop 支）：所属
     * `NodeDef.role`——worker/verify 同属该 loop 节点（设计 §3.2 表：旧 loop
     * 双会话置 `Some(node.role)`，与普通节点同源口径）。详见
     * SessionContext.flowNodeRole。
     */
    flowNodeRole: Option[String] = None,
    /**
     * D6 批 F1（G9 路径 a）：loop 节点人类可读名（worker/verify 同名——
     * 提问归因到节点而非会话分身），AskUser payload nodeName 字段来源。
     */
    flowNodeName: Option[String] = None,
    /**
     * 链级抽象 P2（§9.2 项 5）：loop 会话链身份（worker/verify 同属该 loop
     * 节点 → 同一 chainId 快照）。详见 SessionContext.flowChainId。
     */
    flowChainId: Option[String] = None
  ): IO[LoopSession] =
    for
      initD <- Deferred[IO, Either[String, List[Message]]]
      round = Ref.unsafe[IO, Deferred[IO, Either[String, List[Message]]]](initD)
      agentDef = baseDef.copy(pluginMcpServers = grant.serverIds, pluginTools = prepared.builtinTools)
      ref <- NodeRunner.spawnAgentActor(
        system,
        NodeRunner.SpawnParams(
          agentDef = agentDef,
          resources = resources,
          sessionId = sessionId,
          sessionName = sessionName,
          depth = 1,
          parentRef = None,
          // 与节点同款：project 注入 agentStart 帧（LoopNode worker/verify 会话
          // 亦属 Project 域，面板行同标准标注项目名）。
          wsSend = NodeRunner.routeSubagentWsSend(wsSendFn, rootSessionId, sessionId, Some(projectName)),
          projectRoot = Some(projectRoot),
          safetyMode = "confirm-edits",
          rootSessionId = rootSessionId,
          isFlowNode = true,
          // TaskBoard 批 2（§1d）：loop 会话同置节点身份 + 项目上下文（普通节点
          // runWithAgent 同款——worker/verify 更新自己工单与普通节点同权限面）。
          flowNodeId = flowNodeId,
          flowNodeRole = flowNodeRole,
          projectName = Some(projectName),
          flowNodeName = flowNodeName,
          // 链级抽象 P2（§9.2 项 5）：loop worker/verify 会话链身份与 loop 节点同源
          // （同一 spawn 时刻快照）——工具面/文件名尾溯源归属口径与普通节点恒同。
          flowChainId = flowChainId,
          sandboxEnabled = true,
          sandboxRoot = Some(workspace),
          // B5 缺口②（作者 2026-09-17 M-1 裁定）：loop 节点 worker/verify 会话与普通
          // 节点同口径——会话初始 cwd = 座椅（worktree 节点）；非 worktree 同源零变化。
          sessionCwd = Some(projectRoot),
          // 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：loop worker/verify 属项目
          // 节点会话 ⇒ AGENTS.md 注入判据置位（与普通节点 runWithAgent 同款）。
          projectSession = true,
          initialMessages = initialMessages
        )
      )
      bridgeRef <- system.spawn(
        Behaviors.setup[AgentEvent] { bctx =>
          bctx.watch(ref) *> IO(loopBridge(round, sessionId, sessionName))
        },
        s"loopbridge-${sessionId.take(8)}"
      )
      _ <- resources.agentRegistry.update(
        _ + (sessionId -> AgentRecord(
          sessionId,
          ref,
          AgentKind.Flow,
          rootSessionId,
          startedAt = System.currentTimeMillis(),
          lastActivityMs = System.currentTimeMillis(),
          supervisorRef = Some(bridgeRef),
          project = Some(projectName), // 恢复路径项目徽标（与节点/分发器同标准）
          displayName = Some(sessionName)
        ))
      )
    yield LoopSession(sessionId, ref, bridgeRef, round)

  /**
   * 翻转 + 在飞登记（LoopNode 批复用；与 runWithAgent 内联翻转同语义——CAS 守卫
   * Done/LostRace/Aborted 三态、running/nodeSessions 条件登记、start-aborted 事件。
   * 独立实现避免改动既有 runWithAgent（并发分支保护，同文件不同 hunk 收敛）。
   * crash-recovery 批 D1/裁定③：翻转事务落 sessionRef（worker 主会话）+
   * sessionRefVerify（verify 会话，仅 loop 有值）——loop 崩溃残留据此双会话续接。
   */
  private[project] def flipToRunning(
    node: NodeDef,
    cancelSig: Deferred[IO, Unit],
    sessionId: String,
    nodeId: String,
    nodeName: String,
    verifySessionId: Option[String] = None
  ): IO[Unit] =
    for
      _ <- running.modify { m => if m.contains(nodeId) then (m, ()) else (m + (nodeId -> cancelSig), ()) }
      _ <- nodeSessions.update(_ + (nodeId -> sessionId))
      now <- IO(System.currentTimeMillis())
      (flipped, flipOutcome) <- store.mutateWithResult { s =>
        s.nodes.get(nodeId) match
          case Some(fresh)
              if (fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Wiring)
                && !fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            (
              s.copy(nodes =
                s.nodes.updated(
                  nodeId,
                  fresh.copy(
                    status = NodeLifecycle.Running,
                    startedAt = Some(now),
                    sessionRef = Some(sessionId),
                    sessionRefVerify = verifySessionId,
                    // 销毁窗口撤销（noderpt 批 B 段，与 runWithAgent 翻转同点同语义）：
                    // Loop 节点重激活/重跑 ⇒ 旧窗口作废（绝不按旧计划销毁在跑的进程）。
                    destroyAt = None
                  )
                )
              ),
              NodeEngine.FlipOutcome.Done
            )
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            (s, NodeEngine.FlipOutcome.LostRace)
          case Some(fresh) if fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            (s, NodeEngine.FlipOutcome.Aborted("in-barrier not settled (concurrent rewiring)"))
          case Some(fresh) =>
            (s, NodeEngine.FlipOutcome.Aborted(s"state changed to '${fresh.status}' before flip (concurrent finalize)"))
          case None =>
            (s, NodeEngine.FlipOutcome.Aborted("node vanished"))
      }
      _ <- flipOutcome match
        case NodeEngine.FlipOutcome.Done =>
          running.update(m => m + (nodeId -> cancelSig)) *>
            // 销毁窗口撤销第二步（noderpt 批 B 段）：上一轮双会话解出禁 spawn 表。
            (node.sessionRef.toList ++ node.sessionRefVerify.toList).traverse_(BgTaskRegistry.reopenSession) *>
            flipped.nodes
              .get(nodeId)
              .traverse_(n =>
                emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(n, System.currentTimeMillis()))
              )
        case NodeEngine.FlipOutcome.LostRace =>
          running.modify { case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ()); case m => (m, ()) } *>
            nodeSessions.update(_ - nodeId) *> IO.raiseError(NodeEngine.StartRaceLost(nodeId))
        case NodeEngine.FlipOutcome.Aborted(reason) =>
          running.modify { case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ()); case m => (m, ()) } *>
            nodeSessions.update(_ - nodeId) *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "start-aborted", s"start aborted: $reason") *>
            IO.raiseError(new RuntimeException(s"Node '$nodeName' ($nodeId) start aborted — $reason"))
    yield ()

  /**
   * 终态双会话销毁（裁定 B）：停 agent + 停桥 + 注销 registry。MCP release 与
   * running/nodeSessions 清理在 spawnAndRunLoop 的 guarantee 内一并做（此处只管会话）。
   *
   * noderpt 批 B 段（2026-09-11 作者裁定 ④）**同批修**两处既有缺口：
   *   ① **申报槽清理**：`NodeReportRegistry.remove(worker) + remove(verify)` —— 此前双会话
   *      终态不 remove ⇒ Loop 的 `node_report` 申报残留（设计稿 §2 缺口清单第 6 条）。
   *   ② **双会话的进程/任务收殓**：此前本函数只停 actor，**从不**调用
   *      `BgTaskRegistry.reclaimSession` ⇒ worker/verify 名下的后台进程、在册任务与输出
   *      留存区永不收殓（③ 补的对称化只覆盖普通节点）。
   *      **时点按作者裁定「Loop 终态同样走 30 分钟窗口口径（与③一致）」**——收殓动作集合
   *      与③逐字相同（同一个 `reclaimSession`），但由窗口到期执行：
   *      · 登记 = 本函数的 `scheduleDestroy(nodeId, List(worker, verify), …)`（窗口内禁
   *        这两个 sessionId 的新 spawn，与普通节点同表同判据）；
   *      · 到点 = `sweepDestroyWindows` → `destroyNodeSessions` → `destroyTargetSessions`
   *        取 `sessionRef`（= worker）**与** `sessionRefVerify`（= verify）**两个**会话逐个
   *        `reclaimSession`（杀进程树 + 注销 registry + 逐条 `finalizeTask` + 释放
   *        `ShellSession.sessions` 条目 + WS `backgroundTaskUpdate(status="cancelled")` 帧）。
   *      ⇒ 本函数**不**即时 reclaim：就地收殓会让窗口口径对 Loop 失效（与本段硬口径冲突），
   *      故把「双会话收殓」落在窗口执行体上，并由 `LoopNodeSpec` ⑦（双会话 + 申报槽）与
   *      `NodeBgReclaimSpec` R1/R7（窗口/到点/幂等）钉死。
   *      停 actor / 停桥 / 注销 registry 保持不变（会话实体拆除不随窗口变——窗口保的是
   *      进程与任务（可取证面），不是已死的 agent actor）。
   */
  private[project] def destroyLoopSessions(nodeId: String, worker: LoopSession, verify: LoopSession): IO[Unit] =
    scheduleDestroy(nodeId, List(worker.sessionId, verify.sessionId), "loop-terminal") *>
      NodeReportRegistry.remove(worker.sessionId) *>
      NodeReportRegistry.remove(verify.sessionId) *>
      resources.agentRegistry.update(_ - worker.sessionId - verify.sessionId) *>
      system.stop(worker.agentRef).handleErrorWith(_ => IO.unit) *>
      system.stop(worker.bridgeRef).handleErrorWith(_ => IO.unit) *>
      system.stop(verify.agentRef).handleErrorWith(_ => IO.unit) *>
      system.stop(verify.bridgeRef).handleErrorWith(_ => IO.unit).void

  /**
   * runLoopNode 主编排（§2.2 状态机）：worker/verify 双会话迭代。spawnAndRunLoop
   * 已翻转+spawn+装好两会话后调用；终态（PASS/failed/cancelled/blocked/达 K）落
   * 终态化后即返回，会话清理由调用方 guarantee 兜底。
   * crash-recovery 批裁定③：resume=Some 时从崩溃时持久断点（loopRound/loopPhase）
   * 续跑——phase=worker → 本轮 worker 注入换 resume prompt（transcript 已水合，
   * 从最后持久轮边界续作），完成后照常进 verify；phase=verify → 以 worker transcript
   * 末条 assistant 文本重建本轮 verify 输入（标注崩溃续接）注入续验。loopRound 语义
   * 保持连续（续跑轮号 = 崩溃时轮号，PASS 记同一轮，FAIL 打回 +1）。
   */
  private[project] def runLoopNode(
    node: NodeDef,
    worker: LoopSession,
    verify: LoopSession,
    workerFirstInput: String,
    cancelSig: Deferred[IO, Unit],
    resume: Option[NodeEngine.ResumeContext] = None
  ): IO[Unit] =
    val nodeId = node.id
    val loopCfg = node.loop.get
    val maxRounds = loopCfg.maxRounds

    /**
     * 单会话单轮注入：置新 Deferred → 发 UserInput → race(本轮产出, node cancelSig)。
     * cancelSig 赢 → Left(cancelled)（整 Loop cancelled），否则返回本轮产物。
     */
    def step(ses: LoopSession, input: String): IO[Either[String, List[Message]]] =
      for
        d <- Deferred[IO, Either[String, List[Message]]]
        _ <- ses.round.set(d)
        _ <- (ses.agentRef ! AgentCommand.UserInput(text = input, replyTo = Some(ses.bridgeRef))).void
        r <- IO.race(d.get, cancelSig.get).map {
          case Left(res) => res
          case Right(_) => Left("cancelled by NodeCancel")
        }
      yield r

    /** 轮次/阶段状态落库 + WS nodeUpdated（NodePayload 同构载荷，NodeList/前端可见）。 */
    def goto(nodeId: String, phase: String, round: Int): IO[Unit] =
      store
        .mutate { st =>
          st.nodes.get(nodeId) match
            case Some(f) if f.loop.isDefined =>
              st.copy(nodes = st.nodes.updated(nodeId, f.copy(loopPhase = Some(phase), loopRound = round)))
            case _ => st
        }
        .flatMap(s => s.nodes.get(nodeId).traverse_(emitUpdated))

    /** 最近一次 FAIL verdict 摘要落库 + WS（loopLastVerdict，前端打回原因可见）。 */
    /**
     * verdict 落库「落地判词」版（#239② ⑤-(1)）：`true` = 本次**真的**写下了 loop 判词
     * （节点存在且是 loop 节点）；`false` = 走了 no-op 支（节点已消失 / 非 loop 节点）。
     * 判据与分支与修前逐字一致（同 #239① 对终态写族的手法：只把既有判词向上回报）。
     */
    def setVerdictR(nodeId: String, summary: String): IO[Boolean] =
      store
        .mutateWithResult { st =>
          st.nodes.get(nodeId) match
            case Some(f) if f.loop.isDefined =>
              val updated = f.copy(loopLastVerdict = Some(summary))
              (st.copy(nodes = st.nodes.updated(nodeId, updated)), Some(updated))
            case _ => (st, None)
        }
        .flatMap { case (s, opt) => s.nodes.get(nodeId).traverse_(emitUpdated).as(opt.isDefined) }

    /** `IO[Unit]` 门面：无申报消费的既有调用点零改动（同 #239① 的 `failNode`/`blockedNode` 门面）。 */
    def setVerdict(nodeId: String, summary: String): IO[Unit] =
      setVerdictR(nodeId, summary).void

    /**
     * 执行层失败/取消分流（§2.4）：cancelled → cancelNode（整 Loop cancelled）；
     * 其余（LLM 错误/agent 消失/LoopGuard L1 终止该 turn）→ 整 Loop failed（failNode）。
     * R2/R7（取消静默死锁修复批）：Loop 会话的取消同样带原因 + 触发源——err 文本已
     * 含 `cancelled` 特征，原样作为 reason 落盘（[[CancelSource]] 两态分类同桥口径）。
     */
    def failOrCancel(nodeId: String, err: String): IO[Unit] =
      if err.contains("cancelled") then cancelNode(nodeId, err, CancelSource.classify(err))
      else failNode(nodeId, err)

    /**
     * verify 首轮输入构建（模板三全文：原始任务 + 上游段 + 待验证产出 + 验证清单 +
     * 验证协议脚注）；轮 N≥2 用模板三短段（持久上下文已持有），见 loopVerifyInput。
     */
    def verifyRound1Input(workerText: String): IO[String] =
      node.in
        .traverse { upId =>
          store.findNode(upId).map {
            case Some(up) => up.result.map(res => s"=== Node ${up.name} ===\n$res")
            case None => None
          }
        }
        .map { upstream =>
          NodeEngine.loopVerifyInput(
            1,
            node.task.getOrElse(""),
            upstream.flatten.mkString("\n"),
            workerText,
            loopCfg.verifyTask
          )
        }

    /**
     * verify 产出裁决（PASS 投递 worker 产出 / FAIL 打回 +1 / BLOCKED → Loop 级
     * blocked）——loopRound 正常轮与 verify 相位崩溃续跑（resumeVerifyRound）共用。
     * blocked 结构化信号批（20260909 spec §5.2 #8；同日作者裁定泛化 NodeReport
     * 统一三语义）：verify 会话完成时点先 drain 登记表——工具申报（协议事实）
     * 按类别分流到既有链：pass 与 VERDICT: PASS 同链（投递 worker 产出）、fail
     * 与 VERDICT: FAIL 同链（detail=打回意见 / suggestion=通过标准，打回 worker）、
     * blocked → Loop 级 blocked；未申报走文本锚定降级面（行为零变化）。
     */
    def handleVerify(roundNum: Int, wText: String, vMsgs: List[Message]): IO[Unit] =
      val vText = extractLastAssistantText(vMsgs)
      val R = nebflow.core.tools.NodeReportToolDef
      // #239①：两条**终态写**支（pass → completed / blocked → Loop 级 blocked）的申报
      // 消费挂落地判词；fail 支把申报消费进「判词 + 回边返工」（非终态写，见
      // [[compensateUnconsumedReport]] 的边界声明），text 锚定支无申报（declared=None）
      // 故无补偿面。
      NodeReportRegistry.drain(verify.sessionId).flatMap { declared =>
        declared match
          case Some(fb) if R.isPass(fb.category) =>
            // verify 工具申报 pass → 投递 worker 产出（VERDICT: PASS 同链）
            consumeReport(nodeId, verify.sessionId, declared)(completeNodeR(nodeId, wText)).void
          case Some(fb) if R.isFail(fb.category) =>
            // verify 工具申报 fail → 打回 worker（VERDICT: FAIL 同链）：
            // detail = 打回意见（空则占位），suggestion = 通过标准。
            // #239② ⑤-(1)：本支是**非终态**消费（消费进 `loopLastVerdict` + 回边返工）——
            // 落地判词改由 `setVerdictR` 回报（原 `setVerdict` 的 no-op 分支：节点已消失 /
            // 非 loop 节点 ⇒ 判词未落地 ⇒ 申报全文走同一补偿写回）；控制流零变化（补偿后
            // 照常回边返工，与修前逐字同序）。
            val issues = List(if fb.detail.trim.isEmpty then VerdictReader.PlaceholderIssues else fb.detail.trim)
            val f = VerdictReader.Verdict.Fail(issues, fb.suggestion)
            consumeReport(nodeId, verify.sessionId, declared)(
              setVerdictR(nodeId, VerdictReader.renderFailSummary(f))
            ) *>
              loopRound(roundNum + 1, Some(f))
          case Some(fb) =>
            // verify 工具申报 blocked → Loop 级 blocked
            consumeReport(nodeId, verify.sessionId, declared)(blockedNodeR(nodeId, fb, finalText = Some(vText))).void
          case None =>
            BlockedReader.parse(vText) match
              case Some(fb) => blockedNode(nodeId, fb) // verify 申告任务无法验证 → Loop 级 blocked
              case None =>
                VerdictReader.parse(vText) match
                  case VerdictReader.Verdict.Pass =>
                    // PASS：投递 worker 最终产出原文（§2.2 裁定建议 a）
                    completeNodeR(nodeId, wText).void
                  case f: VerdictReader.Verdict.Fail =>
                    // FAIL：打回 worker（同会话注入意见），轮 +1，至 K
                    setVerdict(nodeId, VerdictReader.renderFailSummary(f)) *>
                      loopRound(roundNum + 1, Some(f))
      }
    end handleVerify

    /** roundNum 轮的 verify 输入构建（首轮模板三全文，N≥2 短段）。 */
    def verifyInputFor(roundNum: Int, wText: String): IO[String] =
      if roundNum <= 1 then verifyRound1Input(wText)
      else IO.pure(NodeEngine.loopVerifyInput(roundNum, "", "", wText, ""))

    def loopRound(
      roundNum: Int,
      lastFail: Option[VerdictReader.Verdict.Fail],
      overrideWorkerInput: Option[String] = None
    ): IO[Unit] =
      if roundNum > maxRounds then
        // 达 K 轮仍未 PASS → 轮级兜底终态（§2.5 划界：maxRounds 是轮帽，非内容检测）
        val suffix = lastFail.map(f => s" — last verdict: ${VerdictReader.renderFailSummary(f)}").getOrElse("")
        failNode(nodeId, s"loop reached maxRounds=${maxRounds} without passing verification$suffix")
      else
        // overrideWorkerInput（crash-recovery 批）：worker 相位崩溃续跑时本轮 worker
        // 输入 = resume prompt（上下文已在水合 transcript 内）；递归轮恒 None。
        val workerInput = overrideWorkerInput.getOrElse {
          if roundNum == 1 then workerFirstInput
          else
            val f = lastFail.getOrElse(VerdictReader.Verdict.Fail(Nil, ""))
            NodeEngine.loopReworkInput(roundNum, f.issues, f.requirements)
        }
        for
          _ <- goto(nodeId, NodeEngine.LoopPhaseWorker, roundNum)
          wOut <- step(worker, workerInput)
          _ <- wOut match
            case Left(err) => failOrCancel(nodeId, s"worker round $roundNum: $err")
            case Right(wMsgs) =>
              val wText = extractLastAssistantText(wMsgs)
              // blocked 结构化信号批（20260909 spec §5.2 #8；同日作者裁定泛化
              // NodeReport 统一三语义）：worker 会话完成时点先 drain 登记表
              // （工具申报按类别分流：blocked → Loop 级 blocked；fail → 既有
              // failed 链；pass 与无申报同链照常进 verify 裁决。spawnLoopSession
              // 会话已带 flowNodeId——工具天然可达）；未申报走文本锚定降级面
              // （行为零变化）。
              val R = nebflow.core.tools.NodeReportToolDef
              // #239①：两条**终态写**支的申报消费挂落地判词——写被拒（节点已消失 /
              // 状态已变）时把申报全文补偿写回审计流，不再静默蒸发。
              NodeReportRegistry.drain(worker.sessionId).flatMap { declared =>
                declared match
                  case Some(fb) if R.isFail(fb.category) =>
                    // worker 工具申报 fail → 既有 failed 链
                    consumeReport(nodeId, worker.sessionId, declared)(failNodeR(nodeId, R.renderFail(fb))).void
                  case Some(fb) if R.isBlockedSemantics(fb.category) =>
                    // worker 工具申报 blocked → Loop 级 blocked
                    consumeReport(nodeId, worker.sessionId, declared)(
                      blockedNodeR(nodeId, fb, finalText = Some(wText))
                    ).void
                  case _ =>
                    // 无申报（None）或 pass/finish 申报：本轮产出照常进 verify 裁决
                    // （pass/finish = worker 正式声明本轮完成，与无申报同链零新链）。
                    // #239② ⑤-(1)：这支是**非终态**消费——`drain` 已取走申报、日志已记
                    // consume 行，但没有任何终态写 ⇒ 留一行审计（可 grep）说明申报被谁消费
                    // 掉了（`declared=None` 零动作）。
                    for
                      _ <- noteNonTerminalConsumption(nodeId, worker.sessionId, declared, "loop-worker-forward")
                      vIn <- verifyInputFor(roundNum, wText)
                      _ <- goto(nodeId, NodeEngine.LoopPhaseVerify, roundNum)
                      vOut <- step(verify, vIn)
                      _ <- vOut match
                        case Left(err) => failOrCancel(nodeId, s"verify round $roundNum: $err")
                        case Right(vMsgs) => handleVerify(roundNum, wText, vMsgs)
                    yield ()
              }
        yield ()
        end for

    /**
     * verify 相位崩溃续跑（裁定③）：以 worker transcript 末条 assistant 文本重建本轮
     * verify 输入（附崩溃续接标注——旧输入可能未持久/已消费，重注入是操作侧消息），
     * 注入水合后的 verify 会话续验；verdict 走 handleVerify 共用裁决（loopRound 语义
     * 连续：PASS 记崩溃轮，FAIL 打回 +1）。
     */
    def resumeVerifyRound(r: NodeEngine.ResumeContext): IO[Unit] =
      val roundNum = math.max(r.loopResumeRound, 1)
      val wText = extractLastAssistantText(r.recoveredMessages)
      for
        vBase <- verifyInputFor(roundNum, wText)
        _ <- goto(nodeId, NodeEngine.LoopPhaseVerify, roundNum)
        vOut <- step(verify, NodeEngine.loopVerifyResumeAnnotation(roundNum) + "\n\n" + vBase)
        _ <- vOut match
          case Left(err) => failOrCancel(nodeId, s"verify round $roundNum (resumed): $err")
          case Right(vMsgs) => handleVerify(roundNum, wText, vMsgs)
      yield ()

    resume match
      case None => loopRound(1, None)
      case Some(r) =>
        val roundNum = math.max(r.loopResumeRound, 1)
        r.loopResumePhase match
          case Some(NodeEngine.LoopPhaseVerify) => resumeVerifyRound(r)
          case _ =>
            // worker 相位（或轮前崩溃 loopRound=0/phase=None）：本轮 worker 输入 =
            // resume prompt，完成后照常进 verify 轮。
            loopRound(roundNum, None, Some(r.resumePrompt))
  end runLoopNode

  // ── verdict 选通与 loop 预算熔断（nrloop 一期 2026-09-12；执行腿二期 2026-09-14）──
  //
  // 语义轴分离的引擎侧落点（设计 §3.1 / §3.3 #6–#8 / §3.5–§3.6）：
  //   · verifier 的 `fail` = **verdict**（被判定对象不合格），**不是**节点失败 ⇒
  //     本节点照常 `completed`（消除 0911 secstore-audit 被误判 failed 的机制根因）；
  //   · 选通 = `(fail)<worker>:loop` **控制边**（作者裁定 R5(a)）：图上不连、不写 in
  //     镜像、不进 barrier/deliveredTo，由引擎显式驱动——**执行腿 `reloopTo` 已落地
  //     （二期 2026-09-14）**：预算内 ⇒ 目标节点重激活 + 返工输入注入 + `startNode`
  //     重跑（回边不止「登记意图」，而是真实投递）；
  //   · 预算 = 轮次帽（verifier.loopRound）+ 时间帽（目标节点 loopStartedAt），
  //     阈值全 prop 化（`Defaults.LoopMaxRounds` / `Defaults.LoopMaxWallClockMs`）；
  //     **轮次维在「本轮消费后」判定**（`consumed = v.loopRound + 1`）——`3/3` 是
  //     熔断轮而非再派发轮（旧口径用消费前的计数 ⇒ 3/3 仍派发、要等第 4 次判词才
  //     熔断；执行腿停摆时第 4 次判词永不到来 ⇒ 静默冻结，2026-09-14 修复）；
  //   · 熔断动作（§3.6 三档统一）：不投任何 `:loop` 边 / verifier 终态化 `failed`
  //     且 result 写 reason + 计量数字 / `FlowMapEventLog` 记 `loop-budget` /
  //     失败通知（经 failNode 尾部的 dispatchNotify，与既有 failed 链同源）。

  /** verdict 取值（`NodeDef.lastVerdict`；verifier 专用）。 */
  val VerdictPass: String = "pass"
  val VerdictFail: String = "fail"

  /** loop 预算事件类型：熔断（`circuitBreakLoop`）。 */
  val LoopBudgetEventType: String = "loop-budget"

  /**
   * loop 轮次事件类型：每次 fail 选通消费一轮。
   * 一期语义 = 「本轮 fail 已消费、verifier 照常 completed、重跑意图已登记」；**二期
   * （2026-09-14）执行腿落地后本事件承担「已驱动第 N 轮重跑」**——summary 里的
   * `deferred=execution-leg-phase-2` 标注（静默 defer 的唯一现场痕迹）随之撤除，
   * 改为逐轮记录投递实况（`dispatched=<target>` / `skipped=<原因>`），任何一轮都
   * 不得既无投递又无留痕。
   */
  val LoopRoundEventType: String = "loop-round"

  /**
   * completed 重激活的显式授权入口名（附 C5①；NodeEdit 参数 `reactivateCompleted`
   * 与事件留痕 `auth=` 字段共用同一字面，供事后对齐）。
   */
  val CompletedReactivationAuth: String = "NODE_COMPLETED_REACTIVATION"

  /**
   * verdict 落库（+ 可选轮次 +1）并推 WS：`lastVerdict` 是 `deliverOut` 的 verdict
   * 感知判据来源（fail ⇒ 不投 pass 边），轮次是轮帽判据来源（§3.6 轮次维）。
   * 事务内现读（节点可能已并发终态化）——非活动区/已消失 ⇒ no-op。
   */
  private[project] def recordVerdict(nodeId: String, verdict: String, bumpRound: Boolean = false): IO[Unit] =
    IO(System.currentTimeMillis()).flatMap { now =>
      store
        .mutateWithResult { st =>
          st.nodes.get(nodeId) match
            case Some(fresh) =>
              val updated = fresh.copy(
                lastVerdict = Some(verdict),
                loopRound = if bumpRound then fresh.loopRound + 1 else fresh.loopRound
              )
              (st.copy(nodes = st.nodes.updated(nodeId, updated)), Some(updated))
            case None => (st, None)
        }
        .flatMap { case (_, opt) => opt.traverse_(emitUpdated) }
    }

  /**
   * fail 回边目标解析（`(fail)<target>:loop` 的**解析后节点 id**）：无此边 / 目标是
   * "Nebula" / 悬空（含目标已在归档区——归档 = 已过期，重跑无意义）⇒ None。
   *
   * 选通边筛选 = [[OutEdge.failRouteTargets]] **单一真相源**（failroute-guard 批
   * 2026-09-21 起；与拒绝态判据 `NodePayload.verifierRouteInvalid` 同点——两处各自派生
   * 即「拒绝态说合法、运行期说不合法」的对偶分歧）。本处只在其上叠加**解析**一步。
   */
  private def loopRouteTargetId(v: NodeDef): IO[Option[String]] =
    OutEdge.failRouteTargets(v.out).headOption match
      case None => IO.pure(None)
      case Some(raw) => store.snapshot.map(s => OutEdge.resolveTargetId(s.nodes, raw))

  /**
   * loop 计时起点置位（目标节点 `loopStartedAt`，只置不重——跨轮不重置，
   * 「本轮 loop 从何时开始」的唯一权威；扫描腿据此算时间帽）。
   */
  private def stampLoopStartedAt(targetId: String, now: Long): IO[Unit] =
    store.mutate { st =>
      st.nodes.get(targetId) match
        case Some(t) if t.loopStartedAt.isEmpty =>
          st.copy(nodes = st.nodes.updated(targetId, t.copy(loopStartedAt = Some(now))))
        case _ => st
    }.void

  /**
   * 计时起点清除（熔断动作第④条的唯一写点；幂等无害——无起点时为 no-op）。
   *
   * **2026-09-14 修复**：本函数此前是熔断的**总闸**（`mutateWithResult` 返回
   * `Boolean`，`false` ⇒ 整条熔断被跳过 + 一句「already handled (idempotent)」INFO）。
   * 旧口径把「目标没有计时起点可清」与「另一路已认领」都判成 `false`，于是
   * **轮次维耗尽时若起点已被孤儿扫描清掉，熔断被静默吞掉**——节点不终态化、不发
   * `loop-budget`、不通知分发器，正是「预算用尽后链同样无声冻结」的第二个根因。
   * 清起点是**熔断的产物**而非前提，故改为无返回值：终态化不再依赖它。
   *
   * 幂等由命中判据承担（不需要 CAS）：轮次维一次判词至多调一次；时间维的重复命中由
   * `sweepLoopBudgets` 的 `due` 过滤挡住（起点已清 ⇒ 不再 due）。
   */
  private def clearLoopStartedAt(targetId: String): IO[Unit] =
    store.mutate { st =>
      st.nodes.get(targetId) match
        case Some(t) if t.loopStartedAt.isDefined =>
          st.copy(nodes = st.nodes.updated(targetId, t.copy(loopStartedAt = None)))
        case _ => st
    }.void

  /**
   * verifier 的 `fail` 申报落点（**替代旧的 `failNode(nodeId, renderFail(fb))`**）：
   *  1. 解析 `(fail)<target>:loop` 回边（无此边 ⇒ 畸形/存量数据：只记 verdict + 照常
   *     completed，绝不误杀）；
   *  2. **预算判定（轮次维在「本轮消费后」判定）**：`consumed = v.loopRound + 1` 喂
   *     `LoopBudget.decide`（配目标节点的 `loopStartedAt` 时间帽）⇒ 命中 ⇒
   *     [[circuitBreakLoop]]（verifier 终态化 failed，本节点的 verdict 照旧记 fail）
   *     ——**`consumed == maxRounds` 即熔断，不再派发该轮**（3/3 = 耗尽轮；旧口径在
   *     消费前判定 ⇒ 3/3 仍派发、须等第 4 次判词才熔断，这是「预算用尽后静默冻结」
   *     的机制根因，2026-09-14 修复）；
   *  3. 预算内 ⇒ 记 `lastVerdict=fail` + 轮次 +1、给目标置计时起点、`completeNode` 走
   *     **completed** 链（verdict 感知的 deliverOut 不投 pass 边）、随后 [[reloopTo]]
   *     **真实执行回边**（重激活目标 + 注入返工输入 + startNode 重跑）。
   *
   * 顺序：`reloopTo` 必须排在 `completeNode` **之后**——它要复位驱动方（本 verifier）
   * 的终态，若排在终态写入之前会被 `completeNode` 覆写回 completed（回边随即只跑
   * 一轮即静默冻结）。
   */
  private[project] def verifierFailR(nodeId: String, fb: BlockedFeedback, resultText: String): IO[Boolean] =
    IO(System.currentTimeMillis()).flatMap { now =>
      store.snapshot.flatMap { s =>
        s.nodes.get(nodeId) match
          case None =>
            logger.warn(s"Node '$nodeId' vanished before its verdict could be recorded — fail verdict dropped") *>
              IO.pure(false)
          case Some(v) =>
            loopRouteTargetId(v).flatMap {
              case None =>
                recordVerdict(nodeId, VerdictFail) *>
                  logger.warn(
                    s"Node '${v.name}' (${nodeId}) reported a fail verdict but declares no usable '(fail)<target>:loop' edge — " +
                      "REJECTION STATE: the verdict is recorded and the node completes, but NO re-run route exists, so the " +
                      "judged worker is NOT re-run and the chain stops here. Restore the route with NodeEdit " +
                      "out=\"(pass)<landing>, (fail)<worker>:loop\" (NODE_VERIFIER_NEEDS_ROUTE)"
                  ) *>
                  FlowMapEventLog.append(
                    workspace,
                    projectName,
                    nodeId,
                    LoopRoundEventType,
                    "verdict=fail but NO usable fail-route edge is declared — no re-run route exists; " +
                      "the verifier is in the REJECTION STATE (see verifier-route-lost)"
                  ) *>
                  // 拒绝态留痕（卡 A4 / §2.5）：主语 = 本 verifier；「判词无处可去」这一事实
                  // 不再只落一句良性退化文案 —— 事件行 + WARN + 载荷键三级同款。
                  emitVerifierRouteLost(List(nodeId -> ""), "fail verdict with no usable route") *>
                  completeNodeR(nodeId, resultText)
              case Some(targetId) =>
                store.findNode(targetId).flatMap { tOpt =>
                  val startedAt = tOpt.flatMap(_.loopStartedAt)
                  val maxRounds = nebflow.shared.Defaults.LoopMaxRounds
                  val maxWall = nebflow.shared.Defaults.LoopMaxWallClockMs
                  // 本轮编号 = 消费后的轮次（1-based）：本次 fail 若派发，它就是第
                  // `consumed` 轮重跑；`consumed >= maxRounds` ⇒ 预算已用尽 ⇒ 熔断而非
                  // 再派发（「3/3 用尽」的语义分界，见本函数 scaladoc 第 2 条）。
                  val consumed = v.loopRound + 1
                  LoopBudget.decide(consumed, maxRounds, startedAt, now, maxWall) match
                    case Some(reason) =>
                      circuitBreakLoopR(v, Some(targetId), reason, now, consumed, Some(resultText))
                    case None =>
                      val issues =
                        List(if fb.detail.trim.isEmpty then VerdictReader.PlaceholderIssues else fb.detail.trim)
                      for
                        _ <- recordVerdict(nodeId, VerdictFail, bumpRound = true)
                        _ <- stampLoopStartedAt(targetId, now)
                        landed <- completeNodeR(nodeId, resultText)
                        // 执行腿（二期）：终态写入之后再复位驱动方 + 重激活目标 + 重跑
                        _ <- reloopTo(v, targetId, consumed, maxRounds, issues, fb.suggestion)
                      yield landed
                }
            }
      }
    }

  /**
   * loop 预算熔断（§3.6 三档统一动作）：
   *  ① **不投任何 `:loop` 边**（控制边本就不进 settleTo/barrier 结算；`deliverFailed`
   *     的 nodeTargets 亦按 mode 过滤——见该函数）；
   *  ② **verifier 终态化 `failed`**，result = reason + 计量数字（经既有 `failNode`
   *     链：WS 事件 + `deliverFailed` + 尾部 `retryOrNotify` ⇒ ④ 失败通知）；
   *  ③ `FlowMapEventLog` 记 `loop-budget`（含 reason + 计量）；
   *  ④ 目标节点计时起点清除（**熔断的产物，不是前提**——见 [[clearLoopStartedAt]] 注：
   *     旧口径用「清起点成功与否」当总闸，起点缺失时把整条熔断静默吞掉）。
   *
   * `reason` 形如 `rounds=3/3` / `wallClock=14400000ms/14400000ms`，`metering` 再附
   * 轮次与预算上限——取证侧据此对齐「哪一维先耗尽」。
   *
   * `rounds` = 本次熔断的轮次读数（1-based；调用方传**消费后**的轮次，故轮次维熔断
   * 恒读作 `rounds=<maxRounds>/<maxRounds>`，即那句话字面意义上的「3/3 用尽」）。
   *
   * 幂等：入口 fresh-read 复核驱动方是否**已由另一次熔断终态化**（⇒ 只留一句 INFO，
   * 零重复事件、零重复失败通知）。
   *
   * ⚠ 复核对的是 `failed`/`cancelled` 两个**熔断产物态**，**不是** `Terminal` 全集：
   * nrloop 的常态恰恰是「驱动方 `completed` 且判词 = fail」（`verdict ≠ 节点状态`），
   * 若把 completed 也当「已处理」，本批要修的 ② 又会被自己的幂等闸吞掉（实测：
   * 终态 fail-verifier 的时间维熔断被跳过，节点停留 completed、零事件、零通知）。
   */
  private def circuitBreakLoopR(
    v: NodeDef,
    targetId: Option[String],
    reason: String,
    now: Long,
    rounds: Int,
    finalText: Option[String] = None
  ): IO[Boolean] =
    val maxRounds = nebflow.shared.Defaults.LoopMaxRounds
    val metering = s"rounds=$rounds/$maxRounds wallClockMaxMs=${nebflow.shared.Defaults.LoopMaxWallClockMs}"
    val msg = s"loop budget exhausted: $reason — $metering"
    // engine-defects 批 #239（2026-09-15）：**熔断不得吞掉判词全文**。旧口径只把计量串
    // 写进 result ⇒「判词已落盘（`recordVerdict` 先跑）、`node_report` 的终止申报与结论
    // 全文丢失、结果被降级成 stub」。修法 = 复用**既有**原结论文本并列落盘机制
    // （[[CompletionGate.withOriginalText]] 的 `[original-conclusion]` 稳定锚，与
    // `completeNode` 的闸门 Reject 分支同一机制、同一格式、同一取回方式）：
    // 计量串在**前**（保住既有「result 必含 loop budget exhausted」断言与分发器可读性），
    // 原结论文本以空行分隔并列在**后**（一条 result 两段各自取用，无 schema 变更）。
    // 取回命令（U6/F 同款，<ws> = 项目工作区，<id> = 节点 id）：
    //   python3 -c "import json;r=json.load(open('<ws>/.nebflow/flow-map.json'))\
    //     ['nodes']['<id>']['result'];print(r.split('[original-conclusion]',1)[1])"
    val msgWithConclusion = finalText.filter(_.trim.nonEmpty) match
      case Some(t) => s"$msg\n\n${CompletionGate.withOriginalText(t)}"
      case None => msg
    def drive: IO[Boolean] =
      targetId.traverse_(clearLoopStartedAt) *>
        recordVerdict(v.id, VerdictFail) *>
        FlowMapEventLog.append(
          workspace,
          projectName,
          v.id,
          LoopBudgetEventType,
          s"$msg target=${targetId.getOrElse("<none>")} verdict=fail" +
            (if finalText.exists(_.trim.nonEmpty) then " conclusion=retained" else " conclusion=<none>")
        ) *>
        logger.warn(s"Node '${v.name}' (${v.id}) loop circuit-break: $msg") *>
        // ② + ④：既有 failed 链（deliverFailed → merge 兜底/停等留痕 → dispatchNotify failed）
        // #239①：落地判词随之向上回报（本腿的 `fail` 申报也走消费点）
        failNodeR(v.id, msgWithConclusion)
    store.getNode(v.id).flatMap {
      case Some(fresh) if fresh.status == NodeLifecycle.Failed || fresh.status == NodeLifecycle.Cancelled =>
        logger.info(
          s"Node '${v.name}' (${v.id}) loop circuit-break skipped — the fail-route driver is already " +
            s"terminalized by another breaker (status=${fresh.status}, idempotent)"
        ) *>
          // 幂等跳过支 = 本次熔断**没有**写下终态（判词也未记）⇒ 落地判词 false，
          // 由消费点把该 fail 申报补偿写回（#239①）
          IO.pure(false)
      case _ => drive
    }

  end circuitBreakLoopR

  /**
   * 回边重激活的字段族（与 NodeEdit 人工重激活的 `reactivated` 分支、`retryReactivate`
   * /`reactivateForRetry` 同款）：状态回 wiring/pending、结果丢弃、时间戳复位、轮次历史
   * 复位（仅 failed 面）——重跑完成后重新投递 + 重新记账。
   *
   * 四处**刻意不动**（每一处都能单独把回边改坏）：
   *   · `loopStartedAt`——时间帽的锚点，跨轮只置不重（清它 = 时间预算被无限刷新）；
   *   · `loopRound`——轮次由 verifier 侧承载（`recordVerdict` 已 +1），目标不自计；
   *   · `gen`——自动回跳（retry）预算的载体，回边重跑**不**消耗它（两者同节点已被
   *     `NODE_RETRY_LOOP_CONFLICT` 硬拒 ⇒ 无耦合面）；
   *   · `deliveredTo`——**保留**：barrier 保持「已结算」，重激活后 `startNode` 才能
   *     立刻拿到会话（清掉它会让目标退回等 barrier，与「本轮立刻重跑」的目标相反）。
   *     **驱动方是唯一例外**（见 [[reloopTo]]：摘掉目标那一轨，等新产出）。
   * `lastVerdict` 同样不动：fail 判词必须留在驱动方身上——它正是收口位的判词闸
   * （`mergeVerdictHolders`，#238 泛化后 = **全部收口位**）在返工期间继续挡住
   * 「未返工先推进」的依据。
   */
  private def resetForLoop(node: NodeDef): NodeDef =
    val fromFailed = node.status == NodeLifecycle.Failed
    val nextStatus =
      if node.task.exists(_.trim.nonEmpty) && node.in.isEmpty then NodeLifecycle.Pending
      else NodeLifecycle.Wiring
    withoutReportPending(
      node.copy(
        status = nextStatus,
        result = None,
        nebulaDeliveredAt = None,
        startedAt = None,
        completedAt = None,
        ttlExpireAt = None,
        blockCount = if fromFailed then 0 else node.blockCount,
        blockedFeedback = if fromFailed then None else node.blockedFeedback,
        notifySentAt = if fromFailed then None else node.notifySentAt
      )
    )

  end resetForLoop

  /**
   * **回边执行腿（nrloop 二期 2026-09-14）**：`verifierFail` 预算内分支的唯一投递动作
   * ——把「判 fail ⇒ 打回重做」从「只登记重跑意图」变成引擎内的真实闭环。此前全树只有
   * 注释（`deferred=execution-leg-phase-2` 是这个静默 defer 的唯一现场痕迹：判词有记录、
   * 目标却零投递、无 error 事件），跨项目复发 ≥3 次（`n-7dabba52` L205 / `n-a9309cd3`
   * L320 / `n-8bb717d6` L470 同 summary 形态），每次都要分发器手工
   * `NODE_COMPLETED_REACTIVATION` 兜底。
   *
   * 一次 `mutate` 事务内原子完成两处复位（避免中间态被投递腿/回扫看到）：
   *  1. **目标重激活**：被判对象回 wiring/pending、旧结果丢弃 —— 前端看到的是「同一
   *     节点 again（attempt N）」而非拓扑增殖（设计 §3.5）；
   *  2. **驱动方复位（本 verifier）**：`verdict ≠ 节点状态` ⇒ 判完 fail 的 verifier 停在
   *     `completed`，而 `deliverOut → settleTo → startNode` 对终态节点**幂等跳过**——
   *     不复位它，目标重跑完成后**没有人再复核**，回边只跑一轮即静默冻结（= 09-14 两链
   *     实证形态）。复位方式 = 状态回 wiring + **只**摘掉目标那一轨的投递记账（其余上游
   *     照旧已投、不重投），barrier 停在「等目标的新产出」，目标重跑完成经 pass 边
   *     （`settleTo` → `startNode`）自动把 verifier 拉起 ⇒ 下一轮判词自然到来 ⇒ 轮帽
   *     `3/3` 可达 ⇒ 熔断有出口（否则「预算耗尽」永远不发生）。
   *
   * 之后在**同一调用**内启动目标重跑（`startNode(loopRework=…)`），输入 = `buildInput`
   * 全文重注 + `loopReworkInput(round, issues, requirements)` 返工段（设计 §3.7 选项 (i)：
   * 每轮付一次冷上下文，换语义确定；transcript-resume 不在本批最小面内）。
   *
   * 边界（逐条都有出口留痕，禁静默）：
   *   · 无 fail 边 / 悬空目标 ⇒ 上游 `verifierFail` 已分流，本函数不达；
   *   · 目标/驱动方不可重激活 ⇒ **不投递**，`loop-round` 事件记 `skipped=<原因>` + WARN
   *     ——链不静默冻结：驱动方终态 + 目标计时起点仍在，时间帽扫描
   *     （[[sweepLoopBudgets]]）会把它当「有活驱动方」熔断出显式终态；
   *   · `:loop` 边**依然**不进 `settleTo`/`barrier`/`deliveredTo`（红线①逐字不变）——
   *     「真实投递」= 引擎显式驱动，不是把控制边当投递边用。
   */
  private def reloopTo(
    v: NodeDef,
    targetId: String,
    round: Int,
    maxRounds: Int,
    issues: List[String],
    requirements: String
  ): IO[Unit] =
    store
      .mutateWithResult { st =>
        (st.nodes.get(targetId), st.nodes.get(v.id)) match
          case (Some(t), Some(driver)) =>
            // 目标可重激活的口径（B5 缺口③ 对偶腿 · 作者 2026-09-17 M-3 裁定「运行期回边腿
            // **同步排除 Running 目标**」⇒ 与编辑期守卫两面口径一致，判据单点在
            // NodeEngine.loopReworkAdmission）：
            //   · **running** ⇒ 排除：目标有在飞会话，重置它 = 把节点翻回 wiring/pending 而
            //     会话仍在跑（节点状态与会话双轨不一致 = 本对偶缺口的那一半洞）；
            //   · 非终态（在等 barrier）或 completed（刚跑完被判）⇒ 可重激活；
            //   · blocked/failed/cancelled 是**分发器持有的现场**，引擎不擅自推翻（重激活
            //     它们会丢掉 blockedFeedback / 死亡现场）。
            // 命中排除面 ⇒ Left(原因)，走既有 `skipped=<原因>` 出口（loop-round 事件 + WARN，
            // 链不静默冻结：时间帽扫描仍持有出口）。
            val reloopRejection: String = NodeEngine.loopReworkAdmission(t.status).fold(identity, _ => "")
            if reloopRejection.nonEmpty then (st, Left(s"target '${t.name}' is $reloopRejection"))
            else
              // 驱动方只在「已完成 ∧ 目标确实是它的 in 上游」时复位：否则它不会因目标的
              // 新产出被重新触发，复位成 wiring/pending 只会让资格回扫空跑一轮（轮次空转）。
              val driverReset = driver.status == NodeLifecycle.Completed && driver.in.contains(targetId)
              val tNew = resetForLoop(t)
              val dNew =
                if driverReset then
                  resetForLoop(driver).copy(
                    status = NodeLifecycle.Wiring,
                    deliveredTo = driver.deliveredTo.filterNot(_ == targetId)
                  )
                else driver
              (
                st.copy(nodes = st.nodes.updated(targetId, tNew).updated(v.id, dNew)),
                Right((t, tNew, dNew, driverReset))
              )
            end if
          case _ =>
            (st, Left(s"target '$targetId' or fail-route driver '${v.id}' is gone (archived or removed)"))
      }
      .map(_._2)
      .flatMap {
        case Left(why) =>
          FlowMapEventLog.append(
            workspace,
            projectName,
            v.id,
            LoopRoundEventType,
            s"verdict=fail round=$round/$maxRounds target=$targetId skipped=$why " +
              "(no re-run dispatched; the fail-route driver stays live so the wall-clock budget scan owns the terminal)"
          ) *>
            logger.warn(
              s"Node '${v.name}' (${v.id}) verdict=fail at round $round/$maxRounds — re-run leg skipped: $why"
            )
        case Right((tOld, tNew, dNew, driverReset)) =>
          val note =
            s"verdict=fail round=$round/$maxRounds target=$targetId dispatched=reloopTo " +
              s"(target '${tNew.name}' re-activated: ${tOld.status}→${tNew.status}; rework input injected; " +
              s"driver '${v.name}' reset=$driverReset)"
          val rework = NodeEngine.loopReworkInput(round, issues, requirements)
          emitUpdated(tNew) *>
            (if driverReset then emitUpdated(dNew) else IO.unit) *>
            FlowMapEventLog.append(
              workspace,
              projectName,
              targetId,
              "reactivated",
              s"loop fail-route re-run dispatched by '${v.name}' (${v.id}) at round $round/$maxRounds " +
                s"[preStatus=${tOld.status} source=loop gen=${tNew.gen} blockCount=${tNew.blockCount} " +
                s"loopRound=${tNew.loopRound}]"
            ) *>
            FlowMapEventLog.append(workspace, projectName, v.id, LoopRoundEventType, note) *>
            logger.info(s"Node '${v.name}' (${v.id}) $note") *>
            // fork 化与 settleDeps/settleRunnableSweep 同款纪律：verifier 的完成 fiber 不被
            // 目标会话质押（目标可能跑几十分钟，verifier 早已终态化完毕）。
            forkStart(s"reloop -> ${tNew.name}(${tNew.id})")(startNode(tNew.id, loopRework = Some(rework)))
      }

  /**
   * loop 时间帽扫描腿（`ProjectActor.TtlTick` 30s 驱动，与 `remindUnreportedNodes` /
   * `settleStaleRunningNodes` / `sweepDestroyWindows` 同族——复用既有心跳点零新调度器）。
   *
   * 判据（§3.6 时间维逐字）：`status ∈ {wiring,pending,running} ∧ loopStartedAt.isDefined
   * ∧ now - loopStartedAt >= maxWallClockMs` ⇒ 熔断。命中者反查「回边驱动方」（持有
   * `(fail)<命中节点>:loop` 边、且**判词仍在驱动重跑**的 verifier——含
   * `lastVerdict=fail` 的终态 verifier，见 [[loopDriverOf]] 注）：
   *   - 找到 ⇒ `circuitBreakLoop`（verifier → failed + `loop-budget` 事件 + 失败通知）；
   *   - 找不到（计时起点成孤儿：驱动方已被删/改判/手改数据）⇒ 清起点 + 单发一条
   *     `loop-budget` 事件（`stage=orphan`）——不终态化无关节点，也绝不留一个每 30s
   *     重复命中的扫描热点。
   *
   * 幂等：起点清除使目标当轮起不再满足 `loopStartedAt.isDefined` ⇒ 重复命中天然 no-op
   * （无需 CAS；熔断侧另有驱动方终态复核，见 [[circuitBreakLoop]]）。best-effort
   * （调用方 handleErrorWith，失败不影响后续 sweep）。
   */
  def sweepLoopBudgets(): IO[Unit] =
    val maxWall = nebflow.shared.Defaults.LoopMaxWallClockMs
    if maxWall <= 0 then IO.unit
    else
      IO(System.currentTimeMillis()).flatMap { now =>
        store.snapshot.flatMap { s =>
          val due = s.nodes.values.toList.filter(n =>
            (n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Running)
              && n.loopStartedAt.exists(st => now - st >= maxWall)
          )
          due.traverse_ { t =>
            val elapsed = t.loopStartedAt.map(st => now - st).getOrElse(0L)
            loopDriverOf(s.nodes.values.toList, t.id) match
              case Some(v) =>
                // 时间维熔断由 30s 扫描腿驱动（非申报消费路径）⇒ 落地判词在此丢弃：
                // 本腿没有「刚被 drain 取走的申报」需要补偿（#239① 的补偿面只挂在消费点）。
                circuitBreakLoopR(
                  v,
                  Some(t.id),
                  s"wallClock=${elapsed}ms/${maxWall}ms",
                  now,
                  v.loopRound,
                  // 时间维熔断：驱动方此刻是 completed 的 fail-verifier，其结论全文在 result
                  // 字段上（`completeNode` 已落库）——同样不得丢（#239 同款口径）。
                  finalText = v.result
                ).void
              case None =>
                clearLoopStartedAt(t.id) *>
                  FlowMapEventLog.append(
                    workspace,
                    projectName,
                    t.id,
                    LoopBudgetEventType,
                    s"loop wall-clock budget exceeded with no live fail-route driver: wallClock=${elapsed}ms/${maxWall}ms " +
                      "stage=orphan — timer cleared (no node terminalized)"
                  ) *>
                  logger.warn(
                    s"Node '${t.name}' (${t.id}) carries an expired loop timer but no live verifier routes a fail edge " +
                      s"to it (${elapsed}ms >= ${maxWall}ms) — timer cleared, nothing terminalized"
                  )
            end match
          }
        }
      }

    end if

  end sweepLoopBudgets

  /**
   * 回边驱动方反查（时间帽熔断用）：活动区内持有 `(fail)<targetId>:loop` 边、且**仍驱动
   * 重跑**的 verifier 节点。目标串支持 id/名字两形态（与 `resolveTargetId` 同源）。
   *
   * 判据是**判词边**而非节点生命周期（2026-09-14 修复）：`verdict ≠ 节点状态` ⇒ 判完
   * fail 的 verifier 停在 `completed`，若仍按「非终态才算驱动方」判，它会被判成"没有
   * 驱动方"，于是过期计时起点走**孤儿**分支——清表、`stage=orphan` 事件、**零终态化、
   * 零通知**，即「轮预算耗尽后链无声冻结」。故补一条：终态 verifier 只要
   * `lastVerdict=fail`（回边仍指向本目标）就仍是驱动方 ⇒ 熔断出显式终态。
   * 无 fail 判词的终态 verifier（pass/未申报）**不算**驱动方（孤儿语义逐字保留）。
   */
  private def loopDriverOf(nodes: List[NodeDef], targetId: String): Option[NodeDef] =
    nodes.find { n =>
      n.role == NodeRoles.Verifier &&
      (!NodeLifecycle.Terminal.contains(n.status) || n.lastVerdict.contains(VerdictFail)) &&
      OutEdge
        .canonical(n.out)
        .exists(e =>
          OutEdge.isLoopEdge(e) && e.on.contains(OutEdge.Fail) &&
            (e.to == targetId || nodes.find(_.id == targetId).exists(t => t.name == e.to))
        )
    }
end NodeLoopRunner
