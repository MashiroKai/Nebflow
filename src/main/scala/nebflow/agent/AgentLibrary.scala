package nebflow.agent

import cats.effect.IO
import nebflow.core.NebflowLogger

/** Loads agent definitions from ~/.nebflow/agents/ directory.
  * Directory structure:
  *   ~/.nebflow/agents/
  *     arch/
  *       agent.yaml
  *       system.md
  *     tina/
  *       agent.yaml
  *       system.md
  */
class AgentLibrary(agentsDir: os.Path):
  private val logger = NebflowLogger.forName("nebflow.agent.library")

  def loadAll(): IO[Map[String, AgentDef]] =
    IO.blocking {
      if !os.exists(agentsDir) then Map.empty
      else
        os.list(agentsDir).filter(os.isDir).flatMap { dir =>
          val yamlPath = dir / "agent.yaml"
          if os.exists(yamlPath) then
            val yaml = os.read(yamlPath)
            val defn = AgentDef.parseYaml(yaml, dir)
            Some(defn.name -> defn)
          else None
        }.toMap
    }.flatTap { defs =>
      IO(logger.info(s"Loaded ${defs.size} agent definitions from $agentsDir: ${defs.keys.mkString(", ")}"))
    }

  def get(name: String): IO[Option[AgentDef]] =
    loadAll().map(_.get(name))

end AgentLibrary

object AgentLibrary:
  def defaultDir: os.Path = os.home / ".nebflow" / "agents"
