package nebflow.core.hotupdate

import nebflow.core.hotrestart.RestartMode

/**
 * 更新请求来源（设计 §4 请求契约：设置页｜设备列表｜中继｜官网｜命令行）。
 *
 * 本批（批 1）只有设置页一个入口接线（D3）；设备列表/中继/官网/命令行的入口恢复
 * 属批 3（G7/G8）与批 6（G10）——本枚举先把来源字段的契约钉住，使后续批次只添调用点、
 * 不改契约。
 */
enum UpdateSource(val wire: String):
  case Settings extends UpdateSource("settings")
  case DeviceList extends UpdateSource("device-list")
  case Relay extends UpdateSource("relay")
  case Website extends UpdateSource("website")
  case Cli extends UpdateSource("cli")

/**
 * 更新通道（裁定 9）：请求契约带该字段，**不在界面暴露**——测试通道只由既有命令行
 * 面（`cli/SystemCommands.scala:32-88`）使用，本批原样保留、零改动。
 */
enum UpdateChannel(val wire: String):
  case Stable extends UpdateChannel("stable")
  case Beta extends UpdateChannel("beta")

/** 更新场景默认值。 */
object UpdateDefaults:

  /**
   * 更新场景等待空闲上限**独立值 = 300 秒**（作者 22:17 终裁第 7 项）。
   *
   * 与界面重启命令的 600 秒默认（`WebSocketRoutes.scala:1585-1619` 的 `waitTimeoutMs`）
   * **互不影响**：本值只被更新链路取用，既有重启命令那一处本批零改动。
   * 参数面口径对齐既有编排器的 `Timing` 面（`HotRestart.scala:364-393`）。
   */
  val AwaitIdleDefaultMs: Long = 300_000L

  /**
   * 现读等待上限（system prop `nebflow.hotupdate.awaitIdleMs`；未设 = 默认值）。
   * 注入面口径与既有 `HotRestart.Timing.fromSystemProps` 同款（验证可缩短等待）。
   */
  def awaitIdleMs: Long =
    sys.props
      .getOrElse("nebflow.hotupdate.awaitIdleMs", AwaitIdleDefaultMs.toString)
      .toLongOption
      .getOrElse(AwaitIdleDefaultMs)

  /**
   * **幂等键保留窗口 = 300 秒（本批冻结值）**——口径对齐契约件（分发器补充裁定 3④）：
   * 契约侧为「与编排器相位记录同寿命」，数值由本批冻结。
   *
   * 语义（冻结，逐条）：
   *   - **在途期**：键有效，同键重复到达 ⇒ 合并为「已在途 + 当前相位」（任务书判据 2②）。
   *   - **成功终态后**：键在窗口内继续保留 ⇒ 同键回「已在途 + 终态相位」（防双击 / 重发
   *     触发第二次安装）；窗口外同键视为过期、重新受理。
   *   - **中止 / 回滚终态**：占位**立即释放**、键不保留（中止后重试是期望行为——
   *     与「中止即恢复准入、旧实例继续服务」同取向）。
   *   - **上界 = 进程寿命**：编排器记录不跨进程持久，交接重启后新进程的编排器从空表
   *     开始（正是契约「与相位记录同寿命」的口径）。
   * 注入面（验证用）：system prop `nebflow.hotupdate.idempotencyWindowMs`。
   */
  val IdempotencyKeyWindowDefaultMs: Long = 300_000L

  def idempotencyKeyWindowMs: Long =
    sys.props
      .getOrElse("nebflow.hotupdate.idempotencyWindowMs", IdempotencyKeyWindowDefaultMs.toString)
      .toLongOption
      .getOrElse(IdempotencyKeyWindowDefaultMs)

end UpdateDefaults

/**
 * **编排层原因枚举（本批冻结字面 · 分发器补充裁定 3②）**：与契约件「失败相位以原因
 * 字段承载（不预设拼写、端点须能表达）」同形态——相位字段只放机器 token，原因走本枚举
 * 的冻结字面，人类可读诊断另放 `detail`（禁把自由文本当原因）。
 *
 * 传输层四原因（设备不存在 / 设备离线 / 出站队列满 / 传输超时）属设备互联服务面
 * （批 3 / 批 6），本批不涉、零动作；本枚举只列**编排层**原因。
 * 🔴 新增原因必须进本枚举（禁在调用点写自由文本当原因）。
 */
