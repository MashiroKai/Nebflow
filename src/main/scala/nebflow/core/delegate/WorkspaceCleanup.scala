package nebflow.core.delegate

import io.circe.generic.semiauto.*
import WorkspaceLedger.*
import scala.util.Try

final class WorkspaceCleanup(ledger: WorkspaceLedger):
  import WorkspaceCleanup.*

  def inspect(
    id: String,
    busyOwners: Set[String] = Set.empty,
    cleanupClaim: Option[String] = None
  ): Either[String, Preview] = Try {
    val state = ledger.snapshot
    val w = state.workspaces.find(_.id == id).getOrElse(throw new IllegalArgumentException("Unknown workspace"))
    require(
      (Set("open", "merged-cleanup-pending").contains(w.lifecycle) ||
        (w.lifecycle == "cleaning" && state.cleanups
          .get(id)
          .exists(c => cleanupClaim.contains(c.token)))) && w.activity == "idle",
      "Workspace is not idle or is reserved"
    )
    require(!busyOwners(w.owner), "Development owner is still executing")
    state.requests
      .find(r => w.reservation.contains(r.id))
      .foreach(r => require(!busyOwners(r.mergerId), "Merger is still executing; request a safe stop first"))
    require(w.branch != w.baseBranch, "The baseline checkout cannot be cleaned as a task workspace")
    def git(args: String*) =
      os.proc(Seq("git", "--git-dir", w.repository) ++ args).call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    val rows = git("worktree", "list", "--porcelain").split("\n\n").toList
    def canonical(p: String) =
      val path = java.nio.file.Path.of(p).toAbsolutePath.normalize()
      Try(path.toRealPath()).getOrElse(path)
    val registered = rows.find(
      _.linesIterator.exists(line =>
        line.startsWith("worktree ") && canonical(line.stripPrefix("worktree ")) == canonical(w.path)
      )
    )
    val recordedPath = java.nio.file.Path.of(w.path).toAbsolutePath.normalize()
    require(!java.nio.file.Files.isSymbolicLink(recordedPath), "Worktree path became a symlink; inspect it manually")
    val exists = java.nio.file.Files.exists(recordedPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    if exists then
      require(recordedPath.toRealPath() == recordedPath, "Worktree path changed through an external symlink")
    require(registered.nonEmpty || !exists, "Directory is not a registered worktree; inspect it manually")
    registered.foreach(row =>
      require(row.linesIterator.contains(s"branch refs/heads/${w.branch}"), "Worktree branch changed; inspect again")
    )
    val source = git("rev-parse", "--verify", s"refs/heads/${w.branch}^{commit}")
    val target = git("rev-parse", "--verify", s"refs/heads/${w.baseBranch}^{commit}")
    val ancestry = os
      .proc("git", "--git-dir", w.repository, "merge-base", "--is-ancestor", source, target)
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
      .exitCode
    require(ancestry <= 1, "Cannot verify integration status")
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    def feed(text: String): Unit = digest.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    feed(s"$id\n${w.path}\n$source\n$target\n${registered.getOrElse("")}")
    val changes =
      if !exists then Nil
      else
        require(
          WorkspaceLedger.repositoryKey(os.Path(w.path)) == java.nio.file.Path.of(w.repository).toRealPath().toString,
          "Worktree repository changed"
        )
        def work(args: String*) =
          os.proc(Seq("git", "-C", w.path) ++ args).call(stdout = os.Pipe, stderr = os.Pipe).out.text()
        val status = work("status", "--porcelain")
        feed(status); feed(work("diff", "--binary", "HEAD")); feed(work("diff", "--cached", "--binary"))
        val untracked = work("ls-files", "--others", "-z").split("\u0000").filter(_.nonEmpty).sorted
        untracked.foreach { relative =>
          feed(relative)
          val path = os.Path(w.path) / os.RelPath(relative)
          if java.nio.file.Files.isSymbolicLink(path.toNIO) then
            feed(java.nio.file.Files.readSymbolicLink(path.toNIO).toString)
          else
            require(
              java.nio.file.Files.isRegularFile(path.toNIO, java.nio.file.LinkOption.NOFOLLOW_LINKS),
              "Untracked path is not a regular file; inspect it manually"
            )
            val stream = java.nio.file.Files.newInputStream(path.toNIO, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            try
              val buffer = new Array[Byte](65536)
              var count = stream.read(buffer)
              while count >= 0 do
                if count > 0 then digest.update(buffer, 0, count)
                count = stream.read(buffer)
            finally stream.close()
          end if
        }
        (status.linesIterator.filter(_.nonEmpty).toList ++ untracked.map(relative => s"?? $relative")).distinct
    val fingerprint = digest.digest().map(b => f"${b & 0xff}%02x").mkString
    Preview(
      w.id,
      w.path,
      w.branch,
      w.baseBranch,
      source,
      target,
      ancestry == 1,
      changes,
      s"${w.revision}:$fingerprint",
      w.revision,
      fingerprint,
      exists,
      registered.nonEmpty
    )
  }.toEither.left.map(e => Option(e.getMessage).getOrElse("Workspace inspection failed"))

  def removalComplete(w: Workspace): Boolean = Try {
    !java.nio.file.Files.exists(java.nio.file.Path.of(w.path), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
    !os
      .proc("git", "--git-dir", w.repository, "worktree", "list", "--porcelain")
      .call(stdout = os.Pipe, stderr = os.Pipe)
      .out
      .text()
      .linesIterator
      .exists(line =>
        line.startsWith("worktree ") &&
          java.nio.file.Path.of(line.stripPrefix("worktree ")).toAbsolutePath.normalize().toString == w.path
      )
  }.getOrElse(false)

  /** Explicit retry reconciles an interrupted claim; it never resumes deletion. */
  def reconcile(id: String, busyOwners: Set[String] = Set.empty): Either[String, Workspace] =
    val state = ledger.snapshot
    state.workspaces.find(_.id == id).toRight("Unknown workspace").flatMap { w =>
      if w.lifecycle != "cleaning" then Right(w)
      else if busyOwners(w.owner) then Left("Workspace owner is still executing")
      else
        RepositoryWriteLock
          .withLock(w.repository) {
            state.cleanups.get(id).toRight("Missing cleanup claim; inspect manually").flatMap { claim =>
              inspect(id, busyOwners, Some(claim.token)).flatMap { preview =>
                val error = Option.when(preview.directoryExists || preview.registered)(
                  "Previous cleanup was interrupted; inspect and confirm a fresh cleanup"
                )
                ledger.finishCleanup(id, claim.token, integrated = !preview.unmerged, error)
              }
            }
          }
          .flatMap(identity)
    }

  end reconcile

  def remove(
    id: String,
    previewToken: String,
    confirmUnmerged: Boolean,
    discardUncommitted: Boolean,
    busyOwners: Set[String] = Set.empty
  ): Either[String, Workspace] =
    ledger.snapshot.workspaces.find(_.id == id).toRight("Unknown workspace").flatMap { before =>
      if before.lifecycle.startsWith("closed-") then
        val claim = ledger.snapshot.cleanups.get(id)
        if claim.exists(c => c.token == previewToken && c.removed) && removalComplete(before) then Right(before)
        else Left("Workspace is closed; inspect externally recreated paths manually")
      else
        RepositoryWriteLock
          .withLock(before.repository) {
            inspect(id, busyOwners).flatMap { preview =>
              if preview.previewToken != previewToken then
                Left("Workspace changed since confirmation; inspect it again")
              else if preview.unmerged && !confirmUnmerged then
                Left("Confirm removal of an unmerged workspace; its branch will be retained")
              else if preview.changes.nonEmpty && !discardUncommitted then
                Left("Explicit confirmation is required to discard uncommitted changes")
              else
                ledger.claimCleanup(id, preview.revision, previewToken).flatMap { claimed =>
                  val removed = Try {
                    val finalInspection = inspect(id, busyOwners, Some(previewToken)).fold(
                      error => throw new IllegalStateException(error),
                      identity
                    )
                    require(
                      finalInspection.fingerprint == preview.fingerprint,
                      "Workspace changed immediately before removal; inspect again"
                    )
                    // Branches are always retained, including all unmerged commits.
                    if preview.registered then
                      val force = if discardUncommitted then Seq("--force") else Nil
                      os.proc(
                        Seq("git", "--git-dir", claimed.repository, "worktree", "remove") ++ force ++ Seq(claimed.path)
                      ).call(stdout = os.Pipe, stderr = os.Pipe)
                    require(
                      !java.nio.file.Files.exists(java.nio.file.Path.of(claimed.path)),
                      "Worktree directory still exists"
                    )
                    val registered = os
                      .proc("git", "--git-dir", claimed.repository, "worktree", "list", "--porcelain")
                      .call(stdout = os.Pipe, stderr = os.Pipe)
                      .out
                      .text()
                    require(
                      !registered.linesIterator.contains(s"worktree ${claimed.path}"),
                      "Worktree registration still exists"
                    )
                  }.toEither.left.map(e => Option(e.getMessage).getOrElse("Cleanup failed"))
                  ledger
                    .finishCleanup(id, previewToken, integrated = !preview.unmerged, removed.left.toOption)
                    .flatMap(next => removed.map(_ => next))
                }
            }
          }
          .flatMap(identity)
    }

end WorkspaceCleanup

object WorkspaceCleanup:

  final case class Preview(
    workspaceId: String,
    path: String,
    branch: String,
    baseBranch: String,
    sourceCommit: String,
    targetCommit: String,
    unmerged: Boolean,
    changes: List[String],
    previewToken: String,
    revision: Long,
    fingerprint: String,
    directoryExists: Boolean,
    registered: Boolean
  )
  given io.circe.Encoder[Preview] = deriveEncoder
end WorkspaceCleanup
