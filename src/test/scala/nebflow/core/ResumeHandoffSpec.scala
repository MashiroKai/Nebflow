package nebflow.core

import io.circe.JsonObject
import munit.FunSuite
import nebflow.shared.{ContentBlock, Message, MessageRole}

/**
 * 无感恢复批（2026-11-07）验收 spec：[[ResumeHandoff]] 的交接单判据。
 *
 * 覆盖作者令点名的三条硬要求（⑥）：
 *   A. 中断那一轮**含工具调用** ⇒ 续跑后模型看到的是 `tool_result`，不是提示消息；
 *   B. **不含工具调用**的普通中断 ⇒ 仍能正常续（逐字回落既有文案，行为零改）；
 *   C. **旧格式会话可读**（历史里带旧的注入消息）⇒ 不崩、不重复配对、可再次续跑。
 *
 * 判据全部落在**模型可见的那一层**（注入块 / transcript 消息序列），不测日志文案。
 */
class ResumeHandoffSpec extends FunSuite:

  private def userText(t: String): Message = Message(MessageRole.User, Left(t))

  private def assistantToolUse(calls: (String, String)*): Message =
    Message(
      MessageRole.Assistant,
      Right(calls.toList.map { case (id, name) => ContentBlock.ToolUse(id, name, JsonObject.empty) })
    )

  private def assistantText(t: String): Message =
    Message(MessageRole.Assistant, Right(List(ContentBlock.Text(t))))

  private def userToolResults(results: (String, String)*): Message =
    Message(
      MessageRole.User,
      Right(results.toList.map { case (id, content) => ContentBlock.ToolResult(id, content, Some(false)) })
    )

  private val LegacyRestartNotice: String =
    "[system] The engine process restarted while your task was in flight. Your work so far (3 transcript " +
      "messages) is loaded below — continue the task from where you left off."

  /** 注入块里的 ToolResult 列表（模型实际读到的东西）。 */
  private def toolResults(p: ResumeHandoff.Plan): List[ContentBlock.ToolResult] =
    p.injectBlocks.getOrElse(Nil).collect { case r: ContentBlock.ToolResult => r }

  /** 注入块里的 Text 列表。 */
  private def textBlocks(p: ResumeHandoff.Plan): List[String] =
    p.injectBlocks.getOrElse(Nil).collect { case ContentBlock.Text(t) => t }

  // ══════════════════════════════════════════════════════════════════════════
  // A. 中断那一轮含工具调用 ⇒ tool_result 而非提示消息
  // ══════════════════════════════════════════════════════════════════════════

  test("A1 断点在工具调用中途：注入载荷 = tool_result 块，模型读不到任何 [system] 提示") {
    val transcript = List(
      userText("refactor the parser"),
      assistantToolUse("t1" -> "Edit", "t2" -> "Bash")
    )
    val p = ResumeHandoff.plan(transcript, ResumeHandoff.Cause.EngineRestart, legacyText = LegacyRestartNotice)

    assert(p.atToolBoundary, "must take the tool boundary")
    assertEquals(p.pending.map(_.toolUseId), List("t1", "t2"), "both interrupted calls are handed back")
    val results = toolResults(p)
    assertEquals(results.size, 2, "one tool_result per interrupted call")
    results.foreach { r =>
      assert(r.content.startsWith(ResumeHandoff.InterruptedToken), s"result must carry the marker: ${r.content}")
      assertEquals(r.isError, Some(true), "an interrupted call is an error-shaped result")
    }
    assert(textBlocks(p).isEmpty, "no instruction ⇒ no text block at all (the model sees ONLY the tool boundary)")
    assert(
      !textBlocks(p).exists(_.contains("[system]")),
      "the legacy [system] notice must NOT reach the model when a tool boundary exists"
    )
    assertEquals(p.transcript, transcript, "paired history is byte-for-byte untouched")
  }

  test("A2 已经完成的工具回合原样保留：只给未完成的那条补 result") {
    val transcript = List(
      userText("go"),
      assistantToolUse("done-1" -> "Read"),
      userToolResults("done-1" -> "file contents"),
      assistantToolUse("hanging-1" -> "Bash")
    )
    val p = ResumeHandoff.plan(transcript, ResumeHandoff.Cause.Crash, legacyText = "legacy")
    assert(p.atToolBoundary)
    assertEquals(p.pending.map(_.toolUseId), List("hanging-1"), "only the unfinished call is pending")
    val results = toolResults(p)
    assertEquals(results.size, 1)
    assertEquals(results.head.toolUseId, "hanging-1")
    assertEquals(p.transcript, transcript, "the completed round stays exactly as persisted")
  }

  test("A3 带新指令：tool_result 在前、指令文本块在后（协议块序），且走同一条注入命令") {
    val transcript = List(userText("go"), assistantToolUse("t7" -> "Bash"))
    val p = ResumeHandoff.plan(
      transcript,
      ResumeHandoff.Cause.Continuation,
      instruction = "also update the changelog",
      legacyText = "[system] legacy"
    )
    val blocks = p.injectBlocks.getOrElse(Nil)
    assert(blocks.head.isInstanceOf[ContentBlock.ToolResult], "tool_result must be the first block")
    assertEquals(textBlocks(p), List("also update the changelog"), "the instruction follows the tool boundary")

    val cmd = ResumeHandoff.userInput(p)
    assert(cmd.blocks.contains(blocks), "the delivered command carries the blocks (model-visible payload)")
    assertEquals(cmd.text, "also update the changelog", "text stays the human-facing line")
    assertEquals(cmd.source, Some(ResumeHandoff.Source))
  }

  test("A4 原因词入正文：不同中断原因给出不同的事实句（机器可核）") {
    val transcript = List(userText("go"), assistantToolUse("t9" -> "Bash"))
    val freeze = ResumeHandoff.plan(transcript, ResumeHandoff.Cause.Freeze)
    val restart = ResumeHandoff.plan(transcript, ResumeHandoff.Cause.EngineRestart)
    assert(toolResults(freeze).head.content.contains("frozen"))
    assert(toolResults(restart).head.content.contains("engine process restarted"))
    assertEquals(ResumeHandoff.causeWire(ResumeHandoff.Cause.EngineRestart), "engine-restart")
  }

  // ══════════════════════════════════════════════════════════════════════════
  // B. 不含工具调用的普通中断 ⇒ 仍能正常续
  // ══════════════════════════════════════════════════════════════════════════

  test("B1 普通中断（末条是 assistant 文本）：无块注入，逐字回落既有文案") {
    val transcript = List(userText("do the thing"), assistantText("I was halfway through…"))
    val p = ResumeHandoff.plan(transcript, ResumeHandoff.Cause.EngineRestart, legacyText = LegacyRestartNotice)

    assert(!p.atToolBoundary, "no tool call in flight ⇒ no tool boundary")
    assertEquals(p.injectBlocks, None, "injection stays a plain message")
    assertEquals(p.injectText, LegacyRestartNotice, "the pre-change text is preserved byte-for-byte")
    assertEquals(p.transcript, transcript, "history unchanged")
    assertEquals(p.pending, Nil)

    val cmd = ResumeHandoff.userInput(p)
    assertEquals(cmd.blocks, None)
    assertEquals(cmd.text, LegacyRestartNotice)
  }

  test("B2 空历史（首轮前崩溃）：同样回落文本，绝不造 tool_result") {
    val p = ResumeHandoff.plan(Nil, ResumeHandoff.Cause.EngineRestart, legacyText = "original prompt")
    assert(!p.atToolBoundary)
    assertEquals(p.injectBlocks, None)
    assertEquals(p.injectText, "original prompt")
    assertEquals(p.transcript, Nil)
  }

  // ══════════════════════════════════════════════════════════════════════════
  // C. 旧格式会话可读（历史里带旧的注入消息）
  // ══════════════════════════════════════════════════════════════════════════

  test("C1 旧格式（tool_use 之后已经有旧注入文本）：就地修补 + 文本注入，旧消息逐字保留") {
    val legacy: List[Message] = List(
      userText("do the thing"),
      assistantToolUse("old-1" -> "Bash"),
      userText(LegacyRestartNotice) // 改前的恢复腿插进去的那条消息
    )
    val p = ResumeHandoff.plan(legacy, ResumeHandoff.Cause.EngineRestart, legacyText = "next notice")

    assert(!p.atToolBoundary, "a legacy session already continued past the call ⇒ stay on the textual leg")
    assertEquals(p.injectBlocks, None)
    assertEquals(p.injectText, "next notice")
    assertEquals(p.repairedMidHistory, 1, "the dangling call is repaired in place")
    assertEquals(p.transcript.size, legacy.size + 1, "exactly one repair message is inserted")
    // 修补消息紧随那条 assistant 消息，内容是这则调用的结果
    val repaired = p.transcript(2)
    assertEquals(repaired.role, MessageRole.User)
    val repairedResults = repaired.content.toOption.toList.flatten.collect { case r: ContentBlock.ToolResult => r }
    assertEquals(repairedResults.map(_.toolUseId), List("old-1"))
    assert(repairedResults.head.content.startsWith(ResumeHandoff.InterruptedToken))
    // 旧注入消息原样留着（存量会话的读数不丢）
    assertEquals(p.transcript.last, legacy.last)
    assertEquals(p.transcript.head, legacy.head)
  }

  test("C2 旧格式幂等：修补过的历史再判一次 = 不再生长（可反复续跑）") {
    val legacy: List[Message] = List(
      userText("do the thing"),
      assistantToolUse("old-1" -> "Bash"),
      userText(LegacyRestartNotice)
    )
    val once = ResumeHandoff.plan(legacy, ResumeHandoff.Cause.EngineRestart, legacyText = "n1")
    val twice = ResumeHandoff.plan(once.transcript, ResumeHandoff.Cause.EngineRestart, legacyText = "n2")

    assertEquals(twice.transcript, once.transcript, "second pass is a no-op on history")
    assertEquals(twice.repairedMidHistory, 0)
    assert(!twice.atToolBoundary)
    assertEquals(twice.injectText, "n2")
  }

  test("C3 完全干净的完整历史（无悬挂）：零改动、零修补") {
    val clean: List[Message] = List(
      userText("go"),
      assistantToolUse("x1" -> "Read"),
      userToolResults("x1" -> "ok"),
      assistantText("done")
    )
    val p = ResumeHandoff.plan(clean, ResumeHandoff.Cause.Continuation, legacyText = "n")
    assertEquals(p.transcript, clean)
    assertEquals(p.repairedMidHistory, 0)
    assertEquals(p.pending, Nil)
    assert(!p.atToolBoundary)
  }
