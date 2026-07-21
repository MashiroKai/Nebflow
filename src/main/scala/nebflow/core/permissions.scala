package nebflow.core

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
