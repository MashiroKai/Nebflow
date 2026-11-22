package nebflow.core.delegate

import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.PathUtil

/**
 * DelegateRegistry —— 统一 delegate 的持久地址簿（unified-delegate 批
 * 2026-10-03）。
 *
 * 大改后的使用形态：`Delegate(task, project?)` 是 Nebula 唯一的派发入口，
 * 执行器可以是内置 agent 或外部 CLI（executor-registry 批的 default）。每个
 * delegate 实例在这里落一条**持久记录**（`<dataRoot>/delegates/<id>.json`）：
 *
 *   - **id** = 续聊地址的 payload（`delegate:<id>`）——内部实例即会话 id
 *     （`delegate-<agent>-<8hex>`，与既有 Sub-Agents 面板前缀兼容）；
 *   - executor / project / cwd / taskId / parentSessionId / task 摘要——
 *     Mail 续聊、AgentControl 地址列、任务列表渲染的统一数据源；
 *   - 跨重启存活（JSON 落盘）：Mail 对一个终态实例的补充信息靠它找到
 *     实例的执行器与工作目录再续起（live 状态本身仍以内存 agentRegistry
 *     为准——重启后一律视为不 live）。
 *
 * 状态**不落本表**：live 与否查 `agentRegistry`（kill -9 后自动视为不 live），
 * 终态结果状态查 `subAgentTaskStore`（既有机制，per-parent 会话文件）。本表
 * 只做「地址 → 实例元数据」的持久映射，绝无第二份可漂移的状态机。
 *
 * 读写纪律：读 = 每次现读盘（热读，仿 JevConfigReader）；写 = 同步整写
 * （AtomicJson.writeSync）。记录量级 = 每 delegate 一个小 JSON 文件，无索引
 * 需求（list 全扫目录）。
 */
