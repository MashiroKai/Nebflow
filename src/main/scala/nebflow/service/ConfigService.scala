package nebflow.service
import nebflow.core.PathUtil

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.{Json, JsonObject}
import nebflow.llm.Config

object ConfigService:
  private val configPath = PathUtil.dataRoot / "nebflow.json"

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
      // Save snapshot before every write for crash/recovery safety
      ConfigSnapshot.save() *> IO
        .blocking {
          val existing = if os.exists(configPath) then os.read(configPath) else "{}"
          val merged = mergeConfig(existing, incoming)
          os.write.over(configPath, merged, createFolders = true)
        }
        .attempt
        .map(_.leftMap(_.getMessage).void)

  private val sensitiveKeyPattern = "(?i)(api[_-]?key|secret|app[_-]?secret|encrypt[_-]?key|token|password)".r

  /**
   * Deep-merge incoming config into existing file.
   *
   * Merge rules:
   * - Keys in existing but NOT in incoming → PRESERVED (safe for partial updates like slider)
   * - Keys in incoming with null value → DELETED (explicit deletion, e.g. removing a provider)
   * - Keys in both → recursive merge
   * - Leaf "***" values → preserved from existing (secret redaction)
   * - Empty string for sensitive keys → preserved from existing
   */
  private def mergeConfig(existing: String, incoming: String): String =
    def merge(existing: Json, incoming: Json, keyChain: List[String] = Nil): Json =
      (existing.asObject, incoming.asObject) match
        case (Some(eObj), Some(iObj)) =>
          // Start with all existing keys as base
          val base = eObj.toMap
          // Apply incoming updates; null values mean explicit deletion
          val deletedKeys = iObj.toMap.filter(_._2.isNull).keys.toSet
          val updatedKeys = iObj.toMap.collect {
            case (key, iVal) if !iVal.isNull =>
              val eVal = base.getOrElse(key, Json.Null)
              key -> merge(eVal, iVal, keyChain :+ key)
          }
          val merged = base.filterNot { case (k, _) => deletedKeys.contains(k) } ++ updatedKeys
          Json.fromFields(merged)
        case _ =>
          val currentKey = keyChain.lastOption.getOrElse("")
          // Leaf value: if incoming is "***", keep existing
          incoming.asString match
            case Some(s) if s == "***" => existing
            case Some(s) if s.isEmpty && sensitiveKeyPattern.findFirstIn(currentKey).isDefined =>
              // Don't let empty string overwrite an existing secret value
              existing
            case _ => incoming
    (parse(existing), parse(incoming)) match
      case (Right(eJson), Right(iJson)) => merge(eJson, iJson).spaces2
      case _ => incoming // fallback: write incoming as-is if parse fails
  end mergeConfig
end ConfigService
