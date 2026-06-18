package nebflow.cli
import nebflow.core.PathUtil

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

/**
 * Main CLI router. Parses arguments, discovers the command, and dispatches.
 *
 * Usage:
 *   nebflow                          → show help
 *   nebflow start                    → start Gateway
 *   nebflow <command> [sub] [args]   → dispatch to CliCommand
 */
object CliRouter:

  def run(rawArgs: List[String]): IO[ExitCode] =
    // Parse global flags
    val (globalFlags, cmdArgs) = rawArgs.partition(a => a == "--json" || a == "--quiet")
    val jsonMode = globalFlags.contains("--json")
    val quietMode = globalFlags.contains("--quiet")

    cmdArgs match
      case Nil =>
        printHelp(jsonMode).as(ExitCode.Success)
      case "help" :: Nil =>
        printHelp(jsonMode).as(ExitCode.Success)
      case "help" :: cmdName :: Nil =>
        CommandRegistry.get(cmdName) match
          case Some(cmd) => printCommandHelp(cmd, jsonMode).as(ExitCode.Success)
          case None => IO.println(s"Unknown command: $cmdName").as(ExitCode.Error)
      case cmdName :: rest =>
        CommandRegistry.get(cmdName) match
          case None =>
            IO.println(s"Unknown command: $cmdName").as(ExitCode.Error)
          case Some(cmd) =>
            dispatchCommand(cmd, rest, jsonMode, quietMode)
    end match

  end run

  private def dispatchCommand(
    cmd: CliCommand,
    args: List[String],
    jsonMode: Boolean,
    quietMode: Boolean
  ): IO[ExitCode] =
    args match
      case Nil =>
        // No subcommand — if single subcommand, use it as default; otherwise show help
        cmd.subcommands match
          case single :: Nil =>
            executeSubcommand(cmd, single, Nil, jsonMode, quietMode)
          case _ =>
            showCommandHelp(cmd, jsonMode)
      case subName :: rest =>
        cmd.subcommands.find(_.name == subName) match
          case None =>
            // Check for --help
            if subName == "--help" || subName == "-h" then printCommandHelp(cmd, jsonMode).as(ExitCode.Success)
            else if subName.startsWith("-") then IO.println(s"Unknown option: $subName").as(ExitCode.Error)
            else
              // Treat as default subcommand with positional args (e.g. "nebflow chat query")
              cmd.subcommands.headOption match
                case Some(defaultSub) =>
                  executeSubcommand(cmd, defaultSub, args, jsonMode, quietMode)
                case None =>
                  IO.println(s"Unknown subcommand: $subName").as(ExitCode.Error)
          case Some(sub) =>
            executeSubcommand(cmd, sub, rest, jsonMode, quietMode)

  private def showCommandHelp(cmd: CliCommand, jsonMode: Boolean): IO[ExitCode] =
    if jsonMode then IO.println(Json.obj("error" -> "Missing subcommand".asJson).spaces2: String).as(ExitCode.Error)
    else
      IO.println(s"Command: ${cmd.name}") *>
        IO.println(s"Description: ${cmd.description}") *>
        IO.println(s"Subcommands:") *> cmd.subcommands
          .traverse_ { sc =>
            IO.println(s"  ${sc.name.padTo(16, ' ')}${sc.description}")
          }
          .as(ExitCode.Error)

  private def executeSubcommand(
    cmd: CliCommand,
    sub: CliSubcommand,
    args: List[String],
    jsonMode: Boolean,
    quietMode: Boolean
  ): IO[ExitCode] =
    // Parse subcommand args into named + positional
    val (named, positional) = parseArgs(args, sub.params)
    val isOffline = cmd.name == "version" || cmd.name == "update" ||
      cmd.name == "doctor" || cmd.name == "uninstall" ||
      cmd.name == "start" || cmd.name == "stop" || cmd.name == "status"

    val ctxIO: IO[CliContext] =
      if isOffline then IO.pure(CliContext(named, positional, jsonMode, quietMode, None, PathUtil.dataRoot))
      else
        GatewayClient.create.map {
          case Some(client) => CliContext(named, positional, jsonMode, quietMode, Some(client), PathUtil.dataRoot)
          case None => CliContext(named, positional, jsonMode, quietMode, None, PathUtil.dataRoot)
        }

    ctxIO.flatMap { ctx =>
      if !isOffline && ctx.client.isEmpty then
        if jsonMode then
          IO.println(Json.obj("error" -> "Gateway not running. Start with 'nebflow start'".asJson).spaces2: String)
            .as(ExitCode.Error)
        else IO.println("Gateway not running. Start with 'nebflow start'").as(ExitCode.Error)
      else
        // Validate required params
        val missing = sub.params.filter(_.required).filterNot { p =>
          named.contains(p.name) || named.contains(p.short.map(_.toString).getOrElse("")) || positional.nonEmpty
        }
        if missing.nonEmpty && positional.isEmpty then
          if jsonMode then
            IO.println(
              Json
                .obj("error" -> s"Missing required params: ${missing.map(_.name).mkString(", ")}".asJson)
                .spaces2: String
            ).as(ExitCode.Error)
          else
            IO.println(s"Missing required params: ${missing.map(_.name).mkString(", ")}")
              .as(ExitCode.Error)
        else
          sub.run(ctx).map(renderResult(_, jsonMode, quietMode)).handleErrorWith { e =>
            val msg = Option(e.getMessage).getOrElse("Unknown error")
            if jsonMode then IO.println(Json.obj("error" -> msg.asJson).spaces2: String).as(ExitCode.Error)
            else IO.println(s"Error: $msg").as(ExitCode.Error)
          }
        end if
    }

  end executeSubcommand

  private def renderResult(result: CliResult, jsonMode: Boolean, quietMode: Boolean): ExitCode =
    result match
      case CliResult.Text(lines) =>
        if !quietMode then lines.foreach(println)
        ExitCode.Success
      case CliResult.Json(json) =>
        if jsonMode then println(json.spaces2)
        else println(json.spaces2) // always pretty print json results
        ExitCode.Success
      case CliResult.Error(msg, code) =>
        if jsonMode then println(Json.obj("error" -> msg.asJson).spaces2)
        else println(s"Error: $msg")
        ExitCode(code)
      case CliResult.Success =>
        ExitCode.Success

  // ===== Arg parsing =====

  /** Parse a flat list of args into (namedParams, positionalArgs) */
  private def parseArgs(args: List[String], params: List[CliParam]): (Map[String, String], List[String]) =
    val named = scala.collection.mutable.Map.empty[String, String]
    val positional = scala.collection.mutable.ListBuffer.empty[String]
    val paramNames = params.map(p => p.name -> p.short.map(_.toString)).toMap
    val shortMap = params.filter(_.short.isDefined).map(p => p.short.get.toString -> p.name).toMap

    var i = 0
    while i < args.length do
      val arg = args(i)
      if arg.startsWith("--") then
        val name = arg.stripPrefix("--")
        val paramName = name
        if i + 1 < args.length && !args(i + 1).startsWith("-") then
          named(paramName) = args(i + 1)
          i += 2
        else
          named(paramName) = "true"
          i += 1
      else if arg.startsWith("-") && arg.length == 2 then
        val shortKey = arg.substring(1)
        shortMap.get(shortKey) match
          case Some(longName) =>
            if i + 1 < args.length && !args(i + 1).startsWith("-") then
              named(longName) = args(i + 1)
              i += 2
            else
              named(longName) = "true"
              i += 1
          case None =>
            positional += arg
            i += 1
      else
        positional += arg
        i += 1
      end if
    end while
    (named.toMap, positional.toList)
  end parseArgs

  // ===== Help =====

  private def printHelp(jsonMode: Boolean): IO[Unit] =
    if jsonMode then
      val cmds = CommandRegistry.all.map { c =>
        io.circe.Json.obj("name" -> c.name.asJson, "description" -> c.description.asJson)
      }
      IO.println(io.circe.Json.obj("commands" -> cmds.asJson).spaces2: String)
    else
      IO.println(s"nebflow v${nebflow.Version.string}") *>
        IO.println("") *>
        IO.println("Usage: nebflow <command> [subcommand] [options]") *>
        IO.println("") *>
        IO.println("System commands (offline):") *> {
          CommandRegistry.all.filter(isOfflineCmd).traverse_ { c =>
            IO.println(s"  ${c.name.padTo(16, ' ')}${c.description}")
          }
        } *> IO.println("") *> IO.println("Gateway commands:") *> {
          CommandRegistry.all.filterNot(isOfflineCmd).traverse_ { c =>
            IO.println(s"  ${c.name.padTo(16, ' ')}${c.description}")
          }
        } *> IO.println("") *>
        IO.println("Global flags: --json, --quiet") *>
        IO.println("Use 'nebflow <command> --help' for command details")

  private def printCommandHelp(cmd: CliCommand, jsonMode: Boolean): IO[Unit] =
    if jsonMode then
      val subs = cmd.subcommands.map { sc =>
        io.circe.Json.obj("name" -> sc.name.asJson, "description" -> sc.description.asJson)
      }
      IO.println(io.circe.Json.obj("command" -> cmd.name.asJson, "subcommands" -> subs.asJson).spaces2: String)
    else
      IO.println(s"${cmd.name} — ${cmd.description}") *>
        IO.println("") *> {
          cmd.subcommands.traverse_ { sc =>
            IO.println(s"  ${cmd.name} ${sc.name.padTo(16, ' ')}${sc.description}")
          }
        } *> {
          if cmd.examples.nonEmpty then
            IO.println("") *> IO.println("Examples:") *> cmd.examples.traverse_(e => IO.println(s"  $e"))
          else IO.unit
        }

  private def isOfflineCmd(cmd: CliCommand): Boolean =
    Set("version", "start", "stop", "status", "update", "doctor", "uninstall").contains(cmd.name)

end CliRouter
