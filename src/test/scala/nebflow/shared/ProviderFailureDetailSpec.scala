package nebflow.shared

class ProviderFailureDetailSpec extends munit.FunSuite:
  test("diagnostics preserve reason, status, and request reference") {
    val message = "HTTP 403 permission denied for model x; request_id=req-123"
    assertEquals(ProviderFailureDetail.sanitize(message), message)
  }
  test("credentials are removed before truncation") {
    val secret = "x" * 500
    val message = s"api_key=$secret permission denied"
    val safe = ProviderFailureDetail.sanitize(message)
    assertEquals(safe, "api_key=[redacted] permission denied")
  }
  test("JSON, headers, query credentials and URL passwords are redacted") {
    val cases = List(
      "{\"api_key\":\"private-key\",\"message\":\"denied\"}",
      "Authorization: Bearer private-key denied",
      "https://host/path?access_token=private-key&model=x",
      "https://user:private-key@host/path",
      "failed with sk-private-key"
    )
    cases.foreach { input =>
      val safe = ProviderFailureDetail.sanitize(input)
      assert(!safe.contains("private-key"), safe)
      assert(safe.contains("[redacted]"), safe)
    }
  }
  test("fallback failure retains sanitized upstream detail") {
    val attempt = FallbackAttempt("p", "m", Some(FailoverReason.Auth),
      Some(ErrorPermanence.Permanent), 1L, 0, "now",
      Some("permission denied api_key=private-key request_id=req-1"))
    val message = new FallbackExhaustedError(List(attempt)).getMessage
    assert(message.contains("permission denied"))
    assert(message.contains("request_id=req-1"))
    assert(!message.contains("private-key"))
  }
