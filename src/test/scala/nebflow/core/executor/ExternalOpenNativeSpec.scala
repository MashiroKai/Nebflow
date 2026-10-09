package nebflow.core.executor

import io.circe.syntax.*
import munit.FunSuite

/**
 * external-executors 批（#1225）验收 · 「打开原软件」命令模板（纯函数面）。
 *
 * 中心性质（原书 AgentControl 面：openNativeCommand 模板，macOS `open -a
 * Terminal <dir>`＋resume、Win/Linux 对应）：
 *   - 三平台模板形态正确（外层 argv 直传，**不走 shell 拼接**）；
 *   - cwd 经 shQuote 单引号转义进内层（含空格/单引号不破形）；
 *   - cliSessionId 白名单校验——非白名单字符不进命令（防注入）；
 *   - resume 命令按适配器族给出（claude --resume / codex resume / zcode
 *     --resume；无锚点 = 裸启动）。
 */
class ExternalOpenNativeSpec extends FunSuite:

  private val cwd = "/tmp/some workspace"

  test("macOS: osascript drives Terminal.app with a cd && resume inner script") {
    val (bin, args) = ExternalOpenNative.templateFor("Mac OS X", cwd, "claude-code", Some("sess-9"))
    assertEquals(bin, "osascript")
    assertEquals(args.head, "-e")
    val script = args(1)
    assert(script.contains("tell application"), script)
    assert(script.contains(s"cd '/tmp/some workspace'"), script)
    assert(script.contains("claude --resume sess-9"), script)
  }

  test("macOS with no cli session: bare CLI start inside the workspace") {
    val (_, args) = ExternalOpenNative.templateFor("mac", cwd, "zcode", None)
    assert(args(1).contains("cd '/tmp/some workspace' && zcode"), args(1))
    assert(!args(1).contains("--resume"), args(1))
  }

  test("Windows: cmd start /k carries cd /d and the resume command") {
    val (bin, args) = ExternalOpenNative.templateFor("Windows 11", "C:\\w", "codex", Some("th-1"))
    assertEquals(bin, "cmd")
    assertEquals(args.take(4), List("/c", "start", "", "cmd"))
    assert(args(4) == "/k")
    assert(args(5).contains("cd"), args(5))
    assert(args(5).contains("codex resume th-1"), args(5))
  }

  test("Linux: first candidate terminal with -e and sh -c wrapper") {
    val (bin, args) = ExternalOpenNative.templateFor("Linux", cwd, "zcode", Some("zc-1"))
    assertEquals(bin, "x-terminal-emulator") // 默认候选表首位
    assertEquals(args.head, "-e")
    assert(args(1).startsWith("sh -c "), args(1))
    assert(args(1).contains("zcode --resume zc-1"), args(1))
  }

  test("Linux: custom candidate list is honored; empty list falls back to xterm") {
    val (bin1, _) = ExternalOpenNative.templateFor("linux", cwd, "zcode", None, linuxTerminals = List("konsole"))
    assertEquals(bin1, "konsole")
    val (bin2, _) = ExternalOpenNative.templateFor("linux", cwd, "zcode", None, linuxTerminals = Nil)
    assertEquals(bin2, "xterm")
  }

  test("shQuote: single quotes and embedded quotes survive") {
    assertEquals(ExternalOpenNative.shQuote("it's here"), "'it'\\''s here'")
  }

  test("cliSessionId whitelist: alnum/dot/dash only; rejects shell metacharacters and length abuse") {
    assert(ExternalOpenNative.validCliSessionId("sess-9f13.01"))
    assert(!ExternalOpenNative.validCliSessionId(""))
    assert(!ExternalOpenNative.validCliSessionId("a; rm -rf /"))
    assert(!ExternalOpenNative.validCliSessionId("a$b"))
    assert(!ExternalOpenNative.validCliSessionId("a" * 129))
  }

  test("resumeCommand: adapter family mapping; hostile session ids fall back to bare start (injection-proof)") {
    assertEquals(ExternalOpenNative.resumeCommand("claude-code", Some("sess-9")), "claude --resume sess-9")
    assertEquals(ExternalOpenNative.resumeCommand("codex", Some("th-1")), "codex resume th-1")
    assertEquals(ExternalOpenNative.resumeCommand("zcode", Some("zc-1")), "zcode --resume zc-1")
    assertEquals(ExternalOpenNative.resumeCommand("claude-code", None), "claude")
    // 注入尝试：非法 sid 被剥掉 → 裸启动（不把任意文本拼进内层 shell）。
    assertEquals(ExternalOpenNative.resumeCommand("claude-code", Some("a; rm -rf /")), "claude")
  }
