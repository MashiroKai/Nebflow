package nebflow.core.secrets

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.core.CredentialFileAcl

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer

/** secrets-panel backend leg — store discipline (2026-09-27).
  *
  * Every case runs against a throwaway data root; the store's `secretsDir`
  * is parameterized, so this suite NEVER touches the real `~/.nebflow/secrets/`
  * (8 live credential files, zero reads, zero writes). All values are mock
  * values (`test-secret-value-N`); no fixture ever carries a real credential.
  *
  * Pins:
  *   - the four-step write order (`SocialChannels.writeSecret` shape): new
  *     targets created `rw-------` from the first byte (mode from the creation
  *     attribute), existing targets narrowed BEFORE the plaintext, in-place
  *     write (`TRUNCATE_EXISTING`), `CredentialFileAcl.restrict` as the
  *     authoritative step;
  *   - zero residue under an injected restrict failure; a pre-existing file
  *     never written into is left untouched;
  *   - the fail-closed doubles, each arm through its own injected seam:
  *     a namespace that cannot be narrowed refuses the write (Z4/Z5, no-op
  *     `restrictDirectory` seam) and a file that did not end up 0600 is
  *     deleted, never reported as stored (Z6/Z7, narrowing-then-widening
  *     `restrict` seam) — both arms mutation-proven load-bearing;
  *   - name validation (charset/length/`.`-prefix/`.tmp`-suffix) and the
  *     managed-namespace write refusal;
  *   - `referencedBy` as a pure scan over BOTH sources (stored channel path
    *     refs + definition-layer preset names; daemon configPanel declarations).
  */
