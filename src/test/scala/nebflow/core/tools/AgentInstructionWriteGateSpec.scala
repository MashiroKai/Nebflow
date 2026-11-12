package nebflow.core.tools

import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.ToolCall

class AgentInstructionWriteGateSpec extends FunSuite:
  private def call(name: String, path: String): ToolCall =
    ToolCall(id = "call", name = name, input = JsonObject("file_path" -> path.asJson), rawArguments = None)

  test("human-originated console writes are not blocked by the agent-only gate"):
    assert(!AgentInstructionWriteGate.requiresConfirmation(call("Write", "/workspace/AGENTS.md"), isAgent = false))

  test("agent Write/Edit/MultiEdit/Bash writes to AGENTS.md require trusted confirmation"):
    for tool <- List("Write", "Edit", "MultiEdit", "Bash") do
      assert(AgentInstructionWriteGate.requiresConfirmation(call(tool, "/workspace/AGENTS.md"), isAgent = true), tool)

  test("agent writes elsewhere and client-provided identity fields do not affect gate"):
    assert(!AgentInstructionWriteGate.requiresConfirmation(call("Write", "/workspace/README.md"), isAgent = true))
    assert(!AgentInstructionWriteGate.requiresConfirmation(call("Read", "/workspace/AGENTS.md"), isAgent = true))

  test("Bash reads are exempt only for a single read-only command, not compound or sed commands"):
    def bash(command: String) = ToolCall(
      id = "bash",
      name = "Bash",
      input = JsonObject("command" -> command.asJson),
      rawArguments = None
    )
    assert(!AgentInstructionWriteGate.requiresConfirmation(bash("cat AGENTS.md"), isAgent = true))
    assert(AgentInstructionWriteGate.requiresConfirmation(bash("cat AGENTS.md; echo change >> AGENTS.md"), isAgent = true))
    assert(AgentInstructionWriteGate.requiresConfirmation(bash("sed -i 's/a/b/' AGENTS.md"), isAgent = true))

  test("canonical filename recognition is case-insensitive and cross-platform"):
    assert(AgentInstructionWriteGate.isAgentsFile("C:\\work\\AGENTS.md"))
    assert(AgentInstructionWriteGate.isAgentsFile("/workspace/agents.MD"))
    assert(!AgentInstructionWriteGate.isAgentsFile("/workspace/.nebflow/agents.md.backup"))
end AgentInstructionWriteGateSpec
