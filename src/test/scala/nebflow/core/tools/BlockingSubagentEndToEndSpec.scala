package nebflow.core.tools

import cats.effect.IO
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentDef
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * P0-2 · Subagent / Workflow 阻塞桥 `finalText` 端到端可完成（本批新增验收面）。
 *
 * 审计 §二·任务3 根因：`BlockingSubagent.scala` 的 wsSend 间谍读
 * `agentDone` 的 `finalText` 字段，而全树此前零生产者 ⇒ 阻塞桥恒报
 * 「ended without a final text」。本批在**上游产出侧**补生产者
 * （`AgentFinishTurn.returnToIdle` ⇒ `AgentStreamEvent.Done.finalText` —
 *  仅 subagent 帧携带，session 级 done 载荷字节稳定）。
 *
 * 本 spec 钉**端到端**（不是只测桥）：真实 `AgentActor` 子会话跑完一轮
 * （脚本化 LLM 瞬回文本）⇒ `BlockingSubagent.run` 返回的 `Right(text)` 正是
 * 子会话最终文本（桥真完成，不是超时兜底，不是「ended without a final text」）。
 *
 * 三条读数：
 *  - Subagent（defName=subagent）：blocking run 完成，返回子文本。
 *  - ChainWrite（tool-face-agentflow-retire 批 2026-11-05 起为唯一链登记入口，
 *    ex-AgentFlow 薄壳）：nodes 走登记闸（scope 图落 pending → 入口放行 → settleDeps
 *    逐级完成；b 的 prompt 带 a 的 `=== Node a ===` 结果块）——非阻塞 ACK 即返；
 *    并钉住 `orchestrationMode` 升级写点（登记的 ACK 路径上把调用 delegate 升到
 *    `agentflow`，迁移前该写点在已删的 已删的 AgentFlow 工具 里）。
 *  - 生产者排他钉：`grep '"finalText"' src/main/scala` 恰两个命中
 *    （`AgentEvent.scala` 生产者 + `BlockingSubagent.scala` 读取点）。
 */
class BlockingSubagentEndToEndSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val repoRoot: os.Path = os.pwd
  private val tempRoot: os.Path = repoRoot / "target" / "test-blocking-subagent-e2e"
  private val originalRoot = PathUtil.dataRoot

  override def beforeAll(): Unit =
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "agents")
    os.write.over(tempRoot / nebflow.shared.Branding.configFileName, "{}")
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  /** 脚本化 LLM：记录每个会话收到的请求，并按其 sessionId 回文本。 */
  private class ScriptedLlm(script: String => String, captured: TrieMap[String, LlmRequest]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(IO(captured.put(req.sessionId, req))).drain ++
        Stream(
          StreamChunk.TextDelta(script(req.sessionId)),
          StreamChunk.Done(None, None)
        )

  private def ctxFor(
    system: nebflow.actor.ActorSystem,
    res: SharedResources,
    parentRef: nebflow.actor.ActorRef[nebflow.actor.AgentCommand]
  ): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("e2e-parent"),
      rootSessionId = Some("e2e-parent"),
      sharedResources = Some(res),
      actorSystem = Some(system),
      // 关键夹具：spawnAgentActor 的 parentRef ⇒ 子会话 isSubagent=true ⇒
      // AgentFinishTurn.returnToIdle 在子会话 Done 帧带 finalText（生产者）。
      agentActorRef = Some(parentRef)
    )

  /** 哑父 actor（真实 ActorSystem 内的真实 ref——吸消息、保持活着）。 */
  private def dummyParent(
    system: nebflow.actor.ActorSystem
  ): IO[nebflow.actor.ActorRef[nebflow.actor.AgentCommand]] =
    def loop: nebflow.actor.Behavior[nebflow.actor.AgentCommand] =
      nebflow.actor.Behaviors.receiveMessage[nebflow.actor.AgentCommand](_ => IO.pure(loop))
    system.spawn(loop, s"parent-${scala.util.Random.nextInt(100000)}")

  // ── Subagent：阻塞桥端到端完成 ──────────────────────────────

  test("P0-2 Subagent: BlockingSubagent.run completes end-to-end and returns the child session's final text"):
    val system = nebflow.actor.ActorSystem("bse2e-sub")
    val captured = TrieMap[String, LlmRequest]()
    (for
      parent <- dummyParent(system)
      res <- SpecResources.mkResources(system, tempRoot, new ScriptedLlm(_ => "SUBAGENT-FINAL-TEXT", captured))
      out <- BlockingSubagent.run(
        prompt = "survey the repo",
        description = "recon",
        ctx = ctxFor(system, res, parent),
        defName = nebflow.core.entity.BuiltinAgents.SubagentName
      )
    yield out)
      .map { out =>
        assert(out.isRight, s"the blocking bridge must COMPLETE (not timeout / not 'ended without a final text'): $out")
        assertEquals(out.getOrElse(""), "SUBAGENT-FINAL-TEXT")
        assert(
          captured.keys.exists(_.startsWith("subagent-")),
          s"the child session must have run a turn: ${captured.keys.toList}"
        )
      }
      .guarantee(system.stopAll.handleErrorWith(_ => IO.unit))

  // ── ChainWrite（N1 刀2）——整链登记闸 ＋ **orchestrationMode 升级写点** e2e ──
  // tool-face-agentflow-retire 批（2026-11-05，作者裁定二）：本用例原走 已删的 AgentFlow 工具
  // 薄壳（已随工具退役删除），改走**唯一现役入口** ChainWriteTool。同时钉住本批的
  // **头号判据**：`orchestrationMode = "agentflow"` 的唯一写点自 已删的 AgentFlow 工具 迁入
  // `ChainWriteTool.registerChain` ⇒ 一次真实链登记后，读回 mode 必须已升到
  // `agentflow`（迁移前这条升级永远不发生的形态就在这里被抓住）。

  test(
    "P0-2 ChainWrite: a two-node chain registers through the gate, the entry runs, node b settles after a — AND orchestrationMode rises to 'agentflow'"
  ):
    val captured = TrieMap[String, LlmRequest]()
    val system = nebflow.actor.ActorSystem("bse2e-cw")
    val llm = new ScriptedLlm(_ => "STEP-TEXT", captured)
    val delegateId = "delegate-general-af11aa11"
    val ws = tempRoot / "af-ws"
    os.remove.all(ws)
    os.makeDir.all(ws)
    def waitUntil(timeout: FiniteDuration)(cond: IO[Boolean]): IO[Unit] =
      def go(deadline: Long): IO[Unit] =
        cond.flatMap {
          case true => IO.unit
          case false =>
            if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("waitUntil timeout"))
            else IO.sleep(50.millis) >> go(deadline)
        }
      go(System.currentTimeMillis() + timeout.toMillis)
    (for
      res <- SpecResources.mkResources(system, tempRoot, llm)
      // spawn 形态记录（mode 缺省 = single）：升级秩 0 ⇒ 升 agentflow（秩 2）必然发生。
      _ <- IO(
        nebflow.core.delegate.DelegateRegistry.put(
          nebflow.core.delegate.DelegateRegistry.Record(
            id = delegateId,
            executor = "nebflow",
            cwd = ws.toString,
            project = None,
            taskId = None,
            parentSessionId = "e2e-root",
            title = "chain e2e",
            createdAt = System.currentTimeMillis() - 60_000L,
            mode = None
          )
        )
      )
      mo <- nebflow.core.project.DelegateFlowHosts.mount(
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
      h = mo.getOrElse(fail("delegate host must mount"))
      modeBefore <- IO(nebflow.core.delegate.DelegateRegistry.find(delegateId).flatMap(_.orchestrationMode))
      registeredAt = System.currentTimeMillis()
      out <- ChainWriteTool.call(
        JsonObject.fromIterable(
          List(
            "chainName" -> "e2e-chain".asJson,
            "nodes" -> io.circe.Json.arr(
              io.circe.Json.obj("id" -> "a".asJson, "task" -> "do A".asJson),
              io.circe.Json.obj("id" -> "b".asJson, "task" -> "do B".asJson, "deps" -> io.circe.Json.arr("a".asJson))
            )
          )
        ),
        ToolContext(
          projectRoot = os.pwd.toString,
          sessionId = Some(delegateId),
          sharedResources = Some(res),
          actorSystem = Some(system)
        )
      )
      // 回执后立刻读回（升级写点就在回执路径上——登记成功 ⇒ 同步读完即应已升）
      modeAfter <- IO(nebflow.core.delegate.DelegateRegistry.find(delegateId).flatMap(_.orchestrationMode))
      readBackAt = System.currentTimeMillis()
      _ <- waitUntil(60.seconds)(
        h.store.getNode("b").map(n => n.exists(x => nebflow.core.project.NodeLifecycle.Terminal.contains(x.status)))
      )
      a <- h.store.getNode("a").map(_.getOrElse(fail("node a must exist")))
      b <- h.store.getNode("b").map(_.getOrElse(fail("node b must exist")))
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
    yield (out, a, b, modeBefore, modeAfter, registeredAt, readBackAt))
      .map { case (out, a, b, modeBefore, modeAfter, registeredAt, readBackAt) =>
        assert(out.isRight, s"the non-blocking ack must land: $out")
        val ack = out.getOrElse("")
        assert(ack.contains("Chain 'e2e-chain'"), s"declared chain id in the ack: $ack")
        assert(ack.contains("released"), s"entry release declared in the ack: $ack")
        assertEquals(a.status, nebflow.core.project.NodeLifecycle.Completed, "entry node runs to completion")
        assertEquals(b.status, nebflow.core.project.NodeLifecycle.Completed, "downstream node settles via deps")
        // 逐级交付证明：节点 b 的 prompt 必带节点 a 的结果块（=== Node a === 头）——
        // 数据流沿边投递（buildInput 上游结果块）。
        val reqTexts = captured.values.toList.flatMap(_.messages.lastOption).map(m => m.textContent).mkString("\n")
        assert(
          captured.size >= 2,
          s"both nodes must actually run a turn, got ${captured.size}: ${captured.keys.toList}"
        )
        assert(reqTexts.contains("=== Node a ==="), s"node b's prompt must carry node a's result block: $reqTexts")
        // 🔴 头号判据：orchestrationMode 写点迁移实测时间线（登记 → 读回 mode）。
        assertEquals(modeBefore, None, "pre-registration the record has no orchestration mode (rank 0)")
        assertEquals(
          modeAfter,
          Some(nebflow.core.delegate.DelegateRegistry.ModeAgentflow),
          s"a successful chain registration MUST raise orchestrationMode to 'agentflow' " +
            s"(write point migrated from the deleted 已删的 AgentFlow 工具 into ChainWriteTool.registerChain); " +
            s"timeline: register@$registeredAt -> readback@$readBackAt (Δt=${readBackAt - registeredAt}ms)"
        )
        println(
          s"[MODE-MIGRATION] mode before registration = $modeBefore; " +
            s"chain registration @$registeredAt; mode readback @$readBackAt => $modeAfter " +
            s"(Δt=${readBackAt - registeredAt}ms)"
        )
      }
      .guarantee(
        (nebflow.core.project.DelegateFlowHosts.unmount(delegateId) *> system.stopAll.handleErrorWith(_ => IO.unit))
      )

  // ── stuck-panel 批（2026-10-06）：叶子终态回收（reap）──────────────────
  // 根因（2026-10-06 17:16 作者截图 3 个 SubTask「空闲」僵尸行）：BlockingSubagent
  // 无监督者，「终态即回收」无实现——桥完成后 registry 条目滞留（kind=SubTask、
  // status=Idle）、task store 恒 running、子 actor 永驻 idle。getActiveAgents 按
  // 「registry 有记录 = 在飞」契约如实上报 ⇒ 面板渲染永不消失的空闲行。
  // 本 spec 钉终态三清：桥完成后 ① registry 条目已摘除 ② task 记录终态化
  // completed（带 completedAt）③ 桥本身未破（finalText 仍在——回归前置）。

  test(
    "stuck-panel: BlockingSubagent.run reaps the leaf child at terminal — registry entry removed + task record terminalized"
  ):
    val system = nebflow.actor.ActorSystem("bse2e-reap")
    val captured = TrieMap[String, LlmRequest]()
    (for
      parent <- dummyParent(system)
      res <- SpecResources.mkResources(system, tempRoot, new ScriptedLlm(_ => "REAPED-CHILD-TEXT", captured))
      out <- BlockingSubagent.run(
        prompt = "survey the repo",
        description = "reap-recon",
        ctx = ctxFor(system, res, parent),
        defName = nebflow.core.entity.BuiltinAgents.SubagentName
      )
      registry <- res.agentRegistry.get
      // recordTask 的 parentSessionId = ctx.sessionId（见 run：parentSid.getOrElse("")）
      tasks <- res.subAgentTaskStore.loadTasks("e2e-parent")
    yield (out, registry, tasks, captured.keys.toList))
      .map { case (out, registry, tasks, capturedSids) =>
        // ③ 回归前置：桥先完成（终态帧 finalText 已交付工具结果）
        assert(out.isRight, s"the blocking bridge must COMPLETE before reap is asserted: $out")
        assertEquals(out.getOrElse(""), "REAPED-CHILD-TEXT")
        val childSids = capturedSids.filter(_.startsWith("subagent-"))
        assert(childSids.nonEmpty, s"the child session must have run a turn: $capturedSids")
        // ① registry：僵尸条目必须已被摘除（getActiveAgents 不再上报）
        childSids.foreach { sid =>
          assert(
            !registry.contains(sid),
            s"zombie registry entry must be reaped, still present: sid=$sid registry=${registry.keys.toList}"
          )
        }
        // ② task store：spawn 记录的 running 必须终态化 completed + completedAt
        val recs = tasks.filter(t => childSids.contains(t.taskId))
        assert(recs.nonEmpty, s"task record must exist for $childSids, got ${tasks.map(_.taskId)}")
        recs.foreach { rec =>
          assertEquals(rec.status, "completed", s"task record must be terminalized: ${rec.taskId}=${rec.status}")
          assert(rec.completedAt.isDefined, s"completedAt must be set on ${rec.taskId}")
        }
      }
      .guarantee(system.stopAll.handleErrorWith(_ => IO.unit))

  // ── 生产者排他钉（机器可查验收点 3）──────────────────────────

  test("P0-2 producer pin: `grep '\"finalText\"' src/main/scala` = producer + reader (exactly 2)"):
    val src = List(
      os.read(repoRoot / "src" / "main" / "scala" / "nebflow" / "actor" / "AgentEvent.scala"),
      os.read(repoRoot / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "BlockingSubagent.scala")
    )
    val hits = src.map(_.linesIterator.count(_.contains("\"finalText\""))).sum
    assertEquals(
      hits,
      2,
      s"the producer (AgentEvent) + the reader (BlockingSubagent) must be the ONLY two `\"finalText\"` hits in main, got $hits"
    )
    assert(
      os.read(repoRoot / "src" / "main" / "scala" / "nebflow" / "actor" / "AgentEvent.scala")
        .contains("\"finalText\" -> ft.asJson"),
      "the producer (AgentEvent.toJson) must be present"
    )

end BlockingSubagentEndToEndSpec
