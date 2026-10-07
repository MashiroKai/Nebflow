package nebflow.social

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import nebflow.bridge.BridgeContext
import nebflow.core.CredentialFileAcl
import nebflow.shared.SessionMeta

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Offline spec for the weixin-ilink ingress token (weixin-inbound batch,
 * 2026-10-07) — the credential plane of the ingest route's track-2. Design =
 * batch report §1 (written before the code); these tests pin its mechanics:
 * mint shape (43 base64url chars), owner-only from creation, idempotence,
 * corrupt-residue self-heal, the total `false` of every verification failure
 * mode, rotation pickup, and the seam-up mint wiring.
 *
 * No network, no shim process, no real credentials — `root` is a temp dir.
 */
class WeixinIngressTokenSpec extends munit.FunSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-weixin-ingress-token-")

  private def tokenFile(root: os.Path) = WeixinIngressToken.path(root).toNIO

  private def read(p: java.nio.file.Path) = new String(Files.readAllBytes(p), "UTF-8").trim

  private val WellFormed = "^[A-Za-z0-9_-]{43}$"

  // ───────────────────────────── mint ─────────────────────────────

  test("WIT-1 ensure mints a well-formed owner-only token at <root>/secrets/") {
    val root = tmpRoot()
    WeixinIngressToken.ensure(root).unsafeRunSync()
    val p = tokenFile(root)
    assert(Files.exists(p), "the token file must exist after seam-up mint")
    val v = read(p)
    assert(v.matches(WellFormed), s"the token must be 43 base64url chars, got ${v.length} chars")
    assume(!CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName), "POSIX mode assertion")
    assertEquals(
      PosixFilePermissions.toString(Files.getPosixFilePermissions(p)),
      CredentialFileAcl.PosixMode,
      "the token must be owner-only (rw-------)"
    )
  }

  test("WIT-2 ensure is idempotent: a second seam-up keeps the minted value") {
    val root = tmpRoot()
    WeixinIngressToken.ensure(root).unsafeRunSync()
    val first = read(tokenFile(root))
    WeixinIngressToken.ensure(root).unsafeRunSync()
    assertEquals(read(tokenFile(root)), first, "a well-formed existing token is never rewritten")
  }

  test("WIT-3 a corrupt/empty residue is regenerated in place on the next ensure") {
    val root = tmpRoot()
    Files.createDirectories(WeixinIngressToken.path(root).toNIO.getParent)
    Files.write(tokenFile(root), Array.emptyByteArray) // empty residue (crash window)
    WeixinIngressToken.ensure(root).unsafeRunSync()
    val healed = read(tokenFile(root))
    assert(healed.matches(WellFormed), "the residue must be replaced by a well-formed token")
  }

  // ───────────────────────────── verification ─────────────────────────────

  test("WIT-4 accepts: matching ⇒ true; every other failure mode ⇒ the same false") {
    val root = tmpRoot()
    WeixinIngressToken.ensure(root).unsafeRunSync()
    val good = read(tokenFile(root))
    // matching
    assert(WeixinIngressToken.accepts(root, Some(good)).unsafeRunSync(), "the minted token must verify")
    // wrong value
    assert(!WeixinIngressToken.accepts(root, Some(good + "x")).unsafeRunSync(), "a wrong token must fail")
    // no header at all
    assert(!WeixinIngressToken.accepts(root, None).unsafeRunSync(), "an absent bearer must fail")
    // empty-string bearer
    assert(!WeixinIngressToken.accepts(root, Some("")).unsafeRunSync(), "an empty bearer must fail")
    // file revoked (deleted)
    Files.delete(tokenFile(root))
    assert(!WeixinIngressToken.accepts(root, Some(good)).unsafeRunSync(), "a deleted token file must close track 2")
    // file corrupt (well-formedness gate, also defeats the compare against junk)
    Files.write(tokenFile(root), "garbage".getBytes("UTF-8"))
    assert(!WeixinIngressToken.accepts(root, Some("garbage")).unsafeRunSync(), "a corrupt residue accepts nothing")
  }

  test("WIT-5 rotation: an in-place rewrite is picked up at once (old dies, new lives)") {
    val root = tmpRoot()
    WeixinIngressToken.ensure(root).unsafeRunSync()
    val old = read(tokenFile(root))
    val fresh = "5urq7JpR8wN2cVhK0mXzQ4bTdY6gAeL3iO1fW9nHuSk" // 43 base64url chars
    Files.write(tokenFile(root), fresh.getBytes("UTF-8"))
    assert(WeixinIngressToken.accepts(root, Some(fresh)).unsafeRunSync(), "the new value must verify immediately")
    assert(!WeixinIngressToken.accepts(root, Some(old)).unsafeRunSync(), "the old value must be dead at once")
  }

  // ───────────────────────────── seam-up wiring ─────────────────────────────

  /** Minimal recording context — the token mint must not need anything from it. */
  private final class Ctx extends BridgeContext:
    def injectMessage(sessionId: String, content: String, senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(Nil)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[io.circe.Json]): IO[Unit] = IO.unit

  test("WIT-6 bridge start mints the token (seam-up ⇒ credential plane in place)") {
    val root = tmpRoot()
    val plugin = new WeixinIlinkBridgePlugin(root, pinnedAllowedIlinkUserIds = Some(Nil))
    plugin.start(new Ctx).unsafeRunSync()
    assert(Files.exists(tokenFile(root)), "seam-up must leave the ingress token on disk")
    assert(read(tokenFile(root)).matches(WellFormed))
    // and the wiring is non-fatal for the seam: the seam is up regardless
    assert(plugin.connected, "a mint issue must never take the seam down (logged, not raised)")
  }

end WeixinIngressTokenSpec
