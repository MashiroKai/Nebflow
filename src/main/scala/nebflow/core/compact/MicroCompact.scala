package nebflow.core.compact

import nebflow.shared.*

object MicroCompact:

  // ===== Instruction model =====

  sealed trait Instruction
  case class Keep(indices: List[Int]) extends Instruction
  case class Compact(start: Int, end: Int, summary: String) extends Instruction

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
        val missing = messages.indices.filterNot(coveredIndices).toList

        // Guard: tool_use and its matching tool_result must be handled by the same instruction.
        // If they are split (e.g. tool_use compacted but tool_result kept), reject.
        checkPairingIntegrity(instructions, missing, messages) match
          case Left(err) => Left(err)
          case Right(_) =>
            // Expand every instruction into (sortKey, List[Message]) pairs.
            // Keep: each index becomes its own pair so ordering is preserved.
            // Compact: sortKey = start.
            val expanded: List[(Int, List[Message])] =
              (instructions ++ missing.map(i => Keep(List(i)))).flatMap {
                case Keep(idx) =>
                  idx.distinct.sorted.map(i => (i, List(messages(i))))
                case Compact(s, e, summary) =>
                  val placeholder = Message(
                    MessageRole.User,
                    Left(
                      s"<context-compact mode=\"micro\">Compressed messages $s-$e.\n$summary</context-compact>"
                    )
                  )
                  List((s, List(placeholder)))
              }

            val ordered = expanded.sortBy(_._1).flatMap(_._2)
            validatePairing(ordered)
      end if

    end if

  end parseAndApply

  /**
   * Check that no instruction splits a tool_use/tool_result pair.
   *  A pair is split when the two indices map to different instructions
   *  (or one is covered and the other defaults to keep).
   */
  private def checkPairingIntegrity(
    instructions: List[Instruction],
    missing: List[Int],
    messages: List[Message]
  ): Either[String, Unit] =
    // Build index -> instruction label map
    val indexToLabel: Map[Int, String] =
      instructions.flatMap {
        case Keep(idx) => idx.map(i => (i, "keep"))
        case Compact(s, e, _) => (s to e).map(i => (i, s"compact-$s-$e"))
      }.toMap ++ missing.map(i => (i, "keep"))

    // Collect tool_use ids and their indices
    val toolUses: List[(String, Int)] = messages.zipWithIndex.flatMap { case (m, i) =>
      m.role match
        case MessageRole.Assistant =>
          m.content match
            case Right(blocks) => blocks.collect { case ContentBlock.ToolUse(id, _, _) => (id, i) }
            case _ => Nil
        case _ => Nil
    }

    // Collect tool_result ids and their indices
    val toolResults: Map[String, Int] = messages.zipWithIndex.flatMap { case (m, i) =>
      m.role match
        case MessageRole.User =>
          m.content match
            case Right(blocks) => blocks.collect { case ContentBlock.ToolResult(toolUseId, _, _) => (toolUseId, i) }
            case _ => Nil
        case _ => Nil
    }.toMap

    val splitPairs = toolUses.filterNot { case (id, tuIdx) =>
      toolResults.get(id) match
        case Some(trIdx) => indexToLabel.get(tuIdx) == indexToLabel.get(trIdx)
        case None => true // unmatched tool_use, let validatePairing catch it
    }

    if splitPairs.isEmpty then Right(())
    else Left(s"Compaction broke tool_use/tool_result pairing for ids: ${splitPairs.map(_._1).mkString(",")}")
  end checkPairingIntegrity

  /** Validate that every assistant tool_use has a matching user tool_result. */
  private def validatePairing(ms: List[Message]): Either[String, List[Message]] =
    val toolUseIds = ms.zipWithIndex.flatMap { case (m, i) =>
      m.role match
        case MessageRole.Assistant => extractToolUseIds(m)
        case _ => Nil
    }
    val toolResultIds: Set[String] = ms.flatMap(extractToolResultIds).toSet
    val orphans = toolUseIds.filterNot(id => toolResultIds.contains(id))
    if orphans.isEmpty then Right(ms)
    else Left(s"Compaction broke tool_use/tool_result pairing for ids: ${orphans.mkString(",")}")
  end validatePairing

  private def extractToolUseIds(m: Message): List[String] =
    m.content match
      case Left(_) => Nil
      case Right(blocks) =>
        blocks.collect { case ContentBlock.ToolUse(id, _, _) => id }

  private def extractToolResultIds(m: Message): List[String] =
    m.content match
      case Left(_) => Nil
      case Right(blocks) =>
        blocks.collect { case ContentBlock.ToolResult(toolUseId, _, _) => toolUseId }

  /** Strip markdown ``` fences before parsing XML tags. */
  private def stripCodeFence(text: String): String =
    val trimmed = text.trim
    if trimmed.startsWith("```") then
      val lines = trimmed.linesIterator.toList
      if lines.size >= 3 then
        // Drop first line (```lang) and last line (```), keep everything in between
        lines.drop(1).dropRight(1).mkString("\n").trim
      else trimmed
    else trimmed

  /**
   * Parse <keep> and <compact> tags from SubAgent output.
   *  Tolerates single/double quotes, attribute order, and markdown fences.
   */
  private def parseInstructions(text: String): List[Instruction] =
    val cleaned = stripCodeFence(text)
    val keepRegex = raw"<keep>\s*([\d,\s]+)\s*</keep>".r
    // Two variants: start before end, and end before start
    val compactRegex1 = raw"""<compact\s+start=["']?(\d+)["']?\s+end=["']?(\d+)["']?>(.*?)</compact>""".r
    val compactRegex2 = raw"""<compact\s+end=["']?(\d+)["']?\s+start=["']?(\d+)["']?>(.*?)</compact>""".r

    val tags = List.newBuilder[(Int, Instruction)]

    keepRegex.findAllMatchIn(cleaned).foreach { m =>
      val indices = m.group(1).split(",").map(_.trim.toInt).toList
      tags += (m.start -> Keep(indices))
    }

    compactRegex1.findAllMatchIn(cleaned).foreach { m =>
      val start = m.group(1).toInt
      val end = m.group(2).toInt
      val summary = m.group(3).trim
      tags += (m.start -> Compact(start, end, summary))
    }

    compactRegex2.findAllMatchIn(cleaned).foreach { m =>
      val start = m.group(2).toInt
      val end = m.group(1).toInt
      val summary = m.group(3).trim
      tags += (m.start -> Compact(start, end, summary))
    }

    tags.result().sortBy(_._1).map(_._2)
  end parseInstructions

end MicroCompact
