package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*

/**
 * 链号台账（chainmodel 批二）语义与**三轴红验**。
 *
 * 覆盖：
 * - T1 出生即定 + 承继不改号（「链号出生即定、永不重归」）
 * - T2 合并 = 显式改号 + 旧号保留别名 + 逐节点改号留痕（先出生者保号）
 * - T3 拆分 = 锚点分量保号 + 拆出分量新号 + 改号留痕
 * - T4 离场 = 条目下沉冷档（只归档不删除）
 * - T5 **轴(a)×(b) 红验**：退役判据 =「已归档/已离场 ∧ 零引用」
 * - T6 **轴(b) 红验**：引用计数覆盖式复算 + 减到 0 ⇒ 退役
 * - T7 **轴(b) 面枚举红验**：七面在册（含未接线三面如实登记）＋ 各带写点/增减时机
 * - T8 **轴(c) 红验（台账无限增长）**：条数 / 字节双阈值触发压缩轮
 * - T9 轴(c) 端到端：越阈值 ⇒ 压缩轮落冷档 + 自检 Right + 压缩后身份不重归
 * - T10 落盘面：原子写 + 启动期载入 + 重启后自洽 + 损坏文件只 WARN 不崩
 * - T11 集成：`FlowMapStore` 拍点接线（出生 → 合并改号 → 轴(a) 归档绑定）
 * - T12 解析单点：热面别名逐跳 + 冷档下沉兜底（旧号永久可达）
 * - T13 **轮间链式红验（同拍）**：dissolve + compact 同拍 ⇒ 拍内链式成立（verify=Right）
 * - T14 **轮间链式红验（跨拍）**：两拍各出一轮 compact、其间仅有出生 ⇒ verify=Right
 * - T15 **open-time cold-archive audit budget**: an over-budget archive skips the
 *   cold read at store open (bounded startup memory); resolveDeep stays a lazy
 *   fallback with unchanged semantics; the explicit verify full audit is intact
 * - T16 **reborn-loop regression**: an archive-only component (zero members in
 *   the active zone) is not born at all, so repeated ticks write zero rounds
 * - T17 the birth gate is narrow: a component with one active member still
 *   births and retires through the standard lifecycle
 *
 * **红验语义**（逐条钉死「把判据改坏 ⇒ 本测试必红」的变异）：
 * 每处 `// 变异:` 注释点名该断言对应哪一处判据；变异 = 把该判据改成永远不成立
 * （或永远成立），对应断言立即翻红。
 *
 * 🔴 本 spec **只做静态自审**（本批零构建，构建/测试腿归 `chainledger-verify`）。
 */
class ChainLedgerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val t0 = 1750000000000L

  /** 派生原型链（`FlowMapStore.topologicalChains` 的返回形态；本 spec 只给成员集） */
  private def proto(id: String, members: List[String]): ChainInfo =
    ChainInfo(id = id, memberIds = members)

  private def entry(
    id: String,
    anchor: String,
    bornAt: Long,
    members: List[String],
    status: String = ChainLedger.StatusActive,
    archivedAt: Option[Long] = None,
    refCount: Int = 0
  ): ChainLedger.Entry =
    ChainLedger.Entry(
      chainId = id,
      anchor = anchor,
      bornAt = bornAt,
      members = members,
      memberCount = members.size,
      status = status,
      archivedAt = archivedAt,
      refCount = refCount
    )

  private def aliasRow(alias: String, canonical: String, refCount: Int = 0): ChainLedger.AliasRow =
    ChainLedger.AliasRow(alias = alias, canonical = canonical, createdAt = t0, refCount = refCount)

  private def nd(
    id: String,
    createdAt: Long,
    status: String = NodeLifecycle.Completed,
    in: List[String] = Nil,
    out: List[OutEdge] = Nil
  ): NodeDef =
    NodeDef(
      id = id,
      name = s"name-$id",
      agent = "general",
      status = status,
      in = in,
      out = out,
      deps = Nil,
      createdAt = createdAt
    )

  private def changesOf(obs: ChainLedger.Observation): List[(String, Option[String], Option[String], String)] =
    obs.changes.map(c => (c.nodeId, c.from, c.to, c.reason))

  // ── T1 出生即定 + 承继不改号 ───────────────────────────────

  test("T1 出生即定 + 承继不改号：同一链成员生长，链号/锚点/出生时刻一律不动") {
    val obs1 = ChainLedger.observe(ChainLedger.State(project = "p"), List(proto("chain-a", List("a"))), t0)
    assertEquals(obs1.born, List("chain-a"), "首次观测 = 出生，链号 = 派生值（与批一逐字同值）")
    assertEquals(changesOf(obs1), Nil, "出生不记改号留痕（None→X 归批一写点）")
    assertEquals(obs1.state.entries("chain-a").anchor, "a")
    assertEquals(obs1.state.entries("chain-a").bornAt, t0)
    // 成员生长（链尾追加 + 换头）：链号不得变 —— 现状病根是「分量合并 ⇒ 并成最早者 id」
    val obs2 = ChainLedger.observe(obs1.state, List(proto("chain-a", List("b", "a", "c"))), t0 + 1000)
    assertEquals(obs2.born, Nil, "既有链再观测 = 承继，不得重新出生")
    assertEquals(changesOf(obs2), Nil, "承继不得改号")
    assertEquals(obs2.state.entries("chain-a").members, List("b", "a", "c"))
    assertEquals(obs2.state.entries("chain-a").bornAt, t0, "出生时刻不得被重算")
    assertEquals(obs2.state.entries("chain-a").anchor, "a", "锚点不得被重算（身份锚）")
  }

  // ── T2 合并 = 显式改号 + 别名 + 留痕 ───────────────────────

  test("T2 合并 = 显式改号 + 旧号保留别名 + 逐节点改号留痕（先出生者保号）") {
    val s1 = ChainLedger.observe(ChainLedger.State(project = "p"), List(proto("chain-a", List("a", "b"))), t0).state
    val s2 =
      ChainLedger.observe(s1, List(proto("chain-a", List("a", "b")), proto("chain-c", List("c", "d"))), t0 + 1000).state
    assertEquals(s2.entries.keySet, Set("chain-a", "chain-c"))
    // 两分量并成一条：派生原型 id = chain-a，但 chain-c 也在命中集 ⇒ 显式改号（不是静默重归）
    val obs3 = ChainLedger.observe(s2, List(proto("chain-a", List("a", "b", "c", "d"))), t0 + 2000)
    assertEquals(obs3.absorbed, List("chain-c"), "被吸收链号（先出生者链-a 保号）")
    assertEquals(obs3.state.entries.keySet, Set("chain-a"))
    assertEquals(obs3.state.aliases("chain-c").canonical, "chain-a", "旧号保留别名")
    assertEquals(ChainLedger.resolve(obs3.state, "chain-c"), Some("chain-a"), "旧号永久可达")
    assertEquals(
      changesOf(obs3),
      List(("c", Some("chain-c"), Some("chain-a"), "re-id"), ("d", Some("chain-c"), Some("chain-a"), "re-id")),
      "改号逐节点留痕（旧号/新号/原因）"
    )
    // 生还者链号不被改写（「永不重归」的可执行判据）
    assertEquals(obs3.state.entries("chain-a").bornAt, t0)
  }

  // ── T3 拆分 = 锚点保号 + 拆出部分新号 ─────────────────────

  test("T3 拆分 = 锚点所在分量保号 + 拆出分量出生新号 + 改号留痕") {
    val s1 =
      ChainLedger.observe(ChainLedger.State(project = "p"), List(proto("chain-a", List("a", "b", "c"))), t0).state
    val obs2 = ChainLedger.observe(s1, List(proto("chain-a", List("a")), proto("chain-b", List("b", "c"))), t0 + 1000)
    assertEquals(obs2.state.entries.keySet, Set("chain-a", "chain-b"), "锚点分量保原号 + 拆出分量新号")
    assertEquals(obs2.born, List("chain-b"))
    assertEquals(
      changesOf(obs2),
      List(("b", Some("chain-a"), Some("chain-b"), "re-id"), ("c", Some("chain-a"), Some("chain-b"), "re-id")),
      "拆出去的节点各记一条改号留痕"
    )
    assertEquals(obs2.state.entries("chain-a").members, List("a"))
  }

  // ── T4 离场 ⇒ 整行下沉（只归档不删除）─────────────────────

  test("T4 离场：链已不在图上 ⇒ 热面移除 + 全行交给冷档（只归档不删除）") {
    val s1 = ChainLedger.observe(ChainLedger.State(project = "p"), List(proto("chain-a", List("a", "b"))), t0).state
    val obs = ChainLedger.observe(s1, Nil, t0 + 1000)
    assertEquals(obs.state.entries.keySet, Set.empty[String], "禁留悬空条目")
    assertEquals(obs.dissolved.map(_.chainId), List("chain-a"))
    assertEquals(obs.dissolved.map(_.members), List(List("a", "b")), "全行下沉（载荷不丢）")
  }

  // ── T5 轴(a)×(b) 退役判据（红验）──────────────────────────

  test("T5 [轴(a)×(b) 红验] 退役判据 =「已归档/已离场 ∧ 零引用」：改坏任一支 ⇒ 本测试必红") {
    def st(status: String, ref: Int): ChainLedger.State =
      ChainLedger.State(
        project = "p",
        entries = Map(
          "chain-a" -> entry(
            "chain-a",
            "a",
            t0,
            List("a"),
            status = status,
            archivedAt = if status == ChainLedger.StatusArchived then Some(t0 + 1) else None,
            refCount = ref
          )
        )
      )

    // 变异: 把 planRetire 的 status 判据删掉（任何行都退）⇒ 下一条断言红
    assertEquals(
      ChainLedger.planRetire(st(ChainLedger.StatusArchived, 0), t0 + 9).entries.map(_.chainId),
      List("chain-a"),
      "已归档 ∧ 零引用 ⇒ 必须退役"
    )
    // 变异: 把 status 判据改成永远成立 ⇒ 本断言红（轴 a：活跃链的行不得退役）
    assertEquals(
      ChainLedger.planRetire(st(ChainLedger.StatusActive, 0), t0 + 9).entries,
      Nil,
      "轴(a) 生命周期绑定：未归档链的行**不得**退役（归档刻 = 最早退役窗口）"
    )
    // 变异: 把 refCount 判据删掉 ⇒ 本断言红（轴 b：有活引用就不退）
    assertEquals(
      ChainLedger.planRetire(st(ChainLedger.StatusArchived, 3), t0 + 9).entries,
      Nil,
      "轴(b) 引用计数：仍有活引用 ⇒ 不得退役（旧号永久可达）"
    )
    // 别名行随 canonical 同刻退 + 离场条目的别名行一并退（禁留悬空）
    val dangling: ChainLedger.State =
      ChainLedger.State(project = "p", aliases = Map("chain-old" -> aliasRow("chain-old", "chain-gone")))
    assertEquals(
      ChainLedger.planRetire(dangling, t0 + 9).aliases.map(_.alias),
      List("chain-old"),
      "canonical 已离场 ⇒ 别名行退役（不留悬空）"
    )
  }

  // ── T6 轴(b) 引用计数复算（红验）──────────────────────────

  test("T6 [轴(b) 红验] 覆盖式复算 + 减到 0 ⇒ 退役：把减计数改坏 ⇒ 本测试必红") {
    val st0 = ChainLedger.State(
      project = "p",
      entries = Map(
        "chain-a" -> entry("chain-a", "a", t0, List("a"), status = ChainLedger.StatusArchived, archivedAt = Some(t0))
      ),
      aliases = Map("chain-old" -> aliasRow("chain-old", "chain-a"))
    )

    val withBatch = ChainLedger.recomputeRefCounts(st0, ChainLedger.FaceCounts(batchIds = Set("chain-old")))
    assertEquals(withBatch.aliases("chain-old").refCount, 2, "面：声明 0 + 归档批 1 + 外部 0 + canonical 面 1")
    // 变异: 把面计数改成「恒 ≥1」⇒ 本断言红（有活引用不得退役）
    assertEquals(ChainLedger.planRetire(withBatch, t0 + 5).aliases, Nil, "有活引用 ⇒ 不退役")

    // 归档批被拉回（= 减计数写点）⇒ 该面归零
    val noBatch = ChainLedger.recomputeRefCounts(st0, ChainLedger.FaceCounts())
    assertEquals(noBatch.aliases("chain-old").refCount, 1, "只剩 canonical 面")
    // 变异: 把减计数（覆盖式复算里去掉批面）改坏 ⇒ 本断言红
    val orphan = noBatch.copy(entries = Map.empty) // canonical 条目也退役/离场
    val zeroed = ChainLedger.recomputeRefCounts(orphan, ChainLedger.FaceCounts())
    assertEquals(zeroed.aliases("chain-old").refCount, 0, "全部面归零")
    assertEquals(
      ChainLedger.planRetire(zeroed, t0 + 5).aliases.map(_.alias),
      List("chain-old"),
      "减到 0 ⇒ 退役动作（轴 b 的判据承重项）"
    )
    // 声明面 / 活动区面同为计数面（三个已接线面各自独立可减）
    val declared =
      ChainLedger.recomputeRefCounts(st0, ChainLedger.FaceCounts(declarations = Map("chain-old" -> Set("n1", "n2"))))
    assertEquals(declared.aliases("chain-old").refCount, 3, "声明面按载体数计（2）+ canonical 面 1")
    val live =
      ChainLedger.recomputeRefCounts(st0, ChainLedger.FaceCounts(activeMembers = Map("chain-a" -> Set("a", "b"))))
    assertEquals(live.entries("chain-a").refCount, 2, "活动区成员面按载体数计")
  }

  // ── T7 引用面枚举（红验）──────────────────────────────────

  test("T7 [轴(b) 面枚举红验] 七面在册 + 各带写点/增减时机；接线状态如实登记（删任一面 / 改 wired 标记 ⇒ 必红）") {
    // 变异: 从 ReferenceFaces 删任一面（或改 wired 标记而不动本断言）⇒ 本断言红
    assertEquals(
      ChainLedger.ReferenceFaces.map(_.id),
      List("live-member", "declaration", "archive-batch", "alias-target", "mail-usage", "board-usage", "report-usage"),
      "引用面枚举 = 判据正本；删面 = 静默缩小计数口径"
    )
    ChainLedger.ReferenceFaces.foreach { f =>
      assert(f.writePoint.trim.nonEmpty, s"face ${f.id} 缺「计引用写点」锚点（禁无据计数）")
      assert(f.incWhen.trim.nonEmpty, s"face ${f.id} 缺「何时加计数」")
      assert(f.decWhen.trim.nonEmpty, s"face ${f.id} 缺「何时减计数」")
      if !f.wired then assert(f.owner.trim.nonEmpty, s"未接线面 ${f.id} 必须带归属批次")
    }
    // 批三+ 外部三面（mail/board/report）接线落地 ⇒ 未接线面表必须为空（禁静默省略；
    // 禁以人工判断代替计数）。日后新增面若不接线，本断言随之红。
    assertEquals(ChainLedger.PendingFaceIds, Nil, "外部三面接线落地 ⇒ 空表（登记在册的缺口清单归零）")
    assertEquals(
      ChainLedger.WiredFaceIds,
      List("live-member", "declaration", "archive-batch", "alias-target", "mail-usage", "board-usage", "report-usage"),
      "已接线面集合 = 七面（批二四面 + 批三+ 三面；改任一面 wired 标记 ⇒ 本断言红）"
    )
  }

  // ── T8 轴(c) 双阈值触发（红验：台账无限增长）──────────────

  test("T8 [轴(c) 红验 · 台账无限增长] 条数 ∨ 字节越在册阈值 ⇒ 必触发压缩轮；阈值改成永不触发 ⇒ 必红") {
    // ① 条数面：无界增长输入（行数 > MaxHotRows）
    val rows = (0 until (ChainLedger.MaxHotRows + 4)).map { i =>
      s"chain-n$i" -> entry(
        s"chain-n$i",
        s"n$i",
        t0 + i,
        List(s"n$i"),
        status = ChainLedger.StatusArchived,
        archivedAt = Some(t0 + i)
      )
    }.toMap
    val many = ChainLedger.State(project = "p", entries = rows)
    val manyBytes = many.asJson.noSpaces.getBytes("UTF-8").length.toLong
    assert(
      ChainLedger.needsCompaction(many, manyBytes),
      s"条数越界必触发（rows=${ChainLedger.hotRows(many)} > ${ChainLedger.MaxHotRows}）"
    )
    // 变异: 把 MaxHotRows 放大到永不触发（如 Int.MaxValue）⇒ 本条断言红
    assertEquals(
      ChainLedger.planCompaction(many, manyBytes).compactedEntries.size,
      ChainLedger.CompactionBatchRows,
      "有界轮：单轮搬走上限行（不搬空全库 ⇒ 可观测可复跑）"
    )

    // ② 字节面：行数未越界、字节越界 ⇒ 同样必触发（双阈值是「∨」不是「∧」）
    //    样本量口径：80000 成员 × ~25B ≈ 2MB > MaxHotBytes(1MiB)（2× 余量）
    val fat = ChainLedger.State(
      project = "p",
      entries = Map(
        "chain-fat" -> entry(
          "chain-fat",
          "f0",
          t0,
          (0 until 80000).map(j => s"fat-node-member-$j").toList,
          status = ChainLedger.StatusArchived,
          archivedAt = Some(t0)
        )
      )
    )
    val fatBytes = fat.asJson.noSpaces.getBytes("UTF-8").length.toLong
    assert(ChainLedger.hotRows(fat) <= ChainLedger.MaxHotRows, "字节面样本：行数未越界")
    assert(fatBytes > ChainLedger.MaxHotBytes, s"字节面样本须越界（$fatBytes > ${ChainLedger.MaxHotBytes}）")
    assert(ChainLedger.needsCompaction(fat, fatBytes), "字节越界必触发")
    // 变异: 把 MaxHotBytes 放大到永不触发 ⇒ 本条断言红
    assertEquals(
      ChainLedger.ThresholdsNow,
      ChainLedger.Thresholds(ChainLedger.MaxHotRows, ChainLedger.MaxHotBytes, ChainLedger.CompactionBatchRows),
      "阈值在册（随每轮冷档留痕的那组值 = 常量现值）"
    )
  }

  // ── T9 轴(c) 端到端：压缩轮落冷档 + 身份不重归 + 自检 ────────

  test("T9 轴(c) 端到端：越阈值 ⇒ 压缩轮落冷档 + 自检 Right + 压缩后链号不重归") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-c-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    // 无界增长输入：MaxHotRows + 8 条单成员链（一次 reconcile 全部出生 ⇒ 行数越界）
    val comps = (0 until (ChainLedger.MaxHotRows + 8)).toList.map(i => proto(s"chain-n$i", List(s"n$i")))
    for
      store <- ChainLedgerStore.open("ledger-c", path, arch)
      obs <- store.reconcile(comps, comps.flatMap(_.memberIds).toSet, Map.empty, Set.empty, t0)
      st <- store.snapshot
      rounds <- store.readRounds
      check <- store.verify
    yield
      assert(obs.capExceeded, "越阈值信号必须置位（判据承重项）")
      assert(obs.compaction.isDefined, "越阈值 ⇒ 压缩轮必须发生（不是只记信号）")
      assertEquals(rounds.size, 1, "一轮恰一个冷档文件")
      assertEquals(rounds.head.kind, ChainLedger.RoundCompact)
      assertEquals(rounds.head.entries.size, ChainLedger.CompactionBatchRows, "有界轮：单轮搬走上限行")
      assertEquals(check, Right(()): Either[String, Unit], "轮守恒 + 镜像 + 结构三查全过（可复算）")
      // 压缩后：身份仍在热面（anchor/成员读数/状态不动），载荷在冷档
      val compacted = st.entries.values.filter(_.compacted)
      assertEquals(compacted.size, ChainLedger.CompactionBatchRows)
      compacted.foreach { e =>
        assert(e.members.isEmpty, s"${e.chainId} 载荷应已下沉")
        assert(e.anchor.nonEmpty, s"${e.chainId} 锚点必须留热（身份不随压缩变化）")
        assertEquals(e.memberCount, 1, "成员读数不随压缩变化")
      }
      // 压缩后同分量再观测 ⇒ 按锚点命中 ⇒ 承继同号（**不重归**）
      val again = ChainLedger.observe(st, List(comps.head), t0 + 9999)
      assertEquals(again.born, Nil, "压缩不得诱发重新出生")
      assertEquals(again.state.entries(comps.head.id).chainId, comps.head.id)
    end for
  }

  // ── T10 落盘面：原子写 / 载入 / 重启自洽 / 损坏容忍 ──────────

  test("T10 落盘面：原子写 + 启动期载入 + 重启后台账逐字一致 + 损坏文件只 WARN 不崩") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-io-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    for
      store <- ChainLedgerStore.open("ledger-io", path, arch)
      obs <- store.reconcile(List(proto("chain-a", List("a", "b"))), Set("a", "b"), Map.empty, Set.empty, t0)
      st1 <- store.snapshot
      onDisk <- IO.blocking(os.exists(path))
      // 重启：同目录再开（启动期载入）
      reopened <- ChainLedgerStore.open("ledger-io", path, arch)
      st2 <- reopened.snapshot
      check2 <- reopened.verify
      // 损坏 ⇒ 空账起步（可重建，不阻启动、绝不静默——WARN 由 store 自己记）
      _ <- IO.blocking(os.write.over(path, "{ this is not json"))
      broken <- ChainLedgerStore.open("ledger-io", path, arch)
      st3 <- broken.snapshot
    yield
      assertEquals(obs.born, List("chain-a"))
      assert(onDisk, "台账必须在盘（与 flow-map.json 同目录同生命周期）")
      assertEquals(st2, st1, "重启后台账逐字一致")
      assertEquals(check2, Right(()): Either[String, Unit], "重启后自检 Right（崩溃/重启后自洽）")
      assertEquals(st3.entries, Map.empty[String, ChainLedger.Entry], "损坏 ⇒ 空账起步")
    end for
  }

  // ── T11 集成：FlowMapStore 拍点接线 ────────────────────────

  test("T11 集成：出生 → 合并显式改号（别名）+ 轴(a) 归档绑定同刻翻 archived") {
    val ws = os.temp.dir(prefix = "nb-chain-ledger-int-", deleteOnExit = false)
    for
      store <- FlowMapStore.open("ledger-int", ws.toString)
      _ <- store.mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "p" -> nd("p", t0, out = List(OutEdge("q"))),
            "q" -> nd("q", t0 + 1, in = List("p")),
            "r" -> nd("r", t0 + 2)
          )
        )
      )
      c1 <- store.reconcileChainLedger(t0 + 100)
      st1 <- store.chainLedgerStore.snapshot
      // 接线 p→q→r：两个分量并成一条 ⇒ 台账须显式改号（chain-r 被吸收为别名）
      _ <- store.mutate(s => s.copy(nodes = s.nodes.updated("r", s.nodes("r").copy(in = List("q")))))
      c2 <- store.reconcileChainLedger(t0 + 200)
      st2 <- store.chainLedgerStore.snapshot
      oldId <- store.chainLedgerStore.resolve("chain-r")
      // 全链终态 ⇒ sweep 出库：轴(a) 绑定必须同刻把台账条目翻 archived
      _ <- store.mutate(s =>
        s.copy(nodes = s.nodes.map { case (k, n) =>
          k -> n.copy(status = NodeLifecycle.Completed, completedAt = Some(t0 + 300))
        })
      )
      removed <- store.sweepCompletedChains(t0 + 400)
      st3 <- store.chainLedgerStore.snapshot
      _ <- store.reconcileChainLedger(t0 + 500)
      st4 <- store.chainLedgerStore.snapshot
      deepP <- store.chainLedgerStore.resolveDeep("chain-p")
      deepR <- store.chainLedgerStore.resolveDeep("chain-r")
      check <- store.chainLedgerStore.verify
    yield
      assertEquals(c1, Nil, "首次观测 = 出生，无改号留痕")
      assertEquals(st1.entries.keySet, Set("chain-p", "chain-r"), "两条分量各自出生")
      assertEquals(
        c2.map(c => (c.nodeId, c.from, c.to, c.reason)),
        List(("r", Some("chain-r"), Some("chain-p"), "re-id")),
        "合并 ⇒ 显式改号 + 逐节点留痕"
      )
      assertEquals(st2.aliases.keySet, Set("chain-r"), "旧号保留别名")
      assertEquals(oldId, Some("chain-p"), "旧号永久可达（解析单点）")
      assertEquals(removed.toSet, Set("p", "q", "r"), "整链出库")
      assertEquals(st3.entries("chain-p").status, ChainLedger.StatusArchived, "轴(a)：出库同刻翻 archived")
      assertEquals(st3.entries("chain-p").archivedAt, Some(t0 + 400))
      // R3 续批（活性别闸）：出库后节点全在归档区 ⇒ 分量非活 ⇒ 条目走**离场臂**（不再被
      // 归档区成员钉活）。批面引用仍在（refCount>0）⇒ 行**不经退役臂**（退役窗没开）——
      // 它是「图已不在」而下场，与「有活引用就不退」的轴(a)×(b) 判据不冲突。
      assert(st4.entries.isEmpty, "图空 ⇒ 条目离场（归档区成员不再钉活）")
      assertEquals(
        st4.pendingDissolve.entries.map(_.chainId),
        List("chain-p"),
        "离场行入待落批（批化落盘；批面引用保留在行上 = 未经退役臂）"
      )
      assert(st4.pendingDissolve.entries.headOption.exists(_.refCount > 0), "批面活引用如实保留（退役窗未开）")
      assertEquals(deepP, Some("chain-p"), "待落批内的行照旧可达（pending 回翻）")
      assertEquals(deepR, Some("chain-p"), "旧号在待落批窗口内照旧可达（pending 回翻）")
      assertEquals(check, Right(()): Either[String, Unit], "集成态下台账自检 Right")
    end for
  }

  // ── T12 解析：别名链 + 冷档兜底 ───────────────────────────

  test("T12 解析单点：热面别名逐跳解析；冷档下沉行由 resolveDeep 兜底（旧号永久可达）") {
    val st = ChainLedger.State(
      project = "p",
      entries = Map("chain-b" -> entry("chain-b", "b", t0, List("b"))),
      aliases = Map("chain-a" -> aliasRow("chain-a", "chain-b"), "chain-a0" -> aliasRow("chain-a0", "chain-a"))
    )
    assertEquals(ChainLedger.resolve(st, "chain-a0"), Some("chain-b"), "两跳别名解析到现号")
    assertEquals(ChainLedger.resolve(st, "chain-zzz"), None, "无解返回 None（不猜）")
    val dir = os.temp.dir(prefix = "nb-chain-ledger-resolve-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    for
      store <- ChainLedgerStore.open("ledger-resolve", path, arch)
      obs <- store.reconcile(List(proto("chain-b", List("b"))), Set("b"), Map.empty, Set.empty, t0)
      _ = assertEquals(obs.born, List("chain-b"))
      hot <- store.resolve("chain-b")
      cold <- store.resolveDeep("chain-b")
      missing <- store.resolveDeep("chain-zzz")
    yield
      assertEquals(hot, Some("chain-b"))
      assertEquals(cold, Some("chain-b"), "热面未命中回落冷档（本形态热面即命中 —— 冷档面同款可达）")
      assertEquals(missing, None)
  }

  // ── T13/T14 轮间链式（同拍 / 跨拍）红验：返工位修 D1（先例 D1-a/D1-b/D1-c）──
  // 判据：轮间链式（`k.hotBefore == (k-1).hotAfter`）**只对同拍相邻轮**成立。
  //  - 同拍内：各轮坐标同源（`hotAfter(k) == hotBefore(k+1)` 结构性成立）⇒ 必 Right；
  //  - 跨拍：出生 / 离场 / 计数复算发生在轮之外 ⇒ 读数合法不同 ⇒ 不得判为链断
  //    （否则该假警告因轮 append-only 而**永不消解**：每次启动 WARN 一次「台账不自洽」）。

  test("T13 [轮间链式红验 · 同拍] dissolve 批 flush + compact 同拍 ⇒ 拍内链式成立（verify=Right）；坐标改回复算前 ⇒ 必红") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-sametick-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    val all = (0 until ChainLedger.MaxHotRows).toList.map(i => proto(s"chain-n$i", List(s"n$i")))
    val fresh = (0 until 8).toList.map(i => proto(s"chain-f$i", List(s"f$i")))
    val tick2 = all.drop(4)
    val ids2 = tick2.flatMap(_.memberIds).toSet
    val tick3 = tick2 ++ fresh
    val ids3 = tick3.flatMap(_.memberIds).toSet
    for
      store <- ChainLedgerStore.open("ledger-sametick", path, arch)
      _ <- store.reconcile(all, all.flatMap(_.memberIds).toSet, Map.empty, Set.empty, t0)
      // 拍 2：4 条链离场 ⇒ 入**待落批**（批化落盘：小批不落轮）；读数 4092 < 阈值 ⇒ 无 compact。
      o2 <- store.reconcile(tick2, ids2, Map.empty, Set.empty, t0 + 1000)
      st2 <- store.snapshot
      // 拍 3（龄期已过 + 8 条新生越条数阈值）：dissolve 批 flush 与 compact **同拍**两轮
      // （先例 D1-a 形态在批化落盘下的等价形态）。
      o3 <- store.reconcile(tick3, ids3, Map.empty, Set.empty, t0 + 1000 + ChainLedger.OfflineFlushAgeMs + 1000)
      st3 <- store.snapshot
      check3 <- store.verify
      _ <- store.reconcile(tick3, ids3, Map.empty, Set.empty, t0 + 3000 + ChainLedger.OfflineFlushAgeMs)
      st4 <- store.snapshot
      check4 <- store.verify
    yield
      assertEquals(
        o2.dissolved.map(_.chainId).sorted,
        List("chain-n0", "chain-n1", "chain-n2", "chain-n3"),
        "拍 2：4 条链离场（整行下沉）"
      )
      assertEquals(st2.rounds, Nil, "小批不落轮（批化落盘；批持久在台账热态里）")
      assertEquals(st2.pendingDissolve.entries.map(_.chainId).sorted, List("chain-n0", "chain-n1", "chain-n2", "chain-n3"), "离场行入待落批")
      assertEquals(
        st3.rounds.map(_.kind),
        List(ChainLedger.RoundDissolve, ChainLedger.RoundCompact),
        "拍 2/拍 3 前段安静（批化 + 阈值未越）；拍 3 = dissolve 批 flush + compact 同拍两轮（先例 D1-a 形态）"
      )
      assert(st3.rounds.forall(_.tick > 0), "每轮必须带拍标识（旧账 tick=0 ⇒ 该查不可判）")
      assertEquals(st3.rounds(0).tick, st3.rounds(1).tick, "同拍各轮同一拍标识")
      // 变异: 把 dissolve 轮坐标改回 `stObs`（计数复算**之前**）⇒ 本条与 check3 立即红
      assertEquals(
        st3.rounds(0).hotAfter,
        st3.rounds(1).hotBefore,
        "拍内链式承重项：dissolve.hotAfter 必须逐项等于 compact.hotBefore（同源坐标）"
      )
      assertEquals(st3.rounds(0).movedEntries, 4, "dissolve 批整批落轮（攒了拍 2 的 4 行）")
      assertEquals(check3, Right(()): Either[String, Unit], "同拍多轮 ⇒ 台账自洽（判据 1「崩溃/重启后自洽」+ 判据 5(c) 的**多轮**形态）")
      assert(st4.rounds.size > st3.rounds.size, "后续拍继续出轮（append-only；读数仍越阈值 ⇒ compact 继续）")
      // 变异: 把坐标错位改回（或恢复跨拍对账）⇒ check4 必红（先例 D1「假警告永不消解」）
      assertEquals(check4, Right(()): Either[String, Unit], "轮 append-only ⇒ 假链断禁出现且不得留痕")
    end for
  }

  test("T14 [轮间链式红验 · 跨拍] 两拍各出一轮 compact、其间仅有普通出生 ⇒ verify=Right（禁跨拍对账）") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-crosstick-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    val base = (0 until 5000).toList.map(i => proto(s"chain-n$i", List(s"n$i")))
    val grown = base ++ (0 until 10).toList.map(i => proto(s"chain-b$i", List(s"b$i")))
    for
      store <- ChainLedgerStore.open("ledger-crosstick", path, arch)
      o1 <- store.reconcile(base, base.flatMap(_.memberIds).toSet, Map.empty, Set.empty, t0)
      st1 <- store.snapshot
      check1 <- store.verify
      o2 <- store.reconcile(grown, grown.flatMap(_.memberIds).toSet, Map.empty, Set.empty, t0 + 1000)
      st2 <- store.snapshot
      check2 <- store.verify
    yield
      assert(o1.compaction.isDefined && o2.compaction.isDefined, "两拍各出一轮压缩（其间仅有普通出生，无 dissolve）")
      assertEquals(st1.rounds.map(_.kind), List(ChainLedger.RoundCompact), "拍 1 单轮")
      assertEquals(check1, Right(()): Either[String, Unit], "拍 1 自洽（单轮时链式查本不触发）")
      assertEquals(
        st2.rounds.map(_.kind),
        List(ChainLedger.RoundCompact, ChainLedger.RoundCompact),
        "两轮皆 compact（先例 D1-c：**无 dissolve 参与**，可达性最强）"
      )
      assert(st2.rounds(0).tick < st2.rounds(1).tick, "跨拍 ⇒ 拍标识必异")
      // 变异: 删掉 verifyLedger ② 查的同拍条件（恢复「与上一轮 hotAfter 对账」）⇒ 下两条必红
      assert(
        st2.rounds(0).hotAfter != st2.rounds(1).hotBefore,
        "跨拍读数差（其间出生 10 条 ⇒ entries 5000→5010）—— 这是**合法**变化，禁当链断"
      )
      assertEquals(check2, Right(()): Either[String, Unit], "跨拍读数差不得判为链断（否则该假警告因轮 append-only 而永不消解）")
    end for
  }

  // ── T15 open-time cold-archive audit budget ────────────────
  // The cold archive is append-only and unbounded; the mount-time self-check
  // must not read it in full at every store open (startup memory stays
  // bounded). `verify` remains the explicit full-audit entrypoint, and
  // `resolveDeep` keeps old numbers reachable via a lazy per-file scan.

  test(
    "T15 open-time cold audit is budget-bounded: over-budget open skips the cold read; resolveDeep stays lazy-correct; explicit verify still audits"
  ) {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-budget-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    for
      // Seed one dissolve round into the cold archive via the normal reconcile
      // path: the row first lands in the offline pending batch (batched flush),
      // then a tick past the flush age sinks the whole batch as one round.
      seeded <- ChainLedgerStore.open("ledger-budget", path, arch)
      _ <- seeded.reconcile(List(proto("chain-a", List("a"))), Set("a"), Map.empty, Set.empty, t0)
      obs <- seeded.reconcile(Nil, Set.empty, Map.empty, Set.empty, t0 + 1000)
      _ = assertEquals(obs.dissolved.map(_.chainId), List("chain-a"))
      stPending <- seeded.snapshot
      _ = assertEquals(
        stPending.pendingDissolve.entries.map(_.chainId),
        List("chain-a"),
        "small batch stays pending (batched flush, no per-event file)"
      )
      flushed <- seeded.reconcile(Nil, Set.empty, Map.empty, Set.empty, t0 + 1000 + ChainLedger.OfflineFlushAgeMs + 1000)
      _ = assertEquals(flushed.state.rounds.map(_.kind), List(ChainLedger.RoundDissolve), "age gate flushes the batch as one round")
      coldFiles <- IO.blocking(if os.exists(arch) then os.list(arch).toList else Nil)
      _ = assert(coldFiles.nonEmpty, "dissolve round must be on disk")

      // Over-budget open (budget = 1 byte): the mount-time self-check skips the
      // cold read; the store still opens, and old numbers stay reachable.
      skipped <- ChainLedgerStore.open("ledger-budget", path, arch, coldReadBudgetBytes = 1L)
      hotAfterSkip <- skipped.snapshot
      resolved <- skipped.resolveDeep("chain-a")
      missing <- skipped.resolveDeep("chain-zzz")

      // Under-budget open (default): the audit path still runs, and the explicit
      // full audit over the archive stays available and clean.
      audited <- ChainLedgerStore.open("ledger-budget", path, arch)
      check <- audited.verify
    yield
      assertEquals(hotAfterSkip.entries.keySet, Set.empty[String], "hot state loads normally (row retired to cold)")
      assertEquals(resolved, Some("chain-a"), "old number reachable via the lazy cold scan")
      assertEquals(missing, None, "unresolvable ids stay unresolvable (no guessing)")
      assertEquals(check, Right(()): Either[String, Unit], "explicit verify still audits the full archive")
    end for
  }

  // ── T16/T17 the birth gate + the offline arm (active-zone awareness) ─────────
  //
  // Retire removes the hot entry observe matches on, so an archive-only
  // component used to re-birth every tick: birth -> born-archived flip ->
  // retire, one fresh round file per project per tick, forever. The birth
  // gate in `ChainLedger.observe` skips candidates with zero members in the
  // active zone; these tests pin the loop shut and the gate's narrowness.
  //
  // R3 续批（活性别闸）：闸后归档区分量对条目**零提名权** ⇒ 那些行走**离场臂**
  // （dissolve），不再走退役臂（retire）—— sinking 仍是「恰一次 + 零风暴」，只是
  // 臂名与批化落盘的时机变了（行先入待落批，达龄/达量整批落一轮）。

  test("T16 archive-only component is never re-born: repeated ticks write zero rounds (reborn-loop regression)") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-reborn-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    for
      store <- ChainLedgerStore.open("ledger-reborn", path, arch)
      // Birth with the members in the active zone, then archive-bind the chain
      // (the sweep's same-tick write point) so the hot row reads Archived.
      born <- store.reconcile(List(proto("chain-r", List("r1", "r2"))), Set("r1", "r2"), Map.empty, Set.empty, t0)
      _ = assertEquals(born.born, List("chain-r"))
      _ <- store.onChainsArchived(Set("chain-r"), Set("r1", "r2"), t0 + 10)
      // Settling tick: the component is still on the combined map but the
      // active zone is empty — the row leaves via the OFFLINE arm into the
      // pending batch (reference faces are irrelevant to graph presence).
      settled <- store.reconcile(List(proto("chain-r", List("r1", "r2"))), Set.empty, Map.empty, Set.empty, t0 + 20)
      stAfterSettle <- store.snapshot
      // The two ticks that used to re-birth the chain (one fresh round file
      // per tick before the gate): they must write nothing at all now.
      tick3 <- store.reconcile(List(proto("chain-r", List("r1", "r2"))), Set.empty, Map.empty, Set.empty, t0 + 30)
      tick4 <- store.reconcile(List(proto("chain-r", List("r1", "r2"))), Set.empty, Map.empty, Set.empty, t0 + 40)
      roundsQuiet <- store.readRounds
      // One tick past the flush age: the pending batch sinks as exactly ONE
      // dissolve round carrying the full row.
      flushed <- store.reconcile(List(proto("chain-r", List("r1", "r2"))), Set.empty, Map.empty, Set.empty, t0 + 20 + ChainLedger.OfflineFlushAgeMs + 1000)
      roundsEnd <- store.readRounds
      filesEnd <- IO.blocking(if os.exists(arch) then os.list(arch).toList else Nil)
    yield
      assertEquals(settled.retired, 0, "the retire arm never opens (offline arm owns graph-gone rows)")
      assertEquals(settled.state.pendingDissolve.entries.map(_.chainId), List("chain-r"), "the row lands in the offline pending batch")
      assertEquals(stAfterSettle.entries.keySet, Set.empty[String], "the drained row has left the hot face")
      assertEquals(tick3.born, Nil, "an archive-only component must not re-birth (the loop's birth arm)")
      assertEquals(tick4.born, Nil, "and it stays unborn on every following tick")
      assertEquals(roundsQuiet, Nil, "quiet ticks write zero rounds (no per-event file for a small batch)")
      assertEquals(flushed.state.rounds.map(_.kind), List(ChainLedger.RoundDissolve), "the age gate flushes exactly one dissolve round")
      assertEquals(flushed.state.rounds.map(_.movedEntries), List(1), "the round carries the full row")
      assertEquals(flushed.state.rounds.map(_.round), List(1), "exactly one round ever (the per-tick storm is gone)")
      assertEquals(filesEnd.count(_.last.startsWith("round-")), 1, "zero extra cold round files on repeated ticks")
      assert(filesEnd.exists(_.last == ChainLedger.RoundsSideVolumeFile), "the manifest side volume rides along")
    end for
  }

  test("T17 the birth gate is narrow: one active member still births and inherits normally") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-gate-narrow-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    for
      store <- ChainLedgerStore.open("ledger-gate", path, arch)
      // One member active, one already outside the zone: >= 1 active member
      // births exactly as before, and the born-archived pass must NOT flip it.
      mixed <- store.reconcile(List(proto("chain-m", List("m1", "m2"))), Set("m1"), Map.empty, Set.empty, t0)
      stBorn <- store.snapshot
      _ <- store.onChainsArchived(Set("chain-m"), Set("m1", "m2"), t0 + 10)
      // The active zone empties: the component becomes archive-only (zero
      // nomination power) and the row drains via the offline arm.
      drained <- store.reconcile(List(proto("chain-m", List("m1", "m2"))), Set.empty, Map.empty, Set.empty, t0 + 20)
      stDrained <- store.snapshot
      // And the following tick stays silent (no rebirth, no round).
      next <- store.reconcile(List(proto("chain-m", List("m1", "m2"))), Set.empty, Map.empty, Set.empty, t0 + 30)
      roundsEnd <- store.readRounds
      stEnd <- store.snapshot
    yield
      assertEquals(mixed.born, List("chain-m"), "a component with one active member births normally")
      assertEquals(stBorn.entries("chain-m").status, ChainLedger.StatusActive, "a born entry with an active member is not flipped Archived")
      assertEquals(drained.state.pendingDissolve.entries.map(_.chainId), List("chain-m"), "the fully-archived chain drains via the offline arm")
      assertEquals(stDrained.entries.keySet, Set.empty[String], "the hot face drains")
      assertEquals(next.born, Nil, "after the drain the archive-only component stays unborn")
      assertEquals(roundsEnd, Nil, "no round on the following tick (batch pending, thresholds not met)")
      assertEquals(stEnd.pendingDissolve.entries.map(_.chainId), List("chain-m"), "the batch persists across ticks until a flush gate fires")
    end for
  }
  // ── T18–T21 [R3 续批 2026-10-30]：活性别闸（账实修复）+ 离场批化 + manifest 外置 + 冷档透明读 ──

  /**
   * T18 —— **Stand 项目面僵尸形态的逐字复刻**（审计 §3.4-② 的项目面读数：active=4、
   * 图空、锚点躺在归档区）。闸前的病灶：归档区成员把分量恒常派生出来 ⇒ 承继臂每拍命中
   * ⇒ 永不离场。闸后：图空 ⇒ 4 条全部走离场臂入待落批；**重启后台不丢**（批持久在台账
   * 热态里 = 崩溃安全）；龄期到 ⇒ 整批一轮落冷档（gzip 单文件）；冷号照旧可达；后续拍
   * 零新轮（不复活、不风暴）。
   */
  test("T18 [活性别闸] 归档区锚点 + 空图 ⇒ 僵尸 active 行清账：批跨重启持久 + 龄期整批落轮 + 冷号可达") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-zombie-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    // Stand 实测形态：compacted 行（members 已清、锚点在归档区）、status 钉在 active、
    // refCount 被「只计不减」的外部面与归档批面钉住。
    def zombie(id: String, anchor: String, refs: Int, status: String): ChainLedger.Entry =
      entry(id, anchor, t0, Nil).copy(
        compacted = true,
        refCount = refs,
        status = status,
        archivedAt = if status == ChainLedger.StatusArchived then Some(t0 - 1000) else None,
        memberCount = 3
      )
    val st0 = ChainLedger.State(
      project = "stand",
      updatedAt = t0,
      entries = Map(
        "chain-n-70916824" -> zombie("chain-n-70916824", "n-70916824", 1, ChainLedger.StatusActive),
        "chain-n-fb990b2f" -> zombie("chain-n-fb990b2f", "n-fb990b2f", 4, ChainLedger.StatusActive),
        "chain-n-527b1b92" -> zombie("chain-n-527b1b92", "n-527b1b92", 1, ChainLedger.StatusArchived),
        "zcode-484-fix" -> zombie("zcode-484-fix", "n-354ae3f5", 0, ChainLedger.StatusActive)
      ),
      externalRefs = Map("chain-n-fb990b2f" -> 4)
    )
    os.write.over(path, st0.asJson.noSpaces, createFolders = true)
    val comps = List(
      proto("chain-n-70916824", List("n-70916824")),
      proto("chain-n-fb990b2f", List("n-fb990b2f")),
      proto("chain-n-527b1b92", List("n-527b1b92")),
      proto("zcode-484-fix", List("n-354ae3f5"))
    )
    for
      store <- ChainLedgerStore.open("stand", path, arch)
      obs <- store.reconcile(comps, Set.empty, Map.empty, Set.empty, t0 + 1000)
      st1 <- store.snapshot
      // 重启（崩溃安全面）：待落批随台账回来。
      reopened <- ChainLedgerStore.open("stand", path, arch)
      st2 <- reopened.snapshot
      // 龄期到 ⇒ 整批一轮（4 行一个文件）。
      flushed <- reopened.reconcile(comps, Set.empty, Map.empty, Set.empty, t0 + 1000 + ChainLedger.OfflineFlushAgeMs + 1000)
      _ = assertEquals(flushed.state.rounds.map(_.kind), List(ChainLedger.RoundDissolve), "龄期闸 ⇒ 整批落一轮")
      st3 <- reopened.snapshot
      deepZ <- reopened.resolveDeep("zcode-484-fix")
      deepF <- reopened.resolveDeep("chain-n-fb990b2f")
      // 安静拍：零新轮、零复活。
      quiet <- reopened.reconcile(comps, Set.empty, Map.empty, Set.empty, t0 + 3000 + ChainLedger.OfflineFlushAgeMs)
      _ = assertEquals(quiet.born, Nil, "后续拍零复活")
      st4 <- reopened.snapshot
      check <- reopened.verify
    yield
      assertEquals(obs.born, Nil, "空图下零出生（出生闸）")
      assertEquals(st1.entries.keySet, Set.empty[String], "4 条僵尸 active 全部离场（归档区锚点不再钉活）")
      assertEquals(st1.pendingDissolve.entries.map(_.chainId).sorted, st0.entries.keySet.toList.sorted, "全部入待落批（批化落盘）")
      assertEquals(st2.pendingDissolve.rowCount, 4, "重启后待落批原样回来（批持久在台账热态里）")
      assertEquals(st3.rounds.map(_.kind), List(ChainLedger.RoundDissolve), "龄期闸 ⇒ 整批落一轮")
      assertEquals(st3.rounds.head.movedEntries, 4, "一轮带走全部 4 行")
      assertEquals(st3.pendingDissolve.rowCount, 0, "落轮后批清空")
      assertEquals(deepZ, Some("zcode-484-fix"), "冷号从落轮文件可达（gzip 透明读）")
      assertEquals(deepF, Some("chain-n-fb990b2f"), "被外部面计过数的冷号照旧可达")
      assertEquals(quiet.born, Nil, "后续拍零复活")
      assertEquals(st4.rounds.size, 1, "后续拍零新轮（逐拍风暴就此断根）")
      assertEquals(check, Right(()): Either[String, Unit], "落轮后台账自洽")
    end for
  }

  /**
   * T19 —— **批量化 flush 的行数闸**：批达 [[ChainLedger.OfflineFlushRows]] ⇒ **同一拍**
   * 整批落轮（不等龄期）；轮坐标守恒（verify Right）；落轮后批清空、热面清空。
   */
  test("T19 [批化落盘] 行数闸同拍 flush：600 行离场 = 一轮带走 + 守恒自洽") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-rowsgate-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    val all = (0 until 600).toList.map(i => proto(s"chain-r$i", List(s"m$i")))
    for
      store <- ChainLedgerStore.open("ledger-rowsgate", path, arch)
      _ <- store.reconcile(all, all.flatMap(_.memberIds).toSet, Map.empty, Set.empty, t0)
      obs <- store.reconcile(Nil, Set.empty, Map.empty, Set.empty, t0 + 1000)
      st1 <- store.snapshot
      files <- IO.blocking(os.list(arch).toList)
      check <- store.verify
      st2 <- store.snapshot
    yield
      assertEquals(obs.state.rounds.map(_.kind), List(ChainLedger.RoundDissolve), "行数闸 ⇒ 吸收的同一拍落轮")
      assertEquals(obs.state.rounds.head.movedEntries, 600, "整批落轮（不按行截断）")
      assertEquals(st1.pendingDissolve.rowCount, 0, "落轮后批清空")
      assertEquals(st1.entries.keySet, Set.empty[String], "热面清空")
      assertEquals(files.count(_.last.startsWith("round-")), 1, "600 行 = 一个冷档文件（不是 600 个）")
      assertEquals(check, Right(()): Either[String, Unit], "轮坐标守恒自洽")
      assertEquals(st2.entries.isEmpty, true)
    end for
  }

  /**
   * T20 —— **manifest 外置**：折叠后 checkpoint 只携带尾部窗口（≤500 条），完整史在旁卷；
   * 重启按轮号合并还原全量；新轮号跨重启续排。
   */
  test("T20 [manifest 外置] checkpoint 尾部窗口 + 旁卷全量 + 重启合并还原") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-window-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    // 轮生成器：每「离场拍 + 龄期拍」产一轮（1 行/轮），rotateBytes 调小让每次追加都折叠。
    val total = 505
    val all = (0 until total).toList.map(i => proto(s"chain-w$i", List(s"w$i")))
    def tickN(n: Int) = t0 + n * (ChainLedger.OfflineFlushAgeMs + 2000)
    for
      store <- ChainLedgerStore.open("ledger-window", path, arch, rotateBytes = 4096L)
      _ <- store.reconcile(all, all.flatMap(_.memberIds).toSet, Map.empty, Set.empty, t0)
      _ <-
        (0 until total).foldLeft(IO.unit) { (acc, i) =>
          acc *> store
            .reconcile(all.drop(i + 1), all.drop(i + 1).flatMap(_.memberIds).toSet, Map.empty, Set.empty, tickN(2 * i + 1))
            .flatMap(_ =>
              store.reconcile(
                all.drop(i + 1),
                all.drop(i + 1).flatMap(_.memberIds).toSet,
                Map.empty,
                Set.empty,
                tickN(2 * i + 2)
              )
            )
            .void
        }
      st1 <- store.snapshot
      checkpointText <- IO.blocking(os.read(path))
      checkpoint = io.circe.parser
        .parse(checkpointText)
        .flatMap(_.as[ChainLedger.State])
        .toOption
        .getOrElse(throw new IllegalStateException("checkpoint undecodable"))
      sideLines <- IO.blocking(os.read(arch / ChainLedger.RoundsSideVolumeFile).linesIterator.toList.filter(_.trim.nonEmpty))
      reopened <- ChainLedgerStore.open("ledger-window", path, arch)
      st2 <- reopened.snapshot
      check <- reopened.verify
    yield
      assertEquals(st1.rounds.size, total, "内存态全量轮清单（append-only）")
      assert(
        checkpoint.rounds.size <= ChainLedger.CheckpointRoundsWindow,
        s"checkpoint 只携带尾部窗口（实带 ${checkpoint.rounds.size}）"
      )
      assert(
        checkpoint.roundsTotal >= checkpoint.rounds.size && checkpoint.roundsTotal <= total,
        s"水位 = 最后一次折叠时刻的全量计数（允许落后未折叠尾巴：实值 ${checkpoint.roundsTotal}）"
      )
      assertEquals(sideLines.size, total, "旁卷 = 完整 manifest 史（一行一条）")
      assertEquals(st2.rounds.size, total, "重启按轮号合并还原全量")
      assertEquals(check, Right(()): Either[String, Unit], "合并后的全量史对每轮冷档自洽")
    end for
  }

  /**
   * T21 —— **冷档透明读**：JSONL 束（gzip）+ 单轮 gzip 文件混挂，readRounds 全收、
   * resolveDeep 穿束命中；无 manifest 的野文件不进守恒审计（manifest 驱动不变）。
   */
  test("T21 [冷档透明读] JSONL.gz 束 + 单轮 .json.gz 混挂：readRounds 全收 + resolveDeep 穿束命中") {
    val dir = os.temp.dir(prefix = "nb-chain-ledger-bundle-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    // 手搓一个束文件（一行一 RoundFile，gzip）——存量迁移工具的产物形态。
    def rf(round: Int, ids: List[String]): ChainLedger.RoundFile =
      ChainLedger.RoundFile(
        round = round,
        at = t0,
        kind = ChainLedger.RoundRetire,
        thresholds = ChainLedger.ThresholdsNow,
        hotBefore = ChainLedger.Totals(entries = ids.size),
        hotAfter = ChainLedger.Totals(),
        entries = ids.map(id => entry(id, id, t0, List(id)))
      )
    val bundleRounds = List(rf(9001, List("chain-old-bundle-a", "chain-old-bundle-b")), rf(9002, List("chain-old-bundle-c")))
    val bundleText = bundleRounds.map(_.asJson.noSpaces).mkString("", "\n", "\n")
    val bundlePath = arch / "rounds-202609.jsonl.gz"
    IO.blocking {
      java.nio.file.Files.createDirectories(arch.toNIO)
      val out = new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(bundlePath.toIO))
      try out.write(bundleText.getBytes("UTF-8"))
      finally out.close()
    }.flatMap { _ =>
      for
        store <- ChainLedgerStore.open("ledger-bundle", path, arch)
        _ <- store.reconcile(List(proto("chain-live", List("l1"))), Set("l1"), Map.empty, Set.empty, t0)
        obs <- store.reconcile(Nil, Set.empty, Map.empty, Set.empty, t0 + 1000)
        _ = assertEquals(obs.state.pendingDissolve.entries.map(_.chainId), List("chain-live"))
        flushed <- store.reconcile(Nil, Set.empty, Map.empty, Set.empty, t0 + 1000 + ChainLedger.OfflineFlushAgeMs + 1000)
        rounds <- store.readRounds
        hitA <- store.resolveDeep("chain-old-bundle-a")
        hitC <- store.resolveDeep("chain-old-bundle-c")
        hitLive <- store.resolveDeep("chain-live")
        miss <- store.resolveDeep("chain-zzz")
      yield
        assertEquals(
          rounds.map(_.round).sorted,
          List(1, 9001, 9002),
          "束（2 轮）+ 单轮 gzip 全收（透明解压 + 逐行解析）"
        )
        assertEquals(hitA, Some("chain-old-bundle-a"), "穿束命中（束内条目可达）")
        assertEquals(hitC, Some("chain-old-bundle-c"), "束内第二条同款可达")
        assertEquals(hitLive, Some("chain-live"), "单轮 gzip 文件同款可达")
        assertEquals(miss, None, "无解返回 None（不猜）")
        assertEquals(flushed.state.rounds.size, 1, "守恒审计仍 manifest 驱动（野束文件不进审计面）")
      end for
    }
  }
end ChainLedgerSpec
