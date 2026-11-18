package nebflow.agent

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import nebflow.core.ask.AskSessions
import nebflow.core.tools.{Tool, ToolContext, ToolRegistry}

/**
 * Card tool revive · authorization-face gate (card-tool-revive batch, 2026-11-18).
 *
 * 作者令「找到我们 git 历史中的 card 工具 让 canvas ask 可以使用 card 工具」。
 * 本 spec 钉死**收窄后的授权面**：Card 只给 Canvas Ask，其余一切身份维持现状。
 *
 * 它能抓住的两类破坏（破坏 → 红）：
 *   ① **Card 被授予 Ask 之外的身份** —— 任一非 ask 身份的装配面或 LLM 面出现
 *      `Card` ⇒ 「②」组断言红；
 *   ② **Ask 拿不到 Card** —— ask 早退面缺 `Card` ⇒ 「①」组断言红。
 *      **未修源码直跑本 spec 即红**（复活前注册表无 `Card` ⇒ ① 与 ①b 红；
 *      且基线还有一个真实的 allowed-set 泄漏面 ⇒ ② 也红）。
 *
 * 身份枚举**不在本文件**：逐身份读数走 [[ToolFaceReadings]] 那一份（作者令
 * 「枚举机制请做成可复用的一份」）——本 spec 只消费它，禁在此另写第二套表。
 * 改身份/加身份一律改 `ToolFaceReadings.Identities`。
 */
