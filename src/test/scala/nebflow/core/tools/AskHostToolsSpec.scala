package nebflow.core.tools

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentDef, AgentKind, AgentRecord}
import nebflow.agent.SharedResources
import nebflow.llm.ModelCandidate
import nebflow.shared.{PathUtil, ThinkingConfig}

class AskHostToolsSpec extends CatsEffectSuite:

  test("takeover admission requires trusted root, outstanding marked request and browser ownership") {
    val pending = AskHostLedger.Pending("req", "worker", "root", "agent", "call")
    val marked = AskHostLedger.RequestState(pending, "[BU-TAKEOVER]\nsession: work", None, Nil)
    val registry = new nebflow.core.browser.BrowserTakeoverRegistry
    registry.created("work", "worker")
    assert(AskHostTool.admitBrowserTakeover(false, "root", Some(marked), "work", registry).isLeft)
    assert(AskHostTool.admitBrowserTakeover(true, "other-root", Some(marked), "work", registry).isLeft)
    assert(AskHostTool.admitBrowserTakeover(true, "root", Some(marked.copy(question = "ordinary")), "work", registry).isLeft)
    assert(AskHostTool.admitBrowserTakeover(true, "root", Some(marked.copy(terminal = Some(AskHostLedger.Terminal.Answered("done")))), "work", registry).isLeft)
    assert(AskHostTool.admitBrowserTakeover(true, "root", Some(marked), "unknown", registry).isLeft)
    val other = marked.copy(pending = pending.copy(workerSession = "intruder"))
    assert(AskHostTool.admitBrowserTakeover(true, "root", Some(other), "work", registry).isLeft)
    assertEquals(AskHostTool.admitBrowserTakeover(true, "root", Some(marked), "work", registry), Right(()))
  }

  test("BU takeover marker is recognized only as the exact leading line") {
    assert(AskHostTool.isBrowserTakeover("[BU-TAKEOVER]\nsession: work\nscreenshot: /tmp/a.png"))
    assert(!AskHostTool.isBrowserTakeover("ordinary [BU-TAKEOVER]"))
    assert(!AskHostTool.isBrowserTakeover("[BU-TAKEOVER] suffix"))
  }

  test("AskHost ledger persists pending source/question and accepts one correlated decision") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    val check = for
      begun <- AskHostLedger.begin("worker-1", "root-1", "worker-agent", "choose a mode")
      (pending, reply) = begun
      rows1 <- IO(os.read.lines(tmp / "askhost" / "ledger.jsonl"))
      _ = assert(rows1.head.contains(pending.correlationId))
      _ = assert(rows1.head.contains("choose a mode"))
      _ = assert(rows1.head.contains("worker-1"))
      accepted <- AskHostLedger.answer(pending.correlationId, "reject: unsafe")
      result <- reply.get
      repeated <- AskHostLedger.answer(pending.correlationId, "different")
      recovered <- AskHostLedger.recover
      rows2 <- IO(os.read.lines(tmp / "askhost" / "ledger.jsonl"))
      _ = assert(accepted)
      _ = assertEquals(result, "reject: unsafe")
      _ = assert(!repeated)
      _ = assert(
        recovered.find(_.pending.correlationId == pending.correlationId).flatMap(_.answer).contains("reject: unsafe")
      )
      _ = assert(rows2.exists(_.contains("answered")))
    yield ()
    check.guarantee(IO {
      PathUtil.setDataRoot(previous)
      AskHostLedger.clearForTests()
      os.remove.all(tmp)
    })
  }

  test("AskHost journal replay restores unanswered requests and treats delivered as terminal") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    val check = for
      (pending, _) <- AskHostLedger.begin("worker-replay", "root-replay", "kernel", "question survives restart")
      first <- AskHostLedger.recover
      _ <- AskHostLedger.markUnavailable(pending.correlationId, "worker session unavailable")
      second <- AskHostLedger.recover
      _ = assertEquals(first.map(_.pending.correlationId), List(pending.correlationId))
      _ = assert(second.isEmpty)
    yield ()
    check.guarantee(IO {
      PathUtil.setDataRoot(previous)
      AskHostLedger.clearForTests()
      os.remove.all(tmp)
    })
  }

  test("crash recovery accepts a delayed answer by the original request id without a live waiter") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (pending, _) <- AskHostLedger.begin("worker", "root", "agent", "first")
      _ <- IO(AskHostLedger.clearForTests())
      accepted <- AskHostLedger.answer(pending.correlationId, "decision")
      restored <- AskHostLedger.restoreWaiter(pending.correlationId)
      result <- restored.get.timeout(scala.concurrent.duration.DurationInt(3).seconds)
      _ = assert(accepted)
      _ = assertEquals(result, "decision")
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("duplicate decision is accepted only once and distinct pending requests remain independent") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (a, _) <- AskHostLedger.begin("worker", "root", "agent", "one")
      (b, _) <- AskHostLedger.begin("worker", "root", "agent", "two")
      accepted <- AskHostLedger.answer(a.correlationId, "yes")
      duplicate <- AskHostLedger.answer(a.correlationId, "yes")
      rows <- AskHostLedger.recover
      _ = assert(accepted && !duplicate)
      _ = assertEquals(rows.size, 2)
      _ = assertNotEquals(a.correlationId, b.correlationId)
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("recovery repeatedly reattaches to the same request and preserves distinct calls") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (a, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "one", "call-a")
      (b, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "two", "call-b")
      _ <- IO(AskHostLedger.clearForTests())
      (again, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "one", "call-a")
      _ <- IO(AskHostLedger.clearForTests())
      (third, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "one", "call-a")
      rows <- AskHostLedger.recover
      _ = assertEquals(again.correlationId, a.correlationId)
      _ = assertEquals(third.correlationId, a.correlationId)
      _ = assertNotEquals(a.correlationId, b.correlationId)
      _ = assertEquals(rows.size, 2)
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("request replay is suppressed after the mailbox has taken and consumed it") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    val event =
      nebflow.actor.AgentCommand.ExternalEvent("askhost", "request", "question", JsonObject.empty, Some("request-a"))
    import nebflow.core.mail.SessionMailbox
    (for
      first <- SessionMailbox.mailOnce("root", event, "askhost-request", "request-a", deduplicateReceipts = true)
      entries <- SessionMailbox.scanPending("root")
      _ <- SessionMailbox.take("root", entries)
      during <- SessionMailbox.mailOnce("root", event, "askhost-request", "request-a", deduplicateReceipts = true)
      _ <- SessionMailbox.unlinkTaken("root", List("request-a"))
      after <- SessionMailbox.mailOnce("root", event, "askhost-request", "request-a", deduplicateReceipts = true)
      _ = assert(first.isInstanceOf[SessionMailbox.MailResult.Enqueued])
      _ = assertEquals(during, SessionMailbox.MailResult.AlreadyPresent)
      _ = assertEquals(after, SessionMailbox.MailResult.AlreadyPresent)
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("recovered handoff waits for the original request and returns its decision as the original tool result") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    import nebflow.shared.{Message, MessageRole, ContentBlock}
    import nebflow.core.ResumeHandoff
    val transcript =
      List(Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse("call-a", "AskHost", JsonObject.empty)))))
    (for
      (request, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "one", "call-a")
      _ <- IO(AskHostLedger.clearForTests())
      safe <- ResumeHandoff.recoverableAskHostOnly("worker", transcript)
      p = ResumeHandoff.plan(transcript, ResumeHandoff.Cause.EngineRestart)
      fiber <- ResumeHandoff.resolveAskHost("worker", p).start
      _ <- AskHostLedger.answer(request.correlationId, "approved")
      resolved <- fiber.joinWithNever
      results = resolved.injectBlocks.toList.flatten.collect { case x: ContentBlock.ToolResult => x }
      _ = assert(safe)
      _ = assertEquals(results.map(r => (r.toolUseId, r.content, r.isError)), List(("call-a", "approved", Some(false))))
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("two simultaneous calls retain separate identities and never exchange decisions") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      start <- Deferred[IO, Unit]
      firstReady <- Deferred[IO, Unit]
      secondReady <- Deferred[IO, Unit]
      first <- (firstReady
        .complete(()) *> start.get *> AskHostLedger.beginForCall("worker", "root", "agent", "first", "call-a")).start
      second <- (secondReady
        .complete(()) *> start.get *> AskHostLedger.beginForCall("worker", "root", "agent", "second", "call-b")).start
      _ <- firstReady.get *> secondReady.get *> start.complete(())
      (a, wa) <- first.joinWithNever
      (b, wb) <- second.joinWithNever
      _ <- (
        AskHostLedger.answer(a.correlationId, "answer-a"),
        AskHostLedger.answer(b.correlationId, "answer-b")
      ).parTupled
      pair <- (wa.get, wb.get).parTupled
      _ = assertNotEquals(a.correlationId, b.correlationId)
      _ = assertEquals(pair, ("answer-a", "answer-b"))
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("answered request closes only after matching tool result is committed") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    import nebflow.shared.{Message, MessageRole, ContentBlock}
    (for
      (request, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "q", "call-a")
      _ <- AskHostLedger.answer(request.correlationId, "yes")
      _ <- AskHostLedger.closeCommittedResults("worker", Nil)
      before <- AskHostLedger.recover
      result = Message(MessageRole.User, Right(List(ContentBlock.ToolResult("call-a", "yes", Some(false)))))
      _ <- AskHostLedger.closeCommittedResults("worker", List(result))
      after <- AskHostLedger.recover
      _ = assertEquals(before.size, 1)
      _ = assertEquals(after.size, 0)
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("cancellation wins before an answer without a second terminal transition") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (request, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "q", "cancel-first")
      cancelled <- AskHostLedger.cancel(request.correlationId, "user cancelled")
      accepted <- AskHostLedger.answer(request.correlationId, "late")
      rows <- AskHostLedger.recover
      _ = assert(cancelled && !accepted && rows.isEmpty)
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("answer wins before cancellation and remains recoverable") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (request, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "q", "answer-first")
      accepted <- AskHostLedger.answer(request.correlationId, "yes")
      cancelled <- AskHostLedger.cancel(request.correlationId, "user cancelled")
      rows <- AskHostLedger.recover
      _ = assert(accepted && !cancelled)
      _ = assertEquals(rows.flatMap(_.answer), List("yes"))
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("legacy requests lacking call identity are not re-created as recovery requests") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (legacy, _) <- AskHostLedger.begin("old-worker", "root", "agent", "old question")
      (current, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "new question", "call-1")
      _ <- AskHostLedger.expireLegacy()
      rows <- AskHostLedger.recover
      _ = assertEquals(rows.map(_.pending.correlationId), List(current.correlationId))
      _ = assert(!rows.exists(_.pending.correlationId == legacy.correlationId))
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("AskUserQuestion remains non-blocking and keeps its existing question schema") {
    val required = AskUserQuestionTool.inputSchema("required").flatMap(_.asArray).getOrElse(Vector.empty)
    assertEquals(required.flatMap(_.asString), Vector("questions"))
    assert(!AskUserQuestionTool.inputSchema("properties").flatMap(_.hcursor.downField("mode").success).isDefined)
  }

  test("AskHost tool schema is correlation-free and requires only a question") {
    val required = AskHostTool.inputSchema("required").flatMap(_.asArray).getOrElse(Vector.empty)
    assertEquals(required.flatMap(_.asString), Vector("question"))
    assert(!AskHostTool.inputSchema("properties").flatMap(_.hcursor.downField("correlationId").success).isDefined)
  }

  // ══════════════════════════════════════════════════════════════════════════
  // AskHost late reply after timeout (defect 2026-11-19; author ruling 13:49)
  // ══════════════════════════════════════════════════════════════════════════

  private val lateReplySystem = nebflow.actor.ActorSystem("askhost-late-reply-spec")

  private def liveRef(): nebflow.actor.ActorRef[nebflow.actor.AgentCommand] =
    lateReplySystem
      .spawn(
        nebflow.actor.Behaviors
          .receiveMessage[nebflow.actor.AgentCommand](_ => IO.pure(nebflow.actor.Behaviors.stopped)),
        s"ar-${java.util.UUID.randomUUID().toString.take(8)}"
      )
      .unsafeRunSync()

  private def resourcesWith(registry: Map[String, AgentRecord]): SharedResources =
    new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = lateReplySystem,
      agentRegistry = Ref.unsafe[IO, Map[String, AgentRecord]](registry),
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )

  private def rootCtx(resources: SharedResources): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-root"),
      agentDef = Some(AgentDef(name = "Nebula", description = "spec fixture")),
      sharedResources = Some(resources)
    )

  private def replyInput(id: String, decision: String): JsonObject =
    JsonObject("correlationId" -> id.asJson, "decision" -> decision.asJson)

  test("takeover recovery replays marker metadata and delivers correlated reply") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    import nebflow.core.mail.SessionMailbox
    val question = "[BU-TAKEOVER]\nsession: work\nscreenshot: /private/shot.png"
    (for
      (request, _) <- AskHostLedger.beginForCall("worker-takeover", "sid-root", "agent", question, "call-takeover")
      _ <- IO(AskHostLedger.clearForTests())
      count <- AskHostLedger.recoverPending(resourcesWith(Map.empty))
      entries <- SessionMailbox.scanPending("sid-root")
      _ = assertEquals(count, 1)
      _ = assertEquals(entries.size, 1)
      event = entries.head.event
      _ = assertEquals(event.metadata("browserTakeover").flatMap(_.asBoolean), Some(true))
      _ = assertEquals(event.metadata("correlationId").flatMap(_.asString), Some(request.correlationId))
      _ = assert(event.payload.contains(question))
      waiter <- AskHostLedger.restoreWaiter(request.correlationId)
      response <- AskHostReplyTool.call(replyInput(request.correlationId, "点确认"), rootCtx(resourcesWith(Map.empty)))
      result <- AskHostTool.awaitReply(request.correlationId, waiter, scala.concurrent.duration.DurationInt(3).seconds)
      _ = assert(response.isRight)
      _ = assertEquals(result.toOption, Some("点确认"))
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("takeover instructions require a fresh snapshot after completion") {
    val instructions = os.read(os.pwd / "src" / "main" / "resources" / "seed" / "plugins" / "browser-use" / "skills" / "browser-session" / "SKILL.md")
    val handoff = instructions.split("## BU 动词阻塞接管", 2)(1).split("## 三、", 2)(0)
    assert(handoff.contains("收到答复（含迟答）后先重新 browser_snapshot"))
    assert(handoff.contains("作者直接操作页面"))
    assert(handoff.contains("等待期间不执行浏览器动词"))
  }

  test("timeout release is a distinct neutral state, never conflated with an unknown id") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (req, _) <- AskHostLedger.beginForCall("worker-x", "sid-root", "agent", "q", "call-x")
      unknown <- AskHostLedger.requestState("no-such-id")
      pending <- AskHostLedger.requestState(req.correlationId)
      released <- AskHostLedger.releaseOnTimeout(req.correlationId, "root reply timed out")
      after <- AskHostLedger.requestState(req.correlationId)
      recovered <- AskHostLedger.recover
      _ = assertEquals(unknown, None)
      _ = assertEquals(pending.map(_.terminal), Some(None))
      _ = assert(released)
      _ = assertEquals(
        after.map(_.terminal),
        Some(Some(AskHostLedger.Terminal.TimedOut(AskHostLedger.TimeoutOutcome, "root reply timed out"))),
        "a timeout release must record the NEUTRAL outcome, not a substantive default"
      )
      _ = assert(
        after.exists(_.pending.workerSession == "worker-x"),
        "the timed-out row must keep its worker binding so a late reply stays routable"
      )
      _ = assert(recovered.isEmpty, "a released wait must not be replayed as pending at boot")
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("awaitReply releases on timeout with a neutral notice (no decision, no substantive default)") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (req, waiter) <- AskHostLedger.beginForCall("worker-y", "sid-root", "agent", "q", "call-y")
      out <- AskHostTool.awaitReply(req.correlationId, waiter, scala.concurrent.duration.DurationInt(150).millis)
      text = out.toOption.getOrElse("")
      state <- AskHostLedger.requestState(req.correlationId)
      _ = assert(out.isRight, s"a timeout release must not be an error that pushes retry/escalate: $out")
      _ = assert(text.contains("TIMEOUT/NO-DECISION"), s"neutral outcome missing: $text")
      _ = assert(text.contains("No option was chosen on your behalf"), s"neutral wording missing: $text")
      _ = assert(text.contains("[ASKHOST-LATE-REPLY]"), s"late-reply contract missing: $text")
      _ = assertEquals(
        state.flatMap(_.terminal),
        Some(AskHostLedger.Terminal.TimedOut(AskHostLedger.TimeoutOutcome, "root reply timed out"))
      )
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("late reply after timeout is injected into the worker mailbox (injection leg observable; idempotent)") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    import nebflow.core.mail.SessionMailbox
    val worker = "delegate-nebflow-workerlive"
    val resources = resourcesWith(
      Map(
        worker ->
          AgentRecord(sessionId = worker, ref = liveRef(), kind = AgentKind.Delegate, rootSessionId = worker)
      )
    )
    val ctx = rootCtx(resources)
    (for
      (req, _) <- AskHostLedger.beginForCall(worker, "sid-root", "agent", "q", "call-late")
      _ <- AskHostLedger.releaseOnTimeout(req.correlationId, "root reply timed out")
      cmd = replyInput(req.correlationId, "use option A")
      res <- AskHostReplyTool.call(cmd, ctx)
      entries <- SessionMailbox.scanPending(worker)
      state <- AskHostLedger.requestState(req.correlationId)
      _ = assert(res.isRight, s"a late reply must not be rejected after a timeout release: $res")
      _ = assert(res.toOption.exists(_.contains("ASKHOST-LATE-REPLY")), s"late-reply marker missing: $res")
      _ = assertEquals(entries.size, 1, "exactly one injected late reply")
      _ = assert(entries.head.event.payload.contains("[ASKHOST-LATE-REPLY]"))
      _ = assert(entries.head.event.payload.contains("use option A"))
      _ = assert(
        entries.head.event.payload.contains(AskHostLedger.TimeoutOutcome),
        "the injected reply must state the neutral outcome it supersedes"
      )
      _ = assert(entries.head.identity.startsWith("askhost-late-reply-"))
      _ = assert(entries.head.event.metadata("lateReply").flatMap(_.asBoolean).contains(true))
      _ = assertEquals(state.map(_.lateReplies), Some(List("use option A")))
      res2 <- AskHostReplyTool.call(cmd, ctx)
      entries2 <- SessionMailbox.scanPending(worker)
      _ = assert(res2.toOption.exists(_.contains("already delivered")), s"re-send must be idempotent: $res2")
      _ = assertEquals(entries2.size, 1, "idempotent re-send must not duplicate the injection")
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("late reply to a non-live worker is queued durably (never silently dropped)") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    import nebflow.core.mail.SessionMailbox
    val worker = "delegate-nebflow-gone"
    val ctx = rootCtx(resourcesWith(Map.empty))
    (for
      (req, _) <- AskHostLedger.beginForCall(worker, "sid-root", "agent", "q", "call-gone")
      _ <- AskHostLedger.releaseOnTimeout(req.correlationId, "root reply timed out")
      res <- AskHostReplyTool.call(replyInput(req.correlationId, "go"), ctx)
      entries <- SessionMailbox.scanPending(worker)
      _ = assert(res.isRight, s"queuing a late reply must not error: $res")
      _ = assertEquals(entries.size, 1, "the late reply must be durably queued in the worker mailbox")
      _ = assert(entries.head.event.payload.contains("[ASKHOST-LATE-REPLY]"))
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("conflict and unknown id are refused with distinct, readable errors") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    val ctx = rootCtx(resourcesWith(Map.empty))
    (for
      (req, _) <- AskHostLedger.beginForCall("worker-z", "sid-root", "agent", "q", "call-z")
      _ <- AskHostLedger.answer(req.correlationId, "Option A")
      conflict <- AskHostReplyTool.call(replyInput(req.correlationId, "Option B"), ctx)
      same <- AskHostReplyTool.call(replyInput(req.correlationId, "Option A"), ctx)
      unknown <- AskHostReplyTool.call(replyInput("no-such-id", "x"), ctx)
      _ = assert(
        conflict.isLeft && conflict.swap.toOption.get.message.contains("ASKHOST_REPLY_CONFLICT"),
        s"an explicit different decision must be a distinct conflict refusal: $conflict"
      )
      _ = assert(same.toOption.exists(_.contains("already accepted")))
      _ = assert(
        unknown.isLeft && unknown.swap.toOption.get.message.contains("ASKHOST_REPLY_UNKNOWN_ID"),
        s"an unknown id must be an explicit error: $unknown"
      )
      _ = assert(
        !unknown.swap.toOption.get.message.contains("unavailable or already answered"),
        "the three states must never share one indistinguishable message"
      )
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("wait window is configurable via -D and never falls back to an infinite wait") {
    val key = "nebflow.askhost.waitWindowMs"
    val previous = sys.props.get(key)
    IO {
      try
        assertEquals(AskHostTool.waitWindowMs, AskHostTool.DefaultWaitWindowMs)
        assertEquals(AskHostTool.DefaultWaitWindowMs, 30L * 60L * 1000L)
        sys.props.update(key, "1200000")
        assertEquals(AskHostTool.waitWindowMs, 1200000L)
        sys.props.update(key, "0")
        assertEquals(
          AskHostTool.waitWindowMs,
          AskHostTool.DefaultWaitWindowMs,
          "<= 0 must fall back, never an infinite wait"
        )
        sys.props.update(key, "nonsense")
        assertEquals(AskHostTool.waitWindowMs, AskHostTool.DefaultWaitWindowMs)
      finally
        previous match
          case Some(v) => sys.props.update(key, v)
          case None => sys.props.remove(key)
    }
  }

  test("AskHostClarify admits a timed-out request with the late-reply contract (same defect class as AskHostReply)") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (req, _) <- AskHostLedger.beginForCall("worker-c", "sid-root", "agent", "q", "call-c")
      pending <- AskHostLedger.requestState(req.correlationId)
      _ <- AskHostLedger.releaseOnTimeout(req.correlationId, "root reply timed out")
      released <- AskHostLedger.requestState(req.correlationId)
      releasedAdmission = released.map(AskHostClarifyTool.admission)
      note = releasedAdmission.flatMap(_.toOption).flatten.getOrElse("")
      _ = assert(
        pending.exists(s => AskHostClarifyTool.admission(s) == Right(None)),
        "a still-pending request clarifies plainly (behaviour unchanged)"
      )
      _ = assert(
        releasedAdmission.exists(_.isRight),
        s"a timed-out request must stay clarifiable, got: $releasedAdmission"
      )
      _ = assert(note.contains("[ASKHOST-LATE-REPLY]"), s"late-reply contract missing: $note")
      _ = assert(note.contains(AskHostLedger.TimeoutOutcome), s"neutral outcome missing: $note")
      _ = assert(
        !releasedAdmission.exists(_.swap.toOption.exists(_.message.contains("pending correlation unavailable"))),
        "the old recover-based refusal must be gone"
      )
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("AskHostClarify refuses answered / never-answerable requests distinctly; an unknown id is explicit") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    val ctx = rootCtx(resourcesWith(Map.empty))
    (for
      (req, _) <- AskHostLedger.beginForCall("worker-c2", "sid-root", "agent", "q", "call-c2")
      _ <- AskHostLedger.answer(req.correlationId, "Option A")
      answered <- AskHostLedger.requestState(req.correlationId)
      (gone, _) <- AskHostLedger.beginForCall("worker-c3", "sid-root", "agent", "q", "call-c3")
      _ <- AskHostLedger.markUnavailable(gone.correlationId, "delivery failed: Failed(mailbox-full)")
      released <- AskHostLedger.requestState(gone.correlationId)
      answeredAdmission = answered.map(AskHostClarifyTool.admission)
      releasedAdmission = released.map(AskHostClarifyTool.admission)
      unknown <- AskHostClarifyTool.call(
        JsonObject("correlationId" -> "no-such-id".asJson, "questions" -> io.circe.Json.arr()),
        ctx
      )
      _ = assert(
        answeredAdmission.exists(_.swap.toOption.exists(_.message.contains("ASKHOST_CLARIFY_ALREADY_ANSWERED"))),
        s"an answered request must be refused distinctly: $answeredAdmission"
      )
      _ = assert(
        releasedAdmission.exists(_.swap.toOption.exists(_.message.contains("ASKHOST_CLARIFY_RELEASED_UNANSWERABLE"))),
        s"a never-answerable request must be refused distinctly: $releasedAdmission"
      )
      _ = assert(
        unknown.swap.toOption.exists(_.message.contains("ASKHOST_CLARIFY_UNKNOWN_ID")),
        s"an unknown id must be explicit: $unknown"
      )
      _ = assert(
        !unknown.swap.toOption.exists(_.message.contains("pending correlation unavailable")),
        "the old conflated refusal must be gone"
      )
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

end AskHostToolsSpec
