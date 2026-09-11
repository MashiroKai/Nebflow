package nebflow.core.project

import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*

/**
 * Project + Node + Flow Map 数据模型（#28 阶段 0，方案文档 20260831_project-node-architecture.md §2.1）。
 *
 * - Node 本质 = 一个 agent 以 flow 形式组织（leaf、无 Mail 身份、无记忆、ephemeral）
 * - Flow Map = 每项目唯一：磁盘 flow-map.json（活动区 nodes + 归档区 archive）+ 内存 Ref
 * - TTL 只管显示（终态 +24h 从活动图消失），归档结果全文保留可长期接线投递
 */

/** 节点生命周期（§2.1）：pending/running/completed/failed/cancelled + wiring 扩展。
  * blocked（20260902 反馈重入设计 §1.1）：turn 正常结束但节点声明无法继续——
  * 停止传播 + 触发重入的终态；永不过期（ttlExpireAt=None，待办语义 §1.4）。
  * hold 机制已于 2026-09-06 提案 A 彻底移除（作者拍板）：节点完成一律走
  * blocked→gate→completed 原路径——「完成即投递 + 事后回看」，不设人工闸门。 */
object NodeLifecycle:
  val Wiring = "wiring"
  val Pending = "pending"
  val Running = "running"
  val Completed = "completed"
  val Failed = "failed"
  val Cancelled = "cancelled"
  val Blocked = "blocked"

  val Terminal: Set[String] = Set(Completed, Failed, Cancelled, Blocked)

  /** 全部合法生命周期值（NodeList status 过滤枚举校验单点，裁定⑤a 20260907）。 */
  val All: Set[String] = Set(Wiring, Pending, Running, Completed, Failed, Cancelled, Blocked)

/** 取消触发源（取消静默死锁修复批 2026-09-10，作者裁定 **R7 方案 3**）：只区分
  * 「引擎发起 / 用户发起」两态（完整 taxonomy——stuck-watcher-l3 / giveup /
  * agent-control / parent-cascade / node-cancel / dead-session-reap——本批不做）。
  *
  * 取值口径（**不改 AgentEvent 消息形态**，设计 §6-R7 工程判定：跨模块常驻协议
  * 零扰动；来源由桥侧从 `AgentEvent.Cancelled` 的 reason 文本前缀推导）：
  *   - `Engine`：引擎自身看门狗/回收链发起——`TaskStuckWatcher` 的 L3 硬恢复与
  *     giveUp 桥取消（reason 尾注 `— released by TaskStuckWatcher`）、
  *     `NodeEngine.reapStaleRunning` 的死会话收殓。特征 = 无人主动要求取消，
  *     是引擎对「卡死/死亡」的自动处置。
  *   - `User`：人/Agent 主动发起——`AgentControl` cancel、面板 `cancelAgent`、
  *     `NodeCancel`（含 `reapStaleRunning` 之外的 NodeCancel-stale 转发）、父会话
  *     删除级联（`SessionChildCascade`）、以及一切无特征文本的兜底。
  *
  * 消费面：节点 `result` 文本（`cancelled[source=engine|user]: reason=…`）、
  * `cancelled` 审计事件 summary、R1 回流通知文本——事后可区分「用户主动取消」
  * 与「引擎误杀」，这是评估判据误伤率的前提。 */
enum CancelSource:
  case Engine
  case User

object CancelSource:
  val EngineCode = "engine"
  val UserCode = "user"

  /** 编码（result / 事件 / 通知文本共用单点）。 */
  def code(s: CancelSource): String =
    s match
      case CancelSource.Engine => EngineCode
      case CancelSource.User   => UserCode

  /** 引擎发起特征串：`TaskStuckWatcher` 两处桥取消的 reason 尾注（L3 `… — released
    * by TaskStuckWatcher` / giveUp `… released by TaskStuckWatcher; consider
    * re-delegating this task`）——单一判据，桥侧零元数据新增。 */
  val StuckWatcherMarker = "released by TaskStuckWatcher"

  /** reason 文本 → 触发源分类（单点，R7）。 */
  def classify(reason: String): CancelSource =
    if reason.contains(StuckWatcherMarker) then CancelSource.Engine else CancelSource.User

  /** 由桥的 `FailOutcome` 消息（形如 `cancelled: <reason>` / `cancelled by NodeCancel`）
    * 反解取消原因文本——桥只把原因拼进消息串，本函数是唯一还原点（R2：原因不再
    * 在桥之后被 `contains` 嗅探后丢弃）。 */
  def reasonFromBridgeMessage(message: String): String =
    val trimmed = message.trim
    if trimmed.startsWith("cancelled:") then trimmed.stripPrefix("cancelled:").trim
    else if trimmed.contains("cancelled") then trimmed
    else trimmed

/** 结构化 blocked 反馈（设计 §1.3 JSON 体）：BlockedReader 从节点最终输出解析。 */case class BlockedFeedback(
  category: String, // upstream-incomplete | task-underspecified | agent-mismatch | external-dependency | needs-split | other
  detail: String,
  suggestion: String
)

object BlockedFeedback:
  given Configuration = Configuration.default
  given Codec[BlockedFeedback] = ConfiguredCodec.derived

/** LoopNode 配置（LoopNode 批 2026-09-06，主设计 20260902_flowmap-engine-evolution-design.md §2）。
  * NodeDef.loop = Some 时节点以 loop 模式执行：worker 会话生产 → verify 会话校验 →
  * PASS → 走既有 completed 交付链；FAIL → 打回 worker（输入恒定）重跑；达
  * maxRounds(K) 仍未 PASS → 终态。与 hold/merge 的简洁一致性：NodeDef.loop 是
  * Option 条件字段（缺省 None = 普通节点，withDefaults 解码旧数据零迁移），
  * buildNodeJson 只在 loop 节点带 "loop" 条件字段（与 hold/merge 同构）。 */
case class LoopConfig(
  /** 轮级上限 K：打回重跑累计达 K 轮仍未 PASS → 终态（failNode，result 注明
    * 「loop 达 K 轮上限未通过验证」）。与 LoopGuard（会话内 turn 级既有防线）
    * **两轴独立**——turn 级管单会话内精确重复（worker/verify 会话内部触发即
    * 以执行层 failed 形态上浮 → 整 Loop failed），轮级管 Loop 总轮数（本字段）。
    * 语义：内容在变的第 K 轮也停（轮帽）；内容逐字不变量由 turn 级 LoopGuard
    * R-text 提前拦截（同会话内计数跨轮累计，见 LoopGuard.scala:84-88）。 */
  maxRounds: Int,
  /** verify 侧 agent 名（默认 "general"）：验证方 = 通用 agent + plugins 体系
    * （阶段 2 裁定 A——verify 专业化按验证域经 plugins 分配；worker 与 verify
    * 共享 node.plugins 注入）。显式指定其他已装载 agent 名亦可。 */
  verify: String = "general",
  /** verify 校验清单模板（含验收基准说明），注入 verify 会话。空 → 执行期用
    * NodeEngine 内置默认清单（VerifyDefaultTask）。 */
  verifyTask: String = "",
  /** loop 开关：false = loop 语义停用——节点退化为普通单次执行（worker 会话
    * 跑一轮即完成，无 verify 无迭代；NodeEdit 可关闭再开启）。缺省 true。 */
  enabled: Boolean = true
)

