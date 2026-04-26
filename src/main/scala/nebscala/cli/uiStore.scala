package nebscala.cli

import nebscala.core.ReplUi
import cats.effect.IO
import cats.effect.Deferred
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.parser

enum Phase:
  case Prompt, Thinking, Streaming, ToolRunning, AskUser

case class CompletedRound(
  id: Int,
  content: String,
  kind: String, // user, llm, tool-error, tool-success, system
  detail: Option[String] = None,
  detailType: Option[String] = None,
  color: String = "",
  token: Option[String] = None
)

case class UiState(
  phase: Phase,
  streamText: String,
  toolLabel: String,
  askUserItems: Option[List[nebscala.core.AskItem]] = None,
  completedRounds: List[CompletedRound] = Nil,
  inputHistory: List[String] = Nil,
  currentInput: String = "",
  historyIndex: Int = -1
)

class UiStore(stateRef: Ref[IO, UiState]) extends ReplUi:
  @volatile private var escHandler: Option[IO[Unit]] = None
  @volatile private var askUserPromise: Option[Deferred[IO, List[String]]] = None

  private val roundCounter = new java.util.concurrent.atomic.AtomicInteger(0)

  // === ReplUi implementation ===
  def emitThinking(): IO[Unit] =
    stateRef.update(_.copy(phase = Phase.Thinking, streamText = ""))

  def emitInterrupted(): IO[Unit] =
    stateRef.update(_.copy(phase = Phase.Prompt, streamText = ""))

  def emitTextDelta(text: String): IO[Unit] =
    stateRef.update(s => s.copy(phase = Phase.Streaming, streamText = s.streamText + text))

  def emitTextDone(): IO[Unit] =
    stateRef.get.flatMap { s =>
      val round = CompletedRound(
        roundCounter.incrementAndGet(),
        s.streamText,
        "llm"
      )
      stateRef.update(_.copy(
        phase = Phase.Prompt,
        streamText = "",
        completedRounds = s.completedRounds :+ round
      ))
    }

  def emitToolStart(label: String): IO[Unit] =
    stateRef.update(_.copy(phase = Phase.ToolRunning, toolLabel = label))

  def emitToolEnd(label: String, summary: String, content: String, isError: Boolean): IO[Unit] =
    stateRef.get.flatMap { s =>
      val round = CompletedRound(
        roundCounter.incrementAndGet(),
        s"$label - $summary",
        if isError then "tool-error" else "tool-success",
        detail = Some(content),
        detailType = Some(if content.contains("@@") then "diff" else "text"),
        token = Some(generateToken)
      )
      stateRef.update(_.copy(
        phase = Phase.Prompt,
        toolLabel = "",
        completedRounds = s.completedRounds :+ round
      ))
    }

  def emitMaxTokens(): IO[Unit] = IO.unit
  def emitTimeout(): IO[Unit] = IO.unit

  def onEscInterrupt(action: IO[Unit]): IO[Unit] =
    IO.delay { escHandler = Some(action) }

  def removeEscListener(): IO[Unit] =
    IO.delay { escHandler = None }

  // === Public API ===
  def getState: IO[UiState] = stateRef.get

  def addUserInput(text: String): IO[Unit] =
    stateRef.get.flatMap { s =>
      val round = CompletedRound(
        roundCounter.incrementAndGet(),
        text,
        "user"
      )
      stateRef.update(_.copy(
        completedRounds = s.completedRounds :+ round,
        inputHistory = s.inputHistory :+ text
      ))
    }

  def addSystemMessage(text: String): IO[Unit] =
    stateRef.get.flatMap { s =>
      val round = CompletedRound(
        roundCounter.incrementAndGet(),
        text,
        "system"
      )
      stateRef.update(_.copy(completedRounds = s.completedRounds :+ round))
    }

  def setCurrentInput(text: String): IO[Unit] =
    stateRef.update(_.copy(currentInput = text))

  def historyUp(): IO[Option[String]] =
    stateRef.get.flatMap { s =>
      if s.inputHistory.isEmpty then IO.pure(None)
      else
        val newIndex = if s.historyIndex < 0 then s.inputHistory.length - 1 else Math.max(0, s.historyIndex - 1)
        stateRef.update(_.copy(historyIndex = newIndex, currentInput = s.inputHistory(newIndex)))
          .as(Some(s.inputHistory(newIndex)))
    }

  def historyDown(): IO[Option[String]] =
    stateRef.get.flatMap { s =>
      if s.historyIndex < 0 then IO.pure(None)
      else
        val newIndex = s.historyIndex + 1
        if newIndex >= s.inputHistory.length then
          stateRef.update(_.copy(historyIndex = -1, currentInput = "")).as(None)
        else
          stateRef.update(_.copy(historyIndex = newIndex, currentInput = s.inputHistory(newIndex)))
            .as(Some(s.inputHistory(newIndex)))
    }

  def triggerEsc(): IO[Unit] =
    escHandler match
      case Some(h) => h
      case None => IO.unit

  def askUser(items: List[nebscala.core.AskItem]): IO[List[String]] =
    Deferred[IO, List[String]].flatMap { deferred =>
      IO.delay { askUserPromise = Some(deferred) } *>
      stateRef.update(_.copy(phase = Phase.AskUser, askUserItems = Some(items))) *>
      deferred.get
    }

  def answerAskUser(answers: List[String]): IO[Unit] =
    IO.delay { askUserPromise = None } *>
    stateRef.update(_.copy(phase = Phase.Prompt, askUserItems = None)) *>
    askUserPromise.traverse_(_.complete(answers))

  def reset(): IO[Unit] =
    stateRef.update(_.copy(
      phase = Phase.Prompt,
      streamText = "",
      toolLabel = "",
      askUserItems = None
    ))

  private def generateToken: String =
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    (1 to 4).map(_ => chars(scala.util.Random.nextInt(chars.length))).mkString

object UiStore:
  def create: IO[UiStore] =
    Ref[IO].of(UiState(
      phase = Phase.Prompt,
      streamText = "",
      toolLabel = "",
      inputHistory = loadHistory
    )).map(new UiStore(_))

  private def historyPath: os.Path = os.home / ".nebscala" / "input_history.json"

  private def loadHistory: List[String] =
    try
      if os.exists(historyPath) then
        io.circe.parser.decode[List[String]](os.read(historyPath)).getOrElse(Nil)
      else Nil
    catch case _: Exception => Nil

  def saveHistory(history: List[String]): IO[Unit] = IO.blocking {
    try
      os.write.over(historyPath, history.asJson.spaces2, createFolders = true)
    catch case _: Exception => ()
  }
