package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.{Json, JsonObject}
import nebflow.llm.Config

object ConfigService:
  private val configPath = os.home / ".nebflow" / "nebflow.json"

  def isConfigured: IO[Boolean] = IO.blocking {
    if !os.exists(configPath) then false
    else
      val content = os.read(configPath).trim
      if content.isEmpty || content == "{}" then false
      else
        parse(content).toOption.exists { json =>
          json.hcursor.downField("llm").downField("providers").as[Map[String, Json]].toOption.exists(_.nonEmpty)
        }
  }

  def getConfig: IO[String] = IO.blocking {
    if os.exists(configPath) then os.read(configPath) else "{}"
  }

  /** Validate config JSON — returns list of errors. Empty list means valid. */
  def validateConfig(jsonStr: String): List[String] =
    parse(jsonStr) match
      case Left(err) => List(s"Invalid JSON: ${err.message}")
      case Right(json) =>
        validateConfigJson(json)
  end validateConfig

  private def validateConfigJson(json: Json): List[String] =
    val errors = scala.collection.mutable.ListBuffer.empty[String]
    val llmHc = json.hcursor.downField("llm")
    val providersResult = llmHc.downField("providers").as[Map[String, Json]]
    val modelResult = llmHc.downField("model").as[Json]

    // Check providers
    providersResult.toOption.foreach { providers =>
      providers.foreach { case (name, pJson) =>
        val pc = pJson.hcursor
        val baseUrl = pc.downField("baseUrl").as[Option[String]].toOption.flatten.getOrElse("")
        val protocol = pc.downField("protocol").as[Option[String]].toOption.flatten.getOrElse("")
        val models = pc.downField("models").as[Option[List[Json]]].toOption.flatten.getOrElse(Nil)
        if baseUrl.trim.isEmpty then errors += s"Provider '$name': Base URL is required"
        if protocol.isEmpty then errors += s"Provider '$name': Protocol is required"
        else if protocol != "anthropic" && protocol != "openai" then
          errors += s"Provider '$name': Unknown protocol '$protocol', must be 'anthropic' or 'openai'"
        if models.isEmpty then errors += s"Provider '$name': At least one model is required"
        else
          models.zipWithIndex.foreach { case (mJson, idx) =>
            val mid = mJson.hcursor.downField("id").as[Option[String]].toOption.flatten.getOrElse("")
            if mid.trim.isEmpty then errors += s"Provider '$name': Model #${idx + 1} has empty id"
          }
      }
    }

    // Check model chain
    modelResult.toOption.foreach { modelJson =>
      val default = modelJson.hcursor.downField("default").as[Option[String]].toOption.flatten.getOrElse("")
      if default.nonEmpty then
        providersResult.toOption.foreach { providers =>
          val idx = default.indexOf('/')
          if idx == -1 then errors += s"Default model '$default' is invalid — expected 'providerId/modelId' format"
          else
            val providerId = default.take(idx)
            if !providers.contains(providerId) then
              errors += s"Default model '$default' points to unknown provider '$providerId'"
        }
      val fallbacks = modelJson.hcursor.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
      fallbacks.foreach { ref =>
        val idx = ref.indexOf('/')
        if idx == -1 then errors += s"Fallback model '$ref' is invalid — expected 'providerId/modelId' format"
        else
          val providerId = ref.take(idx)
          providersResult.toOption.foreach { providers =>
            if !providers.contains(providerId) then
              errors += s"Fallback model '$ref' points to unknown provider '$providerId'"
          }
      }
    }

    errors.toList
  end validateConfigJson

  def updateConfig(incoming: String): IO[Either[String, Unit]] =
    // Validate before writing
    val errors = validateConfig(incoming)
    if errors.nonEmpty then IO.pure(Left(errors.mkString("; ")))
    else
      IO.blocking {
        val existing = if os.exists(configPath) then os.read(configPath) else "{}"
        val merged = mergeConfig(existing, incoming)
        os.write.over(configPath, merged, createFolders = true)
      }.attempt
        .map(_.leftMap(_.getMessage).void)

  private val sensitiveKeyPattern = "(?i)(api[_-]?key|secret|app[_-]?secret|encrypt[_-]?key|token|password)".r

  /**
   * Merge new config into existing, preserving secret values that were redacted as "***".
   * For each leaf string value: if the new value is "***", keep the existing value.
   * Also prevents empty string from overwriting an existing secret value.
   *
   * Keys absent from incoming are NOT preserved — this allows deletions
   * (e.g. removing a provider) to take effect. Only individual "***" leaf values
   * are carried over from the existing config.
   */
  private def mergeConfig(existing: String, incoming: String): String =
    def merge(existing: Json, incoming: Json, keyChain: List[String] = Nil): Json =
      (existing.asObject, incoming.asObject) match
        case (Some(eObj), Some(iObj)) =>
          val merged = iObj.toMap.map { case (key, iVal) =>
            val eVal = eObj(key).getOrElse(Json.Null)
            key -> merge(eVal, iVal, keyChain :+ key)
          }
          Json.fromFields(merged)
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
  end mergeConfig
end ConfigService