object LoopConfig:
  given Configuration = Configuration.default.withDefaults
  given Codec[LoopConfig] = ConfiguredCodec.derived

/** P1 out 语义门控 · 结构化 out 边（20260908 spec §2.2，作者 gate G1-G13 全过；
  * 方案源 wf3 §3.1 OutEdge 双读）。NodeDef.out 自单值 Option[String] 升为 List[OutEdge]
  * （扇出 + 失败信号边表达力），旧字符串经 codec 双读解码（归档数据零迁移）。
  *
  * @param to   "Nebula"（根会话上报通道）| 目标节点 id
  * @param on   门控集 ⊆ {pass, failed}。缺省 {pass}（G1：D5 failed 零结算世界下旧拓扑
  *             零漂移——failed 上游不触发 pass-only 边 = 普通下游停等语义）。
  *             **Nebula 边缺省例外 {pass, failed}**：Nebula 是上报通道非下游结算，
  *             今天 completed/failed 双通报是旧拓扑零漂移的组成部分（D5 只废除向
  *             下游结算，未动 Nebula 通报）；显式门控（"(pass)Nebula"）按声明收紧。
  * @param mode "result"=投载荷 | "signal"=只发信号（deps 同款：投递侧只记账归零
  *             barrier + 启动，载荷由 buildInput 按边 mode 抑制——下游输入=自身 task）。 */
case class OutEdge(
  to: String,
  on: Set[String] = Set("pass"),
  mode: String = "result"
)

object OutEdge:
  val Pass = "pass"
  val Failed = "failed"
  val Gates: Set[String] = Set(Pass, Failed)
  val Result = "result"
  val Signal = "signal"
  val Modes: Set[String] = Set(Result, Signal)
  val DefaultOn: Set[String] = Set(Pass)
  /** Nebula 边**存量读路径**的缺省门集：completed + failed 双通报（旧拓扑零漂移）。
    *
    * **⚠ 两处语义自此分叉（2026-09-12 裁定 1 / R1-a 起）——本常量不再代表「新写一条
    * Nebula 边」的语义**：
    *   - **存量读路径**（＝本常量仅有的两个消费方）：`fromLegacyString("Nebula")`
    *     （旧字符串形态落库边）+ `OutEdge.nebula`（NodeDef 字面构造，测试/工具直建）
    *     ⇒ 仍解出 `{pass,failed}` / mode=result（历史字节零迁移、零回溯）。
    *   - **工具写路径**（`NodeTools.parseOutSegment`）：bare `"Nebula"` 现解为
    *     **纯出口标记** `on={pass}` + `mode=signal`（零投递、只记账）；要通知 root 必须
    *     写**显式门集**字面（`"(pass)Nebula"` / `"(pass,failed)Nebula"`，mode=result）。
    * 二者对同一字面 `"Nebula"` 给出**不同**落边 ⇒ 任何新增消费方必须显式声明自己属哪一侧。 */
  val NebulaDefaultOn: Set[String] = Set(Pass, Failed)
  val NebulaTarget = "Nebula"

  given Configuration = Configuration.default.withDefaults
  given Codec[OutEdge] = ConfiguredCodec.derived

  /** 旧拓扑 "Nebula" 边的等价构造（completed+failed 双通报，零漂移）——NodeDef
    * 字面构造（测试/工具直建）用；与 fromLegacyString("Nebula") 同形。 */
  def nebula: OutEdge = OutEdge(NebulaTarget, NebulaDefaultOn)

  /** 旧字符串单边解码（codec 双读与表面语法共用单点）："A"→OutEdge("A",{pass},result)；
    * "Nebula"→{pass,failed} 双通报形态；""/"null"（任意大小写、含空白）→ None。 */
  def fromLegacyString(s: String): Option[OutEdge] =
    val t = s.trim
    if t.isEmpty || t.equalsIgnoreCase("null") then None
    else Some(if t == NebulaTarget then OutEdge(t, NebulaDefaultOn) else OutEdge(t))

  /** out 边目标串 → 节点 id 解析（标识符二元性收敛单点，2026-09-09 in 落盘丢失事故）：
    * 目标串历史上有两种形态——节点 id（引擎镜像 appendEdgeTo 写入）与节点名（LLM/分发器
    * 按 name 接线的自然写法，原样落库）；而节点 Map、in/deliveredTo 记账、settleTo 投递
    * 全部以 **id 为键**。直接以原始串查 Map 会静默 MISS（setOut added 侧漏记下游 in）或
    * 误命中另一形态（removed 侧把 id 形态旧边当「被移除目标」→ 抹掉下游 in —— 正是
    * 20260909 「create 带 in → 随后 out 按名改接 → in 被清空 → barrier 空真提前启动」
    * 的事故链）。解析顺序：id 命中 → 名字命中 → None（悬空，调用方显式处理）。
    * 名字唯一性由 NodeEdit schema 保证（nodename unique within the Flow Map）。 */
  def resolveTargetId(nodes: Map[String, NodeDef], target: String): Option[String] =
    if nodes.contains(target) then Some(target)
    else nodes.values.find(_.name == target).map(_.id)

  /** 规范化（投递/比较/落库单点）：同 (to, mode) 多边合并 on 集合（compat 矩阵 #1：
    * 一次终态至多投一次）；on 滤非法门（手改数据防御），滤空 → {pass}；mode 非法 →
    * result；LinkedHashMap 保序（边序稳定 = payload/存储确定性）。 */
  def canonical(edges: List[OutEdge]): List[OutEdge] =
    val merged = scala.collection.mutable.LinkedHashMap[(String, String), Set[String]]()
    edges.foreach { e0 =>
      val g = e0.on.filter(Gates.contains)
      val e = e0.copy(on = if g.isEmpty then DefaultOn else g,
        mode = if Modes.contains(e0.mode) then e0.mode else Result)
      val k = (e.to, e.mode)
      merged(k) = merged.getOrElse(k, Set.empty) ++ e.on
    }
    merged.map { case ((to, mode), on) => OutEdge(to, on, mode) }.toList

  /** 双读解码（spec §2.2 #1，归档零迁移）：null/缺键→Nil（withDefaults）；旧字符串→
    * fromLegacyString 单边；数组→逐边 ConfiguredCodec（withDefaults 补 on/mode）。 */
  given Decoder[List[OutEdge]] = Decoder.instance { cur =>
    cur.value match
      case j if j.isNull => Right(Nil)
      case j if j.isString => Right(fromLegacyString(j.asString.getOrElse("")).toList)
      case _ => Decoder.decodeList(summon[Codec[OutEdge]]).tryDecode(cur)
  }

  /** 编码：Nil → Json.Null（与旧 Option None 落盘同形，无出环节点存储字节零漂移）；
    * 非空 → 边对象数组（on/mode withDefaults 全量写出）。 */
  given Encoder[List[OutEdge]] = Encoder.instance {
    case Nil  => Json.Null
    case list => Json.fromValues(list.map(e => summon[Codec[OutEdge]].apply(e)))
  }

