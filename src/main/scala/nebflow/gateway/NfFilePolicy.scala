package nebflow.gateway

import cats.effect.IO
import nebflow.core.{FilePolicyPort, PathUtil}
import org.http4s.Status

/**
 * The `/api/nf-file` credential-namespace policy engine (C1/R1/R2 batch,
 * design plan §3.3) — extension whitelist, namespace tables, the path
 * verdict ladder and its layered projections.
 *
 * Extracted verbatim from the `WebSocketRoutes` companion (behavior-
 * preserving move): members keep their exact names and nesting shape,
 * one level deeper — `WebSocketRoutes.X` becomes `NfFilePolicy.X`. The
 * read endpoint, the ticket endpoint and the tool-side pre-flight gate
 * (`core.tools.FileRefs`) all ask their questions through THIS object, so
 * a path that cannot be read can never be ticketed (C1-5 single
 * authority).
 */
// Phase 5 解耦(行为保持重构,2026-09-25):本对象混入 core 窄端口
// `nebflow.core.FilePolicyPort`——判据阶梯/白名单表全部留在原地(实现不搬),
// core 的 FileRefs 只经端口问两个问题;接线 = GatewayMain 装配时
// FilePolicyPort.install(NfFilePolicy)。
object NfFilePolicy extends FilePolicyPort:

  /**
   * Extension whitelist for GET /api/nf-file (local files served to
   * card/canvas iframes).
   *
   * 2026-09-03 Canvas interactive-HTML fix: beyond the media types it now
   * covers the text asset types a multi-file HTML deliverable references —
   * `js` (companion data/script modules), `mjs`, `css`, `json`. Without
   * them the Canvas HTML viewer renders the document shell, but the page's
   * own boot script dies on the first missing module, BEFORE binding any
   * event listeners — the reported "renders but nothing is clickable"
   * failure (prototype.html:455 `const SNAP = window.FM_SNAPSHOT` →
   * :487 seedFromSnapshot() throws on the 400'd script).
   *
   * Security posture unchanged: the route stays token-gated end to end, and
   * anything running inside the srcdoc canvas iframe already executes with
   * app-origin script privileges (viewers/html.js sandbox: allow-scripts +
   * allow-same-origin), so serving js/css/json does not widen the trust
   * boundary — content source remains locally opened, user-initiated
   * deliverables.
   *
   * 2026-09-17 nfext batch (author ruling, superseding the list above — not a
   * silent override): `doc`, `ppt`, `xls` joined. The ruling's standard is
   * "the device face behaves like the friend/group face": the friend/group
   * attachment leg (`GET /api/friends/attachments/{id}`, RestApiRoutes.scala)
   * carries NO extension gate and serves legacy Office files, while the device
   * face routes its attachment bytes through THIS route's ticket leg — so the
   * three legacy binary types were the one place the two faces disagreed.
   * `devattach-verify` open item ① measured the resulting false affordance:
   * a legacy `.doc` card binds its download key (the frontend criterion
   * `canFetchLocalBytes` resolves `.doc`/`.ppt`/`.xls` to the docx/pptx/xlsx
   * viewers, all of which are in `BLOB_ITEM_TYPES`) but the key's nf-file call
   * is refused 400 once — "the key is there and must fail". Closing that gap
   * means exactly these three entries here, on the endpoint that is allowed.
   *
   * Width of the change: the extension table is NOT a path constraint. It is
   * evaluated LAST, after expandTilde → lexical normalize → exists/isRegularFile
   * → toRealPath → the credential-namespace judge → the R2 hard-link inode
   * guard (see `nfFileVerdict`), and it only decides whether an already-fully
   * resolved real file's lowercased last-dot suffix is served. Adding a suffix
   * therefore grants no reach to any path that the six upstream judgements
   * refuse — it widens "which bytes an already-credentialed, already-path-
   * authorized requester may fetch" by three suffixes and nothing else.
   * Serving bytes executes nothing ON THIS ENDPOINT — the route transports bytes
   * and never parses them — and what the EXISTING viewers then do with a legacy
   * container was MEASURED rather than assumed (probe scripts + raw stdout under
   * `.nebflow/evidence/20260917_nfext/`): `.ppt` parses nothing at all (info card
   * + a click-time download link, `viewers/pptx.js`); `.doc` is refused by
   * mammoth with a visible parse error (CFB/OLE2 header → "Can't find end of
   * central directory : is this a zip file ?", caught at `viewers/docx.js:68` →
   * error panel) instead of being rendered; `.xls` goes down the SAME SheetJS leg
   * that already carried `.xlsx`/`.xlsm` before this batch. No preview leg
   * evaluates VBA, so a macro inside a legacy container is inert on this face.
   *
   * ⚠ Open item registered in the batch report (deliberately NOT fixed here —
   * the frontend tree is outside this batch's face): these two tables decide only
   * what may be FETCHED, whereas the `.xls` leg inherits a pre-existing property
   * of the vendored SheetJS 0.18.5 — `sheet_to_html` escapes a cell's TEXT but
   * not its `data-v` attribute, and `viewers/xlsx.js:50` assigns that string to
   * `pane.innerHTML` (a same-origin div, not a sandboxed iframe). It is reachable
   * TODAY with `.xlsx` alone (already on this list before the batch) and through
   * the friend/group attachment leg (which has no extension gate at all), so this
   * batch neither creates nor widens it. `html` stays excluded from the
   * attachment preview face.
   *
   * Pure + testable on purpose (same pattern as uploadsRoutes).
   */
  val NfFileAllowedExt: Set[String] = Set(
    "png",
    "jpg",
    "jpeg",
    "gif",
    "svg",
    "webp",
    "ico",
    "bmp",
    "avif",
    "tiff",
    "tif",
    "mp4",
    "webm",
    "ogg",
    "ogv",
    "mov",
    "mp3",
    "wav",
    "oga",
    "flac",
    "aac",
    "m4a",
    "woff",
    "woff2",
    "ttf",
    "otf",
    "pdf",
    "doc",
    "docx",
    "xls",
    "xlsx",
    "xlsm",
    "ppt",
    "pptx",
    "epub",
    "js",
    "mjs",
    "css",
    "json"
  )

  // ────────────────────────────────────────────────────────────────────────
  // C1 — credential-namespace refusal (R1 = O-A: default-deny + allowlist)
  //
  // Before this batch the read endpoint confined NO root: credential check +
  // extension whitelist only. `?path=~/.nebflow/auth.json` (extension `json`,
  // on the whitelist) served the gateway token itself, and any extension-
  // whitelisted file under `~/.ssh` / `~/.aws` / `~/.docker` was fair game.
  //
  // Policy (R1 = O-A), applied to the *realpath* (C1-1), so symlinks and
  // `..` chains cannot dodge it:
  //   P1 = PathUtil.dataRoot (the data root; `overrideRoot` respected — never
  //        a hardcoded `~/.nebflow`)   → default deny, allowlist below
  //   P2 = home credential entries      → default deny (whole subtree)
  //   P3 = <workspace>/.nebflow         → default deny, allowlist below
  //   outside P1..P3                    → pattern reject only (mode B), so
  //        /tmp/... and project files stay readable (CardTool's documented
  //        "you MUST use absolute paths" contract)
  //   hits → 403 + actionable `reason` (never a 404 disguise)
  //
  // R2 (hard link aliasing) rides on top: (dev,ino) of the known credential
  // files is captured once at startup — realpath cannot see a hard link.
  // ────────────────────────────────────────────────────────────────────────

  /**
   * A1 — the ONLY `PathUtil.dataRoot` subtrees whose contents may be served.
   *
   * Every other entry under the data root is credential-bearing by default:
   * `auth.json` (the gateway token), `nebflow.json`, `secrets/`, `logs/`,
   * `sessions/`, `usage-records/`, … Fail-closed: a new directory added
   * under the data root is refused until it is listed here.
   *
   * 2026-09-16 (img-ticket batch i, #687-A, author ruling — already ruled, not
   * pending): `docs` joins the list. The author's human-deliverable directory
   * (`~/.nebflow/docs/…`) was the one location the delivery convention told
   * people to write to AND this judge refused with `credential-path`; the
   * ruling resolves that contradiction. 🔴 Scope is EXACTLY this one entry:
   * no other subtree, no prefix/glob matching, no change to the `head`
   * exact-match semantics below, and the five shipped entries keep their order
   * and meaning. The ruling explicitly SUPERSEDES the 2026-09-11 "do not widen
   * the credential namespace" ruling — and that supersession is per-item, so
   * it authorizes nothing beyond `docs`. The tool face reads
   * `FileRefs.DataRootServedNamespaces`, which `FileRefsWhitelistSpec` welds to
   * this list item for item.
   */
  val NfDataRootAllowlist: List[String] =
    List("projects", "uploads", "plots", "workspace-items", "voice-models", "docs")

  /**
   * A2 — the ONLY `<workspace>/.nebflow` subtrees whose contents may be served
   * (evidence capture directories: screenshots/logs a node produced).
   */
  val NfWorkspaceAllowlistPrefix: String = "evidence"

  /** P2 — home credential entries (dirs and files) that are refused wholesale. */
  val NfExternalCredentialEntries: List[String] =
    List(".ssh", ".aws", ".gnupg", ".config/gh", ".docker", ".kube", ".netrc", ".git-credentials")

  /**
   * B — pattern reject for paths OUTSIDE the protected namespaces.
   *
   * Mode B is deliberately narrow (basename-shaped), because it must not
   * misfire on ordinary project material: a `/tmp/output.svg` or
   * `/Users/x/project/plot.png` stays readable. It catches the two shapes a
   * credential file reliably has — a private-key/env basename, or a
   * credential-holding directory anywhere in the path.
   */
  val NfCredentialNamePattern: scala.util.matching.Regex =
    """(?i)^(\.?(env|netrc|git-credentials|npmrc|pypirc|pgpass)|id_(rsa|dsa|ecdsa|ed25519)(\.pub)?|auth\.json|credentials(\.json|\.yaml|\.yml|\.txt)?|secrets?(\.json|\.yaml|\.yml|\.txt)?|token|token\.json|.*\.(pem|key|p12|pfx|keystore|jks))$""".r

  /** B — credential-holding directory segments (matched at any depth). */
  val NfCredentialPathSegments: Set[String] =
    Set(".ssh", ".aws", ".gnupg", ".kube", ".docker", ".git-credentials")

  /**
   * The filesystem coordinates the C1 verdict is computed against.
   *
   * `dataRoot` = P1 (`PathUtil.dataRoot`); `workspaceRoot` = P3 = the project's
   * OWN `<workspace>/.nebflow` directory (R1), whose only allowlisted subtree
   * is the `evidence*` subtree; `credentialInodes` = the R2 `(dev,ino)` snapshot.
   *
   * Injectable so the route stays instance-free and unit-testable with a
   * synthesized data root / workspace root / inode set
   * (`NfFileRoutesSpec` / `NfTicketRoutesSpec`), and so the (dev,ino) scan
   * happens exactly once per JVM in production.
   */
  final case class NfPathPolicy(
    dataRoot: java.nio.file.Path,
    workspaceRoot: java.nio.file.Path,
    credentialInodes: Set[String]
  )

  object NfPathPolicy:

    /**
     * Canonicalize if the directory exists (macOS `/var` → `/private/var`,
     * symlinked data roots), else keep the normalized absolute form.
     */
    private[gateway] def canonicalOrSelf(p: java.nio.file.Path): java.nio.file.Path =
      try p.toRealPath()
      catch case _: Throwable => p.toAbsolutePath.normalize()

    /**
     * `fileKey().toString()` is the JVM's portable identity for a file —
     * on POSIX it is `(dev=…,ino=…)`, exactly the R2 key.
     */
    private[gateway] def inodeKey(p: java.nio.file.Path): Option[String] =
      try
        val attrs =
          java.nio.file.Files.readAttributes(p, classOf[java.nio.file.attribute.BasicFileAttributes])
        Option(attrs.fileKey()).map(_.toString)
      catch case _: Throwable => None

    /** Every file under `dir` (depth-capped), best effort. */
    private def filesUnder(dir: java.nio.file.Path, maxDepth: Int): List[java.nio.file.Path] =
      if !java.nio.file.Files.isDirectory(dir) then Nil
      else
        val out = scala.collection.mutable.ListBuffer.empty[java.nio.file.Path]
        try
          val stream = java.nio.file.Files.walk(dir, maxDepth)
          try
            stream.forEach { p =>
              if java.nio.file.Files.isRegularFile(p) then out += p
            }
          finally stream.close()
        catch case _: Throwable => ()
        out.toList

    /**
     * R2: (dev,ino) of the known credential files — data-root config/auth
     * files, everything under `secrets/`, and the whole `~/.ssh` tree. Small
     * by construction (single digits to tens of files).
     */
    private[gateway] def scanCredentialInodes(dataRoot: java.nio.file.Path): Set[String] =
      val home = java.nio.file.Paths.get(sys.props.getOrElse("user.home", "/"))
      val seeds: List[java.nio.file.Path] =
        List(dataRoot.resolve("auth.json"), dataRoot.resolve("nebflow.json")) ++
          filesUnder(dataRoot.resolve("secrets"), 8) ++
          filesUnder(home.resolve(".ssh"), 8)
      seeds.flatMap(inodeKey).toSet

    /**
     * Production policy: the real data root, the PROCESS WORKSPACE's own
     * `.nebflow` directory (= P3, R1: "项目内 `<workspace>/.nebflow`"), and the
     * startup inode snapshot.
     *
     * The workspace namespace is the project's `.nebflow` dir, NOT the
     * workspace/repo root — rooting it at the root classifies every file of
     * the checkout as "the project .nebflow directory" and then denies it
     * (only the `evidence*` subtree is allowlisted), which 403'd every repo file until
     * `tests/smoke.spec.mjs` caught it end to end (2026-09-11, real backend).
     * Rooted at `.nebflow`, repo files fall through to the pattern rules
     * (allowed unless credential-shaped) exactly as R1's P3 intends.
     */
    def standard(): NfPathPolicy =
      val root = canonicalOrSelf(java.nio.file.Paths.get(PathUtil.dataRoot.toString))
      val workspace = canonicalOrSelf(java.nio.file.Paths.get(os.pwd.toString).resolve(".nebflow"))
      NfPathPolicy(root, workspace, scanCredentialInodes(root))

    /** Memoized so the inode scan is a startup cost, not a per-request one. */
    private lazy val standardMemo: NfPathPolicy = standard()

    def memoized(): NfPathPolicy = standardMemo

    /**
     * The policy for the roots that are in force **now**, memoized per root pair
     * (imgref rework r2, 2026-09-18 — the verifier's F1 ②).
     *
     * [[memoized]] freezes the policy at the FIRST call in this JVM. That is the
     * right shape for a process that has exactly one data root for its whole
     * lifetime (production: the root is resolved from `--home` / the environment
     * before the routes are built), but it makes the answer to "would the
     * endpoint serve this path?" depend on **process history** rather than on the
     * file and the roots: a JVM that changes its data root (`PathUtil.setDataRoot`
     * — the test/`--home` redirection every other component follows by using a
     * `def` instead of a `val`, e.g. `paths.scala:223`) would keep judging new
     * files against the OLD root. That is exactly the drift that made
     * `CardModelFaceSpec` green in one run order and red in another.
     *
     * Semantics: re-derive when (and only when) `PathUtil.dataRoot` / the process
     * working directory differ from the pair the cached policy was derived from;
     * the `(dev,ino)` scan therefore stays a once-per-root cost, never a
     * per-call one. Two roots seen in one JVM yield two policies — never one
     * policy wearing the other's roots.
     */
    private val perRootMemo = new java.util.concurrent.ConcurrentHashMap[String, NfPathPolicy]()

    def current(): NfPathPolicy =
      val key = s"${PathUtil.dataRoot.toString}\u0000${os.pwd.toString}"
      perRootMemo.computeIfAbsent(key, _ => standard())

  end NfPathPolicy

  /**
   * Verdict of the shared path judge (C1-5) — the read endpoint and the
   * signing endpoint ask the SAME question through this one function, so a
   * path that cannot be read can never be ticketed.
   */
  enum NfVerdict:
    case Allowed(realPath: java.nio.file.Path, ext: String)
    case Denied(status: Status, reason: String, message: String)

  /**
   * Phase 5 解耦(行为保持重构,2026-09-25):enum 原样迁
   * `nebflow.core.FilePolicyPort.NfDenyLayer`(core 窄端口签名需要该词汇类型;
   * 文档随行)。此处双别名(type + companion term)保持既有引用路径
   * `NfFilePolicy.NfDenyLayer.*`(含通配 import 侧)与逐字行为不变——
   * 判据阶梯本体全部留在本对象,实现不搬。
   */
  type NfDenyLayer = FilePolicyPort.NfDenyLayer
  val NfDenyLayer = FilePolicyPort.NfDenyLayer

  /**
   * C1-4: pure credential-namespace judge. `Some(reason)` = refuse with 403.
   *
   * Projection of [[nfCredentialDenyLayer]] (same branches, same order, same
   * messages) — the two can never disagree.
   */
  def nfCredentialDeny(realPath: java.nio.file.Path, policy: NfPathPolicy): Option[String] =
    nfCredentialDenyLayer(realPath, policy).map(_._2)

  /** [[nfCredentialDeny]] with the refusing layer named (see [[NfDenyLayer]]). */
  def nfCredentialDenyLayer(
    realPath: java.nio.file.Path,
    policy: NfPathPolicy
  ): Option[(NfDenyLayer, String)] =
    val rp = realPath.toAbsolutePath.normalize()
    val p1 = policy.dataRoot
    val p3 = policy.workspaceRoot
    def relUnder(root: java.nio.file.Path): String =
      root
        .relativize(rp)
        .toString
        .replace('\\', '/')
    if rp.startsWith(p1) then
      val rel = relUnder(p1)
      val head = rel.split('/').headOption.getOrElse("")
      if NfDataRootAllowlist.contains(head) then None
      else
        Some(
          NfDenyLayer.Namespace,
          s"the Nebflow data directory is credential-bearing; only " +
            s"${NfDataRootAllowlist.mkString("/**, ", "/**, ", "/**")} may be served"
        )
    else if rp.startsWith(p3) then
      val rel = relUnder(p3)
      val head = rel.split('/').headOption.getOrElse("")
      if head.startsWith(NfWorkspaceAllowlistPrefix) then None
      else
        Some(
          NfDenyLayer.Namespace,
          s"the project .nebflow directory is credential-bearing; only " +
            s"${NfWorkspaceAllowlistPrefix}*/** may be served"
        )
    else
      val home = java.nio.file.Paths.get(sys.props.getOrElse("user.home", "/")).toAbsolutePath.normalize()
      val homeRel =
        if rp.startsWith(home) then Some(home.relativize(rp).toString.replace('\\', '/')) else None
      val externalHit = homeRel.exists { rel =>
        val norm = rel.stripPrefix("./")
        NfExternalCredentialEntries.exists(entry => norm == entry || norm.startsWith(entry + "/"))
      }
      if externalHit then Some((NfDenyLayer.Credential, "this path is a known credential location"))
      else
        val segments = (0 until rp.getNameCount).map(i => rp.getName(i).toString).toArray
        val basename = Option(rp.getFileName).map(_.toString).getOrElse("")
        if segments.exists(NfCredentialPathSegments.contains) then
          Some((NfDenyLayer.Credential, "this path traverses a credential directory"))
        else if NfCredentialNamePattern.matches(basename) then
          Some((NfDenyLayer.Credential, "this filename is a known credential shape"))
        else None

    end if

  end nfCredentialDenyLayer

  /**
   * C1-5: the single authority for "may this raw path be served?".
   *
   * Chain (plan §3.3): empty → `~` expansion → lexical normalize →
   * exists/isRegularFile (404) → toRealPath (404) → R2 hard-link inode (403)
   * → `nfCredentialDeny` (403) → extension taken from the REAL path (400).
   * The extension verdict deliberately uses the realpath: a client can no
   * longer pick the served extension by naming a symlink (C1-2).
   */
  def nfFileVerdict(
    rawPath: String,
    policy: NfPathPolicy
  ): IO[NfVerdict] =
    if rawPath.trim.isEmpty then
      IO.pure(NfVerdict.Denied(Status.BadRequest, "missing-path", "Missing 'path' parameter"))
    else
      IO.blocking {
        val expanded = PathUtil.expandTilde(rawPath)
        val lexical = java.nio.file.Paths.get(expanded).normalize()
        if !java.nio.file.Files.exists(lexical) || !java.nio.file.Files.isRegularFile(lexical) then
          NfVerdict.Denied(
            Status.NotFound,
            "not-found",
            s"no regular file at $lexical"
          )
        else
          val realTry =
            try Right(lexical.toRealPath())
            catch case e: Throwable => Left(e)
          realTry match
            case Left(e) =>
              NfVerdict.Denied(Status.NotFound, "not-found", s"path could not be resolved: ${e.getMessage}")
            case Right(real) =>
              nfVerdictForReal(real, policy) match
                case Some(d) => d
                case None =>
                  val ext = nfRealExtension(real)
                  NfVerdict.Allowed(real, ext)
        end if
      }

  /**
   * The extension a real path is served under — taken from the REAL path, never
   * from the name the client wrote (C1-2: a client can no longer pick the
   * served extension by naming a symlink).
   */
  private def nfRealExtension(real: java.nio.file.Path): String =
    val name = real.toString
    name.lastIndexOf('.') match
      case -1 => ""
      case i => name.substring(i + 1).toLowerCase

  /**
   * R2 as a question a caller can ask on its own: `true` = this real path IS
   * one of the policy's credential inodes (a credential file, or a hard link
   * that shares its inode). Public (imgref rework r2) because the endpoint's
   * ladder SHORT-CIRCUITS at the credential step — a namespace refusal can hide
   * a credential inode — and a caller that honours only some layers must be able
   * to ask the inode layer directly instead of re-implementing it. Same snapshot,
   * same helper: one judgement, no copy.
   */
  def nfCredentialInode(real: java.nio.file.Path, policy: NfPathPolicy): Boolean =
    NfPathPolicy.inodeKey(real).exists(policy.credentialInodes.contains)

  /**
   * The post-`toRealPath` half of [[nfFileVerdict]] — every step that decides on
   * the REAL path, in the shipped order: `nfCredentialDeny` (403 credential-path)
   * → R2 hard-link inode (403 credential-hardlink) → extension from the realpath
   * (400 file-type). `None` = the endpoint would SERVE this real path.
   *
   * Extracted verbatim (imgref rework r1, 2026-09-18) so that the tool-side
   * pre-flight gate (`FileRefs.servableByEndpoint`) and the endpoint ask EXACTLY
   * the same question by calling the same function. Before this extraction the
   * gate reused only `nfCredentialDeny` and therefore went green on a hard link
   * to a credential file and on a symlink whose realpath extension is not
   * served — both of which the endpoint refuses (`proxied` green while the
   * browser's fetch answered 401: the author's failure ② shape). One function,
   * no copy, no parallel judge.
   *
   * Order is part of the contract: a credential file named directly (or reached
   * through a symlink) must report `credential-path`, not the alias-specific
   * `credential-hardlink`.
   *
   * 🔴 Short-circuit (read this before relying on the layer): the credential
   * step runs FIRST, so a path that is refused for the namespace reason can also
   * be a hard link to a credential file without the inode step ever being asked
   * — a caller that honours only some layers must ask the inode layer itself
   * (see `FileRefs.credentialInodeClean`).
   */
  def nfVerdictForReal(real: java.nio.file.Path, policy: NfPathPolicy): Option[NfVerdict.Denied] =
    nfVerdictForRealLayer(real, policy).map(_._2)

  /**
   * [[nfVerdictForReal]] with the refusing layer named — same order, same
   * reasons, same messages (the entry point above is this function's
   * projection). Extracted in the imgref rework r2 so that a caller which must
   * honour only SOME layers (the inline `data:` leg honours the identity layers
   * and not the reach layer — see `FileRefs.inlineMayTakeOver`) reads the layer
   * from the single source instead of re-implementing the ladder.
   */
  def nfVerdictForRealLayer(
    real: java.nio.file.Path,
    policy: NfPathPolicy
  ): Option[(NfDenyLayer, NfVerdict.Denied)] =
    nfCredentialDenyLayer(real, policy) match
      case Some((layer, reason)) =>
        Some((layer, NfVerdict.Denied(Status.Forbidden, "credential-path", reason)))
      case None =>
        if nfCredentialInode(real, policy) then
          Some(
            (
              NfDenyLayer.CredentialInode,
              NfVerdict.Denied(
                Status.Forbidden,
                "credential-hardlink",
                "this file is a hard link to a Nebflow credential file"
              )
            )
          )
        else if !NfFileAllowedExt.contains(nfRealExtension(real)) then
          Some((NfDenyLayer.FileType, NfVerdict.Denied(Status.BadRequest, "file-type", "File type not allowed")))
        else None

  /**
   * The URL-form-tolerant facade over [[nfFileVerdict]] (imgref batch,
   * 2026-09-18 作者令).
   *
   * 作者实证：路径含空格时 ① `%20`/`+` 编码形态被按**字面**去找 ⇒ `not-found`；
   * ② 工具回包计数绿而前端取回腿红 —— 因为工具发的是 `URLEncoder` 的 form 形态
   * （空格 → `+`），而**取回腿**把 `+` 当成字面加号去找一个不存在的文件。
   *
   * 判据：候选形态按 [[nebflow.core.PathParamCodec.candidates]] 的次序（原样 →
   * `%`-解码 → `+`-折成空格）逐个过**同一个** [[nfFileVerdict]]；只有 `not-found`
   * 才落到下一个形态 —— 一个真的存在的文件（包括文件名里真带 `+` 的）永远在原样
   * 形态就命中，所以既有全绿面逐字不动。
   *
   * 🔴 **权限面零让步**：每个候选形态过的是同一份 `nfFileVerdict`（词法归一 →
   * exists/isRegularFile → toRealPath → R2 inode → credential namespace → realpath
   * 上的扩展名），判据全作用在 **realpath** 上 ⇒ 变形形态与直接写入的形态得到同一个
   * realpath、同一份判据，造不出「原串判不住、变形后判得住」的穿透。策略表
   * （`NfDataRootAllowlist` / `NfWorkspaceAllowlistPrefix` / `NfExternalCredentialEntries`
   * / `NfCredentialPathSegments` / `NfCredentialNamePattern`）**本批零改动**。
   *
   * 票据腿同用此函数（`POST /api/nf-ticket` 与 `GET /api/nf-file` 一条口径），所以
   * 铸票用的 realpath 与取回时解出的 realpath 必然一致。
   */
  def nfFileVerdictTolerant(rawPath: String, policy: NfPathPolicy): IO[NfVerdict] =
    def go(rest: List[String], firstNotFound: Option[NfVerdict]): IO[NfVerdict] =
      rest match
        case Nil =>
          IO.pure(
            firstNotFound
              .getOrElse(NfVerdict.Denied(Status.BadRequest, "missing-path", "Missing 'path' parameter"))
          )
        case form :: tail =>
          nfFileVerdict(form, policy).flatMap {
            // Only "the path does not exist" falls through to the next form. A
            // refusal (credential-path / credential-hardlink / file-type) is
            // final — decoding must never shop for a form that gets past a
            // judgement the raw form already failed.
            case d @ NfVerdict.Denied(_, "not-found", _) if tail.nonEmpty => go(tail, firstNotFound.orElse(Some(d)))
            case v => IO.pure(v)
          }
    go(nebflow.core.PathParamCodec.candidates(rawPath), None)

  end nfFileVerdictTolerant

  /**
   * Phase 5 窄端口实现(core ← gateway 倒置):core 工具面(FileRefs)经
   * `nebflow.core.FilePolicyPort` 问的两个问题,判据 = 本对象的同一套函数
   * (零复制、零旁路);policy 自取 `NfPathPolicy.current()`(在役根,不冻结)。
   */
  override def endpointVerdictLayer(
    real: java.nio.file.Path
  ): Option[(NfDenyLayer, String, String)] =
    nfVerdictForRealLayer(real, NfPathPolicy.current())
      .map((layer, denied) => (layer, denied.reason, denied.message))

  override def credentialInodeHit(real: java.nio.file.Path): Boolean =
    nfCredentialInode(real, NfPathPolicy.current())

end NfFilePolicy
