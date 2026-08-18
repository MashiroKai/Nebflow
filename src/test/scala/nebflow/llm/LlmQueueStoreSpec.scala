package nebflow.llm

import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.shared.{LlmRequest, Message, MessageRole, ToolDefinition}

/**
 * LlmQueueStore unit tests (P0 并发管理阶段 2, design §4.5).
 *
 * Coverage:
 *  - append/load roundtrip + FIFO order
 *  - removeHead (grant cleanup) / removeById (timeout cleanup)
 *  - missing / corrupt file tolerance
 *  - full LlmRequest codec fidelity (messages/tools/thinking/system/agentModel)
 *  - providersWithQueues discovery for startup replay
 */
class LlmQueueStoreSpec extends FunSuite:

  private def withDataRoot[A](f: => A): A =
    val original = PathUtil.dataRoot
    val temp = os.temp.dir()
    PathUtil.setDataRoot(temp)
    try f
    finally PathUtil.setDataRoot(original)

  private def item(id: String, provider: String = "mock", sid: String = "s1"): LlmQueueStore.QueueItem =
    LlmQueueStore.QueueItem(id, provider, LlmRequest(Nil, sid, "a1"), 1000L)

  test("append/load roundtrip preserves item fields") {
    withDataRoot {
      LlmQueueStore.append("mock", item("id-1")).unsafeRunSync()
      val loaded = LlmQueueStore.load("mock").unsafeRunSync()
      assertEquals(loaded.map(_.id), List("id-1"))
      assertEquals(loaded.head.providerId, "mock")
      assertEquals(loaded.head.enqueuedAt, 1000L)
      assertEquals(loaded.head.request.sessionId, "s1")
    }
  }

  test("append preserves FIFO order") {
    withDataRoot {
      List("a", "b", "c").foreach(id => LlmQueueStore.append("mock", item(id)).unsafeRunSync())
      assertEquals(LlmQueueStore.load("mock").unsafeRunSync().map(_.id), List("a", "b", "c"))
    }
  }

  test("removeHead removes the first item (grant cleanup)") {
    withDataRoot {
      List("a", "b").foreach(id => LlmQueueStore.append("mock", item(id)).unsafeRunSync())
      val head = LlmQueueStore.removeHead("mock").unsafeRunSync()
      assertEquals(head.map(_.id), Some("a"))
      assertEquals(LlmQueueStore.load("mock").unsafeRunSync().map(_.id), List("b"))
      // Empty queue → None
      LlmQueueStore.removeHead("mock").unsafeRunSync()
      assertEquals(LlmQueueStore.removeHead("mock").unsafeRunSync(), None)
    }
  }

  test("removeById removes a specific item (timeout cleanup)") {
    withDataRoot {
      List("a", "b", "c").foreach(id => LlmQueueStore.append("mock", item(id)).unsafeRunSync())
      LlmQueueStore.removeById("mock", "b").unsafeRunSync()
      assertEquals(LlmQueueStore.load("mock").unsafeRunSync().map(_.id), List("a", "c"))
    }
  }

  test("missing file loads empty and size 0") {
    withDataRoot {
      assertEquals(LlmQueueStore.load("nonexistent").unsafeRunSync(), Nil)
      assertEquals(LlmQueueStore.size("nonexistent").unsafeRunSync(), 0)
    }
  }

  test("corrupt file loads empty (tolerance)") {
    withDataRoot {
      val dir = PathUtil.dataRoot / "llm-queue"
      os.makeDir.all(dir)
      os.write.over(dir / "mock.json", "{not valid json")
      assertEquals(LlmQueueStore.load("mock").unsafeRunSync(), Nil)
      // append after corrupt file starts fresh, doesn't crash
      LlmQueueStore.append("mock", item("fresh")).unsafeRunSync()
      assertEquals(LlmQueueStore.load("mock").unsafeRunSync().map(_.id), List("fresh"))
    }
  }

  test("full LlmRequest codec roundtrip (messages/tools/thinking/system/agentModel)") {
    withDataRoot {
      val req = LlmRequest(
        messages = List(Message(MessageRole.User, Left("hello"), 111L, Some("user"))),
        sessionId = "s1",
        agentId = "a1",
        tools = Some(
          List(
            ToolDefinition(
              "Bash",
              "run a command",
              io.circe.JsonObject.fromMap(Map("type" -> Json.fromString("object"), "required" -> Json.fromValues(List(Json.fromString("command")))))
            )
          )
        ),
        maxTokens = Some(2048),
        thinking = Some(Json.obj("budget_tokens" -> Json.fromInt(100))),
        systemStable = Some("stable prompt"),
        systemDynamic = Some("dynamic env"),
        agentModel = Some(nebflow.shared.AgentModelConfig(preferred = Some("mock/mock-model"), fallbacks = List("alt/other")))
      )
      LlmQueueStore.append("mock", LlmQueueStore.QueueItem("id-x", "mock", req, 42L)).unsafeRunSync()
      val loaded = LlmQueueStore.load("mock").unsafeRunSync().head.request
      assertEquals(loaded.sessionId, "s1")
      assertEquals(loaded.messages.map(_.role), List(MessageRole.User))
      assertEquals(loaded.messages.head.content, Left("hello"))
      assertEquals(loaded.tools.map(_.map(_.name)), Some(List("Bash")))
      assertEquals(loaded.tools.get.head.inputSchema.toMap.keys.toSet, Set("type", "required"))
      assertEquals(loaded.maxTokens, Some(2048))
      assertEquals(loaded.thinking, Some(Json.obj("budget_tokens" -> Json.fromInt(100))))
      assertEquals(loaded.systemStable, Some("stable prompt"))
      assertEquals(loaded.systemDynamic, Some("dynamic env"))
      assertEquals(loaded.agentModel.flatMap(_.preferred), Some("mock/mock-model"))
      assertEquals(loaded.agentModel.map(_.fallbacks), Some(List("alt/other")))
    }
  }

  test("providersWithQueues lists only providers with queue files") {
    withDataRoot {
      assertEquals(LlmQueueStore.providersWithQueues.unsafeRunSync(), Nil)
      LlmQueueStore.append("mock", item("a")).unsafeRunSync()
      LlmQueueStore.append("zhipu", item("b", provider = "zhipu")).unsafeRunSync()
      assertEquals(LlmQueueStore.providersWithQueues.unsafeRunSync().sorted, List("mock", "zhipu"))
      // After removal the file may still exist but be empty — still listed (replay is a no-op)
    }
  }
