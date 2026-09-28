package nebflow.agent

import cats.effect.IO
import munit.CatsEffectSuite

/**
 * 2026-08-15 FlowTrigger outage — global-agent parse gap.
 *
 * AgentLibrary had its own hand-written agent.json decoder (AgentJson) that
 * predated the `skills`/`flows` fields: global agents (Nebula included)
 * loaded through it lost both fields at the AgentDef layer, so NEITHER the
 * LLM schema NOR the executor ever saw FlowTrigger for them ("Nebula schema
 * 层就没有 FlowTrigger"). The decoder now parses both; these tests pin it.
 */
class AgentLibraryFlowsSpec extends CatsEffectSuite:

  private def withAgentsDir(jsons: Map[String, String])(test: AgentLibrary => IO[Unit]): IO[Unit] =
    val tmp = os.temp.dir()
    IO.delay {
      jsons.foreach { (name, json) =>
        val dir = tmp / name
        os.makeDir.all(dir)
        os.write(dir / "agent.json", json)
      }
    }.bracket(_ => test(new AgentLibrary(tmp)))(_ => IO.delay(os.remove.all(tmp)).attempt.void)

  test("global agent.json flows + skills are parsed into AgentDef"):
    withAgentsDir(Map(
      "FlowCaller" -> """{"name":"FlowCaller","description":"x","tools":["Read"],"skills":["visual-style"],"flows":["code-review","release-beta"]}"""
    )) { lib =>
      lib.get("FlowCaller").map {
        case Some(defn) =>
          assertEquals(defn.flows, List("code-review", "release-beta"))
          assertEquals(defn.skills, List("visual-style"))
        case None => fail("FlowCaller must load")
      }
    }

  test("wildcard flows [\"*\"] survive the parse (Nebula's flows:['*'] case)"):
    withAgentsDir(Map(
      "Nebula" -> """{"name":"Nebula","description":"root","tools":["*"],"flows":["*"]}"""
    )) { lib =>
      lib.get("Nebula").map {
        case Some(defn) =>
          assertEquals(defn.flows, List("*"), "the '*' wildcard must reach AgentDef verbatim")
        case None => fail("Nebula must load")
      }
    }

  test("agent.json without flows/skills fields defaults to Nil (back-compat)"):
    withAgentsDir(Map(
      "Legacy" -> """{"name":"Legacy","description":"old schema","tools":["Read"]}"""
    )) { lib =>
      lib.get("Legacy").map {
        case Some(defn) =>
          assertEquals(defn.flows, Nil)
          assertEquals(defn.skills, Nil)
        case None => fail("Legacy must load")
      }
    }
end AgentLibraryFlowsSpec
