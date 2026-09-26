package nebflow.agent

import java.util.UUID

/**
 * 多 AskUser 并发批（#250 第五项，2026-09-13 作者裁定「6 项全补」）——
 * interaction requestId 的**单点生成器**。
 *
 * 改前现象（代码判据，台账 §1「覆盖」行 + §4「requestId 的完整落点链」）：
 * 生成点各自内联 `java.util.UUID.randomUUID().toString.take(8)` —— **32 bit**
 * 随机段、**无作用域标识**，而 requestId 是 hub `pending: Map[requestId → PendingRequest]`
 * 的**全局唯一键**（`InteractionHub.scala:128-139`）。permission / AskUser（阻塞 +
 * 非阻塞两档）/ SendConfirm / 工作区面板五条来源共用同一个命名空间：碰撞 ⇒ 后到者
 * 覆盖前者（`_ + (id -> …)`），被覆盖的槽位**永不回收**而请求方继续等（R1 起等待
 * 无超时）——即「静默丢弃一个等待中的提问」。
 *
 * 现口径（本文件是唯一生成点）：
 *   - 随机段 `RandomHexChars` = 16 hex（60 bit 随机位：UUID v4 前 16 hex 含 4 bit
 *     版本位），生日界在 2^30 次生成量级；
 *   - **作用域前缀**（ask / asknb / perm / confirm / panel）使跨类碰撞在结构上
 *     不可能（前缀不同 ⇒ 字符串必不同），随机段只需保证类内唯一。
 *
 * 熵强化只动生成侧：requestId 对全部消费面仍是**不透明字符串**（hub Map 键、
 * 前端 `data-request-id` / `dirPickCards` 键、`AskUserAnswerBridge` 的 ActorPath
 * segment、审计行字段）——无消费面改动，无新失败路径（`newId` 不抛）。
 */
object InteractionRequestId:

  /**
   * 随机段字符数（hex）。16 hex = 60 bit 有效熵；改回 8（旧口径 32 bit）会让
   * `InteractionRequestIdSpec` 的熵断言确定性变红 —— 该 spec 即本项的负控哨兵。
   */
  val RandomHexChars: Int = 16

  /** 阻塞档 AskUserQuestion（`AskUserQuestionTool.askUser`）。 */
  def forAskUser(): String = newId("ask")

  /**
   * 非阻塞档 AskUserQuestion（`AskUserQuestionTool.askUserNonBlocking`）。
   * 与阻塞档**前缀不同**：两档可同时挂在同一会话上，槽位不得互相覆盖。
   */
  def forAskUserNonBlocking(): String = newId("asknb")

  /** 权限卡（`AgentCore.sendPermissionRequest`）。 */
  def forPermission(): String = newId("perm")

  /** SendMessage ask 档确认卡（`SendConfirm.ask`）。 */
  def forSendConfirm(): String = newId("confirm")

  /** ProjectCreate 工作区选择面板（`NodeTools.ProjectCreateTool` 的 pathPanel）。 */
  def forDirPanel(): String = newId("panel")

  /**
   * 生成 `scope-<random>`。scope 逐字符过滤为 `[A-Za-z0-9]`（过滤后为空 ⇒ `x`）——
   * 生成器在任何调用路径上都不得成为新的失败点，故**不抛异常**、不做 `require`。
   */
  def newId(scope: String): String =
    val safe = scope.filter(c => c.isLetterOrDigit).take(16)
    val hex = UUID.randomUUID().toString.replace("-", "")
    s"${if safe.isEmpty then "x" else safe}-${hex.take(RandomHexChars)}"

  /** 随机段（前缀之后的部分）——判据/自检用。 */
  def randomPart(id: String): String = id.dropWhile(_ != '-').drop(1)
end InteractionRequestId
