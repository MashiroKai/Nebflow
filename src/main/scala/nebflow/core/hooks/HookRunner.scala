package nebflow.core.hooks

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.core.NebflowLogger

import scala.concurrent.duration.*
import scala.util.Using

/** Spawns a hook process, sends JSON via stdin, reads JSON from stdout. */
object HookRunner:

  private val logger = NebflowLogger.forName("nebflow.hooks")

  /**
   * Execute a single hook command.
   *
   * @param defn       Hook definition (command, timeout)
   * @param event      Event type
   * @param payload    Event payload (serialized to stdin JSON)
   * @param context    Execution context (session, project root, cwd)
   * @param toolName   Tool name for env var injection
   * @return Parsed HookResult, or a fallback on error/timeout
   */
  def run(
    defn: HookDef,
    event: HookEvent,
    payload: HookPayload,
    context: HookContext,
    toolName: Option[String] = None
  ): IO[HookResult] =
    val stdinJson = buildStdin(event, payload, context)
    val envVars = buildEnvVars(event, payload, context, toolName)
    val timeout = defn.timeout.seconds

    executeCommand(defn.command, stdinJson, envVars, context.projectRoot, timeout)
      .map(parseStdout)
      .recoverWith { e =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        if defn.continueOnError then
          logger.warn(s"Hook '${defn.command.take(60)}' failed: $msg — continuing")
          IO.pure(
            HookResult(
              additionalContext = Some(s"[Hook error: $msg]")
            )
          )
        else
          logger.warn(s"Hook '${defn.command.take(60)}' failed: $msg — blocking")
          IO.pure(HookResult.block(s"Hook failed: $msg"))
      }
  end run

  // ------------------------------------------------------------------
  // Stdin JSON
  // ------------------------------------------------------------------

  private def buildStdin(event: HookEvent, payload: HookPayload, context: HookContext): String =
    val base = JsonObject(
      "event" -> event.toString.asJson,
      "session_id" -> context.sessionId.asJson,
      "project_root" -> context.projectRoot.asJson,
      "cwd" -> context.cwd.asJson,
      "timestamp" -> java.time.Instant.now().toString.asJson
    )

    val toolFields = List(
      payload.toolName.map("tool_name" -> _.asJson),
      payload.toolInput.map("tool_input" -> _.asJson),
      payload.toolOutput.map("tool_output" -> _.asJson),
      payload.toolSuccess.map("tool_success" -> _.asJson)
    ).flatten

    val compactFields = List(
      payload.compactMessagesBefore.map("compact_messages_before" -> _.asJson),
      payload.compactMessagesAfter.map("compact_messages_after" -> _.asJson),
      payload.compactTokensSaved.map("compact_tokens_saved" -> _.asJson)
    ).flatten

    val allFields = base.toList ++ toolFields ++ compactFields ++
      (if payload.extra.nonEmpty then List("extra" -> payload.extra.asJson) else Nil)

    Json.fromFields(allFields).spaces2
  end buildStdin

  // ------------------------------------------------------------------
  // Environment variables
  // ------------------------------------------------------------------

  private def buildEnvVars(
    event: HookEvent,
    payload: HookPayload,
    context: HookContext,
    toolName: Option[String]
  ): Map[String, String] =
    Map(
      "HOOK_EVENT" -> event.toString,
      "SESSION_ID" -> context.sessionId.getOrElse(""),
      "PROJECT_ROOT" -> context.projectRoot,
      "TOOL_NAME" -> toolName.getOrElse(""),
      "TOOL_INPUT_FILE_PATH" -> payload.toolInput
        .flatMap(_("file_path").flatMap(_.asString))
        .getOrElse("")
    )

  // ------------------------------------------------------------------
  // Process execution
  // ------------------------------------------------------------------

  private def executeCommand(
    command: String,
    stdinJson: String,
    envVars: Map[String, String],
    cwd: String,
    timeout: FiniteDuration
  ): IO[String] =
    IO.blocking {
      val pb = new ProcessBuilder("bash", "-c", command)
      pb.directory(new java.io.File(cwd))
      pb.redirectErrorStream(false)
      envVars.foreach { case (k, v) => pb.environment().put(k, v) }

      val proc = pb.start()

      // Write stdin
      Using(proc.getOutputStream) { os =>
        os.write(stdinJson.getBytes("UTF-8"))
        os.flush()
      }
      proc.getOutputStream.close()

      // Read stdout in background
      val stdoutFork = ThreadRead(proc.getInputStream)
      val stderrFork = ThreadRead(proc.getErrorStream)

      // Wait with timeout
      val finished = proc.waitFor(timeout.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
      if !finished then
        proc.destroyForcibly()
        stdoutFork.cancel()
        stderrFork.cancel()
        throw new scala.concurrent.TimeoutException(s"Hook timed out after ${timeout.toSeconds}s")

      proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) // ensure cleanup
      val exitCode = proc.exitValue()
      val stdout = stdoutFork.result()
      val stderr = stderrFork.result()

      if exitCode != 0 && stderr.nonEmpty then logger.debug(s"Hook stderr: ${stderr.take(200)}")

      stdout
    }

  // ------------------------------------------------------------------
  // Output parsing
  // ------------------------------------------------------------------

  private def parseStdout(stdout: String): HookResult =
    val trimmed = stdout.trim
    if trimmed.isEmpty then HookResult.allow
    else
      parser.parse(trimmed) match
        case Right(json) =>
          json.asObject match
            case Some(obj) => parseHookResult(obj)
            case None => HookResult(additionalContext = Some(trimmed))
        case Left(_) =>
          // Not JSON — treat as plain text context injection
          HookResult(additionalContext = Some(trimmed))

  private def parseHookResult(obj: JsonObject): HookResult =
    val decision = obj("decision").flatMap(_.asString) match
      case Some("block") => HookDecision.Block
      case _ => HookDecision.Allow

    HookResult(
      decision = decision,
      reason = obj("reason").flatMap(_.asString),
      updatedInput = obj("updated_input").flatMap(_.asObject),
      additionalContext = obj("additional_context").flatMap(_.asString),
      shouldStop = obj("continue").exists(v => v.asBoolean.contains(false)),
      stopReason = obj("stop_reason").flatMap(_.asString)
    )

  // ------------------------------------------------------------------
  // Thread-based stream reading (avoids cats-effect fiber overhead for short-lived processes)
  // ------------------------------------------------------------------

  private class ThreadRead(is: java.io.InputStream):
    private val buffer = new StringBuilder

    private val thread = new Thread(() =>
      try
        val reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"))
        var line: String = null
        while { line = reader.readLine(); line != null } do buffer.append(line).append("\n")
      catch case _: java.io.IOException => () // expected on destroy
      finally is.close()
    )
    thread.setDaemon(true)
    thread.start()

    def result(): String = synchronized {
      thread.join(2000)
      buffer.toString.trim
    }

    def cancel(): Unit = thread.interrupt()
end HookRunner
