package nebflow.core.processor

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord, AgentStatus}

/**
 * **wd-fix 批离线回归器**（2026-09-12，作者裁定「只做方向 A」）——看门狗假阳修复的
 * 主证据面，用**当天真实语料**做「同输入同跑旧判据与新判据」的前后对照。
 *
 * 语料 = `WatchdogEventLog` 的生产事件面
 * `~/.nebflow/logs/watchdog/2026-09-12_events.jsonl`（**只读**，本 spec 零写入该文件）。
 * 路径可用 system prop `nebflow.watchdog.replayLog` 覆盖；**语料不存在 ⇒ 整 spec
 * ignore**（`munitIgnore`，不是失败）——CI / 干净工作区没有生产日志时按 skip 处置。
 *
 * 重放口径（逐字，供复核）：
 *   - `lastActivityMs      = ts - agentIdleMs`
 *   - `currentToolStartedAt = ts - toolPhaseMs`（事件里 `toolPhaseMs == 0` ⇒ 0，无工具相位）
 *   - `authorised`（秒）从 `note` 抽取（class-1 形态 `authorised Ns`；class-2 形态 `≤ Ns`）
 *     ⇒ `effectiveMs = authorised * 1000`；`declaredMs = max(0, effectiveMs - ToolDeadlineSlackMs)`
 *     **当且仅当** `effectiveMs > ToolPhaseStuckMs`（否则取 0——此时 `max(默认档, 声明+宽限)`
 *     与「声明 = 0」逐字同解，故重放**精确**，不是近似）。
 *   - `lastProgressSignalAt  = ts - progressAgoMs`（`progress signal Ns ago` /
 *     `last=Ns ago`；`never` ⇒ 0）
 *   - 事件 `ts` = 判定后写事件时的墙钟（与 `classify` 的 `now` 差 <1ms 量级），
 *     秒级读数照此重建。
 *
 * **旧判据**在本 spec 内**逐字重实现**（`legacyClassify`，与 wd-fix 批改动前的
 * `TaskStuckWatcher.classify` 同构）——它不进生产代码；其正确性自证 = 重放 class 必须
 * 与日志登记的 `class` 完全一致（保真度断言）。**新判据**直接调生产
 * [[TaskStuckWatcher.classify]]，不重实现（避免「重放器自己写一套新逻辑」的漂移）。
 *
 * 报告落 `<repo>/.nebflow/wd-fix/replay-report.txt`（过程件，gitignore 面；同内容同时
 * 打到 stdout 供复核）。
 */
