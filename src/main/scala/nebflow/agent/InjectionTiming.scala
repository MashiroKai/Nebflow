package nebflow.agent

import nebflow.actor.AgentCommand

/**
 * 注入时机判定**单点**（midturn-injection-timing 批，2026-11-05）。
 *
 * == 为什么有这个 object ==
 * 三条注入腿（用户输入 / Mail `delegate:` / dispatcher→`node:`）的「此刻在工具边界即
 * 注入」还是「排队等下一个边界」曾是**三份判据散落**：
 *   - `TurnBoundaryDrains.drainHeadExpanded`（`pendingImmediateInputs` 队首一件）
 *   - `TurnBoundaryDrains.drainMailPackage`（`isMailSupplement` 豁免面）
 *   - 回合末 `AgentFinishTurn` 的 `pendingUserInputs` head-forward（真人旧队列轨）
 * 事实表（`reports-evidence/midturn-injection-timing/facts.md` §1）实锤：用户腿的
 * **第二承载形**（typeless 帧：附件 / 引用 / 引言 / queue drain ⇒
 * `UserInput` + `clientMessageId` + `blocks`）两个边界消费器都不收它，于是它整个
 * 工具循环里零消费、直到回合末才以新回合投出 —— 作者报障命中的就是这条。
 *
 * 本 object = **唯一定义处**：一个纯函数 [[decide]]（一条消息此刻注入还是等下一个
 * 边界，附腿别与理由）+ 一个纯函数 [[planBoundary]]（把两条队列按该判定合成一次
 * 边界的注入计划）。三条腿的边界消费**只经这里**，不许各写一份谓词：
 *   - 腿①用户（纯文本）：`ImmediateInput(fromUser = true)` → [[Leg.UserText]]
 *   - 腿①′用户（typeless 帧）：`UserInput` + `clientMessageId` + 非空 `blocks`
 *     → [[Leg.UserFrame]]（本批新纳入边界；此前落回合末）
 *   - 腿②Mail：`DelegateNotify` → `ImmediateInput(source = "delegate-notify")`
 *     → [[Leg.MailDelegate]]；`MailTool` 续聊 / 邮件腿 → `UserInput(source =
 *     "mail", fromUser = false)` → [[Leg.MailSupplement]]（#939 整包豁免）
 *   - 腿③node：`NodeDelivery.injectRunning` → `ImmediateInput(source = "system")`
 *     → [[Leg.NodeMessage]]
 *
 * == 语义红线（本批不动）==
 *  1. **只在工具边界注入**：本 object 零 `IO`、零调度、零定时；它只回答「这个边界
 *     该不该取这件」，调用点仍在既有的工具批次完成处 —— 永不打断正在生成的流。
 *  2. **幂等**：注入的唯一来源是队列，取走即出队（`remaining*` 里不再有它）；
 *     窗冲刷载荷的展开件 `windowItems` 就地清零（既有谓词，防二次展开）。同一条
 *     消息不可能被两个边界各注入一次。回执/身份（`clientMessageId`）由调用点
 *     在边界注入的同拍登记进 `recentMessageIds`（重连补发的重复帧仍被丢掉）。
 *  3. **不改回合语义**：不提前结束回合、不把多条合成一条、不改消息身份与顺序；
 *     每边界预算仍=「immediate 队首一件 + mail 整包 + 真人 typeless 帧一件」，
 *     其余留队保序（旧队列轨的 turn 末 head-forward 原样保留）。
 *  4. **不许丢消息**：判为 `HoldForNextBoundary` 的件必留在原队列，回落路径
 *     （回合末 head-forward / idle 直投 / compaction 完成后再排空）逐条送达。
 */
