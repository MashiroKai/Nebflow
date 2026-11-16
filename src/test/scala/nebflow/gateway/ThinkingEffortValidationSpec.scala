package nebflow.gateway

import io.circe.parser.parse
import munit.FunSuite

class ThinkingEffortValidationSpec extends FunSuite:

  test("global thinking effort rejects unknown values") {
    val bad = parse("""{"enabled":true,"effort":"ultra"}""").toOption
    val result = WsConfigHandlers.decodeGlobalThinkingEffort(bad)
    assert(result.isLeft, result.toString)
    assert(result.swap.toOption.get.contains("Unknown thinking effort"))
  }
