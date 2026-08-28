package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

import scala.concurrent.duration.*

/**
  * One-shot headless task execution (P0 benchmark — /tmp/headless-design.md
  * D3). This is the Harbor installed-agent entry: send a task, block until
  * the turn completes, print the result, exit.
  *
  *   nebflow run -p "list files" [--agent X] [--session Y] [--json] [--timeout 1800]
  *   cat task.md | nebflow run --json
  *
  * Exit codes: 0 completed with a non-empty final message · 1 turn errored or
  * produced no message · 2 gateway unreachable or turn timed out.
  */
object RunCommand extends CliCommand:
  def name = "run"
  def description = "One-shot headless task: send → wait → result → exit"
  def subcommands = List(RunTask)
  def examples = List(
    """nebflow run -p "list the files in this directory"""",
    """nebflow run --agent terminal-solver --json -p "$(cat task.md)"""",
    "cat task.md | nebflow run"
  )

  private object RunTask extends CliSubcommand:
    def name = "task"
    def description = "Execute one task and print the result"
    def params = List(
      CliParam("prompt", Some('p'), "Task text (omit to read stdin)", required = false),
      CliParam("agent", None, "Agent for a new session (ignored with --session)", required = false),
      CliParam("session", None, "Existing session id to reuse (default: fresh session)", required = false),
      CliParam("timeout", Some('t'), "Turn timeout in seconds (default 1800)", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      for
        task <- ctx.args.get("prompt").filter(_.nonEmpty) match
          case Some(t) => IO.pure(t)
          case None    => IO.blocking(new String(System.in.readAllBytes(), "UTF-8"))
        result <-
          if task.trim.isEmpty then
            IO.pure(CliResult.Error("No task provided (use -p \"<task>\" or pipe stdin)", 2))
          else execute(ctx, task)
      yield result

    /** Resolve a client (auto-starting the gateway once), pick the session,
      * call the turn endpoint, map the response to stdout + exit code.
      */
    private def execute(ctx: CliContext, task: String): IO[CliResult] =
      ctx.client match
        case Some(client) => dispatchTurn(ctx, client, task)
        case None =>
          ensureGateway.flatMap {
            case false =>
              IO.pure(
                CliResult.Error("Gateway not reachable (auto-start failed); run 'nebflow start'", 2)
              )
            case true =>
              GatewayClient.create.flatMap {
                case Some(client) => dispatchTurn(ctx, client, task)
                case None         => IO.pure(CliResult.Error("Gateway did not come up in 60s", 2))
              }
          }

    private def dispatchTurn(ctx: CliContext, client: GatewayClient, task: String): IO[CliResult] =
      val timeoutSec = ctx.args.get("timeout").flatMap(_.toIntOption).getOrElse(1800)
      for
        sid <- resolveSession(ctx, client)
        result <- sid match
          case Right(sessionId) =>
            client
              .post(s"/api/sessions/$sessionId/turn",
                Json.obj("content" -> task.asJson, "timeoutSec" -> timeoutSec.asJson))
              .map(resp => render(resp, ctx.json))
              .handleErrorWith(e =>
                IO.pure(CliResult.Error(s"turn request failed: ${e.getMessage}", 2))
              )
          case Left(err) => IO.pure(err)
      yield result

    /** --session reuses (history resumes via the actor's restart-resume path);
      * default creates a fresh session (benchmark isolation semantics).
      */
    private def resolveSession(ctx: CliContext, client: GatewayClient): IO[Either[CliResult, String]] =
      ctx.args.get("session").filter(_.nonEmpty) match
        case Some(id) => IO.pure(Right(id))
        case None =>
          val body = ctx.args.get("agent").filter(_.nonEmpty).fold(
            Json.obj("name" -> "headless-run".asJson)
          )(a => Json.obj("name" -> "headless-run".asJson, "agentName" -> a.asJson))
          client.post("/api/sessions", body).map { meta =>
            meta.hcursor.downField("id").as[String] match
              case Right(id) if id.nonEmpty => Right(id)
              case _ => Left(CliResult.Error(s"session creation failed: ${meta.noSpaces.take(200)}", 2))
          }

    /** stdout is machine-read by harnesses: plain mode prints ONLY the final
      * message; --json prints the full response body. Exit code per header.
      */
    private def render(resp: Json, jsonMode: Boolean): CliResult =
      val status = resp.hcursor.downField("status").as[String].getOrElse("error")
      val finalMessage = resp.hcursor.downField("finalMessage").as[String].getOrElse("")
      val output = if jsonMode then resp.spaces2 else finalMessage
      (status, finalMessage.isEmpty) match
        case ("completed", false) => CliResult.Exit(0, output)
        case ("completed", true)  => CliResult.Exit(1, output) // finished but silent — treat as failure
        case ("timeout", _)       => CliResult.Exit(2, output)
        case _                    => CliResult.Exit(1, output) // status=error
      end match
    end render

  end RunTask

  /** Fire-and-forget `nebflow start` (it blocks in the foreground of its own
    * detached process), then poll reachability for up to 60s. Failure to spawn
    * is not fatal here — the poll decides.
    */
  private def ensureGateway: IO[Boolean] =
    IO.blocking {
      try
        val pb = new ProcessBuilder("nebflow", "start")
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.start()
        true
      catch case _: Exception => false
    }.flatMap { spawned =>
      def poll(remaining: Int): IO[Boolean] =
        if remaining <= 0 then IO.pure(false)
        else
          IO.sleep(500.millis) *> GatewayClient.create.flatMap {
            case Some(_) => IO.pure(true)
            case None    => poll(remaining - 1)
          }
      poll(if spawned then 120 else 12) // 60s after a spawn attempt, 6s otherwise
    }

end RunCommand
