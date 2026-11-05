package nebflow.shared

/**
 * 候选处置账本（缺陷批 2026-11-05 10:27，作者报障逐字：「我们的 fallback 队列里
 * 肯定是有可用的模型在的，但是还是报了所有模型不可用」）。
 *
 * 病根不在「换链逻辑不换链」——今天现场读数证明换链本身是好的（403 auth 之后
 * 确实继续试了下一根）；病根在**账本**：被健康面/软回避窗/时段窗/配置挡掉的候选
 * 从候选链里**静默消失**，既不进日志也不进终局错误，于是作者拿到的通知只剩
 * 「试过的那两根」，看上去像是「队列里没有别的模型」。本文件把这个缺口补成
 * 一等数据结构：每个候选在终局时刻必须有一条处置（tried / skipped+reason），
 * 一条都丢不掉。
 *
 * 依赖方向：本文件在 `nebflow.shared`（无第三方依赖），因为终局错误
 * [[FallbackExhaustedError]] 在 shared 里，而账本必须随错误一起送到作者的通知面
 * （delegate 冻结文案 = `err.getMessage` 逐字）。
 */

/** 候选一次都没被试过的原因。每条都必须可读（作者面）+ 可机判（断言面）。 */
enum CandidateSkipCause:

  /** 健康面判死（Down）：进探测集等恢复。 */
  case HealthDown(detail: String)

  /** 软回避窗内（配额 / 超时）：窗口到期自然回链，不探测。 */
  case SoftAvoid(remainingMs: Long, label: String)

  /** 模型不可用时段窗（`unavailableWindows`）。 */
  case ScheduleWindow(window: String)

  /** 配置面：provider 被标 `disabled` / 不在当前配置 / ref 不可解析。 */
  case NotConfigured(detail: String)

  /**
   * 静默排除（审计警报）：配置里可见、未 disabled、却没进链、也没有任何阶段
   * 记录原因。这一条**不应该出现**——它的出现即证明「有人偷偷把候选扔了」。
   */
  case SilentExclusion

  def wire: String = this match
    case HealthDown(_)     => "health-down"
    case SoftAvoid(_, _)   => "soft-avoid"
    case ScheduleWindow(_) => "schedule-window"
    case NotConfigured(_)  => "not-configured"
    case SilentExclusion   => "silent-exclusion"

  def describe: String = this match
    case HealthDown(d)        => s"health-down ($d)"
    case SoftAvoid(ms, label) => s"soft-avoid ($label, ${ms / 1000}s left)"
    case ScheduleWindow(w)    => s"schedule-window ($w)"
    case NotConfigured(d)     => s"not-configured ($d)"
    case SilentExclusion      => "silent-exclusion (no reason recorded — dropped before the chain)"
end CandidateSkipCause

/**
 * 链穷尽的**四种**形态（作者令 2026-11-05 第 5 条要三种；第四种
 * [[PartiallyTried]] 是「试了一部分 + 跳了一部分」的诚实形态——把它算进
 * `all-providers-failed` 正是本次报障的误导源头，故单列）。
 */
enum ExhaustionKind:
  /** 链上**每一个**候选都被真实试过且全败（唯一允许说 "All providers failed" 的形态）。 */
  case AllProvidersFailed

  /** 试过 ≥1 个且全败，但链上还有候选**一次都没试**（被跳过）。 */
  case PartiallyTried

  /** 链上一个都没试：候选全被跳过（健康面 / 软回避窗 / 时段窗 / 配置）。 */
  case AllSkipped

  /** 链本来就是空的：配置里没有任何可入选候选（配置问题，不是供应商问题）。 */
  case NoEligibleProviders

  def wire: String = this match
    case AllProvidersFailed  => "all-providers-failed"
    case PartiallyTried      => "partially-tried"
    case AllSkipped          => "all-skipped"
    case NoEligibleProviders => "no-eligible-providers"
end ExhaustionKind

/**
 * 一次 fallback 运行的**全量候选账本**：进链的、试过的、被跳过的（带原因）、
 * 配置里本该可见的。`render` 直接进终局错误文案 ⇒ delegate 冻结通知里作者一眼
 * 就能看出「漏了谁、为什么漏」。
 *
 * @param chain
 *   真正进入 `tryCandidate` 的候选（`providerId/model`，链序保留）。
 * @param tried
 *   本轮至少发过一次请求的候选。
 * @param skipped
 *   一次都没试的被跳候选及原因。
 * @param expected
 *   配置里**应当**可见的候选全集（非 disabled 的 provider/model）——用于静默排除
 *   审计：`expected - chain - skipped` 必须为空，非空即报警。
 */
