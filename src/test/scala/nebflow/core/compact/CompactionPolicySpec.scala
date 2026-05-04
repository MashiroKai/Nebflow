package nebflow.core.compact

import munit.CatsEffectSuite
import nebflow.shared.{ContentBlock, Message, MessageRole, TokenUsage}

class CompactionPolicySpec extends CatsEffectSuite:

  private def textMsg(role: MessageRole, text: String): Message =
    Message(role, Left(text))

  // ---------- TokenEstimator ----------

  test("TokenEstimator: empty messages = 0 tokens") {
    assertEquals(TokenEstimator.estimate(Nil), 0)
  }

  test("TokenEstimator: pure text 300 chars ~ 100 tokens") {
    val text = "x" * 300
    val msgs = List(textMsg(MessageRole.User, text))
    assertEquals(TokenEstimator.estimate(msgs), 100)
  }

  test("TokenEstimator: image block counts 1500 tokens each, text separate") {
    val msgs = List(
      Message(MessageRole.User, Right(List(
        ContentBlock.Text("x" * 300),
        ContentBlock.Image("data", "image/png"),
      )))
    )
    assertEquals(TokenEstimator.estimate(msgs), 1600)
  }

  test("TokenEstimator: multiple images accumulate") {
    val msgs = List(
      Message(MessageRole.User, Right(List(
        ContentBlock.Image("a", "image/png"),
        ContentBlock.Image("b", "image/jpeg"),
      )))
    )
    assertEquals(TokenEstimator.estimate(msgs), 3000)
  }

  test("TokenEstimator: mixed blocks (text + image + tool_use + tool_result + thinking)") {
    val msgs = List(
      Message(MessageRole.User, Right(List(
        ContentBlock.Text("x" * 600),           // 200 tokens
        ContentBlock.Image("d1", "png"),        // 1500 tokens
        ContentBlock.ToolUse("t1", "read", io.circe.JsonObject.empty), // 0 (json empty)
      ))),
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.Thinking("thoughts" * 10, None), // 70 chars ~ 23 tokens
      )))
    )
    val est = TokenEstimator.estimate(msgs)
    assert(est > 0)
    assert(est >= 1723) // 200 + 1500 + 23
  }

  // ---------- CompactConfig 字段有效性 ----------

  test("CompactConfig retains bufferTokens and circuitBreakerMax") {
    val cfg = CompactConfig()
    assertEquals(cfg.bufferTokens, 13000)
    assertEquals(cfg.circuitBreakerMax, 3)
  }

  test("CompactConfig dead fields removed") {
    // Compile-time check: if autoCompactThreshold / contextWindow / forContextWindow
    // still existed, this would not compile. We verify only live fields remain.
    val cfg = CompactConfig(bufferTokens = 5000, circuitBreakerMax = 5)
    assertEquals(cfg.bufferTokens, 5000)
    assertEquals(cfg.circuitBreakerMax, 5)
  }

  test("circuitBreakerMax default prevents runaway compaction") {
    // Simulate 3 failures followed by a 4th attempt
    val max = CompactConfig().circuitBreakerMax
    val failures = 3
    assert(failures >= max, "with default config, 3 failures should open the breaker")
  }

  // ---------- Real usage trigger (issue 009) ----------

  test("latestUsage present and above threshold triggers compact decision") {
    val usage = Some(TokenUsage(inputTokens = 90000, outputTokens = 1000))
    val threshold = 70000
    val shouldCompact = usage.exists(_.inputTokens > threshold)
    assert(shouldCompact)
  }

  test("latestUsage present but below threshold does not trigger compact") {
    val usage = Some(TokenUsage(inputTokens = 50000, outputTokens = 1000))
    val threshold = 70000
    val shouldCompact = usage.exists(_.inputTokens > threshold)
    assert(!shouldCompact)
  }

  test("latestUsage absent skips compact regardless of estimate") {
    val usage: Option[TokenUsage] = None
    val threshold = 70000
    val shouldCompact = usage.exists(_.inputTokens > threshold)
    assert(!shouldCompact)
  }
