package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.actor.AgentCommand.*
import nebflow.agent.PromptSections.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.hooks.*
import nebflow.core.project.{NodeRoles, ProjectRuntimeRegistry}
import nebflow.core.tools.*
import nebflow.llm.{Fallback, TurnBudgetExceeded}
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

// 拆分迁移(2026-09-25,行为保持重构):trait AgentCore 改为合成层——
// registry-emit 基础族(AgentRegistryEmit)+ WS 流事件合批管道族(AgentStreamPipelines,
// 2026-09-07 长裁定注记随 object StreamBatching 同文件迁出)+ 会话与工具执行族
// (AgentSessionExecution,经 extends 前两者衔接以保 protected 互引合法)。成员可见性
// 原样迁移;AgentActor 侧 extends AgentCore with AgentSession 与各文件
// import AgentActor.* 的解析路径零变化。companion object AgentCore 须与 trait 同文件,
// 故整体留驻本文件。
private[agent] trait AgentCore extends AgentRegistryEmit with AgentStreamPipelines with AgentSessionExecution

object AgentCore:

  /** Nebula-root-session identity criterion (tool-face identity batch B1 /
    * taskunify 2026-09-24, single source): `agentDef.name == "Nebula" && depth == 0`.
    * Deliberately NOT the wider root-ish signals (`AgentRecord.kind == AgentKind.Root`
    * is broader -- standalone root sessions also set kind=Root; `rootSessionId ==
    * sessionId` needs a registry lookup = IO + registration timing). Consumers:
    * `ToolContext.isNebulaRoot` / `ToolContext.isNebulaWriter`. (W1 provisional
    * shim: ported verbatim from main's AgentCore -- the PR blob predates B1.) */
  def isNebulaRoot(agentDef: Option[AgentDef], depth: Int): Boolean =
    agentDef.exists(_.name == "Nebula") && depth == 0

  // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-F):isRootAgent 下沉 actor.RootAgentIdentity、
  // markToolProgress 下沉 actor.AgentState(签名零 agent 符号;此处不留转发别名,shim 禁令),
  // 本对象内调用点随迁改指。
  /**
   * 会话工具面身份（Q4/Q5 批 2026-09-13）：定义期变体选择的**唯一输入**。
   *
   * 三个分量各有**既有单点**来源（禁在任何消费点重写判据表达式）：
   *   - `isRootAgent` ⟵ [[isRootAgent]]（含 `depth` 分量）；
   *   - `isDispatcher` ⟵ `AgentState/session.isDispatcher`（`ProjectActor` spawn 置位）；
   *   - `nodeRole` ⟵ `SessionContext.flowNodeRole`，经 `NodeRoles` **归一 + 白名单校验**；
   *     **缺省 / 非法 / 未登记 ⇒ `None`**（= 基础面，fail-closed）——定义层**不**
   *     替运行期做「缺省就是 task」的身份推断：那是 `NodeReportTool.enumFor` 的
   *     运行期口径，定义层据此分化会让「无身份的旁支会话」静默落到 task 变体。
   */
  private[agent] final case class ToolFaceIdentity(
    isRootAgent: Boolean = false,
    isDispatcher: Boolean = false,
    nodeRole: Option[String] = None
  )

  private[agent] object ToolFaceIdentity:
    /**
     * **fail-closed 默认**：未知 / 未登记 / 缺身份的会话形态一律基础面。
     */
    val Base: ToolFaceIdentity = ToolFaceIdentity()

  /** 身份装配单点（[[buildToolList]] 内唯一调用点）。 */
  private[agent] def toolFaceIdentity(
    agentDef: AgentDef,
    depth: Int,
    flowNodeRole: Option[String],
    isDispatcher: Boolean
  ): ToolFaceIdentity =
    ToolFaceIdentity(
      isRootAgent = RootAgentIdentity.isRootAgent(Some(agentDef), depth),
      isDispatcher = isDispatcher,
      nodeRole = flowNodeRole.filter(NodeRoles.isValid).map(NodeRoles.normalize)
    )

  /**
   * 定义期 schema/描述分组：**唯一的变体选择点**（B2/L13 建立；Q4/Q5 批扩到
   * 三个目标工具，仍是**一处选择**）。
   *
   * 纪律（fail-closed）：**默认分支恒为基础变体**——未登记/未来新增的会话形态
   * 自动落基础面，绝不静默拿到分化变体。变异「默认分支改分化变体」⇒
   * `ToolFaceVariantSpec` 必红。
   *
   * 只替换**同一元素**的 `description`（不插删工具元素、零成员资格改动）⇒
   * `ALL_TOOLS` 的迭代序与工具数组逐位不变；**不得**给 `ALL_TOOLS` 加无身份维度的
   * 缓存（加了分化立即失效，规格 §3.4 新降级面 (b)）。
   *
   * 身份维度优先级：root 面先判（互斥形态取 root），再 dispatcher，再节点角色。
   */
  private[agent] def schemaVariantFor(
    td: ToolDefinition,
    identity: ToolFaceIdentity
  ): ToolDefinition =
    // AskUserQuestion 的 schema 变体分支已随「非阻塞-only」裁定整体删除：该工具一律
    // 以非阻塞方式提问，参数面不再有 `mode` 键 ⇒ root 面与基础面 schema 逐字节相等，
    // 无变体可分（`ToolFaceVariantSchemaSpec` 的 AskUser 豁免同步收紧为 Set.empty）。
    if NodeReportToolDef.Name == td.name then NodeReportToolDef.roleVariant(td, identity.nodeRole)
    else if MailTool.name == td.name then MailTool.addressFaceVariant(td, identity.isRootAgent, identity.isDispatcher)
    else td

  /**
   * stuck 自动恢复批 P1：LoopGuard 跨轮指纹的**只读投影**（设计 §3.4 互斥点 2 的
   * 可见性缺口）。`Counters.crossTurn` 在 AgentState 内、watcher 不可读；本函数把它
   * 折成两个可直接比较的标量，写点与既有 loopStreak/loopRounds 镜像**同点**
   * （`pipeToolExecutions`）——**不碰冻结面**（`AgentActor` / `LoopGuard`）。
   *
   *   - `loopStrikeCount` = `crossTurn` 各 fp 的失败 turn 集合大小之和（累计跨轮命中数）；
   *   - `lastLoopFp` = 命中次数最多者，同数取字典序最小（**确定性**，不受 Map 迭代序影响）。
   *
   * **只读不回流判据**：两者都不参与任何判死不等式，只作为「恢复 → 又被冻结」自激
   * 循环的识别信号（见 `TaskStuckWatcher` 互斥点 2）。
   */
  def projectLoopCounters(counters: nebflow.shared.Counters): (Int, String) =
    val hits = counters.crossTurn.iterator.map { case (fp, turns) => (fp, turns.size) }.toList
    val total = hits.iterator.map(_._2).sum
    val lastFp = hits.sortBy { case (fp, n) => (-n, fp) }.headOption.map(_._1).getOrElse("")
    (total, lastFp)

  /**
   * R1 (wait-timeout-fix, 2026-09-03 作者裁定①): the permission-confirmation
   * wait primitive — completes ONLY when the user answers, no timeout. This is
   * the single seam for the "permission waits are unbounded" invariant;
   * WaitTimeoutR1PermissionSpec pins it with a virtual clock (pending across
   * 6min ≫ the removed 5min PermissionTimeout → still waiting; late answer
   * honored). Caller: AgentCore.askUserPermission (the only permission wait).
   */
  def awaitPermissionDecision(deferred: cats.effect.Deferred[IO, Boolean]): IO[Boolean] =
    deferred.get

  /**
   * P0-1（2026-09-20）：mcpPermission 卡的等待原语 —— 与 [[awaitPermissionDecision]]
   * **同一不变式**（只由用户答复完成、无超时、Interrupt 唯一出口），只是答复型别
   * 携带 `scope` / `upgradeMode`（spec §2.4）。第二个 seam 而非改造第一个：
   * 内置工具审批链的等待语义逐字不变（A1-8）。
   */
  def awaitMcpPermissionDecision(
    deferred: cats.effect.Deferred[IO, nebflow.shared.McpPermissionAnswer]
  ): IO[nebflow.shared.McpPermissionAnswer] =
    deferred.get

  /**
   * #12 劝停: error text for the n-th user denial of `toolName` within one
   * turn. First denial stays minimal; from the second on, inject a
   * system-reminder (retryableHint pattern) so the LLM changes approach
   * instead of re-asking endlessly. Every denial is a real user action (the
   * timer-driven auto-deny was removed by R1, wait-timeout-fix).
   *
   * 2026-09-06 节点面摘除 AskUser 时 retryableHint 曾改为工具名中性（原
   * "via AskUserQuestion" 摘除）。2026-09-08 作者修订恢复 general 面
   * AskUserQuestion（D6 批D1）后口径复查：hint 保持工具名中性不变——这与其
   * 说因工具面组成，不如说语义本身如此：被拒的工具可能恰是 AskUserQuestion
   * 本身（点名即劝再问，荒谬），且 dispatcher 等身份不在面内；中性表述对所有
   * 身份均成立（澄清渠道节点=AskUserQuestion 提问/pending 节点/BLOCKED 回投，
   * Nebula=直接汇报）。
   */
  def denialMessage(toolName: String, n: Int): String =
    if n >= 2 then
      s"Permission denied by user ('$toolName' denied $n times this turn)." +
        "<system-reminder>The user has denied this tool " + n.toString + " times in this turn. " +
        "Repeating the same request will keep being denied. Change the approach, " +
        "raise a clarification through your existing reporting channel, or report the blocker " +
        "instead of retrying.</system-reminder>"
    else "Permission denied by user"

  /**
   * Nebula-exclusive tools: stripped from every identity except per
   * exclusiveToolsFor (Nebula keeps all; dream admitted for MemoryNote only —
   * see DreamAdmittedTools below).
   * - Delegate: 极简内核入口（**builtin-def 批 2026-10-03 在場恢复进
     *   `RootOrchestrationTools`**，作者令② Delegate 直接触发 kernel；本集条目
     *   继续承担**防声明逃逸**剥离——非 Nebula 身份的 agent.json/`"*"` 声明授不了）。
     *   目标恒为内置 `kernel` def（代码级，BuiltinAgents 单点）；Team 成员委派走
     *   SubTaskTool（self-clone + ephemeral）。
     *   Flow 触发不在此列——FlowTrigger 由 agent.json flows 白名单驱动注入。
     *   史实：2026-09-05 08:40 作者裁定曾把 Delegate 移出 Nebula 固定面；
     *   2026-09-10 指令 + 2026-09-11 R2-c 裁定以「极简内核」形态回归；
     *   promptgov 批 2026-09-17 再摘（史实归档，被 2026-10-03 作者令②取代）。
   * - AgentControl: 后台 agent 管控（list/status/cancel/restart，spec §4 安全
   *   边界矩阵——危险能力只交给根调度者）。
   * - Issue/CheckIssues（已退役，2026-09-04 作者终裁）：不再在本集——工具整体
   *   退役，报 issue 走 gh cli 由节点代劳（定义层已归档 .archived-tools-2d/）。
   *   未注册名无 schema、无执行路径，声明即惰性字符串，无须剥离。
   * - MemoryNote（阶段 2c §C.1 记忆行）：记忆写面原为 Nebula 专属（2026-08-31
   *   裁定①）。2026-09-05 作者签准修订：写面 = Nebula + dream——dream 仅准入
   *   修订动作（remove/update/replace_section），append 在工具执行层拒绝
   *   （DREAM_APPEND_DENIED——实现该铁律的记账工具本体已随 govmemory 批
   *   2026-09-25 整体退役，故动作面白名单现无机械面承载，见 registry 的退役注）。
   *   准入例外 = DreamAdmittedTools，剥离面经 exclusiveToolsFor 单点生效；
   *   Schedule/Delegate/AgentControl 对 dream 仍专属、不得放开。
   */
  val RootExclusiveTools = Set(
    "Schedule",
    "Delegate",
    "AgentControl",
    "MemoryNote",
    // TaskList（2026-09-06 TaskList 批）：Nebula 专属编排件——任务=快变状态
    // 存储（~/.nebflow/tasks.json），与 MemoryNote 同域隔离（非 Nebula 声明即剥）。
    "TaskList",
    // TaskBoard（20260908 任务板批 2）：项目域编排件（非 Nebula 专属——分发器
    // 固定面 + project 节点会话按身份挂载），但同享本集的【防声明逃逸】通道：
    // agent.json 声明（含 "*"）对一切非 Nebula 身份不授能。project 会话的真实
    // 授能在 buildAllowedToolSet 末段按会话身份追加（晚于本集剥离点），分发器
    // 固定面同理（nebulaFiltered 先剥、末段再挂）——剥离与授能两点不相干扰。
    "TaskBoard",
    // Pop（2026-09-10 作者裁定「我觉得把pop工具给nebula专属吧」）：Pop 收归
    // Nebula 专属——Canvas 是用户面呈现通道，节点乱 Pop 是过程件污染的入口，
    // 靠纪律不如靠工具面收口。本集同时是【防声明逃逸】通道：agent.json 声明
    // （含 "*"）对一切非 Nebula 身份不授能（Pop 已从 general 固定面摘除、从
    // 插件白名单摘除；真实授能 = NebulaOrchestrationTools 单点）。执行面另有
    // PopTool 分发层身份闸（POP_NEBULA_ONLY）——定义层与分发层两层收口。
    "Pop",
    // node_report（20260909 NodeReport 泛化批）：flow 节点会话专属终态语义申报
    // 工具，与 TaskBoard 同享防声明逃逸通道——"*"/显式声明对一切非节点会话身份
    // 不授能（真实授能 = 末段 flowNodeSession 按身份追加）。v1 report_blocked
    // 未入本集（通配声明者 allowedSet 带 schema 泄漏面，执行侧由工具内身份拒绝
    // 兜底），泛化批补齐同款隔离；剥离与授能两点不相干扰（同 TaskBoard 注）。
    nebflow.core.tools.NodeReportToolDef.Name,
    // ListFriends（好友消息改造批 ⑩，方案 `20260912_011320` §4.5 #5 推荐路线，
    // 2026-09-12）：Nebula 专属只读好友名册——全 agent 面披露的是**全量社交图谱**
    // （一次性给出「你是谁的好友」全集），与 TaskList/MemoryNote 同域隔离纪律一致；
    // 且本集同时是【防声明逃逸】通道：agent.json 声明（含 "*"）对一切非 Nebula
    // 身份不授能。本单点被两消费点共用（`buildAllowedToolSet` 的 runtime 剥离 +
    // `AgentLibrary` 面板/定义保存侧 strip —— 见 `exclusiveToolsFor` 注释）。
    // 选本路线而非 `buildAllowedToolSet` 内 `- "ListFriends"`（SendMessage 先例）：
    // 两者授能结果等价，但后者不覆盖保存侧。
    "ListFriends",
    // Subagent / Workflow（builtin-merge 批 2026-10-03）：执行 agent（nebflow）的
    // 机制面工具——同享本集【防声明逃逸】通道：agent.json 声明（含 "*"）对非
    // 授权身份不授能（真实授能 = AgentCore.NebflowFixedTools 单点）。
    "Subagent",
    "Workflow",
  )

  /**
   * dream 的 MemoryNote 准入例外（2026-09-05 作者签准，修订 2026-08-31 裁定①）：
   * 记忆写面 = Nebula + dream，dream 严禁写新记忆——仅放行修订动作（remove/
   * update/replace_section），append 在工具执行层拒绝（DREAM_APPEND_DENIED）。
   * 本集只放开【授能/剥离面】；动作面白名单原在该记账工具内（两道闸独立，
   * 摘任一道 spec 即红）——该工具与队列族已退役（memory-family-retirement 批
   * 2026-09-29）⇒ 动作面白名单随之消失，本集仅存授能/剥离语义。
   */
  val DreamAdmittedTools = Set("MemoryNote")

  /**
   * 身份 → 应剥离的 Nebula 专属工具集（剥离语义单点，两消费点共用）：
   * Nebula → 空（专属集全保留）；dream → 原集 − DreamAdmittedTools（仅
   * MemoryNote 准入）；其余身份 → 原集（行为零变化）。
   * 消费点：buildAllowedToolSet 的 nebulaFiltered（runtime 授能剥离）与
   * AgentLibrary 面板/定义保存侧 strip——dream 声明 MemoryNote 保存时不再
   * 被剥掉（否则准入形同虚设）。
   */
  def exclusiveToolsFor(name: String): Set[String] =
    name match
      // 字面量保留：case 模式匹配形态（改经常量会破坏 match）；身份名单点 = RootAgentIdentity.Name
      case "Nebula" => Set.empty[String]
      case "dream" => RootExclusiveTools -- DreamAdmittedTools
      // builtin-merge 批（2026-10-03）：执行 agent 的机制面豁免——Subagent/Workflow
      // 是 NebflowFixedTools 单点授能（本集在册只为防其他身份声明逃逸）。
      case "nebflow" => RootExclusiveTools -- Set("Subagent", "Workflow")
      case _ => RootExclusiveTools

  /**
   * Nebula 固定工具集（阶段 2c agent 收敛，设计文档 §C.1 角色-工具静态矩阵；
   * 裁定 11：全部机制注入不可配置）。2026-09-05 08:40 作者裁定改版：+基础文件
   * 四件（Bash/Read/Glob/Grep——从 BaseTools 取件）+Card 解封恢复（471 行后端
   * 工具整体回归，注册表同批恢复；其前端 iframe 消费面本批不恢复，chat 可视
   * 渲染待前端批）、−旧体系四件（Mail/Delegate/FlowTrigger/FlowExecute——旧
   * Team/Flow 体系对 Nebula 完全退役，节点结果沿 out 边自动回流；工具类与
   * 注册表注册全部保留——team/flow 双轨期
   * legacyFixedTools 对成员仍授能，零触碰）。**本条覆盖此前相关指令**：
   * 2026-09-12「一个 Mail 统一」批（B4 取代条款）已把「`Task` = 唯一项目触发
   * 入口」**作废**——新口径 = **Mail 唯一消息原语 + Task 已退役**；Nebula 经
   * `Mail(address="project:<name>")` 触发项目分发器（与已退役的 `Task` 同内核
   * `ProjectActor.TriggerDispatcher`）。2026-09-05 13:11 作者裁定曾把
   * 基础六件 Read/Glob/Edit/Write/Grep/Bash 定为全体 agent 统一默认
   * （Nebula 补齐 Write/Edit 至恰十七件——史实，时点 2026-09-05）；同日
   * 23:34 作者裁定
   * （「把你的 bash 和编辑工具收起来」）推翻 Nebula 例外：Bash/Write/Edit
   * 三件从本集移除、Nebula 回归纯编排——general/BaseTools 六件默认注入
   * 不变，Nebula 是唯一例外（编排件+读三件+MemoryNote，恰十四件——史实，时点 2026-09-05；
   * 其中「读三件」= 当日形态；2026-09-16 18:41 令后曾收窄为**读一件 Read**，
   * 该形态已被 2026-09-18 18:18 令取代 ⇒ **读三件在场恢复，史实归档**）。
   * 2026-09-06 00:48 作者裁定再摘 NodeList：节点结果沿 out 边自动投递
   * Nebula，主动查图与「全量派发 + pending 节点、不维护状态清单」的裁定
   * 职责重叠。2026-09-06 TaskList 批（作者 00:07 提议 + 00:11 首期无前端
   * 拍板）：+TaskList——Nebula 专属持久任务清单（快变状态出记忆、入
   * ~/.nebflow/tasks.json 运行时数据层；生命周期节点注入一行 open 摘要，
   * MemoryHygieneSignal 先例）——本集恰十四件（史实，时点 2026-09-06）。
   * 2026-09-11 Delegate 恢复批（作者 2026-09-10 指令 + R2-c 裁定 U3/U6）：
   * +Delegate（极简内核入口，目标恒为内置 `kernel` def——内核工具面 =
   * KernelFixedTools 恰七件 = BaseTools 六件 + AskUserQuestion）。本件是**加法**
   * 而非翻案：08:40 裁定的理由（旧 Team/Flow 双轨过渡件退出）不变，回归的
   * Delegate 不携带任何 Team/Flow/Mail 语义；Mail/FlowTrigger/FlowExecute
   * 维持退役。**在飞实测件数 = 17**（同一清单常量即单点来源，
   * NebulaOrchestrationToolsExpectedSize）。历史沿革（史实，非当前值）：
   * 2026-09-12 好友消息改造批 ⑩ +ListFriends → 16；2026-09-14 附件腿/退役批
   * #145 −TransferFile → 15；2026-09-16 18:41 作者令 −Glob −Grep → 13；
   * **2026-09-18 18:18 作者令「恢复nebula的bash edit write glob grep」+5
   * （Bash/Edit/Write/Glob/Grep）⇒ 17（在飞值）**。
   * **取代关系登记（2026-09-18 18:18 作者令，唯一依据）**：本令**取代** ① 2026-09-16
   * 18:41 作者令（commit `f9451705ac32c678f517c073c922e17841abdf6b`「root 工具面收缩：
   * Nebula(root) 面摘除 Glob/Grep（15→13）」）之 **root 面部分**、② 2026-09-05 23:34
   * 作者裁定（「把你的 bash 和编辑工具收起来」）之 **root 面部分**——**仅 root 面**；
   * **分发器面（DispatcherFixedTools）/ 节点·基础面（BaseTools / GeneralFixedTools /
   * KernelFixedTools）逐字不变**。故 2026-09-16「终态 = 13」与 2026-09-14
   * 「终态 = 15，已定」两笔口径转为 **provisional/存档**，不得作为待拍板项重提。
   * 🔴 2026-09-13「Glob/Grep 永久保留」旧裁定**不因本批复活**：本次恢复的依据**只有**
   * 09-18 18:18 令本身——不得援引旧裁定，也不得替旧裁定翻案。
   * 纪律不变：**不得**改断言常量去凑任何数字，也不得在树内实测值 ≠ 本常量时放宽
   * 断言（该纪律禁的是**为过测而放宽断言**，**不禁**按作者令变更 root 面本身——
   * 本次 12 → 17 即属后者）；⑩-9 的「终态待定」悬置口径已被作者 2026-09-14 拍板
   * 取代，只归档、不重提。
   * 分组与矩阵行一一对应：
   *   - 编排触发：Mail（R2「一个 Mail 统一」批：−Task +Mail，2026-09-12）/
   *     ProjectCreate / AgentControl（list/status/cancel/restart）
   *     / Delegate（2026-09-11 极简内核回归）
   *   - 任务编排：TaskList（2026-09-06 TaskList 批；NebulaExclusiveTools 同批
   *     防声明逃逸——dispatcher/general/"*" 一律剥离）
   *   - 通信：SendMessage（好友功能非旧体系，保留机制固定）
   *     / ListFriends（2026-09-12 好友消息改造批 ⑩：只读好友名册——SendMessage
   *     的寻址前置；同 NebulaExclusiveTools 防声明逃逸）
   *   - 读三件：Read / Glob / Grep（读代码读现状；一切执行走 Project 派发）。
   *     **Glob/Grep 自 2026-09-18 18:18 作者令起在 root 面恢复在场**——取代
   *     2026-09-16 18:41 摘除令之 root 面部分（**仅 root 面**；分发器面
   *     DispatcherFixedTools 与节点/基础面 BaseTools/GeneralFixedTools/
   *     KernelFixedTools 逐字不变）
   *   - 搜索件：Glob / Grep（2026-09-18 18:18 作者令恢复；史实——09-16 18:41 令曾
   *     从 root 面摘除，该摘除令之 root 面部分已被取代 ⇒ 归档）
   *   - 写手三件：Bash / Write / Edit（**2026-09-18 18:18 作者令恢复**——取代
   *     2026-09-05 23:34 裁定「把你的 bash 和编辑工具收起来」之 root 面部分；
   *     **仅 root 面**，general/BaseTools 六件默认注入逐字不变。史实——23:34 裁定
   *     曾把三件移出本集，该裁定之 root 面部分已被取代 ⇒ 归档）
   *   - 可视化：Card（2026-09-05 解封，commit 793f62c1 曾整体删除）
   *   - 用户面：AskUserQuestion / Pop（Pop = Nebula 专属可视化出口，2026-09-10
   *     作者裁定；非 Nebula 身份经 NebulaExclusiveTools 剥 + PopTool 身份闸
   *     双保险——节点交付物沿 out 边交链末端/Nebula，由 Nebula 决定是否展示）；
   *     平台：Schedule（TransferFile 已退役 2026-09-14，能力并入 SendMessage 的
   *     `device:` 附件腿——迁移指引见 RetiredToolGuides）
   *   - 记忆：MemoryNote（§C.2，白名单硬编码 User.md + agents/Nebula/memory.md）
   * 显式不含：NodeList（2026-09-06 00:48 裁定摘除——out 边自动投递取代主动查图；
   * dispatcher 自身面 DispatcherFixedTools 不受影响）、FlowTrigger/FlowExecute
   * （旧体系退役；**Delegate 已由 builtin-def 批 2026-10-03 作者令②恢复在集**）、
   * Web 系、
   * TeamTask*、SubTask、NodeEdit/NodeCancel。Issue/CheckIssues 已整体
   * 退役（2026-09-04 作者终裁：报 issue 走 gh cli 由节点代劳，定义层已归档
   * .archived-tools-2d/）。本集即 Nebula 工具面唯一来源：在飞十七件
   * （2026-09-18 18:18 作者令：+Bash +Edit +Write +Glob +Grep ⇒ 17，取代两笔摘除令
   * 之 root 面部分，**仅 root 面**；史实——本批前 12 = 09-16 18:41 令 −Glob −Grep
   * 后再 −Delegate）、零 Issue、零旧体系
   * FlowTrigger/FlowExecute/Task 三件（`Mail` **在**本集——R2 批翻案：
   * Mail 从「旧体系退役件」成为唯一消息原语）。
   * **反向指路**：Nebula 的代码定义 = `nebflow.core.entity.BuiltinAgents`
   * （builtin-def 批 2026-10-03 起，磁盘/种子镜像退役），其 `tools` 字段
   * **恒空且非权威面**——收敛名短路（本文件 ConvergedAgentNames 分支）使它授不
   * 了任何件；要找 Nebula 的工具清单，只有本集。
   */
  val RootOrchestrationTools = Set(
    // 编排触发（NodeList 2026-09-06 00:48 裁定摘除）
    // **Mail**（R2「一个 Mail 统一」批，2026-09-12 作者裁定 D-1/D-2/B4 取代条款）：
    // −`Task` +`Mail`，件数 16 → 16（史实：该批净 0；当前 = 17，见
    // NebulaOrchestrationToolsExpectedSize。本条覆盖此前「Task = 唯一项目触发入口」的
    // 全部相关指令——`Task` 已删净退役，不留壳、不留别名）。Nebula 的 Mail
    // **地址面按角色分层 = 仅项目分发器**（`project:<name>` 形态；裸项目名等价
    // 接受，D-1 取 B1-a 原样）：发 `node:<id>` 或自身地址（`"Nebula"`）⇒ 显式
    // 报错并指明合法地址面（硬禁静默兜底/模糊匹配）；入站不受限。
    "Mail",
    "ProjectCreate",
    "AgentControl",
    // Delegate（**builtin-def 批 2026-10-03 作者令②在場恢复**：「使用 Delegate
    // 直接触发 kernel，返回地址」）：极简内核入口——无项目归属的单次执行任务，
    // 是**编排件**不是能力件（执行能力 = 内核的 KernelFixedTools 七件）。本批
    // **取代** promptgov 批 2026-09-17（commit `99f4a5294`）的 Delegate 退役令之
    // 授能面部分：一次性执行任务的触发面从 `Mail(address="kernel")` 收归
    // `Delegate`，Delegate 的结果携带续聊地址 `kernel:<id>`；Mail 降级为通信
    // 原语（`kernel:<id>` 续聊腿保留，`kernel` 起实例腿同批退役）。
    // 史实（归档，被本批取代）：promptgov 批 13 → 12 曾摘除本件，退役口径为
    // 「一次性执行任务改路由 general 项目」；kernelgen 批 2026-09-26 又把一次性
    // 执行路由到 `Mail(address="kernel")`（kernelgen 批的 Mail kernel 腿 =
    // mailmodel 批 2026-09-25 裁定 (b)）。本集件数 18 → 19（在飞值，见
    // RootOrchestrationToolsExpectedSize）。
    // 本集之外不变：工具本体（DelegateTool）、AgentKind/子会话机制未动；
    // `RootExclusiveTools` 的 "Delegate" 条目继续承担**防声明逃逸**剥离（非
    // Nebula 身份声明 Delegate 仍被剥）。
    "Delegate",
    // 任务编排（2026-09-06 TaskList 批：快变状态出记忆；首期无前端）
    "TaskList",
    // 通信（好友功能非旧体系）
    "SendMessage",
    // ListFriends（好友消息改造批 ⑩，2026-09-12）：SendMessage 的**只读**前置——
    // 名册取代「靠报错反推」。与 SendMessage 同组（通信）、同一好友数据面与词表
    // （`nebflow.neblink.FriendRoster`），但零写面/零权限档/零限速（一次读）。
    // 归属面 = 本集单点 + NebulaExclusiveTools 防声明逃逸（方案 §4.5 归属面 A 案）。
    "ListFriends",
    // 文件面——读件（Read）+ 搜索件（Glob/Grep）+ 写手三件（Bash/Write/Edit）。
    // **2026-09-18 18:18 作者令「恢复nebula的bash edit write glob grep」已在场
    // 恢复 Glob/Grep/Bash/Write/Edit 五件**（取代 2026-09-16 18:41 摘除令与
    // 2026-09-05 23:34 裁定之 root 面部分；**仅 root 面**；分发器面
    // DispatcherFixedTools 与节点/基础面 BaseTools/GeneralFixedTools/
    // KernelFixedTools 逐字不变）。
    // 史实（保留为史实、标注被取代 ⇒ 归档）：0913「Glob/Grep 永久保留」裁定 →
    // 08:40 解禁四件 → 09-16 18:41 令 −Glob −Grep（15 → 13）→ 23:34 裁定收走写手
    // （Bash/Write/Edit 不在本集，彼时形态）。🔴 本批恢复的依据**只有** 09-18
    // 18:18 令本身——不得援引 0913 旧裁定（该裁定不因本批复活），也不得替旧裁定翻案。
    "Read",
    "Glob",
    "Grep",
    // 写手三件（Bash/Write/Edit）——**2026-09-18 18:18 作者令恢复**（取代
    // 2026-09-05 23:34 裁定「把你的 bash 和编辑工具收起来」之 root 面部分；
    // **仅 root 面**，general/BaseTools 六件默认注入逐字不变）。
    // 史实（归档）：09-05 23:34 裁定曾把三件从本集移除、Nebula 回归纯编排
    // （13 → 14 → 13 的当日形态见上）。🔴 本批恢复的依据**只有** 09-18 18:18 令，
    // 不得援引更早的旧裁定。
    // 🔴 纪律重申（本批正是其适用场景）：**不得**改断言常量去凑任何数字，也不得在
    // 树内实测值 ≠ 本常量时放宽断言——该纪律禁的是**为过测而放宽断言**，**不是**
    // 禁止按作者令变更 root 面本身（本次 12 → 17 即属后者）。
    "Bash",
    "Write",
    "Edit",
    // Card **已退役**（pop-upgrade 批 2026-10-03 作者令「去掉card工具」）：从本集
    // 摘除（19 → 18，见 RootOrchestrationToolsExpectedSize），工具本体与注册表
    // 注册一并删除。其「向用户展示成果」职责并入 Pop（媒体对话内直显叠卡 / 文件
    // 卡片转发 Canvas，见 PopTool 的 pop-upgrade 批注）。迁移指引 =
    // RetiredToolGuides("Card")。历史沿革（史实）：commit 793f62c1（2026-08-11）
    // 曾整体删除，2026-09-05 08:40 作者裁定解封恢复，本批终裁退役。
    // 用户面（Pop = Nebula 专属可视化出口，2026-09-10 作者裁定——Nebula 本体
    // 专属保留；非 Nebula 身份由 NebulaExclusiveTools 剥 + PopTool 身份闸兜底）
    "AskUserQuestion",
    "Pop",
    // 平台
    "Schedule",
    // 记忆（§C.2 MemoryNote）
    "MemoryNote"
  )

  /** friendseal batch (2026-09-25): names stripped from the Nebula DELIVERY
    * face while the friends feature is sealed. The static set above is
    * UNCHANGED — its registry-full-set semantics and the
    * `NebulaOrchestrationToolsExpectedSize` constant both stay untouched; the
    * seal is conditionalization at this ONE consumption point (the only
    * injection point for Nebula's fixed face, `fixedToolsFor`), reading the
    * same `features.friends` latch as the frontend (`nebflow.core.FriendsSeal`).
    * Currently exactly one name: `ListFriends` — sealed means the name never
    * reaches the LLM (no schema, no description), and a hallucinated call is
    * dropped by the allowed-set partition before any execution. SendMessage
    * STAYS on the face (its device leg remains): sealing its friend/group/
    * local arms happens inside the tool (`FRIENDS_SEALED`), and the
    * `buildAllowedToolSet` base-strip of the name (declaration-escape channel)
    * is deliberately NOT extended by this batch. */
  def friendsSealedStrip: Set[String] =
    if nebflow.core.FriendsSeal.isSealed then Set("ListFriends") else Set.empty

  /**
   * 阶段 2c 收敛的 agent 定义名（§C.1 总览，本集 = 四个在册名）：其 agent.json tools
   * 声明在 buildAllowedToolSet 中整体失效（base=∅）——机制固定不可配置（裁定 11），
   * 存量 agent.json 里的文件工具声明（8684acd Nebula 六件 / dispatcher Write/
   * Edit）自动变 no-op，无需定义层先行迁移。
   *
   * E5 batch 2026-10-01 (dangling-name closure): this set previously carried a FIFTH
   * name -- the memory-consolidation agent added by the 2026-09-12 memq batch (spec §5
   * R5 O-A, compressed dual-track second track). That agent retired on 2026-09-25
   * (`aa371d68f`: tool + `memory/queue.jsonl` queue deleted, seed resources ABSENT,
   * zero entry in the manifest `agents:` list), so its membership here became a
   * DANGLING name -- the criterion no longer resolves to any def that can exist. This
   * batch deletes the member, its `fixedToolsFor` by-name branch and the `AgentDef`
   * name constant (single-owner order: name-family closure belongs to the E5 batch
   * alone). The set is back to its four registered names.
   *
   * builtin-def 批（2026-10-03 作者令①「四个 agent 代码硬编码，唯一标准源就是
   * 代码」）：名字全集的**单一所有者** = `nebflow.core.entity.BuiltinAgents.Names`
   * ——本集改为引用该单点（agent → core 的既有依赖方向，零新环）。四个内置定义
   * 的 def 面同批上收进 BuiltinAgents（磁盘 agent.json/system.md 对这四名是死信）。
   */
  val ConvergedAgentNames = nebflow.core.entity.BuiltinAgents.Names

  /**
   * Nebula 工具面**在飞实测件数**（单点来源：所有件数断言只许引用本常量，
   * 不得各处写裸数字）。
   *
   * 值 = **18** = `NebulaOrchestrationTools` 现成员数。历史沿革（史实，非当前值）：
   * 2026-09-11 Delegate 恢复批 +1 → 15；2026-09-12 好友消息改造批 ⑩ +ListFriends
   * → 16；2026-09-12 R2「一个 Mail 统一」批 −`Task` +`Mail` ⇒ 净 0，保持 16；
   * 2026-09-14 附件腿/退役批（#145）`TransferFile` 退役 −1 ⇒ 15；
   * 2026-09-16 18:41 令 root 面摘除 Glob/Grep −2 ⇒ 13；
   * Delegate 退役批 −1 ⇒ 12；
   * **本批：2026-09-18 18:18 作者令「恢复nebula的bash edit write glob grep」
   * +`Bash` +`Edit` +`Write` +`Glob` +`Grep` ⇒ 17（在飞值）**。
   * **agentflow 批（2026-10-02）：+`AgentFlow` ⇒ 18（在飞值）**——root 侧派发件
   * （自包含任务书 → 项目分发器；终名 root #405）。与 `Mail` 不同物：`Mail` 是
   * 消息原语、root 面逐字保留，本批不动。本增量属「按面变更」（新增成员），
   * 非「凑数字」——下条纪律针对的是**为过测而放宽断言**。
   *
   * **取代关系记录（逐字，跨面）**：**2026-09-18 18:18 作者令**（原话「恢复nebula
   * 的bash edit write glob grep」）**取代** ① 2026-09-16 18:41 作者令（原话
   * 「去掉 nebula 的 glob 和 grep 工具…」，commit
   * `f9451705ac32c678f517c073c922e17841abdf6b`）之 **root 面部分**、② 2026-09-05
   * 23:34 作者裁定（「把你的 bash 和编辑工具收起来」）之 **root 面部分**——**仅
   * root 面**。分发器面（`DispatcherFixedTools`）与节点/基础面（`BaseTools`、
   * `GeneralFixedTools`、`KernelFixedTools`）**逐字不变**，反向钉见本文件
   * `DispatcherFixedTools`/`BaseTools` 常量与 `AgentConvergenceSpec`/
   * `Phase2dToolRefactorSpec` 的 dispatcher/general 断言。作者 2026-09-14
   * 「终态 = 15，已定」与 2026-09-16「终态 = 13」两笔口径随之 **provisional/存档**
   * （均已被本令取代，不得作为待拍板项重提）。🔴 2026-09-13「Glob/Grep 永久保留」
   * 旧裁定**不因本批复活**：本次恢复的依据**只有** 09-18 18:18 令本身。
   * 前批的 `Delegate` 退役属**授权面**变更（授能集合成员 −1）：**发散面**（工具本体/
   * 会话机制）未动，不得据此推断「Delegate 整机制已退役」。
   * 纪律不变：**不得**改本常量去凑任何数字，也不得在树内实测值 ≠ 本常量时放宽
   * 断言——该纪律禁的是**为过测而放宽断言**，**不是**禁止按作者令变更 root 面
   * 本身（本次 12 → 17 即属后者，属「按面变更」，非「凑数字」）。⑩-9 的两项旧
   * 口径（「终态 = 14，与 TransferFile 退役批同窗抵平」与「终态待定」，史实）均已
   * 被作者 2026-09-14 拍板取代——**归档，不得作为待拍板项重提**。
   *
   * **builtin-def 批（2026-10-03）：+`Delegate` ⇒ 19（在飞值）**——作者令②
   * 「使用 Delegate 直接触发 kernel，返回地址；Mail 降级为用于通信」。本增量属
   * 「按面变更」（恢复成员），**取代** promptgov 批 2026-09-17（`99f4a5294`）
   * 的 Delegate 退役令之授能面部分（史实 −1 ⇒ 12 的那笔；史实归档，不得据此
   * 推断 Delegate 面缺席）。
   *
   * **pop-upgrade 批（2026-10-03）：−`Card` ⇒ 18**——作者令
   * 「去掉card工具，然后对Pop工具进行升级」。Card 从本集摘除并整体退役（工具
   * 本体 + 注册表注册删除，迁移指引 = RetiredToolGuides("Card")）；展示职责并入
   * Pop（pop-upgrade 批注见 PopTool）。本减量同属「按面变更」（作者令摘除成员），
   * 非「凑数字」。
   * **unified-delegate 批（2026-10-03）：−`AgentFlow` ⇒ 17（在飞值）**——派发面
   * 统一收归 Delegate（Delegate(task, project?) 唯一派发入口；Mail 降级为按地址
   * 通信）。迁移指引 = RetiredToolGuides("AgentFlow")。
   */
  val RootOrchestrationToolsExpectedSize: Int = 17

  /**
   * 退役工具迁移指引表（R2「一个 Mail 统一」批，2026-09-12；设计件 §A.3 C-1）。
   *
   * **本表只产错误文案，零执行面**——不是兼容壳、不是别名、不做任何转发
   * （B5-c 硬禁静默 no-op 与悄悄转发）。消费点 = [[executeToolInner]] 的
   * `case None`（注册表查不到该名时）：给出「它退役了 + 改用哪个工具、怎么构造
   * 调用」。打在未注册名上的调用**只能**来自存量提示词 / 外部客户端 / 幻觉——
   * 恰恰是最需要指引的场景；现状兜底文案 `No such tool available: <name>`
   * 不含迁移指引，不满足「显式报错并指明改用 Mail」的要求。
   *
   * 表零膨胀纪律：只收「本批删净且必须给出迁移路径」的名字，不预收未来退役项。
   */
  val RetiredToolGuides: Map[String, String] = Map(
    "Task" ->
      """Project triggering is now Mail — use `Mail(address="project:<项目名>", message=<任务文本>)` (a bare project name is accepted too; the same engine entry, ProjectActor.TriggerDispatcher).""",
    "NodeMessage" ->
      """Node course-correction is now Mail — use `Mail(address="node:<节点id>", message=<补充文本>)` (same engine semantics: running = injected at the next turn boundary, wiring/pending = appended to the node task, terminal = refused).""",
    "TransferFile" ->
      """TransferFile is not available — its capabilities live in SendMessage: files to another of the user's devices use `SendMessage(to="device:<deviceName|deviceId>", message=<note>, attachments=[<absolute local paths>])` (chunked + both-side SHA-256, max 9 files x 1024 MB = 1 GiB each); local copies use `SendMessage(to="local", attachments=[...], targetDir=<dir>)`. Device-to-device pulls with a remote source (A->B) have no replacement.""",
    "TaskList" ->
      """TaskList is not available — the task ledger is `Task`, the one task-ledger tool (one ledger, one id space): `Task(action="list", assignee=..., project=...)` renders entries with state and dependencies, `Task(action="show", id=...)` renders one entry in full, and create/update/complete/close take the same `action` face.""",
    "TaskBoard" ->
      """TaskBoard is not available — the board's contents live in the single `Task` ledger, and the per-session face is the read-only `TaskInfo` (zero parameters — it renders the one task your session is attached to, note timeline included); any write belongs to Nebula's `Task` tool.""",
    "Card" ->
      """Card is not available — its show face is Pop: media (image/video) renders directly in the chat (one call may batch several paths), and every other file renders as a file card whose forward button opens Canvas. To present HTML/visuals, write the HTML to a file (Write/Bash) and `Pop` that file's path; to open a web page, `Pop` its URL (Canvas iframe).""",
    "AgentFlow" ->
      """AgentFlow is not available — dispatch goes through `Delegate(task=<brief>, project=<optional mounted project>)`. The receipt carries the task number (task #N) and the continuation address `delegate:<id>`; supplements/corrections go through `Mail(address="delegate:<id>", message=...)`. A project-targeted Mail(address="project:<name>") with a task number keeps working as a supplement channel, but new work is dispatched with Delegate only."""
  )

  /**
   * 分发器固定工具集（§C.1）：Node 三件（List/Edit/Cancel）+ 读四件
   * （Read/Glob/Grep/Bash，读现状 + git worktree 管理）。不给 Write/Edit（分发器只
   * 分解不产内容）、不给 AskUserQuestion（单次会话不阻塞等用户，§C.3）。
   * **Mail**（R2「一个 Mail 统一」批，2026-09-12 作者裁定）：−`NodeMessage`
   * +`Mail`，件数 9 → 9。分发器的 Mail **地址面按角色分层 = `Nebula`（root）
   * + `node:<id>`**：不给自己项目发（`project:<name>` 形态对分发器非法 ⇒
   * 显式报错并指明合法地址面）。旧 `NodeMessage` 已删净退役，其三态语义
   * （running=turn 边界注入 / wiring·pending=任务追加 / 终态拒绝）整体并入
   * `Mail(address="node:<id>")`，判据复用引擎侧单点 `NodeEngine.sendNodeMessage`。
   * TaskBoard（20260908 任务板批 2）第九件：项目任务板全权面（§1c 挂载表——
   * create 全量/update 全板含结构字段/close 全板/list 全板；权限判定的引擎侧
   * 身份=isDispatcher，工具内不信客户端参数）。
   */
  val DispatcherFixedTools = Set(
    "NodeList",
    "NodeEdit",
    "NodeCancel",
    // agentflow 批 2026-10-02：−`Mail` +`AskUserQuestion`（件数 9 → 9）。挂载点在
    // `buildAllowedToolSet` 末段按引擎侧身份 `isDispatcher` 追加（TaskBoard/
    // node_report 同款「挂载面 + 工具内身份拒绝」双保险）。
    "AskUserQuestion",
    "Read",
    "Glob",
    "Grep",
    "Bash",
    "TaskBoard"
  )

  /**
   * Team task tools（任务工具重做 2026-08-30：category=team 机制层注入
   * 全体成员——Manager 与成员同级可用，任务=进展展示语义；不再是 lead 专属
   * owner 集。SubTask workers / flow nodes / depth≥2 "*" agents 仍剥离）。
   */
  val TeamTaskTools = Set("TeamTaskCreate", "TeamTaskUpdate", "TeamTaskList")

  /**
   * Base tools always available to ALL agents regardless of category.
   * These are injected automatically — agent.json does not need to list them.
   *
   * Issue was removed (user ruling 2026-08-25 17:49 工具体系精简), then fully
   * retired with CheckIssues (2026-09-04 终裁: 报 issue 走 gh cli 由节点代劳) —
   * no agent has it; NebulaExclusiveTools no longer lists it either.
   */
  val BaseTools = Set(
    "Read",
    "Write",
    "Edit",
    "Glob",
    "Grep",
    "Bash"
  )

  /**
   * 极简内核（Delegate 内核）固定工具面（2026-09-11 恢复批，R2-c 裁定）：
   * 恰七件 = BaseTools 六件 + AskUserQuestion。
   *
   * - 六件 = `BaseTools`（:2360-2367）**同集合** = `RemoteExecutor.remoteableTools`
   *   同集合 ⇒ `device` 参数由 registry.scala 逐件 augmentSchema 自动注入，
   *   零改动拿到跨设备路径。
   * - +AskUserQuestion（R2-c，作者 2026-09-11 拍板采纳）：内核遇「必须用户拍板
   *   才能继续」的信息（凭据/目标/口径）可直接提问，卡片渲染在派发方窗口，
   *   来源标注 `subagent · <任务摘要>`（U3）。
   * - 显式**不含**：Delegate/SubTask/Task（叶子纪律——内核不再派生）、Mail
   *   （已退役）、AgentControl/Card/Pop（管控与用户面归 Nebula）、TaskBoard/
   *   node_report/Node 系工具/TaskList/MemoryNote/Schedule（项目与编排面）、
   *   plugin 与 MCP 工具（极简 = 机制固定、零配置面，裁定 11）。
   *
   * 零配置面：内核名在 ConvergedAgentNames 内 ⇒ agents/kernel/agent.json 的
   * tools/mcpServers 声明整体失效，本常量即唯一来源。
   */
  val NebflowFixedTools: Set[String] = BaseTools + "AskUserQuestion" + "Subagent" + "Workflow"

  /**
   * 只读侦察子面（builtin-merge 批 2026-10-03）：SubagentTool 的 spawn 目标
   * `subagent` 的机制固定工具面——恰五件只读（Read/Glob/Grep/WebSearch/
   * WebFetch），**无写、无 shell、无消息**（只读由机制保证，不由提示词保证）。
   */
  val SubagentFixedTools: Set[String] = Set("Read", "Glob", "Grep", "WebSearch", "WebFetch")

  /**
   * 通用模版固定工具集（§C.1/§C.5）：恰七件 = BaseTools 六件 + AskUserQuestion。
   *
   * 2026-09-10 作者裁定（「我觉得把pop工具给nebula专属吧」）：Pop 从本集摘除
   * ——Pop 收归 Nebula 专属，通用模版（general/节点会话）不再持有；节点交付物
   * 沿 out 边交链末端/Nebula，由 Nebula 决定是否展示（工具面 + PopTool 身份闸
   * 双层收口）。裁定 5 原文「8 件」中的 Pop 一项由此作废，其余七件不变。
   *
   * 恢复裁定（2026-09-08 作者修订，D6 spec §3.2 G6/G7 gate）：直达作者方案
   * ——AskUserQuestion 回归 general 默认面，节点提问经 InteractionHub 直达
   * Nebula 窗口；分发器监督语义以 ask 留痕补齐而非路由（节点提问写
   * node-ask 留痕事件，分发器经事件流审计可见；来源标注「项目 · 节点」随
   * F1F2 前端批同窗落地）。9-6 历史脉络（2026-09-06 作者提议 + Nebula 背书：
   * 「交互出口统一」曾摘除本件七件化）——其针对的「表演性交付/随意打扰」
   * 风险由定向手段化解（来源标注 + 留痕审计 + description anti-pattern 条款），
   * BLOCKED/pending 节点通道原样保留分层（任务级申告 vs 执行级提问）。
   * 机制面零改动：AskUserQuestionTool/InteractionHub/流式特判照旧；
   * Guardrails 剥离对 isFlowNode 会话豁免 AskUserQuestion（G8，见
   * buildAllowedToolSet isFlowNode 分支注释）。Web 系不在默认面内——经
   * §B.6 plugin 扩展授予；MultiEdit 已从 ToolRegistry 删除（能力由 Edit
   * replace_all 覆盖）。
   */


  /**
   * Fixed tools for a given agent — 阶段 2d（D.1-1）后的唯一注入入口。
   *
   * 收敛四定义（§C.1 角色-工具静态矩阵，裁定 11 机制固定零配置；2026-09-11
   * 极简内核批收进 kernel）直接返回静态集常量（收口）：Nebula /
   * project-dispatcher / general / kernel 不再经过任何 legacy 分支路径——2c
   * 建集、2d 删路，常量即唯一事实源。收敛判据**按名**
   * （ConvergedAgentNames），category 不再能把收敛名推去 legacy 面
   * （2026-09-11 agentdef-tidy 批，见方法体首段注释）。
   *
   * 双轨期 legacy 路径（legacyFixedTools）保留至阶段 3：
   * - team 成员：BaseTools + Mail + SubTask + TeamTask 三件（user ruling
   *   2026-08-24 机制层注入 SubTask；FlowExecute 随 2026-09-06 工具面裁撤批
   *   移出固定面）
   * - flow 节点：BaseTools（FlowReport 随 2026-09-06 裁撤批移出——叶子节点
   *   文本输出即结果，verdict 通道退役）
   * - legacy standalone：BaseTools catch-all——Coder/Explorer/design-engineer
   *   等存量 agent 的 agent.json 未声明文件工具，依赖此路径（删除即断活
   *   agent 工具面），随阶段 2e/3 归档一并退役。
   *
   * category 分支配 `if !converged` 守卫（2026-09-11 批前）：非收敛名的
   * category 分支行为逐字节未动（2c parity 保持）。
   */
  def fixedToolsFor(agentDef: AgentDef): Set[String] =
    // 纵深（2026-09-11 agentdef-tidy 批，安全）：收敛名短路 —— category 不再能
    // 把收敛名推去 legacy 面。主收敛点在唯一 JSON 读取点
    // AgentLibrary.loadFromDir（收敛名 category 恒 standalone）；此处兜的是
    // **不经**该点而合成出带 category=team/flow 的收敛名 AgentDef 的路径
    // （EntityLoader.toAgentDef 按路径推断 / 代码兜底 / 运行时 copy / 未来新增
    // 构造点）。fixedToolsFor 是固定工具的**唯一注入入口**（buildAllowedToolSet
    // 注入注释），在此一处收口 ⇒ 「收敛名拿 team 遗留工具面（Mail/SubTask/
    // TeamTask*）」在结构上不可达，与 category 来源无关。
    // 判据与 buildAllowedToolSet 的 base=∅ 同款：**按名**（ConvergedAgentNames），
    // 避免同一收敛语义出现两套判据。非收敛名逐字节 parity：team/flow 仍落
    // legacyFixedTools，name 分支逐字未动。
    val converged = AgentCore.ConvergedAgentNames.contains(agentDef.name)
    agentDef.category match
      case "team" | "flow" if !converged => legacyFixedTools(agentDef)
      case _ =>
        agentDef.name match
          // 字面量保留：case 模式匹配形态（改经常量会破坏 match）；身份名单点 = RootAgentIdentity.Name
          case "Nebula" =>
            // 静态集收口（史实时点：恰十四件；**当前 = 17** = 2026-09-18 18:18
            // 作者令 +Bash/Edit/Write/Glob/Grep 后值；此前 12 = −Delegate 批、
            // 13 = root 面 −Glob −Grep，均史实）：
            // 零 Issue、零旧体系四件（TaskList
            // 批：+TaskList，作者 00:07 提议 + 00:11 首期无前端拍板）。终裁记录：
            // （2026-09-04 作者裁定）Issue/CheckIssues 退役，报 issue 走 gh cli
            // 由节点代劳；定义层已归档（agent.json CheckIssues 声明删除、
            // ~/.nebflow/tools/ 下 issue/check-issues/screenshot 归档
            // .archived-tools-2d/）。2c 的 + "Issue" parity carry 至此删除。
            // （2026-09-05 08:40 作者裁定）+基础四件 Bash/Read/Glob/Grep、+Card
            // 解封、−Mail/Delegate/FlowTrigger/FlowExecute 旧体系退役。
            // （2026-09-05 23:34 作者裁定：Nebula 回归纯编排）13:11 的
            // +Write/Edit 补齐被推翻——Bash/Write/Edit 三件移出本集（**该裁定之
            // root 面部分已被 2026-09-18 18:18 令取代 ⇒ 三件在飞在场；史实归档**）；
            // general/BaseTools 六件默认注入不变
            // （编排件+读三件 Read/Glob/Grep+写手三件 Bash/Write/Edit+MemoryNote，
            // 在飞恰十七件）——本集即 Nebula 工具面唯一来源。
            // friendseal (2026-09-25): the static set above stays the single
            // source; the friends seal strips names at this one consumption
            // point (see friendsSealedStrip — currently ListFriends only).
            AgentCore.RootOrchestrationTools -- AgentCore.friendsSealedStrip
          case "project-dispatcher" => AgentCore.DispatcherFixedTools
          // 合并执行 agent（builtin-merge 批 2026-10-03：kernel+general ⇒ nebflow）：
          // 基础六件 + AskUserQuestion + Subagent（只读侦察）+ Workflow（步级 DAG）。
          // 机制固定单点，不经 legacyFixedTools 的 catch-all。
          case "nebflow" => AgentCore.NebflowFixedTools
          // 只读侦察子（SubagentTool 的 spawn 目标）：机制固定只读五件。
          case "subagent" => AgentCore.SubagentFixedTools
          // E5 batch 2026-10-01: the former fifth member's by-name branch is deleted
          // together with that name -- the agent retired on 2026-09-25 (`aa371d68f`)
          // and its name constant in AgentDef went with it in this batch, so the
          // branch is unreachable by construction.
          case _ => legacyFixedTools(agentDef)

    end match

  end fixedToolsFor

  /**
   * 双轨期 legacy 固定工具（team/flow 分支 + standalone BaseTools catch-all）。
   * 阶段 3 随 team/flow 退役与 legacy agent 归档整体删除（D.1-1 残留面；
   * 三角色 name 分支已删——收口进 fixedToolsFor 静态集派发）。
   */
  private[agent] def legacyFixedTools(agentDef: AgentDef): Set[String] =
    agentDef.category match
      // 任务工具重做（2026-08-30）：任务只配 team——TeamTask 三件机制层注入
      // 全体 team 成员（照 #381 SubTask 先例；ctx.teamName 把写域钉死在
      // 自己的 team，无跨 team 面）。FlowExecute 移出固定面（2026-09-06
      // 工具面裁撤批）。
      case "team" => BaseTools + "Mail" + "SubTask" ++ AgentCore.TeamTaskTools
      // FlowReport 移出固定面（2026-09-06 裁撤批）——flow 节点文本输出即结果。
      case "flow" => BaseTools
      case _ => BaseTools

end AgentCore
