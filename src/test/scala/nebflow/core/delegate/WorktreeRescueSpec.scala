package nebflow.core.delegate

import munit.FunSuite

/**
 * WorktreeRescue 抢救守卫判读（真目录夹具，零 git 面——守卫本身是纯文件面）：
 *
 *  - ① 未并报告被抢救：worktree reports/evidence 下主工作区没有的文件 →
 *    同相对路径复制进主工作区（嵌套目录补建）；reports/evidence 之外的路径
 *    绝不碰；
 *  - ② 已并文件不重复覆盖：主工作区已存在的同相对路径 → 跳过、内容原样；
 *  - ③ 无 reports/evidence ⇒ 空账面零动作（主工作区不被创建任何东西——
 *    行为与守卫落地前逐字同）；
 *  - ④ worktree 缺失 / 非目录 ⇒ 空账面、绝不抛（fail-soft 全包）。
 */
class WorktreeRescueSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-worktree-rescue"

  override def beforeEach(context: munit.BeforeEach): Unit = os.remove.all(tempRoot)

  private def mkWsWt(tag: String): (os.Path, os.Path) =
    val ws = tempRoot / s"$tag-ws"
    val wt = tempRoot / s"$tag-wt"
    os.makeDir.all(ws)
    os.makeDir.all(wt)
    (ws, wt)

  test(
    "① unmerged report rescued: missing in main ⇒ same relative path, nested dirs created; paths outside reports/evidence untouched"
  ) {
    val (ws, wt) = mkWsWt("rescue")
    os.makeDir.all(wt / "reports" / "20261024_120000_fix" / "shots")
    os.write(wt / "reports" / "20261024_120000_fix" / "REPORT.md", "the lost report body")
    os.write(wt / "reports" / "20261024_120000_fix" / "shots" / "graph-data.json", "{}")
    os.makeDir.all(wt / "evidence" / "probe")
    os.write(wt / "evidence" / "probe" / "raw.md", "evidence body")
    os.makeDir.all(wt / "src" / "nebflow")
    os.write(wt / "src" / "nebflow" / "Secret.scala", "must not be copied")

    val r = WorktreeRescue.rescue(ws, wt)

    assertEquals(os.read(ws / "reports" / "20261024_120000_fix" / "REPORT.md"), "the lost report body")
    assertEquals(os.read(ws / "reports" / "20261024_120000_fix" / "shots" / "graph-data.json"), "{}")
    assertEquals(os.read(ws / "evidence" / "probe" / "raw.md"), "evidence body")
    assert(!os.exists(ws / "src"), "paths outside reports/evidence must never be rescued")
    assertEquals(
      r.rescued.sorted,
      List(
        "evidence/probe/raw.md",
        "reports/20261024_120000_fix/REPORT.md",
        "reports/20261024_120000_fix/shots/graph-data.json"
      )
    )
    assert(r.skipped.isEmpty && r.failed.isEmpty, s"clean scene ⇒ no skips/failures: $r")
  }

  test("② already-present files are skipped, never overwritten") {
    val (ws, wt) = mkWsWt("skip")
    val rel = os.rel / "reports" / "20261024_130000_fix" / "REPORT.md"
    os.makeDir.all(wt / rel / os.up)
    os.write(wt / rel, "worktree version")
    os.makeDir.all(ws / rel / os.up)
    os.write(ws / rel, "main version")

    val r = WorktreeRescue.rescue(ws, wt)

    assertEquals(os.read(ws / rel), "main version", "an existing main-workspace file must never be overwritten")
    assertEquals(r.skipped, List(rel.toString))
    assert(r.rescued.isEmpty && r.failed.isEmpty, s"only the skip is recorded: $r")
  }

  test("③ no reports/evidence ⇒ empty report, zero side effects in the main workspace") {
    val (ws, wt) = mkWsWt("empty")
    os.makeDir.all(wt / "src")
    os.write(wt / "src" / "Code.scala", "x")

    val r = WorktreeRescue.rescue(ws, wt)

    assertEquals(r, WorktreeRescue.Report())
    assert(!os.exists(ws / "reports") && !os.exists(ws / "evidence"), "no scan hits ⇒ nothing is created in main")
  }

  test("④ missing worktree directory / non-directory worktree ⇒ empty report, never throws (fail-soft)") {
    val (ws, _) = mkWsWt("soft")
    assertEquals(WorktreeRescue.rescue(ws, tempRoot / "no-such-wt"), WorktreeRescue.Report())
    os.write(tempRoot / "plain-file", "not a dir")
    assertEquals(WorktreeRescue.rescue(ws, tempRoot / "plain-file"), WorktreeRescue.Report())
  }

end WorktreeRescueSpec
