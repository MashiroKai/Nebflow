package nebflow.core.tools

import io.circe.JsonObject
import munit.FunSuite

/**
 * 设备通道 `direction=list` 版本偏斜诊断 + stray 判据 schema 豁免面（台账 #901，
 * 2026-10-26 修正批）。
 *
 * 动因（2026-10-26 晨生产实录）：Mac 引擎的 device-project-mount 批对设备 KAI 发
 * `direction=list` 做远端目录核验，KAI 在产 daemon（2026.9.19 stable，早于
 * 2026-10-10 list 方向落位）的 legacy 接收分支原样回答 `"Unknown direction: list"`
 * ——旧文案把用户引去「先建设备目录」的错误补救路径，且 `Project(device=…)` 的
 * 合法入参被 stray 判据误报「不在 remoteable 工具面」。
 *
 * 本 spec 钉两个单点（**静态判据面**；创建/挂载腿的接线属编译期保证 + 真机验收，
 * 与 [[nebflow.core.project.DeviceProjectMountSpec]] 同一边界口径）：
 *
 *   1. [[RemoteExecutor.listDirectionSkewNote]]——签名小写包含匹配，对发送侧各包裹
 *      形态（裸串 / `Remote error: ` 前缀 / `p2p: …; relay: …` 合成）同判；**非 list
 *      的 legacy 方向名（put/get）命中不得误诊**；不命中 ⇒ `None`（调用方错误通道
 *      逐字节不变的正控）。
 *   2. [[RemoteExecutor.schemaDeclaresDeviceParam]]——schema 自陈 `device` 键 = 工具
 *      自消费（Project 的 create 参数面）⇒ 不属 stray；remoteable 六件的注入键
 *      （augmentSchema 产物）同判 true；无 device 键的 schema（Delegate / 空对象）
 *      = false。
 */
class DeviceChannelListSkewSpec extends FunSuite:

  // ══════════ 1. listDirectionSkewNote：签名判据 ══════════

  test("签名命中：裸 legacy 报错串（对端 FileTransferAction 原样回执）"):
    val note = RemoteExecutor.listDirectionSkewNote("Unknown direction: list", "KAI")
    assert(note.isDefined, "bare legacy signature must hit")
    val text = note.get
    assert(text.contains("KAI"), s"note must name the device: $text")
    assert(text.toLowerCase.contains("version skew"), s"note must diagnose version skew: $text")
    assert(
      text.toLowerCase.contains("upgrade") || text.toLowerCase.contains("deploy"),
      s"note must point at the upgrade remedy: $text"
    )

  test("签名命中：发送侧包裹形态（Remote error: 前缀 / p2p+relay 合成 / 大写）"):
    assert(RemoteExecutor.listDirectionSkewNote("Remote error: Unknown direction: list", "KAI").isDefined)
    assert(
      RemoteExecutor
        .listDirectionSkewNote(
          "p2p: Remote error: Unknown direction: list; relay: relay tunnel refused",
          "KAI"
        )
        .isDefined,
      "combined p2p/relay failure text must still hit the signature"
    )
    assert(RemoteExecutor.listDirectionSkewNote("UNKNOWN DIRECTION: LIST", "KAI").isDefined)

  test("签名不中：其它 legacy 方向名（put/get）不得误诊为版本偏斜"):
    // 🔴 关键负控：对端对 put/get 的 legacy 分支同样会回 "Unknown direction: <dir>"——
    // 那些是真协议错误，不是 list 版本偏斜，调用方错误通道必须逐字节保真。
    assertEquals(RemoteExecutor.listDirectionSkewNote("Unknown direction: put", "KAI"), None)
    assertEquals(RemoteExecutor.listDirectionSkewNote("Unknown direction: get", "KAI"), None)

  test("签名不中：普通传输/语义错误通道逐字节保真（None ⇒ 调用方文案不变）"):
    assertEquals(RemoteExecutor.listDirectionSkewNote("File not found: C:/x", "KAI"), None)
    assertEquals(RemoteExecutor.listDirectionSkewNote("Directory not found: C:/x", "KAI"), None)
    assertEquals(RemoteExecutor.listDirectionSkewNote("Not a directory: C:/x", "KAI"), None)
    assertEquals(
      RemoteExecutor.listDirectionSkewNote("cannot reach KAI at http://x: connection refused", "KAI"),
      None
    )
    assertEquals(RemoteExecutor.listDirectionSkewNote("", "KAI"), None)

  // ══════════ 2. schemaDeclaresDeviceParam：stray 豁免判据 ══════════

  /** 真漏斗读数（与 [[RemoteableDeviceParamSpec]] 同源）：LLM 实际看到的那份 schema。 */
  private def funnelSchema(toolName: String): JsonObject =
    ToolRegistry.ALL_TOOLS
      .find(_.name == toolName)
      .map(t => JsonObject.fromMap(t.inputSchema.toMap))
      .getOrElse(fail(s"tool '$toolName' missing from ToolRegistry.ALL_TOOLS"))

  test("正控：Project 的 create 参数面自陈 device ⇒ true（#901 误报修正的靶面）"):
    val schema = funnelSchema("Project")
    assert(
      RemoteExecutor.schemaDeclaresDeviceParam(schema),
      "Project declares `device` as its own business input — the stray warning must not fire on it"
    )

  test("正控：augmentSchema 注入产物（remoteable 六件的漏斗面）⇒ true"):
    val augmented = RemoteExecutor.augmentSchema(
      "Bash",
      JsonObject(
        "properties" -> io.circe.Json.obj("command" -> io.circe.Json.obj("type" -> io.circe.Json.fromString("string")))
      )
    )
    assert(RemoteExecutor.schemaDeclaresDeviceParam(augmented))

  test("负控：无 device 键的 schema ⇒ false（Delegate / 空对象 / 无 properties）"):
    assert(!RemoteExecutor.schemaDeclaresDeviceParam(funnelSchema("Delegate")))
    assertEquals(RemoteExecutor.schemaDeclaresDeviceParam(JsonObject.empty), false)
    assertEquals(
      RemoteExecutor.schemaDeclaresDeviceParam(JsonObject("type" -> io.circe.Json.fromString("object"))),
      false
    )

  test("边界：device 在 properties 之外（顶层散键）不算自陈"):
    val stray = JsonObject(
      "type" -> io.circe.Json.fromString("object"),
      "device" -> io.circe.Json.fromString("KAI")
    )
    assertEquals(RemoteExecutor.schemaDeclaresDeviceParam(stray), false)
end DeviceChannelListSkewSpec
