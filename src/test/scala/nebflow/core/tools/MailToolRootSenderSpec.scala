package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.shared.PathUtil

/**
 * Mail 地址面 · root / 非团队调用者的分层地址判据（R2 批，2026-09-12 起）。
 *
 * 团队功能删除批（team-feature-deletion-20261104）阶段 3：作者裁定 Mail 地址面的
 * 三形态（团队名 / `team/agent` / 裸团队内短名）**硬删、不留特判**。本件原有
 * 6 条团队面用例随之整段删除：
 *   - queue/immediate × 「root sender + team/agent 地址被拒」（4 条，判据文案
 *     `TEAM names only` 已不存在）
 *   - 「root sender + TEAM name 路由到团队 Manager（lead）」
 *   - 「team member：裸短名 / 同团队 team/agent 路由不受影响」（2 条）
 * 删因：**团队地址面已硬删**（不是「跑不过」）。团队名此后落通用
 * 「不认识的地址」错误（`unresolvableError`），无专门错误码。
 *
 * 保留并仍钉住的面：
 *   1. Nebula 的分层地址：`node:<id|name>` 零挂载作用域 ⇒ CHAIN_NODE_NOT_FOUND；
 *      自身地址 ⇒ 显式报错；认不出的裸名 ⇒ 显式报错（禁静默兜底）。
 *   2. `project:<name>` 拆除面：任何调用者显式 MAIL_PROJECT_TRIGGER_UNSUPPORTED。
 */
