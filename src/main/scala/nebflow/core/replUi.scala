package nebflow.core

import cats.effect.IO

/** REPL 与 UI 层的桥接接口 */
trait ReplUi:
  def emitThinking(): IO[Unit]
  def emitInterrupted(): IO[Unit]
  def emitTextDelta(text: String): IO[Unit]
  def emitTextDone(): IO[Unit]
  def emitToolStart(label: String): IO[Unit]
  def emitToolEnd(label: String, summary: String, content: String, isError: Boolean): IO[Unit]
  def emitMaxTokens(): IO[Unit]
  def emitTimeout(): IO[Unit]
  def onEscInterrupt(action: IO[Unit]): IO[Unit]
  def removeEscListener(): IO[Unit]
