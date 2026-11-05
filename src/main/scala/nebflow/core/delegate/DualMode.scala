package nebflow.core.delegate

/**
 * DualModeConfig —— **执行形态词表 + 判定结果类型**（delegate-param-decisions 批
 * 2026-11-04 · 作者令「kernel和dispatcher 由于你有任务的全部上下文 你也可以做到
 * 直接选择」）。
 *
 * == 本批删除的东西（作者令 2026-11-04 20:58）==
 *   - **模式分配器（mode allocator）整条删除**：`auto` 曾走「JeV 决策 → LLM 兜底 →
 *     fail-open kernel」，这条链上有一次小模型调用在替编排方做决定；连同
 *     `DelegateModeAllocator` 端口与 `llm.decision.DelegateModeMachine` 执行体
 *     一起移除。spawn 路径上不再有任何模式判定模型调用。
 *   - **双模式总闸（`delegate.dualMode.enabled` / `failopenMode` 配置与 sys prop
 *     热读）一并退役**：模式现在是 `Delegate` 工具的**参数**（不填 = 确定性
 *     `kernel`），闸门与「缺省方向旋钮」都不再有判定对象——留一个永远开着的闸
 *     等于没删。
 *
 * 剩下的是**纯词表**（记录字段 / 审计行 / 工具面分叉共用），零 IO、零配置读取。
 */
object DualModeConfig:

  /** 模式词值域（registry/任务行 mode 字段 + mode-decision 审计行共用）。 */
  val ModeKernel: String = "kernel"
  val ModeDispatcher: String = "dispatcher"

  /** 值域校验（mode 参数/记录字段的合法词）。 */
  def isModeWord(s: String): Boolean = s == ModeKernel || s == ModeDispatcher

  /** 全部合法词（错误文案与 schema enum 同源）。 */
  val ModeWords: List[String] = List(ModeKernel, ModeDispatcher)
end DualModeConfig

/**
 * 模式判定结果（spawn 时一次性确定，随记录落盘）：mode + 产出腿 + 原因。
 *
 * 腿值域（mode-decision 审计行 `leg` 字段）：`param`（调用方显式填了 mode）|
 * `default`（未填 ⇒ 确定性 kernel）。**本批起不再有 jev / llm-fallback /
 * fail-open / llm-error / parse-fail 六词**——分配器已删除，没有任何模型判定
 * 参与这个字段。
 */
final case class ModeDecision(mode: String, leg: String, reason: String)

/** [[ModeDecision]] 的腿词表（审计行与回执共用，禁字面量散落）。 */
object ModeLeg:
  /** 调用方显式填了 `mode`。 */
  val Param: String = "param"

  /** 未填 `mode` ⇒ 确定性 kernel（无分配器、无模型调用）。 */
  val Default: String = "default"
end ModeLeg

/**
 * 模式解析单点（纯函数——**确定性、零模型调用**，本批的可判据点）。
 *
 * 规则（作者令）：
 *   - 不填 ⇒ `kernel`（建议缺省，确定性兜底）；
 *   - `kernel` / `dispatcher` ⇒ 原样采纳；
 *   - `auto` ⇒ **显式报错**（不静默映射成 kernel——静默映射会让「分配器有没有
 *     真的删掉」无法被验证）；
 *   - 其它词 ⇒ 显式报错（值域 = kernel | dispatcher）。
 */
object ModeParam:

  /** `auto` 的显式拒绝文案（含引导语——作者令：错误文案要说清出路）。 */
  val AutoRemoved: String =
    "Delegate: mode=\"auto\" is no longer accepted — the mode allocator has been removed, so the execution form is YOUR " +
      "decision, not a model's. Pass mode=\"kernel\" or mode=\"dispatcher\" directly. " +
      "（请直接指定 kernel 或 dispatcher，分配器已移除）(DELEGATE_MODE_AUTO_REMOVED)"

  /** 确定性缺省（不填 `mode` 时的生效值；spawn 腿的缺省参数 = 本值）。 */
  def default: ModeDecision =
    ModeDecision(
      DualModeConfig.ModeKernel,
      ModeLeg.Default,
      "mode omitted — deterministic default kernel (no allocator, zero model calls)"
    )

  /** 非法词的拒绝文案（值域单一来源 = [[DualModeConfig.ModeWords]]）。 */
  def invalid(raw: String): String =
    s"Delegate: invalid mode '$raw' — enum ${DualModeConfig.ModeWords.mkString("|")} (default ${DualModeConfig.ModeKernel}). " +
      "There is no allocator: you pick the execution form yourself. (DELEGATE_MODE_INVALID)"

  /**
   * 参数 → 生效模式（`Left` = 显式拒绝的原文）。
   *
   * 纯值函数（返回 `Either[String, ModeDecision]`，无 IO、无端口、无配置读取）：
   * 「零模型调用」在这里是**类型级**保证，不是运行期承诺。
   */
  def resolve(raw: Option[String]): Either[String, ModeDecision] =
    raw.map(_.trim.toLowerCase).filter(_.nonEmpty) match
      case None => Right(default)
      case Some("auto") => Left(AutoRemoved)
      case Some(m) if DualModeConfig.isModeWord(m) =>
        Right(ModeDecision(m, ModeLeg.Param, "explicit mode parameter (zero model calls)"))
      case Some(bad) => Left(invalid(bad))
end ModeParam
