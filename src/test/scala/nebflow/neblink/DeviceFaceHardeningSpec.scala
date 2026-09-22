package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.{CredentialFileAcl, PathUtil}

import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Device-face hardening batch (chain-devsec-bleed), neblink-side legs:
 *
 *  - **A1 second half** — the presence WS dial no longer puts our `deviceId` in
 *    the URL; it rides the handshake header (`Protocol.DeviceHeader`), the same
 *    channel the REST peer criterion already reads.
 *  - **A6** — the owner-only ACL is bound to the credential WRITE path
 *    (`DeviceIdentity.save` / `NeblinkConfig.save`), not applied out of band,
 *    plus the directory fallback.
 *
 * WHY the write path (A6): `AtomicJson` writes a temp file and `ATOMIC_MOVE`s it
 * over the target ⇒ every write is a NEW inode, so a chmod that is not part of
 * the write is discarded by the next write — which is exactly why the live root
 * `device.json` read back `0644` while its sibling (same writer shape PLUS a
 * `restrict` call, `DeviceCredentialStore.scala:379`) stayed `0600`. The rewrite
 * case below pins both halves: the inode DOES change AND the mode survives.
 *
 * Spec-FIRST note: these are the assertions that go RED on the pre-change tree
 * (see the batch report's 变异验红 readings — same tree with the write-path
 * binding and the URL-carried deviceId restored).
 */
