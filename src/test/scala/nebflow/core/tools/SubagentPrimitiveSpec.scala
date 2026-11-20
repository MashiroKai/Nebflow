package nebflow.core.tools

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.testkit.TestControl
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behavior, Behaviors}
import nebflow.agent.SpecResources
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk, SubAgentTask}

import scala.concurrent.duration.*

/**
 * dispatcher 重设计 P0「抽件」的**行为闸**：子会话原语单点
 * [[SubagentPrimitives]] 的四腿（帧匹配 / 预算耗尽 / 超时 / `reap`）。
 *
 * 四腿**全部断言在本 spec**（设计稿 §8「新增一条原语闸断言帧匹配/预算耗尽/
 * 超时/reap 四腿」；核稿 §6.3 点名本路径）；外壳静态闸
 * `scripts/check-subagent-primitives.mjs` 只断言抽取面与单源，不重复任何
 * 行为断言。
 *
 *  - 腿① 帧匹配：`agentDone`/`agentEnd` + 双键（`nodeSessionId`/`agentId`）
 *    命中 ⇒ 完成桥；`finalText` 缺席 ⇒ 落 `None`；非终态帧 / 他会话帧 ⇒ 不落。
 *  - 腿② 预算耗尽：段一预算窗零帧 ⇒ 判死（`subagent-never-started`）+ Stop +
 *    补一帧面板终态（真实阈值 = [[SubagentPrimitives.startBudget]]，虚拟时钟）。
 *  - 腿③ 超时：段二无完成帧 ⇒ 到 15min 硬顶报 "did not finish within" + Stop +
 *    补帧（真实阈值 = [[SubagentPrimitives.Timeout]]，虚拟时钟）。
 *  - 腿④ `reap`：四腿结算顺序（task 终态化 → registry 移除 → ask 清理 →
 *    child Stop）+ fail-soft（账目缺席不抛）。
 *
 * 时间全部走 cats-effect TestControl 虚拟时钟（腿②③），零真实等待；
 * 手法同 `McpCallTimeoutSpec` / `RetryWithBackoffSpec`。
 *
 * 零行为变化：既有 `BlockingSubagentStartGuardSpec` /
 * `BlockingSubagentEndToEndSpec` / `SubagentCrashRecoverySpec` 抽取前后原样全绿。
 */
class SubagentPrimitiveSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-subagent-primitive"
  private val originalRoot = nebflow.shared.PathUtil.dataRoot

  override def beforeAll(): Unit =
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "agents")
    os.write.over(tempRoot / nebflow.shared.Branding.configFileName, "{}")
    nebflow.shared.PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    nebflow.shared.PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  /** `reap` 腿不发 LLM；任何 LLM 触达即判夹具错（腿④ 只用 registry / task store）。 */
  private def deadLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("spec does not exercise the llm"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("spec does not exercise the llm"))

  // ── 腿① 帧匹配（纯函数桥；零时钟）──────────────────────────────────────────

  test("腿① 帧匹配: agentDone + nodeSessionId 命中 + finalText ⇒ 完成桥交付文本，并原样转发基座"):
    for
      done <- Deferred[IO, Option[String]]
      forwarded <- IO.ref[List[Json]](Nil)
      bridge = SubagentPrimitives.spy(done, "subagent-prim-1", json => forwarded.update(_ :+ json))
      _ <- bridge(
        Json.obj(
          "type" -> "agentDone".asJson,
          "nodeSessionId" -> "subagent-prim-1".asJson,
          "finalText" -> "FINAL".asJson
        )
      )
      got <- done.tryGet
      fwd <- forwarded.get
    yield
      assertEquals(got, Some(Some("FINAL")), "agentDone + nodeSessionId 命中必须完成桥并交付 finalText")
      assertEquals(
        fwd.map(_.hcursor.get[String]("type").toOption),
        List(Some("agentDone")),
        "间谍必须原样转发帧给基座（完成桥不在路由外截流）"
      )

  test("腿① 帧匹配: agentEnd 经 agentId 键命中 ⇒ 完成桥（双键：nodeSessionId / agentId）"):
    for
      done <- Deferred[IO, Option[String]]
      bridge = SubagentPrimitives.spy(done, "subagent-prim-2", _ => IO.unit)
      _ <- bridge(
        Json.obj("type" -> "agentEnd".asJson, "agentId" -> "subagent-prim-2".asJson, "finalText" -> "END".asJson)
      )
      got <- done.tryGet
    yield assertEquals(got, Some(Some("END")), "agentEnd 经 agentId 命中必须完成桥（双键的第二键）")

  test("腿① 帧匹配: finalText 缺席 ⇒ 完成桥落 None（ended-without-final-text 面必须仍完成）"):
    for
      done <- Deferred[IO, Option[String]]
      bridge = SubagentPrimitives.spy(done, "subagent-prim-3", _ => IO.unit)
      _ <- bridge(Json.obj("type" -> "agentDone".asJson, "nodeSessionId" -> "subagent-prim-3".asJson))
      got <- done.tryGet
    yield assertEquals(got, Some(None), "finalText 缺席必须落 None（不是不落——否则父轮会挂死）")

  test("腿① 帧匹配: 非终态帧 / 他会话帧 ⇒ 不落完成（判据 = 类型命中 AND 归属命中）"):
    for
      done <- Deferred[IO, Option[String]]
      bridge = SubagentPrimitives.spy(done, "subagent-prim-4", _ => IO.unit)
      _ <- bridge(Json.obj("type" -> "agentStart".asJson, "nodeSessionId" -> "subagent-prim-4".asJson))
      _ <- bridge(
        Json.obj(
          "type" -> "agentDone".asJson,
          "nodeSessionId" -> "other-session".asJson,
          "finalText" -> "WRONG-CHILD".asJson
        )
      )
      got <- done.tryGet
    yield assertEquals(got, None, "非终态帧或他会话帧不得完成桥（类型与归属都必须命中）")

  test("腿① startSpy: 第一帧（agentStart 领衔）即落活性标记，且二帧幂等不炸"):
    for
      started <- Deferred[IO, Unit]
      forwarded <- IO.ref[List[Json]](Nil)
      spyFn = SubagentPrimitives.startSpy(started, json => forwarded.update(_ :+ json))
      _ <- spyFn(Json.obj("type" -> "agentStart".asJson, "agentId" -> "subagent-prim-5".asJson))
      marked <- started.tryGet
      _ <- spyFn(Json.obj("type" -> "agentDone".asJson))
      fwd <- forwarded.get
    yield
      assertEquals(marked, Some(()), "第一帧必须落活性标记（有活性事实才继续等）")
      assertEquals(fwd.size, 2, "两帧都必须原样转发（间谍只额外读「有帧」事实，零新协议）")

  // ── 腿② 预算耗尽（虚拟时钟；真实阈值 = startBudget）────────────────────────

  test(
    "腿② 预算耗尽: 预算窗零事件帧 ⇒ 判死 subagent-never-started + Stop + 补一帧面板（等满预算窗、不到 15min 硬顶）"
  ):
    val program =
      for
        started <- Deferred[IO, Unit] // 子会话永不出声（永不 complete = 零事件帧）
        done <- Deferred[IO, Option[String]]
        _ <- done.complete(Some("LATE-RESULT-NEVER-CONSUMED")) // 完成段不应被到达
        stops <- IO.ref[List[String]](Nil)
        panels <- IO.ref[Int](0)
        timed <- SubagentPrimitives
          .awaitResult(
            started = started,
            done = done,
            childId = "subagent-prim-budget",
            stopChild = reason => stops.update(_ :+ reason),
            emitPanelDone = panels.update(_ + 1)
          )
          .timed
        (elapsed, result) = timed
        s <- stops.get
        p <- panels.get
      yield (elapsed, result, s, p)
    TestControl.executeEmbed(program).map { case (elapsed, result, s, p) =>
      assert(result.isLeft, s"预算窗零帧必须判死，got $result")
      val err = result.left.toOption.map(_.message).getOrElse("")
      assert(
        err.contains(SubagentPrimitives.NeverStartedToken),
        s"结构化失败必须带 token '${SubagentPrimitives.NeverStartedToken}': $err"
      )
      assert(err.contains("subagent-prim-budget"), s"失败必须点名子会话: $err")
      assertEquals(s, List(SubagentPrimitives.NeverStartedToken), s"判死腿必须 Stop 子会话，got $s")
      assertEquals(p, 1, "判死腿必须补一帧面板终态")
      assert(
        elapsed >= SubagentPrimitives.startBudget,
        s"必须真等满预算窗（阈值逐字 = startBudget），elapsed=$elapsed"
      )
      assert(elapsed < SubagentPrimitives.Timeout, s"不得等到 15min 硬顶，elapsed=$elapsed")
    }

  // ── 腿③ 超时（虚拟时钟；真实阈值 = Timeout = 15min）────────────────────────

  test("腿③ 超时: 活性到场但完成窗内零完成帧 ⇒ 到 15min 硬顶报 'did not finish within' + Stop + 补帧"):
    val program =
      for
        started <- Deferred[IO, Unit]
        done <- Deferred[IO, Option[String]] // 活性到场后永不完成
        _ <- started.complete(())
        stops <- IO.ref[List[String]](Nil)
        panels <- IO.ref[Int](0)
        timed <- SubagentPrimitives
          .awaitResult(
            started = started,
            done = done,
            childId = "subagent-prim-timeout",
            stopChild = reason => stops.update(_ :+ reason),
            emitPanelDone = panels.update(_ + 1)
          )
          .timed
        (elapsed, result) = timed
        s <- stops.get
        p <- panels.get
      yield (elapsed, result, s, p)
    TestControl.executeEmbed(program).map { case (elapsed, result, s, p) =>
      assert(result.isLeft, s"完成窗内零完成帧必须超时返回 Left，got $result")
      val err = result.left.toOption.map(_.message).getOrElse("")
      assert(err.contains("did not finish within"), s"超时腿必须报 'did not finish within': $err")
      assertEquals(s, List("subagent-timeout"), s"超时腿必须 Stop 子会话，got $s")
      assertEquals(p, 1, "超时腿必须补一帧面板终态")
      assert(
        elapsed >= SubagentPrimitives.Timeout,
        s"必须等满 15min 硬顶（阈值逐字 = Timeout），elapsed=$elapsed"
      )
    }

  // ── 腿④ reap：四腿结算顺序 + fail-soft（真实 actor / registry / task store）──

  test("腿④ reap: 四腿结算顺序（task 终态化 → registry 移除 → ask 清理 → child Stop）"):
    val system = ActorSystem("sp-primitive-reap")
    val parentId = "delegate-sp-primitive"
    val childId = "subagent-prim-reap1"
    (for
      res <- SpecResources.mkResources(system, tempRoot, deadLlm)
      stopped <- IO.deferred[Unit]
      // child Stop 送达瞬间的对拍面：证明腿①② 已完成（Stop 是最后一腿）
      atStopStatus <- IO.ref[Option[String]](None)
      atStopInRegistry <- IO.ref[Option[Boolean]](None)
      _ <- res.subAgentTaskStore.recordTask(
        SubAgentTask(
          taskId = childId,
          parentSessionId = parentId,
          agentName = "subagent",
          prompt = "reap me",
          description = "reap",
          status = "running",
          retryCount = 0,
          spawnedAt = System.currentTimeMillis(),
          completedAt = None,
          lastError = None,
          source = "subagent"
        )
      )
      childRef <-
        def behavior: Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand] {
          case AgentCommand.Stop(_) =>
            (for
              tasks <- res.subAgentTaskStore.loadTasks(parentId)
              reg <- res.agentRegistry.get
              _ <- atStopStatus.set(tasks.find(_.taskId == childId).map(_.status))
              _ <- atStopInRegistry.set(Some(reg.contains(childId)))
              _ <- stopped.complete(()).attempt.void
            yield behavior)
          case _ => IO.pure(behavior)
        }
        system.spawn(behavior, childId)
      _ <- res.agentRegistry.update(
        _ + (childId -> AgentRecord(
          sessionId = childId,
          ref = childRef,
          kind = AgentKind.SubTask,
          rootSessionId = parentId,
          parentSessionId = parentId
        ))
      )
      _ <- SubagentPrimitives.reap(res, parentId, childId, childRef, completed = true)
      _ <- stopped.get.timeout(10.seconds)
      statusAtStop <- atStopStatus.get
      regAtStop <- atStopInRegistry.get
      finalTasks <- res.subAgentTaskStore.loadTasks(parentId)
      finalReg <- res.agentRegistry.get
    yield (statusAtStop, regAtStop, finalTasks, finalReg))
      .map { case (statusAtStop, regAtStop, finalTasks, finalReg) =>
        assertEquals(statusAtStop, Some("completed"), "child Stop 送达时 task 行必须已终态化（腿① 先于腿④）")
        assertEquals(regAtStop, Some(false), "child Stop 送达时 registry 条目必须已摘除（腿② 先于腿④）")
        val row = finalTasks.find(_.taskId == childId).getOrElse(fail("task row must exist"))
        assertEquals(row.status, "completed", "终态 = completed（completed=true）")
        assert(row.completedAt.isDefined, "completedAt 必须落")
        assert(!finalReg.contains(childId), "registry 条目必须摘除（getActiveAgents 契约恢复）")
      }
      .guarantee(system.stopAll.handleErrorWith(_ => IO.unit))

  test("腿④ reap fail-soft: 账目缺席（无 task 行 / 无 registry 条目）⇒ 不抛，仍按 cancelled 结算并 Stop"):
    val system = ActorSystem("sp-primitive-reap-soft")
    val parentId = "delegate-sp-primitive-soft"
    val childId = "subagent-prim-reap2"
    (for
      res <- SpecResources.mkResources(system, tempRoot, deadLlm)
      stopped <- IO.deferred[Unit]
      stopReason <- IO.ref[Option[String]](None)
      childRef <-
        def behavior: Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand] {
          case AgentCommand.Stop(reason) =>
            (stopReason.set(Some(reason)) *> stopped.complete(()).attempt.void).as(behavior)
          case _ => IO.pure(behavior)
        }
        system.spawn(behavior, childId)
      // 该 child 既无 task 行也无 registry 条目——两腿都是 miss，任何一腿都不许抛
      _ <- SubagentPrimitives.reap(res, parentId, childId, childRef, completed = false)
      _ <- stopped.get.timeout(10.seconds)
      reason <- stopReason.get
      finalReg <- res.agentRegistry.get
    yield (reason, finalReg))
      .map { case (reason, finalReg) =>
        assertEquals(
          reason,
          Some("subagent-terminal(cancelled)"),
          "缺失账目不得阻断结算——Stop 仍按 cancelled 旗标送达（fail-soft）"
        )
        assert(!finalReg.contains(childId), "无 registry 条目也不得抛")
      }
      .guarantee(system.stopAll.handleErrorWith(_ => IO.unit))

end SubagentPrimitiveSpec
