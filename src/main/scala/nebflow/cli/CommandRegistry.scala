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
      HelpCommand,
      UpdateCommand,
      UninstallCommand,
      AutoStartCommand,
      // Gateway commands
      ChatCommand,
      RunCommand,
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
      PluginCommand,
      MemoryCommand,
      // CLI 命令补全批（chain-clicomplete，2026-09-20）：**append-only 新增两行**。
      // 既有条目一字未动、未重排、未整表重格式化 —— login 段同写本件，只增不改
      // 是避免同件冲突的唯一形态。
      HealthCommand,
      LogsCommand
    ).map(c => c.name -> c).toMap

  def get(name: String): Option[CliCommand] = commands.get(name)
  def all: List[CliCommand] = commands.values.toList.sortBy(_.name)
end CommandRegistry
