package nebflow.core.delegate

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import nebflow.core.AtomicJson

/**
 * Shared durable authority for ownership and execution reservations. Reads fail
 * closed on corrupt data. The file lock also covers other application processes.
 */
final class WorkspaceLedger(val path: os.Path):
  import WorkspaceLedger.*

  private def transaction[A](f: State => (State, A)): A = WorkspaceLedger.synchronized {
    os.makeDir.all(path / os.up)
    val channel = java.nio.channels.FileChannel.open(
      (path / os.up / s"${path.last}.lock").toNIO,
      java.nio.file.StandardOpenOption.CREATE,
      java.nio.file.StandardOpenOption.WRITE
    )
    try
      val lock = channel.lock()
      try
        val before =
          if os.exists(path) then
            io.circe.parser
              .decode[State](os.read(path))
              .fold(e => throw new IllegalStateException("Workspace ledger is unreadable", e), identity)
          else State()
        val (after, result) = f(before)
        if after != before then
          val committed = after.copy(revision = before.revision + 1)
          AtomicJson.writeSync(path, committed.asJson.noSpaces)
          WorkspaceLedger.changed(path, committed.revision)
        result
      finally lock.release()
    finally channel.close()
    end try
  }

  def snapshot: State = transaction(s => (s, s))

  def register(workspace: Workspace): Either[String, Workspace] = transaction { s =>
    s.workspaces.find(w => w.id == workspace.id || w.path == workspace.path) match
      case Some(existing) if existing == workspace => (s, Right(existing))
      case Some(_) => (s, Left("Workspace already has an owner"))
      case None => (s.copy(workspaces = s.workspaces :+ workspace), Right(workspace))
  }

  /** One identity for aliases of the same repository; never silently accept a task. */
  def merger(
    project: String,
    repository: String,
    candidateId: String,
    bootstrap: Option[MergerBootstrap] = None
  ): Merger = transaction { s =>
    s.mergers.find(m => m.repository == repository || m.project == project) match
      case Some(existing) if existing.repository != repository =>
        throw new IllegalStateException("Project repository changed; merger reconciliation required")
      case Some(existing) => (s, existing)
      case None =>
        val created = Merger(project, repository, candidateId)
        (
          s.copy(
            mergers = s.mergers :+ created,
            mergerBootstraps = s.mergerBootstraps ++ bootstrap.map(candidateId -> _)
          ),
          created
        )
  }

  def markMergerInitialization(id: String, status: String, error: Option[String]): Unit = transaction { s =>
    require(Set("initializing", "ready", "failed").contains(status), "Invalid merger initialization status")
    val next = s.mergerBootstraps
      .get(id)
      .fold(s)(seed =>
        s.copy(mergerBootstraps =
          s.mergerBootstraps
            .updated(id, seed.copy(status = status, error = error, everStarted = seed.everStarted || status == "ready"))
        )
      )
    (next, ())
  }

  def claimDevelopment(id: String, owner: String, revision: Long): Either[String, Workspace] =
    change(id, revision) { w =>
      if w.owner != owner then Left("Not the development owner")
      else if w.lifecycle != "open" || w.activity != "idle" then Left("Workspace is reserved, busy, or closed")
      else Right(w.copy(activity = "running"))
    }

  def releaseDevelopment(id: String, owner: String, revision: Long, frozen: Boolean): Either[String, Workspace] =
    change(id, revision) { w =>
      if w.owner != owner || w.lifecycle != "open" || w.activity != "running" then Left("No development claim")
      else Right(w.copy(activity = if frozen then "frozen" else "idle"))
    }

  /** All-or-nothing reservation: busy development never joins a merge queue. */
  def reserve(request: String, ids: List[String]): Either[String, List[Workspace]] = transaction { s =>
    val selected = ids.distinct.flatMap(id => s.workspaces.find(_.id == id))
    if selected.isEmpty || selected.size != ids.distinct.size then (s, Left("Unknown workspace"))
    else if selected.forall(w => w.reservation.contains(request)) then (s, Right(selected))
    else if selected.exists(w => w.lifecycle != "open" || w.activity != "idle") then
      (s, Left("Every selected workspace must be open and idle"))
    else
      val reserved =
        selected.map(w => w.copy(lifecycle = "reserved", reservation = Some(request), revision = w.revision + 1))
      val replacements = reserved.map(w => w.id -> w).toMap
      (s.copy(workspaces = s.workspaces.map(w => replacements.getOrElse(w.id, w))), Right(reserved))
  }

  /** Immutable authorization scope and reservation are committed in the same write. */
  def requestMerge(request: MergeRequest): Either[String, MergeRequest] = transaction { s =>
    s.requests.find(_.id == request.id) match
      case Some(existing) =>
        val same = existing.repository == request.repository && existing.mergerId == request.mergerId &&
          existing.authorization == request.authorization &&
          existing.items.map(i => (i.workspaceId, i.sourceCommit, i.targetBranch)) ==
          request.items.map(i => (i.workspaceId, i.sourceCommit, i.targetBranch))
        (s, if same then Right(existing) else Left("Request identity conflicts with its recorded authorization scope"))
      case None =>
        val selected = request.items.flatMap(i => s.workspaces.find(_.id == i.workspaceId))
        val invalid = request.id.isEmpty || request.authorization.trim.isEmpty || selected.isEmpty ||
          selected.size != request.items.size || selected.map(_.id).distinct.size != selected.size ||
          selected.exists(w => w.repository != request.repository || w.lifecycle != "open" || w.activity != "idle") ||
          request.items.exists(i =>
            !i.sourceCommit.matches("[0-9a-f]{40}|[0-9a-f]{64}") ||
              !selected.find(_.id == i.workspaceId).exists(_.baseBranch == i.targetBranch)
          ) ||
          !s.mergers.exists(m => m.repository == request.repository && m.delegateId == request.mergerId)
        if invalid then
          (s, Left("Merge request needs an existing merger, authorized scope, pinned commits, and idle workspaces"))
        else
          val ids = selected.map(_.id).toSet
          val next = s.workspaces.map(w =>
            if ids(w.id) then w.copy(lifecycle = "reserved", reservation = Some(request.id), revision = w.revision + 1)
            else w
          )
          val accepted = request.copy(
            status = "accepted",
            revision = 0L,
            cancelRequested = false,
            items = request.items.map(i => i.copy(phase = "reserved", mergeCommit = None, verification = None)),
            events = Map.empty
          )
          (s.copy(workspaces = next, requests = s.requests :+ accepted), Right(accepted))
        end if
  }

  def cancelRequest(id: String): Either[String, MergeRequest] = transaction { s =>
    s.requests.find(_.id == id) match
      case None => (s, Left("Unknown merge request"))
      case Some(r) if r.status == "completed" || r.status == "cancelled" => (s, Right(r))
      case Some(r) =>
        val next = r.copy(cancelRequested = true, revision = r.revision + 1)
        (s.copy(requests = s.requests.map(x => if x.id == id then next else x)), Right(next))
  }

  /** validate is a read-only facts check performed while the ledger is locked. */
  def reportMerge(id: String, caller: String, event: MergeReport)(
    validate: (Workspace, MergeItem, MergeReport) => Either[String, Unit]
  ): Either[String, MergeRequest] = transaction { s =>
    s.requests.find(_.id == id) match
      case None => (s, Left("Unknown merge request"))
      case Some(r) if r.mergerId != caller => (s, Left("Only the recorded merger may report this request"))
      case Some(r) if r.events.contains(event.eventId) =>
        (s, if r.events(event.eventId) == event.asJson.noSpaces then Right(r) else Left("Event identity conflict"))
      case Some(r)
          if s.requests
            .find(x =>
              x.repository == r.repository &&
                x.status != "completed" && x.status != "cancelled"
            )
            .exists(_.id != id) =>
        (s, Left("Another request must finish or pause resolution before this request can run"))
      case Some(r) if event.eventId.isEmpty || event.expectedRevision != r.revision =>
        (s, Left("Stale merge request revision"))
      case Some(r) =>
        val item = r.items.find(_.workspaceId == event.workspaceId)
        val workspace = s.workspaces.find(_.id == event.workspaceId)
        val firstOpen = r.items.find(i => i.phase != "completed" && i.phase != "cancelled")
        val allowed = Map(
          "reserved" -> Set("checking", "awaiting-user", "cancelled"),
          "checking" -> Set("merging", "awaiting-user", "failed", "cancelled"),
          "merging" -> Set("validating", "merged-cleanup-pending", "awaiting-user", "failed"),
          "awaiting-user" -> Set("checking", "merging", "validating", "cancelled"),
          "validating" -> Set("merged-cleanup-pending", "failed", "awaiting-user"),
          "merged-cleanup-pending" -> Set("completed"),
          "failed" -> Set("checking", "cancelled")
        )
        if item.isEmpty || workspace.isEmpty || !firstOpen.exists(_.workspaceId == event.workspaceId) ||
          !workspace.exists(_.reservation.contains(id)) ||
          !item.exists(i => allowed.getOrElse(i.phase, Set.empty).contains(event.phase))
        then (s, Left("Invalid or out-of-order merge transition"))
        else
          validate(workspace.get, item.get, event) match
            case Left(error) => (s, Left(error))
            case Right(_) =>
              val nextItem = item.get.copy(
                phase = event.phase,
                mergeCommit = event.mergeCommit.orElse(item.get.mergeCommit),
                verification = event.verification.orElse(item.get.verification)
              )
              val items = r.items.map(i => if i.workspaceId == event.workspaceId then nextItem else i)
              val status =
                if items.forall(_.phase == "completed") then "completed"
                else if items.forall(i => i.phase == "completed" || i.phase == "cancelled") then "cancelled"
                else if event.phase == "failed" || event.phase == "awaiting-user" then "paused"
                else "processing"
              val next = r.copy(
                items = items,
                status = status,
                revision = r.revision + 1,
                events = r.events.updated(event.eventId, event.asJson.noSpaces)
              )
              val lifecycle = event.phase match
                case "completed" => "closed-merged"
                case "cancelled" => "open"
                case phase => phase
              val workspaces = s.workspaces.map(w =>
                if w.id == event.workspaceId then
                  w.copy(
                    lifecycle = lifecycle,
                    reservation = if event.phase == "cancelled" then None else w.reservation,
                    revision = w.revision + 1
                  )
                else w
              )
              (
                s.copy(workspaces = workspaces, requests = s.requests.map(x => if x.id == id then next else x)),
                Right(next)
              )
        end if
  }

  def claimCleanup(id: String, revision: Long, token: String): Either[String, Workspace] = transaction { s =>
    s.workspaces.find(_.id == id) match
      case None => (s, Left("Unknown workspace"))
      case Some(w) if w.revision != revision => (s, Left("Workspace changed; inspect it again"))
      case Some(w) if !Set("open", "merged-cleanup-pending").contains(w.lifecycle) || w.activity != "idle" =>
        (s, Left("Workspace is busy, frozen, reserved or closed"))
      case Some(w) =>
        val next = w.copy(lifecycle = "cleaning", revision = w.revision + 1)
        val claim = CleanupClaim(token, w.lifecycle, removed = false, error = None)
        (
          s.copy(
            workspaces = s.workspaces.map(x => if x.id == id then next else x),
            cleanups = s.cleanups.updated(id, claim)
          ),
          Right(next)
        )
  }

  def finishCleanup(id: String, token: String, integrated: Boolean, error: Option[String]): Either[String, Workspace] =
    transaction { s =>
      (s.workspaces.find(_.id == id), s.cleanups.get(id)) match
        case (Some(w), Some(claim)) if w.lifecycle == "cleaning" && claim.token == token =>
          val lifecycle =
            if error.nonEmpty then claim.previousLifecycle
            else if claim.previousLifecycle == "merged-cleanup-pending" then "merged-cleanup-pending"
            else if integrated then "closed-merged"
            else "closed-discarded"
          val next = w.copy(lifecycle = lifecycle, revision = w.revision + 1)
          (
            s.copy(
              workspaces = s.workspaces.map(x => if x.id == id then next else x),
              cleanups = s.cleanups.updated(id, claim.copy(removed = error.isEmpty, error = error))
            ),
            Right(next)
          )
        case _ => (s, Left("Cleanup claim changed; reconcile the actual directory before continuing"))
    }

  def baseline(repository: String): String = snapshot.baselines.getOrElse(repository, "main")

  def setBaseline(repository: String, branch: String): Either[String, Unit] = transaction { s =>
    if branch.trim.isEmpty || branch.startsWith("delegate/") ||
      s.workspaces.exists(w => w.repository == repository && w.branch == branch && !w.lifecycle.startsWith("closed-"))
    then (s, Left("An unmerged development branch cannot be the creation baseline"))
    else (s.copy(baselines = s.baselines.updated(repository, branch)), Right(()))
  }

  def owned(owner: String): Option[Workspace] = snapshot.workspaces.find(_.owner == owner)

  /** Receiving deferred context must not claim execution or reopen a reserved/closed owner. */
  def checkOpen(owner: String): Either[String, Unit] = transaction { s =>
    val verdict = s.workspaces.find(_.owner == owner) match
      case Some(w) if w.lifecycle.startsWith("closed-") =>
        Left("Workspace is closed; its branch is retained. Create a new delegate explicitly")
      case Some(w) if w.lifecycle != "open" => Left("Workspace is reserved for merger; development cannot resume")
      case _ => Right(())
    (s, verdict)
  }

  /** Existing running owners may receive mail without opening another execution seat. */
  def admit(owner: String, allowFrozen: Boolean): Either[String, Unit] = transaction { s =>
    s.workspaces.find(_.owner == owner) match
      case None => (s, Right(())) // read-only / unmanaged legacy delegate
      case Some(w) if w.lifecycle.startsWith("closed-") =>
        (
          s,
          Left(
            if w.lifecycle == "closed-merged" then "delegate 已终态、worktree 已合并，请重新派发"
            else "delegate 已终态、worktree 已清理（分支保留），请重新派发"
          )
        )
      case Some(w) if w.lifecycle != "open" =>
        (s, Left("Workspace is reserved for merger; development cannot resume"))
      case Some(w) if w.activity == "frozen" && !allowFrozen =>
        (s, Left("Workspace needs explicit manual recovery"))
      case Some(w) if w.activity == "running" => (s, Right(()))
      case Some(w) =>
        val next = w.copy(activity = "running", revision = w.revision + 1)
        (s.copy(workspaces = s.workspaces.map(x => if x.id == w.id then next else x)), Right(()))
  }

  def settle(owner: String, frozen: Boolean): Unit = transaction { s =>
    val next = s.workspaces.map { w =>
      if w.owner == owner && w.lifecycle == "open" then
        w.copy(activity = if frozen then "frozen" else "idle", revision = w.revision + 1)
      else w
    }
    (s.copy(workspaces = next), ())
  }

  private def change(id: String, revision: Long)(f: Workspace => Either[String, Workspace]): Either[String, Workspace] =
    transaction { s =>
      s.workspaces.find(_.id == id) match
        case None => (s, Left("Unknown workspace"))
        case Some(w) if w.revision != revision => (s, Left("Stale workspace revision"))
        case Some(w) =>
          f(w) match
            case Left(error) => (s, Left(error))
            case Right(updated) =>
              val next = updated.copy(revision = w.revision + 1)
              (s.copy(workspaces = s.workspaces.map(x => if x.id == id then next else x)), Right(next))
    }

