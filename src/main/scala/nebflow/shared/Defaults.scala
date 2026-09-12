package nebflow.shared

import scala.concurrent.duration.*

/**
 * Default configuration values used across the codebase.
 * Centralized here to avoid magic numbers scattered in multiple files.
 */
object Defaults:
  val ContextWindow = 128000
  val MaxTokens = 16384
  val MaxTokensCompact = 4096

  /**
   * Stream inactivity timeout — resets on every stream event (text/tool/compaction).
   * Long tasks with ongoing activity will not be killed; only truly stuck streams time out.
   * Also used as frontend spinner timeout (streamTimeoutMs + 30s buffer).
   */
  val StreamTimeoutSec: Int = 600

  /**
   * First-token timeout — if no chunk arrives from the LLM provider within this
   * time after the request is sent, the stream is considered hung (connection
   * dead, provider down). Shorter than inactivity timeout because a responsive
   * provider should always send the first token within seconds. 90s leaves room
   * for thinking models (GLM-5.2 etc.) whose reasoning phase delays the first
   * content token well past 30s.
   */
  val LlmFirstTokenTimeoutSec: Int = 90

  /**
   * Backend LLM stream inactivity timeout — if no chunk is received from the LLM provider
   * within this time, the stream is considered hung and cancelled. This is the primary
   * recovery mechanism for hung connections (e.g. after Mac sleep/wake).
   * Applies AFTER the first token. Shorter than StreamTimeoutSec because LLM providers
   * should always produce chunks within a few seconds, even during extended thinking.
   *
   * Flow-node supervision P3 (2026-08-26): 60 → 120s. Phase-2 stalls on thinking
   * models without SSE keep-alive can legally exceed 60s (production incident:
   * kimi k3 mid-stream stall at 60s with sibling requests healthy). Aligned with
   * the firstToken 90s thinking-margin logic; phase-1 and the 600s whole-stream
   * guard are unchanged. Override via llm.streamTimeouts.inactivitySec.
   */
  val LlmStreamInactivitySec: Int = 120

  /**
   * Whole-stream no-progress watchdog (issue #31, 2026-08-20): bounds the blind
   * window BEFORE the per-provider inactivityTimeout arms. The per-provider
   * watchdog (LlmFirstTokenTimeoutSec / LlmStreamInactivitySec) only covers a
   * provider stream once it starts producing — the intake→first-chunk
   * evaluation chain (candidates resolve / health check / adapter
   * fetch / HTTP setup / consumer-side processing) has NO coverage: a fiber
   * parked there hangs forever (incident: intake logged at 22:48:41, then 40min
   * of zero traces; Stop and hard-cancel both ineffective because the fiber was
   * suspended on a non-cancellable wait). This outer guard fails the whole
   * sendStream when NO chunk appears within this window (and likewise between
   * chunks — legal fallback silences are bounded well below it: overload
   * backoff 60s + first-token 90s per hop). Aligned with LlmTimeoutMs
   * (the non-streaming per-request timeout) as the "10min total" mental model.
   */
  val LlmStreamNoProgressTimeoutSec: Int = 600

  /** Per-provider LLM request timeout (covers streaming generation). */
  val LlmTimeoutMs: Long = 600_000L

  /** HTTP readTimeout for LLM provider connections (must be >= LlmTimeoutMs). */
  val LlmReadTimeoutSec: Int = 600

  /**
   * 工具执行期 WS 心跳间隔（s，审计 20260903 §2.5 子项①）：工具执行
   * （toolStart→toolEnd 之间）每此间隔发一条 toolHeartbeat WS 事件喂活前端
   * busy timer——前台长工具执行零事件段不再触发前端 630s 纯静默超时误杀
   * （实测 56/359 turn 超 630s）。间隔须明显小于前端 timer 预算
   * （StreamTimeoutSec+30s buffer），30s 与 RemoteExecutor 活动心跳、
   * BgHeartbeatIntervalSec 同频。AgentCore.pipeToolExecutions 消费。
   */
  val ToolHeartbeatSec: Int = 30

  /**
   * Timeout 类失败的软回避窗口（ms，审计 20260903 §2.3 子项③）：首 token/
   * 流间隙超时只跳过本次请求并把该 provider 软回避此窗口——不 markDown、
   * 不进健康状态、无需探测恢复（窗口到期自然可用）。「慢 ≠ 死」：markDown
   * 保留给 Auth/404/配额等确证死亡。取值 45s：实测 flap 恢复 p50<10s、
   * p90<20s，45s ≈ 5×p90 抖动恢复；窗口内 all-Down 门最坏等待 45+5s
   * （waitForAnyUp 5s tick）远小于其 120s 超时，不会误触发
   * AllProvidersDownTimeout。interface.scala 错误分支消费。
   */
  val TimeoutAvoidWindowMs: Long = 45_000L

  /** Bash tool max timeout in ms. */
  val BashMaxTimeoutMs: Long = 3_600_000L

  /** Curl tool max timeout in seconds. */
  val CurlMaxTimeoutSec: Int = 120

  /** WebFetch tool timeout in ms. */
  val WebFetchTimeoutMs: Int = 120_000

  /** Background job heartbeat interval in seconds. */
  val BgHeartbeatIntervalSec: Int = 30

  /** Background job health check interval in seconds — polls OS process liveness. */
  val BgHealthCheckIntervalSec: Int = 30

  /** Background job idle threshold (no output) before flagging as stuck, in seconds. */
  val BgStuckThresholdSec: Int = 600

  /**
   * Background job idle timeout — if a background command produces no output
   * for this many seconds, it is automatically cancelled and the agent is
   * notified with a timeout error. This prevents delegate/subtask agents from
   * being permanently blocked by a stuck background command (e.g. a grep that
   * hangs on a FUSE mount, an SSH prompt waiting for input, etc.).
   *
   * 5 minutes is long enough for legitimate slow commands (npm install, sbt
   * compile) that produce no output for a while, but short enough to recover
   * a stuck agent within a reasonable timeframe.
   */
  val BgIdleTimeoutSec: Int = 300

  // ---- Bash 卡死防护（#26，2026-08-30 用户裁定：恢复前台直跑语义）----
  // 08-25 #391「5 分钟自动转后台」已推翻：前台命令不再自动转后台、不设命令级
  // 超时——前台一直跑到结束（或显式 timeout / 停滞检测杀）。卡死兜底 =
  // TaskStuckWatcher（agent turn 10min 零活动 → restart 杀进程树）+ 前台
  // no-progress ceiling（shell.scala，10min 零输出零 CPU 停滞杀）。下方
  // hardTimeout/stuckWindow/healthCheck 只服务显式 run_in_background 后台任务
  // （显式后台不占 turn 活动，TaskStuckWatcher 兜不到，需自身兜底——评估建议
  // #26 保留机制 B）。

  /**
   * 后台命令硬超时（ms）：运行超过此阈值（默认 30min）后进入停滞观察期——
   * 输出零增长且 CPU 增量 < 10ms/采样窗口 连续 ≥ BashStuckWindowSec 才杀
   * （双条件裁定：输出零增长且 CPU 零消耗）。CPU 忙的合法任务不杀。
   */
  val BashBackgroundHardTimeoutMs: Long = 30 * 60 * 1000L

  /** 硬超时后的停滞观察窗口（s）：停滞连续满此值 → killProcessTree + TimeoutException。 */
  val BashStuckWindowSec: Int = 120

  // ---- 节点完成闸（bgtask-completion-gate 批，作者 2026-09-05 18:29 裁定）----
  // 节点完成判定不仅要求 turn 完成，还要求其等待型后台任务全部完成才投递。

  /**
   * node- 会话（Project 节点）等待型后台任务的硬超时延长档（ms，默认 4h）：
   * 30min 档（BashBackgroundHardTimeoutMs）是「超时后才进入停滞观察」的兜底
   * 上限——节点等待期的合法长任务（全量测试 1.5h 实跑先例）若在 30min 后出现
   * >2min 的安静阶段（无输出且 <10ms CPU/采样，如依赖拉取）会被 30min 档误杀。
   * 节点会话延长到 4h；活性不受影响——B1 idle 杀（5min）与停滞杀（120s）不变，
   * 杀条件仍是「停滞」，超时只是兜底上限。非节点会话维持 30min 档。
   */
  val BgGateNodeHardTimeoutMs: Long = 4 * 60 * 60 * 1000L

  /**
   * 节点完成闸等待总上限兜底（ms，默认 2h）：桥 hold 的单个等待期超过此值 →
   * 放弃等待、节点 failed（注明超时原因）。防的是异常面（后台完成通知链断裂/
   * registry 泄漏项）把节点永久悬挂在 running。数小时级依据：单任务硬超时已
   * 延长至 4h（BgGateNodeHardTimeoutMs），总上限取 2h < 4h——闸兜底先于单任务
   * 上限触发；串行多任务的合法长链由「每次后台完成复检时重臂」保护（有活动
   * 就重置）。system prop `nebflow.bgtask.gate.timeoutMs` 可调（CompletionGate
   * kill-switch 先例；每次调用现读，测试可即时翻转）。 */
  def BgGateWaitTimeoutMs: Long =
    sys.props.getOrElse("nebflow.bgtask.gate.timeoutMs", (2 * 60 * 60 * 1000L).toString).toLong

  // ── 节点生命周期收敛批（noderpt 批 A 段，2026-09-11 作者裁定）───────────────
  //
  // 完成门的**两条腿**各自独立开关（同一闸门、两个判据）：
  //   腿 1（后台任务存活）：`BgGateCompletionHold` —— 默认 **false = 封存**
  //     （作者 R1 裁定形态 (a)：代码保留、先不起用、可回退）；
  //   腿 2（未申报 node_report）：`NodeReportCompletionHold` —— 默认 **true**
  //     （作者 2026-09-11 裁定：未申报的 Completed 不终态化，节点保持 Running，
  //     由未申报提醒阶梯兜底；申报后下一个 Completed 放行终态化）。
  //
  // 两者都是「现读」prop（sys.props.getOrElse，BgGateWaitTimeoutMs 同款先例）——
  // 生产侧无在线翻转路由，改值需重启宿主生效；spec 侧走 NodeEngine 构造入参
  // （`bgGateCompletionHold` / `reportGateHold`）避开全局 prop 的跨 suite 污染。

  /**
   * 完成门腿 1 开关（`nebflow.bgGate.completionHold`，默认 `false` = 封存）：
   * true = 桥观察 `Completed` 时查 `BgTaskRegistry.waitingFor` 并 hold（今天的行为，
   * 含 armCap 兜底计时器）；false = 跳过等待集检查直接走放行分支（不再因「后台任务
   * 还在跑」拦节点）。hold 代码与 armCap 逐字保留，flag 打开即恢复旧行为。
   * 每次调用现读（spec/运维可即时翻转）。
   */
  def BgGateCompletionHold: Boolean =
    sys.props.getOrElse("nebflow.bgGate.completionHold", "false").trim.equalsIgnoreCase("true")

  /**
   * 完成门腿 2 开关（`nebflow.noderpt.completionHold`，默认 `true`）：
   * true = 桥收到本会话 `AgentEvent.Completed` 且 `NodeReportRegistry` 申报槽为空 ⇒
   * **不终态化**（节点保持 Running、会话存活），由未申报提醒阶梯提醒；申报槽非空 ⇒
   * 照常放行（resultDeferred 完成 → drain 分流终态化）。
   * false = 未申报也照常放行 = 本批之前的文本锚定降级面行为（零行为变化）。
   */
  def NodeReportCompletionHold: Boolean =
    sys.props.getOrElse("nebflow.noderpt.completionHold", "true").trim.equalsIgnoreCase("true")

  /**
   * 未申报提醒阶梯（`nebflow.noderpt.remind.ladderMs`，逗号分隔毫秒，默认
   * `10min / 30min / 1h / 2h / 4h` + 此后每 4h：8 拍 = 10m/30m/1h/2h/4h/8h/12h/16h）。
   * 第 N 个元素 = 第 N 拍的触发阈值（elapsed ≥ 该值且尚未发过 ⇒ 发一拍）。
   * 空数组 = 阶梯关闭（结合 maxRungs=0 即整条提醒腿停摆）。每次调用现读（验收可压到
   * 10s/30s 级）。
   */
  def NodeReportReminderLadderMs: List[Long] =
    sys.props
      .getOrElse(
        "nebflow.noderpt.remind.ladderMs",
        "600000,1800000,3600000,7200000,14400000,28800000,43200000,57600000")
      .split(',')
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(_.toLongOption)
      .toList

  /**
   * 单节点提醒上限（`nebflow.noderpt.remind.maxRungs`，默认 8 = 作者裁定「单节点上限
   * 8 拍」）。超过上限后**停止注入**（每拍都是一次完整 LLM turn，封顶为省 token），
   * 改由 [[NodeReportReminderQuiescentMs]] 的 quiescent 档继续写事件。`0` = 关闭提醒腿。
   */
  def NodeReportReminderMaxRungs: Int =
    sys.props.getOrElse("nebflow.noderpt.remind.maxRungs", "8").trim.toIntOption.getOrElse(8)

  /**
   * 阶梯耗尽后的 quiescent 事件间隔（`nebflow.noderpt.remind.quiescentIntervalMs`，
   * 默认 4h；作者 2026-09-11 裁定②：第 8 拍后**不再注入**、但**事件不停**——每 4h
   * 写一条 `node-report-missing`（`stage=quiescent`、不递增 reminderCount），持续到
   * 节点终态/挂起出口/申报清表。同一 prop 现读，验收可压到 10s 级。
   */
  def NodeReportReminderQuiescentMs: Long =
    sys.props.getOrElse("nebflow.noderpt.remind.quiescentIntervalMs", (4 * 60 * 60 * 1000L).toString).toLong

  // ── 终态延迟销毁窗口（noderpt 批 B 段，2026-09-11 作者裁定：一律存活 30 分钟再销毁）──

  /**
   * 终态延迟销毁窗口（`nebflow.noderpt.destroyWindowMs`，默认 **30 分钟**——与
   * `ShellSession` 的内建 `SessionTTL = 30.minutes` 同量级对齐，后者为 `private`
   * 常量不可跨包复用，故在此独立声明并显式注明对齐关系）。
   *
   * 语义（作者裁定形态 (b)）：节点**终态时刻只登记不杀进程**（`destroyAt = T + 本窗口`
   * 落节点持久字段）——窗口内进程/任务照跑、输出照写、**允许读取取证**，仅**禁止新
   * spawn**（新后台任务 / 新会话）；到点由 `NodeEngine.sweepDestroyWindows`
   * （`ProjectActor.TtlTick` 30s 节拍）执行 `BgTaskRegistry.reclaimSession` + 逐条
   * `finalizeTask` + 释放 `ShellSession.sessions` 条目 + 清 `destroyAt`（幂等）。
   * 挂起腿不登记窗口（即时收割不变）。
   *
   * 每次调用现读（验收可压到秒级；spec 亦可走 NodeEngine 构造入参 `destroyWindowMs`
   * 注入，避开全局 prop 的跨 suite 污染，`bgGateCompletionHold` 同款）。
   */
  def NodeDestroyWindowMs: Long =
    sys.props.getOrElse("nebflow.noderpt.destroyWindowMs", (30 * 60 * 1000L).toString).toLong

  // ── loop 预算（nrloop 一期 2026-09-12；设计 §3.6「轮次帽 + 时间帽」）────────────
  //
  // 两维独立、**任一命中即熔断**（额度帽不做——一期范围 OUT，设计 §3.6 作者裁定 R6
  // 一期只做 (b) 轮次 + 时间）。判定单点 `LoopBudget.decide` 收两个阈值，熔断动作
  // 三档统一在 `NodeEngine.circuitBreakLoop`。两者都是「现读」prop
  // （`sys.props.getOrElse`，`BgGateWaitTimeoutMs` / `NodeDestroyWindowMs` 同款先例）
  // ——spec / 运维可即时翻转；≤0 = 关闭该维（见 `LoopBudget.decide`）。

  /**
   * loop 轮次帽（`nebflow.nrloop.maxRounds`，默认 **3**）：同一 verifier 对同一
   * 重跑目标累计申报 `fail` 达到此轮数 ⇒ 熔断（verifier 终态化 `failed` + result
   * 写 reason + 计量、`loop-budget` 事件、失败通知）——不再登记新一轮重跑意图。
   *
   * 为什么 3：重跑是「同一判定再来一次」，三轮内无改善基本可判结构性缺陷（与
   * `RetryCap` 系的量级一致）；阈值调优留给运维（改 prop 即生效，无需重建流程）。
   */
  def LoopMaxRounds: Int =
    sys.props.getOrElse("nebflow.nrloop.maxRounds", "3").toInt

  /**
   * loop 时间帽（`nebflow.nrloop.maxWallClockMs`，默认 **4h**）：重跑目标节点自
   * `loopStartedAt` 置位起累计墙钟达到此值 ⇒ 熔断（同上三档动作；由
   * `NodeEngine.sweepLoopBudgets` 在 `ProjectActor.TtlTick` 30s 节拍扫描）。
   *
   * 为什么 4h：与节点会话硬上限 `BgGateNodeHardTimeoutMs` 同量级（循环重跑的总
   * 时长不该超过单节点会话能活的时长——超了说明它早就不是在「重跑」而是在「卡住」，
   * 该由熔断而非静默等待收场）。
   */
  def LoopMaxWallClockMs: Long =
    sys.props.getOrElse("nebflow.nrloop.maxWallClockMs", (4 * 60 * 60 * 1000L).toString).toLong

  // ---- Tool Result Guard ----

  /**
   * Global cap on tool result size (chars). Individual tools may declare a lower
   * maxResultSizeChars, but this constant acts as a system-wide cap regardless.
   * When exceeded, the result is saved to disk and the model receives a preview
   * with the file path instead of the full content.
   */
  val DefaultMaxResultSizeChars: Int = 50_000

  /**
   * Maximum aggregate size (chars) for tool_result blocks within a single turn's
   * batch of tool results. When the total exceeds this, the largest results are
   * persisted to disk and replaced with previews until under budget.
   */
  val MaxToolResultsPerMessageChars: Int = 200_000

  /** Preview size in characters for persisted tool results. */
  val ToolResultPreviewSize: Int = 2048

  // ---- Mail queue delivery dedup (P0 投递层指纹去重) ----

  /**
   * Fingerprint dedup window for queue-delivery: the same sender + recipient
   * + content hash delivers at most once per window. Covers the restart-replay
   * root cause (MailTool restart recovery re-fires MailQueued for the disk
   * head). Content-identical mails outside the window deliver normally —
   * legitimate re-sends are never eaten. Window long enough to span a restart
   * cycle, short enough to never eat a deliberate re-send minutes later.
   */
  val MailDedupWindowMs: Long = 30 * 60 * 1000L

  /**
   * V13 (2026-09-03): replay-scoping window for the mail dedup. The dedup
   * ledger consult now happens ONLY for deliveries inside this window after
   * the recipient session's activation (AgentRecord.startedAt) — the only
   * moment the restart-recovery re-fire (MailTool activateAgent → re-fire
   * disk head) can inject a true duplicate. Outside the window every delivery
   * injects even if the fingerprint is fresh — a legitimate same-content
   * re-send (10min polling text, re-pasted instruction) is never eaten.
   * Long enough to cover any activation→re-fire→drain scheduling delay,
   * short enough that the false-suppress exposure shrinks 30min → 60s.
   */
  val MailDedupReplayWindowMs: Long = 60 * 1000L

  // ---- Task stuck detection (P0 阶段 3) ----

  /**
   * Default stuck threshold: an agent in Processing with no turn activity for
   * this long is considered stuck. 10min is far above the llm-fail retry chain
   * upper bound (8s×3 + provider probe 120s) — every retry action touches the
   * activity stamp, so a healthy agent in the retry chain is never misjudged.
   */
  val StuckThresholdMs: Long = 10 * 60 * 1000L

  /** TaskStuckWatcher scan interval. */
  val StuckWatcherIntervalSec: Int = 30

  /**
   * 2026-09-10 卡死判据换轴（取证 20260910_130621_flow-node-activity-signal-forensics.md）：
   * **工具相位**卡死阈值——同一 turn 内单个工具调用持续超过此值、且该 turn 仍
   * 未完成（status == Processing）→ 判卡死，走既有 L1→L4 分级恢复/告警链路。
   *
   * 与 `StuckThresholdMs` 同档（10min，即既有前台 no-progress ceiling 的档位），
   * 但语义不同：`StuckThresholdMs` 量的是「agent 侧事件流停滞」（LLM 流楔死形态），
   * 本值量的是「单个工具调用不返回」（进程占死形态）。两者都不引用进程 CPU——
   * 这正是本次事故必须换轴的原因：被占死的会话 agent 侧早已停摆（零 LLM turn、
   * 零 chunk），而进程 CPU 微动让旧判据永不失明。
   *
   * system prop `nebflow.stuck.toolPhaseMs`，每次调用现读（kill-switch 先例，
   * 下游真实形态复现验收靠它缩短窗口）。
   */
  def ToolPhaseStuckMs: Long =
    sys.props.getOrElse("nebflow.stuck.toolPhaseMs", "600000").toLong

  /**
   * R6（取消静默死锁修复批 2026-09-10，作者裁定 R6 方案 2）：工具相位判据的
   * **授权宽限**——判据尊重命令自己声明的合法时长：
   *
   *   toolOverdue ⟺ now - currentToolStartedAt > 有效阈值
   *   有效阈值 = [[declaredToolTimeoutMs]] > 0 ? max(ToolPhaseStuckMs, declared + Slack)
   *                                        : ToolPhaseStuckMs
   *
   * **已裁定（2026-09-10）：以 `max` 为准。** 设计文档 §6-R6 的逐字公式为
   * `min(ToolPhaseStuckMs, deadline + slack)`，与该裁定项的两条验收口径
   * （「大 timeout 且持续推进 → 改造后不判」/「不带 timeout 仍 10min 判死」）
   * 不自洽——`min` 在大 timeout 时退化为 10min，案例 1 照旧误杀；`min` 亦与本项
   * 立论（设计 §6-R6：尊重命令自己声明的合法时长）自相矛盾。故实现口径 = `max`
   * （**只放宽不收紧**；未声明 `timeout` 的工具仍按默认档 10min 判死），实现单点 =
   * `ToolStuckJudgment.effectiveToolPhaseMs`。设计文档 §6-R6 已加同源订正注记。
   *
   * 背景：三例误杀的案例 1 命令自带 `timeout=900000ms`（15min 授权），判据在其
   * 11.2 分钟处开火。判据此前与工具自报授权时长零耦合。
   *
   * 取值理由：watcher 扫描周期 `StuckWatcherIntervalSec` = 30s；60s = 2 拍 —— 命令
   * 声明的时长一到，最多 2 拍（≈60s+拍相位）内判死，既不因拍相位漏判，也不把
   * 「合法时长刚过」误判为「早已卡死」。未声明时长的工具零行为变化（仍 10min）。
   *
   * system prop `nebflow.stuck.toolDeadlineSlackMs`，每次调用现读（kill-switch 先例）。
   */
  def ToolDeadlineSlackMs: Long =
    sys.props.getOrElse("nebflow.stuck.toolDeadlineSlackMs", "60000").toLong

  /**
   * 工具调用自己声明的授权时长（ms）。R6 唯一取数口：读工具入参 JSON 的 `timeout`
   * 字段（Bash 工具既有授权参数，语义 = 该命令被允许跑多久）。
   *
   * **只读调用方声明的值，不读工具内部默认值**（默认值 = 引擎授权上限，不是「命令
   * 声明的合法时长」——把默认值当声明会让所有工具都变成「已声明」，从而把 10min
   * 判据静默改写成工具默认时长 + slack）。缺字段 / 非正数 / 非数 → 0（= 未声明，
   * 判据回落到 ToolPhaseStuckMs）。
   *
   * 不改 Bash 工具自身的授权超时语义（红线 R6-3）：本函数只被判据消费。
   */
  def declaredToolTimeoutMs(toolInput: io.circe.JsonObject): Long =
    toolInput("timeout").flatMap(_.asNumber).flatMap(_.toLong).filter(_ > 0L).getOrElse(0L)

  /**
   * 2026-09-10 卡死判据换轴（阈值解耦）：前台 no-progress ceiling 的「有进展」
   * CPU 判据——与 BashTool 活动桥接的 `shell.CpuActiveThresholdNanos`（10ms）
   * **不再共用常量**。10ms/30s = 0.033% 单核，任何「活着且有偶发唤醒」的进程
   * （dev server 的 file watcher/HMR tick）都轻松越过 → 该 10 分钟安全网被
   * 微动无条件解除（本次事故前台命令跑了 2h50m 未被杀，即此因）。
   *
   * 语义（2026-09-10 验收项 3 修复后与实现逐字对齐，见 shell.scala
   * `foregroundNoProgressWatch`）：每 `ForegroundSampleIntervalMs` 采一次进程 CPU，
   * 与**上一个采样窗**的采样值相减；差值**严格大于**本值才算「本窗有进展」（重置
   * 判死窗口）。输出行数增长同样算进展。基线在重置与不重置两条分支**都**推进 ⇒
   * 比较的永远是单窗增量，不是「自上次重置以来的累计量」（旧实现漏推 no-reset
   * 分支的基线，把有效门槛压到 1.754 ms/s——声明值与实现差 19 倍，本条注释的
   * 「不再是跨窗口失效的绝对差值」当时与实现相反；现已按本条实现）。
   *
   * 有效门槛量化：每窗 CPU 增量 ≤ 本值（默认 1e9 ns / 30s 窗 = 33.3 ms/s =
   * **3.3% 单核**）的进程算「无进展」⇒ 连续 `ForegroundNoProgressTimeoutMs`
   * （默认 600s = 20 窗）即被杀。事故实测 CPU 微动 1.786 ms/s（0.18% 单核 ≈
   * 54ms/30s，比门槛低 18.7 倍）⇒ 必然在 10min 窗口内被收掉。反向（#319 保护
   * 不变）：每窗烧 > 本值 CPU 的长跑（构建/测试）或任何有输出的命令照常续跑。
   *
   * system prop `nebflow.shell.foregroundCpuProgressNanos`（每次调用现读）。
   * 注意：本值按「每个采样窗」计量——缩短 `nebflow.shell.foregroundSampleIntervalMs`
   * 会使等效速率门槛按同比例升高，短窗测试须等比调小本值。
   */
  def ForegroundCpuProgressNanos: Long =
    sys.props.getOrElse("nebflow.shell.foregroundCpuProgressNanos", "1000000000").toLong

  /**
   * 前台 no-progress ceiling 的 CPU 采样窗（默认 30s，与 ForegroundCpuProgressNanos
   * 的「1s/窗」配对；判死窗口 = 20 个采样窗）。system prop
   * `nebflow.shell.foregroundSampleIntervalMs`，每次调用现读（kill-switch 先例）——
   * 下游真实形态复现 / 验收可把 10min 级单臂压到分钟级（须 > 0）。
   */
  def ForegroundSampleIntervalMs: Long =
    sys.props.getOrElse("nebflow.shell.foregroundSampleIntervalMs", "30000").toLong

  /**
   * 前台 no-progress ceiling 的判死窗口（默认 600000ms = 10min，即 20 个采样窗）：
   * 前台命令零输出、且每个采样窗的 CPU 增量都 ≤ ForegroundCpuProgressNanos 持续至此
   * → killProcessTree + 带说明的 TimeoutException（命令级；与 #319「前台直跑不转后台」
   * 并存——本判死是停滞兜底，不改变直跑语义）。system prop
   * `nebflow.shell.foregroundNoProgressTimeoutMs`，每次调用现读（kill-switch 先例）。
   */
  def ForegroundNoProgressTimeoutMs: Long =
    sys.props.getOrElse("nebflow.shell.foregroundNoProgressTimeoutMs", "600000").toLong

  // ---- boot-time 崩溃恢复（crash-recovery 批 2026-09-07）----

  /**
   * 崩溃恢复回滚总开关（设计 R1）：默认 true——Gateway 启动后对崩溃残留 running
   * 节点自动 rehydrate 续跑（快段认领先于 TtlTick 首拍，慢段复用 runWithAgent 全链）。
   * false = sweep 空转 + 启动挂载恢复既有僵尸收殓（cancelled）+ watchdog 兜底不变
   * ——完全回到本批前现状。system prop `nebflow.crashRecovery.enabled`（每次调用
   * 现读，CompletionGate kill-switch 先例，测试可即时翻转）。
   */
  def CrashRecoveryEnabled: Boolean =
    sys.props.getOrElse("nebflow.crashRecovery.enabled", "true").toBoolean

  /**
   * 同时 rehydrate 并发上限（设计 §3.2，作者裁定④ 取 3）：大项目 N 节点同时恢复的
   * LLM 洪峰护栏——sweep 慢段信号量，节点会话终态才释放槽位。system prop
   * `nebflow.crashRecovery.concurrency` 可调。
   */
  def CrashRecoveryConcurrency: Int =
    sys.props.getOrElse("nebflow.crashRecovery.concurrency", "3").toInt

  // ---- 引擎活挂硬恢复（hard-recovery 批 2026-09-07，设计 §2/§8/§9）----

  /**
   * 活挂硬恢复总开关（设计 D-2）：默认 true。false 时 SessionKick 与
   * TaskStuckWatcher 的 L1-L4 升级链全部退化为本批前行为（watcher 只广播
   * attention / 旧 hard-cancel；WS 层不 kick）。system prop，每次调用现读
   * （CompletionGate kill-switch 先例，测试可即时翻转）。
   */
  def HardRecoveryEnabled: Boolean =
    sys.props.getOrElse("nebflow.hardRecovery.enabled", "true").toBoolean

  /**
   * SessionKick 的 idle 判据（设计 D-3）：目标会话 Processing 且无活动超过此值
   * 才 kick。默认 150s，刻意大于 LlmStreamInactivitySec(120s)——只有当既有
   * per-provider 看门狗窗口已过、turn 既未结束也未报错（即错误被楔死无法浮出）
   * 时才动手；60s 会误杀合法的 thinking 停顿与 fallback 间隙。误 kick 的代价
   * 由 RecoverableAbort 的有界重试吸收（一次整 turn 重发）。
   */
  def SessionKickIdleSec: Int =
    sys.props.getOrElse("nebflow.hardRecovery.kickIdleSec", "150").toInt

  /**
   * kick/升级动作后的回验 bound（设计 P2）：发出 abort 后在此窗口内验证会话
   * 是否离开 Processing；未通过才升级下一级。默认 60s = 2×扫描间隔。
   */
  def KickVerifyBoundSec: Int =
    sys.props.getOrElse("nebflow.hardRecovery.verifyBoundSec", "60").toInt

  // ---- R8 看门狗自身监测（2026-09-10 机制设计 §2–§4）----

  /**
   * R8 方向②（L3 有效性自检）：L3 开火后 `T+N` 复查该会话/节点的**行为面**结局
   * （设计 §3.4）。默认 120s = 4×`StuckWatcherIntervalSec`：
   *   - 必须 > L3 内部的硬编码 `IO.sleep(5s)` + 最慢 transcript 读；
   *   - 必须是扫描间隔的整数倍 ⇒ **零新增定时器**（复查搭既有扫描轮）；
   *   - 桥侧延迟实测 3ms ⇒ 120s 已给 24 倍余量。
   *
   * **第一版为常量，不做配置项**（设计 §3.4 末：标定后再决定是否外放）。测试/e2e
   * 通过 `TaskStuckWatcher.scan` 的 `l3VerifyDelayMs` 参数注入缩短的窗口，
   * **不引入 system prop**（生产只有一个值：120s）。
   */
  val L3VerifyDelayMs: Long = 120_000L

  /**
   * R8 方向③（影子模式 / dry-run）：**只记录不动作**的标定开关，默认 `false`
   * （生产行为零变化；本期默认不启用——机制备好，等标定窗口需要时再开，设计 §5）。
   *
   * `true` 时 `TaskStuckWatcher.recover` 内**全部破坏性动作**被禁止（设计 §4.4
   * 表 1–9：inflight hard-cancel / transportAbort / `reclaimSession` 进程 kill /
   * 子 agent `AgentCommand.Stop` / `bridgeCancelled` / `RestartAgent(Full)` /
   * `hardResumeFlowNode` / `emitSubagentPanelDone` / `broadcastStuck` WS 帧），
   * **只保留** ① 结构化事件（`WatchdogEventLog`）+ 日志——否则「关掉看门狗来研究
   * 看门狗」是无观测面的死路（设计 §4.1）。
   *
   * 作用域**仅** `TaskStuckWatcher.recover`：`SessionKick`
   * （`WebSocketRoutes.maybeSessionKick`）与 watcher 的 `hardResumeFlowNode` 调用点
   * 之外无第二含义（设计 §4.3 末，避免一个 flag 两套语义）。
   *
   * 与 `HardRecoveryEnabled` **正交**：`shadow=true` 时忽略 hard，一律不动手
   * （shadow 是更强的「只看」）。
   *
   * 代价（须知会）：影子期内**真实卡死不被恢复**（设计 §4.5 末）。
   * system prop `nebflow.stuck.shadow`，每次 `recover` 现读（kill-switch 先例）。
   */
  def StuckShadowMode: Boolean =
    sys.props.getOrElse("nebflow.stuck.shadow", "false").trim.equalsIgnoreCase("true")

  // ---- stuck 判据序 / 正信号门（stuck 自动恢复批 P1，2026-09-11 作者裁定 R-3）----

  /**
   * 正信号（**进展证据**）新鲜窗（默认 60s = 2× 工具活动桥采样间隔 30s）。
   *
   * 语义（作者裁定 R-3 的口径，**方向性是全部要害**）：正信号 = 上一个采样窗内
   * 该会话的在飞工具**确有推进**（stdout 行数增长 ∨ 单窗 CPU 增量 > 活动桥阈值）。
   * 结构化来源 = 工具活动桥采样点（[[nebflow.agent.AgentCore.markToolProgress]]），
   * **不解析任何自有日志**（设计 §2.3 原则 2）。
   *
   * 判据消费方向（唯一）：
   *   - **只阻止判死**：`currentToolStartedAt > 0 ∧ toolPhaseMs ≤ 有效阈值 ∧ 正信号新鲜`
   *     ⇒ 归**类② 假阳性**，本拍不动作、只记 `suspect`；
   *   - **绝不促成判死**：判死仍是 [[TaskStuckWatcher.assessDetailed]] 的两条不等式，
   *     正信号不出现在任何「满足即判死」的合取项里 ⇒ **不构成对红线 R6-4
   *     「判据不引进程 CPU」的放松**（作者 2026-09-11 已确认）。
   *
   * system prop `nebflow.stuck.progressSignalWindowMs`，每次判定现读（kill-switch 先例）。
   */
  def StuckProgressSignalWindowMs: Long =
    sys.props.getOrElse("nebflow.stuck.progressSignalWindowMs", "60000").toLong

  // ---- stuck 自动恢复：挂起腿有界等待（P2，2026-09-11）----

  /**
   * 挂起腿的**有界等待**上限（默认 15s）——替代 L3 里那个固定 `IO.sleep(5s)`。
   *
   * 设计 §6.2 风险 1 的原话：「`IO.sleep(5s)` 是固定时序竞态……**未证「永远 ≤5s」**」。
   * 改法（§3.6 选项 B 的代价点）= 轮询**可观测的终止确认**（会话从
   * `agentRegistry` 摘除 = 引擎 fiber 的清理段已完成）而不是赌一个固定时长：
   *   - 确认到达 ⇒ 立刻 resume（通常远快于 5s，恢复延迟下降）；
   *   - 超时未确认 ⇒ **降级**：不 resume，按恢复未生效走一次上报（诚实失败，
   *     不把「可能仍在运行的旧会话」与「新 resume 会话」叠在一起）。
   *
   * 轮询步长见 [[StuckSuspendPollMs]]。
   * system prop `nebflow.stuck.suspendWaitMs`（kill-switch 先例，测试可缩窗）。
   */
  def StuckSuspendWaitMs: Long =
    sys.props.getOrElse("nebflow.stuck.suspendWaitMs", "15000").toLong

  /** 挂起腿终止确认的轮询步长（默认 250ms）。system prop `nebflow.stuck.suspendPollMs`。 */
  def StuckSuspendPollMs: Long =
    sys.props.getOrElse("nebflow.stuck.suspendPollMs", "250").toLong

  // ---- stuck 自动恢复：重试预算 / 退避 / 冷却（P3，2026-09-11 作者裁定 R-2）----
  //
  // 作者 2026-09-11 拍板「R-2 = 按建议值落成 Defaults 常量」，即设计 §3.3 表的前五
  // 行数值**原样**成为常量（不重新标定）。语义：作者对 token 放大高度敏感 ⇒
  // **禁无条件重试**——恢复是一次性的、有预算的、有冷却的动作，不是循环。
  //
  // 全部走 system prop kill-switch 先例（与上面几条同款），但**默认值 = 裁定值**：
  // prop 只为隔离实例/测试缩窗，不改变生产语义。

  /** 单代次恢复预算（默认 **1**）：一个「卡死 episode」内最多一次恢复尝试。
    *
    * 理由（§3.3）：「类②误杀（本样本形态）**一次即够**——恢复后若同形态再卡，
    * 说明不是误判」。episode 边界 = 会话离开 stuck 候选集那一刻（[[TaskStuckWatcher.scan]]
    * 中与 `stopCounts` 同点的 `genAttempts` 复位）。
    *
    * system prop `nebflow.stuck.recoveryMaxPerGen`。 */
  def StuckRecoveryMaxPerGen: Int =
    sys.props.getOrElse("nebflow.stuck.recoveryMaxPerGen", "1").toInt

  /** 全链恢复预算（默认 **2**，含跨代次）：同一会话累计最多两次恢复。
    *
    * 理由（§3.3）：「与 `BackoffSupervisor.maxRestarts=2` 同档心智；防
    * 『恢复→再卡→再恢复』链」。跨代次 ⇒ 不随 episode 复位（只随会话消失复位）。
    *
    * system prop `nebflow.stuck.recoveryMaxPerChain`。 */
  def StuckRecoveryMaxPerChain: Int =
    sys.props.getOrElse("nebflow.stuck.recoveryMaxPerChain", "2").toInt

  /** 退避曲线（默认 **30s → 120s → 600s**；基点 = 扫描周期 30s）。
    *
    * §3.3：30s 与 `StuckWatcherIntervalSec` 对齐；120s 与 `L3VerifyDelayMs` 同档；
    * 600s = 阈值档。取值 = `StuckRecoveryBackoffMs(attempt)`（attempt 超界时取末项 =
    * 上限，不再增长）。 */
  val StuckRecoveryBackoffMs: List[Long] = List(30_000L, 120_000L, 600_000L)

  /** 退避曲线的查询口径：第 `attempt` 次恢复之后的静默时长（attempt 从 1 起；
    * ≤0 或超界按端点夹取）。纯函数，可独立单测。 */
  def stuckRecoveryBackoffMs(attempt: Int): Long =
    if StuckRecoveryBackoffMs.isEmpty then 0L
    else StuckRecoveryBackoffMs(math.max(1, math.min(attempt, StuckRecoveryBackoffMs.size)) - 1)

  /** 恢复后冷却（默认 **20min** = 2×`StuckThresholdMs`）：一次恢复完成后，
    * 本会话在冷却窗内**零动作**（§3.4 判定序第 3 步）。
    *
    * 理由（§3.3）：「防『恢复后立刻又被同轴判死』的自激振荡」。
    * system prop `nebflow.stuck.recoveryCooldownMs`。 */
  def StuckRecoveryCooldownMs: Long =
    sys.props.getOrElse("nebflow.stuck.recoveryCooldownMs", "1200000").toLong

  /** transcript 重放封顶（默认 **40 条**）：resume 时最多重放最近 40 条消息。
    *
    * 理由（§3.3）：直击「重发全量 ~250k 上下文 = token 放大面」的教训。
    * 消费点 = [[nebflow.core.project.NodeEngine.hardResumeNode]]（封顶 + prompt 声明）。
    * system prop `nebflow.stuck.recoveryReplayMaxMsgs`。 */
  def StuckRecoveryReplayMaxMsgs: Int =
    sys.props.getOrElse("nebflow.stuck.recoveryReplayMaxMsgs", "40").toInt

  /** 互斥点 2（§3.4）的检测窗（默认 **60s** = 扫描周期 ×2）：恢复完成后该窗内若
    * LoopGuard 跨轮命中计数**上升**（或会话进入 Loop 冻结）⇒ 写 `recovery-loop-detected`
    * 事件、**立即停恢复链** + 一次上报（= 「反复卡 ⇒ 反复重试」的机器识别点）。
    * system prop `nebflow.stuck.recoveryLoopDetectMs`。 */
  def StuckRecoveryLoopDetectMs: Long =
    sys.props.getOrElse("nebflow.stuck.recoveryLoopDetectMs", "60000").toLong

  /**
   * 流式请求的 per-request HttpClient 开关（设计 D-1 方案 A）：默认 true——
   * sendStream 每个 attempt 独立 HttpClient + backend + dispatcher，transport
   * abort = shutdownNow()，只杀目标请求零误伤（唯一实证有效原语，取证 §1.3）。
   * 代价 = 每请求 TLS 握手（~100-300ms，相对 LLM 延迟可忽略）。false 时回退
   * 共享 client（连接复用），transport abort 退化为 no-op → watcher 直升 L3。
   */
  def PerRequestTransport: Boolean =
    sys.props.getOrElse("nebflow.llm.perRequestTransport", "true").toBoolean

  /**
   * LLM 连接建立超时（设计 §2.2 配套小修）：共享与 per-request HttpClient 的
   * connectTimeout。现状为无限（interface.scala httpClient 构建无此配置）——
   * 首包前的 TCP 半开（VPN 切换后新连接挂起）由此兜底。
   */
  def LlmConnectTimeoutSec: Int =
    sys.props.getOrElse("nebflow.hardRecovery.connectTimeoutSec", "30").toInt

  // ---- STT（语音输入，可配置转录服务，#295）----

  /** STT 默认模型（OpenAI 兼容音频转录 API 的公共模型名，非用户配置）。 */
  val SttDefaultModel: String = "glm-asr-2512"

  /** STT 默认端点（智谱开放平台音频转录；配置文件可覆盖）。 */
  val SttDefaultEndpoint: String =
    "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"

  /**
   * FreezeScheduler 扫描间隔（冻结恢复延迟上限，freeze-schedule spec ⑤）。
   * frozen agent 靠本扫描驱动重评估时间表——出冻结段后最多延迟本值恢复 dispatch。
   * setWorkSchedule 配置热更走即时 scan，不受此间隔约束。
   */
  val FreezeCheckIntervalSec: Int = 30

  /**
   * v2 冻结式错误恢复升级链（§5.1-5.2）：进入升级链后等待父决策的窗口。
   * 每级窗口相同（从升级链启动算起逐级顺延）；用户级不设超时（最终仲裁，
   * 卡片持久化等待）。与 TaskStuckWatcher 卡死阈值同量级（用户有合理决策窗口）。
   */
  val ErrorEscalateAfterMs: Long = 10 * 60 * 1000L
end Defaults

/** Bash 卡死防护配置（#26：前台直跑语义；hardTimeout/stuckWindow/healthCheck
 * 只服务显式 run_in_background 后台任务。nebflow.json 顶层键可覆盖。 */
case class BashResilienceConfig(
  hardTimeoutMs: Long = Defaults.BashBackgroundHardTimeoutMs,
  stuckWindowSec: Int = Defaults.BashStuckWindowSec,
  healthCheckIntervalSec: Int = Defaults.BgHealthCheckIntervalSec
)
