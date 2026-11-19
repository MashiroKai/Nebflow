package nebflow.core

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.shared.{ContentBlock, Message, MessageRole, UiMessage, given}

import scala.concurrent.duration.*

class SessionStoreMessageIdentitySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 15.seconds

  private def fixture: Resource[IO, (os.Path, SessionStore)] =
    Resource.make(IO.blocking(os.temp.dir(prefix = "session-message-identity-"))) { root =>
      // Let the store's existing one-shot debounce timer finish before removing its root.
      IO.sleep(2100.millis) *> IO.blocking(os.remove.all(root))
    }.map(root => (root, SessionStore(root / "sessions", root / "tasks")))

  private def message(id: Option[String] = Some("message-1"), run: Option[String] = Some("run-1")): Message =
    Message(MessageRole.User, Left("same text"), 42L, Some("subtask"), id, run)

  private def disk(root: os.Path, id: String): IO[List[Message]] =
    IO.blocking(decode[List[Message]](os.read(root / "sessions" / s"$id.json")).fold(throw _, identity))

  test("first switch does not write an empty session and failed outgoing writes preserve the active session") {
    fixture.use { case (root, store) =>
      for
        first <- store.createSession("first")
        second <- store.createSession("second")
        _ <- store.switchSession(first.id)
        initial <- store.getActiveId
        emptyFile <- IO.blocking(os.exists(root / "sessions" / ".json"))
        before <- store.getActiveMessages
        _ <- IO.blocking(os.makeDir.all(root / "sessions" / first.id / "executions"))
        failed <- store.switchSession(second.id).attempt
        after <- store.getActiveId
        messages <- store.getActiveMessages
        _ <- IO.blocking(os.remove.all(root / "sessions" / first.id / "executions"))
        switched <- store.switchSession(second.id)
        finalId <- store.getActiveId
      yield
        assertEquals(initial, first.id)
        assert(!emptyFile)
        assert(failed.isLeft)
        assertEquals(after, first.id)
        assertEquals(messages, before)
        assertEquals(switched, Nil)
        assertEquals(finalId, second.id)
    }
  }

  test("new messages are durable before publication and disk duplicates survive a fresh store") {
    fixture.use { case (root, store) =>
      for
        meta <- store.createSession("identity")
        _ <- store.switchSession(meta.id)
        observed <- Ref.of[IO, List[(List[Message], List[Message])]](Nil)
        _ <- IO(store.setSessionChangedHook(id =>
          (disk(root, id), store.getActiveMessages).tupled.flatMap(snapshot => observed.update(_ :+ snapshot))))
        added <- store.appendDurableMessage(meta.id, message())
        persisted <- disk(root, meta.id)
        snapshots <- observed.get
        restarted = SessionStore(root / "sessions", root / "tasks")
        duplicate <- restarted.appendDurableMessage(meta.id, message().copy(content = Left("different text")))
        duplicateHere <- store.appendDurableMessage(meta.id, message())
        after <- disk(root, meta.id)
        afterSnapshots <- observed.get
      yield
        assert(added)
        assert(!duplicate)
        assert(!duplicateHere)
        assertEquals(persisted, List(message()))
        assertEquals(after, persisted)
        assertEquals(snapshots, List((persisted, persisted)))
        assertEquals(afterSnapshots, snapshots)
    }
  }

  test("identity is scoped by optional run id and session, never by text or run alone") {
    fixture.use { case (root, store) =>
      val messages = List(
        message(),
        message(run = Some("run-2")),
        message(run = None),
        message(id = Some("message-2")),
        message(id = None),
        message(id = None),
        message(id = None, run = None),
        message(id = None, run = None)
      )
      for
        results <- messages.traverse(store.appendDurableMessage("one", _))
        duplicate <- store.appendDurableMessage("one", message(run = None))
        other <- store.appendDurableMessage("two", message())
        first <- disk(root, "one")
        second <- disk(root, "two")
      yield
        assert(results.forall(identity))
        assert(!duplicate)
        assert(other)
        assertEquals(first, messages)
        assertEquals(second, List(message()))
    }
  }

  test("concurrent identical and distinct appends are serialized without loss") {
    fixture.use { case (root, store) =>
      for
        duplicates <- List.fill(16)(message()).parTraverse(store.appendDurableMessage("concurrent", _))
        distinct = (1 to 12).toList.map(i => message(id = Some(s"distinct-$i")))
        additions <- distinct.parTraverse(store.appendDurableMessage("concurrent", _))
        persisted <- disk(root, "concurrent")
      yield
        assertEquals(duplicates.count(identity), 1)
        assert(additions.forall(identity))
        assertEquals(persisted.size, 13)
        assertEquals(persisted.toSet, (message() :: distinct).toSet)
    }
  }

  test("append preserves the newest pending full transcript and removes stale flush work") {
    fixture.use { case (root, store) =>
      val previous = message(id = Some("previous"))
      val pending = List(previous, message(id = None).copy(content = Left("pending tail")))
      for
        _ <- store.durableSwapMessages("pending", List(previous))
        _ <- store.saveMessagesForSession("pending", pending)
        added <- store.appendDurableMessage("pending", message())
        persisted <- disk(root, "pending")
        _ <- store.flushPendingMessages
        afterFlush <- disk(root, "pending")
      yield
        assert(added)
        assertEquals(persisted, pending :+ message())
        assertEquals(afterFlush, persisted)
    }
  }

  test("an identity found only in pending messages is made durable before returning false") {
    fixture.use { case (root, store) =>
      val pending = List(message(), message(id = None))
      for
        _ <- store.saveMessagesForSession("pending-duplicate", pending)
        added <- store.appendDurableMessage("pending-duplicate", message())
        persisted <- disk(root, "pending-duplicate")
        _ <- store.flushPendingMessages
        afterFlush <- disk(root, "pending-duplicate")
      yield
        assert(!added)
        assertEquals(persisted, pending)
        assertEquals(afterFlush, pending)
    }
  }

  test("append waits for a flush that captured an older pending snapshot") {
    fixture.use { case (root, store) =>
      val old = List(message(id = Some("old")))
      for
        captured <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        _ <- IO { store.beforeDirtyMessageWrite = _ => captured.complete(()).void *> release.get }
        _ <- store.saveMessagesForSession("flush-race", old)
        _ <- (for
          flush <- store.flushPendingMessages.start
          _ <- captured.get
          append <- store.appendDurableMessage("flush-race", message()).start
          _ <- release.complete(())
          _ <- flush.joinWithNever
          added <- append.joinWithNever
          persisted <- disk(root, "flush-race")
        yield
          assert(added)
          assertEquals(persisted, old :+ message())
        ).guarantee(release.complete(()).void *> IO { store.beforeDirtyMessageWrite = _ => IO.unit })
      yield ()
    }
  }

  test("failed writes preserve pending state, active reference and index, and allow retry") {
    fixture.use { case (root, store) =>
      for
        meta <- store.createSession("failure")
        _ <- store.switchSession(meta.id)
        pending = List(message(id = Some("pending")))
        _ <- store.saveMessagesForSession(meta.id, pending)
        beforeMeta <- store.getSessionMeta(meta.id)
        notifications <- Ref.of[IO, Int](0)
        _ <- IO(store.setSessionChangedHook(_ => notifications.update(_ + 1)))
        file = root / "sessions" / s"${meta.id}.json"
        failed <- Resource.make(IO.blocking {
          os.remove(file)
          os.makeDir(file)
          os.write(file / "keep", "old")
        })(_ => IO.blocking(os.remove.all(file))).use { _ =>
          for
            failed <- store.appendDurableMessage(meta.id, message()).attempt
            preserved <- store.loadMessagesForSession(meta.id)
            active <- store.getActiveMessages
            afterMeta <- store.getSessionMeta(meta.id)
            count <- notifications.get
            evidence <- IO.blocking(os.read(file / "keep"))
          yield
            assert(failed.isLeft)
            assertEquals(preserved, pending)
            assertEquals(active, pending)
            assertEquals(afterMeta, beforeMeta)
            assertEquals(count, 0)
            assertEquals(evidence, "old")
            failed
        }
        retried <- store.appendDurableMessage(meta.id, message())
        persisted <- disk(root, meta.id)
        count <- notifications.get
      yield
        assert(failed.isLeft)
        assert(retried)
        assertEquals(persisted, pending :+ message())
        assertEquals(count, 1)
    }
  }

  test("pending duplicate write failures are propagated rather than acknowledged") {
    fixture.use { case (root, store) =>
      val file = root / "sessions" / "duplicate-failure.json"
      for
        _ <- store.saveMessagesForSession("duplicate-failure", List(message()))
        _ <- Resource.make(IO.blocking(os.makeDir.all(file)))(_ => IO.blocking(os.remove.all(file))).use { _ =>
          store.appendDurableMessage("duplicate-failure", message()).attempt.map(result => assert(result.isLeft))
        }
        added <- store.appendDurableMessage("duplicate-failure", message())
        persisted <- disk(root, "duplicate-failure")
      yield
        assert(!added)
        assertEquals(persisted, List(message()))
    }
  }

  test("corrupt or unreadable current transcripts fail without overwriting or quarantining them") {
    fixture.use { case (root, store) =>
      val corrupt = root / "sessions" / "corrupt.json"
      val unreadable = root / "sessions" / "unreadable.json"
      for
        _ <- IO.blocking {
          os.write(corrupt, "[broken", createFolders = true)
          os.makeDir(unreadable)
          os.write(unreadable / "keep", "evidence")
        }
        corruptResult <- store.appendDurableMessage("corrupt", message()).attempt
        unreadableResult <- store.appendDurableMessage("unreadable", message()).attempt
        raw <- IO.blocking(os.read(corrupt))
        evidence <- IO.blocking(os.read(unreadable / "keep"))
        files <- IO.blocking(os.list(root / "sessions").map(_.last).toSet)
      yield
        assert(corruptResult.isLeft)
        assert(unreadableResult.isLeft)
        assertEquals(raw, "[broken")
        assertEquals(evidence, "evidence")
        assertEquals(files, Set("corrupt.json", "unreadable.json"))
    }
  }

  test("full disk transcripts are retained without a new message-count cap") {
    fixture.use { case (root, store) =>
      val history = (1 to 2000).toList.map(i => message(id = Some(s"old-$i")))
      for
        _ <- IO.blocking(os.write(root / "sessions" / "long.json", history.asJson.noSpaces, createFolders = true))
        added <- store.appendDurableMessage("long", message())
        persisted <- disk(root, "long")
      yield
        assert(added)
        assertEquals(persisted, history :+ message())
    }
  }

  test("external history projects durable user and assistant messages without a UI file") {
    fixture.use { case (root, store) =>
      val sid = "external-test-history"
      val user = message().copy(content = Left("user request"))
      val assistant = message(id = Some("reply")).copy(
        role = MessageRole.Assistant,
        content = Left("durable external answer"),
        timestamp = 43L
      )
      val expected: List[UiMessage] = List(
        UiMessage.User("user request", injected = true, timestamp = 42L, source = Some("subtask")),
        UiMessage.Ai("durable external answer", timestamp = 43L)
      )
      for
        _ <- store.appendDurableMessage(sid, user)
        _ <- store.appendDurableMessage(sid, assistant)
        page <- store.getHistoryPage(sid, 10, None)
        ui <- store.getUiMessages(sid, 0, 10)
        restarted = SessionStore(root / "sessions", root / "tasks")
        restored <- restarted.getHistoryPage(sid, 10, None)
        hasUiFile <- IO.blocking(os.exists(root / "sessions" / s"$sid.ui.json"))
      yield
        assertEquals(page, (expected, 2, 0, false))
        assertEquals(ui, (expected, 2))
        assertEquals(restored, page)
        assert(!hasUiFile)
    }
  }

  test("external history ignores stale UI data and paginates the uncapped transcript") {
    fixture.use { case (root, store) =>
      val sid = "external-test-pages"
      val history = (1 to 2000).toList.map(i =>
        message(id = Some(s"old-$i")).copy(role = MessageRole.Assistant, content = Left(s"answer-$i")))
      def rows(from: Int, end: Int): List[UiMessage] =
        (from to end).toList.map(i => UiMessage.Ai(s"answer-$i", timestamp = 42L))
      for
        _ <- IO.blocking {
          os.write(root / "sessions" / s"$sid.json", history.asJson.noSpaces, createFolders = true)
          os.write(root / "sessions" / s"$sid.ui.json", List[UiMessage](UiMessage.Ai("stale")).asJson.noSpaces)
        }
        latest <- store.getHistoryPage(sid, 2, None)
        previous <- store.getHistoryPage(sid, 2, Some(latest._3))
        first <- store.getUiMessages(sid, 0, 2)
        all <- store.getUiMessages(sid, 0, 0)
        _ <- store.appendDurableMessage(sid, message(id = Some("last")).copy(
          role = MessageRole.Assistant, content = Left("answer-2001")))
        updated <- store.getHistoryPage(sid, 2, None)
      yield
        assertEquals(latest, (rows(1999, 2000), 2000, 1998, true))
        assertEquals(previous, (rows(1997, 1998), 2000, 1996, true))
        assertEquals(first, (rows(1, 2), 2000))
        assertEquals(all._1.size, 2000)
        assertEquals(updated, (rows(2000, 2001), 2001, 1999, true))
    }
  }

  test("internal session history still reads its UI history rather than main messages") {
    fixture.use { case (root, store) =>
      val sid = "internal-test-history"
      val expected: List[UiMessage] = List(UiMessage.Ai("existing internal UI"))
      for
        _ <- store.appendDurableMessage(sid, message().copy(content = Left("main transcript only")))
        _ <- IO.blocking(os.write(root / "sessions" / s"$sid.ui.json", expected.asJson.noSpaces))
        page <- store.getHistoryPage(sid, 10, None)
        ui <- store.getUiMessages(sid, 0, 10)
      yield
        assertEquals(page, (expected, 1, 0, false))
        assertEquals(ui, (expected, 1))
    }
  }

  test("terminal envelopes preserve the exact event across restart without entering model history") {
    fixture.use { case (root, store) =>
      val event = AgentCommand.ExternalEvent(
        source = "subtask",
        eventType = "completed",
        payload = "result\nwith exact whitespace\n",
        metadata = io.circe.JsonObject("nested" -> io.circe.Json.obj("key" -> "value".asJson)),
        correlationId = Some("correlation"),
        blocks = Some(List(ContentBlock.Text("block text"), ContentBlock.Image("data", "image/png")))
      )
      for
        absent <- store.loadTerminalEnvelope("terminal-test", "run/one")
        ref <- store.saveTerminalEnvelope("terminal-test", "run/one", event)
        restarted = SessionStore(root / "sessions", root / "tasks")
        restored <- restarted.loadTerminalEnvelope("terminal-test", "run/one")
        different = event.copy(payload = "second run", blocks = None, correlationId = None)
        otherRef <- store.saveTerminalEnvelope("terminal-test", "run_one", different)
        original <- store.loadTerminalEnvelope("terminal-test", "run/one")
        other <- store.loadTerminalEnvelope("terminal-test", "run_one")
        history <- store.loadMessagesForSession("terminal-test")
      yield
        assertEquals(absent, None)
        assertEquals(restored, Some(event))
        assertEquals(original, Some(event))
        assertEquals(other, Some(different))
        assert(ref != otherRef)
        assertEquals(os.Path(ref) / os.up, root / "sessions" / "terminal-test")
        assertEquals(history, Nil)
    }
  }

  test("terminal envelopes are write-once and conflicting events preserve the original bytes") {
    fixture.use { case (_, store) =>
      val event = AgentCommand.ExternalEvent("subtask", "completed", "original result")
      for
        ref <- store.saveTerminalEnvelope("terminal-test", "run", event)
        before <- IO.blocking(os.read.bytes(os.Path(ref)).toList)
        sameRef <- store.saveTerminalEnvelope("terminal-test", "run", event)
        afterRetry <- IO.blocking(os.read.bytes(os.Path(ref)).toList)
        conflict <- store.saveTerminalEnvelope("terminal-test", "run", event.copy(payload = "conflicting result")).attempt
        afterConflict <- IO.blocking(os.read.bytes(os.Path(ref)).toList)
        restored <- store.loadTerminalEnvelope("terminal-test", "run")
      yield
        assertEquals(sameRef, ref)
        assertEquals(afterRetry, before)
        assert(conflict.isLeft)
        assertEquals(afterConflict, before)
        assertEquals(restored, Some(event))
    }
  }

  test("terminal envelope IO and decoding failures propagate and permit a later retry") {
    fixture.use { case (root, store) =>
      val event = AgentCommand.ExternalEvent("subtask", "failed", "failure")
      for
        ref <- store.saveTerminalEnvelope("terminal-test", "run", event)
        file = os.Path(ref)
        _ <- IO.blocking(os.write.over(file, "[broken"))
        corrupt <- store.loadTerminalEnvelope("terminal-test", "run").attempt
        _ <- IO.blocking {
          os.remove(file)
          os.makeDir(file)
          os.write(file / "keep", "evidence")
        }
        failed <- store.saveTerminalEnvelope("terminal-test", "run", event).attempt
        evidence <- IO.blocking(os.read(file / "keep"))
        _ <- IO.blocking(os.remove.all(file))
        _ <- store.saveTerminalEnvelope("terminal-test", "run", event)
        restored <- store.loadTerminalEnvelope("terminal-test", "run")
        invalid <- store.saveTerminalEnvelope("../escape", "run", event).attempt
      yield
        assert(corrupt.isLeft)
        assert(failed.isLeft)
        assertEquals(evidence, "evidence")
        assertEquals(restored, Some(event))
        assert(invalid.isLeft)
    }
  }

end SessionStoreMessageIdentitySpec
