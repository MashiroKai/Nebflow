package nebflow.core.secrets

import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.CredentialFileAcl
import nebflow.shared.Branding

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.UUID

/**
 * The three-field catalogue face of the secrets store (secrets-consumption
 * batch, 2026-10-01).
 *
 * The panel leg answers "what secrets exist"; this face answers the
 * consumption question — "given that I need credential X, WHERE do I query
 * it?" — and it answers that question WITHOUT ever reading a value:
 *
 *   - field 1, the VARIABLE NAME: explicit when set, otherwise derived from
 *     the secret name by [[deriveVariableName]] (`vercel-token` ->
 *     `VERCEL_TOKEN`);
 *   - field 2, the VALUE itself: the owner-only file under `secrets/`, owned
 *     by [[SecretsStore]] — this module never opens it;
 *   - field 3, the DESCRIPTION: what this credential is for.
 *
 * Two artefacts, both OUTSIDE `secrets/` — deliberately: `secrets/` is the
 * credential namespace, and `SecretsStore.list()` enumerates every regular
 * file in it, so a catalogue file placed there would surface in the panel as
 * a bogus secret row. Keeping both next to the directory they describe also
 * keeps the credential namespace free of non-credentials:
 *
 *   - `<dataRoot>/secrets-meta.json`  — the (variableName, description)
 *     sidecar; absent or unparseable ⇒ every secret reads as its derived
 *     variable name with an empty description (the migration default for the
 *     secrets that predate this batch);
 *   - `<dataRoot>/secrets-catalog.md` — the rendered catalogue: name +
 *     variable name + description + rotation timestamp.
 *
 * 🔴 Zero value exposure, and zero VALUE FINGERPRINTS. The catalogue never
 * carries a stored value, and never carries anything derived from one — no
 * size, no hash, no prefix. The rotation timestamp is the secret FILE's own
 * mtime: it is metadata about the write, not about the bytes.
 *
 * Both artefacts are written `0600` through the shared
 * [[CredentialFileAcl]] and swapped in atomically (temp sibling +
 * `ATOMIC_MOVE`), so a reader never sees a half-written catalogue.
 */
