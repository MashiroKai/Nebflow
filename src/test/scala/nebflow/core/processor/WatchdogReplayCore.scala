package nebflow.core.processor

import io.circe.Json
import io.circe.parser

/**
 * **wd-fix 离线重放核**（2026-09-12 `wdreplayfix` 批，R1′）——把 `WatchdogCriteriaReplaySpec`
 * 原有的「全语料一律断言旧判据保真」抽成**可注入、可分段**的纯函数面，供主用例与对照
 * fixture 用例共用（同一套判据、同一套读数，两处引用同一实现 ⇒ 无漂移）。
 *
 * ## 为什么分段（R1′ 的根因，逐字采纳 verify 判读）
 *
 * 原 spec 的保真度自证（`legacyMismatch == Nil`）与两侧计数相等断言，隐含前提是
 * **「当日语料全部由修复前构建产出」**。宿主一旦换成含修复的构建（本轮：19:38:54 重启），
 * 窗口后新行由**新判据**写成，旧判据重放必然不一致 ⇒ mismatch **单调增长**、`sbt test`
 * 常红。判据的时间不变性因此必须按**构建出处**分段：
 *
 *   - **窗口前段**（旧构建写的行）：断言**旧判据重放保真**（class / branch / 两侧计数）——
 *     这是「旧判据重实现正确 + 事件行→AgentRecord 重建口径正确」的自证；
 *   - **窗口后段**（修复构建写的行）：断言**新判据重放保真**（`newCls == 日志 class`）——
 *     这是「生产写入与离线重放同口径」的自证（对修复构建的行成立，且与语料增长无关）；
 *   - **全语料（含窗口后行）**：只断言**口径不变式**——`false-positive → true-stuck` 翻转 = 0、
 *     `violations`（放走真卡死）= 0、命名会话 `newTrue = 0`。这三条**时不变**：任何构建写的行
 *     都不准违反，故不缩到窗口前段。
 *
 * ## 窗口起点怎么来（禁写死会过期的日期常量）
 *
 * 两级，**都自校准/可参数化**（`deriveWindowStart` / `parseWindowStart`）：
 *
 *   1. **参数覆盖**：system prop `nebflow.watchdog.replayWindowStart`（epoch 毫秒，或
 *      ISO-8601 本地时刻如 `2026-09-12T20:28:52`）——手工指定窗口起点（换语料/复算旧窗口用）；
 *   2. **缺省自校准 = 语料自身的构建出处标记**（`provenanceOf`）：wd-fix 改动同时改了
 *      两条臂的 note 文案，**修复前构建**只写 `no progress signal (last=…` /
 *      `still inside its authorised window`；**修复构建**的 note 一律带 `window Ns`
 *      机器可读读数（两条臂都写）。故 `窗口起点 := 首个带修复构建文案的行 ts`（行按 ts 排序取首个）。
 *
 * 因此窗口起点**不绑定任何日期常量**：换一次宿主重启、换一份语料、甚至语料里一行修复
 * 构建的行都没有（⇒ 起点 `None`，全语料按窗口前段处理、后段段 = 未证），判据都稳定。
 * 无法判定「旧判据是否该复现这一行」的行（`Unknown` 出处，例如两条构建**逐字相同**的
 * 「无工具相位」分支）**不参与任何保真度断言**、只计数并显式申报——**不用松弛手段换绿**。
 *
 * ## 逐字重放口径（与 wd-fix 批报告 §3 / verify 报告 §③ 一致）
 *
 *   - `lastActivityMs      = ts - agentIdleMs`
 *   - `currentToolStartedAt = ts - toolPhaseMs`（`toolPhaseMs == 0` ⇒ 0，无工具相位）
 *   - `authorised`（秒）从 note 抽取（class-1 形态 `authorised Ns`；class-2 形态 `≤ Ns`）
 *     ⇒ `effectiveMs = authorised * 1000`；`declaredMs = max(0, effectiveMs - ToolDeadlineSlackMs)`
 *     **当且仅当** `effectiveMs > ToolPhaseStuckMs`（否则取 0——此时 `max(默认档, 声明+宽限)`
 *     与「声明 = 0」逐字同解，故重放**精确**，不是近似）。
 *   - `lastProgressSignalAt  = ts - progressAgoMs`（`progress signal Ns ago` / `last=Ns ago`；
 *     `never` ⇒ 0）
 *
 * **旧判据**在本核内**逐字重实现**（`legacyClassify`，与 wd-fix 批改动前的
 * `TaskStuckWatcher.classify` 同构）——它不进生产代码；**新判据**直接调生产
 * [[TaskStuckWatcher.classify]]，不重实现（避免「重放器自己写一套新逻辑」的漂移）。
 *
 * ## 判别力（对照 fixture）
 *
 * `WatchdogReplaySegmentationSpec` 用提交进仓的对照 fixture（
 * `src/test/resources/wd-replay/contrast-fixture.jsonl`）证本核**非空洞**：
 * 注入一处 `false-positive → true-stuck` 翻转 ⇒ `invariantVerdicts` 判红且点数命中会话；
 * 回退到旧形态（[[wholeCorpusLegacyVerdicts]]，全语料一律断言旧判据保真）在同一 fixture 上
 * 判红 4 条而**新形态的窗口前段保真度断言在同一 fixture 上 0 条**（分段不是把断言改恒真）。
 */
