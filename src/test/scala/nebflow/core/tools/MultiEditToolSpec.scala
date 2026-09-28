package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path, Paths}

class MultiEditToolSpec extends CatsEffectSuite:

  private val ctx = ToolContext(projectRoot = "/tmp")

  private def tempFile(content: String): Path =
    val p = Files.createTempFile("nb-multiedit-spec", ".scala")
    Files.write(p, content.getBytes("UTF-8"))
    p.toFile.deleteOnExit()
    p

  private def multiEditInput(path: Path, edits: (String, String, Boolean)*): JsonObject =
    JsonObject(
      "file_path" -> path.toString.asJson,
      "edits" -> Json.fromValues(
        edits.map { (o, n, r) =>
          Json.obj(
            "old_string" -> o.asJson,
            "new_string" -> n.asJson,
            "replace_all" -> r.asJson
          )
        }
      )
    )

  // -------------------------------------------------------------------------
  // #1: three non-overlapping edits — all applied, single write, correct diff
  // -------------------------------------------------------------------------

  test("#1: three non-overlapping edits all apply and return an aggregated diff") {
    val p = tempFile("val a = 1\nval b = 2\nval c = 3\n")
    val input = multiEditInput(
      p,
      ("val a = 1", "val a = 10", false),
      ("val b = 2", "val b = 20", false),
      ("val c = 3", "val c = 30", false)
    )
    MultiEditTool.call(input, ctx).map {
      case Right(res) =>
        assert(res.startsWith("OK:UPDATED"), res)
        assert(DiffUtil.parseUpdatedStats(res).contains((3, 3)), s"aggregated stats: $res")
        assertEquals(DiffUtil.readFile(p), "val a = 10\nval b = 20\nval c = 30\n")
      case Left(err) => fail(s"unexpected error: ${err.message}")
    }
  }

  // -------------------------------------------------------------------------
  // #2: atomicity — failing edit leaves file byte-identical and mtime unchanged
  // -------------------------------------------------------------------------

  test("#2: failing edit[2] leaves the file byte-identical (atomicity)") {
    val p = tempFile("keep1\nkeep2\nkeep3\n")
    val originalBytes = Files.readAllBytes(p)
    val originalMtime = Files.getLastModifiedTime(p)
    val input = multiEditInput(
      p,
      ("keep1", "CHANGED1", false),
      ("keep2", "CHANGED2", false),
      ("no-such-line", "CHANGED3", false)
    )
    MultiEditTool.call(input, ctx).map {
      case Left(err) =>
        assert(err.message.contains("edits[2]"), s"must name failing index: ${err.message}")
        assert(err.message.contains("not found"), s"must say not found: ${err.message}")
        assert(err.message.toLowerCase.contains("unmodified") || err.message.contains("No changes"), s"must state file untouched: ${err.message}")
        // byte-level identity — the core atomicity assertion
        assert(java.util.Arrays.equals(Files.readAllBytes(p), originalBytes), "file bytes must be unchanged")
        // mtime must be unchanged too (no write happened)
        assertEquals(Files.getLastModifiedTime(p), originalMtime)
      case Right(res) => fail(s"must fail, got: $res")
    }
  }

  // -------------------------------------------------------------------------
  // #3: sequential semantics — edit[1] matches content produced by edit[0]
  // -------------------------------------------------------------------------

  test("#3: edit[1] matches the buffer AFTER edit[0] is applied") {
    val p = tempFile("alpha\nbeta\n")
    val input = multiEditInput(
      p,
      ("beta", "BETA", false),   // edit[0] produces "BETA"
      ("BETA", "delta", false)   // edit[1] can only match after edit[0]
    )
    MultiEditTool.call(input, ctx).map {
      case Right(res) =>
        assert(res.startsWith("OK:UPDATED"))
        assertEquals(DiffUtil.readFile(p), "alpha\ndelta\n")
      case Left(err) => fail(s"sequential semantics broken: ${err.message}")
    }
  }

  // -------------------------------------------------------------------------
  // #4: non-unique old_string without replace_all fails with a clear message
  // -------------------------------------------------------------------------

  test("#4: non-unique old_string without replace_all fails and writes nothing") {
    val p = tempFile("dup\nmid\ndup\n")
    val original = Files.readAllBytes(p)
    val input = multiEditInput(
      p,
      ("dup", "unique-first-edit", false),
      ("mid", "ok", false)
    )
    MultiEditTool.call(input, ctx).map {
      case Left(err) =>
        assert(err.message.contains("edits[0]"), s"names index: ${err.message}")
        assert(err.message.contains("2 locations"), s"match count: ${err.message}")
        assert(java.util.Arrays.equals(Files.readAllBytes(p), original), "no write on failure")
      case Right(_) => fail("must fail")
    }
  }

  // -------------------------------------------------------------------------
  // #5: 4-level fuzzy matching works inside MultiEdit (indentation variant)
  // -------------------------------------------------------------------------

  test("#5: whitespace-run fuzzy matching applies within MultiEdit") {
    val p = tempFile("def  foo(a:Int)  =  1\ndef  bar(b:Int)  =  2\n")
    val input = multiEditInput(
      p,
      ("def foo(a:Int) = 1", "def foo(a: Long) = 1L", false),
      ("def bar(b:Int) = 2", "def bar(b: Long) = 2L", false)
    )
    MultiEditTool.call(input, ctx).map {
      case Right(_) =>
        // fuzzy match replaces the whole original span with new_string verbatim
        // (same semantics as Edit — whitespace style of the original is not kept)
        assertEquals(DiffUtil.readFile(p), "def foo(a: Long) = 1L\ndef bar(b: Long) = 2L\n")
      case Left(err) => fail(s"fuzzy matching must work: ${err.message}")
    }
  }

  // -------------------------------------------------------------------------
  // #6: external-modification guard (pure helper — same logic as the write path)
  // -------------------------------------------------------------------------

  test("#6: externallyModified detects content changes after mtime change") {
    val p = tempFile("original\n")
    val content = DiffUtil.readFile(p).replace("\r\n", "\n")
    val mtime = Files.getLastModifiedTime(p)
    // untouched → false
    assert(!MultiEditTool.externallyModified(p, content, mtime))
    // same content, new mtime → false (mtime-only changes don't block)
    Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000))
    assert(!MultiEditTool.externallyModified(p, content, mtime))
    // content actually changed → true
    Files.write(p, "rewritten externally\n".getBytes("UTF-8"))
    assert(MultiEditTool.externallyModified(p, content, mtime))
  }

  // -------------------------------------------------------------------------
  // #7: empty array / over-limit / all-no-op produce their specific errors
  // -------------------------------------------------------------------------

  test("#7a: empty edits array is rejected") {
    val p = tempFile("x\n")
    val input = JsonObject("file_path" -> p.toString.asJson, "edits" -> Json.arr())
    MultiEditTool.call(input, ctx).map {
      case Left(err) => assert(err.message.contains("at least 1"))
      case Right(_) => fail("must fail")
    }
  }

  test("#7b: 51 edits exceed the cap") {
    val p = tempFile("x\n")
    val edits = (1 to 51).map(i => Json.obj("old_string" -> s"x$i".asJson, "new_string" -> s"y$i".asJson))
    val input = JsonObject("file_path" -> p.toString.asJson, "edits" -> Json.fromValues(edits))
    MultiEditTool.call(input, ctx).map {
      case Left(err) => assert(err.message.contains("maximum of 50"), err.message)
      case Right(_) => fail("must fail")
    }
  }

  test("#7c: edits yielding a no-op buffer are rejected without writing") {
    val p = tempFile("val s = \u201Chello\u201D\n") // curly quotes in file
    val original = Files.readAllBytes(p)
    // straight-quote old matches via quote normalization; curly-quote new
    // normalizes back to exactly the file's curly form → buffer unchanged
    val input = multiEditInput(p, ("val s = \"hello\"", "val s = \u201Chello\u201D", false))
    MultiEditTool.call(input, ctx).map {
      case Left(err) =>
        assert(err.message.contains("No changes"), err.message)
        assert(java.util.Arrays.equals(Files.readAllBytes(p), original), "no write on no-op")
      case Right(res) => fail(s"must fail, got $res")
    }
  }

  // -------------------------------------------------------------------------
  // #8: CRLF line separator is preserved on write
  // -------------------------------------------------------------------------

  test("#8: CRLF file keeps its line separator") {
    val p = tempFile("one\r\ntwo\r\nthree\r\n")
    val input = multiEditInput(p, ("one", "ONE", false), ("three", "THREE", false))
    MultiEditTool.call(input, ctx).map {
      case Right(_) =>
        assertEquals(DiffUtil.readFile(p), "ONE\r\ntwo\r\nTHREE\r\n")
      case Left(err) => fail(s"unexpected error: ${err.message}")
    }
  }

  // -------------------------------------------------------------------------
  // #10: failure self-healing — index + closest-line hint + Read suggestion
  // -------------------------------------------------------------------------

  test("#10: not-found error carries line hint and Read(offset, limit) suggestion") {
    val lines = (1 to 30).map(i => s"line $i").mkString("\n") + "\n"
    val p = tempFile(lines.replace("line 17", "def process(items: List[Item]): Result ="))
    val input = multiEditInput(
      p,
      ("def processs(items: List[Item]): Result =", "typo attempt", false) // extra 's' typo
    )
    MultiEditTool.call(input, ctx).map {
      case Left(err) =>
        val m = err.message
        assert(m.contains("edits[0]"), s"index: $m")
        assert(m.contains("line "), s"line hint: $m")
        assert(m.contains("similarity"), s"similarity: $m")
        assert(m.contains("Read(offset="), s"read suggestion: $m")
      case Right(_) => fail("must fail")
    }
  }

  // -------------------------------------------------------------------------
  // Guardrails: no create-file, path validation, summarize
  // -------------------------------------------------------------------------

  test("#11: MultiEdit refuses to create files (missing file → clear error)") {
    val input = multiEditInput(Paths.get("/tmp/nb-multiedit-no-such-file-xyz.txt"), ("a", "b", false))
    MultiEditTool.call(input, ctx).map {
      case Left(err) => assert(err.message.contains("cannot create"), err.message)
      case Right(_) => fail("must fail")
    }
  }

  test("#12: relative path rejected") {
    val input = multiEditInput(Paths.get("relative/x.txt"), ("a", "b", false))
    MultiEditTool.call(input, ctx).map {
      case Left(err) => assert(err.message.contains("absolute"))
      case Right(_) => fail("must fail")
    }
  }

  test("#13: summarize and summarizeResult") {
    val p = Paths.get("/tmp/spec/MyFile.scala")
    assertEquals(
      MultiEditTool.summarize(multiEditInput(p, ("a", "b", false), ("c", "d", false))),
      "MultiEdit(MyFile.scala, 2 edits)\n  (\"/tmp/spec/MyFile.scala\")"
    )
    val result = "OK:UPDATED MyFile.scala, 3 added, 1 removed\n@@ -1,1 +1,3 @@\n+x"
    assertEquals(MultiEditTool.summarizeResult(JsonObject(), result), "3 lines added, 1 line removed")
  }
end MultiEditToolSpec
