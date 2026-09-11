package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.service.MemoryStore

import java.nio.file.Files

/**
 * 记忆队列 + 变更史数据面 spec（2026-09-12 记忆改造批 / spec §5 R1 O-A + §7 IMPL-1
 * 必含项）。
 *
 * 覆盖：折叠谓词（有 note 无 outcome）、幂等钥匙、append-only、崩溃半行容忍、
 * 容量上限兜底（`drop` 记录，禁静默丢）、变更史三 kind 与行级 diff、对账差集。
 *
 * dataRoot 经 PathUtil.setDataRoot 重定向（DeviceIdentitySpec 先例）。
 */
class MemoryQueueSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-memq-queue"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def reset(): Unit =
    os.remove.all(home / "memory")
    MemoryStore.invalidateUserCache()
    MemoryStore.invalidateAgentCache("Nebula")
    MemoryTrackSignalReset()

  /** 进程内信号复位（跨 spec 不串扰）。 */
  private def MemoryTrackSignalReset(): Unit =
    nebflow.agent.MemoryTrackSignal.resetForTest()

  private def enqueue(content: String, target: String = "user"): Either[String, MemoryQueue.EnqueueResult] =
    MemoryQueue.enqueue(
      target = target,
      action = "append",
      section = None,
      matchText = None,
      content = Some(content),
      sessionId = Some("sess-1"),
      trigger = MemoryQueue.TriggerManual,
      actor = "Nebula"
    )

  private def queueLines: Vector[String] =
    if os.exists(MemoryQueue.queuePath) then os.read(MemoryQueue.queuePath).split("\n", -1).toVector.filter(_.nonEmpty)
    else Vector.empty

  // ===== 追加 / 折叠 =====

  test("enqueue 追加一行合法 JSONL note（atMs 必带，字段齐备），pending 计数随折叠变化"):
    reset()
    val r = enqueue("- 条目一")
    assert(r.isRight)
    val id = r.toOption.get.id
    assertEquals(queueLines.size, 1)
    val st = MemoryQueue.readState()
    assertEquals(st.pendingCount, 1)
    assertEquals(st.notes.head.id, id)
    assert(os.read(MemoryQueue.queuePath).contains("\"atMs\":"), "atMs 必带入档")
    assertEquals(MemoryQueue.summaryLine().contains("1 pending note(s)"), true, "注入摘要行反映 pending")
    assert(MemoryQueue.recordOutcome(id, MemoryQueue.ResultApplied, "memory-consolidator", "applied").isRight)
    assertEquals(MemoryQueue.readState().pendingCount, 0, "折叠谓词：有 note 有 outcome ⇒ 非 pending")
    assertEquals(MemoryQueue.summaryLine(), "", "pending=0 且无弃置 ⇒ 摘要行为空（不留常驻噪声）")

  test("append-only：outcome 只追加，不重写 note 行"):
    reset()
    enqueue("- 条目一")
    val first = queueLines.head
    MemoryQueue.recordOutcome(MemoryQueue.readState().notes.head.id, MemoryQueue.ResultApplied, "by", "d")
    assertEquals(queueLines.head, first, "既有行逐字未动（append-only）")
    assertEquals(queueLines.size, 2)
    assert(queueLines(1).contains("\"kind\":\"outcome\""))

  test("幂等键：同 target+action+section+match+content ⇒ deduped + 既有 id；任一字段变化 ⇒ 新行"):
    reset()
    val a = enqueue("- 同一条").toOption.get
    val b = enqueue("- 同一条").toOption.get
    assert(b.deduped && a.id == b.id, "同 hash 返回既有 q-id")
    assertEquals(queueLines.size, 1, "去重不追加行")
    val c = MemoryQueue.enqueue("user", "remove", None, Some("同一条"), None,
      Some("sess-1"), MemoryQueue.TriggerManual, "Nebula").toOption.get
    assert(!c.deduped && c.id != a.id, "action 参与 hash")
    assertEquals(queueLines.size, 2)

  test("崩溃安全：半行 / 坏行计入 unreadable，不影响折叠与后续追加"):
    reset()
    enqueue("- 完好条目")
    os.write.append(MemoryQueue.queuePath, "{\"kind\":\"note\",\"id\":\"q-broken\"", createFolders = true)
    val st = MemoryQueue.readState()
    assertEquals(st.pendingCount, 1, "坏行不进折叠")
    assertEquals(st.unreadable, 1, "坏行如实计数")
    assert(MemoryQueue.recordOutcome(st.notes.head.id, MemoryQueue.ResultObsolete, "by", "gone").isRight)
    assertEquals(MemoryQueue.readState().pendingCount, 0)
    assert(queueLines.size >= 3)

  test("无 outcome 的 note 才算 pending：obsolete / rejected / timeout 均闭合折叠"):
    reset()
    val ids = (1 to 4).map(i => enqueue(s"- 条目 $i").toOption.get.id)
    MemoryQueue.recordOutcome(ids(0), MemoryQueue.ResultObsolete, "b", "d")
    MemoryQueue.recordOutcome(ids(1), MemoryQueue.ResultRejected, "b", "d")
    MemoryQueue.recordOutcome(ids(2), MemoryQueue.ResultTimeout, "b", "d")
    assertEquals(MemoryQueue.readState().pendingCount, 1)

  test("容量上限：pending 超 500 → 按 atMs 保留最近 N，被弃者写 drop 记录（禁静默丢）"):
    reset()
    var last: MemoryQueue.EnqueueResult = null
    for i <- 1 to MemoryQueue.MaxPending do last = enqueue(s"- 条目 $i").toOption.get
    assertEquals(MemoryQueue.readState().pendingCount, MemoryQueue.MaxPending)
    val over = enqueue("- 第 501 条").toOption.get
    assertEquals(over.dropped, 1, "超顶丢弃 1 条")
    val st = MemoryQueue.readState()
    assertEquals(st.pendingCount, MemoryQueue.MaxPending, "超顶后回到上限")
    assertEquals(st.droppedTotal, 1)
    assert(os.read(MemoryQueue.queuePath).contains("\"kind\":\"drop\""), "drop 记录落盘")
    assert(os.read(MemoryQueue.queuePath).contains("\"dropped\":1"), "记 dropped=N")
    val keptIds = st.pending.map(_.id).toSet
    assert(keptIds.contains(over.id), "新条目保留")
    assert(!keptIds.contains(MemoryQueue.readState().notes.head.id), "最旧条目被弃")

  test("summaryLine 反映弃置计数（弃置不静默）"):
    reset()
    for i <- 1 to (MemoryQueue.MaxPending + 1) do enqueue(s"- 条目 $i")
    val line = MemoryQueue.summaryLine()
    assert(line.contains("dropped by the 500-pending cap"), s"摘要行报弃置: $line")

  // ===== 变更史 =====

  test("变更史：queue / consume / change 三 kind 逐行合法 JSONL，at/atMs/actor 引擎代写"):
    reset()
    val id = enqueue("- 条目一").toOption.get.id
    MemoryQueue.recordOutcome(id, MemoryQueue.ResultApplied, "memory-consolidator", "applied")
    MemoryHistory.appendChange(
      atMs = 1L, actor = "memory-consolidator", path = "/x/User.md", target = "user",
      trigger = MemoryQueue.TriggerCompaction, refs = List(id),
      added = List("- 新条目"), removed = List("- 旧条目"))
    val st = MemoryHistory.stats()
    assertEquals(st.queued, 1)
    assertEquals(st.consumed, 1)
    assertEquals(st.changed, 1)
    assertEquals(st.unreadable, 0)
    val ch = MemoryHistory.ofKind(MemoryHistory.KindChange).head
    assertEquals(ch.removed, List("- 旧条目"), "removed 逐行全文不截断")
    assertEquals(ch.added, List("- 新条目"))
    assertEquals(ch.refs, List(id), "change 行以 refs 挂到队列条目（可对账）")
    assert(MemoryHistory.ofKind(MemoryHistory.KindQueue).head.at.nonEmpty, "引擎代写 ISO 时间戳")

  test("变更史对账：队列有 note 无 history:queue / outcome 无 history:consume ⇒ 差集非空"):
    reset()
    val id = enqueue("- 条目一").toOption.get.id
    MemoryQueue.recordOutcome(id, MemoryQueue.ResultApplied, "by", "d")
    assertEquals(MemoryHistory.discrepancies(List(id), List(id)), Nil)
    val d = MemoryHistory.discrepancies(List(id, "q-ghost-1"), List(id, "q-ghost-2"))
    assertEquals(d.size, 2, s"两条缺口都报出: $d")

  test("行级 diff（纯函数）：removed/added 保序、重复计次数、无变化 ⇒ 双空"):
    assertEquals(MemoryHistory.lineDiff("a\nb\nc", "a\nc"), (List("b"), List.empty[String]))
    assertEquals(MemoryHistory.lineDiff("a", "a\nb"), (List.empty[String], List("b")))
    assertEquals(MemoryHistory.lineDiff("x\nx", "x"), (List("x"), List.empty[String]))
    assertEquals(MemoryHistory.lineDiff("", ""), (List.empty[String], List.empty[String]))
    assertEquals(MemoryHistory.lineDiff("same", "same"), (List.empty[String], List.empty[String]))

  test("变更史 append-only：早期行逐字保留，归档轮转读跨代"):
    reset()
    enqueue("- 条目一")
    val firstLine = os.read(MemoryHistory.historyPath).linesIterator.toVector.head
    enqueue("- 条目二")
    assertEquals(os.read(MemoryHistory.historyPath).linesIterator.toVector.head, firstLine, "既有行未改写")
    assertEquals(MemoryHistory.readAll().events.count(_.kind == MemoryHistory.KindQueue), 2)

end MemoryQueueSpec