/** P2 failed 回跳 retry 策略（20260908 spec §2.3，wf3 §4.2 方案①「回跳不是图边
  * 而是策略字段」）：挂**下游单侧**（沿 deps「下游单侧持有、不回写上游」设计先例）。
  * 本节点 failed 且 gen < max → 引擎自动化执行既有重激活协议全链（本节点重激活 +
  * retry.upstream 重激活重跑 → 上游经 pass 边重投 → 本节点新代次启动）；gen 达 max
  * → cap 耗尽，failed + FeedbackRouter RetryCap 升级。回跳边不进图（不进 in/out/
  * deps 任何邻接表）→ 绕开 DAG 环检（环检是批聚簇/settle 终止性/前端渲染的全链
  * 不变量）；retry 自身成环由 NodeEdit 创建/编辑期校验拒绝（NODE_RETRY_CYCLE）。
  * 旧 flow-map.json 无此键 → withDefaults 解码 None（零迁移）= 旧行为（failed 即
  * 终态，无自动回跳）。 */
case class RetryPolicy(
  /** 回跳上游：必须是本节点的 in/deps 邻居（NodeEdit 校验，NODE_RETRY_NEIGHBOR）
    * ——回跳语义 =「重取上游产物再试」，跨子图回跳无输入语义支撑。 */
  upstream: String,
  /** 回跳预算：本节点累计自动回跳次数上限（gen 达 max → 升级）。1-10
    * （NODE_RETRY_MAX_RANGE）。max=1 → 允许一次回跳（共两次执行机会）。 */
  max: Int
)

object RetryPolicy:
  given Configuration = Configuration.default.withDefaults
  given Codec[RetryPolicy] = ConfiguredCodec.derived

/** Node 数据模型（§2.1 JSON 示例字段全量）。
  *
  * agent 字段（2026-09-05 插件架构对齐）：**新建节点一律落 "general"**（执行统一
  * 通用 agent，专业能力由 plugins 差异化——NodeEdit 已不接受 agent 参数）；字段
  * 保留 = 存量数据兼容读（旧节点 agent 值原样装载、nodeJson 照常输出），引擎
  * spawn 仍读 NodeDef.agent（spawnAndRun → EntityLoader.loadAgent）。
  *
  * description（2026-09-05 创建必写）：简短描述（非空 ≤200 字符，创建时 NodeEdit
  * 强校验），存 NodeDef 进 Flow Map 默认载荷（NodePayload）——按需读取第一层；
  * 编辑可 update；存量节点无 description → 前端回退 taskPreview（载荷条件字段，
  * task 首行 ≤80 字符截断）。 */
