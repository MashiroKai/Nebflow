package nebflow.core.processor

import cats.effect.IO
import io.circe.Json
import nebflow.shared.Counters

/**
 * Block 3（supervision trio §D, 2026-08-27；简化重构 2026-08-30 作者拍板）：
 * 循环检测器。
 *
 * 背景：TaskStuckWatcher 的哲学是「无活动 = 病」（Processing 10min 零 touch）；
 * 同日 fork/html-builder 事故是「每 4 秒失败一次 = 更重的病」——每次工具失败
 * 都 touch lastActivityMs，永不满足无活动判据，280+ 次同参同败烧了近 30 分钟。
 *
 * 2026-08-30 简化（作者裁定，替代原 S2「进展判定/轮预算」方案）：进展判定
 * 概念整体删除（进展工具白名单、轮预算、预算警告、轮预算强杀全删——验证型
 * 工作（sbt test→读日志→grep 定位）曾被「非进展轮」误杀）。现有三个信号：
 *
 *  - S1 同参同败（turn 内）：同 fp 连续失败且错误签名相同。N1(3) → L0 警告，
 *    N2(8) → L1 终止 turn。成功或换参数即清零。
 *  - R 精确重复检测（作者方案，替代 S2 全部职能）：
 *      R-call  同一工具+同参数（fp）连续出现 identicalCallHard(10) 次 → L1；
 *      R-text  助手文本输出逐字相同连续 identicalTextHard(10) 轮 → L1。
 *    连续性语义（作者特别补充）：计数必须连续——中间插任何不同调用/不同
 *    文本即计数刷新归零，不跨打断累计（A A A B A A A = B 后 A 从 1 重数）。
 *    成功不清零（同参成功 ×10 同样是循环）；空文本不计不刷新（纯工具轮不
 *    打断文本计数）；exemptTools（轮询类）与权限拒绝不计（轮询等待与 #12
 *    劝停独立治理的既有豁免语义延续）。**T3 调用类别（2026-11-04）**：
 *    R-call 只针对 Work 类调用——Query（`Bash(background_job_id=…)` 无 command
 *    的查询态采样）/ Wait（`Bash(background_job_id, timeout)` 有界等待）不推进也不清零 R-call 连续计数
 *    （同参定时采样不是循环信号；现场 A/B：10 次成功的同参查询被误杀），但
 *    **失败仍计 S1/S3**；另设只提醒的正向指标 queryWarnAfter(5)——同参非 Work
 *    连续恰达 5 ⇒ 一次 L0 nudge（指向 Bash 的有界等待），**永不** Terminate/Freeze。
 *  - S3 跨轮同调用：同 fp 在 ≥K(3) 个不同 turn 都失败且无一次成功 → L2 冻结。
 *    L1 终止后同 fp 在后续 turn 立即复发 → 直接 L2（不再给第三次 L1）。
 *
 * 本对象是纯函数核心（全部可单测）；挂载在 AgentCore 工具环
 * （pipeToolExecutions，guardBatch 之后），处置阶梯的 IO 动作在挂载点实现：
 *  - L0 = ToolsComplete 携带 loopReminder（下一轮 user system-reminder 注入）
 *  - L1 = LlmFailed(LoopDetectedError) → 既有 fatal 链（supervisor notify /
 *    team 成员父 ExternalEvent(failed) / Done 事件 / 持久化）
 *  - L2 = enterFrozen(FreezeReason.Loop) 待人工
 *
 * Root 例外（D3）：根 agent 只 L0 + L2（冻结+广播），不 L1——root 错误由
 * 用户裁决（watcher「根 agent 只广播不自动处置」政策对齐）。挂载点按
 * depth==0 过滤 Terminate。
 *
 * ── 2026-11-02「循环检测误杀整批任务」批（作者令：先诊断后改）────────────
 * 事故面：协作方一天内三例 delegate 批被 `loop-detected` 掐死（两例 R-call
 * 第 10 次同参调用、一例 crossTurn 冻结），整条链的后续节点永久 pending。
 * 三处结构性缺陷（诊断报告带 file:line，见 docs/）：
 *  ① **R-call / R-text / crossTurn 三轴零警告**：Warn 只在 S1 soft(3) 触发，
 *     而掐死 Mashiro 的是 R-call hard(10)——1..9 全是 Pass，第 10 次直接
 *     终止。三轴无警告档 = 模型没有自纠机会。
 *  ② **warn 文案印的是 fp 哈希不是工具名**（`tool 'a1b2c3d4e5f6' has failed`），
 *     模型无法辨认被卡住的是哪个工具——即使注入了也等于没注入。
 *  ③ **暂时性故障与真死循环同罚**：超时/网络抖动/远端 5xx 连败被当成死循环，
 *     而模型重试这些恰恰是正确行为。
 * 本批修法（三条都可配置、可回退）：
 *  ① `identicalCallSoft` / `identicalTextSoft` 新增 → R 两轴也有 Warn；
 *     且 `requireWarnBeforeTerminate`（默认 true）= **同 fp 首次触阈只发
 *     Warn，第二次触阈才终止**（warn → 继续 → 仍重复 → 终止）。grace 记账在
 *     [[Counters.warnedKeys]]（会话级，fp 作键；R-text 用 `text:<hash>`）。
 *  ② Warn/终止文案全部携带工具名（[[Counters.streakTool]] / lastCallTool）。
 *  ③ `transientAware`（默认 true）+ `transientPatterns` + `transientFactor`
 *     （默认 3）：暂时性故障类的失败**仍计数**（超长重试不是免死金牌），但
 *     hard 阈按倍率放大（S1 8→24 / R-call 10→30 / crossTurn 3→9 turn）——
 *     重试空间变大而终止仍是有界的（不放宽到「永不终止」）。
 *
 * 兜底关系：预算帽删除后，超长 turn 的最后防线 = TaskStuckWatcher 卡死检测——
 * 2026-09-10 换轴后为「agent 侧 10min 零事件（无活动）∪ 单个工具调用持续
 * 超 10min 且 turn 未完成（进程占死）」两条 agent 侧判据（**不引用进程 CPU**，
 * 取证 20260910_130621）：R 检测抓「有活动的循环」，watcher 抓「零进展的
 * 挂起/占死」，两者判据正交无重叠误杀面。本检测器与本次换轴无耦合——循环
 * 形态的每次工具失败都是 agent 侧事件（仍持续 touch 活动戳），工具相位亦随
 * 每个短调用重置，故本检测器职责不变。
 */
