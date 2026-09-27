package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.UiMessage

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * chain-uicache-lru (2026-09-25): `SessionStore`'s UI cache used to be an
 * unbounded `Map` with zero runtime eviction — every session ever viewed stayed
 * resident (~10-15MB each) and long uptimes OOM'd (two traced incidents; rescue
 * report residual risk #1). These specs pin the replacement contract:
 *
 *  1. capacity triggers eviction to within BOTH gates (entry count + estimated
 *     bytes), and each gate can fire independently of the other;
 *  2. LRU order is correct: read hits and writes both touch (most recently
 *     used survives, oldest is evicted);
 *  3. eviction is a pure in-memory eviction: `.ui.json` files are untouched and
 *     a re-read after eviction returns the exact pre-eviction value;
 *  4. a dirty session (cache diverged from disk, debounced flush not yet
 *     landed) is PINNED — a capacity flood cannot silently evict it, so the
 *     flush still writes the appended messages to disk (the append is never
 *     lost). After the flush lands, the entry is evictable again (no
 *     permanent pin);
 *  5. coexistence with the pre-existing removal points: `deleteUiMessages`
 *     removes entry + dirty flag atomically (missing id = no-op), and the
 *     boot-migration eviction mid-sequence leaves the LRU order self-healed.
 *
 * Observation technique: `markDiskBehindCache` rewrites the `.ui.json` file
 * with a distinguishable marker — a subsequent read returns the ORIGINAL value
 * if the id is still cached (cache hit) and the marker if it was evicted (disk
 * re-read). This makes cache membership observable through the public API
 * without any timing dependence on the 500ms debounce.
 *
 * The gates are constructor-injected (`uiCacheMaxEntries` / `uiCacheMaxBytes`,
 * defaulting to `Defaults.UiCacheMaxEntries` / `Defaults.UiCacheMaxBytes`) so
 * each gate can be triggered mechanically with tiny fixtures instead of
 * allocating 256MB of messages.
 */
