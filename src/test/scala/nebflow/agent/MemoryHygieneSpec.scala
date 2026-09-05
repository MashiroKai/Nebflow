package nebflow.agent

import munit.FunSuite
import nebflow.service.MemoryBudget

/**
 * 生命周期记忆整理信号 + 提醒渲染 spec（memory-management-plan §6.2-2.5，
 * 2026-09-05 批次二机制五）。
 *
 * 覆盖：
 *  - MemoryHygieneSignal 状态机：重启后首会话一次性消费 / 压缩置位一次性消费；
 *  - renderMemoryBlock / memoryHygieneNotice：>80% 即时任务措辞（升级）；
 *    重启/压缩事件轻量提醒；预算内无信号 = 零打扰；注入主体不受影响。
 *
 * 纯函数直测（renderMemoryBlock 不触文件系统）——buildMemoryBlock 只是
 * 「MemoryStore 读 + 信号消费 + 纯渲染」的薄壳。
 */
class MemoryHygieneSpec extends FunSuite:

  private val small = Some("# User\n\n- 正常内容")

  // ---------------------------------------------------------------
  // MemoryHygieneSignal 状态机
  // ---------------------------------------------------------------

  test("signal: 进程启动 = 重启事件待消费；takePending 一次性取走"):
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)
    assertEquals(MemoryHygieneSignal.takePending(), (true, false), "首消费取到重启事件")
    assertEquals(MemoryHygieneSignal.takePending(), (false, false), "一次性：二次消费为空")

  test("signal: markCompacted 置位一次性消费"):
    MemoryHygieneSignal.resetForTest(restartedV = false, compactedV = false)
    MemoryHygieneSignal.markCompacted()
    assertEquals(MemoryHygieneSignal.peek(), (false, true))
    assertEquals(MemoryHygieneSignal.takePending(), (false, true))
    assertEquals(MemoryHygieneSignal.takePending(), (false, false))

  test("signal: 重启+压缩并发事件一次取齐"):
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)
    MemoryHygieneSignal.markCompacted()
    assertEquals(MemoryHygieneSignal.takePending(), (true, true))

  // ---------------------------------------------------------------
  // 提醒渲染：>80% 升级 / 事件提醒 / 零打扰
  // ---------------------------------------------------------------

  test("notice: 预算内 + 无事件 → 无提醒（零打扰）"):
    assertEquals(ContextRefresher.memoryHygieneNotice(small, small, (false, false)), "")

  test("notice: 压缩事件 → 轻量清扫提示（不改 hook 清扫逻辑，只提示）"):
    val n = ContextRefresher.memoryHygieneNotice(small, small, (false, true))
    assert(n.contains("Memory hygiene"), n)
    assert(n.contains("compacted"), s"提及压缩: $n")
    assert(n.contains("memory-consolidation"), "指向整理 skill")
    assert(!n.contains("IMMEDIATE"), "预算内不升级")

  test("notice: 重启事件 → 提示 T2 闭环场景"):
    val n = ContextRefresher.memoryHygieneNotice(small, small, (true, false))
    assert(n.contains("restarted"), s"提及重启: $n")
    assert(!n.contains("IMMEDIATE"))

  test("notice: 任一文件 >80% 软线 → IMMEDIATE TASK 措辞 + 字节明细（不等周日）"):
    val userBig = Some("x" * 41000) // > 40KB 软线
    val n = ContextRefresher.memoryHygieneNotice(userBig, small, (false, false))
    assert(n.contains("IMMEDIATE TASK"), s"80% 升级为即时任务措辞: $n")
    assert(n.contains("41000 bytes"), "列明当前字节")
    assert(n.contains(s"${MemoryBudget.UserSoftBytes}"), "列明软线")
    assert(n.contains("THIS TURN"), "当轮安排整理")

  test("notice: agent 文件 >24KB 同样升级"):
    val agentBig = Some("x" * 24577) // > 24KB 软线
    val n = ContextRefresher.memoryHygieneNotice(small, agentBig, (false, false))
    assert(n.contains("IMMEDIATE TASK"))
    assert(n.contains("memory.md"), "指明 agent 记忆文件")

  test("notice: 80% 超限优先于事件提醒（同一块不重复渲染两段）"):
    val userBig = Some("x" * 41000)
    val n = ContextRefresher.memoryHygieneNotice(userBig, small, (true, true))
    assert(n.contains("IMMEDIATE TASK"))
    assertEquals(1, n.split("## Memory hygiene", -1).length - 1, "单一提醒块")
    assert(!n.contains("just restarted"), "事件措辞不与即时措辞混排")

  test("render: 记忆主体完整保留 + 提醒追加其后（注入侧不加截断裁定不破坏）"):
    val userBig = Some("x" * 41000)
    val block = ContextRefresher.renderMemoryBlock(userBig, small, (false, false))
    assert(block.contains("# Memory"), "记忆块头保留")
    assert(block.contains("x" * 100), "主体内容完整（无截断）")
    assert(block.contains("IMMEDIATE TASK"), "提醒追加在主体之后")
    assert(block.indexOf("IMMEDIATE TASK") > block.indexOf("# Memory"))

end MemoryHygieneSpec
