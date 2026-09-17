package nebflow.core

import cats.effect.unsafe.implicits.global
import io.circe.Json

import java.time.{LocalDateTime, ZoneId}
import scala.collection.mutable.ListBuffer

/**
 * In-process bench for the token-panel aggregate path — the `tokenpanel-incremental`
 * before/after readings.
 *
 * Both legs run in the same JVM, on the same corpora, with the same query set:
 *   - **after**  = `aggregate` (cache-backed incremental path, what the endpoint serves);
 *   - **before** = `aggregateFull` (today's whole-ledger recompute, kept verbatim as the
 *     reference implementation).
 * That is the cleanest "same machine, same window, same method" comparison available:
 * both legs are the *actual code paths*, not a re-implementation.
 *
 * Readings: cold first request (one full scan — the deployment cost), warm request
 * latency (min/median/p95/max over N rounds, frontend-shaped query mix), delta-append
 * cost and bytes consumed, plus the per-corpus source bytes/cells so the
 * `t(2N)/t(N)` criterion (J-P4) can be evaluated on the warm path and contrasted with
 * the reference path.
 *
 * Usage: `sbt "Test/runMain nebflow.core.UsageAggBench --out <json> --corpus name=path ..."`
 */
object UsageAggBench:

  private val zone: ZoneId = ZoneId.systemDefault()
  private val MS_H = 3600_000L
  private val MS_D = 24 * MS_H

  private final case class Query(label: String, dim: Option[String], from: Option[Long], to: Option[Long])

  private def median(xs: List[Double]): Double =
    if xs.isEmpty then 0.0
    else
      val s = xs.sorted
      if s.size % 2 == 1 then s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2.0

  private def p95(xs: List[Double]): Double =
    if xs.isEmpty then 0.0 else xs.sorted(math.min(xs.size - 1, (xs.size * 0.95).toInt))

  private def localStartOfDay(ts: Long): Long =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), zone).toLocalDate.atStartOfDay(zone).toInstant.toEpochMilli

  private def localStartOfYear(ts: Long): Long =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), zone).toLocalDate.withDayOfYear(1).atStartOfDay(zone).toInstant.toEpochMilli

  /** The query mix the dashboard actually issues (7 summary requests + 1 heatmap). */
  private def queryMix(maxTs: Long): List[Query] =
    val today = localStartOfDay(maxTs)
    val yearStart = localStartOfYear(maxTs)
    List(
      Query("totals/year", None, Some(yearStart), Some(today + MS_D)),
      Query("hour/today", Some("hour"), Some(today), Some(today + MS_D)),
      Query("hour/yesterday", Some("hour"), Some(today - MS_D), Some(today)),
      Query("totals/week", None, Some(today - 6 * MS_D), Some(today + MS_D)),
      Query("totals/lastweek", None, Some(today - 13 * MS_D), Some(today - 6 * MS_D)),
      Query("agent/year", Some("agent"), Some(yearStart), Some(today + MS_D)),
      Query("model/year", Some("model"), Some(yearStart), Some(today + MS_D)),
      Query("day/year", Some("day"), Some(yearStart), Some(today + MS_D)),
      Query("day/unaligned", Some("day"), Some(today - 12 * MS_D + 12345L), Some(today - 30 * 60_000L))
    )

  private def timed[A](body: => A): (A, Double) =
    val t0 = System.nanoTime()
    val a = body
    (a, (System.nanoTime() - t0) / 1e6)

  private def arg(args: Array[String], name: String): Option[String] =
    args.sliding(2).collectFirst { case Array(k, v) if k == name => v }

  private def args(args: Array[String], name: String): List[String] =
    args.sliding(2).collect { case Array(k, v) if k == name => v }.toList

  def main(argv: Array[String]): Unit =
    val warmRounds = arg(argv, "--warm").map(_.toInt).getOrElse(20)
    val prewarm = arg(argv, "--prewarm").map(_.toInt).getOrElse(3)
    val refReps = arg(argv, "--ref-reps").map(_.toInt).getOrElse(3)
    val appendRows = arg(argv, "--append-rows").map(_.toInt).getOrElse(2000)
    val out = arg(argv, "--out").getOrElse("/tmp/usage-agg-bench.json")
    val corpora = args(argv, "--corpus").flatMap { e =>
      e.split("=", 2) match
        case Array(n, p) => Some((n, os.Path(p, os.pwd)))
        case _ => None
    }
    println(s"[bench] corpora=${corpora.map(_._1).mkString(",")} warm=$warmRounds prewarm=$prewarm refReps=$refReps append=$appendRows")

    val corpusResults = ListBuffer.empty[Json]

    corpora.foreach { case (name, src) =>
      val dir = os.Path(s"/tmp/nb-tp-bench-$name", os.pwd)
      if os.exists(dir) then os.remove.all(dir)
      os.makeDir.all(dir)
      os.copy(src, dir / "usage-records.jsonl")
      val ledgerSize = os.size(dir / "usage-records.jsonl")
      val store = new UsageRecordStore(dir)

      // ── cold: the first request after deploy pays one whole-ledger scan ──
      val (_, coldMs) = timed(store.aggregate(None, None, None).unsafeRunSync())
      val coldDiag = store.cacheDiagnostics.unsafeRunSync()
      val maxTs = coldDiag.maxTimestamp
      val shapes = queryMix(maxTs)
      println(s"[bench] $name: cold=${f"$coldMs%.1f"} ms cells=${coldDiag.cellCount} hours=${coldDiag.hourCount} rebuilds=${coldDiag.rebuilds} rows=${coldDiag.lineCount}")

      // ── warm incremental: the steady state the dashboard lives in ──
      (1 to prewarm).foreach(_ => shapes.foreach(q => store.aggregate(q.dim, q.from, q.to).unsafeRunSync()))
      val incTimes = ListBuffer.empty[Double]
      val incBytes = ListBuffer.empty[Long]
      val perShape = scala.collection.mutable.LinkedHashMap.empty[String, ListBuffer[Double]]
      (1 to warmRounds).foreach { _ =>
        shapes.foreach { q =>
          val (_, ms) = timed(store.aggregate(q.dim, q.from, q.to).unsafeRunSync())
          val d = store.cacheDiagnostics.unsafeRunSync()
          incTimes += ms
          incBytes += d.lastDeltaBytes
          perShape.getOrElseUpdate(q.label, ListBuffer.empty) += ms
        }
      }
      val warmDiag = store.cacheDiagnostics.unsafeRunSync()
      println(s"[bench] $name: warm median=${f"${median(incTimes.toList)}%.3f"} ms p95=${f"${p95(incTimes.toList)}%.3f"} ms max=${f"${incTimes.max}%.3f"} ms consumedBytes=${incBytes.sum}")

      // ── delta: append new rows, then one request must cost only the delta ──
      val extra = (1 to appendRows).toList.map { i =>
        LlmUsageRecord(maxTs + i * 1000L, "deepseek", "deepseek-v4-flash", "Backend", Some("bench"), 1000, 100, 900, 0)
      }
      val extraText = extra.map(_.asJson.noSpaces).mkString("", "\n", "\n")
      os.write.append(dir / "usage-records.jsonl", extraText)
      val (deltaAgg, deltaMs) = timed(store.aggregate(Some("day"), None, None).unsafeRunSync())
      val deltaDiag = store.cacheDiagnostics.unsafeRunSync()
      val newBytes = os.size(dir / "usage-records.jsonl") - ledgerSize
      println(s"[bench] $name: delta=${f"$deltaMs%.1f"} ms consumed=${deltaDiag.lastDeltaBytes} B (appended $newBytes B) count=${deltaAgg.count}")

      // ── before: the same corpus through the reference whole-ledger path ──
      val refTimes = ListBuffer.empty[Double]
      (1 to refReps).foreach(_ => shapes.foreach(q => refTimes += timed(store.aggregateFull(q.dim, q.from, q.to).unsafeRunSync())._2))
      println(s"[bench] $name: reference (full recompute) median=${f"${median(refTimes.toList)}%.1f"} ms p95=${f"${p95(refTimes.toList)}%.1f"} ms")

      corpusResults += Json.obj(
        "corpus" -> Json.fromString(name),
        "source" -> Json.fromString(src.toString),
        "bytes" -> Json.fromLong(ledgerSize),
        "rows" -> Json.fromLong(os.read.lines(dir / "usage-records.jsonl").size.toLong),
        "cells" -> Json.fromInt(coldDiag.cellCount),
        "hours" -> Json.fromInt(coldDiag.hourCount),
        "cold_first_request_ms" -> Json.fromDoubleOrNull(coldMs),
        "warm_incremental_ms" -> Json.obj(
          "min" -> Json.fromDoubleOrNull(incTimes.min),
          "median" -> Json.fromDoubleOrNull(median(incTimes.toList)),
          "p95" -> Json.fromDoubleOrNull(p95(incTimes.toList)),
          "max" -> Json.fromDoubleOrNull(incTimes.max)
        ),
        "warm_reference_ms" -> Json.obj(
          "min" -> Json.fromDoubleOrNull(refTimes.min),
          "median" -> Json.fromDoubleOrNull(median(refTimes.toList)),
          "p95" -> Json.fromDoubleOrNull(p95(refTimes.toList)),
          "max" -> Json.fromDoubleOrNull(refTimes.max)
        ),
        "warm_per_shape_ms" -> Json.obj(perShape.toList.map { case (k, v) => k -> Json.fromDoubleOrNull(median(v.toList)) }*),
        "warm_consumed_source_bytes_total" -> Json.fromLong(incBytes.sum),
        "delta_append" -> Json.obj(
          "appended_bytes" -> Json.fromLong(newBytes),
          "consumed_bytes" -> Json.fromLong(deltaDiag.lastDeltaBytes),
          "request_ms" -> Json.fromDoubleOrNull(deltaMs),
          "increments" -> Json.fromLong(deltaDiag.increments),
          "correct_count_after_delta" -> Json.fromInt(deltaAgg.count)
        ),
        "reference_after_delta" -> Json.fromInt(store.aggregateFull(Some("day"), None, None).unsafeRunSync().count),
        "diagnostics" -> Json.obj(
          "rebuilds" -> Json.fromLong(deltaDiag.rebuilds),
          "increments" -> Json.fromLong(deltaDiag.increments),
          "hits" -> Json.fromLong(deltaDiag.hits),
          "fallbacks" -> Json.fromLong(deltaDiag.fallbacks),
          "full_path_calls" -> Json.fromLong(deltaDiag.fullPathCalls),
          "full_path_bytes" -> Json.fromLong(deltaDiag.fullPathBytes),
          "total_source_bytes" -> Json.fromLong(deltaDiag.totalSourceBytes)
        )
      )
    }

    // ── J-P4: 2x data must not double the warm request cost ──
    def find(n: String): Option[Json] =
      corpusResults.toList.find(_.hcursor.get[String]("corpus").toOption.contains(n))
    def warmMedian(j: Json): Option[Double] = j.hcursor.downField("warm_incremental_ms").get[Double]("median").toOption
    def refMedian(j: Json): Option[Double] = j.hcursor.downField("warm_reference_ms").get[Double]("median").toOption

    val ratios = for
      a <- find("340k")
      b <- find("680k")
      wa <- warmMedian(a)
      wb <- warmMedian(b)
      ra <- refMedian(a)
      rb <- refMedian(b)
    yield Json.obj(
      "pair" -> Json.fromString("340k → 680k (density doubling, same span)"),
      "incremental_ratio" -> Json.fromDoubleOrNull(if wa > 0 then wb / wa else 0.0),
      "reference_ratio" -> Json.fromDoubleOrNull(if ra > 0 then rb / ra else 0.0),
      "threshold" -> Json.fromDoubleOrNull(1.5),
      "incremental_pass" -> Json.fromBoolean(wa > 0 && wb / wa <= 1.5),
      "reference_pass" -> Json.fromBoolean(ra > 0 && rb / ra <= 1.5)
    )

    val json = Json.obj(
      "batch" -> Json.fromString("tokenpanel-incremental"),
      "zone" -> Json.fromString(zone.getId),
      "warm_rounds" -> Json.fromInt(warmRounds),
      "prewarm_rounds" -> Json.fromInt(prewarm),
      "reference_reps" -> Json.fromInt(refReps),
      "corpora" -> Json.arr(corpusResults.toList*),
      "J_P4" -> ratios.getOrElse(Json.Null)
    )
    val p = os.Path(out, os.pwd)
    os.makeDir.all(p / os.up)
    os.write.over(p, json.spaces2)
    println(s"[bench] wrote $p")
    ratios.foreach(r => println(s"[bench] J-P4: ${r.noSpaces}"))

end UsageAggBench
