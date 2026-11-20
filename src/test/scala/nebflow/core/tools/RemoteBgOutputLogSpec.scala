package nebflow.core.tools

import munit.FunSuite

/**
 * 后台任务输出查看补全批（作者令 2026-10-25「远端任务看不到」）·
 * [[RemoteBgOutputLog]] 纯判据面 spec。
 *
 * 中心契约（作者实证形态 test5 `/c/Users/Kai/stand-win-test5.log` 为锚）：
 *   - 命令里的输出重定向 → 对端日志路径（stdout 类末位胜出；stderr 类回退；
 *     tee 回退）；
 *   - 引号内 / fd 复制 / devnull / 输入重定向 / IO-number 词中数字不产生路径
 *     （解析不到 = outputPath 缺席，面板走「输出不可用 + 原因」，不猜）；
 *   - 尾拉命令可安全下发（单引号包裹，内嵌 `'` 转义）；
 *   - 对端回执的非零 exit 标记只认末尾 `(exit N)`（解析不到不虚报）。
 */
class RemoteBgOutputLogSpec extends FunSuite:

  // ── parseRedirect：作者实证形态与常见变体 ─────────────────────────────

  test("author's real-world shape: `> /c/Users/Kai/stand-win-test5.log 2>&1`") {
    val cmd = "cd /c/Users/Kai/stand && python run.py > /c/Users/Kai/stand-win-test5.log 2>&1"
    assertEquals(RemoteBgOutputLog.parseRedirect(cmd), Some("/c/Users/Kai/stand-win-test5.log"))
  }

  test("append form `>> log 2>&1` resolves") {
    assertEquals(RemoteBgOutputLog.parseRedirect("sbt test >> build.log 2>&1"), Some("build.log"))
  }

  test("no-space form `>log` resolves (shell-legal)") {
    assertEquals(RemoteBgOutputLog.parseRedirect("make >out.log 2>&1"), Some("out.log"))
  }

  test("stderr-only redirect falls back to the stderr target") {
    assertEquals(RemoteBgOutputLog.parseRedirect("prog 2> err.log"), Some("err.log"))
    assertEquals(RemoteBgOutputLog.parseRedirect("prog 2>> err.log"), Some("err.log"))
  }

  test("all-stream form `&> log` and `&>> log` resolve as stdout-class") {
    assertEquals(RemoteBgOutputLog.parseRedirect("prog &> all.log"), Some("all.log"))
    assertEquals(RemoteBgOutputLog.parseRedirect("prog &>> all.log"), Some("all.log"))
  }

  test("last stdout-class target wins (later redirect overrides earlier)") {
    assertEquals(RemoteBgOutputLog.parseRedirect("prog > a.log > b.log"), Some("b.log"))
  }

  test("quoted path (spaces) is unwrapped") {
    assertEquals(
      RemoteBgOutputLog.parseRedirect("prog > '/tmp/my logs/run log.txt' 2>&1"),
      Some("/tmp/my logs/run log.txt")
    )
    assertEquals(RemoteBgOutputLog.parseRedirect("prog > \"/tmp/x y.log\""), Some("/tmp/x y.log"))
  }

  test("tee pipe form resolves; -a append flag tolerated") {
    assertEquals(RemoteBgOutputLog.parseRedirect("prog | tee out.txt"), Some("out.txt"))
    assertEquals(RemoteBgOutputLog.parseRedirect("prog 2>&1 | tee -a out.txt"), Some("out.txt"))
  }

  test("redirect inside quotes is NOT a redirect (quote-aware scan)") {
    assertEquals(RemoteBgOutputLog.parseRedirect("""echo "a > b" """), None)
    assertEquals(RemoteBgOutputLog.parseRedirect("echo 'x > y' >> real.log 2>&1"), Some("real.log"))
  }

  test("fd dups (2>&1 / >&2 / 1>&2) produce no target") {
    assertEquals(RemoteBgOutputLog.parseRedirect("prog 2>&1"), None)
    assertEquals(RemoteBgOutputLog.parseRedirect("prog >&2"), None)
    assertEquals(RemoteBgOutputLog.parseRedirect("prog 1>&2"), None)
  }

  test("devnull / NUL / variable targets are rejected (no guessing)") {
    assertEquals(RemoteBgOutputLog.parseRedirect("prog > /dev/null 2>&1"), None)
    assertEquals(RemoteBgOutputLog.parseRedirect("prog > NUL 2>&1"), None)
    assertEquals(RemoteBgOutputLog.parseRedirect("prog >nul"), None)
    assertEquals(RemoteBgOutputLog.parseRedirect("prog > $LOGFILE"), None)
  }

  test(
    "word-embedded digit before `>` stays part of the word; the `>` is still a plain stdout redirect (bash tokenization)"
  ) {
    // `-k2` 是词（非全数字词）⇒ `2` 不是 IO-number，`>` 按 stdout 重定向解析。
    assertEquals(RemoteBgOutputLog.parseRedirect("sort -k2> f.txt"), Some("f.txt"))
    // 独立词 `2` = IO-number ⇒ stderr 类目标（同为可拉取路径）。
    assertEquals(RemoteBgOutputLog.parseRedirect("sort 2> f.txt"), Some("f.txt"))
  }

  test("no redirect at all = None; empty command = None") {
    assertEquals(RemoteBgOutputLog.parseRedirect("echo hello"), None)
    assertEquals(RemoteBgOutputLog.parseRedirect(""), None)
  }

  test("windows drive-letter paths resolve verbatim") {
    assertEquals(RemoteBgOutputLog.parseRedirect("node server.js > C:\\logs\\dev.log 2>&1"), Some("C:\\logs\\dev.log"))
  }

  // ── tailCommand：对端安全下发形态 ─────────────────────────────────────

  test("tailCommand: `tail -c N -- '<path>'` with single-quote escaping") {
    assertEquals(
      RemoteBgOutputLog.tailCommand("/c/Users/Kai/stand-win-test5.log", 65536),
      "tail -c 65536 -- '/c/Users/Kai/stand-win-test5.log'"
    )
    // 内嵌单引号按 POSIX 拼接转义（对端 shell 二次解释防护）
    assertEquals(
      RemoteBgOutputLog.tailCommand("/tmp/it's.log", 1024),
      "tail -c 1024 -- '/tmp/it'\\''s.log'"
    )
  }

  test("tail cap constants are display-scale (64KB window, 15s remote timeout)") {
    assertEquals(RemoteBgOutputLog.TailMaxBytes, 64L * 1024)
    assertEquals(RemoteBgOutputLog.TailRemoteTimeoutMs, 15000L)
  }

  // ── parseExitMarker：对端回执的非零 exit 标记 ─────────────────────────

  test("trailing `(exit N)` is recognized; mid-text occurrences are not") {
    assertEquals(RemoteBgOutputLog.parseExitMarker("build ok\n(exit 2)\n"), Some(2))
    assertEquals(RemoteBgOutputLog.parseExitMarker("done (exit 1)"), Some(1))
    // 正文里出现同形文本但不在末尾 → 不误判
    assertEquals(RemoteBgOutputLog.parseExitMarker("(exit 137) was the old failure\ncurrent: all good\n"), None)
    assertEquals(RemoteBgOutputLog.parseExitMarker("clean output, exit 0"), None)
    assertEquals(RemoteBgOutputLog.parseExitMarker(""), None)
  }

end RemoteBgOutputLogSpec
