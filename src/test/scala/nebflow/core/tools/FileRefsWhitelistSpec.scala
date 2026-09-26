package nebflow.core.tools

import munit.FunSuite
import nebflow.gateway.NfFilePolicy

/**
 * A14（C 批 / 票据腿）——工具侧扩展名白名单表与端点侧白名单表必须逐项相等。
 *
 * The two tables exist for two different jobs (FileRefs pre-filters which
 * references the Card/Pop tools turn into proxy URLs; NfFileAllowedExt is the
 * endpoint's own gate) and they have drifted before: the 2026-09-05 fix had to
 * delete avi/eot/wasm/obj/stl/gltf/glb from FileRefs because the endpoint no
 * longer served them. Equality is the cheap invariant that keeps them welded:
 * a future change to one table without the other fails here instead of
 * silently producing dead links (FileRefs-ahead) or 400s (endpoint-ahead).
 *
 * Lives in `nebflow.core.tools` because `FileRefs` is `private[tools]`.
 */
class FileRefsWhitelistSpec extends FunSuite:

  test("A14: FileRefs.AllowedExtensions == NfFilePolicy.NfFileAllowedExt (no table drift)") {
    assertEquals(FileRefs.AllowedExtensions, NfFilePolicy.NfFileAllowedExt)
  }

  test("A14: the mirrored table is non-empty and covers the Canvas companions") {
    val ext = FileRefs.AllowedExtensions
    assert(ext.nonEmpty)
    assert(Set("js", "mjs", "css", "json").subsetOf(ext))
  }

  // ── 2026-09-17 nfext batch: legacy binary Office types on the tool face ──
  //
  // A14 above already welds the two tables, so equality alone is covered. This
  // assertion records WHY the mirrored side had to move in the same commit: the
  // tool face decides which `src`/`href` references become `/api/nf-file` proxy
  // URLs, so an endpoint-only widening would leave Card/Pop teaching the model
  // that `x.doc` is unservable (the dead-link failure the 2026-09-05 comment
  // block describes) while the endpoint happily serves it.

  test("A14-batch: the mirrored table carries the legacy binary Office types the endpoint now serves") {
    val ext = FileRefs.AllowedExtensions
    assert(Set("doc", "ppt", "xls").subsetOf(ext), "the nfext widening must reach the proxy-rewrite face")
    // The macro-enabled / executable near-misses stay out on this side too.
    assert(!ext.contains("docm"))
    assert(!ext.contains("exe"))
    assert(!ext.contains("sh"))
  }

  // ── A1 namespace mirror (img-ticket batch i, 2026-09-16 · #687-A/#687-B) ──
  //
  // The SAME cheap-invariant argument as A14, one level up (the judge's table
  // instead of the extension table): the tool face teaches the model where its
  // `/api/nf-file` references can be served from, and the endpoint decides it.
  // Before this batch the two were unlinked — the tool prose enumerated the
  // allowlist by hand, and the 2026-09-16 author ruling (#687-A) widened the
  // endpoint by exactly one entry (`docs`). These tests make that drift
  // impossible to ship: the tool-side constant must equal the endpoint's list
  // item for item, and the prose the model reads must name it.

  test("A1-mirror: FileRefs.DataRootServedNamespaces == NfFilePolicy.NfDataRootAllowlist (no namespace drift)") {
    assertEquals(FileRefs.DataRootServedNamespaces, NfFilePolicy.NfDataRootAllowlist)
  }

  test("A1-mirror: non-empty, carries `docs`, keeps the pre-batch entries, renders to the tool-face text") {
    val ns = FileRefs.DataRootServedNamespaces
    assert(ns.nonEmpty)
    assert(ns.contains("docs"), "the author ruling #687-A (docs/** served) must reach the tool face")
    // The batch adds ONE entry; it does not reorder or drop the shipped five.
    assert(List("projects", "uploads", "plots", "workspace-items", "voice-models").forall(ns.contains))
    assertEquals(FileRefs.DataRootServedNamespacesText, ns.map(_ + "/**").mkString(", "))
    assert(FileRefs.DataRootServedNamespacesText.contains("docs/**"))
    assert(
      !FileRefs.DataRootServedNamespacesText.startsWith(",") && !FileRefs.DataRootServedNamespacesText.contains("  ")
    )
  }

  test("A1-mirror: the shipped tool descriptions name every served namespace (prose cannot drift)") {
    val text = FileRefs.DataRootServedNamespacesText
    List("Card" -> CardTool.description, "Pop" -> PopTool.description).foreach { (who, described) =>
      assert(described.contains(text), s"$who's description must enumerate the served namespaces: $text")
      assert(
        !described.contains("docs/**` is NOT served") && !described.contains("docs/** is NOT served"),
        s"$who's description still claims docs/** is not served"
      )
    }
  }

  test("A1-judge: <dataRoot>/docs/** is allowed and NOTHING else moved (pure judge, no disk)") {
    // Pure reads (nfCredentialDeny touches no filesystem: it normalizes and
    // compares prefixes), so this pins the widening without writing anything.
    val root = java.nio.file.Paths.get("/tmp/nebflow-ns-mirror-spec/dataroot").normalize()
    val policy = NfFilePolicy.NfPathPolicy(
      root,
      java.nio.file.Paths.get("/tmp/nebflow-ns-mirror-spec/ws"),
      Set.empty
    )
    def deny(rel: String): Option[String] = NfFilePolicy.nfCredentialDeny(root.resolve(rel), policy)
    assertEquals(deny("docs/x.svg"), None, "the batch: docs/** is served")
    assertEquals(deny("docs/nested/deep/x.svg"), None, "…including nested subtrees")
    List(
      "logs/x.svg",
      "sessions/x.svg",
      "usage-records/x.json",
      "secrets/x.txt",
      "auth.json",
      "nebflow.json",
      "future-thing.json",
      "docsmith/x.svg", // a prefix sibling of `docs` — the judge matches the head exactly, never by prefix
      "docsx/x.svg"
    ).foreach(rel => assert(deny(rel).isDefined, s"$rel must STAY refused (one-entry widening)"))
  }
end FileRefsWhitelistSpec
