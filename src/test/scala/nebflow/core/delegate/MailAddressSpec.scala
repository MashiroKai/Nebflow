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

  test("kinds without a Mail face → None (Root / Team / Ephemeral / SubTask / legacy dag- )") {
    List(
      AgentKind.Root,
      AgentKind.Team,
      AgentKind.Ephemeral,
      AgentKind.Plan
    ).foreach { k =>
      assertEquals(MailAddress.of(k, k.toString.toLowerCase + "-1"), None, s"kind $k")
    }
    // SubTask 会话（subtask-*）与旧 dag-* Flow 会话：Mail 侧无解析腿。
    assertEquals(MailAddress.of(AgentKind.SubTask, "subtask-7a7b7c7d"), None)
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
    List("project:stand", "Nebula", "kernel", "delegate:", "node:", "") .foreach { bad =>
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
