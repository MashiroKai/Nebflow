package nebflow.core.wiki

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NeblinkServicePort
import nebflow.core.project.{ProjectDef, ProjectOrigin, ProjectStore}
import nebflow.core.tools.RemoteExecutor
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.collection.mutable.ListBuffer

/**
 * Wiki graph service — turns every registered project's `docs/` vault
 * (Obsidian-compatible) into per-project document graphs (#744-B multi-vault).
 *
 * Vault registration is DISCOVERY-BASED, zero-config (#744-B 设计 §1): a
 * project's vault is always `<workspace>/docs` (the wiki-docs SKILL convention)
 * — mounting the project registers its vault; unmount/archive hides it
 * (`ProjectStore.list` is the single source). Sub-project hierarchy is
 * convention-based too: any direct child `SUB/docs` directory inside a vault is
 * a sub
 * vault (recursive, depth-capped), discovered at scan time — no new fields on
 * project.json.
 *
 * Home-awareness: projects live under the data root, but a project's
 * `workspace` is an absolute path anywhere on disk — the vault is always
 * `<workspace>/docs`, never assumed to sit under `.nebflow`.
 *
 * Cross-device: a project whose `origin.device` points at a peer is scanned
 * ON the peer over the existing `FileTransfer scope=browse` channel
 * (`RemoteExecutor.transferListScoped` / `transferGetScoped` — P2P first,
 * relay fallback; the peer's own egress whitelist already admits its
 * registered project workspaces). A peer that is offline or refuses degrades
 * that project to a single stub node — visible in the graph, never silent.
 * Remote sub-vault detection is best-effort one level (list the vault root's
 * dirs, probe `<sub>/docs`); a failed probe means "no sub vault", never an error.
 *
 * Data isolation (#744-B 设计 §2): every vault scans into its own
 * `VaultScan`, cached under its own key (`merged` | `p:<project>` |
 * `p:<project>|v:<vault-rel>`) for [[CacheTtlMs]] so rapid tab/switcher
 * changes do not re-walk vaults. `graph(None)` keeps the pre-#744-B merged
 * response byte-for-byte. Cross-vault links resolve to `external=true`
 * placeholders — counted in stats, never drawn, never broken-link entries.
 * `project="__user__"` is the C-batch user-history-graph reserved value and
 * returns an empty view placeholder until that batch lands.
 */