class WatchdogCriteriaReplaySpec extends CatsEffectSuite:

  private val replayLog: os.Path =
    os.Path(sys.props.getOrElse(
      "nebflow.watchdog.replayLog",
      (os.home / ".nebflow" / "logs" / "watchdog" / "2026-09-12_events.jsonl").toString))

  /** 无生产语料（CI / 干净工作区）⇒ 整 spec skip，不当失败。 */
  override def munitIgnore: Boolean = !os.exists(replayLog)

  private val sl = nebflow.shared.Defaults.ToolDeadlineSlackMs

  // ── 事件行 → AgentRecord 重建 ────────────────────────────────────────────

  private val AuthorisedRe = """authorised (\d+)s""".r
  private val AuthorisedLeRe = """≤ (\d+)s""".r
  private val ProgressRe = """progress signal (\d+)s ago""".r
  private val LastRe = """last=(\d+)s ago""".r

  private def authorisedSecs(note: String): Option[Long] =
    AuthorisedRe.findFirstMatchIn(note).map(_.group(1).toLong)
      .orElse(AuthorisedLeRe.findFirstMatchIn(note).map(_.group(1).toLong))

  private def progressAgoMs(note: String): Option[Long] =
    ProgressRe.findFirstMatchIn(note).map(_.group(1).toLong).map(_ * 1000L)
      .orElse(LastRe.findFirstMatchIn(note).map(_.group(1).toLong).map(_ * 1000L))

  private def rebuild(ts: Long, toolPhaseMs: Long, agentIdleMs: Long, note: String): AgentRecord =
    val authorisedMs =
      authorisedSecs(note).getOrElse(nebflow.shared.Defaults.ToolPhaseStuckMs / 1000L) * 1000L
    val declaredMs =
      if authorisedMs > nebflow.shared.Defaults.ToolPhaseStuckMs then authorisedMs - sl else 0L
    AgentRecord(
      sessionId = "replay",
      ref = null.asInstanceOf[nebflow.actor.ActorRef[AgentCommand]],
      kind = AgentKind.Flow,
      rootSessionId = "replay-root",
      startedAt = ts - 24 * 60 * 60 * 1000L,
      status = AgentStatus.Processing,
      lastActivityMs = if agentIdleMs > 0 then ts - agentIdleMs else 0L,
      currentToolName = Some("Bash"),
      currentToolStartedAt = if toolPhaseMs > 0 then ts - toolPhaseMs else 0L,
      currentToolDeadlineMs = declaredMs,
      lastProgressSignalAt = progressAgoMs(note).fold(0L)(age => ts - age)
    )

  // ── 旧判据（wd-fix 批改动前形态；逐字重实现，仅重放用，不进生产）──────────

  private def legacyClassify(rec: AgentRecord, a: TaskStuckWatcher.StuckAssessment, now: Long): String =
    val effective = ToolStuckJudgment.effectiveToolPhaseMs(
      nebflow.shared.Defaults.ToolPhaseStuckMs, rec.currentToolDeadlineMs,
      nebflow.shared.Defaults.ToolDeadlineSlackMs)
    val progress = TaskStuckWatcher.hasProgressSignal(
      rec, now, nebflow.shared.Defaults.StuckProgressSignalWindowMs)
    if rec.currentToolStartedAt > 0 then
      if a.toolPhaseMs <= effective && progress then TaskStuckWatcher.ClassFalsePositive
      else TaskStuckWatcher.ClassTrueStuck
    else TaskStuckWatcher.ClassTrueStuck

  /** 正信号在**新窗**下是否新鲜 + 新窗实值——「放走真卡死」的机械检查用。 */
  private def freshness(rec: AgentRecord, now: Long): (Boolean, Long) =
    val w = TaskStuckWatcher.effectiveProgressWindowMs(rec.currentToolDeadlineMs)
    (TaskStuckWatcher.hasProgressSignal(rec, now, w), w)

  // ── 重放主体 ─────────────────────────────────────────────────────────────

  test("wd-fix 重放: 当日 378 条 stuck-detected 同输入同跑 旧/新 判据 → 前后对照 + 翻转明细") {
    IO.blocking {
      val rows = os.read.lines(replayLog).toList
        .flatMap(l => io.circe.parser.parse(l).toOption)
        .filter(_.hcursor.get[String]("type").toOption.contains(TaskStuckWatcher.StuckDetectedType))

      val sb = new StringBuilder
      def emit(s: String): Unit = sb.append(s).append('\n')

      val branchMismatch = scala.collection.mutable.ListBuffer.empty[String]
      val legacyMismatch = scala.collection.mutable.ListBuffer.empty[String]
      val flipped = scala.collection.mutable.ListBuffer.empty[(String, Long, String, Long, Long)]
      val leakedTrueStuck = scala.collection.mutable.ListBuffer.empty[String]
      val newFalseFromTrue = scala.collection.mutable.ListBuffer.empty[String]
      val fpToTrue = scala.collection.mutable.ListBuffer.empty[String]
      var loggedTrue = 0
      var loggedFalse = 0
      var newTrue = 0
      var newFalse = 0
      var oldTrue = 0
      var oldFalse = 0
      val bySession = scala.collection.mutable.LinkedHashMap.empty[String, (Int, Int, Int, Int)]

      emit("=== wd-fix 离线重放（看门狗假阳修复批 · 方向 A）===")
      emit("语料: " + replayLog.toString + "（只读）")
      emit("stuck-detected 行数: " + rows.size)

      rows.foreach { row =>
        val h = row.hcursor
        val ts = h.get[Long]("ts").getOrElse(0L)
        val sid = h.get[String]("sessionId").getOrElse("?")
        val loggedClass = h.get[String]("class").getOrElse("?")
        val loggedBranch = h.get[String]("branch").getOrElse("?")
        val toolPhaseMs = h.get[Long]("toolPhaseMs").getOrElse(0L)
        val agentIdleMs = h.get[Long]("agentIdleMs").getOrElse(0L)
        val note = h.get[String]("note").getOrElse("")
        if loggedClass == TaskStuckWatcher.ClassTrueStuck then loggedTrue += 1 else loggedFalse += 1
        val rec = rebuild(ts, toolPhaseMs, agentIdleMs, note)
        TaskStuckWatcher.assessDetailed(rec, ts) match
          case None =>
            legacyMismatch += (sid + "@" + ts + ": 重放判据未命中（日志却有 class）——重建口径有误")
          case Some(a) =>
            if a.branch != loggedBranch then
              branchMismatch += (sid + "@" + ts + ": 重放 branch=" + a.branch + " ≠ 日志 " + loggedBranch)
            val oldCls = legacyClassify(rec, a, ts)
            if oldCls == TaskStuckWatcher.ClassTrueStuck then oldTrue += 1 else oldFalse += 1
            if oldCls != loggedClass then
              legacyMismatch += (sid + "@" + ts + ": 旧判据重放=" + oldCls + " ≠ 日志 " + loggedClass +
                "（toolPhaseMs=" + toolPhaseMs + " authorised=" + authorisedSecs(note) +
                " progressAgo=" + progressAgoMs(note) + "）")
            val newCls = TaskStuckWatcher.classify(rec, a, ts, inflight = 0).cls
            if newCls == TaskStuckWatcher.ClassTrueStuck then newTrue += 1 else newFalse += 1
            val (fresh, window) = freshness(rec, ts)
            if loggedClass == TaskStuckWatcher.ClassTrueStuck && !fresh &&
               newCls != TaskStuckWatcher.ClassTrueStuck then
              leakedTrueStuck += (sid + "@" + ts + "（新窗 " + (window / 1000) + "s 下正信号不新鲜却翻了：" + newCls + "）")
            if loggedClass == TaskStuckWatcher.ClassTrueStuck && newCls == TaskStuckWatcher.ClassFalsePositive then
              flipped += ((sid, ts, note, progressAgoMs(note).getOrElse(-1L), window))
              newFalseFromTrue += sid
            if loggedClass == TaskStuckWatcher.ClassFalsePositive &&
               newCls == TaskStuckWatcher.ClassTrueStuck then
              fpToTrue += (sid + "@" + ts)
            val cur = bySession.getOrElse(sid, (0, 0, 0, 0))
            bySession.update(sid, (cur._1 + 1,
              cur._2 + (if loggedClass == TaskStuckWatcher.ClassTrueStuck then 1 else 0),
              cur._3 + (if newCls == TaskStuckWatcher.ClassTrueStuck then 1 else 0),
              cur._4 + (if loggedClass == TaskStuckWatcher.ClassTrueStuck &&
                          newCls == TaskStuckWatcher.ClassFalsePositive then 1 else 0)))
      }

      emit("")
      emit("── 前后对照（stuck-detected 判定面，两侧数字齐全）──")
      emit("修复前（日志登记）    : true-stuck " + loggedTrue + " / false-positive " + loggedFalse +
        "  (合计 " + rows.size + ")")
      emit("修复前（旧判据重放）  : true-stuck " + oldTrue + " / false-positive " + oldFalse)
      emit("修复后（新判据重放）  : true-stuck " + newTrue + " / false-positive " + newFalse)
      emit("true-stuck 变化       : " + loggedTrue + " → " + newTrue +
        "（- " + (loggedTrue - newTrue) + " 条）")
      emit("false-positive 变化   : " + loggedFalse + " → " + newFalse +
        "（+ " + (newFalse - loggedFalse) + " 条）")
      emit("false-positive → true-stuck 的翻转: " + fpToTrue.size + " 条（必须为 0 —— 修法只准减少误判，不准新增真判）")
      fpToTrue.foreach(s => emit("  VIOLATION " + s))
      emit("")
      emit("── 保真度自证 ──")
      emit("重放 branch 与日志不一致: " + branchMismatch.size + " 条")
      branchMismatch.take(20).foreach(s => emit("  " + s))
      emit("旧判据重放 class 与日志不一致: " + legacyMismatch.size + " 条")
      legacyMismatch.take(40).foreach(s => emit("  " + s))
      emit("")
      emit("── 逐会话（总数 / 日志 true-stuck / 新 true-stuck / 翻走）──")
      bySession.foreach { case (sid, t) =>
        emit("  " + sid + ": n=" + t._1 + " loggedTrue=" + t._2 + " newTrue=" + t._3 + " flipped=" + t._4)
      }
      emit("")
      emit("── 从 true-stuck 翻走的条目（共 " + flipped.size + " 条）──")
      flipped.groupBy(_._1).toList.sortBy(_._1).foreach { case (sid, rs) =>
        val ages = rs.map(_._4).map(a => s"${a / 1000}s").distinct.sorted
        emit("  " + sid + ": " + rs.size + " 条，正信号新鲜度 ∈ {" + ages.mkString(", ") +
          "}，新窗 " + (rs.head._5 / 1000) + "s")
      }
      emit("")
      emit("── 「放走真卡死」检查（日志 true-stuck ∧ 新窗下正信号不新鲜 ⇒ 必须仍 true-stuck）──")
      emit("  violations: " + leakedTrueStuck.size)
      leakedTrueStuck.foreach(s => emit("  VIOLATION " + s))
      emit("")
      emit("── 任务书点名的生产误判会话（修复后必须不再判 true-stuck）──")
      List("node-eb8a9c70", "node-3880729d", "node-6885c027", "dispatcher-ef548490",
        "dispatcher-1f54460c", "node-8920254c", "node-35ad3b69").foreach { sid =>
        bySession.get(sid) match
          case Some(t) => emit("  " + sid + ": n=" + t._1 + " loggedTrue=" + t._2 +
            " → newTrue=" + t._3 + "（翻走 " + t._4 + "）")
          case None => emit("  " + sid + ": 语料中无记录")
      }

      val report = sb.toString
      val outDir = os.pwd / ".nebflow" / "wd-fix"
      os.makeDir.all(outDir)
      os.write.over(outDir / "replay-report.txt", report, createFolders = true)
      println(report)

      // ── 断言 ────────────────────────────────────────────────────────────
      assert(rows.nonEmpty, "语料必须含 stuck-detected 行")
      assertEquals(branchMismatch.toList, Nil,
        "重放 branch 必须与日志一致（重建口径自证），mismatch=" + branchMismatch.take(5).toList)
      assertEquals(legacyMismatch.toList, Nil,
        "旧判据重放必须复现日志 class（重放保真度自证），mismatch=" + legacyMismatch.take(5).toList)
      assertEquals(leakedTrueStuck.toList, Nil,
        "正信号不新鲜的真卡死一条都不准翻（放走一个 = FAIL）")
      assertEquals(oldClsCountsOk(oldTrue, oldFalse, loggedTrue, loggedFalse), true,
        "旧判据重放的两侧计数必须与日志两侧计数相等")
      assertEquals(fpToTrue.toList, Nil, "不得有 false-positive 翻成 true-stuck")
      assert(newFalseFromTrue.size > 0, "至少要有 true-stuck 翻成 false-positive（修复面）")
      assert(newTrue < loggedTrue, "修复后 true-stuck 必须减少：得 " + newTrue + " vs " + loggedTrue)
      assert(newTrue > 0, "必须有真卡死仍被判 true-stuck（真判不减少）")
      // 5 个误判会话必须全部不再判 true-stuck
      List("node-eb8a9c70", "node-3880729d", "node-6885c027", "dispatcher-ef548490",
        "dispatcher-1f54460c").foreach { sid =>
        val t = bySession.getOrElse(sid, (0, 0, 0, 0))
        assert(t._1 > 0, sid + " 必须在语料中（前提）")
        assertEquals(t._3, 0, sid + " 修复后不得再判 true-stuck（logged " + t._2 + " → new " + t._3 + "）")
      }
      // 窗联动的两个样本（声明 3600s、安静 74s / 61s+）同样不再判死
      List("node-8920254c", "node-35ad3b69").foreach { sid =>
        val t = bySession.getOrElse(sid, (0, 0, 0, 0))
        assert(t._1 > 0, sid + " 必须在语料中（前提）")
        assert(t._3 < t._2, sid + " 必须因窗联动减少 true-stuck（logged " + t._2 + " → new " + t._3 + "）")
      }
    }
  }

  private def oldClsCountsOk(oldTrue: Int, oldFalse: Int, loggedTrue: Int, loggedFalse: Int): Boolean =
    oldTrue == loggedTrue && oldFalse == loggedFalse
