package nebflow.actor

/**
 * 根 agent 身份名的全仓单点（Nebula→Root 重命名 Phase，行为保持）：
 * 所有「根 agent 名」的判定 / 默认值 / 查找键统一引用 [[Name]]，值恒为旧名
 * `"Nebula"`（REBRAND.md 红线②③：本 Phase 不引入新名判定面，行为零变化）。
 * 未来双读挂点：更名落地时只改本对象（新名 + 兼容读旧名），全仓调用面零改动。
 */
object RootAgentIdentity:
  val Name: String = "Nebula"

  // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-F):本纯谓词自 agent.AgentCore 下沉
  // actor(签名零 agent 符号;AgentCore 不留转发别名,shim 禁令),全仓改指,实现逐字迁移。
  /**
   * 「Nebula 本体根会话」身份判据 —— **全仓唯一单点实现**（工具面按角色分化批
   * B1/B2，2026-09-13 作者裁定 T1=(a)）：
   *
   * {{{
   *   name == "Nebula" && depth == 0
   * }}}
   *
   * 两个分量各自的必要性：
   *  - `name == "Nebula"`：身份按名判（`AgentLibrary` 以名为唯一键），排除
   *    standalone 非 Nebula 的 WS 根会话 / team Manager / flow 入口等 depth=0 的
   *    其余根会话（T1=(a)：它们**不算** root）；
   *  - `depth == 0`：排除 `NodeDef.agent="Nebula"` 的**节点**会话（depth=1）——
   *    成员资格面按**名**判（`fixedToolsFor` / `exclusiveToolsFor`）⇒ 该形态照样
   *    持有 `AskUserQuestion`，只按名判会把它误放行到 root 变体（规格 §3.1 末）。
   *
   * **三个消费点，一处实现**：
   *  ① 定义期 schema 分组（[[buildToolList]]，第一性机制）；
   *  ② 运行期兜底闸（`ToolContext.isRootAgent` → `AskUserQuestionTool.call`）；
   *  ③ PopTool 身份闸（同批改为委托本单点）。
   * ⇒ **禁第二份同表达式**（含在 buildToolList 内联手写一份）；spec
   * `AskUserDualModeSpec` 有 grep 级静态断言。
   *
   * `agentDef = None`（REST 直调 / spec harness / 非 agent 上下文）⇒ **fail-closed**：
   * 非 Nebula 身份一律 false（与 PopTool 既有取舍同款）。形参取 `Option` 是为了让
   * 两个求值面（定义期有 `AgentDef`、运行期有 `Option[AgentDef]`）用**同一个**函数，
   * 而不是各写一份 `exists` 包装。
   *
   * **不采用**的同类判据（逐个理由见规格书 §3.1）：`SandboxPolicy.isNebulaRootSession`
   * （含 `sandboxEnabled` feature flag 分量，非身份分量）、`AgentRecord.kind ==
   * AgentKind.Root` 与 `rootSessionId == sessionId`（需 registry 查询 = IO + 依赖注册
   * 时序，且 kind 口径更宽：standalone 根会话也置 kind=Root）。
   */
  def isRootAgent(agentDef: Option[AgentDef], depth: Int): Boolean =
    agentDef.exists(_.name == RootAgentIdentity.Name) && depth == 0
end RootAgentIdentity
