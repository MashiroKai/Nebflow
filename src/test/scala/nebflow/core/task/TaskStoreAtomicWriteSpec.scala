package nebflow.core.task

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.parser.decode
import munit.CatsEffectSuite
import nebflow.core.PathUtil

/** Issue #23: writeTask must be atomic (tmp + rename). A bare os.write.over
  * can leave truncated JSON if the process dies mid-write — readTask then
  * silently drops the task. Guards:
  *   - concurrent updates to the same task never interleave partial writes
  *     (final file always decodes as a valid Task)
  *   - no .tmp.* residue left in the session dir
  *   - legacy behavior: target file content is the last-write-wins task JSON
  */
class TaskStoreAtomicWriteSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-taskstore-atomic"
  PathUtil.setDataRoot(tempRoot)

  private val store: TaskStore = FileTaskStore

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot) }

  private def mkTask(sessionId: String, subject: String): IO[String] =
    store.create(sessionId, TaskCreateInput(subject = subject, description = "d", taskKind = Some("agent")))

  /** Spawn each IO on its own fiber and join all — plain CE3 concurrency
    * without relying on Parallel syntax extensions. */
  private def concurrently(ios: List[IO[Unit]]): IO[Unit] =
    ios.traverse(_.start).flatMap(_.traverse_(_.joinWithNever))

  test("atomic write: concurrent updates to one task never corrupt the file"):
    reset().unsafeRunSync()
    val sid = "sess-atomic-1"
    val idF = mkTask(sid, "concurrent target")
    val taskId = idF.unsafeRunSync()

    // 50 racing updates — before #23 a bare write.over could interleave
    // (truncated) content; with tmp+rename the rename is atomic, so the
    // final file is always a complete JSON document of some writer.
    val updates = concurrently((1 to 50).toList.map { n =>
      store.update(sid, taskId, TaskUpdateInput(subject = Some(s"race-$n"))).void
    })
    updates.unsafeRunSync()

    // Final file decodes cleanly and carries a race-* subject.
    val raw = IO.blocking(os.read((tempRoot / "tasks" / sid / s"$taskId.json"))).unsafeRunSync()
    decode[Task](raw) match
      case Right(t) => assert(t.subject.startsWith("race-"), s"subject=${t.subject}")
      case Left(err) => fail(s"corrupted task JSON after concurrent writes: $err — raw=${raw.take(80)}")

    // No tmp residue: atomic writes clean up via rename.
    val residue = os.list(tempRoot / "tasks" / sid).filter(_.last.contains(".tmp."))
    assertEquals(residue.size, 0, s"leftover tmp files: ${residue.map(_.last)}")

  test("atomic write: many tasks in one session all survive concurrent creation"):
    reset().unsafeRunSync()
    val sid = "sess-atomic-2"
    concurrently((1 to 30).toList.map { n =>
      mkTask(sid, s"task-$n").void
    }).unsafeRunSync()

    val listed = store.list(sid).unsafeRunSync()
    assertEquals(listed.size, 30)
    // Every file on disk is complete JSON (no truncated siblings either).
    val files = os.list(tempRoot / "tasks" / sid).filter(_.last.endsWith(".json"))
    assertEquals(files.size, 30)
    files.foreach { f =>
      val raw = os.read(f)
      decode[Task](raw).fold(err => fail(s"corrupt ${f.last}: $err"), _ => ())
    }
    assertEquals(os.list(tempRoot / "tasks" / sid).count(_.last.contains(".tmp.")), 0)

end TaskStoreAtomicWriteSpec
