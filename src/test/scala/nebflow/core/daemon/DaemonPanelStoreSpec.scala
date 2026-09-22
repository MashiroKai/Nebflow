package nebflow.core.daemon

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite

import java.nio.file.Files

/** daemonpanel Phase A — value store (backend F2/F4/F5 + credential states).
  *
  * Every case runs against a throwaway data root, so the suite never touches
  * the real `~/.nebflow` secrets namespace.
  */
class DaemonPanelStoreSpec extends CatsEffectSuite:

  private def json(s: String): Json = parse(s).fold(e => throw e, identity)

  private val decl = DaemonPanelSchema
    .validate(
      json("""
        {"fields": [
          {"key": "host", "label": "Host", "type": "string", "default": "imap.example.com"},
          {"key": "port", "label": "Port", "type": "number", "min": 1, "max": 65535},
          {"key": "tls", "label": "TLS", "type": "boolean", "default": true},
          {"key": "mode", "label": "Mode", "type": "enum", "options": ["ro", "rw"], "default": "ro"},
          {"key": "password", "label": "Password", "type": "secret"}
        ]}
      """),
      allowWeb = false
    )
    .toOption
    .get

  /** Fresh isolated root per test (deleted via the suite's cleanup). */
  private def withRoot[A](f: (DaemonPanelStore, os.Path) => IO[A]): IO[A] =
    IO.blocking(Files.createTempDirectory("dp-store-spec").toRealPath().toString).flatMap { dir =>
      val root = os.Path(dir)
      f(new DaemonPanelStore(root), root).guarantee(IO.blocking {
        try os.remove.all(root)
        catch case _: Throwable => ()
      })
    }

  // ── F2 · masking ──────────────────────────────────────────────────────

  test("F2 · every declared secret field reads back as *** and non-secrets keep their value") {
    withRoot { (store, _) =>
      for
        _ <- store.writeValues(decl, "mail", Map("host" -> Json.fromString("imap.test"), "password" -> Json.fromString("s3cret")))
        masked <- store.readMasked(decl, "mail")
      yield
        assertEquals(masked("password"), Json.fromString("***"))
        assertEquals(masked("host"), Json.fromString("imap.test"))
        // A declared-but-unset field falls back to its declared default, so the
        // UI shows something meaningful rather than an error.
        assertEquals(masked("port"), Json.Null) // no default declared
    }
  }

  test("F2 · the plaintext never appears in the stored values file nor in daemons.json") {
    withRoot { (store, root) =>
      for
        _ <- store.writeValues(decl, "mail", Map("password" -> Json.fromString("super-secret-value")))
        valuesBytes <- IO.blocking(if os.exists(store.valuesPath("mail")) then os.read(store.valuesPath("mail")) else "")
        secretBytes <- IO.blocking(os.read(store.secretPath("mail", "password")))
        // daemons.json is a different file; the store must not create one at all.
        daemonsExists <- IO.blocking(os.exists(root / "daemons.json"))
      yield
        assert(!valuesBytes.contains("super-secret-value"), "plaintext leaked into the values file (F5)")
        assert(!daemonsExists, "the panel store must never write daemons.json")
        // The secret lives in the existing secrets/ namespace (口径 α).
        assertEquals(secretBytes, "super-secret-value")
    }
  }

  test("F5 · the secret file is owner-only (0600) inside an owner-only dir (0700)") {
    withRoot { (store, _) =>
      for
        _ <- store.writeValues(decl, "mail", Map("password" -> Json.fromString("x")))
        states <- store.credentialStates(decl, "mail")
      yield
        assertEquals(states.map(_.key), List("password"))
        assertEquals(states.head.state, DaemonPanelSchema.CredentialStates.Ok)
        assertEquals(states.head.mode, Some("600"))
    }
  }

  test("F5 · a permissive secret file is reported permissive, not ok") {
    withRoot { (store, _) =>
      for
        _ <- store.writeValues(decl, "mail", Map("password" -> Json.fromString("x")))
        _ <- IO.blocking(os.perms.set(store.secretPath("mail", "password"), "rw-r--r--"))
        states <- store.credentialStates(decl, "mail")
      yield assertEquals(states.head.state, DaemonPanelSchema.CredentialStates.Permissive)
    }
  }

  test("F5 · an absent secret reports missing (the Phase B three-state contract)") {
    withRoot { (store, _) =>
      store.credentialStates(decl, "never-written").map { states =>
        assertEquals(states.map(_.state), List(DaemonPanelSchema.CredentialStates.Missing))
      }
    }
  }

  // ── F3 · unknown field ────────────────────────────────────────────────

  test("F3 · an undeclared key is rejected as unknown field and nothing is written") {
    withRoot { (store, _) =>
      for
        result <- store.writeValues(decl, "mail", Map("host" -> Json.fromString("a"), "backdoor" -> Json.fromString("1")))
        exists <- IO.blocking(os.exists(store.valuesPath("mail")))
      yield
        assertEquals(result.left.toOption.exists(_.startsWith("unknown field")), true)
        assert(!exists, "a rejected write must not create the values file")
    }
  }

  test("F3 · a value violating its declared type is rejected") {
    withRoot { (store, _) =>
      for
        badType <- store.writeValues(decl, "mail", Map("port" -> Json.fromString("not-a-number")))
        badRange <- store.writeValues(decl, "mail", Map("port" -> Json.fromInt(70000)))
        badEnum <- store.writeValues(decl, "mail", Map("mode" -> Json.fromString("nope")))
      yield
        assertEquals(badType.left.toOption.exists(_.contains("invalid value")), true)
        assertEquals(badRange.left.toOption.exists(_.contains("invalid value")), true)
        assertEquals(badEnum.left.toOption.exists(_.contains("invalid value")), true)
    }
  }

  // ── F4 · the *** round-trip is byte-stable ────────────────────────────

  test("F4 · writing *** back leaves the stored bytes identical (cmp-equal)") {
    withRoot { (store, _) =>
      for
        _ <- store.writeValues(decl, "mail", Map("host" -> Json.fromString("imap.test"), "password" -> Json.fromString("plain")))
        beforeValues <- IO.blocking(os.read(store.valuesPath("mail")))
        beforeSecret <- IO.blocking(os.read(store.secretPath("mail", "password")))
        // The UI echoes back exactly what it read: non-secrets as-is, secrets as ***.
        masked <- store.readMasked(decl, "mail")
        _ <- store.writeValues(decl, "mail", masked)
        afterValues <- IO.blocking(os.read(store.valuesPath("mail")))
        afterSecret <- IO.blocking(os.read(store.secretPath("mail", "password")))
      yield
        assertEquals(afterValues, beforeValues, "*** round-trip must not rewrite the values file (F4)")
        assertEquals(afterSecret, beforeSecret, "*** round-trip must not rewrite the secret (F4)")
    }
  }

  test("F4 · an empty string does not modify the stored secret") {
    withRoot { (store, _) =>
      for
        _ <- store.writeValues(decl, "mail", Map("password" -> Json.fromString("keep-me")))
        _ <- store.writeValues(decl, "mail", Map("password" -> Json.fromString("")))
        secret <- IO.blocking(os.read(store.secretPath("mail", "password")))
      yield assertEquals(secret, "keep-me")
    }
  }

  test("F4 · a new plaintext replaces the stored secret") {
    withRoot { (store, _) =>
      for
        _ <- store.writeValues(decl, "mail", Map("password" -> Json.fromString("old")))
        _ <- store.writeValues(decl, "mail", Map("password" -> Json.fromString("new")))
        secret <- IO.blocking(os.read(store.secretPath("mail", "password")))
        states <- store.credentialStates(decl, "mail")
      yield
        assertEquals(secret, "new")
        assertEquals(states.head.state, DaemonPanelSchema.CredentialStates.Ok)
    }
  }

  test("F5 · a permissive secrets directory makes a secret write fail-closed") {
    withRoot { (store, _) =>
      for
        _ <- IO.blocking(os.makeDir.all(store.secretsDir))
        _ <- IO.blocking(os.perms.set(store.secretsDir, "rwxrwxrwx"))
        result <- store.writeValues(decl, "mail", Map("password" -> Json.fromString("x")))
        // The refusal must be reported, never silently swallowed.
        exists <- IO.blocking(os.exists(store.secretPath("mail", "password")))
      yield
        assertEquals(result.isLeft, true, "a world-writable secrets dir must fail closed")
        assert(!exists, "nothing may be written into a permissive secrets dir")
    }
  }
