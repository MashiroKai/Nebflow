package nebflow.core

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import java.time.{LocalDateTime, ZoneId}
import scala.collection.mutable.ListBuffer

/**
 * Equivalence apparatus for the incremental aggregate path — the P0 hard criterion of
 * the `tokenpanel-incremental` batch.
 *
 * Contract: `aggregate` (incremental, cache-backed) must equal `aggregateFull`
 * (today's whole-ledger recompute) **value by value** for every query shape and
 * every corpus: totals, count, `costEquivalent`, the bucket list including its order,
 * and the dropped-line count. Any difference is a failure, and the failure record
 * names the first differing field.
 *
 * Coverage:
 *   - synthetic corpora shaped to break naive implementations: cacheRead values whose
 *     fractional parts differ across cells (the `costEquivalent` truncation trap),
 *     local-midnight / month / year crossings, late and duplicate timestamps,
 *     zero-token rows, hour-edge windows;
 *   - the same corpus appended in 1/2/3/8 segments, compared after each segment
 *     (cross-segment merging, not just "computed in one go");
 *   - the live ledger (`340,025` rows) plus synthetic gradients, supplied through
 *     `NEBFLOW_USAGE_CORPORA` as `name=path` pairs (comma separated) — the reduced
 *     matrix is used there because each reference call re-reads the whole ledger;
 *   - an anti-masking guard: `fallbacks == 0` is asserted, otherwise a broken
 *     incremental path that silently degrades to the reference could pass.
 *
 * The per-case verdicts are written as JSON to `NEBFLOW_USAGE_EQUIV_REPORT`
 * (default `target/usage-aggregate-equivalence/diff-report.json`).
 */
