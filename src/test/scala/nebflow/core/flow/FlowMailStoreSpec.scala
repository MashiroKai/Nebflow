package nebflow.core.flow

import cats.effect.unsafe.implicits.global
import cats.effect.IO
import munit.CatsEffectSuite

class FlowMailStoreSpec extends CatsEffectSuite:

  // #7/#8: flow node sessionIds like "dag-git-merge-scanner-405090" contain
  // non-hex letters (g/i/t/m/r/s/n). The old hex-only regex rejected them,
  // crashing team agents that Mail a RESULT back during a git-merge flow.
  private val flowSessionId = "dag-git-merge-scanner-405090"

  test("#7/#8: flow-style sessionId is accepted (no require failure)") {
    // load is read-only and returns Nil when the mailbox file is absent,
    // so this exercises mailboxDir's regex without side effects.
    val io: IO[List[FlowMailStore.MailRecord]] =
      FlowMailStore.load(flowSessionId, "git-merge")
    io.attempt.map {
      case Right(records) =>
        // Either empty (file absent) or existing records — both are fine;
        // the point is no IllegalArgumentException was thrown.
        assert(records != null)
      case Left(e) =>
        fail(s"sessionId '$flowSessionId' must be accepted, but got: ${e.getMessage}")
    }
  }

  test("#7/#8: a plain UUID sessionId is still accepted") {
    FlowMailStore.load("550e8400-e29b-41d4-a716-446655440000", "code-review").attempt.map {
      case Right(_) => () // ok
      case Left(e) => fail(s"UUID sessionId must be accepted: ${e.getMessage}")
    }
  }

  test("validation preserved: sessionId with path separator still rejected") {
    // "/" is outside the allowed charset — mailboxDir's require throws
    // synchronously (during load's eager val), so we use intercept, not .attempt.
    val caught = intercept[IllegalArgumentException] {
      FlowMailStore.load("evil/escape", "git-merge")
    }
    assert(caught.getMessage.contains("Invalid sessionId"), s"got: ${caught.getMessage}")
  }

  test("validation preserved: empty sessionId is a no-op (returns Nil)") {
    FlowMailStore.load("", "git-merge").map { records =>
      assert(records.isEmpty, "empty sessionId should short-circuit to Nil")
    }
  }
end FlowMailStoreSpec