end WorkspaceLedger

object WorkspaceLedger:
  @volatile private var listener: (os.Path, Long) => Unit = (_, _) => ()
  def onChanged(callback: (os.Path, Long) => Unit): Unit = listener = callback

  private[delegate] def changed(path: os.Path, revision: Long): Unit =
    try listener(path, revision)
    catch case scala.util.control.NonFatal(_) => () // Notifications are hints; snapshots remain authoritative.

  def default: WorkspaceLedger = new WorkspaceLedger(nebflow.shared.PathUtil.dataRoot / "workspaces" / "ledger.json")

  def repositoryKey(workspace: os.Path): String =
    val common = os.proc("git", "rev-parse", "--git-common-dir").call(cwd = workspace).out.text().trim
    java.nio.file.Path
      .of(if java.nio.file.Path.of(common).isAbsolute then common else (workspace / os.RelPath(common)).toString)
      .toRealPath()
      .toString

  final case class Workspace(
    id: String,
    project: String,
    repository: String,
    path: String,
    branch: String,
    baseBranch: String,
    baseCommit: String,
    owner: String,
    ownerMode: String,
    lifecycle: String = "open",
    activity: String = "idle",
    reservation: Option[String] = None,
    revision: Long = 0L
  )
  final case class Merger(project: String, repository: String, delegateId: String)

  final case class MergerBootstrap(
    record: DelegateRegistry.Record,
    prompt: String,
    status: String = "initializing",
    error: Option[String] = None,
    everStarted: Boolean = false
  )
  given io.circe.Encoder[MergerBootstrap] = deriveEncoder

  given io.circe.Decoder[MergerBootstrap] = io.circe.Decoder.instance { c =>
    for
      record <- c.get[DelegateRegistry.Record]("record")
      prompt <- c.get[String]("prompt")
      status <- c.get[Option[String]]("status")
      error <- c.get[Option[String]]("error")
      started <- c.get[Option[Boolean]]("everStarted")
    yield MergerBootstrap(
      record,
      prompt,
      status.getOrElse("failed"),
      error,
      started.getOrElse(!status.contains("initializing"))
    )
  }

  final case class MergeItem(
    workspaceId: String,
    sourceCommit: String,
    targetBranch: String,
    phase: String = "reserved",
    mergeCommit: Option[String] = None,
    verification: Option[String] = None
  )

  final case class MergeRequest(
    id: String,
    repository: String,
    mergerId: String,
    authorization: String,
    items: List[MergeItem],
    status: String = "accepted",
    revision: Long = 0L,
    cancelRequested: Boolean = false,
    events: Map[String, String] = Map.empty
  )

  final case class MergeReport(
    eventId: String,
    expectedRevision: Long,
    workspaceId: String,
    phase: String,
    mergeCommit: Option[String] = None,
    verification: Option[String] = None
  )
  given io.circe.Codec[MergeItem] = deriveCodec
  given io.circe.Codec[MergeRequest] = deriveCodec
  given io.circe.Codec[MergeReport] = deriveCodec

  final case class CleanupClaim(token: String, previousLifecycle: String, removed: Boolean, error: Option[String])
  given io.circe.Codec[CleanupClaim] = deriveCodec

  final case class State(
    workspaces: List[Workspace] = Nil,
    mergers: List[Merger] = Nil,
    baselines: Map[String, String] = Map.empty,
    requests: List[MergeRequest] = Nil,
    cleanups: Map[String, CleanupClaim] = Map.empty,
    revision: Long = 0L,
    mergerBootstraps: Map[String, MergerBootstrap] = Map.empty
  )
  given io.circe.Codec[Workspace] = deriveCodec
  given io.circe.Codec[Merger] = deriveCodec
  given io.circe.Encoder[State] = deriveEncoder

  given io.circe.Decoder[State] = io.circe.Decoder.instance { c =>
    for
      workspaces <- c.get[List[Workspace]]("workspaces")
      mergers <- c.get[List[Merger]]("mergers")
      baselines <- c.get[Option[Map[String, String]]]("baselines")
      requests <- c.get[Option[List[MergeRequest]]]("requests")
      cleanups <- c.get[Option[Map[String, CleanupClaim]]]("cleanups")
      revision <- c.get[Option[Long]]("revision")
      bootstraps <- c.get[Option[Map[String, MergerBootstrap]]]("mergerBootstraps")
    yield State(
      workspaces,
      mergers,
      baselines.getOrElse(Map.empty),
      requests.getOrElse(Nil),
      cleanups.getOrElse(Map.empty),
      revision.getOrElse(0L),
      bootstraps.getOrElse(Map.empty)
    )
  }

  def resolveBaseline(workspace: os.Path, ledger: WorkspaceLedger = default): (String, String) =
    val repository = repositoryKey(workspace)
    val branch = ledger.baseline(repository)
    require(!branch.startsWith("delegate/"), "Development branch cannot be a creation baseline")
    require(
      !ledger.snapshot.workspaces.exists(w =>
        w.repository == repository && w.branch == branch &&
          !w.lifecycle.startsWith("closed-")
      ),
      "Baseline is an open development branch"
    )
    os.proc("git", "check-ref-format", "--branch", branch).call(cwd = workspace)
    val commit = os
      .proc("git", "rev-parse", "--verify", s"refs/heads/$branch^{commit}")
      .call(cwd = workspace)
      .out
      .text()
      .trim
    (branch, commit)
  end resolveBaseline
end WorkspaceLedger