object InjectionTiming:

  /**
   * 注入腿标签 —— 只用于**判定同拍产出的可观测性**（事件行 / 日志），不参与任何
   * 会计或去重。每个取值对应一处真实生产写入点（见 object 头注释）。
   */
  enum Leg:
    /** 腿①用户纯文本：`ImmediateInput(fromUser = true)`（WS `immediateInput` / CLI `userMessage`）。 */
    case UserText

    /** 腿①′用户 typeless 帧：附件 / 引用 / 引言 / queue drain（`UserInput` + `clientMessageId` + 非空 `blocks`）。 */
    case UserFrame

    /** 腿②Mail delegate 通知：`DelegateNotify.inject` 的 `ImmediateInput(source = "delegate-notify")`。 */
    case MailDelegate

    /** 腿②Mail 补充件：`MailTool` 投递腿的 `UserInput(source = "mail", fromUser = false)`。 */
    case MailSupplement

    /** 腿③node：`NodeDelivery.injectRunning` / 根通道通知的 `ImmediateInput(source = "system"|"node")`。 */
    case NodeMessage

    /** 其余服务端注入（flow / tool / subtask / ask 等）：与三腿同队列、同一判定。 */
    case ServerInjected

    /** 永不在工具边界消费的命令（SkillActivate / AskQuestion / 非注入队列件）。 */
    case NonInjectable

  end Leg

  /**
   * 判定发生的**时机面**（同一个判据在三个生命周期位置的取值口径）。
   *
   *  - [[Stage.ToolBoundary]]：本回合内、一次工具调用返回后 / 下一次 LLM 调用前
   *    —— 唯一的「即时注入」面。
   *  - [[Stage.AbortRecovery]]：传输中断恢复后的重投面（既有 immediate 腿）。
   *  - [[Stage.TurnEnd]]：本回合已结束的**回落**面 —— 此时不再有「下一个工具边界」，
   *    真人 typeless 帧一律留队，由 head-forward 作为**新回合**送达（不丢消息）。
   */
  enum Stage:
    case ToolBoundary, AbortRecovery, TurnEnd

  enum Verdict:
    /** 此刻（本边界）注入。 */
    case InjectNow

    /** 留在原队列，等下一个边界 / 回落路径。 */
    case HoldForNextBoundary

  /** 一件消息的判定结果。`reason` 是逐字可查的判据文本（进日志/事件行）。 */
  final case class Decision(leg: Leg, verdict: Verdict, reason: String):
    def injectsNow: Boolean = verdict == Verdict.InjectNow

  // ── 判据子句（本 object 是唯一定义处；`TurnBoundaryDrains` 只做转发）─────────

  /**
   * Mail 补充件判据（#939 作者令的豁免面）：非真人 + `source = "mail"`。
   * 唯一写入点 = `MailTool` 投递腿（delegate 续聊腿 / 邮件腿）。
   */
  def isMailSupplement(ui: AgentCommand.UserInput): Boolean =
    !ui.fromUser && ui.source.contains("mail")

  /**
   * 真人 **typeless 帧**判据（本批收敛点）：带幂等键 + 带内容块 + 无 replyTo。
   *
   * 为什么是这三条而不是「所有真人 UserInput」：
   *  - `clientMessageId.isDefined` = 客户端投递的真人件（服务端注入件不带幂等键；
   *    `MailTool` 的 pending 补发腿显式钉同一形也无妨 —— 它另命中 mail 豁免）；
   *  - `blocks.nonEmpty` = **块承载形**。⚠️ 口径修正（2026-11-05，按代码实测；旧注释
   *    称「收窄为显式 queue 模式的纯文本件」与代码相反，已作废）：`WebSocketRoutes.scala:2044`
   *    对**任何带正文**的 typeless 帧都产出一块 `ContentBlock.Text(content)`
   *    （`:2234-2240` 投 `UserInput(content, None, clientMessageId, Some(blocks).filter(_.nonEmpty), w)`），
   *    而前端 queue 模式 drain 腿（`input.js:1345` `dispatchQueuedItemNow(sessionId, item)`
   *    无 opts → `:1427` `wire==='immediate'` 不成立 → `:1430` `dispatchUserMessageFrames`
   *    默认用户帧）与直投帧**同形** ⇒ **queue 模式的纯文本件同样带块、同样在工具边界注入**。
   *    本判据覆盖的不是「附件/引用专有形」，而是「一切带正文的 typeless 帧」；
   *  - 于是 2026-09-15「排队消息逐条独立 turn」裁定的**残余面只剩零块 `UserInput`**：
   *    `content` 空 ∧ 无附件/引用 ⇒ 网关准入谓词（`content.nonEmpty || attachments.nonEmpty`，
   *    `WebSocketRoutes.scala:2009`）直接拒收，**WS 前端从不产生该形**，仅协议层可手工构造
   *    （`BusyConsoleInputInjectionSpec` W3 夹具即该形 ⇒ 其绿只钉协议残余面、不覆盖
   *    queue 路径）；生产形（纯文本块 `ContentBlock.Text`，与 queue drain 帧逐字段同形）
   *    由既有 `MidTurnInjectionTimingSpec` **L1b**（`:283-289`）钉住且已绿
   *    ⇒ **不变量：带正文的 typeless 真人帧必带块 ⇒ 必在工具边界注入**；
   *  - `replyTo.isEmpty` = 无完成结算目标，边界注入不会让 supervisor 的
   *    `AgentEvent.Completed` 搁浅（带 replyTo 的件仍只走全元数据 head-forward）。
   *
   * 🔴 政策面（如实记，不假装统一；已报作者待裁定）：本判据实质上是把 2026-09-15 裁定的
   * 适用范围退到「零块 `UserInput`」这一 WS 前端不产生的形态 —— 生产面上 queue 模式的
   * 纯文本件已改为工具边界注入。若要保留 queue 模式逐条独立 turn 语义，需前端在默认用户
   * 帧上加**可判别标记**（如 `queued: true`）并由 `AgentCommand.UserInput` 承载，判据改为
   * 显式标记而非「有块」（协议增量，本轮未做）。证据口径见
   * `reports-evidence/midturn-injection-timing/facts.md` §8.4①②。
   */
  def isHumanFrame(ui: AgentCommand.UserInput): Boolean =
    ui.clientMessageId.isDefined && ui.blocks.exists(_.nonEmpty) && ui.replyTo.isEmpty

  /** 队列元素形态判据（`pendingUserInputs` 是 `List[AgentCommand]`）。 */
  def isMailSupplementCommand(cmd: AgentCommand): Boolean = cmd match
    case ui: AgentCommand.UserInput => isMailSupplement(ui)
    case _ => false

  private def legOfImmediate(imm: AgentCommand.ImmediateInput): Leg =
    if imm.fromUser then Leg.UserText
    else
      imm.source match
        case Some("delegate-notify") | Some("flow-notify") | Some("mail") => Leg.MailDelegate
        case Some("system") | Some("node") => Leg.NodeMessage
        case _ => Leg.ServerInjected

  private def legOf(cmd: AgentCommand): Leg = cmd match
    case imm: AgentCommand.ImmediateInput => legOfImmediate(imm)
    case ui: AgentCommand.UserInput =>
      if isMailSupplement(ui) then Leg.MailSupplement
      else if isHumanFrame(ui) then Leg.UserFrame
      else if ui.clientMessageId.isDefined then Leg.UserText
      else Leg.ServerInjected
    case _ => Leg.NonInjectable

  // ── 判定单点 ────────────────────────────────────────────────────────────────

  /**
   * **唯一的注入时机判定**：这件消息此刻注入，还是等下一个边界。
   *
   * @param stage           判定发生的时机面（工具边界 / 中断恢复 / 回合末回落）
   * @param compactionPending 压缩作业挂起中 ⇒ 工具边界零消费（注入进将被 summary
   *                        替换的历史 = 消费即丢失；`CompactionComplete` 再排空）
   */
  def decide(cmd: AgentCommand, stage: Stage, compactionPending: Boolean): Decision =
    val leg = legOf(cmd)
    if compactionPending && stage == Stage.ToolBoundary then
      Decision(
        leg,
        Verdict.HoldForNextBoundary,
        "compaction-pending：注入进将被 summary 替换的历史 = 消费即丢失（CompactionComplete 再排空）"
      )
    else
      cmd match
        // 腿①用户纯文本 / 腿②Mail notify / 腿③node：immediate 承载形，边界即注入。
        case _: AgentCommand.ImmediateInput =>
          Decision(leg, Verdict.InjectNow, "immediate-leg：工具边界即注入")
        // 腿②Mail 补充件：整包合并注入（#939 作者令的显式豁免面）。
        case ui: AgentCommand.UserInput if isMailSupplement(ui) =>
          Decision(leg, Verdict.InjectNow, "mail-supplement：边界整包合并注入（#939 豁免）")
        // 腿①′用户 typeless 帧（本批新纳入）：边界即注入；回合末留队回落。
        case ui: AgentCommand.UserInput if isHumanFrame(ui) =>
          stage match
            case Stage.ToolBoundary | Stage.AbortRecovery =>
              Decision(leg, Verdict.InjectNow, "human-frame：附件/引用/引言承载形在边界即注入")
            case Stage.TurnEnd =>
              Decision(
                leg,
                Verdict.HoldForNextBoundary,
                "human-frame + 回合已结束：留队交 head-forward 作为新回合送达（回落，不丢）"
              )
        // 旧队列轨（纯文本真人件 / 非 mail 服务端件）：2026-09-15 裁定逐条独立 turn。
        case _: AgentCommand.UserInput =>
          Decision(leg, Verdict.HoldForNextBoundary, "队列旧轨：逐条独立 turn（2026-09-15 裁定面）")
        // 全元数据命令：只走 turn 末 head-forward（完成目标不搁浅）。
        case _: AgentCommand.SkillActivate | _: AgentCommand.AskQuestion =>
          Decision(leg, Verdict.HoldForNextBoundary, "全元数据命令：仅 turn 末 head-forward")
        case _ =>
          Decision(leg, Verdict.HoldForNextBoundary, "非注入队列命令：本边界不消费")

    end if

  end decide

  /**
   * 一次工具边界的注入计划（纯值，`AgentProcessing` 的 ToolsComplete 分支逐字段消费）。
   *
   * 预算（与改前逐字节等价，除腿①′新纳入）：immediate **队首一件**（其
   * `windowItems` 载荷在内部展开为 N 件 = N 气泡 / 1 次唤醒）；mail **整包**；
   * 真人 typeless 帧**至多一件**（保 arriv 序，其余留队由后续边界逐条消费）。
   */
  final case class BoundaryPlan(
    /** 本边界注入的 immediate 载荷（已展开）。 */
    immediate: List[AgentCommand.ImmediateInput],
    /** 本边界注入的 mail 补充件（整包，到达顺序保持）。 */
    mails: List[AgentCommand.UserInput],
    /** 本边界注入的真人 typeless 帧（至多一件）。 */
    humanFrames: List[AgentCommand.UserInput],
    /** 未消费的 immediate 队列（原样保序）。 */
    remainingImmediate: List[AgentCommand.ImmediateInput],
    /** 未消费的 user 队列（原样保序；下落 = 回合末 head-forward）。 */
    remainingUser: List[AgentCommand],
    /** 留队件的（腿别, 理由）读数 —— 事件行/排障用。 */
    held: List[(Leg, String)]
  ):
    def isEmpty: Boolean = immediate.isEmpty && mails.isEmpty && humanFrames.isEmpty

  end BoundaryPlan

  def planBoundary(
    immediateQueue: List[AgentCommand.ImmediateInput],
    userQueue: List[AgentCommand],
    stage: Stage,
    compactionPending: Boolean,
    expand: AgentCommand.ImmediateInput => List[AgentCommand.ImmediateInput]
  ): BoundaryPlan =
    // immediate 腿：队首一件（compaction 挂起 ⇒ decide 返回 Hold ⇒ 零消费）。
    val immHead =
      immediateQueue.headOption.filter(c => decide(c, stage, compactionPending).injectsNow)
    val injectedImm = immHead.toList.flatMap(expand)
    val remainingImm = if immHead.isDefined then immediateQueue.drop(1) else immediateQueue

    // user 腿：逐件过同一个判定（按索引摘取 ⇒ 保序、同形件不被值相等误吞）。
    val indexed = userQueue.zipWithIndex.map { (c, i) => (c, decide(c, stage, compactionPending), i) }
    val mailIdx = indexed.collect {
      case (ui: AgentCommand.UserInput, d, i) if d.injectsNow && d.leg == Leg.MailSupplement => (i, ui)
    }
    val humanIdx = indexed
      .collect {
        case (ui: AgentCommand.UserInput, d, i) if d.injectsNow && d.leg == Leg.UserFrame => (i, ui)
      }
      .take(1) // 每边界至多一件真人帧（与 immediate 的「一件一次全上下文往返」预算同源）
    val consumed = (mailIdx ++ humanIdx).map(_._1).toSet
    val held = indexed.collect { case (_, d, i) if !consumed.contains(i) => (d.leg, d.reason) }.distinct

    BoundaryPlan(
      immediate = injectedImm,
      mails = mailIdx.map(_._2),
      humanFrames = humanIdx.map(_._2),
      remainingImmediate = remainingImm,
      remainingUser = indexed.collect { case (c, _, i) if !consumed.contains(i) => c },
      held = held
    )

  end planBoundary

  /**
   * 本边界是否还有可注入件（`AgentFinishTurn` 的回合末腿用它替代 `nonEmpty` 直判，
   * 使「回合末该不该开注入轮」也走同一判据）。
   */
  def hasInjectable(
    immediateQueue: List[AgentCommand.ImmediateInput],
    userQueue: List[AgentCommand],
    stage: Stage,
    compactionPending: Boolean
  ): Boolean =
    !planBoundary(immediateQueue, userQueue, stage, compactionPending, imm => List(imm)).isEmpty

end InjectionTiming
