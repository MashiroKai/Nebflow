package nebflow.core.delegate

import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * 收口面修复（甲′，2026-11-21）：取消墓碑 ∧ 自身任务行已 `cancelled` 的身份，派生读数应
 * 判收口（不再被 `cancel-requested` 活标记钉死在活动窗）；**墓碑记录本身原样保留**，三处
 * 复活守卫（SessionResumption.judge / requiresManual / MailTool）仍按 `cancelRequestedAt` 拒绝。
 *
 * 断言面：
 *  - 纯派生 [[DelegateVisibility.read]]：豁免开/关/缺省三态；
 *  - 现读 [[DelegateVisibility.readOf]] + 盘上夹具（任务档、轮次台账）；
 *  - 墓碑不被读面写掉（`cancelRequestedAt` 读后仍在）；
 *  - 复活守卫 [[SessionResumption.judge]] 对同一墓碑仍 Refuse；
 *  - 保守回落：任务档缺席 ⇒ 无豁免。
 */
class DelegateCancelTombstoneSettledSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-cancel-tombstone-settled"
  private val originalRoot = PathUtil.dataRoot
  private val now: Long = System.currentTimeMillis()

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "subagent-tasks")
    os.remove.all(tempRoot / "sessions")
    os.makeDir.all(tempRoot)
    ()

  private val parent = "root"
  private val id = "delegate-nebflow-cancel01"

  private def record(cancelled: Boolean, archived: Boolean): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = (tempRoot / "general").toString,
      project = None,
      taskId = Some("2533"),
      parentSessionId = parent,
      title = "cancel tombstone fixture",
      createdAt = now - 120_000L,
      archivedAt = Option.when(archived)(now - 60_000L),
      cancelRequestedAt = Option.when(cancelled)(now - 50_000L),
      cancelRequestedBy = Option.when(cancelled)("Nebula")
    )

  private def settledRound: List[DelegateRoundLedger.RoundRow] =
    List(
      DelegateRoundLedger.RoundRow(
        kind = DelegateRoundLedger.KindRound,
        roundNo = 1,
        roundId = "r1",
        startedAt = now - 100_000L,
        cause = "spawn",
        settledAt = Some(now - 40_000L)
      )
    )

  /** 任务档直接落盘（派发会话档 `subagent-tasks/<parent>.json`，taskId == 身份 id）。 */
  private def seedOwnTask(status: String): Unit =
    val dir = tempRoot / "subagent-tasks"
    os.makeDir.all(dir)
    val row = io.circe.Json.arr(
      io.circe.Json.obj(
        "taskId" -> id.asJson,
        "parentSessionId" -> parent.asJson,
        "agentName" -> "nebflow".asJson,
        "prompt" -> "fixture".asJson,
        "description" -> "fixture".asJson,
        "status" -> status.asJson,
        "retryCount" -> 0.asJson,
        "spawnedAt" -> (now - 110_000L).asJson,
        "source" -> "subagent".asJson
      )
    )
    os.write.over(dir / s"$parent.json", row.noSpaces)

  /** 轮次台账直接落盘（已结算一轮）。 */
  private def seedSettledRoundFile(): Unit =
    val dir = tempRoot / "delegates"
    os.makeDir.all(dir)
    val row = io.circe.Json.obj(
      "kind" -> "round".asJson,
      "roundNo" -> 1.asJson,
      "roundId" -> "r1".asJson,
      "startedAt" -> (now - 100_000L).asJson,
      "cause" -> "spawn".asJson,
      "settledAt" -> (now - 40_000L).asJson
    )
    os.write.append(dir / s"$id.rounds.jsonl", row.noSpaces + "\n", createFolders = true)

  // ── 纯派生 ─────────────────────────────────────────────────────────────

  test("pure: cancel tombstone + own task cancelled ⇒ settled, no cancel-requested blocker") {
    val r = DelegateVisibility.read(
      Some(record(cancelled = true, archived = true)),
      settledRound,
      DelegateVisibility.LiveChildren(0, Nil, Nil, fileUnreadable = false),
      ownTaskStatus = Some("cancelled")
    )
    assert(r.settled, s"expected settled, blockers=${r.blockers}")
    assert(r.historyWindow, "settled ∧ legacy archive flag ⇒ history window")
    assert(!r.blockers.contains(s"live-mark:${DelegateVisibility.CancelRequestedMark}"))
  }

  test("pure: cancel tombstone + own task still running ⇒ NOT settled (tombstone stays a live mark)") {
    val r = DelegateVisibility.read(
      Some(record(cancelled = true, archived = true)),
      settledRound,
      DelegateVisibility.LiveChildren(0, Nil, Nil, fileUnreadable = false),
      ownTaskStatus = Some("running")
    )
    assert(!r.settled)
    assert(r.blockers.contains(s"live-mark:${DelegateVisibility.CancelRequestedMark}"))
  }

  test("pure: cancel tombstone + task row absent (None) ⇒ NOT settled (conservative default)") {
    val r = DelegateVisibility.read(
      Some(record(cancelled = true, archived = true)),
      settledRound,
      DelegateVisibility.LiveChildren(0, Nil, Nil, fileUnreadable = false)
    )
    assert(!r.settled)
    assert(r.blockers.contains(s"live-mark:${DelegateVisibility.CancelRequestedMark}"))
  }

  test("pure: own task cancelled but no tombstone ⇒ exemption is inert (no mark to exempt)") {
    val r = DelegateVisibility.read(
      Some(record(cancelled = false, archived = true)),
      settledRound,
      DelegateVisibility.LiveChildren(0, Nil, Nil, fileUnreadable = false),
      ownTaskStatus = Some("cancelled")
    )
    assert(r.settled)
    assert(r.blockers.isEmpty)
  }

  // ── 现读 + 盘上夹具 ─────────────────────────────────────────────────────

  test("readOf: on-disk cancelled task row + settled round ⇒ settled; tombstone is NOT written away") {
    DelegateRegistry.put(record(cancelled = true, archived = true))
    seedOwnTask("cancelled")
    seedSettledRoundFile()

    val reading = DelegateVisibility.readOf(id)
    assert(reading.settled, s"expected settled, blockers=${reading.blockers}")

    val after = DelegateRegistry.find(id).get
    assert(after.cancelRequestedAt.isDefined, "tombstone must survive the read surface (zero write)")
    assertEquals(after.cancelRequestedBy, Some("Nebula"))
  }

  test("readOf: same fixture with own task still running ⇒ not settled") {
    DelegateRegistry.put(record(cancelled = true, archived = true))
    seedOwnTask("running")
    seedSettledRoundFile()
    assert(!DelegateVisibility.readOf(id).settled)
  }

  test("ownTaskStatusOf: reads the parent task file by taskId; absent file ⇒ None") {
    val rec = record(cancelled = true, archived = true)
    assertEquals(DelegateVisibility.ownTaskStatusOf(rec), None)
    seedOwnTask("cancelled")
    assertEquals(DelegateVisibility.ownTaskStatusOf(rec), Some("cancelled"))
  }

  // ── 复活守卫不变（墓碑仍在 ⇒ judge 仍 Refuse）─────────────────────────

  test("resume guard: a settled-by-exemption cancelled identity is still refused by SessionResumption.judge") {
    DelegateRegistry.put(record(cancelled = true, archived = true))
    seedOwnTask("cancelled")
    seedSettledRoundFile()
    assert(DelegateVisibility.readOf(id).settled)

    val verdict = SessionResumption.judge(DelegateRegistry.find(id).get, transcriptLoadError = None, transcriptMessages = 3)
    verdict match
      case SessionResumption.Verdict.Refuse(reason) =>
        assert(reason.contains("cancel tombstone"), reason)
      case other => fail(s"cancelled identity must never resume in place, got $other")
  }

end DelegateCancelTombstoneSettledSpec
