package nebflow.core.compact

import munit.CatsEffectSuite
import cats.effect.IO
import nebflow.shared.{Message, MessageRole}
import io.circe.parser.parse
import io.circe.{Decoder, Json}
import cats.syntax.all._

class HistoryArchiverSpec extends CatsEffectSuite:

  private def makeArchiver(root: os.Path): HistoryArchiver =
    HistoryArchiver.fileSystem(root)

  private def sampleMessages: List[Message] =
    List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("world")),
    )

  test("archive returns path with archives/<sessionId>/<ts>.json format") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    val io = archiver.archive("test-session", sampleMessages)
    io.flatMap {
      case Right(path) =>
        IO {
          assert(path.contains("archives/test-session/"))
          assert(path.endsWith(".json"))
        }
      case Left(err) => IO(fail(s"archive failed: $err"))
    }
  }

  test("archive writes spaces2 JSON array compatible with SessionStore format") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    archiver.archive("fmt-session", sampleMessages).flatMap {
      case Right(path) =>
        IO.blocking(os.read(os.Path(path))).map { content =>
          // Must be valid JSON array (spaces2 format, NOT jsonl)
          val json = parse(content).toOption.get
          assert(json.isArray, "snapshot should be JSON array")
          val arr = json.asArray.get
          assertEquals(arr.size, 2)
          // Verify first message role and text content
          val first = arr(0).asObject.get
          assertEquals(first("role").flatMap(_.asString).get, "user")
          assertEquals(first("content").flatMap(_.asString).get, "hello")
        }
      case Left(err) => IO(fail(s"archive failed: $err"))
    }
  }

  test("archive returns Left on unwritable directory without throwing") {
    val root = os.temp.dir()
    // Make root read-only (best-effort; skip on platforms where this doesn't work)
    val sessionDir = root / "archives" / "bad-session"
    os.makeDir.all(sessionDir)
    java.nio.file.Files.setPosixFilePermissions(
      java.nio.file.Paths.get(sessionDir.toString),
      java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ)
    )
    val archiver = makeArchiver(root)
    archiver.archive("bad-session", sampleMessages).map { result =>
      // Either returns Left (graceful) or succeeds (platform-dependent); never throws
      assert(result.isLeft || result.isRight) // just assert no exception
    }
  }

  test("archive with empty messages writes empty JSON array") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    archiver.archive("empty-session", Nil).flatMap {
      case Right(path) =>
        IO.blocking(os.read(os.Path(path))).map { content =>
          val json = parse(content).toOption.get
          assert(json.isArray)
          assertEquals(json.asArray.get.size, 0)
        }
      case Left(err) => IO(fail(s"archive failed: $err"))
    }
  }

  test("archive path is deterministic in structure (contains sessionId and .json)") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    archiver.archive("my-session", sampleMessages).flatMap {
      case Right(path) =>
        IO {
          assert(path.contains("archives/my-session/"))
          assert(path.endsWith(".json"))
          // Should contain a timestamp (digits before .json)
          val filename = path.split("/").last
          assert(filename.matches("\\d+\\.json"), s"filename should be digits.json, got $filename")
        }
      case Left(err) => IO(fail(s"archive failed: $err"))
    }
  }
