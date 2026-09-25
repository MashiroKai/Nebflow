package nebflow.agent

/**
 * 根 agent 身份名的全仓单点（Nebula→Root 重命名 Phase，行为保持）：
 * 所有「根 agent 名」的判定 / 默认值 / 查找键统一引用 [[Name]]，值恒为旧名
 * `"Nebula"`（REBRAND.md 红线②③：本 Phase 不引入新名判定面，行为零变化）。
 * 未来双读挂点：更名落地时只改本对象（新名 + 兼容读旧名），全仓调用面零改动。
 */
object RootAgentIdentity:
  val Name: String = "Nebula"
