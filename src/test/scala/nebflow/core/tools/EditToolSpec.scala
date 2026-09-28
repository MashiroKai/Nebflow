package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}

class EditToolSpec extends CatsEffectSuite:

  private val ctx = ToolContext(projectRoot = "/tmp")

  private def tempFile(content: String): Path =
    val p = Files.createTempFile("nb-edit-spec", ".txt")
    Files.write(p, content.getBytes("UTF-8"))
    p.toFile.deleteOnExit()
    p

  private def editInput(path: Path, old: String, neu: String, replaceAll: Boolean = false): JsonObject =
    JsonObject(
      "file_path" -> path.toString.asJson,
      "old_string" -> old.asJson,
      "new_string" -> neu.asJson,
      "replace_all" -> replaceAll.asJson
    )

  // -------------------------------------------------------------------------
  // applyEditToBuffer — pure function (shared with MultiEditTool)
  // -------------------------------------------------------------------------

  test("applyEditToBuffer: exact match replaces first occurrence") {
    val res = EditTool.applyEditToBuffer("aaa bbb ccc", "bbb", "XXX", replaceAll = false)
    assertEquals(res, Right("aaa XXX ccc"))
  }

  test("applyEditToBuffer: empty old_string yields EmptyOldString") {
    val res = EditTool.applyEditToBuffer("content", "", "x", replaceAll = false)
    assertEquals(res, Left(EditFailure.EmptyOldString))
  }

  test("applyEditToBuffer: not-found yields OldStringNotFound") {
    val res = EditTool.applyEditToBuffer("aaa bbb", "zzz", "XXX", replaceAll = false)
    assertEquals(res, Left(EditFailure.OldStringNotFound))
  }

  test("applyEditToBuffer: multiple matches without replaceAll yields NotUnique") {
    val res = EditTool.applyEditToBuffer("x y x y x", "x", "z", replaceAll = false)
    res match
      case Left(EditFailure.NotUnique(n)) => assertEquals(n, 3)
      case other => fail(s"expected NotUnique, got $other")
  }

  test("applyEditToBuffer: replaceAll replaces every occurrence") {
    val res = EditTool.applyEditToBuffer("x y x y x", "x", "z", replaceAll = true)
    assertEquals(res, Right("z y z y z"))
  }

  test("applyEditToBuffer: whitespace-insensitive fuzzy match works") {
    // differing whitespace RUN LENGTHS collapse to single spaces (matcher level 3)
    val buffer = "def  foo(a:Int)  =  1"
    val res = EditTool.applyEditToBuffer(buffer, "def foo(a:Int) = 1", "CHANGED", replaceAll = false)
    assertEquals(res, Right("CHANGED"))
  }

  test("applyEditToBuffer: CRLF in old_string is normalized before matching") {
    val buffer = "line1\nline2\nline3"
    val res = EditTool.applyEditToBuffer(buffer, "line1\r\nline2", "A\nB", replaceAll = false)
    assertEquals(res, Right("A\nB\nline3"))
  }

  test("applyEditToBuffer: curly-quote style is preserved onto new_string") {
    // buffer uses curly quotes; old_string uses straight quotes (quote-normalized match)
    val buffer = "val s = \u201Chello\u201D"
    val res = EditTool.applyEditToBuffer(buffer, "val s = \"hello\"", "val s = \"bye\"", replaceAll = false)
    assertEquals(res, Right("val s = \u201Cbye\u201D"))
  }

  // -------------------------------------------------------------------------
  // Edit.call — end-to-end on temp files (error messages unchanged)
  // -------------------------------------------------------------------------

  test("Edit.call: happy path writes file and returns OK:UPDATED with stats") {
    val p = tempFile("alpha\nbeta\ngamma\n")
    EditTool.call(editInput(p, "beta", "BETA"), ctx).map {
      case Right(res) =>
        assert(res.startsWith("OK:UPDATED"))
        assert(DiffUtil.parseUpdatedStats(res).contains((1, 1)), s"stats should be 1 added 1 removed: $res")
        assertEquals(DiffUtil.readFile(p), "alpha\nBETA\ngamma\n")
      case Left(err) => fail(s"unexpected error: ${err.message}")
    }
  }

  test("Edit.call: not-found error message preserved") {
    val p = tempFile("alpha\nbeta\n")
    EditTool.call(editInput(p, "zzz-not-there", "x"), ctx).map {
      case Left(err) =>
        assertEquals(err.message, "old_string not found in file. Ensure the string matches exactly, including whitespace and indentation.")
        // file untouched
        assertEquals(DiffUtil.readFile(p), "alpha\nbeta\n")
      case Right(_) => fail("should fail")
    }
  }

  test("Edit.call: non-unique error message preserved") {
    val p = tempFile("dup\ndup\n")
    EditTool.call(editInput(p, "dup", "x"), ctx).map {
      case Left(err) =>
        assert(err.message.startsWith("Found 2 matches of old_string."), err.message)
      case Right(_) => fail("should fail")
    }
  }

  test("Edit.call: empty old_string creates a new file (existing branch preserved)") {
    val p = Files.createTempFile("nb-edit-spec-create", ".md")
    Files.delete(p)
    p.toFile.deleteOnExit()
    EditTool.call(editInput(p, "", "# fresh content\n"), ctx).map {
      case Right(res) =>
        assert(res.startsWith("OK:CREATED"))
        assertEquals(DiffUtil.readFile(p), "# fresh content\n")
      case Left(err) => fail(s"unexpected error: ${err.message}")
    }
  }

  test("Edit.call: CRLF file keeps its line separator on write") {
    val p = tempFile("one\r\ntwo\r\nthree\r\n")
    EditTool.call(editInput(p, "two", "TWO"), ctx).map {
      case Right(_) =>
        assertEquals(DiffUtil.readFile(p), "one\r\nTWO\r\nthree\r\n")
      case Left(err) => fail(s"unexpected error: ${err.message}")
    }
  }
end EditToolSpec
