package nebflow.neblink

import nebflow.core.tools.{FriendMessageTool, ToolContext, ToolError, ToolRegistry}

import cats.effect.{IO, Ref}
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.neblink.{AgentMessagingConfig, FriendRoster, FriendService, FriendSummary, NeblinkClient, NeblinkEndpoint, NeblinkServerConfig}

/**
 * SendMessage tool 单测（#290 域 A）。
 *
 * 钉死：①to 三级解析（neblinkId 精确→昵称精确→昵称唯一前缀）+ 多命中/零命中候选
 * 列表 ②档位行为透传（off 拒绝 / auto 发送成功文案 / ask 未接线拒绝）③参数校验
 * （缺 to / 缺 message / 超长）④schema 契约（required + properties）。
 *
 * 复用 FriendApiRoutesSpec 的 mock 形态：JDK HttpServer 模拟 neblink-server，
 * server 生命周期挂在 IO guarantee 上（munit IO 体返回后才执行——禁止同步 finally）。
 */
object FriendMessageToolSpec:
  /** 默认上游名册（u1 的 NL 号恰为邮箱——既有夹具形态 ⇒ 「邮箱串」会走 L1 直命，
    * 不经 L4。α 的靶子见 `CustomIdRoster`）。 */
  val DefaultFriendsJson: String =
    """{"friends":[{"userId":"u1","neblinkId":"lin@example.com","name":"林小满"}],"incoming":[],"outgoing":[]}"""

  /** 「NL 号已自定义、邮箱是另一个串」的名册 = L4 邮箱 α 唯一覆盖的洞：u1 的
    * username 是 `customNL1`，邮箱 `lin@example.com` 不在任何本地匹配键上。 */
  val CustomIdRoster: String =
    """{"friends":[{"userId":"u1","username":"customNL1","display_name":"林小满","avatar":null}],"incoming":[],"outgoing":[]}"""

