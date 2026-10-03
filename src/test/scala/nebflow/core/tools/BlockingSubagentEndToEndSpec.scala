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
 *  - Workflow（defName=executor，两步骤 DAG）：第二步吃到第一步 `finalText`
 *    的 `=== step a ===` 上游块 ⇒ 阻塞桥逐级完成。
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
    os.write.over(tempRoot / "nebflow.json", "{}")
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  /** 脚本化 LLM：记录每个会话收到的请求，并按其 sessionId 回文本。 */
  private class ScriptedLlm(script: String => String, captured: TrieMap[String, LlmRequest])
      extends LlmHandle[IO]:
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
        assert(captured.keys.exists(_.startsWith("subagent-")), s"the child session must have run a turn: ${captured.keys.toList}")
      }
      .guarantee(system.stopAll.handleErrorWith(_ => IO.unit))

  // ── Workflow：两步骤 DAG 逐级完成（上游块 = 上游 finalText）─────

  test("P0-2 Workflow: a two-step DAG completes — step b receives step a's final text under the upstream header"):
    val captured = TrieMap[String, LlmRequest]()
    val system = nebflow.actor.ActorSystem("bse2e-wf")
    val llm = new ScriptedLlm(sid => "STEP-" + sid, captured)
    (for
      parent <- dummyParent(system)
      res <- SpecResources.mkResources(system, tempRoot, llm)
      out <- WorkflowTool.call(
        JsonObject.fromIterable(
          List(
            "description" -> "e2e".asJson,
            "steps" -> io.circe.Json.arr(
              io.circe.Json.obj("id" -> "a".asJson, "task" -> "do A".asJson),
              io.circe.Json.obj("id" -> "b".asJson, "task" -> "do B".asJson, "deps" -> io.circe.Json.arr("a".asJson))
            )
          )
        ),
        ctxFor(system, res, parent)
      )
    yield out)
      .map { out =>
        assert(out.isRight, s"the workflow must complete end-to-end: $out")
        val text = out.getOrElse("")
        assert(text.contains("step a"), s"step a must land in the aggregated result: $text")
        assert(text.contains("step b"), s"step b must land in the aggregated result: $text")
        // 逐级交付证明：步骤 b 的 prompt 必带步骤 a 的 finalText（=== step a === 块）——
        // 该块只能来自阻塞桥读到的 finalText。
        val bReq = captured.values.toList.flatMap(_.messages.lastOption).map(_.content).mkString("\n")
        assert(
          captured.size >= 2,
          s"both steps must actually run a turn (finalText produced), got ${captured.size}: ${captured.keys.toList}"
        )
        assert(
          bReq.contains("=== step a ==="),
          s"step b's prompt must carry step a's finalText under the upstream header (finalText is the carrier): $bReq"
        )
      }
      .guarantee(system.stopAll.handleErrorWith(_ => IO.unit))

  // ── 生产者排他钉（机器可查验收点 3）──────────────────────────

  test("P0-2 producer pin: `grep '\"finalText\"' src/main/scala` = producer + reader (exactly 2)"):
    val src = List(
      os.read(repoRoot / "src" / "main" / "scala" / "nebflow" / "actor" / "AgentEvent.scala"),
      os.read(repoRoot / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "BlockingSubagent.scala")
    )
    val hits = src.map(_.linesIterator.count(_.contains("\"finalText\""))).sum
    assertEquals(hits, 2, s"the producer (AgentEvent) + the reader (BlockingSubagent) must be the ONLY two `\"finalText\"` hits in main, got $hits")
    assert(
      os.read(repoRoot / "src" / "main" / "scala" / "nebflow" / "actor" / "AgentEvent.scala").contains("\"finalText\" -> ft.asJson"),
      "the producer (AgentEvent.toJson) must be present"
    )

end BlockingSubagentEndToEndSpec
