package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

class UsageRecordStoreSpec extends FunSuite:

  private def store(dir: os.Path) = new UsageRecordStore(dir)

  private def record(
    ts: Long,
    provider: String = "deepseek",
    model: String = "deepseek-v4-flash",
    agent: String = "Backend",
    input: Int = 1000,
    output: Int = 100,
    cacheRead: Int = 900,
    cacheWrite: Int = 0
  ): LlmUsageRecord =
    LlmUsageRecord(ts, provider, model, agent, Some("s1"), input, output, cacheRead, cacheWrite)

  test("record then loadAll round-trips all fields") {
    val dir = os.temp.dir()
    val s = store(dir)
    val r = record(1000L, "107", "glm-5.2-107", "Nebula", 500, 50, 450, 10)
    s.record(r).unsafeRunSync()
    val loaded = s.loadAll().unsafeRunSync()
    assertEquals(loaded.size, 1)
    assertEquals(loaded.head, r)
  }

  test("aggregate totals sum all token buckets and cost equivalent") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, input = 1000, output = 100, cacheRead = 900)).unsafeRunSync()
    s.record(record(2000L, provider = "kimi", model = "k3-256k", agent = "Frontend", input = 2000, output = 200, cacheRead = 0)).unsafeRunSync()
    val agg = s.aggregate(None, None, None).unsafeRunSync()
    assertEquals(agg.count, 2)
    assertEquals(agg.totalInput, 3000L)
    assertEquals(agg.totalOutput, 300L)
    assertEquals(agg.totalCacheRead, 900L)
    assertEquals(agg.totalCacheWrite, 0L)
    // cost = input + cacheRead*0.1 = 3000 + 90
    assertEquals(agg.costEquivalent, 3090L)
    assertEquals(agg.buckets, Nil) // no dim = totals only
  }

  test("aggregate by provider groups correctly") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, provider = "deepseek", input = 1000)).unsafeRunSync()
    s.record(record(2000L, provider = "deepseek", input = 2000)).unsafeRunSync()
    s.record(record(3000L, provider = "kimi", input = 4000)).unsafeRunSync()
    s.record(record(4000L, provider = "107", input = 8000)).unsafeRunSync()
    val agg = s.aggregate(Some("provider"), None, None).unsafeRunSync()
    assertEquals(agg.count, 4)
    assertEquals(agg.totalInput, 15000L)
    val buckets = agg.buckets.map(b => b.key -> b.inputTokens).toMap
    assertEquals(buckets("deepseek"), 3000L)
    assertEquals(buckets("kimi"), 4000L)
    assertEquals(buckets("107"), 8000L)
    // sorted by key
    assertEquals(agg.buckets.map(_.key), List("107", "deepseek", "kimi"))
  }

  test("aggregate by model, agent groups correctly") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, model = "a", agent = "X", input = 100)).unsafeRunSync()
    s.record(record(2000L, model = "a", agent = "Y", input = 200)).unsafeRunSync()
    s.record(record(3000L, model = "b", agent = "X", input = 300)).unsafeRunSync()
    val byModel = s.aggregate(Some("model"), None, None).unsafeRunSync()
    assertEquals(byModel.buckets.map(b => b.key -> b.inputTokens).toMap, Map("a" -> 300L, "b" -> 300L))
    val byAgent = s.aggregate(Some("agent"), None, None).unsafeRunSync()
    assertEquals(byAgent.buckets.map(b => b.key -> b.inputTokens).toMap, Map("X" -> 400L, "Y" -> 200L))
  }

  test("aggregate from/to filters by timestamp (inclusive lower, exclusive upper)") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, input = 100)).unsafeRunSync()
    s.record(record(2000L, input = 200)).unsafeRunSync()
    s.record(record(3000L, input = 300)).unsafeRunSync()
    val agg = s.aggregate(None, Some(2000L), Some(3000L)).unsafeRunSync()
    assertEquals(agg.count, 1)
    assertEquals(agg.totalInput, 200L)
    // from inclusive
    val agg2 = s.aggregate(None, Some(1000L), Some(3000L)).unsafeRunSync()
    assertEquals(agg2.count, 2)
    assertEquals(agg2.totalInput, 300L)
  }

  test("unknown dim falls back to totals only (backward safe)") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, input = 100)).unsafeRunSync()
    val agg = s.aggregate(Some("bogus"), None, None).unsafeRunSync()
    assertEquals(agg.count, 1)
    assertEquals(agg.totalInput, 100L)
    assertEquals(agg.buckets, Nil)
  }

  test("provider filter narrows totals and buckets (D1: filter before group)") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, provider = "107", model = "m-a", agent = "Nebula", input = 100, output = 10, cacheRead = 90)).unsafeRunSync()
    s.record(record(2000L, provider = "107", model = "m-b", agent = "Backend", input = 200, output = 20, cacheRead = 0)).unsafeRunSync()
    s.record(record(3000L, provider = "deepseek", model = "m-a", agent = "Nebula", input = 400, output = 40, cacheRead = 0)).unsafeRunSync()
    // dim=day + provider=107: buckets only cover provider 107's records
    val agg = s.aggregate(Some("day"), None, None, provider = Some("107")).unsafeRunSync()
    assertEquals(agg.count, 2)
    assertEquals(agg.totalInput, 300L)
    assertEquals(agg.totalOutput, 30L)
    assertEquals(agg.totalCacheRead, 90L)
    assertEquals(agg.buckets.map(_.inputTokens).sum, 300L, "buckets aggregate the filtered set")
    // same-dim grouping on the filtered field collapses to one bucket
    val byProvider = s.aggregate(Some("provider"), None, None, provider = Some("107")).unsafeRunSync()
    assertEquals(byProvider.buckets.map(_.key), List("107"))
    // no filter = full set (backward compatible defaults)
    val unfiltered = s.aggregate(Some("provider"), None, None).unsafeRunSync()
    assertEquals(unfiltered.count, 3)
    assertEquals(unfiltered.totalInput, 700L)
  }

  test("filter is orthogonal to dim: dim=agent within provider subset") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, provider = "107", agent = "Nebula", input = 100)).unsafeRunSync()
    s.record(record(2000L, provider = "107", agent = "Backend", input = 200)).unsafeRunSync()
    s.record(record(3000L, provider = "107", agent = "Nebula", input = 300)).unsafeRunSync()
    s.record(record(4000L, provider = "kimi", agent = "Nebula", input = 4000)).unsafeRunSync()
    val agg = s.aggregate(Some("agent"), None, None, provider = Some("107")).unsafeRunSync()
    val buckets = agg.buckets.map(b => b.key -> b.inputTokens).toMap
    assertEquals(buckets, Map("Nebula" -> 400L, "Backend" -> 200L), "kimi records excluded before grouping")
  }

  test("model and agent filters, and combined filters") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, provider = "107", model = "m-a", agent = "Nebula", input = 100)).unsafeRunSync()
    s.record(record(2000L, provider = "107", model = "m-b", agent = "Nebula", input = 200)).unsafeRunSync()
    s.record(record(3000L, provider = "kimi", model = "m-a", agent = "Backend", input = 400)).unsafeRunSync()
    val byModel = s.aggregate(None, None, None, model = Some("m-a")).unsafeRunSync()
    assertEquals(byModel.count, 2)
    assertEquals(byModel.totalInput, 500L)
    val byAgent = s.aggregate(None, None, None, agent = Some("Backend")).unsafeRunSync()
    assertEquals(byAgent.count, 1)
    assertEquals(byAgent.totalInput, 400L)
    // combined: all three must match (AND)
    val combined = s
      .aggregate(None, None, None, provider = Some("107"), model = Some("m-a"), agent = Some("Nebula"))
      .unsafeRunSync()
    assertEquals(combined.count, 1)
    assertEquals(combined.totalInput, 100L)
  }

  test("non-matching filter yields empty aggregate, not an error") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, input = 100)).unsafeRunSync()
    val agg = s.aggregate(Some("provider"), None, None, provider = Some("nonexistent")).unsafeRunSync()
    assertEquals(agg.count, 0)
    assertEquals(agg.totalInput, 0L)
    assertEquals(agg.buckets, Nil)
  }

  test("provider filter composes with from/to boundary (inclusive lower, exclusive upper)") {
    val dir = os.temp.dir()
    val s = store(dir)
    s.record(record(1000L, provider = "107", input = 100)).unsafeRunSync()
    s.record(record(2000L, provider = "107", input = 200)).unsafeRunSync()
    s.record(record(3000L, provider = "107", input = 400)).unsafeRunSync()
    s.record(record(2000L, provider = "kimi", input = 8000)).unsafeRunSync()
    // [2000, 3000): includes ts=2000 (provider 107), excludes ts=3000 and kimi
    val agg = s.aggregate(None, Some(2000L), Some(3000L), provider = Some("107")).unsafeRunSync()
    assertEquals(agg.count, 1)
    assertEquals(agg.totalInput, 200L)
  }

  test("concurrent appends do not lose records or interleave lines") {
    val dir = os.temp.dir()
    val s = store(dir)
    val n = 200
    val pool = Executors.newFixedThreadPool(8)
    val latch = new CountDownLatch(n)
    try
      val futures: List[java.util.concurrent.Future[?]] = (0 until n).map { i =>
        val runnable: Runnable = () => {
          try s.record(record(i.toLong * 10L, provider = s"p$i", agent = s"a$i", input = i)).unsafeRunSync()
          finally latch.countDown()
        }
        pool.submit(runnable)
      }.toList
      assert(latch.await(30, TimeUnit.SECONDS), "records should complete within 30s")
      futures.foreach(_.get())
    finally pool.shutdown()
    val loaded = s.loadAll().unsafeRunSync()
    assertEquals(loaded.size, n, s"all $n records persisted")
    val providers = loaded.map(_.provider).toSet
    assertEquals(providers.size, n, "no provider lost/duplicated")
  }

  test("hour and day dimensions produce time buckets") {
    val dir = os.temp.dir()
    val s = store(dir)
    // Fixed epoch millis for known local times: 2026-08-18 00:00 local is timezone-dependent;
    // use two timestamps 1 hour apart from a base.
    val base = 1787054400000L // arbitrary
    s.record(record(base, input = 100)).unsafeRunSync()
    s.record(record(base + 3600_000L, input = 200)).unsafeRunSync()
    s.record(record(base + 2 * 3600_000L, input = 300)).unsafeRunSync()
    val byHour = s.aggregate(Some("hour"), None, None).unsafeRunSync()
    assertEquals(byHour.count, 3)
    assertEquals(byHour.buckets.size, 3, "three distinct hour buckets")
    assertEquals(byHour.buckets.map(_.inputTokens).sum, 600L)
    val byDay = s.aggregate(Some("day"), None, None).unsafeRunSync()
    assertEquals(byDay.buckets.size, 1, "all three records in one local day (1h apart)")
    assertEquals(byDay.buckets.head.inputTokens, 600L)
  }

  test("lastActivityMs: 0 for unknown agent, updates after record (cold-start routing)"):
    val s = store(os.temp.dir())
    assertEquals(s.lastActivityMs("Backend"), 0L, "no records yet → treated as idle")
    val ts = 1787054400000L
    s.record(record(ts, input = 10, agent = "Backend")).unsafeRunSync()
    assertEquals(s.lastActivityMs("Backend"), ts)
    assertEquals(s.lastActivityMs("Other"), 0L, "different agent unaffected")
    // Later record bumps the timestamp
    s.record(record(ts + 60_000L, input = 20, agent = "Backend")).unsafeRunSync()
    assertEquals(s.lastActivityMs("Backend"), ts + 60_000L)

end UsageRecordStoreSpec