class DeviceFaceHardeningSpec extends FunSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-devface-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    // Give the directory fallback something to do: the OS hands out 0700 temp
    // dirs, so widen it first — otherwise "narrowed" would be vacuous.
    Files.setPosixFilePermissions(tmpDir, PosixFilePermissions.fromString("rwxr-xr-x"))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    try Files.setPosixFilePermissions(tmpDir, PosixFilePermissions.fromString("rwx------"))
    catch case _: Exception => ()
    try os.remove.all(os.Path(tmpDir, os.pwd))
    catch case _: Exception => ()
    super.afterEach(context)

  // ── helpers ────────────────────────────────────────────────────────────────

  private def mode(p: Path): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

  /** True when ANY group/other bit is still set (what "owner-only" forbids).
    *
    * Matching on the enum CONSTANT rather than on a name string: a Java enum's
    * only name accessor is `Enum.name()`, and `OWNER_*` is the exact set the
    * product allows, so the complement is what must be empty. */
  private def widerThanOwner(p: Path): Boolean =
    Files
      .getPosixFilePermissions(p)
      .asScala
      .exists(perm =>
        perm != PosixFilePermission.OWNER_READ &&
          perm != PosixFilePermission.OWNER_WRITE &&
          perm != PosixFilePermission.OWNER_EXECUTE
      )

  private val id = DeviceIdentity(deviceId = "dev-abc", deviceName = "Testbox", platform = "macos")

  private def withPresence[A](use: NeblinkPresenceService => A): A =
    Dispatcher.parallel[IO]
      .use { dispatcher =>
        NeblinkService.createForTest(0, dispatcher, 400.millis).map { ms =>
          use(new NeblinkPresenceService(ms, 8099)(dispatcher))
        }
      }
      .unsafeRunSync()

  // ── A1 second half ─────────────────────────────────────────────────────────

  test("A1②: buildWsUri drops deviceId from the URL; the id rides the handshake header") {
    withPresence { ps =>
      val uri = ps.buildWsUri("10.0.0.5", 8099, id)
      val headers = ps.presenceHandshakeHeaders(id)
      println(s"[A1-R1] presence dial URI = $uri")
      println(s"[A1-R2] handshake headers = $headers")
      assert(uri.startsWith("ws://10.0.0.5:8099/api/neblink/presence?"), s"unexpected dial URI: $uri")
      assert(
        !uri.contains("deviceId"),
        s"deviceId must NOT travel in the upgrade URL (it is the id the peer criterion trusts): $uri"
      )
      assert(uri.contains("deviceName="), s"peer display metadata stays on the URL: $uri")
      assertEquals(headers, List(Protocol.DeviceHeader -> "dev-abc"))
      assertEquals(Protocol.DeviceHeader, "X-Neblink-Device")
    }
  }

  // ── deviceId-compat leg (2026-09-22, chain neblink-lifecycle-fix) ──────────

  import java.net.http.{HttpClient, HttpHeaders, HttpResponse, WebSocketHandshakeException}

  /** Minimal `HttpResponse` stub — same shape as `RelayTunnelDiagnosticsSpec`. */
  private def fakeResponse(code: Int, bodyText: String): HttpResponse[String] =
    new HttpResponse[String]:
      def statusCode(): Int = code
      def request(): java.net.http.HttpRequest = null
      def previousResponse(): java.util.Optional[HttpResponse[String]] = java.util.Optional.empty()
      def headers(): HttpHeaders = HttpHeaders.of(java.util.Map.of(), (_, _) => true)
      def body(): String = bodyText
      def sslSession(): java.util.Optional[javax.net.ssl.SSLSession] = java.util.Optional.empty()
      def uri(): java.net.URI = java.net.URI.create("ws://10.0.0.5:8099/api/neblink/presence")
      def version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

  private def handshake(code: Int, bodyText: String): WebSocketHandshakeException =
    new WebSocketHandshakeException(fakeResponse(code, bodyText))

  test("compat①: the compat URI carries deviceId AND every A1 param; header contract unchanged") {
    withPresence { ps =>
      val compat = ps.buildCompatWsUri("10.0.0.5", 8099, id)
      println(s"[C1-R1] compat dial URI = $compat")
      assert(compat.startsWith("ws://10.0.0.5:8099/api/neblink/presence?"), s"unexpected compat URI: $compat")
      assert(
        compat.contains("deviceId=dev-abc"),
        s"compat URI must carry deviceId in the query (legacy listeners read the query ONLY): $compat"
      )
      assert(compat.contains("deviceName="), s"peer display metadata stays on the compat URL: $compat")
      // header contract is byte-identical on the compat path (header semantics unchanged)
      assertEquals(ps.presenceHandshakeHeaders(id), List(Protocol.DeviceHeader -> "dev-abc"))
      // and the PRIMARY URI form stays clean (A1② above re-asserts it too)
      assert(!ps.buildWsUri("10.0.0.5", 8099, id).contains("deviceId"), "primary URI must stay free of deviceId")
    }
  }

  test("compat②: the retry trigger fires ONLY on an HTTP-400 upgrade rejection") {
    withPresence { ps =>
      // wrapped shapes, exactly as .get() surfaces them
      val wrapped400 = new java.util.concurrent.ExecutionException(handshake(400, """{"error":"Missing deviceId"}"""))
      val wrapped403 = new java.util.concurrent.ExecutionException(handshake(403, """{"error":"Not a trusted peer"}"""))
      val wrapped404 = new java.util.concurrent.ExecutionException(handshake(404, "not found"))
      val wrapped500 = new java.util.concurrent.ExecutionException(handshake(500, "boom"))
      println(
        s"[C2-R1] 400 wrapped=${ps.isLegacyMissingDeviceIdRejection(wrapped400)} " +
          s"403=${ps.isLegacyMissingDeviceIdRejection(wrapped403)} " +
          s"404=${ps.isLegacyMissingDeviceIdRejection(wrapped404)} " +
          s"500=${ps.isLegacyMissingDeviceIdRejection(wrapped500)}"
      )
      assert(ps.isLegacyMissingDeviceIdRejection(wrapped400), "400 on the upgrade = legacy missing-deviceId rejection")
      assert(!ps.isLegacyMissingDeviceIdRejection(wrapped403), "403 = trust gate — must NOT trigger the compat retry")
      assert(!ps.isLegacyMissingDeviceIdRejection(wrapped404), "404 = wrong path — must NOT trigger the compat retry")
      assert(!ps.isLegacyMissingDeviceIdRejection(wrapped500), "5xx = server-side failure — must NOT trigger the retry")
      // non-handshake failures keep their own classification
      assert(!ps.isLegacyMissingDeviceIdRejection(new java.net.ConnectException("refused")), "refusal is not a compat case")
      assert(
        !ps.isLegacyMissingDeviceIdRejection(new java.util.concurrent.TimeoutException("budget")),
        "budget timeout is not a compat case"
      )
      assert(!ps.isLegacyMissingDeviceIdRejection(null), "null-safe: no crash, no trigger")
    }
  }

  // ── A6 ─────────────────────────────────────────────────────────────────────

  test("A6: DeviceIdentity.save writes 0600 AND narrows the data root") {
    DeviceIdentity.save(id).unsafeRunSync()
    val file = PathUtil.dataRoot / "device.json"
    println(s"[A6-R1] device.json mode = ${mode(file.toNIO)}   (pre-change tree: rw-r--r--)")
    println(s"[A6-R2] data root mode  = ${mode(tmpDir)}   (pre-change tree: rwxr-xr-x)")
    assert(os.exists(file), "device.json written")
    assert(!widerThanOwner(file.toNIO), s"device.json must be owner-only, got ${mode(file.toNIO)}")
    assert(!widerThanOwner(tmpDir), s"data root must be narrowed to owner-only, got ${mode(tmpDir)}")
  }

  test("A6: the mode survives the AtomicJson inode swap (repeat/overwrite write)") {
    val file = PathUtil.dataRoot / "device.json"
    DeviceIdentity.save(id).unsafeRunSync()
    val ino1 = Files.getAttribute(file.toNIO, "unix:ino")
    DeviceIdentity.save(id.copy(userDescription = "second write")).unsafeRunSync()
    val ino2 = Files.getAttribute(file.toNIO, "unix:ino")
    println(s"[A6-R3] inode before=$ino1 after=$ino2 (different ⇒ the swap a one-off chmod cannot survive)")
    println(s"[A6-R4] mode after the second write = ${mode(file.toNIO)}")
    assert(ino1 != ino2, "expected AtomicJson's tmp+ATOMIC_MOVE inode swap")
    assert(!widerThanOwner(file.toNIO), s"mode lost on rewrite: ${mode(file.toNIO)}")
    assert(!widerThanOwner(tmpDir), "directory narrowing must be idempotent, not one-shot")
  }

  test("A6: NeblinkConfig.save binds 0600 on neblink/config.json and narrows neblink/") {
    NeblinkConfig.save(NeblinkConfig()).unsafeRunSync()
    val dir = PathUtil.dataRoot / "neblink"
    val cfg = dir / "config.json"
    println(s"[A6-R5] config.json mode = ${mode(cfg.toNIO)} · neblink/ mode = ${mode(dir.toNIO)}")
    assert(os.exists(cfg), "config.json written")
    assert(!widerThanOwner(cfg.toNIO), s"config.json must be owner-only, got ${mode(cfg.toNIO)}")
    assert(!widerThanOwner(dir.toNIO), s"neblink/ must be owner-only, got ${mode(dir.toNIO)}")
  }

  test("A6: a failing ACL branch is non-fatal — the write survives, the failure is logged") {
    val file = PathUtil.dataRoot / "device.json"
    val failingPort = new CredentialFileAcl.Port:
      def ownerOnlyPosix(path: Path): Unit = throw new RuntimeException("no POSIX view on this FS")
      def ownerOnlyWindows(path: Path): Unit = throw new RuntimeException("no ACL view on this FS")
    DeviceIdentity.save(id).unsafeRunSync()
    val outcome = CredentialWriteAcl
      .bind(file, osName = "Mac OS X", port = failingPort)
      .attempt
      .unsafeRunSync()
    println(s"[A6-R6] bind with a failing port ⇒ $outcome")
    assert(outcome.isRight, "an ACL failure must never abort a credential write")
    assert(os.exists(file), "the credential is on disk either way")
    assert(os.read(file).contains("dev-abc"), "content intact")
  }