enum UpdateReason(val wire: String):
  /** 缺确认位（请求契约的强制位语义）。 */
  case ConfirmMissing extends UpdateReason("confirm-missing")

  /** 冻结相位：忙且请求为「忙即拒绝」模式。 */
  case FreezeBusy extends UpdateReason("freeze-busy")

  /** 冻结相位：等待在飞工作超过等待上限（裁定 4：中止，不强制）。 */
  case FreezeWaitLimitExceeded extends UpdateReason("freeze-wait-limit-exceeded")

  /** 冻结相位：排空收敛期限到达仍忙（裁定 4：中止，不强制）。 */
  case FreezeDrainDeadlineExceeded extends UpdateReason("freeze-drain-deadline-exceeded")

  /** 更新相位：安装动作本体失败。 */
  case InstallFailed extends UpdateReason("install-failed")

  /** 重启相位：既有热重启编排器拒绝或中止。 */
  case RestartRefused extends UpdateReason("restart-refused")

  /**
   * 重启相位：**四档健康自检失败**（批 2 G3）——既有编排器在「到门口确认」与「优雅
   * 让渡」之间判不健康，走既有中止路径；旧实例继续服务、准入闸释放。本原因是批 2 G0
   * 的收口点：该失败**不得**呈现为「已完成」。
   */
  case HealthCheckFailed extends UpdateReason("health-check-failed")

  /** 本实例未装配热重启编排器（能力缺失）。 */
  case OrchestratorUnavailable extends UpdateReason("orchestrator-unavailable")

  /** 编排器自身异常（兜底——绝不留下未收敛的相位）。 */
  case UnexpectedError extends UpdateReason("unexpected-error")

end UpdateReason

/**
 * 统一状态机相位（设计 §4）：空闲 → 检查 → 准备 → 冻结 → 更新 → 重启 → 恢复 → 完成；
 * 失败分支 中止（Aborted）与 已回滚（RolledBack）。
 *
 * - `wire` = 帧内机器 token（逻辑消费用）。
 * - `messageKey` = 文案键（`update.phase` 加相位名，设计 §7 命名规范）；界面展示文案
 *   一律由该键解析，帧内**不携带展示文案**（裁定 10 / D6）。
 * - `stateToken` = 设计 §7 统一四态归属（未完成/进行/失败/回滚/终态）的机器 token。
 *
 * **RolledBack 本批只建态**：回滚动作（健康自检 + 版本留存指针切换）属批 2（G3/G4），
 * 本批任何路径都不会进入该相位（禁把批 2 的面写成已做）。
 */
enum UpdatePhase(val wire: String):
  case Idle extends UpdatePhase("idle")
  case Checking extends UpdatePhase("checking")
  case Preparing extends UpdatePhase("preparing")
  case Freezing extends UpdatePhase("freezing")
  case Updating extends UpdatePhase("updating")
  case Restarting extends UpdatePhase("restarting")
  case Recovering extends UpdatePhase("recovering")
  case Completed extends UpdatePhase("completed")
  case Aborted extends UpdatePhase("aborted")
  case RolledBack extends UpdatePhase("rolled-back")

  /** 文案键（设计 §7 规范：`update.phase` 加相位名）。 */
  def messageKey: String = s"update.phase.$wire"

  /** 统一四态归属（设计 §7 表）的机器 token。 */
  def stateToken: String = this match
    case Idle | Checking => "pending"
    case Preparing | Freezing | Updating | Restarting | Recovering => "in-progress"
    case Aborted => "failed"
    case RolledBack => "rolled-back"
    case Completed => "completed"

end UpdatePhase

/**
 * 统一更新请求（设计 §4 请求契约）：来源 / 目标设备 / 通道 / 模式 / 等待上限 /
 * 幂等键 / **必带确认位**。
 *
 * `confirm` 沿用既有重启命令（`WebSocketRoutes.scala:1586-1601`）的强制位语义：
 * 缺确认 ⇒ 直接拒绝 + 可行动错误（不是静默忽略）。
 *
 * `mode` 复用既有 `RestartMode`（等待空闲 / 忙即拒绝）——不新造第二套模式枚举。
 */
final case class UpdateRequest(
  source: UpdateSource,
  confirm: Boolean,
  channel: UpdateChannel = UpdateChannel.Stable,
  targetDevice: Option[String] = None,
  mode: RestartMode = RestartMode.WaitIdle(UpdateDefaults.awaitIdleMs),
  idempotencyKey: Option[String] = None
):

  /**
   * 生效幂等键：客户端未带 ⇒ 由（来源、通道、目标设备）派生的确定性键——同一次点击
   * 的重复到达（双击 / 重发）必然落到同一键上，被幂等层合并，**不会装第二次**。
   */
  def effectiveKey: String =
    idempotencyKey
      .filter(_.trim.nonEmpty)
      .getOrElse(s"${source.wire}:${channel.wire}:${targetDevice.getOrElse("local")}")

end UpdateRequest

/** 请求受理结果（并发门语义：更新是排他动作、**不做队列** ⇒ 立即回报，绝不排队）。 */
enum UpdateAdmission:
  /** 受理：占位已落定，执行体在 fork fiber 上跑（进度经统一帧广播）。 */
  case Accepted(idempotencyKey: String)

  /**
   * 同键重复到达 ⇒ 「已在途 + 当前相位」（不排第二个、不重复执行）。
   * 成功终态后的同键在幂等键保留窗口内也走本支（相位 = 终态相位）。
   */
  case AlreadyInFlight(idempotencyKey: String, phase: UpdatePhase)

  /** 异键在途 ⇒ 「更新中」+ 当前相位 + 来源（**不排队**）。 */
  case Busy(phase: UpdatePhase, source: UpdateSource, idempotencyKey: String)

  /** 同步拒绝（缺确认位 / 能力缺失）：`reason` = 冻结原因字面，`detail` = 可行动错误。 */
  case Refused(reason: UpdateReason, detail: String)
end UpdateAdmission
