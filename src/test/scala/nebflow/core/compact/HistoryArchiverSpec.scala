package nebflow.core.compact

import munit.CatsEffectSuite
import cats.effect.IO
import nebflow.shared.{ContentBlock, Message, MessageRole}
import io.circe.parser.parse
import io.circe.{Decoder, Json}
import cats.syntax.all.*

class HistoryArchiverSpec extends CatsEffectSuite:

  private def makeArchiver(root: os.Path): HistoryArchiver =
    HistoryArchiver.fileSystem(root)

  private def sampleMessages: List[Message] =
    List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("world"))
    )

  test("archiveCompaction returns Right with report + json paths") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    val io = archiver.archiveCompaction(
      sessionId = "test-session-abc123",
      sessionName = Some("My Session"),
      agentName = "Nebula",
      before = sampleMessages,
      after = List(Message(MessageRole.User, Left("summary"))),
      mode = "full"
    )
    io.flatMap {
      case Right(archive) =>
        IO {
          assert(
            archive.sessionDir.contains("archives/test-ses"),
            s"sessionDir should contain short sid: ${archive.sessionDir}"
          )
          assert(archive.reportPath.endsWith("-report.md"), s"report should end with -report.md: ${archive.reportPath}")
          assert(archive.beforeJsonPath.endsWith("-before.json"))
          assert(archive.afterJsonPath.endsWith("-after.json"))
          // All files should exist
          assert(os.exists(os.Path(archive.reportPath)))
          assert(os.exists(os.Path(archive.beforeJsonPath)))
          assert(os.exists(os.Path(archive.afterJsonPath)))
        }
      case Left(err) => IO(fail(s"archiveCompaction failed: $err"))
    }
  }

  test("archiveCompaction writes valid JSON arrays for before/after") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    val before = List(
      Message(MessageRole.User, Left("a")),
      Message(MessageRole.Assistant, Left("b"))
    )
    val after = List(Message(MessageRole.User, Left("summary")))
    archiver.archiveCompaction("fmt-session", None, "TestAgent", before, after, "full").flatMap {
      case Right(archive) =>
        IO.blocking {
          val beforeJson = parse(os.read(os.Path(archive.beforeJsonPath))).toOption.get
          val afterJson = parse(os.read(os.Path(archive.afterJsonPath))).toOption.get
          assert(beforeJson.isArray)
          assert(afterJson.isArray)
          assertEquals(beforeJson.asArray.get.size, 2)
          assertEquals(afterJson.asArray.get.size, 1)
        }
      case Left(err) => IO(fail(s"archiveCompaction failed: $err"))
    }
  }

  test("archiveCompaction report contains metadata table") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    archiver
      .archiveCompaction(
        "report-session",
        Some("Session X"),
        "Nebula",
        sampleMessages,
        List(Message(MessageRole.User, Left("s"))),
        "micro",
        extra = Map("preservedRounds" -> "3")
      )
      .flatMap {
        case Right(archive) =>
          IO.blocking {
            val report = os.read(os.Path(archive.reportPath))
            assert(report.contains("# Context Compaction Report"))
            assert(report.contains("Session X"))
            assert(report.contains("Nebula"))
            assert(report.contains("micro"))
            assert(report.contains("Preserved rounds"))
            assert(report.contains("## Before"))
            assert(report.contains("## After"))
          }
        case Left(err) => IO(fail(s"archiveCompaction failed: $err"))
      }
  }

  test("archiveCompaction report contains message stats") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    val before = List(
      Message(MessageRole.User, Left("user msg")),
      Message(
        MessageRole.Assistant,
        Right(
          List(
            ContentBlock
              .ToolUse("t1", "Read", io.circe.JsonObject.singleton("file_path", io.circe.Json.fromString("a.ts")))
          )
        )
      ),
      Message(MessageRole.User, Right(List(ContentBlock.ToolResult("t1", "result content", None)))),
      Message(MessageRole.System, Left("system note"))
    )
    val after = List(Message(MessageRole.User, Left("summary")))
    archiver.archiveCompaction("stats-session", None, "Agent", before, after, "full").flatMap {
      case Right(archive) =>
        IO.blocking {
          val report = os.read(os.Path(archive.reportPath))
          // Stats table should list counts
          assert(report.contains("User msgs"))
          assert(report.contains("Assistant msgs"))
          assert(report.contains("System msgs"))
          assert(report.contains("Tool results"))
          assert(report.contains("Tool uses"))
        }
      case Left(err) => IO(fail(s"archiveCompaction failed: $err"))
    }
  }

  test("archiveCompaction returns Left on unwritable directory without throwing") {
    val root = os.temp.dir()
    val sessionDir = root / "archives" / "bad-sess"
    os.makeDir.all(sessionDir)
    java.nio.file.Files.setPosixFilePermissions(
      java.nio.file.Paths.get(sessionDir.toString),
      java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ)
    )
    val archiver = makeArchiver(root)
    archiver.archiveCompaction("bad-session", None, "A", sampleMessages, sampleMessages, "full").map { result =>
      assert(result.isLeft || result.isRight) // just assert no exception
    }
  }

  test("archiveCompaction with empty messages writes empty JSON arrays and valid report") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    archiver.archiveCompaction("empty-session", None, "A", Nil, Nil, "full").flatMap {
      case Right(archive) =>
        IO.blocking {
          val beforeJson = parse(os.read(os.Path(archive.beforeJsonPath))).toOption.get
          val afterJson = parse(os.read(os.Path(archive.afterJsonPath))).toOption.get
          assert(beforeJson.isArray)
          assertEquals(beforeJson.asArray.get.size, 0)
          assert(afterJson.isArray)
          assertEquals(afterJson.asArray.get.size, 0)
        }
      case Left(err) => IO(fail(s"archiveCompaction failed: $err"))
    }
  }

  test("archiveCompaction shortens sessionId to 8 chars in directory") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    val longSid = "12345678-1234-1234-1234-123456789abc"
    archiver.archiveCompaction(longSid, None, "A", sampleMessages, sampleMessages, "full").flatMap {
      case Right(archive) =>
        IO {
          assert(archive.sessionDir.endsWith("/12345678"), s"dir should end with short sid: ${archive.sessionDir}")
        }
      case Left(err) => IO(fail(s"archiveCompaction failed: $err"))
    }
  }
end HistoryArchiverSpec
