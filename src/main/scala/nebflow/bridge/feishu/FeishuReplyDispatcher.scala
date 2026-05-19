package nebflow.bridge.feishu

import cats.effect.{IO, Ref}
import nebflow.bridge.FeishuClient
import nebflow.core.NebflowLogger

/**
 * Manages outbound messages to Feishu for a single conversation turn.
 *
 * Inspired by OpenClaw's per-conversation reply dispatcher pattern:
 * - Created only when a Feishu message triggers a turn
 * - Accumulates streaming text deltas and patches the card with throttle
 * - Manages typing indicator (emoji reaction on user's message)
 * - Destroyed when the agent signals completion (done event)
 *
 * This ensures only Feishu-triggered conversations get pushed to Feishu.
 */
class FeishuReplyDispatcher(
  client: FeishuClient,
  chatId: String,
  chatIdType: String,
  sessionId: String,
  userMessageId: String
):
  private val logger = NebflowLogger.forName("nebflow.bridge.feishu.dispatcher")

  private val textBuffer = Ref.unsafe[IO, StringBuilder](new StringBuilder)
  private val cardMsgId = Ref.unsafe[IO, Option[String]](None)
  private val lastPatchMs = Ref.unsafe[IO, Long](0L)
  private val typingReactionId = Ref.unsafe[IO, Option[String]](None)

  private val PatchIntervalMs = 300L
  private val MaxCardLength = 4000
  private val TypingEmoji = "HEADSET" // 🎧 — "处理中" typing indicator

  /** Show typing indicator on user's message. Non-blocking — errors are logged, not raised. */
  def showTyping(): IO[Unit] =
    client.addReaction(userMessageId, TypingEmoji).flatMap { reactionId =>
      typingReactionId.set(Some(reactionId))
    }.handleErrorWith { e =>
      logger.warn(s"[feishu] add typing reaction failed: ${e.getMessage}")
    }

  /** Append a text delta. Accumulates and fires async patch with throttle. */
  def onTextDelta(delta: String): IO[Unit] =
    if delta.isEmpty then IO.unit
    else textBuffer.update(_ ++= delta) *> fireAsyncPatch()

  /** Finalize the turn: flush remaining text, remove typing indicator. */
  def onDone(): IO[Unit] =
    textBuffer.get.map(_.toString).flatMap { text =>
      if text.nonEmpty then finalFlush(text) else IO.unit
    } *> removeTyping()

  /** Force-flush any remaining text (used when replacing with a new dispatcher). */
  def flushAndClose(): IO[Unit] =
    textBuffer.get.map(_.toString).flatMap { text =>
      if text.nonEmpty then finalFlush(text).attempt.void else IO.unit
    } *> removeTyping()

  // -- internal --

  /** Fire-and-forget: schedule a patch if throttle allows. Never blocks the caller. */
  private def fireAsyncPatch(): IO[Unit] =
    val now = System.currentTimeMillis()
    lastPatchMs.get.flatMap { last =>
      if now - last >= PatchIntervalMs then
        lastPatchMs.set(now)
        cardMsgId.get.flatMap {
          case Some(msgId) =>
            // Already have a card — patch asynchronously (fire-and-forget)
            textBuffer.get.flatMap { buf =>
              val text = truncate(buf.toString)
              if text.nonEmpty then
                client.patchReplyCard(msgId, text).handleErrorWith(_ => IO.unit).start.void
              else IO.unit
            }
          case None =>
            // First message — must be synchronous to capture msgId
            textBuffer.get.flatMap { buf =>
              val text = buf.toString
              if text.nonEmpty then sendOrPatch(text) else IO.unit
            }
        }
      else IO.unit
    }

  private def sendOrPatch(text: String): IO[Unit] =
    val display = truncate(text)
    cardMsgId.get.flatMap {
      case Some(msgId) =>
        client.patchReplyCard(msgId, display).handleErrorWith { e =>
          logger.warn(s"[feishu] patch failed: ${e.getMessage}")
        }
      case None =>
        // First delta for this turn — create a new card
        client.sendReplyCard(chatId, chatIdType, display).flatMap { msgId =>
          cardMsgId.set(Some(msgId))
        }.void.handleErrorWith { e =>
          logger.warn(s"[feishu] send card failed: ${e.getMessage}")
        }
    }

  private def finalFlush(text: String): IO[Unit] =
    val display = truncate(text)
    cardMsgId.get.flatMap {
      case Some(msgId) =>
        client.patchReplyCard(msgId, display).handleErrorWith { e =>
          logger.warn(s"[feishu] final patch failed: ${e.getMessage}")
        }
      case None =>
        client.sendReplyCard(chatId, chatIdType, display).void.handleErrorWith { e =>
          logger.warn(s"[feishu] final send failed: ${e.getMessage}")
        }
    }

  private def removeTyping(): IO[Unit] =
    typingReactionId.get.flatMap {
      case Some(reactionId) =>
        typingReactionId.set(None) *>
          client.deleteReaction(userMessageId, reactionId).handleErrorWith { e =>
            logger.debug(s"[feishu] remove typing reaction failed: ${e.getMessage}")
          }
      case None => IO.unit
    }

  private def truncate(text: String): String =
    val converted = convertMarkdown(text)
    if converted.length > MaxCardLength then converted.take(MaxCardLength - 20) + "\n...(truncated)" else converted

  /** Convert standard markdown to Feishu-compatible format. */
  private def convertMarkdown(text: String): String =
    val t1 = """(?s)```[a-zA-Z]*\n""".r.replaceAllIn(text, "")
    val t2 = "```".r.replaceAllIn(t1, "")
    """`([^`]+)`""".r.replaceAllIn(t2, m => s"""<font color="grey">${m.group(1)}</font>""")

end FeishuReplyDispatcher
