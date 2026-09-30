package nebflow.core.seed

import munit.FunSuite

import java.nio.file.Files

/** secrets-consumption batch (2026-10-01) — acceptance item §二.5, prompt-line leg.
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

  /** Stable substring of the §16 draft line — the needle both files are scanned
    * for. It survives wording edits anywhere else in the line. */
  private val Sentinel = "secrets-catalog.md"

  private val SeedRel = "src/main/resources/seed/agents/Nebula/system.md"

  private def runtimePath: os.Path = os.home / ".nebflow" / "agents" / "Nebula" / "system.md"

  /** The repository root of whichever checkout this spec runs from. `os.pwd` is
    * the sbt project base (the worktree root under a worktree node); the second
    * candidate covers an invocation from a nested directory. A missing seed file
    * is a hard failure, not a skip — the seed is a tracked file. */
  private def seedPath(): os.Path =
    // `SeedRel` carries slashes, which os-lib's `/` operator refuses as a single
    // segment — go through `os.RelPath`, the documented conversion for a
    // multi-segment relative path.
    val candidates = List(os.pwd / os.RelPath(SeedRel), os.pwd / os.up / os.up / os.RelPath(SeedRel))
    candidates.find(p => Files.exists(p.toNIO)).getOrElse(
      fail(s"$SeedRel not found from ${os.pwd} — run from the repository root")
    )

  test("P1 · 🔴 seed and runtime Nebula prompt carry the secrets line together or not at all") {
    val seedText = os.read(seedPath())
    val runtime = runtimePath
    val runtimeText = if Files.exists(runtime.toNIO) then Some(os.read(runtime)) else None
    (seedText.contains(Sentinel), runtimeText.exists(_.contains(Sentinel))) match
      case (false, false) => () // the state this batch leaves behind
      case (true, true)   => () // a future, approved §16 landing that covered both faces
      case (seedHas, runtimeHas) =>
        // (`seedHas` / `runtimeHas`, not `s` / `r` — a pattern binding named `s`
        // would shadow the string interpolator.)
        fail(
          s"seed carries the secrets prompt line = $seedHas but the runtime mirror = $runtimeHas — the two prompt " +
            "faces have diverged. A §16 landing must write BOTH (the runtime sync is a restart-window " +
            "ops action with the author present), and this batch must write NEITHER."
        )
  }

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
