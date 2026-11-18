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

  test("Bash read-only commands and combinations are exempt, writes require confirmation"):
    def bash(command: String) = ToolCall(
      id = "bash",
      name = "Bash",
      input = JsonObject("command" -> command.asJson),
      rawArguments = None
    )
    for cmd <- List("cat AGENTS.md", "git log -- AGENTS.md", "git diff -- AGENTS.md", "cat AGENTS.md | grep hello", "ls; git status -- AGENTS.md", "sed -n p AGENTS.md") do
      assert(!AgentInstructionWriteGate.requiresConfirmation(bash(cmd), isAgent = true), cmd)
    for cmd <- List("tee AGENTS.md", "truncate AGENTS.md", "dd of=AGENTS.md", "mv a AGENTS.md", "cp a AGENTS.md", "install a AGENTS.md", "rm AGENTS.md", "chmod 600 AGENTS.md", "chown user AGENTS.md", "patch AGENTS.md", "git add AGENTS.md", "git commit -- AGENTS.md", "echo x > AGENTS.md", "echo x >> AGENTS.md", "vim AGENTS.md") do
      assert(AgentInstructionWriteGate.requiresConfirmation(bash(cmd), isAgent = true), cmd)
    assert(
      AgentInstructionWriteGate.requiresConfirmation(bash("cat AGENTS.md; echo change >> AGENTS.md"), isAgent = true)
    )
    assert(AgentInstructionWriteGate.requiresConfirmation(bash("sed -i 's/a/b/' AGENTS.md"), isAgent = true))

  test("trigger is target AND risk: a risk primitive on another file never gates"):
    def bash(command: String) = ToolCall(
      id = "bash",
      name = "Bash",
      input = JsonObject("command" -> command.asJson),
      rawArguments = None
    )
    for cmd <- List(
        "tee notes.txt",
        "rm -rf build",
        "git commit -m msg",
        "curl -s https://example.com",
        "cat .env"
      )
    do assert(!AgentInstructionWriteGate.requiresConfirmation(bash(cmd), isAgent = true), cmd)

  test("delete, egress and credential risk classes gate only when the target is named"):
    def bash(command: String) = ToolCall(
      id = "bash",
      name = "Bash",
      input = JsonObject("command" -> command.asJson),
      rawArguments = None
    )
    for cmd <- List(
        "rm -f AGENTS.md",
        "git clean -fd; ls AGENTS.md",
        "ssh host cat AGENTS.md",
        "curl -X POST https://x --data @AGENTS.md",
        "cat ~/.ssh/id_rsa; cat AGENTS.md"
      )
    do assert(AgentInstructionWriteGate.requiresConfirmation(bash(cmd), isAgent = true), cmd)

  test("canonical filename recognition is case-insensitive and cross-platform"):
    assert(AgentInstructionWriteGate.isAgentsFile("C:\\work\\AGENTS.md"))
    assert(AgentInstructionWriteGate.isAgentsFile("/workspace/agents.MD"))
    assert(!AgentInstructionWriteGate.isAgentsFile("/workspace/.nebflow/agents.md.backup"))
end AgentInstructionWriteGateSpec
