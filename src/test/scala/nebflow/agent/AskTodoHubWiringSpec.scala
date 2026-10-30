package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*
import nebflow.actor.{InteractionHubCommand, InteractionKind, InteractionReply, InteractionRequest}
import nebflow.core.scheduler.{AskTodoBridge, ScheduledTask, ScheduledTaskStore}

/**
 * Ask→待办 hub 接线钉（待办分家批 2026-10-30，作者令「待办接 AskUserQuestion，
 * 闭环 Ask→待办→用户答→销账」）——钉的是 **hub↔bridge 接线缝**，bridge 本体
 * 语义见 AskTodoBridgeSpec：
 *  1. AskUser 挂起 ⇒ root 会话待办收件箱出现 ask- 镜像（permission 卡不入面）；
 *  2. 用户取消（CancelRequest）⇒ 镜像随行销账（闭环的「答/取消→销账」半环）；
 *  3. 源会话死亡（CleanupForSession）⇒ 镜像随行销账。
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

  test("permission 卡不入待办面（kind 闸）") {
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
      _ <- system.stopAll
    yield assertEquals(todos.length, 0)
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
