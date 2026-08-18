package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.PathUtil
import nebflow.llm.Config

object ConfigService:
  // def (not val): dual-read resolves per access; a val would freeze the
  // first-touched dataRoot (test isolation) and skip the legacy fallback.
  private def configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)

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
          // P0 concurrency gate fields (all optional; validate when present).
          pc.downField("maxConcurrency").as[Option[Int]].toOption.flatten.foreach { v =>
            if v < 0 then errors += s"Provider '$name': maxConcurrency must be >= 0 (0 = unlimited)"
          }
          pc.downField("rpm").as[Option[Int]].toOption.flatten.foreach { v =>
            if v <= 0 then errors += s"Provider '$name': rpm must be > 0"
          }
          pc.downField("queueTimeoutMs").as[Option[Int]].toOption.flatten.foreach { v =>
            if v <= 0 then errors += s"Provider '$name': queueTimeoutMs must be > 0"
          }
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

  def updateConfig(incomingRaw: String): IO[Either[String, Unit]] =
    IO.blocking {
      val existing = if os.exists(configPath) then os.read(configPath) else "{}"
      // Provider rename detection (B2): delete-old + add-new with identical
      // content is a rename. Rewrite the global-chain refs old→new in the
      // incoming payload BEFORE validation — validation rejects refs to the
      // now-absent old name, and a rename must keep the chain, not scrub it.
      // Masked apiKeys on renamed providers are restored from the old entry
      // (mergeConfig cannot preserve them — the new name has no existing twin).
      val renames = detectProviderRenames(existing, incomingRaw)
      val incoming0 = restoreRenamedSecrets(incomingRaw, existing, renames)
      val incoming = renames.foldLeft(incoming0)((acc, r) => rewriteChainRefs(acc, r._1, r._2))
      (existing, incoming, renames)
    }.flatMap { (existing, incoming, renames) =>
      // Validate before writing
      val errors = validateConfig(incoming)
      if errors.nonEmpty then IO.pure(Left(errors.mkString("; ")))
      else
        val deletedProviders = deletedProviderNames(incoming)
        // Renamed providers KEEP their references — only pure deletes scrub.
        val renameOlds = renames.map(_._1).toSet
        val pureDeletes = deletedProviders.filterNot(renameOlds.contains)
        // Save snapshot before every write for crash/recovery safety
        ConfigSnapshot.save() *>
          IO
            .blocking {
              val merged0 = mergeConfig(existing, incoming)
              // Backend fallback: scrub deleted-provider references from the global
              // model chain (pure deletes only — renames are rewritten below). The
              // frontend normally cleans these first; this catches partial updates
              // where the chain survives the merge untouched.
              val merged1 = pureDeletes.foldLeft(merged0)(scrubGlobalChain)
              // Renames: the merged chain may still carry the old name when the
              // incoming payload omitted llm.model entirely (partial update).
              val merged = renames.foldLeft(merged1)((acc, r) => rewriteChainRefs(acc, r._1, r._2))
              // Write path: always the brand config name — the first
              // read-modify-write after a rename completes the file
              // migration (existing was read through the legacy fallback).
              os.write.over(PathUtil.configJsonWritePath(PathUtil.dataRoot), merged, createFolders = true)
            }
            .attempt
            .flatMap {
              case Left(e) => IO.pure(Left(e.getMessage))
              case Right(_) =>
                // Reference cleanup across agent.json (all three layers) and
                // model presets: pure deletes scrub, renames rewrite old→new.
                scrubAgentModelRefs(pureDeletes) *>
                  rewriteAgentModelRefs(renames) *>
                  scrubPresetRefs(pureDeletes) *>
                  rewritePresetRefs(renames).as(Right(()))
            }
    }
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

  // ============================================================
  // Provider rename → reference rewrite (B2)
  // ============================================================

  /**
   * Detect provider renames in this update: a deleted provider whose content
   * is identical to a newly added provider — same baseUrl (modulo trailing
   * slash), same protocol, same models; apiKey "***" or empty counts as equal
   * (masked round-trip / merge-preserved secret). Returns (oldName, newName)
   * pairs, greedily matched one-to-one. Deletions without a match are pure
   * deletes and keep the existing scrub behavior.
   */
  private def detectProviderRenames(existing: String, incoming: String): List[(String, String)] =
    def liveProviders(raw: String): Map[String, Json] =
      parse(raw).toOption
        .flatMap(_.hcursor.downField("llm").downField("providers").as[Option[Map[String, Json]]].toOption.flatten)
        .getOrElse(Map.empty)
        .filterNot(_._2.isNull) // null = deletion marker, not a live provider
    val existingProviders = liveProviders(existing)
    val incomingProviders = liveProviders(incoming)
    val added = incomingProviders.keys.toSet.diff(existingProviders.keys.toSet)
    def sameProvider(a: Json, b: Json): Boolean =
      def norm(url: String) = url.trim.stripSuffix("/")
      def str(j: Json, key: String) = j.hcursor.downField(key).as[Option[String]].toOption.flatten.getOrElse("")
      def models(j: Json) =
        j.hcursor.downField("models").as[Option[List[Json]]].toOption.flatten.getOrElse(Nil).map(_.noSpaces)
      norm(str(a, "baseUrl")) == norm(str(b, "baseUrl")) &&
      str(a, "protocol") == str(b, "protocol") &&
      models(a) == models(b) && {
        // b is the incoming side: masked or empty apiKey must not block the match
        val keyA = str(a, "apiKey"); val keyB = str(b, "apiKey")
        keyA == keyB || keyB == "***" || keyB.isEmpty
      }
    existingProviders.keys.toSet.diff(incomingProviders.keys.toSet).toList.sorted
      .foldLeft((List.empty[(String, String)], added)) { case ((acc, candidates), old) =>
        candidates.find(newName => sameProvider(existingProviders(old), incomingProviders(newName))) match
          case Some(newName) => ((old, newName) :: acc, candidates - newName)
          case None => (acc, candidates)
      }._1.reverse
  end detectProviderRenames

  /** Restore masked/empty apiKeys on renamed providers. The frontend sends a
    * renamed provider with apiKey "***" (redacted round-trip); mergeConfig's
    * secret-preserving "***" rule only fires on same-key recursive merge, so a
    * delete-old + add-new rename would write the mask literally — and the old
    * provider (holding the real key) is being deleted. Copy the real key from
    * the existing old-name provider into the incoming new-name one. */
  private def restoreRenamedSecrets(incoming: String, existing: String, renames: List[(String, String)]): String =
    if renames.isEmpty then incoming
    else
      def providerApiKey(raw: String, name: String): Option[String] =
        parse(raw).toOption.flatMap(
          _.hcursor.downField("llm").downField("providers").downField(name).downField("apiKey").as[Option[String]].toOption.flatten
        )
      renames.foldLeft(incoming) { (acc, rename) =>
        val (oldName, newName) = rename
        (providerApiKey(acc, newName), providerApiKey(existing, oldName)) match
          case (Some(masked), Some(real)) if masked == "***" || masked.isEmpty =>
            (for
              json    <- parse(acc).toOption
              root    <- json.asObject
              llmObj  <- json.hcursor.downField("llm").focus.flatMap(_.asObject)
              provObj <- llmObj.toMap.get("providers").flatMap(_.asObject)
              newProv <- provObj.toMap.get(newName).flatMap(_.asObject)
            yield
              val withKey = Json.fromFields(newProv.toMap.updated("apiKey", real.asJson))
              val newProviders = Json.fromFields(provObj.toMap.updated(newName, withKey))
              val newLlm = Json.fromFields(llmObj.toMap.updated("providers", newProviders))
              Json.fromFields(root.toMap.updated("llm", newLlm)).noSpaces
            ).getOrElse(acc)
          case _ => acc
      }
  end restoreRenamedSecrets

  /** Rewrite `oldName/…` model refs to `newName/…` in the global chain
    * (llm.model.default + llm.model.fallbacks). No-op when nothing matches. */
  private def rewriteChainRefs(json: String, oldName: String, newName: String): String =
    val oldPrefix = s"$oldName/"; val newPrefix = s"$newName/"
    def rw(ref: String) = if ref.startsWith(oldPrefix) then newPrefix + ref.stripPrefix(oldPrefix) else ref
    parse(json).toOption match
      case None => json
      case Some(j) =>
        j.hcursor.downField("llm").downField("model").focus match
          case None => json
          case Some(modelJson) =>
            val modelHc = modelJson.hcursor
            val defaultRef = modelHc.downField("default").as[Option[String]].toOption.flatten.getOrElse("")
            val fallbackRefs = modelHc.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
            if !defaultRef.startsWith(oldPrefix) && !fallbackRefs.exists(_.startsWith(oldPrefix)) then json
            else
              val fields = scala.collection.mutable.LinkedHashMap.empty[String, Json]
              fields += "default" -> rw(defaultRef).asJson
              val newFallbacks = fallbackRefs.map(rw)
              if newFallbacks.nonEmpty then fields += "fallbacks" -> newFallbacks.asJson
              val newModel = modelJson.asObject
                .map(o => Json.fromFields(o.toMap ++ fields.toMap))
                .getOrElse(Json.fromFields(fields.toMap))
              val newLlm = j.hcursor.downField("llm").focus.flatMap(_.asObject)
                .map(o => Json.fromFields(o.toMap.updated("model", newModel)))
                .getOrElse(Json.obj("model" -> newModel))
              j.asObject.map(o => Json.fromFields(o.toMap.updated("llm", newLlm)).noSpaces).getOrElse(json)
    end match
  end rewriteChainRefs

  /** Rewrite `oldName/…` refs to `newName/…` in an agent.json model config.
    * Structural mirror of scrubAgentJson — refs are rewritten in place, the
    * model selection survives the rename. */
  private def rewriteAgentJson(json: Json, oldName: String, newName: String): Json =
    val oldPrefix = s"$oldName/"; val newPrefix = s"$newName/"
    def rw(ref: String) = if ref.startsWith(oldPrefix) then newPrefix + ref.stripPrefix(oldPrefix) else ref
    json.hcursor.downField("model").focus match
      case None => json
      case Some(modelJson) =>
        val modelHc = modelJson.hcursor
        val preferred = modelHc.downField("preferred").as[Option[String]].toOption.flatten
        val fallbacks = modelHc.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
        if !preferred.exists(_.startsWith(oldPrefix)) && !fallbacks.exists(_.startsWith(oldPrefix)) then json
        else
          val fields = scala.collection.mutable.LinkedHashMap.empty[String, Json]
          preferred.foreach(p => fields += "preferred" -> rw(p).asJson)
          val newFallbacks = fallbacks.map(rw)
          if newFallbacks.nonEmpty then fields += "fallbacks" -> newFallbacks.asJson
          json.asObject match
            case Some(obj) =>
              if fields.isEmpty then Json.fromFields(obj.toMap - "model")
              else Json.fromFields(obj.toMap.updated("model", Json.fromFields(fields.toMap)))
            case None => json
    end match
  end rewriteAgentJson

  /** Rewrite renamed-provider refs in every agent.json across all three layers. */
  private def rewriteAgentModelRefs(renames: List[(String, String)]): IO[Unit] =
    allAgentJsonFiles().traverse_ { path =>
      IO.blocking {
        val content = os.read(path)
        parse(content) match
          case Right(json) =>
            val updated = renames.foldLeft(json)((j, r) => rewriteAgentJson(j, r._1, r._2))
            if updated != json then os.write.over(path, updated.noSpaces)
          case Left(_) => () // skip unparseable file, never clobber it
      }
    }

  // ============================================================
  // Provider delete/rename → model-presets.json cleanup (B2)
  // ============================================================

  private val presetsPath: os.Path = PathUtil.dataRoot / "model-presets.json"

  /** Atomically write a JSON file (temp + rename), mirroring PresetStore.save. */
  private def atomicWriteJson(path: os.Path, json: Json): Unit =
    os.makeDir.all(path / os.up)
    val tmp = path / os.up / s".${path.last}.${System.nanoTime()}.tmp"
    os.write(tmp, json.noSpaces)
    os.move.over(tmp, path)

  /** Apply a JSON transform to model-presets.json. Skips when the file is
    * absent — provider cleanup must NOT initialize the presets store
    * (PresetStore.load owns the create/re-init semantics). Never clobbers an
    * unparseable file. Writes back only when something actually changed. */
  private def editPresets(transform: Json => Json): IO[Unit] = IO.blocking {
    if os.exists(presetsPath) then
      parse(os.read(presetsPath)) match
        case Right(json) =>
          val updated = transform(json)
          if updated != json then atomicWriteJson(presetsPath, updated)
        case Left(_) => ()
  }

  /** Transform every preset's {preferred, fallbacks} chain. Presets whose
    * chain is unchanged are left byte-identical; the file object is rebuilt
    * only when at least one preset actually changed. */
  private def mapPresetChains(
    json: Json,
    f: (Option[String], List[String]) => (Option[String], List[String])
  ): Json =
    json.hcursor.downField("presets").focus.flatMap(_.asObject) match
      case None => json
      case Some(presetsObj) =>
        var touched = false
        val newPresets: List[(String, Json)] = presetsObj.toList.map { case (key, preset) =>
          val updated = preset.asObject match
            case Some(pObj) =>
              val preferred = pObj.toMap.get("preferred").flatMap(_.asString)
              val fallbacks = pObj.toMap
                .get("fallbacks")
                .flatMap(_.asArray.map(_.toList.flatMap(_.asString)))
                .getOrElse(Nil)
              val (newPref, newFbs) = f(preferred, fallbacks)
              if newPref != preferred || newFbs != fallbacks then
                touched = true
                Json.fromFields(
                  pObj.toMap.updated("preferred", newPref.asJson).updated("fallbacks", newFbs.asJson)
                )
              else preset
            case None => preset
          key -> updated
        }
        if !touched then json
        else
          json.asObject
            .map(o => Json.fromFields(o.toMap.updated("presets", Json.fromFields(newPresets))))
            .getOrElse(json)

  /** Scrub deleted-provider refs from every preset chain. A scrubbed
    * preferred promotes from the fallbacks head (mirrors the global-chain
    * scrub); a fully emptied chain resolves to the default preset / global
    * chain at agent-load time. */
  private def scrubPresetRefs(deleted: List[String]): IO[Unit] =
    editPresets(json =>
      deleted.foldLeft(json) { (acc, provider) =>
        val prefix = s"$provider/"
        mapPresetChains(
          acc,
          (preferred, fallbacks) => {
            val prefTouched = preferred.exists(_.startsWith(prefix))
            val fbs = fallbacks.filterNot(_.startsWith(prefix))
            if prefTouched then (fbs.headOption, fbs.drop(1)) else (preferred, fbs)
          }
        )
      }
    )

  /** Rewrite renamed-provider refs old→new in every preset chain. */
  private def rewritePresetRefs(renames: List[(String, String)]): IO[Unit] =
    editPresets(json =>
      renames.foldLeft(json) { (acc, rename) =>
        val (oldName, newName) = rename
        val oldPrefix = s"$oldName/"; val newPrefix = s"$newName/"
        def rw(ref: String) = if ref.startsWith(oldPrefix) then newPrefix + ref.stripPrefix(oldPrefix) else ref
        mapPresetChains(acc, (preferred, fallbacks) => (preferred.map(rw), fallbacks.map(rw)))
      }
    )
end ConfigService
