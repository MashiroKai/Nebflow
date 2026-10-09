package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import nebflow.core.AtomicJson

import scala.collection.mutable

/**
 * perf-481 A3 instrument — exercises the SHIPPED [[ChainLedgerStore]] write path
 * (`setChainControl` → `persist`) against a realistic 14 MB checkpoint, and
 * prints the readings the A3 criteria are stated in, DECOMPOSED so the cost of
 * each layer is visible rather than averaged into one number.
 *
 * WHY A REALISTIC SIZE: the whole point of A3 is that a state change used to cost
 * a full 13.4 MB rewrite. A probe against a 2 KB ledger would look fast for the
 * wrong reason. This builds a checkpoint with the measured production shape
 * (≈39 000 `rounds` manifests + a handful of hot entries) and times the append
 * path on top of it.
 *
 * WHY THE DECOMPOSITION: the rework added `fsync` to the append (durability), and
 * an fsync is not free. Reporting one "append p95" would hide whether the time is
 * the write, the fsync, or the record derivation. The probe therefore measures
 * three layers on the same journal generation:
 *   · the shipped path (`setChainControl`, durable append);
 *   · the bare durable append (`appendSyncDurable`) — write + fsync, no derivation;
 *   · the bare plain append (`appendSync`) — write only, no fsync.
 * The gaps attribute the cost. A rotation is a full checkpoint write by
 * construction, so it is reported SEPARATELY (the verifier's p99 128 ms finding).
 *
 * RUN (one command, foreground, self-exiting; JDK 23 pinned):
 *   sbt --batch 'Test/runMain nebflow.core.project.A3LedgerProbe --n 250 --paced 30 --rounds 39000'
 */
