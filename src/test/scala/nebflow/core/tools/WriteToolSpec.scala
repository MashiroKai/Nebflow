package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite

class WriteToolSpec extends CatsEffectSuite:

  private val ctx = ToolContext(projectRoot = "/tmp")

  test("#6: null content returns an error instead of silently writing 0 bytes") {
    // content key absent — previously fell back to "" and wrote an empty file.
    val input = JsonObject("file_path" -> "/tmp/nb-write-should-not-exist.txt".asJson)
    WriteTool.call(input, ctx).map {
      case Left(err) =>
        assert(err.message.contains("content"), s"should mention content: ${err.message}")
      case Right(_) => fail("must not succeed when content is missing/non-string")
    }
  }

  test("#6: numeric content returns an error instead of writing 0 bytes") {
    // content provided as a JSON number — asString returns None.
    val input = JsonObject(
      "file_path" -> "/tmp/nb-write-should-not-exist.txt".asJson,
      "content" -> Json.fromInt(123)
    )
    WriteTool.call(input, ctx).map {
      case Left(err) =>
        assert(err.message.contains("content"), s"should mention content: ${err.message}")
      case Right(_) => fail("must not succeed when content is a number")
    }
  }

  test("#6: object content returns an error instead of writing 0 bytes") {
    // content provided as a JSON object — asString returns None.
    val input = JsonObject(
      "file_path" -> "/tmp/nb-write-should-not-exist.txt".asJson,
      "content" -> Json.obj("k" -> "v".asJson)
    )
    WriteTool.call(input, ctx).map {
      case Left(err) =>
        assert(err.message.contains("content"), s"should mention content: ${err.message}")
      case Right(_) => fail("must not succeed when content is an object")
    }
  }

  test("#6: path validation still takes precedence in shape (absolute check)") {
    // non-absolute path with valid string content should still report path error.
    val input = JsonObject(
      "file_path" -> "relative/path.txt".asJson,
      "content" -> "hello".asJson
    )
    WriteTool.call(input, ctx).map {
      case Left(err) => assert(err.message.contains("absolute"), s"should mention absolute: ${err.message}")
      case Right(_) => fail("should reject relative path")
    }
  }
end WriteToolSpec
