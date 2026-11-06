package nebflow.core.delegate

import cats.effect.{IO, Ref}
import nebflow.actor.{ActorRef, AgentCommand, AgentRecord}

/**
 * actor-tree spec v1 §13.2 #P2 / 条款 **R-3a** —— `kernel` 身份**不得生子 actor** 的
 * spawn 期硬闸（判据单点，core 侧纯逻辑 + 一次 registry 读数）。
 *
 * == 为什么需要这道闸 ==
 * 本批之前，「kernel 不派生」只靠**工具面裁剪**兑现（`kernelFace` 从工具清单里摘掉
 * Subagent / AgentFlow / node 四件 / delegate_report 侧减法）。工具面是装配面的减法：
 * `buildToolList` 一旦漏一件、某条恢复腿绕开装配、或新增一个派生入口，kernel 会话就
 * 能派生——而**代码层没有任何断言**拦住它。R-3a 要求的是一条**spawn 期**的不变量
 * （拒绝点是 spawn 动作本身，不是「模型看不到工具」这种间接保证）。
 *
 * == 🔴 只读侦察腿豁免（tool-face-agentflow-retire 批 2026-11-05，作者逐字裁定
 * 「改 R-3a，只放行只读 subagent」）==
 * kernel 腿补上 `Subagent` 工具（作者裁定一）后，「kernel 一律不许生子 actor」会让
 * 这个工具变成**按了必报错的按钮**（生产链路 `AgentSessionExecution` 恒传
 * `agentActorRef=Some(ctx.self)`，父子关系成立 ⇒ 旧闸必拒）。作者裁决 = **放宽到
 * 最小面**：kernel 身份**只**放行 `def == subagent` 的子 actor 生成，其余任何 def、
 * 任何子 actor 一律照旧拒绝。这不是「kernel 一律放行」：
 *   - 判据只看**子会话的 def 名**（[[ReadOnlyReconAgentName]]，单一真相源 =
 *     `BuiltinAgents.SubagentName`），不看调用方、不看参数——被派生的 agent def 由
 *     内置表解析（`BlockingSubagent` 走 `BuiltinAgents.entry(defName)`），工具面由
 *     `AgentCore.SubagentFixedTools` 机制固定（五件只读，且 `subagent` 在
 *     `ConvergedAgentNames` 内 ⇒ agent.json 声明不授能），所以放行面**无法被扩写**；
 *   - 副产品语义：`SubAgentTaskStore` 会把父会话 `orchestrationMode` 升成
 *     `subagent-star`（如实显示，作者 2026-11-05 裁定不做 kernel 豁免）。
 *
 * == 判据（双重身份，并集）==
 *   1. registry 模式记录：`DelegateRegistry.Record.mode == "kernel"`（delegate-param-
 *      decisions 批之后的权威读面）；
 *   2. 会话 id 前缀 `delegate-kernel-`（before-merge 存量身份；记录缺席时唯一可用
 *      的证据，见 `SessionStore.DelegateId` / `AgentActor.isKernelSession` 同源口径）。
 *
 * == 零行为变化的三个面（同 spec K2/K3 的钉）==
 *   - `dispatcher` 模式 delegate：照常 spawn（它是**唯一**被允许派生的 delegate 形态）；
 *   - 无父会话（节点 / 分发器 / flow 轨，`parentRef = None`）：不判、不拦；
 *   - 父 ref 在 registry 里查不到（历史装配、裸 actor 父、测试夹具）：不判、不拦——
 *     本闸只对**被确证为 kernel**的父身份生效，绝不拿「查不到」当拒绝理由（把它当
 *     拒绝理由会让所有未登记的调用方立刻停摆，那是另一批的语义）。
 */
