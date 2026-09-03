package nebflow.core

import munit.FunSuite

/**
 * NodeEdit worktree 参数修复（20260903，根因报告 20260903_nodeedit-worktree-param-fix.md）
 * 单测三域：
 *
 * 1. normalizeWorktree 全形态矩阵：裸名/四种前缀形态收（Right 裸名）；绝对路径、
 *    ".."、剥后仍含 "/"、空、"." 拒（Left，WORKTREE_FORMAT 可行动文案四要素）。
 * 2. resolveWorktreeDir 双位置实存：worktrees/ 权威优先 / 顶层存量 fallback /
 *    双现权威赢 / 均无 None / 顶层软链沿链解析（生产实况：分发器孤儿清理后
 *    顶层同名条目为指向权威位置的软链）。
 * 3. resolveNodeProjectRoot（NodeEngine cwd 单点）：顶层存量命中 = 旧公式
 *    `(os.Path(workspace) / ".nebflow" / wt).toString` 逐字节一致（回归红线）；
 *    权威位置 / 前缀形态 / 双删窗口 / 损坏值兜底。
 */
class WorktreeParamSpec extends FunSuite:

  private val Example = "micorb-config-hide"
  private val run = System.nanoTime().toString.take(8) // 同 target 目录多轮 run 隔离

  private def ws(name: String): os.Path =
    val p = os.pwd / "target" / s"test-worktree-param-$run-$name"
    os.remove.all(p)
    os.makeDir.all(p / ".nebflow")
    p

  // ── 1. normalizeWorktree：收（Right 裸名）────────────────────

  test("normalize: bare name → Right(bare)") {
    assertEquals(PathUtil.normalizeWorktree(Example), Right(Example))
  }

  test("normalize: 'worktrees/<name>' prefix → Right(bare)") {
    assertEquals(PathUtil.normalizeWorktree(s"worktrees/$Example"), Right(Example))
  }

  test("normalize: '.nebflow/worktrees/<name>' prefix → Right(bare)") {
    assertEquals(PathUtil.normalizeWorktree(s".nebflow/worktrees/$Example"), Right(Example))
  }

  test("normalize: '.nebflow/<name>' top-level form → Right(bare)") {
    assertEquals(PathUtil.normalizeWorktree(s".nebflow/$Example"), Right(Example))
  }

  test("normalize: './<name>' leading form → Right(bare)") {
    assertEquals(PathUtil.normalizeWorktree(s"./$Example"), Right(Example))
  }

  test("normalize: './worktrees/<name>' combined form → Right(bare)") {
    assertEquals(PathUtil.normalizeWorktree(s"./worktrees/$Example"), Right(Example))
  }

  test("normalize: surrounding whitespace trimmed") {
    assertEquals(PathUtil.normalizeWorktree(s"  worktrees/$Example \n"), Right(Example))
  }

  // ── 1. normalizeWorktree：拒（Left，WORKTREE_FORMAT 可行动文案）──────

  test("reject: absolute path '/abs/x'") {
    val r = PathUtil.normalizeWorktree("/abs/x")
    assert(r.isLeft)
    checkActionable(r.swap.toOption.get)
  }

  test("reject: '..' traversal '../x'") {
    val r = PathUtil.normalizeWorktree("../x")
    assert(r.isLeft)
    checkActionable(r.swap.toOption.get)
  }

  test("reject: multi-segment residue 'a/b' (no known prefix, still contains /)") {
    val r = PathUtil.normalizeWorktree("a/b")
    assert(r.isLeft)
    checkActionable(r.swap.toOption.get)
  }

  test("reject: empty string") {
    val r = PathUtil.normalizeWorktree("")
    assert(r.isLeft)
    checkActionable(r.swap.toOption.get)
  }

  test("reject: bare '.' (current-dir segment, not a worktree name)") {
    val r = PathUtil.normalizeWorktree(".")
    assert(r.isLeft)
    checkActionable(r.swap.toOption.get)
  }

  test("reject: prefix residue '.nebflow/worktrees/a/b' (still multi-segment after strip)") {
    val r = PathUtil.normalizeWorktree(".nebflow/worktrees/a/b")
    assert(r.isLeft)
    checkActionable(r.swap.toOption.get)
  }

  test("normalize: 'v2..fix' (double dot inside one segment) accepted — segment-level '..' check") {
    // QC P3：'..' 按段级拒绝（../x 拒），合法名 v2..fix 放行（旧公式经 os-lib 本就接受）
    assertEquals(PathUtil.normalizeWorktree("v2..fix"), Right("v2..fix"))
  }

  test("reject: absolute windows drive 'C:\\\\x'") {
    val r = PathUtil.normalizeWorktree("C:\\x")
    assert(r.isLeft)
    checkActionable(r.swap.toOption.get)
  }

  /** 轴 2 文案四要素：错误码 + 示例裸名 + 禁形态提示 + 补救 git 命令；
    * 且 os-lib 原始文案（"not a valid path segment"）不得外泄。 */
  private def checkActionable(msg: String): Unit =
    assert(msg.contains("WORKTREE_FORMAT"), s"error code missing: $msg")
    assert(msg.contains(Example), s"example bare name missing: $msg")
    assert(msg.contains("worktrees/"), s"forbidden-prefix hint missing: $msg")
    assert(msg.contains("git worktree add"), s"remedy command missing: $msg")
    assert(!msg.contains("not a valid path segment"), s"os-lib raw message leaked: $msg")

  // ── 2. resolveWorktreeDir：双位置实存 ─────────────────────

  test("resolveWorktreeDir: authoritative worktrees/<name> when only there") {
    val w = ws("auth")
    os.makeDir.all(w / ".nebflow" / "worktrees" / "wt-a")
    assertEquals(PathUtil.resolveWorktreeDir(w, "wt-a"), Some(w / ".nebflow" / "worktrees" / "wt-a"))
  }

  test("resolveWorktreeDir: top-level legacy fallback when only there") {
    val w = ws("legacy")
    os.makeDir.all(w / ".nebflow" / "wt-b")
    assertEquals(PathUtil.resolveWorktreeDir(w, "wt-b"), Some(w / ".nebflow" / "wt-b"))
  }

  test("resolveWorktreeDir: authoritative wins when both positions exist") {
    val w = ws("both")
    os.makeDir.all(w / ".nebflow" / "worktrees" / "wt-c")
    os.makeDir.all(w / ".nebflow" / "wt-c")
    assertEquals(PathUtil.resolveWorktreeDir(w, "wt-c"), Some(w / ".nebflow" / "worktrees" / "wt-c"))
  }

  test("resolveWorktreeDir: neither position → None") {
    val w = ws("none")
    assertEquals(PathUtil.resolveWorktreeDir(w, "wt-d"), None)
  }

  test("resolveWorktreeDir: reserved top-level system dir 'skills' never treated as worktree fallback (QC P1)") {
    val w = ws("reserved")
    os.makeDir.all(w / ".nebflow" / "skills")
    assertEquals(PathUtil.resolveWorktreeDir(w, "skills"), None)
    assertEquals(PathUtil.resolveWorktreeDir(w, "commands"), None)
    assertEquals(PathUtil.resolveWorktreeDir(w, "worktrees"), None)
  }

  test("resolveWorktreeDir: authoritative worktrees/<reserved-name> still resolves (guard is fallback-only)") {
    val w = ws("reserved-auth")
    os.makeDir.all(w / ".nebflow" / "worktrees" / "skills")
    assertEquals(PathUtil.resolveWorktreeDir(w, "skills"), Some(w / ".nebflow" / "worktrees" / "skills"))
  }

  test("resolveWorktreeDir: authoritative wins over top-level symlink alias (production cleanup shape)") {
    // 生产实况（分发器孤儿清理后）：worktrees/<名> 实存 + 顶层 <名> 为指向它的软链
    // → 权威分支赢，返回 canonical 路径（同物理目录，沙箱 canonicalize 后等价）。
    val w = ws("alias")
    os.makeDir.all(w / ".nebflow" / "worktrees" / "wt-e")
    java.nio.file.Files.createSymbolicLink(
      (w / ".nebflow" / "wt-e").toNIO,
      (os.rel / "worktrees" / "wt-e").toNIO)
    assertEquals(PathUtil.resolveWorktreeDir(w, "wt-e"), Some(w / ".nebflow" / "worktrees" / "wt-e"))
  }

  test("resolveWorktreeDir: top-level symlink resolves via fallback when worktrees/<name> absent") {
    // os.exists 沿软链（follow-links）语义与现状一致：软链指向的实存目录即命中。
    val w = ws("symlink")
    os.makeDir.all(w / ".nebflow" / "real-wt-f")
    java.nio.file.Files.createSymbolicLink(
      (w / ".nebflow" / "wt-f").toNIO,
      (os.rel / "real-wt-f").toNIO)
    assertEquals(PathUtil.resolveWorktreeDir(w, "wt-f"), Some(w / ".nebflow" / "wt-f"))
  }

  // ── 3. resolveNodeProjectRoot：cwd 回归红线 ────────────────

  test("projectRoot: legacy top-level bare name = old formula byte-identical (red line)") {
    val w = ws("root-legacy")
    os.makeDir.all(w / ".nebflow" / "wt-b")
    val legacyFormula = (os.Path(w.toString) / ".nebflow" / "wt-b").toString
    assertEquals(PathUtil.resolveNodeProjectRoot(w.toString, Some("wt-b")), legacyFormula)
  }

  test("projectRoot: authoritative worktrees/<name> node → worktrees path") {
    val w = ws("root-auth")
    os.makeDir.all(w / ".nebflow" / "worktrees" / "wt-a")
    assertEquals(
      PathUtil.resolveNodeProjectRoot(w.toString, Some("wt-a")),
      (os.Path(w.toString) / ".nebflow" / "worktrees" / "wt-a").toString)
  }

  test("projectRoot: prefix-form stored value ('worktrees/<n>') resolves to authoritative dir") {
    val w = ws("root-prefix")
    os.makeDir.all(w / ".nebflow" / "worktrees" / "wt-a")
    assertEquals(
      PathUtil.resolveNodeProjectRoot(w.toString, Some("worktrees/wt-a")),
      (os.Path(w.toString) / ".nebflow" / "worktrees" / "wt-a").toString)
  }

  test("projectRoot: no worktree → workspace unchanged") {
    val w = ws("root-none")
    assertEquals(PathUtil.resolveNodeProjectRoot(w.toString, None), w.toString)
  }

  test("projectRoot: both positions gone (deleted-dir window) → legacy formula fallback") {
    val w = ws("root-gone")
    assertEquals(
      PathUtil.resolveNodeProjectRoot(w.toString, Some("wt-gone")),
      (os.Path(w.toString) / ".nebflow" / "wt-gone").toString)
  }

  test("projectRoot: corrupt stored value ('a/b') → workspace fallback, no crash") {
    val w = ws("root-corrupt")
    assertEquals(PathUtil.resolveNodeProjectRoot(w.toString, Some("a/b")), w.toString)
  }

end WorktreeParamSpec
