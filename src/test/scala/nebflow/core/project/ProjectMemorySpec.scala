package nebflow.core.project

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.service.MemoryBudget

import java.nio.file.Files

/**
 * ProjectMemory spec（project-memory 批 2026-09-05 §3）：注入块渲染三态 + 路径契约。
 *
 * - 文件缺失/空 → ""（调用方不注空段——ProjectActor/NodeEngine 共同契约）
 * - 预算内 → 头部（项目名+路径+维护入口）+ 全文
 * - 软警区（>80%）→ 全文 + WARN 脚注（MEMORYEDIT_BUDGET_WARN）
 * - 超硬顶 → 只注头部+字节/条目统计+top 节+整理指引（不注正文）
 * - path() = <workspace>/.nebflow/memory.md（workspace 相对形式按 dataRoot 解析）
 *
 * 纯读渲染函数（injectionBlock 零写入）；临时目录隔离。
 */
class ProjectMemorySpec extends FunSuite:

  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    home = os.Path(Files.createTempDirectory("nb-pmem-spec"))

  override def afterAll(): Unit =
    os.remove.all(home)

  private def ws: os.Path = home / "ws-a"
  private def memFile: os.Path = ws / ".nebflow" / "memory.md"

  private def seed(content: String): Unit =
    os.makeDir.all(ws / ".nebflow")
    os.write.over(memFile, content)

  test("missing file → empty block (caller injects nothing)"):
    val block = ProjectMemory.injectionBlock(ws.toString, "proj-a").unsafeRunSync()
    assertEquals(block, "")

  test("empty file → empty block"):
    seed("")
    assertEquals(ProjectMemory.injectionBlock(ws.toString, "proj-a").unsafeRunSync(), "")

  test("within budget → header + full text"):
    seed("## 项目状态\n\n- 口径：预算 10KB（2026-09-05）")
    val block = ProjectMemory.injectionBlock(ws.toString, "proj-a").unsafeRunSync()
    assert(block.contains("# Project Memory — proj-a"), "header carries project name")
    assert(block.contains(memFile.toString), "header carries the file path")
    assert(block.contains("MemoryEdit target=project:proj-a"), "header carries the maintenance entry point")
    assert(block.contains("- 口径：预算 10KB（2026-09-05）"), "full text inlined")
    assert(!block.contains("WARN"), "预算内无 WARN")

  test("over soft line → full text + WARN footer (MEMORYEDIT_BUDGET_WARN)"):
    seed("x" * 8300) // > 8,192B 软线，< 10,240B 硬顶
    val block = ProjectMemory.injectionBlock(ws.toString, "proj-a").unsafeRunSync()
    assert(block.contains("# Project Memory — proj-a"))
    assert(block.contains("WARN"), "软警区注入 WARN")
    assert(block.contains("MEMORYEDIT_BUDGET_WARN"))
    assert(block.contains("consolidation"), "WARN 指向整理行动")

  test("over hard cap → header + stats only, full text NOT inlined"):
    val body = "## Bulk\n\n" + ("x" * 10300) + "\n\n- 条目丁\n- 条目戊"
    seed(body) // > 10,240B 硬顶，含 2 条目
    val block = ProjectMemory.injectionBlock(ws.toString, "proj-a").unsafeRunSync()
    assert(block.contains("# Project Memory — proj-a"), "头部仍在（统计形态）")
    assert(block.contains(s"${MemoryBudget.ProjectHardBytes}-byte hard budget"), "声明硬顶")
    assert(block.contains("bytes,"), "字节统计在场")
    assert(block.contains("2 entries"), "条目统计在场")
    assert(block.contains("## Bulk") || block.contains("Bulk"), "top 节定位在场")
    assert(block.contains("MemoryEdit target=project:proj-a"), "整理指引带维护入口")
    assert(!block.contains("- 条目丁"), "正文不内联（超限文件全文是税）")
    assert(!block.contains("xxxxx"), "超限正文不内联")

  test("path(): <workspace>/.nebflow/memory.md; relative workspace resolves against dataRoot"):
    assertEquals(ProjectMemory.path(ws.toString), ws / ".nebflow" / "memory.md")
    assertEquals(ProjectMemory.FileName, "memory.md", "命名定稿：与全局 agent 级同名、靠 .nebflow/ 目录位分层")

  test("verdict project dimension shares the write-side gate constants (10KB/8KB)"):
    assertEquals(MemoryBudget.ProjectHardBytes, 10L * 1024)
    assertEquals(MemoryBudget.ProjectSoftBytes, 8L * 1024)
    assert(MemoryBudget.verdict("project", 9000).isInstanceOf[MemoryBudget.Warn], "8KB<9000B<10KB → Warn")
    assert(MemoryBudget.verdict("project", 11000).isInstanceOf[MemoryBudget.Exceeded], ">10KB → Exceeded")
    assertEquals(MemoryBudget.verdict("project", 1000), MemoryBudget.Within)
end ProjectMemorySpec
