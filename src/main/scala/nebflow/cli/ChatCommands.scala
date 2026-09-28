package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

object ChatCommand extends CliCommand:
  def name = "chat"
  def description = "Interactive or single-shot chat"
  def subcommands = List(ChatSend, ChatRepl)

  def examples = List(
    "nebflow chat \"what does this project do?\"",
    "nebflow chat --session abc-123 \"continue\""
  )

  /** A16: resolve the session for a one-shot send.
    *
    * The first frame used to be a private shape
    * `{"type":"command","command":"createSession"}` which is NOT in the WS
    * vocabulary — WebSocketRoutes' `case "command"` handles clear/compact/fork
    * only, so the gateway silently ignored it. The real vocabulary is
    * `switchSession` / `createSession` (WebSocketRoutes.scala:2015, :2030).
    */
  private[cli] def sessionFrame(sessionArg: Option[String]): Json =
    sessionArg.filter(_.nonEmpty) match
      case Some(sid) => Json.obj("type" -> "switchSession".asJson, "sessionId" -> sid.asJson)
      case None      => Json.obj("type" -> "createSession".asJson, "name" -> "New Session".asJson)

  /** The `createSession` reply carries no direct id — the gateway answers with
    * a session-list frame (`sendSessionList` / `sendAgentSessionListByName`),
    * because SessionService.createSession discards the meta it just created
    * (SessionService.scala:38). The new meta is PREPENDED to the index
    * (SessionStore.scala:712), so the first entry is the session just created;
    * a name match is preferred in case the frame is ever filtered.
    */
  private[cli] def newSessionId(resp: Json, requestedName: String): String =
    val sessions = resp.hcursor.downField("sessions").as[List[Json]].getOrElse(Nil)
    sessions
      .find(_.hcursor.downField("name").as[String].toOption.contains(requestedName))
      .orElse(sessions.headOption)
      .flatMap(_.hcursor.downField("id").as[String].toOption)
      .getOrElse("")

  private object ChatSend extends CliSubcommand:
    def name = "send"
    def description = "Send a single message"

    def params = List(
      CliParam("session", Some('s'), "Session ID to use", required = false),
      CliParam("continue", None, "Continue recent session", isFlag = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running. Start with 'nebflow start'"))
        case Some(client) =>
          val query = ctx.positionalArgs.headOption.getOrElse("")
          if query.isEmpty then IO.pure(CliResult.Error("No query provided"))
          else
            val sessionArg = ctx.args.get("session")
            // A16: send the real vocabulary frame, then consume the sessionId it
            // returns — the follow-up `userMessage` used to carry `sessionId: ""`,
            // so the text never reached the session that had just been created.
            for
              frame <- client.command(sessionFrame(sessionArg))
              sessionId = sessionArg.filter(_.nonEmpty).getOrElse(newSessionId(frame, "New Session"))
              resp <- client.command(
                Json.obj(
                  "type" -> "userMessage".asJson,
                  "content" -> query.asJson,
                  "sessionId" -> sessionId.asJson
                )
              )
            yield if ctx.json then CliResult.Json(resp)
            else CliResult.text(s"Message sent to session $sessionId")

          end if

  end ChatSend

  private object ChatRepl extends CliSubcommand:
    def name = "repl"
    def description = "REPL mode (not implemented)"

    def params = List(
      CliParam("session", Some('s'), "Session ID to use", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      // REPL is not wired in this batch (it has never been reachable, so its
      // interactive path has no runtime evidence) — the previous message
      // ("REPL mode should be invoked as 'nebflow chat' without subcommands")
      // was self-contradictory: bare `nebflow chat` did not enter REPL either.
      IO.pure(CliResult.Error("REPL mode is not implemented — use 'nebflow chat send \"<message>\"'"))

end ChatCommand

object AskCommand extends CliCommand:
  def name = "ask"
  def description = "Single question (no session)"
  def subcommands = List(AskRun)

  def examples = List("nebflow ask \"what is 2+2?\"")

  private object AskRun extends CliSubcommand:
    def name = "ask"
    def description = "Ask a question"

    def params = List(
      // A11: this list used to omit the very positional the body consumes as
      // the question, which is why the router could not tell that `ask "q"`
      // was missing its required `session`. Declaration order is the slot
      // order (question first: that is what the body reads from position 0).
      CliParam("question", None, "Question text", required = true),
      CliParam("session", Some('s'), "Session ID (required for ask)", required = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val question = ctx.positionalArgs.headOption.getOrElse("")
          val sessionId = ctx.args.get("session").getOrElse("")
          if question.isEmpty then IO.pure(CliResult.Error("No question provided"))
          else if sessionId.isEmpty then IO.pure(CliResult.Error("Session ID required (--session)"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "ask".asJson,
                  "question" -> question.asJson,
                  "sessionId" -> sessionId.asJson
                )
              )
              .map(resp =>
                // C2: `ask` had no text mode — it always printed the raw JSON.
                if ctx.json then CliResult.Json(resp)
                else CliResult.text(s"Question sent to session $sessionId")
              )

  end AskRun

end AskCommand

object InterruptCommand extends CliCommand:
  def name = "interrupt"
  def description = "Interrupt current response"
  def subcommands = List(InterruptRun)

  def examples = List("nebflow interrupt --session abc-123")

  private object InterruptRun extends CliSubcommand:
    def name = "run"
    def description = "Interrupt"

    def params = List(
      CliParam("session", Some('s'), "Session ID", required = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          // A3: the positional form (`interrupt s1`) was rejected — the body
          // only read the named `--session`.
          val sessionId = ctx.args.get("session").orElse(ctx.positionalArgs.headOption).getOrElse("")
          if sessionId.isEmpty then IO.pure(CliResult.Error("Session ID required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "interrupt".asJson,
                  "sessionId" -> sessionId.asJson
                )
              )
              .as(CliResult.ok)
  end InterruptRun
end InterruptCommand
