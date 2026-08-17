import sbt._

/**
  * L2 rebrand (batch 2, 2026-08-17): the sbt-side view over the repo-root
  * brand.conf — the ONLY edit point for brand values. build.sbt reads
  * name/organization (hence the assembly jar name) from here so a rename
  * is "edit brand.conf + run the rebrand script", not a build-file hunt.
  *
  * NOTE: this file compiles under sbt's OWN Scala (2.12) — Scala-3-only
  * syntax (indentation, `then`) is not available here.
  *
  * Parser parity with the runtime nebflow.core.Branding.parseBrandConf:
  * `key = value`, full-line '#' comments, blank lines skipped, a ` # `
  * sequence starts an inline comment, last duplicate key wins.
  */
object BrandingBuild {

  private def parseBrandConf(text: String): Map[String, String] = {
    val entries = for {
      rawLine <- text.linesIterator.toSeq
      line = rawLine.trim
      if !line.isEmpty && !line.startsWith("#")
      eq = line.indexOf('=')
      if eq >= 0
      key = line.substring(0, eq).trim
      value0 = line.substring(eq + 1).trim
      value = value0.indexOf(" #") match {
        case -1 => value0
        case i  => value0.substring(0, i).trim
      }
      if !key.isEmpty
    } yield key -> value
    entries.toMap
  }

  private val conf: Map[String, String] = {
    val f = file("brand.conf")
    if (!f.exists()) sys.error("brand.conf not found at repo root — it is the single brand source")
    parseBrandConf(IO.read(f))
  }

  private def get(key: String): String =
    conf.getOrElse(key, sys.error(s"brand.conf is missing key: $key"))

  /** Display name — packaging (--name), artifacts. */
  def productName: String = get("productName")

  /** Lowercase name — sbt `name` (hence the assembly jar name), storage keys. */
  def lowerName: String = get("lowerName")

  def githubOrg: String = get("githubOrg")

  def githubRepo: String = get("githubRepo")

  /** COS release mirror bucket. */
  def cosBucket: String = get("cosBucket")

  /** Data directory name (install scripts render it; runtime dual-reads). */
  def homeDirName: String = get("homeDirName")

  /** Env var prefix (install scripts / dev tooling). */
  def envPrefix: String = get("envPrefix")

}
