package nebflow.core.delegate

import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * Stage ③ index internals (delegate-lifecycle-reform) — **new surface**, so these legs have
 * no baseline counterpart (they cannot compile against the pre-stage-③ commit): parity with
 * the source scan, watermark invalidation, per-round dedup materialization, pin, rollback and
 * migration verification. The red→green legs live in [[DelegateIndexStage3Spec]].
 */
class DelegateIndexInternalsSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"
  private val Day = 86_400_000L

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "delegate-index-internals")
    PathUtil.setDataRoot(tmp / "data")
    DelegateIndex.unsafeResetForTests()
    DelegateIndex.resetCounters()

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateIndex.unsafeResetForTests()
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def mkRecord(
    id: String,
    createdAt: Long = System.currentTimeMillis(),
    archivedAt: Option[Long] = None,
    title: String = "internals"
  ): DelegateRegistry.Record =
    val rec = DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = tmp.toString,
      project = None,
      taskId = None,
      parentSessionId = "parent-internals",
      title = title,
      createdAt = createdAt,
      archivedAt = archivedAt
    )
    DelegateRegistry.put(rec)
    rec
  end mkRecord

  private def root: os.Path = PathUtil.dataRoot

  private def db: os.Path = DelegateIndex.dbPathOf(root)

  private def dbRows(path: os.Path): Int =
    val conn = java.sql.DriverManager.getConnection(s"jdbc:sqlite:${path.toString}")
    try
      val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM delegates")
      if rs.next() then rs.getInt(1) else -1
    finally conn.close()

  private def fixture(n: Int): List[String] =
    (1 to n).toList.map { i =>
      val id = f"delegate-nebflow-g$i%02d"
      mkRecord(id, createdAt = 1_700_000_000_000L + i)
      id
    }

  // ── G1 索引路径 == 源码路径（等值，逐记录）──────────────────────────────

  test("G1 the index load equals the source scan record for record") {
    val ids = fixture(6)
    DelegateRoundLedger.openRound(ids.head, 1, "r1", DelegateRoundLedger.CauseSpawn, None, None)
    val indexLoad = DelegateIndex.listRecords().getOrElse(fail("index path must be available"))
    val scan = DelegateIndex.scanRecords(root)
    assertEquals(indexLoad.map(_.id), scan.map(_.id), "same ids, same order")
    assertEquals(indexLoad, scan, "field-for-field equality with the source scan")
    assertEquals(indexLoad.map(_.id).sorted, ids.sorted)
  }

  // ── G2 热身加载零源文件重解析（启动收益的直接读数）──────────────────────

  test("G2 a warm load re-reads no source file (watermark invalidation only)") {
    fixture(5)
    val first = DelegateIndex.listRecords().getOrElse(fail("index path must be available"))
    assert(DelegateIndex.lastLoadStats.sourceParses >= 0L)
    DelegateIndex.resetCounters()
    val second = DelegateIndex.listRecords().getOrElse(fail("index path must be available"))
    assertEquals(second.map(_.id), first.map(_.id))
    assertEquals(DelegateIndex.lastLoadStats.sourceParses, 0L, "an unchanged corpus must not be parsed again")
    assertEquals(DelegateIndex.lastLoadStats.path, "index")
  }

  // ── G3 失效策略：改/增/删都在下一次加载生效（不陈旧）────────────────────

  test("G3 a changed, added or removed source file is reflected on the next load") {
    val ids = fixture(3)
    val kept = ids.head
    assertEquals(DelegateIndex.listRecords().map(_.map(_.id)).getOrElse(Nil).sorted, ids.sorted)

    // ① content change (different byte length ⇒ different watermark)
    val changed = DelegateRegistry.find(kept).get.copy(title = "a much longer title than before")
    os.write.over(root / "delegates" / s"$kept.json", changed.asJson.noSpaces)
    // ② new file
    val added = "delegate-nebflow-gnew"
    mkRecord(added, createdAt = 1_700_000_999_000L)
    // ③ removed file
    val removed = ids.last
    os.remove(root / "delegates" / s"$removed.json")

    val after = DelegateIndex.listRecords().getOrElse(fail("index path must be available"))
    val byId = after.map(r => r.id -> r).toMap
    assertEquals(byId(kept).title, "a much longer title than before", "content change must not be stale")
    assert(byId.contains(added), "a new source file must appear")
    assert(!byId.contains(removed), "a removed source file must disappear")
  }

  // ── G4 台账去重物化：同轮末行胜、stale-round 不入轮次状态 ────────────────

  test("G4 the ledger zone dedups by roundNo (last line wins) and drops stale-round audit rows") {
    val id = "delegate-nebflow-ledger"
    mkRecord(id)
    DelegateRoundLedger.openRound(id, 1, "r1", DelegateRoundLedger.CauseSpawn, None, None)
    DelegateRoundLedger.settleRound(id, 1, "r1", "completed", List("oid-1"), None)
    DelegateRoundLedger.recordStaleRound(id, 0, "r0", "late event")
    DelegateRoundLedger.openRound(id, 2, "r2", DelegateRoundLedger.CauseMail, None, None)

    val lines = os.read.lines(root / "delegates" / s"$id.rounds.jsonl").toList
    assert(lines.size > 3, s"fixture: the ledger keeps every physical line (got ${lines.size})")

    // cold index state: the materialization must be rebuilt from the file
    DelegateIndex.close()
    val fromIndex = DelegateIndex.currentRoundsOf(id).getOrElse(fail("index path must serve the ledger"))
    val fromFile = DelegateRoundLedger.currentRoundsFromFile(id)
    assertEquals(fromIndex, fromFile, "materialized dedup must equal the file read face")
    assertEquals(fromIndex.map(_.roundNo), List(1, 2), "one current row per roundNo, ascending")
    assertEquals(fromIndex.head.verdict, Some("completed"), "the settled line of round 1 wins)")
    assertEquals(fromIndex.last.settledAt, None, "round 2 is still open")
    assert(fromIndex.forall(_.kind == DelegateRoundLedger.KindRound), "stale-round rows carry no round state")
  }

  // ── G5 置顶：7 天物理清理的豁免面（含未置顶对照）────────────────────────

  test("G5 a pinned identity never expires while the unpinned twin does") {
    val now = System.currentTimeMillis()
    val anchor = now - 30 * Day
    val pinned = "delegate-nebflow-pin1"
    val free = "delegate-nebflow-pin2"
    mkRecord(pinned, createdAt = anchor, archivedAt = Some(anchor))
    mkRecord(free, createdAt = anchor, archivedAt = Some(anchor))

    assertEquals(DelegateRegistry.pin(pinned).isRight, true)
    val ts = DelegateRegistry.pinnedOf(pinned)
    assert(ts.isDefined, "pin must be readable back")
    assertEquals(DelegateRegistry.pin(pinned).toOption, ts, "pin is idempotent (same timestamp, no rewrite)")

    val pinnedVerdict = DelegateTerminalTtl.verdict(DelegateRegistry.find(pinned).get, None, now)
    val freeVerdict = DelegateTerminalTtl.verdict(DelegateRegistry.find(free).get, None, now)
    assertEquals(pinnedVerdict, DelegateTerminalTtl.Pinned, "30 days past settlement, pinned stays")
    assert(freeVerdict.isExpired, s"the unpinned twin must expire (verdict=$freeVerdict)")

    assertEquals(DelegateRegistry.unpin(pinned).isRight, true)
    assert(
      DelegateTerminalTtl.verdict(DelegateRegistry.find(pinned).get, None, now).isExpired,
      "unpinning returns the identity to the normal 7-day clock"
    )
  }

  // ── G6 撤销：索引是可丢的派生层，源码读面照旧 ───────────────────────────

  test("G6 rollback drops the index and the source read face stays complete") {
    val ids = fixture(4)
    assert(DelegateIndex.listRecords().isDefined)
    assert(os.exists(db))
    assertEquals(dbRows(db), ids.size)

    val report = DelegateIndex.rollback(root)
    assert(report.dropped, s"rollback must move the index away (report=$report)")
    assert(!os.exists(db), "the live index file is gone after rollback")
    val backup = os.Path(report.backup.getOrElse(fail("rollback must keep a backup")))
    assert(os.exists(backup), "the moved index stays as evidence")
    assertEquals(dbRows(backup), ids.size, "the backed-up index still holds the corpus")

    assertEquals(DelegateRegistry.list().map(_.id).sorted, ids.sorted, "source fallback is intact")
  }

  // ── G7 迁移 + 逐记录/计数/两窗对账 ──────────────────────────────────────

  test("G7 migration imports both zones and verification matches the source per record and in counts") {
    val now = System.currentTimeMillis()
    val archived = List("delegate-nebflow-a1", "delegate-nebflow-a2")
    val active = List("delegate-nebflow-b1", "delegate-nebflow-b2")
    archived.foreach(id => mkRecord(id, createdAt = now - 9 * Day, archivedAt = Some(now - 8 * Day)))
    active.foreach(id => mkRecord(id, createdAt = now - 1_000L))
    DelegateRoundLedger.openRound(archived.head, 1, "r1", DelegateRoundLedger.CauseSpawn, None, None)
    DelegateRoundLedger.settleRound(archived.head, 1, "r1", "completed", Nil, None)
    DelegateRoundLedger.openRound(active.head, 1, "r1", DelegateRoundLedger.CauseSpawn, None, None)

    val dedup = DelegateIndex.validateDedup(root)
    assert(dedup.ok, s"fixture must be dedup-legal: $dedup")
    assertEquals(dedup.identityFiles, 4)
    assertEquals(dedup.archivedIdentities, 2)
    assertEquals(dedup.nonArchivedIdentities, 2)
    assertEquals(dedup.ledgerFiles, 2)

    val report = DelegateIndex.migrate(root).fold(e => fail(s"migration refused: $e"), identity)
    assertEquals(report.importedIdentities, 4)
    assertEquals(report.importedLedgers, 2)
    assertEquals(report.skipped, Nil)
    assert(report.drift.forall(_.delta == 0L), s"no expectation given ⇒ actuals are the baseline: ${report.drift}")

    val verified = DelegateIndex.verify(root).getOrElse(fail("verify needs an index"))
    assert(verified.countsMatch, s"counts must match: $verified")
    assertEquals(verified.recordMismatches, Nil)
    assertEquals(verified.roundMismatches, Nil)
    assertEquals(verified.extraRows, Nil)
    assert(verified.partitionOk, "two-window partition must stay complete after migration")
    assertEquals(verified.windowHistory, 2, "settled + legacy archive flag ⇒ history window")
    assertEquals(verified.windowActive, 2, "no settlement evidence yet ⇒ active window")
    assertEquals(verified.windowActive + verified.windowHistory, verified.windowTotal)
    assert(verified.ok, s"verification must be clean: $verified")
  }

  // ── G8 迁移前双区去重硬违规 ⇒ 拒迁 ──────────────────────────────────────

  test("G8 a duplicated identity key is a hard violation and refuses the migration") {
    mkRecord("delegate-nebflow-good")
    val hostile = DelegateRegistry.find("delegate-nebflow-good").get.copy(id = "delegate-nebflow-other")
    os.write(root / "delegates" / "delegate-nebflow-clash.json", hostile.asJson.noSpaces)

    val dedup = DelegateIndex.validateDedup(root)
    assert(!dedup.ok, "filename stem ≠ record id must not pass silently")
    assertEquals(dedup.idFieldMismatches.size, 1)
    assert(DelegateIndex.migrate(root).isLeft, "migration must be refused on a dedup hard violation")
  }
end DelegateIndexInternalsSpec
