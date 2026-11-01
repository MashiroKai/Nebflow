package nebflow.core.tools

import munit.FunSuite

/**
 * 9.19 纯编排口径重写批（2026-11-01 · 作者令）：**两模式提示词注入面 spec**。
 *
 * 双模式批的注入面单点 = [[DelegateTool.assembleBrief]]（sleep-poll 禁令批 C 段
 * 抽纯函数的判红锚）。本 spec 钉三件事：
 *
 *  - I1 **dispatcher 腿注入**：`dispatcherDiscipline=Some(DispatcherDisciplineLine)`
 *    时座位纪律段在位，且位于任务正文**之前**（单 turn 会话第一屏即见——747cd36a
 *    启动面防线同款布局判据）；
 *  - I2 **kernel/未判定零注入**：`None` 与缺省参数装配**逐字节相等**——kernel 腿
 *    brief 与总闸关时代的现状 brief 零漂移（双模式批「None ⇒ brief 逐字节不变」
 *    判红锚的装配面回写钉）；
 *  - I3 **零交叉泄漏**：kernel 形态 brief 不含任何 dispatcher 模式文本（两模式
 *    提示词语义分界的 brief 面——kernel=自己干的执行座位，不该读到编排纪律）。
 *
 * spawn 调用点的模式闸（`isDispatcher → Some(line)`）在 DelegateTool.scala 装配
 * 行单点（本 spec 钉装配函数本身；调用点闸由 F1-F6 工具面 spec 同判据族覆盖）。
 */
class DualModeBriefInjectionSpec extends FunSuite:

  private val line = DelegateTool.DispatcherDisciplineLine

  test("I1 dispatcher 腿 brief：纪律段在位，且位于任务正文之前（第一屏即见）") {
    val brief = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /tmp/ws",
      bindingLine = None,
      task = "Harden the bind flow.",
      injectedBlock = "",
      dispatcherDiscipline = Some(line)
    )
    assert(brief.contains(line), s"dispatcher brief must carry the seat discipline: $brief")
    val at = brief.indexOf("Dispatcher mode")
    val outAt = brief.indexOf("Output discipline")
    val taskAt = brief.indexOf("Harden the bind flow.")
    assert(at > outAt, "the section sits after the fixed discipline lines (output rule)")
    assert(at < taskAt, s"the section sits before the task body (first screen): $brief")
  }

  test("I2 kernel/未判定 brief：None 与缺省装配逐字节相等（现状零漂移判红锚）") {
    val viaDefault = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /tmp/ws",
      bindingLine = Some("[branch] delegate/delegate-x from main"),
      task = "Harden the bind flow.",
      injectedBlock = "<injected-plugins>x</injected-plugins>"
    )
    val viaNone = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /tmp/ws",
      bindingLine = Some("[branch] delegate/delegate-x from main"),
      task = "Harden the bind flow.",
      injectedBlock = "<injected-plugins>x</injected-plugins>",
      dispatcherDiscipline = None
    )
    assertEquals(viaNone, viaDefault, "None must be byte-identical to the default assembly (kernel = current behaviour, byte-stable)")
  }

  test("I3 零交叉泄漏：kernel 形态 brief 不含任何 dispatcher 模式文本") {
    val brief = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /tmp/ws",
      bindingLine = None,
      task = "Do the thing.",
      injectedBlock = ""
    )
    assert(!brief.contains("Dispatcher mode"), "no dispatcher seat text may leak into a kernel/未判定 brief")
    assert(!brief.contains("pure orchestration"), "the doctrine header must not appear in a kernel brief")
  }
end DualModeBriefInjectionSpec
