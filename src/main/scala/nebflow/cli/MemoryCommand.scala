package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

object MemoryCommand extends CliCommand:
  def name = "memory"
  def description = "Manage memory"
  def subcommands = List(MemoryGet, MemorySet)

  def examples = List(
    "nebflow memory get --scope user",
    "nebflow memory set --scope agent --content \"Use tabs for indentation\""
  )

  private object MemoryGet extends CliSubcommand:
    def name = "get"
    def description = "View memory"

    def params = List(
      CliParam("scope", Some('s'), "Scope: user/agent/folder", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val scope = ctx.args.getOrElse("scope", "agent")
          client
            .command(
              Json.obj(
                "type" -> "getMemory".asJson,
                "scope" -> scope.asJson
              )
            )
            .map { resp =>
              if ctx.json then CliResult.Json(resp)
              else
                val content = resp.hcursor.downField("content").as[String].getOrElse("")
                if content.isEmpty then CliResult.text(s"$scope memory: (empty)")
                else CliResult.text(s"$scope memory:", "", content)
            }

  end MemoryGet

  private object MemorySet extends CliSubcommand:
    def name = "set"
    def description = "Set memory content"

    def params = List(
      CliParam("scope", Some('s'), "Scope: user/agent/folder", required = false),
      CliParam("content", Some('c'), "Memory content", required = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val scope = ctx.args.getOrElse("scope", "agent")
          val content = ctx.args.getOrElse("content", ctx.positionalArgs.headOption.getOrElse(""))
          if content.isEmpty then IO.pure(CliResult.Error("Content required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "saveMemory".asJson,
                  "scope" -> scope.asJson,
                  "content" -> content.asJson
                )
              )
              .as(CliResult.text(s"$scope memory updated"))
  end MemorySet
end MemoryCommand
