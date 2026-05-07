package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.agent.{AgentInfo, AgentLibrary}
import nebflow.core.Repl
import nebflow.shared.Defaults

class AgentService(library: AgentLibrary):

  def listAgents: IO[List[AgentInfo]] =
    library
      .loadAll()
      .map(
        _.values.toList
          .map { d =>
            AgentInfo(d.name, d.description, d.tools, d.subagents.map(_.name), d.displayName, d.avatar)
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
          else ""
        val resolvedJson = replaceDefaultContextWindow(rawJson)
        // For the "Nebula" builtin, load the actual system prompt from resources
        val systemMd =
          if defn.systemPrompt.nonEmpty then defn.systemPrompt
          else if defn.name == "Nebula" then Repl.loadSystemPrompt()
          else ""
        AgentConfig(defn.name, resolvedJson, systemMd)
      })

  def createAgent(name: String, configJson: String, systemMd: String): IO[Either[String, Unit]] =
    validateName(name) match
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        IO.blocking {
          val dir = AgentLibrary.defaultDir / name
          os.makeDir.all(dir)
          val resolved = replaceDefaultContextWindow(configJson)
          os.write.over(dir / "agent.json", resolved)
          os.write.over(dir / "system.md", systemMd)
        }.attempt
          .map(_.leftMap(_.getMessage))

  def updateAgent(name: String, configJson: String, systemMd: String): IO[Either[String, Unit]] =
    validateName(name) match
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        IO.blocking {
          val dir = AgentLibrary.defaultDir / name
          if !os.exists(dir) then throw new RuntimeException(s"Agent not found: $name")
          val resolved = replaceDefaultContextWindow(configJson)
          os.write.over(dir / "agent.json", resolved)
          os.write.over(dir / "system.md", systemMd)
        }.attempt
          .map(_.leftMap(_.getMessage))

  /** Replace hardcoded default contextWindow in agent JSON with the resolved value from nebflow.json. */
  private def replaceDefaultContextWindow(jsonStr: String): String =
    parse(jsonStr) match
      case Left(_) => jsonStr
      case Right(json) =>
        val cw = json.hcursor.downField("contextWindow").as[Int].getOrElse(Defaults.ContextWindow)
        if cw == Defaults.ContextWindow then
          val resolved = library.resolvedContextWindow
          json.deepMerge(io.circe.Json.obj("contextWindow" -> resolved.asJson)).noSpaces
        else jsonStr

  private val AgentNameRegex = "[a-zA-Z0-9_-]+".r

  private def validateName(name: String): Either[String, Unit] =
    if name.nonEmpty && AgentNameRegex.matches(name) then Right(())
    else Left(s"Invalid agent name: $name")
end AgentService

case class AgentConfig(name: String, configJson: String, systemMd: String)
