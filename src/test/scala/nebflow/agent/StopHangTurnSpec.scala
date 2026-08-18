package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.core.PathUtil
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * P0 阶段3 冒烟复现（2026-08-18）：TaskStuckWatcher 发 Stop 给挂起 LLM 流的
 * 子 agent，预期 actor 终止 → BackoffSupervisor 收到 Terminated → 退避重启 →
 * 重启后请求正常完成。冒烟实测 Stop 后无重启（restartLog=0），本测试在
 * 最小环境复现并钉死该链路。
 */
class StopHangTurnSpec extends CatsEffectSuite:

  /** 首请求永久挂起，后续请求正常回复——模拟 mock-stuck 的 stuck 语义。 */
  private class StuckThenOkLlm(counter: cats.effect.Ref[IO, Int]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1)) >> Stream.eval(counter.get).flatMap { n =>
        if n == 1 then Stream.never[IO]
        else Stream(StreamChunk.TextDelta(s"reply #$n"), StreamChunk.Done(None, None))
      }

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
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  test("Stop pierces a hung LLM turn: BackoffSupervisor restarts the child and it completes") {
    val system = ActorSystem("stop-hang-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        llm = new StuckThenOkLlm(counter)
        resources <- mkResources(system, tmp, llm)
        parentEvents <- IO.ref(List.empty[AgentCommand])
        parentRef <- system.spawn(
          {
            def loop: Behavior[AgentCommand] =
              Behaviors.receiveMessage[AgentCommand](cmd => parentEvents.update(_ :+ cmd).as(loop))
            loop
          },
          "parent-rec"
        )
        nebulaDef = AgentDef(
          name = "Nebula",
          description = "root",
          tools = List("Read"),
          systemPrompt = ""
        )
        subagentId = "stop-hang-delegate"
        childRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 1,
            parentRef = Some(parentRef),
            sessionId = Some(subagentId),
            sessionName = Some("hang sub"),
            initialMessages = Nil
          ),
          subagentId
        )
        _ <- resources.agentRegistry.update(
          _ + (
            subagentId -> AgentRecord(
              subagentId,
              childRef,
              AgentKind.Delegate,
              "root-session",
              Some(parentRef)
            )
          )
        )
        _ <- system.spawn(
          BackoffSupervisor(
            childRef = childRef,
            childSpawnFn = (sys: ActorSystem) =>
              sys.spawn(
                AgentActor(
                  agentDef = nebulaDef,
                  resources = resources,
                  wsSend = _ => IO.unit,
                  depth = 1,
                  parentRef = Some(parentRef),
                  sessionId = Some(subagentId),
                  sessionName = Some("hang sub"),
                  initialMessages = Nil
                ),
                subagentId
              ),
            childName = subagentId,
            parentRef = Some(parentRef),
            description = "hang sub",
            agentName = "Nebula",
            subagentId = subagentId,
            parentSessionId = "root-session",
            resources = resources,
            initialPrompt = "hang",
            source = "delegate",
            minBackoff = 100.millis,
            maxBackoff = 500.millis,
            maxRestarts = 2
          ),
          s"$subagentId-adapter"
        )
        _ <- childRef ! AgentCommand.UserInput("start the hung turn")
        _ <- IO.sleep(500.millis) // 首请求挂起中（counter==1）
        firstCount <- counter.get
        _ = assert(firstCount == 1, s"expected the hung first request, got count=$firstCount")
        _ <- childRef ! AgentCommand.Stop("stuck-task-test")
        // Stop → cancelCurrentTurn（杀挂起流）→ Behaviors.stopped → Terminated →
        // supervisor 重启 → 第二次请求（counter==2）正常回复 → child 完成 → parent 收 Completed
        stopResult <- {
          def go(deadline: Long): IO[Unit] =
            counter.get.flatMap { n =>
              parentEvents.get.flatMap { evs =>
                if n >= 2 && evs.exists {
                  case AgentCommand.ExternalEvent(_, eventType, _, _, _) => eventType == "completed"
                  case _ => false
                } then IO.unit
                else if System.currentTimeMillis() > deadline then
                  Thread
                    .getAllStackTraces()
                    .entrySet()
                    .toArray
                    .take(8)
                    .foreach { e =>
                      val entry = e.asInstanceOf[java.util.Map.Entry[Thread, Array[StackTraceElement]]]
                      println(
                        s"THREAD ${entry.getKey.getName}: ${entry.getValue.take(6).mkString(" <- ")}"
                      )
                    }
                  IO.raiseError(
                    new RuntimeException(
                      s"Stop did not restart+complete the child: count=$n parentEvents=${evs.map(_.getClass.getSimpleName)}"
                    )
                  )
                else IO.sleep(200.millis) *> go(deadline)
              }
            }
          go(System.currentTimeMillis() + 5000L).attempt
        }
        // 诊断对比：Stop 消息未生效时，system.stop 直接终止能否触发 supervisor 重启
        _ <- stopResult match
          case Right(_) => IO.unit
          case Left(_) =>
            system.stop(childRef) *> {
              def go(deadline: Long): IO[Unit] =
                counter.get.flatMap { n =>
                  if n >= 2 then IO.unit
                  else if System.currentTimeMillis() > deadline then
                    IO.raiseError(
                      new RuntimeException(s"system.stop also failed to trigger restart: count=$n")
                    )
                  else IO.sleep(200.millis) *> go(deadline)
                }
              go(System.currentTimeMillis() + 5000L)
            }
        _ <- IO.sleep(300.millis)
        finalCount <- counter.get
      yield assert(finalCount >= 2, s"child must be restarted at least once, count=$finalCount")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

end StopHangTurnSpec