object KernelChildSpawnGate:

  /**
   * 唯一被放行的子 agent def 名（只读侦察腿）。单一真相源 =
   * `BuiltinAgents.SubagentName`（"subagent"）——该 def 的工具面由
   * `AgentCore.SubagentFixedTools` 机制固定为五件只读（Read/Glob/Grep/WebSearch/
   * WebFetch），无写、无 shell、无消息。
   */
  val ReadOnlyReconAgentName: String = nebflow.core.entity.BuiltinAgents.SubagentName

  /**
   * 历史 kernel 会话 id 前缀（before-merge 存量身份；与 `AgentActor.isKernelSession`
   * 同源口径——两处必须一致，改词先改这里）。
   */
  val LegacyKernelPrefix: String = "delegate-kernel-"

  /** 闸的拒绝错误：文本即可读判词（调用方 `attempt` 后直接呈给模型/日志）。 */
  final class Refused(val detail: String) extends RuntimeException(detail)

  /**
   * 纯判据：父会话身份的 kernel 证据（`Some` = 它是 kernel，值是命中的那条判据）。
   *
   * 两条判据的**并集**：模式记录与 id 前缀任一命中即认；都未命中 = 非 kernel。
   */
  def kernelEvidence(sessionId: String, modeWord: Option[String]): Option[String] =
    if modeWord.contains(DualModeConfig.ModeKernel) then
      Some(s"registry mode record = '${DualModeConfig.ModeKernel}'")
    else if sessionId.startsWith(LegacyKernelPrefix) then
      Some(s"legacy session id prefix '$LegacyKernelPrefix'")
    else None

  /**
   * 拒绝文本：**说清是谁、被哪条不变量拒、该怎么办**（三件齐，缺一件模型只能瞎猜）。
   *
   * @param parentSessionId 父会话 id（被拒的 kernel 身份）
   * @param childSessionId  试图派生出来的子会话 id
   * @param childAgent      被派生的 agent def 名（判据输入；也是「为什么不是那份豁免」的证据）
   * @param evidence        命中的判据（[[kernelEvidence]] 的返回值）
   */
  def message(parentSessionId: String, childSessionId: String, childAgent: String, evidence: String): String =
    s"kernel session '$parentSessionId' cannot spawn a child actor (child '$childSessionId', agent def '$childAgent'): " +
      "invariant R-3a (actor-tree spec v1 §13.2 #P2) — the kernel leg owns the whole task and derives nothing, " +
      s"so fan-out is refused at spawn time. The ONE exception is the read-only recon leg (agent def " +
      s"'$ReadOnlyReconAgentName' — five read-only tools, nothing writable): '$childAgent' is not it. " +
      s"Judgement: $evidence. " +
      "What to do: for read-only recon call `Subagent(prompt=...)` (not a spawn you construct yourself); " +
      "if the work must fan out into sub-actors, spawn with Delegate(mode=\"dispatcher\"); " +
      "or do the work inside this session as one actor. " +
      "(KERNEL_CHILD_SPAWN_REFUSED)"

  /**
   * 只读侦察腿豁免判据（纯函数，spec 直驱）：**只**认子会话 def 名等于
   * [[ReadOnlyReconAgentName]]。名字是唯一输入——不看调用方、不看会话 id、不看参数，
   * 所以「换个说法」或「传个参」都换不出第二个放行面。
   */
  def readOnlyReconExempt(childAgent: String): Boolean =
    childAgent == ReadOnlyReconAgentName

  /**
   * 纯判据表（spec K5 的载体）：`Right(())` = 放行，`Left(text)` = 拒绝原因原文。
   *
   * @param parentSessionId 已解析的父会话身份；`None` = 无父 / 未解析 ⇒ 恒放行
   * @param modeWordOf      会话 id ⇒ registry 模式词（生产 = `DelegateRegistry.modeFaceOf`；
   *                        形参注入使本表零端口、零落盘，可直接单测）
   * @param childSessionId  被派生的子会话 id
   * @param childAgent      被派生的 agent def 名（kernel 身份下唯一放行面 = 只读侦察腿）
   */
  def judge(
    parentSessionId: Option[String],
    modeWordOf: String => Option[String],
    childSessionId: String,
    childAgent: String
  ): Either[String, Unit] =
    parentSessionId match
      case None => Right(())
      case Some(sid) =>
        kernelEvidence(sid, modeWordOf(sid)) match
          case None => Right(())
          // 只读侦察腿：kernel 身份下**唯一**被放行的子 actor 生成（作者 2026-11-05
          // 「改 R-3a，只放行只读 subagent」）。其余 def 一律落到下面的 Left。
          case Some(_) if readOnlyReconExempt(childAgent) => Right(())
          case Some(evidence)                            => Left(message(sid, childSessionId, childAgent, evidence))

  /**
   * spawn 期闸（IO 面）：`parentRef → registry → 父会话身份 → [[judge]]`。
   *
   * 身份来源**只有** parentRef→registry 一条（与 `AgentActor` 反查监听者同款：
   * `rec.ref == r` 的唯一匹配）；解析不出 = 不判（见类注释第三条零变化面）。
   */
  def check(
    registry: Ref[IO, Map[String, AgentRecord]],
    parentRef: Option[ActorRef[AgentCommand]],
    childSessionId: String,
    childAgent: String
  ): IO[Unit] =
    registry.get.flatMap { reg =>
      val parentSessionId = parentRef.flatMap(r => reg.collectFirst { case (sid, rec) if rec.ref == r => sid })
      judge(parentSessionId, sid => DelegateRegistry.modeFaceOf(Some(sid)), childSessionId, childAgent) match
        case Right(_)   => IO.unit
        case Left(text) => IO.raiseError[Unit](Refused(text))
    }

end KernelChildSpawnGate
