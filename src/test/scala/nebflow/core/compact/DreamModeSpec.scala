package nebflow.core.compact

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.service.MemoryBudget

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * DreamMode T3 生命周期淘汰 + 预算闸 spec（memory-management-plan §6.2-2.4，
 * 2026-09-05 批次二机制二）。
 *
 * 验收断言（plan 表 2.4 原文）：
 *  - 过期条目被淘：未晋升且 firstSeen 距今 > M=14 天 → 合并写入时淘汰；
 *  - FIFO 淘最老：未晋升条目 > 60 条上限 → 最老优先淘汰；
 *  - 晋升条目豁免：正文节存在同事实（文本精确出现）→ TTL/FIFO 均豁免——
 *    机制不自动删 Nebula 已标记（晋升）的条目；
 *  - 预算闸（§6.2-2.2）：合并产物超 User.md 硬顶 → 跳过落盘 + BudgetBlocked
 *    （hook 写面与 MemoryEdit 同判据，防 hook 侧绕过）。
 *
 * 纯函数（t3Evolve / mergeFactsIntoSection / promotedTexts）直测；IO 路径经
 * PathUtil.setDataRoot 钉临时目录（DreamMode 直读直写无缓存，每测可独立钉）。
 */
class DreamModeSpec extends FunSuite:

  private val DayMs: Long = 24L * 60 * 60 * 1000
  private val now: Long = 1_760_000_000_000L // 固定时钟（纯函数注入）

  // ---------------------------------------------------------------
  // t3Evolve：过期淘 / FIFO 淘最老 / 晋升豁免
  // ---------------------------------------------------------------

  private def e(cat: String, text: String, seen: Long) = (cat, text, seen)

  test("T3: 过期未晋升条目被淘（>M=14 天），新鲜条目保留") {
    val entries = Vector(
      e("DECISION", "新鲜条目 A", now - 1 * DayMs),
      e("PATTERN", "过期条目 B", now - 15 * DayMs),
      e("DECISION", "边界条目 C", now - 14 * DayMs) // 恰好 M 天 = 未超（<=Ttl 保留）
    )
    val (kept, (ttl, fifo)) = DreamMode.t3Evolve(entries, promoted = Set.empty, now = now)
    val keptTexts = kept.map(_._2)
    assert(keptTexts.contains("新鲜条目 A"), "1 天条目保留")
    assert(keptTexts.contains("边界条目 C"), "恰好 14 天不算超期（now-seen <= Ttl）")
    assert(!keptTexts.contains("过期条目 B"), "15 天未晋升条目必须被 TTL 淘汰")
    assertEquals(ttl.map(_._2), Vector("过期条目 B"))
    assertEquals(fifo, Vector.empty)
  }

  test("T3: FIFO 上限 60 —— 未晋升条目超限时最老优先淘汰") {
    // 65 条全部新鲜（13 天内）但超 60 上限 → FIFO 淘最老 5 条
    val entries = (1 to 65).map { i =>
      e("PATTERN", s"条目-$i", now - (66 - i) * DayMs / 5) // i 越大越新，全部 <14 天
    }.toVector
    val (kept, (ttl, fifo)) = DreamMode.t3Evolve(entries, promoted = Set.empty, now = now)
    assertEquals(kept.size, 60, "未晋升保留域钉在 MaxEntries=60")
    assertEquals(ttl, Vector.empty, "全部新鲜，无 TTL 淘汰")
    val evicted = fifo.map(_._2).toSet
    assertEquals(evicted, (1 to 5).map(i => s"条目-$i").toSet, "淘的必须是最老的 5 条")
    (6 to 65).foreach { i =>
      assert(kept.map(_._2).contains(s"条目-$i"), s"新条目-($i) 必须保留")
    }
  }

  test("T3: 晋升条目豁免 —— 正文存在同事实的条目既不被 TTL 淘也不占 FIFO 名额") {
    val entries = Vector(
      e("DECISION", "已晋升条目 X", now - 30 * DayMs), // 超期 + 已晋升
      e("PATTERN", "未晋升新鲜 Y", now - 1 * DayMs)
    )
    val (kept, (ttl, fifo)) = DreamMode.t3Evolve(entries, promoted = Set("已晋升条目 X"), now = now)
    assert(kept.map(_._2).contains("已晋升条目 X"), "晋升豁免：30 天已晋升条目保留（Nebula 标记项不自动删）")
    assert(kept.map(_._2).contains("未晋升新鲜 Y"))
    assertEquals(ttl, Vector.empty)
    assertEquals(fifo, Vector.empty)
  }

  test("T3: FIFO 淘汰域只含未晋升条目 —— 晋升条目挤占时不向豁免域下手") {
    // 61 条未晋升新鲜 + 5 条晋升豁免（豁免的故意超期 50 天）：只淘未晋升最老 1 条
    val unpromoted = (1 to 61).map(i => e("PATTERN", s"u-$i", now - (62 - i) * DayMs / 5))
    val promotedEntries = (1 to 5).map(i => e("DECISION", s"p-$i", now - 50 * DayMs))
    val entries = unpromoted.toVector ++ promotedEntries.toVector
    val (kept, (_, fifo)) = DreamMode.t3Evolve(entries, promoted = (1 to 5).map(i => s"p-$i").toSet, now = now)
    promotedEntries.foreach(p => assert(kept.contains(p), "豁免条目不得被 FIFO 波及（即使其超期 50 天）"))
    assertEquals(fifo.map(_._2), Vector("u-1"), "FIFO 只在未晋升域淘最老")
  }

  test("T3: promotedTexts —— 条目文本在正文其余部分精确出现才判晋升；改写措辞不豁免") {
    val restOfContent = // User.md 去掉 Dream 节后的其余部分
      "## Product Decisions\n\n- 官网 logo 全站统一 24px（2026-09-01 裁定）\n\n## 工作风格\n\n- 深色主题\n"
    val promoted = DreamMode.promotedTexts(
      List("官网 logo 全站统一 24px（2026-09-01 裁定）", "语音输入最终取向：普通话为主"),
      restOfContent)
    assert(promoted.contains("官网 logo 全站统一 24px（2026-09-01 裁定）"), "正文精确同文 = 已晋升")
    assert(!promoted.contains("语音输入最终取向：普通话为主"), "正文无同文 = 未晋升")
    assert(!promoted.contains("官网 logo 全站统一 24px"), "部分前缀不构成同事实（晋升改写措辞即不豁免）")
  }

  // ---------------------------------------------------------------
  // mergeFactsIntoSection 端到端（纯函数）
  // ---------------------------------------------------------------

  test("merge: 过期未晋升被淘 + 晋升豁免保留 + 新 fact 合并 + sidecar 时钟正确") {
    val old = now - 20 * DayMs
    val existing =
      """## Product Decisions
        |
        |- 已晋升事实：命令行默认深色
        |
        |## Dream Extract
        |
        |### DECISION
        |
        |- 已晋升事实：命令行默认深色
        |- 过期未晋升：某一次性状态
        |
        |### PATTERN
        |
        |- 新鲜未晋升：某模式
        |""".stripMargin
    val sidecar = Map(
      DreamMode.entryHash("已晋升事实：命令行默认深色") -> old,
      DreamMode.entryHash("过期未晋升：某一次性状态") -> old,
      DreamMode.entryHash("新鲜未晋升：某模式") -> (now - 2 * DayMs)
    )
    val plan = DreamMode.mergeFactsIntoSection(
      existing,
      List("DECISION" -> "全新裁定：测试先行"),
      sidecar,
      now
    )
    val out = plan.content
    assert(!out.contains("过期未晋升"), "TTL 超期未晋升条目被淘")
    assert(out.contains("已晋升事实：命令行默认深色"), "晋升豁免条目保留")
    assert(out.contains("新鲜未晋升：某模式"), "新鲜条目保留")
    assert(out.contains("全新裁定：测试先行"), "新 fact 合并进稳定节")
    assertEquals(plan.evictedTtl, 1, "TTL 淘汰计数 = 1")
    assertEquals(plan.added, 1, "新增 = 1")
    // sidecar 写回集：淘汰条目 hash 被 GC，新条目 clock=now
    val evictedHash = DreamMode.entryHash("过期未晋升：某一次性状态")
    assert(!plan.timestamps.contains(evictedHash), "sidecar GC：淘汰条目时钟不再写回")
    assertEquals(plan.timestamps(DreamMode.entryHash("全新裁定：测试先行")), now, "新条目 clock=now")
    assertEquals(plan.timestamps(DreamMode.entryHash("新鲜未晋升：某模式")), now - 2 * DayMs, "保留条目时钟原样")
  }

  test("merge: sidecar 缺失记录的存量条目 —— 年龄未知不误淘，clock 从现在起算") {
    val existing =
      """## Dream Extract
        |
        |### PATTERN
        |
        |- 无时钟存量条目
        |""".stripMargin
    val plan = DreamMode.mergeFactsIntoSection(existing, List("PATTERN" -> "新模式"), Map.empty, now)
    assert(plan.content.contains("无时钟存量条目"), "missing clock 条目保守保留")
    assert(plan.evicted.isEmpty)
    assertEquals(plan.timestamps(DreamMode.entryHash("无时钟存量条目")), now, "clock 从当前轮起算")
  }

  // ---------------------------------------------------------------
  // IO 路径：预算闸 + 落盘 + sidecar
  // ---------------------------------------------------------------

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-dream-t3"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def userFile: os.Path = home / "User.md"

  test("updateMemory: 正常路径落盘 + sidecar 写入（IO 薄壳）"):
    os.write.over(userFile, "## Dream Extract\n\n### PATTERN\n\n- 既有模式\n", createFolders = true)
    val res = DreamMode.updateMemory(List("FACT 1: [PATTERN] 全新模式条目")).unsafeRunSync()
    res match
      case DreamMode.MergeResult.Merged(_, evicted, added) =>
        assertEquals(added, 1)
        assertEquals(evicted, 0)
      case other => fail(s"期望 Merged，得 $other")
    assert(os.read(userFile).contains("全新模式条目"), "合并落盘")
    assert(os.exists(DreamMode.timestampsPath), "sidecar 落盘")

  test("updateMemory: 合并产物超 User.md 硬顶 → BudgetBlocked，文件零写入（hook 同闸防绕过）"):
    val big = "## Dream Extract\n\n### PATTERN\n\n- " + "x" * 52000 + "\n"
    os.write.over(userFile, big, createFolders = true)
    val before = os.read(userFile)
    assert(before.getBytes.length > MemoryBudget.UserHardBytes, "seed 本体须已超硬顶")
    val res = DreamMode.updateMemory(List("FACT 1: [DECISION] 超限新裁定")).unsafeRunSync()
    res match
      case DreamMode.MergeResult.BudgetBlocked(bytes) =>
        assert(bytes > 50L * 1024, s"报告的应是越限后字节: $bytes")
      case other => fail(s"期望 BudgetBlocked，得 $other")
    assertEquals(os.read(userFile), before, "预算闸拦截 = 文件零写入")
    // 整理后（低于硬顶）同一批 facts 可正常写入 —— 闸不是死锁
    os.write.over(userFile, "## Dream Extract\n\n### PATTERN\n\n- 小内容\n", createFolders = true)
    DreamMode.updateMemory(List("FACT 1: [DECISION] 整理后新裁定")).unsafeRunSync() match
      case DreamMode.MergeResult.Merged(_, _, _) => assert(os.read(userFile).contains("整理后新裁定"))
      case other                                 => fail(s"整理后应放行: $other")

  test("updateMemory: 无可解析 facts → Noop"):
    os.write.over(userFile, "## Dream Extract\n\n### PATTERN\n\n- x\n", createFolders = true)
    assertEquals(DreamMode.updateMemory(List.empty).unsafeRunSync(), DreamMode.MergeResult.Noop)
    assertEquals(DreamMode.updateMemory(List("不是 FACT 格式")).unsafeRunSync(), DreamMode.MergeResult.Noop)

  test("entryHash: 规范化稳定 —— trim 差异同 hash，不同文本不同 hash"):
    assertEquals(DreamMode.entryHash("  abc  "), DreamMode.entryHash("abc"))
    assert(DreamMode.entryHash("abc") != DreamMode.entryHash("abd"))

end DreamModeSpec
