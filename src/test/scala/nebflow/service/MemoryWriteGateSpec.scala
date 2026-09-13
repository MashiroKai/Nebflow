package nebflow.service

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.project.ProjectMemory

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * MemoryWriteGate spec（M4 落盘单点闸，2026-09-13 作者立项）。
 *
 * 断言的**任务书映射**（逐条对应必给项 ④）：
 *   - **正控 A1**：走 WS `saveMemory` 旁路的**同一条代码路径**（`WebSocketRoutes` 该分支
 *     1:1 调 `MemoryStore.saveUserMemory`，见 `WebSocketRoutes.scala` 该 case）——预算超硬顶
 *     ⇒ **被拒绝**，且 **产生快照**、盘上文件零变化。
 *   - **负控 N1**：未超预算 ⇒ 正常写入，且**快照闸不误拒绝**。
 *   - **负控 N2**：超软线未超硬顶 ⇒ 放行（不是拒绝），快照照落（软线不是闸）。
 *   - **fail-closed A4**：快照失败 ⇒ `Code.Snapshot` 结构化拒绝 + **零写入**。
 *   - **正控 A5**：快照内容 = **写前**盘上真身（回滚锚）。
 *   - **ProjectMemory A6**：项目单点同纪律（project 常量 10KB/8KB）。
 *   - **边界 C1（明示未覆盖）**：Edit/Write/Bash 同型的直写**不经过**本闸。
 *
 * 变异臂（判红期望值；**本批受资源闸不跑**，见任务书 ⑥ 与资源降级申报）：
 *   M-1 删 `MemoryStore.saveFile` 里的 `MemoryWriteGate.guard(...)` ⇒ A1 应**红**
 *       （超硬顶内容落盘成功、且无 Rejected）。
 *   M-2 删 `MemoryWriteGate.decide` 里的 `MemoryBudget.verdict` 分支（恒 `Right(())`）
 *       ⇒ A1 / A6 应**红**（拒绝消失）。
 *   M-3 把 `MemorySnapshot.snapshotBeforeWrite` 的 `Left` 分支改成 `Right(target)`
 *       ⇒ A4 应**红**（快照失败不再阻断）。
 *   M-4 把闸序改成「预算 → 快照」（即旧 MemoryEdit 次序）⇒ A1 的
 *       「且产生快照」断言应**红**（预算拒绝时不再快照）。
 *   全部变异验红后必须恢复并复绿（仓内先例：verification-rigor §1 定点替换还原，
 *   勿用 `git checkout --` 抹掉同文件未提交改动）。
 */
class MemoryWriteGateSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-memgate"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  // ── 夹具 ────────────────────────────────────────────────────────

  private def backupRoot: os.Path = home / "memory-backups"

  /** 含该目标文件快照的目录（升序）。 */
  private def snapshotDirsFor(fileName: String): Vector[os.Path] =
    if !os.exists(backupRoot) then Vector.empty
    else
      os.list(backupRoot)
        .filter(os.isDir(_))
        .filter(d => os.exists(d / fileName))
        .sortBy(_.last)
        .toVector

  private def byteLen(s: String): Long = s.getBytes(StandardCharsets.UTF_8).length.toLong

  /** 造「单节 + N 条目行」内容，字节数 ≈ n（用于精确跨过软线 / 硬顶）。 */
  private def contentOfBytes(n: Int): String =
    val head = "# T\n\n## 节\n\n"
    val line = "- " + ("y" * 100) + "\n" // 103 B
    val lines = math.max(0, (n - byteLen(head)) / byteLen(line)).toInt
    head + line * lines

  // ── 正控 A1：WS 旁路同路径 · 超硬顶 ⇒ 拒绝 + 快照 + 零写入 ──────

  test("正控 A1：WS saveMemory 同路径（MemoryStore.saveUserMemory）超硬顶 ⇒ 结构化拒绝，且产生快照") {
    val memPath = home / "User.md"
    os.write.over(memPath, "- old entry\n", createFolders = true)
    val big = contentOfBytes(MemoryBudget.UserHardBytes.toInt + 2048)
    assert(byteLen(big) > MemoryBudget.UserHardBytes, s"夹具必须真超硬顶（实际 ${byteLen(big)} B）")

    val err = intercept[MemoryWriteGate.Rejected](MemoryStore.saveUserMemory(big).unsafeRunSync())

    assertEquals(err.code, MemoryWriteGate.Code.Budget, "拒绝码须为预算码（机器可判）")
    assertEquals(os.read(memPath), "- old entry\n", "被拒 ⇒ 盘上文件零变化（零写入）")
    assert(snapshotDirsFor("User.md").nonEmpty, "正控要求：预算超限被拒时**仍产生快照**")
    assert(err.detail.contains("## 节"), "拒绝文本须含 top-3 最大节（可行动）")
  }

  // ── 负控 N1：未超预算 ⇒ 正常写入 + 快照不误拒绝 ────────────────

  test("负控 N1：未超预算 ⇒ 正常写入，快照闸不误拒绝") {
    val memPath = home / "User.md"
    os.write.over(memPath, "- old entry\n", createFolders = true)
    val small = contentOfBytes(2 * 1024)
    assert(byteLen(small) < MemoryBudget.UserSoftBytes, "夹具须在预算内")

    MemoryStore.saveUserMemory(small).unsafeRunSync() // 不抛 = 过闸

    assertEquals(os.read(memPath), small, "过闸 ⇒ 内容落盘")
    assert(snapshotDirsFor("User.md").nonEmpty, "过闸写入同样留下写前快照（回滚锚）")
  }

  // ── 负控 N2：软警区 ⇒ 放行（不是拒绝） ─────────────────────────

  test("负控 N2：超软线未超硬顶 ⇒ 放行（软线不是闸）") {
    val memPath = home / "User.md"
    os.write.over(memPath, "- old entry\n", createFolders = true)
    val warnBand = contentOfBytes(45 * 1024)
    val n = byteLen(warnBand)
    assert(n > MemoryBudget.UserSoftBytes && n <= MemoryBudget.UserHardBytes, s"夹具须落在软警区（实际 $n B）")

    MemoryStore.saveUserMemory(warnBand).unsafeRunSync() // 不抛 = 放行

    assertEquals(os.read(memPath), warnBand, "软警区放行 ⇒ 内容落盘")
  }

  // ── 正控 A4：快照失败 ⇒ fail-closed 零写入 ────────────────────

  test("正控 A4（fail-closed）：快照失败 ⇒ Code.Snapshot 结构化拒绝 + 零写入") {
    val memPath = home / "User.md"
    os.write.over(memPath, "- untouched\n", createFolders = true)
    // 快照根位置放一个【文件】⇒ createFolders 必败（MemorySnapshotSpec 同款夹具）：
    // 默认 backupRoot = dataRoot/memory-backups，正是本闸调用的那个根。
    if os.exists(backupRoot) then os.remove.all(backupRoot)
    os.write.over(backupRoot, "not a directory", createFolders = true)
    try
      val err = intercept[MemoryWriteGate.Rejected](MemoryStore.saveUserMemory("- new entry\n").unsafeRunSync())
      assertEquals(err.code, MemoryWriteGate.Code.Snapshot, "快照失败 ⇒ 快照码（与预算拒绝可判区分）")
      assertEquals(os.read(memPath), "- untouched\n", "fail-closed ⇒ 零写入")
    finally os.remove.all(backupRoot)
  }

  // ── 正控 A5：快照内容 = 写前真身 ──────────────────────────────

  test("正控 A5：快照内容是【写前】盘上真身（回滚锚）") {
    val memPath = home / "User.md"
    os.write.over(memPath, "- pre-write A\n- pre-write B\n", createFolders = true)
    val dirsBefore = snapshotDirsFor("User.md").size

    MemoryStore.saveUserMemory("- post-write\n").unsafeRunSync()

    val dirs = snapshotDirsFor("User.md")
    assert(dirs.size > dirsBefore, "新快照目录须出现")
    val newest = dirs.maxBy(_.last)
    assertEquals(os.read(newest / "User.md"), "- pre-write A\n- pre-write B\n", "快照须持有写前字节")
  }

  // ── ProjectMemory A6：项目单点同纪律 ──────────────────────────

  test("正控 A6：ProjectMemory.save 超 project 硬顶 ⇒ 拒绝；预算内 ⇒ 落盘") {
    val ws = home / "ws-proj-a"
    val p = ws / ".nebflow" / "memory.md"
    os.write.over(p, "- proj old\n", createFolders = true)

    val big = contentOfBytes(MemoryBudget.ProjectHardBytes.toInt + 1024)
    assert(byteLen(big) > MemoryBudget.ProjectHardBytes, s"夹具须真超 project 硬顶（实际 ${byteLen(big)} B）")
    val err = intercept[MemoryWriteGate.Rejected](ProjectMemory.save(p, big).unsafeRunSync())
    assertEquals(err.code, MemoryWriteGate.Code.Budget)
    assertEquals(os.read(p), "- proj old\n", "被拒 ⇒ 零写入")

    val small = contentOfBytes(1024)
    ProjectMemory.save(p, small).unsafeRunSync()
    assertEquals(os.read(p), small, "预算内 ⇒ 落盘")
  }

  // ── 边界 C1：明示未覆盖（Write / Edit / Bash 同型直写） ────────

  test("边界 C1（明示未覆盖）：os.write 直写不经过本闸 —— 超硬顶内容照落") {
    val memPath = home / "User.md"
    val big = contentOfBytes(MemoryBudget.UserHardBytes.toInt + 4096)
    os.write.over(memPath, big, createFolders = true) // = Edit / Write / Bash 同型直写
    assert(byteLen(os.read(memPath)) > MemoryBudget.UserHardBytes,
      "本项**不覆盖**直写通道：直写不受本闸影响（如实登记，非缺陷承诺）")
  }

end MemoryWriteGateSpec
