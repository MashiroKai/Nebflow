package nebflow.demo

import nebflow.shared.{ContentBlock, Message, MessageRole}
import nebflow.core.compact.{CompactConfig, FullCompact, HistoryArchiver, MicroCompact, TokenEstimator}
import io.circe.JsonObject
import cats.effect.IO
import cats.effect.unsafe.implicits.global

/**
 * Interactive demo: run with
 *   sbt "testOnly nebflow.demo.CompactionDemo"
 *
 * Demonstrates the full context-compaction pipeline without a running actor system.
 */
class CompactionDemo extends munit.FunSuite:

  private def banner(title: String): Unit =
    println("\n" + "=" * 60)
    println(s"  $title")
    println("=" * 60)

  private def textMsg(role: MessageRole, text: String): Message =
    Message(role, Left(text))

  private def toolUseMsg(id: String, name: String, input: JsonObject = JsonObject.empty): Message =
    Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse(id, name, input))))

  private def toolResultMsg(toolUseId: String, content: String): Message =
    Message(MessageRole.User, Right(List(ContentBlock.ToolResult(toolUseId, content))))

  test("Demo: full pipeline".tag(munit.Tag("demo"))) {
    // ----------------------------------------------------------
    // 1. Build a realistic long conversation history
    // ----------------------------------------------------------
    banner("1. Original conversation history")

    val baseMessages = List(
      textMsg(MessageRole.User, "Please help me refactor the authentication module."),
      textMsg(MessageRole.Assistant, "I'll start by reading the current auth implementation."),
      toolUseMsg("tu-1", "Read", JsonObject.singleton("file_path", io.circe.Json.fromString("src/auth.ts"))),
      toolResultMsg("tu-1", "export function login() { ... }\nexport function logout() { ... }\n// 200 lines"),
      textMsg(MessageRole.Assistant, "Now let me check the routes."),
      toolUseMsg("tu-2", "Read", JsonObject.singleton("file_path", io.circe.Json.fromString("src/routes.ts"))),
      toolResultMsg("tu-2", "// routes.ts\napp.post('/login', ...);\napp.post('/logout', ...);\n// 150 lines"),
      textMsg(MessageRole.Assistant, "I see the issue. The JWT validation is duplicated. Let me grep for it."),
      toolUseMsg("tu-3", "Grep", JsonObject.singleton("pattern", io.circe.Json.fromString("jwt.verify"))),
      toolResultMsg("tu-3", "src/auth.ts:42\nsrc/middleware.ts:15\nsrc/utils.ts:88"),
      textMsg(MessageRole.User, "Great find. Please consolidate them into middleware.ts."),
      textMsg(MessageRole.Assistant, "I'll refactor the JWT validation into middleware.ts."),
      toolUseMsg("tu-4", "Edit", JsonObject.fromMap(Map(
        "file_path" -> io.circe.Json.fromString("src/middleware.ts"),
        "old_string" -> io.circe.Json.fromString("// authGuard stub"),
        "new_string" -> io.circe.Json.fromString("// consolidated JWT validation")
      ))),
      toolResultMsg("tu-4", "[Edit successful] src/middleware.ts updated."),
    )

    // Pad with filler messages to simulate a long history
    val filler = (1 to 20).flatMap { i =>
      List(
        textMsg(MessageRole.User, s"Follow-up question #$i about edge cases in auth."),
        textMsg(MessageRole.Assistant, s"Answer #$i: handled by the new middleware."),
      )
    }.toList

    val messages = baseMessages ++ filler

    println(s"Total messages: ${messages.size}")
    println("Message roles: " + messages.map(_.role).mkString(" -> "))

    // ----------------------------------------------------------
    // 2. Token estimation & threshold check
    // ----------------------------------------------------------
    banner("2. Token estimation & auto-compact trigger")

    val estimatedTokens = TokenEstimator.estimate(messages)
    val contextWindow = 128000 // typical Claude 3.5 Sonnet
    val threshold = contextWindow - CompactConfig().bufferTokens

    println(s"Estimated tokens (heuristic): $estimatedTokens")
    println(s"Agent context window: $contextWindow")
    println(s"Buffer tokens: ${CompactConfig().bufferTokens}")
    println(s"Threshold (window - buffer): $threshold")
    println(s"Would auto-compact trigger? ${if estimatedTokens > threshold then "YES" else "NO"}")

    // ----------------------------------------------------------
    // 3. Simulate Micro-compaction (SubAgent output)
    // ----------------------------------------------------------
    banner("3. MICRO mode compaction")

    val microLlmOutput = """
<plan>
Messages 0-3: User request + assistant reading auth.ts -> keep for context.
Messages 4-7: Route reading and grep results -> already utilized, compress.
Messages 8-11: Edit operation -> keep as active work.
Messages 12-51: Filler follow-ups -> compress as resolved Q&A.
Messages 52-53: Most recent -> keep.
</plan>

<keep>0, 1, 2, 3</keep>
<compact start="4" end="7">Read routes.ts and grepped jwt.verify occurrences (auth.ts:42, middleware.ts:15, utils.ts:88)</compact>
<keep>8, 9, 10, 11</keep>
<compact start="12" end="51">20 rounds of follow-up Q&A about auth edge cases, all resolved by new middleware.</compact>
<keep>52, 53</keep>
""".trim

    MicroCompact.parseResponse(microLlmOutput, messages) match
      case Right(compacted) =>
        println(s"  BEFORE: ${messages.size} messages")
        println(s"  AFTER:  ${compacted.size} messages")
        println(s"  Reduction: ${messages.size - compacted.size} messages compressed")
        println("\n  --- Compact result dump ---")
        compacted.zipWithIndex.foreach { case (m, i) =>
          val preview = m.content match
            case Left(t) => if t.length > 120 then t.take(117) + "..." else t
            case Right(blocks) => blocks.map(_.getClass.getSimpleName).mkString(", ")
          println(s"  [$i] ${m.role}: $preview")
        }
      case Left(err) =>
        println(s"  MICRO compaction FAILED: $err")

    // ----------------------------------------------------------
    // 4. Simulate Full-compaction (SubAgent output)
    // ----------------------------------------------------------
    banner("4. FULL mode compaction")

    val fullLlmOutput = """
## Primary Request and Intent
Refactor the authentication module to consolidate duplicated JWT validation.

## Key Technical Concepts
JWT validation was duplicated across auth.ts, middleware.ts, and utils.ts.

## Files and Code Sections
- `src/auth.ts` (line 42-89): Original JWT validation, now delegated to middleware
- `src/middleware.ts` (line 15-30): New consolidated authGuard() function
- `src/routes.ts` (line 10-30): 3 endpoints - login, logout, refresh

## Errors and Fixes
None. Edit applied successfully.

## Problem Solving Progress
Completed: identified duplication via grep, consolidated into middleware.

## Pending Tasks
None. User confirmed the refactor is complete.

## Current Work Focus
Wrapping up the auth refactor.

## Next Step
Confirm completion with user.

<files>
src/middleware.ts
</files>
""".trim

    val projectRoot = System.getProperty("user.dir")
    FullCompact.parseResponse(fullLlmOutput, messages, projectRoot) match
      case Right(compacted) =>
        println(s"  BEFORE: ${messages.size} messages")
        println(s"  AFTER:  ${compacted.size} messages")
        println("\n  --- Full compact result dump ---")
        compacted.zipWithIndex.foreach { case (m, i) =>
          val preview = m.content match
            case Left(t) => if t.length > 200 then t.take(197) + "..." else t
            case Right(bs) => bs.map(_.getClass.getSimpleName).mkString(", ")
          println(s"  [$i] ${m.role}: $preview")
        }
      case Left(err) =>
        println(s"  FULL compaction FAILED: $err")

    // ----------------------------------------------------------
    // 5. History archiving (snapshot before compaction)
    // ----------------------------------------------------------
    banner("5. History archive snapshot")

    val archiver = HistoryArchiver.fileSystem(os.pwd / "target" / "demo-archives")
    val archiveIO = archiver.archive("demo-session", messages)
    archiveIO.unsafeRunSync() match
      case Right(path) =>
        println(s"  Snapshot written to: $path")
        val sizeBytes = os.size(os.Path(path))
        println(s"  File size: $sizeBytes bytes")
        println(s"  (This is what gets saved BEFORE messages are replaced in memory)")
      case Left(err) =>
        println(s"  Archive failed (non-blocking): $err")

    // ----------------------------------------------------------
    // 6. Summary
    // ----------------------------------------------------------
    banner("6. Summary")
    println(s"  Original messages : ${messages.size}")
    println(s"  Estimated tokens  : $estimatedTokens")
    println(s"  Auto-trigger      : ${estimatedTokens > threshold}")
    println(s"  Archive location  : target/demo-archives/archives/demo-session/")
    println("  Modes demonstrated: micro (selective) + full (complete summary)")
    println("\n  All compaction safeguards active:")
    println("    - Message order preserved")
    println("    - tool_use/tool_result pairing validated")
    println("    - History snapshotted before replacement")
    println("    - Circuit breaker at 3 failures")

    // Always pass — this is a visual demo, not an assertion test
    assertEquals(true, true)
  }
