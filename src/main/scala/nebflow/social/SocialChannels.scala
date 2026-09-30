package nebflow.social

import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.{AtomicJson, CredentialFileAcl}
import nebflow.shared.{NebflowLogger, PathUtil}

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * Social interface channels — domain layer for the REST face (socpanel batch,
 * 2026-09-19; design = `socremote-design` n-5c95367d, arch §7.2/§7.3/§7.4).
 *
 * Contract (arch §7.3, unchanged):
 *   GET  /api/social/channels           → 200 {"channels": {<id>: {enabled, fields}}}
 *   POST /api/social/channels/<id>      → 200 {"ok": true, "probe": {<field>: {exists, modeOk, readable}}}
 *   GET  /api/social/probe?channel=<id> → 200 {"adapterRegistered": false, "secrets": {...}}
 *
 * Storage: `~/.nebflow/nebflow.json` top-level key `socialChannels`
 * (surgical read-modify-write — every other key of the file is preserved).
 * An install without the key reads as `{}` (arch §7.2 backward compatibility).
 *
 * 🔴 Two hard rules, both pinned by tests:
 *   ① The responses NEVER carry secret content. Secret fields keep a PATH
 *      (`<key>_ref`, rendered `~/.nebflow/secrets/<name>` on a default install,
 *      the instance's own absolute root otherwise) and the read side only ever
 *      answers the mechanical triple `{exists, modeOk, readable}`.
 *   ② A credential write goes through the EXISTING permission-narrowing module
 *      (`core/CredentialFileAcl`), never a second private copy of the ACL
 *      logic. `restrict` throwing is reported as an explicit failure
 *      (`403 secret_mode`) — never swallowed.
 *   ③ The plaintext never exists inside a not-yet-narrowed file: a new
 *      credential file is created `rw-------` from its first byte, an existing
 *      one is narrowed BEFORE the new plaintext is written into it, and a
 *      failure removes whatever this call created or wrote (finding F1 of
 *      `socpanel-verify`, 2026-09-19 — pinned by `SocialChannelsSpec`).
 *
 * Phase 1: no channel has an adapter. `adapterRegistered` is `false` for every
 * channel here as well as in the frontend definition layer (the render
 * authority), so nothing in this batch can present a live channel.
 */
object SocialChannels:

  private val logger = NebflowLogger.forName("nebflow.social.channels")

  /** One config field. `kind` mirrors the frontend definition layer. */
  final case class FieldSpec(
    key: String,
    kind: String,
    required: Boolean,
    pattern: Option[String],
    secretName: Option[String] = None
  ):
    def isSecret: Boolean = kind == "secret"

    /** Config key holding the value: secrets store a path, never the value. */
    def storedKey: String = if isSecret then s"${key}_ref" else key

  /** One channel card's server-side schema. */
  final case class ChannelSpec(id: String, fields: List[FieldSpec]):
    def field(key: String): Option[FieldSpec] = fields.find(_.key == key)
    def secretFields: List[FieldSpec] = fields.filter(_.isSecret)

  /**
   * Field schema — a mirror of `web/js/socialChannels.js` (the UI copy is UX
   *  feedback only; THIS one is the enforcement point). Feishu and Lark share
   *  one card with a region choice (author ruling 2026-09-19).
   */
  val channels: List[ChannelSpec] = List(
    ChannelSpec(
      "wechat",
      List(
        FieldSpec("app_id", "text", required = true, pattern = Some("^wx[0-9a-f]{16}$")),
        FieldSpec("app_secret", "secret", required = true, pattern = None, Some("social-wechat-app-secret")),
        FieldSpec("token", "secret", required = true, pattern = None, Some("social-wechat-token")),
        FieldSpec("aes_key", "secret", required = true, pattern = None, Some("social-wechat-aes-key"))
      )
    ),
    ChannelSpec(
      "feishu",
      List(
        FieldSpec("app_id", "text", required = true, pattern = Some("^cli_[0-9a-zA-Z]{16,}$")),
        FieldSpec("app_secret", "secret", required = true, pattern = None, Some("social-feishu-app-secret")),
        // feiscanbind (approved pending-decision ⑤, 2026-09-27): optional. The
        // long-connection path never consumes this field (read side
        // `FeishuCredentials` reads only app_id/app_secret_ref; the runtime runs
        // with an empty token — `FeishuChannel` dispatch), so requiring it would
        // block a scan-bind-created app (which has none) from ever reaching
        // `verified`. The manual-fill path keeps the field: optional now, exact
        // same write path as before.
        FieldSpec("verification_token", "secret", required = false, pattern = None,
          Some("social-feishu-verification-token")),
        FieldSpec("encrypt_key", "secret", required = false, pattern = None, Some("social-feishu-encrypt-key")),
        FieldSpec("region", "select", required = true, pattern = Some("^(feishu|lark)$")),
        // feishubridge batch: member-level allowlist SLOT (feishu is a tenant model
        // — every tenant member can reach the gateway by default). Empty = no
        // restriction, which is the shipped default; filling it (comma-separated
        // open_ids) turns the bridge gate fail-closed. Whether to enable remains
        // the author's call — the slot exists so enabling later needs no migration.
        FieldSpec("allowed_open_ids", "text", required = false, pattern = None)
      )
    ),
    ChannelSpec(
      "telegram",
      List(
        FieldSpec("bot_token", "secret", required = true, pattern = None, Some("social-telegram-bot-token")),
        FieldSpec("chat_id", "text", required = true, pattern = Some("^-?\\d+$")),
        FieldSpec("api_base", "url", required = false, pattern = Some("^https?://"))
      )
    )
  )

  def channelIds: List[String] = channels.map(_.id)

  def channel(id: String): Option[ChannelSpec] = channels.find(_.id == id)

  /** Failure vocabulary mapped to the contract's error codes (arch §7.3). */
  enum Failure:
    case UnknownChannel(id: String)
    case InvalidField(field: String, reason: String)
    case SecretMode(field: String, reason: String)
    case Io(reason: String)

  // ─────────────────────────── read side ───────────────────────────

  private def configPath(root: os.Path): os.Path =
    PathUtil.configJsonWritePath(root)

  private def parseConfig(root: os.Path): Json =
    val p = configPath(root)
    if !Files.exists(p.toNIO) then Json.obj()
    else
      io.circe.parser.parse(os.read(p)) match
        case Right(j) => j
        case Left(_) => Json.obj() // unreadable config: the READ side answers `{}`; writes refuse below

  /** The stored `socialChannels` subtree; absent key ⇒ `{}` (old installs, arch §7.2). */
  def readChannels(root: os.Path): Json =
    parseConfig(root).hcursor.downField("socialChannels").focus.getOrElse(Json.obj())

  /**
   * The RAW stored `enabled` of one channel: `None` = the entry (or the store)
   * never carried the key. Deliberately distinct from `false`.
   *
   * 🔴 This distinction IS the feishu-boot-seal fix. On the READ side an absent
   * key is read as "not enabled" (`channelsJson` / `isEnabled` — unchanged, and
   * correct: an install that never enabled a channel is not enabled). On the
   * WRITE side the same conflation was a defect: a request body that did not
   * mention `enabled` was read with `getOrElse(false)` and therefore wrote
   * `false` into the user's config — the engine closing the user's switch on
   * its own authority. A writer must preserve what is here, so it has to be
   * able to tell "stored false" apart from "never stored".
   */
  private def storedEnabled(root: os.Path, id: String): Option[Boolean] =
    readChannels(root).hcursor.downField("channels").downField(id)
      .downField("enabled").as[Boolean].toOption

  /**
   * GET /api/social/channels payload. Secret fields only ever surface their
   *  PATH (the stored `_ref` value) — never a credential.
   */
  // (W1 provisional shim: `registered` parameter + per-channel
  // `adapterRegistered` keys ported from main's feishu-adapter-activation
  // batch; the default (empty) keeps the phase-1 constant-false answer.)
  def channelsJson(root: os.Path, registered: Set[String] = Set.empty): Json =
    val stored = readChannels(root).hcursor.downField("channels").focus.getOrElse(Json.obj())
    val out = channelIds.map { id =>
      val entry = stored.hcursor.downField(id).focus.getOrElse(Json.obj())
      val enabled = entry.hcursor.downField("enabled").as[Boolean].getOrElse(false)
      val fields = entry.hcursor.downField("fields").focus.getOrElse(Json.obj())
      id -> Json.obj(
        "enabled" -> enabled.asJson,
        "fields" -> fields,
        "adapterRegistered" -> registered.contains(id).asJson
      )
    }
    Json.obj("channels" -> Json.fromFields(out), "adapterRegistered" -> registered.nonEmpty.asJson)

  /**
   * The mechanical triple for one stored path: `{exists, modeOk, readable}`.
   *  Content is never read.
   */
  private def probePath(path: os.Path, osName: String): Json =
    val nio: Path = path.toNIO
    val exists =
      try Files.exists(nio)
      catch case _: Exception => false
    if !exists then Json.obj("exists" -> false.asJson, "modeOk" -> false.asJson, "readable" -> false.asJson)
    else
      val modeOk =
        if CredentialFileAcl.isWindows(osName) then
          // Windows: the mode IS the DACL. Read the real mask and compare it with
          // the expected owner-only bit set (the credaacl batch's definition
          // layer). Unavailable / unreadable is reported as NOT ok — never as
          // "clean by default". (True-machine reading is unproven on this host:
          // macOS only; the branch is exercised by an injected-osName test.)
          try
            CredentialFileAcl.systemWindowsAcl
              .readAcl(nio)
              .exists(m => m.missing(CredentialFileAcl.expectedBits).isEmpty)
          catch case _: Exception => false
        else
          try PosixFilePermissions.toString(Files.getPosixFilePermissions(nio)) == CredentialFileAcl.PosixMode
          catch case _: Exception => false
      val readable =
        try Files.isReadable(nio)
        catch case _: Exception => false
      Json.obj("exists" -> true.asJson, "modeOk" -> modeOk.asJson, "readable" -> readable.asJson)

    end if

  end probePath

  /**
   * Rendered form of the data-root-token, i.e. what a default install sees.
   *  Kept in step with the rest of the product: a default install renders
   *  `~/.nebflow`, an isolated instance renders its own absolute root.
   */
  private def renderRoot(root: os.Path): String =
    if root == os.home / nebflow.shared.Branding.homeDirName then s"~/${nebflow.shared.Branding.homeDirName}"
    else root.toString

  /** Inverse of [[renderRoot]]: a stored reference back to a real path. */
  private def resolveRef(root: os.Path, ref: String): os.Path =
    val rendered = renderRoot(root)
    if ref.startsWith(rendered + "/") then os.Path(root.toString + ref.drop(rendered.length), os.pwd)
    else if ref.startsWith("/") then os.Path(ref, os.pwd)
    else os.Path(root.toString + "/" + ref, os.pwd)

  private def storedFields(root: os.Path, id: String): Json =
    readChannels(root).hcursor.downField("channels").downField(id).downField("fields").focus.getOrElse(Json.obj())

  /** Probe every secret field of one channel that has a stored reference. */
  private def probeOf(root: os.Path, spec: ChannelSpec): Json =
    val fields = storedFields(root, spec.id)
    val osName = CredentialFileAcl.currentOsName
    val triples = spec.secretFields.map { f =>
      fields.hcursor.downField(f.storedKey).as[String].toOption.filter(_.nonEmpty) match
        case Some(ref) => f.key -> probePath(resolveRef(root, ref), osName)
        case None => f.key -> Json.obj("exists" -> false.asJson, "modeOk" -> false.asJson, "readable" -> false.asJson)
    }
    Json.fromFields(triples)

  /**
   * GET /api/social/probe?channel=<id>. `adapterRegistered` is a hard `false`
   *  for the whole of phase 1 — the render authority is the frontend
   *  definition layer, and both sides agree there is no adapter yet.
   */
  def probeJson(root: os.Path, id: String, registered: Set[String] = Set.empty): Either[Failure, Json] =
    channel(id) match
      case None => Left(Failure.UnknownChannel(id))
      case Some(spec) => Right(Json.obj("adapterRegistered" -> registered.contains(id).asJson, "secrets" -> probeOf(root, spec)))

  // ─────────────────────────── write side ───────────────────────────

  /**
   * Where a secret for `f` of `spec` lives. Kept under the data root's
   *  `secrets/` subtree and named after the field (arch §7.2).
   */
  private def secretPath(root: os.Path, spec: ChannelSpec, f: FieldSpec): os.Path =
    val name = f.secretName.getOrElse(s"social-${spec.id}-${f.key}")
    root / "secrets" / name

  /**
   * Production narrowing step: the shared module's semantics (POSIX
   *  `rw-------`; Windows runs its DACL ladder). Injectable so the regression
   *  test can make the narrowing fail and pin the "no half state" property —
   *  production never passes anything but the shared module, and there is no
   *  private second copy of the ACL logic in this file.
   */
  private def defaultRestrict(path: Path): Unit = CredentialFileAcl.restrict(path)

  /**
   * Create an EMPTY file with owner-only access `rw-------` (F1: the mode comes
   *  from the creation attribute, never from a later narrowing step — the
   *  "write, then narrow" window reported by `socpanel-verify` cannot exist).
   *
   *  A filesystem without POSIX permission attributes (Windows) refuses the
   *  attribute — the file is then created with the ACL it inherits and
   *  [[CredentialFileAcl.restrict]]'s DACL ladder is the narrowing step there,
   *  exactly as before this fix. Declared, not silently assumed: the fallback
   *  also clears a possibly half-created file so the retry cannot trip over
   *  `FileAlreadyExistsException`.
   */
  private def createOwnerOnly(path: Path): Unit =
    try
      Files.createFile(
        path,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(CredentialFileAcl.PosixMode))
      )
    catch
      case _: UnsupportedOperationException =>
        try Files.deleteIfExists(path)
        catch case _: Exception => ()
        Files.createFile(path)

  /**
   * Write one credential. Order — this order IS the F1 fix (finding F1 of
   *  `socpanel-verify`; before it the plaintext was written into the final path
   *  under the default umask and narrowed only afterwards, so a throwing
   *  `restrict` left a wide-mode plaintext file behind):
   *
   *   ① a target that does not exist yet is created with mode `rw-------` from
   *      its very first byte ([[createOwnerOnly]]);
   *   ② a target that already exists is NARROWED BEFORE any new plaintext can
   *      land in it (it may be wide-mode — e.g. left behind by the pre-F1
   *      code path);
   *   ③ the new plaintext is written;
   *   ④ the EXISTING `CredentialFileAcl` semantics run as the authoritative
   *      narrowing step (POSIX re-assert; Windows DACL ladder).
   *
   *  There is therefore no instant at which the plaintext sits in a
   *  not-yet-narrowed file. The write stays IN PLACE rather than going through
   *  a tmp file + rename: the inode is preserved, which is what the `nf-file`
   *  credential-inode snapshot (`NfPathPolicy.credentialInodes`) keys on — a
   *  rename would hand the credential a fresh inode on every write.
   *
   *  Any failure is on the error channel (`403 secret_mode`, the existing
   *  mapping) and names the path. Zero residue: a file we created or wrote into
   *  is removed again; a pre-existing target we never wrote into (step ② failed)
   *  is left exactly as found — we cannot have added plaintext to it. A
   *  credential is never reported as stored when it is not.
   */
  private def writeSecret(
    root: os.Path,
    spec: ChannelSpec,
    f: FieldSpec,
    plain: String,
    restrict: Path => Unit
  ): Either[Failure, Unit] =
    val target = secretPath(root, spec, f).toNIO
    var ours = false // the file currently at `target` was created or written by THIS call
    try
      Files.createDirectories(target.getParent)
      if Files.exists(target) then restrict(target) // ② narrow the pre-existing file BEFORE the plaintext
      else createOwnerOnly(target) // ① created owner-only from its very first byte
      ours = true
      Files.write( // ③ the plaintext, into an already owner-only file
        target,
        plain.getBytes("UTF-8"),
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      restrict(target) // ④ the authoritative narrowing step — shared module, never swallowed
      Right(())
    catch
      case e: Exception =>
        val residual =
          if ours then
            try if Files.deleteIfExists(target) then "deleted" else "absent"
            catch case _: Exception => "undeletable"
          else "pre-existing-untouched"
        Left(
          Failure.SecretMode(
            f.key,
            s"could not store credential for ${spec.id}.${f.key}: " +
              s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")} " +
              s"(path=$target residual=$residual)"
          )
        )

    end try

  end writeSecret

  /**
   * POST /api/social/channels/<id>.
   *
   * Body: `{"enabled": bool, "fields": {...}}`. Secret fields arrive as
   * PLAINTEXT under their own key and appear in this request body only; what
   * is persisted is the path. Fields absent from the body keep their stored
   * value (surgical merge — a toggle flip cannot wipe a schema).
   *
   * 🔴 `enabled` is the USER's switch (feishu-boot-seal batch, 2026-09-28).
   * The body key is honoured only when it is actually there: a body that omits
   * `enabled` is a call with NO opinion about the switch, so the STORED value
   * survives untouched — and a store that never carried the key does not gain
   * one. This used to be `getOrElse(false)` on the request body, i.e. a body
   * without the key silently CLOSED the channel (observed live: a POST with no
   * `enabled` flipped a verified channel to `false` with zero log lines). The
   * engine never closes a switch on the user's behalf — the same red line as
   * "the engine never makes the dispatcher's or the user's decision for them".
   *
   * `restrict` is the credential-narrowing step; production leaves it at
   * [[defaultRestrict]] (the shared `CredentialFileAcl` module) and only the
   * F1 regression test injects a failing one.
   */
  def save(root: os.Path, id: String, body: Json, restrict: Path => Unit = defaultRestrict): Either[Failure, Json] =
    channel(id) match
      case None => Left(Failure.UnknownChannel(id))
      case Some(spec) =>
        val incoming = body.hcursor.downField("fields").focus.getOrElse(Json.obj())
        // 1. validate the present plaintext fields against their patterns
        val plainPairs = spec.fields.filterNot(_.isSecret).flatMap { f =>
          incoming.hcursor.downField(f.key).as[String].toOption.map(v => f -> v)
        }
        plainPairs.find { case (f, v) => v.nonEmpty && f.pattern.exists(p => !v.matches(p)) } match
          case Some((f, _)) =>
            Left(Failure.InvalidField(f.key, s"value does not match ${f.pattern.getOrElse("")}"))
          case None =>
            // 2. write the credentials that were supplied in this request
            val secretWrites = spec.secretFields.flatMap { f =>
              incoming.hcursor.downField(f.key).as[String].toOption.filter(_.nonEmpty).map(f -> _)
            }
            val writeResults =
              secretWrites.map { case (f, plain) => writeSecret(root, spec, f, plain, restrict) }
            writeResults.collectFirst { case Left(err) => err } match
              case Some(err) => Left(err)
              case None =>
                // 3. merge the config subtree: paths for secrets, values for the rest
                val existing = storedFields(root, id)
                val secretRefs = spec.secretFields.flatMap { f =>
                  if secretWrites.exists(_._1.key == f.key) then
                    Some(
                      f.storedKey -> Json.fromString(
                        s"${renderRoot(root)}/secrets/" +
                          f.secretName.getOrElse(s"social-${spec.id}-${f.key}")
                      )
                    )
                  else None
                }
                val plainJson = plainPairs.map { case (f, v) => f.key -> Json.fromString(v) }
                val merged = Json.fromFields(
                  existing.asObject.getOrElse(JsonObject.empty).toList ++ secretRefs ++ plainJson
                )
                // 4. the user's switch: a body that SAYS something about it is
                // honoured verbatim; a body that does not mention it leaves the
                // stored value — and a key that was never stored stays ABSENT
                // rather than materialising as `false`. The old
                // `getOrElse(false)` closed the channel on a body that simply
                // had no opinion: the engine making the user's decision.
                val enabled: Option[Boolean] =
                  body.hcursor.downField("enabled").as[Boolean].toOption.orElse(storedEnabled(root, id))
                val entry = Json.fromJsonObject(
                  JsonObject.fromIterable(
                    enabled.map(b => "enabled" -> b.asJson).toList ++
                      List(
                        "fields" -> merged,
                        "updatedAt" -> (System.currentTimeMillis() / 1000).asJson
                      )
                  )
                )
                persist(root, id, entry) match
                  case Left(err) => Left(err)
                  case Right(_) =>
                    Right(Json.obj("ok" -> true.asJson, "probe" -> probeOf(root, spec)))

            end match

        end match

  /**
   * Surgical `socialChannels.channels.<id>` rewrite: every other key of
   * nebflow.json survives (same shape as PluginBlockPolicy's writer).
   *
   * 🔴 This writer is the ONLY producer of the `version:1` + `updatedAt`
   * (epoch-second) entry shape, and it used to be completely SILENT — the
   * live incident (2026-09-28 18:00:06) showed the user's `enabled:true`
   * flipped to `false` with zero log lines in the window, so the write could
   * not be attributed after the fact. A config write is a user-visible state
   * change; it now says so (channel id only — never a field value, never a
   * credential; `app_secret` lives behind `_ref` and is not touched here).
   */
  private def persist(root: os.Path, id: String, entry: Json): Either[Failure, Unit] =
    val p = configPath(root)
    try
      Files.createDirectories(p.toNIO.getParent)
      val current = if Files.exists(p.toNIO) then os.read(p) else "{}"
      io.circe.parser.parse(current) match
        case Left(err) =>
          Left(Failure.Io(s"config unparseable — refusing to rewrite for a social channel: ${err.message}"))
        case Right(rootJson) =>
          val social = rootJson.hcursor.downField("socialChannels").focus.getOrElse(Json.obj())
          val channelsObj = social.hcursor.downField("channels").focus.getOrElse(Json.obj())
          val previous =
            channelsObj.hcursor.downField(id).downField("enabled").as[Boolean].toOption
          val next = entry.hcursor.downField("enabled").as[Boolean].toOption
          val nextChannels = Json.fromJsonObject(
            channelsObj.asObject.getOrElse(JsonObject.empty).add(id, entry)
          )
          val nextSocial = Json.fromJsonObject(
            social.asObject.getOrElse(JsonObject.empty).add("version", Json.fromInt(1)).add("channels", nextChannels)
          )
          val out = Json.fromJsonObject(
            rootJson.asObject.getOrElse(JsonObject.empty).add("socialChannels", nextSocial)
          )
          AtomicJson.writeSync(p, out.noSpaces)
          logger.infoSync(
            s"socialChannels: channel '$id' config written (enabled ${previous.map(_.toString).getOrElse("<absent>")} -> ${next.map(_.toString).getOrElse("<absent>")})"
          )
          Right(())
      end match
    catch case e: Exception => Left(Failure.Io(s"could not persist social channel config: ${e.getMessage}"))
    end try
  end persist

  // ───────────────────── activation gate (feishubridge batch) ─────────────────────
  // (W1 provisional shim: ported from main's SocialChannels -- the main-only
  // FeishuBridgePlugin/FeishuChannel/FeishuScanBind files consume this API.
  // The FieldSpec/channelsJson/probeJson signature hunks stay with this file's
  // owning wave.)

  /** The stored `enabled` flag of one channel (absent ⇒ false). */
  def isEnabled(root: os.Path, id: String): Boolean =
    readChannels(root).hcursor.downField("channels").downField(id)
      .downField("enabled").as[Boolean].getOrElse(false)

  private def tripleAllTrue(j: Json): Boolean =
    List("exists", "modeOk", "readable").forall(k => j.hcursor.downField(k).as[Boolean].getOrElse(false))

  /** Config verification for the activate gate: the card is verified when every
    *  REQUIRED field is present and valid — a plain field matches its pattern,
    *  a secret field has a stored `_ref` whose probe triple is all-true.
    *  Purely mechanical (files and patterns; content is never touched), so the
    *  closed loop `enabled ∧ verified ⇒ adapter registered` is testable
    *  offline. Unknown channel ⇒ not verified. */
  def verified(root: os.Path, id: String): Boolean =
    channel(id).exists { spec =>
      val fields = storedFields(root, id)
      val osName = CredentialFileAcl.currentOsName
      spec.fields.filter(_.required).forall { f =>
        if f.isSecret then
          fields.hcursor.downField(f.storedKey).as[String].toOption.filter(_.nonEmpty)
            .exists(ref => tripleAllTrue(probePath(resolveRef(root, ref), osName)))
        else
          fields.hcursor.downField(f.key).as[String].toOption.filter(_.nonEmpty)
            .exists(v => f.pattern.forall(p => v.matches(p)))
      }
    }

  // ───────────────── feishu panel face (feishu-bind batch, 2026-09-27) ─────────────────

  /** The channel-entry key holding the auto-bind default session (feishu, C4).
    * A sibling of `enabled`/`fields` on the channel entry — deliberately NOT a
    * card field, so the save path (which validates and rewrites `fields`) can
    * never touch or wipe it. */
  val DefaultSessionKey = "defaultSessionId"

  /** The stored default session of one channel (C4): None = unset. */
  def defaultSessionId(root: os.Path, id: String): Option[String] =
    readChannels(root).hcursor.downField("channels").downField(id)
      .downField(DefaultSessionKey).as[String].toOption.map(_.trim).filter(_.nonEmpty)

  /** Persist (Some) or clear (None) the default session (C4: set = durable).
    * Surgical merge into the channel entry — `enabled`, `fields` and every
    * other sibling survive untouched (same surgical writer as [[persist]]). A
    * blank value is stored as a clear (unset), never as an empty string. */
  def setDefaultSessionId(root: os.Path, id: String, sessionId: Option[String]): Either[Failure, Unit] =
    channel(id) match
      case None => Left(Failure.UnknownChannel(id))
      case Some(_) =>
        val entry = readChannels(root).hcursor.downField("channels").downField(id).focus.getOrElse(Json.obj())
        val obj = entry.asObject.getOrElse(JsonObject.empty)
        val next = sessionId.map(_.trim).filter(_.nonEmpty) match
          case Some(sid) => obj.add(DefaultSessionKey, Json.fromString(sid))
          case None      => obj.remove(DefaultSessionKey)
        persist(root, id, Json.fromJsonObject(next))

end SocialChannels
