package nebflow.llm.providers

import cats.effect.IO
import io.circe.Json
import munit.FunSuite
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * Thinking-block history replay, per provider:
 *  - Real Anthropic rejects unsigned thinking blocks → drop them (default).
 *  - DeepSeek's Anthropic-compatible thinking mode REQUIRES thinking blocks
 *    to be passed back, signature or not → keep them (requireThinkingPassback).
 * Dropping them for DeepSeek caused the recurring `content[].thinking must be
 * passed back` 400s that marked deepseek DOWN on 2026-08-15.
 */
class AnthropicThinkingPassbackSpec extends FunSuite:

  private def adapter(passback: Boolean): AnthropicAdapter =
    AnthropicAdapter(
      "http://localhost:0",
      "k",
      null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]],
      passback
    )

  private def historyWithUnsignedThinking: List[Message] =
    List(
      Message(MessageRole.User, Left("hi")),
      Message(
        MessageRole.Assistant,
        Right(
          List(
            ContentBlock.Thinking("let me think about this", None),
            ContentBlock.Text("the answer")
          )
        )
      ),
      Message(MessageRole.User, Left("continue"))
    )

  private def historyWithSignedThinking: List[Message] =
    List(
      Message(MessageRole.User, Left("hi")),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.Thinking("signed thought", Some("sig-abc"))))
      ),
      Message(MessageRole.User, Left("continue"))
    )

  private def assistantBlocks(jsons: List[Json]): List[Json] =
    jsons.filter(j => j.hcursor.get[String]("role").contains("assistant"))

  test("default (passback=false) drops unsigned thinking blocks"):
    val out = assistantBlocks(adapter(passback = false).toAnthropicMessages(historyWithUnsignedThinking))
    val flat = out.mkString
    assert(!flat.contains("thinking"), s"unsigned thinking must be dropped: $flat")

  test("passback=true keeps unsigned thinking blocks without a signature field"):
    val out = assistantBlocks(adapter(passback = true).toAnthropicMessages(historyWithUnsignedThinking))
    val flat = out.mkString
    assert(flat.contains("thinking"), s"unsigned thinking must be replayed: $flat")
    assert(!flat.contains("signature"), s"no signature field for unsigned blocks: $flat")

  test("signed thinking blocks replay with signature in both modes"):
    for passback <- List(false, true) do
      val out = assistantBlocks(adapter(passback).toAnthropicMessages(historyWithSignedThinking))
      val flat = out.mkString
      assert(flat.contains("thinking"), s"signed thinking must replay (passback=$passback): $flat")
      assert(flat.contains("sig-abc"), s"signature must be preserved (passback=$passback): $flat")
end AnthropicThinkingPassbackSpec
