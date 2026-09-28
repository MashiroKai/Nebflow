package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.flow.TeamSessionRegistry

/**
 * checkTeamScope cross-team tightening (decision 20): explicit
 * "team/agent" addresses targeting another team are blocked by default for
 * non-lead senders. A team opts in via the rules.md marker
 * `<!-- allow-cross-team-mail: true -->`. Leads always pass. Same-team
 * explicit routes and short names are unaffected.
 */
class MailToolCheckTeamScopeSpec extends FunSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-mail-scope"
  PathUtil.setDataRoot(tempRoot)

  private def writeTeam(name: String, lead: String, rules: String = ""): Unit =
    val dir = tempRoot / "teams" / name
    os.makeDir.all(dir)
    os.write.over(
      dir / "team.json",
      s"""{"name": "$name", "description": "test team", "lead": "$lead", "members": []}"""
    )
    if rules.nonEmpty then os.write(dir / "rules.md", rules)

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "teams")
    TeamSessionRegistry.clear.unsafeRunSync()
    writeTeam("myteam", lead = "boss") // rules.md absent → marker off
    writeTeam("other", lead = "chief")

  /** R2 后 checkTeamScope 需要 ToolContext（canMailNebula 的角色判据走 `ctx.isDispatcher`）。
    * 缺省 = 非分发器（team 身份面，既有 6 用例语义逐字不变）。 */
  private def check(
      address: String,
      senderName: String = "worker",
      senderSid: String = "worker-sid",
      isDispatcher: Boolean = false
  ) =
    MailTool
      .checkTeamScope(
        address,
        "myteam",
        senderSid,
        senderName,
        ToolContext(projectRoot = os.pwd.toString, sessionId = Some(senderSid), isDispatcher = isDispatcher)
      )
      .unsafeRunSync()

  test("non-lead cross-team explicit route is blocked by default"):
    val res = check("other/Backend")
    assert(res.isDefined, "must be blocked without the rules.md marker")
    assert(res.get.contains("Cross-team"), s"error should explain the block: ${res.get}")
    assert(res.get.contains("Manager"), s"error should point to the escalation path: ${res.get}")

  test("same-team explicit route is unaffected"):
    assertEquals(check("myteam/Backend"), None)

  test("short-name routing is unaffected"):
    assertEquals(check("backend"), None)

  test("rules.md marker opts the team in"):
    writeTeam("myteam", lead = "boss", rules = "# Rules\n\n<!-- allow-cross-team-mail: true -->\n")
    assertEquals(check("other/Backend"), None)

  test("lead by session (registered Manager) always passes"):
    TeamSessionRegistry.registerManager("myteam", "mgr-sid").unsafeRunSync()
    assertEquals(check("other/Backend", senderName = "whoever", senderSid = "mgr-sid"), None)

  test("lead by name (team.json lead, unregistered session) passes"):
    // Fork/temporary sessions carry the lead agentDef but an unregistered sid —
    // name-based fallback (same as canMailNebula).
    assertEquals(check("other/Backend", senderName = "boss", senderSid = "fork-sid"), None)

  test("mailing another team BY NAME stays blocked (pre-existing behavior)"):
    val res = check("other")
    assert(res.isDefined)
    assert(res.get.contains("Cannot mail outside your team"), s"${res.get}")

  test("Nebula routing unchanged: non-lead blocked, lead allowed"):
    val blocked = check("Nebula")
    assert(blocked.isDefined)
    assert(blocked.get.contains("Cannot mail Nebula directly"), s"${blocked.get}")
    TeamSessionRegistry.registerManager("myteam", "mgr-sid").unsafeRunSync()
    assertEquals(check("Nebula", senderSid = "mgr-sid"), None)

  test("unknown target team in explicit route is still treated as cross-team"):
    val res = check("ghost-team/Agent")
    assert(res.isDefined, "conservative: unresolvable team part is not an exemption")

  test("R2 判据反转：分发器身份（ctx.isDispatcher）→ Mail(\"Nebula\") 放行"):
    // 2026-09-12 细则把「谁能给 root 发」收窄为**项目分发器**这一个角色；
    // 旧判据（managerMap / lead 名兜底）对分发器恒 false ⇒ 腿③ 结构上不可达。
    assertEquals(check("Nebula", senderName = "project-dispatcher", senderSid = "disp-sid", isDispatcher = true), None)

  test("R2 分层地址面：node:<id> 是分发器专属面 —— team 身份调用 ⇒ 显式越界报错（含合法面文案）"):
    // node: 腿在 layeredRoute 层就按**角色**分派（不再落 team 瀑布）：team 身份
    // 发 node: 一律显式越界报错，且错误文案必须指明该角色的合法地址面
    // （细则硬要求：错误里须指明合法地址面；禁静默兜底/模糊匹配）。
    val system = nebflow.actor.ActorSystem(s"mail-scope-r2-${java.util.UUID.randomUUID().toString.take(6)}")
    val res =
      try
        MailTool
          .call(
            io.circe.JsonObject(
              "address" -> io.circe.Json.fromString("node:n-1"),
              "message" -> io.circe.Json.fromString("hi")
            ),
            ToolContext(
              projectRoot = os.pwd.toString,
              sessionId = Some("worker-sid"),
              actorSystem = Some(system),
              isDispatcher = false
            )
          )
          .unsafeRunSync()
      finally system.stopAll.unsafeRunSync()
    res match
      case Left(err) =>
        assert(err.message.contains("outside your address face"), s"须是越界报错：${err.message}")
        assert(err.message.contains("team/agent"), s"须指明 team 身份的合法地址面：${err.message}")
      case Right(v) => fail(s"team 身份发 node: 必须显式报错，got: $v")
end MailToolCheckTeamScopeSpec
