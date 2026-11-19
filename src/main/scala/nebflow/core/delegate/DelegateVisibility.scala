package nebflow.core.delegate

import nebflow.shared.{NebflowLogger, PathUtil, SubAgentTask}

/**
 * DelegateVisibility —— delegate 身份的**状态派生可见性单源**（delegate-lifecycle-reform
 * 阶段② · 作者令「用状态派生可见性替换存储式归档，零删除」）。
 *
 * == 本批是什么 / 不是什么 ==
 * 阶段①（[[nebflow.core.project.RecoverySummary]]）把「无待处理事实」建成七条合取判据；
 * 本件是它的**身份级派生面**，把原先靠**存储旗**（`DelegateRegistry.Record.archivedAt`
 * 单旗写）表达的「这个身份已经收口了」改由**盘上状态现读**导出：
 *
 * {{{
 *   收口证据（settlement evidence，任一在场即为终态候选）：
 *     - `round-settled:<n>`      —— 轮次台账当前轮已结算（`settledAt` 在）＝契约的
 *                                   SETTLED 状态（三层分离里的轮次层事实）；
 *     - `legacy-archive-flag`    —— 存量记录的归档旗（**只读兼容**：本批起零新写入，
 *                                   863 条在盘旧记录的既有终态读数原样保留）。
 *   活标记（live marks，任一在场即非终态）：
 *     - frozen / standby / restart-interrupted / cancel-requested（注册表既有槽，
 *       逐条与阶段①判据①同值域）。
 *   子项（判据②同判据）：本身份自己的子代理任务行全部收口（`<dataRoot>/subagent-tasks/<id>.json`）。
 * }}}
 *
 * 由此派生两个读数（**同一份判据，四个消费腿共用**）：
 *  - [[Reading.identityClosed]]：身份层收口（证据在 ∧ 四个活标记空）——阶段① 判据① 的输入；
 *  - [[Reading.settled]]：**完整收口**（identityClosed ∧ 子项全收口 ∧ 子项档可解）——
 *    四条原先吃归档旗的行为腿（崩溃恢复跳过 / 复活拒绝 / 唤醒候选 / 7 天 TTL）统一改吃本读数。
 *
 * == 破坏性动作零新写（no deletion）==
 * 本件只**读**：不写盘、不建目录、不删行、不摘旗。身份收口后记录/子代理记账/转录/worktree
 * 原样留存；「用户不想再看」的表达改走 [[DelegateRegistry.Record.disposition]] 的**非破坏
 * 性**确认/忽略旗（可清除、零删除、记录与历史不变）。
 *
 * == 可见性（活动窗 ↔ 历史窗）==
 * 行落历史窗的判据 = `settled ∧ (disposition 在场 ∨ 存量归档旗在)`：
 *  - 新制：收口 ∧ **显式**确认/忽略 ⇒ 历史窗；清除旗即回活动窗（纯派生，零写盘复现）；
 *  - 存量：归档旗 = 老世界「已归档」的表达，读面**等价折叠成历史窗**（863 条旧记录的面板
 *    观感零变化），但引擎侧不再有任何归档写点。
 */
