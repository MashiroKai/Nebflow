package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.{AgentCore, AgentDef, SharedResources}
import nebflow.neblink.{AgentMessagingConfig, ConversationSummary, FriendListResponse, FriendRoster, FriendService, FriendSummary, MessageSummary, NeblinkClient, NeblinkServerConfig}

/**
 * ListFriends 注册 / 授能 / 调用面契约（好友消息改造批 ⑩，方案
 * `20260912_011320_好友消息改造批方案` §4.5 + §5.6 判据 L1–L8；仿
 * `CardToolRegistrationSpec`）。
 *
 * 钉死：
 *  1. **注册面**（L1 前半）：`ToolRegistry` 含名、`eq` 同实例、`ALL_TOOLS` 暴露 schema；
 *     schema = 零参数（`properties={}` / `required=[]`）。
 *  2. **不越界面**（L3）：插件白名单不含；`RemoteExecutor.remoteableTools` 不含且
 *     `augmentSchema` 不注入 `device` 参数。
 *  3. **授能面**（L2）：Nebula 携带；dispatcher / general 不携带；`NebulaExclusiveTools`
 *     单点（含保存侧 strip）含之，dream 无豁免 ⇒ agent.json 声明（含 `"*"`）不授能。
 *     （`buildAllowedToolSet` 本体是 `private[agent] trait AgentCore` 的 protected
 *     成员，跨包不可见 ⇒ 本文件按 `CardToolRegistrationSpec` 先例断言同一批消费者
 *     共用的公开常量与单点函数；声明逃逸的**黑盒**断言在 `AllowedToolSetSpec` /
 *     `AgentConvergenceSpec`（后者的 dream 剥离集会值已含 ListFriends）。）
 *  4. **调用面**：服务缺席 / 未登录 / 上游故障（**均不得被报成空名册**）/ 空名册 /
 *     正常名册 / 超长名册封顶 / 零参数可调用（无 filter / limit 参数路径）。
 *  5. **词表与不谎报**（L4③/L5/L7）：名册行逐字 == `FriendRoster.candidateLine`（与
 *     `SendMessage` 失败候选同一份）；输出与 description 无任何在线/可达字段；只读
 *     零副作用（恰一次 `listFriends`，写面/会话面零触碰）。
 */
