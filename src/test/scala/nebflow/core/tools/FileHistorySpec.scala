package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

class FileHistorySpec extends CatsEffectSuite:

  private def withHistory(test: FileHistory => Unit): Unit =
    val tmpDir = Files.createTempDirectory("nebflow-history-test")
    try
      val history = FileHistory.create(
        historyRoot = tmpDir,
        maxEntries = 3,
        maxFileSizeBytes = 1024L
      ).unsafeRunSync()
      test(history)
    finally
      if Files.exists(tmpDir) then
        Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  private def withTempFile(content: String)(test: Path => Unit): Unit =
    val file = Files.createTempFile("nebflow-test-", ".txt")
    try
      Files.writeString(file, content)
      test(file)
    finally Files.deleteIfExists(file)

  test("snapshot file content before modification") {
    withHistory { history =>
      withTempFile("hello world") { file =>
        history.snapshot(file).unsafeRunSync()
        val historyDir = Files.list(history.historyRoot).findFirst().orElseThrow()
        val snapshots = Files.list(historyDir).iterator().asScala.toList
        assert(snapshots.nonEmpty, "Expected at least one snapshot")
        assertEquals(Files.readString(snapshots.head), "hello world")
      }
    }
  }

  test("not snapshot non-existent files") {
    withHistory { history =>
      val ghost = Path.of("/tmp/nebflow-nonexistent-ghost-" + System.currentTimeMillis())
      history.snapshot(ghost).unsafeRunSync()
      val entries = Files.list(history.historyRoot).iterator().asScala.toList
      assert(entries.isEmpty, "Expected no history entries for non-existent file")
    }
  }

  test("not snapshot files exceeding size limit") {
    withHistory { history =>
      val bigFile = Files.createTempFile("nebflow-big-", ".txt")
      try
        Files.writeString(bigFile, "x" * 2048)
        history.snapshot(bigFile).unsafeRunSync()
        val entries = Files.list(history.historyRoot).iterator().asScala.toList
        assert(entries.isEmpty, "Expected no history entries for oversized file")
      finally Files.deleteIfExists(bigFile)
    }
  }

  test("evict oldest snapshots when maxEntries exceeded") {
    withHistory { history =>
      withTempFile("v1") { file =>
        // maxEntries = 3, create 4 snapshots
        for i <- 1 to 4 do
          Files.writeString(file, s"v$i")
          history.snapshot(file).unsafeRunSync()

        val hashDir = Files.list(history.historyRoot).findFirst().orElseThrow()
        val snapshots = Files.list(hashDir).iterator().asScala.toList.sortBy(_.getFileName.toString)
        assertEquals(snapshots.length, 3)
        // v1 should be evicted, v2/v3/v4 remain
        val contents = snapshots.map(p => Files.readString(p)).toSet
        assertEquals(contents, Set("v2", "v3", "v4"))
      }
    }
  }

  test("clear all history") {
    withHistory { history =>
      withTempFile("clear-test") { file =>
        history.snapshot(file).unsafeRunSync()
        assert(Files.list(history.historyRoot).iterator().asScala.nonEmpty)
        history.clear().unsafeRunSync()
        // After clear, the root dir should be gone or empty
        assert(!Files.exists(history.historyRoot) ||
          Files.walk(history.historyRoot).iterator().asScala.count(_ != history.historyRoot) == 0)
      }
    }
  }

end FileHistorySpec
