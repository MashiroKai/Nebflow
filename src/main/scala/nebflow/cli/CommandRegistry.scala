package nebflow.cli

/**
 * Auto-discovery registry for all CLI commands.
 * Add new CliCommand implementations here.
 */
object CommandRegistry:
  private lazy val commands: Map[String, CliCommand] =
    List[CliCommand](
      // System commands (offline)
      VersionCommand,
      StartCommand,
      StopCommand,
      StatusCommand,
      DoctorCommand,
      // Gateway commands
      ChatCommand,
      AskCommand,
      InterruptCommand,
      SessionCommand,
      FolderCommand,
      ModelCommand,
      ThinkingCommand,
      ConfigCommand,
      ProviderCommand,
      McpCommand,
      AgentCommand,
      SkillCommand,
      FeishuCommand,
      MemoryCommand,
    ).map(c => c.name -> c).toMap

  def get(name: String): Option[CliCommand] = commands.get(name)
  def all: List[CliCommand] = commands.values.toList.sortBy(_.name)
