package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Tests for SessionStore cloud sync integration:
 *   - mergeCloudIndex (merge by ID, newest updatedAt wins)
 *   - setSessionFromCloud (overwrite local session from cloud data)
 *   - listAllFolders
 *   - Session changed hook
 */
class CloudSyncSpec extends CatsEffectSuite:

  private def withStore(test: SessionStore => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-cloudsync-test")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val tasksDir = os.Path(tmp.resolve("tasks"))
    try
      val store = SessionStore(sessionsDir, tasksDir)
      store.load.unsafeRunSync()
      test(store).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  private def meta(id: String, name: String, updatedAt: Long): SessionMeta =
    SessionMeta(id, name, updatedAt, updatedAt, hasUnread = false)

  // ===== mergeCloudIndex =====

  test("mergeCloudIndex adds new sessions from cloud") {
    withStore { store =>
      for
        _ <- store.createSession("Local Session")
        cloudSessions = List(meta("cloud-1", "Cloud Session 1", 2000L))
        _ <- store.mergeCloudIndex(cloudSessions, Nil)
        all <- store.listSessions
      yield
        assert(all.exists(_.name == "Local Session"), "Local session should be preserved")
        assert(all.exists(_.id == "cloud-1"), "Cloud session should be added")
    }
  }

  test("mergeCloudIndex keeps local when local is newer") {
    withStore { store =>
      for
        created <- store.createSession("Test")
        localId = created.id
        // Cloud has same ID but older updatedAt
        older = List(meta(localId, "Cloud Name", 1L))
        _ <- store.mergeCloudIndex(older, Nil)
        sessions <- store.listSessions
        target = sessions.find(_.id == localId)
      yield
        assert(target.isDefined)
        assertEquals(target.get.name, "Test", "Local name should win when local is newer")
    }
  }

  test("mergeCloudIndex takes cloud when cloud is newer") {
    withStore { store =>
      for
        created <- store.createSession("Local Name")
        localId = created.id
        // Cloud has same ID but much newer updatedAt
        newer = List(SessionMeta(localId, "Cloud Updated", 1L, System.currentTimeMillis() + 10000, hasUnread = false))
        _ <- store.mergeCloudIndex(newer, Nil)
        sessions <- store.listSessions
        target = sessions.find(_.id == localId)
      yield
        assert(target.isDefined)
        assertEquals(target.get.name, "Cloud Updated", "Cloud name should win when cloud is newer")
    }
  }

  test("mergeCloudIndex merges folders by ID") {
    withStore { store =>
      for
        localFolder <- store.createFolder("Local Folder")
        cloudFolders = List(Folder("cloud-f-1", "Cloud Folder", None, "", None, 1000L, 1000L))
        _ <- store.mergeCloudIndex(Nil, cloudFolders)
        all <- store.listAllFolders
      yield
        assert(all.exists(_.id == localFolder.id), "Local folder should be preserved")
        assert(all.exists(_.id == "cloud-f-1"), "Cloud folder should be added")
    }
  }

  test("mergeCloudIndex with empty cloud data is no-op") {
    withStore { store =>
      for
        _ <- store.createSession("Test")
        _ <- store.mergeCloudIndex(Nil, Nil)
        sessions <- store.listSessions
      yield assert(sessions.length >= 1, "Sessions should be unchanged")
    }
  }

  test("mergeCloudIndex with multiple new sessions adds all") {
    withStore { store =>
      for
        _ <- store.mergeCloudIndex(
          List(
            meta("c1", "Cloud 1", 1000L),
            meta("c2", "Cloud 2", 2000L),
            meta("c3", "Cloud 3", 3000L)
          ),
          Nil
        )
        sessions <- store.listSessions
      yield
        val ids = sessions.map(_.id).toSet
        assert(ids.contains("c1") && ids.contains("c2") && ids.contains("c3"), "All cloud sessions should be added")
    }
  }

  // ===== setSessionFromCloud =====

  test("setSessionFromCloud writes messages and updates index") {
    withStore { store =>
      val sid = "cloud-import-test"
      val msgs = List(Message(MessageRole.User, Left("Hello from cloud")))
      val uiMsgs = List(UiMessage.User("Hello from cloud"))
      for
        _ <- store.mergeCloudIndex(List(meta(sid, "Cloud Import", System.currentTimeMillis())), Nil)
        _ <- store.setSessionFromCloud(sid, msgs, uiMsgs)
        loaded <- store.loadMessagesForSession(sid)
        (uiLoaded, count) <- store.getUiMessages(sid, 0, 10)
      yield
        assertEquals(loaded.length, 1, "Should have 1 message")
        assertEquals(loaded.head.textContent, "Hello from cloud")
        assertEquals(count, 1, "Should have 1 UI message")
    }
  }

  test("setSessionFromCloud updates activeMessagesRef when session is active") {
    withStore { store =>
      val sid = "active-cloud-test"
      val msgs = List(Message(MessageRole.User, Left("Active session cloud data")))
      for
        _ <- store.mergeCloudIndex(List(meta(sid, "Active Cloud", System.currentTimeMillis())), Nil)
        _ <- store.switchSession(sid)
        _ <- store.setSessionFromCloud(sid, msgs, Nil)
        active <- store.getActiveMessages
      yield assert(
        active.exists(_.textContent == "Active session cloud data"),
        "Active messages should reflect cloud data"
      )
    }
  }

  // ===== listAllFolders =====

  test("listAllFolders returns all folders regardless of agent") {
    withStore { store =>
      for
        _ <- store.createFolder("Folder A", agentName = "Nebula")
        _ <- store.createFolder("Folder B", agentName = "Lyra")
        _ <- store.createFolder("Folder C", agentName = "")
        all <- store.listAllFolders
      yield
        val names = all.map(_.name).toSet
        assert(
          names.contains("Folder A") && names.contains("Folder B") && names.contains("Folder C"),
          "All folders should be returned regardless of agent"
        )
    }
  }

  // ===== Session Changed Hook =====

  test("session changed hook is called on setActiveMessages") {
    withStore { store =>
      var hookCalled = false
      store.setSessionChangedHook(_ => IO { hookCalled = true })
      for
        _ <- store.createSession("Hook Test")
        _ <- store.setActiveMessages(List(Message(MessageRole.User, Left("test"))))
      yield assert(hookCalled, "Hook should be called when messages are set")
    }
  }

  test("session changed hook receives correct sessionId") {
    withStore { store =>
      var receivedId = ""
      store.setSessionChangedHook(sid => IO { receivedId = sid })
      for
        created <- store.createSession("ID Test")
        _ <- store.switchSession(created.id)
        _ <- store.setActiveMessages(List(Message(MessageRole.User, Left("test"))))
      yield assertEquals(receivedId, created.id, "Hook should receive the active session ID")
    }
  }

  test("session changed hook is called on saveMessagesForSession") {
    withStore { store =>
      var hookCalled = false
      var receivedId = ""
      store.setSessionChangedHook(sid => IO { hookCalled = true; receivedId = sid })
      for
        meta <- store.createSession("Save Test")
        _ <- store.saveMessagesForSession(meta.id, List(Message(MessageRole.User, Left("saved"))))
      yield
        assert(hookCalled, "Hook should be called on saveMessagesForSession")
        assertEquals(receivedId, meta.id)
    }
  }

  // ===== mergeMessages (WeChat-like content merge) =====

  test("mergeMessages: both empty → empty") {
    withStore { store =>
      val merged = store.mergeMessages(Nil, Nil)
      IO(assertEquals(merged, Nil))
    }
  }

  test("mergeMessages: local only → unchanged") {
    withStore { store =>
      val local = List(Message(MessageRole.User, Left("hello"), 1000L))
      val merged = store.mergeMessages(local, Nil)
      IO(assertEquals(merged.length, 1))
    }
  }

  test("mergeMessages: cloud only → cloud messages") {
    withStore { store =>
      val cloud = List(Message(MessageRole.Assistant, Left("world"), 2000L))
      val merged = store.mergeMessages(Nil, cloud)
      IO(assertEquals(merged.length, 1))
    }
  }

  test("mergeMessages: same content on both sides → deduplicated") {
    withStore { store =>
      val local = List(Message(MessageRole.User, Left("hello"), 1000L))
      val cloud = List(Message(MessageRole.User, Left("hello"), 1000L))
      val merged = store.mergeMessages(local, cloud)
      IO(assertEquals(merged.length, 1, "Duplicate message should be deduplicated"))
    }
  }

  test("mergeMessages: different messages on both sides → union") {
    withStore { store =>
      val local = List(
        Message(MessageRole.User, Left("question A"), 1000L),
        Message(MessageRole.Assistant, Left("answer A"), 2000L)
      )
      val cloud = List(
        Message(MessageRole.User, Left("question B"), 3000L),
        Message(MessageRole.Assistant, Left("answer B"), 4000L)
      )
      val merged = store.mergeMessages(local, cloud)
      IO(assertEquals(merged.length, 4, "All unique messages should be in the union"))
    }
  }

  test("mergeMessages: result is sorted by timestamp") {
    withStore { store =>
      val local = List(
        Message(MessageRole.User, Left("late"), 5000L),
        Message(MessageRole.Assistant, Left("earliest"), 1000L)
      )
      val cloud = List(
        Message(MessageRole.User, Left("middle"), 3000L)
      )
      val merged = store.mergeMessages(local, cloud)
      IO {
        assertEquals(merged.length, 3)
        assertEquals(merged.head.timestamp, 1000L, "Earliest should be first")
        assertEquals(merged.last.timestamp, 5000L, "Latest should be last")
      }
    }
  }

  test("mergeMessages: first-connection merge (disjoint histories)") {
    withStore { store =>
      // Device A had conversation about topic 1
      val deviceA = List(
        Message(MessageRole.User, Left("tell me about physics"), 1000L),
        Message(MessageRole.Assistant, Left("physics is..."), 2000L),
        Message(MessageRole.User, Left("thanks"), 3000L)
      )
      // Device B had conversation about topic 2
      val deviceB = List(
        Message(MessageRole.User, Left("help with coding"), 4000L),
        Message(MessageRole.Assistant, Left("sure, let's..."), 5000L)
      )
      val merged = store.mergeMessages(deviceA, deviceB)
      IO {
        assertEquals(merged.length, 5, "All messages from both devices should be merged")
        assertEquals(merged.map(_.timestamp), List(1000L, 2000L, 3000L, 4000L, 5000L), "Should be chronological")
      }
    }
  }

  test("mergeUiMessages: takes longer list") {
    withStore { store =>
      val local = List(UiMessage.User("a"), UiMessage.User("b"))
      val cloud = List(UiMessage.User("a"), UiMessage.User("b"), UiMessage.User("c"), UiMessage.User("d"))
      val merged = store.mergeUiMessages(local, cloud)
      IO(assertEquals(merged.length, 4, "Should take the longer list (cloud)"))
    }
  }

  test("mergeUiMessages: equal length prefers local") {
    withStore { store =>
      val local = List(UiMessage.User("local-a"), UiMessage.User("local-b"))
      val cloud = List(UiMessage.User("cloud-a"), UiMessage.User("cloud-b"))
      val merged = store.mergeUiMessages(local, cloud)
      IO(
        assertEquals(merged.head.asInstanceOf[UiMessage.User].text, "local-a", "Should prefer local when equal length")
      )
    }
  }

  // ===== mergeSessionFromCloud (integration) =====

  test("mergeSessionFromCloud: merges cloud messages into existing local session") {
    withStore { store =>
      val sid = "merge-test-session"
      val localMsgs = List(Message(MessageRole.User, Left("local question"), 1000L))
      val cloudMsgs = List(
        Message(MessageRole.User, Left("local question"), 1000L), // same as local
        Message(MessageRole.Assistant, Left("cloud answer"), 2000L) // new from cloud
      )
      for
        _ <- store.mergeCloudIndex(List(meta(sid, "Merge Test", System.currentTimeMillis())), Nil)
        _ <- store.saveMessagesForSession(sid, localMsgs)
        result <- store.mergeSessionFromCloud(sid, cloudMsgs, Nil)
        loaded <- store.loadMessagesForSession(sid)
      yield
        assert(result.changed, "Merge should detect changes")
        assertEquals(loaded.length, 2, "Should have 2 unique messages after merge")
    }
  }

  test("mergeSessionFromCloud: no change when cloud has subset of local") {
    withStore { store =>
      val sid = "no-change-test"
      val localMsgs = List(
        Message(MessageRole.User, Left("q1"), 1000L),
        Message(MessageRole.Assistant, Left("a1"), 2000L)
      )
      val cloudMsgs = List(Message(MessageRole.User, Left("q1"), 1000L))
      for
        _ <- store.mergeCloudIndex(List(meta(sid, "No Change", System.currentTimeMillis())), Nil)
        _ <- store.saveMessagesForSession(sid, localMsgs)
        result <- store.mergeSessionFromCloud(sid, cloudMsgs, Nil)
      yield assert(!result.changed, "Merge should report no change when cloud is a subset")
    }
  }

end CloudSyncSpec
