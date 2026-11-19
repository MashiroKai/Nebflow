package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.SubAgentTask

class SubAgentRunLifecycleSpec extends CatsEffectSuite:
  private def task = SubAgentTask("task", "parent", "worker", "work", "description", "running", 0, 1L, None, None, "subtask")

  private def isolated[A](f: (SubAgentTaskStore, os.Path) => IO[A]): IO[A] =
    IO.blocking(os.temp.dir(prefix = "task-lifecycle-")).bracket(dir => f(new SubAgentTaskStore(dir), dir))(
      dir => IO.blocking(os.remove.all(dir)))

  test("two store instances for the same directory share the write lock") {
    isolated { (store, dir) =>
      IO {
        val other = new SubAgentTaskStore(dir)
        val field = classOf[SubAgentTaskStore].getDeclaredFields.find(_.getType == classOf[java.util.concurrent.locks.ReentrantLock]).get
        field.setAccessible(true)
        assert(field.get(store) eq field.get(other), "same directory must serialize read-modify-write across stores")
      }
    }
  }

  test("concurrent writes across two stores preserve both tasks") {
    isolated { (store, dir) =>
      val other = new SubAgentTaskStore(dir)
      for
        results <- List(store.beginRun(task.copy(taskId = "one"), "r1", "root", "nebflow"),
          other.beginRun(task.copy(taskId = "two"), "r2", "root", "nebflow")).parSequence
        one <- store.findByTaskId("one")
        two <- other.findByTaskId("two")
      yield
        assert(results.forall(_.isRight))
        assertEquals(one.flatMap(_.runId), Some("r1"))
        assertEquals(two.flatMap(_.runId), Some("r2"))
    }
  }

  test("legacy records decode without execution fields and remain writable") {
    isolated { (store, _) =>
      val legacy = task.asJson.mapObject(o => o.filter { case (key, _) =>
        Set("taskId", "parentSessionId", "agentName", "prompt", "description", "status", "retryCount", "spawnedAt", "completedAt", "lastError", "source").contains(key)
      })
      for
        decoded <- IO.fromEither(decode[SubAgentTask](legacy.noSpaces))
        _ <- store.recordTask(decoded)
        _ <- store.updateStatus("parent", "task", "completed")
        row <- store.findByTaskId("task")
      yield
        assertEquals(decoded.runId, None)
        assertEquals(row.map(_.status), Some("completed"))
    }
  }

  test("run fence, terminal first wins, pending notification blocks next run, and revisions survive reopen") {
    isolated { (store, dir) =>
      for
        admitted <- store.beginRun(task, "run-1", "root", "codex")
        duplicate <- store.beginRun(task, "run-1", "root", "codex")
        stale <- store.updateRun("parent", "task", "other", activityAt = Some(10L))
        active <- store.updateRun("parent", "task", "run-1", Some("running"), Some(10L), Some("text"))
        _ <- store.updateStatus("parent", "task", "failed")
        stillActive <- store.findByTaskId("task")
        terminal <- store.completeRun("parent", "task", "run-1", "finished", resultRef = Some("session:task"))
        late <- store.completeRun("parent", "task", "run-1", "cancelled")
        blocked <- store.beginRun(task, "run-2", "root", "codex")
        _ <- store.acknowledgeNotification("parent", "task", "run-1", "task#run-1")
        next <- store.beginRun(task, "run-2", "root", "codex")
        reopened <- new SubAgentTaskStore(dir).findByTaskId("task")
      yield
        assertEquals(duplicate, admitted)
        assertEquals(stale, Left("stale-run"))
        assertEquals(active.toOption.flatMap(_.committedRevision), Some(2L))
        assertEquals(stillActive.flatMap(_.executionStatus), Some("running"))
        assertEquals(terminal.toOption.map(_.status), Some("completed"))
        assertEquals(terminal.toOption.flatMap(_.executionStatus), Some("finished"))
        assertEquals(late, Left("terminal-run"))
        assertEquals(blocked, Left("notification-pending"))
        assertEquals(next.toOption.flatMap(_.committedRevision), Some(5L))
        assertEquals(reopened, next.toOption)
    }
  }

  test("explicit continuation preserves revisions and fences its predecessor") {
    isolated { (store, _) =>
      for
        _ <- store.beginRun(task, "old", "root", "nebflow")
        _ <- store.updateRun("parent", "task", "old", Some("frozen"))
        before <- store.findByTaskId("task").map(_.get)
        _ <- store.resumeObserved(before)
        resumed <- store.findByTaskId("task").map(_.get)
        late <- store.completeRun("parent", "task", "old", "failed")
        _ <- store.markRevived("parent", "task", 3, 100L)
        unchanged <- store.findByTaskId("task")
      yield
        assert(resumed.runId.exists(_ != "old"))
        assertEquals(resumed.executionStatus, Some("starting"))
        assertEquals(resumed.committedRevision, Some(4L))
        assertEquals(late, Left("stale-run"))
        assertEquals(unchanged, Some(resumed))
    }
  }

  test("explicit resume rejects pending notification before any new admission") {
    isolated { (store, _) =>
      for
        _ <- store.beginRun(task, "old", "root", "nebflow")
        terminal <- store.completeRun("parent", "task", "old", "finished", resultRef = Some("saved-envelope"))
        attempted <- store.resumeObserved(terminal.toOption.get).attempt
        after <- store.findByTaskId("task")
      yield
        assert(attempted.left.exists(_.getMessage == "notification-pending"))
        assertEquals(after, terminal.toOption)
    }
  }

  test("serialized concurrent terminal calls commit exactly one winner") {
    isolated { (store, _) =>
      for
        _ <- store.beginRun(task, "run", "root", "nebflow")
        attempts <- List("finished", "failed", "cancelled").parTraverse(status =>
          store.completeRun("parent", "task", "run", status))
        row <- store.findByTaskId("task")
      yield
        assertEquals(attempts.count(_.isRight), 1)
        assertEquals(row.flatMap(_.committedRevision), Some(2L))
    }
  }

  test("corrupt persisted data refuses writes and preserves original bytes") {
    isolated { (store, dir) =>
      for
        _ <- IO(os.write.over(dir / "parent.json", "broken-json"))
        result <- store.beginRun(task, "run", "root", "codex").attempt
        bytes <- IO(os.read(dir / "parent.json"))
      yield
        assert(result.isLeft)
        assertEquals(bytes, "broken-json")
        assertEquals(os.list(dir).map(_.last).toList, List("parent.json"))
    }
  }

  test("startup interrupts external monitoring without execution or a failure notice") {
    isolated { (store, _) =>
      for
        _ <- store.beginRun(task, "run", "root", "codex")
        recovered <- SubAgentStartupRecovery.interruptExternalRuns(store)
        again <- SubAgentStartupRecovery.interruptExternalRuns(store)
        row <- store.findByTaskId("task")
      yield
        assertEquals(recovered, List("task"))
        assertEquals(again, Nil)
        assertEquals(row.flatMap(_.executionStatus), Some("interrupted"))
        assertEquals(row.flatMap(_.notificationPending), Some(false))
        assertEquals(row.flatMap(_.notificationId), None)
        assertEquals(row.flatMap(_.incomplete), Some(true))
    }
  }
end SubAgentRunLifecycleSpec
