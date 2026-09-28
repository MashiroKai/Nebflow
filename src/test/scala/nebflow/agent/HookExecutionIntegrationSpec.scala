package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.ToolExecResult
import nebflow.core.hooks.*
import nebflow.core.tools.ToolContext
import nebflow.shared.ToolCall
import munit.FunSuite

import java.nio.file.{Files => JFiles}

/**
 * Hook P0 integration: a REAL hook process fired from AgentCore.executeTool's
 * PreToolUse/PostToolUse junctions. Config comes from a temp nebflow.json
 * through HooksConfigLoader — the same path GatewayMain uses at startup.
 * Lives in package nebflow.agent because AgentCore is private[agent].
 */
class HookExecutionIntegrationSpec extends FunSuite:

  private val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  // AgentCore.executeTool is protected; expose via minimal stub (AllowedToolSetSpec pattern)
  private object CoreProbe extends AgentCore:
    def exec(call: ToolCall, ctx: ToolContext): IO[ToolExecResult] = executeTool(call, ctx)

  private def mkCtx(root: os.Path, engine: HookEngine): ToolContext = ToolContext(
    projectRoot = root.toString,
    hookEngine = engine,
    hookContext = HookContext(sessionId = Some("sess-hook-int"), projectRoot = root.toString, cwd = root.toString)
  )

  if !isWindows then

    test("PostToolUse hook context flows back into the tool result (end to end)") {
      val root = os.Path(JFiles.createTempDirectory("nb-hook-int").toString)
      try
        os.write(
          root / "nebflow.json",
          """{"hooks": {"PostToolUse": [
            {"matcher": "Read", "hooks": [
              {"type": "command", "command": "echo '{\"additional_context\":\"HOOK-CTX-MARKER\"}'", "timeout": 10}
            ]}
          ]}}"""
        )
        os.write(root / "hello.txt", "hello hook")

        val cfg = HooksConfigLoader.load(root)
        assert(cfg.hooks.contains("PostToolUse"))
        val engine = HookEngine(cfg)

        val call = ToolCall(id = "t1", name = "Read", input = JsonObject("file_path" -> (root / "hello.txt").toString.asJson))
        val res = CoreProbe.exec(call, mkCtx(root, engine)).unsafeRunSync()

        assert(!res.isError, s"Read failed: ${res.content}")
        // the hook's additional_context must be appended to the tool result
        assert(res.content.contains("HOOK-CTX-MARKER"), s"hook context missing from: ${res.content.take(200)}")
      finally os.remove.all(root)
    }

    test("PreToolUse block decision vetoes the tool call before execution") {
      val root = os.Path(JFiles.createTempDirectory("nb-hook-block").toString)
      try
        os.write(
          root / "nebflow.json",
          """{"hooks": {"PreToolUse": [
            {"matcher": "Read", "hooks": [
              {"type": "command", "command": "echo '{\"decision\":\"block\",\"reason\":\"forbidden by policy\"}'", "timeout": 10}
            ]}
          ]}}"""
        )
        val engine = HookEngine(HooksConfigLoader.load(root))
        val call = ToolCall(id = "t2", name = "Read", input = JsonObject("file_path" -> "/etc/hosts".asJson))
        val res = CoreProbe.exec(call, mkCtx(root, engine)).unsafeRunSync()
        // blocked path: error result carrying the hook reason; tool never ran
        assert(res.isError)
        assert(res.content.contains("forbidden by policy"))
      finally os.remove.all(root)
    }

    test("matcher non-matching tool name fires nothing") {
      val root = os.Path(JFiles.createTempDirectory("nb-hook-nomatch").toString)
      try
        os.write(
          root / "nebflow.json",
          """{"hooks": {"PostToolUse": [
            {"matcher": "Edit", "hooks": [
              {"type": "command", "command": "echo '{\"additional_context\":\"MUST-NOT-APPEAR\"}'", "timeout": 10}
            ]}
          ]}}"""
        )
        val engine = HookEngine(HooksConfigLoader.load(root))
        os.write(root / "f.txt", "x")
        val call = ToolCall(id = "t3", name = "Read", input = JsonObject("file_path" -> (root / "f.txt").toString.asJson))
        val res = CoreProbe.exec(call, mkCtx(root, engine)).unsafeRunSync()
        assert(!res.isError)
        assert(!res.content.contains("MUST-NOT-APPEAR"))
      finally os.remove.all(root)
    }

end HookExecutionIntegrationSpec