class MailToolRootSenderSpec extends FunSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-mail-root-sender"
  PathUtil.setDataRoot(tempRoot)

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "teams")

  /** 非 Nebula 的普通会话 sender（不认 Nebula 身份 ⇔ 无 agentDef 的 Nebula 名）。 */
  private val rootSid = "root-sender-sid"

  private def rootCtx: ToolContext =
    ToolContext(projectRoot = tempRoot.toString, sessionId = Some(rootSid))

  /** Nebula root 身份：判据 = agentDef.name（引擎侧身份，非字符串猜名）。 */
  private def nebulaCtx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = tempRoot.toString,
      sessionId = Some(rootSid),
      agentDef = Some(nebflow.actor.AgentDef(name = "Nebula", description = "", tools = Nil)),
      actorSystem = Some(system)
    )

  test("R2: Nebula sender + node:<id|name> ⇒ N1 刀3 授权面扩：零挂载作用域 = 显式 CHAIN_NODE_NOT_FOUND（不猜不静默、指路 NodeList）"):
    val system = ActorSystem(s"mail-r2-neb-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("node:n-1"), "message" -> Json.fromString("hi")),
          nebulaCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          // N1 刀3（S6=C · v3 §7.2 ①授权面扩）：root 的 node: 腿从「越界」升格为
          // 「跨已挂载 delegate 作用域解析」——旧 R2 钉（Nebula 无 node 面 ⇒
          // outside-your-address-face）被设计取代。零挂载作用域 = 显式
          // CHAIN_NODE_NOT_FOUND（不猜不静默），文案指路属主 delegate 的 NodeList。
          assert(err.message.contains("CHAIN_NODE_NOT_FOUND"), s"须显式未找到：${err.message}")
          assert(err.message.contains("NodeList"), s"文案须指路 NodeList：${err.message}")
        case Right(v) => fail(s"零挂载作用域必须显式报错，got: $v")
    finally system.stopAll.unsafeRunSync()

    end try

  test("R2: Nebula sender + \"Nebula\"（自身）⇒ 显式报错（无自身地址）"):
    val system = ActorSystem(s"mail-r2-self-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(JsonObject("address" -> Json.fromString("Nebula"), "message" -> Json.fromString("hi")), nebulaCtx(system))
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(err.message.contains("self"), s"须点名自身地址：${err.message}")
          assert(err.message.contains("node:<节点id或名>"), s"错误文案须指明合法地址面：${err.message}")
        case Right(v) => fail(s"Nebula 发自身上址必须显式报错，got: $v")
    finally system.stopAll.unsafeRunSync()

  test("R2: Nebula sender + 认不出的裸名 ⇒ 显式报错（禁静默兜底/模糊匹配）"):
    val system = ActorSystem(s"mail-r2-unk-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("no-such-project-xyz"), "message" -> Json.fromString("hi")),
          nebulaCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(err.message.contains("not a recognizable target"), s"须是解析失败报错：${err.message}")
          assert(err.message.contains("node:<节点id或名>"), s"错误文案须指明合法地址面：${err.message}")
          // 阶段 3 后团队口径的文案已整体不存在（团队地址面硬删）——本断言保留为
          // 「不得回落成 team 路由口径」的护栏。
          assert(!err.message.contains("TEAM names only"), "不得回落成 team 路由口径（禁静默兜底）")
        case Right(v) => fail(s"认不出的地址必须显式报错，got: $v")
    finally system.stopAll.unsafeRunSync()
    end try

  // ── project: leg demolished (the project-trigger demolition batch) ──────────
  // Mail carries NO project trigger face any more: a project address
  // (`project:<name>` — bare mounted names are refused on their own legs) is an
  // explicit MAIL_PROJECT_TRIGGER_UNSUPPORTED refusal for EVERY role, before any
  // mount check or dispatcher trigger runs (no spawn, no revive, no note append,
  // no auto-created task). Task dispatching is Delegate's entry.

  private def delegateCtx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = tempRoot.toString,
      sessionId = Some("delegate-ab12cd34"),
      actorSystem = Some(system)
    )

  private def dispatcherCtx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = tempRoot.toString,
      sessionId = Some("dispatcher-spec-sid"),
      isDispatcher = true,
      actorSystem = Some(system)
    )

  test("demolished: delegate session + project:<name> => explicit refusal (no dispatcher trigger)"):
    val system = ActorSystem(s"mail-r2-del-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("project:p-x"), "message" -> Json.fromString("hi")),
          delegateCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(
            err.message.contains(MailTool.ErrProjectTriggerUnsupported),
            s"must be the demolition refusal: ${err.message}"
          )
          assert(err.message.contains("Delegate"), s"must point at Delegate: ${err.message}")
          assert(!err.message.contains("not mounted"), s"the refusal must not depend on mount state: ${err.message}")
        case Right(v) => fail(s"a delegate mailing project: must be rejected, got: $v")
    finally system.stopAll.unsafeRunSync()

    end try

  test("demolished: standalone session + project:<name> => explicit refusal (no dispatcher trigger)"):
    val system = ActorSystem(s"mail-r2-team-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("project:p-x"), "message" -> Json.fromString("hi")),
          rootCtx.copy(actorSystem = Some(system)) // no Nebula agentDef => standalone role
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(
            err.message.contains(MailTool.ErrProjectTriggerUnsupported),
            s"must be the demolition refusal: ${err.message}"
          )
          assert(!err.message.contains("not mounted"), s"the refusal must not depend on mount state: ${err.message}")
        case Right(v) => fail(s"a standalone sender mailing project: must be rejected, got: $v")
    finally system.stopAll.unsafeRunSync()

    end try

  test("demolished: dispatcher + project:<name> => explicit refusal (no self-trigger)"):
    val system = ActorSystem(s"mail-r2-disp-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("project:p-x"), "message" -> Json.fromString("hi")),
          dispatcherCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(
            err.message.contains(MailTool.ErrProjectTriggerUnsupported),
            s"must be the demolition refusal: ${err.message}"
          )
          assert(!err.message.contains("not mounted"), s"the refusal must not depend on mount state: ${err.message}")
        case Right(v) => fail(s"a dispatcher mailing project: must be rejected, got: $v")
    finally system.stopAll.unsafeRunSync()

    end try

  test(
    "demolished: Nebula root + project:<name> => explicit refusal (the trigger leg is gone, mount state irrelevant)"
  ):
    val system = ActorSystem(s"mail-r2-root-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val res = MailTool
        .call(
          JsonObject("address" -> Json.fromString("project:no-such-project-xyz"), "message" -> Json.fromString("hi")),
          nebulaCtx(system)
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(
            err.message.contains(MailTool.ErrProjectTriggerUnsupported),
            s"must be the demolition refusal: ${err.message}"
          )
          assert(err.message.contains("Delegate"), s"must point at Delegate: ${err.message}")
          assert(
            !err.message.contains("outside your address face"),
            s"the demolition word replaces the face word: ${err.message}"
          )
        case Right(v) => fail(s"a project address must be refused, got: $v")
    finally system.stopAll.unsafeRunSync()
    end try

end MailToolRootSenderSpec
