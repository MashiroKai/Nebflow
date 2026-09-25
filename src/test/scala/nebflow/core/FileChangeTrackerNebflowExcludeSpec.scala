package nebflow.core

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}

/**
 * Regression guard for the govregress timing fix (chain govregress-fix,
 * 2026-09-26): FileChangeTracker's full walk used to descend into `.nebflow`
 * — Nebflow's own process directory (task state, evidence, worktrees, logs).
 * On a real deployment that subtree held 169,470 of 175,819 walkable files
 * (96.4%), so every fixture `FileChangeTracker.create(...)` paid 9-17s of
 * synchronous stat work, pushing timing-sensitive suites past their
 * watchdogs (TurnEndpointSpec 1s-timeout case ran 21.3-23.1s; RootNotify A2
 * 33.8s TimeoutException; DynamicFanout D8/D12/D15 31.0s watchdogs).
 *
 * The fix adds `.nebflow` to `ExcludedDirs` (name-based, any depth) AND prunes
 * excluded directories from the traversal itself (`Files.walkFileTree` +
 * `SKIP_SUBTREE`), shared by `scanProject` (used by `create` for the initial
 * snapshot) and the instance `scanFiles` (periodic full scan). Both halves are
 * load-bearing: `Files.walk` descends into every directory and only post-
 * filters entries, so a name exclusion alone never saved the walk cost — the
 * probe measured `FileChangeTracker.create` at 16.4s in a tree whose `.nebflow`
 * held 169k files even with `.nebflow` added to `ExcludedDirs`.
 *
 * Red/green: without the fix the exact-set assertion fails (`.nebflow` keys
 * present in the scan); with exclusion-but-no-pruning the canary test fails
 * (the walk opens the unreadable dir inside `.nebflow`, the fail-soft catch
 * discards the whole scan, and the tracked file goes missing); with pruning
 * both pass. The positive control keeps the exact-set assertion from passing
 * vacuously on an over-broad exclusion.
 */
class FileChangeTrackerNebflowExcludeSpec extends CatsEffectSuite:

  test("scanProject skips .nebflow dirs at any depth and still tracks project files"):
    IO.blocking {
      val root: Path = Files.createTempDirectory("fct-nebflow-exclude")
      // A normal project file that must stay tracked (positive control).
      val src = root.resolve("src/main/scala")
      Files.createDirectories(src)
      Files.writeString(src.resolve("Demo.scala"), "object Demo")
      // Process material at the root: 5,000 bulk files prove the walk does
      // not even descend into .nebflow (cost is cut, not just one entry).
      val bulk = root.resolve(".nebflow/evidence/bulk")
      Files.createDirectories(bulk)
      (1 to 5000).foreach(i => Files.writeString(bulk.resolve(s"f$i.log"), "x"))
      Files.writeString(root.resolve(".nebflow/state.json"), "{}")
      // A .nebflow nested deeper in the tree is excluded by name as well.
      val nested = root.resolve("sub/proj/.nebflow/cache")
      Files.createDirectories(nested)
      Files.writeString(nested.resolve("c.bin"), "y")
      root
    }.flatMap { root =>
      val keySet = IO.blocking(FileChangeTracker.scanProject(root.toString)).map(_.keySet)
      assertIO(keySet, Set("src/main/scala/Demo.scala"))
    }

  test("create() wires a working tracker over the same exclusion (no spurious reminders)"):
    IO.blocking {
      val root: Path = Files.createTempDirectory("fct-nebflow-create")
      val src = root.resolve("src")
      Files.createDirectories(src)
      Files.writeString(src.resolve("A.scala"), "object A")
      val proc = root.resolve(".nebflow/evidence")
      Files.createDirectories(proc)
      Files.writeString(proc.resolve("run.log"), "x")
      root
    }.flatMap { root =>
      FileChangeTracker.create(root.toString).flatMap { tracker =>
        // Static tree: the reminder pipeline must run and report nothing.
        assertIO(tracker.checkChanges(), None)
      }
    }

  test("the walk prunes .nebflow: an unreadable dir inside it cannot break the scan"):
    // Traversal observer: if the walker DESCENDS into .nebflow it will try to
    // open this unreadable directory, the fail-soft catch then discards the
    // whole scan (Map.empty), and the positive control below fails. A pruned
    // walk never opens it, so the tracked file survives. This is what makes
    // "excluded but still walked" observable — the plain result-set test
    // cannot distinguish pruning from post-filtering (both return the same
    // keys; only the cost differs).
    IO.blocking {
      val root: Path = Files.createTempDirectory("fct-nebflow-prune")
      val src = root.resolve("src")
      Files.createDirectories(src)
      Files.writeString(src.resolve("Demo.scala"), "object Demo")
      val locked = root.resolve(".nebflow/evidence/locked")
      Files.createDirectories(locked)
      (1 to 500).foreach(i => Files.writeString(locked.resolve(s"f$i.log"), "x"))
      root -> locked
    }.flatMap { case (root, locked) =>
      val lockedPerms = java.nio.file.attribute.PosixFilePermissions.fromString("---------")
      val restorePerms = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
      val scan =
        IO.blocking {
          Files.setPosixFilePermissions(locked, lockedPerms)
          try FileChangeTracker.scanProject(root.toString)
          finally Files.setPosixFilePermissions(locked, restorePerms)
        }
      assertIO(scan.map(_.keySet), Set("src/Demo.scala"))
    }

