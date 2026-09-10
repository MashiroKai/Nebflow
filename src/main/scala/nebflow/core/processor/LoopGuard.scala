package nebflow.core.processor

import cats.effect.IO
import io.circe.Json

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
 *    劝停独立治理的既有豁免语义延续）。
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
 * 兜底关系：预算帽删除后，超长 turn 的最后防线 = TaskStuckWatcher 卡死检测——
 * 2026-09-10 换轴后为「agent 侧 10min 零事件（无活动）∪ 单个工具调用持续
 * 超 10min 且 turn 未完成（进程占死）」两条 agent 侧判据（**不引用进程 CPU**，
 * 取证 20260910_130621）：R 检测抓「有活动的循环」，watcher 抓「零进展的
 * 挂起/占死」，两者判据正交无重叠误杀面。本检测器与本次换轴无耦合——循环
 * 形态的每次工具失败都是 agent 侧事件（仍持续 touch 活动戳），工具相位亦随
 * 每个短调用重置，故本检测器职责不变。
 */
object LoopGuard:

  /** D5 阈值配置（nebflow.json supervision.loopGuard，Defaults 兜底热读）。 */
  final case class Config(
    enabled: Boolean = true,
    identicalFailureSoft: Int = 3,
    identicalFailureHard: Int = 8,
    identicalCallHard: Int = 10,
    identicalTextHard: Int = 10,
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
            callHard <- c.get[Int]("identicalCallHard").toOption
            textHard <- c.get[Int]("identicalTextHard").toOption
            crossK <- c.get[Int]("crossTurnFailureTurns").toOption
            exempt <- c.get[List[String]]("exemptTools").toOption
          yield Config(enabled, soft, hard, callHard, textHard, crossK, exempt.toSet)
        }
    }.handleErrorWith(_ => IO.pure(None))
      .map(_.getOrElse(Config.Default))

  /** 会话级计数器（AgentState 顶层字段——S3 跨 turn，turn 边界只清 S1 与
    * R 连续计数）。turnKey = state.loopTurnKey.toString（逻辑 turn 纪元：
    * UserInput/外部事件唤醒/Mail 投递/冻结唤醒等 dispatch 起点 +1；
    * ToolsComplete 续轮/retry/save-compact 续跑不递增——currentTurnId 每次
    * LLM dispatch 都 +1，wiring 实证不能当 turn 身份用）。 */
  final case class Counters(
    turnKey: String = "",
    streakFp: String = "",
    streakErrHash: String = "",
    streakCount: Int = 0,
    /** R-call：上一支调用（连续同参计数；不同 fp 即刷新）。 */
    lastCallFp: String = "",
    lastCallTool: String = "",
    lastCallCount: Int = 0,
    /** R-text：上一轮助手文本（连续逐字相同计数；不同文本即刷新）。 */
    lastTextHash: String = "",
    lastTextCount: Int = 0,
    /** fp → 曾 L1 终止过的集合（同 fp 复发 → 直接 L2，D3）。 */
    terminatedFps: Set[String] = Set.empty,
    /** fp → 失败过的 turn 集合（S3；该 fp 任一次成功即整条清除）。 */
    crossTurn: Map[String, Set[String]] = Map.empty
  ):
    /** AgentControl list 的 loop×N 展示值（两路连续计数的较大者）。 */
    def repeatStreak: Int = math.max(lastCallCount, lastTextCount)

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

  /** R-text 文本指纹 = sha256(整轮助手文本)。 */
  private def textHash(text: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
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
    * @param events        本轮全部工具结果（含 dropped「Tool not available」形态）
    * @param turnKey       当前 turn 身份（currentTurnId.toString）——turn 边界自动
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
      // turn 边界：S1/R 归零（新用户消息 = 打断），S3（crossTurn/terminatedFps）跨 turn 保留
      val base =
        if counters.turnKey != turnKey then counters.copy(turnKey = turnKey,
          streakFp = "", streakErrHash = "", streakCount = 0,
          lastCallFp = "", lastCallTool = "", lastCallCount = 0,
          lastTextHash = "", lastTextCount = 0)
        else counters

      // 逐事件折叠：S1 streak（仅失败）/ S3 crossTurn / R-call 连续同参（成败皆计）
      val counted = events.foldLeft(base) { (acc, e) =>
        val fp = fingerprint(e.toolName, e.args)
        // R-call：权限拒绝不计（#12 独立治理）；exemptTools（轮询等待）不计；
        // 成败皆计、成功不清零——只有「不同的调用」才刷新连续计数（作者裁定⑤）
        val (lfp, ltool, lcnt) =
          if e.permissionDenied || cfg.exemptTools.contains(e.toolName) then
            (acc.lastCallFp, acc.lastCallTool, acc.lastCallCount)
          else if acc.lastCallFp == fp then (fp, e.toolName, acc.lastCallCount + 1)
          else (fp, e.toolName, 1)
        val accR = acc.copy(lastCallFp = lfp, lastCallTool = ltool, lastCallCount = lcnt)

        val counted = e.isError && !cfg.exemptTools.contains(e.toolName) && !e.permissionDenied
        if !counted then
          // S1/S3 视角的成功事件：同 fp 的 streak 清零 + crossTurn 整条清除
          val streakReset = if accR.streakFp == fp then accR.copy(streakFp = "", streakCount = 0, streakErrHash = "") else accR
          streakReset.copy(
            crossTurn = streakReset.crossTurn - fp,
            terminatedFps = streakReset.terminatedFps - fp
          )
        else
          val sig = errorSignature(e.errorText)
          val (sf, sh, sc) =
            if accR.streakFp == fp && accR.streakErrHash == sig then (accR.streakFp, accR.streakErrHash, accR.streakCount + 1)
            else (fp, sig, 1)
          accR.copy(
            streakFp = sf, streakErrHash = sh, streakCount = sc,
            crossTurn = accR.crossTurn.updated(fp, accR.crossTurn.getOrElse(fp, Set.empty) + turnKey)
          )
      }

      // R-text：空文本不计不刷新；不同文本刷新归零（作者裁定⑤）
      val withText =
        if assistantText.isEmpty then counted
        else
          val th = textHash(assistantText)
          if counted.lastTextHash == th then counted.copy(lastTextHash = th, lastTextCount = counted.lastTextCount + 1)
          else counted.copy(lastTextHash = th, lastTextCount = 1)

      // 裁决（强度序）：S3 冻结 > L1 后复发冻结 > S1 hard 终止 > R-call > R-text > L0 警告
      val crossTurnHit = withText.crossTurn.find { case (_, turns) => turns.size >= cfg.crossTurnFailureTurns }
      val recurrenceHit = events.filter(e => e.isError && !e.permissionDenied &&
        !cfg.exemptTools.contains(e.toolName))
        .map(e => fingerprint(e.toolName, e.args))
        .find(withText.terminatedFps.contains)
      val s1Hard = withText.streakCount >= cfg.identicalFailureHard && withText.streakFp.nonEmpty
      val callRepeatHit = withText.lastCallCount >= cfg.identicalCallHard
      val textRepeatHit = withText.lastTextCount >= cfg.identicalTextHard

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
                    s"loop-detected: tool call with identical arguments failed ${withText.streakCount} times with the same error — turn terminated. Change approach or abandon this path; if re-dispatched, repeating the same call will freeze the session.",
                    withText.streakFp
                  )
                else if callRepeatHit then
                  Verdict.Terminate(
                    s"loop-detected: tool '${withText.lastCallTool}' has now been called ${withText.lastCallCount} times in a row with identical arguments — turn terminated to avoid spinning. Change the approach; for long-running work use run_in_background instead of repeating the same call.",
                    withText.lastCallFp
                  )
                else if textRepeatHit then
                  Verdict.Terminate(
                    s"loop-detected: the assistant has produced the identical text output ${withText.lastTextCount} rounds in a row — turn terminated. Break the repetition: change the response or stop.",
                    ""
                  )
                else if withText.streakCount == cfg.identicalFailureSoft && withText.streakFp.nonEmpty then
                  Verdict.Warn(
                    s"tool '${withText.streakFp}' has failed ${withText.streakCount} times with identical arguments and the same error — change the approach or abandon this path before it is force-terminated"
                  )
                else Verdict.Pass

      // Terminate 裁决记账：fp 进 terminatedFps（R-text 终止无 fp，不记）
      val finalCounters = verdict match
        case t: Verdict.Terminate if t.fp.nonEmpty =>
          withText.copy(terminatedFps = withText.terminatedFps + t.fp)
        case _ => withText

      (finalCounters, verdict)

  /** L0 提醒消息文本（ToolsComplete.loopReminder → 下一轮 user 注入）。 */
  def reminderMessage(warn: Verdict.Warn): String =
    s"""<system-reminder>
       |Loop guard: ${warn.msg}. This is an automated safeguard against token-burning loops.
       |</system-reminder>""".stripMargin

end LoopGuard
