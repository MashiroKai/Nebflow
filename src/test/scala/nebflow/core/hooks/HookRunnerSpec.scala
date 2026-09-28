package nebflow.core.hooks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

import java.nio.file.{Files => JFiles}

/**
 * HookRunner against REAL processes (bash on macOS/Linux CI path).
 * Verifies the stdin JSON contract, env vars, stdout three-state parsing,
 * timeout and both continueOnError branches.
 */
class HookRunnerSpec extends FunSuite:

  // Skip the whole suite when bash is not the execution backend (Windows).
  private val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  private val ctx = HookContext(sessionId = Some("sess-1"), projectRoot = "/tmp", cwd = "/tmp")

  private def toolPayload(name: String = "Edit", filePath: String = "/tmp/f.txt"): HookPayload =
    HookPayload(
      toolName = Some(name),
      toolInput = Some(JsonObject("file_path" -> filePath.asJson))
    )

  private def run(cmd: String, timeout: Int = 10, continueOnError: Boolean = true): HookResult =
    HookRunner
      .run(
        HookDef(`type` = "command", command = cmd, timeout = timeout, continueOnError = continueOnError),
        HookEvent.PreToolUse,
        toolPayload(),
        ctx,
        Some("Edit")
      )
      .unsafeRunSync()

  if !isWindows then

    test("stdin JSON carries event, session, project root, tool fields") {
      // python parses the stdin JSON and echoes one field back as plain text
      val out = run(
        """python3 -c "import json,sys; d=json.load(sys.stdin); print(d['event'], d['session_id'], d['tool_name'], d['tool_input']['file_path'], d['project_root'])" """
      )
      assertEquals(out.additionalContext, Some("PreToolUse sess-1 Edit /tmp/f.txt /tmp"))
    }

    test("env vars HOOK_EVENT / SESSION_ID / TOOL_NAME / TOOL_INPUT_FILE_PATH are injected") {
      val out = run("""echo "$HOOK_EVENT|$SESSION_ID|$TOOL_NAME|$TOOL_INPUT_FILE_PATH"""")
      assertEquals(out.additionalContext, Some("PreToolUse|sess-1|Edit|/tmp/f.txt"))
    }

    test("stdout state 1: valid JSON object is parsed into a structured HookResult") {
      val out = run("""echo '{"decision":"block","reason":"forbidden zone","updated_input":{"file_path":"/tmp/ok.txt"},"additional_context":"note","continue":false,"stop_reason":"halt"}'""")
      assertEquals(out.decision, HookDecision.Block)
      assertEquals(out.reason, Some("forbidden zone"))
      assertEquals(
        out.updatedInput.map(_("file_path").flatMap(_.asString)),
        Some(Some("/tmp/ok.txt"))
      )
      assertEquals(out.additionalContext, Some("note"))
      assert(out.shouldStop)
      assertEquals(out.stopReason, Some("halt"))
    }

    test("stdout state 2: non-JSON text becomes additionalContext") {
      val out = run("echo plain-text-note")
      assertEquals(out.decision, HookDecision.Allow)
      assertEquals(out.additionalContext, Some("plain-text-note"))
    }

    test("stdout state 3: empty stdout yields plain allow with no context") {
      val out = run("true")
      assertEquals(out, HookResult.allow)
    }

    test("stdout JSON array (not object) degrades to additionalContext") {
      val out = run("echo '[1,2,3]'")
      assertEquals(out.decision, HookDecision.Allow)
      assertEquals(out.additionalContext, Some("[1,2,3]"))
    }

    test("timeout produces hook error; continueOnError=true keeps going") {
      val out = run("sleep 30", timeout = 1, continueOnError = true)
      assertEquals(out.decision, HookDecision.Allow)
      assert(out.additionalContext.exists(_.contains("[Hook error:")))
      assert(out.additionalContext.exists(_.contains("timed out")))
    }

    test("timeout with continueOnError=false blocks") {
      val out = run("sleep 30", timeout = 1, continueOnError = false)
      assertEquals(out.decision, HookDecision.Block)
      assert(out.reason.exists(_.contains("Hook failed")))
    }

    test("nonzero exit with no stdout degrades via continueOnError path") {
      val out = run("exit 3", timeout = 5, continueOnError = true)
      assertEquals(out.decision, HookDecision.Allow)
    }

end HookRunnerSpec
