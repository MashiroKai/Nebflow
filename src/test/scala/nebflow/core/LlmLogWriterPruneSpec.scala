package nebflow.core

import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * #26: LlmLogWriter.pruneOldLogs must not read huge log files unbounded.
 *
 * Pre-fix: `Files.readAllLines(file)` loaded every remaining .jsonl fully
 * into memory — real sse.jsonl files reach 400+ MB (complete-dump mode),
 * OOMing the JVM under the default 1g heap. Post-fix:
 *   - only `_full.jsonl` is scanned (summary/sse entries carry no refs)
 *   - scanning is line-by-line (bounded memory)
 *   - files above a size cap are skipped → scanIncomplete=true → the
 *     orphan sweep is suppressed (skipped refs are unknown; deleting
 *     "orphans" could remove live objects)
 */
class LlmLogWriterPruneSpec extends FunSuite:

  private def tmpDir(): Path = Files.createTempDirectory("llm-prune-")

  private def writeFile(dir: Path, name: String, content: String): Path =
    val p = dir.resolve(name)
    Files.writeString(p, content)
    p

  private def fullEntry(hash: String): String =
    s"""{"timestamp":"2026-08-24T00:00:00Z","type":"request","system_ref":"sys-$hash","tools_ref":"tools-$hash","message_refs":["m1-$hash","m2-$hash"]}\n"""

  test("collects refs from full.jsonl files, ignores sse/summary"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("abc") + fullEntry("def"))
    // sse/summary files contain the same-looking refs — they must NOT be scanned
    writeFile(dir, "2026-08-24_sse.jsonl", s"""{"system_ref":"sys-sse"}\n""")
    writeFile(dir, "2026-08-24_summary.jsonl", s"""{"message_refs":["m-sum"]}\n""")
    val used = mutable.Set.empty[String]
    val incomplete = LlmLogWriter.scanFullLogsForRefs(dir, "2026-08-21", used, 128L * 1024 * 1024)
    assert(!incomplete, "small files all scanned")
    assert(used.contains("sys-abc") && used.contains("tools-def") && used.contains("m1-abc"))
    assert(!used.contains("sys-sse"), "sse file must not be scanned")
    assert(!used.contains("m-sum"), "summary file must not be scanned")

  test("deletes jsonl files older than cutoff (date-prefix compare)"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-20_full.jsonl", fullEntry("old"))
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("new"))
    val used = mutable.Set.empty[String]
    LlmLogWriter.scanFullLogsForRefs(dir, "2026-08-21", used, 128L * 1024 * 1024)
    assert(!Files.exists(dir.resolve("2026-08-20_full.jsonl")), "old file deleted")
    assert(Files.exists(dir.resolve("2026-08-24_full.jsonl")), "recent file kept")
    assert(used.contains("sys-new"), "recent file refs collected")
    assert(!used.contains("sys-old"), "old file refs not collected (file deleted)")

  test("oversized full file is skipped → scanIncomplete=true, refs not collected"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("big"))
    writeFile(dir, "2026-08-24_sse.jsonl", "x" * 20000) // huge sse — irrelevant
    val used = mutable.Set.empty[String]
    // tiny cap (10 bytes) forces the full file over the limit
    val incomplete = LlmLogWriter.scanFullLogsForRefs(dir, "2026-08-21", used, 10L)
    assert(incomplete, "oversized full file → scan incomplete")
    assert(!used.contains("sys-big"), "skipped file refs not collected")

  test("all small files scanned → scanIncomplete=false"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("a"))
    writeFile(dir, "2026-08-24_sse.jsonl", "y" * 5000) // large but not scanned
    val used = mutable.Set.empty[String]
    // cap above the full file's size (sse size is irrelevant — never scanned)
    val incomplete = LlmLogWriter.scanFullLogsForRefs(dir, "2026-08-21", used, 1024L)
    assert(!incomplete, "full file under cap even when sse is large → complete scan")
    assert(used.contains("sys-a"))

  test("garbage lines are ignored without failing the scan"):
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", "not-json\n" + fullEntry("ok") + "\n")
    val used = mutable.Set.empty[String]
    val incomplete = LlmLogWriter.scanFullLogsForRefs(dir, "2026-08-21", used, 128L * 1024 * 1024)
    assert(!incomplete)
    assert(used.contains("sys-ok"), "valid line after garbage still collected")

  test("scan is bounded to full files — a 1GB sse file is never read"):
    // Pre-#26 this read the sse file fully (OOM); now the sse file is
    // filtered by filename before any read happens. We assert the scan of a
    // directory with a huge sse file touches only the full file's refs.
    val dir = tmpDir()
    writeFile(dir, "2026-08-24_full.jsonl", fullEntry("only"))
    writeFile(dir, "2026-08-24_sse.jsonl", "z" * 1_000_000)
    val used = mutable.Set.empty[String]
    val incomplete = LlmLogWriter.scanFullLogsForRefs(dir, "2026-08-21", used, 128L * 1024 * 1024)
    assert(!incomplete)
    assert(used == Set("sys-only", "tools-only", "m1-only", "m2-only"))
end LlmLogWriterPruneSpec
