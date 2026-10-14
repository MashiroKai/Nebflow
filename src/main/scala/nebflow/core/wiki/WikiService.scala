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
  val MaxRemoteDocBytes = WikiIndex.MaxDocBytes

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
        listMdFiles(vault).foreach { f =>
          val rel = f.relativeTo(vault).toString.replace('\\', '/')
          if os.size(f) > WikiIndex.MaxDocBytes then warnings += s"$rel exceeds ${WikiIndex.MaxDocBytes} bytes — skipped"
          else
            try docs += ((rel, os.read(f)))
            catch case e: Exception => warnings += s"$rel unreadable: ${msgOf(e)}"
        }
        ProjectScan(
          pd.name, device, ws.toString(), vault.toString(),
          WikiIndex.buildVaultScan(pd.name, device, docs.toList),
          if warnings.isEmpty then None else Some(warnings.mkString("; ")),
          stubProject = false
        )
    }.handleErrorWith(e =>
      IO.pure(ProjectScan(pd.name, device, ws.toString(), vault.toString(), emptyStubScan(pd.name, device), Some(msgOf(e)), stubProject = true))
    )

  /**
   * Remote vault scan: list the peer's `<workspace>/docs`, then pull each md
   * file through the scoped browse channel and parse it locally. Any failure
   * (peer offline, refused, egress-denied) degrades to a stub project node.
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
          files = entries.flatMap { e =>
            val c = e.hcursor
            for
              name <- c.downField("name").as[String].toOption
              typ <- c.downField("type").as[String].toOption
              size <- c.downField("size").as[Long].toOption
              if typ == "file" && name.toLowerCase.endsWith(".md") && size <= MaxRemoteDocBytes
            yield name
          }.take(MaxRemoteFiles)
          docs <- files.traverse { name =>
            val rel =
              if vaultPath.endsWith("/") || vaultPath.endsWith("\\") then vaultPath + name
              else vaultPath + "/" + name
            executor.transferGetScoped(peer, rel).flatMap {
              case Right(bytes) => IO.pure(name -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
              case Left(err) => IO.raiseError[(String, String)](new Exception(s"$name: $err"))
            }
          }
        yield WikiIndex.buildVaultScan(pd.name, device, docs.toList)
        scan
          .map(s => ProjectScan(pd.name, device, wsPath, vaultPath, s, None, stubProject = false))
          .handleErrorWith(e =>
            IO(logger.warnSync(s"wiki: remote vault scan for '${pd.name}' on '$device' failed: ${msgOf(e)}")) *>
              IO.pure(ProjectScan(pd.name, device, wsPath, vaultPath, emptyStubScan(pd.name, device), Some(msgOf(e)), stubProject = true))
          )

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
                    IO.pure(
                      Right(
                        Json.obj(
                          "device" -> device.asJson,
                          "path" -> remotePath.asJson,
                          "content" -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8).asJson
                        )
                      )
                    )
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

  private def listMdFiles(vault: os.Path): List[os.Path] =
    val out = ListBuffer.empty[os.Path]
    def walk(dir: os.Path, depth: Int): Unit =
      if depth <= WikiIndex.MaxScanDepth && out.sizeIs < WikiIndex.MaxFilesPerVault then
        os.list(dir).foreach { p =>
          if os.isDir(p) then
            if p.last != WikiIndex.AttachmentsDirName then walk(p, depth + 1)
          else if p.last.toLowerCase.endsWith(".md") then out += p
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
