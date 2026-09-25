/* 从 AgentCore.scala 迁出(行为保持重构,2026-09-25)。 */
package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.AgentCommand.*
import nebflow.agent.PromptSections.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.hooks.*
import nebflow.core.project.{NodeRoles, ProjectRuntimeRegistry}
import nebflow.core.tools.*
import nebflow.llm.{Fallback, TurnBudgetExceeded}
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * WS 流事件合批管道（原 streamEmitter 内联实现，2026-09-07 抽出并修复 park 饿死）。
 *
 * 缺陷（B-after-A 冒烟悬案，2026-09-07 插桩实证）：旧实现 flush 只发生在
 * ①满 MaxBatch 条 ②距上次 flush ≥ FlushWindowMs ③ToolCall 星号族/Done 事件——
 * 没有常驻 ticker。流 parking（楔死/半开连接——hard-recovery 的目标场景）
 * 且首 delta 距管道构建 < FlushWindowMs（热连接 HTTP 往返仅 ~5ms）时，
 * 缓冲内容永不出网：UI 对恢复前的部分输出完全失明。A 会话冷启动往返
 * ~82ms 恰好越过窗口所以从未暴露。
 *
 * 修复：状态收进 Ref（ticker fiber 与主流 fiber 并发访问，必须原子）+
 * awakeEvery(FlushWindowMs) ticker 挂 `.concurrently`——parking 期间缓冲照常
 * flush；主流终止/出错时 ticker 随之取消。flush 时机语义不变：满 50 条 /
 * 窗口到期（现在真有定时器兜底）/ ToolCall* / Done（先 flush 后发，保序）。
 */
object StreamBatching:

  final case class BatchState(
    text: String = "",
    textCount: Int = 0,
    thinking: String = "",
    thinkingCount: Int = 0,
    lastFlushMs: Long = 0L
  )

  /** One flush's WS frames — thinking first（流序：thinking 段先于 text 段），then text. */
  final case class FlushPayload(thinking: Option[Json], text: Option[Json])

  val MaxBatch = 50
  val FlushWindowMs = 50L

  def textFrame(
    delta: String,
    isAskMode: Boolean,
    isSubagent: Boolean,
    sessionId: Option[String],
    agentPath: String
  ): Json =
    if isAskMode then
      Json.obj("type" -> "askTextDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
    else if isSubagent then AgentStreamEvent.TextDelta(delta).toJson(agentPath, true, None)
    else Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)

  def thinkingFrame(delta: String, isSubagent: Boolean, sessionId: Option[String], agentPath: String): Json =
    if isSubagent then
      // Sub-agents emit agentThinking with a delta field. routeWsSend
      // stamps nodeSessionId; ws.js convertAgentEvent maps agentThinking
      // → thinkingDelta with sessionId = nodeSessionId so the popup view
      // renders the reasoning bubble.
      Json.obj(
        "type" -> "agentThinking".asJson,
        "agentId" -> agentPath.asJson,
        "delta" -> delta.asJson,
        "nodeSessionId" -> sessionId.asJson
      )
    else Json.obj("type" -> "thinkingDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)

  def pipe(
    wsSend: Json => IO[Unit],
    isSubagent: Boolean,
    sessionId: Option[String],
    isAskMode: Boolean,
    isCompactTurn: Boolean,
    agentPath: String
  ): fs2.Pipe[IO, StreamChunk, StreamChunk] =
    stream =>
      fs2.Stream.eval(IO.ref(BatchState(lastFlushMs = System.currentTimeMillis()))).flatMap { st =>
        val send: FlushPayload => IO[Unit] = p => p.thinking.traverse_(wsSend) *> p.text.traverse_(wsSend)

        /** Atomic take: flush now (force) or when the window has elapsed with content buffered. */
        def take(force: Boolean): IO[Option[FlushPayload]] =
          IO(System.currentTimeMillis()).flatMap { now =>
            st.modify { s =>
              val buffered = s.text.nonEmpty || s.thinking.nonEmpty
              val due = buffered && (force || now - s.lastFlushMs >= FlushWindowMs)
              if !due then (s, None)
              else
                val p = FlushPayload(
                  Option.when(s.thinking.nonEmpty)(thinkingFrame(s.thinking, isSubagent, sessionId, agentPath)),
                  Option.when(s.text.nonEmpty)(textFrame(s.text, isAskMode, isSubagent, sessionId, agentPath))
                )
                (BatchState(lastFlushMs = now), Some(p))
            }
          }

        def onDelta(isText: Boolean, delta: String): IO[Unit] =
          IO(System.currentTimeMillis()).flatMap { now =>
            st.modify { s =>
              val count = (if isText then s.textCount else s.thinkingCount) + 1
              if count >= MaxBatch || now - s.lastFlushMs >= FlushWindowMs then
                val merged = (if isText then s.text else s.thinking) + delta
                val p =
                  if isText then
                    FlushPayload(None, Some(textFrame(merged, isAskMode, isSubagent, sessionId, agentPath)))
                  else FlushPayload(Some(thinkingFrame(merged, isSubagent, sessionId, agentPath)), None)
                (BatchState(lastFlushMs = now), Some(p))
              else
                val next =
                  if isText then s.copy(text = s.text + delta, textCount = count)
                  else s.copy(thinking = s.thinking + delta, thinkingCount = count)
                (next, None)
            }.flatMap {
              case Some(p) => send(p)
              case None => IO.unit
            }
          }

        // THE FIX（2026-09-07）：常驻 ticker——主流 parking 时缓冲照常出网。
        val ticker = fs2.Stream
          .awakeEvery[IO](FlushWindowMs.millis)
          .evalMap(_ => take(force = false).flatMap(_.traverse_(send)))

        stream
          .evalTap {
            case StreamChunk.TextDelta(delta) if delta.nonEmpty && !isCompactTurn =>
              onDelta(isText = true, delta)

            case StreamChunk.ThinkingDelta(delta) if delta.nonEmpty && !isCompactTurn =>
              onDelta(isText = false, delta)

            case StreamChunk.ToolCallStart(name) if name != "AskUserQuestion" && !isCompactTurn =>
              val json =
                if isSubagent then AgentStreamEvent.ToolCallDetected(name).toJson(agentPath, true, None)
                else
                  Json.obj("type" -> "toolCallDetected".asJson, "sessionId" -> sessionId.asJson, "name" -> name.asJson)
              take(force = true).flatMap(_.traverse_(send)) *> wsSend(json)

            case StreamChunk.ToolCallChunk(tc) if tc.name != "AskUserQuestion" && !isCompactTurn =>
              val json =
                if isSubagent then
                  AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(tc)).toJson(agentPath, true, None)
                else
                  Json.obj(
                    "type" -> "toolStart".asJson,
                    "sessionId" -> sessionId.asJson,
                    "label" -> nebflow.core.summarizeToolCall(tc).asJson
                  )
              take(force = true).flatMap(_.traverse_(send)) *> wsSend(json)

            case StreamChunk.ToolArgDelta(toolName, delta) if delta.nonEmpty && !isCompactTurn && !isSubagent =>
              val json = Json.obj(
                "type" -> "toolArgDelta".asJson,
                "sessionId" -> sessionId.asJson,
                "toolName" -> toolName.asJson,
                "delta" -> delta.asJson
              )
              take(force = true).flatMap(_.traverse_(send)) *> wsSend(json)

            // Stream end: flush any remaining buffered text/thinking so no deltas are lost.
            case StreamChunk.Done(_, _, _, _) => take(force = true).flatMap(_.traverse_(send))

            case _ => IO.unit
          }
          .concurrently(ticker)
      }

