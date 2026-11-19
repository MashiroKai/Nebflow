package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*
import nebflow.actor.{InteractionAnswered, InteractionHubCommand, InteractionKind, InteractionReply, InteractionRequest}
import nebflow.core.scheduler.{AskTodoBridge, ScheduledTask, ScheduledTaskStore}

/**
 * Ask→待办 hub 接线钉（待办分家批 2026-10-30，作者令「待办接 AskUserQuestion，
 * 闭环 Ask→待办→用户答→销账」；改版批 2026-10-31 扩面：Permission /
 * McpPermission 同步镜像 perm- 待批准组）——钉的是 **hub↔bridge 接线缝**，
 * bridge 本体语义见 AskTodoBridgeSpec：
 *  1. AskUser 挂起 ⇒ root 会话待办收件箱出现 ask- 镜像；
 *  2. Permission 挂起 ⇒ perm- 待批准镜像（content 带 [待批准] 前缀）；
 *  3. 用户取消（CancelRequest）⇒ 镜像随行销账（闭环的「答/取消→销账」半环）；
 *  4. 源会话死亡（CleanupForSession）⇒ 镜像随行销账。
 */
class AskTodoHubWiringSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-ask-todo-hub"

  private def freshStore(): ScheduledTaskStore =
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "scheduled-tasks")
    new ScheduledTaskStore(tempRoot / "scheduled-tasks")

  private def askUserRequest(requestId: String, rootSid: String = "root-1"): InteractionRequest =
    InteractionRequest(
      requestId = requestId,
      kind = InteractionKind.AskUser,
      payload = Json.obj(
        "type" -> Json.fromString("askUser"),
        "items" -> Json.arr(
          Json.obj("question" -> Json.fromString("选哪个方案？"))
        )
      ),
      reply = InteractionReply.AskUserReply(None),
      rootSessionId = rootSid,
      sourceAgent = "worker-a",
      sourceSession = "delegate-worker-a-abcdef12"
    )

  private def permissionRequest(
    requestId: String,
    deferred: cats.effect.Deferred[IO, Boolean],
    rootSid: String = "root-1"
  ): InteractionRequest =
    InteractionRequest(
      requestId = requestId,
      kind = InteractionKind.Permission,
      payload = Json.obj(
        "type" -> Json.fromString("askPermission"),
        "toolName" -> Json.fromString("Edit"),
        "summary" -> Json.fromString("Edit file"),
        "input" -> Json.obj(),
        "dangerLevel" -> Json.fromInt(1)
      ),
      reply = InteractionReply.PermissionReply(deferred),
      rootSessionId = rootSid,
      sourceAgent = "Nebula",
      sourceSession = "sess-1"
    )

  test("AskUser 挂起 ⇒ 待办镜像；用户取消 ⇒ 镜像销账（闭环）") {
    val system = nebflow.actor.ActorSystem("ask-todo-hub-test")
    val store = freshStore()
    val sent = collection.mutable.ListBuffer.empty[Json]
    val bridge = new AskTodoBridge(store, None, (j: Json) => IO.delay { sent += j }.void)
    for
      hub <- system.spawn(
        InteractionHub(todoBridge = Some(bridge)),
        "interaction-hub-ask-todo"
      )
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      _ <- hub ! InteractionHubCommand.Request(askUserRequest("asknb-aaa111"))
      _ <- IO.sleep(150.millis)
      todosAfterAsk <- store.getAllTodos
      _ <- hub ! InteractionHubCommand.CancelRequest("asknb-aaa111", "", "test", None)
      _ <- IO.sleep(150.millis)
      todosAfterCancel <- store.getAllTodos
      _ <- system.stopAll
    yield
      // ① 挂起即入：单份镜像，name=ask-<requestId>，挂在 root 会话。
      assertEquals(todosAfterAsk.length, 1)
      assertEquals(todosAfterAsk.head.name, Some("ask-asknb-aaa111"))
      assertEquals(todosAfterAsk.head.sessionId, "root-1")
      assertEquals(todosAfterAsk.head.kind, Some(ScheduledTask.KindTodo))
      // ② 取消收口：镜像随行销账。
      assertEquals(todosAfterCancel.length, 0)
    end for
  }

  test("permission 卡挂起 ⇒ perm- 待批准镜像；批准作答 ⇒ 随行销账（改版批 kind 闸放宽）") {
    val system = nebflow.actor.ActorSystem("ask-todo-hub-test2")
    val store = freshStore()
    val bridge = new AskTodoBridge(store, None, (_: Json) => IO.unit)
    for
      hub <- system.spawn(
        InteractionHub(todoBridge = Some(bridge)),
        "interaction-hub-ask-todo-2"
      )
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      d <- cats.effect.Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permissionRequest("perm-1", d))
      _ <- IO.sleep(150.millis)
      todos <- store.getAllTodos
      // 批准路径收口（permission 卡无用户取消路——CancelRequest 对非 AskUser
      // 槽位回 NotAskUser 不终结，见 hub 注释①；终态 = 作答 / 源会话死亡）。
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("perm-1", "root-1", Json.obj("approved" -> Json.fromBoolean(true)))
      )
      _ <- IO.sleep(150.millis)
      afterAnswer <- store.getAllTodos
      _ <- system.stopAll
    yield
      // 改版批：permission 入面（原「不入面」闸反转）——单份 perm- 镜像，
      // content 从 payload toolName/summary 提取，带 [待批准] 前缀。
      assertEquals(todos.length, 1)
      assertEquals(todos.head.name, Some("perm-perm-1"))
      assertEquals(todos.head.sessionId, "root-1")
      assertEquals(todos.head.kind, Some(ScheduledTask.KindTodo))
      assert(todos.head.content.startsWith("[待批准] Edit"))
      // 批准收口：镜像随行销账（retire 双前缀幂等清的 permission 半环）。
      assertEquals(afterAnswer.length, 0)
    end for
  }

  test("源会话死亡（CleanupForSession）⇒ 镜像随行销账") {
    val system = nebflow.actor.ActorSystem("ask-todo-hub-test3")
    val store = freshStore()
    val bridge = new AskTodoBridge(store, None, (_: Json) => IO.unit)
    for
      hub <- system.spawn(
        InteractionHub(todoBridge = Some(bridge)),
        "interaction-hub-ask-todo-3"
      )
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      _ <- hub ! InteractionHubCommand.Request(askUserRequest("asknb-bbb222"))
      _ <- IO.sleep(150.millis)
      before <- store.getAllTodos
      _ <- hub ! InteractionHubCommand.CleanupForSession("delegate-worker-a-abcdef12", "turn-interrupted")
      _ <- IO.sleep(150.millis)
      after <- store.getAllTodos
      _ <- system.stopAll
    yield
      assertEquals(before.length, 1)
      assertEquals(after.length, 0)
    end for
  }

end AskTodoHubWiringSpec
