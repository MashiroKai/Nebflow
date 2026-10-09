package nebflow.core.seed

import munit.FunSuite

/** RETIRED-PREREQUISITE NOTE — author P1 ruling, option (a):
  *
  * a5b716d55（seed/agents 树退役批）作废其双面前提；提示词面已上收 BuiltinAgents 代码面
  *
  * Accordingly the former P1 "seed and runtime prompt carry the secrets line
  * together or not at all" leg has been deleted: it asserted agreement between
  * two prompt faces whose seed side (`seed/agents/Nebula/system.md`) the
  * retirement batch removed — a dead premise (the leg read a file that no longer
  * exists, the runtime mirror has no writer, so the leg would be permanently
  * red once landed). This deletion is the mechanical consequence of that
  * retirement ruling, not a new ruling. The docstring below is kept verbatim as
  * the record of the original design intent; its two-face premise is void per
  * the note above. P2 (catalogue path agreement) is unaffected.
  *
  * ---- original header (premise retired, see note above) ----
  *
  * secrets-consumption batch (2026-10-01) — acceptance item §二.5, prompt-line leg.
  *
  * The batch's §16 draft (see `.nebflow/evidence/20261001_secrets-consumption/`)
  * must NOT be landed here: the author reviews the line verbatim first. What this
  * spec can pin TODAY, without landing anything, is the invariant that makes the
  * landing safe:
  *
  *   🔴 **The prompt the gateway actually serves and the prompt a fresh install
  *   would get must agree.** If a future landing writes the secrets line into
  *   the seed, the runtime mirror must carry the same line — otherwise the two
  *   faces silently diverge, which is precisely the failure mode
  *   `SeedService.reconcileAgents`' refuse-on-diff guard exists to surface.
  *
  * The assertion is stated as **both-or-neither**, not as an unconditional
  * byte-identity check of the two files, because:
  *
  *   - the seed is, by its own provenance header, intentionally AHEAD of the
  *     runtime file until a restart-window sync (a boot warning by design, not a
  *     defect), so a byte-identity assert would be red today for a reason that
  *     has nothing to do with this batch;
  *   - a check that is red for unrelated reasons gets muted, which is how a red
  *     line stops meaning anything.
  *
  * Green today (both faces absent — this batch writes neither); load-bearing the
  * moment the line lands, and needing no edit on landing day: [[Sentinel]] is a
  * stable substring of the draft, so the guard arms itself.
  *
  * Read-only throughout: this spec never writes a seed or runtime file.
  */
class NebulaSecretsPromptLineSpec extends FunSuite:

  /** Stable substring of the §16 draft line — pinning that the artefact P2
    * asserts (`SecretsCatalog.catalogPath`) is exactly the one the draft's
    * prompt line named. It survives wording edits anywhere else in the line. */
  private val Sentinel = "secrets-catalog.md"

  test("P2 · the catalogue path the draft prompt line names is the path this batch renders") {
    // The draft points agents at `<dataRoot>/secrets-catalog.md`; if the
    // implementation ever moved that artefact, the approved prompt line would
    // silently point agents at a file that does not exist. Pin the agreement.
    val root = os.pwd
    val rendered = nebflow.core.secrets.SecretsCatalog.catalogPath(root)
    assertEquals(rendered, root / "secrets-catalog.md")
    assertEquals(rendered.last, Sentinel,
      s"the artefact the draft prompt line names must be the artefact rendered: got $rendered")
    assertEquals(nebflow.core.secrets.SecretsCatalog.metaPath(root), root / "secrets-meta.json")
    // Both sit OUTSIDE the credential namespace — `SecretsStore.list()` enumerates
    // every regular file in `secrets/`, so a catalogue placed there would surface
    // in the panel as a bogus secret row.
    assertEquals(rendered / os.up, root,
      s"the catalogue must live next to secrets/, not inside it: $rendered")
    assertEquals(nebflow.core.secrets.SecretsCatalog.metaPath(root) / os.up, root)
  }

end NebulaSecretsPromptLineSpec
