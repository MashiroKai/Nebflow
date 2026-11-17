package nebflow.core.secrets

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

import nebflow.core.CredentialFileAcl

/** Draft primitive for config credentials. Callers must not log values or exception messages. */
object ConfigCredentialStore:
  private val Prefix = "secret:config-"
  private val Name = "config-[a-f0-9]{32}".r

  /** Mint a new immutable reference; publication happens only after a complete atomic write. */
  def mint(root: os.Path, value: String): String =
    val dir = (root / "secrets").toNIO
    Files.createDirectories(dir)
    CredentialFileAcl.restrictDirectory(dir)
    val name = "config-" + UUID.randomUUID().toString.replace("-", "")
    val tmp = dir.resolve("." + name + ".tmp")
    val target = dir.resolve(name)
    try
      Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      Files.write(tmp, value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE)
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
      "secret:" + name
    finally Files.deleteIfExists(tmp)

  def persist(root: os.Path, value: String): String =
    if value.isEmpty || value.startsWith("secret:") then value else mint(root, value)

  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.config.credentials")

  def resolveManaged(root: os.Path, value: String, reader: String): String =
    resolve(root, value, reader, (who, ref) => logger.infoSync(s"credential read: reader=$who reference=$ref"))

  /** Managed reads are audited; arbitrary reads by a same-identity shell are not interceptable here. */
  def resolve(root: os.Path, input: String, reader: String, audit: (String, String) => Unit): String =
    if !input.startsWith(Prefix) then input
    else
      val name = input.stripPrefix("secret:")
      if !Name.matches(name) then throw new IllegalArgumentException("Invalid credential reference")
      audit(reader, input)
      new String(Files.readAllBytes((root / "secrets" / name).toNIO), StandardCharsets.UTF_8)
