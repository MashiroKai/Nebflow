package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.workspace.{KnowledgeStore, WorkspaceItem}

object SaveWorkspaceItemTool extends Tool:
  val name = "SaveWorkspaceItem"

  val description =
    """Save a knowledge item to the workspace sidebar.

## When to Use

- After producing a structured output (markdown summary, code snippet, HTML card)
- When the user wants to save a result for later reference
- When you want to make an output easily accessible from the sidebar

## Fields

- **title**: Brief title for the item
- **itemType**: One of "markdown", "code", "html"
- **content**: Full content of the item"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "title" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Brief title for the workspace item".asJson
        ),
        "itemType" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Item type: markdown, code, or html".asJson,
          "enum" -> Json.arr("markdown".asJson, "code".asJson, "html".asJson)
        ),
        "content" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Full content to save".asJson
        )
      ),
      "required" -> Json.arr("title".asJson, "itemType".asJson, "content".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val title = input("title").flatMap(_.asString).getOrElse("")
    val itemType = input("itemType").flatMap(_.asString).getOrElse("")
    s"SaveWorkspaceItem($title, $itemType)"

  def summarizeResult(input: JsonObject, result: String): String =
    "saved to workspace"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.sharedResources match
      case Some(sr) =>
        val sessionId = ctx.sessionId.getOrElse("global")
        val title = input("title").flatMap(_.asString).getOrElse("Untitled")
        val itemType = input("itemType").flatMap(_.asString).getOrElse("markdown")
        val content = input("content").flatMap(_.asString).getOrElse("")
        val item = WorkspaceItem.create(sessionId, title, itemType, content)
        val notify = ctx.wsSend match
          case Some(send) =>
            send(
              Json.obj(
                "type" -> "workspaceItemSaved".asJson,
                "item" -> Json.obj(
                  "id" -> item.id.asJson,
                  "sessionId" -> item.sessionId.asJson,
                  "title" -> item.title.asJson,
                  "itemType" -> item.itemType.asJson,
                  "content" -> item.content.asJson,
                  "createdAt" -> item.createdAt.asJson
                )
              )
            )
          case None => IO.unit
        for
          _ <- sr.knowledgeStore.addItem(item)
          _ <- notify
        yield Right(s"Saved to workspace: ${item.title} (id: ${item.id})")
      case None => IO.pure(Left(ToolError("No shared resources available")))

end SaveWorkspaceItemTool
