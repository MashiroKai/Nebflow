package nebflow.core

import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.tools.BashTool
import nebflow.shared.ToolCall
import munit.CatsEffectSuite

/**
 * 审批卡三要素（报障批 2026-11-19）：**命令正文 / 为什么危险 / 作用域**。
 *
 * 这里钉住的三条不变式，正是作者实拍卡上缺的东西：
 *   ① 命令正文逐字保留（多行、不静默截断）——而不是「危险操作：」后面空着；
 *   ② 理由来自判定闸**自己**命中的规则（id + 标签 + 闸名），不是前端猜的；
 *   ③ 作用域 = 本机设备名 + 工作目录（+ 文件类操作的目标路径）。
 */
class PermissionCardDetailSpec extends CatsEffectSuite:

  private def call(name: String, input: JsonObject) = ToolCall("id-1", name, input)

  private val dangerousCmd =
    "set -e\ncd /tmp/probe\nrm -rf /tmp/probe/a\nrm -rf /tmp/probe/b\nls -la"

  test("dangerVerdict: 危险 Bash 命中删除档并带回规则 id/标签") {
    val v = BashTool.dangerVerdict("rm -rf /tmp/probe/a")
    assertEquals(v.level, 2)
    assertEquals(v.ruleId, "bash.level2.rm-rf")
    assertEquals(v.label, "递归删除")
  }

  test("dangerVerdict: 安全命令 = 0 档且无规则") {
    assertEquals(BashTool.dangerVerdict("ls -la"), BashTool.DangerVerdict.Safe)
    assertEquals(BashTool.dangerLevel("ls -la"), 0)
  }

  test("dangerLevel 与改前逐档等价（critical > dangerous > warning）") {
    assertEquals(BashTool.dangerLevel("mkfs.ext4 /dev/sdb1"), 3)
    assertEquals(BashTool.dangerLevel("rm -rf /tmp/x"), 2)
    assertEquals(BashTool.dangerLevel("git push --force origin main"), 1)
    assertEquals(BashTool.dangerLevel("echo hi"), 0)
  }

  test("askReason: 出卡理由与判定同源（危险 Bash）") {
    val input = JsonObject("command" -> "rm -rf /tmp/probe/a".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input, SafetyMode.ConfirmEdits), false)
    val r = ToolReversibility.askReason("Bash", input, SafetyMode.ConfirmEdits)
    assert(r.isDefined, "危险命令必须给出理由")
    assertEquals(r.get.gate, "ToolReversibility.checkBash")
    assertEquals(r.get.ruleId, "bash.gate.rm-rf")
    assertEquals(r.get.label, "递归删除（rm -rf）")
  }

  test("askReason: 写类工具在逐次确认档也给理由；编辑放行档放行") {
    val r = ToolReversibility.askReason("Write", JsonObject.empty, SafetyMode.ConfirmEdits)
    assertEquals(r.map(_.ruleId), Some("tool.write-needs-confirm"))
    assertEquals(ToolReversibility.askReason("Write", JsonObject.empty, SafetyMode.AutoEdits), None)
  }

  test("askReason: auto-all 下不出卡也不给理由") {
    val input = JsonObject("command" -> "rm -rf /".asJson)
    assertEquals(ToolReversibility.askReason("Bash", input, SafetyMode.AutoAll), None)
  }

  test("askReason: Curl 非只读方法给理由，GET 不给") {
    val post = JsonObject("method" -> "POST".asJson, "url" -> "https://x/y".asJson)
    assertEquals(ToolReversibility.askReason("Curl", post, SafetyMode.ConfirmEdits).map(_.ruleId), Some("curl.non-safe-method"))
    val get = JsonObject("method" -> "GET".asJson, "url" -> "https://x/y".asJson)
    assertEquals(ToolReversibility.askReason("Curl", get, SafetyMode.ConfirmEdits), None)
  }

  test("build: 命令正文逐字保留（多行不截断）") {
    val c = call("Bash", JsonObject("command" -> dangerousCmd.asJson))
    val detail = PermissionCardDetail.build(c, "/work/dir", SafetyMode.ConfirmEdits,
      ToolReversibility.askReason("Bash", c.input, SafetyMode.ConfirmEdits), isRemoteExec = false, deviceName = "test-device")
    assertEquals(detail("command").flatMap(_.asString), Some(dangerousCmd))
  }

  test("build: 理由三件（闸/规则/标签）+ 档位") {
    val c = call("Bash", JsonObject("command" -> "rm -rf /tmp/probe/a".asJson))
    val detail = PermissionCardDetail.build(c, "/work/dir", SafetyMode.ConfirmEdits,
      ToolReversibility.askReason("Bash", c.input, SafetyMode.ConfirmEdits), isRemoteExec = false, deviceName = "test-device")
    val reason = detail("reason").flatMap(_.asObject).getOrElse(fail("reason 缺失"))
    assertEquals(reason("gate").flatMap(_.asString), Some("ToolReversibility.checkBash"))
    assertEquals(reason("ruleId").flatMap(_.asString), Some("bash.gate.rm-rf"))
    assertEquals(reason("label").flatMap(_.asString), Some("递归删除（rm -rf）"))
    assertEquals(reason("level").flatMap(_.asNumber).flatMap(_.toInt), Some(2))
  }

  test("build: 作用域 = 设备名 + 工作目录 + 目标路径（文件类）") {
    val c = call("Write", JsonObject("file_path" -> "/work/dir/a.txt".asJson, "content" -> "x".asJson))
    val detail = PermissionCardDetail.build(c, "/work/dir", SafetyMode.ConfirmEdits,
      ToolReversibility.askReason("Write", c.input, SafetyMode.ConfirmEdits), isRemoteExec = false, deviceName = "test-device")
    val scope = detail("scope").flatMap(_.asObject).getOrElse(fail("scope 缺失"))
    assertEquals(scope("cwd").flatMap(_.asString), Some("/work/dir"))
    assertEquals(scope("target").flatMap(_.asString), Some("/work/dir/a.txt"))
    // 设备名永不回落字面 "local"（作者令：显示本机设备名）
    assert(scope("device").flatMap(_.asString).forall(_ != "local"))
  }

  test("build: 超长参数显式标注截断量（不静默裁掉）") {
    val big = "x" * (PermissionCardDetail.MaxInlineChars + 500)
    val c = call("Bash", JsonObject("command" -> big.asJson))
    val detail = PermissionCardDetail.build(c, "/work/dir", SafetyMode.ConfirmEdits, None, isRemoteExec = false, deviceName = "test-device")
    val text = detail("command").flatMap(_.asString).getOrElse("")
    assert(text.contains("正文超长"), "超长正文必须显式标注（不静默截断）")
    assert(text.contains(big.length.toString), "标注里要带原文长度")
  }
