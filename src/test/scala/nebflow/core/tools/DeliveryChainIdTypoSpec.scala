package nebflow.core.tools

import munit.FunSuite

/**
 * 送达链路修复批（2026-11-23）P1⑥ 判红锚：AgentControl 手抄 ID 容错。
 * 诊断书实证：23c610c6 vs 真实 23c910c6（单字符抄错）制造「记录无痕消失」表象。
 */
class DeliveryChainIdTypoSpec extends FunSuite:

  test("P1⑥: transposed/single-char typo finds the real id (23c610c6 vs 23c910c6 class)") {
    val real = List("delegate-nebflow-23c910c6", "delegate-nebflow-aaaaaaaa")
    val got = AgentControlTool.didYouMeanCandidates("delegate-nebflow-23c610c6", real)
    assertEquals(got.headOption, Some("delegate-nebflow-23c910c6"))
  }

  test("P1⑥: short tail hand-copy matches by suffix") {
    val got = AgentControlTool.didYouMeanCandidates("23c910c6", List("delegate-nebflow-23c910c6", "other-zzzzzzzz"))
    assertEquals(got, List("delegate-nebflow-23c910c6"))
  }

  test("P1⑥: unrelated id yields no suggestion; too-short query yields none") {
    assertEquals(AgentControlTool.didYouMeanCandidates("delegate-nebflow-ffffffff", List("delegate-nebflow-23c910c6")), Nil)
    assertEquals(AgentControlTool.didYouMeanCandidates("ab", List("abc")), Nil)
  }

  test("P1⑥: editDistance basic correctness") {
    assertEquals(AgentControlTool.editDistance("kitten", "sitting", bound = 5), 3)
    assertEquals(AgentControlTool.editDistance("abc", "abc"), 0)
    // 超界（> bound）时返回值只保证 > bound（早停语义），不承诺精确距离。
    assert(AgentControlTool.editDistance("abcdef", "uvwxyz", bound = 2) > 2)
  }

end DeliveryChainIdTypoSpec