object SecretsCatalog:

  /**
   * The metadata sidecar's per-secret entry. `variableName` is `None` for
   * every secret that has never had one set — the derived name is then used.
   * `description` is the empty string for the same case.
   */
  final case class Meta(variableName: Option[String], description: String)

  /**
   * One catalogue row: a secret NAME (an identifier, not a value — the panel
   * already shows it), its variable name, its description, and the mtime of
   * the file that holds its value.
   */
  final case class Row(name: String, variableName: String, description: String, updatedAt: Long)

  /** Variable-name contract, shared with the routes' validation. */
  val VariableNamePattern: java.util.regex.Pattern =
    java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$")

  def isVariableName(s: String): Boolean = VariableNamePattern.matcher(s).matches()

  /**
   * The metadata sidecar for `dataRoot` — next to the `secrets/` directory it
   * describes, never inside it.
   */
  def metaPath(dataRoot: os.Path): os.Path = dataRoot / "secrets-meta.json"

  /** The rendered catalogue for `dataRoot`. */
  def catalogPath(dataRoot: os.Path): os.Path = dataRoot / "secrets-catalog.md"

  /**
   * Deterministic default variable name: uppercase, every character outside
   * `[A-Za-z0-9]` becomes `_`. A leading digit is prefixed with `_` so the
   * result is always a valid shell identifier (`$1PASSWORD` is not a name,
   * `$_1PASSWORD` is).
   *
   * The mapping is many-to-one on purpose (it is a default, not an identity):
   * a caller that cares about an exact name sets one explicitly.
   */
  def deriveVariableName(name: String): String =
    val upper = name.map(c => if c.isLetterOrDigit && c < 128 then c.toUpper else '_')
    if upper.isEmpty then "_"
    else if upper.head.isDigit then "_" + upper
    else upper

  /**
   * The variable name a consumer should use for `name`: the explicit override
   * when one is set, the derived default otherwise.
   */
  def variableNameOf(name: String, meta: Map[String, Meta]): String =
    meta.get(name).flatMap(_.variableName).filter(_.nonEmpty).getOrElse(deriveVariableName(name))

  /** The description of `name` — the empty string when none was ever set. */
  def descriptionOf(name: String, meta: Map[String, Meta]): String =
    meta.get(name).map(_.description).getOrElse("")

  // ── Sidecar I/O ────────────────────────────────────────────────────────

  /**
   * Read the sidecar. Fail-safe by construction: a missing, unreadable or
   * unparseable file reads as "no metadata at all", which is exactly the
   * migration default — every secret keeps its derived variable name and an
   * empty description. A malformed sidecar must not make the secrets
   * unlistable.
   */
  def load(dataRoot: os.Path): Map[String, Meta] =
    val p = metaPath(dataRoot)
    if !Files.exists(p.toNIO) then Map.empty
    else
      try
        io.circe.parser.parse(os.read(p)) match
          case Right(json) =>
            json.hcursor.downField("secrets").focus.flatMap(_.asObject) match
              case Some(obj) =>
                obj.toMap.flatMap { case (name, entry) =>
                  val vn = entry.hcursor.downField("variableName").as[String].toOption.filter(_.nonEmpty)
                  val desc = entry.hcursor.downField("description").as[String].getOrElse("")
                  // An entry carrying neither field is not metadata — drop it
                  // rather than materialise a meaningless row.
                  if vn.isEmpty && desc.isEmpty then None else Some(name -> Meta(vn, desc))
                }
              case None => Map.empty
          case Left(_) => Map.empty
      catch case _: Throwable => Map.empty

    end if

  end load

  /**
   * Write the sidecar atomically, `0600`, through the shared credential ACL.
   * Entries that carry neither field are dropped: the sidecar holds metadata,
   * and "no metadata" is the absence of an entry, not an empty one.
   */
  def save(dataRoot: os.Path, meta: Map[String, Meta]): Unit =
    val obj = JsonObject.fromIterable(
      meta.toList.collect {
        case (name, Meta(vn, desc)) if vn.exists(_.nonEmpty) || desc.nonEmpty =>
          val fields = List(
            vn.filter(_.nonEmpty).map(v => "variableName" -> Json.fromString(v)),
            if desc.nonEmpty then Some("description" -> Json.fromString(desc)) else None
          ).flatten
          name -> Json.fromJsonObject(JsonObject.fromIterable(fields))
      }
    )
    val payload = Json.obj("version" -> Json.fromInt(1), "secrets" -> Json.fromJsonObject(obj))
    writeAtomically(metaPath(dataRoot), payload.spaces2 + "\n")

  // ── Catalogue rendering ────────────────────────────────────────────────

  /**
   * Markdown cell hardening: a description is user-authored free text, so it
   * may contain the column separator or a newline — either would corrupt the
   * table. Sanitising here keeps the renderer total over any description.
   */
  private def cell(s: String): String =
    s.replace("\\", "\\\\").replace("|", "\\|").replaceAll("[\\r\\n]+", " ")

  private def stamp(millis: Long): String =
    DateTimeFormatter
      .ofPattern("yyyy-MM-dd HH:mm")
      .withZone(ZoneId.systemDefault())
      .format(Instant.ofEpochMilli(millis))

  /**
   * The document. Title + table, and nothing prescriptive: every word that
   * tells an agent HOW to consume a secret belongs to the prompt line, which
   * is submitted for verbatim author review — a generated data document is
   * not the place for instructions that could drift away from the prompt.
   *
   * Rows arrive pre-sorted by name, so the same store state renders the same
   * bytes.
   */
  def render(rows: List[Row]): String =
    val sb = new StringBuilder
    sb.append("# Secrets catalog\n\n")
    sb.append("| Variable | Secret | Description | Updated |\n")
    sb.append("| --- | --- | --- | --- |\n")
    rows.foreach { r =>
      sb.append(s"| `${cell(r.variableName)}` | `${cell(r.name)}` | ${cell(r.description)} | ${stamp(r.updatedAt)} |\n")
    }
    sb.toString

  /**
   * The rows for the current store state: names and mtimes straight off the
   * directory listing plus the sidecar. 🔴 No file's CONTENT is opened here —
   * this is the whole reason the catalogue can be agent-readable.
   */
  def rows(secretsDir: os.Path, dataRoot: os.Path): List[Row] =
    if !Files.isDirectory(secretsDir.toNIO) then Nil
    else
      val meta = load(dataRoot)
      os.list(secretsDir)
        .filter(p => Files.isRegularFile(p.toNIO))
        .map(_.last)
        .sorted
        .toList
        .map { n =>
          val mtime =
            try Files.getLastModifiedTime((secretsDir / n).toNIO).toMillis
            catch case _: Throwable => 0L
          Row(n, variableNameOf(n, meta), descriptionOf(n, meta), mtime)
        }

  /**
   * Re-render the catalogue from the live directory. Called as the last step
   * of the store's write paths (create / overwrite / delete / metadata edit);
   * `list()` deliberately does NOT call it — a read must never write.
   */
  def refresh(secretsDir: os.Path, dataRoot: os.Path): Unit =
    writeAtomically(catalogPath(dataRoot), render(rows(secretsDir, dataRoot)))

  // ── Atomic owner-only write ────────────────────────────────────────────

  /**
   * Temp sibling + `ATOMIC_MOVE`, `0600` before the swap. A failure removes
   * the temp file and rethrows — the caller decides whether that is fatal
   * (the store treats a catalogue refresh failure as loud-but-non-fatal: the
   * secret itself is already stored, and reporting a failed write when the
   * write succeeded would be the worse lie).
   */
  private def writeAtomically(target: os.Path, text: String): Unit =
    val dir = target / os.up
    Files.createDirectories(dir.toNIO)
    val tmp = dir / s".${target.last}.tmp-${UUID.randomUUID().toString.take(8)}"
    try
      os.write.over(tmp, text, createFolders = true)
      CredentialFileAcl.restrict(tmp.toNIO)
      Files.move(tmp.toNIO, target.toNIO, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    catch
      case e: Throwable =>
        try Files.deleteIfExists(tmp.toNIO)
        catch case _: Throwable => ()
        throw e

  /**
   * The rendered location of a secret — what a consumer is told when it asks
   * where to query. Renders `~/.nebflow` for a default install and the
   * absolute root for an isolated one, matching `SocialChannels.renderRoot`
   * and [[SecretsStore]]'s stored-ref scan, so the string a consumer is
   * handed is the same string that appears in a stored `_ref`.
   */
  def renderedLocation(dataRoot: os.Path, secretsDir: os.Path, name: String): String =
    val root = if dataRoot == os.home / Branding.homeDirName then s"~/${Branding.homeDirName}" else dataRoot.toString
    s"$root/secrets/$name"

end SecretsCatalog
