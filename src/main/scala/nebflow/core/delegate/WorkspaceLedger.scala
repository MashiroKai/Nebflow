package nebflow.core.delegate

import io.circe.generic.semiauto.*
import io.circe.syntax.*

/**
 * Read-only compatibility face for the retired managed-workspace ledger
 * (`workspaces/ledger.json`).
 *
 * The engine no longer allocates, reserves, merges or cleans delegate
 * worktrees, and the dedicated merger is gone: agents manage their own git
 * worktrees. The ledger file itself is historical data — workspaces, merger
 * identities, merge requests and cleanups that were recorded while the
 * mechanism was live. This object only decodes it:
 *
 *   - boot lists the in-flight merge requests (one log line each) so the
 *     backlog stays visible for manual disposition;
 *   - the project panel's workspace view renders the recorded rows;
 *   - safety guards (for example the terminal-TTL sweep) still see which
 *     records are tied to legacy ledger rows.
 *
 * Nothing here writes the file or touches Git. A missing file decodes as an
 * empty state; an unreadable one fails closed, exactly like the read face the
 * ledger had while it was live.
 */
final class WorkspaceLedger(val path: os.Path):

  /** Single-read snapshot of the recorded state (fail-closed on corrupt data). */
  def snapshot: WorkspaceLedger.State =
    if !os.exists(path) then WorkspaceLedger.State()
    else
      io.circe.parser
        .decode[WorkspaceLedger.State](os.read(path))
        .fold(e => throw new IllegalStateException("Workspace ledger is unreadable", e), identity)

end WorkspaceLedger

object WorkspaceLedger:

  def default: WorkspaceLedger = new WorkspaceLedger(nebflow.shared.PathUtil.dataRoot / "workspaces" / "ledger.json")

  /**
   * One repository identity per set of aliases of the same repository: the
   * realpath of the git common dir (a worktree and its main repo share one
   * key). Used as a pure Git-facts utility by the settlement diff face when it
   * annotates cross-repo closures.
   */
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

  /** Request states no worker may enter again (the old `completed` is legacy). */
  private[delegate] val TerminalRequestStatuses = Set("merged", "failed", "cancelled", "completed")

  /** Item phases a worker never touched again. */
  private[delegate] val TerminalItemPhases = Set("completed", "cancelled", "merged-cleanup-pending", "failed")

  /**
   * Boot listing of the backlog the retired mechanism left behind: every
   * non-terminal merge request as one structured line. Read-only — nothing is
   * resumed, merged, cleaned or rewritten; manual disposition stays with the
   * author.
   */
  def listInFlightRequests(snapshot: State): List[String] =
    val inFlight = Set("submitted", "accepted", "claimed", "verifying", "processing", "paused")
    snapshot.requests
      .filter(r => inFlight.contains(r.status))
      .map { r =>
        val items = r.items.map(i => s"${i.workspaceId}:${i.phase}").mkString(", ")
        s"merge request '${r.id}' (repository=${r.repository}, merger=${r.mergerId}, status=${r.status}) items [$items] — recorded before the merger retired; disposition is manual"
      }

end WorkspaceLedger
