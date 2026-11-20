package nebflow.core.delegate

import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * Settlement diff-face spec (contract: the diff is an ANNOTATION face, not the truth
 * face — ownership truth = the closure traversal; cross-round commits dedupe against
 * the round ledger's countedOids; cross-repo / non-git / beyond-baseline writes are
 * first-class partial annotations).
 *
 *  - W-1 cross-round OID dedup: a child merge counted by a settled round is excluded
 *    from a new round's rollup (detail rows keep it for audit).
 *  - W-2 partialReasons: cross-repo closure, non-git seat, and writes beyond the
 *    current round's baseline each land a mechanical annotation.
 */
class DelegateWorkReportRoundSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "workreport-round")
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def git(ws: os.Path, args: String*): Unit =
    val r = os.proc("git", "-C", ws.toString, args).call(check = false, mergeErrIntoOut = true)
    assert(r.exitCode == 0, s"git ${args.mkString(" ")} failed: ${r.out.text().take(300)}")

  /** A repo whose delegate branch carries one merge commit back into main. */
  private def mergedChildRepo(id: String): (os.Path, String) =
    val ws = tmp / s"repo-$id"
    os.makeDir.all(ws)
    git(ws, "init", "-q", "-b", "main")
    git(ws, "config", "user.email", "t@t")
    git(ws, "config", "user.name", "t")
    os.write.over(ws / "f1.txt", "line1\n")
    git(ws, "add", ".")
    git(ws, "commit", "-qm", "base")
    git(ws, "checkout", "-qb", s"delegate/$id")
    os.write.over(ws / "f1.txt", "line1\nline2\n")
    git(ws, "add", ".")
    git(ws, "commit", "-qm", "work")
    git(ws, "checkout", "-q", "main")
    git(ws, "merge", "--no-ff", "-qm", s"merge $id", s"delegate/$id")
    val mergeSha = os.proc("git", "-C", ws.toString, "rev-parse", "HEAD").call().out.trim()
    (ws, mergeSha)

  end mergedChildRepo

  private def putChild(id: String, parent: String, cwd: os.Path, baseBranch: String, mergeSha: Option[String]): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = cwd.toString,
        project = None,
        taskId = None,
        parentSessionId = parent,
        title = s"child $id",
        createdAt = System.currentTimeMillis() - 30_000L,
        branch = Some(s"delegate/$id"),
        baseBranch = Some(baseBranch),
        reportStatus = Some("finish"),
        reportDetail = mergeSha.map(sha => s"merged ($sha)")
      )
    )

  private def putRoot(id: String, cwd: os.Path, baseBranch: String): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = cwd.toString,
        project = None,
        taskId = None,
        parentSessionId = "parent-workreport",
        title = "root",
        createdAt = System.currentTimeMillis() - 60_000L,
        baseBranch = Some(baseBranch)
      )
    )

  test("W-1 cross-round OID dedup: a settled round's counted merge drops out of the new round's rollup") {
    val (repo, mergeSha) = mergedChildRepo("w1")
    val rootId = "delegate-nebflow-w1root"
    val childId = "delegate-nebflow-w1child"
    putRoot(rootId, repo, "main")
    putChild(childId, rootId, repo, "main", Some(mergeSha))

    val fresh = DelegateWorkReport.aggregateDiffStat(DelegateRegistry.find(rootId).get, excludeOids = Set.empty)
    assert(fresh.added > 0, s"the child merge counts in a fresh settlement: added=${fresh.added}")
    assert(fresh.countedOids.contains(mergeSha), "the merge OID rides countedOids for the round ledger")

    // round 1 settles having counted that merge; round 2 must not count it again
    DelegateRoundLedger.openRound(rootId, 1, "gen-w1-r1", DelegateRoundLedger.CauseSpawn, None, None)
    DelegateRoundLedger.settleRound(rootId, 1, "gen-w1-r1", "completed", List(mergeSha), None)
    val deduped = DelegateWorkReport.aggregateDiffStat(
      DelegateRegistry.find(rootId).get,
      excludeOids = DelegateRoundLedger.countedOids(rootId)
    )
    assertEquals(deduped.added, 0L, "the already-counted merge is excluded from the rollup")
    assertEquals(deduped.removed, 0L)
    assert(!deduped.countedOids.contains(mergeSha), "countedOids only carries NEW commits")
    // detail rows keep the child visible for audit
    assert(deduped.children.exists(_.delegateId == childId))
  }

  test("W-2 partialReasons: cross-repo closure, non-git seat, and beyond-baseline writes annotate mechanically") {
    val (repoA, mergeA) = mergedChildRepo("w2a")
    val repoB = tmp / "repo-w2b"
    os.makeDir.all(repoB)
    git(repoB, "init", "-q", "-b", "main")
    git(repoB, "config", "user.email", "t@t")
    git(repoB, "config", "user.name", "t")
    os.write.over(repoB / "g1.txt", "b\n")
    git(repoB, "add", ".")
    git(repoB, "commit", "-qm", "base")
    val nonGit = tmp / "plain-seat"
    os.makeDir.all(nonGit)

    val rootId = "delegate-nebflow-w2root"
    putRoot(rootId, repoA, "main")
    putChild("delegate-nebflow-w2a", rootId, repoA, "main", Some(mergeA))
    putChild("delegate-nebflow-w2b", rootId, repoB, "main", None)
    putChild("delegate-nebflow-w2c", rootId, nonGit, "main", None)

    // round 1 open with an EMPTY dirty baseline, then write beyond it on the root seat
    DelegateRoundLedger.openRound(rootId, 1, "gen-w2-r1", DelegateRoundLedger.CauseSpawn, None, Some(Nil))
    os.write.over(repoA / "late.txt", "appeared after the baseline\n")

    val agg = DelegateWorkReport.aggregateDiffStat(DelegateRegistry.find(rootId).get)
    val reasons = agg.partialReasons
    assert(reasons.exists(_.startsWith("cross-repo closure")), s"cross-repo annotation required: $reasons")
    assert(reasons.exists(_.startsWith("non-git seat")), s"non-git annotation required: $reasons")
    assert(
      reasons.exists(r => r.startsWith("writes beyond the round baseline") && r.contains("late.txt")),
      s"beyond-baseline annotation with the path list required: $reasons"
    )
    assert(agg.partial, "the aggregate carries the partial flag")
  }

end DelegateWorkReportRoundSpec