class CardToolReviveFaceSpec extends munit.FunSuite:

  private val AskLeg = "ask (Canvas Ask session leg)"

  /**
   * Resolve Card through the **registry**, never through the `CardTool` symbol.
   *
   * 这是「未修源码直跑即红」的关键：复活前树上没有 `CardTool` 这个类型，若本
   * spec 直接引它，基线只会**编译失败**（红在编译期，不是红在断言）。走注册表
   * 查名 ⇒ 本 spec 在基线树上照常编译并**断言失败**，闸的检测力可被逐条读出。
   */
  private def cardTool: Tool =
    ToolRegistry.TOOL_MAP.getOrElse("Card", fail("Card is not registered — the revive is absent"))

  private def nonAskRows: List[ToolFaceReadings.Row] =
    ToolFaceReadings.rows.filterNot(_.label == AskLeg)

  // ── ① Ask 拿得到 Card ────────────────────────────────────────────────────

  test("① Canvas Ask is the ONE identity whose face carries Card"):
    val ask = ToolFaceReadings.row(AskLeg)
    assert(
      ask.allowedHas("Card"),
      s"the ask early-return face must carry Card; got ${ask.allowed.toList.sorted}"
    )
    // ask 面是封闭集（早退，先于一切声明/固定/插件授予）——逐字相等，不是包含。
    assertEquals(
      ask.allowed,
      AskSessions.AllowedTools,
      "the ask face must be exactly AskSessions.AllowedTools (closed set)"
    )
    // 单点：名字必须在 AskExclusiveTools 里——它同时是「带上 ask 面」与
    // 「从其余身份剥净」两个机制的同一来源；写进 AllowedTools 之外的任何地方
    // 都会让两侧失配。
    assert(AskSessions.AskExclusiveTools.contains("Card"), "Card must ride AskExclusiveTools (the single point)")
    // LLM 实际面：注册表里能取到 Card 的 schema（否则名字进集也发不出去）。
    assert(
      ask.llmFaceHas("Card"),
      s"Card schema must reach the ask LLM face; got ${ask.llmFace.toList.sorted}"
    )

  test("①b Card is registered, with the original parameter face and no new capability"):
    assert(ToolRegistry.TOOL_MAP.contains("Card"), "Card must be registered in ToolRegistry")
    val td = ToolRegistry.ALL_TOOLS.find(_.name == "Card")
    assert(td.isDefined, "Card must appear in ALL_TOOLS (schema delivered to the model)")
    val props = td.get.inputSchema("properties").flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)
    // 原始设计的参数面：html / filePath / title（一字未改，禁新能力）。
    assertEquals(props, Set("html", "filePath", "title"), "the revived parameter face is the original one")

  // ── ② 其余身份一律拿不到 ────────────────────────────────────────────────

  test("② no non-ask identity can see Card — neither in the allowed set nor on the LLM face"):
    val rows = nonAskRows
    assert(rows.nonEmpty, "the shared readings table lost its non-ask identities")
    rows.foreach { r =>
      assert(!r.allowedHas("Card"), s"Card leaked onto a non-ask identity (allowed set): ${r.label} => ${r.allowed.toList.sorted}")
      assert(!r.llmFaceHas("Card"), s"Card leaked onto a non-ask identity (LLM face): ${r.label} => ${r.llmFace.toList.sorted}")
    }

  test("②c the plugin builtin whitelist and the remote-executable set cannot smuggle Card in"):
    assert(
      !nebflow.core.plugin.PluginRegistry.BuiltinToolWhitelist.contains("Card"),
      "Card must not be plugin-grantable"
    )
    assert(
      !nebflow.core.tools.RemoteExecutor.remoteableTools.contains("Card"),
      "Card must not be remotely executable"
    )

  // ── ③ 工具内侧第二保险（挂载面之外的 fail-closed）──────────────────────

  test("③ the in-tool identity gate refuses any non-ask context before any side effect"):
    val refused =
      cardTool
        .call(
          JsonObject("html" -> io.circe.Json.fromString("<div>should never render</div>")),
          ToolContext(projectRoot = "/tmp", sessionId = None)
        )
        .unsafeRunSync()
    assert(refused.isLeft, "a session-less ctx must be refused by the in-tool gate")
    val msg = refused.left.toOption.map(_.message).getOrElse("")
    assert(
      msg.contains("Canvas Ask") && msg.contains("session"),
      s"the refusal must name the ask-only face; got: $msg"
    )
    // 普通会话 id（delegate-… 形态）同样拒。
    val refusedDelegate =
      cardTool
        .call(
          JsonObject("html" -> io.circe.Json.fromString("<div>x</div>")),
          ToolContext(projectRoot = "/tmp", sessionId = Some("delegate-abcdef01"))
        )
        .unsafeRunSync()
    assert(refusedDelegate.isLeft, "a delegate- session must be refused by the in-tool gate")
    // 主会话 uuid 形态同样拒。
    val refusedUuid =
      cardTool
        .call(
          JsonObject("html" -> io.circe.Json.fromString("<div>x</div>")),
          ToolContext(projectRoot = "/tmp", sessionId = Some("3f2b0c1a-1111-2222-3333-444455556666"))
        )
        .unsafeRunSync()
    assert(refusedUuid.isLeft, "a main-session uuid must be refused by the in-tool gate")

  // ── ④ Pop 语义零触碰（红线回归锚）───────────────────────────────────────

  test("④ Pop keeps its Nebula-exclusive face — the Card revival does not widen it"):
    // Pop 的「不可授予他人」红线单点 = `AgentCore.RootExclusiveTools`（注释里称
    // NebulaExclusiveTools 的那一集；非 Nebula 身份一律剥）。本批零触碰。
    assert(AgentCore.RootExclusiveTools.contains("Pop"), "Pop stays Nebula-exclusive")
    assert(!AgentCore.RootExclusiveTools.contains("Card"), "Card must not ride the Nebula-exclusive set")
    assert(ToolRegistry.TOOL_MAP.contains("Pop"), "Pop stays registered")
    // Pop 不在 ask 面（ask 面是封闭集，Card 的复活不得顺带把 Pop 带进去）。
    assert(!AskSessions.AllowedTools.contains("Pop"), "Pop must NOT enter the ask face")
    // Pop 仍只挂在 Nebula 的编排面上；Card 仍不在 Nebula 面。
    val nb = ToolFaceReadings.row("Nebula root (converged, decl=[])")
    assert(nb.allowedHas("Pop"), "Pop stays on the Nebula root face")
    assert(!nb.allowedHas("Card"), "Nebula root face must stay Card-free")
    val general = ToolFaceReadings.row("general (tools=[*])")
    assert(!general.allowedHas("Pop"), "Pop stays off the general face")

  test("④b Pop keeps its delegation red line (not plugin-grantable, not remoteable)"):
    assert(
      !nebflow.core.plugin.PluginRegistry.BuiltinToolWhitelist.contains("Pop"),
      "Pop must not be plugin-grantable (2026-09-10 ruling, untouched)"
    )
    assert(
      !nebflow.core.tools.RemoteExecutor.remoteableTools.contains("Pop"),
      "Pop must not be remotely executable"
    )

end CardToolReviveFaceSpec
