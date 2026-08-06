package nebflow.core.workspace

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.util.UUID

/** A knowledge item stored in the workspace — markdown notes, code snippets, HTML cards, etc. */
case class WorkspaceItem(
  id: String,
  sessionId: String,
  title: String,
  itemType: String,
  content: String,
  createdAt: Long
)

object WorkspaceItem:

  given Codec[WorkspaceItem] = deriveCodec

  def create(
    sessionId: String,
    title: String,
    itemType: String,
    content: String
  ): WorkspaceItem =
    WorkspaceItem(
      id = UUID.randomUUID().toString.take(8),
      sessionId = sessionId,
      title = title,
      itemType = itemType,
      content = content,
      createdAt = System.currentTimeMillis()
    )

end WorkspaceItem
