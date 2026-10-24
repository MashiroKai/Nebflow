package nebflow.core

import io.circe.Json
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.agent.PromptSections
import nebflow.core.entity.AgentIdentityStore

/**
 * identity-sync 批（2026-10-23）提示词/提醒面单测：
 *   ① `## Self` 段（order 396）——只在非默认身份时注入，无名无头像字节零变化；
 *   ② identity 变更提醒——渲染形状与类别键（验收②：agent 上下文携带名字/头像）。
 */
class IdentityPromptSpec extends FunSuite:

  private def assembleWith(identitySelfText: String): String =
    val ctx = PromptSections.PromptContext(agentName = "Nebula", identitySelfText = identitySelfText)
    PromptSections.assembleSystemPrompt("BASE", PromptSections.buildConditionalBlocks(ctx))

  test("Self section injected only when identity is non-default"):
    val without = assembleWith("")
    assert(!without.contains("## Self"))
    val withIdentity = assembleWith(AgentIdentityStore.selfSectionText("Nebula", Some("小雨"), Some("abcd1234")))
    assert(withIdentity.contains("## Self"))
    assert(withIdentity.contains("Display name: \"小雨\""))
    assert(withIdentity.contains("abcd1234".take(8)))

  test("identity reminder: no delta → no identity reminder; delta → identity category present"):
    val store = new nebflow.core.scheduler.ScheduledTaskStore(
      os.pwd / "target" / "test-identity-prompt" / "sched"
    )
    val none = SystemReminders
      .collectAllIO(
        isUserTurn = true,
        taskStore = store,
        sessionId = None,
        identityDelta = ""
      )
      .map(_.filter(_.category == "identity"))
      .unsafeRunSync()
    assertEquals(none.isEmpty, true)
    val some = SystemReminders
      .collectAllIO(
        isUserTurn = true,
        taskStore = store,
        sessionId = None,
        identityDelta = SystemReminders.renderIdentityChange("", "- Display name: \"小雨\"")
      )
      .map(_.filter(_.category == "identity"))
      .unsafeRunSync()
    some match
      case r :: Nil =>
        assert(r.content.contains("小雨"))
        assert(r.content.contains("authoritative"))
      case other => fail(s"expected exactly one identity reminder, got: $other")

  test("renderIdentityChange: current values authoritative, previous superseded, one line per fact"):
    val prev = AgentIdentityStore.selfSectionText("Nebula", Some("旧名"), Some("aa11bb22"))
    val curr = AgentIdentityStore.selfSectionText("Nebula", Some("小雨"), Some("cc33dd44"))
    val text = SystemReminders.renderIdentityChange(prev, curr)
    assert(text.contains("authoritative"))
    assert(text.contains("小雨"))
    assert(text.contains("cc33dd44".take(8)))
    assert(text.contains("superseded"))
    // previous 行内不换行（单行摘要）
    val prevLine = text.linesIterator.find(_.startsWith("(Previous:")).get
    assert(!prevLine.contains("\n"))

  test("renderIdentityChange: cleared identity renders the default-identity clause"):
    val text = SystemReminders.renderIdentityChange("some previous", "")
    assert(text.contains("default identity"))
end IdentityPromptSpec
