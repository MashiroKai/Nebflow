package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AtomicJson
import nebflow.core.mail.SessionMailbox
import nebflow.shared.NebflowLogger

import scala.util.Try
import scala.util.control.NonFatal

import WorkspaceLedger.*

/**
 * WorkspaceAdvisor — the main agent's workspace awareness face (worktree-merger
 * architecture §七 "主 agent 的工作区感知与主动整合").
 *
 * The advisor turns durable facts into AT MOST ONE proactive suggestion per
 * deliverable, delivered to the main-agent session that dispatched the
 * developer. It never creates merge requests, never touches reservations and
 * never merges: the suggestion is a message, and creating a real merge request
 * still requires an explicit user or agent action through the authorized
 * channels (panel selection or MergerRequest).
 *
 * == Candidate condition set (ALL must hold; none suffices alone) ==
 *   - the developer explicitly reported this delivery complete (a persisted
 *     DelegateRegistry finish report on the owner session);
 *   - the workspace is open and idle with no reservation;
 *   - occupancy is readable and empty (no non-terminal scope nodes, no live
 *     background jobs — same fail-closed readers as merge reservation);
 *   - the work is committed: ahead of the recorded base and clean;
 *   - no request item of this workspace is `awaiting-user` (no open question).
 *
 * idle / ahead / a finish report ALONE never suffice. One broken workspace
 * never blocks the others: readings are per-workspace, and an unreadable
 * occupancy or Git fact marks that workspace uncertain — no suggestion and no
 * staleness transition for it this round (fail-closed on that workspace's
 * writes, fail-soft on the other workspaces).
 *
 * == Durable suggestion store (`dataRoot/workspaces/suggestions.json`) ==
 *
 * Rows are keyed by (workspaceId, headCommit, baseBranch). The same key is
 * never re-suggested (no notification spam); a NEW headCommit on the same
 * workspace re-arms evaluation, because the deliverable really changed. Row
 * states: `suggested → stale | dismissed | integrated`, plus
 * `pending-coordination` when the responsible main session is unknown — facts
 * are persisted and nothing is broadcast (no owner-wide spray). When the
 * workspace stops being a candidate the row decays to `stale` (or
 * `integrated` when the deliverable demonstrably entered its base) on the
 * next evaluation; the state change is visible through the read face only.
 * Dismissal is explicit (`dismiss`); explicit manual merge requests are never
 * touched by this file.
 *
 * The store mirrors the ledger's durability shape: a file lock shared with
 * other processes, tmp+rename atomic writes, and fail-closed reads (a corrupt
 * store blocks suggestions instead of forgetting the dedupe history).
 *
 * Occupancy, registry and Git-facts readers are constructor-injected so specs
 * can feed synthetic readings; [[WorkspaceAdvisor.default]] wires the
 * production ones.
 */
