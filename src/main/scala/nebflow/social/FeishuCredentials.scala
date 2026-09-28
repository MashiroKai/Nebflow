package nebflow.social

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.CredentialFileAcl
import nebflow.shared.{Branding, PathUtil}

import java.nio.file.{Files, Path}

/**
 * Feishu credential resolution — the read-only load point for the long-connection
 * channel (feishu-chan, 2026-09-23; upstream plan card = `20260923_093043_feishu-prep`).
 *
 * Two candidate shapes are read, in this order (the plan card §3.5 records the
 * mismatch between them):
 *
 *   ① `~/.nebflow/nebflow.json` → `socialChannels.channels.feishu.fields` — the
 *      in-repo schema (`SocialChannels`), snake_case `app_id` / `app_secret`,
 *      secrets stored as `_ref` PATHS. AUTHORITATIVE since the feishu-bind batch
 *      (2026-09-27): the scan-bind main path stores exactly here, so the schema
 *      side is what the panel shows and what the user just created.
 *   ② `~/.nebflow/feishu.json` — the legacy orphan file, FLAT, camelCase
 *      keys `appId` / `appSecret`. FALLBACK only: consulted when the schema
 *      side carries no credential at all (no config, no feishu entry, or an
 *      entry without credential keys). The historical order was legacy-first,
 *      which is how a freshly stored scan-bind credential sat unread while the
 *      bridge kept connecting a months-old legacy app (the silent-message
 *      incident the 2026-09-27 diagnosis nailed) — do not flip back.
 *
 * 🔴 Zero-secret discipline (this batch's hard constraint §B-8): the credential
 * VALUE never leaves this object — no logging, no `toString`, no exception
 * message, no evidence file. A caller can only read [[FeishuCredentials.appId]]
 * and [[FeishuCredentials.appSecret]] to hand them to the SDK, and can only ask
 * for [[redacted]] / [[fingerprint]] for anything that gets written down.
 *
 * Nothing here creates, narrows or deletes a file: this is a read side. The
 * authoritative ACL module ([[CredentialFileAcl]]) is consulted only to REPORT
 * the mode, never to change it.
 */
