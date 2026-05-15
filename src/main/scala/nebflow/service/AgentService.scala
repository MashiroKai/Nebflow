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
        val dir = os.Path(defn.configPath)
        val rawJson = os.read(dir / "agent.json")
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
          os.makeDir.all(dir)
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
