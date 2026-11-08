package nebflow.core.tools

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.project.{DelegateFlowHosts, DelegateFlowRuntime, NodeDef, NodeLifecycle}
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * `delegate_report(finish)` 语义重构验收（P3 批 2026-11-02 · 作者令逐字：「finish
 * 要是任务完成了 而不是汇报轮次完成 有在飞节点是不能接受完成的 需要用 system
 * reminder 提醒 要么取消 要么 stand by 等节点完成 任务确定完成后再使用 finish」）。
 *
 * 判据（本 spec 逐条钉死）：
 *  - R1 拒绝：作用域图里有非终态节点 ⇒ `finish` **返回结构化错误**（不是软提醒），
 *    状态槽零写入；
 *  - R2 文案断言：reminder 里列在飞节点 id/name/status、给出「取消 / stand by」两条
 *    路、明说「任务确定全部完成后才能用 finish」；
 *  - R3 不误伤：无宿主（delegate 没派任何链）/ 宿主里全终态 ⇒ finish 照常成功；
 *  - R4 取消路径可用：delegate 用自己的 NodeCancel(chain=…) / NodeEdit(abandon) 真能
 *    把在飞节点推到终态，推完 finish 立刻放行 ⇒ 提醒里那句「要么取消」不是空话，
 *    也不存在「永远卡住无法 finish」的死锁（逃逸口 = 工具面里真实存在的两条）。
 *  - R5 fail-soft：态势读取失败不阻断（绝不因读面故障把 delegate 锁死在「不能
 *    finish」）。
 */
class DelegateReportInflightFinishSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-report-inflight"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(tempRoot / "sessions")
  os.makeDir.all(tempRoot / "delegates")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    (DelegateFlowHosts.clear *> cats.effect.IO(
      nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    )).unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateFlowHosts.clear.unsafeRunSync()

  // ── 基建（ChainWriteToolSpec / DelegateFlowHostsSpec 同款骨架）──────────────

  private def mkResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("unused"), StreamChunk.Done(None, None))

  private def mount(ws: os.Path, delegateId: String, system: ActorSystem, res: SharedResources) =
    DelegateFlowHosts.mount(
      delegateId = delegateId,
      project = None,
      baseWorkspace = ws.toString,
      engineCwd = ws.toString,
      system = system,
      resources = res,
      wsSend = None,
      reportGateHold = Some(false),
      rootNotifyQuietMs = Some(0),
      destroyWindowMs = Some(0L)
    )

  private def seedRecord(id: String, ws: os.Path): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = ws.toString,
        project = None,
        taskId = Some("1367"),
        parentSessionId = "nebula-root",
        title = "inflight finish semantics",
        createdAt = 1L
      )
    )

  private def seedNode(
    h: DelegateFlowRuntime,
    id: String,
    name: String,
    status: String,
    chain: Option[String]
  ): IO[Unit] =
    h.store
      .mutate(s =>
        s.copy(nodes =
          s.nodes.updated(
            id,
            NodeDef(
              id = id,
              name = name,
              agent = "general",
              task = Some(s"seeded $id"),
              status = status,
              chainId = chain,
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      .void

  private def ctx(sessionId: Option[String], ws: os.Path): ToolContext =
    ToolContext(projectRoot = ws.toString, sessionId = sessionId)

  private def report(sessionId: String, category: String, detail: String, ws: os.Path): IO[Either[String, String]] =
    DelegateReportTool
      .call(
        Json.obj("category" -> category.asJson, "detail" -> detail.asJson).asObject.get,
        ctx(Some(sessionId), ws)
      )
      .map(_.left.map(_.message))

  // ── R1 + R2：在飞 ⇒ finish 被拒 + 文案三要素 ─────────────────────────────

  test(
    "R1/R2: finish is REFUSED while scope nodes are in flight — structured error + reminder naming nodes, both exits, and the completion condition"
  ) {
    val ws = os.temp.dir(prefix = "nb-dr-inflight-", deleteOnExit = false)
    val system = ActorSystem(s"dr-inflight-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-nebflow-1367aaaa"
    for
      res <- mkResources(system, new SilentLlm)
      _ = seedRecord(delegateId, ws)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      // #1367 形态：上游 failed（终态，不入清单）＋ 两个下游 pending（在飞）
      _ <- seedNode(h, "land", "land-and-evidence", NodeLifecycle.Failed, Some("profile-settings-batch"))
      _ <- seedNode(h, "gate", "batch-gate", NodeLifecycle.Pending, Some("profile-settings-batch"))
      _ <- seedNode(h, "docs", "docs-pass", NodeLifecycle.Pending, Some("profile-settings-batch"))
      _ <- seedNode(h, "recon", "recon", NodeLifecycle.Completed, Some("profile-settings-batch"))
      res0 <- report(delegateId, "finish", "all done", ws)
      recAfter = DelegateRegistry.find(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val err = res0.left.getOrElse(fail(s"finish with in-flight nodes MUST be refused, got: $res0"))
      assert(err.contains("DELEGATE_REPORT_INFLIGHT"), s"structured error code missing: $err")
      assert(err.contains("<system-reminder>"), s"reminder block missing: $err")
      // ① 在飞节点 id + name 逐个列出（终态的 land/recon 不列）
      assert(err.contains("gate") && err.contains("batch-gate"), s"pending node id/name must be listed: $err")
      assert(err.contains("docs") && err.contains("docs-pass"), s"second pending node must be listed: $err")
      assert(!err.contains("land-and-evidence"), s"terminal (failed) node must NOT be listed: $err")
      // ② 两条路：取消 / stand by
      assert(err.contains("取消"), s"the cancel exit must be offered: $err")
      assert(err.contains("standby"), s"the standby exit must be offered: $err")
      // ③ 完成任务后才 finish
      assert(err.contains("任务确定全部完成后"), s"the completion condition must be stated: $err")
      // 零写入：状态槽保持原样（拒绝不是「记一笔就完」）
      assertEquals(recAfter.flatMap(_.reportStatus), None, "a refused finish must not touch the status slot")
    end for
  }

  test(
    "R1b: a lone non-terminal node outside any chain is in flight too — the criterion is node status, not chain membership"
  ) {
    val ws = os.temp.dir(prefix = "nb-dr-inflight-lone-", deleteOnExit = false)
    val system = ActorSystem(s"dr-inflight-lone-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-nebflow-1367bbbb"
    for
      res <- mkResources(system, new SilentLlm)
      _ = seedRecord(delegateId, ws)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      _ <- seedNode(h, "solo", "solo-node", NodeLifecycle.Running, None)
      res0 <- report(delegateId, "finish", "all done", ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val err = res0.left.getOrElse(fail(s"a lone running node must still refuse finish, got: $res0"))
      assert(err.contains("solo-node"), s"the lone node must be named: $err")
      assert(err.contains("DELEGATE_REPORT_INFLIGHT"))
    end for
  }

  // ── R3：不误伤（无宿主 / 全终态 / 非 finish 类别）────────────────────────

  test(
    "R3: no false positives — no host mounted (no chain dispatched) still finishes; all-terminal graph finishes; merge/standby untouched"
  ) {
    val ws = os.temp.dir(prefix = "nb-dr-nohost-", deleteOnExit = false)
    val system = ActorSystem(s"dr-nohost-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-nebflow-1367cccc"
    for
      res <- mkResources(system, new SilentLlm)
      _ = seedRecord(delegateId, ws)
      // ① 从未派链（宿主未挂载）
      noHost <- report(delegateId, "finish", "pure read, nothing dispatched", ws)
      // ② 宿主在、图内全终态
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      _ <- seedNode(h, "a", "done-a", NodeLifecycle.Completed, None)
      _ <- seedNode(h, "b", "dead-b", NodeLifecycle.Failed, None)
      _ <- seedNode(h, "c", "gone-c", NodeLifecycle.Cancelled, None)
      _ <- seedNode(h, "d", "blocked-d", NodeLifecycle.Blocked, None)
      allTerminal <- report(delegateId, "finish", "all terminal", ws)
      // ③ 非 finish 类别不受本闸约束
      _ <- seedNode(h, "e", "live-e", NodeLifecycle.Pending, None)
      merge <- report(delegateId, "merge", "branch work done", ws)
      standby <- report(delegateId, "standby", "waiting for upstream", ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(noHost.isRight, s"a delegate with no scope host must finish normally, got: $noHost")
      assert(noHost.exists(_.contains("finish recorded")), s"got: $noHost")
      assert(allTerminal.isRight, s"all-terminal graph must finish normally, got: $allTerminal")
      assert(merge.left.exists(_.contains("merge is retired")), s"merge belongs to the project merger, got: $merge")
      assert(standby.isRight, s"standby must not be gated by in-flight nodes, got: $standby")
    end for
  }

  // ── R4：取消路径可用（提醒里的「要么取消」是 delegate 真能做的动作）───────

  test(
    "R4: the offered cancel exit really works — NodeCancel(chain=…) terminalizes the in-flight nodes and finish is then accepted"
  ) {
    val ws = os.temp.dir(prefix = "nb-dr-cancel-", deleteOnExit = false)
    val system = ActorSystem(s"dr-cancel-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-nebflow-1367dddd"
    for
      res <- mkResources(system, new SilentLlm)
      _ = seedRecord(delegateId, ws)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      _ <- seedNode(h, "gate", "batch-gate", NodeLifecycle.Pending, Some("chain-n-cancelme"))
      _ <- seedNode(h, "docs", "docs-pass", NodeLifecycle.Pending, Some("chain-n-cancelme"))
      before <- report(delegateId, "finish", "done", ws)
      cancelled <- NodeCancelTool
        .call(
          Json.obj("chain" -> "chain-n-cancelme".asJson).asObject.get,
          ctx(Some(delegateId), ws)
        )
        .map(_.left.map(_.message))
      after <- report(delegateId, "finish", "done (in-flight cancelled)", ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(before.isLeft, s"precondition: in-flight nodes must refuse finish, got: $before")
      assert(cancelled.isRight, s"the delegate's own NodeCancel(chain=…) must work inside its scope, got: $cancelled")
      assert(
        after.isRight,
        s"once every node is terminal, finish MUST be accepted (no deadlock), got: $after"
      )
    end for
  }

  test(
    "R4b: the abandon exit also works — NodeEdit(abandon=true) on a pending node clears it, and the tool face really carries both tools"
  ) {
    val ws = os.temp.dir(prefix = "nb-dr-abandon-", deleteOnExit = false)
    val system = ActorSystem(s"dr-abandon-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-nebflow-1367eeee"
    for
      res <- mkResources(system, new SilentLlm)
      _ = seedRecord(delegateId, ws)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      _ <- seedNode(h, "solo", "solo-node", NodeLifecycle.Pending, None)
      before <- report(delegateId, "finish", "done", ws)
      abandoned <- NodeEditTool
        .call(
          Json.obj("nodename" -> "solo-node".asJson, "abandon" -> true.asJson).asObject.get,
          ctx(Some(delegateId), ws)
        )
        .map(_.left.map(_.message))
      after <- report(delegateId, "finish", "done (abandoned)", ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(before.isLeft, s"precondition: a lone pending node must refuse finish, got: $before")
      assert(abandoned.isRight, s"NodeEdit(abandon=true) must work for a pending node, got: $abandoned")
      assert(after.isRight, s"after abandon, finish must be accepted, got: $after")
      // 工具面实证：delegate 会话带着两个逃逸口（挂载面单点）
      assert(
        NodeTools.DelegateNodeToolsFace.contains("NodeCancel") && NodeTools.DelegateNodeToolsFace.contains("NodeEdit"),
        s"the delegate tool face must carry NodeCancel + NodeEdit, got: ${NodeTools.DelegateNodeToolsFace}"
      )
    end for
  }

end DelegateReportInflightFinishSpec
