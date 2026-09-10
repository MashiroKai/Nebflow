package nebflow.core

/**
 * JDK baseline gate — the gateway requires Java 21 or newer.
 *
 * This object is deliberately **dependency-free**: it imports nothing from
 * `nebflow.*` and touches no Java 21+ API. That is the precondition for it to
 * be usable at all — it has to load and print its message on an *older* JVM,
 * i.e. exactly on the JVMs it is there to reject. Class-init must not drag in
 * the library surface (logback, http4s, pekko, …) whose class files or
 * multi-release entries may themselves need a newer runtime.
 *
 * Detection order (fail-loud, never fail-open):
 *   1. `java.specification.version` — `"1.8"` → 8, `"1.N"` → N, otherwise the
 *      value parsed as an integer (`"17"`, `"21"`, `"23"`).
 *   2. fallback: `java.version` with everything from the first non-digit cut
 *      off (`"23.0.1"` → 23).
 *   3. both unreadable / unparsable → **not satisfied**. A version that cannot
 *      be established is treated as too old; guessing "probably fine" here
 *      would silently boot on an unsupported JVM.
 *
 * The version is read through an injectable lookup function so the decision
 * table can be unit-tested without spawning JVMs (see `JvmRequirementSpec`).
 */
object JvmRequirement:

  /** Lowest Java feature release the gateway runs on. */
  val MinimumFeatureVersion: Int = 21

  /** `true` when the running JVM satisfies [[MinimumFeatureVersion]]. */
  def isSatisfied: Boolean = isSatisfiedWith(defaultLookup)

  /** Feature release of the running JVM, when it could be determined. */
  def detectedFeatureVersion: Option[Int] = detectFeatureVersion(defaultLookup)

  /** Human-readable refusal text — printed verbatim by the startup gate.
    *
    * Shape follows the existing GatewayMain argument-gate message: an `ERROR:`
    * headline, what to do about it, then two diagnostic lines.
    */
  def errorText: String =
    errorTextFor(
      detectedFeatureVersion,
      jarPathOfSelf,
      safeProperty("java.version"),
      safeProperty("java.home")
    )

  // ---------------------------------------------------------------- detection

  private def defaultLookup(name: String): String = System.getProperty(name)

  /** Read a property for *diagnostic* purposes only — never on the gate path,
    * so a `SecurityException` degrades the banner instead of crashing it.
    */
  private def safeProperty(name: String): String =
    try
      val v = System.getProperty(name)
      if v == null then "unknown" else v
    catch case scala.util.control.NonFatal(_) => "unknown"

  /** No-arg `isSatisfied` behind an injectable property reader. */
  private[core] def isSatisfiedWith(lookup: String => String): Boolean =
    detectFeatureVersion(lookup) match
      case Some(v) => v >= MinimumFeatureVersion
      case None    => false

  private[core] def detectFeatureVersion(lookup: String => String): Option[Int] =
    fromSpecificationVersion(lookup) orElse fromLegacyVersion(lookup)

  /** `java.specification.version`: `"1.8"` → 8, `"21"` → 21. */
  private def fromSpecificationVersion(lookup: String => String): Option[Int] =
    readQuietly(lookup, "java.specification.version").flatMap { raw =>
      val v = raw.trim
      if v.startsWith("1.") then v.drop(2).toIntOption
      else v.toIntOption
    }

  /** Legacy fallback: leading digits of `java.version` (`"23.0.1"` → 23). */
  private def fromLegacyVersion(lookup: String => String): Option[Int] =
    readQuietly(lookup, "java.version").flatMap { raw =>
      val digits = raw.trim.takeWhile(_.isDigit)
      if digits.isEmpty then None else digits.toIntOption
    }

  private def readQuietly(lookup: String => String, name: String): Option[String] =
    try Option(lookup(name)).map(_.trim).filter(_.nonEmpty)
    catch case scala.util.control.NonFatal(_) => None

  /** Where this class was loaded from — the assembly jar in a real install. */
  private def jarPathOfSelf: String =
    try
      Option(JvmRequirement.getClass.getProtectionDomain)
        .flatMap(d => Option(d.getCodeSource))
        .map(_.getLocation.getPath)
        .getOrElse("unknown")
    catch case scala.util.control.NonFatal(_) => "unknown"

  // ------------------------------------------------------------------ message

  private[core] def errorTextFor(
    detected: Option[Int],
    jar: String,
    javaVersion: String,
    javaHome: String
  ): String =
    val found = detected match
      case Some(v) => s"Java $v"
      case None    => "an unreadable Java version"
    s"""ERROR: nebflow requires Java $MinimumFeatureVersion or newer — this JVM is $found.
       |The gateway will not start on an older JVM. Upgrade it, then start again:
       |  macOS:   brew install openjdk@21 && export JAVA_HOME=$$(/usr/libexec/java_home -v 21)
       |  Windows: winget install EclipseAdoptium.Temurin.21.JDK
       |  Linux:   sudo apt install openjdk-21-jre
       |The CLI (`nebflow update`, `doctor`, `version`) still runs on this JVM.
       |Jar: $jar
       |Java: $javaVersion ($javaHome)""".stripMargin
