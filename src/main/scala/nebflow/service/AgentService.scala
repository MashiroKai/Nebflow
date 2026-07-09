package nebflow.service

import cats.effect.IO
import nebflow.agent.{AgentInfo, AgentLibrary}

class AgentService(library: AgentLibrary):

  def listAgents: IO[List[AgentInfo]] =
    library
      .loadAll()
      .map(
        _.values.toList
          .map { d =>
            AgentInfo(d.name, d.description, d.tools, d.displayName, d.avatar)
          }
          .sortBy(_.name)
      )

  /** Read the system.md for an agent (for frontend prompt editor). */
  def getSystemPrompt(name: String): IO[Option[String]] =
    library.readSystemPrompt(name)

  /** Write system.md for an agent (user edits the prompt). */
  def updateSystemPrompt(name: String, content: String): IO[Unit] =
    library.updateSystemPrompt(name, content)

end AgentService
