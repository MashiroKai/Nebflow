package nebflow.social

import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.{AtomicJson, CredentialFileAcl, PathUtil}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions

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
 *
 * Phase 1: no channel has an adapter. `adapterRegistered` is `false` for every
 * channel here as well as in the frontend definition layer (the render
 * authority), so nothing in this batch can present a live channel.
 */
object SocialChannels:

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

  /** Field schema — a mirror of `web/js/socialChannels.js` (the UI copy is UX
    *  feedback only; THIS one is the enforcement point). Feishu and Lark share
    *  one card with a region choice (author ruling 2026-09-19). */
  val channels: List[ChannelSpec] = List(
    ChannelSpec("wechat", List(
      FieldSpec("app_id", "text", required = true, pattern = Some("^wx[0-9a-f]{16}$")),
      FieldSpec("app_secret", "secret", required = true, pattern = None, Some("social-wechat-app-secret")),
      FieldSpec("token", "secret", required = true, pattern = None, Some("social-wechat-token")),
      FieldSpec("aes_key", "secret", required = true, pattern = None, Some("social-wechat-aes-key"))
    )),
    ChannelSpec("feishu", List(
      FieldSpec("app_id", "text", required = true, pattern = Some("^cli_[0-9a-zA-Z]{16,}$")),
      FieldSpec("app_secret", "secret", required = true, pattern = None, Some("social-feishu-app-secret")),
      FieldSpec("verification_token", "secret", required = true, pattern = None,
        Some("social-feishu-verification-token")),
      FieldSpec("encrypt_key", "secret", required = false, pattern = None, Some("social-feishu-encrypt-key")),
      FieldSpec("region", "select", required = true, pattern = Some("^(feishu|lark)$"))
    )),
    ChannelSpec("telegram", List(
      FieldSpec("bot_token", "secret", required = true, pattern = None, Some("social-telegram-bot-token")),
      FieldSpec("chat_id", "text", required = true, pattern = Some("^-?\\d+$")),
      FieldSpec("api_base", "url", required = false, pattern = Some("^https?://"))
    ))
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
        case Left(_)  => Json.obj() // unreadable config: the READ side answers `{}`; writes refuse below

  /** The `socialChannels` subtree; absent key ⇒ `{}` (old installs, arch §7.2). */
  def readChannels(root: os.Path): Json =
    parseConfig(root).hcursor.downField("socialChannels").focus.getOrElse(Json.obj())

  /** GET /api/social/channels payload. Secret fields only ever surface their
    *  PATH (the stored `_ref` value) — never a credential. */
  def channelsJson(root: os.Path): Json =
    val stored = readChannels(root).hcursor.downField("channels").focus.getOrElse(Json.obj())
    val out = channelIds.map { id =>
      val entry = stored.hcursor.downField(id).focus.getOrElse(Json.obj())
      val enabled = entry.hcursor.downField("enabled").as[Boolean].getOrElse(false)
      val fields = entry.hcursor.downField("fields").focus.getOrElse(Json.obj())
      id -> Json.obj("enabled" -> enabled.asJson, "fields" -> fields)
    }
    Json.obj("channels" -> Json.fromFields(out), "adapterRegistered" -> false.asJson)

  /** The mechanical triple for one stored path: `{exists, modeOk, readable}`.
    *  Content is never read. */
  private def probePath(path: os.Path, osName: String): Json =
    val nio: Path = path.toNIO
    val exists = try Files.exists(nio) catch case _: Exception => false
    if !exists then
      Json.obj("exists" -> false.asJson, "modeOk" -> false.asJson, "readable" -> false.asJson)
    else
      val modeOk =
        if CredentialFileAcl.isWindows(osName) then
          // Windows: the mode IS the DACL. Read the real mask and compare it with
          // the expected owner-only bit set (the credaacl batch's definition
          // layer). Unavailable / unreadable is reported as NOT ok — never as
          // "clean by default". (True-machine reading is unproven on this host:
          // macOS only; the branch is exercised by an injected-osName test.)
          try
            CredentialFileAcl.systemWindowsAcl.readAcl(nio)
              .exists(m => m.missing(CredentialFileAcl.expectedBits).isEmpty)
          catch case _: Exception => false
        else
          try PosixFilePermissions.toString(Files.getPosixFilePermissions(nio)) == CredentialFileAcl.PosixMode
          catch case _: Exception => false
      val readable = try Files.isReadable(nio) catch case _: Exception => false
      Json.obj("exists" -> true.asJson, "modeOk" -> modeOk.asJson, "readable" -> readable.asJson)

  /** Rendered form of the data-root-token, i.e. what a default install sees.
    *  Kept in step with the rest of the product: a default install renders
    *  `~/.nebflow`, an isolated instance renders its own absolute root. */
  private def renderRoot(root: os.Path): String =
    if root == os.home / nebflow.core.Branding.homeDirName then s"~/${nebflow.core.Branding.homeDirName}"
    else root.toString

  /** Inverse of [[renderRoot]]: a stored reference back to a real path. */
  private def resolveRef(root: os.Path, ref: String): os.Path =
    val rendered = renderRoot(root)
    if ref.startsWith(rendered + "/") then
      os.Path(root.toString + ref.drop(rendered.length), os.pwd)
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
        case None      => f.key -> Json.obj("exists" -> false.asJson, "modeOk" -> false.asJson,
          "readable" -> false.asJson)
    }
    Json.fromFields(triples)

  /** GET /api/social/probe?channel=<id>. `adapterRegistered` is a hard `false`
    *  for the whole of phase 1 — the render authority is the frontend
    *  definition layer, and both sides agree there is no adapter yet. */
  def probeJson(root: os.Path, id: String): Either[Failure, Json] =
    channel(id) match
      case None       => Left(Failure.UnknownChannel(id))
      case Some(spec) => Right(Json.obj("adapterRegistered" -> false.asJson, "secrets" -> probeOf(root, spec)))

  // ─────────────────────────── write side ───────────────────────────

  /** Where a secret for `f` of `spec` lives. Kept under the data root's
    *  `secrets/` subtree and named after the field (arch §7.2). */
  private def secretPath(root: os.Path, spec: ChannelSpec, f: FieldSpec): os.Path =
    val name = f.secretName.getOrElse(s"social-${spec.id}-${f.key}")
    root / "secrets" / name

  /** Write one credential and narrow its permissions through the EXISTING
    *  `CredentialFileAcl` semantics. Any failure is on the error channel —
    *  a credential is never reported as stored when it is not readable. */
  private def writeSecret(root: os.Path, spec: ChannelSpec, f: FieldSpec, plain: String):
      Either[Failure, Unit] =
    val path = secretPath(root, spec, f)
    try
      Files.createDirectories(path.toNIO.getParent)
      Files.write(
        path.toNIO,
        plain.getBytes("UTF-8"),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      // The authoritative narrowing step: the shared module's semantics (POSIX
      // `rw-------`; Windows goes through its DACL ladder). A throw lands on the
      // error channel — never swallowed.
      CredentialFileAcl.restrict(path.toNIO)
      Right(())
    catch
      case e: Exception =>
        Left(Failure.SecretMode(f.key, s"could not store credential for ${spec.id}.${f.key}: ${e.getMessage}"))

  /** POST /api/social/channels/<id>.
    *
    * Body: `{"enabled": bool, "fields": {...}}`. Secret fields arrive as
    * PLAINTEXT under their own key and appear in this request body only; what
    * is persisted is the path. Fields absent from the body keep their stored
    * value (surgical merge — a toggle flip cannot wipe a schema). */
  def save(root: os.Path, id: String, body: Json): Either[Failure, Json] =
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
            val writeResults = secretWrites.map { case (f, plain) => writeSecret(root, spec, f, plain) }
            writeResults.collectFirst { case Left(err) => err } match
              case Some(err) => Left(err)
              case None =>
                // 3. merge the config subtree: paths for secrets, values for the rest
                val existing = storedFields(root, id)
                val secretRefs = spec.secretFields.flatMap { f =>
                  if secretWrites.exists(_._1.key == f.key) then
                    Some(f.storedKey -> Json.fromString(s"${renderRoot(root)}/secrets/" +
                      f.secretName.getOrElse(s"social-${spec.id}-${f.key}")))
                  else None
                }
                val plainJson = plainPairs.map { case (f, v) => f.key -> Json.fromString(v) }
                val merged = Json.fromFields(
                  existing.asObject.getOrElse(JsonObject.empty).toList ++ secretRefs ++ plainJson
                )
                val enabled = body.hcursor.downField("enabled").as[Boolean].getOrElse(false)
                val entry = Json.obj(
                  "enabled" -> enabled.asJson,
                  "fields" -> merged,
                  "updatedAt" -> (System.currentTimeMillis() / 1000).asJson
                )
                persist(root, id, entry) match
                  case Left(err) => Left(err)
                  case Right(_) =>
                    Right(Json.obj("ok" -> true.asJson, "probe" -> probeOf(root, spec)))

  /** Surgical `socialChannels.channels.<id>` rewrite: every other key of
    *  nebflow.json survives (same shape as PluginBlockPolicy's writer). */
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
          Right(())
    catch case e: Exception => Left(Failure.Io(s"could not persist social channel config: ${e.getMessage}"))

end SocialChannels
