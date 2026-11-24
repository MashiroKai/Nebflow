package nebflow.actor

import cats.effect.{IO, Ref}

/**
 * 外部 CLI 执行器在**统一注册表**里的登记面（作者裁定4「登记 AgentKind.External」，
 * 2026-11-05 09:38）。
 *
 * == 裁定原文与依据 ==
 * 「外部 CLI 腿（external-*，无 AgentActor）：要不要登记进 AgentKind？ → **登记
 * AgentKind.External**（推荐）」。设计件 `actor-as-agent-framework-creation-paths-and-
 * invariants__20261104_214436__delegate-nebflow-f3849ed1.md` §1.4 #13 给出落地口径
 * **(a)**：「给它一个 `ExternalAgentRef` 适配器并登记进 `agentRegistry`（新增
 * `AgentKind.External`），让『所有 agent 都能被 list / cancel / 挂终态』这条不变量
 * 成立，而 loop 仍在外部进程」。
 *
 * == 诚实边界（设计件点名的两种口径，本实现取 (a) 并写死例外） ==
 * 外部执行器的「脑子」在另一个进程，**不可能**成为 `Behavior[AgentCommand]`。所以：
 *   - `ref = null`：本记录**没有** ActorRef。任何「往记录里投消息」的腿必须先判
 *     `kind != External`（下文 [[isMessagable]] 是唯一判据，消费点复用）。
 *   - **不参与监督与恢复**：不复活、不重启、不做 barrier——恢复由
 *     `ExternalOutputStore` / CLI resume 承担；取消由
 *     `ExternalSessionRunner.cancel`（杀进程树）承担。
 *   - 登记只覆盖「可见性 + 身份」：list 面能看到 `external-*` 行属于本批收益，
 *     但**没有任何一条新腿给它派活**。
 *
 * == 生命周期 ==
 * spawn 成功落 `DelegateRegistry` 之后登记；首次终态（`settle` 首胜）即摘除——
 * 与内部腿「registry 记录 = 活着」的语义对齐，死行不留。
 */
object ExternalAgentRecord:

  /**
   * 记录能否接收 actor 消息。`External` 唯一为 false —— 这是本文件存在的唯一理由：
   * 把「null ref」从一个散落的空值约定收成一个具名判据。
   */
  def isMessagable(kind: AgentKind): Boolean = kind != AgentKind.External

  /** 纯构造（无副作用，便于断言字段而不起进程）。 */
  def build(
    sessionId: String,
    rootSessionId: String,
    parentSessionId: String,
    startedAt: Long,
    status: AgentStatus = AgentStatus.Idle
  ): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      // 外部执行器进程内无 actor —— 本枚举唯一允许 null ref 的记录（见类注释）。
      ref = null,
      kind = AgentKind.External,
      rootSessionId = rootSessionId,
      // 父会话透传：孤儿/级联面按 parentSessionId 归属（外部腿已从孤儿处置面排除，
      // 但归属字段仍必须真——面板树与诊断读它）。
      parentSessionId = parentSessionId,
      startedAt = startedAt,
      status = status
    )

  /** 登记（幂等覆盖：同 id 续聊复用同一行）。 */
  def attach(registry: Ref[IO, Map[String, AgentRecord]], record: AgentRecord): IO[Unit] =
    registry.update(_ + (record.sessionId -> record))

  /** 摘除（终态/取消；幂等）。 */
  def detach(registry: Ref[IO, Map[String, AgentRecord]], sessionId: String): IO[Unit] =
    registry.update(_ - sessionId)
end ExternalAgentRecord
