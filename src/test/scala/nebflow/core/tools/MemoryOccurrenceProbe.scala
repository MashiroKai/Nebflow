package nebflow.core.tools

import nebflow.core.PathUtil

/**
 * 变更史对账**只读读数器**（零写盘）——记忆变更史对账判据的可复跑命令面。
 *
 * 用法（只起 JVM 跑一个 main，不起服务、不碰 8080）：
 * {{{
 *   sbt -batch "Test/runMain nebflow.core.tools.MemoryOccurrenceProbe --root=<dataRoot> [--threshold=<n>]"
 * }}}
 *
 * 输出两列读数，互为**独立复算面**（判据实现 vs 本文件自己按 occurrence 逐条数）：
 *   - `criterion` = `MemoryHistory` 的判据返回值（实现面）；
 *   - `oracle`    = 本文件不复用判据实现、直接从两本账原始行按 ref 逐 occurrence 配对
 *     数出的缺口。
 * 两者不一致 ⇒ 打印 `ORACLE-MISMATCH`（判据与真实缺口脱钩 = 哑绿/哑红）。
 *
 * 退出码 = 判据缺口条数 vs `--threshold`（默认 0）：`gaps > threshold ⇒ 1`，否则 `0`。
 * **阈值 + 退出码即接线面**（本批只产出判据与命令，不接线：回填未落地前接线会长挂 RED）。
 */
