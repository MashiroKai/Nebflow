package nebflow.core.delegate

import WorkspaceLedger.*
import io.circe.Json
import io.circe.syntax.*

import scala.util.Try

/**
 * Read-only workspace facts for the project panel and the [[nebflow.core.tools
 * .WorktreeContextTool]]: Git ahead/dirty counts, the recorded verification
 * evidence and the read-only reconcile reading of non-terminal requests.
 *
 * Every reading is fail-soft per workspace: one broken workspace degrades to an
 * explicit error marker inside its own fact row while the rest of the endpoint
 * payload stays intact. Nothing here writes the ledger or Git, and no reading
 * is ever fabricated — an unavailable number is `null` with the reason attached.
 */
object WorkspaceFacts:

  /** Upper bound for the dirty summary; worktree seats are small, larger outputs truncate. */
  val DirtyLineCap: Int = 400

  final case class GitReading(
    workspaceId: String,
    aheadCount: Option[Int],
    dirtyTracked: Option[Int],
    dirtyUntracked: Option[Int],
    headCommit: Option[String],
    error: Option[String]
  )

  /** Bounded Git read of one workspace: commits ahead of the recorded base, dirty summary, HEAD. */
  def gitReading(workspace: Workspace): GitReading =
    Try {
      def git(args: String*): String =
        os.proc(Seq("git", "--git-dir", workspace.repository) ++ args)
          .call(stdout = os.Pipe, stderr = os.Pipe)
          .out
          .text()
          .trim
      val directory = java.nio.file.Files.exists(java.nio.file.Path.of(workspace.path))
      val ahead =
        if git("rev-parse", "--verify", s"refs/heads/${workspace.baseBranch}^{commit}").isEmpty then
          throw new IllegalStateException(s"Base branch ${workspace.baseBranch} is unreadable")
        else git("rev-list", "--count", s"refs/heads/${workspace.baseBranch}..refs/heads/${workspace.branch}").toIntOption
      val status =
        if !directory then throw new IllegalStateException("Workspace directory is missing")
        else
          os.proc("git", "-C", workspace.path, "status", "--porcelain")
            .call(stdout = os.Pipe, stderr = os.Pipe)
            .out
            .text()
            .linesIterator
            .take(DirtyLineCap)
            .toList
      val head = git("rev-parse", "--verify", s"refs/heads/${workspace.branch}^{commit}")
      GitReading(
        workspace.id,
        aheadCount = ahead,
        dirtyTracked = Some(status.count(!_.startsWith("??"))),
        dirtyUntracked = Some(status.count(_.startsWith("??"))),
        headCommit = if head.isEmpty then None else Some(head),
        error = None
      )
    }.getOrElse {
      GitReading(workspace.id, None, None, None, None, Some("Git facts are unavailable for this workspace"))
    }

  /** The verification evidence recorded for one workspace across all requests, newest request first. */
  def verificationReadings(workspaceId: String, snapshot: State): List[Json] =
    snapshot.requests
      .filter(_.items.exists(_.workspaceId == workspaceId))
      .reverse
      .flatMap { request =>
        request.items.filter(_.workspaceId == workspaceId).map { item =>
          val proof = item.verification.flatMap(readVerification)
          Json.obj(
            "requestId" -> request.id.asJson,
            "phase" -> item.phase.asJson,
            "mergeCommit" -> item.mergeCommit.asJson,
            "integration" -> item.integration.asJson,
            "verification" -> proof.asJson,
            "verificationMissing" -> item.verification
              .map(path => s"verification record unreadable: $path")
              .getOrElse("no verification record")
              .asJson
          )
        }
      }

  /** The latest verification evidence JSON for one workspace, if any request carries one. */
  def lastVerification(workspaceId: String, snapshot: State): Option[Json] =
    verificationReadings(workspaceId, snapshot).flatMap(row =>
      row.hcursor.downField("verification").focus.filter(_ != Json.Null)
    ).headOption

  private def readVerification(path: String): Option[Json] = Try {
    val parsed = io.circe.parser.parse(os.read(os.Path(path))).toOption
    parsed.map(full =>
      Json.obj(
        "status" -> full.hcursor.get[String]("status").toOption.asJson,
        "sourceCommit" -> full.hcursor.get[String]("sourceCommit").toOption.asJson,
        "mergeCommit" -> full.hcursor.get[Option[String]]("mergeCommit").toOption.flatten.asJson,
        "command" -> full.hcursor.get[String]("command").toOption.asJson,
        "exitCode" -> full.hcursor.get[Option[Int]]("exitCode").toOption.flatten.asJson,
        "ranAt" -> full.hcursor.get[String]("ranAt").toOption.asJson,
        "path" -> path.asJson
      )
    )
  }.toOption.flatten

  /** Per-workspace reconcile readings for every non-terminal request of one repository. */
  def reconcileByWorkspace(repository: String, snapshot: State): Map[String, List[Json]] =
    snapshot.requests
      .filter(r => r.repository == repository && r.status != "completed" && r.status != "cancelled")
      .flatMap(request =>
        RequestReconcile.request(request, snapshot.workspaces).items.map(reading =>
          reading.workspaceId ->
            Json.obj(
              "requestId" -> request.id.asJson,
              "phase" -> reading.phase.asJson,
              "reading" -> reading.reading.asJson,
              "integration" -> reading.integration.asJson,
              "detail" -> reading.detail.asJson
            )
        )
      )
      .groupMap(_._1)(_._2)

  /** Facts row for one workspace; every leg degrades to an explicit marker, never throws. */
  def forWorkspace(workspace: Workspace, snapshot: State): Json =
    val git = Try(gitReading(workspace)).getOrElse {
      GitReading(workspace.id, None, None, None, None, Some("Git facts are unavailable for this workspace"))
    }
    val reconcile = Try(reconcileByWorkspace(workspace.repository, snapshot)).getOrElse(Map.empty)
    Json.obj(
      "workspaceId" -> workspace.id.asJson,
      "aheadCount" -> git.aheadCount.asJson,
      "dirtyTracked" -> git.dirtyTracked.asJson,
      "dirtyUntracked" -> git.dirtyUntracked.asJson,
      "headCommit" -> git.headCommit.asJson,
      "error" -> git.error.asJson,
      "lastVerification" -> Try(lastVerification(workspace.id, snapshot)).getOrElse(None).asJson,
      "reconcile" -> Try(reconcile.getOrElse(workspace.id, Nil)).getOrElse(Nil).asJson
    )

  /** Facts for every workspace of one repository, keyed by workspace id. */
  def forRepository(repository: String, snapshot: State): Json =
    snapshot.workspaces
      .filter(_.repository == repository)
      .map(w => w.id -> forWorkspace(w, snapshot))
      .toMap
      .asJson

end WorkspaceFacts
