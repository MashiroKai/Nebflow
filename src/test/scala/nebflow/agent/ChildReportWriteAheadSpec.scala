package nebflow.agent

import munit.FunSuite
import java.nio.file.Files
import nebflow.core.processor.DeliveredReceiptLedger

/**
 * Behaviour-level spec for the child progress IDENTITY (report 块可见性批，作者令
 * 2026-11-20：「四条行为级红测改前红留档」).
 *
 * 🔴 真持久面：每一步都用 [[ChildReportJournal]] 真写盘（临时目录）+ 真
 * `DeliveredReceiptLedger` 判重读法——不打桩、不 mock，判据是「用户会不会看到
 * 两条同正文进度」这件事本身。
 *
 * 四条判据（改前全红：身份取自 turn id 时同正文重派产出两条，父端无法折叠）：
 *   ① 重派保身份：同一逻辑槽重跑 ⇒ 同一 correlation id，父端折叠成一件；
 *   ② 崩溃恢复重投：崩溃后从账本重开 ⇒ 仍取原 id，重投件被折叠；
 *   ③ 同正文两消息不同 id：**不同**逻辑槽即使正文逐字相同也必须是不同身份
 *      （否则父端会误折叠掉一条真实进度）；
 *   ④ 投递中途崩溃先冲账：已 prepare 未 markFlushed 的件在恢复扫描里先被重投，
 *      再允许新槽分配（顺序钉死，见 [[ChildReportRecoveryProtocolSpec]]）。
 */
class ChildReportWriteAheadSpec extends FunSuite:

  private def journal(tag: String): (java.nio.file.Path, ChildReportJournal) =
    val dir = Files.createTempDirectory(s"report-wal-$tag-")
    (dir, ChildReportJournal.open(dir, "child"))

  test("RED-1 redispatch keeps the persisted identity so the parent folds the duplicate") {
    val (dir, a) = journal("redispatch")
    // 同一逻辑槽重跑两次（= 崩溃/重派后同一 transcript 位置再发一条进度）。
    val slot = a.beginRequest()
    val first = a.prepareReport(slot, "progress")
    // 重派腿：同一逻辑槽 ⇒ 账本里同一个 id（write-ahead 预留，不重新分配）。
    val retry = ChildReportJournal.open(dir, "child").prepareReport(slot, "progress")
    assertEquals(first, retry, "重派必须复用已持久预留的身份")

    val firstEvent = ChildReport.event(first, "progress", "child")
    val redispatched = ChildReport.event(retry, "progress", "child")
    assertEquals(firstEvent.correlationId, redispatched.correlationId,
      "同一逻辑槽的重派件必须同 correlation id")
    assert(DeliveredReceiptLedger.isDuplicateIdentity(Set.empty, List(firstEvent), redispatched),
      "父端必须能把重派件识别为重复（否则用户看到两条同正文进度）")
  }

  test("RED-2 crash recovery redelivers with the ORIGINAL identity and folds") {
    val (dir, before) = journal("crash")
    val slot = before.beginRequest()
    val originalId = before.prepareReport(slot, "progress before crash")
    // 崩溃：进程内状态全丢，账本子在盘上。
    val recovered = ChildReportJournal.open(dir, "child")
    assertEquals(recovered.pendingRecovery(), List((originalId, "progress before crash")))

    val recoveryEvent = ChildReport.event(recovered.pendingRecovery().head._1, "progress before crash", "child")
    assertEquals(recoveryEvent.correlationId, Some(originalId), "恢复重投必须沿用原身份")
    val liveEvent = ChildReport.event(originalId, "progress before crash", "child")
    assert(DeliveredReceiptLedger.isDuplicateIdentity(Set.empty, List(liveEvent), recoveryEvent),
      "崩溃前已投过的件在恢复重投时必须被父端折叠")

    // 恢复协议：先冲账（一次性 markFlushed）再允许新槽。
    recovered.markFlushed(slot)
    assertEquals(recovered.pendingRecovery(), Nil)
    assertNotEquals(recovered.beginRequest(), slot)
  }

  test("RED-3 two messages with identical bodies get distinct identities") {
    val (_, a) = journal("distinct")
    val s1 = a.beginRequest()
    val id1 = a.prepareReport(s1, "same body")
    a.markFlushed(s1)
    val s2 = a.beginRequest()
    val id2 = a.prepareReport(s2, "same body")
    assertNotEquals(id1, id2, "两条独立消息即使正文逐字相同也必须是不同身份")

    val e1 = ChildReport.event(id1, "same body", "child")
    val e2 = ChildReport.event(id2, "same body", "child")
    assertNotEquals(e1.correlationId, e2.correlationId)
    assert(!DeliveredReceiptLedger.isDuplicateIdentity(Set.empty, List(e1), e2),
      "不同消息绝不能被判重折叠掉")
  }

  test("RED-4 crash mid-delivery flushes the prepared report before any new request") {
    val (dir, a) = journal("midflight")
    val slot = a.beginRequest()
    val prepared = a.prepareReport(slot, "in flight")
    // 进程在「已 prepare、未 markFlushed」处死掉。
    val recovered = ChildReportJournal.open(dir, "child")
    // 恢复面：已持久内容可见（先冲账），开放槽先关闭（无响应槽不得阻塞新进度）。
    val pending = recovered.pendingRecovery()
    assertEquals(pending.map(_._1), List(prepared), "先冲账：已持久内容必须先重投")
    recovered.openRequests().foreach(recovered.closeNoReport)
    // 冲账完成后才分配新槽，且新槽不得复用旧槽位。
    recovered.markFlushed(slot)
    val next = recovered.beginRequest()
    assertNotEquals(next, slot)
    assertEquals(recovered.pendingRecovery(), Nil)
  }