end StreamBatching

private[agent] trait AgentStreamPipelines:

  protected def streamEmitter(
    wsSend: io.circe.Json => IO[Unit],
    isSubagent: Boolean = true,
    sessionId: Option[String] = None,
    isAskMode: Boolean = false,
    isCompactTurn: Boolean = false
  )(using ctx: ActorContext[AgentCommand]): fs2.Pipe[IO, StreamChunk, StreamChunk] =
    stream =>
      // ── WS 事件合批（2026-09-07 抽出为 StreamBatching 并修复 park 饿死）──
      // 旧内联实现的 flush 只发生在「满 50 条 / 距上次 flush ≥50ms / ToolCall /
      // Done」——无常驻 ticker。流一旦 parking（楔死/半开连接，正是
      // hard-recovery 的目标场景）且首 delta 距管道构建 <50ms（热连接往返仅
      // ~5ms），缓冲文本永远不出网：UI 对恢复前的部分输出完全失明
      // （B-after-A 冒烟悬案实证：[sse-chunk] 到达而 WS 帧缺席，2026-09-07）。
      StreamBatching.pipe(
        wsSend,
        isSubagent = isSubagent,
        sessionId = sessionId,
        isAskMode = isAskMode,
        isCompactTurn = isCompactTurn,
        agentPath = ctx.self.path.name
      )(stream)

  protected def aggregateChunks(chunks: List[StreamChunk]): ConsumeResult =
    val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
    val thinking = chunks.collect { case StreamChunk.ThinkingDelta(d) => d }.mkString
    val thinkingSignature = chunks.collectFirst { case StreamChunk.ThinkingSignature(s) => s }
    val toolCalls = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
    val usage = chunks
      .collectFirst { case StreamChunk.Done(_, Some(u), _, _) if u.inputTokens > 0 => u }
      .orElse(chunks.collectFirst { case StreamChunk.Done(_, u, _, _) => u }.flatten)
    val stopReason = chunks.collectFirst { case StreamChunk.Done(sr, _, _, _) => sr }.flatten
    val model = chunks.collectFirst { case StreamChunk.Done(_, _, Some(meta), _) =>
      s"${meta.providerId}/${meta.model}"
    }
    val contextWindow = chunks.collectFirst { case StreamChunk.Done(_, _, _, cw) => cw }.flatten
    ConsumeResult(
      text,
      toolCalls,
      Nil,
      stopReason,
      usage,
      Option.when(thinking.nonEmpty)(thinking),
      thinkingSignature,
      model,
      contextWindow
    )

  end aggregateChunks

end AgentStreamPipelines