case class NodeDef(
  id: String,
  name: String,
  agent: String,
  skill: Option[String] = None,
  mcp: Option[String] = None,
  worktree: Option[String] = None,
  preset: Option[String] = None,
  /** task 全文只活在内存（水合）与 per-node 文件——落盘 JSON 不再携带（2026-09-06
    * 存储瘦身批）：活动区 JSON = ≤500 字符摘要 + `taskFile` 指针，task 全文持久化
    * 于 `<workspace>/.nebflow/tasks/<nodeId>.md`（加载水合回全文，buildInput/重入/
    * NodeList detail 消费方零改动）；归档区 JSON 直接剥 task（无重入价值）。
    * taskFile 指针只活在磁盘 JSON，不进内存模型。 */
  task: Option[String] = None,
  /** 创建必写的简短描述（20260907 裁定⑤c 双层化：≤60 字符，进默认载荷）。
    * 默认 None = 存量兼容（旧数据零迁移；存量 ≤200 长描述原样保留不回溯）。 */
  description: Option[String] = None,
  /** 可选长描述（20260907 裁定⑤c 双层化：≤200 字符）——**不进默认载荷**，仅
    * detail 按需通道（NodeList detail= / REST result 端点条件键）与前端详情窗
    * 消费；存量节点无长文 → 缺键，消费方回退短文 description。默认 None = 零迁移。 */
  descriptionLong: Option[String] = None,
  in: List[String] = Nil,
  /** P1 out 语义门控（20260908 spec §2.2）：出边列表（0..N）。Nil = 悬空（结果保留
    * 在 result，接线后自动投递）——旧单值拓扑经 codec 双读零迁移（"A"→pass 单边、
    * "Nebula"→双通报边、null/缺键→Nil）。扇出/失败信号边表达力见 OutEdge。 */
  out: List[OutEdge] = Nil,
  /** 依赖连接（deps 设计 §1.1，主文档 20260902_flowmap-engine-evolution-design.md）：
    * 下游单侧持有、不回写上游（上游不知道自己被依赖——「不用其输出」的结构体现）。
    * 语义 = 只等上游完成信号（status==completed），不投递上游结果——下游输入 =
    * 自身 task（自足）；failed/cancelled/blocked ∉ completed → 不触发，下游保持
    * pending/wiring 可见。旧 flow-map.json 无此键 → withDefaults 解码为 Nil（零迁移）。 */
  deps: List[String] = Nil,
  /** 合并节点标记（merge-node 批 20260905，方案 .nebflow/Spec/merge-node-plan.md）：
    * true = 批次产物落地收口节点——全部上游 completed 才触发（既有 in-barrier 语义）；
    * 上游 failed 时零结算（D5 20260908 wf1cde §3：failed 不向任何下游结算），合并
    * 节点例外转 blocked 可见终态不悬挂
    * （MergeNodePolicy 单点语义，NodeEngine.deliverFailed 唯一挂接）。
    * 落地收口在工作区根仓执行 → 必须不配 worktree（沙箱根=workspace，.git 可写）。
    * 旧 flow-map.json 无此键 → withDefaults 解码为 false（零迁移）= 旧行为。 */
  merge: Boolean = false,
  /** LoopNode 配置（LoopNode 批 2026-09-06，主设计 §2）：Some = 本节点是 loop
    * 节点——worker/verify 双会话迭代（执行期由 NodeEngine.runLoopNode 驱动，双
    * 会话贯穿节点存续期、终态双销毁，不进 Flow Map 存储）；None = 普通节点。
    * **loop 与 pending/等待态交互**：loop 迭代权只在节点 running 期间（startNode
    * 启动 → runLoopNode 驱动轮次，worker/verify 会话 spawn 于启动时）；wiring/
    * pending（等 in barrier + deps 闸门）与普通节点无差别——闸门全在 startNode
    * 内，deps/hold/merge 均可与 loop 共存（deps 启动前把关、hold 在 verify PASS
    * 完成时经 completeNode 生效、merge 语义在 loop 完成后照常）；worker/verify
    * 会话输出 BLOCKED 锚定 → Loop 级 blockedNode（终态，重激活从第 1 轮重跑，
    * 旧会话已随终态销毁）。enabled=false → 本字段保留但节点按普通节点单次执行。
    * 旧 flow-map.json 无此键 → withDefaults 解码 None（零迁移）= 旧行为。 */
  loop: Option[LoopConfig] = None,
  /** loop 运行态 · 已跑轮数（仅 loop 节点有意义；每轮状态迁移经 store.mutate +
    * WS nodeUpdated 同步，NodeList/REST/WS payload 单点序列化见 NodePayload）：
    * 0 = 未启动；running = 当前轮；终态 = 总轮数（PASS 即通过轮）。 */
  loopRound: Int = 0,
  /** loop 运行态 · 当前阶段（running 才有）：worker / verify。 */
  loopPhase: Option[String] = None,
  /** loop 运行态 · 最近一次 FAIL verdict 摘要（≤200 字符；verify FAIL 打回时
    * 更新，前端卡片可显示最近打回原因；PASS/终态保留最后一次 FAIL 供追溯）。 */
  loopLastVerdict: Option[String] = None,
  /** P2 failed 回跳 retry 策略（spec §2.3；RetryPolicy 详注）：Some = 本节点
    * failed 时引擎自动回跳重跑 retry.upstream。下游单侧持有；None = 旧行为
    * （failed 即终态）。旧 flow-map.json 无此键 → withDefaults 解码 None（零迁移）。 */
  retry: Option[RetryPolicy] = None,
  /** P2 retry 代次载体（spec §2.3）：本节点身份累计被自动回跳的次数（重激活协议
    * gen+1，与 blockCount 分立——blockCount 专管 blocked 轮次口径不变）。0 = 未
    * 回跳过；达 retry.max → cap 耗尽升级。前端「attempt N」显示载体（渲染归 F3）。
    * 旧 flow-map.json 无此键 → withDefaults 解码 0（零迁移）。 */
  gen: Int = 0,
  /** dispatch-notify 回流标志（2026-09-05 批）：true = 节点到达终态（先接线
    * completion）后触发项目分发器新会话（带原因码的独立信号通道，不占 out 边；
    * 防循环/预算/去重见 DispatchNotify）。NodeEdit 按需开启，默认关——分发器
    * 因通知新建的节点不继承本标志（显式开启才通知，保证收敛）。
    * 旧 flow-map.json 无此键 → withDefaults 解码为 false（零迁移）= 旧行为。 */
  notifyDispatcher: Boolean = false,
  /** dispatch-notify 投递记账（at-least-once：tell-then-mark，V8 nebulaDeliveredAt
    * 同款）：通知触发后落时间戳；空 = 未触发/未标记（重启后由 TtlTick 补投扫描
    * 重触发）。旧 flow-map.json 无此键 → withDefaults 解码为 None（零迁移）。 */
  notifySentAt: Option[Long] = None,
  deliveredTo: List[String] = Nil,
  /** V8 (2026-09-03): out=Nebula 投递记账——deliverToNebula 成功 offer 后落时间戳。
    * 与 deliveredTo（in barrier 判定，节点间沿边去重）完全分离，barrier 语义零改动；
    * 空 = 结果未达 Nebula（崩溃窗口 / 根 ref 缺失滞留）→ 周期重投扫描补投。
    * 旧 flow-map.json 无此键 → withDefaults 解码为 None（零迁移）。 */
  nebulaDeliveredAt: Option[Long] = None,
  status: String = NodeLifecycle.Wiring,
  result: Option[String] = None,
  // retries/maxRetries 声明字段已删（trigger-chain-fix §6.4 裁定：落库展示但零
  // 消费方的假语义不留——接线需 transient/deterministic 失败分类与下游占位结算
  // 冲突消解，超出最小改动；触发可靠性由 settleSweep + start-aborted/
  // trigger-starved 信号承担。circe withDefaults 解码忽略未知键，存量
  // flow-map.json 携带的两键零迁移零破坏）。
  /** 该节点身份累计被 blocked 轮数（防循环计数 §3.1；NodeEdit 重激活不清零）。 */
  blockCount: Int = 0,
  /** 最近一次 blocked 的结构化反馈（§1.3）。存储侧永久保留（重激活不清零）；
    * **载荷侧仅 status==blocked 携带**（观测面上下文经济学批 20260907 裁定②）：
    * completed/failed/cancelled 的历史残留不进 NodeList/REST/WS 默认载荷——
    * 历史参照走 detail 按需通道补挂与归档留痕；FeedbackRouter 重入协议消费
    * 当下反馈（store 直读，不经载荷），零影响。 */
  blockedFeedback: Option[BlockedFeedback] = None,
  createdAt: Long,
  startedAt: Option[Long] = None,
  completedAt: Option[Long] = None,
  ttlExpireAt: Option[Long] = None,
  /** 阶段 2b Plugins（§B.4 第 3 步）：分配给本节点的能力包名列表（NodeEdit 的
    * plugins 参数，replace-on-provide）。插件解析/注入/回收全链见 NodeEngine
    * prepareNodePlugins / runWithAgent。放在末位带默认值——既有位置构造零破坏。
    * 旧 flow-map.json 无此键 → 解码 Nil（零迁移）。 */
  plugins: List[String] = Nil,
  /** bg-wait 标注（bgtask-completion-gate 批 + 僵尸收敛批 2026-09-06）：节点完成
    * 闸在自持等待后台任务时置位（描述 = 当前在途等待型后台任务快照），全部清空 /
    * 终态化时清除。前端可辨「设计内等待后台任务」（status=running + bgWait 非空）
    * vs 真僵尸（无活会话且无在途后台任务）——避免把设计内等待误判为 dead-session
    * running。旧 flow-map.json 无此键 → withDefaults 解码 None（零迁移）。 */
  bgWait: Option[String] = None,
  /** 节点会话 id 持久引用（crash-recovery 批 2026-09-07，D1）：flipToRunning 与
    * startedAt 同事务落库——崩溃后 boot sweep 据此定位磁盘 transcript（nodeId→sessionId
    * 映射此前只活在进程内，崩溃即断链 G1）。普通节点 = 唯一会话；loop 节点 = worker
    * 主会话（verify 见 sessionRefVerify，裁定③双会话续接）。终态不清除（审计价值：
    * 事后排查可定位 transcript）。旧 flow-map.json 无此键 → withDefaults 解码 None
    * （零迁移，先例 deps/plugins/notifySentAt）——无值运行残留按 (c) 类处置。 */
  sessionRef: Option[String] = None,
  /** loop 节点 verify 会话 id 持久引用（crash-recovery 批，裁定③）：仅 loop 节点
    * 翻转时与 sessionRef 同事务落库；非 loop 节点恒 None（重执行即清除）。 */
  sessionRefVerify: Option[String] = None,
  /** R4「待承接」标记（取消静默死锁修复批 2026-09-10，作者裁定 R4 方案 4）：
    * 本节点 in-barrier 上曾有、后被引擎**取消并自动摘除**（`NodeEngine.cancelNode`
    * 的 R4 摘除：被取消节点 out→Nebula + 本节点 in 镜像 prune）的上游 id 列表。
    *
    * 语义：摘除只是把人工「改接 out 触发 in 镜像 prune」自动化（D5 对 cancelled 的
    * 既有指引），**不是零结果结算**；但摘除后 barrier 会以「少一轨」的输入正常
    * 启动，静默产出一个缺轨结论。本字段把「缺失」变成显式状态 —— 三个启动闸门
    * （`NodeEngine.startNode` / `settleTo` / `settleRunnableSweep`）联合要求
    * `pendingSuccession.isEmpty` 才放行，即 **barrier 不被以缺轨输入自动触发**。
    *
    * 解除：分发器承接动作落地时（NodeEdit 对本节点任意实际变更 —— 例如把新承接
    * 节点 append 进本节点 in）清空。可见性：NodePayload 条件字段 + mount-stalled
    * 事件 reason 明示「待承接」。
    * 旧 flow-map.json 无此键 → withDefaults 解码 Nil（零迁移）。 */
  pendingSuccession: List[String] = Nil,
  /** 未申报计时起点（noderpt 批 A 段 2026-09-11 作者裁定）：节点会话交棒
    * （观察桥收到 `AgentEvent.Completed`）时 `NodeReportRegistry` 申报槽为空 ⇒
    * 引擎置本字段（**只置不重**——后续 Completed 不改起点，NodeMessage 重入也不重置）。
    *
    * 唯一清表条件 = 该会话任一 `node_report` 申报（清表后由既有 `drain` 分流终态化）；
    * 终态/挂起出口（`NodeEngine.cleanupRunTables`）与新一轮翻转
    * （`flipToRunning` / runWithAgent 的 CAS 翻转 = 新会话）同点清零。
    *
    * 落盘的理由（硬约束）：计时必须跨宿主重启存活——扫描腿在 `ProjectActor.TtlTick`
    * （30s 节拍）上跑，禁止 per-node fiber 计时器（宿主重启即丢）。
    * 旧 flow-map.json 无此键 → withDefaults 解码 None（零迁移）。 */
  reportPendingSince: Option[Long] = None,
  /** 未申报提醒拍数（同一批）：每**注入**一拍 +1（CAS 单发；quiescent 档不递增）。
    * 到顶只停止注入，永不判 failed、永不杀会话（作者裁定）。与
    * [[reportPendingSince]] 同点清零。旧 flow-map.json 无此键 → 解码 0（零迁移）。 */
  reportReminderCount: Int = 0,
  /** 终态延迟销毁登记时刻（noderpt 批 B 段 2026-09-11 作者裁定「一律存活 30 分钟再销毁」）：
    * 节点**终态化瞬间**置 `destroyAt = now + Defaults.NodeDestroyWindowMs`（默认 30min），
    * 时刻只登记、**不杀进程**——窗口内进程/任务照跑、输出照写、允许读取取证，仅禁止
    * 新 spawn（`BgTaskRegistry.finalizedSessions` 表）；到点由
    * `NodeEngine.sweepDestroyWindows`（`ProjectActor.TtlTick` 30s）执行
    * `reclaimSession`（杀进程树 + 注销 registry + 逐条 finalizeTask + 释放
    * `ShellSession.sessions` 条目 + WS `backgroundTaskUpdate(status="cancelled")` 帧）
    * 后清零（幂等）。
    *
    * 写入点 = 桥终态四出口（completed/failed/cancelled/zombie）与 blocked 出口的
    * `scheduleDestroy`（挂起腿与 NodeCancel 腿不登记，见 NodeEngine 注）；清除点 =
    * 销毁完成（扫描腿）/ 新一轮翻转回 Running（窗口撤销）/ 节点非终态时扫描腿自愈。
    * 旧 flow-map.json 无此键 → `withDefaults` 解码 None（零迁移，先例 sessionRef 同款）。 */
  destroyAt: Option[Long] = None
)

