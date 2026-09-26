package nebflow.neblink

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite
import nebflow.actor.AgentDef
import nebflow.agent.*
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.*
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*
import nebflow.neblink.FriendCodecs.given

import scala.concurrent.duration.*

/**
 * SendMessage 群寻址面**端到端**钉子（gmsgsend 批，2026-09-15 · 补充卡
 * §6.1–§6.4 + §8 判红三面 + §10 判据①③）。
 *
 * 判据（逐条对应卡 §10）：
 *  - **判据 1**：`SendMessage(to="group:<群名>")` ⇒ 上游收到
 *    `POST /api/groups/<群会话 id>/messages` 且体带 `"origin":"agent"`；回执成功。
 *    （🔴 改前不可满足：`group:` 不被解析 ⇒ 走好友支 ⇒ 上游零群发请求。）
 *  - **判据 4（§6.4 错误词表）**：群不存在 / 已解散 / 非成员 / 长度 / 限速 ⇒ 逐条
 *    **显式**原因，且一律以 `Group message NOT sent` 起头（🔴 禁静默不达）。
 *  - **§8.1(c) 写点唯一性**：`origin = Some("agent")` 全树命中 = FriendService 内
 *    单聊两处 + 群版一处，无第四处（静态读数，同 `FriendRosterSinglePointSpec` 的哨兵形态）。
 *  - **§8 权限面**：三闸的**权威**在服务端（本层零复制）⇒ 本 spec 钉的是「服务端语义码
 *    被逐条转写成可判读回执」，不是「本层复现了闸」。
 *
 * 装配形态（与 `FriendMessageToolSpec` / `SendMessageAskConfirmSpec` 同款）：
 * 传输缝 stub（零网络，记录上游请求） + 真实 `FriendMessageTool.call`；确认卡判据另起
 * 一个真实 hub 夹具（帧面读数）。
 */
class GroupSendMessageSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  // ── 群表 / 群发的标准应答（形态逐字取自跨仓 `model.rs` / `groups.rs`）─────

  private val GroupsJson: String =
    """[{"groupId":"grp-1","title":"团队","role":"owner","memberCount":2,"lastMessage":null,"unreadCount":0,"lastMessageId":0,"createdAt":1,"selfUserId":"u1"},
       | {"groupId":"grp-2","title":"家庭","role":"member","memberCount":3,"lastMessage":null,"unreadCount":0,"lastMessageId":0,"createdAt":2,"selfUserId":"u1"}]
       |""".stripMargin.replaceAll("\\n\\s*", "")

  private val SendOk: (Int, String) =
    (
      201,
      """{"messageId":5,"conversationId":"grp-1","createdAt":1234567899,"createdAtMs":1234567899000,"existing":false}"""
    )

  /** 传输缝 stub（零网络）：记录群发请求（方法/URL/体）与群表取数。 */
  private class StubClient(
    groupsJson: String = GroupsJson,
    groupsReadFails: Boolean = false,
    send: (Int, String) = SendOk
  ):
    val groupSends = scala.collection.mutable.ListBuffer.empty[(String, String, String)]
    val gets = scala.collection.mutable.ListBuffer.empty[String]

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
        else if url.endsWith("/api/groups") && method == "GET" then
          IO { gets += url } *> IO.pure(
            if groupsReadFails then Left("""HTTP 500: {"error":"internal"}""") else Right(groupsJson)
          )
        else if url.contains("/api/conversations/") && url.contains("/messages") then
          IO { gets += url } *> IO.pure(Right("[]"))
        else IO.pure(Left(s"unexpected request: $method $url"))

      override protected def sendRequestJsonWithStatus(
        method: String,
        url: String,
        body: String,
        token: Option[String],
        timeout: FiniteDuration
      ): IO[Either[String, (Int, String)]] =
        IO { groupSends += ((method, url, body)) } *> IO.pure(Right(send))

    def login(): Unit =
      client
        .login("dev-1", "TestMac", "macos", List(NeblinkEndpoint("10.0.0.5", 1, "lan")))
        .unsafeRunSync()

  end StubClient

  private def withFs[A](
    stub: StubClient,
    mode: String = "auto",
    guard: FriendMessagingGuard = new FriendMessagingGuard(),
    askConfirm: Option[String => IO[Boolean]] = None
  )(use: FriendService => IO[A]): IO[A] =
    IO.delay {
      stub.login()
      new FriendService(
        IO.pure(Some(stub.client)),
        AgentMessagingConfig(mode = mode),
        guard = guard,
        askConfirm = askConfirm
      )
    }.flatMap(use)

  private def callTool(fs: FriendService, input: JsonObject): IO[Either[ToolError, String]] =
    FriendMessageTool.initialize(fs)
    FriendMessageTool.call(input, ToolContext(projectRoot = "/tmp"))

  private def callToolWithCtx(
    fs: FriendService,
    ctx: ToolContext,
    input: JsonObject
  ): IO[Either[ToolError, String]] =
    FriendMessageTool.initialize(fs)
    FriendMessageTool.call(input, ctx)

  private def groups: List[GroupSummary] = List(
    GroupSummary(groupId = "grp-1", title = "团队", role = "owner", memberCount = 2),
    GroupSummary(groupId = "grp-2", title = "家庭", role = "member", memberCount = 3)
  )

  // ═══════════ §6.2 群目标解析（与好友面同构） ═══════════

  test("§6.2 L1：群 id 精确（大小写不敏感）") {
    assertEquals(FriendRoster.resolveGroup("grp-1", groups).map(_.title), Right("团队"))
    assertEquals(FriendRoster.resolveGroup("GRP-2", groups).map(_.title), Right("家庭"))
  }

  test("§6.2 L2：群名精确（大小写不敏感仅限 id；名按原样 NOCASE 比较）") {
    assertEquals(FriendRoster.resolveGroup("团队", groups).map(_.groupId), Right("grp-1"))
    assertEquals(FriendRoster.resolveGroup("家庭", groups).map(_.groupId), Right("grp-2"))
  }

  test("§6.2 L3：群名唯一前缀 ⇒ 命中；多命中 ⇒ 候选列表（禁静默首命中）") {
    val many = groups :+ GroupSummary(groupId = "grp-3", title = "团建群")
    assertEquals(FriendRoster.resolveGroup("家", many).map(_.groupId), Right("grp-2"))
    val amb = FriendRoster.resolveGroup("团", many).left.toOption
    assert(amb.isDefined, "前缀命中两个群必须报错")
    assert(amb.get.message.contains("ambiguous"), s"必须明说歧义: ${amb.get.message}")
    assert(amb.get.message.contains("grp-1") && amb.get.message.contains("grp-3"), "候选必须带群 id")
    assert(amb.get.message.contains("use the exact group id"), "必须给出消歧手段")
  }

  test("§6.2 零命中 ⇒ not-found + 可用群表（同一套词表）") {
    val err = FriendRoster.resolveGroup("不存在", groups).left.toOption
    assert(err.isDefined)
    assert(err.get.message.contains("not found"), s"零命中必须报 not found: ${err.get.message}")
    assert(err.get.message.contains("团队") && err.get.message.contains("家庭"), "可用表必须列全")
    assert(err.get.message.contains("Available groups: "), "同族可用表词表")
  }

  test("§6.2 空 query / 空群表：两态分别报告（不得含混）") {
    assert(FriendRoster.resolveGroup("   ", groups).isLeft)
    val emptyErr = FriendRoster.resolveGroup("团队", Nil).left.toOption.get
    assert(
      emptyErr.message.contains("The group list is empty (you are not a member of any group)."),
      s"空群表必须说清是「你没有群」: ${emptyErr.message}"
    )
  }

  test("§6.2 候选行形态：<群名> (<群 id>)——两键可区分（群名可重名）") {
    assertEquals(FriendRoster.groupCandidateLine(groups.head), "团队 (grp-1)")
    assertEquals(FriendRoster.groupCandidates(groups), "团队 (grp-1), 家庭 (grp-2)")
  }

  test("§6.2 GroupSummary 解码：解析键硬、加性键宽容（selfUserId 缺席不破解码）") {
    import io.circe.parser.decode
    // 加性键全缺（老上游形态）⇒ 仍可解码，两个解析键在位
    val minimal = decode[List[GroupSummary]]("""[{"groupId":"grp-9","title":"老群"}]""")
    assertEquals(minimal.map(_.head.title), Right("老群"))
    assertEquals(minimal.map(_.head.selfUserId), Right(""))
    // 缺解析键 ⇒ 硬失败（不得退化成空串参与 L1 匹配）
    assert(decode[List[GroupSummary]]("""[{"title":"无 id"}]""").isLeft, "缺 groupId 必须硬失败")
    assert(decode[List[GroupSummary]]("""[{"groupId":"grp-9"}]""").isLeft, "缺 title 必须硬失败")
  }

  // ═══════════ 判据 1：group: 寻址 ⇒ 上游群发 + origin=agent ═══════════

  test("判据 1：to=group:<群名> ⇒ POST /api/groups/grp-1/messages 且体带 origin=agent（mode=auto）") {
    val stub = StubClient()
    withFs(stub) { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi group".asJson)).map { res =>
        assert(res.isRight, s"群发必须成功，实际 = ${res.left.toOption.map(_.message)}")
        assert(res.toOption.get.contains("已发送到群「团队」"), s"回执必须点名群: ${res.toOption.get}")
        assertEquals(stub.groupSends.size, 1, "恰一次上游群发")
        val (m, url, body) = stub.groupSends.head
        assertEquals(m, "POST")
        assertEquals(url, "http://stub.local/api/groups/grp-1/messages", "寻址段必须用**群会话 id**")
        val json = io.circe.parser.parse(body).getOrElse(fail(s"请求体非法 JSON: $body"))
        assertEquals(json.hcursor.get[String]("origin").toOption, Some("agent"), "🔴 agent 标识必须落 wire")
        assertEquals(json.hcursor.get[String]("body").toOption, Some("hi group"))
        assert(!body.contains("attachments"), "一期纯文本 ⇒ 不带 attachments 键")
      }
    }
  }

  test("判据 1 补：群 id 也可直接寻址；群会话 id 回填补拉（会话域与单聊同源）") {
    val stub = StubClient()
    withFs(stub) { fs =>
      callTool(fs, JsonObject("to" -> "group:grp-1".asJson, "message" -> "by id".asJson)).map { _ =>
        assertEquals(stub.groupSends.map(_._2).toList, List("http://stub.local/api/groups/grp-1/messages"))
        assert(
          stub.gets.exists(_.contains("/api/conversations/grp-1/messages")),
          s"补拉必须打在群会话 id 上，实际 = ${stub.gets.toList}"
        )
      }
    }
  }

  test("§6.4：群列表读不到 ⇒ 显式报错（**不得**折成「你没有群」）") {
    val stub = StubClient(groupsReadFails = true)
    withFs(stub) { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi".asJson)).map { res =>
        val msg = res.left.toOption.map(_.message).getOrElse("")
        assert(msg.contains("could not be loaded"), s"读不到必须显式: $msg")
        assert(!msg.contains("group list is empty"), "🔴 读不到 ≠ 你没有群")
        assertEquals(stub.groupSends.size, 0, "解析失败 ⇒ 零上游发送")
      }
    }
  }

  test("§6.4：`group:` 残缺前缀 ⇒ 工具侧显式错误（零上游）") {
    val stub = StubClient()
    withFs(stub) { fs =>
      callTool(fs, JsonObject("to" -> "group:".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.left.toOption.map(_.message).getOrElse("").contains("missing the group name/id"))
        assertEquals(stub.groupSends.size, 0)
      }
    }
  }

  test("§6.1 一期纯文本：群 + attachments ⇒ 显式拒绝（禁静默丢弃附件）") {
    val stub = StubClient()
    withFs(stub) { fs =>
      callTool(
        fs,
        JsonObject(
          "to" -> "group:团队".asJson,
          "message" -> "hi".asJson,
          "attachments" -> List("/tmp/a.txt").asJson
        )
      ).map { res =>
        val msg = res.left.toOption.map(_.message).getOrElse("")
        assert(msg.contains("TEXT ONLY"), s"必须显式说明一期纯文本: $msg")
        assert(msg.contains("nothing was sent"), s"必须显式说明零投递: $msg")
        assertEquals(stub.groupSends.size, 0)
      }
    }
  }

  test("群支空正文被拒（群总是要正文；好友支的「空正文 + 附件」特例不适用于群）") {
    val stub = StubClient()
    withFs(stub) { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "".asJson)).map { res =>
        assert(res.left.toOption.map(_.message).getOrElse("").contains("'message' is empty"))
        assertEquals(stub.groupSends.size, 0)
      }
    }
  }

  // ═══════════ §6.4 错误词表（逐条） ═══════════

  List(
    ("群不存在", 404, "group_not_found", "does not exist"),
    ("群已解散", 403, "group_disbanded", "disbanded"),
    ("非成员", 403, "not_member", "not a member"),
    ("长度非法", 422, "invalid_length", "length"),
    ("origin 非法", 422, "invalid_origin", "origin"),
    ("服务端限速", 429, "rate_limited", "rate limit")
  ).foreach { case (label, code, errCode, expect) =>
    test(s"§6.4 错误词表：$label ⇒ 显式原因（非静默）") {
      val stub = StubClient(send = (code, s"""{"error":"$errCode"}"""))
      withFs(stub) { fs =>
        callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi".asJson)).map { res =>
          val msg = res.left.toOption.map(_.message).getOrElse(fail(s"$label 必须是失败"))
          assert(msg.startsWith("Group message NOT sent"), s"🔴 禁静默/禁伪装成功: $msg")
          assert(msg.contains(expect), s"$label 必须带具体原因（期望含 '$expect'）: $msg")
        }
      }
    }
  }

  test("§6.4：未知语义码 / 非 JSON 体 ⇒ 原样带出（不猜、不吞）") {
    val stub = StubClient(send = (418, """{"error":"teapot"}"""))
    val stub2 = StubClient(send = (500, "<html>gateway</html>"))
    withFs(stub) { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.left.toOption.get.message.contains("teapot"))
      }
    } *> withFs(stub2) { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi".asJson)).map { res =>
        val msg = res.left.toOption.get.message
        assert(msg.contains("HTTP 500") && msg.contains("html"), s"非 JSON 体原样带出: $msg")
      }
    }
  }

  // ═══════════ §6.1 档位 / 限速（与好友腿同层同语义） ═══════════

  test("mode=off：群发直拒，零上游") {
    val stub = StubClient()
    withFs(stub, mode = "off") { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.left.toOption.map(_.message).getOrElse("").toLowerCase.contains("disabled"))
        assertEquals(stub.groupSends.size, 0)
      }
    }
  }

  test("ask 档：拒绝 ⇒ 零投递 + 可判读文案") {
    val stub = StubClient()
    withFs(stub, mode = "ask", askConfirm = Some(_ => IO.pure(false))) { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi".asJson)).map { res =>
        assertEquals(res.left.toOption.map(_.message), Some("User declined the message"))
        assertEquals(stub.groupSends.size, 0)
      }
    }
  }

  test("ask 档：批准 ⇒ 投递（确认链与好友腿同一接线段）") {
    val stub = StubClient()
    withFs(stub, mode = "ask", askConfirm = Some(_ => IO.pure(true))) { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.isRight, s"批准后必须投递: $res")
        assertEquals(stub.groupSends.size, 1)
      }
    }
  }

  test("ask 档：确认链异常 ⇒ 既不投递、也不伪装成用户拒绝（fail-closed）") {
    val stub = StubClient()
    withFs(stub, mode = "ask", askConfirm = Some(_ => IO.raiseError(new RuntimeException("no surface")))) { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi".asJson)).map { res =>
        val msg = res.left.toOption.map(_.message).getOrElse("")
        assert(msg.contains("NOT sent") && msg.contains("no surface"), s"归因必须可区分: $msg")
        assert(msg != "User declined the message", "确认链故障 ≠ 用户拒绝")
        assertEquals(stub.groupSends.size, 0)
      }
    }
  }

  test("auto 超限 ⇒ 降级 ask（不是硬失败）；拒绝则零投递（§5.5 超限语义）") {
    val stub = StubClient()
    // per-target 桶上限置 0 ⇒ 第一次调用即超限 ⇒ 必须降级到确认卡
    val guard = new FriendMessagingGuard(perFriendPerHour = 0)
    withFs(stub, mode = "auto", guard = guard, askConfirm = Some(_ => IO.pure(false))) { fs =>
      callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "hi".asJson)).map { res =>
        assertEquals(res.left.toOption.map(_.message), Some("User declined the message"), "超限必须降级 ask 而非硬失败")
        assertEquals(stub.groupSends.size, 0)
      }
    }
  }

  test("§5.5 桶键 = 群会话 id：两个不同群各自独立计数（同全局桶）") {
    val stub = StubClient()
    val guard = new FriendMessagingGuard(perFriendPerHour = 1)
    withFs(stub, mode = "auto", guard = guard) { fs =>
      for
        r1 <- callTool(fs, JsonObject("to" -> "group:团队".asJson, "message" -> "one".asJson))
        r2 <- callTool(fs, JsonObject("to" -> "group:家庭".asJson, "message" -> "two".asJson))
      yield
        assert(r1.isRight, s"第一个群首条必须放行: $r1")
        assert(r2.isRight, s"另一个群有自己的 per-target 桶: $r2")
        assertEquals(stub.groupSends.map(_._2).toList.distinct.size, 2)
    }
  }

  // ═══════════ §8.1(c) 写点唯一性（静态读数） ═══════════

  test("§8.1(c)：**wire origin 写点**（去注释后）= FriendService 单聊两处 + 群版一处，无第四处") {
    // 判据口径（比卡 §8.1(c) 的裸 grep 更严）：
    //  ① **去注释**后再扫 —— 文档里提到 `Some("agent")` 不算写点（裸 grep 会把注释算进去，
    //     从而把「写点」与「提到写点」混为一谈）；
    //  ② 扫描面限定 `nebflow/neblink/**` = **wire origin 所在的发送面** —— 全树裸 grep
    //     还会命中与 wire origin 无关的同名字面量（`UsageRecordStore` 的 LLM 用量维度
    //     分组 `Some("agent")`，本批未触碰、与本面正交）。
    val scalaRoot = os.pwd / "src" / "main" / "scala"
    def stripComments(src: String): String =
      src.replaceAll("(?s)/\\*.*?\\*/", "").linesIterator.map(l => l.split("//", 2).head).mkString("\n")
    val hits = os
      .walk(scalaRoot / "nebflow")
      .filter(os.isFile)
      .filter(_.ext == "scala")
      .toList
      .flatMap { p =>
        val rel = p.relativeTo(scalaRoot).toString.replace(java.io.File.separatorChar, '/')
        if !rel.startsWith("nebflow/neblink/") then Nil
        else
          stripComments(os.read(p)).linesIterator.zipWithIndex.collect {
            case (line, idx) if line.contains("""Some("agent")""") => rel -> (idx + 1)
          }
      }
    assertEquals(
      hits.map(_._1).distinct,
      List("nebflow/neblink/FriendService.scala"),
      s"agent 标识写点必须只在 FriendService 单点，实际 = $hits"
    )
    assertEquals(hits.size, 3, s"单聊文本 + 单聊附件 + 群发 = 3（禁第四处），实际 = $hits")
  }

  // ═══════════ print/确认卡面：真实 hub 夹具（帧面读数） ═══════════

  private val RootSid = "root-grp-1"

  /** 从不被调用的 LLM（仅满足 `SharedResources` 构造）。 */
  private object DeadLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("llm not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] = Stream.empty

  private final case class Fixture(
    fs: FriendService,
    ctx: ToolContext,
    frames: Ref[IO, List[Json]],
    stub: StubClient,
    cleanup: IO[Unit]
  )

  /** 真实 hub + 真实装配缝（`askConfirm = SendConfirm.production`，与 `GatewayMain` 同字面）。 */
  private def withHubFixture[A](mode: String, stub: StubClient)(use: Fixture => IO[A]): IO[A] =
    val system = nebflow.actor.ActorSystem(s"gmsgsend-${System.nanoTime()}")
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "data")
    val program = for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
      hubRef <- IO.ref(Option.empty[nebflow.actor.ActorRef[InteractionHubCommand]])
      resources = SharedResources(
        llm = DeadLlm,
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
        voiceMutedRef = voiceMuted,
        interactionHubRef = hubRef
      )
      frames <- IO.ref(List.empty[Json])
      hub <- system.spawn(InteractionHub(), s"hub-${System.nanoTime()}")
      _ <- hubRef.set(Some(hub))
      _ <- hub ! InteractionHubCommand.RegisterRoot(RootSid, (j: Json) => frames.update(_ :+ j))
      _ <- IO(stub.login())
      fs = NeblinkWiring.friendService(
        IO.pure(Some(stub.client)),
        AgentMessagingConfig(mode = mode),
        askConfirm = Some(SendConfirm.production)
      )
      ctx = ToolContext(
        projectRoot = tmp.toString,
        sessionId = Some(RootSid),
        rootSessionId = Some(RootSid),
        sessionName = Some("spec"),
        agentDef = Some(AgentDef(name = "Nebula", description = "spec fixture")),
        sharedResources = Some(resources),
        actorSystem = Some(system)
      )
    yield Fixture(fs, ctx, frames, stub, system.stopAll.attempt.void *> IO(os.remove.all(tmp)).attempt.void)
    program.flatMap(f => use(f).guarantee(f.cleanup))

  end withHubFixture

  private def isAskUser(j: Json): Boolean =
    j.hcursor.get[String]("type").toOption.contains("askUser")

  test("§6.1 确认卡（ask 档）：卡面目标名 = **群名**；超时 ⇒ 零投递 + 显式失败（禁静默直发）") {
    val prev = Option(System.getProperty(SendConfirm.TimeoutProperty))
    System.setProperty(SendConfirm.TimeoutProperty, "1500")
    val stub = StubClient()
    withHubFixture("ask", stub) { f =>
      callToolWithCtx(f.fs, f.ctx, JsonObject("to" -> "group:团队".asJson, "message" -> "hi from spec".asJson)).flatMap {
        res =>
          f.frames.get.map { evs =>
            val asks = evs.filter(isAskUser)
            assertEquals(asks.size, 1, s"群发 ask 档必须出恰一张确认卡（帧面 = $evs）")
            val q = asks.head.hcursor
              .downField("items")
              .downArray
              .downField("question")
              .as[String]
              .toOption
              .getOrElse("")
            assert(q.contains("团队"), s"🔴 卡面目标名必须是群名（A④）: $q")
            assert(q.contains("hi from spec"), s"卡面必须带正文预览: $q")
            assert(res.isLeft, "确认未回（超时）⇒ 必须显式失败")
            assert(res.left.toOption.get.message.contains("NOT sent"), s"超时文案必须显式不投递: $res")
            assertEquals(stub.groupSends.size, 0, "🔴 未确认 ⇒ 零投递（禁静默直发）")
          }
      }
    }.guarantee(
      IO.delay(
        prev.fold(System.clearProperty(SendConfirm.TimeoutProperty))(v =>
          System.setProperty(SendConfirm.TimeoutProperty, v)
        )
      )
    )
  }

end GroupSendMessageSpec
