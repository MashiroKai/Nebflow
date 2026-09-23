package nebflow.core.project

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * 节点申报**跨宿主重启持久化**判据（#239② 面①，2026-09-15）。
 *
 * 本 spec 钉死的是**新持久化面本身**的五条语义（每条的失败方向都必须是保守方向）：
 *  1. **声明落盘 + 重启恢复**：register 同步落 `<ws>/.nebflow/node-reports.jsonl`；
 *     进程内「重启」仿真（清空工作集与加载记账）之后，同 sessionId 的申报**仍在**
 *     （peek 命中 + drain 取回）——旧口径在此处必为 None（申报结构性消失，红臂读数
 *     见 `.nebflow/evidence/20260915_v239b/`）；
 *  2. **消费落盘（不复活）**：drain 之后重启，申报**不得**复活成待消费；
 *  3. **缺失状态不是「已处理」**（fail-closed 方向①）：无日志 ⇒ `Absent`，且 drain
 *     仍返回 None —— 缺失只表示「没有可恢复的申报」，**绝不**表示「已被消费」；
 *  4. **损坏 / 半写**（fail-closed 方向②）：可读的完整行照常恢复、未解析部分**原样
 *     留在盘上（字节零漂移）**、判词降级为 `Degraded` 且事件可见——禁静默降级成
 *     「无待处理」；
 *  5. **写失败可见**（硬要求②）：append 失败 ⇒ WARN（稳定 token）+ `node-report-store`
 *     `kind=write-failed` 事件（可 grep），**且内存语义零变化**（申报照常可 drain）；
 *  6. **跨重启申报不得无声消失**：被清理路径（remove）丢弃的**恢复项**把全文补偿写回
 *     `node-report-unconsumed`（`kind=recovered-discard`）。
 *
 * 与既有 [[NodeReportRegistrySpec]] 的分工：那个 spec 钉**进程内**语义（take-and-remove /
 * last-write-wins / remove 幂等 / 会话隔离），**两者断言不重叠**。
 */
