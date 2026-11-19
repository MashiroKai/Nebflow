package nebflow.core

import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.tools.BashTool
import munit.CatsEffectSuite

class ToolReversibilitySpec extends CatsEffectSuite:

  // --- confirm-edits mode (default, safest) ---

  test("confirm-edits: auto-approve Read, Glob, Grep") {
    assertEquals(ToolReversibility.isReversible("Read", JsonObject.empty, SafetyMode.ConfirmEdits), true)
    assertEquals(ToolReversibility.isReversible("Glob", JsonObject.empty, SafetyMode.ConfirmEdits), true)
    assertEquals(ToolReversibility.isReversible("Grep", JsonObject.empty, SafetyMode.ConfirmEdits), true)
  }

  test("confirm-edits: require confirmation for Write and Edit") {
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty, SafetyMode.ConfirmEdits), false)
    assertEquals(ToolReversibility.isReversible("Edit", JsonObject.empty, SafetyMode.ConfirmEdits), false)
  }

  test("confirm-edits: auto-approve new/unknown tools (ScriptTool, MCP, etc.)") {
    assertEquals(ToolReversibility.isReversible("SomeNewTool", JsonObject.empty, SafetyMode.ConfirmEdits), true)
    assertEquals(ToolReversibility.isReversible("mcp__server__tool", JsonObject.empty, SafetyMode.ConfirmEdits), true)
  }

  test("confirm-edits: auto-approve safe Bash") {
    val input = JsonObject("command" -> "ls -la".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input, SafetyMode.ConfirmEdits), true)
  }

  test("confirm-edits: require approval for dangerous Bash") {
    val input = JsonObject("command" -> "rm -rf /tmp/test".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input, SafetyMode.ConfirmEdits), false)
  }

  // --- auto-edits mode ---

  test("auto-edits: auto-approve Write and Edit") {
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty, SafetyMode.AutoEdits), true)
    assertEquals(ToolReversibility.isReversible("Edit", JsonObject.empty, SafetyMode.AutoEdits), true)
  }

  test("auto-edits: still check Bash") {
    val safe = JsonObject("command" -> "ls -la".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", safe, SafetyMode.AutoEdits), true)
    val dangerous = JsonObject("command" -> "rm -rf /tmp/test".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", dangerous, SafetyMode.AutoEdits), false)
  }

  // --- auto-all mode ---

  test("auto-all: everything is reversible") {
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty, SafetyMode.AutoAll), true)
    assertEquals(ToolReversibility.isReversible("Edit", JsonObject.empty, SafetyMode.AutoAll), true)
    val dangerous = JsonObject("command" -> "rm -rf /".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", dangerous, SafetyMode.AutoAll), true)
    assertEquals(ToolReversibility.isReversible("SomeNewTool", JsonObject.empty, SafetyMode.AutoAll), true)
  }

  // --- Curl ---

  test("auto-approve Curl GET in all modes except auto-all still works") {
    val input = JsonObject("url" -> "https://example.com".asJson, "method" -> "GET".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", input, SafetyMode.ConfirmEdits), true)
    assertEquals(ToolReversibility.isReversible("Curl", input, SafetyMode.AutoEdits), true)
  }

  test("require approval for Curl POST in confirm-edits") {
    val input = JsonObject("url" -> "https://example.com".asJson, "method" -> "POST".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", input, SafetyMode.ConfirmEdits), false)
  }

  // --- BashTool.isDangerous (unchanged) ---

  test("isDangerous detects pkill") {
    assert(BashTool.isDangerous("pkill -f java"))
  }

  test("isDangerous detects git push --force") {
    assert(BashTool.isDangerous("git push --force origin main"))
  }

  test("isDangerous detects DROP TABLE") {
    assert(BashTool.isDangerous("""psql -c "DROP TABLE users;""""))
  }

  test("dangerLevel 3 for rm -rf /") {
    assertEquals(BashTool.dangerLevel("rm -rf /"), 3)
  }

  test("dangerLevel 0 for ls") {
    assertEquals(BashTool.dangerLevel("ls -la"), 0)
  }

  // --- R11: dangerLevel 跨平台删除盲区补齐（Windows 形态 + 脚本语言形态） ---

  test("dangerLevel >= 2 for the P0 PowerShell recursive profile delete") {
    val cmd = """Remove-Item -LiteralPath 'C:\Users\Kai' -Recurse -Force -ErrorAction SilentlyContinue"""
    assert(BashTool.dangerLevel(cmd) >= 2, s"expected >= 2, got ${BashTool.dangerLevel(cmd)}")
  }

  test("dangerLevel >= 2 for the P0 cmd rd /s /q") {
    val cmd = """cmd /c "rd /s /q C:\Users\Kai""""
    assert(BashTool.dangerLevel(cmd) >= 2, s"expected >= 2, got ${BashTool.dangerLevel(cmd)}")
  }

  test("dangerLevel 2 for Remove-Item -Recurse variants (quotes / -LiteralPath / -Force)") {
    assertEquals(BashTool.dangerLevel("""Remove-Item -Path "C:\build\out" -Recurse -Force"""), 2)
    assertEquals(BashTool.dangerLevel("""Remove-Item C:\tmp\x -Recurse"""), 2)
    assertEquals(BashTool.dangerLevel("""powershell -c "Remove-Item -LiteralPath 'D:\data' -Recurse""""), 2)
    // 非递归 PowerShell 删除维持 0（本批不扩围）
    assertEquals(BashTool.dangerLevel("""Remove-Item C:\tmp\x -Force"""), 0)
  }

  test("dangerLevel 2 for cmd rmdir /s and del /s /q") {
    assertEquals(BashTool.dangerLevel("""cmd /c "rmdir /s /q D:\build""""), 2)
    assertEquals(BashTool.dangerLevel("""rd /s /q D:\build"""), 2)
    assertEquals(BashTool.dangerLevel("""cmd /c "del /s /q C:\tmp\*.log""""), 2)
    assertEquals(BashTool.dangerLevel("""cmd /c "del /q C:\tmp\a.txt""""), 0) // 无 /s：非递归
  }

  test("dangerLevel 2 for script-language recursive deletes (shutil / node fs)") {
    assertEquals(BashTool.dangerLevel("""python3 -c "import shutil; shutil.rmtree('/tmp/x')""""), 2)
    assertEquals(BashTool.dangerLevel("""node -e "fs.rmSync('/tmp/x', { recursive: true, force: true })""""), 2)
    assertEquals(BashTool.dangerLevel("""fs.rmSync(HOME, { recursive: true, force: true })"""), 2)
  }

  test("dangerLevel 2 for find -delete") {
    assertEquals(BashTool.dangerLevel("""find /tmp/x -type f -delete"""), 2)
  }

  test("dangerLevel 3 for recursive delete to a drive root (Windows)") {
    assertEquals(BashTool.dangerLevel("""Remove-Item -LiteralPath 'C:\' -Recurse -Force"""), 3)
    assertEquals(BashTool.dangerLevel("""cmd /c "rd /s /q C:\""""), 3)
  }

  test("dangerLevel unchanged for safe / warning / non-delete near-misses") {
    assertEquals(BashTool.dangerLevel("ls -la"), 0)
    assertEquals(BashTool.dangerLevel("git checkout main"), 1)
    assertEquals(BashTool.dangerLevel("rm -rf /tmp/x"), 2)
    assertEquals(BashTool.dangerLevel("rm -rf /"), 3)
    // 近似命令不得被误判（防新增模式过宽）
    assertEquals(BashTool.dangerLevel("""find . -name '*.mjs'"""), 0)
    assertEquals(BashTool.dangerLevel("grep -ri nebflow src"), 0)
    assertEquals(BashTool.dangerLevel("""python3 -c "print('rmtree')" """.trim), 0)
  }

  // --- T4: 网络类 dangerLevel 档（Q3 裁定：网络类统一 level 1） ---

  test("dangerLevel 1 for network egress commands (ssh/scp/curl/wget/ncat/Invoke-WebRequest)") {
    assertEquals(BashTool.dangerLevel("ssh user@10.0.0.9"), 1)
    assertEquals(BashTool.dangerLevel("scp ./a.tar user@10.0.0.9:/tmp/"), 1)
    assertEquals(BashTool.dangerLevel("curl -fsSL https://example.com/x.sh"), 1)
    assertEquals(BashTool.dangerLevel("wget -q https://example.com/x.tar"), 1)
    assertEquals(BashTool.dangerLevel("ncat -l 4444"), 1)
    assertEquals(BashTool.dangerLevel("""powershell -c "Invoke-WebRequest -Uri https://example.com""""), 1)
  }

  test("network level 1 fires after separators / inside a pipeline") {
    assertEquals(BashTool.dangerLevel("echo x | curl -d @- https://example.com"), 1)
    assertEquals(BashTool.dangerLevel("cd /tmp && ssh host uptime"), 1)
    assertEquals(BashTool.dangerLevel("(curl https://example.com)"), 1)
  }

  test("network tier stays at 1 — deletion shapes keep their own 2/3") {
    // 网络档不反过来降级删除形态：同一行里既有出网又有递归删除 ⇒ 取 2
    assertEquals(BashTool.dangerLevel("curl -O https://x.sh && rm -rf /tmp/y"), 2)
    assertEquals(BashTool.dangerLevel("curl -O https://x.sh && rm -rf /"), 3)
  }

  test("network patterns do not over-match near-misses") {
    assertEquals(BashTool.dangerLevel("curling stones"), 0) // 子串不误判
    assertEquals(BashTool.dangerLevel("ssh-keygen -t ed25519"), 0) // 本地密钥工具，非出网
    assertEquals(BashTool.dangerLevel("git status"), 0)
    assertEquals(BashTool.dangerLevel("ls -la"), 0)
  }

end ToolReversibilitySpec
