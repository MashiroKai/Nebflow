package nebflow.core

enum NebflowError:
  case ToolFailed(toolName: String, message: String)
  case PathViolation(path: String, reason: String)
  case LlmFailed(summary: String, attempts: List[String])
  case AuthFailed(reason: String)
  case RateLimited(source: String)
  case Internal(message: String)

object NebflowError:

  def toUserMessage(err: NebflowError): String = err match
    case ToolFailed(tool, msg) => s"Tool '$tool' failed: $msg"
    case PathViolation(path, reason) => s"Path access denied ($path): $reason"
    case LlmFailed(summary, attempts) =>
      val lower = attempts.mkString(" ").toLowerCase
      val transient = lower.contains("timeout") || lower.contains("rate") ||
        lower.contains("overload") || lower.contains("server") || lower.contains("reset")
      if transient then "LLM 服务暂时不可用，请稍后重试"
      else if lower.contains("auth") then "LLM 认证失败，请检查 API Key 配置"
      else s"AI request failed: $summary"
    case AuthFailed(reason) => s"Authentication failed: $reason"
    case RateLimited(source) => s"Rate limit exceeded ($source). Please wait and try again."
    case Internal(message) => s"Internal error: $message"
end NebflowError
