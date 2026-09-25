/* 从 NodeEngine 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.project

import nebflow.agent.*
import nebflow.core.plugin.PluginRegistry
import nebflow.shared.Message

private[project] object NodeEngineContract:
  /**
   * `cascadeCancelledIds` 的 FIFO 上界（chaincancel 批 2026-09-17）：见该 Ref 头注——
   * 只为防长跑进程无界增长；超限丢最旧，最坏后果 = 已取消下游被补一条 `pendingSuccession`
   * 噪标（零功能影响）。
   */
  val CascadeSuppressCap: Int = 512

  /**
   * **L3 硬恢复的级联权限单点（判据 M8 的硬不变量，作者三答 4 参照的 §6-M8）**：
   * `deferDetach = true`（L3 硬恢复**中间态**——bridge Cancelled → 5s → resume）⇒
   * **级联强制 false**。
   *
   * 理由（与设计 §3.4 拒绝 O1 同源）：L3 中间态是**瞬时**终态，5s 后 resume 可能让节点
   * **复活**；若此刻已把下游连带 `cancelled`（不可重激活），拓扑已毁、resume 成功也无
   * 可续跑的接线 ⇒ 恢复路径被结构性掐死。
   *
   * 形态 = 恒 false 的级联旗标（本批**不**给 L3 腿接任何级联写路径，故它是「结构事实
   * 的显式化 + 可失败的守卫」而不是一个可配开关）：L3 腿以本函数为准，日后若有人把
   * L3 腿改接到 `cancelNodes`/链级写路径上，腿上的守卫行立即抛出。
   */
  def l3CascadeAllowed(deferDetach: Boolean): Boolean = !deferDetach

  /**
   * **`prunedReferrers` 派生（R2 报告字段，纯函数）**：一次取消操作前/后快照对比得出
   * 「因摘除被改写 `in` 的引用方」= 原本 `in ∋ 某个取消成员`、操作后不再含该 id 的
   * 节点（升序）。机械可核、与执行顺序无关（M4），也覆盖了 `reapStaleRunning`
   * 内部 [[NodeEngine.cancelNode]] 路径的摘除（不依赖任何返回值透传）。
   */
  def prunedReferrersBetween(before: FlowMapState, after: FlowMapState, cancelIds: Set[String]): List[String] =
    after.nodes.values
      .filter { n =>
        before.nodes.get(n.id).exists(b => cancelIds.exists(cid => b.in.contains(cid) && !n.in.contains(cid)))
      }
      .map(_.id)
      .toList
      .sorted

  /**
   * completed 节点显示 TTL（24h——2026-09-02 作者裁定；测试档可缩短——ProjectActor
   * 注入）。2026-09-07 裁定收紧：仅 completed 带 TTL 到期自动归档；failed/cancelled
   * （与 blocked 同）ttlExpireAt=None 不过期——死亡现场保留主图待上层裁决。
   */
  val TtlDisplayMs: Long = 24 * 60 * 60 * 1000L

  /**
   * startNode 翻转事务的结局（trigger-chain-fix §6.1 CAS 守卫）：Done=本 fiber
   * 完成翻转（唯一赢家，继续 spawn）；LostRace=败方（他者已翻转到 Running——
   * fork 并行化的正常形态，安静回滚不事件）；Aborted=真异常（节点消失/barrier
   * 未齐/终态竞合）→ start-aborted 事件 + 错误上抛。判定在 mutateWithResult
   * 事务内产出（翻转后状态恒 Running，事后补查无法区分赢家与败方）。
   */
  enum FlipOutcome:
    case Done, LostRace
    case Aborted(reason: String)

  /**
   * trigger-starved 触发阈值（§6.3）：同一节点连续 ≥N 轮资格回扫均满足资格却仍
   * 非终态无会话（fork 启动未生效）才落事件——每饥饿期单发（计数恰等于阈值时），
   * 避免每 tick 刷屏。
   */
  val StarvedRounds: Int = 2

  /**
   * mount-stalled 停滞阈值（mount-enforce 批 20260905，作者裁定「节点一旦挂载必须
   * 一定生效」）：可触发点后 60s 仍未触发（仍 pending/wiring 且无 running/wiring
   * 上游）→ mount-stalled 事件留痕（含节点 id+等待原因）+ settleSweep 既有资格回扫
   * 接管补触发。双保险的时间维度信号（轮次维度由 trigger-starved 承担）。
   */
  val MountStalledMs: Long = 60_000L

  /**
   * mount-stalled **告警升级**间隔（engine-defects 批 #85，2026-09-15）：同一停滞期在
   * 首条之后每过本间隔**再发一条**（summary 带 `escalation=#N`），直到节点脱离停滞集。
   *
   * 动因（真身 `.nebflow/flow-map-events.jsonl:5553` + `:5581`）：R4 `pendingSuccession`
   * 形态下 barrier 被永久 hold，唯一出口是分发器人工 `NodeEdit`（`NodeTools.scala:2373-2390`）；
   * 旧「单发 Set」让一个可持续数十分钟、并且会**堵死整条合并队列**的停滞只留下一条
   * 事件 = 事实上的静默。取 10min（≥ MountStalledMs 的 10 倍）：既足以在 30s tick 上
   * 反复提醒，又不会把事件流刷成噪音（一个停滞期 1 小时 ≈ 6 条）。
   *
   * **现读 prop**（`nebflow.stall.reNotifyMs`，`LoopMaxRounds` / `BgateWaitTimeoutMs`
   * 同款先例）——spec 可即时把间隔压到毫秒级做确定性断言，无需等真实 10min。
   *
   * 生产值 = [[nebflow.shared.Defaults.StallReNotifyMs]]（现读 prop）；spec 走构造器
   * 接缝 `stallReNotifyMs`（`destroyWindowMs` / `notifyQuietMs` 同款「避开全局 prop
   * 的跨 suite 污染」纪律——实测本工程测试 JVM 下 `sys.props.update` 与
   * `System.setProperty` **写入后同进程读回均为空**（`Obtained: None`）⇒ 用 prop 做
   * spec 注入口会**静默失效**，断言只会看到默认值）。
   */
  def StallReNotifyMs: Long = nebflow.shared.Defaults.StallReNotifyMs

  /**
   * 死会话自动收敛的 spawn 窗口宽限（僵尸收敛批 2026-09-06）：节点 status 翻
   * Running 后，agent registry 登记发生在 spawn 之后（runWithAgent :816）——Flipped
   * 瞬到 registry 登记之间存在微小窗口。自动对账只在「已过该宽限仍未登记 agent」
   * 时才判定死会话，避免把刚启动（fiber 活着、agent 即将登记）的节点误判收殓。
   * 宽限取 60s（agent spawn + plugin MCP acquire 量级远小于此）。
   */
  val StaleSpawnGraceMs: Long = 60_000L

  /**
   * startNode 翻转竞发的败方信号（trigger-chain-fix）：startNode 单点吞掉——
   * 败方安静退出，赢家持有节点生命周期（会话、cancelSig、终态分发）。
   */
  final case class StartRaceLost(nodeId: String)
      extends RuntimeException(s"start race lost ($nodeId) — winner owns the session")

  /**
   * abandon 摘边台账（**案 A 2026-09-14，作者 17:24 拍板**；写点
   * [[NodeEngine.detachAbandonedNode]]）。三个 referrer 清单都是**活动区**里真正被
   * 改写的节点 id（升序、去重），`selfRewired` = 被退役节点自身的 in/out/deps 确有
   * 需要改写的挂线。四者全空 ⇒ 摘边是 no-op（幂等判据：重复调用恒空）。
   *
   * 字段名 = 被摘掉的**引用方向**（不是被摘的边方向）：
   *   - `inMirrors`：`in` 里还引用退役节点、被 prune 的下游（R4 同款方向）；
   *   - `outRefs`：`out` 里还有一条指向退役节点的边、被摘除的上游；
   *   - `depsRefs`：`deps` 里还引用退役节点、被摘除的下游。
   */
  final case class RetireDetach(
    inMirrors: List[String] = Nil,
    outRefs: List[String] = Nil,
    depsRefs: List[String] = Nil,
    selfRewired: Boolean = false,
    /**
     * **拒绝态受害集**（failroute-guard 批 2026-09-21 · 案 A A1）：本次摘边前后各算
     * 一次「被摘掉 fail 选通边的 referrer」中**摘前合法 ∧ 摘后失路**的 verifier id
     * （升序去重）。纯台账、零额外写；告警按批收口消费本字段
     * （`NodeEngine.emitVerifierRouteLost`）。旧台账语义零改动 ⇒ 默认 Nil。
     */
    routeLost: List[String] = Nil
  ):

    /**
     * 顶层「有没有动过」判据（幂等出口：空 ⇒ 零写、零帧）。**不含 `routeLost`**：
     * 受害集是「本次摘边的后果读数」，非「有没有摘边」的判据（无摘边 ⇒ 受害集必空）。
     */
    def isEmpty: Boolean =
      inMirrors.isEmpty && outRefs.isEmpty && depsRefs.isEmpty && !selfRewired
    def referrers: List[String] = (inMirrors ++ outRefs ++ depsRefs).distinct.sorted
  end RetireDetach

  // ── 投递可靠性批次常量（2026-09-04 四缺口）──────────────────

  /**
   * 缺口2：补投/重投新鲜度阈值（24h，取 TtlDisplayMs 同口径——超过一个显示
   * 周期未被消费的欠账即「历史欠账」，合并汇总降噪；结果照投不丢）。
   */
  val StaleRedeliveryMs: Long = TtlDisplayMs

  /** 缺口2：汇总通知每节点结果摘要截断长度。 */
  val StaleSummaryPerNodeChars: Int = 160

  /**
   * 缺口3：测试夹具信封家族（宁窄勿宽——09-03 实证宿主真实数据唯一可确证
   * 夹具家族：cancel-test 取消语义验证节点，见 phd-notebook 归档 cancel-test、
   * cancel-test-3..11；精确锚定全名，真实节点名巧合含 test 不命中）。
   */
  val FixtureFamilyRegex = "^(cancel-test|cancel-test-\\d+)$".r

  /**
   * 缺口3：夹具载荷自证标记（09-03 实证夹具任务正文均带此前缀）。双条件
   * 缺一不可：名字 ∧ 载荷双确认才排除——真实节点名巧合命中家族但载荷是
   * 真实工作 → 照常投递。
   */
  val FixtureTaskMarker = "取消验证节点"

  /** 缺口3：夹具信封判定（纯函数便于单测）。 */
  def isFixtureEnvelope(node: NodeDef): Boolean =
    FixtureFamilyRegex.matches(node.name) && node.task.exists(_.contains(FixtureTaskMarker))

  /**
   * 缺口4：同 (identity, status) 重复通知去重窗口（60s——秒级抖动口径：挂载
   * 扫描与周期扫描竞态、ProjectCreate 合并回报三连投实证 09-03）。进程内
   * 内存窗，与 V8 nebulaDeliveredAt 持久账本正交（账本管跨重启 at-least-once）。
   */
  val NebulaDedupWindowMs: Long = 60_000L

  // `DispatcherSourceMarker`（`"dispatcher"`）与 `DispatcherTaskSummaryChars` 同批删净
  // （R2「一个 Mail 统一」批 2026-09-12，R7-b）：桥收敛后 source=="dispatcher" 族
  // **无生产者**（恒 0）。保留死常量会误导「该族仍会生产」——按 B5-c 精神随调用点一起删。
  // 观测口径变化登记：`SessionRecorder` 六字段取值集不再出现 `dispatcher`；
  // `web/js/chat.js` 的 `INJECTED_SOURCE_LABELS` 本无该键（走通用回退分支），前端零必需改动。

  /**
   * 阶段 2b Plugins：spawn 前解析完成的分配物（§B.4 第 4 步）。
   * empty = flag 关 / 节点无分配 / 无可注入内容——旧行为零变化。
   */
  final case class PluginPreparation(
    /**
     * <injected-plugins> 全文块（逐 plugin 逐 skill，frontmatter 已去除 +
     * ${SKILL_DIR} 已替换）；"" = 无注入。
     */
    injectedBlock: String,
    /** 含 MCP server 的 plugin（acquire 输入；serverId = plugin_<p>_<s>）。 */
    mcpPlugins: List[PluginRegistry.PluginDef],
    /** org.nebflow/tools 授予的 builtin 工具名（白名单已在装载层校验）。 */
    builtinTools: List[String]
  )

  object PluginPreparation:
    val empty: PluginPreparation = PluginPreparation("", Nil, Nil)

  // ── NodeMessage（20260905 机制批，作者裁定六条语义）──────────────────

  /**
   * 裁定②：running 注入文本的可识别前缀——与用户任务文本、节点结果投递明确
   * 区分（分发器 NodeMessage + 节点名 + 时间戳来源标注）。
   */
  val NodeMessagePrefix: String = "[NODE-MESSAGE]"

  /** 裁定⑤：消息留痕事件类型（FlowMapEventLog append-only JSONL）。 */
  val NodeMessageEventType: String = "node-message"

  /** running 注入文本头（前缀 + 来源标注单点）。 */
  def nodeMessageHeader(nodeName: String): String =
    val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    s"$NodeMessagePrefix 来源：任务分发器 NodeMessage · 节点 $nodeName · $ts"

  /**
   * 裁定③：任务追加分节头（NodeEdit release note 先例同款形态；裁定原文
   * 「== 分发器补充（NodeMessage <时间戳>） ==」）。undelivered=true 时追加
   * 「注入未达」标注（裁定②竞态兜底——留痕不丢）。
   */
  def nodeMessageSection(undelivered: Boolean): String =
    val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    val base = s"== 分发器补充（NodeMessage $ts） =="
    if undelivered then base + "\n（注入未达：会话在消息投递前已终结，仅记录不注入——留痕不丢）" else base

  /** 节点 id 前缀（sessionId = "node-<uuid>"）。 */
  val SessionPrefix = "node-"

  // ── 未申报提醒阶梯常量（noderpt 批 A 段 2026-09-11 作者裁定）──────────────────

  /**
   * 提醒轮注入文本的**专用前缀头**（文案首行；agent 与取证双面可识别——与
   * `[NODE-MESSAGE]`（分发器 NodeMessage 通道）区分：本头 = 引擎未申报兜底）。
   */
  val NodeReportReminderPrefix: String = "[NODE-REPORT-REMINDER]"

  /** 未申报留痕事件类型（FlowMapEventLog）：阶梯期每拍一条 + quiescent 档周期一条。 */
  val NodeReportMissingEventType: String = "node-report-missing"

  /** 死会话 bg-wait 豁免留痕事件类型（代裁 6：把「不算死」的节点标进事件供人监督）。 */
  val DeadSessionBgWaitEventType: String = "dead-session-bg-wait"

  /**
   * 提醒轮注入的 ExternalEvent source 标签（agent 侧 `<system-reminder>` 头内标识；
   * 不进可见气泡白名单 = 不给前端造「用户气泡」，提醒面靠事件 + transcript）。
   */
  val NodeReportReminderSource: String = "node-report-reminder"

  /** 事件 `stage` 取值：阶梯期（有注入） / quiescent（只留痕不注入）。 */
  val NodeReportStageActive: String = "active"
  val NodeReportStageQuiescent: String = "quiescent"

  // ── 释放唤醒常量（noderpt 批 F2，2026-09-11 复核 D2 修复）──────────────────────
  //
  // 与提醒阶梯同族但**不同语义**：提醒 = 「你没申报」（注入提醒轮 + 事件）；
  // 释放唤醒 = 「你申报了，但桥没观测到那一轮」（唤醒桥复检放行 + 事件，不提醒）。
  // 前缀头/ source / 事件类型三者单点区分，取证侧不必猜是哪条腿。

  /** 释放唤醒注入文本的专用前缀头（首行；与 `[NODE-REPORT-REMINDER]` 区分）。 */
  val NodeReportReleaseWakePrefix: String = "[NODE-REPORT-RELEASE-WAKE]"

  /** 释放唤醒注入的 ExternalEvent source 标签。 */
  val NodeReportReleaseWakeSource: String = "node-report-release-wake"

  /** 释放唤醒留痕事件类型（FlowMapEventLog）：每次唤醒一条（单发记账见 `releaseWakeSent`）。 */
  val NodeReportReleaseWakeEventType: String = "node-report-release-wake"

  /**
   * 释放唤醒注入文案（并非「你没申报」的提醒——申报**已到**，缺的只是桥的观测）：
   * 要求 agent 用本轮回复给出节点最终结果文本（重述即可，无需再做工作），该轮回复经桥
   * 复检后作为节点结果投递。**不含判罚/威胁措辞**（与提醒文案同纪律：本族永不判 failed）。
   */
  def reportReleaseWakeText(nodeName: String): String =
    s"""$NodeReportReleaseWakePrefix node $nodeName's node_report arrived, but the completion bridge never saw the declaring turn.
       |Give this turn's text as the node's **final result** — restate the last conclusion, no further tools needed; it is released as the node result and delivered downstream. No failure verdict, no kill.""".stripMargin

  /**
   * 释放唤醒留痕 summary（`k=v` 单空格分隔，与 [[reportMissingSummary]] 同构，可被
   * [[parseReportMissingSummary]] 解析）：pendingSince（未申报起点）· delivered（注入是否达）。
   */
  def reportReleaseWakeSummary(sessionId: String, pendingSince: Long, delivered: Boolean): String =
    s"node_report declaration present but the completion bridge never observed the declaring turn — " +
      s"release wake injected: stage=release-wake session=$sessionId pendingSince=$pendingSince delivered=$delivered"

  // ── 终态延迟销毁窗口常量（noderpt 批 B 段 2026-09-11 作者裁定：一律存活 30 分钟再销毁）──

  /**
   * **申报未消费补偿事件类型**（engine-defects 批 #239①，2026-09-15；写点 =
   * `NodeEngine.compensateUnconsumedReport`）：`node_report` 申报已被 `drain`
   * take-and-remove 取走，而终态写因 R2 fresh-read 纪律**拒写**（节点已消失 /
   * 状态已变 / 关机期 draining 抑制）时，把申报全文补偿写回本审计流。
   * summary = `node_report NOT consumed — terminal write refused …; session=<sid>
   * category=<c> detail=<d> suggestion=<s>`（detail/suggestion 全量、不截断），
   * 取回即 grep 该 type：`grep node-report-unconsumed <ws>/.nebflow/flow-map-events.jsonl`。
   *
   * 与 `blocked` / `bg-harvest` 等既有行**分开记账**：本条回答的是「这个节点申报过，
   * 为什么最终状态里没有它、也没人知道」——旧口径下该形态**零痕迹**（drain 取走即移除、
   * 无补偿写回），是本批闭合的那条缝在观测面上的唯一锚点。
   */
  val ReportUnconsumedEventType: String = "node-report-unconsumed"

  /**
   * **申报被非终态分支消费**事件类型（#239② ⑤-(1)，2026-09-15；写点 =
   * [[NodeEngine.noteNonTerminalConsumption]]）：Loop 的 worker 支把 `pass`/`finish`
   * 申报消费进「本轮照常进 verify 裁决」——该支**没有**终态写，也就没有落地判词可挂，
   * 于是申报会在内存与日志两处同时消失而无一行说明去向。本行补的是「申报被谁消费掉了」
   * 的机械痕迹（`kind=nonterminal branch=loop-worker-forward`，含 category/detail 全量）。
   * 与 `node-report-unconsumed` 成对：那条 = 「消费了但没落地」，本条 = 「消费了且语义
   * 正常（非终态）」。取证：`grep node-report-consumed <ws>/.nebflow/flow-map-events.jsonl`。
   */
  val ReportConsumedEventType: String = "node-report-consumed"

  /**
   * 终态销毁登记事件（写点 = 终态时刻的 `scheduleDestroy`；窗口开启的唯一痕迹——
   * failed/cancelled 与 completed/blocked 口径一致，挂起腿不写）。
   */
  val DestroyScheduledEventType: String = "node-destroy-scheduled"

  /**
   * 终态销毁完成事件（写点 = `sweepDestroyWindows` 到点回收；summary 含 destroyAt /
   * 会话清单 / 被收殓任务数，事后可与 `node-destroy-scheduled` 对齐「窗口是否走完」）。
   */
  val DestroyedEventType: String = "node-destroyed"

  /**
   * 窗口撤销事件（写点 = `sweepDestroyWindows` 发现「带 destroyAt 的节点已非终态」=
   * 窗口内被 reactivate/重跑 ⇒ 撤销窗口、解除禁 spawn；正常路径在翻转点即已清零，
   * 本事件只覆盖异常残留）。
   */
  val DestroyWithdrawnEventType: String = "node-destroy-withdrawn"

  /** 档位人话标签（阈值 ms → "10min"/"1h"/"10s"）——提醒文案与事件共用单点。 */
  def reportRungLabel(ms: Long): String =
    if ms > 0 && ms % 3600000L == 0 then s"${ms / 3600000L}h"
    else if ms > 0 && ms % 60000L == 0 then s"${ms / 60000L}min"
    else s"${ms / 1000L}s"

  /**
   * 提醒轮注入文案（nrloop 一期 2026-09-12 **中性化**，设计 §2 R9 / 附 C2-R9：
   * 文案不按角色分支、不并列值域——「合法取值以工具说明为准」一句把值域解释权收归
   * `node_report` description（按角色单一权威），提醒只做「你还没申报」这一件事）。
   * 适用范围 = **全部节点会话**（`remindUnreportedNodes` 本就按 Running 全扫、无角色
   * 过滤 ⇒ 判据零改动，见该函数头注）。**不含任何判罚/威胁措辞**——本阶梯永不判
   * failed（相对设计稿 §4.2 的核心改判），文案不得暗示「否则会失败」。
   * 实际投递文本 = 本文案**原文**（首行前缀头 `[NODE-REPORT-REMINDER]`），经
   * `AgentCommand.ExternalEvent(source = NodeReportReminderSource, eventType = "reminder")`
   * 进节点会话唤醒轮——不叠 `[NODE-MESSAGE]` 头（那条是分发器 NodeMessage 通道专用，
   * 前缀头单点区分来源；注入通道差异见 [[injectReminderTurn]]）。
   */
  def reportReminderText(nodeName: String, rung: Int, maxRungs: Int, rungMs: Long, elapsedMs: Long): String =
    s"""$NodeReportReminderPrefix you handed off without calling node_report — declare this node's terminal state now (beat $rung/$maxRungs, rung ${reportRungLabel(
        rungMs
      )}, waited ${elapsedMs / 1000}s, node $nodeName).
       |The node stays Running, result undelivered, until you declare: call node_report (values per its description, with detail), then write your wrap-up report. This reminder never fails the node or kills the session.""".stripMargin

  /**
   * `node-report-missing` 的结构化 summary（`k=v` 单空格分隔，含 `=` 的 token 由
   * [[parseReportMissingSummary]] 单点解析——`FlowMapEventLog.append` 的顶层只有
   * ts/type/project/nodeId/summary 五字段，扩展字段一律进 summary，与
   * `FlowMapEventLog.chainArchivedSummary` 先例同构）。字段清单：
   *   stage（active|quiescent）· rung（第几拍，形如 `3/8`）· rungMs（该拍阈值）
   *   · elapsedMs（已等待）· pendingSince（= reportPendingSince）· reminderCount
   *   · ladderExhausted（末拍/之后为 true）· delivered（仅阶梯期有：提醒轮是否投递达）。
   */
  def reportMissingSummary(
    stage: String,
    rung: Int,
    maxRungs: Int,
    rungMs: Long,
    elapsedMs: Long,
    pendingSince: Long,
    reminderCount: Int,
    ladderExhausted: Boolean,
    delivered: Option[Boolean]
  ): String =
    val base = List(
      s"stage=$stage",
      s"rung=$rung/$maxRungs",
      s"rungMs=$rungMs",
      s"elapsedMs=$elapsedMs",
      s"pendingSince=$pendingSince",
      s"reminderCount=$reminderCount",
      s"ladderExhausted=$ladderExhausted"
    ) ++ delivered.map(d => s"delivered=$d").toList
    s"node finished without a node_report declaration — ${base.mkString(" ")}"

  end reportMissingSummary

  /** summary 解析（结构化字段单点，消费者免手工切分；非 `k=v` token 忽略）。 */
  def parseReportMissingSummary(summary: String): Map[String, String] =
    summary
      .split("\\s+")
      .iterator
      .filter(t => t.indexOf('=') > 0)
      .map { t =>
        val i = t.indexOf('=')
        t.substring(0, i) -> t.substring(i + 1)
      }
      .toMap

  // ── P2（stuck 自动恢复批）：runtime 定位键修正（硬依赖 2，正交实验已证**正**）──
  //
  // 事故形态：`rts.find(rt => rt.engine.rootSessionId == rec.rootSessionId)` 在
  // **多项目挂载**下必然歧义——`ProjectActor.mountAll` 把**同一个顶层 rootSessionId**
  // 交给全部项目（`GatewayMain` 单个 `rootSid` → `mountAll` → `mount` →
  // `NodeEngine(rootSessionId, …)`），`ProjectRuntimeRegistry.all` 是 Map 无序
  // （`runtimes.get.map(_.values.toList)`）⇒ 16 个同 id 候选中任取其一。
  //
  // 实证（宿主日志，2026-09-11 06:58 启动 16 项目同一 rootSessionId
  // `5cc7590a-…`）：`no node owns session`（**引擎内**节点查无，NodeEngine:436）12 次，
  // 而 watcher 层「no project runtime for …」（`rts.find` 整体落空）**0 次** ⇒
  // `find` 总是命中、命中的却总是**错的引擎**（15/16 概率），节点在别的项目 store 里。
  //
  // ⇒ 项目定位键 = **会话在哪个 store 里**，不是 rootSessionId。`sessionRef`
  // （node-/dispatcher- 会话 id）在任一 store 内唯一，故按 sessionRef 扫 store 即
  // 精确唯一解。

  /**
   * 按 `sessionRef` / `sessionRefVerify` 在 store 快照里定位节点（P2 定位键唯一入口）。
   * 全部消费点（watcher 的 resume / L3 复查 / 失败兜底）共用本函数——**禁止**再用
   * `rootSessionId` 反查项目（见上方硬依赖 2 说明）。
   */
  def findNodeForSession(snap: FlowMapState, sessionId: String): Option[NodeDef] =
    snap.nodes.values.find(n => n.sessionRef.contains(sessionId) || n.sessionRefVerify.contains(sessionId))

  // ── P3：LoopGuard 互斥（§3.4 互斥点 1）与 R-2 的三个纯函数 ────────────────

  /**
   * 互斥点 1(a) 的判据（P3，设计 §3.4；纯函数，可独立单测）：会话仍处于冻结
   * （`status==Frozen` 或 `frozenReason` 有值）⇒ 恢复必须**拒绝执行**——唤醒冻结
   * 会话意味着走「用户输入」那条会 `resetCrossTurn` 的出口，等于绕过 LoopGuard 的
   * 365 天冻结。`None`（会话已不在 registry）⇒ 不阻止（旧会话已被清场，resume 起
   * 的是全新会话，天然不进 frozen behavior）。
   */
  def frozenSessionBlocksResume(live: Option[AgentRecord]): Boolean =
    live.exists(r => r.status == AgentStatus.Frozen || r.frozenReason.isDefined)

  /**
   * 互斥点 1(b) 的判据（P3，设计 §3.4；纯函数，可独立单测）：**会话级原语**
   * （`LlmInterface.transportAbortFor` / `BgTaskRegistry.reclaimSession`，即 L2 腿）
   * 只在会话 `status == Processing` 时允许执行。
   *
   * 为什么必须**显式**（今日本就隐含——watcher 只扫 Processing）：会话级原语会波及
   * 同根会话的其它状态（例如被 LoopGuard 冻结的会话仍持有同根身份），一旦将来放宽
   * 扫描面（例如把 Frozen 纳入候选），这条约束就会**静默**失效。显式化 ⇒ 未来放宽
   * 扫描面时本闸门仍然发声（与 §2.3 三条误报原则同款纪律）。
   *
   * 守卫定义在引擎侧（本函数）、**调用点在 `TaskStuckWatcher`**——与「不改
   * `BgTaskRegistry`」的文件面纪律一致（`reclaimSession` 本体零改动）。
   */
  def sessionLevelPrimitiveAllowed(rec: AgentRecord): Boolean =
    rec.status == AgentStatus.Processing

  /**
   * R-2：transcript 重放封顶（默认 [[nebflow.shared.Defaults.StuckRecoveryReplayMaxMsgs]]
   * = 40 条）。保留**最近** `max` 条（越近越相关），并丢掉截断产生的**悬空
   * tool_result 头**（其配对的 assistant `tool_use` 已被截掉——provider 侧会因
   * 「tool_result 无对应 tool_use」拒绝请求，故必须一起丢掉）。
   *
   * 纯函数（零 IO、零 LLM）：边界可独立单测（≤max / 超限 / 全悬空 / 空输入）。
   * 返回 `(封顶后的消息, 是否发生了截断)`；第二个值驱动 [[replayCapNote]]。
   */
  def capReplayMessages(msgs: List[Message], max: Int): (List[Message], Boolean) =
    val n = math.max(1, max)
    if msgs.size <= n then (msgs, false)
    else
      val tail = msgs.takeRight(n)
      def orphanedToolResult(m: Message): Boolean = m.content match
        case Right(blocks) =>
          blocks.nonEmpty && blocks.forall(_.isInstanceOf[nebflow.shared.ContentBlock.ToolResult])
        case Left(_) => false
      val trimmed = tail.dropWhile(orphanedToolResult)
      (if trimmed.isEmpty then tail else trimmed, true)

  /**
   * 截断事实的 resume prompt 声明（不可省略：不告知 ⇒ 模型会假定上下文完整）。
   * 与 §3.3「超限则只带最近 40 条 + 摘要」同口径（摘要由模型自行按需读取工作区）。
   */
  def replayCapNote(total: Int, kept: Int): String =
    s"\n（transcript 重放封顶：磁盘上有 $total 条消息，本次只重放最近 $kept 条——" +
      "更早的上下文已省略；若需要早期细节，请自行读取工作区文件 / 项目文档。）"

  /**
   * P2「挂起不终态化」哨兵（R-1=B）——见 [[NodeEngine.suspendNode]] 与
   * `runWithAgent` 的挂起分支。走既有 `AgentEvent.Cancelled` 载体（**不改跨模块消息
   * 形态**，与 `CancelSource` 从 reason 文本前缀推导同款纪律），由引擎侧识别前缀后
   * 走「只停 actor、不终态化」的挂起腿。
   */
  val SuspendReasonPrefix = "(node-suspend"

  def isSuspendOutcome(msg: String): Boolean = msg.contains(SuspendReasonPrefix)

  /**
   * R-5 / 类⑦：`bg-harvest` 事件的 **cause** 词表（从同一份桥消息文本单点派生——
   * 不改 `AgentEvent` 形态，与 `CancelSource` 同纪律）。
   *
   * 该事件此前**无原因**：读者只能看到「node finalized (cancelled) — reclaimed bg
   * session」，看不出是引擎看门狗取消、bg 等待上限、会话猝死还是本批新增的挂起腿
   * （本板 #98 的两条诉求「为什么被 cancelled / 是否通知过」的第一个盲区即此）。
   */
  def finalizeCause(msg: String): String =
    if isSuspendOutcome(msg) then "suspended-for-recovery"
    else if msg.contains("cancelled") then "cancelled"
    else if msg.contains("wait cap") then "bg-wait-cap"
    else if msg.contains("terminated without a terminal event") then "session-died"
    else "failed"

  // ── P2：恢复锚探测结果（设计 §3.1）──────────────────────────────────────

  /**
   * 恢复锚探测结果（§3.1 的 A1/A3 + §3.2 的零产出/有产出判据）。
   *
   * A1（transcript）在主锚位：由调用方（watcher）经 `SessionStore.loadMessagesForSession`
   * 探测（引擎侧同一读点，二者同源）——不可用 ⇒ **不恢复、直接一次上报**。
   * A3（worktree/git）是产物面旁证：目录缺失 ⇒ 该锚不可用（#159 形态），
   * 降级为「A1 可用则续跑」。
   */
  final case class RecoveryAnchor(
    /** A3 可用 = worktree 目录存在 ∧ 是 git 工作树（`.git` 条目存在）。 */
    worktreeAvailable: Boolean,
    /** 探测用的工作目录（worktree 优先、回落工作区）——同时是 hasOutput 探针的 cwd。 */
    probeDir: String,
    /** §3.2 的 `P1 ∨ P2`：有产出 ⇒ 断点续跑；零产出 ⇒ 重启 turn。 */
    hasOutput: Boolean,
    /** 可读的产物证据文本（进 resume prompt 与上报文本；**不回流判据**）。 */
    outputEvidence: String
  )

  /**
   * §3.2 的 resume 分支告知（零产出 vs 有产出），拼在既有 `nodeResumePrompt` 之后。
   *
   * ⚠ **与设计 §3.2 口径的差异（单列，未证项）**：§3.2 的 P1 逐字为
   * `git rev-list --count <baseline>..HEAD`，但 `NodeDef` **不持久化任何 baseline
   * sha**（本仓无该字段）⇒ 无 `baseline` 可用。本实现取可得的只读等价物：
   * `git rev-list --count HEAD --since=<本节点 startedAt>`（「本代次新提交数」）
   * ∨ `git status --porcelain` 非空（未提交产出）。语义一致（都是「本代次是否
   * 改变了工作产物」），但**不是逐字公式**——须由验收/后续批知悉。
   */
  def stuckResumeNote(anchor: Option[RecoveryAnchor]): String =
    val prefix = "\n(hard-recovery: the hung session was interrupted and resumed from the disk breakpoint."
    anchor match
      case Some(a) if !a.hasOutput =>
        prefix +
          s" **No output last attempt** (${a.outputEvidence}; worktree " +
          s"${if a.worktreeAvailable then s"available: ${a.probeDir}" else NodeEngine.a3UnavailableNote(a.probeDir)})" +
          " — do the task from scratch; assume nothing was done.)"
      case Some(a) =>
        prefix +
          s" **Output exists** (${a.outputEvidence}" +
          (if a.worktreeAvailable then "" else s"; worktree ${NodeEngine.a3UnavailableNote(a.probeDir)}") +
          ") — verify unpersisted side effects first (git state, key files); continue from the breakpoint.)"
      case None =>
        prefix + ")"

  end stuckResumeNote

  /**
   * **A3 锚不可用时的承接语义说明**（#159/#176 ③，2026-09-14）。
   *
   * 语义（设计态 + 实现态，逐条对应）：
   *   - **S1 就地续跑**：A3 可用 ⇒ 恢复腿在与上轮**同一**目录里续跑（现行为，未改）；
   *   - **S2 失锚续跑**：A3 不可用（J1 形态）∧ A1（transcript）可用 ⇒ 恢复腿**仍然**执行，
   *     但运行时 cwd **回落项目 workspace**（`PathUtil.resolveNodeProjectRoot` 的两处均
   *     不存在 ⇒ 旧公式路径兜底），且必须把「上轮产物不可信」**显式**告知被恢复会话
   *     ——本函数即该告知的载体（否则恢复后的会话会拿「不存在的目录」当下事实）；
   *   - **S3 承接失败**：`no node owns session` ⇒ 恢复腿无法接管（[[suspendNode]] /
   *     [[hardResumeNode]] 各自留痕 + 跳过，[[failStuckRecovery]] / [[settleFailedHardResume]]
   *     兜终态）；事件流留痕见那两处（本批补）。
   *   - **S4 零产出空支**（J7）：`hasOutput=false` ⇒ 无产物可救，重激活 = 重跑本 turn 或
   *     经 `NodeEdit` 重激活/换名承接；**禁** `branch -D` 强删（审计血缘保留）。
   *
   * 本字符串进被恢复会话的 resume prompt（`ResumeContext.resumePrompt`），故措辞以
   * 「对会话下指令」的口吻写成。**禁指认责任人**（责任者未证；只写机制类）。
   */
  private[project] def a3UnavailableNote(dir: String): String =
    s"UNAVAILABLE — the directory '$dir' no longer exists (judge J1: directory gone + a git worktree " +
      "registration may remain ⇒ a recursive delete performed OUTSIDE git; #159/#176 forensics §4.3). " +
      "This run therefore starts OUTSIDE that directory (the runtime cwd falls back to the project " +
      "workspace) and the previous run's artifacts are UNRELIABLE: do not assume any file written last " +
      "round is still present — re-check before building on it"

  /**
   * 节点级 blocked 重入上限（设计 §3.1/§7.2）：blockCount 1/2 → 重入调整；
   * count=3（> 2）→ 升级 Nebula 不再重入。
   */
  val MaxBlockRoundsPerNode: Int = 2

  /**
   * 节点终态语义申报协议脚注（buildInput 末尾单点注入）。TaskBoard 批 2（§3c）：
   * 末尾增一行上报指引——与终态语义协议同点注入、同生命周期；措辞自带条件
   * （「若…给了」），无板会话注入该行无副作用。
   * blocked 结构化信号批（20260909 spec §5.2 #7）：第一优先 = 调用申报工具；
   * 同日作者裁定泛化（NodeReport 统一三语义）：工具更名 node_report，blocked
   * 细分六类之外 pass/fail 语义同走结构化申报；三语义文本锚定（首行裸 BLOCKED
   * / VERDICT: PASS/FAIL）降级为「工具不可用时」备用通道，措辞强调首行裸形态
   * 要求（6 例实证：markdown 标题/加粗/前置导语均锚定失败）。
   *
   * nrloop 一期 2026-09-12（设计 §3.1 / B9）：值域**按节点角色分化**后，本文案
   * 只保留角色中立表述（「按你的节点角色传对应值」+ 括号注明校验节点 = pass/fail），
   * verifier 专属段（verdict ≠ 节点状态）由 [[protocolFootnoteFor]] 追加——避免把
   * 两套值域并列塞进一篇脚注（R9 中性化同款纪律：解释权收归工具 description）。
   * 本 val 逐字保持旧文本除该句外的全文（下游断言锚：末行 TaskBoard 指引行、
   * `endsWith(ProtocolFootnote)` 身份断言）。
   * F8 收口（2026-09-15）：本 val = 节点终态申报协议在**引擎贡献面**（引擎编译、
   * 随系统提示词/任务输入注入的提示词文本）的单一权威文本——该面唯一完整协议，
   * blocked JSON 文法在该面仅此处承载（工具面语义另由 `node_report` 工具
   * description 与 `NodeReportTool` 承载，不属本断言域）。**非全局唯一**：agent
   * 种子条件句 `seed/agents/general/system.md:7-10` 自带完整规则文本（promptfix
   * 作者并存终态「规则各留一份」，唯一 seed 面防线，无指向本 val 的指针）；
   * `PromptSections` 段序 360 = belt+指针；内置 general 项目脚手架的 AGENTS.md
   * （**符号锚**：按角色描述该文件，不指文件路径——文件路径会随种子面增删漂移）=
   * 一行指针（自陈「本文件不复述」）——后二者只承载指针/短句，不承载
   * 完整协议。
   * 文本逐字冻结——`TaskBoardInjectionSpec` 的末行/`needs-split` 措辞钉
   * 与 `NodeChainAttributionSpec` 的 `endsWith` 身份钉同挂本 val。
   */
  val ProtocolFootnote: String =
    """── Node protocol ──
      |**Call `node_report` before wrapping up** — reporting IS the wrap-up action, not a blocked-only channel.
      |Values by role: task = `finish` / `blocked`; verifier = `pass` / `fail` (verdict — evidence in `detail`) / `blocked`; a wrong value is rejected with your role's legal list.
      |Cannot finish (upstream not ready / brief incomplete / capability mismatch / missing external condition)? Never fabricate: `blocked`, category ∈ upstream-incomplete | task-underspecified | agent-mismatch | external-dependency | needs-split | other | blocked — then still write your wrap-up report; a normal result is no substitute.
      |Unreported ⇒ stays running, result undelivered, reminders only — never auto-failed. No tool ⇒ first line exactly `BLOCKED` + JSON {"category":"…","detail":"…","suggestion":"…"}.
      |TaskBoard work order ⇒ close = done, blocked = stuck.""".stripMargin

  /**
   * verifier 专属附录段（nrloop 一期 B9；设计 §3.1 纪律③「verdict ≠ 节点状态」+
   * §3.2 verifier 语义）。**仅 `role=verifier` 注入**（[[protocolFootnoteFor]]），
   * 逐字回答校验节点最容易搞错的一件事：申报 `fail` 不等于自己失败。
   * 与 `node_report` description 该段同源同措辞（双面同文纪律，设计 §3.1）。
   */
  val VerifierVerdictFootnote: String =
    """── Verifier node ──
      |`pass` / `fail` = verdict on the object under review, never this node's status: `fail` ≠ your failure (this node still completes; verdict ≠ status). The engine routes the re-run along `(fail)<target>:loop` — you never redo the work.
      |A real execution failure (dead session / LLM error) is engine-judged — no agent channel. Stuck ⇒ `blocked`, as for a task node.""".stripMargin

  /**
   * 角色分支脚注（nrloop 一期 B9 单点）：`verifier` = 基线 + [[VerifierVerdictFootnote]]
   * 附录；其余（含 None / 未知）= 基线**逐字原文**（旧行为零漂移——既有
   * `endsWith(ProtocolFootnote)` / 末行断言全部保持）。
   */
  def protocolFootnoteFor(role: Option[String]): String =
    if NodeRoles.normalize(role.getOrElse("")) == NodeRoles.Verifier then
      ProtocolFootnote + "\n" + VerifierVerdictFootnote
    else ProtocolFootnote

  // ── 链级抽象 P2：链上下文透传（20260910_process-doc-chain-attribution-spec §9.2）──

  /**
   * 节点所属链的 spawn 时刻快照（§9.2 项 4/5/7）：chainId = `chain-<分量最早
   * createdAt 节点 id>`，title = 链名三级推导单点（FlowMapStore.chainTitle），
   * memberCount = 分量成员数。三字段同源同刻——首条消息链头与会话身份
   * （ToolContext.flowChainId）由同一快照喂，两处口径恒同。
   */
  final case class NodeChainContext(chainId: String, title: String, memberCount: Int)

  /**
   * 首条消息链头（§9.2 项 7 原文字面）：`[chain: <title> (<chainId>) · N 节点]`
   * ——节点执行会话内唯一能拿到「我属于哪条链」的文本面（节点无 NodeList，
   * registry.scala:70 口径）。单行、无换行尾随。
   */
  def chainHeaderLine(c: NodeChainContext): String =
    s"[chain: ${c.title} (${c.chainId}) · ${c.memberCount} 节点]"

  /**
   * 过程文档命名·溯源提示段（R-3，2026-09-11 作者裁定；替代原「过程文档元数据头
   * 模板」——本批 §9.2 项 8 注入面）——**不再教写正文元数据头**：旧模板体（可复制
   * YAML front matter + 键白名单）正是正文元数据头的诱导源，整体作废。
   * 权威条文（与 `CONVENTIONS.md` 逐字同源）：① 正文零元数据头（`chain`/`chains`/
   * `chain-source`/`chain-role`/`produced-by`/`produced-at`/`doc-class`/`root` 一律
   * 不写进正文）；② 溯源只进文件名尾段——阶段文档
   * `<YYYYMMDD>_<HHMMSS>_<topic>__<chainId>.md`、多链 `__<主链Id>+<次链Id>.md`；
   * ③ 无归属不带尾段（不得编造），活文档（无日期前缀）同规则（有归属才带尾段）；
   * ④ 与既有 `-<n>` 同秒消歧并存（唯一性消歧 ≠ 已禁的版本副本 `-vN`）；⑤ 存量冻结
   * （零改名、零搬移、零回改）。
   * 段体 = 可复制的四种文件名形态 + 三条纪律，**零 `---` 行、零键白名单**（旧模板的
   * 诱导源就在这两处）；链 id 值取自紧随其上的链头行（`chainHeaderLine`）。
   * 旧「元数据头上限 = ≤8 键 / ≤10 行」（2026-09-10 作者裁定）随正文头停写退为**存量
   * 读取侧历史口径**（`index-backfill.py` 的 legacy 元数据头读取路径仍按该口径容错）。
   * 只在有链时注入（紧随链头）——无链会话无归属对象，注入只会诱导编造 chain id
   * （裁定理由同原交付报告）。
   */
  val DocProvenanceBlock: String =
    """[Process doc naming · provenance — body: NO metadata header; chain attribution only in the filename suffix `__<chainId>`:]
      |20260911_082530_<topic>__<chainId>.md = stage doc (<YYYYMMDD>_<HHMMSS>_<topic>__<chainId>) · <topic>__<chainId>.md = living doc (date-less; suffix only when attributed)
      |20260911_082530_<topic>.md = unattributed (no suffix — never invent one) · ...__<mainId>+<secondId>.md = multi-chain
      |Body never carries chain/chains/chain-source/chain-role/produced-by/produced-at/doc-class/root; chainId = the header line above; time = local second; same-second clash ⇒ -<n>; existing docs: no rename / move / retro-edit.""".stripMargin

  // ── LoopNode（LoopNode 批 2026-09-06，主设计 §2.2/§2.3）──────────────────

  /** loop 运行态阶段值（NodeDef.loopPhase；loopRound 配套）。 */
  val LoopPhaseWorker: String = "worker"
  val LoopPhaseVerify: String = "verify"

  // ── boot-time 崩溃恢复（crash-recovery 批 2026-09-07）──────────────────

  /**
   * 恢复认领事件类型（FlowMapEventLog append-only JSONL，「禁止静默自愈」纪律——
   * 每个认领/处置动作一条，summary 含三分类与 transcript 指针）。
   */
  val BootRecoveryEventType: String = "boot-recovery"

  /**
   * 优雅关机翻态事件类型（中断恢复语义批 2026-09-13，spec §2.3-2）：钩子把
   * Running 节点翻成 interrupted 时的唯一审计留痕点（与 draining 守卫的 WARN 留痕
   * 区分：本类型 = 翻态事实，守卫留痕 = 被拒的 failed 写）。
   */
  val InterruptedEventType: String = "interrupted"

  /**
   * 崩溃恢复续跑上下文（D3）：spawnAndRun/runWithAgent/spawnAndRunLoop 全链的
   * resume 增量——Some 时跳过 buildInput、复用旧会话 id（D2：transcript 单文件
   * 续写 + F2 队列重放白捡）+ initialMessages 水合，其余装配逐行复用（R8：禁手写
   * 独立 spawn 链）。普通节点仅主会话三字段；loop 节点（裁定③）另带 verify 双会话
   * 与崩溃时 loopRound/loopPhase 断点。
   */
  final case class ResumeContext(
    /** 复用的旧会话 id（普通节点唯一会话；loop = worker 主会话）。 */
    sessionId: String,
    /**
     * 主会话水合消息（磁盘 transcript，最后持久 tool round 边界——§1.1 诚实语义：
     * 工具执行中/LLM 调用中/2s debounce 窗内崩溃的轮次回退重放）。
     */
    recoveredMessages: List[Message],
    /** 首轮输入 = resume prompt（BackoffSupervisor continue-prompt 直系演化）。 */
    resumePrompt: String,
    /** loop verify 会话 id（仅 loop 节点；None = 普通/无旧 ref 新起）。 */
    verifySessionId: Option[String] = None,
    /** verify 会话水合消息（loop 节点）。 */
    verifyMessages: List[Message] = Nil,
    /** loop 崩溃时持久轮次断点（≥1；0/无值按 1 起）。 */
    loopResumeRound: Int = 0,
    /** loop 崩溃时相位（worker/verify；无值按 worker）。 */
    loopResumePhase: Option[String] = None
  )

  /**
   * 普通节点 resume prompt（§3.3 模板；磁盘上 (a)/(b) 不可区分——副作用核验告知
   * 恒带（裁定②诚实语义：mid-turn 重放=从上一持久轮重跑，已完成的工作无需重复，
   * 未确认的副作用先核验）。bgWaitNote = 认领时清空的等待集快照（G4 死亡告知）。
   */
  def nodeResumePrompt(projectName: String, node: NodeDef, recoveredCount: Int, bgWaitNote: Option[String]): String =
    val bgSection = bgWaitNote match
      case Some(w) =>
        s"\nThe background work awaited before the crash is dead ($w) — verify its result file on disk; restart it as needed.\n"
      case None => ""
    s"""[system] The process crashed during your last turn and restarted; history restored from disk (${recoveredCount} messages; breakpoint = last persisted tool round). Continue task "${node.name}".
       |The interrupted turn may have left side effects unfinished — verify them first (git state, key files); do not repeat completed work.$bgSection
       |Full task text: NodeList(detail="${node.id}", project="$projectName").""".stripMargin

  /**
   * loop worker 相位 resume prompt（裁定③：worker→verify 迭代跨崩溃续接——本轮
   * 产出从最后持久轮边界续作，verify 照常裁决本轮）。
   */
  def loopWorkerResumePrompt(node: NodeDef, round: Int, phase: String, recoveredCount: Int): String =
    s"""[system] The process crashed during LoopNode round $round ($phase) and restarted; history restored from disk (${recoveredCount} messages; breakpoint = last persisted tool round).
       |Continue this round from the breakpoint — verify side effects first (git state, key files); do not repeat completed work; then give this round's final output (the verify session rules on it).
       |Full task text: NodeList(detail="${node.id}").""".stripMargin

  /**
   * loop verify 相位续跑标注（verify 输入由 worker transcript 末条 assistant 文本
   * 重建——旧输入可能未持久/已消费，重注入是操作侧消息）。
   */
  def loopVerifyResumeAnnotation(round: Int): String =
    s"[system] Crashed during LoopNode round $round (verify phase) and restarted; below is this round's rebuilt input — give your verdict."

  // ── LoopNode 模板（续）────────────────────────────────────────

  /**
   * verify 文法脚注（主设计 §2.3 原文单点注入，模板三末尾）——与 ProtocolFootnote
   * 同机制，覆盖所有 verify 会话，防「verify 意图 FAIL 但忘写锚定行」被降级放行。
   */
  val VerifyVerdictFootnote: String =
    """── Verification protocol ──
      |First line of your final output, exactly: VERDICT: PASS or VERDICT: FAIL (upper case, half-width colon).
      |On FAIL follow it with JSON: {"issues":["issue 1","issue 2"],"requirements":"pass criteria"} — issues must name concrete fixes. On PASS write only that first line.""".stripMargin

  /**
   * verify 清单默认模板（loopSpec.verifyTask 为空时兜底注入）。
   * 语言：本批令 2 ①「全部提示词面英文」⇒ 由中文改为英文（设计 §3.1-B 把 B9 记为
   * 「保留原文」的例外，是针对**字节硬线**的计账例外；语言面以任务书 ① 为准）。
   */
  val VerifyDefaultTask: String =
    "Check the worker's output item by item against the original task and the acceptance baseline; " +
      "list every issue that must be fixed (one bullet each) and rule PASS when the bar is met."

  /**
   * **回边重跑准入判据单点**（B5 缺口③ 对偶腿 · 作者 2026-09-17 M-3 裁定：「运行期
   * 回边腿**同步排除 Running 目标**」——与编辑期守卫（`NodeTools`：控制边对 running
   * 目标入待接线队列）**两面口径一致，缺一半即洞**）。
   *
   * 判据（返回 `Left(原因)` = 本轮回边重跑**不派发**，`reloopTo` 走既有
   * `skipped=<原因>` 出口——`loop-round` 事件 + WARN 留痕，链不静默冻结：驱动方终态
   * 仍在、目标计时起点仍在，时间帽扫描 [[sweepLoopBudgets]] 兜底出显式终态）：
   *   - `running` ⇒ **排除**：目标有在飞会话，`resetForLoop` 会把它翻回 wiring/pending
   *     而会话仍在跑（节点状态与会话双轨不一致 = 对偶缺口的根因形态）。运行期「不在
   *     此刻接」与编辑期一致：等目标离开 running（到点由既有出口承接）。
   *   - `blocked` / `failed` / `cancelled` ⇒ 排除（分发器持有的现场，引擎不擅自推翻）。
   *   - 其余（`wiring` / `pending` / `completed` / `interrupted`）⇒ 放行（旧行为逐字不变：
   *     completed = 刚跑完被判，是回边重跑的主场景）。
   *
   * 公开供 spec 断言（判据单点，与 `LoopBudget.decide` 同族——纯函数、零副作用）。
   */
  def loopReworkAdmission(status: String): Either[String, Unit] =
    if status == NodeLifecycle.Running then
      Left(
        "running (input frozen) — the fail-route re-run is NOT dispatched while the target has an in-flight session: " +
          "resetting it would flip the node back to wiring/pending while its session keeps running " +
          "(state↔session dual-track inconsistency). Deferred to the target's next non-running boundary " +
          "(same judgement as the edit-time guard, which queues control edges in pendingOut) — " +
          "the wall-clock loop budget owns the terminal."
      )
    else if NodeLifecycle.Terminal.contains(status) && status != NodeLifecycle.Completed then
      Left(s"$status (blocked/failed/cancelled scenes are dispatcher-owned)")
    else Right(())

  /**
   * worker 返工模板二（第 N≥2 轮同会话注入；产出全文/历史不重复注入——持久会话
   * 上下文已持有，主设计 §2.2 模板二）。
   */
  def loopReworkInput(round: Int, issues: List[String], requirements: String): String =
    val reqLine =
      if requirements.trim.nonEmpty then s"== Pass criteria ==\n$requirements" else ""
    val iss =
      if issues.nonEmpty then issues.map(i => s"- $i").mkString("\n") else "- (the verifier gave no concrete issue)"
    s"""[LoopNode rework · round $round]
       |== Verifier issues ==
       |$iss
       |$reqLine
       |
       |${ProtocolFootnote}""".stripMargin

  /**
   * verify 输入模板三（第 N 轮；首轮全文、第 N≥2 轮新段——持久会话已持有原始任务/
   * 上游段/验证清单/历轮产出，主设计 §2.2 模板三 + 裁定 B 注记）。
   */
  def loopVerifyInput(
    round: Int,
    nodeTask: String,
    upstreamSection: String,
    workerOutput: String,
    verifyTask: String
  ): String =
    if round <= 1 then
      val up = if upstreamSection.nonEmpty then s"\n$upstreamSection" else ""
      s"""[LoopNode verification · round $round]
         |== Original task (acceptance baseline) ==
         |$nodeTask$up
         |== Output under review (worker round $round) ==
         |$workerOutput
         |== Verification checklist ==
         |${if verifyTask.trim.nonEmpty then verifyTask else VerifyDefaultTask}
         |
         |${VerifyVerdictFootnote}""".stripMargin
    else s"""[LoopNode verification · round $round]
         |== Output under review (worker round $round) ==
         |$workerOutput""".stripMargin
end NodeEngineContract
