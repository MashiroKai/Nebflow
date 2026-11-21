package nebflow.core.node

import munit.FunSuite
import nebflow.actor.AgentDef
import nebflow.core.node.NodeRunner.SpawnParams

/**
 * 阶段 A（G1 收口）· sessionName 等价路径专测。
 *
 * 旧 root 直连：`sessionName = metaOpt.map(_.name)`（meta 缺席 ⇒ None，AgentStart 线上无该字段）。
 * 收口后 root 经 spawnAgentActor：`sessionNameAbsent=true` ⇒ None；meta 在场 ⇒ Some(name)。
 * 团队成员/其余路径：恒 Some(sessionName)（旧行为零变化）。
 *
 * 边界：本 spec 钉住 SpawnParams.sessionNameOpt 这一唯一取值口径（spawnActor 直接使用它）。
 * 线上 AgentStart 帧需一个真实回合（需 LLM），不在本单元 spec 范围；线上字段由
 * AgentSessionExecution.scala AgentStart(…, state.sessionName) 承载，取值来源即本方法。
 */
class SpawnSessionNameAbsentSpec extends FunSuite:

  private def params(name: String, absent: Boolean): SpawnParams =
    SpawnParams(
      agentDef = AgentDef(name = "Nebula", description = "t"),
      resources = null,
      sessionId = "s-1",
      sessionName = name,
      depth = 0,
      parentRef = None,
      wsSend = _ => cats.effect.IO.unit,
      sessionNameAbsent = absent
    )

  test("root with absent session meta: sessionNameOpt is None (legacy metaOpt.map ⇒ None)") {
    assertEquals(params("", absent = true).sessionNameOpt, None)
  }

  test("root with present meta: sessionNameOpt is Some(name) — the name is kept verbatim") {
    assertEquals(params("我的会话", absent = false).sessionNameOpt, Some("我的会话"))
  }

  test("present meta with empty name stays Some(\"\") — distinct from the absent None on the wire") {
    assertEquals(params("", absent = false).sessionNameOpt, Some(""))
    assert(params("", absent = false).sessionNameOpt != params("", absent = true).sessionNameOpt)
  }

  test("default (non-root callers, e.g. team member) is Some(sessionName) — legacy behaviour unchanged") {
    assertEquals(params("team-member", absent = false).sessionNameOpt, Some("team-member"))
  }

end SpawnSessionNameAbsentSpec