object DelegateRegistry:

  // ── 状态槽值域常量（终态申报工具通道退役 · T2 批：原定义随工具件删除，
  //    引擎内部承接写点（updateReport / SubAgentStartupRecovery 落「已交付」态）
  //    的值域 ⇒ 迁本伴生对象单点持有）。

  /** 状态槽值：任务完成（引擎内部承接写点使用；模型可见面零申报工具指令）。 */
  val FinishValue: String = "finish"

  /** 状态槽值：入队等合并（历史盘面兼容值）。 */
  val MergeValue: String = "merge"

  /**
   * 状态槽值：显式待命（案A 批 2026-10-31 · 作者 11:40 拍板「显式待命态为主干」；
   * 状态矩阵批 2026-11-01 收编为矩阵组合 complete×standby）：回合收尾挂起待命——
   * 不终判、不投父、任务行保持 running、会话停靠 Waiting 显「待命」，Mail 到达唤醒续跑。
   */
  val StandbyValue: String = "standby"

  /**
   * 一条持久 delegate 记录。
   *
   * worktree-judge-fifo 批（#1224 增补 §3.4）**扩面**：末四字段 = 终态申报的
   * **状态槽**（仅状态单写，不携汇报——汇报仍走终态消息直传；申报工具通道退役后
   * 本槽由引擎内部写点持有）。旧记录无此四键
   * ⇒ 解码按缺失 = None 容忍（手工 Decoder 单点，见文件尾——deriveDecoder 不吃
   * 缺键，会让存量记录在 list 里静默消失，禁）。
   */
  final case class Record(
    id: String,
    executor: String,
    /** 派发时解析的工作目录（项目 workspace 或默认 general 目录；worktree 腿 = worktree 路径）。 */
    cwd: String,
    /** 挂载项目名（缺省派发 = None → cwd 为默认 general 目录）。 */
    project: Option[String],
    /** 关联的 TaskLedger 条目 id（"12" 形态，无 #）。 */
    taskId: Option[String],
    /** 派发者（Nebula 根）会话 id——结果回投与 Mail 排队路由锚点。 */
    parentSessionId: String,
    /** 任务摘要（列表显示用；全文在 TaskLedger）。 */
    title: String,
    createdAt: Long,
    /**
     * 批次短名（后台任务面板层级/命名/版式批 2026-10-31 · 作者令）：Delegate 工具
     * `name` 参数（≤12 字）——面板 delegate 组头行首的扫读名（「扫一眼就知道哪批是
     * 哪批」）；空/缺省 = 旧数据 ⇒ 前端回退既有截断式标题显示（零迁移）。
     * 缺省 None：旧盘面照常解码（cliSessionId 同款纪律）。
     */
    name: Option[String] = None,
    /** 工作分支（S13 分支纪律：恒 `delegate/<id>`；非 git 工作区 = None）。 */
    branch: Option[String] = None,
    /** worktree 目录（R6 判是腿；判否腿 = None——分支建在项目内）。 */
    worktree: Option[String] = None,
    /** spawn 时基分支（合并轮的 `merge --no-ff` 目标；非 git 工作区 = None）。 */
    baseBranch: Option[String] = None,
    /** 基工作区（合并执行的 `git -C` 目标；worktree 腿 = 项目 workspace，就地腿 = cwd）。 */
    projectWorkspace: Option[String] = None,
    /** 终态申报状态槽：finish | blocked（未申报 = None）。 */
    reportStatus: Option[String] = None,
    /** 申报 detail（blocked 的原因承载）。 */
    reportDetail: Option[String] = None,
    /** 申报 suggestion（可选）。 */
    reportSuggestion: Option[String] = None,
    /**
     * 工作结果文本槽（delegate-finish-report 修复批 2026-10-25）：ready-to-merge
     * 停靠（HoldForMerge）时保真的**工作轮终稿**（申报 merge 轮的终文
     * ——合并轮 finish 终文会把它顶掉，本槽 = 工作结果不随两段汇报丢失的承载面）。
     * first-write-wins（首个非空文本落槽后不再改写）；`updateReport` 覆写旧
     * detail 时此处留底（老 delegate 无停靠保真 → merge 申报 detail 回落补种）。
     * 缺省 None：旧盘面照常解码（cliSessionId 同款纪律）。
     */
    workReport: Option[String] = None,
    /**
     * 外部执行器的 CLI 侧会话 id（external-executors 批 #1225）——resume 续聊
     * 锚点（claude session_id / codex thread_id / zcode session_id），从适配流
     * 首帧取得后回写。None = 内部执行器或外部首帧尚未到达（续聊退化形态见
     * ExternalSessionRunner.startResumeTurn：带前文的续聊 brief，仍在同地址
     * 同执行器内，零回落）。缺省 None：旧盘面文件无此字段照常解码（向后兼容）。
     */
    cliSessionId: Option[String] = None,
    /**
     * 分配插件集（jev-plugins 批 #1226 · S9 可查面）：本 delegate spawn 时分配面
     * 授予的包名集（task 行结构帧的 plugins 摘要供源；渲染归 #1228）。**分配动作
     * 发生即记**（`Some(Nil)` = 显式空分配）；`None` = 分配未发生（plugins 总闸关 /
     * 外部执行器腿——外部执行器零注入，恒 None）。缺省 None：旧盘面文件无此字段
     * 照常解码（向后兼容，cliSessionId 同款纪律）。
     */
    plugins: Option[List[String]] = None,
    /**
     * 编排模式（delegate-ui 批 2026-10-08 · G1）：本 delegate 实际呈现的编排形态
     * ——`single`（纯本体，缺省初值）/ `subagent-star`（星型：已在 SubAgentTaskStore
     * 域记账过子代理——Subagent/Subtask/嵌套 Delegate 任一 spawn 记账点惰性升级）/
     * `agentflow`（flow 结构：**链登记成功**惰性升级，值域顶——升级只上不下，
     * 两形态混用时 flow 胜出）。**写点两处**：spawn 初值（[[nebflow.core.tools.DelegateTool]]
     * 内置腿 + [[nebflow.core.executor.ExternalSessionRunner]] 外部腿——外部执行器
     * 零平台工具，恒 single）＋ 惰性升级（记账点单写 [[upgradeOrchestrationMode]]）。
     * 🔴 tool-face-agentflow-retire 批（2026-11-05，作者裁定二）：`agentflow` 的升级
     * 调用点原先长在已删的 AgentFlow 工具里，现**唯一**在
     * `ChainWriteTool.upgradeOrchestrationModeOnRegister`（链登记成功回执路径）。
     * 注意它只是**模式名**：同名工具已退役，两者不要再混用。
     * 缺省 None：旧盘面记录 = 前端过渡启发（面板 kind 前缀判）兜底——**禁在读面把
     * None 折叠成 single**（会剥夺存量行的启发兜底）。
     */
    orchestrationMode: Option[String] = None,
    /**
     * 判定机的「会否改文件」裁决（引擎加固批 2026-10-09）：spawn 时判定机产物
     * 随记录持久化——终态只读座位诚实化（P2-3）的判据面（`Some(false)` = 只读
     * 座位）。`None` = 无绑定（非 git / 建败降级）/ 旧盘面记录（缺键照常解码，
     * 向后兼容同 cliSessionId 纪律）。
     */
    worktreeJudged: Option[Boolean] = None,
    /**
     * **存量归档旗**（delegate-ui 批 2026-10-08 · G6，epoch millis）——delegate-lifecycle-reform
     * 阶段② 起**只读兼容面**：可见性改由状态派生（[[DelegateVisibility]] 的收口证据 +
     * [[disposition]] 非破坏性旗），本槽**零新写入**（归档/反归档写点与自动归档已整体退役）。
     *
     * 读面语义（**为什么保留**）：863 条在盘旧记录的既有「已归档」读数原样可读——
     * [[DelegateVisibility.Reading.legacyArchived]] = 本槽在场，读面等价折叠成历史窗
     * （面板观感零变化）；引擎行为腿（崩溃恢复/复活/唤醒/TTL）**不再吃本槽**（改吃状态
     * 派生读数）。零删除纪律不破：记录/子代理记账/链记录原样留存。缺省 None：旧盘面照常解码。
     */
    archivedAt: Option[Long] = None,
    /**
     * origin 标记（v2.1 阶段 1 §A.3，2026-10-09 实施批）：**执行设备侧**记录本会话
     * 是「受委托」形态——值 = 派活 host 的 deviceId（`delegated-by:<hostDeviceId>`
     * 的载体，验收 #2 的 `sessions/` 标记读数）。编排侧（host）派发记录 = None。
     * 缺省 None：旧盘面照常解码（cliSessionId 同款纪律）。
     */
    delegatedBy: Option[String] = None,
    /**
     * 重启打断标记（任务列表行重启对账批 2026-10-22）：boot 对账（[[nebflow.agent.SubAgentStartupRecovery]]
     * 的孤儿收殓）判定本会话在进程重启时仍在飞（任务记录 running/restarting，或带
     * 重启指纹的终态任务记录回溯腿）即落旗 = 打断时刻 epoch millis。**幂等零重写**
     * （已旗 = 原值返回，与 [[archive]] 同款）。None = 未标记（非重启打断）。显示面
     * 语义（「重启打断」徽标 /「已中断」灰态 / 接续折叠）由读面投影与前端适配层
     * 承载——真完成（报告交付/判词终态）照常覆盖显示态，旗不截断复活续聊的真完成。
     * 缺省 None：旧盘面照常解码（delegatedBy 同款纪律）。
     */
    restartInterruptedAt: Option[Long] = None,
    /**
     * 取消墓碑（R1 缝隙 C 修，2026-10-28 实施批 · 报告二 §1.3）：**取消意图的持久
     * 痕迹**——cancel 入口（[[nebflow.core.tools.AgentControlTool]] doCancel 双路径）
     * 先行单写（幂等零重写：已旗 = 原值返回）。消费点 = [[nebflow.agent.DelegateRevival]]
     * `.eligibility` 复活资格闸（七闸加一道）：墓碑在场 ⇒ 永不复活——「已取消 ×
     * 被复活」僵尸（实证 e25cd0c2 revival#1 / b5646a58 revival#2）的机制封口；
     * 取消意图从此不再全押任务行一行。缺省 None：旧盘面照常解码（零迁移）。
     */
    cancelRequestedAt: Option[Long] = None,
    /** 取消发起方（"the user panel" / 工具调用者标签；观测面，判据不用）。 */
    cancelRequestedBy: Option[String] = None,
    /**
     * 双模式批（2026-10-28 · 设计 §3.2/§7.3）：本 delegate 的**执行形态**——
     * `kernel`（极简内核执行器）| `dispatcher`（分发器模式）。spawn 时一次性判定
     * （选择器/人工覆盖），**终身属性**（运行中不改模式——改模式 = 重派）。读面
     * 缺省解码见 [[modeFaceOf]]：`None` = 未判定（总闸关时代的存量记录/外部腿），
     * 消费方按 kernel 折叠（存量零迁移）。缺省 None：旧盘面照常解码。
     */
    mode: Option[String] = None,
    /**
     * 待命停靠时刻（案A 批 2026-10-31 · 作者 11:40 拍板显式待命态为主干；审计基线
     * `docs/20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939.md`；
     * 状态矩阵批 2026-11-01 收编为矩阵组合 complete×standby）：
     * 显式待命申报 `standby` 且回合收尾被判定机采纳 ⇒ 停靠写时刻（epoch
     * millis）——任务行保持 running、会话置 Waiting、不终判不投父；面板/快照面
     * 据此透出「待命（等作者）」。**写点 = 回合收尾采纳点**（非申报点——同回合
     * 后续 finish/merge 覆写申报时本槽不脏）；消费三态：Some = 在待命、None =
     * 非待命。**申报槽（reportStatus=standby）与停靠槽（standbySince）分立**：
     * 申报是回合内意图、停靠是回合收尾裁决——唤醒（Mail 注入，MailTool
     * deliverToDelegate live 腿）清两槽，唤醒轮新申报照常走矩阵（finish ⇒ 终判、
     * standby ⇒ 再停靠、未申报 ⇒ 提醒）。缺省 None：旧盘面照常解码（cliSessionId
     * 同款纪律）。
     */
    standbySince: Option[Long] = None,
    /**
     * 冻结时刻（错误冻结批 2026-11-01 · 作者令「所有因错误状态的 delegate 应该上报
     * 之后要能保留状态 冻结态 方便被唤醒 接着做」）：delegate 因**错误**结束（LLM 层
     * 故障 / 工具层不可恢复 / 引擎侧异常 / 外部执行器崩溃 / 配额取消）⇒ 不再走 failed
     * 终止，而是**原地冻结**——会话上下文（transcript）、工作区/worktree 与未提交
     * 改动、任务台账条目、ahead 提交指针、失败原因与最后错误上下文全部保留在盘上，
     * 重启后仍在；`frozenSince = 冻结时刻`（epoch millis）。
     *
     * 与 [[standbySince]] 的分工（两者互斥语义，不共用槽）：standby = **自愿待命**
     * （显式待命申报，回合正常收尾，等作者给活）；frozen = **异常冻结**（回合
     * 异常中断，等唤醒续做）。写点 = 监督者冻结腿（[[nebflow.core.delegate
     * .DelegateFreeze.park]]），清点 = 唤醒（Mail live 注入 / 面板唤醒 / boot 原地复活）
     * 与真正终态（[[nebflow.core.delegate.DelegateTerminal.onTerminalCleanup]]）。
     * 缺省 None：旧盘面照常解码（cliSessionId 同款纪律）。
     */
    frozenSince: Option[Long] = None,
    /**
     * 冻结原因词表值（错误冻结批 2026-11-01）：`llm-failure` | `tool-failure` |
     * `engine-fault` | `cancelled-by-quota`（[[DelegateFreeze.Reason.wire]]）。面板
     * 详情卡与唤醒回执的「为什么」数据源；None = 非冻结。
     */
    frozenReason: Option[String] = None,
    /**
     * 冻结上下文摘要（错误冻结批 2026-11-01）：最后一次错误的可读全文（含
     * provider/model 线索）+ 保留面清单摘要——「谁冻了、为什么、保留了什么、
     * 怎么唤醒」四项回执里「为什么/保留了什么」的持久承载面（回执只是一条消息，
     * 本槽是它的盘上真源）。None = 非冻结。
     */
    frozenDetail: Option[String] = None,
    /**
     * 冻结**触发源**线值（engine-fault 冻结报错补详批 · 第二波）：哪条腿/哪个事件/
     * 哪个看门狗触发本次冻结——`failed-event`（回合级 AgentEvent.Failed）|
     * `cancelled-by-quota`（配额类 Cancelled）| `managed-workspace-stopped`
     * （Terminated 时仍持有受管 workspace/merger 槽）| `supervision-exhausted`
     * （Terminated 且重启预算耗尽）| `respawn-failed`（respawn 链中途失败）。
     * 与 [[frozenDetail]] 行首的 `trigger=` 诊断前缀同源同值（文本面 + 结构化槽
     * 双承载——面板详情卡与审计面读槽、人读详情行）。None = 非冻结 / 旧盘面记录
     * （缺省 None：旧记录照常解码，零迁移）。
     */
    frozenTrigger: Option[String] = None,
    /**
     * **续命血缘**（会话续命名与位修正批 2026-11-03 · 作者 16:48/16:51 报障）：
     * 本会话是**派生**出来的时（原位续不可恢复：cancel 墓碑 / 转录损坏），值 =
     * 被继承的原 delegate 会话 id。原位续（同 sessionId）恒 None——同一身份不需要
     * 血缘。审计面：AgentControl 列表行尾标注、ChainStore `delegate-resume-derived`
     * 行、面板会话详情。缺省 None：旧盘面照常解码（cliSessionId 同款纪律）。
     */
    resumedFrom: Option[String] = None,
    /**
     * 就地腿的派发时快照：spawn 时工作区 HEAD（delegate-param-decisions 批
     * 2026-11-04 · 作者补充令第 2 条机械护栏）。**只在 `worktree` 参数缺省
     * （零分配 ⇒ 就地跑道）时落值**——收尾时 [[DelegateTerminal.inPlaceWriteAlarm]]
     * 拿它比对「本任务没分配 worktree 却动了项目工作区」，命中即回执标红。
     * `None` = 有树 / 非 git / 旧盘面记录（照常解码）。
     */
    seatSnapshotSha: Option[String] = None,
    /** 就地腿的派发时快照：spawn 时 `git status --porcelain` 的路径集（同上）。 */
    seatSnapshotDirty: Option[List[String]] = None,
    /**
     * 当前执行轮次号（轮次模型）：spawn = round 1；已结算后 Mail 续唤开新轮
     * （roundNo+1）；冻结唤醒 / boot 复活 = 同轮续跑（不变）。明细（起跑、基线、
     * 结算、判词、已计提交）在 `delegates/<id>.rounds.jsonl` append-only 台账
     * （[[DelegateRoundLedger]]），Record 只存**当前轮指针**。缺省 None：旧盘面
     * 记录照常解码（读面按 round 1 隐式处理，零迁移）。
     */
    roundNo: Option[Int] = None,
    /**
     * 当前轮次主键（UUID，轮次开跑时铸造；与 CompletionDebtStore 的 generation 同值
     * ——终态邮箱幂等键 `<id>#<roundId>` 即既有代际机制）。缺省 None / ""：旧盘面
     * 照常解码（无轮次形态，行为与升级前兼容）。
     */
    roundId: Option[String] = None,
    externalModel: Option[String] = None,
    /**
     * **非破坏性确认/忽略旗**（delegate-lifecycle-reform 阶段② · 作者令）：值域 =
     * [[DispositionAcknowledged]]（"acknowledged" = 我看到了/收下了）/ [[DispositionIgnored]]
     * （"ignored" = 我不想再看到）；空 = 未表态。这是「归档」退场后的**用户表态**承载面：
     *  - **零删除**：只写本槽与 [[dispositionAt]]，记录/子代理记账/转录/worktree/历史行
     *    原样留存，可随时清除（[[clearDisposition]]）⇒ 行回活动窗；
     *  - **可见性**（[[DelegateVisibility.Reading.historyWindow]]）：收口 ∧ 本旗在场 ⇒
     *    历史窗；**收口前表态无效**（未收口的身份照常在活动窗，绝不因表态隐藏未完工作）；
     *  - 缺省 None：旧盘面照常解码（cliSessionId 同款纪律）。
     */
    disposition: Option[String] = None,
    /** 表态时刻（epoch millis；与 [[disposition]] 同写点单写，清旗时一并清空）。 */
    dispositionAt: Option[Long] = None,
    /**
     * **置顶标记**（delegate-lifecycle-reform 阶段③ · 作者令「七天物理清理 + 重要记录置顶」）：
     * 时刻（epoch millis）在场 = 本条身份**豁免 7 天物理清理**——终态 TTL 判读
     * （[[DelegateTerminalTtl.verdict]]）对它恒返 [[DelegateTerminalTtl.Pinned]]，
     * 清理链（[[nebflow.agent.DelegateTtlSweep]]）零动作。**非破坏性、可撤销**：
     * 摘标记（[[unpin]]）即回到正常 TTL 计时（记录/台账/历史原样留存，零删除）。
     * 缺省 None：旧盘面照常解码（cliSessionId 同款纪律）。
     */
    pinnedAt: Option[Long] = None
  )

  private def dir: os.Path = PathUtil.dataRoot / "delegates"

  private def fileOf(id: String): os.Path = dir / s"$id.json"

  /** id 安全校验：只允许落盘文件名白名单字符（防路径穿越）。 */
  def validId(id: String): Boolean =
    id.nonEmpty && id.length <= 128 && id.matches("^[a-zA-Z0-9._-]+$")

  /**
   * 批次短名上界（面板命名批 2026-10-31 · 作者令「≤12 字」）——schema maxLength
   *  与引擎钳位共用此常量。
   */
  val ShortNameMaxChars: Int = 12

  def put(record: Record): Unit =
    os.makeDir.all(dir)
    AtomicJson.writeSync(fileOf(record.id), record.asJson.noSpaces)
    // 阶段③ 写路径钩子（单缝追加，零新增写面；失败软化——索引落后由读面对账收敛）
    DelegateIndex.onRecordWritten(record.id)

  /** 持久查找（每次现读；坏形/缺失 → None，绝不抛）。 */
  def find(id: String): Option[Record] =
    if !validId(id) then None
    else
      try
        val p = fileOf(id)
        if !os.exists(p) then None
        else io.circe.parser.parse(os.read(p)).toOption.flatMap(_.as[Record].toOption)
      catch case _: Throwable => None

  /**
   * 移除一条登记条目（终态 TTL 到期后的**物理清理**动作面）。
   *
   * 阶段③（delegate-lifecycle-reform）**扩物理面**：本函数现在一并清走该身份自己的
   * 两份源文件与索引行——`<id>.json`（身份）+ `<id>.rounds.jsonl`（轮次台账 append-only
   * 史）+ 索引两区行。理由 = 作者令的「七天物理清理」：TTL 到期的身份若把台账留在盘上，
   * 「清理」只清一半（台账成为无主孤儿，正是 [[DelegateIndex.validateDedup]] 要报告的
   * 形态）；已在册身份仍走 TTL 判读（[[DelegateTerminalTtl.Pinned]] 置顶者**永不**到此），
   * 历史事实由 ChainStore 行与子代理记账承载（brief「语义 = 清理」不变）。
   * 任务行剪行仍由调用方（[[nebflow.agent.DelegateTtlSweep]]）负责。
   */
  def remove(id: String): Either[String, Unit] =
    if !validId(id) then Left(s"delegate registry: invalid session id '$id'")
    else
      writeLock.synchronized {
        try
          val file = fileOf(id)
          if os.exists(file) then os.remove(file)
          val ledger = DelegateIndex.ledgerFileOf(PathUtil.dataRoot, id)
          if os.exists(ledger) then os.remove(ledger)
          DelegateIndex.onRecordRemoved(id)
          Right(())
        catch case e: Throwable => Left(s"delegate registry remove failed for '$id': ${e.getMessage}")
      }

  /**
   * 全量列出（按 createdAt 倒序，id 为确定性次序键；坏文件跳过）。
   *
   * 阶段③（delegate-lifecycle-reform）：**启动加载面改吃索引**——索引在场且源码在场 ⇒
   * [[DelegateIndex.listRecords]] 给出全量记录（水位 `(mtime,size)` 失效策略，零源文件
   * 重解析）；关闸 / 索引禁用 / 源码目录缺席 / 任何异常 ⇒ [[DelegateIndex.scanRecords]]
   * 目录扫描（与阶段③ 前逐行同源）。**正确性不依赖索引新鲜度**：索引只在源码在场时服务，
   * 回退路径就是阶段③ 前的基线面（最坏 = 慢，不退化为错）。
   */
  def list(): List[Record] =
    DelegateIndex.listRecords().getOrElse(DelegateIndex.scanRecords(PathUtil.dataRoot))

  /** 续聊地址行——Delegate 回执与 Mail 地址面提示共用一份措辞。 */
  def continuationLine(id: String): String =
    s"\nContinuation address: \"delegate:$id\" — later communication about THIS instance (supplements, corrections) goes through Mail(address=\"delegate:$id\"). Its result is delivered back to your session when it finishes."

  /**
   * 终态申报状态槽单写（#1224 增补 §3.4）：读-改-写（同步整写），只动
   * report* 四键，其余字段原样保真。查无记录 = `Left`（不凭空造记录——申报的
   * 身份必须来自真实 spawn）。申报工具通道退役（T2 批）后本写点 = **引擎内部
   * 承接写**（恢复腿落「已交付」态等），不再是模型可见通道。
   *
   * delegate-finish-report 修复批 2026-10-25 **preserve-before-overwrite**：本槽
   * 是单槽（新申报覆写旧申报）——merge 轮的详细工作汇报会被 finish 轮的「合并
   * 完成」短报覆掉（作者令缺陷：工作结果丢失）。覆写发生且旧 detail 非空、与新
   * detail 不同、workReport 槽尚空 ⇒ 旧 detail **留底**进 `workReport`（首写优先
   * ——停靠保真已落槽的工作轮终稿优先级更高，绝不被申报留底顶掉）。
   */
  def updateReport(id: String, status: String, detail: String, suggestion: String): Either[String, Unit] =
    if !validId(id) then Left(s"delegate registry: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None =>
            Left(s"delegate registry: no record for '$id' — the write point only lands on a real Delegate spawn")
          case Some(rec) =>
            try
              val preservedWorkReport = rec.workReport.orElse {
                val old = rec.reportDetail.getOrElse("")
                if old.trim.nonEmpty && old != detail then Some(old) else None
              }
              // 案A 批 2026-10-31：新申报非 standby ⇒ 清待命停靠槽（新申报覆旧申报，
              // standby 是旧轮的意图——唤醒/重申报后轮收尾不再误认待命）。standby
              // 申报本身不动槽（停靠时刻由回合收尾采纳点单写，见 [[parkStandby]]）。
              val standbyCleared =
                if status != StandbyValue then None
                else rec.standbySince
              // 待命期边界（应答消费批 2026-11-03）：非 standby 申报把停靠槽清掉
              // ⇒ 旧待命期结束，看护记账清零（下一轮待命 = 新期，退避序从头发）。
              if rec.standbySince.isDefined && standbyCleared.isEmpty then DelegateStandbyCare.resetPeriod(id)
              put(
                rec.copy(
                  reportStatus = Some(status),
                  reportDetail = Some(detail),
                  reportSuggestion = if suggestion.nonEmpty then Some(suggestion) else None,
                  workReport = preservedWorkReport,
                  standbySince = standbyCleared
                )
              )
              Right(())
            catch case e: Throwable => Left(s"delegate registry: report write failed: ${e.getMessage}")
      }

  /**
   * 工作结果槽单写（delegate-finish-report 修复批 2026-10-25）：ready-to-merge
   * 停靠点调用——把工作轮终稿保真进 `workReport`，合并轮 finish 终文不再顶掉它。
   * **first-write-wins**：槽内已有非空文本 = 零写（幂等，工作轮终稿权威）；空文本
   * = 零写（无可保真）。查无记录 = `Left`（与 updateReport 同纪律：只对真实
   * spawn 生效）。
   */
  /** Root workspace reconciliation's structured finish update; caller supplies only a durable confirmation receipt. */
  def confirmWorkspaceCompletion(id: String, operationId: String, confirmation: String): Either[String, Unit] =
    if !validId(id) then Left(s"workspace reconcile: invalid delegate id '$id'")
    else if operationId.trim.isEmpty || confirmation.trim.isEmpty then
      Left("workspace reconcile: missing operation receipt")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"workspace reconcile: no registry record for '$id'")
          case Some(rec) =>
            decideCompletionWrite(rec, confirmation) match
              case Left(error) => Left(error)
              case Right(updated) =>
                try
                  put(updated)
                  Right(())
                catch case e: Throwable => Left(s"workspace reconcile registry write failed: ${e.getMessage}")
      }

  /**
   * Pure decision for the reconciliation finish write — deterministic and disk-free so
   * replays can never clobber a NEWER claim: an old receipt is idempotent only against
   * itself, allowed onto an unreported slot, and refused against any later finish /
   * standby / blocked report. `workReport` is never touched here.
   */
  private[delegate] def decideCompletionWrite(rec: Record, confirmation: String): Either[String, Record] =
    if rec.reportStatus.contains("finish") && rec.reportDetail.contains(confirmation) then
      Right(rec) // idempotent replay
    else if rec.cancelRequestedAt.isDefined then Left("delegate has a cancel tombstone; completion receipt refused")
    else if rec.reportStatus.isEmpty then
      Right(rec.copy(reportStatus = Some("finish"), reportDetail = Some(confirmation), reportSuggestion = None))
    else Left("registry report slot carries a newer claim; stale reconcile receipt refused")

  def updateWorkResult(id: String, text: String): Either[String, Unit] =
    if !validId(id) then Left(s"updateWorkResult: invalid session id '$id'")
    else if text.trim.isEmpty then Right(())
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"updateWorkResult: no registry record for '$id'")
          case Some(rec) =>
            if rec.workReport.exists(_.trim.nonEmpty) then Right(())
            else
              try
                put(rec.copy(workReport = Some(text)))
                Right(())
              catch case e: Throwable => Left(s"updateWorkResult: write failed: ${e.getMessage}")
      }

  /** 进程内读-改-写串行化（updateReport 临界区；与 ChainStore.writeLock 各管各件）。 */
  private val writeLock = new Object

  /**
   * 待命停靠单写（案A 批 2026-10-31）：`standbySince = now`——回合收尾采纳 standby
   * 申报时由 [[nebflow.core.delegate.DelegateTerminal]] 调用。查无记录 = `Left`
   * （只对真实 spawn 生效，updateReport 同纪律）。写锁同缝串行；每次停靠刷新时刻
   * （新一轮待命 = 新时刻，caretaker 退避序从头发）。
   */
  def parkStandby(id: String): Either[String, Long] =
    if !validId(id) then Left(s"parkStandby: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"parkStandby: no registry record for '$id'")
          case Some(rec) =>
            val ts = System.currentTimeMillis()
            try
              put(rec.copy(standbySince = Some(ts)))
              Right(ts)
            catch case e: Throwable => Left(s"parkStandby: write failed: ${e.getMessage}")
      }

  /**
   * 待命唤醒单写（案A 批 2026-10-31）：Mail 注入待命会话（[[nebflow.core.tools
   * .MailTool]] deliverToDelegate live 腿）时调用——`standbySince = None` 且
   * report* 三槽一并清空（申报已被该回合消费；唤醒轮若无新申报，矩阵按未申报
   * 分流——提醒补报，绝不静默判完成）。**workReport 槽不动**（工作结果保真面
   * 与待命语义正交）。查无记录 = `Left`。写锁同缝串行。
   */
  def clearStandby(id: String): Either[String, Unit] =
    if !validId(id) then Left(s"clearStandby: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"clearStandby: no registry record for '$id'")
          case Some(rec) =>
            if rec.standbySince.isEmpty && rec.reportStatus.isEmpty then Right(()) // 幂等：非待命零写
            else
              try
                // 待命期边界（应答消费批 2026-11-03）：停靠槽被清 = 该会话已被唤醒
                // /已离开待命 ⇒ 看护记账清零（下轮停靠是新期待命，退避序从头发）。
                DelegateStandbyCare.resetPeriod(id)
                put(
                  rec.copy(
                    standbySince = None,
                    reportStatus = None,
                    reportDetail = None,
                    reportSuggestion = None
                  )
                )
                Right(())
              catch case e: Throwable => Left(s"clearStandby: write failed: ${e.getMessage}")
      }

  // ── 错误冻结槽（错误冻结批 2026-11-01）────────────────────────────────────

  /**
   * 冻结单写（错误冻结批 2026-11-01）：`frozenSince = now` + 原因词表值 + 详情
   * （+ 触发源槽，第二波补详）。「冻结即停」——本槽只记事实，不做任何处置；不知原因
   * 的重复冻结（同一波故障反复冻结）刷新时刻与详情，但**保留首个原因与首个触发源**
   * （`frozenReason` / `frozenTrigger` 已存在时零改——根因比症状重要）。
   * 查无记录 = `Left`（只对真实 spawn 生效，parkStandby 同纪律）。
   * 写锁同缝串行。
   */
  def parkFrozen(id: String, reason: String, detail: String, trigger: String = ""): Either[String, Long] =
    if !validId(id) then Left(s"parkFrozen: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"parkFrozen: no registry record for '$id'")
          case Some(rec) =>
            val ts = System.currentTimeMillis()
            val trig = Option(trigger).map(_.trim).filter(_.nonEmpty)
            try
              put(
                rec.copy(
                  frozenSince = Some(ts),
                  frozenReason = rec.frozenReason.orElse(Some(reason)),
                  frozenDetail = if detail.trim.nonEmpty then Some(detail) else rec.frozenDetail,
                  frozenTrigger = rec.frozenTrigger.orElse(trig)
                )
              )
              Right(ts)
            catch case e: Throwable => Left(s"parkFrozen: write failed: ${e.getMessage}")
      }

  /**
   * 冻结槽唤醒单写（错误冻结批 2026-11-01）：Mail 唤醒 / 面板唤醒 / boot 原地复活
   * 三腿调用——四槽一并清空（`frozenSince` / `frozenReason` / `frozenDetail` /
   * `frozenTrigger`）。
   * **不动** standby 槽与 workReport 槽（正交面）。幂等：非冻结 = `Right` 零写。
   * 查无记录 = `Left`。
   */
  def clearFrozen(id: String): Either[String, Unit] =
    if !validId(id) then Left(s"clearFrozen: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"clearFrozen: no registry record for '$id'")
          case Some(rec) =>
            if rec.frozenSince.isEmpty && rec.frozenReason.isEmpty && rec.frozenDetail.isEmpty && rec.frozenTrigger.isEmpty
            then Right(())
            else
              try
                put(rec.copy(frozenSince = None, frozenReason = None, frozenDetail = None, frozenTrigger = None))
                Right(())
              catch case e: Throwable => Left(s"clearFrozen: write failed: ${e.getMessage}")
      }

  /** 冻结态读数（纯查盘；面板快照 / Mail 腿 / 复活资格共用单点判据）。 */
  def frozenOf(id: String): Option[(Long, String, String)] =
    find(id).flatMap(r => r.frozenSince.map(ts => (ts, r.frozenReason.getOrElse(""), r.frozenDetail.getOrElse(""))))

  /** 是否处于冻结态（`frozenSince` 在场）。 */
  def isFrozen(id: String): Boolean = find(id).exists(_.frozenSince.isDefined)

  // ── 编排模式值域（G1，delegate-ui 批 2026-10-08）────────────────────────

  /** 纯本体（spawn 初值；外部执行器恒此值）。 */
  val ModeSingle = "single"

  /** 星型：SubAgentTaskStore 域记过账（Subagent/Subtask/嵌套 Delegate 子代理）。 */
  val ModeSubagentStar = "subagent-star"

  /**
   * flow 结构：**链登记成功**（`ChainWriteTool.registerChain` 的回执路径）。值域顶
   * （与星型混用时胜出）。tool-face-agentflow-retire 批（2026-11-05）：本章名**不是**
   * 工具名——同名工具已整体退役，读它就是读「这个 delegate 按节点编排在干活」。
   */
  val ModeAgentflow = "agentflow"

  /** 升级序（只上不下）：single(0) < subagent-star(1) < agentflow(2)。未知值 = 0（保守底）。 */
  private def modeRank(m: String): Int =
    if m == ModeAgentflow then 2 else if m == ModeSubagentStar then 1 else 0

  /**
   * 编排模式惰性升级单写（G1）：读-改-写（同步整写），**只上不下**——目标秩不高于
   * 现值 = 零写（幂等）。查无记录 = `Left`（调用方 fail-soft 吞掉——升级挂点是
   * 「顺手记账」语义，绝不阻断主路径；无记录 = 非 delegate 会话/旧盘面，本就该跳过）。
   */
  def upgradeOrchestrationMode(id: String, mode: String): Either[String, Unit] =
    if !validId(id) then Left(s"orchestrationMode upgrade: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"orchestrationMode upgrade: no registry record for '$id'")
          case Some(rec) =>
            val cur = rec.orchestrationMode.getOrElse(ModeSingle)
            if modeRank(mode) <= modeRank(cur) then Right(())
            else
              try
                put(rec.copy(orchestrationMode = Some(mode)))
                Right(())
              catch case e: Throwable => Left(s"orchestrationMode upgrade: write failed: ${e.getMessage}")
      }

  // ── 非破坏性确认/忽略旗（阶段②：归档写点的替代面）────────────────────────

  /** 表态值：确认（"acknowledged"）。 */
  val DispositionAcknowledged: String = "acknowledged"

  /** 表态值：忽略（"ignored"）。 */
  val DispositionIgnored: String = "ignored"

  /** 表态值域（写面白名单；未列表值 = Left，绝不落自由文本）。 */
  val DispositionWords: Set[String] = Set(DispositionAcknowledged, DispositionIgnored)

  /** 值域校验（HTTP 面/工具面共用单点）。 */
  def isDispositionWord(word: String): Boolean = DispositionWords.contains(word)

  /**
   * 表态单写（阶段② · 「归档/反归档」退场后的**唯一可见性写面**）：
   * `disposition = word` + `dispositionAt = now`，**只动这两槽**——记录本体/子代理记账/
   * 转录/worktree/轮次台账原样（零删除纪律）。幂等：同值已表态 = 原时刻返回（零重写）。
   * 查无记录 = `Left`；word ∉ [[DispositionWords]] = `Left`（值域闸，绝不落自由文本）。
   * 写锁同缝串行（与其余单记录读-改-写互斥）。
   */
  def setDisposition(id: String, word: String): Either[String, Long] =
    if !validId(id) then Left(s"disposition: invalid session id '$id'")
    else if !isDispositionWord(word) then
      Left(s"disposition: unknown value '$word' (expected one of ${DispositionWords.toList.sorted.mkString(", ")})")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"disposition: no registry record for '$id'")
          case Some(rec) =>
            rec.disposition match
              case Some(existing) if existing == word =>
                Right(rec.dispositionAt.getOrElse(System.currentTimeMillis())) // 幂等：同值零重写
              case _ =>
                val ts = System.currentTimeMillis()
                try
                  put(rec.copy(disposition = Some(word), dispositionAt = Some(ts)))
                  Right(ts)
                catch case e: Throwable => Left(s"disposition: write failed: ${e.getMessage}")
      }

  /**
   * 表态清除（**非破坏性的对偶面**）：`disposition` / `dispositionAt` 双槽清空 ⇒
   * 该行按状态派生可见性回活动窗（收口后仍收口——清除的只是「用户表态」）。幂等：
   * 未表态 = `Right` 零写。查无记录 = `Left`。**零删除**：记录与全部历史原样。
   */
  def clearDisposition(id: String): Either[String, Unit] =
    if !validId(id) then Left(s"clearDisposition: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"clearDisposition: no registry record for '$id'")
          case Some(rec) =>
            if rec.disposition.isEmpty && rec.dispositionAt.isEmpty then Right(()) // 幂等：未表态零写
            else
              try
                put(rec.copy(disposition = None, dispositionAt = None))
                Right(())
              catch case e: Throwable => Left(s"clearDisposition: write failed: ${e.getMessage}")
      }

  /** 表态读数（纯查盘；面板/读面单点）。 */
  def dispositionOf(id: String): Option[(String, Long)] =
    find(id).flatMap(rec => rec.disposition.map(w => (w, rec.dispositionAt.getOrElse(0L))))

  // ── 置顶标记（阶段③：七天物理清理的**豁免面**）───────────────────────────

  /** 置顶读数（纯查盘；清理面/TTL 判读共用）。 */
  def pinnedOf(id: String): Option[Long] = find(id).flatMap(_.pinnedAt)

  /** 是否置顶（读数短形）。 */
  def isPinned(id: String): Boolean = pinnedOf(id).isDefined

  /**
   * 置顶单写（阶段③ · 作者令「重要记录置顶」）：`pinnedAt = now` 单槽——**幂等**
   * （已置顶 = 原时刻零重写，与墓碑/归档旗同款纪律）。置顶 = 终态 TTL **不判到期**
   * （[[DelegateTerminalTtl.Pinned]]）⇒ 七天物理清理永不作用于本身份；记录/台账/历史
   * 原样留存。查无记录 = `Left`（置顶只对真实 spawn 生效）。写锁同缝串行。
   */
  def pin(id: String): Either[String, Long] =
    if !validId(id) then Left(s"pin: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"pin: no registry record for '$id'")
          case Some(rec) =>
            rec.pinnedAt match
              case Some(ts) => Right(ts) // 幂等：已置顶原样返回，零重写
              case None =>
                try
                  val ts = System.currentTimeMillis()
                  put(rec.copy(pinnedAt = Some(ts)))
                  Right(ts)
                catch case e: Throwable => Left(s"pin: write failed: ${e.getMessage}")
      }

  /**
   * 摘置顶（**非破坏性的对偶面**）：`pinnedAt` 单槽清空 ⇒ 该身份回到正常 7 天 TTL 计时
   * （记录/台账/历史原样留存，零删除）。幂等：未置顶 = `Right` 零写。查无记录 = `Left`。
   */
  def unpin(id: String): Either[String, Unit] =
    if !validId(id) then Left(s"unpin: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"unpin: no registry record for '$id'")
          case Some(rec) =>
            if rec.pinnedAt.isEmpty then Right(()) // 幂等：未置顶零写
            else
              try
                put(rec.copy(pinnedAt = None))
                Right(())
              catch case e: Throwable => Left(s"unpin: write failed: ${e.getMessage}")
      }

  /**
   * 重启打断单写（任务列表行重启对账批 2026-10-22）：`restartInterruptedAt = now`
   * 单旗，**幂等**（已旗 = 原时间戳零重写，与 [[archive]] 同款）。查无记录 =
   * `Left`（打断标记只对真实 spawn 生效——与 updateReport 同纪律）。写锁同缝串行。
   */
  def markRestartInterrupted(id: String, at: Long): Either[String, Long] =
    if !validId(id) then Left(s"markRestartInterrupted: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"markRestartInterrupted: no registry record for '$id'")
          case Some(rec) =>
            rec.restartInterruptedAt match
              case Some(ts) => Right(ts) // 幂等：已旗原样返回，零重写
              case None =>
                try
                  put(rec.copy(restartInterruptedAt = Some(at)))
                  Right(at)
                catch case e: Throwable => Left(s"markRestartInterrupted: write failed: ${e.getMessage}")
      }

  /**
   * 取消墓碑单写（R1 缝隙 C 修）：`cancelRequestedAt = now` + 发起方标签，**幂等**
   * （已旗 = Right 零重写，markRestartInterrupted 同款）。查无记录 = `Left`
   * （墓碑只对真实 spawn 生效）。调用纪律 = cancel 动作**先写墓碑再发取消信号**
   * （crash 窗口内「信号已发而意图未落」不可能——意图先落盘）。
   */
  def markCancelRequested(id: String, by: String): Either[String, Long] =
    if !validId(id) then Left(s"markCancelRequested: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"markCancelRequested: no registry record for '$id'")
          case Some(rec) =>
            rec.cancelRequestedAt match
              case Some(ts) => Right(ts) // 幂等：已旗原样返回，零重写
              case None =>
                try
                  val ts = System.currentTimeMillis()
                  put(rec.copy(cancelRequestedAt = Some(ts), cancelRequestedBy = Some(by)))
                  Right(ts)
                catch case e: Throwable => Left(s"markCancelRequested: write failed: ${e.getMessage}")
      }

  /**
   * 重启打断旗摘除（会话续命名与位修正批 2026-11-03）：原位续命成功 = 打断结束
   * ——`restartInterruptedAt = None`。幂等：未旗 = Right 零写。查无记录 = `Left`。
   * 写锁同缝串行（与墓碑/归档写同缝——单记录读-改-写互斥）。
   */
  def clearRestartInterrupted(id: String): Either[String, Unit] =
    if !validId(id) then Left(s"clearRestartInterrupted: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"clearRestartInterrupted: no registry record for '$id'")
          case Some(rec) =>
            if rec.restartInterruptedAt.isEmpty then Right(())
            else
              try
                put(rec.copy(restartInterruptedAt = None))
                Right(())
              catch case e: Throwable => Left(s"clearRestartInterrupted: write failed: ${e.getMessage}")
      }

  /**
   * 取消墓碑摘除（面板行收口面补齐批 2026-11-21 · 甲′）：`cancelRequestedAt =
   * None` + `cancelRequestedBy = None`（两槽同生共死——发起方标签是墓碑的观测面，
   * 墓碑不在则标签无意义）。**非破坏性对偶面**：记录/子代理记账/转录/轮次台账/
   * worktree 原样留存，零删除、零归档旗写。
   *
   * 写入纪律（语义红线 = 防误删保险丝的延伸）：**只对「任务行已确认终态」的行调用**
   * ——调用点单点在 [[DelegateLedgerReconcile.clearMarksForTerminalRow]]（fail-close：
   * 读不出任务行 ⇒ 不清）。任何可能仍在飞的行绝不摘标记（在飞行仍会被复活/续命
   * 腿认领，墓碑是它们的封口）。
   *
   * 幂等：未旗 = `Right(())` 零写（unpin / clearRestartInterrupted 同款）。查无记录
   * = `Left`（墓碑只对真实 spawn 生效）。写锁同缝串行（与墓碑/归档/轮次写同缝）。
   */
  def clearCancelRequested(id: String): Either[String, Unit] =
    if !validId(id) then Left(s"clearCancelRequested: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"clearCancelRequested: no registry record for '$id'")
          case Some(rec) =>
            if rec.cancelRequestedAt.isEmpty && rec.cancelRequestedBy.isEmpty then Right(()) // 幂等：未旗零写
            else
              try
                put(rec.copy(cancelRequestedAt = None, cancelRequestedBy = None))
                Right(())
              catch case e: Throwable => Left(s"clearCancelRequested: write failed: ${e.getMessage}")
      }

  /**
   * 续命血缘单写（会话续命名与位修正批 2026-11-03）：派生腿（原位续不可恢复）
   * 在新会话记录上落 `resumedFrom = <原 delegate id>`。幂等：已落 = 原值零重写
   * （血缘是终身事实，首写权威）。查无记录 = `Left`（血缘只对真实 spawn 生效）。
   */
  def markResumedFrom(id: String, fromDelegateId: String): Either[String, Unit] =
    if !validId(id) then Left(s"markResumedFrom: invalid session id '$id'")
    else if !validId(fromDelegateId) then Left(s"markResumedFrom: invalid origin id '$fromDelegateId'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"markResumedFrom: no registry record for '$id'")
          case Some(rec) =>
            rec.resumedFrom match
              case Some(existing) => Right(()) // 幂等：首写权威
              case None =>
                try
                  put(rec.copy(resumedFrom = Some(fromDelegateId)))
                  Right(())
                catch case e: Throwable => Left(s"markResumedFrom: write failed: ${e.getMessage}")
      }

  /**
   * 显示名读数（会话续命名与位修正批 · A 部分单点消费）：任务账 title 的**去叠加
   * 形态**（[[SessionResumption.displayTitle]] 单点剥离）——AgentControl 列表 /
   * 快照面 / 任务行显示统一取本口径。剥空回落批次短名 → 会话 id。**纯读投影，
   * 零写盘**（存量污染行当场读干净，不改历史）。
   */
  def displayTitleOf(id: String): String =
    find(id).map(SessionResumption.displayTitle(_)).getOrElse(id)

  /**
   * 当前轮指针单写（轮次模型）：`roundNo` / `roundId` 一并落盘。**阶段② 起不再摘归档旗**
   * ——身份回现役窗是**状态派生**的（新轮未结算 ⇒ [[DelegateVisibility]] 现读判非收口 ⇒
   * 行自动回活动窗），零旗写盘即可复现「开新轮即回现役」。幂等语义由调用方
   * （[[DelegateRoundLedger.openNewRound]] / spawn 写点）持有——本函数只做读-改-写单点。
   * 查无记录 = `Left`（轮次只对真实 spawn 生效）。
   */
  def startRound(id: String, roundNo: Int, roundId: String): Either[String, Unit] =
    if !validId(id) then Left(s"startRound: invalid session id '$id'")
    else if roundNo < 1 then Left(s"startRound: roundNo must be >= 1, got $roundNo")
    else if roundId.trim.isEmpty then Left(s"startRound: roundId must be non-empty")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"startRound: no registry record for '$id'")
          case Some(rec) =>
            try
              put(rec.copy(roundNo = Some(roundNo), roundId = Some(roundId)))
              Right(())
            catch case e: Throwable => Left(s"startRound: write failed: ${e.getMessage}")
      }

  /**
   * JSON 编解码（circe）：Encoder = generic 派生；Decoder = **手工容错形态**——
   * 新扩的 Option 字段缺键时按 None 读（存量记录零迁移）。deriveDecoder 缺键即
   * 失败会让 `find`/`list` 把旧记录静默吞掉（catch → None），禁。
   */
  given io.circe.Encoder[Record] = io.circe.generic.semiauto.deriveEncoder

  given io.circe.Decoder[Record] = (c: io.circe.HCursor) =>
    for
      id <- c.get[String]("id")
      executor <- c.get[String]("executor")
      cwd <- c.get[String]("cwd")
      project <- c.get[Option[String]]("project")
      taskId <- c.get[Option[String]]("taskId")
      parentSessionId <- c.get[String]("parentSessionId")
      title <- c.get[String]("title")
      createdAt <- c.get[Long]("createdAt")
      name <- c.get[Option[String]]("name")
      branch <- c.get[Option[String]]("branch")
      worktree <- c.get[Option[String]]("worktree")
      baseBranch <- c.get[Option[String]]("baseBranch")
      projectWorkspace <- c.get[Option[String]]("projectWorkspace")
      reportStatus <- c.get[Option[String]]("reportStatus")
      reportDetail <- c.get[Option[String]]("reportDetail")
      reportSuggestion <- c.get[Option[String]]("reportSuggestion")
      workReport <- c.get[Option[String]]("workReport")
      cliSessionId <- c.get[Option[String]]("cliSessionId")
      plugins <- c.get[Option[List[String]]]("plugins")
      orchestrationMode <- c.get[Option[String]]("orchestrationMode")
      worktreeJudged <- c.get[Option[Boolean]]("worktreeJudged")
      archivedAt <- c.get[Option[Long]]("archivedAt")
      delegatedBy <- c.get[Option[String]]("delegatedBy")
      restartInterruptedAt <- c.get[Option[Long]]("restartInterruptedAt")
      cancelRequestedAt <- c.get[Option[Long]]("cancelRequestedAt")
      cancelRequestedBy <- c.get[Option[String]]("cancelRequestedBy")
      mode <- c.get[Option[String]]("mode")
      standbySince <- c.get[Option[Long]]("standbySince")
      frozenSince <- c.get[Option[Long]]("frozenSince")
      frozenReason <- c.get[Option[String]]("frozenReason")
      frozenDetail <- c.get[Option[String]]("frozenDetail")
      frozenTrigger <- c.get[Option[String]]("frozenTrigger")
      resumedFrom <- c.get[Option[String]]("resumedFrom")
      seatSnapshotSha <- c.get[Option[String]]("seatSnapshotSha")
      seatSnapshotDirty <- c.get[Option[List[String]]]("seatSnapshotDirty")
      roundNo <- c.get[Option[Int]]("roundNo")
      roundId <- c.get[Option[String]]("roundId")
      externalModel <- c.get[Option[String]]("externalModel")
      disposition <- c.get[Option[String]]("disposition")
      dispositionAt <- c.get[Option[Long]]("dispositionAt")
      pinnedAt <- c.get[Option[Long]]("pinnedAt")
    yield Record(
      id,
      executor,
      cwd,
      project,
      taskId,
      parentSessionId,
      title,
      createdAt,
      name,
      branch,
      worktree,
      baseBranch,
      projectWorkspace,
      reportStatus,
      reportDetail,
      reportSuggestion,
      workReport,
      cliSessionId,
      plugins,
      orchestrationMode,
      worktreeJudged,
      archivedAt,
      delegatedBy,
      restartInterruptedAt,
      cancelRequestedAt,
      cancelRequestedBy,
      mode,
      standbySince,
      frozenSince,
      frozenReason,
      frozenDetail,
      frozenTrigger,
      resumedFrom,
      seatSnapshotSha,
      seatSnapshotDirty,
      roundNo,
      roundId,
      externalModel,
      disposition,
      dispositionAt,
      pinnedAt
    )

  /**
   * delegate 会话的**生效执行形态**读数（工具面/挂载面分叉的单点判据）。
   *
   * delegate-param-decisions 批（2026-11-04）：**双模式总闸退役**——模式现在是
   * `Delegate` 工具的参数（不填 = 确定性 kernel），记录里的 mode 就是权威读面；
   * 不再有「闸关 ⇒ 盘上有 mode 也不分叉」的 Inert 态（那个闸会让显式
   * `mode="dispatcher"` 在缺省配置下静默失效）。`None` 只留给「非 delegate 会话 /
   * 记录缺席 / 本批之前的存量记录」——对 None 消费方恒走现状路径（零迁移）。
   */
  def modeFaceOf(sessionId: Option[String]): Option[String] =
    sessionId.filter(sid => DelegateSessions.isDelegateSession(Some(sid))) match
      case None => None
      case Some(sid) =>
        find(sid).flatMap(_.mode).filter(DualModeConfig.isModeWord)

  /**
   * 记录的 **raw** mode 词读面（归槽批 2026-11-18）。
   *
   * 与 [[modeFaceOf]] 同门（delegate 会话 + 记录在场），**但不做 `isModeWord` 过滤**：
   * 模型归槽判据必须能分开「mode 缺席（`None`）」与「mode 在场但不识别」（值域外的
   * 存量词，如已退役的 `merger`）——静默折叠成 `None` 会把这两件事混成一件，正是
   * 「兜底吞真信息」。消费点 = `ModelRoles.slotChainOf` 的请求面单点（未归槽 ⇒ 不覆写
   * 槽链 + 记一条可读原因）。非 delegate 会话 / 记录缺席 ⇒ None。
   */
  def rawModeOf(sessionId: Option[String]): Option[String] =
    sessionId
      .filter(sid => DelegateSessions.isDelegateSession(Some(sid)))
      .flatMap(sid => find(sid).flatMap(_.mode))

  /**
   * 后台任务归属批（作者令 2026-10-31「后台任务行标归属」）：开启任务的会话的
   * delegate 批次短名读面（Record.name，≤12 字）——与面板命名批的 delegateName
   * 链同源同判据（isDelegateSession + find + name trim），复用不新造。无双模式
   * 闸（归属标注不是双模式语义，同 activeAgents 快照 delegateName 面）。空/无名 =
   * None ⇒ 调用方落空串，前端回退既有显示（零渲染）。
   */
  def ownerNameOf(sessionId: Option[String]): Option[String] =
    sessionId.filter(sid => DelegateSessions.isDelegateSession(Some(sid))) match
      case None => None
      case Some(sid) =>
        find(sid).flatMap(_.name).map(_.trim).filter(_.nonEmpty)

end DelegateRegistry
