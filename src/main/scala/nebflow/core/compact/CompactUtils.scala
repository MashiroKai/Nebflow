package nebflow.core.compact

import nebflow.shared.*

object CompactUtils:

  /** Replace Image blocks with placeholder text to avoid sending base64 to SubAgent. */
  def stripImages(messages: List[Message]): List[Message] =
    messages.map {
      case msg @ Message(_, Right(blocks)) =>
        val stripped = blocks.map {
          case ContentBlock.Image(_, mediaType) => ContentBlock.Text(s"[image: $mediaType]")
          case other                            => other
        }
        msg.copy(content = Right(stripped))
      case other => other
    }

end CompactUtils
