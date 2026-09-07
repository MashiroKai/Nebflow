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

  /**
   * Primary domain. 单域（2026-09-07 命名边界裁定：neblink.space 退役）：默认 =
   * nebflow.space（brand.conf 真值），可用 `NEBFLOW_BRAND_DOMAIN` env 覆盖
   * （复用 L3 dualEnv 双前缀读取），默认无需再覆盖。display-only 语义：
   * 前端 profile 链接必须消费 `profileUrl`，禁止用 domain 构建 URL。
   */
  val domain: String = env("BRAND_DOMAIN").getOrElse(get("domain"))

  /** Profile 页 URL——前端 profile 链接的唯一来源（activityBar.js 消费它拼
    * URL，与 domain 解耦）。默认 = nebflow.space，可用
    * `NEBFLOW_PROFILE_URL` env 覆盖。 */
  val profileUrl: String = env("PROFILE_URL").getOrElse(get("profileUrl"))

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

  /** Config file name — L3 batch 3 dual-read: read prefers this name, falls
    * back to the hardcoded legacy "nebflow.json"; writes always use this
    * name (first read-modify-write completes the rename migration). */
  val configFileName: String = get("configFileName")

  /** Release mirror bucket (COS). */
  val cosBucket: String = get("cosBucket")

  /** Device-interop subsystem name (D2: stays as-is across the rename). */
  val subsystemName: String = get("subsystemName")

  // ── Derived helpers ──────────────────────────────────────────────────────

  /** Dual-prefix env read (L3): try the brand prefix (brand.conf envPrefix)
    * first, then fall back to the HARDCODED legacy "NEBFLOW_" prefix — the
    * legacy prefix is a historical fact, not a brand value, so it never
    * derives from conf (a derived fallback could never miss). When envPrefix
    * is NEBFLOW (current), both lookups name the same variable: zero
    * behavior change. On rename day, old scripts / launchd units / CI
    * exports that still set NEBFLOW_* keep working.
    *
    * `name` is the bare suffix without prefix, e.g. "HOME", "GATEWAY_PORT". */
  def env(name: String): Option[String] =
    dualEnv(sys.env, envPrefix, name)

  /** Pure core of `env` (parameterized for the spec — the live prefix is a
    * compile-time constant baked from brand.conf and cannot be varied). */
  private[core] def dualEnv(env: Map[String, String], prefix: String, name: String): Option[String] =
    env.get(s"${prefix}_$name").orElse(env.get(s"NEBFLOW_$name"))

  /** install.ps1 companion of installUrl, derived by swapping the install.sh
    * basename (same host and dir — current true values travel together).
    * Returns installUrl unchanged when the basename doesn't match, so a
    * differently-shaped installUrl degrades to serving the .sh everywhere
    * instead of inventing a broken URL. */
  def installPs1Url: String =
    swapInstallScript(installUrl, "install.ps1")

  /** uninstall.sh companion of installUrl (see installPs1Url). */
  def uninstallUrl: String =
    swapInstallScript(installUrl, "uninstall.sh")

  /** uninstall.ps1 companion of installUrl (see installPs1Url). */
  def uninstallPs1Url: String =
    swapInstallScript(installUrl, "uninstall.ps1")

  private def swapInstallScript(url: String, replacement: String): String =
    if url.endsWith("/install.sh") then url.dropRight("install.sh".length) + replacement else url

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
