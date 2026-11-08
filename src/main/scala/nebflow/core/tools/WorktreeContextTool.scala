package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.delegate.{DelegateRegistry, DualModeConfig, WorkspaceFacts, WorkspaceLedger}
import nebflow.core.mail.SessionMailbox
import nebflow.core.project.{DelegateFlowHosts, FlowMapState, NodeLifecycle}

import scala.util.Try

/**
 * worktree_context — read-only assembly of one managed workspace's full context
 * for the project merger and the main agent (worktree-merger architecture §六).
 *
 * Summary first, then pagination: the workspace record, the linked task brief,
 * the owner's node graph summary, verification evidence, recent commits and the
 * recorded merge/decision history are bounded summaries; the session mail
 * transcript (owner + merger mailboxes) is paginated via `pageToken`/`pageSize`.
 *
 * Every section names its source and states its missing range explicitly — the
 * caller must never believe it has seen history that does not exist ("transcript
 * not persisted", "no verification record", "no chain/graph record"). Sections
 * fail soft individually; a broken section carries the failure reason instead of
 * aborting the assembly. The read scope is the authorized project repository
 * only: a recorded merger may read workspaces of its own repository, and the
 * main agent may read any managed workspace.
 *
 * Authorization is the twin-safeguard pattern of [[MergerReportTool]] /
 * [[MergerRequestTool]]: the mount face decides which sessions see the schema,
 * and this identity check refuses fail-closed (a plain development delegate, an
 * ask session or a bare REST call gets `Left`).
 */
