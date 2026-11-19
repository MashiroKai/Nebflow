package nebflow.core.plugin

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/**
 * PluginLifecyclePlan —— 统一生命周期计划（插件分发与生命周期协议升级草案 §6.3；A 期
 * §9「把已有安装/更新入口接到统一计划」）。
 *
 * planId **绑定五个身份面**：操作 | 包名 | 候选摘要 | 当前摘要 | 策略版本——任一变化 ⇒
 * id 变化；同输入 ⇒ 确定性相等（可作幂等面/审计对账键）。版本对**不绑**（展示面：
 * 同摘要同策略下的版本字符串只是渲染，不构成新计划身份）。
 *
 * 策略版本形态 `plugin-ledger@<schema>:<policy>`（[[policyVersionOf]] 单点派生——
 * 账本 schema 位 + 作者逐包策略；两条腿共用，禁第二套拼法）。
 *
 * §7 输出面（成功文案 / 审计行 / UpdateResult）由 planId 派生；[[render]] 是一行
 * 审计形态（日志可读可截）。
 */
object PluginLifecyclePlan:

  /** 生命周期操作（§6.3 首两态；卸载出册不在本面）。 */
  enum Operation(val label: String):
    case Install extends Operation("install")
    case Update extends Operation("update")

  /**
   * 一次安装/更新计划。`candidate*` = 本世代字节（摘要 64-hex + 版本串）；
   * `current*` = 在位世代（首装/自愈缺失 ⇒ None）；`policyVersion` 见类注释。
   */
  final case class Plan(
    operation: Operation,
    name: String,
    candidateVersion: String,
    candidateDigest: String,
    currentVersion: Option[String],
    currentDigest: Option[String],
    policyVersion: String
  )

  /** 计划身份：`plan-` + 16 hex（绑定五面；日志/审计行可读可截）。 */
  def planId(p: Plan): String =
    val face = List(p.operation.label, p.name, p.candidateDigest, p.currentDigest.getOrElse(""), p.policyVersion)
      .mkString("\u0000")
    val hex = MessageDigest.getInstance("SHA-256").digest(face.getBytes(UTF_8)).map("%02x".format(_)).mkString
    s"plan-${hex.take(16)}"

  /** 一行审计形态：`<planId> <op> <name> <版本对> (candidate <摘要前缀>…) policy <策略版本>`。 */
  def render(p: Plan): String =
    val versionPair = p.currentVersion match
      case Some(cur) => s"$cur → ${p.candidateVersion}"
      case None => p.candidateVersion
    s"${planId(p)} ${p.operation.label} ${p.name} $versionPair " +
      s"(candidate ${p.candidateDigest.take(8)}…) policy ${p.policyVersion}"

  /** 策略版本单点派生：`plugin-ledger@<schema>:<policy>`（安装/更新两腿共用）。 */
  def policyVersionOf(policy: PluginDistributionTrust.UpdatePolicy): String =
    s"plugin-ledger@${PluginSourceLedger.Schema}:${policy.label}"

  /** 安装腿便捷构造（首装：current 两面 None）。 */
  def forInstall(
    name: String,
    version: String,
    digest: String,
    policy: PluginDistributionTrust.UpdatePolicy
  ): Plan =
    Plan(
      operation = Operation.Install,
      name = name,
      candidateVersion = version,
      candidateDigest = digest,
      currentVersion = None,
      currentDigest = None,
      policyVersion = policyVersionOf(policy)
    )

end PluginLifecyclePlan
