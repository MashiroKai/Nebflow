package nebflow.core.executor

import nebflow.core.executor.ExternalExecutor.Frame

import scala.collection.mutable

object ExternalMessageCollector:
  /** One committed text block, or one bounded chunk of a streamed message. */
  final case class CollectedMessage(
      id: String,
      text: String,
      protocolMessageId: Option[String],
      blockIndex: Int
  )

/** Collects one execution attempt. Callers serialize access with stdout ingestion. */
final class ExternalMessageCollector(runId: String):
  import ExternalMessageCollector.CollectedMessage

  private final class Message(val key: String, val protocolId: Option[String], val streamed: Boolean):
    val blocks = mutable.LinkedHashMap.empty[Int, String]
    def text: String = blocks.values.mkString(if streamed then "" else "\n")

  private val committed = mutable.ListBuffer.empty[CollectedMessage]
  private val warnings = mutable.ListBuffer.empty[String]
  private val endings = mutable.ListBuffer.empty[Frame.ExecutionEnd]
  private val identified = mutable.Map.empty[String, Message]
  private val resultIds = mutable.Set.empty[String]
  private var nextAnonymous = 0L
  private var active: Option[Message] = None
  private val pending = new StringBuilder
  private var adjacent: Option[Message] = None
  private var terminalSeen = false
  private var failureSeen = false

  def messages: List[CollectedMessage] = committed.toList
  def allTexts: List[String] = committed.iterator.map(_.text).toList
  def hasTerminal: Boolean = terminalSeen
  def failed: Boolean = failureSeen
  def diagnostics: List[String] = warnings.toList
  def finalResults: List[Frame.ExecutionEnd] = endings.toList

  private def warn(message: String): Unit =
    if !warnings.contains(message) then warnings += message

  private def newMessage(protocolId: Option[String], streamed: Boolean): Message =
    protocolId match
      case Some(id) =>
        identified.getOrElseUpdate(id, new Message(s"protocol/${id.length}:$id", Some(id), streamed))
      case None =>
        nextAnonymous += 1
        new Message(s"anonymous/$nextAnonymous", None, streamed)

  private def commit(message: Message, index: Int, text: String): Unit =
    message.blocks.get(index) match
      case Some(previous) =>
        if previous != text then warn("Conflicting text for an already committed message block.")
      case None =>
        message.blocks(index) = text
        if text.nonEmpty then
          committed += CollectedMessage(s"$runId/${message.key}/block/$index", text, message.protocolId, index)

  private def drain(): Unit =
    if pending.nonEmpty then
      val message = active.getOrElse {
        val created = newMessage(None, streamed = true)
        active = Some(created)
        created
      }
      val index = message.blocks.keysIterator.maxOption.fold(0)(_ + 1)
      commit(message, index, pending.toString)
      pending.clear()

  private def closeStream(): Unit =
    drain()
    active.foreach { message =>
      if message.blocks.nonEmpty then adjacent = Some(message)
    }
    active = None

  private def newlyCommitted(action: => Unit): List[CollectedMessage] =
    val offset = committed.size
    action
    committed.drop(offset).toList

  /** Seal a bounded streaming chunk without declaring EOF or protocol termination. */
  def drainPending(): List[CollectedMessage] = newlyCommitted(drain())

  /** Return only blocks committed by this frame, never token-sized deltas. */
  def accept(frame: Frame): List[CollectedMessage] = newlyCommitted {
    frame match
      case Frame.Start(_) => ()
      case Frame.Text(text) =>
        if active.isEmpty then active = Some(newMessage(None, streamed = true))
        pending.append(text)
      case Frame.MessageBoundary(messageId) =>
        closeStream()
        active = messageId.map(id => newMessage(Some(id), streamed = true))
      case Frame.Tool(_) =>
        closeStream()
        adjacent = None
      case Frame.MessageComplete(messageId, blocks) =>
        closeStream()
        val message = newMessage(messageId, streamed = false)
        blocks.foreach(block => commit(message, block.index, block.text))
        adjacent = Some(message)
      case end: Frame.ExecutionEnd => finish(end)
      case Frame.Final(text, failed) =>
        finish(Frame.ExecutionEnd(failed, Some(text)))
  }

  private def finish(end: Frame.ExecutionEnd): Unit =
    closeStream()
    terminalSeen = true
    failureSeen ||= end.failed
    endings += end
    val resultId = end.metadata.hcursor.get[String]("uuid").toOption
      .orElse(end.metadata.hcursor.get[String]("eventId").toOption)
    val repeated = resultId.exists(resultIds.contains)
    resultId.foreach(resultIds.add)
    if !repeated then
      end.result.filter(_.nonEmpty).foreach { text =>
        val correlated = end.messageId match
          case Some(id) => identified.get(id)
          case None => adjacent.filter(_.text == text)
        correlated match
          case Some(message) =>
            if message.text != text then warn("The final result differs from its identified assistant message.")
          case None =>
            val message = newMessage(end.messageId, streamed = false)
            commit(message, 0, text)
      }
    // A result can echo its immediately preceding assistant message, not an arbitrary older result.
    adjacent = None

  /** Flush EOF text; absence of a protocol terminal is diagnostic, not inferred success. */
  def flush(): List[CollectedMessage] = newlyCommitted {
    closeStream()
    if !terminalSeen then warn("The external CLI stream ended without a protocol terminal event.")
  }
end ExternalMessageCollector