object WorktreeContextTool extends Tool:
  val Name = "worktree_context"
  def name: String = Name

  def description: String =
    "Read the assembled context of one managed workspace before integrating it: workspace record, linked task brief, " +
      "owner node graph, verification evidence, recent commits and recorded merge history, plus the paginated session " +
      "mail transcript. Every section names its source and its missing range; never claim you have read history that a " +
      "section reports as missing. Main agent and merger-mode sessions only."

  private val MaxPageSize = 50
  private val DefaultPageSize = 20
  private val DefaultCommitCount = 20
  private val PreviewCap = 600

  def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "workspaceId" -> Json.obj("type" -> "string".asJson),
      "requestId" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Optional merge request scoping for the verification and history sections".asJson
      ),
      "pageToken" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> "Mail transcript offset; pass back nextPageToken to continue".asJson
      ),
      "pageSize" -> Json.obj("type" -> "integer".asJson, "description" -> s"Mail page size, capped at $MaxPageSize".asJson)
    ),
    "required" -> List("workspaceId").asJson
  )
  def summarize(input: JsonObject): String = s"worktree_context(${input("workspaceId").flatMap(_.asString).getOrElse("?")})"
  def summarizeResult(input: JsonObject, result: String): String = result.take(200)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    IO.blocking(checked(input, ctx)).flatMap {
      case Left(error) => IO.pure(Left(error))
      case Right((workspace, snapshot, offset, pageSize, requestScope)) =>
        // The scope-graph registry is IO-carried; read it on the IO layer, then
        // assemble everything else inside one blocking section (fail-soft per section).
        DelegateFlowHosts.get(workspace.owner).flatMap { host =>
          val graph = host match
            case None => IO.pure(None)
            case Some(h) => h.store.snapshot.attempt.map(_.toOption)
          graph.flatMap(nodes => IO.blocking(assemble(workspace, snapshot, offset, pageSize, requestScope, nodes)).map(Right(_)))
        }.handleError(e => Left(ToolError(Option(e.getMessage).getOrElse("worktree_context failed"))))
    }

  /** Authorization + parameter decoding + ledger load (blocking, single point). */
  private def checked(
    input: JsonObject,
    ctx: ToolContext
  ): Either[ToolError, (WorkspaceLedger.Workspace, WorkspaceLedger.State, Int, Int, Option[String])] =
    val caller = ctx.sessionId.getOrElse("")
    val mergerMode = DelegateRegistry.modeFaceOf(ctx.sessionId).contains(DualModeConfig.ModeMerger)
    if !ctx.isRootAgent && !mergerMode then
      Left(ToolError("worktree_context is limited to the main agent and merger-mode sessions"))
    else
      val c = Json.fromJsonObject(input).hcursor
      val workspaceId = c.get[String]("workspaceId").getOrElse("")
      val pageSize = c.get[Int]("pageSize").toOption.filter(_ > 0).map(_.min(MaxPageSize)).getOrElse(DefaultPageSize)
      val offset = c.get[Int]("pageToken").toOption.filter(_ >= 0).getOrElse(0)
      val requestScope = c.get[String]("requestId").toOption.filter(_.trim.nonEmpty)
      val ledger = WorkspaceLedger.default
      val snapshot = ledger.snapshot
      snapshot.workspaces.find(_.id == workspaceId) match
        case None => Left(ToolError("Unknown workspace"))
        case Some(workspace) =>
          // A recorded merger reads only inside its own repository; the main agent may
          // read any managed workspace. Nothing outside that repository is touched.
          snapshot.mergers.find(_.delegateId == caller) match
            case Some(m) if m.repository != workspace.repository =>
              Left(ToolError("Workspace is outside your authorized repository"))
            case _ => Right((workspace, snapshot, offset, pageSize, requestScope))

  private def assemble(
    workspace: WorkspaceLedger.Workspace,
    snapshot: WorkspaceLedger.State,
    offset: Int,
    pageSize: Int,
    requestScope: Option[String],
    graph: Option[FlowMapState]
  ): String =
    Json
      .obj(
        "workspaceId" -> workspace.id.asJson,
        "sections" -> Json.obj(
          "workspace" -> Json.obj("source" -> "workspace ledger".asJson, "workspace" -> workspace.asJson),
          "task" -> taskBrief(workspace),
          "mail" -> mailSection(workspace, snapshot, offset, pageSize),
          "nodes" -> nodeSummary(workspace, graph),
          "verification" -> verificationSection(workspace, snapshot, requestScope),
          "commits" -> commitSection(workspace),
          "history" -> historySection(workspace, snapshot, requestScope)
        ),
        "pagination" -> mailPagination(workspace, snapshot, offset, pageSize)
      )
      .noSpaces

  /** Linked task brief: owner registry record → taskId → the unified task ledger. */
  private def taskBrief(workspace: WorkspaceLedger.Workspace): Json =
    val ownerRecord = Try(DelegateRegistry.find(workspace.owner)).toOption.flatten
    val entry = ownerRecord.flatMap(_.taskId).flatMap(id => Try(nebflow.core.project.TaskLedgerStore.open().findSync(id)).toOption.flatten)
    (ownerRecord, entry) match
      case (None, _) =>
        missingSection("delegate registry", s"the owner ${workspace.owner} has no delegate registry record")
      case (Some(_), None) =>
        missingSection("delegate registry and task ledger", "the owner record carries no linked task entry")
      case (Some(_), Some(task)) =>
        Json.obj(
          "source" -> "task ledger".asJson,
          "task" -> Json
            .obj(
              "id" -> task.id.asJson,
              "title" -> task.title.asJson,
              "status" -> task.status.asJson,
              "assignee" -> task.assignee.asJson,
              "createdAt" -> task.createdAt.asJson,
              "updatedAt" -> task.updatedAt.asJson,
              "closedAt" -> task.closedAt.asJson,
              "completedAt" -> task.completedAt.asJson
            )
            .asJson
        )

  /** One session mailbox reading: readability, entries, and the honest empty marker. */
  private def mailboxOf(sid: String): Mailbox =
    // An absent mailbox directory is the normal "never received mail" state, not a
    // read failure; only a failing scan reads as unreadable.
    val entries = Try(SessionMailbox.scanPendingSync(sid) ++ SessionMailbox.scanTakenSync(sid)).toOption
    Mailbox(sid, entries)

  private final case class Mailbox(sid: String, entries: Option[List[SessionMailbox.Entry]]):
    def rows: List[(String, SessionMailbox.Entry)] = entries.toList.flatten.map(e => (sid, e))
    def missing: Option[String] = entries match
      case None      => Some(s"mailbox unreadable: $sid")
      case Some(Nil) => Some(s"no mail recorded for $sid; transcript not persisted here")
      case Some(_)   => None

  /** All transcript rows of one workspace's owner and merger sessions, FIFO order. */
  private def mailboxes(workspace: WorkspaceLedger.Workspace, snapshot: WorkspaceLedger.State): List[Mailbox] =
    val mergerId = snapshot.mergers.find(_.repository == workspace.repository).map(_.delegateId)
    (List(workspace.owner) ++ mergerId.toList).distinct.map(mailboxOf)

  private def mailSection(workspace: WorkspaceLedger.Workspace, snapshot: WorkspaceLedger.State, offset: Int, pageSize: Int): Json =
    val boxes = Try(mailboxes(workspace, snapshot)).getOrElse(Nil)
    val rows = boxes.flatMap(_.rows).sortBy(_._2.seq)
    val slice = rows.slice(offset, math.min(offset + pageSize, rows.length))
    val missing =
      if boxes.isEmpty then List("no owner or merger session is recorded for this workspace")
      else boxes.flatMap(_.missing)
    Json.obj(
      "source" -> "session mailbox (pending + taken) of the owner and merger sessions".asJson,
      "entries" -> slice
        .map((sid, e) =>
          Json.obj(
            "session" -> sid.asJson,
            "seq" -> e.seq.asJson,
            "kind" -> e.kind.asJson,
            "enqueuedAt" -> e.enqueuedAt.asJson,
            "producer" -> e.producer.asJson,
            "payload" -> e.event.payload.take(PreviewCap).asJson
          )
        )
        .asJson,
      "missing" -> missing.asJson
    )

  private def mailPagination(workspace: WorkspaceLedger.Workspace, snapshot: WorkspaceLedger.State, offset: Int, pageSize: Int): Json =
    val total = Try(mailboxes(workspace, snapshot).flatMap(_.rows).size).getOrElse(0)
    Json.obj(
      "section" -> "mail".asJson,
      "offset" -> offset.asJson,
      "pageSize" -> pageSize.asJson,
      "total" -> total.asJson,
      "nextPageToken" -> (if offset + pageSize < total then Some(offset + pageSize) else None).asJson
    )

  /** Owner scope-graph summary; a missing host is an explicit fact, not an empty success. */
  private def nodeSummary(workspace: WorkspaceLedger.Workspace, graph: Option[FlowMapState]): Json =
    graph match
      case None =>
        Json.obj(
          "source" -> "delegate flow hosts".asJson,
          "missing" -> s"no chain/graph record for owner ${workspace.owner}".asJson
        )
      case Some(state) =>
        val nodes = state.nodes.valuesIterator.toList.sortBy(n => (n.createdAt, n.id))
        Json.obj(
          "source" -> "owner scope flow graph".asJson,
          "nodes" -> nodes
            .map(n =>
              Json.obj(
                "id" -> n.id.asJson,
                "name" -> n.name.asJson,
                "status" -> n.status.asJson,
                "terminal" -> NodeLifecycle.Terminal.contains(n.status).asJson,
                "taskPreview" -> n.task.map(_.take(PreviewCap)).asJson,
                "resultPreview" -> n.result.map(_.take(PreviewCap)).asJson
              )
            )
            .asJson,
          "missing" -> (if nodes.isEmpty then Some("the owner's graph has no nodes") else None).asJson
        )

  /** Verification evidence files referenced by this workspace's request items. */
  private def verificationSection(
    workspace: WorkspaceLedger.Workspace,
    snapshot: WorkspaceLedger.State,
    requestScope: Option[String]
  ): Json =
    val rows = Try(WorkspaceFacts.verificationReadings(workspace.id, snapshot)).getOrElse(Nil)
    val scoped = requestScope.map(id => rows.filter(r => r.hcursor.get[String]("requestId").toOption.contains(id))).getOrElse(rows)
    val anyProof = scoped.exists(r => r.hcursor.downField("verification").focus.exists(_ != Json.Null))
    Json.obj(
      "source" -> "verification evidence files referenced by the workspace ledger".asJson,
      "records" -> scoped.asJson,
      "missing" -> (if anyProof then None else Some("no verification record for this workspace")).asJson
    )

  /** Bounded recent-commit list of the workspace branch. */
  private def commitSection(workspace: WorkspaceLedger.Workspace): Json =
    Try {
      val out = os
        .proc(
          "git",
          "--git-dir",
          workspace.repository,
          "log",
          "--format=%H%x09%h%x09%s%x09%P",
          s"--max-count=$DefaultCommitCount",
          s"refs/heads/${workspace.branch}"
        )
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        .out
        .text()
        .trim
      if out.isEmpty then throw new IllegalStateException(s"branch ${workspace.branch} has no commits or is gone")
      val commits = out.linesIterator
        .map { l =>
          l.split('\t').toList match
            case full :: short :: subject :: parents :: Nil =>
              Json.obj(
                "commit" -> full.asJson,
                "short" -> short.asJson,
                "subject" -> subject.asJson,
                "parents" -> parents.asJson
              )
            case _ => Json.obj("commit" -> l.asJson)
        }
        .toList
      Json.obj(
        "source" -> s"git log refs/heads/${workspace.branch} (max $DefaultCommitCount)".asJson,
        "commits" -> commits.asJson
      )
    }.fold(
      _ => missingSection("git log", s"branch ${workspace.branch} is unreadable"),
      json => json
    )

  /** Recorded merge/decision history: every request that carried this workspace. */
  private def historySection(
    workspace: WorkspaceLedger.Workspace,
    snapshot: WorkspaceLedger.State,
    requestScope: Option[String]
  ): Json =
    val rows = snapshot.requests
      .filter(r => r.repository == workspace.repository && r.items.exists(_.workspaceId == workspace.id))
      .filter(r => requestScope.forall(_ == r.id))
      .map(r =>
        Json.obj(
          "requestId" -> r.id.asJson,
          "status" -> r.status.asJson,
          "cancelRequested" -> r.cancelRequested.asJson,
          "item" -> r.items
            .find(_.workspaceId == workspace.id)
            .map(i =>
              Json.obj(
                "phase" -> i.phase.asJson,
                "sourceCommit" -> i.sourceCommit.asJson,
                "targetBranch" -> i.targetBranch.asJson,
                "mergeCommit" -> i.mergeCommit.asJson,
                "integration" -> i.integration.asJson
              )
            )
            .asJson,
          "decisions" -> r.events.keys.toList.sorted.asJson
        )
      )
    Json.obj(
      "source" -> "workspace ledger requests".asJson,
      "requests" -> rows.asJson,
      "missing" -> (if rows.isEmpty then Some("no recorded merge or decision history for this workspace") else None).asJson
    )

  private def missingSection(source: String, reason: String): Json =
    Json.obj("source" -> source.asJson, "missing" -> reason.asJson)

end WorktreeContextTool
