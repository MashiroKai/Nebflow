package nebflow.core

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.{Files, Path, Paths}

/**
 * Regression guard for the boot-OOM attribution fix (2026-10-25).
 *
 * History: the gateway creates the FileChangeTracker over
 * `System.getProperty("user.dir")`. The 2026-10-25 production boot chain
 * (launchd WorkingDirectory=/Users/<user> → bin/stand wrapper that never
 * cd's) booted with user.dir = the user's HOME, so the initial snapshot walk
 * crawled the entire home tree at ~3GB/s of path-op allocation (measured, E11
 * reproduction: 96 GC pauses in the first 4.6s of boot; the same night's
 * production cluster died at +15-17s with a ZGC allocation-stall OOM while
 * the walk was still running).
 *
 * Two independent guards live in FileChangeTracker now:
 *   ① isUnscannableRoot — user.home and filesystem roots are never walked;
 *      `create` yields an empty-snapshot (disabled) tracker instead.
 *   ② walk budget — every scan is capped by file count and wall clock
 *      (system-property tunable); a breach discards the scan (Map.empty),
 *      the same fail-soft contract as a traversal error.
 *
 * Red/green: without ② the budget test's exact-empty assertion fails (the
 * 200-file tree is fully collected); without the maxFiles restore step the
 * positive control fails. Without ① the home/root assertions fail.
 */
class FileChangeTrackerBootGuardSpec extends CatsEffectSuite:

  test("user home and filesystem roots are unscannable roots"):
    val home = Paths.get(System.getProperty("user.home"))
    assert(FileChangeTracker.isUnscannableRoot(home), "user.home must be refused")
    assert(FileChangeTracker.isUnscannableRoot(Paths.get("/")), "filesystem root must be refused")
    assert(
      !FileChangeTracker.isUnscannableRoot(home.resolve("Documents")),
      "a home SUBDIR is a legitimate root (budget caps it)"
    )
    IO.blocking(Files.createTempDirectory("fct-guard")).flatMap { tmp =>
      IO(assert(!FileChangeTracker.isUnscannableRoot(tmp), "a temp project root must stay scannable"))
    }

  test("create() over the user home yields a tracker that never walks (checkChanges stays None)"):
    val home = System.getProperty("user.home")
    FileChangeTracker.create(home).flatMap { tracker =>
      // The refused tracker holds an EMPTY snapshot; checkChanges must be a
      // cheap no-op (no walk of the home tree) answering None.
      tracker.checkChanges().map(result => assert(result.isEmpty))
    }

  test(
    "walk budget: a scan over a tree larger than the cap is discarded (fail-soft), and rescans normally once the cap lifts"
  ):
    val prop = "nebflow.filewalk.maxFiles"
    IO.blocking {
      val root = Files.createTempDirectory("fct-budget")
      val dir = root.resolve("bulk")
      Files.createDirectories(dir)
      (1 to 200).foreach(i => Files.writeString(dir.resolve(s"f$i.txt"), "x"))
      root
    }.flatMap { root =>
      val scan = IO.blocking(FileChangeTracker.scanProject(root.toString))
      for
        _ <- IO(sys.props.update(prop, "50"))
        // 200 files + ~2 dirs > cap 50 ⇒ the scan is discarded, not half-kept.
        breached <- scan
        _ <- IO(sys.props.remove(prop))
        // Positive control: with the cap lifted the same tree scans fully.
        full <- scan
      yield
        assert(breached.isEmpty, s"breached scan should be discarded, got ${breached.size} entries")
        assert(full.size == 200, s"capped-off rescan should see all files, got ${full.size}")
    }
end FileChangeTrackerBootGuardSpec
