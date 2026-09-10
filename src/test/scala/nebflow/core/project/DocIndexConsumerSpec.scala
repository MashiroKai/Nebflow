package nebflow.core.project

import cats.effect.IO
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * P3 引擎侧归档联动单测（2026-09-10，spec
 * `20260910_process-doc-chain-attribution-spec.md` §6.2/§9.2 项 9）：
 *
 * - A 事件契约：`chain-archived` 顶层可选 `chainId` + 结构化 summary
 *   （`chain=<id> archivedAt=<ms> members=<n>`）；旧行（无 chainId 键）零迁移兼容
 *   （summary 回退）；既有事件类型不凭空多键。
 * - B sweep 明细：链级事实（chainId/nodeId/members）来自推导单点；兼容外壳
 *   `sweepCompletedChains` 的 List[String] 口径不回归。
 * - C 索引变换：块迁入/迁出链分区 + 行 state 翻转 + 标题归档时间戳；幂等不动点。
 * - D 消费者：白名单（只改写 INDEX.md）、无条目/无索引跳过 + 记日志、
 *   chain-restored 回翻分支（接口点，无写入点）。
 * - E 对账兜底：missing-in-index / stale-in-index 检出 + 报表落
 *   `<workspace>/.nebflow/tmp/`；tick 节流与「索引未落地 = 零开销 no-op」。
 */
class DocIndexConsumerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def freshDir(prefix: String): os.Path =
    os.temp.dir(prefix = prefix, deleteOnExit = true)

  private val sampleDomain = "Test域"
  private val atMs = 1757492400000L

  private def sampleIndex(chainId: String = "chain-n1"): String =
    s"""# 测试域文档索引
       |
       |绪言行。
       |
       |## 活跃链分区（active）
       |
       |### $chainId · 链标题一 · completed · 2 节点
       |
       || time | doc | class | chain | role | source | state | confidence |
       ||---|---|---|---|---|---|---|---|
       || 2026-09-10T11:00:00+08:00 | ./20260910_110000_a.md | stage | $chainId | head | engine | active | |
       || 2026-09-10T11:05:00+08:00 | ./20260910_110500_b.md | stage | $chainId | tail | engine | active | |
       |
       |## 已归档链分区（archived）
       |
       |## 无归属（unattributed）
       |
       || time | doc | class | chain | role | source | state | confidence |
       ||---|---|---|---|---|---|---|---|
       ||  | ./笔记.md | live |  |  |  | active | |
       |
       |## 活文档索引（live）
       |""".stripMargin

  /** 造一个域目录：`<root>/<域>/INDEX.md` + 两份被索引文档 + 一份非索引 .md。 */
  private def writeDomain(root: os.Path, index: String): IO[(os.Path, List[os.Path])] =
    IO.blocking {
      val dir = root / sampleDomain
      os.makeDir.all(dir)
      val a = dir / "20260910_110000_a.md"
      val b = dir / "20260910_110500_b.md"
      val notes = dir / "NOTES.md"
      os.write(a, "doc a body\n")
      os.write(b, "doc b body\n")
      os.write(notes, "# 非索引文件（消费者不得改写）\n")
      val idx = dir / "INDEX.md"
      os.write(idx, index)
      (idx, List(a, b, notes))
    }

  private def writeBatch(ws: String, batchId: String): IO[Unit] =
    IO.blocking {
      val dir = os.Path(ws) / ".nebflow" / "flow-map-archive"
      os.makeDir.all(dir)
      os.write(dir / s"$batchId.json", s"""{"project":"demo","batch":"$batchId","archivedAt":1,"nodes":{}}""")
    }

  private def archived(chainId: String, at: Long = atMs, members: Int = 2): DocIndexConsumer.DesiredState =
    DocIndexConsumer.DesiredState(chainId, archived = true, at, Some(members))

  private def restored(chainId: String, at: Long = atMs + 60000, members: Int = 2): DocIndexConsumer.DesiredState =
    DocIndexConsumer.DesiredState(chainId, archived = false, at, Some(members))

  // ── A 事件契约 ───────────────────────────────────────────

  test("A① chain-archived 事件：顶层可选 chainId + 结构化 summary，旧行零迁移兼容") {
    val ws = freshDir("nb-docidx-ev-").toString
    for
      _ <- FlowMapEventLog.append(ws, "demo", "n1", FlowMapEventLog.ChainArchivedType,
        FlowMapEventLog.chainArchivedSummary("chain-n1", atMs, 3), Some("chain-n1"))
      _ <- FlowMapEventLog.append(ws, "demo", "n3", "node-ask", "ask") // 既有事件：默认 None
      raw <- IO.blocking(os.read.lines(os.Path(ws) / ".nebflow" / FlowMapEventLog.FileName).toList)
    yield
      assertEquals(raw.size, 2)
      val first = jsonParse(raw.head).toOption.getOrElse(fail("event line must be valid JSON"))
      assertEquals(first.hcursor.get[String]("chainId").toOption, Some("chain-n1"))
      assertEquals(first.hcursor.get[String]("summary").toOption,
        Some(s"chain=chain-n1 archivedAt=$atMs members=3"))
      assertEquals(first.hcursor.get[String]("type").toOption, Some("chain-archived"))
      // 既有调用点零改动：chainId 缺省不写该键（append-only 零迁移）
      val second = jsonParse(raw(1)).toOption.getOrElse(fail("event line must be valid JSON"))
      assertEquals(second.hcursor.get[String]("chainId").toOption, None)
      assertEquals(second.asObject.map(_.keys.toSet),
        Some(Set("ts", "type", "project", "nodeId", "summary")))
  }

  test("A② 消费侧解析：顶层 chainId 优先、summary 回退、非链族事件不入期望态") {
    val withField =
      s"""{"ts":11,"type":"chain-archived","project":"demo","nodeId":"n1","chainId":"chain-n1","summary":"chain=chain-n1 archivedAt=$atMs members=3"}"""
    val legacy =
      """{"ts":5,"type":"chain-restored","project":"demo","nodeId":"n2","summary":"chain=chain-n2 restoredAt=9 members=1"}"""
    val nodeAsk = """{"ts":7,"type":"node-ask","project":"demo","nodeId":"n3","summary":"ask: 提问"}"""
    val e1 = DocIndexConsumer.parseEventLine(withField).getOrElse(fail("must parse"))
    val e2 = DocIndexConsumer.parseEventLine(legacy).getOrElse(fail("must parse"))
    val e3 = DocIndexConsumer.parseEventLine(nodeAsk).getOrElse(fail("must parse"))
    assertEquals(e1.chainId, Some("chain-n1"))
    assertEquals(e1.atMs, atMs)
    assertEquals(e2.chainId, Some("chain-n2"))
    assertEquals(e2.atMs, 9L)
    assertEquals(e3.chainId, None)
    val desired = DocIndexConsumer.desiredStates(List(e1, e2, e3))
    assertEquals(desired.keySet, Set("chain-n1", "chain-n2"))
    assertEquals(desired("chain-n1").archived, true)
    assertEquals(desired("chain-n2").archived, false)
    // 后写覆盖先写（archived → restored → archived 末态 = archived）
    val e4 = DocIndexConsumer.parseEventLine(
      s"""{"ts":12,"type":"chain-archived","project":"demo","nodeId":"n2","chainId":"chain-n2","summary":"chain=chain-n2 archivedAt=12 members=1"}""")
      .getOrElse(fail("must parse"))
    assertEquals(DocIndexConsumer.desiredStates(List(e1, e2, e3, e4))("chain-n2").archived, true)
  }

  // ── B sweep 明细（链级事实单点） ────────────────────────────

  private def seedChain(store: FlowMapStore, base: Long): IO[Unit] =
    store.mutate(s => s.copy(nodes = Map(
      "n1" -> NodeDef(id = "n1", name = "head", agent = "Backend", status = NodeLifecycle.Completed,
        createdAt = base, completedAt = Some(base + 1), out = List(OutEdge.nebula)),
      "n2" -> NodeDef(id = "n2", name = "tail", agent = "Backend", status = NodeLifecycle.Completed,
        createdAt = base + 10, completedAt = Some(base + 20), in = List("n1"), out = List(OutEdge.nebula))
    ))).void

  test("B① sweepCompletedChainsDetailed：chainId / nodeId（分量最早 createdAt）/ members / 移出成员") {
    val ws = freshDir("nb-docidx-sweep-").toString
    val base = System.currentTimeMillis() - 100000
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- seedChain(store, base)
      swept <- store.sweepCompletedChainsDetailed(base + 5000)
    yield
      assertEquals(swept.map(_.chainId), List("chain-n1"))
      assertEquals(swept.map(_.nodeId), List("n1"), "nodeId = 分量内 createdAt 最早节点（与 chainId 派生同源）")
      assertEquals(swept.head.members, 2)
      assertEquals(swept.head.nodeIds.sorted, List("n1", "n2"))
      assertEquals(swept.head.archivedAt, base + 5000)
  }

  test("B② 兼容外壳：sweepCompletedChains 返回 List[String]（既有调用方零改动）") {
    val ws = freshDir("nb-docidx-sweep2-").toString
    val base = System.currentTimeMillis() - 100000
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- seedChain(store, base)
      ids <- store.sweepCompletedChains(base + 5000)
      batches <- store.archiveBatches
    yield
      assertEquals(ids.sorted, List("n1", "n2"))
      assertEquals(batches.keySet, Set("chain-n1"), "批 id = 链 id")
  }

  // ── C 索引变换（纯函数、幂等） ─────────────────────────────

  test("C① 归档：链块迁入已归档分区 + 行 state 翻 archived + 标题补归档时间戳；二次 no-op") {
    val desired = Map("chain-n1" -> archived("chain-n1"))
    val r1 = DocIndexConsumer.transformIndex(sampleIndex(), desired)
    assert(r1.changed, "first apply must change the index")
    assertEquals(r1.blocksMoved, 1)
    assertEquals(r1.stateFlips, 2)
    assertEquals(r1.notes, Nil)
    val stamp = DocIndexConsumer.isoSeconds(atMs)
    assert(r1.content.contains(s"### chain-n1 · 链标题一 · completed · 2 节点 · archived $stamp"),
      s"heading must carry archived stamp, got:\n${r1.content}")
    val archIdx = r1.content.indexOf("## 已归档链分区（archived）")
    val blockIdx = r1.content.indexOf("### chain-n1")
    assert(blockIdx > archIdx, "chain block must live in the archived section")
    assert(!r1.content.substring(0, archIdx).contains("### chain-n1"), "active section must no longer hold the block")
    assert(r1.content.contains("| chain-n1 | head | engine | archived |"), s"row state must flip:\n${r1.content}")
    assertEquals(r1.content.sliding("| archived |".length).count(_ == "| archived |"), 2)
    // 无归属行零变化
    assert(r1.content.contains("| ./笔记.md | live |"))
    // 幂等：同输入同事件 → 第二次零 diff
    val r2 = DocIndexConsumer.transformIndex(r1.content, desired)
    assertEquals(r2.changed, false)
    assertEquals(r2.content, r1.content)
    assertEquals(r2.stateFlips, 0)
    assertEquals(r2.blocksMoved, 0)
  }

  test("C② 回翻（chain-restored 接口点）：块迁回活跃分区 + 行 state 回 active + 时间戳剥除；二次 no-op") {
    val arch = Map("chain-n1" -> archived("chain-n1"))
    val rest = Map("chain-n1" -> restored("chain-n1"))
    val archivedContent = DocIndexConsumer.transformIndex(sampleIndex(), arch).content
    val r1 = DocIndexConsumer.transformIndex(archivedContent, rest)
    assert(r1.changed, "restore must move the block back")
    assertEquals(r1.blocksMoved, 1)
    assertEquals(r1.stateFlips, 2)
    assert(r1.content.contains("### chain-n1 · 链标题一 · completed · 2 节点"),
      s"archived stamp must be stripped:\n${r1.content}")
    assert(!r1.content.contains("· archived"), s"no archived stamp left:\n${r1.content}")
    val actIdx = r1.content.indexOf("## 活跃链分区（active）")
    val archIdx = r1.content.indexOf("## 已归档链分区（archived）")
    val blockIdx = r1.content.indexOf("### chain-n1")
    assert(blockIdx > actIdx && blockIdx < archIdx, "block must be back in the active section")
    assert(r1.content.contains("| chain-n1 | head | engine | active |"))
    val r2 = DocIndexConsumer.transformIndex(r1.content, rest)
    assertEquals(r2.changed, false)
    assertEquals(r2.content, r1.content)
  }

  test("C③ 多链行与无链行：块内行随块整体翻（spec §6.3）；松散行按链集判定；查无条目零 diff") {
    val blockMulti = sampleIndex().replace(
      "| ./20260910_110500_b.md | stage | chain-n1 | tail | engine | active | |",
      "| ./20260910_110500_b.md | stage | chain-n1, chain-n9 | tail | engine | active | |")
    // ① 块内多链行随块整体迁移（一次事件批量翻——链分区口径，非行级）
    val r1 = DocIndexConsumer.transformIndex(blockMulti, Map("chain-n1" -> archived("chain-n1")))
    assert(r1.content.contains("| chain-n1, chain-n9 | tail | engine | archived |"),
      s"block-scoped rows flip with the block:\n${r1.content}")
    assertEquals(r1.stateFlips, 2)

    // ② 松散行（无链标题，如 unattributed 分区条目表）：全链归档才翻，部分归档不动
    val loose = sampleIndex().replace(
      "|  | ./笔记.md | live |  |  |  | active | |",
      "| 2026-09-10T12:00:00+08:00 | ./20260910_120000_m.md | stage | chain-n1, chain-n9 | merge | engine | active | |")
    val r2 = DocIndexConsumer.transformIndex(loose, Map("chain-n1" -> archived("chain-n1")))
    assert(r2.content.contains("| chain-n1, chain-n9 | merge | engine | active |"),
      s"partial archive must not flip a multi-chain loose row:\n${r2.content}")
    val r3 = DocIndexConsumer.transformIndex(loose,
      Map("chain-n1" -> archived("chain-n1"), "chain-n9" -> archived("chain-n9")))
    assert(r3.content.contains("| chain-n1, chain-n9 | merge | engine | archived |"),
      s"all-chains-archived flips the loose row in place:\n${r3.content}")

    // ③ 事件引用的链在索引里查无条目 → 零 diff
    val r4 = DocIndexConsumer.transformIndex(sampleIndex(), Map("chain-nope" -> archived("chain-nope")))
    assertEquals(r4.changed, false)
    assertEquals(r4.content, sampleIndex())
  }

  // ── D 消费者（IO 层：白名单 + 跳过 + 幂等） ─────────────────

  test("D① applyChainEvents：事件驱动翻转 INDEX.md；被索引文档零改动；二次全 no-op") {
    val ws = freshDir("nb-docidx-apply-").toString
    val root = freshDir("nb-docidx-root-")
    for
      _ <- FlowMapEventLog.append(ws, "demo", "n1", FlowMapEventLog.ChainArchivedType,
        FlowMapEventLog.chainArchivedSummary("chain-n1", atMs, 2), Some("chain-n1"))
      // 事件引用的链在索引里查无条目 → 跳过 + 记日志
      _ <- FlowMapEventLog.append(ws, "demo", "n9", FlowMapEventLog.ChainArchivedType,
        FlowMapEventLog.chainArchivedSummary("chain-nope", atMs, 1), Some("chain-nope"))
      (idx, docs) <- writeDomain(root, sampleIndex())
      before <- IO.blocking(docs.map(p => p -> os.read(p)))
      run1 <- DocIndexConsumer.applyChainEvents(ws, List(root.toString))
      after1 <- IO.blocking(docs.map(p => p -> os.read(p)))
      idx1 <- IO.blocking(os.read(idx))
      run2 <- DocIndexConsumer.applyChainEvents(ws, List(root.toString))
      files <- IO.blocking(os.walk(root).filter(os.isFile).map(_.last).toList.sorted)
    yield
      assertEquals(run1.indexFiles, List(idx.toString))
      assertEquals(run1.skippedChains, List("chain-nope"))
      assertEquals(run1.chainEvents, 2)
      assertEquals(run1.results.map(_.changed), List(true))
      assert(idx1.contains("| chain-n1 | head | engine | archived |"))
      assertEquals(before, after1, "被索引文档必须零改动")
      assertEquals(run2.results.map(_.changed), List(false), "同事件重放 = 幂等 no-op")
      // 未创建任何新文件（尤其不得新建 INDEX.md / 新分区文件）
      assertEquals(files, List("20260910_110000_a.md", "20260910_110500_b.md", "INDEX.md", "NOTES.md"))
  }

  test("D② 白名单与非索引文件：scanIndexFiles 只收 INDEX.md；NOTES.md 永不被改写") {
    val root = freshDir("nb-docidx-scan-")
    val ws = freshDir("nb-docidx-apply2-").toString
    for
      _ <- writeDomain(root, sampleIndex())
      _ <- IO.blocking {
        os.makeDir.all(root / sampleDomain / "sub")
        os.write(root / sampleDomain / "sub" / "INDEX.md", sampleIndex("chain-n2"))
      }
      _ <- FlowMapEventLog.append(ws, "demo", "n1", FlowMapEventLog.ChainArchivedType,
        FlowMapEventLog.chainArchivedSummary("chain-n1", atMs, 2), Some("chain-n1"))
      files <- DocIndexConsumer.scanIndexFiles(List(root.toString))
      notesBefore <- IO.blocking(os.read(root / sampleDomain / "NOTES.md"))
      run <- DocIndexConsumer.applyChainEvents(ws, List(root.toString))
      notesAfter <- IO.blocking(os.read(root / sampleDomain / "NOTES.md"))
    yield
      assertEquals(files.map(_.last), List("INDEX.md", "INDEX.md"))
      assertEquals(run.indexFiles.size, 2)
      assertEquals(run.results.count(_.changed), 1, "只有含该链块的那份索引变化")
      assertEquals(notesBefore, notesAfter)
  }

  test("D③ 索引不存在：跳过 + 记日志（不创建 INDEX.md）") {
    val ws = freshDir("nb-docidx-noindex-").toString
    val root = freshDir("nb-docidx-emptyroot-")
    for
      _ <- FlowMapEventLog.append(ws, "demo", "n1", FlowMapEventLog.ChainArchivedType,
        FlowMapEventLog.chainArchivedSummary("chain-n1", atMs, 2), Some("chain-n1"))
      run <- DocIndexConsumer.applyChainEvents(ws, List(root.toString))
      files <- IO.blocking(os.walk(root).map(_.last).toList)
    yield
      assertEquals(run.indexFiles, Nil)
      assertEquals(run.skippedChains, List("chain-n1"))
      assertEquals(files, Nil, "绝不创建 INDEX.md")
  }

  // ── E 对账兜底 ────────────────────────────────────────────

  test("E① reconcile：missing-in-index / stale-in-index 检出 + 报表落 .nebflow/tmp/") {
    val ws = freshDir("nb-docidx-rec-").toString
    val root = freshDir("nb-docidx-recroot-")
    for
      _ <- writeDomain(root, sampleIndex()) // chain-n1 条目 state=active
      _ <- writeBatch(ws, "chain-n1")       // 归档区有 chain-n1（索引 pending → stale）
      _ <- writeBatch(ws, "chain-n9")       // 归档区有 chain-n9（索引无 → missing）
      r <- DocIndexConsumer.reconcile(ws, List(root.toString))
      raw <- IO.blocking(os.read(os.Path(r.reportPath)))
      json <- IO.fromEither(jsonParse(raw))
    yield
      assertEquals(r.archiveChains, List("chain-n1", "chain-n9"))
      assertEquals(r.indexChains, List("chain-n1"))
      assertEquals(r.missingInIndex, List("chain-n9"))
      assertEquals(r.staleInIndex.size, 2, "块内两条 state=active 条目均为 stale（a/b 两份文档）")
      assert(r.staleInIndex.forall(_.startsWith("chain=chain-n1 ")), r.staleInIndex.toString)
      assert(r.staleInIndex.exists(_.contains("./20260910_110000_a.md")), r.staleInIndex.toString)
      assert(r.staleInIndex.exists(_.contains("./20260910_110500_b.md")), r.staleInIndex.toString)
      assert(!r.isClean)
      assertEquals(r.reportPath, (os.Path(ws) / ".nebflow" / "tmp" / DocIndexConsumer.ReportFileName).toString)
      assertEquals(json.hcursor.get[List[String]]("missingInIndex").toOption, Some(List("chain-n9")))
      assertEquals(json.hcursor.get[List[String]]("archiveChains").toOption, Some(List("chain-n1", "chain-n9")))
      // 对账只写报表：文档本体零改动
      assertEquals(os.read(root / sampleDomain / "INDEX.md"), sampleIndex())
  }

  test("E② tick：索引区无 INDEX.md → 零开销 no-op；报表干净不落盘；有差异才落报表") {
    val ws = freshDir("nb-docidx-tick-").toString
    val emptyRoot = freshDir("nb-docidx-tickempty-")
    val okRoot = freshDir("nb-docidx-tickok-")
    val badRoot = freshDir("nb-docidx-tickbad-")
    val report = os.Path(ws) / ".nebflow" / "tmp" / DocIndexConsumer.ReportFileName
    for
      none <- DocIndexConsumer.tick(ws, minIntervalMs = 0, indexRoots = Some(List(emptyRoot.toString)))
      // 归档区空 + 索引有链条目 → clean（无 missing/stale）
      _ <- writeDomain(okRoot, sampleIndex())
      clean <- DocIndexConsumer.tick(ws, minIntervalMs = 0, indexRoots = Some(List(okRoot.toString)))
      cleanReportExists <- IO.blocking(os.exists(report))
      // 出现缺索引的归档链 → 落报表
      _ <- writeBatch(ws, "chain-n9")
      _ <- writeDomain(badRoot, sampleIndex())
      dirty <- DocIndexConsumer.tick(ws, minIntervalMs = 0, indexRoots = Some(List(badRoot.toString)))
      dirtyReportExists <- IO.blocking(os.exists(report))
      now <- IO.blocking(os.read(report))
      _ <- IO.blocking(os.remove.all(report))
      // 节流：同 JVM 内小于间隔的第二次调用不执行
      throttled <- DocIndexConsumer.tick(ws, minIntervalMs = 60000, indexRoots = Some(List(badRoot.toString)))
      throttledReport <- IO.blocking(os.exists(report))
    yield
      assertEquals(none, None)
      assert(clean.exists(_.isClean), clean.toString)
      assertEquals(cleanReportExists, false, "报表干净时不落盘")
      assert(dirty.exists(!_.isClean))
      assert(dirtyReportExists)
      assert(now.contains("chain-n9"))
      assertEquals(throttled, None)
      assertEquals(throttledReport, false)
  }