object MemoryOccurrenceProbe:

  private final case class Opts(root: os.Path, threshold: Int)

  private def expandHome(p: String): os.Path =
    if p == "~" then os.home
    else if p.startsWith("~/") then os.home / p.drop(2)
    else os.Path(p, os.pwd)

  private def parse(args: Array[String]): Opts =
    var root: Option[os.Path] = None
    var threshold = 0
    args.foreach { a =>
      if a.startsWith("--root=") then root = Some(expandHome(a.drop("--root=".length)))
      else if a.startsWith("--threshold=") then threshold = a.drop("--threshold=".length).toIntOption.getOrElse(0)
    }
    Opts(root.getOrElse(PathUtil.dataRoot), threshold)

  /** ref → occurrence 数（保 insertion order，输出确定性）。 */
  private def count(refs: Iterable[String]): Vector[(String, Int)] =
    val m = scala.collection.mutable.LinkedHashMap.empty[String, Int]
    refs.foreach(r => m.update(r, m.getOrElse(r, 0) + 1))
    m.toVector

  /** 逐 occurrence 配对的缺口：`left[ref] > right[ref]` 的超出条数 + 明细。 */
  private def deficits(left: Vector[(String, Int)], right: Vector[(String, Int)]): (Int, Vector[(String, Int, Int)]) =
    val r = right.toMap
    var total = 0
    val out = Vector.newBuilder[(String, Int, Int)]
    left.foreach { (ref, n) =>
      val m = r.getOrElse(ref, 0)
      if n > m then
        total += n - m
        out += ((ref, n, m))
    }
    (total, out.result())

  private def listing(detail: Vector[(String, Int, Int)], cap: Int = 12): String =
    val head = detail.take(cap).map((r, n, m) => s"$r($n>$m)").mkString(", ")
    if detail.size > cap then s"$head, ... +${detail.size - cap} more refs" else head

  def main(args: Array[String]): Unit =
    val o = parse(args)
    PathUtil.setDataRoot(o.root)

    val queueBack  = JsonlLedger.readLines(MemoryQueue.queuePath, MemoryQueue.archivePath)
    val histsBack  = JsonlLedger.readLines(MemoryHistory.historyPath, MemoryHistory.archivePath)
    val qState     = MemoryQueue.parseState(queueBack.lines)
    val hist       = MemoryHistory.readAll()

    val noteRefs  = qState.notes.map(_.id).toList
    val outcRefs  = qState.outcomes.map(_.ref).toList
    val hQueue    = hist.events.filter(_.kind == MemoryHistory.KindQueue).flatMap(_.ref).toList
    val hConsume  = hist.events.filter(_.kind == MemoryHistory.KindConsume).flatMap(_.ref).toList

    val noteOcc  = count(noteRefs)
    val outcOcc  = count(outcRefs)
    val hqOcc    = count(hQueue)
    val hcOcc    = count(hConsume)

    val (missQN, missQD) = deficits(noteOcc, hqOcc)
    val (missCN, missCD) = deficits(outcOcc, hcOcc)
    val (orphN, orphD)   = deficits(hcOcc, outcOcc)
    val orphanTotal      = missQN + missCN + orphN

    val gaps = MemoryHistory.reconciliation(noteRefs, outcRefs)

    // 成因面：运维对账台账行（`kind=reconcile`，由只读对账脚本直写，非 MemoryHistory 产出
    // ——故不设 KindReconcile 常量）。其 `refs` 集合与 occurrence 缺口集合恒等 ⇒ 缺口不是
    // 「丢行」而是「绕过 recordOutcome 直写 queue.jsonl 的通道只增 outcome 不增 consume」。
    val recRefs = hist.events.filter(_.kind == "reconcile").flatMap(_.refs).toSet
    val gapRefs = missCD.map(_._1).toSet

    def line(k: String, v: Any): Unit = println(f"$k%-28s = $v")

    println("=== memory occurrence reconciliation probe (read-only) ===")
    line("dataRoot", o.root)
    line("queue ledger", MemoryQueue.queuePath)
    line("history ledger", MemoryHistory.historyPath)
    line("queue raw lines", s"${queueBack.lines.size} (unreadable=${qState.unreadable})")
    line("history raw lines", s"${histsBack.lines.size} (unreadable=${hist.unreadable}, ioError=${hist.ioError.getOrElse("-")})")
    println("--- queue side (caller inputs) ---")
    line("notes", s"${noteRefs.size} occurrences / ${noteOcc.size} distinct refs")
    line("outcomes", s"${outcRefs.size} occurrences / ${outcOcc.size} distinct refs")
    println("--- history ledger ---")
    line("history:queue", s"${hQueue.size} occurrences / ${hqOcc.size} distinct refs")
    line("history:consume", s"${hConsume.size} occurrences / ${hcOcc.size} distinct refs")
    println("--- criterion under test (MemoryHistory.reconciliation) ---")
    line("criterion missingQueue", gaps.missingQueue.size)
    line("criterion missingConsume", gaps.missingConsume.size)
    line("criterion orphanConsume", gaps.orphanConsume.size)
    line("criterion gaps", gaps.total)
    gaps.messages.take(2).foreach(m => line("criterion message", m))
    println("--- oracle (independent occurrence recount, no criterion reuse) ---")
    line("missingQueue", s"$missQN   [${listing(missQD)}]")
    line("missingConsume", s"$missCN   [${listing(missCD)}]")
    line("orphanConsume", s"$orphN   [${listing(orphD)}]")
    line("oracle gaps", orphanTotal)
    println("--- cause (ops-level reconcile ledger, kind=reconcile) ---")
    line("reconcile refs", recRefs.size)
    if recRefs.nonEmpty then
      line("gap-refs == reconcile-refs", s"${gapRefs == recRefs} (${gapRefs.size}/${recRefs.size})")
    println("--- verdict ---")
    line("threshold", o.threshold)
    if gaps.total == orphanTotal then line("cross-check", s"MATCH (criterion=${gaps.total} == oracle=$orphanTotal)")
    else line("cross-check", s"ORACLE-MISMATCH (criterion=${gaps.total} vs oracle=$orphanTotal)")
    val exit = if gaps.total > o.threshold then 1 else 0
    println(s"EXIT $exit")
    // 真退出码（接线面）：不能只打印 —— 门禁/CI 读的是进程退出码
    if exit != 0 then sys.exit(exit)

end MemoryOccurrenceProbe
