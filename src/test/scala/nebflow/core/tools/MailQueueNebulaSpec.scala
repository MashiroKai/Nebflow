package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.agent.{AgentKind, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.flow.MailQueueStore
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

  /** 真 actor ref：R2 起 `resolveNebulaRoots` 的口径是「可投递的 Root 记录」
    *（`ref != null`）——记录存在但 ref 空 = 投不出去 = 判解析不出（显式报错，禁静默）。 */
  private val system = nebflow.actor.ActorSystem("mail-queue-nebula-spec")

  private def liveRef: nebflow.actor.ActorRef[nebflow.agent.AgentCommand] =
    system
      .spawn(
        nebflow.actor.Behaviors.receiveMessage[nebflow.agent.AgentCommand](_ =>
          IO.pure(nebflow.actor.Behaviors.stopped)
        ),
        s"qnb-${java.util.UUID.randomUUID().toString.take(8)}"
      )
      .unsafeRunSync()

  private def record(sid: String, kind: AgentKind): AgentRecord =
    AgentRecord(sessionId = sid, ref = liveRef, kind = kind, rootSessionId = sid)

  /** 显式空 ref 记录（用于「记录在但不可投递 ⇒ 解析不出」用例）。 */
  private def deadRecord(sid: String, kind: AgentKind): AgentRecord =
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

  test("resolveNebulaRootSession（档① preferred）finds the triggering root session"):
    val res = resourcesWith(
      Map(
        "agent-1" -> record("nebula-root-sid", AgentKind.Root),
        "agent-2" -> record("team-sid", AgentKind.Team)
      )
    )
    assertEquals(
      MailTool.resolveNebulaRootSession(res, senderSessionId = "", preferredRootSid = Some("nebula-root-sid")).unsafeRunSync(),
      Some("nebula-root-sid")
    )

  test("resolveNebulaRootSession（档①）preferred 命中失败 ⇒ None（禁静默改选别的 Root 会话）"):
    val res = resourcesWith(
      Map(
        "agent-1" -> record("nebula-root-sid", AgentKind.Root),
        "agent-2" -> record("other-root-sid", AgentKind.Root)
      )
    )
    assertEquals(
      MailTool.resolveNebulaRootSession(res, senderSessionId = "", preferredRootSid = Some("does-not-exist")).unsafeRunSync(),
      None,
      "preferred 找不到时不得回落到注册表里的任意 Root 记录（旧实现正是这一条导致落到非 Nebula 的 Root 会话）"
    )

  test("resolveNebulaRootSession（档①）排除发信者自身（硬禁回落成发信者自身）"):
    val res = resourcesWith(Map("agent-1" -> record("dispatcher-me", AgentKind.Root)))
    assertEquals(
      MailTool.resolveNebulaRootSession(res, senderSessionId = "dispatcher-me", preferredRootSid = Some("dispatcher-me")).unsafeRunSync(),
      None,
      "preferred == 发信者自身 ⇒ 解析不出（不是「发给自己」）"
    )

  test("resolveNebulaRootSession（档② meta）no Root record ⇒ None"):
    val res = resourcesWith(Map("agent-2" -> record("team-sid", AgentKind.Team)))
    assertEquals(MailTool.resolveNebulaRootSession(res, senderSessionId = "").unsafeRunSync(), None)

  test("resolveNebulaRootSession（档② meta）empty registry ⇒ None"):
    assertEquals(MailTool.resolveNebulaRootSession(resourcesWith(Map.empty), senderSessionId = "").unsafeRunSync(), None)

  test("queueToNebula 无 root 会话 ⇒ 显式 NEBULA_ROOT_UNRESOLVED（不是静默成功）"):
    val ctx = ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("dispatcher-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(resourcesWith(Map.empty)),
      isDispatcher = true
    )
    val res = MailTool
      .queueToNebula("hello Nebula", "RESULT", Nil, ctx, null, "dispatcher-sid", "Nebula", None)
      .unsafeRunSync()
    assert(res.isLeft, "must fail when the root agent is not in the registry")
    assert(res.swap.toOption.get.message.contains("NEBULA_ROOT_UNRESOLVED"), s"unexpected message: $res")

  test("R2 腿③：分发器身份 + 可解析 root ⇒ queue 路真正可达（旧实现此处恒不可达）"):
    val ctx = ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("dispatcher-sid"),
      rootSessionId = Some("nebula-root-sid"),
      sharedResources = Some(resourcesWith(Map("agent-1" -> record("nebula-root-sid", AgentKind.Root)))),
      isDispatcher = true
    )
    // ctx.sharedResources 命中 root 会话 —— 投递本体需要 queueToSession 的资源，这里只断言
    // **解析档**成功（不再落入 NEBULA_ROOT_UNRESOLVED）：解析可达即腿③ 的结构前提成立，
    // 端到端可达由隔离实例三条腿验证（见本批交付⑦/⑧）。
    val resolved = MailTool
      .resolveNebulaRootSession(ctx.sharedResources.get, ctx.sessionId.getOrElse(""), ctx.rootSessionId)
      .unsafeRunSync()
    assertEquals(resolved, Some("nebula-root-sid"))

  test("R2 腿③ 解析判据：Root 记录存在但 ref 空 ⇒ 判解析不出（不可投递，禁静默落到别的会话）"):
    // R2 起判据 = 「**可投递**的 Root 记录」（resolveNebulaRoots 过滤 ref != null）：
    // 记录在册但 ref 空（会话已死/未起）不是可投递目标 ⇒ 归入「解析不出」显式报错，
    // 硬禁静默改选别的 Root 会话或回落发信者自身（追加条款② 三种静默）。
    val res = resourcesWith(Map("agent-1" -> deadRecord("nebula-root-sid", AgentKind.Root)))
    assertEquals(
      MailTool.resolveNebulaRootSession(res, senderSessionId = "", preferredRootSid = Some("nebula-root-sid")).unsafeRunSync(),
      None
    )
    assertEquals(
      MailTool.resolveNebulaRootSession(res, senderSessionId = "").unsafeRunSync(),
      None,
      "档②（无 preferred）同样落空——不得因记录存在就报成功"
    )

  test("R2 腿③ + chainId：解析不出 ⇒ 显式报错且零落库（chainId 只校验不落库，B2-x）"):
    val ctx = ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("dispatcher-sid"),
      rootSessionId = Some("nebula-root-absent"),
      sharedResources = Some(resourcesWith(Map.empty)),
      isDispatcher = true
    )
    val before = MailQueueStore.size("nebula-root-absent").unsafeRunSync()
    val res = MailTool
      .queueToNebula("批级回传", "RESULT", Nil, ctx, null, "dispatcher-sid", "Nebula", Some("chain-n-q1"))
      .unsafeRunSync()
    assert(res.isLeft, s"解析不出必须失败：$res")
    assert(res.swap.toOption.get.message.contains("NEBULA_ROOT_UNRESOLVED"), s"unexpected: $res")
    assertEquals(
      MailQueueStore.size("nebula-root-absent").unsafeRunSync(),
      before,
      "解析不出 ⇒ 零队列落库（chainId 不落库、投递不发生）"
    )
