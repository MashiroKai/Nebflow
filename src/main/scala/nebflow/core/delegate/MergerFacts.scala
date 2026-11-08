package nebflow.core.delegate

import WorkspaceLedger.*
import scala.util.Try

/** Minimum repository evidence for reports; never performs a merge or cleanup. */
object MergerFacts:
  def validate(workspace: Workspace, item: MergeItem, report: MergeReport): Either[String, Unit] =
    if report.phase == "awaiting-user" then return Right(())
    Try {
      def git(args: String*): String =
        os.proc(Seq("git", "--git-dir", workspace.repository) ++ args)
          .call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
      def ancestor(commit: String, target: String): Boolean =
        os.proc("git", "--git-dir", workspace.repository, "merge-base", "--is-ancestor", commit, target)
          .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
      val target = s"refs/heads/${item.targetBranch}"
      git("rev-parse", "--verify", s"$target^{commit}")
      require(ancestor(workspace.baseCommit, target), "Recorded baseline ancestry changed; ask the user")
      if Set("checking", "merging").contains(report.phase) then
        require(git("rev-parse", "--verify", s"refs/heads/${workspace.branch}^{commit}") == item.sourceCommit,
          "Source branch changed after authorization; ask the user")
      if Set("validating", "merged-cleanup-pending", "completed").contains(report.phase) then
        val merge = report.mergeCommit.orElse(item.mergeCommit).getOrElse(
          throw new IllegalArgumentException("Actual merge commit is required"))
        require(merge.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Merge commit must be a full object id")
        val parents = git("show", "-s", "--format=%P", merge).split(" ").toList
        require(parents.size == 2 && parents(1) == item.sourceCommit, "Merge must retain both parents and the authorized source")
        require(ancestor(merge, target) && ancestor(item.sourceCommit, merge), "Reported merge is not contained in the target")
        if report.phase == "completed" then
          val registered = git("worktree", "list", "--porcelain").linesIterator
            .filter(_.startsWith("worktree ")).map(_.stripPrefix("worktree "))
            .exists(p => java.nio.file.Path.of(p).toAbsolutePath.normalize().toString == workspace.path)
          require(!registered && !java.nio.file.Files.exists(java.nio.file.Path.of(workspace.path)), "Worktree cleanup is incomplete")
          val evidence = report.verification.orElse(item.verification).getOrElse(
            throw new IllegalArgumentException("Verification record is required"))
          val evidencePath = java.nio.file.Path.of(evidence).toRealPath()
          val evidenceRoots = git("worktree", "list", "--porcelain").linesIterator
            .filter(_.startsWith("worktree ")).map(p => java.nio.file.Path.of(p.stripPrefix("worktree "))
              .resolve(".nebflow/evidence").toAbsolutePath.normalize()).toList
          require(evidenceRoots.exists(root => evidencePath.startsWith(root)), "Verification must be stored under repository .nebflow/evidence")
          val proof = io.circe.parser.parse(os.read(os.Path(evidencePath))).fold(throw _, identity).hcursor
          require(proof.get[String]("status").toOption.contains("passed") &&
            proof.get[String]("sourceCommit").toOption.contains(item.sourceCommit) &&
            proof.get[String]("mergeCommit").toOption.contains(merge), "Verification record does not match this merge")
      if report.phase == "cancelled" || report.phase == "failed" then
        require(item.mergeCommit.isEmpty && !ancestor(item.sourceCommit, target),
          "Source is already integrated; report the merge and cleanup outcome instead of undoing it")
      ()
    }.toEither.left.map(e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
