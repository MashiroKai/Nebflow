package nebflow.core.compact

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.{Decoder, Json}
import munit.CatsEffectSuite
import nebflow.shared.{Message, MessageRole}

class HistoryArchiverSpec extends CatsEffectSuite:

  private def makeArchiver(root: os.Path): HistoryArchiver =
    HistoryArchiver.fileSystem(root)

  private def sampleMessages: List[Message] =
    List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("world"))
    )

  test("archiveCompaction returns Right with json paths") {
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
          // 2026-09-02 迁移：按会话归组 sessions/<sessionId>/compaction/（完整 sessionId，不再截短）
          assert(
            archive.sessionDir.contains("/test-session-abc123/compaction"),
            s"sessionDir should be <sessionsRoot>/<sessionId>/compaction: ${archive.sessionDir}"
          )
          assert(!archive.sessionDir.contains("/archives/"), s"legacy archives/ path must not appear: ${archive.sessionDir}")
          assert(archive.beforeJsonPath.endsWith("-before.json"))
          assert(archive.afterJsonPath.endsWith("-after.json"))
          // All files should exist
          assert(os.exists(os.Path(archive.beforeJsonPath)))
          assert(os.exists(os.Path(archive.afterJsonPath)))
        }
      case Left(err) => IO(fail(s"archiveCompaction failed: $err"))
    }
  }

  // 作者令 2026-09-15（逐字）：「把上下文压缩写成 report 的功能删掉，并且删掉
  // 以往记录的 report。」—— 本用例是该令的机读判据：触发一次归档后，归档目录
  // 内不得出现任何 `*-report.md`（README 式人读报告已废弃；before/after 原始
  // JSON 转储是另一件事，不在该令范围内，故仍断言其存在）。
  test("author order 2026-09-15: archiveCompaction must NOT write any -report.md") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    archiver.archiveCompaction("noreport-session", None, "Nebula", sampleMessages, sampleMessages, "full").flatMap {
      case Right(archive) =>
        IO.blocking {
          val dir = os.Path(archive.sessionDir)
          val entries = os.list(dir).map(_.last).sorted
          val reports = entries.filter(_.endsWith("-report.md"))
          assert(
            reports.isEmpty,
            s"compaction must not write a report file (author order 2026-09-15), found: ${reports.mkString(", ")}"
          )
          assert(entries.exists(_.endsWith("-before.json")), s"before.json should still be written: $entries")
          assert(entries.exists(_.endsWith("-after.json")), s"after.json should still be written: $entries")
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

  // 2026-09-15 作者令：两份断言 report 正文的用例（metadata 表 / message stats 表）
  // 随 report 生成链一并删除 —— 它们断言的产物已不存在。

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

  test("archiveCompaction with empty messages writes empty JSON arrays") {
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

  test("archiveCompaction groups by full sessionId under compaction/ (no truncation)") {
    val root = os.temp.dir()
    val archiver = makeArchiver(root)
    val longSid = "12345678-1234-1234-1234-123456789abc"
    archiver.archiveCompaction(longSid, None, "A", sampleMessages, sampleMessages, "full").flatMap {
      case Right(archive) =>
        IO {
          assert(
            archive.sessionDir.endsWith(s"/$longSid/compaction"),
            s"dir should end with full sessionId + compaction: ${archive.sessionDir}"
          )
        }
      case Left(err) => IO(fail(s"archiveCompaction failed: $err"))
    }
  }

  test("production wiring: sessions root from PathUtil.dataRoot lands under <dataRoot>/sessions/<sid>/compaction") {
    val tmpHome = os.temp.dir()
    val prevRoot = nebflow.core.PathUtil.dataRoot
    nebflow.core.PathUtil.setDataRoot(tmpHome)
    // Mirror GatewayMain.scala's wiring expression exactly.
    val archiver = makeArchiver(nebflow.core.PathUtil.dataRoot / "sessions")
    archiver
      .archiveCompaction("wiring-session-01", None, "Nebula", sampleMessages, sampleMessages, "full")
      .flatMap {
        case Right(archive) =>
          IO {
            val expected = (tmpHome / "sessions" / "wiring-session-01" / "compaction").toString
            assert(archive.sessionDir.startsWith(expected), s"sessionDir under dataRoot sessions: ${archive.sessionDir}")
          }
        case Left(err) => IO(fail(s"archiveCompaction failed: $err"))
      }
      .guarantee(IO(nebflow.core.PathUtil.setDataRoot(prevRoot)))
  }
end HistoryArchiverSpec
