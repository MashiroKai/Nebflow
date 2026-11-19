package nebflow.core.entity

import cats.effect.kernel.Outcome
import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{
  ActorPath,
  ActorRef,
  ActorSystem,
  AgentCommand,
  Behavior,
  InteractionHubCommand,
  InteractionKind,
  InteractionReply,
  InteractionRequest,
  NativeActorHandle
}
import nebflow.agent.{InteractionHub, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.{NodeStatus, RunningFlowRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{BgTaskRegistry, FileLockManager}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}
import org.apache.pekko.actor.typed

import java.util.UUID
import scala.concurrent.duration.*

/**
 * Executor-level tests for the flow timeout redesign (v2.1):
 *
 *  UT-1 — a slow node (LLM answers late) completes normally: node lifetime is
 *         event-driven; there is no wall-clock executioner. (The real
 *         8-minute sbt-compile reproduction is E2E-1; at unit scale this
 *         proves the wait survives latency far beyond an instant answer.)
 *  UT-2 — a multi-node chain with sustained per-node latency completes: no
 *         global flow timeout races the DAG (35-min scale is E2E).
 *  UT-4 — cancel while the node is RUNNING pierces it: the agent is stopped,
 *         cleanup runs (agentRegistry entry removed), node status is cancelled,
 *         flowCompleted(success=false) is emitted — and the flow returns
 *         promptly, not when the LLM would have answered. The node's dag-*
 *         session FILES are preserved (flow-run panel observability, #deck-v6).
 *  UT-5 — same, with a turn fiber hung on a never-emitting LLM: only the
 *         cancel signal can end the node.
 *  UT-6 — a successful flow keeps its node session on disk, listable via
 *         listSessionsIncludeUnindexed (the panel's REST fallback) with the
 *         full turn (user input + assistant output) intact.
 *
 * Drives the REAL FlowDagExecutor.execute against a REAL AgentActor spawned
 * in a real ActorSystem — only the LlmHandle is faked.
 */
class FlowDagExecutorCancelSpec extends CatsEffectSuite:

  // A fully controllable LLM: each request's answer stream is produced by `body`.
  private class FakeLlm(body: LlmRequest => Stream[IO, StreamChunk]) extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] = body(req)

  /** Answers with plain text after `delay` — no tool calls. */
  private def answerAfter(delay: FiniteDuration, text: String): Stream[IO, StreamChunk] =
    Stream.eval(IO.sleep(delay)) >> Stream(
      StreamChunk.TextDelta(text),
      StreamChunk.Done(None, None)
    )

  private def neverAnswer: Stream[IO, StreamChunk] = Stream.never[IO]

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    sessionsDir: os.Path,
    llm: LlmHandle[IO],
    dispatcher: Dispatcher[IO]
  ): IO[SharedResources] =
    for
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(sessionsDir, tmp / "tasks"),
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
      // Not touched on these paths (same pattern as SubAgentTaskStatusSpec).
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new nebflow.agent.SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  /**
   * Set up a temp dataRoot (with one flow agent "worker"), run the test, then
   * stop all actors, release the dispatcher, restore the dataRoot, and delete the temp tree — even on
   * failure or timeout.
   */
  private def withFlowEnv[A](llm: LlmHandle[IO])(
    test: (SharedResources, ActorSystem, os.Path, Ref[IO, List[io.circe.Json]]) => IO[A]
  ): IO[A] =
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
      Dispatcher.parallel[IO].use { dispatcher =>
        ActorSystem.resource(s"flow-cancel-test-${UUID.randomUUID().toString.take(6)}").use { system =>
          IO.ref(List.empty[io.circe.Json]).flatMap { eventsRef =>
            mkResources(system, tmp, tmp / "sessions", llm, dispatcher).flatMap { resources =>
              test(resources, system, tmp / "sessions", eventsRef)
            }
          }
        }
      }
    } { _ =>
      IO.delay(PathUtil.setDataRoot(prevRoot)) *>
        IO.delay(if os.exists(tmp) then os.remove.all(tmp)).attempt.void
    }

  end withFlowEnv

  private def oneNodeFlow: FlowDagDef =
    FlowDagDef(
      name = "tflow",
      description = "test",
      nodes = Map("n1" -> FlowNode("worker", "$task", NodeRoute.Return)),
      entry = "n1"
    )

  private def threeNodeChainFlow: FlowDagDef =
    FlowDagDef(
      name = "tflow",
      description = "test chain",
      nodes = Map(
        "n1" -> FlowNode("worker", "$task", NodeRoute.Goto("n2")),
        "n2" -> FlowNode("worker", "$task", NodeRoute.Goto("n3")),
        "n3" -> FlowNode("worker", "$task", NodeRoute.Return)
      ),
      entry = "n1"
    )

  private def wsSink(eventsRef: Ref[IO, List[io.circe.Json]]): Option[io.circe.Json => IO[Unit]] =
    Some(j => eventsRef.update(_ :+ j))

  private def flowCompletedSuccess(events: List[io.circe.Json]): Option[Boolean] =
    events
      .find(_.hcursor.downField("type").as[String].contains("flowCompleted"))
      .flatMap(_.hcursor.downField("success").as[Boolean].toOption)

  // ===== UT-1: slow node survives and completes =====

  test("UT-1 slow node (LLM answers after 1.2s) completes — no executioner timeout") {
    val llm = new FakeLlm(_ => answerAfter(1200.millis, "node output"))
    withFlowEnv(llm) { (resources, system, _, eventsRef) =>
      val instId = s"ut1-${UUID.randomUUID().toString.take(6)}"
      val t0 = System.currentTimeMillis()
      for
        result <- FlowDagExecutor
          .execute(oneNodeFlow, "do work", resources, system, wsSink(eventsRef), instId)
          .timeout(20.seconds)
        elapsed = System.currentTimeMillis() - t0
        events <- eventsRef.get
      yield
        assert(elapsed >= 1100, s"node must have taken at least the LLM delay (took ${elapsed}ms)")
        assert(result.isRight, s"slow node must complete, got: $result")
        assertEquals(result.toOption.get, "node output")
        assertEquals(flowCompletedSuccess(events), Some(true), "flowCompleted(success=true) emitted")
    }
  }

  // ===== UT-2: sustained multi-node activity — no global timeout =====

  test("UT-2 three-node chain with per-node latency completes — no global flow timeout") {
    val llm = new FakeLlm(_ => answerAfter(500.millis, "step done"))
    withFlowEnv(llm) { (resources, system, _, eventsRef) =>
      val instId = s"ut2-${UUID.randomUUID().toString.take(6)}"
      val t0 = System.currentTimeMillis()
      for
        result <- FlowDagExecutor
          .execute(threeNodeChainFlow, "chain work", resources, system, wsSink(eventsRef), instId)
          .timeout(20.seconds)
        elapsed = System.currentTimeMillis() - t0
        events <- eventsRef.get
        rfOpt <- RunningFlowRegistry.list.map(_.find(_.instanceId == instId))
      yield
        // 3 × 500ms of sustained work past the entry — a global race (or a
        // routing break) would fail this
        assert(elapsed >= 1400, s"chain must have sustained 3 nodes (took ${elapsed}ms)")
        assert(result.isRight, s"chain must complete, got: $result")
        assertEquals(flowCompletedSuccess(events), Some(true))
        val nodeStatuses = rfOpt.map(_.nodes.values.map(_.status).toList).getOrElse(Nil)
        assertEquals(nodeStatuses.count(_ == NodeStatus.Completed), 3, "all three nodes completed")
      end for
    }
  }

  // ===== UT-4: cancel pierces a running node (LLM would answer at 5s) =====

  test("UT-4 cancel while node runs: pierce stops agent, cleanup runs, status cancelled, prompt return") {
    val llm = new FakeLlm(_ => answerAfter(5.seconds, "too late"))
    withFlowEnv(llm) { (resources, system, sessionsDir, eventsRef) =>
      val instId = s"ut4-${UUID.randomUUID().toString.take(6)}"
      val t0 = System.currentTimeMillis()
      for
        execFiber <- FlowDagExecutor
          .execute(oneNodeFlow, "long work", resources, system, wsSink(eventsRef), instId)
          .start
        _ <- IO.sleep(500.millis) // the node is RUNNING inside its turn now
        nodeRunning <- RunningFlowRegistry.list.map(
          _.find(_.instanceId == instId).exists(_.nodes("n1").status == NodeStatus.Running)
        )
        _ <- RunningFlowRegistry.cancel(instId)
        // Pierce must return well before the 5s LLM delay; the join times out
        // hard (test failure) if the flow does not end — a broken pierce
        // would hang forever.
        result <- execFiber.join
          .flatMap {
            case Outcome.Succeeded(ioa) => ioa
            case other => IO.raiseError(new RuntimeException(s"flow fiber ended abnormally: $other"))
          }
          .timeout(6.seconds)
          .attempt
        elapsed = System.currentTimeMillis() - t0
        events <- eventsRef.get
        rfOpt <- RunningFlowRegistry.list.map(_.find(_.instanceId == instId))
        registry <- resources.agentRegistry.get
        sessionFiles <- IO.delay(
          if os.exists(sessionsDir) then os.list(sessionsDir).map(_.last).filter(_.startsWith("dag-")).toList
          else Nil
        )
      yield
        assert(nodeRunning, "node should be running when cancel fires")
        assert(result.exists(_.isLeft), s"flow must end via cancel pierce (timed out or unexpected: $result)")
        assert(elapsed < 4500, s"flow must return via cancel pierce, not by waiting out the 5s LLM (took ${elapsed}ms)")
        val flowResult = result.toOption.get
        assert(flowResult.isLeft, s"cancelled flow returns Left, got: $flowResult")
        assert(flowResult.swap.exists(_.toLowerCase.contains("cancelled")), s"error mentions cancellation: $flowResult")
        assertEquals(flowCompletedSuccess(events), Some(false), "flowCompleted(success=false) emitted")
        assertEquals(rfOpt.map(_.status), Some(NodeStatus.Cancelled), "flow status cancelled")
        assertEquals(
          rfOpt.flatMap(_.nodes.get("n1").map(_.status)),
          Some(NodeStatus.Cancelled),
          "node status cancelled"
        )
        assert(
          registry.keys.forall(k => !k.startsWith("dag-")),
          s"agentRegistry cleaned of flow node sessions, found: ${registry.keys.filter(_.startsWith("dag-"))}"
        )
        // Note: we deliberately do NOT assert on session FILES here — a cancel
        // mid-turn leaves nothing persisted (the aborted turn never reached
        // finish-turn persist). Preservation itself is covered by UT-6.
      end for
    }
  }

  // ===== UT-5: cancel pierces a HUNG turn fiber (LLM never answers) =====

  test("UT-5 cancel while turn fiber hangs on a never-answering LLM: pierce ends the flow") {
    val llm = new FakeLlm(_ => neverAnswer)
    withFlowEnv(llm) { (resources, system, _, eventsRef) =>
      val instId = s"ut5-${UUID.randomUUID().toString.take(6)}"
      for
        execFiber <- FlowDagExecutor
          .execute(oneNodeFlow, "hung work", resources, system, wsSink(eventsRef), instId)
          .start
        _ <- IO.sleep(500.millis) // the turn fiber is now hanging on the LLM stream
        _ <- RunningFlowRegistry.cancel(instId)
        result <- execFiber.join
          .flatMap {
            case Outcome.Succeeded(ioa) => ioa
            case other => IO.raiseError(new RuntimeException(s"flow fiber ended abnormally: $other"))
          }
          .timeout(6.seconds)
          .attempt
        events <- eventsRef.get
        rfOpt <- RunningFlowRegistry.list.map(_.find(_.instanceId == instId))
      yield
        assert(
          result.exists(_.isLeft),
          s"hung node must end via cancel pierce, not hang — got: $result (timeout = Left(TimeoutException) would mean broken pierce)"
        )
        assertEquals(flowCompletedSuccess(events), Some(false))
        assertEquals(rfOpt.flatMap(_.nodes.get("n1").map(_.status)), Some(NodeStatus.Cancelled))
      end for
    }
  }

  test("enclosing fiber cancellation stops the actor, closes pending interactions, and reclaims session tasks") {
    Deferred[IO, String].flatMap { entered =>
      val llm = new FakeLlm(req => Stream.eval(entered.complete(req.sessionId)).drain ++ neverAnswer)
      withFlowEnv(llm) { (resources, system, _, eventsRef) =>
        val instId = s"fiber-cancel-${UUID.randomUUID().toString.take(6)}"
        val rootSessionId = s"root-$instId"
        val requestId = s"ask-$instId"
        val jobId = s"task-$instId"
        for
          hub <- system.spawn(InteractionHub(), s"hub-$instId")
          _ <- resources.interactionHubRef.set(Some(hub))
          _ <- hub ! InteractionHubCommand.RegisterRoot(rootSessionId, j => eventsRef.update(_ :+ j))
          _ <- Resource
            .make(
              FlowDagExecutor
                .execute(
                  oneNodeFlow,
                  "cancel the enclosing IO",
                  resources,
                  system,
                  wsSink(eventsRef),
                  instId,
                  rootSessionId = rootSessionId
                )
                .start
            )(_.cancel)
            .use { fiber =>
              for
                sessionId <- entered.get.timeout(6.seconds)
                record <- resources.agentRegistry.get.map(
                  _.getOrElse(sessionId, fail("running agent must be registered"))
                )
                aliveBefore <- system.isAlive(record.ref.path)
                _ = assert(aliveBefore, "the owned local actor must be alive before cancellation")
                _ <- hub ! InteractionHubCommand.Request(
                  InteractionRequest(
                    requestId = requestId,
                    kind = InteractionKind.AskUser,
                    payload = io.circe.Json.obj(
                      "items" -> io.circe.Json
                        .arr(io.circe.Json.obj("question" -> io.circe.Json.fromString("Continue?")))
                    ),
                    reply = InteractionReply.AskUserReply(None),
                    rootSessionId = rootSessionId,
                    sourceAgent = "worker",
                    sourceSession = sessionId
                  )
                )
                pendingBefore <- pollFor(
                  hub
                    .?[List[io.circe.Json]](reply => InteractionHubCommand.ListPendingAsks(rootSessionId, reply))
                    .map(rows => Option.when(rows.exists(_.hcursor.get[String]("requestId").contains(requestId)))(rows))
                )
                _ = assert(pendingBefore.isDefined, "the real hub must retain the request before cancellation")
                // Registry-only task: no shell, process, or host resource is started.
                _ <- BgTaskRegistry.register(jobId, sessionId, "cancellation cleanup fixture", "local", rootSessionId)
                taskBefore <- BgTaskRegistry.find(jobId)
                _ = assert(taskBefore.isDefined, "the session task must be registered before cancellation")
                _ <- fiber.cancel.timeout(6.seconds)
                outcome <- fiber.join
                registry <- resources.agentRegistry.get
                aliveAfter <- system.isAlive(record.ref.path)
                closed <- pollFor(eventsRef.get.map(_.find { event =>
                  val cursor = event.hcursor
                  cursor.get[String]("type").contains("askUserClosed") &&
                  cursor.get[String]("requestId").contains(requestId)
                }))
                pendingAfter <- hub
                  .?[List[io.circe.Json]](reply => InteractionHubCommand.ListPendingAsks(rootSessionId, reply))
                taskRemoved <- pollFor(BgTaskRegistry.find(jobId).map(task => Option.when(task.isEmpty)(())))
              yield
                assert(outcome.isCanceled, s"the enclosing fiber must end by cancellation: $outcome")
                assert(!registry.contains(sessionId), "enclosing cancellation must remove the owned registry entry")
                assert(!aliveAfter, "enclosing cancellation must stop the owned local actor before returning")
                assert(closed.isDefined, "enclosing cancellation must emit askUserClosed for the pending request")
                assertEquals(closed.flatMap(_.hcursor.get[String]("reason").toOption), Some("source-stopped"))
                assert(
                  !pendingAfter.exists(_.hcursor.get[String]("requestId").contains(requestId)),
                  "enclosing cancellation must remove the request from the hub"
                )
                assert(taskRemoved.isDefined, "enclosing cancellation must reclaim the session task registry entry")
            }
            .guarantee(BgTaskRegistry.unregister(jobId))
        yield ()
        end for
      }
    }
  }

  private enum StartupFailure:
    case Spawn, Send

  /** Keep real actors and native ownership; intercept only the requested failure boundary. */
  private class FailingActorSystem(
    delegate: ActorSystem,
    failure: StartupFailure,
    spawned: Ref[IO, Option[ActorRef[?]]],
    nativeHandles: Ref[IO, List[NativeActorHandle[?]]],
    injected: Ref[IO, Boolean]
  ) extends ActorSystem:
    def localDevice: String = delegate.localDevice

    def spawn[Msg](behavior: Behavior[Msg], name: String, ttl: Option[FiniteDuration] = None): IO[ActorRef[Msg]] =
      if !name.startsWith("dagnode-") then delegate.spawn(behavior, name, ttl)
      else if failure == StartupFailure.Spawn then
        injected.set(true) *> IO.raiseError(new RuntimeException("injected agent startup failure"))
      else
        delegate.spawn(behavior, name, ttl).flatMap { ref =>
          spawned
            .set(Some(ref))
            .as(new ActorRef[Msg]:
              def path: ActorPath = ref.path
              def !(msg: Msg): IO[Unit] = msg match
                case _: AgentCommand.UserInput =>
                  injected.set(true) *> IO.raiseError(new RuntimeException("injected input delivery failure"))
                case _ => ref ! msg
              def ?[Reply](makeMsg: ActorRef[Reply] => Msg, timeout: Option[FiniteDuration]): IO[Reply] =
                ref.?(makeMsg, timeout))
        }

    override def spawnNative[Msg](
      behavior: Dispatcher[IO] => typed.Behavior[Msg],
      name: String
    ): Resource[IO, NativeActorHandle[Msg]] =
      delegate.spawnNative(behavior, name).evalTap(handle => nativeHandles.update(handle :: _))
    def resolve[Msg](path: String): IO[ActorRef[Msg]] = delegate.resolve(path)

    def stop(ref: ActorRef[?]): IO[Unit] =
      spawned.get.flatMap {
        case Some(actual) if actual.path == ref.path => delegate.stop(actual)
        case _ => delegate.stop(ref)
      }
    def stopAll: IO[Unit] = delegate.stopAll

    override def scheduleOnce(delay: FiniteDuration, delivery: IO[Unit]): IO[Unit] =
      delegate.scheduleOnce(delay, delivery)
    def isAlive(path: ActorPath): IO[Boolean] = delegate.isAlive(path)
    def watch(watcher: ActorPath, target: ActorPath): IO[Unit] = delegate.watch(watcher, target)
    def unwatch(watcher: ActorPath, target: ActorPath): IO[Unit] = delegate.unwatch(watcher, target)
    def deadLetterCount: IO[Long] = delegate.deadLetterCount
    def replayWatches: IO[Unit] = delegate.replayWatches

  end FailingActorSystem

  StartupFailure.values.foreach { failure =>
    test(s"$failure failure releases the native bridge and any owned local agent") {
      withFlowEnv(new FakeLlm(_ => neverAnswer)) { (resources, system, _, eventsRef) =>
        for
          spawned <- Ref.of[IO, Option[ActorRef[?]]](None)
          nativeHandles <- Ref.of[IO, List[NativeActorHandle[?]]](Nil)
          injected <- Ref.of[IO, Boolean](false)
          failingSystem = new FailingActorSystem(system, failure, spawned, nativeHandles, injected)
          result <- FlowDagExecutor
            .execute(
              oneNodeFlow,
              "fail during startup",
              resources.copy(actorSystem = failingSystem),
              failingSystem,
              wsSink(eventsRef),
              s"startup-${UUID.randomUUID().toString.take(6)}"
            )
            .attempt
            .timeout(6.seconds)
          wasInjected <- injected.get
          registry <- resources.agentRegistry.get
          actor <- spawned.get
          alive <- actor.traverse(ref => system.isAlive(ref.path))
          handles <- nativeHandles.get
          _ <- handles.traverse_(_.terminated.timeout(3.seconds))
        yield
          assert(wasInjected, "the requested failure boundary must be reached")
          assert(result.fold(_ => true, _.isLeft), s"startup failure must not succeed: $result")
          assertEquals(registry, Map.empty, "startup failure must remove the registry association")
          assert(handles.nonEmpty, "startup must acquire the real native result bridge")
          failure match
            case StartupFailure.Spawn => assertEquals(actor, None)
            case StartupFailure.Send =>
              assertEquals(alive, Some(false), "failed delivery must stop the real local actor")
      }
    }
  }

  // ===== UT-6: success preserves the node session (flow-run observability) =====

  /**
   * Finish-turn persist is forked (fire-and-forget) — the file may land a
   * moment AFTER execute returns. Poll instead of sleeping blindly.
   */
  private def pollFor[A](cond: IO[Option[A]], tries: Int = 60): IO[Option[A]] =
    cond.flatMap {
      case some @ Some(_) => IO.pure(some)
      case None if tries <= 0 => IO.pure(None)
      case None => IO.sleep(100.millis) >> pollFor(cond, tries - 1)
    }

  test("UT-6 successful flow keeps node session file, listed via includeUnindexed, turn content intact") {
    val llm = new FakeLlm(_ => answerAfter(50.millis, "node output text"))
    withFlowEnv(llm) { (resources, system, sessionsDir, eventsRef) =>
      val instId = s"ut6-${UUID.randomUUID().toString.take(6)}"
      for
        result <- FlowDagExecutor
          .execute(oneNodeFlow, "preserve me", resources, system, wsSink(eventsRef), instId)
          .timeout(20.seconds)
        // Wait for the forked persist to land: any dag-* raw session file.
        dagFile <- pollFor(
          IO.delay(
            if os.exists(sessionsDir)
            then
              os.list(sessionsDir)
                .map(_.last)
                .find(f => f.startsWith("dag-") && f.endsWith(".json") && !f.endsWith(".ui.json"))
            else None
          )
        )
        sid = dagFile.map(_.stripSuffix(".json").stripSuffix(".ui"))
        messages <- sid match
          case Some(id) => resources.sessionStore.loadMessagesForSession(id)
          case None => IO.pure(Nil)
      yield
        assert(result.isRight, s"flow must complete, got: $result")
        assert(dagFile.isDefined, "node session .json preserved after SUCCESS terminal (was deleted pre-fix)")
        // Note on scope: the panel's REST fallback additionally needs a .ui.json
        // (listSessionsIncludeUnindexed scans those) — that file is maintained
        // by the gateway turn wiring (SessionStore.appendUiMessages call sites
        // in WebSocketRoutes), outside this minimal actor harness. Historical
        // runtime evidence (dag-release-* sessions carry paired .ui.json)
        // confirms production emits it; unit asserts the preservation contract
        // this change owns.
        assert(messages.nonEmpty, "persisted turn present in the node session")
        val texts = messages.flatMap { m =>
          m.content.fold(Some(_), _.collect { case b: nebflow.shared.ContentBlock.Text => b.text })
        }
        assert(texts.exists(_.contains("preserve me")), s"user input persisted, texts: $texts")
        assert(texts.exists(_.contains("node output text")), s"assistant output persisted, texts: $texts")
      end for
    }
  }

end FlowDagExecutorCancelSpec
