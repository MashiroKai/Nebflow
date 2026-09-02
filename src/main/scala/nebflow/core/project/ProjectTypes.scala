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
  * 停止传播 + 触发重入的终态；永不过期（ttlExpireAt=None，待办语义 §1.4）。 */
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

/** Node 数据模型（§2.1 JSON 示例字段全量）。 */
case class NodeDef(
  id: String,
  name: String,
  agent: String,
  skill: Option[String] = None,
  mcp: Option[String] = None,
  worktree: Option[String] = None,
  preset: Option[String] = None,
  task: Option[String] = None,
  in: List[String] = Nil,
  out: Option[String] = None,
  /** 依赖连接（deps 设计 §1.1，主文档 20260902_flowmap-engine-evolution-design.md）：
    * 下游单侧持有、不回写上游（上游不知道自己被依赖——「不用其输出」的结构体现）。
    * 语义 = 只等上游完成信号（status==completed），不投递上游结果——下游输入 =
    * 自身 task（自足）；failed/cancelled/blocked ∉ completed → 不触发，下游保持
    * pending/wiring 可见。旧 flow-map.json 无此键 → withDefaults 解码为 Nil（零迁移）。 */
  deps: List[String] = Nil,
  deliveredTo: List[String] = Nil,
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
  ttlExpireAt: Option[Long] = None
)

object NodeDef:
  given Configuration = Configuration.default.withDefaults
  given Codec[NodeDef] = ConfiguredCodec.derived

/** NodeList 载荷同构的节点 JSON（NodeList 工具 / REST flow-map / WS 事件共用单一序列化点）。
  * WS 事件（nodeCreated/nodeUpdated/nodeRemoved）与快照永远同构，前端增量渲染可直接对齐
  * 字段集：{id, name, agent, skill, mcp, preset, status, in, out, hasWorktree, worktree,
  * result(≤500 字符摘要), retries, createdAt, completedAt, ttlLeftSec}。
  * skill/mcp/preset 为节点配置（子任务 C：Flow Map 卡片徽标与详情展示的数据源）。
  * deps 为条件字段（非 Nil 才带，与 blockedFeedback 同构——见下方 depsFields 注释）。 */
object NodePayload:
  def buildNodeJson(node: NodeDef, now: Long): Json =
    val ttlLeft = node.ttlExpireAt.map(t => Math.max(0L, (t - now) / 1000L))
    val baseFields = List(
      "id" -> node.id.asJson,
      "name" -> node.name.asJson,
      "agent" -> node.agent.asJson,
      "skill" -> node.skill.asJson,
      "mcp" -> node.mcp.asJson,
      "preset" -> node.preset.asJson,
      "status" -> node.status.asJson,
      "in" -> node.in.asJson,
      "out" -> node.out.asJson,
      "hasWorktree" -> node.worktree.isDefined.asJson,
      "worktree" -> node.worktree.asJson,
      "result" -> node.result.map(r => if r.length > 500 then r.take(500) + "…" else r).asJson,
      "retries" -> node.retries.asJson,
      // blocked 反馈重入（设计 §4.1）：blockCount 恒带；blockedFeedback 仅 blocked 态才有结构化体
      "blockCount" -> node.blockCount.asJson,
      "createdAt" -> node.createdAt.asJson,
      "completedAt" -> node.completedAt.asJson,
      "ttlLeftSec" -> ttlLeft.asJson
    )
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
      Json.obj((baseFields ++ depsFields ++ feedbackFields)*)

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
  createdAt: Long
)

object ProjectDef:
  given Configuration = Configuration.default.withDefaults
  given Codec[ProjectDef] = ConfiguredCodec.derived
