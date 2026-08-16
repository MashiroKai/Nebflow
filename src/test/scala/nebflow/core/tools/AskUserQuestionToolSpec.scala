package nebflow.core.tools

import io.circe.Json
import io.circe.parser.*
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.{AskItem, AskOption}

/**
 * AskUserQuestion `multiple` support (question-level multi-select):
 *  - parseItems: `multiple` three states (true / false / absent)
 *  - formatAnswer: multi-select answers (JSON array strings) vs scalar answers
 *  - backward compatibility: questions without `multiple` keep the exact
 *    historical parsing + answer formatting
 */
class AskUserQuestionToolSpec extends FunSuite:

  private def questionJson(fields: (String, Json)*): Json =
    Json.obj(fields*)

  // ============================================================
  // parseItems — multiple three states
  // ============================================================

  test("parseItems: multiple absent defaults to false") {
    val input = List(questionJson(
      "question" -> "Which stack?".asJson,
      "options" -> Json.arr(
        Json.obj("label" -> "Scala".asJson),
        Json.obj("label" -> "Rust".asJson)
      )
    ))
    val items = AskUserQuestionTool.parseItems(input)
    assertEquals(items.size, 1)
    assertEquals(items.head.multiple, false)
    assertEquals(items.head.options.map(_.label), List("Scala", "Rust"))
  }

  test("parseItems: multiple true is decoded") {
    val input = List(questionJson(
      "question" -> "Which areas?".asJson,
      "multiple" -> true.asJson,
      "options" -> Json.arr(Json.obj("label" -> "Frontend".asJson))
    ))
    val items = AskUserQuestionTool.parseItems(input)
    assertEquals(items.head.multiple, true)
  }

  test("parseItems: multiple false is decoded") {
    val input = List(questionJson(
      "question" -> "Deploy?".asJson,
      "multiple" -> false.asJson
    ))
    assertEquals(AskUserQuestionTool.parseItems(input).head.multiple, false)
  }

  test("parseItems: non-boolean multiple falls back to false (lenient)") {
    val input = List(questionJson(
      "question" -> "Deploy?".asJson,
      "multiple" -> "yes".asJson
    ))
    assertEquals(AskUserQuestionTool.parseItems(input).head.multiple, false)
  }

  test("parseItems: multiple coexists with id and dependsOn") {
    val input = List(questionJson(
      "question" -> "Extras?".asJson,
      "id" -> "extras".asJson,
      "multiple" -> true.asJson,
      "dependsOn" -> Json.obj("ref" -> "target".asJson, "equals" -> "docker".asJson),
      "options" -> Json.arr(Json.obj("label" -> "Cache".asJson))
    ))
    val item = AskUserQuestionTool.parseItems(input).head
    assertEquals(item.multiple, true)
    assertEquals(item.id, Some("extras"))
    assertEquals(item.dependsOn.map(d => (d.ref, d.equals)), Some(("target", "docker")))
  }

  test("parseItems: malformed entries still skipped (empty question / empty label)") {
    val input = List(
      questionJson("question" -> "".asJson), // blank question → skipped
      questionJson(
        "question" -> "Pick".asJson,
        "multiple" -> true.asJson,
        "options" -> Json.arr(
          Json.obj("label" -> "".asJson), // blank label → skipped
          Json.obj("label" -> "ok".asJson)
        )
      )
    )
    val items = AskUserQuestionTool.parseItems(input)
    assertEquals(items.size, 1)
    assertEquals(items.head.options.map(_.label), List("ok"))
    assertEquals(items.head.multiple, true)
  }

  // ============================================================
  // formatAnswer — multi-select array vs scalar
  // ============================================================

  private def multiItem(q: String, labels: String*): AskItem =
    AskItem(q, labels.map(l => AskOption(l)).toList, multiple = true)

  private def singleItem(q: String, labels: String*): AskItem =
    AskItem(q, labels.map(l => AskOption(l)).toList)

  test("formatAnswer: single multi-select question renders a JSON array") {
    val items = List(multiItem("Which areas?", "Frontend", "Backend", "Docs"))
    val answers = List("""["Frontend","Backend"]""")
    assertEquals(AskUserQuestionTool.formatAnswer(items, answers), """["Frontend","Backend"]""")
  }

  test("formatAnswer: multi-select answer is normalized to compact JSON") {
    val items = List(multiItem("Which areas?", "Frontend", "Backend"))
    // spaced/pretty input still normalizes
    val answers = List("""[ "Frontend" , "Backend" ]""")
    assertEquals(AskUserQuestionTool.formatAnswer(items, answers), """["Frontend","Backend"]""")
  }

  test("formatAnswer: mixed single + multiple questions keep positions and the arrow style") {
    val items = List(
      singleItem("Which stack?", "Scala", "Rust"),
      multiItem("Which areas?", "Frontend", "Backend")
    )
    val answers = List("Scala", """["Frontend","Backend"]""")
    val expected =
      """1. Which stack?
        |   → Scala
        |2. Which areas?
        |   → ["Frontend","Backend"]""".stripMargin
    assertEquals(AskUserQuestionTool.formatAnswer(items, answers), expected)
  }

  test("formatAnswer: non-JSON multi-select payload passes through unchanged (fallback)") {
    val items = List(multiItem("Which areas?", "Frontend", "Backend"))
    val answers = List("Frontend, Backend") // old-frontend joined text
    assertEquals(AskUserQuestionTool.formatAnswer(items, answers), "Frontend, Backend")
  }

  test("formatAnswer: empty multi-select answer formats as skipped like before") {
    val items = List(singleItem("Stack?", "Scala"), multiItem("Areas?", "Frontend"))
    val answers = List("Scala", "")
    val expected =
      """1. Stack?
        |   → Scala
        |2. Areas?
        |   → (skipped)""".stripMargin
    assertEquals(AskUserQuestionTool.formatAnswer(items, answers), expected)
  }

  // ============================================================
  // Backward compatibility — byte-identical legacy paths
  // ============================================================

  test("formatAnswer: legacy single question, scalar answer — unchanged format") {
    val items = List(singleItem("Continue?", "yes", "no"))
    assertEquals(AskUserQuestionTool.formatAnswer(items, List("yes")), "yes")
  }

  test("formatAnswer: legacy multi-question layout — unchanged format") {
    val items = List(
      singleItem("Which language?", "Scala", "Rust"),
      singleItem("Priority?", "Performance", "Simplicity")
    )
    val answers = List("Scala", "Performance")
    val expected =
      """1. Which language?
        |   → Scala
        |2. Priority?
        |   → Performance""".stripMargin
    assertEquals(AskUserQuestionTool.formatAnswer(items, answers), expected)
  }

  test("inputSchema: multiple documented as boolean on question items") {
    val schema = AskUserQuestionTool.inputSchema
    val items = schema("properties")
      .flatMap(_.asObject)
      .flatMap(_("questions"))
      .flatMap(_.asObject)
      .flatMap(_("items"))
      .flatMap(_.asObject)
    val props = items.flatMap(_("properties")).flatMap(_.asObject)
    val multiple = props.flatMap(_("multiple")).flatMap(_.asObject)
    assert(multiple.isDefined, "question items schema must document `multiple`")
    assertEquals(multiple.flatMap(_("type")).flatMap(_.asString), Some("boolean"))
  }

  test("description: documents the multiple flag usage") {
    assert(AskUserQuestionTool.description.contains("\"multiple\": true"))
  }

end AskUserQuestionToolSpec
