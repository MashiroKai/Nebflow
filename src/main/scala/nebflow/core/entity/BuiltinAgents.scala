package nebflow.core.entity

import nebflow.shared.{Config, PathUtil}
import nebflow.prompts.PromptCatalog

/**
 * BuiltinAgents —— 四个收敛 agent 的**代码级唯一定义源**（builtin-def batch
 * 2026-10-03 作者令①：「全部的四个 agent：Nebula、dispatcher、general、kernel，
 * 都走代码硬编码，不扫盘，唯一标准源就是代码，插件面板只能做只读查看」）。
 *
 * 本对象取代旧的「磁盘定义 + 种子镜像」三层来源：
 *  - 定义面（name / description / system prompt / identity / category）= 本文件
 *    内嵌字符串常量，磁盘上的 `agents/<name>/{agent.json,system.md}` 对这四个名
 *    是**死信**（读侧单点跳过，见 [[EntityLoader.loadAgent]] /
 *    [[nebflow.agent.AgentLibrary.loadAll]]；存量文件零删除、零读取）。
 *  - `seed/agents/` 资源树与 SeedService 的 agent 播种 / reconcile 腿随本批退役
 *    ——代码即权威，种子镜像的第二份拷贝是漂移源，不留。
 *  - **模型链引用是用户设置，不是定义**：`agent.json` 对这四个名收缩为纯
 *    model-chain sidecar（`model` 键，可写面 = `PUT /api/agents/:name/model`，
 *    core.SchemePolicy 单点），本对象的条目构造**每次现读**该 sidecar（def 而
 *    非 val），per-turn 重载才能拿到 `/model` 面板的新链。
 *  - 面板只读：`Names.contains(name)` 是 REST/WS 写通道的统一拒绝判据，
 *    `/api/agents` 与 `GET /api/agents/:name` 响应带 `builtin: true` 标记。
 *
 * 工具面不在本对象——[[nebflow.agent.AgentCore.fixedToolsFor]] 按名派发静态集
 * 的机制不变（AgentEntry.tools 恒 Nil = 非权威面，同 AgentLibrary.Seeds 先例）。
 */
