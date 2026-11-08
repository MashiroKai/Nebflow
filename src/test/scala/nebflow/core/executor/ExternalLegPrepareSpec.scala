package nebflow.core.executor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.delegate.{ChainStore, DelegateJudge, DelegateRegistry}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, MessageRole, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * 外部执行器腿接纪律判读（引擎加固批 2026-10-09，P1-2）：
 *
 *  - ① spawn 前的座位腿（delegate-param-decisions 批后 = **零分配就地腿**）：git
 *    工作区 = 分支 `delegate/<id>` ＋ 就地座位（进程 cwd = 项目工作区；外部腿没有
 *    `worktree` 参数，工具面显式拒收）；记录四键落库、spawn 行 `seat` 键在链上、
 *    brief 行进会话历史。判定机已删除——spawn 行不再有 worktreeJudged/judgeLeg；
 */
class ExternalLegPrepareSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-external-leg"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot / "chains")
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "subagent-tasks")

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateJudge.DelegateJudgePort.clear()

  private def mkRepo(dir: os.Path): os.Path =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    dir

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def spec(
    repo: os.Path,
    resources: nebflow.core.AgentRuntimePort,
    task: String
  ): ExternalSessionRunner.SpawnSpec =
    ExternalSessionRunner.SpawnSpec(
      executorId = "claude-code",
      binPath = "/bin/echo", // 假 CLI：打印 argv 即退（exit 0，零帧）——真通路的进程面
      task = task,
      title = "external leg task",
      workspace = repo.toString,
      project = None,
      taskId = Some("91"),
      parentSessionId = "nebula-root",
      rootSessionId = "nebula-root",
      parentRef = None,
      wsSend = None,
      resources = resources
    )

  /** 轮询直到条件成立（外部腿 settle 是异步 fiber——轮询锚）。 */
  private def pollUntil(label: String, maxMs: Long = 30000)(cond: IO[Boolean]): IO[Unit] =
    val deadline = System.currentTimeMillis() + maxMs
    def loop(): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          IO(System.currentTimeMillis()).flatMap { now =>
            if now > deadline then IO.raiseError(new RuntimeException(s"poll timeout: $label"))
            else IO.sleep(200.millis) *> loop()
          }
      }
    loop()

  test(
    "① external spawn prepares the SAME branch + IN-PLACE seat (zero allocation); record + spawn-row `seat` key + brief line land"
  ) {
    val system = ActorSystem(s"el-1-${scala.util.Random.nextInt(100000)}")
    for
      res <- nebflow.agent.SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ = DelegateJudge.DelegateJudgePort.clear() // 判定机缺席 = fail-open 反转腿
      repo = mkRepo(tempRoot / "ext-repo")
      spawned <- ExternalSessionRunner.spawn(spec(repo, res, "read the code and report"))
      (sessionId, ack) = spawned.fold(err => fail(s"spawn failed: $err"), identity)
      _ <- pollUntil("task row terminal (settle ran)")(
        res.subAgentTaskStore.findByTaskId(sessionId).map(_.map(_.status).contains("completed"))
      )
      record = DelegateRegistry.find(sessionId)
      spawnRows = ChainStore.listSync().flatMap(_.entries).filter(_.kind == "delegate-spawn")
      userText <- res.sessionStore
        .loadMessagesForSession(sessionId)
        .map(
          _.filter(_.role == MessageRole.User).map(_.textContent).mkString("\n")
        )
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val r = record.getOrElse(fail("no registry record for the external session"))
      assertEquals(r.branch, Some(s"delegate/$sessionId"), "the external leg gets the same delegate/<id> branch")
      // delegate-param-decisions 批（2026-11-04）：外部腿没有 `worktree` 参数（工具面
      // 已显式拒收）⇒ **零分配**：不建树，座位 = 项目工作区本身。
      assertEquals(r.worktree, None, "zero allocation: the external leg never gets a worktree")
      assertEquals(r.cwd, repo.toString, "the process cwd is the shared project workspace")
      assertEquals(r.baseBranch, Some("main"))
      assertEquals(r.projectWorkspace, Some(repo.toString))
      assertEquals(r.worktreeJudged, None, "the worktree judge is gone — no verdict is recorded")
      assert(!os.exists(repo / ".nebflow" / "worktrees"), "not even the worktree root is created")
      val branchOut = os
        .proc("git", "-C", repo.toString, "rev-parse", "--abbrev-ref", s"delegate/$sessionId")
        .call(check = true)
        .out
        .trim()
      assertEquals(branchOut, s"delegate/$sessionId", "the branch exists in the base repo")
      // spawn 行审计键（worktreeJudged / judgeLeg —— additive）
      val row = spawnRows.find(_.payload.hcursor.get[String]("delegateId").toOption.contains(sessionId))
      assert(row.isDefined, "the external leg writes a delegate-spawn ChainStore row")
      val c = row.get.payload.hcursor
      assertEquals(
        c.get[String]("seat").toOption,
        Some("none"),
        "the spawn row records the seat rule that hit (here: zero allocation)"
      )
      assert(c.get[Boolean]("worktreeJudged").toOption.isEmpty, "judge keys are gone")
      assert(c.get[String]("judgeLeg").toOption.isEmpty, "judge keys are gone")
      assertEquals(c.get[String]("executor").toOption, Some("claude-code"))
      // brief 申报行进会话历史（外部零平台注入——任务文本即全部上下文）
      assert(
        userText.contains("[working branch]"),
        s"the working-branch line must precede the task text: ${userText.take(200)}"
      )
      assert(
        userText.contains("NO worktree was allocated"),
        s"the in-place brief line must declare the zero-allocation seat: ${userText.take(400)}"
      )
      assert(userText.contains("read the code and report"), "the original task text is preserved after the brief line")
    end for
  }

end ExternalLegPrepareSpec