object FeishuCredentials:

  /** The credential pair, held in memory only. `toString` is overridden so an
    *  accidental interpolation (`s"$creds"`) cannot leak the secret. */
  final case class Credential(appId: String, appSecret: String, source: String):
    override def toString: String = s"Credential(appId=${redacted(appId)}, appSecret=<redacted>, source=$source)"

  /** Why a credential could not be resolved. Never carries a value. */
  enum Failure:
    case Missing(reason: String)
    case Unreadable(reason: String)
    case Incomplete(reason: String)

  /** Mask a value for display: keep the first/last 4 chars of anything long
    *  enough, otherwise emit nothing but the length. Mirrors the prep node's
    *  evidence convention (masked id + length + digest only). */
  def redacted(v: String): String =
    if v.length > 8 then s"${v.take(4)}${"*" * (v.length - 8)}${v.takeRight(4)}"
    else s"<len:$v.length>"

  /** A stable, non-invertible identifier for a value — safe to write down. */
  def fingerprint(v: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(v.getBytes("UTF-8")).take(6).map("%02x".format(_)).mkString

  /** The legacy flat file (`~/.nebflow/feishu.json`), if present. */
  def legacyPath(root: os.Path): os.Path = root / "feishu.json"

  /** The in-repo schema's field storage (`secrets/<name>`), per
    *  `SocialChannels.secretPath`. */
  private def schemaSecretPath(root: os.Path, name: String): os.Path = root / "secrets" / name

  /** Resolve the credential pair. `root` is the active data root (honours
    *  `--home` / `NEBFLOW_HOME` through [[PathUtil.dataRoot]]).
    *
    *  🔴 Resolution order (feishu-bind batch, 2026-09-27 — the schema-first
    *  flip): SCHEMA first, legacy file as FALLBACK.
    *    - a resolvable schema credential wins;
    *    - no schema credential at all (no config, no feishu entry, or an entry
    *      whose fields carry neither app_id nor app_secret_ref) falls through
    *      to the legacy flat file;
    *    - neither source ⇒ the same Missing failure as before, naming both
    *      candidate locations.
    *  A schema entry that declared a credential but whose secret file is gone
    *  stays an explicit error (never a silent fallthrough to legacy): the
    *  bridge must not quietly reconnect a DIFFERENT app than the stored one —
    *  that surface is what the fingerprint guard (FeishuBridgePlugin.sync)
    *  exists to expose. */
  def resolve(root: os.Path): Either[Failure, Credential] =
    fromSocialChannelsSchema(root).orElse(fromLegacyFile(root)) match
      case Some(Right(c)) => Right(c)
      case Some(Left(err)) => Left(err)
      case None => Left(Failure.Missing(
        s"no Feishu credential found: neither ${root}/nebflow.json → socialChannels.channels.feishu.fields " +
          s"nor ${legacyPath(root)} (flat appId/appSecret) is present"))

  /** ② The legacy flat file — the FALLBACK (feishu-bind flip). Returns `None`
    *  when the file does not exist (so the caller can fall through); a
    *  present-but-unusable file is an error, not a silent fallthrough — a
    *  half-written credential must not look like "no credential". */
  private def fromLegacyFile(root: os.Path): Option[Either[Failure, Credential]] =
    val p = legacyPath(root)
    if !Files.exists(p.toNIO) then None
    else
      readJson(p) match
        case Left(err) => Some(Left(err))
        case Right(j) =>
          val id = j.hcursor.downField("appId").as[String].toOption.filter(_.nonEmpty)
          val secret = j.hcursor.downField("appSecret").as[String].toOption.filter(_.nonEmpty)
          (id, secret) match
            case (Some(a), Some(s)) => Some(Right(Credential(a, s, s"file:$p")))
            case _ => Some(Left(Failure.Incomplete(
              s"${p} exists but does not carry both appId and appSecret (keys present: " +
                j.asObject.map(_.keys.toList.sorted.mkString(",")).getOrElse("<not an object>") + ")")))

  /** ① The in-repo schema, AUTHORITATIVE: `nebflow.json →
    *  socialChannels.channels.feishu.fields`. `app_id` is inline, `app_secret`
    *  is a `_ref` path pointing into `secrets/`. An entry whose fields carry
    *  neither `app_id` nor `app_secret_ref` returns `None` — it holds no
    *  credential information, so the legacy fallback may speak. */
  private def fromSocialChannelsSchema(root: os.Path): Option[Either[Failure, Credential]] =
    val cfg = PathUtil.configJsonWritePath(root)
    if !Files.exists(cfg.toNIO) then None
    else
      readJson(cfg) match
        case Left(err) => Some(Left(err))
        case Right(j) =>
          val fields = j.hcursor.downField("socialChannels").downField("channels")
            .downField("feishu").downField("fields").focus.getOrElse(Json.obj())
          val id = fields.hcursor.downField("app_id").as[String].toOption.filter(_.nonEmpty)
          val secretRef = fields.hcursor.downField("app_secret_ref").as[String].toOption.filter(_.nonEmpty)
          (id, secretRef) match
            case (Some(a), Some(ref)) =>
              val secretPath = resolveRef(root, ref)
              if !Files.exists(secretPath.toNIO) then
                Some(Left(Failure.Missing(
                  s"socialChannels.feishu.fields.app_secret_ref points at $secretPath, which does not exist")))
              else
                try Some(Right(Credential(a, os.read(secretPath).trim, s"schema:$secretPath")))
                catch case e: Exception =>
                  Some(Left(Failure.Unreadable(
                    s"could not read the app_secret referenced by $secretPath: ${e.getClass.getSimpleName}")))
            case _ => None

  /** Inverse of the `_ref` rendering: an absolute path stays as-is, the rendered
    *  `~/<homeDirName>` form maps back under `root`, anything else is relative to
    *  `root` (same convention as `SocialChannels.resolveRef`). */
  private def resolveRef(root: os.Path, ref: String): os.Path =
    val rendered = s"~/${Branding.homeDirName}"
    if ref.startsWith(rendered + "/") then os.Path(root.toString + ref.drop(rendered.length), os.pwd)
    else if ref.startsWith("/") then os.Path(ref, os.pwd)
    else os.Path(root.toString + "/" + ref, os.pwd)

  private def readJson(p: os.Path): Either[Failure, Json] =
    try
      parse(os.read(p)) match
        case Right(j)  => Right(j)
        case Left(err) => Left(Failure.Unreadable(s"$p is not valid JSON: ${err.message}"))
    catch case e: Exception =>
      Left(Failure.Unreadable(s"$p could not be read: ${e.getClass.getSimpleName}"))

  /** The mechanical `{exists, mode, modeOk, readable}` reading for one credential
    *  path — the same shape the REST probe face answers with, reusable by the
    *  three-state table. Content is never touched. */
  def aclReading(path: Path): Json =
    val exists = try Files.exists(path) catch case _: Exception => false
    if !exists then
      Json.obj("exists" -> false.asJson, "modeOk" -> false.asJson, "readable" -> false.asJson)
    else
      val osName = CredentialFileAcl.currentOsName
      val mode =
        if CredentialFileAcl.isWindows(osName) then "<windows-dacl>"
        else
          try java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
          catch case _: Exception => "<unreadable>"
      val modeOk =
        if CredentialFileAcl.isWindows(osName) then
          try CredentialFileAcl.systemWindowsAcl.readAcl(path)
                .exists(m => m.missing(CredentialFileAcl.expectedBits).isEmpty)
          catch case _: Exception => false
        else mode == CredentialFileAcl.PosixMode
      val readable = try Files.isReadable(path) catch case _: Exception => false
      Json.obj("exists" -> true.asJson, "mode" -> mode.asJson,
        "modeOk" -> modeOk.asJson, "readable" -> readable.asJson)

end FeishuCredentials
