package nebflow.cli

import cats.effect.{IO, Ref}
import nebflow.core.{ReplUi, UserAbort}
import nebflow.shared.TerminalUtils.*
import org.jline.reader.{LineReader, LineReaderBuilder}
import org.jline.terminal.{Terminal, TerminalBuilder}

import scala.concurrent.duration.*

class NebflowUI(store: UiStore):

  private val terminal: Terminal = TerminalBuilder
    .builder()
    .system(true)
    .build()

  private val reader: LineReader = LineReaderBuilder
    .builder()
    .terminal(terminal)
    .build()

  def runInteractive(modelRef: String, onInput: String => IO[Unit]): IO[Unit] =
    // Print splash
    Splash.lines.foreach(println)
    println(s"  model: $modelRef")
    println()

    // Main loop
    def loop: IO[Unit] =
      readInput.flatMap {
        case None => IO.unit // Exit
        case Some(input) =>
          if input == "quit" || input == "exit" then IO.unit
          else onInput(input) *> loop
      }

    loop.guarantee(IO(terminal.close()))

  def readInput: IO[Option[String]] = IO.blocking {
    try
      val prompt = s"$Cyan>$Reset "
      val line = reader.readLine(prompt)
      if line == null then None else Some(line.trim)
    catch
      case _: org.jline.reader.EndOfFileException => None
      case _: org.jline.reader.UserInterruptException => None
  }

  def printText(text: String): IO[Unit] = IO.blocking {
    println(text)
  }

  def printMarkdown(text: String): IO[Unit] = IO.blocking {
    println(MarkdownRenderer.render(text))
  }

  def printToolStart(label: String): IO[Unit] = IO.blocking {
    println(s"$Dim▸ $label...$Reset")
  }

  def printToolEnd(label: String, summary: String, isError: Boolean): IO[Unit] = IO.blocking {
    val icon = if isError then s"${Red}✗$Reset" else s"${Green}✓$Reset"
    println(s"$icon $label - $summary")
  }

  def printError(msg: String): IO[Unit] = IO.blocking {
    println(s"$Red$msg$Reset")
  }

  def printSystem(msg: String): IO[Unit] = IO.blocking {
    println(s"$Dim$msg$Reset")
  }

  def selectOption(question: String, options: List[String]): IO[Int] = IO.blocking {
    val out = terminal.writer()
    val in = terminal.reader()

    out.println()
    out.println(question)
    options.zipWithIndex.foreach { case (opt, i) =>
      if i == 0 then out.println(s"  > $opt")
      else out.println(s"    $opt")
    }
    out.flush()

    var selected = 0
    var done = false

    terminal.puts(org.jline.utils.InfoCmp.Capability.cursor_invisible)
    terminal.flush()

    def render() =
      out.print(s"\u001b[${options.length}A")
      options.zipWithIndex.foreach { case (opt, i) =>
        out.print("\u001b[G")
        out.print("\u001b[2K")
        if i == selected then out.print(s"> $opt")
        else out.print(s"  $opt")
        if i < options.length - 1 then out.print("\n")
      }
      out.flush()

    while !done do
      try
        val ch = in.read()
        ch match
          case 27 => // ESC sequence
            if in.read() == 91 then // '['
              in.read() match
                case 65 => // up
                  selected = if selected > 0 then selected - 1 else options.length - 1
                  render()
                case 66 => // down
                  selected = if selected < options.length - 1 then selected + 1 else 0
                  render()
                case _ => ()
          case 10 | 13 => // Enter
            done = true
          case _ => ()
      catch case _: Exception => done = true

    terminal.puts(org.jline.utils.InfoCmp.Capability.cursor_normal)
    out.print(s"\u001b[${options.length}B")
    out.println()
    out.flush()

    selected
  }

  def runSingleShot(query: String, onComplete: => IO[Unit]): IO[Unit] = onComplete

end NebflowUI

object NebflowUI:
  def apply(store: UiStore): NebflowUI = new NebflowUI(store)
