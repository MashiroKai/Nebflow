package nebflow.neblink

import munit.FunSuite
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

class PortalWsMessageAssemblerSpec extends FunSuite:

  test("UTF-16 surrogate split across callbacks is encoded only after joining"):
    val a = new PortalWsMessageAssembler(16)
    assertEquals(a.text("配置\uD83D", false), Right(None))
    val out = a.text("\uDE00", true).toOption.flatten.get
    assertEquals(new String(out, UTF_8), "配置😀")

  test("UTF-8 byte cap is enforced even when the character count is smaller"):
    val a = new PortalWsMessageAssembler(3)
    assertEquals(a.text("😀", true), Left("message-too-large"))
    assertEquals(new String(a.text("ok", true).toOption.flatten.get, UTF_8), "ok")

  test("unterminated text and binary fragments cannot grow without bound"):
    val a = new PortalWsMessageAssembler(4)
    assertEquals(a.text("1234", false), Right(None))
    assertEquals(a.text("5", false), Left("message-too-large"))
    assertEquals(a.binary(ByteBuffer.wrap(Array[Byte](1, 2, 3, 4)), false), Right(None))
    assertEquals(a.binary(ByteBuffer.wrap(Array[Byte](5)), true), Left("message-too-large"))

  test("binary fragments join and successive messages remain separate"):
    val a = new PortalWsMessageAssembler(8)
    assertEquals(a.binary(ByteBuffer.wrap(Array[Byte](1, 2)), false), Right(None))
    assertEquals(a.binary(ByteBuffer.wrap(Array[Byte](3)), true).toOption.flatten.get.toList, List[Byte](1, 2, 3))
    assertEquals(new String(a.text("next", true).toOption.flatten.get, UTF_8), "next")

  test("mixed fragment kinds are refused and buffered content is discarded"):
    val a = new PortalWsMessageAssembler(8)
    assertEquals(a.text("half", false), Right(None))
    assertEquals(a.binary(ByteBuffer.wrap(Array[Byte](1)), true), Left("mixed-message-fragments"))
    assertEquals(new String(a.text("new", true).toOption.flatten.get, UTF_8), "new")
end PortalWsMessageAssemblerSpec
