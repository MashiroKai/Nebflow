package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.mail.SessionMailbox
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * Durable node-result park spec (contract: when the creator delegate is not live,
 * the node result lands in the DELEGATE'S OWN persistent mailbox — any later wake
 * drains it from disk; the in-memory 30s redelivery window is no longer the only
 * carrier).
 *
 *  - P-1 root ref missing: the result lands in the delegate's mailbox
 *    (kind=node-result, identity=node-result-<nodeId>-…), idempotent on replay.
 *  - P-2 the drain side is the standard mailbox scan — any wake path reads it back.
 */
class NodeResultParkedSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-park"
  private val originalRoot: os.Path = PathUtil.dataRoot

  private def resetRoot(): Unit =
    if os.exists(tempRoot) then
      try os.remove.all(tempRoot)
      catch case _: Throwable => ()
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  resetRoot()

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    resetRoot()
    DelegateFlowHosts.clear.unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateFlowHosts.clear.unsafeRunSync()

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = fakeLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

  test("P-1 root ref missing parks the node result into the delegate's own durable mailbox (idempotent)") {
    val ws = os.temp.dir(prefix = "node-park-ws")
    val system = ActorSystem("node-park-1")
    val delegateId = "delegate-general-p1a2b3c4"
    for
      resources <- mkResources(system, ws)
      mo <- DelegateFlowHosts.mount(
        delegateId = delegateId,
        project = None,
        baseWorkspace = ws.toString,
        engineCwd = ws.toString,
        system = system,
        resources = resources,
        wsSend = None,
        rootNotifyQuietMs = Some(0L)
      )
      h = mo.getOrElse(fail("host must mount"))
      // the creator delegate is NOT registered => the offer would park (in-memory only, before this batch)
      _ <- h.engine.deliverToRoot("implement finished: 3 files changed", "implement", "completed", Some("node-impl-1"))
      parked <- SessionMailbox.scanPending(delegateId)
      // replay the SAME result: identity dedup must keep exactly one entry
      _ <- h.engine.deliverToRoot("implement finished: 3 files changed", "implement", "completed", Some("node-impl-1"))
      parkedAfterReplay <- SessionMailbox.scanPending(delegateId)
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll
    yield
      val results = parked.filter(_.kind == "node-result")
      assertEquals(results.size, 1, s"exactly one durable park entry, got kinds: ${parked.map(_.kind)}")
      val e = results.head
      assert(
        e.identity.startsWith(s"node-result-node-impl-1-"),
        s"identity = node-result-<nodeId>-<gen>: ${e.identity}"
      )
      assertEquals(e.event.eventType, "completed")
      assert(e.event.payload.contains("3 files changed"))
      assertEquals(e.event.source, "node")
      assertEquals(
        parkedAfterReplay.count(_.kind == "node-result"),
        1,
        "same (node, content) replay dedupes onto the same identity"
      )
    end for
  }

end NodeResultParkedSpec
