package nebflow.core.tools

import munit.FunSuite

/**
 * 超时对账 spec（C 批 ④，2026-09-13）：[[MemoryQueue.reconcile]] 的**纯函数面** +
 * 终态集的向后安全。
 *
 * 判据真源：`~/.nebflow/docs/Nebflow/20260913_185524_memory-track-timeout-forensics-plan__chain-n-95e0aa0d.md`
 * §4（候选 ④）+ §3.2（重复行反例）。作者三条硬约束逐条对应本 spec 的用例：
 *   - (i) 独立字样 `applied-by-reconcile` + detail 前缀 + 进 [[MemoryQueue.TerminalResults]]
 *     ⇒ 用例 1 / 3；
 *   - (ii) 判据只取高精度两支（逐字行命中 / 定位键消失）、宁漏不误 ⇒ 用例 1 / 2
 *     （落空形态、`replace_section`、空内容、无目标文件一律**不判**）；
 *   - (iii) 未知结局按「未闭合」处理（旧 jar 保守方向）⇒ 用例 3。
 *
 * 纯函数 ⇒ 全部用例**零盘面**（不重定向 dataRoot、不落任何文件；判定只吃入参）。
 */
class MemoryQueueReconcileSpec extends FunSuite:

  private def note(
    id: String,
    atMs: Long,
    target: String,
    action: String,
    section: Option[String] = None,
    matchText: Option[String] = None,
    content: Option[String] = None
  ): MemoryQueue.Note =
    MemoryQueue.Note(
      id,
      atMs,
      "2026-09-13T00:00:00Z",
      target,
      action,
      section,
      matchText,
      content,
      Some("s"),
      MemoryQueue.TriggerManual
    )

  private def stateOf(
    notes: Vector[MemoryQueue.Note],
    outcomes: Vector[MemoryQueue.Outcome] = Vector.empty
  ): MemoryQueue.State =
    MemoryQueue.State(notes, outcomes, Set.empty, 0, 0)

  private def outcome(ref: String, result: String): MemoryQueue.Outcome =
    MemoryQueue.Outcome(ref, 1L, "2026-09-13T00:00:01Z", result, "memory-consolidator", "")

  private def tf(path: String, content: String): MemoryQueue.TargetFile = MemoryQueue.TargetFile(path, content)

  /** 目标文件路径故意指向**不存在**的位置：判定若读盘就会失败/判错，用例即证「只吃入参」。 */
  private val ghostPath = "/nonexistent-reconcile-fixture/User.md"

  private val fileBody =
    """# User
      |
      |## 节
      |
      |- 已存在的条目
      |- 全量派发 + pending 节点模式（Plan first）→full-dispatch-pending-node [T1]
      |""".stripMargin

  // ── (i)+(ii) 两支高精度判据；其余一律不判 ────────────────────────

  test("④ 对账只认两支高精度判据：逐字行命中（append⇒deduped / update⇒applied-by-reconcile）+ 定位键消失（remove⇒applied-by-reconcile）；其余不判"):
    val notes = Vector(
      note("q-dup", 1L, "user", "append", None, None, Some("- 已存在的条目")), // 已落 append ⇒ deduped
      note("q-new", 2L, "user", "append", None, None, Some("- 全新条目")), // 未落 ⇒ 不判
      note(
        "q-up-ok",
        3L,
        "user",
        "update",
        None,
        Some("某旧文本"),
        Some("- 全量派发 + pending 节点模式（Plan first）→full-dispatch-pending-node [T1]")
      ), // content 行已在 ⇒ 判
      note("q-up-miss", 4L, "user", "update", None, Some("定位键已消失"), Some("- 改写后的新文本")), // L3 落空形态 ⇒ 不判
      note("q-rm-miss", 5L, "user", "remove", None, Some("- 已不存在的键"), None), // 定位键消失 ⇒ 判
      note("q-rm-hit", 6L, "user", "remove", None, Some("已存在的条目"), None), // 定位键仍在 ⇒ 不判
      note("q-sec", 7L, "user", "replace_section", Some("节"), None, Some("- 整段替换")), // 整段替换 ⇒ 不判
      note("q-empty", 8L, "user", "append", None, None, Some("   ")) // 空内容 ⇒ 不判
    )
    val report = MemoryQueue.reconcile(stateOf(notes), Map("user" -> tf(ghostPath, fileBody)))

    assertEquals(report.refs, Set("q-dup", "q-up-ok", "q-rm-miss"), s"判据只取两支: ${report.closed}")
    assertEquals(report.byRef("q-dup").result, MemoryQueue.ResultDeduped, "append 已在文件 = 重复 ⇒ deduped")
    assertEquals(report.byRef("q-up-ok").result, MemoryQueue.ResultAppliedByReconcile, "update content 已在盘 ⇒ 独立字样")
    assertEquals(report.byRef("q-rm-miss").result, MemoryQueue.ResultAppliedByReconcile, "remove 键已消失 ⇒ 独立字样")
    assertEquals(report.count, 3)
    assertEquals(report.resultCounts, Map(MemoryQueue.ResultDeduped -> 1, MemoryQueue.ResultAppliedByReconcile -> 2))
    // 「谁判的」可辨（作者硬约束 i）：独立字样 + detail 前缀 + 目标路径可读
    report.closed.foreach { v =>
      assert(v.detail.startsWith(MemoryQueue.ReconcileDetailPrefix), s"detail 前缀是审计锚: ${v.detail}")
      assert(v.detail.contains(ghostPath), s"detail 指出判在哪个文件上: ${v.detail}")
      assertEquals(v.target, "user")
    }
    // 不判的条目一条都不许混进 closed
    assert(!report.refs.contains("q-new"), "未落 append 不得判")
    assert(!report.refs.contains("q-up-miss"), "L3 落空（match 消失但 content 不在盘）不得判 —— 宁漏不误")
    assert(!report.refs.contains("q-rm-hit"), "定位键仍在 ⇒ 未落 remove 不得判")
    assert(!report.refs.contains("q-sec"), "replace_section 不判")
    assert(!report.refs.contains("q-empty"), "空 content 不判")
    // 不判的条目在折叠语义下仍是 pending（留 timeout 重试的口径）
    val st = stateOf(notes)
    assertEquals(MemoryQueue.reconcile(st, Map("user" -> tf(ghostPath, fileBody))).refs.size, 3)
    assertEquals(st.pending.size, notes.size, "对账本身零写入（state 不被改）")

  test("④ 无凭据不下判：目标层不在 postFiles / 跑后内容为空串 ⇒ 一律不判（宁漏不误）"):
    val withTarget = Vector(
      note("q-1", 1L, "user", "append", None, None, Some("- x")),
      note("q-2", 2L, "user", "remove", None, Some("- y"), None)
    )
    assert(MemoryQueue.reconcile(stateOf(withTarget), Map.empty).closed.isEmpty, "postFiles 空 ⇒ 不判")
    assert(
      MemoryQueue.reconcile(stateOf(withTarget), Map("other" -> tf(ghostPath, fileBody))).closed.isEmpty,
      "目标层不在 postFiles ⇒ 不判"
    )
    assert(
      MemoryQueue.reconcile(stateOf(withTarget), Map("user" -> tf(ghostPath, ""))).closed.isEmpty,
      "跑后内容为空串（文件不存在/首次写入）⇒ 无凭据、不判（remove 也不会被误标「已消失」）"
    )

  // ── (iii) 终态集与未知结局的保守方向 ─────────────────────────────

  test("④ 向后安全：applied-by-reconcile ∈ TerminalResults；未知结局值按「未闭合」处理（旧 jar 读到新字样不会误闭合）"):
    assert(MemoryQueue.TerminalResults.contains(MemoryQueue.ResultAppliedByReconcile), "新字样必须进终态集（否则对账等于白写）")
    assert(!MemoryQueue.RetryableResults.contains(MemoryQueue.ResultAppliedByReconcile))

    val n = note("q-x", 1L, "user", "append", None, None, Some("- x"))
    val judged = stateOf(Vector(n), Vector(outcome("q-x", MemoryQueue.ResultAppliedByReconcile)))
    assert(judged.consumed(n), "对账终态 ⇒ 闭合")
    assertEquals(judged.pendingCount, 0)

    val unknown = stateOf(Vector(n), Vector(outcome("q-x", "some-future-result")))
    assert(!unknown.consumed(n), "未知结局值（旧 jar 视角）⇒ 未闭合")
    assertEquals(unknown.pending.map(_.id), Vector("q-x"), "保守方向：条目留在 pending 池（宁保留勿静默丢）")

  test("④ 纯函数：判定只吃入参（目标文件不在盘上也能判）、同输入可复现"):
    val notes = Vector(note("q-dup", 1L, "user", "append", None, None, Some("- 已存在的条目")))
    val files = Map("user" -> tf(ghostPath, fileBody))
    assert(!os.exists(os.Path(ghostPath)), "夹具路径确实不在盘上")
    val r1 = MemoryQueue.reconcile(stateOf(notes), files)
    val r2 = MemoryQueue.reconcile(stateOf(notes), files)
    assertEquals(r1, r2, "同输入同输出（可复算）")
    assertEquals(r1.refs, Set("q-dup"), "不读盘也能判 ⇒ 判定面只有 (state, postFiles)")

end MemoryQueueReconcileSpec
