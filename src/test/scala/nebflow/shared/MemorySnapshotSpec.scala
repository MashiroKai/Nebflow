package nebflow.shared

import munit.FunSuite
import nebflow.shared.{MemorySnapshot, PathUtil}

import java.nio.file.Files

/**
 * MemorySnapshot spec（snapshot-on-write 硬护栏，dream-agent 批 2026-09-05）。
 *
 * 验收断言（权限模型选型 spec §快照先行）：
 *  - 快照内容 = 写前磁盘真身（备份文件字节 == 目标写前字节）；
 *  - fail-closed：快照失败 → Left，调用方据此中止写入（无备份的覆盖不可回滚——
 *    两记忆文件不在 ~/.nebflow git 跟踪层）；
 *  - 首次写入（目标不存在）→ 无可备份，放行；
 *  - 文件名折叠自描述（agents__Nebula__memory.md / User.md）；
 *  - 保留修剪：每目标 KeepPerFile=20 份，最老整目录淘汰；
 *  - 同毫秒目录冲突由 seq 递增消解（allocateDir 语义，经连续两次快照断言）。
 */
class MemorySnapshotSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-dream-snapshot"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def agentFile: os.Path = home / "agents" / "Nebula" / "memory.md"
  private val root = home / "memory-backups"

  test("快照内容 = 写前磁盘真身；文件名折叠自描述") {
    os.write.over(agentFile, "- pre-write entry A\n- pre-write entry B\n", createFolders = true)
    val result = MemorySnapshot.snapshotBeforeWrite(agentFile, root)
    val dest = result.toOption.getOrElse(fail(s"expected Right, got $result"))
    assert(dest.last == "agents__Nebula__memory.md", s"folded name, got ${dest.last}")
    assert(os.read(dest) == "- pre-write entry A\n- pre-write entry B\n", "backup must hold the PRE-write bytes")
    // 同毫秒连拍 → seq 递增，两目录并存
    val dest2 = MemorySnapshot.snapshotBeforeWrite(agentFile, root).toOption.getOrElse(fail("second snapshot failed"))
    assert(dest != dest2, "same-ms snapshots must allocate distinct dirs")
  }

  test("User.md 文件名折叠为 User.md") {
    val userFile = home / "User.md"
    os.write.over(userFile, "- user fact\n", createFolders = true)
    val dest = MemorySnapshot.snapshotBeforeWrite(userFile, root).toOption.getOrElse(fail("snapshot failed"))
    assert(dest.last == "User.md", s"got ${dest.last}")
  }

  test("首次写入（目标不存在）→ 无可备份放行") {
    val missing = home / "User.md.gone"
    assertEquals(MemorySnapshot.snapshotBeforeWrite(missing, root), Right(missing))
  }

  test("fail-closed：快照根不可写 → Left（调用方中止写入的依据）") {
    os.write.over(agentFile, "- content\n", createFolders = true)
    // root 位置是一个【文件】→ makeDir.all 必败
    val blockedRoot = home / "memory-backups-blocked"
    os.write.over(blockedRoot, "not a directory", createFolders = true)
    val result = MemorySnapshot.snapshotBeforeWrite(agentFile, blockedRoot)
    assert(result.isLeft, "snapshot into a file-path root must fail")
    assert(result.left.exists(_.nonEmpty), "reason must be non-empty")
  }

  test("保留修剪：每目标仅留最近 20 份，最老目录整删") {
    val rootP = home / "memory-backups-prune"
    os.write.over(agentFile, "- prune probe\n", createFolders = true)
    val keep = MemorySnapshot.KeepPerFile
    // 造 keep+3 份：目录名尾部零填充计数器保证字典序==时间序（0..keep+2）
    val key = MemorySnapshot.backupFileName(agentFile)
    for i <- 0 until keep + 3 do
      val d = rootP / f"20260905-000000-000000-$i%03d"
      os.makeDir.all(d)
      os.write(d / key, s"- snapshot $i\n")
    MemorySnapshot.pruneForTest(agentFile, rootP)
    val mine = os.list(rootP).filter(d => os.exists(d / key)).toList
    assertEquals(mine.size, keep, "must keep exactly KeepPerFile")
    val names = mine.map(_.last).sorted
    assertEquals(names.head, "20260905-000000-000000-003", "oldest 3 pruned — head must be counter 003")
  }

  // ── 落地前快照闸（2026-09-13 缺失自愈批 / 方案 §6「现无 fail-closed」补齐）──

  test("snapshotGate：多目标一次性备份 + sha256 断言表 + 读回复核（含 absent 目标）") {
    val rootG = home / "memory-backups-gate"
    val userF = home / "User.md"
    os.write.over(userF, "- 用户条目\n", createFolders = true)
    os.write.over(agentFile, "- agent 条目\n", createFolders = true)
    val missing = home / "no-such.md"

    val gate = MemorySnapshot.snapshotGate(Vector(userF, agentFile, missing), "unit-test", rootG)
    val set = gate.toOption.getOrElse(fail(s"expected Right, got $gate"))
    assertEquals(set.files.size, 3)
    assertEquals(set.files.count(_.absent), 1, "不存在的目标记为 absent（无可回滚对象，不阻断）")
    assertEquals(set.label, "unit-test")
    // 断言表落盘且逐行含 sha / 字节 / 路径
    val table = set.dir / "SNAPSHOT-SHA256.txt"
    assert(os.exists(table), "sha256 断言表必须落盘（回滚锚的可核对面）")
    val text = os.read(table)
    assert(text.contains("# memory-track pre-landing snapshot  label=unit-test"), text.linesIterator.next())
    assert(text.contains("(absent)  ") || text.contains("(absent) "), s"absent 行标注: $text")
    // 备份内容 = 写前磁盘真身（逐字节）
    set.files.filterNot(_.absent).foreach { f =>
      val backup = set.dir / MemorySnapshot.backupFileName(os.Path(f.path))
      assertEquals(os.read(backup), os.read(os.Path(f.path)), s"备份必须等于写前字节: ${f.path}")
    }
  }

  test("snapshotGate fail-closed：快照根不可写 ⇒ Left（调用方据此中止落地）") {
    val userF = home / "User.md"
    os.write.over(userF, "- x\n", createFolders = true)
    val badRoot = home / "User.md.badroot" // 文件占位 ⇒ 无法建目录
    os.write.over(badRoot, "not a dir\n")
    val gate = MemorySnapshot.snapshotGate(Vector(userF), "fail-closed", badRoot)
    assert(gate.isLeft, s"不可写根必须 Left（无快照不落笔）: $gate")
    assert(gate.left.exists(_.nonEmpty), "原因非空")
  }

  // ── R8-B（2026-09-13 记忆归档批）：人工快照钉住、不计滚动槽位 ──
  //
  // 布局覆盖声明（2026-09-13 复核缺陷「R8-B spec 不具载力」修正）：`prune` 只扫快照根的
  // **直接子目录**、且要求该目录**直接含** `key` ⇒ 两种布局的判定路径不同，本节分别覆盖：
  //   (a) 生产布局 `pinned/<batch>/<key>`（`memory-archive-gate.sh begin-batch` 落此形态）
  //       —— `pinned` 顶层不含 key ⇒ **过滤行不参与判定**（下面两条属「布局不变性」断言：
  //       删掉 `MemorySnapshot.scala` 的 `.filter(_.last != PinnedDirName)` 后仍全绿）。
  //   (b) 防御性布局 `pinned/<key>`（`pinned` 本身即快照目录、直接含 key）—— 此时**过滤行
  //       是唯一防线**：没有它 `pinned` 会以「最新目录」身份占掉一个槽位、把最老的自动快照
  //       挤掉。**载力由本节最后一条 spec 提供**（其注释给变异自证法）。

  // 布局不变性断言（对过滤行不敏感；载力见本节末「R8-B 载力」spec）
  test("R8-B：pinned 子树不计槽位，人工快照永不被 prune 淘汰") {
    val rootP = home / "memory-backups-pinned"
    os.write.over(agentFile, "- pinned probe\n", createFolders = true)
    val key = MemorySnapshot.backupFileName(agentFile)
    // 1 份「钉住」的人工快照（pinned 子树）
    val pinned = MemorySnapshot.pinnedRoot(rootP) / "20260913_0300_manual"
    os.makeDir.all(pinned)
    os.write(pinned / key, "- manual snapshot (pinned)\n")
    // 填满自动槽位：KeepPerFile 份自动时间戳目录（名字序全部低于 pinned 之外的同池目录）
    for i <- 0 until MemorySnapshot.KeepPerFile do
      val d = rootP / f"20260905-000000-000000-$i%03d"
      os.makeDir.all(d)
      os.write(d / key, s"- snapshot $i\n")

    MemorySnapshot.pruneForTest(agentFile, rootP)

    assert(os.exists(pinned / key), "pinned 子树必须不受 prune 影响（人工快照 = 钉住）")
    val auto = os
      .list(rootP)
      .filter(os.isDir(_))
      .filter(_.last != MemorySnapshot.PinnedDirName)
      .filter(d => os.exists(d / key))
    assertEquals(auto.size, MemorySnapshot.KeepPerFile, "pinned 不占槽位，自动槽位仍为 KeepPerFile")
  }

  // 布局不变性断言（对过滤行不敏感；载力见本节末「R8-B 载力」spec）
  test("R8-B：槽位满时 pinned 存在不改变自动快照的滚动行为（同池仍按名字序淘汰最老）") {
    val rootP = home / "memory-backups-pinned-2"
    os.write.over(agentFile, "- roll probe\n", createFolders = true)
    val key = MemorySnapshot.backupFileName(agentFile)
    val pinned = MemorySnapshot.pinnedRoot(rootP) / "manual-dream-0908"
    os.makeDir.all(pinned)
    os.write(pinned / key, "- pinned\n")
    for i <- 0 until MemorySnapshot.KeepPerFile + 2 do
      val d = rootP / f"20260906-000000-000000-$i%03d"
      os.makeDir.all(d)
      os.write(d / key, s"- s$i\n")

    MemorySnapshot.pruneForTest(agentFile, rootP)

    val auto = os
      .list(rootP)
      .filter(os.isDir(_))
      .filter(_.last != MemorySnapshot.PinnedDirName)
      .map(_.last)
      .sorted
    assertEquals(auto.size, MemorySnapshot.KeepPerFile, "自动槽位 = KeepPerFile")
    assertEquals(auto.head, "20260906-000000-000000-002", "同池最老 2 份被淘汰（名字序不变）")
    assert(os.exists(pinned / key), "pinned 不参与淘汰")
  }

  /**
   * **载力断言（R8-B）**：`prune` 的 `.filter(_.last != PinnedDirName)` 是「pinned 不占槽位」
   * 在**防御性布局**（`pinned` 直接含 key）下的唯一防线。
   *
   * 变异自证（2026-09-13 复核缺陷 1 的返工验证法）：删掉 `MemorySnapshot.prune` 里那行
   * `.filter(_.last != PinnedDirName)` → 本 spec **必须转红**（`pinned` 以「最新目录」身份
   * 占掉一个槽位 ⇒ 最老的自动快照 `...-000` 被挤掉 ⇒ `autoNames.size = 19`、
   * `autoNames.head = "...-001"`）；恢复该行 → 复绿。
   *
   * 断言刻意**不复用生产 filter 语义**（逐个数 `2026…` 前缀的自动目录），否则断言会随实现
   * 一起漂、给出假绿。
   */
  test("R8-B 载力：pinned 直下含 key（防御性布局）⇒ 过滤行缺席时最老自动快照被挤掉") {
    val rootP = home / "memory-backups-pinned-direct"
    os.write.over(agentFile, "- pinned direct probe\n", createFolders = true)
    val key = MemorySnapshot.backupFileName(agentFile)
    // 防御性布局：pinned 本身 = 快照目录（快照根的直接子目录，且**直接含** key）
    val pinned = MemorySnapshot.pinnedRoot(rootP)
    os.makeDir.all(pinned)
    os.write(pinned / key, "- manual snapshot (pinned, direct layout)\n")
    // 自动槽位恰好满额（目录名尾零填充计数器 ⇒ 字典序 == 时间序）
    for i <- 0 until MemorySnapshot.KeepPerFile do
      val d = rootP / f"20260905-000000-000000-$i%03d"
      os.makeDir.all(d)
      os.write(d / key, s"- snapshot $i\n")

    MemorySnapshot.pruneForTest(agentFile, rootP)

    assert(os.exists(pinned / key), "pinned 必须存活（钉住语义）")
    val autoNames = os
      .list(rootP)
      .filter(os.isDir(_))
      .map(_.last)
      .filter(_.startsWith("2026"))
      .sorted
    assertEquals(
      autoNames.size,
      MemorySnapshot.KeepPerFile,
      s"pinned 不得占槽位（过滤行缺席时会少 1 份）：${autoNames.mkString(",")}"
    )
    assertEquals(
      autoNames.head,
      "20260905-000000-000000-000",
      "最老自动快照不得被 pinned 挤掉（过滤行缺席时它会被整目录删除）"
    )
  }
end MemorySnapshotSpec
