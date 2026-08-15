package nebflow.core.tools

import munit.FunSuite

import java.nio.file.{Files, Path}

class DiffUtilSpec extends FunSuite:

  private def tempFile(): Path =
    val p = Files.createTempFile("nb-diffutil-spec", ".txt")
    p.toFile.deleteOnExit()
    p

  private def roundtrip(content: String, lineSep: String): String =
    val p = tempFile()
    DiffUtil.writeFile(p, content, lineSep)
    DiffUtil.readFile(p)

  test("writeFile keeps \\n when lineSep is \\n") {
    assertEquals(roundtrip("a\nb\n", "\n"), "a\nb\n")
  }

  test("writeFile converts \\n to \\r\\n when lineSep is \\r\\n") {
    assertEquals(roundtrip("a\nb\n", "\r\n"), "a\r\nb\r\n")
  }

  test("writeFile leaves \\r\\n intact when lineSep is \\r\\n") {
    assertEquals(roundtrip("a\r\nb\r\n", "\r\n"), "a\r\nb\r\n")
  }

  test("writeFile cleans isolated \\r into line breaks (\\n sep)") {
    // CR-only endings (classic Mac) + stray mid-line CR all become \n
    assertEquals(roundtrip("a\rb\rc", "\n"), "a\nb\nc")
  }

  test("writeFile cleans isolated \\r into line breaks (\\r\\n sep)") {
    assertEquals(roundtrip("a\rb", "\r\n"), "a\r\nb")
  }

  test("writeFile does not touch \\r\\n when normalizing isolated \\r (order matters)") {
    // \r\n must be converted to \n BEFORE isolated-\r cleanup, else \n\r\n double-breaks
    assertEquals(roundtrip("a\r\nb\rc", "\n"), "a\nb\nc")
  }

  test("splitLines treats cleaned content consistently") {
    val cleaned = roundtrip("one\rtwo\nthree", "\n")
    assertEquals(DiffUtil.splitLines(cleaned), List("one", "two", "three"))
  }
end DiffUtilSpec