object DelegateVisibility:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.visibility")

  /** 轮次收口证据名（前缀 + 轮号；审计/诊断 greppable 面）。 */
  val RoundSettledPrefix: String = "round-settled:"

  /** 存量归档旗证据名（只读兼容；本批零新写入）。 */
  val LegacyArchiveEvidence: String = "legacy-archive-flag"

  /** 活标记词（非终态面；值域与注册表槽一一对应）。 */
  val FrozenMark: String = "frozen"
  val StandbyMark: String = "standby"
  val RestartInterruptedMark: String = "restart-interrupted"
  val CancelRequestedMark: String = "cancel-requested"

  /** 阻塞项词（诊断/面板读数用；缺证据 = 保守非终态，绝不判成收口）。 */
  val NoEvidenceBlocker: String = "no-settlement-evidence"

  /** 子项档在场但不可解时的占位 id（与阶段①判据②同值域）。 */
  val UnreadableChildId: String = "(unreadable-child-file)"

  /**
   * 子项读数（本身份自己的子代理任务行；判据②的原始读单点）。
   * `fileUnreadable` = 档在场但不可解——**缺证据不算收口**（保守：不可解按有在飞子项计）。
   */
  final case class LiveChildren(count: Int, ids: List[String], byStatus: List[(String, Int)], fileUnreadable: Boolean)

  /** 子项收口 status 值域（`SubAgentTaskStore` 投影面同款）。 */
  val ChildTerminalStatus: Set[String] = Set("completed", "failed", "cancelled")

  /** 子项收口执行值域（与 [[nebflow.core.project.RecoverySummary]] 判据② 同源镜像）。 */
  val ChildTerminalExecution: Set[String] = Set("finished", "failed", "cancelled", "interrupted")

  /** 子项未收口判据（三路；缺字段不读作 false——旧行无这些键是常态）。 */
  def isLiveChild(t: SubAgentTask): Boolean =
    !ChildTerminalStatus.contains(t.status) ||
      t.executionStatus.exists(s => !ChildTerminalExecution.contains(s)) ||
      t.incomplete.contains(true) ||
      t.notificationPending.contains(true)

  /** 子项档路径（`SubAgentTaskStore.sessionFile` 同布局：`<dataRoot>/subagent-tasks/<id>.json`）。 */
  def childTaskFile(scope: String): os.Path =
    PathUtil.dataRoot / "subagent-tasks" / s"$scope.json"

  /**
   * 子项原始读（零 ActorSystem；档缺席 = 零子项；档在场不可解 = 保守记 1 条未知在飞子项，
   * 与阶段①判据② 逐字同判据——本单点是它的唯一实现，判据② 消费本函数）。
   */
  def liveChildrenOf(scope: String): LiveChildren =
    val p = childTaskFile(scope)
    if !os.exists(p) then LiveChildren(count = 0, ids = Nil, byStatus = Nil, fileUnreadable = false)
    else
      io.circe.parser.decode[List[SubAgentTask]](os.read(p)) match
        case Right(rows) =>
          val live = rows.filter(isLiveChild)
          LiveChildren(
            count = live.size,
            ids = live.map(_.taskId).sorted,
            byStatus = live.groupBy(_.status).view.mapValues(_.size).toList.sortBy(_._1),
            fileUnreadable = false
          )
        case Left(_) =>
          LiveChildren(count = 1, ids = List(UnreadableChildId), byStatus = List((UnreadableChildId, 1)), fileUnreadable = true)

  /**
   * 一条身份的状态派生读数（**同一份判据的唯一形状**）。
   *
   * @param identityClosed 身份层收口：收口证据在场 ∧ 四个活标记全空（阶段①判据①的输入）
   * @param settled        完整收口：identityClosed ∧ 子项全收口 ∧ 子项档可解（四条行为腿的输入）
   * @param evidence       收口证据词（定序；审计面）
   * @param blockers       未收口项清单（定序；每条一个命名事实）
   * @param disposition    非破坏性确认/忽略旗（`DelegateRegistry` 值域）
   * @param settledAt      状态派生的收口时刻（轮次结算刻 ∨ 存量归档刻）——TTL 锚
   * @param legacyArchived 存量归档旗在场（只读兼容读数）
   */
  final case class Reading(
    identityClosed: Boolean,
    settled: Boolean,
    evidence: List[String],
    blockers: List[String],
    disposition: Option[String],
    settledAt: Option[Long],
    legacyArchived: Boolean,
    liveChildItems: Int,
    childFileUnreadable: Boolean
  ):

    /** 是否落入历史窗（收口 ∧ 显式确认/忽略 ∨ 存量归档旗——见对象头注「可见性」）。 */
    def historyWindow: Boolean = settled && (disposition.isDefined || legacyArchived)

    /** 活动窗可见（面板平铺面）。 */
    def activeWindow: Boolean = !historyWindow

    /** 面板/审计读数词（greppable）。 */
    def windowWord: String = if historyWindow then "history" else "active"

  /** 空读数（记录缺失形态；保守 = 未收口）。 */
  val Empty: Reading = Reading(
    identityClosed = false,
    settled = false,
    evidence = Nil,
    blockers = List(NoEvidenceBlocker),
    disposition = None,
    settledAt = None,
    legacyArchived = false,
    liveChildItems = 0,
    childFileUnreadable = false
  )

  /**
   * 纯派生单点（全部输入现读给定；零盘面副作用——spec 可直驱）。
   *
   * 收口证据取**当前轮的结算状态**（`rounds` 的现值行）而非「曾经结算过」：开新轮（Mail 续唤）
   * 后当前轮回到未结算 ⇒ 身份自动回活动窗，**零旗写盘**即可复现「复活即回现役」的既有语义。
   * 轮次台账在场时**取代**存量归档旗（旧世界的原语是「开轮时摘旗」，本批零写盘复现同义）；
   * 台账缺席（旧记录形态）才读存量旗，863 条在盘旧记录的既有读数因此原样保留。
   */
  def read(record: Option[DelegateRegistry.Record], rounds: List[DelegateRoundLedger.RoundRow], children: LiveChildren): Reading =
    record match
      case None => Empty
      case Some(rec) =>
        val marks = List(
          Option.when(rec.frozenSince.isDefined)(FrozenMark),
          Option.when(rec.standbySince.isDefined)(StandbyMark),
          Option.when(rec.restartInterruptedAt.isDefined)(RestartInterruptedMark),
          Option.when(rec.cancelRequestedAt.isDefined)(CancelRequestedMark)
        ).flatten
        val legacy = rec.archivedAt
        val roundRows = rounds.filter(_.kind == DelegateRoundLedger.KindRound)
        val current = roundRows.sortBy(_.roundNo).lastOption
        // 轮次层**在场即取代**存量归档旗：开新轮（Mail 续唤/复活）后当前轮未结算 ⇒
        // 零旗写盘自动回活动窗。存量归档旗的只读兼容因此仅适用于**无轮次台账**的旧记录
        // （863 条在盘记录的既有终态读数原样保留）。
        val (evidence, settledAt) =
          if roundRows.nonEmpty then
            (
              current.filter(_.settledAt.isDefined).map(r => s"$RoundSettledPrefix${r.roundNo}").toList,
              current.flatMap(_.settledAt)
            )
          else (legacy.map(_ => LegacyArchiveEvidence).toList, legacy)
        val identityClosed = evidence.nonEmpty && marks.isEmpty
        val settled = identityClosed && children.count == 0 && !children.fileUnreadable
        val blockers =
          (Option.when(evidence.isEmpty)(NoEvidenceBlocker).toList ++
            marks.map(m => s"live-mark:$m") ++
            Option.when(children.fileUnreadable)("child-items-file-unreadable").toList ++
            Option.when(children.count > 0)(s"child-items:${children.count}").toList).sorted
        Reading(
          identityClosed = identityClosed,
          settled = settled,
          evidence = evidence,
          blockers = blockers,
          disposition = rec.disposition,
          settledAt = settledAt,
          legacyArchived = legacy.isDefined,
          liveChildItems = children.count,
          childFileUnreadable = children.fileUnreadable
        )

  /** 现读派生（注册记录 + 轮次台账 + 子项档；永不抛——读面故障 = 保守未收口）。 */
  def readOf(id: String): Reading =
    try read(DelegateRegistry.find(id), DelegateRoundLedger.currentRounds(id), liveChildrenOf(id))
    catch
      case _: Throwable =>
        logger.warn(s"[delegate-visibility] read failed for '$id' — treating as open (conservative)")
        Empty

  /** 现读判读：身份是否**完整收口**（四条行为腿共用的单点短形）。 */
  def settledOf(id: String): Boolean = readOf(id).settled

  /** 记录级现读判读（已持记录时免二次读盘）。 */
  def settledOf(rec: DelegateRegistry.Record): Boolean =
    try read(Some(rec), DelegateRoundLedger.currentRounds(rec.id), liveChildrenOf(rec.id)).settled
    catch case _: Throwable => false

end DelegateVisibility
