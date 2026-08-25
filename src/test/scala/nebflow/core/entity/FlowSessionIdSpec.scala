package nebflow.core.entity

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.{NodeStatus, RunningFlowRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, StreamChunk}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * #412 flow session ownership — sessionId (triggering agent) + rootSessionId
 * (outermost root) flow from execute → registerFlow → RunningFlow registry,
 * and surface on the flowStarted / flowCompleted WS events and toJson.
 *
 * #407 gate contract is preserved: RunningFlow.sessionId stays the TRIGGERING
 * agent's own session (MailIdleGate does f.sessionId.contains(sid)); the root
 * is carried in the separate rootSessionId field.
 */
class FlowSessionIdSpec extends CatsEffectSuite:

  private def answer(text: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(text), StreamChunk.Done(None, None))

  /** Every node answers plain text — single-entry flow returns immediately. */
  private val plainLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      answer("DONE")

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
    val system = ActorSystem(s"sid-${UUID.randomUUID().toString.take(6)}")
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

  /** n1 → $return — the minimal DAG. */
  private def sidFlow: FlowDagDef =
    FlowDagDef(
      name = "tflow",
      description = "session id test",
      nodes = Map(
        "n1" -> FlowNode("worker", "$task", NodeRoute.Return)
      ),
      entry = "n1"
    )

  test("#412 flowStarted/flowCompleted carry sessionId (=caller) + rootSessionId; registry + toJson set"):
    val wsEvents: Ref[IO, List[Json]] = Ref.unsafe(Nil)
    val caller = s"caller-${UUID.randomUUID().toString.take(8)}"
    val root = s"root-${UUID.randomUUID().toString.take(8)}"
    withFlowEnv(plainLlm) { (resources, system) =>
      val instId = s"sid-${UUID.randomUUID().toString.take(6)}"
      val wsSend: Json => IO[Unit] = j => wsEvents.update(_ :+ j)
      FlowDagExecutor
        .execute(
          sidFlow,
          "work",
          resources,
          system,
          Some(wsSend),
          instId,
          rootSessionId = root,
          callerSessionId = caller
        )
        .timeout(30.seconds)
        .flatMap { result =>
          wsEvents.get.flatMap { ws =>
            RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).map { rfOpt =>
              assert(result.isRight, s"flow must complete, got: $result")
              val started = ws
                .find(_.hcursor.downField("type").as[String].contains("flowStarted"))
                .getOrElse(fail("flowStarted event missing"))
              assertEquals(started.hcursor.downField("sessionId").as[String], Right(caller))
              assertEquals(started.hcursor.downField("rootSessionId").as[String], Right(root))
              val completed = ws
                .find(_.hcursor.downField("type").as[String].contains("flowCompleted"))
                .getOrElse(fail("flowCompleted event missing"))
              assertEquals(completed.hcursor.downField("sessionId").as[String], Right(caller))
              assertEquals(completed.hcursor.downField("rootSessionId").as[String], Right(root))
              val rf = rfOpt.getOrElse(fail("running flow entry missing"))
              // #407 gate semantics: sessionId = triggering agent's own session
              assertEquals(rf.sessionId, Some(caller), "#407: sessionId must stay the triggering agent's session")
              assertEquals(rf.rootSessionId, Some(root), "#412: rootSessionId carries the outermost root")
              // toJson serialization includes both ownership fields
              val json = RunningFlowRegistry.toJson(rf).asJson
              assertEquals(json.hcursor.downField("sessionId").as[Option[String]], Right(Some(caller)))
              assertEquals(json.hcursor.downField("rootSessionId").as[Option[String]], Right(Some(root)))
            }
          }
        }
    }

  test("#412 empty ownership → registry None + events carry empty strings (backward compat)"):
    val wsEvents: Ref[IO, List[Json]] = Ref.unsafe(Nil)
    withFlowEnv(plainLlm) { (resources, system) =>
      val instId = s"sid-${UUID.randomUUID().toString.take(6)}"
      val wsSend: Json => IO[Unit] = j => wsEvents.update(_ :+ j)
      FlowDagExecutor
        .execute(sidFlow, "work", resources, system, Some(wsSend), instId)
        .timeout(30.seconds)
        .flatMap { result =>
          wsEvents.get.flatMap { ws =>
            RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).map { rfOpt =>
              assert(result.isRight)
              val started = ws
                .find(_.hcursor.downField("type").as[String].contains("flowStarted"))
                .getOrElse(fail("flowStarted event missing"))
              assertEquals(started.hcursor.downField("sessionId").as[String], Right(""))
              assertEquals(started.hcursor.downField("rootSessionId").as[String], Right(""))
              val rf = rfOpt.getOrElse(fail("running flow entry missing"))
              assertEquals(rf.sessionId, None, "no caller → None (gate association inert)")
              assertEquals(rf.rootSessionId, None)
            }
          }
        }
    }

end FlowSessionIdSpec
