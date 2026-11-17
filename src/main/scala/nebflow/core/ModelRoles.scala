package nebflow.core

import nebflow.shared.AgentModelConfig

/**
 * 模型配置五类粒度（模型粒度批，作者令 2026-11-06 20:36 逐字「按主agent/ kernel /
 * dispatcher/node/subagent 这几类分」）——**会话类别 → 模型槽**的单一真源。
 *
 * == 数据面 ==
 * 五个槽全部落既有 agent sidecar 机制（`<dataRoot>/agents/<slot>/agent.json` 的
 * `model` 键，[[nebflow.core.SchemePolicy.ownChainOf]] 现读）：
 *
 *   | 类（wire） | 槽目录 | 面           |
 *   |-----------|--------|--------------|
 *   | `main`   | `agents/Nebula/`            | 主 agent（作者本人对话框）|
 *   | `kernel` | `agents/kernel/`（虚拟槽）   | kernel 会话 |
 *   | `dispatcher` | `agents/project-dispatcher/` | project dispatcher |
 *   | `node`   | `agents/node/`（虚拟槽）     | flow / dag 节点会话 |
 *   | `subagent` | `agents/subagent/`（虚拟槽）| delegate 子代理会话 |
 *
 * 「虚拟槽」= 只有 model sidecar、不是完整 agent 定义：kernel/node/subagent 会话
 * 的运行身份仍是内置执行 agent（提示词/工具面零变化），只有 **模型链** 按槽取。
 *
 * == 切换语义（两档）==
 *   - **类粒度**：/model 面板或设置里对某槽写入（REST `PUT /api/agents/<slot>/model`，
 *     写闸 [[nebflow.core.SchemePolicy.SettableAgents]]）⇒ 下一 turn 起**该类全部
 *     会话**一起换（本件的 effectiveModel 每 turn 现读，零传播步骤）。
 *   - **单粒度**：点进具体对话框切（ws `setSessionModel` ⇒ 会话级 override，
 *     `LlmInterface` 单点生效）——只变该会话。既有实现零改动。
 *
 * == 优先级（请求面单点 = AgentSessionExecution 的 LlmRequest 构造）==
 *   1. 会话级 override（setSessionModel，LlmInterface 内既有单点）；
 *   2. **类别槽链**（本件，槽非空时）——压过派发钉链：类切换要「同类会话都变」，
 *      快照语义必须让位（切类是显式用户动作，缓存键换血可接受）；
 *   3. 派发钉链（provider-concurrency 批快照，槽为空时保缓存键稳定）；
 *   4. agent 自身 sidecar 链（freshDef.model 兜底 = 既有行为）。
 *
 * == 分类判据（会话 id 前缀；全部取自生产 id 生成点，逐条带锚）==
 *   - `delegate-kernel-` ⇒ kernel（**必须先于 `delegate-` 判**：kernel 会话 id 形状
 *     为 `delegate-kernel-<8hex>`，见 `nebflow.core.delegate.MailAddress.KernelSessionPrefix`、
 *     `AgentActor.isKernelSession` 的同款前缀判据——顺序颠倒会把 kernel 会话错归 subagent）；
 *   - `delegate-` ⇒ subagent（`DelegateTool.spawnDelegate` 的 id 形状，
 *     `DelegateJudge.Prefix`）；
 *   - `dispatcher-` ⇒ dispatcher（`ProjectActor.DispatcherSessionPrefix`，
 *     分发器会话 id = `dispatcher-<uuid8>`）；
 *   - `dag-` ⇒ node（`FlowDagExecutor` 节点会话 id 形状）；
 *   - `node-` ⇒ node（项目节点会话族，`SessionScope.BackgroundAgentPrefixes` 成员）；
 *   - 未命中 ⇒ None ⇒ 不覆写（回落 freshDef.model = 既有行为零变化）。
 */