class UsageAggCacheEquivalenceSpec extends FunSuite:

  private val zone: ZoneId = ZoneId.systemDefault()
  private val MS_H = 3600_000L
  private val MS_D = 24 * MS_H

  private def local(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
    LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant.toEpochMilli

  private final case class Query(
    label: String,
    dim: Option[String],
    from: Option[Long],
    to: Option[Long],
    provider: Option[String] = None,
    model: Option[String] = None,
    agent: Option[String] = None
  )

  private final case class CaseResult(
    corpus: String,
    segments: Int,
    segment: Int,
    query: String,
    ok: Boolean,
    detail: String,
    refMs: Double,
    incMs: Double
  )

  private val results = ListBuffer.empty[CaseResult]

  private def reportPath: String =
    sys.env.getOrElse("NEBFLOW_USAGE_EQUIV_REPORT", "target/usage-aggregate-equivalence/diff-report.json")

  override def afterAll(): Unit =
    val failed = results.count(!_.ok)
    val json = Json.obj(
      "corpus" -> Json.fromString("usage-aggregate equivalence (incremental vs full recompute)"),
      "zone" -> Json.fromString(zone.getId),
      "comparisons" -> Json.fromInt(results.size),
      "failures" -> Json.fromInt(failed),
      "verdict" -> Json.fromString(if failed == 0 then "PASS (zero differences)" else "FAIL"),
      "cases" -> Json.arr(
        results.toList.map(r =>
          Json.obj(
            "corpus" -> Json.fromString(r.corpus),
            "segments" -> Json.fromInt(r.segments),
            "segment" -> Json.fromInt(r.segment),
            "query" -> Json.fromString(r.query),
            "ok" -> Json.fromBoolean(r.ok),
            "detail" -> Json.fromString(r.detail),
            "full_ms" -> Json.fromDoubleOrNull(r.refMs),
            "incremental_ms" -> Json.fromDoubleOrNull(r.incMs)
          )
        )*
      )
    )
    val p = os.Path(reportPath, os.pwd)
    os.makeDir.all(p / os.up)
    os.write.over(p, json.spaces2, createFolders = true)
    println(s"[equivalence] ${results.size} comparisons, $failed failures → $p")
    super.afterAll()

  // ── corpus + query construction ─────────────────────────────────────────────

  private def rec(
    ts: Long,
    p: String = "deepseek",
    m: String = "deepseek-v4-flash",
    a: String = "Backend",
    in: Int = 1000,
    out: Int = 100,
    cr: Int = 900,
    cw: Int = 0
  ): LlmUsageRecord = LlmUsageRecord(ts, p, m, a, Some("s1"), in, out, cr, cw)

  private def jsonLine(r: LlmUsageRecord): String = r.asJson.noSpaces

  private def windows(base: Long, maxTs: Long): List[Query] =
    List(
      Query("unbounded", None, None, None),
      Query("1d", Some("day"), Some(base), Some(base + MS_D)),
      Query("7d", Some("day"), Some(base), Some(base + 7 * MS_D)),
      Query("1y", Some("day"), Some(base), Some(base + 365 * MS_D)),
      Query("half-day", Some("hour"), Some(base), Some(base + 12 * MS_H)),
      Query("hour-aligned-3h", Some("hour"), Some(local(2026, 12, 31, 21, 0)), Some(local(2027, 1, 1, 0, 0))),
      Query("non-hour-aligned", Some("hour"), Some(base + 30 * 60_000L), Some(base + 90 * 60_000L)),
      Query("tail-hour", Some("hour"), Some(maxTs - 30 * 60_000L), Some(maxTs + MS_H))
    )

  private def withFilters(w: Query, ps: List[String], ms: List[String], as: List[String]): List[Query] =
    val p = ps.headOption
    val m = ms.headOption
    val a = as.headOption
    List(
      w,
      w.copy(label = w.label + "/provider", provider = p),
      w.copy(label = w.label + "/model", model = m),
      w.copy(label = w.label + "/agent", agent = a),
      w.copy(label = w.label + "/combo", provider = p, model = m, agent = a)
    )

  private def matrix(records: List[LlmUsageRecord]): List[Query] =
    val ts = records.map(_.timestamp)
    val base = if ts.isEmpty then local(2026, 12, 31, 20, 0) else ts.min
    val maxTs = if ts.isEmpty then base else ts.max
    val ps = records.map(_.provider).distinct.take(2)
    val ms = records.map(_.model).distinct.take(2)
    val as = records.map(_.agent).distinct.take(2)
    val dims = List(None, Some("provider"), Some("model"), Some("agent"), Some("hour"), Some("day"), Some("bogus"))
    val cross = for
      w <- windows(base, maxTs)
      wf <- withFilters(w, ps, ms, as)
    yield wf
    for
      d <- dims
      w <- cross
    yield w.copy(label = s"dim=${d.getOrElse("none")}/${w.label}", dim = d)

  /** Reduced matrix for whole-ledger corpora (every reference call re-reads everything). */
  private def reducedMatrix(records: List[LlmUsageRecord]): List[Query] =
    val ts = records.map(_.timestamp)
    val base = ts.min
    val maxTs = ts.max
    val p = records.map(_.provider).distinct.headOption
    val m = records.map(_.model).distinct.headOption
    val a = records.map(_.agent).distinct.headOption
    List(
      Query("dim=none/unbounded", None, None, None),
      Query("dim=day/unbounded", Some("day"), None, None),
      Query("dim=hour/unbounded", Some("hour"), None, None),
      Query("dim=provider/unbounded", Some("provider"), None, None),
      Query("dim=agent/unbounded", Some("agent"), None, None),
      Query("dim=model/unbounded", Some("model"), None, None),
      Query("dim=day/last-1d", Some("day"), Some(maxTs - MS_D), Some(maxTs + MS_H)),
      Query("dim=none/half-day", None, Some(maxTs - MS_D / 2), Some(maxTs)),
      Query("dim=hour/last-6h", Some("hour"), Some(maxTs - 6 * MS_H), Some(maxTs + MS_H)),
      Query("dim=none/from-first-day", None, Some(base), Some(base + MS_D)),
      Query("dim=provider/filtered", Some("provider"), None, None, provider = p),
      Query("dim=agent/combo", Some("agent"), None, None, provider = p, model = m, agent = a),
      Query("dim=day/non-aligned", Some("day"), Some(base + 12 * 60_000L), Some(maxTs - 30 * 60_000L)),
      Query("dim=hour/exact-tail", Some("hour"), Some(maxTs - 60_000L), Some(maxTs + 60_000L))
    )

  // ── comparison ──────────────────────────────────────────────────────────────

  private def diffOf(inc: UsageAggregate, full: UsageAggregate): Option[String] =
    def f(name: String, a: Any, b: Any): Option[String] = if a == b then None else Some(s"$name incremental=$a full=$b")
    f("totalInput", inc.totalInput, full.totalInput)
      .orElse(f("totalOutput", inc.totalOutput, full.totalOutput))
      .orElse(f("totalCacheRead", inc.totalCacheRead, full.totalCacheRead))
      .orElse(f("totalCacheWrite", inc.totalCacheWrite, full.totalCacheWrite))
      .orElse(f("count", inc.count, full.count))
      .orElse(f("costEquivalent", inc.costEquivalent, full.costEquivalent))
      .orElse {
        if inc.buckets.size != full.buckets.size then Some(s"bucket count incremental=${inc.buckets.size} full=${full.buckets.size}")
        else
          inc.buckets
            .zip(full.buckets)
            .collectFirst { case (x, y) if x != y => s"bucket incremental=$x full=$y" }
            .orElse(
              if inc.buckets.map(_.key) != full.buckets.map(_.key) then Some(s"bucket order incremental=${inc.buckets.map(_.key)} full=${full.buckets.map(_.key)}")
              else None
            )
      }

  private def compare(corpus: String, segments: Int, segment: Int, store: UsageRecordStore, q: Query): Unit =
    val t0 = System.nanoTime()
    val inc = store.aggregate(q.dim, q.from, q.to, q.provider, q.model, q.agent).unsafeRunSync()
    val t1 = System.nanoTime()
    val full = store.aggregateFull(q.dim, q.from, q.to, q.provider, q.model, q.agent).unsafeRunSync()
    val t2 = System.nanoTime()
    val d = diffOf(inc, full)
    results += CaseResult(corpus, segments, segment, q.label, d.isEmpty, d.getOrElse("zero difference"), (t2 - t1) / 1e6, (t1 - t0) / 1e6)

  /**
   * Run one corpus: append in `segments` chunks, and after every chunk compare the
   * whole query matrix (the incremental path has to keep the cache in sync across
   * appends, so comparing only at the end would miss merge bugs).
   *
   * `bulk = false` exercises the real `record()` append path (small corpora);
   * `bulk = true` writes the same line format with the same `os.write.append`
   * primitive (whole-ledger corpora, where one IO per row is not affordable).
   */
  private def runCorpus(
    name: String,
    records: List[LlmUsageRecord],
    segments: Int,
    queries: List[Query],
    bulk: Boolean
  ): Unit =
    val dir = os.temp.dir(prefix = s"nb-equiv-$name-")
    val log = dir / "usage-records.jsonl"
    val store = new UsageRecordStore(dir)
    val chunks = if segments <= 1 then List(records) else records.grouped(math.max(1, records.size / segments)).toList
    chunks.zipWithIndex.foreach { case (chunk, idx) =>
      if chunk.nonEmpty then
        if bulk then
          if idx == 0 then os.write.over(log, chunk.map(jsonLine).mkString("", "\n", "\n"), createFolders = true)
          else os.write.append(log, chunk.map(jsonLine).mkString("", "\n", "\n"))
        else chunk.foreach(r => store.record(r).unsafeRunSync())
      queries.foreach(q => compare(name, chunks.size, idx + 1, store, q))
    }
    // Anti-masking: the incremental path must be the one that served the queries.
    val diag = store.cacheDiagnostics.unsafeRunSync()
    val served = diag.rebuilds + diag.increments + diag.hits
    assert(diag.fallbacks == 0, s"$name: the cache path threw and fell back to full recompute x${diag.fallbacks} — equivalence would be vacuous")
    assert(served > 0, s"$name: no cache-path call recorded (diagnostics=$diag)")
    // Dropped-line parity: the reference silently drops undecodable lines; the cache counts them.
    val fileLines = if os.exists(log) then os.read.lines(log).size else 0
    val loaded = store.loadAll().unsafeRunSync().size
    val refDropped = (fileLines - loaded).toLong
    val incDropped = diag.droppedLines
    results += CaseResult(name, chunks.size, chunks.size, "droppedLines", refDropped == incDropped, s"incremental=$incDropped full=$refDropped (fileLines=$fileLines)", 0.0, 0.0)
    assert(
      refDropped == incDropped,
      s"$name: dropped-line count differs — incremental=$incDropped full=$refDropped"
    )

  private def assertNoFailures(): Unit =
    val bad = results.filter(!_.ok)
    if bad.nonEmpty then
      val head = bad.take(5).map(r => s"${r.corpus} seg${r.segment} ${r.query}: ${r.detail}").mkString("\n  ")
      fail(s"${bad.size}/${results.size} equivalence comparisons differ:\n  $head")

  // ── synthetic corpora ───────────────────────────────────────────────────────

  test("equivalence: empty corpus") {
    runCorpus("empty", Nil, 1, matrix(Nil), bulk = false)
    assertNoFailures()
  }

  test("equivalence: single record") {
    val b = local(2026, 12, 31, 23, 30)
    runCorpus("single", List(rec(b)), 1, matrix(List(rec(b))), bulk = false)
    assertNoFailures()
  }

  test("equivalence: costEquivalent truncation across cells and segments (M4 trap)") {
    // cacheRead values whose fractional 0.1x parts differ per cell: summing per-cell
    // costs truncates once per cell, recomputing on the merged total truncates once.
    val b = local(2026, 12, 31, 22, 0)
    val rs = List(
      rec(b + 0, "deepseek", "m-a", "Backend", in = 1000, cr = 905, cw = 5),
      rec(b + 60_000, "deepseek", "m-a", "Backend", in = 1000, cr = 907, cw = 0),
      rec(b + 2 * MS_H, "zhipu", "m-b", "Frontend", in = 2000, out = 55, cr = 1234, cw = 7),
      rec(b + 2 * MS_H + 60_000, "zhipu", "m-b", "Frontend", in = 2000, out = 0, cr = 1235, cw = 0),
      rec(b + 3 * MS_H, "kimi", "m-c", "Nebula", in = 3000, out = 12, cr = 899, cw = 3),
      rec(b + 4 * MS_H, "kimi", "m-c", "Nebula", in = 4000, out = 1, cr = 101, cw = 0)
    )
    (1 to 3).foreach { seg =>
      val before = results.size
      runCorpus(s"truncation-seg$seg", rs, seg, matrix(rs), bulk = false)
      assert(results.drop(before).forall(_.ok), s"segment split $seg produced differences")
    }
    assertNoFailures()
  }

  test("equivalence: local midnight, month and year crossing") {
    val rs = List(
      rec(local(2026, 12, 31, 23, 55), cr = 100),
      rec(local(2026, 12, 31, 23, 59), cr = 205),
      rec(local(2027, 1, 1, 0, 0), cr = 310),
      rec(local(2027, 1, 1, 0, 1), cr = 415),
      rec(local(2026, 3, 1, 0, 0), cr = 520),
      rec(local(2026, 2, 28, 23, 59), cr = 625)
    )
    runCorpus("crossings", rs, 2, matrix(rs), bulk = false)
    assertNoFailures()
  }

  test("equivalence: late, duplicate and zero-token rows") {
    val b = local(2026, 12, 31, 20, 0)
    val rs = List(
      rec(b, cr = 0, in = 0, out = 0),
      rec(b + 1000, cr = 0, in = 0, out = 0, a = "Nebula"),
      rec(b + 900, cr = 250), // late row (clock backstep)
      rec(b + 900, cr = 250), // duplicate timestamp
      rec(b + 2 * MS_H, cr = 7),
      rec(b + 2 * MS_H - 1500, cr = 13), // 1.5 s backstep across an hour boundary
      rec(b + 5 * MS_H, cr = 0, in = 5, out = 0, p = "qwen", m = "qwen-max", a = "Coder")
    )
    runCorpus("late-duplicate", rs, 3, matrix(rs), bulk = false)
    assertNoFailures()
  }

  test("equivalence: 8-segment append with hour edges") {
    val b = local(2026, 12, 31, 18, 0)
    val rs = (0 until 40).toList.map { i =>
      rec(b + i * 22 * 60_000L, p = if i % 2 == 0 then "deepseek" else "zhipu", a = if i % 3 == 0 then "Backend" else "Frontend", in = 100 * (i + 1), out = i, cr = (i * 37) % 997, cw = i % 5)
    }
    runCorpus("hour-edges-seg8", rs, 8, matrix(rs), bulk = false)
    assertNoFailures()
  }

  // ── live ledger + synthetic gradients (env-driven) ──────────────────────────

  test("equivalence: supplied corpora (live ledger + gradients)") {
    val spec = sys.env.getOrElse("NEBFLOW_USAGE_CORPORA", "")
    if spec.trim.isEmpty then
      println("[equivalence] NEBFLOW_USAGE_CORPORA unset — large-corpus leg not run in this invocation")
    else
      spec.split(",").map(_.trim).filter(_.nonEmpty).foreach { entry =>
        entry.split("=", 2) match
          case Array(name, path) =>
            val src = os.Path(path, os.pwd)
            val records = os.read.lines(src).flatMap(l => io.circe.parser.decode[LlmUsageRecord](l).toOption).toList
            println(s"[equivalence] corpus $name: ${records.size} rows / ${os.size(src)} B")
            val before = results.size
            runCorpus(name, records, 2, reducedMatrix(records), bulk = true)
            assert(results.drop(before).forall(_.ok), s"corpus $name produced differences")
          case _ => fail(s"malformed corpus entry (expected name=path): $entry")
      }
    assertNoFailures()
  }

end UsageAggCacheEquivalenceSpec
