package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * mailack 批（2026-09-23 作者令「任务分发器就没停过，一直在收 Mail」）· **B/C 面判据**。
 *
 * 审计结论（`.nebflow` 外件 `20260923_184500_mail-behavior-audit.md`）：分发器→root 的
 * 回复是**逐件立即注入** ⇒ root 醒一轮、再发令又触发分发器 ⇒ **级联率实测 100%**
 * （root 40 封中 39 封在收执后 3 分钟内被再触发）、双向 20.9 封/小时、分发器回执
 * **73% 纯 ACK**。本批给**这条腿**加生产者侧打包窗（形态照 `NodeEngine.RootNotifyBatch`
 * 既有先例），并加背压可见化。
 *
 * 本文件钉三条（每条都机械可判、且能红）：
 *   1. **合并**：N 件入窗 ⇒ **一次**注入（N≥2 时正文分节、每件全文仍在）；
 *   2. **保序 + 不丢**：批内 FIFO、批间拼接序 == 入队顺序；溢出（超 `max`）留队下窗；
 *   3. **豁免 + 关窗**：`interrupt` 腿与 `windowMs <= 0` **不进窗**（= 旧行为可回滚）。
 *
 * 红侧（变异）：把 `flushDispatcherReplies` 的 `splitAt` 上限改成 1（等于永不合并）⇒
 * 面 1 的「一次注入」当场红；把 `isDispatcherMailInterrupt` 判据去掉 ⇒ 面 3 红。
 */
class MailDispatcherReplyBatchSpec extends FunSuite:

  private def drain(): IO[List[String]] =
    MailTool.dispatcherMailPendingCount.map(_ => Nil)

  override def beforeEach(context: munit.BeforeEach): Unit =
    // 每用例前清空缓冲（进程内 Ref 单点，跨 suite 会污染）
    MailTool.flushDispatcherReplies().unsafeRunSync()

  test("A · 空窗 flush 是零动作（不注入、不报错）"):
    MailTool.flushDispatcherReplies().unsafeRunSync()
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0)

  test("B · 入窗后件在缓冲中、窗口未闭合前不注入（pending 计数可见）"):
    val captured = scala.collection.mutable.ListBuffer.empty[String]
    val deliver: String => IO[Unit] = t => IO { captured += t; () }
    val r = MailToolTestAccess.enqueue("hello-1", "info", deliver).unsafeRunSync()
    assertEquals(r, None, "入窗 ⇒ None（调用方按「已受理、窗末投递」回报）")
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 1)
    assert(MailTool.dispatcherMailWindowArmed.unsafeRunSync())
    assertEquals(captured.toList, Nil, "窗未闭合 ⇒ 尚未注入")
    MailTool.flushDispatcherReplies().unsafeRunSync()
    assertEquals(captured.toList, List("hello-1"), "窗末 ⇒ 恰一次注入，正文逐字不变（N=1）")
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0)

  test("C · N 件合并为一次注入 + 每件全文仍在 + 保序"):
    val captured = scala.collection.mutable.ListBuffer.empty[String]
    val deliver: String => IO[Unit] = t => IO { captured += t; () }
    (1 to 3).foreach { i =>
      MailToolTestAccess.enqueue(s"body-$i", if i == 2 then "failed" else "info", deliver).unsafeRunSync()
    }
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 3)
    MailTool.flushDispatcherReplies().unsafeRunSync()
    assertEquals(captured.size, 1, "N 件 ⇒ 一次注入（本批核心读数）")
    val merged = captured.head
    assert(merged.contains("body-1") && merged.contains("body-2") && merged.contains("body-3"), "每件正文全文仍在（不折叠不丢）")
    assert(merged.indexOf("body-1") < merged.indexOf("body-2"), "批内保序")
    assert(merged.indexOf("body-2") < merged.indexOf("body-3"), "批内保序")

  test("D · 溢出留队下窗（不丢件）——上限 1 时逐窗各注入一件"):
    val captured = scala.collection.mutable.ListBuffer.empty[String]
    val deliver: String => IO[Unit] = t => IO { captured += t; () }
    (1 to 3).foreach(i => MailToolTestAccess.enqueue(s"x$i", "info", deliver).unsafeRunSync())
    MailToolTestAccess.flushWithMax(2).unsafeRunSync()
    assertEquals(captured.size, 1, "第一窗一次注入")
    assert(captured.head.contains("x1") && captured.head.contains("x2"))
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 1, "溢出件留队")
    MailTool.flushDispatcherReplies().unsafeRunSync()
    assertEquals(captured.size, 2, "第二窗取剩余件")
    assert(captured.last.contains("x3"), "溢出件未丢")
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0)

  test("E · P0 豁免：interrupt 腿不进缓冲（旁路 ⇒ Some，立即注入路径）"):
    val captured = scala.collection.mutable.ListBuffer.empty[String]
    val deliver: String => IO[Unit] = t => IO { captured += t; () }
    val r = MailToolTestAccess.enqueue("urgent", "interrupt", deliver).unsafeRunSync()
    assertEquals(r, Some("urgent"), "interrupt ⇒ 旁路（调用方走立即注入）")
    assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0, "不进缓冲")
    assertEquals(captured.toList, Nil, "本层不注入（由调用点立即路径负责）")

  test("F · 关窗（windowMs <= 0）⇒ 全部旁路，等同旧行为"):
    MailToolTestAccess.withWindowMs(0L) {
      val captured = scala.collection.mutable.ListBuffer.empty[String]
      val deliver: String => IO[Unit] = t => IO { captured += t; () }
      val r = MailToolTestAccess.enqueue("legacy", "info", deliver).unsafeRunSync()
      assertEquals(r, Some("legacy"), "关窗 ⇒ 旁路（回滚面）")
      assertEquals(MailTool.dispatcherMailPendingCount.unsafeRunSync(), 0)
    }

  test("G · 合并件 eventType 保守（强提醒优先 failed > blocked > 首件）"):
    val deliver: String => IO[Unit] = _ => IO.unit
    MailToolTestAccess.enqueue("a", "info", deliver).unsafeRunSync()
    MailToolTestAccess.enqueue("b", "failed", deliver).unsafeRunSync()
    assertEquals(MailToolTestAccess.mergedType(List("info", "failed")), "failed")
    assertEquals(MailToolTestAccess.mergedType(List("info", "blocked")), "blocked")
    assertEquals(MailToolTestAccess.mergedType(List("info", "info")), "info")
    MailTool.flushDispatcherReplies().unsafeRunSync()

/** 测试接缝：`private[tools]` 面直调（与 `MailQueueNebulaSpec` 等既有先例同款）。 */
object MailToolTestAccess:
  def enqueue(text: String, mailType: String, deliver: String => IO[Unit]): IO[Option[String]] =
    MailTool.enqueueDispatcherReplyForTest(text, mailType, deliver)

  def flushWithMax(n: Int): IO[Unit] =
    MailTool.flushDispatcherRepliesWithMaxForTest(n)

  def withWindowMs[A](ms: Long)(body: => A): A =
    val old = System.getProperty("nebflow.mail.dispatcherBatchMs")
    System.setProperty("nebflow.mail.dispatcherBatchMs", ms.toString)
    try body
    finally
      if old == null then System.clearProperty("nebflow.mail.dispatcherBatchMs")
      else System.setProperty("nebflow.mail.dispatcherBatchMs", old)

  def mergedType(types: List[String]): String =
    MailTool.mergedDispatcherReplyTypeForTest(types)