object A3LedgerProbe:

  private var argv: Array[String] = Array.empty
  private def arg(name: String, dflt: String): String =
    val i = argv.indexOf("--" + name)
    if i >= 0 && i + 1 < argv.length then argv(i + 1) else dflt

  private def pct(xs: Seq[Double], p: Double): Double =
    if xs.isEmpty then Double.NaN
    else
      val s = xs.sorted
      val idx = math.min(s.size - 1, math.max(0, math.ceil(p / 100.0 * s.size).toInt - 1))
      s(idx)

  private def line(label: String, xs: Seq[Double]): String =
    if xs.isEmpty then f"    $label%-30s (no samples)"
    else f"    $label%-30s n=${xs.size}%-5d p50=${pct(xs, 50)}%8.3f p95=${pct(xs, 95)}%8.3f p99=${pct(xs, 99)}%8.3f max=${xs.max}%8.3f ms"

  private def t0: Long = 1_700_000_000_000L

  /** Raw append + `force(false)` — the data-only fsync (no F_FULLFSYNC). */
  private def forceDataOnly(j: os.Path, content: String): Unit =
    val ch = java.nio.channels.FileChannel.open(
      j.toNIO,
      java.nio.file.StandardOpenOption.CREATE,
      java.nio.file.StandardOpenOption.WRITE,
      java.nio.file.StandardOpenOption.APPEND
    )
    try
      val buf = java.nio.ByteBuffer.wrap((content + "\n").getBytes("UTF-8"))
      while buf.hasRemaining do ch.write(buf)
      ch.force(false)
    finally ch.close()

  /** A manifest of the shape the production ledger carries (≈120 B on disk). */
  private def round(i: Int): ChainLedger.RoundManifest =
    ChainLedger.RoundManifest(
      round = i,
      at = t0 + i * 1000L,
      kind = ChainLedger.RoundCompact,
      file = s"round-$i.json",
      movedEntries = 12,
      movedAliases = 1,
      movedMembers = 144,
      hotBefore = ChainLedger.Totals(1000, 20, 12000, 5000L),
      hotAfter = ChainLedger.Totals(988, 19, 11856, 4900L),
      digest = "ab" * 32,
      tick = i.toLong
    )

  /** A checkpoint of the measured production bulk: `rounds` dominates (≈14 MB). */
  private def bigState(rounds: Int, project: String): ChainLedger.State =
    ChainLedger.State(
      project = project,
      updatedAt = t0,
      entries = (1 to 8).map(i => s"chain-$i" -> ChainLedger.Entry(s"chain-$i", s"anchor-$i", t0)).toMap,
      rounds = (1 to rounds).map(round).toList
    )

  def main(argvIn: Array[String]): Unit =
    argv = argvIn
    val roundsCount = arg("rounds", "39000").toInt
    val bursts = arg("n", "250").toInt
    val paced = arg("paced", "30").toInt
    val gapMs = arg("gap", "1000").toLong

    val dir = os.temp.dir(prefix = "p481-a3-")
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName

    println("== perf-481 A3 probe (shipped ChainLedgerStore, incremental write) ==")
    println(s"dir=$dir rounds=$roundsCount burst_n=$bursts paced_n=$paced gap_ms=$gapMs")

    val seed = bigState(roundsCount, "probe")
    val seedText = seed.asJson.noSpaces
    os.write.over(path, seedText, createFolders = true)
    println(f"    checkpoint_bytes=${seedText.getBytes("UTF-8").length}%,d  rounds=${seed.rounds.size}")

    val store = ChainLedgerStore.open("probe", path, arch).unsafeRunSync()

    // ── 1. sustained burst: shipped path, split append vs rotation ───────────
    // One REUSED chain id (the production shape: the same chain is paused / resumed
    // repeatedly) so the record stays constant-size and the reading is not skewed by
    // an ever-growing `pausedChains` map.
    val burstAll = mutable.ListBuffer.empty[Double]
    val burstAppend = mutable.ListBuffer.empty[Double]
    val burstRotate = mutable.ListBuffer.empty[Double]
    var i = 0
    while i < bursts do
      val status = if i % 2 == 0 then ChainLedger.StatusPaused else ChainLedger.StatusActive
      val before = AtomicJson.noteBytes(path)
      val t = System.nanoTime()
      store.setChainControl("probe-chain", status, t0 + i).unsafeRunSync()
      val dt = (System.nanoTime() - t) / 1e6
      val after = AtomicJson.noteBytes(path)
      burstAll += dt
      // A rotation is observable as "journal shrank" (folding truncates it).
      if after < before then burstRotate += dt else burstAppend += dt
      i += 1

    println("[1] sustained burst (gap=0) — shipped path, append vs rotation split")
    println(line("all writes", burstAll.toList))
    println(line("append only", burstAppend.toList))
    println(line("rotation (full checkpoint write)", burstRotate.toList))

    // ── 2. layer decomposition on the same journal generation ───────────────
    // Same record bytes at each layer, so the gaps attribute the cost:
    //   derivation (record build + encode + IO.blocking hop) and the fsync.
    val prologue = """{"base":0,"state":{"v":1,"project":"probe","rounds":[]}}"""
    val plain = mutable.ListBuffer.empty[Double]
    val durable = mutable.ListBuffer.empty[Double]
    val durableDataOnly = mutable.ListBuffer.empty[Double]
    i = 0
    while i < bursts do
      var t = System.nanoTime()
      AtomicJson.appendSync(path, prologue)
      plain += (System.nanoTime() - t) / 1e6
      t = System.nanoTime()
      AtomicJson.appendSyncDurable(path, prologue)
      durable += (System.nanoTime() - t) / 1e6
      // Raw NIO with force(false) = force data with plain fsync (no F_FULLFSYNC):
      // the middle option between "no durability" and "full drive-cache flush".
      t = System.nanoTime()
      forceDataOnly(AtomicJson.journalPathOf(path), prologue)
      durableDataOnly += (System.nanoTime() - t) / 1e6
      i += 1

    println("[2] layer decomposition (identical record bytes, same journal generation)")
    println(line("bare appendSync (no fsync)", plain.toList))
    println(line("force(false): data fsync only", durableDataOnly.toList))
    println(line("appendSyncDurable: force(true) [F_FULLFSYNC]", durable.toList))
    println(f"    derived: the shipped-path append minus bare durable append = est. record derivation cost")

    // ── 3. paced sequence (the measured 1.88 writes/min shape) ──────────────
    val pacedAll = mutable.ListBuffer.empty[Double]
    val pacedRot = mutable.ListBuffer.empty[Double]
    var minuteBytes = 0L
    i = 0
    while i < paced do
      val status = if i % 2 == 0 then ChainLedger.StatusPaused else ChainLedger.StatusActive
      val before = AtomicJson.noteBytes(path)
      val t = System.nanoTime()
      store.setChainControl("probe-chain", status, t0 + 100_000 + i).unsafeRunSync()
      val dt = (System.nanoTime() - t) / 1e6
      val after = AtomicJson.noteBytes(path)
      if after < before then pacedRot += dt else minuteBytes += (after - before)
      pacedAll += dt
      if gapMs > 0 then Thread.sleep(gapMs)
      i += 1

    println(s"[3] paced n=$paced gap=${gapMs}ms")
    println(line("all writes", pacedAll.toList))
    println(line("rotation only", pacedRot.toList))
    println(
      s"    journal_bytes_growth_per_write=${minuteBytes / math.max(1, paced)} (criterion: <= 3 MB/min = ${3L * 1024 * 1024} B)"
    )

    // ── 4. crash-recovery legs ──────────────────────────────────────────────
    println("[4] crash-recovery semantics")
    val beforePause = store.snapshot.unsafeRunSync().pausedChains.keySet
    store.setChainControl("crash-chain", ChainLedger.StatusPaused, t0 + 900_000).unsafeRunSync()
    val afterAppendKeys = store.snapshot.unsafeRunSync().pausedChains.keySet
    val reopenClean = ChainLedgerStore.open("probe", path, arch).unsafeRunSync().snapshot.unsafeRunSync()
    println(s"    BEFORE_APPEND          paused_chains=${beforePause.size} crash_chain_present=${beforePause.contains("crash-chain")}")
    println(s"    AFTER_APPEND           paused_chains=${afterAppendKeys.size} crash_chain_present=${afterAppendKeys.contains("crash-chain")}")
    println(
      s"    REOPEN_CLEAN           recovered=${reopenClean.pausedChains.contains("crash-chain")} " +
        s"(append acknowledged ⇒ must survive a reopen)"
    )

    // Leg B: DELETE the journal — models an append that never reached the device.
    // The durable append makes that impossible for an ACKNOWLEDGED write, so this
    // leg now models deliberate journal deletion / loss of the whole file rather
    // than a silent crash loss. It also shows the fold boundary: state folded into
    // the checkpoint before the deletion is still there.
    val j = AtomicJson.journalPathOf(path)
    val journalBytes = if os.exists(j) then os.size(j) else 0L
    if os.exists(j) then os.remove(j)
    val withoutJournal = ChainLedgerStore.open("probe", path, arch).unsafeRunSync().snapshot.unsafeRunSync()
    println(s"    journal_deleted bytes=$journalBytes")
    println(
      s"    REOPEN_WITHOUT_JOURNAL  paused_chains=${withoutJournal.pausedChains.size} " +
        s"checkpoint_face_intact=${withoutJournal.rounds.size == roundsCount}"
    )

    println(s"    dir_kept_for_inspection=$dir")
    println("== end (exit 0) ==")
    sys.exit(0)
  end main
end A3LedgerProbe