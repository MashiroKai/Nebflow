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
 *
 * **2026-09-23 例外化（notifypack 解 b 批 · 作者裁定 A）**：裁定经作者收窄后新增**唯一**
 * 豁免 —— root 通道通知打包窗冲刷件（`ImmediateInput.windowItems.isDefined`，唯一写入点
 * = `NodeEngine.flushRootNotify` 的 `case many`）可在同一边界展开为 N 个虚拟件（N 气泡 /
 * 1 次唤醒）。**队列层不变**（仍「一次边界只消费队首一个元素」，本 spec 的 `drainHead`
 * 三用例一字未改）；本节新增断言把豁免**钉在结构谓词上**并给出负对照。
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

  test("no batch consumer remains: every drain surface is head-only EXCEPT the tagged window flush carrier (静态判据)") {
    // 🔴 2026-09-23 例外化后的禁令面（**除下述唯一豁免外全部在禁列**）：
    //   禁 ① `drainAll`（整队消费）—— 不得以任何名字复活；
    //   禁 ② `drainUserBatch`（可内联件整批）—— 同上；
    //   禁 ③ 任何在生产侧把 N 件**排队用户消息**合成一次 offer 的形态；
    //   禁 ④ 任何把 `windowItems` 写到 root 通知窗之外的写入点（唯一合法写入点 =
    //        `NodeEngine.flushRootNotify` 的 `case many`，见 `RootNotifyBatchSpec` 侧断言）；
    //   准 ⑤ **唯一豁免**：队首元素自带 `windowItems` ⇒ 其**载荷**在同一边界展开为
    //        N 个虚拟件（`TurnBoundaryDrains.expandWindowFlush`）；队列层仍为「一次边界
    //        只消费队首一个元素」。
    // 静态面：合批消费器（drainAll / drainUserBatch）已从 TurnBoundaryDrains 删除 ⇒
    // 全仓无任何引用（`grep -rn "drainAll\|drainUserBatch" src/` 只应命中说明文字与
    // AgentActor 的退役注释），因此任何一处若残留调用即编译失败。
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
    assertEquals(steps.map(_._2.size), List(3, 2, 1, 0), "每一步只能消费一件——队列层语义不受豁免影响")

    // ① 无标记件 ⇒ 展开恒等（1→1）：用户消息腿与全部非窗腿的保护面
    val plain = AgentCommand.ImmediateInput("plain-user-text", source = None, fromUser = true)
    assertEquals(
      TurnBoundaryDrains.expandWindowFlush(plain),
      List(plain),
      "未标记的件必须恒等展开——豁免不得外溢到用户消息腿"
    )

    // ② 标记载体 ⇒ 展开 N 件，序 = 载荷序（保序），且逐件自带身份/状态
    val carrier = AgentCommand.ImmediateInput(
      "merged-summary-text",
      source = Some("node"),
      eventType = Some("completed"),
      sender = Some("proj/first-node"),
      fromUser = false,
      windowItems = Some(
        List(
          AgentCommand.WindowItem("BODY_1", "node-1", "completed", "proj/node-1"),
          AgentCommand.WindowItem("BODY_2", "node-2", "failed", "proj/node-2")
        )
      )
    )
    val expanded = TurnBoundaryDrains.expandWindowFlush(carrier)
    assertEquals(expanded.size, 2, "标记载体必须展开为 N 件（气泡逐件）")
    assertEquals(expanded.map(_.text), List("BODY_1", "BODY_2"), "展开序 == 载荷到达序（保序）")
    assertEquals(
      expanded.map(_.eventType),
      List(Some("completed"), Some("failed")),
      "逐件状态不得被批次主状态覆盖（逐件辨识不被抹平）"
    )
    assertEquals(
      expanded.map(s => s.sender.exists(_.endsWith("/node-1"))),
      List(true, false),
      "逐件 sender 身份来自写入点（零字符串手术），按件区分"
    )
    assert(expanded.forall(_.windowItems.isEmpty), "展开必须幂等：虚拟件不得再带窗标记（防二次展开）")

    // ③ 队列层读数：载体在队列中恒占 1 个元素（豁免不改队列算术）
    val (taken, tail) =
      TurnBoundaryDrains.drainHeadExpanded(List[AgentCommand.ImmediateInput](carrier, plain), compactionPending = false)(
        TurnBoundaryDrains.expandWindowFlush
      )
    assertEquals(taken.size, 2, "载体展开出 2 个虚拟件（同一 turn 内注入）")
    assertEquals(tail, List(plain), "队列只移除 1 个元素——第二个元素必须留队")
  }
end InjectionBatchDrainSpec
