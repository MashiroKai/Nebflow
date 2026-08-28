package nebflow.core.entity

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

/**
 * 2026-08-15 FlowTrigger outage regression guard.
 *
 * Root cause: the AgentEntry → AgentDef conversion existed as SIX hand-copied
 * field maps at spawn sites (WebSocketRoutes / MailTool ×2 / FlowTreeActor /
 * FlowDagExecutor / EntityLoader.findAgentByName), and only two of them
 * (ContextRefresher, EntityLoader) carried `flows`/`skills`. Agents whose
 * actor-startup snapshot came from one of the four lossy maps advertised
 * FlowTrigger in the LLM schema (built from the ContextRefresher-refreshed
 * def) while the executor gate (buildAllowedToolSet on the actor-held def)
 * rejected every call: "Tool not available: FlowTrigger".
 *
 * The conversion is now single-sourced (AgentEntry.toAgentDef). These tests
 * pin the invariant end-to-end at the loader layer: a flows/skills-declaring
 * agent.json must yield an AgentDef that still carries them, through every
 * public loader entry.
 */
class AgentDefPropagationSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-agent-def-propagation"
  PathUtil.setDataRoot(tempRoot)

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot) }

  private def writeAgentJson(dir: os.Path, json: Json): Unit =
    os.makeDir.all(dir)
    os.write(dir / "agent.json", json.noSpaces)

  private val flowsAgentJson: Json = Json.obj(
    "name" -> "FlowCaller".asJson,
    "description" -> "declares flows and skills".asJson,
    "tools" -> List("Read").asJson,
    "skills" -> List("visual-style", "nebflow/api").asJson,
    "flows" -> List("code-review", "release-beta").asJson
  )

  test("toAgentDef: AgentEntry fields survive the conversion — flows, skills, preset, tools"):
    val entry = AgentEntry(
      name = "X",
      description = "d",
      useWhen = "",
      tools = List("Read", "Grep"),
      voice = true,
      systemPrompt = "sys",
      category = "team",
      mcpServers = List("git"),
      model = None,
      preset = None,
      skills = List("visual-style"),
      flows = List("*")
    )
    val defn = entry.toAgentDef
    assertEquals(defn.name, "X")
    assertEquals(defn.tools, List("Read", "Grep"))
    assertEquals(defn.systemPrompt, "sys")
    assertEquals(defn.voiceEnabled, true)
    assertEquals(defn.category, "team")
    assertEquals(defn.mcpServers, List("git"))
    assertEquals(defn.skills, List("visual-style"))
    assertEquals(defn.flows, List("*"), "wildcard flows must survive verbatim")

  test("loadTeamAgent → toAgentDef carries flows + skills (team-member spawn path)"):
    for
      _ <- reset()
      _ <- IO(writeAgentJson(tempRoot / "teams" / "proj" / "agents" / "FlowCaller", flowsAgentJson))
      entryOpt <- EntityLoader.loadTeamAgent("proj", "FlowCaller")
      defn = entryOpt.map(_.toAgentDef)
    yield
      assert(defn.isDefined, "team agent entry must load")
      assertEquals(defn.get.flows, List("code-review", "release-beta"))
      assertEquals(defn.get.skills, List("visual-style", "nebflow/api"))

  test("findAgentByName carries flows + skills (global-agent path — the Nebula case)"):
    for
      _ <- reset()
      _ <- IO(writeAgentJson(tempRoot / "agents" / "FlowCaller", flowsAgentJson))
      defnOpt <- EntityLoader.findAgentByName("FlowCaller")
    yield
      assert(defnOpt.isDefined, "global agent must be found")
      assertEquals(defnOpt.get.flows, List("code-review", "release-beta"))
      assertEquals(defnOpt.get.skills, List("visual-style", "nebflow/api"))

  test("loadAgent returns the raw entry with flows + skills intact"):
    for
      _ <- reset()
      _ <- IO(writeAgentJson(tempRoot / "agents" / "FlowCaller", flowsAgentJson))
      entryOpt <- EntityLoader.loadAgent("FlowCaller")
    yield
      assert(entryOpt.isDefined)
      assertEquals(entryOpt.get.flows, List("code-review", "release-beta"))
      assertEquals(entryOpt.get.skills, List("visual-style", "nebflow/api"))
end AgentDefPropagationSpec
