package nebflow.core

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Files
import java.security.MessageDigest
import java.time.{LocalDateTime, ZoneId}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

/**
 * Behaviour of the persisted aggregate cache: the invalidation rules (V1–V7, R1/R2 of
 * `.nebflow/reports/20260917_tokenpanel-diag.md` §6.3), the additivity/truncation
 * invariants of the merge, the atomic write, and the deterministic performance
 * readings J-P1 (a warm request never rewrites the cache) / J-P2 (a warm request
 * consumes zero source bytes) plus the single-flight / read-lock reading.
 *
 * Every assertion here is binary (no wall-clock threshold), so it cannot go red from
 * machine noise — that is deliberate: red criteria must be deterministic, thresholds
 * are reported as measurements by the bench runner instead.
 */
class UsageAggCacheSpec extends FunSuite:

  private val zone: ZoneId = ZoneId.systemDefault()
  private val MS_H = 3600_000L

  private def local(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
    LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant.toEpochMilli

  private def rec(
    ts: Long,
    p: String = "deepseek",
    m: String = "m-a",
    a: String = "Backend",
    in: Int = 1000,
    out: Int = 100,
    cr: Int = 900,
    cw: Int = 0
  ): LlmUsageRecord = LlmUsageRecord(ts, p, m, a, Some("s1"), in, out, cr, cw)

  private def writeLedger(dir: os.Path, rs: List[LlmUsageRecord]): Unit =
    os.write.over(dir / "usage-records.jsonl", rs.map(_.asJson.noSpaces).mkString("", "\n", "\n"), createFolders = true)

  private def cacheFile(dir: os.Path): os.Path = dir / "usage-agg-v1.json"

  private def readCacheJson(dir: os.Path): Json = io.circe.parser.parse(os.read(cacheFile(dir))).toOption.get

  private def patchCache(dir: os.Path, f: Json => Json): Unit =
    os.write.over(cacheFile(dir), f(readCacheJson(dir)).spaces2)

  private def sha256(path: os.Path): String =
    MessageDigest.getInstance("SHA-256").digest(os.read.bytes(path)).map(b => f"${b & 0xff}%02x").mkString

  private def otherZone: String =
    if zone.getId == "UTC" then "Asia/Shanghai" else "UTC"

  private val base = local(2026, 12, 31, 20, 0)

  private def sampleRecords: List[LlmUsageRecord] =
    List(
      rec(base, cr = 905),
      rec(base + 60_000, cr = 907, p = "zhipu", m = "m-b", a = "Frontend"),
      rec(base + 2 * MS_H, cr = 0, p = "kimi", m = "m-c", a = "Nebula")
    )

  // ── pure invariants ─────────────────────────────────────────────────────────

  test("costEquivalent is recomputed on the merged totals; per-cell truncation is not additive") {
    val c1 = UsageAggCell("2026-12-31T22", "p", "m", "a", UsageCounters(1L, 1000L, 0L, 905L, 0L))
    val c2 = UsageAggCell("2026-12-31T22", "p", "m", "a", UsageCounters(1L, 1000L, 0L, 907L, 0L))
    val agg = UsageAggCache.build(List(c1, c2), None, None, None, None)
    assertEquals(agg.costEquivalent, (2000L - 1812L) + (1812L * 0.1).toLong)
    val perCell = ((1000L - 905L) + (905L * 0.1).toLong) + ((1000L - 907L) + (907L * 0.1).toLong)
    assert(
      agg.costEquivalent != perCell,
      s"corpus cannot detect the M4 trap: merged=${agg.costEquivalent} per-cell-sum=$perCell"
    )
  }

  test("mergeCells is order independent, additive per key, and sorted by key") {
    val a = List(
      UsageAggCell("2026-12-31T20", "p1", "m", "a", UsageCounters(1L, 10L, 0L, 0L, 0L)),
      UsageAggCell("2026-12-31T21", "p2", "m", "a", UsageCounters(2L, 20L, 0L, 0L, 0L))
    )
    val b = List(
      UsageAggCell("2026-12-31T21", "p2", "m", "a", UsageCounters(3L, 30L, 0L, 0L, 0L)),
      UsageAggCell("2026-12-31T22", "p1", "m", "a", UsageCounters(4L, 40L, 0L, 0L, 0L))
    )
    val ab = UsageAggCache.mergeCells(a, b)
    val ba = UsageAggCache.mergeCells(b, a)
    assertEquals(ab, ba, "cell merge must not depend on the order of the two prefixes")
    assertEquals(ab.map(_.hourKey), List("2026-12-31T20", "2026-12-31T21", "2026-12-31T22"))
    assertEquals(ab(1).counters.count, 5L, "overlapping keys sum their counters")
    assertEquals(ab.map(_.counters.count).sum, 10L, "no record lost or duplicated by the merge")
  }

  test("hour/day key derivation and window classification are consistent") {
    val ts = local(2026, 12, 31, 23, 30)
    val hk = UsageAggCache.hourKey(ts, zone)
    assertEquals(hk, "2026-12-31T23")
    assertEquals(UsageAggCache.dayKey(hk), "2026-12-31")
    val (hs, he) = UsageAggCache.hourBounds(hk, zone)
    assert(hs <= ts && ts < he, "an hour's bounds must contain its own records")
    assertEquals(he - hs, MS_H)
    // A window inside the hour cuts it ⇒ it must be re-read, not taken from the cell.
    val cache = UsageAggCacheFile(
      1,
      zone.getId,
      UsageAggWatermark(0L, 0L, 0L, 0L, "", ""),
      List(UsageAggCell(hk, "p", "m", "a", UsageCounters(1L, 1L, 0L, 0L, 0L))),
      Map(hk -> HourSpan(0L, 10L))
    )
    assertEquals(UsageAggCache.interiorCells(cache, Some(hs + 60_000L), Some(he), zone).size, 0)
    assertEquals(UsageAggCache.edgeHours(cache.hourSpans.keySet, Some(hs + 60_000L), Some(he), zone), List(hk))
    assertEquals(UsageAggCache.interiorCells(cache, Some(hs), Some(he), zone).size, 1)
    assertEquals(UsageAggCache.edgeHours(cache.hourSpans.keySet, Some(hs), Some(he), zone), Nil)
    assertEquals(UsageAggCache.edgeHours(cache.hourSpans.keySet, None, None, zone), Nil)
  }

  // ── V-rules: what may trigger a full rebuild ────────────────────────────────

  test("V1 cache missing → rebuild, and the cache file lands next to the ledger") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    val first = store.aggregate(Some("day"), None, None).unsafeRunSync()
    assertEquals(first, store.aggregateFull(Some("day"), None, None).unsafeRunSync())
    assert(os.exists(cacheFile(dir)), "the cache must be persisted under the store's own directory")
    assertEquals(store.cacheDiagnostics.unsafeRunSync().rebuilds, 1L)
    // Evicting the file forces exactly one more rebuild, with an identical result.
    os.remove(cacheFile(dir))
    assertEquals(store.aggregate(Some("day"), None, None).unsafeRunSync(), first)
  }

  test("V2 schemaVersion mismatch → rebuild") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    val expected = store.aggregate(None, None, None).unsafeRunSync()
    patchCache(dir, j => j.mapObject(_.add("schemaVersion", Json.fromInt(99))))
    assertEquals(store.aggregate(None, None, None).unsafeRunSync(), expected, "an old/new schema must never be trusted")
    assertEquals(readCacheJson(dir).hcursor.get[Int]("schemaVersion").toOption, Some(UsageAggCache.SchemaVersion))
  }

  test("V3 source truncated below the watermark → rebuild, no error") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    store.aggregate(None, None, None).unsafeRunSync()
    val w = store.cacheDiagnostics.unsafeRunSync()
    assert(w.byteOffset > 0)
    // Simulate a rotation/truncation: keep only the first line.
    val line = os.read.lines(dir / "usage-records.jsonl").head
    os.write.over(dir / "usage-records.jsonl", line + "\n")
    assert(os.size(dir / "usage-records.jsonl") < w.byteOffset)
    val after = store.aggregate(None, None, None).unsafeRunSync()
    assertEquals(after, store.aggregateFull(None, None, None).unsafeRunSync())
    assertEquals(after.count, 1)
    assertEquals(store.cacheDiagnostics.unsafeRunSync().byteOffset, os.size(dir / "usage-records.jsonl"))
  }

  test("V4 in-place rewrite at constant size → detected by the 64 KiB digests") {
    val dir = os.temp.dir()
    val corpusA = sampleRecords
    // Same byte length, different provider/agent values → the size probe alone is blind.
    val corpusB = corpusA.map(r => r.copy(provider = r.provider.reverse, agent = r.agent.reverse))
    writeLedger(dir, corpusA)
    val store = new UsageRecordStore(dir)
    store.aggregate(None, None, None).unsafeRunSync()
    val sizeA = os.size(dir / "usage-records.jsonl")
    writeLedger(dir, corpusB)
    assertEquals(
      os.size(dir / "usage-records.jsonl"),
      sizeA,
      "the rewrite must keep the byte length for this test to be meaningful"
    )
    val after = store.aggregate(Some("provider"), None, None).unsafeRunSync()
    assertEquals(
      after,
      store.aggregateFull(Some("provider"), None, None).unsafeRunSync(),
      "a rewritten prefix must not be served from the stale cache"
    )
  }

  test("V5 timezone change → rebuild (day/hour keys are local)") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    val expected = store.aggregate(Some("day"), None, None).unsafeRunSync()
    patchCache(dir, j => j.mapObject(_.add("timezoneId", Json.fromString(otherZone))))
    assertEquals(store.aggregate(Some("day"), None, None).unsafeRunSync(), expected)
    assertEquals(readCacheJson(dir).hcursor.get[String]("timezoneId").toOption, Some(zone.getId))
  }

  test("V6 corrupt cache → quarantined for forensics, request still correct") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    os.write.over(cacheFile(dir), "{\"schemaVersion\": 1, \"cell", createFolders = true)
    val agg = store.aggregate(None, None, None).unsafeRunSync()
    assertEquals(agg, store.aggregateFull(None, None, None).unsafeRunSync())
    val quarantined = os.list(dir).filter(_.last.startsWith("usage-agg-v1.json.corrupt-")).toList
    assertEquals(quarantined.size, 1, s"the corrupt cache must be moved aside, dir=${os.list(dir).map(_.last)}")
    assertEquals(store.cacheDiagnostics.unsafeRunSync().fallbacks, 0L, "corruption must be handled, not escalated")
  }

  test("V7 watermark not on a line boundary → rebuild") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    val expected = store.aggregate(None, None, None).unsafeRunSync()
    patchCache(
      dir,
      j =>
        j.mapObject(
          _.add(
            "watermark",
            j.hcursor.downField("watermark").focus.get.mapObject(_.add("byteOffset", Json.fromLong(3L)))
          )
        )
    )
    assertEquals(store.aggregate(None, None, None).unsafeRunSync(), expected)
    val diag = store.cacheDiagnostics.unsafeRunSync()
    assertEquals(diag.byteOffset, os.size(dir / "usage-records.jsonl"))
  }

  // ── R1/R2 + J-P1/J-P2 ───────────────────────────────────────────────────────

  test("R1/J-P1/J-P2: warm requests neither rewrite the cache nor consume source bytes") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    store.aggregate(None, None, None).unsafeRunSync()
    val before = (sha256(cacheFile(dir)), os.mtime(cacheFile(dir)))
    val hitsBefore = store.cacheDiagnostics.unsafeRunSync().hits
    val fullCallsBefore = store.cacheDiagnostics.unsafeRunSync().fullPathCalls
    val bytesBefore = store.cacheDiagnostics.unsafeRunSync().totalSourceBytes
    assert(bytesBefore > 0, "the cold request must have consumed the ledger once")
    (1 to 20).foreach { _ =>
      store.aggregate(Some("day"), None, None).unsafeRunSync()
      assertEquals(
        store.cacheDiagnostics.unsafeRunSync().totalSourceBytes,
        bytesBefore,
        "a warm request must consume 0 source bytes (J-P2)"
      )
    }
    assertEquals(sha256(cacheFile(dir)), before._1, "the cache content must be unchanged")
    assertEquals(os.mtime(cacheFile(dir)), before._2, "the cache file must not be rewritten")
    assert(store.cacheDiagnostics.unsafeRunSync().hits >= hitsBefore + 20)
    assertEquals(
      store.cacheDiagnostics.unsafeRunSync().fullPathCalls,
      fullCallsBefore,
      "a warm request must not read the whole ledger (this is what an 'always recompute' implementation would trip)"
    )
  }

  test("R2: an append consumes only the appended span and keeps the result exact") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    store.aggregate(None, None, None).unsafeRunSync()
    val wBefore = store.cacheDiagnostics.unsafeRunSync().byteOffset
    val extra = List(rec(base + 3 * MS_H, cr = 1234), rec(base + 3 * MS_H + 60_000, cr = 1235, a = "Coder"))
    val text = extra.map(_.asJson.noSpaces).mkString("", "\n", "\n")
    os.write.append(dir / "usage-records.jsonl", text)
    val inc = store.aggregate(None, None, None).unsafeRunSync()
    val diag = store.cacheDiagnostics.unsafeRunSync()
    assertEquals(
      diag.lastDeltaBytes,
      text.getBytes("UTF-8").length.toLong,
      "the cost must be the delta, not the ledger"
    )
    assertEquals(diag.increments, 1L)
    assert(diag.byteOffset > wBefore)
    assertEquals(inc, store.aggregateFull(None, None, None).unsafeRunSync())
    assertEquals(inc.count, sampleRecords.size + extra.size, "a 2-segment merge must not lose the prefix")
  }

  // ── concurrency: single-flight + the read-path lock fix ─────────────────────

  test("8 concurrent cold requests consume the ledger once (single-flight + read lock)") {
    val dir = os.temp.dir()
    val rs = (0 until 400).toList.map(i => rec(base + i * 60_000L, p = s"p${i % 3}", a = s"a${i % 5}", cr = i % 997))
    writeLedger(dir, rs)
    val ledgerSize = os.size(dir / "usage-records.jsonl")
    val store = new UsageRecordStore(dir)
    val pool = Executors.newFixedThreadPool(8)
    val latch = new CountDownLatch(8)
    val out = new java.util.concurrent.ConcurrentLinkedQueue[UsageAggregate]()
    try
      (0 until 8).foreach { _ =>
        pool.submit(
          new Runnable:
            def run(): Unit =
              try out.add(store.aggregate(Some("provider"), None, None).unsafeRunSync())
              finally latch.countDown()
        )
      }
      assert(latch.await(120, TimeUnit.SECONDS), "concurrent aggregates must complete")
    finally pool.shutdown()
    val diag = store.cacheDiagnostics.unsafeRunSync()
    val reference = store.aggregateFull(Some("provider"), None, None).unsafeRunSync()
    assertEquals(out.size, 8)
    out.forEach(a => assertEquals(a, reference))
    assertEquals(diag.rebuilds, 1L, "8 concurrent callers must trigger exactly one rebuild")
    assertEquals(diag.totalSourceBytes, ledgerSize, "the ledger must be read once, not once per request")
    assertEquals(diag.fullPathCalls, 0L, "no concurrent aggregate may fall back to a whole-ledger read")
  }

  test("appends during concurrent aggregates never yield a wrong or torn result") {
    val dir = os.temp.dir()
    val rs = (0 until 200).toList.map(i => rec(base + i * 60_000L, p = s"p${i % 2}"))
    writeLedger(dir, rs)
    val store = new UsageRecordStore(dir)
    val pool = Executors.newFixedThreadPool(9)
    val latch = new CountDownLatch(9)
    try
      (0 until 8).foreach { k =>
        pool.submit(
          new Runnable:
            def run(): Unit =
              try store.aggregate(Some("day"), None, None).unsafeRunSync()
              finally latch.countDown()
        )
      }
      pool.submit(
        new Runnable:
          def run(): Unit =
            try (0 until 50).foreach(i => store.record(rec(base + (200 + i) * 60_000L)).unsafeRunSync())
            finally latch.countDown()
      )
      assert(latch.await(120, TimeUnit.SECONDS), "mixed read/write load must complete")
    finally pool.shutdown()
    end try
    // Final state must equal a from-scratch read of the (now larger) ledger.
    val direct = store.aggregateFull(None, None, None).unsafeRunSync()
    assertEquals(store.aggregate(None, None, None).unsafeRunSync(), direct)
    assertEquals(direct.count, 250)
  }

  // ── failure direction: slow, never wrong ────────────────────────────────────

  test("an unwritable directory degrades to a correct in-memory result (no 5xx)") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    val expected = store.aggregate(None, None, None).unsafeRunSync()
    os.remove(cacheFile(dir))
    val perms = Files.getPosixFilePermissions(dir.toNIO)
    try
      Files.setPosixFilePermissions(dir.toNIO, PosixFilePermissions.fromString("r-xr-xr-x"))
      val agg = store.aggregate(None, None, None).unsafeRunSync()
      assertEquals(agg, expected, "a failed cache write must not change the answer")
      assertEquals(
        store.cacheDiagnostics.unsafeRunSync().fallbacks,
        0L,
        "the cache layer must absorb the write failure"
      )
    finally Files.setPosixFilePermissions(dir.toNIO, perms)
  }

  test("the cache lives inside the store directory only (zero policy surface)") {
    val dir = os.temp.dir()
    writeLedger(dir, sampleRecords)
    val store = new UsageRecordStore(dir)
    store.aggregate(None, None, None).unsafeRunSync()
    val diag = store.cacheDiagnostics.unsafeRunSync()
    assertEquals(diag.cachePath, (dir / "usage-agg-v1.json").toString)
    val unexpected = os.list(dir).filter(p => !Set("usage-records.jsonl", "usage-agg-v1.json").contains(p.last)).toList
    assertEquals(unexpected.map(_.last), List.empty[String], "nothing but the ledger and its cache may be created")
  }

end UsageAggCacheSpec
