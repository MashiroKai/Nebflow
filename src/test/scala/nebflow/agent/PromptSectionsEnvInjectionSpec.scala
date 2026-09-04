package nebflow.agent

import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.file.{Files, Paths}
import java.nio.file.attribute.FileTime

/**
 * 宿主 PID 环境注入 spec（进程残留治理 2026-09-05 批次）——钉死注入链路契约：
 *
 *   GatewayMain 启动 System.setProperty("nebflow.gateway.pid"=ProcessHandle.current.pid,
 *   "nebflow.gateway.port"=cfg.port.value)（GatewayMain.scala:210-211，来源=运行时自身）
 *   → PromptSections.renderWithScript 以 env 传给 environment/data.sh（:481-482）
 *   → {{pid}}/{{gateway_port}} 模板替换 → envInfoSection(order 100) 进系统环境表。
 *
 * 节点会话与分发器会话同走 AgentCore envInfoSection 一条路（同源）。
 *
 * 契约：
 *   1. 环境文本含 `| PID | <值> |` 行且值 == ProcessHandle.current().pid()（运行时真值，
 *      非硬编码——变异验红：注释 renderWithScript 的 NEBFLOW_PID 行 → data.sh 回退
 *      `$$`（bash 自身 pid）→ 值≠运行时 pid → 红；恢复 → 绿）
 *   2. `| Gateway port | <值> |` 行 == 注入的 prop 值（用非默认端口 8099 证明端口来自
 *      config 注入而非 8080 硬编码回退）
 */
class PromptSectionsEnvInjectionSpec extends FunSuite:

  test("environment section carries runtime host PID and configured gateway port (non-hardcoded)"):
    val prevRoot = PathUtil.dataRoot
    val prevPid = sys.props.get("nebflow.gateway.pid")
    val prevPort = sys.props.get("nebflow.gateway.port")
    val tempRoot = os.pwd / "target" / "test-env-injection"
    val envDir = tempRoot / "prompts" / "sections" / "environment"
    try
      os.remove.all(tempRoot)
      os.makeDir.all(envDir)
      os.write.over(envDir / "condition.json", """{"order": 100, "condition": "always"}""")
      os.write.over(
        envDir / "prompt.md",
        """## Environment
          |
          || Property | Value |
          ||----------|-------|
          || PID | {{pid}} |
          || Gateway port | {{gateway_port}} |
          |""".stripMargin
      )
      os.write.over(
        envDir / "data.sh",
        """#!/usr/bin/env bash
          |set -euo pipefail
          |cat << JSON
          |{
          |  "pid": "${NEBFLOW_PID:-$$}",
          |  "gateway_port": "${NEBFLOW_GATEWAY_PORT:-8080}"
          |}
          |JSON
          |""".stripMargin
      )
      // fixture mtime 拨到极早：loadFileSectionsCached 按 max-mtime 判缓存，
      // 恢复真实 root 后（真实 mtime ≫ 1000）必然重读，不污染后续套件
      val old = FileTime.fromMillis(1000L)
      for p <- List("condition.json", "prompt.md", "data.sh") do
        Files.setLastModifiedTime(Paths.get((envDir / p).toString), old)

      val runtimePid = java.lang.ProcessHandle.current().pid().toString
      System.setProperty("nebflow.gateway.pid", runtimePid)
      // 非默认端口：命中即证明端口来自 config 注入而非 8080 硬编码回退
      System.setProperty("nebflow.gateway.port", "8099")

      PathUtil.setDataRoot(tempRoot)
      val env = PromptSections.envInfoSection(PromptSections.PromptContext())

      val pidMatch = "\\| PID \\| (\\S+) \\|".r.findFirstMatchIn(env)
      assert(pidMatch.isDefined, s"env text must contain a `| PID |` row, got: $env")
      assertEquals(
        pidMatch.get.group(1),
        runtimePid,
        s"PID row must equal the RUNTIME pid ($runtimePid) — injection broken (fallback to data.sh $$ would show a bash pid)"
      )
      assert(
        env.contains("| Gateway port | 8099 |"),
        s"gateway port row must carry the injected port (8099), got: $env"
      )
    finally
      PathUtil.setDataRoot(prevRoot)
      prevPid match
        case Some(v) => System.setProperty("nebflow.gateway.pid", v)
        case None    => System.clearProperty("nebflow.gateway.pid")
      prevPort match
        case Some(v) => System.setProperty("nebflow.gateway.port", v)
        case None    => System.clearProperty("nebflow.gateway.port")
      os.remove.all(tempRoot)

  test("scala-level fallback: prop absent → PID row still equals the runtime pid (never a hardcoded value)"):
    // 反向契约钉死：renderWithScript 对 NEBFLOW_PID 有双保险——system prop 缺失时
    // 回退 ProcessHandle.current().pid()（PromptSections.scala:481 getOrElse 分支）。
    // 因此「prop 缺位」时 PID 行仍是运行时真值，绝无硬编码常量路径。
    val prevRoot = PathUtil.dataRoot
    val prevPid = sys.props.get("nebflow.gateway.pid")
    val tempRoot = os.pwd / "target" / "test-env-injection-noinj"
    val envDir = tempRoot / "prompts" / "sections" / "environment"
    try
      os.remove.all(tempRoot)
      os.makeDir.all(envDir)
      os.write.over(envDir / "condition.json", """{"order": 100, "condition": "always"}""")
      os.write.over(envDir / "prompt.md", "## Environment\n\n| PID | {{pid}} |\n")
      os.write.over(
        envDir / "data.sh",
        """#!/usr/bin/env bash
          |set -euo pipefail
          |echo "{\"pid\": \"${NEBFLOW_PID:-$$}\"}"
          |""".stripMargin
      )
      val old = FileTime.fromMillis(1000L)
      for p <- List("condition.json", "prompt.md", "data.sh") do
        Files.setLastModifiedTime(Paths.get((envDir / p).toString), old)

      System.clearProperty("nebflow.gateway.pid") // prop 缺位 → 走 scala fallback
      PathUtil.setDataRoot(tempRoot)
      val env = PromptSections.envInfoSection(PromptSections.PromptContext())

      val pidMatch = "\\| PID \\| (\\S+) \\|".r.findFirstMatchIn(env)
      assert(pidMatch.isDefined, s"PID row missing: $env")
      assertEquals(
        pidMatch.get.group(1),
        java.lang.ProcessHandle.current().pid().toString,
        s"with the prop absent the scala fallback must still inject the runtime pid, got: ${pidMatch.get.group(1)}"
      )
    finally
      PathUtil.setDataRoot(prevRoot)
      prevPid match
        case Some(v) => System.setProperty("nebflow.gateway.pid", v)
        case None    => System.clearProperty("nebflow.gateway.pid")
      os.remove.all(tempRoot)

end PromptSectionsEnvInjectionSpec
