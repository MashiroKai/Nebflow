package nebflow.core

import io.circe.*
import io.circe.syntax.*

enum ToolRisk:
  case Safe
  case NeedsApproval

object ToolRisk:

  private val defaults: Map[String, ToolRisk] = Map(
    "Read" -> Safe,
    "Glob" -> Safe,
    "Grep" -> Safe,
    "WebSearch" -> Safe,
    "WebFetch" -> Safe,
    "AskUserQuestion" -> Safe,
    "ContextManage" -> Safe,
    "Bash" -> NeedsApproval,
    "Write" -> NeedsApproval,
    "Edit" -> NeedsApproval,
    "Curl" -> NeedsApproval
  )

  def classify(toolName: String): ToolRisk =
    defaults.getOrElse(toolName, NeedsApproval)

enum ApprovalDecision:
  case Approved
  case NeedsUserApproval
  case Blocked(reason: String)

case class PermissionPolicy(
  autoApproveAll: Boolean = false,
  autoApproveTools: Set[String] = Set.empty,
  blockedTools: Set[String] = Set.empty
)

object PermissionPolicy:

  given Encoder[PermissionPolicy] = Encoder.instance { p =>
    Json.obj(
      "autoApproveAll" -> p.autoApproveAll.asJson,
      "autoApproveTools" -> p.autoApproveTools.toList.asJson,
      "blockedTools" -> p.blockedTools.toList.asJson
    )
  }

  given Decoder[PermissionPolicy] = Decoder.instance { c =>
    for
      auto <- c.downField("autoApproveAll").as[Option[Boolean]].map(_.getOrElse(false))
      autoTools <- c.downField("autoApproveTools").as[Option[List[String]]].map(_.getOrElse(Nil).toSet)
      blocked <- c.downField("blockedTools").as[Option[List[String]]].map(_.getOrElse(Nil).toSet)
    yield PermissionPolicy(auto, autoTools, blocked)
  }

  def default: PermissionPolicy = PermissionPolicy()

  /** Map a policy back to a simple name string for the frontend. */
  def toName(p: PermissionPolicy): String =
    if p.autoApproveAll then "auto"
    else if p.blockedTools.nonEmpty then "block"
    else "ask"

  def fromString(s: String): PermissionPolicy = s match
    case "auto" => PermissionPolicy(autoApproveAll = true)
    case "ask" => PermissionPolicy.default
    case "block" => PermissionPolicy(blockedTools = Set("Bash", "Write", "Edit", "Curl"))
    case _ => PermissionPolicy.default

end PermissionPolicy

