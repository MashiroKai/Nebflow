package nebflow.agent

import munit.FunSuite

/**
 * U3（作者裁定 2026-09-11：显示「subagent · 任务摘要」，否决裸 `kernel`）：
 * 内核 ask 的来源标注形态 + 会话判定。
 *
 * 验红：本用例把「标签 = 裸 agentName（kernel）」这条旧形态设为反例——回退即红。
 */
class KernelAskLabelSpec extends FunSuite:

  test("kernel session discriminator is the delegate-kernel- prefix (same key as the audit filter)"):
    assert(AgentActor.isKernelSession("delegate-kernel-a1b2c3d4"))
    assert(!AgentActor.isKernelSession("delegate-Coder-a1b2c3d4"), "旧 standalone 形态不再算内核")
    assert(!AgentActor.isKernelSession("delegate-kernelish-x"))
    assert(!AgentActor.isKernelSession("node-41-session-abc"))

  test("label = 'subagent · <description>' and NEVER the bare agent name"):
    val label = AgentActor.subagentAskLabel(Some("pull the last 20 lines of D:\\build\\out.log"), "delegate-kernel-a1b2c3d4")
    assert(label.startsWith("subagent · "), label)
    assert(label != "kernel", "U3 否决裸 kernel（作者逐字）")
    assert(label != "delegate · kernel", "U3 亦否决 delegate · kernel 形态")
    // 摘要 ≤ 24 字符（不把整段任务文本灌进标签）
    val summary = label.stripPrefix("subagent · ")
    assert(summary.length <= 24, s"summary must be <= 24 chars, got ${summary.length}: $summary")

  test("short description is used verbatim; distinct tasks give distinct labels (multi-card discernibility)"):
    val a = AgentActor.subagentAskLabel(Some("拉日志"), "delegate-kernel-aaaa1111")
    val b = AgentActor.subagentAskLabel(Some("重启 foo 服务"), "delegate-kernel-bbbb2222")
    assertEquals(a, "subagent · 拉日志")
    assertEquals(b, "subagent · 重启 foo 服务")
    assertNotEquals(a, b, "多张卡必须可区分（U3 第 1 点）")

  test("blank/absent description falls back to the session suffix (never an empty label)"):
    val l1 = AgentActor.subagentAskLabel(None, "delegate-kernel-ab12cd34")
    val l2 = AgentActor.subagentAskLabel(Some("   "), "delegate-kernel-ab12cd34")
    assertEquals(l1, "subagent · ab12cd34")
    assertEquals(l2, "subagent · ab12cd34")

  test("exactly-24-char summary is not truncated; 25 chars gets the ellipsis"):
    val exact = "123456789012345678901234" // 24
    assertEquals(AgentActor.subagentAskLabel(Some(exact), "s"), s"subagent · $exact")
    val over = "1234567890123456789012345" // 25
    val l = AgentActor.subagentAskLabel(Some(over), "s")
    assertEquals(l, "subagent · " + "12345678901234567890123" + "…")
    assertEquals(l.stripPrefix("subagent · ").length, 24)

end KernelAskLabelSpec
