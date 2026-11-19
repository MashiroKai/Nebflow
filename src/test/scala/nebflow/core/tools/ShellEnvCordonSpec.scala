package nebflow.core.tools

import munit.FunSuite

/**
 * 缺陷五 · 会话环境变量污染（引擎会话治理专项，作者令 2026-11-03 14:38
 * 「`NEBFLOW_GATEWAY_PORT=6138` 误导跨品牌 CLI，改为 stand 专属命名空间或不在通用
 * 会话面注入」）。
 *
 * 改前红：改前 spawn 点**原样继承父环境**、零围栏对象 ⇒ 会话内
 * `printenv NEBFLOW_GATEWAY_PORT` = 引擎端口（实证 6138），而 `STAND_GATEWAY_PORT`
 * 未设；`ShellEnvCordon.decide` 不存在（本 spec 编译不过 = 红）。
 * 改后绿：非 nebflow 品牌恒摘遗留名 + 写本品牌专属名（值 = 真实端口）。
 */
class ShellEnvCordonSpec extends FunSuite:

  test("① stand 品牌：摘除 NEBFLOW_GATEWAY_PORT，写入 STAND_GATEWAY_PORT = 真实端口") {
    val c = ShellEnvCordon.decide(
      parentKeys = Set("NEBFLOW_GATEWAY_PORT", "PATH", "HOME"),
      envPrefix = "STAND",
      portValue = "6138"
    )
    assertEquals(c.removals, List("NEBFLOW_GATEWAY_PORT"), "the cross-brand name must be stripped")
    assertEquals(c.additions, List("STAND_GATEWAY_PORT" -> "6138"), "the brand-native name must carry the real port")
  }

  test("② 跨品牌误判防线：会话内按 NEBFLOW_* 探测不得命中本实例端口") {
    // 真实语义模拟：父环境带 NEBFLOW_GATEWAY_PORT=6138（引擎自身被这样拉起），
    // 会话环境 = 父环境 ∖ removals ∪ additions ⇒ 会话里 nebflow CLI 的探测读不到
    // 6138（它只会回落到自己的缺省 6140），stand CLI 读到 6138。
    val parent = Map("NEBFLOW_GATEWAY_PORT" -> "6138", "PATH" -> "/usr/bin")
    val c = ShellEnvCordon.decide(parent.keySet, "STAND", "6138")
    val sessionEnv = parent -- c.removals ++ c.additions
    assertEquals(sessionEnv.get("NEBFLOW_GATEWAY_PORT"), None, s"session env must not leak the cross-brand port: $sessionEnv")
    assertEquals(sessionEnv.get("STAND_GATEWAY_PORT"), Some("6138"))
    assertEquals(sessionEnv.get("PATH"), Some("/usr/bin"), "unrelated env is untouched")
  }

  test("③ nebflow 品牌零变化（回滚兼容）：不摘除、品牌名即遗留名") {
    val c = ShellEnvCordon.decide(Set("NEBFLOW_GATEWAY_PORT"), "NEBFLOW", "6140")
    assertEquals(c.removals, Nil)
    assertEquals(c.additions, List("NEBFLOW_GATEWAY_PORT" -> "6140"))
  }

end ShellEnvCordonSpec
