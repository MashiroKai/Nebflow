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
 * == 分类判据（前缀 + delegate 记录 mode；判据来源逐条带锚）==
 *
 * 🔴 归槽批（2026-11-18 · 作者令「delegate会话这个划分的太粗了啊 我们是分了 kernel
 * node dispatcher和subagent的 怎么能都分给subagent呢」）：`delegate-` 前缀会话
 * **不再一律归 subagent**。生产实证（`<dataRoot>/delegates/` 1013 条记录，
 * 2026-11-18 现读）：`mode=kernel` 808 条、`mode=dispatcher` 96 条，**全部**带着
 * `delegate-nebflow-<8hex>` 这一条 id 形状——`DelegateTool.spawnBackground` 的
 * `subagentId` 生成点（DelegateTool.scala:1138）**与 mode 无关**；而 `delegate-kernel-`
 * 形状在 `delegates/` 盘上**零条**。按前缀归槽 ⇒ 808+96 条 kernel/dispatcher 身份
 * 全部落到 subagent 槽 ⇒ 槽链压过钉链/主链（作者看到的「配置 cmdcode 实际跑 terra」
 * 的成因）。故 `delegate-` 的归槽判据改吃**记录的 mode**（`DelegateRegistry.Record.mode`，
 * spawn 时落盘、终身属性）= 归槽真相来源；前缀只保留 kernel 实例这一条结构性命中。
 *
 *   - `delegate-kernel-` ⇒ kernel（**必须先于 `delegate-` 判**：kernel 实例会话 id
 *     形状，见 `nebflow.core.delegate.MailAddress.KernelSessionPrefix`、
 *     `AgentActor.isKernelSession` 的同款前缀判据——顺序颠倒会把 kernel 实例错归
 *     其它类）；
 *   - `delegate-<agent>-<8hex>` ⇒ **该记录 mode 对应的槽**（[[DelegateModeWires]]：
 *     kernel ⇒ kernel / dispatcher ⇒ project-dispatcher / node ⇒ node /
 *     subagent ⇒ subagent）；mode **缺席或不识别** ⇒ 未归槽（None）+ 可读原因
 *     （[[delegateSlotUnresolvedReason]]）——**绝不静默塞进 subagent**；
 *   - `dispatcher-` ⇒ dispatcher（`ProjectActor.DispatcherSessionPrefix`，
 *     分发器会话 id = `dispatcher-<uuid8>`）；
 *   - `dag-` ⇒ node（`FlowDagExecutor` 节点会话 id 形状）；
 *   - `node-` ⇒ node（项目节点会话族，`SessionScope.BackgroundAgentPrefixes` 成员）；
 *   - 未命中 ⇒ None ⇒ 不覆写（回落 freshDef.model = 既有行为零变化）。
 *
 * == 组合序（本批**未**动）==
 *   请求面单点 = `AgentSessionExecution` 的 LlmRequest 构造：
 *   **槽链 > 派发钉链 > agent 自身链**。本批只修「谁属于哪个槽」的判据。
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
   * 判据表与顺序见文件头注「分类判据」——`delegate-kernel-` 必须压过 `delegate-`
   * （kernel 实例是 delegate 族里的独立类）。
   *
   * 🔴 `delegate-<agent>-<8hex>` 的归槽入参 = `delegateMode`（该会话 delegate 记录的
   * **raw** mode）。**不给 mode ⇒ 该族一律未归槽（None）**——按前缀一律 subagent 的
   * 旧判据已删除（归槽批 2026-11-18，理由与生产实证见文件头注）。
   *
   * 🔴 返回值是**槽目录名**不是 wire 名：`dispatcher-…` 会话 ⇒ `project-dispatcher`
   * （wire 名 "dispatcher" 没有对应目录，直接喂给 `ownChainOf` 会读到不存在的
   * `agents/dispatcher/agent.json` ⇒ 类切换静默失效；ModelChainRoutesSpec 五槽用例
   * 首轮实证）。kernel/node/subagent 三类 wire 名与槽名同形，映射表仍是唯一真源。
   */
  def slotOfSessionId(
    sessionId: Option[String],
    delegateMode: Option[String] = None
  ): Option[String] =
    val wire = sessionId
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { id =>
        val lower = id.toLowerCase
        if lower.startsWith("delegate-kernel-") then KernelWire
        // 🔴 归槽批（2026-11-18）：`delegate-` 一律 SubagentWire 的按前缀判据**已删除**
        // （生产实证 808 kernel + 96 dispatcher 全落 subagent 槽）。改吃记录 mode
        // （真相来源，入参 `delegateMode`）；未识别 ⇒ "" ⇒ 下面的 filter 丢掉 ⇒ None
        // （不覆写，回落钉链/agent 链），原因经 [[delegateSlotUnresolvedReason]] 记录。
        else if lower.startsWith("delegate-") then delegateWireWordOf(delegateMode).getOrElse("")
        else if lower.startsWith("dispatcher-") then DispatcherWire
        else if lower.startsWith("dag-") then NodeWire
        else if lower.startsWith("node-") then NodeWire
        // Ask 会话是主会话的独立 UI/历史身份，不是一个独立模型类别；
        // 它应继承 main/Nebula 模型槽。若遗漏此路由，ask- 会落到 None，
        // 下游只能使用会话钉链/AgentDef 空链，迁移后的主链配置不可达。
        else if lower.startsWith(nebflow.core.ask.AskSessions.Prefix) then MainWire
        else if lower.startsWith("kernel-") then KernelWire
        else ""
        end if
      }
      .filter(_.nonEmpty)
    wire.flatMap(w => Slots.find(_._1 == w).map(_._3))
  end slotOfSessionId

  /**
   * delegate 会话的归槽映射表（**唯一真源**：mode 词 → 槽 wire 名）。归槽批
   * 2026-11-18——真相来源 = `DelegateRegistry.Record.mode`（spawn 时落盘、终身属性）。
   *
   * 词表四项 = 作者名出的四类（kernel / node / dispatcher / subagent）：
   *
   *   | mode 词（记录值） | wire | 槽目录（`agents/<槽>/agent.json`）| 依据（文件:行）|
   *   |------------------|------|--------------------------------|----------------|
   *   | `kernel`     | kernel     | `kernel`             | `DualModeConfig.ModeKernel` = "kernel"（DualMode.scala:23）|
   *   | `dispatcher` | dispatcher | `project-dispatcher` | `DualModeConfig.ModeDispatcher` = "dispatcher"（DualMode.scala:24）|
   *   | `node`       | node       | `node`               | `NodeEngineContract.SessionPrefix` = "node-"（NodeEngineContract.scala:270）/ FlowDagExecutor `dag-` 族 ⇒ NodeWire；词表侧同名词容忍 |
   *   | `subagent`   | subagent   | `subagent`           | `DelegateTool.spawnBackground` 的 `delegate-<agent>-<8hex>` 家族（DelegateTool.scala:1138）|
   *
   * 🔴 注：**可派发**的 mode 词域只有 kernel|dispatcher
   * （[[nebflow.core.delegate.DualModeConfig.ModeWords]]）；`node` / `subagent`
   * 两项是槽名侧的同名词——记录里若真出现这两个词，不得判为陌生词（读面容忍，
   * 零迁移）。值域外的词（存量 `merger`）与缺席一律走
   * [[delegateSlotUnresolvedReason]] 的兜底面：不猜、不覆写。
   *
   * 判据钉住：ModelRolesSpec「四种 mode 各归对槽」四条 + 未识别 mode 兜底一条。
   */
  lazy val DelegateModeWires: List[(String, String)] = List(
    (nebflow.core.delegate.DualModeConfig.ModeKernel, KernelWire),
    (nebflow.core.delegate.DualModeConfig.ModeDispatcher, DispatcherWire),
    (NodeWire, NodeWire),
    (SubagentWire, SubagentWire)
  )

  /** mode 词 → 槽 wire 名（[[DelegateModeWires]] 单点；未识别/缺席 ⇒ None）。 */
  private def delegateWireWordOf(mode: Option[String]): Option[String] =
    mode
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .flatMap(word => DelegateModeWires.collectFirst { case (w, wire) if w == word => wire })

  /** mode 词 → 槽目录名（未识别/缺席 ⇒ None ⇒ 不覆写）。 */
  def slotOfDelegateMode(mode: Option[String]): Option[String] =
    delegateWireWordOf(mode).flatMap(wire => Slots.find(_._1 == wire).map(_._3))

  /** 已归槽的 delegate 会话 id（同一会话只记一条——逐 turn 请求面不刷屏）。 */
  private val unresolvedDelegateSlotsRecorded =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /**
   * delegate 会话**未按 mode 归槽**时的可读原因（归槽批；**兜底不许吞真信息**）。
   * `None` = 无需解释：非 delegate 会话 / 结构性 `delegate-kernel-` 命中 / mode
   * 已在 [[DelegateModeWires]] 内。
   *
   * 两种未归槽形态**分开陈述**（不许混成一句「没有 mode」）：
   *   - mode **缺席**（`Record.mode = None`）= 参数化之前的存量身份；
   *   - mode **在场但不识别**（值域外，如已退役的 `merger`）= 有值、但不在归槽表内。
   *
   * 两者的处置恒相同：**不覆写槽链**（回落派发钉链/agent 自身链）。理由 = 猜一个
   * 类就是又一次「槽链压过主链」，正是本批要修的缺陷；而保守面（不猜）与引擎自身
   * 对这类身份的判断一致（`KernelChildSpawnGate.kernelEvidence` 对同一批 id 同样
   * 两条判据都不命中 ⇒ 非 kernel；它们也不是 dispatcher）。
   */
  def delegateSlotUnresolvedReason(sessionId: Option[String], mode: Option[String]): Option[String] =
    val id = sessionId.map(_.trim).getOrElse("")
    val lower = id.toLowerCase
    if id.isEmpty || !lower.startsWith("delegate-") then None
    else if lower.startsWith("delegate-kernel-") then None
    else if delegateWireWordOf(mode).isDefined then None
    else
      val known = DelegateModeWires.map(_._1).mkString("|")
      Some(
        mode.map(_.trim).filter(_.nonEmpty) match
          case None =>
            s"delegate session '$id' has no recorded mode (DelegateRegistry.Record.mode absent — " +
              s"a pre-mode-parameter legacy identity): no entry in the slot map [$known], so NO slot chain is " +
              s"applied and the session keeps its dispatch pin / agent chain (conservative: never guess a class)"
          case Some(word) =>
            s"delegate session '$id' records mode '$word', which is outside the slot map [$known]: " +
              s"NO slot chain is applied and the session keeps its dispatch pin / agent chain " +
              s"(conservative: never silently fold an unknown mode into the subagent slot)"
      )

    end if

  end delegateSlotUnresolvedReason

  /**
   * 未归槽原因落日志（**单点记录**，同一会话一条 WARN）。口径同
   * `SchemePolicy.smallLlmModel` 的 WARN（「回落必须可观测」）；消费点 = [[slotChainOf]]
   * 的请求面单点。进程内去重集上界 = 见过的未归槽 delegate 身份数（存量 59 条量级）。
   */
  private def recordUnresolvedDelegateSlot(sessionId: Option[String], mode: Option[String]): Unit =
    delegateSlotUnresolvedReason(sessionId, mode).foreach { reason =>
      val key = sessionId.map(_.trim).getOrElse("")
      if unresolvedDelegateSlotsRecorded.add(key) then
        org.slf4j.LoggerFactory.getLogger("nebflow.model.roles").warn(reason)
    }

  /**
   * Slot whose chain edit governs a session's explicit model pin; `None` means the pin
   * cannot be attributed to a category, so no category edit may clear it.
   *
   * The five request-time families come from [[slotOfSessionId]] — which needs the
   * delegate record's mode for the `delegate-<agent>-<8hex>` family (归槽批): a caller
   * that cannot supply it leaves that family unattributed (`None`, the honest answer:
   * a `delegate-` id alone no longer claims any class). An ordinary main conversation
   * (any id outside the background families, see
   * [[nebflow.shared.SessionScope.isMainSession]]) belongs to the main slot: the Nebula
   * chain is its base chain, even though [[slotOfSessionId]] leaves it unclassified on
   * purpose for request resolution. Background families without a slot (`subtask-`,
   * `subagent-`, `workflow-`, `external-`) and blank ids stay `None`.
   */
  def pinOwnerSlot(sessionId: String, delegateMode: Option[String] = None): Option[String] =
    val id = sessionId.trim
    slotOfSessionId(Some(id), delegateMode).orElse(
      Option.when(nebflow.shared.SessionScope.isMainSession(id))(SchemePolicy.NebulaName)
    )

  /** 类别槽自身的链（槽 sidecar 现读；空/未配置 = None ⇒ 回落）。 */
  def ownChainOfSlot(slot: String): Option[AgentModelConfig] =
    SchemePolicy.ownChainOf(slot).filter(SchemePolicy.hasChain)

  /**
   * 请求面单点（每 turn 现读）：该会话命中五类槽且**槽已配置** ⇒ 槽链；否则 None
   * （**不**回落 base——槽未配置时由调用方继续走钉链/agent 自身链的既有优先级）。
   * 🔴 组合纪律：调用点必须 `slotChainOf(sid, mode).orElse(钉链面)` ——钉链在 base
   * 之前（provider-concurrency 批的缓存键稳定面）；本函数只回答「槽链有没有」。
   *
   * `delegateMode` = 该会话 delegate 记录的 **raw** mode（
   * `DelegateRegistry.rawModeOf`；不许用 `modeFaceOf` 的过滤版——那会把「有 mode 但
   * 不识别」折叠成「没有 mode」，吞掉兜底要陈述的真信息）。
   */
  def slotChainOf(
    sessionId: Option[String],
    delegateMode: Option[String] = None
  ): Option[AgentModelConfig] =
    recordUnresolvedDelegateSlot(sessionId, delegateMode)
    slotOfSessionId(sessionId, delegateMode).flatMap { slot =>
      ownChainOfSlot(slot).orElse {
        // Ask sessions have an ephemeral AgentDef with no model/pin. When the
        // main sidecar is absent/empty, resolve through the same configured
        // main fallback used by SchemePolicy rather than returning no chain.
        Option.when(nebflow.core.ask.AskSessions.isAskSession(sessionId))(
          SchemePolicy.resolveModel(SchemePolicy.NebulaName, None)._1
        )
      }
    }

  end slotChainOf

  /**
   * 组合面（语义同 `slotChainOf(sid, mode).orElse(base)`）：槽已配置 ⇒ 槽链（类粒度
   * 切换生效点）；否则原样透传 base。**注意**：带钉链的调用点（AgentSessionExecution）
   * 用 [[slotChainOf]] 自行组合（槽 > 钉链 > base），不走本函数——本函数服务无钉
   * 链的读面。
   */
  def effectiveModel(
    base: Option[AgentModelConfig],
    sessionId: Option[String],
    delegateMode: Option[String] = None
  ): Option[AgentModelConfig] =
    slotChainOf(sessionId, delegateMode).orElse(base)

end ModelRoles
