package nebscala

import nebscala.cli.{Args, CliMode, MarkdownRenderer, NebscalaUI, Splash, UiStore}
import nebscala.core.{AskUser, Repl, ReplUi, UserAbort}
import nebscala.core.mcp.{McpClient, StdioTransport, HttpTransport, createMcpToolWrapper}
import nebscala.core.tools.ToolRegistry
import nebscala.llm.{Config, LlmInterface, NebscalaServiceConfig}
import nebscala.shared.{Message, MessageRole, TerminalUtils}
import nebscala.shared.given
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*

object Main extends IOApp:
  private val SESSION_PATH = os.home / ".nebscala" / "sessions" / "last.json"

  private def loadSession(): IO[List[Message]] = IO.blocking {
    if os.exists(SESSION_PATH) then
      decode[List[Message]](os.read(SESSION_PATH)) match
        case Right(msgs) => msgs
        case Left(_) => Nil
    else Nil
  }

  private def saveSession(messages: List[Message]): IO[Unit] = IO.blocking {
    os.write.over(SESSION_PATH, messages.asJson.spaces2, createFolders = true)
  }

  private def initMcpServers(config: NebscalaServiceConfig): IO[List[Unit]] =
    config.mcpServers match
      case Some(servers) =>
        servers.toList.traverse { case (serverId, serverConfig) =>
          val transport = (serverConfig.command, serverConfig.url) match
            case (Some(cmd), _) =>
              new StdioTransport(cmd, serverConfig.args.getOrElse(Nil), serverConfig.env.getOrElse(Map.empty))
            case (_, Some(url)) =>
              new HttpTransport(url, serverConfig.headers.getOrElse(Map.empty))
            case _ =>
              throw new RuntimeException(s"MCP server $serverId must have either command or url")

          val client = new McpClient(transport)
          client.initialize() *>
            client.listTools().flatMap { tools =>
              val wrapped = tools.map(t => createMcpToolWrapper(serverId, t, client))
              IO.delay(ToolRegistry.registerTools(wrapped))
            }
        }.handleErrorWith { e =>
          IO.println(s"[MCP] Warning: ${e.getMessage}") *> IO.pure(Nil)
        }
      case None => IO.pure(Nil)

  def run(args: List[String]): IO[ExitCode] =
    val parsed = Args.parse(args.toArray)
    parsed match
      case None =>
        IO.println("Usage: nebscala [-c] [query]") *> IO.pure(ExitCode.Error)
      case Some(cliArgs) =>
        val configPath = Config.DefaultConfigPath.toString
        if !os.exists(Config.DefaultConfigPath) then
          IO.println(s"Config file not found: $configPath") *>
            IO.println("Create ~/.nebscala/nebscala.json with your LLM provider settings.") *>
            IO.pure(ExitCode.Error)
        else
          runWithArgs(cliArgs)

  private def runWithArgs(args: Args): IO[ExitCode] =
    val config = Config.loadServiceConfig()
    val llm = LlmInterface.createLlm()
    val modelRef = config.llm.model.primary

    NebscalaUI.create.flatMap { ui =>
      ui.getClass.getDeclaredFields.find(_.getName == "store") match
        case Some(field) =>
          IO.delay {
            field.setAccessible(true)
            field.get(ui).asInstanceOf[UiStore]
          }
        case None =>
          IO.raiseError(new RuntimeException("Could not access UI store"))
    }.handleErrorWith { _ =>
      UiStore.create
    }.flatMap { store =>
      // Register AskUser handler
      AskUser.setHandler(store.askUser)

      // Initialize MCP servers
      initMcpServers(config).flatMap { _ =>
        args.mode match
          case CliMode.SingleShot =>
            // Single-shot mode
            store.addUserInput(args.input) *>
              Repl.runRepl(args.input, llm, System.getProperty("user.dir"), Nil, store).flatMap { messages =>
                store.getState.flatMap { s =>
                  // Print the LLM response
                  s.completedRounds.lastOption match
                    case Some(round) if round.kind == "llm" =>
                      IO.println(round.content)
                    case _ => IO.unit
                } *>
                saveSession(messages) *>
                IO.pure(ExitCode.Success)
              }.handleError { e =>
                println(s"Error: ${e.getMessage}")
                ExitCode.Error
              }

          case CliMode.ContinueSession =>
            loadSession().flatMap { history =>
              val msg = if history.isEmpty then
                IO.println("No previous session found. Starting new session.")
              else
                IO.println(s"Resumed session (${history.length} messages)")
              msg *> runInteractive(llm, modelRef, store, history)
            }

          case CliMode.Interactive =>
            runInteractive(llm, modelRef, store, Nil)
      }
    }

  private def runInteractive(llm: nebscala.shared.LlmHandle[IO], modelRef: String, store: UiStore, history: List[Message]): IO[ExitCode] =
    Splash.lines.foreach(println)
    println(s"  model: $modelRef")
    println()

    @volatile var sessionMessages = history

    def loop: IO[ExitCode] =
      val prompt = s"${TerminalUtils.Cyan}>${TerminalUtils.Reset} "
      IO.blocking {
        scala.io.StdIn.readLine(prompt)
      }.flatMap {
        case null => IO.pure(ExitCode.Success)
        case input =>
          val trimmed = input.trim
          if trimmed == "quit" || trimmed == "exit" then
            saveSession(sessionMessages) *>
              IO.println(s"Session saved (${sessionMessages.length} messages). Use nebscala -c to resume.") *>
              IO.pure(ExitCode.Success)
          else if trimmed.nonEmpty then
            val displayText = Repl.replaceMediaPaths(trimmed)
            store.addUserInput(displayText) *>
              IO.println(s"${TerminalUtils.Dim}▸ You:${TerminalUtils.Reset} $displayText") *>
              Repl.runRepl(trimmed, llm, System.getProperty("user.dir"), sessionMessages, store, Some { msgs =>
                sessionMessages = msgs
                saveSession(msgs)
              }).flatMap { messages =>
                sessionMessages = messages
                saveSession(messages) *>
                store.getState.flatMap { s =>
                  IO.blocking {
                    s.completedRounds.drop(sessionMessages.length / 2).foreach { round =>
                      round.kind match
                        case "llm" =>
                          println(MarkdownRenderer.render(round.content))
                        case "tool-success" =>
                          println(s"${TerminalUtils.Green}✓${TerminalUtils.Reset} ${round.content}")
                        case "tool-error" =>
                          println(s"${TerminalUtils.Red}✗${TerminalUtils.Reset} ${round.content}")
                        case _ =>
                    }
                  }
                } *> loop
              }.handleErrorWith {
                case _: UserAbort =>
                  IO.println(s"${TerminalUtils.Yellow}Interrupted.${TerminalUtils.Reset}") *> loop
                case e =>
                  IO.println(s"${TerminalUtils.Red}Error: ${e.getMessage}${TerminalUtils.Reset}") *> loop
              }
          else loop
      }

    loop.guarantee {
      store.getState.flatMap { state =>
        UiStore.saveHistory(state.inputHistory).handleError(_ => ())
      }
    }
