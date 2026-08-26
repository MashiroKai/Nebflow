package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

/**
 * #303 D1/D5 — RefResolver unit tests.
 *
 * Covers the resolve() → [引用: …] injection-block mapping (spec
 * 20260825_global-reference-spec §2.3 ③) for the four refTypes plus the
 * fail-open edges (unknown refType / missing identity → None). Input shapes
 * mirror the frontend refs payload (input.js L845: {refType, id, source,
 * anchor, meta, display}).
 */
class RefResolverSpec extends FunSuite:

  private def fileRef(overrides: (String, Json)*): Json =
    val base = Json.obj(
      "refType" -> "file".asJson,
      "id" -> "ref:file:x".asJson,
      "source" -> Json.obj(
        "kind" -> "workspace".asJson,
        "path" -> "/Users/dev/Claude code/Nebflow/README.md".asJson,
        "fileName" -> "README.md".asJson,
        "title" -> "README.md".asJson
      ),
      "anchor" -> Json.obj("kind" -> "none".asJson),
      "meta" -> Json.obj("icon" -> "file-text".asJson, "typeLabel" -> "MD".asJson),
      "display" -> Json.obj("label" -> "README.md".asJson, "preview" -> "".asJson)
    )
    overrides.foldLeft(base) { (acc, kv) =>
      val (k, v) = kv
      acc.mapObject(_.add(k, v))
    }

  test("file ref resolves to a pointer block (source + title, no content)"):
    val res = RefResolver.resolve(fileRef())
    assert(res.isDefined)
    assertEquals(
      res.get,
      "[引用: 文件 · README.md · /Users/dev/Claude code/Nebflow/README.md]"
    )

  test("file ref with missing title falls back to the file name from path"):
    val noTitle = fileRef(
      "source" -> Json.obj(
        "kind" -> "workspace".asJson,
        "path" -> "/tmp/notes.txt".asJson
      )
    )
    assertEquals(
      RefResolver.resolve(noTitle).get,
      "[引用: 文件 · notes.txt · /tmp/notes.txt]"
    )

  test("file ref with empty path is unresolvable (None)"):
    val noPath = fileRef(
      "source" -> Json.obj("kind" -> "workspace".asJson, "path" -> "".asJson, "title" -> "x".asJson)
    )
    assert(RefResolver.resolve(noPath).isEmpty)

  test("document ref with page anchor carries the page badge"):
    val doc = fileRef(
      "refType" -> "document".asJson,
      "source" -> Json.obj(
        "kind" -> "canvas".asJson,
        "path" -> "/abs/paper.pdf".asJson,
        "title" -> "paper.pdf".asJson
      ),
      "anchor" -> Json.obj(
        "kind" -> "page".asJson,
        "pageStart" -> 3.asJson,
        "pageEnd" -> 4.asJson
      )
    )
    assertEquals(
      RefResolver.resolve(doc).get,
      "[引用: 文档 · paper.pdf · p.3–4 · /abs/paper.pdf]"
    )

  test("document ref with single page renders p.N (no range)"):
    val doc = fileRef(
      "refType" -> "document".asJson,
      "source" -> Json.obj("kind" -> "canvas".asJson, "path" -> "/a.pdf".asJson, "title" -> "a.pdf".asJson),
      "anchor" -> Json.obj("kind" -> "page".asJson, "pageStart" -> 3.asJson, "pageEnd" -> 3.asJson)
    )
    assertEquals(RefResolver.resolve(doc).get, "[引用: 文档 · a.pdf · p.3 · /a.pdf]")

  test("document ref with line range renders L start–end"):
    val doc = fileRef(
      "refType" -> "document".asJson,
      "source" -> Json.obj("kind" -> "canvas".asJson, "path" -> "/f.md".asJson, "title" -> "f.md".asJson),
      "anchor" -> Json.obj("kind" -> "range".asJson, "lineStart" -> 12.asJson, "lineEnd" -> 45.asJson)
    )
    assertEquals(RefResolver.resolve(doc).get, "[引用: 文档 · f.md · L12–45 · /f.md]")

  test("document ref with cell anchor renders sheet!cellRange"):
    val doc = fileRef(
      "refType" -> "document".asJson,
      "source" -> Json.obj("kind" -> "canvas".asJson, "path" -> "/t.xlsx".asJson, "title" -> "t.xlsx".asJson),
      "anchor" -> Json.obj("kind" -> "cell".asJson, "sheet" -> "Sheet1".asJson, "cellRange" -> "A1:D10".asJson)
    )
    assertEquals(RefResolver.resolve(doc).get, "[引用: 文档 · t.xlsx · Sheet1!A1:D10 · /t.xlsx]")

  test("html-element ref resolves with element tag from the selector"):
    val el = fileRef(
      "refType" -> "html-element".asJson,
      "source" -> Json.obj("kind" -> "web".asJson, "url" -> "https://ex.com/post".asJson, "title" -> "Post title".asJson),
      "anchor" -> Json.obj(
        "kind" -> "element".asJson,
        "selector" -> "html>body>div.post>p:nth-of-type(2)".asJson,
        "text" -> "…选中段落文本…".asJson
      )
    )
    assertEquals(
      RefResolver.resolve(el).get,
      "[引用: 页面元素 · Post title · <p> · https://ex.com/post]"
    )

  test("html-element ref with empty url is unresolvable"):
    val el = fileRef(
      "refType" -> "html-element".asJson,
      "source" -> Json.obj("kind" -> "web".asJson, "url" -> "".asJson, "title" -> "x".asJson),
      "anchor" -> Json.obj("kind" -> "element".asJson, "selector" -> "p".asJson)
    )
    assert(RefResolver.resolve(el).isEmpty)

  test("task refType is NOT resolved here (routes to the return flow)"):
    val task = fileRef(
      "refType" -> "task".asJson,
      "source" -> Json.obj("kind" -> "task".asJson, "taskId" -> "101".asJson, "sessionId" -> "sess-x".asJson, "title" -> "修复登录".asJson)
    )
    assert(RefResolver.resolve(task).isEmpty)

  test("unknown refType yields None (fail-open)"):
    val unknown = fileRef("refType" -> "mystery".asJson)
    assert(RefResolver.resolve(unknown).isEmpty)

  test("anchor kind none renders no badge segment"):
    val res = RefResolver.resolve(fileRef())
    assert(!res.get.contains(" · p."))
    assert(!res.get.contains(" · L"))
    assert(!res.get.contains(" · <"))

  test("tagOf extracts the first tag from a selector path"):
    assertEquals(RefResolver.tagOf("html>body>div.c>p:nth-of-type(2)"), Some("p"))
    assertEquals(RefResolver.tagOf("section#main"), Some("section"))
    assertEquals(RefResolver.tagOf(""), None)

end RefResolverSpec
