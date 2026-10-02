package nebflow.core.project

import cats.effect.IO
import io.circe.Json
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.shared.PathUtil
import nebflow.core.tools.ToolError

import scala.util.Try

/**
 * Project create core — the single implementation behind BOTH creation faces:
 *   ① `ProjectCreateTool.createChain` (the agent/tool face), and
 *   ② the `projectCreate` WebSocket command (`WebSocketRoutes`, the direct
 *      frontend face).
 *
 * Extraction ruling (author 2026-09-24, D2 = option (b) "reuse the existing
 * create core"): the tool face becomes a thin shell over this service, so the
 * occupancy gate, the idempotent re-mount semantics, the archive refusal and
 * the per-file scaffold report live in ONE place and cannot drift between the
 * two faces.
 *
 * Semantics are preserved verbatim from the pre-extraction tool body — the
 * occupancy default-deny (`docs`/`listAll()` scan) still runs BEFORE any disk
 * write, the workspace/idempotency judgement is still the single
 * [[sameWorkspace]] function, and every error string is byte-identical.
 *
 * IDENTITY IS EXPLICIT — never implicit, never fabricated. Mounting is what
 * actually makes a project live ([[ProjectRuntimeRegistry.mount]]), and the
 * mount needs a `rootSessionId` (the delivery root: node `out="Nebula"`
 * messages must land on the true top-level session). A call that carries no
 * session context is REFUSED rather than given a placeholder root — see the
 * `case None` branch of [[mountProject]] and the freshinstall-rootsessionid
 * ruling (author 2026-09-14) it implements.
 */
