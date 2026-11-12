package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import munit.CatsEffectSuite
import nebflow.core.SessionStore
import nebflow.core.mail.SessionMailbox
import nebflow.shared.{Defaults, PathUtil, SubAgentTask}

/** Deterministic boot reconciliation checks using real task, session, and mailbox stores. */
class MailboxBootSweepSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-mailbox-boot-sweep"
  private val originalRoot = PathUtil.dataRoot
  private val firstBootMs = 1_800_000_000_000L
  private val completedMs = firstBootMs + 1L
  private val nextBootMs = firstBootMs + 2L
  private def epochFile: os.Path = tempRoot / "mailbox-reconcile.json"

  PathUtil.setDataRoot(tempRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot)
    os.makeDir.all(tempRoot)
    // Keep each test's session index in the same store instance used by the sweep.
    taskStore = SubAgentTaskStore(tempRoot / "subagent-tasks")
    sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks")
    ()

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private var taskStore: SubAgentTaskStore = null
  private var sessionStore: SessionStore = null

  private def run(nowMs: Long = nextBootMs): IO[MailboxBootSweep.Report] =
    MailboxBootSweep.runAt(taskStore, sessionStore, nowMs)

  private def readEpoch(): IO[MailboxBootSweep.BootEpoch] =
    IO.blocking(decode[MailboxBootSweep.BootEpoch](os.read(epochFile))).flatMap(IO.fromEither)

  private def row(
    taskId: String,
    parent: String,
    status: String,
    source: String = "delegate",
    completedAt: Option[Long] = Some(completedMs)
  ): SubAgentTask =
    SubAgentTask(
      taskId = taskId,
      parentSessionId = parent,
      agentName = "worker",
      prompt = "do the thing",
      description = "the thing",
      status = status,
      retryCount = 0,
      spawnedAt = firstBootMs - 60_000L,
      completedAt = completedAt,
      lastError = if status == "failed" then Some("it broke") else None,
      source = source
    )

  /** Seed an empty first-boot window with an explicit timestamp. */
  private def seedEpoch(): IO[MailboxBootSweep.Report] = run(firstBootMs)

  test("first boot seeds an empty window without replaying pre-activation rows") {
    for
      s <- sessionStore.getOrCreateSession("parent-1", "p1")
      _ = assert(s._2, "session created")
      _ <- taskStore.saveTasks(
        "parent-1",
        List(row("delegate-a-11111111", "parent-1", "completed", completedAt = Some(firstBootMs - 1L)))
      )
      r <- seedEpoch()
      epoch <- readEpoch()
      pend <- SessionMailbox.scanPending("parent-1")
    yield
      assertEquals(clue(r.synthesized), Nil, "first boot window is empty by construction")
      assertEquals(clue(pend), Nil, "no entry synthesized from the pre-activation ledger")
      assertEquals(epoch, MailboxBootSweep.BootEpoch(lastBootMs = 0L, thisBootMs = firstBootMs))
  }

  test("terminal delegate row in the previous window with an empty box is synthesized") {
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-2", "p2")
      _ <- taskStore.saveTasks("parent-2", List(row("delegate-b-22222222", "parent-2", "completed")))
      r <- run()
      epoch <- readEpoch()
      pend <- SessionMailbox.scanPending("parent-2")
    yield
      assertEquals(clue(r.synthesized), List("delegate-b-22222222"), "missing terminal entry synthesized")
      assertEquals(clue(pend.map(_.identity)), List("delegate-b-22222222"), "identity uses the task idempotency key")
      assertEquals(clue(pend.head.kind), "delegate-terminal")
      assert(
        clue(pend.head.event.payload).contains("synthesized from the task ledger"),
        "payload honestly declares its synthesized origin"
      )
      assertEquals(
        clue(pend.head.event.metadata("recoveredBy").flatMap(_.asString)),
        Some("mailbox-boot-reconcile")
      )
      assertEquals(epoch, MailboxBootSweep.BootEpoch(lastBootMs = firstBootMs, thisBootMs = nextBootMs))
  }

  test("consumed terminal with only a delivered tombstone is not re-synthesized") {
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-3", "p3")
      _ <- taskStore.saveTasks("parent-3", List(row("delegate-c-33333333", "parent-3", "completed")))
      // Record consumption without leaving a pending or taken entry to suppress synthesis.
      _ <- SessionMailbox.unlinkTaken("parent-3", List("delegate-c-33333333"))
      r <- run()
      pend <- SessionMailbox.scanPending("parent-3")
      taken <- SessionMailbox.scanTaken("parent-3")
    yield
      assertEquals(clue(r.synthesized), Nil, "tombstone gate: delivered stays delivered")
      assertEquals(clue(pend), Nil)
      assertEquals(clue(taken), Nil)
  }

  test("entry already on disk or parent session gone causes no synthesis") {
    for
      _ <- seedEpoch()
      // An existing live-path entry accounts for this terminal row.
      _ <- sessionStore.getOrCreateSession("parent-4", "p4")
      _ <- taskStore.saveTasks("parent-4", List(row("delegate-d-44444444", "parent-4", "failed")))
      _ <- SessionMailbox.mailOnce(
        "parent-4",
        nebflow.actor.AgentCommand.ExternalEvent(
          source = "delegate",
          eventType = "failed",
          payload = "already delivered by the live path",
          metadata = io.circe.JsonObject.empty,
          correlationId = Some("delegate-d-44444444")
        ),
        kind = "delegate-terminal",
        identity = "delegate-d-44444444"
      )
      // This row has no surviving parent session.
      _ <- taskStore.saveTasks("parent-gone", List(row("delegate-e-55555555", "parent-gone", "cancelled")))
      r <- run()
      pend4 <- SessionMailbox.scanPending("parent-4")
      gone <- SessionMailbox.scanPending("parent-gone")
    yield
      assertEquals(clue(r.synthesized), Nil, "accounted rows and parent-gone rows are skipped")
      assertEquals(clue(pend4.map(_.identity)), List("delegate-d-44444444"), "live entry untouched")
      assertEquals(gone, Nil)
  }

  test("running rows and subtask rows never synthesize") {
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-5", "p5")
      _ <- taskStore.saveTasks(
        "parent-5",
        List(
          row("delegate-f-66666666", "parent-5", "running", completedAt = None),
          row("subtask-77777777", "parent-5", "completed", source = "subtask")
        )
      )
      r <- run()
      pend <- SessionMailbox.scanPending("parent-5")
    yield
      assertEquals(clue(r.synthesized), Nil)
      assertEquals(clue(pend), Nil)
  }

  test("tmp fragments are swept before the clock is sampled lazily and once per execution") {
    val fragment = tempRoot / "sessions" / "parent-6" / "mailbox" / "pending" / "000001-crash.json.tmp"
    var samples = 0
    val sweep = MailboxBootSweep.runAt(
      taskStore,
      sessionStore,
      {
        assert(!os.exists(fragment), "temporary fragments are removed before clock sampling")
        samples += 1
        firstBootMs
      }
    )
    assertEquals(samples, 0, "constructing the effect must not sample the clock")
    for
      _ <- IO.blocking(os.write.over(fragment, "{ half", createFolders = true))
      r <- sweep
      epoch <- readEpoch()
      pend <- SessionMailbox.scanPending("parent-6")
      _ = assertEquals(samples, 1, "one sample per sweep execution")
      _ <- sweep
    yield
      assertEquals(clue(r.tmpSwept), 1)
      assertEquals(clue(pend), Nil, "tmp never counts as an entry")
      assertEquals(epoch, MailboxBootSweep.BootEpoch(lastBootMs = 0L, thisBootMs = firstBootMs))
      assertEquals(samples, 2, "reusing the effect samples the clock again")
  }

  test("rolling the epoch prevents replay even after removing the mailbox without a tombstone") {
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-7", "p7")
      _ <- taskStore.saveTasks("parent-7", List(row("delegate-h-88888888", "parent-7", "completed")))
      r2 <- run()
      first <- SessionMailbox.scanPending("parent-7")
      accounted <- run(nextBootMs)
      retained <- SessionMailbox.scanPending("parent-7")
      // Remove accounting evidence so only the rolled window can prevent replay.
      _ <- IO.blocking(nebflow.ForceRemove.removeAll(tempRoot / "sessions" / "parent-7" / "mailbox"))
      delivered <- SessionMailbox.deliveredContains("parent-7", Set("delegate-h-88888888"))
      r3 <- run(nextBootMs + 1L)
      epoch <- readEpoch()
      pend <- SessionMailbox.scanPending("parent-7")
    yield
      assertEquals(clue(r2.synthesized), List("delegate-h-88888888"))
      assertEquals(first.map(_.identity), List("delegate-h-88888888"))
      assertEquals(accounted.synthesized, Nil)
      assertEquals(retained, first, "an existing synthesized entry is retained unchanged")
      assertEquals(delivered, Set.empty[String], "no tombstone masks the window gate")
      assertEquals(clue(r3.synthesized), Nil, "the row is now outside the window")
      assertEquals(pend, Nil, "an empty mailbox does not cause replay")
      assertEquals(epoch, MailboxBootSweep.BootEpoch(lastBootMs = nextBootMs, thisBootMs = nextBootMs + 1L))
  }

  test("boot window is exclusive on the left and inclusive on the right, including adjacent timestamps") {
    val right = firstBootMs + 10L
    val cases = List(
      ("before-left", firstBootMs - 1L),
      ("at-left", firstBootMs),
      ("after-left", firstBootMs + 1L),
      ("before-right", right - 1L),
      ("at-right", right),
      ("after-right", right + 1L)
    )
    val expected = Set("after-left", "before-right", "at-right")
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-window", "window")
      _ <- taskStore.saveTasks(
        "parent-window",
        cases.map { case (id, at) => row(id, "parent-window", "completed", completedAt = Some(at)) }
      )
      r <- run(right)
      pend <- SessionMailbox.scanPending("parent-window")
    yield
      assertEquals(r.synthesized.toSet, expected)
      assertEquals(pend.map(_.identity).toSet, expected)
  }

  test("TTL independently excludes ages at or above the threshold inside a wide boot window") {
    val now = firstBootMs + Defaults.DelegateTerminalTtlMs + 100L
    val cutoff = now - Defaults.DelegateTerminalTtlMs
    val cases = List(("ttl-older", cutoff - 1L), ("ttl-exact", cutoff), ("ttl-younger", cutoff + 1L))
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-ttl", "ttl")
      _ <- taskStore.saveTasks(
        "parent-ttl",
        cases.map { case (id, at) => row(id, "parent-ttl", "completed", completedAt = Some(at)) }
      )
      r <- run(now)
      pend <- SessionMailbox.scanPending("parent-ttl")
    yield
      assert(cases.forall { case (_, at) => at > firstBootMs && at <= now }, "all TTL cases pass the boot window")
      assertEquals(r.synthesized, List("ttl-younger"))
      assertEquals(pend.map(_.identity), List("ttl-younger"))
  }

  test("terminal rows without completedAt do not synthesize even with a recent fallback timestamp") {
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-no-time", "no-time")
      _ <- taskStore.saveTasks(
        "parent-no-time",
        List(row("missing-time", "parent-no-time", "completed", completedAt = None).copy(statusAt = Some(completedMs)))
      )
      r <- run()
      pend <- SessionMailbox.scanPending("parent-no-time")
    yield
      assertEquals(r.synthesized, Nil)
      assertEquals(pend, Nil)
  }

  test("equal boot timestamps form an empty window and an equal completion remains excluded on the next roll") {
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-equal", "equal")
      _ <- taskStore.saveTasks(
        "parent-equal",
        List(row("equal-time", "parent-equal", "completed", completedAt = Some(firstBootMs)))
      )
      equal <- run(firstBootMs)
      equalEpoch <- readEpoch()
      later <- run()
      laterEpoch <- readEpoch()
      pend <- SessionMailbox.scanPending("parent-equal")
    yield
      // Equality is a possible timing failure mechanism, not proof of a unique historical cause.
      assertEquals(equal.synthesized, Nil)
      assertEquals(equalEpoch, MailboxBootSweep.BootEpoch(lastBootMs = firstBootMs, thisBootMs = firstBootMs))
      assertEquals(later.synthesized, Nil, "a completion at the exclusive left boundary stays excluded")
      assertEquals(laterEpoch, MailboxBootSweep.BootEpoch(lastBootMs = firstBootMs, thisBootMs = nextBootMs))
      assertEquals(pend, Nil, "neither an existing entry nor a tombstone masks the equality check")
  }

  test("corrupt epoch safely resets to an empty window and the following boot uses the persisted epoch") {
    for
      _ <- sessionStore.getOrCreateSession("parent-corrupt", "corrupt")
      _ <- taskStore.saveTasks(
        "parent-corrupt",
        List(
          row("before-reset", "parent-corrupt", "completed", completedAt = Some(firstBootMs - 1L)),
          row("after-reset", "parent-corrupt", "completed")
        )
      )
      _ <- IO.blocking(os.write.over(epochFile, "{ corrupt", createFolders = true))
      reset <- run(firstBootMs)
      resetEpoch <- readEpoch()
      empty <- SessionMailbox.scanPending("parent-corrupt")
      recovered <- run()
      recoveredEpoch <- readEpoch()
      pend <- SessionMailbox.scanPending("parent-corrupt")
    yield
      assertEquals(reset.synthesized, Nil)
      assertEquals(empty, Nil, "a corrupt epoch must not replay old ledger rows")
      assertEquals(resetEpoch, MailboxBootSweep.BootEpoch(lastBootMs = 0L, thisBootMs = firstBootMs))
      assertEquals(recovered.synthesized, List("after-reset"))
      assertEquals(pend.map(_.identity), List("after-reset"))
      assertEquals(recoveredEpoch, MailboxBootSweep.BootEpoch(lastBootMs = firstBootMs, thisBootMs = nextBootMs))
  }
end MailboxBootSweepSpec
