package nebflow.agent

import munit.FunSuite

/**
 * 用户消息队列 burst 缺陷批（2026-09-15，root 裁定）：**排队消息按序逐条注入、每条
 * 独立成 turn，保序、禁合并语义、禁全量 burst；错误恢复路径与正常路径同规。**
 *
 * 本 spec 原为「缺陷⑥ 外部注入合批（2026-09-07 设计 §8）」的断言面（drainAll = 同一下
 * 一轮请求携带全部排队注入）。合批已删除 ⇒ 断言面按新裁定**反向重写**：边界只消费队首
 * 一件、其余留队、到达顺序不变、compaction 守卫不变（注入后被摘要替换即丢失）。
 * 属「判据随裁定更新」，非弱化：断言条数与强度均未降（逐条 + 保序 + 守卫三类）。
 */
class InjectionBatchDrainSpec extends FunSuite:

  test("drainHead consumes exactly the head, tail stays queued in arrival order (T10′)") {
    val q = List("n1", "n2", "n3")
    val (head, remaining) = TurnBoundaryDrains.drainHead(q, compactionPending = false)
    assertEquals(head, Some("n1"))
    assertEquals(remaining, List("n2", "n3"), "余件必须留队——一次边界只注入一件")
    // 连发多件逐次消费：注入顺序 = 到达顺序（保序）
    val (h2, r2) = TurnBoundaryDrains.drainHead(remaining, compactionPending = false)
    assertEquals(h2, Some("n2"))
    assertEquals(r2, List("n3"))
  }

  test("drainHead on empty queue is a no-op") {
    val (head, remaining) = TurnBoundaryDrains.drainHead(Nil, compactionPending = false)
    assertEquals(head, None)
    assertEquals(remaining, Nil)
  }

  test("drainHead holds the queue while compaction is pending (2026-08-14 guard preserved)") {
    val q = List("n1", "n2")
    val (head, remaining) = TurnBoundaryDrains.drainHead(q, compactionPending = true)
    assertEquals(head, None, "nothing may be consumed mid-compaction — injected-then-summarized-away loss")
    assertEquals(remaining, List("n1", "n2"))
  }

  test("no batch consumer remains: every drain surface is head-only (静态判据)") {
    // 合批消费器（drainAll / drainUserBatch）已从 TurnBoundaryDrains 删除：本批的静态
    // 断言面 = 全仓无任何引用（`grep -rn "drainAll\|drainUserBatch" src/` 只应命中本
    // spec 的说明文字与 AgentActor 的退役注释），因此任何一处若残留调用即编译失败。
    // 运行期行为面另见 AgentActorCompactionSpec 的「一件留队、余件不注入」断言。
    val q = List("a", "b", "c", "d")
    // 逐条注入序列 = 到达顺序，且每一步都有余件（禁全量 burst 的可执行判据）
    val steps: List[(Option[String], List[String])] =
      Iterator
        .unfold(q)(qs =>
          if qs.isEmpty then None
          else
            val drained = TurnBoundaryDrains.drainHead(qs, compactionPending = false)
            Some((drained, drained._2))
        )
        .toList
    assertEquals(steps.map(_._1), List(Some("a"), Some("b"), Some("c"), Some("d")))
    assertEquals(steps.map(_._2.size), List(3, 2, 1, 0), "每一步只能消费一件")
  }
end InjectionBatchDrainSpec
