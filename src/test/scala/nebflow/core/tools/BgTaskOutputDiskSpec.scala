package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.nio.file.Files

/**
 * 后台任务输出查看补全批（作者令 2026-10-25 需求④「本地任务输出落盘 + 记录
 * 路径」）· [[BgTaskOutputDisk]] 纯文件面 spec（temp dir 注入，零全局态）。
 *
 * 中心契约：
 *   - 行追加写（cap 超限停写 + truncated 记账；写失败静默降级）；
 *   - meta 原子落盘（temp+move）与读回（缺文件/损坏 = None）；
 *   - log 切片读与内存读同协议（字节游标 + 行对齐 + nextOffset + 到尾旗标）；
 *   - sweep 成对保留最近 N 对（观测面 pairCount 可断言）。
 */
class BgTaskOutputDiskSpec extends FunSuite:

  private def tmpDir(): os.Path =
    os.Path(Files.createTempDirectory("bg-output-disk-spec").toRealPath().toString)

  // ── LogWriter：行追加 + cap 停写 ─────────────────────────────────────

  test("LogWriter appends lines; bytesWritten counts line bytes (+newline)") {
    val dir = tmpDir()
    try
      val w = new BgTaskOutputDisk.LogWriter(BgTaskOutputDisk.logPath(dir, "j1"), capBytes = 1024 * 1024)
      w.writeLine("hello")
      w.writeLine("world 二") // 多字节行同口径（UTF-8 长度 + 1）
      w.flushClose()
      val content = os.read(BgTaskOutputDisk.logPath(dir, "j1"))
      assertEquals(content, "hello\nworld 二\n")
      val expect = "hello".getBytes.length + 1 + "world 二".getBytes.length + 1
      assertEquals(w.bytesWritten, expect.toLong)
      assert(!w.persistTruncated)
    finally os.remove.all(dir)
  }

  test("LogWriter cap: stops writing past the cap and marks persistTruncated") {
    val dir = tmpDir()
    try
      val w = new BgTaskOutputDisk.LogWriter(BgTaskOutputDisk.logPath(dir, "j2"), capBytes = 10)
      w.writeLine("line-1") // 7 bytes ≤ 10
      w.writeLine("line-2") // 越限 → 停写 + truncated
      val stopped = w.persistTruncated
      w.writeLine("line-3") // 停写后 no-op
      w.flushClose()
      assert(stopped, "cap 超限必须置 truncated")
      assert(w.persistTruncated)
      val content = os.read(BgTaskOutputDisk.logPath(dir, "j2"))
      assert(!content.contains("line-3"), "停写后不得再落行")
    finally os.remove.all(dir)
  }

  // ── meta：原子写 + 读回 ──────────────────────────────────────────────

  test("meta round-trips (writeMeta/readMeta); missing = None") {
    val dir = tmpDir()
    try
      assertEquals(BgTaskOutputDisk.readMeta(dir, "nope"), None)
      val meta = BgTaskOutputDisk.Meta(
        jobId = "j3",
        status = "completed",
        exitCode = Some(3),
        errorHint = Some("boom"),
        startedAtMs = Some(111L),
        finishedAtMs = Some(222L),
        description = Some("desc"),
        totalBytes = 456L,
        totalLines = 12L,
        headDropped = true,
        persistTruncated = false,
        kind = Some("local"),
        deviceName = None,
        outputPath = Some("/data/bg-task-logs/j3.log")
      )
      BgTaskOutputDisk.writeMeta(dir, meta)
      val back = BgTaskOutputDisk.readMeta(dir, "j3")
      assertEquals(back, Some(meta))
      // 无残留 temp 文件（原子 move 收口）
      assertEquals(os.list(dir).filter(_.last.endsWith(".tmp")).size, 0)
    finally os.remove.all(dir)
  }

  // ── readSlice：字节游标 + 行对齐 ─────────────────────────────────────

  test("readSlice: offset semantics mirror the in-memory buffer (line-aligned, nextOffset, eof flag)") {
    val dir = tmpDir()
    try
      val w = new BgTaskOutputDisk.LogWriter(BgTaskOutputDisk.logPath(dir, "j4"))
      w.writeLine("alpha")
      w.writeLine("beta")
      w.writeLine("gamma")
      w.flushClose()
      // offset=0 全量
      val all = BgTaskOutputDisk.readSlice(dir, "j4", 0L).get
      assertEquals(all._1, "alpha\nbeta\ngamma\n")
      assertEquals(all._2, ("alpha\nbeta\ngamma\n".getBytes.length).toLong)
      assertEquals(all._3, true)
      // offset 落在行中（跳过 "al"）→ 对齐到下一行首
      val mid = BgTaskOutputDisk.readSlice(dir, "j4", 2L).get
      assertEquals(mid._1, "beta\ngamma\n")
      // offset 越过末尾 = 空且到尾
      val past = BgTaskOutputDisk.readSlice(dir, "j4", 10_000L).get
      assertEquals(past._1, "")
      assertEquals(past._3, true)
    finally os.remove.all(dir)
  }

  test("readSlice: missing log = None (meta-only tasks serve empty output at the store layer)") {
    val dir = tmpDir()
    try assertEquals(BgTaskOutputDisk.readSlice(dir, "nope", 0L), None)
    finally os.remove.all(dir)
  }

  // ── sweep：成对保留最近 N 对 ────────────────────────────────────────

  test("sweep keeps the newest N pairs (log+meta deleted together)") {
    val dir = tmpDir()
    try
      // 造 5 对 + 1 孤儿 log；保留 3 对 ⇒ 最旧 2 对（log+meta）与孤儿一起消亡。
      for i <- 1 to 5 do
        val id = s"sw-$i"
        os.write.over(BgTaskOutputDisk.logPath(dir, id), s"content-$i\n", createFolders = true)
        // 错开 mtime（同秒内 fs 时间戳粒度可能相同）
        Thread.sleep(5)
        BgTaskOutputDisk.writeMeta(dir, BgTaskOutputDisk.Meta(jobId = id, status = "completed"))
      os.write.over(BgTaskOutputDisk.logPath(dir, "orphan"), "orphan\n")
      Thread.sleep(5)
      BgTaskOutputDisk.writeMeta(dir, BgTaskOutputDisk.Meta(jobId = "orphan", status = "failed"))
      assertEquals(BgTaskOutputDisk.pairCount(dir), 6)

      BgTaskOutputDisk.sweep(dir, keep = 3)
      assertEquals(BgTaskOutputDisk.pairCount(dir), 3)
      // 最新的 3 对在（orphan 最后写 = 最新）
      assert(os.exists(BgTaskOutputDisk.metaPath(dir, "orphan")))
      assert(os.exists(BgTaskOutputDisk.logPath(dir, "orphan")))
      assert(os.exists(BgTaskOutputDisk.metaPath(dir, "sw-5")))
      assert(os.exists(BgTaskOutputDisk.logPath(dir, "sw-4")))
      // 最旧的消亡（成对）
      assert(!os.exists(BgTaskOutputDisk.metaPath(dir, "sw-1")))
      assert(!os.exists(BgTaskOutputDisk.logPath(dir, "sw-1")))
      assert(!os.exists(BgTaskOutputDisk.metaPath(dir, "sw-2")))
    finally os.remove.all(dir)
  }

  // ── Store 集成：落盘开启后的 round-trip（fillRemote + 落盘兜底读）──────

  test("store round-trip with persistence on: fillRemote lands in memory AND on disk; read falls back to disk after retention miss") {
    val root = tmpDir()
    val dir = BgTaskOutputDisk.dir(root) // store 落在 <root>/bg-task-logs/
    try
      BgTaskOutputStore.enablePersistence(root)
      val id = s"store-disk-${System.nanoTime().toString.take(8)}"
      val origin = BgTaskOutputStore.Origin("remote", "KAI", "/c/Users/Kai/stand-win-test5.log", "[KAI] full test", 1234L)
      BgTaskOutputStore
        .fillRemote(id, "line A\nline B\n", "completed", Some(2), None, origin)
        .unsafeRunSync()
      // 内存读（留存区命中）：origin 面随读回
      val mem = BgTaskOutputStore.read(id, 0L).unsafeRunSync().get
      assertEquals(mem.status, "completed")
      assertEquals(mem.output, "line A\nline B\n")
      assertEquals(mem.exitCode, Some(2))
      assertEquals(mem.kind, Some("remote"))
      assertEquals(mem.deviceName, Some("KAI"))
      assertEquals(mem.outputPath, Some("/c/Users/Kai/stand-win-test5.log"))
      // 盘上面在（meta + log 成对）
      assert(os.exists(BgTaskOutputDisk.metaPath(dir, id)))
      assert(os.exists(BgTaskOutputDisk.logPath(dir, id)))
      // 直接构造「留存 miss」面：读一个只存在盘上的 jobId（meta 手工落，模拟
      // 引擎重启后的旧任务——内存表空，落盘兜底可回看）
      val orphanId = s"store-orphan-${System.nanoTime().toString.take(8)}"
      BgTaskOutputDisk.writeMeta(
        dir,
        BgTaskOutputDisk.Meta(
          jobId = orphanId, status = "failed", exitCode = None, errorHint = Some("old crash"),
          totalBytes = 5L, totalLines = 1L, kind = Some("local"),
          outputPath = Some(s"$dir/$orphanId.log")
        )
      )
      os.write.over(BgTaskOutputDisk.logPath(dir, orphanId), "oops\n", createFolders = true)
      val disk = BgTaskOutputStore.read(orphanId, 0L).unsafeRunSync().get
      assertEquals(disk.status, "failed")
      assertEquals(disk.output, "oops\n")
      assertEquals(disk.errorHint, Some("old crash"))
      assertEquals(disk.kind, Some("local"))
    finally os.remove.all(root)
  }
end BgTaskOutputDiskSpec