class NodeReportJournalSpec extends CatsEffectSuite:

  private val root: os.Path = os.pwd / "target" / "test-node-report-journal"

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def freshWs(name: String): String =
    val p = root / name
    if os.exists(p) then os.remove.all(p)
    os.makeDir.all(p)
    p.toString

  private def journal(ws: String): os.Path = NodeReportRegistry.journalPath(ws)

  private def events(ws: String): String =
    val p = os.Path(ws) / ".nebflow" / FlowMapEventLog.FileName
    if os.exists(p) then os.read(p) else ""

  private val fbA = BlockedFeedback("upstream-incomplete", "上游 X 未完成（marker-J-A）", "需上游先完成")

  test("① 声明落盘 + 重启恢复：日志有 declare 行，重启仿真后 peek/drain 仍取回申报") {
    val ws = freshWs("recover")
    for
      _ <- NodeReportRegistry.register(ws, "journal-spec", "n-rec", "sid-rec", fbA)
      onDisk <- IO(os.read(journal(ws)))
      peeked <- NodeReportRegistry.peek("sid-rec")
      // 进程内「重启」：工作集与加载记账清空 ⇒ 下一次读必须从磁盘日志重新水合。
      // 生产侧的「挂载后加载」由 [[NodeReportRegistry.loadMounted]] 按**已挂载项目**枚举
      // 执行（boot 链先挂载、后有节点会话）；本 spec 无挂载面，故显式走同一条加载路径
      // （`loadOutcome` = 同一个 `ensureLoaded` 单点）建模「重启后挂载」。
      _ <- NodeReportRegistry.resetForRestartSimulation()
      _ <- NodeReportRegistry.loadOutcome(ws)
      peekAfter <- NodeReportRegistry.peek("sid-rec")
      drainedAfter <- NodeReportRegistry.drain("sid-rec")
      outcome <- NodeReportRegistry.loadOutcome(ws)
      ev <- IO(events(ws))
    yield
      assert(onDisk.contains("\"op\":\"declare\""), s"journal must hold a declare line, got: $onDisk")
      assert(
        onDisk.contains("sid-rec") && onDisk.contains("marker-J-A"),
        s"journal line must carry session + the declaration text verbatim, got: $onDisk"
      )
      assertEquals(peeked, Some(fbA), "declare must be visible in-memory right away")
      assertEquals(peekAfter, Some(fbA), "RESTART: the declaration must be recovered from the journal (old code: None)")
      assertEquals(drainedAfter, Some(fbA), "post-restart drain must return the recovered declaration")
      assert(outcome != NodeReportRegistry.LoadOutcome.Absent, s"journal exists, got $outcome")
      assert(
        ev.contains(NodeReportRegistry.StoreEventType) && ev.contains("kind=recovered"),
        s"recovery must leave a greppable audit line, got: $ev"
      )
    end for
  }

  test("② 消费落盘：drain 后重启，申报不得复活（consume 行 + 重放后 None）") {
    val ws = freshWs("consume")
    for
      _ <- NodeReportRegistry.register(ws, "journal-spec", "n-c", "sid-c", fbA)
      first <- NodeReportRegistry.drain("sid-c")
      onDisk <- IO(os.read(journal(ws)))
      _ <- NodeReportRegistry.resetForRestartSimulation()
      _ <- NodeReportRegistry.loadOutcome(ws) // 重启后挂载加载（生产 = loadMounted 同一路径）
      revived <- NodeReportRegistry.peek("sid-c")
      second <- NodeReportRegistry.drain("sid-c")
    yield
      assertEquals(first, Some(fbA), "first drain consumes the declaration")
      assert(onDisk.contains("\"op\":\"consume\""), s"drain must append a consume line, got: $onDisk")
      assertEquals(revived, None, "a consumed declaration must NOT be resurrected by a restart")
      assertEquals(second, None, "idempotent: nothing left to drain after the restart")
  }

  test("③ fail-closed：无日志 ⇒ Absent，且 drain=None（缺失状态 ≠ 已处理）") {
    val ws = freshWs("absent")
    for
      _ <- NodeReportRegistry.resetForRestartSimulation()
      outcome <- NodeReportRegistry.loadOutcome(ws)
      // 关键方向断言：Absent 不得被读成「申报已被消费」；同时也不得凭空造出一条申报
      peeked <- NodeReportRegistry.peek("sid-never")
      drained <- NodeReportRegistry.drain("sid-never")
      noFile <- IO(!os.exists(journal(ws)))
    yield
      assertEquals(outcome, NodeReportRegistry.LoadOutcome.Absent)
      assert(noFile, "no journal must exist for this case")
      assertEquals(peeked, None, "missing state must not fabricate a declaration")
      assertEquals(drained, None, "missing state must never be reported as already-consumed")
  }

  test("④ fail-closed：损坏行/撕裂尾行 ⇒ Degraded + 可读部分照常恢复 + 盘上字节零漂移") {
    val ws = freshWs("corrupt")
    val p = journal(ws)
    val good =
      """{"op":"declare","ts":1,"project":"journal-spec","node":"n-k","session":"sid-keep","category":"blocked","detail":"marker-J-KEEP","suggestion":""}"""
    val before =
      s"$good\nnot-json-at-all\n{\"op\":\"future-op\",\"session\":\"sid-x\"}\n{\"op\":\"declare\",\"ts\":2,\"se" // 撕裂尾行
    os.write.over(p, before, createFolders = true)
    for
      _ <- NodeReportRegistry.resetForRestartSimulation()
      outcome <- NodeReportRegistry.loadOutcome(ws)
      kept <- NodeReportRegistry.peek("sid-keep")
      after <- IO(os.read(p))
      ev <- IO(events(ws))
    yield
      outcome match
        case NodeReportRegistry.LoadOutcome.Degraded(reason) =>
          assert(
            reason.contains("torn") || reason.contains("unparseable"),
            s"degraded reason must name the damage, got: $reason"
          )
        case other => fail(s"corrupt/torn journal MUST degrade (conservative), got $other")
      assertEquals(
        kept,
        Some(BlockedFeedback("blocked", "marker-J-KEEP", "")),
        "the readable declare line must still be recovered"
      )
      assertEquals(after, before, "the journal must be left byte-for-byte untouched (never truncated/repaired)")
      assert(
        ev.contains("kind=degraded") && ev.contains("path="),
        s"a degraded load must be visible as an event, got: $ev"
      )
    end for
  }

  test("⑤ 写失败可见：append 失败 ⇒ 事件 kind=write-failed（可 grep），内存语义零变化") {
    val ws = freshWs("writefail")
    // 注入写失败：日志路径被一个**目录**占住（append 必失败；读也失败 ⇒ 同时覆盖 degraded 面）
    os.makeDir.all(journal(ws))
    for
      _ <- NodeReportRegistry.resetForRestartSimulation()
      _ <- NodeReportRegistry.register(ws, "journal-spec", "n-w", "sid-w", fbA)
      drained <- NodeReportRegistry.drain("sid-w")
      ev <- IO(events(ws))
      isDir <- IO(os.isDir(journal(ws)))
    yield
      assert(isDir, "the failure injection must still be in place")
      assert(
        ev.contains(NodeReportRegistry.StoreEventType) && ev.contains("kind=write-failed"),
        s"a failed journal append MUST be visible by design, got: $ev"
      )
      assert(ev.contains("IN-MEMORY ONLY"), s"the failure line must state the consequence (in-memory only), got: $ev")
      assertEquals(
        drained,
        Some(fbA),
        "a failed append must not change in-memory semantics (the declaration is still consumable)"
      )
      assert(
        NodeReportRegistry.WriteFailedLogToken.nonEmpty,
        "the WARN token must exist as the second visible path (grep judge)"
      )
    end for
  }

  test("⑥ 跨重启申报不得无声消失：remove 丢弃恢复项 ⇒ node-report-unconsumed（kind=recovered-discard）") {
    val ws = freshWs("discard")
    for
      _ <- NodeReportRegistry.register(ws, "journal-spec", "n-d", "sid-d", fbA)
      _ <- NodeReportRegistry.resetForRestartSimulation()
      _ <- NodeReportRegistry.loadOutcome(ws) // 重启后挂载加载
      recovered <- NodeReportRegistry.peek("sid-d")
      _ <- NodeReportRegistry.remove("sid-d") // 清理路径（cancelled/failed 出口）丢弃它
      ev <- IO(events(ws))
      gone <- NodeReportRegistry.drain("sid-d")
    yield
      assertEquals(recovered, Some(fbA), "precondition: the declaration must be recovered first")
      assertEquals(gone, None, "remove drops it")
      assert(
        ev.contains(NodeEngine.ReportUnconsumedEventType) && ev.contains("kind=recovered-discard"),
        s"a recovered declaration dropped without consumption MUST leave a compensation line, got: $ev"
      )
      assert(ev.contains("marker-J-A"), s"the compensation line must carry the declaration text verbatim, got: $ev")
    end for
  }

  test("⑦ 进程内残留（非恢复项）的 remove 保持既有行为：零补偿行（零回归）") {
    val ws = freshWs("inproc")
    for
      _ <- NodeReportRegistry.register(ws, "journal-spec", "n-i", "sid-i", fbA)
      _ <- NodeReportRegistry.remove("sid-i") // 未跨重启 ⇒ 既有对称清理语义，不产生补偿噪音
      ev <- IO(events(ws))
    yield assert(
      !ev.contains("kind=recovered-discard"),
      s"in-process cleanup must NOT emit the cross-restart compensation line, got: $ev"
    )
  }
end NodeReportJournalSpec
