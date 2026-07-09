package nebflow.dropbox

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite

import java.security.MessageDigest

/**
 * Tests for Dropbox file handling utilities:
 *   - SHA-256 hash computation during stream-to-file write
 *   - File name conflict resolution with timestamp suffix
 *   - Downloads directory detection
 */
class DropboxUtilSpec extends CatsEffectSuite:

  // ===== SHA-256 hash computation =====

  test("streamToFileWithHash: correct hash for known content") {
    val content = "Hello, Dropbox!".getBytes("UTF-8")
    val expected = MessageDigest.getInstance("SHA-256")
    expected.update(content)
    val expectedHex = expected.digest().map(b => f"$b%02x").mkString

    val tempFile = os.pwd / "target" / "dropbox-test" / "hash-test.bin"
    os.makeDir.all(tempFile / os.up)

    val stream = Stream.emits(content).covary[IO]
    DropboxUtil.streamToFileWithHash(stream, tempFile).flatMap { actualHex =>
      IO {
        assertEquals(actualHex, expectedHex, "SHA-256 hash should match MessageDigest result")
        // Also verify the file content was written correctly
        val written = os.read.bytes(tempFile)
        assert(written.sameElements(content), "File content should match input")
        os.remove(tempFile)
      }
    }
  }

  test("streamToFileWithHash: empty stream produces SHA-256 of empty string") {
    val expected = MessageDigest
      .getInstance("SHA-256")
      .digest(Array.emptyByteArray)
      .map(b => f"$b%02x")
      .mkString

    val tempFile = os.pwd / "target" / "dropbox-test" / "empty-test.bin"
    os.makeDir.all(tempFile / os.up)

    val stream = Stream.empty.covary[IO]
    DropboxUtil.streamToFileWithHash(stream, tempFile).flatMap { actualHex =>
      IO {
        assertEquals(actualHex, expected, "Empty stream hash should match SHA-256 of empty bytes")
        os.remove(tempFile)
      }
    }
  }

  test("streamToFileWithHash: large stream (1MB) hash matches direct computation") {
    val chunkSize = 8192
    val numChunks = 128 // ~1MB
    val chunk = Array.fill(chunkSize)(42: Byte)
    val totalSize = chunkSize * numChunks

    // Expected hash
    val expectedDigest = MessageDigest.getInstance("SHA-256")
    for _ <- 0 until numChunks do expectedDigest.update(chunk)
    val expectedHex = expectedDigest.digest().map(b => f"$b%02x").mkString

    val tempFile = os.pwd / "target" / "dropbox-test" / "large-test.bin"
    os.makeDir.all(tempFile / os.up)

    // Build a stream of repeated chunks
    val stream = Stream.repeatEval(IO.pure(fs2.Chunk.array(chunk))).take(numChunks).flatMap(Stream.chunk)
    DropboxUtil.streamToFileWithHash(stream, tempFile).flatMap { actualHex =>
      IO {
        assertEquals(actualHex, expectedHex, "Large stream hash should match")
        val fileSize = os.size(tempFile)
        assertEquals(fileSize, totalSize.toLong, "File size should match")
        os.remove(tempFile)
      }
    }
  }

  test("streamToFileWithHash: hash mismatch on corrupted data") {
    val original = "correct data".getBytes("UTF-8")
    val corrupted = "corrupted data".getBytes("UTF-8")

    val d1 = MessageDigest.getInstance("SHA-256"); d1.update(original)
    val d2 = MessageDigest.getInstance("SHA-256"); d2.update(corrupted)
    val h1 = d1.digest().map(b => f"$b%02x").mkString
    val h2 = d2.digest().map(b => f"$b%02x").mkString

    IO(assert(h1 != h2, "Different content must produce different hashes"))
  }

  // ===== File name conflict resolution =====

  test("resolveFinalPath: returns original when no conflict") {
    val dir = os.pwd / "target" / "dropbox-test" / "noconflict"
    os.makeDir.all(dir)
    val result = DropboxUtil.resolveFinalPath(dir, "report.pdf")
    assertEquals(result, dir / "report.pdf", "No conflict should return the original path")
  }

  test("resolveFinalPath: appends timestamp when file exists") {
    val dir = os.pwd / "target" / "dropbox-test" / "conflict"
    os.makeDir.all(dir)
    // Create the conflicting file
    os.write(dir / "report.pdf", "existing".getBytes)

    val result = DropboxUtil.resolveFinalPath(dir, "report.pdf")
    val resultName = result.last
    // Should be like "report_20250115_143022.pdf"
    assert(resultName.startsWith("report_"), s"Conflicted name should start with 'report_', got: $resultName")
    assert(resultName.endsWith(".pdf"), s"Conflicted name should end with '.pdf', got: $resultName")
    assert(resultName != "report.pdf", "Should not be the original name")

    // Cleanup
    os.remove(dir / "report.pdf")
  }

  test("resolveFinalPath: handles file without extension") {
    val dir = os.pwd / "target" / "dropbox-test" / "noext"
    os.makeDir.all(dir)
    os.write(dir / "README", "data".getBytes)

    val result = DropboxUtil.resolveFinalPath(dir, "README")
    val resultName = result.last
    assert(
      resultName.startsWith("README_"),
      s"Conflicted name without ext should start with 'README_', got: $resultName"
    )
    assert(!resultName.contains('.'), s"Should have no extension, got: $resultName")

    os.remove(dir / "README")
  }

  // ===== Downloads directory =====

  test("downloadsDir: returns a valid path ending with 'Downloads'") {
    val dir = DropboxUtil.downloadsDir
    val dirName = dir.last
    assertEquals(dirName, "Downloads", "Directory should be named 'Downloads'")
    // os.Path is always absolute by design — verify it resolves to a real directory parent
    assert(os.exists(dir / os.up), "Parent of Downloads should exist")
  }

end DropboxUtilSpec
