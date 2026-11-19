package nebflow.core.delegate

import io.circe.Json
import io.circe.parser.parse
import nebflow.core.AtomicJson
import nebflow.shared.PathUtil

import scala.util.Try

/** Durable parent edges for DAG sessions, whose AgentRegistry entries are ephemeral. */
object FlowNodeOwnership:
  final case class Edge(sessionId: String, parentSessionId: String)

  private def dir: os.Path = PathUtil.dataRoot / "flow-node-ownership"
  private def validId(id: String): Boolean = id.nonEmpty && id.length <= 128 && id.matches("^[a-zA-Z0-9._-]+$")
  private def fileOf(id: String): os.Path = dir / s"$id.json"

  /** Best-effort durable registration. Invalid IDs are ignored rather than creating unsafe paths. */
  def register(sessionId: String, parentSessionId: String): Unit =
    if validId(sessionId) && validId(parentSessionId) then
      AtomicJson.writeSync(fileOf(sessionId), Json.obj(
        "sessionId" -> io.circe.Json.fromString(sessionId),
        "parentSessionId" -> io.circe.Json.fromString(parentSessionId)
      ).noSpaces)

  /** Corrupt/unreadable edges degrade to absence; caller marks the observable aggregate partial. */
  def list(): List[Edge] =
    try
      if !os.exists(dir) then Nil
      else
        os.list(dir).filter(_.ext == "json").flatMap { path =>
          Try(parse(os.read(path)).toOption.flatMap { json =>
            for
              sessionId <- json.hcursor.get[String]("sessionId").toOption
              parentSessionId <- json.hcursor.get[String]("parentSessionId").toOption
              if validId(sessionId) && validId(parentSessionId)
            yield Edge(sessionId, parentSessionId)
          }).toOption.flatten
        }.toList
    catch case _: Throwable => Nil
end FlowNodeOwnership
