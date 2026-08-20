package nebflow.core.tools

import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.presets.{ModelPreset, PresetFile, PresetStore}

/**
 * 2026-08-20: preset descriptions written in Settings must be visible to
 * agents — Delegate/SubTask render the live preset catalog into the `preset`
 * parameter doc. Covered:
 *  - catalogLines rendering (name — note; name-only when no description;
 *    name-sorted; Nil on unreadable store)
 *  - tool inputSchema embeds the catalog consistently with the store
 */
class PresetCatalogInToolDocSpec extends FunSuite:

  private def tempStore(suffix: String, presets: (String, String)*): PresetStore =
    val dir = os.pwd / "target" / "preset-catalog-test" / suffix
    os.makeDir.all(dir)
    val path = dir / "model-presets.json"
    val file = PresetFile(
      defaultPreset = presets.headOption.map(_._1).getOrElse(""),
      presets = presets.map { case (name, desc) =>
        name -> ModelPreset(name = name, description = desc, preferred = Some("prov/model"))
      }.toMap
    )
    os.write.over(path, file.asJson.toString)
    new PresetStore(path, () => List("prov/model"))

  test("catalogLines: description renders as 'name — note'"):
    val store = tempStore("with-notes", "LowCost" -> "便宜但慢", "Vision" -> "vision capable")
    val lines = PresetStore.catalogLines(store)
    assertEquals(lines, List("LowCost — 便宜但慢", "Vision — vision capable"))

  test("catalogLines: presets without description list name only (no dangling dash)"):
    val store = tempStore("no-notes", "Bare" -> "", "Quiet" -> "   ")
    val lines = PresetStore.catalogLines(store)
    assertEquals(lines, List("Bare", "Quiet"))

  test("catalogLines: sorted by name regardless of file order"):
    val store = tempStore("sorted", "Zeta" -> "z", "Alpha" -> "a", "Mid" -> "m")
    assertEquals(PresetStore.catalogLines(store).map(_.takeWhile(_ != ' ')), List("Alpha", "Mid", "Zeta"))

  test("catalogLines: unreadable store degrades to Nil (catalog omitted, schema still builds)"):
    val dir = os.pwd / "target" / "preset-catalog-test" / "broken"
    os.makeDir.all(dir)
    val path = dir / "model-presets.json"
    os.write.over(path, "{ not valid json")
    // A broken file is repaired by load() (invariant enforcer), so simulate a
    // hard failure instead: a directory where the file should be.
    val storeDir = os.pwd / "target" / "preset-catalog-test" / "unreadable"
    os.makeDir.all(storeDir / "model-presets.json") // path is a DIRECTORY → read throws
    val store = new PresetStore(storeDir / "model-presets.json", () => List("prov/model"))
    assertEquals(PresetStore.catalogLines(store), Nil)

  private def presetParamDoc(schema: io.circe.JsonObject): String =
    schema("properties")
      .flatMap(_.asObject)
      .flatMap(_("preset"))
      .flatMap(_.asObject)
      .flatMap(_("description"))
      .flatMap(_.asString)
      .getOrElse(fail("preset parameter missing from schema"))

  test("Delegate inputSchema embeds live catalog from the store"):
    val schema = DelegateTool.inputSchema
    val doc = presetParamDoc(schema)
    // Static semantics preserved
    assert(doc.contains("overrides the sub-agent's own preset/model"))
    // Catalog consistency: every preset in the real store appears in the doc
    // (environment-independent — self-consistent with the same file both read)
    val names = PresetStore.catalogLines().map(_.takeWhile(c => c != ' ' && c != '—').trim)
    names.foreach(n => assert(doc.contains(n), s"preset $n missing from Delegate preset doc"))

  test("SubTask inputSchema embeds live catalog from the store"):
    val doc = presetParamDoc(SubTaskTool.inputSchema)
    assert(doc.contains("overrides the worker's own preset/model"))
    val names = PresetStore.catalogLines().map(_.takeWhile(c => c != ' ' && c != '—').trim)
    names.foreach(n => assert(doc.contains(n), s"preset $n missing from SubTask preset doc"))

  test("dynamic: rendering path re-reads the store (fresh per call, val→def)"):
    val dir = os.pwd / "target" / "preset-catalog-test" / "fresh"
    os.makeDir.all(dir)
    val path = dir / "model-presets.json"
    val mkStore = () => new PresetStore(path, () => List("prov/model"))
    os.write.over(path, PresetFile("Old", Map("Old" -> ModelPreset("Old", "old note", Some("prov/model")))).asJson.toString)
    assertEquals(PresetStore.catalogLines(mkStore()), List("Old — old note"))
    os.write.over(path, PresetFile("Old", Map("Old" -> ModelPreset("Old", "NEW note", Some("prov/model")))).asJson.toString)
    assertEquals(PresetStore.catalogLines(mkStore()), List("Old — NEW note"))

end PresetCatalogInToolDocSpec
