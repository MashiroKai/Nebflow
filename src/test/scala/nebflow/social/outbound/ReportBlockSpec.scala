package nebflow.social.outbound

import munit.FunSuite

class ReportBlockSpec extends FunSuite:
  test("only report bodies are extracted, independent of the retired gate") {
    assertEquals(OutputBlock.extractGated("secret <report> A </report> middle <report>B</report>", false), OutputBlock.Extracted("A\n\nB"))
    assertEquals(OutputBlock.extract("<output>old</output>"), OutputBlock.NoBlocks)
    assertEquals(OutputBlock.extract("<report></report>"), OutputBlock.Extracted(""))
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