class ListFriendsToolRegistrationSpec extends CatsEffectSuite:

  private def mkDef(name: String, tools: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools)

  private val emptyInput = JsonObject.empty

  /** 只读 `sharedResources.friendService` 的 ToolContext。本工具不读 SharedResources
    * 的任何其它字段 ⇒ 其余槽位一律 `null`（`AgentControlToolSpec` 先例：`llm = null`
    * / `providerRegistry = null`），无需真 ActorSystem / Dispatcher。 */
  private def ctx(fs: Option[FriendService]): ToolContext =
    ToolContext(
      projectRoot = "/tmp",
      sharedResources = Some(
        SharedResources(
          llm = null,
          dispatcher = null,
          sessionStore = null,
          projectRoot = os.pwd,
          thinkingConfigRef = null,
          rateLimiter = null,
          fileChangeTracker = null,
          contextWindow = 100_000,
          agentLibrary = null,
          taskStore = null,
          historyArchiver = null,
          fileLockManager = null,
          sessionModelOverrides = null,
          providerRegistry = null,
          healthMonitor = null,
          actorSystem = null,
          friendService = fs,
          voiceMutedRef = null
        )
      )
    )

  // ── 好友域 seam：遮蔽 `NeblinkClient` 的公开 API（`listFriends` 非 final）──
  // 走公开方法而非 protected 的 transport 接缝，为的是不必 `login()`（其参数类型
  // `NeblinkEndpoint` 是 `private[neblink]`，跨包不可构造）。写面与相邻读面一律
  // 遮蔽并记账：被触碰即记 `unexpected:*` ⇒ L7「只读零副作用」的机制反证。
  private class StubClient(reply: Either[String, FriendListResponse]):
    val calls = scala.collection.mutable.ListBuffer.empty[String]

    private def unexpected[A](what: String): IO[Either[String, A]] =
      calls += s"unexpected:$what"
      IO.pure(Left(s"unexpected call: $what"))

    val client = new NeblinkClient(
      NeblinkServerConfig(url = "http://stub.local", networkId = "n1", secret = "s"),
      serverPort = 1
    ):
      override def listFriends: IO[Either[String, FriendListResponse]] =
        calls += "listFriends"
        IO.pure(reply)

      override def listConversations: IO[Either[String, List[ConversationSummary]]] =
        unexpected("listConversations")
      override def listMessages(
        conversationId: String,
        after: Long = 0L,
        limit: Int = 50
      ): IO[Either[String, List[MessageSummary]]] = unexpected("listMessages")
      override def markConversationRead(
        conversationId: String,
        lastReadMessageId: Long
      ): IO[Either[String, String]] = unexpected("markConversationRead")
      override def blockFriend(friendUserId: String): IO[Either[String, String]] =
        unexpected("blockFriend")
      override def sendFriendMessage(
        friendUserId: String,
        body: String,
        origin: Option[String] = None
      ): IO[Either[String, Json]] = unexpected("sendFriendMessage")

  private def withStub[A](reply: Either[String, FriendListResponse])(
      use: (FriendService, StubClient) => IO[A]
  ): IO[A] =
    IO.delay {
      val stub = StubClient(reply)
      new FriendService(IO.pure(Some(stub.client)), AgentMessagingConfig()) -> stub
    }.flatMap { case (fs, stub) => use(fs, stub) }

  private val twoFriends = FriendListResponse(
    friends = List(
      FriendSummary("u1", "lin@example.com", "林小满", blocked = Some(true)),
      FriendSummary("u2", "wangxuan", "王选")
    )
  )

  private val emptyRoster = FriendListResponse(Nil)

  // ══════════ 1. 注册面（L1） ══════════

  test("注册面：ToolRegistry 含 ListFriends + eq 同实例 + ALL_TOOLS 暴露 schema"):
    assert(ToolRegistry.TOOL_MAP.contains("ListFriends"), "ListFriends must be registered")
    assert(ToolRegistry.TOOL_MAP("ListFriends") eq ListFriendsTool, "registry entry is ListFriendsTool")
    val exposed = ToolRegistry.ALL_TOOLS.find(_.name == "ListFriends")
    assert(exposed.isDefined, "ALL_TOOLS exposes ListFriends schema（未注册名在交付面静默落空）")
    assert(exposed.get.description.nonEmpty, "description 随 schema 交给 LLM")

  test("schema：零参数契约（properties={} / required=[]），不加 filter、不加 limit"):
    val schema = ListFriendsTool.inputSchema
    assertEquals(schema("type").flatMap(_.asString), Some("object"))
    assertEquals(schema("properties").flatMap(_.asObject).map(_.keys.toList), Some(List.empty[String]),
      "零参数：properties 必须显式给空对象（不加 filter —— 会与 FriendRoster.resolve 分叉；不加 limit —— 静默截断会让模型误判）")
    assertEquals(schema("required").flatMap(_.asArray).map(_.toList), Some(List.empty[io.circe.Json]),
      "required = []（显式给出）")
    // 交付面 schema 也不得被 device 注入污染（见下方 L3 用例）

  // ══════════ 2. 不越界面（L3） ══════════

  test("L3：插件白名单不含 ListFriends（否则绕过 NebulaExclusiveTools 剥离面）"):
    assert(
      !nebflow.core.plugin.PluginRegistry.BuiltinToolWhitelist.contains("ListFriends"),
      "插件通道不得授 ListFriends（授能面仅 NebulaOrchestrationTools 单点）"
    )

  test("L3：RemoteExecutor.remoteableTools 不含 ListFriends，augmentSchema 不注入 device"):
    assert(!RemoteExecutor.remoteableTools.contains("ListFriends"),
      "ListFriends 无 device 参数语义（好友面 ≠ 设备面，两者正交）")
    val augmented = RemoteExecutor.augmentSchema("ListFriends", ListFriendsTool.inputSchema)
    assertEquals(augmented, ListFriendsTool.inputSchema, "schema 逐字不变")
    assert(augmented("properties").flatMap(_.asObject).forall(o => !o.contains("device")),
      "零参数 schema 不得被注入 device 参数")

  // ══════════ 3. 授能面（L2） ══════════

  test("L2：Nebula 固定面携带 ListFriends；dispatcher / general 均不携带"):
    assert(AgentCore.fixedToolsFor(mkDef("Nebula")).contains("ListFriends"), "Nebula 携带 ListFriends")
    assert(!AgentCore.fixedToolsFor(mkDef("project-dispatcher")).contains("ListFriends"), "dispatcher 不授 ListFriends")
    assert(!AgentCore.fixedToolsFor(mkDef("general")).contains("ListFriends"), "general 不授 ListFriends")
    assert(!AgentCore.DispatcherFixedTools.contains("ListFriends"), "DispatcherFixedTools 零 ListFriends")
    assert(!AgentCore.BaseTools.contains("ListFriends"), "BaseTools 零 ListFriends（不得进全 agent 面）")

  test("L2：防声明逃逸单点——NebulaExclusiveTools 含之，dream 无豁免，其余身份剥全集"):
    assert(AgentCore.NebulaExclusiveTools.contains("ListFriends"),
      "ListFriends 进 NebulaExclusiveTools（runtime 剥离 + AgentLibrary 面板/保存侧 strip 共用单点）")
    assert(!AgentCore.exclusiveToolsFor("Nebula").contains("ListFriends"),
      "Nebula 自身无剥离（静态集单点授能）")
    assert(AgentCore.exclusiveToolsFor("general").contains("ListFriends"),
      "其余身份剥全集 ⇒ general 声明无效")
    assert(AgentCore.exclusiveToolsFor("dream").contains("ListFriends"),
      "dream 无豁免（豁免面恰 MemoryEdit 一件）⇒ dream 声明无效")
    assert(!AgentCore.DreamAdmittedTools.contains("ListFriends"), "DreamAdmittedTools 不含 ListFriends")
    // legacy 面（team / flow / catch-all）同样零 ListFriends：机制固定集 = NebulaOrchestrationTools 单点
    assert(!AgentCore.fixedToolsFor(mkDef("member", List("*")).copy(category = "team")).contains("ListFriends"),
      "legacy team 成员不携带")
    assert(!AgentCore.fixedToolsFor(mkDef("leaf", List("*")).copy(category = "flow")).contains("ListFriends"),
      "legacy flow 节点不携带")
    assert(!AgentCore.fixedToolsFor(mkDef("standalone-x", List("*"))).contains("ListFriends"),
      "legacy catch-all 不携带（BaseTools 面）")

  // ══════════ 4. 调用面 ══════════

  test("调用面：friends service 缺席 → 显式 ToolError（不得静默返回空名册）"):
    ListFriendsTool.call(emptyInput, ctx(None)).map { res =>
      assert(res.isLeft, "服务缺席必须失败")
      val msg = res.left.toOption.get.message
      assert(msg.contains("unavailable"), s"错误文案须说明不可用，got: $msg")
      assert(!msg.startsWith("Friends:"), "不得以名册形态返回")
    }

  test("调用面：零参数工具可调用（空输入对象与未知参数均不阻断）"):
    withStub(Right(emptyRoster)) { (fs, _) =>
      ListFriendsTool.call(emptyInput, ctx(Some(fs))).flatMap { empty =>
        val withUnknown =
          JsonObject("filter" -> "lin".asJson, "limit" -> 5.asJson)
        ListFriendsTool.call(withUnknown, ctx(Some(fs))).map { extra =>
          assertEquals(empty.isRight, true, "空输入对象是合法输入（零参数，无缺参失败路径）")
          assertEquals(extra.isRight, true, "未知参数被忽略（无 filter/limit 路径；schema 零参数）")
          assertEquals(extra, empty, "未知参数不改变结果")
        }
      }
    }

  test("L6：未登录 → 「读不到」（显式错误），不是空名册"):
    val notLoggedIn = new FriendService(IO.pure(Option.empty[NeblinkClient]), AgentMessagingConfig())
    ListFriendsTool.call(emptyInput, ctx(Some(notLoggedIn))).map { res =>
      assert(res.isLeft, "未登录必须失败")
      val msg = res.left.toOption.get.message
      assert(msg.contains("Unable to read the friend list"), s"须显式说读不到，got: $msg")
      assert(!msg.contains("no accepted friendships"), "不得借用空名册文案")
      assert(!msg.startsWith("Friends:"), "不得以名册形态返回")
    }

  test("L6：上游故障 → 「读不到」，与空名册严格区分（取数走 listFriends 分态穿透）"):
    withStub(Left("upstream friends endpoint failed (simulated 502)")) { (fs, _) =>
      ListFriendsTool.call(emptyInput, ctx(Some(fs))).map { res =>
        assert(res.isLeft, "上游故障必须上抛（refreshFriends 的折叠版会谎报成空名册）")
        val msg = res.left.toOption.get.message
        assert(msg.contains("upstream friends endpoint failed"), s"上游错误须透传，got: $msg")
        assert(!msg.contains("no accepted friendships"), "上游故障 ≠ 空名册（L6 红判据）")
      }
    }

  test("调用面：空名册 → Right，首行计数行 + 显式空名册说明"):
    withStub(Right(emptyRoster)) { (fs, _) =>
      ListFriendsTool.call(emptyInput, ctx(Some(fs))).map { res =>
        assert(res.isRight, s"空名册是成功结果，got: ${res.left.toOption.map(_.message)}")
        val lines = res.toOption.get.linesIterator.toList
        assertEquals(lines.head, "Friends: 0", "首行恒为计数行")
        assertEquals(lines(1), "The friend list is empty (no accepted friendships).",
          "空名册显式说明（与「读不到」区分）")
        assertEquals(lines.size, 2)
      }
    }

  test("调用面：正常名册 → 首行计数 + 逐行 == FriendRoster.candidateLine（blocked 标记在位）"):
    withStub(Right(twoFriends)) { (fs, _) =>
      ListFriendsTool.call(emptyInput, ctx(Some(fs))).map { res =>
        val out = res match
          case Right(v) => v
          case Left(e)  => fail(s"expected Right, got ${e.message}")
        val lines = out.linesIterator.toList
        assertEquals(lines.head, "Friends: 2")
        assertEquals(lines(1), "林小满 (lin@example.com) [blocked]")
        assertEquals(lines(2), "王选 (wangxuan)")
        assertEquals(lines.size, 3)
        // 逐行 == candidateLine（成功路径与失败路径同一套词表）
        assertEquals(lines.tail,
          List(FriendRoster.candidateLine(FriendSummary("u1", "lin@example.com", "林小满", blocked = Some(true))),
            FriendRoster.candidateLine(FriendSummary("u2", "wangxuan", "王选"))))
      }
    }

  test("L4③：名册行与 SendMessage 失败候选文案逐字同形（同 FriendRoster.candidateLine）"):
    val friends = List(
      FriendSummary("u1", "lin@example.com", "林小满", blocked = Some(true)),
      FriendSummary("u2", "wangxuan", "王选")
    )
    val row = FriendRoster.candidateLine(friends.head)
    assertEquals(row, "林小满 (lin@example.com) [blocked]")
    val roster = ListFriendsTool.render(friends)
    assert(roster.linesIterator.contains(row), s"名册行必须逐字 == candidateLine，got:\n$roster")
    val sendMsgErr = FriendMessageTool.resolveFriend("不存在", friends).left.toOption.get.message
    assert(sendMsgErr.contains(row), s"SendMessage 失败候选必须含同形行，got: $sendMsgErr")
    // 未拉黑条目（blocked 缺席）：两侧同样是既有无后缀字面（零行为变更）
    val plain = FriendRoster.candidateLine(FriendSummary("u2", "wangxuan", "王选"))
    assertEquals(plain, "王选 (wangxuan)")

  test("L4①（⑦ 待落地欠项）：remark 预留槽与 displayName 可区分——本批不加任何模型字段"):
    val f = FriendSummary("u1", "lin@example.com", "林小满")
    // 本批无模型字段 ⇒ 数据面恒走默认 None（ListFriends / SendMessage 两侧都不传 remark）
    assertEquals(FriendRoster.candidateLine(f), "林小满 (lin@example.com)")
    // 预留槽的形状：备注不顶替显示名，两者可区分（⑦ 落地后由此入参供水）
    assertEquals(FriendRoster.candidateLine(f, Some("老林")), "林小满 (lin@example.com) [remark: 老林]")
    assertEquals(FriendRoster.candidateLine(f.copy(blocked = Some(true)), Some("老林")),
      "林小满 (lin@example.com) [remark: 老林] [blocked]")
    assertEquals(FriendRoster.candidateLine(f, Some("")), "林小满 (lin@example.com)", "空备注不渲染")

  test("L5：名册输出与 description 均不含在线/可达/最后在线字段"):
    val roster = ListFriendsTool.render(
      List(FriendSummary("u1", "lin@example.com", "林小满", blocked = Some(true)))
    )
    List("online", "reachable", "lastSeen", "last_seen", "presence").foreach { forbidden =>
      assert(!roster.toLowerCase.contains(forbidden.toLowerCase), s"名册输出不得含 $forbidden（数据面无该字段）")
    }
    val d = ListFriendsTool.description
    assert(d.contains("No online / reachable / last-seen state is reported"),
      "description 必须显式声明不报告在线/可达状态")
    assert(d.contains("Do not assume a friend is reachable from this list"),
      "description 必须含硬句子（L5/L8）")

  test("超长名册：代码内封顶 + 显式尾行（计数行仍报总数）"):
    val n = ListFriendsTool.MaxRows
    val many = (1 to n + 3).toList.map(i => FriendSummary(s"u$i", s"user$i", s"好友$i"))
    val out = ListFriendsTool.render(many)
    val lines = out.linesIterator.toList
    assertEquals(lines.head, s"Friends: ${n + 3}", "计数行报总数（封顶不让模型误判好友总数）")
    assertEquals(lines.size, 2 + n, "＝计数行 + MaxRows 行 + 尾行")
    assertEquals(lines.last, "... 3 more friends not shown", "显式尾行（方案 §4.5 定稿词形）")
    assertEquals(lines(1), FriendRoster.candidateLine(many.head))
    assert(out.contains(s"好友$n"), s"第 MaxRows 件仍在输出内（封顶含端点）")
    assert(!out.contains(s"好友${n + 1}"), "越界者不进输出")
    // 边界：恰 MaxRows 件 ⇒ 无尾行
    val exact = ListFriendsTool.render(many.take(n))
    assertEquals(exact.linesIterator.size, 1 + n, "恰 MaxRows 件不产生尾行")
    assert(!exact.contains("more friends not shown"))

  test("L7：只读零副作用——恰一次 listFriends，写面 / 会话面 / 消息面零触碰"):
    withStub(Right(twoFriends)) { (fs, stub) =>
      ListFriendsTool.call(emptyInput, ctx(Some(fs))).map { res =>
        assert(res.isRight)
        assertEquals(stub.calls.toList, List("listFriends"),
          "唯一一次调用 = 读名册（不刷本地态 / 不动未读游标 / 不触发任何写——写面与会话/消息面被遮蔽记账，触碰即失败）")
      }
    }

  // ══════════ 5. description 契约 ══════════

  test("description：好友 ≠ 设备 + 用途 + 各段齐 + blocked 语义 + 只读零副作用 + SendMessage 复用"):
    val d = ListFriendsTool.description
    assert(d.contains("Friends are NOT devices"), "摘要段必须写清好友 ≠ 设备")
    assert(d.contains("a device is one of the user's own other machines on the same NebLink account"),
      "设备定义（同账号的其它机器）")
    assert(d.contains("this tool never lists"), "明写本工具永不列出设备")
    assert(d.contains("before calling SendMessage"), "用途：发消息前先看谁能发")
    assert(d.contains("## Output"), "Output 段在位")
    assert(d.contains("## Notes"), "Notes 段在位")
    assert(d.contains("<display name> (<username>)"), "行形写清")
    assert(d.contains("... N more friends not shown"), "封顶尾行写清")
    assert(d.contains("no accepted friendships"), "空名册文案写清")
    assert(d.contains("unreadable roster"), "读不到与空名册分别报告")
    assert(d.contains("[blocked]"), "blocked 语义位在位")
    assert(d.contains("read-only") && d.contains("does not refresh local state"),
      "只读零副作用（不刷本地态 / 不动未读游标 / 不触发写）")
    assert(d.contains("`SendMessage`'s `to`") || d.contains("SendMessage"), "本列表的值原样交给 SendMessage 的 to")

end ListFriendsToolRegistrationSpec
