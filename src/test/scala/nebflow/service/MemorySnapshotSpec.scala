package nebflow.service

import munit.FunSuite
import nebflow.core.PathUtil

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
