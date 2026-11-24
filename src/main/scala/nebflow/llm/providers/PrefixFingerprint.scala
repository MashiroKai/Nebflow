package nebflow.llm.providers

import io.circe.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Diagnostic fingerprint of the fixed Anthropic wire core, not the provider cache key. */
object PrefixFingerprint:
  final case class Segment(name: String, layer: String, value: String)
  final case class Difference(segment: String, layer: String, byteOffset: Int, before: String, after: String)

  def anthropicCore(body: Json): Vector[Segment] =
    val tools = body.hcursor.downField("tools").focus.getOrElse(Json.arr()).noSpaces
    val system = body.hcursor.downField("system").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    val stable = system
      .find(_.hcursor.downField("cache_control").focus.isDefined)
      .flatMap(_.hcursor.get[String]("text").toOption)
      .getOrElse("")
    Vector(Segment("tools", "tool definitions", tools), Segment("system", "assembled system injection", stable))

  def fingerprint(segments: Vector[Segment]): Map[String, String] =
    segments
      .map(s =>
        s.name -> MessageDigest
          .getInstance("SHA-256")
          .digest(s.value.getBytes(UTF_8))
          .map(b => f"${b & 0xff}%02x")
          .mkString
      )
      .toMap

  def firstDifference(a: Vector[Segment], b: Vector[Segment]): Option[Difference] =
    (0 until math.max(a.size, b.size)).iterator
      .flatMap { i =>
        val x = a.lift(i).getOrElse(Segment("<missing>", "<missing>", ""))
        val y = b.lift(i).getOrElse(Segment("<missing>", "<missing>", ""))
        val xb = x.value.getBytes(UTF_8); val yb = y.value.getBytes(UTF_8)
        val common = math.min(xb.length, yb.length)
        val offset = (0 until common).find(j => xb(j) != yb(j)).getOrElse(common)
        if x.name != y.name || x.layer != y.layer || offset < xb.length || offset < yb.length then
          def context(bytes: Array[Byte]): String =
            new String(bytes.slice(math.max(0, offset - 16), math.min(bytes.length, offset + 16)), UTF_8)
          Some(Difference(x.name, x.layer, offset, context(xb), context(yb)))
        else None
      }
      .take(1)
      .toList
      .headOption
end PrefixFingerprint