final case class CandidateDisposition(
  chain: List[String],
  tried: List[String],
  skipped: List[(String, CandidateSkipCause)] = Nil,
  expected: List[String] = Nil
):
  private def uniq(xs: List[String]): List[String] = xs.distinct

  def skippedRefs: List[String] = skipped.map(_._1).distinct

  /** 静默排除：配置里本该可见，却既不在链上、也没有跳过原因。 */
  def silentExclusions: List[String] =
    uniq(expected).filterNot(r => chain.contains(r) || skippedRefs.contains(r))

  def kind: ExhaustionKind =
    val chainSet = chain.toSet
    val triedSet = tried.toSet
    if chainSet.isEmpty then
      if skipped.nonEmpty then ExhaustionKind.AllSkipped else ExhaustionKind.NoEligibleProviders
    else if triedSet.isEmpty then ExhaustionKind.AllSkipped
    else if chainSet.subsetOf(triedSet) then ExhaustionKind.AllProvidersFailed
    else ExhaustionKind.PartiallyTried

  def reasonCode: String = kind.wire

  /** 判词行（终局错误的第一行：**只有全试全败**才允许出现 All providers failed）。 */
  def headline(attemptCount: Int, families: String): String =
    val fam = if families.isEmpty then "" else s"; families: $families"
    kind match
      case ExhaustionKind.AllProvidersFailed =>
        s"All providers failed ($attemptCount attempt(s)$fam)"
      case ExhaustionKind.PartiallyTried =>
        s"All tried candidates failed ($attemptCount attempt(s)$fam) — " +
          s"${skippedRefs.size} candidate(s) were skipped WITHOUT being tried"
      case ExhaustionKind.AllSkipped =>
        s"No candidate was tried ($attemptCount attempt(s)$fam) — every candidate was skipped " +
          s"(${skippedRefs.size} skipped)"
      case ExhaustionKind.NoEligibleProviders =>
        s"No eligible providers — the candidate chain is empty ($attemptCount attempt(s)$fam)"

  private def sortKey(r: String): String = r

  /**
   * 处置表（作者面文本）。形态：
   * {{{
   * reason: all-providers-failed  (chain 3: tried 3, skipped 0)
   *   [tried]   kimi/k3.1-pro-highspeed — Auth
   *   [skipped] zhipu/glm-5.3-flash — health-down (statusCode: 429)
   * expected from config: 5 ref(s) — none silently excluded
   * }}}
   */
  def render(failedBy: Map[String, String] = Map.empty): String =
    val sb = new StringBuilder
    sb.append(
      s"reason: ${reasonCode}  (chain ${chain.size}: tried ${tried.size}, skipped ${skippedRefs.size})\n"
    )
    chain.foreach { ref =>
      val why = failedBy.get(ref).fold("")(r => s" — $r")
      sb.append(s"  [tried]   $ref$why\n")
    }
    skipped.sortBy(s => sortKey(s._1)).foreach { case (ref, cause) =>
      sb.append(s"  [skipped] $ref — ${cause.describe}\n")
    }
    val silent = silentExclusions
    if expected.nonEmpty then
      if silent.isEmpty then
        sb.append(s"expected from config: ${expected.size} ref(s) — none silently excluded")
      else
        sb.append(
          s"expected from config: ${expected.size} ref(s) — ${silent.size} SILENTLY EXCLUDED " +
            s"(no reason recorded; this is a bug, not provider failure): ${silent.sorted.mkString(", ")}"
        )
    else sb.append("expected from config: (not audited)")
    sb.toString
  end render

  /** 一行摘要（日志面用：每次请求入口一行，别把作者面弄脏）。 */
  def summary: String =
    s"chain=${chain.size} tried=${tried.size} skipped=${skippedRefs.size}" +
      (if skippedRefs.isEmpty then ""
       else skipped.sortBy(_._1).map { case (r, c) => s"$r(${c.wire})" }.mkString(" [", ", ", "]")) +
      (if silentExclusions.isEmpty then "" else s" SILENT-EXCLUSION=${silentExclusions.sorted.mkString(",")}")
end CandidateDisposition
