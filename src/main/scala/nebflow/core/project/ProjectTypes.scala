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
  * held（20260903 暂停/人在回路设计 §2.1，方案 B「节点 hold」）：节点完成后被
  * 人工闸门扣住——结果持久保存 + 全文通知 Nebula，但不投递不结算，等待
  * NodeEdit(release=true) 放行。**非终态**（Terminal 不加 held）——这是全部既有
  * 机械零修改正确工作的关键：sweepExpired 不扫、整链归档判定天然排除、
  * deps 闸门不触发、startNode 幂等跳过。 */
object NodeLifecycle:
  val Wiring = "wiring"
  val Pending = "pending"
  val Running = "running"
  val Completed = "completed"
  val Failed = "failed"
  val Cancelled = "cancelled"
  val Blocked = "blocked"
  val Held = "held"

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
  /** 人工闸点（20260903 暂停/人在回路设计 §2.1，方案 B）：true = 完成时不投递
    * 不结算，status→held（非终态）+ 结果全文通知 Nebula，等待 NodeEdit release。
    * 旧 flow-map.json 无此键 → withDefaults 解码为 false（零迁移）= 旧行为。 */
  hold: Boolean = false,
  deliveredTo: List[String] = Nil,
  /** V8 (2026-09-03): out=Nebula 投递记账——deliverToNebula 成功 offer 后落时间戳。
    * 与 deliveredTo（in barrier 判定，节点间沿边去重）完全分离，barrier 语义零改动；
    * 空 = 结果未达 Nebula（崩溃窗口 / 根 ref 缺失滞留）→ 周期重投扫描补投。
    * 旧 flow-map.json 无此键 → withDefaults 解码为 None（零迁移）。 */
  nebulaDeliveredAt: Option[Long] = None,
  status: String = NodeLifecycle.Wiring,
  result: Option[String] = None,
  retries: Int = 0,
  maxRetries: Int = 1,
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
  plugins: List[String] = Nil
)

object NodeDef:
  given Configuration = Configuration.default.withDefaults
  given Codec[NodeDef] = ConfiguredCodec.derived

/** NodeList 载荷同构的节点 JSON（NodeList 工具 / REST flow-map / WS 事件共用单一序列化点）。
  * WS 事件（nodeCreated/nodeUpdated/nodeRemoved）与快照永远同构，前端增量渲染可直接对齐
  * 字段集：{id, name, agent, skill, mcp, preset, description, status, in, out, hasWorktree,
  * worktree, retries, createdAt, completedAt, ttlLeftSec}。
  *
  * **载荷收敛（2026-09-05 Flow Map 精简批）**：默认载荷只含元数据——**节点结果全文与
  * 摘要都不进默认载荷**（原 result ≤500 字符摘要键移除；结果全文持久化在 per-node 文件
  * `results/<nodeId>.md`，经 REST GET /projects/<n>/flow-map/nodes/<id>/result 或
  * NodeList(detail=<nodeId>) 按需单点取）。条件字段（与 deps/hold/plugins 同构，非命中
  * 不带——载荷字段集对无此特征的节点零漂移）：
  *   - hasResult: 节点持有结果全文（前端据此发起按需拉取）；
  *   - taskPreview: 存量节点无 description 时的回退展示（task 首行 ≤80 字符截断）；
  *   - deps / blockedFeedback / hold / plugins：既有条件字段语义不变。
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
      "retries" -> node.retries.asJson,
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
      // hold 条件序列化（20260903 暂停/人在回路设计 §2.1；与 deps 条件字段同构）：
      // true 才带——无 hold 节点 payload 字段集不变（NodeEventPushSpec 零影响）。
      val holdFields = if node.hold then List("hold" -> node.hold.asJson) else Nil
      // plugins 条件序列化（阶段 2b §B.4 第 3 步 + H-3①用户可见性；与 deps 同构）：
      // 非 Nil 才带——无分配节点的 payload 字段集零变化。
      val pluginFields = if node.plugins.nonEmpty then List("plugins" -> node.plugins.asJson) else Nil
      Json.obj((baseFields ++ hasResultFields ++ taskPreviewFields ++ depsFields ++ feedbackFields ++ holdFields ++ pluginFields)*)

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