object WatchdogReplayCore:

  // ── 1. 行 / 构建出处 / 段落 / 窗口 ──────────────────────────────────────────

  /** 事件行（只看判据消费面；其余字段与本核无关）。 */
  final case class Row(
    ts: Long,
    sid: String,
    loggedClass: String,
    loggedBranch: String,
    toolPhaseMs: Long,
    agentIdleMs: Long,
    note: String
  )

  /**
   * 行的**构建出处**（由 note 文案形态判定，见头注「窗口起点怎么来」）。
   *   - `Legacy`     = 只可能由**修复前构建**写成（旧文案标记）；
   *   - `CurrentFix` = 只可能由**修复构建**写成（新文案标记，`, window Ns` 读数）；
   *   - `Unknown`    = 两构建文案**逐字相同**的分支（无出处信息）⇒ 不参与保真度断言。
   */
  enum Provenance:
    case Legacy, CurrentFix, Unknown

  /** 窗口起点（`None` = 语料内无出处标记 ⇒ 起点不可判定）。`source` 逐字读入报告。 */
  final case class Window(start: Option[Long], source: String)

  /**
   * 一个段落的重放读数。`asserted` = 参与该段保真度断言的**行数**（出处已定的行）；
   * `excluded` = 出处不明、只计数不断言的行数。
   */
  final case class Segment(
    label: String,
    rows: Int,
    asserted: Int,
    excluded: Int,
    branchMismatch: Vector[String],
    legacyMismatch: Vector[String],
    newMismatch: Vector[String],
    oldTrue: Int,
    oldFalse: Int,
    loggedTrue: Int,
    loggedFalse: Int
  )

  final case class SessionStat(n: Int, loggedTrue: Int, newTrue: Int, flipped: Int)

  final case class Flipped(sid: String, ts: Long, progressAgoMs: Long, windowMs: Long)

  /** 全语料重放读数（分段读数见 `pre` / `post`）。 */
  final case class Result(
    total: Int,
    window: Window,
    pre: Segment,
    post: Segment,
    /** 出处不明的行（`sid@ts`；只申报、不断言）。 */
    unknownRows: Vector[String],
    /** 语料自相矛盾的行（旧文案出现在窗口起点之后等；只申报、不断言）。 */
    anomalies: Vector[String],
    /**
     * **旧形态读数**：全语料（含窗口后行）旧判据重放与日志 class 的不一致条数——
     * 反向对照用（新形态只看 `pre.legacyMismatch`）。
     */
    allLegacyMismatch: Vector[String],
    fpToTrue: Vector[String],
    leakedTrueStuck: Vector[String],
    flipped: Vector[Flipped],
    bySession: Map[String, SessionStat],
    loggedTrue: Int,
    loggedFalse: Int,
    newTrue: Int,
    newFalse: Int
  ):
    /** 修复面读数：`true-stuck` 由日志的 `loggedTrue` 降到的 `newTrue`（负数 = 减少）。 */
    def trueStuckDelta: Int = newTrue - loggedTrue
  end Result

  // ── 2. 解析 / 出处 / 窗口起点 ──────────────────────────────────────────────

  /** 旧构建文案标记（任一命中 ⇒ `Legacy`）。 */
  private val LegacyMarkers: List[String] =
    List("no progress signal (last=", "still inside its authorised window")

  /**
   * 修复构建文案标记（任一命中 ⇒ `CurrentFix`）。
   *
   * `window Ns` **机器可读读数**是最一般的判别式：wd-fix 后两条臂的 note 都带该读数
   * （`(progress signal Ns ago, window Ns)` / `(no fresh …, window Ns)`），而修复前的两条臂
   * note 写的是 `still inside its authorised window (1617s ≤ 3060s)`——**没有** `window Ns`
   * 读数（语料实测：153+318 行旧文案命中 0）。前两个标记是逐臂的冗余保险。
   */
  private val FixMarkers: List[String] =
    List("no fresh progress signal", "over its authorised window")

  private val FixWindowRe = """window (\d+)s""".r

  def provenanceOf(note: String): Provenance =
    if LegacyMarkers.exists(note.contains) then Provenance.Legacy
    else if FixMarkers.exists(note.contains) || FixWindowRe.findFirstIn(note).isDefined then Provenance.CurrentFix
    else Provenance.Unknown

  /**
   * 窗口起点参数（`nebflow.watchdog.replayWindowStart`）：纯数字 = epoch 毫秒；
   * 其余按 ISO-8601 本地时刻（`2026-09-12T20:28:52`）解析。解析失败抛异常（参数写错要炸，
   * 不许静默回落成「全语料窗口前段」）。
   */
  def parseWindowStart(v: String): Long =
    val t = v.trim
    if t.nonEmpty && t.forall(_.isDigit) then t.toLong
    else java.time.LocalDateTime.parse(t).atZone(java.time.ZoneId.systemDefault()).toInstant.toEpochMilli

  /** 缺省窗口起点 = **首个带修复构建文案的行**的 ts（rows 须已按 ts 排序）。 */
  def deriveWindowStart(rows: Vector[Row]): (Option[Long], String) =
    val fixRows = rows.filter(r => provenanceOf(r.note) == Provenance.CurrentFix)
    fixRows.headOption match
      case Some(r) =>
        (
          Some(r.ts),
          "corpus: min ts of rows carrying the wd-fix note provenance (" +
            r.sid + "@" + r.ts + "; " + fixRows.size + " such rows)"
        )
      case None =>
        (
          None,
          "corpus: no row carries the wd-fix note provenance ⇒ 窗口起点不可判定" +
            "（全语料按窗口前段处理；窗口后段段 = 未证）"
        )

  end deriveWindowStart

  /** 事件行 → [[Row]]（非 `stuck-detected` 行或解析失败 ⇒ `None`）。 */
  def rowOf(json: Json): Option[Row] =
    val h = json.hcursor
    if !h.get[String]("type").toOption.contains(TaskStuckWatcher.StuckDetectedType) then None
    else
      h.get[Long]("ts").toOption.map { ts =>
        Row(
          ts = ts,
          sid = h.get[String]("sessionId").getOrElse("?"),
          loggedClass = h.get[String]("class").getOrElse("?"),
          loggedBranch = h.get[String]("branch").getOrElse("?"),
          toolPhaseMs = h.get[Long]("toolPhaseMs").getOrElse(0L),
          agentIdleMs = h.get[Long]("agentIdleMs").getOrElse(0L),
          note = h.get[String]("note").getOrElse("")
        )
      }

  end rowOf

  /**
   * 注入面①：从**行内容**（JSONL 文本行）取语料——主用例读生产语料、对照用例读 fixture
   * 走的都是这条路径（同一实现）。
   */
  def rowsFromLines(lines: Iterable[String]): Vector[Row] =
    lines.iterator.flatMap(l => parser.parse(l).toOption).flatMap(rowOf).toVector

  /** 注入面②：从**语料文件**取语料（`munitIgnore` 的前置存在性检查由调用方做）。 */
  def rowsFromFile(path: os.Path): Vector[Row] = rowsFromLines(os.read.lines(path))

  // ── 3. 重建 + 旧判据（逐字重实现，仅重放用）────────────────────────────────

  private val AuthorisedRe = """authorised (\d+)s""".r
  private val AuthorisedLeRe = """≤ (\d+)s""".r
  private val ProgressRe = """progress signal (\d+)s ago""".r
  private val LastRe = """last=(\d+)s ago""".r

  private def sl: Long = nebflow.shared.Defaults.ToolDeadlineSlackMs

  def authorisedSecs(note: String): Option[Long] =
    AuthorisedRe
      .findFirstMatchIn(note)
      .map(_.group(1).toLong)
      .orElse(AuthorisedLeRe.findFirstMatchIn(note).map(_.group(1).toLong))

  def progressAgoMs(note: String): Option[Long] =
    ProgressRe
      .findFirstMatchIn(note)
      .map(_.group(1).toLong)
      .map(_ * 1000L)
      .orElse(LastRe.findFirstMatchIn(note).map(_.group(1).toLong).map(_ * 1000L))

  /** 事件行 → `AgentRecord` 重建（口径见头注，逐字沿用 wd-fix 批）。 */
  def rebuild(r: Row): nebflow.actor.AgentRecord =
    val authorisedMs =
      authorisedSecs(r.note).getOrElse(nebflow.shared.Defaults.ToolPhaseStuckMs / 1000L) * 1000L
    val declaredMs =
      if authorisedMs > nebflow.shared.Defaults.ToolPhaseStuckMs then authorisedMs - sl else 0L
    nebflow.actor.AgentRecord(
      sessionId = "replay",
      ref = null.asInstanceOf[nebflow.actor.ActorRef[nebflow.actor.AgentCommand]],
      kind = nebflow.actor.AgentKind.Flow,
      rootSessionId = "replay-root",
      startedAt = r.ts - 24 * 60 * 60 * 1000L,
      status = nebflow.actor.AgentStatus.Processing,
      lastActivityMs = if r.agentIdleMs > 0 then r.ts - r.agentIdleMs else 0L,
      currentToolName = Some("Bash"),
      currentToolStartedAt = if r.toolPhaseMs > 0 then r.ts - r.toolPhaseMs else 0L,
      currentToolDeadlineMs = declaredMs,
      lastProgressSignalAt = progressAgoMs(r.note).fold(0L)(age => r.ts - age)
    )

  end rebuild

  /** 旧判据（wd-fix 批改动前形态；逐字重实现，仅重放用，不进生产）。 */
  def legacyClassify(rec: nebflow.actor.AgentRecord, a: TaskStuckWatcher.StuckAssessment, now: Long): String =
    val effective = ToolStuckJudgment.effectiveToolPhaseMs(
      nebflow.shared.Defaults.ToolPhaseStuckMs,
      rec.currentToolDeadlineMs,
      nebflow.shared.Defaults.ToolDeadlineSlackMs
    )
    val progress = TaskStuckWatcher.hasProgressSignal(rec, now, nebflow.shared.Defaults.StuckProgressSignalWindowMs)
    if rec.currentToolStartedAt > 0 then
      if a.toolPhaseMs <= effective && progress then TaskStuckWatcher.ClassFalsePositive
      else TaskStuckWatcher.ClassTrueStuck
    else TaskStuckWatcher.ClassTrueStuck

  /** 正信号在**新窗**下是否新鲜 + 新窗实值——「放走真卡死」的机械检查用。 */
  def freshness(rec: nebflow.actor.AgentRecord, now: Long): (Boolean, Long) =
    val w = TaskStuckWatcher.effectiveProgressWindowMs(rec.currentToolDeadlineMs)
    (TaskStuckWatcher.hasProgressSignal(rec, now, w), w)

  private final case class RowRead(
    loggedClass: String,
    branchMismatch: Option[String],
    legacyMismatch: Option[String],
    newMismatch: Option[String],
    oldCls: String,
    newCls: String,
    fresh: Boolean,
    window: Long,
    progressAgo: Long
  )

  private def read(r: Row): RowRead =
    val rec = rebuild(r)
    TaskStuckWatcher.assessDetailed(rec, r.ts) match
      case None =>
        RowRead(
          r.loggedClass,
          None,
          Some(r.sid + "@" + r.ts + ": 重放判据未命中（日志却有 class）——重建口径有误"),
          Some(r.sid + "@" + r.ts + ": 重放判据未命中（日志却有 class）——重建口径有误"),
          TaskStuckWatcher.ClassTrueStuck,
          TaskStuckWatcher.ClassTrueStuck,
          fresh = false,
          window = 0L,
          progressAgo = progressAgoMs(r.note).getOrElse(-1L)
        )
      case Some(a) =>
        val oldCls = legacyClassify(rec, a, r.ts)
        val newCls = TaskStuckWatcher.classify(rec, a, r.ts, inflight = 0).cls
        val (fresh, window) = freshness(rec, r.ts)
        val branchBad =
          if a.branch != r.loggedBranch then
            Some(r.sid + "@" + r.ts + ": 重放 branch=" + a.branch + " ≠ 日志 " + r.loggedBranch)
          else None
        val legacyBad =
          if oldCls != r.loggedClass then
            Some(
              r.sid + "@" + r.ts + ": 旧判据重放=" + oldCls + " ≠ 日志 " + r.loggedClass +
                "（toolPhaseMs=" + r.toolPhaseMs + " authorised=" + authorisedSecs(r.note) +
                " progressAgo=" + progressAgoMs(r.note) + "）"
            )
          else None
        val newBad =
          if newCls != r.loggedClass then
            Some(
              r.sid + "@" + r.ts + ": 新判据重放=" + newCls + " ≠ 日志 " + r.loggedClass +
                "（toolPhaseMs=" + r.toolPhaseMs + " authorised=" + authorisedSecs(r.note) +
                " progressAgo=" + progressAgoMs(r.note) + "）"
            )
          else None
        RowRead(
          r.loggedClass,
          branchBad,
          legacyBad,
          newBad,
          oldCls,
          newCls,
          fresh,
          window,
          progressAgoMs(r.note).getOrElse(-1L)
        )
    end match
  end read

  // ── 4. 重放主体（分段）────────────────────────────────────────────────────

  /** 全量重放 + 分段。`windowStartOverride` = [[parseWindowStart]] 的结果（`None` = 自校准）。 */
  def run(input: Iterable[Row], windowStartOverride: Option[Long] = None): Result =
    val rows = input.toVector.sortBy(_.ts)
    val derived = deriveWindowStart(rows)
    val window = windowStartOverride match
      case Some(t) => Window(Some(t), "param: nebflow.watchdog.replayWindowStart=" + t)
      case None => Window(derived._1, derived._2)
    val start = window.start

    val preLabel = "窗口前段"
    val postLabel = "窗口后段"
    val preBranch = Vector.newBuilder[String]
    val preLegacy = Vector.newBuilder[String]
    val postNew = Vector.newBuilder[String]
    val allLegacy = Vector.newBuilder[String]
    val unknown = Vector.newBuilder[String]
    val anomal = Vector.newBuilder[String]
    val fpToTrue = Vector.newBuilder[String]
    val leaked = Vector.newBuilder[String]
    val flipped = Vector.newBuilder[Flipped]
    val bySession = scala.collection.mutable.LinkedHashMap.empty[String, SessionStat]

    var preRows, preAsserted, preExcluded = 0
    var postRows, postAsserted, postExcluded = 0
    var preOldTrue, preOldFalse, preLoggedTrue, preLoggedFalse = 0
    var loggedTrue, loggedFalse, newTrue, newFalse = 0

    rows.foreach { r =>
      val p = provenanceOf(r.note)
      val rd = read(r)
      val inPre = start.forall(r.ts < _)
      // (a) 分段归属（只按要求断言；出处不明/自相矛盾的行一律只计数、显式申报）
      if p == Provenance.Unknown then
        unknown += (r.sid + "@" + r.ts)
        if inPre then
          // 出处不明且落在窗口前段：只计数、不断言（见头注）。两侧计数口径与断言行同集 ⇒
          // 不把它们计入 `preLoggedTrue/preLoggedFalse`（否则「两侧计数相等」会假红）。
          preRows += 1; preExcluded += 1
        else
          postRows += 1; postExcluded += 1
      else if p == Provenance.Legacy && !inPre then
        anomal += (r.sid + "@" + r.ts + ": 旧构建文案出现在窗口起点之后（起点=" +
          start.map(_.toString).getOrElse("-") + "）——起点或语料顺序存疑，该行不计入任何保真度断言")
        postRows += 1; postExcluded += 1
      else if p == Provenance.CurrentFix && inPre then
        anomal += (r.sid + "@" + r.ts + ": 修复构建文案出现在窗口起点之前（起点=" +
          start.map(_.toString).getOrElse("-") + "，来源 " + window.source +
          "）——手工参数与语料不符，该行不计入任何保真度断言")
        preRows += 1; preExcluded += 1
      else if inPre then
        preRows += 1; preAsserted += 1
        rd.branchMismatch.foreach(preBranch += _)
        rd.legacyMismatch.foreach(preLegacy += _)
        if rd.oldCls == TaskStuckWatcher.ClassTrueStuck then preOldTrue += 1 else preOldFalse += 1
        if rd.loggedClass == TaskStuckWatcher.ClassTrueStuck then preLoggedTrue += 1
        else preLoggedFalse += 1
      else
        postRows += 1; postAsserted += 1
        rd.newMismatch.foreach(postNew += _)
      end if
      // (b) 全语料口径不变式（所有行，含出处不明/异常行——时不变判据不缩段）
      rd.legacyMismatch.foreach(allLegacy += _)
      if rd.loggedClass == TaskStuckWatcher.ClassTrueStuck then loggedTrue += 1 else loggedFalse += 1
      if rd.newCls == TaskStuckWatcher.ClassTrueStuck then newTrue += 1 else newFalse += 1
      if rd.loggedClass == TaskStuckWatcher.ClassTrueStuck && !rd.fresh &&
        rd.newCls != TaskStuckWatcher.ClassTrueStuck
      then
        leaked += (r.sid + "@" + r.ts + "（新窗 " + (rd.window / 1000) + "s 下正信号不新鲜却翻了：" +
          rd.newCls + "）")
      val crossed =
        rd.loggedClass == TaskStuckWatcher.ClassTrueStuck &&
          rd.newCls == TaskStuckWatcher.ClassFalsePositive
      if crossed then flipped += Flipped(r.sid, r.ts, rd.progressAgo, rd.window)
      if rd.loggedClass == TaskStuckWatcher.ClassFalsePositive &&
        rd.newCls == TaskStuckWatcher.ClassTrueStuck
      then fpToTrue += (r.sid + "@" + r.ts)
      val cur = bySession.getOrElse(r.sid, SessionStat(0, 0, 0, 0))
      bySession.update(
        r.sid,
        SessionStat(
          cur.n + 1,
          cur.loggedTrue + (if rd.loggedClass == TaskStuckWatcher.ClassTrueStuck then 1 else 0),
          cur.newTrue + (if rd.newCls == TaskStuckWatcher.ClassTrueStuck then 1 else 0),
          cur.flipped + (if crossed then 1 else 0)
        )
      )
    }
    val pre = Segment(
      preLabel,
      preRows,
      preAsserted,
      preExcluded,
      preBranch.result(),
      preLegacy.result(),
      Vector.empty,
      preOldTrue,
      preOldFalse,
      preLoggedTrue,
      preLoggedFalse
    )
    val post =
      Segment(postLabel, postRows, postAsserted, postExcluded, Vector.empty, Vector.empty, postNew.result(), 0, 0, 0, 0)
    Result(
      rows.size,
      window,
      pre,
      post,
      unknown.result(),
      anomal.result(),
      allLegacy.result(),
      fpToTrue.result(),
      leaked.result(),
      flipped.result(),
      bySession.toMap,
      loggedTrue,
      loggedFalse,
      newTrue,
      newFalse
    )
  end run

  // ── 5. 判定层（可复用）：新形态（分段） / 旧形态（反向对照）──────────────────

  enum Status:
    case Ok, Fail, Skipped

  /** 一条判定：`name` = 断言名（判定面标识），`status` = 读数，`detail` = 原始读数文本。 */
  final case class Verdict(name: String, status: Status, detail: String)

  /** 命名会话的期望形态（主用例传入生产点名清单；对照用例不传）。 */
  enum SessionExpectation:
    case NewTrueZero, Reduced

  final case class SessionCase(sid: String, expectation: SessionExpectation)

  private def check(name: String, offenders: Vector[String], why: String): Verdict =
    if offenders.isEmpty then Verdict(name, Status.Ok, "0 条")
    else Verdict(name, Status.Fail, s"${offenders.size} 条（" + why + "）: " + offenders.take(5).mkString(" | "))

  private def skip(name: String, why: String): Verdict = Verdict(name, Status.Skipped, "未证: " + why)

  /**
   * **口径不变式**（时不变，全语料含窗口后行）+ 命名会话期望。
   */
  def invariantVerdicts(r: Result, sessions: Vector[SessionCase] = Vector.empty): Vector[Verdict] =
    val inv = Vector(
      check("不变量/全语料: false-positive → true-stuck 翻转 = 0", r.fpToTrue, "修法只准减少误判、不准新增真判"),
      check("不变量/全语料: violations（放走真卡死）= 0", r.leakedTrueStuck, "正信号不新鲜的真卡死一条都不准翻")
    )
    val sess = sessions.map { c =>
      val st = r.bySession.getOrElse(c.sid, SessionStat(0, 0, 0, 0))
      if st.n == 0 then Verdict("不变量/全语料: 命名会话 " + c.sid, Status.Fail, "0 条（须在语料中——前提不成立）")
      else
        c.expectation match
          case SessionExpectation.NewTrueZero =>
            Verdict(
              "不变量/全语料: 命名会话 " + c.sid + " newTrue = 0",
              if st.newTrue == 0 then Status.Ok else Status.Fail,
              "n=" + st.n + " loggedTrue=" + st.loggedTrue + " newTrue=" + st.newTrue
            )
          case SessionExpectation.Reduced =>
            Verdict(
              "不变量/全语料: 命名会话 " + c.sid + " newTrue < loggedTrue（窗联动）",
              if st.newTrue < st.loggedTrue then Status.Ok else Status.Fail,
              "n=" + st.n + " loggedTrue=" + st.loggedTrue + " newTrue=" + st.newTrue +
                " flipped=" + st.flipped
            )
      end if
    }
    inv ++ sess

  end invariantVerdicts

  /**
   * **分段保真度**：窗口前段 = 旧判据自证；窗口后段 = 新判据自证；起点可判定性单列。
   */
  def fidelityVerdicts(r: Result): Vector[Verdict] =
    val start = Verdict("窗口起点可判定", if r.window.start.isDefined then Status.Ok else Status.Skipped, r.window.source)
    val pre =
      if r.pre.asserted == 0 then
        Vector(
          skip(
            "窗口前段: 旧判据重放复现日志 class",
            "窗口前段无出处已定（旧构建文案）的行 ⇒ 旧判据保真度自证无法判定；缺「窗口前段行」或显式 " +
              "nebflow.watchdog.replayWindowStart 参数（excluded=" + r.pre.excluded + "）"
          ),
          skip("窗口前段: 重放 branch 与日志一致", "同上（窗口前段无可断言的行）"),
          skip("窗口前段: 两侧计数相等", "同上（窗口前段无可断言的行）")
        )
      else
        Vector(
          check("窗口前段: 旧判据重放复现日志 class", r.pre.legacyMismatch, "窗口前段 " + r.pre.asserted + " 行（旧构建出处）必须逐行复现"),
          check("窗口前段: 重放 branch 与日志一致", r.pre.branchMismatch, "重建口径自证，窗口前段 " + r.pre.asserted + " 行"),
          Verdict(
            "窗口前段: 两侧计数相等",
            if r.pre.oldTrue == r.pre.loggedTrue && r.pre.oldFalse == r.pre.loggedFalse then Status.Ok
            else Status.Fail,
            "旧判据重放 " + r.pre.oldTrue + "/" + r.pre.oldFalse + " vs 日志 " +
              r.pre.loggedTrue + "/" + r.pre.loggedFalse + "（窗口前段 " + r.pre.asserted + " 行）"
          )
        )
    val post =
      if r.post.asserted == 0 then
        Vector(skip("窗口后段: 新判据重放复现日志 class", "窗口后段无出处已定（修复构建文案）的行 ⇒ 新判据自证无法判定（excluded=" + r.post.excluded + "）"))
      else
        Vector(
          check(
            "窗口后段: 新判据重放复现日志 class",
            r.post.newMismatch,
            "窗口后段 " + r.post.asserted + " 行（修复构建出处）必须逐行复现（生产写入 ↔ 离线重放同口径）"
          )
        )
    start +: (pre ++ post)

  end fidelityVerdicts

  /**
   * **新形态**（分段断言）全集——主用例与对照 fixture 用例共用。
   */
  def segmentedVerdicts(r: Result, sessions: Vector[SessionCase] = Vector.empty): Vector[Verdict] =
    invariantVerdicts(r, sessions) ++ fidelityVerdicts(r)

  /**
   * **修复面**读数（本批目标行为；语料特定，主用例用）。
   */
  def fixEffectVerdicts(r: Result): Vector[Verdict] =
    Vector(
      if r.flipped.nonEmpty then
        Verdict(
          "修复面: 至少一条 true-stuck → false-positive",
          Status.Ok,
          s"${r.flipped.size} 条翻走（会话 " + r.flipped.map(_.sid).distinct.size + " 个）"
        )
      else Verdict("修复面: 至少一条 true-stuck → false-positive", Status.Fail, "0 条（修复面为空）"),
      if r.newTrue < r.loggedTrue then
        Verdict(
          "修复面: 修复后 true-stuck 减少",
          Status.Ok,
          "loggedTrue " + r.loggedTrue + " → newTrue " + r.newTrue + "（" + r.trueStuckDelta + "）"
        )
      else Verdict("修复面: 修复后 true-stuck 减少", Status.Fail, "loggedTrue " + r.loggedTrue + " → newTrue " + r.newTrue),
      if r.newTrue > 0 then Verdict("修复面: 真卡死仍被捕获（newTrue > 0）", Status.Ok, "newTrue=" + r.newTrue)
      else Verdict("修复面: 真卡死仍被捕获（newTrue > 0）", Status.Fail, "newTrue=0（真判被清零）")
    )

  /**
   * **旧形态**（反向对照）——「全语料一律断言旧判据保真」的原 spec 断言形态，原样重放其读数。
   */
  def wholeCorpusLegacyVerdicts(r: Result): Vector[Verdict] =
    Vector(check("旧形态/全语料: 旧判据重放复现日志 class（原 spec 断言形态）", r.allLegacyMismatch, "含窗口后行 ⇒ 修复构建写的行必然不复现旧判据"))

  /** 供报告与断言失败信息用的短标签。 */
  def label(v: Verdict): String = v.status match
    case Status.Ok => "[OK]   "
    case Status.Fail => "[FAIL] "
    case Status.Skipped => "[未证] "

end WatchdogReplayCore