object NodeDef:
  given Configuration = Configuration.default.withDefaults
  given Codec[NodeDef] = ConfiguredCodec.derived

/** NodeList 载荷同构的节点 JSON（NodeList 工具 / REST flow-map / WS 事件共用单一序列化点）。
  * WS 事件（nodeCreated/nodeUpdated/nodeRemoved）与快照永远同构，前端增量渲染可直接对齐
  * 字段集：{id, name, agent, description, status, in, hasWorktree, worktree,
  * createdAt, completedAt, ttlLeftSec}（+ 条件字段，见下）。
  *
  * **载荷收敛（2026-09-05 Flow Map 精简批）**：默认载荷只含元数据——**节点结果全文与
  * 摘要都不进默认载荷**（原 result ≤500 字符摘要键移除；结果全文持久化在 per-node 文件
  * `results/<nodeId>.md`，经 REST GET /projects/<n>/flow-map/nodes/<id>/result 或
  * NodeList(detail=<nodeId>) 按需单点取）。条件字段（与 deps/plugins 同构，非命中
  * 不带——载荷字段集对无此特征的节点零漂移）：
 *   - hasResult: 节点持有结果全文（前端据此发起按需拉取）；
 *   - taskPreview: 存量节点无 description 时的回退展示（task 首行 ≤80 字符截断）；
 *   - deps / plugins / merge：既有条件字段语义不变（merge 仅 merge 节点带 "merge":
 *     true——mount-enforce 批 payload 契约，缺失=非 merge）；
 *   - blockedFeedback：**仅 status==blocked 携带**（20260907 上下文经济学批裁定②
 *     ——非 blocked 终态的历史残留不进默认载荷；历史参照走 detail 补挂/归档）。
 *   - bgWait：**仅 bg-wait 自持的 running 节点携带**（僵尸收敛批 2026-09-06）。
 *   - reportPendingSince / reportReminderCount：**仅 status==running 且未申报计时
 *     已置携带**（noderpt 批 A 段 2026-09-11：已交棒但未 node_report 的节点——
 *     取证/前端据此辨「待申报」Running 态，两键成对、绝不在终态节点泄漏）。
 *   - notifySentAt：**仅异常终态（failed/cancelled）且已上报携带**（归档语义批
 *     2026-09-07「送达即移」——前端链判据据此判断异常终态「已上报可归档」；与
 *     deps/plugins 同构条件字段，非命中不带 = 零字段漂移）。completed 无上报要求
 *     恒不带。
 *   - chainId：**仅当节点所属拓扑链成员数 ≥2 携带**（链级抽象 P0；链 = 活动∪归档
 *     合并集弱连通分量，派生单点 FlowMapStore.topologicalChains，判据注入单点 =
 *     FlowMapStore.chainAttrsOf）——孤立单节点链不带，payload 字段集零膨胀；与
 *     deps/plugins 同构条件字段，非命中不带。WS 四事件经 NodeEngine.emitWithChain
 *     富化单点自动携带（帧外壳零改动）。
 *   - chainIds：**仅 merge 节点且多链归属（可达成员链数 ≥2）携带**（U1 批 · 作者裁定①；
 *     值 = **主链 id 首项 + 全量成员链 id**（主链恒首项，即 `chainIds.head == chainId`；
 *     入口可达分解，分量 entries 序），无上限无降级；派生单点
 *     FlowMapStore.mergeChainIds）——普通节点与单链 merge 节点不带（普通节点恒单值
 *     chainId，禁改成全员数组）；主链值在两键中冗余出现 = 有意形态契约（对应 §0bis.3
 *     文档元数据头 `chains: [主链, 支链…]`）。
 * skill/mcp/preset 为节点配置（2b §B.4/H-11① deprecated，新建参数已退役）：同样
 * 条件序列化——仅非 None 才带（20260907 裁定③，无三键节点字段集字节级零漂移）。 */
