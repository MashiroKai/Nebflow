package nebflow.core.delegate

import WorkspaceLedger.*
import scala.util.Try

/** Minimum repository evidence for reports; never performs a merge or cleanup. */
object MergerFacts:

  /** Pin only a clean, registered development seat that still needs integration. */
  def pinSource(workspace: Workspace): Either[String, MergeItem] = Try {
    val tree = os.Path(workspace.path)
    require(os.isDir(tree), "Worktree is missing; inspect its saved state before submitting")
    require(
      tree.toNIO.toRealPath() == tree.toNIO.toAbsolutePath.normalize(),
      "Worktree path changed through a symlink; inspect it before submitting"
    )
    val registered = os
      .proc("git", "--git-dir", workspace.repository, "worktree", "list", "--porcelain")
      .call(stdout = os.Pipe, stderr = os.Pipe)
      .out
      .text()
      .linesIterator
      .filter(_.startsWith("worktree "))
      .exists(line =>
        Try(java.nio.file.Path.of(line.stripPrefix("worktree ")).toRealPath()).toOption
          .contains(tree.toNIO.toRealPath())
      )
    require(registered, "Directory is not a registered worktree; inspect it before submitting")
    require(
      WorkspaceLedger.repositoryKey(tree) == workspace.repository,
      "Worktree repository changed; inspect it before submitting"
    )
    def work(args: String*) = os
      .proc(Seq("git", "-C", workspace.path) ++ args)
      .call(stdout = os.Pipe, stderr = os.Pipe)
      .out
      .text()
      .trim
    require(
      work("symbolic-ref", "--quiet", "--short", "HEAD") == workspace.branch,
      "Worktree branch changed; inspect it before submitting"
    )
    require(
      work("status", "--porcelain").isEmpty,
      "Worktree has uncommitted changes; commit or save them before requesting a merge"
    )
    def commit(branch: String) = os
      .proc("git", "--git-dir", workspace.repository, "rev-parse", "--verify", s"refs/heads/$branch^{commit}")
      .call(stdout = os.Pipe, stderr = os.Pipe)
      .out
      .text()
      .trim
    def ancestor(source: String, target: String) =
      val rc = os
        .proc("git", "--git-dir", workspace.repository, "merge-base", "--is-ancestor", source, target)
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        .exitCode
      require(rc <= 1, "Cannot verify source ancestry")
      rc == 0
    val source = commit(workspace.branch)
    val target = commit(workspace.baseBranch)
    require(
      ancestor(workspace.baseCommit, source) && ancestor(workspace.baseCommit, target),
      "Recorded baseline ancestry changed; inspect the source and target before submitting"
    )
    require(
      !ancestor(source, target),
      s"Workspace ${workspace.id} is already contained in ${workspace.baseBranch}; use workspace cleanup instead of requesting another merge"
    )
    MergeItem(workspace.id, source, workspace.baseBranch)
  }.toEither.left.map(e => Option(e.getMessage).getOrElse("Cannot pin workspace source"))

  def validate(workspace: Workspace, item: MergeItem, report: MergeReport): Either[String, Unit] =
    if report.phase == "awaiting-user" then return Right(())
    Try {
      def git(args: String*): String =
        os.proc(Seq("git", "--git-dir", workspace.repository) ++ args)
          .call(stdout = os.Pipe, stderr = os.Pipe)
          .out
          .text()
          .trim
      def ancestor(commit: String, target: String): Boolean =
        val rc = os
          .proc("git", "--git-dir", workspace.repository, "merge-base", "--is-ancestor", commit, target)
          .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
          .exitCode
        require(rc <= 1, "Cannot verify repository ancestry; inspect the actual state before reporting")
        rc == 0
      val target = s"refs/heads/${item.targetBranch}"
      git("rev-parse", "--verify", s"$target^{commit}")
      require(ancestor(workspace.baseCommit, target), "Recorded baseline ancestry changed; ask the user")
      if Set("checking", "merging").contains(report.phase) then
        val pinned = pinSource(workspace).fold(error => throw new IllegalStateException(error), identity)
        require(pinned.sourceCommit == item.sourceCommit, "Source branch changed after authorization; ask the user")
      if Set("validating", "merged-cleanup-pending", "completed").contains(report.phase) then
        val sourceAncestor = report.integration.orElse(item.integration).contains("source-ancestor")
        if sourceAncestor then
          // The source is already contained in the target without this flow's merge commit
          // (externally integrated, or recovered after a crash): cleanup-only closure.
          require(
            report.mergeCommit.isEmpty && item.mergeCommit.isEmpty,
            "An externally integrated source carries no merge commit"
          )
          require(ancestor(item.sourceCommit, target), "Source is not contained in the target; ask the user")
        else
          val merge = report.mergeCommit
            .orElse(item.mergeCommit)
            .getOrElse(throw new IllegalArgumentException("Actual merge commit is required"))
          require(merge.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Merge commit must be a full object id")
          val parents = git("show", "-s", "--format=%P", merge).split(" ").toList
          require(
            parents.size == 2 && parents(1) == item.sourceCommit,
            "Merge must retain both parents and the authorized source"
          )
          require(
            ancestor(merge, target) && ancestor(item.sourceCommit, merge),
            "Reported merge is not contained in the target"
          )
        end if
        if report.phase == "completed" then
          val registered = git("worktree", "list", "--porcelain").linesIterator
            .filter(_.startsWith("worktree "))
            .map(_.stripPrefix("worktree "))
            .exists(p => java.nio.file.Path.of(p).toAbsolutePath.normalize().toString == workspace.path)
          require(
            !registered && !java.nio.file.Files.exists(java.nio.file.Path.of(workspace.path)),
            "Worktree cleanup is incomplete"
          )
          val evidence = report.verification
            .orElse(item.verification)
            .getOrElse(throw new IllegalArgumentException("Verification record is required"))
          val evidencePath = java.nio.file.Path.of(evidence).toRealPath()
          val evidenceRoots = git("worktree", "list", "--porcelain").linesIterator
            .filter(_.startsWith("worktree "))
            .map(p =>
              java.nio.file.Path
                .of(p.stripPrefix("worktree "))
                .resolve(".nebflow/evidence")
                .toAbsolutePath
                .normalize()
            )
            .toList
          require(
            evidenceRoots.exists(root => evidencePath.startsWith(root)),
            "Verification must be stored under repository .nebflow/evidence"
          )
          val proof = io.circe.parser.parse(os.read(os.Path(evidencePath))).fold(throw _, identity).hcursor
          require(
            proof.get[String]("status").toOption.contains("passed") &&
              proof.get[String]("sourceCommit").toOption.contains(item.sourceCommit),
            "Verification record does not match this merge"
          )
          if sourceAncestor then
            require(
              proof.get[String]("integration").toOption.contains("source-ancestor") &&
                proof.get[String]("mergeCommit").toOption.isEmpty,
              "Verification record must declare the source-ancestor integration without a merge commit"
            )
          else
            require(
              proof
                .get[String]("mergeCommit")
                .toOption
                .contains(
                  report.mergeCommit.orElse(item.mergeCommit).getOrElse("")
                ),
              "Verification record does not match this merge"
            )
          end if
          require(
            proof.get[String]("command").toOption.exists(_.trim.nonEmpty),
            "Verification record must carry the command that ran"
          )
          require(
            proof.get[Int]("exitCode").toOption.contains(0),
            "Verification record must carry a zero exit code"
          )
          require(
            proof.get[String]("ranAt").toOption.exists(_.trim.nonEmpty),
            "Verification record must carry the run timestamp"
          )
        end if
      end if
      if report.phase == "cancelled" || report.phase == "failed" then
        require(
          item.mergeCommit.isEmpty && !ancestor(item.sourceCommit, target),
          "Source is already integrated; report the merge and cleanup outcome instead of undoing it"
        )
      ()
    }.toEither.left.map(e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
  end validate
end MergerFacts
