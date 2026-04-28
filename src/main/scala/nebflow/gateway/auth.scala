package nebflow.gateway

import cats.effect.IO
import io.circe.syntax.*
import io.circe.parser

import java.security.SecureRandom
import java.util.Base64

object Auth:
  private val tokenPath = os.home / ".nebflow" / "auth.json"

  def generateToken: IO[String] = IO.delay {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  def loadOrCreateToken: IO[String] = IO.blocking {
    if os.exists(tokenPath) then
      parser.decode[String](os.read(tokenPath)) match
        case Right(t) => t
        case Left(_)  => createAndSave()
    else createAndSave()
  }

  private def createAndSave(): String =
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    val token = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    os.write.over(tokenPath, token.asJson.noSpaces, createFolders = true)
    token

  def validateToken(provided: String, expected: String): Boolean =
    java.security.MessageDigest.isEqual(
      provided.getBytes("UTF-8"),
      expected.getBytes("UTF-8")
    )
end Auth
