package nebflow.core.delegate

import munit.FunSuite
import nebflow.actor.AgentKind
import nebflow.core.tools.MailTool

/**
 * Mail 地址形单点（name/address 批 2026-11-05）的纯单测。
 *
 * 这三条纪律各自都有一次「绿了但其实没验证」的陷阱，故逐条钉住：
 *  ① 形态与 Mail 解析面同形 → 用 MailTool 的同一组前缀常量做闸（不是自证）；
 *  ② 只按记录推导、不反猜 → 无 ref（无节点名）/无 Mail 面的 kind 一律 None；
 *  ③ 派生 + 解析互逆（parse(wire) 回到同一形态）→ 读面给出的字符串可原样投递。
 */
class MailAddressSpec extends FunSuite:

  private val NodeName = "worker-1"

  // ── ① 三种形态：派生 ────────────────────────────────────────

  test("delegate batch → delegate:<sessionId>") {
    val sid = "delegate-Explorer-aaaa1111"
    assertEquals(MailAddress.of(AgentKind.Delegate, sid), Some(s"delegate:$sid"))
  }

  test("kernel instance (delegate-kernel-* session, kind Delegate) → kernel:<id>") {
    val sid = "delegate-kernel-1f2e3d4c"
    assertEquals(MailAddress.of(AgentKind.Delegate, sid), Some(s"kernel:$sid"))
  }

  test("Project node session (node-* + node name) → node:<nodeName>") {
    val sid = "node-819"
    assertEquals(MailAddress.of(AgentKind.Flow, sid, Some(NodeName)), Some(s"node:$NodeName"))
  }

  // ── ② 不编造：ref 不足 / 无 Mail 面 ⇒ None ──────────────────

  test("node session without a node name → None (node-<uuid> carries no node id; never guess)") {
    assertEquals(MailAddress.of(AgentKind.Flow, "node-819"), None)
    assertEquals(MailAddress.of(AgentKind.Flow, "node-819", Some("   ")), None)
  }

  test("kinds without a Mail face → None (Root / External / legacy dag- )") {
    // 裁定5 删除死枚举 Ephemeral / Plan 后，此处改挂裁定4 新增的 External
    // （外部 CLI 腿：Mail 续传走 delegate:<id> 面，`external-*` 本身无 Mail 地址）。
    List(
      AgentKind.Root,
      AgentKind.Root,
      AgentKind.External
    ).foreach { k =>
      assertEquals(MailAddress.of(k, k.toString.toLowerCase + "-1"), None, s"kind $k")
    }
    // SubTask 会话（subtask-*）与旧 dag-* Flow 会话：Mail 侧无解析腿。
    assertEquals(MailAddress.of(AgentKind.Flow, "subtask-7a7b7c7d"), None)
    assertEquals(MailAddress.of(AgentKind.Flow, "dag-legacy-1234"), None)
    // dispatcher- 会话：Mail 的 project 地址面已退役（派发走 Delegate），无续传地址。
    assertEquals(MailAddress.of(AgentKind.Flow, "dispatcher-9f9e9d9c"), None)
  }

  test("empty session id → None") {
    assertEquals(MailAddress.of(AgentKind.Delegate, ""), None)
    assertEquals(MailAddress.of(AgentKind.Delegate, "   "), None)
  }

  // ── ③ 与 Mail 解析面同形（同一组前缀）──────────────────────

  test("derived addresses pass Mail's layered-address gate (delegate/kernel/node)") {
    val cases = List(
      (MailAddress.of(AgentKind.Delegate, "delegate-Explorer-aaaa1111"), "delegate"),
      (MailAddress.of(AgentKind.Delegate, "delegate-kernel-1f2e3d4c"), "kernel"),
      (MailAddress.of(AgentKind.Flow, "node-819", Some(NodeName)), "node")
    )
    cases.foreach { case (addrOpt, form) =>
      val addr = addrOpt.getOrElse(fail(s"address missing for form $form"))
      assertEquals(MailTool.layeredAddressFormOf(addr), Some(form), s"$addr must be a Mail layered address")
      assert(MailTool.isLayeredAddress(addr), s"$addr must be pasteable into Mail")
    }
  }

  test("Mail's gate rejects the forms this object never emits (no silent fuzzy matching)") {
    List("project:stand", "Nebula", "kernel", "delegate:", "node:", "").foreach { bad =>
      assertEquals(MailTool.layeredAddressFormOf(bad), None, s"'$bad' must not pass")
    }
  }

  test("parse/derive are inverse: parse(addr).wire == addr and the form matches the source kind") {
    val cases = List(
      (AgentKind.Delegate, "delegate-Explorer-aaaa1111", None, MailAddress.Form.Delegate),
      (AgentKind.Delegate, "delegate-kernel-1f2e3d4c", None, MailAddress.Form.Kernel),
      (AgentKind.Flow, "node-819", Some(NodeName), MailAddress.Form.Node)
    )
    cases.foreach { case (kind, sid, nodeName, form) =>
      val addr = MailAddress.of(kind, sid, nodeName).getOrElse(fail(s"no address for $sid"))
      val parsed = MailAddress.parse(addr).getOrElse(fail(s"parse failed for $addr"))
      assertEquals(parsed.form, form)
      assertEquals(parsed.wire, addr)
    }
  }

  test("parse keeps the target verbatim (ids with dots/dashes survive a round trip)") {
    val sid = "delegate-nebflow-f2451d50"
    val addr = MailAddress.of(AgentKind.Delegate, sid).get
    assertEquals(MailAddress.parse(addr).map(_.target), Some(sid))
  }

  // ── ④ ask 形态（ask-mail 批 2026-11-16）─────────────────────
  // 身份判据 = `ask-` 会话 id 前缀（AskSessions.Prefix 单源），**不是 kind**：
  // AgentKind 无 ask 值（ask 会话不进统一注册表的 kind 面，登记面 = AskSessionRegistry
  // 绑定表），故 of() 的 ask 分支按 sid 前缀先判、kind 无关。

  test("ask session id → ask:<sessionId> (kind-agnostic: the ask- prefix IS the identity)") {
    val sid = "ask-1a2b3c4d"
    assertEquals(MailAddress.of(AgentKind.Delegate, sid), Some(s"ask:$sid"))
    assertEquals(MailAddress.of(AgentKind.Flow, sid), Some(s"ask:$sid"))
    // ask 会话 id 短于 8hex / 空白仍不编造目标（nonEmpty 闸在 kind 分支之前）。
    assertEquals(MailAddress.of(AgentKind.Delegate, "   "), None)
  }

  test("ask addresses pass Mail's layered-address gate and parse back (wire == addr, target verbatim)") {
    val sid = "ask-9f8e7d6c"
    val addr = MailAddress.of(AgentKind.Delegate, sid).get
    assertEquals(MailTool.layeredAddressFormOf(addr), Some("ask"), s"$addr must be a Mail layered address")
    assert(MailTool.isLayeredAddress(addr), s"$addr must be pasteable into Mail")
    val parsed = MailAddress.parse(addr).getOrElse(fail(s"parse failed for $addr"))
    assertEquals(parsed.form, MailAddress.Form.Ask)
    assertEquals(parsed.wire, addr)
    assertEquals(parsed.target, sid)
  }
end MailAddressSpec
