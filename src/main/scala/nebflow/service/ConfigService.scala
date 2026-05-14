package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.{Json, JsonObject}

object ConfigService:
  private val configPath = os.home / ".nebflow" / "nebflow.json"

  def getConfig: IO[String] = IO.blocking {
    val content = if os.exists(configPath) then os.read(configPath) else "{}"
    // Redact sensitive fields before sending to client
    content.replaceAll(
      "(?i)\"(api[_-]?key|secret|app[_-]?secret|encrypt[_-]?key|token|password)\"\\s*:\\s*\"[^\"]*\"",
      "\"$1\":\"***\""
    )
  }

  def updateConfig(incoming: String): IO[Either[String, Unit]] = IO
    .blocking {
      val existing = if os.exists(configPath) then os.read(configPath) else "{}"
      val merged = mergeConfig(existing, incoming)
      os.write.over(configPath, merged, createFolders = true)
    }
    .attempt
    .map(_.leftMap(_.getMessage).void)

  private val sensitiveKeyPattern = "(?i)(api[_-]?key|secret|app[_-]?secret|encrypt[_-]?key|token|password)".r

  /**
   * Merge new config into existing, preserving secret values that were redacted as "***".
   * For each leaf string value: if the new value is "***", keep the existing value.
   * Also prevents empty string from overwriting an existing secret value.
   */
  private def mergeConfig(existing: String, incoming: String): String =
    def merge(existing: Json, incoming: Json, keyChain: List[String] = Nil): Json =
      (existing.asObject, incoming.asObject) match
        case (Some(eObj), Some(iObj)) =>
          val merged = iObj.toMap.map { case (key, iVal) =>
            val eVal = eObj(key).getOrElse(Json.Null)
            key -> merge(eVal, iVal, keyChain :+ key)
          }
          // Preserve keys from existing that are not in incoming
          val withExisting = eObj.toMap.filterNot { case (k, _) => merged.contains(k) }
          Json.fromFields(merged ++ withExisting)
        case _ =>
          val currentKey = keyChain.lastOption.getOrElse("")
          // Leaf value: if incoming is "***", keep existing
          incoming.asString match
            case Some(s) if s == "***" => existing
            case Some(s) if s.isEmpty && sensitiveKeyPattern.findFirstIn(currentKey).isDefined =>
              // Don't let empty string overwrite an existing secret
              existing
            case _ => incoming
    (parse(existing), parse(incoming)) match
      case (Right(eJson), Right(iJson)) => merge(eJson, iJson).spaces2
      case _ => incoming // fallback: write incoming as-is if parse fails
end ConfigService
