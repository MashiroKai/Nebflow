package nebflow.core.compact

import nebflow.shared.{ContentBlock, Message}

/**
 * Token estimation for compaction triggering heuristics.
 *
 * Uses `chars/4` base ratio with CJK weighting — inspired by OpenClaw's approach.
 * Latin text averages ~4 chars/token, CJK text averages ~1 char/token.
 * By weighting CJK chars 4×, the single `weightedChars / 4` formula works
 * accurately for any script mix.
 *
 * NOT for billing — only for compaction triggering estimates.
 */
object TokenEstimator:

  private val ImageTokenEstimate = 1500
  private val CharsPerToken = 4

  /** Code-point ranges of non-Latin scripts that take ~1 token/char. */
  private def isDenseChar(cp: Int): Boolean =
    (cp >= 0x2e80 && cp <= 0x9fff) || // CJK Unified, Hangul, etc.
      (cp >= 0xac00 && cp <= 0xd7af) || // Hangul Syllables
      (cp >= 0xf900 && cp <= 0xfaff) || // CJK Compatibility Ideographs
      (cp >= 0xfe30 && cp <= 0xfe6f) || // CJK Compatibility Forms
      (cp >= 0xff01 && cp <= 0xff60) || // Fullwidth Forms
      (cp >= 0x20000 && cp <= 0x2fa1f) // CJK Extension B+ (supplementary)

  /**
   * Compute an adjusted character length: each non-Latin dense character
   * counts as `CharsPerToken` chars so that `result / CharsPerToken` yields
   * an accurate token estimate for mixed-script text.
   */
  def estimateWeightedChars(text: String): Int =
    val n = text.length
    if n == 0 then 0
    else
      var weighted = 0
      var i = 0
      while i < n do
        val cp = text.codePointAt(i)
        if isDenseChar(cp) then weighted += CharsPerToken // CJK → 1 token each
        else weighted += 1 // Latin → ~1/4 token each
        i += (if Character.charCount(cp) == 2 then 2 else 1)
      weighted
  end estimateWeightedChars

  def estimate(messages: List[Message]): Int =
    val (textChars, imageCount) = messages.foldLeft((0, 0)) { case ((tc, ic), msg) =>
      msg.content match
        case Left(text) => (tc + estimateWeightedChars(text), ic)
        case Right(blocks) =>
          blocks.foldLeft((tc, ic)) {
            case ((t, i), ContentBlock.Text(text)) => (t + estimateWeightedChars(text), i)
            case ((t, i), ContentBlock.ToolUse(_, _, input)) => (t + estimateWeightedChars(input.toString), i)
            case ((t, i), ContentBlock.ToolResult(_, content, _)) => (t + estimateWeightedChars(content), i)
            case ((t, i), ContentBlock.Image(_, _)) => (t, i + 1) // 每张图按固定 token 计
            case ((t, i), ContentBlock.Thinking(t2, _)) => (t + estimateWeightedChars(t2), i)
          }
    }
    (textChars / CharsPerToken) + (imageCount * ImageTokenEstimate)
  end estimate

end TokenEstimator