object NodePayload:
  /** taskPreview 截断上限（回退展示第一层，存量节点专用）。 */
  val TaskPreviewMaxChars: Int = 80

  def buildNodeJson(node: NodeDef, now: Long, chainId: Option[String] = None,
                    chainIds: Option[List[String]] = None): Json =
    val ttlLeft = node.ttlExpireAt.map(t => Math.max(0L, (t - now) / 1000L))
    val baseFields = List(
      "id" -> node.id.asJson,
      "name" -> node.name.asJson,
      "agent" -> node.agent.asJson,
      // 创建必写的简短描述（按需读取第一层）；存量无值 → null（前端回退 taskPreview）
      "description" -> node.description.asJson,
      "status" -> node.status.asJson,
      "in" -> node.in.asJson,
      "hasWorktree" -> node.worktree.isDefined.asJson,
      "worktree" -> node.worktree.asJson,
      // blocked 反馈重入（设计 §4.1）：blockCount 恒带；blockedFeedback 仅 blocked 态才有结构化体
      "blockCount" -> node.blockCount.asJson,
      "createdAt" -> node.createdAt.asJson,
      "completedAt" -> node.completedAt.asJson,
      "ttlLeftSec" -> ttlLeft.asJson
    )
      // P1 out 边数组条件序列化（spec §2.2 #8）：Nil 不带键（无出环节点字段集零漂移
      // ——旧 None→null 键一并消失，消费方以缺键=无出边读取）；非空 → 规范化边数组
      // [{to,on,mode}...]。前端边渲染适配属批 F3（本批仅载荷形态）。
      val outFields =
        if node.out.isEmpty then Nil
        else List("out" -> OutEdge.canonical(node.out).asJson)
      // deprecated 三键条件序列化（观测面上下文经济学批 20260907 裁定③）：skill/
      // mcp/preset 移出基础集——仅存量节点非 None 才带（与 deps/plugins 条件字段
      // 同构；新建节点参数已退役（NODE_AGENT_RETIRED），字段集恒零漂移）。审计实测
      // 三键合计 ~5.6KB/载荷（131 节点全 null）。
      val legacyConfigFields =
        node.skill.toList.map(v => "skill" -> v.asJson) ++
          node.mcp.toList.map(v => "mcp" -> v.asJson) ++
          node.preset.toList.map(v => "preset" -> v.asJson)
      // hasResult 条件序列化（2026-09-05 载荷收敛）：节点持有结果全文才带——前端据此
      // 经 REST result 端点按需拉全文；无结果节点载荷字段集零变化。
      val hasResultFields =
        if node.result.exists(_.trim.nonEmpty) then List("hasResult" -> true.asJson) else Nil
      // wiringGap 条件键（W2 = O-B 必做 3，2026-09-12 批「out 可空置 + 接线即投递」）：
      // 无出边的节点携带，值为两态——"retained"（已持有结果 = 结果滞留待接线，最可行动）
      // 优先于 "pending"（wiring/pending = 建完尚未接线）。与 hasResult 同处同风格
      //（纯派生、零新持久字段）。**缺键 = 有 out**（消费方据此读；有 out 节点字段集
      // 字节级零漂移）。本批**只出键**——前端渲染归后续批。
      val wiringGapFields =
        if node.out.nonEmpty then Nil
        else if node.result.exists(_.trim.nonEmpty) then List("wiringGap" -> "retained".asJson)
        else if node.status == NodeLifecycle.Wiring || node.status == NodeLifecycle.Pending then
          List("wiringGap" -> "pending".asJson)
        else Nil
      // taskPreview 条件序列化（存量节点回退展示）：无 description 且有 task 才带，
      // 值 = task 首行 ≤80 字符（有 description 的新节点不带——字段集零漂移）。
      val taskPreviewFields = node.description match
        case Some(_) => Nil
        case None =>
          node.task.map(_.trim).filter(_.nonEmpty).map { t =>
            val firstLine = t.linesIterator.next().trim
            val preview = if firstLine.length > TaskPreviewMaxChars then firstLine.take(TaskPreviewMaxChars) + "…" else firstLine
            List("taskPreview" -> preview.asJson)
          }.getOrElse(Nil)
      // blockedFeedback 条件序列化（观测面上下文经济学批 20260907 裁定②）：
      // **仅 status==blocked 携带**——completed/failed/cancelled 的历史残留不进
      // 默认载荷（NodeList/REST/WS 单一序列化点同源瘦身，实测 9 个非 blocked
      // 节点曾泄漏 11.6KB，审计 §3.5）。历史参照：NodeList(detail=) 补挂 + 归档
      // 留痕；FeedbackRouter 重入协议消费当下反馈（store 直读不经载荷），零影响。
      // 入库侧封顶（detail 300 / suggestion 150）在 BlockedReader 单点。
      val feedbackFields =
        if node.status == NodeLifecycle.Blocked then
          node.blockedFeedback.toList.map { bf =>
            "blockedFeedback" -> Json.obj(
              "category" -> bf.category.asJson,
              "detail" -> bf.detail.asJson,
              "suggestion" -> bf.suggestion.asJson
            )
          }
        else Nil
      // deps 条件序列化（deps 设计 §1.1；与 blockedFeedback 条件字段同构）：
      // 非 Nil 才带——NodeEventPushSpec 的 NodeListKeys 字段集断言零改动（无 deps
      // 的节点 payload 字段集不变），前端增量渲染对缺键天然兼容。
      val depsFields = if node.deps.nonEmpty then List("deps" -> node.deps.asJson) else Nil
      // plugins 条件序列化（阶段 2b §B.4 第 3 步 + H-3①用户可见性；与 deps 同构）：
      // 非 Nil 才带——无分配节点的 payload 字段集零变化。
      val pluginFields = if node.plugins.nonEmpty then List("plugins" -> node.plugins.asJson) else Nil
      // notifyDispatcher 条件序列化（dispatch-notify 批 2026-09-05；与 deps 条件字段
      // 同构）：true 才带——未开启节点的 payload 字段集零变化（NodeList 上分发器可辨哪些
      // 节点会回流通知）。
      val notifyFields = if node.notifyDispatcher then List("notifyDispatcher" -> node.notifyDispatcher.asJson) else Nil
      // merge 条件序列化（mount-enforce 批 20260905 payload 契约；与 deps 条件字段
      // 同构）：仅 merge 节点带 "merge": true——缺省/缺失 = 非 merge（前端按缺省
      // 防御，非 merge 节点 payload 字段集零变化）。
      val mergeFields = if node.merge then List("merge" -> node.merge.asJson) else Nil
      // loop 条件序列化（LoopNode 批 2026-09-06；与 merge 条件字段同构）：
      // 仅 loop 节点带 "loop" 配置对象 + 运行态条件字段——缺省/缺失 = 非 loop
      // （前端按缺省防御，非 loop 节点 payload 字段集零变化）。运行态字段按
      // 条件带：loopRound（>0 才带）、loopPhase（running 才有）、
      // loopLastVerdict（非空才带，最近一次 FAIL 摘要）。
      val loopFields = node.loop match
        case Some(lc) =>
          val cfg = List("loop" -> Json.obj(
            "maxRounds" -> lc.maxRounds.asJson,
            "verify" -> lc.verify.asJson,
            "enabled" -> lc.enabled.asJson
          ))
          val roundField = if node.loopRound > 0 then List("loopRound" -> node.loopRound.asJson) else Nil
          val phaseField = node.loopPhase match
            case Some(p) => List("loopPhase" -> p.asJson)
            case None => Nil
          val verdictField = node.loopLastVerdict match
            case Some(v) if v.nonEmpty => List("loopLastVerdict" -> v.asJson)
            case _ => Nil
          cfg ++ roundField ++ phaseField ++ verdictField
        case None => Nil
      // bgWait 条件序列化（僵尸收敛批 2026-09-06）：仅 bg-wait 自持的 running 节点
      // 带——命中才产出，未命中节点 payload 字段集零变化（既有条件字段断言零影响）。
      val bgWaitFields = node.bgWait.toList.map(w => "bgWait" -> w.asJson)
      // 未申报计时条件序列化（noderpt 批 A 段 2026-09-11）：**仅 status==running 且
      // 计时已置**才带——两键成对（reportPendingSince = 交棒时刻 ms；reportReminderCount
      // = 已注入拍数）。未命中节点字段集零变化（与 bgWait/deps 条件字段同构）；终态
      // 节点即使字段有残留也不带（与 bgWait 的「不泄漏过期态」同纪律）。观测面：
      // 前端/取证可直接辨「已交棒待申报」的 Running 节点。
      val reportPendingFields =
        if node.status == NodeLifecycle.Running && node.reportPendingSince.isDefined then
          List(
            "reportPendingSince" -> node.reportPendingSince.asJson,
            "reportReminderCount" -> node.reportReminderCount.asJson
          )
        else Nil
      // destroyAt 条件序列化（noderpt 批 B 段 2026-09-11）：**仅终态且已登记**才带——
      // 值是窗口到点的 ms 时刻（前端/取证可算「还有多久销毁」），窗口结束即随字段清零
      // 消失。未登记节点（含全部 Running 节点）payload 字段集零变化（与 notifySentAt
      // 的「仅异常终态携带」同纪律）。
      val destroyAtFields =
        if NodeLifecycle.Terminal.contains(node.status) then node.destroyAt.toList.map(t => "destroyAt" -> t.asJson)
        else Nil
      // P2 retry 条件序列化（spec §2.3；与 merge 条件字段同构）：仅配置了 retry 的
      // 节点带——未配置节点 payload 字段集零变化。gen 条件序列化（与 loopRound 同构）：
      // >0 才带（0 = 未回跳过，缺键 = 同义）——前端「attempt N」徽标数据载体（渲染
      // 消费归批 F3，本批只做字段+载荷暴露）。
      val retryFields = node.retry.toList.map(r => "retry" -> Json.obj(
        "upstream" -> r.upstream.asJson, "max" -> r.max.asJson))
      val genFields = if node.gen > 0 then List("gen" -> node.gen.asJson) else Nil
      // notifySentAt 条件序列化（归档语义批 2026-09-07「送达即移」）：**仅异常终态
      // （failed/cancelled）且已上报（notifySentAt.isDefined）才带**——前端链判据
      // （flowMapArchive.js chainEligible）以此判断异常终态「已上报可归档」；与
      // deps/plugins 同构条件字段，非命中不带 = 零字段漂移。completed 无上报要求恒不带。
      val notifySentAtFields =
        (if node.status == NodeLifecycle.Failed || node.status == NodeLifecycle.Cancelled then
           node.notifySentAt.toList.map(t => "notifySentAt" -> t.asJson)
         else Nil)
      // chainId / chainIds 条件序列化（链级抽象 P0 + U1 多链归属批；与 deps/plugins
      // 条件字段同构）：chainId 仅调用方注入（FlowMapStore.chainAttrsOf 单点判据：
      // 所属合并集分量成员数 ≥2）才带——孤立单节点链与未注入调用方（如归档 REST
      // 端点）payload 字段集零变化；chainIds 仅 **merge 节点**且可达成员链数 ≥2 才带
      // （普通节点恒不带 = 单值 chainId 语义不变，作者裁定①），值 = 主链 id 首项 +
      // 全量成员链（无上限、无降级）。
      val chainFields = chainId.toList.map(c => "chainId" -> c.asJson) ++
        chainIds.filter(_.size >= 2).toList.map(ids => "chainIds" -> ids.asJson)
      // pendingSuccession 条件序列化（取消静默死锁修复批 R4；与 deps/plugins 同构）：
      // 非空才带——无「待承接」槽位的节点 payload 字段集零变化。前端渲染面本批零
      // 改动（未知键天然忽略，仅作可见性载体）；分发器侧读 NodeList 即可见。
      val pendingSuccessionFields =
        if node.pendingSuccession.nonEmpty then List("pendingSuccession" -> node.pendingSuccession.asJson)
        else Nil
      Json.obj((baseFields ++ outFields ++ legacyConfigFields ++ hasResultFields ++ wiringGapFields ++ taskPreviewFields ++ depsFields ++ feedbackFields ++ pluginFields ++ notifyFields ++ mergeFields ++ loopFields ++ bgWaitFields ++ reportPendingFields ++ destroyAtFields ++ retryFields ++ genFields ++ notifySentAtFields ++ pendingSuccessionFields ++ chainFields)*)

