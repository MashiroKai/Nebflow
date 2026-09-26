package nebflow.core.tools

import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * 变更史对账判据 spec —— **occurrence 级**（记忆变更史对账批 2026-09-13）。
 *
 * 被试物 = `MemoryHistory.reconciliation` / `discrepancies`。判据从「ref 集合差」改为
 * 「逐 occurrence 配对」：同一 ref 可被消费多次（重试 / 对账闭合）⇒ 同 ref 可有多条
 * queue `outcome` 行与多条 `history:consume` 行；集合口径对这种**重复缺口**恒报 0
 * （同 ref 各至少一条即视为一致），是生产上实测到的哑绿形态（真缺口 151 报 0）。
 *
 * 三个 arm 覆盖：
 *   1. **判红**：人工构造一条已知缺口 ⇒ 新判据非零（同构造下 ref 集合口径仍 0，作对照臂）；
 *   2. **无误报**：真写通道（`recordOutcome`）多轮消费后 occurrence 级仍为空；
 *   3. **真缺陷复现**：绕过 `recordOutcome` 直写 `queue.jsonl`（运维对账脚本形态）⇒ 缺口显形。
 *
 * dataRoot 经 `PathUtil.setDataRoot` 重定向（`MemoryQueueSpec` 先例）。账本行**手写直写**
 * 的部分刻意复现「不经过写通道」的落盘形态，不用写通道代劳（否则复现不了该缺陷）。
 */
class MemoryHistoryOccurrenceSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-memhist-occ"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def reset(): Unit = os.remove.all(home / "memory")

  // ── 账本直写面（复现「绕过 recordOutcome 直写」的落盘形态）────────────

  private def appendRaw(path: os.Path, lines: String*): Unit =
    os.write.append(path, lines.map(_ + "\n").mkString, createFolders = true)

  private def qNote(ref: String, ms: Long): String =
    s"""{"kind":"note","at":"t","atMs":$ms,"id":"$ref","target":"user","action":"append","content":"- c","source":{"trigger":"manual"}}"""

  private def qOutcome(ref: String, ms: Long, result: String): String =
    s"""{"kind":"outcome","at":"t","atMs":$ms,"ref":"$ref","result":"$result","by":"memory-consolidator","detail":"d"}"""

  private def hQueue(ref: String, ms: Long): String =
    s"""{"kind":"queue","at":"t","atMs":$ms,"actor":"system","ref":"$ref","target":"user","action":"append","content":"- c","source":{"trigger":"manual"}}"""

  private def hConsume(ref: String, ms: Long, result: String): String =
    s"""{"kind":"consume","at":"t","atMs":$ms,"actor":"memory-consolidator","ref":"$ref","target":"user","action":"append","result":"$result","by":"memory-consolidator","detail":"d"}"""

  private def queueSide(): (List[String], List[String]) =
    val st = MemoryQueue.readState()
    (st.notes.map(_.id).toList, st.outcomes.map(_.ref).toList)

  private def enqueue(content: String): String =
    MemoryQueue
      .enqueue(
        target = "user",
        action = "append",
        section = None,
        matchText = None,
        content = Some(content),
        sessionId = Some("sess-occ"),
        trigger = MemoryQueue.TriggerManual,
        actor = "Nebula"
      )
      .toOption
      .get
      .id

  /**
   * **对照臂**：改动前的 ref 集合口径（`MemoryHistory.discrepancies` 改前逐字语义：
   * 三个集合差条件）。只用于并列证明「同一构造下旧口径恒 0」，**不参与判据断言**
   * ——判据面是被试的 `reconciliation`。
   */
  private def refSetControlArm(noteRefs: List[String], outcomeRefs: List[String]): List[String] =
    val events = MemoryHistory.readAll().events
    val queued = events.filter(_.kind == MemoryHistory.KindQueue).flatMap(_.ref).toSet
    val consumed = events.filter(_.kind == MemoryHistory.KindConsume).flatMap(_.ref).toSet
    val missingQueue = noteRefs.filterNot(queued.contains).map(r => s"note $r has no history:queue line")
    val missingConsume = outcomeRefs.filterNot(consumed.contains).map(r => s"outcome $r has no history:consume line")
    val orphanConsume = consumed.filterNot(noteRefs.contains).map(r => s"history:consume $r has no note").toList
    missingQueue ++ missingConsume ++ orphanConsume

  // ── ① 判红（鉴别力）────────────────────────────────────────────────

  test("①判红：同 ref 第 3 次 outcome 无对应 consume 行 ⇒ 报缺口；同一构造下 ref 集合口径恒 0"):
    reset()
    val r = "q-fixture-1"
    appendRaw(
      MemoryQueue.queuePath,
      qNote(r, 1000L),
      qOutcome(r, 1001L, "timeout"),
      qOutcome(r, 1002L, "timeout"),
      qOutcome(r, 1003L, "applied")
    )
    appendRaw(MemoryHistory.historyPath, hQueue(r, 1000L), hConsume(r, 1001L, "timeout"), hConsume(r, 1002L, "timeout"))

    val (notes, outcomes) = queueSide()
    assertEquals(outcomes.size, 3, "ref 侧 3 个 outcome occurrence")
    val g = MemoryHistory.reconciliation(notes, outcomes)
    assertEquals(g.missingConsume.size, 1, s"重复消费缺口必须显形: $g")
    assertEquals(g.total, 1)
    assertEquals(g.missingQueue.size, 0)
    assertEquals(g.orphanConsume.size, 0)
    assert(g.messages.head.contains("occurrence 3 of 3"), s"报出未配对的第 3 次 occurrence: ${g.messages}")
    assert(g.messages.head.contains("history:consume has 2"), s"读数带两侧 occurrence 数: ${g.messages.head}")
    assertEquals(MemoryHistory.discrepancies(notes, outcomes), g.messages, "discrepancies 返回面 == Gaps.messages")

    // 对照臂 1：同一构造，改动前 ref 集合口径恒 0（哑绿）
    assertEquals(refSetControlArm(notes, outcomes), Nil, "旧口径对同构造恒 0 —— 鉴别力缺口")
    // 对照臂 2：缺口之所以不可见，是 ref 集合层面根本没有差集（成因直证）
    val consumeRefs = MemoryHistory.ofKind(MemoryHistory.KindConsume).flatMap(_.ref).toSet
    assertEquals(outcomes.toSet.diff(consumeRefs), Set.empty[String], "ref 层面零差集 = 旧口径盲区")

  test("①判红/反向：history:consume 多出的 occurrence（无配对 outcome）也报出"):
    reset()
    val r = "q-orph-1"
    appendRaw(MemoryQueue.queuePath, qNote(r, 1000L), qOutcome(r, 1001L, "applied"))
    appendRaw(
      MemoryHistory.historyPath,
      hQueue(r, 1000L),
      hConsume(r, 1001L, "applied"),
      hConsume(r, 1002L, "applied"),
      hConsume("q-nobody", 1003L, "applied")
    )

    val (notes, outcomes) = queueSide()
    val g = MemoryHistory.reconciliation(notes, outcomes)
    assertEquals(g.missingQueue.size, 0)
    assertEquals(g.missingConsume.size, 0)
    assertEquals(g.orphanConsume.size, 2, s"双向都报（多出 occurrence + 无 note 的 consume）: $g")
    assert(
      g.orphanConsume.exists(m => m.contains(r) && m.contains("occurrence 2 of 2")),
      s"多出的第 2 条 consume 报出: ${g.orphanConsume}"
    )
    assert(
      g.orphanConsume.exists(m => m.contains("q-nobody") && m.contains("no note on the queue side")),
      s"无 note 的 consume 报出: ${g.orphanConsume}"
    )

  test("①判红/note 侧：note 无 history:queue 行 / 同 ref 列表多重度即 occurrence 数"):
    reset()
    val a = "q-a"
    val b = "q-b"
    appendRaw(MemoryQueue.queuePath, qNote(a, 1000L), qNote(b, 1001L))
    appendRaw(MemoryHistory.historyPath, hQueue(a, 1000L))

    val (notes, outcomes) = queueSide()
    val g = MemoryHistory.reconciliation(notes, outcomes)
    assertEquals(g.total, 1, s"q-b 整条缺 history:queue 行: $g")
    assertEquals(g.missingQueue.size, 1)
    assert(
      g.missingQueue.head.contains(b) && g.missingQueue.head.contains("occurrence 1 of 1"),
      g.missingQueue.toString
    )

    // 配对是**逐 ref 内的 occurrence 序**：同 ref 在调用方列表出现 2 次 = 2 个 occurrence，
    // 账本只 1 条 ⇒ 缺的正是第 2 个（这是「列表多重度即 occurrence 数」的直接断言）
    val g2 = MemoryHistory.reconciliation(List(a, a), Nil)
    assertEquals(
      g2.missingQueue,
      Vector(s"note $a occurrence 2 of 2 on the queue side has no history:queue line (history:queue has 1)"),
      "同 ref 的重复 occurrence 缺口逐条报出（集合口径看不见）"
    )

  // ── ② 无误报（真写通道）──────────────────────────────────────────

  test("②无误报：真写通道同 ref 多轮 recordOutcome（3 次）⇒ occurrence 级仍为空"):
    reset()
    val id = enqueue("- 条目一")
    for i <- 1 to 3 do
      assert(MemoryQueue.recordOutcome(id, MemoryQueue.ResultTimeout, "memory-consolidator", s"attempt $i").isRight)

    val (notes, outcomes) = queueSide()
    assertEquals(outcomes.count(_ == id), 3, "3 个 occurrence")
    assertEquals(MemoryHistory.ofKind(MemoryHistory.KindConsume).count(_.ref.contains(id)), 3, "consume 侧同样 3 条")
    val g = MemoryHistory.reconciliation(notes, outcomes)
    assert(g.isEmpty, s"1:1 写通道不产生缺口: $g")
    assertEquals(MemoryHistory.discrepancies(notes, outcomes), Nil)
    assertEquals(g, MemoryHistory.Gaps.empty, "与空读数结构相等")

  // ── ③ 真缺陷复现（直写通道）──────────────────────────────────────

  test("③真缺陷复现：绕过 recordOutcome 直写 queue.jsonl 一条 outcome ⇒ occurrence 缺口 1"):
    reset()
    val id = enqueue("- 条目一")
    assert(MemoryQueue.recordOutcome(id, MemoryQueue.ResultTimeout, "memory-consolidator", "attempt 1").isRight)
    val (n0, o0) = queueSide()
    assert(MemoryHistory.reconciliation(n0, o0).isEmpty, "写通道内 1:1 零缺口")

    // 运维对账脚本形态：只往 queue.jsonl 追加 outcome 行，不落 history:consume
    appendRaw(MemoryQueue.queuePath, qOutcome(id, 2000L, "applied"))

    val (notes, outcomes) = queueSide()
    assertEquals(outcomes.count(_ == id), 2, "队列侧多出 1 个 occurrence")
    assertEquals(MemoryHistory.ofKind(MemoryHistory.KindConsume).count(_.ref.contains(id)), 1, "consume 侧未增 —— 缺口本体")
    val g = MemoryHistory.reconciliation(notes, outcomes)
    assertEquals(g.missingConsume.size, 1, s"直写通道的缺口必须显形: $g")
    assertEquals(g.total, 1)
    assertEquals(refSetControlArm(notes, outcomes), Nil, "同一构造下旧口径仍 0")

  // ── ④ 零丢失（旧三条件全部保留）──────────────────────────────────

  test("④零丢失：旧口径报出的构造新口径一律也报（新 ⊇ 旧），且旧「consume 无 note」条件保留"):
    reset()
    // arm i：consume 存在但 note 不存在（畸形账本：outcome 与 consume 成对、无 note 行）
    val z = "q-ghost-note"
    appendRaw(MemoryQueue.queuePath, qOutcome(z, 1001L, "applied"))
    appendRaw(MemoryHistory.historyPath, hConsume(z, 1001L, "applied"))
    val (n1, o1) = queueSide()
    val g1 = MemoryHistory.reconciliation(n1, o1)
    assertEquals(g1.orphanConsume, Vector(s"history:consume $z has no note"), "旧「consume 无 note」条件逐字保留")
    assert(refSetControlArm(n1, o1).nonEmpty, "旧口径也报")

    // arm ii：note 无 history:queue 行（旧口径条件之一）
    reset()
    val w = "q-ghost-queue"
    appendRaw(MemoryQueue.queuePath, qNote(w, 1000L))
    val (n2, o2) = queueSide()
    val g2 = MemoryHistory.reconciliation(n2, o2)
    assertEquals(g2.missingQueue.size, 1)
    assert(refSetControlArm(n2, o2).nonEmpty, "旧口径也报")

    // arm iii：outcome 无 consume 行（旧口径条件之一，且 multiplicity=1 时两口径同判）
    reset()
    val v = "q-ghost-consume"
    appendRaw(MemoryQueue.queuePath, qNote(v, 1000L), qOutcome(v, 1001L, "applied"))
    appendRaw(MemoryHistory.historyPath, hQueue(v, 1000L))
    val (n3, o3) = queueSide()
    val g3 = MemoryHistory.reconciliation(n3, o3)
    assertEquals(g3.missingConsume.size, 1)
    assert(refSetControlArm(n3, o3).nonEmpty, "旧口径也报")

  // ── ⑤ 输出确定性（不依赖哈希迭代序）──────────────────────────────

  test("⑤输出顺序 = 调用方列表首次出现序（哈希迭代序会让本断言乱序）"):
    reset()
    val refs = List("q-1789184932788-1", "q-1789180934510-1", "q-1789225515662-1", "q-a", "q-0")
    val g = MemoryHistory.reconciliation(refs, refs)
    assertEquals(g.missingQueue.size, refs.size)
    assertEquals(g.missingQueue.map(_.split(" ")(1)).toList, refs, "按传入序报出（LinkedHashMap 首现序）")
    assertEquals(g.missingConsume.size, refs.size)
    assertEquals(g.messages.size, refs.size * 2, "missingQueue ++ missingConsume 顺序不变")

end MemoryHistoryOccurrenceSpec
