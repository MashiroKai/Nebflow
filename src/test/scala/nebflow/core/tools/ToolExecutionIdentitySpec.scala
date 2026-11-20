package nebflow.core.tools

import nebflow.actor.RootAgentIdentity

class ToolExecutionIdentitySpec extends munit.FunSuite:

  test("identity is absent by default and a minted root identity is recognized") {
    val plain = ToolContext(projectRoot = "/tmp")
    val rootDef = nebflow.actor.AgentDef(name = RootAgentIdentity.Name, description = "", tools = Nil)
    assert(!plain.hasTrustedExecutionIdentity)
    assert(!plain.isTrustedRootExecution)

    val identity = ToolExecutionIdentity.issue("Nebula", "root")
    val trusted = plain.copy(executionIdentity = Some(identity), agentDef = Some(rootDef))
    assert(trusted.hasTrustedExecutionIdentity)
    assert(trusted.isTrustedRootExecution)
    assert(!trusted.copy(depth = 1).isTrustedRootExecution)
  }

  test("Neblink-gated tool opts into trusted identity requirement") {
    assert(AskUserQuestionTool.requiresTrustedExecutionIdentity)
  }

  test("only the root issuer can mint a root identity; depth remains part of runtime validation") {
    val base = ToolContext(projectRoot = "/tmp")
    val rootDef = nebflow.actor.AgentDef(name = RootAgentIdentity.Name, description = "", tools = Nil)
    val root = base.copy(
      executionIdentity = Some(ToolExecutionIdentity.issue(RootAgentIdentity.Name, "root")),
      agentDef = Some(rootDef)
    )

    assert(AskUserQuestionTool.trustedIdentityError(root).isEmpty)
    assert(AskUserQuestionTool.trustedIdentityError(root.copy(depth = 1)).nonEmpty)
    assert(AskUserQuestionTool.trustedIdentityError(base).nonEmpty)
    intercept[IllegalArgumentException](ToolExecutionIdentity.issue("Nebula", "agent"))
    intercept[IllegalArgumentException](ToolExecutionIdentity.issue("worker", "root"))
  }
end ToolExecutionIdentitySpec
