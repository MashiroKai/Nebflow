package nebflow.core.delegate

import munit.FunSuite
import nebflow.shared.PathUtil

class LegacyMergeQueueRetirementSpec extends FunSuite:
  test("legacy enqueue, care and self-tick never grant a development owner a merge turn") {
    val previous = PathUtil.dataRoot
    val root = os.temp.dir(prefix = "retired-merge-queue-")
    val sent = scala.collection.mutable.ListBuffer.empty[MergeQueueTestPorts.Sent]
    try
      PathUtil.setDataRoot(root / "data")
      MergeQueue.reset()
      MergeQueueTestPorts.recording(value => { sent += value; () })
      val repo = root / "repo"
      os.makeDir.all(repo)
      def git(args: String*) = os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe)
      git("init", "-b", "main"); git("config", "user.name", "test"); git("config", "user.email", "test@example.invalid")
      git("commit", "--allow-empty", "-m", "base"); git("checkout", "-b", "delegate/old")
      os.write(repo / "work.txt", "unmerged work")
      git("add", "work.txt"); git("commit", "-m", "work"); git("checkout", "main")
      val member = MergeQueue.Member("delegate-legacy-owner", "delegate/old", repo.toString, "main", false,
        "project", None, "parent", "old merge request", 1L, "identity",
        declaredAt = Some(1L), declareSource = Some(MergeDeclaration.Source.ReportMerge))
      MergeQueue.enqueue(member)
      MergeQueue.pump("project")
      MergeQueue.careTick()
      MergeQueue.onTerminal(member.delegateId)
      MergeQueue.closeMerged(member.delegateId, "retired-terminal", cleanup = true)
      MergeQueue.enableSelfTick(1L)
      assertEquals(sent.toList, Nil)
      assert(!MergeQueue.isMergeRound(member.delegateId))
      assertEquals(MergeQueue.find(member.delegateId).map(_.state), Some(MergeQueue.State.Waiting))
    finally
      MergeQueue.reset()
      MergeQueue.OwnerDeliveryPort.clear()
      PathUtil.setDataRoot(previous)
      os.remove.all(root)
  }
