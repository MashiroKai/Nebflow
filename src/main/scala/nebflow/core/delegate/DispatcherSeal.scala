package nebflow.core.delegate

import io.circe.Json

/**
 * DispatcherSeal —— **dispatcher 模式封存闸**（dispatcher-seal 批 2026-11-18 · 作者令
 * 逐字：「先不修复 派个批 报delegate的dispatcher模式封存 然后立即重启 让kernel来做」）。
 *
 * == 为什么封存（现象，2026-11-18 当日多次复现）==
 * dispatcher 模式的会话**反复挂死**：起链正常（`NodeList` → `ChainWrite(2 nodes)`），
 * 节点跑到终态（`NODE … REVIEW … COMPLETED`）之后**父会话再也不被唤醒**——不继续、
 * 不交回、不报错，一挂两小时。作者裁定：先不修这个 bug，把 dispatcher 模式**封存**，
 * 后续工作一律 `mode=kernel`。
 *
 * == 封存语义（不是删除）==
 *   - 派发时显式拒绝：`mode=dispatcher` ⇒ [[SealedMessage]]（错误码
 *     `DELEGATE_DISPATCHER_SEALED`）。**绝不静默降级**成 kernel（既有铁律：不许把
 *     未知/不可用能力悄悄降级——静默降级会让「封存是否生效」无法被验证）。
 *   - **实现全保留**：dispatcher 执行腿、registry 记录、读面（`KernelChildSpawnGate`
 *     的 mode 记录等）一行不动；封存的只是「新派发入口」。
 *   - **可恢复**：`stand.json` 顶层 `delegate` 节 `dispatcherMode` 键 ——
 *     `"sealed"`（**缺省**，含节/键缺失与非法词，fail-closed）/ `"enabled"`（恢复
 *     dispatcher 派发，行为与封存前一致）。恢复是作者的显式动作，改配置即生效
 *     （派发点现读），无需改代码、无需重启以外的迁移。
 *   - `mode=kernel` 与省略 mode 的路径**零触碰**（省略仍按既有规则落 kernel）。
 *
 * == 与 ModeParam 的分工 ==
 * [[ModeParam.resolve]] 保持纯词表解析（kernel|dispatcher 值域不变——dispatcher 仍是
 * **合法词**，只是封存态下不可**派发**）；封存判定在本对象（`gate`）——配置热读由
 * 派发点（`DelegateTool.dispatchLeg`，先例 = BackoffSupervisor 的
 * `delegateProgressNotifications` 热读）完成，本对象零 IO、零配置读取。
 */
object DispatcherSeal:

  /** `stand.json` 顶层节名（原样 JSON 进 `NebflowServiceConfig.delegate`）。 */
  val ConfigSection: String = "delegate"

  /** 节内开关键名。 */
  val ConfigKey: String = "dispatcherMode"

  /** 封存态（缺省）。 */
  val Sealed: String = "sealed"

  /** 恢复态（作者显式动作）。 */
  val Enabled: String = "enabled"

  /**
   * 封存拒绝文案（错误码 = `DELEGATE_DISPATCHER_SEALED`——一眼可辨是「封存」而非
   * 「参数写错」；错误码家族先例 = `DELEGATE_MODE_AUTO_REMOVED`）。含现象、出路
   * （mode=kernel）与恢复方法（stand.json 开关），双语。
   */
  val SealedMessage: String =
    "Delegate: mode=\"dispatcher\" is SEALED (2026-11-18) — dispatcher-mode sessions kept hanging after " +
      "nodes reached terminal states (the parent session was never re-awakened; recurring, 2h+ hangs). " +
      "This is a deliberate seal by the author, not a bad parameter: dispatch with mode=\"kernel\" instead. " +
      "The dispatcher implementation is fully retained; to re-enable it the author sets " +
      "delegate.dispatcherMode=\"enabled\" in stand.json (hot-read at dispatch time, no code change). " +
      "（dispatcher 模式已封存——节点终态后父会话反复不再被唤醒；请改用 mode=\"kernel\"。" +
      "实现已保留，恢复 = stand.json 里 delegate.dispatcherMode 置 \"enabled\"。）" +
      "(DELEGATE_DISPATCHER_SEALED)"

  /**
   * 配置节 fail-safe 解析（纯函数，零 IO）：`Some(Enabled)` 当且仅当节与键都在且值为
   * `"enabled"`（大小写/空白容忍）；节缺失 / 键缺失 / 非法词 ⇒ [[Sealed]]——封存是
   * 安全缺省方向（fail-closed：读不懂的配置绝不该把已封存的能力放出来）。
   */
  def modeFromConfigNode(raw: Option[Json]): String =
    raw
      .flatMap(_.hcursor.downField(ConfigKey).as[String].toOption)
      .map(_.trim.toLowerCase)
      .filter(_ == Enabled)
      .getOrElse(Sealed)

  /** 封存态判定（词表外的任何值都按封存处理——与 [[modeFromConfigNode]] 同向）。 */
  def isSealed(dispatcherMode: String): Boolean = dispatcherMode != Enabled

  /**
   * 派发闸（纯函数）：封存态下 dispatcher 显式拒（Left = 拒绝原文）；kernel / 未封存
   * ⇒ 原样放行。**不做静默降级**——Right 里绝不会出现把 dispatcher 改写成 kernel 的
   * 结果。
   */
  def gate(decision: ModeDecision, dispatcherMode: String): Either[String, ModeDecision] =
    if decision.mode == DualModeConfig.ModeDispatcher && isSealed(dispatcherMode) then Left(SealedMessage)
    else Right(decision)

  /**
   * 派发时模式解析（[[ModeParam.resolve]] 词表解析 + 封存闸，单点）：`Left` = 显式
   * 拒绝的原文（auto / merger / 非法词沿用 [[ModeParam]] 文案；封存拒用
   * [[SealedMessage]]）。`dispatcherMode` 由调用方热读后传入（本函数保持纯）。
   */
  def resolveForDispatch(modeParam: Option[String], dispatcherMode: String): Either[String, ModeDecision] =
    ModeParam.resolve(modeParam).flatMap(gate(_, dispatcherMode))
end DispatcherSeal
