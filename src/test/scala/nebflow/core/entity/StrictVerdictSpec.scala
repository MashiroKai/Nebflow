package nebflow.core.entity

import cats.effect.kernel.Outcome
import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentDef, FlowNodeContract, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.NodeStatus
import nebflow.core.flow.RunningFlowRegistry
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, FlowReportStore, FlowReportData, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, ToolCall}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * R8-P1 structured verdict contract (2026-09-06 口径更新):
 *
 *  Engine level — strictVerdict flows:
 *   - switch node ending WITHOUT a verdict fails the node with an
 *     error listing the expected case keys (second line of defense);
 *   - Switch.lenient=true re-enables the legacy guess pipeline per switch;
 *   - strictVerdict=false (default) keeps legacy behavior unchanged;
 *   - a stored verdict routes the switch; slots flow downstream via
 *     $<nodeId>.slots.<field>.
 *
 *  2026-09-06 工具面裁撤批：FlowReportTool（工具层 contract 验证 + store 写入）
 *  已退役删除——fakes 改为直写 [[FlowReportStore]]（引擎消费面零变化），原
 *  tool-level 5 条 tests 与「invalid verdict → ToolError → self-corrects」
 *  随工具退役（被测对象不复存在）。FlowNodeContract.describe 的产出语义仍由
 *  下面的 describe test 锁定。
 */
