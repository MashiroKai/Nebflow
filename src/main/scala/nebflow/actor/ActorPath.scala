package nebflow.actor

/**
 * Address of an actor in the Nebflow actor system.
 *
 * Format: nebflow://device/segment1/segment2/...
 *
 * Examples:
 *   nebflow://macbook/Nebula/abc-123
 *   nebflow://pi-livingroom/SensorAgent/env
 */
final case class ActorPath(device: String, segments: List[String]):

  override def toString: String =
    if segments.isEmpty then s"nebflow://$device"
    else s"nebflow://$device/${segments.mkString("/")}"

  def name: String = segments.lastOption.getOrElse("")
  def parent: ActorPath = copy(segments = segments.init)
  def /(child: String): ActorPath = copy(segments = segments :+ child)
  def isLocal(localDevice: String): Boolean = device == localDevice

object ActorPath:

  def apply(device: String, path: String): ActorPath =
    ActorPath(device, path.split("/").filter(_.nonEmpty).toList)

  def parse(raw: String): Either[String, ActorPath] =
    val stripped = raw.stripPrefix("nebflow://")
    stripped.indexOf('/') match
      case -1 => Right(ActorPath(stripped, List.empty))
      case idx =>
        val device = stripped.substring(0, idx)
        val rest = stripped.substring(idx + 1)
        Right(ActorPath(device, rest.split("/").filter(_.nonEmpty).toList))
