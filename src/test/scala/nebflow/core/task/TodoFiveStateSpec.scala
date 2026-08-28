package nebflow.core.task

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

/** todo-panel v2 backend contract (spec ~/.nebflow/docs/Nebflow/todo-panel-spec.md):
  * - B1  five-state transition matrix (§2.2) incl. needs_confirmation lanes
  * - B6/B7 return() semantics: needs_confirmation -> in_progress + returnCount
  *       + feedback note + events; re-confirmation loop; empty feedback
  * - B9  human todos stay out of needs_confirmation
  * - B10/C15 agent path may not reach completed directly
  * - B10/C22 agent may not re-judge failed while awaiting ruling
  * - legacy JSON without returnCount decodes as 0 (withDefaults red line)
  * - renderForPrompt marks needs_confirmation tasks
  */
class TodoFiveStateSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-todo-five-state"
  PathUtil.setDataRoot(tempRoot)

  private val store: TaskStore = FileTaskStore

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot) }

  private def mkAgentTask(sessionId: String, subject: String = "s"): IO[String] =
    store.create(sessionId, TaskCreateInput(subject = subject, description = "d"))

  private def toNeedsConfirmation(sessionId: String, taskId: String, outcomeNote: Option[String] = None): IO[Unit] =
    store
      .update(sessionId, taskId, TaskUpdateInput(status = Some(TaskStatus.InProgress)))
      .flatMap(_ =>
        store.update(sessionId, taskId, TaskUpdateInput(
          status = Some(TaskStatus.NeedsConfirmation), note = outcomeNote
        ))
      )
      .void

  // ── B1: transition matrix (agent TaskUpdate path unless noted) ──────

  test("B1: pending -> needs_confirmation is legal (fast-complete lane)"):
    for
      _ <- reset()
      sid = "b1-fast"
      tid <- mkAgentTask(sid)
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.NeedsConfirmation)))
      t <- store.get(sid, tid)
    yield assertEquals(t.get.status, TaskStatus.NeedsConfirmation)

  test("B1: in_progress -> needs_confirmation is legal (the completion lane)"):
    for
      _ <- reset()
      sid = "b1-lane"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      t <- store.get(sid, tid)
    yield assertEquals(t.get.status, TaskStatus.NeedsConfirmation)

  test("B1: needs_confirmation no-op update is legal (idempotent)"):
    for
      _ <- reset()
      sid = "b1-noop"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      r <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.NeedsConfirmation))).attempt
    yield assert(r.isRight)

  test("B1/2026-08-24: needs_confirmation -> in_progress via agent TaskUpdate WITH note is legal (dialogue feedback)"):
    for
      _ <- reset()
      sid = "b1-agentback"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      r <- store.update(sid, tid, TaskUpdateInput(
        status = Some(TaskStatus.InProgress),
        note = Some("用户对话反馈：卡片样式要改成毛玻璃")
      )).attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isRight, s"agent must be able to self-return on dialogue feedback: $r")
      assertEquals(after.get.status, TaskStatus.InProgress)
      assert(after.get.notes.map(_.content).contains("用户对话反馈：卡片样式要改成毛玻璃"), "feedback must be recorded as a note")

  test("B1/2026-08-24: needs_confirmation -> in_progress WITHOUT note is REJECTED (anti-abuse)"):
    for
      _ <- reset()
      sid = "b1-agentback-nonote"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      r <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress))).attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isLeft, "self-return without a note must be rejected")
      assert(r.left.toOption.get.getMessage.contains("note"), s"error should mention the note requirement: ${r.left.toOption.get.getMessage}")
      assertEquals(after.get.status, TaskStatus.NeedsConfirmation, "task must stay awaiting ruling after rejected return")

  test("B1/2026-08-24: needs_confirmation -> in_progress with blank note is REJECTED (anti-abuse)"):
    for
      _ <- reset()
      sid = "b1-agentback-blank"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      r <- store.update(sid, tid, TaskUpdateInput(
        status = Some(TaskStatus.InProgress), note = Some("   ")
      )).attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isLeft, "blank note must be rejected")
      assertEquals(after.get.status, TaskStatus.NeedsConfirmation)

  test("B1: needs_confirmation -> completed via complete(by=user) is legal (user confirmation)"):
    for
      _ <- reset()
      sid = "b1-confirm"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      r <- store.complete(sid, tid, by = "user")
      after <- store.get(sid, tid)
    yield
      assert(r.isDefined)
      assertEquals(after.get.status, TaskStatus.Completed)
      assertEquals(after.get.completedBy, Some("user"))

  test("B1: needs_confirmation -> dismissed is legal (user dismiss)"):
    for
      _ <- reset()
      sid = "b1-dismiss"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      _ <- store.dismiss(sid, tid)
      after <- store.get(sid, tid)
    yield assertEquals(after.get.status, TaskStatus.Dismissed)

  // ── B10 / C15: agent may not reach completed directly ───────────────

  test("B10/C15: agent in_progress -> completed via TaskUpdate is REJECTED"):
    for
      _ <- reset()
      sid = "c15-inprog"
      tid <- mkAgentTask(sid)
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress)))
      r <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.Completed))).attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isLeft, "C15: completed is reserved for user confirmation")
      assertEquals(after.get.status, TaskStatus.InProgress)

  test("B10/C15: agent pending -> completed via TaskUpdate is REJECTED"):
    for
      _ <- reset()
      sid = "c15-pending"
      tid <- mkAgentTask(sid)
      r <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.Completed))).attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isLeft)
      assertEquals(after.get.status, TaskStatus.Pending)

  test("B10/C15: same task reaching needs_confirmation succeeds where completed failed"):
    for
      _ <- reset()
      sid = "c15-ok"
      tid <- mkAgentTask(sid)
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress)))
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.Completed))).attempt // rejected
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.NeedsConfirmation)))
      t <- store.get(sid, tid)
    yield assertEquals(t.get.status, TaskStatus.NeedsConfirmation)

  test("C15: human pending -> completed via agent TaskUpdate stays legal (agent checks off human todo)"):
    for
      _ <- reset()
      sid = "c15-human"
      tid <- store.create(sid, TaskCreateInput(subject = "h", description = "d", taskKind = Some("human")))
      r <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.Completed))).attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isRight, "human todo check-off by agent is unaffected by C15")
      assertEquals(after.get.status, TaskStatus.Completed)

  // ── B10 / C22: no re-judging while awaiting ruling ───────────────────

  test("B10/C22: needs_confirmation -> failed via agent TaskUpdate is REJECTED"):
    for
      _ <- reset()
      sid = "c22"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      r <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.Failed))).attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isLeft, "C22: user ruling takes precedence over agent re-judging")
      assertEquals(after.get.status, TaskStatus.NeedsConfirmation)

  // ── B9: human todos stay out of needs_confirmation ──────────────────

  test("B9: human pending -> needs_confirmation via TaskUpdate is REJECTED"):
    for
      _ <- reset()
      sid = "b9"
      tid <- store.create(sid, TaskCreateInput(subject = "h", description = "d", taskKind = Some("human")))
      r <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.NeedsConfirmation))).attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isLeft)
      assertEquals(after.get.status, TaskStatus.Pending)

  test("B9: human todo return() raises (no return concept)"):
    for
      _ <- reset()
      sid = "b9-ret"
      tid <- store.create(sid, TaskCreateInput(subject = "h", description = "d", taskKind = Some("human")))
      r <- store.`return`(sid, tid, "please redo").attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isLeft, "human todos cannot be returned")
      assertEquals(after.get.status, TaskStatus.Pending)

  // ── B6/B7: return() semantics ────────────────────────────────────────

  test("B6: return converts to in_progress + returnCount=1 + feedback note + events"):
    for
      _ <- reset()
      sid = "b6"
      tid <- mkAgentTask(sid, "Implement dark mode")
      _ <- toNeedsConfirmation(sid, tid, Some("Implemented via CSS variables"))
      r <- store.`return`(sid, tid, "第 2 条结论不对，重做")
      after <- store.get(sid, tid)
    yield
      assert(r.isDefined)
      assertEquals(after.get.status, TaskStatus.InProgress, "returned task is back in progress")
      assertEquals(after.get.returnCount, 1)
      assertEquals(after.get.notes.lastOption.map(_.content), Some("第 2 条结论不对，重做"), "C20: feedback lands in notes")
      val statusEv = after.get.events.filter(_.kind == "status").lastOption
      assertEquals(statusEv.flatMap(_.detail), Some("needs_confirmation→in_progress"))
      assert(after.get.events.exists(_.kind == "returned"), "returned event recorded")

  test("B6: multi-round return accumulates returnCount (needs_confirmation ⇄ in_progress loop)"):
    for
      _ <- reset()
      sid = "b6-multi"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      _ <- store.`return`(sid, tid, "first feedback")
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.NeedsConfirmation)))
      _ <- store.`return`(sid, tid, "second feedback")
      after <- store.get(sid, tid)
    yield
      assertEquals(after.get.returnCount, 2)
      assertEquals(
        after.get.notes.map(_.content),
        List("first feedback", "second feedback"),
        "revision history accumulates (C20)"
      )

  test("B7: return with empty feedback is legal and adds no note"):
    for
      _ <- reset()
      sid = "b7-empty"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid, Some("outcome"))
      r <- store.`return`(sid, tid, "")
      after <- store.get(sid, tid)
    yield
      assert(r.isDefined)
      assertEquals(after.get.status, TaskStatus.InProgress)
      assertEquals(after.get.returnCount, 1)
      assertEquals(after.get.notes.map(_.content), List("outcome"), "no empty note appended")

  test("B8: return on non-needs_confirmation task raises with state in message"):
    for
      _ <- reset()
      sid = "b8"
      pendingId <- mkAgentTask(sid, "pending one")
      r1 <- store.`return`(sid, pendingId, "x").attempt
      after1 <- store.get(sid, pendingId)
      completedId <- mkAgentTask(sid, "completed one")
      _ <- store.complete(sid, completedId, by = "user")
      r2 <- store.`return`(sid, completedId, "x").attempt
      after2 <- store.get(sid, completedId)
    yield
      assert(r1.isLeft, "pending task cannot be returned")
      assert(r1.swap.toOption.get.getMessage.contains("pending"))
      assertEquals(after1.get.status, TaskStatus.Pending, "status unchanged after rejection")
      assert(r2.isLeft, "already-confirmed task cannot be returned")
      assertEquals(after2.get.status, TaskStatus.Completed)

  test("return on missing task returns None"):
    for
      _ <- reset()
      r <- store.`return`("b-missing", "999", "x")
    yield assert(r.isEmpty)

  // ── B7: injection block format (WS layer builds it from this function) ──

  test("B7: returnInjectionBlock carries id/subject/feedback ONLY (payload-slim 2026-08-27)"):
    for
      _ <- reset()
      sid = "b7-inject"
      // long distinctive description so the 20-char fragment probe is
      // meaningful (mkAgentTask's default "d" would substring-match anywhere)
      tid <- store.create(sid, TaskCreateInput(
        subject = "Implement dark mode",
        description = "a long task description that must never leak into the return payload"))
      _ <- toNeedsConfirmation(sid, tid, Some("done via CSS variables, commit abc123"))
      snapshot <- store.get(sid, tid)
      _ <- store.`return`(sid, tid, "第 2 条结论不对，重做")
      after <- store.get(sid, tid)
      block = Task.returnInjectionBlock(tid, snapshot.get, "第 2 条结论不对，重做")
      desc = snapshot.get.description
      output = "done via CSS variables, commit abc123"
    yield
      // (a) kept: taskId + subject + feedback FULL TEXT
      assert(block.contains(s"[打回任务 #$tid: Implement dark mode]"))
      assert(block.contains("用户意见: 第 2 条结论不对，重做"), "feedback must reach the agent verbatim")
      assert(block.contains("已回到进行中"))
      // (b) dropped: no 20-char consecutive fragment of description/output
      def frag(s: String, n: Int = 20): String = if s.length <= n then s else s.slice(4, 4 + n)
      assert(!block.contains(frag(desc)), s"description leaked into payload: ${frag(desc)}")
      assert(!block.contains(frag(output)), s"output leaked into payload: ${frag(output)}")
      assert(!block.contains("任务描述:"), "description field line removed")
      assert(!block.contains("产出:"), "output field line removed")
      // post-return notes DO contain the feedback (C20) — separate concern
      assertEquals(after.get.notes.lastOption.map(_.content), Some("第 2 条结论不对，重做"))

  test("B7: injection block with empty feedback says （未附意见）"):
    val snapshot = Task(id = "7", subject = "s", description = "a description that is longer than twenty characters for the fragment probe", notes = Nil)
    val block = Task.returnInjectionBlock("7", snapshot, "")
    assert(block.contains("用户意见: （未附意见）"))
    assert(!block.contains("产出:"), "no output line even with empty notes")
    assert(!block.contains("twenty characters for"), "description fragment must not leak")

  // ── compatibility red line ───────────────────────────────────────────

  test("legacy task JSON without returnCount decodes as 0 (withDefaults)"):
    val legacy =
      """{"id":"1","subject":"old","description":"legacy","status":"pending"}"""
    decode[Task](legacy) match
      case Right(t) => assertEquals(t.returnCount, 0)
      case Left(err) => fail(s"legacy decode failed: $err}")

  test("needs_confirmation round-trips through the wire codec"):
    val t = Task(id = "1", subject = "s", description = "d", status = TaskStatus.NeedsConfirmation, returnCount = 3)
    decode[Task](t.asJson.noSpaces) match
      case Right(back) =>
        assertEquals(back.status, TaskStatus.NeedsConfirmation)
        assertEquals(back.returnCount, 3)
      case Left(err) => fail(s"round-trip failed: $err}")

  // ── prompt rendering ─────────────────────────────────────────────────

  test("renderForPrompt marks needs_confirmation tasks (agent must not re-work them)"):
    for
      _ <- reset()
      sid = "render-v2"
      tid <- mkAgentTask(sid, "awaiting one")
      _ <- toNeedsConfirmation(sid, tid)
      _ <- mkAgentTask(sid, "plain pending")
      text <- store.renderForPrompt(sid)
    yield
      assert(text.contains("[needs_confirmation]"), s"marker present; got:\n$text")
      assert(text.contains("awaiting one"))
      assert(text.contains("[pending]"), "other tasks still render")
      assert(
        text.toLowerCase.contains("needs_confirmation"),
        "header explains the completion lane"
      )

  test("listActive includes needs_confirmation (non-terminal, counts as active)"):
    for
      _ <- reset()
      sid = "active-v2"
      tid <- mkAgentTask(sid)
      _ <- toNeedsConfirmation(sid, tid)
      other <- mkAgentTask(sid, "plain")
      _ <- store.update(sid, other, TaskUpdateInput(status = Some(TaskStatus.Failed)))
      active <- store.listActive(sid)
    yield
      assertEquals(active.map(_.id), List(tid), "needs_confirmation is active; failed is not")

end TodoFiveStateSpec
