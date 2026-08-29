package nebflow.core

import cats.effect.IO
import io.circe.JsonObject
import nebflow.core.tools.BashTool

// ============================================================
// Safety modes — three trust levels controlled by the user
// ============================================================
//
// Mode 1: confirm-edits  (default, safest)
//   Write, Edit, Bash  → need confirmation
//   Everything else    → auto-approved
//
// Mode 2: auto-edits
//   Bash               → needs confirmation
//   Write, Edit        → auto-approved
//   Everything else    → auto-approved
//
// Mode 3: auto-all
//   Everything         → auto-approved (no confirmation ever)
//
// New/unknown tools (ScriptTool, MCP, etc.) default to auto-approved
// in all modes — they are trusted because the user chose to install them.

enum SafetyMode:
  case ConfirmEdits, AutoEdits, AutoAll

object SafetyMode:

  def fromString(s: String): SafetyMode = s match
    case "auto-edits" => AutoEdits
    case "auto-all" => AutoAll
    case _ => ConfirmEdits

  def toString(m: SafetyMode): String = m match
    case ConfirmEdits => "confirm-edits"
    case AutoEdits => "auto-edits"
    case AutoAll => "auto-all"

  given io.circe.Encoder[SafetyMode] =
    io.circe.Encoder.encodeString.contramap(toString)

  given io.circe.Decoder[SafetyMode] =
    io.circe.Decoder.decodeString.map(fromString)

end SafetyMode

// ============================================================
// 递进式放行链 (2026-08-30): permission-card answers may carry an
// optional `upgradeMode` — "allow this one AND switch the session to
// <mode>". Escalation ladder: ConfirmEdits → AutoEdits → AutoAll.
//
// Wire contract (permissionAnswer):
//   { approved: true, upgradeMode?: "auto-edits" | "auto-all" }
//   — old replies without upgradeMode are byte-compatible (no upgrade).
//   — a deny with upgradeMode is invalid: the upgrade is IGNORED (warn)
//     and the deny proceeds unchanged (rule #12 spirit: never transform
//     a reply into something the user did not click).
//   — "confirm-edits" / unknown values are not escalation targets and
//     are rejected here (route layer logs and proceeds as plain allow).
// ============================================================
object PermissionUpgrade:

  /** Parse an escalation request off a permission-card reply. */
  def parse(approved: Boolean, upgradeMode: Option[String]): Either[String, Option[SafetyMode]] =
    upgradeMode match
      case None         => Right(None)
      case Some("auto-edits") if approved => Right(Some(SafetyMode.AutoEdits))
      case Some("auto-all")   if approved => Right(Some(SafetyMode.AutoAll))
      case Some(raw) if !approved =>
        Left(s"upgradeMode '$raw' requires approved=true — deny replies must not carry an upgrade")
      case Some(other) => Left(s"unknown upgradeMode '$other' — valid targets: auto-edits, auto-all")

end PermissionUpgrade

// ============================================================
// F1 (#433): global safety mode
//
// The user's auto-all was only ever a single session's meta field — the
// system had no global channel, and ~/.nebflow/permission_policy.json was
// a dead file no code read (incident 2026-08-26). This object wires the
// global mode from nebflow.json:
//
//   { "safety": { "defaultMode": "auto-all" } }
//
// Hot-read per access (config path resolves per call, same pattern as
// PresetStore) — flipping the value takes effect on the next decision /
// seeding without a restart. Missing key, unparsable file, or unknown
// value all fall back to ConfirmEdits.
// ============================================================
object GlobalSafety:

  /** Read `safety.defaultMode` from nebflow.json; ConfirmEdits on any miss. */
  def defaultMode: IO[SafetyMode] =
    IO.blocking {
      val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(configPath) then None
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField("safety").downField("defaultMode").as[String].toOption)
    }.handleErrorWith(_ => IO.pure(None))
      .map(_.fold(SafetyMode.ConfirmEdits)(SafetyMode.fromString))

end GlobalSafety

// ============================================================
// Reversibility check — drives the confirm/auto-approve decision
// ============================================================

object ToolReversibility:

  // Tools that always need confirmation regardless of mode
  // (except auto-all which overrides everything)
  private val EditTools = Set("Write", "Edit")

  // Bash is special — same tool can be safe or dangerous depending on command
  private val SafeHttpMethods = Set("GET", "HEAD", "OPTIONS")

  def isReversible(toolName: String, input: JsonObject, mode: SafetyMode): Boolean =
    mode match
      case SafetyMode.AutoAll =>
        true

      case SafetyMode.AutoEdits =>
        // Write/Edit auto-approved, Bash still checked
        if EditTools.contains(toolName) then true
        else if toolName == "Bash" then checkBash(input)
        else if toolName == "Curl" then checkCurl(input)
        else true // all other tools (Read, Grep, MCP, ScriptTool, etc.) auto-approved

      case SafetyMode.ConfirmEdits =>
        // Write/Edit need confirmation, Bash checked, others auto-approved
        if EditTools.contains(toolName) then false
        else if toolName == "Bash" then checkBash(input)
        else if toolName == "Curl" then checkCurl(input)
        else true // all other tools auto-approved

  private def checkBash(input: JsonObject): Boolean =
    input("command").flatMap(_.asString).forall { cmd =>
      !BashTool.isDangerous(cmd) && BashTool.checkInjection(cmd).isEmpty
    }

  private def checkCurl(input: JsonObject): Boolean =
    input("method").flatMap(_.asString).forall(m => SafeHttpMethods.contains(m.toUpperCase))

end ToolReversibility
