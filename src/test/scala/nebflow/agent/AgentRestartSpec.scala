package nebflow.agent

import io.circe.JsonObject
import munit.FunSuite
import nebflow.shared.*

class AgentRestartSpec extends FunSuite:

  // Helper: check if a message is an Assistant message with ToolUse blocks
  private def hasToolUse(msg: Message): Boolean =
    msg.role == MessageRole.Assistant && {
      msg.content match
        case Right(blocks) => blocks.exists(_.isInstanceOf[ContentBlock.ToolUse])
        case _ => false
    }

  // Helper: find tool name from an Assistant message
  private def toolNameOf(msg: Message): String =
    msg.content match
      case Right(blocks) =>
        blocks.collectFirst { case ContentBlock.ToolUse(_, name, _) => name }.getOrElse("unknown")
      case _ => "unknown"

  test("finds last tool call in message list") {
    val messages = List(
      Message(MessageRole.User, Left("do something")),
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.Text("I'll read the file"),
        ContentBlock.ToolUse("call-1", "Read", JsonObject("file_path" -> io.circe.Json.fromString("test.txt")))
      ))),
      Message(MessageRole.User, Right(List(
        ContentBlock.ToolResult("call-1", "file content", Some(false))
      ))),
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.Text("done")
      )))
    )
    val lastToolUseIdx = messages.lastIndexWhere(hasToolUse)
    assertEquals(lastToolUseIdx, 1)
  }

  test("returns -1 when no tool call exists") {
    val messages = List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("hi there"))
    )
    val idx = messages.lastIndexWhere(hasToolUse)
    assertEquals(idx, -1)
  }

  test("finds the correct tool when multiple tool calls exist") {
    val messages = List(
      Message(MessageRole.User, Left("task")),
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.ToolUse("call-1", "Read", JsonObject.empty)
      ))),
      Message(MessageRole.User, Right(List(
        ContentBlock.ToolResult("call-1", "content", Some(false))
      ))),
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.ToolUse("call-2", "Bash", JsonObject.empty)
      ))),
      Message(MessageRole.User, Right(List(
        ContentBlock.ToolResult("call-2", "output", Some(true))
      )))
    )
    val idx = messages.lastIndexWhere(hasToolUse)
    assertEquals(idx, 3)
  }

  test("truncation removes tool call and result") {
    val messages = List(
      Message(MessageRole.User, Left("task")),
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.ToolUse("call-1", "Read", JsonObject.empty)
      ))),
      Message(MessageRole.User, Right(List(
        ContentBlock.ToolResult("call-1", "content", Some(false))
      )))
    )
    val truncateIdx = 1
    val truncated = messages.take(truncateIdx)
    assertEquals(truncated.length, 1)
    assertEquals(truncated.head.role, MessageRole.User)
  }

  test("extracts tool name from ToolUse block") {
    val msg = Message(MessageRole.Assistant, Right(List(
      ContentBlock.Text("running tests"),
      ContentBlock.ToolUse("call-1", "Bash", JsonObject("command" -> io.circe.Json.fromString("sbt test")))
    )))
    assertEquals(toolNameOf(msg), "Bash")
  }

  test("empty message list has no tool calls") {
    val messages: List[Message] = Nil
    val idx = messages.lastIndexWhere(hasToolUse)
    assertEquals(idx, -1)
  }

  test("single user message has no tool calls") {
    val messages = List(Message(MessageRole.User, Left("hello")))
    val idx = messages.lastIndexWhere(hasToolUse)
    assertEquals(idx, -1)
  }

  test("RestartLevel has four levels") {
    assertEquals(RestartLevel.values.length, 4)
    assertEquals(RestartLevel.Soft.toString, "Soft")
    assertEquals(RestartLevel.Rollback.toString, "Rollback")
    assertEquals(RestartLevel.Prune.toString, "Prune")
    assertEquals(RestartLevel.Full.toString, "Full")
  }

  test("RestartAgent command carries level") {
    val cmd = AgentCommand.RestartAgent(RestartLevel.Soft)
    assertEquals(cmd.level, RestartLevel.Soft)
  }

  test("rollback + inject produces correct message structure") {
    val messages = List(
      Message(MessageRole.User, Left("task")),
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.ToolUse("call-1", "Bash", JsonObject.empty)
      ))),
      Message(MessageRole.User, Right(List(
        ContentBlock.ToolResult("call-1", "error output", Some(true))
      )))
    )
    val truncateIdx = messages.lastIndexWhere(hasToolUse) // = 1
    val truncated = messages.take(truncateIdx)
    val supervisorMsg = Message(MessageRole.User, Left(
      "[SUPERVISOR] Your last action (Bash) was rolled back. Do not repeat."
    ))
    val result = truncated :+ supervisorMsg
    assertEquals(result.length, 2)
    assertEquals(result(0).role, MessageRole.User)
    assertEquals(result(1).role, MessageRole.User)
    assert(result(1).content.left.getOrElse("").contains("[SUPERVISOR]"))
  }

end AgentRestartSpec
