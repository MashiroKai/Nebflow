package nebflow.core

import java.io.InputStream

import scala.io.Source

/**
  * L1 rebrand (2026-08-17): typed view over the brand single source
  * `brand.conf` — repo root, packaged into the JAR by build.sbt. Brand
  * strings that used to be hardcoded in Scala flow through here so a rename
  * is "edit brand.conf + run the rebrand script", not a code hunt.
  *
  * Scope notes:
  *  - The package tree is NOT parameterized (D3): renaming packages is the
  *    rebrand script's job, and this object keeps the package it lives in.
  *  - Batch 3 (L3 runtime compat) will extend this object with env()
  *    dual-prefix reads and configFileName — the val surface below is the
  *    extension point; do not pre-implement those here.
  *  - `domain` is a PLACEHOLDER until the user picks the real value (D4).
  *    Nothing in this period may consume it to build a runtime or build-time
  *    URL — installUrl/serverUrl carry the current true values instead.
  */
object Branding:

  /** Parse the `key = value` format: full-line '#' comments and blank lines
    * are skipped; a ` # ` sequence starts an inline comment (a '#' glued to
    * the value is kept — URLs may legally contain fragments). No quoting, no
    * escapes: brand values are plain tokens. Duplicate keys: last one wins.
    * Package-visible for the spec. */
  private[core] def parseBrandConf(text: String): Map[String, String] =
    text.linesIterator
      .map(_.trim)
      .filterNot(line => line.isEmpty || line.startsWith("#"))
      .flatMap { line =>
        line.indexOf('=') match
          case -1 => None
          case eq =>
            val key = line.substring(0, eq).trim
            val value = stripInlineComment(line.substring(eq + 1).trim)
            if key.isEmpty then None else Some(key -> value)
      }
      .toMap
  end parseBrandConf

  private def stripInlineComment(value: String): String =
    value.indexOf(" #") match
      case -1 => value
      case i  => value.substring(0, i).trim

  private lazy val conf: Map[String, String] =
    val stream: InputStream = Option(getClass.getClassLoader.getResourceAsStream("brand.conf"))
      .getOrElse(throw new IllegalStateException(
        "brand.conf is not on the classpath — build.sbt must package the repo-root file into the JAR"))
    val text =
      try Source.fromInputStream(stream, "UTF-8").mkString
      finally stream.close()
    parseBrandConf(text)

  private def get(key: String): String =
    conf.getOrElse(key, throw new IllegalStateException(s"brand.conf is missing key: $key"))

  // ── Brand values (single source: brand.conf) ─────────────────────────────

  /** Display name — UI, dmg/msi packaging. */
  val productName: String = get("productName")

  /** Lowercase name — jar/binary names, storage key prefixes. */
  val lowerName: String = get("lowerName")

  /** Full name — about dialog, first mention in docs. */
  val fullName: String = get("fullName")

  /** Primary domain. PLACEHOLDER (D4) — display-only until rename day;
    * must not be consumed to build any runtime or build-time URL this period. */
  val domain: String = get("domain")

  /** Install script URL (current true value). */
  val installUrl: String = get("installUrl")

  /** Device-interop default server URL (current true value). */
  val serverUrl: String = get("serverUrl")

  val githubOrg: String = get("githubOrg")

  val githubRepo: String = get("githubRepo")

  /** Data directory name — L3 batch 3 uses this for dual-read + migration. */
  val homeDirName: String = get("homeDirName")

  /** Env var prefix — L3 batch 3 uses this for dual-prefix reads. */
  val envPrefix: String = get("envPrefix")

  /** Release mirror bucket (COS). */
  val cosBucket: String = get("cosBucket")

  /** Device-interop subsystem name (D2: stays as-is across the rename). */
  val subsystemName: String = get("subsystemName")

  // ── Derived helpers ──────────────────────────────────────────────────────

  /** Politeness User-Agent for academic-search APIs (Crossref etiquette asks
    * for a mailto contact). The contact address follows the install host —
    * the one domain guaranteed to be a current-true value (the placeholder
    * `domain` must not leak into outbound strings). */
  def academicSearchUserAgent: String =
    val host =
      try new java.net.URI(installUrl).getHost
      catch case _: Exception => null
    val contact = if host == null || host.isEmpty then installUrl else s"research@$host"
    s"$productName/academic-search (mailto:$contact)"

end Branding
