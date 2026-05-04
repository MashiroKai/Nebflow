package nebflow.core.compact

import nebflow.shared.{ContentBlock, Message}

/** Rough token estimation. NOT for billing — only for compaction triggering heuristics. */
object TokenEstimator:

  private val ImageTokenEstimate = 1500

  def estimate(messages: List[Message]): Int =
    val (textChars, imageCount) = messages.foldLeft((0, 0)) { case ((tc, ic), msg) =>
      msg.content match
        case Left(text) => (tc + text.length, ic)
        case Right(blocks) =>
          blocks.foldLeft((tc, ic)) {
            case ((t, i), ContentBlock.Text(text))              => (t + text.length, i)
            case ((t, i), ContentBlock.ToolUse(_, _, input))    => (t + input.toString.length, i)
            case ((t, i), ContentBlock.ToolResult(_, content, _))=> (t + content.length, i)
            case ((t, i), ContentBlock.Image(_, _))             => (t, i + 1)               // 每张图按固定 token 计,不走 chars/3
            case ((t, i), ContentBlock.Thinking(t2, _))         => (t + t2.length, i)
          }
    }
    (textChars / 3) + (imageCount * ImageTokenEstimate)
  end estimate
