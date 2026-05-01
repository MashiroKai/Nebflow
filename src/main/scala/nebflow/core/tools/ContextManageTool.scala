package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.shared.*

object ContextManageTool extends Tool:
  private val logger = NebflowLogger.forName("nebflow.context")

  // Track last inspect mapping: display index (0-based) → global unit index
  private val lastInspectMapping = new java.util.concurrent.atomic.AtomicReference[Option[List[Int]]](None)
  val name = "ContextManage"

  val description = """Manage your context window. Two operations:

inspect — Show the most recent ~20 units with expanded previews. Each tool call + its result is one unit; plain messages are standalone units.

replace — Replace units with a concise summary. Supports single index, range, or multiple non-contiguous indices via `indices` array. Tool call + result pairs are always handled atomically.

Rules:
- You MUST call inspect before any replace. Attempting replace without a prior inspect will fail.
- You can only replace units whose indexes were shown in the last inspect result. Operating on unseen indexes will fail.
- After a successful replace, the inspect window is invalidated — you must inspect again before the next operation.
- You cannot replace the last user message (safety check).

How to write good summaries:
- Preserve enough info for you to continue working: key findings, conclusions, file locations, decisions, next steps.
- Bad: "Read some files". Good: "Read auth.ts (JWT validation at line 42-89 using jsonwebtoken lib), routes.ts (3 endpoints). Next: add rate-limiting before auth middleware."

Strategy:
- Inspect to see what's consuming context
- Use `indices` to replace multiple non-contiguous units in one call
- Replace completed tool results (file reads, grep output) with summaries
- Replacing old content improves prompt cache hit rate — faster responses, fewer tokens"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "operation" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("inspect".asJson, "replace".asJson),
          "description" -> "Operation to perform".asJson
        ),
        "index" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Single target unit index. For replace.".asJson
        ),
        "start_index" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Range start (inclusive). For replace.".asJson
        ),
        "end_index" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Range end (inclusive). For replace.".asJson
        ),
        "indices" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "items" -> io.circe.Json.obj("type" -> "integer".asJson),
          "description" -> "Multiple target unit indexes (non-contiguous). For replace. Takes priority over index/start_index/end_index.".asJson
        ),
        "content" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Replacement text (for replace)".asJson
        )
      ),
      "required" -> io.circe.Json.arr("operation".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val op = input("operation").flatMap(_.asString).getOrElse("")
    val indicesStr =
      input("indices").flatMap(_.asArray).map(_.flatMap(_.asNumber.flatMap(_.toInt))).map(_.mkString(",")).getOrElse("")
    val idx = input("index").flatMap(_.asNumber).flatMap(_.toInt)
    val startIdx = input("start_index").flatMap(_.asNumber).flatMap(_.toInt)
    val endIdx = input("end_index").flatMap(_.asNumber).flatMap(_.toInt)
    val label = op match
      case "inspect" => "CtxInspect()"
      case "replace" =>
        if indicesStr.nonEmpty then s"CtxReplace([$indicesStr])"
        else
          (startIdx, endIdx) match
            case (Some(s), Some(e)) => s"CtxReplace([$s-$e])"
            case _ => idx.map(i => s"CtxReplace([$i])").getOrElse("CtxReplace(?)")
      case _ => s"CtxManage($op)"
    label

  def summarizeResult(input: JsonObject, result: String): String =
    val op = input("operation").flatMap(_.asString).getOrElse("")
    if op == "inspect" then "CtxInspect: returned"
    else if result.length > 120 then result.take(117) + "..."
    else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.messagesRef match
      case None => IO.pure(Left(ToolError("ContextManage: no message ref available")))
      case Some(ref) =>
        val op = input("operation").flatMap(_.asString).getOrElse("")
        op match
          case "inspect" => doInspect(input, ref, ctx.usageRef)
          case "replace" => doReplace(input, ref)
          case _ => IO.pure(Left(ToolError(s"Unknown operation: $op")))

  // ===== Unit Model =====
  // blockIdx = -1 means the whole message (Left content)

  private case class BlockLoc(msgIdx: Int, blockIdx: Int)

  private case class CtxUnit(
    locs: List[BlockLoc],
    role: String, // "user", "assistant", "tool", "tool-error"
    toolName: Option[String],
    callInput: Option[String],
    resultContent: Option[String],
    textContent: Option[String]
  )

  private def buildUnits(messages: List[Message]): List[CtxUnit] =
    import scala.collection.mutable.{Map as MMap, Set as MSet, ListBuffer}

    val callMap = MMap.empty[String, (Int, Int, String)] // toolUseId → (msgIdx, blockIdx, toolName)
    val assigned = MSet.empty[(Int, Int)]
    val units = ListBuffer.empty[CtxUnit]

    // Pass 1: collect tool call locations
    for (msg, mi) <- messages.zipWithIndex do
      msg.content match
        case Right(blocks) =>
          blocks.zipWithIndex.foreach {
            case (ContentBlock.ToolUse(id, name, _), bi) => callMap(id) = (mi, bi, name)
            case _ =>
          }
        case _ =>

    // Pass 2: build units
    for (msg, mi) <- messages.zipWithIndex do
      msg.content match
        case Left(text) =>
          val role = if msg.role == MessageRole.User then "user" else "assistant"
          units += CtxUnit(
            List(BlockLoc(mi, -1)),
            role,
            None,
            None,
            None,
            Some(previewText(text))
          )

        case Right(blocks) =>
          blocks.zipWithIndex.foreach { (block, bi) =>
            if !assigned.contains((mi, bi)) then
              block match
                case ContentBlock.ToolUse(id, name, input) =>
                  findToolResult(messages, id) match
                    case Some((rmi, rbi)) =>
                      assigned += ((mi, bi))
                      assigned += ((rmi, rbi))
                      val isError = messages(rmi).content match
                        case Right(bs) if rbi < bs.length =>
                          bs(rbi) match
                            case ContentBlock.ToolResult(_, _, err) => err.contains(true)
                            case _ => false
                        case _ => false
                      val role = if isError then "tool-error" else "tool"
                      val resultContent = blockText(messages, rmi, rbi)
                      val inputStr = input.toMap
                        .map { (k, v) =>
                          val vs = v.asString.getOrElse(v.noSpaces.take(60))
                          s"$k: $vs"
                        }
                        .take(3)
                        .mkString(", ")
                      units += CtxUnit(
                        List(BlockLoc(mi, bi), BlockLoc(rmi, rbi)),
                        role,
                        Some(name),
                        Some(inputStr),
                        Some(samplePreview(resultContent, 300)),
                        None
                      )
                    case None =>
                      units += CtxUnit(
                        List(BlockLoc(mi, bi)),
                        "tool",
                        Some(name),
                        None,
                        Some("(no result)"),
                        None
                      )

                case ContentBlock.ToolResult(_, content, isError) =>
                  val role = if isError.contains(true) then "tool-error" else "tool-result"
                  units += CtxUnit(
                    List(BlockLoc(mi, bi)),
                    role,
                    None,
                    None,
                    Some(samplePreview(content, 300)),
                    None
                  )

                case ContentBlock.Text(t) =>
                  val role = if msg.role == MessageRole.User then "user" else "assistant"
                  units += CtxUnit(
                    List(BlockLoc(mi, bi)),
                    role,
                    None,
                    None,
                    None,
                    Some(previewText(t))
                  )

                case ContentBlock.Image(_, _) =>
                  units += CtxUnit(
                    List(BlockLoc(mi, bi)),
                    "user",
                    None,
                    None,
                    None,
                    Some("[image]")
                  )
          }
    end for
    units.toList
  end buildUnits

  private def findToolResult(messages: List[Message], toolUseId: String): Option[(Int, Int)] =
    messages.zipWithIndex.flatMap { (msg, mi) =>
      msg.content match
        case Right(blocks) =>
          blocks.zipWithIndex.collect {
            case (ContentBlock.ToolResult(id, _, _), bi) if id == toolUseId => (mi, bi)
          }
        case _ => Nil
    }.headOption

  private def blockText(messages: List[Message], mi: Int, bi: Int): String =
    messages(mi).content match
      case Right(blocks) =>
        blocks.lift(bi) match
          case Some(ContentBlock.Text(t)) => t
          case Some(ContentBlock.ToolResult(_, content, _)) => content
          case Some(ContentBlock.ToolUse(_, name, _)) => s"[tool:$name]"
          case Some(ContentBlock.Image(_, _)) => "[image]"
          case None => ""
      case Left(text) => text

  // ===== Preview =====

  private def previewText(text: String): String =
    if text.startsWith("<system-reminder") then "<system-reminder>"
    else if text.isEmpty then "(empty)"
    else samplePreview(text, 200)

  private def samplePreview(text: String, maxLen: Int = 200): String =
    if text.length <= maxLen then text
    else
      val budget = maxLen - 3
      text.take(budget) + "..."

  // ===== Token estimation (CJK-aware) =====

  private def estimateTokens(text: String): Int =
    var ascii = 0
    var cjk = 0
    var i = 0
    while i < text.length do
      val c = text.charAt(i)
      if c >= 0x4e00 && c <= 0x9fff || c >= 0x3040 && c <= 0x30ff || c >= 0xac00 && c <= 0xd7af then cjk += 1
      else if c < 128 then ascii += 1
      else cjk += 1
      i += 1
    (ascii / 4 + (cjk * 1.5).toInt).max(1)

  /** Full text of a message including tool result content (textContent only returns Text blocks). */
  private def fullText(msg: Message): String = msg.content match
    case Left(text) => text
    case Right(blocks) =>
      blocks
        .map {
          case ContentBlock.Text(t) => t
          case ContentBlock.Image(_, _) => "[image]"
          case ContentBlock.ToolUse(_, name, _) => s"[tool:$name]"
          case ContentBlock.ToolResult(_, content, _) => content
        }
        .mkString("\n")

  private val indexShiftTip = "\nTip: indexes have shifted, run inspect again before next operation."

  // ===== Inspect =====

  private def doInspect(
    input: JsonObject,
    ref: Ref[IO, List[Message]],
    usageRef: Option[Ref[IO, Option[TokenUsage]]]
  ): IO[Either[ToolError, String]] =

    (ref.get, usageRef.traverse(_.get).map(_.flatten)).mapN { (messages, usageOpt) =>
      if messages.isEmpty then Right("Context: empty")
      else
        val allUnits = buildUnits(messages)
        val total = allUnits.length
        val estTokens = estimateTokens(messages.map(fullText).mkString)

        // Take last 20 units, renumber from 0
        val limit = 20
        val startIdx = math.max(0, total - limit)
        val slice = allUnits.slice(startIdx, total)

        // Store mapping: display index → global index
        lastInspectMapping.set(Some(slice.indices.map(i => startIdx + i).toList))

        val lines = slice.zipWithIndex.map { (u, displayIdx) =>
          u.toolName match
            case Some(name) =>
              val inputLine = u.callInput.map(i => s"\n  call: {$i}").getOrElse("")
              val resultLine = u.resultContent.map(r => s"\n  result: \"$r\"").getOrElse("")
              s"[$displayIdx] ${u.role}($name):$inputLine$resultLine"
            case None =>
              val content = u.textContent.getOrElse("")
              s"[$displayIdx] ${u.role}: \"$content\""
        }

        val tokenInfo = usageOpt match
          case Some(u) => s"${u.inputTokens} actual / ~$estTokens est"
          case None => s"~$estTokens est"

        val header =
          if startIdx == 0 then s"Context: ${messages.length} msgs ($total units) | tokens: $tokenInfo"
          else
            s"Context: ${messages.length} msgs ($total units total) | tokens: $tokenInfo | showing latest $slice.length units [0-${slice.length - 1}]"
        Right((header :: lines).mkString("\n"))
    }
  end doInspect

  // ===== Block removal helper =====

  private def removeBlocks(messages: List[Message], locs: List[BlockLoc]): (List[Message], Int) =
    val byMsg = locs.groupBy(_.msgIdx).view.mapValues(_.map(_.blockIdx).toSet).toMap
    // Use last removed message position for insertion (preserves semantic order for non-contiguous indices)
    val maxMsgIdx = locs.map(_.msgIdx).max

    // Count messages fully removed at or before maxMsgIdx to adjust insertion position
    val removedAtOrBefore = (0 to maxMsgIdx).count { mi =>
      byMsg.get(mi) match
        case None => false
        case Some(blockIdxs) =>
          messages(mi).content match
            case Left(_) => blockIdxs.contains(-1)
            case Right(blocks) => blocks.indices.forall(blockIdxs.contains)
    }

    val result = messages.zipWithIndex.flatMap { (msg, mi) =>
      byMsg.get(mi) match
        case None => Some(msg)
        case Some(blockIdxs) =>
          msg.content match
            case Left(_) if blockIdxs.contains(-1) => None
            case Right(blocks) =>
              val remaining = blocks.zipWithIndex.filterNot((_, bi) => blockIdxs.contains(bi)).map(_._1)
              if remaining.nonEmpty then Some(msg.copy(content = Right(remaining)))
              else None
            case _ => Some(msg)
    }

    // Insert after the last removed message position
    (result, maxMsgIdx - removedAtOrBefore + 1)
  end removeBlocks

  // ===== Safety check =====

  private def wouldRemoveLastUser(messages: List[Message], locs: List[BlockLoc]): Boolean =
    val lastUserIdx = messages.lastIndexWhere(_.role == MessageRole.User)
    if lastUserIdx < 0 then false
    else
      val byMsg = locs.groupBy(_.msgIdx).view.mapValues(_.map(_.blockIdx).toSet).toMap
      byMsg.get(lastUserIdx) match
        case None => false
        case Some(blockIdxs) =>
          messages(lastUserIdx).content match
            case Left(_) => blockIdxs.contains(-1)
            case Right(blocks) => blocks.indices.forall(blockIdxs.contains)

  // ===== Index resolution & range guard =====

  /** Parse target indices from input: indices array > start+end range > single index. */
  private def resolveTargetIndices(input: JsonObject): Option[List[Int]] =
    // indices array takes priority
    input("indices").flatMap(_.asArray).map(_.flatMap(_.asNumber.flatMap(_.toInt)).toList) match
      case Some(indices) if indices.nonEmpty => Some(indices)
      case _ =>
        // start_index + end_index range
        val startOpt = input("start_index").flatMap(_.asNumber).flatMap(_.toInt)
        val endOpt = input("end_index").flatMap(_.asNumber).flatMap(_.toInt)
        (startOpt, endOpt) match
          case (Some(s), Some(e)) => Some((s to e).toList)
          case _ =>
            // single index
            input("index").flatMap(_.asNumber).flatMap(_.toInt).map(i => List(i))

  /**
   * Validate display indices and map them to global unit indices.
   * Returns Right(globalIndices) if valid, Left(error) otherwise.
   */
  private def validateAndMapIndices(displayIndices: List[Int]): Either[ToolError, List[Int]] =
    lastInspectMapping.get() match
      case None =>
        Left(ToolError("Must run inspect before replace."))
      case Some(mapping) =>
        val invalid = displayIndices.filter(i => i < 0 || i >= mapping.length)
        if invalid.nonEmpty then
          Left(
            ToolError(
              s"Index(es) ${invalid.mkString("[", ",", "]")} are outside inspect range [0-${mapping.length - 1}]. Run inspect first."
            )
          )
        else Right(displayIndices.map(mapping(_)))

  private def clearInspectMapping(): Unit = lastInspectMapping.set(None)

  /** Format index list for display: compact contiguous ranges like [1-3,5,7-9]. */
  private def formatIndices(indices: List[Int]): String =
    val sorted = indices.distinct.sorted
    if sorted.isEmpty then "[]"
    else
      val parts = scala.collection.mutable.ListBuffer.empty[String]
      var rangeStart = sorted.head
      var rangeEnd = sorted.head
      for i <- sorted.tail do
        if i == rangeEnd + 1 then rangeEnd = i
        else
          parts += formatRange(rangeStart, rangeEnd)
          rangeStart = i
          rangeEnd = i
      parts += formatRange(rangeStart, rangeEnd)
      s"[${parts.mkString(",")}]"

  private def formatRange(start: Int, end: Int): String =
    if start == end then start.toString else s"$start-$end"

  // ===== Replace (atomic) =====

  private def doReplace(input: JsonObject, ref: Ref[IO, List[Message]]): IO[Either[ToolError, String]] =
    val content = input("content").flatMap(_.asString).getOrElse("")

    if content.isEmpty then IO.pure(Left(ToolError("replace requires non-empty content")))
    else
      resolveTargetIndices(input) match
        case None => IO.pure(Left(ToolError("replace requires: index, (start_index + end_index), or indices")))
        case Some(displayIndices) =>
          val idxLabel = formatIndices(displayIndices)
          // Validate and execute inside ref.modify to prevent TOCTOU with concurrent operations
          ref
            .modify { messages =>
              // Validate mapping inside the atomic block
              lastInspectMapping.get() match
                case None =>
                  (messages, Left(ToolError("Must run inspect before replace.")))
                case Some(mapping) =>
                  val invalidDisplay = displayIndices.filter(i => i < 0 || i >= mapping.length)
                  if invalidDisplay.nonEmpty then
                    (
                      messages,
                      Left(
                        ToolError(
                          s"Index(es) ${invalidDisplay.mkString("[", ",", "]")} are outside inspect range [0-${mapping.length - 1}]. Run inspect first."
                        )
                      )
                    )
                  else
                    val globalIndices = displayIndices.map(mapping(_))
                    val units = buildUnits(messages)
                    val invalidGlobal = globalIndices.filter(i => i < 0 || i >= units.length)
                    if invalidGlobal.nonEmpty then
                      (
                        messages,
                        Left(
                          ToolError(
                            s"Invalid index(es) ${invalidGlobal.mkString("[", ",", "]")} (valid: 0-${units.length - 1})"
                          )
                        )
                      )
                    else
                      val targetUnits = globalIndices.flatMap(i => units.lift(i))
                      val allLocs = targetUnits.flatMap(_.locs)

                      if wouldRemoveLastUser(messages, allLocs) then
                        (messages, Left(ToolError("Cannot replace range that includes the last user message.")))
                      else
                        val (cleaned, insertAt) = removeBlocks(messages, allLocs)
                        val summaryMsg = Message(MessageRole.Assistant, Left(s"[context summary] $content"))
                        val finalMessages = cleaned.take(insertAt) ++ (summaryMsg :: cleaned.drop(insertAt))
                        (
                          finalMessages,
                          Right(
                            s"OK: Replaced units $idxLabel (${targetUnits.length} units). Context: ${finalMessages.length} msgs.$indexShiftTip"
                          )
                        )
                    end if
                  end if
              end match
            }
            .flatMap {
              case r @ Right(_) =>
                clearInspectMapping()
                logger.info(s"Replaced units $idxLabel") *> IO.pure(r)
              case l @ Left(_) => IO.pure(l)
            }
    end if
  end doReplace

end ContextManageTool
