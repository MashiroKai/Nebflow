package nebflow.cli

import java.util.Properties
import munit.FunSuite

class SplashSpec extends FunSuite:
  private val base = Map("TERM" -> "xterm-256color", "STAND_BANNER_IMAGE" -> "off")

  private def render(env: Map[String, String] = base, columns: Int = 80): String =
    Splash.render(Splash.TerminalFacts(env, interactive = true, columns = columns))

  private val payloads =
    val result = new Properties()
    val stream = getClass.getClassLoader.getResourceAsStream("brand/stand-terminal-banner.properties")
    assert(stream != null)
    try result.load(stream)
    finally stream.close()
    result

  private def stripAnsi(value: String): String = value.replaceAll("\u001b\\[[0-9;]*m", "")

  test("ordinary terminals render the exact installer horizontal lockup") {
    val output = stripAnsi(render()).stripPrefix("\n").stripSuffix("\n\n")
    val rows = output.split("\n").toList
    val token = "([0-9]+)([.un#])".r
    val expected = payloads
      .getProperty("art")
      .split(';')
      .map { row =>
        "  " + token
          .findAllMatchIn(row)
          .map { m =>
            val glyph = m.group(2) match
              case "." => " "
              case "u" => "\u2580"
              case "n" => "\u2584"
              case _ => "\u2588"
            glyph * m.group(1).toInt
          }
          .mkString
      }
      .toList
    assertEquals(rows, expected)
    assertEquals(rows.size, 9)
    assert(rows.forall(_.length == 74))
    assert(!output.contains("One entry"))
  }

  test("dark and light themes use the installer inks") {
    val dark = render(base ++ Map("COLORTERM" -> "truecolor"))
    val light = render(base ++ Map("COLORTERM" -> "truecolor", "STAND_BANNER_THEME" -> "light"))
    assert(dark.contains("\u001b[38;2;255;255;255m"))
    assert(light.contains("\u001b[38;2;0;0;0m"))
    assertEquals(stripAnsi(dark), stripAnsi(light))
    assertEquals(render(base ++ Map("COLORFGBG" -> "0;15")), render(base ++ Map("STAND_BANNER_THEME" -> "light")))
  }

  test("iTerm uses the official PNG and dimensions") {
    val env = Map("TERM" -> "xterm-256color", "TERM_PROGRAM" -> "iTerm.app")
    val output = render(env)
    assert(output.startsWith("\n\u001b]1337;File="))
    assert(output.contains("width=72;height=9:" + payloads.getProperty("png_dark")))
    assert(output.endsWith("\u0007\n\n"))
  }

  test("Windows Terminal uses the official SIXEL payload") {
    val output = render(Map("TERM" -> "xterm", "WT_SESSION" -> "test"))
    assertEquals(output, "\n\u001bP0;1;0q" + payloads.getProperty("sixel_dark") + "\u001b\\\n\n")
  }

  test("native Windows shells do not need TERM to select images") {
    assertEquals(render(Map("WT_SESSION" -> "test")), render(Map("TERM" -> "xterm", "WT_SESSION" -> "test")))
    assertEquals(render(Map("STAND_BANNER_IMAGE" -> "sixel")), render(Map("WT_SESSION" -> "test")))
    assertEquals(render(Map("WT_SESSION" -> "test"), columns = 60), "\n  Stand\n\n")
  }

  test("NO_COLOR and dumb terminals never emit escapes, even with forced images") {
    for env <- List(
        base ++ Map("NO_COLOR" -> "1", "STAND_BANNER_IMAGE" -> "osc"),
        base ++ Map("TERM" -> "dumb", "STAND_BANNER_IMAGE" -> "sixel")
      )
    do
      val output = render(env)
      assert(!output.contains('\u001b'))
      assert(output.contains('#'))
  }

  test("redirected output and CI remain silent, even with image overrides") {
    assertEquals(Splash.render(Splash.TerminalFacts(base, interactive = false)), "")
    assertEquals(render(base ++ Map("CI" -> "1", "STAND_BANNER_IMAGE" -> "osc")), "")
  }

  test("narrow terminals use a text name instead of another logo") {
    assertEquals(render(columns = 73), "\n  Stand\n\n")
    assertEquals(stripAnsi(render(columns = 74)).split("\n").count(_.length == 74), 9)
  }

  test("legacy environment aliases render identical official artwork") {
    val canonical = render(base ++ Map("STAND_BANNER_THEME" -> "light", "STAND_UI_LEVEL" -> "3"))
    val legacy = render(
      Map(
        "TERM" -> "xterm-256color",
        "NEBFLOW_BANNER_IMAGE" -> "off",
        "NEBFLOW_BANNER_THEME" -> "light",
        "NEBFLOW_UI_LEVEL" -> "3"
      )
    )
    assertEquals(canonical, legacy)
  }

  test("canonical settings win and old variants cannot restore the S") {
    assertEquals(render(base ++ Map("NEBFLOW_BANNER_IMAGE" -> "osc")), render())
    for variant <- List("A", "B", "C") do assertEquals(render(base ++ Map("STAND_BANNER" -> variant)), render())
    assertEquals(render(base ++ Map("STAND_BANNER_IMAGE" -> "invalid")), render())
  }
end SplashSpec
