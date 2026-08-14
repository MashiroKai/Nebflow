package nebflow.core.flow

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite

import java.util.UUID
import scala.concurrent.duration.*

/**
 * UT-6 + cancel-signal semantics for the flow timeout redesign (v2.1):
 * the between-nodes flag alone couldn't pierce a running node, so cancel()
 * now also completes a per-instance Deferred that the DAG executor races.
 */
class RunningFlowRegistrySpec extends FunSuite:

  private def mkFlow(id: String, status: NodeStatus = NodeStatus.Running): RunningFlowRegistry.RunningFlow =
    RunningFlowRegistry.RunningFlow(
      instanceId = id,
      flowName = "test-flow",
      description = "",
      entry = "n1",
      nodes = Map.empty,
      edges = Nil,
      status = status,
      startedAt = 0L
    )

  test("cancel completes the per-instance signal — get unblocks after cancel"):
    val id = s"sig-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      _ <- RunningFlowRegistry.register(mkFlow(id))
      sig <- RunningFlowRegistry.cancelSignal(id)
      // Before cancel: the signal must NOT be completed (timeout fires)
      before <- sig.get.timeout(150.millis).attempt
      _ <- RunningFlowRegistry.cancel(id)
      _ <- sig.get // unblocks instantly — would hang the test if broken
      isC <- RunningFlowRegistry.isCancelled(id)
    yield
      assert(before.isLeft, "signal must be pending before cancel")
      assert(isC, "cancel flag must be set")
    prog.unsafeRunSync()

  test("cancel is idempotent — repeated cancels do not throw and the signal stays completed"):
    val id = s"sig-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      _ <- RunningFlowRegistry.register(mkFlow(id))
      _ <- RunningFlowRegistry.cancel(id)
      _ <- RunningFlowRegistry.cancel(id) // second complete — must be a no-op
      _ <- RunningFlowRegistry.cancel(id) // third — flag update idempotent too
      _ <- RunningFlowRegistry.cancelSignal(id).flatMap(_.get)
      isC <- RunningFlowRegistry.isCancelled(id)
      status <- RunningFlowRegistry.list.map(_.find(_.instanceId == id).map(_.status))
    yield
      assert(isC)
      assertEquals(status, Some(NodeStatus.Cancelled))
    prog.unsafeRunSync()

  test("register and pre-created signal bind to the same Deferred (get-or-create reuse)"):
    val id = s"sig-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      sigPre <- RunningFlowRegistry.cancelSignal(id) // created before register
      _ <- RunningFlowRegistry.register(mkFlow(id)) // must reuse, not replace
      _ <- RunningFlowRegistry.cancel(id)
      _ <- sigPre.get // the PRE-created signal completes — proves reuse
    yield ()
    prog.unsafeRunSync()

  test("clearCancelled drops both the flag and the signal"):
    val id = s"sig-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      _ <- RunningFlowRegistry.register(mkFlow(id))
      _ <- RunningFlowRegistry.cancel(id)
      _ <- RunningFlowRegistry.clearCancelled(id)
      isC <- RunningFlowRegistry.isCancelled(id)
      fresh <- RunningFlowRegistry.cancelSignal(id)
      pending <- fresh.get.timeout(150.millis).attempt
    yield
      assert(!isC, "flag cleared")
      assert(pending.isLeft, "signal after clear is a fresh pending Deferred")
    prog.unsafeRunSync()

  test("remove drops the signal along with the flow entry"):
    val id = s"sig-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      _ <- RunningFlowRegistry.register(mkFlow(id))
      _ <- RunningFlowRegistry.remove(id)
      gone <- RunningFlowRegistry.list.map(_.exists(_.instanceId == id))
      fresh <- RunningFlowRegistry.cancelSignal(id)
      pending <- fresh.get.timeout(150.millis).attempt
    yield
      assert(!gone)
      assert(pending.isLeft, "signal removed with the flow entry")
    prog.unsafeRunSync()

  test("cleanupStale removes stale signals of evicted flows"):
    val id = s"sig-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      _ <- RunningFlowRegistry.register(mkFlow(id))
      _ <- RunningFlowRegistry.update(id)(_.copy(status = NodeStatus.Completed, completedAt = Some(0L)))
      _ <- RunningFlowRegistry.cleanupStale(retentionMs = 1L)
      gone <- RunningFlowRegistry.list.map(_.exists(_.instanceId == id))
      fresh <- RunningFlowRegistry.cancelSignal(id)
      pending <- fresh.get.timeout(150.millis).attempt
    yield
      assert(!gone, "stale completed flow evicted")
      assert(pending.isLeft, "its signal was evicted too")
    prog.unsafeRunSync()
end RunningFlowRegistrySpec
