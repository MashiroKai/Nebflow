package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import nebflow.agent.{AgentDef, AgentInfo, AgentLibrary}
import nebflow.core.tools.ToolRegistry

class AgentService(library: AgentLibrary):

  def listAgents: IO[List[AgentInfo]] =
    library
      .loadAll()
      .map(
        _.values.toList
          .map { d =>
            AgentInfo(d.name, d.description, d.tools, d.displayName, d.avatar, d.mcpServers)
          }
          .sortBy(_.name)
      )

  def getAgentConfig(name: String): IO[Option[AgentConfig]] =
    library
      .get(name)
      .map(_.map { defn =>
        val rawJson =
          if defn.configPath.nonEmpty && os.exists(os.Path(defn.configPath) / "agent.json")
          then os.read(os.Path(defn.configPath) / "agent.json")
          else
            // Builtin agent — reconstruct JSON from the definition
            import io.circe.syntax.*
            import io.circe.Json
            val fields = List(
              Some("name" -> defn.name.asJson),
              Some("description" -> defn.description.asJson),
              if defn.tools.nonEmpty then Some("tools" -> defn.tools.asJson) else None,
              if defn.mcpServers.nonEmpty then Some("mcpServers" -> defn.mcpServers.asJson) else None,
              defn.displayName.map(v => "displayName" -> v.asJson),
              defn.avatar.map(v => "avatar" -> v.asJson)
            ).flatten
            Json.obj(fields*).noSpaces
        AgentConfig(defn.name, rawJson, defn.systemPrompt)
      })

  def createAgent(name: String, configJson: String, systemMd: String): IO[Either[String, Unit]] =
    val nameErr: Option[String] = validateName(name).left.toOption
    val toolErr: Option[String] = validateTools(configJson)
    val error = nameErr.orElse(toolErr)
    error match
      case Some(err) => IO.pure(Left(err))
      case None =>
        IO.blocking {
          val dir = AgentLibrary.defaultDir / name
          os.makeDir.all(dir)
          os.write.over(dir / "agent.json", configJson)
          os.write.over(dir / "system.md", systemMd)
        }.attempt
          .map(_.leftMap(_.getMessage))
          .flatTap {
            case Right(_) => library.refresh()
            case Left(_) => IO.unit
          }

  def updateAgent(name: String, configJson: String, systemMd: String): IO[Either[String, Unit]] =
    val toolResult = validateTools(configJson)
    toolResult match
      case Some(err) => IO.pure(Left(err))
      case None =>
        IO.blocking {
          val dir = AgentLibrary.defaultDir / name
          if !os.exists(dir) then throw new RuntimeException(s"Agent not found: $name")
          os.write.over(dir / "agent.json", configJson)
          os.write.over(dir / "system.md", systemMd)
        }.attempt
          .map(_.leftMap(_.getMessage))
          .flatTap {
            case Right(_) => library.refresh()
            case Left(_) => IO.unit
          }

  /** Validate tool names in agent JSON against ToolRegistry. */
  private def validateTools(configJson: String): Option[String] =
    decode[AgentDef](configJson) match
      case Left(err) => Some(s"Invalid JSON: ${err.getMessage}")
      case Right(defn) =>
        if defn.tools == List("*") then None // wildcard — allowed for all agents
        else
          val known = ToolRegistry.TOOL_MAP.keySet
          val unknown = defn.tools.filterNot(known.contains)
          if unknown.isEmpty then None
          else Some(s"Unknown tools: ${unknown.mkString(", ")}")

  private val AgentNameRegex = "[a-zA-Z0-9_-]+".r

  private def validateName(name: String): Either[String, Unit] =
    if name.nonEmpty && AgentNameRegex.matches(name) then Right(())
    else Left(s"Invalid agent name: $name")
end AgentService

case class AgentConfig(name: String, configJson: String, systemMd: String)
