package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.*
import nebflow.core.FileChangeTracker
import nebflow.core.mail.SessionMailbox
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.{DelegateJudge, DelegateRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{ContentBlock, Message, MessageRole, PathUtil, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 空壳通知回归测试（message-truncation-report.md 方案 A ＋ 四修① 2026-10-07 更订
 * ＋ P5 静默完成治理第二波更新）：
 *
 *  - 空白且无工具调用的末轮经过重试预算后明确失败；历史轮次的工具调用不豁免末轮。
 *  - 正常文本尾 → `"描述":\n<text>` 形态（原契约不变）。
 */
class EmptyShellNotifySpec extends CatsEffectSuite:

  /** A child that replies Completed with the given messages, replying to whoever asked. */
  private def completingChild(messages: List[Message]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand] {
        case AgentCommand.UserInput(_, replyTo, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
          // NOTE: `!` returns IO[Unit] (the send action) — it MUST be chained
          // into the returned IO, not discarded by foreach.
          val send = replyTo.fold(IO.unit)(ref => ref ! AgentEvent.Completed("", messages))
          send *> IO.pure(loop)
        case _ => IO.pure(loop)
      }
    loop

  /** Spy parent that captures ExternalEvent payloads (loops on itself). */
  private def spyParent(captured: cats.effect.Ref[IO, List[String]]): Behavior[AgentCommand] =
    Behaviors.receiveMessage[AgentCommand] {
      case AgentCommand.ExternalEvent(_, _, payload, _, _, _) =>
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

  /**
   * Run one Completed round-trip: child replies Completed(messages) to the
   * supervisor; returns the payload the spy parent captured.
   */
  private def runCompletedPayload(
    testName: String,
    messages: List[Message],
    eventType: String = "completed"
  ): String =
    val system = ActorSystem(s"empty-shell-$testName")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val captured = cats.effect.Ref.unsafe[IO, List[String]](Nil)
      val program = for
        resources <- mkResources(system, tmp)
        _ <- IO {
          DelegateJudge.DelegateJudgePort.clear()
          DelegateRegistry.put(
            DelegateRegistry.Record(
              id = s"$testName-child",
              executor = "nebflow",
              cwd = os.pwd.toString,
              project = None,
              taskId = None,
              parentSessionId = s"$testName-parent-session",
              title = "empty shell test",
              createdAt = System.currentTimeMillis(),
              reportStatus = Some("finish")
            )
          )
        }
        _ <- resources.sessionStore.getOrCreateSession(s"$testName-parent-session", "parent")
        _ <- resources.sessionStore.getOrCreateSession(s"$testName-child", "child")
        _ <- resources.subAgentTaskStore.recordTask(
          SubAgentTask(
            taskId = s"$testName-child",
            parentSessionId = s"$testName-parent-session",
            agentName = "Worker",
            prompt = "ignored",
            description = "empty shell test",
            status = "running",
            retryCount = 0,
            spawnedAt = System.currentTimeMillis(),
            completedAt = None,
            lastError = None,
            source = "delegate"
          )
        )
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
            source = "delegate",
            minBackoff = 50.millis,
            maxBackoff = 100.millis
          ),
          s"$testName-supervisor"
        )
        // Trigger: child gets UserInput with replyTo = supervisor, replies Completed.
        _ <- child ! AgentCommand.UserInput("go", Some(supervisor))
        // Delegate terminal delivery moved from parentRef push to the parent's durable
        // SessionMailbox. Wait on the authoritative event row, not the legacy actor spy.
        _ <- waitUntil(20.seconds)(
          SessionMailbox
            .scanPending(s"$testName-parent-session")
            .map(
              _.exists(_.event.eventType == eventType)
            )
        )
        events <- SessionMailbox.scanPending(s"$testName-parent-session")
      yield events.find(_.event.eventType == eventType).get.event.payload

      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

    end try

  end runCompletedPayload

  test(
    "whitespace-only final turn exhausts retries and delivers explicit failure"
  ) {
    // Last assistant message: thinking block + whitespace-only text ("\n\n")
    val messages = List(
      Message(MessageRole.User, Left("do the thing")),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.Thinking("analysis..."), ContentBlock.Text("\n\n")))
      )
    )
    val payload = runCompletedPayload("ws", messages, "failed")
    assert(payload.contains("empty-final-turn"), s"explicit empty-turn failure required: $payload")
    assert(
      !payload.contains("[delegate-judge] verdict: blocked"),
      "blocked 判词已按 #566 退役 —— delegate 面不得再产出 blocked 终态"
    )
  }

  test(
    "prior tool use does not exempt a whitespace-only final turn from failure"
  ) {
    val messages = List(
      Message(MessageRole.User, Left("do the thing")),
      Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse("t1", "Bash", io.circe.JsonObject.empty)))),
      Message(MessageRole.User, Right(List(ContentBlock.ToolResult("t1", "ok")))),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.Thinking("analysis..."), ContentBlock.Text("\n\n")))
      )
    )
    val payload = runCompletedPayload("ws2", messages, "failed")
    assert(payload.contains("empty-final-turn"), s"prior tool use must not mask an empty final turn: $payload")
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