class WikiService(neblinkService: Option[NeblinkServicePort] = None):

  private val logger = NebflowLogger.forName("nebflow.wiki")

  private val CacheTtlMs = 30_000L

  /** Remote per-vault fetch caps — bounded work per graph build. */
  val MaxRemoteFiles = 300
  val MaxRemoteAttachments = 200
  val MaxRemoteDocBytes = WikiIndex.MaxDocBytes

  /** Sub-vault discovery budgets (#744-B) — a runaway nesting must not stall
    * a graph request. Depth counts nested `SUB/docs` levels BELOW the root. */
  val MaxSubVaultDepth = 3
  val MaxSubVaultsPerVault = 32
  val MaxRemoteSubVaultProbe = 16

  /** Binary viewer extensions — remote-doc returns base64 for these so the
    * frontend can open them from a blob URL (literature PDFs, figures). */
  private val BinaryDocExtensions: Set[String] = Set(
    ".pdf", ".docx", ".pptx", ".xlsx", ".epub", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".mp4", ".webm", ".mov", ".mp3", ".wav"
  )

  private val cacheState =
    new java.util.concurrent.atomic.AtomicReference[Map[String, (Long, Json)]](Map.empty)
  private val building = new java.util.concurrent.atomic.AtomicBoolean(false)

  // ── Public surface ───────────────────────────────────────────────

  /**
   * Graph for one view, TTL-cached per view key:
   *   `project = None`                      → merged graph over every project's ROOT vault
   *                                             (pre-#744-B response shape, byte-compatible);
   *   `project = Some(name), vault = None`  → that project's root vault + childVaults;
   *   `project = Some(name), vault = Some(rel)` → drill-in to a sub vault (breadcrumb back);
   *   `project = Some("__user__")`          → C-batch reserved placeholder (empty view).
   * Concurrent cache misses are serialized with an atomic builder flag — a loser
   * keeps its previous snapshot for that view (or an empty shell on the first
   * race); the UI refresh re-issues.
   */
  def graph(project: Option[String] = None, vault: Option[String] = None, force: Boolean = false): IO[Json] =
    val key = cacheKey(project, vault)
    IO(System.currentTimeMillis()).flatMap { now =>
      val cache = cacheState.get()
      cache.get(key) match
        case Some((at, json)) if !force && now - at < CacheTtlMs => IO.pure(json)
        case _ if !building.compareAndSet(false, true) =>
          // Another build is in flight — serve the previous snapshot of THIS
          // view (or an empty shell on the very first race); the UI re-issues.
          IO.pure(cache.get(key).map(_._2).getOrElse(emptyView(project, vault)))
        case _ =>
          build(project, vault)
            .map { json =>
              cacheState.set(cacheState.get() + (key -> (System.currentTimeMillis(), json)))
              json
            }
            .handleErrorWith(e =>
              IO(logger.errorSync(s"wiki: graph build failed (${key}): ${msgOf(e)}")) *>
                IO.pure(emptyView(project, vault))
            )
            .guarantee(IO(building.set(false)))
    }

  /** Create `<workspace>/docs/attachments` for a project (idempotent). */
  def ensureVault(project: String): IO[Either[String, Json]] =
    ProjectStore.load(project).flatMap {
      case None => IO.pure(Left(s"project '$project' not found"))
      case Some(pd) if !ProjectOrigin.isSelfLiteral(deviceOf(pd)) =>
        IO.pure(Left(s"project '$project' lives on device '${deviceOf(pd)}' — create the vault on that device"))
      case Some(pd) =>
        val vault = workspacePath(pd) / WikiIndex.VaultDirName
        IO.blocking {
          val existed = os.exists(vault)
          os.makeDir.all(vault / WikiIndex.AttachmentsDirName)
          Right(
            Json.obj(
              "project" -> project.asJson,
              "vault" -> vault.toString.asJson,
              "existed" -> existed.asJson
            )
          )
        }.handleErrorWith(e => IO.pure(Left(msgOf(e))))
    }

  /**
   * Lightweight vault manifest for the panel switcher (#744-B): one entry per
   * registered project with its vault path and (local) presence — NO content
   * scan, so the switcher can render before any graph is built.
   */
  def vaults: IO[Json] =
    ProjectStore.list().flatMap { defs =>
      defs.traverse { pd =>
        val device = deviceOf(pd)
        if ProjectOrigin.isSelfLiteral(device) then
          IO.blocking {
            val ws = workspacePath(pd)
            Json.obj(
              "project" -> pd.name.asJson,
              "device" -> device.asJson,
              "workspace" -> ws.toString.asJson,
              "vault" -> (ws / WikiIndex.VaultDirName).toString.asJson,
              "present" -> os.isDir(ws / WikiIndex.VaultDirName).asJson
            )
          }
        else
          IO.pure(
            Json.obj(
              "project" -> pd.name.asJson,
              "device" -> device.asJson,
              "workspace" -> pd.origin.map(_.path).getOrElse(pd.workspace).asJson,
              "vault" -> joinRemote(pd.origin.map(_.path).getOrElse(pd.workspace), WikiIndex.VaultDirName).asJson,
              "present" -> Json.Null // presence unknown without a peer round-trip
            )
          )
      }.map(entries => Json.obj("vaults" -> entries.asJson))
    }

  // ── View dispatch ────────────────────────────────────────────────

  private def cacheKey(project: Option[String], vault: Option[String]): String =
    project match
      case None                         => "merged"
      case Some(p) if p == UserHistoryProject => "user-history"
      case Some(p)                      => vault.fold(s"p:$p")(v => s"p:$p|v:$v")

  /** C-batch reserved project value — user-history graph lands there later. */
  val UserHistoryProject = "__user__"

  private def build(project: Option[String], vault: Option[String]): IO[Json] =
    project match
      case Some(p) if p == UserHistoryProject => IO.pure(userHistoryPlaceholder)
      case Some(p) =>
        ProjectStore.load(p).flatMap {
          case None => IO.pure(notFoundView(p, vault))
          case Some(pd) =>
            vault match
              case None => buildProjectRootView(pd)
              case Some(rel) if !isSafeVaultRel(rel) => IO.pure(badVaultView(p, rel))
              case Some(rel) => buildVaultView(pd, rel)
        }
      case None => buildMerged

  /** Vault-rel whitelist: forward-slash segments, no "..", no empties, no abs. */
  private def isSafeVaultRel(rel: String): Boolean =
    rel.nonEmpty && !rel.startsWith("/") && !rel.startsWith("\\") &&
      rel.split('/').forall(s => s.nonEmpty && s != "." && s != "..")

  /** Segments of a root-vault-relative vault rel BELOW the root vault:
    * "docs" → Nil, "sub/docs" → Vector("sub", "docs"), "a/docs/b/docs" →
    * Vector("a", "docs", "b", "docs"). Single join point for the "docs" root
    * segment (folder for vault paths, string namespace for rels elsewhere). */
  private def vaultRelSegments(rel: String): Vector[String] =
    val r = if rel == WikiIndex.VaultDirName then "" else rel.stripPrefix(WikiIndex.VaultDirName + "/")
    r.split('/').filter(_.nonEmpty).toVector

  // ── Merged view (pre-#744-B shape, byte-compatible) ──────────────

  private def buildMerged: IO[Json] =
    ProjectStore.list().flatMap { defs =>
      // Local projects scan first (fast, direct disk); remote projects after —
      // remote legs are network calls and each is individually fault-isolated.
      val local = defs.filter(pd => ProjectOrigin.isSelfLiteral(deviceOf(pd)))
      val remote = defs.filterNot(pd => ProjectOrigin.isSelfLiteral(deviceOf(pd)))
      local.traverse(scanLocalRootProject).flatMap { localScans =>
        remote.traverse(scanRemoteRootProject).map { remoteScans =>
          assembleMerged(localScans ++ remoteScans)
        }
      }
    }

  private def assembleMerged(scans: List[ProjectScan]): Json =
    val nodes = scans.flatMap(s => s.scan.nodes.map(WikiIndex.nodeJson))
    val edges = scans.flatMap(s => s.scan.edges.map(WikiIndex.edgeJson))
    Json.obj(
      "generatedAt" -> System.currentTimeMillis().asJson,
      "projects" -> scans
        .map { s =>
          Json.obj(
            "name" -> s.name.asJson,
            "device" -> s.device.asJson,
            "workspace" -> s.workspace.asJson,
            "vault" -> s.vault.asJson,
            "docCount" -> s.scan.nodes.count(n => !n.stub).asJson,
            "error" -> s.error.map(_.asJson).getOrElse(Json.Null),
            "stub" -> s.stubProject.asJson
          )
        }
        .asJson,
      "nodes" -> nodes.asJson,
      "edges" -> edges.asJson
    )

  // ── Per-project views (#744-B) ───────────────────────────────────

  /** Root-vault view of one project: its own scan + childVaults aggregates. */
  private def buildProjectRootView(pd: ProjectDef): IO[Json] =
    buildVaultView(pd, WikiIndex.VaultDirName)

  /**
   * One vault's isolated graph. Scans THIS vault plus (for external
   * classification) the rel sets of the project's OTHER vaults — the other
   * vaults are LISTED, not parsed (only this vault's documents are read).
   */
  private def buildVaultView(pd: ProjectDef, vaultRel: String): IO[Json] =
    if ProjectOrigin.isSelfLiteral(deviceOf(pd)) then buildLocalVaultView(pd, vaultRel)
    else buildRemoteVaultView(pd, vaultRel)

  private def buildLocalVaultView(pd: ProjectDef, vaultRel: String): IO[Json] =
    val device = deviceOf(pd)
    val ws = workspacePath(pd)
    IO.blocking {
      // vaultRel namespace = ROOT-VAULT-relative ("docs" = the root vault,
      // "sub/docs" = <workspace>/docs/sub/docs — a sub vault lives INSIDE the
      // root vault, #744-B 设计 §1). Segments below the root vault (the root
      // segment itself is already folded in), each validated no-'/' by /.
      val rootVault = ws / WikiIndex.VaultDirName
      val vault = vaultRelSegments(vaultRel).foldLeft(rootVault)(_ / _)
      // Sub-vault aggregates: rels are ROOT-vault-relative; the direct children
      // of THIS vault are the ones exactly one `<sub>/docs` pair deeper
      // (root "docs" is itself the pair's tail segment, so its children sit at
      // below-depth 2; deeper levels add 2 per hop).
      val allChildren =
        if os.isDir(ws / WikiIndex.VaultDirName) then discoverLocalSubVaults(ws / WikiIndex.VaultDirName)
        else Nil
      val below = vaultRelSegments(vaultRel)
      val children = allChildren.filter { c =>
        val cBelow = vaultRelSegments(c.rel)
        cBelow.length == below.length + 2 && cBelow.startsWith(below)
      }
      // Sibling/ancestor rel sets for external classification (listing only —
      // only THIS vault's documents are parsed). srel is root-vault-relative.
      val siblingRels = allLocalVaultRels(ws)
        .filterNot(_ == vaultRel)
        .flatMap { srel =>
          val sv = vaultRelSegments(srel).foldLeft(rootVault)(_ / _)
          val rels = listVaultFiles(sv, WikiIndex.DocExtensions)
            .take(WikiIndex.MaxFilesPerVault)
            .map(_.relativeTo(sv).toString.replace('\\', '/'))
          rels ++ rels.map(r => s"$srel/$r")
        }
        .toSet
      if !os.isDir(vault) then
        Json.obj(
          "generatedAt" -> System.currentTimeMillis().asJson,
          "view" -> viewJson(pd, vaultRel),
          "projects" -> Json.arr(),
          "childVaults" -> Json.arr(),
          "nodes" -> Json.arr(),
          "edges" -> Json.arr(),
          "stats" -> zeroStats.asJson,
          "error" -> s"vault '$vaultRel' not found".asJson,
          "stub" -> true.asJson
        )
      else
        // Prune THIS view's child vault roots from the walk (relativized to
        // the vault being scanned; root children carry no "docs/" prefix, so
        // stripPrefix is a no-op there and the root-relative rel is right).
        val skipRels = children.map(_.rel.stripPrefix(vaultRel + "/")).toSet
        val scan = scanLocalVaultDir(pd.name, device, vault, vaultRel, skipRels)
        val classified = WikiIndex.classifyExternals(scan, siblingRels)
        assembleVaultView(pd, vaultRel, classified, children, None)
    }.handleErrorWith(e =>
      // Swallowed errors must stay visible: the degraded view carries the
      // message in its `error` field, the log keeps the stack face.
      IO(logger.errorSync(s"wiki: local vault view '${pd.name}/$vaultRel' degraded: ${msgOf(e)}")) *>
        IO.pure(vaultErrorView(pd, vaultRel, msgOf(e)))
    )

  private def buildRemoteVaultView(pd: ProjectDef, vaultRel: String): IO[Json] =
    val wsPath = pd.origin.map(_.path).getOrElse(pd.workspace)
    // vaultRel is root-vault-relative (same namespace as the local face):
    // "docs" = <origin>/docs, "sub/docs" = <origin>/docs/sub/docs.
    val vaultPath =
      if vaultRel == WikiIndex.VaultDirName then joinRemote(wsPath, WikiIndex.VaultDirName)
      else joinRemote(joinRemote(wsPath, WikiIndex.VaultDirName), vaultRel)
    // Best-effort one-level child-vault probe (design §1); a failed probe means
    // "no sub vaults" — never an error.
    val childrenIO: IO[List[WikiIndex.ChildVault]] =
      if vaultRel != WikiIndex.VaultDirName then IO.pure(Nil)
      else probeRemoteSubVaults(deviceOf(pd), vaultPath)
    scanRemoteVault(pd, vaultRel, vaultPath).flatMap { case (scan, scanErr) =>
      childrenIO.map { children =>
        assembleVaultView(pd, vaultRel, scan, children, scanErr)
      }
    }

  private def assembleVaultView(
    pd: ProjectDef,
    vaultRel: String,
    scan: WikiIndex.VaultScan,
    children: List[WikiIndex.ChildVault],
    error: Option[String]
  ): Json =
    val isRoot = vaultRel == WikiIndex.VaultDirName
    Json.obj(
      "generatedAt" -> System.currentTimeMillis().asJson,
      "view" -> viewJson(pd, vaultRel),
      "projects" -> Json.arr(
        Json.obj(
          "name" -> pd.name.asJson,
          "device" -> deviceOf(pd).asJson,
          "workspace" -> pd.workspace.asJson,
          "vault" -> vaultRel.asJson,
          "docCount" -> scan.nodes.count(n => !n.stub).asJson,
          "error" -> error.map(_.asJson).getOrElse(Json.Null),
          "stub" -> false.asJson
        )
      ),
      "childVaults" -> children.map(c => Json.obj(
        "name" -> c.name.asJson,
        "rel" -> c.rel.asJson,
        "docCount" -> c.docCount.asJson
      )).asJson,
      "nodes" -> scan.nodes.map(WikiIndex.nodeJson).asJson,
      "edges" -> scan.edges.map(WikiIndex.edgeJson).asJson,
      "stats" -> statsJson(scan).asJson,
      "error" -> error.map(_.asJson).getOrElse(Json.Null),
      "stub" -> false.asJson,
      // Merged-view consumers ignore these; the single-view panel reads them.
      "workspace" -> pd.workspace.asJson,
      "vault" -> vaultRel.asJson,
      "rootVault" -> isRoot.asJson
    )

  private def statsJson(scan: WikiIndex.VaultScan): Json =
    val externalNodes = scan.nodes.filter(_.external).map(_.id).toSet
    val externalEdges = scan.edges.count(e => externalNodes.contains(e.to))
    val totalEdges = scan.edges.length
    Json.obj(
      "docs" -> scan.nodes.count(n => !n.stub).asJson,
      "attachments" -> scan.nodes.count(n => !n.stub && n.kind == "attachment").asJson,
      "edges" -> (totalEdges - externalEdges).asJson,
      "unresolved" -> scan.edges.count(e => !externalNodes.contains(e.to) && scan.nodes.exists(n => n.id == e.to && n.stub)).asJson,
      "crossVault" -> externalEdges.asJson
    )

  private val zeroStats: Json = Json.obj(
    "docs" -> 0.asJson, "attachments" -> 0.asJson, "edges" -> 0.asJson,
    "unresolved" -> 0.asJson, "crossVault" -> 0.asJson
  )

  private def viewJson(pd: ProjectDef, vaultRel: String): Json =
    Json.obj(
      "project" -> pd.name.asJson,
      "vault" -> vaultRel.asJson,
      "breadcrumb" -> breadcrumbOf(pd.name, vaultRel).asJson
    )

  /** Root "docs" + one entry per nested sub vault, outermost first. */
  private def breadcrumbOf(project: String, vaultRel: String): List[Json] =
    val segs = vaultRel.split('/').toList
    // segs = "docs" or "sub1" "docs" "sub2" "docs" ... — pairs of (subName, docs).
    val crumbs = ListBuffer(Json.obj("name" -> project.asJson, "vault" -> WikiIndex.VaultDirName.asJson))
    var i = 0
    var acc = ListBuffer.empty[String]
    while i + 1 < segs.length do
      acc += segs(i)
      crumbs += Json.obj("name" -> segs(i).asJson, "vault" -> (acc.toList :+ WikiIndex.VaultDirName).mkString("/").asJson)
      i += 2
    crumbs.toList

  private def vaultErrorView(pd: ProjectDef, vaultRel: String, err: String): Json =
    Json.obj(
      "generatedAt" -> System.currentTimeMillis().asJson,
      "view" -> viewJson(pd, vaultRel),
      "projects" -> Json.arr(),
      "childVaults" -> Json.arr(),
      "nodes" -> Json.arr(),
      "edges" -> Json.arr(),
      "stats" -> zeroStats.asJson,
      "error" -> err.asJson,
      "stub" -> true.asJson
    )

  private def notFoundView(project: String, vault: Option[String]): Json =
    Json.obj(
      "generatedAt" -> System.currentTimeMillis().asJson,
      "view" -> Json.obj(
        "project" -> project.asJson,
        "vault" -> vault.getOrElse(WikiIndex.VaultDirName).asJson,
        "breadcrumb" -> Json.arr()
      ),
      "projects" -> Json.arr(),
      "childVaults" -> Json.arr(),
      "nodes" -> Json.arr(),
      "edges" -> Json.arr(),
      "stats" -> zeroStats.asJson,
      "error" -> s"project '$project' not found".asJson,
      "stub" -> true.asJson
    )

  private def badVaultView(project: String, rel: String): Json =
    Json.obj(
      "generatedAt" -> System.currentTimeMillis().asJson,
      "view" -> Json.obj(
        "project" -> project.asJson,
        "vault" -> rel.asJson,
        "breadcrumb" -> Json.arr()
      ),
      "projects" -> Json.arr(),
      "childVaults" -> Json.arr(),
      "nodes" -> Json.arr(),
      "edges" -> Json.arr(),
      "stats" -> zeroStats.asJson,
      "error" -> s"invalid vault path '$rel'".asJson,
      "stub" -> true.asJson
    )

  private val userHistoryPlaceholder: Json = Json.obj(
    "generatedAt" -> System.currentTimeMillis().asJson,
    "view" -> Json.obj(
      "project" -> UserHistoryProject.asJson,
      "vault" -> Json.Null,
      "breadcrumb" -> Json.arr()
    ),
    "projects" -> Json.arr(),
    "childVaults" -> Json.arr(),
    "nodes" -> Json.arr(),
    "edges" -> Json.arr(),
    "stats" -> zeroStats.asJson,
    "error" -> Json.Null,
    "stub" -> false.asJson,
    "reserved" -> true.asJson // C-batch hook: user-history graph plugs in here
  )

  private def emptyView(project: Option[String], vault: Option[String]): Json =
    Json.obj(
      "generatedAt" -> System.currentTimeMillis().asJson,
      "view" -> Json.obj(
        "project" -> project.map(_.asJson).getOrElse(Json.Null),
        "vault" -> vault.map(_.asJson).getOrElse(Json.Null),
        "breadcrumb" -> Json.arr()
      ),
      "projects" -> Json.arr(),
      "childVaults" -> Json.arr(),
      "nodes" -> Json.arr(),
      "edges" -> Json.arr()
    )

  // ── Local vault scanning ─────────────────────────────────────────

  private def deviceOf(pd: ProjectDef): String =
    pd.origin.map(_.device).getOrElse(ProjectOrigin.Self)

  private def workspacePath(pd: ProjectDef): os.Path =
    os.Path(pd.workspace, PathUtil.dataRoot)

  final case class ProjectScan(
    name: String,
    device: String,
    workspace: String,
    vault: String,
    scan: WikiIndex.VaultScan,
    error: Option[String],
    stubProject: Boolean
  )

  private def scanLocalRootProject(pd: ProjectDef): IO[ProjectScan] =
    val device = deviceOf(pd)
    val ws = workspacePath(pd)
    val vault = ws / WikiIndex.VaultDirName
    IO.blocking {
      if !os.isDir(vault) then
        ProjectScan(pd.name, device, ws.toString(), vault.toString(), emptyStubScan(pd.name, device), None, stubProject = true)
      else
        // Root vault scan prunes discovered sub-vault dirs (they are separate graphs).
        val subRels = discoverLocalSubVaults(vault).map(_.rel).toSet
        val scan = scanLocalVaultDir(pd.name, device, vault, WikiIndex.VaultDirName, subRels)
        ProjectScan(pd.name, device, ws.toString(), vault.toString(), scan, None, stubProject = false)
    }.handleErrorWith(e =>
      IO.pure(ProjectScan(pd.name, device, ws.toString(), vault.toString(), emptyStubScan(pd.name, device), Some(msgOf(e)), stubProject = true))
    )

  /**
   * Scan ONE local vault directory into a VaultScan. `skipRels` = vault-relative
   * dir paths that are sub-vault roots — pruned from the walk so a parent
   * graph never absorbs its children's documents (per-vault isolation).
   */
  private def scanLocalVaultDir(
    project: String,
    device: String,
    vault: os.Path,
    vaultRel: String,
    skipRels: Set[String]
  ): WikiIndex.VaultScan =
    val docs = ListBuffer.empty[(String, String)]
    val warnings = ListBuffer.empty[String]
    listVaultFiles(vault, WikiIndex.DocExtensions, skipRels).foreach { f =>
      val rel = f.relativeTo(vault).toString.replace('\\', '/')
      if os.size(f) > WikiIndex.MaxDocBytes then warnings += s"$rel exceeds ${WikiIndex.MaxDocBytes} bytes — skipped"
      else
        try docs += ((rel, os.read(f)))
        catch case e: Exception => warnings += s"$rel unreadable: ${msgOf(e)}"
    }
    // Attachments: rel paths only — literature PDFs, figures, datasets.
    // Content is never read here; the canvas opens them on click.
    val attachments = listVaultFiles(vault, WikiIndex.AttachmentExtensions, skipRels)
      .map(_.relativeTo(vault).toString.replace('\\', '/'))
      .take(WikiIndex.MaxFilesPerVault)
    WikiIndex.buildVaultScan(project, device, docs.toList, attachments.toList)

  /**
   * Discover sub-vaults (`<name>/docs/` direct children, recursively) of a
   * LOCAL vault. Bounded by [[MaxSubVaultDepth]] / [[MaxSubVaultsPerVault]];
   * `rel`s are relative to the ROOT vault ("sub/docs", "a/docs/b/docs", …).
   */
  private def discoverLocalSubVaults(rootVault: os.Path): List[WikiIndex.ChildVault] =
    val out = ListBuffer.empty[WikiIndex.ChildVault]
    def walk(vault: os.Path, prefix: List[String], depth: Int): Unit =
      if depth < MaxSubVaultDepth && out.sizeIs < MaxSubVaultsPerVault && os.isDir(vault) then
        os.list(vault)
          .filter(p => os.isDir(p) && !os.isLink(p))
          .foreach { sub =>
            val subDocs = sub / WikiIndex.VaultDirName
            if os.isDir(subDocs) && out.sizeIs < MaxSubVaultsPerVault then
              val rel = (prefix :+ sub.last :+ WikiIndex.VaultDirName).mkString("/")
              val docCount = listVaultFiles(subDocs, WikiIndex.DocExtensions, Set.empty).length
              out += WikiIndex.ChildVault(name = sub.last, rel = rel, docCount = docCount)
              walk(subDocs, prefix :+ sub.last :+ WikiIndex.VaultDirName, depth + 1)
          }
    walk(rootVault, Nil, 0)
    out.toList

  /** Every vault rel of a local workspace (root + sub vaults) — listing only. */
  private def allLocalVaultRels(ws: os.Path): List[String] =
    val root = ws / WikiIndex.VaultDirName
    if !os.isDir(root) then Nil
    else WikiIndex.VaultDirName +: discoverLocalSubVaults(root).map(_.rel)

  // ── Remote vault scanning ────────────────────────────────────────

  /**
   * Remote ROOT vault scan: list the peer's `<workspace>/docs` (plus its
   * `attachments/` folder, best-effort), then pull each document through the
   * scoped browse channel and parse it locally. Any failure (peer offline,
   * refused, egress-denied) degrades to a stub project node.
   */
  private def scanRemoteRootProject(pd: ProjectDef): IO[ProjectScan] =
    val device = deviceOf(pd)
    val wsPath = pd.origin.map(_.path).getOrElse(pd.workspace)
    val vaultPath = joinRemote(wsPath, WikiIndex.VaultDirName)
    RemoteExecutor.current match
      case None =>
        IO.pure(ProjectScan(pd.name, device, wsPath, vaultPath, emptyStubScan(pd.name, device), Some("peer channel not initialized"), stubProject = true))
      case Some(executor) =>
        val scan = for
          peers <- peers
          peer <- IO.fromOption(RemoteExecutor.resolvePeer(device, peers).toOption)(
            new Exception(s"peer '$device' not found — device offline or unpaired")
          )
          listed <- executor.transferListScoped(peer, vaultPath)
          entries <- listed match
            case Right(json) => IO.pure(json.hcursor.downField("entries").as[List[Json]].getOrElse(Nil))
            case Left(err) => IO.raiseError[List[Json]](new Exception(err))
          // Attachments folder listing is best-effort (a vault may not have one).
          attachmentEntries <- executor
            .transferListScoped(peer, joinRemote(vaultPath, WikiIndex.AttachmentsDirName))
            .map(_.map(_.hcursor.downField("entries").as[List[Json]].getOrElse(Nil)).getOrElse(Nil))
            .handleErrorWith(_ => IO.pure(Nil))
          docNames = fileNames(entries, WikiIndex.DocExtensions).take(MaxRemoteFiles)
          // Attachment rel paths ride the namespace without fetching content.
          attachments = fileNames(attachmentEntries, WikiIndex.AttachmentExtensions)
            .take(MaxRemoteAttachments)
            .map(name => s"${WikiIndex.AttachmentsDirName}/$name")
          docs <- docNames.traverse { name =>
            executor.transferGetScoped(peer, joinRemote(vaultPath, name)).flatMap {
              case Right(bytes) => IO.pure(name -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
              case Left(err) => IO.raiseError[(String, String)](new Exception(s"$name: $err"))
            }
          }
        yield WikiIndex.buildVaultScan(pd.name, device, docs.toList, attachments.toList)
        scan
          .map(s => ProjectScan(pd.name, device, wsPath, vaultPath, s, None, stubProject = false))
          .handleErrorWith(e =>
            IO(logger.warnSync(s"wiki: remote vault scan for '${pd.name}' on '$device' failed: ${msgOf(e)}")) *>
              IO.pure(ProjectScan(pd.name, device, wsPath, vaultPath, emptyStubScan(pd.name, device), Some(msgOf(e)), stubProject = true))
          )

  /**
   * Remote SUB-vault scan (drill-in, #744-B): same channel, paths joined under
   * the requested vault rel. Cross-vault external classification is skipped on
   * remote views (each sibling listing is a peer round-trip; v1 keeps remote
   * phantoms as broken, documented in the design §6). Returns the scan plus an
   * optional error (peer offline / refused) so the view can surface it.
   */
  private def scanRemoteVault(pd: ProjectDef, vaultRel: String, vaultPath: String): IO[(WikiIndex.VaultScan, Option[String])] =
    val device = deviceOf(pd)
    RemoteExecutor.current match
      case None =>
        IO.pure((emptyStubScan(pd.name, device), Some("peer channel not initialized")))
      case Some(executor) =>
        val scan = for
          peers <- peers
          peer <- IO.fromOption(RemoteExecutor.resolvePeer(device, peers).toOption)(
            new Exception(s"peer '$device' not found — device offline or unpaired")
          )
          listed <- executor.transferListScoped(peer, vaultPath)
          entries <- listed match
            case Right(json) => IO.pure(json.hcursor.downField("entries").as[List[Json]].getOrElse(Nil))
            case Left(err) => IO.raiseError[List[Json]](new Exception(err))
          attachmentEntries <- executor
            .transferListScoped(peer, joinRemote(vaultPath, WikiIndex.AttachmentsDirName))
            .map(_.map(_.hcursor.downField("entries").as[List[Json]].getOrElse(Nil)).getOrElse(Nil))
            .handleErrorWith(_ => IO.pure(Nil))
          docNames = fileNames(entries, WikiIndex.DocExtensions).take(MaxRemoteFiles)
          attachments = fileNames(attachmentEntries, WikiIndex.AttachmentExtensions)
            .take(MaxRemoteAttachments)
            .map(name => s"${WikiIndex.AttachmentsDirName}/$name")
          docs <- docNames.traverse { name =>
            executor.transferGetScoped(peer, joinRemote(vaultPath, name)).flatMap {
              case Right(bytes) => IO.pure(name -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
              case Left(err) => IO.raiseError[(String, String)](new Exception(s"$name: $err"))
            }
          }
        yield WikiIndex.buildVaultScan(pd.name, device, docs.toList, attachments.toList)
        scan
          .map(s => (s, None))
          .handleErrorWith(e =>
            IO(logger.warnSync(s"wiki: remote sub-vault scan for '${pd.name}/$vaultRel' on '$device' failed: ${msgOf(e)}")) *>
              IO.pure((emptyStubScan(pd.name, device), Some(msgOf(e))))
          )

  /**
   * Best-effort one-level child-vault probe over the peer channel (design §1):
   * list the vault root, probe `<sub>/docs` for each dir entry (bounded by
   * [[MaxRemoteSubVaultProbe]]). A failed/empty probe means "no sub vaults" —
   * never an error, never fails the view.
   */
  private def probeRemoteSubVaults(device: String, vaultPath: String): IO[List[WikiIndex.ChildVault]] =
    RemoteExecutor.current match
      case None => IO.pure(Nil)
      case Some(executor) =>
        (for
          ps <- peers
          peer <- IO.fromOption(RemoteExecutor.resolvePeer(device, ps).toOption)(
            new Exception(s"peer '$device' not found — device offline or unpaired")
          )
          listed <- executor.transferListScoped(peer, vaultPath)
          entries <- listed match
            case Right(json) => IO.pure(json.hcursor.downField("entries").as[List[Json]].getOrElse(Nil))
            case Left(err) => IO.raiseError[List[Json]](new Exception(err))
          dirNames = entries.flatMap { e =>
            val c = e.hcursor
            for
              name <- c.downField("name").as[String].toOption
              typ <- c.downField("type").as[String].toOption
              if typ == "dir"
            yield name
          }.take(MaxRemoteSubVaultProbe)
          children <- dirNames.traverse { sub =>
            val subDocs = joinRemote(vaultPath, s"$sub/${WikiIndex.VaultDirName}")
            executor.transferListScoped(peer, subDocs).map {
              case Right(json) =>
                val count = json.hcursor.downField("entries").as[List[Json]].getOrElse(Nil).count { e =>
                  val c = e.hcursor
                  c.downField("type").as[String].contains("file") &&
                    WikiIndex.DocExtensions.contains(WikiIndex.extOf(c.downField("name").as[String].getOrElse("")))
                }
                if count > 0 then Some(WikiIndex.ChildVault(name = sub, rel = s"$sub/${WikiIndex.VaultDirName}", docCount = count))
                else None
              case Left(_) => None
            }.handleErrorWith(_ => IO.pure(None))
          }
        yield children.flatten).handleErrorWith(_ => IO.pure(Nil))

  /** File names of one remote listing filtered to `exts` (metadata only). */
  private def fileNames(entries: List[Json], exts: Set[String]): List[String] =
    entries.flatMap { e =>
      val c = e.hcursor
      for
        name <- c.downField("name").as[String].toOption
        typ <- c.downField("type").as[String].toOption
        size <- c.downField("size").as[Long].toOption
        if typ == "file" && exts.contains(WikiIndex.extOf(name)) && size <= MaxRemoteDocBytes
      yield name
    }

  /** Single-file fetch from a peer (remote doc open face). */
  def remoteDoc(device: String, remotePath: String): IO[Either[String, Json]] =
    if remotePath.isBlank then IO.pure(Left("path is required"))
    else if remotePath.length > 1024 then IO.pure(Left("path too long"))
    else
      RemoteExecutor.current match
        case None => IO.pure(Left("peer channel not initialized"))
        case Some(executor) =>
          peers.flatMap(p => IO(RemoteExecutor.resolvePeer(device, p))).flatMap {
            case Left(err) => IO.pure(Left(err.message))
            case Right(peer) =>
              executor.transferGetScoped(peer, remotePath).flatMap {
                case Right(bytes) =>
                  if bytes.length > MaxRemoteDocBytes then IO.pure(Left(s"file exceeds ${MaxRemoteDocBytes} bytes"))
                  else
                    // Binary viewer files (literature PDFs, figures) come back
                    // base64 so the frontend can open them from a blob URL;
                    // text documents keep the decoded-content shape.
                    val binary = BinaryDocExtensions.contains(WikiIndex.extOf(remotePath))
                    val fields: List[(String, Json)] = List(
                      "device" -> device.asJson,
                      "path" -> remotePath.asJson,
                      "binary" -> binary.asJson
                    ) ++ (if binary then
                            List("contentBase64" -> java.util.Base64.getEncoder.encodeToString(bytes).asJson)
                          else
                            List("content" -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8).asJson))
                    IO.pure(Right(Json.obj(fields*)))
                case Left(err) => IO.pure(Left(err))
              }
          }

  /** Peer roster via the wired Neblink service (absent ⇒ no remote legs). */
  private def peers: IO[List[nebflow.shared.PeerInfo]] =
    neblinkService.fold(IO.pure(Nil))(_.peers)

  private def emptyStubScan(project: String, device: String): WikiIndex.VaultScan =
    WikiIndex.VaultScan(
      List(
        WikiIndex.WikiDocNode(
          WikiIndex.nodeId(project, WikiIndex.VaultDirName + "/"),
          project, device, WikiIndex.VaultDirName + "/", project, Nil, Nil, stub = true
        )
      ),
      Nil, Nil
    )

  /** `<base>/<child>` for remote (possibly Windows-style) paths — string-level
    * join: the path never resolves on THIS filesystem. */
  private def joinRemote(base: String, child: String): String =
    if base.endsWith("/") || base.endsWith("\\") then base + child
    else base + "/" + child

  private def listVaultFiles(vault: os.Path, exts: Set[String], skipRels: Set[String] = Set.empty): List[os.Path] =
    val out = ListBuffer.empty[os.Path]
    def walk(dir: os.Path, depth: Int): Unit =
      if depth <= WikiIndex.MaxScanDepth && out.sizeIs < WikiIndex.MaxFilesPerVault then
        os.list(dir).foreach { p =>
          if os.isDir(p) then
            val rel = p.relativeTo(vault).toString.replace('\\', '/')
            // Sub-vault roots are separate graphs — never descend into them
            // while scanning the parent (#744-B per-vault isolation).
            if !skipRels.contains(rel) then walk(p, depth + 1)
          else if exts.contains(WikiIndex.extOf(p.last)) then out += p
        }
    walk(vault, 0)
    out.toList

  private def msgOf(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
end WikiService

object WikiService:
  /**
   * Process-wide instance (the TTL cache and the builder flag must be shared
   * by every request). First caller wins — the Neblink service port is stable
   * for the life of the gateway, so a late caller passing None cannot downgrade
   * an already-wired instance.
   */
  @volatile private var instance: Option[WikiService] = None

  def current(neblinkService: Option[NeblinkServicePort]): WikiService =
    instance.getOrElse {
      synchronized {
        instance.getOrElse {
          val s = new WikiService(neblinkService)
          instance = Some(s)
          s
        }
      }
    }
end WikiService
