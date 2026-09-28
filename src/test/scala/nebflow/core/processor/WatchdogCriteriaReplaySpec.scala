package nebflow.core.processor

import cats.effect.IO
import munit.CatsEffectSuite

import WatchdogReplayCore.{Result, SessionCase, SessionExpectation, Status}

/**
 * **wd-fix 批离线回归器**（2026-09-12，作者裁定「只做方向 A」）——看门狗假阳修复的
 * 主证据面，用**当天真实语料**做「同输入同跑旧判据与新判据」的前后对照。
 *
 * 语料 = `WatchdogEventLog` 的生产事件面
 * `~/.nebflow/logs/watchdog/2026-09-12_events.jsonl`（**只读**，本 spec 零写入该文件）。
 * 路径可用 system prop `nebflow.watchdog.replayLog` 覆盖；**语料不存在 ⇒ 整 spec
 * ignore**（`munitIgnore`，不是失败）——CI / 干净工作区没有生产日志时按 skip 处置。
 *
 * ## 2026-09-12 `wdreplayfix` 批（R1′）：断言按**构建出处**分段
 *
 * 原形态「全语料一律断言旧判据保真（`legacyMismatch == Nil`）」隐含前提是
 * **「当日语料全部由修复前构建产出」**；宿主一旦换成含修复的构建（本轮 19:38:54 重启），
 * 窗口后新行由新判据写成 ⇒ 旧判据重放必然不一致、mismatch **单调增长**、`sbt test` 常红。
 * 修法 = 把断言按**窗口起点**分成两段，并把**时不变**的口径不变式留成全语料口径：
 *
 *   - **窗口前段**（旧构建出处行）：旧判据保真度自证（class / branch / 两侧计数）——保留；
 *   - **窗口后段**（修复构建出处行）：新判据保真度自证（`newCls == 日志 class`）；
 *   - **全语料（含窗口后行）**：只断言三条口径不变式——`false-positive → true-stuck` 翻转 = 0、
 *     `violations`（放走真卡死）= 0、命名会话 `newTrue = 0`。
 *
 * **窗口起点不写死任何日期常量**：`nebflow.watchdog.replayWindowStart`（epoch 毫秒或
 * ISO-8601 本地时刻）可覆盖；缺省 = 从**语料自身的构建出处标记**推导（修复前/后的 note
 * 文案形态不同：修复后两条臂的 note 都带 `, window Ns)` 读数）⇒ 换一次宿主重启、换一份
 * 语料都稳定；**出处不明的行**（两构建文案逐字相同的分支，如「无工具相位」）只计数、
 * **不参与任何保真度断言**并显式申报；窗口前段/后段任一段无出处已定的行 ⇒ 该段判定
 * **显式 skip 并申报「未证」**（缺什么证据写进 detail），**不用松弛手段换绿**。
 *
 * 判据实现、分段口径与判定层全部在 [[WatchdogReplayCore]]（可注入语料路径/行内容），
 * 本 spec 与对照 fixture 用例（[[WatchdogReplaySegmentationSpec]]）**共用同一实现**；
 * 本批**零生产行为改动**（改动面 = 测试面 + 仓内 sink 驱动模板）。
 *
 * 重放口径（`lastActivityMs` / `currentToolStartedAt` / `authorised` / 声明时长 /
 * 正信号年龄）逐字见 [[WatchdogReplayCore]] 头注（与 wd-fix 批报告 §3、窗口验证报告 §③ 同源）。
 *
 * 报告落 `<repo>/.nebflow/wd-fix/replay-report.txt`（过程件，gitignore 面；同内容同时
 * 打到 stdout 供复核）。
 */
