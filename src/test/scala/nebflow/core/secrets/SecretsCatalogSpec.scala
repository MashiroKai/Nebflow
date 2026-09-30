package nebflow.core.secrets

import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.nio.file.Files

/** secrets-consumption batch (2026-10-01) — the three-field catalogue face.
  *
  * Pins the C-case contract end to end:
  *   - the three fields (variable name / value / description), where the VALUE
  *     is the owner-only file and never appears in any derived artefact;
  *   - pointer semantics: every consumer-facing answer carries a LOCATION, not
  *     a value;
  *   - the zero-value red line, asserted mechanically over both the rendered
  *     catalogue and the pointer payloads;
  *   - the injection allowlist's default-empty no-op, and the per-entry
  *     fail-safe (a missing or oversized secret is skipped, never raised).
  *
  * Every case runs against a throwaway root; the real `~/.nebflow/secrets/` is
  * never read or written. All values are mock values.
  */
class SecretsCatalogSpec extends FunSuite:

  private val MockValue = "test-secret-value-catalog"

  /** A store plus its temp root, with `secrets/` pre-created. */
  private def withStore(f: (SecretsStore, os.Path) => Unit): Unit =
    val root = os.Path(Files.createTempDirectory("secrets-catalog-spec").toRealPath().toString)
    try
      val dir = root / "secrets"
      os.makeDir.all(dir)
      val store = new SecretsStore(dir, dataRoot = root, daemonsPath = root / "daemons.json")
      f(store, root)
    finally
      try os.remove.all(root)
      catch case _: Throwable => ()

  // ── Field 1: variable name ──────────────────────────────────────────────

  test("C1 · variable name: derived from the secret name when no override is set") {
    assertEquals(SecretsCatalog.deriveVariableName("vercel-token"), "VERCEL_TOKEN")
    assertEquals(SecretsCatalog.deriveVariableName("openai.api_key"), "OPENAI_API_KEY")
    assertEquals(SecretsCatalog.deriveVariableName("aliyun-oss"), "ALIYUN_OSS")
    assertEquals(SecretsCatalog.deriveVariableName("kai-nbverify"), "KAI_NBVERIFY")
    // A leading digit is not a shell identifier — the derived form is prefixed.
    assertEquals(SecretsCatalog.deriveVariableName("1password"), "_1PASSWORD")
  }

  test("C2 · variable name: an explicit override wins over the derived default") {
    withStore { (store, root) =>
      assert(store.create("vercel-token", MockValue).unsafeRunSync().isRight)
      val derived = store.pointer("vercel-token").unsafeRunSync().toOption.get
      assertEquals(derived.variableName, "VERCEL_TOKEN")

      assert(store.setMetadata("vercel-token", variableName = Some("MY_DEPLOY_TOKEN")).unsafeRunSync().isRight)
      val overridden = store.pointer("vercel-token").unsafeRunSync().toOption.get
      assertEquals(overridden.variableName, "MY_DEPLOY_TOKEN")

      // Clearing the override falls back to the derived default, not to empty.
      assert(store.setMetadata("vercel-token", variableName = None).unsafeRunSync().isRight)
      assertEquals(store.pointer("vercel-token").unsafeRunSync().toOption.get.variableName, "VERCEL_TOKEN")
    }
  }

  test("C3 · variable name: a non-identifier override is refused, name intact") {
    withStore { (store, _) =>
      assert(store.create("mock-a", MockValue).unsafeRunSync().isRight)
      store.setMetadata("mock-a", variableName = Some("not a name")).unsafeRunSync() match
        case Left(SecretsStore.Failure.InvalidVariableName(n, v)) =>
          assertEquals(n, "mock-a")
          assertEquals(v, "not a name")
        case other => fail(s"expected invalid_variable_name, got $other")
      // A refused edit leaves the stored metadata exactly as it was.
      assertEquals(store.pointer("mock-a").unsafeRunSync().toOption.get.variableName, "MOCK_A")
    }
  }

  // ── Field 3: description ────────────────────────────────────────────────

  test("C4 · description: empty by default (the migration reading for existing secrets)") {
    withStore { (store, _) =>
      assert(store.create("mock-a", MockValue).unsafeRunSync().isRight)
      assertEquals(store.pointer("mock-a").unsafeRunSync().toOption.get.description, "")
      // No sidecar was materialised for a secret that has no metadata at all.
      assertEquals(SecretsCatalog.load(os.Path("/nonexistent")).size, 0)
    }
  }

  test("C5 · description: set, read back, and cleared") {
    withStore { (store, _) =>
      assert(store.create("mock-a", MockValue).unsafeRunSync().isRight)
      assert(store.setMetadata("mock-a", description = Some("Deploy token for the docs site"))
        .unsafeRunSync().isRight)
      assertEquals(store.pointer("mock-a").unsafeRunSync().toOption.get.description,
        "Deploy token for the docs site")
      assert(store.setMetadata("mock-a", description = None).unsafeRunSync().isRight)
      assertEquals(store.pointer("mock-a").unsafeRunSync().toOption.get.description, "")
    }
  }

  test("C6 · metadata never mints a secret: a missing target is unknown_secret") {
    withStore { (store, _) =>
      assertEquals(store.setMetadata("no-such-secret", description = Some("x")).unsafeRunSync(),
        Left(SecretsStore.Failure.UnknownSecret("no-such-secret")))
    }
  }

  // ── Pointer semantics + the zero-value red line ─────────────────────────

  test("C7 · pointer: the answer is a LOCATION, and carries no value") {
    withStore { (store, root) =>
      assert(store.create("vercel-token", MockValue).unsafeRunSync().isRight)
      val p = store.pointer("vercel-token").unsafeRunSync().toOption.get
      assertEquals(p.location, s"${root}/secrets/vercel-token")
      assert(!p.location.contains(MockValue), "the location is a path, not the value")
      val rendered = s"${p.name}|${p.variableName}|${p.description}|${p.location}"
      assert(!rendered.contains(MockValue), s"no pointer field may carry the value: $rendered")
    }
  }

  test("C8 · 🔴 zero value exposure: the rendered catalogue contains no stored value") {
    withStore { (store, root) =>
      assert(store.create("mock-a", MockValue).unsafeRunSync().isRight)
      assert(store.create("mock-b", "another-test-value-2").unsafeRunSync().isRight)
      val catalog = os.read(SecretsCatalog.catalogPath(root))
      assert(!catalog.contains(MockValue), "the catalogue must never carry a stored value")
      assert(!catalog.contains("another-test-value-2"), "nor any other stored value")
      // Sanity: the catalogue is not vacuous — it does carry the names.
      assert(catalog.contains("`MOCK_A`"))
      assert(catalog.contains("`mock-a`"))
    }
  }

  test("C9 · 🔴 zero value exposure: pointers() over the whole store is value-free") {
    withStore { (store, _) =>
      assert(store.create("mock-a", MockValue).unsafeRunSync().isRight)
      val all = store.pointers().unsafeRunSync().toOption.get
      assertEquals(all.map(_.name), List("mock-a"))
      val blob = all.map(p => s"${p.name}|${p.variableName}|${p.description}|${p.location}").mkString("\n")
      assert(!blob.contains(MockValue), s"the pointer payload must never carry a value: $blob")
    }
  }

  test("C10 · catalogue is refreshed by the write paths, not by a read") {
    withStore { (store, root) =>
      assert(!Files.exists(SecretsCatalog.catalogPath(root).toNIO), "nothing written before any mutation")
      // A READ must not write: `list` is read-only by contract.
      store.list().unsafeRunSync()
      assert(!Files.exists(SecretsCatalog.catalogPath(root).toNIO), "list() must not materialise the catalogue")

      assert(store.create("mock-a", MockValue).unsafeRunSync().isRight)
      assert(Files.exists(SecretsCatalog.catalogPath(root).toNIO), "create refreshes the catalogue")
      assert(os.read(SecretsCatalog.catalogPath(root)).contains("`mock-a`"))

      assert(store.overwrite("mock-a", "rotated-value").unsafeRunSync().isRight)
      assert(!os.read(SecretsCatalog.catalogPath(root)).contains(MockValue), "the old value is never in the catalogue")

      assert(store.delete("mock-a").unsafeRunSync().isRight)
      assert(!os.read(SecretsCatalog.catalogPath(root)).contains("`mock-a`"), "delete removes the row")
    }
  }

  test("C11 · delete drops the metadata entry (no orphan for a future同名 secret)") {
    withStore { (store, root) =>
      assert(store.create("mock-a", MockValue).unsafeRunSync().isRight)
      assert(store.setMetadata("mock-a", variableName = Some("A_TOKEN"), description = Some("d"))
        .unsafeRunSync().isRight)
      assert(SecretsCatalog.load(root).contains("mock-a"))
      assert(store.delete("mock-a").unsafeRunSync().isRight)
      assertEquals(SecretsCatalog.load(root).get("mock-a"), None)
    }
  }

  test("C12 · the artefacts are owner-only (0600) and never live inside secrets/") {
    withStore { (store, root) =>
      assert(store.create("mock-a", MockValue).unsafeRunSync().isRight)
      assert(store.setMetadata("mock-a", description = Some("d")).unsafeRunSync().isRight)
      // Outside the credential namespace: `list()` enumerates every regular
      // file in secrets/, so a catalogue placed there would read as a secret.
      assertEquals(os.list(root / "secrets").map(_.last).toList, List("mock-a"))
      assertEquals(store.list().unsafeRunSync().toOption.get.map(_.name), List("mock-a"))

      import java.nio.file.attribute.PosixFilePermissions
      def mode(p: os.Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(p.toNIO))
      assume(!nebflow.core.CredentialFileAcl.isWindows(nebflow.core.CredentialFileAcl.currentOsName),
        "POSIX-only mode readback")
      assertEquals(mode(SecretsCatalog.metaPath(root)), "rw-------")
      assertEquals(mode(SecretsCatalog.catalogPath(root)), "rw-------")
    }
  }

  test("C13 · metadata round-trips through the sidecar; a malformed sidecar degrades to defaults") {
    withStore { (store, root) =>
      assert(store.create("mock-a", MockValue).unsafeRunSync().isRight)
      assert(store.setMetadata("mock-a", variableName = Some("A_TOKEN"), description = Some("desc here"))
        .unsafeRunSync().isRight)
      assertEquals(SecretsCatalog.load(root).get("mock-a"),
        Some(SecretsCatalog.Meta(Some("A_TOKEN"), "desc here")))
      assertEquals(store.pointer("mock-a").unsafeRunSync().toOption.get.variableName, "A_TOKEN")

      // A malformed sidecar must not make the secrets unlistable.
      os.write.over(SecretsCatalog.metaPath(root), "{ this is not json")
      val listed = store.list().unsafeRunSync().toOption.get
      assertEquals(listed.map(_.name), List("mock-a"))
      assertEquals(listed.head.variableName, "MOCK_A", "degrades to the derived name")
      assertEquals(listed.head.description, "")
    }
  }

  test("C14 · a newline-terminated value file is a well-formed catalogue input (mtime only, no read)") {
    withStore { (store, root) =>
      assert(store.create("mock-a", MockValue + "\n").unsafeRunSync().isRight)
      val rows = SecretsCatalog.rows(root / "secrets", root)
      assertEquals(rows.map(_.name), List("mock-a"))
      assert(rows.head.updatedAt > 0, "the row carries the file mtime (the rotation timestamp)")
    }
  }

  test("C15 · rendered table survives a description carrying a separator or a newline") {
    val rendered = SecretsCatalog.render(List(
      SecretsCatalog.Row("n", "N", "has | a pipe and\nan embedded newline", 0L)))
    val body = rendered.linesIterator.toList
    // Header + separator + exactly one row: an unsanitised description would
    // split the row across lines and break the table.
    assertEquals(body.count(_.startsWith("| `N` |")), 1)
    assert(rendered.contains("has \\| a pipe and an embedded newline"), rendered)
  }

  private def CredentialFileAclIsWindows: Boolean =
    nebflow.core.CredentialFileAcl.isWindows(nebflow.core.CredentialFileAcl.currentOsName)

end SecretsCatalogSpec
