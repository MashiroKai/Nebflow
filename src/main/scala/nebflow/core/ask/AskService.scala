package nebflow.core.ask

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.agent.SharedResources
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.core.{NebflowLogger, ToolExecResult, summarizeToolResult}
import nebflow.shared.*

/**
 * Standalone Ask service — direct LLM Q&A with tool support.
 *
 * Does NOT depend on the agent library. System prompt and allowed tools
 * are hardcoded, matching the former agents/Ask/agent.json + system.md.
 */
object AskService:

  private val logger = NebflowLogger.forName("nebflow.ask")

  // --- Hardcoded config (from former agents/Ask/) ---

  val SystemPrompt: String =
    """You are a helpful assistant answering a quick follow-up question about an ongoing conversation.
You have access to file reading, code search, and web search tools to help answer accurately.

Rules:
- Answer the user's question directly and concisely.
- Use tools when needed to find accurate information.
- Your response will NOT be saved to the conversation history.
- This is a single exchange: answer the question, then stop.
"""

  /** Allowed tool names — mirrors the former agent.json "tools" field. */
  val AllowedToolNames: Set[String] = Set("Read", "Glob", "Grep", "WebSearch", "WebFetch", "Curl")

  /** Resolved tool definitions filtered by allowed names. */
  def toolDefs: List[ToolDefinition] =
    ToolRegistry.ALL_TOOLS.filter(t => AllowedToolNames.contains(t.name))

  /** Max tool-calling rounds before giving up. */
  private val MaxRounds = 20

  // ------------------------------------------------------------------
  // Result type
  // ------------------------------------------------------------------

  case class AskResult(text: String, durationMs: Long, model: Option[String])

  // ------------------------------------------------------------------
  // Public API
  // ------------------------------------------------------------------

  /**
   * Run an Ask Q&A loop: LLM → tools → LLM → ... → final answer.
   *
   * @param question     User's follow-up question
   * @param history      Existing conversation messages for context
   * @param toolContext  Tool execution context
   * @param resources    Shared resources (LLM handle, etc.)
   * @param sessionId    Session identifier
   * @param wsSend       WebSocket send callback for streaming events
   * @return Final answer text with metadata
   */
  def ask(
    question: String,
    history: List[Message],
    toolContext: ToolContext,
    resources: SharedResources,
    sessionId: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[AskResult] =
    val askMsg = Message(MessageRole.User, Left(question))
    val allMsgs = history :+ askMsg
    askLoop(allMsgs, toolContext, resources, sessionId, wsSend, System.currentTimeMillis())
  end ask

  // ------------------------------------------------------------------
  // Internal: LLM loop
  // ------------------------------------------------------------------

  private def askLoop(
    initialMsgs: List[Message],
    toolContext: ToolContext,
    resources: SharedResources,
    sessionId: String,
    wsSend: io.circe.Json => IO[Unit],
    startTime: Long,
    round: Int = 0
  ): IO[AskResult] =
    if round > MaxRounds then
      IO.pure(AskResult("(Ask reached maximum tool rounds)", System.currentTimeMillis() - startTime, None))
    else
      val request = LlmRequest(
        messages = initialMsgs,
        sessionId = sessionId,
        agentId = "ask",
        tools = Some(toolDefs),
        maxTokens = Some(resources.agentLibrary.globalMaxTokens),
        systemStable = Some(SystemPrompt)
      )
      for
        chunks <- resources.llm
          .sendStream(request)
          .evalTap {
            case StreamChunk.TextDelta(delta) =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "askTextDelta".asJson,
                  "sessionId" -> sessionId.asJson,
                  "delta" -> delta.asJson
                )
              )
            case _ => IO.unit
          }
          .compile
          .toList
        text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
        toolCalls = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
        meta = chunks.collectFirst { case StreamChunk.Done(_, _, Some(m), _) => m }
        result <-
          if toolCalls.isEmpty then
            val durationMs = System.currentTimeMillis() - startTime
            wsSend(
              io.circe.Json.obj(
                "type" -> "askDone".asJson,
                "sessionId" -> sessionId.asJson,
                "durationMs" -> durationMs.asJson,
                "model" -> meta.map(_.model).getOrElse("").asJson
              )
            ) *> IO.pure(AskResult(text, durationMs, meta.map(_.model)))
          else
            executeTools(toolCalls, toolContext, sessionId, wsSend).flatMap { toolResults =>
              val assistantBlocks = (if text.nonEmpty then List(ContentBlock.Text(text)) else Nil) ++
                toolCalls.map(c => ContentBlock.ToolUse(c.id, c.name, c.input))
              val assistantMsg = Message(MessageRole.Assistant, Right(assistantBlocks))
              val resultBlocks = toolResults.map { (call, r) =>
                ContentBlock.ToolResult(call.id, r.content, Some(r.isError))
              }
              val resultMsg = Message(MessageRole.User, Right(resultBlocks))
              val newMsgs = initialMsgs ++ List(assistantMsg, resultMsg)
              askLoop(newMsgs, toolContext, resources, sessionId, wsSend, startTime, round + 1)
            }
      yield result
      end for
  end askLoop

  // ------------------------------------------------------------------
  // Internal: tool execution
  // ------------------------------------------------------------------

  private def executeTools(
    calls: List[ToolCall],
    toolContext: ToolContext,
    sessionId: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[List[(ToolCall, ToolExecResult)]] =
    calls.traverse { call =>
      val summary = ToolRegistry.TOOL_MAP.get(call.name).map(_.summarize(call.input)).getOrElse(call.name)
      val execResult: IO[ToolExecResult] = ToolRegistry.TOOL_MAP.get(call.name) match
        case Some(tool) =>
          tool
            .call(call.input, toolContext)
            .map {
              case Left(err) => ToolExecResult(err.message, isError = true)
              case Right(result) => ToolExecResult(result)
            }
            .handleErrorWith(e => IO.pure(ToolExecResult(s"Tool error: ${e.getMessage}", isError = true)))
        case None =>
          IO.pure(ToolExecResult(s"Unknown tool: ${call.name}", isError = true))
      wsSend(
        io.circe.Json.obj(
          "type" -> "toolStart".asJson,
          "sessionId" -> sessionId.asJson,
          "label" -> summary.asJson
        )
      ) *> execResult.flatMap { result =>
        wsSend(
          io.circe.Json.obj(
            "type" -> "toolEnd".asJson,
            "sessionId" -> sessionId.asJson,
            "label" -> summary.asJson,
            "summary" -> nebflow.core.summarizeToolResult(call, result.content).asJson,
            "content" -> result.content.asJson,
            "isError" -> result.isError.asJson,
            "input" -> call.input.asJson.spaces2.asJson
          )
        ).as(call -> result)
      }
    }

end AskService
