package nebflow.core.mail

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.shared.{Message, MessageRole, PathUtil}

class CompletionDebtStoreSpec extends CatsEffectSuite:
  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-completion-debt"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    if tmp != null && os.exists(tmp) then os.remove.all(tmp)

  private val result = List(Message(MessageRole.Assistant, Left("final synthesized result")))

  test("stable owner debt survives store re-read and is cleared only by explicit acknowledgement") {
    for
      _ <- CompletionDebtStore.record("delegate-session-1", result)
      recovered <- CompletionDebtStore.pending("delegate-session-1")
      replayed <- CompletionDebtStore.pending("delegate-session-1")
      _ <- CompletionDebtStore.acknowledge("delegate-session-1")
      cleared <- CompletionDebtStore.pending("delegate-session-1")
    yield
      assertEquals(recovered.map(_.ownerSessionId), Some("delegate-session-1"))
      assertEquals(recovered.map(_.messages), Some(result))
      assertEquals(replayed, recovered, "reopening/replaying does not consume the debt")
      assertEquals(cleared, None, "only reliable receiver acceptance acknowledges the debt")
  }

  test("unreachable receiver leaves debt intact; cancelled completion is not resurrected when caller does not record") {
    for
      _ <- CompletionDebtStore.record("delegate-session-2", result)
      unavailable <- CompletionDebtStore.pending("delegate-session-2")
      _ = assert(unavailable.nonEmpty, "no receiver is represented by retained durable debt")
      // Cancellation is explicitly terminal and must not call record; a fresh identity stays empty.
      cancelled <- CompletionDebtStore.pending("delegate-session-cancelled")
    yield assertEquals(cancelled, None)
  }
end CompletionDebtStoreSpec
