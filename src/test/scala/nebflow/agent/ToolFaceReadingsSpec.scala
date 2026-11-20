package nebflow.agent

/**
 * Runner for the reusable per-identity tool-face readings (see
 * [[ToolFaceReadings]] for the enumerator itself and the full contract).
 *
 *   sbt -batch "testOnly nebflow.agent.ToolFaceReadingsSpec"
 *   sbt -batch '-Dtoolface.readings=Card,Pop,ask_edit' "testOnly nebflow.agent.ToolFaceReadingsSpec"
 *
 * It prints `READING|…` lines and makes no pass/fail claim of its own — it is a
 * **readings printer**, so it stays green on either tree (before/after). The
 * pass/fail gate lives in `CardToolReviveFaceSpec`, which consumes the SAME
 * enumerator (one enumeration, never a second copy).
 */
class ToolFaceReadingsSpec extends munit.FunSuite:

  test("print per-identity tool-face readings"):
    val tools = sys.props
      .get("toolface.readings")
      .map(_.split(",").map(_.trim).filter(_.nonEmpty).toList)
      .getOrElse(List("Card", "Pop"))
    ToolFaceReadings.printReadings(tools)
    // 读数件唯一自证：枚举表非空且标签唯一（防复制粘贴造成的重复身份）。
    val labels = ToolFaceReadings.rows.map(_.label)
    assert(labels.nonEmpty, "the identity table must not be empty")
    assertEquals(labels.distinct.length, labels.length, s"duplicate identity labels: $labels")
    assert(
      ToolFaceReadings.has("ask (Canvas Ask session leg)", "Read"),
      "sanity: the ask leg has the shared Read tool on both faces"
    )

end ToolFaceReadingsSpec
