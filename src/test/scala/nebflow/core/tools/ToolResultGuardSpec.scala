package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.core.{PathUtil, ToolExecResult}
import nebflow.shared.{Defaults, ToolCall}

import java.nio.file.Files

/** #38 Layer A — Read 豁免堵死 + guard 对 ∞ 防御回退（2026-09-01）。
  *
  * 回归护栏：任何工具（含 Read）的超大单结果必须走落盘+预览路径；
  * 回读落盘文件被 Read 守卫拦截（Read 上限 = 50K，非 ∞）。
  */
class ToolResultGuardSpec extends CatsEffectSuite:

  private val testRoot = Files.createTempDirectory("tool-result-guard-test")
  private val resultDir = testRoot.resolve("tool-results")

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(os.Path(testRoot))

  override def afterAll(): Unit =
    def deleteRecursively(path: java.nio.file.Path): Unit =
      if Files.exists(path) then
        val stream = Files.walk(path)
        try
          stream.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally stream.close()
    deleteRecursively(testRoot)

  private def toolCall(name: String, id: String = "call-test"): ToolCall =
    ToolCall(id = id, name = name, input = JsonObject.empty)

  private def result(content: String): ToolExecResult =
    ToolExecResult(content = content)

  // ---- Layer A 核心：Read 不再豁免 ----

  test("Read declares the default 50K cap, not Int.MaxValue"):
    val readMax = ToolRegistry.TOOL_MAP.get("Read").map(_.maxResultSizeChars)
    assertEquals(readMax, Some(Defaults.DefaultMaxResultSizeChars))
    assert(readMax.forall(_ != Int.MaxValue))

  test("Read >50K result is persisted with a preview, full text on disk"):
    val big = "x" * (Defaults.DefaultMaxResultSizeChars + 10_000)
    val guarded = ToolResultGuard.guardResult(toolCall("Read"), result(big), "sess-a").unsafeRunSync()

    assert(guarded.content.startsWith("<persisted-output>"), "must start with persisted tag")
    assert(guarded.content.contains("Full output saved to"), "must point at the on-disk file")
    assert(guarded.content.length <= Defaults.ToolResultPreviewSize + 400, "LLM-visible content stays small")
    // 全文保留在 frontendContent（WS 展示），与既有 M3 语义一致
    assertEquals(guarded.frontendContent, Some(big))

    val persisted = resultDir.resolve("sess-a").resolve("call-test.txt")
    assert(Files.exists(persisted), "persisted file must exist")
    assertEquals(new String(Files.readAllBytes(persisted), "UTF-8"), big)

  test("persisted preview tells the agent to paginate, not re-read wholesale"):
    val big = "y" * (Defaults.DefaultMaxResultSizeChars + 5_000)
    val guarded = ToolResultGuard.guardResult(toolCall("Read"), result(big), "sess-b").unsafeRunSync()
    assert(guarded.content.contains("offset/limit"), "preview must advise chunked re-read")

  test("Read ≤50K result passes through unchanged"):
    val small = "s" * 1_000
    val guarded = ToolResultGuard.guardResult(toolCall("Read"), result(small), "sess-c").unsafeRunSync()
    assertEquals(guarded.content, small)

  test("unknown tool falls back to the default cap (no tool is exempt)"):
    val big = "z" * (Defaults.DefaultMaxResultSizeChars + 5_000)
    val guarded = ToolResultGuard.guardResult(toolCall("NoSuchTool"), result(big), "sess-d").unsafeRunSync()
    assert(guarded.content.startsWith("<persisted-output>"))
    assert(guarded.content.length <= Defaults.ToolResultPreviewSize + 400)

  test("error results skip the guard (existing semantics preserved)"):
    val big = "e" * (Defaults.DefaultMaxResultSizeChars + 5_000)
    val guarded =
      ToolResultGuard.guardResult(toolCall("Read"), ToolExecResult(content = big, isError = true), "sess-e").unsafeRunSync()
    assertEquals(guarded.content, big)

  // ---- Batch 预算（M2 既有语义回归护栏）----

  test("batch over 200K budget persists the largest results"):
    val a = result("a" * 90_000)
    val b = result("b" * 90_000)
    val c = result("c" * 90_000)
    val guarded =
      ToolResultGuard
        .guardBatch(
          List(
            (toolCall("Bash", "call-1"), a),
            (toolCall("Bash", "call-2"), b),
            (toolCall("Bash", "call-3"), c)
          ),
          "sess-f"
        )
        .unsafeRunSync()

    // 合计 270K > 200K：至少一个落盘，且不再有 ≥2 个全文结果（每个 >90K 都超预算）
    val persistedCount = guarded.count(_._2.content.startsWith("<persisted-output>"))
    assert(persistedCount >= 1, s"expected ≥1 persisted, got $persistedCount")
    val fullSizes = guarded.map(_._2.content.length)
    assert(fullSizes.sum <= Defaults.MaxToolResultsPerMessageChars + 10_000, "aggregate stays under budget")

end ToolResultGuardSpec
