package nebflow.agent

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.{AskItem, AskOption, AskPreview}

/**
 * #380 backend passthrough contract (V11): buildAskUserJson emits the optional
 * `canvas` (question-level) and `preview` (option-level) fields only when
 * present — a payload without them stays byte-identical to the pre-#380 wire
 * format, so the frontend direction-C consumption is additive and zero-risk.
 */
class AskUserBuildJsonSpec extends FunSuite:

  private def frame(items: List[AskItem]): Json =
    AgentActor.buildAskUserJson(Some("root-1"), "Backend", items, Some("Backend"), Some("team-abc"))

  test("V11a: canvas + preview are emitted when present") {
    val items = List(
      AskItem(
        "Choose a scheme",
        List(
          AskOption("Morning mist", preview = Some(AskPreview("swatch", colors = Some(List("#6b9c8a", "#a8c3b5"))))),
          AskOption("Image A", preview = Some(AskPreview("image", src = Some("https://example.com/a.png"))))
        ),
        canvas = Some("/abs/path/compare.html")
      )
    )
    val itemsJson = frame(items).hcursor.downField("items").as[List[Json]].toOption.get
    assertEquals(itemsJson.head.hcursor.downField("canvas").as[String], Right("/abs/path/compare.html"))
    val opts = itemsJson.head.hcursor.downField("options").as[List[Json]].toOption.get
    assertEquals(opts(0).hcursor.downField("preview").downField("type").as[String], Right("swatch"))
    assertEquals(
      opts(0).hcursor.downField("preview").downField("colors").as[List[String]],
      Right(List("#6b9c8a", "#a8c3b5"))
    )
    assertEquals(opts(0).hcursor.downField("preview").downField("src").focus, None)
    assertEquals(opts(1).hcursor.downField("preview").downField("type").as[String], Right("image"))
    assertEquals(
      opts(1).hcursor.downField("preview").downField("src").as[String],
      Right("https://example.com/a.png")
    )
  }

  test("V11b: no canvas/preview → payload byte-identical to the pre-#380 shape") {
    val items = List(AskItem("Continue?", List(AskOption("yes"), AskOption("no"))))
    val expected =
      """{"type":"askUser","sessionId":"root-1","agentName":"Backend","sourceAgent":"Backend",""" +
        """"sourceSession":"team-abc","items":[{"question":"Continue?","options":[{"label":"yes"},{"label":"no"}],""" +
        """"allowOther":true}]}"""
    assertEquals(frame(items).noSpaces, expected)
  }

  test("V11c: preview with description keeps both keys in order") {
    val items = List(AskItem("Pick", List(AskOption("A", description = Some("explain"), preview = Some(AskPreview("swatch", colors = Some(List("#111"))))))))
    val opts = frame(items).hcursor.downField("items").as[List[Json]].toOption.get.head
      .hcursor.downField("options").as[List[Json]].toOption.get
    val keys = opts.head.hcursor.keys.map(_.toSet).getOrElse(Set.empty)
    assertEquals(keys, Set("label", "description", "preview"))
    assertEquals(
      opts.head.hcursor.downField("preview").downField("colors").as[List[String]],
      Right(List("#111"))
    )
  }

  // ---- D6 批 F1（G9 来源标注）：project/nodeName 字段契约 ----

  test("F1a: project + nodeName are emitted when present (project node ask)") {
    val items = List(AskItem("Which scheme?", List(AskOption("A"), AskOption("B"))))
    val json = AgentActor.buildAskUserJson(
      Some("root-1"), "general", items, Some("general"), Some("node-abc"),
      project = Some("Nebflow"), nodeName = Some("实施-F1F2")
    )
    assertEquals(json.hcursor.downField("project").as[String], Right("Nebflow"))
    assertEquals(json.hcursor.downField("nodeName").as[String], Right("实施-F1F2"))
  }

  test("F1b: dispatcher ask carries nodeName=dispatcher") {
    val items = List(AskItem("Proceed?", List(AskOption("yes"), AskOption("no"))))
    val json = AgentActor.buildAskUserJson(
      Some("root-1"), "dispatcher", items, Some("dispatcher"), Some("disp-1"),
      project = Some("Nebflow"), nodeName = Some("dispatcher")
    )
    assertEquals(json.hcursor.downField("project").as[String], Right("Nebflow"))
    assertEquals(json.hcursor.downField("nodeName").as[String], Right("dispatcher"))
  }

  test("F1c: no project/nodeName → payload stays byte-identical to the pre-F1 shape") {
    val items = List(AskItem("Continue?", List(AskOption("yes"), AskOption("no"))))
    val json = frame(items)
    assertEquals(json.hcursor.downField("project").focus, None)
    assertEquals(json.hcursor.downField("nodeName").focus, None)
    val expected =
      """{"type":"askUser","sessionId":"root-1","agentName":"Backend","sourceAgent":"Backend",""" +
        """"sourceSession":"team-abc","items":[{"question":"Continue?","options":[{"label":"yes"},{"label":"no"}],""" +
        """"allowOther":true}]}"""
    assertEquals(json.noSpaces, expected)
  }

end AskUserBuildJsonSpec
