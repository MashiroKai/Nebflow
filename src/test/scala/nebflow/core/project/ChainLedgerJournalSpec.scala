package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.AtomicJson

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * perf-481 A3 —— **增量写（journal + checkpoint）的崩溃点注入与等价性红验**。
 *
 * 计划风险 **R-3**（`ChainLedgerStore.persist` 从「整份原子写」改为「append + 周期折叠」）的
 * 必做验收面。A3 的修法是**行为保持**（存量语义一字不改，只把 13.4 MB 的整写换成 ~2 KB 的
 * 追加），因此本 spec 的判据不是「新格式长什么样」，而是**三条机械保证是否仍成立**：
 *
 *  1. **崩溃点任意 ⇒ 不丢载荷、不读半状态**（[[AtomicJson]] 头注的判据，禁弱化）：
 *     - 残尾（无终结 `\n` 的半条 append）⇒ 回放到前一条完整记录；
 *     - 旧格式 checkpoint（无 journal）⇒ 逐字可读（旧文件的读取路径未被破坏）；
 *  2. **重放幂等**：checkpoint 已落而 journal 未截断 ⇒ 重放记录结果与「只读 checkpoint」相同
 *     （记录是完整快照 + 轮号去重 ⇒ 见 [[ChainLedger.mergeRounds]]）；
 *  3. **等价性**：同一串状态变更下，新路径的落盘**末次写语义**与旧 [[AtomicJson.writeSync]]
 *     的整份写**逐字节相等** —— 这是「行为保持」的可判据形式（不是「像」而是「等」）。
 *
 * 另加两条 A3 自身的实现判据：
 *  - **记录的增量形状**：记录只携带相对 checkpoint 的增量（`base` = checkpoint 已有 manifest
 *    条数；`state.rounds` 只存新增段）—— 这是「13.4 MB → ~2 KB」的唯一来源，须钉死；
 *  - **折叠触发**：达到阈值 ⇒ checkpoint 前进（承载全量 `rounds`）+ journal 清空。
 *
 * 🔴 非空洞：每条断言都指名一个**可变异点**（改坏 ⇒ 本测试必红），见各 `// 变异:` 注。
 * 🔴 只读运行：零 push / 零 tag / 零 VERSION；不监听端口；台账隔离在 `os.temp.dir`。
 */
class ChainLedgerJournalSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val LogName = "nebflow.chain-ledger"
  private val t0 = 1750000000000L

  // ── 夹具 ───────────────────────────────────────────────────────────────

  private def proto(id: String, members: List[String]): ChainInfo =
    ChainInfo(id = id, memberIds = members)

  private def dir(prefix: String): os.Path =
    os.temp.dir(prefix = s"nb-chain-ledger-journal-$prefix-", deleteOnExit = false)

  private def open(
    project: String,
    path: os.Path,
    arch: os.Path,
    rotateBytes: Long = ChainLedger.JournalRotateBytes
  ): IO[ChainLedgerStore] =
    ChainLedgerStore.open(project, path, arch, rotateBytes = rotateBytes)

  /** 手搓一条 manifest（本 spec 只关心「轮清单被原样带回」，不重算守恒）。 */
  private def round(n: Int): ChainLedger.RoundManifest =
    ChainLedger.RoundManifest(
      round = n,
      at = t0 + n,
      kind = ChainLedger.RoundCompact,
      file = s"round-$n.json",
      movedEntries = 1,
      movedAliases = 0,
      movedMembers = 1,
      hotBefore = ChainLedger.Totals(entries = 2, members = 2),
      hotAfter = ChainLedger.Totals(entries = 1, members = 1),
      digest = s"digest-$n",
      tick = n
    )

  /** 抓 `nebflow.chain-ledger` 的 WARN 行（不抓则「绝不静默」的判据无法判）。 */
  private def withWarnAppender[A](f: => IO[A]): IO[(A, List[String])] =
    val lbLogger =
      org.slf4j.LoggerFactory.getLogger(LogName).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    IO.delay {
      appender.start()
      lbLogger.addAppender(appender)
    }.bracket { _ =>
      f.map(a =>
        a -> appender.list.asScala.toList
          .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
          .map(_.getFormattedMessage)
      )
    } { _ => IO.delay(lbLogger.detachAppender(appender)) }

  // ── ① 残尾：无终结换行的半条 append 必须被丢弃 ──────────────────────

  test("把 `\\n` 定帧改成「按行即记录」⇒ 本测试必红") {
    val d = dir("torn")
    val path = d / ChainLedger.FileName
    val arch = d / ChainLedger.ArchiveDirName
    val journal = AtomicJson.journalPathOf(path)
    for
      // 两条完整记录 + 一条**半条**（无终结换行，模拟 append 中途断电）
      _ <- IO.blocking {
        AtomicJson.appendSync(path, """{"base":0,"state":{"v":1,"project":"p","rounds":[]}}""")
        AtomicJson.appendSync(path, """{"base":0,"state":{"v":1,"project":"p","rounds":[]}}""")
        os.write.append(journal, """{"base":0,"state":{"v":1,"pro""")
      }
      read <- IO.blocking(AtomicJson.readAll(path))
      store <- open("torn", path, arch)
      st <- store.snapshot
    yield
      // 变异: 让 `readJournalRecords` 接受无 `\n` 的尾段 ⇒ 残尾变成第 3 条记录 ⇒ 断言翻红。
      assertEquals(read.records.size, 2, "残尾不算记录（无终结 `\\n` 的半条不是帧）")
      assertEquals(read.checkpointError, None, "无 checkpoint 是正常形态（首次折叠前崩溃）")
      assertEquals(read.checkpoint, None, "本次从未折叠 ⇒ 盘上无 checkpoint")
      assert(read.records.forall(_.frameBytes > 0), "每条记录都带自己的在盘帧字节")
      // 载入不得因残尾而崩，且 journal 可回放（本 spec 的状态里 rounds 为空 ⇒ 无合并语义）
      assertEquals(st.project, "torn", "载入态的项目名取自记录")
    end for
  }

  // ── ② 旧格式 checkpoint（无 journal）仍逐字可读 ─────────────────────

  test("旧格式（纯 checkpoint、无 journal）⇒ 载入结果与写入态逐字节一致") {
    val d = dir("legacy")
    val path = d / ChainLedger.FileName
    val arch = d / ChainLedger.ArchiveDirName
    // 旧格式 = 整份 State 的原子写（A3 之前的形态），带 2 条 round manifest
    val legacy = ChainLedger.State(
      project = "legacy",
      updatedAt = t0,
      entries = Map(
        "chain-a" -> ChainLedger.Entry(
          chainId = "chain-a",
          anchor = "a",
          bornAt = t0,
          members = List("a", "b"),
          memberCount = 2
        )
      ),
      rounds = List(round(1), round(2))
    )
    for
      _ <- IO.blocking(AtomicJson.writeSync(path, legacy.asJson.noSpaces))
      noJournal <- IO.blocking(os.exists(AtomicJson.journalPathOf(path)))
      store <- open("legacy", path, arch)
      st <- store.snapshot
      pending <- store.journalPendingBytes
    yield
      // 变异: 让载入在 journal 缺失时必须「非空」才认 checkpoint ⇒ 本断言翻红。
      assert(!noJournal, "旧格式无 journal 面")
      assertEquals(st, legacy, "旧格式 checkpoint 逐字可读（内存态 == 写入态）")
      assertEquals(pending, 0L, "无 journal ⇒ 待折叠字节为 0")
    end for
  }

  // ── ③ 重放幂等：checkpoint 已落 + journal 未截断 ────────────────────

  test("checkpoint 已含全部 + journal 残留同段记录 ⇒ 重放幂等（结果与只读 checkpoint 相同）") {
    val d = dir("replay")
    val path = d / ChainLedger.FileName
    val arch = d / ChainLedger.ArchiveDirName
    val state2 = ChainLedger.State(
      project = "replay",
      updatedAt = t0 + 1,
      entries = Map(
        "chain-a" -> ChainLedger.Entry(chainId = "chain-a", anchor = "a", bornAt = t0, members = List("a"), memberCount = 1)
      ),
      rounds = List(round(1))
    )
    val journal = AtomicJson.journalPathOf(path)
    for
      // 场景 = 崩在「checkpoint 已写、journal 未截断」之间：checkpoint 已是**最新**态，
      // journal 里那条记录是被折叠过的旧记录（前一个 checkpoint 的增量）。
      _ <- IO.blocking {
        AtomicJson.writeSync(path, state2.asJson.noSpaces)
        AtomicJson.appendSync(
          path,
          ChainLedger.JournalRecord(
            base = 0,
            state = state2.copy(rounds = state2.rounds)
          ).asJson.noSpaces
        )
      }
      store <- open("replay", path, arch)
      st <- store.snapshot
      // 对照：删掉 journal 只读 checkpoint（「只读 checkpoint」这一支）
      _ <- IO.blocking(os.remove(journal))
      store2 <- open("replay", path, arch)
      st2 <- store2.snapshot
    yield
      // 变异: 让载入在「有 journal 记录」时**覆盖**而非**合并**（丢掉 checkpoint 已有的段）
      // ⇒ `st != st2` ⇒ 本断言翻红。
      assertEquals(st, st2, "重放一条已折叠的记录 == 只读 checkpoint（幂等）")
      assertEquals(st.rounds.map(_.round), List(1), "轮清单按轮号去重、不重复累积")
      assertEquals(st, state2, "重放后仍然是最新态")
    end for
  }

  test("同一轮号在两段里内容恒同 ⇒ mergeRounds 取后见者、升序、不静默丢段") {
    // 变异: 让 `mergeRounds` 直接 `committed ++ pending`（不去重）⇒ 长度断言翻红。
    val merged = ChainLedger.mergeRounds(List(round(1), round(2)), List(round(2), round(3)))
    assertEquals(merged.map(_.round), List(1, 2, 3), "按轮号去重 + 升序")
    val onlyPending = ChainLedger.mergeRounds(Nil, List(round(1), round(2)))
    assertEquals(onlyPending.map(_.round), List(1, 2), "空 checkpoint + 全量 journal ⇒ 完整还原")
    val onlyCommitted = ChainLedger.mergeRounds(List(round(1)), Nil)
    assertEquals(onlyCommitted.map(_.round), List(1), "journal 为空 ⇒ 纯 checkpoint")
  }

  // ── ④ 两文件皆缺 / checkpoint 损坏 ─────────────────────────────────

  test("两文件皆缺 ⇒ 空账且零 WARN；checkpoint 损坏 ⇒ 空账 + WARN 且不采信 journal") {
    val d = dir("corrupt")
    val path = d / ChainLedger.FileName
    val arch = d / ChainLedger.ArchiveDirName
    val journal = AtomicJson.journalPathOf(path)
    val goodRecord = ChainLedger.JournalRecord(
      base = 0,
      state = ChainLedger.State(
        project = "corrupt",
        updatedAt = t0,
        entries = Map(
          "chain-a" -> ChainLedger.Entry(chainId = "chain-a", anchor = "a", bornAt = t0, members = List("a"), memberCount = 1)
        )
      )
    ).asJson.noSpaces
    for
      // (a) 全新目录：两文件皆缺
      (fresh, warnsFresh) <- withWarnAppender {
        open("corrupt", path, arch).flatMap(_.snapshot)
      }
      // (b) checkpoint **存在但损坏** + journal **有可用记录**
      _ <- IO.blocking {
        os.write.over(path, "{ this is not json", createFolders = true)
        AtomicJson.appendSync(path, goodRecord)
      }
      (broken, warnsBroken) <- withWarnAppender {
        open("corrupt", path, arch).flatMap(_.snapshot)
      }
    yield
      // 变异: 让 `loadState` 在 `checkpointError` 分支里也从 journal 拼状态 ⇒ (b) 的
      // 「entries 必须为空」翻红（那正是「拿 journal 片段拼半信状态」的错法）。
      assertEquals(warnsFresh, Nil, "全新项目：空账起步必须零 WARN（可重建不是异常）")
      assert(fresh.entries.isEmpty, "全新 ⇒ 空账")
      assert(
        warnsBroken.exists(w => w.contains("corrupt") || w.contains("starting empty")),
        s"损坏的 checkpoint 必须 WARN（绝不静默）：$warnsBroken"
      )
      assert(broken.entries.isEmpty, "损坏 ⇒ 空账起步，**不**采信 journal（权威面不可用即重来）")
      assert(broken.rounds.isEmpty, "损坏 ⇒ 轮清单同样为空")
    end for
  }

  // ── ⑤ 记录的增量形状 + 折叠触发 ───────────────────────────────────

  test("记录只带增量（base = checkpoint 已载条数、state.rounds 只存新增段）+ 达阈值折叠") {
    val d = dir("shape")
    val path = d / ChainLedger.FileName
    val arch = d / ChainLedger.ArchiveDirName
    val journal = AtomicJson.journalPathOf(path)
    // checkpoint 先摆好 2 条 manifest（模拟「已折叠过两次」的底盘）
    val seed = ChainLedger.State(project = "shape", updatedAt = t0, rounds = List(round(1), round(2)))
    for
      _ <- IO.blocking(AtomicJson.writeSync(path, seed.asJson.noSpaces))
      // 阈值取极大 ⇒ 本次 append 不折叠，便于观察记录形状
      store <- open("shape", path, arch, rotateBytes = Long.MaxValue)
      bytes0 <- store.journalPendingBytes
      _ <- store.reconcile(List(proto("chain-a", List("a", "b"))), Set("a", "b"), Map.empty, Set.empty, t0 + 10)
      st <- store.snapshot
      bytes1 <- store.journalPendingBytes
      checkpointText <- IO.blocking(os.read(path))
      journalText <- IO.blocking(os.read(journal))
      rec <- IO.fromEither(
        jsonParse(journalText.linesIterator.toList.last)
          .left
          .map(e => new AssertionError(s"journal 最后一行必须可解析：$e"))
          .flatMap(_.as[ChainLedger.JournalRecord].left.map(e => new AssertionError(e.getMessage)))
      )
      // 再用一个极小阈值开同一个 path（模拟「下一拍跨阈值」）⇒ 下一次 persist 应折叠
      small <- open("shape", path, arch, rotateBytes = 1L)
      _ <- small.reconcile(List(proto("chain-b", List("c", "d"))), Set("c", "d"), Map.empty, Set.empty, t0 + 20)
      stAfterFold <- small.snapshot
      pendingAfterFold <- small.journalPendingBytes
      journalGone <- IO.blocking(!os.exists(journal))
      foldedText <- IO.blocking(os.read(path))
      folded <- IO.fromEither(
        jsonParse(foldedText)
          .left
          .map(e => new AssertionError(s"折叠后的 checkpoint 必须可解析：$e"))
          .flatMap(_.as[ChainLedger.State].left.map(e => new AssertionError(e.getMessage)))
      )
    yield
      // ── 形状（13.4 MB → ~2 KB 的唯一来源）─────────────────────────
      // 变异: 让 `recordOf` 回传整份 `st`（`base = 0` + 全量 rounds）⇒ base/新增段断言翻红。
      assertEquals(bytes0, 0L, "折叠过 ⇒ journal 空")
      assert(bytes1 > 0L, "未折叠 ⇒ append 已落 journal")
      assertEquals(rec.base, 2, "base = checkpoint 已有的 manifest 条数（只在折叠时前进）")
      assertEquals(rec.state.rounds.size, 0, "本次无新轮 ⇒ 记录的新增段为空（不重写已落盘段）")
      assertEquals(rec.state.project, "shape", "非轮字段全量在记录里（记录是完整快照，非 diff）")
      assert(rec.state.entries.keySet.contains("chain-a"), "本次变更的条目必须被记录带走")
      assertEquals(jsonParse(checkpointText).toOption.flatMap(_.as[ChainLedger.State].toOption).map(_.rounds.size), Some(2), "未折叠 ⇒ checkpoint 逐字不变（仍是 2 条）")
      assertEquals(st.rounds.size, 2, "内存态 = checkpoint 2 条 + 新增 0 条")
      // ── 折叠：checkpoint 前进承载全量 + journal 清空 ───────────────
      assert(journalGone, "达阈值 ⇒ journal 必须被截断（折叠后半步）")
      assertEquals(pendingAfterFold, 0L, "折叠后待折叠字节归零")
      assertEquals(folded.project, "shape", "折叠把项目名写回 checkpoint")
      assert(folded.entries.keySet.contains("chain-b"), "折叠后的 checkpoint 是完整最新态")
      assertEquals(folded.entries.keySet, stAfterFold.entries.keySet, "checkpoint 与内存态同源")
    end for
  }

  test("等价性：折叠后的 checkpoint 与旧整份 atomicWrite 逐字节相等 + journal 回放还原同态") {
    val d = dir("equiv")
    val path = d / ChainLedger.FileName
    val arch = d / ChainLedger.ArchiveDirName
    val pathOld = d / "old-style.json"
    val journal = AtomicJson.journalPathOf(path)
    for
      // 新路径：阈值极小（每次 persist 都折叠 ⇒ checkpoint 恒为最新态）
      store <- open("equiv", path, arch, rotateBytes = 1L)
      _ <- store.reconcile(List(proto("chain-a", List("a", "b"))), Set("a", "b"), Map.empty, Set.empty, t0)
      _ <- store.reconcile(List(proto("chain-a", List("a", "b")), proto("chain-c", List("c"))), Set("a", "b", "c"), Map.empty, Set.empty, t0 + 1)
      st <- store.snapshot
      onDisk <- IO.blocking(os.read(path))
      journalGone <- IO.blocking(!os.exists(journal))
      // 旧路径：同一末态走整份原子写
      _ <- IO.blocking(AtomicJson.writeSync(pathOld, st.asJson.noSpaces))
      oldOnDisk <- IO.blocking(os.read(pathOld))
    yield
      // 变异: 让 persist 把 `recordOf` 的**增量态**直接当 checkpoint 写入 ⇒ 盘上 bytes 与
      // `st` 的整份编码不等 ⇒ 本断言翻红（这正是「行为保持」的判据）。
      assert(journalGone, "阈值 1 ⇒ 每次 persist 都折叠，journal 不留")
      assertEquals(onDisk, st.asJson.noSpaces, "新路径 checkpoint == 内存态的整份编码")
      assertEquals(onDisk, oldOnDisk, "新路径末次写与旧 writeSync **逐字节相等**（行为保持）")
    end for
  }

  test("journal 独有 / checkpoint 独有 ⇒ 两个面各自独立还原出**同一个**末态") {
    val d1 = dir("jonly")
    val path1 = d1 / ChainLedger.FileName
    val arch1 = d1 / ChainLedger.ArchiveDirName
    val d2 = dir("conly")
    val path2 = d2 / ChainLedger.FileName
    val arch2 = d2 / ChainLedger.ArchiveDirName
    // 同一串变更跑两次；两条腿分别**删掉一个面**再重开 ⇒ 留下的那个面必须能独立还原。
    // （`persist` 的首写必建 checkpoint ⇒ 正常操作不会留下「纯 journal」形态；本测试的腿 A
    //   模拟的是「checkpoint 被外部删除 / 从旧版本升级」，腿 B 模拟「journal 被外部删除」
    //   —— 两条都是载入侧必须能承受的形态，判据②的防御面。）
    val changes = (s: ChainLedgerStore) =>
      s.reconcile(List(proto("chain-a", List("a", "b"))), Set("a", "b"), Map.empty, Set.empty, t0) *>
        s.reconcile(
          List(proto("chain-a", List("a", "b")), proto("chain-c", List("c"))),
          Set("a", "b", "c"),
          Map.empty,
          Set.empty,
          t0 + 1
        )
    for
      // 腿 A：正常写 → 删掉 checkpoint，只留 journal
      a <- open("jonly", path1, arch1)
      _ <- changes(a)
      stA <- a.snapshot
      checkpointPresent <- IO.blocking(os.exists(path1))
      journalPresent <- IO.blocking(os.exists(AtomicJson.journalPathOf(path1)))
      _ <- IO.blocking(os.remove(path1))
      reopenedA <- open("jonly", path1, arch1)
      stA2 <- reopenedA.snapshot
      // 腿 B：同一串变更 → 删掉 journal，只留 checkpoint（先折叠，让 checkpoint 承载全量）
      b <- open("conly", path2, arch2, rotateBytes = 1L)
      _ <- changes(b)
      stB <- b.snapshot
      _ <- IO.blocking(os.remove(AtomicJson.journalPathOf(path2)))
      reopenedB <- open("conly", path2, arch2)
      stB2 <- reopenedB.snapshot
    yield
      // 变异: 让 `persist` 的首写走 append（不建 checkpoint）⇒ 下一条断言翻红（T10 契约）。
      assert(checkpointPresent, "首写必建 checkpoint（台账是权威面，必须在盘）")
      assert(journalPresent, "默认阈值下本次不折叠 ⇒ journal 有记录")
      // 变异: 让 `loadState` 在 checkpoint 缺失时直接返回空账（不读 journal）⇒ 本断言翻红
      //（这正是「checkpoint 丢失 ⇒ 丢载荷」的错法）。
      assertEquals(stA2.entries.keySet, Set("chain-a", "chain-c"), "checkpoint 缺失 ⇒ journal 独立还原")
      assertEquals(stA2.project, "jonly", "项目名取自记录（override 到本次 project）")
      // 变异: 让折叠只写「增量段」而非全量 ⇒ checkpoint 独立还原缺 rounds/entries ⇒ 翻红。
      assertEquals(stB2, stB, "journal 缺失 ⇒ checkpoint 独立还原，且与写入态逐字一致")
      // 两面还原的**语义等价面**逐项比对（project 因目录不同而别，其余必须同值）。
      assertEquals(stA2.entries, stB2.entries, "journal 还原 == checkpoint 还原（同一末态）")
      assertEquals(stA2.aliases, stB2.aliases, "别名面同值")
      assertEquals(stA2.rounds, stB2.rounds, "轮清单面同值")
      assertEquals(stA2, stA, "腿 A 内存态 == journal 还原态（写入即读回）")
    end for
  }
end ChainLedgerJournalSpec
