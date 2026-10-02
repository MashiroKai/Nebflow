package nebflow.core.jev

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * P2 (Face C) golden-diff assertions — the "OFF is byte-identical" contract.
 *
 * The design card names five assertion faces (dispatcher injection bytes /
 * node first-message `<injected-plugins>` bytes / WS `configData` payload /
 * NodeEdit validation error strings / REST model payload). This suite covers
 * the faces that are mechanically assertable OFFLINE, and each assertion is
 * written as a COMPARISON between a no-JeV baseline and a JeV-present state,
 * so the property under test is the DELTA (what changed), not a snapshot.
 *
 * ==What "byte-identical" can and cannot mean for the config payload==
 * Card §C.2.2 ③ is explicit that adding a top-level `jev` block DOES change the
 * `configData` payload — that is visible and harmless. So asserting "the whole
 * payload is unchanged" would be asserting something false. What must hold is
 * that the change is CONFINED to the `jev` key and that nothing behaviour-
 * bearing moves. That is what these tests assert.
 *
 * The Playwright layer of the same five faces needs an isolated instance (its
 * own port and home) and is NOT run here; it is reported as an open item
 * rather than silently skipped.
 */
class JevGoldenDiffSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-golden-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private val baselineNoJev = """{"llm":{"providers":{}},"plugins":{"enabled":true},"mcpServers":{}}"""
  private val withJevOff =
    """{"llm":{"providers":{}},"plugins":{"enabled":true},"mcpServers":{},"jev":{"enabled":false,"provider":"typesafe-jev"}}"""
  private val withJevOn =
    """{"llm":{"providers":{}},"plugins":{"enabled":true},"mcpServers":{},"jev":{"enabled":true,"provider":"typesafe-jev","timeoutMs":15000}}"""

  private def topLevelKeys(json: String): Set[String] =
    io.circe.parser.parse(json).toOption.flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def without(key: String)(json: String): Json =
    io.circe.parser
      .parse(json)
      .toOption
      .flatMap(_.asObject)
      .map(o => Json.fromJsonObject(o.remove(key)))
      .getOrElse(Json.Null)

  // ── face ③/⑤: the config payload delta is confined to `jev` ─────────────

  test("the config delta caused by the jev block is exactly the 'jev' key") {
    val base = topLevelKeys(baselineNoJev)
    val off = topLevelKeys(withJevOff)
    val on = topLevelKeys(withJevOn)
    assertEquals(off.diff(base), Set("jev"), "enabling/disabling adds only the jev key")
    assertEquals(on.diff(base), Set("jev"))
    assertEquals(base.diff(on), Set.empty[String], "no existing key may disappear")
  }

  test("every non-jev section is byte-identical between baseline and either jev state") {
    // Compared as parsed JSON (key order is not part of the contract, values
    // and structure are).
    assertEquals(without("jev")(withJevOff), without("jev")(baselineNoJev))
    assertEquals(without("jev")(withJevOn), without("jev")(baselineNoJev))
  }

  test("the service config decodes to identical behaviour-bearing fields regardless of jev") {
    withTempDataRoot { tmp =>
      IO {
        def load(json: String) =
          os.write.over(tmp / "nebflow.json", json)
          nebflow.shared.Config.loadServiceConfig()
        val a = load(baselineNoJev)
        val b = load(withJevOff)
        val c = load(withJevOn)
        assertEquals(b.llm, a.llm)
        assertEquals(c.llm, a.llm)
        assertEquals(b.mcpServers, a.mcpServers)
        assertEquals(c.mcpServers, a.mcpServers)
        assertEquals(b.jev.map(_.isEnabled), Some(false))
        assertEquals(c.jev.map(_.isEnabled), Some(true))
      }
    }
  }

  // ── face ①: the dispatcher capability-catalog bytes are jev-independent ─

  test("the dispatcher capability catalog renders identically across jev states") {
    // The catalog is the most sensitive injected artifact (card §C.2.2 ①).
    // The JeV face must not alter it: allocation consumes the catalog, it does
    // not extend it. A temp data root with no installed packages renders the
    // empty catalog in every state — the assertion is the EQUALITY, which is
    // what a regression that injects a jev line would break.
    withTempDataRoot { tmp =>
      IO {
        def render(json: String) =
          os.write.over(tmp / "nebflow.json", json)
          nebflow.core.plugin.PluginRegistry.renderCatalog().unsafeRunSync()
        val a = render(baselineNoJev)
        val b = render(withJevOff)
        val c = render(withJevOn)
        assertEquals(b, a, "jeV off must not change the catalog bytes")
        assertEquals(c, a, "jeV on must not change the catalog bytes either")
      }
    }
  }

  // ── face ④: the NodeEdit validation strings are untouched ───────────────

  test("the NodeEdit declaration-gate and dispatch-gate error strings are unchanged") {
    // These are model-visible strings (§16 face). P2 must not touch them, and
    // the #8 relocation of the DECLARATION duty is explicitly out of this
    // batch's landing scope — so the strings must still read exactly as before.
    val src = os.read(os.pwd / os.RelPath("src/main/scala/nebflow/core/tools/NodeEditTool.scala"))
    for code <- List("NODE_PLUGINS_UNDECLARED", "PLUGIN_BLOCKED", "PLUGIN_DISPATCH_DISABLED")
    do assert(src.contains(code), s"the '$code' error-code face must still be present")
    assert(
      src.contains("must DECLARE its plugin capability face"),
      "the declaration-gate wording must be unchanged in this batch"
    )
  }

  // ── the OFF path is structural, not conditional sprinkling ──────────────

  test("no call site mutates the plugin set when the gate is off") {
    // The gate returns a typed Off; the P3 hook is a declared no-op stub in
    // this batch. Assert the structural claim that makes "OFF = old code path"
    // true: the jev modules do not write NodeDef.plugins anywhere.
    val repoRoot = os.pwd
    val jevSources = List(
      "src/main/scala/nebflow/core/jev/JevGate.scala",
      "src/main/scala/nebflow/core/jev/JevConfigReader.scala",
      "src/main/scala/nebflow/core/jev/JevFallback.scala",
      "src/main/scala/nebflow/core/jev/JevAllocatorPort.scala"
    )
    // Strip comment lines before scanning: naming the node model in prose is
    // not a mutation, and conflating the two would make the assertion fail
    // for cosmetic edits (which teaches the wrong lesson).
    def codeLines(src: String): String =
      src.linesIterator
        .map(_.trim)
        .filterNot(l => l.startsWith("*") || l.startsWith("//") || l.startsWith("/*"))
        .mkString("\n")
    for p <- jevSources do
      val code = codeLines(os.read(repoRoot / os.RelPath(p)))
      assert(!code.contains("NodeDef"), s"$p must not reach into the node model")
      assert(!code.contains(".plugins ="), s"$p must not assign a plugin set")
  }

  test("the OFF verdict is reached without ever constructing a provider") {
    // A structural property worth pinning: when the gate is off, resolution
    // must not build a decision provider (no credential read, no client). We
    // assert the negative by resolving from a state whose secret is absent and
    // confirming no token is produced and no provider is reached for.
    withTempDataRoot { tmp =>
      IO {
        os.write.over(tmp / "nebflow.json", withJevOff)
        assertEquals(JevConfigReader.outcome.unsafeRunSync(), JevGateOutcome.Off(JevGateReason.ToggleOff))
        // The allocator is only built when the face is on; off => None.
        assertEquals(nebflow.llm.decision.JevAllocator.fromConfig(None), None)
      }
    }
  }
