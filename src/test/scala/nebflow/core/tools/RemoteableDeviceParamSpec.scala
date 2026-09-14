package nebflow.core.tools

import io.circe.JsonObject
import munit.FunSuite
import nebflow.agent.AgentCore

/**
 * `device` 参数的**两列读数**契约（2026-09-14 工具面 `device` 摘除批 · S2 新增正控）。
 *
 * 动因：本批从 `Delegate` 的 schema 结构性摘除 `device`（作者裁定 U1/U2）。摘除是
 * 「减」操作，最容易出的错是**摘多了**（连带把真远端六件的注入面也削掉）——本 spec
 * 就是防这一档的**阳性对照**，逐件钉死：
 *
 *  1. **正控（keep 面）**：`RemoteExecutor.remoteableTools` 逐字 = `AgentCore.BaseTools`
 *     六件，且**每件**经 `augmentSchema` / 真漏斗 `ToolRegistry.ALL_TOOLS` 后
 *     `properties.device` 仍在（逐件断言，非抽样）。
 *  2. **负控（remove 面）**：`Delegate` 不在集合内、其 schema 不含 `device`，且
 *     `augmentSchema` 对它**逐字不改**（non-remoteable ⇒ 原样返回，见
 *     `RemoteExecutor.scala` 的 `if !remoteableTools.contains(toolName) then schema`）。
 *  3. **边界（禁注入多了）**：内核七件的第 7 件 `AskUserQuestion` 同样不得被注入。
 *
 * 与 `ListFriendsToolRegistrationSpec`（L3 条）同款负控模式；本文件是其「正控补位」。
 * 覆盖面（board #475 纪律）：本 spec 只做**静态 schema 面**断言，**不覆盖**引擎派发
 * 行为面（S3 三条语义）——那一条由隔离实例 e2e / 调用面用例承担。
 */
class RemoteableDeviceParamSpec extends FunSuite:

  /** 真漏斗读数：`registry.scala` 的 `ALL_TOOLS` 逐件过 `augmentSchema`，
    * 即 LLM 实际看到的那份 schema。 */
  private def funnelSchema(toolName: String): JsonObject =
    ToolRegistry.ALL_TOOLS
      .find(_.name == toolName)
      .map(_.inputSchema)
      .getOrElse(fail(s"tool '$toolName' missing from ToolRegistry.ALL_TOOLS"))

  private def props(schema: JsonObject): Set[String] =
    schema("properties").flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def deviceProp(schema: JsonObject): Option[io.circe.Json] =
    schema("properties").flatMap(_.asObject).flatMap(_("device"))

  // ══════════ 1. 正控：keep 面 = 真远端六件 ══════════

  test("正控：remoteableTools 逐字 = BaseTools 六件（集合单点来源未变）"):
    assertEquals(
      RemoteExecutor.remoteableTools,
      AgentCore.BaseTools,
      "device 注入面必须仍恒等于内核/通用面的文件六件；两侧任一漂移都是本批的回归"
    )
    assertEquals(
      RemoteExecutor.remoteableTools,
      Set("Read", "Write", "Edit", "Glob", "Grep", "Bash"),
      "六件名单逐字钉死（摘除批不得顺手动它）"
    )

  test("正控：六件**逐件**带 device —— augmentSchema 直调面"):
    AgentCore.BaseTools.toList.sorted.foreach { name =>
      val tool = ToolRegistry.TOOL_MAP.getOrElse(name, fail(s"tool '$name' is not registered"))
      val augmented = RemoteExecutor.augmentSchema(name, tool.inputSchema)
      assert(
        deviceProp(augmented).exists(_.isObject),
        s"$name must still carry the injected `device` property (augmentSchema), got: ${props(augmented)}"
      )
    }

  test("正控：六件**逐件**带 device —— 真漏斗面（ToolRegistry.ALL_TOOLS）"):
    AgentCore.BaseTools.toList.sorted.foreach { name =>
      val schema = funnelSchema(name)
      assert(
        deviceProp(schema).exists(_.isObject),
        s"$name must still carry `device` in the LLM-facing schema, got: ${props(schema)}"
      )
      // 注入的属性形态本就固定（type=string）——顺带钉死，防「在但形态坏了」。
      val typeOpt = deviceProp(schema).flatMap(_.asObject).flatMap(_("type")).flatMap(_.asString)
      assertEquals(typeOpt, Some("string"), s"$name's injected `device` must stay type=string")
    }

  // ══════════ 2. 负控：remove 面 = Delegate ══════════

  test("负控：Delegate 不在 remoteableTools 内，augmentSchema 对它逐字不改"):
    assert(!RemoteExecutor.remoteableTools.contains("Delegate"), "Delegate 是本地编排件，永不进远端注入面")
    val augmented = RemoteExecutor.augmentSchema("Delegate", DelegateTool.inputSchema)
    assertEquals(
      augmented,
      DelegateTool.inputSchema,
      "non-remoteable ⇒ augmentSchema 必须原样返回（不得注入 device）"
    )
    assert(!props(augmented).contains("device"), s"Delegate schema must carry no device, got: ${props(augmented)}")

  test("负控：漏斗面 Delegate 的 device 零命中 —— 摘除面读数"):
    val schema = funnelSchema("Delegate")
    assertEquals(
      props(schema),
      Set("task", "description"),
      "摘除后 Delegate 的 LLM 面 schema 恰两参数（required 同）"
    )
    assert(deviceProp(schema).isEmpty, "Delegate must have NO device property in the LLM-facing schema")

  // ══════════ 3. 边界：禁「注入多了」 ══════════

  test("边界：内核七件的第 7 件 AskUserQuestion 不得被注入 device"):
    assert(AgentCore.KernelFixedTools.contains("AskUserQuestion"), "内核面仍含 AskUserQuestion（本批不动）")
    assert(!RemoteExecutor.remoteableTools.contains("AskUserQuestion"), "AskUserQuestion 不是远端工具")
    val augmented = RemoteExecutor.augmentSchema("AskUserQuestion", funnelSchema("AskUserQuestion"))
    assert(
      !props(augmented).contains("device"),
      s"AskUserQuestion must not receive device, got: ${props(augmented)}"
    )

  test("边界：内核面 = 六件（带 device）+ AskUserQuestion（不带），件数 7 不变"):
    assertEquals(AgentCore.KernelFixedTools.size, 7)
    val withDevice = AgentCore.KernelFixedTools.filter(n => deviceProp(funnelSchema(n)).isDefined)
    assertEquals(
      withDevice,
      AgentCore.BaseTools,
      s"内核面带 device 者必须**恰为**六件（多一件少一件都是回归），got: ${withDevice.toList.sorted}"
    )

end RemoteableDeviceParamSpec
