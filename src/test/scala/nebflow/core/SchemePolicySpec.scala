package nebflow.core

import munit.FunSuite
import nebflow.shared.AgentModelConfig

/** SchemePolicy v2 — four-role model-chain resolution:
  * own chain (settable roles) > Nebula primary chain (everyone else, fresh
  * read) > seed chain (Nebula unconfigured). */
class SchemePolicySpec extends FunSuite:

  private val lowCost = AgentModelConfig(
    preferred = Some("zhipu/GLM-5.3-Flash"),
    fallbacks = List("cmdcode/deepseek/deepseek-v4.1-flash", "kimi/kimi-k3")
  )
  private val other = AgentModelConfig(preferred = Some("kimi/kimi-k3"), fallbacks = List("deepseek/deepseek-flash"))

  private def withRoot(name: String)(body: os.Path => Unit): Unit =
    val tmp = os.pwd / "target" / s"scheme-policy-v2-$name-${System.nanoTime().toHexString}"
    os.makeDir.all(tmp)
    PathUtil.setDataRoot(tmp)
    try body(tmp)
    finally
      PathUtil.setDataRoot(os.Path("/tmp"))
      os.remove.all(tmp)

  private def writeAgent(root: os.Path, name: String, json: String): Unit =
    val dir = root / "agents" / name
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json", json)

  private def writeNebula(root: os.Path, model: Option[AgentModelConfig]): Unit =
    val modelJson = model.map(m =>
      s"""{"preferred":${m.preferred.map(p => s"\"$p\"").getOrElse("null")},"fallbacks":${m.fallbacks.map(s => s"\"$s\"").mkString("[", ",", "]")}}"""
    ).getOrElse("null")
    writeAgent(root, "Nebula", s"""{"name":"Nebula","model":$modelJson}""")

  private def providersJson(entries: (String, String)*): String =
    val providers = entries.map { case (pid, mid) => s""""$pid":{"baseUrl":"http://127.0.0.1:1","apiKey":"k","protocol":"openai","models":[{"id":"$mid","contextWindow":8192}]}""" }.mkString(",")
    s"""{"llm":{"providers":{$providers}}}"""

  // ── write gate ────────────────────────────────────────────

  test("SettableAgents is exactly the four roles") {
    assertEquals(SchemePolicy.SettableAgents, Set("Nebula", "project-dispatcher", "kernel", "general"))
  }

  // ── level 1: own chain (settable roles) ──────────────────

  test("Nebula with an own chain resolves to it (own-chain)") {
    withRoot("nebula-own") { root =>
      writeNebula(root, Some(lowCost))
      val (chain, from) = SchemePolicy.resolveModel("Nebula", Some(lowCost))
      assertEquals(chain, lowCost)
      assertEquals(from, SchemePolicy.OwnChainSource)
    }
  }

  test("each follower role with an own chain resolves to it (fork)") {
    withRoot("fork") { root =>
      writeNebula(root, Some(lowCost))
      for name <- List("kernel", "project-dispatcher", "general") do
        val (chain, from) = SchemePolicy.resolveModel(name, Some(other))
        assertEquals(chain, other, s"$name own chain must win")
        assertEquals(from, SchemePolicy.OwnChainSource, s"$name resolvedFrom")
    }
  }

  test("an empty own chain is treated as absent (follow)") {
    withRoot("empty-own") { root =>
      writeNebula(root, Some(lowCost))
      val (chain, from) = SchemePolicy.resolveModel("kernel", Some(AgentModelConfig.empty))
      assertEquals(chain, lowCost)
      assertEquals(from, SchemePolicy.NebulaChainSource)
    }
  }

  // ── level 2: follow the Nebula primary chain ─────────────

  test("followers without an own chain inherit Nebula's chain (nebula-chain)") {
    withRoot("follow") { root =>
      writeNebula(root, Some(lowCost))
      for name <- List("kernel", "project-dispatcher", "general", "custom-agent") do
        val (chain, from) = SchemePolicy.resolveModel(name, None)
        assertEquals(chain, lowCost, s"$name must follow Nebula")
        assertEquals(from, SchemePolicy.NebulaChainSource, s"$name resolvedFrom")
    }
  }

  test("non-role agents follow Nebula; their stored chain is not read") {
    withRoot("non-role") { root =>
      writeNebula(root, Some(lowCost))
      writeAgent(root, "coder", """{"name":"coder","model":{"preferred":"x/y","fallbacks":[]}}""")
      // resolution input is the caller's duty: passing the stored chain of a
      // non-role agent must still land on Nebula (stored refs stay audit-only)
      val (chain, from) = SchemePolicy.resolveModel("coder", Some(AgentModelConfig(Some("x/y"), Nil)))
      assertEquals(chain, lowCost)
      assertEquals(from, SchemePolicy.NebulaChainSource)
    }
  }

  test("followers re-read Nebula's chain on every call (live follow)") {
    withRoot("live-follow") { root =>
      writeNebula(root, Some(lowCost))
      assertEquals(SchemePolicy.resolveModel("kernel", None)._1, lowCost)
      os.write.over(root / "agents" / "Nebula" / "agent.json",
        """{"name":"Nebula","model":{"preferred":"kimi/kimi-k3","fallbacks":[]}}""")
      val (chain, from) = SchemePolicy.resolveModel("kernel", None)
      assertEquals(chain.preferred, Some("kimi/kimi-k3"))
      assertEquals(from, SchemePolicy.NebulaChainSource)
    }
  }

  test("nebulaChain reads the raw stored chain without resolution") {
    withRoot("raw-nebula") { root =>
      assertEquals(SchemePolicy.nebulaChain(), None)
      writeNebula(root, Some(other))
      assertEquals(SchemePolicy.nebulaChain(), Some(other))
    }
  }

  // ── level 3: seed chain ──────────────────────────────────

  test("unconfigured Nebula falls to the providers-derived seed chain") {
    withRoot("seed-providers") { root =>
      os.write.over(root / "nebflow.json", providersJson("Z" -> "m2", "A" -> "m1"))
      writeNebula(root, None)
      val (chain, from) = SchemePolicy.resolveModel("Nebula", None)
      assertEquals(chain, AgentModelConfig(Some("Z/m2"), Nil)) // first provider in field order
      assertEquals(from, SchemePolicy.SeedSource)
    }
  }

  test("missing Nebula agent.json: followers fall to the seed chain (fail-safe)") {
    withRoot("seed-no-root") { root =>
      os.write.over(root / "nebflow.json", providersJson("Z" -> "m2"))
      val (chain, from) = SchemePolicy.resolveModel("kernel", None)
      assertEquals(chain, AgentModelConfig(Some("Z/m2"), Nil))
      assertEquals(from, SchemePolicy.SeedSource)
    }
  }

  test("unconfigured install resolves to an empty chain (registry all-candidates path)") {
    withRoot("seed-empty") { root =>
      val (chain, from) = SchemePolicy.resolveModel("general", None)
      assertEquals(chain, AgentModelConfig.empty)
      assertEquals(from, SchemePolicy.SeedSource)
    }
  }

  test("corrupt Nebula agent.json degrades to the seed chain (never throws)") {
    withRoot("seed-corrupt") { root =>
      writeAgent(root, "Nebula", "{not json")
      os.write.over(root / "nebflow.json", providersJson("Z" -> "m2"))
      val (chain, from) = SchemePolicy.resolveModel("kernel", None)
      assertEquals(chain, AgentModelConfig(Some("Z/m2"), Nil))
      assertEquals(from, SchemePolicy.SeedSource)
    }
  }

  test("seed chain: legacy llm.model wins over providers while it exists") {
    withRoot("seed-llmmodel") { root =>
      os.write.over(root / "nebflow.json",
        """{"llm":{"providers":{"Z":{"baseUrl":"http://127.0.0.1:1","apiKey":"k","protocol":"openai","models":[{"id":"m2","contextWindow":8192}]}},"""
          + """"model":{"default":"Z/m9","fallbacks":["A/m1"]}}}""")
      assertEquals(SchemePolicy.readSeedChain(), List("Z/m9", "A/m1"))
    }
  }

  test("seed chain: absent llm.model falls to providers; empty install yields Nil") {
    withRoot("seed-order") { root =>
      os.write.over(root / "nebflow.json", providersJson("Y" -> "y1", "X" -> "x1"))
      assertEquals(SchemePolicy.readSeedChain(), List("Y/y1"))
      os.write.over(root / "nebflow.json", """{"llm":{"providers":{}}}""")
      assertEquals(SchemePolicy.readSeedChain(), Nil)
      os.remove(root / "nebflow.json")
      assertEquals(SchemePolicy.readSeedChain(), Nil)
    }
  }
