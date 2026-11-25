package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ScheduleTool, ToolContext, ToolError}
import nebflow.core.compact.HistoryArchiver
import nebflow.llm.ModelCandidate
import nebflow.shared.ThinkingConfig

/**
 * 待办数据模型与命名规范批（2026-11-18 作者令）**行为闸**。
 *
 * 作者原话（逐字）：「1.是把定时任务融合到这里面 2.是我创建的叫提醒 是给我自己用的
 * 3.你创建的是待办和定时任务 然后需要决策的是待办 然后每个待办必须要有选项」；
 * 17:52 追加：「……要能填充图片和视频 并且支持多张叠卡显示 …… 然后这个也可以给待办应用上」。
 *
 * 本 spec 钉死四组：
 *  ① 三面判定（[[TodoTaxonomy.classify]]）——提醒/待办/定时任务/镜像的判定依据
 *     = kind + source，单点；
 *  ② **「无选项的待办创建必须被拒」**（本批主线红点）——写侧（`Schedule` 工具面，
 *     source="tool" ⇒ 待办）返回 Left 且**零落库**；带选项则成功且选项落库；
 *  ③ 媒体形态——选项可挂 0 张（合法）、多张（叠卡）、非法 kind/ref/refType 被拒；
 *  ④ 归一化——空白选项 id 补齐、title 为空的选项被丢弃（手改文件防线）。
 *
 * 破坏本闸的办法（自证检测力）：删掉 `ScheduleTool.call` 里
 * `TodoTaxonomy.validateCreate` 那一步 ⇒ ② 立即红。
 */
class TodoTaxonomySpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-todo-taxonomy"
  private val taskStore = new ScheduledTaskStore(tempRoot / "scheduled-tasks")

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot / "scheduled-tasks") }

  // ------------------------------------------------------------------
  // ① 三面判定
  // ------------------------------------------------------------------

  test("① 判定：kind=todo + source=user ⇒ 提醒（作者自建，选项不必备）"):
    assertEquals(TodoTaxonomy.classify(Some("todo"), Some("user")), TodoClass.Reminder)
    assert(!TodoTaxonomy.requiresOptions(TodoClass.Reminder))
    assertEquals(TodoClass.wire(TodoClass.Reminder), "reminder")
    assertEquals(TodoClass.zhLabel(TodoClass.Reminder), "提醒")

  test("① 判定：kind=todo + source=tool ⇒ 待办（agent 派发，选项必备）"):
    assertEquals(TodoTaxonomy.classify(Some("todo"), Some("tool")), TodoClass.Todo)
    assert(TodoTaxonomy.requiresOptions(TodoClass.Todo))
    assertEquals(TodoClass.wire(TodoClass.Todo), "todo")
    assertEquals(TodoClass.zhLabel(TodoClass.Todo), "待办")

  test("① 判定：kind=todo + source 缺省/未知 ⇒ 待办（缺 source 视为非作者自建）"):
    assertEquals(TodoTaxonomy.classify(Some("todo"), None), TodoClass.Todo)
    assertEquals(TodoTaxonomy.classify(Some("todo"), Some("whatever")), TodoClass.Todo)
    assertEquals(TodoTaxonomy.classify(Some("TODO"), Some("USER")), TodoClass.Reminder, "大小写归一")

  test("① 判定：kind=todo + source=engine ⇒ 引擎镜像（用户面仍显示「待办」）"):
    assertEquals(TodoTaxonomy.classify(Some("todo"), Some("engine")), TodoClass.EngineMirror)
    assert(!TodoTaxonomy.requiresOptions(TodoClass.EngineMirror))
    assertEquals(TodoClass.zhLabel(TodoClass.EngineMirror), "待办")

  test("① 判定：kind≠todo（含 None / \"task\" / 垃圾值）⇒ 定时任务"):
    assertEquals(TodoTaxonomy.classify(None, Some("tool")), TodoClass.ScheduledTask)
    assertEquals(TodoTaxonomy.classify(Some("task"), Some("tool")), TodoClass.ScheduledTask)
    assertEquals(TodoTaxonomy.classify(Some("nonsense"), None), TodoClass.ScheduledTask)
    assert(!TodoTaxonomy.requiresOptions(TodoClass.ScheduledTask))

  test("① 判定：ScheduledTask 实例直读（kind+source 双字段）"):
    val t = ScheduledTask.create("s1", "x", 1L).copy(source = Some("tool"))
    assertEquals(TodoTaxonomy.classify(t), TodoClass.ScheduledTask)

  // ------------------------------------------------------------------
  // ② 校验（写侧拒绝面）
  // ------------------------------------------------------------------

  test("② 待办无 decision ⇒ TODO_OPTIONS_REQUIRED"):
    val res = TodoTaxonomy.validateCreate(Some("todo"), Some("tool"), None)
    res match
      case Left(v) =>
        assertEquals(v.code, TodoTaxonomy.Codes.OptionsRequired)
        assert(v.message.contains("TODO_OPTIONS_REQUIRED"), "错误串必须带码（可机读）")
        assert(v.message.contains("提醒"), "错误串必须给可行动出路（这是提醒就去提醒入口）")
      case Right(_) => fail("无选项的待办必须被拒")

  test("② 待办 decision 在场但 options 为空 ⇒ 同样被拒"):
    val res = TodoTaxonomy.validateCreate(Some("todo"), Some("tool"), Some(TodoDecision(question = "q")))
    assert(res.isLeft, s"空 options 必须被拒，实得 $res")
    assertEquals(res.left.toOption.get.code, TodoTaxonomy.Codes.OptionsRequired)

  test("② 提醒无 decision ⇒ 放行（作者自建不必备选项）"):
    assertEquals(TodoTaxonomy.validateCreate(Some("todo"), Some("user"), None), Right(()))

  test("② 定时任务无 decision ⇒ 放行"):
    assertEquals(TodoTaxonomy.validateCreate(None, Some("tool"), None), Right(()))
    assertEquals(TodoTaxonomy.validateCreate(Some("task"), Some("tool"), None), Right(()))

  test("② 待办带 ≥1 选项 ⇒ 放行"):
    val d = TodoDecision(question = "选哪个", options = List(DecisionOption(id = "o1", title = "合并")))
    assertEquals(TodoTaxonomy.validateCreate(Some("todo"), Some("tool"), Some(d)), Right(()))

  test("② 选项 title 空白 ⇒ TODO_OPTION_TITLE_REQUIRED"):
    val d = TodoDecision(options = List(DecisionOption(id = "o1", title = "  ")))
    val res = TodoTaxonomy.validateCreate(Some("todo"), Some("tool"), Some(d))
    assertEquals(res.left.toOption.map(_.code), Some(TodoTaxonomy.Codes.OptionTitleRequired))

  test("② 选项条数超上限 ⇒ TODO_OPTION_LIMIT"):
    val d = TodoDecision(options = (1 to TodoTaxonomy.MaxOptions + 1).toList.map(i => DecisionOption(s"o$i", s"t$i")))
    val res = TodoTaxonomy.validateCreate(None, Some("tool"), Some(d))
    assertEquals(res.left.toOption.map(_.code), Some(TodoTaxonomy.Codes.OptionLimit))

  // ------------------------------------------------------------------
  // ③ 媒体（17:52 追加令）
  // ------------------------------------------------------------------

  test("③ 选项可无媒体（合法形态，媒体可选）"):
    val d = TodoDecision(options = List(DecisionOption(id = "o1", title = "不带图")))
    assertEquals(TodoTaxonomy.validateCreate(Some("todo"), Some("tool"), Some(d)), Right(()))

  test("③ 一个选项可挂多张媒体（叠卡）"):
    val d = TodoDecision(options =
      List(
        DecisionOption(
          id = "o1",
          title = "看截图再定",
          media = List(
            DecisionMedia(
              DecisionMedia.KindImage,
              ref = "att-1",
              refType = DecisionMedia.RefAttachment,
              role = Some("cover")
            ),
            DecisionMedia(DecisionMedia.KindImage, ref = "att-2", refType = DecisionMedia.RefAttachment),
            DecisionMedia(
              DecisionMedia.KindVideo,
              ref = "/tmp/clip.mp4",
              refType = DecisionMedia.RefPath,
              durationMs = Some(3200)
            )
          )
        )
      )
    )
    assertEquals(TodoTaxonomy.validateCreate(Some("todo"), Some("tool"), Some(d)), Right(()))

  test("③ 媒体 kind 非法 ⇒ TODO_MEDIA_KIND"):
    val d = TodoDecision(options =
      List(DecisionOption("o1", "t", media = List(DecisionMedia("audio", "x", DecisionMedia.RefUrl))))
    )
    assertEquals(
      TodoTaxonomy.validateCreate(None, Some("tool"), Some(d)).left.toOption.map(_.code),
      Some(TodoTaxonomy.Codes.MediaKind)
    )

  test("③ 媒体 ref 空白 ⇒ TODO_MEDIA_REF"):
    val d = TodoDecision(options =
      List(DecisionOption("o1", "t", media = List(DecisionMedia(DecisionMedia.KindImage, "  ", DecisionMedia.RefUrl))))
    )
    assertEquals(
      TodoTaxonomy.validateCreate(None, Some("tool"), Some(d)).left.toOption.map(_.code),
      Some(TodoTaxonomy.Codes.MediaRef)
    )

  test("③ 媒体 refType 非法 ⇒ TODO_MEDIA_REF_TYPE"):
    val d = TodoDecision(options =
      List(DecisionOption("o1", "t", media = List(DecisionMedia(DecisionMedia.KindImage, "x", "ftp"))))
    )
    assertEquals(
      TodoTaxonomy.validateCreate(None, Some("tool"), Some(d)).left.toOption.map(_.code),
      Some(TodoTaxonomy.Codes.MediaRefType)
    )

  test("③ 媒体 validator 不校验可达性（外链 404/路径不存在都放行）"):
    val d = TodoDecision(options =
      List(
        DecisionOption(
          "o1",
          "t",
          media = List(DecisionMedia(DecisionMedia.KindImage, "https://example.invalid/nope.png", DecisionMedia.RefUrl))
        )
      )
    )
    assertEquals(TodoTaxonomy.validateCreate(None, Some("tool"), Some(d)), Right(()))

  test("③ decision JSON 在场但解不出 ⇒ TODO_DECISION_MALFORMED（不是「没写」）"):
    val bad = Json.obj("decision" -> Json.obj("options" -> Json.arr(Json.fromString("o1"))))
    assertEquals(TodoTaxonomy.decodeDecision(bad).left.toOption.map(_.code), Some(TodoTaxonomy.Codes.DecisionMalformed))

  test("③ decision 缺席/null ⇒ Right(None)（合法缺席）"):
    assertEquals(TodoTaxonomy.decodeDecision(Json.obj()), Right(None))
    assertEquals(TodoTaxonomy.decodeDecision(Json.obj("decision" -> Json.Null)), Right(None))

  // ------------------------------------------------------------------
  // ④ 归一化
  // ------------------------------------------------------------------

  test("④ 归一化：空白 id 按位置补、title 空白选项被丢弃、空白可选串归 None"):
    val d = TodoDecision(
      question = "  q  ",
      options = List(
        DecisionOption(
          id = "",
          title = " 第一条 ",
          detail = Some("  "),
          media = List(DecisionMedia(DecisionMedia.KindImage, " a ", DecisionMedia.RefAttachment))
        ),
        DecisionOption(id = "kept", title = "   ", media = Nil)
      )
    )
    val n = TodoTaxonomy.normalizeDecision(d)
    assertEquals(n.question, "q")
    assertEquals(n.options.size, 1)
    assertEquals(n.options.head.id, "o1")
    assertEquals(n.options.head.title, "第一条")
    assertEquals(n.options.head.detail, None)
    assertEquals(n.options.head.media.head.ref, "a")

  // ------------------------------------------------------------------
  // ② 端到端（真写路径）：工具面 kind=todo
  // ------------------------------------------------------------------

  private val wsSent = scala.collection.mutable.ListBuffer.empty[Json]

  private def mkCtx(): ToolContext =
    wsSent.clear()
    val sr = SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "todo-taxonomy-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      scheduledTaskStore = taskStore,
      scheduledTaskService = None
    )
    ToolContext(
      projectRoot = "/tmp",
      sessionId = Some("s-taxonomy"),
      sharedResources = Some(sr),
      wsSend = Some(j => IO(wsSent += j).void)
    )

  end mkCtx

  private def callTool(input: Json): Either[ToolError, String] =
    ScheduleTool.call(input.asObject.get, mkCtx()).unsafeRunSync()

  test("explicitly sourceless tool turn uses the latest reported device with auditable provenance"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision, "triggerAt" -> "2035-01-01T09:00".asJson)
    val report = DeviceZoneReport("browser:test", "Asia/Tokyo", 123L, "takeoverIdentify")
    for
      _ <- reset()
      result <- ScheduleTool.call(input.asObject.get, mkCtx().copy(recentDeviceZone = Some(report), deviceZoneFallbackAllowed = true))
      stored <- taskStore.getAllTodos
    yield
      assert(result.isRight, s"a known recent device should permit creation: $result")
      val audit = stored.head.asJson.hcursor.downField("dueZoneSource")
      assertEquals(audit.get[String]("kind").toOption, Some("recent-device"))
      assertEquals(audit.get[String]("deviceId").toOption, Some("browser:test"))
      assertEquals(audit.get[Long]("reportedAt").toOption, Some(123L))

  test("tool offset with explicit region preserves the input instant and original offset"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision,
      "triggerAt" -> "2035-01-01T09:00:00+08:00[Europe/Paris]".asJson)
    for
      _ <- reset()
      result <- ScheduleTool.call(input.asObject.get, mkCtx())
      stored <- taskStore.getAllTodos
    yield
      assert(result.isRight, s"input offset determines instant: $result")
      val due = stored.head.due.get.asJson.hcursor
      assertEquals(due.get[String]("zoneId").toOption, Some("Europe/Paris"))
      assertEquals(due.get[String]("originalOffset").toOption, Some("+08:00"))
      assertEquals(due.get[Long]("dueAt").toOption, Some(java.time.Instant.parse("2035-01-01T01:00:00Z").toEpochMilli))

  test("tool weekly todo persists a wall-clock rule in the device zone"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision,
      "triggerAt" -> "2035-01-01T09:00".asJson, "repeat" -> "weekly".asJson)
    for
      _ <- reset()
      result <- ScheduleTool.call(input.asObject.get, mkCtx().copy(deviceZone = Some("Asia/Tokyo")))
      stored <- taskStore.getAllTodos
    yield
      assert(result.isRight, s"weekly deadline must be accepted: $result")
      val due = stored.head.due.get.asJson.hcursor
      assertEquals(due.get[String]("type").toOption, Some("weekly"))
      assertEquals(due.get[String]("zoneId").toOption, Some("Asia/Tokyo"))
      assertEquals(due.get[String]("localTime").toOption, Some("09:00"))
      assertEquals(stored.head.nextDueAt, Some(java.time.Instant.parse("2035-01-01T00:00:00Z").toEpochMilli))

  test("tool todo overlap selects earlier instant and exposes chosen offset"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision, "triggerAt" -> "2035-11-04T01:30".asJson)
    for
      _ <- reset()
      result <- ScheduleTool.call(input.asObject.get, mkCtx().copy(deviceZone = Some("America/New_York")))
      stored <- taskStore.getAllTodos
    yield
      assert(result.isRight, s"overlap must resolve: $result")
      assertEquals(stored.head.nextDueAt, Some(java.time.Instant.parse("2035-11-04T05:30:00Z").toEpochMilli))
      assert(result.toOption.get.contains("-04:00"), "the selected overlap offset must appear in the receipt")

  test("tool todo gap resolves to the first valid local instant"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision, "triggerAt" -> "2035-03-11T02:30".asJson)
    for
      _ <- reset()
      result <- ScheduleTool.call(input.asObject.get, mkCtx().copy(deviceZone = Some("America/New_York")))
      stored <- taskStore.getAllTodos
    yield
      assert(result.isRight, s"gap must be resolved: $result")
      assert(result.toOption.get.toLowerCase.contains("adjust"), "gap adjustment must be visible in the receipt")
      assertEquals(stored.head.due.get.asJson.hcursor.get[String]("wallClock").toOption, Some("2035-03-11T02:30"))
      assertEquals(stored.head.nextDueAt, Some(java.time.Instant.parse("2035-03-11T07:00:00Z").toEpochMilli))

  test("tool todo relative tomorrow resolves using its device region"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision, "triggerAt" -> "tomorrow at 09:00".asJson)
    val zone = java.time.ZoneId.of("Asia/Tokyo")
    val expectedDate = java.time.LocalDate.now(zone).plusDays(1)
    for
      _ <- reset()
      result <- ScheduleTool.call(input.asObject.get, mkCtx().copy(deviceZone = Some("Asia/Tokyo")))
      stored <- taskStore.getAllTodos
    yield
      assert(result.isRight, s"tomorrow should resolve in device region: $result")
      assertEquals(stored.size, 1)
      val due = stored.head.due.get.asJson.hcursor
      assertEquals(due.get[Long]("dueAt").toOption, Some(expectedDate.atTime(9, 0).atZone(zone).toInstant.toEpochMilli))

  test("tool todo offset fixes instant but cannot substitute for a device region"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision, "triggerAt" -> "2035-01-01T09:00:00+08:00".asJson)
    for
      _ <- reset()
      missing <- ScheduleTool.call(input.asObject.get, mkCtx())
      result <- ScheduleTool.call(input.asObject.get, mkCtx().copy(deviceZone = Some("Asia/Tokyo")))
      stored <- taskStore.getAllTodos
    yield
      assert(missing.isLeft, "an offset alone is not a creation region")
      assert(result.isRight, s"offset and device region should succeed: $result")
      assertEquals(stored.size, 1)
      val due = stored.head.due.get.asJson.hcursor
      assertEquals(due.get[String]("zoneId").toOption, Some("Asia/Tokyo"))
      assertEquals(due.get[String]("originalOffset").toOption, Some("+08:00"))
      assertEquals(due.get[Long]("dueAt").toOption, Some(java.time.Instant.parse("2035-01-01T01:00:00Z").toEpochMilli))

  test("tool todo explicit IANA zone overrides device region"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision, "triggerAt" -> "2035-01-01T09:00:00+01:00[Europe/Paris]".asJson)
    for
      _ <- reset()
      result <- ScheduleTool.call(input.asObject.get, mkCtx().copy(deviceZone = Some("Asia/Tokyo")))
      stored <- taskStore.getAllTodos
    yield
      assert(result.isRight, s"explicit region must be accepted: $result")
      assertEquals(stored.size, 1)
      val due = stored.head.due.get.asJson.hcursor
      assertEquals(due.get[String]("zoneId").toOption, Some("Europe/Paris"))
      assertEquals(due.get[String]("originalOffset").toOption, Some("+01:00"))
      assertEquals(due.get[Long]("dueAt").toOption, Some(java.time.Instant.parse("2035-01-01T08:00:00Z").toEpochMilli))

  test("tool todo local due uses the device region and preserves no offset"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision, "triggerAt" -> "2035-01-01T09:00".asJson)
    for
      _ <- reset()
      result <- ScheduleTool.call(input.asObject.get, mkCtx().copy(deviceZone = Some("Asia/Tokyo")))
      stored <- taskStore.getAllTodos
    yield
      assert(result.isRight, s"device region should permit due creation: $result")
      assertEquals(stored.size, 1)
      val due = stored.head.due.get.asJson.hcursor
      assertEquals(due.get[String]("zoneId").toOption, Some("Asia/Tokyo"))
      assertEquals(due.get[Long]("dueAt").toOption, Some(java.time.Instant.parse("2035-01-01T00:00:00Z").toEpochMilli))
      assert(due.downField("originalOffset").focus.forall(_.isNull))

  test("tool todo due without a region requests clarification"):
    val decision = Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson)))
    val input = Json.obj("kind" -> "todo".asJson, "content" -> "remind".asJson, "decision" -> decision, "triggerAt" -> "2035-01-01T09:00".asJson)
    for
      _ <- reset()
      result <- IO(callTool(input))
      stored <- taskStore.getAllTodos
    yield
      assert(result.isLeft, "a missing source must be clarified")
      assert(result.swap.toOption.get.message.toLowerCase.contains("clarif"))
      assertEquals(stored, Nil)

  test("② [端到端] 工具面 kind=todo 无选项 ⇒ 拒绝且零落库"):
    (for
      _ <- reset()
      res <- IO(callTool(Json.obj("kind" -> "todo".asJson, "content" -> "确认发布窗口".asJson)))
      stored <- taskStore.getAllTodos
    yield
      assert(res.isLeft, s"无选项的待办创建必须被拒，实得 $res")
      val msg = res.left.toOption.get.message
      assert(msg.contains(TodoTaxonomy.Codes.OptionsRequired), s"错误必须带码，实得：$msg")
      assertEquals(stored, Nil, "被拒的创建不得落库")
    )

  test("② [端到端] 工具面 kind=todo 带选项 ⇒ 成功且选项（含多张媒体）落库"):
    val decision = Json.obj(
      "question" -> "是否合并 release 分支？".asJson,
      "options" -> Json.arr(
        Json.obj("id" -> "o1".asJson, "title" -> "批准合并".asJson),
        Json.obj(
          "id" -> "o2".asJson,
          "title" -> "看截图再定".asJson,
          "media" -> Json.arr(
            Json.obj(
              "kind" -> "image".asJson,
              "refType" -> "attachment".asJson,
              "ref" -> "att-1".asJson,
              "role" -> "cover".asJson
            ),
            Json.obj("kind" -> "image".asJson, "refType" -> "attachment".asJson, "ref" -> "att-2".asJson)
          )
        )
      )
    )
    (for
      _ <- reset()
      res <- IO(callTool(Json.obj("kind" -> "todo".asJson, "content" -> "等 CI 绿后合并".asJson, "decision" -> decision)))
      stored <- taskStore.getAllTodos
    yield
      assert(res.isRight, s"带选项的待办应创建成功，实得 $res")
      assertEquals(stored.size, 1)
      val t = stored.head
      assertEquals(TodoTaxonomy.classify(t), TodoClass.Todo, "工具面创建 ⇒ source=tool ⇒ 待办")
      assertEquals(t.decision.map(_.options.size), Some(2))
      assertEquals(t.decision.map(_.options(1).media.size), Some(2), "一个选项挂多张媒体（叠卡）")
      assertEquals(t.decision.map(_.options(1).media.head.role), Some(Some("cover")))
    )

  test("② [端到端] 工具面 kind=task 不受选项强制（定时任务面）"):
    val future = System.currentTimeMillis() + 3_600_000L
    (for
      _ <- reset()
      res <- IO(callTool(Json.obj("kind" -> "task".asJson, "content" -> "例行汇报".asJson, "triggerAt" -> future.asJson)))
      stored <- taskStore.getAllPendingTasks
    yield
      assert(res.isRight, s"定时任务面不需要选项，实得 $res")
      assertEquals(stored.count(!_.isTodo), 1)
      assertEquals(stored.head.source, Some(TodoTaxonomy.SourceTool))
    )

  test("② [端到端] decision 形态错 ⇒ TODO_DECISION_MALFORMED（不当成「没写」）"):
    (for
      _ <- reset()
      res <- IO(
        callTool(
          Json.obj(
            "kind" -> "todo".asJson,
            "content" -> "x".asJson,
            "decision" -> Json.obj("options" -> Json.arr(Json.fromString("裸字符串选项")))
          )
        )
      )
    yield assertEquals(res.left.toOption.map(_.message.contains(TodoTaxonomy.Codes.DecisionMalformed)), Some(true)))

end TodoTaxonomySpec

object TodoTaxonomySpec:

  /** 其它 spec 造「合法待办创建参数」时复用的最小 decision（选项 ≥1、title 非空）。 */
  val sampleDecision: Json =
    Json.obj(
      "question" -> "是否照此办理？".asJson,
      "options" -> Json.arr(
        Json.obj("id" -> "o1".asJson, "title" -> "是".asJson),
        Json.obj("id" -> "o2".asJson, "title" -> "否，先讨论".asJson)
      )
    )