object ModelRoles:

  /** 五类的 wire 名（/model 面板、设置面板、REST 路径共用同一拼写）。 */
  val MainWire = "main"
  val KernelWire = "kernel"
  val DispatcherWire = "dispatcher"
  val NodeWire = "node"
  val SubagentWire = "subagent"
  val SmallLlmWire = "small-llm"

  /**
   * (wire, 中文标签, sidecar 槽名)。槽名 = agents/ 目录名（wire 与槽名解耦：main 的槽是 Nebula）。
   *
   * 🔴 `lazy val` 是**初始化顺序纪律**（模型粒度批实证 2026-11-07）：本表要读
   * `SchemePolicy.NebulaName` / `DispatcherName`，而 `SchemePolicy.SettableAgents`
   * 又要读本表 ⇒ 两侧都急切求值即成环（`SchemePolicy.<clinit>` 期间反向触达本表的
   * `<clinit>`，读到的 `Slots` 还是 null ⇒ NPE ⇒ `ExceptionInInitializerError`，
   * 进程内 SchemePolicy/ModelRoles 双双判废）。两侧同时 `lazy val`（另见
   * `SchemePolicy.SettableAgents`）⇒ 任一侧先初始化都不在 `<clinit>` 期间反向触达
   * 对方，槽名单点真源（不复制 "Nebula"/"project-dispatcher" 字面量）得以保留。
   * 判据钉住：`ModelRolesSpec` 断言本表槽名与 `SchemePolicy.NebulaName` /
   * `DispatcherName` 逐字相等。
   */
  lazy val Slots: List[(String, String, String)] = List(
    (MainWire, "主agent", nebflow.core.SchemePolicy.NebulaName),
    (KernelWire, "kernel", KernelWire),
    (DispatcherWire, "dispatcher", nebflow.core.SchemePolicy.DispatcherName),
    (NodeWire, "node", NodeWire),
    (SubagentWire, "subagent", SubagentWire),
    (SmallLlmWire, "通用小 LLM", SmallLlmWire)
  )

  /** 该名字是否为可配置的模型槽（REST GET/PUT /agents/:name/model 的合法面）。 */
  def isSlot(name: String): Boolean = Slots.exists(_._1 == name) || Slots.exists(_._3 == name)

  /**
   * wire 名 → 槽目录名的规范形（非槽名原样返回）。
   *
   * 🔴 存在的理由：REST 面两种拼写都合法（[[isSlot]]），但只有**槽目录名**对应
   * `agents/<目录>/agent.json`——`main` / `dispatcher` 在盘上没有目录，若写入面
   * 不归一到规范形，`PUT /agents/main/model` 会就地建出 `agents/main/agent.json`，
   * 而引擎只读 `agents/Nebula/`（[[slotOfSessionId]] 返回槽目录名）⇒ **静默空写**。
   * 两个 REST 臂（GET/PUT `/agents/:name/model`）与 [[isSettable]] 判据一律经本函数
   * 归一后使用；判据钉住：ModelChainRoutesSpec「wire 拼写归一」用例。
   */
  def canonicalSlotName(name: String): String =
    Slots.find(_._1 == name).map(_._3).getOrElse(name)

  /**
   * 该名字是否为**虚拟**槽（只有 model sidecar、没有 agent 实体——EntityLoader 存在性
   * 闸对它必须跳过）：kernel / node / subagent 三个。
   */
  val VirtualSlots: Set[String] = Set(KernelWire, NodeWire, SubagentWire, SmallLlmWire)

  def isVirtualSlot(name: String): Boolean = VirtualSlots.contains(name)

  /**
   * 会话 id → 类别槽（**sidecar 槽名**，即 `agents/<槽>/agent.json` 的目录名；未命中
   * = None ⇒ 不覆写）。
   *
   * 前缀表与判据顺序见文件头注「分类判据」——`delegate-kernel-` 必须压过
   * `delegate-`（kernel 会话是 delegate 族里的独立类）。
   *
   * 🔴 返回值是**槽目录名**不是 wire 名：`dispatcher-…` 会话 ⇒ `project-dispatcher`
   * （wire 名 "dispatcher" 没有对应目录，直接喂给 `ownChainOf` 会读到不存在的
   * `agents/dispatcher/agent.json` ⇒ 类切换静默失效；ModelChainRoutesSpec 五槽用例
   * 首轮实证）。kernel/node/subagent 三类 wire 名与槽名同形，映射表仍是唯一真源。
   */
  def slotOfSessionId(sessionId: Option[String]): Option[String] =
    val wire = sessionId
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { id =>
        val lower = id.toLowerCase
        if lower.startsWith("delegate-kernel-") then KernelWire
        else if lower.startsWith("delegate-") then SubagentWire
        else if lower.startsWith("dispatcher-") then DispatcherWire
        else if lower.startsWith("dag-") then NodeWire
        else if lower.startsWith("node-") then NodeWire
        // Ask 会话是主会话的独立 UI/历史身份，不是一个独立模型类别；
        // 它应继承 main/Nebula 模型槽。若遗漏此路由，ask- 会落到 None，
        // 下游只能使用会话钉链/AgentDef 空链，迁移后的主链配置不可达。
        else if lower.startsWith(nebflow.core.ask.AskSessions.Prefix) then MainWire
        else if lower.startsWith("kernel-") then KernelWire
        else ""
      }
      .filter(_.nonEmpty)
    wire.flatMap(w => Slots.find(_._1 == w).map(_._3))
  end slotOfSessionId

  /**
   * Slot whose chain edit governs a session's explicit model pin; `None` means the pin
   * cannot be attributed to a category, so no category edit may clear it.
   *
   * The five request-time families come from [[slotOfSessionId]]. An ordinary main
   * conversation (any id outside the background families, see
   * [[nebflow.shared.SessionScope.isMainSession]]) belongs to the main slot: the Nebula
   * chain is its base chain, even though [[slotOfSessionId]] leaves it unclassified on
   * purpose for request resolution. Background families without a slot (`subtask-`,
   * `subagent-`, `workflow-`, `external-`) and blank ids stay `None`.
   */
  def pinOwnerSlot(sessionId: String): Option[String] =
    val id = sessionId.trim
    slotOfSessionId(Some(id)).orElse(
      Option.when(nebflow.shared.SessionScope.isMainSession(id))(SchemePolicy.NebulaName)
    )

  /** 类别槽自身的链（槽 sidecar 现读；空/未配置 = None ⇒ 回落）。 */
  def ownChainOfSlot(slot: String): Option[AgentModelConfig] =
    SchemePolicy.ownChainOf(slot).filter(SchemePolicy.hasChain)

  /**
   * 请求面单点（每 turn 现读）：该会话命中五类槽且**槽已配置** ⇒ 槽链；否则 None
   * （**不**回落 base——槽未配置时由调用方继续走钉链/agent 自身链的既有优先级）。
   * 🔴 组合纪律：调用点必须 `slotChainOf(sid).orElse(钉链面)` ——钉链在 base 之前
   * （provider-concurrency 批的缓存键稳定面）；本函数只回答「槽链有没有」。
   */
  def slotChainOf(sessionId: Option[String]): Option[AgentModelConfig] =
    slotOfSessionId(sessionId).flatMap { slot =>
      ownChainOfSlot(slot).orElse {
        // Ask sessions have an ephemeral AgentDef with no model/pin. When the
        // main sidecar is absent/empty, resolve through the same configured
        // main fallback used by SchemePolicy rather than returning no chain.
        Option.when(nebflow.core.ask.AskSessions.isAskSession(sessionId))(
          SchemePolicy.resolveModel(SchemePolicy.NebulaName, None)._1
        )
      }
    }

  /**
   * 组合面（语义同 `slotChainOf(sid).orElse(base)`）：槽已配置 ⇒ 槽链（类粒度切换
   * 生效点）；否则原样透传 base。**注意**：带钉链的调用点（AgentSessionExecution）
   * 用 [[slotChainOf]] 自行组合（槽 > 钉链 > base），不走本函数——本函数服务无钉
   * 链的读面。
   */
  def effectiveModel(
    base: Option[AgentModelConfig],
    sessionId: Option[String]
  ): Option[AgentModelConfig] =
    slotChainOf(sessionId).orElse(base)

end ModelRoles
