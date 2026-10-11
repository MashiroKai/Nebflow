package nebflow.gateway

import cats.effect.{IO, Ref}
import io.circe.parser
import io.circe.syntax.*
import nebflow.shared.PathUtil

import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

object Auth:
  // Live read, NOT an object-level val: PathUtil.dataRoot is redirected by the
  // isolated-instance / spec faces, and a val would freeze whatever root was
  // current at first touch (same discipline as PortalBindingState.stateFile).
  // In production the root is fixed before boot touches Auth, so the value is
  // identical.
  private def tokenPath = PathUtil.dataRoot / "auth.json"

  def generateToken: IO[String] = IO.delay {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  /**
   * Rotate the gateway token (phase 3b, criterion 7): generate a fresh value,
   * atomically persist it to `auth.json`, and push it into `tokenRef` so the
   * LIVE gateway drops the old value immediately. Returns the new token.
   *
   * `current` must be a resolved live value (non-empty) — it is validated and
   * otherwise not reused: the new value is always freshly generated, and the
   * old one is never carried over.
   */
  def rotateToken(current: String, tokenRef: Ref[IO, String]): IO[String] =
    if current.isEmpty then IO.raiseError(new IllegalStateException("rotateToken: no live token to rotate"))
    else
      generateToken.flatMap { next =>
        writeTokenAtomic(next) *> tokenRef.set(next).as(next)
      }

  /**
   * Atomically persist `next` as the gateway token: write a `.tmp` sibling in
   * the SAME directory, set owner-only POSIX permissions BEFORE the rename
   * (no 0644 window), then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. On
   * any failure the original file is untouched (no half states) and the tmp
   * file is removed best-effort. Reused verbatim by the CLI's offline rotate
   * path (the running gateway is not involved there).
   */
  def writeTokenAtomic(next: String): IO[Unit] =
    IO.blocking {
      val target = java.nio.file.Paths.get(tokenPath.toString)
      val tmp = target.resolveSibling("auth.json.tmp")
      try
        os.write.over(os.Path(tmp, os.pwd), next.asJson.noSpaces, createFolders = true)
        // Restrict permissions BEFORE the rename (no 0644 window); best effort
        // on non-POSIX systems — same posture as createAndSave.
        try
          java.nio.file.Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        catch case _: Exception => ()
        java.nio.file.Files.move(
          tmp,
          target,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
      catch
        case e: Throwable =>
          try java.nio.file.Files.deleteIfExists(tmp)
          catch case _: Exception => ()
          throw e
    }

  def loadOrCreateToken: IO[String] = IO.blocking {
    if os.exists(tokenPath) then
      parser.decode[String](os.read(tokenPath)) match
        case Right(t) => t
        case Left(_) => createAndSave()
    else createAndSave()
  }

  private def createAndSave(): String =
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    val token = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    os.write.over(tokenPath, token.asJson.noSpaces, createFolders = true)
    // Restrict file permissions to owner-only (rw-------)
    try
      val perms = PosixFilePermissions.fromString("rw-------")
      java.nio.file.Files.setPosixFilePermissions(
        java.nio.file.Paths.get(tokenPath.toString),
        perms
      )
    catch case _: Exception => () // best effort on non-POSIX systems
    token

  def validateToken(provided: String, expected: String): Boolean =
    java.security.MessageDigest.isEqual(
      provided.getBytes("UTF-8"),
      expected.getBytes("UTF-8")
    )
end Auth
