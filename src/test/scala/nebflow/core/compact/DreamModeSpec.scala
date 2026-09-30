package nebflow.core.compact

import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * DreamMode 停用（落法 ii「连机制一起停」）后的新语义 spec。
 *
 * 原文件覆盖的是**合并核**（`t3Evolve` / `mergeFactsIntoSection` / `updateMemory` /
 * `promotedTexts` / sidecar 时钟）——那些 API 已随 DreamMode 机制整体删除 ⇒ 本文件
 * **按新语义改写**（不删文件、不放宽断言、不 skip）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 记忆族退役批（memory-family-retirement，2026-09-29）——本次收窄
 * ═══════════════════════════════════════════════════════════════════════
 *
 * DreamMode 机制停用批时，本 spec 钉的四条事实里有三条**以队列为被试物**
 * （生产者入队条目 `section` 恒 `None`；无具名节文件上照旧可落；`## Dream Extract`
 * 不再是可落节 ⇒ 判 `would-retry`）。队列族（含 plan / Note / State / TargetFile /
 * Bucket 等 API）已在本批整体退役 ⇒ **那三条的被试物不存在** ⇒ 逐条删除（不
 * 放宽、不 skip、不改成空断言——退役即退役）。这是**既有政策的机械后果**，非新
 * 裁定。
 *
 * **留面（本 spec 现存全部覆盖）**：DreamMode 仍活着的两件纯函数
 * `[[DreamMode.parseFact]]` / `[[DreamMode.entryHash]]` 的语义直测。二者原为
 * 生产者链路的解析/识别子；队列退役后**零生产调用方**，但仍属 DreamMode 的
 * 公开面，收口与否归 DreamMode 自身的批（不在本批六族范围）⇒ 断言保留。
 * dataRoot 不再需要重定向（本文件已不触任何记忆文件与队列）。
 */
class DreamModeSpec extends FunSuite:

  // ===== ① 仍在用的纯函数：fact 行解析 =====

  test("parseFact：`FACT n: [CATEGORY] text` 解析（类别大写化；空文本/非 FACT 行 ⇒ None）"):
    assertEquals(DreamMode.parseFact("FACT 1: [PATTERN] 先写 spec"), Some(("PATTERN", "先写 spec")))
    assertEquals(DreamMode.parseFact("  FACT 12 : [user_preference] 深色主题  "), Some(("USER_PREFERENCE", "深色主题")))
    assertEquals(DreamMode.parseFact("FACT 3: [DECISION]"), None, "空文本不解析")
    assertEquals(DreamMode.parseFact("不是 FACT 格式"), None)

  // ===== ② 仍在用的纯函数：条目识别子 =====

  test("entryHash：trim 归一化后取 sha256（同文本同哈希；异文本异哈希；跨调用稳定）"):
    assertEquals(DreamMode.entryHash("  abc  "), DreamMode.entryHash("abc"))
    assert(DreamMode.entryHash("abc") != DreamMode.entryHash("abd"))
    assertEquals(DreamMode.entryHash("abc").length, 64, "sha256 十六进制全长")

end DreamModeSpec