object ProjectCreateService:

  /**
   * The identity a create call carries, passed EXPLICITLY by each caller.
   *
   * @param actorSystem     needed to mount; `None` => definition is written but
   *                        the project is not mounted.
   * @param sharedResources needed to mount (same condition as `actorSystem`).
   * @param rootSessionId   the TRUE top-level session id (delivery root). This
   *                        is what [[ProjectRuntimeRegistry.mount]] receives, so
   *                        that node completion messages addressed to "Nebula"
   *                        reach the real root and not whoever happened to mount
   *                        the project.
   * @param sessionId       the caller's own session id; used only as a fallback
   *                        for `rootSessionId`, mirroring the tool's historical
   *                        `orElse` order.
   * @param wsSend          broadcast channel for the `projectCreated` frame.
   *                        `None` (remote exec / no connection context) is a
   *                        deliberate silent no-op, matching ProjectActor's
   *                        `fold(IO.unit)` for node events.
   */
  final case class CreateIdentity(
      actorSystem: Option[ActorSystem],
      sharedResources: Option[SharedResources],
      rootSessionId: Option[String],
      sessionId: Option[String],
      wsSend: Option[Json => IO[Unit]]
  )

  object CreateIdentity:
    /** An identity that cannot mount (no engine resources) but can still write
     *  the definition and broadcast the `mounted=false` frame. */
    val definitionOnly: CreateIdentity =
      CreateIdentity(None, None, None, None, None)

  /** AGENTS.md template written into a NEW workspace.
    *
    * L1 is deliberately EMPTY (promptopt batch W3, author ruling 2026-09-18
    * §2.3): projects created via NodeEdit no longer pre-fill any workspace
    * AGENTS.md content — `ensureScaffoldSync` lands a 0-byte file and the
    * project/author owns the instructions. Semantics are unchanged: ProjectStore
    * still backfills only missing pieces and never overwrites existing ones. */
  val AgentMdTemplate: String = ""

  /**
   * Create + idempotently mount. `name` defaults to the workspace basename.
   *
   * Same-name semantics (unchanged): same workspace => idempotent
   * ("already exists" + mount); different workspace => explicit error, never a
   * silent re-point of an existing definition.
   */
  def create(
      nameOpt: Option[String],
      workspace: String,
      description: Option[String],
      identity: CreateIdentity
  ): IO[Either[ToolError, String]] =
    val resolvedName = nameOpt.getOrElse(baseName(workspace))
    if resolvedName.isEmpty then
      IO.pure(Left(ToolError(
        s"Cannot derive project name from workspace '$workspace' — pass 'name' explicitly"
      )))
    else
      emitAndMount(resolvedName, workspace, description, identity)

  // ============================================================
  // Internals
  // ============================================================

  private def emitAndMount(
      resolvedName: String,
      workspace: String,
      description: Option[String],
      identity: CreateIdentity
  ): IO[Either[ToolError, String]] =
    /** Project-level realtime event. Goes through the EXISTING push face (never
      * a second one): the tool face's `ctx.wsSend` is built as
      * `makeRecordingWsSend(sessionId, (json) => wsHub.broadcast(json))`, so it
      * is the same channel the node events use. The frame shape is produced only
      * by [[ProjectActor.projectCreatedFrame]] (single frame-shell point). */
    def emitProjectCreated(pd: ProjectDef, mounted: Boolean): IO[Unit] =
      identity.wsSend.fold(IO.unit)(send => send(ProjectActor.projectCreatedFrame(pd, mounted)))

    /** Mount for both the freshly-created and the already-exists paths.
      * [[ProjectRuntimeRegistry.mount]] is itself idempotent: an already-mounted
      * project returns its existing runtime (rootSessionId was wired on the
      * first mount; re-mounting a live project would need an engine rebuild,
      * which has no call site).
      *
      * Emit face: ONLY a fresh create (`created=true`) broadcasts a frame;
      * an idempotent re-mount does not (no visual change). A failed mount
      * throws and therefore never emits; an absent root key returns Left and
      * also never emits (a failure frame must not report success) — that gap is
      * covered on the frontend by the low-frequency fallback re-pull. */
    def mountProject(
        pd: ProjectDef,
        created: Boolean,
        scaffold: Option[ProjectStore.ScaffoldReport] = None
    ): IO[Either[ToolError, String]] =
      // Per-file report (author ruling 2026-09-17 12:09): the success and
      // idempotent result sentences both carry this run's backfill readings.
      // The substrings existing consumers match on ("Mail(address='project:",
      // "already exists") are unchanged — the report is appended at the tail.
      val scaffoldSuffix: String = scaffold.fold("")(r => s" Scaffold: ${r.render}.")
      (identity.actorSystem, identity.sharedResources) match
        case (Some(system), Some(res)) =>
          // Mount receives the UPLINE rootSessionId (the true top level), not the
          // mounting caller's own session — otherwise out="Nebula" would deliver
          // to the mounter (e.g. a qa node), creating a self-sustaining loop.
          //
          // freshinstall-rootsessionid batch M3 (author 2026-09-14 ruling ②): a
          // mount with NO session context is ILLEGAL — aligned verbatim with the
          // existing positive precedent (SendConfirm.scala's "filter non-empty
          // first, then refuse explicitly"). The `orElse` does NOT filter empty
          // values (`Some("")` would win and yield an empty delivery bucket), and
          // the last-ditch placeholder `"default"` would be a fabricated bucket
          // key — the author explicitly forbids fabricating a root — so both are
          // rejected and we refuse instead.
          //
          // Call-site enumeration: ProjectCreate's ToolContext has 4 production
          // construction points — the AgentCore session face (has identity), WS
          // AgentControl (never reaches this tool), and neblink relay /
          // neblink REST remote-exec (NEITHER carries any session identity:
          // the request body has only action/params/projectRoot, no sessionId)
          // => the latter cannot "pass its own identity" without inventing one,
          // so they hit the explicit refusal.
          val rootKey =
            identity.rootSessionId.filter(_.nonEmpty).orElse(identity.sessionId.filter(_.nonEmpty))
          rootKey match
            case None =>
              IO.pure(Left(ToolError(
                "ProjectCreate refused to mount: this call carries no session context " +
                  "(both rootSessionId and sessionId are empty or absent), so the project's delivery root " +
                  "cannot be attributed — and inventing a placeholder root is not allowed. " +
                  "Re-invoke ProjectCreate from an agent session (Nebula / project dispatcher / project node)."
              )))
            case Some(root) =>
              val mountedResult = ProjectRuntimeRegistry
                .mount(pd, system, res, identity.wsSend, root)
                .as {
                  val verb = if created then "created" else "already exists"
                  Right(
                    s"Project '${pd.name}' $verb and mounted. Flow Map ready at ${pd.agentFile}. " +
                      s"Dispatch work with Mail(address='project:${pd.name}', message=...).$scaffoldSuffix"
                  )
                }
              // A fresh create emits BEFORE returning the result (mount succeeded
              // => mounted=true).
              if created then mountedResult.flatMap(r => emitProjectCreated(pd, mounted = true).as(r))
              else mountedResult
        case _ =>
          // Definition is on disk (only ProjectStore.create's success branch gets
          // here) but there is no engine context to mount with: the list endpoint
          // will show it, so the frontend must be told or it stays stale =>
          // mounted=false. An idempotent re-mount (created=false) does not emit.
          val ready: Either[ToolError, String] =
            Right(s"Project '${pd.name}' definition ready. Mount requires an agent session.$scaffoldSuffix")
          if created then emitProjectCreated(pd, mounted = false).as(ready) else IO.pure(ready)

    // ============================================================
    // Reverse guard (author ruling 2026-09-17 12:09, ④-4 + ④-12: default-deny,
    // prefer a false refusal over a false creation)
    // ============================================================
    // The gap: the original three guards only covered "same name overwrite /
    // same name same workspace idempotent / same name different workspace
    // error" — "NEW name + a workspace already used by a DIFFERENT project" had
    // ZERO guard, so a second project could silently share one workspace
    // (flow-map / task-board / worktrees all key off the workspace; mounting
    // both in parallel means mutual writes).
    //
    // Judgement discipline (ruling by ruling):
    // - BEFORE any disk write: the occupancy check runs before ProjectStore
    //   .create, so the refusal path writes nothing (no projects/<newName>/,
    //   workspace file hashes unchanged).
    // - Occupancy and idempotency share ONE function ([[sameWorkspace]]): the
    //   same normalization (absolute + trailing-slash-stripped + CASE
    //   INSENSITIVE). Never two divergent predicates.
    // - NO realpath/symlink resolution (not ruled on; /tmp vs /private/tmp would
    //   change existing semantics).
    // - Occupancy scan source = ProjectStore.listAll() INCLUDING archived
    //   definitions (conservative default: prefer a false refusal).
    // - Occupant name comparison uses [[sameProjectName]]: same name (including
    //   case-only differences) is the SAME project, so an idempotent re-mount is
    //   not falsely refused.
    ProjectStore.listAll().flatMap { defs =>
      defs.find(d => !sameProjectName(d.name, resolvedName) && sameWorkspace(d.workspace, workspace)) match
        case Some(occupant) =>
          IO.pure(Left(ToolError(occupiedWorkspaceError(resolvedName, workspace, occupant))))
        case None =>
          ProjectStore.createWithScaffold(resolvedName, workspace, description, AgentMdTemplate).flatMap {
            case Right((pd, report)) => mountProject(pd, created = true, scaffold = Some(report))
            case Left(err) =>
              // Idempotent re-mount (the key restart-recovery path): the
              // definition already exists, so do not re-create it; backfill the
              // scaffold (③-8: backfill missing pieces, never overwrite) — this
              // backfill must ship in the same batch as the success semantics,
              // otherwise it is a silent zero-write.
              // Same name + different workspace => explicit error (never a silent
              // reuse of the old definition).
              // Archived projects are the exception (migration plan v2 §6.1,
              // one-way semantics): refuse the mount — otherwise the project is
              // mounted yet invisible in the panel (list filters archived), a
              // zombie state; restoring means deleting the two archive keys in
              // project.json by hand first.
              ProjectStore.load(resolvedName).flatMap {
                case Some(pd) if pd.archived.contains(true) =>
                  IO.pure(Left(ToolError(
                    s"Project '$resolvedName' is archived (hidden from the Projects panel). " +
                      s"Remove the 'archived'/'archivedAt' keys in ${PathUtil.dataRootRenderValue}/projects/$resolvedName/project.json to restore it first."
                  )))
                case None => IO.pure(Left(ToolError(err)))
                case Some(pd) if sameWorkspace(pd.workspace, workspace) =>
                  // Backfill hangs ONLY on this branch (a successful idempotent
                  // re-mount); the archived and different-workspace branches
                  // stay zero-write.
                  ProjectStore.ensureScaffold(os.Path(workspace, PathUtil.dataRoot), AgentMdTemplate)
                    .flatMap(report => mountProject(pd, created = false, scaffold = Some(report)))
                case Some(pd) =>
                  IO.pure(Left(ToolError(
                    s"Project '$resolvedName' already exists with a different workspace (${pd.workspace}) — " +
                      "choose another name or reuse the existing workspace"
                  )))
              }
          }
    }

  /** Workspace normalization (absolute + trailing slashes stripped) — the ONE
    * form shared by display and by the judgement itself. Unresolvable => None
    * (treated as different).
    * Do NOT fold realpath/symlink resolution into this function: it was never
    * ruled on, and /tmp vs /private/tmp would change existing semantics. */
  def normalizeWorkspace(p: String): Option[String] =
    Try(os.Path(p, PathUtil.dataRoot).toString).toOption.map(stripTrailingSlashes)

  /** The comparison key of that same normalization (case insensitive) — the
    * occupancy check and the idempotency check share this ONE predicate
    * [[sameWorkspace]] (never two). Case-insensitive normalization is author
    * ruling ④-12 ("path semantics + case-insensitive normalization"; on a
    * case-sensitive filesystem this is the stricter direction). */
  private def workspaceKey(p: String): Option[String] =
    normalizeWorkspace(p).map(_.toLowerCase(java.util.Locale.ROOT))

  def sameWorkspace(a: String, b: String): Boolean =
    (workspaceKey(a), workspaceKey(b)) match
      case (Some(x), Some(y)) => x == y
      case _                  => false

  /** Project identity equality (same lineage as ProjectRuntimeRegistry.get's
    * equalsIgnoreCase fallback: project identifiers are inherently case
    * insensitive). A same name (including case-only differences) is the SAME
    * project, not an occupant — which keeps "same name, same workspace,
    * idempotent re-mount" from being falsely refused (existing precedent: the
    * project named `nebflow` has a workspace basename of `Nebflow`, differing
    * only in case, and the live install resolves it via the registry fallback). */
  def sameProjectName(a: String, b: String): Boolean =
    a == b || a.equalsIgnoreCase(b)

  /** Occupancy error (actionable: names the occupant, its archive state, the
    * normalized workspace, and two ways out). */
  def occupiedWorkspaceError(newName: String, workspace: String, occupant: ProjectDef): String =
    val norm = normalizeWorkspace(workspace).getOrElse(workspace)
    val occupantWs = normalizeWorkspace(occupant.workspace).getOrElse(occupant.workspace)
    val arch = if occupant.archived.contains(true) then " (archived)" else ""
    s"Workspace '$norm' is already used by project '${occupant.name}'$arch " +
      s"(its project.json workspace = '$occupantWs'). ProjectCreate default-denies creating '$newName' " +
      "on an occupied workspace — a second project on the same workspace would silently share its " +
      "flow-map / task board / worktrees (2026-09-17 裁定 ④-4). Two ways out: " +
      s"(a) reuse the existing project — ProjectCreate(name='${occupant.name}') to re-mount it, or " +
      s"Mail(address='project:${occupant.name}', message=...) to dispatch work; " +
      s"(b) pass a different 'workspace' directory for '$newName'."

  /** Path basename (name derivation); root paths with no basename => "" (the
    * caller reports the error). */
  def baseName(workspace: String): String =
    Try(os.Path(workspace, PathUtil.dataRoot)).map(_.last).getOrElse("")

  /** Strip trailing slashes (Scala's `stripTrailing` has no zero-arg form; slash
    * semantics are written by hand). Shared with the panel answer parser so the
    * two faces normalize identically. */
  def stripTrailingSlashes(s: String): String = s.replaceAll("/+$", "")
