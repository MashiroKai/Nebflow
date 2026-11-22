package nebflow.core.tools

import nebflow.shared.ToolDefinition

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

object ToolRegistry:
  private val tools = new ConcurrentHashMap[String, Tool]()

  locally {
    val builtins: Map[String, Tool] = Map(
      // File operations
      "Read" -> ReadTool,
      "Write" -> WriteTool,
      "Edit" -> EditTool,
      // MultiEdit 已从注册表移除（阶段 2c §C.1/裁定 5：通用 agent 8 件之外；
      // 能力由 Edit 的 replace_all 覆盖）。MultiEditTool 类保留（非物理删除），
      // spec 与未来 plugin 扩展（§B.6 白名单扩展）仍可引用。
      // Search
      "Glob" -> GlobTool,
      "Grep" -> GrepTool,
      // Shell
      "Bash" -> BashTool,
      // Web
      "WebSearch" -> WebSearchTool,
      "WebFetch" -> WebFetchTool,
      "Curl" -> CurlTool,
      // Canvas file display
      "Pop" -> PopTool,
      // Card **revived 2026-11-18** (card-tool-revive batch, author ruling
      // 「找到我们 git 历史中的 card 工具 让 canvas ask 可以使用 card 工具」).
      // The body is restored byte-for-byte from the last pre-retirement
      // revision (`02d781530^` — the file the pop-upgrade batch deleted) and
      // registered again under its original name; the sentinel
      // (`___CARD_HTML___`), the `FileRefs` shared policy and the frontend
      // consumer (`cardRegistry.js` → sandboxed iframe) all survived the
      // retirement untouched, so the revived tool renders with zero frontend
      // change. 呼号语义 [[nebflow.core.tools.CardTool]] 一字未改。
      //
      // 🔴 授权面 = **Canvas Ask 唯一**：名字进 `AskSessions.AskExclusiveTools`
      // （单点），由此同时得到两件事——① ask 会话的早退面（`buildAllowedToolSet`
      // 的 `askSession` 分支返回 `AskSessions.AllowedTools`）把它带上；② 该函数
      // 尾段的 `-- AskExclusiveTools` 把它从**其余一切身份**（Nebula root /
      // 项目节点 / 分发器 / delegate / subagent / kernel）剥净。声明（含 `"*"`）
      // 与插件白名单（`PluginRegistry.BuiltinToolWhitelist`）均不含本名，且
      // 工具内侧另有 `AskSessions.isAskSession` 身份闸（双保险）。
      // Pop 不动：Nebula 专属面逐字不变，两件职责不重叠（见 CardTool 顶注）。
      //
      // History (史实)：`793f62c1`（2026-08-11）整体删除 471 行 → 2026-09-05 08:40
      // 作者裁定解封（`21bc2e74a`，回归后长到 1238 行）→ `02d781530`（2026-10-03
      // pop-upgrade 批，作者令「去掉card工具,然后对Pop工具进行升级」）退役 →
      // 2026-11-18 本批复活并收窄给 ask。
      "Card" -> CardTool,
      // 分支管理问答批（gitviz，2026-11-19 第二轮）：gitviz_graph = 项目分支图只读取数件。
      // 授权面与 Card 同款单点：名字进 `AskSessions.AskExclusiveTools`（因此由
      // `buildAllowedToolSet` 尾段的 `-- AskExclusiveTools` 从其余一切身份剥净），
      // 实际挂载面 = `AskSessions.GitVizAllowedTools`（`ask-gv-` 会话专属）；
      // 工具内侧另有 `isGitVizSession` 身份闸（双保险）。
      // 🔴 零写面：实现只调用 `GitGraph.build`（子命令白名单 rev-parse/for-each-ref/
      // log/rev-list/merge-base），不 merge/不 branch/不 push/不改 ref。
      "gitviz_graph" -> GitVizGraphTool,
      // 交办腿（同批）：gitviz_handoff = 把「合并/建支/push」表达为**结构化请求**投给
      // 主会话（AskHandoffPort.deliver，与面板「多选+合并」钮同一个出口）。它不执行
      // 任何 git 命令 —— 两条入口一个出口的「出口」就是这里。
      "gitviz_handoff" -> GitVizHandoffTool,
      // User interaction
      "AskUserQuestion" -> AskUserQuestionTool,
      "AskHost" -> AskHostTool,
      "AskHostReply" -> AskHostReplyTool,
      "AskHostClarify" -> AskHostClarifyTool,
      // Perception mail (mail-perception-impl batch): draft + snapshot card;
      // the user always triggers the actual send (card/canvas).
      "EmailSend" -> PerceptionMailTool,
      // 团队任务工具（TeamTaskCreate/Update/List）随团队类型面删除批阶段 B2 退役
      // （作者裁定 Q11）：它们的注入面 = category=team 机制层，团队退役后无注入方；
      // ToolContext.teamName 恒 None ⇒ 三个工具必 hard-reject。存储 TaskStore 保留
      // （session 域 TaskList 仍在用），本批只撤工具面。
      // Scheduled tasks
      "Schedule" -> ScheduleTool,
      // Unified agent communication —— **唯一消息原语**（R2「一个 Mail 统一」，
      // 2026-09-12 作者裁定）。三条腿共用一套地址语法：`project:<name>`（Nebula →
      // 项目分发器；裸项目名等价接受）/ `Nebula`（分发器 → root；解析到真正的
      // Nebula root 会话，认不出即显式报错）/ `node:<nodeId>`（分发器 → 节点；
      // 复用引擎侧 NodeEngine.sendNodeMessage 三态判据）+ 既有 team 腿。
      // 授权面按角色分层（Nebula 只见 `project:`；分发器只见 `Nebula`/`node:`；
      // 节点不挂 Mail）。旧注释「message + ask modes」已过时：ask 模式由
      // 2026-08-27 作者裁定整体移除。
      "Mail" -> MailTool,
      // 旧 AgentFlow（dispatcher 派发面）retired (unified-delegate batch
      // 2026-10-03): dispatch is unified under Delegate(task, project?); 其退役
      // 墓碑条目随 N1 复名批删除（名字复用后表对该名永不可达——registry 注册即
      // 令 RetiredToolGuides 对该名失效的既有教义）。
      // Sub-agent delegation → 内置极简内核（2026-09-11 恢复批）：Nebula 专属
      // 执行件，目标恒为 `kernel` def（无 agent 目标参数）；persistent 模式已退役。
      "Delegate" -> DelegateTool,
      // builtin-merge 批（2026-10-03）：Subagent = 侦察子入口（阻塞，结果即
      // 返回值）。授权面 = AgentCore.NebflowFixedTools（执行 agent 机制面）；
      // RootExclusiveTools 防声明逃逸。
      // 🔴 tool-face-agentflow-retire 批（2026-11-05，作者裁定二）：`AgentFlow` 名
      // **不再注册**（AgentFlow 工具整文件删除，能力原样留在 ChainWrite 一个入口）。
      // 注册面没有该名 ⇒ `executeToolInner` 的 `case None` 命中
      // `AgentCore.RetiredToolGuides("AgentFlow")`，给出「agentflow 只是模式名；改用
      // ChainWrite 一次写全链」的可诊断错误（绝不是静默 no-op，也不是别名转发）。
      "Subagent" -> SubagentTool,
      // ChainWrite（N1 刀2 新注册项，v3 §5 S4）：delegate 会话专属整链登记件——
      // 一次调用登记节点＋deps＋任务＋描述整链进调用 delegate 自己的作用域图，
      // 校验过 → 原子落盘 → 入口放行（链齐才跑）。授权面 = buildAllowedToolSet
      // 尾段 delegateSession 腿；RootExclusiveTools 防声明逃逸（插件声明不授能）。
      "ChainWrite" -> ChainWriteTool,
      // Background agent inspection & control (list/status/cancel/restart) — Nebula 专用
      "AgentControl" -> AgentControlTool,
      // FlowTrigger / FlowExecute / FlowReport retired 2026-09-06（工具面裁撤
      // 批，作者裁定提前执行阶段 2d 子集）：agent 侧 flow 触发/动态编排/verdict
      // 报告三工具整体退役。FlowDagRunner 引擎本体当时保留（红线：引擎零触碰），
      // 后由 R4 卫生批 2026-10-29（P4）删除——其唯一 spawn 点（FlowTrigger）已
      // 退役，全仓零生产调用点；FlowReportStore（FlowDagExecutor 消费的 verdict
      // 数据面）抽出为独立文件保留至阶段 3。
      // Team-member task delegation (self-clone + ephemeral worker, no Mail identity)
      // TransferFile retired 2026-09-14 (#145): cross-device file moves moved into
      // SendMessage's `device:` attachment leg (Dropbox chunked channel); local
      // copies = SendMessage `to="local"`. Migration guide: AgentCore.RetiredToolGuides.
      // The transfer CHANNEL stays (FileTransferAction / relayTransfer* /
      // /api/neblink/transfer / NeblinkService.receiveFile|sendFile — the Dropbox
      // relay fallback depends on them): we retired the tool, not the channel.
      // A2A 一期: agent sends a message to one of the user's NebLink friends
      // (#290). Authorization (阶段 2d, D.1-11): mechanism-fixed for Root
      // only (RootOrchestrationTools, 2c 起) — agent.json declaration
      // channel removed (buildAllowedToolSet strips the name from base).
      "SendMessage" -> FriendMessageTool,
      // ListFriends（好友消息改造批 ⑩，方案 `20260912_011320` §4.5 定稿）：Nebula
      // 编排面**只读**好友名册——`SendMessage` 的寻址前置（既有候选文案只在失败
      // 路径出现，且 displayName == username 时零区分力）。授权面 = 机制固定唯一
      // （`NebulaOrchestrationTools` 单点；名字进 `NebulaExclusiveTools` 防声明
      // 逃逸——agent.json 声明与 "*" 对一切非 Nebula 身份不授能）。插件白名单
      // （`PluginRegistry.BuiltinToolWhitelist`）与远端可执行面
      // （`RemoteExecutor.remoteableTools`）均**不含**本件（零参数 schema，
      // 不得被 augmentSchema 注入 `device`）。
      "ListFriends" -> ListFriendsTool,
      // Load Team/Flow from disk (validate + mount)
      "Load" -> LoadTool,
      // FlowReport 注册已随 2026-09-06 工具面裁撤批移除（见上注）。
      // #28 阶段 0：Project + Node 模型工具集（分发器白名单声明；全局注册使
      // agent.json tools 可解析）。NodeEdit/NodeList/NodeCancel = 分发器用；
      // Project = Nebula 用（N2 project-tool 批 2026-10-06 ⑨A 更名，原 ProjectCreate
      // ——建项目 + 工作区脚手架 + unmount 硬卸载面；workspace 缺省/不可用
      // 时弹 AskUser 式路径面板复用 pending 机制。旧名走 RetiredToolGuides 墓碑指路）。
      // 本条覆盖此前相关指令：**`Task` 已删净退役**（2026-09-12「一个 Mail
      // 统一」批，B3-a + B5-c）——当时的触发入口 `Mail(address="project:<name>")`
      // 亦已随 project-trigger 拆除批（2026-10-18）退役，派发入口唯一 =
      // `Delegate(task=<自足任务书>, project=<name>)`；
      // 打到 `Task` 的调用走 AgentCore 的退役工具迁移指引表（只产错误文案，
      // 非兼容壳，零执行面）。**`NodeMessage` 已删净退役**——并入
      // `Mail(address="node:<id>")`（三态判据复用引擎侧 NodeEngine.sendNodeMessage）。
      "NodeEdit" -> NodeEditTool,
      "NodeList" -> NodeListTool,
      "NodeCancel" -> NodeCancelTool,
      "Project" -> ProjectTool,
      // MemoryNote registration retired 2026-09-25 (govmemory batch): the tool
      // was pure queue bookkeeping (zero disk writes since 2026-09-12) and the
      // queue is retired with it. Memory is written directly with Edit/Write on
      // the three memory layers (budget guard: MemoryDirectWriteGuard); calling
      // the retired name hits AgentCore.RetiredToolGuides (error text only).
      // Task (taskunify batch 2026-09-24): `TaskList` + `TaskBoard` are merged into **one**
      // item -- the persistent task ledger (`~/.nebflow/tasks-v2.json`, a single global
      // file, the runtime data layer). The authorization face = the single source
      // `NebulaOrchestrationTools` (members renamed, item count unchanged = 17);
      // `NebulaExclusiveTools` prevents declaration escape (dispatcher/general/"*" are all
      // stripped).
      // 🔴 A single write right: the dispatcher and project nodes **never see** this item at
      // all (the mount face), and inside the tool the engine-side identity
      // (ctx.isDispatcher / ctx.flowNodeId) refuses fail-closed (the second safeguard).
      // `note` is not a parameter -- the timeline is auto-appended by the engine when Nebula
      // Mails that task's dispatcher.
      "Task" -> TaskToolDef,
      // TaskInfo (taskunify batch 2026-09-24): the project domain's **read-only** item --
      // zero parameters, showing "the one task your session is attached to" + the note
      // timeline. Authorization face = mounted by the session-identity mechanism (the
      // dispatcher's ninth DispatcherFixedTools item + appended by identity at the end of
      // buildAllowedToolSet for project nodes / dispatcher sessions); a plugin declaration
      // grants nothing (it shares NebulaExclusiveTools' escape-prevention channel).
      // Attribution is resolved from the session identity (ctx.taskId); no attribution ⇒ a
      // fail-closed refusal (TASKINFO_NO_ATTACHMENT, with no fallback to the whole board).
      "TaskInfo" -> TaskInfoToolDef,
      // 🔴 `TaskList` / `TaskBoard` are **not registered** (retired and removed): the
      // registry has no such name ⇒ `case None` in AgentCore.executeToolInner hits
      // AgentCore.RetiredToolGuides and yields the retirement guide (**error text only, zero
      // execution surface**). Registering the old names as aliases would mean the retirement
      // guide never fires AND a second path to the write face would exist -- hence the
      // **deliberate absence** here.
      // TaskSearch（task-rag 批 #1227 / §Batch 7）：任务语义检索（Nebula 面，
      // 只读）——embedding 余弦 top-k + 词法降级 + 可选 Jev 重排。授权面 =
      // RootOrchestrationTools 单点 + RootExclusiveTools 防声明逃逸；插件白名单
      // 与 remoteableTools 均不含本件。
      "TaskSearch" -> TaskSearchTool,
      // Nebula-root-only on-demand history lookup; no plugin or remoteable authorization.
      "ConversationSearch" -> ConversationSearchTool,
      // Ask-only tools are stripped from other roles, including wildcard definitions.
      // Shared lookup tools and Bash are registered above and are not Ask-exclusive.
      "message_record_search" -> MessageRecordSearchTool,
      "send_message_to_main_chat" -> SendMessageToMainChatTool,
      // canvas-ask-restricted-edit（CAE-REQ-001）：受限编辑两件套——提案制
      // （零直写，前端 diff 卡 Accept/Reject）+ 仅绑定文件路径。授权面同上
      // AskExclusiveTools（非 ask 角色剥除；工具内侧 isAskSession 双保险）。
      "ask_edit" -> AskEditTool,
      "ask_write" -> AskWriteTool,
      // PDF note/ask 批（2026-11-18）：ask 会话绑定 PDF 的按需取页件（只读解析
      // 产物，不整篇载入；答案引用页码的取数面）。授权面同 ask_edit/ask_write
      // （AskExclusiveTools 剥除非 ask 角色；工具内侧 isAskSession 双保险）。
      "PdfPage" -> PdfPageTool
    )
    // imagegen 双层批（2026-10-24）+ 功能开放批（2026-10-30 作者令「直接开放
    // 出来」，缺省编入）：编译期 seal 门控保留为应急开关——显式 `STAND_IMAGEGEN=0`
    // 构建（ImageGenSeal.CompiledIn=false，final val 常量折叠）本注册整段消失；
    // 缺省构建在场。seal 断言锚见 ImageGenSpec。授权面 = Nebula 专属
    // （RootOrchestrationTools 单点 + RootExclusiveTools 防声明逃逸 + 工具内
    // isRootAgent 身份闸）。非阻塞 job 语义见 GenerateImageTool。
    val experimental: Map[String, Tool] = imageGenTools(
      nebflow.core.ImageGenSeal.CompiledIn,
      nebflow.service.ConfigService.imagegenEnabledForToolFace
    )
    tools.putAll((builtins ++ experimental).asJava)
  }

  private[nebflow] def imageGenTools(compiledIn: Boolean, panelEnabled: Boolean): Map[String, Tool] =
    if compiledIn && panelEnabled then Map("GenerateImage" -> GenerateImageTool) else Map.empty

  def TOOL_MAP: Map[String, Tool] = tools.asScala.toMap

  /**
   * 动态注册名快照（阶段 2b：plugin MCP allowedSet 追加源；轻量——不经
   * augmentSchema，纯键名）。
   */
  def registeredToolNames: List[String] = tools.keys.asScala.toList

  def ALL_TOOLS: List[ToolDefinition] = tools.asScala.values.map { t =>
    val schema = RemoteExecutor.augmentSchema(t.name, t.inputSchema)
    ToolDefinition(t.name, t.description, schema)
  }.toList

  /** Builtin tool names (non-MCP), used by frontend to build configurable tool list. */
  def builtinToolNames: List[String] =
    tools.asScala.keys.filterNot(_.startsWith("mcp__")).toList.sorted

  /**
   * 面判定（P0-1 / P-M1，2026-09-20）：该注册名对应的实例是否为**外部工具**
   * （ScriptTool，`~/.nebflow/tools/<name>/tool.json` 装载）。
   *
   * WHY 走注册表身份而不是名字模式：`ScriptTool.name = config.name`（现读
   * `ScriptTool.scala:16`）是**自由文本**，全仓无「外部工具名前缀/命名空间」——
   * 名字模式核不到（spec §6 #8 本批现读结论）。注册表身份是唯一可判据的面。
   *
   * 只读、不改变任何注册行为（`registerTool` / `unregisterTool` 逐字不动）。
   */
  def isExternalTool(name: String): Boolean =
    tools.get(name) match
      case t: ScriptTool => true
      case _ => false

  def registerTool(tool: Tool): Unit =
    tools.put(tool.name, tool)

  def registerTools(newTools: List[Tool]): Unit =
    newTools.foreach(t => tools.put(t.name, t))

  /** Unregister a single tool by name. */
  def unregisterTool(name: String): Unit =
    tools.remove(name)

  /** Unregister all tools whose name starts with the given prefix (e.g. "mcp__zai__"). */
  def unregisterToolsByPrefix(prefix: String): Unit =
    tools.keySet.removeIf(_.startsWith(prefix))
end ToolRegistry
