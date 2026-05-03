package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.shared.*
import nebflow.shared.given

object MicroCompact:

  // ===== Instruction model =====

  sealed trait Instruction
  case class Keep(indices: List[Int]) extends Instruction
  case class Compact(start: Int, end: Int, summary: String) extends Instruction

  private[compact] def compact(
    messages: List[Message],
    llm: LlmHandle[IO],
    config: CompactConfig,
    projectRoot: String = ""
  ): IO[Either[String, List[Message]]] =
    if messages.size < 6 then IO.pure(Right(messages))
    else
      val cleaned = CompactUtils.stripImages(messages)
      val messagesJson = cleaned.asJson.noSpaces

      val request = LlmRequest(
        messages = List(
          Message(MessageRole.System, Left(CompactPrompts.micro)),
          Message(MessageRole.User, Left(messagesJson))
        ),
        sessionId = "compact",
        agentId = "compact",
        tools = None,
        maxTokens = Some(Defaults.MaxTokensCompact)
      )

      for
        chunks <- llm.sendStream(request).compile.toList
        text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
      yield parseResponse(text, messages)
  end compact

  /** Parse LLM response text into a compacted message list. */
  def parseResponse(
    text: String,
    originalMessages: List[Message]
  ): Either[String, List[Message]] =
    if text.isEmpty then Left("SubAgent returned empty response")
    else parseAndApply(text, originalMessages)
  end parseResponse

  // ===== Parse instructions and apply =====

  private def parseAndApply(
    text: String,
    messages: List[Message]
  ): Either[String, List[Message]] =
    val instructions = parseInstructions(text)

    if instructions.isEmpty then Left("SubAgent output contains no valid instructions")
    else
      val coveredIndices = instructions.flatMap {
        case Keep(indices) => indices
        case Compact(s, e, _) => (s to e).toList
      }.toSet

      val expectedIndices = (0 until messages.length).toSet

      // Fail only on extra (out-of-range) indices; default missing to keep
      val extra = coveredIndices.filter(_ >= messages.length).toList.sorted
      if extra.nonEmpty then
        Left(s"Instruction coverage has extra indices out of range: ${extra.mkString("[", ",", "]")}")
      else
        val missing = (expectedIndices -- coveredIndices).toList.sorted
        val augmentedInstructions =
          if missing.nonEmpty then instructions :+ Keep(missing) else instructions

        val newMessages = augmentedInstructions.flatMap {
          case Keep(indices) => indices.map(messages(_))
          case Compact(s, e, summary) =>
            List(
              Message(
                MessageRole.User,
                Left(
                  s"<context-compact mode=\"micro\">Compressed messages $s-$e.\n$summary</context-compact>"
                )
              )
            )
        }
        Right(newMessages)

    end if

  end parseAndApply

  /** Parse <keep> and <compact> tags from SubAgent output */
  private def parseInstructions(text: String): List[Instruction] =
    val keepPattern = """<keep>\s*([\d,\s]+)\s*</keep>""".r
    val compactPattern = """<compact\s+start=\"(\d+)\"\s+end=\"(\d+)\">\s*(.*?)\s*</compact>""".r

    val tags = List.newBuilder[(Int, Instruction)]

    keepPattern.findAllMatchIn(text).foreach { m =>
      val indices = m.group(1).split(",").map(_.trim.toInt).toList
      tags += (m.start -> Keep(indices))
    }

    compactPattern.findAllMatchIn(text).foreach { m =>
      val start = m.group(1).toInt
      val end = m.group(2).toInt
      val summary = m.group(3).trim
      tags += (m.start -> Compact(start, end, summary))
    }

    tags.result().sortBy(_._1).map(_._2)
  end parseInstructions

end MicroCompact
