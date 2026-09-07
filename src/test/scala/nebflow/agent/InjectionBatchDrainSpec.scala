package nebflow.agent

import munit.FunSuite

/**
 * 缺陷⑥ 外部注入合批（2026-09-07 设计 §8）：turn 边界的注入队列消费从逐条
 * （drainHead = N 个 turn = N 次全上下文 LLM 往返，纯 token 浪费）改为整批
 * （drainAll = 同一下一轮请求携带全部排队注入）。纯函数级断言：全量、保序、
 * compaction 守卫；drainHead 原语义零回归（单条注入行为不变，验收 T11）。
 */
class InjectionBatchDrainSpec extends FunSuite:

  test("drainAll returns the whole queue in arrival order (T10)") {
    val q = List("n1", "n2", "n3")
    val (drained, remaining) = TurnBoundaryDrains.drainAll(q, compactionPending = false)
    assertEquals(drained, List("n1", "n2", "n3"))
    assertEquals(remaining, Nil)
  }

  test("drainAll on empty queue is a no-op") {
    val (drained, remaining) = TurnBoundaryDrains.drainAll(Nil, compactionPending = false)
    assertEquals(drained, Nil)
    assertEquals(remaining, Nil)
  }

  test("drainAll holds the queue while compaction is pending (2026-08-14 guard preserved)") {
    val q = List("n1", "n2")
    val (drained, remaining) = TurnBoundaryDrains.drainAll(q, compactionPending = true)
    assertEquals(drained, Nil, "nothing may be consumed mid-compaction — injected-then-summarized-away loss")
    assertEquals(remaining, List("n1", "n2"))
  }

  test("drainHead single-item semantics unchanged (T11 regression guard)") {
    val q = List("a", "b", "c")
    val (head, rest) = TurnBoundaryDrains.drainHead(q, compactionPending = false)
    assertEquals(head, Some("a"))
    assertEquals(rest, List("b", "c"))
    val (held, kept) = TurnBoundaryDrains.drainHead(q, compactionPending = true)
    assertEquals(held, None)
    assertEquals(kept, q)
  }
end InjectionBatchDrainSpec
