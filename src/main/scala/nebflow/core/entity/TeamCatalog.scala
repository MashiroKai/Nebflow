package nebflow.core.entity

/** Builds Team catalog strings for system prompt injection. */
object TeamCatalog:

  /**
   * Build the Team catalog section for system prompt injection.
   *  Shows team lead, members (with description + useWhen), and available flows.
   */
  def buildCatalog(team: TeamDef, agents: Map[String, AgentEntry], flows: Map[String, FlowDagDef]): String =
    val leadLine = agents.get(team.lead) match
      case Some(a) => s"- ${team.lead}: ${a.description}\n  Use when: ${a.useWhen}"
      case None => s"- ${team.lead}: (agent not found)"

    val memberLines = team.members
      .flatMap { name =>
        agents.get(name).map { a =>
          s"- $name: ${a.description}\n  Use when: ${a.useWhen}"
        }
      }
      .mkString("\n")

    s"""=== Team: ${team.name} ===
       |
       |${team.description}
       |
       |Team Lead (Mail by name):
       |$leadLine
       |
       |Team Members (Mail by name):
       |$memberLines
       |
       |=== End Team ===""".stripMargin

  end buildCatalog

  /**
   * Build a global catalog for Nebula (not part of any team).
   *  Lists all teams and flows with routing guidance.
   */
  def buildGlobalCatalog(
    teams: Map[String, TeamDef],
    flows: Map[String, FlowDagDef],
    agents: Map[String, AgentEntry] = Map.empty
  ): String =
    val teamLines = teams.values.toList
      .sortBy(_.name)
      .map { t =>
        val members = (t.lead :: t.members).distinct.mkString(", ")
        s"- ${t.name}: ${t.description}\n  Members: $members"
      }
      .mkString("\n")

    val flowLines = flows.values.toList
      .sortBy(_.name)
      .map { f =>
        s"- ${f.name}: ${f.description}"
      }
      .mkString("\n")

    val standaloneAgents = agents.values.toList
      .filter(a => a.category == "standalone" && a.name != "Nebula")
      .sortBy(_.name)
    val agentLines = standaloneAgents
      .map { a =>
        s"- ${a.name}: ${a.description}"
      }
      .mkString("\n")

    s"""=== Teams & Flows ===

## When to use what

**Agent** — functional specialist for simple tasks. No persistent context.
  → `Mail("agent-name", "your task")`
  → Use when: task needs one specific capability (explore code, write a function, generate a chart).

**Team** — ongoing project work (development, research, writing). The Team Lead receives your task and coordinates members internally.
  → `Mail("team-name", "your task")`
  → Use when: task belongs to a known project, needs multiple roles (e.g. backend + frontend + docs).

**Flow** — structured one-shot pipeline (code review, release, merge, research). Executes a fixed DAG of agents, returns result.
  → `Mail("flow-name", "your task")`
  → Use when: task matches a pipeline pattern (review code, cut a release, merge a branch, research a topic).

**Create new** — if no existing Team/Flow fits, use the entity-creator flow to design one.
  → `Mail("entity-creator", "create a team/flow/agent for ...")`

${if standaloneAgents.nonEmpty then s"""## Standalone Agents (Mail by name for direct delegation)
$agentLines
""" else ""}## Teams
${if teamLines.nonEmpty then teamLines else "(none — create one with entity-creator flow)"}

## Flows
${if flowLines.nonEmpty then flowLines else "(none)"}

=== End ===""".stripMargin
  end buildGlobalCatalog

end TeamCatalog
