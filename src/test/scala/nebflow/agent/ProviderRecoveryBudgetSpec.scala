package nebflow.agent

import nebflow.shared.Defaults

class ProviderRecoveryBudgetSpec extends munit.FunSuite:
  test("configuration, quota, format, and unknown errors need manual recovery") {
    List("HTTP 401 invalid api key", "HTTP 404 model_not_found",
      "insufficient_quota", "HTTP 400 invalid request", "unrecognized failure").foreach { detail =>
      assert(!FreezeAlertSweep.automaticRecoveryAllowed(detail, 0), detail)
    }
  }
  test("temporary errors stop at the automatic budget") {
    val detail = "HTTP 503 service unavailable"
    assert(FreezeAlertSweep.automaticRecoveryAllowed(detail, 0))
    assert(!FreezeAlertSweep.automaticRecoveryAllowed(detail, Defaults.DelegateFreezeAutoResumeMax))
    assert(!FreezeAlertSweep.automaticRecoveryAllowed(detail, Defaults.DelegateFreezeAutoResumeMax + 1))
  }
