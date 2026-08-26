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
import nebflow.shared.{ContentBlock, Message, MessageRole}

import scala.concurrent.duration.*

/**
 * 空壳通知回归测试（message-truncation-report.md 方案 A）：
 * 子 agent 最后一条 assistant 消息为纯空白（"\n\n"）时，
 * 完成通知 payload 必须是 "(no text output)"，而非 `"描述":\n\n\n` 空壳形态。
 *
 * 修复前：text.nonEmpty 判断让 "\n\n" 溜过 → 空壳 payload
 * 修复后：text.trim.nonEmpty → 走 "(no text output)" 分支
 */
class EmptyShellNotifySpec extends CatsEffectSuite:

  /** A child that replies Completed with the given messages, replying to whoever asked. */
  private def completingChild(messages: List[Message]): Behavior[AgentCommand] =
    Behaviors.receiveMessage[AgentCommand] {
      case AgentCommand.UserInput(_, replyTo, _, _, _, _, _, _, _, _) =>
        // NOTE: `!` returns IO[Unit] (the send action) — it MUST be chained
        // into the returned IO, not discarded by foreach.
        val send = replyTo.fold(IO.unit)(ref => ref ! AgentEvent.Completed("", messages))
        send *> IO.pure(Behaviors.stopped[AgentCommand])
      case _ => IO.pure(Behaviors.stopped[AgentCommand])
    }

  /** Spy parent that captures ExternalEvent payloads (loops on itself). */
  private def spyParent(captured: cats.effect.Ref[IO, List[String]]): Behavior[AgentCommand] =
    Behaviors.receiveMessage[AgentCommand] {
      case AgentCommand.ExternalEvent(_, _, payload, _, _) =>
        captured.update(_ :+ payload).as(spyParent(captured))
      case _ => IO.pure(spyParent(captured))
    }

  private def waitUntil(timeout: FiniteDuration)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new RuntimeException(s"waitUntil: condition not met in $timeout"))
          else IO.sleep(50.millis) *> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

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

  /** Run one Completed round-trip: child replies Completed(messages) to the
    * supervisor; returns the payload the spy parent captured. */
  private def runCompletedPayload(
      testName: String,
      messages: List[Message]
  ): String =
    val system = ActorSystem(s"empty-shell-$testName")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val captured = cats.effect.Ref.unsafe[IO, List[String]](Nil)
      val program = for
        resources <- mkResources(system, tmp)
        parent <- system.spawn(spyParent(captured), s"$testName-parent")
        child <- system.spawn(completingChild(messages), s"$testName-child")
        supervisor <- system.spawn(
          BackoffSupervisor(
            childRef = child,
            childSpawnFn = (_, _) => system.spawn(completingChild(messages), s"$testName-child-r"),
            childName = s"$testName-child",
            parentRef = Some(parent),
            description = "empty shell test",
            agentName = "Worker",
            subagentId = s"$testName-child",
            parentSessionId = s"$testName-parent-session",
            resources = resources,
            initialPrompt = "ignored",
            source = "delegate"
          ),
          s"$testName-supervisor"
        )
        // Trigger: child gets UserInput with replyTo = supervisor, replies Completed.
        _ <- child ! AgentCommand.UserInput("go", Some(supervisor))
        // 20s (was 5s): under full-suite parallel load the actor round-trip
        // (UserInput → Completed → supervisor forward) can exceed 5s of CPU
        // contention — known flake, green path returns in <1s so the longer
        // ceiling costs nothing unloaded. Kept below munit's 30s test timeout.
        _ <- waitUntil(20.seconds)(captured.get.map(_.nonEmpty))
        payloads <- captured.get
      yield payloads.head

      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

  test("whitespace-only tail produces '(no text output)', not an empty shell") {
    // Last assistant message: thinking block + whitespace-only text ("\n\n")
    val messages = List(
      Message(MessageRole.User, Left("do the thing")),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.Thinking("analysis..."), ContentBlock.Text("\n\n")))
      )
    )
    val payload = runCompletedPayload("ws", messages)
    assert(payload.contains("(no text output)"), s"expected '(no text output)' marker, got: ${payload.take(80)}")
    assert(!payload.trim.endsWith(":"), s"payload must not end with bare colon (empty shell), got: ${payload.take(80)}")
  }

  test("normal text tail still produces '\"desc\":\\n<text>' form") {
    val messages = List(
      Message(MessageRole.User, Left("do the thing")),
      Message(MessageRole.Assistant, Left("done, result is 42"))
    )
    val payload = runCompletedPayload("ok", messages)
    assert(payload.contains("done, result is 42"), s"normal text must survive, got: ${payload.take(80)}")
    assert(!payload.contains("(no text output)"), "normal text must not hit the no-output branch")
  }

end EmptyShellNotifySpec
