package nebflow.service

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.AtomicJson
import nebflow.neblink.core.*
import nebflow.shared.{Config, ImageGenConfig, PathUtil}

object ConfigService:

  /**
   * nebflow.json 全部 read-modify-write 写者的进程级串行锁。
   *
   * 写者 = updateConfig（WS/REST 全量保存）+ gateway 各 targeted write
   * （persistWorkSchedule/persistThinkingConfig/persistMcpServerEnabled/
   * setToolResultTtl）。这些函数都是「读盘 → 内存合并 → 整文件覆写」形态：
   * 无锁并发时读到旧底稿的一方会把他人刚写入的键冲回旧值——2026-08-27 冻结
   * 修复批实证（宿主日志 08-25 15:06:25.698/.717 同秒 enabled=false→true
   * 双写互踩两例；segments「反复丢失」即此族）。单例锁按 JVM 实例隔离，
   * 测试同 JVM 并发 suite 共享一把锁仅表现为串行、不破坏语义。
   */
  private val configWriteLock = new java.util.concurrent.locks.ReentrantLock()

  /** 在配置写锁内执行 ioa（attempt 包裹保证异常路径也释放）。 */
  def writeLocked[A](ioa: IO[A]): IO[A] =
    IO.blocking(configWriteLock.lock()).flatMap { _ =>
      ioa.attempt.flatMap(r => IO.blocking(configWriteLock.unlock()) *> IO.fromEither(r))
    }
  // def (not val): dual-read resolves per access; a val would freeze the
  // first-touched dataRoot (test isolation) and skip the legacy fallback.
  private def configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)

  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.config")

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

  /**
   * The mask literal — the SAME convention the write side's [[mergeConfig]]
   * treats as "keep the stored value" (`incoming.asString == Some("***")`),
   * so a masked read that round-trips through updateConfig can never clobber
   * the real key. One literal, both sides.
   */
  val SecretMask: String = "***"

  /**
   * Read-side secret projection (keyaudit-quickfix ③): `llm.providers.<id>.
   * apiKey` and `search.apiKey` are masked to `"***"`. Everything else keeps
   * its value verbatim — including the `jev` / `embedding` `keyRef` pointers
   * (those are secret NAMES, never values). (Object key order may re-seat in
   * the re-serialization; JSON key order is not semantic anywhere downstream.)
   *
   * A body that does not parse is handed back unchanged (the write side
   * ref-parse-fails on it identically; there is no round-trip to protect).
   */
  def maskSecrets(raw: String): String =
    parse(raw).toOption match
      case None => raw
      case Some(json) => maskSecretsJson(json).spaces2

  /** JSON-level core of [[maskSecrets]] (spec-facing pure form). */
  def maskSecretsJson(json: Json): Json =
    maskProviderApiKeys(maskSearchApiKey(json))

  /** `llm.providers.<id>.apiKey` → `"***"` (absent key ⇒ untouched). */
  private def maskProviderApiKeys(json: Json): Json =
    (for
      root <- json.asObject
      llm <- root("llm").flatMap(_.asObject)
      providers <- llm("providers").flatMap(_.asObject)
    yield
      val masked = JsonObject.fromIterable(providers.toMap.map { case (id, p) =>
        id -> p.mapObject(o => if o.contains("apiKey") then o.add("apiKey", Json.fromString(SecretMask)) else o)
      })
      Json.fromJsonObject(root.add("llm", Json.fromJsonObject(llm.add("providers", Json.fromJsonObject(masked)))))
    ).getOrElse(json)

  /** `search.apiKey` → `"***"` (absent key ⇒ untouched). */
  private def maskSearchApiKey(json: Json): Json =
    (for
      root <- json.asObject
      search <- root("search").flatMap(_.asObject)
      if search.contains("apiKey")
    yield Json.fromJsonObject(
      root.add("search", Json.fromJsonObject(search.add("apiKey", Json.fromString(SecretMask))))
    )).getOrElse(json)

  /**
   * 设置页 apikey 明文回读投影（作者令 2026-10-27 10:33「设置里配置的 apikey，
   * 直接显示就行了」）：把 nebflow.json 里 `llm.providers.<id>.apiKey` 的**真值**
   * 投影出来——即通用读面 [[getConfig]] 刻意打码（keyaudit-quickfix ③）的那些值。
   *
   *  - 线格式：`{"providers":{"<id>":"<apiKey>"}}`；没有 apiKey 的 provider
   *    缺席（前端显示空态，不是掩码）。
   *  - 范围：仅 provider apiKey——`search.apiKey` 不在设置页两处字段内，通用读面
   *    打码维持；`jev.keyRef` 无需回读（secrets 目录里的**名**，config 读面本就
   *    逐字回传）。
   *  - 纪律：唯一调用方 = 网关 `withAuth` 臂（与设置写同一条 owner 门槛，
   *    GatewayAuth Rails.ApiFull）；🔴 日志零 key 值——值的唯一出现位置是响应体。
   *  - 纯读投影：不取写锁、不落盘，盘上真相不变。
   */
  def revealProviderApiKeys: IO[Json] = IO.blocking {
    val raw = if os.exists(configPath) then os.read(configPath) else "{}"
    val providers = parse(raw).toOption
      .flatMap(_.hcursor.downField("llm").downField("providers").as[Map[String, Json]].toOption)
      .getOrElse(Map.empty[String, Json])
    Json.obj("providers" -> Json.fromFields(providers.flatMap { case (id, p) =>
      p.hcursor.downField("apiKey").as[String].toOption.filter(_.nonEmpty).map { key =>
        id -> Json.fromString(
          nebflow.core.secrets.ConfigCredentialStore.resolveManaged(PathUtil.dataRoot, key, "provider-settings")
        )
      }
    }))
  }

  /** Resolve only the requested provider; never resolve neighbouring credentials. */
  def revealProviderApiKey(id: String): IO[Option[String]] = IO.blocking {
    val raw = if os.exists(configPath) then os.read(configPath) else "{}"
    parse(raw).toOption
      .flatMap(_.hcursor.downField("llm").downField("providers").downField(id).downField("apiKey").as[String].toOption)
      .filter(_.nonEmpty)
      .map(key =>
        nebflow.core.secrets.ConfigCredentialStore.resolveManaged(PathUtil.dataRoot, key, "provider-settings")
      )
  }

  def getConfig: IO[String] = IO.blocking {
    // Read-side masking (keyaudit-quickfix ③): the wire faces (REST
    // GET /api/config, WS getConfig) must not echo the stored keys. Every
    // consumer of this method is safe under the mask:
    //   · config wire faces — the frontend already treats `"***"` as "do not
    //     prefill / keep stored" (sidebar.js provider form sends `***` for an
    //     empty key input; mergeConfig preserves it on the write side);
    //   · onboarding model step / probe — consume model ids and presence
    //     only, never a key value; the probe's real LLM call goes through the
    //     in-memory ProviderRegistry, not this string;
    //   · finishOnboarding's contextWindow write-back — deepMerges the masked
    //     tree into updateConfig, where mergeConfig's `***` rule keeps the
    //     stored key. Round-trip closed by construction.
    if os.exists(configPath) then maskSecrets(os.read(configPath)) else "{}"
  }

  /** Convert provider keys before the final configuration write. */
  private[nebflow] def persistProviderCredentials(json: Json): Json =
    json.mapObject { root =>
      root("llm").flatMap(_.asObject) match
        case None => root
        case Some(llm) =>
          llm("providers").flatMap(_.asObject) match
            case None => root
            case Some(providers) =>
              val updated = JsonObject.fromIterable(providers.toIterable.map { case (id, provider) =>
                id -> provider.mapObject { obj =>
                  obj("apiKey").flatMap(_.asString) match
                    case Some(value) =>
                      obj.add(
                        "apiKey",
                        Json.fromString(nebflow.core.secrets.ConfigCredentialStore.persist(PathUtil.dataRoot, value))
                      )
                    case None => obj
                }
              })
              root.add("llm", Json.fromJsonObject(llm.add("providers", Json.fromJsonObject(updated))))
    }

  private[nebflow] def persistSearchCredentials(json: Json): Json =
    json.mapObject { root =>
      root("search").flatMap(_.asObject) match
        case None => root
        case Some(search) =>
          val secured = search("apiKey").flatMap(_.asString) match
            case Some(value) =>
              search.add(
                "apiKey",
                Json.fromString(nebflow.core.secrets.ConfigCredentialStore.persist(PathUtil.dataRoot, value))
              )
            case None => search
          root.add("search", Json.fromJsonObject(secured))
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

    // #339：llm.model 链校验段已删——字段退役（decoder 容忍但忽略；boot 迁移
    // 播种成默认 preset 后原子剥离）。preset 引用的清理/改写由 scrubPresetRefs
    // / rewritePresetRefs 承担（已存在）。

    errors.toList
  end validateConfigJson

  /**
   * Update nebflow.json from an incoming full/partial config (deep merge,
   * incoming-优先), under the config write lock.
   *
   * @param runtimeOverrides 运行时热更键的内存权威值（freezeScheduleRef /
   *   thinkingConfigRef / toolResultTtlRef 等）。merge 完成后这些顶层键以
   *   权威值覆写落盘——调用方的 incoming 快照（如前端 state.parsedConfig）
   *   可能是页面加载时刻的陈旧底稿，若不覆写，任意 provider 类保存都会把
   *   用户早已改过的 runtime 键回滚到快照值（冻结「关了又开/segments 反复
   *   丢失」的根因，2026-08-27）。调用方省略某键 = 该键允许由 incoming 深
   *   合并（无热更 ref 的普通配置键不受影响）。
   */
  def updateConfig(incomingRaw: String, runtimeOverrides: Map[String, Json] = Map.empty): IO[Either[String, Unit]] =
    writeLocked {
      IO.blocking {
        val existing = if os.exists(configPath) then os.read(configPath) else "{}"
        // Provider rename detection (B2): delete-old + add-new with identical
        // content is a rename. Masked apiKeys on renamed providers are restored
        // from the old entry (mergeConfig cannot preserve them — the new name has
        // no existing twin). (#339：llm.model 链的 rename 改写已删——字段退役；
        // preset 引用的改写在下方的 rewritePresetRefs。)
        val renames = detectProviderRenames(existing, incomingRaw)
        val incoming0 = restoreRenamedSecrets(incomingRaw, existing, renames)
        (existing, incoming0, renames)
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
                // config-single-source batch (2026-11-05): every persisted config
                // ① converges model-level unavailable windows UP to the provider
                //   (author ruling ④ — one provider, one behaviour) and
                // ② prunes `llm.fallback` refs of providers that were just deleted
                //   (the file must not promise a candidate the runtime cannot build).
                val mergedNorm = parse(merged0).toOption match
                  case Some(j) => pruneChainRefs(nebflow.shared.Config.normalizeConfigJson(j), pureDeletes).spaces2
                  case None => merged0
                val merged =
                  if runtimeOverrides.isEmpty then mergedNorm
                  else
                    parse(mergedNorm).toOption match
                      case Some(j) =>
                        // Runtime-authoritative keys win over the incoming snapshot.
                        val overridden = runtimeOverrides.foldLeft(j) { (acc, kv) =>
                          acc.mapObject(_.add(kv._1, kv._2))
                        }
                        overridden.spaces2
                      case None => mergedNorm
                // Write path: always the brand config name — the first
                // read-modify-write after a rename completes the file
                // migration (existing was read through the legacy fallback).
                // Restricted form (keyaudit-quickfix ①): nebflow.json carries
                // provider apiKeys in cleartext — the file lands 0600 instead
                // of the umask default 0644 (legacy files converge on their
                // next save; no migration script).
                val persisted = persistSearchCredentials(
                  persistProviderCredentials(
                    parse(merged)
                      .fold(_ => throw new IllegalArgumentException("Invalid merged configuration"), identity)
                  )
                )
                AtomicJson.writeSyncRestricted(PathUtil.configJsonWritePath(PathUtil.dataRoot), persisted.spaces2)
              }
              .attempt
              .flatMap {
                case Left(e) => IO.pure(Left(e.getMessage))
                case Right(_) =>
                  // 删除落地校验（provider-config-vs-runtime-mismatch 批）：
                  // 无锁的旁路写者可能与刚才这次合并交叉，把已删 provider 写回。
                  // 静默容忍 = 「界面上删了、运行期还在调」——本批的原始病症。
                  // 因此回读核对，未落地即**上浮失败**（调用方回错误帧给界面）。
                  val survived = undeletedProviders(pureDeletes)
                  if survived.nonEmpty then
                    IO.delay(
                      logger.warnSync(
                        s"Provider delete did NOT land for [${survived.mkString(", ")}] — " +
                          "a concurrent config writer restored them; the runtime will keep " +
                          "using them until a successful delete. Re-save the settings to retry."
                      )
                    ).attempt *> IO.pure(
                      Left(
                        s"Provider deletion not persisted for: ${survived.mkString(", ")} " +
                          "(a concurrent config write restored them — retry the removal)"
                      )
                    )
                  else
                    // Reference cleanup across agent.json (all three layers) and
                    // model presets: pure deletes scrub, renames rewrite old→new.
                    scrubAgentModelRefs(pureDeletes) *>
                      rewriteAgentModelRefs(renames) *>
                      scrubPresetRefs(pureDeletes) *>
                      rewritePresetRefs(renames).as(Right(()))
                  end if
              }
        end if
      }
    }
  end updateConfig

  // ============================================================
  // fallback 链单一源维护（config-single-source 批，作者裁定①③ 2026-11-05）
  // ============================================================

  private val fallbackLog = nebflow.shared.NebflowLogger.forName("nebflow.config.fallback")

  /** @deprecated 语义已并入 [[ensureConfigConvergence]]；保留别名以免调用点漂移。 */
  def ensureFallbackSection(): IO[Unit] = ensureConfigConvergence()

  /**
   * Boot convergence of the config file (config-single-source batch, author
   * rulings ③④ 2026-11-05) — ONE read-modify-write, logged, idempotent:
   *
   *  1. **unavailable-window convergence**: model-level
   *     `llm.providers.<id>.models[].unavailableWindows` are merged UP into
   *     `llm.providers.<id>.unavailableWindows` and removed from the model
   *     entries ("one provider, one behaviour"). Without this boot write the
   *     runtime already saw the converged shape (read-side normalization) but
   *     the FILE still carried two sources — i.e. the exact drift this batch
   *     removes.
   *  2. **`llm.fallback` seed**: when the section carries no ref, the CURRENT
   *     effective chain (Nebula primary chain / provider-derived seed) is written
   *     into it, so the chain is visible and editable where every other provider
   *     setting lives. A hand-edited section is never rewritten.
   *
   * Failures are the caller's to log (boot continues: a read-only data root must
   * never brick the engine).
   */
  def ensureConfigConvergence(): IO[Unit] =
    writeLocked {
      IO.blocking {
        val existing = if os.exists(configPath) then os.read(configPath) else "{}"
        parse(existing) match
          case Left(_) => ()
          case Right(json) =>
            val converged = nebflow.shared.Config.normalizeConfigJson(json)
            val windowsChanged = converged.noSpaces != json.noSpaces
            if windowsChanged then
              fallbackLog.infoSync(
                "[config-convergence] model-level unavailable windows merged into the provider level " +
                  "(single source: llm.providers.<id>.unavailableWindows)"
              )
            val currentRefs =
              converged.hcursor
                .downField("llm")
                .downField("fallback")
                .as[Option[nebflow.shared.AgentModelConfig]]
                .toOption
                .flatten
                .map(c => nebflow.shared.FallbackChain.refs(Some(c)))
                .getOrElse(Nil)
            val withFallback =
              if currentRefs.nonEmpty then (converged, false)
              else
                val policy = nebflow.core.SchemePolicy
                val (chain, source) = policy.resolveModel(policy.NebulaName, policy.nebulaChain())
                val refs = nebflow.shared.FallbackChain.refs(Some(chain))
                if refs.isEmpty then (converged, false)
                else
                  val section = Json.obj(
                    "preferred" -> Json.fromString(refs.head),
                    "fallbacks" -> Json.arr(refs.tail.map(Json.fromString)*)
                  )
                  val updated = converged.mapObject { root =>
                    val llm = root("llm").flatMap(_.asObject).getOrElse(JsonObject.empty)
                    root.add("llm", Json.fromJsonObject(llm.add("fallback", section)))
                  }
                  fallbackLog.infoSync(
                    s"[fallback-chain] seeded llm.fallback from ${source}: ${refs.mkString(" → ")} " +
                      "(the config file is now the single source of the chain)"
                  )
                  (updated, true)
                end if
            if windowsChanged || withFallback._2 then
              AtomicJson.writeSyncRestricted(PathUtil.configJsonWritePath(PathUtil.dataRoot), withFallback._1.spaces2)
        end match
      }
    }

  /**
   * Mirror a chain write into `llm.fallback` (the `/model` panel write face).
   *
   * Single-source discipline: `PUT /agents/Nebula/model` still owns the Nebula
   * sidecar (per-turn reload), but the SAME chain must be visible in the config
   * file — otherwise editing the panel would silently stop affecting the
   * runtime, which is exactly the class of drift this batch removes. A `None`
   * chain removes the section (back to the sidecar/seed legs).
   */
  def setFallbackChain(chain: Option[nebflow.shared.AgentModelConfig]): IO[Unit] =
    writeLocked {
      IO.blocking {
        val existing = if os.exists(configPath) then os.read(configPath) else "{}"
        val json = parse(existing).getOrElse(Json.obj())
        val refs = nebflow.shared.FallbackChain.refs(chain)
        val updated = json.mapObject { root =>
          val llm = root("llm").flatMap(_.asObject).getOrElse(JsonObject.empty)
          val nextLlm =
            if refs.isEmpty then llm.remove("fallback")
            else
              llm.add(
                "fallback",
                Json.obj(
                  "preferred" -> Json.fromString(refs.head),
                  "fallbacks" -> Json.arr(refs.tail.map(Json.fromString)*)
                )
              )
          root.add("llm", Json.fromJsonObject(nextLlm))
        }
        AtomicJson.writeSyncRestricted(PathUtil.configJsonWritePath(PathUtil.dataRoot), updated.spaces2)
        fallbackLog.infoSync(
          if refs.isEmpty then "[fallback-chain] llm.fallback removed (chain follows the sidecar/seed legs again)"
          else s"[fallback-chain] llm.fallback written: ${refs.mkString(" → ")}"
        )
      }
    }

  /**
   * Provider-delete chain hygiene: refs of a provider that just disappeared are
   * pruned from `llm.fallback` (with a log line) — the file must never promise a
   * candidate the runtime cannot build. Surviving order is preserved; when the
   * preferred ref dies, the first surviving fallback takes its place.
   */
  private[service] def pruneChainRefs(json: Json, deletedProviders: List[String]): Json =
    if deletedProviders.isEmpty then json
    else
      val gone = deletedProviders.toSet
      (for
        root <- json.asObject
        llm <- root("llm").flatMap(_.asObject)
        section <- llm("fallback").flatMap(_.asObject)
      yield
        val refs = nebflow.shared.FallbackChain.refs(
          Json.fromJsonObject(section).as[nebflow.shared.AgentModelConfig].toOption
        )
        val kept = refs.filterNot(r => gone.contains(r.takeWhile(_ != '/')))
        val dropped = refs.filter(r => gone.contains(r.takeWhile(_ != '/')))
        if dropped.isEmpty then json
        else
          fallbackLog.warnSync(
            s"[fallback-chain] pruned ${dropped.mkString(", ")} from llm.fallback " +
              s"(provider deleted from llm.providers; kept: ${
                  if kept.isEmpty then "<empty>" else kept.mkString(" → ")
                })"
          )
          val nextLlm =
            if kept.isEmpty then llm.remove("fallback")
            else
              llm.add(
                "fallback",
                Json.obj(
                  "preferred" -> Json.fromString(kept.head),
                  "fallbacks" -> Json.arr(kept.tail.map(Json.fromString)*)
                )
              )
          Json.fromJsonObject(root.add("llm", Json.fromJsonObject(nextLlm)))
        end if
      ).getOrElse(json)

  /**
   * 定向写 `safety.defaultMode`（= 应用的权限模式，全局唯一持久权威源）。
   *
   * 2026-09-12 权限全局单一权威源批（设计 §5.1 / §13 #16）：REST
   * `PUT /api/safety/mode` 与 Settings UI 的写入口。
   *
   * 形态 = 「读双读路径 → parse → 定向覆写子树 → 写品牌名」，照
   * `WebSocketRoutes.persistMcpServerEnabled` 的既有先例：
   *   · **整体包在 `writeLocked`** 内 —— 与其它 read-modify-write 写者（updateConfig
   *     / persistWorkSchedule / persistThinkingConfig / persistMcpServerEnabled /
   *     setToolResultTtl）串行，避免同秒双写互踩（`:14-23` 注释记载的踩踏族）；
   *   · **不走 `mergeConfig`** ⇒ `null = 删键`语义不参与（调用方已做枚举白名单校验，
   *     只传三档显式值）；
   *   · 密钥遮蔽 / `***` 规则不涉及 `safety` 子树；
   *   · 写盘失败**上浮**（fail-loud，与冻结修复批同族）——调用方据此回 4xx/5xx，
   *     不让用户看到"已保存"而实际未落盘。
   */
  def setSafetyDefaultMode(mode: String): IO[Unit] =
    writeLocked {
      IO.blocking {
        val existing = if os.exists(configPath) then os.read(configPath) else "{}"
        val parsed = parse(existing).getOrElse(Json.obj())
        val updated = parsed.mapObject(_.add("safety", Json.obj("defaultMode" -> mode.asJson)))
        // Restricted form (keyaudit-quickfix ①) — same rationale as updateConfig.
        AtomicJson.writeSyncRestricted(PathUtil.configJsonWritePath(PathUtil.dataRoot), updated.spaces2)
      }
    }

  /**
   * 定向写顶层 `llmLog` 节（LLM 日志记录开关的持久化权威源）。
   *
   * 2026-09-13「LLM 日志记录默认关」批（D-A：开关不持久化 ⇒ 宿主重启回落 true）：
   * WS `setLlmLog` 的写入口。只有落盘成功，调用方才热更 `LlmLogWriter` 内存态
   * （持久化优先排序，镜像 `setToolResultTtl` 的「persist 成功才热更 ref」）——
   * 保证「显式改动过的值跨重启保持」不被「内存已改、盘上未改」的窗口破坏。
   *
   * 形态照 [[setSafetyDefaultMode]]：整体包在 `writeLocked` 内与其它
   * read-modify-write 写者串行；只覆写顶层 `llmLog` 节的 `enabled`（**其余顶层
   * 键与本节其它字段原样保留**；`llmLog` 无其它写者）；写盘走既有
   * [[nebflow.core.AtomicJson]]（temp + ATOMIC_MOVE）；失败**上浮**（fail-loud）。
   */
  def setLlmLogEnabled(enabled: Boolean): IO[Unit] =
    writeLocked {
      IO.blocking {
        val existing = if os.exists(configPath) then os.read(configPath) else "{}"
        val parsed = parse(existing).getOrElse(Json.obj())
        val section = parsed.hcursor
          .downField(nebflow.core.LlmLogWriter.configSection)
          .focus
          .flatMap(_.asObject)
          .getOrElse(JsonObject.empty)
        val updated = parsed.mapObject(
          _.add(
            nebflow.core.LlmLogWriter.configSection,
            Json.fromJsonObject(section.add("enabled", enabled.asJson))
          )
        )
        // Restricted form (keyaudit-quickfix ①) — same rationale as updateConfig.
        AtomicJson.writeSyncRestricted(PathUtil.configJsonWritePath(PathUtil.dataRoot), updated.spaces2)
      }
    }

  private val sensitiveKeyPattern = "(?i)(api[_-]?key|secret|app[_-]?secret|encrypt[_-]?key|token|password)".r

  /**
   * 定向写顶层 `embedding` 节（task-rag 批 #1227 / §Batch 7：OpenAI 兼容
   * embeddings 端点的设置区）。
   *
   * 形态：整体包在 `writeLocked` 内串行；只覆写调用方
   * 显式给出的键（`None` 键原样保留，本节无其它写者）；写盘走
   * [[nebflow.core.AtomicJson]]；失败上浮（fail-loud）。
   *
   * 🔴 凭据纪律（同 jev 节）：`keyRef` 是 secrets 目录里的**名**（指针），
   * 在册；**值型凭据键一律不在册**（无 `apiKey` 入参，形态上不可表达）。
   */
  def setEmbeddingSection(values: Map[String, Json]): IO[Either[String, Unit]] =
    val unknown = values.keySet.diff(EMBEDDING_WRITABLE_KEYS.toSet)
    if unknown.nonEmpty then
      IO.pure(Left(s"embedding section write refused — unknown key(s): ${unknown.toList.sorted.mkString(", ")}"))
    else if values.isEmpty then IO.pure(Left("embedding section write refused — no keys given"))
    else
      writeLocked {
        IO.blocking {
          val existing = if os.exists(configPath) then os.read(configPath) else "{}"
          parse(existing) match
            case Left(err) =>
              Left(s"config file unparseable — refusing to rewrite for embedding update: ${err.message}")
            case Right(parsed) =>
              val section = parsed.hcursor
                .downField(EMBEDDING_SECTION)
                .focus
                .flatMap(_.asObject)
                .getOrElse(JsonObject.empty)
              val updatedSection = values.foldLeft(section) { case (acc, (k, v)) => acc.add(k, v) }
              val updated = parsed.mapObject(_.add(EMBEDDING_SECTION, Json.fromJsonObject(updatedSection)))
              AtomicJson.writeSyncRestricted(PathUtil.configJsonWritePath(PathUtil.dataRoot), updated.spaces2)
              Right(())
        }
      }
    end if
  end setEmbeddingSection

  /** 顶层节名（写侧单点）。 */
  private val EMBEDDING_SECTION = "embedding"

  /** `embedding` 节可写键白名单（keyRef 指针在册；值型凭据键不在册——同 jev）。 */
  private val EMBEDDING_WRITABLE_KEYS: List[String] =
    List("enabled", "baseUrl", "model", "keyRef", "timeoutMs")

  def embeddingWritableKeys: List[String] = EMBEDDING_WRITABLE_KEYS

  /**
   * 定向写顶层 `executor` 节（executor-registry 批 2026-10-03：统一 delegate
   * 的执行器 default / disabled）。
   *
   * 形态：整体包在 `writeLocked` 内串行；只覆写调用方
   * 显式给出的键（`None` 键原样保留）；写盘走 [[nebflow.core.AtomicJson]]；
   * 失败上浮（fail-loud）。本节不收任何凭据键。
   *
   * 值校验：`default` 必须在 [[nebflow.core.executor.ExecutorRegistry.defs]]
   * 在册；`disabled` 不得含内置执行器（[[nebflow.core.executor.ExecutorRegistry.DefaultId]]，零依赖兜底面，
   * 恒可用——registry 读取侧也会再挡一层）。
   */
  def setExecutorSection(values: Map[String, Json]): IO[Either[String, Unit]] =
    val unknown = values.keySet.diff(EXECUTOR_WRITABLE_KEYS.toSet)
    if unknown.nonEmpty then
      IO.pure(Left(s"executor section write refused — unknown key(s): ${unknown.toList.sorted.mkString(", ")}"))
    else if values.isEmpty then IO.pure(Left("executor section write refused — no keys given"))
    else
      val badDefault = values.get("default").flatMap(_.asString).filterNot { id =>
        nebflow.core.executor.ExecutorRegistry.defs.exists(_.id == id)
      }
      val disablesBuiltin = values.get("disabled").flatMap(_.asArray).exists { arr =>
        arr.exists(_.asString == Some("nebflow"))
      }
      if badDefault.isDefined then
        IO.pure(Left(s"executor section write refused — unknown default '${badDefault.get}'"))
      else if disablesBuiltin then
        IO.pure(Left("executor section write refused — the built-in executor 'nebflow' cannot be disabled"))
      else
        writeLocked {
          IO.blocking {
            val existing = if os.exists(configPath) then os.read(configPath) else "{}"
            parse(existing) match
              case Left(err) =>
                Left(s"config file unparseable — refusing to rewrite for executor update: ${err.message}")
              case Right(parsed) =>
                val section = parsed.hcursor
                  .downField(EXECUTOR_SECTION)
                  .focus
                  .flatMap(_.asObject)
                  .getOrElse(JsonObject.empty)
                val updatedSection = values.foldLeft(section) { case (acc, (k, v)) => acc.add(k, v) }
                val updated = parsed.mapObject(_.add(EXECUTOR_SECTION, Json.fromJsonObject(updatedSection)))
                AtomicJson.writeSyncRestricted(PathUtil.configJsonWritePath(PathUtil.dataRoot), updated.spaces2)
                Right(())
          }
        }
      end if
    end if
  end setExecutorSection

  /** Update one model under the same lock as all other config writes. */
  def setExecutorModel(id: String, model: String): IO[Either[String, Unit]] =
    if !Set("claude-code", "codex").contains(id) then
      IO.pure(Left("Executor does not support headless model selection"))
    else if model.length > 200 || model.exists(_.isControl) || model.trim.startsWith("-") then
      IO.pure(Left("Invalid model ID"))
    else
      writeLocked {
        IO.blocking {
          val existing = if os.exists(configPath) then os.read(configPath) else "{}"
          parse(existing) match
            case Left(err) => Left(s"config file unparseable — refusing executor model update: ${err.message}")
            case Right(parsed) =>
              val section = parsed.hcursor.downField("executor").focus.flatMap(_.asObject).getOrElse(JsonObject.empty)
              val models = section("models").flatMap(_.asObject).getOrElse(JsonObject.empty)
              val value = model.trim
              val updatedModels = if value.isEmpty then models.remove(id) else models.add(id, value.asJson)
              val updated = parsed.mapObject(
                _.add("executor", Json.fromJsonObject(section.add("models", Json.fromJsonObject(updatedModels))))
              )
              AtomicJson.writeSyncRestricted(PathUtil.configJsonWritePath(PathUtil.dataRoot), updated.spaces2)
              Right(())
        }
      }

  /** 顶层节名（写侧单点，避免字符串散落）。 */
  private val EXECUTOR_SECTION = "executor"

  /** `executor` 节可写键白名单（本面不收凭据值）。 */
  private val EXECUTOR_WRITABLE_KEYS: List[String] =
    List("default", "disabled")

  def executorWritableKeys: List[String] = EXECUTOR_WRITABLE_KEYS

  // ── 生图（imagegen）设置面（settings-panel-jev-imagegen 批 2026-10-28）──────
  //
  // 面板两形态（作者令：「seal=OFF 构建下面板显示『未编入（release 构建零痕迹）』
  // 提示位；seal=ON 构建显示正常配置面」）的数据源。🔴 边界申报：本对象是
  // **设置/状态面**，不是生图能力面——seal=OFF 的 release 构建里它照常在
  // （面板的「未编入」提示位正需要它），ImageGenSpec 的零痕迹锚（工具注册段 /
  // Nebula 交付面 / ImageGenRoutes / 前端 imagegen.js DCE）不经过这里。

  /** 顶层节名（写侧单点）。 */
  private val IMAGEGEN_SECTION = "imagegen"

  /**
   * `imagegen` 节可写键白名单（整体节替换语义的机械表达：面板每次提交**整节**
   * 的目标形态，缺键=删键）。🔴 `keyRef` 是 secrets 目录下的**名**（指针），
   * 在册；**值型凭据键一律不在册**——key 值走既有 POST/PUT /api/secrets 面
   * （0600 落盘），永不进 nebflow.json。
   */
  private val IMAGEGEN_WRITABLE_KEYS: List[String] =
    List("enabled", "defaultProvider", "providers")

  def imagegenWritableKeys: List[String] = IMAGEGEN_WRITABLE_KEYS

  /**
   * 整节校验（纯函数）：白名单越界键拒绝；providers 各项必须可解码为
   * [[nebflow.shared.ImageGenProviderConfig]] 且 `type` 在适配器注册表内
   * （typos fail-loud，不静默落盘成永不解析的死节）。返回 Left = 拒绝理由。
   */
  def validateImagegenSection(json: Json): Either[String, Unit] =
    json.asObject match
      case None => Left("imagegen section must be a JSON object")
      case Some(o) =>
        val unknown = o.keys.toSet.diff(IMAGEGEN_WRITABLE_KEYS.toSet)
        if unknown.nonEmpty then Left(s"imagegen section write refused — unknown key(s): ${unknown.mkString(", ")}")
        else
          json.as[ImageGenConfig] match
            case Left(err) => Left(s"imagegen section undecodable: ${err.getMessage}")
            case Right(cfg) =>
              val bad = cfg.providers.getOrElse(Map.empty).collect {
                case (name, pc) if nebflow.llm.imagegen.ImageGenAdapters.byType(pc.`type`).isEmpty =>
                  s"$name (type '${pc.`type`}')"
              }
              if bad.nonEmpty then
                Left(
                  s"imagegen provider type has no adapter — valid: ${nebflow.llm.imagegen.ImageGenAdapters.All.map(_.`type`).mkString(", ")}; offending: ${bad.mkString(", ")}"
                )
              else Right(())

  /**
   * 定向写顶层 `imagegen` 节（**整节替换**——面板是本节唯一写者，每次提交
   * 全量目标形态；缺键=从节里消失，无「省略=保留」歧义）。形态：整体包在
   * `writeLocked` 内串行；写盘走
   * [[nebflow.core.AtomicJson]] 限权形态（0600）；失败上浮（fail-loud）。
   * 调用方必须先过 [[validateImagegenSection]]（本函数重复校验，双保险）。
   */
  def setImagegenSection(section: Json): IO[Either[String, Unit]] =
    validateImagegenSection(section) match
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        writeLocked {
          IO.blocking {
            val existing = if os.exists(configPath) then os.read(configPath) else "{}"
            parse(existing) match
              case Left(err) =>
                Left(s"config file unparseable — refusing to rewrite for imagegen update: ${err.message}")
              case Right(parsed) =>
                // 空对象 = 整节清空（删顶层节，回到未配置态——graceful
                // degrade 方向与删节同义）；非空 = 整节替换。
                val updated = section.asObject.filter(_.isEmpty) match
                  case Some(_) => parsed.mapObject(_.remove(IMAGEGEN_SECTION))
                  case None => parsed.mapObject(_.add(IMAGEGEN_SECTION, section))
                AtomicJson.writeSyncRestricted(PathUtil.configJsonWritePath(PathUtil.dataRoot), updated.spaces2)
                Right(())
          }
        }

  /**
   * 生图设置面状态投影（GET /api/config/imagegen 的载荷；面板两形态的唯一
   * 数据源）。`compiledIn` = 编译期 seal 常量（功能开放批 2026-10-30 起缺省
   * true——面板据此显示正常配置面；显式 `STAND_IMAGEGEN=0` 构建恒 false =
   * 「未编入」提示位）；配置段热读现取；`keyConfigured`
   * 只读 secrets **在位性**（存在且非空——ImageGenService.readSecret 同判据），
   * **永不回传 key 值**。`debugLabel` 随正式开放退役为空串（线框保留，任何面
   * 不得再渲染「调试中」）。🔴 日志零 key：本投影不打日志，值的唯一可能位置是
   * secrets 文件本身（本函数不读值，只 stat 在位性）。
   */

  /** Panel toggle decoder; missing/invalid config fails closed. */
  private[nebflow] def imagegenEnabledFromConfig(raw: String): Boolean =
    parse(raw).toOption
      .flatMap(_.hcursor.downField(IMAGEGEN_SECTION).as[ImageGenConfig].toOption)
      .exists(_.isEnabled)

  /** Current panel toggle for root tool-face assembly; missing/invalid config fails closed. */
  def imagegenEnabledForToolFace: Boolean =
    try imagegenEnabledFromConfig(if os.exists(configPath) then os.read(configPath) else "{}")
    catch case _: Exception => false

  def imagegenStatus: IO[Json] = IO.blocking {
    val raw = if os.exists(configPath) then os.read(configPath) else "{}"
    val cfg = parse(raw).toOption
      .flatMap(_.hcursor.downField(IMAGEGEN_SECTION).as[ImageGenConfig].toOption)
    val providers = cfg.flatMap(_.providers).getOrElse(Map.empty)
    val providerJson = Json.fromFields(providers.map { case (name, pc) =>
      name ->
        Json.obj(
          "type" -> pc.`type`.asJson,
          "model" -> pc.model.asJson,
          "baseUrl" -> pc.baseUrl.asJson,
          "keyRef" -> pc.keyRef.asJson,
          "keyConfigured" -> imagegenKeyConfigured(pc.keyRef).asJson
        )
    })
    Json.obj(
      "compiledIn" -> nebflow.core.ImageGenSeal.CompiledIn.asJson,
      "debugLabel" -> nebflow.core.ImageGenSeal.DebugLabel.asJson,
      "configured" -> cfg.isDefined.asJson,
      "enabled" -> cfg.exists(_.isEnabled).asJson,
      "defaultProvider" -> cfg.flatMap(_.defaultProvider).asJson,
      "providers" -> providerJson
    )
  }

  /**
   * keyRef 指针的**在位性**判定（存在且非空即已配——与
   * [[nebflow.llm.imagegen.ImageGenService.readSecret]] 的腿关判据逐字同源，
   * 面板显示与引擎判定永不漂移）。只 stat/读空判，不回传值。
   */
  private def imagegenKeyConfigured(keyRef: Option[String]): Boolean =
    keyRef.filter(_.trim.nonEmpty).exists { name =>
      try
        val p = PathUtil.dataRoot / "secrets" / name.trim
        os.exists(p) && os.read(p).trim.nonEmpty
      catch case _: Exception => false
    }

  /**
   * 服务商面板的**类型域 + 协议适配器**枚举投影（`GET /api/config/provider-types`
   * 的载荷；provider-add-tabs 批 2026-11-06）。
   *
   * 为什么必须有这个端点：设置页的「服务商」面板按类型切换字段套（LLM / 生图），
   * 面板里的类型标签与各类型的协议下拉都必须是**后端枚举的投影**，而不是
   * 前端再拼一份字面量表（旧面正是如此：`PROVIDER_TYPES = ['cogview', …]` 等
   * 前端硬编码，与后端 `ImageGenAdapters.All` 是同一事实的两份拷贝；jev 域
   * 连同 `adapters.jev` 已随 jev-retire 批 2026-11-16 退役）。
   *
   * 单源三处，本函数只做汇编，零新字面量：
   *   · `domains`            ← [[nebflow.shared.ProviderDomains.All]]
   *   · `adapters.llm`       ← [[nebflow.shared.LlmProtocol]]（anthropic / openai）
   *   · `adapters.imagegen`  ← `ImageGenAdapters.All.map(_.type)`
   * 载荷零配置值、零密钥——纯枚举（与其它 config GET 同 owner 门槛）。
   */
  def providerTypes: IO[Json] = IO.blocking {
    Json.obj(
      "domains" -> Json.fromValues(
        nebflow.shared.ProviderDomains.All.map { d =>
          Json.obj(
            "id" -> d.asJson,
            "enabledSwitch" -> nebflow.shared.ProviderDomains.hasEnabledSwitch(d).asJson
          )
        }
      ),
      "adapters" -> Json.obj(
        "llm" -> Json.fromValues(
          List(nebflow.shared.LlmProtocol.Anthropic, nebflow.shared.LlmProtocol.OpenAI).map(p => p.name.asJson)
        ),
        "imagegen" -> Json.fromValues(nebflow.llm.imagegen.ImageGenAdapters.All.map(a => a.`type`.asJson))
      )
    )
  }

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
   * 删除落地校验（provider-config-vs-runtime-mismatch 批，2026-11-05 作者令）。
   *
   * 病：`nebflow.json` 有多名**绕过 `writeLocked`** 的手术式写者
   * （`PluginRegistry.mutateNebflowJson`、`PluginDispatchPolicy`、
   * `PluginBlockPolicy`、`SocialChannels`、`FeishuCredentials` 等，均为
   * 「读全量 → 改自己的子树 → 原子写回」）。它们与 `updateConfig` 的
   * read-modify-write 无锁交叉 ⇒ 读到旧底稿的一方整文件覆写时，把刚删掉的
   * provider **原样写回**（lost update）。今日病例：设置页删 kimi → 磁盘仍
   * 有 kimi ⇒ 运行期按配置照常调用已被"删掉"的 provider，界面上却显示已删。
   *
   * 本函数 = 写后立刻回读、逐名核对「声明删除的 provider 是否真的不在了」。
   * 返回仍存在于磁盘的名字列表（空 = 落地成功）。调用方据此**上浮失败**
   * （fail-loud，不许静默沿用旧快照）。
   */
  private[nebflow] def undeletedProviders(declared: List[String]): List[String] =
    if declared.isEmpty then Nil
    else
      val onDisk =
        if !os.exists(configPath) then Set.empty[String]
        else
          parse(os.read(configPath)).toOption
            .flatMap(
              _.hcursor
                .downField("llm")
                .downField("providers")
                .as[Option[Map[String, Json]]]
                .toOption
                .flatten
            )
            .map(_.keySet)
            .getOrElse(Set.empty)
      declared.filter(onDisk.contains)

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
    existingProviders.keys.toSet
      .diff(incomingProviders.keys.toSet)
      .toList
      .sorted
      .foldLeft((List.empty[(String, String)], added)) { case ((acc, candidates), old) =>
        candidates.find(newName => sameProvider(existingProviders(old), incomingProviders(newName))) match
          case Some(newName) => ((old, newName) :: acc, candidates - newName)
          case None => (acc, candidates)
      }
      ._1
      .reverse
  end detectProviderRenames

  /**
   * Restore masked/empty apiKeys on renamed providers. The frontend sends a
   * renamed provider with apiKey "***" (redacted round-trip); mergeConfig's
   * secret-preserving "***" rule only fires on same-key recursive merge, so a
   * delete-old + add-new rename would write the mask literally — and the old
   * provider (holding the real key) is being deleted. Copy the real key from
   * the existing old-name provider into the incoming new-name one.
   */
  private def restoreRenamedSecrets(incoming: String, existing: String, renames: List[(String, String)]): String =
    if renames.isEmpty then incoming
    else
      def providerApiKey(raw: String, name: String): Option[String] =
        parse(raw).toOption.flatMap(
          _.hcursor
            .downField("llm")
            .downField("providers")
            .downField(name)
            .downField("apiKey")
            .as[Option[String]]
            .toOption
            .flatten
        )
      renames.foldLeft(incoming) { (acc, rename) =>
        val (oldName, newName) = rename
        (providerApiKey(acc, newName), providerApiKey(existing, oldName)) match
          case (Some(masked), Some(real)) if masked == "***" || masked.isEmpty =>
            (for
              json <- parse(acc).toOption
              root <- json.asObject
              llmObj <- json.hcursor.downField("llm").focus.flatMap(_.asObject)
              provObj <- llmObj.toMap.get("providers").flatMap(_.asObject)
              newProv <- provObj.toMap.get(newName).flatMap(_.asObject)
            yield
              val withKey = Json.fromFields(newProv.toMap.updated("apiKey", real.asJson))
              val newProviders = Json.fromFields(provObj.toMap.updated(newName, withKey))
              val newLlm = Json.fromFields(llmObj.toMap.updated("providers", newProviders))
              Json.fromFields(root.toMap.updated("llm", newLlm)).noSpaces
            ).getOrElse(acc)
          case _ => acc
        end match
      }
  end restoreRenamedSecrets

  /**
   * Rewrite `oldName/…` refs to `newName/…` in an agent.json model config.
   * Structural mirror of scrubAgentJson — refs are rewritten in place, the
   * model selection survives the rename.
   */
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

  // def (not val) — 同 `configPath` 的既有理由（`:31-33`）：val 会把**首个触碰
  // ConfigService 对象时**的 dataRoot 冻进路径（object 初始化逐 val 求值），
  // 后续 setDataRoot 重定向失效 ⇒ 跨 suite 顺序相关的假红（实测：新 spec 在自己
  // 的隔离 home 下触碰本对象 ⇒ 本路径被冻到该临时目录，ConfigServiceSpec 的
  // preset 改写断言随即失败）。生产端 dataRoot 启动即定，行为零变。
  private def presetsPath: os.Path = PathUtil.dataRoot / "model-presets.json"

  /** Atomically write a JSON file (temp + rename), mirroring PresetStore.save. */
  private def atomicWriteJson(path: os.Path, json: Json): Unit =
    os.makeDir.all(path / os.up)
    val tmp = path / os.up / s".${path.last}.${System.nanoTime()}.tmp"
    os.write(tmp, json.noSpaces)
    os.move.over(tmp, path)

  /**
   * Apply a JSON transform to the preset catalog face(s). Skips absent files —
   * provider cleanup must NOT initialize the presets store (PresetStore.load
   * owns the create/re-init semantics). Never clobbers an unparseable file.
   * Writes back only when something actually changed.
   *
   * preset→chain 迁移批（2026-10-30）：变换**同时**落在 legacy 目录与迁移后的
   * 链注册表（`model-chains.json`，chains 槽位）——provider 删除/改名的链清洗
   * 在两个面上等价，不随迁移产生清洗死角。transform 按各面的槽位名
   * （"presets" / "chains"）参数化。
   */
  private def editPresets(transform: String => Json => Json): IO[Unit] = IO.blocking {
    List(presetsPath -> "presets", nebflow.core.presets.ModelChainRegistry.chainsPath() -> "chains")
      .foreach { (path, field) =>
        if os.exists(path) then
          parse(os.read(path)) match
            case Right(json) =>
              val updated = transform(field)(json)
              if updated != json then atomicWriteJson(path, updated)
            case Left(_) => ()
      }
  }

  /**
   * Transform every preset's {preferred, fallbacks} chain. Presets whose
   * chain is unchanged are left byte-identical; the file object is rebuilt
   * only when at least one preset actually changed. `field` names the catalog
   * slot ("presets" on the legacy file, "chains" on the migrated registry).
   */
  private def mapPresetChains(
    json: Json,
    field: String,
    f: (Option[String], List[String]) => (Option[String], List[String])
  ): Json =
    json.hcursor.downField(field).focus.flatMap(_.asObject) match
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
            .map(o => Json.fromFields(o.toMap.updated(field, Json.fromFields(newPresets))))
            .getOrElse(json)

  /**
   * Scrub deleted-provider refs from every preset chain. A scrubbed
   * preferred promotes from the fallbacks head (mirrors the global-chain
   * scrub); a fully emptied chain resolves to the default preset / global
   * chain at agent-load time.
   */
  private def scrubPresetRefs(deleted: List[String]): IO[Unit] =
    editPresets(field =>
      json =>
        deleted.foldLeft(json) { (acc, provider) =>
          val prefix = s"$provider/"
          mapPresetChains(
            acc,
            field,
            (preferred, fallbacks) =>
              val prefTouched = preferred.exists(_.startsWith(prefix))
              val fbs = fallbacks.filterNot(_.startsWith(prefix))
              if prefTouched then (fbs.headOption, fbs.drop(1)) else (preferred, fbs)
          )
        }
    )

  /** Rewrite renamed-provider refs old→new in every preset chain. */
  private def rewritePresetRefs(renames: List[(String, String)]): IO[Unit] =
    editPresets(field =>
      json =>
        renames.foldLeft(json) { (acc, rename) =>
          val (oldName, newName) = rename
          val oldPrefix = s"$oldName/"; val newPrefix = s"$newName/"
          def rw(ref: String) = if ref.startsWith(oldPrefix) then newPrefix + ref.stripPrefix(oldPrefix) else ref
          mapPresetChains(acc, field, (preferred, fallbacks) => (preferred.map(rw), fallbacks.map(rw)))
        }
    )
end ConfigService
