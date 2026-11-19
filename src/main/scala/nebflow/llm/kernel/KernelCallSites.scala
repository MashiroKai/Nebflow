package nebflow.llm.kernel

/**
 * LLM 调用点登记表（kernel-spec 批 2026-11-19，K2「system 段来源单一」+
 * K7「模型来源显式」的**登记面**）。
 *
 * == 为什么要有这张表 ==
 * 缓存命中 spec 的 C1–C4 主语一直是「`AgentSessionExecution` 装配的持久历史」——
 * 契约只为一条腿写；`LlmAllocator` / `DelegateJudgeMachine` / `HealthMonitor`
 * 三条辅腿**零约束、零计量、零归因**（审计 §2.2 / §3）。K2 要求「每个调用点必须
 * 声明 `systemStable`；无 `systemStable` 的调用点须在登记表里带白名单理由」。
 * 本对象就是那张登记表：每个 `KernelCall` 必须携带一个 [[KernelCallSites.Site]]，
 * 其 `systemPrefix` 字段逐条写明该腿的前缀处置（有稳定前缀 / 为何没有）。
 *
 * == 闸（编译期 + 测试期，运行期零开销）==
 *   - **编译期**：`KernelCall.site` 与 `KernelCall.source` 都是**无缺省值**的必填
 *     字段 ⇒ 少声明一条编译不过（[[LlmKernel.KernelCall]]）；
 *   - **测试期**（`nebflow.llm.kernel.LlmKernelContractSpec` 的 K2 闸）：
 *     ① 全仓 `src/main` 里每一处 `site = …` 的右值必须以 `KernelCallSites.` 开头
 *        ——禁止就地拼一个游离的 purpose 字符串；
 *     ② 每一处 `SystemPrefix.Absent(…)` 的实参必须以 `KernelCallSites.` 开头
 *        ——缺席理由只能来自本表（白名单），不准临时编一句；
 *     ③ 本表自身：id 唯一、声明文本非空。
 *
 * 🔴 本表是**登记面**，不是开关：它不改变任何运行期行为，只把「这条腿声明了什么」
 * 变成可 grep、可断言的常量。
 */
object KernelCallSites:

  /**
   * 一个调用点声明。
   *
   * @param id
   *   归因域标识（计量/日志/登记三面同源）
   * @param logName
   *   logger 名（WARN/INFO 归因行落在哪个通道）
   * @param logTag
   *   行内前缀（可 grep 的腿级标签）
   * @param systemPrefix
   *   该腿的 system 前缀处置声明（K2 的「显式理由」字段）
   */
  final case class Site(id: String, logName: String, logTag: String, systemPrefix: String)

  /** 主 agent 腿（`LlmInterface` 的 `LlmRequest` 路径；前缀随请求声明）。 */
  val MainAgent: Site = Site(
    id = "agent",
    logName = "nebflow.llm.kernel",
    logTag = "agent",
    systemPrefix =
      "declared per request — Stable(LlmRequest.systemStable) when the caller supplies a non-empty stable prompt, " +
        "otherwise Absent(MainLegAbsence); never fabricated inside the assembly point"
  )

  /** 插件分配腿（`LlmAllocator`；B2 + 作者裁定 2026-11-19 17:22 = 常量段整体入 system）。 */
  val PluginAlloc: Site = Site(
    id = "plugin-alloc",
    logName = "nebflow.llm.plugin-alloc",
    logTag = "llm allocator",
    systemPrefix =
      "Stable(LlmAllocator.stableBlock) — the whole constant segment (preamble + headers + catalog, ≈5288 tokens, " +
        "above the vendor minimum); the varying task brief stays in the user message"
  )

  /** 决策卡判官腿（`DelegateJudgeMachine`；B2 = 判令模板提为 `systemStable`）。 */
  val DelegateJudge: Site = Site(
    id = "delegate-judge",
    logName = "nebflow.llm.delegate-judge",
    logTag = "delegate-judge",
    systemPrefix = "Stable(DelegateJudgeMachine.JudgeSystemTemplate) — the constant judging template; the delegate state stays in the user message"
  )

  /** 健康探针腿（`HealthMonitor.probe`；K6 = 计量但不过闸）。 */
  val HealthProbe: Site = Site(
    id = "health-check",
    logName = "nebflow.llm.health",
    logTag = "health-check",
    systemPrefix = "Absent(HealthProbeAbsence) — a byte-frozen single-token probe body has no shared system prefix to reuse"
  )

  /** 主 agent 腿「本例无稳定 system 前缀」的白名单理由（K2 登记项）。 */
  val MainLegAbsence: String =
    "main agent leg: LlmRequest.systemStable = None for this request (the caller declared no shared system prefix)"

  /** 健康探针腿「无稳定 system 前缀」的白名单理由（K2 登记项）。 */
  val HealthProbeAbsence: String =
    "health probe: byte-frozen single-token probe body; there is no cross-call shared system prefix to cache"

  /** 全部登记调用点。 */
  val all: List[Site] = List(MainAgent, PluginAlloc, DelegateJudge, HealthProbe)

  /** 白名单缺席理由全集（K2 闸的比对基准）。 */
  val absenceReasons: List[String] = List(MainLegAbsence, HealthProbeAbsence)

end KernelCallSites
