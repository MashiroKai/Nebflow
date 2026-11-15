package nebflow.gateway

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** A recovery principal is derived only from an authenticated credential. */
private[gateway] object RecoveryPrincipal:
  def fromCredential(credential: Credential): Option[String] = credential match
    case Credential.ViaBearer(true) => Some("local-file-token")
    case Credential.ViaBearer(false) => None
    case Credential.ViaSession(record) =>
      val fields = List(record.accountSub, record.origin, record.boundNetworkId)
      if fields.exists(_.isEmpty) then None
      else
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("stand-recovery-principal-v1".getBytes(UTF_8))
        fields.foreach { field =>
          val bytes = field.getBytes(UTF_8)
          digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array())
          digest.update(bytes)
        }
        Some("session-" + digest.digest().map(b => f"${b & 0xff}%02x").mkString)
