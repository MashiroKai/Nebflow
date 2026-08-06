package nebflow.core.entity

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

class EntityLoaderSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-entity-loader"
  PathUtil.setDataRoot(tempRoot)

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot) }

  private def writeAgent(dir: os.Path, nameOpt: Option[String], description: String = "desc"): Unit =
    os.makeDir.all(dir)
    val json = nameOpt match
      case Some(n) => Json.obj("name" -> n.asJson, "description" -> description.asJson)
      case None => Json.obj("description" -> description.asJson) // no name field
    os.write(dir / "agent.json", json.noSpaces)

  test("findAgentByName: global agent found by dir name (fast path)"):
    for
      _ <- reset()
      _ <- IO(writeAgent(tempRoot / "agents" / "Coder", Some("Coder")))
      result <- EntityLoader.findAgentByName("Coder")
    yield
      assert(result.isDefined, "global agent should be found")
      assertEquals(result.get.name, "Coder")

  test("findAgentByName: flow agent with name != dirName is found by agent.json name"):
    for
      _ <- reset()
      // Directory is "consolidator" but agent.json says "memory-consolidator"
      _ <- IO(writeAgent(tempRoot / "flows" / "memory-consolidation" / "agents" / "consolidator", Some("memory-consolidator")))
      result <- EntityLoader.findAgentByName("memory-consolidator")
    yield
      assert(result.isDefined, "flow agent should be found by agent.json name, not dir name")
      assertEquals(result.get.name, "memory-consolidator")

  test("findAgentByName: team agent with name != dirName is found by agent.json name"):
    for
      _ <- reset()
      _ <- IO(writeAgent(tempRoot / "teams" / "myteam" / "agents" / "worker", Some("team-worker")))
      result <- EntityLoader.findAgentByName("team-worker")
    yield
      assert(result.isDefined, "team agent should be found by agent.json name")
      assertEquals(result.get.name, "team-worker")

  test("findAgentByName: agent with no name field falls back to directory name"):
    for
      _ <- reset()
      // No "name" field in agent.json — should use dir name "entity-creator"
      _ <- IO(writeAgent(tempRoot / "agents" / "entity-creator", None))
      entry <- EntityLoader.loadAgent("entity-creator")
      result <- EntityLoader.findAgentByName("entity-creator")
    yield
      assert(entry.isDefined)
      assertEquals(entry.get.name, "entity-creator", "loadAgent should fallback to dir name")
      assert(result.isDefined, "findAgentByName should find by dir name fallback")
      assertEquals(result.get.name, "entity-creator")

  test("findAgentByName: flow agent with no name field uses dir name and is found"):
    for
      _ <- reset()
      _ <- IO(writeAgent(tempRoot / "flows" / "myflow" / "agents" / "scanner", None))
      result <- EntityLoader.findAgentByName("scanner")
    yield
      assert(result.isDefined, "flow agent with no name field should be found by dir name")
      assertEquals(result.get.name, "scanner")

  test("findAgentByName: non-existent agent returns None"):
    for
      _ <- reset()
      _ <- IO(writeAgent(tempRoot / "agents" / "Coder", Some("Coder")))
      result <- EntityLoader.findAgentByName("Ghost")
    yield assert(result.isEmpty)

  test("findAgentByName: global agent takes priority over flow agent with same name"):
    for
      _ <- reset()
      _ <- IO(writeAgent(tempRoot / "agents" / "dup", Some("dup"), "global"))
      _ <- IO(writeAgent(tempRoot / "flows" / "f" / "agents" / "other", Some("dup"), "flow"))
      result <- EntityLoader.findAgentByName("dup")
    yield
      assert(result.isDefined)
      assertEquals(result.get.description, "global", "global agent should win")

  test("loadAgentFromDir: falls back to directory name when agent.json has no name"):
    for
      _ <- reset()
      _ <- IO(writeAgent(tempRoot / "agents" / "noname", None))
      entry = EntityLoader.loadAgentFromDir(tempRoot / "agents" / "noname")
    yield
      assert(entry.isDefined)
      assertEquals(entry.get.name, "noname", "should use directory name as fallback")
end EntityLoaderSpec