class StrictVerdictSpec extends CatsEffectSuite:

  test("contract describe block lists verdict enum and slot schema") {
    val text = FlowNodeContract(caseKeys = Set("pass", "revise"), slots = Map("topics" -> "string")).describe
    assert(text.contains("pass | revise"), s"enum listed: $text")
    assert(text.contains("topics (string)"), s"slot schema listed: $text")
  }

  // ==========================================================
  // Executor level — strict verdict semantics
  // ==========================================================

  /** Answers with plain text after `delay` — no tool calls. */
  private def answerAfter(delay: FiniteDuration, text: String): Stream[IO, StreamChunk] =
    Stream.eval(IO.sleep(delay)) >> Stream(StreamChunk.TextDelta(text), StreamChunk.Done(None, None))

  /** Plain-text answerer — never reports a verdict. */
  private class PlainTextLlm(text: String, delay: FiniteDuration = 50.millis) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] = answerAfter(delay, text)

  /**
   * Verdict reporter: on the session's first request, writes the verdict +
   * slots straight into FlowReportStore (the post-retirement stand-in for the
   * retired FlowReport tool — the engine's consumption path is unchanged),
   * then answers plain text so the node completes.
   */
  private class StoreReportingLlm(verdict: String, slots: Json, capture: Ref[IO, Map[String, List[Message]]])
      extends LlmHandle[IO]:
    private val reported = Ref.unsafe[IO, Set[String]](Set.empty)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(capture.update(m => m.updated(req.sessionId, req.messages))) >>
        Stream.eval(reported.get.flatMap { seen =>
          if !seen(req.sessionId) then
            reported.update(_ + req.sessionId) *>
              FlowReportStore.set(
                req.sessionId,
                FlowReportData(verdict, "reported output", slots.asObject.getOrElse(io.circe.JsonObject.empty))
              )
          else IO.unit
        }) >>
        Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new nebflow.agent.SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def withFlowEnv[A](llm: LlmHandle[IO])(
    test: (SharedResources, ActorSystem) => IO[A]
  ): IO[A] =
    val system = ActorSystem(s"strict-verdict-test-${UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val dataRoot = tmp / "data"
    IO.delay {
      os.makeDir.all(dataRoot / "flows" / "tflow" / "agents" / "worker")
      os.write(
        dataRoot / "flows" / "tflow" / "agents" / "worker" / "agent.json",
        """{"name":"worker","description":"test worker","tools":["Read"]}"""
      )
      PathUtil.setDataRoot(dataRoot)
    }.bracket { _ =>
      mkResources(system, tmp, llm).flatMap(test(_, system))
    } { _ =>
      IO.delay(PathUtil.setDataRoot(prevRoot)) *>
        system.stopAll.attempt.void *>
        IO.delay(if os.exists(tmp) then os.remove.all(tmp)).attempt.void
    }

  /** n1 (switch pass→n2 / fail→$return) → n2 → $return */
  private def switchFlow(strict: Boolean, lenient: Boolean, n2Input: String = "second: $task"): FlowDagDef =
    FlowDagDef(
      name = "tflow",
      description = "test",
      nodes = Map(
        "n1" -> FlowNode(
          agent = "worker",
          input = "$task",
          onComplete = NodeRoute.Switch(
            "$n1.verdict",
            Map("pass" -> NodeRoute.Goto("n2"), "fail" -> NodeRoute.Return),
            None,
            lenient
          )
        ),
        "n2" -> FlowNode("worker", n2Input, NodeRoute.Return)
      ),
      entry = "n1",
      strictVerdict = strict
    )

  test("strict flow: switch node without verdict fails with expected keys") {
    val llm = new PlainTextLlm("I think this passes: VERDICT: pass")
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"sv1-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(switchFlow(strict = true, lenient = false), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).map { rfOpt =>
            assert(result.isLeft, s"strict flow without verdict must fail, got: $result")
            val err = result.swap.toOption.get
            assert(err.contains("no FlowReport verdict"), s"error names the missing verdict: $err")
            assert(err.contains("Expected verdict one of:"), s"error lists expected keys: $err")
            assert(err.contains("pass"), s"error mentions 'pass': $err")
            assertEquals(
              rfOpt.flatMap(_.nodes.get("n1").map(_.status)),
              Some(NodeStatus.Failed),
              "node failed (second line of defense)"
            )
          }
        }
    }
  }

  test("strict flow + lenient switch: legacy guess pipeline still routes") {
    val llm = new PlainTextLlm("analysis...\nVERDICT: pass")
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"sv2-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(switchFlow(strict = true, lenient = true), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .map { result =>
          assert(result.isRight, s"lenient switch must allow the legacy guess: $result")
        }
    }
  }

  test("strictVerdict=false (default): legacy behavior unchanged — text verdict still routes") {
    val llm = new PlainTextLlm("analysis...\nVERDICT: pass")
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"sv3-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(switchFlow(strict = false, lenient = false), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .map { result =>
          assert(result.isRight, s"legacy flows keep the guess pipeline: $result")
        }
    }
  }

  test("strict flow: stored verdict routes the switch and slots resolve downstream") {
    val slots = Json.obj("topics" -> "quantum dots; perovskite; SiC".asJson)
    val capture = Ref.unsafe[IO, Map[String, List[Message]]](Map.empty)
    val llm = new StoreReportingLlm("pass", slots, capture)
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"sv4-${UUID.randomUUID().toString.take(6)}"
      // n1 declares a topics slot; n2's input template references it
      val flow = switchFlow(
        strict = true,
        lenient = false,
        n2Input = "subtopics: $n1.slots.topics"
      ).copy(
        nodes = Map(
          "n1" -> switchFlow(strict = true, lenient = false).nodes("n1").copy(outputs = Map("topics" -> "string")),
          "n2" -> FlowNode("worker", "subtopics: $n1.slots.topics", NodeRoute.Return)
        )
      )
      FlowDagExecutor
        .execute(flow, "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          capture.get.map { captured =>
            assert(result.isRight, s"valid verdict must route pass→n2: $result")
            // n2's LLM request must contain the RESOLVED slot value, not the template
            val n2Msgs: List[Message] =
              captured.toList.collect { case (sid, msgs) if sid.contains("n2") => msgs }.flatten
            val userTexts = n2Msgs
              .filter(_.role == MessageRole.User)
              .map(m => m.content.fold(t => t, blocks => blocks.collect { case ContentBlock.Text(t) => t }.mkString))
              .mkString("\n")
            assert(
              userTexts.contains("quantum dots; perovskite; SiC"),
              s"n2 must receive resolved $$n1 slots value, got: $userTexts"
            )
            assert(!userTexts.contains("$n1.slots.topics"), "template must be resolved, not passed raw")
          }
        }
    }
  }

end StrictVerdictSpec