/** Flow Map 活动区（§2.6，磁盘 flow-map.json）。 */
case class FlowMapState(
  v: Int = 1,
  project: String,
  updatedAt: Long,
  nodes: Map[String, NodeDef] = Map.empty
)

object FlowMapState:
  given Configuration = Configuration.default.withDefaults
  given Codec[FlowMapState] = ConfiguredCodec.derived

/** Flow Map 归档区（§2.6 整链全终态移入——裁定④「TTL 分开」批 2026-09-07；
  * 磁盘按派发批次分文件 `flow-map-archive/<batchId>.json`；结果全文保留）。
  * 内存模型：全量水合（findNode/投递链/detail/REST 零改动）；分批只是落盘布局。 */
case class FlowMapArchive(
  project: String,
  nodes: Map[String, NodeDef] = Map.empty
)

object FlowMapArchive:
  given Configuration = Configuration.default.withDefaults
  given Codec[FlowMapArchive] = ConfiguredCodec.derived

/** 归档批次分文件（裁定④「TTL 分开」批）：`<workspace>/.nebflow/flow-map-archive/<batchId>.json`
  * 一批一文件。batchId = `chain-<分量内 createdAt 最早节点 id>`——链级抽象 P0（C4）
  * 起为拓扑链 id（派生单点 FlowMapStore.topologicalChains，取代旧 ≤120s 时间批聚簇；
  * id 生成规则与旧口径同构，前端面板/分文件名消费方式不变；存量旧时间批 id 零迁移
  * 共存，C5）。nodes 落盘经 result 摘要+指针 / task 剥除手术（FlowMapStore
  * persistBatchFiles）。 */
