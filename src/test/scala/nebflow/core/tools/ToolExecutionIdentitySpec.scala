package nebflow.core.tools

class ToolExecutionIdentitySpec extends munit.FunSuite:
  test("identity is absent by default and a minted root identity is recognized") {
    val plain = ToolContext(projectRoot = "/tmp")
    assert(!plain.hasTrustedExecutionIdentity)
    assert(!plain.isTrustedRootExecution)

    val identity = ToolExecutionIdentity.issue("Nebula", "root")
    val trusted = plain.copy(executionIdentity = Some(identity))
    assert(trusted.hasTrustedExecutionIdentity)
    assert(trusted.isTrustedRootExecution)
    assert(!trusted.copy(depth = 1).isTrustedRootExecution)
  }

  test("Neblink-gated tool opts into trusted identity requirement") {
    assert(AskUserQuestionTool.requiresTrustedExecutionIdentity)
  }
