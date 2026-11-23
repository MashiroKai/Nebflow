package nebflow.core.desktop

import munit.FunSuite

import nebflow.agent.AgentCore
import nebflow.core.plugin.PluginRegistry
import nebflow.core.tools.{BrowserVerbNames, DesktopVerbNames, ToolRegistry}

/**
 * desktop-observe-p0 契约（观测半边，写类不做；插件分发裁定 2026-11-22）。
 *
 * 钉死：
 *  1. 纯策略：宿主拒绝名单（子串、大小写不敏感）、工具内基本身份校验、应用名/区域解析、窗口与进程行解析、剪贴板截断。
 *  2. 注册面：六动词登记于插件分发表（pluginEngine）；名单单点与注册表一致；不含任何写类名字。
 *  3. 授权面（插件分发）：默认面不授能；挂载 computer-use ⇒ 六动词；挂载 browser-use 不串授；声明按插件分域。
 */
class DesktopObserveSpec extends FunSuite:

  private val six = Set(
    "desktop_list_apps",
    "desktop_list_windows",
    "desktop_screenshot",
    "desktop_clipboard_read",
    "desktop_open",
    "desktop_displays"
  )

  // ══════════ 1. 纯策略 ══════════

  test("宿主拒绝名单：engine/sbt/java 及 stand 命中，大小写不敏感，普通应用不误伤子串") {
    assert(DesktopPolicy.isHostName("Stand"))
    assert(DesktopPolicy.isHostName("nebflow-engine"))
    assert(DesktopPolicy.isHostName("SBT"))
    assert(DesktopPolicy.isHostName("java"))
    assert(DesktopPolicy.isHostName("openjdk 21"))
    assert(!DesktopPolicy.isHostName("Calculator"))
    assert(!DesktopPolicy.isHostName("Safari"))
  }

  test("应用名形状：字母数字空格点加号下划线连字符；拒路径与引号；1–80 位") {
    assertEquals(DesktopPolicy.validAppName("Calculator"), Some("Calculator"))
    assertEquals(DesktopPolicy.validAppName("  Visual Studio Code "), Some("Visual Studio Code"))
    assertEquals(DesktopPolicy.validAppName("../etc"), None)
    assertEquals(DesktopPolicy.validAppName("a\"b"), None)
    assertEquals(DesktopPolicy.validAppName(""), None)
    assertEquals(DesktopPolicy.validAppName("a" * 81), None)
  }

  test("工具内基本身份校验：Nebula 根或有会话身份放行（挂载即授权），无身份直调 fail-closed") {
    assert(DesktopPolicy.callerAllowed(isNebulaRoot = true, sessionId = None))
    assert(DesktopPolicy.callerAllowed(isNebulaRoot = false, sessionId = Some("delegate-abc")))
    assert(DesktopPolicy.callerAllowed(isNebulaRoot = false, sessionId = Some("node-7")))
    assert(!DesktopPolicy.callerAllowed(isNebulaRoot = false, sessionId = None))
    assert(!DesktopPolicy.callerAllowed(isNebulaRoot = false, sessionId = Some("  ")))
  }

  test("区域解析：x,y,w,h 整数，w/h 必须为正；负 x/y 合法（副屏左侧）") {
    assertEquals(DesktopPolicy.parseRegion("10,20,300,400"), Some((10, 20, 300, 400)))
    assertEquals(DesktopPolicy.parseRegion("-1920,0,800,600"), Some((-1920, 0, 800, 600)))
    assertEquals(DesktopPolicy.parseRegion("0,0,0,400"), None)
    assertEquals(DesktopPolicy.parseRegion("0,0,-5,400"), None)
    assertEquals(DesktopPolicy.parseRegion("1,2,3"), None)
    assertEquals(DesktopPolicy.parseRegion("a,b,c,d"), None)
  }

  test("窗口行解析：6 列、坏行跳过、标题内制表符被压平") {
    val text = "Safari\tStart Page\t0\t25\t1200\t800\nbad line\nCalc\tCal\tculator\t5\t6\t7\t8"
    val rows = DesktopPolicy.parseWindowLines(text)
    assertEquals(rows.size, 1)
    assertEquals(rows.head, DesktopPolicy.WindowRow("Safari", "Start Page", 0, 25, 1200, 800))
  }

  test("进程行解析：name\\tpid，坏 pid 跳过") {
    val rows = DesktopPolicy.parseAppLines("Finder\t101\nNoPid\tx\nSafari\t202\n")
    assertEquals(rows.map(_.name), List("Finder", "Safari"))
    assertEquals(rows.map(_.pid), List(101L, 202L))
  }

  test("剪贴板截断：上限内原样；超限截到 UTF-8 字节上限且标注截断") {
    val small = "hello"
    assertEquals(DesktopPolicy.capClipboard(small), (small, false))
    val big = "字" * (DesktopPolicy.ClipboardCapBytes / 2 + 100) // 每字 3 字节，超限
    val (out, truncated) = DesktopPolicy.capClipboard(big)
    assert(truncated)
    assert(out.getBytes("UTF-8").length <= DesktopPolicy.ClipboardCapBytes)
    assert(out.nonEmpty)
  }

  // ══════════ 2. 注册面 ══════════

  test("六动词进 TOOL_MAP，名单单点与注册名一致") {
    assertEquals(DesktopVerbNames.All.toSet, six)
    six.foreach(n => assert(ToolRegistry.registeredToolNames.contains(n), s"$n not registered"))
  }

  test("红线：观测面不含任何写类动词名") {
    val writeish = List("click", "type", "key", "drag", "scroll", "move", "resize", "close", "focus", "set_value", "perform")
    DesktopVerbNames.All.foreach { n =>
      writeish.foreach(w => assert(!n.contains(w), s"write-class token `$w` leaked into `$n`"))
    }
  }

  // ══════════ 3. 授权面（插件分发，作者 2026-11-22）══════════

  test("默认面不授能：六动词不在 RootExclusiveTools、不在内建清单（builtinToolNames）") {
    six.foreach(n => assert(!AgentCore.RootExclusiveTools.contains(n), s"$n leaked into RootExclusiveTools"))
    six.foreach(n => assert(!ToolRegistry.builtinToolNames.contains(n), s"$n leaked into builtin face"))
  }

  test("挂载态：挂载 computer-use（plugin_computer-use_engine）⇒ 六动词全授予") {
    assertEquals(PluginRegistry.engineGrantFor(List("plugin_computer-use_engine")), six)
  }

  test("未挂载态：无引擎前缀来源 ⇒ 空集") {
    assertEquals(PluginRegistry.engineGrantFor(Nil), Set.empty[String])
    assertEquals(PluginRegistry.engineGrantFor(List("plugin_x_playwright")), Set.empty[String])
  }

  test("不串授：仅挂载 browser-use 时桌面六动词一个不授；仅挂载 computer-use 时浏览器九动词一个不授") {
    val browserOnly = PluginRegistry.engineGrantFor(List("plugin_browser-use_engine"))
    assert(browserOnly.intersect(six).isEmpty, "browser-use mount must not grant desktop verbs")
    val desktopOnly = PluginRegistry.engineGrantFor(List("plugin_computer-use_engine"))
    assert(desktopOnly.intersect(BrowserVerbNames.All.toSet).isEmpty, "computer-use mount must not grant browser verbs")
  }

  test("声明校验按插件分域：computer-use 不得声明浏览器动词，反之亦然") {
    assertEquals(PluginRegistry.EngineToolsByPlugin("computer-use"), six)
    assert(PluginRegistry.EngineToolsByPlugin("computer-use").intersect(BrowserVerbNames.All.toSet).isEmpty)
  }
end DesktopObserveSpec
