package nebflow.core

import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * 保留窗执行的**两条独立路径**（2026-09-13 回收解耦 + D-B 抑制修复批）。
 *
 * 修前语义（本 spec 覆盖的反例）：任一窗内 `_full.jsonl` > 128 MiB ⇒
 * `scanIncomplete=true` ⇒ 孤儿清扫**整段跳过**（不是「扫到一半停」）⇒ 超限常态下
 * 孤儿**永不**清扫（实测三件窗内 full 275/208/148 MB 全超限）。
 * 修后语义：
 *   - pass 1 删除腿：出窗 jsonl 一律删，**不读内容、不受扫描预算影响**；
 *   - pass 2 引用扫描：流式 + 每轮字节预算，超预算**停在行边界**、游标跨轮续读，
 *     多轮跑完即清扫孤儿；
 *   - 引用集不完整（超限行 ⇒ poisoned）时**不清扫**（宁可少删，绝不误删活对象）。
 * 内存上界 = 单行（流式）；读取量上界 = 预算 + 单行。
 */
class LlmLogWriterPruneSpec extends FunSuite:

  private def tmpDir(): Path = Files.createTempDirectory("llm-prune-")

  private def writeFile(dir: Path, name: String, content: String): Path =
    val p = dir.resolve(name)
    Files.writeString(p, content)
    p

  private def fullEntry(hash: String): String =
    s"""{"timestamp":"2026-08-24T00:00:00Z","type":"request","system_ref":"sys-$hash","tools_ref":"tools-$hash","message_refs":["m1-$hash","m2-$hash"]}\n"""

  private def objNames(dir: Path): List[String] =
    val o = dir.resolve("objects")
    if !Files.exists(o) then Nil
    else Files.list(o).iterator().asScala.toList.map(_.getFileName.toString).filter(_.endsWith(".json"))

  private def touchObject(dir: Path, hash: String): Unit =
    Files.createDirectories(dir.resolve("objects"))
    Files.writeString(dir.resolve("objects").resolve(s"$hash.json"), "{}")

  private def fresh: LlmLogWriter.ScanState = LlmLogWriter.ScanState.empty

  // ── pass 1：删除腿独立 ────────────────────────────────────────────────

  test("pass 1: out-of-window jsonl files are deleted regardless of the scan budget"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-20_full.jsonl", fullEntry("old"))
    writeFile(dir, "2026-08-20_sse.jsonl", "{}")
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("new"))
    // 极小预算：扫描腿本轮一步也走不完
    val (_, _) = LlmLogWriter.retentionRound(dir, "2026-08-21", fresh, 1L)
    assert(!Files.exists(dir.resolve("2026-08-20_full.jsonl")), "out-of-window full deleted")
    assert(!Files.exists(dir.resolve("2026-08-20_sse.jsonl")), "out-of-window sse deleted (delete leg)")
    assert(Files.exists(dir.resolve("2026-08-24_full.jsonl")), "in-window file kept")

  test("pass 2: refs collected only from _full.jsonl (sse/summary never scanned)"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("abc") + fullEntry("def"))
    writeFile(dir, "2026-08-24_sse.jsonl", s"""{"system_ref":"sys-sse"}\n""")
    writeFile(dir, "2026-08-24_summary.jsonl", s"""{"message_refs":["m-sum"]}\n""")
    val (st, complete) = LlmLogWriter.retentionRound(dir, "2026-08-21", fresh, 128L * 1024 * 1024)
    assert(complete, "small in-window full file is fully scanned in one round")
    assert(st.used.contains("sys-abc") && st.used.contains("tools-def") && st.used.contains("m1-abc"))
    assert(!st.used.contains("sys-sse"), "sse file must not be scanned")
    assert(!st.used.contains("m-sum"), "summary file must not be scanned")

  test("a 1 MB sse file is never read (scan is bounded to full files)"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("only"))
    writeFile(dir, "2026-08-24_sse.jsonl", "z" * 1_000_000)
    val (st, complete) = LlmLogWriter.retentionRound(dir, "2026-08-21", fresh, 128L * 1024 * 1024)
    assert(complete)
    assertEquals(st.used.toSet, Set("sys-only", "tools-only", "m1-only", "m2-only"))

  test("garbage lines are ignored without failing the scan"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", "not-json\n" + fullEntry("ok") + "\n")
    val (st, complete) = LlmLogWriter.retentionRound(dir, "2026-08-21", fresh, 128L * 1024 * 1024)
    assert(complete)
    assert(st.used.contains("sys-ok"), "valid line after garbage still collected")

  // ── D-B：预算耗尽 ≠ 放弃（多轮续读） ────────────────────────────────

  test("D-B: a full file larger than the round budget is scanned ACROSS rounds (never abandoned)"):
    val dir = tmpDir()
    // 两件窗内 full，各 2 行（行 ~150 B）；预算 200 B ⇒ 一轮读不完任何一件的第二行之后
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("a1") + fullEntry("a2"))
    writeFile(dir, "2026-08-25_full.jsonl", fullEntry("b1") + fullEntry("b2"))
    touchObject(dir, "sys-a1")
    touchObject(dir, "orphan-x") // 窗内无引用 ⇒ 完成轮必被清
    val budget = 200L

    // round 1：进度保留、未完成、**不清扫**（孤儿仍在 ⇒ 绝不基于不完整引用集删对象）
    val (st1, done1) = LlmLogWriter.retentionRound(dir, "2026-08-21", fresh, budget)
    assert(!done1, "round 1 exhausts the budget ⇒ pass in progress (NOT abandoned)")
    assert(st1.cursor.isDefined, "cursor points at the line boundary where the round stopped")
    assert(st1.used.nonEmpty, "refs read so far are kept (progress is never discarded)")
    assert(objNames(dir).contains("orphan-x.json"), "no orphan sweep while the ref set is incomplete")
    assert(!st1.done.contains("2026-08-25_full.jsonl"), "second file not yet reached")

    // round 2：从游标续读
    val (st2, done2) = LlmLogWriter.retentionRound(dir, "2026-08-21", st1, budget)
    assert(st2.used.size >= st1.used.size, "accumulated refs only grow across rounds")
    assert(!done2, "still budget-bound (two files need 3 rounds at this budget)")

    // round 3：收口 ⇒ 整窗完整 ⇒ 清扫
    val (st3, done3) = LlmLogWriter.retentionRound(dir, "2026-08-21", st2, budget)
    assert(st3.used.contains("sys-a1") && st3.used.contains("m2-b2"), "all refs finally collected")
    assert(done3, "pass completes once every in-window full file is scanned")
    assertEquals(st3.cursor, None, "no leftover cursor after completion")
    assert(objNames(dir).contains("sys-a1.json"), "referenced object kept")
    assert(!objNames(dir).contains("orphan-x.json"), "unreferenced object swept on completion")

  test("D-B block: the 128 MiB semantic — a single file bigger than the budget no longer suppresses"):
    val dir = tmpDir()
    // 一件窗内 full 远大于预算（相当于现网 275 MB vs 128 MiB 的形态）
    val big = (1 to 40).map(i => fullEntry(s"big$i")).mkString
    writeFile(dir, "2026-08-24_full.jsonl", big)
    touchObject(dir, "orphan-y")
    var st = fresh
    var rounds = 0
    var complete = false
    while !complete && rounds < 200 do
      val (n, c) = LlmLogWriter.retentionRound(dir, "2026-08-21", st, 1024L)
      st = n
      complete = c
      rounds += 1
    assert(complete, s"multi-round scan finishes (rounds=$rounds)")
    assert(rounds > 1, s"a file above the per-round budget needs several rounds (rounds=$rounds)")
    assert(!objNames(dir).contains("orphan-y.json"), "sweep ran once the scan completed")

  // ── 负控：窗内被引用必不被删 ────────────────────────────────────────

  test("negative control: referenced objects survive the sweep, unreferenced ones are removed"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("keep1") + fullEntry("keep2"))
    for h <- List(
        "sys-keep1",
        "tools-keep1",
        "m1-keep1",
        "m2-keep1",
        "sys-keep2",
        "tools-keep2",
        "m1-keep2",
        "m2-keep2",
        "orphan1",
        "orphan2"
      )
    do touchObject(dir, h)
    val (st, complete) = LlmLogWriter.retentionRound(dir, "2026-08-21", fresh, 128L * 1024 * 1024)
    assert(complete)
    val left = objNames(dir).sorted
    assertEquals(
      left,
      List(
        "m1-keep1.json",
        "m1-keep2.json",
        "m2-keep1.json",
        "m2-keep2.json",
        "sys-keep1.json",
        "sys-keep2.json",
        "tools-keep1.json",
        "tools-keep2.json"
      ),
      "all 8 in-window referenced objects kept; both unreferenced objects swept"
    )

  test("oracle: objects newer than the pass start are NOT swept (incremental-scan safety)"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("keep"))
    touchObject(dir, "orphan-old")
    touchObject(dir, "orphan-new")
    // 把「新」对象的 mtime 推到未来的 pass 起点之后（模拟跨轮扫描期间新写入的对象）
    val future = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000)
    Files.setLastModifiedTime(dir.resolve("objects").resolve("orphan-new.json"), future)
    val (_, complete) = LlmLogWriter.retentionRound(dir, "2026-08-21", fresh, 128L * 1024 * 1024)
    assert(complete)
    val left = objNames(dir)
    assert(!left.contains("orphan-old.json"), "old unreferenced object swept")
    assert(left.contains("orphan-new.json"), "object newer than pass start kept (refs may not be read yet)")

  // ── poisoned：引用集不可读 ⇒ 不清扫 ─────────────────────────────────

  test("oversized line ⇒ poisoned ⇒ no sweep, and the window is not rescanned"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", "x" * 5000 + "\n")
    writeFile(dir, "2026-08-25_full.jsonl", fullEntry("v"))
    touchObject(dir, "orphan-z")
    val (st, finished) = LlmLogWriter.retentionRound(dir, "2026-08-21", fresh, 1L * 1024 * 1024, 64)
    assert(st.poisoned, "line above the memory bound ⇒ refs unreadable ⇒ poisoned")
    assert(finished, "poisoned window is not retried (no value in rescanning)")
    assert(objNames(dir).contains("orphan-z.json"), "orphan sweep stays OFF on a poisoned window")
    // 下一轮：直接短路，不做无谓重扫
    val (st2, finished2) = LlmLogWriter.retentionRound(dir, "2026-08-21", st, 1L * 1024 * 1024, 64)
    assert(finished2 && st2.poisoned)
    assert(exists(dir, "2026-08-25_full.jsonl"), "in-window files are untouched")

  test("cutoff rollover resets the accumulated ref set (window identity is enforced)"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-21_full.jsonl", fullEntry("w1"))
    writeFile(dir, "2026-08-23_full.jsonl", fullEntry("w2"))
    val (st1, _) = LlmLogWriter.retentionRound(dir, "2026-08-20", fresh, 128L * 1024 * 1024)
    assert(st1.used.contains("sys-w1") && st1.used.contains("sys-w2"))
    // 窗口滚动：2026-08-21 出窗（删除腿删掉）⇒ 引用集必须重置并只按新窗重建
    val (st2, complete2) = LlmLogWriter.retentionRound(dir, "2026-08-22", st1, 128L * 1024 * 1024)
    assert(!exists(dir, "2026-08-21_full.jsonl"), "out-of-window file removed by the delete leg")
    assert(complete2)
    assert(!st2.used.contains("sys-w1"), "refs of the出窗 file are not carried into the new window")
    assert(st2.used.contains("sys-w2"), "the new window's refs are collected")

  private def exists(dir: Path, name: String): Boolean = Files.exists(dir.resolve(name))
end LlmLogWriterPruneSpec
