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
 * The fix adds `.nebflow` to `ExcludedDirs` (name-based, any depth). Both
 * `scanProject` (used by `create` for the initial snapshot) and the instance
 * `scanFiles` (periodic full scan) read that same set, so one entry covers
 * both walk sites.
 *
 * Red/green: without the fix the exact-set assertion fails (`.nebflow` keys
 * present in the scan); with it the walk returns exactly the tracked project
 * file. The positive control (tracked file must stay in the scan) keeps the
 * exact-set assertion from passing vacuously on an over-broad exclusion.
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
