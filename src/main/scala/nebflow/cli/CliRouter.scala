package nebflow.cli

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.PathUtil

/**
 * Main CLI router. Parses arguments, discovers the command, and dispatches.
 *
 * Usage:
 *   nebflow                          → start the Gateway
 *   nebflow start                    → start Gateway (same branch)
 *   nebflow <command> [sub] [args]   → dispatch to CliCommand
 */
object CliRouter:

  def run(rawArgs: List[String]): IO[ExitCode] =
    quietLogbackStatus()
    // Parse global flags
    val (globalFlags, cmdArgs) = rawArgs.partition(a => a == "--json" || a == "--quiet")
    val jsonMode = globalFlags.contains("--json")
    val quietMode = globalFlags.contains("--quiet")

    cmdArgs match
      case Nil =>
        printHelp(jsonMode).as(ExitCode.Success)
      case h :: _ if h == "--help" || h == "-h" =>
        // A7: top-level help was unreachable — `nebflow --help` answered
        // "Unknown command: --help" with exit 1.
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

  /**
   * A13/V18: logback prints its own configuration-status dump to STDOUT while
   * it initialises (44 `|-INFO in ch.qos.logback…` lines, triggered by the
   * duplicate `logback.xml` on the classpath). That dump lands ahead of every
   * machine-parsed response (`plugin add`, `skill audit`, `autostart status`).
   * `logback.statusListenerClass` is read by logback at initialisation and no
   * logger has been used yet on the CLI path, so setting it here is early
   * enough to suppress the dump. Application logs are unaffected — the FILE
   * appender still receives them.
   */
  private def quietLogbackStatus(): Unit =
    if sys.props.get("logback.statusListenerClass").isEmpty then
      sys.props("logback.statusListenerClass") = "ch.qos.logback.core.status.NopStatusListener"

  private def dispatchCommand(
    cmd: CliCommand,
    args: List[String],
    jsonMode: Boolean,
    quietMode: Boolean
  ): IO[ExitCode] =
    args match
      case Nil =>
        // No subcommand — if single subcommand, use it as default; otherwise
        // report the missing subcommand (V16: `nebflow chat` used to print the
        // command help and exit 1, which is not a usable behaviour).
        cmd.subcommands match
          case single :: Nil =>
            executeSubcommand(cmd, single, Nil, jsonMode, quietMode)
          case subs =>
            if jsonMode then
              IO.println(
                Json
                  .obj(
                    "error" -> s"'${cmd.name}' needs a subcommand".asJson,
                    "subcommands" -> subs.map(_.name).asJson
                  )
                  .spaces2: String
              ).as(ExitCode.Error)
            else
              IO.println(s"Error: '${cmd.name}' needs a subcommand — one of: ${subs.map(_.name).mkString(", ")}")
                .as(ExitCode.Error)
      case subName :: rest =>
        cmd.subcommands.find(_.name == subName) match
          case Some(sub) =>
            // `--help` / `-h` anywhere in a subcommand's args shows that
            // command's help (and is never parsed as a parameter).
            if rest.exists(a => a == "--help" || a == "-h") then printCommandHelp(cmd, jsonMode).as(ExitCode.Success)
            else executeSubcommand(cmd, sub, rest, jsonMode, quietMode)
          case None =>
            if subName == "--help" || subName == "-h" then printCommandHelp(cmd, jsonMode).as(ExitCode.Success)
            else if acceptsLeadingPositional(cmd, subName) then
              // The default subcommand takes this token as data: a leading
              // flag (`nebflow run -p "task"`) or free text
              // (`nebflow chat "hi"` — ChatCommands.scala:14).
              executeSubcommand(cmd, cmd.subcommands.head, args, jsonMode, quietMode)
            else
              // A12: an unrecognised subcommand used to silently fall back to
              // the first subcommand (`session frobnicate` ran `session list`).
              IO.println(s"Unknown subcommand: $subName").as(ExitCode.Error)

  /**
   * Commands whose declared examples use a bare positional as data rather
   * than a subcommand name (`chat`: "nebflow chat \"…\"" — ChatCommands.scala:14).
   * Every other multi-subcommand command treats an unrecognised first token
   * as an unknown subcommand (A12).
   */
  private val PositionalFormCommands: Set[String] = Set("chat")

  private def acceptsLeadingPositional(cmd: CliCommand, token: String): Boolean =
    token.startsWith("-") ||
      cmd.subcommands.sizeIs == 1 ||
      PositionalFormCommands.contains(cmd.name)

  private def executeSubcommand(
    cmd: CliCommand,
    sub: CliSubcommand,
    args: List[String],
    jsonMode: Boolean,
    quietMode: Boolean
  ): IO[ExitCode] =
    // Parse subcommand args into named + positional
    parseArgs(args, sub.params) match
      case Left(err) =>
        // A12: an unknown flag used to be swallowed into a named parameter
        // (`--frobnicate 1` became args("frobnicate") = "1") and ignored.
        IO.println(err).as(ExitCode.Error)
      case Right((named, positional)) =>
        val isOffline = CliRouter.isOffline(cmd.name, sub.name)

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
            // Validate required params (A11: the check no longer exempts every
            // required param as soon as any positional is present).
            val missing = missingRequired(sub, named, positional)
            if missing.nonEmpty then
              if jsonMode then
                IO.println(
                  Json
                    .obj("error" -> s"Missing required params: ${missing.mkString(", ")}".asJson)
                    .spaces2: String
                ).as(ExitCode.Error)
              else
                IO.println(s"Missing required params: ${missing.mkString(", ")}")
                  .as(ExitCode.Error)
            else
              sub.run(ctx).map(renderResult(_, jsonMode, quietMode)).handleErrorWith { e =>
                val msg = Option(e.getMessage).getOrElse("Unknown error")
                if jsonMode then IO.println(Json.obj("error" -> msg.asJson).spaces2: String).as(ExitCode.Error)
                else IO.println(s"Error: $msg").as(ExitCode.Error)
              }
            end if
        }
    end match

  end executeSubcommand

  private def renderResult(result: CliResult, jsonMode: Boolean, quietMode: Boolean): ExitCode =
    result match
      case CliResult.Text(lines) =>
        if !quietMode then lines.foreach(println)
        ExitCode.Success
      case CliResult.Json(json) =>
        // C6: `--quiet` silences machine output too (it used to affect only
        // Text, so `--quiet --json` still printed the whole payload).
        if !quietMode then println(json.spaces2)
        ExitCode.Success
      case CliResult.Error(msg, code) =>
        // Errors stay visible under --quiet: silence must never hide a failure.
        if jsonMode then println(Json.obj("error" -> msg.asJson).spaces2)
        else println(s"Error: $msg")
        ExitCode(code)
      case CliResult.Exit(code, output) =>
        if output.nonEmpty && !quietMode then println(output)
        ExitCode(code)
      case CliResult.Success =>
        ExitCode.Success

  // ===== Arg parsing =====

  /**
   * Parse a flat list of args into (namedParams, positionalArgs).
   *
   * A12: returns Left on an unknown flag instead of silently storing it as a
   * named parameter. Long flags (`--foo`) are always checked; single-dash
   * short flags are checked only when alphabetic, so negative values and
   * `-1`-style data stay positional.
   */
  private[cli] def parseArgs(
    args: List[String],
    params: List[CliParam]
  ): Either[String, (Map[String, String], List[String])] =
    val named = scala.collection.mutable.Map.empty[String, String]
    val positional = scala.collection.mutable.ListBuffer.empty[String]
    val paramNames = params.map(_.name).toSet
    val shortMap = params.filter(_.short.isDefined).map(p => p.short.get.toString -> p.name).toMap
    var unknown: Option[String] = None

    var i = 0
    while i < args.length && unknown.isEmpty do
      val arg = args(i)
      if arg.startsWith("--") then
        val name = arg.stripPrefix("--")
        if !paramNames.contains(name) then unknown = Some(arg)
        else if i + 1 < args.length && !args(i + 1).startsWith("-") then
          named(name) = args(i + 1)
          i += 2
        else
          named(name) = "true"
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
            if shortKey.forall(_.isLetter) then unknown = Some(arg)
            else
              positional += arg
              i += 1
      else
        positional += arg
        i += 1
      end if
    end while
    unknown match
      case Some(flag) => Left(s"Unknown flag: $flag")
      case None => Right((named.toMap, positional.toList))
  end parseArgs

  /**
   * Required params satisfied by a NAMED value or by a positional slot.
   *
   * A11: the old check was `missing.nonEmpty && positional.isEmpty`, so the
   * presence of ANY positional exempted every required param — `ask "q"` never
   * reported the missing `session`. Slots are assigned required-params-first,
   * in declaration order, then to the remaining value params: that is the
   * order the command bodies consume positionals in (`config set KEY VALUE`,
   * `interrupt SESSION`, `memory set CONTENT`).
   */
  private[cli] def missingRequired(
    sub: CliSubcommand,
    named: Map[String, String],
    positional: List[String]
  ): List[String] =
    val valueParams = sub.params.filterNot(_.isFlag)
    val slotOrder = valueParams.filter(_.required) ++ valueParams.filterNot(_.required)
    def satisfied(p: CliParam): Boolean =
      named.contains(p.name) ||
        p.short.exists(s => named.contains(s.toString)) || {
          val idx = slotOrder.indexOf(p); idx >= 0 && idx < positional.size
        }
    sub.params.filter(_.required).filterNot(satisfied).map(_.name)
  end missingRequired

  /**
   * Single source of "this invocation needs no gateway" (A18 — the set used to
   * be written out twice, at both the dispatch and the help site, and lacked
   * `help`/`skill audit`). Keyed by (command, subcommand) because `skill` is
   * mixed: `audit` is a local file scan (A14) while `list`/`run` are gateway
   * calls.
   */
  private val OfflineCommands: Set[String] =
    Set(
      "version",
      "start",
      "stop",
      "status",
      "update",
      "doctor",
      "uninstall",
      "autostart",
      "help",
      // CLI 命令补全批（2026-09-20）：本机读数 / 本机取数 —— 网关不在时恰恰是它们
      // 最该可用的时候（health 的网关面与 logs 的取数面都自己探，不靠 ctx.client）。
      "health",
      "logs"
    )

  private val OfflineSubcommands: Set[(String, String)] = Set(
    ("skill", "audit"),
    // 只读、纯本机，不经网关：配置校验尤其必须在「网关起不来」时可用，否则
    // 配置坏掉的场景下校验器本身就不可达。沿用 T10 的同一分类机制（见
    // isOfflineCmd 注释），不新造第二套「可离线子命令」判据。
    ("config", "path"),
    ("config", "validate")
  )

  private[cli] def isOffline(cmdName: String, subName: String): Boolean =
    OfflineCommands.contains(cmdName) || OfflineSubcommands.contains((cmdName, subName))

  /**
   * Help grouping is per command: a command is listed on the offline face when
   * any of its subcommands is offline (T10 — `skill audit` belongs there).
   */
  private def isOfflineCmd(cmd: CliCommand): Boolean =
    OfflineCommands.contains(cmd.name) || cmd.subcommands.exists(sc => isOffline(cmd.name, sc.name))

  // ===== Help =====

  private def printHelp(jsonMode: Boolean): IO[Unit] =
    if jsonMode then
      val cmds = CommandRegistry.all.map { c =>
        io.circe.Json.obj("name" -> c.name.asJson, "description" -> c.description.asJson)
      }
      IO.println(io.circe.Json.obj("commands" -> cmds.asJson).spaces2: String)
    else
      IO.println(s"nebflow ${nebflow.Version.string}") *>
        IO.println("") *>
        IO.println("Usage: nebflow <command> [subcommand] [options]") *>
        IO.println("") *>
        IO.println("Starting / stopping:") *>
        IO.println(s"  ${"nebflow".padTo(16, ' ')}Start the Gateway (same as 'nebflow start')") *>
        IO.println(s"  ${"start".padTo(16, ' ')}Start the Gateway server") *>
        IO.println(s"  ${"stop".padTo(16, ' ')}Stop the running Gateway") *>
        IO.println("  -s, --server          (legacy) same as 'nebflow start'") *>
        IO.println("") *>
        IO.println("System commands (offline):") *> {
          CommandRegistry.all
            .filter(c => isOfflineCmd(c) && c.name != "start" && c.name != "stop")
            .traverse_ { c =>
              IO.println(s"  ${c.name.padTo(16, ' ')}${c.description}")
            }
        } *> IO.println("") *> IO.println("Gateway commands:") *> {
          CommandRegistry.all.filterNot(isOfflineCmd).traverse_ { c =>
            IO.println(s"  ${c.name.padTo(16, ' ')}${c.description}")
          }
        } *> IO.println("") *>
        IO.println("Global flags: --json, --quiet, --home <dir>, --port <n>, --no-browser") *>
        IO.println("Exit codes: 0 ok · 1 error · 2 unreachable/timeout") *>
        IO.println("Use 'nebflow <command> --help' for command details")

  private def printCommandHelp(cmd: CliCommand, jsonMode: Boolean): IO[Unit] =
    if jsonMode then
      val subs = cmd.subcommands.map { sc =>
        io.circe.Json.obj(
          "name" -> sc.name.asJson,
          "description" -> sc.description.asJson,
          "parameters" -> sc.params.map { p =>
            io.circe.Json.obj(
              "name" -> p.name.asJson,
              "short" -> p.short.map(_.toString).asJson,
              "description" -> p.description.asJson,
              "required" -> p.required.asJson,
              "default" -> p.default.asJson,
              "flag" -> p.isFlag.asJson,
              "offline" -> isOffline(cmd.name, sc.name).asJson
            )
          }.asJson
        )
      }
      IO.println(io.circe.Json.obj("command" -> cmd.name.asJson, "subcommands" -> subs.asJson).spaces2: String)
    else
      IO.println(s"${cmd.name} — ${cmd.description}") *>
        IO.println("") *> {
          cmd.subcommands.traverse_ { sc =>
            val offlineMark =
              if isOffline(cmd.name, sc.name) && !OfflineCommands.contains(cmd.name) then s" (no gateway needed)"
              else ""
            IO.println(s"  ${cmd.name} ${sc.name.padTo(16, ' ')}${sc.description}$offlineMark")
          }
        } *> {
          val withParams = cmd.subcommands.filter(_.params.nonEmpty)
          if withParams.isEmpty then IO.unit
          else
            val single = cmd.subcommands.sizeIs == 1
            IO.println("") *>
              IO.println("Parameters:") *> withParams.traverse_ { sc =>
                val head = if single then IO.unit else IO.println(s"  ${sc.name}")
                head *> sc.params.traverse_ { p => IO.println(parameterLine(p)) }
              }
        } *> {
          if cmd.examples.nonEmpty then
            IO.println("") *> IO.println("Examples:") *> cmd.examples.traverse_(e => IO.println(s"  $e"))
          else IO.unit
        }

  /**
   * T5/T6 parameter line: `--session, -s   Session ID`, plus `(required)` for
   * a missing mandatory param and `(default: X)` when one is declared.
   */
  private[cli] def parameterLine(p: CliParam): String =
    val flag = "--" + p.name + p.short.fold("")(s => s", -$s")
    val annotations =
      (if p.required then List("(required)") else Nil) ++
        p.default.map(d => s"(default: $d)").toList
    s"    ${flag.padTo(20, ' ')}${(p.description :: annotations).mkString(" ")}"

end CliRouter
