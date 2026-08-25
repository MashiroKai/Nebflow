package nebflow.core.flow

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
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

  // ---------- #414 fix 2：setNodeStatus 只更新节点状态，不动 flow 整体 status ----------

  test("#414 node status updates never touch the flow-level status (entry completed ≠ flow completed)"):
    val id = s"st-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      _ <- RunningFlowRegistry.register(
        mkFlow(id).copy(nodes = Map("n1" -> RunningFlowRegistry.NodeState("n1", "a", NodeStatus.Pending)))
      )
      // entry 节点（n1）完成——修复前 flow 被误标 completed 且刷新 completedAt
      _ <- RunningFlowRegistry.setNodeStatus(id, "n1", NodeStatus.Completed, "out")
      _ <- RunningFlowRegistry.setNodeStatus(id, "n2", NodeStatus.Running)
      flow <- RunningFlowRegistry.list.map(_.find(_.instanceId == id).get)
    yield
      assertEquals(flow.status, NodeStatus.Running, "flow must stay running while the DAG continues")
      assertEquals(flow.completedAt, None, "completedAt must not be set by node-level updates")
      assertEquals(flow.nodes("n1").status, NodeStatus.Completed, "node status still recorded")
    prog.unsafeRunSync()

  test("#414 terminal flow status only via terminate paths (execute-finalize / cancel)"):
    val id = s"tm-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      _ <- RunningFlowRegistry.register(mkFlow(id))
      // 节点 Failed 也不改 flow 整体 status（walk 失败由 execute 末尾统一置 Failed）
      _ <- RunningFlowRegistry.setNodeStatus(id, "n1", NodeStatus.Failed, "", "boom")
      afterNode <- RunningFlowRegistry.list.map(_.find(_.instanceId == id).get.status)
      // execute 末尾模拟：update(copy(status=Completed)) → 终态 + completedAt
      _ <- RunningFlowRegistry.update(id)(rf =>
        rf.copy(status = NodeStatus.Completed, completedAt = Some(42L))
      )
      flow <- RunningFlowRegistry.list.map(_.find(_.instanceId == id).get)
    yield
      assertEquals(afterNode, NodeStatus.Running, "node failure alone must not mark the flow failed")
      assertEquals(flow.status, NodeStatus.Completed)
      assertEquals(flow.completedAt, Some(42L))
    prog.unsafeRunSync()

  // ---------- #412：toJson 序列化 sessionId / rootSessionId（badge 归属 / 窗口路由）----------

  test("#412 toJson serializes sessionId + rootSessionId"):
    val id = s"tj-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      _ <- RunningFlowRegistry.register(
        mkFlow(id).copy(sessionId = Some("caller-1"), rootSessionId = Some("root-1"))
      )
      json <- RunningFlowRegistry.listJson.map { j =>
        j.hcursor
          .downField("flows")
          .as[List[Json]]
          .toOption
          .get
          .find(_.hcursor.downField("instanceId").as[String].contains(id))
          .get
      }
    yield
      assertEquals(json.hcursor.downField("sessionId").as[Option[String]], Right(Some("caller-1")))
      assertEquals(json.hcursor.downField("rootSessionId").as[Option[String]], Right(Some("root-1")))
    prog.unsafeRunSync()

  test("#412 toJson serializes None ownership as null (backward compat)"):
    val id = s"tj2-${UUID.randomUUID().toString.take(8)}"
    val prog = for
      _ <- RunningFlowRegistry.register(mkFlow(id)) // no session fields
      json <- RunningFlowRegistry.listJson.map { j =>
        j.hcursor
          .downField("flows")
          .as[List[Json]]
          .toOption
          .get
          .find(_.hcursor.downField("instanceId").as[String].contains(id))
          .get
      }
    yield
      // None ownership serializes as JSON null → decodes back to None
      assertEquals(json.hcursor.downField("sessionId").as[Option[String]], Right(None))
      assertEquals(json.hcursor.downField("rootSessionId").as[Option[String]], Right(None))
    prog.unsafeRunSync()
end RunningFlowRegistrySpec
