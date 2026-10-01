package nebflow.core.jev

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * P2 (Face C) acceptance: the coexistence gate and the fail-open path.
 *
 * The central property under test is the author's third sentence made
 * mechanical: **OFF must be byte-identical to the pre-JeV behaviour**. That is
 * asserted as a STRUCTURAL property of the gate (one pure decision point that
 * returns a typed Off, not a scattering of conditionals), plus a golden check
 * that the OFF decision does not depend on any allocation-side state.
 */
class JevGateSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-gate-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  // ── the gate is a pure function of its snapshot ─────────────────────────

  test("applied requires BOTH configured and enabled (author sentence 3)") {
    val on = JevGateSnapshot(configured = true, enabled = true, provider = "typesafe-jev", timeoutMs = 15000L, choiceHighBasePolicy = "")
    assertEquals(JevGate.effective(on), JevGateOutcome.Applied(on))
    assert(JevGate.isApplied(on))

    val disabled = on.copy(enabled = false)
    assertEquals(JevGate.effective(disabled), JevGateOutcome.Off(JevGateReason.ToggleOff))

    val unconfigured = on.copy(configured = false)
    assertEquals(JevGate.effective(unconfigured), JevGateOutcome.Off(JevGateReason.NotConfigured))
  }

  test("an unknown or empty provider id is refused rather than defaulted") {
    val base = JevGateSnapshot(configured = true, enabled = true, provider = "typesafe-jev", timeoutMs = 15000L, choiceHighBasePolicy = "")
    assertEquals(JevGate.effective(base.copy(provider = "")), JevGateOutcome.Off(JevGateReason.ProviderMissing))
    assertEquals(JevGate.effective(base.copy(provider = "openai")), JevGateOutcome.Off(JevGateReason.ProviderMissing))
    assert(JevGate.isApplied(base.copy(provider = "laya")))
  }

  test("the gate accepts exactly the provider ids the decision layer defines") {
    // `core` cannot import `llm` (dependency direction), so the list is
    // duplicated as literals; this is the assertion that keeps the two in
    // step. Drift fails here rather than at a dispatch site.
    assertEquals(JevGate.KnownProviders.sorted, nebflow.llm.decision.DecisionProvider.AllIds.sorted)
  }

  // ── hot read: every malformed shape degrades to OFF, never throws ───────

  test("an absent or malformed jev block reads as OFF") {
    withTempDataRoot { tmp =>
      for
        absent <- JevConfigReader.snapshot
        _ = assertEquals(absent, JevGateSnapshot.Off)
        _ = os.write.over(tmp / "nebflow.json", "{ not json")
        broken <- JevConfigReader.snapshot
        _ = assertEquals(broken, JevGateSnapshot.Off, "an unparseable config must not throw")
        _ = os.write.over(tmp / "nebflow.json", """{"jev":"a string, not an object"}""")
        wrongType <- JevConfigReader.snapshot
        _ = assertEquals(wrongType, JevGateSnapshot.Off)
        _ = os.write.over(tmp / "nebflow.json", """{"llm":{"providers":{}}}""")
        absentAgain <- JevConfigReader.outcome
        _ = assertEquals(absentAgain, JevGateOutcome.Off(JevGateReason.NotConfigured))
      yield ()
    }
  }

  test("a configured block reads its scalars and applies") {
    withTempDataRoot { tmp =>
      for
        _ <- IO(os.write.over(tmp / "nebflow.json", """{"jev":{"enabled":true,"provider":"laya","timeoutMs":2500,"choiceHighBasePolicy":"warn"}}"""))
        snap <- JevConfigReader.snapshot
        _ = assert(snap.configured)
        _ = assert(snap.enabled)
        _ = assertEquals(snap.provider, "laya")
        _ = assertEquals(snap.timeoutMs, 2500L)
        _ = assertEquals(snap.choiceHighBasePolicy, "warn")
        out <- JevConfigReader.outcome
        _ = assert(JevGate.isApplied(snap), s"expected applied, got: $out")
        // A non-positive deadline is not a deadline: it reads as unset.
        _ <- IO(os.write.over(tmp / "nebflow.json", """{"jev":{"enabled":true,"provider":"laya","timeoutMs":0}}"""))
        zero <- JevConfigReader.snapshot
        _ = assertEquals(zero.timeoutMs, 0L)
      yield ()
    }
  }

  test("the gate hot-reads: flipping the toggle changes the verdict without a restart") {
    withTempDataRoot { tmp =>
      for
        _ <- IO(os.write.over(tmp / "nebflow.json", """{"jev":{"enabled":false,"provider":"typesafe-jev"}}"""))
        off <- JevConfigReader.outcome
        _ = assertEquals(off, JevGateOutcome.Off(JevGateReason.ToggleOff))
        _ <- IO(os.write.over(tmp / "nebflow.json", """{"jev":{"enabled":true,"provider":"typesafe-jev"}}"""))
        on <- JevConfigReader.outcome
        _ = assert(on match { case JevGateOutcome.Applied(_) => true; case _ => false })
      yield ()
    }
  }

  // ── fail-open: never silent, never blocking ────────────────────────────

  test("a failure fallback both warns and appends a visibility event") {
    withTempDataRoot { tmp =>
      for
        out <- JevFallback.record(JevFallbackKind.Failure, "node-1", "decision API server error: HTTP 500")
        _ = assertEquals(out, JevAllocation.FellBack(JevFallbackKind.Failure, "decision API server error: HTTP 500"))
        lines <- IO(os.read(tmp / "logs" / "jev-allocation.jsonl").trim.linesIterator.toList)
        _ = assertEquals(lines.size, 1, "exactly one event for one failure")
        _ = assert(lines.head.contains("\"event\":\"failure\""), s"event kind must be recorded: ${lines.head}")
        _ = assert(lines.head.contains("\"subject\":\"node-1\""), s"subject must be recorded: ${lines.head}")
      yield ()
    }
  }

  test("a timeout fallback is distinguishable from a generic failure") {
    withTempDataRoot { tmp =>
      for
        _ <- JevFallback.record(JevFallbackKind.Timeout, "n", "decision API timeout")
        lines <- IO(os.read(tmp / "logs" / "jev-allocation.jsonl").trim.linesIterator.toList)
        _ = assert(lines.head.contains("\"event\":\"timeout\""), s"timeout must be its own kind: ${lines.head}")
      yield ()
    }
  }

  test("the OFF path emits no event (a normal state must not pollute the stream)") {
    withTempDataRoot { tmp =>
      for
        out <- JevFallback.record(JevFallbackKind.Off, "n", "jev block absent")
        _ = assertEquals(out, JevAllocation.FellBack(JevFallbackKind.Off, "jev block absent"))
        exists <- IO(os.exists(tmp / "logs" / "jev-allocation.jsonl"))
        _ = assert(!exists, "the OFF state is normal and must not write an event")
      yield ()
    }
  }

  test("a successful allocation is recorded so 'the action happened' is auditable (#8)") {
    withTempDataRoot { tmp =>
      for
        _ <- JevFallback.recordSuccess("node-9", List("backend-dev", "visual-report"))
        lines <- IO(os.read(tmp / "logs" / "jev-allocation.jsonl").trim.linesIterator.toList)
        _ = assertEquals(lines.size, 1)
        _ = assert(lines.head.contains("\"event\":\"allocated\""))
        _ = assert(lines.head.contains("backend-dev,visual-report"), s"the chosen set must be recorded: ${lines.head}")
      yield ()
    }
  }

  test("a failed event append never fails the dispatch") {
    withTempDataRoot { tmp =>
      // Make the log path unusable by putting a FILE where the directory must
      // be: the fallback must still return its value.
      for
        _ <- IO(os.write(tmp / "logs", "not a directory"))
        out <- JevFallback.record(JevFallbackKind.Failure, "n", "boom")
        _ = assertEquals(out, JevAllocation.FellBack(JevFallbackKind.Failure, "boom"))
      yield ()
    }
  }
