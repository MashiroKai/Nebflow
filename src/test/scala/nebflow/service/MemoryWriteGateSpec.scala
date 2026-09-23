package nebflow.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.project.ProjectMemory

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * MemoryWriteGate spec（M4 落盘单点闸，2026-09-13 作者立项）。
 *
 * **闸判据 v2（作者 2026-09-14 两处裁定；取代 09-13 旧序）**：
 *   - **(a) 闸序 = 预算 → 快照 → 落盘**；🔴 **拒绝时不快照**，判据 = **拒绝路径零文件写
 *     （含 `backups/` 面）**；快照唯一触发点 = 「预算放行、即将落盘」。
 *   - **(b) 纯收缩豁免**：判据 = 字节比（POST ≤ PRE，逐文件），**不是动作名**；适格面 =
 *     `replace_section`（`shrinkChannel = true`）；`append`/`update`（= 缺省通道）永不豁免；
 *     只豁免预算闸——快照前置与「闸不过 ⇒ 零写」不变式照旧。
 *
 * 断言的**任务书映射**（逐条对应必给项 ④）：
 *   - **正控 A1**：走 WS `saveMemory` 旁路的**同一条代码路径**（`WebSocketRoutes:3265/3271`
 *     1:1 调 `MemoryStore.saveUserMemory` / `saveAgentMemory`）——预算超硬顶 ⇒ **被拒绝**，
 *     且**记忆面与备份面双零写**（v2：拒绝路径不快照）。
 *   - **负控 N1**：未超预算 ⇒ 正常写入，且**快照闸不误拒绝**。
 *   - **负控 N2**：超软线未超硬顶 ⇒ 放行（不是拒绝），软线不是闸。
 *   - **fail-closed A2**：快照失败 ⇒ `Code.Snapshot` 结构化拒绝 + **零写入**。
 *   - **A3**：快照内容 = **写前**盘上真身（回滚锚）。
 *   - **A4（v2 豁免正路）**：`shrinkChannel = true` + 真收缩 + 超硬顶 ⇒ **放行**，且快照照落
 *     （豁免只豁预算闸）。
 *   - **A5（v2 夹带拦截）**：`shrinkChannel = true` + **实际净增** + 超硬顶 ⇒ **照拒**
 *     （判据是字节比，不是动作名）。
 *   - **A6（非适格通道不享豁免）**：缺省通道（WS `saveMemory`）+ 收缩 + 超硬顶 ⇒ 拒绝
 *     （收缩通道是硬顶下唯一自救路径）。
 *   - **A7**：`ProjectMemory.save` 项目单点同纪律（project 常量 10KB/8KB）。
 *   - **边界 C1（明示未覆盖）**：Edit/Write/Bash 同型的直写**不经过**本闸。
 *
 * 变异臂（判红期望值；本批 v2 闸放行后实跑）：
 *   M-1 删 `MemoryStore.saveFile` 里的 `MemoryWriteGate.guard(...)` ⇒ A1 应**红**
 *       （超硬顶内容落盘成功、且无 Rejected）。
 *   M-2 删 `MemoryWriteGate.decide` 里的 `MemoryBudget.verdict` 分支（恒 `Right(())`）
 *       ⇒ A1 / A7 应**红**（拒绝消失）。
 *   M-3 把 `MemorySnapshot.snapshotBeforeWrite` 的 `Left` 分支改成 `Right(target)`
 *       ⇒ A2 应**红**（快照失败不再阻断）。
 *   M-4 **把闸序改回「快照 → 预算」（09-13 旧序）** ⇒ A1 的「备份面无新快照」断言应**红**
 *       （拒绝路径开始留快照 ⇒ 备份面只增不减）。
 *   M-5 删豁免的字节比（`shrinkChannel` 即豁免，不看 `bytes <= pre`）⇒ A5 应**红**
 *       （夹带净增被放行）。
 *   M-6 让豁免无条件（去掉 `shrinkChannel &&`）⇒ A6 应**红**（非适格通道也享豁免）。
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

  // ── 正控 A1（v2 核心）：WS 旁路同路径 · 超硬顶 ⇒ 拒绝 + 记忆面/备份面双零写 ──

  test("正控 A1：WS saveMemory 同路径（MemoryStore.saveUserMemory）超硬顶 ⇒ 结构化拒绝 + 记忆面与备份面双零写") {
    val memPath = home / "User.md"
    os.write.over(memPath, "- old entry\n", createFolders = true)
    val snapshotsBefore = snapshotDirsFor("User.md").size
    val big = contentOfBytes(MemoryBudget.UserHardBytes.toInt + 2048)
    assert(byteLen(big) > MemoryBudget.UserHardBytes, s"夹具必须真超硬顶（实际 ${byteLen(big)} B）")

    val err = intercept[MemoryWriteGate.Rejected](MemoryStore.saveUserMemory(big).unsafeRunSync())

    assertEquals(err.code, MemoryWriteGate.Code.Budget, "拒绝码须为预算码（机器可判）")
    assertEquals(os.read(memPath), "- old entry\n", "被拒 ⇒ 记忆面零变化（零写入）")
    assertEquals(snapshotDirsFor("User.md").size, snapshotsBefore, "v2 (a)：拒绝路径**不快照** ⇒ 备份面零写（拒绝是高频路径，备份面不得只增不减）")
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

  // ── 正控 A2：快照失败 ⇒ fail-closed 零写入 ────────────────────

  test("正控 A2（fail-closed）：快照失败 ⇒ Code.Snapshot 结构化拒绝 + 零写入") {
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

  // ── 正控 A3：快照内容 = 写前真身 ──────────────────────────────

  test("正控 A3：快照内容是【写前】盘上真身（回滚锚）") {
    val memPath = home / "User.md"
    os.write.over(memPath, "- pre-write A\n- pre-write B\n", createFolders = true)
    val dirsBefore = snapshotDirsFor("User.md").size

    MemoryStore.saveUserMemory("- post-write\n").unsafeRunSync()

    val dirs = snapshotDirsFor("User.md")
    assert(dirs.size > dirsBefore, "新快照目录须出现")
    val newest = dirs.maxBy(_.last)
    assertEquals(os.read(newest / "User.md"), "- pre-write A\n- pre-write B\n", "快照须持有写前字节")
  }

  // ── 正控 A4（v2 豁免正路）：适格收缩 ⇒ 只豁预算闸，快照照落 ─────

  test("正控 A4（v2 豁免正路）：shrinkChannel + 真收缩 + 超硬顶 ⇒ 放行，且快照照落") {
    val memPath = home / "User.md"
    val pre = contentOfBytes(53 * 1024)
    val shrunk = contentOfBytes(52 * 1024)
    os.write.over(memPath, pre, createFolders = true)
    val snapshotsBefore = snapshotDirsFor("User.md").size
    assert(byteLen(shrunk) > MemoryBudget.UserHardBytes, s"夹具须仍超硬顶（实际 ${byteLen(shrunk)} B）")
    assert(byteLen(shrunk) <= byteLen(pre), "夹具须为真收缩（POST ≤ PRE）")

    // 收缩通道（replace_section 的落盘面）声明适格身份。guard 只闸不写——落盘是调用方
    // 义务（同 MemoryStore.saveFile 形：guard *> os.write.over）。
    MemoryWriteGate.guard("user", memPath, shrunk, shrinkChannel = true).unsafeRunSync() // 不抛 = 豁免放行
    IO.blocking(os.write.over(memPath, shrunk, createFolders = true)).unsafeRunSync()

    assertEquals(os.read(memPath), shrunk, "豁免 ⇒ 收缩写入真的落盘（硬顶下的自救路径）")
    assert(snapshotDirsFor("User.md").size > snapshotsBefore, "豁免只豁预算闸 ⇒ 快照前置照旧（放行路径的唯一触发点）")
  }

  // ── 正控 A5（v2 夹带拦截）：适格但净增 ⇒ 照拒 ─────────────────

  test("正控 A5（v2 夹带拦截）：shrinkChannel + 实际净增 + 超硬顶 ⇒ 照拒（字节比判据）") {
    val memPath = home / "User.md"
    val pre = contentOfBytes(52 * 1024)
    val grown = contentOfBytes(53 * 1024)
    os.write.over(memPath, pre, createFolders = true)
    val snapshotsBefore = snapshotDirsFor("User.md").size
    assert(byteLen(grown) > byteLen(pre), "夹具须为净增（POST > PRE）")
    assert(byteLen(grown) > MemoryBudget.UserHardBytes, "夹具须超硬顶")

    val err = intercept[MemoryWriteGate.Rejected](
      MemoryWriteGate.guard("user", memPath, grown, shrinkChannel = true).unsafeRunSync()
    )

    assertEquals(err.code, MemoryWriteGate.Code.Budget, "净增 ⇒ 不享豁免（判据是字节比，不是动作名）")
    assertEquals(os.read(memPath), pre, "被拒 ⇒ 零写入")
    assertEquals(snapshotDirsFor("User.md").size, snapshotsBefore, "被拒 ⇒ 备份面零写（v2 (a)）")
  }

  // ── 正控 A6：非适格通道的收缩不享豁免 ─────────────────────────

  test("正控 A6：缺省通道（WS saveMemory）+ 收缩 + 超硬顶 ⇒ 拒绝（收缩通道才享豁免）") {
    val memPath = home / "User.md"
    val pre = contentOfBytes(53 * 1024)
    val shrunk = contentOfBytes(52 * 1024)
    os.write.over(memPath, pre, createFolders = true)
    val snapshotsBefore = snapshotDirsFor("User.md").size

    val err = intercept[MemoryWriteGate.Rejected](MemoryStore.saveUserMemory(shrunk).unsafeRunSync())

    assertEquals(err.code, MemoryWriteGate.Code.Budget, "整文件覆盖不是收缩通道 ⇒ 无豁免")
    assertEquals(os.read(memPath), pre, "被拒 ⇒ 零写入")
    assertEquals(snapshotDirsFor("User.md").size, snapshotsBefore, "被拒 ⇒ 备份面零写")
  }

  // ── 正控 A7：ProjectMemory 项目单点同纪律 ─────────────────────

  test("正控 A7：ProjectMemory.save 超 project 硬顶 ⇒ 拒绝（不快照）；预算内 ⇒ 落盘") {
    val ws = home / "ws-proj-a"
    val p = ws / ".nebflow" / "memory.md"
    os.write.over(p, "- proj old\n", createFolders = true)
    val fileName = MemorySnapshot.backupFileName(p)
    val snapshotsBefore = snapshotDirsFor(fileName).size

    val big = contentOfBytes(MemoryBudget.ProjectHardBytes.toInt + 1024)
    assert(byteLen(big) > MemoryBudget.ProjectHardBytes, s"夹具须真超 project 硬顶（实际 ${byteLen(big)} B）")
    val err = intercept[MemoryWriteGate.Rejected](ProjectMemory.save(p, big).unsafeRunSync())
    assertEquals(err.code, MemoryWriteGate.Code.Budget)
    assertEquals(os.read(p), "- proj old\n", "被拒 ⇒ 零写入")
    assertEquals(snapshotDirsFor(fileName).size, snapshotsBefore, "被拒 ⇒ 备份面零写（v2 (a) 同纪律）")

    val small = contentOfBytes(1024)
    ProjectMemory.save(p, small).unsafeRunSync()
    assertEquals(os.read(p), small, "预算内 ⇒ 落盘")
  }

  // ── 边界 C1：明示未覆盖（Write / Edit / Bash 同型直写） ────────

  test("边界 C1（明示未覆盖）：os.write 直写不经过本闸 —— 超硬顶内容照落") {
    val memPath = home / "User.md"
    val big = contentOfBytes(MemoryBudget.UserHardBytes.toInt + 4096)
    os.write.over(memPath, big, createFolders = true) // = Edit / Write / Bash 同型直写
    assert(byteLen(os.read(memPath)) > MemoryBudget.UserHardBytes, "本项**不覆盖**直写通道：直写不受本闸影响（如实登记，非缺陷承诺）")
  }

end MemoryWriteGateSpec
