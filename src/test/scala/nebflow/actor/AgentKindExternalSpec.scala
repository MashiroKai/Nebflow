package nebflow.actor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.delegate.CancelCascade

import scala.concurrent.duration.*

/**
 * 裁定4「登记 AgentKind.External」+ 裁定5「删死枚举 Ephemeral / Plan」的机械验收。
 *
 * 裁定原文（作者 2026-11-05 09:38 · msg `11c13555-000082`）：
 * ```
 * 4. 外部 CLI 腿（external-*，无 AgentActor）：要不要登记进 AgentKind？
 *    → 登记 AgentKind.External（推荐）
 * 5. 死枚举 AgentKind.Ephemeral / Plan：删不删？
 *    → 删（推荐）
 * ```
 *
 * ## 本 spec 钉住的边
 * - **E1 记录形态**：外部腿记录 = `kind=External` + `ref=null` + 父/根会话透传。
 * - **E2 登记生命周期**：`attach` 进统一注册表、`detach` 摘下、重复 attach 幂等。
 * - **E3 可投递判据**：`External` 是唯一不可投递的 kind（其余全部可投递给 actor）。
 * - **E4 不进任务型面板集**：`filterActiveAgents` 不因登记而把外部腿当成 task 行。
 * - **E5 不进孤儿处置面**：父会话终态时，外部腿不会被判成「假孤儿」。
 * - **E6 词表**：`AgentKind.values` **恰好**是 `Root/Team/Flow/Delegate/External/SubTask`
 *   —— 死的 `Ephemeral`/`Plan` 不得回来。
 *
 * 零进程、零数据根：全部用 `Ref` + 纯函数。
 */
class AgentKindExternalSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def rec(sessionId: String, kind: AgentKind, parent: String = ""): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      // 本 spec 只判 kind / 注册表成员关系，从不投递消息；ref 一律 null（External
      // 本就是唯一允许 null 的 kind）。
      ref = null,
      kind = kind,
      rootSessionId = parent,
      parentSessionId = parent
    )

  // ── E6（先钉词表：其余用例的枚举前提）──────────────────────────────
  test("E6 词表：AgentKind 恰好是 Root/Team/Flow/Delegate/External/SubTask（死的 Ephemeral/Plan 不得复活）") {
    assertEquals(
      AgentKind.values.toList.map(_.toString),
      List("Root", "Team", "Flow", "Delegate", "External", "SubTask")
    )
  }

  // ── E1 ────────────────────────────────────────────────────────────────
  test("E1 记录形态：外部腿记录 kind=External、ref=null、根/父会话与起点透传") {
    val r = ExternalAgentRecord.build(
      sessionId = "external-codex-deadbeef",
      rootSessionId = "root-1",
      parentSessionId = "delegate-2",
      startedAt = 1234L
    )
    assertEquals(r.sessionId, "external-codex-deadbeef")
    assertEquals(r.kind, AgentKind.External)
    assert(r.ref == null, "外部腿记录必须 ref=null——它没有进程内 actor")
    assertEquals(r.rootSessionId, "root-1")
    assertEquals(r.parentSessionId, "delegate-2")
    assertEquals(r.startedAt, 1234L)
    assertEquals(r.status, AgentStatus.Idle)
  }

  // ── E2 ────────────────────────────────────────────────────────────────
  test("E2 登记生命周期：attach 进注册表、detach 摘下、重复 attach 幂等") {
    for
      registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
      r = ExternalAgentRecord.build("external-claude-1", "root-1", "delegate-1", 10L)
      _ <- ExternalAgentRecord.attach(registry, r)
      afterAttach <- registry.get
      _ <- ExternalAgentRecord.attach(registry, r.copy(startedAt = 20L))
      afterSecondAttach <- registry.get
      _ <- ExternalAgentRecord.detach(registry, "external-claude-1")
      afterDetach <- registry.get
      _ <- ExternalAgentRecord.detach(registry, "external-claude-1") // 幂等
      afterSecondDetach <- registry.get
    yield
      assertEquals(afterAttach.keySet, Set("external-claude-1"))
      assertEquals(afterSecondAttach.size, 1, "重复 attach 不得产生第二行")
      assertEquals(afterSecondAttach("external-claude-1").startedAt, 20L, "同 id 覆盖为新记录")
      assertEquals(afterDetach, Map.empty[String, AgentRecord])
      assertEquals(afterSecondDetach, Map.empty[String, AgentRecord])
  }

  // ── E3 ────────────────────────────────────────────────────────────────
  test("E3 可投递判据：External 是唯一不可投递的 kind") {
    val nonMessagable = AgentKind.values.toList.filterNot(ExternalAgentRecord.isMessagable)
    assertEquals(nonMessagable, List(AgentKind.External))
    assert(ExternalAgentRecord.isMessagable(AgentKind.Delegate))
    assert(ExternalAgentRecord.isMessagable(AgentKind.SubTask))
    assert(ExternalAgentRecord.isMessagable(AgentKind.Flow))
    assert(ExternalAgentRecord.isMessagable(AgentKind.Root))
  }

  // ── E4 ────────────────────────────────────────────────────────────────
  test("E4 面板集：filterActiveAgents 不把外部腿当 task 行（登记不改变既有面板语义）") {
    val registry = Map(
      "external-codex-1" -> rec("external-codex-1", AgentKind.External),
      "delegate-1" -> rec("delegate-1", AgentKind.Delegate),
      "subtask-1" -> rec("subtask-1", AgentKind.SubTask),
      "flow-1" -> rec("flow-1", AgentKind.Flow)
    )
    nebflow.gateway.WebSocketRoutes.filterActiveAgents(registry).map { rows =>
      assertEquals(rows.map(_.sessionId).sorted, List("delegate-1", "flow-1", "subtask-1"))
      assert(!rows.exists(_.kind == AgentKind.External), "外部腿不得进 task 面板集")
    }
  }

  // ── E5 ────────────────────────────────────────────────────────────────
  test("E5 孤儿面：父会话终态时外部腿不被判成假孤儿（ref=null 无从 Stop）") {
    val registry = Map(
      "external-codex-2" -> rec("external-codex-2", AgentKind.External, parent = "delegate-9"),
      "subtask-2" -> rec("subtask-2", AgentKind.SubTask, parent = "delegate-9")
    )
    val orphans = CancelCascade.detectOrphans(registry, Set("delegate-9"))
    assertEquals(
      orphans.map(_.child.sessionId),
      List("subtask-2"),
      "外部腿必须在孤儿处置面之外（否则会得到一条无法处置的假孤儿）"
    )
  }

end AgentKindExternalSpec
