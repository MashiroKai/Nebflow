package nebflow.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite

/**
 * `nebflow logs --follow` (phase 2 daemon plan §5, LogsFollowSpec): the
 * follow state machine with an INJECTED event source (no real WatchService,
 * no timing races), plus the R2-1 routing regression and the existing pure
 * helpers that this batch touched.
 */
class LogsFollowSpec extends FunSuite:

  // ── R2-1: both routes accept the flag (no `Unknown flag: --follow`) ────

  test("R2-1: `logs --follow` routes through LogsPath params without unknown-flag rejection") {
    assertEquals(
      CliRouter.parseArgs(List("--follow"), LogsCommand.subcommands.head.params),
      Right(Map("follow" -> "true"), Nil)
    )
  }

  test("R2-1: `logs tail --follow` parses too, beside its existing flags") {
    val tailParams = LogsCommand.subcommands(2).params
    assertEquals(
      CliRouter.parseArgs(List("--follow", "-n", "5"), tailParams),
      Right(Map("follow" -> "true", "lines" -> "5"), Nil)
    )
  }

  test("follow + --json is refused (streaming face has no JSON semantics)") {
    val ctx = CliContext(
      Map("follow" -> "true"),
      Nil,
      json = true,
      quiet = false,
      client = None,
      configDir = os.temp.dir(prefix = "logs-follow-spec-")
    )
    LogsCommand.subcommands.head.run(ctx).unsafeRunSync() match
      case CliResult.Error(msg, _) => assert(msg.contains("follow"), msg)
      case other => fail(s"expected Error, got $other")
  }

  // ── drainStep: incremental lines / partial hold-back / rotation ────────

  test("drainStep: reads new complete lines and holds back a trailing partial line") {
    val dir = os.temp.dir(prefix = "logs-follow-drain-")
    try
      val f = (dir / "nebflow.log").toIO
      os.write.over(dir / "nebflow.log", "a\nb\n")
      val (l1, p1) = LogsCommand.drainStep(f, 0L)
      assertEquals(l1, List("a", "b"))
      assertEquals(p1, 4L)

      os.write.append(dir / "nebflow.log", "c\n")
      val (l2, p2) = LogsCommand.drainStep(f, p1)
      assertEquals(l2, List("c"))

      os.write.append(dir / "nebflow.log", "par") // no newline yet
      val (l3, p3) = LogsCommand.drainStep(f, p2)
      assertEquals(l3, Nil)
      assertEquals(p3, p2) // nothing consumed

      os.write.append(dir / "nebflow.log", "tial\n")
      val (l4, p4) = LogsCommand.drainStep(f, p3)
      assertEquals(l4, List("partial"))
      assertEquals(p4, f.length())
    finally os.remove.all(dir)
    end try
  }

  test("drainStep: rotation (recreated smaller file) reopens from the head") {
    val dir = os.temp.dir(prefix = "logs-follow-rot-")
    try
      os.write.over(dir / "nebflow.log", "a very long first generation line\n")
      val pos = os.size(dir / "nebflow.log")
      // logback rotation: rename away + fresh file appears (CREATE event)
      os.move(dir / "nebflow.log", dir / "nebflow.2026-10-10.0.log")
      os.write.over(dir / "nebflow.log", "fresh\n")
      val (lines, newPos) = LogsCommand.drainStep((dir / "nebflow.log").toIO, pos)
      assertEquals(lines, List("fresh"))
      assertEquals(newPos, 6L)
    finally os.remove.all(dir)
  }

  // ── the follow loop with a stubbed event source ────────────────────────

  /** Control-flow sentinel: thrown by the stub source to end the loop. */
  private case object StopFollow extends RuntimeException("stop-follow")

  test("followLoop: initial offset at file end, then prints each appended line once") {
    val dir = os.temp.dir(prefix = "logs-follow-loop-")
    try
      os.write.over(dir / "nebflow.log", "seed\n")
      // The stub event source APPENDS the new content when it fires — that
      // is exactly what a real fs event means, and it keeps the test
      // deterministic (no race with the loop's start-offset capture).
      var budget = 2
      val await = () =>
        budget match
          case 0 => throw StopFollow
          case 2 =>
            os.write.append(dir / "nebflow.log", "PHASE2-FOLLOW-MARKER\n"); budget -= 1
          case 1 =>
            os.write.append(dir / "nebflow.log", "second line\n"); budget -= 1
          case _ => throw StopFollow
      val printed = scala.collection.mutable.ListBuffer[String]()
      LogsCommand
        .followLoop(dir, "nebflow.log", None, l => IO(printed += l), await)
        .attempt
        .unsafeRunSync()
      assertEquals(printed.toList, List("PHASE2-FOLLOW-MARKER", "second line"))
      assert(!printed.contains("seed")) // start offset was at end-of-file
    finally os.remove.all(dir)
    end try
  }

  test("followLoop: --grep filter keeps only matching lines") {
    val dir = os.temp.dir(prefix = "logs-follow-grep-")
    try
      os.write.over(dir / "nebflow.log", "seed\n")
      var fired = false
      val await = () =>
        if fired then throw StopFollow
        else
          os.write.append(dir / "nebflow.log", "ERROR bad\nINFO fine\n")
          fired = true
      val pat = java.util.regex.Pattern.compile("ERROR")
      val printed = scala.collection.mutable.ListBuffer[String]()
      LogsCommand
        .followLoop(dir, "nebflow.log", Some(pat), l => IO(printed += l), await)
        .attempt
        .unsafeRunSync()
      assertEquals(printed.toList, List("ERROR bad"))
    finally os.remove.all(dir)
    end try
  }

  // ── existing pure helpers stay intact ──────────────────────────────────

  test("validateLogFileName still rejects traversal forms") {
    assert(LogsCommand.validateLogFileName("../x").isLeft)
    assert(LogsCommand.validateLogFileName("/etc/passwd").isLeft)
    assert(LogsCommand.validateLogFileName("nebflow.log").isRight)
  }

  test("tailLines returns the last n lines bounded") {
    val dir = os.temp.dir(prefix = "logs-follow-tail-")
    try
      os.write.over(dir / "nebflow.log", (1 to 100).map(i => s"line$i").mkString("", "\n", "\n"))
      val last = LogsCommand.tailLines((dir / "nebflow.log").toIO, 3)
      assertEquals(last, List("line98", "line99", "line100"))
    finally os.remove.all(dir)
  }

end LogsFollowSpec
