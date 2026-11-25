package nebflow.core.browser

import io.circe.Json

/** No arbitrary CDP commands; never log these parameters. */
private[nebflow] object BrowserTakeoverInput:
  def viewportPoint(x: Double, y: Double, width: Double, height: Double, viewportWidth: Double, viewportHeight: Double): (Double, Double) =
    (x * viewportWidth / width, y * viewportHeight / height)

  def valid(input: Json): Boolean =
    val c = input.hcursor
    val kind = c.get[String]("type").getOrElse("")
    val xy = List("x", "y").forall(k => c.get[Double](k).toOption.exists(v => v.isFinite && v >= 0 && v <= 4096))
    kind match
      case "mouseMoved" | "mousePressed" | "mouseReleased" => xy
      case "mouseWheel" => xy && List("deltaX", "deltaY").forall(k => c.get[Double](k).toOption.exists(v => v.isFinite && math.abs(v) <= 4096))
      case "keyDown" | "keyUp" => c.get[String]("key").toOption.exists(_.length <= 64)
      case "insertText" => c.get[String]("text").toOption.exists(_.length <= 4096)
      case _ => false
