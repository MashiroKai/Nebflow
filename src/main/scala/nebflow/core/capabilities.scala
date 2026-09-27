/* Phase 5 解耦(行为保持重构,2026-09-25)。 */
// capabilities.scala — core ← agent 依赖倒置的窄能力 trait(D 步试点)。
//
// 背景:agent.SharedResources 是横穿 8 个包的定位器(agent/SharedResources.scala 的
// import 面),core 侧引它即传递依赖半个世界。D 步按『行为面』(非『字段面』)抽窄
// 能力,形态与 C 步 ports.scala 相同:
//   - 接口在 core,签名零 agent/gateway 类型;实现在 agent 原地(SharedResources
//     混入本组 trait,方法体 = core 原内联逻辑逐字迁移,零行为差);接线在装配点
//     (GatewayMain / spec 构造处按窄类型传入)。
//
// 🔴 设计约束(Phase 5 评审裁定,D 步起生效):agentRegistry 字段类型是
//   `Ref[IO, Map[String, AgentRecord]]`(AgentRecord 是 agent 类型)——**不可原样
//   暴露**;只暴露行为查询(如 processingSessionIds)。后续批次按用面增补成员时
//   同样受此约束(长出 agent 类型签名会被 check-scala-layers 门禁打回)。
//   actorSystem/projectRoot/contextWindow 等底层类型字段不经本组定位器,由
//   调用点以原类型直接传参(ActorSystem 本就在底层包 actor)。
// 2026-09-27 裁定 dwfq-5c7a31ea-1:AgentRecord 已随②移入 actor,本约束前提失效,见该裁定。
//
// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-A=A1):AgentRegistryPort 扩成员逐字镜像
// `agentRegistry: Ref[IO, Map[String, AgentRecord]]`(actor 合法向下依赖;上方 D 步约束
// 注释为历史记录一字不动,前提失效说明见裁定);core 消费点的 .get/.update/.modify 零改动。

package nebflow.core

import cats.effect.{IO, Ref}
import nebflow.actor.AgentRecord

/**
 * 统一注册表(`SharedResources.agentRegistry`)的窄能力投影(D 步)。core 当前只
 * 消费一个行为面:hot-restart 五域判定 F3 的「活跃 turn 会话」读数(HotRestart
 * 原内联过滤)。ProjectActor/TaskStuckWatcher 等对 `_.ref`(`ActorRef[AgentCommand]`)
 * 的取用是 agent 协议类型,不在本投影可达面内——属后续批次。
 */
trait AgentRegistryPort:

  /** status == Processing 的会话 id 清单(hot-restart 五域判定 F3 的唯一读数)。 */
  def processingSessionIds: IO[List[String]]

  // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-A=A1):镜像 SharedResources.agentRegistry
  // 的原样成员(AgentRecord 已在 actor 包,core→actor 合法;行上方法体/字段由 agent 侧
  // SharedResources 既有字段实现,零行为差)。
  /** 统一注册表原样镜像(sessionId → AgentRecord;AgentRecord ∈ actor,见裁定)。 */
  def agentRegistry: Ref[IO, Map[String, AgentRecord]]
end AgentRegistryPort

/** 在飞 sub-agent 任务的最小视图(D 步 core 侧自建瘦类型,零 agent 符号)。 */
final case class RunningSubAgentTask(
  taskId: String,
  parentSessionId: String,
  source: String
)

/**
 * `SharedResources.subAgentTaskStore`(agent.SubAgentTaskStore)的窄能力投影
 * (D 步)。core 当前只消费「在飞任务清单」一个行为面(hot-restart 五域判定 F2
 * 读数);findByTaskId/recordTask/updateStatus 等读写面仍由 agent 侧消费者直用
 * (AgentControlTool/DelegateTool/SubTaskTool,豁免清单 reason=后续批次路径),
 * 待后续批次按用面在此增补。
 */
trait SubAgentTaskPort:

  /** 在飞(status == running)的 sub-agent 任务清单。 */
  def findRunningTasks: IO[List[RunningSubAgentTask]]
end SubAgentTaskPort
