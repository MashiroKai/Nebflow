package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import nebflow.agent.{AgentInfo, AgentLibrary}

class AgentService(library: AgentLibrary):

  def listAgents: IO[List[AgentInfo]] =
    library.loadAll().map(_.values.toList.map { d =>
      AgentInfo(d.name, d.description, d.tools, d.subagents.map(_.name))
    }.sortBy(_.name))

  def getAgentConfig(name: String): IO[Option[AgentConfig]] =
    library.get(name).map(_.map { defn =>
      val configJson =
        if defn.configPath.nonEmpty && os.exists(os.Path(defn.configPath) / "agent.json")
        then os.read(os.Path(defn.configPath) / "agent.json")
        else ""
      AgentConfig(defn.name, configJson, defn.systemPrompt)
    })

  def createAgent(name: String, configJson: String, systemMd: String): IO[Either[String, Unit]] =
    validateName(name) match
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        IO.blocking {
          val dir = AgentLibrary.defaultDir / name
          os.makeDir.all(dir)
          os.write.over(dir / "agent.json", configJson)
          os.write.over(dir / "system.md", systemMd)
        }.attempt.map(_.leftMap(_.getMessage))

  def updateAgent(name: String, configJson: String, systemMd: String): IO[Either[String, Unit]] =
    validateName(name) match
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        IO.blocking {
          val dir = AgentLibrary.defaultDir / name
          if !os.exists(dir) then throw new RuntimeException(s"Agent not found: $name")
          os.write.over(dir / "agent.json", configJson)
          os.write.over(dir / "system.md", systemMd)
        }.attempt.map(_.leftMap(_.getMessage))

  private val AgentNameRegex = "[a-zA-Z0-9_-]+".r

  private def validateName(name: String): Either[String, Unit] =
    if name.nonEmpty && AgentNameRegex.matches(name) then Right(())
    else Left(s"Invalid agent name: $name")
end AgentService

case class AgentConfig(name: String, configJson: String, systemMd: String)
