package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.ContentBlock

import java.nio.file.{Files, Paths}
import java.util.Base64

class ReadImageSpec extends CatsEffectSuite:

  private val testDir = java.nio.file.Files.createTempDirectory("read-image-test")

  private def ctx = ToolContext(projectRoot = testDir.toString)

  private def inputJson(path: String): JsonObject =
    JsonObject("file_path" -> io.circe.Json.fromString(path))

  test("Read on PNG file returns image description and extractImages provides Image block"):
    // Create a minimal valid PNG (1x1 red pixel)
    val pngBytes = Array[Byte](
      0x89.toByte, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
      0x00, 0x00, 0x00, 0x0D, // IHDR length
      0x49, 0x48, 0x44, 0x52, // "IHDR"
      0x00, 0x00, 0x00, 0x01, // width=1
      0x00, 0x00, 0x00, 0x01, // height=1
      0x08, 0x02,             // bit depth=8, color type=RGB
      0x00, 0x00, 0x00,       // compression, filter, interlace
      0x90.toByte, 0x77, 0x53, 0xDE.toByte, // CRC
      0x00, 0x00, 0x00, 0x0C, // IDAT length
      0x49, 0x44, 0x41, 0x54, // "IDAT"
      0x08, 0xD7.toByte, 0x63, 0xF8.toByte, 0xCF.toByte, 0xC0.toByte, 0x00, 0x00,
      0x00, 0x03, 0x00, 0x01, // zlib data
      0x50, 0x74, 0x1C.toByte, 0xAE.toByte, // CRC
      0x00, 0x00, 0x00, 0x00, // IEND length
      0x49, 0x45, 0x4E, 0x44, // "IEND"
      0xAE.toByte, 0x42, 0x60, 0x82.toByte  // CRC
    )
    val pngPath = testDir.resolve("test.png").toString
    Files.write(Paths.get(pngPath), pngBytes)

    for
      result <- ReadTool.call(inputJson(pngPath), ctx)
    yield
      result match
        case Right(text) =>
          assert(text.startsWith("[image:"), s"Expected image description, got: $text")
          assert(text.contains("image/png"), s"Expected media type in description: $text")
          // extractImages should return base64-encoded Image blocks
          val images = ReadTool.extractImages(inputJson(pngPath), text)
          assert(images.isDefined, "extractImages should return Some for image result")
          val imageList = images.get
          assertEquals(imageList.length, 1)
          assertEquals(imageList.head.mediaType, "image/png")
          // Verify base64 data matches file content
          val expectedBase64 = Base64.getEncoder.encodeToString(pngBytes)
          assertEquals(imageList.head.data, expectedBase64)
        case Left(err) => fail(s"Read failed: ${err.message}")

  test("Read on JPG file returns image description"):
    // Create a minimal JPEG (just enough bytes to have the right extension)
    val jpgBytes = Array[Byte](0xFF.toByte, 0xD8.toByte, 0xFF.toByte, 0xE0.toByte, 0x00, 0x10,
      0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
      0x00, 0x01, 0x00, 0x00, 0xFF.toByte, 0xD9.toByte)
    val jpgPath = testDir.resolve("photo.jpg").toString
    Files.write(Paths.get(jpgPath), jpgBytes)

    for
      result <- ReadTool.call(inputJson(jpgPath), ctx)
    yield
      result match
        case Right(text) =>
          assert(text.startsWith("[image:"), s"Expected image description, got: $text")
          assert(text.contains("image/jpeg"), s"Expected jpeg media type: $text")
          val images = ReadTool.extractImages(inputJson(jpgPath), text)
          assert(images.isDefined)
          assertEquals(images.get.head.mediaType, "image/jpeg")
        case Left(err) => fail(s"Read failed: ${err.message}")

  test("Read on SVG file returns text content (not image block)"):
    val svgContent = """<?xml version="1.0"?>
<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
  <circle cx="50" cy="50" r="40" fill="red"/>
</svg>"""
    val svgPath = testDir.resolve("diagram.svg").toString
    Files.write(Paths.get(svgPath), svgContent.getBytes)

    for
      result <- ReadTool.call(inputJson(svgPath), ctx)
    yield
      result match
        case Right(text) =>
          // SVG is text — should NOT have image description
          assert(!text.startsWith("[image:"), s"SVG should not be treated as image: $text")
          assert(text.contains("svg"), s"SVG content should be readable: $text")
          // extractImages should return None for SVG
          val images = ReadTool.extractImages(inputJson(svgPath), text)
          assert(images.isEmpty, "extractImages should return None for SVG")
        case Left(err) => fail(s"Read failed: ${err.message}")

  test("Read on text file returns text content (not image block)"):
    val txtPath = testDir.resolve("readme.txt").toString
    Files.write(Paths.get(txtPath), "Hello World\nLine 2".getBytes)

    for
      result <- ReadTool.call(inputJson(txtPath), ctx)
    yield
      result match
        case Right(text) =>
          assert(!text.startsWith("[image:"), s"Text file should not be treated as image: $text")
          assert(text.contains("Hello World"), s"Text content should be readable: $text")
          val images = ReadTool.extractImages(inputJson(txtPath), text)
          assert(images.isEmpty, "extractImages should return None for text file")
        case Left(err) => fail(s"Read failed: ${err.message}")

  test("Read on nonexistent image file returns error"):
    val result = ReadTool.call(inputJson("/nonexistent/test.png"), ctx).unsafeRunSync()
    result match
      case Left(err) => assert(err.message.contains("File does not exist"))
      case Right(text) => fail(s"Expected error for nonexistent file, got: $text")

  test("extractImages returns None for non-image results"):
    val images = ReadTool.extractImages(
      JsonObject("file_path" -> io.circe.Json.fromString("/some/path.txt")),
      "1\tHello\n2\tWorld"
    )
    assert(images.isEmpty)

  test("summarizeResult handles image descriptions"):
    val summary = ReadTool.summarizeResult(
      JsonObject("file_path" -> io.circe.Json.fromString("/test.png")),
      "[image: test.png | image/png | 123KB]"
    )
    assertEquals(summary, "[image: test.png | image/png | 123KB]")

  override def afterAll(): Unit =
    try java.nio.file.Files.walk(testDir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(p => java.nio.file.Files.deleteIfExists(p))
    catch case _: Exception => ()

end ReadImageSpec
