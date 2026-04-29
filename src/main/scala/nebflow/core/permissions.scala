package nebflow.core

import cats.effect.{IO, Ref}
import io.circe.*
import io.circe.syntax.*
import io.circe.parser.decode

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
  blockedTools: Set[String] = Set.empty,
  autoApproveAfter: Int = Int.MaxValue
)

object PermissionPolicy:
  given Encoder[PermissionPolicy] = Encoder.instance { p =>
    Json.obj(
      "autoApproveAll" -> p.autoApproveAll.asJson,
      "autoApproveTools" -> p.autoApproveTools.toList.asJson,
      "blockedTools" -> p.blockedTools.toList.asJson,
      "autoApproveAfter" -> p.autoApproveAfter.asJson
    )
  }

  given Decoder[PermissionPolicy] = Decoder.instance { c =>
    for
      auto <- c.downField("autoApproveAll").as[Option[Boolean]].map(_.getOrElse(false))
      autoTools <- c.downField("autoApproveTools").as[Option[List[String]]].map(_.getOrElse(Nil).toSet)
      blocked <- c.downField("blockedTools").as[Option[List[String]]].map(_.getOrElse(Nil).toSet)
      after <- c.downField("autoApproveAfter").as[Option[Int]].map(_.getOrElse(Int.MaxValue))
    yield PermissionPolicy(auto, autoTools, blocked, after)
  }

  def default: PermissionPolicy = PermissionPolicy()
  def fromString(s: String): PermissionPolicy = s match
    case "auto"     => PermissionPolicy(autoApproveAll = true)
    case "safe"     => PermissionPolicy(autoApproveTools = Set("Read", "Glob", "Grep", "WebSearch", "WebFetch", "AskUserQuestion"))
    case "ask"      => PermissionPolicy.default
    case "block"    => PermissionPolicy(blockedTools = Set("Bash", "Write", "Edit", "Curl"))
    case _          => PermissionPolicy.default

class PermissionState private (
  policyRef: Ref[IO, PermissionPolicy],
  approvalCountRef: Ref[IO, Map[String, Int]]
):
  def policy: IO[PermissionPolicy] = policyRef.get

  def shouldApprove(toolName: String): IO[ApprovalDecision] =
    for
      p <- policyRef.get
      counts <- approvalCountRef.get
      count = counts.getOrElse(toolName, 0)
    yield
      if p.autoApproveAll then ApprovalDecision.Approved
      else if p.blockedTools.contains(toolName) then ApprovalDecision.Blocked(s"$toolName is blocked by policy")
      else if p.autoApproveTools.contains(toolName) then ApprovalDecision.Approved
      else if p.autoApproveAfter > 0 && count >= p.autoApproveAfter then ApprovalDecision.Approved
      else ApprovalDecision.NeedsUserApproval

  def recordApproval(toolName: String): IO[Unit] =
    approvalCountRef.update(m => m.updatedWith(toolName)(_.map(_ + 1).orElse(Some(1))))

  def updatePolicy(f: PermissionPolicy => PermissionPolicy): IO[Unit] =
    policyRef.update(f) *> PermissionState.savePolicy(policyRef)

  def setPolicy(policy: PermissionPolicy): IO[Unit] =
    policyRef.set(policy) *> PermissionState.savePolicy(policyRef)

object PermissionState:
  private val policyPath: os.Path = os.home / ".nebflow" / "permission_policy.json"

  private def loadPolicy: PermissionPolicy =
    try
      if os.exists(policyPath) then decode[PermissionPolicy](os.read(policyPath)).getOrElse(PermissionPolicy.default)
      else PermissionPolicy.default
    catch case _: Exception => PermissionPolicy.default

  private def savePolicy(ref: Ref[IO, PermissionPolicy]): IO[Unit] =
    ref.get.flatMap { p =>
      IO.blocking {
        try os.write.over(policyPath, p.asJson.spaces2, createFolders = true)
        catch case _: Exception => ()
      }
    }

  def create: IO[PermissionState] =
    for
      saved <- IO.blocking(loadPolicy)
      policyRef <- Ref.of[IO, PermissionPolicy](saved)
      countRef <- Ref.of[IO, Map[String, Int]](Map.empty)
    yield new PermissionState(policyRef, countRef)
