package nebflow.agent

import munit.CatsEffectSuite
import nebflow.core.delegate.SessionResumption
import nebflow.core.delegate.SessionResumption.PendingGate
import nebflow.shared.SubAgentTask

/**
 * 待投递旗守门（c7a03e37 个案裁定，2026-11-22）：
 *  ① 释放腿 ∧ 终态（failed/cancelled）∧ 旗真 ⇒ 清旗（陈旧残留）；
 *  ② 非终态行的旗一律不碰（store 拒绝 + 纯判定 Refuse）；
 *  ③ 守门拒绝 = 干净判定，不进 admitted / 不 park（纯判定可断言，不触盘）。
 */
class SubAgentStalePendingSpec extends CatsEffectSuite:

  private def mkTask(id: String, status: String, pending: Option[Boolean], runId: Option[String]): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = "parent-stale",
      agentName = "nebflow",
      prompt = "stale pending fixture",
      description = "stale pending fixture",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate",
      runId = runId,
      notificationPending = pending
    )

  // ── 纯判定 ──────────────────────────────────────────────────────────

  test("gate: no pending flag proceeds regardless of status") {
    assertEquals(SessionResumption.pendingNotificationGate(None, gateReleased = true), PendingGate.Proceed)
    val row = mkTask("t1", "failed", Some(false), Some("r1"))
    assertEquals(SessionResumption.pendingNotificationGate(Some(row), gateReleased = true), PendingGate.Proceed)
  }

  test("gate: terminal stale flag on a gate-release leg is cleared") {
    val row = mkTask("t2", "failed", Some(true), Some("r2"))
    assertEquals(SessionResumption.pendingNotificationGate(Some(row), gateReleased = true), PendingGate.Clear(row))
    val cancelled = mkTask("t2b", "cancelled", Some(true), Some("r2b"))
    assertEquals(
      SessionResumption.pendingNotificationGate(Some(cancelled), gateReleased = true),
      PendingGate.Clear(cancelled)
    )
  }

  test("gate: non-terminal pending flag is refused, never cleared") {
    val running = mkTask("t3", "running", Some(true), Some("r3"))
    SessionResumption.pendingNotificationGate(Some(running), gateReleased = true) match
      case PendingGate.Refuse(reason) => assert(reason.startsWith("notification-pending"), reason)
      case other => fail(s"expected refusal for a non-terminal row, got $other")
  }

  test("gate: terminal flag without the release leg is refused (no clearing outside Mail release)") {
    val row = mkTask("t4", "failed", Some(true), Some("r4"))
    SessionResumption.pendingNotificationGate(Some(row), gateReleased = false) match
      case PendingGate.Refuse(_) => ()
      case other => fail(s"expected refusal without release leg, got $other")
  }

  test("gate: terminal flag without a runId is refused (nothing to bind the clear to)") {
    val row = mkTask("t5", "failed", Some(true), None)
    SessionResumption.pendingNotificationGate(Some(row), gateReleased = true) match
      case PendingGate.Refuse(_) => ()
      case other => fail(s"expected refusal without runId, got $other")
  }

  // ── 存储面：真实落盘行形态（终态 + 通知挂起，同 c7a03e37） ─────────────

  private def withStore(body: (SubAgentTaskStore, os.Path) => cats.effect.IO[Unit]): cats.effect.IO[Unit] =
    cats.effect.IO.blocking {
      val dir = os.temp.dir(prefix = "stale-pending-")
      (new SubAgentTaskStore(dir / "subagent-tasks"), dir)
    }.flatMap { case (store, dir) => body(store, dir).guarantee(cats.effect.IO.blocking(os.remove.all(dir))) }

  test("store: terminal failed row with a pending notification is cleared; run id is kept") {
    val taskId = "delegate-nebflow-stale-term"
    val runId = "run-stale-term"
    withStore { (store, _) =>
      val task = mkTask(taskId, "running", None, None)
      for
        _ <- store.beginRun(task, runId, "parent-stale", "nebflow")
        // 与 c7a03e37 同形：finished 终态写入时 notifyParent=true ⇒ pending=true
        _ <- store.completeRun("parent-stale", taskId, runId, "failed", reason = Some("boom"))
        before <- store.findByTaskId(taskId)
        cleared <- store.clearStalePendingNotification("parent-stale", taskId, runId)
        after <- store.findByTaskId(taskId)
      yield
        assertEquals(before.flatMap(_.notificationPending), Some(true), "fixture must reproduce the stale flag")
        assert(cleared.isRight, cleared.toString)
        assertEquals(after.flatMap(_.notificationPending), Some(false))
        assertEquals(after.map(_.runId), Some(Some(runId)))
        assertEquals(after.map(_.status), Some("failed"))
    }
  }

  test("store: non-terminal row keeps its pending flag (clear refused, row untouched)") {
    val taskId = "delegate-nebflow-stale-live"
    val runId = "run-stale-live"
    withStore { (store, _) =>
      val task = mkTask(taskId, "running", None, None)
      for
        _ <- store.beginRun(task, runId, "parent-stale", "nebflow")
        before <- store.findByTaskId(taskId)
        cleared <- store.clearStalePendingNotification("parent-stale", taskId, runId)
        after <- store.findByTaskId(taskId)
      yield
        assert(cleared.isLeft, cleared.toString)
        assertEquals(after, before, "a non-terminal row must not be touched")
    }
  }

end SubAgentStalePendingSpec
