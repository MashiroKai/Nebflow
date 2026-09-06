package nebflow.core.project

import io.circe.{Codec, Json}
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

/** 结构化 blocked 反馈（设计 §1.3 JSON 体）：BlockedReader 从节点最终输出解析。 */
case class BlockedFeedback(
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
  /** 创建必写的简短描述（≤200 字符）。默认 None = 存量兼容（旧数据零迁移）。 */
  description: Option[String] = None,
  in: List[String] = Nil,
  out: Option[String] = None,
  /** 依赖连接（deps 设计 §1.1，主文档 20260902_flowmap-engine-evolution-design.md）：
    * 下游单侧持有、不回写上游（上游不知道自己被依赖——「不用其输出」的结构体现）。
    * 语义 = 只等上游完成信号（status==completed），不投递上游结果——下游输入 =
    * 自身 task（自足）；failed/cancelled/blocked ∉ completed → 不触发，下游保持
    * pending/wiring 可见。旧 flow-map.json 无此键 → withDefaults 解码为 Nil（零迁移）。 */
  deps: List[String] = Nil,
  /** 合并节点标记（merge-node 批 20260905，方案 .nebflow/Spec/merge-node-plan.md）：
    * true = 批次产物落地收口节点——全部上游 completed 才触发（既有 in-barrier 语义）；
    * 上游 failed 时**不做 collect 占位结算**，转 blocked 可见终态不悬挂
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
  /** 最近一次 blocked 的结构化反馈（§1.4；重激活后保留供历史参照）。 */
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
  bgWait: Option[String] = None
)

object NodeDef:
  given Configuration = Configuration.default.withDefaults
  given Codec[NodeDef] = ConfiguredCodec.derived

/** NodeList 载荷同构的节点 JSON（NodeList 工具 / REST flow-map / WS 事件共用单一序列化点）。
  * WS 事件（nodeCreated/nodeUpdated/nodeRemoved）与快照永远同构，前端增量渲染可直接对齐
  * 字段集：{id, name, agent, skill, mcp, preset, description, status, in, out, hasWorktree,
  * worktree, createdAt, completedAt, ttlLeftSec}。
  *
  * **载荷收敛（2026-09-05 Flow Map 精简批）**：默认载荷只含元数据——**节点结果全文与
  * 摘要都不进默认载荷**（原 result ≤500 字符摘要键移除；结果全文持久化在 per-node 文件
  * `results/<nodeId>.md`，经 REST GET /projects/<n>/flow-map/nodes/<id>/result 或
  * NodeList(detail=<nodeId>) 按需单点取）。条件字段（与 deps/plugins 同构，非命中
  * 不带——载荷字段集对无此特征的节点零漂移）：
 *   - hasResult: 节点持有结果全文（前端据此发起按需拉取）；
 *   - taskPreview: 存量节点无 description 时的回退展示（task 首行 ≤80 字符截断）；
 *   - deps / blockedFeedback / plugins / merge：既有条件字段语义不变（merge
 *     仅 merge 节点带 "merge": true——mount-enforce 批 payload 契约，缺失=非 merge）。
 * skill/mcp/preset 为节点配置（skill/mcp 仅存量兼容展示——2b §B.4/H-11① deprecated）。 */
object NodePayload:
  /** taskPreview 截断上限（回退展示第一层，存量节点专用）。 */
  val TaskPreviewMaxChars: Int = 80

  def buildNodeJson(node: NodeDef, now: Long): Json =
    val ttlLeft = node.ttlExpireAt.map(t => Math.max(0L, (t - now) / 1000L))
    val baseFields = List(
      "id" -> node.id.asJson,
      "name" -> node.name.asJson,
      "agent" -> node.agent.asJson,
      "skill" -> node.skill.asJson,
      "mcp" -> node.mcp.asJson,
      "preset" -> node.preset.asJson,
      // 创建必写的简短描述（按需读取第一层）；存量无值 → null（前端回退 taskPreview）
      "description" -> node.description.asJson,
      "status" -> node.status.asJson,
      "in" -> node.in.asJson,
      "out" -> node.out.asJson,
      "hasWorktree" -> node.worktree.isDefined.asJson,
      "worktree" -> node.worktree.asJson,
      // blocked 反馈重入（设计 §4.1）：blockCount 恒带；blockedFeedback 仅 blocked 态才有结构化体
      "blockCount" -> node.blockCount.asJson,
      "createdAt" -> node.createdAt.asJson,
      "completedAt" -> node.completedAt.asJson,
      "ttlLeftSec" -> ttlLeft.asJson
    )
      // hasResult 条件序列化（2026-09-05 载荷收敛）：节点持有结果全文才带——前端据此
      // 经 REST result 端点按需拉全文；无结果节点载荷字段集零变化。
      val hasResultFields =
        if node.result.exists(_.trim.nonEmpty) then List("hasResult" -> true.asJson) else Nil
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
      val feedbackFields = node.blockedFeedback.toList.map { bf =>
        "blockedFeedback" -> Json.obj(
          "category" -> bf.category.asJson,
          "detail" -> bf.detail.asJson,
          "suggestion" -> bf.suggestion.asJson
        )
      }
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
      Json.obj((baseFields ++ hasResultFields ++ taskPreviewFields ++ depsFields ++ feedbackFields ++ pluginFields ++ notifyFields ++ mergeFields ++ loopFields ++ bgWaitFields)*)

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

/** Flow Map 归档区（§2.6 TTL 移入，磁盘 flow-map-archive.json；结果全文保留）。 */
case class FlowMapArchive(
  project: String,
  nodes: Map[String, NodeDef] = Map.empty
)

object FlowMapArchive:
  given Configuration = Configuration.default.withDefaults
  given Codec[FlowMapArchive] = ConfiguredCodec.derived

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
