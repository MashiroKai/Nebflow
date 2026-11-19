package nebflow.shared

import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * WorkspaceTmpSweep spec (worktree/tmp TTL wiring batch, 2026-11-19): the
 * mechanism side of the author's 2026-11-16 ruling — the engine must drive
 * `scripts/stand-wt.py clean --apply --ttl-days 7` on a boot + periodic leg.
 *
 * The clean semantics themselves stay single-sourced in the script; this spec
 * pins exactly the two things the engine owns: the argv it hands over (apply,
 * seven days, the project root) and the fail-safe no-op when the project has no
 * `.stand/tmp` root (installed/data-root cwd) or no script.
 */
class WorkspaceTmpSweepSpec extends FunSuite:

  /** A fake cleaner that records its argv next to itself; prints one DELETE line. */
  private def seedFakeProject(): os.Path =
    val root = os.temp.dir(prefix = "ws-tmp-sweep-")
    os.makeDir.all(root / "scripts")
    os.write(
      root / "scripts" / "stand-wt.py",
      """import pathlib, sys
        |pathlib.Path(__file__).with_name('argv.txt').write_text('\n'.join(sys.argv[1:]))
        |print('DELETE fake')
        |""".stripMargin
    )
    os.makeDir.all(root / ".stand" / "tmp" / "demo")
    root

  test("sweepOnce drives the cleaner with --apply --ttl-days 7 and the project root") {
    val root = seedFakeProject()
    try
      WorkspaceTmpSweep.sweepOnce(root).unsafeRunSync()
      val argv = os.read(root / "scripts" / "argv.txt").linesIterator.toList
      assert(argv.contains("clean"), s"argv: $argv")
      assert(argv.contains("--apply"), s"clean must actually delete, not dry-run: $argv")
      assert(argv.sliding(2).contains(List("--ttl-days", WorkspaceTmpSweep.TtlDays.toString)), s"argv: $argv")
      assert(!argv.contains("--force"), s"the uncommitted-retention guard must not be forced off: $argv")
      val rootAt = argv.indexOf("--root")
      assert(rootAt >= 0 && argv(rootAt + 1) == root.toString, s"argv: $argv")
    finally os.remove.all(root)
  }

  test("no-op (fail-safe) when the project has no .stand/tmp root, and never throws") {
    val root = os.temp.dir(prefix = "ws-tmp-sweep-empty-")
    try
      os.makeDir.all(root / "scripts")
      os.write(root / "scripts" / "stand-wt.py", "raise SystemExit(3)\n")
      WorkspaceTmpSweep.sweepOnce(root).unsafeRunSync() // must not throw
      assert(!os.exists(root / "scripts" / "argv.txt"), "cleaner must not be invoked without a tmp root")
    finally os.remove.all(root)
  }

  test("a missing script is a no-op, not a failure") {
    val root = os.temp.dir(prefix = "ws-tmp-sweep-noscript-")
    try
      os.makeDir.all(root / ".stand" / "tmp" / "demo")
      WorkspaceTmpSweep.sweepOnce(root).unsafeRunSync()
    finally os.remove.all(root)
  }

end WorkspaceTmpSweepSpec
