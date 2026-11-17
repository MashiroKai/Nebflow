package nebflow.core.secrets

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*
import munit.FunSuite

class ConfigCredentialStoreSpec extends FunSuite:
  test("mint and managed resolution repeat twenty times without exposing the value in the reference") {
    val root = os.temp.dir(prefix = "config-credential-")
    try
      for i <- 1 to 20 do
        val value = s"fixture-value-$i"
        val ref = ConfigCredentialStore.mint(root, value)
        assert(ref.startsWith("secret:config-"))
        assert(!ref.contains(value))
        val file = root / "secrets" / ref.stripPrefix("secret:")
        assertEquals(Files.getPosixFilePermissions(file.toNIO).asScala.toSet,
          Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        var events = List.empty[(String, String)]
        val resolved = ConfigCredentialStore.resolve(root, ref, "test-reader", (who, key) => events = events :+ (who -> key))
        assertEquals(resolved, value)
        assertEquals(events, List("test-reader" -> ref))
        assertEquals(ConfigCredentialStore.resolve(root, value, "test-reader", (_, _) => fail("inline read audited")), value)
    finally os.remove.all(root)
  }

  test("invalid config references fail closed") {
    val root = os.temp.dir(prefix = "config-credential-invalid-")
    try intercept[IllegalArgumentException] {
      ConfigCredentialStore.resolve(root, "secret:config-../../escape", "test-reader", (_, _) => ())
    }
    finally os.remove.all(root)
  }
