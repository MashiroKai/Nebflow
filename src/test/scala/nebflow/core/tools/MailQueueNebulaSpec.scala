package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.agent.{AgentKind, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.llm.{ModelCandidate, ThinkingConfig}

/**
 * Issue #312: queue-mode Mail to "Nebula" was rejected with a misleading
 * "only Team Lead can communicate with Nebula" error even when the sender
 * IS the Team Lead (Manager→Nebula queue rejected; immediate mode fine).
 *
 * Root cause: resolveSessionId's contract for "Nebula" is Right(None) =
 * "route to the root agent directly", but the caller (resolveAndQueue)
 * treated Right(None) as "not found" → mailNotFound. The Nebula root is a
 * Root-kind AgentRecord in the agent registry, NOT a team session in
 * sessionMap, so it was never resolvable in queue mode. immediate mode was
 * unaffected because it resolves the actor by name via system.resolve.
 *
 * Fix: resolveAndQueue now routes "Nebula" through queueToNebula, which
 * locates the root session in the agent registry and queues there.
 */
class MailQueueNebulaSpec extends FunSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-mail-queue-nebula"
  PathUtil.setDataRoot(tempRoot)

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)

  private def record(sid: String, kind: AgentKind): AgentRecord =
    // resolveNebulaRootSession only reads kind + sessionId — ref can be null.
    AgentRecord(sessionId = sid, ref = null, kind = kind, rootSessionId = sid)

  private def resourcesWith(registry: Map[String, AgentRecord]): SharedResources =
    new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      agentRegistry = Ref.unsafe[IO, Map[String, AgentRecord]](registry),
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )

  test("resolveNebulaRootSession finds the Root record's sessionId"):
    val res = resourcesWith(
      Map(
        "agent-1" -> record("nebula-root-sid", AgentKind.Root),
        "agent-2" -> record("team-sid", AgentKind.Team)
      )
    )
    assertEquals(MailTool.resolveNebulaRootSession(res).unsafeRunSync(), Some("nebula-root-sid"))

  test("resolveNebulaRootSession returns None when no Root record is registered"):
    val res = resourcesWith(Map("agent-2" -> record("team-sid", AgentKind.Team)))
    assertEquals(MailTool.resolveNebulaRootSession(res).unsafeRunSync(), None)

  test("resolveNebulaRootSession returns None on an empty registry"):
    assertEquals(MailTool.resolveNebulaRootSession(resourcesWith(Map.empty)).unsafeRunSync(), None)

  test("queueToNebula without a Root record falls back to the not-found error"):
    val ctx = ToolContext(projectRoot = os.pwd.toString, sharedResources = Some(resourcesWith(Map.empty)))
    val res = MailTool
      .queueToNebula("hello Nebula", "RESULT", Nil, ctx, null, "manager-sid", "Nebula")
      .unsafeRunSync()
    assert(res.isLeft, "must fail when the root agent is not in the registry")
    assert(res.swap.toOption.get.message.contains("Cannot deliver to 'Nebula'"), s"unexpected message: $res")
