package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior}
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * MergeAcceptance 机械验收闸判读（引擎加固批 2026-10-09，P1-1①）：
 *
 *  - ①a 基分支 HEAD 双亲 merge commit 且第二亲 = delegate 支头（真 --no-ff 合并
 *    夹具）⇒ ok；
 *  - ①b 祖先判据单独失败（分支并后另开新提交……此处以「基直写后支未并」形态锚
 *    a 路失败）⇒ 拒；
 *  - ② b 路独立锚：支已并（祖先真）但 HEAD 非本支的 merge commit（他支后并）⇒
 *    a 路拒——两路**都**要过（spec 判红锚：任一路单独真 ≠ 放行）；
 *  - ③ 记录锚缺失（无 branch/baseBranch/cwd 不可解析）⇒ 拒（fail-closed：合并轮
 *    的放行面无 fail-open）；
 *  - ④ onCompleted 集成：合并轮 completed 判词 × 零合并形态 ⇒ completed＋注记
 *    （source=merge-verify，reason 携证据；#566：原 blocked 退役），Release 被拒
 *    ——绝不凭自申报放行。
 */
class MergeAcceptanceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-acceptance"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    nebflow.ForceRemove.removeAll(tempRoot / "chains")
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "subagent-tasks")

  override def afterEach(context: munit.AfterEach): Unit = MergeQueue.reset()

  private def g(dir: os.Path, args: String*): String =
    os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe).out.trim()

  /** main@init + delegate 分支上一笔交付提交；返回 (repo, branch, 已并与否的形态由调用方续)。 */
  private def mkRepo(dir: os.Path, branch: String, deliver: Boolean = true): os.Path =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g(dir, "config", "user.email", "spec@nebflow.local")
    g(dir, "config", "user.name", "spec")
    g(dir, "commit", "--allow-empty", "-m", "init")
    g(dir, "checkout", "-b", branch)
    if deliver then
      os.write(dir / "deliverable.txt", "deliverable")
      g(dir, "add", ".")
      g(dir, "commit", "-m", "deliverable")
    g(dir, "checkout", "main")
    dir

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def noopRefs(
    system: ActorSystem
  ): IO[(nebflow.actor.ActorRef[nebflow.actor.AgentCommand], nebflow.actor.ActorRef[nebflow.actor.AgentEvent])] =
    for
      child <- system.spawn(
        new Behavior[nebflow.actor.AgentCommand]:
          def receive(
            ctx: nebflow.actor.ActorContext[nebflow.actor.AgentCommand],
            msg: nebflow.actor.AgentCommand
          ): IO[Behavior[nebflow.actor.AgentCommand]] = IO.pure(this)
        ,
        s"child-${scala.util.Random.nextInt(100000)}"
      )
      sup <- system.spawn(
        new Behavior[nebflow.actor.AgentEvent]:
          def receive(
            ctx: nebflow.actor.ActorContext[nebflow.actor.AgentEvent],
            msg: nebflow.actor.AgentEvent
          ): IO[Behavior[nebflow.actor.AgentEvent]] = IO.pure(this)
        ,
        s"sup-${scala.util.Random.nextInt(100000)}"
      )
    yield (child, sup)

  private def verdictRows(delegateId: String): List[(String, String, String)] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict").flatMap { e =>
      val c = e.payload.hcursor
      c.get[String]("delegateId")
        .toOption
        .filter(_ == delegateId)
        .map(_ =>
          (
            c.get[String]("verdict").toOption.getOrElse(""),
            c.get[String]("source").toOption.getOrElse(""),
            c.get[String]("reason").toOption.getOrElse("")
          )
        )
    }

  test("① a-path passes on a real --no-ff merge: two-parent HEAD, second parent = branch tip, ancestor true") {
    val repo = mkRepo(tempRoot / "a-ok", branch = "delegate/d-ma")
    g(repo, "merge", "--no-ff", "delegate/d-ma", "-m", "merge d-ma")
    val r = MergeAcceptance.verify(repo, "main", "delegate/d-ma")
    assert(r.ok, s"a real merge must pass: ${r.evidence}")
    assert(
      r.evidence.contains("secondParentIsBranchTip=true") && r.evidence.contains("isAncestor(branch→base)=true"),
      r.evidence
    )
  }

  test(
    "② b-path alone is NOT enough: branch merged but base HEAD advanced past it (another ref merged later) ⇒ refused"
  ) {
    val repo = mkRepo(tempRoot / "b-only", branch = "delegate/d-mb")
    // d-mb 先并（真 merge），随后**另一支**再并——HEAD 变成他支的 merge commit。
    g(repo, "merge", "--no-ff", "delegate/d-mb", "-m", "merge d-mb")
    g(repo, "checkout", "-b", "feature/later")
    os.write(repo / "later.txt", "later")
    g(repo, "add", ".")
    g(repo, "commit", "-m", "later work")
    g(repo, "checkout", "main")
    g(repo, "merge", "--no-ff", "feature/later", "-m", "merge later")
    val r = MergeAcceptance.verify(repo, "main", "delegate/d-mb")
    assert(!r.ok, s"stale HEAD (another branch's merge) must refuse: ${r.evidence}")
    assert(
      r.evidence.contains("secondParentIsBranchTip=false") && r.evidence.contains("isAncestor(branch→base)=true"),
      r.evidence
    )
  }

  test("③ unreadable anchors = fail-closed: missing branch ref / non-git base ⇒ refused with evidence") {
    val repo = mkRepo(tempRoot / "a-gone", branch = "delegate/d-mg", deliver = false) // 支零提交 park 在 fork
    g(repo, "branch", "-D", "delegate/d-mg")
    val r1 = MergeAcceptance.verify(repo, "main", "delegate/d-mg")
    assert(!r1.ok, "missing branch ref must refuse (no fail-open on the merge gate)")
    val r2 = MergeAcceptance.verify(tempRoot / "not-a-repo", "main", "delegate/x")
    assert(!r2.ok && r2.evidence.contains("not a git repo"), r2.evidence)
  }

  test("④ verifyRecord: incomplete spawn binding ⇒ refused; None record ⇒ refused") {
    val rec = DelegateRegistry.Record(
      id = "delegate-acc",
      executor = "nebflow",
      cwd = "/tmp",
      project = None,
      taskId = None,
      parentSessionId = "p",
      title = "t",
      createdAt = 1L,
      branch = Some("delegate/acc"),
      baseBranch = None // 基分支缺失
    )
    val r1 = MergeAcceptance.verifyRecord(Some(rec))
    assert(!r1.ok && r1.evidence.contains("incomplete spawn binding"), r1.evidence)
    assert(!MergeAcceptance.verifyRecord(None).ok, "no record must refuse")
  }

  test(
    "⑤ onCompleted integration: merge-round completed verdict over a NEVER-merged branch ⇒ completed+note (source=merge-verify), release refused"
  ) {
    val system = ActorSystem(s"ma-5-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        (child, sup) <- noopRefs(system)
        // 零合并形态：支 park 在 fork（上批红验③的旧夹具形态——P1-1① 起正确判否）
        repo = mkRepo(tempRoot / "never-merged", branch = "delegate/delegate-ma5", deliver = false)
        _ = DelegateRegistry.put(
          DelegateRegistry.Record(
            id = "delegate-ma5",
            executor = "nebflow",
            cwd = repo.toString,
            project = None,
            taskId = Some("81"),
            parentSessionId = "nebula-root",
            title = "seeded",
            createdAt = 1L,
            branch = Some("delegate/delegate-ma5"),
            baseBranch = Some("main"),
            projectWorkspace = Some(repo.toString)
          )
        )
        _ = MergeQueue.enqueue(
          MergeQueue.Entry(
            delegateId = "delegate-ma5",
            branch = "delegate/delegate-ma5",
            baseWs = repo.toString,
            baseBranch = "main",
            worktree = false,
            projectKey = "p-ma5",
            taskId = Some("81"),
            parentSessionId = "nebula-root",
            description = "t",
            readyAt = 1L,
            childRef = child,
            supervisorRef = sup
          )
        )
        // 判定机缺席＋无自申报 ⇒ 既有回落链本来会放 completed——机械验收闸必须拦下
        outcome <- IO { DelegateJudge.DelegateJudgePort.clear() } *>
          DelegateTerminal.onCompleted(res, "delegate-ma5", "nebula-root", "t", "brief", "merge round done", child, sup)
        rows = verdictRows("delegate-ma5")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        // #566：验收拒绝 = completed＋注记（source=merge-verify），不再是 blocked 状态。
        assert(
          outcome.toString.contains("completed") && outcome.toString.contains("[delegate-judge] note:"),
          s"a self-claimed merge with no merge commit must be refused as completed+note: $outcome"
        )
        assertEquals(rows.map(_._1).lastOption, Some("completed"), "no blocked wire word is ever written")
        assertEquals(rows.map(_._2).lastOption, Some("merge-verify"), "the refusal source must be merge-verify")
        val reason = rows.map(_._3).mkString
        assert(reason.contains("mechanical merge acceptance refused") && reason.contains("baseTip"), reason)
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

end MergeAcceptanceSpec
