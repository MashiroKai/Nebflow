package nebflow.agent

import cats.effect.IO
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.core.mail.SessionMailbox
import nebflow.shared.{Message, MessageRole, PathUtil}

/**
 * 邮箱消费口（[[MailboxDrain]]）的判红锚面：
 *
 *   - [[MailboxDrain.barrierPlan]] W2 批门禁纯函数（HOLD 本职保留、实现换轴）；
 *   - drain 三面（idle/barrier/replay）的盘面事务：取走=原子 rename、事件带
 *     viaMailbox 标、unlink=已投递物理事实 + 消费墓碑；
 *   - 屏障会计恰一次（takenSubagentSlots 只在取走拍减）。
 *
 * 全部纯桩驱动、零墙钟。resumeSweep 深检（冻结窗完工原文注入）见
 * [[MailboxRedAnchorSpec]]。
 */
class MailboxDrainSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-mbdrain"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    if tmp != null && os.exists(tmp) then os.remove.all(tmp)

  private def evt(
    payload: String,
    source: String = "delegate",
    eventType: String = "completed",
    identity: String = "task"
  ): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = source,
      eventType = eventType,
      payload = payload,
      metadata = JsonObject.empty,
      correlationId = Some(identity)
    )

  /** 纯内存 Entry（barrierPlan 纯函数面——不落盘）。 */
  private def entryOf(identity: String, source: String = "delegate"): SessionMailbox.Entry =
    SessionMailbox.Entry(
      seq = 1,
      identity = identity,
      kind = "delegate-terminal",
      coalesce = false,
      enqueuedAt = 0L,
      producer = "spec",
      suppressedCount = 0,
      event = evt(identity, source = source, identity = identity)
    )

  /** 落盘 Entry（drain 盘面用——走真 mailOnce）。 */
  private def entry(
    sid: String,
    identity: String,
    payload: String,
    sourceOverride: String = "delegate"
  ): IO[SessionMailbox.Entry] =
    SessionMailbox
      .mailOnce(
        sid,
        evt(payload, source = sourceOverride, identity = identity),
        kind = "delegate-terminal",
        identity = identity
      )
      .map(_.asInstanceOf[SessionMailbox.MailResult.Enqueued].entry)

  // ── barrierPlan：W2 批门禁纯函数 ────────────────────────────────────────

  test("barrierPlan: last sub-agent member arriving (outstanding == subRemaining) releases the batch") {
    val a = entryOf("a")
    val (take, hold) = MailboxDrain.barrierPlan(List(a), outstanding = 1)
    assertEquals(clue(take).map(_.identity), List("a"), "final member ⇒ take (batch complete)")
    assertEquals(clue(hold), Nil)
  }

  test("barrierPlan: members still in flight (outstanding > subRemaining) HOLD sub-agent entries") {
    val a = entryOf("a")
    val b = entryOf("b")
    val (take, hold) = MailboxDrain.barrierPlan(List(a, b), outstanding = 3)
    assertEquals(clue(hold).map(_.identity), List("a", "b"), "in-flight members ⇒ hold (HOLD 本职)")
    assertEquals(clue(take), Nil)
  }

  test("barrierPlan: full sub batch at exactly outstanding takes ALL") {
    val a = entryOf("a")
    val b = entryOf("b")
    val (take, hold) = MailboxDrain.barrierPlan(List(a, b), outstanding = 2)
    assertEquals(clue(take).map(_.identity), List("a", "b"))
    assertEquals(clue(hold), Nil)
  }

  test("barrierPlan: non-subagent entries (escalations etc.) are ALWAYS taken, sub sibling may hold") {
    val esc = entryOf("esc-1", source = "error-escalated")
    val a = entryOf("a")
    val (take, hold) = MailboxDrain.barrierPlan(List(a, esc), outstanding = 3)
    assertEquals(clue(take).map(_.identity), List("esc-1"), "escalations never held")
    assertEquals(clue(hold).map(_.identity), List("a"))
  }

  test("barrierPlan: takes keep seq relative order (scheduling predicate never reorders)") {
    val a = entryOf("a")
    val e1 = entryOf("e1", source = "error-escalated")
    val b = entryOf("b")
    val e2 = entryOf("e2", source = "error-escalated")
    val (take, hold) = MailboxDrain.barrierPlan(List(a, e1, b, e2), outstanding = 2)
    assertEquals(clue(take).map(_.identity), List("a", "e1", "b", "e2"), "relative FIFO order preserved")
    assertEquals(clue(hold), Nil)
  }

  test("barrierPlan: empty box or zero outstanding defensive faces") {
    assertEquals(clue(MailboxDrain.barrierPlan(Nil, 5)), (Nil, Nil))
    val a = entryOf("a")
    // outstanding=0：空批次防御面（0-1 < 0 ⇒ 放行，绝不因会计坏数扣留已产出结果）。
    val (take, hold) = MailboxDrain.barrierPlan(List(a), outstanding = 0)
    assertEquals(clue(take).map(_.identity), List("a"))
    assertEquals(clue(hold), Nil)
  }

  // ── drain 盘面：取走 + viaMailbox 标 + 屏障会计 ─────────────────────────

  test("drainIdleFaceSync takes ALL pending, stamps viaMailbox, leaves taken/ as the carrier") {
    val sid = "w3"
    for
      _ <- entry(sid, "t1", "one")
      _ <- entry(sid, "t2", "two")
      drained = MailboxDrain.drainIdleFaceSync(sid)
      pend <- SessionMailbox.scanPending(sid)
      taken <- SessionMailbox.scanTaken(sid)
    yield
      assertEquals(clue(drained.map(_.correlationId)), List(Some("t1"), Some("t2")))
      assert(clue(drained).forall(_.metadata("viaMailbox").exists(_.asBoolean.getOrElse(false))), "all stamped viaMailbox")
      assertEquals(clue(pend), Nil, "pending drained")
      assertEquals(clue(taken.map(_.identity)), List("t1", "t2"), "taken files are the post-take carrier")
  }

  test("drainBarrierFaceSync returns (events, held) and holds entries untouched on disk") {
    val sid = "w2"
    for
      _ <- entry(sid, "t1", "one")
      _ <- entry(sid, "t2", "two")
      _ <- entry(sid, "esc", "notice", sourceOverride = "error-escalated")
      heldNow <- IO(MailboxDrain.drainBarrierFaceSync(sid, outstanding = 3))
      pend <- SessionMailbox.scanPending(sid)
      depth <- SessionMailbox.depth(sid)
      released <- IO(MailboxDrain.drainBarrierFaceSync(sid, outstanding = 2))
      pendAfter <- SessionMailbox.scanPending(sid)
    yield
      assertEquals(clue(heldNow._1.map(_.correlationId)), List(Some("esc")), "escalation released, sub held")
      assertEquals(clue(heldNow._2), 2, "held count = in-flight sub entries")
      assertEquals(clue(pend.map(_.identity)), List("t1", "t2"), "held entries untouched (唯一拷贝在盘)")
      assertEquals(clue(depth), 2, "badge depth counts held entries")
      assertEquals(clue(released._1.map(_.correlationId)), List(Some("t1"), Some("t2")))
      assertEquals(clue(released._2), 0)
      assertEquals(clue(pendAfter), Nil)
  }

  test("takenSubagentSlots counts sub-agent events only — the take-time barrier accounting is exact-once") {
    val events = List(evt("a"), evt("esc", source = "error-escalated"), evt("b", source = "subtask"))
    assertEquals(clue(MailboxDrain.takenSubagentSlots(events)), 2)
  }

  test("replayTakenEventsSync re-injects taken residuals (crash window 3/4); unlink ends the replay") {
    val sid = "w14"
    for
      _ <- entry(sid, "t1", "residual payload")
      pend <- SessionMailbox.scanPending(sid)
      _ <- SessionMailbox.take(sid, pend)
      replay1 <- IO(MailboxDrain.replayTakenEventsSync(sid))
      replay2 <- IO(MailboxDrain.replayTakenEventsSync(sid))
      _ <- MailboxDrain.unlinkConsumed(sid, replay1)
      replay3 <- IO(MailboxDrain.replayTakenEventsSync(sid))
    yield
      assertEquals(clue(replay1.map(_.correlationId)), List(Some("t1")))
      assertEquals(clue(replay2.map(_.correlationId)), List(Some("t1")), "idempotent until unlink")
      assert(clue(replay1.head.metadata("viaMailbox")).isDefined, "replayed events carry the mailbox stamp")
      assertEquals(clue(replay3), Nil, "unlink = delivered")
  }

  test("mailboxHeldCount reads pending depth without consuming") {
    val sid = "held"
    for
      _ <- entry(sid, "t1", "one")
      _ <- entry(sid, "t2", "two")
      n1 <- IO(MailboxDrain.mailboxHeldCount(sid))
      n2 <- IO(MailboxDrain.mailboxHeldCount(sid))
    yield
      assertEquals(clue(n1), 2)
      assertEquals(clue(n2), 2, "pure read — zero consumption")
  }

  // ── 呈现组装：单件直投形 / 多件合批形 ───────────────────────────────────

  test("mailboxMessage: single event = direct-injection form with payload verbatim + source marker") {
    val msg = MailboxDrain.mailboxMessage(List(evt("FINAL RESULT: all tests green")))
    assertEquals(clue(msg.role), MessageRole.User)
    assert(
      clue(msg.textContent).contains("FINAL RESULT: all tests green"),
      "completion payload MUST reach the parent verbatim (10-31 anchor)"
    )
    assertEquals(clue(msg.source), Some("delegate"), "single delegate event keeps the delegate marker")
  }

  test("mailboxMessage: retryable failure carries the re-delegation hint") {
    val failed = evt("boom", source = "delegate", eventType = "failed")
      .copy(metadata = JsonObject(
        "retryable" -> io.circe.Json.True,
        "failureType" -> io.circe.Json.fromString("overloaded"),
        "agentName" -> io.circe.Json.fromString("worker")
      ))
    val msg = MailboxDrain.mailboxMessage(List(failed))
    assert(clue(msg.textContent).contains("boom"))
    assert(clue(msg.textContent).contains("retryable error"), "failed+retryable ⇒ re-delegation guidance")
  }

  test("mailboxMessage: many events = numbered reminder batch form") {
    val msgs = List(evt("first"), evt("second"))
    val msg = MailboxDrain.mailboxMessage(msgs)
    val text = clue(msg.textContent)
    assert(text.startsWith("<system-reminder>"), s"batch form wraps in reminder, got: ${text.take(40)}")
    assert(text.contains("1. first") && text.contains("2. second"))
    assertEquals(clue(msg.source), Some("external"), "batch form marker = external")
  }
end MailboxDrainSpec
