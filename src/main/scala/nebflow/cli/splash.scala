package nebflow.cli

import java.util.Properties

/** The installer and runtime share generated artwork from the official horizontal lockup. */
object Splash:
  final case class TerminalFacts(env: Map[String, String], interactive: Boolean, columns: Int = 80)

  private lazy val artwork: Properties =
    val stream = Option(getClass.getClassLoader.getResourceAsStream("brand/stand-terminal-banner.properties"))
      .getOrElse(throw new IllegalStateException("Missing Stand terminal artwork"))
    val properties = new Properties()
    try properties.load(stream)
    finally stream.close()
    properties

  private def payload(key: String): String = artwork.getProperty(key)

  private def setting(env: Map[String, String], key: String): Option[String] =
    env.get(s"STAND_$key").orElse(env.get(s"NEBFLOW_$key")).filter(_.nonEmpty)

  def currentFacts: TerminalFacts =
    val env = sys.env
    val (interactive, width) =
      try
        if sys.props.getOrElse("os.name", "").toLowerCase.contains("windows") then
          val handle = org.jline.nativ.Kernel32.GetStdHandle(org.jline.nativ.Kernel32.STD_OUTPUT_HANDLE)
          val info = new org.jline.nativ.Kernel32.CONSOLE_SCREEN_BUFFER_INFO()
          val console = org.jline.nativ.Kernel32.GetConsoleScreenBufferInfo(handle, info) != 0
          (console, if console then info.windowWidth() else 80)
        else (org.jline.nativ.CLibrary.isatty(1) == 1, org.jline.nativ.CLibrary.getTerminalWidth(1).toInt)
      catch case _: LinkageError | _: SecurityException => (ConfigCommand.hasInteractiveTerminal, 80)
    val columns = env
      .get("COLUMNS")
      .flatMap(_.toIntOption)
      .filter(_ > 0)
      .getOrElse(if width > 0 then width else 80)
    TerminalFacts(env, interactive, columns)

  end currentFacts

  def printBanner(): Unit =
    val output = render(currentFacts)
    if output.nonEmpty then Console.print(output)

  def render(facts: TerminalFacts): String =
    val env = facts.env
    if !facts.interactive || env.get("CI").exists(_.nonEmpty) then ""
    else renderInteractive(facts, env)
  end render

  private def renderInteractive(facts: TerminalFacts, env: Map[String, String]): String =
    val noColor = env.get("NO_COLOR").exists(_.nonEmpty)
    val term = env.getOrElse("TERM", "")
    val plain = noColor || term == "dumb"
    if facts.columns < payload("columns").toInt + 2 then "\n  Stand\n\n"
    else renderWide(env, plain, term)
  end renderInteractive

  private def renderWide(env: Map[String, String], plain: Boolean, term: String): String =
    val theme = setting(env, "BANNER_THEME").filter(Set("dark", "light")).getOrElse {
      if env.get("COLORFGBG").flatMap(_.split(';').lastOption).flatMap(_.toIntOption).exists(_ >= 7)
      then "light"
      else "dark"
    }
    val requested = setting(env, "BANNER_IMAGE").getOrElse("auto")
    val mode =
      if plain then "off"
      else
        requested match
          case "off" | "0" | "no" => "off"
          case "osc" | "iterm" => "osc"
          case "sixel" | "wt" => "sixel"
          case "auto" | "yes" | "1" | "on" =>
            if env.get("TERM_PROGRAM").exists(Set("iTerm.app", "WezTerm", "mintty")) ||
              env.get("KONSOLE_VERSION").flatMap(_.toIntOption).exists(_ >= 220401)
            then "osc"
            else if env.get("WT_SESSION").exists(_.nonEmpty) then "sixel"
            else "off"
          case _ => "off"
    mode match
      case "osc" =>
        s"\n\u001b]1337;File=name=c3RhbmQtbG9nby5wbmc=;inline=1;width=${payload("columns")};height=${payload("rows")}:${payload(s"png_$theme")}\u0007\n\n"
      case "sixel" => s"\n\u001bP0;1;0q${payload(s"sixel_$theme")}\u001b\\\n\n"
      case _ =>
        val level =
          if plain then 0
          else
            setting(env, "UI_LEVEL").flatMap(_.toIntOption).filter(n => n >= 0 && n <= 3).getOrElse {
              if env.get("COLORTERM").exists(Set("truecolor", "24bit")) then 3
              else if term.contains("256color") then 2
              else 1
            }
        val ink = payload(s"ink_$theme")
        val foreground = level match
          case 3 => s"\u001b[38;2;${ink}m"
          case 2 => if theme == "light" then "\u001b[38;5;16m" else "\u001b[38;5;231m"
          case 1 => if theme == "light" then "\u001b[30m" else "\u001b[97m"
          case _ => ""
        val reset = if level > 0 then "\u001b[0m" else ""
        val token = "([0-9]+)([.un#])".r
        val rows = payload("art").split(';').map { row =>
          val text = token
            .findAllMatchIn(row)
            .map { m =>
              val cell = m.group(2).head
              val glyph =
                if cell == '.' then " "
                else if level == 0 then "#"
                else
                  cell match
                    case 'u' => "\u2580"
                    case 'n' => "\u2584"
                    case _ => "\u2588"
              glyph * m.group(1).toInt
            }
            .mkString
          s"  $foreground$text$reset"
        }
        rows.mkString("\n", "\n", "\n\n")
    end match
  end renderWide
end Splash
