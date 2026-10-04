package nebflow.core

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.{MemoryBudget, MemorySnapshot, MemoryStore, PathUtil}

import java.nio.file.Files

/**
 * `Soul.md` 迁移腿 + 双读 + 注入分块/预算联动（personal-agent 批 2026-10-04）。
 *
 * 覆盖判据（全部二值）：
 *  - [[SoulMigration.run]]：`Soul.md` 不存在 ∧ 旧文件非空 ⇒ 迁移；已迁移 ⇒ 跳过且零副作用；
 *    无源 ⇒ 跳过；写前快照落盘（回滚锚）；快照失败 ⇒ 中止且零文件写。
 *  - [[MemoryStore.loadSoulMemory]]：新位优先、缺省回落旧位、皆空 ⇒ None。
 *  - [[nebflow.agent.ContextRefresher.renderMemoryBlock]]：Soul / User **分块**（两块标题可区分）。
 *  - [[nebflow.shared.MemoryBudget]]：上下文窗口 → 注入预算上限的线性联动与 fail-open。
 *
 * 隔离纪律：本 spec **零写真实 `~/.nebflow`** —— 全部动作在 `setDataRoot` 钉住的临时
 * 目录内；收尾还原 dataRoot 并删除临时树。
 */
class SoulMigrationSpec extends FunSuite:

  private var originalRoot: os.Path = null
  private var home: os.Path = null

  override def beforeAll(): Unit =
    originalRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-soul-spec"))
    // 🔴 单一固定 dataRoot：MemoryStore 的 mtime 缓存是 `val`，在对象初始化时
    // 绑定当时的 dataRoot —— 每测轮换 root 会让缓存永久指向旧路径（测试自欺）。
    // 故本 spec 钉一个 home，逐测清空其内容并显式失效缓存。
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(home)

  /** 清空 home 内全部记忆面 + 失效缓存（每测起点一致）。 */
  private def reset(): Unit =
    List(
      home / "Soul.md",
      home / "agents",
      home / "memory-backups"
    ).foreach(p => if os.exists(p) then os.remove.all(p))
    MemoryStore.invalidateSoulCache()
    MemoryStore.invalidateUserCache()
    MemoryStore.invalidateAgentCache(rootName)

  private def rootName: String = nebflow.actor.RootAgentIdentity.Name

  private def seedLegacy(content: String): os.Path =
    val p = home / "agents" / rootName / "memory.md"
    os.write.over(p, content, createFolders = true)
    p

  // ---------------------------------------------------------------
  // 迁移腿
  // ---------------------------------------------------------------

  test("migrate: Soul.md 不存在 ∧ 旧文件非空 ⇒ 迁移（新位成立、旧文件保留、快照已落）"):
    reset()
    val legacy = seedLegacy("# Nebula memory\n\n- 旧记忆一条\n")
    val outcome = SoulMigration.run()
    assert(outcome.isInstanceOf[SoulMigration.Outcome.Migrated], s"应迁移，实际 $outcome")
    val soul = home / "Soul.md"
    assert(os.exists(soul), "Soul.md 必须落位")
    assertEquals(os.read(soul), os.read(legacy), "内容逐字复制")
    assert(os.exists(legacy), "旧文件保留为只读备份（不删）")
    // 写前快照 = 回滚锚：备份根下必须出现一份旧位副本
    val backups = os.list(MemorySnapshot.backupRoot).filter(os.isDir)
    assert(backups.nonEmpty, "写前快照必须落盘（fail-closed 前置）")
    val names = os.walk(backups.head).filter(os.isFile).map(_.last).toSet
    assert(names.contains(MemorySnapshot.backupFileName(legacy)), s"快照应含旧文件副本，实际 $names")

  test("migrate: 幂等——已迁移则跳过，且再跑零副作用（Soul.md 不被重写、旧文件 mtime 不变）"):
    reset()
    val legacy = seedLegacy("# Nebula memory\n\n- 一条\n")
    SoulMigration.run() match
      case _: SoulMigration.Outcome.Migrated => ()
      case other => fail(s"首跑应迁移，实际 $other")
    val soul = home / "Soul.md"
    // 首跑后再跑：跳过；改写 Soul.md 内容以证明「跳过 = 不覆盖」
    os.write.over(soul, "# 用户手写的 Soul\n")
    val legacyMtime = os.mtime(legacy)
    val second = SoulMigration.run()
    assertEquals(second, SoulMigration.Outcome.SkippedSoulExists, "Soul.md 已存在 ⇒ 跳过")
    assertEquals(os.read(soul), "# 用户手写的 Soul\n", "跳过路径零写：用户的 Soul 不被自动动作覆盖")
    val third = SoulMigration.run()
    assertEquals(third, SoulMigration.Outcome.SkippedSoulExists)
    assertEquals(os.mtime(legacy), legacyMtime, "旧文件在两次跳过路径上 mtime 不变（零副作用）")

  test("migrate: 无旧文件 ⇒ 跳过（全新安装）"):
    reset()
    assertEquals(SoulMigration.run(), SoulMigration.Outcome.SkippedNoSource)

  test("migrate: 旧文件为空 ⇒ 跳过（空内容不建空壳 Soul.md）"):
    reset()
    seedLegacy("   \n\n")
    assertEquals(SoulMigration.run(), SoulMigration.Outcome.SkippedNoSource)
    assert(!os.exists(home / "Soul.md"), "不得落空壳")

  test("migrate: 快照失败 ⇒ 中止且零文件写（fail-closed）"):
    reset()
    val legacy = seedLegacy("# 有内容\n")
    // 让快照根不可写：用同名文件占住 memory-backups 路径
    os.write.over(home / "memory-backups", "blocker", createFolders = true)
    val outcome = SoulMigration.run()
    assert(outcome.isInstanceOf[SoulMigration.Outcome.Aborted], s"应中止，实际 $outcome")
    assert(!os.exists(home / "Soul.md"), "快照失败 ⇒ Soul.md 不得被写出（零文件写）")
    assertEquals(os.read(legacy), "# 有内容\n", "源文件保持原样")

  // ---------------------------------------------------------------
  // 双读
  // ---------------------------------------------------------------

  test("dual-read: 两腿皆在 ⇒ 取新位 Soul.md"):
    reset()
    seedLegacy("旧位内容")
    os.write.over(home / "Soul.md", "新位内容")
    MemoryStore.invalidateSoulCache()
    MemoryStore.invalidateAgentCache(rootName)
    assertEquals(MemoryStore.loadSoulMemory(rootName), Some("新位内容"))

  test("dual-read: 新位缺失 ⇒ 回落旧位（既有记忆不失联）"):
    reset()
    seedLegacy("只有旧位")
    MemoryStore.invalidateSoulCache()
    MemoryStore.invalidateAgentCache(rootName)
    assertEquals(MemoryStore.loadSoulMemory(rootName), Some("只有旧位"))

  test("dual-read: 两腿皆空/缺失 ⇒ None（不注入空块）"):
    reset()
    MemoryStore.invalidateSoulCache()
    MemoryStore.invalidateAgentCache(rootName)
    assertEquals(MemoryStore.loadSoulMemory(rootName), None)

  test("dual-read: 旧位为空串 ⇒ 等同缺失（parseMemory 归一）"):
    reset()
    seedLegacy("  \n")
    MemoryStore.invalidateSoulCache()
    MemoryStore.invalidateAgentCache(rootName)
    assertEquals(MemoryStore.loadSoulMemory(rootName), None)

  // ---------------------------------------------------------------
  // 注入分块
  // ---------------------------------------------------------------

  test("inject: Soul 与 User 分两块（标题可区分），顺序 Soul 在前"):
    val block = nebflow.agent.ContextRefresher.renderMemoryBlock(
      Some("- 怎么称呼：阿凯"),
      Some("- 名字：星尘"),
      (false, false)
    )
    assert(block.contains("## Soul"), s"应有 Soul 块: $block")
    assert(block.contains("## User"), s"应有 User 块: $block")
    assert(block.indexOf("## Soul") < block.indexOf("## User"), "Soul 块在前")
    assert(!block.contains("## Agent Memory"), "旧块名退役（不分块则语义丢失）")
    assert(!block.contains("## User Memory"), "旧块名退役")

  test("inject: 只有一块时另一块不渲染空壳"):
    val onlySoul = nebflow.agent.ContextRefresher.renderMemoryBlock(None, Some("- 名字：星尘"), (false, false))
    assert(onlySoul.contains("## Soul"))
    assert(!onlySoul.contains("## User"))

  // ---------------------------------------------------------------
  // 预算联动（⑥）
  // ---------------------------------------------------------------

  test("budget: 窗口 → 注入上限线性联动，且默认窗口不收紧既有常量"):
    // 默认 128k ⇒ 128000 × 4 × 10% = 51200 > 既有两级硬顶 ⇒ 生效值 = 常量本身
    assertEquals(MemoryBudget.injectionCapBytes(nebflow.shared.Defaults.ContextWindow), 51200L)
    assertEquals(MemoryBudget.userInjectionHardBytes(nebflow.shared.Defaults.ContextWindow), MemoryBudget.UserHardBytes)
    assertEquals(MemoryBudget.agentInjectionHardBytes(nebflow.shared.Defaults.ContextWindow), MemoryBudget.AgentHardBytes)
    // 小窗口 ⇒ 收紧
    assertEquals(MemoryBudget.userInjectionHardBytes(32000), 12800L)
    assertEquals(MemoryBudget.agentInjectionHardBytes(32000), 12800L)
    // 大窗口（1M）⇒ 上限高于常量 ⇒ 仍以常量为生效值（比例不放大既有预算）
    assertEquals(MemoryBudget.userInjectionHardBytes(1000000), MemoryBudget.UserHardBytes)

  test("budget: 软线 = 生效硬顶的 80%"):
    assertEquals(MemoryBudget.userInjectionSoftBytes(32000), 10240L)
    assertEquals(MemoryBudget.userInjectionSoftBytes(nebflow.shared.Defaults.ContextWindow), MemoryBudget.UserSoftBytes)

  test("budget: 非正窗口 ⇒ fail-open 到既有常量（缺值不把预算压成 0）"):
    assertEquals(MemoryBudget.injectionCapBytes(0), Long.MaxValue)
    assertEquals(MemoryBudget.injectionCapBytes(-1), Long.MaxValue)
    assertEquals(MemoryBudget.userInjectionHardBytes(0), MemoryBudget.UserHardBytes)

  test("notice: 小窗口下 15KB 内容升级为即时任务（默认窗口下不升级 ⇒ 联动真实生效）"):
    val mid = Some("x" * 15000)
    val atDefault = nebflow.agent.ContextRefresher.memoryHygieneNotice(None, mid, (false, false))
    assertEquals(atDefault, "", "默认 128k 窗口下 15KB 未超 24KB 软线 ⇒ 零打扰")
    val atSmall = nebflow.agent.ContextRefresher.memoryHygieneNotice(
      None, mid, (false, false), contextWindow = 32000
    )
    assert(atSmall.contains("IMMEDIATE TASK"), s"32k 窗口下应升级（软线 10240 < 15000）: $atSmall")
    assert(atSmall.contains("Soul.md"), "指明 Soul 文件")
    assert(atSmall.contains("10240"), "列明生效软线（窗口推导，非常量 24576）")

  // ---------------------------------------------------------------
  // 孤儿归档（迁移不删）
  // ---------------------------------------------------------------

  test("orphans: listOrphans 列出非根 agent 的记忆，排除根 agent 自身"):
    reset()
    os.write.over(home / "agents" / "Coder" / "memory.md", "coder", createFolders = true)
    os.write.over(home / "agents" / "Manager" / "memory.md", "mgr", createFolders = true)
    os.write.over(home / "agents" / rootName / "memory.md", "root", createFolders = true)
    val names = SoulMigration.listOrphans().map(_._1).sorted
    assertEquals(names, List("Coder", "Manager"), "根 agent 自身不在孤儿清单")

  test("orphans: archiveOrphans 复制到归档根且原文件保留（不删）"):
    reset()
    val coderMem = home / "agents" / "Coder" / "memory.md"
    os.write.over(coderMem, "coder 记忆", createFolders = true)
    val (count, root) = SoulMigration.archiveOrphans("test-stamp")
    assertEquals(count, 1)
    assert(os.exists(root / "Coder" / "memory.md"), "归档副本必须落位")
    assertEquals(os.read(root / "Coder" / "memory.md"), "coder 记忆")
    assert(os.exists(coderMem), "原文件保留（归档不删）")

  test("write path: saveSoulMemory 落新位（不经旧位）"):
    reset()
    MemoryStore.saveSoulMemory("# 新写的 Soul\n").unsafeRunSync()
    assert(os.exists(home / "Soul.md"), "Soul.md 必须落新位")
    assert(!os.exists(home / "agents" / rootName / "memory.md"), "落新位时不得在旧位建文件")
    MemoryStore.invalidateSoulCache()
    assertEquals(MemoryStore.loadSoulMemory(rootName), Some("# 新写的 Soul"))

end SoulMigrationSpec
