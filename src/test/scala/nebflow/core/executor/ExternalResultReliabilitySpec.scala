package nebflow.core.executor

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.db.DbHost
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{Message, MessageRole, PathUtil, SubAgentTask, ThinkingConfig}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

/** Real subprocess integration tests; fixtures never invoke an installed CLI or a provider. */
class ExternalResultReliabilitySpec extends CatsEffectSuite:

  override val munitIOTimeout = 45.seconds

  private final case class Script(bin: os.Path, pid: os.Path, release: os.Path)
  private final case class Run(id: String, spec: ExternalSessionRunner.SpawnSpec, script: Script)
  private final case class Settled(row: SubAgentTask, messages: List[Message], mail: SessionMailbox.Entry)
  private final case class Fixture(
    root: os.Path,
    resources: SharedResources,
    owned: Ref[IO, List[Run]],
    frames: Ref[IO, List[Json]]
  ):
    val parentId = "external-reliability-missing-parent"
    val rootId = "external-reliability-root"

    // Separate instances must reconstruct results from disk, not a writer's cache.
    def freshSessions: SessionStore = SessionStore(root / "sessions", root / "tasks")
    def freshTasks: SubAgentTaskStore = new SubAgentTaskStore(root / "subagent-tasks")

  private def eventually[A](label: String)(read: IO[A])(ready: A => Boolean): IO[A] =
    def loop: IO[A] = read.flatMap { value =>
      if ready(value) then IO.pure(value)
      else IO.sleep(20.millis) *> IO.defer(loop)
    }
    loop.timeoutTo(12.seconds, IO.raiseError(new AssertionError(s"Timed out waiting for $label")))

  private def fakeResources(root: os.Path): Resource[IO, SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO]
      system <- Resource.make(IO(ActorSystem("external-result-reliability")))(_.stopAll)
      resources <- Resource.eval {
        for
          rateLimiter <- RateLimiter.create()
          tracker <- FileChangeTracker.create(root.toString)
          fileLocks <- FileLockManager.create
          thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
          models <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          muted <- Ref.of[IO, Boolean](false)
        yield SharedResources(
          llm = null,
          dispatcher = dispatcher,
          sessionStore = SessionStore(root / "sessions", root / "tasks"),
          projectRoot = root,
          thinkingConfigRef = thinking,
          rateLimiter = rateLimiter,
          fileChangeTracker = tracker,
          contextWindow = 100_000,
          agentLibrary = new AgentLibrary(root / "agents"),
          taskStore = FileTaskStore,
          historyArchiver = HistoryArchiver.fileSystem(root / "archives"),
          fileLockManager = fileLocks,
          sessionModelOverrides = models,
          providerRegistry = null,
          healthMonitor = ProviderHealthMonitor(null),
          actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(root / "subagent-tasks"),
          voiceMutedRef = muted
        )
      }
    yield resources

  private def fixture: Resource[IO, Fixture] =
    for
      root <- Resource.make(IO.blocking {
        assume(!ExecutorRegistry.isWindows, "Synthetic executable fixtures require a POSIX shell")
        val original = PathUtil.dataRoot
        val tmp = os.temp.dir(prefix = "nb-external-result-reliability-")
        PathUtil.setDataRoot(tmp)
        (original, tmp)
      }) { case (original, tmp) =>
        IO.blocking(DbHost.release(tmp.toNIO))
          .guarantee(IO.blocking {
            PathUtil.setDataRoot(original)
            os.remove.all(tmp)
          })
      }.map(_._2)
      resources <- fakeResources(root)
      owned <- Resource.eval(Ref.of[IO, List[Run]](Nil))
      frames <- Resource.eval(Ref.of[IO, List[Json]](Nil))
      result <- Resource.make(IO.pure(Fixture(root, resources, owned, frames)))(cleanup)
    yield result

  private def quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

  private def executable(f: Fixture, lines: List[String], gated: Boolean = false): IO[Script] = IO.blocking {
    val dir = os.temp.dir(dir = f.root, prefix = "synthetic-cli-")
    val script = Script(dir / "fixture.sh", dir / "pid", dir / "release")
    val home = dir / "home"
    val emptyPath = dir / "empty-path"
    os.makeDir.all(home)
    os.makeDir.all(emptyPath)
    val output = lines.map(line => s"printf '%s\\n' ${quote(line)}").mkString("\n")
    val gate = if gated then
      s"""attempt=0
         |while [ ! -f ${quote(script.release.toString)} ]; do
         |  attempt=$$((attempt + 1))
         |  [ "$$attempt" -lt 200 ] || exit 91
         |  /bin/sleep 0.05
         |done
         |""".stripMargin
    else ""
    os.write(script.bin,
      s"""#!/bin/sh
         |set -eu
         |if [ "$${1-}" != '--isolated-fixture' ]; then
         |  exec /usr/bin/env -i HOME=${quote(home.toString)} PATH=${quote(emptyPath.toString)} LC_ALL=C /bin/sh "$$0" --isolated-fixture "$$@"
         |fi
         |shift
         |[ "$$HOME" = ${quote(home.toString)} ] || exit 92
         |[ "$$PATH" = ${quote(emptyPath.toString)} ] || exit 93
         |printf '%s\\n' "$$$$" > ${quote(script.pid.toString)}
         |$gate$output
         |exit 0
         |""".stripMargin)
    os.perms.set(script.bin, "rwx------")
    script
  }

  private def spawn(f: Fixture, executor: String, lines: List[String], gated: Boolean = false): IO[Run] =
    for
      script <- executable(f, lines, gated)
      spec = ExternalSessionRunner.SpawnSpec(
        executorId = executor,
        binPath = script.bin.toString,
        task = "Return the synthetic fixture report without contacting any service.",
        title = "External result reliability fixture",
        workspace = f.root.toString,
        project = None,
        taskId = None,
        parentSessionId = f.parentId,
        rootSessionId = f.rootId,
        parentRef = None,
        wsSend = Some(frame => f.frames.update(_ :+ frame)),
        resources = f.resources
      )
      result <- ExternalSessionRunner.spawn(spec)
      run <- result match
        case Left(error) => IO.raiseError(new AssertionError(s"Synthetic CLI was rejected: $error"))
        case Right((id, ack)) =>
          val run = Run(id, spec, script)
          f.owned.update(run :: _) *> IO {
            assert(ack.contains(id), "The accepted session must be visible in the spawn acknowledgement")
            run
          }
    yield run

  private def settle(f: Fixture, run: Run): IO[Settled] =
    for
      row <- eventually("durable terminal task and notification acknowledgement")(
        f.freshTasks.loadTasks(f.parentId).map(_.find(_.taskId == run.id))
      )(_.exists(t => Set("finished", "failed", "cancelled").contains(t.executionStatus.getOrElse("")) &&
        t.notificationPending.contains(false))).map(_.get)
      _ <- eventually("runner detachment")(f.resources.agentRegistry.get)(registry => !registry.contains(run.id))
      _ <- eventually("fixture process exit")(ExternalOutputStore.processOf(run.id).map(_.forall(p => !p.isAlive)))(identity)
      messages <- f.freshSessions.loadMessagesForSession(run.id)
      entries <- SessionMailbox.scanPending(f.parentId)
      entry <- IO.fromOption(entries.find(_.identity == row.notificationId.getOrElse("")))(
        new AssertionError("Terminal task has no durable mailbox entry"))
      _ <- IO {
        val runId = row.runId.getOrElse(fail("Missing execution identity"))
        assert(runId.nonEmpty)
        assertEquals(row.executor, Some(run.spec.executorId))
        assertEquals(row.rootSessionId, Some(f.rootId))
        assertEquals(row.parentSessionId, f.parentId)
        assertEquals(row.source, "delegate")
        assertEquals(row.resultRef, Some(s"${run.id}#$runId"))
        assertEquals(row.notificationId, Some(s"${run.id}#$runId"))
        assert(row.committedRevision.exists(_ > 1L))
        assert(row.completedAt.nonEmpty)
        assertEquals(messages.filter(_.role == MessageRole.User).map(_.textContent), List(run.spec.task))
        assert(messages.forall(_.runId.contains(runId)))
        assert(messages.forall(_.messageId.exists(_.nonEmpty)))
        assertEquals(messages.flatMap(_.messageId).distinct.size, messages.size)
        assertEquals(entry.kind, "delegate-terminal")
        assertEquals(entry.producer, "external-session-runner")
        assertEquals(entry.event.source, "delegate")
        assertEquals(entry.event.correlationId, Some(run.id))
        assertEquals(entry.event.metadata("completionGeneration").flatMap(_.asString), Some(runId))
        assertEquals(entry.event.metadata("taskId").flatMap(_.asString), Some(run.id))
      }
    yield Settled(row, messages, entry)

  private def assertReport(result: Settled, texts: List[String], finished: Boolean = true): Unit =
    assertEquals(result.messages.filter(_.role == MessageRole.Assistant).map(_.textContent), texts)
    assertEquals(result.row.executionStatus, Some(if finished then "finished" else "failed"))
    assertEquals(result.row.status, if finished then "completed" else "failed")
    assertEquals(result.row.incomplete, Some(!finished))
    assertEquals(result.mail.event.eventType, if finished then "completed" else "failed")
    texts.foreach(text => assert(result.mail.event.payload.contains(text)))
    if finished then
      assertEquals(result.row.terminalReason, None)
      assertEquals(result.row.lastError, None)
      assert(result.mail.event.payload.contains("requirement completion is not inferred"))
    else
      assert(result.row.terminalReason.exists(_.toLowerCase.contains("incomplete")))
      assert(result.row.diagnostic.exists(_.nonEmpty))

  private def codexMessage(id: String, text: String): String =
    Json.obj("type" -> Json.fromString("item.completed"), "item" -> Json.obj(
      "type" -> Json.fromString("agent_message"), "id" -> Json.fromString(id), "text" -> Json.fromString(text)
    )).noSpaces

  private def claudeMessage(id: String, text: String): String =
    Json.obj("type" -> Json.fromString("assistant"), "message" -> Json.obj(
      "id" -> Json.fromString(id), "role" -> Json.fromString("assistant"),
      "content" -> Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text)))
    )).noSpaces

  private def claudeResult(id: String, text: String): String =
    Json.obj("type" -> Json.fromString("result"), "subtype" -> Json.fromString("success"),
      "uuid" -> Json.fromString(id), "is_error" -> Json.False, "result" -> Json.fromString(text)
    ).noSpaces

  private val codexEnd = """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1}}"""

  test("live external supplement is queued and becomes a subsequent process input") {
    fixture.use { f =>
      for
        run <- spawn(f, "codex", List(codexMessage("reply", "fixture response"), codexEnd), gated = true)
        _ <- eventually("fixture start")(IO.blocking(os.exists(run.script.pid)))(identity)
        record <- IO.blocking(nebflow.core.delegate.DelegateRegistry.find(run.id).get)
        receipt <- ExternalSessionRunner.continueExternal(record, "recognizable queued supplement", None, None, f.resources)
        _ = assert(receipt.isRight, receipt.toString)
        _ = assert(receipt.toOption.get.toLowerCase.contains("queued"), receipt.toString)
        _ <- IO.blocking(os.write(run.script.release, "release"))
        messages <- eventually("supplement input in next run")(
          f.freshSessions.loadMessagesForSession(run.id)
        )(_.exists(m => m.role == MessageRole.User && m.textContent == "recognizable queued supplement"))
        _ = assert(messages.exists(_.textContent == "recognizable queued supplement"))
      yield ()
    }
  }

  test("Codex assistant text survives a textless turn.completed and a missing parent") {
    fixture.use { f =>
      for
        existsBefore <- f.freshSessions.sessionRecordExists(f.parentId)
        _ <- IO(assertEquals(existsBefore, false))
        run <- spawn(f, "codex", List(
          """{"type":"thread.started","thread_id":"synthetic-codex-thread"}""",
          codexMessage("codex-report", "The full report\ncontains two lines and a 'quoted' value."), codexEnd
        ))
        result <- settle(f, run)
        parentTranscriptExists <- IO.blocking(os.exists(f.root / "sessions" / s"${f.parentId}.json"))
        output <- ExternalOutputStore.read(run.id, 0L)
      yield
        assertReport(result, List("The full report\ncontains two lines and a 'quoted' value."))
        assertEquals(parentTranscriptExists, false, "Mailbox delivery must not require or fabricate a parent transcript")
        assertEquals(output.flatMap(_.exitCode), Some(0))
        assertEquals(output.flatMap(_.cliSessionId), Some("synthetic-codex-thread"))
    }
  }

  test("Blocked report text with exit zero is preserved without inferring business success") {
    fixture.use { f =>
      val text = "BLOCKED: credentials are unavailable; no requested changes were made."
      for
        run <- spawn(f, "codex", List(codexMessage("blocked-report", text), codexEnd))
        result <- settle(f, run)
        output <- ExternalOutputStore.read(run.id, 0L)
      yield
        assertReport(result, List(text))
        assertEquals(output.flatMap(_.exitCode), Some(0))
        assert(result.mail.event.payload.contains("Execution finished; requirement completion is not inferred."))
    }
  }

  test("Claude Code preserves report, distinct result, trailing assistant and trailing result in order") {
    fixture.use { f =>
      val texts = List("Initial detailed report.", "First result summary.", "Additional report after result.", "Final result summary.")
      for
        run <- spawn(f, "claude-code", List(
          claudeMessage("report-one", texts(0)), claudeResult("result-one", texts(1)),
          claudeMessage("report-two", texts(2)), claudeResult("result-two", texts(3))
        ))
        result <- settle(f, run)
      yield
        assertReport(result, texts)
        assert(result.mail.event.payload.contains(texts.mkString("\n\n")), "Notification must use the entire persisted run")
    }
  }

  List("codex", "claude-code").foreach { executor =>
    test(s"$executor empty exit-zero output fails incomplete rather than claiming success") {
      fixture.use { f =>
        for
          run <- spawn(f, executor, Nil)
          result <- settle(f, run)
          output <- ExternalOutputStore.read(run.id, 0L)
        yield
          assertReport(result, Nil, finished = false)
          assertEquals(output.flatMap(_.exitCode), Some(0))
      }
    }

    test(s"$executor retains assistant text but fails incomplete when no protocol terminal arrives") {
      fixture.use { f =>
        val text = "Partial report before EOF without a terminal event."
        val line = if executor == "codex" then codexMessage("partial", text) else claudeMessage("partial", text)
        for
          run <- spawn(f, executor, List(line))
          result <- settle(f, run)
        yield assertReport(result, List(text), finished = false)
      }
    }

    test(s"$executor malformed lines do not discard later valid reports or their terminal") {
      fixture.use { f =>
        val text = "Valid report after malformed JSON."
        val good = if executor == "codex" then List(codexMessage("after-bad-json", text), codexEnd)
          else List(claudeMessage("after-bad-json", text), claudeResult("after-bad-result", text))
        for
          run <- spawn(f, executor, "{not valid JSON" :: good)
          result <- settle(f, run)
          output <- ExternalOutputStore.read(run.id, 0L)
        yield
          assertReport(result, List(text))
          assert(output.exists(_.output.contains("{not valid JSON")), "Malformed input must remain in raw evidence")
      }
    }

    test(s"$executor keeps identical text from distinct assistant message identities") {
      fixture.use { f =>
        val text = "The same report text is a separate message."
        val lines = if executor == "codex" then
          List(codexMessage("same-one", text), codexMessage("same-two", text), codexEnd)
        else List(claudeMessage("same-one", text), claudeMessage("same-two", text), claudeResult("same-result", text))
        for
          run <- spawn(f, executor, lines)
          result <- settle(f, run)
        yield assertReport(result, List(text, text))
      }
    }
  }

  test("Terminal notification identity stays stable through duplicate enqueue, recovery and consumption") {
    fixture.use { f =>
      for
        run <- spawn(f, "codex", List(codexMessage("stable-report", "Stable notification report."), codexEnd))
        result <- settle(f, run)
        _ <- ExternalSessionRunner.notifyParent(run.spec, run.id, ExternalSessionRunner.Outcome.Completed,
          "Stable notification report.", None, result.row.runId)
        _ <- ExternalSessionRunner.recoverPendingNotifications(f.freshTasks, f.freshSessions)
        pending <- SessionMailbox.scanPending(f.parentId)
        _ <- IO(assertEquals(pending, List(result.mail)))
        taken <- SessionMailbox.take(f.parentId, pending)
        _ <- IO(assertEquals(taken, pending))
        _ <- ExternalSessionRunner.notifyParent(run.spec, run.id, ExternalSessionRunner.Outcome.Completed,
          "Stable notification report.", None, result.row.runId)
        whileTaken <- SessionMailbox.scanPending(f.parentId)
        _ <- IO(assertEquals(whileTaken, Nil))
        _ <- SessionMailbox.unlinkTaken(f.parentId, taken.map(_.identity))
        _ <- ExternalSessionRunner.notifyParent(run.spec, run.id, ExternalSessionRunner.Outcome.Completed,
          "Stable notification report.", None, result.row.runId)
        afterConsumption <- SessionMailbox.scanPending(f.parentId)
        delivered <- SessionMailbox.deliveredContains(f.parentId, Set(result.mail.identity))
        rows <- f.freshTasks.loadTasks(f.parentId)
      yield
        assertEquals(afterConsumption, Nil)
        assertEquals(delivered, Set(result.mail.identity))
        assertEquals(rows.find(_.taskId == run.id), Some(result.row))
    }
  }

  private def persistPendingTerminal(
    f: Fixture, sessionId: String, runId: String, texts: List[String]
  ): IO[SubAgentTask] =
    val task = SubAgentTask(
      taskId = sessionId,
      parentSessionId = f.parentId,
      agentName = "codex",
      prompt = "Synthetic recovery input, not assistant output.",
      description = "Pending external result recovery",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )
    def committed(result: Either[String, SubAgentTask]): IO[SubAgentTask] =
      result.fold(error => IO.raiseError(new AssertionError(s"Recovery fixture write failed: $error")), IO.pure)
    for
      _ <- f.freshTasks.beginRun(task, runId, f.rootId, "codex").flatMap(committed)
      _ <- f.resources.sessionStore.getOrCreateSession(sessionId, task.description)
      _ <- f.resources.sessionStore.appendDurableMessage(sessionId,
        Message(MessageRole.User, Left(task.prompt), source = Some("delegate"),
          messageId = Some(s"$runId-input"), runId = Some(runId)))
      _ <- texts.zipWithIndex.traverse_ { case (text, index) =>
        f.resources.sessionStore.appendDurableMessage(sessionId,
          Message(MessageRole.Assistant, Left(text), source = Some("delegate"),
            messageId = Some(s"$runId-report-$index"), runId = Some(runId))).void
      }
      row <- f.freshTasks.completeRun(f.parentId, sessionId, runId,
        executionStatus = "finished", resultRef = Some(s"$sessionId#$runId"),
        notificationId = Some(s"$sessionId#$runId")).flatMap(committed)
    yield row

  test("Recovery enqueues the full ordered durable transcript and clears pending exactly once") {
    fixture.use { f =>
      val sessionId = "external-codex-recovery-missing-mail"
      val runId = "recovery-current-run"
      val texts = List("First durable report.", "Repeated report.", "Repeated report.", "Final durable report.")
      for
        before <- persistPendingTerminal(f, sessionId, runId, texts)
        _ <- f.resources.sessionStore.appendDurableMessage(sessionId,
          Message(MessageRole.Assistant, Left("Unrelated previous execution output."),
            messageId = Some("previous-report"), runId = Some("previous-run")))
        persistedBefore <- f.freshTasks.findByTaskId(sessionId)
        missing <- SessionMailbox.scanPending(f.parentId)
        _ <- IO {
          assertEquals(persistedBefore, Some(before))
          assertEquals(before.notificationPending, Some(true))
          assertEquals(missing, Nil)
        }
        _ <- ExternalSessionRunner.recoverPendingNotifications(f.freshTasks, f.freshSessions)
        pending <- SessionMailbox.scanPending(f.parentId)
        after <- f.freshTasks.findByTaskId(sessionId)
        _ <- IO {
          assertEquals(pending.size, 1)
          val entry = pending.head
          assertEquals(entry.identity, s"$sessionId#$runId")
          assertEquals(entry.kind, "delegate-terminal")
          assertEquals(entry.event.eventType, "completed")
          assertEquals(entry.event.correlationId, Some(sessionId))
          assertEquals(entry.event.metadata("completionGeneration").flatMap(_.asString), Some(runId))
          assertEquals(entry.event.payload,
            s"${before.description}: Execution finished; requirement completion is not inferred.\n" +
              texts.mkString("\n\n") + s"\n[Result history: $sessionId]")
          assertEquals(after.map(_.notificationPending), Some(Some(false)))
          assertEquals(after.map(_.notificationId), Some(before.notificationId))
          assertEquals(after.map(_.runId), Some(before.runId))
          assertEquals(after.map(_.resultRef), Some(before.resultRef))
          assertEquals(after.map(_.executionStatus), Some(before.executionStatus))
          assert(after.flatMap(_.committedRevision).get > before.committedRevision.get)
        }
        _ <- ExternalSessionRunner.recoverPendingNotifications(f.freshTasks, f.freshSessions)
        repeated <- SessionMailbox.scanPending(f.parentId)
        unchanged <- f.freshTasks.findByTaskId(sessionId)
      yield
        assertEquals(repeated, pending)
        assertEquals(unchanged, after, "A second recovery must not even rewrite the acknowledged task")
    }
  }

  test("A consumed old-run tombstone does not suppress recovery of a new run for the same session") {
    fixture.use { f =>
      val sessionId = "external-codex-recovery-next-run"
      val oldRunId = "consumed-old-run"
      val newRunId = "pending-new-run"
      val newTexts = List("New run first report.", "New run final report.")
      val oldIdentity = s"$sessionId#$oldRunId"
      val newIdentity = s"$sessionId#$newRunId"
      for
        _ <- persistPendingTerminal(f, sessionId, oldRunId, List("Old consumed report."))
        _ <- ExternalSessionRunner.recoverPendingNotifications(f.freshTasks, f.freshSessions)
        oldPending <- SessionMailbox.scanPending(f.parentId)
        _ <- IO(assertEquals(oldPending.map(_.identity), List(oldIdentity)))
        taken <- SessionMailbox.take(f.parentId, oldPending)
        _ <- IO(assertEquals(taken, oldPending))
        _ <- SessionMailbox.unlinkTaken(f.parentId, taken.map(_.identity))
        tombstonesBefore <- SessionMailbox.deliveredContains(f.parentId, Set(oldIdentity, newIdentity))
        before <- persistPendingTerminal(f, sessionId, newRunId, newTexts)
        missing <- SessionMailbox.scanPending(f.parentId)
        _ <- IO {
          assertEquals(tombstonesBefore, Set(oldIdentity))
          assertEquals(missing, Nil)
          assertEquals(before.runId, Some(newRunId))
          assertEquals(before.notificationPending, Some(true))
        }
        _ <- ExternalSessionRunner.recoverPendingNotifications(f.freshTasks, f.freshSessions)
        pending <- SessionMailbox.scanPending(f.parentId)
        after <- f.freshTasks.findByTaskId(sessionId)
        history <- f.freshSessions.loadMessagesForSession(sessionId)
        _ <- IO {
          assert(history.exists(_.runId.contains(oldRunId)), "The old transcript must still exist")
          assertEquals(pending.map(_.identity), List(newIdentity))
          assertEquals(pending.head.event.metadata("completionGeneration").flatMap(_.asString), Some(newRunId))
          assert(pending.head.event.payload.contains(newTexts.mkString("\n\n")))
          assert(!pending.head.event.payload.contains("Old consumed report."))
          assertEquals(after.map(_.runId), Some(Some(newRunId)))
          assertEquals(after.map(_.notificationId), Some(Some(newIdentity)))
          assertEquals(after.map(_.notificationPending), Some(Some(false)))
        }
        _ <- ExternalSessionRunner.recoverPendingNotifications(f.freshTasks, f.freshSessions)
        repeated <- SessionMailbox.scanPending(f.parentId)
        unchanged <- f.freshTasks.findByTaskId(sessionId)
        tombstonesAfter <- SessionMailbox.deliveredContains(f.parentId, Set(oldIdentity, newIdentity))
      yield
        assertEquals(repeated, pending)
        assertEquals(unchanged, after)
        assertEquals(tombstonesAfter, Set(oldIdentity), "Recovery must not consume the new notification")
    }
  }

  test("Spawn acceptance and durable run identity are visible before delayed process output") {
    fixture.use { f =>
      for
        run <- spawn(f, "codex", List(codexMessage("delayed-report", "Released fixture report."), codexEnd), gated = true)
        _ <- eventually("isolated fixture readiness")(IO.blocking(os.exists(run.script.pid)))(identity)
        rows <- f.freshTasks.loadTasks(f.parentId)
        row <- IO.fromOption(rows.find(_.taskId == run.id))(new AssertionError("Accepted task was not persisted"))
        messages <- f.freshSessions.loadMessagesForSession(run.id)
        pending <- SessionMailbox.scanPending(f.parentId)
        frames <- f.frames.get
        output <- ExternalOutputStore.read(run.id, 0L)
        _ <- IO {
          assertEquals(row.executionStatus, Some("running"))
          assertEquals(row.status, "running")
          assert(row.runId.exists(_.nonEmpty))
          assert(row.committedRevision.exists(_ >= 1L))
          assertEquals(row.rootSessionId, Some(f.rootId))
          assertEquals(row.completedAt, None)
          assertEquals(row.notificationId, None)
          assertEquals(messages.map(_.role), List(MessageRole.User))
          assertEquals(messages.head.runId, row.runId)
          assertEquals(pending, Nil)
          assertEquals(output.map(_.totalLines), Some(0L))
          assert(frames.exists(_.hcursor.get[String]("type").toOption.contains("agentStart")))
          assert(!frames.exists(_.hcursor.get[String]("type").toOption.contains("agentDone")))
        }
        _ <- IO.blocking(os.write(run.script.release, "release"))
        result <- settle(f, run)
      yield
        assertReport(result, List("Released fixture report."))
        assertEquals(result.row.runId, row.runId)
        assert(result.row.committedRevision.get > row.committedRevision.get)
    }
  }

  private def cleanup(f: Fixture): IO[Unit] =
    f.owned.get.flatMap(_.traverse_ { run =>
      // Releasing a gate lets a healthy fixture finish even when an assertion failed.
      IO.blocking {
        if !os.exists(run.script.release) then os.write(run.script.release, "cleanup")
      } *> ExternalOutputStore.processOf(run.id).flatMap {
        case None => IO.unit
        case Some(process) =>
          IO.blocking(process.waitFor(1, TimeUnit.SECONDS)).flatMap { exited =>
            val stop = if exited then IO.unit else
              IO.blocking {
                val recorded = os.read(run.script.pid).trim.toLong
                require(recorded == process.pid(), "Refusing to stop a process not owned by this fixture")
                require(process.pid() != ProcessHandle.current().pid(), "Refusing to signal the test JVM")
                val command = process.toHandle.info().commandLine().orElse("")
                require(command.contains(run.script.bin.toString), "Fixture process executable identity changed")
              } *> ExternalSessionRunner.cancel(run.id, "Integration fixture cleanup", "test").void *>
                IO.blocking {
                  if !process.waitFor(2, TimeUnit.SECONDS) then
                    process.destroyForcibly()
                    require(process.waitFor(2, TimeUnit.SECONDS), "Synthetic CLI did not exit during cleanup")
                }
            stop *> IO.blocking {
              process.getOutputStream.close()
              process.getInputStream.close()
              process.getErrorStream.close()
            }
          }
      } *> eventually("fixture runner cleanup")(f.resources.agentRegistry.get)(registry => !registry.contains(run.id)).void
    })

end ExternalResultReliabilitySpec
