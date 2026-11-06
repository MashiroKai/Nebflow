package nebflow.core.mail

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.shared.PathUtil

/**
 * P3 磁盘邮箱批 · 节点 2 验收 —— 作者 2026-11-05 10:16 裁定布局 A 后钉的三条不变量，
 * 逐条对应一组断言（不许只写在注释里）：
 *
 *   - **I-ISO 物理隔离**：输入条目不得进入 event 面（`SessionMailbox.scanPending/scanTaken`）
 *     的可见范围 —— `MailboxDrain` 读的正是这两个面，故「它看不见」由此机械成立。
 *   - **I-ORD 输入自身顺序**：`pending-input → taken-input` 迁移保序（seq 前缀定序，
 *     与 identity 字典序无关）。
 *   - **I-IDEM 重放不重复执行**：同 key 不得产生第二次执行（含跨「已消费」状态）。
 *
 * 全部纯桩驱动、只以**盘面**为判据（不依赖进程内状态），与 event 面 spec 同款纪律。
 */
class InputMailboxSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-input-mailbox"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    if tmp != null && os.exists(tmp) then os.remove.all(tmp)

  private val sid = "sess-1"

  private def payload(text: String): InputPayload =
    InputPayload(kind = InputPayload.KindUser, text = text, fromUser = true)

  private def enq(identity: String, text: String = "t"): IO[InputMailbox.InputMailResult] =
    InputMailbox.enqueue(sid, payload(text), identity, producer = "spec")

  private def pendingNames: List[String] =
    InputMailbox.scanPendingSync(sid).map(_.identity)

  private def evt(payloadText: String): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "completed",
      payload = payloadText,
      metadata = JsonObject.empty,
      correlationId = None
    )

  // ── I-ISO：物理隔离（输入不进 event drain 扫描面）─────────────────────

  test("I-ISO: 输入条目对 event 面完全不可见（MailboxDrain 读的两个面均为空）") {
    (for
      _ <- enq("in-1", "hello")
      _ <- enq("in-2", "world")
      // event 面：MailboxDrain.drainIdleFaceSync / replayTakenEventsSync 的唯一数据源
      evtPending <- SessionMailbox.scanPending(sid)
      evtTaken <- SessionMailbox.scanTaken(sid)
    yield (evtPending, evtTaken)).map { case (p, t) =>
      assertEquals(p, Nil, "event 面 pending 不得看见输入条目")
      assertEquals(t, Nil, "event 面 taken 不得看见输入条目")
      // 同时确认输入确实落盘了（不是「因为没写成才看不见」）
      assertEquals(pendingNames, List("in-1", "in-2"))
    }
  }

  test("I-ISO: 两条流同根共存、互不串面（event 条目也不出现在输入面）") {
    val eventEntry = evt("payload-1")
    (for
      _ <- enq("in-1")
      _ <- SessionMailbox.mailOnce(sid, eventEntry, kind = "delegate-terminal", identity = "evt-1")
      inputs <- InputMailbox.scanPending(sid)
      events <- SessionMailbox.scanPending(sid)
    yield (inputs.map(_.identity), events.map(_.identity))).map { case (ins, evs) =>
      assertEquals(ins, List("in-1"))
      assertEquals(evs, List("evt-1"))
    }
  }

  // ── I-ORD：输入自身顺序（与 identity 字典序无关）─────────────────────

  test("I-ORD: 连续 5 条输入 take 前后顺序不变（identity 字典序故意与入队序相反）") {
    val ids = List("k-5", "k-1", "k-3", "k-2", "k-4") // 字典序 ≠ 入队序
    (for
      _ <- ids.traverse(enq(_))
      before <- InputMailbox.scanPending(sid)
      taken = InputMailbox.takeSync(sid, before)
      after <- InputMailbox.scanTaken(sid)
    yield (before.map(_.identity), taken.map(_.identity), after.map(_.identity))).map {
      case (before, taken, after) =>
        assertEquals(before, ids, "pending 顺序 = 入队顺序（seq 定序，非字典序）")
        assertEquals(taken, ids, "take 保序")
        assertEquals(after, ids, "taken 侧仍保序（迁移不乱）")
        assertEquals(InputMailbox.scanPendingSync(sid), Nil, "全部迁走后 pending 为空")
    }
  }

  test("I-ORD: seq 单调且 taken 占序（迁空后再入队不复用序号）") {
    (for
      _ <- enq("a")
      _ <- enq("b")
      first <- InputMailbox.scanPending(sid)
      _ = InputMailbox.takeSync(sid, first)
      _ <- enq("c")
      third <- InputMailbox.scanPending(sid)
      all = first.map(_.seq) ++ InputMailbox.scanTakenSync(sid).map(_.seq) ++ third.map(_.seq)
    yield (first.map(_.seq), third.map(_.seq))).map { case (firstSeqs, thirdSeqs) =>
      assertEquals(firstSeqs, List(1L, 2L))
      assertEquals(thirdSeqs, List(3L), "taken 占序，新条目 seq 不复用 1/2")
    }
  }

  // ── I-IDEM：同一 key 重放不产生第二次执行 ────────────────────────────

  test("I-IDEM: 同 identity 二次 enqueue ⇒ AlreadyPresent，盘上只有 1 条") {
    (for
      r1 <- enq("dup")
      r2 <- enq("dup")
      all <- InputMailbox.scanPending(sid)
    yield (r1, r2, all)).map { case (r1, r2, all) =>
      assert(r1.isInstanceOf[InputMailbox.InputMailResult.Enqueued])
      assertEquals(r2, InputMailbox.InputMailResult.AlreadyPresent)
      assertEquals(all.map(_.identity), List("dup"))
    }
  }

  test("I-IDEM: 已消费 key 再入队 ⇒ AlreadyPresent（墓碑挡住，执行计数仍 = 1）") {
    (for
      _ <- enq("once", "run me")
      before <- InputMailbox.scanPending(sid)
      _ = InputMailbox.takeSync(sid, before)
      // 模拟「执行成功后」的回填：删 taken + 写墓碑（D3 的调用点语义）
      _ = InputMailbox.unlinkTakenSync(sid, before.map(_.identity))
      retry <- enq("once", "run me")
      stillPending <- InputMailbox.scanPending(sid)
      delivered <- InputMailbox.deliveredContains(sid, Set("once"))
    yield (retry, stillPending, delivered)).map { case (retry, stillPending, delivered) =>
      assertEquals(retry, InputMailbox.InputMailResult.AlreadyPresent, "已执行过的 key 不得再入队")
      assertEquals(stillPending, Nil)
      assertEquals(delivered, Set("once"), "墓碑记录了已执行身份")
    }
  }

  test("I-IDEM: 含 `:`/`/` 的 identity 也能被正确清除与记墓碑（文件名清洗不得丢匹配）") {
    val weird = "flow-notify:sess/42"
    (for
      _ <- enq(weird)
      before <- InputMailbox.scanPending(sid)
      _ = InputMailbox.takeSync(sid, before)
      _ = InputMailbox.unlinkTakenSync(sid, List(weird))
      taken <- InputMailbox.scanTaken(sid)
      delivered <- InputMailbox.deliveredContains(sid, Set(weird))
      // 再投同 key：墓碑命中 ⇒ 不得再执行
      retry <- enq(weird)
    yield (before.map(_.identity), taken.map(_.identity), delivered, retry)).map {
      case (beforeIds, takenIds, delivered, retry) =>
        assertEquals(beforeIds, List(weird), "入队一条，identity 逐字节保留")
        assertEquals(takenIds, Nil, "taken-input 里该条目必须被清除（清洗后仍需匹配）")
        assertEquals(delivered, Set(weird), "墓碑记的是原始 identity，不是清洗形")
        assertEquals(retry, InputMailbox.InputMailResult.AlreadyPresent)
    }
  }

  // ── 崩溃残差三窗（W1 未取 / W2 取后未执行 / W3 已执行未清）────────────

  test("W1: 入队后未取就崩 ⇒ 重扫仍见（零丢失）") {
    (for
      _ <- enq("w1")
      seen <- InputMailbox.scanPending(sid) // 「重启后」只靠盘面重建，无内存态
    yield seen).map(e => assertEquals(e.map(_.identity), List("w1")))
  }

  test("W2: take 后执行前崩 ⇒ scanTaken 重投（至多 1 重复、零丢失）") {
    (for
      _ <- enq("w2")
      before <- InputMailbox.scanPending(sid)
      _ = InputMailbox.takeSync(sid, before)
      replayed <- InputMailbox.scanTaken(sid)
    yield replayed).map(e => assertEquals(e.map(_.identity), List("w2")))
  }

  test("W3: 已执行但 taken 残留 ⇒ 重放腿靠墓碑跳过，执行计数 = 1") {
    // 执行腿的替身：只在此处「消费」，用计数器模拟真实 turn 执行次数
    var executions = 0
    def executeCounted(entries: List[InputMailbox.InputEntry]): IO[List[InputMailbox.InputEntry]] =
      InputMailbox.deliveredContains(sid, entries.map(_.identity).toSet).map { done =>
        entries.filterNot(e => done.contains(e.identity)).map { e =>
          executions += 1
          e
        }
      }

    (for
      _ <- enq("w3")
      before <- InputMailbox.scanPending(sid)
      _ = InputMailbox.takeSync(sid, before)
      // 第一次执行 + 回填墓碑
      ran1 <- executeCounted(before)
      _ = InputMailbox.unlinkTakenSync(sid, before.map(_.identity))
      // 崩溃在「删文件」与「墓碑」之间 → 人为制造 taken 残留 + 墓碑已在
      _ = os.makeDir.all(tmp / "sessions" / sid / "mailbox" / "taken-input")
      _ = os.write.over(
        tmp / "sessions" / sid / "mailbox" / "taken-input" / f"${1L}%012d-w3.json",
        before.head.asJson.noSpaces
      )
      residue <- InputMailbox.scanTaken(sid)
      // 恢复点重放：墓碑命中 ⇒ 不得再执行
      ran2 <- executeCounted(residue)
    yield (ran1.size, ran2.size, executions)).map { case (a, b, total) =>
      assertEquals(a, 1, "首次执行 1 次")
      assertEquals(b, 0, "W3 残差重放必须被墓碑拦住（0 次）")
      assertEquals(total, 1, "执行计数恒为 1 —— 重放不产生第二次执行")
    }
  }

  // ── 原子性 & 坏形件隔离 ─────────────────────────────────────────────

  test("原子写: 入队后 pending-input 里只有可解析的完整文件，无 tmp 残片") {
    (for
      _ <- enq("atom-1")
      _ <- enq("atom-2")
      entries <- InputMailbox.scanPending(sid)
      swept <- InputMailbox.sweepTmpAll()
    yield (entries, swept)).map { case (entries, swept) =>
      assertEquals(entries.map(_.identity), List("atom-1", "atom-2"), "两条都能被完整解析")
      assertEquals(swept, Nil, "无 tmp 残片（tmp 名含 .tmp. 前缀，由 sweep 命中）")
    }
  }

  test("坏形件不阻断 FIFO：被隔离到 quarantine-input，其余条目照常有序扫出") {
    os.makeDir.all(tmp / "sessions" / sid / "mailbox" / "pending-input")
    os.write.over(tmp / "sessions" / sid / "mailbox" / "pending-input" / "000000000002-broken.json", "{not json")
    (for
      _ <- IO(InputMailbox.scanPendingSync(sid)) // 触发一次扫描（隔离坏形件）
      _ <- enq("good-1")
      after <- InputMailbox.scanPending(sid)
      quarantined = os.list(tmp / "sessions" / sid / "mailbox" / "quarantine-input").toList
    yield (after.map(_.identity), quarantined.nonEmpty)).map { case (ids, q) =>
      assertEquals(ids, List("good-1"), "坏形件不进入结果集，好件照常")
      assert(q, "坏形件已被移入 quarantine-input")
    }
  }
