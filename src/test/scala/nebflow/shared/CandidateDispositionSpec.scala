package nebflow.shared

import munit.FunSuite

/**
 * 缺陷批 2026-11-05（作者报障逐字：「我们的 fallback 队列里肯定是有可用的模型在
 * 的，但是还是报了所有模型不可用，然后把后台 agent 冻结了」）——**候选账本**判据。
 *
 * 本 spec 钉死三件事（全部是纯函数面，无 IO）：
 *   1. 终局判词分形态：只有「链上每个候选都被真实试过且全败」才允许出现
 *      `All providers failed` / `all-providers-failed`；
 *   2. 被跳过的候选必须带原因进表（`[skipped] ref — health-down(...)` 等），
 *      试过的候选进 `[tried]` 表——通知面不许只剩「试过的那几根」；
 *   3. 静默排除审计：配置里可见、未 disabled、却既不在链上也没有原因的候选
 *      **必须被检出并报警**（防止「有可用模型却当它不存在」再次发生）。
 *
 * 断言 3 / 4 / 5（作者五条验收里的三条）在此纯函数面钉死；1 / 2 的端到端腿见
 * `nebflow.llm.FallbackEligibleProvidersSpec`。
 */
class CandidateDispositionSpec extends FunSuite:

  private val hd = CandidateSkipCause.HealthDown("statusCode: 429")
  private val sa = CandidateSkipCause.SoftAvoid(298_000L, "quota exhausted")

  // ---------------------------------------------------------------
  // 判词分形态（作者令第 5 条：三种形态三种 reason code）
  // ---------------------------------------------------------------

  test("D1: every candidate tried and failed ⇒ all-providers-failed（唯一允许的全败形态）") {
    val d = CandidateDisposition(
      chain = List("kimi/k3.1-pro-highspeed", "deepseek/deepseek-flash"),
      tried = List("kimi/k3.1-pro-highspeed", "deepseek/deepseek-flash"),
      skipped = Nil
    )
    assertEquals(d.reasonCode, "all-providers-failed")
    val head = d.headline(4, "auth,unknown")
    assert(head.startsWith("All providers failed"), head)
  }

  test("D2: 有候选一次都没试 ⇒ 禁止 all-providers-failed，判 partially-tried") {
    val d = CandidateDisposition(
      chain = List("a/m1", "b/m1", "c/m1"),
      tried = List("a/m1"),
      skipped = List("b/m1" -> hd, "c/m1" -> sa)
    )
    assertEquals(d.reasonCode, "partially-tried")
    val head = d.headline(2, "auth")
    assert(!head.contains("All providers failed"), s"不许把「没试完」说成全败：$head")
    assert(head.contains("2 candidate(s) were skipped WITHOUT being tried"), head)
  }

  test("D3: 链上一个都没试 ⇒ all-skipped（不是 all-providers-failed）") {
    val d = CandidateDisposition(chain = Nil, tried = Nil, skipped = List("a/m1" -> hd), expected = List("a/m1"))
    assertEquals(d.reasonCode, "all-skipped")
    val head = d.headline(0, "")
    assert(!head.contains("All providers failed"), head)
    assert(head.contains("every candidate was skipped"), head)
  }

  test("D4: 链本身就是空的（配置里一个候选都没有）⇒ no-eligible-providers") {
    val d = CandidateDisposition(chain = Nil, tried = Nil, skipped = Nil, expected = Nil)
    assertEquals(d.reasonCode, "no-eligible-providers")
    val head = d.headline(0, "")
    assert(!head.contains("All providers failed"), head)
    assert(head.contains("no") || head.startsWith("No eligible providers"), head)
  }

  test("D5: 判词四形态互斥——同一份账本只给一个 reason code") {
    val codes = List(
      CandidateDisposition(List("a/m1"), List("a/m1")).reasonCode,
      CandidateDisposition(List("a/m1", "b/m1"), List("a/m1"), List("b/m1" -> hd)).reasonCode,
      CandidateDisposition(Nil, Nil, List("a/m1" -> hd)).reasonCode,
      CandidateDisposition(Nil, Nil).reasonCode
    )
    assertEquals(codes, List("all-providers-failed", "partially-tried", "all-skipped", "no-eligible-providers"))
    assertEquals(codes.distinct.size, 4, "四种形态必须四个码（不许合并成一个「全败」）")
  }

  // ---------------------------------------------------------------
  // 处置表（作者令第 4 条：冻结通知带候选清单与每个的处置）
  // ---------------------------------------------------------------

  test("D6: 处置表列出 tried 与 skipped(+原因)，一行不丢") {
    val d = CandidateDisposition(
      chain = List("cmdcode/deepseek/deepseek-v4.1-flash", "zhipu/glm-5.3-flash"),
      tried = List("cmdcode/deepseek/deepseek-v4.1-flash"),
      skipped = List("zhipu/glm-5.3-flash" -> hd),
      expected = List("cmdcode/deepseek/deepseek-v4.1-flash", "zhipu/glm-5.3-flash")
    )
    val table = d.render(Map("cmdcode/deepseek/deepseek-v4.1-flash" -> "unknown Unknown"))
    assert(table.contains("reason: partially-tried"), table)
    assert(table.contains("[tried]   cmdcode/deepseek/deepseek-v4.1-flash — unknown Unknown"), table)
    assert(table.contains("[skipped] zhipu/glm-5.3-flash — health-down (statusCode: 429)"), table)
    assert(table.contains("chain 2: tried 1, skipped 1"), table)
    assert(!table.contains("SILENTLY EXCLUDED"), table)
  }

  test("D7: 文案经 FallbackExhaustedError 送出时表随行（作者拿到的就是这张表）") {
    val d = CandidateDisposition(
      chain = List("a/m1", "b/m1"),
      tried = List("a/m1"),
      skipped = List("b/m1" -> sa),
      expected = List("a/m1", "b/m1")
    )
    val e = FallbackExhaustedError(
      List(
        FallbackAttempt("a", "m1", Some(FailoverReason.Auth), Some(ErrorPermanence.Permanent), 5L, 0, "t")
      ),
      disposition = Some(d)
    )
    val msg = e.getMessage
    assert(!msg.startsWith("All providers failed"), msg)
    assert(msg.contains("reason: partially-tried"), msg)
    assert(msg.contains("[skipped] b/m1 — soft-avoid (quota exhausted, 298s left)"), msg)
    assert(msg.contains("[tried]   a/m1 — auth Auth"), msg)
  }

  test("D8: 无账本时老文案逐字不变（既有单测/既有通知零影响）") {
    val e = FallbackExhaustedError(
      List(FallbackAttempt("p", "m", Some(FailoverReason.Timeout), Some(ErrorPermanence.Permanent), 1L, 0, "t"))
    )
    assert(e.getMessage.startsWith("All providers failed (1 attempt(s); families: timeout):"), e.getMessage)
    assert(!e.getMessage.contains("reason:"), "无账本不得凭空长出判词行")
  }

  // ---------------------------------------------------------------
  // 静默排除审计（作者令第 1/5 条 + 验收 5）
  // ---------------------------------------------------------------

  test("D9: 配置可见却没进链也没原因 ⇒ 必须检出（silentExclusions 非空）") {
    val d = CandidateDisposition(
      chain = List("a/m1"),
      tried = List("a/m1"),
      skipped = Nil,
      expected = List("a/m1", "b/m1")
    )
    assertEquals(d.silentExclusions, List("b/m1"))
    val table = d.render()
    assert(table.contains("1 SILENTLY EXCLUDED"), table)
    assert(table.contains("b/m1"), table)
    assert(table.contains("this is a bug, not provider failure"), table)
  }

  test("D10: 反向——有原因的排除不算静默（不许把合法跳过误报成 bug）") {
    val d = CandidateDisposition(
      chain = List("a/m1"),
      tried = List("a/m1"),
      skipped = List("b/m1" -> CandidateSkipCause.NotConfigured("provider disabled")),
      expected = List("a/m1", "b/m1")
    )
    assertEquals(d.silentExclusions, Nil)
    assert(!d.render().contains("SILENTLY EXCLUDED"), d.render())
    assert(d.render().contains("[skipped] b/m1 — not-configured (provider disabled)"), d.render())
  }

  test("D11: summary 一行带跳过原因与静默排除警报（日志面判据）") {
    val d = CandidateDisposition(List("a/m1"), List("a/m1"), List("b/m1" -> hd), List("a/m1", "b/m1", "c/m1"))
    val s = d.summary
    assert(s.contains("chain=1 tried=1 skipped=1"), s)
    assert(s.contains("b/m1(health-down)"), s)
    assert(s.contains("SILENT-EXCLUSION=c/m1"), s)
  }
end CandidateDispositionSpec