class WatchdogCriteriaReplaySpec extends CatsEffectSuite:

  private val replayLog: os.Path =
    os.Path(
      sys.props.getOrElse(
        "nebflow.watchdog.replayLog",
        (os.home / ".nebflow" / "logs" / "watchdog" / "2026-09-12_events.jsonl").toString
      )
    )

  /** 无生产语料（CI / 干净工作区）⇒ 整 spec skip，不当失败（**该行为不得回退**）。 */
  override def munitIgnore: Boolean = !os.exists(replayLog)

  /** 窗口起点的**参数覆盖**（缺省 = 语料自校准，见头注）；两种形态都接受。 */
  private val windowStartProp: Option[Long] =
    sys.props.get("nebflow.watchdog.replayWindowStart").map(WatchdogReplayCore.parseWindowStart)

  /**
   * 任务书 / 批报告点名的会话期望（语料特定数据，故留在 spec 而非核内）。
   *   - 5 个生产误判会话：修复后**不得再判** true-stuck（口径不变式）；
   *   - 2 个窗联动样本（声明 3600s）：修复后 true-stuck 必须减少。
   */
  private val namedSessions: Vector[SessionCase] = Vector(
    SessionCase("node-eb8a9c70", SessionExpectation.NewTrueZero),
    SessionCase("node-3880729d", SessionExpectation.NewTrueZero),
    SessionCase("node-6885c027", SessionExpectation.NewTrueZero),
    SessionCase("dispatcher-ef548490", SessionExpectation.NewTrueZero),
    SessionCase("dispatcher-1f54460c", SessionExpectation.NewTrueZero),
    SessionCase("node-8920254c", SessionExpectation.Reduced),
    SessionCase("node-35ad3b69", SessionExpectation.Reduced)
  )

  test("wd-fix 重放: 当日 stuck-detected 语料同输入同跑 旧/新 判据 → 分段断言（窗口前段自证 / 窗口后段自证 / 全语料口径不变式）") {
    IO.blocking {
      val rows = WatchdogReplayCore.rowsFromFile(replayLog)
      val r = WatchdogReplayCore.run(rows, windowStartProp)
      val verdicts =
        WatchdogReplayCore.segmentedVerdicts(r, namedSessions) ++ WatchdogReplayCore.fixEffectVerdicts(r)

      val report = render(r, verdicts)
      val outDir = os.pwd / ".nebflow" / "wd-fix"
      os.makeDir.all(outDir)
      os.write.over(outDir / "replay-report.txt", report, createFolders = true)
      println(report)

      // ── 断言 ──────────────────────────────────────────────────────────────
      assert(rows.nonEmpty, "语料必须含 stuck-detected 行")
      val fails = verdicts.filter(_.status == Status.Fail)
      assertEquals(
        fails.map(v => v.name + " → " + v.detail),
        Vector.empty[String],
        "分段断言不得有 FAIL（分段机制见 [[WatchdogReplayCore]] 头注）"
      )
    }
  }

  // ── 报告渲染（读数与判定同源；未证/异常单列）─────────────────────────────────

  private def segLine(label: String, s: WatchdogReplayCore.Segment): String =
    label + ": 行 " + s.rows + "（参与断言 " + s.asserted + " / 出处不明或异常 " + s.excluded +
      "）| 旧判据不一致 " + s.legacyMismatch.size + " | 新判据不一致 " + s.newMismatch.size +
      " | branch 不一致 " + s.branchMismatch.size

  private def render(r: Result, verdicts: Vector[WatchdogReplayCore.Verdict]): String =
    val sb = new StringBuilder
    def emit(s: String): Unit = sb.append(s).append('\n')
    emit("=== wd-fix 离线重放（看门狗假阳修复批 · 方向 A）===")
    emit("语料: " + replayLog.toString + "（只读）")
    emit("stuck-detected 行数: " + r.total)
    emit("")
    emit("── 窗口与分段（起点 = 参数覆盖或语料自校准，禁日期常量）──")
    emit("窗口起点: " + r.window.start.map(_.toString).getOrElse("(不可判定)") + "  来源: " + r.window.source)
    emit(segLine("窗口前段（旧构建出处 ⇒ 旧判据保真度自证）", r.pre))
    emit(segLine("窗口后段（修复构建出处 ⇒ 新判据保真度自证）", r.post))
    emit("")
    emit("── 前后对照（stuck-detected 判定面，两侧数字齐全）──")
    emit(
      "修复前（日志登记）    : true-stuck " + r.loggedTrue + " / false-positive " + r.loggedFalse +
        "  (合计 " + r.total + ")"
    )
    emit(
      "修复前（旧判据重放）  : true-stuck " + r.pre.oldTrue + " / false-positive " + r.pre.oldFalse +
        "（窗口前段口径）"
    )
    emit("修复后（新判据重放）  : true-stuck " + r.newTrue + " / false-positive " + r.newFalse)
    emit(
      "true-stuck 变化       : " + r.loggedTrue + " → " + r.newTrue +
        "（" + r.trueStuckDelta + " 条）"
    )
    emit(
      "false-positive → true-stuck 的翻转: " + r.fpToTrue.size +
        " 条（必须为 0 —— 修法只准减少误判，不准新增真判）"
    )
    r.fpToTrue.foreach(s => emit("  VIOLATION " + s))
    emit("")
    emit("── 保真度自证（分段）──")
    emit(
      "窗口前段 旧判据重放 class 与日志不一致: " + r.pre.legacyMismatch.size + " 条（参与断言 " +
        r.pre.asserted + " 行）"
    )
    r.pre.legacyMismatch.take(40).foreach(s => emit("  " + s))
    emit("窗口前段 重放 branch 与日志不一致: " + r.pre.branchMismatch.size + " 条")
    r.pre.branchMismatch.take(20).foreach(s => emit("  " + s))
    emit(
      "窗口前段 两侧计数: 旧判据重放 " + r.pre.oldTrue + "/" + r.pre.oldFalse + " vs 日志 " +
        r.pre.loggedTrue + "/" + r.pre.loggedFalse
    )
    emit(
      "窗口后段 新判据重放 class 与日志不一致: " + r.post.newMismatch.size + " 条（参与断言 " +
        r.post.asserted + " 行）"
    )
    r.post.newMismatch.take(40).foreach(s => emit("  " + s))
    emit(
      "旧形态读数（全语料一律断言旧判据保真）: " + r.allLegacyMismatch.size +
        " 条 —— 该形态在含窗口后行的语料上必然非零（R1′ 现象本体）"
    )
    emit("")
    emit("── 逐会话（总数 / 日志 true-stuck / 新 true-stuck / 翻走）──")
    r.bySession.toList.foreach { case (sid, t) =>
      emit(
        "  " + sid + ": n=" + t.n + " loggedTrue=" + t.loggedTrue + " newTrue=" + t.newTrue +
          " flipped=" + t.flipped
      )
    }
    emit("")
    emit("── 从 true-stuck 翻走的条目（共 " + r.flipped.size + " 条）──")
    r.flipped.groupBy(_.sid).toList.sortBy(_._1).foreach { case (sid, rs) =>
      val ages = rs.map(_.progressAgoMs).map(a => s"${a / 1000}s").distinct.sorted
      emit(
        "  " + sid + ": " + rs.size + " 条，正信号新鲜度 ∈ {" + ages.mkString(", ") +
          "}，新窗 " + (rs.head.windowMs / 1000) + "s"
      )
    }
    emit("")
    emit("── 「放走真卡死」检查（日志 true-stuck ∧ 新窗下正信号不新鲜 ⇒ 必须仍 true-stuck）──")
    emit("  violations: " + r.leakedTrueStuck.size)
    r.leakedTrueStuck.foreach(s => emit("  VIOLATION " + s))
    emit("")
    emit("── 判定表（本 spec 的断言面；[未证] 行 = 该段无法判定，已在下方申明缺什么证据）──")
    verdicts.foreach(v => emit("  " + WatchdogReplayCore.label(v) + v.name + " | " + v.detail))
    emit("")
    emit("── 未证 / 异常申报（显式，禁粉饰）──")
    emit(
      "未证① 出处不明的行（两构建文案逐字相同 ⇒ 不参与任何保真度断言）: " +
        r.unknownRows.size + " 条" + (if r.unknownRows.isEmpty then ""
                                     else
                                       "（" + r.unknownRows.take(10).mkString(", ") +
                                         (if r.unknownRows.size > 10 then ", …" else "") + "）") +
        " —— 缺证据: 事件行本身不含构建出处标记（该分支文案未被 wd-fix 改动）"
    )
    emit("未证② 语料自相矛盾行（出处与窗口分段不符）: " + r.anomalies.size + " 条")
    r.anomalies.take(10).foreach(s => emit("  ANOMALY " + s))
    verdicts.filter(_.status == Status.Skipped).foreach(v => emit("未证③ " + v.name + " | " + v.detail))
    if verdicts.forall(_.status != Status.Skipped) then emit("未证③ 无（窗口前段与后段均有出处已定的行 ⇒ 两段保真度自证均可判定）")
    emit("")
    emit("── 任务书点名的生产误判会话（修复后必须不再判 true-stuck）──")
    List(
      "node-eb8a9c70",
      "node-3880729d",
      "node-6885c027",
      "dispatcher-ef548490",
      "dispatcher-1f54460c",
      "node-8920254c",
      "node-35ad3b69"
    ).foreach { sid =>
      r.bySession.get(sid) match
        case Some(t) =>
          emit(
            "  " + sid + ": n=" + t.n + " loggedTrue=" + t.loggedTrue +
              " → newTrue=" + t.newTrue + "（翻走 " + t.flipped + "）"
          )
        case None => emit("  " + sid + ": 语料中无记录")
    }
    sb.toString
  end render
end WatchdogCriteriaReplaySpec