class FriendMessageToolSpec extends CatsEffectSuite:
  // ── 解析三态（纯函数，直接测 resolveFriend） ─────────────

  private val friends = List(
    FriendSummary(userId = "u1", username = "lin@example.com", displayName = "林小满"),
    FriendSummary(userId = "u2", username = "wangxuan", displayName = "王选"),
    FriendSummary(userId = "u3", username = "linlin@example.com", displayName = "林小林")
  )

  test("resolveFriend level 1: username exact match (case-insensitive)") {
    val hit = FriendMessageTool.resolveFriend("LIN@EXAMPLE.COM", friends)
    assertEquals(hit.map(_.userId), Right("u1"))
  }

  test("resolveFriend level 2: name exact match") {
    val hit = FriendMessageTool.resolveFriend("王选", friends)
    assertEquals(hit.map(_.userId), Right("u2"))
  }

  test("resolveFriend level 3: unique name prefix") {
    val hit = FriendMessageTool.resolveFriend("林小满", friends) // 完整名=唯一前缀
    assertEquals(hit.map(_.userId), Right("u1"))
    val prefix = FriendMessageTool.resolveFriend("林", friends) // 前缀命中满+林两个 → 但先查精确名无 → 前缀两命中 → ambiguous
    assert(prefix.isLeft, "prefix hitting two friends must be ambiguous")
  }

  test("resolveFriend ambiguous prefix lists all candidates") {
    val err = FriendMessageTool.resolveFriend("林小", friends).left.toOption
    assert(err.isDefined, "ambiguous prefix must fail")
    val msg  = err.get.message
    val hits = friends.filter(_.displayName.startsWith("林小"))
    hits.foreach { f => assert(msg.contains(f.displayName), s"candidates must list ${f.displayName}") }
    assert(msg.contains("username"), "must suggest using the exact username")
  }

  test("resolveFriend zero hit lists available friends") {
    val err = FriendMessageTool.resolveFriend("不存在", friends).left.toOption
    assert(err.isDefined)
    assert(err.get.message.contains("not found"))
    friends.foreach { f => assert(err.get.message.contains(f.displayName), s"available list must contain ${f.displayName}") }
  }

  test("resolveFriend empty query rejected") {
    assert(FriendMessageTool.resolveFriend("  ", friends).isLeft)
  }

  // ── 档位行为 + 参数校验（NeblinkClient sendRequest seam stub——零网络） ──

  /** Transport-seam stub (NeblinkClientReloginSpec pattern): route by URL,
    * canned replies, record message-POST + search paths. Login first so
    * sessionToken is set — no HttpServer, no dataRoot writes.
    *
    * ⑦（2026-09-12）扩面：`/api/users/search`（L4 邮箱 α 的唯一通路）+ 可换的
    * 好友名册 JSON（「NL 号已自定义、邮箱是另一个串」的洞需要名册里 username ≠ 邮箱）。
    */
  private class StubClient(
    friendsJson: String = FriendMessageToolSpec.DefaultFriendsJson
  ):
    val postedPaths = scala.collection.mutable.ListBuffer.empty[String]
    val searchedPaths = scala.collection.mutable.ListBuffer.empty[String]

    /** L4 搜索应答（默认 miss；改它模拟命中 / 429 / 5xx / 网络错）。 */
    var searchReply: Either[String, String] = Right("""{"found":false}""")

    val client = new NeblinkClient(
      NeblinkServerConfig(url = "http://stub.local", networkId = "n1", secret = "s"),
      serverPort = 1
    ):
      override protected def sendRequest(
        method: String,
        url: String,
        body: String,
        token: Option[String]
      ): IO[Either[String, String]] =
        if url.endsWith("/api/device/login") then
          IO.pure(Right("""{"token":"tok-1","networkId":"n1","deviceId":"d1","peers":[]}"""))
        else if url.contains("/api/users/search") then
          IO { searchedPaths += url } *> IO.pure(searchReply)
        else if url.endsWith("/api/friends") && method == "GET" then
          IO.pure(Right(friendsJson))
        else if url.endsWith("/messages") then
          IO { postedPaths += url } *> IO.pure(Right("""{"messageId":5,"conversationId":"c1","createdAt":123}"""))
        else IO.pure(Left(s"unexpected request: $method $url"))

    def login(): Unit =
      client
        .login("dev-1", "TestMac", "macos", List(NeblinkEndpoint("10.0.0.5", 1, "lan")))
        .unsafeRunSync()

  private def withStubFs[A](
      mode: String,
      remarks: Map[String, String] = Map.empty,
      friendsJson: String = FriendMessageToolSpec.DefaultFriendsJson
  )(
      use: (FriendService, StubClient) => IO[A]
  ): IO[A] =
    IO.delay {
      val stub = StubClient(friendsJson)
      stub.login()
      new FriendService(
        IO.pure(Some(stub.client)),
        AgentMessagingConfig(mode = mode),
        remarkRef = Ref.unsafe[IO, Map[String, String]](remarks)
      ) -> stub
    }.flatMap { case (fs, stub) => use(fs, stub) }

  /** 备注注入版装配：启动期 `load → Ref` 的等价形态（`remarks` 非空即当已持久化）。 */
  private def withFs[A](mode: String, remarks: Map[String, String] = Map.empty)(
      use: FriendService => IO[A]
  ): IO[A] =
    withStubFs(mode, remarks) { (fs, _) => use(fs) }

  private def callTool(fs: FriendService, input: JsonObject): IO[Either[ToolError, String]] = {
    FriendMessageTool.initialize(fs)
    FriendMessageTool.call(input, ToolContext(projectRoot = "/tmp"))
  }

  test("mode=off: tool reports the user has disabled agent messaging") {
    withFs("off") { fs =>
      callTool(fs, JsonObject("to" -> "林小满".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.isLeft)
        assert(res.left.toOption.get.message.toLowerCase.contains("disabled"))
      }
    }
  }

  test("mode=auto: sends via friend addressing and returns the delivery confirmation") {
    withFs("auto") { fs =>
      callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.isRight, s"expected success, got ${res.left.toOption.map(_.message)}")
        assert(res.toOption.get.contains("已发送给 林小满"))
      }
    }
  }

  test("message longer than 4000 chars rejected before any network call") {
    withFs("auto") { fs =>
      val long = "a" * 4001
      callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> long.asJson)).map { res =>
        assert(res.isLeft)
        assert(res.left.toOption.get.message.contains("too long"))
      }
    }
  }

  test("missing 'to' parameter rejected") {
    withFs("auto") { fs =>
      callTool(fs, JsonObject("message" -> "hi".asJson)).map { res =>
        assert(res.isLeft)
        assert(res.left.toOption.get.message.contains("'to'"))
      }
    }
  }

  test("missing 'message' parameter rejected") {
    withFs("auto") { fs =>
      callTool(fs, JsonObject("to" -> "林小满".asJson)).map { res =>
        assert(res.isLeft)
        assert(res.left.toOption.get.message.contains("'message'"))
      }
    }
  }

  // ── schema 契约 ──────────────────────────────────────────

  test("schema: required = to + message, both typed string") {
    val schema = FriendMessageTool.inputSchema
    val req    = schema("required").flatMap(_.asArray).getOrElse(Vector.empty).map(_.asString.getOrElse(""))
    assertEquals(req.toSet, Set("to", "message"))
    assert(schema("properties").isDefined)
    assert(schema("properties").get.asObject.get("to").isDefined)
    assert(schema("properties").get.asObject.get("message").isDefined)
  }

  test("description carries the established-friendship + user-identity semantics") {
    val d = FriendMessageTool.description
    assert(d.contains("established friend relationships"))
    assert(d.contains("delivered as the user"))
  }

  test("tool registered under the exact name SendMessage") {
    assert(ToolRegistry.TOOL_MAP.contains("SendMessage"))
  }

  // ══════════ ⑦（2026-09-12）：L0 备注层 / 候选含备注 / 回执 / L4 邮箱 α ══════════

  /** L0 夹具：A 有备注、B 无备注；`C` 用于「L0 冲突 vs L1 命中」的对照。 */
  private val remarkFriends = List(
    FriendSummary("u1", "customNL1", "林小满", remark = Some("老林")),
    FriendSummary("u2", "wangxuan", "王选"),
    FriendSummary("u3", "老林", "陈老林") // username 恰与 A 的备注同串（L0 冲突对照）
  )

  test("L0 备注命中：备注（NOCASE + trim）优先于 L1，唯一命中即成功") {
    assertEquals(FriendRoster.resolve("老林", remarkFriends).map(_.userId), Right("u1"),
      "恰 1 个好友的备注匹配 ⇒ 命中该好友（备注是用户自己设的意图信号，排在 username 前）")
    assertEquals(FriendRoster.resolve("  老林 ", remarkFriends).map(_.userId), Right("u1"),
      "query 前后空白必须 trim 后参与 L0 匹配")
    assertEquals(FriendRoster.resolve("OLD LIN", List(FriendSummary("u9", "nl9", "九", remark = Some("Old Lin"))))
      .map(_.userId), Right("u9"), "L0 大小写不敏感（NOCASE）")
  }

  test("L0 冲突（多命中）⇒ 立即报错并列候选，不得降级命中 L1（⑦-D5）") {
    val twins = List(
      FriendSummary("u1", "nl1", "林小满", remark = Some("老林")),
      FriendSummary("u2", "nl2", "林小林", remark = Some("老林")),
      FriendSummary("u3", "老林", "陈老林") // L1 会命中这个 —— 降级就会「静默打到别人」
    )
    val res = FriendRoster.resolve("老林", twins)
    assert(res.isLeft, "备注撞车必须报错，不得任选一个")
    val msg = res.left.toOption.get.message
    assert(msg.contains("is ambiguous"), s"歧义文案在位，got: $msg")
    assert(msg.contains("林小满") && msg.contains("林小林"), s"两个备注撞车者都要列进候选，got: $msg")
    assert(msg.contains("[remark: 老林]"), s"候选行必须带备注（模型才知道是备注撞车），got: $msg")
    assert(!msg.contains("陈老林"), s"L0 冲突不得降级到 L1 的 username 命中者，got: $msg")
    // 对照：把冲突者去掉后，同一串在 L1 上是可命中的（证明上面确实是「被 L0 拦下」）
    assertEquals(FriendRoster.resolve("老林", List(twins(2))).map(_.userId), Right("u3"))
  }

  test("L0 零命中 ⇒ 照旧降级 L1–L3（有备注的好友不影响既有寻址链，逐级回归）") {
    assertEquals(FriendRoster.resolve("CUSTOMNL1", remarkFriends).map(_.userId), Right("u1"), "L1 username 精确")
    assertEquals(FriendRoster.resolve("王选", remarkFriends).map(_.userId), Right("u2"), "L2 昵称精确")
    assertEquals(FriendRoster.resolve("林小满", remarkFriends).map(_.userId), Right("u1"), "L2 昵称精确（该好友有备注，仍按昵称可达）")
    assertEquals(FriendRoster.resolve("林小", remarkFriends).map(_.userId), Right("u1"), "L3 唯一前缀（备注层不影响前缀口径）")
  }

  test("候选文案含备注（备注键对模型可见，否则模型永远不知道备注可寻址）") {
    val err = FriendRoster.resolve("查无此人", remarkFriends).left.toOption.get.message
    assert(err.contains("not found"), err)
    assert(err.contains("Available friends: "), err)
    assert(err.contains("[remark: 老林]"), s"候选必须带备注，got: $err")
    // 备注与 displayName 可区分（不是备注顶替显示名——形如 `A (a) [remark: r]`）
    assert(err.contains("林小满 (customNL1) [remark: 老林]"), s"行形 = displayName (username) [remark: …]，got: $err")
    // 未设备注的好友保持旧字面（零回归）
    assert(err.contains("王选 (wangxuan)"), err)
  }

  test("回执形态 ⑦-D6：命中备注 ⇒ 「备注（username）」；数据面备注经 refreshFriends 注入工具") {
    withFs("auto", remarks = Map("u1" -> "老林")) { fs =>
      callTool(fs, JsonObject("to" -> "老林".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.isRight, s"expected success, got ${res.left.toOption.map(_.message)}")
        val receipt = res.toOption.get
        assert(receipt.contains("已发送给 老林（lin@example.com）"),
          s"回执必须能确认「打到了谁」= 备注（username），got: $receipt")
      }
    }
  }

  test("α 命中：名册里 username 已自定义、邮箱是另一个串 ⇒ 经 /api/users/search 回映射 userId 后送达") {
    withStubFs("auto", friendsJson = FriendMessageToolSpec.CustomIdRoster) { (fs, stub) =>
      stub.searchReply = Right(
        """{"found":true,"user":{"userId":"u1","username":"customNL1","display_name":"林小满","avatar":null},"relation_status":"already_friends"}"""
      )
      // 名册里 u1 的 username 是自定义 NL 号，邮箱（lin@example.com）不在任何匹配键上
      // ⇒ L0–L3 必全未命中（正是 α 要补的洞）。
      assertEquals(FriendRoster.resolve("lin@example.com", List(FriendSummary("u1", "customNL1", "林小满"))).isLeft, true,
        "前提：邮箱不是 username/昵称 ⇒ 本地链必然 miss")
      callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.isRight, s"α 命中必须送达，got ${res.left.toOption.map(_.message)}")
        assertEquals(stub.searchedPaths.toList,
          List("http://stub.local/api/users/search?q=lin%40example.com"),
          "α 走唯一通路 /api/users/search（同桶 20/min，⑦-D11 的会计锚）")
        assert(stub.postedPaths.exists(_.endsWith("/api/friends/u1/messages")),
          s"按命中卡 userId 精确回映射 → 发给 u1，got: ${stub.postedPaths.toList}")
      }
    }
  }

  test("α 失败回落：上游 429/5xx/网络错 ⇒ 原样 not-found + 候选（不得升格为「好友不存在」）") {
    val friends = List(FriendSummary("u1", "customNL1", "林小满"))
    val expected = FriendRoster.resolve("lin@example.com", friends).left.toOption.get.message
    for
      res429 <- withStubFs("auto", friendsJson = FriendMessageToolSpec.CustomIdRoster) { (fs, stub) =>
        stub.searchReply = Left("HTTP 429: rate_limited")
        callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson))
      }
      res5xx <- withStubFs("auto", friendsJson = FriendMessageToolSpec.CustomIdRoster) { (fs, stub) =>
        stub.searchReply = Left("HTTP 503: upstream unavailable")
        callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson))
      }
      resNet <- withStubFs("auto", friendsJson = FriendMessageToolSpec.CustomIdRoster) { (fs, stub) =>
        stub.searchReply = Left("connection refused")
        callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson))
      }
    yield
      List(res429, res5xx, resNet).foreach { res =>
        assert(res.isLeft, "回落路径必须是失败（不发送）")
        val msg = res.left.toOption.get.message
        assertEquals(msg, expected, "必须原样返回 α 之前的失败文案（候选列表不变，逐字相等）")
        assert(!msg.contains("429") && !msg.contains("503") && !msg.contains("connection refused"),
          s"上游故障不得泄进错误面（更不得被说成「好友不存在」），got: $msg")
      }
  }

  test("α miss（found:false / 命中非好友 / 命中卡无 userId）⇒ 一律回落原样 not-found，且零发送") {
    val friends = List(FriendSummary("u1", "customNL1", "林小满"))
    val expected = FriendRoster.resolve("lin@example.com", friends).left.toOption.get.message
    val miss  = Right("""{"found":false}""")
    val stranger = Right(
      """{"found":true,"user":{"userId":"u-stranger","username":"someone","display_name":"陌生人","avatar":null},"relation_status":"addable"}"""
    )
    val noIdCard = Right("""{"found":true,"user":{"username":"someone","display_name":"无 id 卡"},"relation_status":"addable"}""")
    for
      a <- withStubFs("auto", friendsJson = FriendMessageToolSpec.CustomIdRoster) { (fs, stub) =>
        stub.searchReply = miss
        callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson))
      }
      b <- withStubFs("auto", friendsJson = FriendMessageToolSpec.CustomIdRoster) { (fs, stub) =>
        stub.searchReply = stranger
        callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson))
      }
      c <- withStubFs("auto", friendsJson = FriendMessageToolSpec.CustomIdRoster) { (fs, stub) =>
        stub.searchReply = noIdCard
        callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson))
      }
      after <- withStubFs("auto", friendsJson = FriendMessageToolSpec.CustomIdRoster) { (fs, stub) => // 零发送断言需要 stub 的快照
        stub.searchReply = stranger
        callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson)).map(r => r -> stub.postedPaths.toList)
      }
    yield
      List(a, b, c).zip(List("miss", "命中非好友", "命中卡无 userId")).foreach { (res, label) =>
        assert(res.isLeft, s"$label: 未回映射成好友 ⇒ 不发送")
        assertEquals(res.left.toOption.get.message, expected, s"$label: 原样回落（候选列表逐字不变）")
      }
      assertEquals(after._2, List.empty[String], "零发送（只有好友可发）")
  }

  test("α 仅失败路径触发：L1/L2 命中的发送不产生任何搜索往返（零额外上游开销）") {
    withStubFs("auto") { (fs, stub) =>
      stub.searchReply = Right("""{"found":false}""")
      callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.isRight)
        assertEquals(stub.searchedPaths.toList, Nil, "成功解析路径不得调 search")
      }
    }
  }

  test("⑦ 文本面：description / schema 的 to 文案 = 备注 / 用户名 / 邮箱三选一口径") {
    val d = FriendMessageTool.description
    assert(d.contains("remark"), "description 必须点名备注（模型据此知道备注可寻址）")
    assert(d.contains("username") && d.contains("email"), "description 必须点名 username + email")
    assert(d.contains("Plain text only."), "「只支持文本」事实句保留（末句不动）")
    val toDesc = FriendMessageTool.inputSchema("properties").flatMap(_.asObject)
      .flatMap(_("to")).flatMap(_.hcursor.get[String]("description").toOption).getOrElse("")
    assert(toDesc.contains("remark") && toDesc.contains("username") && toDesc.contains("email"),
      s"schema to 文案必须三选一（旧字面「Friend's username or display name.」已过时），got: $toDesc")
  }

end FriendMessageToolSpec
