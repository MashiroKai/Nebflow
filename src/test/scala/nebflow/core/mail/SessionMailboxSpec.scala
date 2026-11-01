package nebflow.core.mail

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.shared.PathUtil

/**
 * 终态通知 actor 邮箱化批 P1 底座验收（设计 §6 五不变式 + kill -9 四窗矩阵）。
 *
 * 语义反转的承重断言：**磁盘邮箱是唯一真源**——每个用例都只通过盘面重读验证
 * 「写成功=必达」，不依赖任何内存态。全部纯桩驱动、零墙钟依赖（时间只作
 * enqueuedAt 记录值，不作判据）。
 */
class SessionMailboxSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-mailbox"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    if tmp != null && os.exists(tmp) then os.remove.all(tmp)

  private def evt(
    payload: String,
    eventType: String = "completed",
    source: String = "delegate",
    correlationId: Option[String] = None
  ): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = source,
      eventType = eventType,
      payload = payload,
      metadata = JsonObject.empty,
      correlationId = correlationId
    )

  private def enqueue(
    sid: String,
    identity: String,
    payload: String,
    coalesce: Boolean = false
  ): IO[SessionMailbox.MailResult] =
    SessionMailbox.mailOnce(sid, evt(payload), kind = "delegate-terminal", identity = identity, coalesce = coalesce)

  // ── I2 恰一次：幂等键防写重 ─────────────────────────────────────────────

  test("mailOnce is idempotent per identity (non-coalesce): second write is AlreadyPresent, disk keeps one entry") {
    for
      first <- enqueue("s1", "task-a", "done once")
      second <- enqueue("s1", "task-a", "done twice")
      pending <- SessionMailbox.scanPending("s1")
    yield
      assert(first.isInstanceOf[SessionMailbox.MailResult.Enqueued], clue(first))
      assertEquals(clue(second), SessionMailbox.MailResult.AlreadyPresent: SessionMailbox.MailResult)
      assertEquals(clue(pending).map(_.identity), List("task-a"), "one event, one copy")
      assertEquals(clue(pending).head.event.payload, "done once", "duplicate write must not overwrite payload")
  }

  // ── 风暴闸换轴面：coalesce 原位合条 ─────────────────────────────────────

  test("mailOnce with coalesce=true merges in place: seq unchanged, suppressedCount accumulates, nothing dropped") {
    for
      _ <- enqueue("s2", "esc-a", "notice v1", coalesce = true)
      merged2 <- enqueue("s2", "esc-a", "notice v2", coalesce = true)
      merged3 <- enqueue("s2", "esc-a", "notice v3", coalesce = true)
      pending <- SessionMailbox.scanPending("s2")
      depth <- SessionMailbox.depth("s2")
    yield
      val e2 = merged2.asInstanceOf[SessionMailbox.MailResult.Coalesced].entry
      val e3 = merged3.asInstanceOf[SessionMailbox.MailResult.Coalesced].entry
      assertEquals(clue(e2.seq), clue(e3.seq), "coalesce must not reorder the FIFO position")
      assertEquals(clue(pending).size, 1, "storm collapses to ONE entry")
      assertEquals(clue(depth), 1)
      val onDisk = pending.head
      assertEquals(clue(onDisk.suppressedCount), 2, "absorbed two same-cause notices")
      assert(
        clue(onDisk.event.payload).contains("2 same-cause notice(s) coalesced"),
        "release payload must carry the coalesce summary line"
      )
      assert(onDisk.event.payload.contains("notice v3"), "merged payload is the latest")
  }

  // ── I2 并发面：100 并发同 identity 零重 ────────────────────────────────

  test("100 concurrent mailOnce calls with the same identity land exactly ONE entry on disk") {
    val writes = (1 to 100).toList.map(i => enqueue("s3", "task-storm", s"attempt $i"))
    for
      results <- writes.parSequence
      pending <- SessionMailbox.scanPending("s3")
    yield
      val enqueued = results.collect { case e: SessionMailbox.MailResult.Enqueued => e }
      assertEquals(clue(enqueued.size), 1, "exactly one writer wins the seq allocation")
      assertEquals(clue(pending).size, 1)
      val others = results.count {
        case SessionMailbox.MailResult.Enqueued(_) | SessionMailbox.MailResult.Failed(_) => false
        case _ => true
      }
      assertEquals(clue(others), 99, "losers must be AlreadyPresent, never Failed")
  }

  // ── I4 顺序：FIFO = seq 名字序 ─────────────────────────────────────────

  test("100 sequential enqueues scan back in strict FIFO order (seq monotonic, ids stable)") {
    val ids = (1 to 100).map(i => s"task-$i%03d").toList
    for
      _ <- ids.traverse(id => enqueue("s4", id, s"payload of $id"))
      pending <- SessionMailbox.scanPending("s4")
    yield
      assertEquals(clue(pending).map(_.identity), ids, "FIFO order == enqueue order")
      val seqs = pending.map(_.seq)
      assertEquals(clue(seqs), seqs.sorted, "seq strictly monotonic")
  }

  // ── I4：坏形条目入 quarantine 不阻塞后件 ───────────────────────────────

  test("a malformed pending entry is quarantined and does NOT block the FIFO behind it") {
    for
      _ <- enqueue("s5", "task-1", "first")
      _ <- enqueue("s5", "task-2", "second")
      _ <- enqueue("s5", "task-3", "third")
      before <- SessionMailbox.scanPending("s5")
      _ <- IO.blocking {
        // 把 seq-2 的文件内容写坏（模拟半写/位翻转）——文件名（FIFO 位）不动。
        val bad = PathUtil.dataRoot / "sessions" / "s5" / "mailbox" / "pending" /
          f"${before(1).seq}%012d-task-2.json"
        os.write.over(bad, "{ not json")
      }
      pending <- SessionMailbox.scanPending("s5")
      depth <- SessionMailbox.depth("s5")
    yield
      assertEquals(clue(pending).map(_.identity), List("task-1", "task-3"), "bad entry skipped, rest intact")
      assertEquals(clue(depth), 2, "depth counts well-formed entries only")
      val q = PathUtil.dataRoot / "sessions" / "s5" / "mailbox" / "quarantine"
      assert(os.exists(q) && os.list(q).nonEmpty, "malformed file moved to quarantine, never dropped silently")
  }

  // ── kill -9 窗口1：入队半写（tmp 残片）─────────────────────────────────

  test("crash window 1 (half-written tmp): sweepTmpAll removes tmp fragments, pending scan ignores them") {
    for
      _ <- enqueue("s6", "task-a", "real entry")
      _ <- IO.blocking {
        val pend = PathUtil.dataRoot / "sessions" / "s6" / "mailbox" / "pending"
        os.write.over(pend / "000009-crash.json.tmp", "{ half-written", createFolders = true)
      }
      beforeSweep <- SessionMailbox.scanPending("s6")
      swept <- SessionMailbox.sweepTmpAll()
      afterSweep <- SessionMailbox.scanPending("s6")
    yield
      assertEquals(clue(beforeSweep).map(_.identity), List("task-a"), "tmp never counts as a visible entry")
      assertEquals(clue(swept), List("000009-crash.json.tmp"))
      assertEquals(clue(afterSweep).map(_.identity), List("task-a"))
  }

  // ── kill -9 窗口2：取走前崩（条目在 pending → 下个唤醒点照常扫出）───────

  test("crash window 2 (crash before take): entry sits in pending, next wake-up scans it out — zero loss") {
    for
      r <- enqueue("s7", "task-a", "survived")
      // 模拟「崩后重启」：全新读面 = 纯盘扫，无任何恢复动作。
      pending <- SessionMailbox.scanPending("s7")
    yield
      assert(r.isInstanceOf[SessionMailbox.MailResult.Enqueued])
      assertEquals(clue(pending).map(_.identity), List("task-a"))
  }

  // ── kill -9 窗口3/4：取走后注入前崩 → taken 重注（≤1 重复零丢失）────────

  test("crash window 3/4 (taken before unlink): scanTaken replays the SAME identity, unlink clears + tombstones") {
    for
      _ <- enqueue("s8", "task-a", "payload survives crash")
      pend <- SessionMailbox.scanPending("s8")
      taken <- SessionMailbox.take("s8", pend)
      // 取走后「崩」：重读面 = scanTaken（恢复点 W1/W4）。
      replay1 <- SessionMailbox.scanTaken("s8")
      replay2 <- SessionMailbox.scanTaken("s8") // 幂等重注：条目留在 taken 直到 unlink
      replayedAgain <- SessionMailbox.scanTaken("s8") // 第三拍仍可重注（重复上限 = 重注次数，零丢失）
      _ <- SessionMailbox.unlinkTaken("s8", List("task-a"))
      after <- SessionMailbox.scanTaken("s8")
      tomb <- SessionMailbox.deliveredContains("s8", Set("task-a"))
    yield
      assertEquals(clue(taken).map(_.identity), List("task-a"))
      assertEquals(clue(replay1).map(_.identity), List("task-a"))
      assertEquals(clue(replay2).map(_.identity), List("task-a"), "idempotent replay face")
      assertEquals(clue(replayedAgain.head.event.payload), "payload survives crash", "payload integrity across crash")
      assertEquals(clue(after), Nil, "unlink = delivered (physical fact)")
      assertEquals(clue(tomb), Set("task-a"), "consumption tombstone guards boot reconcile")
  }

  // ── 幂等键防写重不防读重：消费后同 identity 再投 = 合法新条目 ────────────

  test("after consumption the same identity may be enqueued again as a NEW entry (guard is write-once, not dedup)") {
    for
      _ <- enqueue("s9", "task-a", "round one")
      pend1 <- SessionMailbox.scanPending("s9")
      _ <- SessionMailbox.take("s9", pend1)
      _ <- SessionMailbox.unlinkTaken("s9", List("task-a"))
      again <- enqueue("s9", "task-a", "round two")
      pend2 <- SessionMailbox.scanPending("s9")
    yield
      assert(again.isInstanceOf[SessionMailbox.MailResult.Enqueued], clue(again))
      assertEquals(clue(pend2).map(_.identity), List("task-a"))
      assertEquals(clue(pend2.head.event.payload), "round two", "re-delivery after consumption is a legal fresh entry")
    // seq 语义注（诚实账）：占序防重用只在 taken 文件尚存期间成立（unlink 把
    // taken 清空后，空箱的 seq 从 1 重新起算）。FIFO 不变式 I4 的保证域 = 共存
    // 条目之间；整箱消费完毕后的 seq 重用不构成乱序（箱内不存在更老的未消费件）。
  }

  // ── 消费墓碑 cap（1024 drop-oldest，开放决策⑤零墙钟的伴生边界）──────────

  test("delivered tombstone caps at 1024 entries drop-oldest; the newest id always survives") {
    val sid = "s10"
    // 有序构造（drop-oldest 的「最老」= 列表头部——乱序构造会让断言自身不确定）。
    val old = (1 to 1024).map(i => f"old-$i%04d").toList
    val dir = PathUtil.dataRoot / "sessions" / sid / "mailbox"
    val f = dir / "delivered.json"
    for
      _ <- IO.blocking {
        os.makeDir.all(dir)
        os.write.over(f, SessionMailbox.Delivered(1, old).asJson.noSpaces)
      }
      _ <- SessionMailbox.unlinkTaken(sid, List("new-0001"))
      tomb <- SessionMailbox.deliveredContains(sid, Set("new-0001", "old-0001", "old-1024"))
      kept <- IO.blocking(io.circe.parser.decode[SessionMailbox.Delivered](os.read(f)).map(_.ids.size).getOrElse(-1))
      dump <- IO.blocking(if os.exists(f) then os.read(f).take(160) else "<missing>")
    yield
      assertEquals(clue(tomb), Set("new-0001", "old-1024"), s"tombstone file head: $dump")
      assertEquals(clue(kept), 1024, s"cap enforced; file head: $dump")
  }

  // ── I5/徽标：depth = pending 可见深度 ──────────────────────────────────

  test("depth counts pending entries only: enqueue 3 → take 1 → depth 2; unlink does not change pending depth") {
    for
      _ <- enqueue("s11", "a", "1") *> enqueue("s11", "b", "2") *> enqueue("s11", "c", "3")
      d1 <- SessionMailbox.depth("s11")
      pend <- SessionMailbox.scanPending("s11")
      _ <- SessionMailbox.take("s11", pend.take(1))
      d2 <- SessionMailbox.depth("s11")
      _ <- SessionMailbox.unlinkTaken("s11", List("a"))
      d3 <- SessionMailbox.depth("s11")
    yield
      assertEquals(clue(d1), 3)
      assertEquals(clue(d2), 2)
      assertEquals(clue(d3), 2, "unlink clears taken/, pending depth untouched")
  }

  // ── 显式失败面：空收件方 / 空 identity = Failed（绝不静默）──────────────

  test("mailOnce refuses blank recipient or identity with Failed — never a silent write") {
    for
      r1 <- enqueue("", "task-a", "x")
      r2 <- enqueue("s12", "", "x")
    yield
      assertEquals(clue(r1), SessionMailbox.MailResult.Failed("recipient session id is empty"): SessionMailbox.MailResult)
      assertEquals(clue(r2), SessionMailbox.MailResult.Failed("entry identity is empty"): SessionMailbox.MailResult)
  }
end SessionMailboxSpec
