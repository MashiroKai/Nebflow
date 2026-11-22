package nebflow.core.desktop

import munit.FunSuite

import nebflow.agent.AgentCore
import nebflow.core.tools.{DesktopVerbNames, ToolRegistry}

/**
 * desktop-observe-p0 契约（观测半边，写类不做）。
 *
 * 钉死：
 *  1. 纯策略：宿主拒绝名单（子串、大小写不敏感）、身份判据、应用名/区域解析、窗口与进程行解析、剪贴板截断。
 *  2. 注册面：六动词进 TOOL_MAP；名单单点与注册表一致；不含任何写类名字。
 *  3. 授权面：六动词进 RootExclusiveTools（防声明逃逸）；挂载集 = DesktopObserveTools。
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

  test("身份判据单点：Nebula 根或 delegate- 会话放行，其余 fail-closed") {
    assert(DesktopPolicy.callerAllowed(isNebulaRoot = true, sessionId = None))
    assert(DesktopPolicy.callerAllowed(isNebulaRoot = false, sessionId = Some("delegate-abc")))
    assert(!DesktopPolicy.callerAllowed(isNebulaRoot = false, sessionId = Some("ask-gv-1")))
    assert(!DesktopPolicy.callerAllowed(isNebulaRoot = false, sessionId = Some("node-7")))
    assert(!DesktopPolicy.callerAllowed(isNebulaRoot = false, sessionId = None))
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

  // ══════════ 3. 授权面 ══════════

  test("防声明逃逸：六动词在 RootExclusiveTools，插件/agent 声明不授能") {
    six.foreach(n => assert(AgentCore.RootExclusiveTools.contains(n), s"$n missing from RootExclusiveTools"))
  }

  test("挂载集 = DesktopObserveTools（单点）") {
    assertEquals(AgentCore.DesktopObserveTools, six)
  }
end DesktopObserveSpec