object BuiltinAgents:

  /**
   * 代码定义名的单点（AgentCore.ConvergedAgentNames 引用本集）。
   *
   * builtin-merge 批（2026-10-03）：kernel + general 合并为执行 agent
   * `nebflow`（kernel 基础工具 + Subagent + Workflow 三面）；+`subagent`
   * （只读侦察子面，SubagentTool 的 spawn 目标，Explore 同型）。`general`
   * 与 `kernel` 退役（磁盘死信名沿用既有读侧跳过）。`project-dispatcher`
   * 过渡保留——随 flow 引擎退役批一并删除。
   */
  val Names: Set[String] = Set("Nebula", "project-dispatcher", "nebflow", "subagent")

  /**
   * 面板隐藏名（2026-10-07 14:13 作者令②「任务分发器面板隐藏」）——枚举链
   * **第一过滤点**。🔴 本集是**可见性**维度，不是生命维度：集内 agent 的本体
   * 过渡保留零触碰（仍在 [[Names]]——entry/entries 照常解析、isBuiltin 写通道
   * 判据零变、注册/旧轨/生命周期全不动），藏的是面板枚举不是生命：
   * `GET /api/agents`（[[nebflow.gateway.RegistryRoutes]] 面板枚举，第二过滤点）
   * 与其前端消费面（web/js/plugins.js agents 列表，第三过滤点）按本集过滤。
   * `project-dispatcher` 物理删除仍候 #1229 / S19③ 收口闸，本集只是过渡期的
   * 显示面挡板——随该闸一并删除。
   */
  val PanelHiddenNames: Set[String] = Set("project-dispatcher")

  /**
   * 面板枚举可见性判据（枚举链单一判据面：REST 与前端共用本语义）。
   * 🔴 只用于**枚举/列表**面——单取（GET /api/agents/:name）、解析、派发、
   * Mail 等生命面一律不走本判据。
   */
  def panelVisible(name: String): Boolean = !PanelHiddenNames.contains(name)

  /** 合并后的执行 agent 定义名（Delegate 的 spawn 目标；builtin-merge 批起取代 kernel）。 */
  val ExecutorName: String = "nebflow"

  /** 只读侦察子 agent 名（SubagentTool 的 spawn 目标）。 */
  val SubagentName: String = "subagent"

  /**
   * 退役名解析表（P0-1 旧数据兼容批 2026-10-03）：`general` / `kernel` 在
   * builtin-merge 批（2026-10-03）合并为执行 agent [[ExecutorName]]，但**存量
   * 数据仍带这两个名字**——节点 JSON 的 `agent` / `verify` 字段、旧会话元数据、
   * 旧 model sidecar 目录。全新 home 上这些名字在磁盘上不存在 ⇒ 直接
   * `loadAgentFromDir` 落空 ⇒ 旧项目节点无法 spawn（审计 §三 的 P0-1 下半面）。
   *
   * 本表是**读侧解析兜底的单点**：名字仍可被解析，语义落到合并后的执行 agent。
   * 新写入面（NodeEditTool / ProjectTypes 缺省）一律直接写 [[ExecutorName]]，
   * 不再产出这些名字。
   */
  val RetiredNames: Map[String, String] = Map(
    "general" -> ExecutorName,
    "kernel" -> ExecutorName
  )

  /** 退役名 ⇒ 现行内置名；非退役名 ⇒ None（自定义名不受影响，仍走磁盘解析）。 */
  def resolveRetired(name: String): Option[String] = RetiredNames.get(name)

  def isBuiltin(name: String): Boolean = Names.contains(name)

  /**
   * 用户为根 agent 起的显示名（personal-agent 批 2026-10-04，方案 §2.3）。
   *
   * 读取 `agents/<root>/agent.json` 的 `displayName`；**缺省回落机制名**
   * （`RootAgentIdentity.Name`）——无名字用户零回归。
   *
   * 这是**显示面**的读取单点：UI 渲染与提示词自报家门都走它。
   * 🔴 **身份键恒为 `"Nebula"`**，本对象不参与任何判据（白名单见任务书 §2 C）。
   *
   * 独立于 [[sidecarRefs]] 的第二个读点（那里返回 preset/model），但同文件同宽容
   * 策略（坏 JSON / 缺文件 ⇒ 回落机制名）。
   */
  def displayNameForRoot: String =
    try
      val p = PathUtil.dataRoot / "agents" / nebflow.actor.RootAgentIdentity.Name / "agent.json"
      if !os.exists(p) then nebflow.actor.RootAgentIdentity.Name
      else
        io.circe.parser
          .parse(os.read(p))
          .toOption
          .flatMap(_.hcursor.downField("displayName").as[Option[String]].toOption.flatten)
          .map(_.trim)
          .filter(_.nonEmpty)
          .getOrElse(nebflow.actor.RootAgentIdentity.Name)
    catch case _: Throwable => nebflow.actor.RootAgentIdentity.Name

  /**
   * 读 model-chain sidecar 的 `preset` / `model` 引用（缺文件 / 坏 JSON ⇒ 空引用，
   * 与 core.SchemePolicy.ownChainOf 同款宽容；fresh home 上该文件可以根本不存在）。
   */
  private def sidecarRefs(name: String): (Option[String], Option[nebflow.shared.AgentModelConfig]) =
    try
      val p = PathUtil.dataRoot / "agents" / name / "agent.json"
      if !os.exists(p) then (None, None)
      else
        io.circe.parser.parse(os.read(p)).toOption match
          case None => (None, None)
          case Some(json) =>
            val preset = json.hcursor.downField("preset").as[Option[String]].toOption.flatten
            val model = json.hcursor.downField("model").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
            (preset, model)
    catch case _: Throwable => (None, None)

  /** 单个内置条目（**每次调用现读 sidecar**——per-turn 模型链跟随的依据）。 */
  def entry(name: String): Option[AgentEntry] =
    if !Names.contains(name) then None
    else
      val (preset, model) = sidecarRefs(name)
      val (description, prompt, displayName, skills) = name match
        // 🔴 root entry's displayName = the user's chosen name (falling back to
        // the mechanism name). Display face only — `name` (the judgement key)
        // stays "Nebula" and the whole backend addresses the agent by it.
        case "Nebula" => (NebulaDescription, NebulaPrompt, Some(displayNameForRoot), List("*"))
        case "project-dispatcher" => (DispatcherDescription, DispatcherPrompt, None, Nil)
        case "nebflow" => (NebflowDescription, NebflowNodePrompt, Some(nebflow.shared.Branding.productName), Nil)
        case "subagent" => (SubagentDescription, SubagentPrompt, Some("Subagent"), Nil)
        case _ => ("", "", None, Nil)
      Some(
        AgentEntry(
          name = name,
          description = description,
          useWhen = "",
          tools = Nil, // 非权威面——fixedToolsFor 按名派发静态集
          systemPrompt = prompt,
          category = "standalone",
          model = model,
          preset = preset,
          skills = skills,
          displayName = displayName // display face（Nebula = Some(displayNameForRoot)，用户起的名）——判定键仍是 name
        )
      )

  /** 四件全集（每件现读 sidecar）。 */
  def entries(): Map[String, AgentEntry] =
    Names.flatMap(n => entry(n)).map(e => e.name -> e).toMap

  /**
   * delegate 面解析路径（提示词来源拆分批 2026-10-08）：**与 [[entry]] 并列的第二
   * 条读路**——delegate spawn 的唯二读点（`DelegateTool.spawnKernel` 新鲜派发 +
   * `MailTool` 续聊复活腿）经本函数取条目，`nebflow` 名落 [[NebflowDelegatePrompt]]
   * （delegate_report 纪律；模型可见面零 node_report）。非 `nebflow` 名无 delegate
   * 面变体，逐字回落 [[entry]]（今日 delegate spawn 只落 `ExecutorName` 一名，本
   * 回落是机械完备性而非第二行为面）。节点 spawn（`EntityLoader.loadAgent`）**不
   * 经本函数**——节点面恒走 [[entry]]，两来源机械可分。
   */
  def delegateEntry(name: String): Option[AgentEntry] =
    entry(name).map(e => if name == ExecutorName then e.copy(systemPrompt = NebflowDelegatePrompt) else e)

  /**
   * 面板提示词双面投影（prompt-panel 面板批 2026-10-09）：builtin 名在**面板展示
   * 面**的提示词页签清单——(faceId, systemPrompt) 对。仅 [[ExecutorName]] 有两面
   * （delegate 面在前 = 默认页签，与列表行/详情头的 Delegate 目标描述同面；node
   * 面在后），且两面确异才产出（常量同文时退化为空 = 面板维持单 prompt 展示）；
   * 其余名（含全部自定义名）恒 Nil。🔴 纯只读投影面：判据/生命面零触碰。REST
   * 单取（GET /api/agents/:name，[[nebflow.gateway.PresenceRoutes]]）按本函数
   * 下发 `promptFaces`，前端按 faceId 配 locale 标注——面板不再「借 node 面正文
   * 配 Delegate 目标标注」（2026-10-09 用户报告的面板错配即此）。
   */
  def panelPromptFaces(name: String): List[(String, String)] =
    if name != ExecutorName then Nil
    else
      // 引擎加固批 2026-10-09 顺手收口（上批被重启打断的半成品：yield List 产出
      // List[List[(String,String)]] 与返回类型不合，编译红）——按原意 flatten：
      // 两面确异 = [("delegate", …), ("node", …)]，同文 = Nil。语义零改写。
      (for
        node <- entry(name).toList
        delegate <- delegateEntry(name).toList
        if node.systemPrompt != delegate.systemPrompt
      yield List("delegate" -> delegate.systemPrompt, "node" -> node.systemPrompt)).flatten

  // ============================================================
  // 定义文案（内嵌常量；本文件即唯一权威）
  // ============================================================

  private val NebulaDescription =
    "Orchestrator — reads the user's intent, dispatches work to projects, supervises execution, reports synthesized results; does not execute project work itself"

  private val DispatcherDescription =
    "项目任务分发器——负责把一批工作拆成若干可执行的节点、安排它们之间的先后与依赖，并在节点完成后汇总结果。"

  private val NebflowDescription =
    "Built-in executor agent (the Delegate target): kernel base tools + Subagent " +
      "(read-only recon) + Workflow (DAG of subagent steps); no project context, no memory. " +
      "Definition is mechanism-fixed in code."

  private val SubagentDescription =
    "Read-only recon sub-agent (the Subagent tool's target): Read/Glob/Grep/WebSearch/WebFetch, " +
      "blocking one-shot runs; the final text IS the answer. Definition is mechanism-fixed in code."

  /**
   * Nebula system prompt —— 原 seed/agents/Nebula/system.md 全文随本批上收进代码；
   * builtin-def 批仅改写 kernel 路由行（作者令②：Delegate 直接触发 kernel，
   * Mail 降级为通信——`kernel:<id>` 续聊腿保留，起实例腿退役）。
   *
   * **自报家门参数化**（personal-agent 批 2026-10-04，方案 §2.3）：首句的显示名取
   * [[displayNameForRoot]]（缺省回落机制名）。🔴 **只参数化这一处显示名**：
   * 正文里的 `"Nebula"` 是**机制地址**（Mail 拒收自身地址所用的判据键），
   * 属于身份键白名单，**恒不替换**（以参数化之名改它会让 Mail 地址判据漂移）。
   * 模板以 `{{agent_display_name}}` 占位，渲染在 [[NebulaPrompt]] 内完成。
   */
  private val NebulaPromptTemplate: String = PromptCatalog.Roles.NebulaPromptTemplate

  /** 渲染后的 Nebula 提示词（自报家门 = 用户起的显示名）。 */
  private def NebulaPrompt: String =
    NebulaPromptTemplate.replace("{{agent_display_name}}", displayNameForRoot)

  private val DispatcherPromptTemplate: String = PromptCatalog.Roles.DispatcherPromptTemplate

  /**
   * 协作同步句（batch78 三裁②，BA:330 移出＋参数化）：`collabSync.remoteUrl`
   * 配置存在时渲染同步句——句内 remote 名取配置键名段 `collabSync`，非硬编码
   * dev；缺省 None＝整句不渲染（模板占位 {{collab_sync_line}} 替换为空串，
   * 前后句直接衔接，「The single exception…」句语法自洽）。渲染时现读配置
   * （def 而非 val）＋fail-safe：配置读失败等同未配置（DelegateJudgeMachine
   * 同款 Try 包裹）。
   */
  private def collabSyncLine: Option[String] =
    scala.util.Try(Config.loadServiceConfig().collabSync.flatMap(_.remoteUrl)).toOption.flatten.map { url =>
      s" The collaboration-sync remote (`$url`) is the sync face: every landing pushes it automatically (`git push collabSync main`, fast-forward, executed right after the merge-window release step) so that its main always equals `main`."
    }

  /**
   * 渲染后的 dispatcher 提示词（协作同步句经 {{collab_sync_line}} 占位注入；
   * 先例 = NebulaPromptTemplate / {{agent_display_name}}）。
   */
  private def DispatcherPrompt: String =
    DispatcherPromptTemplate.replace("{{collab_sync_line}}", collabSyncLine.getOrElse(""))

  /**
   * 提示词来源拆分批（2026-10-08 作者令）：`nebflow` 执行名的系统提示词按会话面
   * 拆成**两个独立来源**，禁止一处模板双面引用——
   *  - **flow 节点面** = [[NebflowNodePrompt]]：节点语义独立成稿——接收上游/分发
   *    输入（任务文本 = 全部世界）、产出交付（末条输出 = 交付物，引擎沿 out 边
   *    投递）、沿 out 边交接（无消息工具、无 root 地址）、受监督约束（node_report
   *    申报义务、未申报不终态、role=verifier 值域分化 pass/fail）。解析路径 =
   *    [[entry]]（EntityLoader.loadAgent → NodeStarter 节点 spawn）。
   *    （prompt-panel 面板批 2026-10-09 更正：本行原写「面板只读显示同路」——
   *    拆分后面板若仍沿 [[entry]] 单面显示，就是「Delegate 目标标注配 node 面
   *    正文」的错配源头；面板只读展示现经 [[panelPromptFaces]] 双面投影，
   *    delegate 面默认页签 + node 面第二页签，标注与正文逐面对齐。）
   *  - **delegate 面** = [[NebflowDelegatePrompt]]：delegate 语义独立成稿——
   *    executor/dispatcher/manager 三角色、分工面、delegate_report 三态申报纪律
   *    （finish/merge/blocked + MergeQueue 合并轮）；**模型可见面零 node_report
   *    指令**。解析路径 = [[delegateEntry]]（DelegateTool.spawnKernel 新鲜派发 +
   *    MailTool 续聊复活腿——delegate spawn 的唯二读点）。
   * 二者是两个常量 + 两条解析路径，机械可分；判据门 =
   * `nebflow.core.seed.NodeSeedOutputContractSpec`（节点面）与
   * `nebflow.core.entity.PromptFaceSplitSpec`（双面分离 + delegate 面值域）。
   *
   * 历史形态（本批拆分前）：单一 `NebflowPrompt` 共用模板 = 开头 node 面 + 中段
   * delegate 面，delegate 会话经 `entry("nebflow")` 拿到以 node_report 开场的系统
   * 提示词（「应该用 delegate_report，实际用的却是 node_report」即指此缺陷）。
   *
   * P0-1 批（2026-10-03）**节点交付契约回填**（史实）：现行契约＝单轨五要素体例：
   * 首内容行即节点身份句，末条输出＝自身交付物，文末五要素 Report 段在位；
   * node_report 申报义务与未申报不终态语义在位（dual-track 双轨体例曾为该契约
   * 载体，2026-10-07 作者令「deliver in two part 去掉」随 N3 修订批退役）。
   * 判据门 = `nebflow.core.seed.NodeSeedOutputContractSpec`。
   */
  private val NebflowNodePrompt: String = PromptCatalog.Roles.NebflowNodePrompt

  /**
   * delegate 面系统提示词（来源拆分批 2026-10-08，见 [[NebflowNodePrompt]] 头注）：
   * **独立成稿**，只承载 delegate 语义——三角色（executor/dispatcher/manager）、
   * 分工面、delegate_report 三态申报（finish/merge/blocked + MergeQueue 合并轮）、
   * delegate 实际工具面（BaseTools + Subagent/AgentFlow/ChainWrite + Node 三件
   * 作用域面 + Mail + delegate_report；🔴 无 node_report、无 TaskBoard、无 Delegate
   * 派发件——那三件不在此会话身份的工具面上）。**模型可见面零 node_report 指令**
   * （判据门 = `PromptFaceSplitSpec` 的零命中钉）。
   */
  private val NebflowDelegatePrompt: String = PromptCatalog.Roles.NebflowDelegatePrompt
// 🔴 上两串常量 = 提示词来源拆分批（2026-10-08 作者令）的两个独立来源——
// 节点面（NebflowNodePrompt）/ delegate 面（NebflowDelegatePrompt），一模板双面
// 引用的旧形态（单一 NebflowPrompt）已拆。delegate 面模型可见文本零 node_report。
// 「Merge discipline (hard)」行 = worktree-judge-fifo 批 #1224 增补 §3.3 改写
// （R7 原拟「不得自行安排合并」→「未收合并 reminder 不得自行合并；收到即自执行」），
// 本批随 delegate 面逐字迁入；§16 待过目（模型可见文案，成稿归 N3 提示词批）。
// 注记用 Scala 注释而非 HTML 注释：种子契约（NodeSeedOutputContractSpec）要求
// 代码级 prompt 零 HTML 注释。

  /** 只读侦察子 agent 提示词（SubagentTool 的 spawn 目标；Explore 同型）。 */
  private val SubagentPrompt: String = PromptCatalog.Roles.SubagentPrompt

end BuiltinAgents
