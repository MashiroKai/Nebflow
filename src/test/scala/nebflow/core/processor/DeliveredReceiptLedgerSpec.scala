package nebflow.core.processor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.actor.AgentCommand
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * #886 完成回执重启去重批（2026-10-26）——持久投递标记台账
 * （[[DeliveredReceiptLedger]]）的规格面。
 *
 * 实证背景（10-25 23:15 重启）：canvas ×3 / console-card ×2 / imagegen ×3 的
 * [delegate-finish] 载荷重复到达根会话——投递判定此前只有内存队列 + ack 两腿，
 * 重启后恢复重放闸（RecoverPersistedQueues）把已投件当「未投」重放，叠加
 * DelegateDeliveryReceipts sweep 的 10min 节拍重投。
 *
 * 断言面（作者令修法要求 4 的回归 spec 三态）：
 *  - R1 **已投 → 模拟恢复 → 不重放**：标记在案的 id，恢复闸（filterUndelivered）
 *    滤除——恢复会话不再见到该回执；
 *  - R2 **未投 → 恢复 → 恰好投一次**：未标记件首闸放行（注入），注入即标记，
 *    第二次恢复闸滤除——跨两次重启恰好一次；
 *  - R3 **崩溃窗口不丢不重**：fail-open 契约——标记面读失败 = 全量放行
 *    （簿记面故障绝不造成丢件）；无 correlationId 的事件恒放行恒不入账
 *    （载荷摘要不具身份唯一性，误杀即语义回退）；
 *  - P 持久往返：mark → load 跨「重启」（新 load 调用）保真；幂等；容量
 *    drop-oldest；坏文件 = 空集放行。
 */
class DeliveredReceiptLedgerSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def withDataRoot(name: String)(body: String => Unit): Unit =
    val tmp = Files.createTempDirectory(s"delivered-$name").toAbsolutePath.toString
    PathUtil.setDataRoot(os.Path(tmp))
    try body(s"session-$name")
    finally PathUtil.setDataRoot(originalRoot)

  private def event(correlationId: Option[String], payload: String = "p"): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent("delegate", "completed", payload, correlationId = correlationId)

  // ── R1 已投 → 模拟恢复 → 不重放 ──────────────────────────────────────────

  test("R1 已投递回执：标记在案 ⇒ 恢复闸滤除（重启后不重放）"):
    withDataRoot("r1-replayed-not") { sid =>
      val delivered = event(Some("delegate-nebflow-bd39ddf7"))
      // 首次投递：注入即标记
      DeliveredReceiptLedger.markDelivered(sid, delivered.correlationId.toList).unsafeRunSync()
      // 模拟重启恢复：恢复闸对同一事件（含于队列文件重放集）判定
      val (kept, blocked) = DeliveredReceiptLedger.filterUndelivered(sid, List(delivered)).unsafeRunSync()
      assertEquals(kept, Nil, "delivered receipt must NOT replay into pendingEvents on recovery")
      assertEquals(blocked.map(_.correlationId), List(Some("delegate-nebflow-bd39ddf7")))
    }

  test("R1b 混合队列：已投件滤除、未投件放行（一次恢复一个判定）"):
    withDataRoot("r1b-mixed") { sid =>
      val old = event(Some("delegate-nebflow-old"))
      val fresh = event(Some("delegate-nebflow-fresh"))
      DeliveredReceiptLedger.markDelivered(sid, List("delegate-nebflow-old")).unsafeRunSync()
      val (kept, blocked) = DeliveredReceiptLedger.filterUndelivered(sid, List(old, fresh)).unsafeRunSync()
      assertEquals(kept.map(_.correlationId), List(Some("delegate-nebflow-fresh")))
      assertEquals(blocked.map(_.correlationId), List(Some("delegate-nebflow-old")))
    }

  // ── R2 未投 → 恢复 → 恰好投一次 ──────────────────────────────────────────

  test("R2 未投递回执：首次恢复放行（注入），注入即标记，第二次恢复滤除——跨重启恰好一次"):
    withDataRoot("r2-exactly-once") { sid =>
      val e = event(Some("delegate-nebflow-f817af85"))
      // 第一次重启恢复：未标记 ⇒ 放行（交给投递腿注入）
      val (kept1, blocked1) = DeliveredReceiptLedger.filterUndelivered(sid, List(e)).unsafeRunSync()
      assertEquals(kept1, List(e), "undelivered receipt must pass the gate exactly once")
      assertEquals(blocked1, Nil)
      // 注入即标记（投递腿的标记点）
      DeliveredReceiptLedger.markDelivered(sid, e.correlationId.toList).unsafeRunSync()
      // 第二次重启恢复（模拟再次崩溃/重启）：不再重放
      val (kept2, blocked2) = DeliveredReceiptLedger.filterUndelivered(sid, List(e)).unsafeRunSync()
      assertEquals(kept2, Nil, "second recovery must not replay")
      assertEquals(blocked2, List(e))
    }

  // ── R3 崩溃窗口契约：fail-open + 无 correlationId 恒放行 ─────────────────

  test("R3a 无 correlationId 的事件恒放行且不入账（载荷摘要不具身份唯一性——防误杀）"):
    withDataRoot("r3a-no-corr-pass") { sid =>
      val a = event(None, "identical notification body")
      val b = event(None, "identical notification body") // 同文异件（合法形态）
      // 标记 a 的「摘要身份」不存在——无 id 件 markDelivered 是 no-op
      DeliveredReceiptLedger.markDelivered(sid, a.correlationId.toList).unsafeRunSync()
      assertEquals(DeliveredReceiptLedger.load(sid).unsafeRunSync(), Set.empty)
      // 两者都放行：同文多次通知绝不被误杀（语义零回退）
      val (kept, blocked) = DeliveredReceiptLedger.filterUndelivered(sid, List(a, b)).unsafeRunSync()
      assertEquals(kept.map(_.payload), List("identical notification body", "identical notification body"))
      assertEquals(blocked, Nil)
    }

  test("R3b 台账文件损坏 = 空集放行（fail-open：簿记面故障绝不丢件）"):
    withDataRoot("r3b-corrupt") { sid =>
      val f = PathUtil.dataRoot / "sessions" / sid / "delivered-receipts.json"
      os.makeDir.all(f / os.up)
      os.write.over(f, "{not json")
      assertEquals(DeliveredReceiptLedger.load(sid).unsafeRunSync(), Set.empty)
      val (kept, blocked) = DeliveredReceiptLedger.filterUndelivered(sid, List(event(Some("x-1")))).unsafeRunSync()
      assertEquals(kept.map(_.correlationId), List(Some("x-1")), "corrupt ledger must fail OPEN")
      assertEquals(blocked, Nil)
    }

  // ── P 持久往返 / 幂等 / 容量 ─────────────────────────────────────────────

  test("P1 mark → load 跨调用（模拟重启）保真；重复标记幂等"):
    withDataRoot("p1-roundtrip") { sid =>
      DeliveredReceiptLedger.markDelivered(sid, List("id-a", "id-b")).unsafeRunSync()
      DeliveredReceiptLedger.markDelivered(sid, List("id-b", "id-c")).unsafeRunSync()
      assertEquals(DeliveredReceiptLedger.load(sid).unsafeRunSync(), Set("id-a", "id-b", "id-c"))
    }

  test("P2 容量封顶 drop-oldest（标记集不无限增长）"):
    withDataRoot("p2-cap") { sid =>
      val ids = (0 until DeliveredReceiptLedger.MaxEntries + 50).map(i => s"id-$i").toList
      DeliveredReceiptLedger.markDelivered(sid, ids).unsafeRunSync()
      val loaded = DeliveredReceiptLedger.load(sid).unsafeRunSync()
      assertEquals(loaded.size, DeliveredReceiptLedger.MaxEntries)
      assert(!loaded.contains("id-0"), "oldest entries drop first")
      assert(loaded.contains(s"id-${DeliveredReceiptLedger.MaxEntries + 49}"), "newest entries retained")
    }

  test("P3 partitionDelivered 纯函数：无 id 恒放行、已 id 滤除、未 id 放行"):
    val delivered = Set("d-1")
    val noId = event(None)
    val marked = event(Some("d-1"))
    val unmarked = event(Some("d-2"))
    val (kept, blocked) = DeliveredReceiptLedger.partitionDelivered(delivered, List(noId, marked, unmarked))
    assertEquals(kept, List(noId, unmarked))
    assertEquals(blocked, List(marked))

end DeliveredReceiptLedgerSpec
