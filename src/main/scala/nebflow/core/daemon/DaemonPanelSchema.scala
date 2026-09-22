package nebflow.core.daemon

import io.circe.{Json, JsonObject}

import java.util.regex.Pattern

/** Declaration validator for daemon config panels (daemonpanel Phase A).
  *
  * Threat model: `~/.nebflow/daemons.json` is an AGENT-WRITABLE surface, so a
  * `configPanel` declaration is an injection surface. Three mechanical
  * defences, all fail-closed:
  *   1. `type` is a CLOSED whitelist, and the WHOLE declaration is rejected on
  *      any violation (the daemon's config entry is hidden - never partially
  *      rendered).
  *   2. `kind` defaults to `"schema"`; the absence of the key means the
  *      strictest form (the host renders the DOM, zero foreign code runs).
  *   3. `kind:"web"` is DENIED by default and only takes effect when
  *      `nebflow.json` carries an explicit switch (`daemonPanel.allowWeb`).
  *
  * Pure: no IO, no filesystem. Callers decide what to do with a rejection.
  */
object DaemonPanelSchema:

  /** Closed whitelist of field types. Anything else rejects the declaration. */
  val FieldTypes: Set[String] = Set("string", "number", "boolean", "enum", "secret", "text")

  /** Closed whitelist of panel kinds. `schema` = host-rendered (default). */
  val Kinds: Set[String] = Set("schema", "web")

  /** Sandbox tokens a panel iframe may carry. `allow-same-origin`,
    * `allow-popups` and `allow-top-navigation` are deliberately absent.
    */
  val AllowedSandboxTokens: Set[String] = Set("allow-scripts", "allow-forms")

  /** Extensions a panel file may carry. Narrow on purpose: the host reads the
    * bytes and hands them to the panel as an `srcdoc` document (F-7), so this
    * is NOT the browser-facing `/api/nf-file` whitelist — that one deliberately
    * omits `html`/`htm`, which is exactly why the host carries the document
    * instead of pointing the iframe at a URL.
    */
  val PanelHtmlExtensions: Set[String] = Set("html", "htm")

  /** Upper bound on the panel document the host will carry into `srcdoc`.
    * Fail-closed: an oversized file is refused rather than silently truncated
    * (a truncated document could drop the panel's own `<meta CSP>` or its
    * closing tags). 512 KB is far above any hand-written settings panel.
    */
  val MaxPanelHtmlBytes: Int = 512 * 1024

  /** Top-level declaration keys, closed: an unknown key rejects the whole
    * declaration rather than being quietly ignored.
    */
  private val TopLevelKeys: Set[String] =
    Set("version", "kind", "title", "fields", "url", "htmlFile", "forms")

  /** Per-field keys, closed for the same reason. */
  private val FieldKeys: Set[String] =
    Set("key", "label", "type", "default", "min", "max", "options", "required")

  private val KeyPattern: Pattern = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}")

  /** Relative, traversal-free panel file path (no leading slash, no `..`). */
  private val RelPathPattern: Pattern = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._/-]*")

  /** One declared field — the only thing a daemon may say about its panel. */
  final case class PanelField(
    key: String,
    label: String,
    ftype: String,
    default: Option[Json],
    min: Option[Double],
    max: Option[Double],
    options: List[String],
    required: Boolean
  ):
    def isSecret: Boolean = ftype == "secret"
    def isEnum: Boolean = ftype == "enum"
  end PanelField

  /** A validated declaration. Constructible only through `validate`. */
  final case class Declaration(
    version: Int,
    kind: String,
    title: String,
    fields: List[PanelField],
    url: Option[String],
    htmlFile: Option[String],
    forms: Boolean
  ):
    def isSchema: Boolean = kind == "schema"
    def isWeb: Boolean = kind == "web"
    def fieldByKey: Map[String, PanelField] = fields.map(f => f.key -> f).toMap
    /** Sandbox tokens for this panel's iframe — never `allow-same-origin`. */
    def sandboxTokens: List[String] =
      if forms then List("allow-scripts", "allow-forms") else List("allow-scripts")
  end Declaration

  /** Per-secret-field backing-file state (Phase B credential three-state). */
  final case class CredentialState(key: String, name: String, state: String, mode: Option[String])

  /** Credential states, matching the Phase B three-state contract. */
  object CredentialStates:
    val Ok = "ok"
    val Missing = "missing"
    val Permissive = "permissive"
  end CredentialStates

  /** The LOCAL content-security policy injected into a host-carried panel
    * document (F-7). Never a global CSP — this string only ever lands inside the
    * panel iframe's own `<meta http-equiv>`, injected by the HOST, so a daemon
    * declaration cannot remove or weaken it.
    *
    * `default-src 'none'` denies every fetch class by default; scripts and
    * styles are admitted inline because a self-contained panel document carries
    * its own `<script>`/`<style>`; `img-src data:` keeps inline icons working.
    * `connect-src 'none'` is deliberate and STRICTER than the design's suggested
    * `connect-src 'self'`: a host-carried document runs in an opaque origin
    * (sandbox without `allow-same-origin`), where `'self'` matches no origin at
    * all, and Phase A has no panel -> network leg by design (the host relays
    * configuration I/O; the panel ships zero `postMessage` consumer). So the
    * panel document can reach NOTHING — and in particular
    * `fetch('https://evil.example')` is refused.
    */
  val PanelCsp: String =
    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
      "img-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'"

  /** Markers used to make the injection both idempotent and assertable. */
  private val CspTagMarker = "data-daemon-panel-csp"

  /** The `<meta>` the injector writes, carrying the assertable marker. */
  private def cspMetaTag: String =
    s"""<meta $CspTagMarker http-equiv="Content-Security-Policy" content="$PanelCsp">"""

  /** True when `html` carries an injected panel CSP meta (the full tag shape,
    * not merely the marker substring — a hostile document can print the marker
    * text in its own body without any policy being in force).
    */
  def hasPanelCsp(html: String): Boolean = html.contains(cspMetaTag)

  /** Inject the local `<meta CSP>` as the FIRST thing inside `<head>` (falling
    * back to a prepended head, or to a bare prepend for a fragment).
    *
    * Purely textual and total: no parsing of untrusted markup beyond locating
    * the first `<head ...>` open tag, and never an exception on malformed input.
    *
    * 🔴 The skip condition is the removal of OUR OWN tag, never the bare marker
    * substring: keying idempotence on a string the declaration can also contain
    * would let a hostile declaration suppress the policy by quoting the marker
    * (a fail-open hole). Here a prior injected tag is stripped and re-inserted,
    * so a second call is byte-identical while an author's own marker text has no
    * effect at all.
    */
  def injectPanelCsp(html: String): String =
    val stripped = html.replace(cspMetaTag, "")
    val lower = stripped.toLowerCase
    // Locate `<head>` and the end of its open tag, so the meta becomes the
    // first child of head (before any author script can run fetch()).
    val headIdx = lower.indexOf("<head")
    if headIdx < 0 then cspMetaTag + stripped
    else
      val closeIdx = stripped.indexOf('>', headIdx)
      if closeIdx < 0 then cspMetaTag + stripped
      else stripped.substring(0, closeIdx + 1) + cspMetaTag + stripped.substring(closeIdx + 1)

  /** Give the document a `<head>` element if it has none, so the injected
    * `<meta>` is actually honoured — a `<meta>` outside `head` (or ahead of the
    * doctype) is ignored by the browser, and a policy that is present in the
    * bytes but not in force is worse than none because it reads as protection.
    *
    * Three shapes, in order: an existing `<head>` is left alone; an `<html>`
    * without a head gets an empty head inserted right after its open tag; a bare
    * fragment is wrapped into a minimal document.
    */
  def normalizePanelDocument(html: String): String =
    val lower = html.toLowerCase
    if lower.contains("<head") then html
    else
      val htmlIdx = lower.indexOf("<html")
      if htmlIdx >= 0 then
        val closeIdx = html.indexOf('>', htmlIdx)
        if closeIdx < 0 then s"<head></head>$html"
        else html.substring(0, closeIdx + 1) + "<head></head>" + html.substring(closeIdx + 1)
      else s"<!doctype html><html><head></head><body>$html</body></html>"

  /** Build the final `srcdoc` payload for a host-carried panel document:
    * normalize (so a head exists) then inject the local policy.
    */
  def panelSrcdoc(html: String): String = injectPanelCsp(normalizePanelDocument(html))

  /** The `nebflow.json` explicit switch for the web escape hatch. Absent or
    * malformed config reads as DENY (fail-closed default).
    */
  def allowWeb(raw: Option[Json]): Boolean =
    raw.flatMap(j => j.hcursor.downField("allowWeb").as[Boolean].toOption).getOrElse(false)

  /** Validate a raw declaration. `Left(reason)` means the WHOLE declaration is
    * rejected: the daemon's panel entry is hidden and nothing is rendered.
    *
    * @param raw       the raw `configPanel` JSON from daemons.json
    * @param allowWeb  the resolved `daemonPanel.allowWeb` switch
    */
  def validate(raw: Json, allowWeb: Boolean): Either[String, Declaration] =
    raw.asObject match
      case None => Left("declaration must be a JSON object")
      case Some(obj) =>
        val unknownTop = obj.keys.filterNot(TopLevelKeys.contains).toList
        if unknownTop.nonEmpty then Left(s"unknown declaration key(s): ${unknownTop.sorted.mkString(", ")}")
        else
          for
            version <- readVersion(raw)
            kind <- readKind(raw)
            title <- readTitle(raw)
            forms <- readForms(raw)
            fields <- readFields(raw, kind)
            web <- readWeb(raw, kind, allowWeb)
          yield Declaration(version, kind, title, fields, web._1, web._2, forms)

  private def readVersion(raw: Json): Either[String, Int] =
    raw.hcursor.downField("version").focus match
      case None => Right(1)
      case Some(j) =>
        j.asNumber.flatMap(_.toInt) match
          case Some(1) => Right(1)
          case Some(other) => Left(s"unsupported version: $other (expected 1)")
          case None => Left("version must be the integer 1")

  private def readKind(raw: Json): Either[String, String] =
    raw.hcursor.downField("kind").focus match
      case None => Right("schema") // absent => strictest form (host-rendered)
      case Some(j) =>
        j.asString match
          case Some(k) if Kinds.contains(k) => Right(k)
          case Some(k) => Left(s"unknown kind: '$k' (expected one of ${Kinds.toList.sorted.mkString(", ")})")
          case None => Left("kind must be a string")

  private def readTitle(raw: Json): Either[String, String] =
    raw.hcursor.downField("title").focus match
      case None => Right("")
      case Some(j) => j.asString.toRight("title must be a string")

  private def readForms(raw: Json): Either[String, Boolean] =
    raw.hcursor.downField("forms").focus match
      case None => Right(false)
      case Some(j) => j.asBoolean.toRight("forms must be a boolean")

  private def readFields(raw: Json, kind: String): Either[String, List[PanelField]] =
    raw.hcursor.downField("fields").focus match
      case None =>
        // A schema panel with no fields renders nothing useful; a web panel
        // carries its own markup and needs no field declarations.
        if kind == "schema" then Left("kind 'schema' requires a non-empty 'fields' array")
        else Right(Nil)
      case Some(j) =>
        j.asArray match
          case None => Left("fields must be an array")
          case Some(arr) =>
            if kind == "schema" && arr.isEmpty then Left("kind 'schema' requires a non-empty 'fields' array")
            else
              val parsed = arr.zipWithIndex.map { case (fj, i) => readField(fj, i) }
              parsed.collectFirst { case Left(e) => e } match
                case Some(err) => Left(err)
                case None =>
                  val fields = parsed.collect { case Right(f) => f }.toList
                  val dupes = fields.groupBy(_.key).collect { case (k, vs) if vs.size > 1 => k }.toList
                  if dupes.nonEmpty then Left(s"duplicate field key(s): ${dupes.sorted.mkString(", ")}")
                  else Right(fields)

  private def readField(fj: Json, idx: Int): Either[String, PanelField] =
    fj.asObject match
      case None => Left(s"fields[$idx] must be an object")
      case Some(obj) =>
        val unknown = obj.keys.filterNot(FieldKeys.contains).toList
        if unknown.nonEmpty then Left(s"fields[$idx]: unknown key(s): ${unknown.sorted.mkString(", ")}")
        else
          val keyOpt = obj("key").flatMap(_.asString)
          keyOpt match
            case None => Left(s"fields[$idx]: 'key' must be a string")
            case Some(k) if !KeyPattern.matcher(k).matches() =>
              Left(s"fields[$idx]: invalid key '$k' (expected [A-Za-z_][A-Za-z0-9_]{0,63})")
            case Some(key) =>
              val label = obj("label").flatMap(_.asString).getOrElse(key)
              obj("type").flatMap(_.asString) match
                case None => Left(s"fields[$idx] ('$key'): 'type' must be a string")
                case Some(t) if !FieldTypes.contains(t) =>
                  Left(s"fields[$idx] ('$key'): unsupported type '$t' (allowed: ${FieldTypes.toList.sorted.mkString(", ")})")
                case Some(ftype) =>
                  for
                    options <- readOptions(obj, idx, key, ftype)
                    bounds <- readBounds(obj, idx, key)
                    required <- readRequired(obj, idx, key)
                    default <- readDefault(obj, idx, key, ftype, options)
                  yield PanelField(key, label, ftype, default, bounds._1, bounds._2, options, required)

  private def readOptions(obj: JsonObject, idx: Int, key: String, ftype: String): Either[String, List[String]] =
    obj("options") match
      case None =>
        if ftype == "enum" then Left(s"fields[$idx] ('$key'): type 'enum' requires a non-empty 'options' array")
        else Right(Nil)
      case Some(j) =>
        j.asArray match
          case None => Left(s"fields[$idx] ('$key'): options must be an array of strings")
          case Some(arr) =>
            val opts = arr.toList.map(_.asString)
            opts.collectFirst { case None => () } match
              case Some(_) => Left(s"fields[$idx] ('$key'): options must be an array of strings")
              case None =>
                val values = opts.flatten
                if ftype != "enum" then Left(s"fields[$idx] ('$key'): options is only valid for type 'enum'")
                else if values.isEmpty then Left(s"fields[$idx] ('$key'): options must not be empty")
                else if values.distinct.size != values.size then Left(s"fields[$idx] ('$key'): options must be distinct")
                else Right(values)

  private def readBounds(obj: JsonObject, idx: Int, key: String): Either[String, (Option[Double], Option[Double])] =
    val minRaw = obj("min")
    val maxRaw = obj("max")
    (minRaw, maxRaw) match
      case (None, None) => Right((None, None))
      case _ =>
        // circe's `toDouble` is already total (BigDecimal -> Double); a
        // non-numeric JSON value surfaces as `asNumber == None`.
        def asDouble(j: Option[Json]): Option[Option[Double]] = j.map(_.asNumber.map(_.toDouble))
        val minD = asDouble(minRaw)
        val maxD = asDouble(maxRaw)
        if minD.exists(_.isEmpty) then Left(s"fields[$idx] ('$key'): min must be a number")
        else if maxD.exists(_.isEmpty) then Left(s"fields[$idx] ('$key'): max must be a number")
        else
          val lo: Option[Double] = minD.flatten
          val hi: Option[Double] = maxD.flatten
          (lo, hi) match
            case (Some(a), Some(b)) if a > b => Left(s"fields[$idx] ('$key'): min must not exceed max")
            case _                           => Right((lo, hi))

  private def readRequired(obj: JsonObject, idx: Int, key: String): Either[String, Boolean] =
    obj("required") match
      case None    => Right(false)
      case Some(j) => j.asBoolean.toRight(s"fields[$idx] ('$key'): required must be a boolean")

  private def readDefault(
    obj: JsonObject,
    idx: Int,
    key: String,
    ftype: String,
    options: List[String]
  ): Either[String, Option[Json]] =
    obj("default") match
      case None => Right(None)
      case Some(j) if j.isNull => Right(None)
      case Some(j) =>
        val ok = ftype match
          case "string" | "text" | "secret" => j.isString
          case "number"                     => j.isNumber
          case "boolean"                    => j.isBoolean
          case "enum"                       => j.asString.exists(options.contains)
          case _                            => false
        if ok then Right(Some(j))
        else Left(s"fields[$idx] ('$key'): default does not match type '$ftype'")

  private def readWeb(raw: Json, kind: String, allowWeb: Boolean): Either[String, (Option[String], Option[String])] =
    val url = raw.hcursor.downField("url").focus.filterNot(_.isNull).flatMap(_.asString)
    val htmlFile = raw.hcursor.downField("htmlFile").focus.filterNot(_.isNull).flatMap(_.asString)
    if kind != "web" then
      if url.isDefined || htmlFile.isDefined then
        Left("url/htmlFile are only valid when kind is 'web'")
      else Right((None, None))
    else if !allowWeb then
      // Default-closed: the escape hatch needs an explicit nebflow.json switch.
      Left("kind 'web' is disabled (set daemonPanel.allowWeb=true in nebflow.json to enable)")
    else
      (url, htmlFile) match
        case (Some(_), Some(_)) => Left("kind 'web' requires exactly one of url or htmlFile (both given)")
        case (None, None)       => Left("kind 'web' requires exactly one of url or htmlFile (neither given)")
        case (Some(u), None)    => validateWebUrl(u).map(v => (Some(v), None))
        case (None, Some(f))    => validateHtmlFile(f).map(v => (None, Some(v)))

  /** Absolute http(s) URL, no userinfo, no fragment — the MCP entry shape. */
  private def validateWebUrl(url: String): Either[String, String] =
    if !(url.startsWith("http://") || url.startsWith("https://")) then
      Left("web url must be absolute (http:// or https://)")
    else
      val afterScheme = url.indexOf("://") + 3
      val authorityEnd =
        val slash = url.indexOf('/', afterScheme)
        if slash < 0 then url.length else slash
      val authority = url.substring(afterScheme, authorityEnd)
      if authority.contains('@') then Left("web url must not contain userinfo")
      else if url.contains('#') then Left("web url must not contain a fragment")
      else if authority.isEmpty then Left("web url must have a host")
      else Right(url)

  /** Panel file, relative and contained: no absolute paths, no traversal, and
    * restricted to a filename-safe character set.
    */
  private def validateHtmlFile(f: String): Either[String, String] =
    if f.isEmpty then Left("htmlFile must not be empty")
    else if f.startsWith("/") then Left("htmlFile must be a relative path")
    else if f.split('/').contains("..") then Left("htmlFile must not contain '..'")
    else if !RelPathPattern.matcher(f).matches() then Left("htmlFile contains unsupported characters")
    else Right(f)

end DaemonPanelSchema
