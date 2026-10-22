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
 * (Obsidian-compatible) into one merged document graph.
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
 *
 * The whole graph is cached for [[CacheTtlMs]] so rapid tab switches do not
 * re-walk every vault. Concurrent cache misses are serialized with an atomic
 * builder flag — a loser keeps its previous snapshot instead of double-fetching.
 */
class WikiService(neblinkService: Option[NeblinkServicePort] = None):

  private val logger = NebflowLogger.forName("nebflow.wiki")

  private val CacheTtlMs = 30_000L

  /** Remote per-vault fetch caps — bounded work per graph build. */
  val MaxRemoteFiles = 300
  val MaxRemoteAttachments = 200
  val MaxRemoteDocBytes = WikiIndex.MaxDocBytes

  /** Binary viewer extensions — remote-doc returns base64 for these so the
    * frontend can open them from a blob URL (literature PDFs, figures). */
  private val BinaryDocExtensions: Set[String] = Set(
    ".pdf", ".docx", ".pptx", ".xlsx", ".epub", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".mp4", ".webm", ".mov", ".mp3", ".wav"
  )

  private val cacheState = new java.util.concurrent.atomic.AtomicReference[Option[(Long, Json)]](None)
  private val building = new java.util.concurrent.atomic.AtomicBoolean(false)

  // ── Public surface ───────────────────────────────────────────────

  /** Full merged graph (local + remote projects), TTL-cached. */
  def graph(force: Boolean = false): IO[Json] =
    IO(System.currentTimeMillis()).flatMap { now =>
      cacheState.get match
        case Some((at, json)) if !force && now - at < CacheTtlMs => IO.pure(json)
        case _ if !building.compareAndSet(false, true) =>
          // Another build is in flight — serve the previous snapshot (or an
          // empty shell on the very first race); the UI refresh re-issues.
          IO.pure(cacheState.get.map(_._2).getOrElse(emptyGraph))
        case _ =>
          build
            .map { json =>
              cacheState.set(Some((System.currentTimeMillis(), json)))
              json
            }
            .handleErrorWith(e =>
              IO(logger.errorSync(s"wiki: graph build failed: ${msgOf(e)}")) *> IO.pure(emptyGraph)
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

  // ── Build pipeline ───────────────────────────────────────────────

  private def build: IO[Json] =
    ProjectStore.list().flatMap { defs =>
      // Local projects scan first (fast, direct disk); remote projects after —
      // remote legs are network calls and each is individually fault-isolated.
      val local = defs.filter(pd => ProjectOrigin.isSelfLiteral(deviceOf(pd)))
      val remote = defs.filterNot(pd => ProjectOrigin.isSelfLiteral(deviceOf(pd)))
      local.traverse(scanLocalProject).flatMap { localScans =>
        remote.traverse(scanRemoteProject).map { remoteScans =>
          assemble(localScans ++ remoteScans)
        }
      }
    }

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

  private def scanLocalProject(pd: ProjectDef): IO[ProjectScan] =
    val device = deviceOf(pd)
    val ws = workspacePath(pd)
    val vault = ws / WikiIndex.VaultDirName
    IO.blocking {
      if !os.isDir(vault) then
        ProjectScan(pd.name, device, ws.toString(), vault.toString(), emptyStubScan(pd.name, device), None, stubProject = true)
      else
        val docs = ListBuffer.empty[(String, String)]
        val warnings = ListBuffer.empty[String]
        listVaultFiles(vault, WikiIndex.DocExtensions).foreach { f =>
          val rel = f.relativeTo(vault).toString.replace('\\', '/')
          if os.size(f) > WikiIndex.MaxDocBytes then warnings += s"$rel exceeds ${WikiIndex.MaxDocBytes} bytes — skipped"
          else
            try docs += ((rel, os.read(f)))
            catch case e: Exception => warnings += s"$rel unreadable: ${msgOf(e)}"
        }
        // Attachments: rel paths only — literature PDFs, figures, datasets.
        // Content is never read here; the canvas opens them on click.
        val attachments = listVaultFiles(vault, WikiIndex.AttachmentExtensions)
          .map(_.relativeTo(vault).toString.replace('\\', '/'))
          .take(WikiIndex.MaxFilesPerVault)
        ProjectScan(
          pd.name, device, ws.toString(), vault.toString(),
          WikiIndex.buildVaultScan(pd.name, device, docs.toList, attachments.toList),
          if warnings.isEmpty then None else Some(warnings.mkString("; ")),
          stubProject = false
        )
    }.handleErrorWith(e =>
      IO.pure(ProjectScan(pd.name, device, ws.toString(), vault.toString(), emptyStubScan(pd.name, device), Some(msgOf(e)), stubProject = true))
    )

  /**
   * Remote vault scan: list the peer's `<workspace>/docs` (plus its
   * `attachments/` folder, best-effort), then pull each document through the
   * scoped browse channel and parse it locally. Any failure (peer offline,
   * refused, egress-denied) degrades to a stub project node.
   */
  private def scanRemoteProject(pd: ProjectDef): IO[ProjectScan] =
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

  // ── Paragraph snapshot (wiki-graph 段落级引用批 — xerj 集成边界) ──
  // Design doc §5 contract, pinned:
  //   1) paragraph index  — read the doc (local disk / peer channel), parse
  //      locally with the SAME WikiIndex the graph payload embeds;
  //   2) best-effort tools/stand-recall — the xerj 按段检索 leg (memory-db).
  //      Process absent, timeout, zero hits: all silently skipped;
  //   3) doc-level fallback — {fallback:"doc", summary} — the contract
  //      floor. The graph NEVER depends on the runtime memory-db.
  // Malformed params are the only Left (route answers 400/404); content legs
  // always answer Right (a snapshot or a declared fallback).

  val ParagraphRecallTimeoutMs = 2000L
  val ParagraphRecallMaxHits = 3
  val ParagraphRecallTextCap = 200

  def paragraph(project: String, rel: String, anchor: String): IO[Either[String, Json]] =
    if project.isBlank then IO.pure(Left("project is required"))
    else if rel.isBlank || rel.contains("..") || rel.contains('\\') then IO.pure(Left("rel must be a vault-relative path"))
    else if anchor.isBlank then IO.pure(Left("anchor is required"))
    else
      ProjectStore.load(project).flatMap {
        case None => IO.pure(Left(s"project '$project' not found"))
        case Some(pd) =>
          readVaultDoc(pd, rel).flatMap {
            case None => IO.pure(Right(docFallback(rel, anchor, None)))
            case Some(text) =>
              IO.blocking {
                val parsed = WikiIndex.parseDoc(rel, text)
                val wanted = WikiIndex.edgeAnchorForm(anchor)
                (parsed.paragraphs.find(_.anchor == wanted).map { p =>
                  Right(Json.obj(
                    "fallback" -> "index".asJson,
                    "project" -> project.asJson,
                    "rel" -> rel.asJson,
                    "anchor" -> wanted.asJson,
                    "paragraph" -> WikiIndex.paragraphJson(p)
                  ))
                }, parsed.summary)
              }.handleErrorWith(e =>
                IO(logger.warnSync(s"wiki: paragraph parse failed for $project/$rel: ${msgOf(e)}")) *> IO.pure((None, None))
              ).flatMap {
                case (Some(r), _) => IO.pure(r)
                case (None, summary) =>
                  standRecallParagraph(anchor).map {
                    case Some(hits) => Right(Json.obj(
                      "fallback" -> "recall".asJson,
                      "project" -> project.asJson,
                      "rel" -> rel.asJson,
                      "anchor" -> WikiIndex.edgeAnchorForm(anchor).asJson,
                      "source" -> "stand-recall".asJson,
                      "hits" -> hits.asJson))
                    case None => Right(docFallback(rel, anchor, summary))
                  }
              }
          }
      }

  /** Doc-level contract floor: summary when we have the text, plain fallback
    * shape when we do not (unreachable peer, unreadable file). */
  private def docFallback(rel: String, anchor: String, summary: Option[String]): Json =
    Json.obj(
      "fallback" -> "doc".asJson,
      "rel" -> rel.asJson,
      "anchor" -> WikiIndex.edgeAnchorForm(anchor).asJson,
      "summary" -> summary.map(_.asJson).getOrElse(Json.Null)
    )

  /** Read one vault doc's text — local projects straight from disk, remote
    * projects through the peer browse channel. None = unreadable (degrade,
    * never fail). */
  private def readVaultDoc(pd: ProjectDef, rel: String): IO[Option[String]] =
    val device = deviceOf(pd)
    if ProjectOrigin.isSelfLiteral(device) then
      IO.blocking {
        val segs = rel.split('/').toVector.filter(s => s.nonEmpty && s != "." && s != "..")
        if segs.isEmpty then None
        else
          val f = segs.foldLeft(workspacePath(pd) / WikiIndex.VaultDirName)(_ / os.RelPath(_))
          if os.isFile(f) && os.size(f) <= WikiIndex.MaxDocBytes then Some(os.read(f)) else None
      }.handleErrorWith(_ => IO.pure(None))
    else
      RemoteExecutor.current match
        case None => IO.pure(None)
        case Some(executor) =>
          peers.flatMap(p => IO(RemoteExecutor.resolvePeer(device, p))).flatMap {
            case Left(_) => IO.pure(None)
            case Right(peer) =>
              val remotePath = joinRemote(joinRemote(pd.origin.map(_.path).getOrElse(pd.workspace), WikiIndex.VaultDirName), rel)
              executor.transferGetScoped(peer, remotePath).map {
                case Right(bytes) if bytes.length <= WikiIndex.MaxDocBytes =>
                  Some(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                case _ => None
              }.handleErrorWith(_ => IO.pure(None))
          }

  /** The xerj 按段检索 leg — best-effort `tools/stand-recall` (memory-db).
    * Locates the CLI via STAND_RECALL_CLI (absolute) or `<cwd>/tools/stand-recall`;
    * anything absent/late/malformed ⇒ None (the graph must not depend on the
    * runtime memory-db — design §5 boundary clause 1). */
  private def standRecallParagraph(anchor: String): IO[Option[List[Json]]] =
    IO.blocking {
      val cli = sys.env.get("STAND_RECALL_CLI") match
        case Some(p) => os.Path(p, PathUtil.dataRoot)
        case None    => os.Path("tools/stand-recall", os.pwd)
      if !os.isFile(cli) then None
      else
        val query = anchor.stripPrefix("#").stripPrefix("^").take(120)
        val proc = new java.lang.ProcessBuilder("python3", cli.toString, "--json", "-n", String.valueOf(ParagraphRecallMaxHits), query)
          .redirectErrorStream(false)
          .start()
        try
          if !proc.waitFor(ParagraphRecallTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) then
            proc.destroyForcibly()
            None
          else
            val out = new String(proc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            io.circe.parser.decode[List[Json]](out).toOption.map { hits =>
              hits.map { h =>
                val c = h.hcursor
                Json.obj(
                  "score" -> c.downField("score").as[Double].map(_.asJson).getOrElse(Json.Null),
                  "ts" -> c.downField("ts").as[String].map(_.asJson).getOrElse(Json.Null),
                  "role" -> c.downField("role").as[String].map(_.asJson).getOrElse(Json.Null),
                  "session" -> c.downField("session_name").as[String].map(_.asJson).getOrElse(Json.Null),
                  "text" -> c.downField("text").as[String].map(t => t.take(ParagraphRecallTextCap).asJson).getOrElse(Json.Null)
                )
              }
            }
        finally proc.destroyForcibly()
    }.handleErrorWith { e =>
      IO(logger.warnSync(s"wiki: stand-recall leg skipped: ${msgOf(e)}")) *> IO.pure(None)
    }

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

  private def emptyGraph: Json = Json.obj(
    "generatedAt" -> System.currentTimeMillis().asJson,
    "projects" -> Json.arr(),
    "nodes" -> Json.arr(),
    "edges" -> Json.arr()
  )

  /** `<base>/<child>` for remote (possibly Windows-style) paths — string-level
    * join: the path never resolves on THIS filesystem. */
  private def joinRemote(base: String, child: String): String =
    if base.endsWith("/") || base.endsWith("\\") then base + child
    else base + "/" + child

  private def listVaultFiles(vault: os.Path, exts: Set[String]): List[os.Path] =
    val out = ListBuffer.empty[os.Path]
    def walk(dir: os.Path, depth: Int): Unit =
      if depth <= WikiIndex.MaxScanDepth && out.sizeIs < WikiIndex.MaxFilesPerVault then
        os.list(dir).foreach { p =>
          if os.isDir(p) then walk(p, depth + 1)
          else if exts.contains(WikiIndex.extOf(p.last)) then out += p
        }
    walk(vault, 0)
    out.toList

  private def assemble(scans: List[ProjectScan]): Json =
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