final class WorkspaceAdvisor(
  val path: os.Path,
  occupancy: WorkspaceOccupancy = WorkspaceOccupancy.default,
  records: String => Option[DelegateRegistry.Record] = DelegateRegistry.find,
  facts: Workspace => WorkspaceFacts.GitReading = WorkspaceFacts.gitReading,
  doorbell: String => IO[Unit] = _ => IO.unit
):
  import WorkspaceAdvisor.*

  private val logger = NebflowLogger.forName("nebflow.core.delegate.workspace-advisor")

  // ── store (fail-closed transaction; same shape as the ledger) ─────────

  private def transaction[A](f: State => Either[String, (State, A)]): Either[String, A] =
    WorkspaceAdvisor.synchronized {
      try
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
                io.circe.parser.decode[State](os.read(path)) match
                  case Right(state) => Right(state)
                  case Left(error)  => Left(s"workspace suggestion store is unreadable (fail-closed): $error")
              else Right(State())
            before.flatMap { current =>
              f(current).map { case (after, result) =>
                if after != current then
                  AtomicJson.writeSync(path, after.copy(revision = current.revision + 1).asJson.noSpaces)
                result
              }
            }
          finally lock.release()
        finally channel.close()
        end try
      catch
        case NonFatal(error) =>
          Left(
            s"workspace suggestion store transaction failed (fail-closed): ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          )
    }

  /** Durable rows; corrupt data is an explicit `Left`, never a silent empty list. */
  def rows(): Either[String, List[Suggestion]] = transaction(s => Right(s, s.suggestions))

  /** Explicit dismissal (#26): one row, one state flip; merge requests are never touched. */
  def dismiss(workspaceId: String, headCommit: String): Either[String, Suggestion] =
    transaction { s =>
      s.suggestions.find(w => w.workspaceId == workspaceId && w.headCommit == headCommit) match
        case None => Left(s"No advisor suggestion for workspace $workspaceId at $headCommit")
        case Some(row) if row.state == StateDismissed => Right(s, row)
        case Some(row) =>
          val next = row.copy(state = StateDismissed, note = Some("dismissed by explicit request"), updatedAt = now())
          Right(s.copy(suggestions = s.suggestions.map(x => if x.key == row.key then next else x)), next)
    }

  // ── evaluation ─────────────────────────────────────────────────────────

  /**
   * Classify every ledger workspace and reconcile the store: new candidates
   * become `suggested` rows with one structured mail each; rows whose
   * workspace left the candidate set decay to `stale` (or `integrated`);
   * nothing here ever writes the ledger, Git or a merge request. One broken
   * workspace never blocks the others and nothing ever throws into the
   * caller (the gateway broadcast path included).
   */
  def evaluate(ledger: WorkspaceLedger): IO[Reading] =
    for
      snapshot <- IO.blocking(ledger.snapshot)
      readings <- snapshot.workspaces.traverse(w => readOne(w, snapshot))
      stored <- IO.blocking(rows())
      outcome <- stored match
        case Left(error) =>
          // Fail-closed: without the dedupe history no suggestion is created and
          // no row is rewritten; the readings themselves stay visible.
          IO(logger.warn(s"workspace advisor skipped evaluation: $error")).as(
            Reading(snapshot.revision, None, readings, Nil, List(error))
          )
        case Right(existing) =>
          IO
            .blocking(reconcile(snapshot, existing, readings))
            .flatMap { case (reading, pending) =>
              pending
                .traverse_ { case (row, recipient) => deliver(row, recipient) }
                .*>(
                  IO.blocking(rows()).map(finalRows => reading.copy(suggestions = finalRows.getOrElse(reading.suggestions)))
                )
            }
            .handleErrorWith { error =>
              val text = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
              IO(logger.warn(s"workspace advisor evaluation failed (fail-soft): $text")).as(
                Reading(snapshot.revision, None, readings, existing, List(text))
              )
            }
    yield outcome
  end evaluate

  /** Per-workspace candidate reading; one broken seat never blocks the others. */
  private def readOne(workspace: Workspace, snapshot: WorkspaceLedger.State): IO[WorkspaceReading] =
    val git = IO.blocking(Try(facts(workspace)).toEither.left.map(describe))
    val occupancyReading = occupancy.occupancyOf(workspace.owner).attempt.map {
      case Right(verdict) => verdict
      case Left(error)    => Left(describe(error))
    }
    (git, occupancyReading).mapN { (gitReading, occ) =>
      val finishReported = records(workspace.owner).exists(_.reportStatus.contains(FinishReport)) ||
        snapshot.completionConfirmations.valuesIterator.exists(c => c.workspaceId == workspace.id && c.owner == workspace.owner)
      val awaitingUser =
        snapshot.requests.exists(_.items.exists(i => i.workspaceId == workspace.id && i.phase == "awaiting-user"))
      val reasons = scala.collection.mutable.ListBuffer[String]()
      gitReading match
        case Left(error) => reasons += s"git facts are unavailable: $error"
        case Right(reading) =>
          if !reading.aheadCount.exists(_ > 0) then reasons += "no commits ahead of the recorded base branch"
          if !reading.dirtyTracked.contains(0) || !reading.dirtyUntracked.contains(0) then
            reasons += "uncommitted changes are present"
      if workspace.lifecycle != LifecycleOpen || workspace.activity != ActivityIdle || workspace.reservation.isDefined then
        reasons +=
          s"workspace is ${workspace.lifecycle}/${workspace.activity}${workspace.reservation.map(id => s" (reserved by $id)").getOrElse("")}"
      if !finishReported then reasons += "the developer has not reported this delivery complete (no finish report)"
      occ match
        case Left(error) => reasons += s"occupancy is unreadable (fail-closed): $error"
        case Right(Nil)  => ()
        case Right(busy) => reasons += busy.mkString("; ")
      if awaitingUser then reasons += "a request on this workspace is awaiting the user"
      WorkspaceReading(
        workspace.id,
        workspace.repository,
        workspace.owner,
        workspace.lifecycle,
        workspace.activity,
        if reasons.isEmpty then ClassSuggestable else ClassNotCandidate,
        reasons.toList,
        gitReading.toOption.flatMap(_.aheadCount),
        gitReading.toOption.flatMap(_.dirtyTracked),
        gitReading.toOption.flatMap(_.dirtyUntracked),
        gitReading.toOption.flatMap(_.headCommit),
        gitReading.left.toOption
      )
    }
  end readOne

  /**
   * Store reconciliation inside one transaction: insert fresh suggestion rows,
   * decay rows whose workspace left the candidate set, then deliver exactly
   * the rows this evaluation inserted — a replay can never double-suggest one
   * key because delivery happens only for rows whose insert won the race.
   */
  private def reconcile(
    snapshot: WorkspaceLedger.State,
    existing: List[Suggestion],
    readings: List[WorkspaceReading]
  ): (Reading, List[(Suggestion, String)]) =
    val byId = snapshot.workspaces.map(w => w.id -> w).toMap
    // A workspace that stopped being a candidate while carrying an active row reads as `stale`.
    val classified = readings.map(r =>
      if r.classification == ClassNotCandidate &&
        existing.exists(row => row.workspaceId == r.workspaceId && isActive(row.state))
      then r.copy(classification = ClassStale)
      else r
    )
    val decays = existing.filter(row => isActive(row.state)).flatMap { row =>
      decayOf(row, snapshot, byId, readings).map { case (state, note) => (row.key, state, note) }
    }
    val fresh = classified.filter(_.classification == ClassSuggestable).filterNot(r =>
      existing.exists(row => row.key == (r.workspaceId, r.headCommit.getOrElse(""), byId(r.workspaceId).baseBranch))
    )
    val inserts = fresh.map { reading =>
      val workspace = byId(reading.workspaceId)
      val record = records(workspace.owner)
      val recipient = record.map(_.parentSessionId).filter(_.trim.nonEmpty)
      val row = Suggestion(
        workspace.id,
        workspace.repository,
        workspace.owner,
        workspace.branch,
        workspace.baseBranch,
        reading.headCommit.getOrElse(""),
        if recipient.isDefined then StateSuggested else StatePending,
        taskTitle = record.map(_.title),
        aheadCount = reading.aheadCount,
        verificationPresent = Some(WorkspaceFacts.lastVerification(workspace.id, snapshot).isDefined),
        note =
          if recipient.isDefined then None
          else Some("the responsible main-agent session is unknown; facts kept, nothing broadcast"),
        createdAt = now(),
        updatedAt = now()
      )
      row -> recipient
    }
    val applied = transaction { s =>
      var next = s
      inserts.foreach { case (row, _) =>
        if !next.suggestions.exists(x => x.key == row.key) then next = next.copy(suggestions = next.suggestions :+ row)
      }
      decays.foreach { case (key, state, note) =>
        next = next.copy(suggestions = next.suggestions.map { row =>
          if row.key == key && isActive(row.state) then row.copy(state = state, note = Some(note), updatedAt = now())
          else row
        })
      }
      Right(next, ())
    }
    applied match
      case Left(error) =>
        logger.warn(s"workspace advisor store update refused (fail-closed): $error")
        (Reading(snapshot.revision, None, classified, existing, List(error)), Nil)
      case Right(_) =>
        val pending =
          inserts.collect { case (row, Some(recipient)) if row.state == StateSuggested => row -> recipient }
        (Reading(snapshot.revision, None, classified, Nil, Nil), pending)
  end reconcile

  /**
   * Delivery of one new suggestion (#21/#27): one durable mail to the main
   * session that dispatched the developer — structured, honest, and explicit
   * that this is a suggestion only. A failed delivery downgrades the row to
   * `pending-coordination` instead of pretending it was delivered.
   */
  private def deliver(row: Suggestion, recipient: String): IO[Unit] =
    val identity = mailIdentity(row.workspaceId, row.headCommit)
    val event = AgentCommand.ExternalEvent("advisor", "workspace-suggestion", message(row), correlationId = Some(identity))
    SessionMailbox
      .mailOnce(
        recipient,
        event,
        MailKind,
        identity,
        coalesce = false,
        producer = "workspace-advisor",
        deduplicateReceipts = true
      )
      .flatMap { result =>
        result match
          case _: SessionMailbox.MailResult.Failed =>
            val cause = result.toString
            logger.warn(s"workspace suggestion for ${row.workspaceId} could not be delivered: $cause")
            markPending(row, cause) *> doorbell(recipient)
          case _ =>
            markDelivered(row, recipient) *> doorbell(recipient)
      }
      .handleErrorWith { error =>
        val cause = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
        logger.warn(s"workspace suggestion delivery for ${row.workspaceId} failed: $cause")
        markPending(row, cause)
      }
  end deliver

  private def markDelivered(row: Suggestion, recipient: String): IO[Unit] =
    IO.blocking(transaction { s =>
      Right(
        s.copy(suggestions = s.suggestions.map(x =>
          if x.key == row.key then x.copy(deliveredTo = Some(recipient), updatedAt = now()) else x
        )),
        ()
      )
    }).void

  private def markPending(row: Suggestion, cause: String): IO[Unit] =
    IO.blocking(transaction { s =>
      Right(
        s.copy(suggestions = s.suggestions.map(x =>
          if x.key == row.key && x.state != StateDismissed then
            x.copy(state = StatePending, note = Some(s"delivery failed, facts kept: $cause"), updatedAt = now())
          else x
        )),
        ()
      )
    }).void

  /**
   * The decay verdict for one active row (#25): `integrated` when the
   * deliverable demonstrably entered its base, `stale` when the workspace
   * left the candidate set, `None` when the row must not be touched
   * (uncertainty — unreadable occupancy or Git facts keep the row as is).
   */
  private def decayOf(
    row: Suggestion,
    snapshot: WorkspaceLedger.State,
    byId: Map[String, Workspace],
    readings: List[WorkspaceReading]
  ): Option[(String, String)] =
    byId.get(row.workspaceId) match
      case None => Some(StateStale -> "the workspace is no longer recorded")
      case Some(workspace) =>
        val mergedProof = workspace.lifecycle == "closed-merged" ||
          snapshot.requests.exists(_.items.exists(i =>
            i.workspaceId == row.workspaceId && i.phase == "completed" && i.sourceCommit == row.headCommit
          ))
        if mergedProof then Some(StateIntegrated -> "the deliverable entered its base (merged and verified)")
        else
          workspace.lifecycle match
            case l if l.startsWith("closed-") =>
              Some(StateStale -> s"the workspace closed as $l without merging this deliverable")
            case "cleaning" => Some(StateStale -> "the workspace is being cleaned up")
            case other if other != LifecycleOpen => Some(StateStale -> s"the workspace is $other")
            case _ =>
              if workspace.activity != ActivityIdle then
                Some(StateStale -> s"development resumed (the workspace is ${workspace.activity})")
              else
                readings.find(_.workspaceId == row.workspaceId) match
                  case None => None // no reading this round: leave the row alone
                  case Some(r) =>
                    if r.error.isDefined then None // unreadable facts are uncertainty, not a decay trigger
                    else if r.headCommit.exists(head => head != row.headCommit) then
                      Some(StateStale -> "the branch HEAD moved since the suggestion")
                    else if r.reasons.nonEmpty then Some(StateStale -> r.reasons.head)
                    else None
  end decayOf

  /** Honest, structured suggestion text: facts first, the suggestion nature stated, no auto-merge. */
  private def message(row: Suggestion): String =
    val head = row.headCommit.take(12)
    val verification = row.verificationPresent match
      case Some(true)  => "verification evidence is on record."
      case Some(false) => "no verification evidence is on record."
      case None        => "verification evidence presence: unknown."
    s"""Workspace suggestion (origin: advisor): workspace "${row.workspaceId}" (branch ${row.branch}) looks ready for an integration decision.
       |Task: ${row.taskTitle.getOrElse("unknown — no delegate registry title")}
       |Repository: ${row.repository}
       |Commits ahead of ${row.baseBranch}: ${row.aheadCount.getOrElse("unknown")} (HEAD ${head}).
       |Tree: committed and clean. Developer finish report: recorded. $verification.
       |This is a SUGGESTION only — no merge request was created and nothing was merged. Creating one still requires your or the user's explicit action; the user's current instructions override this suggestion. Dismiss it with WorkspaceSummary(action="dismiss-suggestion") if it is unwanted.""".stripMargin

  private def describe(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private def isActive(state: String): Boolean = state == StateSuggested || state == StatePending

end WorkspaceAdvisor

object WorkspaceAdvisor:

  /** Row states (`suggested → stale | dismissed | integrated`, plus the undeliverable park). */
  val StateSuggested: String = "suggested"
  val StateStale: String = "stale"
  val StateDismissed: String = "dismissed"
  val StateIntegrated: String = "integrated"
  val StatePending: String = "pending-coordination"

  /** Reading classifications. */
  val ClassSuggestable: String = "suggestable"
  val ClassNotCandidate: String = "not-candidate"
  val ClassStale: String = "stale"

  val MailKind: String = "workspace-suggestion"
  val Origin: String = "advisor"

  /** The persisted finish-report value this advisor trusts (single source with the report tool). */
  val FinishReport: String = nebflow.core.tools.DelegateReportTool.FinishValue

  private val LifecycleOpen: String = "open"
  private val ActivityIdle: String = "idle"

  def mailIdentity(workspaceId: String, headCommit: String): String =
    s"workspace-suggestion:$workspaceId:$headCommit"

  private def now(): Long = System.currentTimeMillis()

  /** Production advisor over the shared data root (no doorbell; the gateway wires its own). */
  def default: WorkspaceAdvisor =
    new WorkspaceAdvisor(nebflow.shared.PathUtil.dataRoot / "workspaces" / "suggestions.json")

  /** Gateway variant: same store, plus a best-effort mailbox doorbell to wake a live main session. */
  def withDoorbell(doorbell: String => IO[Unit]): WorkspaceAdvisor =
    new WorkspaceAdvisor(nebflow.shared.PathUtil.dataRoot / "workspaces" / "suggestions.json", doorbell = doorbell)

  // ── durable shapes ─────────────────────────────────────────────────────

  /** One suggestion row, keyed by (workspaceId, headCommit, baseBranch). */
  final case class Suggestion(
    workspaceId: String,
    repository: String,
    owner: String,
    branch: String,
    baseBranch: String,
    headCommit: String,
    state: String,
    origin: String = Origin,
    taskTitle: Option[String] = None,
    aheadCount: Option[Int] = None,
    verificationPresent: Option[Boolean] = None,
    deliveredTo: Option[String] = None,
    note: Option[String] = None,
    createdAt: Long = 0L,
    updatedAt: Long = 0L
  ):
    def key: (String, String, String) = (workspaceId, headCommit, baseBranch)

  final case class State(suggestions: List[Suggestion] = Nil, revision: Long = 0L)

  /** Per-workspace candidate reading. */
  final case class WorkspaceReading(
    workspaceId: String,
    repository: String,
    owner: String,
    lifecycle: String,
    activity: String,
    classification: String,
    reasons: List[String],
    aheadCount: Option[Int],
    dirtyTracked: Option[Int],
    dirtyUntracked: Option[Int],
    headCommit: Option[String],
    error: Option[String]
  )

  /** One evaluation outcome: readings, the reconciled rows, and any fail-closed error. */
  final case class Reading(
    ledgerRevision: Long,
    storeRevision: Option[Long],
    workspaces: List[WorkspaceReading],
    suggestions: List[Suggestion],
    errors: List[String]
  )

  given io.circe.Encoder[Suggestion] = io.circe.generic.semiauto.deriveEncoder

  /** Missing optional keys read as None (forward-compatible with newer writers). */
  given io.circe.Decoder[Suggestion] = io.circe.Decoder.instance { c =>
    for
      workspaceId <- c.get[String]("workspaceId")
      repository <- c.get[String]("repository")
      owner <- c.get[String]("owner")
      branch <- c.get[String]("branch")
      baseBranch <- c.get[String]("baseBranch")
      headCommit <- c.get[String]("headCommit")
      state <- c.get[String]("state")
      origin <- c.get[Option[String]]("origin")
      taskTitle <- c.get[Option[String]]("taskTitle")
      aheadCount <- c.get[Option[Int]]("aheadCount")
      verificationPresent <- c.get[Option[Boolean]]("verificationPresent")
      deliveredTo <- c.get[Option[String]]("deliveredTo")
      note <- c.get[Option[String]]("note")
      createdAt <- c.get[Option[Long]]("createdAt")
      updatedAt <- c.get[Option[Long]]("updatedAt")
    yield Suggestion(
      workspaceId,
      repository,
      owner,
      branch,
      baseBranch,
      headCommit,
      state,
      origin.getOrElse(Origin),
      taskTitle,
      aheadCount,
      verificationPresent,
      deliveredTo,
      note,
      createdAt.getOrElse(0L),
      updatedAt.getOrElse(0L)
    )
  }

  given io.circe.Encoder[State] = io.circe.generic.semiauto.deriveEncoder

  given io.circe.Decoder[State] = io.circe.Decoder.instance { c =>
    for
      suggestions <- c.get[Option[List[Suggestion]]]("suggestions")
      revision <- c.get[Option[Long]]("revision")
    yield State(suggestions.getOrElse(Nil), revision.getOrElse(0L))
  }

end WorkspaceAdvisor
