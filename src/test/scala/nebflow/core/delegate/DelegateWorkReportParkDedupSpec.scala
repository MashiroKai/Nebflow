package nebflow.core.delegate

import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * ready-to-merge 停靠投递恰一次（投递可靠性批 2026-10-29 · 作者令 23:44「停靠等
 * 合并不再扣住报告……合并完成后可再补一行合并回执」）——合并轮 Release 附页的
 * 去重腿（[[DelegateWorkReport.appendixFor]]）：
 *
 *  - ① **停靠投递已被父确认**（回执在且已 ack）⇒ 附页只带 merge status + report
 *    files，**work result 段缺席**——工作结果全文已随停靠投递进过父会话，恰一次；
 *  - ② **停靠投递未确认/回执缺席**（补投机器还可能补投全文——宁重复不丢失）⇒
 *    work result 段照常随行；
 *  - ③ 三段全空 = ""（既有契约：不产空附页）。
 */
class DelegateWorkReportParkDedupSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-park-dedup"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def rec(id: String): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = "/tmp/nowhere-$id",
      project = None,
      taskId = None,
      parentSessionId = "nebula-root",
      title = "park dedup spec",
      createdAt = 0L,
      reportStatus = Some("finish"),
      reportDetail = Some("Merge landed: abcdef0 — one line merge status"),
      workReport = Some("THE WORK RESULT FULL TEXT (delivered at park time)")
    )

  private def ackReceipt(id: String, acked: Boolean): Unit =
    import cats.effect.unsafe.implicits.global
    DelegateDeliveryReceipts
      .record(
        DelegateDeliveryReceipts.Receipt(
          delegateId = id,
          parentSessionId = "nebula-root",
          eventType = "completed",
          payload = "park payload",
          metadata = JsonObject("parkedForMerge" -> true.asJson),
          deliveredAt = System.currentTimeMillis(),
          ackedAt = if acked then Some(System.currentTimeMillis()) else None
        )
      )
      .unsafeRunSync()

  test("① the park delivery ACKED ⇒ the merge appendix DROPS the work-result section (exactly-once)") {
    ackReceipt("park-acked-1", acked = true)
    val appendix = DelegateWorkReport.appendixFor(Some(rec("park-acked-1")))
    assert(appendix.contains("merge status"), "the merge status line always rides")
    assert(
      !appendix.contains("THE WORK RESULT FULL TEXT"),
      "the work result must NOT re-enter the parent context once the park delivery is acknowledged"
    )
  }

  test("② the park delivery UNACKED/absent ⇒ the appendix KEEPS the work-result section (never lost)") {
    // 回执在但未 ack（补投机器仍可能补投全文）
    ackReceipt("park-open-2", acked = false)
    val open = DelegateWorkReport.appendixFor(Some(rec("park-open-2")))
    assert(open.contains("THE WORK RESULT FULL TEXT"), "an unacknowledged park delivery keeps the work section (never lost)")
    // 回执整体缺席（老盘/故障）
    val absent = DelegateWorkReport.appendixFor(Some(rec("park-absent-3")))
    assert(absent.contains("THE WORK RESULT FULL TEXT"), "a missing receipt keeps the work section (conservative)")
  }

  test("③ no report detail and no work report ⇒ empty appendix (the no-empty-appendix contract holds)") {
    val bare = rec("park-bare-4").copy(reportDetail = None, workReport = None)
    assertEquals(DelegateWorkReport.appendixFor(Some(bare)), "", "an all-empty appendix stays empty")
  }

end DelegateWorkReportParkDedupSpec
