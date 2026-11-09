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

  test("only sealed Nebula root identity qualifies, not aliases, roles, or depth") {
    val base = ToolContext(projectRoot = "/tmp")
    val root = base.copy(executionIdentity = Some(ToolExecutionIdentity.issue("Nebula", "root")))
    val alias = base.copy(executionIdentity = Some(ToolExecutionIdentity.issue("Nebula", "agent")))
    val other = base.copy(executionIdentity = Some(ToolExecutionIdentity.issue("worker", "root")))

    assert(AskUserQuestionTool.trustedIdentityError(root).isEmpty)
    assert(AskUserQuestionTool.trustedIdentityError(alias).nonEmpty)
    assert(AskUserQuestionTool.trustedIdentityError(other).nonEmpty)
    assert(AskUserQuestionTool.trustedIdentityError(root.copy(depth = 1)).nonEmpty)
    assert(AskUserQuestionTool.trustedIdentityError(base).nonEmpty)
  }
