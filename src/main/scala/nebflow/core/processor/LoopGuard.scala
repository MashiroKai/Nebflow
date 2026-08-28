package nebflow.core.processor

import cats.effect.IO
import io.circe.Json

/**
 * Block 3（supervision trio §D, 2026-08-27）：循环检测器 — 「高活动零进展」轴。
 *
 * 背景：TaskStuckWatcher 的哲学是「无活动 = 病」（Processing 10min 零 touch）；
 * 同日 fork/html-builder 事故是「每 4 秒失败一次 = 更重的病」——每次工具失败
 * 都 touch lastActivityMs，永不满足无活动判据，280+ 次同参同败烧了近 30 分钟。
 *
 * 三信号（互补不重叠）：
 *  - S1 同参同败（turn 内）：同 fp 连续失败且错误签名相同。N1(3) → L0 警告，
 *    N2(8) → L1 终止 turn。成功或换参数即清零。
 *  - S2 高活动零进展（turn 内）：工具轮预算（有进展轮不计——成功的
 *    Edit/Write/任务状态迁移）。>60 → L1，70% → L0。
 *  - S3 跨轮同调用：同 fp 在 ≥K(3) 个不同 turn 都失败且无一次成功 → L2 冻结。
 *    L1 终止后同 fp 在后续 turn 立即复发 → 直接 L2（不再给第三次 L1）。
 *
 * 本对象是纯函数核心（全部可单测）；挂载在 AgentCore 工具环
 * （pipeToolExecutions，guardBatch 之后），处置阶梯的 IO 动作在挂载点实现：
 *  - L0 = ToolsComplete 携带 loopReminder（下一轮 user system-reminder 注入）
 *  - L1 = LlmFailed(LoopDetectedError) → 既有 fatal 链（supervisor notify /
 *    team 成员父 ExternalEvent(failed) / Done 事件 / 持久化）
 *  - L2 = LoopFreezeDetected → enterFrozen(FreezeReason.Loop) 待人工
 *
 * Root 例外（D3）：根 agent 只 L0 + L2（冻结+广播），不 L1——root 错误由
 * 用户裁决（watcher「根 agent 只广播不自动处置」政策对齐）。挂载点按
 * depth==0 过滤 Terminate。
 *
 * 豁免（D4）：S1 连续计数（成功即清零，flaky 败-败-成永不触发）；错误签名
 * 含变化内容（耗时/时间戳）→ 不同签名不累计；exemptTools 轮询类工具跳过
 * S1/S3（S2 仍适用）；用户权限拒绝不计（#12 劝停独立治理）。
 *
 * save-turn 豁免（D1/裁定②）：save 阶段有 guardSaveTurn 更严的 10 轮预算，
 * S2 双重治理无意义——挂载点按 pendingCompaction==Save 跳过 S2。
 */
