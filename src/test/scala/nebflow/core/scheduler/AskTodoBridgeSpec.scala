package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

/**
 * Ask→待办 bridge（待办分家批 2026-10-30，作者令「待办接 AskUserQuestion：
 * Ask 产生的事项自动入待办，闭环 Ask→待办→用户答→销账」）单钉：
 *  1. attach：root 会话落 kind=todo 镜像（name=ask-<requestId>，浮出时刻 =
 *     下一次 09:00），广播 scheduledTaskCreated（带 name/kind）；
 *  2. attach 幂等：同 requestId 重放 = 替换不叠加（单份）；
 *  3. retire：按名跨会话幂等删除 + 广播 scheduledTaskDeleted；未知 id = no-op；
 *  4. 空入参护栏：root/requestId 空 ⇒ 零写零广播；
 *  5. 文案/反解助手：todoContent 带 [待答] 前缀与提问方；requestIdOf 只认 ask- 前缀。
 */
class AskTodoBridgeSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-ask-todo-bridge"

  private def freshStore(): ScheduledTaskStore =
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "scheduled-tasks")
    new ScheduledTaskStore(tempRoot / "scheduled-tasks")

  private def mkBridge(store: ScheduledTaskStore, sent: collection.mutable.ListBuffer[Json]) =
    new AskTodoBridge(store, None, (j: Json) => IO.delay { sent += j }.void)

  test("attach 落 kind=todo 镜像（name=ask-<requestId>）并广播创建帧") {
    val store = freshStore()
    val sent = collection.mutable.ListBuffer.empty[Json]
    val bridge = mkBridge(store, sent)
    for
      _ <- bridge.attach("root-1", "asknb-deadbeef", "选哪个方案？", "worker-a")
      todos <- store.getAllTodos
      created = sent.filter(_.hcursor.downField("type").as[String].contains("scheduledTaskCreated"))
    yield
      assertEquals(todos.length, 1)
      val t = todos.head
      assertEquals(t.sessionId, "root-1")
      assertEquals(t.name, Some("ask-asknb-deadbeef"))
      assertEquals(t.kind, Some(ScheduledTask.KindTodo))
      assert(!t.triggered)
      assertEquals(t.content, "[待答] 选哪个方案？ — worker-a")
      // 浮出时刻 = 未来（下一次 09:00，必晚于 now）
      assert(t.triggerAt > System.currentTimeMillis())
      assertEquals(created.length, 1)
      val task = created.head.hcursor.downField("task")
      assertEquals(task.downField("name").as[String], Right("ask-asknb-deadbeef"))
      assertEquals(task.downField("kind").as[String], Right("todo"))
  }

  test("attach 幂等：同 requestId 重放 = 单份替换") {
    val store = freshStore()
    val sent = collection.mutable.ListBuffer.empty[Json]
    val bridge = mkBridge(store, sent)
    for
      _ <- bridge.attach("root-1", "rid-1", "第一问", "a")
      _ <- bridge.attach("root-1", "rid-1", "第一问（补发）", "a")
      todos <- store.getAllTodos
    yield
      assertEquals(todos.length, 1)
      assertEquals(todos.head.content, "[待答] 第一问（补发） — a")
  }

  test("retire 幂等销账：删除 + 广播；重复 retire / 未知 id = no-op") {
    val store = freshStore()
    val sent = collection.mutable.ListBuffer.empty[Json]
    val bridge = mkBridge(store, sent)
    for
      _ <- bridge.attach("root-1", "rid-2", "问题二", "b")
      before <- store.getAllTodos
      _ <- bridge.retire("rid-2")
      after <- store.getAllTodos
      _ <- bridge.retire("rid-2") // 重复收口：幂等 no-op
      _ <- bridge.retire("never-existed")
      deleted = sent.filter(_.hcursor.downField("type").as[String].contains("scheduledTaskDeleted"))
    yield
      assertEquals(before.length, 1)
      assertEquals(after.length, 0)
      assertEquals(deleted.length, 1)
      assertEquals(deleted.head.hcursor.downField("sessionId").as[String], Right("root-1"))
  }

  test("空入参护栏：root/requestId 空 ⇒ 零写零广播") {
    val store = freshStore()
    val sent = collection.mutable.ListBuffer.empty[Json]
    val bridge = mkBridge(store, sent)
    for
      _ <- bridge.attach("", "rid-3", "q", "a")
      _ <- bridge.attach("root-1", "", "q", "a")
      _ <- bridge.retire("")
      todos <- store.getAllTodos
    yield
      assertEquals(todos.length, 0)
      assertEquals(sent.length, 0)
  }

  test("助手：todoContent 无提问方时省略尾缀；requestIdOf 只认 ask- 前缀") {
    assertEquals(AskTodoBridge.todoContent("只有问题", ""), "[待答] 只有问题")
    assertEquals(AskTodoBridge.todoContent("只有问题", "  "), "[待答] 只有问题")
    assertEquals(AskTodoBridge.requestIdOf(Some("ask-abc123")), Some("abc123"))
    assertEquals(AskTodoBridge.requestIdOf(Some("weekly-report-42")), None)
    assertEquals(AskTodoBridge.requestIdOf(None), None)
    assertEquals(AskTodoBridge.requestIdOf(Some("ask-")), None)
  }

  // ── 改版批（2026-10-31）：perm- 待批准镜像 ──────────────────────────

  test("attachPermission 落 perm- 镜像（[待批准] toolName · summary — agent）") {
    val store = freshStore()
    val sent = collection.mutable.ListBuffer.empty[Json]
    val bridge = mkBridge(store, sent)
    val payload = Json.obj(
      "toolName" -> "Bash".asJson,
      "summary" -> "rm -rf build/tmp".asJson,
      "dangerLevel" -> 2.asJson
    )
    for
      _ <- bridge.attachPermission("root-1", "permnb-ccc333", payload, "worker-a")
      todos <- store.getAllTodos
    yield
      assertEquals(todos.length, 1)
      assertEquals(todos.head.name, Some("perm-permnb-ccc333"))
      assertEquals(todos.head.kind, Some(ScheduledTask.KindTodo))
      assertEquals(todos.head.content, "[待批准] Bash · rm -rf build/tmp — worker-a")
  }

  test("retire 双前缀同清：ask- 与 perm- 镜像一并销账（单边缺失 = no-op）") {
    val store = freshStore()
    val sent = collection.mutable.ListBuffer.empty[Json]
    val bridge = mkBridge(store, sent)
    val payload = Json.obj("toolName" -> "Edit".asJson, "summary" -> "Edit file".asJson)
    for
      _ <- bridge.attach("root-1", "rid-dual", "问题", "a")
      _ <- bridge.attachPermission("root-1", "rid-dual", payload, "a")
      before <- store.getAllTodos
      _ <- bridge.retire("rid-dual")
      after <- store.getAllTodos
      // 只有 ask- 镜像的 id retire：perm 侧缺失不报错。
      _ <- bridge.attach("root-1", "rid-ask-only", "问题二", "a")
      _ <- bridge.retire("rid-ask-only")
      askOnly <- store.getAllTodos
    yield
      assertEquals(before.length, 2)
      assertEquals(after.length, 0)
      assertEquals(askOnly.length, 0)
  }

  test("助手：permContent 字段回落（toolName → serverId → approval）；permRequestIdOf 只认 perm- 前缀") {
    val full = Json.obj("toolName" -> "Bash".asJson, "summary" -> "ls".asJson)
    assertEquals(AskTodoBridge.permContent(full, "a"), "[待批准] Bash · ls — a")
    val noSummary = Json.obj("toolName" -> "Curl".asJson)
    assertEquals(AskTodoBridge.permContent(noSummary, ""), "[待批准] Curl")
    val mcp = Json.obj("serverId" -> "fs-server".asJson, "summary" -> "read dir".asJson)
    assertEquals(AskTodoBridge.permContent(mcp, "a"), "[待批准] fs-server · read dir — a")
    assertEquals(AskTodoBridge.permContent(Json.obj(), ""), "[待批准] approval")
    assertEquals(AskTodoBridge.permRequestIdOf(Some("perm-abc123")), Some("abc123"))
    assertEquals(AskTodoBridge.permRequestIdOf(Some("ask-abc123")), None)
    assertEquals(AskTodoBridge.permRequestIdOf(Some("weekly-report-42")), None)
  }

  // ── 改版批追加令（2026-10-31 23:14 ①）：项目维度 ─────────────────────

  test("attach 带 project 标签落库；payloadProject 提取（project → sourceFlow，空串=未分组）") {
    val store = freshStore()
    val sent = collection.mutable.ListBuffer.empty[Json]
    val bridge = mkBridge(store, sent)
    for
      _ <- bridge.attach("root-1", "rid-proj", "选哪个方案？", "worker-a", Some(" stand "))
      todos <- store.getAllTodos
    yield
      assertEquals(todos.length, 1)
      assertEquals(todos.head.project, Some("stand"))
      // 提取单点：project 优先；空串/缺席 = None（未分组）。
      assertEquals(AskTodoBridge.payloadProject(Json.obj("project" -> "DOSTEL".asJson)), Some("DOSTEL"))
      assertEquals(AskTodoBridge.payloadProject(Json.obj("sourceFlow" -> "stand".asJson)), Some("stand"))
      assertEquals(AskTodoBridge.payloadProject(Json.obj("project" -> "   ".asJson)), None)
      assertEquals(AskTodoBridge.payloadProject(Json.obj()), None)
  }

end AskTodoBridgeSpec
