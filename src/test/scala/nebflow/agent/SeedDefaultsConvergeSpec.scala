package nebflow.agent

import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

// Seed list convergence spec (F.3 agents-layer archiving companion fix).
//
// Retirement at the agents layer renames agent.json to agent.json.archived
// (the git repo keeps the definition). Before convergence Seeds.all still
// carried Explorer/Coder, and GatewayMain calls seedDefaults() on every
// startup — seedDefaults() rewrites seeds for any in-list dir missing
// agent.json, so an archived dir was resurrected (untracked) on next restart.
// After convergence the list is Nebula-only: the sentinel pins the list, the
// behavior test pins that an archived dir is NOT re-seeded while the in-list
// Nebula dir still is.
class SeedDefaultsConvergeSpec extends CatsEffectSuite:

  test("Seeds.all is Nebula-only — archived agent names must never re-enter the seed list"):
    assertEquals(Seeds.all.map(_.name), List("Nebula"))

  test("seedDefaults does not resurrect an archived agent dir; Nebula dir is still seeded"):
    val tmpDir = os.temp.dir()
    // Archived agent: agent.json renamed to agent.json.archived (no agent.json)
    val explorerDir = tmpDir / "Explorer"
    os.makeDir.all(explorerDir)
    os.write.over(explorerDir / "agent.json.archived", "{}")
    // Nebula dir absent entirely — in-list seeds are still written on install
    assert(!os.exists(tmpDir / "Nebula"))

    new AgentLibrary(tmpDir, None).seedDefaults().unsafeRunSync()

    assert(!os.exists(explorerDir / "agent.json"), "archived agent must NOT be resurrected by seeding")
    assert(os.exists(tmpDir / "Nebula" / "agent.json"), "in-list seed agent.json must still be written")
    assert(os.exists(tmpDir / "Nebula" / "system.md"), "in-list seed system.md must still be written")

end SeedDefaultsConvergeSpec
