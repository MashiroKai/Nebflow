package nebflow.social.outbound

import munit.FunSuite

class ReportBlockSpec extends FunSuite:
  test("only report bodies are extracted on the root face, independent of the retired gate") {
    assertEquals(OutputBlock.extractGated("secret <report> A </report> middle <report>B</report>", rootSession = true), OutputBlock.Extracted("A\n\nB"))
    assertEquals(OutputBlock.extract("<output>old</output>"), OutputBlock.NoBlocks)
    assertEquals(OutputBlock.extract("<report></report>"), OutputBlock.Extracted(""))
  }

  test("the non-root face ships the raw text verbatim (previous presentation, no filter)") {
    val raw = "secret <report> A </report> middle <report>B</report>"
    assertEquals(OutputBlock.extractGated(raw, rootSession = false), OutputBlock.Extracted(raw))
  }

  test("invalid structures fail closed") {
    assertEquals(OutputBlock.extract("<report>unfinished"), OutputBlock.Unbalanced)
    assertEquals(OutputBlock.extract("<report><report>x</report></report>"), OutputBlock.Unbalanced)
    assertEquals(OutputBlock.extract("</report>"), OutputBlock.Unbalanced)
  }

  test("quoted and code examples are not structural tags") {
    assertEquals(OutputBlock.extract("> <report>quoted</report>\n`<report>inline</report>`\n```html\n<report>fenced</report>\n```"), OutputBlock.NoBlocks)
    assertEquals(OutputBlock.extract("`<report>x</report>` <report>visible</report>"), OutputBlock.Extracted("visible"))
  }
