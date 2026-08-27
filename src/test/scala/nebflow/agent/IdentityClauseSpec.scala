package nebflow.agent

import munit.FunSuite
import PromptSections.PromptContext

/** 轨道二 #5: identity-clause conditional section (order 395) — T1 flow
  * worker / userFacing variant / T2 team member / flag-off zero-render. */
class IdentityClauseSpec extends FunSuite:

  private def clause(userFacing: Boolean): String = PromptSections.flowWorkerIdentityBlock(userFacing)

  test("flag off renders nothing for every shape"):
    for ctx <- List(
        PromptContext(guardrailsOn = false, isFlowNode = true),
        PromptContext(guardrailsOn = false, agentCategory = "team")
      )
    do
      val rendered = PromptSections.buildConditionalBlocks(ctx)
      assert(!rendered.contains("身份与受众"), "guardrails disabled = no clause (default-off contract)")

  test("T1 strict block: machine-consumer audience, Pop ban, budget"):
    val text = clause(userFacing = false)
    assert(text.contains("受众是编排器与下游节点"))
    assert(text.contains("不调用 Pop"))
    assert(text.contains("≤ 500 tokens"))
    assert(text.contains("assumption"), "ambiguity routes to outputs.assumption, not a question")

  test("T1 userFacing variant: dual audience, no display ban, relaxed budget"):
    val text = clause(userFacing = true)
    assert(text.contains("用户终审环节"))
    assert(!text.contains("不调用 Pop"), "whitelisted node keeps display tools — no ban line")

  test("T2 team member block: Lead consumer + Mail/RESULT channels"):
    assert(PromptSections.teamMemberIdentityBlock.contains("Team Lead"))
    assert(PromptSections.teamMemberIdentityBlock.contains("[ASSUMPTION]"))
    assert(PromptSections.teamMemberIdentityBlock.contains("≤ 300 tokens"))

  test("section wiring: renders via buildConditionalBlocks by category"):
    val node = PromptSections.buildConditionalBlocks(
      PromptContext(guardrailsOn = true, isFlowNode = true, agentCategory = "standalone")
    )
    assert(node.contains("受众是编排器与下游节点"), "flow node gets the strict T1 clause")

    val member = PromptSections.buildConditionalBlocks(
      PromptContext(guardrailsOn = true, agentCategory = "team", isTeamLead = false)
    )
    assert(member.contains("[ASSUMPTION]"), "team member gets the T2 clause")

    val lead = PromptSections.buildConditionalBlocks(
      PromptContext(guardrailsOn = true, agentCategory = "team", isTeamLead = true)
    )
    assert(!lead.contains("身份与受众"), "Manager exempt — Lead IS the user interface")

    val whitelisted = PromptSections.buildConditionalBlocks(
      PromptContext(guardrailsOn = true, isFlowNode = true, userFacingNode = true)
    )
    assert(whitelisted.contains("用户终审环节"), "userFacing node gets the dual-audience variant")
end IdentityClauseSpec