object LoopGuard:

  /** D5 阈值配置（nebflow.json supervision.loopGuard，Defaults 兜底热读）。 */
  final case class Config(
    enabled: Boolean = true,
    identicalFailureSoft: Int = 3,
    identicalFailureHard: Int = 8,
    maxToolRoundsPerTurn: Int = 60,
    crossTurnFailureTurns: Int = 3,
    exemptTools: Set[String] = Set("TaskQuery", "TaskList", "TeamTaskList")
  )

  object Config:
    val Default: Config = Config()

  /** D5 配置热读（nebflow.json supervision.loopGuard；Guardrails.enabled 同款
    * 模式——无缓存每轮读盘，任意键缺失/解析失败 → Defaults 兜底）。 */
  def loadConfig: IO[Config] =
    IO.blocking {
      val configPath = nebflow.core.PathUtil.configJsonReadPath(nebflow.core.PathUtil.dataRoot)
      if !os.exists(configPath) then None
      else
        io.circe.parser.parse(os.read(configPath)).toOption.flatMap { root =>
          val c = root.hcursor.downField("supervision").downField("loopGuard")
          for
            enabled <- c.get[Boolean]("enabled").toOption
            soft <- c.get[Int]("identicalFailureSoft").toOption
            hard <- c.get[Int]("identicalFailureHard").toOption
            maxRounds <- c.get[Int]("maxToolRoundsPerTurn").toOption
            crossK <- c.get[Int]("crossTurnFailureTurns").toOption
            exempt <- c.get[List[String]]("exemptTools").toOption
          yield Config(enabled, soft, hard, maxRounds, crossK, exempt.toSet)
        }
    }.handleErrorWith(_ => IO.pure(None))
      .map(_.getOrElse(Config.Default))

  /** 会话级计数器（AgentState 顶层字段——S3 跨 turn，turn 边界只清 S1/S2）。
    * turnKey = state.loopTurnKey.toString（逻辑 turn 纪元：UserInput/外部事件
    * 唤醒/Mail 投递/冻结唤醒等 dispatch 起点 +1；ToolsComplete 续轮/retry/
    * save-compact 续跑不递增——currentTurnId 每次 LLM dispatch 都 +1，wiring
    * 实证不能当 turn 身份用）。 */
  final case class Counters(
    turnKey: String = "",
    roundCount: Int = 0,
    streakFp: String = "",
    streakErrHash: String = "",
    streakCount: Int = 0,
    /** fp → 曾 L1 终止过的集合（同 fp 复发 → 直接 L2，D3）。 */
    terminatedFps: Set[String] = Set.empty,
    /** fp → 失败过的 turn 集合（S3；该 fp 任一次成功即整条清除）。 */
    crossTurn: Map[String, Set[String]] = Map.empty,
    /** F2 防轮询记账：连续「唯一成功 Bash 且同 fp」轮的 fp 与计数（turn 边界归零）。 */
    sameBashFp: String = "",
    sameBashCount: Int = 0
  )

  object Counters:
    val Empty: Counters = Counters()

  /** 一轮工具批的单个观测事件（挂载点从 guardedBatch 构造）。 */
  final case class RoundEvent(
    toolName: String,
    args: Json,
    isError: Boolean,
    errorText: String,
    permissionDenied: Boolean
  )

  /** 成功即视为「有进展轮」的工具集（S2 progress 轮不计）。F2（2026-08-29，
    * loop-detected 报告 §6）：验证型工作的主体是成功的 Bash/Read/Grep——
    * 08-28 十起 S2 误杀全是「写盘稀疏但真验证」形态（跑测试/查日志/读代码），
    * 只认 Edit/Write 会把合法验证工作推出 60 轮预算。同 hash 无效写由
    * guardSaveTurn 在 save 阶段独立治理（单一职责）。 */
  private val ProgressTools: Set[String] =
    Set("Edit", "Write", "TaskUpdate", "TaskCreate", "TeamTaskCreate", "TeamTaskUpdate",
        "Bash", "Read", "Grep")

  /** F2 反向规则（报告 option a 的附带条件）：同 fp 的成功 Bash 连续超过该值
    * 视为轮询（watch/sleep 循环每轮都「成功」），此后该轮计回非进展——S2 预算
    * 仍然管得住轮询循环。 */
  private val BashPollBackstop = 3

  sealed trait Verdict extends Product with Serializable
  object Verdict:
    /** L0：向消息流注入提醒（零成本给模型自纠机会）。 */
    final case class Warn(msg: String) extends Verdict
    /** L1：终止当前 turn（LoopDetected 失败链）。Root 豁免（挂载点过滤）。 */
    final case class Terminate(msg: String, fp: String) extends Verdict
    /** L2：冻结会话待人工（enterFrozen("loop") + 广播 + 父通知）。 */
    final case class Freeze(msg: String) extends Verdict
    case object Pass extends Verdict

  // ── 指纹 ──────────────────────────────────────────────────

  /** fp = sha256(toolName + canonicalJson(args)).take(12) —— 同参判定。 */
  def fingerprint(toolName: String, args: Json): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest((toolName + "|" + canonicalize(args)).getBytes("UTF-8"))
    digest.map(b => f"${b & 0xff}%02x").mkString.take(12)

  /** 错误签名 = sha256(错误文本前 120 字符)——「File does not exist: <同一路径>」
    * 恒定；「timeout after 30s / 31s」不同（含变化内容的 flaky 天然豁免）。 */
  def errorSignature(errorText: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(errorText.take(120).getBytes("UTF-8"))
    digest.map(b => f"${b & 0xff}%02x").mkString.take(12)

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
            case None      => j
    norm(json).noSpaces

  // ── 判定核心（纯函数） ─────────────────────────────────────

  /** 一轮（一个 LLM 响应的工具批）的评估。
    *
    * @param events   本轮全部工具结果（含 dropped「Tool not available」形态）
    * @param turnKey  当前 turn 身份（currentTurnId.toString）——turn 边界自动
    *                 重置 S1/S2 计数，S3 按不同 turnKey 计数
    * @param counters 会话级计数器（上个状态）
    * @param cfg      阈值配置
    * @param s2Exempt save-turn 等场景跳过 S2 轮预算（guardSaveTurn 独立治理）
    * @return 更新后的计数器 + 处置裁决（一轮至多一条非 Pass 裁决，强度
    *         Freeze > Terminate > Warn）
    */
  def evaluate(
    events: List[RoundEvent],
    turnKey: String,
    counters: Counters,
    cfg: Config,
    s2Exempt: Boolean = false
  ): (Counters, Verdict) =
    if !cfg.enabled then (counters, Verdict.Pass)
    else
      // turn 边界：S1/S2 归零，S3（crossTurn/terminatedFps）跨 turn 保留
      val base =
        if counters.turnKey != turnKey then counters.copy(turnKey = turnKey, roundCount = 0,
          streakFp = "", streakErrHash = "", streakCount = 0, sameBashFp = "", sameBashCount = 0)
        else counters

      // F2 防轮询：本轮若恰为一轮成功 Bash 且 fp 与上轮相同 → 连续计数；超过
      // backstop 后本轮不再算进展（S2 预算接管轮询循环）。
      val roundBashFps = events.filter(e => !e.isError && e.toolName == "Bash")
        .map(e => fingerprint(e.toolName, e.args)).distinct
      val (nextBashFp, nextBashCount, bashPollStalled) = roundBashFps match
        case List(fp) if base.sameBashFp == fp =>
          val n = base.sameBashCount + 1
          (fp, n, n > BashPollBackstop)
        case List(fp) => (fp, 1, false)
        case _ => ("", 0, false)
      val roundHasProgress =
        events.exists(e => !e.isError && ProgressTools.contains(e.toolName)) && !bashPollStalled
      val base2 = base.copy(sameBashFp = nextBashFp, sameBashCount = nextBashCount)
      val roundCount = if s2Exempt then base2.roundCount else base2.roundCount + (if roundHasProgress then 0 else 1)

      // 逐事件折叠 S1 streak / S3 crossTurn
      val counted = events.foldLeft(base2.copy(roundCount = roundCount)) { (acc, e) =>
        val exempt = cfg.exemptTools.contains(e.toolName)
        val counted = e.isError && !exempt && !e.permissionDenied
        if !counted then
          // 成功事件：同 fp 的 streak 清零 + crossTurn 整条清除（「无一次成功」语义）
          val fp = fingerprint(e.toolName, e.args)
          val streakReset = if acc.streakFp == fp then acc.copy(streakFp = "", streakCount = 0, streakErrHash = "") else acc
          streakReset.copy(
            crossTurn = streakReset.crossTurn - fp,
            terminatedFps = streakReset.terminatedFps - fp
          )
        else
          val fp = fingerprint(e.toolName, e.args)
          val sig = errorSignature(e.errorText)
          val (sf, sh, sc) =
            if acc.streakFp == fp && acc.streakErrHash == sig then (acc.streakFp, acc.streakErrHash, acc.streakCount + 1)
            else (fp, sig, 1)
          acc.copy(
            streakFp = sf, streakErrHash = sh, streakCount = sc,
            crossTurn = acc.crossTurn.updated(fp, acc.crossTurn.getOrElse(fp, Set.empty) + turnKey)
          )
      }

      // 裁决（强度序）：S3 冻结 > L1 后复发冻结 > S1 hard 终止 > S2 超预算终止 > L0 警告
      val crossTurnHit = counted.crossTurn.find { case (_, turns) => turns.size >= cfg.crossTurnFailureTurns }
      val recurrenceHit = events.filter(e => e.isError && !e.permissionDenied &&
        !cfg.exemptTools.contains(e.toolName))
        .map(e => fingerprint(e.toolName, e.args))
        .find(counted.terminatedFps.contains)
      val s1Hard = counted.streakCount >= cfg.identicalFailureHard && counted.streakFp.nonEmpty
      val s2Over = !s2Exempt && roundCount > cfg.maxToolRoundsPerTurn

      val verdict: Verdict =
        crossTurnHit match
          case Some((fp, turns)) =>
            Verdict.Freeze(
              s"loop-detected: identical call failed in ${turns.size} separate turns (fp=$fp) with no success — session frozen pending human/Manager decision"
            )
          case None =>
            recurrenceHit match
              case Some(fp) =>
                Verdict.Freeze(
                  s"loop-detected: call fp=$fp was terminated earlier this session and immediately failed again — session frozen pending human/Manager decision"
                )
              case None =>
                if s1Hard then
                  Verdict.Terminate(
                    s"loop-detected: tool call with identical arguments failed ${counted.streakCount} times with the same error — turn terminated. Change approach or abandon this path; if re-dispatched, repeating the same call will freeze the session.",
                    counted.streakFp
                  )
                else if s2Over then
                  Verdict.Terminate(
                    s"loop-detected: tool-round budget exceeded ($roundCount rounds with no structural progress this turn) — turn terminated.",
                    ""
                  )
                else if counted.streakCount == cfg.identicalFailureSoft && counted.streakFp.nonEmpty then
                  Verdict.Warn(
                    s"tool '${counted.streakFp}' has failed ${counted.streakCount} times with identical arguments and the same error — change the approach or abandon this path before it is force-terminated"
                  )
                else if !s2Exempt && roundCount == math.max(1, cfg.maxToolRoundsPerTurn * 7 / 10) then
                  Verdict.Warn(
                    s"tool-round budget warning: $roundCount of ${cfg.maxToolRoundsPerTurn} non-progress rounds this turn — wrap up or the turn will be terminated"
                  )
                else Verdict.Pass

      // Terminate 裁决记账：fp 进 terminatedFps（空 fp 的 S2 终止不记）
      val finalCounters = verdict match
        case t: Verdict.Terminate if t.fp.nonEmpty =>
          counted.copy(terminatedFps = counted.terminatedFps + t.fp)
        case _ => counted

      (finalCounters, verdict)

  /** L0 提醒消息文本（ToolsComplete.loopReminder → 下一轮 user 注入）。 */
  def reminderMessage(warn: Verdict.Warn): String =
    s"""<system-reminder>
       |Loop guard: ${warn.msg}. This is an automated safeguard against token-burning loops.
       |</system-reminder>""".stripMargin

end LoopGuard
