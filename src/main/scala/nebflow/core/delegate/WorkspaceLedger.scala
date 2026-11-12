package nebflow.core.delegate

import io.circe.Json
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

  /** Read face: the recorded merger for a project/repository (the creation predicate's twin). */
  def recordedMerger(project: String, repository: String): Either[String, Merger] = transaction { s =>
    s.mergers.find(m => m.project == project || m.repository == repository) match
      case Some(existing) if existing.repository != repository =>
        (s, Left("Project repository changed; merger reconciliation required"))
      case Some(existing) => (s, Right(existing))
      case None => (s, Left("No merger is recorded for this repository"))
  }

  /**
   * Read face: the latest recorded release event for a project/repository — the replay
   * probe that lets callers distinguish "never recorded" (honest refusal) from
   * "already released" (idempotent replay of the recorded facts) after the identity
   * slot itself is gone, including across a process restart.
   */
  def releasedMergerEvent(project: String, repository: String): Either[String, MergerRelease] = transaction { s =>
    val latest = s.mergerEvents.values.toList
      .flatMap(payload => io.circe.parser.decode[MergerRelease](payload).toOption)
      .filter(r => r.project == project || r.repository == repository)
      .sortBy(_.releasedAt)
      .lastOption
    (s, latest.toRight("No merger is recorded for this repository").map(_.copy(replayed = true)))
  }

  /**
   * Release face (cancelled-merger recovery batch): the only supported way to retire
   * a merger identity whose delegate session was cancelled — the state that otherwise
   * wedges `Delegate(mode=merger)` forever (the dead address is returned with
   * taskAccepted=false and no supported replacement entry existed).
   *
   * Every gate is re-evaluated inside the ledger transaction (fresh state under the
   * file lock), so concurrent callers serialize and an in-flight worker pass cannot
   * slip between the checks and the write:
   *
   *   1. A recorded release event with the same id replays verbatim — idempotent for
   *      crash recovery, concurrent calls and late retries; a replacement merger
   *      created meanwhile is never touched through this path.
   *   2. The merger must exist for this project/repository (creation predicate twin)
   *      and still carry the expected delegate identity (race guard: the slot moved).
   *   3. Cancellation must be PROVEN by the delegate record's cancel tombstone,
   *      re-read inside the lock (recoverRequests precedent: durable evidence is
   *      validated while the state is pinned). A missing record or a missing
   *      tombstone refuses fail-closed — frozen mergers keep their own recovery path.
   *   4. No non-terminal merge request may exist for the repository: the mechanical
   *      worker owns those (never re-executed, never orphaned by an identity swap).
   *   5. No workspace may be occupied by merger activity: a reservation occupies
   *      only when it cannot be attributed to this seat's own terminal history
   *      ([[WorkspaceLedger.reservationOccupies]] — orphan record, foreign or
   *      item-mismatched request, or a request still in flight); a terminal
   *      request's historical reservation never blocks (`reportMerge` keeps it on
   *      completed items — the `closed-merged` residue every real ledger carries) —
   *      and resting in a merger-occupied lifecycle occupies.
   *
   * The write removes only the merger identity slot; requests, workspaces, cleanup
   * claims and the bootstrap seed (history) are preserved verbatim, and the release
   * facts are appended to `mergerEvents` (durable audit that outlives the registry's
   * terminal TTL). The replacement is minted afterwards through the normal creation
   * entry — [[merger]] via `Delegate(mode=merger)` — never here.
   */
  def releaseMerger(
    project: String,
    repository: String,
    expectedDelegateId: String,
    eventId: String,
    reason: String
  ): Either[String, MergerRelease] = transaction { s =>
    s.mergerEvents.get(eventId) match
      case Some(payload) =>
        io.circe.parser
          .decode[MergerRelease](payload)
          .fold(
            _ => (s, Left("Recorded merger release event is unreadable; inspect the ledger")),
            release => (s, Right(release.copy(replayed = true)))
          )
      case None =>
        s.mergers.find(m => m.project == project || m.repository == repository) match
          case Some(existing) if existing.repository != repository =>
            (s, Left("Project repository changed; merger reconciliation required"))
          case Some(existing) if existing.delegateId != expectedDelegateId =>
            (s, Left("The recorded merger identity changed; inspect it again"))
          case Some(existing) =>
            val record = DelegateRegistry.find(existing.delegateId)
            val tombstone = record.flatMap(_.cancelRequestedAt)
            val refusal =
              if record.isEmpty then
                Some("The merger's delegate record is missing; the state is unclear (fail-closed)")
              else if tombstone.isEmpty then
                Some("The merger is not cancelled (no cancel tombstone); it keeps its own recovery path")
              else if s.requests.exists(r => r.repository == repository && !TerminalRequestStatuses.contains(r.status)) then
                Some("Integration is in flight for this repository; the merger identity stays")
              else if s.workspaces.exists(w =>
                w.repository == repository &&
                  (reservationOccupies(w, s.requests) || MergerOccupiedLifecycles(w.lifecycle))
              ) then
                Some("A workspace is still occupied by merger activity; the merger identity stays")
              else None
            refusal match
              case Some(message) => (s, Left(message))
              case None =>
                val release = MergerRelease(
                  eventId = eventId,
                  project = project,
                  repository = repository,
                  releasedDelegateId = existing.delegateId,
                  replayed = false,
                  tombstoneAt = tombstone,
                  cancelledBy = record.flatMap(_.cancelRequestedBy),
                  reason = reason.take(300),
                  releasedAt = System.currentTimeMillis()
                )
                (
                  s.copy(
                    mergers = s.mergers.filterNot(m => m.delegateId == existing.delegateId),
                    mergerEvents = s.mergerEvents.updated(eventId, release.asJson.noSpaces)
                  ),
                  Right(release)
                )
          case None => (s, Left("No merger is recorded for this repository"))
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
          val created = request.copy(
            status = "submitted",
            revision = 0L,
            cancelRequested = false,
            items = request.items.map(i => i.copy(phase = "reserved", mergeCommit = None, verification = None)),
            events = Map.empty
          )
          (s.copy(workspaces = next, requests = s.requests :+ created), Right(created))
        end if
  }

  def cancelRequest(id: String): Either[String, MergeRequest] = transaction { s =>
    s.requests.find(_.id == id) match
      case None => (s, Left("Unknown merge request"))
      case Some(r) if TerminalRequestStatuses.contains(r.status) => (s, Right(r))
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
                Set("claimed", "verifying", "processing", "paused").contains(x.status)
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
          "reserved" -> Set("checking", "merged-cleanup-pending", "awaiting-user", "cancelled"),
          "checking" -> Set("merging", "merged-cleanup-pending", "awaiting-user", "failed", "cancelled"),
          "merging" -> Set("validating", "merged-cleanup-pending", "awaiting-user", "failed"),
          "awaiting-user" -> Set("checking", "merging", "validating", "merged-cleanup-pending", "cancelled"),
          "validating" -> Set("merged-cleanup-pending", "failed", "awaiting-user"),
          "merged-cleanup-pending" -> Set("completed"),
          "failed" -> Set("checking", "cancelled")
        )
        // An integration declaration closes an externally integrated (or recovered)
        // source without a new merge; the validate closure still proves the Git fact.
        val integrationLegal = event.integration.forall { kind =>
          kind match
            case "source-ancestor" =>
              event.phase == "merged-cleanup-pending" && event.mergeCommit.isEmpty &&
                item.exists(i => i.mergeCommit.isEmpty)
            case "merge-commit" =>
              Set("validating", "merged-cleanup-pending", "completed").contains(event.phase) &&
                item.forall(i => i.integration.forall(_ == "merge-commit"))
            case _ => false
        }
        // A closure landing without evidence of the integration (no recorded merge
        // commit on the item, none reported, no declared kind) is out of order.
        val bareClosure = item.exists(i =>
          event.phase == "merged-cleanup-pending" && i.mergeCommit.isEmpty &&
            event.mergeCommit.isEmpty && event.integration.isEmpty
        )
        if item.isEmpty || workspace.isEmpty || !firstOpen.exists(_.workspaceId == event.workspaceId) ||
          !workspace.exists(_.reservation.contains(id)) ||
          !item.exists(i => allowed.getOrElse(i.phase, Set.empty).contains(event.phase)) ||
          !integrationLegal || bareClosure
        then (s, Left("Invalid or out-of-order merge transition"))
        else
          validate(workspace.get, item.get, event) match
            case Left(error) => (s, Left(error))
            case Right(_) =>
              val nextItem = item.get.copy(
                phase = event.phase,
                mergeCommit = event.mergeCommit.orElse(item.get.mergeCommit),
                verification = event.verification.orElse(item.get.verification),
                integration = event.integration.orElse(item.get.integration),
                error = if event.phase == "completed" then None else event.note.orElse(item.get.error)
              )
              val items = r.items.map(i => if i.workspaceId == event.workspaceId then nextItem else i)
              val status = deriveRequestStatus(items)
              val next = r.copy(
                items = items,
                status = status,
                revision = r.revision + 1,
                events = r.events.updated(event.eventId, event.asJson.noSpaces)
              )
              val lifecycle = event.phase match
                case "completed" => "closed-merged"
                // Failed and cancelled items release their reservation: the
                // workspace opens for development while its branch stays put.
                case "cancelled" | "failed" => "open"
                case phase => phase
              val workspaces = s.workspaces.map(w =>
                if w.id == event.workspaceId then
                  w.copy(
                    lifecycle = lifecycle,
                    reservation =
                      if event.phase == "cancelled" || event.phase == "failed" then None else w.reservation,
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

  /** Worker face: claim the oldest queued request; None while another is in flight. */
  def claimNextMerge(): Option[String] = transaction { s =>
    val inFlight = Set("claimed", "verifying")
    if s.requests.exists(r => inFlight.contains(r.status)) then (s, None)
    else
      s.requests.find(r => Set("submitted", "accepted").contains(r.status)) match
        case None => (s, None)
        case Some(r) =>
          val next = r.copy(status = "claimed", revision = r.revision + 1)
          (s.copy(requests = s.requests.map(x => if x.id == r.id then next else x)), Some(r.id))
  }

  /**
   * Worker face: begin the first reserved item of a claimed request. Items run
   * strictly in order and only from the rest state; every non-terminal item is
   * either the one being begun or a later one still reserved.
   */
  def beginItem(id: String, caller: String, workspaceId: String): Either[String, MergeRequest] = transaction { s =>
    s.requests.find(_.id == id) match
      case None => (s, Left("Unknown merge request"))
      case Some(r) if r.mergerId != caller => (s, Left("Only the recorded merger may run this request"))
      case Some(r) if !Set("claimed", "verifying").contains(r.status) => (s, Left("Request is not being executed"))
      case Some(r) =>
        r.items.find(i => !TerminalItemPhases.contains(i.phase)) match
          case None => (s, Left("Request has no open item"))
          case Some(item) if item.workspaceId != workspaceId || item.phase != "reserved" =>
            (s, Left("Items run strictly in order and only from the rest state"))
          case Some(item) =>
            val next = r.copy(
              items = r.items.map(i => if i.workspaceId == workspaceId then item.copy(phase = "merging") else i),
              status = "verifying",
              revision = r.revision + 1,
              events = r.events.updated(s"begin:$workspaceId", Json.obj("phase" -> "merging".asJson).noSpaces)
            )
            (s.copy(requests = s.requests.map(x => if x.id == id then next else x)), Right(next))
  }

  /**
   * Worker face: close out everything that never integrated. Requires the
   * request to have failed or carry a recorded cancellation. Already integrated
   * items keep their integration (never undo); the rest are cancelled and their
   * workspaces reopen.
   */
  def releaseUnfinished(id: String, caller: String, reason: String): Either[String, MergeRequest] = transaction { s =>
    s.requests.find(_.id == id) match
      case None => (s, Left("Unknown merge request"))
      case Some(r) if r.mergerId != caller => (s, Left("Only the recorded merger may release this request"))
      case Some(r) if r.status != "failed" && !r.cancelRequested => (s, Left("Nothing to release"))
      case Some(r) =>
        val items = r.items.map { item =>
          if TerminalItemPhases.contains(item.phase) then item
          else
            val reading = RequestReconcile.readItem(r.repository, s.workspaces.find(_.id == item.workspaceId), item)
            reading.reading match
              case "integrated" =>
                item.copy(
                  phase = "merged-cleanup-pending",
                  mergeCommit = reading.mergeCommit.orElse(item.mergeCommit),
                  integration = reading.integration.orElse(item.integration)
                )
              case _ => item.copy(phase = "cancelled", error = Some(reason.take(300)))
        }
        val phaseById = items.map(i => i.workspaceId -> i.phase).toMap
        val workspaces = s.workspaces.map { w =>
          phaseById.get(w.id).flatMap {
            case "cancelled" =>
              if w.reservation.contains(id) || w.lifecycle != "open" then
                Some(w.copy(lifecycle = "open", reservation = None, revision = w.revision + 1))
              else None
            case "merged-cleanup-pending" =>
              if w.lifecycle != "merged-cleanup-pending" then
                Some(w.copy(lifecycle = "merged-cleanup-pending", revision = w.revision + 1))
              else None
            case _ => None
          }.getOrElse(w)
        }
        val next = r.copy(
          items = items,
          status = deriveRequestStatus(items),
          revision = r.revision + 1,
          events = r.events
            .updated(s"release:${r.revision}", Json.obj("reason" -> reason.take(300).asJson).noSpaces)
        )
        (s.copy(workspaces = workspaces, requests = s.requests.map(x => if x.id == id then next else x)), Right(next))
  }

  /**
   * Worker face: fail-closed valve. After an unexpected worker error, close out
   * the request instead of wedging the serial slot: an item whose source already
   * reached the target keeps its integration (merged-cleanup-pending, facts
   * recovered from Git), every other unfinished item fails with the abort
   * reason and its workspace reopens.
   */
  def abandonRequest(id: String, caller: String, error: String): Either[String, MergeRequest] = transaction { s =>
    s.requests.find(_.id == id) match
      case None => (s, Left("Unknown merge request"))
      case Some(r) if r.mergerId != caller => (s, Left("Only the recorded merger may abandon this request"))
      case Some(r) if TerminalRequestStatuses.contains(r.status) => (s, Right(r))
      case Some(r) =>
        val items = r.items.map { item =>
          if TerminalItemPhases.contains(item.phase) then item
          else
            val reading = RequestReconcile.readItem(r.repository, s.workspaces.find(_.id == item.workspaceId), item)
            reading.reading match
              case "integrated" =>
                item.copy(
                  phase = "merged-cleanup-pending",
                  mergeCommit = reading.mergeCommit.orElse(item.mergeCommit),
                  integration = reading.integration.orElse(item.integration)
                )
              case _ => item.copy(phase = "failed", error = Some(error.take(300)))
        }
        val phaseById = items.map(i => i.workspaceId -> i.phase).toMap
        val workspaces = s.workspaces.map { w =>
          phaseById.get(w.id).flatMap {
            case "failed" =>
              if w.reservation.contains(id) || w.lifecycle != "open" then
                Some(w.copy(lifecycle = "open", reservation = None, revision = w.revision + 1))
              else None
            case "merged-cleanup-pending" =>
              if w.lifecycle != "merged-cleanup-pending" then
                Some(w.copy(lifecycle = "merged-cleanup-pending", revision = w.revision + 1))
              else None
            case _ => None
          }.getOrElse(w)
        }
        val next = r.copy(
          items = items,
          status = deriveRequestStatus(items),
          revision = r.revision + 1,
          events = r.events.updated(s"abandon:${r.revision}", Json.obj("error" -> error.take(300).asJson).noSpaces)
        )
        (s.copy(workspaces = workspaces, requests = s.requests.map(x => if x.id == id then next else x)), Right(next))
  }

  /**
   * Boot face: normalize every in-flight request against actual Git state and
   * re-queue what the worker can resume. Terminal requests are untouched. An
   * integrated item keeps its recorded integration facts; a resumable item goes
   * back to the rest state for the serial worker; anything unreadable fails
   * with the reconcile detail (fact validation still guards every Git claim).
   * Reads Git inside the ledger lock, following the reportMerge precedent.
   */
  def recoverRequests(): Unit = transaction { s =>
    val inFlight = Set("submitted", "accepted", "claimed", "verifying", "processing", "paused")
    val next =
      if s.requests.exists(r => inFlight.contains(r.status)) then
        val requests2 = s.requests.map { r =>
          if !inFlight.contains(r.status) then r
          else
            val items = r.items.map { item =>
              if TerminalItemPhases.contains(item.phase) then item
              else
                val reading =
                  RequestReconcile.readItem(r.repository, s.workspaces.find(_.id == item.workspaceId), item)
                reading.reading match
                  case "integrated" =>
                    item.copy(
                      phase = "merged-cleanup-pending",
                      mergeCommit = reading.mergeCommit.orElse(item.mergeCommit),
                      integration = reading.integration.orElse(item.integration)
                    )
                  case "resumable" => item.copy(phase = "reserved", error = None)
                  case _ => item.copy(phase = "failed", error = Some(reading.detail.take(300)))
            }
            val derived = deriveRequestStatus(items)
            val status = if TerminalRequestStatuses.contains(derived) then derived else "submitted"
            if items == r.items && status == r.status then r
            else
              r.copy(
                items = items,
                status = status,
                revision = r.revision + 1,
                events = r.events.updated(s"recover:${r.revision}", Json.obj("status" -> status.asJson).noSpaces)
              )
        }
        val itemByWorkspace = requests2.flatMap(r => r.items.map(i => i.workspaceId -> i)).toMap
        val workspaces2 = s.workspaces.map { w =>
          itemByWorkspace.get(w.id).flatMap { item =>
            item.phase match
              case "merged-cleanup-pending" =>
                if w.lifecycle != "merged-cleanup-pending" then
                  Some(w.copy(lifecycle = "merged-cleanup-pending", revision = w.revision + 1))
                else None
              case "failed" | "cancelled" =>
                if w.lifecycle != "open" || w.reservation.isDefined then
                  Some(w.copy(lifecycle = "open", reservation = None, revision = w.revision + 1))
                else None
              case "reserved" =>
                if w.lifecycle != "reserved" then Some(w.copy(lifecycle = "reserved", revision = w.revision + 1))
                else None
              case _ => None
          }.getOrElse(w)
        }
        s.copy(requests = requests2, workspaces = workspaces2)
      else s
    (next, ())
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

  /** Root-authorized completion reconciliation; validation executes under the ledger file lock. */
  def reconcileCompletion(
    operation: CompletionConfirmation,
    validate: Workspace => Either[String, String]
  ): Either[String, CompletionConfirmation] = transaction { s =>
    s.completionConfirmations.get(operation.operationId) match
      case Some(existing) if existing.stage == "completed" =>
        val same = existing.copy(actualBranch = None, recordedAt = 0L, stage = "") == operation.copy(actualBranch = None, recordedAt = 0L, stage = "")
        (s, if same then Right(existing) else Left("Operation id conflicts with its recorded confirmation"))
      case _ =>
        s.workspaces.find(_.id == operation.workspaceId) match
          case None => (s, Left("Unknown workspace"))
          case Some(w) if w.revision != operation.expectedRevision => (s, Left("Stale workspace revision"))
          case Some(w) if w.owner != operation.owner || w.path != operation.expectedPath =>
            (s, Left("Workspace owner or exact path mismatch"))
          case Some(w) if w.lifecycle != "open" || w.activity != "idle" || w.reservation.nonEmpty =>
            (s, Left("Workspace must be open, idle and unreserved"))
          case Some(w) => validate(w) match
            case Left(error) => (s, Left(error))
            case Right(actualBranch) =>
              val confirmed = operation.copy(actualBranch = Some(actualBranch), stage = "completed")
              val updated = w.copy(branch = actualBranch, activity = "idle", revision = w.revision + 1)
              (s.copy(workspaces = s.workspaces.map(x => if x.id == w.id then updated else x),
                completionConfirmations = s.completionConfirmations.updated(operation.operationId, confirmed)), Right(confirmed))
  }

  /**
   * Root-explicit stale-running recovery: the SAME atomic completion as
   * [[reconcileCompletion]] but gated on `activity == "running"` — for seats whose
   * owner session truly ended while the ledger still says running. All identity /
   * revision / pin-HEAD / dirty checks and the single-transaction transition
   * (running → idle + branch correction + confirmation) are identical; a live
   * claim can never interleave because claimDevelopment requires `idle` and the
   * revision CAS fails after our +1.
   */
  def reconcileRunningCompletion(
    operation: CompletionConfirmation,
    validate: Workspace => Either[String, String]
  ): Either[String, CompletionConfirmation] = transaction { s =>
    s.completionConfirmations.get(operation.operationId) match
      case Some(existing) if existing.stage == "completed" =>
        val same = existing.copy(actualBranch = None, recordedAt = 0L, stage = "") == operation.copy(actualBranch = None, recordedAt = 0L, stage = "")
        (s, if same then Right(existing) else Left("Operation id conflicts with its recorded confirmation"))
      case _ =>
        s.workspaces.find(_.id == operation.workspaceId) match
          case None => (s, Left("Unknown workspace"))
          case Some(w) if w.revision != operation.expectedRevision => (s, Left("Stale workspace revision"))
          case Some(w) if w.owner != operation.owner || w.path != operation.expectedPath =>
            (s, Left("Workspace owner or exact path mismatch"))
          case Some(w) if w.lifecycle != "open" || w.activity != "running" || w.reservation.nonEmpty =>
            (s, Left("Running recovery requires an open, running, unreserved workspace"))
          case Some(w) => validate(w) match
            case Left(error) => (s, Left(error))
            case Right(actualBranch) =>
              val confirmed = operation.copy(actualBranch = Some(actualBranch), stage = "completed")
              val updated = w.copy(branch = actualBranch, activity = "idle", revision = w.revision + 1)
              (s.copy(workspaces = s.workspaces.map(x => if x.id == w.id then updated else x),
                completionConfirmations = s.completionConfirmations.updated(operation.operationId, confirmed)), Right(confirmed))
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

  /** Request states no worker may enter again (the old `completed` is legacy). */
  private[delegate] val TerminalRequestStatuses = Set("merged", "failed", "cancelled", "completed")

  /** Item phases a worker never touches again. */
  private[delegate] val TerminalItemPhases = Set("completed", "cancelled", "merged-cleanup-pending", "failed")

  /**
   * Workspace lifecycles that mean merger activity is coupled to the seat (reserved
   * for a queued request, or resting in a merger phase). A cancelled merger identity
   * is not releasable while any of these hold; open/closed/development states are not
   * merger occupation — including the historical reservations those states carry,
   * which occupy only per [[reservationOccupies]].
   */
  private[delegate] val MergerOccupiedLifecycles =
    Set("reserved", "checking", "merging", "validating", "awaiting-user", "merged-cleanup-pending")

  /**
   * Reservation occupation verdict for the release gate. A reservation occupies only
   * when it cannot be safely attributed to this seat's own terminal history: a missing
   * request record (orphan — unclear, fail-closed), a foreign repository or a request
   * without this workspace's item (mismatched — the seat cannot be attributed), or a
   * request still in flight all occupy. An attributable TERMINAL request
   * ([[TerminalRequestStatuses]]) is historical residue — `reportMerge` keeps the
   * reservation when an item completes (the workspace rests in `closed-merged`), and
   * real ledgers carry those rows — and never blocks a release.
   */
  private[delegate] def reservationOccupies(w: Workspace, requests: List[MergeRequest]): Boolean =
    w.reservation.exists { requestId =>
      requests.find(_.id == requestId) match
        case None => true // orphan reservation: safety cannot be confirmed
        case Some(r) =>
          val attributable = r.repository == w.repository && r.items.exists(_.workspaceId == w.id)
          !attributable || !TerminalRequestStatuses.contains(r.status)
    }

  def default: WorkspaceLedger = new WorkspaceLedger(nebflow.shared.PathUtil.dataRoot / "workspaces" / "ledger.json")

  def repositoryKey(workspace: os.Path): String =
    val common = os.proc("git", "rev-parse", "--git-common-dir").call(cwd = workspace).out.text().trim
    java.nio.file.Path
      .of(if java.nio.file.Path.of(common).isAbsolute then common else (workspace / os.RelPath(common)).toString)
      .toRealPath()
      .toString

  /**
   * Request status derived from item phases (the worker's single truth). Any
   * open item keeps the request in flight — including one awaiting cleanup,
   * whose closure legs still belong to the worker. In order of severity: a
   * failed item marks the whole request failed until a retry or release
   * resolves it; its merged-cleanup-pending siblings stay single-listed for the
   * manual cleanup retry (claimCleanup accepts that lifecycle).
   */
  private[delegate] def deriveRequestStatus(items: List[MergeItem]): String =
    val inFlight = Set("reserved", "checking", "merging", "validating", "awaiting-user", "merged-cleanup-pending")
    if items.exists(i => inFlight.contains(i.phase)) then
      if items.exists(_.phase == "failed") then "failed" else "verifying"
    else if items.exists(_.phase == "failed") then "failed"
    else if items.forall(_.phase == "completed") then "merged"
    else "cancelled"

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

  /**
   * Durable release record for a cancelled merger identity (cancelled-merger recovery
   * batch). Stored as JSON in `State.mergerEvents` keyed by the deterministic event id
   * (`release-merger:<repository>:<delegateId>:<tombstoneAt>`): the replay key that
   * makes concurrent calls and crash retries idempotent, and the audit facts that
   * outlive the delegate registry's terminal TTL.
   */
  final case class MergerRelease(
    eventId: String,
    project: String,
    repository: String,
    releasedDelegateId: String,
    replayed: Boolean,
    tombstoneAt: Option[Long],
    cancelledBy: Option[String],
    reason: String,
    releasedAt: Long
  )
  given io.circe.Codec[MergerRelease] = deriveCodec

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
    verification: Option[String] = None,
    integration: Option[String] = None,
    error: Option[String] = None
  )

  final case class MergeRequest(
    id: String,
    repository: String,
    mergerId: String,
    authorization: String,
    items: List[MergeItem],
    status: String = "submitted",
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
    verification: Option[String] = None,
    integration: Option[String] = None,
    note: Option[String] = None
  )
  given io.circe.Codec[MergeItem] = deriveCodec
  given io.circe.Codec[MergeRequest] = deriveCodec
  given io.circe.Codec[MergeReport] = deriveCodec

  final case class CleanupClaim(token: String, previousLifecycle: String, removed: Boolean, error: Option[String])
  given io.circe.Codec[CleanupClaim] = deriveCodec

  final case class CompletionConfirmation(
    operationId: String,
    workspaceId: String,
    owner: String,
    expectedPath: String,
    expectedHead: String,
    expectedRevision: Long,
    acceptanceEvidence: String,
    explicitRootConfirmation: String,
    actualBranch: Option[String] = None,
    recordedAt: Long = 0L,
    stage: String = "completed",
    recoverRunning: Boolean = false
  )
  given io.circe.Codec[CompletionConfirmation] = deriveCodec

  final case class State(
    workspaces: List[Workspace] = Nil,
    mergers: List[Merger] = Nil,
    baselines: Map[String, String] = Map.empty,
    requests: List[MergeRequest] = Nil,
    cleanups: Map[String, CleanupClaim] = Map.empty,
    revision: Long = 0L,
    mergerBootstraps: Map[String, MergerBootstrap] = Map.empty,
    /** Durable release events for cancelled merger identities (keyed by event id). */
    mergerEvents: Map[String, String] = Map.empty,
    completionConfirmations: Map[String, CompletionConfirmation] = Map.empty
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
      mergerEvents <- c.get[Option[Map[String, String]]]("mergerEvents")
      confirmations <- c.get[Option[Map[String, CompletionConfirmation]]]("completionConfirmations")
    yield State(
      workspaces,
      mergers,
      baselines.getOrElse(Map.empty),
      requests.getOrElse(Nil),
      cleanups.getOrElse(Map.empty),
      revision.getOrElse(0L),
      bootstraps.getOrElse(Map.empty),
      mergerEvents.getOrElse(Map.empty),
      confirmations.getOrElse(Map.empty)
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