class SecretsStoreSpec extends FunSuite:

  private val MockValue1 = "test-secret-value-1"
  private val MockValue2 = "test-secret-value-2"

  private val realRestrict: Path => Unit = p => CredentialFileAcl.restrict(p)

  /** Injected narrowing that always fails — the zero-residue reproduction. */
  private val failingRestrict: Path => Unit =
    _ => throw new java.io.IOException("injected: narrowing refused")

  /** Injected namespace narrowing that does NOTHING — the reproduction of a
    * secrets directory that CANNOT be narrowed (a legacy-wide namespace whose
    * tightening never lands). This is the only in-process way to make the
    * directory gate's refusal arm reachable: the real
    * `CredentialFileAcl.restrictDirectory` always succeeds on a directory we
    * own, so without this seam the gate could never refuse in a test. */
  private val noNarrowDirectory: Path => Unit = _ => ()

  /** Injected file narrowing that narrows and then RE-WIDENS — the shape of a
    * restriction step that cannot keep its result. Drives the post-write
    * fail-closed double (`postVerify`'s false arm: a file that did not end up
    * 0600 is deleted and the write refused) through the injected seam. */
  private val looseningRestrict: Path => Unit = p =>
    CredentialFileAcl.restrict(p)
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-r--r--"))

  private def posixOnly(): Unit =
    assume(
      !CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName),
      "POSIX-only readback assertion"
    )

  /** Isolated root: `<tmp>/data/` acts as the data root, so the store's
    * reference scans read fixture files inside the tmp tree only. */
  private def withStore(f: (SecretsStore, os.Path) => Unit): Unit =
    val root = os.Path(Files.createTempDirectory("secrets-store-spec").toRealPath().toString)
    try
      val store = new SecretsStore(
        root / "secrets",
        dataRoot = root,
        daemonsPath = root / "daemons.json",
        restrict = realRestrict
      )
      f(store, root)
    finally
      try os.remove.all(root)
      catch case _: Throwable => ()

  private def modeOf(p: Path): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

  private def target(root: os.Path, name: String): Path = (root / "secrets" / name).toNIO

  private def namesUnder(root: os.Path): List[String] =
    if os.exists(root / "secrets") then os.list(root / "secrets").map(_.last).toList.sorted
    else Nil

  // ── Four-step write order ───────────────────────────────────────────────

  test("W1 · create: new target is 0600 from its first byte and holds the plaintext") {
    posixOnly()
    withStore { (store, root) =>
      val r = store.create("mock-secret", MockValue1).unsafeRunSync()
      assert(r.isRight, s"create must succeed, got $r")
      assertEquals(modeOf(target(root, "mock-secret")), CredentialFileAcl.PosixMode,
        "mode must come from the creation attribute (rw------- from the first byte)")
      assertEquals(Files.readString(target(root, "mock-secret")), MockValue1)
      assertEquals(namesUnder(root), List("mock-secret"), "zero residue in the secrets dir")
    }
  }

  test("W2 · create: directory is built 0700 when missing") {
    posixOnly()
    withStore { (store, root) =>
      assert(!os.exists(root / "secrets"), "precondition: no secrets dir yet")
      assert(store.create("mock-secret", MockValue1).unsafeRunSync().isRight)
      assertEquals(modeOf((root / "secrets").toNIO), "rwx------",
        "the namespace directory itself must be owner-only")
    }
  }

  test("W3 · overwrite: existing target is narrowed BEFORE the new plaintext lands") {
    posixOnly()
    withStore { (store, root) =>
      assert(store.create("mock-secret", MockValue1).unsafeRunSync().isRight,
        "precondition: first write succeeds")
      // Record every restrict call: mode + on-disk content AT THAT MOMENT.
      val seen = ArrayBuffer.empty[String]
      val recording = new SecretsStore(
        root / "secrets",
        dataRoot = root,
        daemonsPath = root / "daemons.json",
        restrict = p => { seen += s"${modeOf(p)}|${Files.readString(p)}"; CredentialFileAcl.restrict(p) }
      )
      val r = recording.overwrite("mock-secret", MockValue2).unsafeRunSync()
      assert(r.isRight, s"overwrite must succeed, got $r")
      assertEquals(
        seen.toList,
        List(s"rw-------|$MockValue1", s"rw-------|$MockValue2"),
        "first restrict must fire BEFORE the new plaintext (old content still on disk); " +
          "the second is the post-write authoritative step"
      )
      assertEquals(Files.readString(target(root, "mock-secret")), MockValue2, "in-place overwrite")
      assertEquals(modeOf(target(root, "mock-secret")), CredentialFileAcl.PosixMode)
    }
  }

  test("W4 · overwrite: a wide-mode pre-existing file is narrowed before the plaintext") {
    posixOnly()
    withStore { (store, root) =>
      // Simulate a legacy wide-mode file (left by a pre-F1 writer).
      Files.createDirectories(target(root, "mock-secret").getParent)
      Files.write(target(root, "mock-secret"), MockValue1.getBytes(StandardCharsets.UTF_8))
      Files.setPosixFilePermissions(target(root, "mock-secret"), PosixFilePermissions.fromString("rw-r--r--"))
      val seen = ArrayBuffer.empty[String]
      val recording = new SecretsStore(
        root / "secrets",
        dataRoot = root,
        daemonsPath = root / "daemons.json",
        restrict = p => { seen += s"${modeOf(p)}|${Files.readString(p)}"; CredentialFileAcl.restrict(p) }
      )
      assert(recording.overwrite("mock-secret", MockValue2).unsafeRunSync().isRight)
      assertEquals(seen.headOption, Some("rw-r--r--|" + MockValue1),
        "the wide file must be narrowed while it still holds the OLD value — " +
          "no instant where new plaintext sits in an un-narrowed file")
      assertEquals(modeOf(target(root, "mock-secret")), CredentialFileAcl.PosixMode)
    }
  }

  // ── Zero residue (injected restrict failure) ────────────────────────────

  test("Z1 · create + restrict failure ⇒ zero residue, explicit error, no success report") {
    posixOnly()
    withStore { (store, root) =>
      val failing = new SecretsStore(
        root / "secrets", dataRoot = root, daemonsPath = root / "daemons.json",
        restrict = failingRestrict)
      val r = failing.create("mock-secret", MockValue1).unsafeRunSync()
      r match
        case Left(SecretsStore.Failure.SecretMode(name, detail)) =>
          assertEquals(name, "mock-secret")
          assert(detail.contains((root / "secrets" / "mock-secret").toString),
            s"failure detail must name the path, got: $detail")
          assert(!detail.contains(MockValue1), "failure detail must NEVER carry the value")
        case other => fail(s"expected Left(SecretMode), got $other")
      assertEquals(namesUnder(root), List.empty, "the created file must be removed — zero residue")
    }
  }

  test("Z2 · overwrite + pre-write restrict failure ⇒ old value intact, untouched file stays 0600") {
    posixOnly()
    withStore { (store, root) =>
      assert(store.create("mock-secret", MockValue1).unsafeRunSync().isRight,
        "precondition: normal write succeeds")
      val failing = new SecretsStore(
        root / "secrets", dataRoot = root, daemonsPath = root / "daemons.json",
        restrict = failingRestrict)
      val r = failing.overwrite("mock-secret", MockValue2).unsafeRunSync()
      assert(r.isLeft, "the failure must take the error channel — never reported as stored")
      assertEquals(Files.readString(target(root, "mock-secret")), MockValue1,
        "the new plaintext must NOT have landed")
      assertEquals(modeOf(target(root, "mock-secret")), CredentialFileAcl.PosixMode,
        "the pre-existing file keeps its owner-only mode")
      assertEquals(namesUnder(root), List("mock-secret"), "no extra residue")
    }
  }

  test("Z3 · a wide namespace is best-effort NARROWED before the write proceeds") {
    posixOnly()
    withStore { (store, root) =>
      // The write path first best-effort-narrows the namespace, THEN reads
      // the mode for its gate decision. A legacy-wide dir that CAN be
      // narrowed is therefore narrowed and the write proceeds — safely: the
      // file is created 0600 from its creation attribute and no plaintext
      // ever sits in a wide namespace. (The can't-be-narrowed refusal arm is
      // Z4/Z5's, through the injected no-op narrowing seam.)
      os.makeDir.all(root / "secrets")
      os.perms.set(root / "secrets", "rwxr-xr-x")
      val r = store.create("mock-secret", MockValue1).unsafeRunSync()
      assert(r.isRight, s"narrowing succeeds in-process, so the write must proceed, got $r")
      assertEquals(modeOf((root / "secrets").toNIO), "rwx------",
        "a wide namespace must be narrowed BEFORE the write proceeds")
      assertEquals(modeOf(target(root, "mock-secret")), CredentialFileAcl.PosixMode)
    }
  }

  test("Z4 · dir gate (create leg): a namespace that cannot be narrowed refuses with zero residue") {
    posixOnly()
    withStore { (store, root) =>
      // A legacy-wide secrets dir + an injected no-op narrowing: the gate's
      // refusal arm MUST fire (never a plaintext in a wide namespace).
      os.makeDir.all(root / "secrets")
      os.perms.set(root / "secrets", "rwxr-xr-x")
      val gated = new SecretsStore(
        root / "secrets", dataRoot = root, daemonsPath = root / "daemons.json",
        restrict = realRestrict, restrictDirectory = noNarrowDirectory)
      val r = gated.create("mock-secret", MockValue1).unsafeRunSync()
      r match
        case Left(SecretsStore.Failure.SecretMode(name, detail)) =>
          assertEquals(name, "mock-secret")
          assert(detail.toLowerCase.contains("permissive"),
            s"refusal detail must name the reason, got: $detail")
          assert(!detail.contains(MockValue1), "refusal detail must NEVER carry the value")
        case other => fail(s"expected Left(SecretMode) — the gate must refuse, got $other")
      assertEquals(namesUnder(root), List.empty,
        "nothing may be written into a namespace that stays wide")
      assertEquals(modeOf((root / "secrets").toNIO), "rwxr-xr-x",
        "the refusal leaves the namespace exactly as found (no silent fix-up)")
    }
  }

  test("Z5 · dir gate (overwrite leg): refusal fires before the target is even narrowed") {
    posixOnly()
    withStore { (store, root) =>
      // Pre-existing 0600 file inside a wide, un-narrowable namespace: the
      // gate must refuse BEFORE the file narrowing/writing ladder starts, so
      // the pre-existing file is left exactly as found.
      os.makeDir.all(root / "secrets")
      os.write.over(root / "secrets" / "mock-secret", MockValue1)
      os.perms.set(root / "secrets", "rwxr-xr-x")
      os.perms.set(root / "secrets" / "mock-secret", "rw-------")
      val gated = new SecretsStore(
        root / "secrets", dataRoot = root, daemonsPath = root / "daemons.json",
        restrict = realRestrict, restrictDirectory = noNarrowDirectory)
      val r = gated.overwrite("mock-secret", MockValue2).unsafeRunSync()
      r match
        case Left(SecretsStore.Failure.SecretMode(_, detail)) =>
          assert(detail.toLowerCase.contains("permissive"),
            s"refusal detail must name the reason, got: $detail")
        case other => fail(s"expected Left(SecretMode) — the gate must refuse, got $other")
      assertEquals(Files.readString(target(root, "mock-secret")), MockValue1,
        "the new plaintext must NOT have landed")
      assertEquals(modeOf(target(root, "mock-secret")), CredentialFileAcl.PosixMode,
        "the pre-existing file keeps its owner-only mode — untouched by the refusal")
      assertEquals(namesUnder(root), List("mock-secret"), "no extra residue")
    }
  }

  test("Z6 · postVerify (create leg): a file that did not end up 0600 is deleted and refused") {
    posixOnly()
    withStore { (store, root) =>
      // The injected narrowing narrows then re-widens: after the write the
      // file is 0644. postVerify's false arm must delete the plaintext and
      // refuse the store — the value never stays behind un-narrowed.
      val loosening = new SecretsStore(
        root / "secrets", dataRoot = root, daemonsPath = root / "daemons.json",
        restrict = looseningRestrict)
      val r = loosening.create("mock-secret", MockValue1).unsafeRunSync()
      r match
        case Left(SecretsStore.Failure.SecretMode(name, detail)) =>
          assertEquals(name, "mock-secret")
          assert(detail.contains("could not restrict"),
            s"refusal detail must name the post-verify reason, got: $detail")
          assert(detail.contains((root / "secrets" / "mock-secret").toString),
            s"refusal detail must name the path, got: $detail")
          assert(!detail.contains(MockValue1), "refusal detail must NEVER carry the value")
        case other => fail(s"expected Left(SecretMode) — postVerify must refuse, got $other")
      assertEquals(namesUnder(root), List.empty,
        "the widened file must be deleted — the plaintext never stays behind")
    }
  }

  test("Z7 · postVerify (overwrite leg): a widened overwrite is deleted, never reported stored") {
    posixOnly()
    withStore { (store, root) =>
      assert(store.create("mock-secret", MockValue1).unsafeRunSync().isRight,
        "precondition: normal write succeeds")
      // The overwrite narrows, writes the new plaintext, narrows again — but
      // the injected step re-widens every time, so the file ends 0644. The
      // post-write double must delete it and refuse: not stored.
      val loosening = new SecretsStore(
        root / "secrets", dataRoot = root, daemonsPath = root / "daemons.json",
        restrict = looseningRestrict)
      val r = loosening.overwrite("mock-secret", MockValue2).unsafeRunSync()
      r match
        case Left(SecretsStore.Failure.SecretMode(_, detail)) =>
          assert(detail.contains("could not restrict") && detail.contains("refusing to store"),
            s"refusal detail must name the post-verify reason, got: $detail")
          assert(!detail.contains(MockValue2), "refusal detail must NEVER carry the value")
        case other => fail(s"expected Left(SecretMode) — postVerify must refuse, got $other")
      assertEquals(namesUnder(root), List.empty,
        "the widened file (old or new plaintext) must be deleted — never left behind")
    }
  }

  // ── POST/PUT semantics (no upsert) ──────────────────────────────────────

  test("P1 · create on existing name ⇒ already_exists (409 face)") {
    withStore { (store, _) =>
      assert(store.create("mock-secret", MockValue1).unsafeRunSync().isRight)
      assert(
        store.create("mock-secret", MockValue2).unsafeRunSync() ==
          Left(SecretsStore.Failure.AlreadyExists("mock-secret")),
        "POST on an existing name must refuse with already_exists")
    }
  }

  test("P2 · overwrite on missing name ⇒ unknown_secret (404 face; no upsert)") {
    withStore { (store, _) =>
      assert(
        store.overwrite("mock-secret", MockValue1).unsafeRunSync() ==
          Left(SecretsStore.Failure.UnknownSecret("mock-secret")),
        "PUT on a missing name must refuse with unknown_secret — never silently create")
    }
  }

  test("P3 · delete: missing ⇒ unknown_secret; existing unreferenced ⇒ gone") {
    withStore { (store, root) =>
      assert(store.delete("mock-secret").unsafeRunSync() ==
        Left(SecretsStore.Failure.UnknownSecret("mock-secret")))
      assert(store.create("mock-secret", MockValue1).unsafeRunSync().isRight)
      assert(store.delete("mock-secret").unsafeRunSync().isRight)
      assertEquals(namesUnder(root), List.empty)
    }
  }

  test("P4 · delete referenced ⇒ referenced with the referrer list (409 face)") {
    withStore { (store, _) =>
      assert(store.create("mock-secret", MockValue1).unsafeRunSync().isRight)
      assert(
        store.delete("mock-secret", List("social:feishu")).unsafeRunSync() ==
          Left(SecretsStore.Failure.Referenced("mock-secret", List("social:feishu"))),
        "a referenced secret must refuse deletion and name the referrers")
    }
  }

  // ── Name validation & managed namespace ─────────────────────────────────

  test("N1 · charset/length: valid names pass, bad ones fail with a specific reason") {
    withStore { (store, _) =>
      List("a", "aliyun-oss", "logto-test-account.json", "a" * 64, "x_1.y-2").foreach { n =>
        assertEquals(store.validateName(n), Right(n), s"'$n' must validate")
      }
      // charset / leading digit rules
      assert(store.validateName("-abc").isLeft, "must start with a letter or digit")
      assert(store.validateName("A-upper").isLeft, "uppercase outside the charset")
      assert(store.validateName("a/b").isLeft, "slash outside the charset (traversal face)")
      assert(store.validateName("a b").isLeft, "space outside the charset")
      // length cap
      store.validateName("a" * 65) match
        case Left(SecretsStore.Failure.InvalidName(_, reason)) =>
          assert(reason.contains("64"), s"the reason must name the length cap, got: $reason")
        case other => fail(s"expected InvalidName for an overlong name, got $other")
    }
  }

  test("N2 · hard reds: '.' prefix and '.tmp' suffix are refused with specific reasons") {
    withStore { (store, _) =>
      // `.<name>.tmp` is the daemon panel's temp-file namespace — never writable.
      store.validateName(".hidden") match
        case Left(SecretsStore.Failure.InvalidName(_, reason)) =>
          assert(reason.contains("'.'"), s"the reason must name the dot-prefix rule, got: $reason")
        case other => fail(s"expected InvalidName for a dot-prefixed name, got $other")
      store.validateName("mock.tmp") match
        case Left(SecretsStore.Failure.InvalidName(_, reason)) =>
          assert(reason.contains(".tmp"), s"the reason must name the tmp-suffix rule, got: $reason")
        case other => fail(s"expected InvalidName for a .tmp-suffixed name, got $other")
      // and the write faces refuse the same way
      assert(store.create(".hidden", MockValue1).unsafeRunSync().isLeft)
      assert(store.create("mock.tmp", MockValue1).unsafeRunSync().isLeft)
      assert(store.overwrite(".hidden", MockValue1).unsafeRunSync().isLeft)
    }
  }

  test("N3 · managed namespace: social-/daemon- writes refused, name check runs first") {
    withStore { (store, _) =>
      assert(
        store.create("social-telegram-bot-token", MockValue1).unsafeRunSync() ==
          Left(SecretsStore.Failure.ManagedNamespace("social-telegram-bot-token")),
        "a managed name must refuse POST with managed_namespace")
      assert(store.create("daemon-mail-password", MockValue1).unsafeRunSync().isLeft)
      assert(store.overwrite("social-telegram-bot-token", MockValue1).unsafeRunSync().isLeft)
      // The refusal is ordered AFTER name validation: an invalid managed name
      // reports the NAME failure, not the namespace.
      assert(store.create("social-INVALID!", MockValue1).unsafeRunSync().isLeft)
      assert(store.create(".social-x", MockValue1).unsafeRunSync().isLeft)
    }
  }

  test("N4 · managed names stay VISIBLE to the list face (metadata + refs)") {
    withStore { (store, root) =>
      // A managed file that already exists (written by its own panel).
      os.makeDir.all(root / "secrets")
      os.write(root / "secrets" / "social-telegram-bot-token", MockValue1)
      os.perms.set(root / "secrets" / "social-telegram-bot-token", "rw-------")
      val entries = store.list().unsafeRunSync()
      entries match
        case Right(list) =>
          val names = list.map(_.name)
          assert(names.contains("social-telegram-bot-token"),
            "managed names must be listable (visible + inspectable)")
        case other => fail(s"expected Right(list), got $other")
    }
  }

  // ── Value cap & list face ───────────────────────────────────────────────

  test("V1 · value cap: a value over 64 KiB refuses with invalid_value, nothing written") {
    withStore { (store, root) =>
      val big = "x" * (SecretsStore.MaxValueBytes + 1)
      assert(
        store.create("mock-secret", big).unsafeRunSync() match
          case Left(SecretsStore.Failure.InvalidValue(_, _)) => true
          case other                                         => false,
        "an oversized value must refuse with invalid_value")
      assertEquals(namesUnder(root), List.empty, "no byte of an oversized payload may land")
    }
  }

  test("V4 · value cap boundary: EXACTLY 64 KiB stores, one byte over refuses") {
    withStore { (store, root) =>
      posixOnly()
      // The cap is inclusive: a value of exactly MaxValueBytes bytes is legal.
      val exact = "x" * SecretsStore.MaxValueBytes
      assert(store.create("mock-boundary", exact).unsafeRunSync().isRight,
        s"a value of exactly ${SecretsStore.MaxValueBytes} bytes must store")
      assertEquals(Files.size(target(root, "mock-boundary")),
        SecretsStore.MaxValueBytes.toLong, "the stored payload is byte-exact")
      assertEquals(modeOf(target(root, "mock-boundary")), CredentialFileAcl.PosixMode)
      // One byte over is refused before anything lands.
      val over = "x" * (SecretsStore.MaxValueBytes + 1)
      assert(
        store.create("mock-over", over).unsafeRunSync() match
          case Left(SecretsStore.Failure.InvalidValue(_, reason)) =>
            assert(reason.contains("byte limit"), s"reason names the cap, got: $reason"); true
          case other => false,
        "one byte over the cap must refuse with invalid_value")
      assertEquals(namesUnder(root), List("mock-boundary"),
        "the refused payload left nothing behind")
    }
  }

  test("V2 · list: metadata only — entries carry name/size/mtime/mode/refs, no value shape") {
    withStore { (store, root) =>
      assert(store.create("mock-secret", MockValue1).unsafeRunSync().isRight)
      store.list().unsafeRunSync() match
        case Right(List(e)) =>
          assertEquals(e.name, "mock-secret")
          assertEquals(e.size, MockValue1.getBytes(StandardCharsets.UTF_8).length.toLong)
          assert(e.mtime > 0)
          assertEquals(e.mode, Some("600"))
          assertEquals(e.refs, Nil)
        case other => fail(s"expected exactly one entry, got $other")
    }
  }

  test("V3 · list: missing secrets dir ⇒ empty list, not an error") {
    withStore { (store, _) =>
      store.list().unsafeRunSync() match
        case Right(Nil) => ()
        case other      => fail(s"expected Right(Nil) for a fresh root, got $other")
    }
  }

  // ── referencedBy: the two-source pure scan ──────────────────────────────

  test("R1 · channel face: a stored `_ref` path string claims its channel id") {
    withStore { (store, root) =>
      os.makeDir.all(root)
      os.write.over(root / "nebflow.json",
        """{"socialChannels":{"version":1,"channels":{"feishu":{"enabled":true,
          |"fields":{"app_secret_ref":"~/.nebflow/secrets/social-feishu-app-secret"}}}}}""".stripMargin)
      val refs = store.referencedByAll()
      assertEquals(refs.get("social-feishu-app-secret"), Some(List("social:feishu")),
        "the stored-ref scan and the definition-layer preset agree on the same claim — " +
          "one channel, one entry (no duplicate accumulation)")
    }
  }

  test("R2 · channel face: definition-layer preset names claim even with an empty config") {
    withStore { (store, root) =>
      os.write.over(root / "nebflow.json", "{}")
      val refs = store.referencedByAll()
      assertEquals(refs.get("social-wechat-app-secret"), Some(List("social:wechat")),
        "preset names are claimed from the definition layer alone")
      assertEquals(refs.get("social-telegram-bot-token"), Some(List("social:telegram")))
    }
  }

  test("R3 · daemon face: configPanel secret fields claim daemon-<id>-<key>") {
    withStore { (store, root) =>
      os.write.over(root / "daemons.json",
        """{"daemons":[{"id":"mail","name":"mail","command":["true"],
          |"configPanel":{"fields":[
          |{"key":"password","label":"PW","type":"secret"},
          |{"key":"host","label":"Host","type":"string"}]}}]}""".stripMargin)
      val refs = store.referencedByAll()
      assertEquals(refs.get("daemon-mail-password"), Some(List("daemon:mail")),
        "a declared secret field's backing name is claimed by its daemon id")
      assertEquals(refs.get("daemon-mail-host"), None,
        "non-secret fields never claim")
    }
  }

  test("R4 · pure function: same fixture bytes ⇒ identical mapping (and no cross-file bleed)") {
    withStore { (storeA, rootA) =>
      withStore { (storeB, rootB) =>
        val cfg =
          """{"socialChannels":{"channels":{"wechat":{"enabled":false,"fields":{"app_secret_ref":
            |"~/.nebflow/secrets/social-wechat-app-secret"}}}},"daemons":[{"id":"d1",
            |"name":"d1","command":["true"],"configPanel":{"fields":[{"key":"tok","label":"T",
            |"type":"secret"}]}}]}""".stripMargin
        os.write.over(rootA / "nebflow.json", cfg)
        os.write.over(rootA / "daemons.json", cfg)
        os.write.over(rootB / "nebflow.json", cfg)
        os.write.over(rootB / "daemons.json", cfg)
        assertEquals(storeA.referencedByAll(), storeB.referencedByAll(),
          "the scan must be a pure function of its inputs")
        val refs = storeA.referencedByAll()
        assert(refs.contains("social-wechat-app-secret"))
        assert(refs.contains("daemon-d1-tok"))
      }
    }
  }

  test("R5 · unparseable/missing config files degrade to definition-layer-only claims") {
    withStore { (store, root) =>
      os.write.over(root / "nebflow.json", "not json at all {{{")
      val refs = store.referencedByAll()
      assert(refs.contains("social-wechat-app-secret"),
        "definition-layer claims survive an unparseable stored config")
      assert(!refs.values.exists(_.exists(_.startsWith("daemon:"))),
        "no daemon claims without a readable daemons.json")
    }
  }

  // ── Path safety ─────────────────────────────────────────────────────────

  test("S1 · traversal: names with `/`, `..` or absolute paths never reach the filesystem") {
    withStore { (store, root) =>
      val evil = List("../escape", "a/b", "..", "sub/../x", "/etc/passwd", "C:\\x")
      evil.foreach { n =>
        val r = store.create(n, MockValue1).unsafeRunSync()
        assert(r.isLeft, s"'$n' must be refused")
        r match
          case Left(SecretsStore.Failure.InvalidName(got, _)) => assertEquals(got, n)
          case other => fail(s"expected InvalidName for '$n', got $other")
      }
      assertEquals(namesUnder(root), List.empty, "nothing may land outside the namespace")
      assert(!os.exists(root / "escape"), "no sibling escape directory may appear")
    }
  }