case class FlowMapArchiveBatch(
  project: String,
  batch: String,
  archivedAt: Long,
  nodes: Map[String, NodeDef] = Map.empty
)

object FlowMapArchiveBatch:
  given Configuration = Configuration.default.withDefaults
  given Codec[FlowMapArchiveBatch] = ConfiguredCodec.derived

/** 内存批次索引条目（裁定④）：nodeIds 为批次成员集（落盘写粒度判定 + REST 按批
  * 组装的数据源）；不进 NodeDef——批次归属只活在批次索引与分文件名。跨区续做分量
  * 再归档命中同链 id 时 nodeIds 取并集合并（FlowMapStore sweep，防旧批成员孤儿化）。 */
case class ArchiveBatchMeta(
  id: String,
  archivedAt: Long,
  nodeIds: Set[String]
)

/** 链谱系边（链级抽象 P0 · D3）：via ∈ {in, out, deps} 标注连接语义——deps 为弱关联
  * （只等上游 completed 信号、不投载荷，deps 设计 §1.1）；同一双端经不同类型边连接
  * 时各自保留一条（in 镜像边与 out 边语义不同）。方向恒上游→下游（in/deps 由下游
  * 持有、翻转标注；out 原生即上游→下游）。 */
case class ChainEdge(
  from: String,
  to: String,
  via: String
)

/** 拓扑链（链级抽象 P0 · spec §2.1）：活动∪归档合并节点集上的弱连通分量，派生单点
  * FlowMapStore.topologicalChains 的返回载体。链 id = `chain-<分量内 createdAt 最早
  * 节点 id>`（与旧时间批 id 规则同构）；entries = 分量内 in=Nil ∧ deps=Nil 双空（D2，
  * 与创建期入口判据对齐）；ends = 分量内 out 无节点目标的成员（仅 Nebula/悬空/
  * out=Nil 都算，D7）；memberIds 按 createdAt 升序（平局 id 兜底）。纯派生量——
  * NodeDef 本体不加字段（C1）。 */
case class ChainInfo(
  id: String,
  entries: List[String] = Nil,
  ends: List[String] = Nil,
  memberIds: List[String] = Nil,
  edges: List[ChainEdge] = Nil
)

/** Project 实体定义（§1.1，projects/<name>/project.json）。 */
case class ProjectDef(
  name: String,
  description: Option[String] = None,
  workspace: String,
  agentFile: String,
  /** blocked 反馈档位（设计 §7.1）：auto（默认，自动重入）| escalate-only（blocked 直接升级 Nebula）。
    * 可选字段——存量 project.json 无此字段时反序列化默认 None → 挂载时取 auto。 */
  feedbackMode: Option[String] = None,
  createdAt: Long,
  /** 归档标记（迁移方案 v2 §6.1）：只有显式人工动作（面板归档按钮 → POST
    * /api/projects/<name>/archive）会设置；无任何自动归档路径。归档后项目不出现在
    * 项目列表（ProjectStore.list 源头过滤——面板 API 与 startupMount 同源跳过），
    * workspace 文件零触碰（零删除零移动）。解码侧可选——存量 project.json 无此键 →
    * withDefaults 解码 None（零迁移）；磁盘写入走 ProjectStore.archive 手术式原位
    * 插键（非全量 re-encode），未归档项目文件不含此键（条件序列化，对齐 deps 风格）。
    * 单程语义：恢复 = 手工删除 project.json 中 archived/archivedAt 两键（本批无 UI）。 */
  archived: Option[Boolean] = None,
  archivedAt: Option[Long] = None
)

object ProjectDef:
  given Configuration = Configuration.default.withDefaults
  given Codec[ProjectDef] = ConfiguredCodec.derived
