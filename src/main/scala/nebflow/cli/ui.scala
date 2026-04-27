package nebflow.cli

import nebflow.core.{ReplUi, UserAbort}
import nebflow.shared.TerminalUtils.*
import cats.effect.IO
import cats.effect.Ref
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.reader.{LineReader, LineReaderBuilder}
import scala.concurrent.duration.*

class NebflowUI(store: UiStore):
  private val terminal: Terminal = TerminalBuilder.builder()
    .system(true)
    .build()
  private val reader: LineReader = LineReaderBuilder.builder()
    .terminal(terminal)
    .build()

  private val spinnerFrames = List("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
  @volatile private var spinnerRunning = false
  @volatile private var spinnerIndex = 0

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
          if input == "quit" || input == "exit" then
            IO.unit
          else
            onInput(input) *> loop
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

  def startSpinner(label: String): IO[Unit] = IO {
    spinnerRunning = true
    spinnerIndex = 0
    val thread = new Thread(() => {
      while spinnerRunning do
        print(s"\r$Cyan${spinnerFrames(spinnerIndex)}$Reset $label")
        System.out.flush()
        spinnerIndex = (spinnerIndex + 1) % spinnerFrames.length
        Thread.sleep(80)
      print("\r" + " " * (label.length + 10) + "\r")
      System.out.flush()
    })
    thread.setDaemon(true)
    thread.start()
  }

  def stopSpinner: IO[Unit] = IO.delay { spinnerRunning = false }

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

  def runSingleShot(query: String, onComplete: => IO[Unit]): IO[Unit] = onComplete

object NebflowUI:
  def create: IO[NebflowUI] =
    UiStore.create.map(store => new NebflowUI(store))
