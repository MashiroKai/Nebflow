package nebflow.cli

import munit.FunSuite

/** D-H5 案 ⒜（作者 2026-09-23 07:27 批）：`config edit` 的无编辑器 / 非交互兜底
  * 判据 + H9 文案闸。此处只直测**纯函数面**（真跑形态的红/绿/对照臂读数在实施
  * 报告里给；非交互判定依赖进程 stdio，不做环境依赖型断言）。
  */
class ConfigEditHeadlessSpec extends FunSuite:

  test("H9 逐字（§16 文案闸：禁改写）") {
    assertEquals(
      ConfigCommand.NoEditorMessage,
      "No editor available (no interactive terminal / no $EDITOR). Use 'nebflow config set <key> <value>' instead."
    )
  }

  test("兜底判据真值表：非交互 ∨ 编辑器不可得") {
    val editor = Some("emacs")
    assertEquals(ConfigCommand.needsEditorFallback(hasTerminal = true, editor), false)
    assertEquals(ConfigCommand.needsEditorFallback(hasTerminal = true, None), true)
    assertEquals(ConfigCommand.needsEditorFallback(hasTerminal = false, editor), true)
    assertEquals(ConfigCommand.needsEditorFallback(hasTerminal = false, None), true)
  }

  test("编辑器解析：既有优先级 `$EDITOR` → `$VISUAL` 一处不动") {
    assertEquals(ConfigCommand.pickEditor(Some("emacs"), Some("vim")), Some("emacs"))
    assertEquals(ConfigCommand.pickEditor(None, Some("vim")), Some("vim"))
    assertEquals(ConfigCommand.pickEditor(None, None), None)
  }

  test("空值 / 纯空白等同未设（治 `Cannot run program \"\"` 形态）") {
    assertEquals(ConfigCommand.pickEditor(Some(""), Some("vim")), Some("vim"))
    assertEquals(ConfigCommand.pickEditor(Some("   "), Some("vim")), Some("vim"))
    assertEquals(ConfigCommand.pickEditor(Some(""), Some("")), None)
    assertEquals(ConfigCommand.pickEditor(Some("  emacs  "), None), Some("emacs"))
  }
end ConfigEditHeadlessSpec
