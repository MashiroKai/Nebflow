package nebflow.core.browser

import io.circe.Json
import io.circe.syntax.*
import java.util.concurrent.atomic.AtomicBoolean

/** Caller serializes this object's operations away from the CDP listener thread. */
private[nebflow] final class BrowserScreencast(send: (String, Json) => Unit):
  private val stopped = new AtomicBoolean(false)
  private val frames = new BrowserFrameBuffer(id => send("Page.screencastFrameAck", Json.obj("sessionId" -> id.asJson)))

  def start(): Unit =
    send("Page.startScreencast", Json.obj("format" -> "jpeg".asJson, "quality" -> 60.asJson,
      "maxWidth" -> 1280.asJson, "maxHeight" -> 1280.asJson, "everyNthFrame" -> 1.asJson))

  def input(params: Json): Unit =
    if !BrowserTakeoverInput.valid(params) then throw new IllegalArgumentException("invalid browser input")
    val kind = params.hcursor.get[String]("type").getOrElse("")
    val method = kind match
      case "insertText" => "Input.insertText"
      case "keyDown" | "keyUp" => "Input.dispatchKeyEvent"
      case _ => "Input.dispatchMouseEvent"
    val body = if kind == "insertText" then params.mapObject(_.remove("type")) else params
    send(method, body)

  def frame(params: Json): Unit =
    val c = params.hcursor
    for
      id <- c.get[Int]("sessionId").toOption
      data <- c.get[String]("data").toOption
    do
      val metadata = c.downField("metadata").focus.getOrElse(Json.obj())
      frames.receive(id, Json.obj("data" -> data.asJson, "metadata" -> metadata).noSpaces)

  def take(): Option[String] = frames.take()

  def close(): Unit =
    if stopped.compareAndSet(false, true) then send("Page.stopScreencast", Json.obj())
