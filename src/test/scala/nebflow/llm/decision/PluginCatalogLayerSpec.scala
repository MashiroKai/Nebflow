package nebflow.llm.decision

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.jev.CatalogEntry

/**
 * 双模式批（2026-10-28 · 设计 §7 插件三层规范）——**分配问对面 + 校验脚本**层：
 *
 *  - T3 catalogText（JevAllocator/LlmAllocator 共用的分配问对面渲染单点）：
 *    keywords/useWhen 增量行随块；缺席 = 块形逐字节不变。
 *  - T4 validate_plugin.py C17/C18 增量软检查：description>200 → WARN；
 *    Plugin.md 引用 SKILL.md 断链 → WARN（[WARN] 永不阻断；无引用/无 Plugin.md =
 *    PASS）。
 */
class PluginCatalogLayerSpec extends FunSuite:

  // ── T3 分配问对面 ───────────────────────────────────────────────────────────

  test("T3a catalogText 缺席增量键 = 旧块形逐字节") {
    val text = JevAllocator.catalogText(List(CatalogEntry("pkg-a", "cap statement")))
    assertEquals(text, "### pkg-a\ncap statement", "the legacy block shape is untouched")
  }

  test("T3b catalogText 在键 = keywords/use when 增量行随块") {
    val text = JevAllocator.catalogText(
      List(
        CatalogEntry("pkg-a", "cap statement", List("alpha", "beta"), Some("when the task needs it")),
        CatalogEntry("pkg-b", "other", Nil, None)
      )
    )
    assert(text.contains("### pkg-a\ncap statement\nkeywords: alpha, beta\nuse when: when the task needs it"), text)
    assert(text.endsWith("### pkg-b\nother"), s"absent-key blocks are byte-identical: $text")
  }

  // ── T4 validate_plugin.py 增量软检查（子进程真跑脚本）───────────────────────

  // ── T4 validate_plugin.py C17/C18 —— 本线已退役（2026-11-07 合并对账）──────────
  // USTC 线新带入的 T4a/T4b 钉的是 `nebflow-plugin-creator/.../validate_plugin.py` 的
  // C17/C18 软检查，但该脚本随「开发插件统一批·六包合并为 stand-dev」（`c9dff9829`，
  // 2026-10-31 作者令）在本线退役：现种子 `src/main/resources/seed/plugins/` 下已无任何
  // .py 脚本，`nebflow-plugin-creator` 目录也并入 `stand-dev`（只剩 SKILL.md + references）。
  // 全仓仅旧 delegate worktree 里还留有副本 ⇒ 本线属「钉已退役产物」，按仓内先例
  // （MemoryNoteToolSpec 退役同批复删）删除这两条；T3a/T3b（catalogText 渲染）保留。
end PluginCatalogLayerSpec
