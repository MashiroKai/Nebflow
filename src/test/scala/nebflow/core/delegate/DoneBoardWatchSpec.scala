package nebflow.core.delegate

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite

/**
 * DoneBoardWatch spec — done-board mechanization (SCM blueprint P0 item 3).
 *
 * Coverage: aged clean tree reports to the dispatching root (report-only);
 * fresh/dirty/live/unreadable candidates never report; a merged branch whose
 * worktree is gone reports only after 24h of observed lingering; the notify
 * window throttles repeats; the sweep throttle skips reads; state entries
 * prune once the condition clears; one root with several leftovers gets one
 * aggregated notice.
 */
class DoneBoardWatchSpec extends CatsEffectSuite:

  private val Now = 1_000_000_000_000L
  private val H = 3600_000L

  private def rec(
    id: String,
    branch: String = "delegate/x",
    base: String = "main",
    worktree: Option[String] = Some("/tmp/wt-x"),
    ws: String = "/tmp/ws",
    createdAt: Long = Now,
    title: String = "task x"
  ): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = ws,
      project = None,
      taskId = None,
      parentSessionId = "root-x",
      title = title,
      createdAt = createdAt,
      branch = Some(branch),
      baseBranch = Some(base),
      projectWorkspace = Some(ws),
      worktree = worktree
    )

  private def clean(lastActiveMs: Long) = DoneBoardWatch.TreeRead(0, 0, lastActiveMs)

  private final case class FakePort(
    recs: List[DelegateRegistry.Record],
    live: Set[String] = Set.empty,
    trees: Map[String, DoneBoardWatch.TreeRead] = Map.empty,
    missingTrees: Set[String] = Set.empty,
    unreadable: Set[String] = Set.empty,
    merged: Map[String, Boolean] = Map.empty,
    state: Ref[IO, DoneBoardWatch.State],
    sent: Ref[IO, List[(String, String)]],
    clock: Ref[IO, Long],
    reads: Ref[IO, Int]
  ) extends DoneBoardWatch.Port:
    def delegates = reads.update(_ + 1).as(recs)
    def liveSessionIds = IO.pure(live)
    def worktreePresent(p: String) = !missingTrees.contains(p)

    def treeRead(p: String, base: String, branch: String) =
      if unreadable.contains(p) then None else trees.get(p)

    def branchMerged(ws: String, baseBranch: String, branch: String) =
      Some(merged.getOrElse(branch, false))
    def notifyRoot(sessionId: String, text: String) = sent.update(_ :+ (sessionId, text)).as(true)
    override def nowMs = clock.get
    override def loadState = state.get
    override def saveState(s: DoneBoardWatch.State) = state.set(s)
  end FakePort

  private def rig(init: DoneBoardWatch.State = DoneBoardWatch.State.empty) =
    for
      st <- Ref.of[IO, DoneBoardWatch.State](init)
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](Now)
      reads <- Ref.of[IO, Int](0)
    yield (st, sent, clock, reads)

  test("aged clean done tree is reported to the dispatching root, report-only") {
    for
      (st, sent, clock, reads) <- rig()
      port = FakePort(
        recs = List(rec("d1", worktree = Some("/tmp/wt-d1"), createdAt = Now - 30 * H)),
        trees = Map("/tmp/wt-d1" -> clean(Now - 30 * H)),
        state = st,
        sent = sent,
        clock = clock,
        reads = reads
      )
      out <- DoneBoardWatch.scan(port)
      msgs <- sent.get
      s <- st.get
    yield
      assertEquals(out, List("root-x: 1 lingering finished item(s)"))
      assertEquals(msgs.map(_._1), List("root-x"))
      assert(msgs.head._2.contains("[done-board]"))
      assert(msgs.head._2.contains("delegate/x"))
      assert(msgs.head._2.contains("wt-d1"))
      assert(msgs.head._2.contains("Report only"))
      assertEquals(s.entries.get("tree:d1").map(_.lastNotifyMs), Some(Now))
  }

  test("fresh or dirty trees are never reported") {
    for
      (st1, sent1, clock1, reads1) <- rig()
      p1 = FakePort(
        recs = List(rec("d1", worktree = Some("/tmp/wt-fresh"), createdAt = Now)),
        trees = Map("/tmp/wt-fresh" -> clean(Now - 2 * H)),
        state = st1,
        sent = sent1,
        clock = clock1,
        reads = reads1
      )
      _ <- DoneBoardWatch.scan(p1)
      freshSent <- sent1.get
      freshState <- st1.get
      (st2, sent2, clock2, reads2) <- rig()
      p2 = FakePort(
        recs = List(rec("d1", worktree = Some("/tmp/wt-dirty"), createdAt = Now - 30 * H)),
        trees = Map("/tmp/wt-dirty" -> DoneBoardWatch.TreeRead(3, 0, Now - 30 * H)),
        state = st2,
        sent = sent2,
        clock = clock2,
        reads = reads2
      )
      _ <- DoneBoardWatch.scan(p2)
      dirtySent <- sent2.get
      dirtyState <- st2.get
    yield
      assertEquals(freshSent, Nil)
      assertEquals(freshState.entries, Map.empty)
      assertEquals(dirtySent, Nil)
      assertEquals(dirtyState.entries, Map.empty)
  }

  test("live delegate and unreadable tree readout are skipped") {
    for
      (st, sent, clock, reads) <- rig()
      port = FakePort(
        recs = List(rec("d1", worktree = Some("/tmp/wt-live"), createdAt = Now - 30 * H)),
        live = Set("d1"),
        trees = Map("/tmp/wt-live" -> clean(Now - 30 * H)),
        state = st,
        sent = sent,
        clock = clock,
        reads = reads
      )
      _ <- DoneBoardWatch.scan(port)
      liveSent <- sent.get
      liveState <- st.get
      (st2, sent2, clock2, reads2) <- rig()
      port2 = FakePort(
        recs = List(rec("d2", worktree = Some("/tmp/wt-unreadable"), createdAt = Now - 30 * H)),
        unreadable = Set("/tmp/wt-unreadable"),
        state = st2,
        sent = sent2,
        clock = clock2,
        reads = reads2
      )
      _ <- DoneBoardWatch.scan(port2)
      unreadableSent <- sent2.get
      unreadableState <- st2.get
    yield
      assertEquals(liveSent, Nil)
      assertEquals(liveState.entries, Map.empty)
      assertEquals(unreadableSent, Nil)
      assertEquals(unreadableState.entries, Map.empty)
  }

  test("merged branch with removed tree: first observation records only, aged observation reports") {
    for
      (st, sent, clock, reads) <- rig()
      port = FakePort(
        recs = List(rec("d1", worktree = Some("/tmp/gone-wt"))),
        missingTrees = Set("/tmp/gone-wt"),
        merged = Map("delegate/x" -> true),
        state = st,
        sent = sent,
        clock = clock,
        reads = reads
      )
      _ <- DoneBoardWatch.scan(port)
      firstSent <- sent.get
      s1 <- st.get
      _ <- clock.set(Now + 25 * H)
      _ <- DoneBoardWatch.scan(port)
      agedSent <- sent.get
      s2 <- st.get
    yield
      assertEquals(firstSent, Nil)
      assertEquals(s1.entries.get("branch:d1").map(_.firstSeenMs), Some(Now))
      assertEquals(agedSent.map(_._1), List("root-x"))
      assert(agedSent.head._2.contains("fully merged into main"))
      assertEquals(s2.entries.get("branch:d1").map(_.lastNotifyMs), Some(Now + 25 * H))
  }

  test("unmerged branch with removed tree is never reported and prunes stale entries") {
    val prior = DoneBoardWatch.State(
      DoneBoardWatch.StateVersion,
      Now - 30 * H,
      Map(
        "branch:d1" -> DoneBoardWatch.Entry(
          "branch",
          "d1",
          "delegate/x",
          "main",
          "t",
          "root-x",
          "fully merged into main, worktree removed, observed lingering 30h",
          Now - 30 * H
        )
      )
    )
    for
      (st, sent, clock, reads) <- rig(prior)
      port = FakePort(
        recs = List(rec("d1", worktree = Some("/tmp/gone-wt"))),
        missingTrees = Set("/tmp/gone-wt"),
        merged = Map("delegate/x" -> false),
        state = st,
        sent = sent,
        clock = clock,
        reads = reads
      )
      _ <- DoneBoardWatch.scan(port)
      msgs <- sent.get
      s <- st.get
    yield
      assertEquals(msgs, Nil)
      assertEquals(s.entries, Map.empty)
    end for
  }

  test("notify window throttles repeat notices") {
    val prior = DoneBoardWatch.State(
      DoneBoardWatch.StateVersion,
      Now - 2 * H,
      Map(
        "tree:d1" -> DoneBoardWatch.Entry(
          "tree",
          "d1",
          "delegate/x",
          "main",
          "t",
          "root-x",
          "worktree wt-x clean, 0 commits ahead of main, idle 30h",
          Now - 30 * H,
          Now - 1 * H
        )
      )
    )
    for
      (st, sent, clock, reads) <- rig(prior)
      port = FakePort(
        recs = List(rec("d1", worktree = Some("/tmp/wt-x"), createdAt = Now - 30 * H)),
        trees = Map("/tmp/wt-x" -> clean(Now - 30 * H)),
        state = st,
        sent = sent,
        clock = clock,
        reads = reads
      )
      _ <- DoneBoardWatch.scan(port)
      msgs <- sent.get
      s <- st.get
    yield
      assertEquals(msgs, Nil)
      assertEquals(s.entries.get("tree:d1").map(_.lastNotifyMs), Some(Now - 1 * H))
    end for
  }

  test("sweep throttle: within MinScanIntervalMs no delegate reads happen") {
    for
      (st, sent, clock, reads) <- rig(DoneBoardWatch.State(DoneBoardWatch.StateVersion, Now - 60_000L, Map.empty))
      port = FakePort(
        recs = List(rec("d1")),
        trees = Map("/tmp/wt-x" -> clean(Now - 30 * H)),
        state = st,
        sent = sent,
        clock = clock,
        reads = reads
      )
      out <- DoneBoardWatch.scan(port)
      n <- reads.get
      msgs <- sent.get
    yield
      assertEquals(out, Nil)
      assertEquals(n, 0)
      assertEquals(msgs, Nil)
  }

  test("entries prune once the condition clears") {
    val prior = DoneBoardWatch.State(
      DoneBoardWatch.StateVersion,
      Now - 30 * H,
      Map(
        "tree:d1" -> DoneBoardWatch.Entry(
          "tree",
          "d1",
          "delegate/x",
          "main",
          "t",
          "root-x",
          "detail",
          Now - 30 * H
        ),
        "branch:d2" -> DoneBoardWatch.Entry(
          "branch",
          "d2",
          "delegate/y",
          "main",
          "t",
          "root-x",
          "detail",
          Now - 30 * H
        )
      )
    )
    for
      (st, sent, clock, reads) <- rig(prior)
      // d1's tree turned dirty again (work resumed); d2's record is gone entirely.
      port = FakePort(
        recs = List(rec("d1", worktree = Some("/tmp/wt-x"), createdAt = Now - 30 * H)),
        trees = Map("/tmp/wt-x" -> DoneBoardWatch.TreeRead(2, 0, Now - 30 * H)),
        state = st,
        sent = sent,
        clock = clock,
        reads = reads
      )
      _ <- DoneBoardWatch.scan(port)
      s <- st.get
    yield assertEquals(s.entries, Map.empty)
  }

  test("one root with several leftovers gets one aggregated notice") {
    // d2's branch was first observed lingering 30h ago; d1's tree is activity-stale.
    val prior = DoneBoardWatch.State(
      DoneBoardWatch.StateVersion,
      0L,
      Map(
        "branch:d2" -> DoneBoardWatch.Entry(
          "branch",
          "d2",
          "delegate/y",
          "main",
          "task y",
          "root-x",
          "fully merged into main, worktree removed, observed lingering 30h",
          Now - 30 * H
        )
      )
    )
    for
      (st, sent, clock, reads) <- rig(prior)
      port = FakePort(
        recs = List(
          rec("d1", branch = "delegate/x", worktree = Some("/tmp/wt-x"), createdAt = Now - 30 * H),
          rec("d2", branch = "delegate/y", worktree = Some("/tmp/gone-y"), title = "task y")
        ),
        trees = Map("/tmp/wt-x" -> clean(Now - 30 * H)),
        missingTrees = Set("/tmp/gone-y"),
        merged = Map("delegate/y" -> true),
        state = st,
        sent = sent,
        clock = clock,
        reads = reads
      )
      _ <- DoneBoardWatch.scan(port)
      msgs <- sent.get
    yield
      assertEquals(msgs.map(_._1), List("root-x"))
      assert(msgs.head._2.contains("delegate/x"))
      assert(msgs.head._2.contains("delegate/y"))
      assert(msgs.head._2.contains("2 finished delegate item(s)"))
    end for
  }

end DoneBoardWatchSpec
