package nebflow.core.delegate

import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * Round-model spec (contract: three-layer separation — persistent identity vs
 * execution round vs turn). Covers:
 *
 *  - R1 ledger append-only: open + settle rows accumulate; a settled round is never
 *    rewritten; old-round truth survives later rounds (no whitewashing).
 *  - R2 countedOids accumulate only over SETTLED rounds (cross-round dedup account).
 *  - R3 stale-round audit rows: kind=stale-round, never part of round state.
 *  - R4 cancel settles every open round as cancelled (tombstone wins across rounds).
 *  - R5 decideMailRound pure decision: busy signals inject into the CURRENT round;
 *    settled identity opens a NEW round; insufficient evidence leaves things as-is.
 *  - R6 openNewRound effects: roundNo+1, fresh roundId, archivedAt cleared (back to
 *    the active window), diff baseline re-anchored, prior ledger rows preserved.
 *  - R7 legacy tolerance: records without round fields decode and behave as round 1.
 */
class DelegateRoundLedgerSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "rounds-ledger")
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def mkRecord(
    id: String,
    cwd: os.Path,
    roundNo: Option[Int] = None,
    roundId: Option[String] = None,
    archived: Boolean = false
  ): DelegateRegistry.Record =
    val rec = DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = cwd.toString,
      project = None,
      taskId = None,
      parentSessionId = "parent-rounds",
      title = s"rounds $id",
      createdAt = System.currentTimeMillis() - 60_000L,
      archivedAt = if archived then Some(System.currentTimeMillis()) else None
    )
    DelegateRegistry.put(rec)
    // round pointer via the single write point (mirrors the spawn/Mail write faces)
    roundNo.foreach(n => DelegateRegistry.startRound(id, n, roundId.getOrElse("")))
    DelegateRegistry.find(id).getOrElse(sys.error("record must exist"))
  end mkRecord

  // ── R1 append-only + no whitewashing ──────────────────────────────────────

  test("R1 open + settle rows append; a settled round is never rewritten; old-round truth survives") {
    val id = "delegate-nebflow-r1"
    val rec = mkRecord(id, tmp, roundNo = Some(1), roundId = Some("gen-r1"))
    DelegateRoundLedger.openSpawnRound(id, "gen-r1", rec.cwd)
    DelegateRoundLedger.settleRound(id, 1, "gen-r1", "failed", Nil, Some(true))
    val afterFirstSettle = DelegateRoundLedger.currentRounds(id)
    // Settling the same round again must be an idempotent no-op (truth is frozen).
    DelegateRoundLedger.settleRound(id, 1, "gen-r1", "completed", List("oid-1"), Some(false))
    val afterReplay = DelegateRoundLedger.currentRounds(id)
    assertEquals(afterReplay, afterFirstSettle, "a settled round must never be rewritten")
    assertEquals(
      afterFirstSettle.map(r => (r.roundNo, r.verdict)),
      List((1, Some("failed"))),
      "round 1 keeps its failed truth"
    )
    // A later successful round does not touch round 1.
    DelegateRoundLedger.settleRound(id, 2, "gen-r2", "completed", List("oid-2"), None)
    val rounds = DelegateRoundLedger.currentRounds(id)
    assertEquals(rounds.map(_.roundNo), List(1, 2), "both rounds stay in the ledger")
    assertEquals(
      rounds.find(_.roundNo == 1).flatMap(_.verdict),
      Some("failed"),
      "later success never whitewashes an earlier failed round"
    )
    assertEquals(rounds.find(_.roundNo == 2).flatMap(_.verdict), Some("completed"))
  }

  // ── R2 countedOids over settled rounds only ───────────────────────────────

  test("R2 countedOids accumulate over settled rounds only") {
    val id = "delegate-nebflow-r2"
    val rec = mkRecord(id, tmp, roundNo = Some(1), roundId = Some("gen-a"))
    DelegateRoundLedger.openRound(id, 1, "gen-a", DelegateRoundLedger.CauseSpawn, None, None)
    assertEquals(DelegateRoundLedger.countedOids(id), Set.empty, "open round contributes nothing")
    DelegateRoundLedger.settleRound(id, 1, "gen-a", "completed", List("oid-1", "oid-2"), None)
    DelegateRoundLedger.openRound(id, 2, "gen-b", DelegateRoundLedger.CauseMail, None, None)
    assertEquals(
      DelegateRoundLedger.countedOids(id),
      Set("oid-1", "oid-2"),
      "open round 2 does not pollute the counted account"
    )
    DelegateRoundLedger.settleRound(id, 2, "gen-b", "completed", List("oid-2", "oid-3"), None)
    assertEquals(
      DelegateRoundLedger.countedOids(id),
      Set("oid-1", "oid-2", "oid-3"),
      "countedOids is the union over settled rounds"
    )
  }

  // ── R3 stale-round audit ──────────────────────────────────────────────────

  test("R3 stale-round audit rows land in the ledger but never join round state") {
    val id = "delegate-nebflow-r3"
    mkRecord(id, tmp, roundNo = Some(2), roundId = Some("gen-now"))
    DelegateRoundLedger.recordStaleRound(id, 1, "gen-old", "late terminal from an old round")
    val rows = DelegateRoundLedger.rowsOf(id)
    assertEquals(rows.map(_.kind), List(DelegateRoundLedger.KindStaleRound))
    assertEquals(rows.head.detail, Some("late terminal from an old round"))
    assertEquals(DelegateRoundLedger.currentRounds(id), Nil, "stale-round audit rows are never round state")
    // The audit row survives later round writes untouched (append-only file).
    DelegateRoundLedger.openRound(id, 2, "gen-now", DelegateRoundLedger.CauseMail, None, None)
    assert(DelegateRoundLedger.rowsOf(id).exists(_.kind == DelegateRoundLedger.KindStaleRound))
  }

  // ── R4 cancel settles every open round ────────────────────────────────────

  test("R4 cancel settles every open round as the cancelled terminal value") {
    val id = "delegate-nebflow-r4"
    mkRecord(id, tmp, roundNo = Some(2), roundId = Some("gen-r4b"))
    DelegateRoundLedger.openRound(id, 1, "gen-r4a", DelegateRoundLedger.CauseSpawn, None, None)
    DelegateRoundLedger.settleRound(id, 1, "gen-r4a", "completed", List("oid-x"), None)
    DelegateRoundLedger.openRound(id, 2, "gen-r4b", DelegateRoundLedger.CauseMail, None, None)
    DelegateRoundLedger.settleOpenRoundsCancelled(id, "cancelled terminal")
    val rounds = DelegateRoundLedger.currentRounds(id)
    assertEquals(
      rounds.find(_.roundNo == 1).flatMap(_.verdict),
      Some("completed"),
      "the already-settled round keeps its own truth"
    )
    assertEquals(
      rounds.find(_.roundNo == 2).flatMap(_.verdict),
      Some("cancelled"),
      "the open round settles as cancelled (tombstone wins)"
    )
    assert(rounds.find(_.roundNo == 2).exists(_.settledAt.isDefined))
  }

  // ── R5 pure Mail decision ─────────────────────────────────────────────────

  test("R5 decideMailRound: busy injects into the current round; settled opens a new round; unknown stays") {
    val rec = mkRecord("delegate-nebflow-r5", tmp, roundNo = Some(1), roundId = Some("gen-r5"))
    import DelegateRoundLedger.MailRoundDecision as D
    // busy: live actor registration
    assertEquals(
      DelegateRoundLedger.decideMailRound(rec, Some("completed"), actorRegistered = true),
      D.InjectCurrentRound,
      "a live actor means the round is running — Mail injects, no second execution body"
    )
    // busy: unfinished row statuses
    List("running", "restarting", "frozen", "ready-to-merge").foreach { s =>
      assertEquals(
        DelegateRoundLedger.decideMailRound(rec, Some(s), actorRegistered = false),
        D.InjectCurrentRound,
        s"row status '$s' means unfinished business — same round"
      )
    }
    // settled: terminal rows open a new round
    List("completed", "failed").foreach { s =>
      assertEquals(
        DelegateRoundLedger.decideMailRound(rec, Some(s), actorRegistered = false),
        D.OpenNewRound,
        s"row status '$s' is settled — Mail opens a new round"
      )
    }
    // unknown / missing evidence: legacy behavior, zero new writes
    assertEquals(DelegateRoundLedger.decideMailRound(rec, Some("weird-status"), actorRegistered = false), D.LeaveAsIs)
    assertEquals(
      DelegateRoundLedger.decideMailRound(rec.copy(archivedAt = None), None, actorRegistered = false),
      D.LeaveAsIs
    )
    // no task row + the identity reads closed by STATE (阶段②：收口判据由归档旗改为状态派生读数)
    assertEquals(
      DelegateRoundLedger.decideMailRound(rec, None, actorRegistered = false, identityClosed = true),
      D.OpenNewRound
    )
  }

  // ── R6 openNewRound effects ───────────────────────────────────────────────

  test(
    "R6 openNewRound: roundNo+1, fresh roundId, no flag write (state-derived return to the active window), baseline re-anchored, prior rows kept"
  ) {
    val id = "delegate-nebflow-r6"
    // seat = a real git repo so the baseline snapshot is judgeable
    val seat = tmp / "seat"
    os.makeDir.all(seat)
    os.proc("git", "-C", seat.toString, "init", "-q").call()
    os.proc("git", "-C", seat.toString, "config", "user.email", "t@t").call()
    os.proc("git", "-C", seat.toString, "config", "user.name", "t").call()
    os.write.over(seat / "base.txt", "base\n")
    os.proc("git", "-C", seat.toString, "add", ".").call()
    os.proc("git", "-C", seat.toString, "commit", "-qm", "base").call()
    mkRecord(id, seat, roundNo = Some(1), roundId = Some("gen-r6a"), archived = true)
    DelegateRoundLedger.openSpawnRound(id, "gen-r6a", seat.toString)
    DelegateRoundLedger.settleRound(id, 1, "gen-r6a", "completed", List("oid-early"), None)
    val flagBefore = DelegateRegistry.find(id).flatMap(_.archivedAt)
    assert(flagBefore.isDefined, "the fixture carries the legacy archive flag")
    assert(DelegateVisibility.readOf(id).historyWindow, "settled round 1 ⇒ the window reads history so far")
    // dirty one path BEFORE the new round opens — it belongs to the new baseline
    os.write.over(seat / "dirty.txt", "dirt\n")

    val row = DelegateRoundLedger.openNewRound(DelegateRegistry.find(id).get).getOrElse(sys.error("open must succeed"))

    assertEquals(row.roundNo, 2)
    assert(row.roundId != "gen-r6a", "a new round mints a fresh roundId")
    assert(row.baseHeadSha.isDefined, "the git seat baseline head is snapshotted")
    assertEquals(
      row.baseDirty.getOrElse(Nil).contains("?? dirty.txt"),
      true,
      "the dirty path is part of the new round's baseline"
    )
    val rec2 = DelegateRegistry.find(id).get
    assertEquals(rec2.roundNo, Some(2))
    assertEquals(rec2.roundId, Some(row.roundId))
    assertEquals(
      rec2.archivedAt,
      flagBefore,
      "阶段② 起零旗写盘 — the legacy archive flag is NOT rewritten (no storage archive write point remains)"
    )
    assert(
      !DelegateVisibility.readOf(id).historyWindow,
      "the open new round returns the identity to the active window (state-derived, zero flag write)"
    )
    val rounds = DelegateRoundLedger.currentRounds(id)
    assertEquals(rounds.map(_.roundNo), List(1, 2), "prior rounds stay in the ledger")
    assertEquals(rounds.head.cause, DelegateRoundLedger.CauseSpawn)
    assertEquals(rounds(1).cause, DelegateRoundLedger.CauseMail)
    assertEquals(
      DelegateRoundLedger.countedOids(id),
      Set("oid-early"),
      "the settled round-1 account is preserved for cross-round dedup"
    )
  }

  // ── R7 legacy tolerance ───────────────────────────────────────────────────

  test("R7 legacy records without round fields decode and read as round 1 implicit") {
    val id = "delegate-nebflow-r7"
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = tmp.toString,
        project = None,
        taskId = None,
        parentSessionId = "parent-rounds",
        title = "legacy",
        createdAt = System.currentTimeMillis()
      )
    )
    val rec = DelegateRegistry.find(id).get
    assertEquals(rec.roundNo, None, "legacy decode: no round pointer")
    assertEquals(DelegateRoundLedger.currentRound(id), None, "no ledger file = no rounds")
    // Mail decision on a settled legacy row still opens a new round (round 1 -> 2).
    assertEquals(
      DelegateRoundLedger.decideMailRound(rec, Some("completed"), actorRegistered = false),
      DelegateRoundLedger.MailRoundDecision.OpenNewRound
    )
    val row = DelegateRoundLedger.openNewRound(rec).getOrElse(sys.error("open must succeed"))
    assertEquals(row.roundNo, 2, "implicit round 1 + 1 = 2")
  }

  // ── R8 idempotent open ────────────────────────────────────────────────────

  test("R8 openRound is idempotent for an already-open round (frozen wake / boot revival keep the round)") {
    val id = "delegate-nebflow-r8"
    mkRecord(id, tmp, roundNo = Some(1), roundId = Some("gen-r8"))
    val first = DelegateRoundLedger.openRound(id, 1, "gen-r8", DelegateRoundLedger.CauseSpawn, None, None)
    val again = DelegateRoundLedger.openRound(id, 1, "gen-r8", DelegateRoundLedger.CauseRevivalSameRound, None, None)
    assertEquals(again, first, "re-opening the same open round is a zero-write no-op (same-round continuation)")
    assertEquals(DelegateRoundLedger.rowsOf(id).size, 1, "no duplicate rows")
  }

end DelegateRoundLedgerSpec
