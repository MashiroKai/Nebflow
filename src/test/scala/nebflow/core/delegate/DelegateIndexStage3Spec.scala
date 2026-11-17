package nebflow.core.delegate

import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * Stage ③ (delegate-lifecycle-reform) — indexed persistence of the delegate corpus.
 *
 * These assertions go through **pre-existing entry points only**
 * ([[DelegateRegistry.put]] / `list` / `remove`, [[DelegateRoundLedger.openRound]]) plus
 * raw `java.sql` + `os-lib`, so the file compiles against the baseline commit
 * (ed27b076f) and the red state is a **behavioral** red, not a compile error:
 *
 *  - R1 the corpus is persisted into a per-root SQLite index on the load path
 *    (baseline: no such artifact exists at all);
 *  - R2 the 7-day physical cleanup of an identity removes its round ledger too
 *    (baseline: `remove` deletes `<id>.json` and leaves `<id>.rounds.jsonl` behind,
 *    which is exactly the orphan-ledger shape the migration validator must report);
 *  - R1c/R2c are the **control faces** — they hold on both sides of the change and
 *    pin down that the red legs are not a "nothing happens" no-op.
 *
 * The index internals (parity vs. the source scan, watermark invalidation, per-round
 * dedup materialization, pin, rollback, migration verification) live in
 * [[DelegateIndexInternalsSpec]] — new surface, so it has no baseline counterpart.
 */
class DelegateIndexStage3Spec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "delegate-index-stage3")
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def mkRecord(id: String, createdAt: Long = System.currentTimeMillis(), archivedAt: Option[Long] = None): DelegateRegistry.Record =
    val rec = DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = tmp.toString,
      project = None,
      taskId = None,
      parentSessionId = "parent-index-stage3",
      title = s"stage3 $id",
      createdAt = createdAt,
      archivedAt = archivedAt
    )
    DelegateRegistry.put(rec)
    rec

  private def indexDb: os.Path = PathUtil.dataRoot / "delegate-index" / "delegates.db"

  private def indexRowCount(db: os.Path): Int =
    val conn = java.sql.DriverManager.getConnection(s"jdbc:sqlite:${db.toString}")
    try
      val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM delegates")
      if rs.next() then rs.getInt(1) else -1
    finally conn.close()

  // ── R1 索引持久化 ────────────────────────────────────────────────────────

  test("R1 red: a full load persists the corpus into <root>/delegate-index/delegates.db") {
    val ids = List("delegate-nebflow-idx1", "delegate-nebflow-idx2", "delegate-nebflow-idx3")
    ids.zipWithIndex.foreach { case (id, i) => mkRecord(id, createdAt = 1_700_000_000_000L + i) }

    val loaded = DelegateRegistry.list()
    assertEquals(loaded.map(_.id).sorted, ids.sorted, "the load face must still see every record")

    assert(
      os.exists(indexDb),
      "the load path must persist the corpus into <root>/delegate-index/delegates.db (indexed persistence)")
    assertEquals(indexRowCount(indexDb), ids.size, "the index must hold one row per identity")
  }

  test("R1c control: a repeated load is idempotent and keeps every record") {
    val ids = List("delegate-nebflow-idem1", "delegate-nebflow-idem2")
    ids.foreach(id => mkRecord(id))
    assertEquals(DelegateRegistry.list().map(_.id).sorted, ids.sorted)
    assertEquals(DelegateRegistry.list().map(_.id).sorted, ids.sorted)
  }

  // ── R2 七天物理清理（记录 + 台账一并清走）───────────────────────────────

  test("R2 red: physical cleanup removes the round ledger along with the record") {
    val id = "delegate-nebflow-clean1"
    mkRecord(id)
    DelegateRoundLedger.openRound(id, 1, "round-1", DelegateRoundLedger.CauseSpawn, None, None)

    val ledger = PathUtil.dataRoot / "delegates" / s"$id.rounds.jsonl"
    val record = PathUtil.dataRoot / "delegates" / s"$id.json"
    assert(os.exists(ledger), "fixture: the round ledger exists before cleanup")
    assert(os.exists(record), "fixture: the identity record exists before cleanup")

    assert(DelegateRegistry.remove(id).isRight, "removal must succeed")

    assert(!os.exists(record), "the identity record is physically cleaned")
    assert(
      !os.exists(ledger),
      "the append-only round ledger must be physically cleaned with its identity — a retained ledger is an orphan ledger")
    assertEquals(DelegateRegistry.list().map(_.id), Nil)
  }

  test("R2c control: an identity within its 7-day terminal TTL is not expired") {
    val now = System.currentTimeMillis()
    val rec = mkRecord("delegate-nebflow-ttlctl", createdAt = now - 60_000L)
    val v = DelegateTerminalTtl.verdict(rec, None, now)
    assert(!v.isExpired, s"a fresh terminal identity must not be expired (verdict=$v)")
  }
end DelegateIndexStage3Spec
