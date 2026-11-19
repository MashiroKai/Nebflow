package nebflow.agent

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import nebflow.core.ResumeHandoff
import nebflow.shared.*

class RecoveryAdmissionSpec extends munit.CatsEffectSuite:
  test("concurrent recovery is rejected, later freeze can recover") {
    for
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      first <- DelegateWakeLeg.exclusiveWake("test-recovery")(
        entered.complete(()) *> release.get.as(Right("first"))
      ).start
      _ <- entered.get
      second <- DelegateWakeLeg.exclusiveWake("test-recovery")(IO.pure(Right("duplicate")))
      _ = assert(second.isLeft)
      _ <- release.complete(())
      _ <- first.joinWithNever
      later <- DelegateWakeLeg.exclusiveWake("test-recovery")(IO.pure(Right("later")))
      _ = assertEquals(later, Right("later"))
    yield ()
  }
  test("tool outcome must be present before recovery") {
    val call = Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse("tool-1", "write", io.circe.JsonObject.empty))))
    val result = Message(MessageRole.User, Right(List(ContentBlock.ToolResult("tool-1", "done", None))))
    assert(ResumeHandoff.hasUnresolvedToolCalls(List(call)))
    assert(!ResumeHandoff.hasUnresolvedToolCalls(List(call, result)))
  }
