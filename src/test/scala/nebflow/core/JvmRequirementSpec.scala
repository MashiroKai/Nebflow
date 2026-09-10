package nebflow.core

import munit.FunSuite

/**
 * JDK 基线闸（B 轨）契约：判定表二值化 + 无法判定时 fail-loud（禁 fail-open）。
 *
 * 全程用注入的 property reader，起真 JVM 无法覆盖 17/23/异常分支——这正是
 * `JvmRequirement` 把判定拆成 `...With(lookup)` seam 的原因。
 */
class JvmRequirementSpec extends FunSuite:

  private def props(kv: (String, String)*): String => String =
    val m = kv.toMap
    name => m.getOrElse(name, null)

  private val throwing: String => String = _ => throw new SecurityException("denied")

  private def satisfied(kv: (String, String)*): Boolean =
    JvmRequirement.isSatisfiedWith(props(kv*))

  // ------------------------------------------------------ 1. specification.version

  test("java.specification.version = 1.8 → 8 → NOT satisfied") {
    assertEquals(JvmRequirement.detectFeatureVersion(props("java.specification.version" -> "1.8")), Some(8))
    assert(!satisfied("java.specification.version" -> "1.8"))
  }

  test("java.specification.version = 17 → 17 → NOT satisfied") {
    assertEquals(JvmRequirement.detectFeatureVersion(props("java.specification.version" -> "17")), Some(17))
    assert(!satisfied("java.specification.version" -> "17"))
  }

  test("java.specification.version = 21 → 21 → satisfied (boundary)") {
    assertEquals(JvmRequirement.detectFeatureVersion(props("java.specification.version" -> "21")), Some(21))
    assert(satisfied("java.specification.version" -> "21"))
  }

  test("java.specification.version = 23 → 23 → satisfied") {
    assertEquals(JvmRequirement.detectFeatureVersion(props("java.specification.version" -> "23")), Some(23))
    assert(satisfied("java.specification.version" -> "23"))
  }

  test("boundary: 20 is not enough, 21 is") {
    assert(!satisfied("java.specification.version" -> "20"))
    assert(satisfied("java.specification.version" -> "21"))
  }

  // ------------------------------------------------------ 2. fallback / fail-loud

  test("blank specification.version falls back to java.version leading digits") {
    assertEquals(
      JvmRequirement.detectFeatureVersion(
        props("java.specification.version" -> "  ", "java.version" -> "23.0.1")
      ),
      Some(23)
    )
    assertEquals(
      JvmRequirement.detectFeatureVersion(
        props("java.specification.version" -> "", "java.version" -> "17.0.11")
      ),
      Some(17)
    )
  }

  test("missing specification.version property (null) falls back to java.version") {
    assertEquals(
      JvmRequirement.detectFeatureVersion(props("java.version" -> "21.0.2")),
      Some(21)
    )
    assert(satisfied("java.version" -> "21.0.2"))
  }

  test("non-numeric specification.version falls back to java.version") {
    assertEquals(
      JvmRequirement.detectFeatureVersion(
        props("java.specification.version" -> "abc", "java.version" -> "21.0.2")
      ),
      Some(21)
    )
    assert(!satisfied("java.specification.version" -> "abc", "java.version" -> "17"))
  }

  test("undeterminable version (both blank / non-numeric) → NOT satisfied (fail-loud, not fail-open)") {
    assertEquals(JvmRequirement.detectFeatureVersion(props()), None)
    assert(!satisfied())
    assert(!satisfied("java.specification.version" -> "abc", "java.version" -> "openjdk"))
    assert(!satisfied("java.specification.version" -> "", "java.version" -> ""))
  }

  test("property lookup throwing → NOT satisfied (fail-loud)") {
    assertEquals(JvmRequirement.detectFeatureVersion(throwing), None)
    assert(!JvmRequirement.isSatisfiedWith(throwing))
  }

  test("specification.version throwing but java.version readable → still decided by fallback") {
    val lookup: String => String = name =>
      if name == "java.specification.version" then throw new RuntimeException("boom")
      else if name == "java.version" then "17.0.11"
      else null
    assertEquals(JvmRequirement.detectFeatureVersion(lookup), Some(17))
    assert(!JvmRequirement.isSatisfiedWith(lookup))
  }

  // ------------------------------------------------------ 3. errorText

  test("errorText carries the ERROR headline, three platform hints and the two diagnostics") {
    val t = JvmRequirement.errorTextFor(Some(17), "/app/app.jar", "17.0.11", "/opt/java/17")
    assert(t.startsWith("ERROR: "), t.take(40))
    assert(t.contains("Java 17"), t)
    assert(t.contains("Java 21"), t)
    assert(t.contains("macOS:"), t)
    assert(t.contains("Windows:"), t)
    assert(t.contains("Linux:"), t)
    assert(t.contains("Jar: /app/app.jar"), t)
    assert(t.contains("Java: 17.0.11 (/opt/java/17)"), t)
  }

  test("errorText for an undeterminable version stays loud and still lists the platform hints") {
    val t = JvmRequirement.errorTextFor(None, "unknown", "unknown", "unknown")
    assert(t.startsWith("ERROR: "), t.take(40))
    assert(t.contains("unreadable"), t)
    assert(t.contains("macOS:") && t.contains("Windows:") && t.contains("Linux:"), t)
    assert(t.contains("Jar: unknown"), t)
  }

  test("errorText (no-arg, live JVM) is self-contained and names the baseline") {
    val t = JvmRequirement.errorText
    assert(t.startsWith("ERROR: "), t.take(40))
    assert(t.contains(s"Java ${JvmRequirement.MinimumFeatureVersion}"), t)
    assert(t.contains("\nJar: ") && t.contains("\nJava: "), t)
  }

  test("the running test JVM (>= 21 in this build) is itself satisfied") {
    // Guards the live no-arg path end to end.
    assertEquals(JvmRequirement.isSatisfied, JvmRequirement.detectedFeatureVersion.exists(_ >= 21))
  }
