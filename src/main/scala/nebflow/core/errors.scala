package nebflow.core

enum NebflowError:
  case ToolFailed(toolName: String, message: String)
  case ToolDenied(toolName: String, reason: String)
  case ToolBlocked(toolName: String, reason: String)
  case PathViolation(path: String, reason: String)
  case LlmFailed(summary: String, attempts: List[String])
  case AuthFailed(reason: String)
  case RateLimited(source: String)
  case Internal(message: String)

object NebflowError:
  def toUserMessage(err: NebflowError): String = err match
    case ToolFailed(tool, msg)       => s"Tool '$tool' failed: $msg"
    case ToolDenied(tool, reason)    => s"Tool '$tool' denied: $reason"
    case ToolBlocked(tool, reason)   => s"Tool '$tool' blocked: $reason"
    case PathViolation(path, reason) => s"Path access denied ($path): $reason"
    case LlmFailed(summary, attempts) =>
      if attempts.isEmpty then s"AI request failed: $summary"
      else s"AI request failed: $summary\nAttempts: ${attempts.mkString(", ")}"
    case AuthFailed(reason)          => s"Authentication failed: $reason"
    case RateLimited(source)         => s"Rate limit exceeded ($source). Please wait and try again."
    case Internal(message)           => s"Internal error: $message"
end NebflowError
