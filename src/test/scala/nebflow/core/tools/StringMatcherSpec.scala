package nebflow.core.tools

import munit.FunSuite

class StringMatcherSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // Step 1: exact match
  // ---------------------------------------------------------------------------

  test("exact match returns the search string itself") {
    val content = "module foo (\n  input clk,\n  output reg led\n);"
    val search  = "input clk"
    val result  = StringMatcher.findActualString(content, search)
    assertEquals(result, Some(search))
  }

  test("exact match fails when search not in content") {
    val content = "module foo;"
    val search  = "nonexistent"
    assertEquals(StringMatcher.findActualString(content, search), None)
  }

  // ---------------------------------------------------------------------------
  // Step 2: quote-normalized match (curly → straight)
  // ---------------------------------------------------------------------------

  test("match curly quotes via normalization") {
    val content = "assign msg = \u201Chello\u201D;"
    val search  = "assign msg = \"hello\";"
    val result  = StringMatcher.findActualString(content, search)
    assert(result.isDefined, "Should find curly-quoted string with straight-quote search")
    // Must return span from the ORIGINAL content (curly quotes preserved)
    assertEquals(result.get, content)
  }

  // ---------------------------------------------------------------------------
  // Step 3: whitespace-insensitive match (tabs ↔ spaces)
  // ---------------------------------------------------------------------------

  test("match tab-indented file with space-indented search") {
    val content = "\t\tmodule foo (\n\t\t  input clk,\n\t\t  output reg led\n\t\t);"
    val search  = "  module foo (\n    input clk,\n    output reg led\n  );"
    val result  = StringMatcher.findActualString(content, search)
    assert(result.isDefined, "Should match tab-indented content with space-indented search")
    // Must return the original tab-indented version from the file
    assertEquals(result.get, content)
  }

  test("match space-indented file with tab-indented search") {
    val content = "  module bar (\n    input rst,\n    output reg out\n  );"
    val search  = "\tmodule bar (\n\t\tinput rst,\n\t\toutput reg out\n\t);"
    val result  = StringMatcher.findActualString(content, search)
    assert(result.isDefined, "Should match space-indented content with tab-indented search")
    assertEquals(result.get, content)
  }

  test("match mixed tabs+spaces in content against plain-space search") {
    // Realistic: file has mixed \t + space indentation, LLM copies with spaces
    val content = "\t  wire [7:0]  \tdata;"
    val search  = "  wire [7:0]  data;"
    val result  = StringMatcher.findActualString(content, search)
    assert(result.isDefined, "Should match content with mixed tabs and spaces")
    assertEquals(result.get, content)
  }

  test("whitespace match returns correct span from original content") {
    // 3-level tab indentation in file, 2/4/2 space indentation in LLM search
    val content = "\t\talways @(posedge clk) begin\n\t\t\tcnt <= cnt + 1;\n\t\tend"
    val search  = "  always @(posedge clk) begin\n    cnt <= cnt + 1;\n  end"
    val result  = StringMatcher.findActualString(content, search)
    assert(result.isDefined)
    // Must return the TAB-indented version (from the file)
    assertEquals(result.get, content)
    // Must NOT return the space-indented version (the search string)
    assertNotEquals(result.get, search)
  }

  test("whitespace match fails when non-whitespace content differs") {
    val content = "\t\tmodule foo (\n\t\t  input clk\n\t\t);"
    val search  = "  module bar (\n    input clk\n  );"
    assertEquals(StringMatcher.findActualString(content, search), None)
  }

  test("whitespace match preserves non-whitespace structure") {
    // Both sides use similar whitespace structure (space before each item)
    val content = "\twire [31:0] counter;\n\treg  flag;"
    val search  = " wire [31:0] counter;\n reg  flag;"
    val result  = StringMatcher.findActualString(content, search)
    assert(result.isDefined)
    assertEquals(result.get, content)
  }

  // ---------------------------------------------------------------------------
  // Step 4: quote + whitespace normalization combined
  // ---------------------------------------------------------------------------

  test("match when both quotes and whitespace differ") {
    val content = "\t\tassign msg = \u201Cdone\u201D;"
    val search  = "  assign msg = \"done\";"
    val result  = StringMatcher.findActualString(content, search)
    assert(result.isDefined, "Should match with both tab→space and curly→straight normalization")
    // Must return span with original tabs AND curly quotes from the file
    assertEquals(result.get, content)
  }

  // ---------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------

  test("empty content") {
    assertEquals(StringMatcher.findActualString("", "module"), None)
  }

  test("search matches entire content with whitespace difference") {
    val content = "\t\tmodule top;\n\t\tendmodule"
    val search  = "  module top;\n  endmodule"
    val result  = StringMatcher.findActualString(content, search)
    assert(result.isDefined)
    assertEquals(result.get, content)
  }

  test("no false positive on normalized substring") {
    // "a b" should NOT match "ab" — the whitespace normalization
    // collapses runs but doesn't REMOVE whitespace entirely.
    val content = "always_comb begin\n  a = b;\nend"
    val search  = "always_comb begin\n a=b;\nend"
    // "a = b" normalizes to "a = b" (3 tokens), "a=b" normalizes to "a=b" (1 token).
    assertEquals(StringMatcher.findActualString(content, search), None)
  }

  test("match partial snippet within larger file") {
    // Realistic: LLM copies a function body from a large file
    val content = "// Top module\nmodule top (\n\tinput clk,\n\tinput rst,\n\toutput reg led\n);\nendmodule"
    val search  = "module top (\n  input clk,\n  input rst,\n  output reg led\n);"
    val result  = StringMatcher.findActualString(content, search)
    assert(result.isDefined)
    assertEquals(result.get, "module top (\n\tinput clk,\n\tinput rst,\n\toutput reg led\n);")
  }

end StringMatcherSpec
