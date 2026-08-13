package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.PathUtil
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
        // null = explicit deletion marker (frontend sends providers.<name> = null).
        // A deleted provider has no fields to validate — skip.
        if !pJson.isNull then
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

    // Check model chain. A provider being deleted (null value) counts as absent —
    // a surviving reference to it reports "points to unknown provider", guiding
    // the caller to clean it up (the frontend does this before sending the delete).
    def providerPresent(providerId: String): Boolean =
      providersResult.toOption.exists(_.get(providerId).exists(!_.isNull))

    modelResult.toOption.foreach { modelJson =>
      val default = modelJson.hcursor.downField("default").as[Option[String]].toOption.flatten.getOrElse("")
      if default.nonEmpty then
        val idx = default.indexOf('/')
        if idx == -1 then errors += s"Default model '$default' is invalid — expected 'providerId/modelId' format"
        else
          val providerId = default.take(idx)
          if !providerPresent(providerId) then
            errors += s"Default model '$default' points to unknown provider '$providerId'"
      val fallbacks = modelJson.hcursor.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
      fallbacks.foreach { ref =>
        val idx = ref.indexOf('/')
        if idx == -1 then errors += s"Fallback model '$ref' is invalid — expected 'providerId/modelId' format"
        else
          val providerId = ref.take(idx)
          if !providerPresent(providerId) then
            errors += s"Fallback model '$ref' points to unknown provider '$providerId'"
      }
    }

    errors.toList
  end validateConfigJson

  def updateConfig(incoming: String): IO[Either[String, Unit]] =
    // Validate before writing
    val errors = validateConfig(incoming)
    if errors.nonEmpty then IO.pure(Left(errors.mkString("; ")))
    else
      val deletedProviders = deletedProviderNames(incoming)
      // Save snapshot before every write for crash/recovery safety
      ConfigSnapshot.save() *>
        IO
          .blocking {
            val existing = if os.exists(configPath) then os.read(configPath) else "{}"
            val merged0 = mergeConfig(existing, incoming)
            // Backend fallback: scrub deleted-provider references from the global
            // model chain. The frontend normally cleans these first; this catches
            // partial updates where the chain survives the merge untouched.
            val merged = deletedProviders.foldLeft(merged0)(scrubGlobalChain)
            os.write.over(configPath, merged, createFolders = true)
          }
          .attempt
          .flatMap {
            case Left(e) => IO.pure(Left(e.getMessage))
            case Right(_) =>
              deletedProviders match
                case Nil => IO.pure(Right(()))
                case names => scrubAgentModelRefs(names).as(Right(()))
          }
    end if
  end updateConfig

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

  // ============================================================
  // Provider deletion → graceful reference cleanup
  // ============================================================

  /** Providers being deleted in this update (null value = deletion marker). */
  private def deletedProviderNames(incoming: String): List[String] =
    parse(incoming).toOption
      .flatMap(_.hcursor.downField("llm").downField("providers").as[Option[Map[String, Json]]].toOption.flatten)
      .map(_.collect { case (name, v) if v.isNull => name }.toList)
      .getOrElse(Nil)

  /**
   * Remove `provider/...` references from the global model chain
   * (llm.model.default + llm.model.fallbacks). Called as a backend fallback
   * during merge — the frontend normally cleans these references before
   * sending the delete.
   *
   * `model.default` is a required field in ServiceLlmConfig, so a scrubbed
   * default is promoted from the first remaining fallback, or set to "" (the
   * registry skips empty/invalid refs and falls back to the first available
   * model). Fallbacks are filtered; an emptied fallbacks key is dropped.
   * Returns the input unchanged when nothing references the provider.
   */
  private def scrubGlobalChain(json: String, provider: String): String =
    val prefix = s"$provider/"
    parse(json).toOption match
      case None => json
      case Some(j) =>
        j.hcursor.downField("llm").downField("model").focus match
          case None => json
          case Some(modelJson) =>
            val modelHc = modelJson.hcursor
            val defaultRef = modelHc.downField("default").as[Option[String]].toOption.flatten.getOrElse("")
            val fallbackRefs = modelHc.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
            val defaultTouched = defaultRef.startsWith(prefix)
            val fallbacksTouched = fallbackRefs.exists(_.startsWith(prefix))
            if !defaultTouched && !fallbacksTouched then json
            else
              val fbs = fallbackRefs.filterNot(_.startsWith(prefix))
              val newDefault =
                if defaultTouched then fbs.headOption.getOrElse("") else defaultRef
              val newFallbacks = if defaultTouched then fbs.drop(1) else fbs
              // `model.default` is a REQUIRED field in ServiceLlmConfig — never
              // drop it. "" is skipped by the registry's chain resolution, which
              // then falls back to the first available model.
              val fields = scala.collection.mutable.LinkedHashMap.empty[String, Json]
              fields += "default" -> newDefault.asJson
              if newFallbacks.nonEmpty then fields += "fallbacks" -> newFallbacks.asJson
              val newModel = Json.fromFields(fields.toMap)
              val newLlm = j.hcursor
                .downField("llm")
                .focus
                .flatMap(_.asObject)
                .map(obj => Json.fromFields(obj.toMap.updated("model", newModel)))
                .getOrElse(Json.obj("model" -> newModel))
              j.asObject
                .map(obj => Json.fromFields(obj.toMap.updated("llm", newLlm)).noSpaces)
                .getOrElse(json)

            end if

    end match

  end scrubGlobalChain

  /** Every agent.json across standalone / team / flow layers. */
  private def allAgentJsonFiles(): List[os.Path] =
    val root = PathUtil.dataRoot
    def agentJsons(parent: os.Path): List[os.Path] =
      if !os.exists(parent) then Nil
      else os.list(parent).filter(os.isDir).map(_ / "agent.json").filter(os.exists).toList
    val standalone = agentJsons(root / "agents")
    val teamAgents =
      if !os.exists(root / "teams") then Nil
      else os.list(root / "teams").filter(os.isDir).flatMap(t => agentJsons(t / "agents")).toList
    val flowAgents =
      if !os.exists(root / "flows") then Nil
      else os.list(root / "flows").filter(os.isDir).flatMap(f => agentJsons(f / "agents")).toList
    standalone ++ teamAgents ++ flowAgents

  /** Remove `provider/...` refs from an agent.json model config. */
  private def scrubAgentJson(json: Json, provider: String): Json =
    val prefix = s"$provider/"
    json.hcursor.downField("model").focus match
      case None => json
      case Some(modelJson) =>
        val modelHc = modelJson.hcursor
        val preferred = modelHc.downField("preferred").as[Option[String]].toOption.flatten
        val fallbacks = modelHc.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
        val prefTouched = preferred.exists(_.startsWith(prefix))
        val fbTouched = fallbacks.exists(_.startsWith(prefix))
        if !prefTouched && !fbTouched then json
        else
          val fields = scala.collection.mutable.LinkedHashMap.empty[String, Json]
          preferred.filterNot(_.startsWith(prefix)).foreach(p => fields += "preferred" -> p.asJson)
          val fbs = fallbacks.filterNot(_.startsWith(prefix))
          if fbs.nonEmpty then fields += "fallbacks" -> fbs.asJson
          json.asObject match
            case Some(obj) =>
              val base = obj.toMap
              if fields.isEmpty then Json.fromFields(base - "model")
              else Json.fromFields(base.updated("model", Json.fromFields(fields.toMap)))
            case None => json

    end match

  end scrubAgentJson

  /**
   * Scrub deleted-provider references from every agent.json's
   * model.preferred / model.fallbacks. Only files that actually changed are
   * written back — untouched files keep their original content.
   */
  private def scrubAgentModelRefs(deletedProviders: List[String]): IO[Unit] =
    allAgentJsonFiles().traverse_ { path =>
      IO.blocking {
        val content = os.read(path)
        parse(content) match
          case Right(json) =>
            val updated = deletedProviders.foldLeft(json)(scrubAgentJson)
            if updated != json then os.write.over(path, updated.noSpaces)
          case Left(_) => () // skip unparseable file, never clobber it
      }
    }
end ConfigService
