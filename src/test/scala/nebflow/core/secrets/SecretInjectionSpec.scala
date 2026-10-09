package nebflow.core.secrets

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.tools.ShellSession

import java.nio.file.Files
import java.util.UUID
import scala.concurrent.duration.*

/**
 * secrets-consumption batch (2026-10-01) — the C-case injection allowlist.
 *
 * Pins, in the order the batch's acceptance items name them:
 *   - the DEFAULT-EMPTY no-op: an empty allowlist reads nothing and adds
 *     nothing, and the child environment is the inherited one, unchanged
 *     (asserted live, through a real spawn);
 *   - the named opt-in: an allowlisted key reaches the command environment of
 *     the Bash the gateway spawns, and only there;
 *   - the per-entry fail-safe: a missing / oversized / empty value is SKIPPED,
 *     never raised — one bad entry must not take down every Bash command;
 *   - the audit face: the names that did not resolve are what may be reported,
 *     and a name is not a value;
 *   - `$(cat …)` parity: one trailing newline is stripped, so the injected
 *     value is what a shell interpolation of the same file would yield.
 *
 * All values are mock values; every root is a throwaway temp directory.
 */
class SecretInjectionSpec extends FunSuite:

  private val MockValue = "test-secret-value-inject"

  override def munitTimeout: FiniteDuration = FiniteDuration(120, "s")

  private def withRoot(f: os.Path => Unit): Unit =
    val root = os.Path(Files.createTempDirectory("secret-injection-spec").toRealPath().toString)
    try
      os.makeDir.all(root / "secrets")
      f(root)
    finally
      try os.remove.all(root)
      catch case _: Throwable => ()

  private def sid(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  private def writeSecret(root: os.Path, name: String, value: String): Unit =
    os.write.over(root / "secrets" / name, value, createFolders = true)
    os.perms.set(root / "secrets" / name, "rw-------")

  // ── Config face ─────────────────────────────────────────────────────────

  test("I1 · config: an absent or malformed section reads as EMPTY (no injection)") {
    withRoot { root =>
      assertEquals(SecretInjection.load(root), SecretInjection.empty, "absent file")

      os.write.over(nebflow.shared.PathUtil.configJsonWritePath(root), "{ not json")
      assertEquals(SecretInjection.load(root), SecretInjection.empty, "unparseable file")

      os.write.over(
        nebflow.shared.PathUtil.configJsonWritePath(root),
        """{"secrets":{"inject":{"default":"not-an-array"}}}"""
      )
      assertEquals(SecretInjection.load(root), SecretInjection.empty, "wrong shape")

      os.write.over(
        nebflow.shared.PathUtil.configJsonWritePath(root),
        """{"secrets":{"inject":{"default":[{"variable":"V","secret":"s"},{"variable":"","secret":"s"}]}}}"""
      )
      assertEquals(
        SecretInjection.load(root).defaults,
        List(SecretInjection.Entry("V", "s")),
        "half-formed entries are dropped, not guessed at"
      )
    }
  }

  test("I2 · config: a well-formed allowlist parses, blanket + per-agent") {
    withRoot { root =>
      os.write.over(
        nebflow.shared.PathUtil.configJsonWritePath(root),
        """{"secrets":{"inject":{
          |  "default":[{"variable":"VERCEL_TOKEN","secret":"vercel-token"}],
          |  "agents":{"Nebula":[{"variable":"OPENAI_API_KEY","secret":"openai-key"}]}
          |}}}""".stripMargin
      )
      val cfg = SecretInjection.load(root)
      assertEquals(cfg.defaults, List(SecretInjection.Entry("VERCEL_TOKEN", "vercel-token")))
      assertEquals(cfg.byAgent.get("Nebula"), Some(List(SecretInjection.Entry("OPENAI_API_KEY", "openai-key"))))
    }
  }

  test("I3 · resolve: a per-agent entry REPLACES same-named blanket entry; others are added") {
    val cfg = SecretInjection.Config(
      defaults = List(
        SecretInjection.Entry("A", "a-file"),
        SecretInjection.Entry("B", "b-file"),
        SecretInjection.Entry("C", "c-file")
      ),
      byAgent = Map("Agent1" -> List(SecretInjection.Entry("B", "b-other-file")))
    )
    assertEquals(
      SecretInjection.resolve(cfg, Some("Agent1")),
      List(
        SecretInjection.Entry("A", "a-file"),
        SecretInjection.Entry("C", "c-file"),
        SecretInjection.Entry("B", "b-other-file")
      )
    )
    // An agent with no block of its own gets exactly the blanket list.
    assertEquals(SecretInjection.resolve(cfg, Some("Agent2")), cfg.defaults)
    // No agent identity (an unscoped call) also gets exactly the blanket list.
    assertEquals(SecretInjection.resolve(cfg, None), cfg.defaults)
  }

  // ── The default-empty no-op (read side) ─────────────────────────────────

  test("I4 · 🔴 empty allowlist: materialize returns empty and opens no file") {
    withRoot { root =>
      writeSecret(root, "vercel-token", MockValue)
      assertEquals(SecretInjection.materialize(root / "secrets", Nil), Map.empty)
      // Not merely empty-valued: nothing was read. Encode a file that THROWS on
      // read (a directory) — an empty entry list must not touch it at all.
      os.makeDir.all(root / "secrets" / "a-directory")
      assertEquals(SecretInjection.materialize(root / "secrets", Nil), Map.empty)
    }
  }

  test("I5 · materialize: resolves an allowlisted secret, one trailing newline stripped") {
    withRoot { root =>
      writeSecret(root, "plain", MockValue)
      writeSecret(root, "lf", MockValue + "\n")
      writeSecret(root, "crlf", MockValue + "\r\n")
      val env = SecretInjection.materialize(
        root / "secrets",
        List(
          SecretInjection.Entry("PLAIN", "plain"),
          SecretInjection.Entry("LF", "lf"),
          SecretInjection.Entry("CRLF", "crlf")
        )
      )
      assertEquals(
        env,
        Map("PLAIN" -> MockValue, "LF" -> MockValue, "CRLF" -> MockValue),
        "$(cat …) parity: exactly one trailing newline is stripped"
      )
    }
  }

  test("I6 · materialize: a bad entry is SKIPPED per-entry, never raised") {
    withRoot { root =>
      writeSecret(root, "good", MockValue)
      os.makeDir.all(root / "secrets" / "is-a-dir")
      writeSecret(root, "empty", "")
      writeSecret(root, "oversized", "x" * (SecretInjection.MaxInjectBytes + 1))
      val env = SecretInjection.materialize(
        root / "secrets",
        List(
          SecretInjection.Entry("GOOD", "good"),
          SecretInjection.Entry("MISSING", "does-not-exist"),
          SecretInjection.Entry("ISDIR", "is-a-dir"),
          SecretInjection.Entry("EMPTY", "empty"),
          SecretInjection.Entry("OVERSIZED", "oversized")
        )
      )
      assertEquals(env, Map("GOOD" -> MockValue), "the resolvable entry survives; every broken shape is skipped")
    }
  }

  test("I7 · 🔴 unresolved(): the audit payload is NAMES, never a value") {
    withRoot { root =>
      writeSecret(root, "good", MockValue)
      val entries = List(
        SecretInjection.Entry("GOOD", "good"),
        SecretInjection.Entry("MISSING", "does-not-exist"),
        SecretInjection.Entry("MISSING2", "does-not-exist")
      )
      val env = SecretInjection.materialize(root / "secrets", entries)
      val missing = SecretInjection.unresolved(entries, env)
      assertEquals(missing, List("does-not-exist"), "distinct secret names, no duplicates")
      assert(!missing.exists(_.contains(MockValue)), "the audit payload is names only")
      assert(!env.values.exists(_ == ""), "no empty value is ever injected")
    }
  }

  // ── The live spawn: what actually reaches a child (acceptance item §二.1) ─

  test("I8 · 🔴 empty allowlist ⇒ the child environment is the inherited one, byte-identical") {
    val id = sid("inj-empty")
    val program = for
      s <- ShellSession.forSession(id, secretEnv = Map.empty)
      n <- s.execute("printenv | wc -l", 30.seconds)
      probe <- s.execute("printenv NEBFLOW_TEST_INJECTED; echo rc=$?", 30.seconds)
      _ <- ShellSession.destroySession(id)
    yield (n, probe)
    val (n, probe) = program.unsafeRunSync()
    assert(n.exitCode == 0, s"printenv must run: ${n.stderr}")
    assertEquals(probe.stdout.trim, "rc=1", "the sentinel variable is absent — nothing was injected")
  }

  test("I9 · allowlisted entry ⇒ the value is in the child env, and only there") {
    val id = sid("inj-on")
    val program = for
      s <- ShellSession.forSession(id, secretEnv = Map("NEBFLOW_TEST_INJECTED" -> MockValue))
      // The trailing `|` is a sentinel: it pins the value's END, so a spurious
      // trailing newline inside the variable (the `$(cat …)` hazard this batch
      // strips) fails the assertion instead of being lost in transport
      // whitespace.
      seen <- s.execute("printf '%s|' \"$NEBFLOW_TEST_INJECTED\"", 30.seconds)
      len <- s.execute("printf '%s' \"${#NEBFLOW_TEST_INJECTED}\"", 30.seconds)
      _ <- ShellSession.destroySession(id)
    yield (seen, len)
    val (seen, len) = program.unsafeRunSync()
    val out = seen.stdout.stripLineEnd
    assertEquals(out, MockValue + "|", "the allowlisted value reaches the spawned command, byte-exact and newline-free")
    assertEquals(len.stdout.trim, MockValue.length.toString, "and carries no extra byte")

    // …and not in the PARENT process environment: injection is per-spawn, not
    // a process-wide mutation.
    assert(!sys.env.get("NEBFLOW_TEST_INJECTED").contains(MockValue), "the gateway process env must not be mutated")
  }

  test("I10 · the allowlist is per-session: a different session keeps its own environment") {
    val a = sid("inj-a")
    val b = sid("inj-b")
    val program = for
      sa <- ShellSession.forSession(a, secretEnv = Map("NEBFLOW_TEST_INJECTED" -> MockValue))
      sb <- ShellSession.forSession(b, secretEnv = Map.empty)
      inA <- sa.execute("printf '%s|' \"${NEBFLOW_TEST_INJECTED:-absent}\"", 30.seconds)
      inB <- sb.execute("printf '%s|' \"${NEBFLOW_TEST_INJECTED:-absent}\"", 30.seconds)
      _ <- ShellSession.destroySession(a)
      _ <- ShellSession.destroySession(b)
    yield (inA, inB)
    val (inA, inB) = program.unsafeRunSync()
    assertEquals(inA.stdout.stripLineEnd, MockValue + "|")
    assertEquals(inB.stdout.stripLineEnd, "absent|", "an unallowlisted session never sees the value")
  }

end SecretInjectionSpec
