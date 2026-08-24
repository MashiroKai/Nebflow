package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.*
import nebflow.core.PathUtil
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{Message, MessageRole}

import scala.concurrent.duration.*

/**
 * #317 断点续跑：BackoffSupervisor 重启子 agent 时从磁盘恢复崩溃前消息，
 * 而非重注入原始 prompt（丢掉工具结果+重新烧全量 token）。
 *
 * 本测试钉死两个契约：
 * 1. 重启时 childSpawnFn 收到从 sessionStore 加载的 recovered messages
 * 2. recovered messages 非空 → 发 "continue" 指令（而非原始 prompt）
 *    recovered messages 为空 → 发原始 prompt（fallback）
 */
class CrashResumeSpec extends CatsEffectSuite:

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = null,
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

  /** Poll until a condition is met or timeout expires. */
  private def waitUntil(timeout: FiniteDuration)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new RuntimeException(s"waitUntil: condition not met in $timeout"))
          else IO.sleep(100.millis) *> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** A child actor that immediately stops (simulates crash) on first message. */
  private def crashingChild: Behavior[AgentCommand] =
    Behaviors.receiveMessage[AgentCommand] {
      case AgentCommand.UserInput(_, _, _, _, _, _, _, _, _, _) =>
        IO.pure(Behaviors.stopped[AgentCommand])
      case _ => IO.pure(Behaviors.stopped[AgentCommand])
    }

  test("restart recovers persisted messages and sends 'continue' instruction") {
    val system = ActorSystem("crash-resume-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        resources <- mkResources(system, tmp)
        childSid = "crash-resume-child"
        parentSid = "crash-resume-parent"

        // Persist some messages to the session store — simulating a child that
        // ran a few turns before crashing.
        persistedMsgs = List(
          Message(MessageRole.User, Left("original task prompt")),
          Message(MessageRole.Assistant, Left("I'm working on it...")),
          Message(MessageRole.User, Left("[tool result] file content here"))
        )
        _ <- resources.sessionStore.saveMessagesForSession(childSid, persistedMsgs)

        // Track what childSpawnFn receives and what UserInput the new child gets.
        spawnReceived <- IO.ref(List.empty[List[Message]])
        inputReceived <- IO.ref(List.empty[String])
        firstChild <- system.spawn(crashingChild, childSid)

        supervisor <- system.spawn(
          BackoffSupervisor(
            childRef = firstChild,
            childSpawnFn = (_, recovered) =>
              spawnReceived.update(_ :+ recovered) *>
                system.spawn(
                  Behaviors.receiveMessage[AgentCommand] {
                    case AgentCommand.UserInput(text, _, _, _, _, _, _, _, _, _) =>
                      inputReceived.update(_ :+ text).as(Behaviors.stopped[AgentCommand])
                    case _ => IO.pure(Behaviors.stopped[AgentCommand])
                  },
                  childSid
                ),
            childName = childSid,
            parentRef = None,
            description = "crash resume test",
            agentName = "Worker",
            subagentId = childSid,
            parentSessionId = parentSid,
            resources = resources,
            initialPrompt = "original task prompt",
            source = "delegate",
            minBackoff = 50.millis,
            maxBackoff = 200.millis,
            maxRestarts = 1
          ),
          s"$childSid-adapter"
        )

        // Trigger crash: send a UserInput to the first child, which immediately stops.
        _ <- firstChild ! AgentCommand.UserInput("trigger crash", Some(supervisor))
        // Wait for supervisor to detect Terminated, load messages, respawn, and
        // send the "continue" instruction. The supervisor's backoff includes
        // up to 1s jitter, so we poll until childSpawnFn is called and the
        // new child receives its UserInput.
        _ <- waitUntil(10.seconds)(inputReceived.get.map(_.nonEmpty))

        spawnMsgs <- spawnReceived.get
        inputs <- inputReceived.get
      yield (spawnMsgs, inputs)

      val (spawnMsgs, inputs) = program.unsafeRunSync()
      // childSpawnFn must have received the persisted messages
      assertEquals(spawnMsgs.size, 1, "childSpawnFn should be called exactly once")
      assertEquals(spawnMsgs.head.size, 3, "should recover all 3 persisted messages")
      assertEquals(spawnMsgs.head.head.textContent, "original task prompt")

      // The new child must receive a "continue" instruction, not the original prompt
      assertEquals(inputs.size, 1, "new child should receive exactly one UserInput")
      assert(inputs.head.contains("continue"), s"expected 'continue' instruction, got: ${inputs.head}")
      assert(!inputs.head.equals("original task prompt"), "must not re-inject original prompt when messages recovered")
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("restart falls back to original prompt when no messages persisted") {
    val system = ActorSystem("crash-resume-fallback-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        resources <- mkResources(system, tmp)
        childSid = "crash-fallback-child"
        parentSid = "crash-fallback-parent"

        // No messages persisted — simulating a crash before any turn completed.
        spawnReceived <- IO.ref(List.empty[List[Message]])
        inputReceived <- IO.ref(List.empty[String])
        firstChild <- system.spawn(crashingChild, childSid)

        supervisor <- system.spawn(
          BackoffSupervisor(
            childRef = firstChild,
            childSpawnFn = (_, recovered) =>
              spawnReceived.update(_ :+ recovered) *>
                system.spawn(
                  Behaviors.receiveMessage[AgentCommand] {
                    case AgentCommand.UserInput(text, _, _, _, _, _, _, _, _, _) =>
                      inputReceived.update(_ :+ text).as(Behaviors.stopped[AgentCommand])
                    case _ => IO.pure(Behaviors.stopped[AgentCommand])
                  },
                  childSid
                ),
            childName = childSid,
            parentRef = None,
            description = "crash fallback test",
            agentName = "Worker",
            subagentId = childSid,
            parentSessionId = parentSid,
            resources = resources,
            initialPrompt = "the original prompt",
            source = "subtask",
            minBackoff = 50.millis,
            maxBackoff = 200.millis,
            maxRestarts = 1
          ),
          s"$childSid-adapter"
        )

        _ <- firstChild ! AgentCommand.UserInput("trigger crash", Some(supervisor))
        _ <- waitUntil(10.seconds)(inputReceived.get.map(_.nonEmpty))

        spawnMsgs <- spawnReceived.get
        inputs <- inputReceived.get
      yield (spawnMsgs, inputs)

      val (spawnMsgs, inputs) = program.unsafeRunSync()
      assertEquals(spawnMsgs.size, 1)
      assertEquals(spawnMsgs.head.size, 0, "should recover 0 messages when nothing persisted")

      assertEquals(inputs.size, 1)
      assertEquals(inputs.head, "the original prompt", "must fall back to original prompt")
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

end CrashResumeSpec
