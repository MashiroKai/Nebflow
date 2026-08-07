//! Error classification and fallback logic.
//! Mirrors nebflow.llm.Fallback from Scala.

use crate::types::{ErrorClassification, ErrorPermanence, FailoverReason};

/// Maximum retries per provider before falling through to the next.
pub const MAX_RETRIES: u32 = 1;

/// Initial backoff in milliseconds for transient retries.
pub const INITIAL_BACKOFF_MS: u64 = 1_000;

/// Maximum backoff cap in milliseconds.
pub const MAX_BACKOFF_MS: u64 = 10_000;

/// Default per-provider request timeout (covers streaming generation).
pub const DEFAULT_TIMEOUT_MS: u64 = 600_000;

/// Classify an error into reason + permanence.
///
/// Fatal: affects all providers (e.g. context overflow) — abort.
/// Permanent: skip to next provider (e.g. auth, model not found).
/// Transient: retry same provider with backoff (e.g. rate limit, connection reset).
pub fn classify_error(status: Option<u16>, message: &str) -> ErrorClassification {
    let msg_lower = message.to_lowercase();

    if let Some(code) = status {
        let reason = match code {
            401 | 403 => FailoverReason::Auth,
            404 => FailoverReason::ModelNotFound,
            429 => FailoverReason::RateLimit,
            500 | 502 | 503 => FailoverReason::ServerError,
            529 => FailoverReason::Overloaded,
            400 => FailoverReason::Format,
            _ => FailoverReason::ProviderError,
        };

        let is_context_overflow = msg_lower.contains("context_length_exceeded")
            || msg_lower.contains("maximum context length")
            || msg_lower.contains("reduce the length of the messages");

        let permanence = match code {
            400 if is_context_overflow => ErrorPermanence::Fatal,
            401 | 403 | 404 | 400 => ErrorPermanence::Permanent,
            _ => ErrorPermanence::Transient,
        };

        return ErrorClassification {
            reason,
            permanence,
            status_code: Some(code),
            message: Some(message.to_string()),
        };
    }

    // No status code — classify by message content
    if msg_lower.contains("connection reset")
        || msg_lower.contains("econnreset")
        || msg_lower.contains("econnrefused")
        || msg_lower.contains("epipe")
        || msg_lower.contains("broken pipe")
    {
        ErrorClassification {
            reason: FailoverReason::ConnectionReset,
            permanence: ErrorPermanence::Transient,
            status_code: None,
            message: Some(message.to_string()),
        }
    } else if msg_lower.contains("timeout") || msg_lower.contains("timed out") {
        ErrorClassification {
            reason: FailoverReason::Timeout,
            permanence: ErrorPermanence::Transient,
            status_code: None,
            message: Some(message.to_string()),
        }
    } else if msg_lower.contains("auth")
        || msg_lower.contains("unauthorized")
        || msg_lower.contains("403")
        || msg_lower.contains("401")
    {
        ErrorClassification {
            reason: FailoverReason::Auth,
            permanence: ErrorPermanence::Permanent,
            status_code: None,
            message: Some(message.to_string()),
        }
    } else if msg_lower.contains("rate limit") || msg_lower.contains("429") {
        ErrorClassification {
            reason: FailoverReason::RateLimit,
            permanence: ErrorPermanence::Transient,
            status_code: None,
            message: Some(message.to_string()),
        }
    } else if msg_lower.contains("overloaded") || msg_lower.contains("529") {
        ErrorClassification {
            reason: FailoverReason::Overloaded,
            permanence: ErrorPermanence::Transient,
            status_code: None,
            message: Some(message.to_string()),
        }
    } else if msg_lower.contains("server error")
        || msg_lower.contains("500")
        || msg_lower.contains("502")
        || msg_lower.contains("503")
    {
        ErrorClassification {
            reason: FailoverReason::ServerError,
            permanence: ErrorPermanence::Transient,
            status_code: None,
            message: Some(message.to_string()),
        }
    } else if msg_lower.contains("model not found") || msg_lower.contains("404") {
        ErrorClassification {
            reason: FailoverReason::ModelNotFound,
            permanence: ErrorPermanence::Permanent,
            status_code: None,
            message: Some(message.to_string()),
        }
    } else if msg_lower.contains("empty response") || msg_lower.contains("no content") {
        ErrorClassification {
            reason: FailoverReason::EmptyStream,
            permanence: ErrorPermanence::Transient,
            status_code: None,
            message: Some(message.to_string()),
        }
    } else {
        ErrorClassification {
            reason: FailoverReason::Unknown,
            permanence: ErrorPermanence::Transient,
            status_code: None,
            message: Some(message.to_string()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_auth_error() {
        let cls = classify_error(Some(401), "Unauthorized");
        assert_eq!(cls.reason, FailoverReason::Auth);
        assert_eq!(cls.permanence, ErrorPermanence::Permanent);
    }

    #[test]
    fn classify_rate_limit() {
        let cls = classify_error(Some(429), "Rate limited");
        assert_eq!(cls.reason, FailoverReason::RateLimit);
        assert_eq!(cls.permanence, ErrorPermanence::Transient);
    }

    #[test]
    fn classify_server_error() {
        let cls = classify_error(Some(503), "Service unavailable");
        assert_eq!(cls.reason, FailoverReason::ServerError);
        assert_eq!(cls.permanence, ErrorPermanence::Transient);
    }

    #[test]
    fn classify_overloaded() {
        let cls = classify_error(Some(529), "Overloaded");
        assert_eq!(cls.reason, FailoverReason::Overloaded);
        assert_eq!(cls.permanence, ErrorPermanence::Transient);
    }

    #[test]
    fn classify_model_not_found() {
        let cls = classify_error(Some(404), "Model not found");
        assert_eq!(cls.reason, FailoverReason::ModelNotFound);
        assert_eq!(cls.permanence, ErrorPermanence::Permanent);
    }

    #[test]
    fn classify_context_overflow_is_fatal() {
        let cls = classify_error(
            Some(400),
            "context_length_exceeded: reduce the length of the messages",
        );
        assert_eq!(cls.reason, FailoverReason::Format);
        assert_eq!(cls.permanence, ErrorPermanence::Fatal);
    }

    #[test]
    fn classify_connection_reset_by_message() {
        let cls = classify_error(None, "Connection reset by peer");
        assert_eq!(cls.reason, FailoverReason::ConnectionReset);
        assert_eq!(cls.permanence, ErrorPermanence::Transient);
    }

    #[test]
    fn classify_timeout_by_message() {
        let cls = classify_error(None, "Request timed out");
        assert_eq!(cls.reason, FailoverReason::Timeout);
        assert_eq!(cls.permanence, ErrorPermanence::Transient);
    }

    #[test]
    fn classify_empty_stream() {
        let cls = classify_error(None, "empty response from provider");
        assert_eq!(cls.reason, FailoverReason::EmptyStream);
        assert_eq!(cls.permanence, ErrorPermanence::Transient);
    }

    #[test]
    fn classify_unknown_error_defaults_transient() {
        let cls = classify_error(None, "something weird happened");
        assert_eq!(cls.reason, FailoverReason::Unknown);
        assert_eq!(cls.permanence, ErrorPermanence::Transient);
    }
}
