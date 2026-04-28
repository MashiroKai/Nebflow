package nebflow.core

import cats.effect.{IO, Ref}

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
    policyRef.update(f)

  def setPolicy(policy: PermissionPolicy): IO[Unit] =
    policyRef.set(policy)

object PermissionState:
  def create: IO[PermissionState] =
    for
      policyRef <- Ref.of[IO, PermissionPolicy](PermissionPolicy.default)
      countRef <- Ref.of[IO, Map[String, Int]](Map.empty)
    yield new PermissionState(policyRef, countRef)
