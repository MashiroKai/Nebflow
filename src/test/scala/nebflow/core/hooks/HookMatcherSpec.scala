package nebflow.core.hooks

import munit.FunSuite

class HookMatcherSpec extends FunSuite:

  test("exact match: only identical name matches") {
    assert(HookMatcher.matches("Edit", "Edit"))
    assert(!HookMatcher.matches("Edit", "Editor"))
    assert(!HookMatcher.matches("Edit", "edit")) // exact = case-sensitive
    assert(!HookMatcher.matches("Edit", "Write"))
  }

  test("star matches everything") {
    assert(HookMatcher.matches("*", "Edit"))
    assert(HookMatcher.matches("*", ""))
    assert(HookMatcher.matches(" * ", "Bash")) // trimmed
  }

  test("pipe-separated alternation with whitespace tolerance") {
    assert(HookMatcher.matches("Edit|Write", "Edit"))
    assert(HookMatcher.matches("Edit|Write", "Write"))
    assert(HookMatcher.matches("Edit | Write | Bash", "Bash"))
    assert(!HookMatcher.matches("Edit|Write", "Read"))
  }

  test("regex heuristic: ^...$, .* and \\d patterns match by substring search") {
    assert(HookMatcher.matches("^Edit$", "Edit"))
    assert(!HookMatcher.matches("^Edit$", "Edit2")) // ^$ anchors via findFirstIn? ^Edit$ must span whole string
    assert(HookMatcher.matches("mcp__.*", "mcp__github__search"))
    assert(!HookMatcher.matches("mcp__.*", "Bash"))
    assert(HookMatcher.matches("Log\\d+", "Log42"))
  }

  test("non-regex, non-pipe pattern without special chars is exact") {
    // a plain word containing no regex marker chars must not fall into regex
    assert(HookMatcher.matches("Bash", "Bash"))
    assert(!HookMatcher.matches("Bash", "BashScript"))
  }

end HookMatcherSpec