object LoopGuard:

  /**
   * 暂时性故障默认模式（正则，大小写不敏感）。命中 ⇒ 该次失败属于「重试是
   * 正确行为」的类别，hard 阈按 [[Config.transientFactor]] 放大。
   *
   * 刻意**只收**环境/网络/远端可用性信号：文件不存在、语法错、编译错、
   * 权限拒绝这类确定性失败一个字都不能命中——否则真死循环会溜过去。
   */
  val DefaultTransientPatterns: Set[String] = Set(
    "(?i)timed?\\s?out|timeout|deadline exceeded",
    "(?i)econn(reset|refused|aborted|timedout)|socket hang up|connection (reset|refused|aborted|closed)",
    "(?i)broken pipe|\\bepipe\\b|unexpected eof|stream (error|interrupted)",
    "(?i)eai_again|could not resolve host|temporary failure in name resolution|getaddrinfo",
    "(?i)temporarily unavailable|service unavailable|too many requests|rate limit|try again later",
    // HTTP 状态码必须带 "http" 前缀才认（否则 `/docs/503.md` 这类路径会被误判）
    "(?i)http[ /]?(429|5\\d\\d)\\b|bad gateway|gateway timeout|internal server error"
  )

  /** D5 阈值配置（nebflow.json supervision.loopGuard，Defaults 兜底热读）。 */
  final case class Config(
    enabled: Boolean = true,
    identicalFailureSoft: Int = 3,
    identicalFailureHard: Int = 8,
    /** R-call 警告档（新增 2026-11-02）：同参连击到此先发 Warn，不终止。 */
    identicalCallSoft: Int = 5,
    identicalCallHard: Int = 10,
    /** R-text 警告档（新增 2026-11-02）：逐字复读到此先发 Warn。 */
    identicalTextSoft: Int = 5,
    identicalTextHard: Int = 10,
    crossTurnFailureTurns: Int = 3,
    /**
     * warn-before-terminate 阶梯（默认开）：同 fp 首次触阈只发 Warn（模型获得
     * 一次自纠机会），第二次触阈才终止/冻结。关掉即回到本批之前的
     * 直接终止语义（回退开关）。
     */
    requireWarnBeforeTerminate: Boolean = true,
    /** 暂时性故障区分（默认开）：命中 transientPatterns 的失败阈按倍率放大。 */
    transientAware: Boolean = true,
    /** 暂时性故障的阈放大倍率（≥1；1 = 关闭放大但保留分类观察）。 */
    transientFactor: Int = 3,
    /** 暂时性故障模式（正则；配置项整体替换默认集）。 */
    transientPatterns: Set[String] = DefaultTransientPatterns,
    exemptTools: Set[String] = Set("TaskQuery", "TaskList", "TeamTaskList"),
    /**
     * T3（bash overhaul，作者令 2026-11-04）：同 fp 的 Query/Wait 类调用**连续**
     * 达此次数 → 仅一次 L0 nudge（指向 `Bash(background_job_id, timeout)` 有界等待），**永不**
     * Terminate/Freeze。热读键 `supervision.loopGuard.queryWarnAfter`；缺键走
     * 本缺省（不因此把整段 loopGuard 配置打回 Default）。
     */
    queryWarnAfter: Int = 5
  )

  object Config:
    val Default: Config = Config()

  /**
   * D5 配置热读（nebflow.json supervision.loopGuard；Guardrails.enabled 同款
   * 模式——无缓存每轮读盘）。
   *
   * 2026-11-02 批修正：**逐键兜底**（原实现是一个全有或全无的 for-comprehension
   * ——只要配置文件里缺任何一个键，整份配置静默回落到 Default，等于「阈值
   * 可配置」名存实亡：用户只写 `identicalCallHard: 20` 是不生效的）。
   */
  def loadConfig: IO[Config] =
    IO.blocking {
      val configPath = nebflow.shared.PathUtil.configJsonReadPath(nebflow.shared.PathUtil.dataRoot)
      if !os.exists(configPath) then None
      else io.circe.parser.parse(os.read(configPath)).toOption.flatMap(parseConfig)
    }.handleErrorWith(_ => IO.pure(None))
      .map(_.getOrElse(Config.Default))

  /**
   * 配置解析（纯函数，单测直调）：nebflow.json 根 Json → supervision.loopGuard。
   * 缺键逐键回落 [[Config.Default]]；整个 loopGuard 段缺失 → None。
   */
  def parseConfig(root: Json): Option[Config] =
    val c = root.hcursor.downField("supervision").downField("loopGuard")
    val d = Config.Default
    if c.focus.isEmpty then None
    else
      Some(
        Config(
          enabled = c.get[Boolean]("enabled").toOption.getOrElse(d.enabled),
          identicalFailureSoft = c.get[Int]("identicalFailureSoft").toOption.getOrElse(d.identicalFailureSoft),
          identicalFailureHard = c.get[Int]("identicalFailureHard").toOption.getOrElse(d.identicalFailureHard),
          identicalCallSoft = c.get[Int]("identicalCallSoft").toOption.getOrElse(d.identicalCallSoft),
          identicalCallHard = c.get[Int]("identicalCallHard").toOption.getOrElse(d.identicalCallHard),
          identicalTextSoft = c.get[Int]("identicalTextSoft").toOption.getOrElse(d.identicalTextSoft),
          identicalTextHard = c.get[Int]("identicalTextHard").toOption.getOrElse(d.identicalTextHard),
          crossTurnFailureTurns = c.get[Int]("crossTurnFailureTurns").toOption.getOrElse(d.crossTurnFailureTurns),
          requireWarnBeforeTerminate =
            c.get[Boolean]("requireWarnBeforeTerminate").toOption.getOrElse(d.requireWarnBeforeTerminate),
          transientAware = c.get[Boolean]("transientAware").toOption.getOrElse(d.transientAware),
          transientFactor = math.max(1, c.get[Int]("transientFactor").toOption.getOrElse(d.transientFactor)),
          transientPatterns =
            c.get[List[String]]("transientPatterns").toOption.map(_.toSet).filter(_.nonEmpty).getOrElse(d.transientPatterns),
          exemptTools = c.get[List[String]]("exemptTools").toOption.map(_.toSet).getOrElse(d.exemptTools),
          queryWarnAfter = c.get[Int]("queryWarnAfter").toOption.getOrElse(d.queryWarnAfter)
        )
      )

  /** 一轮工具批的单个观测事件（挂载点从 guardedBatch 构造）。 */
  final case class RoundEvent(
    toolName: String,
    args: Json,
    isError: Boolean,
    errorText: String,
    permissionDenied: Boolean
  )

  // ── 调用类别（T3，bash overhaul 2026-11-04） ───────────────

  /**
   * 调用类别（作者令 2026-11-04「组织优化 bash，用业界成熟做法完整实现」；
   * 事实源 `docs/20261104_110500_our-bash-tool-defects.md` §0-2/§3 与 §5.3）。
   *
   * 病灶：R-call 判据「同工具 + 同参数连续 identicalCallHard(10) 次、成败皆计、
   * 成功不清零」把**定时采样**误判成循环——等一个长任务时，连续同参
   * `Bash(background_job_id=…)` 查询（**成功的**读操作）是唯一合理的动作，却在
   * 第 10 拍被 Terminate（现场 A/B）。业界没有一家主流产品用「同参 N 次即终止
   * 会话」。故按调用类别增一维：
   *
   *  - `Query`（查询态采样）/ `Wait`（有界等待）**不推进也不清零** R-call 连续
   *    计数（与 exemptTools/permissionDenied 同姿态：不是循环信号）；
   *  - 但**失败仍计 S1/S3**——失败查询连发同一错误 8 次照样 Terminate（豁免只
   *    针对「同参次数」这一维，不放过真正的死循环失败）；
   *  - 补偿一条**只提醒**的正向指标 `queryWarnAfter`：同参非 Work 连续达该次数
   *    ⇒ 一次 L0 nudge（指向 `Bash(background_job_id, timeout)` 有界等待），**永不** Terminate/Freeze。
   *
   * 判据是纯函数、只看 `toolName` + `args`（不改 RoundEvent 形状、不引入第二套
   * 指纹——非 Work 连续计数复用同一 `fingerprint`）。
   */
  sealed trait CallKind

  object CallKind:
    /** 普通工作调用（会改变世界）：照旧参与 R-call 判据，口径逐字不变。 */
    case object Work extends CallKind

    /**
     * 查询态采样：`Bash` 且 `background_job_id` 非空、`command` 缺失/空——
     * 即「读某个后台任务的当前状态」，不产生新副作用。
     */
    case object Query extends CallKind

    /** 有界等待原语：`Bash(background_job_id, timeout)`（自带 deadline；重复调用等价于阻塞等待）。 */
    case object Wait extends CallKind

    /**
     * Classify a Bash wait/cancel request as non-work; normal command execution remains Work.
     */
    def classify(toolName: String, args: Json): CallKind =
      if toolName == "Bash" && nonEmpty(args, "background_job_id") then
        if nonEmpty(args, "cancel_background_job") then CallKind.Work else CallKind.Wait
      else CallKind.Work

    /** 字段存在且为非空白字符串（非字符串/缺失一律 false）。 */
    private def nonEmpty(args: Json, field: String): Boolean =
      args.hcursor.get[String](field).toOption.exists(_.trim.nonEmpty)
  end CallKind

  sealed trait Verdict extends Product with Serializable

  object Verdict:
    /**
     * L0：向消息流注入提醒（零成本给模型自纠机会）。
     *
     * @param key grace 记账键（`<fp>` 或 `text:<hash>`；空 = 不记账）。带 key
     *            的 Warn 会把该键写入 [[Counters.warnedKeys]]，使同一 fp 的
     *            下一次触阈升级为 Terminate / Freeze。
     */
    final case class Warn(msg: String, key: String = "") extends Verdict

    /** L1：终止当前 turn（LoopDetected 失败链）。Root 豁免（挂载点过滤）。 */
    final case class Terminate(msg: String, fp: String) extends Verdict

    /** L2：冻结会话待人工（enterFrozen("loop") + 广播 + 父通知）。 */
    final case class Freeze(msg: String) extends Verdict
    case object Pass extends Verdict

  // ── 指纹 ──────────────────────────────────────────────────

  /** fp = sha256(toolName + canonicalJson(args)).take(12) —— 同参判定。 */
  def fingerprint(toolName: String, args: Json): String =
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest((toolName + "|" + canonicalize(args)).getBytes("UTF-8"))
    digest.map(b => f"${b & 0xff}%02x").mkString.take(12)

  /**
   * 错误签名 = sha256(错误文本前 120 字符)——「File does not exist: <同一路径>」
   * 恒定；「timeout after 30s / 31s」不同（含变化内容的 flaky 天然豁免）。
   */
  def errorSignature(errorText: String): String =
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(errorText.take(120).getBytes("UTF-8"))
    digest.map(b => f"${b & 0xff}%02x").mkString.take(12)

  /** R-text 文本指纹 = sha256(整轮助手文本)。 */
  private def textHash(text: String): String =
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
    digest.map(b => f"${b & 0xff}%02x").mkString.take(12)

  /** R-text 的 grace 键（与 fp 键空间隔离——两者都是 12 位 hex）。 */
  private def textKey(hash: String): String = s"text:$hash"

  /**
   * 暂时性故障判定（2026-11-02 批，作者特别要求）：命令超时 / 网络抖动 /
   * 远端临时不可用 —— 这些连败属于「重试是对的」，不是死循环。
   *
   * 命中后不是免检：计数照旧、Warn 照旧，只是 hard 阈按 transientFactor
   * 放大（终止依然有界）。正则编译失败的模式退化为字面量 contains。
   */
  def isTransientError(errorText: String, patterns: Set[String]): Boolean =
    val text = Option(errorText).getOrElse("")
    if text.isEmpty then false
    else
      patterns.exists { p =>
        try java.util.regex.Pattern.compile(p).matcher(text).find()
        catch case _: Throwable => text.toLowerCase.contains(p.toLowerCase)
      }

  /** 键排序的紧凑 JSON（同语义同指纹——模型重复调用时键序漂移不影响）。 */
  private def canonicalize(json: Json): String =
    def norm(j: Json): Json =
      j.asObject match
        case Some(obj) =>
          Json.fromJsonObject(
            io.circe.JsonObject.fromIterable(
              obj.toIterable.toList.sortBy(_._1).map { case (k, v) => k -> norm(v) }
            )
          )
        case None =>
          j.asArray match
            case Some(arr) => Json.fromValues(arr.map(norm))
            case None => j
    norm(json).noSpaces

  // ── 判定核心（纯函数） ─────────────────────────────────────

  /**
   * 一轮（一个 LLM 响应的工具批）的评估。
   *
   * @param events        本轮全部工具结果（含 dropped「Tool not available」形态）
   * @param turnKey       当前 turn 身份（loopTurnKey.toString）——turn 边界自动
   *                      重置 S1/R 计数，S3 按不同 turnKey 计数
   * @param counters      会话级计数器（上个状态）
   * @param cfg           阈值配置
   * @param assistantText 本轮助手文本输出（R-text 判定；空串不计不刷新）
   * @return 更新后的计数器 + 处置裁决（一轮至多一条非 Pass 裁决，强度
   *         Freeze > Terminate > Warn）
   */
  def evaluate(
    events: List[RoundEvent],
    turnKey: String,
    counters: Counters,
    cfg: Config,
    assistantText: String = ""
  ): (Counters, Verdict) =
    if !cfg.enabled then (counters, Verdict.Pass)
    else
      val scale = math.max(1, cfg.transientFactor)
      // turn 边界：S1/R 归零（新用户消息 = 打断），S3（crossTurn/terminatedFps）
      // 与 grace 记账（warnedKeys）跨 turn 保留
      val base =
        if counters.turnKey != turnKey then
          counters.copy(
            turnKey = turnKey,
            streakFp = "",
            streakErrHash = "",
            streakCount = 0,
            streakTool = "",
            streakTransient = false,
            lastCallFp = "",
            lastCallTool = "",
            lastCallCount = 0,
            lastCallTransient = false,
            lastNonWorkFp = "",
            lastNonWorkCount = 0,
            lastTextHash = "",
            lastTextCount = 0
          )
        else counters

      // 逐事件折叠：S1 streak（仅失败）/ S3 crossTurn / R-call 连续同参（成败皆计）
      val counted = events.foldLeft(base) { (acc, e) =>
        val fp = fingerprint(e.toolName, e.args)
        val transient =
          e.isError && cfg.transientAware && isTransientError(e.errorText, cfg.transientPatterns)
        // R-call：权限拒绝不计（#12 独立治理）；exemptTools（轮询等待）不计；
        // 成败皆计、成功不清零——只有「不同的调用」才刷新连续计数（作者裁定⑤）
        // T3 调用类别（一处判类，全仓唯一）：Query/Wait 不推进也不清零 R-call
        // 连续计数（同参定时采样不是循环信号——现场 A/B 病灶），但另记一条
        // **只提醒**的非 Work 连续计数（queryWarnAfter nudge 的数据面）；
        // 失败仍走下方 S1/S3 折叠（同参失败查询 8 连败照样 Terminate）。
        val kind = CallKind.classify(e.toolName, e.args)
        val (lfp, ltool, lcnt, ltrans) =
          if e.permissionDenied || cfg.exemptTools.contains(e.toolName) || kind != CallKind.Work then
            (acc.lastCallFp, acc.lastCallTool, acc.lastCallCount, acc.lastCallTransient)
          else if acc.lastCallFp == fp then (fp, e.toolName, acc.lastCallCount + 1, transient)
          else (fp, e.toolName, 1, transient)
        // 非 Work 连续计数：同 fp 推进、不同 fp 刷新；被 Work 调用打断即归零
        // （「连续」语义与 R-call 一致）；权限拒绝/豁免调用不推进也不清零
        val (nwfp, nwcnt) =
          if e.permissionDenied || cfg.exemptTools.contains(e.toolName) then
            (acc.lastNonWorkFp, acc.lastNonWorkCount)
          else if kind == CallKind.Work then ("", 0)
          else if acc.lastNonWorkFp == fp then (fp, acc.lastNonWorkCount + 1)
          else (fp, 1)
        val accR = acc.copy(
          lastCallFp = lfp,
          lastCallTool = ltool,
          lastCallCount = lcnt,
          lastCallTransient = ltrans,
          lastNonWorkFp = nwfp,
          lastNonWorkCount = nwcnt
        )

        val counted = e.isError && !cfg.exemptTools.contains(e.toolName) && !e.permissionDenied
        if !counted then
          // S1/S3 视角的成功事件：同 fp 的 streak 清零 + crossTurn 整条清除
          val streakReset =
            if accR.streakFp == fp then
              accR.copy(
                streakFp = "",
                streakCount = 0,
                streakErrHash = "",
                streakTool = "",
                streakTransient = false
              )
            else accR
          streakReset.copy(
            crossTurn = streakReset.crossTurn - fp,
            terminatedFps = streakReset.terminatedFps - fp,
            // 该 fp 成功了 ⇒ 它不再是「暂时性故障专属」形态
            transientFps = streakReset.transientFps - fp
          )
        else
          val sig = errorSignature(e.errorText)
          val (sf, sh, sc, st, stool) =
            if accR.streakFp == fp && accR.streakErrHash == sig then
              (
                accR.streakFp,
                accR.streakErrHash,
                accR.streakCount + 1,
                // 整条 streak 全是暂时性故障才算暂时性（混入一次确定性失败 = 真循环）
                accR.streakTransient && transient,
                accR.streakTool
              )
            else (fp, sig, 1, transient, e.toolName)
          accR.copy(
            streakFp = sf,
            streakErrHash = sh,
            streakCount = sc,
            streakTool = stool,
            streakTransient = st,
            crossTurn = accR.crossTurn.updated(fp, accR.crossTurn.getOrElse(fp, Set.empty) + turnKey),
            transientFps = if transient then accR.transientFps + fp else accR.transientFps - fp
          )
        end if
      }

      // R-text：空文本不计不刷新；不同文本刷新归零（作者裁定⑤）
      val withText =
        if assistantText.isEmpty then counted
        else
          val th = textHash(assistantText)
          if counted.lastTextHash == th then counted.copy(lastTextHash = th, lastTextCount = counted.lastTextCount + 1)
          else counted.copy(lastTextHash = th, lastTextCount = 1)

      // 阈值（暂时性故障类按倍率放大；终止依然有界）
      val crossTurnBase = cfg.crossTurnFailureTurns
      val s1HardTh = if withText.streakTransient then cfg.identicalFailureHard * scale else cfg.identicalFailureHard
      val callHardTh = if withText.lastCallTransient then cfg.identicalCallHard * scale else cfg.identicalCallHard

      def crossTurnTh(fp: String): Int =
        if withText.transientFps.contains(fp) then crossTurnBase * scale else crossTurnBase

      // grace：同一个 fp（或同一文本指纹）是否已经吃过一次 Warn；以及「这一次
      // 能不能直接动手」。两条都按 fp 记账（同一 fp = 同一「工具+参数」组合，
      // 无论它从哪条轴被警告过，模型都已被明确提示过这个组合在循环）。
      def warned(key: String): Boolean = withText.warnedKeys.contains(key)
      def graceUsed(key: String): Boolean = cfg.requireWarnBeforeTerminate && warned(key)
      /** 是否可以动手（终止/冻结）：关掉阶梯即无条件动手；否则必须先警告过。 */
      def mustAct(key: String): Boolean = !cfg.requireWarnBeforeTerminate || warned(key)

      /** 事件里该 fp 对应的工具名（Warn/终止文案用；查不到回落 fp）。 */
      def toolOf(fp: String): String =
        events.find(e => fingerprint(e.toolName, e.args) == fp).map(_.toolName).getOrElse(fp)

      // 裁决（强度序）：S3 冻结 > L1 后复发冻结 > S1 hard 终止 > R-call
      // > R-text > S1 soft 警告 > R-call soft 警告 > R-text soft 警告
      val crossTurnHit = withText.crossTurn.find { case (fp, turns) => turns.size >= crossTurnTh(fp) }
      val recurrenceHit = events
        .filter(e =>
          e.isError && !e.permissionDenied &&
            !cfg.exemptTools.contains(e.toolName)
        )
        .map(e => fingerprint(e.toolName, e.args))
        .find(withText.terminatedFps.contains)
      val s1Hard = withText.streakCount >= s1HardTh && withText.streakFp.nonEmpty
      val callRepeatHit = withText.lastCallCount >= callHardTh && withText.lastCallFp.nonEmpty
      val textRepeatHit = withText.lastTextCount >= cfg.identicalTextHard && withText.lastTextHash.nonEmpty
      // T3 正向指标：同参非 Work（Query/Wait）连续计数**恰等于** queryWarnAfter
      // ⇒ 一次性 L0 nudge（只提醒；不参与任何终止/冻结判据）
      val queryNudgeHit =
        withText.lastNonWorkFp.nonEmpty && withText.lastNonWorkCount == cfg.queryWarnAfter

      val verdict: Verdict =
        crossTurnHit match
          case Some((fp, turns)) =>
            val need = crossTurnTh(fp)
            val transientNote =
              if withText.transientFps.contains(fp) then
                s" (transient-failure class: the bound is ×$scale = $need turns)"
              else ""
            if mustAct(fp) then
              Verdict.Freeze(
                s"loop-detected: identical call (tool '${toolOf(fp)}') failed in ${turns.size} separate " +
                  s"turns (fp=$fp) with no success$transientNote — session frozen pending human/Manager decision"
              )
            else
              Verdict.Warn(
                s"tool '${toolOf(fp)}' has now failed in ${turns.size} separate turns with no success — " +
                  s"one more failing turn freezes this session$transientNote",
                fp
              )
          case None =>
            recurrenceHit match
              case Some(fp) =>
                Verdict.Freeze(
                  s"loop-detected: call fp=$fp was terminated earlier this session and immediately failed again — session frozen pending human/Manager decision"
                )
              case None =>
                if s1Hard then
                  if mustAct(withText.streakFp) then
                    Verdict.Terminate(
                      s"loop-detected: tool call with identical arguments failed ${withText.streakCount} times with the same error — turn terminated. Change approach or abandon this path; if re-dispatched, repeating the same call will freeze the session.",
                      withText.streakFp
                    )
                  else
                    Verdict.Warn(
                      s"tool '${withText.streakTool}' has now failed ${withText.streakCount} times with " +
                        s"identical arguments and the same error — one more identical failure terminates this turn",
                      withText.streakFp
                    )
                else if callRepeatHit then
                  if mustAct(withText.lastCallFp) then
                    Verdict.Terminate(
                      s"loop-detected: tool '${withText.lastCallTool}' has now been called ${withText.lastCallCount} times in a row with identical arguments — turn terminated to avoid spinning. Change the approach; for long-running work use run_in_background instead of repeating the same call.",
                      withText.lastCallFp
                    )
                  else
                    Verdict.Warn(
                      s"tool '${withText.lastCallTool}' has now been called ${withText.lastCallCount} times in " +
                        s"a row with identical arguments — one more repetition terminates this turn",
                      withText.lastCallFp
                    )
                else if textRepeatHit then
                  if mustAct(textKey(withText.lastTextHash)) then
                    Verdict.Terminate(
                      s"loop-detected: the assistant has produced the identical text output ${withText.lastTextCount} rounds in a row — turn terminated. Break the repetition: change the response or stop.",
                      ""
                    )
                  else
                    Verdict.Warn(
                      s"the assistant has produced the identical text output ${withText.lastTextCount} rounds in " +
                        s"a row — one more identical round terminates this turn",
                      textKey(withText.lastTextHash)
                    )
                else if withText.streakCount >= cfg.identicalFailureSoft && withText.streakFp.nonEmpty
                  && !warned(withText.streakFp)
                then
                  Verdict.Warn(
                    s"tool '${withText.streakTool}' has failed ${withText.streakCount} times with identical " +
                      s"arguments and the same error — change the approach or abandon this path before it is force-terminated",
                    withText.streakFp
                  )
                else if withText.lastCallCount >= cfg.identicalCallSoft && withText.lastCallFp.nonEmpty
                  && !warned(withText.lastCallFp)
                then
                  Verdict.Warn(
                    s"tool '${withText.lastCallTool}' has been called ${withText.lastCallCount} times in a row " +
                      s"with identical arguments — if this is not progressing, change the approach",
                    withText.lastCallFp
                  )
                else if withText.lastTextCount >= cfg.identicalTextSoft && withText.lastTextHash.nonEmpty
                  && !warned(textKey(withText.lastTextHash))
                then
                  Verdict.Warn(
                    s"the assistant has produced the identical text output ${withText.lastTextCount} rounds in " +
                      s"a row — say something new or stop",
                    textKey(withText.lastTextHash)
                  )
                else if queryNudgeHit then
                  // T3：只提醒、不处置——查询/等待类重复不是循环信号（业界无
                  // 「同参 N 次即终止会话」的做法）；指向有界等待原语或回合结束。
                  Verdict.Warn(
                    s"the same query/wait call has now been issued ${withText.lastNonWorkCount} times in a row with identical arguments — polling the same handle cannot make progress by itself. Use Bash with background_job_id and timeout for a bounded wait, or end your turn and wait for the completion notification instead of re-polling."
                  )
                else Verdict.Pass

      // 记账：Terminate 入 terminatedFps（R-text 终止无 fp，不记）；Warn 入
      // warnedKeys（grace 消耗——同一 fp 的下一次触阈升级为终止/冻结）
      val finalCounters = verdict match
        case t: Verdict.Terminate if t.fp.nonEmpty =>
          withText.copy(terminatedFps = withText.terminatedFps + t.fp)
        case w: Verdict.Warn if w.key.nonEmpty =>
          withText.copy(warnedKeys = withText.warnedKeys + w.key)
        case _ => withText

      (finalCounters, verdict)

  /**
   * L0 提醒消息文本（ToolsComplete.loopReminder → 下一轮 user 注入）。
   *
   * 2026-11-02 批：**结构化**——不只陈述「你重复了」，还给出可执行的纠正
   * 台阶（暂时性故障 vs 确定性失败的分流处理），并明写「再重复一次就终止」。
   * 这一条是「warn 档必须真的进模型上下文」的落点：挂载点把它作为 user
   * system-reminder 追加在工具结果之后（AgentProcessing 的 newMessages）。
   */
  def reminderMessage(warn: Verdict.Warn): String =
    s"""<system-reminder>
       |Loop guard: ${warn.msg}.
       |This is an automated safeguard against token-burning loops. Your turn is still alive — use it to correct course:
       |1. If the failure is TRANSIENT (command timeout, network hiccup, remote service temporarily unavailable),
       |   say so explicitly and retry at most once more with a *changed* approach: longer timeout, smaller scope,
       |   run_in_background instead of a foreground wait, or an alternative endpoint/command.
       |2. If the call is deterministically broken (wrong path, missing tool, bad arguments), stop repeating it:
       |   change the tool or the arguments, or report the failure explicitly and finish the work that does not depend on it.
       |3. Repeating the identical call again terminates this turn; a call already terminated for a loop freezes the
       |   whole session on its next identical failure.
       |4. Before you stop (or get stopped), leave your partial results somewhere durable: write the analysis already
       |   produced to a file (docs/ or reports/) and, for a delegated task, report what completed and what did not.
       |</system-reminder>""".stripMargin

end LoopGuard
