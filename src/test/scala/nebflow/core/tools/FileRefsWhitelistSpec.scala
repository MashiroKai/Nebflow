package nebflow.core.tools

import munit.FunSuite
import nebflow.gateway.NfFilePolicy

/**
 * A14（C 批 / 票据腿）——工具侧扩展名白名单表与端点侧白名单表必须逐项相等。
 *
 * The two tables exist for two different jobs (FileRefs pre-filtered which
 * references the Card/Pop tools turned into proxy URLs; NfFileAllowedExt was
 * the endpoint's own gate) and they have drifted before: the 2026-09-05 fix had
 * to delete avi/eot/wasm/obj/stl/gltf/glb from FileRefs because the endpoint no
 * longer served them. Equality is the cheap invariant that keeps them welded.
 *
 * 🔴 2026-10-07 askuser-attach-align 批（作者 09:35/09:41 两令）：两张表**同时退役**
 * ——端点阶梯的扩展名维与 FileRefs 的前置过滤双双撤除（Canvas viewer 注册表为准，
 * 未注册类型渲染面下载兜底）；表本体与逐项相等不变量**保留**，防任何单侧复活。
 * The namespace mirror (A1) keeps the same shape for the same reason — and the
 * 2026-10-07 askuser-copy-land cut (§16.2 落刀) retires the prose leg with the
 * judge: the table body stays welded (A1-mirror), while the tool prose must
 * NOT enumerate the list anymore (the judge no longer reads it).
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
  // item for item — and (since the 2026-10-07 flip) the prose the model reads
  // must no longer name it.

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

  test(
    "A1-mirror (2026-10-07 flip): the shipped tool descriptions no longer enumerate the served namespaces (retired prose cannot silently return)"
  ) {
    val text = FileRefs.DataRootServedNamespacesText
    // The 2026-10-07 askuser-copy-land cut (§16.2 落刀) rewrote PopTool's prose to
    // "every readable local location qualifies" — the enumeration left the model face
    // together with the judge that read it. This leg welds the PROSE side of that
    // retirement: the list must not reappear in tool prose while the judge no longer
    // reads it (same anti-single-side-revival weld as the retired tables above).
    List("Pop" -> PopTool.description).foreach { (who, described) =>
      assert(!described.contains(text), s"$who's description must not enumerate the retired namespace list: $text")
      assert(
        !described.contains("docs/**` is NOT served") && !described.contains("docs/** is NOT served"),
        s"$who's description still claims docs/** is not served"
      )
    }
  }

  test(
    "A1-judge (2026-10-07 flip): the namespace reach layer is RETIRED — only credential identity refuses (pure judge, no disk)"
  ) {
    // Pure reads (nfCredentialDeny touches no filesystem: it normalizes and
    // compares prefixes), so this pins the FLIP without writing anything:
    // former namespace refusals all read None now; the only refusing shapes
    // left are the credential identity tables. (The R2 inode layer is not
    // exercised here — this synthetic policy carries an empty inode set; the
    // route-level inode pins live in NfTicketRoutesSpec A5/A6/A17-counter.)
    val root = java.nio.file.Paths.get("/tmp/nebflow-ns-mirror-spec/dataroot").normalize()
    val policy = NfFilePolicy.NfPathPolicy(
      root,
      java.nio.file.Paths.get("/tmp/nebflow-ns-mirror-spec/ws"),
      Set.empty
    )
    def deny(rel: String): Option[String] = NfFilePolicy.nfCredentialDeny(root.resolve(rel), policy)
    // served (the whole former-namespace face, allowlisted member or not)
    List(
      "docs/x.svg",
      "docs/nested/deep/x.svg",
      "logs/x.svg",
      "sessions/x.svg",
      "usage-records/x.json",
      "future-thing.json",
      "docsmith/x.svg", // a prefix sibling of `docs` — irrelevant either way now
      "docsx/x.svg"
    ).foreach(rel => assertEquals(deny(rel), None, s"$rel must SERVE (namespace reach retired)"))
    // still refused: credential identity only. Two sources since the flip:
    //  - the root-free tables (credential-shaped basenames / directory segments)
    //  - the ROOT-ANCHORED Nebflow seed (2026-10-07 askuser-attach-align 批):
    //    `<dataRoot>/auth.json` / `<dataRoot>/nebflow.json` / the `<dataRoot>/secrets`
    //    subtree are credential files BY IDENTITY now — the seed answers live for
    //    every request, so they refuse here even though this synthetic policy's
    //    inode set is empty (before the seed they were only caught by the
    //    startup inode snapshot / the now-retired namespace reach).
    assert(deny("auth.json").isDefined, "auth.json stays refused (credential-shaped basename)")
    assert(deny("nebflow.json").isDefined, "nebflow.json stays refused (root-anchored seed)")
    assert(
      deny("secrets/x.txt").isDefined,
      "the dataRoot secrets subtree stays refused (root-anchored seed, live — no longer the namespace layer's job)"
    )
  }
end FileRefsWhitelistSpec
