package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * T10: per-file write-lock mutual exclusion — the guarantee MultiEdit's
 * single-lock design relies on to survive AgentCore's parTraverse parallel
 * tool execution without torn writes.
 */
class FileLockMutexSpec extends CatsEffectSuite:

  private def tempFile(content: String): Path =
    val p = Files.createTempFile("nb-lockmut-spec", ".txt")
    Files.write(p, content.getBytes("UTF-8"))
    p.toFile.deleteOnExit()
    p

  test("withWriteLock serializes concurrent critical sections on the same file") {
    FileLockManager.create.flatMap { lm =>
      Ref_Sugar.withCounter { inside =>
        val path = Path.of("/tmp/nb-lockmut-virtual.txt")
        def critical(tag: Int): IO[Int] =
          lm.withWriteLock(path) {
            for
              c <- inside.updateAndGet(_ + 1)
              _ = assert(c == 1, s"critical section overlapped (depth $c) — lock is NOT mutual")
              _ <- IO.sleep(30.millis)
              _ <- inside.update(_ - 1)
            yield tag
          }
        // 8 fibers racing for the same path; any overlap trips the assert
        IO.parSequenceN(8)(List.range(1, 9).map(critical)).map { tags =>
          assertEquals(tags.toSet, (1 to 8).toSet)
        }
      }
    }
  }

  test("locks are per-file — different paths do not block each other") {
    FileLockManager.create.flatMap { lm =>
      Ref_Sugar.withCounter { inside =>
        def critical(p: String): IO[Unit] =
          lm.withWriteLock(Path.of(p)) {
            for
              c <- inside.updateAndGet(_ + 1)
              // both sections run concurrently → depth reaches 2
              _ = assert(c >= 1)
              _ <- IO.sleep(40.millis)
              _ <- inside.update(_ - 1)
            yield ()
          }
        (IO.both(critical("/tmp/nb-lockmut-a.txt"), critical("/tmp/nb-lockmut-b.txt")) *> IO.unit)
      }
    }
  }

  test("concurrent MultiEdits on the same file: serialized, no torn writes") {
    FileLockManager.create.flatMap { lm =>
      val ctx = ToolContext(projectRoot = "/tmp", fileLockManager = Some(lm))
      val p = tempFile("l1\nl2\nl3\nl4\nl5\n")
      def multiEdit(old: String, neu: String): IO[Either[ToolError, String]] =
        MultiEditTool.call(
          JsonObject(
            "file_path" -> p.toString.asJson,
            "edits" -> Json.arr(Json.obj("old_string" -> old.asJson, "new_string" -> neu.asJson))
          ),
          ctx
        )
      // two non-overlapping edits racing; the lock must serialize them so
      // BOTH succeed and the file ends with both applied (whole, not torn)
      IO.both(multiEdit("l1", "A1"), multiEdit("l5", "B5")).map { case (r1, r2) =>
        r1.fold(e => fail(s"edit A failed: ${e.message}"), identity)
        r2.fold(e => fail(s"edit B failed: ${e.message}"), identity)
        assertEquals(DiffUtil.readFile(p), "A1\nl2\nl3\nl4\nB5\n")
      }
    }
  }
end FileLockMutexSpec

/** Tiny helper: a Ref-based depth counter. */
private object Ref_Sugar:
  def withCounter[A](f: cats.effect.Ref[IO, Int] => IO[A]): IO[A] =
    cats.effect.Ref.of[IO, Int](0).flatMap(f)
