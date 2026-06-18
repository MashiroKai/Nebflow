package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import io.circe.JsonObject
import io.circe.syntax.*

class AcademicSearchSpec extends CatsEffectSuite:

  private def search(query: String, engine: String) =
    val input = JsonObject("query" -> query.asJson, "engine" -> engine.asJson)
    WebSearchTool.call(input, ToolContext(projectRoot = "/tmp"))

  test("arXiv: search CdZnTe pixel detector") {
    search("CdZnTe pixel detector 3D position", "arXiv").map {
      case Right(result) =>
        assert(result.contains("arXiv"), s"should mention arXiv")
        assert(result.contains("**"), s"should have bold titles: ${result.take(200)}")
        assert(result.contains("http"), s"should have URLs")
        println(s"\n=== arXiv result preview ===\n${result.take(500)}")
      case Left(err) => fail(s"arXiv search failed: ${err.message}")
    }
  }

  test("Semantic Scholar: search HEXITEC ASIC") {
    search("HEXITEC ASIC CdZnTe spectroscopy imaging", "Semantic Scholar").map {
      case Right(result) =>
        assert(result.contains("**"), s"should have titles: ${result.take(200)}")
        println(s"\n=== Semantic Scholar result preview ===\n${result.take(500)}")
      case Left(err) => fail(s"Semantic Scholar failed: ${err.message}")
    }
  }

  test("Crossref: search NuSTAR CdZnTe") {
    search("NuSTAR CdZnTe detector X-ray", "Crossref").map {
      case Right(result) =>
        assert(result.contains("**"), s"should have titles: ${result.take(200)}")
        assert(result.contains("doi.org"), s"should have DOI links")
        println(s"\n=== Crossref result preview ===\n${result.take(500)}")
      case Left(err) => fail(s"Crossref failed: ${err.message}")
    }
  }

  test("general search still works (no academic engine)") {
    search("react hooks tutorial", "Bing INT").map {
      case Right(result) =>
        assert(result.nonEmpty, "general search should return results")
      case Left(err) => fail(s"General search failed: ${err.message}")
    }
  }
end AcademicSearchSpec