class SessionStoreUiCacheLruSpec extends CatsEffectSuite:

  private var tmp: os.Path = null
  private var sessionsDir: os.Path = null

  override def beforeEach(context: BeforeEach): Unit =
    tmp = os.Path(Files.createTempDirectory("nb-uicache-lru"))
    sessionsDir = tmp / "sessions"
    os.makeDir.all(sessionsDir)

  override def afterEach(context: AfterEach): Unit =
    os.remove.all(tmp)

  private def store(maxEntries: Int, maxBytes: Long): SessionStore =
    SessionStore(sessionsDir, tmp / "tasks", uiCacheMaxEntries = maxEntries, uiCacheMaxBytes = maxBytes)

  private def uiFile(id: String): os.Path = sessionsDir / s"$id.ui.json"

  /** Minimal valid index so `load` takes the `loadFromIndex` branch. */
  private def seedEmptyIndex(): Unit =
    os.write.over(sessionsDir / "_index.json", """{"activeId":"","sessions":[],"folders":[]}""", createFolders = true)

  /** Seed a `.ui.json` with a single User message carrying `marker`. */
  private def seedUiFile(id: String, marker: String): Unit =
    os.write.over(uiFile(id), s"""[{"type":"user","text":"$marker"}]""", createFolders = true)

  /** Seed a `.ui.json` with a single Tool message whose content is `n` chars. */
  private def seedToolFile(id: String, label: String, n: Int, ch: Char): Unit =
    os.write.over(
      uiFile(id),
      s"""[{"type":"tool","label":"$label","summary":"s","content":"${ch.toString * n}"}]""",
      createFolders = true
    )

  /** Overwrite the file BEHIND the cache with a distinguishable marker. */
  private def markDiskBehindCache(id: String): Unit =
    os.write.over(uiFile(id), s"""[{"type":"user","text":"disk-marker-$id"}]""")

  private def headText(st: SessionStore, id: String): IO[Option[String]] =
    st.getUiMessages(id, 0, 1).map { case (msgs, _) =>
      msgs.headOption.collect { case u: UiMessage.User => u.text }
    }

  private def firstText(st: SessionStore, id: String): IO[String] =
    headText(st, id).flatMap {
      case Some(t) => IO.pure(t)
      case None => IO.raiseError(new RuntimeException(s"no User message found for $id"))
    }

  private def toolContent(st: SessionStore, id: String): IO[Option[String]] =
    st.getUiMessages(id, 0, 1).map { case (msgs, _) =>
      msgs.headOption.collect { case t: UiMessage.Tool => t.content }
    }

  /** Cache-miss backfill of a seeded file, asserting the seeded value. */
  private def backfill(st: SessionStore, id: String, marker: String): IO[String] =
    firstText(st, id).flatTap(t => IO(assertEquals(t, marker)))

  private def append(st: SessionStore, id: String, text: String): IO[Unit] =
    st.appendUiMessages(id, List(UiMessage.User(text)))

  private def waitFor(cond: IO[Boolean], what: String, attempts: Int = 150): IO[Unit] =
    if attempts <= 0 then IO.raiseError(new RuntimeException(s"timed out waiting for $what"))
    else cond.ifM(IO.unit, IO.sleep(100.millis) *> waitFor(cond, what, attempts - 1))

  // ------------------------------------------------------------------
  // 0. Default gates: small usage never evicts (backward-compatible shape)
  // ------------------------------------------------------------------

  test("default gates: five small sessions stay cached (no eviction)"):
    val st = SessionStore(sessionsDir, tmp / "tasks") // Defaults: 32 entries / 256MB
    List("s1", "s2", "s3", "s4", "s5").foreach(id => seedUiFile(id, s"m-$id"))
    List("s1", "s2", "s3", "s4", "s5").traverse(id => backfill(st, id, s"m-$id").void) *>
      IO(List("s1", "s2", "s3", "s4", "s5").foreach(markDiskBehindCache)) *>
      List("s1", "s2", "s3", "s4", "s5").traverse(id => firstText(st, id).map(t => assertEquals(t, s"m-$id"))).void

  // ------------------------------------------------------------------
  // 1. Entry gate + LRU victim choice
  // ------------------------------------------------------------------

  test("entry gate: inserting past the cap evicts the LRU-oldest entry"):
    val st = store(maxEntries = 3, maxBytes = 1L << 50)
    List("s1", "s2", "s3", "s4").foreach(id => seedUiFile(id, s"m-$id"))
    // Backfill in order: ticks s1 < s2 < s3 < s4.
    backfill(st, "s1", "m-s1") *> backfill(st, "s2", "m-s2") *> backfill(st, "s3", "m-s3") *>
      backfill(st, "s4", "m-s4") *>
      IO {
        markDiskBehindCache("s1")
        markDiskBehindCache("s2")
      } *>
      // s2 still served from cache => not evicted (asserted BEFORE the s1
      // reread: that reread backfills s1, which legitimately triggers the
      // next eviction — repopulation on access is part of the contract).
      firstText(st, "s2").map(t => assertEquals(t, "m-s2")) *>
      // s1 re-reads from disk => was evicted.
      firstText(st, "s1").map(t => assertEquals(t, "disk-marker-s1"))

  // ------------------------------------------------------------------
  // 2. LRU order: read hits touch (reorder)
  // ------------------------------------------------------------------

  test("read hit touches: recently read entry survives, untouched oldest is evicted"):
    val st = store(maxEntries = 3, maxBytes = 1L << 50)
    List("s1", "s2", "s3", "s4").foreach(id => seedUiFile(id, s"m-$id"))
    backfill(st, "s1", "m-s1") *> backfill(st, "s2", "m-s2") *> backfill(st, "s3", "m-s3") *>
      // Read-hit on s1: s1 becomes most recently used.
      firstText(st, "s1") *>
      backfill(st, "s4", "m-s4") *>
      IO {
        markDiskBehindCache("s1")
        markDiskBehindCache("s2")
      } *>
      // Victim is s2 (now-oldest untouched), NOT s1.
      firstText(st, "s1").map(t => assertEquals(t, "m-s1")) *>
      firstText(st, "s2").map(t => assertEquals(t, "disk-marker-s2"))

  // ------------------------------------------------------------------
  // 3. Byte gate fires independently of the entry gate
  // ------------------------------------------------------------------

  test("byte gate: two fat entries evict down to budget while the entry count stays far under its cap"):
    val st = store(maxEntries = 8, maxBytes = 3000L)
    // One Tool message of 2500 chars estimates ~5.2KB (2 bytes/char + overhead)
    // — above the 3000-byte budget; a User entry estimates ~172 bytes.
    seedToolFile("fat1", "read", 2500, 'X')
    seedToolFile("fat2", "read", 2500, 'Y')
    // First fat entry alone is over budget but has no eviction candidate:
    // it stays (documented bound = maxBytes + one entry).
    toolContent(st, "fat1").map(t => assertEquals(t.map(_.length), Some(2500))) *>
      // Second fat insert breaches the budget with a candidate available:
      // fat1 (oldest) is evicted by the BYTE gate (count = 2 of 8 throughout).
      toolContent(st, "fat2").map(t => assertEquals(t.map(_.length), Some(2500))) *>
      IO(markDiskBehindCache("fat1")) *>
      // fat1 re-reads from disk (now a User marker) => was evicted.
      headText(st, "fat1").map(t => assertEquals(t, Some("disk-marker-fat1"))) *>
      // fat2 is still cached and must round-trip byte-identical.
      toolContent(st, "fat2").map(t => assertEquals(t, Some("Y" * 2500)))

  test("byte gate does not over-evict: small entries stay under budget and are all kept"):
    val st = store(maxEntries = 8, maxBytes = 3000L)
    List("a", "b", "c").foreach(id => seedUiFile(id, s"m-$id"))
    backfill(st, "a", "m-a") *> backfill(st, "b", "m-b") *> backfill(st, "c", "m-c") *>
      IO(List("a", "b", "c").foreach(markDiskBehindCache)) *>
      List("a", "b", "c").traverse(id => firstText(st, id).map(t => assertEquals(t, s"m-$id"))).void

  // ------------------------------------------------------------------
  // 4. Dirty sessions are pinned; flush unpins
  // ------------------------------------------------------------------

  test("dirty session survives a capacity flood and its append lands on disk"):
    val st = store(maxEntries = 2, maxBytes = 1L << 50)
    // Two appends to sA: cache = m1+m2, entry dirty => pinned. sA is also the
    // LRU-oldest entry at flood time, so a pin-less implementation would evict
    // exactly this one and lose m1/m2 (flush would find nothing cached).
    append(st, "sA", "m1") *> append(st, "sA", "m2") *>
      // Flood: two clean backfills push the count past the cap. The victim
      // must be sB (clean, oldest), never the pinned sA.
      IO(seedUiFile("sB", "m-sB")) *> firstText(st, "sB") *>
      IO(seedUiFile("sC", "m-sC")) *> firstText(st, "sC") *>
      // Deterministic drain: whenever the 500ms debounce fired during the test,
      // the drain rewrites the same full value — the final disk state is the
      // same in every interleaving.
      st.flushPendingUiWrites *>
      IO {
        val disk = os.read(uiFile("sA"))
        assert(disk.contains("m1"), s"sA disk file lost m1: $disk")
        assert(disk.contains("m2"), s"sA disk file lost m2: $disk")
      }

  test("flush unpins: a flushed session is evictable again (no permanent pin)"):
    val st = store(maxEntries = 2, maxBytes = 1L << 50)
    // Append + flush: sA is clean (disk == cache) and unpinned.
    append(st, "sA", "m1") *> st.flushPendingUiWrites *>
      // Flood: sA is now the LRU-oldest CLEAN entry => evicted.
      IO(seedUiFile("sB", "m-sB")) *> firstText(st, "sB") *>
      IO(seedUiFile("sC", "m-sC")) *> firstText(st, "sC") *>
      IO(markDiskBehindCache("sA")) *>
      // sA re-reads from disk => the pin really cleared after the flush.
      firstText(st, "sA").map(t => assertEquals(t, "disk-marker-sA"))

  // ------------------------------------------------------------------
  // 5. Eviction is a pure in-memory eviction + reread equivalence
  // ------------------------------------------------------------------

  test("eviction never touches the disk file and reread returns the exact pre-eviction value"):
    val st = store(maxEntries = 1, maxBytes = 1L << 50)
    seedUiFile("s1", "m-s1")
    seedUiFile("s2", "m-s2")
    val before = os.read(uiFile("s1"))
    firstText(st, "s1") *> // backfill s1
      backfill(st, "s2", "m-s2") *> // insert evicts s1
      IO(assertEquals(os.read(uiFile("s1")), before, "eviction must not rewrite the .ui.json")) *>
      firstText(st, "s1").map(t => assertEquals(t, "m-s1"))

  // ------------------------------------------------------------------
  // 6. Coexistence with the pre-existing removal points
  // ------------------------------------------------------------------

  test("deleteUiMessages removes entry + dirty flag atomically; missing id is a no-op; LRU self-heals"):
    val st = store(maxEntries = 3, maxBytes = 1L << 50)
    List("s1", "s2", "s3").foreach(id => seedUiFile(id, s"m-$id"))
    backfill(st, "s1", "m-s1") *> backfill(st, "s2", "m-s2") *> backfill(st, "s3", "m-s3") *>
      // Make s2 dirty, then delete: entry + dirty flag must BOTH clear.
      append(st, "s2", "pending") *>
      st.deleteUiMessages("s2") *>
      st.flushPendingUiWrites *> // must not resurrect or throw
      headText(st, "s2").map(t => assertEquals(t, None)) *>
      // Never-cached, never-existing id: plain no-op.
      st.deleteUiMessages("does-not-exist") *>
      // LRU heals around the removed id: backfills s4 + s5 push {s1,s3,s4,s5}
      // past the cap and the victim is s1 (oldest), never a phantom id.
      IO(seedUiFile("s4", "m-s4")) *> backfill(st, "s4", "m-s4") *>
      IO(seedUiFile("s5", "m-s5")) *> backfill(st, "s5", "m-s5") *>
      IO(markDiskBehindCache("s1")) *>
      firstText(st, "s1").map(t => assertEquals(t, "disk-marker-s1")) *>
      firstText(st, "s3").map(t => assertEquals(t, "m-s3"))

  test("boot-migration eviction mid-sequence coexists with LRU (order self-heals, shrunk value rereads)"):
    seedEmptyIndex()
    val st = store(maxEntries = 3, maxBytes = 1L << 50)
    // Oversized file (> ShrinkThresholdBytes = 512KB): one Tool message with
    // 600k chars of content; sanitizeForStorage caps it well below the threshold.
    seedToolFile("big", "read", 600000, 'B')
    seedUiFile("s1", "m-s1")
    seedUiFile("s2", "m-s2")
    firstText(st, "s1") *> firstText(st, "s2") *> // ticks: s1 < s2
      st.load *> // fires the background shrink fiber for "big"
      waitFor(IO(os.size(uiFile("big")) < 512L * 1024L), "boot shrink to complete") *>
      // The migration evicted "big"; LRU keeps working. Two backfills push the
      // count past the cap whether or not "big" is still cached at this instant
      // (the fiber's removeEntry races the size poll by design) — in both
      // interleavings the victim is s1 (oldest tick), never a phantom id.
      IO(seedUiFile("s3", "m-s3")) *> backfill(st, "s3", "m-s3") *>
      IO(seedUiFile("s4", "m-s4")) *> backfill(st, "s4", "m-s4") *>
      IO(markDiskBehindCache("s1")) *>
      firstText(st, "s1").map(t => assertEquals(t, "disk-marker-s1")) *>
      firstText(st, "s2").map(t => assertEquals(t, "m-s2")) *>
      // Reread of the migrated session returns the SHRUNK value — identical
      // whether served from the cache or re-read from the shrunk file.
      st.getUiMessages("big", 0, 1).map { case (msgs, _) =>
        msgs match
          case (t: UiMessage.Tool) :: _ =>
            assert(t.content.length < 600000, "migrated entry should be the sanitized (shrunk) value")
            assert(t.content.contains("truncated"), s"expected truncation marker, got head=${t.content.take(80)}")
          case other => fail(s"expected a Tool message, got ${other.map(_.typeName)}")
      }

end SessionStoreUiCacheLruSpec
