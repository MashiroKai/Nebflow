package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

object FolderCommand extends CliCommand:
  def name = "folder"
  def description = "Manage folders"
  def subcommands = List(FolderList, FolderCreate, FolderDelete, FolderSetRoot, FolderRules)

  def examples = List(
    "nebflow folder list",
    "nebflow folder create MyProject",
    "nebflow folder set-root abc-123 /path/to/project"
  )

  private object FolderList extends CliSubcommand:
    def name = "list"
    def description = "List folders"

    def params = List(
      CliParam("agent", Some('a'), "Agent name", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val agentName = ctx.args.getOrElse("agent", "Nebula")
          client
            .command(
              Json.obj(
                "type" -> "listAgentSessions".asJson,
                "name" -> agentName.asJson
              )
            )
            .map { resp =>
              if ctx.json then CliResult.Json(resp)
              else
                val folders = resp.hcursor.downField("folders").as[List[Json]].getOrElse(Nil)
                val lines = folders.map { f =>
                  val id = f.hcursor.downField("id").as[String].getOrElse("").take(8)
                  val name = f.hcursor.downField("name").as[String].getOrElse("-")
                  val pr = f.hcursor.downField("projectRoot").as[Option[String]].toOption.flatten.getOrElse("")
                  if pr.nonEmpty then s"$id  $name  → $pr" else s"$id  $name"
                }
                if lines.isEmpty then CliResult.text("No folders")
                else CliResult.Text("ID        Name              Project Root" :: lines)
            }

  end FolderList

  private object FolderCreate extends CliSubcommand:
    def name = "create"
    def description = "Create a folder"

    def params = List(
      CliParam("name", Some('n'), "Folder name", required = true),
      CliParam("parent", Some('p'), "Parent folder ID", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val name = ctx.positionalArgs.headOption.getOrElse(ctx.args.getOrElse("name", ""))
          if name.isEmpty then IO.pure(CliResult.Error("Folder name required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "createFolder".asJson,
                  "name" -> name.asJson,
                  "parentId" -> ctx.args.get("parent").asJson
                )
              )
              .as(CliResult.text(s"Folder '$name' created"))

  end FolderCreate

  private object FolderDelete extends CliSubcommand:
    def name = "delete"
    def description = "Delete a folder"
    def params = List(CliParam("folder-id", None, "Folder ID", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val folderId = ctx.positionalArgs.headOption.getOrElse("")
          if folderId.isEmpty then IO.pure(CliResult.Error("Folder ID required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "deleteFolder".asJson,
                  "folderId" -> folderId.asJson
                )
              )
              .as(CliResult.text(s"Folder $folderId deleted"))

  end FolderDelete

  private object FolderSetRoot extends CliSubcommand:
    def name = "set-root"
    def description = "Set project root for a top-level folder"

    def params = List(
      CliParam("folder-id", None, "Folder ID", required = true),
      CliParam("path", Some('p'), "Project root path", required = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val folderId = ctx.positionalArgs.headOption.getOrElse("")
          val path = ctx.args.get("path").orElse(ctx.positionalArgs.lift(1)).getOrElse("")
          if folderId.isEmpty then IO.pure(CliResult.Error("Folder ID required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "setFolderProjectRoot".asJson,
                  "folderId" -> folderId.asJson,
                  "projectRoot" -> (if path.nonEmpty then Some(path) else None).asJson
                )
              )
              .as(CliResult.text(s"Project root set for folder $folderId"))

  end FolderSetRoot

  private object FolderRules extends CliSubcommand:
    def name = "rules"
    def description = "View or set rules.md for a folder"

    def params = List(
      CliParam("folder-id", None, "Folder ID", required = true),
      CliParam("content", Some('c'), "Rules content (omit to view)", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val folderId = ctx.positionalArgs.headOption.getOrElse("")
          if folderId.isEmpty then IO.pure(CliResult.Error("Folder ID required"))
          else
            ctx.args.get("content") match
              case Some(content) =>
                client
                  .command(
                    Json.obj(
                      "type" -> "saveRules".asJson,
                      "folderId" -> folderId.asJson,
                      "content" -> content.asJson
                    )
                  )
                  .as(CliResult.text(s"Rules updated for folder $folderId"))
              case None =>
                client
                  .command(
                    Json.obj(
                      "type" -> "getRules".asJson,
                      "folderId" -> folderId.asJson
                    )
                  )
                  .map(resp => CliResult.Json(resp))
          end if
  end FolderRules
end FolderCommand
